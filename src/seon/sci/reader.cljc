(ns seon.sci.reader
  "The one reader for accepted Clojure source."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [sci.core :as sci]))

(def ^:private eof ::eof)

(defn- error-value
  [kind message data]
  {kind (if (= ::refused-tag kind) (::tag data) true)
   :seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- refusal-handler
  [tag]
  (fn [_]
    (throw
     (ex-info (str "Reader tag is not accepted: " tag)
              {::refusal true
               ::tag tag}))))

(defn- accepted-reader
  [tags]
  (fn [tag]
    (or (get tags tag)
        (when-not #?(:clj (contains? clojure.core/default-data-readers tag)
                     :cljs false)
          (refusal-handler tag)))))

(defn- reject-read-eval
  [_]
  (throw
   (ex-info "Reader evaluation is not accepted: #="
            {::refusal true
             ;; `#=` cannot round-trip as an EDN symbol. Reader errors
             ;; cross the same ordinary-data boundary as eval results.
             ::tag "#="})))

(defn- line-starts
  [text]
  (persistent!
   (reduce-kv
    (fn [starts index character]
      (if (= \newline character)
        (conj! starts (inc index))
        starts))
    (transient [0])
    (vec text))))

(defn- cursor-offset
  [starts text-length line column]
  (let [line-start (get starts (dec line) text-length)]
    (min text-length (+ line-start (dec column)))))

