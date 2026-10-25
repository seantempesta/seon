(ns seon.server-test
  "Root's server as an outside agent reaches it (seon.server, through the built root:
  seon.agent-test/in-agent). Run: bin/test seon.server-test"
  (:require [clojure.test :refer [deftest is]] [seon.agent-test :as at] [seon.contract :as contract] [seon.server] [clojure.data.json] [clojure.edn] [clojure.string]))

(deftest a-root-eval-replies-like-an-agent-eval
  ;; task seon.server/root-evals-through-the-one-repl: an eval as root goes through the
  ;; agents' REPL path, so its response is an agent's: the one printer, a result handle, the
  ;; error map, *1 kept between requests
  (let [[a-ret a-err r-ret r-err r-star]
        (at/in-agent ["{:a 1}" "(/ 1 0)"
                      {:op :eval :agent "root" :code "{:a 1}"} {:op :eval :agent "root" :code "(/ 1 0)"}
                      {:op :eval :agent "root" :code "(count (:agents (c/state sys)))"}])]
    (is (= {:a 1} (:value a-ret) (:value r-ret)) "a value replies alike")
    (is (qualified-symbol? (:handle r-ret)) "root's value has its result handle, as an agent's does")
    (is (= (select-keys (:ex a-err) [:seon.error/type :seon.error/message]) (select-keys (:ex r-err) [:seon.error/type :seon.error/message]))
        "an error replies alike")
    (is (= 1 (:value r-star)) "`sys` is this root: one agent")))

(deftest a-server-refused-answer-is-the-one-error-map
  ;; task seon.render/one-owner-per-view-path (B2): the server refuses as root does, with the
  ;; one error map, never a second {:error "text"} shape
  (let [answers (at/in-agent [{:op :nope} {:op :context :agent "a9"} {:op :done :agent "a9"} {:op :attach :task "no/such"}])]
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
  ;; task seon.server/the-server-answers-mcp-itself: root's server answers MCP's JSON-RPC
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
    (is (= ["agent" "code"] (get-in (second tools) ["inputSchema" :required])) "an op's required arguments are its tool's")
    (is (nil? (ask {"jsonrpc" "2.0" "method" "notifications/initialized"})) "a notification is not answered")
    (is (= -32601 (get-in (ask {"id" 3 "method" "resources/list"}) ["error" "code"])) "a method it does not know: method not found")
    (is (= -32602 (get-in (ask {"id" 4 "method" "tools/call" "params" {"name" "seon_nope" "arguments" {}}}) ["error" "code"])) "a tool no op names: invalid params")))

