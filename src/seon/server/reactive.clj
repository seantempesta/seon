(ns seon.server.reactive
  "Per-conn reactive engine. Routes each datahike commit to the subscriptions it
  could affect (a cheap e/a/v pattern gate, no query run), re-runs the candidates'
  queries, and emits one `changed-summaries` event per commit carrying the subs
  whose result actually moved.

  Runs inside `d/listen!` on the writer thread — READ-ONLY: it never transacts.
  Engine state is per-conn (per-cluster): one `(new-engine-state db-name)` per
  datahike connection, never global. Posh is a vendored reference only — the
  matcher here is a ~15-line port, no dependency, no core.async, no core.match.

  M1 scope: routing + change-detection. The full `changed-summaries` entry
  (agent-id, rows, render) and the inverted index land at M3/M6."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]))

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
             (<= 2 (count clause) 3)
             (not (seq? (first clause))))          ; skip [(pred ...)] / fn clauses
    (let [[e a v] clause]
      [(pos->pat e) (pos->pat a) (pos->pat (when (= 3 (count clause)) v))])))

(defn query->patterns
  "Derive the e/a/v wake-patterns from a datalog query (a form, or its source
  string). Reads the :where clauses; qvars/_ → wildcard, literals kept."
  [query]
  (let [q (if (string? query) (edn/read-string query) query)]
    (into [] (keep clause->pattern) (->> q (drop-while #(not= :where %)) rest))))

;; ---------------------------------------------------------------------------
;; per-conn engine state
;; {:db-name s :subs {sub-id {:query q :patterns [...] :last-result <result>}}}
;; ---------------------------------------------------------------------------

(defn new-engine-state [db-name]
  (atom {:db-name db-name :subs {}}))

(defn register-sub!
  "Register a subscription: derive its patterns, run the initial query, cache it.
  Call AFTER the subscription datom's own transact returns, so the registration
  tx does not route to the brand-new sub. Returns the cached entry."
  [state conn sub-id query]
  (let [entry {:query query
               :patterns (query->patterns query)
               :last-result (d/q query (d/db conn))}]
    (swap! state assoc-in [:subs sub-id] entry)
    entry))

(defn unregister-sub! [state sub-id]
  (swap! state update :subs dissoc sub-id)
  nil)

;; ---------------------------------------------------------------------------
;; the d/listen! callback — two-gate dispatch, READ-ONLY, emits via emit!
;; ---------------------------------------------------------------------------

(defn- report->datoms [report]
  (mapv (fn [d] [(:e d) (:a d) (:v d)]) (:tx-data report)))

(defn on-tx!
  "datahike `d/listen!` callback. Routes the commit to affected subscriptions
  (cheap gate), re-runs the candidates, and emits ONE event per commit carrying
  the subs whose result really moved. READ-ONLY — never transacts.

  ctx = {:db-name :conn :state :emit!}. Returns the changed entries (for tests)."
  [{:keys [db-name state emit!]} report]
  (let [datoms (report->datoms report)
        db (:db-after report)
        basis-t (:max-tx db)
        request-id (:seon.db/request-id (:tx-meta report))   ; nil until M3 wires it
        changed (reduce
                 (fn [acc [sub-id {:keys [query patterns last-result]}]]
                   (if (any-datoms-match? patterns datoms)            ; GATE 1 (cheap)
                     (let [new-result (d/q query db)]
                       (if (not= new-result last-result)              ; GATE 2 (change)
                         (do (swap! state assoc-in [:subs sub-id :last-result] new-result)
                             (conj acc {:seon.reactive/sub-id sub-id
                                        :seon.reactive/result new-result}))
                         acc))
                     acc))
                 []
                 (:subs @state))]
    (when (seq changed)
      (emit! (cond-> {:seon.reactive/db-name db-name
                      :seon.reactive/basis-t basis-t
                      :seon.reactive/changed changed}
               request-id (assoc :seon.reactive/request-id request-id))))
    changed))
