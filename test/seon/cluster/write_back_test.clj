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
            [seon.test-support :as support]))

(defn- fixture-source
  "One small namespace: a callee and its caller. The test reaching both is the
  agent's, written at its REPL, as a lane writes one."
  [fixture-ns]
  ;; the file requires clojure.test: the agent's tests name it, and write-back
  ;; refuses a candidate file whose analysis finds an unrequired namespace
  (str "(ns " fixture-ns "\n  \"Write-back fixture.\"\n  (:require [clojure.test]))\n\n"
       "(defn callee\n  \"Add one.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 1))\n\n"
       "(defn caller\n  \"Call the callee.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (callee x))\n"))

(defn- digests
  "Definition digest by identity of each declaration in `entities`."
  [entities]
  (into {} (keep #(when-let [d (:seon.program/definition-digest %)] [(program/row-identity %) d])) entities))

(defn submit!
  "One ordinary agent turn of `text` on `request`'s agent, observed closed; its turn id."
  {:malli/schema [:=> [:cat :map :string] [:maybe :string]]}
  [request text]
  (let [connection (get-in request [:seon.turn.loop/cluster :seon.db/connection])
        ;; The agent's work is its own branch's: under the member's custody the
        ;; writer refuses a write naming the candidate branch.
        submitted (db/call-with-custody {:seon.db/connection connection}
                                        #(agent/evaluate-source! (assoc request :seon.cluster.reply/text text)))
        run-id (:seon.turn/id submitted)]
    (is run-id "the agent accepted the submission")
    (when run-id
      (support/await-event! connection ::turn-closed
                            (fn [_] (:seon.turn/closed-tx (db/pull (db/db connection) [:seon.turn/closed-tx]
                                                                    [:seon.turn/id run-id])))))
    run-id))

(defn with-agent!
  "Create `agent-id` in `fixture-ns` on `connection`'s branch, arm it, call
  `body` with its request, then disarm it and close its channels; nil."
  {:malli/schema [:=> [:cat :seon.db/connection :string :string :symbol [:fn clojure.core/ifn?]] :nil]}
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
          nil
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
        proofs #{(symbol (str fixture-ns) "caller-check")}
        c (keyword (str "write-back-c-" suffix))
        agent-id (str "write-back-" suffix)
        made (volatile! [c])]
    (io/make-parents file)
    (spit file (fixture-source fixture-ns))
    (try
      ;; The fixture file indexed onto H by the ordinary analyzer, as publication would.
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            base-text (slurp file)
            [start stop] (:seon.fn/form-span (first (filter #(= callee (program/row-identity %)) entities)))
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            ;; The test is the agent's own, written at its REPL on H before the lane forks.
            _ (with-agent! h-conn cluster-name (str agent-id "-author") fixture-ns
                #(submit! % "(clojure.test/deftest caller-check (clojure.test/is (= 3 (caller 2))))"))
            before (digests entities)
            _ (registry/branch! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          ;; The agent is assigned the fixture namespace on C, so its `defn` replaces the file's declaration.
          (with-agent! c-conn cluster-name agent-id fixture-ns #(submit! % replacement))
          (testing "the agent's evaluation settled one replacement entity on its branch"
            (is (not= (before callee)
                      (:seon.program/definition-digest (db/pull (db/db c-conn) [:seon.program/definition-digest] callee)))))
          (testing "the parent head does not move before the merge"
            (is (= (before callee)
                   (:seon.program/definition-digest (db/pull (db/db h-conn) [:seon.program/definition-digest] callee)))))
          (let [proposal (source/prepare-merge! target store c proofs)
                _ (some->> (:seon.source/candidate proposal) (vswap! made conj))
                _ (is (true? (:seon.test/passed? proposal)))
                accepted (source/accept-merge! target store proposal)
                _ (is (:seon.source/commit-id accepted))]
           ;; Each stage is asserted before the next reads it: a refused merge is
           ;; shown as the merge's own value, never handed to write-back.
           (when (:seon.source/commit-id accepted)
           (let [merged (:seon.program/definition-digest (db/pull (db/db h-conn) [:seon.program/definition-digest] callee))
                request {:seon.db/connection h-conn :seon.source/accepted accepted :seon.fn/root root}
                ;; Before, any write after the accept (root's own recorded run) refused as a moved head.
                _ (support/transacted! h-conn (support/agents-tx (db/db h-conn) [(str "write-back-test-" suffix)]))
                written (source/write-back! request)]
            (testing "the exact span is written back and nothing else changes"
              (is (= [path] (:seon.source/changed-paths written)))
              (is (= [callee] (get-in written [:seon.source/files 0 :seon.source/replaced])))
              (is (= (str (subs base-text 0 start) replacement (subs base-text stop)) (slurp file))))
            (testing "the file reindexed from disk declares exactly the merged program"
              (is (= (assoc before callee merged) (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})))))
            (testing "a second write of the same acceptance refuses: the file is no longer its base"
              (is (= :file-changed (:seon.source/write-back-refused (source/write-back! request)))))
            (testing "a later change to a written entity refuses by name with both digests, before any file is read"
              (support/transacted! h-conn (support/replacement-tx (db/db h-conn) callee "(defn callee [x] (+ 1 x))" {}))
              (let [now (:seon.program/definition-digest (db/pull (db/db h-conn) [:seon.program/definition-digest] callee))]
                (is (= [:row-changed {callee merged} {callee now}]
                       ((juxt :seon.source/write-back-refused :seon.error/expected :seon.error/offending)
                        (source/write-back! request)))))))))
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
        proofs #{(second added-test)}
        c (keyword (str "write-back-add-c-" suffix))
        made (volatile! [c])]
    (io/make-parents file)
    (spit file (fixture-source fixture-ns))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            base-text (slurp file)
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            before (digests entities)
            _ (registry/branch! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (with-agent! c-conn cluster-name (str "write-back-add-" suffix) fixture-ns
            (fn [request] (submit! request function-source) (submit! request test-source)))
          (testing "the head lacks both additions before the merge"
            (is (every? #(nil? (:db/id (db/pull (db/db h-conn) [:db/id] %))) [added-fn added-test])))
          (testing "a selection merges only named changes; a selected test reaching an unselected addition refuses by name"
            (is (= #{added-test} (get-in (source/prepare-merge! target store c proofs #{added-test})
                                         [:seon.error/offending :seon.program/identities])))
            (is (= [[:seon.fn/sym 'absent/declaration]]
                   (:seon.error/offending (source/prepare-merge! target store c proofs #{[:seon.fn/sym 'absent/declaration]})))))
          (let [proposal (source/prepare-merge! target store c proofs #{added-fn added-test})
                _ (some->> (:seon.source/candidate proposal) (vswap! made conj))
                _ (is (true? (:seon.test/passed? proposal)))
                accepted (when (:seon.test/passed? proposal) (source/accept-merge! target store proposal))
                _ (is (:seon.source/commit-id accepted))]
            (when (:seon.source/commit-id accepted)
              (let [written (source/write-back! {:seon.db/connection h-conn :seon.source/accepted accepted :seon.fn/root root})
                    text (slurp file)]
                (testing "both additions are appended to the namespace's file, the rest untouched"
                  (is (= #{added-fn added-test} (set (get-in written [:seon.source/files 0 :seon.source/added]))))
                  (is (str/starts-with? text base-text))
                  (is (str/includes? text function-source))
                  (is (str/includes? text test-source)))
                (testing "the file reindexed from disk declares the merged program"
                  (let [reindexed (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]}))]
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

(defn- merge-world!
  "The fixture file indexed onto H and a lane C where the agent submits
  `forms` (each a function of the fixture namespace), proved by its test
  `test-name`; calls `body` with the world, then retires C and every
  branch `body` conj'd onto `:made`, and deletes the root."
  [label test-name forms body]
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        suffix (id/id)
        fixture-ns (symbol (str label suffix ".core"))
        root (str "tmp/" label "-test-" suffix)
        file (io/file root (str "src/" label suffix "/core.clj"))
        c (keyword (str label "-c-" suffix))
        made (volatile! [c])]
    (io/make-parents file)
    (spit file (fixture-source fixture-ns))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            _ (registry/branch! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (with-agent! c-conn (:seon.cluster/name target) (str label suffix) fixture-ns
            (fn [request] (doseq [form forms] (submit! request (form fixture-ns)))))
          (body {:target target :store store :c c :proofs #{(symbol (str fixture-ns) test-name)} :root root :file file :fixture-ns fixture-ns :made made})
          (finally (agent/release-context! candidate))))
      (finally
        (doseq [b @made] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))
        (support/delete-recursively! root)))))

(deftest ^{:seon.test/long "One agent turn on C and one prepare-merge! whose file proof (one analysis of a one-file fixture) refuses before the gate runs."
           :seon.test/long-ms 30000}
  a-declaration-naming-an-unrequired-namespace-refuses-at-prepare-before-any-test-runs
  ;; The dogfood class: the agent's SCI world resolves `clojure.set/union`
  ;; unrequired, so the merge gate passed and only write-back refused.
  (merge-world! "wbreq" "set-check"
                [(fn [_] "(clojure.test/deftest set-check (clojure.test/is (= #{1 2} (clojure.set/union #{1} #{2}))))")]
                (fn [{:keys [target store c proofs root file made]}]
                  (let [before (slurp file)
                        refused (source/prepare-merge! target store c proofs nil root)]
                    (some->> (:seon.source/candidate refused) (vswap! made conj))
                    (is (= :not-analyzable (:seon.source/write-back-refused refused)))
                    (is (not (:seon.source/candidate refused)) "no intermediate branch, no test run")
                    (is (= before (slurp file)))))))

(deftest ^{:seon.test/long "Two agent turns on C, one prepare-merge! (file proof, then a nested seon.test/run; 60 s is merge-test's measured bound) and two analyses of a one-file fixture."
           :seon.test/long-ms 90000}
  a-require-added-at-the-repl-merges-and-writes-back-its-ns-form
  ;; Namespaces are program entities: the agent's `ns` form adds a require, the
  ;; merge carries that entity with the declaration that needs it, and write-back
  ;; splices the `ns` form's span; the namespace's other declarations are
  ;; re-derived under it, so the reindexed file equals the merged program.
  (let [ns-form #(str "(ns " % "\n  \"Write-back fixture.\"\n  (:require [clojure.set] [clojure.test]))")]
    (merge-world!
     "wbns" "set-check"
     [ns-form (fn [_] "(clojure.test/deftest set-check (clojure.test/is (= #{1 2} (clojure.set/union #{1} #{2}))))")]
     (fn [{:keys [target store proofs c root file fixture-ns made]}]
       (let [h-conn (:seon.db/connection target)
             check [:seon.test/sym (symbol (str fixture-ns) "set-check")]
             namespace-entity [:seon.ns/name fixture-ns]
             proposal (source/prepare-merge! target store c proofs #{check} root)
             _ (some->> (:seon.source/candidate proposal) (vswap! made conj))
             _ (is (true? (:seon.test/passed? proposal)))
             accepted (when (:seon.test/passed? proposal) (source/accept-merge! target store proposal))]
         (is (= #{check namespace-entity} (:seon.program/identities accepted)))
         (when (:seon.source/commit-id accepted)
           (let [written (source/write-back! {:seon.db/connection h-conn :seon.source/accepted accepted :seon.fn/root root})
                 reindexed (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]}))]
             (is (= [namespace-entity] (get-in written [:seon.source/files 0 :seon.source/replaced])))
             (is (str/starts-with? (slurp file) (ns-form fixture-ns)))
             (is (= reindexed
                    (digests (db/pull-many (db/db h-conn) [:seon.fn/sym :seon.test/sym :seon.ns/name :seon.program/definition-digest]
                                           (vec (keys reindexed)))))
                 "the file reindexed from disk declares the merged program"))))))))

(deftest ^{:seon.test/long "Two agent turns on C, one prepare-merge! (file proofs, then a nested seon.test/run; 60 s is merge-test's measured bound) and three analyses of a two-file fixture."
           :seon.test/long-ms 90000}
  a-merge-writes-each-change-into-its-own-file
  ;; Before (2026-09-28 dogfood loop), a merge whose function and proving test
  ;; live in two files refused :one-file: every real change with its regression.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        suffix (id/id)
        fixture-ns (symbol (str "wbtwo" suffix ".core"))
        test-ns (symbol (str fixture-ns "-test"))
        root (str "tmp/wbtwo-test-" suffix)
        paths [(str "src/wbtwo" suffix "/core.clj") (str "src/wbtwo" suffix "/core_test.clj")]
        test-source (str "(ns " test-ns "\n  \"Write-back test fixture.\"\n  (:require [clojure.test] [" fixture-ns "]))\n")
        callee [:seon.fn/sym (symbol (str fixture-ns) "callee")]
        check [:seon.test/sym (symbol (str test-ns) "callee-check")]
        c (keyword (str "wbtwo-c-" suffix))
        made (volatile! [c])]
    (doseq [[path text] (map vector paths [(fixture-source fixture-ns) test-source])]
      (io/make-parents (io/file root path))
      (spit (io/file root path) text))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            _ (registry/branch! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn))})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (with-agent! c-conn (:seon.cluster/name target) (str "wbtwo-" suffix) fixture-ns
            (fn [request]
              (submit! request "(defn callee\n  \"Add one, by increment.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (inc x))")
              (submit! request (str "(in-ns '" test-ns ")\n(clojure.test/deftest callee-check (clojure.test/is (= 3 ("
                                    fixture-ns "/callee 2))))"))))
          (let [proposal (source/prepare-merge! target store c #{(second check)} nil root)
                _ (some->> (:seon.source/candidate proposal) (vswap! made conj))
                accepted (when (:seon.test/passed? proposal) (source/accept-merge! target store proposal))
                written (when (:seon.source/commit-id accepted)
                          (source/write-back! {:seon.db/connection h-conn :seon.source/accepted accepted :seon.fn/root root}))]
            (is (= (sort paths) (:seon.source/changed-paths written)) "both files are written")
            (is (= [[callee] [check]] (mapv #(into (:seon.source/replaced %) (:seon.source/added %)) (:seon.source/files written))))
            (is (= (select-keys (digests (db/pull-many (db/db h-conn) [:seon.fn/sym :seon.test/sym :seon.program/definition-digest]
                                                        [callee check]))
                                [callee check])
                   (select-keys (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})) [callee check]))
                "each file reindexed from disk declares the merged program"))
          (finally (agent/release-context! candidate))))
      (finally
        (doseq [b @made] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))
        (support/delete-recursively! root)))))

(deftest ^{:seon.test/long "Two analyses of a two-file fixture onto H, one replacement on a branch C, and one write-back (one analysis of the candidate)."
           :seon.test/long-ms 20000}
  a-branch-change-calling-another-first-party-namespace-writes-back
  ;; A lane's `defn` on its own branch is written back with no merge: the
  ;; accepted value names H's commit as the base and C's head as the change.
  ;; The callee calls a first-party function of another namespace, which the
  ;; candidate's analysis classifies from C's program, not from clj-kondo's
  ;; dependency cache.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        suffix (id/id)
        helper-ns (symbol (str "wbfp" suffix ".helper"))
        core-ns (symbol (str "wbfp" suffix ".core"))
        root (str "tmp/write-back-first-party-test-" suffix)
        core-file (io/file root (str "src/wbfp" suffix "/core.clj"))
        callee [:seon.fn/sym (symbol (str core-ns) "callee")]
        replacement "(defn callee\n  \"Add one through the helper.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (helper/helper x))"
        c (keyword (str "write-back-first-party-c-" suffix))]
    (io/make-parents core-file)
    (spit (io/file root (str "src/wbfp" suffix "/helper.clj"))
          (str "(ns " helper-ns ")\n\n(defn helper\n  \"Add one.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 1))\n"))
    (spit core-file
          (str "(ns " core-ns "\n  (:require [" helper-ns " :as helper]))\n\n"
               "(defn callee\n  \"Add one.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (helper/helper x))\n"))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            base (db/commit-id (db/db h-conn))
            _ (registry/branch! {:seon.store/store store :seon.store/branch c :seon.cluster.registry/from base})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (support/transacted! c-conn (support/replacement-tx (db/db c-conn) callee replacement {}))
          (let [written (source/write-back! {:seon.db/connection c-conn :seon.fn/root root
                                             :seon.source/accepted {:seon.program/identities #{callee}
                                                                    :seon.source/base-commit-id base
                                                                    :seon.source/commit-id (db/commit-id (db/db c-conn))}})]
            (is (= [callee] (get-in written [:seon.source/files 0 :seon.source/replaced]))
                "the branch's replacement is written into its file")
            (is (= (:seon.program/definition-digest (db/pull (db/db c-conn) [:seon.program/definition-digest] callee))
                   (get (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})) callee))
                "the file reindexed from disk declares the branch's definition"))
          (finally (agent/release-context! candidate))))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch c})
        (support/delete-recursively! root)))))
