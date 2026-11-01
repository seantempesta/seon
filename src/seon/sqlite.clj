(ns seon.sqlite
  "The one place that names libsqlite3 (docs/vector.md#search): an in-memory FTS5 table over texts and the
  query that ranks them. The library is macOS's own (:seon.config.native/sqlite-lib), bound with jolt.ffi
  on first use, so a process that never searches never loads it. A refused call is the one error map
  `refused` raises, with sqlite3_errmsg's words (docs/failure-points.md)."
  (:refer-clojure :exclude [defn defn-])
  (:require [jolt.ffi :as ffi] [clojure.string :as str]
            [seon.contract :refer [defn defn-] :as contract] [seon.config :as cfg]))

;; The C functions. Plain bindings, not contracted ones (seon.lib/defc): a build calls four of them per text,
;; and a contracted call is recorded work (docs/cost.md); the contracts are on the functions below.
(ffi/defcfn c-open "sqlite3_open" [:string :pointer] :int)
(ffi/defcfn c-close "sqlite3_close_v2" [:pointer] :int)
(ffi/defcfn c-exec "sqlite3_exec" [:pointer :string :pointer :pointer :pointer] :int)
(ffi/defcfn c-prepare "sqlite3_prepare_v2" [:pointer :string :int :pointer :pointer] :int)
(ffi/defcfn c-bind-text "sqlite3_bind_text" [:pointer :int :string :int :int64] :int)
(ffi/defcfn c-bind-int "sqlite3_bind_int64" [:pointer :int :int64] :int)
(ffi/defcfn c-step "sqlite3_step" [:pointer] :int)
(ffi/defcfn c-reset "sqlite3_reset" [:pointer] :int)
(ffi/defcfn c-column-int "sqlite3_column_int64" [:pointer :int] :int64)
(ffi/defcfn c-column-double "sqlite3_column_double" [:pointer :int] :double)
(ffi/defcfn c-finalize "sqlite3_finalize" [:pointer] :int)
(ffi/defcfn c-errmsg "sqlite3_errmsg" [:pointer] :string)

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

(defn- open!
  "A new in-memory connection."
  {:malli/schema [:=> [:cat] :seon.ffi/address]}
  []
  (ffi/with-arena [a]
    (let [pp (ffi/alloc a :pointer) rc (c-open ":memory:" pp) h (ffi/read pp :pointer)]
      (when-not (zero? rc) (refused h :open rc))
      h)))

(defn fts-open
  "An FTS5 index over rows [[e a text] ...] (an entity, a small integer naming the attribute it came from, its
  text): {:h the connection :arena the arena that closes it :n rows :counts {a rows}}. The table tokenizes
  with Porter's stemmer over unicode61 (a word is its stem; \"it\" is not a part of \"edit\"). In memory, so
  it survives fork() as plain memory, and closed when the map holding it is collected (the arena is auto: a
  reader holding the index keeps it open). See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:sequential [:tuple :int :int :string]]] [:map [:n :int]]]}
  [rows]
  (loaded!)
  (let [h (open!)
        arena (ffi/auto-arena)]
    (ffi/reinterpret h 1 arena (fn [p] (c-close p)))
    (exec! h :create "CREATE VIRTUAL TABLE t USING fts5(body, e UNINDEXED, a UNINDEXED, tokenize='porter unicode61')")
    (exec! h :begin "BEGIN")
    (let [st (prepare h :insert "INSERT INTO t(body, e, a) VALUES (?1, ?2, ?3)")
          counts (try (loop [rs (seq rows) counts (transient {})]
                        (if rs
                          (let [[e a text] (first rs)]
                            (c-bind-text st 1 text -1 SQLITE_TRANSIENT)
                            (c-bind-int st 2 e)
                            (c-bind-int st 3 a)
                            (let [rc (c-step st)] (when-not (= SQLITE_DONE rc) (refused h :insert rc)))
                            (c-reset st)
                            (recur (next rs) (assoc! counts a (inc (get counts a 0)))))
                          (persistent! counts)))
                      (finally (c-finalize st)))]
      (exec! h :commit "COMMIT")
      {:h h :arena arena :n (reduce + 0 (vals counts)) :counts counts})))

(defn fts-match
  "The rows of index ix holding a word of query (FTS5 syntax: words quoted and joined by OR), of the attributes
  in attrs (small integers as fts-open was given, all when attrs is empty): [[e a score]] unordered, score the
  negated bm25 of the text against every text of the index (higher is better). See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:map [:h :seon.ffi/address]] :string [:set :int]] [:vector [:tuple :int :int :double]]]}
  [{h :h} query attrs]
  (let [st (prepare h :match (str "SELECT e, a, bm25(t) FROM t WHERE t MATCH ?1"
                                  (when (seq attrs) (str " AND a IN (" (str/join "," (sort attrs)) ")"))))]
    (try
      (c-bind-text st 1 query -1 SQLITE_TRANSIENT)
      (loop [out (transient [])]
        (let [rc (c-step st)]
          (cond (= SQLITE_ROW rc) (recur (conj! out [(c-column-int st 0) (c-column-int st 1) (- (c-column-double st 2))]))
                (= SQLITE_DONE rc) (persistent! out)
                :else (refused h :match rc))))
      (finally (c-finalize st)))))
