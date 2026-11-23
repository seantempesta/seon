(ns seon.render.print.expand
  "Pure path-drilling, filtering, offset/limit slicing, and Datahike `preview-slice` query expansion."
  (:require [clojure.string :as str]
            [seon.store.query :as sq]))

(defn real-vec?
  "True for actual persistent vectors (excluding `clojure.lang.MapEntry`)."
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 5000
   :cost/bytes 4096}
  [x]
  (and (vector? x) (not (map-entry? x))))

(defn norm-opts
  "Normalizes printer/expand options map `{:limit :offset :budget :max-chars :max-depth :nested-limit :path}`."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 50000
   :cost/bytes 32768}
  [opts]
  (let [m (if (map? opts) opts {})
        pos-l (fn [k d mx] (let [v (get m k)] (if (integer? v) (max 0 (min mx (long v))) d)))
        mode (if (keyword? (:mode m)) (:mode m) :model)]
    (assoc m
           :mode mode
           :limit (pos-l :limit 20 10000)
           :offset (pos-l :offset 0 1000000000)
           :budget (pos-l :budget (if (= :human mode) 4000 2000) 500000)
           :max-chars (max 256 (pos-l :max-chars 24000 2000000))
           :max-depth (pos-l :max-depth 2 6)
           :nested-limit (max 1 (pos-l :nested-limit 5 50))
           :depth (pos-l :depth 0 100)
           :path (if (sequential? (:path m)) (vec (take 64 (:path m))) []))))

