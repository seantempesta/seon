(ns seon.contracts-compile-test
  "Every declared `:malli/schema` compiles against the packaged projection.

  The defect class (three from-zero breaks on 2026-09-22/23): a contract a
  warm JVM accepts but a fresh store refuses at its first publication —
  `[:fn #(instance? Throwable %)]`, a bare `:vector`, `[:fn #(instance?
  ValueKey %)]`. Publication reads each contract as the indexer stored it
  (`seon.fn` qualifies its symbols, `schema/canonical-definition` canonicalizes
  it, the row carries its `pr-str` and `seon.fn/add-contract-facts` reads it
  back with `edn/read-string`), then compiles it inside
  `schema/build-projection`'s registry: `schema/compilable-form` binds named
  predicates to callables and refuses anything else, and `m/function-schema`
  compiles the prepared form against the declaration registry.

  [[check]] runs exactly that chain over source TEXT, read statically with
  edamame, against the projection built from `seon.schema.edn/packaged-forms`
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
            [clojure.test :refer [deftest is testing]]
            [edamame.core :as edamame]
            [malli.core :as m]
            [seon.error.refusal :as error.refusal]
            [seon.fn :as seon.fn]
            [seon.fn.analyzer :as analyzer]
            [seon.schema :as schema]
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

(defn- read-source
  "Every top-level form of one JVM source text, `::alias/k` resolved."
  {:malli/schema [:=> [:cat :string] [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Top-level source forms are arbitrary read data.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [text]
  (edamame/parse-string-all
   text
   {:all true
    :read-eval false
    :auto-resolve-ns true
    :read-cond :allow
    :features #{:clj}
    :readers (fn [tag] (fn [value] (tagged-literal tag value)))}))

(def ^:private no-namespace-context
  "The context of a text with no `ns` form: nothing aliased or referred."
  {:requires #{} :aliases {} :refers {} :imports {}})

(defn- through-ns-form
  "The text up to the end of its `ns` form, or nil when it has none.

  A prefix keeps every row and column of the form, and the namespace context
  reads only the form's clauses, so analysing it answers as the whole file
  does (all 442 checkout files, 2026-09-24) at a fifteenth of the cost."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [text]
  (when-some [ns-form (first (filter #(and (seq? %) (= 'ns (first %)))
                                     (read-source text)))]
    (let [{:keys [end-row end-col]} (meta ns-form)
          lines (str/split text #"\n" -1)]
      (subs text 0 (+ (reduce + (map #(inc (count %)) (take (dec end-row) lines)))
                      (dec end-col))))))

(defn- namespace-contexts
  "The indexer's own alias/refer context for each text's `ns` form, by path.

  One `seon.fn.analyzer/analyze` over each text through its `ns` form, keyed
  by canonical path (the analysis mirror resolves only canonical paths), then
  `seon.fn/namespace-context` on each namespace definition: symbol
  qualification is the indexer's rule, not a copy of it. The analysis writes a
  private clj-kondo cache, so a reconstructed text never answers for the
  checkout's namespace of the same name in the shared cache."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:map-of :string :map]]}
  [texts]
  (let [paths (into {} (map (fn [path] [(.getCanonicalPath (io/file path)) path]))
                    (keys texts))
        cache (.toFile (java.nio.file.Files/createTempDirectory
                        "contracts-compile-kondo"
                        (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (into {}
            (map (fn [entry]
                   [(get paths (:seon.fn.analyzer/filename entry))
                    (@#'seon.fn/namespace-context entry)]))
            (:seon.fn.analyzer/namespace-definitions
             (analyzer/analyze
              {:seon.fn.analyzer/sources
               (into {} (keep (fn [[path text]]
                                (when-some [prefix (through-ns-form text)]
                                  [(.getCanonicalPath (io/file path)) prefix])))
                     texts)
               :seon.fn.analyzer/cache-root (.getPath cache)})))
      (finally (@#'seon.fn.analyzer/delete-tree! cache)))))

(defn declared-contracts
  "Every `:malli/schema` declared in one source text, as the indexer stores it.

  Each entry names its file, row, column and definition; `::stored` is the
  contract after the indexer's qualification, canonicalization and EDN round
  trip, or `::read-refusal` when that round trip itself refuses."
  {:malli/schema [:=> [:cat :string :string :map] [:vector :map]]}
  [path text context]
  (let [forms (read-source text)
        namespace-name (or (some #(when (and (seq? %) (= 'ns (first %)))
                                    (second %))
                                 forms)
                           'user)]
    (into []
          (keep (fn [form]
                  (when-some [authored (declared-schema form)]
                    (let [{:keys [row col]} (meta form)
                          entry {::path path ::row row ::col col
                                 ::symbol (symbol (str namespace-name)
                                                  (name (second form)))
                                 ::authored authored}]
                      (try
                        (assoc entry ::stored
                               (edn/read-string
                                (pr-str (schema/canonical-definition
                                         (@#'seon.fn/qualify-schema-symbols
                                          authored context)))))
                        (catch Exception error
                          (assoc entry ::read-refusal
                                 (error.refusal/chain error))))))))
          (definition-forms forms))))

;;; ---------------------------------------------------------------------------
;;; Completeness: every function an edit adds or changes is specified
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
  "Findings for every `defn`/`defn-` in `text` that `baseline` lacks or
  defines differently and whose contract is missing or uses `:any` as a
  stand-in. Forms compare as read data, so moving or reformatting a function
  changes nothing; a baseline of \"\" is a new file."
  {:malli/schema [:=> [:cat :string :string :string] [:vector :map]]}
  [path text baseline]
  (let [forms (read-source text)
        namespace-name (or (some #(when (and (seq? %) (= 'ns (first %))) (second %)) forms)
                           'user)
        before (into {} (map (juxt second identity))
                     (definition-forms (read-source baseline)))]
    (into []
          (keep (fn [form]
                  (when (and (function-heads (first form))
                             (not= form (get before (second form))))
                    (let [authored (declared-schema form)
                          {:keys [row col]} (meta form)
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

(defn checkout-packaged-forms
  "The packaged schema population of the checkout at `root`, read from disk.

  `seon.schema.edn/packaged-forms` reads the same directory through the
  classpath; a JVM that runs a published snapshot has the snapshot's copy on
  its classpath, so the checkout's files are read here directly and composed
  by the same `derive-config-forms`. Duplicate keys and placement are schema
  admission's refusals, not this check's."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (->> (schema-files root)
       (map #(edn/read-string (slurp %)))
       (apply merge)
       (schema.edn/derive-config-forms)))

(defn placement-findings
  "A finding for a schema key the checkout at `root` declares outside
  `<its namespace>.edn`: the placement schema admission refuses when a JVM
  loads the resources (`seon.schema.edn/validate-resource-placement!`, which
  names the first such key). Empty when every key is in its own file."
  {:malli/schema [:=> [:cat :string] [:vector :map]]}
  [root]
  (let [loaded (mapv (fn [path]
                       {::schema.edn/file path
                        ::schema.edn/resource (str "seon/schemas/" (.getName (io/file path)))
                        ::schema.edn/forms (edn/read-string (slurp path))})
                     (schema-files root))]
    (try
      (#'schema.edn/validate-resource-placement! loaded)
      []
      ;; Declared case: the placement refusal, reported as the gate's finding.
      (catch clojure.lang.ExceptionInfo refusal
        (if-let [attribute (::schema.edn/misplaced-attribute (ex-data refusal))]
          [{:filename (::schema.edn/file (ex-data refusal)) :row 1 :col 1 :level :error
            :type :schema-placement :message (ex-message refusal)
            ::misplaced-attribute attribute}]
          (throw refusal))))))

(defn- namespace-source
  "The checkout source text that declares namespace `namespace-name`, or nil."
  {:malli/schema [:=> [:cat :string :symbol] [:or :nil :string]]}
  [root namespace-name]
  (let [relative (-> (str namespace-name) (.replace "-" "_") (.replace "." "/"))]
    (some (fn [[directory extension]]
            (let [file (io/file root directory (str relative extension))]
              (when (.isFile file) (slurp file))))
          (for [directory ["src" "test"] extension [".clj" ".cljc"]]
            [directory extension]))))

(defn- checkout-defines?
  "True when the checkout's source for the predicate's namespace defines it.

  The answer a fresh JVM's `requiring-resolve` gives, read statically for a
  predicate the checking JVM has not loaded (a snapshot JVM predates it)."
  {:malli/schema [:=> [:cat :string :qualified-symbol] :boolean]}
  [root predicate]
  (boolean
   (when-some [text (namespace-source root (symbol (namespace predicate)))]
     (some #(= (name predicate) (name (second %)))
           (definition-forms (read-source text))))))

(defn- predicate-bindings
  "Callables for every qualified predicate `definitions` name, and the rest.

  `schema/build-projection` binds each with the same private resolver
  (`requiring-resolve`) before compiling. A predicate the checking JVM cannot
  resolve but the checkout's source defines is what a fresh JVM would bind;
  only the compile is asked, so nothing validates against its stand-in. A
  predicate neither answers is `::unresolved`: the refusal is named here
  rather than letting `compilable-form` reload a namespace of the checking
  JVM to look for it."
  {:malli/schema [:=> [:cat :string [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Authored declarations are arbitrary data until they compile.", :gen/elements [nil false 0 "" :k [] {}]}]]]
                  [:map [::bound :map] [::unresolved [:set :qualified-symbol]]]]}
  [root definitions]
  (reduce (fn [result predicate]
            (if-let [callable (@#'schema/runtime-predicate predicate)]
              (assoc-in result [::bound predicate] callable)
              (if (checkout-defines? root predicate)
                (assoc-in result [::bound predicate] (resolve 'clojure.core/any?))
                (update result ::unresolved conj predicate))))
          {::bound {} ::unresolved #{}}
          (sort (into #{} (mapcat @#'schema/predicate-symbols-in) definitions))))

(defn packaged-projection
  "The projection publication builds from the checkout's schema resources."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (let [forms (checkout-packaged-forms root)
        {::keys [bound unresolved]} (predicate-bindings root (vals forms))]
    (when (seq unresolved)
      (throw (ex-info "The checkout's schema resources name predicates nothing defines."
                      {::unresolved unresolved})))
    (schema/build-projection forms {} {:seon.schema/predicate-functions bound})))

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
    (let [{::keys [bound unresolved]} (predicate-bindings root [stored])]
      (if (seq unresolved)
        (assoc entry ::chain []
               ::reason (str "Predicate " (pr-str (first unresolved))
                             " has no admitted callable in the corpus projection"
                             " (neither this JVM nor the checkout defines it)."))
        (try
          (m/function-schema
           (schema/compilable-form
            stored (merge (schema/predicate-functions-in projection) bound))
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

(defn- finding
  "One refusal in the hook's clj-kondo finding shape."
  {:malli/schema [:=> [:cat :map] :map]}
  [{::keys [path row col authored reason chain] definition ::symbol}]
  {:filename path :row row :col col :level :error
   :type :contract-compile
   :message (str definition " :malli/schema " (pr-str authored)
                 " does not compile against the packaged projection: " reason)
   ::chain chain})

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
  (let [projection (or projection
                       (when (and candidate
                                  (= (checkout-packaged-forms root)
                                     (:seon.schema.projection/forms candidate)))
                         candidate)
                       (packaged-projection root))
        texts (merge (into {} (map (fn [path] [path (slurp path)])) paths)
                     sources)
        contexts (namespace-contexts texts)]
    (into (into (placement-findings root)
                (mapcat (fn [[path text]]
                          (when-some [[_ baseline] (find baselines path)]
                            (incomplete-changes path text baseline))))
                (sort-by key texts))
          (comp (mapcat (fn [[path text]]
                          (declared-contracts path text
                                              (get contexts path no-namespace-context))))
                (keep #(contract-refusal root projection %))
                (map finding))
          (sort-by key texts))))

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
;;; Regressions
;;; ---------------------------------------------------------------------------

(deftest every-declared-contract-compiles-against-the-packaged-projection
  (let [texts (into {} (map (fn [path] [path (slurp path)]))
                    (source-files ["src" "test"]))
        contexts (namespace-contexts texts)
        contracts (into []
                        (mapcat (fn [[path text]]
                                  (declared-contracts path text
                                                      (get contexts path no-namespace-context))))
                        texts)
        projection (packaged-projection ".")
        findings (into [] (comp (keep #(contract-refusal "." projection %))
                                (map finding))
                       contracts)]
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

(deftest a-new-or-changed-function-needs-a-complete-contract
  ;; Owner 2026-09-27: the edit hook refuses a function an edit adds or
  ;; changes without a complete contract; unchanged functions are untouched.
  (let [baseline "(ns seon.probe)
(defn legacy [x] x)
(defn- kept {:malli/schema [:=> [:cat :int] :int]} [x] x)"
        edited "(ns seon.probe)
(defn legacy [x] x)
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
    (is (not (named "seon.probe/justified")) "a declared polymorphic boundary is complete")
    (testing "without a baseline the corpus check judges compilation only"
      (is (empty? (filter #(= :incomplete-contract (:type %))
                          (check {::sources {"src/seon/probe.clj" edited}})))))))
