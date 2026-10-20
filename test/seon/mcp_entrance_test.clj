(ns seon.mcp-entrance-test
  "The MCP bridge's one JVM entrance (`seon.mcp/tools`, `seon.mcp/call`):
  JSON in, JSON out, and what an MCP caller reads is what its agent's next
  context reads."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.host :as host]
            [seon.cluster.registry :as registry]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.eval.drive :as drive]
            [seon.fault :as fault]
            [seon.id :as id]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.operator.runtime :refer [running-instances]]
            [seon.render.transcript :as transcript]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]
            [seon.test :as seon-test]
            [seon.turn :as turn]))

(defn- answer
  "`seon.mcp/call` on `tool` and `arguments`, decoded."
  {:malli/schema [:=> [:cat :string [:map-of :string :seon.schema/value]] [:map-of :string :seon.schema/value]]}
  [tool arguments]
  (json/read-str (mcp/call (json/write-str {"name" tool "arguments" arguments}))))

(defn- latest-run-in
  "The agent's newest run id in `database` (the latest transaction naming one)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:maybe :seon.turn/id]]}
  [database agent-id]
  (->> (db/q '[:find ?id ?tx :in $ ?agent-id
               :where [?agent :seon.agent/id ?agent-id] [?run :seon.turn/agent ?agent] [?run :seon.turn/id ?id ?tx]]
             database agent-id)
       (sort-by second) last first))

(defn- stored-responses
  "Each evaluation of run `run` as the agent's next context reads it, read back
  from the connection's current value."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id :seon.turn/id] [:vector [:maybe :string]]]}
  [connection agent-id run]
  (let [database (db/db connection)]
    (mapv :seon.render.history/printed-value
          (transcript/history-entries
           {:seon.db/db database :seon.agent/id agent-id
            :seon.sci.admit/caps (config/result-caps (config/effective database (:seon.cluster/name (support/execution-handle nil))))
            :seon.render.transcript/selected-run-id run}))))

(deftest tools-derive-each-input-schema-from-its-argument-schema
  (let [tools (json/read-str (mcp/tools))
        by-name (into {} (map (juxt #(get % "name") identity)) tools)]
    (is (= #{"eval_clj" "runtime_status"} (set (keys by-name))))
    (is (= ["code"] (get-in by-name ["eval_clj" "inputSchema" "required"])))
    (is (= ["sci" "jvm"] (get-in by-name ["eval_clj" "inputSchema" "properties" "mode" "enum"])))
    (is (false? (get-in by-name ["eval_clj" "inputSchema" "additionalProperties"]))
        "a misspelled argument is refused, never ignored")))

(deftest ^{:seon/tasks #{'seon.mcp/eval-clj-advertises-no-timeout}}
  eval-clj-advertises-the-timeout-the-bridge-honours
  (let [timeout (get-in (into {} (map (juxt #(get % "name") identity)) (json/read-str (mcp/tools)))
                        ["eval_clj" "inputSchema" "properties" "timeout_ms"])]
    (is (= "integer" (get timeout "type")))
    (is (<= 120000 (get timeout "maximum")) "a platform check's wait fits under the bound"))
  (is (not (get (answer "eval_clj" {"code" "(+ 1 2)" "timeout_ms" 120000}) "isError"))
      "a call naming its timeout is not refused as an unknown argument"))

(deftest an-unknown-tool-answers-an-error-result
  (let [result (answer "get_value" {})]
    (is (true? (get result "isError")))
    (is (= 1 (count (get result "content"))))))

(deftest invalid-arguments-answer-an-error-result
  (is (true? (get (answer "eval_clj" {"code" "(+ 1 2)" "namepsace" "user"}) "isError")))
  (is (true? (get (answer "eval_clj" {}) "isError"))))

(deftest a-request-that-is-not-json-answers-an-error-result
  (is (true? (get (json/read-str (mcp/call "{not json")) "isError"))))

(deftest ^{:seon.test/subject "seon.mcp/sci-run"} an-sci-call-answers-what-the-agents-next-context-reads
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [result (answer "eval_clj" {"code" "(+ 1 2) (str \"a\" \"b\")" "agent" agent-id "cluster" running})
           run (latest-run-in (db/db connection) agent-id)]
       (is (false? (get result "isError")))
       (is (= (stored-responses connection agent-id run)
              (support/mcp-texts result))
           "one text per evaluation, exactly the agent's own response")))))

(deftest ^{:seon.test/subject "seon.mcp/jvm-run"} a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [jvm (answer "eval_clj" {"code" "(+ 1 2)" "agent" agent-id "cluster" running "mode" "jvm"})
           jvm-run (latest-run-in (db/db connection) agent-id)
           handle (admit/result-handle (id/evaluation jvm-run 0))
           dug (answer "eval_clj" {"code" (str "(inc " handle ")") "agent" agent-id "cluster" running})
           run (latest-run-in (db/db connection) agent-id)]
       (is (false? (get jvm "isError")))
       (is (false? (get dug "isError")))
       (is (= 4 (:seon.repl/value (support/reply (first (stored-responses connection agent-id run)))))
           "the JVM value is bound under its handle in the agent's own context")))))

(defn- outside
  "`f`'s value, called on a fresh platform thread so no custody is conveyed."
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value]] :seon.schema/value]}
  [f]
  (let [task (java.util.concurrent.FutureTask. ^java.util.concurrent.Callable f)]
    (.start (Thread. task))
    (.get task 60 java.util.concurrent.TimeUnit/SECONDS)))

