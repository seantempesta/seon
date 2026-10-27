(ns seon.server-test
  "Root's server as an outside agent reaches it (seon.server, through the built root:
  seon.agent-test/in-agent). Run: bin/test seon.server-test"
  (:require [clojure.test :refer [deftest is]] [seon.agent-test :as at] [seon.contract :as contract] [seon.server] [clojure.data.json] [clojure.edn] [clojure.string]
            [seon.test-store :as ts] [seon.task :as task] [datahike.api :as d]))

(deftest each-op-has-its-answer
  ;; the ops table (each op's doc and args, the MCP tool list) and op-answer's methods are one set of
  ;; keys: an op with no method would be listed and answer "unknown op", a method with no entry unlisted
  (is (= (set (keys seon.server/ops)) (disj (set (keys (methods seon.server/op-answer))) :default)))
  (is (contains? (methods seon.server/op-answer) :default) "an unknown op has its answer: refused"))

(deftest seon-agents-answers-what-is-live
  ;; seon_agents answered every task on main (580 on the standing root, most :held or
  ;; :cancelled with no run), so an outside agent never saw what runs
  (ts/test-branch!
   (fn [_ conn]
     (let [before (:others (seon.server/live-tasks @conn))
           decls (for [n ["a" "b" "c" "d" "e"]] {:seon.task/ns 'demo :seon.task/name n :seon.task/plan "Work." :seon.task/proofs #{}})
           _ (d/transact conn (task/create-tx @conn (vec decls)))
           env {:branch :demo :model nil :max-turns 4}
           _ (d/transact conn (concat (task/execution-tx ["demo/b" "demo/d"] :running) (task/execution-tx ["demo/e"] :cancelled)
                                      (task/run-start-tx "demo/b#1" "demo/b" "boot-1" :a1 env 1000 "Work.")
                                      (task/run-start-tx "demo/d#1" "demo/d" "boot-1" :a2 env 2000 "Work.")))
           {:keys [tasks others all]} (seon.server/live-tasks @conn)]
       (is (= ["demo/d" "demo/b"] (filterv #(= "demo" (namespace (symbol %))) (keys tasks))) "the running tasks, newest run first, and no held one")
       (is (= [["demo/d#1" :running nil nil nil]] (get-in tasks ["demo/d" :runs])) "each with its runs")
       (is (= (merge-with + before {:held 2 :cancelled 1}) others) "the others counted by execution: demo/a and demo/c held, demo/e cancelled")
       (is (= '(seon.server/task-summaries @(:conn sys)) all) "with the form that lists every task")))))

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
  ;; release with no run (it would spawn a second agent for it). <ns>/u shares <ns>/t's
  ;; branch, which <ns>/t's attached agent holds
  (let [[refused seen] (at/in-agent (fn [{:keys [ns]}]
                                      (let [u (str ns "/u")]
                                        [{:op :attach :task u}
                                         {:op :eval :agent "root"
                                          :code (str "(let [db @(:conn sys) t (seon.task/task db " (pr-str u) ")]"
                                                     " [(:seon.task/execution t) (:seon.task/model t)"
                                                     " (count (filter #(= " (pr-str u) " (:seon.run/task %)) (vals (seon.task/runs db))))])")}]))
                                    {:decls (fn [{:keys [ns]}]
                                              [{:seon.task/name "u" :seon.task/plan "Work too." :seon.task/kind :work
                                                :seon.task/proofs #{'demo-test/u} :seon.task/branch (keyword (str "task-" ns "-t"))}])})]
    (clojure.test/is (clojure.string/includes? (pr-str refused) "is held by live agent") "the attach is refused: the t task's agent holds the branch")
    (clojure.test/is (= [:held nil 0] (:value seen)) "and wrote nothing: the u task is still held, with no env and no run")))

(deftest an-attached-agent-holds-its-run
  ;; agent view's browser check: an agent attached from outside (seon.server/attach!
  ;; spawns it before it writes the run on main, so its branch is cut without the run) held no
  ;; :runs in @ctx, and its page no task, until a turn or a context read began the run. Forked for
  ;; a run, the agent records it on its branch as it starts (seon.agent/begin-run!): before any turn
  (let [names (volatile! nil)
        [held] (at/in-agent (fn [n] (vreset! names n) ["(let [t @seon.ctx/ctx r (val (first (:runs t)))] [(count (:runs t)) (:seon.run/id r) (get-in r [:task :seon.task/id])])"]))]
    (is (= [1 (:run @names) (:task @names)] (:value held)) "its run, with its task, in its record")))

(deftest an-agent-reads-its-task-running
  ;; task seon.task/an-agent-reads-its-task-as-main-holds-it: an attached agent's branch is cut
  ;; before main writes the task's release, so (my.task/current) and its page read :held
  (let [[current page] (at/in-agent ["(:seon.task/execution (my.task/current))"
                                     "(pr-str (seon.repl/render-ctx-hiccup))"])]
    (is (= :running (:value current)) "the agent reads its task running, as main does")
    (is (clojure.string/includes? (:value page) "· running") "and so does its page")))

(defn- in-run
  "Root-REPL code that evaluates form in the agent run rid works, answering its value (read back
  from the response's text, as the server's reader does)."
  [rid form]
  (str "(let [a (some #(when (= " (pr-str rid) " (:seon.run/id %)) (:seon.run/agent %)) (seon.task/running @(:conn sys)))]"
       " (:value (clojure.edn/read-string {:default tagged-literal} (:text @(c/submit! sys a " (pr-str form) ")))))"))

(deftest a-reset-starts-the-task-again-from-main
  ;; the owner (2026-10-27): reset an agent easily. seon_attach {:fresh true}: a new branch cut from
  ;; main and an empty conversation; the old branch and its runs stay, the record
  (let [[_ reset branch seen] (at/in-agent (fn [{:keys [ns task]}]
                                             [(str "(ns " ns ") (def x 41)")
                                              {:op :attach :task task :fresh true}
                                              {:op :eval :agent "root" :code (str "(:seon.task/branch (seon.task/task @(:conn sys) " (pr-str task) "))")}
                                              {:op :eval :agent "root" :code (in-run (str task "#2") (str "[(some-> (find-ns '" ns ") (ns-resolve 'x)) (count (:evals @seon.ctx/ctx))]"))}]))]
    (is (clojure.string/ends-with? (:run reset) "#2") (pr-str reset))
    (is (clojure.string/ends-with? (name (:value branch)) "-r2") "the task's branch is a new one")
    (is (= [nil 0] (:value seen)) "whose agent holds nothing the old one defined, and no conversation")))

(deftest a-fork-goes-on-from-an-agents-head
  ;; the owner (2026-10-27): fork an agent easily. seon_attach {:from a}: a clone of the agent on a
  ;; branch of its head, working a sibling task, so it tries an alternative from where it stood
  (let [[_ fork plan seen] (at/in-agent (fn [{:keys [ns agent]}]
                                          [(str "(ns " ns ") (def x 41)")
                                           {:op :attach :from agent}
                                           {:op :eval :agent "root" :code (str "(:seon.task/plan (seon.task/task @(:conn sys) \"" ns "/t-fork-1\"))")}
                                           {:op :eval :agent "root" :code (in-run (str ns "/t-fork-1#1") (str "[(some-> (find-ns '" ns ") (ns-resolve 'x) deref) (some? (datahike.api/entity (seon.agent/db) [:var/name '" ns "/x]))]"))}]))]
    (is (clojure.string/ends-with? (str (:task fork)) "/t-fork-1") (pr-str fork))
    (is (= "Work." (:value plan)) "the sibling task holds the task's plan")
    (is (= [41 true] (:value seen)) "and its agent what the forked one defined, live and on its branch")))

(deftest a-message-reaches-the-agent
  ;; task seon.render/a-debug-view-of-any-agent (parity row 73): the owner's message, sent as
  ;; the page's message bar sends it (seon.root/message!, here through the server's :message
  ;; op), is root's one delivery: it is in the agent's inbox when the agent next reads it,
  ;; though it arrived while the agent was not reading its socket; no agent: refused
  (let [names (volatile! nil)
        [sent taken none] (at/in-agent (fn [{:keys [agent] :as n}]
                                         (vreset! names n)
                                         [{:op :message :agent agent :body "use demo/g"}
                                          "(my.agent/messages)"
                                          {:op :message :agent "a0" :body "hello?"}]))]
    (is (true? (:ok sent)) "answered when its fact is on main")
    (is (re-find #":from \"owner\"" (:text taken)) (:text taken))
    (is (re-find #":body \"use demo/g\"" (:text taken)))
    (is (clojure.string/includes? (:text taken) (str ":to " (pr-str (:run @names)))) "to the run the agent works")
    (is (re-find #":id \"m-" (:text taken)) "the fact's id, which the agent writes on its branch")
    (is (contract/error? none) "no live agent a0: refused, as root refuses")))

(deftest the-context-op-answers-what-a-model-is-sent
  ;; dogfood 2026-10-25: an outside agent read the task's data and turn sections only, not the system
  ;; prompt and screen a model is sent, and its (my.task/status) answered :seon.turn/not-observed.
  ;; Its context read is its turn: the screen's forms run as a model turn's do, and the read
  ;; answers the request's text, system prompt first, screen last
  (let [[ctx status sent] (at/in-agent (fn [{:keys [agent task]}]
                                         [{:op :context :agent agent} "(my.task/status)"
                                          {:op :eval :agent "root"
                                           :code (str "(let [db (:db @(seon.root/branch-db sys (seon.task/default-branch " (pr-str task) ")))]"
                                                      " (datahike.api/q '[:find [?n ...] :where [?t :seon.turn/origin :outside] [?t :seon.turn/sent _] [?t :seon.turn/n ?n]] db))")}]))
        text (:context ctx)]
    (is (clojure.string/starts-with? text "# You are a Seon agent") "the system prompt, first")
    (is (clojure.string/includes? text "(seon.repl/render-ctx-hiccup)") "the form that shows the render of ctx now")
    (is (re-find #"task-data-\d+" text) "the render of ctx by component key")
    (is (not (contract/error? (:value status))) (pr-str (:value status)))
    (is (contains? (:value status) :proofs) "status answers as the turn measured it")
    (is (seq (:value sent)) "and the read records what it sent, as a model turn does (the debug page shows it)")))

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
  (let [[miss no-ns not-a-name a-class switched sourced state] (at/in-agent ["(my.agent/help \"a-section-that-is-not-there\")"
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
    (is (clojure.string/includes? (:text state) "(get (deref seon.agent/state) :db)") "each host object by its type, with the form that reads it")
    (is (nil? (:ex sourced)) (str "source resolves in a namespace the agent switched to: " (pr-str (:ex sourced))))
    (is (clojure.string/includes? (str (:text sourced)) "defn help") "and reads the definition")))

(deftest done-answers-the-judges-reasons
  ;; dogfood 2026-10-25: seon_done answered {:judging ...} and ended the agent, so an outside agent
  ;; never saw its verdict. It answers when main holds the judgement: here the one proof was never
  ;; written, so the done rule refuses, the task is held, and the answer says why
  (let [[done] (at/in-agent #(vector {:op :done :agent (:agent %)}))]
    (is (= :closed (:status done)) (pr-str done))
    (is (= :red (:outcome done)))
    (is (clojure.string/includes? (str (get-in done [:held :seon.task/failure :reason])) "proof-unknown demo-test/t")
        "the done rule's reasons, as the judge wrote them on the held task")))

(deftest an-outside-agent-changes-its-task-by-swap
  ;; the owner, 2026-10-27: "I want the ctx atom to be a mirror of the agents context that we are
  ;; pulling and they can do easy ops there on the atom rather than learning datalog". An agent
  ;; attached from outside reads its task in @ctx, changes the plan by swap! (no query, no my.*
  ;; function), and the change is on its branch, in its next @ctx, on its page, and told as its
  ;; next read's diff (seon.context/world): the plan's path with its old and new value, each eval
  ;; it made by its handle, which its own message shows
  (let [plan "Work: f0 returns its input."
        [first-turn _ swapped page elsewhere on-branch told next-turn]
        (at/in-agent (fn [{:keys [agent task]}]
                       [{:op :context :agent agent}
                        "(def c0 (datahike.api/commit-id (seon.agent/db)))"
                        (str "(let [rid (key (first (:runs @ctx)))] (swap! ctx assoc-in [:runs rid :task :seon.task/plan] " (pr-str plan) ")"
                             " (get-in @ctx [:runs rid :task :seon.task/plan]))")
                        "(pr-str (seon.repl/render-ctx-hiccup))"
                        ;; from any namespace, the record by its qualified name (a value the printer
                        ;; would show as an earlier eval's handle is compared here instead)
                        (str "(in-ns 'demo) (= " (pr-str plan) " (get-in @seon.ctx/ctx [:runs (key (first (:runs @seon.ctx/ctx))) :task :seon.task/plan]))")
                        {:op :eval :agent "root"
                         :code (str "(:seon.task/plan (datahike.api/entity (:db @(seon.root/branch-db sys (seon.task/default-branch " (pr-str task) ")))"
                                    " [:seon.task/id " (pr-str task) "]))")}
                        ;; what the next read tells of the swap!, between the commit before it and now
                        (str "(let [ms (seon.context/world (seon.agent/db) (:db/id @seon.ctx/ctx) " (str "agent." agent "/c0") " (datahike.api/commit-id (seon.agent/db))) t (:content (first ms))]"
                             " {:path (clojure.string/includes? t \":seon.task/plan]\") :old (clojure.string/includes? t " (pr-str (pr-str "Work.")) ")"
                             " :new (clojure.string/includes? t " (pr-str (pr-str plan)) ") :handle (clojure.string/includes? t \"result/e\")"
                             " :record (clojure.string/includes? t \":seon.eval/record\") :agent-id (clojure.string/includes? t \":seon.agent/id\") :tokens (seon.context/messages-tokens [{:role \"user\" :content (subs t (clojure.string/index-of t \":ctx\"))}])})")
                        {:op :context :agent agent}]))]
    (is (clojure.string/includes? (:context first-turn) "\"Work.\"") "the first turn shows the plan as it was")
    (is (= plan (:value swapped)) "the next @ctx holds what the swap! wrote")
    (is (clojure.string/includes? (:value page) plan) "the render of ctx draws it")
    (is (true? (:value elsewhere)) "read from another namespace as @seon.ctx/ctx")
    (is (= plan (:value on-branch)) "written on the agent's branch, one transaction")
    (is (= {:path true :old true :new true :handle true :record false :agent-id false} (dissoc (:value told) :tokens)) "the next turn tells it as a diff in ctx's shape: the plan's path, its old and its new value, each eval by its handle, never its record")
    ;; the :ctx part of the message, which is what the diff owns: about 90 tokens (630 when an eval was
    ;; told whole), four evals by their handles and the plan's old and new value. The page part beside
    ;; it is the task's render redrawn with the new plan (a whole task's instructions among it)
    (is (>= 100 (:tokens (:value told))) "a swap! is told in what it changed")
    (is (clojure.string/includes? (:context next-turn) (pr-str plan)) "the next read tells the plan as it is now")
    (is (clojure.string/includes? (:context next-turn) "result/e") "and each eval it made by its handle")
    (is (not (clojure.string/includes? (:context next-turn) ":seon.turn/basis")) "and the read before it by its id, never its record")
    (is (clojure.string/includes? (:context next-turn) (pr-str "Work.")) "the next read is what changed since the read before: the plan's old value, which a whole page no longer draws")))

(deftest a-later-read-draws-one-page-and-costs-what-the-one-before-did
  ;; the owner, 2026-10-27: a later turn's request build measured 94M ticks, 317 MB on Seon's own tasks, 25,000
  ;; contracted calls, two pages drawn a read (the one before's again, to diff against). Each read after
  ;; the first draws the one page it tells and costs what the read before did: ticks and bytes are the
  ;; process's exact counts (seon.profile/measure!), the page draws the contracted calls of seon.context/page
  (let [[reads]
        (at/in-agent (fn [{:keys []}]
                       [(str "(let [t (:task @seon.agent/state) r (:run @seon.agent/state)"
                             " m (fn [] (let [v (volatile! nil)]"
                             "           (seon.profile/measure! #(count (seon.turn/context-block t r true)) (fn [d] (vreset! v d)))"
                             "           {:ticks (:ticks @v) :bytes (:bytes @v)"
                             "            :pages (some (fn [f] (when (= 'seon.context/page (:var f)) (:calls f))) (seon.profile/touched))}))]"
                             "  (vec (repeatedly 5 m)))")]))
        later (rest (:value reads))]
    (is (= 5 (count (:value reads))))
    (is (every? #(= 1 (:pages %)) later) (str "each later read draws one page: " (pr-str (map :pages later))))
    (is (every? (fn [[a b]] (<= (:ticks b) (* 1.05 (:ticks a)))) (partition 2 1 later)) (str "and costs what the one before it did: " (pr-str (map :ticks later))))
    (is (every? (fn [[a b]] (<= (:bytes b) (* 1.05 (:bytes a)))) (partition 2 1 later)) (str "in bytes too: " (pr-str (map :bytes later))))))

(deftest an-attach-gives-the-caller-the-branch-or-names-who-holds-it
  ;; THE PLAN 0.7 (root, 2026-10-27): the first seon_attach to a task was refused "held by live
  ;; agent :a7", an agent the caller never attached. A context read of a task no agent works attaches
  ;; one (the :context op); the caller's attach after it is the same outside run: it answers that
  ;; agent, never a refusal. A task another task's agent holds the branch of is refused, naming the
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
    (is (= :seon.root/refused (:seon.error/type other)) (pr-str other))
    (is (re-find #"held by live agent :a\d+ working t[0-9a-f]+/t \(run t[0-9a-f]+/t#1\)" (str (:seon.error/message other))) (pr-str other))))

(deftest a-prove-never-ends-the-agent-it-proves
  ;; THE PLAN 0.7 (root, 2026-10-27): after seon_prove, the outside agent had ended ("agent a7
  ;; ended"), so my.task/fail! and seon_done recorded nothing ("a7 works no task"). An outside agent
  ;; lives until seon_done or an explicit end: a prove of its branch, then its REPL and its run
  (let [alive #(hash-map :op :eval :agent "root" :code (str "(contains? (:agents (c/state sys)) :" % ")"))
        [ctx proved after live1 main-proved after2 live2 failed]
        (at/in-agent (fn [{:keys [agent]}]
                       [{:op :context :agent agent}
                        {:op :prove :agent agent}
                        "(+ 1 2)"
                        (alive agent)
                        {:op :prove}
                        "(+ 1 2)"
                        (alive agent)
                        "(my.task/fail! \"cannot\")"]))]
    (is (string? (:context ctx)) (pr-str ctx))
    (is (not (contract/error? proved)) (pr-str proved))
    (is (= 3 (:value after)) (str "the agent answers after a prove of its branch: " (pr-str after)))
    (is (true? (:value live1)) "and root holds it live")
    (is (not (contract/error? main-proved)) (pr-str main-proved))
    (is (= 3 (:value after2)) (str "and after a prove of main: " (pr-str after2)))
    (is (true? (:value live2)))
    (is (not (contract/error? (:value failed))) (str "its fail! records on its run: " (pr-str failed)))))
