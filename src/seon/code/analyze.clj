(ns seon.code.analyze
  "Compiler-exact `jolt.analyzer` IR accounting, var-root before/after diffing, and `datahike.reads`
   touched-entity extraction (ported & adapted from `jolt/data:src/seon/program.clj`)."
  (:require [datahike.api :as d]
            [datahike.reads :as reads]
            [jolt.ir :as ir]
            [jolt.analyzer]
            [seon.schema :as schema]
            [clojure.string :as str]
            [jolt.scheme :as scm]
            [seon.code.graph :as graph]))

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

(defn eval-form-with-account!
  "Evaluates Clojure form source `form-src` inside `nsym` under `jolt.analyzer/*unit-tap*`,
   arms any updated `:malli/schema` var contracts on `db`, and returns `{:ok? :account :set-vars :error}`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :symbol :string :boolean] :map]}
  [db nsym form-src is-ns?]
  (let [tap-acc (volatile! {:nodes [] :before {}})]
    (try
      (if is-ns?
        (binding [*ns* *ns*
                  jolt.analyzer/*unit-tap* (make-tap tap-acc)]
          (eval (read-string form-src)))
        (do
          (when-not (find-ns nsym) (require nsym))
          (binding [*ns* (the-ns nsym)
                    jolt.analyzer/*unit-tap* (make-tap tap-acc)]
            (eval (read-string form-src)))))
      (let [acct (merge-accounts (map unit-account (:nodes @tap-acc)))
            svs (set-vars @tap-acc)]
        (doseq [vs svs]
          (when-let [v (find-var vs)]
            (when (and (not (:private (meta v))) (:malli/schema (meta v)))
              (schema/arm-var! db v))))
        {:ok? true :account acct :set-vars svs :error nil})
      (catch Throwable ex
        {:ok? false :account nil :set-vars [] :error (or (.getMessage ex) (str ex))}))))

(defn stale-var-edge-retracts
  "Computes `:db/retract` ops for removed values on var edge attributes (cardinality-many refs/sets\n        AND cardinality-one `:seon.code.var/catches` when `try/catch` is removed)."
  {:malli/schema
   [:function
    [:=>
     [:cat [:maybe :seon.host/db] [:sequential :map]]
     [:vector :any]]
    [:=>
     [:cat [:maybe :seon.host/db] [:sequential :map] [:maybe :map]]
     [:vector :any]]]}
  ([db kept-vars] (stale-var-edge-retracts db kept-vars nil))
  ([db kept-vars {:keys [preserve-analyzed?]}]
    (if-not (and db (contains? (:schema db) :seon.code.var/name))
      []
      (let [dh-sch (:schema db)
            base-attrs [:seon.code.var/refs
                        :seon.code.var/calls
                        :seon.code.var/schema-refs
                        :seon.code.var/keywords
                        :seon.code.var/input
                        :seon.code.var/optional-input
                        :seon.code.var/output]
            many-attrs (filterv
                         #(contains? dh-sch %)
                         (if preserve-analyzed?
                           base-attrs
                           (conj base-attrs :seon.code.var/ops)))
            check-catches? (and
                             (not preserve-analyzed?)
                             (contains? dh-sch :seon.code.var/catches))
            norm-val (fn [a x]
                       (if (and
                             (=
                               :db.type/ref
                               (get-in dh-sch [a :db/valueType]))
                             (vector? x)
                             (= 2 (count x))
                             (contains? dh-sch (first x)))
                         (or
                           (:e
                             (first
                               (d/datoms
                                 db
                                 :avet
                                 (first x)
                                 (second x))))
                           x)
                         x))]
        (vec
          (mapcat
            (fn [v]
              (when-let [eid (:e
                               (first
                                 (d/datoms
                                   db
                                   :avet
                                   :seon.code.var/name
                                   (:seon.code.var/name v))))]
                (let [many-ops (mapcat
                                 (fn 
                                   [a]
                                   (let 
                                     [old-s
                                      (into
                                        #{}
                                        (map :v)
                                        (d/datoms db :eavt eid a))
                                      new-s
                                      (into
                                        #{}
                                        (map #(norm-val a %))
                                        (get v a #{}))]
                                     (for
                                       [gone (remove new-s old-s)]
                                       [:db/retract eid a gone])))
                                 many-attrs)
                      catch-ops (when
                                  (and
                                    check-catches?
                                    (nil? (:seon.code.var/catches v)))
                                  (for
                                    [dt
                                     (d/datoms
                                       db
                                       :eavt
                                       eid
                                       :seon.code.var/catches)]
                                    [:db/retract
                                     eid
                                     :seon.code.var/catches
                                     (:v dt)]))]
                  (concat many-ops catch-ops))))
            kept-vars))))))

(defn var-tempid [& args] (apply graph/var-tempid args))

(defn ns-tempid [& args] (apply graph/ns-tempid args))

(defn extract-malli-io-keys [& args]
  (apply graph/extract-malli-io-keys args))

(defn enrich-var-with-account
  "Enriches a parsed `:seon.code.var/*` entity map with compiler-analyzed `:refs`, `:calls`, `:ops`,
   `:catches`, Malli AST `:input`/`:optional-input`/`:output` refs, and `:defmacro` meta fallback."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :map [:maybe :map] :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :map [:maybe :map] :any [:maybe :map]] :map]]}
  ([db v acct boot-root-keys] (enrich-var-with-account db v acct boot-root-keys nil))
  ([db v acct boot-root-keys batch-ctx]
   (let [dh-sch (when db (:schema db))
         has-attr? (fn [a] (or (nil? dh-sch) (contains? dh-sch a)))
         vsym (:seon.code.var/name v)
         existing-eid (when (and db vsym (contains? dh-sch :seon.code.var/name))
                        (:e (first (d/datoms db :avet :seon.code.var/name vsym))))
         var->ref (:var->ref batch-ctx)
         sch->ref (:schema->ref batch-ctx)
         acct-refs (when (map? acct) (:refs acct))
         merged-refs (into #{}
                           (remove #(= vsym %))
                           (if (seq acct-refs)
                             (into (set (:seon.code.var/refs v)) acct-refs)
                             (set (:seon.code.var/refs v))))
         resolve-var (fn [sym]
                       (or (when (and db (contains? dh-sch :seon.code.var/name))
                             (:e (first (d/datoms db :avet :seon.code.var/name sym))))
                           (get var->ref sym)))
         resolve-sch (fn [sk]
                       (or (when (and db (contains? dh-sch :seon.schema/key))
                             (:e (first (d/datoms db :avet :seon.schema/key sk))))
                           (get sch->ref sk)))
         calls (when (and (has-attr? :seon.code.var/calls) (seq merged-refs))
                 (into #{} (keep resolve-var) merged-refs))
         malli-sch (:seon.code.var/schema v)
         io-res (when (and malli-sch
                           (or (has-attr? :seon.code.var/input)
                               (has-attr? :seon.code.var/optional-input)
                               (has-attr? :seon.code.var/output)))
                  (let [reg-info (when-not (:registry batch-ctx) (schema/registry-for-db db))
                        reg (or (:registry batch-ctx) (:registry reg-info))
                        known (or (:known-schemas batch-ctx)
                                  (into (set (keys (:forms reg-info))) (keys sch->ref)))]
                    (extract-malli-io-keys malli-sch reg known)))
         in-refs     (when io-res (into #{} (keep resolve-sch) (:input io-res)))
         opt-in-refs (when io-res (into #{} (keep resolve-sch) (:optional-input io-res)))
         out-refs    (when io-res (into #{} (keep resolve-sch) (:output io-res)))
         ops (when (and (map? acct) (seq (:ops acct))) (set (:ops acct)))
         catches (when (and (map? acct) (seq (:catches acct))) (vec (:catches acct)))
         mmeta (when (and vsym (:seon.code.var/macro? v) (nil? (:seon.code.var/doc v)))
                 (macro-var-meta vsym))]
     (cond-> v
       (and (nil? existing-eid) (:assign-tempid? batch-ctx) vsym)
       (assoc :db/id (or (get var->ref vsym) (var-tempid vsym)))
       (seq merged-refs)
       (assoc :seon.code.var/refs merged-refs
              :seon.code.var/platform-test?
              (and (:seon.code.var/test? v)
                   (boolean (some #(contains? boot-root-keys (str %)) merged-refs))))
       (seq calls) (assoc :seon.code.var/calls calls)
       (and (seq in-refs) (has-attr? :seon.code.var/input)) (assoc :seon.code.var/input in-refs)
       (and (seq opt-in-refs) (has-attr? :seon.code.var/optional-input)) (assoc :seon.code.var/optional-input opt-in-refs)
       (and (seq out-refs) (has-attr? :seon.code.var/output)) (assoc :seon.code.var/output out-refs)
       (and (seq ops) (has-attr? :seon.code.var/ops)) (assoc :seon.code.var/ops ops)
       (and (seq catches) (has-attr? :seon.code.var/catches)) (assoc :seon.code.var/catches catches)
       (:doc mmeta) (assoc :seon.code.var/doc (:doc mmeta))
       (:arglists mmeta) (assoc :seon.code.var/arglists (:arglists mmeta))))))

(def ^{:seon.flow/edge true, :private true} make-analyze-ctx-fn
 (scm/eval-string "(lambda (ns) (make-analyze-ctx ns))"))

(defn ^{:seon.flow/edge true} analyze-form-account
  "Side-effect-free compiler AST analysis via `jolt.analyzer/analyze` + `unit-account`."
  {:malli/schema
   [:function
    [:=> [:cat :symbol :string] [:maybe :map]]
    [:=> [:cat :symbol :string :boolean] [:maybe :map]]]}
  ([nsym form-src] (analyze-form-account nsym form-src false))
  ([nsym form-src load-ns?]
    (when (and
            (symbol? nsym)
            (string? form-src)
            (not (str/blank? form-src)))
      (try
        (when (and load-ns? (nil? (find-ns nsym))) (require nsym))
        (when-let [ns-obj (find-ns nsym)]
          (let [form (binding [*ns* ns-obj] (read-string form-src))
                ir (jolt.analyzer/analyze
                     (make-analyze-ctx-fn (name nsym))
                     form)]
            (unit-account ir)))
        (catch Throwable _ nil)))))

(defn resolve-ns-requires-refs [& args]
  (apply graph/resolve-ns-requires-refs args))

(defn backfill-codebase-analysis-tx
  "Unified cold-boot & backfill pass: runs compiler AST analysis (`jolt.analyzer/analyze` ->\n        `unit-account`) and links all canonical code/schema/ns/rule/doc/test refs idempotently."
  {:malli/schema
   [:function
    [:=> [:cat :seon.host/db] [:vector :any]]
    [:=> [:cat :seon.host/db :map] [:vector :any]]],
   :seon.eval/budget-override {:ticks 1000000000, :bytes 4294967296}}
  ([db] (backfill-codebase-analysis-tx db {}))
  ([db opts]
    (graph/backfill-codebase-analysis-tx
      db
      (assoc
        opts
        :analyze-fn
        (when (:analyze-compiler? opts true) analyze-form-account)))))