(deftest a-fresh-request-inherits-one-admission
  (support/with-running-agent
   (fn [running _ connection]
     (let [instance (get @running-instances running)
           handle (:seon.turn.loop/cluster instance)
           store (:seon.store/store instance)
           agents (vec (repeatedly 4 #(str "fresh-cost-" (id/id))))]
       (try
         (is (nil? (outside #(host/register! connection (first agents) (keyword (str "fresh-home-" (id/id)))
                                             'user (:seon.cluster/name handle)))))
         (let [measurements
               (mapv (fn [agent-id]
                       (outside
                        #(profile/measure
                          (fn [] (#'mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/agent agent-id
                                                 :seon.mcp/cluster running :seon.mcp/mode "sci"})))))
                     agents)
               measured (subvec measurements 1)]
           (is (false? (second (:seon.profile/value (first measurements))))
               "a committed admission with no branch is completed by the request")
           (is (< (nth (sort (map :seon.profile/ms measured)) 1) 150)
               "a fresh request avoids the duplicate admission cost")
           (doseq [m measured]
             (is (false? (second (:seon.profile/value m))))
             (is (= 3 (profile/calls-of m 'seon.db/transact-call)))
             (is (= 2 (profile/calls-of m 'seon.cluster.registry/branch!)))))
         (let [agent-id (peek agents)
               branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id agent-id]))]
           (outside #(host/stop! (host/hosted handle branch)))
           (let [m (outside #(profile/measure
                              (fn [] (#'mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/agent agent-id
                                                     :seon.mcp/cluster running :seon.mcp/mode "sci"}))))]
             (is (false? (second (:seon.profile/value m))))
             (is (= 1 (profile/calls-of m 'seon.db/transact-call))
                 "rehosting writes only the evaluation's recording")))
         (finally
           (doseq [agent-id agents
                   :let [branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch]
                                                           [:seon.agent/id agent-id]))]
                   :when branch]
             (outside
              #(do (some-> (host/hosted handle branch) host/retire!)
                   (registry/retire-branch! {:seon.store/store store :seon.store/branch branch}))))))))))

(defn- stored-origins
  "Each stored evaluation of `agent-id` as `[source origin]`, origin nil when
  absent, plus the evaluations that carry the retired author."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:map [:origins [:set [:tuple :string [:maybe :keyword]]]] [:authored :int]]]}
  [database agent-id]
  (let [forms (db/q '[:find ?form ?source :in $ ?agent-id
                      :where [?agent :seon.agent/id ?agent-id] [?run :seon.turn/agent ?agent]
                      [?form :seon.cluster.eval/run ?run] [?form :seon.cluster.eval/source ?source]]
                    database agent-id)]
    {:origins (set (map (fn [[form source]]
                          [(str/trim source) (db/q '[:find ?origin . :in $ ?form
                                          :where [?form :seon.cluster.eval/origin ?origin]]
                                        database form)])
                        forms))
     :authored (count (filter (fn [[form _]]
                                (db/q '[:find ?author . :in $ ?form
                                        :where [?form :seon.cluster.eval/author ?author]]
                                      database form))
                              forms))}))

(deftest ^{:seon.test/subject "seon.turn/receipt-row"}
  entrant-origin-is-recorded-on-each-evaluation
  (support/with-running-agent
   (fn [running agent-id connection]
     (answer "eval_clj" {"code" "(+ 1 1)" "agent" agent-id "cluster" running})
     (answer "eval_clj" {"code" "(+ 1 2)" "agent" agent-id "cluster" running "mode" "jvm"})
     (let [cluster (:seon.turn.loop/cluster (get @running-instances running))
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           ledger (atom [])]
       (support/with-provider
        ledger (support/reply-queue ["```clojure\n(+ 40 2)\n```"])
        (fn [server]
          (answer "eval_clj" {"code" (str "(my.agent/settings! "
                                          (id/printed (merge (support/provider-config server)
                                                             {:seon.config.ai/no-provider false
                                                              :seon.config.run/max-episode-runs 1}))
                                          ")")
                              "agent" agent-id "cluster" running})
          (support/assign-task! connection agent-id)
          (outside #(drive/inbound! connection cluster-name (:seon.db.process/id cluster) agent-id "Wake."))
          ;; the member's wake routing is not armed: drive the agent's passes as its proc would
          (loop [passes 0]
            (let [now (java.util.Date.)
                  work (turn/next-agent-work @connection {:seon.agent/id agent-id
                                                          :seon.db.process/id (:seon.db.process/id cluster)
                                                          :seon.turn.work/now now})]
              (when (and work (< passes 4))
                (turn/turn {:seon.turn.loop/cluster cluster :seon.turn.work/next work} now)
                (recur (inc passes)))))))
       (let [{:keys [origins authored]} (stored-origins (db/db connection) agent-id)]
         (is (contains? origins ["(+ 1 1)" :outside]) "an MCP SCI form is recorded by the outside entrant")
         (is (contains? origins ["(+ 1 2)" :outside]) "an MCP JVM form is recorded by the outside entrant")
         (is (contains? origins ["(+ 40 2)" :model]) "the model's reply form is recorded by the model entrant")
         (is (zero? authored) "no evaluation carries the retired author"))))))

(defn- core-fault!
  "Record one core fault on `connection`'s branch through the production writer."
  {:malli/schema [:=> [:cat :seon.db/connection :string] :seon.fault/recorded]}
  [connection message]
  (fault/fault! (env/environment {:seon.boot/cluster-name (:seon.cluster/name (support/execution-handle nil))
                                  :seon.db/connection connection
                                  :seon.sci.admit/caps (config/result-caps config/defaults)})
                (ex-info message {})
                {:seon.error/layer ::reply :seon.error/operation `core-fault! :seon.db.process/id "mcp-entrance-test"}))

(deftest ^{:seon.test/long "Ten MCP SCI runs on one hosted agent, each one settlement; measured in the s5a-reply landing."
           :seon.test/long-ms 30000}
  an-sci-reply-is-what-the-run-saved
  ;; The reply is printed from the receipts the settlement saved, never by
  ;; reading the run back; it reads as the stored run reads.
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [agent-ns (str "my.agents." agent-id)]
       ;; each row: the code, the reply key its last evaluation is about, and
       ;; what happens before it runs
       (doseq [[label code about before]
               [["ordinary" "(+ 1 2)\n[nil false \"\"]" :seon.repl/value]
                ["def answers its Var" "(def held-value 7)" :seon.repl/value]
                ["declared renderer" "#'clojure.core/inc" :seon.repl/value]
                ["in-ns" (str "(in-ns 'scratch.held-reply)\n(in-ns '" agent-ns ")") :seon.repl/ns]
                ["set! print-length" "(set! *print-length* 2)\n(range 5)" :seon.repl/value]
                ["error" "(/ 1 0)\n:after" :seon.repl/value]
                ["wait disposition" "(my.turn/wait {:my.turn/note \"done\"})" :seon.repl/value]
                ["faulted" "(+ 2 2)" :seon.repl/errors #(core-fault! connection "raised before the faulted row")]
                ["defn" "(defn held-inc \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
                 :seon.repl/value]]]
         (testing label
           (when before (before))
           (let [texts (support/mcp-texts (answer "eval_clj" {"code" code "agent" agent-id "cluster" running}))
                 saved (stored-responses connection agent-id (latest-run-in (db/db connection) agent-id))]
             (is (seq saved) "the run saved its evaluations")
             (is (= saved (subvec texts 0 (min (count texts) (count saved))))
                 "each evaluation's reply is its saved response")
             (is (contains? (support/reply (nth texts (dec (count saved)))) about)
                 "the row's evaluation answers the key it is about"))))
       (testing "a form's set! of *print-length* is saved with its receipts"
         (answer "eval_clj" {"code" "(set! *print-length* 2)\n(range 5)" "agent" agent-id "cluster" running})
         (is (= #{2} (set (db/q '[:find [?length ...] :in $ ?id
                                  :where [?run :seon.turn/id ?id] [?receipt :seon.cluster.eval/run ?run]
                                  [?receipt :seon.eval/print-length ?length]]
                                (db/db connection) (latest-run-in (db/db connection) agent-id))))))
       (testing "a settlement the writer refused saved no receipt: each value still returns, the refusal beside it"
         ;; the form closes its own open run, so the settlement's receipts
         ;; refuse and the refusal's guarded receipts are skipped at the writer
         (let [code (str
                     ;; a durable write opens the run first
                     (pr-str (list 'seon.db/transact! (list 'quote {:tx-data [(support/task-row "abcdef123456" "opens the run")]})))
                     "\n(let [run (seon.db/q '[:find ?run . :in $ ?id :where [?a :seon.agent/id ?id]"
                     " [?run :seon.turn/agent ?a] (not [?run :seon.turn/closed-tx _])] (seon.db/db) \"" agent-id "\")]"
                     " (seon.db/transact! {:tx-data [{:db/id run :seon.turn/closed-tx \"datomic.tx\"}]}) run)")
               result (answer "eval_clj" {"code" code "agent" agent-id "cluster" running})
               saved (stored-responses connection agent-id (latest-run-in (db/db connection) agent-id))]
           (is (every? nil? saved) "the run stored no terminal receipt")
           (is (true? (get result "isError")))
           (let [replies (map support/reply (support/mcp-texts result))
                 values (filter :seon.repl/result replies)]
             (is (= 2 (count values)) "the path is total: both forms' values return")
             (is (every? (fn [value] (some #(= :seon.db.write/error (:seon.error/type %)) (:seon.repl/diagnostics value)))
                         values)
                 "each value carries the refused recording beside it")
             (is (some #(= :seon.db.write/validation-refusal (:seon.error/type %)) replies)
                 "and the writer's refusal is answered as data"))))))))

(deftest a-reply-keeps-its-duration-after-an-earlier-run
  ;; A second run's reply carries its time as the first one's does, and it is what the agent's next context reads.
  ;; (Its JVM-mode form went stale when host evaluation became root's alone, `seon.turn.batch/jvm-evaluate`.)
  (support/with-running-agent
   (fn [running agent-id connection]
     (answer "eval_clj" {"code" "(+ 1 1)" "agent" agent-id "cluster" running})
     (let [result (answer "eval_clj" {"code" "(+ 1 2)" "agent" agent-id "cluster" running})
           text (get-in result ["content" 0 "text"])
           data (support/reply text)
           run (latest-run-in (db/db connection) agent-id)]
       (is (false? (get result "isError")))
       (is (= 3 (:seon.repl/value data)))
       (is (contains? data :seon.repl/ms)
           "a second run's reply carries its time, as the first one's does")
       (is (= :call (:seon.turn.work/situation (db/pull (db/db connection) [:seon.turn.work/situation] [:seon.turn/id run])))
           "the run is recorded with the call situation")
       (is (= [text] (stored-responses connection agent-id run))
           "the reply is what the agent's next context reads")))))

(deftest only-a-run-that-changed-loaded-code-signals-pages
  (let [signal (fn [body]
                 (let [channel (async/chan 1)
                       mark (cluster/loaded-code-mark)]
                   (body)
                   (#'mcp/pages-changed! mark [channel])
                   (some? (async/poll! channel))))
        probe (symbol (str "signal-probe-" (id/id)))]
    (try
      (is (false? (signal #(+ 1 2))) "a run that changes no code keeps cached pages")
      (is (true? (signal #(binding [*ns* (the-ns 'seon.mcp-entrance-test)] (eval (list 'def probe 1)))))
          "a new Var re-renders pages")
      (finally (ns-unmap 'seon.mcp-entrance-test probe)))))

(deftest ^{:seon.test/long "One hosted agent's first MCP runs and its own test request: hosting a new branch, a cold checkpoint and request world, 3.0-6.5 s measured on default (2026-09-28)."
           :seon.test/long-ms 30000}
  an-agent-runs-its-own-test-on-its-own-branch
  ;; Before 06fd3a4d5, every such request refused: its member branched in the
  ;; agent's file store, not the request's overlay that holds the commit.
  (support/with-running-agent
   (fn [running _ connection]
     (let [agent-id (str "mcp-own-tests-" (id/id))
           namespace-name (symbol (str "my.own" (id/id)))
           test-symbol (symbol (str namespace-name) "twice-doubles")
           evaluate! #(answer "eval_clj" {"code" % "agent" agent-id "cluster" running})]
       (try
       (evaluate! (str "(ns " namespace-name ")"
                  " (defn twice \"Doubles x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))"
                  " (clojure.test/deftest twice-doubles (clojure.test/is (= 4 (twice 2))))"))
       (evaluate! (str "(my.test/run-owned {:seon.ns/name '" namespace-name "})"))
       (let [branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id agent-id]))
             recorded (db/q '[:find ?pass ?fail ?error :in $ ?symbol
                              :where [?member :seon.test.member/symbol ?symbol]
                                     [?member :seon.test.member/pass-count ?pass]
                                     [?member :seon.test.member/fail-count ?fail]
                                     [?member :seon.test.member/error-count ?error]]
                            (db/branch-database (db/db connection) branch) test-symbol)]
         (is (not= (get-in (db/db connection) [:config :branch]) branch) "the agent is hosted on its own branch")
         (is (= #{[1 0 0]} recorded) "its test ran green there and is recorded on its branch"))
       (finally
         (when-let [branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id agent-id]))]
           (some-> (host/hosted (get-in @running-instances [running :seon.turn.loop/cluster]) branch) host/retire!))))))))

(deftest a-retired-agent-refuses-by-name
  (support/with-running-agent
   (fn [running agent-id connection]
     (support/transacted! connection [[:db/add [:seon.agent/id agent-id] :seon.agent/admission-closed-tx "datomic.tx"]])
     (is (true? (get (answer "eval_clj" {"code" "(+ 1 2)" "agent" agent-id "cluster" running}) "isError"))))))

(deftest a-cluster-still-starting-or-stopping-refuses-by-name
  ;; During a restart the registry holds the start's partial instance (its
  ;; first layers, no routing yet): an eval answered a bare
  ;; NullPointerException "Cannot invoke Future.get() because fut is null".
  ;; It refuses, saying the cluster is not ready.
  (let [starting {"c" {:seon.boot/layers [{:seon.boot/layer :seon.store/store :seon.boot/at-ms 3}]}}
        stopping {"c" {:seon.boot/ready-ms 10 :seon.boot/stopping true}}]
    (is (= :seon.boot/ready-ms (:seon.error/member (#'mcp/instance starting "c")))
        "a starting instance is refused as not ready")
    (is (= :seon.boot/ready-ms (:seon.error/member (#'mcp/instance stopping "c")))
        "a stopping instance is refused as not ready")
    (is (= "c" (:seon.mcp/cluster (#'mcp/instance {"c" {:seon.boot/ready-ms 10}} "c")))
        "a ready instance is the one named")))

(deftest ^{:seon.test/subject "seon.mcp/sci-run"} a-one-defn-run-settles-in-proportion-to-the-defn
  ;; One contracted defn settled 3,522 datoms: 3,307 were read evidence, as
  ;; showing the Var read its reaching tests through
  ;; `seon.fn/declared-callers` one declaring row at a time, and ~100 its
  ;; executed set; neither has a consumer for a form that binds a Var (the
  ;; pre-turn refresh re-runs only read-only evaluations). The per-attribute
  ;; census on default (2026-09-28) leaves 62-89 datoms.
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [namespace-name (symbol (str "settle.probe" (id/id)))
           result (answer "eval_clj" {"code" (str "(ns " namespace-name ")"
                                                  " (defn one {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
                                      "agent" agent-id "cluster" running})
           database (db/db connection)
           run (latest-run-in database agent-id)
           [evaluation tx] (first (db/q '[:find ?evaluation ?tx :in $ ?run
                                          :where [?turn :seon.turn/id ?run]
                                          [?evaluation :seon.cluster.eval/run ?turn]
                                          [?evaluation :seon.cluster.eval/ordinal 1 ?tx]]
                                        database run))
           stored (db/pull database [:seon.cluster.eval/executed] evaluation)
           settled (count (filter #(= tx (:tx %)) (db/datoms (db/since database (dec tx)) :eavt)))]
       (is (false? (get result "isError")))
       (is (empty? (:seon.cluster.eval/executed stored)) "a form stores no executed set")
       (is (< settled 110) "the settlement is proportional to one ns form and one defn")))))

(deftest ^{:seon/tasks #{'seon.db/a-contract-property-with-a-qualified-key-is-refused-by-its-digest}}
  a-defn-whose-contract-has-a-qualified-property-key-is-admitted
  ;; Chain experiment 4: a Seon agent copied a shipped contract shape
  ;; ([:any {:gen/elements ...}]) and the writer refused its own REPL's entity.
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [namespace-name (symbol (str "qualified.property" (id/id)))
           subject (symbol (str namespace-name) "probe")
           result (answer "eval_clj" {"code" (str "(ns " namespace-name ")"
                                                  " (defn probe {:malli/schema [:=> [:cat [:int {:gen/elements [1 2]}]] :boolean]} [x] (some? x))")
                                      "agent" agent-id "cluster" running})]
       (is (false? (get result "isError")))
       (is (some? (db/q '[:find ?e . :in $ ?s :where [?e :seon.fn/sym ?s]] (db/db connection) subject))
           "the defn is a program entity")))))

(deftest ^{:seon/tasks #{'seon.schema/a-fn-schema-over-a-branch-predicate-is-refused}}
  a-fn-schema-over-a-predicate-the-agent-defined-is-registered
  ;; Chain experiment 4: an agent could not give its new descriptor key a
  ;; predicate schema; malli-form? resolves the predicate as a host Var.
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [namespace-name (str "branch.predicate" (id/id))
           schema-key (keyword namespace-name "thing")
           _ (answer "eval_clj" {"code" (str "(ns " namespace-name ")"
                                             " (defn thing? {:malli/schema [:=> [:cat :int] :boolean]} [x] (pos? x))"
                                             " (seon.schema/register! " schema-key " '[:fn " namespace-name "/thing?])")
                                 "agent" agent-id "cluster" running})]
       (is (some? (db/q '[:find ?e . :in $ ?k :where [?e :seon.schema/key ?k]] (db/db connection) schema-key))
           "the schema is registered on the agent's branch")))))

(deftest a-thrown-call-carries-its-whole-request
  ;; The value printer shows the request; nothing before it clips the text.
  (let [request (json/write-str {"name" "eval_clj" "arguments" {"code" (apply str (repeat 500 "(+ 1 2) "))}})
        failure (mcp/call-failure request (ex-info "boom" {}))]
    (is (= request (:seon.mcp/request failure)))
    (is (= 'seon.mcp/call (:seon.error/operation failure)))))

(deftest ^{:seon.test/subject "seon.test/reaching"} a-test-written-at-the-repl-reaches-the-function-it-calls
  ;; A deftest an agent writes at its REPL carries the call edges a
  ;; file-loaded one does: after the agent changes the callee, its caller's
  ;; test is the callee's reaching test.
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [namespace-name (symbol (str "reach.probe" (id/id)))
           callee (symbol (str namespace-name) "callee")
           evaluate #(answer "eval_clj" {"code" % "agent" agent-id "cluster" running})
           replies [(evaluate (str "(ns " namespace-name ")"
                               " (defn callee {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))"
                               " (defn caller {:malli/schema [:=> [:cat :int] :int]} [x] (callee x))"
                               " (clojure.test/deftest caller-check (clojure.test/is (= 3 (caller 2))))"))
                    (evaluate "(defn callee {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")]]
       (is (every? #(false? (get % "isError")) replies))
       (is (= [(symbol (str namespace-name) "caller-check")]
              (seon-test/reaching {:seon.db/db (db/db connection) :seon.test/changed [callee]})))))))
