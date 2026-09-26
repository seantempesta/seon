(ns seon.cluster.write-back-test
  "The REPL-to-file loop: an agent's `defn` on a branch, merged, written back
  into its source file as an exact byte span, and reindexed from disk
  (`seon.cluster.source/write-back!`)."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
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
  "One small namespace: a callee and its caller. The test reaching both is the
  agent's, written at its REPL, as a lane writes one."
  [fixture-ns]
  (str "(ns " fixture-ns "\n  \"Write-back fixture.\")\n\n"
       "(defn callee\n  \"Add one.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 1))\n\n"
       "(defn caller\n  \"Call the callee.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (callee x))\n"))

(defn- digests
  "Definition digest by identity of each declaration in `rows`."
  [rows]
  (into {} (keep #(when-let [d (:seon.program/definition-digest %)] [(program/row-identity %) d])) rows))

(defn- submit!
  "One ordinary agent turn of `text` on `request`'s agent, observed closed."
  [request text]
  (let [connection (get-in request [:seon.turn.loop/cluster :seon.db/connection])
        ;; The agent's work is its own branch's: under the member's custody the
        ;; writer refuses a write naming the candidate branch.
        submitted (db/call-with-custody {:seon.db/connection connection}
                                        #(turn/virtual-turn! (assoc request :seon.cluster.reply/text text)))
        run-id (:seon.turn/id submitted)]
    (is run-id (str "the agent accepted the submission: " (pr-str submitted)))
    (when run-id
      (support/await-event! connection ::turn-closed
                            (fn [_] (:seon.turn/closed-tx (db/pull (db/db connection) [:seon.turn/closed-tx]
                                                                    [:seon.turn/id run-id])))))
    run-id))

(defn- with-agent!
  "Create `agent-id` in `fixture-ns` on `connection`'s branch, arm it, call
  `body` with its request, then disarm it and close its channels."
  [connection cluster-name agent-id fixture-ns body]
  (let [environment (support/environment cluster-name connection)
        ctx (support/fork-cluster-ctx connection cluster-name)
        routing (support/attempt-routing connection)
        faults (async/chan (async/sliding-buffer 16))]
    (with-open [launcher (support/closeable
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                  flow/flow-workload-attributes)})
                          flow/stop-work-launcher!)]
      (let [handle (support/cluster-handle
                    {:seon.env/environment environment :seon.db/connection connection :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                     :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id}]
        (swap! routing assoc :seon.agent/fault-channel faults)
        (support/transacted! connection (support/agent-tx @connection agent-id fixture-ns))
        (agent/arm! request)
        (try
          (body request)
          (finally
            (agent/disarm! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))

(deftest ^{:seon.test/long "Two agent turns (the test on H, the replacement on C), one prepare-merge! (a nested seon.test/run on a new candidate commit; 60 s is merge-test's measured bound for one prepare) and three analyses of a one-file fixture."
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
            ;; The test is the agent's own, written at its REPL on H before the lane forks.
            _ (with-agent! h-conn cluster-name (str agent-id "-author") fixture-ns
                #(submit! % "(clojure.test/deftest caller-check (clojure.test/is (= 3 (caller 2))))"))
            _ (support/transacted! h-conn [{:seon.issue/id issue :seon.issue/title "Write back" :seon.issue/status :open
                                            :seon.issue/severity :cleanup :seon.issue/problem "Replace the callee."
                                            :seon.issue/tests #{[:seon.test/sym (symbol (str fixture-ns) "caller-check")]}}])
            before (digests rows)
            _ (registry/branch! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          ;; The agent is assigned the fixture namespace on C, so its `defn` replaces the file's declaration.
          (with-agent! c-conn cluster-name agent-id fixture-ns #(submit! % replacement))
          (testing "the agent's evaluation settled one replacement row on its branch"
            (is (not= (before callee)
                      (:seon.program/definition-digest (db/pull (db/db c-conn) [:seon.program/definition-digest] callee)))))
          (testing "the parent head does not move before the merge"
            (is (= (before callee)
                   (:seon.program/definition-digest (db/pull (db/db h-conn) [:seon.program/definition-digest] callee)))))
          (let [proposal (source/prepare-merge! target store c issue)
                _ (some->> (:seon.source/candidate proposal) (vswap! made conj))
                _ (is (true? (:seon.test/passed? proposal)) (pr-str proposal))
                report (source/accept-merge! target store proposal)
                _ (is (:db-after report) (pr-str report))]
           ;; Each stage is asserted before the next reads it: a refused merge is
           ;; shown as the merge's own value, never handed to write-back.
           (when (:db-after report)
           (let [merged (:seon.program/definition-digest (db/pull (db/db h-conn) [:seon.program/definition-digest] callee))
                request {:seon.db/connection h-conn :seon.source/accepted report :seon.fn/root root}
                written (source/write-back! request)]
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
              (is (= :head-moved (:seon.source/write-back-refused (source/write-back! request))))))))
          (finally
            (agent/release-context! candidate))))
      (finally
        (doseq [b @made] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))
        (support/delete-recursively! root)))))

(deftest ^{:seon.test/long "One agent turn on C, one prepare-merge! (a nested seon.test/run on a new candidate commit; 60 s is merge-test's measured bound for one prepare) and two analyses of a one-file fixture."
           :seon.test/long-ms 90000}
  an-agent-addition-merges-and-writes-back-both-declarations
  ;; An agent adds a function and its test at the REPL on its lane; the merge
  ;; carries both as additions and write-back appends both to the namespace's file.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        suffix (id/id)
        fixture-ns (symbol (str "wbadd" suffix ".core"))
        path (str "src/wbadd" suffix "/core.clj")
        root (str "tmp/write-back-add-test-" suffix)
        file (io/file root path)
        added-fn [:seon.fn/sym (symbol (str fixture-ns) "triple")]
        added-test [:seon.test/sym (symbol (str fixture-ns) "triple-check")]
        function-source "(defn triple\n  \"Triple.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (* 3 x))"
        test-source "(clojure.test/deftest triple-check (clojure.test/is (= 9 (triple 3))))"
        issue (str "write-back-add-" suffix)
        c (keyword (str "write-back-add-c-" suffix))
        made (volatile! [c])]
    (io/make-parents file)
    (spit file (fixture-source fixture-ns))
    (try
      (let [rows (fn/analyze-rows {:seon.fn/root root :seon.fn/roots ["src"]})
            base-text (slurp file)
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) rows (mapv program/row-identity rows)))
            before (digests rows)
            _ (registry/branch! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (with-agent! c-conn cluster-name (str "write-back-add-" suffix) fixture-ns
            (fn [request] (submit! request function-source) (submit! request test-source)))
          ;; The issue lives on the lane with the test it names.
          (support/transacted! c-conn [{:seon.issue/id issue :seon.issue/title "Add triple" :seon.issue/status :open
                                        :seon.issue/severity :cleanup :seon.issue/problem "Add a triple function."
                                        :seon.issue/tests #{added-test}}])
          (testing "the head lacks both additions before the merge"
            (is (every? #(nil? (:db/id (db/pull (db/db h-conn) [:db/id] %))) [added-fn added-test])))
          (let [proposal (source/prepare-merge! target store c issue)
                _ (some->> (:seon.source/candidate proposal) (vswap! made conj))
                _ (is (true? (:seon.test/passed? proposal)) (pr-str proposal))
                report (when (:seon.test/passed? proposal) (source/accept-merge! target store proposal))
                _ (is (:db-after report) (pr-str report))]
            (when (:db-after report)
              (let [written (source/write-back! {:seon.db/connection h-conn :seon.source/accepted report :seon.fn/root root})
                    text (slurp file)]
                (testing "both additions are appended to the namespace's file, the rest untouched"
                  (is (= #{added-fn added-test} (set (get-in written [:seon.source/written :seon.source/added]))) (pr-str written))
                  (is (str/starts-with? text base-text))
                  (is (str/includes? text function-source))
                  (is (str/includes? text test-source)))
                (testing "the file reindexed from disk declares the merged program"
                  (let [reindexed (digests (fn/analyze-rows {:seon.fn/root root :seon.fn/roots ["src"]}))]
                    (is (= (set (conj (keys before) added-fn added-test)) (set (keys reindexed))))
                    (is (= (select-keys (digests (db/pull-many (db/db h-conn) [:seon.fn/sym :seon.test/sym :seon.program/definition-digest]
                                                                [added-fn added-test]))
                                        [added-fn added-test])
                           (select-keys reindexed [added-fn added-test]))))))))
          (finally
            (agent/release-context! candidate))))
      (finally
        (doseq [b @made] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))
        (support/delete-recursively! root)))))
