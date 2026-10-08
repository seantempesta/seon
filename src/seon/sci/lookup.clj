(ns seon.sci.lookup
  "The agent REPL's lookups: `doc`, `dir`, `source` and the clojure.test entries, read from the
  database at the call and installed into each agent's SCI context."
  (:require [clojure.edn :as edn]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.test]
            [malli.core :as m]
            [sci.core :as sci]
            [sci.ctx-store]
            [sci.impl.namespaces :as sci.namespaces]
            [seon.call-preparation :as call-preparation]
            [seon.db :as db]
            [seon.error.refusal]
            [seon.fn.analyzer :as analyzer]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.sci.reader :as reader]))

;;; clojure.test over the evaluating context's namespaces. The agent's
;;; clojure.test is the host namespace copied in; its test-var, test-vars and
;;; report run as they are, while these four find SCI namespaces through the
;;; evaluating ctx (`sci.ctx-store`, bound by `sci/eval-form`) as babashka's
;;; SCI-side copy does (reference-code/babashka/src/babashka/impl/clojure/test.clj:750-790).

(defn- agent-test-all-vars
  "Run every test Var interned in one SCI namespace, with its fixtures."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "clojure.test names a namespace by symbol or by namespace object.", :gen/elements [nil]}]] :nil]}
  [namespace-designator]
  (let [ctx (sci.ctx-store/get-ctx)]
    (clojure.test/test-vars
     (vals (sci.namespaces/sci-ns-interns*
            ctx (sci.namespaces/sci-the-ns* ctx namespace-designator))))))

