(ns seon.guard-census
  "The error law's census (AGENTS.md, Nothing crashes): the guards each changed function holds, read statically from
  source text with edamame, so it runs in Babashka with no Seon JVM. A changed function is a `defn` whose form differs
  from its base's; a guard is a catch of a general class outside a declared `:seon/entrant`, or an error-shape check of
  a value whose producer's contract declares no error variant. Producers' contracts are their `:malli/schema` attribute
  maps in the source of the namespace the call resolves to.

  bb -cp script:src -m seon.guard-census TREE BASE PATH...

  judges the `src` .clj PATHs of the Git tree TREE against BASE (a commit), prints each guarded function with its
  guards, and exits 1 when any holds one."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [edamame.core :as edamame]))

(def general-catches
  "Exception classes only a guard or the entrant boundary catches: an interop edge catches its own exception."
  #{'Throwable 'Exception 'RuntimeException 'Error 'Object
    'java.lang.Throwable 'java.lang.Exception 'java.lang.RuntimeException 'java.lang.Error 'java.lang.Object})

(defn git-text
  "The text of `path` in the Git tree-ish `tree` of the checkout `repository`, or nil where it has none."
  {:malli/schema [:=> [:cat :string :string :string] [:maybe :string]]}
  [repository tree path]
  (let [{:keys [exit out]} (shell/sh "git" "-C" repository "show" (str tree ":" path))]
    (when (zero? exit) out)))

(defn read-forms
  "The top-level forms of Clojure `text`, read structurally (whitespace and comments never count)."
  {:malli/schema [:=> [:cat :string] [:sequential :any]]}
  [text]
  (edamame/parse-string-all text {:all true :auto-resolve (fn [a] (if (= :current a) 'user a))
                                  :readers (fn [_] identity) :read-cond :allow :features #{:clj}}))

(defn namespace-of
  "The namespace `forms` declares, its aliases and its file's functions by name: `{:ns :aliases :functions}`, each
  function its form."
  {:malli/schema [:=> [:cat [:sequential :any]] [:map [:ns [:maybe :symbol]] [:aliases [:map-of :symbol :symbol]]
                                                 [:functions [:map-of :symbol :any]]]]}
  [forms]
  (let [declaration (some #(when (and (seq? %) (= 'ns (first %))) %) forms)
        requires (mapcat rest (filter #(and (seq? %) (= :require (first %))) declaration))]
    {:ns (second declaration)
     :aliases (into {} (keep #(when (vector? %) (let [{as :as} (apply hash-map (rest %))] (when as [as (first %)]))))
                    requires)
     :functions (into {} (keep #(when (and (seq? %) (#{'defn 'defn-} (first %)) (symbol? (second %))) [(second %) %]))
                      forms)}))

(defn changed-functions
  "Each function of `after` (a `namespace-of` value) whose form differs from `before`'s or which `before` lacks: its
  name and form, sorted by name."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:vector [:tuple :symbol :any]]]}
  [before after]
  (vec (for [[fn-name form] (sort-by key (:functions after))
             :when (not= form (get-in before [:functions fn-name]))]
         [fn-name form])))

(defn function-schema
  "The `:malli/schema` of a `defn` form, from its attribute map."
  {:malli/schema [:=> [:cat :any] :any]}
  [form]
  (some #(when (map? %) (:malli/schema %)) (take 4 form)))

(defn error-variant?
  "Whether the output schema form `schema` names an error variant: a key of `seon.error`, or one whose name says error
  or refusal, in the form or through a named schema `definition` (a function of the key) holding one as a union member."
  {:malli/schema [:=> [:cat :any ifn?] :boolean]}
  [schema definition]
  (letfn [(variant-name? [k] (and (qualified-keyword? k)
                                  (or (str/starts-with? (namespace k) "seon.error")
                                      (some #(str/includes? (name k) %) ["error" "refusal"]))))
          (union-members [form seen]
            (cond (and (qualified-keyword? form) (not (seen form)))
                  (if-let [d (definition form)] (cons form (union-members d (conj seen form))) [form])
                  (and (vector? form) (#{:or :and :multi} (first form)))
                  (mapcat #(union-members % seen) (rest form))
                  :else []))]
    (boolean (or (some variant-name? (tree-seq coll? seq schema))
                 (some variant-name? (union-members schema #{}))))))

(defn outputs
  "The output schema forms of a function schema form."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [schema]
  (cond (and (vector? schema) (= :=> (first schema))) [(nth schema 2 nil)]
        (and (vector? schema) (= :function (first schema))) (into [] (mapcat outputs) (rest schema))
        :else []))

(defn producers
  "The calls that may produce `form`'s value: the call itself, or a conditional's branches."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [form]
  (if (and (seq? form) (#{'when 'when-not 'if 'if-not 'when-let 'if-let 'some->} (first form)))
    (into [] (mapcat producers) (if (#{'if 'if-not 'if-let} (first form)) (drop 2 form) [(last form)]))
    [form]))

(defn- translates?
  "Whether `catch-form` keeps the exception it binds: a named binding its handler uses, as an interop edge's catch
  does when it builds its declared variant from its library's own exception."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [[_ _ binding & handler]]
  (boolean (and (symbol? binding) (not= '_ binding)
                (some #{binding} (tree-seq coll? seq handler)))))

(defn guards
  "The guards function `form` holds. `contract` answers the `:malli/schema` of the call head symbol it is given, or
  nil; `definition` the named schema form of a key, or nil. A catch of a general class is the interop edge's own catch
  when the function declares an error variant and the handler keeps the exception: a library such as
  clojure.data.json throws only `java.lang.Exception`, so its own exception has no narrower class."
  {:malli/schema [:=> [:cat :any ifn? ifn?] [:vector :any]]}
  [form contract definition]
  (let [entrant? (some #(and (map? %) (:seon/entrant %)) form)
        edge? (boolean (some #(error-variant? % definition) (outputs (function-schema form))))
        nodes (tree-seq coll? seq form)
        bound (reduce (fn [held [name init]] (update held name (fnil conj []) init)) {}
                      (sequence (comp (filter #(and (seq? %) (#{'let 'when-let 'if-let 'loop} (first %)) (vector? (second %))))
                                      (mapcat #(partition 2 (second %)))
                                      (filter (comp symbol? first)))
                                nodes))
        catches (when-not entrant?
                  (filter #(and (seq? %) (= 'catch (first %)) (general-catches (second %))
                                (not (and edge? (translates? %))))
                          nodes))
        call-declares-none? (fn [call]
                              (let [schema (when (and (seq? call) (symbol? (first call))) (contract (first call)))]
                                (boolean (and schema (not-any? #(error-variant? % definition) (outputs schema))))))
        ;; a name bound more than once is judged only when every binding's producer declares no error variant
        declares-none? (fn [producer]
                         (if (symbol? producer)
                           (let [inits (bound producer)]
                             (boolean (and (seq inits) (every? #(some call-declares-none? (producers %)) inits))))
                           (call-declares-none? producer)))
        checks (filter #(and (seq? %) (#{:seon.error/at :seon.error/type} (first %))
                             (some declares-none? (producers (second %))))
                       nodes)]
    (vec (concat catches checks))))

(defn namespace-path
  "The `src` path candidates of namespace `nsn`."
  {:malli/schema [:=> [:cat :symbol] [:vector :string]]}
  [nsn]
  (let [base (str "src/" (-> (str nsn) (str/replace "." "/") (str/replace "-" "_")))]
    [(str base ".clj") (str base ".cljc")]))

(defn census
  "The guarded functions among those `paths` change from `base` to `tree`, each `ns/name` to its guards. `text-at` answers
  a path's text at a tree-ish (`git-text` of one checkout, partially applied), or nil."
  {:malli/schema [:=> [:cat ifn? :string :string [:sequential :string]] [:map-of :symbol [:vector :any]]]}
  [text-at tree base paths]
  (let [namespaces (memoize (fn [nsn] (some->> (namespace-path nsn) (some #(text-at tree %)) read-forms namespace-of)))
        schemas (memoize (fn [nsn]
                           (some-> (text-at tree (str "resources/seon/schemas/" nsn ".edn"))
                                   (edamame/parse-string {:readers (fn [_] identity) :auto-resolve name}))))
        definition (fn [k] (get (schemas (namespace k)) k))]
    (into (sorted-map)
          (for [path paths
                :when (and (str/starts-with? path "src/") (str/ends-with? path ".clj"))
                :let [text (text-at tree path)]
                :when text
                :let [after (namespace-of (read-forms text))
                      before (some-> (text-at base path) read-forms namespace-of)
                      contract (fn [head]
                                 (let [target (if-let [prefix (some-> (namespace head) symbol)]
                                                (get (:aliases after) prefix prefix)
                                                (:ns after))
                                       held (if (= target (:ns after)) after (namespaces target))]
                                   (some-> (get-in held [:functions (symbol (name head))]) function-schema)))]
                [fn-name form] (changed-functions before after)
                :let [held (guards form contract definition)]
                :when (seq held)]
            [(symbol (str (:ns after)) (str fn-name)) held]))))

(defn -main
  "Judge the PATHs of TREE against BASE in the current checkout: exit 1 naming each guarded function, else 0."
  [tree base & paths]
  (let [began (System/nanoTime)
        found (census (partial git-text (System/getProperty "user.dir")) tree base paths)
        ms (quot (- (System/nanoTime) began) 1000000)]
    ;; the answer is data on stdout (bin/lane shows it); the exit status is the verdict
    (prn {:seon.guard-census/paths (count paths) :seon.guard-census/ms ms :seon.guard-census/guarded found})
    (System/exit (if (empty? found) 0 1))))
