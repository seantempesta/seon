(ns seon.server-test
  "Root's server as an outside agent reaches it (seon.server, through the built root:
  seon.agent-test/in-agent). Run: bin/test seon.server-test"
  (:require [clojure.test :refer [deftest is]] [jolt.fibers] [seon.agent-test :as at] [seon.contract :as contract] [seon.server] [clojure.data.json] [clojure.edn] [clojure.string]
            [seon.test-store :as ts] [seon.task :as task] [seon.main] [seon.fx] [seon.lib] [datahike.api :as d] [seon.db :as pdb] [clojure.java.shell] [clojure.core.async :as a] [my.root] [seon.merge] [seon.edn]))

(deftest each-op-has-its-answer
  ;; the ops table (each op's doc and args, the MCP tool list) and op-answer's methods are one set of
  ;; keys: an op with no method would be listed and answer "unknown op", a method with no entry unlisted
  (is (= (set (keys seon.server/ops)) (disj (set (keys (methods seon.server/op-answer))) :default)))
  (is (contains? (methods seon.server/op-answer) :default) "an unknown op has its answer: refused"))

;; Estimate: one memory store made, one server socket, two request lines read and answered: ~1M ticks; 2,000,000
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-request-to-a-stopping-root-answers-restarting
  ;; the switch-over's L2 step 1 (task seon.system/an-update-loses-nothing: close the edges): once root's end has begun (its cause
  ;; delivered: a hand-over, a stop), every new request line, EDN or MCP's JSON-RPC, is answered :seon.system/restarting, so its
  ;; caller re-asks the next root on the same port. Red before: the request ran on the stopping root
  (let [cfg (pdb/made-cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :writer pdb/writer-cfg})
        _ (d/create-database cfg)
        conn (pdb/connect cfg)
        ss (seon.server/serve! {:graph nil :ext (doto (a/chan) a/close!) :conn conn :ending (deliver (promise) :hand-over)} 0)
        ask (fn [line] (with-open [s (java.net.Socket. "127.0.0.1" (int (.getLocalPort ^java.net.ServerSocket ss)))]
                         (let [w (.getOutputStream s) r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "UTF-8"))]
                           (.write w (.getBytes (str line "\n") "UTF-8")) (.flush w)
                           (.readLine r))))]
    (try
      (let [e (clojure.edn/read-string {:default tagged-literal} (ask "{:op :interrupt :client \"c\"}"))]
        (is (= :seon.system/restarting (:seon.error/type e)) (pr-str e)))
      (let [j (clojure.data.json/read-str (ask "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"seon_interrupt\",\"arguments\":{}}}"))]
        (is (clojure.string/includes? (str (get-in j ["error" "message"])) "restarting") (pr-str j)))
      (finally (.close ^java.net.ServerSocket ss) (d/release conn)))))

(deftest seon-agents-answers-what-is-live
  ;; seon_agents answered every task on main (580 on the standing root, most :held or
  ;; :cancelled with no agent), so an outside agent never saw what runs. It reads no reconciler index
  (ts/test-branch!
   (fn [_ conn]
     (let [before (:others (seon.server/live-tasks @conn))
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
       ;; web, 2026-11-16 (the owner's rip-out of bad designs, #1): seon_agents kept its own copy of the reconciler's index, advanced by
       ;; what main moved, whose first answer after a move cost more than a whole index; it reads main and root's view instead
       (is (contains? (:tasks (seon.server/live-tasks @conn ["demo/a"])) "demo/a") "a task a live agent works, named by root's view, though held")
       (is (= (:held others) (inc (:held (:others (seon.server/live-tasks @conn ["demo/a"]))))) "and no longer counted among the held")))))

;; Estimate: an empty test branch ~0.3M and three answers that refuse before any proc is asked: ~0.4M; 1,000,000
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-server-refused-answer-is-the-one-error-map
  ;; task seon.render/one-owner-per-view-path (B2): the server refuses as root does, with the one error map, never a second {:error "text"} shape
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           answers [(seon.server/answer sys {:op :nope}) (seon.server/answer sys {:op :attach :task "no/such"})]]
       (is (= 2 (count answers)))
       (doseq [a answers]
         (is (contract/error? a) (pr-str a))
         (is (nil? (:error a)) (pr-str a)))))))

;; Estimate: an empty test branch ~0.3M, one answer that throws in attach!'s contract and one that refuses: ~0.5M; 1,000,000
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-server-defect-answers-that-request-alone
  ;; task seon.server/a-server-defect-answers-that-request-alone: a request whose op throws (attach! refuses a task id that is not a string: its
  ;; contract) is answered the typed :seon.server/defect naming the line it took, and the server answers the next request
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           ask #(clojure.edn/read-string (#'seon.server/respond sys (pr-str %)))
           defect (ask {:op :attach :task 5})
           next (ask {:op :nope})]
       (is (= :seon.server/defect (:seon.error/type defect)))
       (is (= (pr-str {:op :attach :task 5}) (:line defect)))
       (is (= :seon.system/refused (:seon.error/type next)) (str "the server goes on: " (pr-str next)))))))

