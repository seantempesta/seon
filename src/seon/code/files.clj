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
  (and (str/starts-with? path "resources/seon/schemas/")
       (str/ends-with? path ".edn")))

(defn config-file-path? [^String path]
  (= path "resources/seon/config/default.edn"))

(defn task-file-path? [^String path]
  (and (str/starts-with? path "resources/seon/tasks/")
       (str/ends-with? path ".edn")))

(defn agent-file-path? [^String path]
  (and (str/starts-with? path "resources/seon/agents/")
       (str/ends-with? path ".edn")))

(defn task-file-prefix [^String path]
  (when (task-file-path? path)
    (let [s (subs path (count "resources/seon/tasks/"))]
      (subs s 0 (- (count s) 4)))))

(defn task-owner-path [task-id]
  (when (qualified-keyword? task-id)
    (str "resources/seon/tasks/" (namespace task-id) ".edn")))

(def durable-agent-keys
  [:seon.agent/id :seon.agent/parent :seon.agent/entry? :seon.agent/label
   :seon.agent/hero-title :seon.agent/read-branch :seon.agent/router?
   :seon.agent/seed-path :seon.agent/scope :seon.agent/focus :seon.agent/branch
   :seon.agent/task :seon.agent/role :seon.agent/goal :seon.agent/system-prompt
   :seon.agent/render-fn :seon.agent/examples :seon.agent/pinned
   :seon.agent/auto-merge? :seon.agent/context-prefs :seon.ctx/pattern])

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
  "Parses `resources/seon/tasks/<ns>.edn` into `:seon.task/*` transaction data, retracting
   any tasks in that namespace removed from the file."
  [db path text]
  (let [raw (edn/read-string text)
        items (cond (vector? raw) raw (map? raw) [raw] :else [])
        prefix (task-file-prefix path)
        new-ids (into #{} (keep :seon.task/id) items)
        stale (when (and db prefix (contains? (:schema db) :seon.task/id))
                (for [dt (d/datoms db :aevt :seon.task/id)
                      :let [tid (:v dt)]
                      :when (and (= prefix (namespace tid))
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
   never clobbers in-flight work."
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
              eval-ops (keep (fn [ev]
                               (let [eid (or (:seon.agent.eval/id ev)
                                             (hasch/uuid [:seed-eval aid (:seon.agent.eval/turn ev) (:seon.agent.eval/call-index ev 0)]))]
                                 (when-not (and has-eval-schema?
                                                (first (d/datoms db :avet :seon.agent.eval/id eid)))
                                   (assoc ev :seon.agent.eval/id eid :seon.agent.eval/agent aid))))
                             evals)]
          (concat ns-ops [ent] eval-ops)))
      items))))

(defn import-schema-file-tx [path text]
  (let [m (edn/read-string text)
        ents (mapv (fn [[k f]]
                     (let [props (when (and (vector? f) (map? (second f))) (second f))]
                       (cond-> {:seon.schema/key k :seon.schema/form f}
                         (:seon.schema/doc props) (assoc :seon.schema/doc (str (:seon.schema/doc props)))
                         (:seon.render/hiccup props) (assoc :seon.schema/render (:seon.render/hiccup props)))))
                   (sort-by key m))]
    (conj ents {:seon.code.file/path path :seon.code.file/text text :seon.code.file/exec? false})))

(defn render-tasks-file
  "Renders all `:seon.task/*` entities belonging to `path` (`resources/seon/tasks/<ns>.edn`)."
  [db path]
  (when-let [prefix (task-file-prefix path)]
    (when (contains? (:schema db) :seon.task/id)
      (let [tasks (->> (d/datoms db :aevt :seon.task/id)
                       (filter #(= prefix (namespace (:v %))))
                       (map #(dissoc (d/pull db '[*] (:e %)) :db/id))
                       (map (fn [t]
                              (cond-> t
                                (contains? t :seon.task/needs) (update :seon.task/needs set)
                                (contains? t :seon.task/tests) (update :seon.task/tests set))))
                       (sort-by #(str (:seon.task/id %)))
                       vec)]
        (when (seq tasks)
          (str ";; Declarative Seon tasks for `" prefix "` (loaded on fresh install, written back on green merge).\n"
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
                 ks (keys store/default-config-values)]
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
  "Renders all `:seon.schema/key` entities whose owner path is `path` into a clean EDN map,
   preserving `:seon.schema/doc` and `:seon.render/hiccup`."
  [db path]
  (let [matching (->> (d/datoms db :aevt :seon.schema/key)
                      (map #(d/pull db '[:seon.schema/key :seon.schema/form :seon.schema/doc :seon.schema/render] (:e %)))
                      (filter #(= path (schema/schema-owner-path (:seon.schema/key %))))
                      (sort-by #(str (:seon.schema/key %)))
                      vec)]
    (when (seq matching)
      (str ";; Co-located Malli schemas (" path ")\n"
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
           "}\n"))))
