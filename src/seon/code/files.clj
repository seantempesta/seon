(ns seon.code.files
  "Declarative Datahike <-> Disk/Git persistence for tasks, seed agents, config, and Malli schemas:
   - `resources/seon/tasks/<ns>.edn` <-> `:seon.task/*` entities
   - `resources/seon/agents/<name>.edn` <-> durable `:seon.agent/*` definitions (preserving live runtime state)
   - `resources/seon/config/default.edn` <-> `:seon.config/root` (Aero-compatible round-trip)
   - `resources/seon/schemas/<area>.edn` <-> `:seon.schema/*` entities (preserving docs & renderers)"
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [fipp.edn :as fipp]
            [hasch.core :as hasch]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn schema-file-path? [^String path]
  (or (= path "resources/seon/schemas.edn")
      (and (str/starts-with? path "resources/seon/schemas/")
           (str/ends-with? path ".edn"))))

(defn config-file-path? [^String path]
  (= path "resources/seon/config/default.edn"))

(defn task-file-path? [^String path]
  (or (= path "resources/seon/tasks.edn")
      (and (str/starts-with? path "resources/seon/tasks/")
           (str/ends-with? path ".edn"))))

(defn agent-file-path? [^String path]
  (and (str/starts-with? path "resources/seon/agents/")
       (str/ends-with? path ".edn")))

(defn task-file-prefix [^String path]
  (when (and (str/starts-with? path "resources/seon/tasks/")
             (str/ends-with? path ".edn"))
    (let [s (subs path (count "resources/seon/tasks/"))]
      (subs s 0 (- (count s) 4)))))

(defn task-owner-path [task-id]
  (when (qualified-keyword? task-id)
    "resources/seon/tasks.edn"))

(def durable-agent-keys
  [:seon.agent/id :seon.agent/parent :seon.agent/entry? :seon.agent/label
   :seon.agent/hero-title :seon.agent/read-branch :seon.agent/router?
   :seon.agent/seed-path :seon.agent/scope :seon.agent/focus :seon.agent/branch
   :seon.agent/task :seon.agent/role :seon.agent/goal :seon.agent/system-prompt
   :seon.agent/render-fn :seon.agent/examples :seon.agent/pinned
   :seon.agent/auto-merge? :seon.agent/context-prefs :seon.agent/tail-renders :seon.config/viewport-screenshot? :seon.config/max-tool-turns :seon.ctx/pattern])

(def initial-only-agent-keys
  [:seon.agent/paused? :seon.agent/waiting? :seon.agent/ready?])

(defn- example-agent? [aid]
  (and (keyword? aid) (= "example" (namespace aid))))

(defn- pprint-edn [x]
  (with-out-str (fipp/pprint x {:width 100})))

(defn- ns-chain-for-focus [nsym]
  (loop [cur nsym, acc []]
    (let [s (name cur)
          idx (.lastIndexOf s ".")
          p (when (pos? idx) (symbol (subs s 0 idx)))
          tid (str "ns/" cur)]
      (if p
        (recur p (conj acc {:db/id tid :seon.code.ns/name cur :seon.code.ns/parent (str "ns/" p)}))
        (vec (rseq (conj acc {:db/id tid :seon.code.ns/name cur})))))))

