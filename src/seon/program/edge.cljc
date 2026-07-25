(ns seon.program.edge
  "Canonical direct program edges shared by both authored-code tees."
  (:require [clojure.string :as str]
            [seon.content-hash :as content-hash]
            [seon.db :as db]
            [seon.schema :as schema]))

;;; Persisted edge and terminal attributes

(schema/register! ::generation :string)
(schema/register! ::calls [:set :string])
(schema/register! ::read-attributes [:set :qualified-keyword])
(schema/register! ::written-attributes [:set :qualified-keyword])
(schema/register! ::all-at-basis? :boolean)
(schema/register!
 ::uncertainty
 [:enum :constructed-keyword
  :dynamic-call
  :dynamic-read-attributes
  :dynamic-written-attributes
  :macro-expansion
  :open-higher-order
  :unresolved-symbol
  :value-passed-pattern])
(schema/register! ::uncertainties [:set ::uncertainty])
(schema/register!
 ::terminal-symbol
 [:and {:seon.db/identity true} [:string {:min 3}]])
(schema/register!
 ::effect
 [:enum :pure :read :idempotent :external])
(schema/register! ::required-bindings [:set :string])
(schema/register! ::terminal-generation :string)
(schema/register! ::terminal-refs [:set :seon.db/ref])

(schema/register! ::namespace :symbol)
(schema/register! ::aliases [:map-of :symbol :symbol])
(schema/register! ::refers [:map-of :symbol :qualified-symbol])
(schema/register! ::current-vars [:set :symbol])
(schema/register! ::core-vars [:set :symbol])
(schema/register! ::known-namespaces [:set :symbol])
(schema/register! ::macro-symbols [:set :qualified-symbol])
(schema/register! ::effects [:map-of :qualified-symbol ::effect])
(schema/register!
 ::resolution
 [:map {:closed true}
  [::namespace ::namespace]
  [::aliases ::aliases]
  [::refers ::refers]
  [::current-vars ::current-vars]
  [::core-vars ::core-vars]
  [::known-namespaces ::known-namespaces]
  [::macro-symbols ::macro-symbols]
  [::effects ::effects]])

(schema/register! ::function-symbol :string)
(schema/register! ::form :any)
(schema/register! ::function-effect ::effect)
(schema/register!
 ::analyze-request
 [:map {:closed true}
  [::function-symbol ::function-symbol]
  [::form ::form]
  [::resolution ::resolution]
  [::function-effect {:optional true} ::function-effect]])
(schema/register!
 ::terminal
 [:map {:closed true}
  [::terminal-symbol ::terminal-symbol]
  [::effect ::effect]
  [::required-bindings ::required-bindings]
  [::terminal-generation ::terminal-generation]])
(schema/register! ::terminals [:vector ::terminal])
(schema/register!
 ::bundle
 [:map {:closed true}
  [::function-symbol ::function-symbol]
  [::generation ::generation]
  [::calls ::calls]
  [::read-attributes ::read-attributes]
  [::written-attributes ::written-attributes]
  [::all-at-basis? ::all-at-basis?]
  [::uncertainties ::uncertainties]
  [::terminals ::terminals]])

(def stored-function-attrs
  "The direct-edge attributes stored on a `:seon.fn` entity."
  #{::generation ::calls ::read-attributes ::written-attributes
    ::all-at-basis? ::uncertainties ::terminal-refs})

;;; Symbol resolution

(defn- canonical-target [target]
  (if (= "cljs.core" (namespace target))
    (symbol "clojure.core" (name target))
    target))

(defn- qualified-target
  [{::keys [aliases known-namespaces]} value]
  (let [prefix (some-> value namespace symbol)
        target-ns (or (get aliases prefix)
                      (when (contains? known-namespaces prefix) prefix))]
    (when target-ns
      (canonical-target (symbol (str target-ns) (name value))))))

