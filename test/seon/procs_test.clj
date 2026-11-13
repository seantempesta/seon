(ns seon.procs-test
  "Every proc's step-fn, called as a plain function (no graph, no Flow), checked against
  its `:malli/schema` (seon.step/step-schema) and against what each arity must do.
  Run: bin/test seon.procs-test (proto/flow f2-final test/proto/procs_test.clj)"
  (:require [seon.lib] [seon.test-store :as ts] [clojure.test :refer [deftest is testing run-tests]]
            [malli.core :as m] [seon.contract]
            [clojure.core.async :as a] [jolt.async.remote :as remote] [konserve.jolt.fasl :as codec]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.step :as step] [seon.db :as pdb] [seon.sock :as sock]
            [seon.writer :as writer] [seon.reconcile :as reconcile] [seon.fork-server :as server]
            [seon.agent :as agent] [seon.agent-proc :as ap] [seon.fx] [seon.system :as root] [seon.problems :as problems] [seon.repl :as repl] [seon.render.proc] [seon.web]
            [taoensso.trove :as trove] [my.test :as mt]))

(trove/set-log-fn! nil)

(def ^:private describe-ok? (m/validator (seon.contract/schema :seon.step/describe)))
(def ^:private step-out-ok? (m/validator (seon.contract/schema [:tuple :map :seon.step/outputs])))

(defn- arity-0 [v] (let [d (v)] (is (describe-ok? d) (str v " describe")) d))
(defn- step [v st in msg] (let [r (v st in msg)] (is (step-out-ok? r) (str v " transform " in)) r))

;; Estimate: a few pure step calls ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} every-step-fn-carries-the-contract
  (doseq [v [#'writer/writer-proc #'reconcile/reconciler-proc #'server/fork-server-proc #'ap/agent-proc #'root/api-proc
             #'server/server-step #'repl/agent-step]]
    (is (= :seon.step/step-fn (:malli/schema (meta v))) (str v))
    (arity-0 v)))

;; Estimate: 1 test-branch! x 1M; 5 step-fn calls x 0.1M (a transact or a commit read in each) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} writer
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        t0 (:max-tx @conn)]
    (testing "a main transaction: committed once, its report to the reconciler, its commit to the fork server, its answer by dest"
      (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a" :note/agent :t}] :dest {:rid 1}})]
        (is (< t0 (:max-tx @conn)) "main's t moves past its last")
        (is (= [:tx] (map :op (:to-fork-server outs))))
        (is (= [(:max-tx @conn)] (map :t (:main outs))))
        (is (= {:rid 1} (:dest (first (:to-api outs)))))
        (is (true? (:ok (:r (first (:to-api outs))))))
        (testing "group commit: three queued transactions, one step, one commit"
          (a/>!! in {:op :tx :branch :db :tx-data [{:note/text "c" :note/agent :t}] :dest {:rid 3}})
          (a/>!! in {:op :tx :branch :db :tx-data [{:no/such 1}] :dest {:rid 4}})
          (let [[st2 outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "b" :note/agent :t}] :dest {:rid 2}})
                answers (into {} (keep (fn [x] (when-let [r (get-in x [:dest :rid])] [r (:r x)]))) (:to-api outs))]
            (is (= 1 (- (:commits st2) (:commits st))))
            (is (every? :ok (map answers [2 3])))
            (is (seon.contract/error? (answers 4)) "a refused transaction answers its error, the others commit")))))
    (testing "a closed in-port: drained"
      (let [[_ outs] (step #'writer/writer-proc st :in nil)]
        (is (= :drained (:op (first (:to-api outs)))))))
    (testing "the stop transition releases the branch connections"
      (is (map? (#'writer/writer-proc st ::flow/stop))))))))

;; Estimate: 1 test-branch! x 1M; 2 step-fn calls x 0.1M (a transact or a commit read in each) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-refused-op-keeps-the-batch-committed-before-it
  ;; docs/storage.md#durability-is-continuous-group-commit: an op other than :tx commits the batch first; an op that then
  ;; throws (an interop edge) is answered as its error, and the writer keeps the state after that commit. Red before: one
  ;; catch around both handed back the state from before the commit (batch 12: with writes then held ahead, the writer and
  ;; Datahike fell apart; here the write's commit is lost from the writer's count)
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        _ (a/>!! in {:op :clone-branch :from :no-such-branch :branch (pdb/on-main (:branch cfg) :x1) :dest {:rid 2}})
        [st2 outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a" :note/agent :t}] :dest {:rid 1}})
        answers (into {} (keep (fn [x] (when-let [r (get-in x [:dest :rid])] [r (:r x)]))) (:to-api outs))]
    (is (:ok (answers 1)) "the write is answered, stored")
    (is (seon.contract/error? (answers 2)) "the op that throws is answered as its error")
    (is (= 1 (- (:commits st2) (:commits st))) "and the writer counts the commit made before the op")
    (is (= 1 (count (:main outs))) "whose notice went out")))))

;; Estimate: 1 test-branch! x 1M; 2 step-fn calls x 0.1M (a transact or a commit read in each) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} the-writers-transactions-name-their-runtime
  ;; main's transactions through the writer's group
  ;; commit carry the runtime that made them, and so does the stored commit the fork server's replica reads
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a" :note/agent :t}] :dest {:rid 1}})
        fp (jolt.host/runtime-fingerprint)]
    (is (= fp (:seon.tx/runtime (d/entity @conn (:max-tx @conn)))) "main's transaction names it")
    (let [m (first (:to-fork-server outs))]
      (is (= fp (:seon.tx/runtime (d/entity (d/commit-as-db conn (:commit m)) (:t m)))) "and so does the stored commit the replica reads"))))))

