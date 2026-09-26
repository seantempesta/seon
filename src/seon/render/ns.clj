(ns seon.render.ns
  "The program graph's node pairs: namespaces, their bindings, functions,
  schemas and tasks, each rendered as one node of the agent's context graph.

  Distance is inverse detail: zero names the node; one and beyond show its
  content, with its neighbours as names. The walk renders the neighbours."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.issue :as issue]
            [seon.repl :as repl]
            [seon.render.route :as route]))

;;; ---------------------------------------------------------------------------
;;; Bounded reads
;;; ---------------------------------------------------------------------------

(defn- read-bounds
  {:malli/schema [:=> [:cat :seon.render/unit] [:map-of :keyword :int]]}
  [unit]
  (let [caps (:seon.sci.admit/caps unit)
        max-nodes (:seon.config.eval.result/max-nodes caps)
        max-collection (:seon.config.eval.result/max-collection caps)]
    (cond-> {}
      (pos-int? max-nodes)
      (assoc :max-work max-nodes :max-result-weight max-nodes)

      (pos-int? max-collection)
      (assoc :max-results max-collection))))

(defn- read-refusal?
  "A bounded read's refusal: `seon.db`'s declared `:seon.db/invalid-read-error`,
  known by its distinguishing member. Sections are also strings and hiccup
  vectors, which are never refusals."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (and (map? value) (contains? value :seon.db/invalid-read)))

;;; ---------------------------------------------------------------------------
;;; Namespace bindings
;;; ---------------------------------------------------------------------------

(defn- first-doc-line
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [doc]
  (some-> doc str/split-lines first str/trim not-empty))

(defn render-alias-ai
  "Render source that says what one alias resolves to and how to look there.

  Clojure's own words for this binding are the `:as` portion of a require
  libspec, so that is what the comment states; the form is the ordinary way to
  see what the aliased namespace publishes."
  {:malli/schema [:=> [:cat :seon.ns.alias/binding] :seon.render/source]}
  [{local :seon.ns.alias/local target :seon.ns.alias/target-ns}]
  (str ";; Here `" local "` is an alias for " target ", so `" local
       "/name` reads a Var\n"
       ";; in that namespace — the `:as` half of [" target " :as " local "].\n"
       (pr-str (list 'dir (list 'quote target)))))

(defn render-alias-html
  "Render one namespace alias binding with its local and target roles explicit."
  {:malli/schema [:=> [:cat :seon.ns.alias/binding] :seon.render/hiccup]}
  [{local :seon.ns.alias/local target :seon.ns.alias/target-ns}]
  [:article {:class "seon-family-entry seon-namespace-alias-entry"}
   [:p {:class "seon-kicker"} "Namespace alias"]
   [:p [:code (str local)] " → " [:code (str target)]]])