(deftest a-line-opening-a-string-key-is-mcps
  ;; task seon.server/the-server-answers-mcp-itself: the protocol rule. A line opening {" is
  ;; JSON-RPC, so an EDN request with a string key is refused naming the rule (-32700), never
  ;; misread; a keyword-keyed EDN line still reads as EDN
  (let [respond #'seon.server/respond
        bad (clojure.data.json/read-str (respond nil "{\"op\" \"agents\"}"))]
    (is (= -32700 (get-in bad ["error" "code"])))
    (is (clojure.string/includes? (get-in bad ["error" "message"]) "keyword-keyed map"))
    (is (contract/error? (clojure.edn/read-string (respond nil "{:op :nope}"))) "a keyword-keyed line is EDN in, EDN out")))

(deftest a-refused-attach-writes-nothing
  ;; task seon.server/attach-writes-nothing-when-it-is-refused: attach spawns first and
  ;; writes the env, the release and the run in one transaction only once its agent
  ;; exists, so a refused spawn leaves the task as it was, and the reconciler never sees a
  ;; release with no run (it would spawn a second agent for it). demo/u shares demo/t's
  ;; branch, which demo/t's attached agent holds
  (let [[refused seen] (at/in-agent [{:op :attach :task "demo/u"}
                                     {:op :eval :agent "root"
                                      :code (str "(let [db @(:conn sys) t (seon.task/task db \"demo/u\")]"
                                                 " [(:seon.task/execution t) (:seon.task/model t)"
                                                 " (count (filter #(= \"demo/u\" (:seon.run/task %)) (vals (seon.task/runs db))))])")}]
                                    {:decls [{:seon.task/name "u" :seon.task/plan "Work too." :seon.task/kind :work
                                              :seon.task/proofs #{'demo-test/u} :seon.task/branch :task-demo-t}]})]
    (clojure.test/is (clojure.string/includes? (pr-str refused) "is held by live agent") "the attach is refused: demo/t's agent holds the branch")
    (clojure.test/is (= [:held nil 0] (:value seen)) "and wrote nothing: demo/u is still held, with no env and no run")))

(deftest a-message-reaches-the-agent
  ;; task seon.render/a-debug-view-of-any-agent (parity row 73): the owner's message, sent as
  ;; the page's message bar sends it (seon.root/message!, here through the server's :message
  ;; op), is root's one delivery: it is in the agent's inbox when the agent next reads it,
  ;; though it arrived while the agent was not reading its socket; no agent: refused
  (let [[sent taken none] (at/in-agent [{:op :message :agent "a1" :body "use demo/g"}
                                        "(my.agent/messages)"
                                        {:op :message :agent "a9" :body "hello?"}])]
    (is (true? (:ok sent)) "answered when its fact is on main")
    (is (re-find #":from :owner" (:text taken)) (:text taken))
    (is (re-find #":body \"use demo/g\"" (:text taken)))
    (is (re-find #":to \"demo/t#1\"" (:text taken)) "to the run the agent works")
    (is (re-find #":id \"m-" (:text taken)) "the fact's id, which the agent writes on its branch")
    (is (contract/error? none) "no live agent a9: refused, as root refuses")))

(deftest the-context-op-answers-what-a-model-is-sent
  ;; dogfood 2026-10-25: an outside agent read the story and turn sections only, not the system
  ;; prompt and screen a model is sent, and its (my.task/status) answered :seon.turn/not-observed.
  ;; Its context read is its turn: the screen's forms run as a model turn's do, and the read
  ;; answers the request's text, system prompt first, screen last
  (let [[ctx status sent] (at/in-agent [{:op :context :agent "a1"} "(my.task/status)"
                                        {:op :eval :agent "root"
                                         :code (str "(let [db (:db @(seon.root/branch-db sys (seon.task/default-branch \"demo/t\")))]"
                                                    " (datahike.api/q '[:find [?n ...] :where [?t :seon.turn/origin :outside] [?t :seon.turn/sent _] [?t :seon.turn/n ?n]] db))")}])
        text (:context ctx)]
    (is (clojure.string/starts-with? text "# You are a Seon agent") "the system prompt, first")
    (is (clojure.string/includes? text "(my.task/status)") "the screen's forms, as a model's request ends with them")
    (is (clojure.string/includes? text "(my.branch/changes)"))
    (is (not (contract/error? (:value status))) (pr-str (:value status)))
    (is (contains? (:value status) :proofs) "status answers as the turn measured it")
    (is (seq (:value sent)) "and the read records what it sent, as a model turn does (the debug page shows it)")))

(deftest done-answers-the-judges-reasons
  ;; dogfood 2026-10-25: seon_done answered {:judging ...} and ended the agent, so an outside agent
  ;; never saw its verdict. It answers when main holds the judgement: here the one proof was never
  ;; written, so the done rule refuses, the task is held, and the answer says why
  (let [[done] (at/in-agent [{:op :done :agent "a1"}])]
    (is (= :closed (:status done)) (pr-str done))
    (is (= :red (:outcome done)))
    (is (clojure.string/includes? (str (get-in done [:held :seon.task/failure :reason])) "proof-unknown demo-test/t")
        "the done rule's reasons, as the judge wrote them on the held task")))