(defn import-tasks-tx
  "Parses `resources/seon/tasks.edn` (or legacy `resources/seon/tasks/<ns>.edn`) into `:seon.task/*`
   transaction data, retracting tasks removed from the file."
  [db path text]
  (let [raw (edn/read-string text)
        items (cond (vector? raw) raw (map? raw) [raw] :else [])
        prefix (task-file-prefix path)
        global? (= path "resources/seon/tasks.edn")
        new-ids (into #{} (keep :seon.task/id) items)
        stale (when (and db (or global? prefix) (contains? (:schema db) :seon.task/id))
                (for [dt (d/datoms db :aevt :seon.task/id)
                      :let [tid (:v dt)]
                      :when (and (or global? (= prefix (namespace tid)))
                                 (not (contains? new-ids tid)))]
                  [:db/retractEntity [:seon.task/id tid]]))
        retract-sets
        (when (and db (contains? (:schema db) :seon.task/id))
          (mapcat
           (fn [t]
             (when-let [eid (:e (first (d/datoms db :avet :seon.task/id (:seon.task/id t))))]
               (mapcat
                (fn [a]
                  (let [old-s (into #{} (map :v) (d/datoms db :eavt eid a))
                        new-s (set (get t a #{}))]
                    (for [gone (set/difference old-s new-s)]
                      [:db/retract [:seon.task/id (:seon.task/id t)] a gone])))
                [:seon.task/needs :seon.task/tests])))
           items))]
    (vec (concat stale retract-sets items))))

(defn import-agents-tx
  "Parses `resources/seon/agents/*.edn` into durable `:seon.agent/*` (and optional example `:evals`)
   transaction data. Preserves runtime state on already-existing live agents so resume/catch-up
   never clobbers in-flight work, and keeps example `:evals` idempotent (no duplicate tool_call_ids)."
  [db path text]
  (let [raw (edn/read-string text)
        items (cond (vector? raw) raw (map? raw) [raw] :else [])
        has-agent-schema? (boolean (and db (contains? (:schema db) :seon.agent/id)))
        has-eval-schema?  (boolean (and db (contains? (:schema db) :seon.agent.eval/id)))
        has-ns-schema?    (boolean (and db (contains? (:schema db) :seon.code.ns/name)))
        ns-seen (volatile! #{})]
    (vec
     (mapcat
      (fn [entry]
        (let [aid (:seon.agent/id entry)
              evals (:evals entry)
              exists? (and has-agent-schema? (some? (first (d/datoms db :avet :seon.agent/id aid))))
              keep-ks (if (or (not exists?) (example-agent? aid))
                        (into durable-agent-keys initial-only-agent-keys)
                        durable-agent-keys)
              base-ent (-> (select-keys entry keep-ks)
                           (assoc :seon.agent/seed-path path))
              focus (:seon.agent/focus base-ent)
              focus-ns (when (and (vector? focus) (= :seon.code.ns/name (first focus)))
                         (second focus))
              focus-exists? (and focus-ns has-ns-schema?
                                 (some? (first (d/datoms db :avet :seon.code.ns/name focus-ns))))
              ns-ops (when (and focus-ns (not focus-exists?) (not (contains? @ns-seen focus-ns)))
                       (vswap! ns-seen conj focus-ns)
                       (ns-chain-for-focus focus-ns))
              ent (if (and focus-ns (not focus-exists?))
                    (assoc base-ent :seon.agent/focus (str "ns/" focus-ns))
                    base-ent)
              eval-ents (mapv (fn [ev]
                                (let [eid (or (:seon.agent.eval/id ev)
                                              (hasch/uuid [:seed-eval aid (:seon.agent.eval/turn ev) (:seon.agent.eval/call-index ev 0)]))]
                                  (assoc ev :seon.agent.eval/id eid :seon.agent.eval/agent aid)))
                              evals)
              desired-eids (into #{} (map :seon.agent.eval/id) eval-ents)
              stale-eval-ops (when (and (example-agent? aid) (seq evals) has-eval-schema?
                                        (contains? (:schema db) :seon.agent.eval/agent))
                               (keep (fn [dt]
                                       (let [ev-id (:seon.agent.eval/id (d/pull db '[:seon.agent.eval/id] (:e dt)))]
                                         (when (and ev-id (not (contains? desired-eids ev-id)))
                                           [:db/retractEntity [:seon.agent.eval/id ev-id]])))
                                     (d/datoms db :avet :seon.agent.eval/agent aid)))
              eval-ops (keep (fn [ev]
                               (when-not (and has-eval-schema?
                                              (first (d/datoms db :avet :seon.agent.eval/id (:seon.agent.eval/id ev))))
                                 ev))
                             eval-ents)]
          (concat ns-ops [ent] stale-eval-ops eval-ops)))
      items))))

(defn import-schema-file-tx
  "Parses `resources/seon/schemas.edn` (or legacy `resources/seon/schemas/*.edn`) into pure\n               enriched `:seon.schema/*` entities (zero `:seon.code.file/text` shadow blob!)."
  {:malli/schema
   [:function
    [:=> [:cat :string :string] [:vector :any]]
    [:=>
     [:cat [:maybe :seon.host/db] :string :string]
     [:vector :any]]]}
  ([path text] (import-schema-file-tx nil path text))
  ([db _path text]
    (let [m (edn/read-string text)
          {:keys [registry]} (schema/registry-for-db db m)]
      (schema/enrich-schema-entities-tx db m registry))))

(defn render-tasks-file
  "Renders `:seon.task/*` entities belonging to `path` (`resources/seon/tasks.edn` = all tasks)."
  [db path]
  (let [global? (= path "resources/seon/tasks.edn")
        prefix (task-file-prefix path)]
    (when (and (or global? prefix) (contains? (:schema db) :seon.task/id))
      (let [tasks (->> (d/datoms db :aevt :seon.task/id)
                       (filter #(or global? (= prefix (namespace (:v %)))))
                       (map #(dissoc (d/pull db '[*] (:e %)) :db/id))
                       (map (fn [t]
                              (cond-> t
                                (contains? t :seon.task/needs) (update :seon.task/needs set)
                                (contains? t :seon.task/tests) (update :seon.task/tests set))))
                       (sort-by #(str (:seon.task/id %)))
                       vec)]
        (when (seq tasks)
          (str (if global?
                 ";; Declarative Seon tasks (resources/seon/tasks.edn — loaded on fresh install, written back on green merge).\n"
                 (str ";; Declarative Seon tasks for `" prefix "` (loaded on fresh install, written back on green merge).\n"))
               (pprint-edn tasks)))))))

(defn render-agent-file
  "Renders durable `:seon.agent/*` definitions tagged with `:seon.agent/seed-path path`."
  [db path]
  (when (and (agent-file-path? path) (contains? (:schema db) :seon.agent/seed-path))
    (let [agents (->> (d/datoms db :aevt :seon.agent/seed-path)
                      (filter #(= path (:v %)))
                      (map #(d/pull db '[*] (:e %)))
                      (sort-by #(str (:seon.agent/id %)))
                      vec)]
      (when (seq agents)
        (let [entries
              (mapv (fn [ag]
                      (let [aid (:seon.agent/id ag)
                            ex? (example-agent? aid)
                            ks (if ex?
                                 (into durable-agent-keys initial-only-agent-keys)
                                 durable-agent-keys)
                            base (select-keys ag ks)
                            focus (:seon.agent/focus base)
                            focus' (if (and (map? focus) (:db/id focus))
                                     (when-let [nsym (:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:db/id focus)))]
                                       [:seon.code.ns/name nsym])
                                     focus)
                            clean (cond-> (dissoc base :seon.agent/seed-path)
                                    focus' (assoc :seon.agent/focus focus')
                                    (contains? base :seon.agent/examples) (update :seon.agent/examples set))
                            evals (when (and ex? (contains? (:schema db) :seon.agent.eval/agent))
                                    (->> (d/datoms db :avet :seon.agent.eval/agent aid)
                                         (map #(dissoc (d/pull db '[*] (:e %)) :db/id :seon.agent.eval/agent))
                                         (sort-by (juxt #(or (:seon.agent.eval/turn %) 0)
                                                        #(or (:seon.agent.eval/call-index %) 0)))
                                         vec))]
                        (cond-> clean (seq evals) (assoc :evals evals))))
                    agents)]
          (str ";; Declarative Seon agent definitions (" path " — loaded on fresh install, written back on green merge).\n"
               (pprint-edn entries)))))))

(defn render-config-file
  "Renders `resources/seon/config/default.edn` from `:seon.config/root`.
   Preserves existing Aero `#env` file text verbatim when all config values match; renders
   updated EDN cleanly whenever a config value changes inside Seon."
  [db path]
  (let [stored-txt (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path]))
        cur (when (contains? (:schema db) :seon.config/key)
              (dissoc (d/pull db '[*] [:seon.config/key :seon.config/root]) :db/id :seon.config/key))]
    (cond
      (empty? cur) stored-txt
      (and stored-txt
           (let [parsed (try (dissoc (store/read-aero-config-tx stored-txt) :seon.config/key)
                             (catch Throwable _ nil))
                 ks (filter #(contains? (:schema db) %) (keys store/default-config-values))]
             (= (select-keys cur ks) (select-keys parsed ks))))
      stored-txt
      :else
      (let [extras (into {}
                         (map (fn [[k v]]
                                [k (try (edn/read-string v) (catch Throwable _ v))]))
                         (:seon.config/extra cur))
            m (into (sorted-map) (merge (dissoc cur :seon.config/extra) extras))]
        (str ";; Seon system default configuration (rendered from Datahike :seon.config/root on merge).\n"
             (pprint-edn m))))))

(defn render-schema-file
  "Renders global `:seon.schema/key` entities into `resources/seon/schemas.edn` cleanly
   (preserving `:seon.schema/doc` and `:seon.render/hiccup`, zero `#:ns{}` shorthand)."
  [db path]
  (let [global? (= path "resources/seon/schemas.edn")
        matching (->> (d/datoms db :aevt :seon.schema/key)
                      (map #(d/pull db '[:seon.schema/key :seon.schema/form :seon.schema/doc :seon.schema/render] (:e %)))
                      (filter #(or global? (= path (schema/schema-owner-path (:seon.schema/key %)))))
                      (sort-by #(str (:seon.schema/key %)))
                      vec)]
    (when (seq matching)
      (binding [*print-namespace-maps* false]
        (str ";; Global Malli schemas (" path " — Datahike attributes & renderers derived automatically)\n"
             "{"
             (str/join "\n\n "
                       (map (fn [{:keys [:seon.schema/key :seon.schema/form :seon.schema/doc :seon.schema/render]}]
                              (let [f' (if (and (vector? form) (or doc render))
                                         (let [has-props? (map? (second form))
                                               props (cond-> (if has-props? (second form) {})
                                                       (and doc (not (:seon.schema/doc (if has-props? (second form) {}))))
                                                       (assoc :seon.schema/doc doc)
                                                       (and render (not (:seon.render/hiccup (if has-props? (second form) {}))))
                                                       (assoc :seon.render/hiccup render))]
                                           (if has-props?
                                             (assoc form 1 props)
                                             (into [(first form) props] (rest form))))
                                         form)]
                                (str (pr-str key) "\n " (pr-str f'))))
                            matching))
             "}\n")))))

(defn rule-file-path?
  "True when `path` is a declarative Seon rules file (`resources/seon/rules.edn` or `resources/seon/rules/*.edn`)."
  {:malli/schema [:=> [:cat :string] :boolean]
   :cost/ticks 5000}
  [^String path]
  (or (= path "resources/seon/rules.edn")
      (and (str/starts-with? path "resources/seon/rules/")
           (str/ends-with? path ".edn"))))

(defn import-rules-tx
  "Parses `resources/seon/rules.edn` into `:seon.rule/*` transaction data, retracting removed rules."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :string :string] [:vector :any]]
   :cost/ticks 2000000}
  [db path text]
  (let [raw (edn/read-string text)
        items (cond (vector? raw) raw (map? raw) [raw] :else [])
        global? (= path "resources/seon/rules.edn")
        new-ids (into #{} (keep :seon.rule/id) items)
        stale (when (and db global? (contains? (:schema db) :seon.rule/id))
                (for [dt (d/datoms db :aevt :seon.rule/id)
                      :let [rid (:v dt)]
                      :when (not (contains? new-ids rid))]
                  [:db/retractEntity [:seon.rule/id rid]]))]
    (vec (concat stale items))))

(defn render-rules-file
  "Renders all `:seon.rule/*` entities into clean, sorted EDN for `resources/seon/rules.edn`."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :string]]
   :cost/ticks 2000000}
  [db path]
  (when (and (= path "resources/seon/rules.edn") (contains? (:schema db) :seon.rule/id))
    (let [rules (->> (d/datoms db :aevt :seon.rule/id)
                     (map #(dissoc (d/pull db '[*] (:e %)) :db/id))
                     (filter :seon.rule/id)
                     (sort-by (juxt #(long (or (:seon.rule/stratum %) 50)) #(str (:seon.rule/id %))))
                     vec)]
      (when (seq rules)
        (binding [*print-namespace-maps* false]
          (str ";; Declarative Seon logic rules (resources/seon/rules.edn — loaded on fresh install, written back on green merge).\n"
               (pprint-edn rules)))))))
