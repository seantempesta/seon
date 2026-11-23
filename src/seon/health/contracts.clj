(ns seon.health.contracts
  "Contract & reference integrity gates for System Health — broken docstring/qualified-symbol refs, impure generatable fns, and newly added/edited public `defn`s lacking a `:malli/schema` (hard merge gate)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema :as schema]
            [malli.core :as m]
            [seon.store :as store]
            [malli.registry :as mr]
            [seon.health.static :as static]))

(defn broken-symbol-refs
  "Referential integrity gate: flags any qualified symbol referenced by `:seon.render/hiccup`
   inside `:seon.schema/form` that does not exist in `:seon.code.var/name`. Accepts optional `var-snap`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :map]] [:vector :map]]]}
  ([db] (broken-symbol-refs db nil))
  ([db var-snap]
   (if-not (and db (contains? (:schema db) :seon.code.var/name))
     []
     (let [br (get-in db [:config :branch] :main)
           live-vars (or (:all-var-syms var-snap)
                         (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name)))]
       (if (empty? live-vars)
         []
         (let [errs (volatile! [])
               check! (fn [src-kind owner sym]
                        (when (and (qualified-symbol? sym)
                                   (not (contains? live-vars sym))
                                   (nil? (when-let [ns-obj (find-ns (symbol (namespace sym)))]
                                           (ns-resolve ns-obj (symbol (name sym))))))
                          (vswap! errs conj
                                  {:class :broken-symbol-ref
                                   :severity :error
                                   :kind :static
                                   :ns (symbol (namespace sym))
                                   :var sym
                                   :subject (str src-kind " " owner " -> " sym)
                                   :branch br
                                   :message (str "Broken symbol reference in " src-kind " on " owner
                                                 ": var " sym " does not exist. Define it or update the reference.")})))]
           (when (contains? (:schema db) :seon.schema/form)
             (doseq [dt (d/datoms db :aevt :seon.schema/form)]
               (let [f (:v dt)
                     r (when (and (vector? f) (map? (second f))) (:seon.render/hiccup (second f)))]
                 (when r
                   (let [k (:v (first (d/datoms db :eavt (:e dt) :seon.schema/key)))]
                     (check! :seon.render/hiccup k r))))))
           (vec (sort-by :subject (distinct @errs)))))))))

(defn broken-doc-refs
  "Detects backticked qualified symbol references inside var or namespace docstrings that do not
   exist in `:seon.code.var/name` on `db`. Accepts optional `var-snap` to avoid duplicate scans."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :symbol]] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map]] [:vector :map]]]}
  ([db] (broken-doc-refs db nil nil))
  ([db scope] (broken-doc-refs db scope nil))
  ([db scope var-snap]
   (if-not (and db
                (contains? (:schema db) :seon.code.var/name)
                (contains? (:schema db) :seon.code.var/doc))
     []
     (let [br (get-in db [:config :branch] :main)
           all-vars (or (:all-var-syms var-snap)
                        (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name)))
           eid->name ^java.util.HashMap (:eid->name var-snap)
           eid->ns ^java.util.HashMap (:eid->ns var-snap)
           scope-s (when scope (name scope))
           in-scope? (fn [nsym]
                       (and nsym
                            (not (str/ends-with? (name nsym) "-test"))
                            (or (nil? scope-s)
                                (= (name nsym) scope-s)
                                (str/starts-with? (name nsym) (str scope-s ".")))))
           pat #"`(seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`"
           parse-qsym (fn [^String s]
                        (let [idx (.indexOf s "/")]
                          (when (pos? idx) (symbol (subs s 0 idx) (subs s (inc idx))))))
           var-probs
           (let [out (volatile! [])]
             (doseq [dt (d/datoms db :aevt :seon.code.var/doc)
                     :let [doc (str (:v dt))]
                     :when (str/includes? doc "`seon.")]
               (let [missing (keep (fn [[_ sym-s]]
                                     (let [sym (parse-qsym sym-s)]
                                       (when (and sym (not (contains? all-vars sym))) sym)))
                                   (re-seq pat doc))]
                 (when (seq missing)
                   (let [e (:e dt)
                         vsym (if eid->name (.get eid->name e) (:v (first (d/datoms db :eavt e :seon.code.var/name))))
                         nsym (if eid->ns (.get eid->ns e) (:v (first (d/datoms db :eavt e :seon.code.var/ns))))]
                     (when (and vsym (in-scope? nsym))
                       (doseq [sym missing]
                         (vswap! out conj
                                 {:class :broken-doc-ref
                                  :severity :error
                                  :kind :static
                                  :ns nsym
                                  :var vsym
                                  :subject (str vsym "->" sym)
                                  :branch br
                                  :message (str "Docstring of " vsym " references missing var `" sym "`. Update the docstring or restore the var.")})))))))
             @out)
           ns-probs
           (if-not (contains? (:schema db) :seon.code.ns/doc)
             []
             (let [out (volatile! [])]
               (doseq [dt (d/datoms db :aevt :seon.code.ns/doc)
                       :let [doc (str (:v dt))]
                       :when (str/includes? doc "`seon.")]
                 (let [missing (keep (fn [[_ sym-s]]
                                       (let [sym (parse-qsym sym-s)]
                                         (when (and sym (not (contains? all-vars sym))) sym)))
                                     (re-seq pat doc))]
                   (when (seq missing)
                     (let [nsym (:v (first (d/datoms db :eavt (:e dt) :seon.code.ns/name)))]
                       (when (in-scope? nsym)
                         (doseq [sym missing]
                           (vswap! out conj
                                   {:class :broken-doc-ref
                                    :severity :error
                                    :kind :static
                                    :ns nsym
                                    :subject (str nsym "#ns->" sym)
                                    :branch br
                                    :message (str "Namespace docstring of " nsym " references missing var `" sym "`. Update the docstring.")})))))))
               @out))]
       (vec (sort-by :subject (concat var-probs ns-probs)))))))

