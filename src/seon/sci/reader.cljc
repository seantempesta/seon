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
            (try
              (sci/parse-next+string
               ctx source-reader
               (parse-options
                (merge reading-context
                       (select-keys state [::ns ::aliases ::refers ::publics]))))
              (catch #?(:clj Throwable :cljs :default) failure
                (throw
                 (ex-info
                  (or (ex-message failure) (str failure))
                  (merge (ex-data failure)
                         {::partial-events events
                          ::failure-start start
                          ::reading-state state})
                  failure))))
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

(defn- reader-error
  ([text failure]
   (reader-error text failure nil))
  ([text failure classification]
  (let [data (ex-data failure)
        refused? (::refusal data)
        failure-line (or (:line data) (:row data))
        failure-column (or (:column data) (:col data))
        error-data
        (cond-> {::text text
                 ::phase (or (:phase data) "parse")}
          failure-line (assoc ::line failure-line)
          failure-column (assoc ::column failure-column)
          classification (merge classification)
          refused? (assoc ::tag (::tag data)))]
    (error-value
     (if refused? ::refused-tag ::unreadable)
     (or (ex-message failure) (str failure))
     error-data))))

(defn- shift-event
  [event offset line-offset column-offset]
  (let [shift-position (fn [position]
                         (when (integer? position) (+ offset position)))
        form (::form event)
        form-meta (meta form)
        first-line? (= 1 (::line event))]
    (cond-> (-> event
                (update ::start shift-position)
                (update ::end shift-position)
                (update ::source-start shift-position)
                (update ::source-end shift-position)
                (update ::line #(+ line-offset %)))
      first-line? (update ::column #(+ column-offset %))
      form-meta
      (assoc ::form
             (with-meta form
               (cond-> form-meta
                 (:line form-meta) (update :line #(+ line-offset %))
                 (and (= 1 (:line form-meta)) (:column form-meta))
                 (update :column #(+ column-offset %))))))))

(defn- cause-data
  "The Edamame fact map beneath SCI's parse wrapper, when one exists."
  [failure]
  (some (fn [cause]
          (let [data (ex-data cause)]
            (when (= :edamame/error (:type data)) data)))
        (take-while some? (iterate ex-cause failure))))

(defn- closer?
  [character]
  (contains? #{\) \] \}} character))

(defn- parse-classification
  "Classify from Edamame's structured delimiter/cursor facts.

  Edamame currently throws a bare IllegalArgumentException for invalid
  metadata values. That dependency hole is retained as `:other`; recovery
  never guesses a class from exception prose."
  [text failure-start failure]
  (let [data (or (cause-data failure) (ex-data failure))
        starts (line-starts text)
        line (or (:line data) (:row data) 1)
        column (or (:column data) (:col data) 1)
        failure-offset (cursor-offset starts (count text) line column)
        opened (get data :edamame/opened-delimiter ::absent)
        expected (get data :edamame/expected-delimiter ::absent)
        at-offset (get text failure-offset)
        previous (when (pos? failure-offset) (get text (dec failure-offset)))
        delimiter-facts? (and (not= ::absent opened)
                              (not= ::absent expected))
        mismatched-closer
        (or (and delimiter-facts?
                 (closer? at-offset)
                 (not= (str at-offset) expected))
            (and delimiter-facts?
                 (= failure-offset (count text))
                 (closer? previous)
                 (not= (str previous) expected)))
        unclosed? (and (not= ::absent opened)
                       (not (str/blank? (str opened)))
                       (not mismatched-closer))
        detail
        (cond
          unclosed? :unclosed
          (or (= "" opened) mismatched-closer) :stray-closer
          (and (map? data)
               (= failure-offset failure-start)
               (= \{ (get text failure-start))) :odd-map
          (and (cause-data failure)
               (> failure-offset failure-start)) :invalid-token
          #?(:clj (some #(instance? IllegalArgumentException %)
                        (take-while some? (iterate ex-cause failure)))
             :cljs false) :bad-metadata
          :else :other)]
    {::recovery-kind (if unclosed? :unclosed :localized)
     ::error-kind detail
     ::failure-offset failure-offset}))

(defn- line-start-anchor
  [text floor accepted]
  (some (fn [offset]
          (when (and (> offset floor)
                     (accepted (get text offset)))
            offset))
        (line-starts text)))

(defn- preceding-comment-block
  "Include the contiguous column-zero comment block narrating an anchor."
  [text floor anchor]
  (loop [candidate anchor]
    (let [previous-newline (when (pos? candidate)
                             (.lastIndexOf text "\n" (- candidate 2)))
          previous-start (if (nil? previous-newline)
                           0
                           (inc previous-newline))]
      (if (and (>= previous-start floor)
               (< previous-start candidate)
               (= \; (get text previous-start)))
        (recur previous-start)
        candidate))))

(defn- token-end
  [text start]
  (loop [offset start]
    (let [character (get text offset)]
      (if (or (nil? character)
              (str/blank? (str character))
              (= \, character)
              (contains? #{\; \" \( \[ \{} character))
        (max (inc start) offset)
        (recur (inc offset))))))

(defn- recovery-point
  [text failure-start {::keys [recovery-kind error-kind]}]
  (let [text-length (count text)
        point
        (cond
          (and (= :invalid-token error-kind)
               (not= \( (get text failure-start)))
          (token-end text failure-start)

          (= :unclosed recovery-kind)
          (when-let [anchor (line-start-anchor text failure-start #{\(})]
            (preceding-comment-block text failure-start anchor))

          :else
          (line-start-anchor text failure-start #{\( \;}))
        point (or point text-length)]
    ;; Recovery is a terminating operation, not a hopeful retry.
    (if (> point failure-start) point text-length)))

(defn- closer-only?
  [source]
  (and (seq source)
       (every? #(or (str/blank? (str %))
                    (closer? %))
               source)))

(defn- error-event
  [text start end state failure classification]
  (let [[line column] (offset-cursor (line-starts text) start)
        source (subs text start end)]
    (cond-> {::form (with-meta '() {:line line :column column})
             ::source source
             ::start start
             ::end end
             ::source-start start
             ::source-end end
             ::line line
             ::column column
             ::error (reader-error text failure classification)}
      (::ns state) (assoc ::ns (::ns state)))))

(defn- recovering-events
  "Recover only at proven top-level anchors or past one invalid token."
  [text reading-context failure]
  (let [starts (line-starts text)]
    (loop [offset 0
           state reading-context
           pending failure
           recovered []]
      (let [data (ex-data pending)
            [base-line base-column] (offset-cursor starts offset)
            line-offset (dec base-line)
            column-offset (dec base-column)
            partial (mapv #(shift-event % offset line-offset column-offset)
                          (::partial-events data))
            local-start (long (or (::failure-start data) 0))
            absolute-start (+ offset local-start)
            local-text (subs text offset)
            classification (parse-classification local-text local-start pending)
            local-end (recovery-point local-text local-start classification)
            absolute-end (+ offset local-end)
            classification (update classification ::failure-offset + offset)
            state-after (or (::reading-state data) state)
            source (subs text absolute-start absolute-end)
            recovered (into recovered partial)
            recovered (if (closer-only? source)
                        recovered
                        (conj recovered
                              (error-event text absolute-start absolute-end
                                           state-after pending classification)))]
        (if (= absolute-end (count text))
          recovered
          (let [suffix (subs text absolute-end)
                [next-line _] (offset-cursor starts absolute-end)
                attempt
                (try
                  {::events (read-events suffix state-after)}
                  (catch #?(:clj Throwable :cljs :default) next-failure
                    {::failure next-failure}))]
            (if-let [events (::events attempt)]
              (into recovered
                    (map #(shift-event % absolute-end (dec next-line)
                                       (dec (second (offset-cursor starts
                                                                  absolute-end)))))
                    events)
              (recur absolute-end state-after (::failure attempt)
                     recovered))))))))

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
          (let [failure-data (ex-data failure)
                refused? (::refusal failure-data)]
            (if refused?
              (reader-error text failure)
              (recovering-events text reading-context failure))))))))
