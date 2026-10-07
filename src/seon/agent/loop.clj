(ns seon.agent.loop
  "The agent loop's context producers (spec agent-loop §3), not yet wired into
  any pass: the system message every call carries, and the context a task
  seeds. Both read one database value and answer data or text; neither writes.

  The task context is a vector of units. Each unit is a requery form an agent
  can run at its REPL, the value that form answers on this database, and why
  the unit is shown. `context-text` prints the units as REPL exchanges; every
  value goes through the value printer whole."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.instruction :as instruction]
            [seon.db :as db]
            [seon.print :as print]
            [seon.render.value :as render.value]
            [seon.repl :as repl]
            [seon.sci.eval :as sci.eval]
            [seon.task :as task]
            [seon.test :as test]))

;;; SYSTEM MESSAGE: the REPL protocol from `(help)` and each toolkit namespace's
;;; own documentation, the same text on every call of one agent.

(defn- tool-lines
  "One namespace's lines: its summary, then each public function's summary
  and documented example, the call an agent copies."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:vector :string]]}
  [database namespace-name]
  (let [summary (:summary (sci.eval/documentation-value database namespace-name namespace-name))
        functions (map :sym (:functions (sci.eval/directory-value database namespace-name true)))]
    (into [(str namespace-name (when summary (str ": " summary)))]
          (for [sym functions
                :let [{doc :summary example :example} (sci.eval/documentation-value database sym sym)]]
            (str "  " sym (when doc (str ": " doc)) (when example (str "\n    " (str/replace example "\n" "\n    "))))))))

(defn system-message
  "The system message sent with every model call of `agent-id`.

  The REPL protocol `(help)` answers, then each context-relevant namespace's tools
  (`seon.cluster.instruction/toolkit-namespaces`) as their own docs state them.

  Example:
  (seon.agent.loop/system-message (seon.db/db) \"root\")

  ## Costs

  One help read, then per toolkit namespace one documentation pull and one
  directory read; nothing is cached."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :string]}
  [database agent-id]
  (str/join "\n" (concat (:seon.help/lines (bootstrap/help-value database agent-id))
                         [""]
                         (mapcat #(tool-lines database %) (instruction/toolkit-namespaces database)))))

;;; TASK CONTEXT: the task, then what its refs reach.

(defn- unit
  "One context unit: why it is shown, the requery form and its value."
  {:malli/schema [:=> [:cat :string :seon.schema/value :seon.schema/value] :seon.agent.loop/unit]}
  [why form value]
  {:seon.agent.loop/why why :seon.agent.loop/form form :seon.agent.loop/value value})

(def ^:private task-selector
  [:seon.task/id :seon.task/ns :seon.task/name :seon.task/kind :seon.task/intent
   :seon.task/must-read :seon.task/evidence])

(defn- contract-keys
  "The registered schema keys `functions`' declared contracts name, in order,
  each once; property keys and unregistered names are not schemas."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :qualified-symbol]] [:vector :qualified-keyword]]}
  [database functions]
  (let [named (distinct (for [sym functions
                              :let [spec (:seon.fn/spec (db/pull database [:seon.fn/spec] [:seon.fn/sym sym]))]
                              :when spec
                              k (tree-seq coll? seq (edn/read-string spec))
                              :when (qualified-keyword? k)]
                          k))
        registered (set (when (seq named)
                          (db/q '[:find [?k ...] :in $ [?k ...] :where [_ :seon.schema/key ?k]] database (vec named))))]
    (filterv registered named)))

(defn- test-units
  "A proof test's recorded status, its recorded failures when it has any, and
  its source."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] [:vector :seon.agent.loop/unit]]}
  [database proof]
  (let [failures-query '[:find [(pull ?f [:seon.test.failure/type :seon.test.failure/message
                                          :seon.test.failure/expected :seon.test.failure/actual
                                          :seon.test.failure/testing-contexts :seon.test.failure/line]) ...]
                         :in $ ?s :where [?t :seon.test/sym ?s] [?f :seon.test.failure/test ?t]]
        failures (db/q failures-query database proof)]
    (cond-> [(unit "A proof test: the task is done when every proof is green on your branch. Its latest recorded result."
                   (list 'seon.test/recorded-result '(seon.db/db) (list 'quote proof))
                   (test/recorded-result database proof))]
      (seq failures)
      (conj (unit "The proof's recorded failing assertions."
                  (list 'seon.db/q (list 'quote failures-query) '(seon.db/db) (list 'quote proof))
                  (vec failures)))
      :always
      (conj (unit "The proof's source: what it asserts."
                  (list 'clojure.repl/source-fn (list 'quote proof)) (sci.eval/stored-source database proof))))))

(defn- function-units
  "A function's documentation (summary, arglists, contract) and its stored source."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] [:vector :seon.agent.loop/unit]]}
  [database sym]
  [(unit "A function the task names: its documentation and contract."
         (list 'doc sym) (sci.eval/documentation-value database sym sym))
   (unit "Its stored source, the code to change."
         (list 'clojure.repl/source-fn (list 'quote sym)) (sci.eval/stored-source database sym))])

(defn- schema-unit
  "A schema key's stored form."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword] :seon.agent.loop/unit]}
  [database schema-key]
  (unit "A schema the contracts above name: the shape of the data in and out."
        (list 'seon.db/pull '(seon.db/db) [:seon.schema/form] [:seon.schema/key schema-key])
        (db/pull database [:seon.schema/form] [:seon.schema/key schema-key])))

