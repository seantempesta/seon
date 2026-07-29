(ns seon.sci.reader
  "The one reader for accepted Clojure source."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [sci.core :as sci]))

(def ^:private default-max-chars
  ;; D5 in parse-primitives-plan-2026-07-29.md. A later config owner
  ;; supplies this value; S1 has no production callers or config seam.
  1048576)

(def ^:private eof ::eof)

(defn- error-value
  [kind message data]
  {:seon.error/kind kind
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
        (refusal-handler tag))))

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
  [{::keys [ns aliases refers features tags]}]
  {:eof eof
   :features features
   :auto-resolve
   (fn [alias]
     (if (= :current alias)
       ns
       (get aliases (if (keyword? alias)
                      (symbol (name alias))
                      alias))))
   :syntax-quote
   {:resolve-symbol (syntax-quote-resolver ns aliases refers)}
   ;; A function, not a map: Edamame consults it for built-ins too.
   :readers (accepted-reader tags)
   ;; Always explicit: never inherit SCI's dynamic *read-eval* policy.
   :read-eval reject-read-eval})

(defn- require-edge
  [require-spec]
  (cond
    (symbol? require-spec)
    {:seon.ns.require/target require-spec}

    (and (vector? require-spec)
         (symbol? (first require-spec)))
    (let [target (first require-spec)
          options (try
                    (apply hash-map (rest require-spec))
                    (catch #?(:clj Throwable :cljs :default) _
                      {}))
          alias (or (:as options) (:as-alias options))
          refers (:refer options)]
      (cond-> {:seon.ns.require/target target}
        (symbol? alias)
        (assoc :seon.ns.require/alias alias)

        (and (symbol? (:as-alias options))
             (nil? (:as options)))
        (assoc :seon.ns.require/as-alias? true)

        (sequential? refers)
        (assoc :seon.ns.require/refers (set refers))

        (= :all refers)
        (assoc :seon.ns.require/refer-all? true)))

    :else
    nil))

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
  [form]
  (when-let [namespace-name (symbol-name (second form))]
    (let [doc (when (string? (nth form 2 nil)) (nth form 2))
          requires (->> (namespace-clauses form)
                        (filter #(and (seq? %) (= :require (first %))))
                        (mapcat rest))
          edges (into #{} (keep require-edge) requires)
          aliases
          (into {}
                (keep (fn [{:seon.ns.require/keys [target alias]}]
                        (when alias [alias target])))
                edges)
          refers
          (into {}
                (mapcat
                 (fn [{:seon.ns.require/keys [target refers]}]
                   (map (fn [referred]
                          [referred
                           (symbol (str target) (str referred))])
                        refers)))
                edges)]
      (cond->
       {::ns namespace-name
        ::aliases aliases
        ::refers refers
        :seon.ns/name namespace-name
        :seon.ns/require-edges edges}
        doc (assoc :seon.ns/doc doc)))))

(defn- quoted-symbol
  [value]
  (when (and (seq? value)
             (= 'quote (first value))
             (= 2 (count value))
             (symbol? (second value)))
    (second value)))

(defn- function-declaration
  [form namespace-name]
  (when (seq? form)
    (let [operation (first form)
          function-name (second form)]
      (when (and (symbol? operation)
                 (#{"defn" "defn-"} (name operation))
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
            (boolean (or (= "defn-" (name operation))
                         (:private metadata)))}
            qualified (assoc :seon.fn/sym (str qualified))
            namespace-name
            (assoc :seon.fn/ns [:seon.ns/name namespace-name])
            doc (assoc :seon.fn/doc doc)
            schema (assoc :seon.fn/spec (pr-str schema))
            (contains? #{:io :compute} workload)
            (assoc :seon.fn/workload workload)))))))

(declare resolved-operation)

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
  "The operation symbol as the reading namespace's aliases resolve it.

  An UNQUALIFIED operator is deliberately not resolved through refers or
  the current namespace yet, so `seon.schema`'s own
  `(register! ::compiled-validator 'fn?)` does not become a schema row.
  Recognizing it exposed a real blocker: its static form is
  `(quote fn?)`, which is not a Malli form, and unlike
  `seon.sci.eval/program-row` the build indexer has no `malli-form?`
  admission gate. See
  `docs/seon/issues/eval-time-schema-and-test-rows-have-no-recurring-proof.md`."
  [operation {::keys [aliases refers]}]
  (when (symbol? operation)
    (if-let [operation-namespace (namespace operation)]
      (if-let [target (get aliases (symbol operation-namespace))]
        (symbol (str target) (name operation))
        operation)
      (get refers operation operation))))

(defn- schema-declaration
  [form context]
  (when (and (seq? form)
             (= 3 (count form))
             (= 'seon.schema/register!
                (resolved-operation (first form) context))
             (qualified-keyword? (second form)))
    (let [schema-key (second form)]
      {:seon.schema/key schema-key
       :seon.schema/form (pr-str (nth form 2))
       :seon.schema/ns
       [:seon.ns/name (symbol (namespace schema-key))]})))

(defn- declaration-facts
  [form namespace-name context source]
  (if (and (seq? form)
           (symbol? (first form))
           (= "ns" (name (first form))))
    (assoc
     (select-keys
      (namespace-info form)
      [:seon.ns/name :seon.ns/doc :seon.ns/require-edges])
     :seon.ns/source source)
    (or
     (when-let [function (function-declaration form namespace-name)]
       (assoc function :seon.fn/source source))
     (when-let [test (test-declaration form namespace-name context)]
       (assoc test :seon.test/source source))
     (schema-declaration form context))))

(defn- namespace-changing-mention?
  "True when `form` mentions a namespace-changing call in operator position.

  Only `ns` and `in-ns` change the current namespace. A form that mentions
  either one below its own head — `(do (in-ns 'other))` — may move the
  namespace in a way static reading cannot prove, so attribution becomes
  absent. This is a property derived from the form itself, never a list of
  operations believed safe: such a list dropped attribution at the first
  ordinary top-level call, so a `set!` or a predicate registration silently
  erased every declaration below it in the same file."
  [form]
  (and (coll? form)
       (boolean
        (or (and (seq? form)
                 (symbol? (first form))
                 (contains? #{"ns" "in-ns"} (name (first form))))
            (some namespace-changing-mention? form)))))

(defn- next-reading-context
  [{::keys [contexts] :as state} form]
  (let [operation (when (and (seq? form) (symbol? (first form)))
                    (name (first form)))]
    (cond
      (= "ns" operation)
      (if-let [info (namespace-info form)]
        (let [context (select-keys info [::ns ::aliases ::refers])]
          (assoc context
                 ::attribution? true
                 ::contexts (assoc contexts (::ns context) context)))
        (assoc state ::attribution? false))

      (= "in-ns" operation)
      (if-let [namespace-name (quoted-symbol (second form))]
        (merge {::ns namespace-name ::aliases {} ::refers {}}
               (get contexts namespace-name)
               {::attribution? true
                ::contexts contexts})
        (assoc state ::attribution? false))

      ;; Attribution otherwise follows the last explicit valid namespace.
      ;; Evaluator namespace receipts remain the runtime truth for a change
      ;; only evaluation can see.
      (namespace-changing-mention? form)
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
                                   [::ns ::aliases ::refers])]
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
                     (select-keys state [::ns ::aliases ::refers]))))
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
                  (select-keys state [::ns ::aliases ::refers])
                  source))]
            (recur (next-reading-context state form)
                   (conj events event))))))))

