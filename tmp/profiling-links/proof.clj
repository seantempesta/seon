;; Lane profiling-and-links-research-opus, 2026-09-28. Disposable REPL probes.
;; Evaluated section by section through MCP eval_clj, JVM mode, namespace
;; tmp.profiling-links on default (pid 9778). Nothing here interns into a seon.*
;; namespace or rebinds a Var root; scratch cells are weakly held and released.
;; Results are recorded in
;; docs/research/agent-platform/profiling-and-related-data-2026-09-28.md.
(ns tmp.profiling-links
  (:require [seon.batch]
            [seon.profile]))

;;; ===========================================================================
;;; §A1 Inventory probe: what one armed call pays today
;;; ===========================================================================

(def armed @#'seon.profile/adder?)
(def original (:malli.instrument/original (meta armed)))

(defn bench [f arg n]
  (dotimes [_ 200000] (f arg))
  (let [t0 (System/nanoTime)]
    (dotimes [_ n] (f arg))
    (double (/ (- (System/nanoTime) t0) n))))

(def scratch-cell (seon.profile/cell {:seon.profile/sym 'tmp.profiling-links/probe
                                      :seon.profile/scope :seon.profile/host}))
(def timed-only (seon.profile/with-cell scratch-cell (fn [& args] (seon.profile/timed (apply original args)))))
(def timed-only-1 (seon.profile/with-cell scratch-cell (fn [x] (seon.profile/timed (original x)))))
(def apply-only (fn [& args] (apply original args)))

(comment
  (let [x (java.util.concurrent.atomic.LongAdder.) n 2000000]
    {:original-ns (bench original x n)
     :apply-only-ns (bench apply-only x n)
     :timed-varargs-ns (bench timed-only x n)
     :timed-fixed-ns (bench timed-only-1 x n)
     :armed-ns (bench armed x n)
     :armed-in-world-ns (binding [seon.batch/*world* {:seon.batch/sink (seon.batch/sink)}] (bench armed x n))})
  ;; => {:original-ns 0.9, :apply-only-ns 17.1, :timed-varargs-ns 50.3, :timed-fixed-ns 34.4,
  ;;     :armed-ns 106.6, :armed-in-world-ns 110.3}
  )

;;; ===========================================================================
;;; §A2 The proposed accumulation: `timed` + per-world time + self time
;;;      + a log2 histogram on the lifetime cell. No allocation per call.
;;; ===========================================================================

;; One per-thread frame: the nanos the current entry's callees have used.
;; Self time = elapsed - children. A long[1], created once per thread.
(def ^ThreadLocal frame
  (ThreadLocal/withInitial (reify java.util.function.Supplier (get [_] (long-array 1)))))

;; A world entry: [calls total-ns self-ns] for one executed definition in one
;; evaluation. AtomicLongArray: one object, created at the definition's first
;; call in that world only.
(def ^java.util.function.Function new-slot
  (reify java.util.function.Function
    (apply [_ _] (java.util.concurrent.atomic.AtomicLongArray. 3))))

(defn cell2
  "The lifetime cell plus a self-time adder and a 48-bucket log2 histogram."
  [sym digest]
  {:sym sym :digest digest :key [sym digest]
   :calls (java.util.concurrent.atomic.LongAdder.)
   :total (java.util.concurrent.atomic.LongAdder.)
   :self (java.util.concurrent.atomic.LongAdder.)
   :max (java.util.concurrent.atomic.LongAccumulator.
         (reify java.util.function.LongBinaryOperator (applyAsLong [_ a b] (Math/max a b))) 0)
   :histogram (java.util.concurrent.atomic.AtomicLongArray. 48)
   :entered (ThreadLocal.)})

(defn exit!
  "Account one outermost exit: lifetime cell, histogram, and the world slot.
  (The MCP JVM reader is edamame, whose syntax-quote qualifies `.method`
  symbols, so the prototype spells the bracket as a function; the landed
  version stays the `seon.profile/timed` macro.)"
  [c ^ThreadLocal entered prior sink ^longs fr outer-children started]
  (let [elapsed (- (System/nanoTime) (long started))
        self (- elapsed (aget fr 0))]
    (aset fr 0 (+ (long outer-children) elapsed))
    (.set entered prior)
    (when (nil? prior)
      (.increment ^java.util.concurrent.atomic.LongAdder (:calls c))
      (.add ^java.util.concurrent.atomic.LongAdder (:total c) elapsed)
      (.add ^java.util.concurrent.atomic.LongAdder (:self c) self)
      (.accumulate ^java.util.concurrent.atomic.LongAccumulator (:max c) elapsed)
      (.incrementAndGet ^java.util.concurrent.atomic.AtomicLongArray (:histogram c)
                        (- 64 (Long/numberOfLeadingZeros (max 1 elapsed)))))
    (when sink
      (let [^java.util.concurrent.atomic.AtomicLongArray slot
            (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap sink (:key c) new-slot)]
        (.incrementAndGet slot 0)
        (.addAndGet slot 1 elapsed)
        (.addAndGet slot 2 self)))))

(defn wrap2
  "`f` bracketed by the proposed accounting (varargs, as the armed wrappers are)."
  [c f]
  (let [^ThreadLocal entered (:entered c)]
    (fn [& args]
      (let [prior (.get entered)
            sink (:seon.batch/sink seon.batch/*world*)
            here (or sink seon.profile/host-entry)]
        (if (identical? prior here)
          (apply f args)
          (let [^longs fr (.get ^ThreadLocal frame)
                outer (aget fr 0)
                started (System/nanoTime)]
            (aset fr 0 0)
            (.set entered here)
            (try (apply f args)
                 (finally (exit! c entered prior sink fr outer started)))))))))

(defn wrap2-1
  "The same bracket at a fixed arity of one."
  [c f]
  (let [^ThreadLocal entered (:entered c)]
    (fn [x]
      (let [prior (.get entered)
            sink (:seon.batch/sink seon.batch/*world*)
            here (or sink seon.profile/host-entry)]
        (if (identical? prior here)
          (f x)
          (let [^longs fr (.get ^ThreadLocal frame)
                outer (aget fr 0)
                started (System/nanoTime)]
            (aset fr 0 0)
            (.set entered here)
            (try (f x)
                 (finally (exit! c entered prior sink fr outer started)))))))))

(def probe-cell (cell2 'tmp.profiling-links/probe nil))
(def timed2-1 (wrap2-1 probe-cell original))
(def timed2-varargs (wrap2 probe-cell original))

(comment
  (let [x (java.util.concurrent.atomic.LongAdder.) n 2000000]
    {:original-ns (bench original x n)
     :timed-fixed-ns (bench timed-only-1 x n)
     :timed2-fixed-ns (bench timed2-1 x n)
     :timed2-in-world-ns (binding [seon.batch/*world* {:seon.batch/sink (seon.batch/sink)}] (bench timed2-1 x n))}))

;;; §A2b The bracket as the landed macro would compile it: accumulators
;;; captured as hinted locals at install (as `seon.profile/with-cell` does),
;;; the exit inlined, primitive longs throughout, so nothing boxes.
(defn wrap3-1
  [c f]
  (let [^ThreadLocal entered (:entered c)
        ^ThreadLocal fr-local frame
        ^java.util.concurrent.atomic.LongAdder calls (:calls c)
        ^java.util.concurrent.atomic.LongAdder total (:total c)
        ^java.util.concurrent.atomic.LongAdder self-adder (:self c)
        ^java.util.concurrent.atomic.LongAccumulator longest (:max c)
        ^java.util.concurrent.atomic.AtomicLongArray histogram (:histogram c)
        k (:key c)]
    (fn [x]
      (let [prior (.get entered)
            sink (:seon.batch/sink seon.batch/*world*)
            here (or sink seon.profile/host-entry)]
        (if (identical? prior here)
          (f x)
          (let [^longs fr (.get fr-local)
                outer (aget fr 0)
                started (System/nanoTime)]
            (aset fr 0 0)
            (.set entered here)
            (try (f x)
                 (finally
                   (let [elapsed (- (System/nanoTime) started)
                         self (- elapsed (aget fr 0))]
                     (aset fr 0 (+ outer elapsed))
                     (.set entered prior)
                     (when (nil? prior)
                       (.increment calls)
                       (.add total elapsed)
                       (.add self-adder self)
                       (.accumulate longest elapsed)
                       (.incrementAndGet histogram (- 64 (Long/numberOfLeadingZeros (max 1 elapsed)))))
                     (when sink
                       (let [^java.util.concurrent.atomic.AtomicLongArray slot
                             (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap sink k new-slot)]
                         (.incrementAndGet slot 0)
                         (.addAndGet slot 1 elapsed)
                         (.addAndGet slot 2 self))))))))))))

(def timed3-1 (wrap3-1 probe-cell original))

(comment
  (let [x (java.util.concurrent.atomic.LongAdder.) n 3000000
        run (fn [] {:original-ns (bench original x n)
                    :timed-fixed-ns (bench timed-only-1 x n)
                    :timed-fixed-in-world-ns (binding [seon.batch/*world* {:seon.batch/sink (seon.batch/sink)}] (bench timed-only-1 x n))
                    :timed3-fixed-ns (bench timed3-1 x n)
                    :timed3-fixed-in-world-ns (binding [seon.batch/*world* {:seon.batch/sink (seon.batch/sink)}] (bench timed3-1 x n))})]
    [(run) (run) (run)]))

;;; ===========================================================================
;;; §A3 Per-evaluation hotspot report on real work (see doc §2.3)
;;; ===========================================================================

(require '[datahike.api :as d] '[malli.core :as m] '[malli.registry :as mr])
(def conn (seon.cluster.boot/connection "default"))
(def db0 (seon.db/db conn))

(defn measured
  "Run thunk in a fresh world; its sink's call counts are exact per world;
  inclusive nanos are JVM-wide cell deltas restricted to the sink's keys
  (the loaded HEAD's sink holds counts only)."
  [thunk]
  (let [sink (seon.batch/sink)
        mark (seon.profile/begin)
        t0 (System/nanoTime)
        value (binding [seon.batch/*world* {:seon.batch/sink sink}] (thunk))
        wall (/ (- (System/nanoTime) t0) 1e6)
        growth (:seon.profile/growths (seon.profile/explain mark 100000))
        ms-by-sym (into {} (map (juxt :seon.profile/sym :seon.profile/total-ms)) growth)
        counts (seon.batch/calls sink)]
    {:value value :wall-ms wall
     :rows (->> counts
                (map (fn [[sym calls]] {:sym sym :calls calls :ms (get ms-by-sym sym 0)}))
                (sort-by (juxt (comp - :ms) (comp - :calls)))
                vec)}))

(defn hotspots
  "The per-evaluation hotspot report: top n by time, plus every definition
  called at least `hot` times, as columns and rows (the dir shape)."
  [rows wall-ms n hot]
  (let [top (take n rows)
        topset (set (map :sym top))
        frequent (->> rows (remove (comp topset :sym)) (filter #(<= hot (:calls %))) (sort-by :calls >) (take n))]
    {:seon.profile/wall-ms (Math/round (double wall-ms))
     :seon.profile/armed-calls (reduce + (map :calls rows))
     :seon.profile/definitions (count rows)
     :seon.repl/columns [:sym :ms :calls]
     :seon.repl/rows (mapv (juxt :sym :ms :calls) (concat top frequent))}))

(comment
  (def r1 (measured #(seon.render.var/status db0 'seon.profile/top)))
  (hotspots (:rows r1) (:wall-ms r1) 5 500)
  ;; => 144 tokens; see doc §2.3
  )

;;; ===========================================================================
;;; §A4 Persistence cost: one observation entity per [sym digest], d/with only
;;; ===========================================================================

(def profile-schema
  [{:db/ident :seon.profile/key :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :seon.profile/sym :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :seon.profile/digest :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :seon.profile/calls :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :seon.profile/total-ns :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :seon.profile/self-ns :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :seon.profile/max-ns :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :seon.profile/p99-ns :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])

(defn observation-rows [obs]
  (mapv (fn [o] {:seon.profile/key (seon.id/id [(:seon.profile/sym o) (:seon.profile/digest o)])
                 :seon.profile/sym (:seon.profile/sym o) :seon.profile/digest (:seon.profile/digest o)
                 :seon.profile/calls (:seon.profile/calls o) :seon.profile/total-ns (* 1000000 (:seon.profile/total-ms o))
                 :seon.profile/self-ns 0 :seon.profile/max-ns (* 1000000 (:seon.profile/max-ms o)) :seon.profile/p99-ns 0})
        obs))

(comment
  (let [observed (->> (seon.profile/cells) (map seon.profile/observation)
                      (filter #(and (:seon.profile/digest %) (pos? (:seon.profile/calls %)))) vec)
        with-schema (:db-after (d/with db0 profile-schema))]
    (time (d/with with-schema (observation-rows observed))))
  ;; => 1485 observations: 118 ms first write, 45 ms rewrite, 100 rows 10 ms, read one 0.6 ms
  )

;;; ===========================================================================
;;; §B1 Value references derived from schema declarations (doc §3.2)
;;; ===========================================================================

(def proposed-reference-to
  "Declarations the prototype assumes (doc §3.2): today only
  `:seon.fn/reference-to` on three render attributes and the schedule task exist."
  {:seon.program/edge-symbol #{:seon.fn/sym :seon.test/sym}
   :seon.instrument/fn #{:seon.fn/sym}
   :seon.profile/sym #{:seon.fn/sym}})

(defn named-leaf
  "The named schema an attribute's value schema bottoms out in: unwrap
  :set/:vector/:sequential/:and/:maybe, stop at a qualified keyword."
  [reg k]
  (loop [form (some-> (mr/schema reg k) m/form) seen #{}]
    (cond
      (nil? form) nil
      (and (qualified-keyword? form) (not (seen form))) (if (= form k) nil form)
      (and (vector? form) (#{:set :vector :sequential :and :maybe} (first form)))
      (let [children (drop (if (map? (second form)) 2 1) form)]
        (recur (first (filter #(or (qualified-keyword? %) (vector? %)) children)) (conj seen form)))
      :else nil)))

(defn structure
  "Derived once per projection (2.7 ms on default): identity attributes, ref
  attributes, each value attribute -> the identity attributes its values name."
  [db]
  (let [projection (seon.db/carried-projection db)
        reg (:seon.schema.projection/registry projection)
        schema (:schema (seon.db/schema-database db))
        attrs (into [] (filter keyword?) (keys schema))
        props (fn [k] (some-> (mr/schema reg k) m/properties))
        identities (into #{} (filter #(:seon.db/identity (props %))) attrs)
        refs (into #{} (filter #(= :db.type/ref (get-in schema [% :db/valueType]))) attrs)
        by-leaf (reduce (fn [m i] (if-let [leaf (named-leaf reg i)] (update m leaf (fnil conj #{}) i) m)) {} identities)
        refers (fn [a]
                 (let [leaf (named-leaf reg a)]
                   (not-empty (into #{} (remove #{a})
                                    (concat (get by-leaf leaf)
                                            (some-> (:seon.fn/reference-to (props a)) vector)
                                            (get proposed-reference-to leaf)
                                            (get proposed-reference-to a))))))]
    {:identities identities
     :refs refs
     :value-refs (into {} (keep (fn [a] (when-not (or (identities a) (refs a)) (some->> (refers a) (vector a))))) attrs)
     :indexed (into #{} (filter #(or (get-in schema [% :db/index]) (get-in schema [% :db/unique]))) attrs)}))

;;; ===========================================================================
;;; §B2 One hop both ways from an entity, rendered by each item's own render
;;; ===========================================================================

(defn identity-of
  "The first identity attribute and value an entity carries, as a lookup."
  [db identities e]
  (some (fn [^datahike.datom.Datom dt] (when (identities (.-a dt)) [(.-a dt) (.-v dt)]))
        (d/datoms db :eavt e)))

(defn related-edges
  "Forward refs, forward value refs, reverse refs (AVET per ref attribute),
  reverse value refs (AVET, or one AEVT scan when the attribute is unindexed)."
  [db {:keys [identities refs value-refs indexed]} e limit]
  (let [own (d/datoms db :eavt e)
        own-ids (into {} (keep (fn [^datahike.datom.Datom dt] (when (identities (.-a dt)) [(.-a dt) (.-v dt)]))) own)
        group (fn [k xs] (when (seq xs) [k {:count (count xs) :items (vec (take limit xs))}]))
        holders (fn [a v] (if (indexed a)
                            (map #(.-e ^datahike.datom.Datom %) (d/datoms db :avet a v))
                            (d/q '[:find [?h ...] :in $ ?a ?v :where [?h ?a ?v]] db a v)))
        fwd-ref (->> own (filter #(refs (.-a ^datahike.datom.Datom %))) (group-by #(.-a ^datahike.datom.Datom %))
                     (keep (fn [[a ds]] (group [:forward a] (mapv #(identity-of db identities (.-v ^datahike.datom.Datom %)) ds)))))
        fwd-val (->> own (filter #(value-refs (.-a ^datahike.datom.Datom %))) (group-by #(.-a ^datahike.datom.Datom %))
                     (keep (fn [[a ds]]
                             (group [:forward a]
                                    (vec (for [^datahike.datom.Datom dt ds i (value-refs a)
                                               :when (seq (d/datoms db :avet i (.-v dt)))]
                                           [i (.-v dt)]))))))
        rev-ref (keep (fn [a] (group [:reverse a] (mapv #(identity-of db identities (.-e ^datahike.datom.Datom %))
                                                       (take (inc (* 50 limit)) (d/datoms db :avet a e)))))
                      refs)
        rev-val (for [[a targets] value-refs
                      [i v] own-ids
                      :when (targets i)
                      :let [g (group [:reverse a] (mapv #(identity-of db identities %) (holders a v)))]
                      :when g]
                  g)]
    (into (sorted-map-by #(compare (str %1) (str %2))) (concat fwd-ref fwd-val rev-ref rev-val))))

(defn timed-ms [f] (let [t0 (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t0) 1e6) v]))

(def reg (:seon.schema.projection/registry (seon.db/carried-projection db0)))
(def schema-props (fn [k] (some-> (mr/schema reg k) m/properties)))

(defn render-sym-for
  "The AI render the identity attribute's row schema declares, or nil."
  [identity-attr]
  (some-> (schema-props identity-attr) :seon.program/row-schema schema-props :seon.render/ai))

(def proposed-renders
  "Distance-0 renders the prototype assumes: a profile observation (new
  schema) and a test's name and state without the host walk (doc §3.4)."
  {:seon.profile/key (fn [db lookup]
                       (let [p (d/pull db [:seon.profile/sym :seon.profile/digest :seon.profile/calls :seon.profile/total-ns :seon.profile/max-ns] lookup)
                             current (:seon.program/definition-digest (d/pull db [:seon.program/definition-digest] [:seon.fn/sym (:seon.profile/sym p)]))]
                         {:seon.profile/calls (:seon.profile/calls p)
                          :seon.profile/max-ms (quot (:seon.profile/max-ns p) 1000000)
                          :seon.profile/mean-us (quot (:seon.profile/total-ns p) (* 1000 (max 1 (:seon.profile/calls p))))
                          :seon.profile/current? (= current (:seon.profile/digest p))}))
   :seon.test/sym (fn [db [_ sym]]
                    (let [r (seon.test/recorded-result db sym)]
                      {:seon.test/sym sym
                       :seon.render.test/state (cond (not (map? r)) "unrun"
                                                     (pos? (get r :seon.test/error-count 0)) "error"
                                                     (pos? (get r :seon.test/fail-count 0)) "fail"
                                                     (pos? (get r :seon.test/pass-count 0)) "pass"
                                                     :else "unrun")}))})

(defn item-view
  "One related entity at distance 0 through its own declared AI render."
  [db [a _ :as lookup]]
  (if-let [f (get proposed-renders a)]
    (f db lookup)
    (if-let [sym (render-sym-for a)]
      (let [render (requiring-resolve sym)]
        (try (render {:seon.render/value (d/pull db '[*] lookup) :seon.render/distance 0 :seon.db/db db})
             (catch Throwable t {:lookup lookup :render-failed (str sym) :message (ex-message t)})))
      lookup)))

(defn related
  "`:seon.related/data` for `lookup`. Items are entities whose identity's row
  schema declares an AI render, rendered at distance 0; the rest are counted
  under :seon.related/skipped. A group over `limit` carries its drill form."
  [db st lookup limit]
  (let [e (:db/id (d/entity db lookup))
        groups (related-edges db st e 1000)
        keyname (fn [[dir a]] (if (= dir :reverse) (keyword (namespace a) (str "_" (name a))) a))
        rendered? (fn [lk] (and lk (or (proposed-renders (first lk)) (render-sym-for (first lk)))))]
    (reduce (fn [acc [k {items :items}]]
              (let [shown (filterv rendered? items)
                    skipped (- (count items) (count shown))]
                (cond-> acc
                  (seq shown)
                  (assoc (keyname k)
                         (cond-> {:count (count shown) :items (mapv #(item-view db %) (take limit shown))}
                           (> (count shown) limit)
                           (assoc :seon.render/operations
                                  [(list 'seon.related/items (list 'quote lookup) (keyname k) {:offset limit})])))
                  (pos? skipped) (update :seon.related/skipped (fnil assoc {}) (keyname k) skipped))))
            (sorted-map-by #(compare (str %1) (str %2)))
            groups)))

;;; §B3 The slow-function demo: profile row and an auto task, on d/with only.
(comment
  (def st (structure db0))
  (def stp (-> st (update :value-refs assoc :seon.profile/sym #{:seon.fn/sym})
               (update :identities conj :seon.profile/key)
               (update :indexed conj :seon.profile/sym :seon.profile/key)))
  (def slow-sym 'seon.db/write-owned-values-error)
  (def slow-obs (->> (seon.profile/cells) (map seon.profile/observation) (filter #(= slow-sym (:seon.profile/sym %)))))
  (def auto-task-handle (seon.issue/task-handle 'seon.db (str "slow " (name slow-sym)) :function))
  (def dbd (-> db0 (d/with profile-schema) :db-after
               (d/with (observation-rows slow-obs)) :db-after
               (d/with [{:seon.task/id auto-task-handle :seon.task/ns 'seon.db :seon.task/name (str "slow " (name slow-sym))
                         :seon.task/kind :function :seon.task/must-read #{slow-sym}
                         :seon.task/intent (str slow-sym " max 7135 ms over its 1000 ms bound at digest "
                                                (subs (:seon.profile/digest (first slow-obs)) 0 12)
                                                ". Done when a reaching test bounded under 1000 ms passes at a new digest.")}]) :db-after))
  (timed-ms #(related dbd stp [:seon.fn/sym slow-sym] 3))
  ;; => 4.8 ms warm (15.5 first), 530 tokens; value in doc §3.5
  (timed-ms #(related db0 st [:seon.task/id "4bb788513862"] 3))
  ;; => 1.3 ms warm, 185 tokens; seon.issue/task-edges 4.4 ms
  )