(defn- offset-cursor
  [starts offset]
  (let [line (max 1 (count (take-while #(<= % offset) starts)))
        line-start (get starts (dec line) 0)]
    [line (inc (- offset line-start))]))

(defn- symbol-name
  [value]
  (when (symbol? value)
    value))

(defn- qualified-symbol
  [namespace-name value]
  (when (symbol? value)
    (if (namespace value)
      value
      (when namespace-name
        (symbol (str namespace-name) (str value))))))

(defn- syntax-quote-resolver
  [namespace-name aliases refers]
  (fn [value]
    (let [symbol-namespace (some-> value namespace symbol)]
      (cond
        (special-symbol? value)
        value

        symbol-namespace
        (if-let [target (get aliases symbol-namespace)]
          (symbol (str target) (name value))
          value)

        (contains? refers value)
        (get refers value)

        namespace-name
        (symbol (str namespace-name) (name value))

        :else
        value))))

(defn- parse-options
  [{::keys [ns aliases refers features tags defer-auto-resolve?]}]
  {:eof eof
   :features features
   :auto-resolve
   (fn [alias]
     (if (= :current alias)
       ns
       (let [alias-name (if (keyword? alias)
                          (symbol (name alias))
                          alias)]
         (or (get aliases alias-name)
             (when defer-auto-resolve? alias-name)))))
   :syntax-quote
   {:resolve-symbol (syntax-quote-resolver ns aliases refers)}
   ;; A function, not a map: Edamame consults it for built-ins too.
   :readers (accepted-reader tags)
   ;; Always explicit: never inherit SCI's dynamic *read-eval* policy.
   :read-eval reject-read-eval})

(defn- alias-binding
  [local-name target-ns]
  {:seon.ns.alias/local local-name
   :seon.ns.alias/target-ns target-ns})

(defn- refer-binding
  [local-name target-ns target-name]
  {:seon.ns.refer/local local-name
   :seon.ns.refer/target-ns target-ns
   :seon.ns.refer/target-name target-name})

(defn- require-bindings
  [require-spec publics]
  (cond
    (symbol? require-spec)
    {:requires #{require-spec}}

    (and (vector? require-spec)
         (symbol? (first require-spec)))
    (let [target (first require-spec)
          options (try
                    (apply hash-map (rest require-spec))
                    (catch #?(:clj Throwable :cljs :default) _
                      {}))
          alias (or (:as options) (:as-alias options))
          refers (:refer options)
          renames (or (:rename options) {})
          referred-names (cond
                           (sequential? refers) refers
                           (= :all refers) (get publics target #{})
                           :else [])]
      {:aliases
       (cond-> #{}
         (symbol? alias) (conj (alias-binding alias target)))
       :refers
       (into #{}
             (map (fn [target-name]
                    (refer-binding (get renames target-name target-name)
                                   target
                                   target-name)))
             referred-names)
       :requires
       (cond-> #{}
         (nil? (:as-alias options)) (conj target))
       ::refer-all-targets
       (cond-> #{}
         (= :all refers) (conj target))})

    :else
    {}))

(defn- namespace-clauses
  [form]
  (let [after-name (drop 2 form)
        after-doc (if (string? (first after-name))
                    (next after-name)
                    after-name)]
    (if (map? (first after-doc))
      (next after-doc)
      after-doc)))

(defn- namespace-info
  [form context]
  (when-let [namespace-name (symbol-name (second form))]
    (let [doc (when (string? (nth form 2 nil)) (nth form 2))
          requires (->> (namespace-clauses form)
                        (filter #(and (seq? %) (= :require (first %))))
                        (mapcat rest))
          bindings (map #(require-bindings % (::publics context)) requires)
          alias-rows (into #{} (mapcat :aliases) bindings)
          refer-rows (into #{} (mapcat :refers) bindings)
          required-targets (into #{} (mapcat :requires) bindings)
          refer-all-targets (into #{} (mapcat ::refer-all-targets) bindings)
          aliases (into {}
                        (map (juxt :seon.ns.alias/local
                                   :seon.ns.alias/target-ns))
                        alias-rows)
          refers (into {}
                       (map (fn [{:seon.ns.refer/keys
                                  [local target-ns target-name]}]
                              [local (symbol (str target-ns)
                                             (str target-name))]))
                       refer-rows)]
      (cond->
       {::ns namespace-name
        ::aliases aliases
        ::refers refers
        :seon.ns/name namespace-name
        :seon.ns/requires required-targets
        :seon.ns/aliases alias-rows
        :seon.ns/refers refer-rows
        ::refer-all-targets refer-all-targets}
        doc (assoc :seon.ns/doc doc)))))

(defn- quoted-symbol
  [value]
  (when (and (seq? value)
             (= 'quote (first value))
             (= 2 (count value))
             (symbol? (second value)))
    (second value)))

(defn- quoted-value
  [value]
  (when (and (seq? value)
             (= 'quote (first value))
             (= 2 (count value)))
    (second value)))

(defn- literal-require-bindings
  [form publics]
  (map #(require-bindings (or (quoted-value %) %) publics)
       (rest form)))

(declare resolved-operation)

(defn- function-declaration
  [form namespace-name context]
  (when (seq? form)
    (let [operation (first form)
          function-name (second form)]
      (when (and (contains? #{'clojure.core/defn 'clojure.core/defn-}
                            (resolved-operation operation context))
                 (symbol? function-name))
        (let [after-name (drop 2 form)
              doc (when (string? (first after-name)) (first after-name))
              after-doc (if doc (next after-name) after-name)
              attributes (if (map? (first after-doc))
                           (first after-doc)
                           {})
              declarations (if (map? (first after-doc))
                             (next after-doc)
                             after-doc)
              arglists
              (cond
                (vector? (first declarations))
                (list (first declarations))

                (and (seq? (first declarations))
                     (every? #(and (seq? %) (vector? (first %)))
                             declarations))
                (apply list (map first declarations))

                :else
                '())
              metadata (merge (meta form)
                              (meta operation)
                              (meta function-name)
                              attributes)
              qualified (qualified-symbol namespace-name function-name)
              schema (:malli/schema metadata)
              workload (:seon.workload metadata)]
          (cond->
           {:seon.fn/arglists (pr-str arglists)
            :seon.fn/private?
            (boolean (or (= 'clojure.core/defn-
                            (resolved-operation operation context))
                         (:private metadata)))}
            qualified (assoc :seon.fn/sym (str qualified))
            namespace-name
            (assoc :seon.fn/ns [:seon.ns/name namespace-name])
            doc (assoc :seon.fn/doc doc)
            schema (assoc :seon.fn/spec (pr-str schema))
            (contains? #{:io :compute} workload)
            (assoc :seon.fn/workload workload)))))))

(defn- test-declaration
  [form namespace-name context]
  (when (and (seq? form)
             (= 'clojure.test/deftest
                (resolved-operation (first form) context))
             (symbol? (second form)))
    (when-let [qualified (qualified-symbol namespace-name (second form))]
      {:seon.test/sym (str qualified)
       :seon.test/ns [:seon.ns/name namespace-name]})))

(defn- resolved-operation
  "Resolve an operator through explicit aliases/refers and Clojure core.

  Qualification is semantic: `foo/defn` remains `foo/defn`, while an
  unqualified core operation resolves to `clojure.core/*`."
  [operation {::keys [aliases refers]}]
  (when (symbol? operation)
    (if-let [operation-namespace (namespace operation)]
      (if-let [target (get aliases (symbol operation-namespace))]
        (symbol (str target) (name operation))
        operation)
      (or (get refers operation)
          (symbol "clojure.core" (name operation))))))

(defn- declaration-occurrence
  "The declaration family and independently placeable identity of `form`.

  An occurrence remains present when namespace attribution is unavailable.
  Build indexing can therefore refuse an unplaceable declaration instead of
  mistaking the absence of its durable row for the absence of a declaration."
  [form namespace-name context]
  (when (seq? form)
    (let [operation (resolved-operation (first form) context)
          declared-name (second form)]
      (cond
        (contains? #{'clojure.core/defn 'clojure.core/defn-} operation)
        (let [identity (when (symbol? declared-name)
                         (qualified-symbol namespace-name declared-name))]
          (cond-> {::declaration-family :seon.fn/sym}
            identity (assoc ::declaration-identity (str identity))
            (nil? identity)
            (assoc ::declaration-refusal
                   (if (symbol? declared-name)
                     ::namespace-unproven
                     ::malformed-declaration))))

        (= 'clojure.test/deftest operation)
        (let [identity (when (symbol? declared-name)
                         (qualified-symbol namespace-name declared-name))]
          (cond-> {::declaration-family :seon.test/sym}
            identity (assoc ::declaration-identity (str identity))
            (nil? identity)
            (assoc ::declaration-refusal
                   (if (symbol? declared-name)
                     ::namespace-unproven
                     ::malformed-declaration))))

        (= 'seon.schema/register! operation)
        (let [identity (when (and (= 3 (count form))
                                  (qualified-keyword? declared-name))
                         declared-name)]
          (cond-> {::declaration-family :seon.schema/key}
            identity (assoc ::declaration-identity identity)
            (nil? identity)
            (assoc ::declaration-refusal ::malformed-declaration)))

        :else
        nil))))

(defn- schema-declaration
  [form context]
  (when (and (seq? form)
             (= 3 (count form))
             (= 'seon.schema/register!
                (resolved-operation (first form) context))
             (qualified-keyword? (second form)))
    (let [schema-key (second form)]
      {:seon.schema/key schema-key
       :seon.schema/form (pr-str (nth form 2))})))

(defn- schema-unregister
  [form context]
  (when (and (seq? form)
             (= 2 (count form))
             (= 'seon.schema/unregister!
                (resolved-operation (first form) context))
             (qualified-keyword? (second form)))
    {::schema-unregister-key (second form)}))

(defn- namespace-unmap
  "Mark one semantically resolved top-level `ns-unmap` operation.

  Arguments stay unevaluated here. The evaluator derives removed intern
  identities from SCI's isolated before/after namespace state."
  [form context]
  (when (and (seq? form)
             (= 3 (count form))
             (= 'clojure.core/ns-unmap
                (resolved-operation (first form) context)))
    {::ns-unmap? true}))

(defn- declaration-facts
  [form namespace-name context source]
  (if (and (seq? form)
           (= 'clojure.core/ns
              (resolved-operation (first form) context)))
    (assoc
     (select-keys
      (namespace-info form context)
      [:seon.ns/name :seon.ns/doc :seon.ns/requires
       :seon.ns/aliases :seon.ns/refers ::refer-all-targets])
     :seon.ns/source source)
    (merge
     (declaration-occurrence form namespace-name context)
     (or
      (when-let [function (function-declaration form namespace-name context)]
        (assoc function :seon.fn/source source))
      (when-let [test (test-declaration form namespace-name context)]
        (assoc test :seon.test/source source))
      (schema-declaration form context)
      (schema-unregister form context)
      (namespace-unmap form context)))))

(defn- nested-executable-declarations
  "Declaration facts nested directly under an executable top-level `do`.

  The publication contract is one top-level form to at most one row, so these
  are refused loudly rather than silently omitted. Quote, function bodies, and
  other inert data are not traversed."
  [form namespace-name context]
  (when (and (seq? form) (= 'do (first form)))
    (into []
          (mapcat
           (fn [child]
             (if (and (seq? child) (= 'do (first child)))
               (nested-executable-declarations child namespace-name context)
               (when-let [facts
                          (declaration-facts child namespace-name context
                                             (pr-str child))]
                 [(dissoc facts
                          ::declaration-family
                          ::declaration-identity
                          ::declaration-refusal)]))))
          (rest form))))

(defn- namespace-changing-mention?
  "True when executing this top-level form can change the reading namespace."
  [form context]
  (when (seq? form)
    (let [operation (resolved-operation (first form) context)]
      (or (contains? #{'clojure.core/ns 'clojure.core/in-ns} operation)
          (and (= 'do (first form))
               (boolean
                (some #(namespace-changing-mention? % context)
                      (rest form))))))))

(defn- next-reading-context
  [{::keys [contexts] :as state} form]
  (let [operation (when (seq? form)
                    (resolved-operation (first form) state))]
    (cond
      (= 'clojure.core/ns operation)
      (if-let [info (namespace-info form state)]
        (let [context (select-keys info [::ns ::aliases ::refers])]
          (assoc context
                 ::publics (::publics state)
                 ::attribution? true
                 ::contexts (assoc contexts (::ns context) context)))
        (assoc state ::attribution? false))

      (= 'clojure.core/in-ns operation)
      (if-let [namespace-name (quoted-symbol (second form))]
        (merge {::ns namespace-name ::aliases {} ::refers {}}
               (get contexts namespace-name)
               {::attribution? true
                ::contexts contexts})
        (assoc state ::attribution? false))

      (= 'clojure.core/require operation)
      (let [bindings (literal-require-bindings form (::publics state))]
        (-> state
            (update ::aliases
                    into
                    (mapcat
                     (fn [{:keys [aliases]}]
                       (map (juxt :seon.ns.alias/local
                                  :seon.ns.alias/target-ns)
                            aliases))
                     bindings))
            (update ::refers
                    into
                    (mapcat
                     (fn [{:keys [refers]}]
                       (map
                        (fn [{:seon.ns.refer/keys
                              [local target-ns target-name]}]
                          [local (symbol (str target-ns)
                                         (str target-name))])
                        refers))
                     bindings))))

      ;; Attribution otherwise follows the last explicit valid namespace.
      ;; Evaluator namespace receipts remain the runtime truth for a change
      ;; only evaluation can see.
      (namespace-changing-mention? form state)
      (assoc state ::attribution? false)

      :else
      state)))

(defn- read-events
  [text reading-context]
  (let [ctx (sci/init {})
        source-reader (sci/source-reader text)
        starts (line-starts text)
        text-length (count text)
        initial-context
        (let [context (select-keys reading-context
                                   [::ns ::aliases ::refers ::publics])]
          (assoc context
                 ::attribution? true
                 ::contexts
                 (cond-> {}
                   (::ns context) (assoc (::ns context) context))))]
    (loop [state initial-context
           events []]
      (let [line (sci/get-line-number source-reader)
            column (sci/get-column-number source-reader)
            start (cursor-offset starts text-length line column)
            [form _]
            (sci/parse-next+string
             ctx source-reader
             (parse-options
              (merge reading-context
                     (select-keys state [::ns ::aliases ::refers ::publics]))))
            end-line (sci/get-line-number source-reader)
            end-column (sci/get-column-number source-reader)
            end (cursor-offset starts text-length end-line end-column)]
        (if (= eof form)
          (if (seq events)
            (assoc events (dec (count events))
                   ;; EOF is the terminal cursor. SCI may leave its reported
                   ;; column on the final one-character token, so the input
                   ;; length is its exact subs-compatible offset.
                   (assoc (peek events) ::end text-length))
            [])
          (let [consumed (subs text start end)
                source-start (+ start
                                (- (count consumed)
                                   (count (str/triml consumed))))
                source-end (+ start (count (str/trimr consumed)))
                source (subs text source-start source-end)
                [source-line source-column]
                (offset-cursor starts source-start)
                event
                (merge
                 {::form form
                  ::source source
                  ::start start
                  ::end end
                  ::source-start source-start
                  ::source-end source-end
                  ::line source-line
                  ::column source-column}
                 (when (and (::attribution? state) (::ns state))
                   {::ns (::ns state)})
                 (declaration-facts
                  form
                  (when (::attribution? state) (::ns state))
                  (select-keys state [::ns ::aliases ::refers ::publics])
                  source)
                 (when-let [nested
                            (seq
                             (nested-executable-declarations
                              form
                              (when (::attribution? state) (::ns state))
                              (select-keys state [::ns ::aliases ::refers])))]
                   {::nested-declarations nested}))]
            (recur (next-reading-context state form)
                   (conj events event))))))))

(defn read
  "Read accepted Clojure source into ordered read events or a flat error."
  {:malli/schema
   [:=> [:cat :map]
    [:or
     [:vector :map]
     [:map
      [:seon.error/kind :keyword]
      [:seon.error/message :string]
      [:seon.error/data :map]]]]}
  [{text ::text
    namespace-name ::ns
    aliases ::aliases
    refers ::refers
    publics ::publics
    features ::features
    tags ::tags
    defer-auto-resolve? ::defer-auto-resolve?
    max-source :seon.config.eval.result/max-source}]
  (let [reading-context
        {::ns (or namespace-name 'user)
         ::aliases (or aliases {})
         ::refers (or refers {})
         ::publics (or publics {})
         ::features (or features #{:clj})
         ::tags (or tags {})
         ::defer-auto-resolve? (boolean defer-auto-resolve?)
         ::max-source max-source}]
    (cond
      (not (string? text))
      (error-value
       ::unreadable
       "Reader text must be a string."
       {::text text
        ::phase "parse"})

      (or (not (integer? (::max-source reading-context)))
          (neg? (::max-source reading-context)))
      (error-value
       ::unreadable
       "Reader max-source must be a non-negative integer."
       {::text text
        :seon.config.eval.result/max-source (::max-source reading-context)
        ::phase "parse"})

      (> (count text) (::max-source reading-context))
      (error-value
       ::oversize
       "Clojure source exceeds the declared character bound."
       {::length (count text)
        :seon.config.eval.result/max-source (::max-source reading-context)})

      :else
      (try
        (read-events text reading-context)
        (catch #?(:clj Throwable :cljs :default) failure
          (let [data (ex-data failure)
                refused? (::refusal data)
                failure-line (or (:line data) (:row data))
                failure-column (or (:column data) (:col data))
                error-data
                (cond-> {::text text
                         ::phase (or (:phase data) "parse")}
                  failure-line (assoc ::line failure-line)
                  failure-column (assoc ::column failure-column)
                  refused? (assoc ::tag (::tag data)))]
            (error-value
             (if refused? ::refused-tag ::unreadable)
             (or (ex-message failure) (str failure))
             error-data)))))))
