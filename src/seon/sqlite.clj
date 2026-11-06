(ns seon.sqlite
  "The one place that names libsqlite3 (docs/vector.md#search): an in-memory index of texts with two FTS5
  tables, the Porter one that ranks them and the trigram one that finds a substring. The library is macOS's
  own (:seon.config.native/sqlite-lib), bound with jolt.ffi on first use, so a process that never searches never
  loads it. A refused call is the one error map `refused` raises, with sqlite3_errmsg's words
  (docs/failure-points.md). An index is closed when it is retired and no reader is using it, or when the
  collector reclaims it."
  (:refer-clojure :exclude [defn defn-])
  (:require [jolt.ffi :as ffi] [clojure.string :as str]
            [seon.contract :refer [defn defn-] :as contract] [seon.config :as cfg]
            [seon.lib :refer [defc]]))

;; The C functions, bound as seon.lib/defc binds every one: contracted from their C signatures. The six a loop
;; calls per text or per row are not vars but locals of `with-row-calls`: every var is contracted
;; (bin/check contracts), and a contracted call is recorded work (docs/cost.md), so four a text made the build's
;; calls grow with its texts (docs/vector.md#search). The functions that loop carry the contracts.
(defc c-open "sqlite3_open" [:string :pointer] :int)
(defc c-close "sqlite3_close_v2" [:pointer] :int)
(defc c-exec "sqlite3_exec" [:pointer :string :pointer :pointer :pointer] :int)
(defc c-prepare "sqlite3_prepare_v2" [:pointer :string :int :pointer :pointer] :int)
(defc c-finalize "sqlite3_finalize" [:pointer] :int)
(defc c-errmsg "sqlite3_errmsg" [:pointer] :string)

(defmacro ^:private with-row-calls
  "body with c-bind-text, c-bind-int, c-step, c-reset, c-column-int and c-column-double bound to the C functions
  as locals (ffi/cfn), uncontracted."
  [& body]
  `(let [~'c-bind-text (ffi/cfn "sqlite3_bind_text" [:pointer :int :string :int :int64] :int)
         ~'c-bind-int (ffi/cfn "sqlite3_bind_int64" [:pointer :int :int64] :int)
         ~'c-step (ffi/cfn "sqlite3_step" [:pointer] :int)
         ~'c-reset (ffi/cfn "sqlite3_reset" [:pointer] :int)
         ~'c-column-int (ffi/cfn "sqlite3_column_int64" [:pointer :int] :int64)
         ~'c-column-double (ffi/cfn "sqlite3_column_double" [:pointer :int] :double)]
     ~@body))

;; sqlite3.h's constants, under its names: a step's rows and its end, and the destructor that has sqlite copy a text
(def ^:private SQLITE_ROW 100)
(def ^:private SQLITE_DONE 101)
(def ^:private SQLITE_TRANSIENT -1)

(defn- load!
  "The library at :seon.config.native/sqlite-lib bound, or the :seon.sqlite/unloaded error map when it will not
  load. See docs/vector.md#search."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (let [path (cfg/value :seon.config.native/sqlite-lib)]
    (try (ffi/load-library path) nil
         (catch Throwable t
           (contract/error :seon.sqlite/unloaded #'seon.sqlite/load! (str "libsqlite3 did not load from " path ": " (ex-message t))
                           {:path path :seon.error/fix "Set :seon.config.native/sqlite-lib to a libsqlite3 built with FTS5."})))))

(defonce ^:private library (delay (load!)))

(defn- loaded!
  "Nil once the library is loaded; throws the :seon.sqlite/unloaded error map (as an ex-info) when it is not."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (when-let [e @library] (throw (ex-info (:seon.error/message e) e))))

(defn- refused
  "The edge of every call: throws the :seon.sqlite/refused error map (as an ex-info) for op's non-OK return
  code rc, with sqlite3_errmsg's words for connection h. Nothing else here catches or checks."
  {:malli/schema [:=> [:cat :seon.ffi/address :keyword :int] :nil]}
  [h op rc]
  (let [e (contract/error :seon.sqlite/refused #'seon.sqlite/refused (str "sqlite " (name op) ": " (c-errmsg h) " (code " rc ")") {:op op :rc rc})]
    (throw (ex-info (:seon.error/message e) e))))

(defn- exec!
  "SQL run on connection h, no rows wanted."
  {:malli/schema [:=> [:cat :seon.ffi/address :keyword :string] :nil]}
  [h op sql]
  (let [rc (c-exec h sql ffi/null ffi/null ffi/null)]
    (when-not (zero? rc) (refused h op rc))))

(defn- prepare
  "The statement for sql on connection h."
  {:malli/schema [:=> [:cat :seon.ffi/address :keyword :string] :seon.ffi/address]}
  [h op sql]
  (ffi/with-arena [a]
    (let [pp (ffi/alloc a :pointer) rc (c-prepare h sql -1 pp ffi/null)]
      (when-not (zero? rc) (refused h op rc))
      (ffi/read pp :pointer))))

(defmacro ^:private ok!
  "Evaluates the C call and hands a return code that is not OK to `refused` (connection h, operation op). Inline,
  so a loop's per-row call is not a function call."
  [h op call]
  `(let [rc# ~call] (when-not (zero? rc#) (refused ~h ~op rc#))))

(defn- open!
  "A new in-memory connection. SQLite wants sqlite3_close on a handle whose open failed, so it is closed
  before the error is thrown."
  {:malli/schema [:=> [:cat] :seon.ffi/address]}
  []
  (ffi/with-arena [a]
    (let [pp (ffi/alloc a :pointer) rc (c-open ":memory:" pp) h (ffi/read pp :pointer)]
      (when-not (zero? rc)
        (try (refused h :open rc) (finally (c-close h))))
      h)))

(defn- settle!
  "Applies f to the index state atom st, marking it closed when it is retired with no reader, and closes the
  connection h on the one transition to closed. Answers the state before f."
  {:malli/schema [:=> [:cat :any :seon.ffi/address ifn?] :map]}
  [st h f]
  (let [[old new] (swap-vals! st (fn [s] (let [s (f s)] (cond-> s (and (:retired? s) (zero? (:uses s))) (assoc :closed? true)))))]
    (when (and (:closed? new) (not (:closed? old))) (c-close h))
    old))

(defn- insert-rows!
  "Rows [[e a text] ...] inserted into the index on connection h, inside a transaction the caller holds: each its row of r,
  whose id is its text's rowid in both tables (`fts-open`). The rows per attribute, {a rows}."
  {:malli/schema [:=> [:cat :seon.ffi/address [:sequential [:tuple :int :int :string]]] [:map-of :int :int]]}
  [h rows]
  (with-row-calls
    (let [ins-r (prepare h :insert "INSERT INTO r(e, a) VALUES (?1, ?2)")
          ins-t (prepare h :insert "INSERT INTO t(rowid, body) VALUES (last_insert_rowid(), ?1)")
          ins-g (prepare h :insert "INSERT INTO g(rowid, body) VALUES (last_insert_rowid(), ?1)")]
      (try (loop [rs (seq rows) counts (transient {})]
             (if rs
               (let [[e a text] (first rs)]
                 (ok! h :bind (c-bind-int ins-r 1 e))
                 (ok! h :bind (c-bind-int ins-r 2 a))
                 (let [rc (c-step ins-r)] (when-not (= SQLITE_DONE rc) (refused h :insert rc)))
                 (ok! h :reset (c-reset ins-r))
                 (doseq [stm [ins-t ins-g]]
                   (ok! h :bind (c-bind-text stm 1 text -1 SQLITE_TRANSIENT))
                   (let [rc (c-step stm)] (when-not (= SQLITE_DONE rc) (refused h :insert rc)))
                   (ok! h :reset (c-reset stm)))
                 (recur (next rs) (assoc! counts a (inc (get counts a 0)))))
               (persistent! counts)))
           (finally (c-finalize ins-r) (c-finalize ins-t) (c-finalize ins-g))))))

(defn- delete-rows!
  "Rows [[e a] ...], each a text the index holds, deleted from the index on connection h by one seek of r each, inside a
  transaction the caller holds. The rows per attribute, {a rows}."
  {:malli/schema [:=> [:cat :seon.ffi/address [:sequential [:tuple :int :int]]] [:map-of :int :int]]}
  [h rows]
  (with-row-calls
    (let [stms [(prepare h :delete "DELETE FROM t WHERE rowid = (SELECT id FROM r WHERE e = ?1 AND a = ?2)")
                (prepare h :delete "DELETE FROM g WHERE rowid = (SELECT id FROM r WHERE e = ?1 AND a = ?2)")
                (prepare h :delete "DELETE FROM r WHERE e = ?1 AND a = ?2")]]
      (try (loop [rs (seq rows) counts (transient {})]
             (if rs
               (let [[e a] (first rs)]
                 (doseq [stm stms]
                   (ok! h :bind (c-bind-int stm 1 e))
                   (ok! h :bind (c-bind-int stm 2 a))
                   (let [rc (c-step stm)] (when-not (= SQLITE_DONE rc) (refused h :delete rc)))
                   (ok! h :reset (c-reset stm)))
                 (recur (next rs) (assoc! counts a (inc (get counts a 0)))))
               (persistent! counts)))
           (finally (doseq [stm stms] (c-finalize stm)))))))

(defn fts-open
  "An index over rows [[e a text] ...] (an entity, a small integer naming the attribute it came from, its
  text; one text for each [e a]): {:h the connection :state an atom}, the atom holding the readers, whether it is
  retired and closed, and :counts {a rows} (`counts`). Built in the one transaction, two FTS5 tables hold every text:
  t, `porter unicode61`, whose terms are stems (\"it\" is not a part of \"edit\"), ranks by bm25; g, `trigram`, answers a
  substring. A third, r, names a text's rowid in both by its [e a], so one text is found by one seek (an entity's id
  is 60 bits, so no rowid can be made of it, and FTS5 holds no index on a column of its own). In memory, so it
  survives fork() as plain memory. Used through `using` and closed by `retire!` once no reader holds it, or by the collector when the map is garbage: the native memory is invisible to the
  collector's accounting, which is why a superseded index is retired and not left. See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:sequential [:tuple :int :int :string]]] [:map [:h :seon.ffi/address]]]}
  [rows]
  (loaded!)
  (let [h (open!)
        st (atom {:uses 0 :retired? false :closed? false :counts {}})
        arena (ffi/auto-arena)]
    (ffi/reinterpret h 1 arena (fn [p] (settle! st p #(assoc % :retired? true))))
    (exec! h :create (str "BEGIN;"
                          "CREATE TABLE r(id INTEGER PRIMARY KEY, e INTEGER NOT NULL, a INTEGER NOT NULL, UNIQUE(e, a));"
                          "CREATE VIRTUAL TABLE t USING fts5(body, tokenize='porter unicode61');"
                          "CREATE VIRTUAL TABLE g USING fts5(body, tokenize='trigram')"))
    (swap! st assoc :counts (insert-rows! h rows))
    (exec! h :commit "COMMIT")
    {:h h :state st :arena arena}))

(defn replace-rows!
  "Index ix moved by one write, in one transaction: gone [[e a] ...], the texts it holds that the write changed or
  retracted, deleted by rowid, and added [[e a text] ...], their new texts and the new ones, inserted. The rest of the
  index is untouched, so a write costs its texts whatever the index holds. Call it inside `using`. nil.
  See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:map [:h :seon.ffi/address]] [:sequential [:tuple :int :int]] [:sequential [:tuple :int :int :string]]] :nil]}
  [{:keys [h state]} gone added]
  (exec! h :begin "BEGIN")
  (let [done (volatile! false)]
    (try (let [out (delete-rows! h gone) in (insert-rows! h added)]
           (swap! state update :counts (fn [m] (merge-with + m in (into {} (for [[a n] out] [a (- n)]))))))
         (exec! h :commit "COMMIT")
         (vreset! done true)
         (finally (when-not @done (c-exec h "ROLLBACK" ffi/null ffi/null ffi/null)))))
  nil)

(defn counts
  "The rows of each attribute of index ix, {a rows}, as the last build or replacement left them."
  {:malli/schema [:=> [:cat [:map [:state :any]]] [:map-of :int :int]]}
  [{:keys [state]}]
  (:counts @state))

(defn size
  "The rows of index ix."
  {:malli/schema [:=> [:cat [:map [:state :any]]] :int]}
  [ix]
  (reduce + 0 (vals (counts ix))))

(defn retire!
  "Marks index ix superseded: it is closed now when no reader is using it, else by the last reader's release.
  Idempotent. nil. See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:map [:h :seon.ffi/address]]] :nil]}
  [{:keys [h state]}]
  (settle! state h #(assoc % :retired? true))
  nil)

