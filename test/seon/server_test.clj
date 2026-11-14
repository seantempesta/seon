(ns seon.server-test
  "Root's server as an outside agent reaches it (seon.server, through the built root:
  seon.agent-test/in-agent). Run: bin/test seon.server-test"
  (:require [clojure.test :refer [deftest is]] [jolt.fibers] [seon.agent-test :as at] [seon.contract :as contract] [seon.server] [clojure.data.json] [clojure.edn] [clojure.string]
            [seon.test-store :as ts] [seon.task :as task] [seon.main] [seon.fx] [seon.lib] [datahike.api :as d] [clojure.java.shell] [clojure.core.async :as a]))

(deftest each-op-has-its-answer
  ;; the ops table (each op's doc and args, the MCP tool list) and op-answer's methods are one set of
  ;; keys: an op with no method would be listed and answer "unknown op", a method with no entry unlisted
  (is (= (set (keys seon.server/ops)) (disj (set (keys (methods seon.server/op-answer))) :default)))
  (is (contains? (methods seon.server/op-answer) :default) "an unknown op has its answer: refused"))

(deftest seon-agents-answers-what-is-live
  ;; seon_agents answered every task on main (580 on the standing root, most :held or
  ;; :cancelled with no agent), so an outside agent never saw what runs
  (ts/test-branch!
   (fn [_ conn]
     (let [before (:others (seon.server/live-tasks @conn))
           ;; the index :agents keeps, made now and brought forward by what changes below (seon.server/index-now)
           _ (#'seon.server/index-now conn @conn)
           decls (for [n ["a" "b" "c" "d" "e"]] {:seon.task/ns 'demo :seon.task/name n :seon.task/plan "Work." :seon.task/proofs #{}})
           _ (d/transact conn (task/create-tx @conn (vec decls)))
           env {:branch :demo :model nil :max-turns 4}
           _ (d/transact conn (concat (task/execution-tx ["demo/b" "demo/d"] :running) (task/execution-tx ["demo/e"] :cancelled)))
           _ (d/transact conn (task/start-tx @conn "demo/b" "boot-1" :a1 env 1000))
           _ (d/transact conn (task/start-tx @conn "demo/d" "boot-1" :a2 env 2000))
           {:keys [tasks others all]} (seon.server/live-tasks @conn)]
       (is (= ["demo/d" "demo/b"] (filterv #(= "demo" (namespace (symbol %))) (keys tasks))) "the running tasks, newest start first, and no held one")
       (is (= [:running :a2] ((juxt :outcome :agent) (get tasks "demo/d"))) "each with its outcome and agent")
       (is (= (merge-with + before {:held 2 :cancelled 1}) others) "the others counted by execution: demo/a and demo/c held, demo/e cancelled")
       (is (= '(seon.server/task-summaries @(:conn sys)) all) "with the form that lists every task")
       (is (= (seon.server/live-tasks @conn) (seon.server/live-tasks @conn (#'seon.server/index-now conn @conn)))
           "the index kept by change answers as a whole one: what :agents reads (docs/server.md#ops)")))))

(deftest a-root-eval-replies-like-an-agent-eval
  ;; task seon.server/root-evals-through-the-one-repl: an eval as root goes through the
  ;; agents' REPL path, so its response is an agent's: the one printer, a result handle, the
  ;; error map, *1 kept between requests
  (let [[a-ret a-err r-ret r-err r-star own]
        (at/in-agent #(vector "{:a 1}" "(/ 1 0)"
                              {:op :eval :agent "root" :code "{:a 1}"} {:op :eval :agent "root" :code "(/ 1 0)"}
                              {:op :eval :agent "root" :code (str "(:pid (get (:agents (c/state sys)) :" (:agent %) "))")}
                              "(seon.lib/c-getpid)"))]
    (is (= {:a 1} (:value a-ret) (:value r-ret)) "a value replies alike")
    (is (qualified-symbol? (:handle r-ret)) "root's value has its result handle, as an agent's does")
    (is (= (select-keys (:ex a-err) [:seon.error/type :seon.error/message]) (select-keys (:ex r-err) [:seon.error/type :seon.error/message]))
        "an error replies alike")
    (is (and (int? (:value own)) (= (:value own) (:value r-star))) "`sys` is this root: the agent it holds is the one answering")))

(deftest a-server-refused-answer-is-the-one-error-map
  ;; task seon.render/one-owner-per-view-path (B2): the server refuses as root does, with the
  ;; one error map, never a second {:error "text"} shape
  (let [answers (at/in-agent [{:op :nope} {:op :context :agent "a0"} {:op :done :agent "a0"} {:op :attach :task "no/such"}])]
    (is (= 4 (count answers)))
    (doseq [a answers]
      (is (contract/error? a) (pr-str a))
      (is (nil? (:error a)) (pr-str a)))))

(deftest a-server-defect-answers-that-request-alone
  ;; task seon.server/a-server-defect-answers-that-request-alone: a request whose op throws
  ;; (attach! refuses a task id that is not a string: its contract) is answered the typed
  ;; :seon.server/defect naming the line it took, and the server answers the next request
  (let [[defect ok] (at/in-agent [{:op :attach :task 5} {:op :eval :agent "root" :code "(+ 1 2)"}])]
    (is (= :seon.server/defect (:seon.error/type defect)))
    (is (= (pr-str {:op :attach :task 5}) (:line defect)))
    (is (= 3 (:value ok)) "the server goes on")))

(deftest the-server-speaks-mcp
  ;; root's server answers MCP's JSON-RPC
  ;; itself (bin/mcp only relays lines): initialize with a version it implements, ping,
  ;; tools/list from the one op table, nothing for a notification, and the JSON-RPC error for
  ;; a method or tool it does not know. tools/call through a real root is
  ;; seon.check/mcp-proof (bin/check mcp-proof), through the relay
  (let [ask #(seon.server/mcp-reply nil %)
        version #(get-in (ask {"id" 1 "method" "initialize" "params" {"protocolVersion" %}}) ["result" "protocolVersion"])
        tools (get-in (ask {"jsonrpc" "2.0" "id" 2 "method" "tools/list"}) ["result" "tools"])]
    (is (= "2024-11-05" (version "2024-11-05")) "a version it implements is the client's")
    (is (= (peek seon.server/protocol-versions) (version "1999-01-01")) "else the newest it implements, never an echo")
    (is (= {} (get (ask {"id" 9 "method" "ping"}) "result")))
    (is (= (mapv #(str "seon_" (name %)) (keys seon.server/ops)) (mapv #(get % "name") tools))
        "every op is the tool seon_<op>, in the table's order")
    (is (= ["agent"] (get-in (second tools) ["inputSchema" :required])) "an op's required arguments are its tool's")
    (is (nil? (ask {"jsonrpc" "2.0" "method" "notifications/initialized"})) "a notification is not answered")
    (is (= -32601 (get-in (ask {"id" 3 "method" "resources/list"}) ["error" "code"])) "a method it does not know: method not found")
    (is (= -32602 (get-in (ask {"id" 4 "method" "tools/call" "params" {"name" "seon_nope" "arguments" {}}}) ["error" "code"])) "a tool no op names: invalid params")))

(deftest a-line-opening-a-string-key-is-mcps
  ;; the protocol rule. A line opening {" is
  ;; JSON-RPC, so an EDN request with a string key is refused naming the rule (-32700), never
  ;; misread; a keyword-keyed EDN line still reads as EDN
  (let [respond #'seon.server/respond
        bad (clojure.data.json/read-str (respond nil "{\"op\" \"agents\"}"))]
    (is (= -32700 (get-in bad ["error" "code"])))
    (is (clojure.string/includes? (get-in bad ["error" "message"]) "keyword-keyed map"))
    (is (contract/error? (clojure.edn/read-string (respond nil "{:op :nope}"))) "a keyword-keyed line is EDN in, EDN out")))

(deftest a-refused-attach-writes-nothing
  ;; attach spawns first and
  ;; writes the env, the release and the start in one transaction only once its agent
  ;; exists, so a refused spawn leaves the task as it was, and the reconciler never sees a
  ;; release with no agent (it would spawn a second agent for it). <ns>/u shares <ns>/t's
  ;; branch, which <ns>/t's attached agent holds
  (let [[refused seen] (at/in-agent (fn [{:keys [ns]}]
                                      (let [u (str ns "/u")]
                                        [{:op :attach :task u}
                                         {:op :eval :agent "root"
                                          :code (str "(let [db @(:conn sys) t (seon.task/task db " (pr-str u) ")]"
                                                     " [(:seon.task/execution t) (:seon.task/model t)"
                                                     " (some? (:seon.task/agent t))])")}]))
                                    {:decls (fn [{:keys [ns]}]
                                              [{:seon.task/name "u" :seon.task/plan "Work too." :seon.task/kind :work
                                                :seon.task/proofs #{'demo-test/u} :seon.task/branch (keyword (str "task-" ns "-t"))}])})]
    (clojure.test/is (clojure.string/includes? (pr-str refused) "is held by live agent") "the attach is refused: the t task's agent holds the branch")
    (clojure.test/is (= [:held nil false] (:value seen)) "and wrote nothing: the u task is still held, with no env and no agent")))

(deftest an-attached-agent-holds-its-task
  ;; agent view's browser check: an agent attached from outside (seon.server/attach!
  ;; spawns it before it writes the start on main, so its branch is cut without the task) held no
  ;; task in @ctx, and its page no task, until a turn or a context read began its work. Forked for
  ;; a task, the agent records it on its branch as it starts (seon.agent/begin-run!): before any turn
  (let [names (volatile! nil)
        [held] (at/in-agent (fn [n] (vreset! names n) ["(let [t @seon.ctx/ctx] [(contains? t :runs) (get-in t [:seon.agent/task :seon.task/id])])"]))]
    (is (= [false (:task @names)] (:value held)) "its task, in its record, and no runs")))

(deftest an-agent-reads-its-task-running
  ;; task seon.task/an-agent-reads-its-task-as-main-holds-it: an attached agent's branch is cut
  ;; before main writes the task's release, so (my.task/current) and its page read :held
  ;; its record, which every part of its page is drawn from (the agent's own task part tells it its work, not the execution)
  (let [[current held] (at/in-agent ["(:seon.task/execution (my.task/current))"
                                     "(:seon.task/execution (:seon.agent/task @seon.ctx/ctx))"])]
    (is (= :running (:value current)) "the agent reads its task running, as main does")
    (is (= :running (:value held)) "and so does its record")))

(defn- in-agent-of
  "Root-REPL code that evaluates form in the agent that works task id, answering its value (read back
  from the response's text, as the server's reader does)."
  {:malli/schema [:=> [:cat :seon.task/id :any] :string]}
  [id form]
  (str "(let [a (some #(when (= " (pr-str id) " (:task %)) (:agent %)) (seon.task/running @(:conn sys)))]"
       " (:value (clojure.edn/read-string {:default tagged-literal} (:text @(c/submit! sys a " (pr-str form) ")))))"))

(deftest a-reset-starts-the-task-again-from-main
  ;; the owner (2026-10-27): reset an agent easily. seon_attach {:fresh true}: a new branch cut from
  ;; main and an empty conversation; the old branch and its records stay
  (let [[_ reset branch seen old] (at/in-agent (fn [{:keys [ns task agent]}]
                                             [(str "(ns " ns ") (def x 41)")
                                              {:op :attach :task task :fresh true}
                                              {:op :eval :agent "root" :code (str "(:seon.task/branch (seon.task/task @(:conn sys) " (pr-str task) "))")}
                                              {:op :eval :agent "root" :code (in-agent-of task (str "[(some-> (find-ns '" ns ") (ns-resolve 'x)) (count (:seon.eval/_agent @seon.ctx/ctx))]"))}
                                              ;; root's requests run in order: its kill, then this eval, which only the agent's death can answer
                                              {:op :eval :agent agent :message-md "" :evals [{:intent "add" :code "(+ 1 1)"}]}]))]
    (is (keyword? (:agent reset)) (pr-str reset))
    (is (contract/error? old) (str "the agent on the old branch ended: " (pr-str old)))
    (is (re-find #"-r[0-9]+$" (name (:value branch))) "the task's branch is a new one, named by its ending (seon.task/restart-tx)")
    (is (= [nil 0] (:value seen)) "whose agent holds nothing the old one defined, and no conversation")))

(deftest a-fork-goes-on-from-an-agents-head
  ;; the owner (2026-10-27): fork an agent easily. seon_attach {:from a}: a clone of the agent on a
  ;; branch of its head, working a sibling task, so it tries an alternative from where it stood
  (let [[_ fork plan seen] (at/in-agent (fn [{:keys [ns agent]}]
                                          [(str "(ns " ns ") (def x 41)")
                                           {:op :attach :from agent}
                                           {:op :eval :agent "root" :code (str "(:seon.task/plan (seon.task/task @(:conn sys) \"" ns "/t-fork-1\"))")}
                                           {:op :eval :agent "root" :code (in-agent-of (str ns "/t-fork-1") (str "[(some-> (find-ns '" ns ") (ns-resolve 'x) deref) (some? (datahike.api/entity (seon.agent/db) [:var/name '" ns "/x]))]"))}]))]
    (is (clojure.string/ends-with? (str (:task fork)) "/t-fork-1") (pr-str fork))
    (is (= "Work." (:value plan)) "the sibling task holds the task's plan")
    (is (= [41 true] (:value seen)) "and its agent what the forked one defined, live and on its branch")))

(deftest an-agent-attached-by-one-client-is-refused-to-another
  ;; root, 2026-10-27: one session's seon_done named another session's agent (a1, not its own a2)
  ;; and ended that task red. An agent attached by a client (bin/mcp stamps each request with its
  ;; own) answers done, eval and message to that client, or to a request that names its task; any
  ;; other is refused, naming who holds it, and the task stays open
  (let [[other-done other-eval other-message own named open]
        (at/in-agent (fn [{:keys [agent task]}]
                       [{:op :done :agent agent :client "c2"}
                        {:op :eval :agent agent :message-md "" :evals [{:intent "add" :code "(+ 1 2)"}] :client "c2"}
                        {:op :message :agent agent :body "hi" :client "c2"}
                        {:op :eval :agent agent :message-md "" :evals [{:intent "add" :code "(+ 1 2)"}] :client "c1"}
                        {:op :eval :agent agent :message-md "" :evals [{:intent "add" :code "(+ 2 2)"}] :client "c2" :task task}
                        {:op :eval :agent "root" :code (str "(boolean (some #(= " (pr-str task) " (:task %)) (seon.task/running @(:conn sys))))")}])
                     {:client "c1"})]
    (doseq [a [other-done other-eval other-message]]
      (is (= :seon.server/held (:seon.error/type a)) (pr-str a))
      (is (clojure.string/includes? (str (:seon.error/message a)) "c1") "naming who holds it"))
    (is (some #{3} (map :value (:responses own))) "its own client's eval answers")
    (is (some #{4} (map :value (:responses named))) "and so does one that names the agent's task")
    (is (true? (:value open)) "the refused done left the task open")))

(deftest a-message-reaches-the-agent
  ;; (parity row 73): the owner's message, sent as
  ;; the page's message bar sends it (seon.system/message!, here through the server's :message
  ;; op), is root's one delivery: it is in the agent's inbox when the agent next reads it,
  ;; though it arrived while the agent was not reading its socket; no agent: refused
  (let [names (volatile! nil)
        [sent taken none] (at/in-agent (fn [{:keys [agent] :as n}]
                                         (vreset! names n)
                                         [{:op :message :agent agent :body "use demo/g"}
                                          "(my.agent/messages)"
                                          {:op :message :agent "a0" :body "hello?"}]))]
    (is (true? (:ok sent)) "answered when its entity is on main")
    (is (clojure.string/includes? (:text taken) (str ":from " (pr-str (seon.config/value :seon.config.root/user-name)))) (:text taken))
    (is (re-find #":body \"use demo/g\"" (:text taken)))
    (is (re-find #":sent-ms \d+" (:text taken)) "when root's writer recorded it")
    (is (re-find #":id \"[a-p][0-9a-f]{15}\"" (:text taken)) "the entity's id, which the agent writes on its branch")
    (is (contract/error? none) "no live agent a0: refused, as root refuses")))

(deftest the-context-op-answers-what-a-model-is-sent
  ;; dogfood 2026-10-25: an outside agent read the task's data and turn sections only, not the system
  ;; prompt and screen a model is sent, and its (my.task/status) answered :seon.turn/not-observed.
  ;; Its context read is its turn: the screen's forms run as a model turn's do, and the read
  ;; answers the request's text, system prompt first, screen last
  (let [[ctx status sent] (at/in-agent (fn [{:keys [agent task]}]
                                         [{:op :context :agent agent} "(my.task/status)"
                                          ;; read here, from the store (seon.test-store/read-task-branch): no request to root
                                          (fn [{:keys [root]}]
                                            (let [db (ts/read-task-branch root task)]
                                              {:ns (d/q '[:find [?n ...] :where [?m :seon.model-call/origin :outside] [?m :seon.model-call/sent-hash _] [?m :seon.model-call/n ?n]] db)
                                               :ids (d/q '[:find [?id ...] :where [?m :seon.model-call/origin :outside] [?m :seon.model-call/id ?id]] db)
                                               :turns (d/q '[:find [?t ...] :where [_ :seon.agent/turn ?t]] db)
                                               :key (d/q '[:find ?k . :where [?a :seon.agent/key ?k] [?m :seon.model-call/agent ?a]] db)}))]))
        text (:context ctx)]
    (is (clojure.string/starts-with? text "# You are a Seon agent") "the system prompt, first")
    (is (clojure.string/includes? text "(my.render/page {:commit") "the page, as the call Seon made and its answer")
    (is (re-find #":render seon.render.ui/task-view" text) "each part headed by the render that drew it, its full symbol (seon.ctx.wire/presentation-free); the task's, seon.render.ui/task-view")
    (is (not (contract/error? (:value status))) (pr-str (:value status)))
    (is (contains? (:value status) :proofs) "status answers as the turn measured it")
    (is (seq (:ns (:value sent))) "and the read records the hash of what it sent, as a model turn does (the debug page compares its redraw with it)")
    ;; docs/turn.md#the-turn-is-a-number: a read is a model call with its own count, and moves no turn
    (is (= [(seon.lib/call-id (:key (:value sent)) "read-1")] (:ids (:value sent))) "the read's model call is its agent and read number digested")
    (is (empty? (:turns (:value sent))) "a read moves no turn number: no agent holds one")))

(deftest an-outside-agent-plays-the-model
  ;; docs/server.md#working-a-task-from-a-worktree: a read holds the conversation, as JSON the body a provider is sent;
  ;; a seon_eval by the agent attached is one reply, a turn, as a model's is, and a blank intent is not run, as a model's is not
  (let [[attach called stopped old turn-of moved-ns ns-after wire text]
        (at/in-agent (fn [{:keys [agent task]}]
                       [{:op :attach :task task}
                        {:op :eval :agent agent :message-md "Adding, then adding again." :evals [{:intent "add one and one" :code "(+ 1 1)"} {:intent "then two and two" :code "(+ 2 2)"}]}
                        {:op :eval :agent agent :message-md "" :evals [{:intent " " :code "(def not-run-form 1)"} {:intent "after it" :code "(def not-run-either 1)"}]}
                        {:op :eval :agent agent :intent "the old shape" :code "(def old-shape-ran 1)"}
                        "(seon.agent/turn-now)"
                        {:op :eval :agent agent :message-md "" :evals [{:intent "move" :code "(in-ns 'seon.print)"}]}
                        {:op :eval :agent agent :message-md "" :evals [{:intent "where" :code "(str *ns*)"}]}
                        {:op :context :agent agent :wire true}
                        {:op :context :agent agent}]))
        body (clojure.data.json/read-str (:context wire))
        msgs (get body "messages")
        call (first (filter #(some-> (get-in % ["tool_calls" 0 "function" "arguments"]) (clojure.string/includes? "(+ 1 1)")) msgs))
        [ecall ecall2] (get call "tool_calls")
        args (clojure.data.json/read-str (get-in ecall ["function" "arguments"]))
        result-of (fn [c] (get (first (filter #(and (= "tool" (get % "role")) (= (get c "id") (get % "tool_call_id"))) msgs)) "content"))]
    (is (clojure.string/includes? (:next attach) "seon_context") "an attach names the next step")
    (is (clojure.string/includes? (:next attach) "message-md") "and the shape of a reply")
    (is (= [2 4] (filterv number? (map :value (:responses called)))) "each eval answers, in order")
    (let [exs (keep #(:seon.error/type (:ex %)) (:responses stopped))]
      (is (= [:seon.turn/invalid-call] exs) "a call with no intent is answered invalid, and what follows it runs (owner, 2026-11-08)"))
    (is (= :seon.system/refused (:seon.error/type old)) (pr-str old))
    (is (clojure.string/includes? (:seon.error/message old) "message-md") "the old shape is told the new one, nothing run")
    (is (= 2 (:value turn-of)) "each reply is a turn: no one growing turn 0")
    (is (= ["seon.print"] (filterv string? (map :value (:responses ns-after)))) (str "the namespace a reply moved to is where the next one runs: " (pr-str moved-ns)))
    (is (= ["repl"] (mapv #(get-in % ["function" "name"]) (get body "tools"))) "the body holds the tool a model is given")
    (is (= (clojure.data.json/read-str (clojure.data.json/write-str seon.model/eval-tool)) (first (get body "tools"))) "that very tool")
    (is (= "system" (get (first msgs) "role")))
    (is (= "Adding, then adding again." (get call "content")) "the message is the reply's text, as a model's")
    (is (= {"intent" "add one and one" "code" "(+ 1 1)"} args) "the first eval is a call of its own")
    (is (= {"intent" "then two and two" "code" "(+ 2 2)"} (clojure.data.json/read-str (get-in ecall2 ["function" "arguments"]))) "and the second")
    (is (= [true true] [(boolean (re-find #":value 2" (result-of ecall))) (boolean (re-find #":value 4" (result-of ecall2)))]) "each call answered by a tool message of its own")
    (is (not-any? #(clojure.string/includes? (str (get % "content")) "old-shape-ran") msgs) "the old shape ran nothing")
    (is (clojure.string/includes? (:context text) "(+ 1 1)") "the text read holds the conversation too")))

(deftest roots-repl-names-what-has-no-branch
  ;; my.agent/* and my.db/* at root answered seon.agent/db's invalid-output, "Fix seon.agent/db (the producer)", and
  ;; (my.agent/prove) an NPE from datahike.writer/abandon-pending!: root has no branch, and neither said so
  (let [[db prove] (at/in-agent [{:op :eval :agent "root" :code "(my.db/db)"} {:op :eval :agent "root" :code "(my.agent/prove)"}])]
    (doseq [r [db prove]]
      (is (= :seon.agent/no-branch (:seon.error/type (:ex r))) (pr-str r))
      (is (clojure.string/includes? (:seon.error/fix (:ex r)) "@(:conn sys)") (pr-str r)))))

(deftest an-eval-text-says-how-main-differs
  ;; the answer held :main and the tool's text dropped it, though main-difference's doc promises it to an outside agent
  (is (= "{:value 1}\n:main main moved" (#'seon.server/eval-text {:text "{:value 1}" :main "main moved"})))
  (is (= "{:value 1}" (#'seon.server/eval-text {:text "{:value 1}"}))))

(deftest an-outside-agent-reads-its-context-after-it-defines
  ;; THE PLAN 0.7: the first real work through the server is an outside agent that attaches,
  ;; defines something at its branch's REPL and reads its context again. Once its branch holds a
  ;; change, the read's observe selects tests by what the branch changed (seon.merge/changed-vars):
  ;; it answered "nth not supported on this type: Keyword" for every agent past its first define
  (let [[before defined after status] (at/in-agent (fn [{:keys [agent ns]}]
                                                     [{:op :context :agent agent}
                                                      (str "(ns " ns ") (defn answer [] 42)")
                                                      {:op :context :agent agent}
                                                      "(my.task/status)"]))]
    (is (clojure.string/starts-with? (:context before) "# You are a Seon agent") (str "the read before any change: " (pr-str before)))
    (is (not (:ex defined)) (pr-str (:ex defined)))
    (is (clojure.string/starts-with? (str (:context after)) "# You are a Seon agent") (pr-str after))
    (is (clojure.string/includes? (str (:context after)) "answer") "the read after it defined tells the change")
    (is (not (contract/error? (:value status))) (pr-str (:value status)))))

(deftest an-outside-agents-repl-answers-plainly
  ;; THE PLAN 0.7's first run (2026-10-27) found four things in an outside agent's REPL: a
  ;; missed help address answered the whole help page (10k characters), (source ..) of a name that
  ;; is no var failed inside its own contract, and after (in-ns 'my.task) the next eval had no
  ;; source, as clojure.repl's names stay referred at a REPL that switches namespaces
  (let [[miss no-ns not-a-name a-class switched sourced state] (at/in-agent ["(my.agent/doc-of \"a-section-that-is-not-there\")"
                                                                      "(source 'no.such.ns/missing-var)"
                                                                      "(source :kw)"
                                                                      "(source 'java.lang.String)"
                                                                      "(in-ns 'my.task)"
                                                                      "(source my.agent/help)"
                                                                      "(swap! seon.agent/state assoc :zz 1)"])]
    (is (< (count (:text miss)) 1500) "a missed help address names what it did not find, not the help page")
    (is (= #{:no-section :nearest :read-one} (set (keys (:value miss)))))
    (doseq [[what r] [["no such namespace" no-ns] ["not a name" not-a-name] ["a class" a-class]]]
      (is (nil? (:ex r)) (str "source of " what " answers, it does not fail: " (pr-str (:ex r)))))
    (is (clojure.string/includes? (str (:text no-ns)) "names no namespace") "a plain no-such-var answer")
    (is (clojure.string/includes? (str (:text not-a-name)) "Source not found") "and a name that is none")
    (is (< (count (:text state)) 6000) "a bare swap! of the agent's state answers its summary, not 42,000 characters")
    (is (clojure.string/includes? (:text state) "(get (deref seon.agent/state) :db)") (str "each host object by its type, with the form that reads it: " (:text state)))
    (is (nil? (:ex sourced)) (str "source resolves in a namespace the agent switched to: " (pr-str (:ex sourced))))
    (is (clojure.string/includes? (str (:text sourced)) "defn help") "and reads the definition")))

(deftest ^{:seon.test/estimate {:ticks 2000000} :seon.test/execs ["git"]} a-done-shells-on-no-thread-of-roots
  ;; task seon.system/a-boot-runs-no-shell-of-its-own: a connection is answered on its own thread (seon.server/serve!), and
  ;; seon_done's answer reads the checkout's uncommitted files through git (seon.fx/uncommitted): clojure.java.shell called
  ;; off a fiber copies on futures, two clojure-agent-send-off-pool threads root does not declare, which outlive the answer.
  ;; Every shell runs on a fiber (seon.lib/sh, docs/machine.md#processes): that read, made from a plain thread as a connection's
  ;; is, runs its git on a fiber. A pool's thread count is no proof here (an idle one this process holds takes the copies).
  ;; Estimate: two git calls on an empty repository (a spawn and an exit event each) and one thread: ~1M ticks
  (let [dir (str (seon.config/checkout) "/target/tmp/done-shells-" (random-uuid))
        sh clojure.java.shell/sh
        on-fiber (atom [])]
    (try
      (seon.lib/sh "git" "init" "-q" dir)
      (with-redefs [clojure.java.shell/sh (fn [& args] (swap! on-fiber conj (jolt.fibers/in-fiber?)) (apply sh args))]
        (let [files (atom nil)]
          (doto (Thread. ^Runnable (fn [] (reset! files (seon.fx/uncommitted dir)))) (.start) (.join))
          (is (= [] @files) "the read answers from the thread")
          (is (= [true] @on-fiber) "and its one shell ran on a fiber, never on the thread's futures")))
      (finally (seon.lib/remove-tree! dir)))))

(deftest done-answers-the-judges-reasons
  ;; dogfood 2026-10-25: seon_done answered {:judging ...} and ended the agent, so an outside agent
  ;; never saw its verdict. It answers when main holds the judgement: here the one proof was never
  ;; written, so the done rule refuses, the task is held, and the answer says why
  (let [[done] (at/in-agent #(vector {:op :done :agent (:agent %)}))]
    ;; the answer is one line and the handle that reads the whole verdict (seon.server/verdict-line)
    (is (clojure.string/includes? (:line done) ":outcome :closed") (pr-str done))
    (is (clojure.string/includes? (:line done) "proof-unknown demo-test/t")
        "the done rule's reasons, as the judge wrote them on the held task")
    (is (clojure.string/includes? (:handle done) "seon.server/verdict"))))

(deftest an-outside-agent-changes-its-task-by-swap
  ;; the owner, 2026-10-27: "I want the ctx atom to be a mirror of the agents context that we are
  ;; pulling and they can do easy ops there on the atom rather than learning datalog". An agent
  ;; attached from outside reads its task in @ctx, changes the plan by swap! (no query, no my.*
  ;; function), and the change is on its branch, in its next @ctx, on its page, and told as its
  ;; next read, which holds the page whole (seon.ctx.wire/request: an outside read ends with the render as the call Seon made)
  (let [plan "Work: f0 returns its input."
        [first-turn _ swapped page elsewhere on-branch next-turn]
        (at/in-agent (fn [{:keys [agent task]}]
                       [{:op :context :agent agent}
                        "(def c0 (datahike.api/commit-id (seon.agent/db)))"
                        (str "(do (swap! ctx assoc-in [:seon.agent/task :seon.task/plan] " (pr-str plan) ")"
                             " (get-in @ctx [:seon.agent/task :seon.task/plan]))")
                        "(pr-str (seon.repl/render-ctx-hiccup))"
                        ;; from any namespace, the record by its qualified name (a value the printer
                        ;; would show as an earlier eval's handle is compared here instead)
                        (str "(in-ns 'demo) (= " (pr-str plan) " (get-in @seon.ctx/ctx [:seon.agent/task :seon.task/plan]))")
                        (fn [{:keys [root]}] (:seon.task/plan (d/entity (ts/read-task-branch root task) [:seon.task/id task])))
                        {:op :context :agent agent}]))]
    (is (clojure.string/includes? (:context first-turn) "\"Work.\"") "the first turn shows the plan as it was")
    (is (= plan (:value swapped)) "the next @ctx holds what the swap! wrote")
    (is (clojure.string/includes? (:value page) plan) "the render of ctx draws it")
    (is (true? (last (:value elsewhere))) "read from another namespace as @seon.ctx/ctx (the in-ns, then the read: each form's value)")
    (is (= plan (:value on-branch)) "written on the agent's branch, one transaction")
    (is (clojure.string/includes? (:context next-turn) (pr-str plan)) "the next read tells the plan as it is now")
    (is (zero? (count (re-seq #":seon.model-call/basis" (:context next-turn))))
        "and the read before it by its id, never its record: the first point describes @ctx (paths and what each holds), so no model call's basis is printed at all")
    (is (clojure.string/includes? (:context next-turn) "(my.agent/describe @ctx)") "the record described at the first point")))

(deftest a-task-is-declared-held-and-a-missing-argument-is-named
  ;; repl-audit B F3, F1: no op made a task, and an attach with no task printed root's whole system map. A plain op answers the id and starts nothing;
  ;; what a request needs is said from the ops table, for every op
  (let [[no-task _ made held no-args] (at/in-agent (fn [{:keys [ns]}]
                                        [{:op :attach}
                                         "(+ 1 1)"
                                         {:op :create :ns (str ns) :name "made" :plan "Do it."}
                                         {:op :eval :agent "root" :code (str "(:seon.task/execution (datahike.api/pull @(:conn sys) '[:seon.task/execution] [:seon.task/id " (pr-str (str ns "/made")) "]))")}
                                         {:op :create}]))]
    (is (clojure.string/includes? (:seon.error/message no-task) "task=") (pr-str no-task))
    (is (clojure.string/ends-with? (:task made) "/made") (pr-str made))
    (is (= :held (:value held)) "nothing starts" )
    (is (clojure.string/includes? (:seon.error/message no-args) "seon_create needs ns, name, plan") (pr-str no-args))))

(deftest an-outside-agents-process-test-goes-to-the-judge-and-a-red-says-why
  ;; THE PLAN 0.7's first run (root, 2026-10-27): a deftest an agent defined at its REPL that
  ;; reached a process var ran in the agent's fork and errored ("no process store"), its proof or not;
  ;; seon_prove answered only counts and red names. A process test is deferred from the fork (named, with
  ;; why), (my.agent/process-test?) says so, and each red comes with its first failing assertion
  (let [[defined deferred why answer status]
        (at/in-agent (fn [{:keys [agent]}]
                       [(str "(ns app.ptest (:require [clojure.test :refer [deftest is]]))"
                             " (deftest p-test (is (fn? seon.lib/fork!)))"
                             " (deftest r-test (is (= 1 2) \"one is two\"))")
                        "(+ 1 1)"
                        "(my.agent/process-test? 'app.ptest/p-test)"
                        {:op :prove :agent agent :names ["app.ptest"]}
                        "(my.agent/process-test? 'app.ptest/r-test)"]))]
    (is (nil? (:ex defined)) (pr-str defined))
    (is (some #{'seon.lib/fork!} (:uses (:value why))) (str "and it says why: " (pr-str why)))
    (let [r (or (:result answer) answer)]
      (is (= "one is two" (get-in r [:failures 'app.ptest/r-test :message])) (str "a red's first failing assertion: " (pr-str answer)))
      (is (some #{'app.ptest/p-test} (:deferred r)) "the service's answer names the deferred one too"))
    (is (nil? (:value status)) "an in-process test is none")))

(deftest a-later-read-draws-no-page-cold-and-costs-what-the-one-before-did
  ;; the owner, 2026-10-27: a later turn's request build measured 94M ticks, 317 MB on Seon's own tasks, 25,000
  ;; contracted calls, two pages drawn a read (the one before's again, to diff against). Each read after
  ;; the first draws the one page it tells and costs what the read before did: ticks and bytes are the
  ;; process's exact counts (seon.profile/measure!); its page is the agent's live walk's (seon.repl/page-walk),
  ;; so seon.render/draw, the cold draw, is never called, and it equals the cold draw on the db it is drawn to
  (let [[reads]
        (at/in-agent (fn [{:keys []}]
                       [(str "(let [t (:task @seon.agent/state)"
                             " m (fn [] (let [v (seon.profile/measure! #(count (seon.turn/context-block t true)))]"
                             "           {:ticks (:ticks v) :bytes (:bytes v)"
                             "            :main (let [_ (seon.repl/page-walk) db (seon.agent/db)] (:seon.agent/main-commit (datahike.api/entity db (seon.db/eid db :seon.agent/key (:key @seon.agent/state)))))"
                             "            :cold? (let [w (:walk @seon.agent/state)] (not (< (count (:ran w)) (count (:cache w)))))}))]"
                             "  {:reads (vec (repeatedly 9 m))"
                             "   :live=cold (let [pw (seon.repl/page-walk) db (seon.agent/db) a (seon.db/eid db :seon.agent/key (:key @seon.agent/state))"
                             "                    ix (seon.program/index-of-db db) t (:tree (seon.ctx/build ix db a (:seon.agent/branch (datahike.api/entity db a))))"
                             "                    cw (seon.render/walk nil {:db db :tree t :root (seon.render/render-var-of ix (merge {:seon.agent/key (:key @seon.agent/state)} t)) :index ix :changes seon.ctx/changes :model-only true :once true :outputs #{:seon.render/hiccup :seon.render/openai-compat}})"
                             "                    msgs (fn [w] (mapv first (get-in w [:answers :seon.render/openai-compat])))]"
                             "                (or (= (msgs pw) (msgs cw)) (vec (remove (set (msgs cw)) (msgs pw)))))})")]))
        later (rest (:reads (:value reads)))]
    (is (= 9 (count (:reads (:value reads)))))
    (is (true? (:live=cold (:value reads))) (str "the live walk's request is a cold walk's on the db it is drawn to; live only: " (pr-str (:live=cold (:value reads)))))
    (is (not-any? :cold? later) (str "each later read draws no page cold: its walk ran fewer components than it holds " (pr-str (map :cold? later))))
    ;; a read costs what changed (the render's views, docs/render.md): main's commit moving between two reads
    ;; (an earlier test's task merging in this root) redraws the branch views, which the task's render
    ;; keys by it (seon.render.ui/render-ctx), so only reads beside the same main are compared; measured 2026-10-29:
    ;; every read after a move cost 15.5M ticks against 9.2M, a shard run's reads moved main in 8 of 13
    (let [same (filter (fn [[a b]] (= (:main a) (:main b))) (partition 2 1 later))]
      (is (seq same) (str "some later reads follow one that saw the same main: " (pr-str (map :main later))))
      (is (every? (fn [[a b]] (<= (:ticks b) (* 1.05 (:ticks a)))) same) (str "and costs what the one before it did: " (pr-str (map (juxt :ticks :main) later))))
      (is (every? (fn [[a b]] (<= (:bytes b) (* 1.05 (:bytes a)))) same) (str "in bytes too: " (pr-str (map (juxt :bytes :main) later)))))))

(deftest a-named-prove-counts-for-status-and-the-judge-and-answers-one-line
  ;; the blind agent (root, 2026-10-27): a prove naming its proofs (171 green) left both proofs
  ;; :proof-unknown in my.task/status and at the judge, and a bare prove answered 25k chars. A proof's
  ;; result counts whichever call measured it, and the answer prints its verdict
  (let [[_ _ _ proved status drawn done]
        (at/in-agent (fn [{:keys [agent]}]
                       [{:op :context :agent agent}
                        "(ns app.okt (:require [clojure.test :refer [deftest is]])) (deftest ok-test (is (= 2 (+ 1 1))))"
                        "(my.task/update! :seon.task/proofs conj 'app.okt/ok-test)"
                        "(my.agent/prove #{'app.okt/ok-test})"
                        "(let [s (my.task/status)] [(some #(when (= 'app.okt/ok-test (:test %)) (:status %)) (:proofs s)) (filterv #(= 'app.okt/ok-test (:symbol %)) (:refused s (:reasons s)))])"
                        ;; the prove's eval as recorded: its value, data, under the schema the page draws it by
                        "(let [e (datahike.api/q '[:find [?e ?k] :in $ ?c :where [?e :seon.eval/code ?c] [?e :seon.eval/response _] [?e :seon.eval/schema ?k]] (seon.agent/db) \"(my.agent/prove #{'app.okt/ok-test})\")
                               v (seon.ratom/realize (:value (seon.db/response-of (seon.agent/db) (datahike.api/pull (seon.agent/db) '[:seon.eval/response :seon.eval/result] (first e)))))]
                           [(second e) (:proofs v) (seon.web.html/html (seon.render.ui/proved {:seon.render/index (seon.db/index)} v))])"
                        {:op :done :agent agent}]))]
    ;; the owner, 2026-10-27: an eval's answer stays small, its handle and one line; what the run
    ;; found is drawn on its eval, from its value (seon.render.ui/proved)
    (is (clojure.string/includes? (:text proved) "green: ") (:text proved))
    (is (< (count (:text proved)) 300) (str "the answer is its handle and one line: " (count (:text proved)) " chars"))
    (is (= [:pass []] (:value status)) (str "my.task/status: the proof's status, and no reason about it: " (pr-str status)))
    (let [[k proofs html] (:value drawn)]
      (is (= :seon.test/proved k) (str "the eval's record names the value's render schema: " (pr-str drawn)))
      (is (= :pass (get proofs 'app.okt/ok-test)) "the value is the run, stored as data")
      (is (and (string? html) (clojure.string/includes? html "green: ") (clojure.string/includes? html "app.okt/ok-test"))
          (str "drawn on the eval: the line, then each proof's status: " html)))
    ;; the harness's task also names demo-test/t, which no test defines: that one is the judge's unknown
    (is (not (clojure.string/includes? (pr-str done) "proof-unknown app.okt/ok-test")) (str "and the judge counts it: " (pr-str done)))))

(deftest an-attach-gives-the-caller-the-branch-or-names-who-holds-it
  ;; THE PLAN 0.7 (root, 2026-10-27): the first seon_attach to a task was refused "held by live
  ;; agent :a7", an agent the caller never attached. A context read of a task no agent works attaches
  ;; one (the :context op); the caller's attach after it is the same outside run: it answers that
  ;; agent, never a refused request. A task another task's agent holds the branch of is refused, naming the
  ;; agent and the task it works
  (let [[ctx attached again other]
        (at/in-agent (fn [{:keys [ns]}]
                       [{:op :context :task (str ns "/u")}
                        {:op :attach :task (str ns "/u")}
                        {:op :attach :task (str ns "/u")}
                        {:op :attach :task (str ns "/v")}])
                     {:decls (fn [{:keys [ns]}]
                               [{:seon.task/name "u" :seon.task/plan "Work too." :seon.task/kind :work :seon.task/proofs #{'demo-test/u}}
                                {:seon.task/name "v" :seon.task/plan "Work three." :seon.task/kind :work
                                 :seon.task/proofs #{'demo-test/v} :seon.task/branch (keyword (str "task-" ns "-t"))}])})]
    (is (string? (:context ctx)) "the context read attached an agent to u")
    (is (keyword? (:agent attached)) (pr-str attached))
    (is (true? (:shared attached)) "the context read's agent, shared: its seon_done ends it for both")
    (is (= [(:agent attached) (:task attached)] [(:agent again) (:task again)]) "attaching again answers the same agent and task")
    (is (contract/error? other) "v's branch is t's, which t's agent holds: refused, as an answer, never a server defect")
    (is (= :seon.system/refused (:seon.error/type other)) (pr-str other))
    (is (re-find #"held by live agent :[a-p][0-9a-f]{15} working [a-p][0-9a-f]{15}/t: its seon_done" (str (:seon.error/message other))) (pr-str other))))

(deftest a-prove-never-ends-the-agent-it-proves
  ;; THE PLAN 0.7 (root, 2026-10-27): after seon_prove, the outside agent had ended ("agent a7
  ;; ended"), so my.task/fail! and seon_done recorded nothing ("a7 works no task"). An outside agent
  ;; lives until seon_done or an explicit end: a prove of its branch, then its REPL and its run.
  ;; Each prove names one small namespace: an unnamed one proved all of main inside this test (568 s
  ;; alone, 2026-10-30, and its shard's root stopped under it); the agent's life is what is proved here
  (let [alive #(hash-map :op :eval :agent "root" :code (str "(contains? (:agents (c/state sys)) :" % ")"))
        [ctx proved after live1 failed]
        (at/in-agent (fn [{:keys [agent]}]
                       [{:op :context :agent agent}
                        {:op :prove :agent agent :names ["seon.union-test"]}
                        "(+ 1 2)"
                        (alive agent)
                        "(my.task/fail! \"cannot\")"]))]
    (is (string? (:context ctx)) (pr-str ctx))
    (is (not (contract/error? proved)) (pr-str proved))
    (is (= 3 (:value after)) (str "the agent answers after a prove of its branch: " (pr-str after)))
    (is (true? (:value live1)) "and root holds it live")
    (is (not (contract/error? (:value failed))) (str "its fail! records on its task: " (pr-str failed)))))

;; docs/turn.md#an-outside-agent: an attached agent reads the page as the model does, Seon's message of the turn, and what moved after
(deftest an-outside-agents-read-carries-the-page-and-what-moved
  (let [[_ first-read _ moved-read]
        (at/in-agent (fn [{:keys [agent task]}]
                       [{:op :attach :task task}
                        {:op :context :agent agent}
                        {:op :eval :agent agent :message-md "Changing my plan." :evals [{:intent "change it" :code "(swap! ctx assoc-in [:seon.agent/task :seon.task/plan] \"A plan that moved.\")"}]}
                        {:op :context :agent agent}]))]
    (is (clojure.string/includes? (str (:context first-read)) "(my.render/page ") "the page, the first time")
    (is (clojure.string/includes? (str (:context moved-read)) "A plan that moved.") "and what moved, the next time")))

(deftest green-work-behind-main-is-merged-at-done
  ;; an outside agent's green work was closed "ended green: behind 6", its agent gone, while each answer said to merge when it chose: seon.turn/hand-back! merges main first.
  ;; And the judge at its end asks the test service (task seon.reconcile/the-judge-asks-the-test-service), so its proof's verdict is cached at its digest.
  ;; On the shard's root: what it merges is its own namespaces, which no other test reads
  (let [[_ _ _ _ done cached]
        (at/in-agent (fn [{:keys [agent ns]}]
                       [{:op :attach :task (str ns "/t")}
                        (str "(ns " ns "-test (:require [clojure.test :refer [deftest is]]))")
                        "(deftest ^{:seon.test/estimate {:ticks 100000}} t (is (= 2 (inc 1))))"
                        {:op :eval :agent "root" :code (str "(let [db @(:conn sys)] @(c/transact-main! sys ((my.test/code '" ns ".behind \"(ns " ns ".behind) (defn f \\\"One.\\\" {:malli/schema [:=> [:cat] :int]} [] 1)\") db)))")}
                        {:op :done :agent agent}
                        {:op :eval :agent "root" :code (str "(some-> (java.io.File. (str (seon.config/value :seon.config.proofs/results-dir) \"/" ns "-test\")) .list vec)")}])
                     {:proofs (fn [{:keys [ns]}] #{(symbol (str ns "-test") "t")})})]
    (is (not (clojure.string/includes? (str (:line done)) "behind")) (pr-str done))
    (is (clojure.string/includes? (str (:line done)) ":merged") (pr-str done))
    (is (= ["t"] (:value cached)) (str "the judge's verdict is cached at the test's digest: " (pr-str cached)))))

(deftest the-done-answer-is-a-line-and-a-handle
  ;; one-wait's real run (root, 2026-10-28): seon_done answered 73,856 characters, the whole verdict
  ;; with every red. A line, each red with its first failure, cut by the one printer, and the form
  ;; that reads the whole
  (let [reds (vec (for [i (range 300)] (symbol "a.t-test" (str "x" i))))
        db (d/db-with (ts/test-db) [{:seon.task/id "a/t" :seon.task/execution :held :seon.task/outcome :closed :seon.task/reason "red"
                                     :seon.task/failure {:reason (str "proof-red " (clojure.string/join " " reds))}
                                     :seon.task/evidence {:from "a/t" :conflicts [] :unmerged [] :red reds}
                                     :seon.task/failures {'a.t-test/x0 [{:type :fail :message "expected 1, got 2"}]}}])
        {:keys [line handle]} (seon.server/verdict-line db "a/t" "main moved since your base: 2 names changed")]
    (is (clojure.string/includes? line "expected 1, got 2") (str "a red with its first failure: " line))
    (is (clojure.string/includes? line "main moved since your base") "how main differs, so nothing at done is new")
    (is (< (count line) 4000) (str "300 reds, one line: " (count line) " characters"))
    (is (= "(seon.server/verdict @(:conn sys) \"a/t\")" handle) "the form that reads it whole")))

(deftest a-dead-agent-never-wedges-its-task
  ;; one-wait's real run (root, 2026-10-28): an agent that died left its task :running, and
  ;; seon_attach answered the dead agent, shared, so every eval said "agent a2 ended" with no way back.
  ;; A death is a fact root's view holds: attach continues the session's agent on its branch with a new process
  (let [agent-of "(some #(when (= \"%s/u\" (:task %%)) %%) (seon.task/running @(:conn sys)))"
        pid-of (fn [ns] {:op :eval :agent "root"
                         :code (str "(:pid (get (:agents (c/state sys)) (:agent " (format agent-of ns) ")))")})
        key-of (fn [ns] {:op :eval :agent "root" :code (str "(:seon.task/agent (seon.task/task @(:conn sys) \"" ns "/u\"))")})
        kill (fn [ns] {:op :eval :agent "root"
                       :code (str "(let [ev (c/events sys) r " (format agent-of ns)
                                  "      a (:agent r)]"
                                  "  (c/kill! sys a)"
                                  "  (loop [] (let [e (clojure.core.async/<!! ev)] (when-not (and (#{:ended :dead :killed} (:type e)) (= a (:agent e))) (recur))))"
                                  "  (clojure.core.async/untap (:events-mult sys) ev) a)")})
        [first-a pid1 key1 _ second-a pid2 key2]
        (at/in-agent (fn [{:keys [ns]}] [{:op :attach :task (str ns "/u")} (pid-of ns) (key-of ns) (kill ns) {:op :attach :task (str ns "/u")} (pid-of ns) (key-of ns)])
                     {:decls (fn [_] [{:seon.task/name "u" :seon.task/plan "Work too." :seon.task/kind :work :seon.task/proofs #{'demo-test/u}}])})]
    (is (keyword? (:agent first-a)) (pr-str first-a))
    (is (keyword? (:agent second-a)) (str "attach after the death starts an agent: " (pr-str second-a)))
    (is (and (string? (:value key1)) (= (:value key1) (:value key2))) (str "the session's agent continues (its key), as a retry after a death is: " (pr-str [key1 key2])))
    (is (and (int? (:value pid1)) (int? (:value pid2)) (not= (:value pid1) (:value pid2))) (str "in a new process, never the dead one: " (pr-str [pid1 pid2])))
    (is (not (:shared second-a)) "its own start, not the dead agent's")))

(deftest a-context-read-that-only-looks-writes-nothing
  ;; owner, 2026-11-10: "A read never writes". An observer's read of an agent (take? false: anyone's but its outside model's) commits
  ;; nothing and records nothing: the branch's t and commit are the same after it; and no :context read, an outside model's own
  ;; included, leaves an eval of its print form on the branch (the outside model's read is its turn: a model call, never an eval)
  (let [[looked _ evals] (at/in-agent (fn [{:keys [agent task]}]
                                        [(str "(let [db0 (seon.agent/db) _ (seon.turn/context-block " (pr-str task) " false) db1 (seon.agent/db)]"
                                              " {:t [(:max-tx db0) (:max-tx db1)] :commit [(datahike.api/commit-id db0) (datahike.api/commit-id db1)]})")
                                         {:op :context :agent agent}
                                         (fn [{:keys [root]}]
                                           (d/q '[:find [?c ...] :where [?e :seon.eval/code ?c] [(clojure.string/starts-with? ?c "(print (seon.turn/context-block")]]
                                                (ts/read-task-branch root task)))]))
        {:keys [t commit]} (:value looked)]
    (is (some? t) (pr-str looked))
    (is (apply = t) "the branch's t is the same after an observer's read")
    (is (apply = commit) "and nothing was committed for it")
    (is (empty? (:value evals)) "no read left an eval of its print form")))

(deftest a-sessions-checkout-is-proved-on-its-trees-branch
  ;; docs/merging.md#a-session-proves-through-root (Flow step 3, with Tests): :prove with :checkout and :tree proves the checkout's
  ;; files on a branch keyed by the tree, every asker of one tree sharing its branch, agent and run key. Red before: :checkout was
  ;; ignored and the op proved main
  (let [checkout (seon.config/checkout)
        root (ts/shared-root! ["--set" (str ":seon.config.source/root=" (pr-str checkout))])
        tree (seon.fx/tree-id checkout)
        ask #(ts/ask root {:op :prove :checkout checkout :tree tree :names ["seon.ref-test"]})
        one (ask)
        two (ask)]
    (is (clojure.string/starts-with? (str (:key one)) (str "tree-" tree "@")) (pr-str one))
    (is (= (:key one) (:key two)) "a second ask of one tree is the same run, on the same branch")
    (is (pos? (+ (or (:pass one) 0) (or (:reused one) 0))) (pr-str one))
    ;; the owner, 2026-11-11 ("Yes, drop them"): no run of it in flight, the tree's branch is dropped, its agent ended
    (is (= [] (ts/value-of (:response (ts/ask root {:op :eval :agent "root"
                                                   :code (str "(filterv #(clojure.string/ends-with? (name %) " (pr-str (str "tree-" tree)) ") (datahike.api/branches (:conn sys)))")}))))
        "the tree's branch is gone once its runs answered")))

(deftest ^{:seon.test/estimate {:ticks 3000000}} bin-prove-asks-a-root-and-prints-each-verdict-as-it-lands
  ;; docs/merging.md#a-session-proves-through-root (Flow step 3, the client): bin/prove --root PORT asks that root to prove its
  ;; checkout's tree, prints each test's verdict as root streams it, and ends on the answer. The client is seon.main/prove-client:
  ;; here it is fed root's stream as values (seon.server/ask-streamed's events, then its answer), so what is proved is what it asks
  ;; and what it prints; a port no root listens on goes through the real ask-streamed, a refused connect.
  ;; Estimate: three client calls, each a few out! lines and one shown map (~100k ticks each), one refused connect: ~1M ticks
  (let [asked (atom [])
        stream (fn [events answer] (fn [port m on-event] (swap! asked conj [port m]) (run! on-event events) answer))
        prove (fn [ask & names] (with-redefs [seon.server/ask-streamed ask]
                                  (let [exit (atom nil)
                                        out (with-out-str (reset! exit (seon.main/prove-client 7 "/co" (vec names))))]
                                    {:exit @exit :out out :lines (clojure.string/split-lines out)})))
        before? (fn [lines a b] (let [i (.indexOf ^java.util.List lines a) j (.indexOf ^java.util.List lines b)] (and (<= 0 i) (< i j))))]
    (with-redefs [seon.fx/tree-id (constantly "t1")]
      (let [green (prove (stream [{:type :prove-verdict :test 'a.x-test/a-green :status :pass :ms 12.4}
                                  {:type :prove-held :held true :load 20 :max-load 18 :queued 3}
                                  {:type :prove-held :held false}]
                                 {:key "tree-t1@1" :selected ['a.x-test/a-green] :ran 1 :pass 1})
                         "a.x-test")
            red (prove (stream [{:type :prove-verdict :test 'a.x-test/a-red :status :fail :failures [{:message "expected 1"}]}]
                               {:key "tree-t1@2" :selected ['a.x-test/a-red] :ran 1 :fail 1 :red ['a.x-test/a-red]}))
            ;; a port no root listens on: bound for its number, then let go
            free (let [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))] (.close s) (.getLocalPort s))
            none (let [exit (atom nil) out (with-out-str (reset! exit (seon.main/prove-client free "/co" [])))]
                   {:exit @exit :out out :lines (clojure.string/split-lines out)})]
        (is (= [7 {:op :prove :checkout "/co" :tree "t1" :mode "judge" :stream true :names ["a.x-test"]}] (first @asked))
            "it asks for the checkout's tree, streamed, named by what it was given")
        (is (not (contains? (second (second @asked)) :names)) "and names nothing when given nothing")
        (is (= 0 (:exit green)) (:out green))
        (is (= "PROVE OK" (last (:lines green))) (:out green))
        (is (before? (:lines green) "PASS a.x-test/a-green 12 ms" "HELD: load 20 >= max-load 18, 3 items waiting")
            (str "the green's verdict printed as it landed, then the hold, never a silent wait: " (:out green)))
        (is (some #{"RESUMED"} (:lines green)) (:out green))
        (let [refused (prove (stream [] (contract/error :seon.system/binary-not-current 'seon.system/tree-refusal
                                                        "this tree can't be proved on the running system: it changes what the running binary bakes (reference-code/jolt); its tests would run on the system's build of them. Prove it on its own build: bin/prove --out DIR"
                                                        {:binary "b0" :tree "t1" :baked ["reference-code/jolt"]})))]
          (is (= [1 "PROVE FAILED"] [(:exit refused) (last (:lines refused))]) (:out refused))
          (is (clojure.string/includes? (:out refused) "bin/prove --out") "a tree the system can't run is refused, pointing at its own build"))
        (is (= 1 (:exit red)) (:out red))
        (is (= "PROVE FAILED" (last (:lines red))) (:out red))
        (is (before? (:lines red) "RED a.x-test/a-red (fail)" "prove: red a.x-test/a-red")
            (str "the red's verdict (:fail) printed before the answer, which names it: " (:out red)))
        (is (= 1 (:exit none)) (:out none))
        (is (= ["PROVE FAILED"] (take-last 1 (:lines none))) (:out none))
        (is (clojure.string/includes? (:out none) "refused the connection") "no root there: a typed end, never a wait")))))

(deftest two-sessions-proving-one-tree-at-once-each-get-every-verdict
  ;; docs/merging.md#a-session-proves-through-root (Flow step 4, two askers of one tree): two clients ask root for the same tree at
  ;; the same time, each through seon.server/ask-streamed as bin/prove --root does, from two futures of this process released together.
  ;; Red before the Jolt pin ec4c1e44: two of them first using a socket at once threw "No dependency provides java.net.Socket".
  ;; One run, each item once: a second asker arriving after the run would be answered from :done with :ran 0, so :ran 2 in
  ;; both answers and one :key say both joined the run in flight. The checkout is a
  ;; worktree of this tree with one test namespace of two green tests added (a run id in it, so no verdict is reused)
  (let [here (seon.config/checkout)
        root (ts/shared-root! ["--set" (str ":seon.config.source/root=" (pr-str here))])
        tmp (.getCanonicalPath (doto (java.io.File. (ts/mark-of (str (System/getProperty "java.io.tmpdir") "/seon-two-askers"))) (.mkdirs)))
        wt (str tmp "/wt")
        run (str (random-uuid))
        git (fn [& args] (let [r (apply clojure.java.shell/sh "git" "-C" here "-c" "user.name=t" "-c" "user.email=t@t" args)]
                           (is (zero? (:exit r)) (str args (:err r))) (clojure.string/trim (:out r))))
        links (for [f ["seon" "lib" "home"]] (java.io.File. (str wt "/target/" f)))]
    (try
      (git "worktree" "add" "-q" "--detach" wt (git "commit-tree" (seon.fx/tree-id here) "-p" "HEAD" "-m" "two askers' fixture"))
      (spit (str wt "/test/seon/two_askers_test.clj")
            (str "(ns seon.two-askers-test (:require [clojure.test :refer [deftest is]]))\n\n(def run " (pr-str run) ")\n\n"
                 "(deftest one (is (string? run)))\n(deftest two (is (string? run)))\n"))
      (.mkdirs (java.io.File. (str wt "/target")))
      (doseq [^java.io.File l links]
        (java.nio.file.Files/createSymbolicLink (.toPath l) (.toPath (java.io.File. (str here "/target/" (.getName l)))) (make-array java.nio.file.attribute.FileAttribute 0)))
      (let [port (:port root)
            tree (seon.fx/tree-id wt)
            go (promise)
            asker (fn [] (let [events (atom [])]
                           ;; both released together; the wait ends at the answer or the connection's end (ask-streamed)
                           (future @go
                                   (let [answer (seon.server/ask-streamed port {:op :prove :checkout wt :tree tree :mode "judge" :stream true :names ["seon.two-askers-test"]}
                                                                          #(swap! events conj %))]
                                     {:answer answer :events @events}))))
            a (asker)
            b (asker)
            _ (deliver go true)
            ;; each wait ends with ask-streamed's: its answer or the connection's end (no clock of its own)
            ra @a
            rb @b
            tests (fn [r] (->> (:events r) (filter #(= :prove-verdict (:type %))) (filter :test)))]
        (do
          (is (not (contract/error? (:answer ra))) (pr-str (:answer ra)))
          (is (not (contract/error? (:answer rb))) (pr-str (:answer rb)))
          (is (= #{'seon.two-askers-test/one 'seon.two-askers-test/two} (set (map :test (tests ra)))) (pr-str (:events ra)))
          (is (= (set (map (juxt :test :status) (tests ra))) (set (map (juxt :test :status) (tests rb))))
              "both askers got the same verdicts")
          (is (= [:pass :pass] (map :status (tests ra))) "each verdict once, green")
          (is (= 2 (count (tests ra)) (count (tests rb))) "every test's verdict once for each asker, none twice")
          (is (= (:key (:answer ra)) (:key (:answer rb))) "one run of the tree for both")
          (is (= 2 (:ran (:answer ra)) (:ran (:answer rb))) (pr-str [(:answer ra) (:answer rb)]))
          (is (empty? (:red (:answer ra))))
          (is (empty? (:red (:answer rb))))))
      (finally
        (doseq [^java.io.File l links] (.delete l))
        (clojure.java.shell/sh "git" "-C" here "worktree" "remove" "--force" wt)
        (seon.lib/remove-tree! tmp)))))

;; docs/server.md#ops: a streaming :prove writes every verdict its answer names, though the answer came before the verdict's
;; event (two paths with no order between them: root's events mult and the asker's answer) or the event never came (a
;; sliding tap); one the events brought is not written twice. The race is forced: the run answers before any event is put.
(deftest a-streamed-prove-writes-every-verdict-its-answer-names
  (ts/test-branch!
   (fn [_ conn]
     (let [src (a/chan 8)
           sys {:graph nil :ext (a/chan) :conn conn :events-mult (a/mult src)}
           v1 {:test 'app.s-test/one :digest "d1" :status :pass :ms 3}
           v2 {:test 'app.s-test/two :digest "d2" :status :pass :ms 4}
           k "tree-x@d:app.s-test"
           lines (atom [])
           seen (promise)
           stream (fn [run] (binding [seon.server/*emit* (fn [l] (swap! lines conj l) (when (= 'app.s-test/one (:test l)) (deliver seen true)))]
                              (#'seon.server/prove-streamed sys :tree-x run)))]
       ;; the answer first, no event at all: today nothing is written
       (stream (fn [] {:key k :ran 2 :verdicts [v1 v2]}))
       (is (= [['app.s-test/one :pass] ['app.s-test/two :pass]] (mapv (juxt :test :status) @lines)) (pr-str @lines))
       (is (every? #(and (= :prove-verdict (:type %)) (= k (:key %))) @lines) "each line a verdict of the run")
       ;; one brought live, then the answer: one is not written again, two is written from the answer
       (reset! lines [])
       (stream (fn [] (a/>!! src {:type :prove-verdict :key k :test 'app.s-test/one :digest "d1" :status :pass :ms 3})
                 @seen
                 {:key k :ran 2 :verdicts [v1 v2]}))
       (is (= ['app.s-test/one 'app.s-test/two] (mapv :test @lines)) "each verdict once, the live one first")))))

;; docs/server.md#ops: seon_done answers once the judge has decided the task's own work, at :gate too: the merge gate's run of
;; the process tests the change reaches can outlast any client's wait (242 items, ~30 min, Data model's real task, 2026-11-11:
;; the client gave up while the verdict was still to come). Red before: :gate was a state the done waited through
(deftest done-answers-when-the-tasks-own-work-is-judged-not-after-its-gate
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}]))
     (let [at (fn [o] (d/transact conn [{:seon.task/id "demo/g" :seon.task/outcome o}]) @conn)
           settled? (fn [db] (#'seon.server/done-settled? db "demo/g" (constantly nil)))]
       (is (not (settled? (at :running))) "still working: wait")
       (is (not (settled? (at :ready))) "judged green, its merge next: wait (minutes)")
       (is (not (settled? (at :gate))) "a gate with no process test to run is its merge (minutes): wait, as at :ready")
       (d/transact conn [{:seon.task/id "demo/g" :seon.task/deferred {'demo-test/p "d-p"}}])
       (is (settled? (at :gate)) "its gate running process tests: answer now, the verdict comes to the page")
       (is (settled? (at :merged)))
       (is (#'seon.server/done-settled? (at :running) "demo/g" (constantly {:seon.error/type :x})) "a defect stopped the judge: answer")))))

;; docs/errors.md#nothing-crashes-and-nothing-hangs: a streamed prove whose run throws answers that throw as a typed defect: a
;; spawned fiber keeps its throw for a joiner, and nobody joined this one, so its reader waited on done for good (code read,
;; 2026-11-12). Red before: Jolt's deadlock detector ended the wait ("every fiber and thread of this process waits")
(deftest a-streamed-prove-whose-run-throws-answers-the-throw
  (ts/test-branch!
   (fn [_ conn]
     (let [src (a/chan 8)
           sys {:graph nil :ext (a/chan) :conn conn :events-mult (a/mult src)}
           r (binding [seon.server/*emit* (fn [_])]
               (#'seon.server/prove-streamed sys :tree-x (fn [] (throw (ex-info "the run broke" {})))))]
       (is (= :seon.server/defect (:seon.error/type r)) (pr-str r))
       (is (re-find #"the run broke" (str (:seon.error/message r))))))))

;; Estimate: a root booted on a scratch repo of two files (~a shared root's boot, counted as the root's, not the test's ticks) + 8 asks:
;; a create and an attach (~0.3M each), an import of two files (two files' forms, ~0.5M), a prove of one in-process test (~1M), a done
;; (its judge and merge, ~3M), a git read: ~6M ticks
(deftest ^{:seon.test/root [] :seon.test/execs ["git"] :seon.test/estimate {:ticks 6000000 :asks 8}} a-sessions-edit-reaches-gits-main-through-done
  ;; Acceptance for plan Stage 1 (the owner, 2026-11-14: Claude sessions work, prove and merge through the system; task
  ;; seon.server/a-sessions-file-edit-merges-through-done). A session's agent on its task; the session edits files in its checkout; the
  ;; import writes them onto the agent's branch (my.branch/import!, the agent's own writer); the agent proves; seon_done merges; git's
  ;; main holds the edit. No git worktree and no bin/land. Red first: each step's mechanism lands as a path task (runtime's import,
  ;; hold-env!, the drain; git following the store, Stage 1.7). A new task entry's reaching main is the one merge's, so this asserts code
  (let [d (str (java.nio.file.Files/createTempDirectory "seon-acceptance" (make-array java.nio.file.attribute.FileAttribute 0)))
        git (fn [& args] (let [r (apply clojure.java.shell/sh "git" "-C" d args)] (assert (zero? (:exit r)) (pr-str [args r])) (:out r)))
        file (fn [p text] (let [f (java.io.File. (str d "/" p))] (.mkdirs (.getParentFile f)) (spit f text)))
        app "src/acc/app.clj"
        app-test "test/acc/app_test.clj"]
    (git "init" "-q" "-b" "main")
    (git "config" "user.email" "seon@example.com") (git "config" "user.name" "seon")
    (file app "(ns acc.app\n  \"An app a session works on. See docs/testing.md#testing-in-the-world.\")\n\n(defn f\n  \"One.\"\n  []\n  1)\n")
    (git "add" "-A") (git "commit" "-q" "-m" "the app")
    (let [;; the path it walks, named here: the agent evaluates each from text, which no reference shows, so a change to one of them
          ;; reruns this only by these edges (seon.program/reach-step)
          _ '[my.branch/import! my.agent/prove seon.turn/hand-back! seon.server/attach! seon.reconcile/done-explain]
          root (ts/shared-root! ["--set" (str ":seon.config.source/root=" (pr-str d))])
          ask (fn [m] (ts/ask root m))
          created (ask {:op :create :ns "acc.app" :name "f-is-two" :plan "Make acc.app/f answer 2." :proofs ["acc.app-test/f-is-two"]})
          {:keys [agent]} (ask {:op :attach :task "acc.app/f-is-two"})
          ;; the session edits its checkout: the function and the proof that judges it
          _ (file app "(ns acc.app\n  \"An app a session works on. See docs/testing.md#testing-in-the-world.\")\n\n(defn f\n  \"Two.\"\n  []\n  2)\n")
          _ (file app-test "(ns acc.app-test\n  (:require [clojure.test :refer [deftest is]] [acc.app :as app]))\n\n(deftest ^{:seon.test/estimate {:ticks 100000}} f-is-two\n  (is (= 2 (app/f))))\n")
          eval-in (fn [code] (ts/value-of (:response (ask {:op :eval :agent (name agent) :code code}))))
          imported (eval-in (str "(my.branch/import! [" (pr-str app) " " (pr-str app-test) "])"))
          proved (eval-in "(:proofs (my.agent/prove))")
          done (ask {:op :done :agent agent})]
      (is (some? agent) (pr-str created))
      (is (= #{app app-test} (set (:imported imported))) (str "both files on the agent's branch, through its own writer: " (pr-str imported)))
      (is (= :pass (get proved 'acc.app-test/f-is-two)) (str "proved in the system: " (pr-str proved)))
      (is (clojure.string/includes? (str (:line done)) ":merged") (str "merged by Seon's merge: " (pr-str done)))
      (is (clojure.string/includes? (git "show" (str "main:" app)) "  2)") "and git's main holds the edit, with no worktree and no bin/land"))))