(defn- related-units
  "Other tasks naming the same functions, and recorded errors whose operation
  is one of them; each unit only when it has members."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle [:vector :qualified-symbol]]
                  [:vector :seon.agent.loop/unit]]}
  [database handle functions]
  (let [tasks-query '[:find [(pull ?t [:seon.task/id :seon.task/ns :seon.task/name :seon.task/intent]) ...]
                      :in $ [?f ...] ?h
                      :where [?t :seon.task/must-read ?f] [?t :seon.task/id ?id] [(not= ?id ?h)]]
        errors-query '[:find [(pull ?e [:seon.error/signature :seon.error/operation :seon.error/message
                                        :seon.error/occurrence-count]) ...]
                       :in $ [?op ...]
                       :where [?e :seon.error/operation ?op] [?e :seon.error/signature _]]
        tasks (when (seq functions) (db/q tasks-query database functions handle))
        errors (when (seq functions) (db/q errors-query database functions))]
    (cond-> []
      (seq tasks) (conj (unit "Other tasks naming the same functions: work that may overlap or conflict."
                              (list 'seon.db/q (list 'quote tasks-query) '(seon.db/db) functions handle) (vec tasks)))
      (seq errors) (conj (unit "Recorded errors raised by these functions."
                               (list 'seon.db/q (list 'quote errors-query) '(seon.db/db) functions) (vec errors))))))

(defn task-context
  "The context task `handle` seeds, as units.

  The task; its edges (subjects
  and proof tests); each proof's status, recorded failures and source; each
  must-read or subject function's documentation and source; the schemas
  their contracts name; other tasks and recorded errors touching the same
  functions.

  Example:
  (seon.agent.loop/task-context (seon.db/db) \"d6e3b775f474\")

  ## Costs

  One task pull and its edges, then per proof two reads and a source query,
  per function a documentation pull and a source query, per schema one pull,
  and two queries over the functions: proportional to what the task names."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] [:vector :seon.agent.loop/unit]]}
  [database handle]
  (let [the-task (db/pull database task-selector [:seon.task/id handle])
        edges (task/task-edges database handle)
        functions (vec (distinct (concat (:seon.task/must-read the-task) (sort (:seon.task/subjects edges)))))
        schemas (contract-keys database functions)]
    (vec (concat
          [(unit "Your task: what to change and why."
                 (list 'seon.db/pull '(seon.db/db) task-selector [:seon.task/id handle]) the-task)
           (unit "The task's edges: the proof tests that define done, the subjects it names, and who must read them."
                 (list 'seon.task/task-edges '(seon.db/db) handle) edges)]
          (mapcat #(test-units database %) (sort (:seon.task/proofs edges)))
          (mapcat #(function-units database %) functions)
          (map #(schema-unit database %) schemas)
          (related-units database handle functions)))))

(defn- unit-text
  "One unit as a REPL exchange: the form, then its value printed whole by the
  value printer; a string value (source) prints as its text."
  {:malli/schema [:=> [:cat :seon.agent.loop/unit :boolean] :string]}
  [{why :seon.agent.loop/why form :seon.agent.loop/form value :seon.agent.loop/value} annotated?]
  (let [shown (if (string? value)
                value
                (render.value/render-ai {:seon.render/value (print/full value)
                                         :seon.render.value/root (str "context-" (hash form))}))]
    (str (when annotated? (str ";; why: " why " (~" (tokens/estimate (str shown)) " tokens)\n"))
         "user=> " (repl/source-text form) "\n" shown "\n")))

(defn context-text
  "The units as the text an agent reads, in order.

  `annotated?` precedes each with why it is there and its estimated tokens.

  Example:
  (seon.agent.loop/context-text (seon.agent.loop/task-context (seon.db/db) \"d6e3b775f474\") false)"
  {:malli/schema [:=> [:cat [:vector :seon.agent.loop/unit] :boolean] :string]}
  [units annotated?]
  (str/join "\n" (map #(unit-text % annotated?) units)))
