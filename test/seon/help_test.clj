(ns seon.help-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [edamame.core :as edamame]
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

(defn- help-lines
  "Every line of help's groups, in order."
  {:malli/schema [:=> [:cat :seon.help/groups] :seon.help/lines]}
  [groups]
  (into [] (mapcat :seon.help/lines) groups))

(defn- lines-of
  "The help lines help-value answers on `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.help/lines]}
  [database]
  (help-lines (:seon.help/groups (bootstrap/help-value database "help"))))

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
       (is (not (re-find #"my\.agent/|my\.test/check|turns-left|NEXT turn" text))
           "no shelved, deleted or self-running lesson")
       (is (not (str/includes? text "…")) "the printed help carries no elision")))))

(deftest help-teaches-defn-in-the-namespace-that-owns-the-function
  (with-help-agent
   (fn [connection]
     (let [text (str/join "\n" (lines-of @connection))]
       (is (not (re-find #"in-ns '(my|user)[. ]" text)) "no placeholder or real namespace prefix is taught")
       (is (str/includes? text "the namespace that owns it") "the owner's namespace is the lesson")))))

(deftest help-teaches-the-repl-not-files-as-the-change-medium
  (with-help-agent
   (fn [connection]
     (let [text (str/join "\n" (lines-of @connection))]
       (is (str/includes? text "never in files") "code changes at the REPL, never in files")
       (is (str/includes? text "slurp") "the file calls are named as wrong")))))

(deftest help-teaches-no-placeholder-value
  (with-help-agent
   (fn [connection]
     (let [lines (lines-of @connection)
           forms (mapcat taught-forms lines)]
       (is (not-any? #(re-find #"/path/to|\"\.\.\.\"|<[a-z -]+>" %) lines)
           "no line carries a placeholder path, ellipsis or angle-bracket slot")
       (is (not-any? #(= 'my.program/write-branch! (first %)) forms)
           "write-back is root's step, not a call help teaches a worker")))))

(deftest the-help-reply-reads-as-data-its-value-the-help-lines
  (support/with-running-agent
   (fn [running agent-id connection]
     (is (= (:seon.help/groups (bootstrap/help-value @connection agent-id))
            (:seon.repl/value (support/reply (agent-text "(help)" agent-id running))))
         "the reply is one EDN form whose value is the help groups, whole"))))

(deftest help-teaches-the-schema-registration-form
  (support/with-running-agent
   (fn [running agent-id _]
     (let [lines (help-lines (:seon.repl/value (support/reply (agent-text "(help)" agent-id running))))
           heads (set (map first (mapcat taught-forms lines)))]
       (is (contains? heads 'seon.schema/register!) "help teaches the registration form")))))

(deftest help-prints-whole-without-a-large-value-finding
  (support/with-running-agent
   (fn [running agent-id _]
     (let [reply (support/reply (agent-text "(help)" agent-id running))]
       (is (= [] (filterv #(= :large-value (:seon.repl/finding %)) (:seon.repl/findings reply)))
           "a value the printer shows whole is not flagged large")))))

(deftest ^{:seon/tasks #{'my.program/source-finds-a-schema-registered-in-the-same-eval-request}}
  source-finds-a-schema-registered-in-the-same-eval-request
  (support/with-running-agent
   (fn [running agent-id _]
     (let [code "(do (seon.schema/register! :comp.order/qty :int) (my.program/source :comp.order/qty))"
           value (:seon.repl/value (support/reply (agent-text code agent-id running)))]
       (is (nil? (:seon.program/not-found value)) "the key registered a moment ago is found")))))

(deftest dir-lists-what-an-agent-calls-as-an-agent-calls-it
  (support/with-running-agent
   (fn [running agent-id _]
     (let [functions (into {} (map (juxt :sym :arglists))
                           (:seon.repl/value (support/reply (agent-text "(dir my.program)" agent-id running))))
           leaves (set (tree-seq coll? seq (vec (vals functions))))]
       (is (not (contains? functions 'my.program/supplied-context)) "an internal helper is not listed")
       (is (= '([subject]) (get functions 'my.program/source)) "a supplied database is not an argument")
       (is (not-any? leaves [:seon.db/db :my.program/context 'database 'context])
           "no arglist names a supplied world member")))))

(deftest help-teaches-how-a-deftest-names-its-task
  (let [lines (lines-of (seon.db/db))]
    (is (some #(clojure.string/includes? % "(deftest ^{:seon/tasks #{'") lines)
        "help teaches a deftest whose name carries :seon/tasks")))

(deftest ^{:seon/tasks #{'seon.bootstrap/help-teaches-in-ns-first-and-no-probing}}
  help-teaches-locals-need-their-bindings-not-in-ns
  (with-help-agent
   (fn [connection]
     (let [lines (lines-of @connection)
           defining (first (filter #(some (fn [form] (= 'defn (first form))) (taught-forms %)) lines))]
       (is (= 'defn (first (first (taught-forms defining)))) "the definition lesson starts with defn, never in-ns")
       (is (some #(str/includes? % ":seon.context/namespace") lines) "help says the block names the agent's namespace")
       (is (some #(and (str/includes? % "locals") (str/includes? % "binding")) lines)
           "help states that a body's locals exist only inside their binding")))))

(deftest help-is-an-orienting-guide-in-titled-groups
  (with-help-agent
   (fn [connection]
     (let [groups (:seon.help/groups (bootstrap/help-value @connection "help"))
           text (str/join "\n" (help-lines groups))]
       (is (= ["Who you are" "Reading your context" "Done" "How work proceeds" "The REPL" "Data"
               "Ask root or give the task back" "Clojure and Malli gotchas"]
              (mapv :seon.help/title groups))
           "help carries each orienting group, in reading order")
       (is (every? #(str/includes? text %)
                   [":seon.context/proofs" ":pending" ":seon.ref/message" ":seon.render/operations"
                    "There is no done call" ":malli/schema you write" "merge refuses it" "tool-call markup"
                    "with-redefs" "(my.turn/wait" "(my.task/send-back!" "my.message/send"])
           "each group says what it is for: the block's parts, done, the contract form, the REPL, the don'ts and the way out")))))

(deftest help-teaches-the-common-gotchas-and-names-only-symbols-that-exist
  (with-help-agent
   (fn [connection]
     (let [groups (:seon.help/groups (bootstrap/help-value @connection "help"))
           text (str/join "\n" (help-lines groups))
           gotchas (:seon.help/lines (first (filter #(= "Clojure and Malli gotchas" (:seon.help/title %)) groups)))
           symbols (distinct (map second (re-seq #"(?<![:\w./'])([a-z][\w.-]*\.[\w.-]*/[\w!?*<>=-]+)" text)))]
       (is (= 5 (count gotchas)) "help carries its five gotcha lines")
       (is (every? #(str/includes? (str/join "\n" gotchas) %) ["[:= {} nil]" "[:cat in1 in2]" "(contains? {:k false} :k)" "mapv" "ns-aliases" ":seon.error/problems"])
           "each gotcha names its remedy")
       (is (not (re-find #"\[:maybe :" text)) "no line advises [:maybe ...]")
       (is (seq symbols) "help names qualified symbols")
       (is (empty? (remove #(requiring-resolve (symbol %)) symbols))
           "every qualified symbol help names resolves")))))