(defn expand-form-data
  "Builds the quoted Clojure `(user/expand! ...)` form for an `:elision` node."
  {:malli/schema [:=> [:cat :map :int :int] :any]
   :cost/ticks 50000
   :cost/bytes 65536}
  [{:keys [handle path filter reverse?]} next-offset limit]
  (let [h (cond (and (symbol? handle) (not= 'result/e? handle)) handle
                (and (string? handle) (str/starts-with? handle "result/")) (symbol handle)
                :else 'result/eN)
        flt (when (and (some? filter) (not (fn? filter)))
              (if (instance? java.util.regex.Pattern filter) (str filter) filter))
        om (cond-> {:offset next-offset :limit limit}
             (seq path) (assoc :path (vec path))
             (some? flt) (assoc :filter flt)
             reverse? (assoc :reverse? true))]
    (list 'user/expand! (list 'quote h) om)))

(defn expand-hint-str
  "Formats a concrete `(user/expand! ...)` continuation string."
  {:malli/schema [:=> [:cat :map :int :int] :string]
   :cost/ticks 50000
   :cost/bytes 65536}
  [opts next-offset limit]
  (let [f (expand-form-data opts next-offset limit)
        [_ qh om] f]
    (str "(user/expand! '" (second qh) " " (pr-str om) ")")))

(defn drill-path
  "Pure safe nested path navigator across maps, vectors, seqs, and sets."
  {:malli/schema [:=> [:cat :any [:vector :any]] :any]
   :cost/ticks 100000
   :cost/bytes 32768}
  [val eff-path]
  (reduce (fn [cur seg]
            (cond (nil? cur) nil
                  (map? cur) (get cur seg)
                  (and (real-vec? cur) (integer? seg)) (when (and (>= (long seg) 0) (< (long seg) (count cur))) (nth cur (long seg)))
                  (and (sequential? cur) (integer? seg) (not (neg? (long seg)))) (nth cur (long seg) nil)
                  (set? cur) (get cur seg)
                  :else nil))
          val eff-path))

(defn- make-filter-pred [flt]
  (cond
    (nil? flt) nil
    (fn? flt) flt
    (instance? java.util.regex.Pattern flt) #(boolean (re-find flt (pr-str %)))
    (string? flt) (let [q (str/lower-case flt)] #(str/includes? (str/lower-case (pr-str %)) q))
    (map? flt) (fn [it] (let [m (if (map-entry? it) (val it) it)] (and (map? m) (every? (fn [[k v]] (= v (get m k))) flt))))
    (keyword? flt) (fn [it] (cond (map-entry? it) (or (= (key it) flt) (and (map? (val it)) (some? (get (val it) flt))))
                                  (map? it) (some? (get it flt))
                                  :else (= it flt)))
    :else #(= % flt)))

(defn slice-value
  "Pure path-drill, filter, reverse, and offset/limit slicer over any Clojure value."
  {:malli/schema [:=> [:cat :any :map] :map]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [val opts]
  (let [{:keys [path offset limit filter reverse?] :as nopts} (norm-opts opts)
        epath (cond-> path (contains? nopts :key) (conj (:key nopts)))
        cur (if (seq epath) (drill-path val epath) val)
        pred (make-filter-pred filter)
        rev? (boolean reverse?)]
    (cond
      (and (real-vec? cur) (nil? pred) (not rev?))
      (let [tot (count cur) s (subvec cur (min offset tot) (min tot (+ offset limit)))]
        {:ok? true :kind :vector :path epath :total tot :offset offset :limit limit
         :preview s :more? (> tot (+ offset (count s))) :remaining (max 0 (- tot (+ offset (count s))))})

      (and (or (real-vec? cur) (set? cur) (seq? cur) (sequential? cur)) (or pred rev?))
      (let [base (if (and rev? (real-vec? cur)) (rseq cur) (cond-> (seq cur) rev? reverse))
            flt-seq (if pred (clojure.core/filter pred base) base)
            items (vec (take limit (drop offset flt-seq)))
            cnt (fn [s] (if (instance? clojure.lang.Counted s) (count s) (min 100000 (bounded-count 100001 s))))
            tot (if pred (cnt flt-seq) (cnt cur))]
        {:ok? true :kind (cond (real-vec? cur) :vector (set? cur) :set :else :seq) :path epath :total tot
         :offset offset :limit limit :preview items :more? (> tot (+ offset (count items)))
         :remaining (max 0 (- tot (+ offset (count items))))})

      (and (map? cur) (or pred rev?))
      (let [base (if pred (clojure.core/filter pred (seq cur)) (seq cur))
            es0 (vec (take 10001 base))
            es (cond-> (if (<= (count es0) 500) (vec (sort-by (comp str key) es0)) es0)
                 rev? (-> reverse vec))
            tot (if pred (count es0) (count cur))
            sl (vec (take limit (drop offset es)))]
        {:ok? true :kind :map :path epath :total tot :offset offset :limit limit :preview (into {} sl)
         :key-order (mapv first sl) :more? (> tot (+ offset (count sl))) :remaining (max 0 (- tot (+ offset (count sl))))})

      :else {:ok? true :value cur :path epath})))

(defn db-query-pattern?
  "True when `target` is a Datahike `[:attr val]` or `[e a v]` slice pattern valid against `db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] :boolean]
   :cost/ticks 50000
   :cost/bytes 16384}
  [db target]
  (boolean
   (and db (real-vec? target)
        (or (and (= 2 (count target)) (qualified-keyword? (first target)) (contains? (:schema db) (first target)))
            (and (= 3 (count target)) (or (nil? (first target)) (integer? (first target)))
                 (or (nil? (second target)) (qualified-keyword? (second target))))))))

(defn expand-db-pattern
  "Executes O(log N + k) bounded Datahike slice over `[:attr val]` or `[e a v]` via `seon.store.query/preview-slice`."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :any] :map] :map]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [db target opts]
  (let [nopts (norm-opts opts)
        pat (if (= 2 (count target)) [nil (first target) (second target)] target)
        sl (sq/preview-slice db pat {:limit (:limit nopts) :offset (:offset nopts) :reverse? (boolean (:reverse? nopts))})
        items (mapv (fn [dt] {:e (:e dt) :a (:a dt) :v (:v dt) :tx (:tx dt)}) (:datoms sl))
        next-off (+ (:offset sl) (count items))]
    (cond-> {:ok? true :kind :db-slice :pattern pat :total (:total sl) :offset (:offset sl)
             :limit (:limit sl) :reverse? (:reverse? sl) :more? (:more? sl)
             :remaining (max 0 (- (:total sl) next-off)) :preview items :datoms (:datoms sl)}
      (:more? sl) (assoc :expand-hint (str "(user/expand! " (pr-str target) " "
                                           (pr-str {:offset next-off :limit (:limit sl) :reverse? (:reverse? sl)}) ")")))))
