(ns seon.help-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [edamame.core :as edamame]
            [seon.mcp :as mcp]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.task :as task]
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
  (help-lines (:seon.help/groups (bootstrap/help-value database))))

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
               (bootstrap/help-value database))
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
                                                    ;; inside the form: a comment after it is no part of the definition
                                                    (str/replace-first source "[database]"
                                                                       "[database]\n  ;; revised")
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
                                 agent-id running))
           ;; a key the agent's own block shows is no lesson but the name of what it reads
           shown (->> (support/reply (agent-text "(seon.context/current-state)" agent-id running))
                      :seon.repl/value (tree-seq coll? seq) (filter qualified-keyword?) (map str) set)
           lessons (reduce #(str/replace %1 %2 "") text (sort-by (comp - count) shown))]
       (is (seq forms) "help teaches forms")
       (is (every? second (:seon.repl/value resolved)) "every taught head resolves at the agent's REPL")
       (is (contains? shown ":my.agent/turns-left") "the block shows the turns left")
       (is (not (re-find #"my\.agent/|my\.test/check|turns-left|NEXT turn" lessons))
           "no shelved, deleted or self-running lesson beside the keys the block shows")
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
     (is (= (bootstrap/render-help-ai (bootstrap/help-value @connection))
            (:seon.repl/value (support/reply (agent-text "(help)" agent-id running))))
         "the reply is one EDN form whose value is the help groups' titles and lines, whole; references stay in the value"))))

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

(deftest help-never-says-root-reviews
  (with-help-agent
   (fn [connection]
     (let [text (str/join "\n" (lines-of @connection))]
       (is (not (re-find #"(?i)root (reviews|answers)|ask root|\"root\"" text)) "nothing waits on root")))))

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
     (support/assign-task! connection "help")
     (support/transacted! connection [{:db/id [:seon.task/id (task/agent-task @connection "help")] :seon.task/step :prove}])
     (let [groups (:seon.help/groups (bootstrap/help-value @connection))
           text (str/join "\n" (help-lines groups))]
       (is (not (re-find #":prove|:implement|Prove|Implement" text))
           "no group teaches one step's work: a step's guidance is its instructions")
       (is (= ["Who you are" "How to speak" "Your task" "How work proceeds" "Write what you learn"
               "When you cannot" "Each reply is data" "Data"]
              (mapv :seon.help/title groups))
           "help carries each orienting group, in reading order")
       (is (every? #(str/includes? text %)
                   [":seon.context/proofs" ":pending" ":seon.render/operations" "There is no done call"
                    "merge refuses it" "You have one tool, eval." "keep the ;; comments you were shown"
                    "with-redefs" "(my.task/send-back!" "(my.task/update!" "merges onto main by itself"])
           "each group says what it is for: the block's parts, done, the contract form, the REPL, the don'ts and the way out")))))

(deftest help-teaches-the-common-gotchas-and-names-only-symbols-that-exist
  (with-help-agent
   (fn [connection]
     (let [groups (:seon.help/groups (bootstrap/help-value @connection))
           text (str/join "\n" (help-lines groups))
           symbols (distinct (map second (re-seq #"(?<![:\w./'])([a-z][\w.-]*\.[\w.-]*/[\w!?*<>=-]+)" text)))]
       (is (every? #(str/includes? text %) ["[:= {} nil]" "[:cat in1 in2]" "(contains? {:k false} :k)" "mapv" "ns-aliases" ":seon.error/problems"])
           "each gotcha names its remedy")
       (is (not (re-find #"\[:maybe :" text)) "no line advises [:maybe ...]")
       (is (seq symbols) "help names qualified symbols")
       (is (empty? (remove #(requiring-resolve (symbol %)) symbols))
           "every qualified symbol help names resolves")))))

(deftest
  help-teaches-the-chain-block
  (with-help-agent
   (fn [connection]
     (let [line (first (filter #(str/includes? % ":seon.context/chain") (lines-of @connection)))]
       (is (some? line) "a help line explains :seon.context/chain")
       (is (every? #(str/includes? (str line) %) [":seon.context/before" ":seon.context/after" ":seon.context/merged" "already on your branch"])
           "it names the steps before with what each merged, the steps after, and that the merged work is on the branch")))))

(deftest help-teaches-private-contracts-and-the-whole-proof-set
  (with-help-agent
   (fn [connection]
     (is (some #(and (str/includes? % "defn-") (str/includes? % "[:fn {:error/message \"an even integer\" :gen/elements [0 2 4]} clojure.core/even?]")) (lines-of @connection))
         "a help line says defn- needs a contract and shows a named predicate with its message and generator")
     (is (some #(and (str/includes? % "Prefer a plain Malli schema") (str/includes? % "one you define on your branch is refused")) (lines-of @connection))
         "a help line prefers plain schemas and says a branch predicate is refused at write"))))
