(ns seon.cost.growth
  "A function's growth profile: what its counted cost (armed calls, `seon.db` reads, allocated bytes) grows with,
  measured generatively. Inputs come from the function's Malli contract (`malli.generator/generate`) at two sizes; a
  database argument is the given value padded by `datahike.api/with`, once on attributes the call never reads
  (`pad-untouched`) and once on the attributes it does (`pad-touched`). Each dimension's growth is the exponent of
  the measure's ratio over the size's ratio, so a read count that grows while only untouched datoms grow is the
  lazy rescan. Counts, never wall time. Every pad and sample is bounded by its caller's stated datoms and samples."
  (:require [datahike.api :as d]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.db :as db]
            [seon.profile :as profile]))

(defn elements
  "How many values `value` holds, itself included: N's measure of an input or output. A database value counts one."
  {:malli/schema [:=> [:cat :any] [:int {:min 1}]]}
  [value]
  (if (and (coll? value) (not (instance? datahike.db.DB value)))
    (reduce + 1 (map elements value))
    1))

(defn counted
  "One call of `thunk`, counted: armed calls, reads among them, the calling thread's allocated bytes and the
  elements of its value (`seon.profile/measure`)."
  {:malli/schema [:=> [:cat :seon.profile/thunk] :seon.cost.growth/measure]}
  [thunk]
  (let [measured (profile/measure thunk)]
    {:seon.cost.growth/calls (:seon.profile/calls measured)
     :seon.cost.growth/reads (:seon.profile/reads measured)
     :seon.cost.growth/alloc-bytes (:seon.profile/alloc-bytes measured 0)
     :seon.cost.growth/n (elements (:seon.profile/value measured))}))

(defn growth-class
  "The growth class of a measured exponent: constant below 0.2, sublinear below 0.7, linear below 1.4, superlinear
  below 1.8, else quadratic."
  {:malli/schema [:=> [:cat :double] :seon.cost.growth/class]}
  [exponent]
  (cond (< exponent 0.2) :constant (< exponent 0.7) :sublinear (< exponent 1.4) :linear
        (< exponent 1.8) :superlinear :else :quadratic))

(defn dimension
  "Each measure's growth between `small` and `large`, measured at sizes `size-small` < `size-large`: both values,
  the exponent `ln(ratio) / ln(size ratio)`, its class and the added cost per added unit of size."
  {:malli/schema [:=> [:cat :seon.cost.growth/measure :seon.cost.growth/measure [:int {:min 1}] [:int {:min 1}]]
                  :seon.cost.growth/dimension]}
  [small large size-small size-large]
  (let [size-ratio (/ (double size-large) size-small)]
    (into {:seon.cost.growth/size-ratio size-ratio}
          (map (fn [k]
                 (let [a (get small k) b (get large k)
                       ratio (cond (pos? a) (/ (double b) a) (pos? b) (double b) :else 1.0)
                       exponent (if (and (pos? ratio) (< 1.0 size-ratio)) (/ (Math/log ratio) (Math/log size-ratio)) 0.0)]
                   [k {:seon.cost.growth/small a :seon.cost.growth/large b
                       :seon.cost.growth/exponent exponent :seon.cost.growth/class (growth-class exponent)
                       :seon.cost.growth/per-unit (/ (double (- b a)) (- size-large size-small))}])))
          [:seon.cost.growth/calls :seon.cost.growth/reads :seon.cost.growth/alloc-bytes])))