(defn- agent-do-report
  "Report one clojure.test event; an erroring test's Throwable chain loses its
  host stack frames first, so the report holds each link's class and message."
  {:malli/schema [:=> [:cat :map] :any]}
  [event]
  (when (and (= :error (:type event)) (instance? Throwable (:actual event)))
    (run! #(.setStackTrace ^Throwable % (make-array StackTraceElement 0))
          (take-while some? (iterate #(.getCause ^Throwable %) (:actual event)))))
  (clojure.test/do-report event))

(defn- agent-test-ns
  "Run one SCI namespace's tests; answer its report counters."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "clojure.test names a namespace by symbol or by namespace object.", :gen/elements [nil]}]] [:map-of :keyword :int]]}
  [namespace-designator]
  (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
    ;; clojure.test's :begin-test-ns report calls the host `ns-name`, which
    ;; cannot name a SCI namespace; print its line here instead.
    (clojure.test/with-test-out
      (println "\nTesting" (str (sci.namespaces/sci-the-ns* (sci.ctx-store/get-ctx) namespace-designator))))
    (agent-test-all-vars namespace-designator)
    @clojure.test/*report-counters*))

(defn- agent-run-tests
  "Run the named SCI namespaces' tests, else the current namespace's; report the summary."
  {:malli/schema [:=> [:cat [:* [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "clojure.test names a namespace by symbol or by namespace object.", :gen/elements [nil]}]]] [:map-of :keyword [:or :int :keyword]]]}
  [& namespace-designators]
  (let [summary (assoc (apply merge-with + (map agent-test-ns (or namespace-designators [@sci/ns])))
                       :type :summary)]
    (clojure.test/do-report summary)
    summary))

(defn- agent-use-fixtures
  "Record fixtures on the current SCI namespace, where clojure.test/test-vars reads them."
  {:malli/schema [:=> [:cat [:enum :once :each] [:* ifn?]] :any]}
  [fixture-type & fixtures]
  (alter-meta! @sci/ns assoc
               (keyword "clojure.test" (str (name fixture-type) "-fixtures")) fixtures))

(defn agent-test-namespace
  "The clojure.test entries that must see the evaluating context's namespaces."
  [sci-namespace]
  (into {}
        (map (fn [[intern-name function]]
               [intern-name (sci/new-var intern-name function {:ns sci-namespace})]))
        {'test-all-vars agent-test-all-vars
         'test-ns agent-test-ns
         'run-tests agent-run-tests
         'do-report agent-do-report
         'use-fixtures agent-use-fixtures}))

(def ^:private program-documentation-selector
  [:seon.fn/sym :seon.fn/doc :seon.fn/doc-order :seon.fn/arglists :seon.fn/spec
   :seon.schema.admission/source
   {:seon.fn/arities
    [:seon.fn.arity/arity
     '(limit :seon.fn.arity/input-refs nil)
     '(limit :seon.fn.arity/output-refs nil)]}])

(defn- program-documentation
  "Read one namespace's public functions at the evaluation's database basis."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol]
                  [:or [:vector :map] :seon.db/error-result]]}
  [database namespace-name]
  (db/q '[:find [(pull ?function selector) ...]
          :in $ selector ?name
          :where [?namespace :seon.ns/name ?name]
                 [?function :seon.fn/ns ?namespace]
                 [?function :seon.fn/sym _]
                 [?function :seon.fn/private? false]
                 (not [?function :seon.fn/internal? true])]
        database program-documentation-selector namespace-name))

(defn- documentation-unavailable
  {:malli/schema [:=> [:cat :seon.sci.eval/authored-reference] :seon.sci.eval/documentation-unavailable-error]}
  [requested]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.lookup/documentation-unavailable
   {:seon.error/message "No public documentation is available; request an installed public declaration."
    :seon.error/offending requested
    :seon.sci.eval/documentation-unavailable requested
    :seon.error/member :seon.fn/sym
    :seon.error/expected :seon.sci.eval/public-documentation}))

(defn- declaration-absent
  "The typed unknown `doc` and `dir` show for an absent declaration.

   An absent contract and a declared empty one are different facts, and this
   is the surface an agent reads before deciding how to call a function: a
   missing `:seon.fn/spec` rendered as `[]` says the function declares
   nothing, which is a claim the row does not support (AGENTS.md section 2.4)."
  {:malli/schema [:=> [:cat :qualified-keyword] :seon.sci.eval/declaration-absent-error]}
  [attribute]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.lookup/declaration-absent
   {:seon.error/message "The program row lacks a declaration; supply the declared member before relying on it."
    :seon.error/offending attribute
    :seon.sci.eval/missing-declaration attribute
    :seon.error/expected :seon.sci.eval/present-declaration}))

(def ^:private section-headings
  "The fixed docstring headings that open a section and so end an Example."
  ["Edge cases:" "Costs:" "Recommendations:" "Errors:" "Known issues:" "## "])

(defn- example-start
  "The first-form text after an Example heading line (`Example:`, `Examples:`,
  `## Example`, `## Examples`), or nil when the line opens no Example."
  {:malli/schema [:=> [:cat :string] [:or :nil :string]]}
  [line]
  (or (some #(when (str/starts-with? line %) (str/trim (subs line (count %))))
            ["Examples:" "Example:"])
      (when (#{"## Example" "## Examples"} line) "")))

(defn docstring-parts
  "Split a declared docstring into its summary (the first sentence of its first
  paragraph, whole), the body that follows, and the final Example section.

  The Example section runs from its heading to the next fixed heading (Edge
  cases, Costs, Recommendations, Errors, Known issues, any `## ` heading) or the
  end; the sections around it stay in the body. `Example: (form)` on one line
  yields that form."
  {:malli/schema [:=> [:cat :string] [:map [:summary :string] [:body {:optional true} [:string {:min 1}]]
                                      [:example {:optional true} [:string {:min 1}]]]]}
  [docstring]
  (let [lines (mapv str/trim (str/split-lines (or docstring "")))
        lines (vec (drop-while str/blank? lines))
        [paragraph after] (split-with (complement str/blank?) lines)
        flat (str/join " " paragraph)
        stop (str/index-of flat ". ")
        summary (if stop (subs flat 0 (inc stop)) flat)
        leftover (when stop (str/trim (subs flat (+ stop 2))))
        lines (vec (concat (when (seq leftover) [leftover]) after))
        start (last (keep-indexed #(when (example-start %2) %1) lines))
        heading? (fn [line] (some #(str/starts-with? line %) section-headings))
        end (when start
              (or (first (filter #(heading? (lines %)) (range (inc start) (count lines))))
                  (count lines)))
        join #(str/trim (str/join "\n" %))
        body (join (if start (concat (subvec lines 0 start) (subvec lines end)) lines))]
    ;; an empty section is absent
    (cond-> {:summary summary}
      (seq body) (assoc :body body)
      start (assoc :example (join (cons (example-start (lines start)) (subvec lines (inc start) end)))))))

(defn- arglists-reader
  "The function that reads one arglists text as the namespace's own source
  reads it: the one reader resolves auto-resolved keywords (`{id ::id}`) in
  the namespace's aliases."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] ifn?]}
  [database namespace-name]
  (let [row (db/pull database '[{:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}]
                     [:seon.ns/name namespace-name])]
    (if (or (:seon.db/invalid-read row) (:seon.schema/expected-value row))
      (constantly row)
      (let [aliases (into {} (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns))
                          (:seon.ns/aliases row))]
        (fn [text]
          (let [read (reader/read {::reader/text text ::reader/ns namespace-name ::reader/aliases aliases
                                   :seon.config.eval.result/max-source (count text)})]
            (if (vector? read) (::reader/form (first read)) read)))))))

(defn- documentation-schemas
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:map-of :qualified-keyword :seon.schema/definition]]}
  [database row]
  (let [keys (into #{} (mapcat #(concat (:seon.fn.arity/input-refs %)
                                       (:seon.fn.arity/output-refs %)))
                   (:seon.fn/arities row))
        definitions (db/q '[:find ?key ?form :in $ [?key ...]
                            :where [?schema :seon.schema/key ?key]
                                   [?schema :seon.schema/form ?form]] database keys)]
    (when (or (:seon.db/invalid-read definitions) (:seon.schema/expected-value definitions))
      (throw (ex-info "Documentation schema references unavailable." definitions)))
    (into (sorted-map) (map (fn [[key form]] [key (edn/read-string form)])) definitions)))

(defn- documentation-contract
  [database row]
  (if-let [spec (:seon.fn/spec row)]
    (let [projection (or (db/carried-projection database)
                         (let [failure (db/projection-fallback
                                        'seon.sci.lookup/documentation-contract)]
                           (throw (ex-info (:seon.error/message failure) failure))))]
      (schema/projection-cache-value
       projection [:seon.sci.eval/documentation-contract spec]
       (fn []
         (let [compiled (m/function-schema (edn/read-string spec)
                          {:registry (:seon.schema.projection/registry projection)})
               arities (mapv m/-function-info (m/-function-schema-arities compiled))
               inputs (mapv #(m/form (:input %)) arities)
               outputs (mapv #(m/form (:output %)) arities)]
           ;; one return shared by every arity is said once
           {:in (if (= 1 (count inputs)) (first inputs) inputs)
            :out (if (apply = outputs) (first outputs) outputs)}))))
    {:in (declaration-absent :seon.fn/spec)
     :out (declaration-absent :seon.fn/spec)}))

(defn- agent-documentation-contract
  {:malli/schema [:=> [:cat :seon.db/database-value :map]
                  [:or :map :seon.db/error-result]]}
  [database row]
  (let [entries (call-preparation/supplied-map-entries database (:seon.fn/sym row))
        contract (documentation-contract database row)
        schemas (documentation-schemas database row)
        spec (:seon.fn/spec row)
        arities (if (and spec (= :function (first (edn/read-string spec))))
                  (:in contract) [(:in contract)])]
    (if (or (:seon.db/invalid-read entries) (:seon.schema/expected-value entries))
      entries
      (let [inputs
            (mapv (fn [order input]
                    (reduce (fn [form [position supplied]]
                              (let [offset (if (map? (second form)) 2 1)
                                    index (+ offset position)
                                    named (get form index)
                                    ;; A request map named by its registered key opens one level so
                                    ;; the runtime-supplied entries can be left out of it.
                                    argument (if (keyword? named) (get schemas named named) named)
                                    keys (set (map #(nth % 2) supplied))]
                                (if (and (vector? argument) (= :map (first argument)))
                                  (assoc form index
                                         (into [] (remove #(and (vector? %) (keys (first %)))) argument))
                                  form)))
                            input
                            (group-by second (filter #(= order (first %)) entries))))
                  (range) arities)]
        (cond-> contract
          (seq entries)
          (assoc :in (if (= 1 (count inputs)) (first inputs) inputs)
                 :supplied (vec (distinct (map #(nth % 2) entries)))))))))

(defn- agent-arglists
  "`arglists` as an agent calls `sym`: each arglist without the positional
  arguments and map entries call preparation supplies. Unchanged where the
  contract plans no supply."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym :seon.schema/value] :seon.schema/value]}
  [database sym arglists]
  (let [plans (call-preparation/supplied-arguments database sym)]
    (if (or (:seon.db/invalid-read plans) (:seon.schema/expected-value plans) (not (seq? arglists)))
      arglists
      (let [by-count (into {} (map (juxt :argument-count identity)) plans)]
        (map (fn [arglist]
               (if-let [{:keys [positions keys]} (and (vector? arglist) (get by-count (count arglist)))]
                 (into []
                       (comp (keep-indexed (fn [index argument] (when-not (positions index) argument)))
                             (map (fn [argument]
                                    (if (map? argument)
                                      (into {} (remove (fn [[_ value]] (keys value))) argument)
                                      argument))))
                       arglist)
                 arglist))
             arglists)))))

(defn- function-doc-map
  "The documentation of one program row: the SCI Var's doc and arglists, the
  stored contract and, for an accepted override, its JVM-boundary note."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :map [:maybe :map]] [:or :map :seon.db/error-result]]
                  [:=> [:cat :seon.db/database-value :map [:maybe :map] :boolean] [:or :map :seon.db/error-result]]]}
  ([database row metadata]
   (let [overrides (when (= :agent (:seon.schema.admission/source row))
                     (program/overrides database))]
     (if (or (:seon.db/invalid-read overrides) (:seon.schema/expected-value overrides))
       overrides
       (function-doc-map database row metadata
                         (boolean (some #{(:seon.fn/sym row)} overrides))))))
  ([database row metadata overridden?]
   (merge (when-let [doc (:doc metadata)]
            (docstring-parts doc))
          {:arglists (if-let [arglists (:arglists metadata)]
                       (agent-arglists database (:seon.fn/sym row) arglists)
                       (declaration-absent :seon.fn/arglists))}
          (agent-documentation-contract database row)
          (when overridden?
            {:seon.schema.admission/note
             "Accepted database override for SCI; JVM callers retain the compiled definition until write-back and reload."}))))

(defn directory-value
  "Return current public function summaries and declared schemas for a namespace."
  {:malli/schema [:=> [:cat :seon.db/db :symbol :boolean] [:or :seon.repl/directory :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [database namespace-name present?]
  (let [namespace-row (db/pull database
                             '[:seon.ns/name
                               {:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}
                               {:seon.schema/_ns [:seon.schema/key :seon.schema/form]}]
                             [:seon.ns/name namespace-name])
        functions (program-documentation database namespace-name)
        read-arglists (arglists-reader database namespace-name)]
    (cond
      (or (:seon.db/invalid-read namespace-row) (:seon.schema/expected-value namespace-row)) namespace-row
      (or (:seon.db/invalid-read functions) (:seon.schema/expected-value functions)) functions
      (or (:seon.ns/name namespace-row) present? (seq functions))
      (let [functions (sort-by (juxt #(get % :seon.fn/doc-order Long/MAX_VALUE)
                                    :seon.fn/sym) functions)]
        ;; `dir` is a macro, so its call names no producer; the directory
        ;; carries its own pair (the render law's master override).
        {:seon.render/ai 'seon.repl/render-directory-ai
         :seon.render/html 'seon.repl/render-directory-html
         :schemas (into (into (sorted-map) (mapcat #(documentation-schemas database %)) functions)
                        (map (fn [row]
                               [(:seon.schema/key row)
                                (edn/read-string (:seon.schema/form row))]))
                        (:seon.schema/_ns namespace-row))
         :functions (mapv (fn [row]
                            (merge {:sym (:seon.fn/sym row)
                                    :arglists (if-let [arglists (:seon.fn/arglists row)]
                                                (agent-arglists database (:seon.fn/sym row) (read-arglists arglists))
                                                (declaration-absent :seon.fn/arglists))}
                                   (when-let [doc (:seon.fn/doc row)]
                                     {:doc (:summary (docstring-parts doc))})
                                   (agent-documentation-contract database row)))
                          functions)})
      :else (documentation-unavailable namespace-name))))

(defn reference-in
  "The qualified symbol of the Var an authored symbol resolves to in the calling
  SCI namespace, else the authored syntax unchanged."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.sci.eval/authored-reference] :seon.sci.eval/authored-reference]}
  [ctx authored]
  (if-let [resolved (and (symbol? authored) (sci/resolve ctx authored))]
    (sci/var->symbol resolved)
    authored))

(defn schema-documentation
  "The declared form of the schema named `key`, with its description property."
  {:malli/schema [:=> [:cat :seon.db/db :qualified-keyword :seon.sci.eval/authored-reference]
                  [:or :seon.sci.eval/schema-doc :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [database key authored]
  (let [row (db/pull database [:seon.schema/key :seon.schema/form] [:seon.schema/key key])]
    (cond
      (db/read-refusal? row) row
      (not (:seon.schema/form row)) (documentation-unavailable authored)
      :else
      (let [form (edn/read-string (:seon.schema/form row))
            description (when (and (vector? form) (map? (second form))) (:description (second form)))]
        (cond-> {:key key :form form}
          description (assoc :description description))))))

(defn- namespace-documentation
  "The documentation of the namespace `namespace-name`: its docstring parts and
  public names, from the program entity and the SCI namespace."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/db :symbol :seon.sci.eval/authored-reference]
                  [:or :seon.sci.eval/namespace-doc :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [ctx database namespace-name authored]
  (let [row (db/pull database [:seon.ns/name :seon.ns/doc] [:seon.ns/name namespace-name])
        functions (when-not (db/read-refusal? row)
                    (program-documentation database namespace-name))
        sci-ns (sci/find-ns ctx namespace-name)]
    (cond
      (db/read-refusal? row) row
      (db/read-refusal? functions) functions
      (not (or sci-ns (:seon.ns/name row) (seq functions))) (documentation-unavailable authored)
      :else
      (let [doc (or (:seon.ns/doc row) (:doc (meta sci-ns)))
            scoped (when sci-ns
                     (map #(symbol (str namespace-name) (str %))
                          (keys (sci.namespaces/sci-ns-publics* ctx sci-ns))))]
        (merge (when doc (docstring-parts doc))
               {:publics (vec (sort (distinct (concat (map :seon.fn/sym functions) scoped))))})))))

(defn documentation-value
  "Read documentation for a public function, SCI Var, named override, schema key or namespace."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/db :seon.sci.eval/authored-reference :seon.sci.eval/authored-reference]
                  [:or :map :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [ctx database authored resolved]
  (cond
    (qualified-keyword? resolved) (schema-documentation database resolved authored)
    (not (symbol? resolved)) (documentation-unavailable authored)
    (namespace resolved)
    (let [row (db/pull database
                       program-documentation-selector
                       [:seon.fn/sym resolved])
          metadata (meta (sci/resolve ctx resolved))
          overrides (when (= :agent (:seon.schema.admission/source row))
                      (program/overrides database))
          overridden? (boolean (some #{resolved} overrides))]
      (cond
        (or (:seon.db/invalid-read row) (:seon.schema/expected-value row)) row
        (or (:seon.db/invalid-read overrides) (:seon.schema/expected-value overrides)) overrides
        (:seon.fn/sym row)
        (function-doc-map database row metadata overridden?)
        (or (:doc metadata) (:arglists metadata))
        (merge (when-let [doc (:doc metadata)] (docstring-parts doc))
               (when-let [arglists (:arglists metadata)] {:arglists arglists}))
        :else (documentation-unavailable authored)))
    :else (namespace-documentation ctx database resolved authored)))

(defn- program-doc-var
  "Resolve in the calling SCI namespace; read facts when the form executes."
  {:malli/schema [:=> [:cat] :seon.schema/value]}
  []
  (sci/new-macro-var
   'doc
   (fn [_form _env authored]
     `(let [authored# '~authored]
        (seon.sci.lookup/documentation-value
         (seon.db/db) authored# (seon.sci.lookup/reference-in authored#))))
   {:ns (sci/create-ns 'clojure.repl)}))

(defn- program-dir-var
  "Read current facts through the calling evaluation's database custody."
  []
  (sci/new-macro-var
   'dir
   (fn [_form _env namespace-name]
     `(seon.sci.lookup/directory-value
       (seon.db/db) '~namespace-name
       (boolean (clojure.core/find-ns '~namespace-name))))
   {:ns (sci/create-ns 'clojure.repl)}))

(defn stored-source
  "The stored source of the program declaration named `qualified`, or nil.

  Each program family's shape names its identity and source attributes
  (`seon.program/shapes-in`); the families whose identity schema admits
  `qualified` are asked, and the first holding it answers."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:maybe :string]]}
  [database qualified]
  (let [projection (or (db/carried-projection database)
                       (schema/projection-from-database database))
        registry (:seon.schema.projection/registry projection)]
    (some (fn [[identity-attribute {source-attribute :seon.program/source-attribute}]]
            (when (m/validate (m/schema identity-attribute {:registry registry}) qualified)
              (let [source (db/q '[:find ?source . :in $ ?identity ?value ?attribute
                                   :where [?entity ?identity ?value] [?entity ?attribute ?source]]
                                 database identity-attribute qualified source-attribute)]
                (if (:seon.db/invalid-read source)
                  (throw (ex-info (:seon.error/message source) source))
                  source))))
          (program/shapes-in projection))))

(defn- stored-source-fn
  "`clojure.repl/source-fn` for the evaluating context: SCI's `source` macro
  calls it (`reference-code/sci/src/sci/impl/namespaces.cljc:2436`); the
  symbol resolves in the calling SCI namespace; the stored row answers, else
  the host's loaded Var reads its pinned file from the classpath, else a dependency
  namespace that is not loaded reads its pinned file from the dependency cache's row."
  {:malli/schema [:=> [:cat :symbol] [:maybe :string]]}
  [requested]
  (let [resolved (sci/resolve (sci.ctx-store/get-ctx) requested)
        {var-ns :ns var-name :name} (meta resolved)
        qualified (if resolved (symbol (str var-ns) (str var-name)) requested)]
    (or (stored-source (db/db) qualified)
        (when (namespace qualified)
          (or (repl/source-fn qualified)
              (::analyzer/source (analyzer/unloaded-definition qualified)))))))

(defn install-program-doc!
  "Install REPL macros without capturing program facts or a database value."
  [ctx _db _projection]
  (let [doc-var (program-doc-var)
        dir-var (program-dir-var)]
    (sci/add-namespace! ctx 'clojure.repl
                        {'doc doc-var 'dir dir-var
                         'source-fn (sci/new-var 'source-fn stored-source-fn
                                                 {:ns (sci/create-ns 'clojure.repl)})})
    (sci/add-namespace! ctx 'clojure.core
                        {'doc doc-var 'dir dir-var
                         'deftest (sci/resolve ctx 'clojure.test/deftest)
                         'is (sci/resolve ctx 'clojure.test/is)})
    ctx))
