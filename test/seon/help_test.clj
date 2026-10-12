(ns seon.help-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [edamame.core :as edamame]
            [seon.ai.tokens :as tokens]
            [seon.mcp :as mcp]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.test-support :as support]))

;; The opening no longer evaluates (help) first: the agent's identity unit
;; offers it as data (db908b0c6), so help is one read away rather than the
;; opening's first stored evaluation.

(defn- with-help-agent
  "Run `body` with the fixture connection holding a seeded cluster and agent help."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] :seon.schema/value]}
  [body]
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "help")
     (support/transacted! connection
                          (agent/creation-tx {:seon.agent/id "help"
                                              :seon.ns/name 'my.agents.help
                                              :seon.cluster/name "help"}))
     (body connection))))

(deftest an-agent-identity-offers-help-as-its-operation
  (with-help-agent
   (fn [connection]
     (is (= ['(help)]
            (:seon.render/operations
             (agent/render-identity-ai {:seon.db/db @connection :seon.agent/id "help"})))
         "a non-root agent's identity offers exactly (help)"))))

(deftest help-evidence-tracks-its-code-version
  (with-help-agent
   (fn [connection]
     (let [captured (atom [])
           database @connection
           _ (binding [db/*read-evidence-sink* captured]
               (bootstrap/help-value database "help"))
           evidence (db/read-evidence @captured)
           source (:seon.fn/source (db/pull database [:seon.fn/source]
                                            [:seon.fn/sym 'seon.bootstrap/help-value]))]
       (is (seq evidence) "help records what it read")
       (support/transacted! connection (support/agent-tx @connection "unrelated"))
       (is (true? (db/read-evidence-current? @connection evidence))
           "an unrelated write leaves help current")
       (support/transacted! connection
                            (support/replacement-tx @connection
                                                    [:seon.fn/sym 'seon.bootstrap/help-value]
                                                    (str source "\n;; revised")
                                                    {}))
       (is (false? (db/read-evidence-current? @connection evidence))
           "a new definition of help makes its evidence stale")))))

(defn- taught-forms
  "The parenthesised forms a help line teaches, read in order."
  {:malli/schema [:=> [:cat :string] [:vector :seon.schema/value]]}
  [line]
  (loop [from 0 forms []]
    (let [start (str/index-of line "(" from)]
      (if (nil? start)
        forms
        (let [form (try (edamame/parse-string (subs line start) {:all true}) (catch Exception _ nil))]
          (recur (inc start) (cond-> forms (seq? form) (conj form))))))))

(defn- agent-text
  "The raw text of the REPL item of one `eval_clj` of `code` as `agent-id` through the MCP entrance."
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [code agent-id running]
  (let [texts (map #(get % "text")
                   (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                                  "arguments" {"code" code "agent" agent-id "cluster" running}})))
                        "content"))]
    (or (first (filter #(str/includes? % ":seon.repl/result") texts)) (first texts))))

(deftest help-names-only-installed-forms
  (support/with-running-agent
   (fn [running agent-id _]
     (let [text (agent-text "(help)" agent-id running)
           lines (str/split-lines text)
           forms (mapcat taught-forms lines)
           heads (distinct (map first forms))
           resolved (support/reply
                     (agent-text (pr-str (list 'mapv '(fn [s] [s (some? (resolve s))])
                                               (list 'quote (vec heads))))
                                 agent-id running))]
       (is (seq forms) "help teaches forms")
       (is (every? second (:seon.repl/value resolved)) "every taught head resolves at the agent's REPL")
       (is (not (re-find #"my\.task|my\.agent/|my\.test/check|turns-left|NEXT turn" text))
           "no shelved, deleted or self-running lesson")
       (is (not (str/includes? text ":large-value")) "help raises no misuse finding")
       (is (<= (tokens/estimate text) 800) "help stays small")))))

(deftest help-teaches-defn-in-the-namespace-that-owns-the-function
  (with-help-agent
   (fn [connection]
     (let [text (str/join "\n" (:seon.help/lines (bootstrap/help-value @connection "help")))]
       (is (not (re-find #"in-ns '(my|user)[. ]" text)) "no placeholder or real namespace prefix is taught")
       (is (str/includes? text "the namespace that owns it") "the owner's namespace is the lesson")))))

(deftest help-teaches-no-placeholder-value
  (with-help-agent
   (fn [connection]
     (let [lines (:seon.help/lines (bootstrap/help-value @connection "help"))
           forms (mapcat taught-forms lines)]
       (is (not-any? #(re-find #"/path/to|\"\.\.\.\"|<[a-z -]+>" %) lines)
           "no line carries a placeholder path, ellipsis or angle-bracket slot")
       (is (not-any? #(= 'my.program/write-branch! (first %)) forms)
           "write-back is root's step, not a call help teaches a worker")))))

(deftest the-help-reply-reads-as-data-its-value-the-help-lines
  (support/with-running-agent
   (fn [running agent-id connection]
     (is (= (:seon.help/lines (bootstrap/help-value @connection agent-id))
            (:seon.repl/value (support/reply (agent-text "(help)" agent-id running))))
         "the reply is one EDN form whose value is the help lines"))))

(deftest help-teaches-the-schema-registration-form
  (support/with-running-agent
   (fn [running agent-id _]
     (let [lines (:seon.repl/value (support/reply (agent-text "(help)" agent-id running)))
           heads (set (map first (mapcat taught-forms lines)))]
       (is (contains? heads 'seon.schema/register!) "help teaches the registration form")))))

(deftest dir-lists-what-an-agent-calls-as-an-agent-calls-it
  (support/with-running-agent
   (fn [running agent-id _]
     (let [functions (into {} (map (juxt :sym :arglists))
                           (:seon.repl/value (support/reply (agent-text "(dir my.program)" agent-id running))))
           leaves (set (tree-seq coll? seq (vec (vals functions))))]
       (is (not (contains? functions 'my.program/supplied-context)) "an internal helper is not listed")
       (is (= '([sym]) (get functions 'my.program/source)) "a supplied database is not an argument")
       (is (not-any? leaves [:seon.db/db :my.program/context 'database 'context])
           "no arglist names a supplied world member")))))

(deftest help-teaches-how-a-deftest-names-its-task
  (let [lines (:seon.help/lines (seon.bootstrap/help-value (seon.db/db) "help"))]
    (is (some #(clojure.string/includes? % "(deftest ^{:seon/tasks #{'") lines)
        "help teaches a deftest whose name carries :seon/tasks")))

(deftest ^{:seon/tasks #{'seon.bootstrap/help-teaches-in-ns-first-and-no-probing}}
  help-teaches-locals-need-their-bindings-not-in-ns
  (with-help-agent
   (fn [connection]
     (let [lines (:seon.help/lines (bootstrap/help-value @connection "help"))
           defining (first (filter #(some (fn [form] (= 'defn (first form))) (taught-forms %)) lines))]
       (is (= 'defn (first (first (taught-forms defining)))) "the definition lesson starts with defn, never in-ns")
       (is (some #(str/includes? % ":seon.context/namespace") lines) "help says the block names the agent's namespace")
       (is (some #(and (str/includes? % "locals") (str/includes? % "binding")) lines)
           "help states that a body's locals exist only inside their binding")))))

(deftest help-teaches-the-common-gotchas-and-names-only-symbols-that-exist
  (with-help-agent
   (fn [connection]
     (let [lines (:seon.help/lines (bootstrap/help-value @connection "help"))
           text (str/join "\n" lines)
           gotchas (filter #(str/starts-with? % "Gotcha:") lines)
           symbols (distinct (map second (re-seq #"(?<![:\w./'])([a-z][\w.-]*\.[\w.-]*/[\w!?*<>=-]+)" text)))]
       (is (= 7 (count gotchas)) "help carries its seven gotcha lines")
       (is (every? #(str/includes? text %) ["[:= {} nil]" "[:cat in1 in2]" "contains? m :k" "(some f xs)" "mapv" "ns-aliases" ":seon.error/problems"])
           "each gotcha names its remedy")
       (is (not (re-find #"\[:maybe :" text)) "no line advises [:maybe ...]")
       (is (seq symbols) "help names qualified symbols")
       (is (empty? (remove #(requiring-resolve (symbol %)) symbols))
           "every qualified symbol help names resolves")))))
