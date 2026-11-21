(ns seon.code.analyze
  "Compiler-exact `jolt.analyzer` IR accounting, var-root before/after diffing, and `datahike.reads`
   touched-entity extraction (ported & adapted from `jolt/data:src/seon/program.clj`)."
  (:require [datahike.api :as d]
            [datahike.reads :as reads]
            [jolt.ir :as ir]))

(defn- vsym [n]
  (when (and (:ns n) (:name n))
    (symbol (str (:ns n)) (str (:name n)))))

(defn- datum [n]
  (when (map? n)
    (case (:op n)
      :const (:val n)
      :quote (:form n)
      nil)))

(defn- invoked [n]
  (when (and (map? n) (= :invoke (:op n)))
    (let [f (:fn n)]
      (when (= :var (:op f))
        (vsym f)))))

(defn- def-info [n]
  (let [init (:init n)
        f (when init (invoked init))
        f (if (= 'clojure.core/assoc f) (some-> (first (:args init)) invoked) f)]
    {:var (vsym n)
     :meta (if (map? (:meta n)) (:meta n) {})
     :declare? (boolean (or (:no-init n) (nil? init)))
     :init (cond (nil? init) nil (= :fn (:op init)) :fn :else f)}))

(defn- tail [n]
  (if (map? n)
    (case (:op n)
      :do (tail (:ret n))
      :let (tail (:body n))
      n)
    n))

(defn- answers [n]
  (case (:op (tail n))
    (:const :quote) :constant
    :throw :rethrows
    :expr))

(defn- clause-class [test]
  (let [found (volatile! nil)]
    ((fn walk [n]
       (when (and (map? n) (nil? @found))
         (when (= 'clojure.core/__catch-broad? (invoked n))
           (vreset! found (str (datum (first (:args n))))))
         (ir/reduce-ir-children (fn [_ c] (walk c)) nil n)))
     test)
    @found))

(defn- extract-catches [n]
  (loop [d (some-> (:catch-body n) tail)
         acc []]
    (cond
      (or (nil? d) (not (map? d))) acc
      (and (= :if (:op d)) (clause-class (:test d)))
      (recur (tail (:else d))
             (conj acc {:class (clause-class (:test d))
                        :answers (answers (:then d))}))
      (= :throw (:op d)) acc
      :else (conj acc {:class "*" :answers (answers d)}))))

(defn- quoted-names [x]
  (into #{} (filter qualified-symbol?) (tree-seq coll? seq x)))

(def ^:private resolvers
  #{'clojure.core/resolve 'clojure.core/ns-resolve 'clojure.core/requiring-resolve})

(defn- resolved-target [f args]
  (case f
    (clojure.core/resolve clojure.core/requiring-resolve)
    (let [x (first args)] (when (qualified-symbol? x) x))
    clojure.core/ns-resolve
    (let [[ns-s sym] args]
      (cond
        (qualified-symbol? sym) sym
        (and (simple-symbol? ns-s) (simple-symbol? sym)) (symbol (name ns-s) (name sym))
        :else nil))
    nil))

(defn unit-account
  "Extracts compiler-resolved facts from one `jolt.analyzer` IR node:
   `:defs`, `:inline-defs`, `:methods`, `:impls`, `:multis`, `:refs`, `:quoted`,
   `:keywords`, `:ops`, `:catches`, `:news`, `:requires`, `:env?`."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 10000000}
  [node]
  (let [acc (volatile! {:defs [] :inline-defs #{} :methods [] :impls [] :multis #{}
                        :refs #{} :quoted #{} :keywords #{} :ops #{} :catches []
                        :news #{} :requires #{} :env? false})]
    ((fn walk [n in-fn?]
       (when (map? n)
         (case (:op n)
           :def (if in-fn?
                  (when-let [s (vsym n)] (vswap! acc update :inline-defs conj s))
                  (vswap! acc update :defs conj (def-info n)))
           :defmacro (if in-fn?
                       (when-let [s (vsym n)] (vswap! acc update :inline-defs conj s))
                       (vswap! acc update :defs conj {:var (vsym n) :meta {} :macro? true :init :fn}))
           (:var :the-var) (when-let [s (vsym n)]
                             (vswap! acc update :refs conj s))
           :host-static (when (and (:class n) (:member n))
                          (vswap! acc update :refs conj (symbol (str (:class n)) (str (:member n)))))
           :host-new (when (:class n)
                       (vswap! acc update :news conj (str (:class n))))
           (:quote :const)
           (let [x (datum n)]
             (cond
               (qualified-keyword? x) (vswap! acc update :keywords conj x)
               (qualified-symbol? x) (vswap! acc update :quoted conj x)
               (coll? x)
               (vswap! acc
                       (fn [m]
                         (reduce (fn [a v]
                                   (cond
                                     (qualified-keyword? v) (update a :keywords conj v)
                                     (and (map? v) (keyword? (:op v))) (update a :ops conj (:op v))
                                     :else a))
                                 (update m :quoted into (quoted-names x))
                                 (tree-seq coll? seq x))))))
           :map (when-some [k (some (fn [[kn vn]]
                                      (when (and (map? kn) (= :const (:op kn)) (= :op (:val kn)))
                                        (let [v (datum vn)] (when (keyword? v) v))))
                                    (:pairs n))]
                  (vswap! acc update :ops conj k))
           :try (let [cs (extract-catches n)]
                  (when (seq cs)
                    (vswap! acc update :catches into cs)))
           :invoke (let [f (invoked n)
                         args (:args n)]
                     (when-let [s (when (contains? resolvers f) (resolved-target f (mapv datum args)))]
                       (vswap! acc update :refs conj s))
                     (when-not in-fn?
                       (case f
                         clojure.core/defmethod-setup
                         (vswap! acc update :methods conj {:multi (datum (first args)) :dispatch (datum (second args))})
                         clojure.core/register-method
                         (vswap! acc update :impls conj {:type (str (datum (first args))) :protocol (symbol (str (datum (second args))))})
                         clojure.core/defmulti-setup
                         (vswap! acc update :multis conj (datum (first args)))
                         (when (contains? '#{clojure.core/require clojure.core/use clojure.core/refer clojure.core/import clojure.core/in-ns} f)
                           (vswap! acc assoc :env? true)))))
           nil)
         (let [in-fn?' (or in-fn? (contains? #{:fn :defmacro} (:op n)))]
           (if (= :def (:op n))
             (let [kws (:keywords @acc)
                   meta-keys (let [m (:meta n)] (set (when (map? m) (filter qualified-keyword? (keys m)))))]
               (ir/reduce-ir-children (fn [_ c] (walk c in-fn?')) nil n)
               (vswap! acc update :keywords #(into kws (remove meta-keys) %)))
             (ir/reduce-ir-children (fn [_ c] (walk c in-fn?')) nil n)))))
     node false)
    (let [a @acc]
      (if (some :macro? (:defs a))
        (update a :refs into (:quoted a))
        a))))

(defn merge-accounts
  "Merges a sequence of `unit-account` maps into a single summary account."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]
   :cost/ticks 5000000}
  [accounts]
  (reduce (fn [a b]
            (merge-with (fn [x y]
                          (cond
                            (boolean? x) (or x y)
                            (and (coll? x) (coll? y)) (into x y)
                            :else (or y x)))
                        a b))
          {:defs [] :inline-defs #{} :methods [] :impls [] :multis #{}
           :refs #{} :quoted #{} :keywords #{} :ops #{} :catches []
           :news #{} :requires #{} :env? false}
          accounts))

(defn root-of
  "Returns the current raw root binding of var `sym`, or `::unbound`."
  {:malli/schema [:=> [:cat :qualified-symbol] :any]
   :cost/ticks 50000}
  [sym]
  (if-let [v (find-var sym)]
    (if (bound? v) (.getRawRoot v) ::unbound)
    ::unbound))

(defn macro-var-meta
  "Fallback var-meta reader for `:defmacro` vars (since `jolt.analyzer` drops `:doc`/`:arglists`
   on `:defmacro` IR nodes)."
  {:malli/schema [:=> [:cat :qualified-symbol] :map]
   :cost/ticks 50000}
  [sym]
  (if-let [v (find-var sym)]
    (let [m (meta v)]
      (cond-> {}
        (string? (:doc m)) (assoc :doc (:doc m))
        (seq (:arglists m)) (assoc :arglists (vec (:arglists m)))
        (:macro m) (assoc :macro? true)))
    {}))

(defn make-tap
  "Creates a lightweight `jolt.analyzer/*unit-tap*` callback capturing IR nodes + pre-eval var roots
   into volatile `acc` (`{:nodes [] :before {}}`)."
  {:malli/schema [:=> [:cat :any] :seon.host/fn]}
  [acc]
  (fn [_form node]
    (let [before (volatile! (:before @acc))]
      ((fn walk [n]
         (when (and (map? n) (not (contains? #{:fn} (:op n))))
           (when (contains? #{:def :defmacro} (:op n))
             (when-let [s (vsym n)]
               (when-not (contains? @before s)
                 (vswap! before assoc s (root-of s)))))
           (when-not (= :defmacro (:op n))
             (ir/reduce-ir-children (fn [_ c] (walk c)) nil n))))
       node)
      (vswap! acc (fn [m] (-> m (update :nodes conj node) (assoc :before @before))))
      nil)))

(defn set-vars
  "Returns qualified symbols of vars whose root changed (or macros defined) during a tapped eval."
  {:malli/schema [:=> [:cat :map] [:vector :qualified-symbol]]
   :cost/ticks 2000000}
  [{:keys [nodes before]}]
  (vec (distinct
        (for [n (or nodes [])
              d (:defs (unit-account n))
              :let [s (:var d)]
              :when (and s (or (:macro? d)
                               (not (identical? (get before s ::missing) (root-of s)))))]
          s))))

(defn extract-touched-eids
  "Extracts up to `limit` unique entity IDs / `[attr val]` lookup keys from a `datahike.reads/with-reads`
   read-set via `datahike.reads/reads-index`."
  {:malli/schema [:function
                  [:=> [:cat :any] [:vector :any]]
                  [:=> [:cat :any :int] [:vector :any]]]
   :cost/ticks 2000000}
  ([read-set] (extract-touched-eids read-set 48))
  ([read-set limit]
   (if-not (and (coll? read-set) (not (map? read-set)) (pos? (long limit)))
     []
     (let [ridx (try (reads/reads-index (filter #(and (vector? %) (= 2 (count %))) read-set))
                     (catch Throwable _ #{}))
           seen (volatile! #{})
           out (volatile! [])]
       (doseq [rk ridx
               :while (< (count @out) limit)]
         (when (vector? rk)
           (let [tok (case (first rk)
                       :e  (second rk)
                       :ea (second rk)
                       :av [(second rk) (nth rk 2)]
                       nil)]
             (when (and tok (not (contains? @seen tok)))
               (vswap! seen conj tok)
               (vswap! out conj tok)))))
       @out))))

(defn enrich-var-with-account
  "Enriches a parsed `:seon.code.var/*` entity map with compiler-analyzed `:refs`, true `:db.type/ref`
   `:seon.code.var/calls`, `:seon.code.var/ops`, `:seon.code.var/catches`, and `:defmacro` meta fallback."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map [:maybe :map] :any] :map]}
  [db v acct boot-root-keys]
  (let [vsym (:seon.code.var/name v)
        acct-refs (when (map? acct) (:refs acct))
        merged-refs (if (seq acct-refs)
                      (into (set (:seon.code.var/refs v)) (remove #(= vsym %)) acct-refs)
                      (set (:seon.code.var/refs v)))
        calls (when (and db (contains? (:schema db) :seon.code.var/calls)
                         (contains? (:schema db) :seon.code.var/name)
                         (seq merged-refs))
                (into #{} (keep (fn [sym]
                                  (when-let [dt (first (d/datoms db :avet :seon.code.var/name sym))]
                                    (:e dt))))
                      merged-refs))
        ops (when (and (map? acct) (seq (:ops acct))) (set (:ops acct)))
        catches (when (and (map? acct) (seq (:catches acct))) (vec (:catches acct)))
        mmeta (when (and vsym (:seon.code.var/macro? v) (nil? (:seon.code.var/doc v)))
                (macro-var-meta vsym))]
    (cond-> v
      (seq merged-refs)
      (assoc :seon.code.var/refs merged-refs
             :seon.code.var/platform-test?
             (and (:seon.code.var/test? v)
                  (boolean (some #(contains? boot-root-keys (str %)) merged-refs))))
      (seq calls) (assoc :seon.code.var/calls calls)
      (and (seq ops) db (contains? (:schema db) :seon.code.var/ops)) (assoc :seon.code.var/ops ops)
      (and (seq catches) db (contains? (:schema db) :seon.code.var/catches)) (assoc :seon.code.var/catches catches)
      (:doc mmeta) (assoc :seon.code.var/doc (:doc mmeta))
      (:arglists mmeta) (assoc :seon.code.var/arglists (:arglists mmeta)))))