;; See docs/server.md#ops
;; Estimate: the shard's shared root (~0 here), five root evals and two stops through the server (~0.25M each), two sockets
;; (~10k ticks each): ~1.8M; 2,000,000 ticks, worked from the work.
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 2000000} :seon.test/execs ["git"]} a-caller-stops-its-own-op
  ;; Stage 1's safety item: a root eval has no work bound, so what ends one that waits for good is its caller's stop:
  ;; seon_interrupt (EDN :interrupt, every op of its client) and MCP's notifications/cancelled (the one request id), each
  ;; answered on the connection's fiber. The blocked eval is answered, and the server goes on. The residue, named: a client
  ;; killed outright sends no cancel; a bounded op still ends at its bound, but root's :eval runs on until seon_interrupt or root's stop
  (let [root @ts/shared-root-default
        ask #(ts/ask root %)
        tag (str "stop-" (System/nanoTime))
        line! (fn [s l] (let [o (.getOutputStream ^java.net.Socket s)] (.write o (.getBytes (str l "\n") "UTF-8")) (.flush o)))
        ;; a red that is never answered stalls on the read, and the runner ends it (seen red with the owner's stop! removed)
        open! #(java.net.Socket. "127.0.0.1" (int (:port root)))
        read! #(.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream ^java.net.Socket %) "UTF-8")))
        started (fn [p] (str "(do (deliver root.repl/" p " true) @(promise))"))
        _ (ask {:op :eval :agent "root" :code "(do (def e1 (promise)) (def e2 (promise)))"})
        ;; EDN: an eval that waits for good, stopped by its client's seon_interrupt once it runs
        s1 (open!)
        _ (line! s1 (pr-str {:op :eval :agent "root" :client tag :code (started "e1")}))
        _ (ask {:op :eval :agent "root" :code "@e1"})
        ;; item 4: the :server-op owner told the api's view its running set (seon.fx/running-told)
        running (ask {:op :eval :agent "root" :code "(mapv :client (get-in (c/state sys) [:server-ops :server-op]))"})
        stop1 (ask {:op :interrupt :client tag})
        a1 (clojure.edn/read-string (read! s1))
        ;; MCP: the same wait, as tools/call id 7, stopped by notifications/cancelled naming request 7
        s2 (open!)
        _ (line! s2 (clojure.data.json/write-str {"jsonrpc" "2.0" "id" 7 "method" "tools/call" "seon/client" (str tag "-mcp")
                                                  "params" {"name" "seon_eval" "arguments" {"agent" "root" "code" (started "e2")}}}))
        _ (ask {:op :eval :agent "root" :code "@e2"})
        _ (with-open [s (open!)]
            (line! s (clojure.data.json/write-str {"jsonrpc" "2.0" "method" "notifications/cancelled" "seon/client" (str tag "-mcp") "params" {"requestId" 7}})))
        a2 (clojure.data.json/read-str (read! s2))
        after (ask {:op :eval :agent "root" :code "(+ 1 2)"})]
    (.close s1) (.close s2)
    (is (some #{tag} (ts/value-of (:response running))) (str "root's view lists the running op's client: " (pr-str running)))
    (is (= {:stopped 1 :dropped 0} stop1) "the stop finds the client's running op")
    (is (re-find #"(?i)interrupt" (pr-str a1)) (str "the stopped eval is answered, interrupted: " (pr-str a1)))
    (is (= 7 (get a2 "id")) "the cancelled MCP request is answered, by its id")
    (is (re-find #"(?i)interrupt" (pr-str a2)) (str "interrupted: " (pr-str a2)))
    (is (= 3 (ts/value-of (:response after))) "the server goes on")))

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

(deftest ^{:seon.test/estimate {:ticks 5000000}} an-attached-agent-holds-its-task-running-in-its-record
  ;; agent view's browser check: an agent attached from outside (seon.server/attach! spawns it before it writes the start on main, so its branch is
  ;; cut without the task, and before main writes the task's release) held no task in @ctx, and read it :held, until a turn or a context read began
  ;; its work. Forked for a task, the agent records it on its branch as it starts (seon.agent/begin-run!): its task in its record, no runs, and
  ;; (my.task/current) and its record read the task running, as main does (task seon.task/an-agent-reads-its-task-as-main-holds-it)
  ;; in this process (my.test/eval-in): the agent of a fresh task on the world, whose start (init!) is begin-run!, run as the first form
  (let [names (volatile! nil)
        [_ held current running] (my.test/eval-in (fn [n] (vreset! names n)
                                              [(fn [_] (seon.agent/begin-run! {:key (:agent n) :role :test} (:task n)))
                                               "(let [t @seon.ctx/ctx] [(contains? t :runs) (get-in t [:seon.agent/task :seon.task/id])])"
                                               "(:seon.task/execution (my.task/current agent))"
                                               "(:seon.task/execution (:seon.agent/task @seon.ctx/ctx))"]))]
    (is (= [false (:task @names)] (:value held)) "its task, in its record, and no runs")
    (is (= :running (:value current)) "the agent reads its task running, as main does")
    (is (= :running (:value running)) "and so does its record")))

;; Estimate: in this process, four server round trips and the answers' reads (~0.5M ticks each, as a-message-reaches-the-agent's): ~2M; 3,000,000.
;; The reply's own cost at the agent: a seek of the messages linked to it, O(m) datoms (m its messages: here 1), and an entity read of each
;; new one, ~10.5k ticks a message [M result/b45faee3]
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000}} a-message-to-an-attached-agent-is-in-the-next-reply
  ;; task seon.server/a-message-reaches-the-session-that-drives-its-agent (docs/users.md#messages): a session driving an agent through
  ;; MCP hears a message to it in its next reply, as a model hears one in its next request, once. Red
  ;; before: the reply carried its evals' responses alone, and main's merge had already put the message on the branch, so it read as
  ;; delivered while no one had read it (flow's probe, 2026-11-15: agent ba18c9e9bd81a184)
  (let [[_ sent heard again] (at/in-agent (fn [{:keys [agent task]}]
                                            [{:op :attach :task task}
                                             {:op :message :agent agent :body "use demo/g"}
                                             {:op :eval :agent agent :message-md "" :evals [{:intent "add" :code "(+ 1 2)"}]}
                                             {:op :eval :agent agent :message-md "" :evals [{:intent "add again" :code "(+ 2 2)"}]}]))]
    (is (true? (:ok sent)) (pr-str sent))
    (is (= [3] (mapv :value (:responses heard))) "the evals answer as before")
    ;; the reply adds what the model's next request adds (seon.turn/outside-reply!, one request! for both kinds of agent), where a message
    ;; to the agent is placed as its model reads it
    (is (clojure.string/includes? (str (:request heard)) "use demo/g") (str "the reply after the message carries it: " (pr-str (dissoc heard :responses))))
    (is (clojure.string/includes? (str (:request heard)) (seon.config/value :seon.config.root/user-name)) "with who sent it, by name")
    (is (not (clojure.string/includes? (str (:request again)) "use demo/g")) (str "and the next reply does not carry it again: " (pr-str (:request again))))))

;; Estimate: in this process, four server round trips and the answers' reads (~0.5M ticks each): ~2M; 3,000,000. At the agent: the
;; attach's read and the context read each draw the request once and store its parts (~50-100M, the agent's own work, not this process's)
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000}} a-reply-carries-what-moved-since-the-sessions-read
  ;; docs/users.md#messages: a reply answers what the model's next request adds past what the session was shown. A session that never
  ;; called seon_context had no read, so its first reply was the whole request; and a seon_context read stored no parts, so the reply
  ;; after it was the whole request again: runtime's reply of 93,941 characters, 2026-11-16 [result/dba934d6]. Every read stores what it
  ;; showed, so the reply after a seon_context is what moved since it. Red before: it carried the system prompt whole. A session that has
  ;; read nothing is answered its whole request (seon.ctx/a-runs-request-holds-its-own-run would make it its run's).
  ;; Estimate unchanged: one more round trip (~0.5M)
  (let [[_ first-reply ctx second-reply third-reply] (at/in-agent (fn [{:keys [agent task]}]
                                                         [{:op :attach :task task}
                                                          {:op :eval :agent agent :message-md "" :evals [{:intent "add" :code "(+ 1 2)"}]}
                                                          {:op :context :agent agent}
                                                          {:op :eval :agent agent :message-md "" :evals [{:intent "add again" :code "(+ 2 2)"}]}
                                                          {:op :eval :agent agent :message-md "third" :evals [{:intent "add a third" :code "(+ 30 4)"}]}]))
        prompt "# You are a Seon agent"]
    (is (= [3] (mapv :value (:responses first-reply))) (pr-str first-reply))
    (is (clojure.string/includes? (str ctx) prompt) "seon_context shows it whole")
    (is (not (clojure.string/includes? (str (:request second-reply)) prompt)) "and the reply after it: what moved since it")
    ;; a seon-driver's replies each echoed its own evals and results under the request (2026-11-16, agent mf89a160307a724d
    ;; [result/b8996cc0]): what a reply sent and answers under :responses is the session's already
    (is (= [34] (mapv :value (:responses third-reply))) (pr-str third-reply))
    (is (not (clojure.string/includes? (str (:request third-reply)) "(+ 30 4)")) (str "a reply does not send its own eval back: " (pr-str (:request third-reply))))
    (is (not (clojure.string/includes? (str (:request third-reply)) "(+ 2 2)")) "nor the reply's before it")))

;; Estimate: an empty test branch ~0.3M [M], four small transacts (a task, a work-tx, two messages) ~0.1M each, two undelivered reads ~20k: ~0.8M;
;; 1,500,000. Measured warm 0.87M ticks (the first call in a fresh process reads 9M: code not yet loaded, which the warm fork has paid).
;; No root: seon.task/undelivered is the read inbox-answer answers at once from (against main at since) (it waits only when this is empty), so a wrong read fails here, never hangs.
(deftest ^{:seon.test/estimate {:ticks 1500000}} an-inbox-answers-at-once-with-what-came-after-since
  ;; docs/users.md#messages: seon_inbox reads the messages linked to the agent after since (0: all), oldest first, each with :t, the next since.
  ;; Red before: it answered what the branch had not taken, and took it
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "w" :seon.task/plan "Work w." :seon.task/proofs #{'demo-test/w}}]))
     (d/transact conn (ts/work-tx "session/w" :running {:agent :k1}))
     (d/transact conn (task/message-tx "m1" {:seon.agent/key "user"} ["k1"] "first" 4))
     (let [read-db @conn]
       (d/transact conn (task/message-tx "m2" {:seon.agent/key "user"} ["k1"] "second" 5))
       (let [all (task/undelivered @conn nil "k1")
             after (task/undelivered @conn read-db "k1")]
       (is (= ["first" "second"] (mapv :body all)) (pr-str all))
       (is (every? #(int? (:t %)) all) "each with :t, the next since")
       (is (= ["second"] (mapv :body after)) "after a since: only what came later"))))))

(deftest ^{:seon.test/estimate {:ticks 300000}} an-inbox-line-runs-on-its-connection-and-holds-no-worker
  ;; part 2: seon_inbox waits, so it runs on the connection's fiber (which parks), never one of the 16 :server-op worker threads
  ;; (:seon.config.root/server-op-workers): eight waiting sessions would hold half of them. Both protocols. Red before: no inline op
  ;; read once by line-parts; the table decides (an op's :inline), never a name in code (root's reviewer on 6635b0cac2)
  (let [inline? #(:inline? (#'seon.server/line-parts %))]
    (is (inline? "{:op :inbox :agent \"a1\"}") "EDN")
    (is (inline? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"seon_inbox\",\"arguments\":{\"agent\":\"a1\"}}}") "MCP")
    (is (not (inline? "{:op :eval :agent \"root\" :code \"(inbox)\"}")) "an eval naming inbox is no inbox")
    (is (seq (filter (comp :inline val) seon.server/ops)) "some op declares :inline")
    (doseq [[op v] seon.server/ops :when (:inline v)]
      (is (inline? (str "{:op " op "}")) (str op " declared :inline runs on its connection (EDN)"))
      (is (inline? (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"seon_" (name op) "\"}}")) (str op " (MCP)")))
    (is (not (inline? "{:op :agents}")) "an op that does not declare :inline runs on a worker")))

(deftest the-context-op-answers-what-a-model-is-sent
  ;; dogfood 2026-10-25: an outside agent read the task's data and turn sections only, not the system
  ;; prompt and screen a model is sent, and its (my.task/status) answered :seon.turn/not-observed.
  ;; Its context read is its turn: the screen's forms run as a model turn's do, and the read
  ;; answers the request's text, system prompt first, screen last
  (let [[ctx status sent] (at/in-agent (fn [{:keys [agent task]}]
                                         [{:op :context :agent agent} "(my.task/status)"
                                          ;; read here, from the store (seon.test-store/read-task-branch): no request to root
                                          ;; this test's own agent's facts only: its task branch forks from the shard's main, which holds every
                                          ;; earlier test's agents and their reads (seon.agent-test/in-agent)
                                          (fn [{:keys [root names]}]
                                            (let [db (ts/read-task-branch root task)
                                                  k (name (:agent names))
                                                  a (pdb/eid db :seon.agent/key k)]
                                              {:ns (d/q '[:find [?n ...] :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/origin :outside] [?m :seon.model-call/sent-hash _] [?m :seon.model-call/n ?n]] db a)
                                               :ids (d/q '[:find [?id ...] :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/origin :outside] [?m :seon.model-call/id ?id]] db a)
                                               :turns (d/q '[:find [?t ...] :in $ ?a :where [?a :seon.agent/turn ?t]] db a)
                                               :key k}))]))
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

;; Estimate: one agent's setup and teardown (~1.5M) and six asks of ~0.25M each, its replies' evals through the REPL path (~0.3M each): ~3.5M; 4,000,000
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 6}} each-outside-reply-is-a-turn-and-its-evals-answer-in-order
  ;; docs/server.md#working-a-task-from-a-worktree: a seon_eval by the agent attached is one reply, a turn, as a model's is: each of its evals answers, in
  ;; order, a call with no intent is answered invalid and what follows it runs (owner, 2026-11-08), and plain {intent, code} on an agent is a reply of
  ;; that one eval, for every caller (the :eval op, where bin/mcp's rewrite was). The tool body a read sends is seon.ctx.wire-test's and model-test's;
  ;; the blank intent's error is agent-test's (call-problem); the REPL's namespace staying where a form moved it is an-outside-agents-repl-answers-plainly's
  (let [[attach called stopped old turn-of]
        (at/in-agent (fn [{:keys [agent task]}]
                       [{:op :attach :task task}
                        {:op :eval :agent agent :message-md "Adding, then adding again." :evals [{:intent "add one and one" :code "(+ 1 1)"} {:intent "then two and two" :code "(+ 2 2)"}]}
                        {:op :eval :agent agent :message-md "" :evals [{:intent " " :code "(def not-run-form 1)"} {:intent "after it" :code "(def ran-after-it 1)"}]}
                        {:op :eval :agent agent :intent "the old shape" :code "(def old-shape-ran 1)"}
                        "(seon.agent/turn-now agent)"]))]
    (is (clojure.string/includes? (:next attach) "seon_context") "an attach names the next step")
    (is (clojure.string/includes? (:next attach) "message-md") "and the shape of a reply")
    (is (= [2 4] (filterv number? (map :value (:responses called)))) "each eval answers, in order")
    (is (= [:seon.turn/invalid-call] (keep #(:seon.error/type (:ex %)) (:responses stopped))) "a call with no intent is answered invalid")
    (is (= ["ran-after-it"] (mapv name (mapcat :recorded (:responses stopped)))) (str "and what follows it runs: " (pr-str stopped)))
    (is (= ["old-shape-ran"] (mapv name (:recorded (first (:responses old))))) (str "plain code on an agent runs as one eval: " (pr-str old)))
    (is (= 3 (:value turn-of)) "each reply is a turn, the plain one too: no one growing turn 0")))

(deftest roots-repl-names-what-has-no-branch
  ;; my.agent/* and my.db/* at root answered seon.agent/db's invalid-output, "Fix seon.agent/db (the producer)", and
  ;; (my.agent/prove) an NPE from datahike.writer/abandon-pending!: root has no branch, and neither said so
  (let [[db prove] (at/in-agent [{:op :eval :agent "root" :code "(my.db/db)"} {:op :eval :agent "root" :code "(my.agent/prove nil)"}])]
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

(deftest a-swap-of-the-agents-state-answers-its-summary
  ;; THE PLAN 0.7's first run: a bare swap! of the agent's state answered 42,000 characters. Its db is the branch's whole db, which a world's is not, so
  ;; this one is an agent on a branch of the shared root (its siblings' claims are an-outside-agents-repl-answers-plainly's, in this process)
  (let [[state] (at/in-agent ["(swap! seon.agent/state assoc :zz 1)"])]
    (is (< (count (:text state)) 6000) "a bare swap! of the agent's state answers its summary, not 42,000 characters")
    (is (clojure.string/includes? (:text state) "(get (deref seon.agent/state) :db)") (str "each host object by its type, with the form that reads it: " (:text state)))))

;; Estimate: six forms at the REPL of a fresh task's agent on the world (eval-in, no root), each recorded and noted as an agent's is (~5M a form [M]: the
;; first worked 0.3M a form, and was wrong by 10x: the record and the note are the form's own work): ~30M; 40,000,000. Measured warm 31.3M.
(deftest ^{:seon.test/estimate {:ticks 40000000}} an-outside-agents-repl-answers-plainly
  ;; THE PLAN 0.7's first run (2026-10-27) found four things in an outside agent's REPL: a
  ;; missed help address answered the whole help page (10k characters), (source ..) of a name that
  ;; is no var failed inside its own contract, and after (in-ns 'my.task) the next eval had no
  ;; source, as clojure.repl's names stay referred at a REPL that switches namespaces
  (let [[miss no-ns not-a-name a-class switched sourced] (my.test/eval-in ["(my.agent/doc-of agent \"a-section-that-is-not-there\")"
                                                                      "(source 'no.such.ns/missing-var)"
                                                                      "(source :kw)"
                                                                      "(source 'java.lang.String)"
                                                                      "(in-ns 'my.task)"
                                                                      "(source my.agent/help)"])]
    (is (< (count (:text miss)) 1500) "a missed help address names what it did not find, not the help page")
    (is (= #{:no-section :nearest :read-one} (set (keys (:value miss)))))
    (doseq [[what r] [["no such namespace" no-ns] ["not a name" not-a-name] ["a class" a-class]]]
      (is (nil? (:ex r)) (str "source of " what " answers, it does not fail: " (pr-str (:ex r)))))
    (is (clojure.string/includes? (str (:text no-ns)) "names no namespace") "a plain no-such-var answer")
    (is (clojure.string/includes? (str (:text not-a-name)) "Source not found") "and a name that is none")
    (is (nil? (:ex sourced)) (str "source resolves in a namespace the agent switched to: " (pr-str (:ex sourced))))
    (is (clojure.string/includes? (str (:text sourced)) "defn help") "and reads the definition")))

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
                        "(pr-str (seon.repl/render-ctx-hiccup agent))"
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

;; Estimate: an empty test branch ~0.3M and two answers that stop at the ops table's required-arguments check: ~0.4M; 1,000,000
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-missing-argument-is-named-from-the-ops-table
  ;; repl-audit B F1: an attach with no task printed root's whole system map. What a request needs is said from the ops table, for every
  ;; op, before the op runs (seon.server/answer): a stub sys, no root
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           no-task (seon.server/answer sys {:op :attach})
           no-args (seon.server/answer sys {:op :create})]
       (is (clojure.string/includes? (:seon.error/message no-task) "task=") (pr-str no-task))
       (is (clojure.string/includes? (:seon.error/message no-args) "seon_create needs ns, name, plan") (pr-str no-args))))))

(deftest ^{:seon.test/estimate {:ticks 6000000}} a-reads-kept-points-name-committed-values
  ;; docs/storage.md#a-kept-db-value-is-a-reference: a kept db value is a reference {:commit :t}, so every point a context read keeps names a
  ;; value a later read can read back, its t its commit's own. Red before: the read wrote its record, then drew its request on that run-ahead
  ;; value, so its page walk kept the last commit with a t past it, which a later read could only read as an as-of value of a different
  ;; db (a cold walk of the same turn drew another record: a-later-read-draws-no-page-cold, red on the candidate)
  (let [[r] (ts/test-branch! (fn [_ conn] (my.test/eval-in [(str "(let [t (seon.agent/task-of agent)"
                               " _ (seon.turn/context-block agent t true) _ (seon.turn/context-block agent t true)"
                               " db (seon.agent/db) st (seon.db/store-of db)"
                               " own? (fn [p] (= (:t p) (:max-tx (datahike.api/commit-as-db st (:commit p)))))"
                               " k (:seon.ctx.wire/parts-walk @seon.agent/state)]"
                               " {:kept (own? (select-keys k [:commit :t])) :first (own? (zipmap [:commit :t] (:point (:first k))))})")] 'seon.repl-test.scratch {:conn conn})))]
    (is (= {:kept true :first true} (:value r)) (str "each kept point names its commit's own t: " (pr-str r)))))

(deftest ^{:seon.test/estimate {:ticks 60000000}} a-later-read-draws-no-page-cold
  ;; the owner, 2026-10-27: a later turn's request build measured 94M ticks, 317 MB on Seon's own tasks, 25,000
  ;; contracted calls, two pages drawn a read (the one before's again, to diff against). Each read after
  ;; the first draws the one page it tells and costs what the read before did: ticks and bytes are the
  ;; process's exact counts (seon.profile/measure!); its page is the agent's live walk's (seon.repl/page-walk),
  ;; so no walk is drawn cold, and it equals a cold walk (seon.render/walk :once) on the db it is drawn to. Its cost claim (a read costs what the one
  ;; before did) is no test: a ratio of two readings in a root whose main other tests move (docs/testing.md#testing-in-the-world, a cost proof is seon.cost/proof)
  (let [[reads]
        (my.test/eval-in (fn [{:keys []}]
                       [(str "(let [t (seon.agent/task-of agent)"
                             " m (fn [] (let [v (seon.profile/measure! #(count (seon.turn/context-block agent t true)))]"
                             "           {:ticks (:ticks v) :bytes (:bytes v)"
                             "            :main (let [_ (seon.repl/page-walk agent) db (seon.agent/db)] (:seon.agent/main-commit (datahike.api/entity db (seon.db/eid db :seon.agent/key (:key agent)))))"
                             "            :cold? (let [w (:walk @seon.agent/state)] (not (< (count (:ran w)) (count (:cache w)))))}))]"
                             "  {:reads (vec (repeatedly 9 m))"
                             "   :live=cold (let [pw (seon.repl/page-walk agent) db (seon.agent/db) a (seon.db/eid db :seon.agent/key (:key agent))"
                             "                    ix (seon.program/index-of-db db) t (:tree (seon.ctx/build ix db a (get-in db [:config :branch])))"
                             "                    cw (seon.render/walk nil {:db db :tree t :root (seon.render/render-var-of ix (merge {:seon.agent/key (:key agent)} t)) :index ix :changes seon.ctx/changes :model-only true :once true :outputs #{:seon.render/hiccup :seon.render/openai-compat}})"
                             "                    msgs (fn [w] (mapv first (get-in w [:answers :seon.render/openai-compat])))]"
                             "                (or (= (msgs pw) (msgs cw)) (vec (remove (set (msgs cw)) (msgs pw)))))})")]))
        later (rest (:reads (:value reads)))]
    (is (= 9 (count (:reads (:value reads)))))
    (is (true? (:live=cold (:value reads))) (str "the live walk's request is a cold walk's on the db it is drawn to; live only: " (pr-str (:live=cold (:value reads)))))
    (is (not-any? :cold? later) (str "each later read draws no page cold: its walk ran fewer components than it holds " (pr-str (map :cold? later))))))

;; Estimate: one agent's setup and teardown (~1.5M) and four asks of ~0.25M each, its prove run by the service, not here: ~2.5M; 3,000,000
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 4}} a-prove-never-ends-the-agent-and-a-named-test-runs-alone
  ;; THE PLAN 0.7 (root 2026-10-27: after seon_prove the agent had ended). Each prove names one test, which runs alone (seon.agent/named-tests):
  ;; naming a namespace once proved all of main inside a test (568 s, 2026-10-30)
  (let [alive #(hash-map :op :eval :agent "root" :code (str "(contains? (:agents (c/state sys)) :" % ")"))
        [ctx proved after live]
        (at/in-agent (fn [{:keys [agent]}]
                       [{:op :context :agent agent}
                        {:op :prove :agent agent :names ["seon.db-test/a-question-across-branches-is-one-query"]}
                        "(+ 1 2)"
                        (alive agent)]))]
    (is (string? (:context ctx)) (pr-str ctx))
    (is (not (contract/error? proved)) (pr-str proved))
    (is (= 1 (:ran proved (:ran (:result proved)))) (str "a named test runs alone: " (pr-str proved)))
    (is (= 3 (:value after)) (str "the agent answers after a prove of its branch: " (pr-str after)))
    (is (true? (:value live)) "and root holds it live")))

;; Estimate: one agent's setup and teardown (~1.5M) and five asks of ~0.25M each, its prove run by the service: ~2.8M; 3,000,000
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 3000000 :asks 5}} a-process-test-an-agent-defined-is-deferred-and-a-red-names-its-failure
  ;; a process test an agent defined is deferred from the fork with why, and a red comes with its first failing assertion
  (let [[defined _ why answer status-p]
        (at/in-agent (fn [{:keys [agent]}]
                       [(str "(ns app.ptest (:require [clojure.test :refer [deftest is]]))"
                             " (deftest p-test (is (fn? seon.lib/fork!)))"
                             " (deftest r-test (is (= 1 2) \"one is two\"))")
                        "(+ 1 1)"
                        "(my.agent/process-test? 'app.ptest/p-test)"
                        {:op :prove :agent agent :names ["app.ptest"]}
                        "(my.agent/process-test? 'app.ptest/r-test)"]))]
    (is (nil? (:ex defined)) (pr-str defined))
    (is (some #{'seon.lib/fork!} (:uses (:value why))) (str "a deferred process test says why: " (pr-str why)))
    (let [r (or (:result answer) answer)]
      (is (= "one is two" (get-in r [:failures 'app.ptest/r-test :message])) (str "a red's first failing assertion: " (pr-str answer)))
      (is (some #{'app.ptest/p-test} (:deferred r)) "the service's answer names the deferred one too"))
    (is (nil? (:value status-p)) "an in-process test is none")))

;; Estimate: one agent's setup and teardown (~1.5M) and seven asks of ~0.25M each, its prove run by the service and its done judged by the reconciler: ~3.8M; 4,000,000
(deftest ^{:seon.test/root [] :seon.test/estimate {:ticks 4000000 :asks 7}} a-named-prove-counts-for-status-and-the-judge-and-done-answers-its-reasons
  ;; - a named prove counts for my.task/status and the judge and answers one line (the blind agent, 2026-10-27: 171 green left both proofs
  ;;   :proof-unknown, and a bare prove answered 25k chars); how that line and the eval's value are drawn is seon.render-test's
  ;; - done answers the judge's reasons once main holds them (dogfood 2026-10-25: seon_done answered {:judging ...}): the harness's task names
  ;;   demo-test/t, which no test defines, so the done rule refuses and the line says why
  (let [[_ _ _ proved-ok status drawn done]
        (at/in-agent (fn [{:keys [agent]}]
                       ["(ns app.okt (:require [clojure.test :refer [deftest is]])) (deftest ok-test (is (= 2 (+ 1 1))))"
                        "(my.task/update! agent :seon.task/proofs conj 'app.okt/ok-test)"
                        "(+ 2 2)"
                        "(my.agent/prove agent #{'app.okt/ok-test})"
                        "(let [s (my.task/status)] [(some #(when (= 'app.okt/ok-test (:test %)) (:status %)) (:proofs s)) (filterv #(= 'app.okt/ok-test (:symbol %)) (:refused s (:reasons s)))])"
                        ;; the prove's eval as recorded names the render schema its value is drawn by (seon.render.ui/proved is render-test's)
                        "(second (datahike.api/q '[:find [?e ?k] :in $ ?c :where [?e :seon.eval/code ?c] [?e :seon.eval/response _] [?e :seon.eval/schema ?k]] (seon.agent/db) \"(my.agent/prove agent #{'app.okt/ok-test})\"))"
                        {:op :done :agent agent}]))]
    (is (clojure.string/includes? (:text proved-ok) "green: ") (:text proved-ok))
    (is (< (count (:text proved-ok)) 300) (str "the answer is its handle and one line: " (count (:text proved-ok)) " chars"))
    (is (= [:pass []] (:value status)) (str "my.task/status: the proof's status, and no reason about it: " (pr-str status)))
    (is (= :seon.test/proved (:value drawn)) (str "the live prove's eval record names the render schema its value is drawn by: " (pr-str drawn)))
    (is (not (clojure.string/includes? (pr-str done) "proof-unknown app.okt/ok-test")) (str "and the judge counts it: " (pr-str done)))
    (is (clojure.string/includes? (:line done) ":outcome :closed") (pr-str done))
    (is (clojure.string/includes? (:line done) "proof-unknown demo-test/t") "the done rule's reasons, as the judge wrote them on the held task")
    (is (clojure.string/includes? (:handle done) "seon.server/verdict") "and the handle that reads the whole verdict")))

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
                        ;; the verdict main holds once the branch merged: its :seon.proof entity (seon.agent/record-results!)
                        {:op :eval :agent "root" :code (str "(count (datahike.api/datoms @(:conn sys) :avet :seon.proof/test '" ns "-test/t))")}])
                     {:proofs (fn [{:keys [ns]}] #{(symbol (str ns "-test") "t")})})]
    (is (not (clojure.string/includes? (str (:line done)) "behind")) (pr-str done))
    (is (clojure.string/includes? (str (:line done)) ":merged") (pr-str done))
    (is (pos? (:value cached)) (str "the judge's verdict is held at the test's digest, a :seon.proof entity main merged: " (pr-str cached)))))

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

(deftest a-context-read-that-only-looks-writes-nothing
  ;; owner, 2026-11-10: "A read never writes". An observer's read of an agent (take? false: anyone's but its outside model's) commits
  ;; nothing and records nothing: the branch's t and commit are the same after it; and no :context read, an outside model's own
  ;; included, leaves an eval of its print form on the branch (the outside model's read is its turn: a model call, never an eval)
  (let [[looked _ evals] (at/in-agent (fn [{:keys [agent task]}]
                                        [(str "(let [db0 (seon.agent/db) _ (seon.turn/context-block agent " (pr-str task) " false) db1 (seon.agent/db)]"
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
;; the client gave up while the verdict was still to come). The judge's one prove runs them, and no stage after it holds a done
(deftest done-answers-when-the-tasks-own-work-is-judged-not-after-its-gate
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "g" :seon.task/plan "Make g." :seon.task/proofs #{'demo-test/g-test}}]))
     (let [at (fn [o] (d/transact conn [{:seon.task/id "demo/g" :seon.task/outcome o}]) @conn)
           settled? (fn [db] (seon.reconcile/settled? db "demo/g"))]
       (is (not (settled? (at :running))) "still working: wait")
       (is (not (settled? (at :ready))) "judged green, its merge next: wait (minutes)")
       ;; no gate stage after the judge (its one prove ran the process tests): a merge task is :ready or held :closed naming them
       (is (settled? (at :closed)) "held, naming the tests the judge's gate gave no verdict: answer now")
       (is (settled? (at :merged)))))))

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

;; Estimate: three transactions on a test branch and two :aevt walks of one holder datom: ~0.8M; 2,000,000
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-sessions-messages-are-from-its-agent-after-a-restart-ended-it
  ;; docs/users.md#messages: a message's sender is who the client is, its session's agent, whether or not that agent's process
  ;; lives; only what done and eval act on needs a live one. Red before: the hold map kept live agents alone, so flow's messages after
  ;; root's 11:45 restart went :from \"user\" (deps, t 536918880, result/a43e63b6)
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "s" :seon.task/plan "A session." :seon.task/kind :user}]))
     (d/transact conn (ts/work-tx "session/s" :running {:agent :k1}))
     (d/transact conn [{:seon.agent/key "k1" :seon.agent/started-ms 1 :seon.agent/ended-ms 5}])
     (let [sys {:graph nil :ext (a/chan) :conn conn}]
       (is (= "k1" (seon.server/connection-agent sys {:client "s"})) "its messages are from its session's agent, ended in a restart")))))

;; Estimate: four transactions on a test branch and two :aevt walks of three holder datoms: ~1M; 2,000,000
(deftest ^{:seon.test/estimate {:ticks 2000000}} an-agent-spawned-again-is-live
  ;; docs/server.md#ops: an agent key spawned again keeps its last :seon.agent/ended-ms, which is no end of the new process
  ;; (seon.task/agent-ended?, the reconciler's one rule), read here as a session's live agent (my.root/branch-agent)
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "s" :seon.task/plan "Make s true." :seon.task/proofs #{'demo-test/s}}]))
     (d/transact conn (ts/work-tx "session/s" :running {:agent :k1}))
     (d/transact conn [{:seon.agent/key "k1" :seon.agent/started-ms 1 :seon.agent/ended-ms 5}])
     (let [sys {:graph nil :ext (a/chan) :conn conn}]
       (is (nil? (my.root/branch-agent sys "jolt/s")) "ended after it started: its session has no live agent")
       (d/transact conn [{:seon.agent/key "k1" :seon.agent/started-ms 9}])
       (is (= {:agent :k1 :task "session/s"} (my.root/branch-agent sys "jolt/s")) "spawned again after that end: live again")))))

;; Estimate: two small transactions on a test branch and one refused read, ~0.3M
(deftest ^{:seon.test/estimate {:ticks 1000000}} an-inbox-since-past-mains-head-is-refused
  ;; root, 2026-11-16: an inbox whose since is past main's head answered a :seon.server/defect ('No commit of the branch holds this
  ;; transaction yet'), the commit lookup at since throwing. A since past the head is the caller's error, refused as a since that is no
  ;; number is, naming main's head; read before any lookup. Red before: a throw
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "w" :seon.task/plan "Work w." :seon.task/proofs #{'demo-test/w}}]))
     (d/transact conn (ts/work-tx "session/w" :running {:agent :k1}))
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           head (:max-tx @conn)
           r (try (#'seon.server/inbox-answer sys {:task "session/w" :since (+ head 1000)}) (catch Throwable e {:threw (ex-message e)}))]
       (is (= :seon.system/refused (:seon.error/type r)) (pr-str r))
       (is (re-find (re-pattern (str head)) (str (:seon.error/message r))) "it names main's head")))))

;; Estimate: an in-memory store, one branch, five small transactions and two seeks of k1's links, ~1M
(deftest ^{:seon.test/estimate {:ticks 3000000}} an-inbox-reads-a-message-a-merge-carried-onto-main
  ;; deps' reproduction (result/d1027635 at deps' agent), 2026-11-16: a merge up carries a branch's facts onto main with their own, older
  ;; transactions, so a message linked on a branch before main moved arrives on main below a since its reader already read, and a read of
  ;; "links after since by tx" misses it. An inbox reads what is new against main's commit at since: the messages to the recipient now that
  ;; that commit does not hold (seon.task/undelivered, as an agent's delivery reads its branch), whatever a link's tx. Latent today (messages are written on main only); live once a reply travels by merge. Red
  ;; before: the tx filter answered none
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true
             :initial-tx [{:db/ident :seon.agent/key :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                          {:db/ident :seon.message/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                          {:db/ident :seon.message/body :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                          {:db/ident :seon.message/sent-ms :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                          {:db/ident :seon.message/to :db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/index true}]}
        _ (d/create-database cfg)
        m (d/connect cfg)
        _ (d/transact m [{:seon.agent/key "k1"}])
        _ (d/branch! m :db :b)
        b (d/connect (assoc cfg :branch :b))
        tb (:max-tx (:db-after (d/transact b [{:seon.message/id "m0" :seon.message/body "by a merge" :seon.message/sent-ms 1 :seon.message/to [[:seon.agent/key "k1"]]}])))
        _ (d/transact m [{:seon.message/id "m1" :seon.message/body "on main" :seon.message/sent-ms 2 :seon.message/to [[:seon.agent/key "k1"]]}])
        read-db @m
        since (:max-tx read-db)
        _ (d/transact m (vec (filter #(= tb (:tx %)) (d/datoms @b :eavt))))]
    (is (some #(and (= :seon.message/to (:a %)) (< (:tx %) since)) (d/datoms @m :eavt)) "the carried link keeps its older tx, below since")
    (is (= ["by a merge"] (mapv :body (task/undelivered @m read-db "k1"))) "read against the commit at since, it is new")
    (is (= [] (task/undelivered read-db read-db "k1")) "nothing new against itself")
    (is (= ["by a merge" "on main"] (sort (mapv :body (task/undelivered @m nil "k1")))) "with nothing read, every message")
    (d/release m) (d/release b)))

;; Estimate: one index of an empty test branch and a map over two agents, ~0.5M
(deftest ^{:seon.test/estimate {:ticks 1500000}} the-agents-answer-names-each-agents-pid
  ;; root, 2026-11-16: context could not tie a GC-bound pid 62088 to its agent; seon_agents named id, branch, status and task, and the pid
  ;; was read through root's :state. Each live agent's row carries its process's :pid, from root's view; a gone one is left out as before.
  ;; Red before: no :pid
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           r (with-redefs [seon.system/state (fn [_] {:agents {:k1 {:branch :b :status :idle :task "t/a" :pid 62088}
                                                               :k2 {:branch :c :status :killed :task "t/b" :pid 7}}})]
               (seon.server/agents-answer sys))]
       (is (= {:k1 {:branch :b :status :idle :task "t/a" :pid 62088}} (:agents r)) (pr-str (:agents r)))))))

;; Estimate: three small transactions on a test branch and one branch-agent read, ~0.3M
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-killed-agent-is-held-by-no-one
  ;; tests, 2026-11-16: after attach fresh=true, every eval of client tests answered :seon.system/ended for its old agent n281eb679a9b6642.
  ;; Root's view records a process's end on its process entity (:seon.process/ended-ms, seon.task/agent-tx); a kill outside the reconciler
  ;; (root's kill!, an OOM, the gate's spawn ended) writes no :seon.agent/ended-ms, and seon.task/agent-ended?, the one ended rule, read
  ;; only that. So a killed agent read live to every reader of the rule. Red before: :k1
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "w" :seon.task/plan "Work w." :seon.task/proofs #{'demo-test/w}}]))
     (d/transact conn (ts/work-tx "session/w" :running {:agent :k1 :start-ms 1000}))
     (d/transact conn [{:seon.process/id "k1/42" :seon.process/agent [:seon.agent/key "k1"] :seon.process/pid 42 :seon.process/ended-ms 2000}
                       {:seon.agent/key "k1" :seon.agent/status :killed}])
     (let [sys {:graph nil :ext (a/chan) :conn conn}]
       (is (nil? (my.root/branch-agent sys "jolt/w")) "its process ended after its start: its session has no live agent")))))

;; Estimate: the inbox test's setup (empty branch ~0.3M, two transacts ~0.1M each), four own-agent reads (~0.05M each): ~0.7M; 1,000,000. Measured warm 0.66M ticks
;; (6.1M on the first call in a fresh process: code not yet loaded).
(deftest ^{:seon.test/estimate {:ticks 1000000}} an-op-acts-on-the-agent-named-or-its-sessions
  ;; docs/server.md#ops (root, 2026-11-16: an act is addressed by its session's task or by the agent named, never by who attached
  ;; last): done and eval act on the agent the request names; with none named, a client named <area> acts on session/<area>'s
  ;; agent; a client of no session that names none is refused before any op runs; root's own eval is no agent's. Red before: the
  ;; client's last attach (the :seon.agent/holder facts) won over the agent named
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "c1" :seon.task/plan "A session." :seon.task/proofs #{'demo-test/s}}]))
     (d/transact conn (ts/work-tx "session/c1" :running {:agent :k1}))
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           own #'seon.server/own-agent]
       ;; own-agent alone, never answer: a wrong resolution would run the op, which waits on an api this stub does not have
       (is (= :k1 (own sys {:op :eval :client "c1"})) "c1's eval, naming none, acts on its session's agent")
       (is (= :k9 (own sys {:op :done :agent "k9" :client "c1"})) "the agent named, whoever asks")
       (is (contract/error? (own sys {:op :done :client "c2"})) "c2 has no session and names none: refused")
       (is (= :root (own sys {:op :eval :agent "root" :client "c2"})) "root's own eval is no agent's")))))

;; Estimate: three small transactions on a test branch and one agent-key read, ~0.5M
(deftest ^{:seon.test/estimate {:ticks 1500000}} a-sessions-message-is-from-its-session-whoever-holds-its-agent
  ;; root, 2026-11-16: 15 of 27 messages to root arrived :from "user", as the owner, though sessions sent them. The sender was the agent
  ;; its client last attached (the hold map, since deleted), and a hold another client took over (the edit hook's checkout,
  ;; a second session) left the session's client holding none, so its message fell back to the signed-in person. A client named <area>
  ;; is the session session/<area>: its messages are from that task's agent, whatever holds its process. Red before: nil
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "w" :seon.task/plan "Work w." :seon.task/proofs #{'demo-test/w}}]))
     (d/transact conn (ts/work-tx "session/w" :running {:agent :k1}))
     (let [sys {:graph nil :ext (a/chan) :conn conn}]
       (is (= "k1" (seon.server/connection-agent sys {:client "w"})) "the session's agent, its hold taken by its checkout")
       (is (nil? (seon.server/connection-agent sys {:client "nobody"})) "a client of no session and no hold is no agent")))))

;; Estimate: four small transactions on a test branch, one read of the task's agents and their message links, ~0.5M
(deftest ^{:seon.test/estimate {:ticks 1500000}} an-inbox-by-task-reads-every-agent-the-task-had
  ;; root, 2026-11-16: a session's inbox followed one agent: an agent ended, OOM-killed or replaced (attach fresh=true makes a new key)
  ;; left its messages under its old key, which an inbox of the task no longer read. An inbox named by task reads the messages to every
  ;; agent that has worked it. Red before: only the newest agent's
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "w" :seon.task/plan "Work w." :seon.task/proofs #{'demo-test/w}}]))
     (d/transact conn (ts/work-tx "session/w" :running {:agent :k0}))
     (d/transact conn (task/message-tx "m0" {:seon.agent/key "user"} ["k0"] "to the old agent" 1))
     (d/transact conn [{:seon.agent/key "k0" :seon.agent/ended-ms 2}])
     (d/transact conn (ts/work-tx "session/w" :running {:agent :k1 :start-ms 3}))
     (let [mid (:max-tx @conn)]
       (d/transact conn (task/message-tx "m1" {:seon.agent/key "user"} ["k1"] "to the new agent" 4))
       (let [sys {:graph nil :ext (a/chan) :conn conn}
             r (#'seon.server/inbox-answer sys {:task "session/w" :since 0})
             ;; a since between the two: the path that reads main at the commit made at since (commit-at-tx), through inbox-answer
             later (#'seon.server/inbox-answer sys {:task "session/w" :since mid})]
         (is (= ["to the old agent" "to the new agent"] (mapv :body (:messages r))) (pr-str r))
         (is (= ["to the new agent"] (mapv :body (:messages later))) (str "since between them, the later one only: " (pr-str later))))))))

;; Estimate: a task on a test branch, one :done answered over stubs (~0.3M), one verdict line read (~0.2M): ~1M
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-sessions-done-with-its-checkout-merges-its-change-into-main
  ;; task seon.server/a-sessions-done-with-its-checkout-merges-its-change-into-main (FOCUS step 5): a session lands through seon_done with
  ;; its checkout. The :done op reads which files changed (seon.system/catch-up against the branch's tree), the agent imports exactly those
  ;; (my.branch/import!, which loads what it writes, 061a0fe4ff), hands back (seon.turn/hand-back!) and is judged; at a :gate with tests it
  ;; answers with :next (seon.reconcile/answer-judged, 5968148585); a file that does not read, or code that does not load, refuses with
  ;; nothing judged. Root's
  ;; functions are stubbed, so no git, bin/ or root (owner's rules, tests2's batch 3). Red before: the old test booted a root and a worktree
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "w" :seon.task/plan "Work w." :seon.task/proofs #{'demo-test/w}}]))
     (d/transact conn [{:seon.task/id "session/w" :seon.task/outcome :gate :seon.task/deferred {'demo-test/p "d1"}}])
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           done (fn [skipped load-errors]
                  (let [asked (atom []) written (atom [])
                        r (with-redefs [seon.server/work-of (fn [_ _] {:task "session/w" :env {:branch :b} :key "k1"})
                                        ;; the checkout's own commits since its last landing (docs/source.md#commit-the-merges-branch)
                                        seon.fx/landing-message (fn [_ _] {:message "A fix\n\nDeletes: x, onto y" :head "h1"})
                                        seon.system/transact-main! (fn [_ tx] (swap! written into tx) (doto (promise) (deliver {:t 1})))
                                        seon.system/branch-db (fn [_ _] (doto (promise) (deliver {:ok true :db @conn})))
                                        seon.system/catch-up (fn [_ _ _] {:paths ["src/app/x.clj" "test/app/x_test.clj"] :skipped skipped})
                                        seon.system/submit! (fn [_ _ code _]
                                                              (let [form (clojure.edn/read-string code)]
                                                                (swap! asked conj form)
                                                                (doto (promise)
                                                                  (deliver (case (first form)
                                                                             my.branch/import! {:value (cond-> {:imported (vec (last form)) :skipped (vec skipped)} (seq load-errors) (assoc :load-errors load-errors))}
                                                                             seon.turn/hand-back! {:text "ended"})))))
                                        seon.system/judge! (fn [_ _] {:task "session/w" :outcome :gate})
                                        seon.fx/uncommitted (fn [_] [])]
                            (seon.server/op-answer sys :k1 {:op :done :checkout "/co" :client "w"}))]
                    {:r r :asked @asked :written @written}))]
       (let [{:keys [r asked written]} (done [] nil)]
         (is (= '[my.branch/import! seon.turn/hand-back!] (mapv first asked)) (str "import, then hand back, nothing between: " (pr-str asked)))
         (is (= [{:seon.task/id "session/w" :seon.task/landing {:message "A fix\n\nDeletes: x, onto y" :head "h1"}}] written)
             "the landing's own message is the task's, which the merge's git commit carries (seon.source/commit-message)")
         (is (= ["src/app/x.clj" "test/app/x_test.clj"] (last (first asked))) "the import is asked for exactly the changed files")
         (is (re-find #":outcome :gate" (str (:line r))) (pr-str r))
         (is (re-find #"merge gate runs 1 process test" (str (:next r))) "a :gate answers with :next"))
       (let [{:keys [r asked]} (done [{:path "src/app/x.clj" :why "does not read"}] nil)]
         (is (= '[my.branch/import!] (mapv first asked)) "a file that does not read: nothing handed back")
         (is (re-find #"do not read, so nothing is judged" (str (:seon.error/message r))) (pr-str r)))
       (let [{:keys [r asked]} (done [] [{:ns 'app.x :message "Unable to resolve symbol: y"}])]
         (is (= '[my.branch/import!] (mapv first asked)) "code that does not load: nothing handed back")
         (is (re-find #"does not load, so nothing is judged" (str (:seon.error/message r))) (pr-str r)))))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-checkout-names-the-agent-it-holds
  ;; docs/server.md#working-a-task-from-a-worktree: a session's edit hook (bin/seon-import) imports onto its session's agent. A
  ;; worktree's branch is jolt/<area> and its session works session/<area> (AGENTS.md section 2), so the checkout's branch names the
  ;; agent: the live one working session/<area>, whoever attached it (the session attaches as its area, the hook writes as its checkout:
  ;; one session, two clients, and root measured that nothing imported, 2026-11-16). An ended agent, or a branch of no session, is none
  (ts/test-branch!
   (fn [_ conn]
     (let [decl (fn [nm] {:seon.task/ns 'session :seon.task/name nm :seon.task/plan (str "Work " nm ".") :seon.task/proofs #{(symbol "demo-test" nm)}})]
       (d/transact conn (task/create-tx @conn [(decl "w") (decl "v")]))
       (d/transact conn (ts/work-tx "session/w" :running {:agent :k0}))
       (d/transact conn [{:seon.agent/key "k0" :seon.agent/ended-ms 1}])
       (d/transact conn (ts/work-tx "session/w" :running {:agent :k1}))
       (d/transact conn (ts/work-tx "session/v" :running {:agent :k2}))
       (let [sys {:graph nil :ext (a/chan) :conn conn}]
         (is (= {:agent :k1 :task "session/w"} (select-keys (my.root/branch-agent sys "jolt/w") [:agent :task])) "the session's live agent, attached by its area")
         (is (= :k2 (:agent (my.root/branch-agent sys "jolt/v"))) "whoever attached it")
         (is (nil? (my.root/branch-agent sys "jolt/nobody")) "a branch whose session attached none holds none")
         (is (nil? (my.root/branch-agent sys "main")) "a branch of no session holds none"))))))

;; Estimate: a test branch (~1M), a task and its agent written (~0.5M), one AVET seek of each outcome (<0.1M): ~2M, 3,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-session-whose-hand-back-parked-it-hands-back-again
  ;; docs/server.md#ops: a session's task parked between requests (a hand-back that ended :parked: its agent's heap reached, 2026-11-16)
  ;; is still its agent's work, as live-run reads it, so seon_done hands it back again, never "works no task"
  (ts/test-branch!
   (fn [_ conn]
     (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "p" :seon.task/plan "Work p." :seon.task/proofs #{'demo-test/p}}]))
     (d/transact conn (ts/work-tx "session/p" :parked {:agent :k3}))
     (is (= {:task "session/p" :key "k3"} (select-keys (#'seon.server/work-of {:graph nil :ext (a/chan) :conn conn} :k3) [:task :key]))
         "the parked session's agent works its task"))))

;; ---- the ops over a stub sys, in process: no root, no agent, no fork (the writer, the api's waits and spawn are stubbed at their edge) ----

(defn- done-promise {:malli/schema [:=> [:cat :any] :any]} [v] (doto (promise) (deliver v)))

(defmacro ^:private stubbed
  "Run body with root's edges answered over conn (a test branch): transact-main! writes conn, message! writes the one message entity
  (seon.system/message-write's fact), an inbox wait is answered by its own typed bound at once, and state is the agents map."
  [conn agents & body]
  `(with-redefs [seon.system/transact-main! (fn [_# tx# & _#] (done-promise (let [r# (d/transact ~conn tx#)] (assoc r# :t (:max-tx (:db-after r#))))))
                 seon.system/message! (fn [_# id# body# & [{from# :from}]]
                                        (done-promise (d/transact ~conn (task/message-tx (seon.lib/unique-id) (task/sender (or from# (seon.config/value :seon.config.root/user-name)))
                                                                                         [(name id#)] body# 1))))
                 seon.system/await-message (fn [_# k# t0# opts#] {:p (done-promise (:due opts#)) :k (:k opts#)})
                 seon.system/drop-wait! (fn [_# _#] nil)
                 seon.system/state (fn [_#] {:agents ~agents})]
     ~@body))

(defn- session-w! {:malli/schema [:=> [:cat :any :keyword] :any]} [conn agent]
  (d/transact conn (task/create-tx @conn [{:seon.task/ns 'session :seon.task/name "w" :seon.task/plan "Work w." :seon.task/proofs #{'demo-test/w}}]))
  (d/transact conn (ts/work-tx "session/w" :running {:agent agent})))

(defn- bodies {:malli/schema [:=> [:cat :any :string] [:vector :string]]} [db k] (mapv :body (task/undelivered db nil k)))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-message-is-to-the-agent-named-or-its-tasks-and-from-its-connections-agent
  ;; docs/server.md#ops: :message to an agent id, or to a task (session/<area>) by seon.task/agent-key; from the agent a client's session holds
  ;; (connection-agent), else the signed-in person; neither named: refused. Over the writer's edge, no root
  (ts/test-branch!
   (fn [_ conn]
     (session-w! conn :k1)
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           from-of #(task/sender-name (:from (first (task/undelivered @conn nil "k1"))))]
       (stubbed conn {}
         (seon.server/op-answer sys :k1 {:op :message :body "by id"})
         (is (= (seon.config/value :seon.config.root/user-name) (from-of)) "a client of no session: from the signed-in person")
         (seon.server/op-answer sys nil {:op :message :task "session/w" :body "by its task"})
         (seon.server/op-answer sys :k1 {:op :message :body "from a session" :client "w"})
         (is (= ["by id" "by its task" "from a session"] (bodies @conn "k1")))
         (is (= "k1" (task/sender-name (:from (last (task/undelivered @conn nil "k1"))))) "from the agent its connection holds")
         (is (contract/error? (seon.server/op-answer sys nil {:op :message :task "session/none" :body "x"})) "no agent named, none of the task's: refused"))))))

(def ^:private two-messages
  [[:m1 "first" 4] [:m2 "second" 5]])

(deftest ^{:seon.test/estimate {:ticks 3000000}} an-inbox-reads-from-its-since-and-writes-nothing
  ;; docs/users.md#messages: asked from since 0 it answers every message with :t; asked again from that :t it has nothing new and ends at the
  ;; api's typed bound (its wait stubbed to answer its own due at once); two readers of one since both read a message (a read marks
  ;; nothing); a stream writes each message as a line through *emit*, then the typed end with the since to stream from again
  (ts/test-branch!
   (fn [_ conn]
     (session-w! conn :k1)
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           t0 (str (:max-tx @conn))]
       (d/transact conn (task/message-tx "m1" {:seon.agent/key "user"} ["k1"] "first" 4))
       (d/transact conn (task/message-tx "m2" {:seon.agent/key "user"} ["k1"] "second" 5))
       (stubbed conn {}
         (let [head (:max-tx @conn)
               a (#'seon.server/inbox-answer sys {:agent "k1" :since "0"})
               nothing (#'seon.server/inbox-answer sys {:agent "k1" :since (str (:t a))})
               b (#'seon.server/inbox-answer sys {:agent "k1" :since t0})
               lines (atom [])
               end (binding [seon.server/*emit* #(swap! lines conj %)] (seon.server/op-answer sys nil {:op :inbox :agent "k1" :stream true :since t0}))]
           (is (= ["first" "second"] (mapv :body (:messages a))) (pr-str a))
           (is (= :seon.server/no-message (:seon.error/type nothing)) (pr-str nothing))
           (is (= ["first" "second"] (mapv :body (:messages b))) "a second reader of the same since reads them too")
           (is (= head (:max-tx @conn)) "nothing was written")
           (is (= ["first" "second"] (mapv :body @lines)) "each message a line")
           (is (and (= :seon.server/no-message (:seon.error/type end)) (int? (:t end))) (pr-str end))))))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-root-eval-replies-like-an-agent-eval-and-reads-its-requests-input
  ;; task seon.server/root-evals-through-the-one-repl: an eval as root goes through the agents' REPL path (the printer, a result handle, the error
  ;; map); its *in* is the request's own input, empty when none (a read of stdin once held a connection 30 min);  Over a stub sys: the record's write goes to a test branch
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           ask (fn [code & [input]] (:response (seon.server/op-answer sys :root (cond-> {:op :eval :agent "root" :code code} input (assoc :input input)))))]
       (stubbed conn {}
         (let [ok (ask "{:a 1}") bad (ask "(/ 1 0)") sent (ask "(when (.ready ^java.io.Reader *in*) (slurp *in*))" "abc")
               none (ask "(if (.ready ^java.io.Reader *in*) (slurp *in*) :not-ready)")]
           (is (= {:a 1} (:value ok)) (pr-str ok))
           (is (qualified-symbol? (:handle ok)) "root's value has its result handle, as an agent's does")
           (is (some? (:seon.error/type (:ex bad))) (pr-str bad))
           (is (= "abc" (:value sent)) "the request's input is what the forms read")
           (is (= "" (:value none)) "with none sent, stdin is empty: a read answers at once")))))))

;; Estimate: an empty test branch ~0.3M, two namespaces made as root.repl is (~0.8M and ~2 MB each: refer-clojure's ~650 refers each
;; copy a path of the namespace's mappings; measured 0.95M / 3.1 MB with an eval's own overhead) and seven root evals (~0.2M each): ~3.3M; 4,000,000
(deftest ^{:seon.test/estimate {:ticks 4000000}} each-callers-root-vars-are-its-own
  ;; docs/server.md#ops: root's evals all ran in root.repl, so two tests sharing one candidate's root (the prove proc deals process
  ;; tests to its free workers in shares on one booted candidate) defined the same var: one's (def ready-ev ...) took the other's
  ;; place, whose hello wait then read a channel the first one's teardown untapped, and answered nil at its ask's bound
  ;; (with-agent's "ended before its hello", :end nil, 4 in-agent tests on every session's candidate run, 2026-11-17)
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           ask (fn [ns code] (:response (seon.server/op-answer sys :root (cond-> {:op :eval :agent "root" :code code} ns (assoc :ns ns)))))
           [a b] (repeatedly 2 #(str "root." (seon.lib/unique-id)))]
       (stubbed conn {}
         (try
           (ask a "(def held :a)") (ask b "(def held :b)")
           (is (= [:a :b] [(:value (ask a "held")) (:value (ask b "held"))]) "each caller reads its own var")
           (is (= 'seon.system (:value (ask a "(ns-name (get (ns-aliases *ns*) 'c))"))) "its namespace is made as root.repl is")
           (is (= 'root.repl (:value (ask nil "(ns-name *ns*)"))) "with none named, root.repl")
           (finally (run! #(remove-ns (symbol %)) [a b]))))))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} an-op-past-its-bound-answers-its-caller-and-root-eval-runs-unbounded
  ;; docs/server.md#ops: an op runs under its declared work bound (:seon.config.fx/bounds :server-op, seon.intr/run in seon.server/answer), so one
  ;; past it answers its caller the typed defect, and the next request is answered; root's own :eval is unbounded
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn}
           v seon.config/value]
       (stubbed conn {}
         (with-redefs [seon.config/value (fn [k & more] (if (= k :seon.config.fx/bounds) {:server-op {:seon.bound/ticks 1000}} (apply v k more)))]
           (let [past (seon.edn/decode (#'seon.server/respond sys "{:op :agents}"))
                 ok (:response (seon.edn/decode (#'seon.server/respond sys "{:op :eval :agent \"root\" :code \"(reduce + (range 100000))\"}")))]
             (is (= :seon.server/defect (:seon.error/type past)) (pr-str (select-keys past [:seon.error/type :seon.error/message])))
             (is (= :seon.repl/ticks-spent (get-in past [:data :seon.error/type])) "with the bound's typed error")
             (is (= 4999950000 (:value ok)) "and root's own eval runs unbounded, the server going on"))))))))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-task-is-declared-held
  ;; repl-audit B F3: no op made a task. A plain create answers the id and starts nothing
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn}]
       (stubbed conn {}
         (let [made (seon.server/op-answer sys nil {:op :create :ns "demo" :name "made" :plan "Do it."})]
           (is (= {:task "demo/made"} made) (pr-str made))
           (is (= :held (:seon.task/execution (d/pull @conn [:seon.task/execution] [:seon.task/id "demo/made"]))) "nothing starts")))))))

;; attach: the spawn, the clone and the kill are answered at their edge over the test branch (what an agent does once started is the agent-process tests')
(defmacro ^:private spawning
  "Run body with seon.system/call! recording each spawn request's opts in the atom `spawned` and answering {:id (keyword key)} (or `refusal`
  when non-nil), clone! recording [from opts] in `spawned` too and answering {:id :k2}, kill! recording in the atom `killed`, and branch-db
  answering the db that thunk `branch-db` makes."
  [spawned killed refusal branch-db & body]
  `(with-redefs [seon.system/call! (fn [_# req#] (let [opts# (:opts req#)] (swap! ~spawned conj opts#) (done-promise (or ~refusal {:id (keyword (:key opts#))}))))
                 seon.system/clone! (fn [_# from# opts#] (swap! ~spawned conj [from# opts#]) (done-promise {:id :k2}))
                 seon.system/kill! (fn [_# a#] (swap! ~killed conj a#) nil)
                 seon.system/branch-db (fn [_# _#] (done-promise {:ok true :db (~branch-db)}))]
     ~@body))

(defn- demo-tasks! {:malli/schema [:=> [:cat :any [:vector :string]] :any]} [conn names]
  (d/transact conn (task/create-tx @conn (vec (for [n names] {:seon.task/ns 'demo :seon.task/name n :seon.task/plan (str "Work " n ".") :seon.task/kind :work :seon.task/proofs #{(symbol "demo-test" n)}
                                                          :seon.task/branch :task-demo-t})))))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-refused-attach-writes-nothing-and-names-its-branchs-unmerged-entries
  ;; attach writes the env, the release and the start in one transaction before its spawn; a refused spawn answers that start, so the task is held again
  ;; with no env and no agent, its entity as before. A refusal names the unmerged entries its branch holds, and where their repair is
  ;; (docs/merging.md#root-repairs-a-failed-merge-from-its-repl). The spawn's refusal is stubbed (the branch held by another task's agent)
  (ts/test-branch!
   (fn [_ conn]
     (demo-tasks! conn ["u"])
     (let [sys {:graph nil :ext (a/chan) :conn conn :boot "boot-1"}
           pull (fn [] (dissoc (d/pull @conn '[*] [:seon.task/id "demo/u"]) :seon.task/outcome :seon.task/reason :seon.task/error))
           before (pull)
           e (:db/id (d/entity @conn [:seon.task/id "demo/u"]))
           um-db #(:db-after (d/with @conn (seon.merge/unmerged-tx @conn [{:key [:datum e :seon.task/plan] :base "Work u." :ours "Work u." :theirs "main's"}] 0)))
           held (#'seon.server/refused "branch :task-demo-t is held by live agent :k0 working demo/t: its seon_done")
           spawned (atom []) killed (atom [])]
       (stubbed conn {}
         (spawning spawned killed held um-db
           (let [refused (seon.server/op-answer sys nil {:op :attach :task "demo/u"})
                 t (task/task @conn "demo/u")]
             (is (clojure.string/includes? (pr-str refused) "is held by live agent") "the attach is refused")
             (is (= [:held nil false] [(:seon.task/execution t) (:seon.task/model t) (some? (:seon.task/agent t))]) "the u task is held again, with no env and no agent")
             (is (= before (pull)) "its entity exactly as before, apart from the refusal (each fact the start wrote inverted)")
             (is (= [[:datum e :seon.task/plan]] (:unmerged refused)) "the refusal names the branch's unmerged entry")
             (is (clojure.string/includes? (pr-str refused) "root-repairs-a-failed-merge-from-its-repl") "with where its repair is"))))))))

(deftest ^{:seon.test/estimate {:ticks 4000000}} an-attach-answers-the-live-agent-continues-a-dead-one-and-resets-from-main
  ;; docs/server.md#ops: a task whose agent lives (running, or parked at a turn boundary) is answered that agent, :shared, never a second spawn; a
  ;; session's first attach makes its task of the :user template (kind :user, model outside); an agent gone from root's view is replaced by a new
  ;; process on its branch with the key continuing (a death is a fact root's view holds); :fresh moves the task to a new branch from main
  ;; (-r<n>, seon.task/restart-tx) and kills the old agent
  (ts/test-branch!
   (fn [_ conn]
     (let [sys {:graph nil :ext (a/chan) :conn conn :boot "boot-1"}
           spawned (atom []) killed (atom [])
           attach #(seon.server/op-answer sys nil (merge {:op :attach} %))
           db #(deref conn)]
       (stubbed conn {}
         (spawning spawned killed nil #(deref conn)
           ;; a session of the :user template, made at its first attach
           (let [one (attach {:task "session/s" :template "user" :client "s-park"})
                 made [(:seon.task/kind (task/task (db) "session/s")) (:seon.task/model (d/entity (db) [:seon.task/id "session/s"]))]]
             (is (keyword? (:agent one)) (pr-str one))
             (is (= [:user "outside"] made) "of the :user template's kind, driven from outside")
             ;; parked, its process alive in root's view: the attach answers it
             (d/transact conn [{:seon.task/id "session/s" :seon.task/outcome :parked}])
             (with-redefs [seon.system/state (fn [_] {:agents {(:agent one) {:status :idle}}})]
               (let [again (attach {:task "session/s" :template "user" :client "s-park-again"})]
                 (is (and (:shared again) (= (:agent one) (:agent again))) (str "the parked agent is answered, not a second spawn: " (pr-str again)))
                 (is (= 1 (count @spawned)) "one spawn only"))))
           ;; a dead agent: gone from root's view
           (demo-tasks! conn ["t"])
           (let [first-a (attach {:task "demo/t"})
                 second-a (attach {:task "demo/t"})]
             (is (and (keyword? (:agent first-a)) (keyword? (:agent second-a))) (pr-str [first-a second-a]))
             (is (not (:shared second-a)) "its own start, not the dead agent's")
             (is (= 2 (count (filter :key (rest @spawned)))))
             (is (apply = (map :key (rest @spawned))) "the session's agent continues, its key")
             ;; reset: the agent is live, the attach fresh
             (with-redefs [seon.system/state (fn [_] {:agents {(:agent second-a) {:status :idle}}})]
               (reset! spawned [])
               (let [reset (attach {:task "demo/t" :fresh true})]
                 (is (keyword? (:agent reset)) (pr-str reset))
                 (is (re-find #"-r[0-9]+$" (name (:branch (first @spawned)))) "the task's branch is a new one, named by its ending (seon.task/restart-tx)")
                 (is (= [(:agent second-a)] @killed) "the agent on the old branch is killed"))))))))))

(deftest ^{:seon.test/estimate {:ticks 3000000}} a-fork-is-a-clone-on-a-sibling-task
  ;; the owner (2026-10-27): seon_attach {:from a} clones a live agent onto a sibling task <name>-fork-<n> holding the task's plan and proofs,
  ;; so it tries an alternative from where it stood (seon.system/clone!, stubbed at its edge)
  (ts/test-branch!
   (fn [_ conn]
     (demo-tasks! conn ["t"])
     (let [sys {:graph nil :ext (a/chan) :conn conn :boot "boot-1"}
           spawned (atom []) killed (atom [])]
       (stubbed conn {:k1 {:task "demo/t" :status :idle}}
         (spawning spawned killed nil #(deref conn)
           (let [fork (seon.server/op-answer sys nil {:op :attach :from "k1"})]
             (is (clojure.string/ends-with? (str (:task fork)) "/t-fork-1") (pr-str fork))
             (is (= "Work t." (:seon.task/plan (task/task @conn "demo/t-fork-1"))) "the sibling task holds the task's plan")
             (is (= :k1 (ffirst @spawned)) "cloned from the agent forked"))))))))

;; docs/server.md#working-a-task-from-a-worktree: an import reads the file from the checkout the caller names, a session's worktree,
;; never the main tree its relay's client names (data found worktree edits read as unchanged). Red first: the op read the client's
;; checkout alone. Estimate: two canonical paths: 50,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 50000}} an-import-reads-the-checkout-its-caller-names
  (let [wt (str (System/getProperty "java.io.tmpdir") "/wt-" (random-uuid)) main (str (System/getProperty "java.io.tmpdir") "/main-" (random-uuid))
        canon #(.getCanonicalPath (java.io.File. ^String %))]
    (is (= (canon wt) (seon.server/import-checkout {:checkout wt :client (str "checkout-" main)})) "the caller's worktree, over its client's main tree")
    (is (= (canon main) (seon.server/import-checkout {:client (str "checkout-" main)})) "else the client's")
    (is (nil? (seon.server/import-checkout {:client "tests2"})) "else none")))

;; docs/server.md#ops: a session's task parked between hand-backs keeps its agent as a desired state: seon_done on it after the agent
;; ended brings one back on the task's own branch (attach!) and carries on, so the caller never attaches again. Red first: seon_done
;; answered "agent ended" (the pilot's run 2, 2026-11-16). Estimate: four calls of a pure rule: 20,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 20000}} a-done-on-a-parked-task-brings-its-agent-back
  (is (= "session/x" (seon.server/resume-task {:status :ended} {:task "session/x"})) "an ended agent of a parked task: brought back")
  (is (= "session/x" (seon.server/resume-task nil {:task "session/x"})) "and one root no longer holds")
  (is (nil? (seon.server/resume-task {:status :running} {:task "session/x"})) "a live agent works on")
  (is (nil? (seon.server/resume-task {:status :ended} nil)) "an agent with no parked task is not brought back"))

;; plan NOW (root, until F2): seon_done merges once the in-process tests its change reaches are green, and the process tests it reaches
;; are named on its answer as unrun, never silently deferred; the task entries its import left out are named beside them. Red first:
;; the line held neither. Estimate: a world, one task fact, one verdict read and print: 4,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-dones-line-names-the-process-tests-it-left-unrun
  (let [db (my.test/with (my.test/world) [{:seon.task/id "app.calc/total-is-exact" :seon.task/outcome :merged
                                           :seon.task/deferred {'app.calc-test/boots-a-root (seon.lib/digest "boots")}}])
        line (:line (seon.server/verdict-line db "app.calc/total-is-exact" nil {"app.x/dangles" "needs name no task: gone/deleted"}))]
    (is (clojure.string/includes? line "app.calc-test/boots-a-root") (str "the unrun process test is named: " line))
    (is (clojure.string/includes? line ":unrun") "under :unrun, with its count")
    (is (clojure.string/includes? line "app.x/dangles") "and the entry the import left out")))