(defn read
  "Read accepted Clojure source into ordered read events or a flat error."
  {:malli/schema
   [:=> [:cat :map]
    [:or
     [:vector :map]
     [:map {:closed true}
      [:seon.error/kind :keyword]
      [:seon.error/message :string]
      [:seon.error/data :map]]]]}
  [{text ::text
    namespace-name ::ns
    aliases ::aliases
    refers ::refers
    features ::features
    tags ::tags
    max-chars ::max-chars}]
  (let [reading-context
        {::ns (or namespace-name 'user)
         ::aliases (or aliases {})
         ::refers (or refers {})
         ::features (or features #{:clj})
         ::tags (or tags {})
         ::max-chars (or max-chars default-max-chars)}]
    (cond
      (not (string? text))
      (error-value
       ::unreadable
       "Reader text must be a string."
       {::text text
        ::phase "parse"})

      (or (not (integer? (::max-chars reading-context)))
          (neg? (::max-chars reading-context)))
      (error-value
       ::unreadable
       "Reader max-chars must be a non-negative integer."
       {::text text
        ::max-chars (::max-chars reading-context)
        ::phase "parse"})

      (> (count text) (::max-chars reading-context))
      (error-value
       ::oversize
       "Clojure source exceeds the declared character bound."
       {::length (count text)
        ::max-chars (::max-chars reading-context)})

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
