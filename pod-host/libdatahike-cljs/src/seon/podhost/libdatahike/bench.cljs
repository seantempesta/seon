(ns seon.podhost.libdatahike.bench
  "CLJS-2.5: head-to-head perf bench across `:memory`, `:file`
   (konserve.node-filestore on real disk), and `:indexeddb`-via-tiered
   (konserve.indexeddb under the fake-indexeddb polyfill).

   Methodology is ported from upstream datahike's JVM benchmark suite
   (`~/src/datahike/benchmark/src/benchmark/{measure,config}.clj`):

     - Multi-iteration measurement with mean/median/std/min/max/count/observations
     - Query taxonomy: simple, limit, e-join (free + first-fixed + second-fixed),
       a-join, v-join, equals (free + 1-fixed), less-than (free + 1-fixed),
       scalar-arg, scalar-arg-with-join, vector-arg, plus the existing 5 vault
       queries (scan-all, scan-by-tag, indexed-by-id, pull-by-path, range-by-time).
     - Aggregate queries (sum/avg/median/max/variance/stddev) timed against a
       synthetic vector input — engine-aggregate cost, not a scan.
     - Connection-time measurement (re-open existing store N times).
     - Transaction-time measurement at multiple batch sizes.
     - For queries with constants: hit (data-in-db?=true) vs miss (=false) variants.
     - For queries with attr choice: int-typed vs string-typed variants.

   Two schema modes (env BENCH_SCHEMA):

     - `vault` (default) — vault-realistic notes: :note/id (unique-id string),
       :note/path (unique string), :note/text (string), :note/created-at (long),
       :note/tag (cardinality/many string). String attr = :note/path,
       second string = :note/text, int attr = :note/created-at. There is no
       second long-typed attr in the vault schema, so query variants that need
       two int attrs (e-join across two ints, a/v-join over int) fall back to
       running once with the int attr paired with :note/path or are dropped.

     - `synthetic` — upstream's :s1 :s2 :i1 :i2 (string/string/bigint/bigint),
       for apples-to-apples comparison against upstream JVM numbers. bigint
       isn't representable losslessly in JS for very large values; we use the
       integer subset that fits in a JS Number for now.

   Outputs:
     - Stdout: setup/load lines + final per-(backend,size,query,data-type,data-in-db?)
       summary table (median + std), grouped by query class for at-a-glance reading.
     - `out/bench-results.edn`: full results in upstream-compatible shape —
       `[{:context {:dh-config ... :function ... :db-entities N :db-datoms M
                    :execution {:data-type :int :data-in-db? true}} :time {...}}
         ...]`
       Plus an `:asymmetries` map noting what timings are NOT comparable to.

   Env vars:

     BENCH_SIZES=1000,10000          ;; entity counts to run (default 1000,10000)
     BENCH_BACKENDS=memory,fs,idb    ;; backends (default memory,fs,idb)
     BENCH_ITERATIONS=10             ;; iterations per measurement (default 10)
     BENCH_TX_BATCHES=100,1000,10000 ;; tx batch sizes for transaction-time measurement
     BENCH_BATCH=1000                ;; bulk-load batch size
     BENCH_SCHEMA=vault               ;; or `synthetic`
     BENCH_QUERIES=all               ;; or comma-separated subset by name
     BENCH_OUT=out/bench-results.edn ;; EDN output path

   Notes:
     - `:idb` is skipped for size > 100000 (fake-indexeddb is too slow there).
     - 100000 is reachable but not default.
     - `:sqlite` backend is a follow-up (needs konserve-sqlite-cljs adapter).
       TODO: :sqlite backend"
  (:require [datahike.api :as d]
            [datahike.datom]
            [datahike.index.persistent-set]
            [konserve.node-filestore]
            [konserve.indexeddb]
            [me.tonsky.persistent-sorted-set :as psset]
            [me.tonsky.persistent-sorted-set.btset :as btset]
            [cljs.core.async :as a :refer [<!]]
            [cljs.pprint :as pp])
  (:require-macros [cljs.core.async :refer [go go-loop]]))

;; ---------------------------------------------------------------------------
;; CLJS-DATAHIKE FIXES — see REPL-WORKFLOW.md "Diagnosis sidebar" for the
;; full root-cause analysis. Two upstream incompatibilities between datahike
;; 0.7.1624 and persistent-sorted-set 0.3.116:
;;   (1) `empty-index` passes `:cmp` but psset reads `:comparator`
;;   (2) `insert`'s 3-arg `psset/lookup` call treats the comparator as
;;       a `not-found` value, causing cardinality/many duplicates to be
;;       silently dropped.
;; Patches mirror the ones in seon.podhost.libdatahike.repl.
;; ---------------------------------------------------------------------------

