(ns seon.server.reactive
  "Per-conn reactive engine. Routes each datahike commit to the subscriptions it
  could affect (an inverted index → a cheap e/a/v pattern gate, no query run for
  the rest), re-runs the candidates' queries, and emits one `changed-summaries`
  event per commit carrying the subs whose result actually moved.

  Runs inside `d/listen!` on the writer thread — READ-ONLY: it never transacts.
  Engine state is per-conn (per-cluster): one `(new-engine-state db-name)` per
  datahike connection, never global. Posh is a vendored reference only — the
  matcher here is a ~15-line port, no dependency, no core.async, no core.match.

  These engine fns are runtime plumbing over opaque artifacts (a conn, an atom,
  a TxReport), so they take positional args rather than the map-in/map-out data
  convention; the DATA boundary (the subscription entity + the changed-summaries
  event) gets its registered Malli schemas at M3, through seon.db.

  M1 scope: routing + change-detection + the index. The full `changed-summaries`
  entry (agent-id, rows, render) lands at M3/M4; basis-t catch-up at M4."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; data-boundary schemas. Registered under :seon.server.reactive/* (this code
;; ns) — NOT bare :seon.reactive/* (that belongs to seon.web.reactive). The
;; durable subscription id is :seon.subscription/*. The full subscription entity
;; + :seon.agent/id + render-fn refs land at M3 (need registry's :seon.agent/id).
;; The engine emits the changed-summaries event in this exact registered shape —
;; no translation layer.
;; ---------------------------------------------------------------------------

(schema/register! :seon.subscription/id [:string {:min 1 :seon.db/identity true}])
(schema/register! :seon.server.reactive/db-name :string)
(schema/register! :seon.server.reactive/basis-t [:int {:min 0}])
(schema/register! :seon.server.reactive/request-id :string)
(schema/register! :seon.server.reactive/scalar
                  [:or :string :int :boolean :keyword :inst :uuid :double])
(schema/register! :seon.server.reactive/rows
                  [:vector [:vector :seon.server.reactive/scalar]])  ; result tuples (set → vec)
(schema/register! :seon.server.reactive/changed-entry
                  [:map
                   [:seon.subscription/id      :seon.subscription/id]
                   [:seon.server.reactive/rows :seon.server.reactive/rows]])
                  ;; + :seon.agent/id + :seon.render/* at M3
(schema/register! :seon.server.reactive/changed-summaries-event
                  [:map
                   [:seon.server.reactive/db-name    :seon.server.reactive/db-name]
                   [:seon.server.reactive/basis-t    :seon.server.reactive/basis-t]
                   [:seon.server.reactive/request-id {:optional true} :seon.server.reactive/request-id]
                   [:seon.server.reactive/changed    [:vector :seon.server.reactive/changed-entry]]])

;; ---------------------------------------------------------------------------
;; e/a/v datom matcher  (ported from posh.lib.datom-matcher — no dependency)
;; a pattern is [e a v]; each position is '_ (wildcard), a set (membership),
;; or a literal (equality). a datom is matched positionally as [e a v].
;; ---------------------------------------------------------------------------

(defn- pattern-match? [pattern datom]
  (if (empty? pattern)
    true
    (let [p (first pattern)]
      (when (or (= p '_)
                (and (set? p) (contains? p (first datom)))
                (= p (first datom)))
        (recur (rest pattern) (rest datom))))))

(defn- datom-match? [patterns datom]
  (boolean (some #(pattern-match? % datom) patterns)))

(defn- any-datoms-match? [patterns datoms]
  (boolean (some #(datom-match? patterns %) datoms)))

;; ---------------------------------------------------------------------------
;; where-clause pattern extraction (attribute + literal precision)
;; qvars (?x) and _ become wildcards; literals (keywords, strings, ints) stay.
;; ---------------------------------------------------------------------------

(defn- qvar? [x]
  (and (symbol? x) (= \? (first (name x)))))

(defn- pos->pat [x]
  (if (or (nil? x) (= '_ x) (qvar? x)) '_ x))

(defn- clause->pattern [clause]
  (when (and (vector? clause)
             (>= (count clause) 2)
             (not (coll? (first clause))))         ; skip [(pred ...)] / fn clauses
    (let [[e a v] clause]                           ; e/a/v are positions 0-2; 4-/5-elem
      [(pos->pat e)                                 ; datom clauses [e a v tx added] still index by a
       (pos->pat a)
       (pos->pat (when (>= (count clause) 3) v))])))

(defn query->patterns
  "Derive the e/a/v wake-patterns from a datalog query (a form, or its source
  string). Walks the :where clauses RECURSIVELY, so clauses nested inside
  not/or/and/*-join are still indexed — missing one would silently under-match
  and drop wakes. qvars/_ → wildcard, literals kept. Over-collecting is safe
  (the cheap gate + result-diff confirm); under-collecting is not."
  [query]
  (let [q (if (string? query) (edn/read-string query) query)
        where (->> q (drop-while #(not= :where %)) rest)]
    (into [] (keep clause->pattern) (tree-seq coll? seq where))))

;; ---------------------------------------------------------------------------
;; inverted index over subscriptions  {attr|entity -> #{sub-id}} + match-all.
;; turns per-tx routing from O(all subs) into O(subs touching the tx's attrs).
;; a SUPERSET filter — candidates are confirmed by the cheap gate in on-tx!.
;; ---------------------------------------------------------------------------

(defn- pattern-index-key
  "Where a [e a v] pattern indexes: by its attribute (most selective), else by a
  concrete entity, else the match-everything bucket (:all)."
  [[e a _v]]
  (cond
    (keyword? a)               [:by-attr a]
    (and (= '_ a) (not= '_ e)) [:by-entity e]
    :else                      :all))

(defn- index-update [index f sub-id patterns]
  (reduce (fn [idx pat]
            (let [k (pattern-index-key pat)]
              (if (= :all k)
                (update idx :all f sub-id)
                (update-in idx k (fnil f #{}) sub-id))))
          index patterns))

(defn- index-add    [index sub-id patterns] (index-update index conj sub-id patterns))
(defn- index-remove [index sub-id patterns] (index-update index disj sub-id patterns))

(defn- candidate-subs
  "The subscriptions a commit COULD affect: those indexed under any modified
  attribute or touched entity, plus the match-everything bucket. A superset of
  the true matches — on-tx!'s cheap gate confirms the precise e/a/v."
  [index datoms]
  (into (:all index)
        (concat (mapcat #(get-in index [:by-attr (second %)]) datoms)
                (mapcat #(get-in index [:by-entity (first %)]) datoms))))

;; ---------------------------------------------------------------------------
;; per-conn engine state
;; {:db-name s
;;  :subs   {sub-id {:query q :patterns [...] :last-result <result>}}
;;  :index  {:by-attr {a #{sub-id}} :by-entity {e #{sub-id}} :all #{sub-id}}}
;; ---------------------------------------------------------------------------

(defn new-engine-state
  "Fresh per-conn (per-cluster) engine state: an atom holding the subscription
  cache and the inverted index. Never share one across conns."
  [db-name]
  (atom {:db-name db-name :subs {} :index {:by-attr {} :by-entity {} :all #{}}}))

(defn- register-sub*
  "Register a subscription against a GIVEN db value: derive patterns, seed the
  initial result, add to the cache AND the inverted index."
  [state db sub-id query]
  (let [patterns (query->patterns query)
        entry {:query query :patterns patterns :last-result (d/q query db)}]
    (swap! state (fn [s] (-> s
                             (assoc-in [:subs sub-id] entry)
                             (update :index index-add sub-id patterns))))
    entry))

(defn register-sub!
  "Register a subscription in the engine cache + index. Call AFTER the
  subscription datom's own transact returns, so the registration tx does not
  route to the brand-new sub. Returns the cached entry."
  [state conn sub-id query]
  (register-sub* state (d/db conn) sub-id query))

(defn unregister-sub!
  "Drop a subscription from both the cache and the inverted index (so its id
  doesn't leak in the index sets)."
  [state sub-id]
  (swap! state (fn [s]
                 (let [patterns (get-in s [:subs sub-id :patterns])]
                   (-> s
                       (update :subs dissoc sub-id)
                       (update :index index-remove sub-id patterns)))))
  nil)

;; ---------------------------------------------------------------------------
;; persistence + rebuild (bootstrap-from-DB)
;; a subscription is a durable datom; the cache is derived from it.
;; ---------------------------------------------------------------------------

(defn register-subscription!
  "Transact the subscription as a durable datom (query stored as SOURCE STRING —
  code-as-data), THEN register it in the engine cache + index. The cache
  registration runs AFTER the transact returns, so the registration tx itself
  does not route to the brand-new sub."
  [state conn sub-id query]
  (d/transact conn [{:seon.subscription/id sub-id
                     :seon.subscription/query (pr-str query)
                     :seon.subscription/active? true}])
  (register-sub! state conn sub-id query))

(defn rebuild!
  "Reconstitute the in-memory cache + index from the active subscription datoms —
  nothing in the cache is authoritative, the datoms are. Captures the db once,
  re-derives patterns, and seeds :last-result from the CURRENT db (seed-to-current;
  basis-t catch-up for changes missed during downtime is M4, once writebacks
  persist :seon.subscription/basis-t). Returns the number of subscriptions rebuilt."
  [state conn]
  (let [db (d/db conn)
        subs (d/q '[:find ?id ?q
                    :where [?s :seon.subscription/id ?id]
                           [?s :seon.subscription/active? true]
                           [?s :seon.subscription/query ?q]]
                  db)]
    (doseq [[sub-id qstr] subs]
      (register-sub* state db sub-id (edn/read-string qstr)))   ; db captured once
    (count (:subs @state))))

;; ---------------------------------------------------------------------------
;; the d/listen! callback — two-gate dispatch, READ-ONLY, emits via emit!
;; ---------------------------------------------------------------------------

(defn- report->datoms [report]
  (mapv (fn [d] [(:e d) (:a d) (:v d)]) (:tx-data report)))

(defn on-tx!
  "datahike `d/listen!` callback. Routes the commit to candidate subscriptions
  (inverted index → cheap gate confirms), re-runs the candidates, and emits ONE
  event per commit carrying the subs whose result really moved. READ-ONLY — never
  transacts. ctx = {:db-name :conn :state :emit!}. Returns the changed entries."
  [{:keys [db-name state emit!]} report]
  (let [datoms (report->datoms report)
        db (:db-after report)
        basis-t (:max-tx db)
        request-id (:seon.db/request-id (:tx-meta report))   ; nil until M3 wires it
        snapshot @state
        ;; internal: [sub-id new-result] tuples for the subs whose result moved
        changed (reduce
                 (fn [acc sub-id]
                   (let [{:keys [query patterns last-result]} (get-in snapshot [:subs sub-id])]
                     (if (any-datoms-match? patterns datoms)           ; GATE 1 (cheap, confirms index)
                       (let [new-result (d/q query db)]
                         (if (not= new-result last-result)             ; GATE 2 (real change)
                           (conj acc [sub-id new-result])
                           acc))
                       acc)))
                 []
                 (candidate-subs (:index snapshot) datoms))]   ; candidates only, not all N
    (when (seq changed)
      ;; one swap! advances every moved sub's :last-result (no atom thrashing)
      (swap! state update :subs
             (fn [subs] (reduce (fn [m [sub-id result]] (assoc-in m [sub-id :last-result] result))
                                subs changed)))
      ;; emit the canonical changed-summaries event directly (the registered
      ;; :seon.server.reactive/changed-summaries-event shape — no translation layer)
      (emit! (cond-> {:seon.server.reactive/db-name db-name
                      :seon.server.reactive/basis-t basis-t
                      :seon.server.reactive/changed
                      (mapv (fn [[sub-id result]]
                              {:seon.subscription/id sub-id
                               :seon.server.reactive/rows (vec result)})
                            changed)}
               request-id (assoc :seon.server.reactive/request-id request-id))))
    changed))
