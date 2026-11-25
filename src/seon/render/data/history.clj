(ns seon.render.data.history
  "Generic entity-derived `d/as-of` timeline history queries for `seon.render.data`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store :refer [db-schema]]))

(defonce ^:private ^:seon.flow/edge view-history-cache (store/lru-cache 32))

(def ^:private agent-control-attrs
  #{:seon.agent/id :seon.agent/branch :seon.agent/interactive? :seon.agent/label :seon.agent/entry?
    :seon.agent/parent :seon.agent/waiting? :seon.agent/paused? :seon.agent/ready?
    :seon.agent/awaiting :seon.agent/archived? :seon.agent/auto-merge? :seon.agent/read-branch
    :seon.agent/router? :seon.agent/tested-tx :seon.agent/merged-tx :seon.agent/last-proofs
    :seon.agent/hero-title :seon.agent/system-prompt :seon.agent/scope :seon.agent/focus})

(defn- resolve-ref-eid [bdb mdb x]
  (cond
    (integer? x) x
    (and (vector? x) (= 2 (count x)) (qualified-keyword? (first x)))
    (let [[a v] x]
      (or (when (and bdb (contains? (db-schema bdb) a)) (:e (first (d/datoms bdb :avet a v))))
          (when (and mdb (not= mdb bdb) (contains? (db-schema mdb) a)) (:e (first (d/datoms mdb :avet a v))))))
    :else nil))

(defn- unpack-refs [v]
  (cond (and (vector? v) (= 2 (count v)) (qualified-keyword? (first v))) [v]
        (coll? v) v
        (some? v) [v]
        :else []))

(defn- linked-agent-eids [bdb mdb hdb hmdb eid meid agent-id]
  (let [bs (db-schema bdb) ms (db-schema mdb)
        raw-refs (concat
                  (when (and eid (bs :seon.agent/tasks)) (map :v (d/datoms bdb :eavt eid :seon.agent/tasks)))
                  (when (and meid (ms :seon.agent/tasks)) (map :v (d/datoms mdb :eavt meid :seon.agent/tasks)))
                  (when (and eid (bs :seon.agent/pinned)) (mapcat #(unpack-refs (:v %)) (filter :added (d/datoms hdb :eavt eid :seon.agent/pinned))))
                  (when (and meid (ms :seon.agent/pinned)) (mapcat #(unpack-refs (:v %)) (filter :added (d/datoms hmdb :eavt meid :seon.agent/pinned))))
                  (when (and eid (bs :seon.agent/curated)) (mapcat #(unpack-refs (:v %)) (filter :added (d/datoms hdb :eavt eid :seon.agent/curated)))))
        err-eids (concat
                  (when (bs :seon.error/agent) (map :e (d/datoms bdb :avet :seon.error/agent agent-id)))
                  (when (and (not= mdb bdb) (ms :seon.error/agent)) (map :e (d/datoms mdb :avet :seon.error/agent agent-id))))]
    (into (set err-eids) (keep #(resolve-ref-eid bdb mdb %)) raw-refs)))

(defn- entity-mutation-txs [hdb hmdb eids]
  (mapcat (fn [e]
            (concat (keep #(when (:added %) (:tx %)) (d/datoms hdb :eavt e))
                    (when-not (identical? hmdb hdb) (keep #(when (:added %) (:tx %)) (d/datoms hmdb :eavt e)))))
          (take 32 eids)))

(defn view-history-txs
  "Generic entity-derived `d/as-of` history across projected entities, agent links, messages, and replies."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] [:maybe [:sequential :any]]] [:maybe [:vector :int]]]]
   :cost/ticks 15000000}
  ([bdb agent-id] (view-history-txs bdb bdb agent-id nil))
  ([bdb main-db agent-id] (view-history-txs bdb main-db agent-id nil))
  ([bdb main-db agent-id entity-ids]
   (when (and bdb agent-id)
     (let [mdb (or main-db bdb)
           eff-eids (vec (distinct (keep #(resolve-ref-eid bdb mdb %) entity-ids)))
           live? #(and (not (instance? datahike.db.AsOfDB %)) (not (instance? datahike.db.HistoricalDB %)))
           ck (when (and (live? bdb) (live? mdb))
                [(store/store-id bdb) (get-in bdb [:config :branch] :main) (:max-tx bdb)
                 (store/store-id mdb) (:max-tx mdb) agent-id eff-eids])
           compute
           (fn []
             (let [bs (db-schema bdb) ms (db-schema mdb)
                   hdb (d/history bdb) hmdb (if (identical? mdb bdb) hdb (d/history mdb))
                   eid (when (bs :seon.agent/id) (:e (first (d/datoms bdb :avet :seon.agent/id agent-id))))
                   meid (if (identical? mdb bdb) eid (when (ms :seon.agent/id) (:e (first (d/datoms mdb :avet :seon.agent/id agent-id)))))
                   non-ctrl (fn [hd e] (when e (keep #(when (and (:added %) (not (agent-control-attrs (:a %)))) (:tx %)) (d/datoms hd :eavt e))))
                   ag-txs (concat (non-ctrl hdb eid) (when-not (identical? hmdb hdb) (non-ctrl hmdb meid)))
                   all-eids (into (set eff-eids) (linked-agent-eids bdb mdb hdb hmdb eid meid agent-id))
                   ent-txs (entity-mutation-txs hdb hmdb all-eids)
                   msg-txs (when (ms :seon.agent.message/to)
                             (for [dt (d/datoms hmdb :avet :seon.agent.message/to agent-id) :when (:added dt)
                                   :let [m (d/pull mdb (quote [:seon.agent.message/type :seon.agent.message/from]) (:e dt))]
                                   :when (not (#{:nudge :system} (:seon.agent.message/type m)))]
                               (:tx dt)))
                   reply-txs (keep #(when (and (str/blank? (str (:seon.agent.eval/code %)))
                                               (not (str/blank? (str (:seon.agent.eval/thought %)))))
                                      (:seon.agent.eval/tx %))
                                   (sort-by :seon.agent.eval/tx (store/agent-historical-evals bdb agent-id)))]
               (->> (concat ag-txs ent-txs msg-txs reply-txs) (filter integer?) distinct sort vec)))]
       (if ck (store/lru-get! view-history-cache 32 ck compute) (compute))))))

(defn canvas-history-txs
  "Delegates to generic entity-derived `view-history-txs` for `d/as-of` rewind across all views."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]]
   :cost/ticks 15000000}
  ([bdb agent-id] (view-history-txs bdb bdb agent-id nil))
  ([bdb main-db agent-id] (view-history-txs bdb main-db agent-id nil)))
