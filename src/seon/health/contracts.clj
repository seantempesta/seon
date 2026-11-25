(ns seon.health.contracts
  "Contract & reference integrity health gates."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.datom :as dd]
            [datahike.index.interface :as di]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.ctx.diff :as diff]
            [seon.health.static :as static]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn- mk-broken-symbol-prob [br src-kind owner sym]
  {:class :broken-symbol-ref :severity :error :kind :static
   :ns (symbol (namespace sym)) :var sym
   :subject (str src-kind " " owner " -> " sym) :branch br
   :message (str "Broken symbol reference in " src-kind " on " owner
                 ": var " sym " does not exist. Define it or update the reference.")})

(defn broken-symbol-refs
  "Flags qualified symbols in `:seon.render/hiccup` inside `:seon.schema/form` missing from `:seon.code.var/name`.
   Optional `opts` map `{:target-syms syms}` scopes to O(|schemas|) checks for specific retracted vars."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :map]] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :map] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (broken-symbol-refs db nil nil))
  ([db var-snap] (broken-symbol-refs db var-snap nil))
  ([db var-snap opts]
   (if-not (and db (contains? (:schema db) :seon.code.var/name) (contains? (:schema db) :seon.schema/form))
     []
     (let [br (get-in db [:config :branch] :main)]
       (if (contains? opts :target-syms)
         (let [missing (into #{} (remove #(first (d/datoms db :avet :seon.code.var/name %))) (:target-syms opts))]
           (if (empty? missing)
             []
             (->> (for [dt (d/datoms db :aevt :seon.schema/form)
                        :let [f (:v dt)
                              r (when (and (vector? f) (map? (second f))) (:seon.render/hiccup (second f)))]
                        :when (and (qualified-symbol? r) (contains? missing r))
                        :let [sk (:v (first (d/datoms db :eavt (:e dt) :seon.schema/key)))]
                        :when sk]
                    (mk-broken-symbol-prob br :seon.render/hiccup sk r))
                  distinct (sort-by :subject) vec)))
         (let [live-vars (or (:all-var-syms var-snap)
                             (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name)))]
           (if (empty? live-vars)
             []
             (let [errs (volatile! [])]
               (doseq [dt (d/datoms db :aevt :seon.schema/form)]
                 (let [f (:v dt)
                       r (when (and (vector? f) (map? (second f))) (:seon.render/hiccup (second f)))]
                   (when (and (qualified-symbol? r)
                              (not (contains? live-vars r))
                              (nil? (when-let [ns-obj (find-ns (symbol (namespace r)))]
                                      (ns-resolve ns-obj (symbol (name r))))))
                     (let [k (:v (first (d/datoms db :eavt (:e dt) :seon.schema/key)))]
                       (vswap! errs conj (mk-broken-symbol-prob br :seon.render/hiccup k r))))))
               (vec (sort-by :subject (distinct @errs)))))))))))

(defn- mk-ns-doc-prob [br nsym sym]
  {:class :broken-doc-ref :severity :error :kind :static
   :ns nsym :subject (str nsym "#ns->" sym) :branch br
   :message (str "Namespace docstring of " nsym " references missing var `" sym "`. Update the docstring.")})

(defn- mk-var-doc-prob [br nsym vsym sym]
  {:class :broken-doc-ref :severity :error :kind :static
   :ns nsym :var vsym :subject (str vsym "->" sym) :branch br
   :message (str "Docstring of " vsym " references missing var `" sym "`. Update the docstring or restore the var.")})

(defn- broken-doc-refs-by-syms [db br syms]
  (let [missing (vec (distinct (remove #(first (d/datoms db :avet :seon.code.var/name %)) syms)))
        needles (mapv (fn [s] [s (str "`" s "`")]) missing)
        out (volatile! [])]
    (when (seq needles)
      (doseq [dt (d/datoms db :aevt :seon.code.var/doc)
              :let [doc (str (:v dt))] :when (str/includes? doc "`seon.")
              [sym needle] needles :when (str/includes? doc needle)
              :let [vsym (:v (first (d/datoms db :eavt (:e dt) :seon.code.var/name)))]
              :when (and vsym (not (str/ends-with? (str (namespace vsym)) "-test")))]
        (vswap! out conj (mk-var-doc-prob br (symbol (namespace vsym)) vsym sym)))
      (when (contains? (:schema db) :seon.code.ns/doc)
        (doseq [dt (d/datoms db :aevt :seon.code.ns/doc)
                :let [doc (str (:v dt))] :when (str/includes? doc "`seon.")
                [sym needle] needles :when (str/includes? doc needle)
                :let [nsym (:v (first (d/datoms db :eavt (:e dt) :seon.code.ns/name)))]
                :when (and nsym (not (str/ends-with? (name nsym) "-test")))]
          (vswap! out conj (mk-ns-doc-prob br nsym sym)))))
    (vec (sort-by :subject @out))))

(defn- parse-doc-qsym [^String s]
  (let [idx (.indexOf s "/")]
    (when (pos? idx) (symbol (subs s 0 idx) (subs s (inc idx))))))

(def ^:private doc-sym-pat #"`(seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`")

(defn- broken-doc-refs-by-nss [db br ns-syms]
  (if (empty? ns-syms)
    []
    (let [missing-in (fn [doc]
                       (when (and (string? doc) (str/includes? doc "`seon."))
                         (keep (fn [[_ ss]]
                                 (when-let [sym (parse-doc-qsym ss)]
                                   (when-not (first (d/datoms db :avet :seon.code.var/name sym)) sym)))
                               (re-seq doc-sym-pat doc))))
          out (volatile! [])]
      (doseq [nsym (distinct ns-syms) :when (and nsym (not (str/ends-with? (name nsym) "-test")))]
        (doseq [dt (d/datoms db :avet :seon.code.var/ns nsym)
                :let [e (:e dt) doc (:v (first (d/datoms db :eavt e :seon.code.var/doc)))]
                :when (and doc (str/includes? (str doc) "`seon."))]
          (when-let [ms (seq (missing-in (str doc)))]
            (when-let [vsym (:v (first (d/datoms db :eavt e :seon.code.var/name)))]
              (doseq [sym ms] (vswap! out conj (mk-var-doc-prob br nsym vsym sym))))))
        (when (contains? (:schema db) :seon.code.ns/doc)
          (when-let [ne (:e (first (d/datoms db :avet :seon.code.ns/name nsym)))]
            (when-let [ndoc (:v (first (d/datoms db :eavt ne :seon.code.ns/doc)))]
              (doseq [sym (missing-in (str ndoc))]
                (vswap! out conj (mk-ns-doc-prob br nsym sym)))))))
      (vec (sort-by :subject @out)))))

(defn broken-doc-refs
  "Detects backticked qualified symbol references inside var/ns docstrings missing from `:seon.code.var/name`.
   Optional `opts` map supports indexed `{:target-nss ns-syms}` or `{:target-syms syms}` scoping."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :symbol]] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map]] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (broken-doc-refs db nil nil nil))
  ([db scope] (broken-doc-refs db scope nil nil))
  ([db scope var-snap] (broken-doc-refs db scope var-snap nil))
  ([db scope var-snap opts]
   (if-not (and db (contains? (:schema db) :seon.code.var/name) (contains? (:schema db) :seon.code.var/doc))
     []
     (let [br (get-in db [:config :branch] :main)]
       (cond
         (contains? opts :target-nss) (broken-doc-refs-by-nss db br (:target-nss opts))
         (contains? opts :target-syms) (broken-doc-refs-by-syms db br (:target-syms opts))
         :else
         (let [all-vars (or (:all-var-syms var-snap) (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name)))
               eid->name ^java.util.HashMap (:eid->name var-snap)
               eid->ns ^java.util.HashMap (:eid->ns var-snap)
               scope-s (when scope (name scope))
               in-scope? (fn [nsym]
                           (and nsym (not (str/ends-with? (name nsym) "-test"))
                                (or (nil? scope-s) (= (name nsym) scope-s) (str/starts-with? (name nsym) (str scope-s ".")))))
               var-probs
               (let [out (volatile! [])]
                 (doseq [dt (d/datoms db :aevt :seon.code.var/doc)
                         :let [doc (str (:v dt))] :when (str/includes? doc "`seon.")]
                   (let [missing (keep (fn [[_ sym-s]] (let [sym (parse-doc-qsym sym-s)] (when (and sym (not (contains? all-vars sym))) sym)))
                                       (re-seq doc-sym-pat doc))]
                     (when (seq missing)
                       (let [e (:e dt)
                             vsym (if eid->name (.get eid->name e) (:v (first (d/datoms db :eavt e :seon.code.var/name))))
                             nsym (if eid->ns (.get eid->ns e) (:v (first (d/datoms db :eavt e :seon.code.var/ns))))]
                         (when (and vsym (in-scope? nsym))
                           (doseq [sym missing] (vswap! out conj (mk-var-doc-prob br nsym vsym sym))))))))
                 @out)
               ns-probs
               (if-not (contains? (:schema db) :seon.code.ns/doc)
                 []
                 (let [out (volatile! [])]
                   (doseq [dt (d/datoms db :aevt :seon.code.ns/doc)
                           :let [doc (str (:v dt))] :when (str/includes? doc "`seon.")]
                     (let [missing (keep (fn [[_ sym-s]] (let [sym (parse-doc-qsym sym-s)] (when (and sym (not (contains? all-vars sym))) sym)))
                                         (re-seq doc-sym-pat doc))]
                       (when (seq missing)
                         (let [nsym (:v (first (d/datoms db :eavt (:e dt) :seon.code.ns/name)))]
                           (when (in-scope? nsym)
                             (doseq [sym missing] (vswap! out conj (mk-ns-doc-prob br nsym sym))))))))
                   @out))]
           (vec (sort-by :subject (concat var-probs ns-probs)))))))))

(defonce ^:private ^:seon.flow/edge impure-gen-cache (store/lru-cache 32))

(defn- mk-impure-prob [br nsym vsym bad-refs]
  {:class :impure-generatable-fn :severity :error :kind :static
   :ns nsym :var vsym :subject (str vsym) :branch br
   :message (str "Generatable pure schema on " vsym " calls ^:seon.flow/edge var(s) "
                 (str/join ", " bad-refs)
                 ". Mark ^:seon.flow/edge, use :seon.host/* args, or keep pure logic separate from IO.")})

(defn- compute-impure-generatable-fns [db scope var-snap]
  (let [br (get-in db [:config :branch] :main)
        scope-s (when scope (name scope))
        edge-vars (if var-snap
                    (:edge-vars var-snap)
                    (into #{} (keep (fn [dt] (when (:v dt) (:v (first (d/datoms db :eavt (:e dt) :seon.code.var/name))))))
                          (d/datoms db :aevt :seon.code.var/flow-edge?)))]
    (if (empty? edge-vars)
      []
      (let [gen-eids (java.util.HashMap. 256)]
        (if var-snap
          (doseq [[e vsym nsym edge? sch] (:var-schemas var-snap)]
            (let [ns-s (when vsym (namespace vsym))]
              (when (and vsym ns-s (not edge?) (not (str/ends-with? ns-s "-test"))
                         (or (nil? scope-s) (= ns-s scope-s) (str/starts-with? ns-s (str scope-s ".")))
                         (schema/generatable-function-schema? sch))
                (.put gen-eids e [vsym (or nsym (symbol ns-s))]))))
          (let [edge-eids (into #{} (keep #(when (:v %) (:e %))) (d/datoms db :aevt :seon.code.var/flow-edge?))]
            (doseq [dt (d/datoms db :aevt :seon.code.var/schema)]
              (let [e (:e dt) sch (:v dt)]
                (when (and (not (contains? edge-eids e)) (schema/generatable-function-schema? sch))
                  (when-let [vsym (:v (first (d/datoms db :eavt e :seon.code.var/name)))]
                    (let [ns-s (namespace vsym)]
                      (when (and ns-s (not (str/ends-with? ns-s "-test"))
                                 (or (nil? scope-s) (= ns-s scope-s) (str/starts-with? ns-s (str scope-s "."))))
                        (.put gen-eids e [vsym (symbol ns-s)])))))))))
        (if (.isEmpty gen-eids)
          []
          (let [bad-by-e (java.util.HashMap. 16)]
            (doseq [esym edge-vars]
              (doseq [dt (d/datoms db :avet :seon.code.var/refs esym)]
                (let [e (:e dt)]
                  (when (.containsKey gen-eids e)
                    (let [^java.util.ArrayList al (or (.get bad-by-e e) (let [a (java.util.ArrayList. 2)] (.put bad-by-e e a) a))]
                      (.add al esym))))))
            (if (.isEmpty bad-by-e)
              []
              (->> (.entrySet bad-by-e)
                   (map (fn [^java.util.Map$Entry en]
                          (let [[vsym nsym] (.get gen-eids (.getKey en))
                                bad-refs (vec (sort (distinct ^java.util.ArrayList (.getValue en))))]
                            (mk-impure-prob br nsym vsym bad-refs))))
                   (sort-by :subject) vec))))))))

(defn- impure-generatable-fns-by-edges [db br edge-syms]
  (if (empty? edge-syms)
    []
    (let [live-edges (filter #(when-let [e (:e (first (d/datoms db :avet :seon.code.var/name %)))]
                                (true? (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?)))))
                             (distinct edge-syms))
          by-caller (reduce (fn [acc esym]
                              (reduce (fn [m dt] (update m (:e dt) (fnil conj #{}) esym))
                                      acc (d/datoms db :avet :seon.code.var/refs esym)))
                            {} live-edges)]
      (->> (for [[e bad-set] by-caller
                 :let [sch (:v (first (d/datoms db :eavt e :seon.code.var/schema)))]
                 :when (and sch (not (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?))))
                            (schema/generatable-function-schema? sch))
                 :let [vsym (:v (first (d/datoms db :eavt e :seon.code.var/name)))
                       nsym (when vsym (symbol (namespace vsym)))]
                 :when (and vsym (not (str/ends-with? (name nsym) "-test")))]
             (mk-impure-prob br nsym vsym (vec (sort bad-set))))
           (sort-by :subject) vec))))

(defn- impure-generatable-fns-by-nss [db br ns-syms]
  (if (empty? ns-syms)
    []
    (let [edge-cache (java.util.HashMap.)
          edge? (fn [sym]
                  (let [v (.get edge-cache sym)]
                    (if (some? v)
                      v
                      (let [res (boolean (when-let [e (:e (first (d/datoms db :avet :seon.code.var/name sym)))]
                                           (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?)))))]
                        (.put edge-cache sym res)
                        res))))]
      (->> (for [nsym (distinct ns-syms) :when (and nsym (not (str/ends-with? (name nsym) "-test")))
                 dt (d/datoms db :avet :seon.code.var/ns nsym)
                 :let [e (:e dt) sch (:v (first (d/datoms db :eavt e :seon.code.var/schema)))]
                 :when (and sch (not (:v (first (d/datoms db :eavt e :seon.code.var/flow-edge?))))
                            (schema/generatable-function-schema? sch))
                 :let [vsym (:v (first (d/datoms db :eavt e :seon.code.var/name)))
                       bad-refs (->> (d/datoms db :eavt e :seon.code.var/refs) (map :v) (filter edge?) sort distinct vec)]
                 :when (and vsym (seq bad-refs))]
             (mk-impure-prob br nsym vsym bad-refs))
           (sort-by :subject) vec))))

(defn impure-generatable-fns
  "Detects non-edge generatable functions calling `^:seon.flow/edge` vars via indexed `:avet :seon.code.var/refs`.
   Optional `opts` map supports indexed `{:target-nss ns-syms}` or `{:edge-syms edge-syms}` scoping."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :symbol]] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map]] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (impure-generatable-fns db nil nil nil))
  ([db scope] (impure-generatable-fns db scope nil nil))
  ([db scope var-snap] (impure-generatable-fns db scope var-snap nil))
  ([db scope var-snap opts]
   (if-not (and db (contains? (:schema db) :seon.code.var/schema)
                (contains? (:schema db) :seon.code.var/flow-edge?)
                (contains? (:schema db) :seon.code.var/refs))
     []
     (let [br (get-in db [:config :branch] :main)]
       (cond
         (contains? opts :target-nss) (impure-generatable-fns-by-nss db br (:target-nss opts))
         (contains? opts :edge-syms) (impure-generatable-fns-by-edges db br (:edge-syms opts))
         :else
         (if-let [csig (diff/code-db-sig db)]
           (do (when di/*reads*
                 (doseq [a [:seon.code.var/schema :seon.code.var/flow-edge? :seon.code.var/refs]]
                   (di/record-read! (dd/datom 0 a nil 0) (dd/datom Long/MAX_VALUE a nil Long/MAX_VALUE))))
               (store/lru-get! impure-gen-cache 32 [csig scope br]
                               #(binding [di/*reads* nil] (compute-impure-generatable-fns db scope var-snap))))
           (compute-impure-generatable-fns db scope var-snap)))))))

(defn missing-contract-violations
  "Hard merge gate: flags newly added/edited non-test public `defn`s lacking `:malli/schema`."
  {:malli/schema [:function [:=> [:cat :any :any] [:vector :seon.health/problem]] [:=> [:cat :any :any [:maybe [:or [:sequential :symbol] [:set :symbol]]]] [:vector :seon.health/problem]]]}
  ([ours-db theirs-db] (missing-contract-violations ours-db theirs-db nil))
  ([ours-db theirs-db touched-nss]
   (if-not (and theirs-db (contains? (:schema theirs-db) :seon.code.var/name) (contains? (:schema theirs-db) :seon.code.form/key))
     []
     (let [br (get-in theirs-db [:config :branch] :main)
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
                              (reduce (fn [a dt] (if-let [k (get ks (:e dt))] (assoc a k (:v dt)) a))
                                      {} (d/datoms db :aevt :seon.code.form/hash)))))
                   ohs (fmap ours-db) ths (fmap theirs-db)]
               (into #{} (keep (fn [[fk th]] (when (not= th (get ohs fk)) fk))) ths)))]
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
                              vsym (:seon.code.var/name v) nsym (:seon.code.var/ns v) fk (:seon.code.var/form v)]
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
                                {:class :missing-contract :severity :error :kind :static
                                 :ns nsym :var vsym :subject (str vsym) :branch br
                                 :message (str "Public defn " vsym " was added/edited without a :malli/schema contract. "
                                               "Add one via (user/set-meta! '" vsym " {:malli/schema [:=> [:cat ...] :any]}) — "
                                               "for host values use :seon.host/db, :seon.host/conn, :seon.host/chan, or :any; "
                                               "for pure fns include :cost/ticks & :cost/bytes for seed=42 generative proofs.")}))))))
                (sort-by :subject) vec)))))))

(def ^:private simple-malli-leaves
  #{:any :int :double :string :boolean :keyword :qualified-keyword :simple-keyword
    :symbol :qualified-symbol :simple-symbol :map :vector :set :nil :uuid :inst
    'number? 'pos-int? 'nat-int? 'neg-int? 'string? 'keyword? 'symbol? 'fn? 'bytes?
    'boolean? 'map? 'vector? 'set? 'int? 'pos? 'coll? 'sequential?
    :seon.host/db :seon.host/conn :seon.host/chan :seon.host/atom :seon.host/fn
    :seon.host/var :seon.host/ns :seon.host/deref :seon.host/throwable :char})

(def ^:private host-only-registry
  (delay
    (mr/composite-registry
     (m/default-schemas)
     (or (some-> (ns-resolve 'seon.schema 'host-predicates) deref) {}))))

(defonce ^:private ^:seon.flow/edge host-contract-cache (store/lru-cache 1024))

(defonce ^:private ^:seon.flow/edge custom-contract-cache (store/lru-cache 512))

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
      (:maybe :? :* :+) (and (= 2 (count s)) (fast-valid-malli? (second s)))
      (:sequential :vector :set)
      (or (and (= 2 (count s)) (fast-valid-malli? (second s)))
          (and (= 3 (count s)) (map? (second s))
               (every? #{:min :max :doc :description} (keys (second s)))
               (fast-valid-malli? (nth s 2))))
      :enum (and (>= (count s) 2) (every? #(or (keyword? %) (string? %) (number? %) (boolean? %) (nil? %)) (rest s)))
      (:int :string :double) (and (= 2 (count s)) (map? (second s)) (every? #{:min :max} (keys (second s))) (every? number? (vals (second s))))
      :map (let [p? (map? (second s))
                 ch (if p? (nnext s) (rest s))]
             (and (or (not p?) (every? #{:closed :doc :description :min :max} (keys (second s))))
                  (every? (fn [en]
                            (and (vector? en) (keyword? (first en))
                                 (or (and (= 2 (count en)) (fast-valid-malli? (second en)))
                                     (and (= 3 (count en)) (map? (second en))
                                          (every? #{:optional :doc :description} (keys (second en)))
                                          (fast-valid-malli? (nth en 2))))))
                          ch)))
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
                        x))]
    (into #{}
          (filter (fn [x]
                    (and (qualified-keyword? x)
                         (let [ns-s (namespace x)]
                           (and (not= "seon.host" ns-s) (not= "gen" ns-s) (not (str/starts-with? ns-s "malli")))))))
          (tree-seq coll? seq (strip-props s)))))

(defn- validate-contract-schema [db reg-cell sch]
  (when-not (fast-valid-malli? sch)
    (let [refs (custom-schema-refs sch)
          host-check #(store/lru-get! host-contract-cache 1024 sch
                        (fn []
                          (try (m/schema sch {:registry @host-only-registry}) nil
                               (catch Throwable ex (or (.getMessage ex) (str ex))))))]
      (if (empty? refs)
        (host-check)
        (let [{:keys [forms registry reg-sig]} (or @reg-cell (let [r (schema/registry-for-db db)] (vreset! reg-cell r) r))]
          (if-not (some #(contains? forms %) refs)
            (host-check)
            (store/lru-get! custom-contract-cache 512 [reg-sig sch]
              (fn []
                (try (m/schema sch {:registry registry}) nil
                     (catch Throwable ex (or (.getMessage ex) (str ex))))))))))))

(defn invalid-contract-violations
  "Validates stored `:seon.code.var/schema` forms on `db` reusing the compiled Malli registry.
   Optional `opts` `{:target-nss ns-syms}` scopes to O(|vars in nss|) indexed `:avet :seon.code.var/ns` checks."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :map]] [:vector :seon.health/problem]]
                  [:=> [:cat :seon.host/db [:maybe :map] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (invalid-contract-violations db nil nil))
  ([db var-snap] (invalid-contract-violations db var-snap nil))
  ([db var-snap opts]
   (if-not (and db (contains? (:schema db) :seon.code.var/schema))
     []
     (let [br (get-in db [:config :branch] :main)
           reg-cell (volatile! nil)
           checked (java.util.HashMap. 300)
           rows (cond
                  (contains? opts :target-nss)
                  (for [nsym (distinct (remove nil? (:target-nss opts)))
                        dt (d/datoms db :avet :seon.code.var/ns nsym)
                        :let [e (:e dt)
                              sch (:v (first (d/datoms db :eavt e :seon.code.var/schema)))]
                        :when sch]
                    [e nil nsym nil sch])
                  var-snap (:var-schemas var-snap)
                  :else (d/datoms db :aevt :seon.code.var/schema))]
       (->> rows
            (keep
             (fn [row]
               (let [[e vsym nsym _ sch] (if (vector? row) row [(:e row) nil nil nil (:v row)])
                     err (if (.containsKey checked sch)
                           (.get checked sch)
                           (let [res (validate-contract-schema db reg-cell sch)]
                             (.put checked sch res) res))]
                 (when err
                   (let [vsym (or vsym (:v (first (d/datoms db :eavt e :seon.code.var/name))))
                         nsym (or nsym (:v (first (d/datoms db :eavt e :seon.code.var/ns))))]
                     (when vsym
                       {:class :invalid-contract :severity :error :kind :static
                        :ns nsym :var vsym :subject (str vsym) :branch br
                        :message (str "Invalid :malli/schema on " vsym ": " err
                                      " — :malli/schema must be a static EDN Malli form (not a local def symbol); "
                                      "use predicate symbols like number?, pos-int?, nat-int? (not :number), "
                                      "or built-ins :int, :double, :string, :boolean, :keyword, :map, :vector, :any, :seon.host/db.")}))))))
            (sort-by :subject) vec)))))