;; Estimate: 1 test-branch! 1M + 1 branch, connect and redefining transact 0.5M + 1 :octopus-merge step ~2M + the query and checks ~0.1M ~ 3.6M, about 4e6 ~ 4,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-writers-merge-names-its-runtime
  ;; a branch merged into main through the writer
  ;; (:octopus-merge, d/merge-db) is a transaction like any other: it names the runtime that made it
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        b (pdb/on-main (:branch cfg) :a1)
        _ (d/branch! conn (:branch cfg) b)
        bc (pdb/connect (assoc cfg :branch b))
        [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f2] [?v :var/form ?f] [?f :form/ns ?n]] @bc)
        _ (d/transact bc [{:db/id "f2'" :form/id "app.m0/f2-redefined" :form/ns nsx :form/order 100 :form/defines [v]
                           :form/src "(defn f2 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 200))"}
                          [:db/add v :var/form "f2'"]])
        _ (d/release bc)
        t0 (:max-tx @conn)
        [_ outs] (step #'writer/writer-proc st :in {:op :octopus-merge :branches [b] :dest {:rid 1}})]
    (is (:merged? (first (:results (:r (first (filter #(= {:rid 1} (:dest %)) (:to-api outs))))))) "the branch merges")
    (is (< t0 (:max-tx @conn)))
    (is (= (jolt.host/runtime-fingerprint) (:seon.tx/runtime (d/entity @conn (:max-tx @conn)))) "and its merge names the runtime")))))

;; Estimate: 1 test-branch! 1M + 3 writer :tx steps (0.2M each, one a :flush? batch) + 1 branch, connect, transact 0.4M + 1 :octopus-merge step (~2M) + the writer's start ~0.3M + 4 queries ~0.4M ~ 4.7M, about 5e6 ~ 5,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 5000000}} the-writer-marks-the-commit-that-moves-mains-content
  ;; docs/storage.md#mains-content-mark: every commit that moves main's content carries a new content mark, in that same
  ;; commit (what an agent reads from the store to skip its diffs of main), and a commit of records leaves it: a write, a
  ;; :flush? batch, an octopus merge, and the writer's start. Red before: no commit carried one
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        mark (fn [db] (:seon.system/content-t (d/entity db [:seon.system/id :root])))
        at (fn [outs] (d/commit-as-db conn (:commit (last (:main outs)))))
        t0 (:max-tx @conn)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        m0 (mark @conn)
        [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f2] [?v :var/form ?f] [?f :form/ns ?n]] @conn)
        redefine (fn [id] [{:db/id "f" :form/id id :form/ns nsx :form/order 100 :form/defines [v]
                            :form/src (str "(defn f2 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x " (count id) "))")}
                           [:db/add v :var/form "f"]])]
    (is (< t0 m0) "the writer's start moves it past what a boot wrote")
    (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a" :note/agent :t}] :dest {:rid 1}})]
      (is (= m0 (mark (at outs))) "a commit of records leaves it")
      (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data (redefine "app.m0/f2-a") :dest {:rid 2}})
            m1 (mark (at outs))]
        (is (< m0 m1) "a definition moves it, in its own stored commit")
        (is (some (fn [[_ _ a]] (= :seon.system/content-t a)) (:ops (last (:main outs)))) "and the notice's ops carry it")
        (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :flush? true :tx-data (redefine "app.m0/f2-b") :dest {:rid 3}})
              m2 (mark (at outs))]
          (is (< m1 m2) "a :flush? batch's definition moves it")
          (let [b (pdb/on-main (:branch cfg) :a1)
                _ (d/branch! conn (:branch cfg) b)
                bc (pdb/connect (assoc cfg :branch b))
                _ (d/transact bc (redefine "app.m0/f2-c"))
                _ (d/release bc)
                [_ outs] (step #'writer/writer-proc st :in {:op :octopus-merge :branches [b] :dest {:rid 4}})]
            (is (:merged? (first (:results (:r (first (filter #(= {:rid 4} (:dest %)) (:to-api outs))))))) "the branch merges")
            (is (< m2 (mark (at outs))) "and the merge moves it in its commit")))))))))

;; Estimate: 1 test-branch! 1M + 1 branch, connect and 1 redefining transact 0.4M + 1 :merge-candidate step (the octopus merge's files and texts, a dry run, ~2M) + the query and checks ~0.2M ~ 3.6M, about 4e6 ~ 4,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-merge-candidate-is-what-the-merge-writes
  ;; the merge gate's checkout takes the writer's :merge-candidate: the files the octopus merge would write, with their
  ;; text, its changed schema entries' resources among them (md-scan 29: the gate wrote the changed namespace's file
  ;; but not :seon.ref/block's resource, so the checkout's new seon.ref/scan met its old schema and the seed threw)
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        b (pdb/on-main (:branch cfg) :a1)
        _ (d/branch! conn (:branch cfg) b)
        bc (pdb/connect (assoc cfg :branch b))
        [v nsx] (d/q '[:find [?v ?n] :where [?v :var/name app.m0/f2] [?v :var/form ?f] [?f :form/ns ?n]] @bc)
        _ (d/transact bc [{:db/id "f2'" :form/id "app.m0/f2-redefined" :form/ns nsx :form/order 100 :form/defines [v]
                           :form/src "(defn f2 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 200))"}
                          [:db/add v :var/form "f2'"]
                          {:schema/key :app.m0/thing :schema/form "[:map [:x :int]]" :schema/file "resources/seon/schemas/app.m0.edn"}])
        _ (d/release bc)
        t0 (:max-tx @conn)
        [_ outs] (step #'writer/writer-proc st :in {:op :merge-candidate :branch b :dest {:rid 1}})
        r (:r (first (filter #(= {:rid 1} (:dest %)) (:to-api outs))))]
    (is (:ok r) (pr-str r))
    (is (some #{"resources/seon/schemas/app.m0.edn"} (:files r)) (str "the changed schema entry's resource: " (pr-str (:files r))))
    (is (re-find #":app.m0/thing" (str (get (:text r) "resources/seon/schemas/app.m0.edn"))) "with its text as the merge holds it")
    (is (or (some #(re-find #"\.clj$" %) (:files r)) (contains? (:unfiled r) 'app.m0))
        (str "the changed namespace's own source file, or it named as unfiled (the schema resource alone is not it): " (pr-str (select-keys r [:files :unfiled]))))
    (is (= t0 (:max-tx @conn)) "and main is unchanged: a candidate is never committed")))))

;; Estimate: 1 test-branch! 1M + a 2,000-note transact in one writer step (2,000 datoms x 5k = 10M) + the replica's commit-as-db read 1M + vec of d/datoms :eavt of main and the replica (2 x ~4,000 datoms x 0.5k = 4M) ~ 16M, about 2e7 ~ 20,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 20000000}} the-fork-servers-replica-is-main-at-each-commit
  ;; the fork server's replica reads each main commit from the store (d/commit-as-db of the
  ;; commit the writer names), never replaying its datoms: replaying the boot's 42,942-datom
  ;; source import took 3.5 s and the first fork waited behind it (2026-10-23). After a large
  ;; commit the replica is main at that t exactly: the same commit, the same datoms
  (ts/test-branch! (fn [cfg conn]
    (let [in (a/chan 16)
          wst (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
          before @conn
          [_ outs] (step #'writer/writer-proc wst :in {:op :tx :branch :db :tx-data (vec (for [i (range 2000)] {:note/text (str "n" i) :note/agent :t})) :dest {:rid 1}})
          m (first (filter #(= :tx (:op %)) (:to-fork-server outs)))
          fst (#'server/server-step {:db before :cfg cfg :store conn :conn nil})
          [fst' fouts] (step #'server/server-step fst :cmd m)
          replica (:db fst')]
      (is (= (d/commit-id @conn) (:commit m)) "the writer names the commit it made")
      (is (= (:max-tx @conn) (:max-tx replica) (:t m)) "the replica is main at t")
      (is (= (d/commit-id @conn) (d/commit-id replica)))
      (is (= (vec (d/datoms @conn :eavt)) (vec (d/datoms replica :eavt))) "with main's datoms, every one")
      (is (= 0 (:mismatch fst')))
      (is (= [:applied] (mapv :ev (:ev fouts))))))))

;; Estimate: 1 test-branch! x 1M; 2 step-fn calls x 0.1M (a transact or a commit read in each); 1 transacts x 50k ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-fork-at-another-commit-is-recorded-misaligned
  ;; a new agent whose branch is not at the
  ;; commit :spawn named (or whose image is not at its t) says so in its hello, and root
  ;; records it as the agent's problem, :seon.agent/fork-misaligned
  (ts/test-branch! (fn [cfg conn]
    (let [at (d/commit-id @conn) t (:max-tx @conn)
          _ (d/transact conn [{:note/text "main moved on" :note/agent :t}])]
      (is (nil? (agent/misaligned (d/commit-id @conn) (:max-tx @conn) @conn @conn)) "aligned: nothing to say")
      (let [x (agent/misaligned at t @conn @conn)
            [r a'] (sock/socketpair)
            end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
            st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
            [_ outs] (step #'ap/agent-proc st :from {:op :hello :t t :start-ms 1.0 :mem-mb 1.0 :interrupt-ok true :misaligned x})
            problem (first (filter #(= :problem (:op %)) (:to-api outs)))]
        (is (= {:commit at :t t :branch-commit (d/commit-id @conn) :image-t (:max-tx @conn)} x) "a branch past the commit it was branched at")
        (is (= :seon.agent/fork-misaligned (get-in problem [:error :seon.error/type])) "is recorded as the agent's problem")
        (is (= at (get-in problem [:error :commit])))
        (remote/close! end) (sock/close! a'))))))

;; Estimate: 5 step-fn calls x 10k (pure state in, state out) ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} a-context-read-makes-its-agent-neither-busy-nor-idle
  ;; docs/server.md#ops: a read never writes (owner, 2026-11-10). A :context read is an eval of origin :context: in
  ;; flight, the agent is not made busy, and its answer reports the status it already had, so root's view of the agent
  ;; does not move and nothing is written to main for it (seon.system's record-agents writes only a change). An eval that
  ;; is work still makes it busy. Red before: every request sent {:status :busy}, and its answer :idle: two commits on
  ;; main for each read.
  (let [[r a'] (sock/socketpair)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        statuses (fn [outs] (keep #(when (= :status (:op %)) (get-in % [:set :status])) (:to-api outs)))]
    (try
      (let [[st outs] (step #'ap/agent-proc st :in {:op :req :rid 5 :kind :eval :msg {:op :eval :forms "(ctx)" :origin :context}})]
        (is (empty? (statuses outs)) "a read in flight reports no status")
        (is (contains? (:inflight st) 5) "and is in flight, answered when it ends")
        (let [[st outs] (step #'ap/agent-proc st :from {:op :reply :id 5 :response {:value 1} :t 1})]
          (is (= [:idle] (statuses outs)) "its answer reports the agent as it was: idle")
          (let [[st outs] (step #'ap/agent-proc st :in {:op :req :rid 6 :kind :eval :msg {:op :eval :forms "(+ 1 2)"}})]
            (is (= [:busy] (statuses outs)) "an eval that is work makes it busy")
            (let [[_ outs] (step #'ap/agent-proc st :in {:op :req :rid 7 :kind :eval :msg {:op :eval :forms "(ctx)" :origin :context}})]
              (is (empty? (statuses outs)) "a read beside it changes nothing")))))
      (finally (remote/close! end) (sock/close! a')))))

;; Estimate: 4 step-fn calls x 10k (pure state in, state out) ~ 40,000 ticks
(deftest ^{:seon.test/estimate {:ticks 40000}} an-agent-that-reads-nothing-is-seen-as-a-stall
  ;; docs/processes.md#a-stalled-agent: root's frames to an agent never block its proc. An agent that stopped reading its
  ;; socket (an eval held in a blocking open(2)) left the proc's blocking put parked in its step, so it never took root's
  ;; :stall? and no stall was recorded (batch 16's problems step, 8.5 min). Here nothing ever takes from the end's :out:
  ;; each step returns, the frames wait in order, and the stall check answers. No load, no clock: red before, the first
  ;; step never returned (shown once under a bound outside this test).
  (let [out (a/chan)
        st (#'ap/agent-proc {:id :a1 :end {:out out :in (a/chan)} :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        [st _] (step #'ap/agent-proc st :in {:op :req :rid 5 :kind :eval :msg {:op :eval :forms "(open-a-fifo)"}})
        [st _] (step #'ap/agent-proc st :in {:op :req :rid 6 :kind :eval :msg {:op :eval :forms "(+ 1 2)"}})
        [_ outs] (step #'ap/agent-proc (assoc st :last-frame-ms 0) :in {:op :stall? :now-ms 10000 :stall-ms 1000})]
    (is (= [:stalled] (map :op (:to-api outs))) "the proc takes :stall? and answers the stall")
    (is (= [5 6] [(:id (a/poll! out)) (:id (a/poll! out))]) "and its frames wait for the agent, in order")))

;; Estimate: a few pure step calls ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} a-reply-the-wire-cannot-encode-answers-its-asker-and-the-end-serves-on
  ;; task jolt.async/a-value-a-remote-end-cannot-encode-answers-its-asker: root's end sent an agent
  ;; a reply holding a sorted map by a comparator of its own (a function: no stable encoding), and the writer pump closed the end, which
  ;; root read as the agent's death. The reply goes in the end's :encode-error, ap/unencodable: the
  ;; asker's :k gets the typed error, and the next frame follows on the same end.
  (let [[r a'] (sock/socketpair)
        codec' {:encode codec/encode :decode codec/decode}
        root-end (remote/open r {:codec codec' :pump :fiber :encode-error (partial ap/unencodable (a/chan 1))})
        agent-end (remote/open a' {:codec codec' :pump :fiber})
        take! #(a/<!! (:in agent-end))]   ; a closed end answers nil, so no wait outlives it
    (a/>!! (:out root-end) {:op :reply :k 7 :result (sorted-map-by compare :a 1)})
    (a/>!! (:out root-end) {:op :reply :k 8 :result {:ok true}})
    (let [x (take!) y (take!)]
      (is (= [:reply 7 :seon.agent/unencodable] [(:op x) (:k x) (get-in x [:result :seon.error/type])]) (pr-str x))
      (is (re-find #"cannot encode|no stable encoding" (get-in x [:result :seon.error/message])) (pr-str x))
      (is (= {:op :reply :k 8 :result {:ok true}} y) "the end carries the next frame"))
    (remote/close! root-end) (remote/close! agent-end)))

;; Estimate: 3 step-fn calls x 10k (pure state in, state out) ~ 30,000 ticks
(deftest ^{:seon.test/estimate {:ticks 30000}} a-request-the-wire-cannot-encode-answers-roots-asker-and-nothing-waits
  ;; the same class for a request of root's (an :id): root is its asker, so the agent proc answers
  ;; the rid it holds in flight with the typed error, from the frame the end's hook puts on its :in
  (let [[r a'] (sock/socketpair)
        codec' {:encode codec/encode :decode codec/decode}
        in (a/chan 4)
        end (remote/open r {:codec codec' :pump :fiber :in in :encode-error (partial ap/unencodable in)})
        agent-end (remote/open a' {:codec codec' :pump :fiber})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        [st _] (step #'ap/agent-proc st :in {:op :req :rid 5 :kind :eval :msg {:op :eval :forms "(+ 1 2)" :opts (sorted-map-by compare :a 1)}})
        frame (a/<!! in)
        [st' outs] (step #'ap/agent-proc st :from frame)
        answered (first (filter #(= {:rid 5} (:dest %)) (:to-api outs)))]
    (is (= {:op :unsendable :rid 5} (select-keys frame [:op :rid])) (pr-str frame))
    (is (= :seon.agent/unencodable (get-in answered [:r :seon.error/type])) (pr-str outs))
    (is (empty? (:inflight st')) "nothing is left in flight")
    (is (= :reply (:op (a/<!! (:in agent-end)))) "the agent is sent an answer no wait holds, and the end is open")
    (remote/close! end) (remote/close! agent-end)))

;; Estimate: 3 step-fn calls x 10k (pure state in, state out) ~ 30,000 ticks
(deftest ^{:seon.test/estimate {:ticks 30000}} an-agent-root-cannot-interrupt-is-a-problem-and-memory-rides-its-commits
  ;; shrink brief 3, rows 19 and 34: an agent whose boot check found root's interrupt cannot
  ;; reach it says so in its hello, and root records :seon.agent/interrupt-unreachable (the
  ;; answer nothing read before); its memory reaches root's view on its commits, never on each
  ;; eval reply
  (let [[r a'] (sock/socketpair)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        problems (fn [ok] (filter #(= :problem (:op %)) (:to-api (second (step #'ap/agent-proc st :from {:op :hello :t 1 :start-ms 1.0 :mem-mb 1.0 :interrupt-ok ok})))))
        sets (fn [m] (keep #(when (= :status (:op %)) (:set %)) (:to-api (second (step #'ap/agent-proc st :from m)))))]
    (is (= [:seon.agent/interrupt-unreachable] (map #(get-in % [:error :seon.error/type]) (problems false))))
    (is (empty? (problems true)) "a reachable agent records nothing")
    (is (= [{:mem-mb 12.5}] (sets {:op :committed :t 2 :txs 1 :mem-mb 12.5})) "a commit carries its memory to the view")
    (remote/close! end) (sock/close! a')))

;; Estimate: 1 test-branch! x 1M; 3 step-fn calls x 0.1M (a transact or a commit read in each) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-fork-names-the-commit-its-agent-starts-at
  ;; :spawn branches the agent from main's
  ;; head and asks the fork server, whose image holds main at that head (the FIFO), to fork it;
  ;; :fork names that commit and t, so the agent checks both (seon.agent/init!)
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        [st _] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a" :note/agent :t}] :dest {:rid 1}})
        b (pdb/on-main (:branch cfg) :a1)
        [_ outs] (step #'writer/writer-proc st :in {:op :spawn :agent :a1 :branch b :ns 0 :fd 0 :dest {:rid 2}})
        fork (first (filter #(= :fork (:op %)) (:to-fork-server outs)))]
    (is (= (d/commit-id @conn) (:commit fork)) "main's commit")
    (is (= (:max-tx @conn) (:t fork)) "main's t")
    (is (= (:commit fork) (d/commit-id (pdb/head conn b))) "which the agent's branch starts at")))))

;; Estimate: a store on disk of its own opened and seeded (2,000 datoms x 5k = 10M) + 3 writer steps over it 0.3M + seon.fx/read-source of the 70 files of src (2.2 MB, about 1M) ~ 11M, about 1e7 ~ 10,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-value-the-store-cannot-encode-is-refused-to-its-asker
  ;; land6 re-proof (e2e loop): a sorted map on root's source fact reached the store's codec at
  ;; commit (konserve.jolt.fasl has none for one by a comparator of its own: a function; one by the default stores) and threw out of the writer as a proc
  ;; defect, unanswered. The commit refusing is the asker's error: the transaction that carried
  ;; it is answered :seon.writer/refused, the others in its batch commit, and the writer goes on.
  ;; A store on disk of this test's own, the last resort: the in-memory test store has no codec
  (let [path (str (seon.config/checkout) "/target/procs-test-" (random-uuid))
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)]
   (try (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        source (fn [pins] {:seon.system/id :root :seon.system/source {:root "tree" :files 0 :forms 0 :ms 0.0 :pins pins}})]
    (a/>!! in {:op :tx :branch :db :tx-data [{:note/text "after" :note/agent :t}] :dest {:rid 2}})
    (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [(source (into (sorted-map-by compare) (for [i (range 40)] [(str "reference-code/m" i) (apply str (repeat 40 "a"))])))] :dest {:rid 1}})
          answers (into {} (keep (fn [x] (when-let [r (get-in x [:dest :rid])] [r (:r x)]))) (:to-api outs))]
      (is (= :seon.writer/refused (:seon.error/type (answers 1))) (pr-str (answers 1)))
      (is (true? (:ok (answers 2))) "the batch's other transaction commits")
      (testing "the writer goes on, and a plain map of pins (what the source read answers) reads back"
        (let [pins (:pins (seon.fx/read-source {:req {:root (seon.config/checkout) :dirs ["src"]}}))
              [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [(source pins)] :dest {:rid 3}})]
          (is (true? (:ok (:r (first (:to-api outs))))))
          (is (seq pins))
          (is (= pins (get-in (d/pull @conn [:seon.system/source] [:seon.system/id :root]) [:seon.system/source :pins])))))))
     (finally (d/release conn) (seon.lib/remove-tree! path)))))

;; Estimate: 1 test-branch! x 1M; 7 step-fn calls x 0.1M (a transact or a commit read in each) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} the-flush-is-continuous-group-commit
  ;; owner 2026-10-21 (git history, "Durability is batched everywhere", superseded
  ;; paragraph): no window. A write is answered when committed in memory; the flusher parks
  ;; until something is unflushed, syncs once for everything committed so far, and says what it
  ;; covered; commits during a sync share the next one; an idle system never syncs.
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? true :to-writer in :events (a/chan 16)})]
    (testing "the flusher syncs at once and tells the writer which commits it covered"
      (let [fl (#'writer/flusher-proc {:to-writer in :cfg cfg})
            [fl outs] (step #'writer/flusher-proc fl :in {:op :dirty :commits 3 :since 0})]
        (is (= {:to-writer [{:op :flushed :commits 3}]} outs))
        (is (= 1 (:flushes fl)))))
    (testing "a write is answered unflushed, and the writer tells the flusher what it committed"
      (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a" :note/agent :t}] :dest {:rid 1}})
            notice (first (:to-flusher outs))]
        (is (true? (:ok (:r (first (:to-api outs))))))
        (is (= {:op :dirty :commits (:commits st)} (select-keys notice [:op :commits])))
        (testing "the flush that covers it leaves the store clean"
          (let [[st2 _] (step #'writer/writer-proc st :in {:op :flushed :commits (:commits st)})]
            (is (false? (:dirty st2)))
            (is (= 1 (- (:flushes st2 0) (:flushes st 0))))))
        (testing "a commit after the flush began: still dirty, and the flusher is told again"
          (let [[st3 _] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "b" :note/agent :t}] :dest {:rid 2}})
                [st4 outs] (step #'writer/writer-proc st3 :in {:op :flushed :commits (:commits st)})]
            (is (true? (:dirty st4)))
            (is (= [{:op :dirty :commits (:commits st3)}] (mapv #(select-keys % [:op :commits]) (:to-flusher outs))))))))))))

;; Estimate: 1 test-branch! x 1M; 5 step-fn calls x 0.1M (a transact or a commit read in each) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} the-writer-answers-clean-once-the-flush-covers-its-commits
  ;; task seon.check/idle-waits-for-the-flush: a :clean request is held while the store is
  ;; dirty and answered by the step that takes the covering flush, after that flush's notice
  ;; to the api; a clean store answers at once
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? true :to-writer in :events (a/chan 16)})
        [st _] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a" :note/agent :t}] :dest {:rid 1}})
        [st outs] (step #'writer/writer-proc st :in {:op :clean :dest {:rid 2}})]
    (is (not-any? #(= {:rid 2} (:dest %)) (:to-api outs)) "dirty: held")
    (let [[st2 outs] (step #'writer/writer-proc st :in {:op :flushed :commits (:commits st)})
          api (:to-api outs)
          i-notice (first (keep-indexed #(when (= :writer (:op %2)) %1) api))
          i-answer (first (keep-indexed #(when (= {:rid 2} (:dest %2)) %1) api))]
      (is (some? i-answer) "the covering flush answers it")
      (is (< i-notice i-answer) "after the flush's notice to the api")
      (is (= (:commits st) (get-in api [i-answer :r :commits])))
      (is (empty? (:clean-waiters st2)))
      (let [[_ outs] (step #'writer/writer-proc st2 :in {:op :clean :dest {:rid 3}})]
        (is (some #(= {:rid 3} (:dest %)) (:to-api outs)) "clean: at once")))))))

(defn- notice
  "The writer's notice for conn's head, the commit after prev: {:t :prev :ops :commit :ns}, its :ns stamp 0 (data no step reads here)."
  [conn prev ops] {:t (:max-tx @conn) :prev prev :ops ops :commit (d/commit-id @conn) :ns 0})

;; Estimate: 1 test-branch! x 1M; 2 step-fn calls x 0.1M (a transact or a commit read in each) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} the-writers-main-notice-holds-no-db
  ;; a queued notice pins its own version of
  ;; the index (docs/storage.md#a-kept-db-value-is-a-reference), so the notice names the commit and its consumers make the db
  ;; when they take it
  (ts/test-branch! (fn [cfg conn]
    (let [in (a/chan 16)
          st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
          t0 (:max-tx @conn)
          _ (a/>!! in {:op :tx :branch :db :tx-data [{:note/text "c" :note/agent :t}] :dest {:rid 3}})
          [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "b" :note/agent :t}] :dest {:rid 2}})
          n (first (:main outs))]
      (is (= 1 (count (:main outs))) "a group commit is one stored commit: one notice")
      (is (not-any? #(some? (:max-tx %)) (vals n)) "no db value in the notice")
      (is (= #{:t :prev :ops :commit :ns} (set (keys n))))
      (is (= t0 (:prev n)))
      (is (= (:max-tx @conn) (:t n)))
      (is (= (:tx-data (first (:to-fork-server outs))) (:ops n)) "its ops are every report's ops, in order: the stored commit's")
      (is (= #{"b" "c"} (set (keep (fn [[_ _ a v]] (when (= :note/text a) v)) (:ops n)))) "the batch's two transactions are both in it")
      (is (= (d/commit-id @conn) (:commit n)))
      (is (= (:max-tx @conn) (:max-tx (d/commit-as-db conn (:commit n)))) "the consumer makes the db from it")))))

;; Estimate: 1 test-branch! x 1M; 3 step-fn calls x 0.1M (a transact or a commit read in each) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} reconciler
  (ts/test-branch! (fn [_ conn] (let [st (#'reconcile/reconciler-proc {:boot "b" :conn conn :provider {:kind :scripted :script 'seon.fixtures/reply}})]
    (testing "a main commit with no tasks: the reconciler reads it and starts nothing (quiescence: seon.reconcile-test)"
      (let [[st outs] (step #'reconcile/reconciler-proc st :main (notice conn nil []))]
        (is (= 1 (:seen st)))
        (is (= :main-seen-us (:k (first (:to-api outs)))))
        (is (empty? (:flights st)))))
    (testing "an answer nobody asked for changes nothing"
      (let [[st2 outs] (step #'reconcile/reconciler-proc st :answers {:tag 99 :r {:ok true}})]
        (is (= (:flights st) (:flights st2)))
        (is (empty? (:to-api outs)))))))))

;; Estimate: 1 test-branch! x 1M; 1 transacts x 50k ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-wait-on-main-is-answered-at-the-reconcilers-quiet
  ;; task seon.system/a-wait-on-main-ends-when-nothing-can-make-it-true: a wait on main (seon.system/
  ;; await-db, named) asks the reconciler on :judge for its first quiet at or after :after. Before,
  ;; only a commit making the wait's pred true ended it, and a quiet spell that went on wrote no
  ;; later report: agent_test's four await-quiet calls after :done hung (1h45m). The answer is the
  ;; reconciler's own state, never a clock
  (ts/test-branch! (fn [_ conn]
    (let [v #'reconcile/reconciler-proc
          ;; a commit touching nothing the reconciler watches (as root's own report is)
          other-commit! #(d/transact conn {:tx-data []})
          [st0 _] (step v (v {:boot "b" :conn conn :provider {:kind :scripted :script 'seon.fixtures/reply}}) :main (notice conn nil []))
          t (:t st0)
          ;; the writer's answer to the spell's report
          [st _] (step v st0 :answers {:tag (:report st0) :r {:ok true}})]
      (is (:quiet st0) "a commit with no tasks leaves the reconciler quiet")
      (testing "a new spell's wait is held until its report is committed, so the wait reads it"
        (let [p (promise) [st1 _] (step v st0 :judge {:op :quiet :after t :p p})]
          (is (= ::unanswered (deref p 0 ::unanswered)))
          (step v st1 :answers {:tag (:report st0) :r {:ok true}})
          (is (= {:quiet t} (deref p 0 ::unanswered)))))
      (testing "asked while quiet past its :after: answered at once, with the t it was quiet at"
        (let [p (promise) [st2 outs] (step v st :judge {:op :quiet :after t :p p})]
          (is (= {:quiet t} (deref p 0 ::unanswered)))
          (is (empty? (:waits st2)) "nothing held")
          (is (empty? (:to-api outs)) "a wait asks nothing of the api")))
      (testing "asked past what it has seen: held until that commit's pass, then answered"
        (let [p (promise) [st2 _] (step v st :judge {:op :quiet :after (inc t) :p p})
              _ (other-commit!)
              [st3 _] (step v st2 :main (notice conn t []))
              ;; the spell goes on, or (main relisted) a new one began: answered once its report is
              [st3 _] (if (:report st3) (step v st3 :answers {:tag (:report st3) :r {:ok true}}) [st3 nil])]
          (is (= 1 (count (:waits st2))) "held while the reconciler has not seen t+1")
          (is (= {:quiet (:t st3)} (deref p 0 ::unanswered)))
          (is (empty? (:waits st3)))))
      (testing "a judge handed it first ends the spell: the wait behind it is held"
        (let [p (promise)
              [st2 _] (step v st :judge {:op :judge :run "r-1" :task "a/b" :env {} :agent :agent-1})
              [st3 _] (step v st2 :judge {:op :quiet :after (:t st2) :p p})]
          (is (= ::unanswered (deref p 0 ::unanswered)))
          (testing "and a stop answers what is held"
            (v st3 ::flow/stop)
            (is (= {:stopped true} (deref p 0 ::unanswered))))))))))

;; Estimate: 1 test-branch! x 1M; 3 step-fn calls x 0.1M (a transact or a commit read in each); 1 transacts x 50k ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} an-agent-forked-from-a-program-that-did-not-load-is-told
  ;; docs/processes.md#an-agent-that-starts-without-its-program (owner, 2026-11-11: run, never silently): the fork server holds its program's
  ;; load errors while they stand, by namespace, and gives them to every agent it forks (fork-args' :program-errors), whose hello makes each a
  ;; recorded :seon.agent/load-failed problem of that agent. Red before: they went out once as an event, beside one fork, and were forgotten,
  ;; so the next agents started without their program and said nothing (an eval stopped at its third form)
  (let [stood #'server/stood
        e {:ns 'app.t :message "Unable to resolve symbol: deftest"}
        st (stood {} [e] #{'app.t})]
    (is (= [e] (:program-errors (server/fork-args st {:agent :a1} []))) "a fork after the failed load is told")
    (is (= [e] (:program-errors (server/fork-args st {:agent :a2} []))) "and so is the next one: forking forgets nothing")
    (is (= [] (:program-errors (server/fork-args (stood st nil #{'app.t}) {:agent :a3} []))) "a later load of that namespace that succeeds clears it")
    (is (= [e] (:program-errors (server/fork-args (stood st nil #{'app.other}) {:agent :a4} []))) "one of another namespace does not")
    (is (= [] (:program-errors (server/fork-args (stood st nil :all) {:agent :a5} []))) "and a whole load that loads it all replaces what stood")
    (testing "the agent's hello carries them, and each is a recorded problem of that agent"
      (let [ap0 (#'ap/agent-proc {:id :a1 :end {:out (a/chan 4) :in (a/chan)} :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
            [_ outs] (step #'ap/agent-proc ap0 :from {:op :hello :t 1 :start-ms 1.0 :mem-mb 1.0 :interrupt-ok true :load-errors [e]})
            pr (filter #(= :problem (:op %)) (:to-api outs))]
        (is (= [[:a1 :seon.agent/load-failed 'app.t]] (mapv (juxt :agent (comp :seon.error/type :error) (comp :ns :error)) pr)))))
    (testing "root answers its spawn with the typed error, and Seon tells the agent once, in a message"
      (ts/test-branch!
       (fn [_ conn]
         (let [fresh (fn [p] (-> (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                                                   :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
                                 (assoc :conn conn)
                                 (assoc-in [:view :agents :a1] {:status :spawning}) (assoc-in [:seon.system/spawning :a1] p)))
               p (promise) o (volatile! {})
               st' (#'root/set-agent (fresh p) o :a1 {:ready true :load-errors [e]})
               swp (:started-without-program (deref p 0 ::unanswered))
               tx (some #(when (= :tx (:op %)) (:tx-data %)) (:to-writer @o))
               msg (first tx)]
           (is (= :seon.agent/started-without-program (:seon.error/type swp)) (pr-str (deref p 0 ::unanswered)))
           (is (= [e] (:errors swp)) "naming every load error")
           (is (= :idle (get-in st' [:view :agents :a1 :status])) "the agent runs")
           (is (= {:seon.system/id :root} (:seon.message/from msg)) "and its first fact is a message from Seon to it")
           (is (= [[:seon.agent/key "a1"]] (:seon.message/to msg)))
           (is (clojure.string/includes? (:seon.message/body msg) "app.t") "naming what did not load")
           (testing "once per standing set of errors: a second hello that meets them, main holding the message, writes none"
             (d/transact conn (into [{:seon.agent/key "a1"}] tx))
             (let [p2 (promise) o2 (volatile! {})]
               (#'root/set-agent (fresh p2) o2 :a1 {:ready true :load-errors [e]})
               (is (= :seon.agent/started-without-program (:seon.error/type (:started-without-program (deref p2 0 ::unanswered)))) "its spawn still answers typed")
               (is (not-any? #(= :tx (:op %)) (:to-writer @o2)) "and no second message is written")))))))))

;; docs/references.md#an-entity-cites: an agent whose config cites a wiki section its store does not hold (a store older than its wiki) is
;; made citing the rest and says which are missing in its hello: each a recorded :seon.agent/cite-missing problem of that agent naming the
;; address, and its spawn's answer names them. Red before: the cite's lookup threw inside the fork server's step and no agent started
;; Estimate: 1 test-branch! x 1M; 3 step-fn calls x 0.1M (a transact or a commit read in each) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} an-agent-whose-store-lacks-a-cited-section-says-so-at-its-start
  (let [gone "docs/nowhere.md#never-written"]
    (testing "its hello carries the addresses, and each is a recorded problem of that agent"
      (let [ap0 (#'ap/agent-proc {:id :a1 :end {:out (a/chan 4) :in (a/chan)} :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
            [_ outs] (step #'ap/agent-proc ap0 :from {:op :hello :t 1 :start-ms 1.0 :mem-mb 1.0 :interrupt-ok true :seon.agent/missing-cites [gone]})
            pr (filter #(= :problem (:op %)) (:to-api outs))]
        (is (= [[:a1 :seon.agent/cite-missing gone]] (mapv (juxt :agent (comp :seon.error/type :error) (comp :seon.doc/address :error)) pr)))
        (is (clojure.string/includes? (:seon.error/message (:error (first pr))) "erases the store") "and says what a reindexing start does")
        (is (some #(and (map? %) (= [gone] (:seon.agent/missing-cites %))) (tree-seq coll? seq (:to-api outs))) "its status carries them to root")))
    (testing "root answers its spawn naming them, and the agent runs"
      (ts/test-branch!
       (fn [_ conn]
         (let [p (promise) o (volatile! {})
               st (-> (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                                        :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
                      (assoc :conn conn)
                      (assoc-in [:view :agents :a1] {:status :spawning}) (assoc-in [:seon.system/spawning :a1] p))
               st' (#'root/set-agent st o :a1 {:ready true :seon.agent/missing-cites [gone]})]
           (is (= [gone] (:seon.agent/missing-cites (deref p 0 ::unanswered))) (pr-str (deref p 0 ::unanswered)))
           (is (= :idle (get-in st' [:view :agents :a1 :status])) "the agent runs")))))))

;; Estimate: 6 step-fn calls x 10k (pure state in, state out) ~ 60,000 ticks
(deftest ^{:seon.test/estimate {:ticks 60000}} fork-server-events
  (let [st {:waiters (conj clojure.lang.PersistentQueue/EMPTY {:rid 5 :req {:op :stats}}) :pid 1 :log []}]
    (testing "a stats event answers the oldest waiter, by rid"
      (let [[st outs] (step #'server/fork-server-proc st :ev {:ev :stats :t 3})]
        (is (empty? (:waiters st)))
        (is (= {:rid 5} (:dest (first (:to-api outs)))))))
    (testing "an event about an agent goes on to the api"
      (let [[_ outs] (step #'server/fork-server-proc st :ev {:ev :exit :pid 9 :status {:exit 0}})]
        (is (= [9] (map :pid (:to-api outs))))))
    (testing "EOF of an older server is ignored"
      (let [[st2 outs] (step #'server/fork-server-proc st :ev {:ev :eof :pid 2})]
        (is (= st st2)) (is (nil? outs))))
    ;; docs/processes.md#a-stop-reaps-every-child: root's side keeps each forked pid until the server reaps it (its :exit), the pids
    ;; a stop kills and awaits (seon.fork-server/release!), so no agent outlives a stop that killed its server's group. Red before:
    ;; no pid was kept, and a killed agent was left to launchd (batch 21's lifecycle step, :left)
    (testing "a forked pid is kept until the server reaps it"
      (let [[st _] (step #'server/fork-server-proc st :ev {:ev :forked :agent :a1 :slot 0 :pid 41})
            [st _] (step #'server/fork-server-proc st :ev {:ev :forked :agent :a2 :slot 1 :pid 42})
            [st outs] (step #'server/fork-server-proc st :ev {:ev :exit :pid 41 :status {:exit 0}})]
        (is (= #{42} (:forked st)) "the one not reaped is the stop's to end")
        (is (= [41] (map :pid (:to-api outs))) "and the exit goes on to the api")))))

;; Estimate: 4 step-fn calls x 10k (pure state in, state out) ~ 40,000 ticks
(deftest ^{:seon.test/estimate {:ticks 40000}} a-lost-fork-server-answers-every-waiter
  ;; the server died with no standby to take its place (it died at boot, before its first
  ;; standby): every request it was asked, and every one asked after, is answered with the
  ;; error, and a fork's descriptor is closed. Unfixed, a stats request waited for good and a
  ;; later fork was never sent nor answered, so the attach that asked for it hung (an agent
  ;; test's root whose fork server died at boot)
  (let [[fd peer] (sock/socketpair)
        st {:waiters (conj clojure.lang.PersistentQueue/EMPTY {:rid 5 :req {:op :stats}}) :pid 1 :fd fd :log []}
        refused? #(= :seon.system/refused (:seon.error/type %))
        [st outs] (step #'server/fork-server-proc st :ev {:ev :eof :pid 1})]
    (testing "its death answers the requests it never answered"
      (is (= [:fork-server-down :fork-server-lost] (keep :ev (:to-api outs))))
      (is (= [5] (keep #(get-in % [:dest :rid]) (:to-api outs))))
      (is (every? refused? (keep #(when (:dest %) (:r %)) (:to-api outs))))
      (is (empty? (:waiters st))))
    (testing "a fork asked after it fails at once, naming why, and its descriptor is closed"
      (let [[afd apeer] (sock/socketpair)
            [st outs] (step #'server/fork-server-proc st :cmd {:op :fork :agent :a1 :fd afd})]
        (is (= [{:ev :fork-failed :agent :a1}] (map #(select-keys % [:ev :agent]) (:to-api outs))))
        (is (string? (:reason (first (:to-api outs)))))
        (is (= -1 (sock/close! afd)) "root's copy of the agent's socket is closed")
        (is (empty? (:in-flight st)))
        (sock/close! apeer)))
    (testing "a stats request asked after it is answered at once"
      (let [[st outs] (step #'server/fork-server-proc st :ext {:op :stats :rid 6})]
        (is (= [6] (keep #(get-in % [:dest :rid]) (:to-api outs))))
        (is (refused? (:r (first (:to-api outs)))))
        (is (empty? (:waiters st)))))
    (testing "a shutdown of the lost server closes no descriptor it no longer holds"
      (is (nil? (:fd st)))
      (let [[st outs] (step #'server/fork-server-proc st :ext {:op :shutdown})]
        (is (:stopping st))
        (is (= [:fs-down] (keep :op (:to-api outs))))))
    (sock/close! peer)))

;; Estimate: 5 step-fn calls x 10k (pure state in, state out) ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} agent-proc-steps
  ;; root's end of the agent's socket is a jolt.async.remote end: the agent's frames arrive
  ;; as values on its :in (the agent proc's :from), and nil there is its close
  (let [[r a] (sock/socketpair)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        st (assoc st :pid 42 :inflight {7 {:kind :eval}})]
    (testing "a reply answers its request by id and reports the agent idle"
      (let [[st outs] (step #'ap/agent-proc st :from {:op :reply :id 7 :response {:handle 'result/e1 :text "{:handle result/e1, :value 3}"} :t 1})]
        (is (= {:rid 7} (:dest (first (:to-api outs)))))
        (is (empty? (:inflight st)))
        (is (some #(= :idle (get-in % [:set :status])) (:to-api outs)))))
    (testing "the agent committed its own branch: the writer hears it, for the next flush"
      (let [[_ outs] (step #'ap/agent-proc st :from {:op :committed :t 5 :txs 2})]
        (is (= [{:op :agent-commit :branch :agent-1 :t 5}] (:to-writer outs)))))
    (testing "the end's close ends the agent, naming the requests it leaves unanswered"
      (let [[st outs] (step #'ap/agent-proc st :from nil)]
        (is (:dead? st))
        (is (= [:ended] (map :op (filter :op (:to-api outs)))))
        (is (= [7] (:inflight (first (filter #(= :ended (:op %)) (:to-api outs))))))))
    (testing "a request goes onto the end's :out, framed for the agent's socket"
      (let [_ (step #'ap/agent-proc st :in {:op :write :msg {:op :message :body 1}})
            c (sock/conn a)]
        (is (= {:op :message :body 1} (sock/recv! c)))))
    (remote/close! end)
    (sock/close! a)))

;; Estimate: 5 step-fn calls x 10k (pure state in, state out) ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} api-server
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                          :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})]
    (testing "the view is answered to its caller's promise"
      (let [p (promise)
            [_ outs] (step #'root/api-proc st :ext {:op :state :p p})]
        (is (= {:agents {}} @p)) (is (= {} outs))))
    (testing "a request for no live agent answers at once"
      (let [p (promise)]
        (step #'root/api-proc st :ext {:op :eval :id :nope :forms "1" :p p})
        (is (re-find #"no live agent" (:seon.error/message @p)))))
    (testing "a writer request carries its rid and is answered by it"
      (let [p (promise)
            [st outs] (step #'root/api-proc st :ext {:op :write :req {:op :tx :branch :db :tx-data []} :p p})
            rid (get-in (first (:to-writer outs)) [:dest :rid])
            [_ _] (step #'root/api-proc st :from-writer {:dest {:rid rid} :r {:ok true}})]
        (is (= {:ok true} @p))))))

;; Estimate: 1 test-branch! x 1M; 2 step-fn calls x 0.1M (a transact or a commit read in each); 2 transacts x 50k ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-persons-ask-and-message-name-the-signed-in-user-and-are-refused-with-none
  ;; docs/users.md#whats-on-screen: who asks is the user signed in on main, never the OS account
  (ts/test-branch!
   (fn [_ conn]
     (let [api #(#'root/api-proc {:boot "b1" :conn conn :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                                  :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
           ask (fn [m] (let [p (promise) [_ outs] (step #'root/api-proc (api) :ext (assoc m :p p))] [p outs]))
           txs (fn [outs] (vec (mapcat :tx-data (:to-writer outs))))]
       (d/transact conn {:tx-data [{:seon.agent/key "a1"}]})
       (let [[p _] (ask {:op :ask :text "do a thing"})]
         (is (re-find #"no user is signed in" (str (:seon.error/message @p))) "no user: the ask is refused"))
       (let [[p _] (ask {:op :message :id :a1 :body "hi"})]
         (is (re-find #"no user is signed in" (str (:seon.error/message @p))) "no user: the message is refused"))
       (d/transact conn {:tx-data [{:db/id "u" :seon.agent/key "sean" :seon.user/name "sean"} {:seon.system/id :root :seon.system/signed-in "u"}]})
       (let [[_ outs] (ask {:op :ask :text "do a thing"})]
         (is (some #{[:db/add [:seon.task/id "request/do-a-thing"] :seon.task/started-by [:seon.agent/key "sean"]]} (txs outs)) "the signed-in user started it"))
       (let [[_ outs] (ask {:op :message :id :a1 :body "hi"})]
         (is (some #(and (map? %) (= {:seon.agent/key "sean"} (:seon.message/from %))) (txs outs)) "from the signed-in user"))
       ;; a sender the caller names is kept: root's own (the reconciler's stage boundary sends :from :root), never the person
       (let [[_ outs] (ask {:op :message :id :a1 :from :root :body "the design is done"})]
         (is (some #(and (map? %) (= {:seon.system/id :root} (:seon.message/from %))) (txs outs)) "from the system, as root names itself"))))))

;; Estimate: 1 test-branch! x 1M; 2 step-fn calls x 0.1M (a transact or a commit read in each); 3 transacts x 50k ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} root-moves-the-signed-in-users-focus-and-refuses-an-unknown-agent-or-no-user
  ;; docs/users.md#whats-on-screen: root's focus! request writes the signed-in user's :seon.user/focus to the agent named by key
  (ts/test-branch!
   (fn [_ conn]
     (let [api #(#'root/api-proc {:boot "b1" :conn conn :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                                  :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
           ask (fn [m] (let [p (promise) [_ outs] (step #'root/api-proc (api) :ext (assoc m :p p))] [p outs]))
           req (fn [k] (root/request 'focus! nil {:key k} nil))]
       (is (= {:op :focus :key "a1"} (req "a1")) "the request table maps focus! to the op")
       (d/transact conn {:tx-data [{:seon.agent/key "a1"}]})
       (let [[p _] (ask (req "a1"))]
         (is (re-find #"no user is signed in" (str (:seon.error/message @p))) "no user: refused"))
       (d/transact conn {:tx-data [{:db/id "u" :seon.agent/key "sean" :seon.user/name "sean"} {:seon.system/id :root :seon.system/signed-in "u"}]})
       (let [[p _] (ask (req "nobody"))]
         (is (re-find #"no agent or task nobody" (str (:seon.error/message @p))) "no such agent or task: refused"))
       ;; a task an agent just launched, before its agent is made: the focus is the task
       (d/transact conn {:tx-data (seon.task/create-tx @conn [{:seon.task/ns 'request :seon.task/name "w" :seon.task/plan "Make w."}])})
       (let [[_ outs] (ask (req "request/w"))]
         (is (= [[:db/add [:seon.user/name "sean"] :seon.user/focus [:seon.task/id "request/w"]]] (vec (mapcat :tx-data (:to-writer outs)))) "a launched task, by its id"))
       (let [[_ outs] (ask (req "a1"))]
         (is (= [[:db/add [:seon.user/name "sean"] :seon.user/focus [:seon.agent/key "a1"]]] (vec (mapcat :tx-data (:to-writer outs)))) "the signed-in user's focus moves to the agent"))))))

;; Estimate: 1 child process started, killed and waited on (3 calls, ~30k ticks each: the work is the kernel's) + api-proc and 3 pure state steps ~30k ~ 0.12M, about 1e5 ~ 100,000 ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} an-end-closed-on-a-live-pid-ends-the-agent-and-answers-its-waiter
  ;; an agent whose end closed can never answer root again: root ends it at once (the kill its :kill
  ;; makes), and its exit, which comes, answers the requests it held through ::dying. No bound is
  ;; reached and no stall timer is armed. The pid is a real child (sleep 20), alive unless killed.
  (let [proc (.start (ProcessBuilder. ^java.util.List ["sleep" "20"]))
        pid (.pid proc)
        p (promise)
        st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {:a1 {:status :busy :pid pid :branch :b1}}} :graph (promise)
                             :system (doto (promise) (deliver {:procs {} :conns []}))})
        st (-> st (assoc-in [:agents :a1] {:branch :b1}) (assoc :writer-closed true))
        [st rid] (#'root/pend st p :eval :a1)
        st (#'root/end-agent st (volatile! {}) {:agent :a1 :pid pid :inflight [rid]})
        status {:exit (.waitFor proc)}]
    (is (not= {:exit 0} status) (str "ended by root, not left to run out: " (pr-str status)))
    (is (not (realized? p)) "its waiter is answered at its exit, not before")
    (let [st' (#'root/back-op st (volatile! {}) {:ev :exit :pid pid :status status})]
      (is (realized? p))
      (when (realized? p)
        (is (= [:seon.agent/died :a1] ((juxt :seon.error/type :agent) @p)) (pr-str @p))
        (is (re-find #"ended by root" (:seon.error/message @p))))
      (is (empty? (:seon.system/dying st')) "nothing is parked")
      (is (false? @(:seon.system/stall-timer st')) "and no stall timer was armed"))))

;; Estimate: 5 step-fn calls x 10k (pure state in, state out) ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} a-step-that-throws-after-a-stall-check-leaves-the-check-armed
  ;; docs/clocks.md: root's stall check is a timer to itself (seon.step/timer). A step that took :stall-check and then
  ;; threw is undone by Flow (its state from before the step is kept); the timer's armed flag is a host object, so the
  ;; next step with an eval in flight arms it again. Red before: the flag was in the state (::stall-due), kept true by
  ;; the undo, and no check was armed for the life of root. No clock: each arm's put is recorded as it is asked for
  ;; (seon.step/put-after! redefined), never waited on.
  (let [puts (atom [])
        st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
        st (assoc st :stall-ms 2 :seon.system/busy #{:a1})]
    (with-redefs [step/put-after! (fn [_ _ m] (swap! puts conj m) nil)]
      (let [[st _] (#'root/api-proc st :ext {:op :noop})]
        (is (= [{:op :stall-check}] @puts) "an eval in flight arms the check")
        (let [[st _] (#'root/api-proc st :ext {:op :noop})]
          (is (= 1 (count @puts)) "armed once: a second step arms nothing more")
          ;; the step that takes it throws after the take: Flow keeps the state from before it
          (is (thrown? Throwable (with-redefs [root/record-agents (fn [& _] (throw (ex-info "after the take" {})))]
                                   (#'root/api-proc st :back {:op :stall-check}))))
          (#'root/api-proc st :ext {:op :noop})
          (is (= [{:op :stall-check} {:op :stall-check}] @puts) "the next step arms it again"))))))

;; Estimate: 4 step-fn calls x 10k (pure state in, state out) ~ 40,000 ticks
(deftest ^{:seon.test/estimate {:ticks 40000}} root-records-what-it-observes-of-its-agents
  ;; what root observes of an agent is facts on the
  ;; agent's entity, written when a member it records changes; a change it does not record
  ;; (the main t the agent reports) writes nothing
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
        ;; no stall check (:stall-ms 0): the busy agent's check is not this test's
        st (assoc st :stall-ms 0 :view {:agents {:a3 {:status :idle :pid 77 :branch :task-demo-f}}})
        [st outs] (step #'root/api-proc st :back {:op :status :agent :a3 :set {:status :busy :running {:form "(f 2)" :since-ms 5}}})
        w (first (:to-writer outs))]
    (is (= {:op :tx :branch :db :dest {:agent-facts [:a3]}} (dissoc w :tx-data)))
    (is (= [:busy "(f 2)" 77] (let [p (first (filter :seon.process/id (:tx-data w)))] [(:seon.agent/status (first (:tx-data w))) (:seon.process/running p) (:seon.process/pid p)])))
    (is (= "a3" (:seon.agent/key (first (:tx-data w)))) "its key is its id's name, whatever boot")
    (let [[st outs] (step #'root/api-proc st :back {:op :status :agent :a3 :set {:t 42}})]
      (is (empty? (:to-writer outs)) "nothing it records changed: nothing written")
      (let [[_ outs] (step #'root/api-proc st :back {:op :status :agent :a3 :set {:status :idle :running nil}})]
        (is (some #{[:db.fn/retractAttribute [:seon.process/id "a3/77"] :seon.process/running]} (:tx-data (first (:to-writer outs))))
            "the form returned: no longer running")))))

;; Estimate: two held calls, the second at n = 100,000: a 100,000-element vec (~20 ticks an element = 2M) + 2 strings of 100,000 chars (apply str repeat, ~20 ticks a char = 4M) + 4 api steps ~0.1M + pr-str of the 2 handles ~0 ~ 6M, about 5e6 ~ 5,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 5000000}} root-view-of-an-agents-evals-holds-handles-not-values
  ;; task seon.system/an-agents-view-keeps-handles-not-values (docs/machine.md#roots-heap):
  ;; the bytes root's view keeps of an agent's evals are the same whatever the evals returned, wrote or
  ;; threw. Red while the entry held the response whole (its :value, :out and an error's :data grew with them)
  (let [st0 (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                              :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
        st0 (assoc st0 :stall-ms 0 :view {:agents {:a3 {:status :idle :pid 77 :branch :task-demo-f :evals []}}})
        held (fn [n]
               (let [big (vec (range n))
                     r1 {:handle 'result/e1 :text "[0 1 2 ...]" :ms 0.1 :value big :out (apply str (repeat n "x"))}
                     r2 {:handle 'result/e2 :text "boom" :ex {:seon.error/type :seon.agent/eval-error :seon.error/message "boom" :data {:v big}} :err (apply str (repeat n "y"))}
                     [st _] (step #'root/api-proc st0 :back {:op :status :agent :a3 :set {:evals-add [r1] :status :idle}})
                     [st _] (step #'root/api-proc st :back {:op :status :agent :a3 :set {:evals-add [r2] :status :idle}})
                     evals (get-in st [:view :agents :a3 :evals])]
                 (is (= '[result/e1 result/e2] (mapv :handle evals)) "each eval's handle, in order")
                 (is (= "boom" (get-in evals [1 :ex :seon.error/message])))
                 (count (pr-str evals))))]
    (is (= (held 10) (held 100000)) "bytes held do not grow with what the evals returned")))

;; Estimate: 2 step-fn calls x 10k (pure state in, state out) ~ 20,000 ticks
(deftest ^{:seon.test/estimate {:ticks 20000}} an-agent-that-ended-is-answered-ended-one-never-had-no-agent
  ;; bin/check e2e-calc (2026-10-23): root's answer says whether the agent ended (its view
  ;; records :ended-ms: a kill, its run's end, a death) or root never had it, so a reader
  ;; (seon.prove) tells a cancel from a defect by the type alone
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {:a7 {:status :killed :ended-ms 1}}} :graph (promise)
                             :system (doto (promise) (deliver {:procs {} :conns []}))})
        ask (fn [m] (let [p (promise)] (step #'root/api-proc st :ext (assoc m :p p)) @p))]
    (doseq [op [:eval :tests :stats :merge-main]]
      (is (= [:seon.system/ended :a7] ((juxt :seon.error/type :agent) (ask {:op op :id :a7 :forms "1"}))) (str op " to an agent that ended"))
      (is (= :seon.system/no-agent (:seon.error/type (ask {:op op :id :nope :forms "1"}))) (str op " to one root never had")))
    (is (= :seon.system/ended (:seon.error/type (ask {:op :clone :parent :a7}))) "a clone of an agent that ended")
    (testing "a request in flight when root stopped its agent is answered ended"
      (let [p (promise)
            st (-> st (assoc-in [:agents :a1] {:branch :b1}) (assoc-in [:view :agents :a1] {:status :killed}))
            [st rid] (#'root/pend st p :eval :a1)
            st (#'root/end-agent st (volatile! {}) {:agent :a1 :inflight [rid]})]
        (is (= [:seon.system/ended :a1] ((juxt :seon.error/type :agent) @p)))
        (is (get-in st [:view :agents :a1 :ended-ms]) "and its view records the end")))
    (is (= :seon.system/ended (:seon.error/type (root/request 'merge-main! :a7 nil {:status :killed :ended-ms 1}))) "request's merge-main! to an agent that ended")))

(def ^:private fake-members
  "Protocol members a refused answer must not carry: an eval item's, a test count's, a merge's."
  #{:id :tag :ok :merged? :pass :fail :error :reports :applied :conflicts})

;; Estimate: 1 test-branch! x 1M; 6 step-fn calls x 0.1M (a transact or a commit read in each) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-refused-answer-is-the-one-error-map
  ;; a request no live agent can take, a function root refuses, and a write the writer refuses
  ;; each answer the one error map (seon.contract/error) and nothing beside it: no eval item
  ;; with id -1, no test count, no :ok or :merged? false
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})
        bare? (fn [r] (and (map? r) (seon.contract/error? r) (empty? (filter fake-members (keys r)))))]
    (doseq [op [:eval :tests :merge-main :stats]]
      (let [p (promise)]
        (step #'root/api-proc st :ext {:op op :id :nope :forms "1" :p p})
        (is (bare? @p) (str op " to no live agent: " (pr-str @p)))))
    (let [p (promise)]
      (step #'root/api-proc st :ext {:op :clone :parent :nope :p p})
      (is (bare? @p) (pr-str @p)))
    (let [p (promise)]
      (step #'root/api-proc st :ext {:op :ping :id :nope :p p})
      (is (= :seon.system/refused (:seon.error/type @p)) "a request kind with no producer is refused, not left unanswered"))
    (doseq [[f a] [['merge-main! nil] ['merge! nil] ['merge! {:branch :b :status :dead}] ['nope! nil]]]
      (is (bare? (root/request f :a1 nil a)) (str f))))
  (ts/test-branch! (fn [cfg conn]
    (let [st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer (a/chan 4) :events (a/chan 4)})
          [_ outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:no/such 1}] :dest {:rid 1}})
          r (:r (first (:to-api outs)))]
      (is (and (seon.contract/error? r) (not (contains? r :ok))) (pr-str r))))))

;; Estimate: 3 step-fn calls x 10k (pure state in, state out) ~ 30,000 ticks
(deftest ^{:seon.test/estimate {:ticks 30000}} a-collection-waits-until-no-agent-is-live
  ;; root collects the store only as its only writer (owner, 2026-10-22, simplest first;
  ;; seon.db/collect!): a :collect while an agent is live is refused at once, naming the live
  ;; agents, and never reaches the writer; with none live it goes to the writer like any write
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))})]
    (let [p (promise)
          [_ outs] (step #'root/api-proc (assoc st :agents {:a1 {:status :running}}) :ext {:op :write :req {:op :collect} :p p})]
      (is (realized? p) "answered at once")
      (is (= :seon.system/refused (:seon.error/type (when (realized? p) @p))))
      (is (= [:a1] (:agents (when (realized? p) @p))))
      (is (empty? (:to-writer outs))))
    (let [p (promise)
          [_ outs] (step #'root/api-proc st :ext {:op :write :req {:op :collect} :p p})]
      (is (= :collect (:op (first (:to-writer outs))))))))

;; ---- request/answer: one asker's table (seon.step/pend, ask, answered) ---------------------

;; Estimate: 1 step-fn calls x 10k (pure state in, state out) ~ 10,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000}} one-asker-table-answers-each-request-once
  (let [st {:rid 0 :pending {}}
        [st m1] (step/ask st [:render :answers] :state {:op :state})
        [st m2] (step/ask st [:render :answers] [:branch :a1] {:op :write})
        [st r3] (step/pend st nil)]
    (testing "each request a new id, routed back to the asker, what awaits it pending by id"
      (is (= {:op :request :req {:op :state} :route [:render :answers] :tag 1} m1))
      (is (= 2 (:tag m2)))
      (is (= 3 r3 (:rid st)))
      (is (= {1 :state 2 [:branch :a1]} (:pending st)) "an id nothing awaits holds no entry"))
    (testing "an answer takes its entry once: a second answer to the same id finds nothing"
      (let [[st v] (step/answered st 2)
            [st again] (step/answered st 2)]
        (is (= [:branch :a1] v))
        (is (nil? again))
        (is (= {1 :state} (:pending st)))))
    (testing "an id never asked finds nothing"
      (is (nil? (second (step/answered st 99)))))))

(defn- agent-proc-on
  "An agent's proc over a socketpair, with request 7 in flight; f takes it, then both ends close."
  [f]
  (let [[r a] (sock/socketpair)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode}})
        st (#'ap/agent-proc {:id :a1 :end end :branch :agent-1 :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})]
    (try (f (assoc st :pid 42))
         (finally (remote/close! end) (sock/close! a)))))

;; Estimate: 2 step-fn calls x 10k (pure state in, state out) ~ 20,000 ticks
(deftest ^{:seon.test/estimate {:ticks 20000}} an-eval-says-it-started
  ;; the agent's {:op :started} is root's
  ;; :started event, with the eval's text (what a kill or an interrupt meant for it awaits)
  (agent-proc-on
   (fn [st]
     (let [[st _] (step #'ap/agent-proc st :in {:op :req :rid 7 :kind :eval :msg {:op :eval :forms "(loop [] (recur))"}})
           [_ outs] (step #'ap/agent-proc st :from {:op :started :id 7})]
       (is (= [{:type :started :agent :a1 :rid 7 :form "(loop [] (recur))"}] (mapv #(dissoc % :at-ms) (:events outs))))))))

;; Estimate: 1 step-fn calls x 10k (pure state in, state out) ~ 10,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000}} a-test-run-says-its-child-forked
  ;; the agent sends the test child's pid once it is forked: root's :tests-started event
  (agent-proc-on
   (fn [st]
     (let [[_ outs] (step #'ap/agent-proc st :from {:op :tests-started :id 7 :pid 4242})]
       (is (= [{:type :tests-started :agent :a1 :rid 7 :pid 4242}] (mapv #(dissoc % :at-ms) (:events outs))))))))

;; Estimate: 3 step-fn calls x 10k (pure state in, state out) ~ 30,000 ticks
(deftest ^{:seon.test/estimate {:ticks 30000}} a-tab-hears-its-view-settled
  ;; the render proc says when nothing it asked is in flight any more (after an answer, or
  ;; after what it sent the tabs), never while a request is out; the web proc puts a
  ;; settled comment on every feed
  ;; main already read (:main-db): after its first read, main comes in the writer's commits
  (let [st {:events-ch (a/chan 8) :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events []
            :views {} :zoom {} :merges 0 :main {:t 1} :main-db (ts/test-db)}
        settled? (fn [outs] (some #(= :settled (:op %)) (:to-web outs)))
        [st o1] (#'seon.render.proc/render-proc st :events {:type :spawned :agent :a1 :at-ms 1})
        tag (:tag (first (filter #(= {:op :state} (:req %)) (:to-api o1))))
        [st o2] (#'seon.render.proc/render-proc st :answers {:tag tag :r {:agents {} :writer {:tx-count nil :recent []} :fork-server {:status :up} :stats {} :store {} :boot {}}})]
    (is (not (settled? o1)) "its ask is in flight")
    (is (settled? o2) "the answer emptied what was in flight")
    (is (empty? (:pending st))))
  (let [ch (a/chan 4)
        st {:tabs {1 {:ch ch :view :root}} :stale #{} :bytes 0}
        [st _] (#'seon.web/web-proc st :from-render {:op :settled})]
    (is (= ": settled\n\n" (a/poll! ch)))
    (is (= (count ": settled\n\n") (:bytes st)))))

;; Estimate: 5 step-fn calls x 10k (pure state in, state out) ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} the-render-proc-asks-for-root-s-view-once-per-round-trip
  ;; the coalescing window is the request in flight, not a clock (ASK-BOUND-MS is gone)
  (let [events (a/chan 8)
        st {:events-ch events :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events []
            :views {} :zoom {} :merges 0 :main {:t 1}}
        asks (fn [outs] (filter #(= {:op :state} (:req %)) (:to-api outs)))
        [st o1] (#'seon.render.proc/render-proc st :events {:type :spawned :agent :a1 :at-ms 1})
        [st o2] (#'seon.render.proc/render-proc st :events {:type :ready :agent :a1 :at-ms 2})]
    (is (= 1 (count (asks o1))) "an event asks for the view")
    (is (empty? (asks o2)) "while that request is in flight, more events only queue")
    (let [[st _] (#'seon.render.proc/render-proc st :answers {:tag (:tag (first (asks o1))) :r {:agents {} :writer {:tx-count 1 :recent []} :fork-server {:status :up} :stats {} :store {} :boot {}}})
          [_ o3] (#'seon.render.proc/render-proc st :events {:type :ready :agent :a2 :at-ms 3})]
      (is (not-any? #{:state} (vals (:pending st))) "the answer took its request")
      (is (= 1 (count (asks o3))) "the next event asks again"))))

;; ---- a proc answers every request it took (laws audit #2) ----------------------

(defn- api-state []
  (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                    :view {:agents {}} :graph (promise) :system (doto (promise) (deliver {:procs {} :conns []}))}))

(defn- answer "What p was answered, or :unanswered." [p] (if (realized? p) @p :unanswered))

;; Estimate, each piece measured in an agent (2026-11-13): the throw, a case miss (Jolt's IllegalArgumentException) ~0.7M; flow-error
;; ~0.13M (its trace: Jolt keeps 25 frames, ~0.1M to read at any depth); api-state ~0.2M; one api step ~0.1M; step-out-ok?'s
;; validation ~0.3M the first time its schema compiles: 1,500,000 ticks, its own work. Measured 2M-4.3M where it ran first in its
;; shard, which charges the process's first compiles and loads to it: a measurement defect filed for tests, not this test's work
(deftest ^{:seon.test/estimate {:ticks 1500000}} a-proc-defect-names-where-it-threw
  ;; the standing root's worst open problem: the reconciler's "No matching clause: " 255 times, a
  ;; case meeting nil, with nothing saying in which function or on what message
  (let [ex (try (case (:op {}) :a 1) (catch Exception x x))
        m (#'root/flow-error {:clojure.core.async.flow/pid :reconciler :clojure.core.async.flow/ex ex
                              :clojure.core.async.flow/msg {:tag 7 :r nil}})
        p (promise)
        [_ outs] (step #'root/api-proc (api-state) :back (assoc m :msg {:op :state :p p}))]
    (is (= [{:type :proc-defect :pid :reconciler :message (ex-message ex)}] (map #(dissoc % :at-ms) (:events outs)))
        "an event, so a wait on root's events for what the proc owed ends (bin/check robust waited for good)")
    (is (= "seon.procs-test" (some-> (:in m) namespace)) (str "the fn it threw in: " (pr-str (:in m))))
    (is (= (:in m) (:seon.error/in (answer p))) "on the defect")
    (is (= :state (:took (answer p))) "with what its step took")))

;; Estimate: 3 threads started and joined (~30k each) + 3 ex-info throws with their stacks (~20k each) + 1 go block 20k + api-state and 1 api step ~0.1M ~ 0.3M, about 2e5 ~ 200,000 ticks
(deftest ^{:seon.test/estimate {:ticks 200000}} an-uncaught-error-reaches-root-never-only-stderr
  ;; docs/errors.md#nothing-crashes-and-nothing-hangs (Point 2026, Sean's silent errors): a fiber died at start with its error
  ;; only on stderr ("Exception in go/fiber body"). Root takes every uncaught error (seon.system/catch-uncaught!): kept until it
  ;; listens, which its start fails on, then its :back, which the api records as a named defect
  (let [old (Thread/getDefaultUncaughtExceptionHandler)
        u @#'root/uncaught
        boom (fn [msg] (doto (Thread. ^Runnable (fn [] (throw (ex-info msg {:n 1})))) (.start) (.join)))]
    (try
      (#'root/catch-uncaught!)
      (testing "before root listens: kept, for its start to fail on"
        (boom "early boom")
        (is (= ["early boom"] (map #(re-find #"early boom" (:message %)) (:early @u))) (pr-str @u)))
      (testing "a go block's uncaught throw reaches the same handler (the Jolt pin 75eec622: never only stderr)"
        ;; its record is the wait's end: the handler's own write. A jolt.fibers/spawn body's throw does NOT reach it (held for a
        ;; joiner, measured 2026-11-12): with Deep fixes, not asserted here
        (let [before (count (:early @u)) p (promise) k (gensym)]
          (add-watch u k (fn [_ _ _ v] (when (some #(re-find #"go boom" (str (:message %))) (drop before (:early v))) (deliver p true))))
          (a/go (throw (ex-info "go boom" {:n 3})))
          @p (remove-watch u k)
          (is (some #(re-find #"go boom" (str (:message %))) (drop before (:early @u))) (str "the go block's: " (pr-str @u)))))
      (testing "after: on root's :back, and the api records it as a defect naming it"
        (let [back (a/chan 4) _ (swap! u assoc :back back)
              _ (boom "late boom")
              m (a/poll! back)
              [_ outs] (step #'root/api-proc (api-state) :back m)]
          (is (= :uncaught (:pid m)) (pr-str m))
          (is (re-find #"uncaught in thread .*: late boom" (str (:message m))))
          (is (= :seon.system/proc-defect (get-in m [:data :seon.error/type])))
          (is (= [:proc-defect] (map :type (:events outs))) "an event, as any proc's defect")))
      (finally (Thread/setDefaultUncaughtExceptionHandler old) (reset! u {:back nil :early []})))))

;; Estimate: 1 test-branch! x 1M; 9 step-fn calls x 0.1M (a transact or a commit read in each) ~ 2,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-throwing-step-answers-every-pending-request
  ;; Flow's step catch keeps the old state and drops the outputs; its error message carries the
  ;; message the step took (::flow/msg), which the pipe from Flow's error channel carries as :msg
  (let [st (api-state)]
    (testing "the api's own step threw on {:op :state}: its caller is answered the defect, not left waiting"
      (let [p (promise)]
        (step #'root/api-proc st :back {:op :error :pid :api :message "boom" :msg {:op :state :p p}})
        (is (= :seon.system/proc-defect (:seon.error/type (answer p))))))
    (testing "the writer threw on a request the api sent it: the api answers that request's caller by its rid"
      (let [p (promise)
            [st outs] (step #'root/api-proc st :ext {:op :write :req {:op :tx :branch :db :tx-data []} :p p})
            sent (first (:to-writer outs))]
        (step #'root/api-proc st :back {:op :error :pid :writer :message "boom" :msg sent})
        (is (= :seon.system/proc-defect (:seon.error/type (answer p))))
        (is (= "boom" (:seon.error/message (answer p))))))
    (testing "the api's step threw on a later message of its batch (Flow names only the first): each message it took is answered"
      (let [first-msg {:op :state :p (promise)}]
        (a/>!! (:back st) {:op :request :req {:op :write :req {:op :tx :branch :db :tx-data []}} :route [:reconciler :answers] :tag 3})
        (a/>!! (:back st) {:op :status :agent "not-an-id" :set {}})
        (is (thrown? Exception (#'root/api-proc st :ext first-msg)) "the third message throws: Flow keeps st, drops the outputs")
        (let [[_ outs] (step #'root/api-proc st :back {:op :error :pid :api :message "boom" :msg first-msg})
              again (get outs [:reconciler :answers])]
          (is (= [3] (map :tag again)) "the second message, a proc's write request, is answered on its route")
          (is (= :seon.system/proc-defect (:seon.error/type (:r (first again))))))))
    (testing "a proc threw on the answer it took: the api answers it again on the proc's :answers route, once"
      (let [[_ outs] (step #'root/api-proc st :back {:op :error :pid :render :message "boom" :msg {:tag 7 :r {:agents {}}}})
            again (get outs [:render :answers])]
        (is (= [7] (map :tag again)))
        (is (= :seon.system/proc-defect (:seon.error/type (:r (first again)))))
        (testing "and a step that throws on that defect is not answered again"
          (let [[_ outs] (step #'root/api-proc st :back {:op :error :pid :render :message "boom" :msg (first again)})]
            (is (empty? (get outs [:render :answers])))))
        (testing "the render proc takes the defect: its view request answered, the view as it was"
          (let [rs {:rid 7 :pending {7 :state} :view {:agents {:a1 {}}}}
                [rs2 _] (#'seon.render.proc/render-proc rs :answers (first again))]
            (is (empty? (:pending rs2)))
            (is (= (:view rs) (:view rs2)))))))))

(defn- spawning-api
  "(f st) on an api state that can spawn: its main a branch of the test store, its graph a stub
  that takes the update! a spawn makes (the agent's proc), so the api's step runs alone."
  [f]
  (ts/test-branch! (fn [_ conn]
    (f (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 8) :conn conn
                         :view {:agents {}} :system (doto (promise) (deliver {:procs {} :conns []}))
                         :graph (doto (promise) (deliver (reify clojure.core.async.flow.impl.graph/Update (update-flow [_ _] {}))))})))))

;; Estimate: the fork server's state on the world (graph and index given) ~10k + a throw with its stack ~0.1M + a :stats step (a count
;; of the world's ~2,000 datoms) ~50k + api-state and 3 api steps (a spawn refused, its error printed) ~0.5M ~ 0.7M; plus the world when
;; this test builds it first in its process, its 5 checked db-withs ~3M (its docstring's 0.6M is low): 4,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-fork-server-step-that-throws-answers-and-the-server-serves-on
  ;; task seon.fork-server/a-throwing-fork-server-step-answers-its-asker: a main commit the fork
  ;; server cannot read (a :tx naming no commit: the replica's d/commit-as-db throws, as a bad
  ;; entity in a d/with did, 2026-10-29) threw out of its step. Unfixed, the server stayed alive
  ;; at 0% CPU, root heard nothing, and the spawn asked next waited for good. Now the step's
  ;; throw is an event naming the op, the server's state is as it was, the next command is
  ;; served, and root records the error and goes on to spawn.
  ;; Then task seon.fork-server/every-fork-server-wait-ends-with-the-server: a server lost (dead
  ;; with no standby, a-lost-fork-server-answers-every-waiter) is heard by root's api, which
  ;; refuses a spawn with the error naming it, before any agent is made.
  ;; Both are step functions called in process on values (docs/processes.md#a-step-that-throws): the server on the world
  ;; with no store, so the :tx's commit can't be read; the catch is the step's own, whatever the op threw on
  (let [db (mt/world)
        st (#'server/server-step {:db db :graph {} :index {}})
        [st1 outs1] (#'server/server-step st :cmd {:op :tx :t (inc (:max-tx db)) :commit (random-uuid) :ns 0 :tx-data []})
        [_ outs2] (step #'server/server-step st1 :cmd {:op :stats})]
    (is (identical? st st1) "the step that threw left the server's state as it was")
    (is (= [:step-failed] (map :ev (:ev outs1))))
    (is (= :tx (:op (first (:ev outs1)))) "naming the op it took")
    (is (= :seon.fork-server/step-failed (get-in (first (:ev outs1)) [:error :seon.error/type])))
    (is (= [[:stats (:max-tx db)]] (map (juxt :ev :t) (:ev outs2))) "and the next command is served, on the db it held")
    (let [[told _] (step #'root/api-proc (api-state) :from-fs (first (:ev outs1)))
          [lost _] (step #'root/api-proc told :from-fs {:ev :fork-server-lost})
          p (promise)
          [after _] (step #'root/api-proc lost :ext {:op :spawn :opts {} :p p})
          refused (answer p)]
      (is (re-find #"step-failed" (pr-str (:seon.system/errors told))) "root recorded the error")
      (is (= :lost (get-in lost [:view :fork-server :status])))
      (is (= :seon.system/refused (:seon.error/type refused)) (pr-str refused))
      (is (re-find #"fork server is lost" (str (:seon.error/message refused))) (pr-str refused))
      (is (= (:agents lost) (:agents after)) "no agent was made for the refused spawn"))))

;; Estimate: 1 test-branch! x 1M; 3 step-fn calls x 0.1M (a transact or a commit read in each) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-spawn-is-answered-at-its-agents-ready
  ;; task seon.system/a-spawn-answers-at-its-agents-ready: never at once, while the agent is
  ;; :spawning, but at its hello, so what is sent to it next reaches a ready agent
  (spawning-api (fn [st]
    (let [p (promise)
          [st _] (step #'root/api-proc st :ext {:op :spawn :opts {} :p p})
          a1 (first (keys (:agents st)))]
      (is (= :unanswered (answer p)) "spawning: not answered")
      (let [[st _] (step #'root/api-proc st :back {:op :status :agent a1 :set {:forked-ms 1.0 :mem-mb 1.0 :t 1}})]
        (is (= :unanswered (answer p)) "a status that is not its hello: still not")
        (step #'root/api-proc st :back {:op :status :agent a1 :set {:ready true}})
        (is (= {:id a1} (answer p)) "its hello answers it"))))))

;; Estimate: 3 test-branch! x 1M; 7 step-fn calls x 0.1M (a transact or a commit read in each) ~ 4,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} a-spawn-whose-agent-dies-first-answers-the-death
  ;; the end the agent met before its hello is the answer: died, with the exit status the fork
  ;; server reaped (the process's exit, never a timeout); stopped first, the stop
  (spawning-api (fn [st]
    (let [p (promise)
          [st _] (step #'root/api-proc st :ext {:op :spawn :opts {} :p p})
          a1 (first (keys (:agents st)))
          [st _] (step #'root/api-proc st :from-fs {:ev :forked :agent a1 :pid 4242 :fork-ms 1.0})
          [st _] (step #'root/api-proc st :back {:op :ended :agent a1 :pid 4242 :inflight []})]
      (is (= :unanswered (answer p)) "its death waits for the exit status")
      (let [[st _] (step #'root/api-proc st :from-fs {:ev :exit :pid 4242 :status {:exit 3}})
            first-answer (answer p)]
        (is (= :seon.agent/died (:seon.error/type first-answer)))
        (is (= {:exit 3} (:exit first-answer)) "with the status it exited with")
        (is (= a1 (:agent first-answer)))
        ;; exactly one answer: the spawn's entry is gone once answered, so a later hello or end
        ;; has no caller to answer (a promise ignores a second delivery, so the entry is the proof)
        (is (not (contains? (:seon.system/spawning st) a1)) "answered: no spawn is held for the agent")
        (let [[st _] (step #'root/api-proc st :back {:op :status :agent a1 :set {:ready true}})]
          (is (not (contains? (:seon.system/spawning st) a1)))
          (is (= first-answer (answer p))))))))
  (spawning-api (fn [st]
    (let [p (promise)
          [st _] (step #'root/api-proc st :ext {:op :spawn :opts {} :p p})
          a1 (first (keys (:agents st)))]
      (step #'root/api-proc st :back {:op :ended :agent a1 :pid nil :inflight []})
      (is (= :seon.agent/died (:seon.error/type (answer p))) "never forked: its death at once, no status to wait for")))))

(defn- answering-sys
  "(f sys), sys's server answering every request with r (a stub for the api), on a
  branch of the test store deleted when f returns."
  [r f]
  (ts/test-branch! (fn [cfg conn] (let [ext (a/chan 4)]
    (a/go-loop [] (when-let [m (a/<! ext)] (deliver (:p m) r) (recur)))
    (f {:ext ext :cfg cfg :conn conn :graph nil})))))

;; Estimate: 1 test-branch! x 1M ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} root-state-never-answers-an-empty-system
  ;; "Unknown is not {}": what the api answers is what the caller gets; a defect stays a defect
  (let [e (seon.contract/error :seon.system/proc-defect 'seon.system/api-proc "boom" {:pid :api})]
    (answering-sys e (fn [sys]
      (testing "state: the defect, not a view decorated around it"
        (is (= e (root/state sys))))
      (testing "errors: the defect, not an empty fold"
        (is (= e (root/errors sys))))
      (a/close! (:ext sys))))))

;; Estimate: a few pure step calls ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} the-step-loop-runs-flows-contract-in-one-thread
  ;; a counter step-fn run by seon.step/run-loop over an in-memory transport
  (let [step-fn (fn ([] {:workload :io :ping-map-fn (fn [s] {:n (:n s)})})
                  ([_] {:n 0 :log []})
                  ([s tr] (update s :log conj tr))
                  ([s _ m] [(update s :n inc) {:out [[:got m]]}]))
        inbox (atom [[:in 1] [:in {:op :pause}] [:in 2] [:in {:op :ping}] [:in {:op :resume}] [:in 3] [:in {:op :stop}] [:in 4]])
        sent (atom [])
        final (step/run-loop step-fn nil
                             (fn [] (let [[x & more] @inbox] (reset! inbox more) x))
                             (fn [port m] (swap! sent conj [port m])))]
    (is (= [1 2 3] (keep (fn [[p m]] (when (= p :out) (second m))) @sent)) "held while paused, then in order")
    (is (= {:op :pong :status :paused :count 1 :held 1 :state {:n 1}} (second (first (filter (fn [[p m]] (= :pong (:op m))) @sent)))))
    (is (= [::flow/pause ::flow/resume ::flow/stop] (:log final)))
    (is (= [[:in 4]] @inbox) "nothing is taken after stop")))

;; Estimate: 1 test-branch! 1M + 5 transacts and 4 server steps reading each commit (~0.2M each = 1M) + 3 reconcile/index of main (0.2M + 100k x 1 task each ~ 0.3M = 1M) + 1 branch, connect and transact 0.4M ~ 3.4M, about 4e6 ~ 4,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 4000000}} the-fork-server-step
  (ts/test-branch! (fn [cfg conn] (let [st (#'server/server-step {:db @conn :cfg cfg :store conn :conn nil})
        stored (fn [rep] {:op :tx :t (:max-tx @conn) :commit (d/commit-id @conn) :ns 0 :tx-data (pdb/report->tx rep)})]
    (testing "a main commit is read into its replica from the store"
      (let [m (stored (d/transact conn [{:note/text "z" :note/agent :t}]))
            [st outs] (step #'server/server-step st :cmd m)]
        (is (= (:max-tx @conn) (:max-tx (:db st))))
        (is (= [:applied] (map :ev (:ev outs))))
        (testing "and a commit it already holds is skipped"
          (is (= [st nil] (#'server/server-step st :cmd m))))
        (testing "the replica's transaction holds main's transaction facts (its instant, its tx-meta), as stored"
          (let [m2 (stored (d/transact conn {:tx-data [{:note/text "w" :note/agent :t}] :tx-meta {:note/text "the commit's own fact" :note/agent :t}}))
                t (:t m2)
                [st2 _] (step #'server/server-step st :cmd m2)
                instant (fn [d] (:db/txInstant (d/entity d t)))]
            (is (inst? (instant @conn)) "main's transaction has its instant (history is kept)")
            (is (= (instant @conn) (instant (:db st2))))
            (is (= "the commit's own fact" (:note/text (d/entity (:db st2) t))) "and its tx-meta")))
        (testing "main's reconciler index is held for the db it is of, and a fork observes it forward to main now"
          (let [m3 (stored (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name "forked" :seon.task/plan "Make forked true."}])))
                [st3 _] (step #'server/server-step st :cmd m3)
                held (server/index-at-fork st3)]
            (let [s0 (#'server/server-step {:db @conn :cfg cfg :store conn :conn nil})] (is (= (d/commit-id (:db s0)) (get-in s0 [:index :commit])) "made for the commit it holds, never the db"))
            (is (not (contains? (get-in st3 [:index :index :tasks]) "demo/forked")) "a commit leaves it: a fork brings it forward")
            (is (= (d/commit-id (:db st3)) (get-in held [:index :commit])))
            (is (contains? (get-in held [:index :index :tasks]) "demo/forked"))
            (is (= (reconcile/index (:db st3)) (get-in held [:index :index])) "the index listed at that db")
            (is (= (:db st3) (:db (server/index-for-agent held))) "and an agent is handed main's db it is of")
            (testing "and an agent observes its branch from it, read through its own connection"
              (let [ab (pdb/on-main (:branch cfg) :held-index)
                    _ (d/branch! conn (:branch cfg) ab)
                    bc (pdb/connect (assoc cfg :branch ab))]
                (try
                  (d/transact bc (seon.task/create-tx @bc [{:seon.task/ns 'demo :seon.task/name "branched" :seon.task/plan "Make branched true."}]))
                  (let [{idx :index db :db} (server/index-for-agent held) observed (reconcile/index idx db @bc)]
                    (is (contains? (:tasks observed) "demo/branched"))
                    (is (= (reconcile/index @bc) observed) "the branch's index listed"))
                  (finally (d/release bc)))))))))))))

;; Estimate: 1 test-branch! 1M + 18 commits (create-tx + transact ~0.15M each = 2.7M) + 6 fork-server steps (0.2M = 1.2M) + 6 reconciler steps (a notice each, index observed ~0.5M = 3M) + 6 render advance-main (0.15M = 0.9M) + stale-dbs walks ~0.2M ~ 9M, about 8e6 ~ 8,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 8000000}} no-proc-keeps-a-db-older-than-its-branchs-current
  ;; docs/machine.md#roots-heap, a kept db value is a reference (owner, 2026-10-28: "I don't want us
  ;; carrying around large values for no reason"; 2026-10-29): after a run of main commits, each
  ;; proc that holds a db holds main's current one, and its older points as {:commit :t}. A proc's
  ;; state is walked as data (seon.test-store/stale-dbs); the failure names the proc and the path.
  ;; The agent's state is the same walk (seon.agent-test/an-agent-keeps-no-db-older-than-its-branchs-current).
  (ts/test-branch!
   (fn [cfg conn]
     (let [n 6
           commit! (fn [i] (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name (str "t" i) :seon.task/plan (str "Make t" i " true.")}])))
           ;; each proc is judged against main as it stood when its own run of commits ended
           stale (fn [proc st] (for [[path t] (ts/stale-dbs st (:max-tx @conn))] [proc path t]))
           _ (d/transact conn (seon.test-store/root-tx {:seon.system/boot "b" :seon.config.agent/max-running 4}))
           ;; the fork server: every commit is a :tx, and a fork or a standby is the only thing that brings its index to main
           fs (reduce (fn [st i]
                        (let [rep (commit! i)
                              [st _] (step #'server/server-step st :cmd {:op :tx :t (:max-tx @conn) :commit (d/commit-id @conn) :ns 0 :tx-data (pdb/report->tx rep)})]
                          st))
                      (#'server/server-step {:db @conn :cfg cfg :store conn :conn nil}) (range n))
           fs-stale (stale :fork-server fs)
           ;; the reconciler: a notice per commit
           rc (reduce (fn [st i]
                        (let [prev (:max-tx @conn) _ (commit! (+ n i))
                              [st _] (step #'reconcile/reconciler-proc st :main (notice conn prev []))]
                          st))
                      (#'reconcile/reconciler-proc {:boot "b" :conn conn :provider {:kind :scripted :script 'seon.fixtures/reply}}) (range n))
           rc-stale (stale :reconciler rc)
           ;; the render proc's main: each commit's db, advanced by what moved
           rp (reduce (fn [st i] (let [_ (commit! (+ n n i))] (#'seon.render.proc/advance-main st (d/commit-as-db conn (d/commit-id @conn)))))
                      {} (range n))]
       (is (= [] fs-stale) "main's index is kept as a commit, not the db it was listed at")
       (is (= [] rc-stale))
       (is (= [] (stale :render rp)))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.procs-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

;; Estimate: 1 test-branch! 1M + building the union at the first :union (the seed's ~2,000 datoms re-added across branches, 5k x 2,000 = 10M) + 2 commits' datoms added and 1 branch + connect + 2 transacts (~1M) ~ 12M, about 1e7 ~ 10,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} the-writer-keeps-the-union-current
  ;; seon.union through the writer: built at the first :union, then every main commit and
  ;; every agent's commit notice add their datoms, so one query over its answer sees both
  (ts/test-branch! (fn [cfg conn] (let [in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        main (:branch cfg)
        ab (pdb/on-main main :a1)
        union-of (fn [st] (let [[st outs] (step #'writer/writer-proc st :in {:op :union :dest {:rid 9}})] [st (:r (first (:to-api outs)))]))
        bodies (fn [db] (set (d/q '[:find ?b ?br :where [?m :seon.message/body ?b ?tx] [?tx :seon.tx/branch ?br]] db)))]
    (d/branch! conn main ab)
    (let [[st r] (union-of st)
          _ (is (true? (:ok r)))
          [st _] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:seon.message/id "m1" :seon.message/from {:seon.system/id :root} :seon.message/body "from main"}] :dest {:rid 1}})
          bc (pdb/connect (assoc cfg :branch ab))
          _ (d/transact bc {:tx-data [{:seon.message/id "r1" :seon.message/from {:seon.agent/key "a1"} :seon.message/body "from a1"}] :tx-meta (pdb/tx-meta ab)})
          _ (d/release bc)
          [st _] (step #'writer/writer-proc st :in {:op :agent-commit :branch :a1})
          [_ r2] (union-of st)]
      (is (= #{["from main" main] ["from a1" ab]} (bodies (:db r2))) "a main commit and an agent's commit, each under its branch"))))))

;; Estimate: 4 step-fn calls x 10k (pure state in, state out) ~ 40,000 ticks
(deftest ^{:seon.test/estimate {:ticks 40000}} a-fork-server-step-that-throws-answers-with-the-error-and-its-state-is-unchanged
  ;; docs/processes.md#a-step-that-throws: the server's step caught what it threw (a thrown step left the
  ;; process alive and silent, or dead, with every request it took unanswered); the event names the op
  ;; and carries the typed error, a fork's received socket is closed, and root's proc answers the asker
  (let [step-failed? #(= :seon.fork-server/step-failed (get-in % [:error :seon.error/type]))]
    (testing "a step with no db to read answers its stats request the error, its state as it was"
      (let [st {:db nil}
            [st' outs] (#'server/server-step st :cmd {:op :stats})]
        (is (identical? st st'))
        (is (= [:step-failed] (map :ev (:ev outs))))
        (is (step-failed? (first (:ev outs))))
        (is (= :stats (:op (first (:ev outs)))))))
    (testing "a fork that throws before it forks closes the socket that came with it"
      (let [[a b] (sock/socketpair) [fa fpeer] (sock/socketpair)
            conn (sock/conn b)
            _ (sock/send! a {:op :fork :agent :a1} fa)
            m (loop [] (let [f (sock/next-frame! conn)] (if (= :none f) (recur) f)))
            fd (first @(:fds conn))
            st {:db nil :conn conn :graph-moved true}
            [st' outs] (#'server/server-step st :cmd m)]
        (is (some? fd) "a descriptor came with the request")
        (is (identical? st st'))
        (is (= {:ev :step-failed :op :fork :agent :a1} (select-keys (first (:ev outs)) [:ev :op :agent])))
        (is (empty? @(:fds conn)) "taken from the pending descriptors")
        (is (= -1 (seon.lib/c-close fd)) "and closed")
        (sock/close! fa) (sock/close! fpeer) (sock/close! a) (sock/close! b)))
    (testing "root's proc answers the waiter the stats request is, and fails the fork"
      (let [err (#'server/step-failed {:op :stats} (ex-info "boom" {}))
            [st outs] (step #'server/fork-server-proc
                            {:waiters (conj clojure.lang.PersistentQueue/EMPTY {:rid 5 :req {:op :stats}}) :pid 1 :log []}
                            :ev {:ev :step-failed :op :stats :error (:error err)})]
        (is (= [5] (keep #(get-in % [:dest :rid]) (:to-api outs))))
        (is (step-failed? {:error (:r (first (:to-api outs)))}))
        (is (empty? (:waiters st))))
      (let [[afd apeer] (sock/socketpair)
            err (#'server/step-failed {:op :fork :agent :a1} (ex-info "boom" {}))
            [st outs] (step #'server/fork-server-proc {:waiters clojure.lang.PersistentQueue/EMPTY :pid 1 :log [] :in-flight {:a1 afd}}
                            :ev {:ev :step-failed :op :fork :agent :a1 :error (:error err)})]
        (is (= [:fork-failed :step-failed] (map :ev (:to-api outs))))
        (is (string? (:reason (first (:to-api outs)))))
        (is (empty? (:in-flight st)))
        (is (= -1 (sock/close! afd)) "root's copy of the agent's socket is closed")
        (sock/close! apeer)))))

;; Estimate: a few pure step calls ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} the-fork-servers-stop-ends-every-child-before-it-answers
  ;; bin/check lifecycle's :left (docs/processes.md#a-stop-reaps-every-child): root SIGKILLed the fork
  ;; server while an agent that had closed its socket was still exiting, and launchd
  ;; inherited it, alive after stop!. Now the server's stop kills and reaps its children
  ;; before it answers, so root's wait for the server is the end of every one of them.
  (let [p (.start (ProcessBuilder. ^java.util.List ["sleep" "600"]))
        pid (.pid p)]
    (is (zero? (seon.lib/c-kill pid 0)) "the child is running")
    (server/server-step {:children #{pid} :status-buf (jolt.ffi/alloc 4)} ::flow/stop)
    (is (= -1 (seon.lib/c-kill pid 0)) "and gone when the stop answers: killed and reaped")))

;; Estimate: a few pure step calls ~ 50,000 ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} an-agents-memory-alone-moves-no-main
  ;; root wrote an agent's memory on every change, so main moved five times under three reads of a
  ;; working agent (seon.server-test/a-later-read-draws-no-page-cold-and-costs-what-the-one-before-did)
  (let [st {:boot "b1" :agents {} :pending {} :rid 0 :view {:agents {:a1 {:status :busy :pid 7 :mem-mb 151.66}}}}
        o (volatile! {})
        writes #(:to-writer @o)
        rec (fn [st before] (vreset! o {}) (#'root/record-agents st o before) (writes))]
    (is (empty? (rec (assoc-in st [:view :agents :a1 :mem-mb] 152.42) (get-in st [:view :agents])))
        "memory alone writes nothing")
    (let [[w] (rec (update-in st [:view :agents :a1] assoc :status :idle :mem-mb 152.42) (get-in st [:view :agents]))]
      (is (some #(= :idle (:seon.agent/status %)) (:tx-data w)) "another change writes that change")
      (is (not-any? #(contains? % :seon.process/mem-mb) (:tx-data w)) "and no memory: nothing reads it as a fact"))))

;; docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08: one live process per agent (docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08)
;; Estimate: 1 test-branch! x 1M; 3 step-fn calls x 0.1M (a transact or a commit read in each) ~ 1,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} back-leaves-one-live-process-for-the-agent
  (spawning-api
   (fn [st]
     (let [live (fn [st] (vec (sort (keys (:agents st)))))
           [st _] (step #'root/api-proc st :ext {:op :spawn :opts {:key "a3"} :p (promise)})
           b (get-in st [:agents :a3 :branch])
           st (-> st (assoc-in [:view :agents :a3 :status] :idle) (assoc-in [:seon.system/backing :a3] {:p (promise) :branch b}))
           [st1 outs1] (step #'root/api-proc st :from-writer {:dest {:back :a3} :r {:ok true}})]
       (is (= [:a3] (live st)) "an agent started under a session's key is named by it")
       (is (= [:a3] (live st1)) "the old process is the one live process at the Back")
       (is (= [{:op :kill}] (get outs1 [:a3 :in])) "and it is told to stop")
       (is (empty? (filter #(= :spawn (:op %)) (:to-writer outs1))) "nothing is forked while it lives")
       (let [[st2 outs2] (step #'root/api-proc st1 :back {:op :ended :agent :a3 :inflight []})
             spawns (filter #(= :spawn (:op %)) (:to-writer outs2))]
         (is (= [:a3] (live st2)) "its end seen, the new process takes its place: one live process")
         (is (= [[:a3 "a3"]] (mapv (juxt :agent :key) spawns)) "forked once, as the same agent, its id's name its key")
         (is (empty? (:seon.system/respawn st2)) "nothing waits to be forked")
         (is (= :spawning (get-in st2 [:view :agents :a3 :status])) "and the view holds the new process, not the old"))))))

;; Estimate: 3 step-fn calls x 10k (pure state in, state out) ~ 30,000 ticks
(deftest ^{:seon.test/estimate {:ticks 30000}} a-failed-standby-is-a-recorded-problem
  ;; docs/processes.md#a-step-that-throws: the fork server's :standby-failed event reaches root's api,
  ;; which records a platform problem naming the threads and shows the server has no standby
  (let [st (#'root/api-proc {:boot "b1" :ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                             :view {:agents {} :fork-server {:status :up :pid 5 :standby true}} :graph (promise)
                             :system (doto (promise) (deliver {:procs {} :conns []}))})
        [st2 _] (step #'root/api-proc st :back {:ev :standby-failed :gen 3 :active-threads 7})
        fold (pr-str (:seon.system/errors st2))]
    (is (re-find #":seon.fork-server/standby-failed" fold) fold)
    (is (re-find #"7" fold) fold)
    (is (= false (get-in st2 [:view :fork-server :standby])) "the view shows the server has no standby")
    (is (= :up (get-in st2 [:view :fork-server :status])) "the server itself still serves")
    (is (= true (get-in (first (step #'root/api-proc st2 :back {:ev :standby :gen 4 :pid 9})) [:view :fork-server :standby]))
        "the next standby made: the view shows it has one again")))

(deftest ^{:seon.test/estimate {:ticks 50000}} a-forking-process-holds-only-io-procs
  ;; the one-unit spec's law 1 (docs/processes.md#what-a-fork-inherits): a process that forks holds only :workload :io procs,
  ;; so its procs run as fibers and a fork finds one active thread. An agent forks its own children: every step of its graph
  ;; (seon.repl/agent-steps, what serve! builds from) is :io. Blocking foreign work (:mixed: the writer, the flusher, fx workers,
  ;; render) runs only in root, which forks nothing after boot
  (doseq [[pid step] seon.repl/agent-steps]
    (is (= :io (:workload (step))) (str "an agent's " pid " proc is :io, not " (:workload (step))))))

(defn- note-texts
  "The texts of the notes the test wrote (prefix p) on the connection's head."
  [conn p] (set (filter #(.startsWith ^String % p) (d/q '[:find [?t ...] :where [?e :note/text ?t]] @conn))))

;; docs/flow.md#the-step-fn-contract-every-proc-follows (paused, a proc reads only control; its in-port keeps what is sent).
;; Replaces the backpressure check step's claim. Pausing is Flow's, not the writer's: the writer's transition arity is
;; identity, so "held" is what the step-fn never being called leaves: the writes sit on :in, uncommitted and unanswered.
;; Resumed, the first step takes one and drains the rest of the batch from :in (seon.writer/drain), FIFO.
;; Estimate: 1 test branch (~2 ms) + 5 one-note transacts in ONE group commit + 1 query ~ 80,000,000 ticks: test-branch! (the seed's ~2,000 datoms through the schema check) is ~99% of it, measured 70.8M, the steps themselves under 1M
;; Red if: drain's `(conj acc y)` becomes `(conj (list) ...)`-style reversal, i.e. answers reverse the order sent.
(deftest ^{:seon.test/estimate {:ticks 80000000}} a-paused-writer-holds-every-write-and-resumed-answers-them-in-order
  (ts/test-branch! (fn [cfg conn]
    (let [in (a/chan 16)
          st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
          req (fn [i] {:op :tx :branch :db :tx-data [{:note/text (str "held-" i) :note/agent :t}] :dest {:rid i}})]
      (is (= st (#'writer/writer-proc st ::flow/pause)) "the pause transition leaves the state as it is")
      (doseq [i (range 5)] (a/>!! in (req i)))
      (testing "paused: no step runs, so nothing is committed and nothing answered"
        (is (= #{} (note-texts conn "held-")))
        (is (= 0 (:commits st))))
      (testing "resumed: the first send starts the step, which takes the rest, and every one is answered in the order sent"
        (let [m (a/poll! in)
              [st2 outs] (step #'writer/writer-proc st :in m)
              answers (filter :dest (:to-api outs))]
          (is (nil? (a/poll! in)) "the step drained :in")
          (is (= [{:rid 0} {:rid 1} {:rid 2} {:rid 3} {:rid 4}] (mapv :dest answers)))
          (is (every? #(true? (:ok (:r %))) answers))
          (is (= 5 (:tx-count st2)))
          (is (= (set (map #(str "held-" %) (range 5))) (note-texts conn "held-")))))))))

;; docs/flow.md (a closed :in is the end of the flow: everything taken is committed and flushed, then :drained goes out) and
;; docs/storage.md#durability-is-continuous-group-commit. Replaces the drain check step's claim. NOT as stated: the writer's
;; ::flow/stop transition arity does nothing (returns st); the flush at the end is the closed-:in step (message nil).
;; Each write is committed and answered in its own step; the end step flushes (a dirty durable writer) and says :drained.
;; Estimate: 1 test branch + 3 transacts + 1 sync! (no-op unless lmdb) + a fresh connect and query ~ 80,000,000 ticks: test-branch! ~99% of it (measured 71.0M); the steps, one sync! and the fresh connect are under 1M
;; Red if: the nil-message arity returns without sync!/`:drained`, or commit-batch skips `(pdb/transact-checked ...)` so :ok answers lie.
(deftest ^{:seon.test/estimate {:ticks 80000000}} at-its-end-every-write-the-writer-answered-ok-is-in-the-store-opened-again
  (ts/test-branch! (fn [cfg conn]
    (let [in (a/chan 16)
          st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? true :to-writer in :events (a/chan 16)})
          st (reduce (fn [st i]
                       (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text (str "end-" i) :note/agent :t}] :dest {:rid i}})]
                         (is (true? (:ok (:r (first (filter :dest (:to-api outs)))))) (str "answered ok: " i))
                         st))
                     st (range 3))]
      (is (= st (#'writer/writer-proc st ::flow/stop)) "the stop transition is the identity: the writer's end is the closed :in")
      (is (true? (:dirty st)) "committed, not yet flushed")
      (let [[st2 outs] (step #'writer/writer-proc st :in nil)]
        (is (false? (:dirty st2)) "the end flushed what was taken")
        (is (= [{:op :drained :tx-count 3}] (:to-api outs))))
      (testing "a fresh connection reads every write answered :ok"
        (let [c2 (pdb/connect cfg)]
          (try (is (= #{"end-0" "end-1" "end-2"} (note-texts c2 "end-")))
               (finally (d/release c2)))))))))
