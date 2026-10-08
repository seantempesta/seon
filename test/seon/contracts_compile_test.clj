(ns seon.contracts-compile-test
  "Every declared `:malli/schema` compiles against the packaged projection.

  The defect class (three from-zero breaks on 2026-09-22/23): a contract a
  warm JVM accepts but a fresh store refuses at its first publication —
  `[:fn #(instance? Throwable %)]`, a bare `:vector`, `[:fn #(instance?
  ValueKey %)]`. Publication reads each contract as the indexer stored it
  (`seon.fn.analyzer` qualifies its symbols, `schema/canonical-definition` canonicalizes
  it, the row carries its `pr-str` and `seon.fn/add-contract-facts` reads it
  back with `edn/read-string`), then compiles it inside
  `schema/build-projection`'s registry: `schema/compilable-form` binds named
  predicates to callables and refuses anything else, and `m/function-schema`
  compiles the prepared form against the declaration registry.

  [[check]] runs exactly that chain over source TEXT, read statically with
  Clojure's reader, against the projection built from `seon.schema.edn/packaged-forms`
  (the resources on disk now). It loads no namespace it reads. `bin/seon-hook`
  calls it in the live root for the files a hook event changes; the deftests
  run it over all of `src/` and `test/` and over the three historical forms.

  Given the file as it was (`::baselines`), [[check]] also refuses every
  `defn`/`defn-` the edit adds or changes without a complete contract: no
  `:malli/schema`, or an `:any` standing in for a data shape. Unchanged
  functions are not judged (owner 2026-09-27: agents editing files specify
  every function they touch; REPL agents are warned, merge refuses)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [clojure.walk :as walk]
            [malli.core :as m]
            [seon.cache :as cache]
            [seon.error.refusal :as error.refusal]
            [seon.fn.analyzer :as analyzer]
            [seon.schema :as schema]
            [seon.schema.datahike]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Static reading: the contract as the indexer stores it
;;; ---------------------------------------------------------------------------

(def ^:private attr-map-heads
  "Definition heads whose optional attribute map merges into the Var's meta."
  #{'defn 'defn- 'defmacro})

(defn- definition-head?
  "True for a list naming a `def`-family form over a symbol."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A top-level source form is arbitrary read data.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [form]
  (boolean
   (and (seq? form)
        (symbol? (first form))
        (nil? (namespace (first form)))
        (.startsWith ^String (name (first form)) "def")
        (symbol? (second form)))))

(defn- declared-schema
  "The `:malli/schema` one definition form declares, or nil when none.

  The name symbol's metadata and, for `defn`-family heads, the attribute map
  after an optional docstring are the two places Clojure merges into the
  Var's metadata, which is what clj-kondo's `:meta` analysis reports to the
  indexer."
  {:malli/schema [:=> [:cat [:sequential {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form's members are arbitrary read data.", :gen/elements [[]]} [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form's members are arbitrary read data.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:or :nil [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An authored contract is arbitrary data until it compiles; that is the question asked.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [form]
  (let [[head definition-name & more] form
        more (if (string? (first more)) (rest more) more)
        attr-map (when (and (attr-map-heads head) (map? (first more)))
                   (first more))]
    (or (get attr-map :malli/schema)
        (get (meta definition-name) :malli/schema))))

(defn- definition-forms
  "Every definition form in `forms`, descending into top-level `do`."
  {:malli/schema [:=> [:cat [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:vector [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form's members are arbitrary read data.", :gen/elements [nil false 0 "" :k [] {}]}]]]]}
  [forms]
  (into []
        (mapcat (fn [form]
                  (cond
                    (and (seq? form) (= 'do (first form)))
                    (definition-forms (rest form))

                    (definition-head? form) [form]

                    :else [])))
        forms))

(defn- require-aliases
  "The `:as` and `:as-alias` aliases the `ns` form among `forms` requires, alias to namespace."
  {:malli/schema [:=> [:cat [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]] [:map-of :symbol :symbol]]}
  [forms]
  (into {}
        (for [form forms
              :when (and (seq? form) (= 'ns (first form)))
              clause (drop 2 form)
              :when (and (seq? clause) (= :require (first clause)))
              spec (rest clause)
              :when (and (vector? spec) (symbol? (first spec)))
              [k v] (partition 2 (rest spec))
              :when (and (#{:as :as-alias} k) (symbol? v))]
          [v (first spec)])))

(defn- read-source
  "The JVM source's forms and positions, cached by text. Clojure reads without
  evaluation, resolving namespaces and aliases from the source's own ns form."
  {:malli/schema [:=> [:cat :string] [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [text]
  (cache/lookup ::read-source text (fn [_] (* 24 (count text)))
                (fn []
                  (let [reader (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))
                        eof (Object.)
                        options {:eof eof :read-cond :allow :features #{:clj}}]
                    (binding [*read-eval* false
                              *data-readers* {}
                              *default-data-reader-fn* tagged-literal]
                      (loop [forms [] namespace-name 'user aliases {}]
                        (let [form (binding [*reader-resolver* (reify clojure.lang.LispReader$Resolver
                                                                 (currentNS [_] namespace-name)
                                                                 (resolveClass [_ _] nil)
                                                                 (resolveAlias [_ symbol-alias] (get aliases symbol-alias))
                                                                 (resolveVar [_ _] nil))]
                                     (read options reader))]
                          (cond
                            (identical? eof form) forms

                            (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
                            (recur (conj forms (vary-meta form assoc :end-line (.getLineNumber reader) :end-column (.getColumnNumber reader)))
                                   (second form) (require-aliases [form]))

                            :else (recur (conj forms form) namespace-name aliases)))))))))

(def ^:private no-namespace-context
  "The context of a text with no `ns` form: nothing aliased or referred."
  {:requires #{} :aliases {} :refers {} :imports {}})

(defn- namespace-name-in
  "The name of the `ns` form among read `forms`, or `user` when there is none."
  {:malli/schema [:=> [:cat [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]] :symbol]}
  [forms]
  (or (some #(when (and (seq? %) (= 'ns (first %))) (second %)) forms) 'user))

(defn- through-ns-form
  "The text up to the end of the `ns` form among its read `forms`, or nil.

  A prefix keeps every row and column of the form, and the namespace context
  reads only the form's clauses, so analysing it answers as the whole file
  does."
  {:malli/schema [:=> [:cat :string [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]] [:maybe :string]]}
  [text forms]
  (when-some [ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))]
    (let [{:keys [end-line end-column]} (meta ns-form)
          line-start (loop [offset 0 row 1]
                       (if (= row end-line) offset (recur (inc (.indexOf ^String text "\n" (int offset))) (inc row))))]
      (subs text 0 (+ line-start (dec end-column))))))

(defn- namespace-contexts
  "The indexer's own alias/refer context for each read text's `ns` form, by path.

  `texts` maps each path to its text and read forms. One
  `seon.fn.analyzer/analyze` over each text through its `ns` form, keyed by
  canonical path (the analysis mirror resolves only canonical paths), then
  `seon.fn.analyzer/namespace-context` on each namespace definition: symbol
  qualification is the indexer's rule, not a copy of it. The analysis writes a
  private clj-kondo cache, so a reconstructed text never answers for the
  checkout's namespace of the same name in the shared cache. A text with no
  `ns` form, or whose contracts name no symbol, analyses nothing."
  {:malli/schema [:=> [:cat [:map-of :string [:map [::text :string] [::forms [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]]]]] [:map-of :string :map]]}
  [texts]
  (let [paths (into {} (map (fn [path] [(.getCanonicalPath (io/file path)) path]))
                    (keys texts))
        ;; Only a symbol in a contract is qualified through the context.
        names-symbol? (fn [forms] (some #(some symbol? (tree-seq coll? seq (declared-schema %))) (definition-forms forms)))
        prefixes (into {} (keep (fn [[path {::keys [text forms]}]]
                                  (when-some [prefix (when (names-symbol? forms) (through-ns-form text forms))]
                                    [(.getCanonicalPath (io/file path)) prefix])))
                       texts)]
    (if (empty? prefixes)
      {}
      (let [cache (.toFile (java.nio.file.Files/createTempDirectory
                            "contracts-compile-kondo"
                            (make-array java.nio.file.attribute.FileAttribute 0)))]
        (try
          (into {}
                (map (fn [entry]
                       [(get paths (:seon.fn.analyzer/filename entry))
                        (analyzer/namespace-context entry)]))
                (:seon.fn.analyzer/namespace-definitions
                 (analyzer/analyze
                  {:seon.fn.analyzer/sources prefixes
                   :seon.fn.analyzer/cache-root (.getPath cache)})))
          (finally (@#'seon.fn.analyzer/delete-tree! cache)))))))

(defn- read-texts
  "Each text by path with its top-level forms, each text read once."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:map-of :string [:map [::text :string] [::forms [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]]]]]}
  [texts]
  (update-vals texts (fn [text] {::text text ::forms (read-source text)})))

(defn declared-contracts
  "Every `:malli/schema` declared in one source's read `forms`, as the indexer stores it.

  Each entry names its file, row, column and definition; `::stored` is the
  contract after the indexer's qualification, canonicalization and EDN round
  trip, or `::read-refusal` when that round trip itself refuses."
  {:malli/schema [:=> [:cat :string [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]] :map] [:vector :map]]}
  [path forms context]
  (let [namespace-name (namespace-name-in forms)]
    (into []
          (keep (fn [form]
                  (when-some [authored (declared-schema form)]
                    (let [{row :line col :column} (meta form)
                          entry {::path path ::row row ::col col
                                 ::symbol (symbol (str namespace-name)
                                                  (name (second form)))
                                 ::authored authored}]
                      (try
                        (assoc entry ::stored
                               (edn/read-string
                                (pr-str (schema/canonical-definition
                                         (analyzer/qualify-schema-symbols
                                          authored context)))))
                        (catch Exception error
                          (assoc entry ::read-refusal
                                 (error.refusal/chain error))))))))
          (definition-forms forms))))

;;; ---------------------------------------------------------------------------
;;; Completeness: every function an edit adds or changes is specified

(defn- generated-symbol?
  "True for a symbol the reader generates: a `#()` parameter (`p1__12#`) or a
  syntax-quote gensym (`x__12__auto__`), numbered afresh by every read."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Any source node.", :gen/elements [nil 0 :k [] {}]}]] :boolean]}
  [node]
  (boolean (and (simple-symbol? node)
                (let [text (name node)]
                  (and (str/includes? text "__")
                       (or (str/ends-with? text "#") (str/ends-with? text "__auto__")))))))

(defn- same-definition?
  "True when two read definition forms are the same source. A regex literal
  reads as a `java.util.regex.Pattern`, whose equality is identity, so patterns
  compare by their text; reader-generated symbols compare by the `x#` they were
  generated from."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form is arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]
                       [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form is arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]
                  :boolean]}
  [a b]
  (let [canonical (fn [form]
                    (walk/postwalk #(cond (instance? java.util.regex.Pattern %) (list 're-pattern (str %))
                                          (generated-symbol? %) (symbol (str (subs (name %) 0 (str/index-of (name %) "__")) "#"))
                                          :else %)
                                   form))]
    (or (= a b) (= (canonical a) (canonical b)))))
;;; ---------------------------------------------------------------------------

(def ^:private function-heads
  "The definition heads the completeness rule judges (owner 2026-09-27)."
  #{'defn 'defn-})

(defn- exempt-any?
  "True for `[:any {...}]` declaring a justified polymorphic boundary."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An authored contract node is arbitrary data until it compiles.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [node]
  (boolean (and (vector? node) (= :any (first node)) (map? (second node))
                (:seon.schema.admission/exemption (second node)))))

(defn- stand-in-any?
  "True when `contract` names `:any` outside a declared exemption."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An authored contract is arbitrary data until it compiles.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [contract]
  (boolean (some #{:any} (tree-seq #(and (coll? %) (not (exempt-any? %))) seq contract))))

(def ^:private shape-advice
  (str "Declare each data shape it takes and returns as a registered schema first "
       "(resources/seon/schemas/<namespace>.edn), then name those shapes: "
       "{:malli/schema [:=> [:cat :my.ns/request] :my.ns/result]}. A genuinely "
       "polymorphic boundary says so: [:any {:seon.schema.admission/exemption "
       ":seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason \"...\"}]."))

(defn incomplete-changes
  "Findings for every `defn`/`defn-` in `forms` that `baseline` lacks or
  defines differently and whose contract is missing or uses `:any` as a
  stand-in, both given as read top-level forms. Forms compare as read data,
  so moving or reformatting a function changes nothing; an empty baseline is
  a new file."
  {:malli/schema [:=> [:cat :string [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]] [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]] [:vector :map]]}
  [path forms baseline]
  (let [namespace-name (namespace-name-in forms)
        before (into {} (map (juxt second identity)) (definition-forms baseline))]
    (into []
          (keep (fn [form]
                  (when (and (function-heads (first form))
                             (not (same-definition? form (get before (second form)))))
                    (let [authored (declared-schema form)
                          {row :line col :column} (meta form)
                          function-symbol (symbol (str namespace-name) (name (second form)))
                          change (if (find before (second form)) "changes" "adds")
                          refusal (fn [problem]
                                    {:filename path :row row :col col :level :error
                                     :type :incomplete-contract
                                     :message (str "This edit " change " " function-symbol
                                                   " (" (first form) ") " problem " "
                                                   shape-advice)})]
                      (cond
                        (nil? authored) (refusal "without a :malli/schema.")
                        (stand-in-any? authored)
                        (refusal (str "with :any standing in for a data shape: "
                                      (pr-str authored) ".")))))))
          (definition-forms forms))))

(defn- read-file
  "The UTF-8 text of the file at `path`, read into one exactly sized string."
  {:malli/schema [:=> [:cat :string] :string]}
  [path]
  (java.nio.file.Files/readString (.toPath (io/file path))))

(defn source-files
  "Every JVM source file under `roots`, relative to the working directory."
  {:malli/schema [:=> [:cat [:sequential :string]] [:vector :string]]}
  [roots]
  (into []
        (comp (mapcat #(file-seq (io/file %)))
              (filter #(.isFile ^java.io.File %))
              (map #(.getPath ^java.io.File %))
              (filter #(or (.endsWith ^String % ".clj")
                           (.endsWith ^String % ".cljc"))))
        roots))

;;; ---------------------------------------------------------------------------
;;; Containment: nothing crashes (AGENTS.md "Nothing crashes")
;;; ---------------------------------------------------------------------------
;;
;; A `throw` is internal control flow only when its own function converts it
;; (a `try` whose catch clause catches it and does not rethrow), when it
;; resurfaces a caught cause unchanged (`(throw e)` of a catch binding: the
;; producer is whoever threw `e`), or when every call of the private function
;; that holds it sits in such a `try` in the same file (its declared caller
;; converts). Any other throw reaches its caller, and every program function
;; is callable by an agent (AGENTS.md "Custody is the default world"), so its
;; function must declare the failure as an alternative of its output union: a
;; schema that is `:seon.error/base` or an `[:and :seon.error/base ...]`
;; extension, reached through `:or`, `:orn`, `:multi` or `:maybe`
;; (reference-code/malli 43c6dca8, src/malli/core.cljc:996, :1044, :1861,
;; :1810, `:and` :825).

(def ^:private converting-catch-classes
  "Catch classes that catch an `ex-info` or any other thrown Throwable."
  #{'Throwable 'java.lang.Throwable 'Exception 'java.lang.Exception
    'RuntimeException 'java.lang.RuntimeException
    'ExceptionInfo 'clojure.lang.ExceptionInfo :default})

(defn- rethrows?
  "True when `body` (a catch clause's forms) ends by throwing its own
  `bound` again: nothing is converted. A rethrow inside a conditional
  converts the declared cases and resurfaces the rest whole, the law's
  \"catch only declared cases or resurface the whole cause\"."
  {:malli/schema [:=> [:cat :symbol [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]] :boolean]}
  [bound body]
  (let [tail (last body)]
    (and (seq? tail) (= 'throw (first tail)) (= bound (second tail)))))

(defn- form-sites
  "Every node of `form` that `site?` selects, each as its row and column (a
  symbol's are those of the list holding it) and
  whether a converting `try` of the same form encloses it (`::converted?`),
  plus `::resurfaced?` for a `throw` of an enclosing catch's own binding and
  `::node` for a selected symbol. The arguments of a call to one of
  `runners` are converted. Quoted forms and `comment` bodies are data, not
  code."
  {:malli/schema [:function
                  [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form is arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]
                        [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Any source node.", :gen/elements [nil 0 :k [] {}]}]] :boolean]]
                   [:vector :map]]
                  [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form is arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]
                        [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Any source node.", :gen/elements [nil 0 :k [] {}]}]] :boolean]
                        [:set :symbol]]
                   [:vector :map]]]}
  ([form site?] (form-sites form site? #{}))
  ([form site? runners]
  (letfn [(walk [node converted? bindings at]
            (let [at (if (:line (meta node)) (meta node) at)
                  here (when (site? node)
                         [(cond-> {::row (:line at) ::col (:column at)
                                   ::converted? converted?
                                   ::resurfaced? (and (seq? node) (= 'throw (first node))
                                                      (contains? bindings (second node)))}
                            (symbol? node) (assoc ::node node))])]
              (into (vec here)
                    (cond
                      (and (seq? node) (#{'quote 'comment} (first node))) []

                      ;; a same-file runner calls its function argument inside
                      ;; its own converting try: what is handed to it is converted
                      (and (seq? node) (runners (first node)))
                      (mapcat #(walk % true bindings at) (rest node))

                      (and (seq? node) (= 'try (first node)))
                      (let [clause? (fn [head] #(and (seq? %) (= head (first %))))
                            catches (filter (clause? 'catch) (rest node))
                            converting? (some (fn [[_ caught bound & body]]
                                                (and (converting-catch-classes caught)
                                                     (not (rethrows? bound body))))
                                              catches)]
                        (concat
                         (mapcat #(walk % (boolean (or converted? converting?)) bindings at)
                                 (remove #(or ((clause? 'catch) %) ((clause? 'finally) %)) (rest node)))
                         (mapcat (fn [[_ _ bound & body]]
                                   (mapcat #(walk % converted? (conj bindings bound) at) body))
                                 catches)
                         (mapcat #(walk % converted? bindings at)
                                 (mapcat rest (filter (clause? 'finally) (rest node))))))

                      (coll? node) (mapcat #(walk % converted? bindings at) (seq node))
                      :else []))))]
    (walk form false #{} (meta form)))))

(defn- throw-site?
  "True for a `(throw ...)` form."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Any source node.", :gen/elements [nil 0 :k [] {}]}]] :boolean]}
  [node]
  (and (seq? node) (= 'throw (first node))))

(defn- output-schemas
  "The output schema of each arity `contract` declares, following a named
  function schema through `forms`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An authored contract is arbitrary data until it compiles.", :gen/elements [nil :k [] {}]}] :map]
                  [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An authored output schema is arbitrary data until it compiles.", :gen/elements [nil :k []]}]]]}
  [contract forms]
  (loop [pending [contract] seen #{} outputs []]
    (if-let [[node & more] (seq pending)]
      (cond
        (and (keyword? node) (not (seen node)))
        (recur (cons (get forms node) more) (conj seen node) outputs)

        (and (vector? node) (= :=> (first node))) (recur more seen (conj outputs (nth node 2 nil)))
        (and (vector? node) (= :-> (first node))) (recur more seen (conj outputs (peek node)))
        (and (vector? node) (= :function (first node)))
        (recur (concat (remove map? (rest node)) more) seen outputs)

        :else (recur more seen outputs))
      outputs)))

(defn- error-alternative?
  "True when `schema`, or one alternative of its union, is `:seon.error/base`
  or an `:and` extending it, named schemas followed through `forms`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "An authored schema is arbitrary data until it compiles.", :gen/elements [nil :k []]}] :map]
                  :boolean]}
  [schema forms]
  (letfn [(error? [node seen]
            (cond
              (= :seon.error/base node) true
              (and (keyword? node) (not (seen node))) (error? (get forms node) (conj seen node))
              (not (vector? node)) false
              :else
              (let [[head & children] node
                    children (remove map? children)]
                (case head
                  :and (boolean (some #(error? % seen) children))
                  (:or :maybe) (boolean (some #(error? % seen) children))
                  :orn (boolean (some #(error? (peek (vec %)) seen) children))
                  :multi (boolean (some #(and (vector? %) (error? (peek %) seen)) children))
                  false))))]
    (error? schema #{})))

(defn- form-transaction-functions
  "Every function the read `forms` of one text name as the target of
  `[:db.fn/call f ...]`, qualified through the text's `ns` aliases."
  {:malli/schema [:=> [:cat [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]] [:set :qualified-symbol]]}
  [forms]
  (let [namespace-name (namespace-name-in forms)
        aliases (require-aliases forms)]
    (into #{}
          (for [node (tree-seq coll? seq forms)
                :when (and (vector? node) (= :db.fn/call (first node)))
                :let [target (second node)
                      target (if (and (seq? target) (= 'var (first target))) (second target) target)]
                :when (symbol? target)]
            (if-let [target-ns (namespace target)]
              (symbol (str (get aliases (symbol target-ns) target-ns)) (name target))
              (symbol (str namespace-name) (name target)))))))

(defn transaction-functions
  "Every function a text names as the target of `[:db.fn/call f ...]`,
  qualified. Datahike's writer calls it with the transaction database
  (reference-code/datahike/src/datahike/db/transaction.cljc:1171-1172) and
  `seon.db/transact!` returns its throw as `:seon.db/transaction-refused-error`:
  a transaction function's throw IS its declared refusal."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:set :qualified-symbol]]}
  [texts]
  (into #{}
        (mapcat (fn [text]
                  (when (str/includes? text ":db.fn/call")
                    (form-transaction-functions (read-source text)))))
        (vals texts)))

(defn- parameters
  "Every symbol a function definition's argument vectors bind."
  {:malli/schema [:=> [:cat [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form's members are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]] [:set :symbol]]}
  [form]
  (let [arglists (concat (filter vector? (take 4 (drop 2 form)))
                         (keep #(when (and (seq? %) (vector? (first %))) (first %)) (drop 2 form)))]
    (into #{} (comp (mapcat #(tree-seq coll? seq %)) (filter simple-symbol?) (remove #{'&}))
          arglists)))

(defn- runners
  "The functions of `definitions` that call one of their own parameters
  inside their own converting `try`: whatever is handed to them is converted
  (my.program's `read-result`, the pattern of a public boundary running a
  private body)."
  {:malli/schema [:=> [:cat [:vector [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A definition form's members are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]]] [:set :symbol]]}
  [definitions]
  (into #{}
        (keep (fn [form]
                (when (function-heads (first form))
                  (let [bound (parameters form)]
                    (when (some ::converted? (form-sites form #(and (seq? %) (contains? bound (first %)))))
                      (second form))))))
        definitions))

(defn containment
  "Each `defn`/`defn-` in the source `text` that holds a `throw` or reaches
  one through a same-file function, classified:
  `::sites` its own throw sites; `::escaping` those neither converted in the
  function nor resurfacing a caught cause, plus each unconverted reference
  to a same-file function whose failure escapes undeclared (the throwing
  helper's callers); `::declared?` its output union names an error
  alternative; `::caller-converted?` it is private and its failure never
  leaves the file: every same-file call of it is converted (by a `try` or a
  runner it is handed to) or made by a function whose own failure never
  leaves the file.
  A declared function or a `:db.fn/call` target of the text does not pass
  its throws on. `source` is the text's read top-level forms, `forms` the
  schema population. With `selected`, only those definitions are answered."
  {:malli/schema [:function
                  [:=> [:cat [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]] :map] [:vector :map]]
                  [:=> [:cat [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]] :map [:maybe [:set :symbol]]] [:vector :map]]]}
  ([source forms] (containment source forms nil))
  ([source forms selected]
  (let [namespace-name (namespace-name-in source)
        definitions (filterv #(function-heads (first %)) (definition-forms source))
        names (into #{} (map second) definitions)
        converting (runners definitions)
        local-transactions (into #{} (map #(symbol (name %)))
                                 (form-transaction-functions source))
        facts (into {}
                    (map (fn [form]
                           (let [sites (form-sites form throw-site? converting)
                                 references (form-sites form #(and (symbol? %) (contains? names %)) converting)]
                             [(second form)
                              {::form form
                               ::sites sites
                               ::direct (into [] (comp (remove #(or (::converted? %) (::resurfaced? %)))
                                                       (map #(assoc % ::sites-own? true)))
                                              sites)
                               ::references (filterv #(not= (second form) (::node %)) references)
                               ::declared? (boolean (some #(error-alternative? % forms)
                                                          (output-schemas (declared-schema form) forms)))}])))
                    definitions)
        passes-on? (fn [name-symbol] (not (or (get-in facts [name-symbol ::declared?])
                                              (local-transactions name-symbol))))
        ;; the functions whose failure reaches their caller undeclared: their
        ;; own escaping throws, then every unconverted caller of one, to a fixpoint
        escaping (loop [found (into #{} (keep (fn [[n f]] (when (and (seq (::direct f)) (passes-on? n)) n))) facts)]
                   (let [more (into found
                                    (keep (fn [[n f]]
                                            (when (and (passes-on? n)
                                                       (some #(and (found (::node %)) (not (::converted? %)))
                                                             (::references f)))
                                              n)))
                                    facts)]
                     (if (= more found) found (recur more))))
        ;; each function's same-file callers, and whether each call converts
        referrers (reduce-kv (fn [m from {::keys [references]}]
                               (reduce #(update %1 (::node %2) (fnil conj []) (assoc %2 ::from from))
                                       m references))
                             {} facts)
        public? (fn [name-symbol] (= 'defn (first (get-in facts [name-symbol ::form]))))
        ;; the escaping functions whose failure leaves the file: every public
        ;; one, a private one no function here calls, then every private one
        ;; an escaping caller calls unconverted, to a fixpoint
        leaves (loop [found (into #{} (filter #(or (public? %) (empty? (referrers %)))) escaping)]
                 (let [more (into found
                                  (filter #(some (fn [call] (and (found (::from call)) (not (::converted? call))))
                                                 (referrers %)))
                                  escaping)]
                   (if (= more found) found (recur more))))]
    (into []
          (keep (fn [[name-symbol {::keys [form sites direct references declared?]}]]
                  (let [reached (filterv #(and (escaping (::node %)) (not (::converted? %))) references)]
                    (when (and (or (seq sites) (seq reached))
                               (or (nil? selected) (selected name-symbol)))
                      {::symbol (symbol (str namespace-name) (name name-symbol))
                       ::row (:line (meta form))
                       ::sites sites
                       ::escaping (if (escaping name-symbol) (into direct reached) direct)
                       ::declared? declared?
                       ::public? (public? name-symbol)
                       ::caller-converted? (boolean (and (escaping name-symbol) (not (leaves name-symbol))))}))))
          (sort-by (comp :line meta ::form val) facts)))))

(defn- test-source?
  "True for a path under a `test` root: the test runner converts its members."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path]
  (or (str/starts-with? path "test/") (str/includes? path "/test/")))

(defn uncontained-changes
  "Findings for every `defn`/`defn-` in the read `source` forms that the read
  `baseline` forms lack or define differently and that holds a throw reaching
  its caller with no error alternative in its output union (`forms` is the
  schema population): the crash the owner's
  2026-09-28 law forbids. A throw its own function converts, a resurfaced
  caught cause and a private function every same-file caller converts are
  internal control flow. Test sources are the test runner's to convert.
  A transaction function is judged by [[check]], which knows every text."
  {:malli/schema [:=> [:cat :string [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]] [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]] :map] [:vector :map]]}
  [path source baseline forms]
  (if (test-source? path)
    []
    (let [before (into {} (map (juxt second identity)) (definition-forms baseline))
          changed (into #{} (comp (remove #(same-definition? % (get before (second %)))) (map second))
                        (definition-forms source))]
      (into []
            (keep (fn [{::keys [row escaping declared? caller-converted?] function-symbol ::symbol}]
                    (when (and (changed (symbol (name function-symbol)))
                               (seq escaping) (not declared?) (not caller-converted?))
                      {:filename path :row (::row (first escaping)) :col (::col (first escaping))
                       :level :error :type :uncontained-throw ::symbol function-symbol
                       :message (str function-symbol " (row " row ") throws to its caller "
                                     (count escaping) " way(s) with no error alternative in its "
                                     "output union. Return the failure as a named error value "
                                     "instead ([:and :seon.error/base [:map ...]] naming the "
                                     "operation, what was expected, the offending value by "
                                     "reference and the fix) and add it to the contract's "
                                     "output union, e.g. [:=> [:cat ...] [:or :my.ns/result "
                                     ":my.ns/refused-error]]; or convert the throw in this "
                                     "function's own try/catch.")})))
            (when (seq changed) (containment source forms changed))))))

(defn- checkout-sources
  "Every JVM source text under `root`'s src/, by path."
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [root]
  (into {} (map (fn [path] [path (read-file path)]))
        (source-files [(.getPath (io/file root "src"))])))

;;; ---------------------------------------------------------------------------
;;; Compilation: the publication path
;;; ---------------------------------------------------------------------------

(defn- schema-files
  "The paths of the checkout's schema resource files at `root`, by name."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [root]
  (->> (.listFiles (io/file root "resources/seon/schemas"))
       (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
       (sort-by #(.getName ^java.io.File %))
       (mapv #(.getPath ^java.io.File %))))

(defn- resources-population
  "The packaged schema population the read schema `resources` compose."
  {:malli/schema [:=> [:cat [:vector [:map [::schema.edn/forms :map]]]] :map]}
  [resources]
  (schema.edn/derive-config-forms (apply merge (map ::schema.edn/forms resources))))

(defn- schema-resources
  "The checkout's schema resource files at `root` (`::resources`) and the
  population they compose (`::population`), each file read once and parsed
  once per content: a `seon.cache` kind keyed by every file's path and text."
  {:malli/schema [:=> [:cat :string] [:map [::resources [:vector [:map ::schema.edn/file ::schema.edn/resource [::schema.edn/forms :map]]]] [::population :map]]]}
  [root]
  (let [texts (mapv (fn [path] [path (read-file path)]) (schema-files root))]
    (cache/lookup ::schema-resources texts
                  #(* 50000 (count (::resources %)))
                  #(let [resources (mapv (fn [[path text]]
                                           {::schema.edn/file path
                                            ::schema.edn/resource (str "seon/schemas/" (.getName (io/file path)))
                                            ::schema.edn/forms (edn/read-string text)})
                                         texts)]
                     {::resources resources ::population (resources-population resources)}))))

(defn checkout-packaged-forms
  "The packaged schema population of the checkout at `root`, read from disk.

  `seon.schema.edn/packaged-forms` reads the same directory through the
  classpath; a JVM that runs a published snapshot has the snapshot's copy on
  its classpath, so the checkout's files are read here directly and composed
  by the same `derive-config-forms`. Duplicate keys and placement are schema
  admission's refusals, not this check's."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (::population (schema-resources root)))

(defn- resources-placement
  "A finding for a schema key the read schema `resources` declare outside
  `<its namespace>.edn`: the placement schema admission refuses when a JVM
  loads the resources (`seon.schema.edn/validate-resource-placement!`, which
  names the first such key). Empty when every key is in its own file."
  {:malli/schema [:=> [:cat [:vector [:map ::schema.edn/file ::schema.edn/resource [::schema.edn/forms :map]]]] [:vector :map]]}
  [resources]
  (try
    (#'schema.edn/validate-resource-placement! resources)
    []
    ;; Declared case: the placement refusal, reported as the gate's finding.
    (catch clojure.lang.ExceptionInfo refusal
      (if-let [attribute (::schema.edn/misplaced-attribute (ex-data refusal))]
        [{:filename (::schema.edn/file (ex-data refusal)) :row 1 :col 1 :level :error
          :type :schema-placement :message (ex-message refusal)
          ::misplaced-attribute attribute}]
        (throw refusal)))))

(defn placement-findings
  "The schema placement findings of the checkout at `root`: see `resources-placement`."
  {:malli/schema [:=> [:cat :string] [:vector :map]]}
  [root]
  (resources-placement (::resources (schema-resources root))))

(defn- namespace-source
  "The checkout source text that declares namespace `namespace-name`, or nil."
  {:malli/schema [:=> [:cat :string :symbol] [:or :nil :string]]}
  [root namespace-name]
  (let [relative (-> (str namespace-name) (.replace "-" "_") (.replace "." "/"))]
    (some (fn [[directory extension]]
            (let [file (io/file root directory (str relative extension))]
              (when (.isFile file) (read-file (.getPath file)))))
          (for [directory ["src" "test"] extension [".clj" ".cljc"]]
            [directory extension]))))

(defn- checkout-defines?
  "True when the checkout's source declares the predicate."
  {:malli/schema [:=> [:cat :string :qualified-symbol] :boolean]}
  [root predicate]
  (boolean
   (when-some [text (namespace-source root (symbol (namespace predicate)))]
     (some #(= (name predicate) (name (second %)))
           (definition-forms (read-source text))))))

(defn- predicate-bindings
  "Loaded or supplied predicates, compile-only placeholders for checkout declarations, and unresolved names."
  {:malli/schema [:function [:=> [:cat :string [:sequential :seon.schema/value]] [:map [::bound :map] [::unresolved [:set :qualified-symbol]]]] [:=> [:cat :string [:sequential :seon.schema/value] :map] [:map [::bound :map] [::unresolved [:set :qualified-symbol]]]]]}
  ([root definitions] (predicate-bindings root definitions {}))
  ([root definitions supplied]
   (reduce (fn [result predicate]
             (if (contains? (::bound result) predicate)
               result
               (if-let [callable (when (find-ns (symbol (namespace predicate))) (find-var predicate))]
                 (assoc-in result [::bound predicate] callable)
                 (if (checkout-defines? root predicate)
                   (assoc-in result [::bound predicate] #'clojure.core/any?)
                   (if-let [callable (when-not (namespace-source root (symbol (namespace predicate)))
                                      (@#'schema/runtime-predicate predicate))]
                     (assoc-in result [::bound predicate] callable)
                     (update result ::unresolved conj predicate))))))
           {::bound supplied ::unresolved #{}}
           (sort (into #{} (mapcat @#'schema/predicate-symbols-in) definitions)))))

(defn- population-projection
  "The projection publication builds from the packaged schema population `forms`
  of the checkout at `root`."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [root forms]
  (let [{::keys [bound unresolved]} (predicate-bindings root (vals forms))]
    (when (seq unresolved)
      (throw (ex-info "The checkout's schema resources name predicates nothing defines."
                      {::unresolved unresolved})))
    (schema/build-projection forms {} {:seon.schema/predicate-functions bound})))

(defn packaged-projection
  "The checkout's compile-check projection; unloaded checkout predicates are placeholders, never validators."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (population-projection root (checkout-packaged-forms root)))

(defn contract-refusal
  "The refusal publication would raise compiling one stored contract, or nil.

  A refusal is the declared outcome this check exists to report: its whole
  cause chain is returned, never a bare message."
  {:malli/schema [:=> [:cat :string :map :map] [:or :nil :map]]}
  [root projection {::keys [stored read-refusal] :as entry}]
  (if read-refusal
    (assoc entry ::chain read-refusal
           ::reason (str "the indexer refuses to store it: "
                         (:seon.error/message (first read-refusal))))
    (let [{::keys [bound unresolved]} (predicate-bindings root [stored] (schema/predicate-functions-in projection))]
      (if (seq unresolved)
        (assoc entry ::chain [] ::unresolved unresolved
               ::reason (str "Predicate " (pr-str (first unresolved))
                             " has no admitted callable in the corpus projection"
                             " (neither this JVM nor the checkout defines it)."))
        (try
          (m/function-schema
           (schema/compilable-form
            stored bound)
           {:registry (:seon.schema.projection/registry projection)})
          nil
          (catch Exception error
            (let [chain (error.refusal/chain error)
                  root (peek chain)
                  ;; Malli's refusals carry their subject under `:data`.
                  subject (get-in root [:seon.error/data :data])]
              (assoc entry ::chain chain
                     ::reason (str (or (:seon.error/message root)
                                       (:seon.error/throwable-class root))
                                   (when subject
                                     (str " " (pr-str subject))))))))))))

(defn- contract-refusals
  "Compile `contracts` against one projection, resolving their distinct predicates once."
  {:malli/schema [:=> [:cat :string :map [:vector :map]] [:vector :map]]}
  [root projection contracts]
  (let [{::keys [bound]} (predicate-bindings root (map ::stored contracts)
                                            (schema/predicate-functions-in projection))
        projection (schema/with-predicate-functions projection bound)]
    (into [] (keep #(contract-refusal root projection %)) contracts)))

(defn- finding
  "One refusal in the hook's clj-kondo finding shape."
  {:malli/schema [:=> [:cat :map] :map]}
  [{::keys [path row col authored reason chain] definition ::symbol :as entry}]
  (merge (select-keys entry [::unresolved])
         {:filename path :row row :col col :level :error
          :type :contract-compile
          :message (str definition " :malli/schema " (pr-str authored)
                        " does not compile against the packaged projection: " reason)
          ::chain chain}))

(defn throw-census
  "The containment census of every `defn`/`defn-` under `root`'s src/ that
  holds a `throw`: each classified by the rule [[uncontained-changes]]
  applies, with counts by class and the escaping functions by file. Measured
  on 4e10213a9 (2026-09-28): 514 sites in 376 functions; 270 functions (352
  sites) escape with no error alternative, in about one second. A function
  that throws nothing itself but calls such a same-file function unconverted
  is `::escaping-through-helper`."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (let [forms (checkout-packaged-forms root)
        texts (checkout-sources root)
        transactional (transaction-functions texts)
        rows (into [] (mapcat (fn [[path text]] (map #(assoc % ::path path) (containment (read-source text) forms))))
                   texts)
        class-of (fn [row]
                   (cond (empty? (::escaping row)) ::contained-in-function
                         (::declared? row) ::declared-error-alternative
                         (::caller-converted? row) ::caller-converted
                         (transactional (::symbol row)) ::transaction-function
                         (some ::sites-own? (::escaping row)) ::escaping
                         :else ::escaping-through-helper))
        by-class (group-by class-of rows)
        sites (mapcat ::sites rows)]
    {::functions (count rows)
     ::sites (count sites)
     ::converted-sites (count (filter ::converted? sites))
     ::resurfaced-sites (count (filter ::resurfaced? sites))
     ::functions-by-class (update-vals by-class count)
     ::escaping-sites-by-class (update-vals by-class #(count (mapcat ::escaping %)))
     ::public-boundaries (count (filter ::public? (concat (::escaping by-class) (::escaping-through-helper by-class))))
     ::escaping-by-file (into (sorted-map) (update-vals (group-by ::path (::escaping by-class))
                                                        #(mapv ::symbol %)))
     ::through-helper-by-file (into (sorted-map) (update-vals (group-by ::path (::escaping-through-helper by-class))
                                                              #(mapv ::symbol %)))}))

(defn- uncontained
  "The uncontained-throw findings of the edited read `texts` against their
  read `baselines`, less transaction functions: the checkout's sources are read
  for `:db.fn/call` targets only when a finding needs the answer."
  {:malli/schema [:=> [:cat :string [:map-of :string [:map [::text :string] [::forms [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]]]] [:maybe [:map-of :string [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil 0 :k [] {}]}]]]] :map]
                  [:vector :map]]}
  [root texts baselines projection]
  (let [findings (into [] (mapcat (fn [[path {::keys [forms]}]]
                                    (when-some [[_ baseline] (find baselines path)]
                                      (uncontained-changes path forms baseline
                                                           (:seon.schema.projection/forms projection)))))
                       (sort-by key texts))]
    (if (empty? findings)
      findings
      (let [names (into #{} (map #(name (::symbol %))) findings)
            ;; only a text naming a flagged function can make it transactional
            candidates (into {} (filter (fn [[_ text]] (some #(str/includes? text %) names)))
                             (merge (checkout-sources root) (update-vals texts ::text)))
            transactional (transaction-functions candidates)]
        (filterv #(not (transactional (::symbol %))) findings)))))

(defn check
  "Findings for every contract declared in `::paths` (read from disk) or
  `::sources` (path to prospective text), compiled against `::projection` or
  the projection of the checkout at `::root` (default the working directory).
  `::candidate` is a projection already in hand — a live cluster's carried
  projection — used when its forms ARE the checkout's packaged forms, so an
  adopted checkout never rebuilds the population it already holds.
  Empty when every contract compiles."
  {:malli/schema [:=> [:cat [:map [::root {:optional true} :string]
                           [::paths {:optional true} [:sequential :string]]
                           [::sources {:optional true} [:map-of :string :string]]
                           [::projection {:optional true} :map]
                           [::candidate {:optional true} :map]
                           [::baselines {:optional true} [:map-of :string :string]]]]
                  [:vector :map]]}
  [{::keys [root paths sources projection candidate baselines] :or {root "."}}]
  (let [{::keys [resources population]} (schema-resources root)
        projection (or projection
                       (if (and candidate (= population (:seon.schema.projection/forms candidate)))
                         candidate
                         (population-projection root population)))
        texts (read-texts (merge (into {} (map (fn [path] [path (read-file path)])) paths)
                                 sources))
        baselines (some-> baselines (update-vals read-source))
        contexts (namespace-contexts texts)
        contracts (into [] (mapcat (fn [[path {::keys [forms]}]]
                                    (declared-contracts path forms (get contexts path no-namespace-context))))
                        (sort-by key texts))]
    (into (into (into (resources-placement resources)
                      (mapcat (fn [[path {::keys [forms]}]]
                                (when-some [[_ baseline] (find baselines path)]
                                  (incomplete-changes path forms baseline))))
                      (sort-by key texts))
                (uncontained root texts baselines projection))
          (map finding)
          (contract-refusals root projection contracts))))

;;; ---------------------------------------------------------------------------
;;; Regressions
;;; ---------------------------------------------------------------------------

(deftest every-declared-contract-compiles-against-the-packaged-projection
  (let [texts (read-texts (into {} (map (fn [path] [path (read-file path)]))
                               (source-files ["src" "test"])))
        contexts (namespace-contexts texts)
        contracts (into []
                        (mapcat (fn [[path {::keys [forms]}]]
                                  (declared-contracts path forms
                                                      (get contexts path no-namespace-context))))
                        texts)
        projection (packaged-projection ".")
        findings (mapv finding (contract-refusals "." projection contracts))]
    (is (< 1000 (count contracts))
        "the static reader found the population's contracts")
    (is (= [] (mapv #(select-keys % [:filename :row :message]) findings)))
    (is (= [] (placement-findings ".")) "every schema key is declared in its own namespace's file")))

(deftest a-schema-key-in-another-namespaces-file-fails-the-gate
  ;; Class regression (b738dff1b): four view schemas declared in seon.fn.edn,
  ;; seon.ns.edn and seon.test.edn passed this gate and refused HEAD's boot.
  (let [root (str (java.nio.file.Files/createTempDirectory "placement" (make-array java.nio.file.attribute.FileAttribute 0)))
        schemas (io/file root "resources/seon/schemas")]
    (try
      (.mkdirs schemas)
      (spit (io/file schemas "seon.fn.edn") "{:seon.fn/doc :string :seon.render.ns/function-view [:map]}")
      (let [findings (placement-findings root)]
        (is (= [:seon.render.ns/function-view] (mapv ::misplaced-attribute findings)))
        (is (= [:schema-placement] (mapv :type findings))))
      (spit (io/file schemas "seon.fn.edn") "{:seon.fn/doc :string}")
      (spit (io/file schemas "seon.render.ns.edn") "{:seon.render.ns/function-view [:map]}")
      (is (= [] (placement-findings root)) "the same key in its own file passes")
      (finally
        (doseq [f (reverse (file-seq (io/file root)))] (io/delete-file f true))))))

(deftest a-check-of-no-sources-has-no-contract-findings
  ;; `seon.contracts-compile-test/empty-input-calls-analyzer-with-a-refused-empty-source-map`:
  ;; no selected text, or a text without an `ns` form, analyses nothing.
  (is (= [] (check {::paths []})))
  (is (= [] (check {::sources {"src/seon/probe.clj" "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)"}}))))

(def ^:private historical-sources
  "The three from-zero breaks, reconstructed in their namespaces' shape."
  {"test/seon/cluster/release_context_test.clj"
   "(ns seon.cluster.release-context-test)
(defn- release-result
  {:malli/schema [:=> [:cat :map] [:or :nil [:fn #(instance? Throwable %)]]]}
  [x] x)"
   "src/seon/render/web.clj"
   "(ns seon.render.web)
(defn- page-keys
  {:malli/schema [:=> [:cat :map] :vector]}
  [x] [])"
   "src/seon/db.clj"
   "(ns seon.db (:import [datahike.db ValueKey]))
(defn- projection-value-key
  {:malli/schema [:=> [:cat :map] [:fn #(instance? ValueKey %)]]}
  [x] x)"})

(deftest each-historical-from-zero-break-is-refused-by-name
  (let [findings (check {::sources historical-sources})
        by-file (group-by :filename findings)]
    (is (= (set (keys historical-sources)) (set (keys by-file))))
    (testing "an anonymous predicate is refused where the indexer stores it"
      ;; `schema/canonical-definition` refuses an unnamed callable by name
      ;; before any compile is attempted.
      (is (every? #(str/includes? (:message %) "contracts are data")
                  (concat (get by-file "test/seon/cluster/release_context_test.clj")
                          (get by-file "src/seon/db.clj")))))
    (testing "an incomplete form names the Malli refusal"
      (is (str/includes? (:message (first (get by-file "src/seon/render/web.clj"))) ":vector")))
    (testing "every finding names file, row and definition"
      (is (every? #(and (:row %) (str/starts-with? (:message %) "seon.")) findings)))))

(deftest source-reads-as-the-clojure-reader-reads-it
  ;; `read-source` is Clojure's own reader: no namespace is loaded, nothing is
  ;; evaluated, and aliases resolve from the text's own `ns` form.
  (let [head "(ns a.b (:require [x.y :as y] [z.w :as-alias w]))\n"
        read-last #(peek (read-source (str head %)))]
    (are [text value] (= value (read-last text))
      "[::k ::y/k ::w/k]" [:a.b/k :x.y/k :z.w/k]
      "[#::{:k 1} #::y{:k 1} #:q{:k 1}]" [{:a.b/k 1} {:x.y/k 1} {:q/k 1}]
      "[#?(:clj 1 :cljs 2) #?@(:clj [3 4])]" [1 3 4]
      "#my/tag [1]" (tagged-literal 'my/tag [1])
      "'x" '(quote x)
      "#'x" '(var x))
    (is (= "a+" (str (read-last "#\"a+\""))) "a regex literal reads as its pattern")
    (is (= {:malli/schema :int} (meta (second (read-last "(def ^{:malli/schema :int} f 1)")))) "name metadata is kept")
    (is (= {:line 2 :column 1} (select-keys (meta (read-last "(f)")) [:line :column])) "a list carries its position")
    (is (= (str/trim head) (through-ns-form (str head "(f)") (read-source (str head "(f)")))) "the ns form carries where it ends")
    (is (thrown? Exception (read-source "#=(+ 1 2)")) "nothing is evaluated")))

(deftest a-new-or-changed-function-needs-a-complete-contract
  ;; Owner 2026-09-27: the edit hook refuses a function an edit adds or
  ;; changes without a complete contract; unchanged functions are untouched.
  (let [baseline "(ns seon.probe)
(defn legacy [x] x)
(defn with-regex [x] (re-matches #\"a+\" x))
(defn with-generated [xs] (map #(list `(let [y# ~%] y#)) xs))
(defn- kept {:malli/schema [:=> [:cat :int] :int]} [x] x)"
        edited "(ns seon.probe)
(defn legacy [x] x)
(defn with-regex [x] (re-matches #\"a+\" x))
(defn with-generated [xs] (map #(list `(let [y# ~%] y#)) xs))
(defn- kept {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))
(defn fresh [x] x)
(defn- private-fresh [x] x)
(defn loose {:malli/schema [:=> [:cat :any] :int]} [x] 1)
(defn justified {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason \"Any read value.\"}]] :int]} [x] 1)
(defn specified {:malli/schema [:=> [:cat :int] :int]} [x] x)"
        findings (check {::sources {"src/seon/probe.clj" edited}
                         ::baselines {"src/seon/probe.clj" baseline}})
        refused (filterv #(= :incomplete-contract (:type %)) findings)
        named (fn [fragment] (some #(str/includes? (:message %) fragment) refused))]
    (is (= 3 (count refused)))
    (is (named "adds seon.probe/fresh (defn) without a :malli/schema"))
    (is (named "adds seon.probe/private-fresh (defn-) without a :malli/schema"))
    (is (named "adds seon.probe/loose (defn) with :any standing in"))
    (is (every? #(str/includes? (:message %) "registered schema") refused))
    (is (not (named "seon.probe/legacy")) "an unchanged uncontracted function is untouched")
    (is (not (named "seon.probe/with-regex")) "a regex literal does not make an unchanged function read as changed")
    (is (not (named "seon.probe/with-generated")) "symbols each read generates afresh do not make an unchanged function read as changed")
    (is (not (named "seon.probe/justified")) "a declared polymorphic boundary is complete")
    (testing "without a baseline the corpus check judges compilation only"
      (is (empty? (filter #(= :incomplete-contract (:type %))
                          (check {::sources {"src/seon/probe.clj" edited}})))))))

(deftest a-throw-that-reaches-its-caller-needs-an-error-alternative
  ;; Owner 2026-09-28, AGENTS.md "Nothing crashes": a function an edit adds or
  ;; changes that throws to its caller declares the failure in its output
  ;; union; internal control flow is recognized by its structure.
  (let [edited "(ns seon.probe)
(defn legacy [x] (throw (ex-info \"old\" {})))
(defn crashes {:malli/schema [:=> [:cat :int] :int]} [x] (when (neg? x) (throw (ex-info \"negative\" {:x x}))) x)
(defn declares {:malli/schema [:=> [:cat :int] [:or :int :my.edit/no-match-error]]} [x] (throw (ex-info \"declared\" {})))
(defn converts {:malli/schema [:=> [:cat :int] :int]} [x] (try (throw (ex-info \"inner\" {})) (catch clojure.lang.ExceptionInfo _ 0)))
(defn resurfaces {:malli/schema [:=> [:cat :int] :int]} [x] (try (inc x) (catch Exception e (throw e))))
(defn rethrows-inner {:malli/schema [:=> [:cat :int] :int]} [x] (try (throw (ex-info \"inner\" {})) (catch Throwable t (throw t))))
(defn wraps {:malli/schema [:=> [:cat :int] :int]} [x] (try (inc x) (catch Exception e (throw (ex-info \"wrapped\" {} e)))))
(defn- helper {:malli/schema [:=> [:cat :int] :int]} [x] (throw (ex-info \"helper\" {})))
(defn declared-cases {:malli/schema [:=> [:cat :int] :int]} [x] (try (throw (ex-info \"inner\" {:kind :k})) (catch clojure.lang.ExceptionInfo e (if (:kind (ex-data e)) 0 (throw e)))))
(defn caller {:malli/schema [:=> [:cat :int] :int]} [x] (try (helper x) (catch Throwable _ 0)))
(defn- leaky-helper {:malli/schema [:=> [:cat :int] :int]} [x] (throw (ex-info \"leaky\" {})))
(defn leaky-caller {:malli/schema [:=> [:cat :int] :int]} [x] (leaky-helper x))
(defn quoted {:malli/schema [:=> [:cat :int] :any]} [x] '(throw x))
(defn- body {:malli/schema [:=> [:cat :int] :int]} [x] (throw (ex-info \"body\" {})))
(defn- run-converted {:malli/schema [:=> [:cat [:=> [:cat] :int]] :int]} [f] (try (f) (catch Exception _ 0)))
(defn runs-body {:malli/schema [:=> [:cat :int] :int]} [x] (run-converted #(body x)))
(defn refuse-call {:malli/schema [:=> [:cat :map :int] [:vector :map]]} [db x] (throw (ex-info \"refused\" {:x x})))
(defn write {:malli/schema [:=> [:cat :int] [:vector :any]]} [x] [[:db.fn/call #'refuse-call x]])"
        baseline "(ns seon.probe)
(defn legacy [x] (throw (ex-info \"old\" {})))"
        findings (check {::sources {"src/seon/probe.clj" edited}
                         ::baselines {"src/seon/probe.clj" baseline}})
        refused (into #{} (comp (filter #(= :uncontained-throw (:type %)))
                                (map #(symbol (first (str/split (:message %) #" ")))))
                      findings)]
    (is (= '#{seon.probe/crashes seon.probe/rethrows-inner seon.probe/wraps seon.probe/leaky-helper
              seon.probe/leaky-caller}
           refused)
        "a crash, a catch that rethrows what it caught, a wrapping rethrow, a helper one caller leaves unconverted and that caller are refused")
    (is (not (refused 'seon.probe/caller)) "a caller that converts its helper's throw is contained")
    (is (not (or (refused 'seon.probe/body) (refused 'seon.probe/runs-body)))
        "a body handed to a same-file runner that converts it is contained")
    (is (not (refused 'seon.probe/legacy)) "an unchanged function is not judged")
    (is (not (refused 'seon.probe/declares)) "an error alternative in the output union declares the failure")
    (is (not (refused 'seon.probe/refuse-call)) "a transaction function's throw is the writer's declared refusal")
    (is (not (refused 'seon.probe/declared-cases)) "a catch that converts declared cases and resurfaces the rest converts")
    (is (every? #(str/includes? (:message %) ":seon.error/base")
                (filter #(= :uncontained-throw (:type %)) findings))
        "each finding says how to declare the failure")
    (testing "test sources belong to the test runner's boundary"
      (is (empty? (filter #(= :uncontained-throw (:type %))
                          (check {::sources {"test/seon/probe_test.clj" edited}
                                  ::baselines {"test/seon/probe_test.clj" baseline}})))))))

(deftest checkout-predicates-are-checked-without-loading-and-follow-source-content
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "contract-predicates" (make-array java.nio.file.attribute.FileAttribute 0)))
        path (io/file root "src/contract/cost/fixture.clj")
        projection (packaged-projection ".")
        form [:=> [:cat [:fn 'contract.cost.fixture/predicate?]] :boolean]
        entry {::path "consumer.clj" ::row 1 ::col 1 ::symbol 'contract.cost.consumer/f
               ::authored form ::stored form}]
    (try
      (.mkdirs (.getParentFile path))
      (doseq [[definition expected]
              [["(defn predicate? [x] true)" []]
               ["(defn other? [x] true)" [#{'contract.cost.fixture/predicate?}]]
               ["(defn predicate? [x] false)" []]]]
        (spit path (str "(ns contract.cost.fixture)\n" definition))
        (is (= expected
               (mapv ::unresolved (contract-refusals (.getPath root) projection [entry]))))
        (is (nil? (find-ns 'contract.cost.fixture))))
      (finally
        (doseq [file (reverse (file-seq root))] (io/delete-file file true))))))