(defn touched-attributes
  "The attributes `thunk`'s database reads depend on (`datahike.api/dependency-plan-attributes` over the read
  evidence `seon.db/*read-evidence-sink*` collects), and how many reads widened to every attribute."
  {:malli/schema [:=> [:cat :seon.profile/thunk] :seon.cost.growth/read-attributes]}
  [thunk]
  ;; the sink is seon.db's own invocation-local evidence atom
  (let [sink (atom [])]
    (binding [db/*read-evidence-sink* sink] (thunk))
    (reduce (fn [touched {plan :datahike.read/dependency-plan position :seon.db/source-argument-position}]
              (let [attributes (if plan (d/dependency-plan-attributes plan position) :all)]
                (if (= :all attributes)
                  (update touched :seon.cost.growth/widened inc)
                  (update touched :seon.cost.growth/attributes into
                          (remove #(= "db" (namespace %)))
                          (if (map? attributes) (keys attributes) attributes)))))
            {:seon.cost.growth/attributes #{} :seon.cost.growth/widened 0}
            @sink)))

(defn pad-untouched
  "`database` with `datoms` more datoms on an attribute no function reads, `:seon.cost.growth/pad`, declared in the
  same `datahike.api/with`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:int {:min 1}]] :seon.db/database-value]}
  [database datoms]
  (:db-after (d/with database (into [{:db/ident :seon.cost.growth/pad :db/valueType :db.type/long
                                      :db/cardinality :db.cardinality/one}]
                                    (map (fn [i] {:seon.cost.growth/pad i}))
                                    (range datoms)))))

(defn- cloned-value
  "`value` of an attribute for clone `copy`: unchanged unless the attribute is unique, then a new value of its kind;
  `::skip` when no new value of its kind exists."
  {:malli/schema [:=> [:cat :any :boolean [:int {:min 1}]] :any]}
  [value unique? copy]
  (cond (not unique?) value
        (symbol? value) (symbol (namespace value) (str (name value) "__" copy))
        (keyword? value) (keyword (namespace value) (str (name value) "__" copy))
        (string? value) (str value "__" copy)
        (uuid? value) (java.util.UUID/randomUUID)
        :else ::skip))

(defn pad-touched
  "`database` with `copies` clones of every entity holding one of `attributes`, each clone holding only those
  attributes: unique values renamed, refs between cloned entities kept inside the clone, other refs shared. The
  clones are `copies` × the touched datoms (`:seon.cost.growth/touched-datoms`); cluster configuration and tuple
  attributes are not cloned (one configuration per cluster)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :qualified-keyword] [:int {:min 1}]]
                  :seon.cost.growth/padded]}
  [database attributes copies]
  (let [schema (d/schema database)
        attributes (filterv #(and (get schema %)
                                  (not= :db.type/tuple (:db/valueType (get schema %)))
                                  (not (.startsWith (str (namespace %)) "seon.config")))
                            attributes)
        datoms (into [] (mapcat #(d/datoms database :aevt %)) attributes)
        entities (into #{} (map :e) datoms)
        tx (for [copy (range 1 (inc copies))
                 {:keys [e a v]} datoms
                 :let [declared (get schema a)
                       ref? (= :db.type/ref (:db/valueType declared))
                       value (cond (and ref? (entities v)) (str "clone-" copy "-" v)
                                   ref? v
                                   :else (cloned-value v (boolean (:db/unique declared)) copy))]
                 :when (not= ::skip value)]
             [:db/add (str "clone-" copy "-" e) a value])]
    {:seon.cost.growth/database (if (seq datoms) (:db-after (d/with database (vec tx))) database)
     :seon.cost.growth/touched-datoms (count datoms)}))

(defn database-growth
  "How `f`, a function of one database value, grows with untouched datoms (`database` padded by `pad-datoms` on an
  untouched attribute against one) and with touched datoms (2× against 4× the attributes its call on `database`
  reads, `pad-touched`). Each value is fresh, so a cache keyed by commit misses on all four; a cache keyed by the
  touched attributes' content hits on both untouched values, as it would in use."
  {:malli/schema [:=> [:cat ifn? :seon.db/database-value [:int {:min 1}]] :seon.cost.growth/profile]}
  [f database pad-datoms]
  (let [touched (touched-attributes #(f database))
        ;; one call on another fresh value first, so neither measured untouched value pays the pad attribute's
        ;; one-time derivations (its schema projection) alone
        _ (f (pad-untouched database 2))
        total (count (d/datoms database :eavt))
        small (pad-touched database (:seon.cost.growth/attributes touched) 1)
        large (pad-touched database (:seon.cost.growth/attributes touched) 3)
        t (max 1 (:seon.cost.growth/touched-datoms small))
        [u-small u-large t-small t-large]
        (mapv (fn [value] (counted #(f value)))
              [(pad-untouched database 1) (pad-untouched database pad-datoms)
               (:seon.cost.growth/database small) (:seon.cost.growth/database large)])]
    (merge touched
           {:seon.cost.growth/total-datoms total
            :seon.cost.growth/touched-datoms t
            :seon.cost.growth/untouched (dimension u-small u-large (inc total) (+ total pad-datoms))
            :seon.cost.growth/touched (dimension t-small t-large (* 2 t) (* 4 t))
            :seon.cost.growth/measures [u-small u-large t-small t-large]})))

(defn input-growth
  "How `f` grows with its generated input: `samples` argument lists generated from its `:=>` contract `schema` at
  generator size `small-size` and at 4× it (`malli.generator/generate`, seeds 0 to `samples` − 1), each call
  counted; the size is the arguments' elements (`elements`) summed over the samples, and so is each measure."
  {:malli/schema [:=> [:cat ifn? :seon.schema/value :map [:int {:min 1}] [:int {:min 1}]] :seon.cost.growth/profile]}
  [f schema options small-size samples]
  (let [input (:input (m/-function-info (m/schema schema options)))
        at (fn [size]
             (let [calls (for [seed (range samples)
                               :let [arguments (mg/generate input {:size size :seed seed})]]
                           [(elements arguments) (counted #(apply f arguments))])]
               [(reduce + (map first calls))
                (apply merge-with + (map second calls))]))
        [n-small small] (at small-size)
        [n-large large] (at (* 4 small-size))]
    {:seon.cost.growth/input (dimension small large n-small (max (inc n-small) n-large))
     :seon.cost.growth/measures [small large]}))

(defn generatable
  "How a contracted function's inputs can be generated, without generating any: `:generated` (every argument has a
  generator), `:database` (a database value and generated arguments), `:connection` (it needs a connection), `:none`
  (an argument has no generator), `:no-arguments`, or `:invalid` (its contract does not compile in `options`)."
  {:malli/schema [:=> [:cat :seon.schema/value :map] :seon.cost.growth/generatable]}
  [schema options]
  (try
    (let [compiled (m/schema schema options)
          arity (if (= :function (m/type compiled)) (first (m/children compiled)) compiled)
          arguments (m/children (:input (m/-function-info arity)))
          kind (fn [argument]
                 (case (m/form argument)
                   :seon.db/database-value :database
                   :seon.db/connection :connection
                   (try (mg/generator argument) :generated
                        (catch clojure.lang.ExceptionInfo _ :none))))
          kinds (into #{} (map kind) arguments)]
      (cond (empty? arguments) :no-arguments
            (kinds :connection) :connection
            (kinds :none) :none
            (kinds :database) :database
            :else :generated))
    ;; Malli's compile refuses an unresolvable contract by ex-info: that contract's kind
    (catch clojure.lang.ExceptionInfo _ :invalid)))
