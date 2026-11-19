(ns seon.render.wire
  "Single-source LLM wire context & ephemeral `<neighborhood-activity>` request builder."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]
            [fipp.edn :as fipp]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.model :as model]
            [seon.reconciler :as reconciler]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata :refer [agent-evals-in agent-messages-in compact-entity-preview evals->turns first-doc-line pull-agent-entity router-agent?]]
            [seon.render.markdown :as rmd]
            [seon.render.tiles :as rtiles]
            [seon.render.ns-tile :as rnstile]))

(defn neighborhood-recent-activity
  "Builds a concise ephemeral tail summary of LIVE activity relevant to this agent's neighborhood:
   1. Active incoming & outgoing `:seon.agent.call/*` entries (always fresh, zero KV-cache churn).
   2. Recent live Datahike records matching this agent's working schemas (capped cleanly).
   3. Live runtime, test, perf-outlier & eval-efficiency alerts routed to this agent's scope."
  [main-db branch-db ag]
  (let [aid (:seon.agent/id ag)
        scope (:seon.agent/scope ag)
        router? (router-agent? ag)
        prefs (or (:seon.agent/context-prefs ag) {})
        rec-limit (long (or (:recent-data-limit prefs) 4))
        scope-schema-keys
        (fn [db sc]
          (when (and db sc (not router?) (contains? (:schema db) :seon.schema/key))
            (let [has-kw? (contains? (:schema db) :seon.code.var/keywords)
                  freq (volatile! {})
                  bump! (fn [k w] (when (qualified-keyword? k) (vswap! freq update k (fnil + 0) w)))
                  nid (when (contains? (:schema db) :seon.code.ns/name)
                        (:db/id (d/pull db '[:db/id] [:seon.code.ns/name sc])))
                  child-nss (when (and nid (contains? (:schema db) :seon.code.ns/parent))
                              (mapv #(:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e %)))
                                    (d/datoms db :avet :seon.code.ns/parent nid)))
                  scan-nss (cons sc (remove nil? child-nss))
                  proj (if has-kw? '[:seon.code.var/keywords :seon.code.var/schema-refs]
                           '[:seon.code.var/schema-refs])
                  ns-str (name sc)
                  owned-prefix (str ns-str ".")]
              (doseq [sn scan-nss
                      dt (d/datoms db :avet :seon.code.var/ns sn)
                      :let [v (d/pull db proj (:e dt))]]
                (doseq [k (:seon.code.var/schema-refs v)] (bump! k 3))
                (doseq [k (:seon.code.var/keywords v)] (bump! k 1)))
              (doseq [dt (d/datoms db :avet :seon.schema/key)
                      :let [k (:v dt)
                            kns (namespace k)]
                      :when (and kns (or (= kns ns-str) (str/starts-with? kns owned-prefix)))]
                (bump! k 2))
              (->> @freq
                   (sort-by (fn [[k w]] [(- w) (str k)]))
                   (take 80)
                   (mapv key)))))
        lines (volatile! [])
        add! (fn [s] (when-not (str/blank? s) (vswap! lines conj s)))]
    (when (and aid (contains? (:schema main-db) :seon.agent.call/callee))
      (let [incoming (->> (d/datoms main-db :avet :seon.agent.call/callee aid)
                          (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                  :seon.agent.call/status :seon.agent.call/prompt
                                                  :seon.agent.call/result-schema] (:e %)))
                          (filter #(= :pending (:seon.agent.call/status %)))
                          vec)
            outgoing (->> (d/datoms main-db :avet :seon.agent.call/caller aid)
                          (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/callee
                                                  :seon.agent.call/status :seon.agent.call/created-tx] (:e %)))
                          (sort-by #(or (:seon.agent.call/created-tx %) 0))
                          (take-last 5)
                          vec)]
        (when (seq incoming)
          (add! "Open incoming calls to answer via (user/answer! call-id val):")
          (doseq [c incoming]
            (add! (str "  - " (pr-str (:seon.agent.call/id c)) " from " (:seon.agent.call/caller c)
                       (when (:seon.agent.call/result-schema c) (str " schema=" (:seon.agent.call/result-schema c)))
                       ": " (first-doc-line (:seon.agent.call/prompt c) 100)))))
        (when (seq outgoing)
          (add! "Recent outgoing calls:")
          (doseq [c outgoing]
            (add! (str "  - " (pr-str (:seon.agent.call/id c)) " -> " (:seon.agent.call/callee c)
                       " [" (name (:seon.agent.call/status c :pending)) "]"))))))
    (when (and scope (pos? rec-limit))
      (let [is-meta-scope? (or (= scope 'seon.code) (= scope 'seon.schema))
            extra-set (set (:extra-schemas prefs))
            router-defaults (when router?
                              [:seon.agent.call/id :seon.task/id :seon.test.run/branch
                               :seon.health.fault/id :seon.agent/id])
            working-db (if (and branch-db (contains? (:schema branch-db) :seon.code.var/keywords)
                                (first (d/datoms branch-db :avet :seon.code.var/keywords)))
                         branch-db
                         main-db)
            working (concat (map (fn [k] {:key k}) (:extra-schemas prefs))
                            (map (fn [k] {:key k}) router-defaults)
                            (map (fn [k] {:key k}) (scope-schema-keys working-db scope)))
            candidate-attrs (->> working
                                 (map :key)
                                 distinct
                                 (filter #(and (contains? (:schema main-db) %)
                                               (first (take 1 (d/datoms main-db :avet %)))))
                                 (remove (fn [k]
                                           (and (not (contains? extra-set k))
                                                (let [ns-s (namespace k)]
                                                  (or (= ns-s "seon.agent.eval")
                                                      (= ns-s "seon.config")
                                                      (and (not is-meta-scope?)
                                                           (or (str/starts-with? ns-s "seon.code")
                                                               (= ns-s "seon.schema"))))))))
                                 (reduce (fn [[seen-ns acc] k]
                                           (let [kns (namespace k)]
                                             (if (or (>= (count acc) 6) (contains? seen-ns kns))
                                               [seen-ns acc]
                                               [(conj seen-ns kns) (conj acc k)])))
                                         [#{} []])
                                 second)
            newest-2 (fn [a]
                       (reduce (fn [acc dt]
                                 (if (< (count acc) 2)
                                   (conj acc dt)
                                   (vec (rest (sort-by :tx (conj acc dt))))))
                               []
                               (take 250 (d/datoms main-db :avet a))))
            samples (->> candidate-attrs
                         (mapcat (fn [a]
                                   (map (fn [dt] {:attr a :tx (:tx dt) :eid (:e dt)})
                                        (newest-2 a))))
                         (sort-by (comp - :tx))
                         (reduce (fn [[seen acc] item]
                                   (if (or (>= (count acc) rec-limit) (contains? seen (:eid item)))
                                     [seen acc]
                                     [(conj seen (:eid item)) (conj acc item)]))
                                 [#{} []])
                         second)]
        (when (seq samples)
          (add! (str "Recent live data matching " scope " schemas:"))
          (doseq [{:keys [attr tx eid]} samples]
            (let [ent (d/pull main-db '[*] eid)]
              (add! (str "  - tx " tx " (" attr "): " (compact-entity-preview ent 150))))))))
    (when scope
      (let [live-probs (health/live-problems-for-scope main-db branch-db scope aid)]
        (when (seq live-probs)
          (add! (if router?
                  "Live system health & efficiency alerts (delegate to owning :ns/<subsys> specialist):"
                  (str "Live health & efficiency alerts for " scope ":")))
          (doseq [p (take 6 live-probs)
                  :let [owner (health/owner-specialist-id (:ns p))]]
            (add! (str "  - [" (name (:severity p :warning)) " :" (name (:class p)) "] "
                       (:subject p)
                       (when (and router? owner) (str " (owner " owner ")"))
                       " — " (:message p)))))))
    (when (seq @lines)
      (str/join "\n" @lines))))

(defonce ^:seon.flow/edge wire-request-cache
  (atom {}))

(defn- wire-datoms-sig [ds]
  (reduce (fn [[n mx] dt] [(inc n) (max mx (long (:tx dt 0)))]) [0 0] ds))

(defn- wire-db-sig [db agent-id]
  (let [s (:schema db)]
    [(count s)
     (when (contains? s :seon.agent/id)
       (d/pull db '[:seon.agent/goal :seon.agent/system-prompt :seon.agent/scope
                    :seon.agent/branch :seon.agent/examples :seon.agent/pinned
                    :seon.agent/context-prefs :seon.agent/router?]
               [:seon.agent/id agent-id]))
     (when (contains? s :seon.agent/id) (wire-datoms-sig (d/datoms db :aevt :seon.agent/id)))
     (when (contains? s :seon.agent.eval/agent) (wire-datoms-sig (d/datoms db :avet :seon.agent.eval/agent agent-id)))
     (when (contains? s :seon.agent.message/agent) (wire-datoms-sig (d/datoms db :avet :seon.agent.message/agent agent-id)))
     (when (contains? s :seon.agent.call/callee) (wire-datoms-sig (d/datoms db :avet :seon.agent.call/callee agent-id)))
     (when (contains? s :seon.agent.call/caller) (wire-datoms-sig (d/datoms db :avet :seon.agent.call/caller agent-id)))
     (when (contains? s :seon.health.fault/id) (wire-datoms-sig (d/datoms db :aevt :seon.health.fault/id)))
     (when (contains? s :seon.task/id) (wire-datoms-sig (d/datoms db :aevt :seon.task/id)))
     (when (contains? s :seon.code.form/hash) (wire-datoms-sig (d/datoms db :aevt :seon.code.form/hash)))
     (when (contains? s :seon.schema/key) (wire-datoms-sig (d/datoms db :aevt :seon.schema/key)))
     (when (contains? s :seon.test/pass?) (wire-datoms-sig (d/datoms db :aevt :seon.test/pass?)))]))

(defn wire-cache-key [main-db branch-db agent-id vision? viewport-screenshot]
  (when (and main-db branch-db
             (not (instance? datahike.db.AsOfDB main-db))
             (not (instance? datahike.db.AsOfDB branch-db))
             (not (instance? datahike.db.HistoricalDB main-db))
             (not (instance? datahike.db.HistoricalDB branch-db)))
    (let [mcid (try (d/commit-id main-db) (catch Throwable _ nil))
          bcid (try (d/commit-id branch-db) (catch Throwable _ nil))]
      (when (and mcid bcid)
        [agent-id
         (wire-db-sig main-db agent-id)
         (if (= mcid bcid) :same-db [bcid (:max-tx branch-db)])
         (boolean vision?)
         (when viewport-screenshot
           [(:width viewport-screenshot) (:height viewport-screenshot)
            (:bytes viewport-screenshot) (:captured-at viewport-screenshot)
            (hash (:data-url viewport-screenshot)) (hash (:base64 viewport-screenshot))])]))))

(defn build-live-wire-request
  "Single source of truth for an agent's LLM context AND Debug View:
   Walks `branch-db`, projects discovered tiles via `render/render-value` -> `render/hiccup->text`,
   frames `:seon.agent/examples` in `<session kind=\"example\">...</session>`, and attaches ephemeral
   tail `<neighborhood-activity>` + `<live-viewport>` without polluting the static KV-cache prefix."
  [main-db branch-db agent-id {:keys [viewport-screenshot vision?]}]
  (let [ck (wire-cache-key main-db branch-db agent-id vision? viewport-screenshot)]
    (if-let [hit (when ck (get @wire-request-cache ck))]
      hit
      (let [ag (pull-agent-entity main-db branch-db agent-id)
            evals (agent-evals-in branch-db agent-id)
            msgs (agent-messages-in main-db agent-id)
            seen-ids (store/agent-seen-message-ids branch-db agent-id)
            unread (filterv #(not (contains? seen-ids (:seon.agent.message/id %))) msgs)
            tiles (render/discover-renderable-tiles main-db branch-db ag evals health/report)
            tile-projections
            (mapv (fn [{:keys [val] :as tile}]
                    (let [h (render/render-value branch-db val {:mode :agent :compact? true :main-db main-db :agent ag})
                          txt (render/hiccup->text h)]
                      (assoc tile :text txt)))
                  tiles)
            walk-text (str/join "\n\n---\n\n"
                                (map (fn [{:keys [schema source handle text]}]
                                       (str "### [" (name source)
                                            (when handle (str " " handle))
                                            " · " schema "]\n" text))
                                     tile-projections))
            activity-text (neighborhood-recent-activity main-db branch-db ag)
            examples (vec
                      (for [ex-id (sort (:seon.agent/examples ag))
                            :when (first (d/datoms main-db :avet :seon.agent/id ex-id))
                            :let [ex-ag (d/pull main-db '[:seon.agent/id :seon.agent/goal] [:seon.agent/id ex-id])
                                  ex-evals (agent-evals-in main-db ex-id)]]
                        {:id ex-id
                         :goal (:seon.agent/goal ex-ag)
                         :turns (evals->turns ex-evals)}))
            wire (model/build-agent-wire-messages
                  {:thinking? true :replay-reasoning? true :vision? (boolean vision?)}
                  {:agent-id agent-id
                   :branch (:seon.agent/branch ag :main)
                   :role (:seon.agent/role ag :worker)
                   :goal (:seon.agent/goal ag)
                   :system-prompt (:seon.agent/system-prompt ag)
                   :examples examples
                   :walk-text walk-text
                   :activity-text activity-text
                   :history (evals->turns evals (into {} (map (juxt :seon.agent.message/id identity)) msgs))
                   :unread unread
                   :viewport-screenshot viewport-screenshot})
            res (assoc wire
                       :agent ag
                       :tiles tile-projections
                       :activity-text activity-text
                       :evals evals
                       :messages-inbox msgs
                       :unread unread)]
        (when ck
          (swap! wire-request-cache
                 (fn [m]
                   (let [m' (if (>= (count m) 4) {} m)]
                     (assoc m' ck res)))))
        res))))
