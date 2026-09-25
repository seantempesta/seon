(ns seon.cluster.write-back-test
  "The REPL-to-file loop: an agent's `defn` on a branch, merged, written back
  into its source file as an exact byte span, and reindexed from disk
  (`seon.cluster.source/write-back!`)."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.fn :as fn]
            [seon.id :as id]
            [seon.program :as program]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- fixture-source
  "One small namespace: a callee, its caller and the test that reaches both."
  [fixture-ns]
  (str "(ns " fixture-ns "\n  \"Write-back fixture.\"\n  (:require [clojure.test :as t]))\n\n"
       "(defn callee\n  \"Add one.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 1))\n\n"
       "(defn caller\n  \"Call the callee.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (callee x))\n\n"
       "(t/deftest caller-check\n  (t/is (= 3 (caller 2))))\n"))

(defn- digests
  "Definition digest by identity of each declaration in `rows`."
  [rows]
  (into {} (keep #(when-let [d (:seon.program/definition-digest %)] [(program/row-identity %) d])) rows))

(defn- submit!
  "One ordinary agent turn of `text` on `request`'s agent, observed closed."
  [request text]
  (let [connection (get-in request [:seon.turn.loop/cluster :seon.db/connection])
        run-id (:seon.turn/id (turn/virtual-turn! (assoc request :seon.cluster.reply/text text)))]
    (is run-id "the agent accepted the submission")
    (support/await-event! connection ::turn-closed
                          (fn [_] (:seon.turn/closed-tx (db/pull (db/db connection) [:seon.turn/closed-tx]
                                                                  [:seon.turn/id run-id]))))
    run-id))

(deftest ^{:seon.test/long "One agent turn, one prepare-merge! (a nested seon.test/run on a new candidate commit; 60 s is merge-test's measured bound for one prepare) and three analyses of a one-file fixture."
           :seon.test/long-ms 90000}
  an-agent-replacement-merges-and-writes-back-its-exact-span
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        suffix (id/id)
        fixture-ns (symbol (str "wbfix" suffix ".core"))
        path (str "src/wbfix" suffix "/core.clj")
        root (str "tmp/write-back-test-" suffix)
        file (io/file root path)
        callee [:seon.fn/sym (symbol (str fixture-ns) "callee")]
        replacement "(defn callee\n  \"Add one, by increment.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (inc x))"
        issue (str "write-back-test-" suffix)
        c (keyword (str "write-back-c-" suffix))
        agent-id (str "write-back-" suffix)
        made (volatile! [c])]
    (io/make-parents file)
    (spit file (fixture-source fixture-ns))
    (try
      ;; The fixture file indexed onto H by the ordinary analyzer, as publication would.
      (let [rows (fn/analyze-rows {:seon.fn/root root :seon.fn/roots ["src"]})
            base-text (slurp file)
            [start stop] (:seon.fn/form-span (first (filter #(= callee (program/row-identity %)) rows)))
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) rows (mapv program/row-identity rows)))
            _ (support/transacted! h-conn [{:seon.issue/id issue :seon.issue/title "Write back" :seon.issue/status :open
                                            :seon.issue/severity :cleanup :seon.issue/problem "Replace the callee."
                                            :seon.issue/tests #{[:seon.test/sym (symbol (str fixture-ns) "caller-check")]}}])
            before (digests rows)
            _ (registry/branch! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)
            environment (support/environment cluster-name c-conn)
            ctx (support/fork-cluster-ctx c-conn cluster-name)
            routing (support/attempt-routing c-conn)
            faults (async/chan (async/sliding-buffer 16))]
        (try
          (with-open [launcher (support/closeable
                                (flow/start-work-launcher!
                                 {:seon.env/environment environment
                                  :seon.flow/configuration (select-keys (config/effective (db/db c-conn) cluster-name)
                                                                        flow/flow-workload-attributes)})
                                flow/stop-work-launcher!)]
            (let [handle (support/cluster-handle
                          {:seon.env/environment environment :seon.db/connection c-conn :seon.cluster/name cluster-name
                           :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                           :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                           :seon.db.process/id cluster/boot-process-identity})
                  request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id}]
              (swap! routing assoc :seon.agent/fault-channel faults)
              ;; The agent is assigned the fixture namespace on C, so its `defn` replaces the file's declaration.
              (support/transacted! c-conn (support/agent-tx @c-conn agent-id fixture-ns))
              (agent/arm! request)
              (try
                (submit! request replacement)
                (finally
                  (agent/disarm! request)
                  (doseq [channel [faults (:seon.cluster.wake/channel handle)
                                   (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
                    (async/close! channel))))))
          (testing "the agent's evaluation settled one replacement row on its branch"
            (is (not= (before callee)
                      (:seon.program/definition-digest (db/pull (db/db c-conn) [:seon.program/definition-digest] callee)))))
          (let [proposal (source/prepare-merge! target store c issue)
                _ (some->> (:seon.source/candidate proposal) (vswap! made conj))
                report (source/accept-merge! target store proposal)
                merged (:seon.program/definition-digest (db/pull (db/db h-conn) [:seon.program/definition-digest] callee))
                request {:seon.db/connection h-conn :seon.source/accepted report :seon.fn/root root}
                written (source/write-back! request)]
            (is (true? (:seon.test/passed? proposal)) (:seon.source/tally proposal))
            (is (:db-after report) (pr-str (select-keys report [:seon.error/message :seon.error/offending])))
            (testing "the exact span is written back and nothing else changes"
              (is (= [path] (:seon.source/changed-paths written)) (pr-str written))
              (is (= [callee] (get-in written [:seon.source/written :seon.source/replaced])))
              (is (= (str (subs base-text 0 start) replacement (subs base-text stop)) (slurp file))))
            (testing "the file reindexed from disk declares exactly the merged program"
              (is (= (assoc before callee merged) (digests (fn/analyze-rows {:seon.fn/root root :seon.fn/roots ["src"]})))))
            (testing "a second write of the same acceptance refuses: the file is no longer its base"
              (is (= :file-changed (:seon.source/write-back-refused (source/write-back! request)))))
            (testing "a moved head refuses before any file is read"
              (support/transacted! h-conn [{:seon.issue/id issue :seon.issue/title "Written back"}])
              (is (= :head-moved (:seon.source/write-back-refused (source/write-back! request))))))
          (finally
            (agent/release-context! candidate))))
      (finally
        (doseq [b @made] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))
        (support/delete-recursively! root)))))
