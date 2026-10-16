(ns seon.render.ns
  "The program graph's node pairs: namespaces, their bindings, functions,
  schemas and tasks, each rendered as one node of the agent's context graph.

  Distance is inverse detail: zero names the node; one and beyond show its
  content, with its neighbours as names. The walk renders the neighbours."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.error.refusal]
            [seon.db :as db]
            [seon.ref :as ref]
            [seon.sci.reader :as reader]
            [seon.task :as task]
            [seon.render.route :as route]))

(defn- first-doc-line
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [doc]
  (let [paragraph (->> (str/split-lines (or doc ""))
                       (map str/trim)
                       (drop-while str/blank?)
                       (take-while (complement str/blank?))
                       (str/join " "))
        end (str/index-of paragraph ". ")]
    (not-empty (if end (subs paragraph 0 (inc end)) paragraph))))

(defn render-alias-ai
  "One alias binding as data: the local symbol and the namespace it names,
  the `:as` half of a require libspec."
  {:malli/schema [:=> [:cat :seon.ns.alias/binding] :seon.ns.alias/binding]}
  [entry]
  (select-keys entry [:seon.ns.alias/local :seon.ns.alias/target-ns]))

(defn render-alias-form
  "The `dir` of the aliased namespace: how to look there. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.ns.alias/binding] :seon.render/read-form]}
  [{target :seon.ns.alias/target-ns}]
  (list 'dir target))

(defn render-alias-html
  "Render one namespace alias binding with its local and target roles explicit."
  {:malli/schema [:=> [:cat :seon.ns.alias/binding] :seon.render/hiccup]}
  [{local :seon.ns.alias/local target :seon.ns.alias/target-ns}]
  [:article {:class "seon-family-entry seon-namespace-alias-entry"}
   [:p {:class "seon-kicker"} "Namespace alias"]
   [:p [:code (str local)] " → " [:code (str target)]]])

(defn render-refer-ai
  "One refer binding as data: the bare local symbol and the Var it names,
  the `:refer` half of a require libspec."
  {:malli/schema [:=> [:cat :seon.ns.refer/binding] :seon.ns.refer/binding]}
  [entry]
  (select-keys entry [:seon.ns.refer/local :seon.ns.refer/target-ns :seon.ns.refer/target-name]))

(defn render-refer-form
  "The `doc` of the referred Var. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.ns.refer/binding] :seon.render/read-form]}
  [{target-ns :seon.ns.refer/target-ns target-name :seon.ns.refer/target-name}]
  (list 'doc (symbol (str target-ns) (str target-name))))

(defn render-refer-html
  "Render one refer binding with its local and target roles explicit."
  {:malli/schema [:=> [:cat :seon.ns.refer/binding] :seon.render/hiccup]}
  [{local :seon.ns.refer/local
    target-ns :seon.ns.refer/target-ns
    target-name :seon.ns.refer/target-name}]
  [:article {:class "seon-family-entry seon-namespace-refer-entry"}
   [:p {:class "seon-kicker"} "Namespace refer"]
   [:p [:code (str local)] " ← " [:code (str target-ns "/" target-name)]]])

(defn render-import-ai
  "One import binding as data: the bare class symbol and the JVM class it names."
  {:malli/schema [:=> [:cat :seon.ns.import/binding] :seon.ns.import/binding]}
  [entry]
  (select-keys entry [:seon.ns.import/local :seon.ns.import/target-class]))

(defn render-import-html
  "Render one import binding with its local and target roles explicit."
  {:malli/schema [:=> [:cat :seon.ns.import/binding] :seon.render/hiccup]}
  [{local :seon.ns.import/local target :seon.ns.import/target-class}]
  [:article {:class "seon-family-entry seon-namespace-import-entry"}
   [:p {:class "seon-kicker"} "Namespace import"]
   [:p [:code (str local)] " → " [:code (str (or target local))]]])

(defn namespace-form
  "Return the ordinary `dir` form for a namespace entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [unit]
  (list 'dir (list 'quote (:seon.ns/name unit))))

;;; ---------------------------------------------------------------------------
;;; Graph nodes: one entity each. Distance 0 names it; distance 1 and beyond
;;; show its content, with its neighbours as names (the walk renders those).
;;; ---------------------------------------------------------------------------

(defn- node-distance
  {:malli/schema [:=> [:cat :seon.render/unit] [:int {:min 0}]]}
  [unit]
  (max 0 (long (get unit :seon.render/distance 1))))

(defn task-ai
  "One task as data: its id, symbol, name and namespace; beyond distance zero its plan, the references it cites
  and, on a bailed attempt's problem task, the verdict that bailed it (`:seon.task/last-verdict`). Its subjects and proofs are its related data."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.task/view]}
  [unit]
  (let [task (or (:seon.render/value unit) unit)
        named (select-keys task [:seon.task/id :seon.task/sym :seon.task/name :seon.task/ns])]
    (if (zero? (node-distance unit))
      named
      ;; A pulled cardinality-many value is a vector (Datahike's pull
      ;; shape); references are declared a set, so they are shown as one.
      (cond-> (merge named (select-keys task [:seon.task/plan]))
        (seq (:seon.task/references task)) (assoc :seon.task/references (set (:seon.task/references task)))
        (:seon.task/verdict task) (assoc :seon.task/last-verdict (edn/read-string (:seon.task/verdict task)))))))

(defn task-form
  "The read of a task's obligations (`seon.task/obligations`). Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [unit]
  (list 'seon.task/obligations '(seon.db/db) (list 'quote (:seon.task/sym (or (:seon.render/value unit) unit)))))

(defn task-html
  "Render one task as a card: plan and the references it cites. Its subjects and
  proofs are its related data."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [{:seon.task/keys [id plan references] task-name :seon.task/name task-ns :seon.task/ns}
        (or (:seon.render/value unit) unit)
        entity (fn [label items]
              [:div [:dt label] [:dd (if (seq items) (into [:ul] (map #(vector :li [:code (str %)])) (sort-by str items)) "None")]])]
    [:article {:class "seon-family-entry seon-task-entry"}
     [:header [:p {:class "seon-kicker"} (str "Task · " task-ns)] [:h3 task-name " " [:code id]]]
     [:p {:class "seon-task-plan"} plan]
     (when (pos? (node-distance unit))
       [:dl (entity "References" references)])]))

;;; The agent's held task (`:seon.task/of-agent`, a derived attachment of the agent): read from the
;;; agent's own world, whose branch holds its assignment.

(defn- held-task
  "The task `unit`'s agent holds, read on the unit's database: its name and
  plan with the read of its proofs offered; nil when it holds none."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :nil :seon.task/held-view :seon.db/invalid-read-error]]}
  [{database :seon.db/db value :seon.render/value}]
  (let [handle (task/agent-task database (:seon.agent/id value))]
    (if (string? handle)
      (let [task (db/pull database [:seon.task/name :seon.task/plan] [:seon.task/id handle])]
        (if (:seon.error/at task)
          task
          (assoc (select-keys task [:seon.task/name :seon.task/plan])
                 :seon.task/id handle :seon.render/operations ['(my.task/current {})])))
      handle)))

(defn held-task-ai
  "The task I hold as data: name and plan, with the read of its proofs offered; nothing when I hold none.

  ## Costs

  One query for my assignment and one pull of the task, both on my branch."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :nil :seon.task/held-view :seon.db/invalid-read-error]]}
  [unit]
  (held-task unit))

(defn held-task-form
  "The read that finds my task and its proofs again. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [_unit]
  (list 'my.task/current {}))

(defn held-task-html
  "Render the task an agent holds: its name and plan."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [held (held-task unit)]
    (if (:seon.task/id held)
      [:section {:class "seon-held-task"}
       [:h3 (:seon.task/name held) " " [:code (:seon.task/id held)]]
       (when (:seon.task/plan held) [:p (:seon.task/plan held)])]
      [:section {:class "seon-held-task"} [:p (or (:seon.error/message held) "No task held.")]])))

(defn function-ai
  "A function node as data: its name and doc line, a library Var's arglists, and the forms that read its doc, its
  source, its callers and the tests reaching it. Its source is one `my.program/source` away; what it calls and what
  calls it are its related data."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render.ns/function-view]}
  [unit]
  (let [{function-name :seon.fn/sym doc :seon.fn/doc source :seon.fn/source arglists :arglists} (or (:seon.render/value unit) unit)
        line (first-doc-line doc)
        subject (list 'quote function-name)]
    (cond-> {:seon.fn/sym function-name
             :seon.render/operations [(list 'doc function-name)
                                      (if source (list 'my.program/source subject) (list 'clojure.repl/source function-name))
                                      (list 'my.program/callers subject) (list 'my.program/tests-reaching subject)]}
      line (assoc :seon.fn/doc line)
      (and arglists (not source)) (assoc :arglists arglists))))

(defn function-html
  "Render a function node: its doc and authored source."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [{function-name :seon.fn/sym doc :seon.fn/doc source :seon.fn/source} (or (:seon.render/value unit) unit)]
    [:article {:class "seon-family-entry seon-function"}
     [:header [:p {:class "seon-kicker"} "Function"]
      [:h3 [:a {:href (route/path ::route/namespace {:namespace (namespace function-name)})} [:code (str function-name)]]]]
     (when-let [line (first-doc-line doc)] [:p line])
     (when (and source (pos? (node-distance unit))) [:pre [:code source]])]))

(def ^:private directory-selector
  [:seon.ns/name
   {:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}
   {[:seon.fn/_ns :limit nil] [:seon.fn/sym :seon.fn/private? :seon.fn/doc :seon.fn/arglists]}])

(defn- public-functions
  "The namespace row pulled once, unlimited, with each function's name,
  visibility, doc and arglists and the namespace's aliases; a refused read
  returns its refusal."
  {:malli/schema [:=> [:cat :seon.render/unit :symbol] [:or :map :seon.db/invalid-read-error]]}
  [unit namespace-name]
  (let [found (db/pull (:seon.db/db unit) directory-selector [:seon.ns/name namespace-name])]
    (if (db/read-refusal? found) found (or found {}))))

(defn- public-rows
  "The held pull's public function rows, sorted by name."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [pulled]
  (->> (:seon.fn/_ns pulled)
       (filter #(false? (:seon.fn/private? %)))
       (sort-by :seon.fn/sym)
       vec))

(defn- read-arglists
  "One arglists text read in the namespace's own aliases; a failed read stays its returned data."
  {:malli/schema [:=> [:cat :symbol :map :string] :any]}
  [namespace-name aliases text]
  (let [read (reader/read {::reader/text text ::reader/ns namespace-name ::reader/aliases aliases
                           :seon.config.eval.result/max-source (count text)})]
    (if (vector? read) (::reader/form (first read)) read)))

(defn- directory
  "The held pull's public functions as columns and rows: name, arglists and
  doc summary."
  {:malli/schema [:=> [:cat :map :symbol]
                  [:map [:seon.repl/columns [:vector :keyword]] [:seon.repl/rows [:vector [:vector :seon.schema/value]]]]]}
  [pulled namespace-name]
  (let [aliases (into {} (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns)) (:seon.ns/aliases pulled))]
    {:seon.repl/columns [:sym :arglists :summary]
     :seon.repl/rows (mapv (fn [{function-name :seon.fn/sym arglists :seon.fn/arglists doc :seon.fn/doc}]
                             [function-name
                              (when arglists (read-arglists namespace-name aliases arglists))
                              (when doc (:summary (ref/docstring doc)))])
                           (public-rows pulled))}))

(defn render-ai
  "A namespace node as data: its name and doc line; beyond distance zero its
  requires and its public functions as name, arglists and doc summary rows."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render.ns/namespace-view :seon.db/error-result
                   :seon.sci.eval/documentation-unavailable-error]]}
  [unit]
  (let [{namespace-name :seon.ns/name doc :seon.ns/doc requires :seon.ns/requires}
        (or (:seon.render/value unit) unit)
        named (cond-> {:seon.ns/name namespace-name}
                (first-doc-line doc) (assoc :seon.ns/doc (first-doc-line doc)))
        pulled (when (pos? (node-distance unit)) (public-functions unit namespace-name))
        listed (when pulled (if (db/read-refusal? pulled) pulled (directory pulled namespace-name)))]
    (cond
      (nil? listed) named
      (db/read-refusal? listed) listed
      :else (merge named (when requires {:seon.ns/requires (vec (sort requires))}) listed))))

(defn- agent-namespace-ref
  "The namespace an agent-namespace unit names: the walked namespace itself,
  else the unit agent's `:seon.agent/namespace`."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe [:or :int [:tuple :keyword :symbol]]]]}
  [{database :seon.db/db agent-id :seon.agent/id :as unit}]
  (let [value (:seon.render/value unit)]
    (cond
      (:seon.ns/name value) [:seon.ns/name (:seon.ns/name value)]
      (int? (:db/id value)) (:db/id value)
      (int? value) value
      agent-id (let [found (db/q '[:find ?n . :in $ ?id :where [?a :seon.agent/id ?id] [?a :seon.agent/namespace ?n]]
                                 database agent-id)]
                 (when (int? found) found)))))

(defn- unassigned
  "The declared refusal for an agent-namespace unit that names no namespace."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.error/value]}
  [unit]
  (seon.error.refusal/diagnostic
   (java.util.Date.) :seon.render/invocation 'seon.render.ns/agent-namespace-ai
   {:seon.error/message "This unit names no namespace: neither the walked namespace nor its agent's assignment."
    :seon.error/member :seon.agent/namespace :seon.error/expected :seon.ns/name
    :seon.error/offending (select-keys unit [:seon.agent/id :seon.render/value])}))

(defn agent-namespace-ai
  "The agent's own namespace as data: its name, doc line, requires, and how
  many definitions it holds; `dir` reads its functions (the form face)."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render.ns/agent-namespace-view :seon.db/error-result :seon.error/value]]}
  [unit]
  (let [reference (agent-namespace-ref unit)
        pulled (when reference
                 (db/pull (:seon.db/db unit)
                          [:seon.ns/name :seon.ns/doc :seon.ns/requires {[:seon.fn/_ns :limit nil] [:db/id]}]
                          reference))
        {namespace-name :seon.ns/name doc :seon.ns/doc requires :seon.ns/requires defs :seon.fn/_ns} pulled]
    (cond
      (nil? reference) (unassigned unit)
      (db/read-refusal? pulled) pulled
      :else
      (cond-> {:seon.ns/name namespace-name
               :seon.ns/requires (vec (sort requires))
               :seon.render.ns/definition-count (count defs)}
        (first-doc-line doc) (assoc :seon.ns/doc (first-doc-line doc))))))

(defn agent-namespace-form
  "The `dir` of the agent's own namespace. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/read-form :seon.db/error-result :seon.error/value]]}
  [unit]
  (let [reference (agent-namespace-ref unit)
        pulled (when reference (db/pull (:seon.db/db unit) [:seon.ns/name] reference))]
    (cond
      (nil? reference) (unassigned unit)
      (db/read-refusal? pulled) pulled
      :else (list 'dir (:seon.ns/name pulled)))))

(defn render-html
  "Render a namespace node: its doc, requires and public functions by name."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/hiccup :seon.db/invalid-read-error]]}
  [unit]
  (let [{namespace-name :seon.ns/name doc :seon.ns/doc requires :seon.ns/requires} (or (:seon.render/value unit) unit)
        pulled (when (pos? (node-distance unit)) (public-functions unit namespace-name))
        members (if (db/read-refusal? pulled) pulled (some->> pulled public-rows (map (comp symbol name :seon.fn/sym))))
        link #(vector :a {:href (route/path ::route/namespace {:namespace (str %)})} [:code (str %)])
        entity (fn [label items] [:div [:dt label] [:dd (if (seq items) (into [:ul] (map #(vector :li %)) items) "None")]])]
    (if (db/read-refusal? members)
      members
      [:article {:class "seon-family-entry seon-namespace-entry"}
       [:header [:p {:class "seon-kicker"} "Namespace"] [:h3 (link namespace-name)]]
       (when-let [line (first-doc-line doc)] [:p {:class "seon-namespace-description"} line])
       (when members
         [:dl (entity "Requires" (map link (sort-by str requires)))
          (entity "Public functions" (map #(vector :code (str %)) (sort-by str members)))])])))
