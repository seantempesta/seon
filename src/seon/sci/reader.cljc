(ns seon.sci.reader
  "The one reader for accepted Clojure source."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            #?(:clj [clojure.tools.reader.reader-types :as rt]
               :cljs [cljs.tools.reader.reader-types :as rt])
            #?(:clj [clojure.spec.alpha :as s])
            #?(:clj [clojure.core.specs.alpha])
            [sci.core :as sci]))

(def ^:private eof ::eof)

(defn- error-value
  {:malli/schema [:=> [:cat :qualified-keyword :string :map :map]
                  :seon.sci.reader/failure]}
  [member message data observation]
  (merge
    {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
     :seon.error/layer :seon.sci.reader/source
     :seon.error/operation 'seon.sci.reader/error-value
     :seon.error/message message
     :seon.error/offending (::text data data)
     :seon.error/member member
     :seon.error/expected :seon.sci.reader/accepted-source
     :seon.error/data data}
    observation))

(defn- refusal-handler
  [tag]
  (fn [_]
    (throw
     (ex-info "Reader tag is not accepted; supply an explicitly declared reader."
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
  "Reader policy for one parse. With the evaluating SCI `ctx`, SCI resolves
  `::alias/k` and syntax-quote from the namespace table bound as `sci/ns`
  (sci/impl/parser.cljc:60-167); without one, the caller's supplied aliases
  and refers are the whole resolution world."
  [{::keys [ns aliases refers features tags defer-auto-resolve? ctx]}]
  (cond->
   {:eof eof
    :features features
    ;; A function, not a map: Edamame consults it for built-ins too.
    :readers (accepted-reader tags)
    ;; Always explicit: never inherit SCI's dynamic *read-eval* policy.
    :read-eval reject-read-eval}
    (nil? ctx)
    (assoc :auto-resolve
           (fn [alias]
             (if (= :current alias)
               ns
               (let [alias-name (if (keyword? alias)
                                  (symbol (name alias))
                                  alias)]
                 (or (get aliases alias-name)
                     (when defer-auto-resolve? alias-name)))))
           :syntax-quote
           {:resolve-symbol (syntax-quote-resolver ns aliases refers)})))

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
  "Bindings of one `[target options]` pair; `options` is conformed by
  `:clojure.core.specs.alpha/libspec`, so `:refer` is `[:all :all]` or
  `[:syms names]`."
  [[target options] publics]
  (let [alias (or (:as options) (:as-alias options))
        [refer-kind referred] (:refer options)
        renames (or (:rename options) {})
        referred-names (case refer-kind
                         :syms referred
                         :all (get publics target #{})
                         [])]
    {:aliases
     (cond-> #{}
       alias (conj (alias-binding alias target)))
     :refers
     (into #{}
           (map (fn [target-name]
                  (refer-binding (get renames target-name target-name)
                                 target
                                 target-name)))
           referred-names)
     :requires
     (cond-> #{}
       (nil? (:as-alias options)) (conj target))}))

(defn- libspec-pair
  [prefix [kind value]]
  (let [[lib options] (if (= :lib kind)
                        [value {}]
                        [(:lib value) (:options value)])]
    [(if prefix (symbol (str prefix "." lib)) lib) options]))

(defn- conformed
  "`value` conformed by Clojure's own `clojure.core/ns` grammar
  (`clojure.core.specs.alpha`), or nil when that grammar refuses it."
  [spec value]
  #?(:clj (let [result (s/conform spec value)]
            (when-not (s/invalid? result) result))
     :cljs nil))

(defn- require-pairs
  "`[target options]` for each lib of one conformed `:require` clause.
  The spec owns prefix lists, flags and option syntax."
  [{:keys [body]}]
  (mapcat (fn [[kind value]]
            (case kind
              :libspec [(libspec-pair nil value)]
              :prefix-list (map #(libspec-pair (:prefix value) %)
                                (:libspecs value))
              :flag []))
          body))

(defn- namespace-info
  [form context]
  (when-let [{namespace-name :ns-name doc :docstring :keys [ns-clauses]}
             (conformed :clojure.core.specs.alpha/ns-form (rest form))]
    (let [bindings (->> ns-clauses
                        (filter #(= :require (first %)))
                        (mapcat #(require-pairs (second %)))
                        (map #(require-bindings % (::publics context))))
          alias-rows (into #{} (mapcat :aliases) bindings)
          refer-rows (into #{} (mapcat :refers) bindings)
          required-targets (into #{} (mapcat :requires) bindings)
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
        :seon.ns/refers refer-rows}
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
  (map #(require-bindings % publics)
       (require-pairs
        (conformed :clojure.core.specs.alpha/ns-require
                   (cons :require (map (fn [arg] (or (quoted-value arg) arg))
                                       (rest form)))))))

(declare resolved-operation)

(defn- function-declaration
  {:seon/tasks #{"5f48e860a402"}}
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
            qualified (assoc :seon.fn/sym qualified)
            namespace-name
            (assoc :seon.fn/ns [:seon.ns/name namespace-name])
            doc (assoc :seon.fn/doc doc)
            (true? (:seon.fn/internal? metadata))
            (assoc :seon.fn/internal? true)
            (:seon.fn/doc-order metadata)
            (assoc :seon.fn/doc-order (:seon.fn/doc-order metadata))
            schema (assoc :seon.fn/spec (pr-str schema))
            (contains? #{:io :compute} workload)
            (assoc :seon.fn/workload workload)))))))

(defn- test-declaration
  {:seon/tasks #{"5f48e860a402"}}
  [form namespace-name context]
  (when (and (seq? form)
             (= 'clojure.test/deftest
                (resolved-operation (first form) context))
             (symbol? (second form)))
    (when-let [qualified (qualified-symbol namespace-name (second form))]
      {:seon.test/sym qualified
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
       :seon.ns/aliases :seon.ns/refers])
     :seon.ns/source source)
    (or
     (when-let [function (function-declaration form namespace-name context)]
       (assoc function :seon.fn/source source))
     (when-let [test (test-declaration form namespace-name context)]
       (assoc test :seon.test/source source))
     (when-let [declaration (schema-declaration form context)]
       (assoc declaration :seon.schema/ns [:seon.ns/name namespace-name]))
     (schema-unregister form context)
     (namespace-unmap form context))))

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

(defn fence-line?
  "True for a Markdown backtick or tilde delimiter line, with an optional tag."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [line]
  (let [line (str/triml line)]
    (or (str/starts-with? line "```")
        (str/starts-with? line "~~~"))))

(defn- advance-to!
  "Advance `source-reader` over `text` to character offset `end`; its cursor
  owns line and column, so coordinates stay those of `text`. Answers `end`."
  [source-reader text starts end]
  (loop []
    (when (< (cursor-offset starts (count text)
                           (sci/get-line-number source-reader)
                           (sci/get-column-number source-reader)) end)
      (when (rt/read-char source-reader)
        (recur))))
  end)

(defn- skip-fences!
  "Skip delimiter lines only between top-level forms, never inside a literal.
  SCI owns form boundaries; its source reader owns cursor advancement."
  [source-reader text starts start]
  (let [end
        (loop [offset start fence-end start]
          (let [character (get text offset)
                line-end (when character (.indexOf text "\n" offset))
                line-end (if (or (nil? line-end) (neg? line-end))
                           (count text) line-end)]
            (cond
              (nil? character) fence-end
              (or (= \, character) (str/blank? (str character)))
              (recur (inc offset) fence-end)
              (= \; character) (recur line-end fence-end)
              (and (contains? #{\` \~} character)
                   (str/blank? (subs text (inc (.lastIndexOf text "\n" (dec offset))) offset))
                   (fence-line? (subs text offset line-end)))
              (recur line-end line-end)
              :else fence-end)))]
    (advance-to! source-reader text starts end)))

(defn- read-events
  "Read `text`'s forms from character offset `from` into events. Recovery
  resumes the same text at a later offset, so every event, form and nested
  form carries the coordinates of `text` itself."
  {:seon/tasks #{"430c53fde483"}}
  [text reading-context from]
  (let [ctx (or (::ctx reading-context) (sci/init {}))
        source-reader (sci/source-reader text)
        starts (line-starts text)
        text-length (count text)
        _ (advance-to! source-reader text starts from)
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
            source-offset (skip-fences! source-reader text starts start)
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
                          ::failure-start source-offset
                          ::reading-state state})
                  failure))))
            end (cursor-offset starts text-length
                               (sci/get-line-number source-reader)
                               (sci/get-column-number source-reader))]
        (if (= eof form)
          (if (seq events)
            (assoc events (dec (count events))
                   ;; EOF is the terminal cursor. SCI may leave its reported
                   ;; column on the final one-character token, so the input
                   ;; length is its exact subs-compatible offset.
                   (assoc (peek events) ::end text-length))
            [])
          (let [consumed (subs text source-offset end)
                source-start (+ source-offset
                                (- (count consumed)
                                   (count (str/triml consumed))))
                source-end (+ source-offset (count (str/trimr consumed)))
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
                  source))
                event
                (cond-> event
                  (and (map? form)
                       (some #(and (keyword? %)
                                   (= "seon.repl" (namespace %))) (keys form)))
                  (assoc ::error
                         (error-value
                          ::response-keys
                          "You wrote a response. Only the REPL writes responses; send forms and wait."
                          {::text source ::line source-line
                           ::column source-column ::phase "reply"}
                          {::response-keys (into #{} (filter #(and (keyword? %) (= "seon.repl" (namespace %)))) (keys form))})))]
            (recur (next-reading-context state form)
                   (conj events event))))))))

(declare cause-data)

(defn- reader-error
  ([text failure]
   (reader-error text failure nil))
  ([text failure classification]
  (let [data (ex-data failure)
        refused? (::refusal data)
        failure-line (or (:line data) (:row data))
        failure-column (or (:column data) (:col data))
        parser-data (cause-data failure)
        containers (:edamame/containers parser-data)
        call-container (some #(when (= "(" (:edamame/opened-delimiter %)) %) containers)
        call (first (:edamame/elements call-container))
        error-data
        (cond-> {::text text
                 ::phase (or (:phase data) "parse")}
          (:edamame/token parser-data) (assoc ::token (:edamame/token parser-data)
                                               ::token-kind (:edamame/token-kind parser-data))
          (seq containers) (assoc ::containers containers)
          (symbol? call) (assoc ::call (resolved-operation call (::reading-state data))
                                ::argument-index (dec (count (:edamame/elements call-container))))
          failure-line (assoc ::line failure-line)
          failure-column (assoc ::column failure-column)
          classification (merge classification)
          refused? (assoc ::tag (::tag data)))]
    (error-value
     (if refused? ::tag ::form)
     (or (ex-message failure) "Source could not be read; correct the supplied reader form.")
     error-data
     (if refused?
       {::refused-token (::tag data)}
       {::unreadable-member ::form})))))

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
          (:edamame/token data) :invalid-token
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
     ::prose-span? (and (= :symbol (:edamame/token-kind data))
                        (empty? (:edamame/containers data)))
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
  [text failure-start {::keys [recovery-kind error-kind prose-span?]}]
  (let [text-length (count text)
        point
        (cond
          (and (= :invalid-token error-kind) (not prose-span?)
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
  "Resynchronize after an unreadable span at a heuristic column-zero anchor
  (not a proof of top-level structure), retaining the span as one error event.
  Emitted spans are ordered and recovery strictly advances."
  [text reading-context failure]
  (loop [offset 0
         state reading-context
         pending failure
         recovered []]
    (let [data (ex-data pending)
          partial (::partial-events data)
          absolute-start (long (or (::failure-start data) offset))
          classification (parse-classification text absolute-start pending)
          absolute-end (recovery-point text absolute-start classification)
          ;; The failed read's namespace state, over the caller's reading
          ;; policy (features, tags, deferral, ctx), which a resumed read
          ;; must keep: ::reading-state carries only ns, aliases and refers.
          state-after (merge reading-context (or (::reading-state data) state))
          source (subs text absolute-start absolute-end)
          recovered (into recovered partial)
          ;; An unmatched closer is a reader error, as in Clojure
          ;; (LispReader's UnmatchedDelimiterReader): kept in order after
          ;; the forms before it, never silently repaired away. Only a
          ;; whitespace-only recovery span holds nothing to report.
          recovered (if (str/blank? source)
                      recovered
                      (conj recovered
                            (error-event text absolute-start absolute-end
                                         state-after pending classification)))]
      (if (= absolute-end (count text))
        recovered
        (let [attempt
              (try
                {::events (read-events text state-after absolute-end)}
                (catch #?(:clj Throwable :cljs :default) next-failure
                  {::failure next-failure}))]
          (if-let [events (::events attempt)]
            (into recovered events)
            (recur absolute-end state-after (::failure attempt)
                   recovered)))))))

(defn read
  "Read accepted Clojure source into ordered read events or a flat error.

  `::ctx` is the evaluating SCI context, with the target namespace bound as
  `sci/ns` by the caller: SCI then resolves auto-resolved keywords and
  syntax-quote from that context. Without it, resolution uses only the
  supplied `::ns`, `::aliases` and `::refers`."
  {:malli/schema
   [:=> [:cat :map]
    [:or
     [:vector :map]
     :seon.sci.reader/failure]]}
  [{text ::text
    namespace-name ::ns
    aliases ::aliases
    refers ::refers
    publics ::publics
    features ::features
    tags ::tags
    defer-auto-resolve? ::defer-auto-resolve?
    ctx ::ctx
    max-source :seon.config.eval.result/max-source}]
  (let [reading-context
        (cond->
         {::ns (or namespace-name 'user)
          ::aliases (or aliases {})
          ::refers (or refers {})
          ::publics (or publics {})
          ::features (or features #{:clj})
          ::tags (or tags {})
          ::defer-auto-resolve? (boolean defer-auto-resolve?)
          ::max-source max-source}
          ctx (assoc ::ctx ctx))]
    (cond
      (not (string? text))
      (error-value
       ::text
       "Reader text must be a string; supply Clojure source text."
       {::text text ::phase "parse"}
       {::unreadable-member ::text})

      (or (not (integer? (::max-source reading-context)))
          (neg? (::max-source reading-context)))
      (error-value
       :seon.config.eval.result/max-source
       "Reader max-source must be a non-negative integer; supply its declared bound."
       {::text text
        :seon.config.eval.result/max-source (::max-source reading-context)
        ::phase "parse"}
       {::unreadable-member :seon.config.eval.result/max-source})

      (> (count text) (::max-source reading-context))
      (error-value
       :seon.config.eval.result/max-source
       "Clojure source exceeds the declared character bound; submit a smaller source."
       ;; the over-bound text is referenced by its length, never copied: the
       ;; caller holds it, and it is by definition too large to carry again
       {::length (count text)
        :seon.config.eval.result/max-source (::max-source reading-context)}
       {::length (count text) ::source-bound (::max-source reading-context)})

      :else
      (try
        (read-events text reading-context 0)
        (catch #?(:clj Throwable :cljs :default) failure
          (let [failure-data (ex-data failure)
                refused? (::refusal failure-data)]
            (if refused?
              (reader-error text failure)
              (recovering-events text reading-context failure))))))))