(defn impure-generatable-fns
  "Detects non-edge functions carrying a generatable `[:=> ...]` `:malli/schema` whose static/IR
   references call a `^:seon.flow/edge` side-effecting var. Reuses `var-snap` when provided."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :symbol]] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map]] [:vector :map]]]}
  ([db] (impure-generatable-fns db nil nil))
  ([db scope] (impure-generatable-fns db scope nil))
  ([db scope var-snap]
   (if-not (and db
                (contains? (:schema db) :seon.code.var/schema)
                (contains? (:schema db) :seon.code.var/flow-edge?)
                (contains? (:schema db) :seon.code.var/refs))
     []
     (let [br (get-in db [:config :branch] :main)
           scope-s (when scope (name scope))]
       (if var-snap
         (let [edge-vars (:edge-vars var-snap)
               eid->refs ^java.util.HashMap (:eid->refs var-snap)]
           (if (empty? edge-vars)
             []
             (->> (:var-schemas var-snap)
                  (keep (fn [[e vsym nsym edge? sch]]
                          (let [ns-s (when vsym (namespace vsym))]
                            (when (and vsym ns-s (not edge?)
                                       (not (str/ends-with? ns-s "-test"))
                                       (or (nil? scope-s) (= ns-s scope-s) (str/starts-with? ns-s (str scope-s ".")))
                                       (schema/generatable-function-schema? sch))
                              (let [refs (.get eid->refs e)
                                    bad-refs (when (seq refs)
                                               (->> refs (filter #(contains? edge-vars %)) sort vec))]
                                (when (seq bad-refs)
                                  {:class :impure-generatable-fn
                                   :severity :error
                                   :kind :static
                                   :ns (or nsym (symbol ns-s))
                                   :var vsym
                                   :subject (str vsym)
                                   :branch br
                                   :message (str "Generatable pure schema on " vsym " calls ^:seon.flow/edge var(s) "
                                                 (str/join ", " bad-refs)
                                                 ". Mark ^:seon.flow/edge, use :seon.host/* args, or keep pure logic separate from IO.")}))))))
                  (sort-by :subject)
                  vec)))
         (let [edge-eids (into #{} (keep #(when (:v %) (:e %))) (d/datoms db :aevt :seon.code.var/flow-edge?))]
           (if (empty? edge-eids)
             []
             (let [eid->var (java.util.HashMap. 1200)
                   _ (doseq [dt (d/datoms db :aevt :seon.code.var/name)] (.put eid->var (:e dt) (:v dt)))
                   edge-vars (into #{} (keep #(.get eid->var %)) edge-eids)]
               (->> (d/datoms db :aevt :seon.code.var/schema)
                    (keep (fn [dt]
                            (let [e (:e dt)
                                  sch (:v dt)
                                  vsym (.get eid->var e)
                                  ns-s (when vsym (namespace vsym))]
                              (when (and vsym ns-s
                                         (not (contains? edge-eids e))
                                         (not (str/ends-with? ns-s "-test"))
                                         (or (nil? scope-s) (= ns-s scope-s) (str/starts-with? ns-s (str scope-s ".")))
                                         (schema/generatable-function-schema? sch))
                                (let [bad-refs (->> (d/datoms db :eavt e :seon.code.var/refs)
                                                    (keep #(when (contains? edge-vars (:v %)) (:v %)))
                                                    distinct sort vec)]
                                  (when (seq bad-refs)
                                    {:class :impure-generatable-fn
                                     :severity :error
                                     :kind :static
                                     :ns (symbol ns-s)
                                     :var vsym
                                     :subject (str vsym)
                                     :branch br
                                     :message (str "Generatable pure schema on " vsym " calls ^:seon.flow/edge var(s) "
                                                   (str/join ", " bad-refs)
                                                   ". Mark ^:seon.flow/edge, use :seon.host/* args, or keep pure logic separate from IO.")}))))))
                    (sort-by :subject)
                    vec)))))))))

(defn missing-contract-violations
  "Hard merge gate: flags any newly added or edited non-test public `defn` on `theirs-db`
   (relative to `ours-db`) that lacks a `:malli/schema` contract (skipping thin `[& args] (apply ...)`
   forwarding stubs). Returns actionable `:error` violations with copy-pasteable `user/set-meta!` hints."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:vector :map]]
                  [:=> [:cat :any :any [:maybe [:or [:sequential :symbol] [:set :symbol]]]] [:vector :map]]]}
  ([ours-db theirs-db] (missing-contract-violations ours-db theirs-db nil))
  ([ours-db theirs-db touched-nss]
   (if-not (and theirs-db
                (contains? (:schema theirs-db) :seon.code.var/name)
                (contains? (:schema theirs-db) :seon.code.form/key))
     []
     (let [br (get-in theirs-db [:config :branch] :main)
         ;; Fast O(|changed-forms|) filter: find only form keys whose hash differs from ours-db!
         changed-fkeys
         (if (some? touched-nss)
           (into #{}
                 (for [n (distinct touched-nss)
                       :when (and n (not (str/ends-with? (name n) "-test")))
                       dt (d/datoms theirs-db :avet :seon.code.form/ns n)
                       :let [e (:e dt)
                             fk (:v (first (d/datoms theirs-db :eavt e :seon.code.form/key)))
                             th (:v (first (d/datoms theirs-db :eavt e :seon.code.form/hash)))
                             oh (when (and ours-db fk (contains? (:schema ours-db) :seon.code.form/key))
                                  (when-let [oe (:e (first (d/datoms ours-db :avet :seon.code.form/key fk)))]
                                    (:v (first (d/datoms ours-db :eavt oe :seon.code.form/hash)))))]
                       :when (and fk th (not= th oh))]
                   fk))
           (let [fmap (fn [db]
                        (if-not (and db (contains? (:schema db) :seon.code.form/key))
                          {}
                          (let [ks (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
                            (reduce (fn [a dt]
                                      (if-let [k (get ks (:e dt))] (assoc a k (:v dt)) a))
                                    {}
                                    (d/datoms db :aevt :seon.code.form/hash)))))
                 ohs (fmap ours-db)
                 ths (fmap theirs-db)]
             (into #{}
                   (keep (fn [[fk th]] (when (not= th (get ohs fk)) fk)))
                   ths)))]
     (if (empty? changed-fkeys)
       []
       (let [foreign (static/foreign-nss theirs-db)
             proj '[:seon.code.var/name :seon.code.var/ns :seon.code.var/form
                     :seon.code.var/private? :seon.code.var/macro? :seon.code.var/test?
                     :seon.code.var/arglists :seon.code.var/schema]]
         (->> changed-fkeys
              (mapcat #(map :e (d/datoms theirs-db :avet :seon.code.var/form %)))
              (keep (fn [e]
                      (let [v (d/pull theirs-db proj e)
                            vsym (:seon.code.var/name v)
                            nsym (:seon.code.var/ns v)
                            fk (:seon.code.var/form v)]
                        (when (and vsym nsym fk
                                   (not (str/ends-with? (name nsym) "-test"))
                                   (not (contains? foreign nsym))
                                   (not (:seon.code.var/test? v))
                                   (not (:seon.code.var/private? v))
                                   (not (:seon.code.var/macro? v))
                                   (seq (:seon.code.var/arglists v))
                                   (nil? (:seon.code.var/schema v)))
                          (let [fe (:e (first (d/datoms theirs-db :avet :seon.code.form/key fk)))
                                src (str (when fe (:v (first (d/datoms theirs-db :eavt fe :seon.code.form/src)))))
                                delegate-stub? (boolean (re-find #"\[&\s+args\]\s*\(apply\s+" src))]
                            (when-not delegate-stub?
                              {:class :missing-contract
                               :severity :error
                               :kind :static
                               :ns nsym
                               :var vsym
                               :subject (str vsym)
                               :branch br
                               :message (str "Public defn " vsym " was added/edited without a :malli/schema contract. "
                                             "Add one via (user/set-meta! '" vsym " {:malli/schema [:=> [:cat ...] :any]}) — "
                                             "for host values use :seon.host/db, :seon.host/conn, :seon.host/chan, or :any; "
                                             "for pure fns include :cost/ticks & :cost/bytes for seed=42 generative proofs.")}))))))
              (sort-by :subject)
              vec)))))))

(def ^:private simple-malli-leaves
  #{:any :int :double :string :boolean :keyword :qualified-keyword :symbol :qualified-symbol
    :map :vector :set :nil :uuid :inst
    'number? 'pos-int? 'nat-int? 'neg-int? 'string? 'keyword? 'symbol?
    'boolean? 'map? 'vector? 'set? 'int? 'pos? 'coll?
    :seon.host/db :seon.host/conn :seon.host/chan :seon.host/atom :seon.host/fn
    :seon.host/var :seon.host/ns :seon.host/deref :seon.host/throwable :char})

(def ^:private host-only-registry
  (delay
    (mr/composite-registry
      (m/default-schemas)
      (or (some-> (ns-resolve 'seon.schema 'host-predicates) deref) {}))))

(defonce ^:private ^:seon.flow/edge host-contract-cache (store/lru-cache 1024))

(defonce ^:private ^:seon.flow/edge custom-contract-cache (store/lru-cache 256))

(defn- fast-valid-malli? [s]
  (cond
    (contains? simple-malli-leaves s) true
    (not (vector? s)) false
    (empty? s) false
    :else
    (case (first s)
      :=> (let [[_ a1 a2 a3] s
                [props args ret] (if (map? a1) [a1 a2 a3] [nil a1 a2])]
            (and (or (nil? props) (every? #{:examples :doc :description} (keys props)))
                 (if (map? a1) (= 4 (count s)) (= 3 (count s)))
                 (vector? args) (= :cat (first args))
                 (every? fast-valid-malli? (rest args))
                 (fast-valid-malli? ret)))
      :function (let [props (when (map? (second s)) (second s))
                      items (if props (nnext s) (rest s))]
                  (and (or (nil? props) (every? #{:examples :doc :description} (keys props)))
                       (seq items)
                       (every? #(and (vector? %) (= :=> (first %)) (fast-valid-malli? %)) items)))
      (:maybe :sequential :vector :set) (and (= 2 (count s)) (fast-valid-malli? (second s)))
      (:or :and :tuple) (and (>= (count s) 2) (not (map? (second s))) (every? fast-valid-malli? (rest s)))
      :map-of (and (= 3 (count s)) (not (map? (second s))) (fast-valid-malli? (second s)) (fast-valid-malli? (nth s 2)))
      false)))

(defn- custom-schema-refs [s]
  (let [strip-props (fn strip [x]
                      (if (vector? x)
                        (mapv (fn [el]
                                (if (and (map? el) (or (contains? el :examples) (contains? el :doc) (contains? el :description)))
                                  (dissoc el :examples :doc :description)
                                  (strip el)))
                              x)
                        x))
        cleaned (strip-props s)]
    (into #{}
          (filter (fn [x]
                    (and (qualified-keyword? x)
                         (let [ns-s (namespace x)]
                           (and (not= "seon.host" ns-s)
                                (not= "gen" ns-s)
                                (not (str/starts-with? ns-s "malli")))))))
          (tree-seq coll? seq cleaned))))

(defn- transitive-schema-refs [forms seed-keys]
  (loop [frontier (set seed-keys)
         seen (set seed-keys)]
    (if (empty? frontier)
      seen
      (let [nxt (into #{}
                      (comp (keep #(get forms %))
                            (mapcat #(tree-seq coll? seq %))
                            (filter #(and (qualified-keyword? %)
                                          (contains? forms %)
                                          (not (contains? seen %)))))
                      frontier)]
        (recur nxt (into seen nxt))))))

(defn- lazy-custom-registry [forms]
  (let [compiled (volatile! {})
        reg (volatile! nil)
        host-reg @host-only-registry
        lazy (reify mr/Registry
               (-schema [_ k]
                 (or (get @compiled k)
                     (when-let [f (get forms k)]
                       (let [s (m/schema f {:registry @reg})]
                         (vswap! compiled assoc k s)
                         s))))
               (-schemas [_] @compiled))]
    (vreset! reg (mr/composite-registry host-reg lazy))
    @reg))

(defn- validate-contract-schema [db forms-cell sch]
  (when-not (fast-valid-malli? sch)
    (let [refs (custom-schema-refs sch)
          host-check #(store/lru-get! host-contract-cache 1024 sch
                        (fn []
                          (try
                            (m/schema sch {:registry @host-only-registry})
                            nil
                            (catch Throwable ex (or (.getMessage ex) (str ex))))))]
      (if (empty? refs)
        (host-check)
        (let [forms (or @forms-cell
                        (let [df (schema/db-schema-forms db)
                              f (if (every? #(contains? df %) refs)
                                  df
                                  (merge @schema/disk-base-forms df))]
                          (vreset! forms-cell f)
                          f))]
          (if-not (some #(contains? forms %) refs)
            (host-check)
            (let [rel-forms (select-keys forms (transitive-schema-refs forms refs))]
              (store/lru-get! custom-contract-cache 256 [rel-forms sch]
                (fn []
                  (try
                    (m/schema sch {:registry (lazy-custom-registry rel-forms)})
                    nil
                    (catch Throwable ex (or (.getMessage ex) (str ex)))))))))))))

(defn invalid-contract-violations
  "Hard health & merge gate: validates that every `:seon.code.var/schema` stored on `db`
   compiles against the live Malli registry. Accepts optional `var-snap`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :map]] [:vector :map]]]}
  ([db] (invalid-contract-violations db nil))
  ([db var-snap]
   (if-not (and db (contains? (:schema db) :seon.code.var/schema))
     []
     (let [br (get-in db [:config :branch] :main)
           forms-cell (volatile! nil)
           checked (java.util.HashMap. 300)
           rows (if var-snap
                  (:var-schemas var-snap)
                  (d/datoms db :aevt :seon.code.var/schema))]
       (->> rows
            (keep
              (fn [row]
                (let [[e vsym nsym _ sch] (if var-snap row [(:e row) nil nil nil (:v row)])
                      err (if (.containsKey checked sch)
                            (.get checked sch)
                            (let [res (validate-contract-schema db forms-cell sch)]
                              (.put checked sch res)
                              res))]
                  (when err
                    (let [vsym (or vsym (:v (first (d/datoms db :eavt e :seon.code.var/name))))
                          nsym (or nsym (:v (first (d/datoms db :eavt e :seon.code.var/ns))))]
                      (when vsym
                        {:class :invalid-contract
                         :severity :error
                         :kind :static
                         :ns nsym
                         :var vsym
                         :subject (str vsym)
                         :branch br
                         :message (str "Invalid :malli/schema on " vsym ": " err
                                       " — in Malli use predicate symbols like number?, pos-int?, nat-int? (not :number), "
                                       "or built-ins :int, :double, :string, :boolean, :keyword, :map, :vector, :any, :seon.host/db.")}))))))
            (sort-by :subject)
            vec)))))