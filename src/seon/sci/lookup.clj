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
            [sci.lang]
            [sci.impl.namespaces :as sci.namespaces]
            [seon.call-preparation :as call-preparation]
            [seon.db :as db]
            [seon.error.refusal]
            [seon.fn.analyzer :as analyzer]
            [seon.program :as program]
            [seon.render.schema :as render.schema]
            [seon.ref :as ref]
            [seon.schema :as schema]
            [seon.sci.reader :as reader]))

;;; clojure.test over the evaluating context's namespaces. The agent's
;;; clojure.test is the host namespace copied in; its report runs as it is,
;;; while these entries find SCI namespaces through the evaluating ctx
;;; (`sci.ctx-store`, bound by `sci/eval-form`) as babashka's SCI-side copy
;;; does (reference-code/babashka/src/babashka/impl/clojure/test.clj:750-790),
;;; and every test Var runs through the agent's one test path, `my.test/check`,
;;; which records its result on the agent's branch.

(defn- test-symbol
  "The qualified name of one test Var in the evaluating context."
  {:malli/schema [:=> [:cat :seon.test/var] :qualified-symbol]}
  [test-var]
  (let [{namespace-object :ns var-name :name} (meta test-var)]
    (symbol (str namespace-object) (str var-name))))

(defn- agent-test-vars
  "Run `test-vars` and answer the recorded run, nil when none is recorded.
  A test the database holds runs by name through `my.test/check` on the agent's branch
  (an SCI test Var's metadata is the base's stub, without `:test`); a deftest
  this context defined that the database does not hold yet has no evidence to
  record and runs in place. Assertion counts reach the bound report counters."
  {:malli/schema [:=> [:cat [:sequential :seon.schema/value]]
                  [:or :nil :seon.test/run-result :seon.error/value]]}
  [test-vars]
  (let [named (mapv test-symbol test-vars)
        recorded (set (db/q (db/db) '[:find [?s ...] :in $ [?s ...] :where [_ :seon.test/sym ?s]] named))
        in-place (filterv #(and (not (recorded (test-symbol %))) (:test (meta %))) test-vars)]
    (when (seq in-place)
      (clojure.test/test-vars in-place))
    (when-let [names (seq (filterv recorded named))]
      (let [check @(sci/resolve (sci.ctx-store/get-ctx) 'my.test/check)
            run (check {:seon.test/identities (set names)})
            results (:seon.test/results run)]
        (when (and clojure.test/*report-counters* (seq results))
          (dosync
           (commute clojure.test/*report-counters*
                    #(merge-with + % {:test (count results)
                                      :pass (transduce (map :seon.test/pass-count) + results)
                                      :fail (transduce (map :seon.test/fail-count) + results)
                                      :error (transduce (map :seon.test/error-count) + results)}))))
        run))))

(defn- agent-test-var
  "Run one test Var through the agent's test path; answer the run."
  {:malli/schema [:=> [:cat :seon.test/var] [:or :nil :seon.test/run-result :seon.error/value]]}
  [test-var]
  (agent-test-vars [test-var]))

(defn- agent-run-test-var
  "Run one test Var through the agent's test path; answer the run with its clojure.test summary counts."
  {:malli/schema [:=> [:cat :seon.test/var] [:or :seon.test/run-result [:map-of :keyword [:or :int :keyword]] :seon.error/value]]}
  [test-var]
  (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
    (let [run (agent-test-vars [test-var])]
      (if (and run (not (:seon.test/results run)))
        run
        (merge run (assoc @clojure.test/*report-counters* :type :summary))))))

(defn- agent-test-all-vars
  "Run every test Var interned in one SCI namespace through the agent's test path; answer the run."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "clojure.test names a namespace by symbol or by namespace object.", :gen/elements [nil]}]] [:or :nil :seon.test/run-result :seon.error/value]]}
  [namespace-designator]
  (let [ctx (sci.ctx-store/get-ctx)]
    (agent-test-vars
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

(defn- agent-record-fixtures
  "Hold `fixtures` as the current SCI namespace's `fixture-type` fixtures: in its
  metadata, where clojure.test/test-vars reads them, and by symbol under
  `:seon.ns/fixtures` in the context's namespace table, which the namespace's
  entity records (`:seon.ns.fixture/binding`). A fixture named by a Var is held
  as that Var, so a redefinition reaches it; an anonymous one is this
  context's alone."
  {:malli/schema [:=> [:cat [:enum :once :each] [:sequential ifn?]] :nil]}
  [fixture-type fixtures]
  (let [namespace-object @sci/ns
        named (into [] (keep (fn [fixture]
                               (when (instance? sci.lang.Var fixture)
                                 (let [{namespace-name :ns var-name :name} (meta fixture)]
                                   (symbol (str namespace-name) (str var-name))))))
                    fixtures)]
    (alter-meta! namespace-object assoc
                 (keyword "clojure.test" (str (name fixture-type) "-fixtures")) (vec fixtures))
    (swap! (:env (sci.ctx-store/get-ctx)) assoc-in
           [:namespaces (sci/ns-name namespace-object) :seon.ns/fixtures fixture-type] named)
    nil))

(defn- agent-use-fixtures
  "The agent's `use-fixtures` macro: each fixture written as a symbol is passed
  as its Var (`agent-record-fixtures`), so the namespace records its name."
  {:malli/schema [:=> [:cat :any :any [:enum :once :each] [:* :any]] [:sequential :any]]}
  [_form _env fixture-type & fixtures]
  (list 'clojure.test/record-fixtures fixture-type
        (mapv #(if (symbol? %) (list 'var %) %) fixtures)))

(defn agent-test-namespace
  "The clojure.test entries that must see the evaluating context's namespaces."
  [sci-namespace]
  (into {'use-fixtures (sci/new-macro-var 'use-fixtures agent-use-fixtures {:ns sci-namespace})}
        (map (fn [[intern-name function]]
               [intern-name (sci/new-var intern-name function {:ns sci-namespace})]))
        {'test-vars agent-test-vars
         'test-var agent-test-var
         'run-test-var agent-run-test-var
         'test-all-vars agent-test-all-vars
         'test-ns agent-test-ns
         'run-tests agent-run-tests
         'do-report agent-do-report
         'record-fixtures agent-record-fixtures}))

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

(defn- uncompilable-contract
  "The error a stored contract that Malli cannot compile shows in its place, naming its function and keeping the
  cause: a contract naming a predicate no longer defined, as a branch's stale row can (S5 run 5c)."
  {:malli/schema [:=> [:cat [:maybe :qualified-symbol] :seon.error/throwable] :seon.sci.eval/uncompilable-contract-error]}
  [sym cause]
  (seon.error.refusal/diagnostic
   {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.sci.eval/program
    :seon.error/operation 'seon.sci.lookup/documentation-contract
    :seon.error/message "The stored contract does not compile; read the function's source and correct its :malli/schema."
    :seon.error/throwable cause
    :seon.sci.eval/uncompilable-contract (or sym 'unknown/function)}))

(defn- compiled-contract
  "`spec`'s `:in` and `:out` forms, compiled under `projection`'s registry."
  {:malli/schema [:=> [:cat :string :seon.schema/projection] [:map [:in :seon.schema/value] [:out :seon.schema/value]]]}
  [spec projection]
  (let [compiled (m/function-schema (edn/read-string spec) {:registry (:seon.schema.projection/registry projection)})
        arities (mapv m/-function-info (m/-function-schema-arities compiled))
        inputs (mapv #(m/form (:input %)) arities)
        outputs (mapv #(m/form (:output %)) arities)]
    ;; one return shared by every arity is said once
    {:in (if (= 1 (count inputs)) (first inputs) inputs)
     :out (if (apply = outputs) (first outputs) outputs)}))

(defn- documentation-contract
  "The stored contract of `row` as `:in` and `:out` forms; a contract that does not compile shows its error on both
  sides, the rest of the documentation still answers; an absent contract is `:seon.sci.eval/missing-declaration`."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] :map]}
  [database row]
  (if-let [spec (:seon.fn/spec row)]
    (let [projection (or (db/carried-projection database)
                         (let [failure (db/projection-fallback
                                        'seon.sci.lookup/documentation-contract)]
                           (throw (ex-info (:seon.error/message failure) failure))))]
      (schema/projection-cache-value
       projection [:seon.sci.eval/documentation-contract spec]
       (fn []
         ;; the one translation: Malli throws compiling a form whose predicate does not resolve, and documentation
         ;; shows that as the error it is beside what it can show
         (try (compiled-contract spec projection)
              (catch clojure.lang.ExceptionInfo cause
                (let [error (uncompilable-contract (:seon.fn/sym row) cause)]
                  {:in error :out error}))))))
    ;; an uncontracted function declares neither side: one typed fact says so, never an empty contract and never
    ;; an error map per side
    {:seon.sci.eval/missing-declaration :seon.fn/spec}))

(defn- opened-argument
  "The argument of input `form` at supplied-entry `position`, as `schemas` names it: its index in `form`, its schema
  key when a request map is named by its registered key, and the argument's form."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :seon.schema/definition] :seon.schema/value :int]
                  [:map [:index :int] [:key {:optional true} :qualified-keyword] [:argument :seon.schema/value]]]}
  [schemas form position]
  (let [index (+ (if (map? (second form)) 2 1) position)
        named (get form index)]
    (cond-> {:index index :argument (if (keyword? named) (get schemas named named) named)}
      (keyword? named) (assoc :key named))))

(defn- contract-arities
  "`contract`'s input forms, one per arity."
  {:malli/schema [:=> [:cat :map :map] [:vector :seon.schema/value]]}
  [row contract]
  (let [spec (:seon.fn/spec row)]
    ;; a contract that did not compile holds one error, not one input per arity
    (cond (nil? spec) []
          (and (= :function (first (edn/read-string spec))) (not (map? (:in contract)))) (:in contract)
          :else [(:in contract)])))

(defn- agent-documentation-contract
  "The stored contract as an agent calls it: a request map's runtime-supplied entries left out and `:supplied`
  naming them."
  {:malli/schema [:=> [:cat :seon.db/database-value :map]
                  [:or :map :seon.db/error-result]]}
  [database row]
  (let [entries (call-preparation/supplied-map-entries database (:seon.fn/sym row))
        contract (documentation-contract database row)
        schemas (documentation-schemas database row)
        arities (contract-arities row contract)]
    (if (or (:seon.db/invalid-read entries) (:seon.schema/expected-value entries))
      entries
      (let [inputs
            (mapv (fn [order input]
                    (reduce (fn [form [position supplied]]
                              (let [{:keys [index argument]} (opened-argument schemas form position)
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

(defn- opened-schema-keys
  "The schema keys `agent-documentation-contract` shows in `:in` with their runtime-supplied entries left out: a
  schema is cited once, so `:schemas` does not repeat them."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:set :qualified-keyword]]}
  [database row]
  (let [entries (call-preparation/supplied-map-entries database (:seon.fn/sym row))
        schemas (documentation-schemas database row)
        arities (contract-arities row (documentation-contract database row))]
    (if (or (:seon.db/invalid-read entries) (:seon.schema/expected-value entries))
      #{}
      (into #{}
            (keep (fn [[order position]]
                    (let [{named :key argument :argument} (opened-argument schemas (nth arities order) position)]
                      (when (and named (vector? argument) (= :map (first argument))) named))))
            entries))))

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

(defn- schema-descriptions
  "Each schema `row`'s contract names, as its description, else its form; one `:in` already shows is left out."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:map-of :qualified-keyword :map]]}
  [database row]
  (let [opened (opened-schema-keys database row)]
    (into (sorted-map)
          (comp (remove (comp opened key))
                (map (fn [[key form]]
                       [key (if-let [text (render.schema/description form)] {:description text} {:form form})])))
          (documentation-schemas database row))))

(defn- function-doc-map
  "The documentation of one program row: its doc and arglists, the stored
  contract with the schemas it names and, for an accepted override, its
  JVM-boundary note."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :boolean] [:or :map :seon.db/error-result]]}
  [database row overridden?]
  (let [arglists (when-let [text (:seon.fn/arglists row)]
                   ((arglists-reader database (symbol (namespace (:seon.fn/sym row)))) text))]
    (merge (when-let [doc (:seon.fn/doc row)]
             (ref/docstring doc))
           {:arglists (if arglists
                        (agent-arglists database (:seon.fn/sym row) arglists)
                        {:seon.sci.eval/missing-declaration :seon.fn/arglists})}
           (agent-documentation-contract database row)
           (when-let [schemas (not-empty (schema-descriptions database row))]
             {:schemas schemas})
           (when overridden?
             {:seon.schema.admission/note
              "Accepted database override for SCI; JVM callers retain the compiled definition until write-back and reload."}))))

(defn function-documentation
  "The documentation of the program function `sym`, read from the branch's own
  program row alone (no SCI context): doc parts, agent arglists, contract and the schemas
  it names. `documentation-unavailable` when no row declares it."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol]
                  [:or :map :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [database sym]
  (let [row (db/pull database program-documentation-selector [:seon.fn/sym sym])
        overrides (when (= :agent (:seon.schema.admission/source row))
                    (program/overrides database))]
    (cond
      (or (:seon.db/invalid-read row) (:seon.schema/expected-value row)) row
      (or (:seon.db/invalid-read overrides) (:seon.schema/expected-value overrides)) overrides
      (:seon.fn/sym row) (function-doc-map database row (boolean (some #{sym} overrides)))
      :else (ref/documentation-unavailable sym))))

(defn directory-value
  "Return current public function summaries and declared schemas for a namespace."
  {:malli/schema [:=> [:cat :seon.db/db :symbol :boolean] [:or :seon.repl/directory :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [database namespace-name present?]
  (let [namespace-row (db/pull database
                             '[:seon.ns/name
                               {:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}
                               {:seon.schema/_ns [:seon.schema/key :seon.schema/form]}
                               {:seon.test/_ns [:seon.test/sym]}]
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
        (cond->
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
                                                {:seon.sci.eval/missing-declaration :seon.fn/arglists})}
                                   (when-let [doc (:seon.fn/doc row)]
                                     {:doc (:summary (ref/docstring doc))})
                                   (agent-documentation-contract database row)))
                          functions)}
          (seq (:seon.test/_ns namespace-row))
          (assoc :tests (vec (sort-by str (map :seon.test/sym (:seon.test/_ns namespace-row)))))))
      :else (ref/documentation-unavailable namespace-name))))

(defn reference-in
  "The qualified symbol of the Var an authored symbol resolves to in the calling
  SCI namespace, else the authored syntax unchanged."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.sci.eval/authored-reference] :seon.sci.eval/authored-reference]}
  [ctx authored]
  (if-let [resolved (and (symbol? authored) (sci/resolve ctx authored))]
    (sci/var->symbol resolved)
    authored))

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
      (not (or sci-ns (:seon.ns/name row) (seq functions))) (ref/documentation-unavailable authored)
      :else
      (let [doc (or (:seon.ns/doc row) (:doc (meta sci-ns)))
            ;; A program namespace's SCI table also holds the clojure.core Vars
            ;; installed for its interpreted callers: its publics are its
            ;; declared functions, the set `dir` answers. A library namespace
            ;; has no declarations, so its SCI table answers.
            publics (concat (map :seon.fn/sym functions)
                            (when (and sci-ns (not (:seon.ns/name row)))
                              (map #(symbol (str namespace-name) (str %))
                                   (keys (sci.namespaces/sci-ns-publics* ctx sci-ns)))))]
        (merge (when doc (ref/docstring doc))
               {:publics (vec (sort (distinct publics)))})))))

(defn documentation-value
  "Read documentation for a public function, SCI Var, named override, schema key or namespace."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/db :seon.sci.eval/authored-reference :seon.sci.eval/authored-reference]
                  [:or :map :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [ctx database authored resolved]
  (cond
    (qualified-keyword? resolved) (ref/schema-documentation database resolved authored)
    (not (symbol? resolved)) (ref/documentation-unavailable authored)
    (namespace resolved)
    (let [found (function-documentation database resolved)]
      (if (:seon.sci.eval/documentation-unavailable found)
        (let [metadata (meta (sci/resolve ctx resolved))]
          (if (or (:doc metadata) (:arglists metadata))
            (merge (when-let [doc (:doc metadata)] (ref/docstring doc))
                   (when-let [arglists (:arglists metadata)] {:arglists arglists}))
            (ref/documentation-unavailable authored)))
        found))
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