(defonce ^:private patches-applied?
  (do
    (let [orig btset/from-opts]
      (set! btset/from-opts
            (fn [opts]
              (let [opts' (if (and (:cmp opts) (not (:comparator opts)))
                            (assoc opts :comparator (:cmp opts))
                            opts)]
                (orig opts')))))
    (let [_orig-insert datahike.index.persistent-set/insert]
      (set! datahike.index.persistent-set/insert
            (fn [pset datom index-type]
              (psset/conj pset datom
                          (datahike.datom/index-type->cmp-quick index-type)))))
    true))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def os (js/require "os"))

(defn- now-ms [] (js/performance.now))

(defn- env [k default]
  (or (some-> js/process .-env (aget k)) default))

(defn- rm-rf [p]
  (when (.existsSync fs p)
    (.rmSync fs p #js {:recursive true :force true})))

;; ---------------------------------------------------------------------------
;; Statistics — mirrors upstream's `time-statistics` shape.
;; ---------------------------------------------------------------------------

(defn- time-statistics
  "Given a seq of ms-doubles, return {:mean :median :std :min :max :count :observations}."
  [times]
  (let [n (count times)]
    (if (zero? n)
      {:mean 0 :median 0 :std 0 :min 0 :max 0 :count 0 :observations []}
      (let [sorted (vec (sort times))
            mean   (/ (reduce + times) n)
            var    (/ (reduce + (map (fn [t] (let [d (- t mean)] (* d d))) times)) n)]
        {:mean         mean
         :median       (nth sorted (quot n 2))
         :std          (Math/sqrt var)
         :min          (first sorted)
         :max          (peek sorted)
         :count        n
         :observations (vec times)}))))

(defn- simple-config
  "Strip :store down to a small comparable cfg map — mirrors upstream's
   `simple-cfg` (used in :dh-config of every result row)."
  [cfg]
  (let [backend (get-in cfg [:store :backend])
        nested  (case backend
                  :tiered (get-in cfg [:store :backend-config :backend])
                  backend)]
    {:backend            (if (= :tiered backend) nested backend)
     :tiered?            (= :tiered backend)
     :index              (:index cfg :datahike.index/persistent-set)
     :keep-history?      (:keep-history? cfg false)
     :schema-flexibility (:schema-flexibility cfg :write)}))

;; ---------------------------------------------------------------------------
;; Schema — two modes.
;; ---------------------------------------------------------------------------

(def vault-schema
  [{:db/ident :note/id
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/valueType :db.type/string}
   {:db/ident :note/path
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/valueType :db.type/string}
   {:db/ident :note/text
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string}
   {:db/ident :note/created-at
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/valueType :db.type/long}
   {:db/ident :note/tag
    :db/cardinality :db.cardinality/many
    :db/index true
    :db/valueType :db.type/string}])

(def synthetic-schema
  ;; Mirrors upstream config.clj `schema` (s1/s2 strings, i1/i2 longs).
  ;; Note: upstream uses :db.type/bigint; CLJS datahike doesn't preserve
  ;; bigints losslessly, so we use :db.type/long and pick values that
  ;; fit comfortably in JS Number.
  [{:db/ident :s1 :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :s2 :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :i1 :db/valueType :db.type/long   :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :i2 :db/valueType :db.type/long   :db/cardinality :db.cardinality/one :db/index true}])

;; Map symbolic attr roles (string-1, string-2, int-1, int-2) to actual
;; attrs in each schema. `:int-2` is `nil` for the vault schema — variants
;; that need it get dropped.
(defn- attrs-for [mode]
  (case mode
    :vault      {:s1 :note/path :s2 :note/text :i1 :note/created-at :i2 nil
                :id-attr :note/id}
    :synthetic {:s1 :s1 :s2 :s2 :i1 :i1 :i2 :i2 :id-attr nil}))

;; ---------------------------------------------------------------------------
;; Data generators.
;; ---------------------------------------------------------------------------

(def ^:private tag-pool
  ["vault" "seon" "datahike" "wasmer" "edgejs" "clojure" "cljs"
   "research" "spec" "todo" "meeting" "idea" "decision"])

(defn- pick-tags [seed]
  (let [b (mod (* seed 13) (count tag-pool))
        c (mod (* seed 31) (count tag-pool))]
    (vec (distinct ["vault" (nth tag-pool b) (nth tag-pool c)]))))

(def ^:private base-ts 1700000000000)

(defn- gen-text [i]
  (str "note-" i " body text. Lorem ipsum sit amet at index " i
       " consectetur adipiscing elit, integer scelerisque."))

(defn- gen-vault-note [i id-vec]
  {:note/id         (nth id-vec i)
   :note/path       (str "vault/notes/note-" i ".md")
   :note/text       (gen-text i)
   :note/created-at (+ base-ts (* i 1000))
   :note/tag        (pick-tags i)})

(defn- rand-str [max-int]
  ;; Mirrors upstream's `(format "%15d" (rand-int max-int))` — 15-char numeric
  ;; padded string. Stable string-shape across iterations is what matters.
  (let [s (str (rand-int max-int))
        pad (- 15 (count s))]
    (str (apply str (repeat (max 0 pad) "0")) s)))

(defn- gen-synth-entity [i max-int]
  ;; `i` is the entity index, used only so that the data isn't pure noise —
  ;; values come from rand-int just like upstream.
  {:s1 (rand-str max-int)
   :s2 (rand-str max-int)
   :i1 (rand-int max-int)
   :i2 (rand-int max-int)})

(defn- gen-batch [mode start n id-vec max-int]
  (case mode
    :vault      (mapv (fn [i] (gen-vault-note i id-vec)) (range start (+ start n)))
    :synthetic (mapv (fn [i] (gen-synth-entity i max-int)) (range start (+ start n)))))

;; ---------------------------------------------------------------------------
;; Config builders.
;; ---------------------------------------------------------------------------

(defn- mem-cfg []
  {:store              {:backend :memory :id (random-uuid)}
   :schema-flexibility :write :keep-history? false})

(defn- fs-cfg [store-path]
  {:store              {:backend :file :path store-path :id (random-uuid)}
   :schema-flexibility :write :keep-history? false})

(defn- idb-cfg []
  (let [tid (random-uuid)]
    {:store              {:backend         :tiered
                          :frontend-config {:backend :memory :id tid}
                          :backend-config  {:backend :indexeddb
                                            :name (str "bench-store-" (.getTime (js/Date.)))
                                            :id tid}
                          :id              tid}
     :schema-flexibility :write :keep-history? false}))

;; ---------------------------------------------------------------------------
;; Bulk-load + connection helpers.
;; ---------------------------------------------------------------------------

(defn- bulk-load!
  "Returns a channel yielding {:ms ms-elapsed :batches batch-count :id-vec ids}."
  [conn mode n batch-size]
  (let [id-vec  (mapv #(str "note-id-" %) (range n))
        max-int 1000000000]
    (go
      (let [t0 (now-ms)]
        (loop [start 0 batches 0]
          (if (>= start n)
            {:ms (- (now-ms) t0) :batches batches :id-vec id-vec}
            (let [m      (min batch-size (- n start))
                  batch  (gen-batch mode start m id-vec max-int)
                  tx-rep (<! (d/transact! conn batch))]
              (when (instance? js/Error tx-rep)
                (println "[bench] tx ERR @start=" start ":" (.-message tx-rep)))
              (recur (+ start m) (inc batches)))))))))

(defn- make-store!
  "Create + connect to a fresh store. Returns channel yielding [conn cfg fs-path]."
  [backend]
  (go
    (let [[cfg fs-path] (case backend
                         :memory [(mem-cfg) nil]
                         :fs     (let [p (str (.tmpdir os)
                                              "/vault-bench-" (.getTime (js/Date.))
                                              "-" (rand-int 1000000))]
                                   (rm-rf p)
                                   [(fs-cfg p) p])
                         :idb    [(idb-cfg) nil])]
      (<! (d/create-database cfg))
      [(<! (d/connect cfg {:sync? false})) cfg fs-path])))

(defn- teardown!
  "Best-effort store teardown. konserve.node-filestore needs its directory
   removed; :memory and :indexeddb (under fake-indexeddb) are GC'd."
  [_backend fs-path]
  (when fs-path (rm-rf fs-path)))

;; ---------------------------------------------------------------------------
;; Per-query timing primitive.
;;
;; Wraps a query in N iterations, returns `time-statistics`. Failures (thrown
;; queries) are recorded as `nil`-stats with an `:error` field; the bench
;; carries on so a single misshapen query doesn't kill the run.
;; ---------------------------------------------------------------------------

(defn- run-q!
  "Synchronously execute (apply d/q q-and-args db inputs) once and return ms.
   Throws on error so the caller can record an error result."
  [db q-vec inputs]
  (let [t0 (now-ms)]
    (apply d/q q-vec db inputs)
    (- (now-ms) t0)))

(defn- measure-q!
  "Run query N times, return stats (or {:error ... :count 0} on failure)."
  [db q-vec inputs iterations]
  (try
    (let [obs (vec (for [_ (range iterations)]
                     (run-q! db q-vec inputs)))]
      (time-statistics obs))
    (catch :default e
      {:mean 0 :median 0 :std 0 :min 0 :max 0 :count 0 :observations []
       :error (or (ex-message e) (str e))})))

(defn- measure-pull!
  "Run d/pull N times, return stats."
  [db lookup-ref iterations]
  (try
    (let [obs (vec (for [_ (range iterations)]
                     (let [t0 (now-ms)]
                       (d/pull db '[*] lookup-ref)
                       (- (now-ms) t0))))]
      (time-statistics obs))
    (catch :default e
      {:mean 0 :median 0 :std 0 :min 0 :max 0 :count 0 :observations []
       :error (or (ex-message e) (str e))})))

;; ---------------------------------------------------------------------------
;; Query taxonomy — ported from upstream config.clj, adapted to whichever
;; schema is in play.
;;
;; Each entry returns a vector of `{:function :query :inputs :details}` maps,
;; one per query-variant to run. `:details` carries the executable axes
;; (data-type, data-in-db?) that become the :execution map in the EDN row.
;; ---------------------------------------------------------------------------

(defn- pick-value
  "Pick a representative value for an attr from a sample of entities.
   For vault :note/created-at: value at midpoint, by construction = base-ts + (mid * 1000).
   For vault :note/path: midpoint path string.
   For :note/text: midpoint text body.
   For synthetic: query the DB itself to grab a real value (round-trips
   the same way upstream uses `m-known`)."
  [db mode attr-key attr n]
  (let [mid (quot n 2)]
    (cond
      ;; vault-known constants are deterministic by index.
      (and (= mode :vault) (= attr-key :i1))
      (+ base-ts (* mid 1000))

      (and (= mode :vault) (= attr-key :s1))
      (str "vault/notes/note-" mid ".md")

      (and (= mode :vault) (= attr-key :s2))
      (gen-text mid)

      ;; Synthetic: pull one real value off the DB. If the DB is empty, fall
      ;; back to a known-missing default.
      :else
      (let [hits (d/q [:find '?v :where ['_ attr '?v]] db)]
        (if (seq hits)
          (ffirst hits)
          (case attr-key (:i1 :i2) 0 ""))))))

(defn- pick-miss-value
  "Pick a value known NOT to be in the DB for the given attr-key. vault
   :note/created-at uses base-ts - 1 (predates everything); :note/path uses
   a clearly-out-of-pattern string. Synthetic uses a string longer than 15
   chars or an int beyond the gen range."
  [mode attr-key]
  (case attr-key
    :i1 (if (= mode :vault) (dec base-ts) -1)
    :i2 -1
    :s1 (if (= mode :vault) "vault/notes/__NEVER_GENERATED__.md" "__no_such_string__")
    :s2 (if (= mode :vault) "this body text is not in any note" "__no_such_string__")))

(defn- vector-arg-values
  "Build a vector-arg input: 10 values of the requested attr. For data-in-db?
   we sample existing values; for miss we use the miss value padded out."
  [db mode attr-key attr in-db?]
  (if in-db?
    (let [hits (->> (d/q [:find '?v :where ['_ attr '?v]] db)
                    (map first)
                    (take 10)
                    vec)]
      (if (seq hits) hits [(pick-value db mode attr-key attr 0)]))
    (vec (repeat 10 (pick-miss-value mode attr-key)))))

(defn- non-var-queries
  "Queries that don't depend on a specific 'real' value — they take no inputs
   beyond the DB. data-type axis only (no data-in-db?)."
  [_db mode]
  (let [{s1-attr :s1 s2-attr :s2 i1-attr :i1 i2-attr :i2} (attrs-for mode)
        ;; For vault there's no second long attr — we only run join shapes where
        ;; both attrs share a type (joining int=str values is engine-incoherent
        ;; in datahike CLJS).
        pairs (cond-> []
                (and s1-attr s2-attr) (conj {:dt :str  :a1 s1-attr :a2 s2-attr})
                (and i1-attr i2-attr) (conj {:dt :int  :a1 i1-attr :a2 i2-attr}))]
    (mapcat
     (fn [{:keys [dt a1 a2]}]
       (cond-> [{:function :e-join-query
                 :query    (vec (concat '[:find ?e :where] [['?e a1 '?v1] ['?e a2 '?v2]]))
                 :inputs   []
                 :details  {:data-type dt :a1 a1 :a2 a2}}

                {:function :a-join-query
                 :query    (vec (concat '[:find ?v1 ?v2 :where] [['?e1 a1 '?v1] ['?e2 a1 '?v2]]))
                 :inputs   []
                 :details  {:data-type dt :a1 a1}}

                {:function :v-join-query
                 :query    (vec (concat '[:find ?e1 ?e2 :where] [['?e1 a1 '?v] ['?e2 a2 '?v]]))
                 :inputs   []
                 :details  {:data-type dt :a1 a1 :a2 a2}}

                {:function :equals-query
                 :query    (vec (concat '[:find ?e1 ?e2 :where]
                                        [['?e1 a1 '?v1] ['?e2 a1 '?v2] '[(= ?v1 ?v2)]]))
                 :inputs   []
                 :details  {:data-type dt :a1 a1}}

                {:function :limit-query
                 ;; Upstream uses :offset/:limit map form; CLJS datahike's d/q accepts
                 ;; the conj'd-vec form `:find ?e ?v :where [?e attr ?v]` plus a
                 ;; reduce-on-result limit. To keep the engine doing the work, we run
                 ;; the full query and slice — that's not identical to upstream but
                 ;; close enough to surface scan cost. Documented in asymmetries.
                 :query    (vec (concat '[:find ?e ?v :where] [['?e a1 '?v]]))
                 :inputs   []
                 :details  {:data-type dt :a1 a1 :note "client-side-limit"}}]

         ;; less-than only makes sense for numeric attrs in datahike CLJS — string
         ;; ordering isn't implemented (`No protocol method CollectionOrder.-strictly-decreasing?`).
         (= dt :int)
         (conj {:function :less-than-query
                :query    (vec (concat '[:find ?e1 ?e2 :where]
                                       [['?e1 a1 '?v1] ['?e2 a1 '?v2] '[(< ?v1 ?v2)]]))
                :inputs   []
                :details  {:data-type dt :a1 a1}})))
     pairs)))

(defn- var-queries
  "Queries that depend on a real DB value (or a deliberately-missing one).
   data-type × data-in-db? axes."
  [db mode n]
  (let [{s1-attr :s1 s2-attr :s2 i1-attr :i1 i2-attr :i2} (attrs-for mode)
        attr-roles (cond-> []
                     s1-attr (conj {:dt :str :ak :s1 :attr s1-attr :attr2-ak :s2 :attr2 s2-attr})
                     i1-attr (conj {:dt :int :ak :i1 :attr i1-attr :attr2-ak :i2 :attr2 i2-attr}))]
    (println "[bench] var-queries roles:" (pr-str (mapv #(select-keys % [:dt :ak :attr :attr2]) attr-roles)))
    (mapcat
     (fn [{:keys [dt ak attr attr2-ak attr2]}]
       (println "[bench] var-queries entering role" dt ak attr "/" attr2-ak attr2)
       (mapcat
        (fn [in-db?]
          (println "[bench] var-queries  in-db?=" in-db?)
          (let [v1     (if in-db? (pick-value db mode ak attr n) (pick-miss-value mode ak))
                _      (println "[bench] var-queries   v1=" (pr-str v1))
                v2     (when attr2
                         (if in-db? (pick-value db mode attr2-ak attr2 n) (pick-miss-value mode attr2-ak)))
                _      (println "[bench] var-queries   v2=" (pr-str v2))
                v-comp (pick-value db mode ak attr n)
                _      (println "[bench] var-queries   v-comp=" (pr-str v-comp))
                d      {:data-type dt :data-in-db? in-db? :a1 attr}
                rows   [{:function :simple-query
                         :query   (vec (concat '[:find ?e :where] [['?e attr v1]]))
                         :inputs  []
                         :details d}
                        {:function :scalar-arg-query
                         :query   (vec (concat '[:find ?e :in $ ?v :where] [['?e attr '?v]]))
                         :inputs  [v1]
                         :details d}
                        {:function :scalar-arg-query-with-join
                         :query   (vec (concat '[:find ?e1 ?e2 ?v2
                                                 :in $ ?v1 :where]
                                               [['?e1 attr '?v1]
                                                ['?e2 attr '?v2]]))
                         :inputs  [v1]
                         :details d}
                        {:function :vector-arg-query
                         :query   (vec (concat '[:find ?e :in $ [?v ...] :where] [['?e attr '?v]]))
                         :inputs  [(vector-arg-values db mode ak attr in-db?)]
                         :details d}
                        {:function :equals-query-1-fixed
                         :query   nil ;; built below
                         :inputs  []
                         :details d}]]
            ;; The `*-1-fixed` shapes need a predicate with a concrete value
            ;; embedded; build the predicate term explicitly here. less-than-1-fixed
            ;; is gated on numeric dt (string less-than throws in datahike CLJS).
            (let [eq-pred [(list '= '?v v-comp)]
                  lt-pred [(list '< '?v v-comp)]
                  rows*   (-> rows
                              (assoc-in [4 :query]
                                        (vec (concat '[:find ?e :where]
                                                     [['?e attr '?v] eq-pred]))))
                  rows*   (cond-> rows*
                            (= dt :int)
                            (conj {:function :less-than-query-1-fixed
                                   :query   (vec (concat '[:find ?e :where]
                                                         [['?e attr '?v] lt-pred]))
                                   :inputs  []
                                   :details d}))]
              ;; e-join-{first,second}-fixed need a second attr; skip when attr2 nil
              (cond-> rows*
                attr2
                (into
                 [{:function :e-join-query-first-fixed
                   :query   (vec (concat '[:find ?v2 :where]
                                         [['?e attr v1]
                                          ['?e attr2 '?v2]]))
                   :inputs  []
                   :details (assoc d :a2 attr2)}
                  {:function :e-join-query-second-fixed
                   :query   (vec (concat '[:find ?v1 :where]
                                         [['?e attr '?v1]
                                          ['?e attr2 v2]]))
                   :inputs  []
                   :details (assoc d :a2 attr2)}]))))
        [true false]))
     attr-roles))))

(defn- aggregate-queries
  "Aggregates over a synthetic input vector — engine-aggregate cost. Mirrors
   upstream's `aggregate-queries`."
  [n]
  (let [vals (vec (repeatedly n #(rand-int 100)))]
    [{:function :sum-query
      :query    '[:find (sum ?x) :in [?x ...]]
      :inputs   [vals]
      :details  {:data-type :int}}
     {:function :avg-query
      :query    '[:find (avg ?x) :in [?x ...]]
      :inputs   [vals]
      :details  {:data-type :int}}
     {:function :median-query
      :query    '[:find (median ?x) :in [?x ...]]
      :inputs   [vals]
      :details  {:data-type :int}}
     {:function :variance-query
      :query    '[:find (variance ?x) :in [?x ...]]
      :inputs   [vals]
      :details  {:data-type :int}}
     {:function :stddev-query
      :query    '[:find (stddev ?x) :in [?x ...]]
      :inputs   [vals]
      :details  {:data-type :int}}
     {:function :max-query
      :query    '[:find (max ?x) :in [?x ...]]
      :inputs   [vals]
      :details  {:data-type :int}}]))

(defn- vault-shape-queries
  "The original 5 vault-shaped queries. Kept because Sean cares about them
   even though they overlap conceptually with the upstream taxonomy. Returns
   the same `{:function :query :inputs :details}` shape so the runner can
   treat them uniformly."
  [db mode id-vec n]
  (when (= mode :vault)
    (let [pick-idx  (quot n 2)
          pick-id   (nth id-vec pick-idx)
          pick-path (str "vault/notes/note-" pick-idx ".md")
          lo        base-ts
          hi        (+ lo (* (quot n 10) 1000))]
      [{:function :vault/scan-all
        :query    '[:find (count ?e) :where [?e :note/path _]]
        :inputs   []
        :details  {}}
       {:function :vault/scan-by-tag
        :query    '[:find ?p :in $ ?t :where [?e :note/tag ?t] [?e :note/path ?p]]
        :inputs   ["vault"]
        :details  {}}
       {:function :vault/indexed-by-id
        :query    '[:find ?p :in $ ?id :where [?e :note/id ?id] [?e :note/path ?p]]
        :inputs   [pick-id]
        :details  {}}
       {:function :vault/pull-by-path
        :query    nil :pull? true :lookup-ref [:note/path pick-path]
        :inputs   []
        :details  {}}
       {:function :vault/range-by-time
        :query    '[:find (count ?e) :in $ ?lo ?hi
                    :where
                    [?e :note/created-at ?t]
                    [(>= ?t ?lo)]
                    [(<= ?t ?hi)]]
        :inputs   [lo hi]
        :details  {:data-type :int}}])))

(defn- safely
  "Realize seq xs into a vector, catching any throw. Returns [vec-of-results err]."
  [label thunk]
  (try
    [(vec (thunk)) nil]
    (catch :default e
      (println (str "[bench] " label " threw: " (or (ex-message e) (str e))))
      [[] e])))

(defn- all-queries
  "Build the full list of query specs for the running DB. Each builder runs
   independently; if one throws, only that group is dropped."
  [db mode id-vec n]
  (let [[a _] (safely "vault-shape-queries" #(vault-shape-queries db mode id-vec n))
        [b _] (safely "non-var-queries"    #(non-var-queries db mode))
        [c _] (safely "var-queries"        #(var-queries db mode n))
        [d _] (safely "aggregate-queries"  #(aggregate-queries (min n 10000)))]
    (concat a b c d)))

;; ---------------------------------------------------------------------------
;; Asymmetry annotations.
;; ---------------------------------------------------------------------------

(def asymmetries
  {:memory "pure in-process; no persistence, no I/O cost; reset each run."
   :fs     "real disk via konserve.node-filestore; persistence cost included; macOS APFS / SSD latency dominates writes."
   :idb    "fake-indexeddb shim under Node, NOT a real browser IndexedDB; reflects algorithm cost in CLJS, not real-browser IDB persistence."
   :sqlite "(not yet wired) — would use sql.js, in-memory only; real SQLite query planner, no disk."
   :methodology
   {:limit-query        "client-side .slice applied to full query result — engine still does full scan."
    :less-1-fixed       "predicate baked into query body (no `:in`); planner sees the constant."
    :aggregates         "operate on a synthetic input vector, not the DB — engine-aggregate cost only."
    :vault-int+str-pair  "vault schema has no second :db.type/long attr, so e-join across two ints is run with :note/created-at × :note/path (int×str) and flagged :paired-int+str true."
    :data-in-db-false   "miss-value is constructed to be lexically/numerically unreachable; for vault :i1 we use (dec base-ts), for :s1 a path with __NEVER_GENERATED__."
    :iterations         "default 10 iters per measurement; JS GC and JIT warmup are not isolated — std reflects them."
    :tx-batch           "tx-batches drawn with retract afterwards so DB size stays at db-entities for subsequent batches (mirrors upstream's :db.purge/entity pattern, adapted to no-history)."}})

;; ---------------------------------------------------------------------------
;; Driver — for each (backend × size) run a full bench, collect rows, emit.
;; ---------------------------------------------------------------------------

(defn- run-queries!
  "For a connected, populated DB, run the full query taxonomy and return
   a vec of context+time rows."
  [db conn cfg mode id-vec n iterations]
  (let [datom-count (* n (count (if (= mode :vault) vault-schema synthetic-schema)))
        simple-cfg  (simple-config cfg)
        specs       (try
                      (vec (all-queries db mode id-vec n))
                      (catch :default e
                        (println "[bench] all-queries threw:" (ex-message e))
                        []))]
    (println (str "[bench] specs: " (count specs) " query specs"))
    (vec
     (for [{:keys [function query inputs details pull? lookup-ref]} specs]
       (let [stats (if pull?
                     (measure-pull! db lookup-ref iterations)
                     (measure-q! db query inputs iterations))
             row   {:context (cond-> {:dh-config   simple-cfg
                                      :function    function
                                      :db-entities n
                                      :db-datoms   datom-count}
                               (seq details) (assoc :execution details))
                    :time    stats}]
         (when (:error stats)
           (println (str "[bench] query " function " " (pr-str details)
                         " ERR: " (:error stats))))
         row)))))

(defn- gen-tx-entity
  "Generate one entity for a transaction-time measurement. Uses a unique
   id/path namespace (`tx-{iter}-{idx}`) so it can't collide with bulk-loaded
   entities — vault uniqueness is on :note/id + :note/path."
  [mode iter idx max-int]
  (case mode
    :vault      {:note/id         (str "tx-id-" iter "-" idx)
                :note/path       (str "tx-path-" iter "-" idx ".md")
                :note/text       (str "tx text iter=" iter " idx=" idx)
                :note/created-at (+ base-ts 10000000000 (* iter 1000000) (* idx 1000))
                :note/tag        ["tx-tag" (str "tx-iter-" iter)]}
    :synthetic {:s1 (rand-str max-int)
                :s2 (rand-str max-int)
                :i1 (rand-int max-int)
                :i2 (rand-int max-int)}))

(defn- measure-transaction-times!
  "Run `iterations` tx-batches at each batch-size in `tx-batches`. After each
   batch we retract the entities transacted, so the DB stays at db-entities
   across iterations (mirrors upstream's `:db.purge/entity` pattern, adapted
   to a no-history DB where we just :db/retractEntity)."
  [conn cfg mode n iterations tx-batches]
  (go-loop [rows [] sizes tx-batches]
    (if (empty? sizes)
      rows
      (let [tx-size (first sizes)
            schema  (if (= mode :vault) vault-schema synthetic-schema)
            datoms  (* tx-size (count schema))
            obs     (atom [])]
        (loop [i 0]
          (when (< i iterations)
            (let [batch  (mapv (fn [idx] (gen-tx-entity mode i idx 1000000000))
                               (range tx-size))
                  t0     (now-ms)
                  result (<! (d/transact! conn batch))
                  ms     (- (now-ms) t0)]
              (swap! obs conj ms)
              ;; Retract the entities we just added so subsequent tx-batches
              ;; see the same DB shape.
              (when (and (not (instance? js/Error result))
                         (:tx-data result))
                (let [eids (->> (:tx-data result)
                                (filter (fn [d] (some? (:e d))))
                                (map :e)
                                (into #{}))]
                  (<! (d/transact! conn (mapv (fn [e] [:db/retractEntity e]) eids)))))
              (recur (inc i)))))
        (recur (conj rows
                     {:context {:dh-config   (simple-config cfg)
                                :function    :transaction
                                :db-entities n
                                :db-datoms   (* n (count schema))
                                :execution   {:tx-entities tx-size :tx-datoms datoms}}
                      :time    (time-statistics @obs)})
               (rest sizes))))))

(defn- measure-connection-times!
  "Repeatedly d/connect to an existing populated store; report stats."
  [cfg mode n iterations]
  (go-loop [i 0 obs []]
    (if (>= i iterations)
      (let [schema (if (= mode :vault) vault-schema synthetic-schema)]
        {:context {:dh-config   (simple-config cfg)
                   :function    :connection
                   :db-entities n
                   :db-datoms   (* n (count schema))}
         :time    (time-statistics obs)})
      (let [t0   (now-ms)
            conn (<! (d/connect cfg {:sync? false}))
            ms   (- (now-ms) t0)]
        ;; CLJS datahike doesn't have an explicit d/release; let conn be GC'd.
        (recur (inc i) (conj obs ms))))))

(defn- run-bench-once!
  "One backend × size run. Returns channel yielding {:rows [...] :load-stats {...} :backend :size}."
  [backend mode size {:keys [batch-size iterations tx-batches]}]
  (go
    (println (str "\n[bench] backend=" backend " size=" size
                  " mode=" mode " iters=" iterations " batch=" batch-size))
    (let [t-setup0       (now-ms)
          [conn cfg fs-path] (<! (make-store! backend))
          schema         (if (= mode :vault) vault-schema synthetic-schema)
          _              (<! (d/transact! conn schema))
          t-setup        (- (now-ms) t-setup0)
          _              (println (str "[bench] setup: " (.toFixed t-setup 1) "ms"))

          {:keys [ms batches id-vec]} (<! (bulk-load! conn mode size batch-size))
          load-ms        ms
          throughput     (.toFixed (/ size (/ load-ms 1000)) 0)
          _              (println (str "[bench] load: " (.toFixed load-ms 1) "ms ("
                                       throughput " entities/s across " batches " batches)"))

          ;; Sanity scan
          sanity (count (d/q '[:find ?e :where [?e]] @conn))
          _      (println (str "[bench] sanity scan: " sanity " datoms-with-?e"))

          query-rows (run-queries! @conn conn cfg mode id-vec size iterations)
          _          (println (str "[bench] query rows: " (count query-rows)))

          tx-rows (<! (measure-transaction-times! conn cfg mode size iterations tx-batches))
          _       (println (str "[bench] tx-batch rows: " (count tx-rows)))

          conn-row (<! (measure-connection-times! cfg mode size iterations))

          ;; Load result as a context+time row too — `:bulk-load` over `batch-size`.
          load-row {:context {:dh-config   (simple-config cfg)
                              :function    :bulk-load
                              :db-entities size
                              :db-datoms   (* size (count schema))
                              :execution   {:batch-size batch-size :batches batches}}
                   :time    {:mean load-ms :median load-ms :std 0
                             :min load-ms :max load-ms :count 1
                             :observations [load-ms]}}]
      (teardown! backend fs-path)
      {:backend backend
       :size    size
       :rows    (vec (concat [load-row conn-row] tx-rows query-rows))
       :load-ms load-ms
       :load-eps (parse-long throughput)
       :setup-ms t-setup})))

;; ---------------------------------------------------------------------------
;; Summary printers.
;; ---------------------------------------------------------------------------

(defn- pad [s n]
  (let [s (str s)]
    (subs (str s "                                       ") 0 (min n (count (str s "                                       "))))))

(defn- fmt-ms [x]
  (if (number? x) (.toFixed x 2) (str x)))

(defn- print-asymmetries []
  (println "\n========== asymmetry annotations ==========")
  (doseq [[k v] (dissoc asymmetries :methodology)]
    (println (str " " (pad (str k) 10) " — " v)))
  (println "\n -- methodology --")
  (doseq [[k v] (:methodology asymmetries)]
    (println (str " " (pad (str k) 22) " — " v))))

(defn- row->summary [{:keys [backend size]} row]
  (let [{:keys [function db-datoms execution]} (:context row)
        {:keys [mean median std min max]}      (:time row)]
    {:backend  backend
     :size     size
     :datoms   db-datoms
     :function function
     :dt       (or (:data-type execution) "-")
     :indb?    (if (contains? execution :data-in-db?)
                 (if (:data-in-db? execution) "hit" "miss")
                 "-")
     :median   median
     :mean     mean
     :std      std
     :min      min
     :max      max
     :error    (:error (:time row))}))

(defn- group-key [s]
  (let [fn-name (str (:function s))]
    (cond
      (.startsWith fn-name ":vault/") "vault-shape"
      (#{:simple-query :scalar-arg-query :scalar-arg-query-with-join
         :vector-arg-query} (:function s)) "var-queries"
      (#{:e-join-query :a-join-query :v-join-query
         :e-join-query-first-fixed :e-join-query-second-fixed} (:function s)) "joins"
      (#{:equals-query :equals-query-1-fixed
         :less-than-query :less-than-query-1-fixed} (:function s)) "predicates"
      (#{:limit-query} (:function s)) "limit"
      (#{:sum-query :avg-query :median-query :variance-query
         :stddev-query :max-query} (:function s)) "aggregates"
      (= :bulk-load (:function s)) "bulk-load"
      (= :transaction (:function s)) "transaction"
      (= :connection (:function s)) "connection"
      :else "other")))

(defn- print-summary [results]
  (println "\n========== summary ==========")
  (let [all (vec (for [{:keys [backend size rows] :as r} results
                       row rows]
                   (row->summary r row)))
        grouped (group-by group-key all)
        order ["bulk-load" "connection" "transaction"
               "vault-shape" "var-queries" "joins" "predicates" "limit"
               "aggregates" "other"]]
    (println (pad "group" 12) (pad "backend" 9) (pad "size" 7)
             (pad "fn" 32) (pad "dt" 8) (pad "indb?" 6)
             (pad "median" 10) (pad "std" 10) (pad "min" 10) (pad "max" 10))
    (doseq [g order
            :let [rows (get grouped g)]
            :when rows]
      (println (str "-- " g " --"))
      (doseq [s (sort-by (juxt :backend :size :function :dt :indb?) rows)]
        (println (pad g 12)
                 (pad (name (:backend s)) 9)
                 (pad (:size s) 7)
                 (pad (str (:function s)) 32)
                 (pad (str (:dt s)) 8)
                 (pad (:indb? s) 6)
                 (pad (fmt-ms (:median s)) 10)
                 (pad (fmt-ms (:std s)) 10)
                 (pad (fmt-ms (:min s)) 10)
                 (pad (fmt-ms (:max s)) 10)
                 (when (:error s) (str " ERR:" (:error s))))))))

;; ---------------------------------------------------------------------------
;; Runner.
;; ---------------------------------------------------------------------------

(defn- parse-csv [raw]
  (->> (.split raw ",") (map #(.trim %)) (remove empty?) vec))

(defn- parse-sizes []
  (mapv parse-long (parse-csv (env "BENCH_SIZES" "1000,10000"))))

(defn- parse-backends []
  (mapv keyword (parse-csv (env "BENCH_BACKENDS" "memory,fs,idb"))))

(defn- parse-tx-batches []
  (mapv parse-long (parse-csv (env "BENCH_TX_BATCHES" "100,1000"))))

(defn- ensure-out-dir [path]
  (let [parent (.dirname (js/require "path") path)]
    (when (and parent (not (.existsSync fs parent)))
      (.mkdirSync fs parent #js {:recursive true}))))

(defn- write-edn! [path data]
  (ensure-out-dir path)
  (let [s (with-out-str (pp/pprint data))]
    (.writeFileSync fs path s)
    (println (str "[bench] wrote " path " (" (count s) " bytes)"))))

(defn main [& _args]
  (println "[bench] indexedDB present?" (boolean (.-indexedDB js/globalThis)))
  (let [sizes      (parse-sizes)
        backends   (parse-backends)
        batch-size (parse-long (env "BENCH_BATCH" "1000"))
        iterations (parse-long (env "BENCH_ITERATIONS" "10"))
        tx-batches (parse-tx-batches)
        mode       (keyword (env "BENCH_SCHEMA" "vault"))
        out-path   (env "BENCH_OUT" "out/bench-results.edn")
        opts       {:batch-size batch-size :iterations iterations :tx-batches tx-batches}]
    (println (str "[bench] sizes=" sizes
                  " backends=" backends
                  " iterations=" iterations
                  " batch=" batch-size
                  " tx-batches=" tx-batches
                  " schema=" mode))
    (print-asymmetries)
    (go
      (let [results (atom [])]
        (doseq [backend backends
                size    sizes]
          (cond
            (and (= backend :idb) (> size 100000))
            (println (str "[bench] skip " backend " size=" size " (fake-indexeddb too slow above 100k)"))

            :else
            (let [r (<! (run-bench-once! backend mode size opts))]
              (swap! results conj r))))
        (print-summary @results)
        (let [edn-data
              {:meta         {:run-at         (.toISOString (js/Date.))
                              :schema-mode    mode
                              :sizes          sizes
                              :backends       backends
                              :iterations     iterations
                              :bulk-batch     batch-size
                              :tx-batches     tx-batches}
               :asymmetries  asymmetries
               :results      (vec (mapcat :rows @results))}]
          (write-edn! out-path edn-data))
        (println "\n[bench] done")))))