(defn- resolved-target
  [{::keys [namespace refers current-vars core-vars] :as resolution} value]
  (when (symbol? value)
    (if (qualified-symbol? value)
      (qualified-target resolution value)
      (some->
       (or (get refers value)
           (when (contains? current-vars value)
             (symbol (str namespace) (name value)))
           (when (contains? core-vars value)
             (symbol "clojure.core" (name value))))
       canonical-target))))

(defn- binding-symbols [binding-form]
  (into #{}
        (filter #(and (symbol? %) (not= '& %) (not= '_ %)))
        (tree-seq coll? seq binding-form)))

(defn- closed-targets [resolution locals value]
  (cond
    (symbol? value)
    (if (contains? locals value)
      (get locals value)
      (some-> (resolved-target resolution value) hash-set))

    (and (seq? value) (= 'if (first value)))
    (let [branches (map #(closed-targets resolution locals %)
                        (drop 2 value))]
      (when (and (seq branches) (every? set? branches))
        (apply into #{} branches)))

    (and (seq? value) (= 'do (first value)))
    (closed-targets resolution locals (last value))

    :else nil))

;;; Canonical terminal descriptors

(defn- canonical-terminal
  [{::keys [effects]} target]
  (let [target (canonical-target target)
        target-string (str target)
        effect (get effects target :external)
        base {::terminal-symbol target-string
              ::effect effect
              ::required-bindings (if (= :pure effect)
                                    #{}
                                    #{target-string})}]
    (assoc base ::terminal-generation
           (content-hash/sha-256
            (pr-str [target-string effect [target-string]])))))

(defn- add-uncertainty [state reason]
  (update state ::uncertainties conj reason))

(defn- add-call [state resolution target]
  (let [target (canonical-target target)]
    (-> state
        (update ::calls conj (str target))
        (assoc-in [::terminal-by-symbol (str target)]
                  (canonical-terminal resolution target))
        (cond-> (= 'clojure.core/keyword target)
          (add-uncertainty :constructed-keyword)))))

(defn- add-read-dependencies [state dependencies]
  (if (= :all dependencies)
    (assoc state ::all-at-basis? true)
    (update state ::read-attributes into dependencies)))

;;; Literal database edge projection

(defn- quoted-value [value]
  (if (and (seq? value)
           (= 'quote (first value))
           (= 2 (count value)))
    (second value)
    value))

(defn- lookup-ref-attribute [value]
  (let [value (quoted-value value)]
    (when (and (vector? value)
               (= 2 (count value))
               (qualified-keyword? (first value)))
      (first value))))

(def ^:private transaction-attribute-ops
  #{:db/add :db/retract :db/cas :db.fn/cas
    :db.fn/retractAttribute})

(declare written-attributes)

(defn- written-map-attributes [state value]
  (reduce-kv
   (fn [result attribute field-value]
     (let [result
           (cond
             (= :db/id attribute) result
             (qualified-keyword? attribute)
             (update result ::written-attributes conj attribute)
             :else
             (add-uncertainty result :dynamic-written-attributes))
           result
           (if-let [lookup-attribute
                    (lookup-ref-attribute field-value)]
             (update result ::written-attributes conj lookup-attribute)
             result)]
       result))
   state value))

(defn- written-operation-attributes [state value]
  (let [operation (first value)
        state
        (if (contains? transaction-attribute-ops operation)
          (let [attribute (nth value 2 nil)]
            (if (qualified-keyword? attribute)
              (update state ::written-attributes conj attribute)
              (add-uncertainty state :dynamic-written-attributes)))
          state)]
    (reduce
     (fn [result item]
       (if-let [attribute (lookup-ref-attribute item)]
         (update result ::written-attributes conj attribute)
         result))
     state (rest value))))

(defn- written-attributes [state value]
  (let [value (quoted-value value)]
    (cond
      (map? value) (written-map-attributes state value)

      (and (vector? value)
           (keyword? (first value))
           (some-> (namespace (first value))
                   (str/starts-with? "db")))
      (written-operation-attributes state value)

      (vector? value)
      (reduce written-attributes state value)

      :else
      (add-uncertainty state :dynamic-written-attributes))))

(defn- transact-edges [state argument]
  (let [argument (quoted-value argument)
        tx-data (if (map? argument)
                  (get argument ::db/tx-data ::dynamic)
                  argument)]
    (if (= ::dynamic tx-data)
      (add-uncertainty state :dynamic-written-attributes)
      (written-attributes state tx-data))))

(defn- static-query-request [arguments]
  (let [first-argument (first arguments)
        request (quoted-value first-argument)]
    (cond
      (and (map? request) (contains? request ::db/query))
      (cond-> {::db/query (quoted-value (::db/query request))}
        (contains? request ::db/args)
        (assoc ::db/args
               (mapv quoted-value (quoted-value (::db/args request)))))

      (or (vector? request) (map? request) (string? request))
      {::db/query request
       ::db/args (mapv quoted-value (rest arguments))}

      :else nil)))

(defn- query-edges [state arguments]
  (if-let [request (static-query-request arguments)]
    (add-read-dependencies state (db/read-attribute-dependencies request))
    (-> state
        (assoc ::all-at-basis? true)
        (add-uncertainty :dynamic-read-attributes)
        (add-uncertainty :value-passed-pattern))))

(defn- static-pull-request [arguments]
  (let [first-argument (quoted-value (first arguments))]
    (cond
      (and (map? first-argument)
           (contains? first-argument ::db/pull-pattern))
      (cond-> {::db/pull-pattern
               (quoted-value (::db/pull-pattern first-argument))}
        (::db/ref first-argument)
        (assoc ::db/refs [(quoted-value (::db/ref first-argument))])
        (::db/refs first-argument)
        (assoc ::db/refs
               (mapv quoted-value
                     (quoted-value (::db/refs first-argument)))))

      (vector? first-argument)
      {::db/pull-pattern first-argument
       ::db/refs (cond-> []
                   (second arguments)
                   (conj (quoted-value (second arguments))))}

      (vector? (quoted-value (second arguments)))
      {::db/pull-pattern (quoted-value (second arguments))
       ::db/refs (cond-> []
                   (nth arguments 2 nil)
                   (conj (quoted-value (nth arguments 2))))}

      :else nil)))

(defn- pull-edges [state arguments]
  (if-let [request (static-pull-request arguments)]
    (add-read-dependencies state (db/read-attribute-dependencies request))
    (-> state
        (assoc ::all-at-basis? true)
        (add-uncertainty :dynamic-read-attributes)
        (add-uncertainty :value-passed-pattern))))

(defn- entity-edges [state arguments]
  (let [entity-ref (quoted-value (last arguments))]
    (cond-> (assoc state ::all-at-basis? true)
      (lookup-ref-attribute entity-ref)
      (update ::read-attributes conj (lookup-ref-attribute entity-ref)))))

(defn- database-call-edges [state target arguments]
  (case target
    seon.db/transact! (transact-edges state (first arguments))
    seon.db/query (query-edges state arguments)
    seon.db/query-with-evidence (query-edges state arguments)
    seon.db/pull (pull-edges state arguments)
    seon.db/pull-many (pull-edges state arguments)
    seon.db/entity (entity-edges state arguments)
    state))

;;; Function-form walk

(declare walk-expression)

(defn- walk-expressions [state resolution locals expressions]
  (reduce #(walk-expression %1 resolution locals %2) state expressions))

(defn- walk-binding-form [state resolution locals bindings body]
  (loop [state state
         locals locals
         pairs (partition 2 bindings)]
    (if-let [[binding-form init] (first pairs)]
      (let [state (walk-expression state resolution locals init)
            targets (closed-targets resolution locals init)
            locals
            (if (symbol? binding-form)
              (assoc locals binding-form (or targets ::open))
              (reduce #(assoc %1 %2 ::open)
                      locals (binding-symbols binding-form)))]
        (recur state locals (rest pairs)))
      (walk-expressions state resolution locals body))))

(defn- fn-methods [tail]
  (let [tail (drop-while #(or (string? %) (map? %)) tail)]
    (if (vector? (first tail))
      [tail]
      tail)))

(defn- walk-fn [state resolution locals tail]
  (reduce
   (fn [result method]
     (let [[parameters & body] method
           method-locals
           (reduce #(assoc %1 %2 ::open)
                   locals (binding-symbols parameters))]
       (walk-expressions result resolution method-locals body)))
   state (fn-methods tail)))

(defn- argument-uncertainties [state resolution locals arguments]
  (reduce
   (fn [result argument]
     (if (set? (closed-targets resolution locals argument))
       (add-uncertainty result :value-passed-pattern)
       result))
   state arguments))

(defn- walk-call [state resolution locals form]
  (let [head (first form)
        arguments (rest form)
        local-target (when (and (symbol? head) (contains? locals head))
                       (get locals head))
        target (when (and (symbol? head)
                          (not (contains? locals head)))
                 (resolved-target resolution head))
        state
        (cond
          (set? local-target)
          (reduce #(add-call %1 resolution %2) state local-target)

          (= ::open local-target)
          (add-uncertainty state :open-higher-order)

          (and target
               (contains? (::macro-symbols resolution) target))
          (add-uncertainty state :macro-expansion)

          target
          (add-call state resolution target)

          (symbol? head)
          (add-uncertainty state :unresolved-symbol)

          :else
          (add-uncertainty state :dynamic-call))
        state (if target
                (database-call-edges state target arguments)
                state)
        state (walk-expressions state resolution locals arguments)]
    (argument-uncertainties state resolution locals arguments)))

(defn- walk-expression [state resolution locals form]
  (cond
    (symbol? form)
    (cond
      (contains? locals form) state
      (resolved-target resolution form) state
      :else (add-uncertainty state :unresolved-symbol))

    (not (seq? form)) state
    (= 'quote (first form)) state

    (contains? #{'let 'let* 'loop 'loop*} (first form))
    (walk-binding-form state resolution locals
                       (second form) (drop 2 form))

    (contains? #{'fn 'fn*} (first form))
    (walk-fn state resolution locals (rest form))

    (= 'if (first form))
    (walk-expressions state resolution locals (rest form))

    (= 'do (first form))
    (walk-expressions state resolution locals (rest form))

    (contains? #{'recur 'throw 'set! 'var 'new '. 'try 'catch 'finally}
               (first form))
    (walk-expressions state resolution locals (rest form))

    :else (walk-call state resolution locals form)))

(defn- canonical-bundle-value [bundle]
  {::function-symbol (::function-symbol bundle)
   ::calls (vec (sort (::calls bundle)))
   ::read-attributes (vec (sort-by str (::read-attributes bundle)))
   ::written-attributes (vec (sort-by str (::written-attributes bundle)))
   ::all-at-basis? (boolean (::all-at-basis? bundle))
   ::uncertainties (vec (sort (::uncertainties bundle)))
   ::terminals
   (mapv (fn [terminal]
           (-> terminal
               (update ::required-bindings #(vec (sort %)))))
         (sort-by ::terminal-symbol (::terminals bundle)))})

(defn analyze-function
  "Return one canonical direct-edge bundle for an accepted function form."
  {:malli/schema [:=> [:cat ::analyze-request] ::bundle]}
  [{::keys [function-symbol form resolution function-effect]}]
  (let [initial {::function-symbol function-symbol
                 ::calls #{}
                 ::read-attributes #{}
                 ::written-attributes #{}
                 ::all-at-basis? false
                 ::uncertainties #{}
                 ::terminal-by-symbol {}}
        tail (drop 2 form)
        walked
        (if (and (seq? form)
                 (symbol? (first form))
                 (contains? #{"defn" "defn-"} (name (first form))))
          (walk-fn initial resolution {} tail)
          (add-uncertainty initial :dynamic-call))
        walked
        (if function-effect
          (assoc-in walked
                    [::terminal-by-symbol function-symbol]
                    (canonical-terminal
                     (assoc-in resolution
                               [::effects (symbol function-symbol)]
                               function-effect)
                     (symbol function-symbol)))
          walked)
        bundle
        (-> walked
            (assoc ::terminals
                   (vec (sort-by ::terminal-symbol
                                 (vals (::terminal-by-symbol walked)))))
            (dissoc ::terminal-by-symbol))
        generation
        (content-hash/sha-256 (pr-str (canonical-bundle-value bundle)))]
    (assoc bundle ::generation generation)))

(defn program-graph-digest
  "Return one canonical sibling digest for a collection of edge bundles."
  {:malli/schema [:=> [:cat [:sequential ::bundle]] :string]}
  [bundles]
  (content-hash/sha-256
   (pr-str
    (mapv canonical-bundle-value
          (sort-by ::function-symbol bundles)))))

(defn reconstruct-bundles
  "Reconstruct exact canonical bundles from pulled persisted function rows."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector ::bundle]]}
  [function-rows]
  (mapv
   (fn [function-row]
     {::function-symbol (:seon.fn/sym function-row)
      ::generation (::generation function-row)
      ::calls (set (::calls function-row))
      ::read-attributes (set (::read-attributes function-row))
      ::written-attributes (set (::written-attributes function-row))
      ::all-at-basis? (true? (::all-at-basis? function-row))
      ::uncertainties (set (::uncertainties function-row))
      ::terminals
      (mapv
       (fn [terminal]
         {::terminal-symbol (::terminal-symbol terminal)
          ::effect (::effect terminal)
          ::required-bindings (set (::required-bindings terminal))
          ::terminal-generation (::terminal-generation terminal)})
       (sort-by ::terminal-symbol (::terminal-refs function-row)))})
   (sort-by :seon.fn/sym function-rows)))

;;; Exact persistence transition

(defn transition-tx
  "Return exact transaction data for one direct-edge bundle."
  {:malli/schema [:=> [:cat ::bundle] [:vector :any]]}
  [bundle]
  (let [function-ref [:seon.fn/sym (::function-symbol bundle)]
        retracts
        (mapv (fn [attribute]
                [:db.fn/retractAttribute function-ref attribute])
              (sort stored-function-attrs))
        function-map
        (cond-> {:seon.fn/sym (::function-symbol bundle)
                 ::generation (::generation bundle)}
          (::all-at-basis? bundle) (assoc ::all-at-basis? true))
        function-adds
        (concat
         (map #(vector :db/add function-ref ::calls %)
              (sort (::calls bundle)))
         (map #(vector :db/add function-ref ::read-attributes %)
              (sort-by str (::read-attributes bundle)))
         (map #(vector :db/add function-ref ::written-attributes %)
              (sort-by str (::written-attributes bundle)))
         (map #(vector :db/add function-ref ::uncertainties %)
              (sort (::uncertainties bundle))))
        terminal-data
        (mapcat
         (fn [terminal]
           (let [terminal-ref [::terminal-symbol (::terminal-symbol terminal)]
                 terminal-id (str "seon.program.edge/terminal:"
                                  (::terminal-symbol terminal))]
             (concat
              [[:db.fn/retractAttribute terminal-ref ::required-bindings]
               {:db/id terminal-id
                ::terminal-symbol (::terminal-symbol terminal)
                ::effect (::effect terminal)
                ::terminal-generation (::terminal-generation terminal)}
               [:db/add function-ref ::terminal-refs terminal-id]]
              (map #(vector :db/add terminal-id ::required-bindings %)
                   (sort (::required-bindings terminal))))))
         (sort-by ::terminal-symbol (::terminals bundle)))]
    (vec (concat retracts [function-map] function-adds terminal-data))))
