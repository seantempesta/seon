;;; p4 — (1) how do you reliably detect an interpreted closure, and how deep
;;; must you walk; (2) the hybrid restore the owner proposes: data defs
;;; restored as VALUES, closure-valued defs re-evaluated from their defining
;;; forms against the restored ctx.
;;; Run: clojure -M:dev -i tmp/durable-env/p4_closures_and_restore.clj
(require '[sci.core :as sci]
         '[clojure.string :as str]
         '[konserve.filestore :as fs]
         '[konserve.core :as k]
         '[clojure.java.io :as io])

(defn line [& xs] (println (apply str xs)))
(defn ms [f] (let [t (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t) 1e6) v]))

(def root "tmp/durable-env/store-p4")
(defn rm-rf [p] (let [f (io/file p)]
                  (when (.exists f) (doseq [c (reverse (file-seq f))] (.delete ^java.io.File c)))))
(rm-rf root)
(def store (fs/connect-fs-store root :opts {:sync? true}))

(defn fresh-ctx [] (sci/init {:namespaces {'user {}}}))

(line "\n=== A. what classes do the fn shapes actually produce? ===")
(let [ctx (fresh-ctx)]
  (sci/eval-string* ctx "(ns my.p)")
  (doseq [[label src]
          [["single-arity defn"      "(defn a1 [x] x) a1"]
           ["multi-arity defn"       "(defn a2 ([x] x) ([x y] y)) a2"]
           ["variadic defn"          "(defn a3 [& xs] xs) a3"]
           ["anonymous fn"           "(fn [x] x)"]
           ["closure over a local"   "(let [n 5] (fn [x] (+ x n)))"]
           ["partial over interp fn" "(partial (fn [x y] x) 1)"]
           ["comp over interp fn"    "(comp (fn [x] x) inc)"]
           ["a HOST fn (inc)"        "inc"]
           ["ordinary data"          "{:a 1}"]]]
    (let [v (sci/eval-string* ctx (str "(in-ns 'my.p) " src))]
      (line (format "  %-24s fn?=%-6s class=%s" label (fn? v) (str (class v)))))))

(line "\n=== B. two candidate predicates ===")
(defn interpreted-fn?
  "Class-package predicate: sci's generated closures all live in sci.impl.fns."
  [v]
  (and (fn? v) (str/starts-with? (.getName (class v)) "sci.impl.fns$")))

(defn store-faithful?
  "COMPUTED predicate: does the value round-trip through the real serializer,
   equal and with its metadata and concrete class intact?"
  [v]
  (try
    (k/assoc store :probe v {:sync? true})
    (let [back (k/get store :probe nil {:sync? true})]
      {:ok (and (= v back) (= (class v) (class back)) (= (meta v) (meta back)))
       :equal (= v back) :same-class (= (class v) (class back)) :meta-kept (= (meta v) (meta back))})
    (catch Throwable t {:ok false :err (.getMessage t)})))

(let [ctx (fresh-ctx)]
  (sci/eval-string* ctx "(ns my.p)")
  (doseq [[label src]
          [["plain data"                     "{:a 1 :b [1 2 3]}"]
           ["a defn value"                   "(defn f [x] x) f"]
           ["MAP CONTAINING a fn"            "{:k (fn [x] x)}"]
           ["VECTOR of vectors with a fn"    "[[1 [2 {:z (fn [] 1)}]]]"]
           ["set containing a fn"            "#{(fn [] 1)}"]
           ["sorted-set-by w/ fn comparator" "(sorted-set-by (fn [a b] (compare b a)) 3 1 2)"]
           ["sorted-map"                     "(sorted-map :b 2 :a 1)"]
           ["value WITH metadata"            "(with-meta {:a 1} {:m 1})"]
           ["a lazy-seq"                     "(map inc (range 5))"]
           ["an atom"                        "(atom 1)"]
           ["a host fn"                      "inc"]]]
    (let [v (sci/eval-string* ctx (str "(in-ns 'my.p) " src))
          shallow (interpreted-fn? v)
          deep (boolean (some interpreted-fn? (tree-seq coll? seq v)))
          faithful (store-faithful? v)]
      (line (format "  %-33s shallow-fn?=%-6s deep-walk-fn?=%-6s store-faithful=%s"
                    label shallow deep (pr-str faithful))))))

(line "\n  => deep walk finds nested closures the shallow predicate misses;")
(line "     but ONLY store-faithful? catches the SILENT losses (metadata,")
(line "     sortedness, custom comparator) that no fn-predicate can see.")

(line "\n=== C. walk cost: how expensive is the deep closure walk? ===")
(doseq [[label v] [["small map"      {:a 1 :b [1 2 3]}]
                   ["10k vector"     (vec (range 10000))]
                   ["200k vector"    (vec (range 200000))]
                   ["10k-entry map"  (into {} (map (fn [i] [i i]) (range 10000)))]]]
  (let [ts (doall (for [_ (range 5)]
                    (first (ms #(boolean (some fn? (tree-seq coll? seq v)))))))]
    (line (format "  %-14s deep walk median %.2f ms" label (nth (sort ts) 2)))))

(line "\n=== D. THE HYBRID RESTORE — 50-def mixed session, end to end ===")
(def session-forms
  (concat
   (for [i (range 20)] [(symbol (str "d" i)) (format "(def d%d (vec (range %d)))" i (* 10 (inc i)))])
   (for [i (range 20)]
     [(symbol (str "f" i))
      (if (zero? i)
        "(defn f0 [x] (+ x (count d0)))"
        (format "(defn f%d [x] (+ (f%d x) (count d%d)))" i (dec i) i))])
   (for [i (range 5)]
     [(symbol (str "c" i)) (format "(def c%d (let [n %d] (fn [x] (* n x))))" i (inc i))])
   (for [i (range 5)]
     [(symbol (str "r" i)) (format "(def r%d (f19 %d))" i i)])))

(line "  session size: " (count session-forms) " top-level defs")

(def ctx1 (fresh-ctx))
(sci/eval-string* ctx1 "(ns my.sess)")
(def t-orig
  (first (ms #(doseq [[_ src] session-forms]
                (sci/eval-string* ctx1 (str "(in-ns 'my.sess) " src))))))
(line (format "  original session eval: %.1f ms" t-orig))
(def answer-orig (sci/eval-string* ctx1 "(in-ns 'my.sess) [(f19 1) (c4 3) r4 (count d19)]"))
(line "  original answers: " (pr-str answer-orig))

(def image
  (let [nsm (get (:namespaces @(:env ctx1)) 'my.sess)
        src-by-name (into {} session-forms)]
    (vec
     (for [[nm v] (sort-by (comp str key) (dissoc nsm :obj))
           :let [val @v
                 closure? (boolean (some interpreted-fn? (tree-seq coll? seq val)))
                 faithful (when-not closure? (store-faithful? val))]]
       (cond
         closure? {:name nm :source (get src-by-name nm) :why :closure}
         (:ok faithful) {:name nm :value val}
         :else {:name nm :source (get src-by-name nm) :why :unfaithful})))))
(line (format "  image entries: %d  (as value: %d, as source: %d)"
              (count image) (count (filter :value image)) (count (filter :source image))))
(line "  as-source names: " (pr-str (mapv :name (filter :source image))))

(def t-write (first (ms #(k/assoc store :session image {:sync? true}))))
(line (format "  image write to konserve: %.1f ms" t-write))

(def ctx2 (fresh-ctx))
(def restore-time
  (first
   (ms (fn []
         (let [img (k/get store :session nil {:sync? true})
               source-names (set (map :name (filter :source img)))]
           (sci/eval-string* ctx2 "(ns my.sess)")
           ;; pass 1 — pre-intern every name unbound (stateless-resume §1.2)
           (sci/add-namespace! ctx2 'my.sess
                               (into {} (for [{nm :name} img]
                                          [nm (sci/new-var nm nil {:ns (sci/find-ns ctx2 'my.sess)})])))
           ;; pass 2a — bind the value entries
           (doseq [{nm :name value :value} img
                   :when (some? value)]
             (sci/alter-var-root (get (get (:namespaces @(:env ctx2)) 'my.sess) nm)
                                 (constantly value)))
           ;; pass 2b — re-evaluate the defining forms, in original ordinal order
           (doseq [[nm src] session-forms
                   :when (contains? source-names nm)]
             (sci/eval-string* ctx2 (str "(in-ns 'my.sess) " src))))))))
(line (format "  RESTORE (read image + 2 passes): %.1f ms" restore-time))

(def answer-restored (sci/eval-string* ctx2 "(in-ns 'my.sess) [(f19 1) (c4 3) r4 (count d19)]"))
(line "  restored answers: " (pr-str answer-restored))
(line "  IDENTICAL TO ORIGINAL? " (= answer-orig answer-restored))

(line "\n=== E. does a re-evaluated closure see a RESTORED data def? ===")
(let [ctx (fresh-ctx)]
  (sci/eval-string* ctx "(ns my.e)")
  (k/assoc store :dv (vec (range 1000)) {:sync? true})
  (sci/add-namespace! ctx 'my.e {'stored (sci/new-var 'stored
                                                     (k/get store :dv nil {:sync? true})
                                                     {:ns (sci/find-ns ctx 'my.e)})})
  (sci/eval-string* ctx "(in-ns 'my.e) (defn uses-stored [x] (+ x (count stored)))")
  (line "  (uses-stored 5) => " (sci/eval-string* ctx "(in-ns 'my.e) (uses-stored 5)"))
  (let [ctx' (fresh-ctx)]
    (sci/eval-string* ctx' "(ns my.e2)")
    (sci/add-namespace! ctx' 'my.e2 {'later (sci/new-var 'later nil {:ns (sci/find-ns ctx' 'my.e2)})})
    (sci/eval-string* ctx' "(in-ns 'my.e2) (defn uses-later [x] (+ x (count later)))")
    (sci/alter-var-root (get (get (:namespaces @(:env ctx')) 'my.e2) 'later)
                        (constantly (vec (range 7))))
    (line "  WRONG ORDER (fn defined before the value was bound) => "
          (sci/eval-string* ctx' "(in-ns 'my.e2) (uses-later 5)"))))

(line "\n=== F. what happens if you DON'T detect a closure and just store it? ===")
(let [ctx (fresh-ctx)
      _ (sci/eval-string* ctx "(ns my.f) (defn g [x] x)")
      v (sci/eval-string* ctx "(in-ns 'my.f) g")]
  (line "  konserve k/assoc of a sci closure => " (pr-str (store-faithful? v))))

(System/exit 0)