(defn render-refer-ai
  "Render source that says which Var one refer binding names.

  A refer points the other way from an alias: the local symbol is the target
  Var's name brought into this namespace, so the arrow reads backwards."
  {:malli/schema [:=> [:cat :seon.ns.refer/binding] :seon.render/source]}
  [{local :seon.ns.refer/local
    target-ns :seon.ns.refer/target-ns
    target-name :seon.ns.refer/target-name}]
  (str ";; Here the bare symbol `" local "` is " target-ns "/" target-name
       " —\n"
       ";; the `:refer` half of [" target-ns " :refer [" target-name "]].\n"
       (pr-str (list 'doc (symbol (str target-ns) (str target-name))))))

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
  "Render source naming the class one import binding makes available."
  {:malli/schema [:=> [:cat :seon.ns.import/binding] :seon.render/source]}
  [{local :seon.ns.import/local target :seon.ns.import/target-class}]
  (str ";; Here the bare symbol `" local "` is the JVM class "
       (or target local) " —\n"
       ";; the `:import` half of the ns form.\n"
       (pr-str (list 'class (list 'quote (or target local))))))

(defn render-import-html
  "Render one import binding with its local and target roles explicit."
  {:malli/schema [:=> [:cat :seon.ns.import/binding] :seon.render/hiccup]}
  [{local :seon.ns.import/local target :seon.ns.import/target-class}]
  [:article {:class "seon-family-entry seon-namespace-import-entry"}
   [:p {:class "seon-kicker"} "Namespace import"]
   [:p [:code (str local)] " → " [:code (str (or target local))]]])

(defn- ns-form
  {:malli/schema [:=> [:cat :symbol [:sequential :symbol]] :seon.repl/expression]}
  [namespace-name requires]
  (apply list
         (cond-> ['ns namespace-name]
           (seq requires) (conj (apply list :require requires)))))

;;; ---------------------------------------------------------------------------
;;; Family defaults
;;; ---------------------------------------------------------------------------

(defn namespace-form
  "Return the ordinary `dir` form for a namespace entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'dir (list 'quote (:seon.ns/name unit))))

(defn schema-form
  "Return the ordinary `doc` form for a schema entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'doc (:seon.schema/key unit)))

;;; ---------------------------------------------------------------------------
;;; Graph nodes: one entity each. Distance 0 names it; distance 1 and beyond
;;; show its content, with its neighbours as names (the walk renders those).
;;; ---------------------------------------------------------------------------

(defn- node-distance
  {:malli/schema [:=> [:cat :seon.render/unit] [:int {:min 0}]]}
  [unit]
  (max 0 (long (get unit :seon.render/distance 1))))

(defn- names
  {:malli/schema [:=> [:cat [:maybe [:or [:set :symbol] [:sequential :symbol]]]] :string]}
  [symbols]
  (if (seq symbols) (str/join " " (sort-by str symbols)) "none"))

(defn- comment-lines
  {:malli/schema [:=> [:cat :string :string] :string]}
  [label text]
  (let [indent (apply str (repeat (count label) \space))]
    (str/join "\n" (map-indexed #(str ";; " (if (zero? %1) label indent) %2) (str/split-lines text)))))

(defn task-ai
  "Render one task: intent, done-when proofs, subjects and must-reads.

  Neighbours are names; the last form is the call that expands the edges."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/source :seon.db/invalid-read-error]]}
  [unit]
  (let [{:seon.task/keys [id kind intent must-read] task-name :seon.task/name task-ns :seon.task/ns}
        (or (:seon.render/value unit) unit)
        header (str ";; Task " id " \"" task-name "\": a " (name kind) " task in " task-ns)
        edges (when (pos? (node-distance unit)) (issue/task-edges (:seon.db/db unit) id))]
    (cond
      (nil? edges) header
      (read-refusal? edges) edges
      :else (str header "\n" (comment-lines "Intent: " intent)
                 "\n;; Done when these proofs pass: " (names (:seon.task/proofs edges))
                 "\n;; Subjects: " (names (:seon.task/subjects edges))
                 "\n;; Must read: " (names must-read)
                 "\n" (repl/source-text (list 'seon.issue/task-edges (list 'seon.db/db) id))))))

(defn task-html
  "Render one task as a card: intent, proofs, subjects, must-reads, evidence."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/hiccup :seon.db/invalid-read-error]]}
  [unit]
  (let [{:seon.task/keys [id kind intent must-read evidence] task-name :seon.task/name task-ns :seon.task/ns}
        (or (:seon.render/value unit) unit)
        edges (when (pos? (node-distance unit)) (issue/task-edges (:seon.db/db unit) id))
        row (fn [label items]
              [:div [:dt label] [:dd (if (seq items) (into [:ul] (map #(vector :li [:code (str %)])) (sort-by str items)) "None")]])]
    (if (read-refusal? edges)
      edges
      [:article {:class "seon-family-entry seon-task-entry"}
       [:header [:p {:class "seon-kicker"} (str "Task · " (name kind) " · " task-ns)] [:h3 task-name " " [:code id]]]
       [:p {:class "seon-task-intent"} intent]
       (when edges
         [:dl (row "Done when these proofs pass" (:seon.task/proofs edges)) (row "Subjects" (:seon.task/subjects edges))
          (row "Must read" must-read) (row "Evidence" evidence)])])))

(defn- schema-parts
  "A schema row's key, its stored form read as data, and the form's description."
  {:malli/schema [:=> [:cat :seon.render/unit] [:tuple :qualified-keyword [:or [:vector :any] :keyword :symbol] [:maybe :string]]]}
  [unit]
  (let [{schema-key :seon.schema/key text :seon.schema/form} (or (:seon.render/value unit) unit)
        form (edn/read-string text)]
    [schema-key form (when (and (vector? form) (map? (second form))) (:description (second form)))]))

(defn schema-ai
  "Render one schema as its `register!` form, headed by its doc line."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (let [[schema-key form doc] (schema-parts unit)
        header (str ";; Schema " schema-key (some->> (first-doc-line doc) (str " — ")))]
    (if (zero? (node-distance unit))
      header
      (str header "\n" (repl/source-text (list 'seon.schema/register! schema-key form))))))

(defn schema-html
  "Render one schema: its key, its doc and its declaring form."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [[schema-key form doc] (schema-parts unit)]
    [:article {:class "seon-family-entry seon-schema-entry"}
     [:header [:p {:class "seon-kicker"} "Schema"] [:h3 [:code (str schema-key)]]]
     (when doc [:p doc])
     (when (pos? (node-distance unit))
       [:pre [:code (repl/source-text (list 'seon.schema/register! schema-key form))]])]))

(def ^:private callers-named
  "Callers are named up to this count; beyond it the node renders less: the
  count and the call that lists them."
  20)

(defn- function-neighbours
  "The program functions among `calls`, and the functions calling `function-name`."
  {:malli/schema [:=> [:cat :seon.render/unit :qualified-symbol [:maybe [:or [:set :symbol] [:sequential :symbol]]]]
                  [:or [:tuple [:vector :symbol] [:vector :symbol]] :seon.db/invalid-read-error]]}
  [unit function-name calls]
  (let [database (:seon.db/db unit)
        bounds (read-bounds unit)
        callees (db/q (merge bounds {:query '[:find [?s ...] :in $ [?s ...] :where [_ :seon.fn/sym ?s]]
                                     :args [database (vec (remove #{function-name} calls))]}))
        callers (when-not (read-refusal? callees)
                  (db/q (merge bounds {:query '[:find [?caller ...] :in $ ?target
                                                :where [?c :seon.fn/calls ?target] [?c :seon.fn/sym ?caller]]
                                       :args [database function-name]})))]
    (or (first (filter read-refusal? [callees callers])) [(vec callees) (vec callers)])))

(defn function-ai
  "Render a function node: its authored source, then its callees and callers by name."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/source :seon.db/invalid-read-error]]}
  [unit]
  (let [{function-name :seon.fn/sym doc :seon.fn/doc source :seon.fn/source spec :seon.fn/spec calls :seon.fn/calls}
        (or (:seon.render/value unit) unit)
        header (str ";; " function-name (some->> (first-doc-line doc) (str " — ")))
        neighbours (when (pos? (node-distance unit)) (function-neighbours unit function-name calls))
        [callees callers] neighbours]
    (cond
      (nil? neighbours) header
      (read-refusal? neighbours) neighbours
      :else (str header "\n" (or source (repl/source-text (list 'doc function-name)))
                 ;; the contract is shown where the authored form does not carry it
                 (when (and spec source (not (str/includes? source ":malli/schema"))) (str "\n;; contract: " spec))
                 "\n;; calls: " (names callees)
                 "\n;; called by: "
                 (if (<= (count callers) callers-named)
                   (names callers)
                   (str (count callers) " functions, listed by "
                        (repl/source-text (list 'my.program/callers {:seon.program/subject (list 'quote function-name)}))))))))

(defn function-html
  "Render a function node: its doc, authored source, callees and callers."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/hiccup :seon.db/invalid-read-error]]}
  [unit]
  (let [{function-name :seon.fn/sym doc :seon.fn/doc source :seon.fn/source calls :seon.fn/calls} (or (:seon.render/value unit) unit)
        neighbours (when (pos? (node-distance unit)) (function-neighbours unit function-name calls))
        [callees callers] neighbours
        row (fn [label items]
              [:div [:dt label] [:dd (if (seq items) (into [:ul] (map #(vector :li [:code (str %)])) (sort-by str items)) "None")]])]
    (if (read-refusal? neighbours)
      neighbours
      [:article {:class "seon-family-entry seon-function"}
       [:header [:p {:class "seon-kicker"} "Function"]
        [:h3 [:a {:href (route/path ::route/namespace {:namespace (namespace function-name)})} [:code (str function-name)]]]]
       (when-let [line (first-doc-line doc)] [:p line])
       (when neighbours
         (list (when source [:pre [:code source]])
               [:dl (row "Calls" callees) (row "Called by" callers)]))])))

(defn- public-functions
  "The public functions a namespace declares, by name: one reverse pull from
  the namespace row, unlimited, so a large namespace is read whole and the AI
  pair shows less (`shown-functions`). A query joining every function row
  exceeded the read budget even for an empty namespace (0.2 ms here, measured
  on default 2026-09-26)."
  {:malli/schema [:=> [:cat :seon.render/unit :symbol] [:or [:vector :symbol] :seon.db/invalid-read-error]]}
  [unit namespace-name]
  (let [found (db/pull (:seon.db/db unit)
                       [{[:seon.fn/_ns :limit nil] [:seon.fn/sym :seon.fn/private?]}]
                       [:seon.ns/name namespace-name])]
    (if (read-refusal? found)
      found
      (into [] (comp (filter #(false? (:seon.fn/private? %)))
                     (map (comp symbol name :seon.fn/sym)))
            (:seon.fn/_ns found)))))

(defn- shown-functions
  "The public function names the AI face shows: all of them, or the first
  `max-collection` by name with the total and `dir` as the continuation."
  {:malli/schema [:=> [:cat :seon.render/unit [:vector :symbol]] :string]}
  [unit members]
  (let [limit (get-in unit [:seon.sci.admit/caps :seon.config.eval.result/max-collection])
        ordered (sort-by str members)]
    (if (and (pos-int? limit) (< limit (count ordered)))
      (str " (first " limit " of " (count ordered) " by name; `dir` below lists every one): "
           (names (take limit ordered)))
      (str ": " (names ordered)))))

(defn render-ai
  "Render a namespace node: its declaration, public functions by name, `dir`."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/source :seon.db/invalid-read-error]]}
  [unit]
  (let [{namespace-name :seon.ns/name doc :seon.ns/doc source :seon.ns/source requires :seon.ns/requires}
        (or (:seon.render/value unit) unit)
        header (str ";; Namespace " namespace-name (some->> (first-doc-line doc) (str " — ")))
        members (when (pos? (node-distance unit)) (public-functions unit namespace-name))]
    (cond
      (nil? members) header
      (read-refusal? members) members
      :else (str header "\n" (or source (repl/source-text (ns-form namespace-name (sort requires))))
                 "\n;; public functions" (shown-functions unit members)
                 "\n" (repl/source-text (list 'dir namespace-name))))))

(defn render-html
  "Render a namespace node: its doc, requires and public functions by name."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/hiccup :seon.db/invalid-read-error]]}
  [unit]
  (let [{namespace-name :seon.ns/name doc :seon.ns/doc requires :seon.ns/requires} (or (:seon.render/value unit) unit)
        members (when (pos? (node-distance unit)) (public-functions unit namespace-name))
        link #(vector :a {:href (route/path ::route/namespace {:namespace (str %)})} [:code (str %)])
        row (fn [label items] [:div [:dt label] [:dd (if (seq items) (into [:ul] (map #(vector :li %)) items) "None")]])]
    (if (read-refusal? members)
      members
      [:article {:class "seon-family-entry seon-namespace-entry"}
       [:header [:p {:class "seon-kicker"} "Namespace"] [:h3 (link namespace-name)]]
       (when-let [line (first-doc-line doc)] [:p {:class "seon-namespace-description"} line])
       (when members
         [:dl (row "Requires" (map link (sort-by str requires)))
          (row "Public functions" (map #(vector :code (str %)) (sort-by str members)))])])))