(defn using
  "Applies f to index ix while it is held open, and answers {:value (f ix)}; nil when ix was already closed (a
  retired index nobody was using), for the caller to ask for the current one. See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:map [:h :seon.ffi/address]] ifn?] [:maybe [:map [:value :any]]]]}
  [{:keys [h state] :as ix} f]
  (let [old (settle! state h #(if (:closed? %) % (update % :uses inc)))]
    (when-not (:closed? old)
      (try {:value (f ix)}
           (finally (settle! state h #(update % :uses dec)))))))

(defn- select
  "The rows of sql on connection h with query bound to ?1, each read by row-fn from the statement."
  {:malli/schema [:=> [:cat :seon.ffi/address :keyword :string :string ifn?] [:vector :any]]}
  [h op sql query row-fn]
  (let [st (prepare h op sql)]
    (with-row-calls
    (try
      (ok! h :bind (c-bind-text st 1 query -1 SQLITE_TRANSIENT))
      (loop [out (transient [])]
        (let [rc (c-step st)]
          (cond (= SQLITE_ROW rc) (recur (conj! out (row-fn st)))
                (= SQLITE_DONE rc) (persistent! out)
                :else (refused h op rc))))
      (finally (c-finalize st))))))

(defn- in-attrs
  "The SQL restricting a query to attributes attrs (small integers), empty for all."
  {:malli/schema [:=> [:cat [:set :int]] :string]}
  [attrs]
  (if (seq attrs) (str " AND r.a IN (" (str/join "," (sort attrs)) ")") ""))

(defn fts-match
  "The rows of index ix holding a word of query (FTS5 syntax: words quoted and joined by OR), of the attributes
  in attrs (small integers as fts-open was given, all when attrs is empty): [[e a score]] unordered, score the
  negated bm25 of the text against every text of the index (higher is better). Call it inside `using`.
  See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:map [:h :seon.ffi/address]] :string [:set :int]] [:vector [:tuple :int :int :double]]]}
  [{h :h} query attrs]
  (with-row-calls
    (select h :match (str "SELECT r.e, r.a, bm25(t) FROM t CROSS JOIN r ON r.id = t.rowid WHERE t MATCH ?1" (in-attrs attrs)) query
            (fn [st] [(c-column-int st 0) (c-column-int st 1) (- (c-column-double st 2))]))))

(defn fts-substring
  "The rows [[e a]] of index ix whose text holds string text of three characters or more anywhere (inside a term,
  across a separator), of the attributes attrs, unordered: the trigram table answers it, ignoring case (a superset
  of a case-sensitive match). Call it inside `using`. See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:map [:h :seon.ffi/address]] [:string {:min 3}] [:set :int]] [:vector [:tuple :int :int]]]}
  [{h :h} text attrs]
  (with-row-calls
    (select h :substring (str "SELECT r.e, r.a FROM g CROSS JOIN r ON r.id = g.rowid WHERE g MATCH ?1" (in-attrs attrs))
            (str "\"" (str/replace text "\"" "\"\"") "\"")
            (fn [st] [(c-column-int st 0) (c-column-int st 1)]))))
