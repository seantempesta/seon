(ns seon.render.ns
  "The program graph's node pairs: namespaces, their bindings, functions,
  schemas and tasks, each rendered as one node of the agent's context graph.

  Distance is inverse detail: zero names the node; one and beyond show its
  content, with its neighbours as names. The walk renders the neighbours."
  (:require [clojure.string :as str]
            [seon.error.refusal]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.render.route :as route]))

(defn- first-doc-line
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [doc]
  (some-> doc str/split-lines first str/trim not-empty))

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
  "One task as data: its id, name, kind and namespace; beyond distance zero
  its intent and must-reads. Its subjects and proofs are its related data."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.task/view]}
  [unit]
  (let [task (or (:seon.render/value unit) unit)
        named (select-keys task [:seon.task/id :seon.task/name :seon.task/kind :seon.task/ns])]
    (if (zero? (node-distance unit))
      named
      ;; A pulled cardinality-many value is a vector (Datahike's pull
      ;; shape); must-read is declared a set, so it is shown as one.
      (cond-> (merge named (select-keys task [:seon.task/intent]))
        (seq (:seon.task/must-read task)) (assoc :seon.task/must-read (set (:seon.task/must-read task)))))))

(defn task-form
  "The read of a task's edges. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [unit]
  (list 'seon.issue/task-edges '(seon.db/db) (:seon.task/id (or (:seon.render/value unit) unit))))

(defn task-html
  "Render one task as a card: intent, must-reads, evidence. Its subjects and
  proofs are its related data."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [{:seon.task/keys [id kind intent must-read evidence] task-name :seon.task/name task-ns :seon.task/ns}
        (or (:seon.render/value unit) unit)
        entity (fn [label items]
              [:div [:dt label] [:dd (if (seq items) (into [:ul] (map #(vector :li [:code (str %)])) (sort-by str items)) "None")]])]
    [:article {:class "seon-family-entry seon-task-entry"}
     [:header [:p {:class "seon-kicker"} (str "Task · " (name kind) " · " task-ns)] [:h3 task-name " " [:code id]]]
     [:p {:class "seon-task-intent"} intent]
     (when (pos? (node-distance unit))
       [:dl (entity "Must read" must-read) (entity "Evidence" evidence)])]))

(defn function-ai
  "A function node as data: its name and doc line; beyond distance zero its
  authored source as its lines (text the printer shows unescaped, layout
  kept) and its contract where the source does not carry it. What it calls
  and what calls it are its related data."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render.ns/function-view]}
  [unit]
  (let [{function-name :seon.fn/sym doc :seon.fn/doc source :seon.fn/source spec :seon.fn/spec}
        (or (:seon.render/value unit) unit)
        named (cond-> {:seon.fn/sym function-name}
                (first-doc-line doc) (assoc :seon.fn/doc (first-doc-line doc)))]
    (cond-> named
      (and source (pos? (node-distance unit))) (assoc :seon.render.ns/source-lines (str/split-lines source))
      (and spec (pos? (node-distance unit)) (not (and source (str/includes? source ":malli/schema"))))
      (assoc :seon.fn/spec spec))))

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

(defn- public-functions
  "The public functions a namespace declares, by name: one reverse pull from
  the namespace row, unlimited, so a large namespace is read whole. A query joining every function row
  exceeded the read budget even for an empty namespace (0.2 ms here, measured
  on default 2026-09-26)."
  {:malli/schema [:=> [:cat :seon.render/unit :symbol] [:or [:vector :symbol] :seon.db/invalid-read-error]]}
  [unit namespace-name]
  (let [found (db/pull (:seon.db/db unit)
                       [{[:seon.fn/_ns :limit nil] [:seon.fn/sym :seon.fn/private?]}]
                       [:seon.ns/name namespace-name])]
    (if (db/read-refusal? found)
      found
      (into [] (comp (filter #(false? (:seon.fn/private? %)))
                     (map (comp symbol name :seon.fn/sym)))
            (:seon.fn/_ns found)))))

(defn- directory
  "The namespace's public functions as `dir` reads them, as columns and rows:
  name, arglists, doc line and contract."
  {:malli/schema [:=> [:cat :seon.render/unit :symbol]
                  [:or [:map [:seon.repl/columns [:vector :keyword]] [:seon.repl/rows [:vector [:vector :seon.schema/value]]]]
                   :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [unit namespace-name]
  (let [found (sci.eval/directory-value (:seon.db/db unit) namespace-name true)
        columns [:sym :arglists :doc :in :out]]
    (if (:functions found)
      {:seon.repl/columns columns
       :seon.repl/rows (mapv #(mapv % columns) (:functions found))}
      found)))

(defn render-ai
  "A namespace node as data: its name and doc line; beyond distance zero its
  requires and its public functions with their contracts, as `dir` reads them."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render.ns/namespace-view :seon.db/error-result
                   :seon.sci.eval/documentation-unavailable-error]]}
  [unit]
  (let [{namespace-name :seon.ns/name doc :seon.ns/doc requires :seon.ns/requires}
        (or (:seon.render/value unit) unit)
        named (cond-> {:seon.ns/name namespace-name}
                (first-doc-line doc) (assoc :seon.ns/doc (first-doc-line doc)))
        listed (when (pos? (node-distance unit)) (directory unit namespace-name))]
    (cond
      (nil? listed) named
      (:seon.error/at listed) listed
      :else (merge named {:seon.ns/requires (vec (sort requires))} listed))))

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
        members (when (pos? (node-distance unit)) (public-functions unit namespace-name))
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
