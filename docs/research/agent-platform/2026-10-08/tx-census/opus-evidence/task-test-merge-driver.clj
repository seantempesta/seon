(do
  (require 'seon.mcp 'clojure.data.json)
  (let [agent "census-merge"
        call (fn [who code & [ns]]
               (let [reply (clojure.data.json/read-str
                            (seon.mcp/call (clojure.data.json/write-str
                                            {"name" "eval_clj"
                                             "arguments" (cond-> {"agent" who "cluster" "default" "code" code}
                                                           ns (assoc "namespace" ns))})))]
                 (mapv #(get % "text") (get reply "content"))))
        step (fn [label who code & [ns]]
               (tx-census.probe/start!)
               (let [t0 (System/nanoTime) shown (call who code ns) ms (/ (- (System/nanoTime) t0) 1e6)]
                 {:label label :wall-ms ms :shown (subs (apply str shown) 0 (min 600 (count (apply str shown))))
                  :census (tx-census.probe/stop!)}))
        s1 (step :agent-first-pass agent "(+ 1 2)" "census.m")
        s2 (step :task-create agent "(:seon.task/id (seon.task/create! '{:seon.task/ns census.m :seon.task/name \"square\" :seon.task/kind :function :seon.task/intent \"Square.\"}))" "census.m")
        task-id (let [db (datahike.api/db (get (tx-census.probe/conns) :cluster-default))]
                  (datahike.api/q '[:find ?id . :where [?t :seon.task/name "square"] [?t :seon.task/id ?id]] db))
        branch (let [db (datahike.api/db (get (tx-census.probe/conns) :cluster-default))]
                 (datahike.api/q '[:find ?b . :where [?a :seon.agent/id "census-merge"] [?a :seon.agent/branch ?b]] db))
        s3 (step :task-assign "root" (str "(seon.task/assign! {:seon.task/assigned \"" task-id "\" :seon.task/agent [:seon.agent/id \"census-merge\"] :seon.task/branch " branch "})"))
        s4 (step :defn agent "(defn square \"Square x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))" "census.m")
        s5 (step :deftest agent "(clojure.test/deftest ^{:seon/tasks #{'census.m/square}} square-test (clojure.test/is (= 9 (square 3))))" "census.m")
        s6 (step :check-cold agent "(select-keys (my.test/check {:seon.test/changed ['census.m/square]}) [:seon.test/passed?])" "census.m")
        s7 (step :check-warm agent "(select-keys (my.test/check {:seon.test/changed ['census.m/square]}) [:seon.test/passed?])" "census.m")
        s8 (step :propose-merge agent "(select-keys (my.program/propose-merge! {}) [:seon.task/merge :seon.task/refused])" "census.m")
        merge-id (let [db (datahike.api/db (get (tx-census.probe/conns) :cluster-default))]
                   (datahike.api/q '[:find ?id . :where [?t :seon.task/merge ?id]] db))
        s9 (step :prepare-merge "root" (str "(select-keys (my.program/prepare-merge! {:seon.task/merge \"" merge-id "\"}) [:seon.test/passed? :seon.error/message])"))
        s10 (step :accept-merge "root" (str "(my.program/accept-merge! {:seon.task/merge \"" merge-id "\"})"))]
    {:task-id task-id :branch branch :merge-id merge-id :steps [s1 s2 s3 s4 s5 s6 s7 s8 s9 s10]}))
