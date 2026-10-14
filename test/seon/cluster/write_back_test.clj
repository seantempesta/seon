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
            [seon.task :as task]
            [seon.test-support :as support]))

(defn- proved!
  "Index the one task these merges prove, `tq.write-back/fix`, on `connection`; its symbol, each proof's tag."
  [connection]
  (task/index-tasks! {:seon.db/connection connection
                      :seon.task/files {"resources/seon/tasks/tq.write-back.edn"
                                        "[#:seon.task{:name \"fix\" :intent \"Merge and write back the tested change.\"}]"}})
  'tq.write-back/fix)

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
        submitted (db/with-branch {:seon.db/connection connection}
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
        (agent/start-flow! request)
        (try
          (body request)
          nil
          (finally
            (agent/stop-flow! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))

(deftest write-back-refuses-a-root-that-is-not-an-absolute-checkout-and-writes-nothing
  ;; A Seon JVM runs from its committed archive (data/source/<sha>), so a relative root
  ;; such as "." resolved into the archive; write-back refuses such a root before reading anything.
  (let [conn (:seon.db/connection (support/execution-handle nil))
        base (support/temp-root "wb-root-refusal")
        archive (io/file base "data" "source" "abc123")
        sentinel (io/file archive "src" "x.clj")
        accepted {:seon.program/identities #{}
                  :seon.source/base-commit-id (random-uuid) :seon.source/commit-id (random-uuid)}
        refusal (fn [root] (source/write-back! {:seon.db/connection conn :seon.source/accepted accepted :seon.fn/root root}))]
    (io/make-parents sentinel)
    (spit sentinel "(ns x)")
    (try
      (doseq [root ["." "tmp/checkout" (str base "/missing") (.getPath archive) (.getPath (io/file archive "src"))]]
        (let [refused (refusal root)]
          (testing "a relative, missing or archive-internal root is refused naming the root"
            (is (= [:root-refused root]
                   ((juxt :seon.source/write-back-refused :seon.error/offending) refused))))))
      (is (= "(ns x)" (slurp sentinel)) "nothing was written")
      (is (= :commit-absent (:seon.source/write-back-refused (refusal base))) "an absolute existing directory outside the archive is not refused for its root and reaches the write")
      (finally (support/delete-root! base)))))

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
        root (support/temp-root (str "write-back-test-" suffix))
        file (io/file root path)
        callee [:seon.fn/sym (symbol (str fixture-ns) "callee")]
        replacement "(defn callee\n  \"Add one, by increment.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (inc x))"
        proofs (proved! h-conn)
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
                #(submit! % "(clojure.test/deftest ^{:seon/tasks #{'tq.write-back/fix}} caller-check (clojure.test/is (= 3 (caller 2))))"))
            before (digests entities)
            _ (registry/fork! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
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
          (let [prepared (source/prepare-merge! target store c proofs)
                _ (some->> (:seon.source/candidate prepared) (vswap! made conj))
                _ (is (true? (:seon.test/passed? prepared)))
                accepted (source/accept-merge! target store prepared)
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
            (testing "a second write of the same acceptance leaves the file as the first wrote it"
              ;; write-back rebases by declaration onto the file as it is now (e984eb0ab)
              (let [text (slurp file)]
                (is (not (:seon.error/type (source/write-back! request))))
                (is (= text (slurp file)))))
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
        (support/delete-root! root)))))

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
        root (support/temp-root (str "write-back-add-test-" suffix))
        file (io/file root path)
        added-fn [:seon.fn/sym (symbol (str fixture-ns) "triple")]
        added-test [:seon.test/sym (symbol (str fixture-ns) "triple-check")]
        function-source "(defn triple\n  \"Triple.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (* 3 x))"
        test-source "(clojure.test/deftest ^{:seon/tasks #{'tq.write-back/fix}} triple-check (clojure.test/is (= 9 (triple 3))))"
        proofs (proved! h-conn)
        c (keyword (str "write-back-add-c-" suffix))
        made (volatile! [c])]
    (io/make-parents file)
    (spit file (fixture-source fixture-ns))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            base-text (slurp file)
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            before (digests entities)
            _ (registry/fork! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (with-agent! c-conn cluster-name (str "write-back-add-" suffix) fixture-ns
            (fn [request] (submit! request function-source) (submit! request test-source)))
          (testing "the head lacks both additions before the merge"
            (is (every? #(nil? (:db/id (db/pull (db/db h-conn) [:db/id] %))) [added-fn added-test])))
          (testing "a selection merges only named changes; a selected test reaching an unselected addition refuses by name"
            (is (= #{added-test} (get-in (source/prepare-merge! target store c proofs {:selection #{added-test}})
                                         [:seon.error/offending :seon.program/identities])))
            (is (= [[:seon.fn/sym 'absent/declaration]]
                   (:seon.error/offending (source/prepare-merge! target store c proofs {:selection #{[:seon.fn/sym 'absent/declaration]}})))))
          (let [prepared (source/prepare-merge! target store c proofs {:selection #{added-fn added-test}})
                _ (some->> (:seon.source/candidate prepared) (vswap! made conj))
                _ (is (true? (:seon.test/passed? prepared)))
                accepted (when (:seon.test/passed? prepared) (source/accept-merge! target store prepared))
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
        (support/delete-root! root)))))

(defn- merge-world!
  "The fixture file indexed onto H and a lane C where the agent submits
  `forms` (each a function of the fixture namespace), proved by its test
  `test-name` (tagged with `tq.write-back/fix`); calls `body` with the world, then retires C and every
  branch `body` conj'd onto `:made`, and deletes the root."
  [label test-name forms body]
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        suffix (id/id)
        fixture-ns (symbol (str label suffix ".core"))
        root (support/temp-root (str label "-test-" suffix))
        file (io/file root (str "src/" label suffix "/core.clj"))
        c (keyword (str label "-c-" suffix))
        made (volatile! [c])]
    (io/make-parents file)
    (spit file (fixture-source fixture-ns))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            proofs (proved! h-conn)
            _ (registry/fork! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (with-agent! c-conn (:seon.cluster/name target) (str label suffix) fixture-ns
            (fn [request] (doseq [form forms] (submit! request (form fixture-ns)))))
          (body {:target target :store store :c c :proofs proofs :root root :file file :fixture-ns fixture-ns :made made})
          (finally (agent/release-context! candidate))))
      (finally
        (doseq [b @made] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))
        (support/delete-root! root)))))

(deftest ^{:seon.test/long "One agent turn on C, one prepare-merge! (file proof, then a nested seon.test/run; 60 s is merge-test's measured bound) and two analyses of a one-file fixture."
           :seon.test/long-ms 90000}
  a-declaration-naming-an-unrequired-namespace-merges-with-its-ns-form-requiring-it
  ;; The dogfood class: the agent's SCI world resolves `clojure.set/union`
  ;; unrequired. Its evaluation requires it in the agent's namespace
  ;; (`seon.sci.eval/auto-require!`), so the merge carries the namespace and
  ;; the written file's `ns` form requires it.
  (merge-world! "wbreq" "set-check"
                [(fn [_] "(clojure.test/deftest ^{:seon/tasks #{'tq.write-back/fix}} set-check (clojure.test/is (= #{1 2} (clojure.set/union #{1} #{2}))))")]
                (fn [{:keys [target store c proofs root file fixture-ns made]}]
                  (let [h-conn (:seon.db/connection target)
                        check [:seon.test/sym (symbol (str fixture-ns) "set-check")]
                        namespace-entity [:seon.ns/name fixture-ns]
                        prepared (source/prepare-merge! target store c proofs {:selection #{check} :root root})
                        _ (some->> (:seon.source/candidate prepared) (vswap! made conj))
                        _ (is (true? (:seon.test/passed? prepared)))
                        accepted (when (:seon.test/passed? prepared) (source/accept-merge! target store prepared))]
                    (is (= #{check namespace-entity} (:seon.program/identities accepted)))
                    (when (:seon.source/commit-id accepted)
                      (source/write-back! {:seon.db/connection h-conn :seon.source/accepted accepted :seon.fn/root root})
                      (let [requires (:seon.ns/requires (db/pull (db/db h-conn) [:seon.ns/requires] namespace-entity))
                            analysis (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})]
                        (is (contains? (set requires) 'clojure.set) "the merged namespace requires what its definition names")
                        (is (str/includes? (slurp file) "clojure.set") "the written ns form requires it")
                        (is (not (:seon.fn/index-refused analysis)) "the written file analyzes clean")))))))

(deftest ^{:seon.test/long "One agent turn on C and one prepare-merge! whose file proof (one analysis and one lint of a one-file fixture) refuses before the gate runs."
           :seon.test/long-ms 30000}
  a-file-clj-kondo-reports-an-error-in-refuses-at-prepare-naming-the-finding
  ;; Chain 4's merge 5f7fc3f7b published an `is` message printing a value; HEAD then failed
  ;; lint and every lane commit was refused. The merge holds the lane commit's bar.
  (merge-world! "wblint" "lint-check"
                [(fn [_] "(clojure.test/deftest ^{:seon/tasks #{'tq.write-back/fix}} lint-check (clojure.test/is (= 1 1) (pr-str :x)))")]
                (fn [{:keys [target store c proofs root file made]}]
                  (let [before (slurp file)
                        refused (source/prepare-merge! target store c proofs {:root root})
                        finding (first (:seon.source/lint-findings refused))]
                    (some->> (:seon.source/candidate refused) (vswap! made conj))
                    (is (= :seon.source/lint-error (:seon.error/type refused)))
                    (is (= [(subs (.getPath ^java.io.File file) (inc (count root))) :seon/unbounded-assertion-message]
                           ((juxt :seon.source/path :seon.lint/type) finding)))
                    (is (pos-int? (:seon.lint/row finding)))
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
     [ns-form (fn [_] "(clojure.test/deftest ^{:seon/tasks #{'tq.write-back/fix}} set-check (clojure.test/is (= #{1 2} (clojure.set/union #{1} #{2}))))")]
     (fn [{:keys [target store proofs c root file fixture-ns made]}]
       (let [h-conn (:seon.db/connection target)
             check [:seon.test/sym (symbol (str fixture-ns) "set-check")]
             namespace-entity [:seon.ns/name fixture-ns]
             prepared (source/prepare-merge! target store c proofs {:selection #{check} :root root})
             _ (some->> (:seon.source/candidate prepared) (vswap! made conj))
             _ (is (true? (:seon.test/passed? prepared)))
             accepted (when (:seon.test/passed? prepared) (source/accept-merge! target store prepared))]
         (is (= #{check namespace-entity} (:seon.program/identities accepted)))
         (when (:seon.source/commit-id accepted)
           (let [written (source/write-back! {:seon.db/connection h-conn :seon.source/accepted accepted :seon.fn/root root})
                 reindexed (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]}))]
             (is (= [namespace-entity] (get-in written [:seon.source/files 0 :seon.source/replaced])))
             ;; the namespace row's source is the `ns` form of its resolver state as SCI holds it
             (let [{merged :seon.ns/source requires :seon.ns/requires}
                   (db/pull (db/db h-conn) [:seon.ns/source :seon.ns/requires] namespace-entity)]
               (is (contains? (set requires) 'clojure.set) "the merged namespace requires what the agent added")
               (is (str/starts-with? (slurp file) merged) "the file's ns form is the merged namespace's"))
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
        root (support/temp-root (str "wbtwo-test-" suffix))
        paths [(str "src/wbtwo" suffix "/core.clj") (str "src/wbtwo" suffix "/core_test.clj")]
        test-source (str "(ns " test-ns "\n  \"Write-back test fixture.\"\n  (:require [clojure.test] [" fixture-ns "]))\n")
        callee [:seon.fn/sym (symbol (str fixture-ns) "callee")]
        check [:seon.test/sym (symbol (str test-ns) "callee-check")]
        proved (proved! h-conn)
        c (keyword (str "wbtwo-c-" suffix))
        made (volatile! [c])]
    (doseq [[path text] (map vector paths [(fixture-source fixture-ns) test-source])]
      (io/make-parents (io/file root path))
      (spit (io/file root path) text))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            _ (registry/fork! {:seon.store/store store :seon.store/branch c
                                 :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (with-agent! c-conn (:seon.cluster/name target) (str "wbtwo-" suffix) fixture-ns
            (fn [request]
              (submit! request "(defn callee\n  \"Add one, by increment.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (inc x))")
              (submit! request (str "(in-ns '" test-ns ")\n(clojure.test/deftest ^{:seon/tasks #{'tq.write-back/fix}} callee-check (clojure.test/is (= 3 ("
                                    fixture-ns "/callee 2))))"))))
          (let [prepared (source/prepare-merge! target store c proved {:root root})
                _ (some->> (:seon.source/candidate prepared) (vswap! made conj))
                accepted (when (:seon.test/passed? prepared) (source/accept-merge! target store prepared))
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
        (support/delete-root! root)))))

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
        root (support/temp-root (str "write-back-first-party-test-" suffix))
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
            _ (registry/fork! {:seon.store/store store :seon.store/branch c :seon.cluster.registry/from base :seon.db/db (db/db h-conn)})
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
        (support/delete-root! root)))))

(deftest ^{:seon.test/long "Analyses of a one-file fixture onto H, three branches each replacing one declaration, and three write-backs (each analyzing the changed file and its candidate)."
           :seon.test/long-ms 20000}
  a-sibling-change-landed-in-the-file-is-kept-and-the-same-declaration-conflicts
  ;; Two branches of one base change different declarations of one file: the
  ;; second writes back onto the file the first already rewrote, keeping both.
  ;; A third changing the declaration the first rewrote is a conflict.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        suffix (id/id)
        fixture-ns (symbol (str "wbsib" suffix ".core"))
        root (support/temp-root (str "write-back-sibling-test-" suffix))
        path (str "src/wbsib" suffix "/core.clj")
        callee [:seon.fn/sym (symbol (str fixture-ns) "callee")]
        caller [:seon.fn/sym (symbol (str fixture-ns) "caller")]
        branches (mapv #(keyword (str "write-back-sibling-" % "-" suffix)) ["one" "two" "three"])]
    (io/make-parents (io/file root path))
    (spit (io/file root path) (fixture-source fixture-ns))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            base (db/commit-id (db/db h-conn))
            base-digests (digests entities)
            write! (fn [branch identity source]
                     (registry/fork! {:seon.store/store store :seon.store/branch branch :seon.cluster.registry/from base :seon.db/db (db/db h-conn)})
                     (let [context (agent/acquire-context! target nil {:seon.agent/branch branch})
                           conn (:seon.db/connection context)]
                       (try
                         (support/transacted! conn (support/replacement-tx (db/db conn) identity source {}))
                         {:digest (:seon.program/definition-digest (db/pull (db/db conn) [:seon.program/definition-digest] identity))
                          :written (source/write-back! {:seon.db/connection conn :seon.fn/root root
                                                        :seon.source/accepted {:seon.program/identities #{identity}
                                                                               :seon.source/base-commit-id base
                                                                               :seon.source/commit-id (db/commit-id (db/db conn))}})}
                         (finally (agent/release-context! context)))))
            one (write! (branches 0) callee "(defn callee\n  \"Add one, by increment.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (inc x))")
            two (write! (branches 1) caller "(defn caller\n  \"Call the callee twice.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (callee (callee x)))")
            on-disk (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]}))
            three (write! (branches 2) callee "(defn callee\n  \"Add one, by addition.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ 1 x))")]
        (is (= [callee] (get-in one [:written :seon.source/files 0 :seon.source/replaced])) "the first branch writes back")
        (is (= [caller] (get-in two [:written :seon.source/files 0 :seon.source/replaced]))
            "the second branch writes back onto the file the first changed")
        (is (= [(:digest one) (:digest two)] [(get on-disk callee) (get on-disk caller)])
            "the file holds both branches' changes")
        (is (= {:seon.source/write-back-refused :conflict :seon.source/path path
                :seon.program/conflict-digests {callee {:seon.program/base-digest (get base-digests callee)
                                                        :seon.program/head-digest (:digest one)
                                                        :seon.program/branch-digest (:digest three)}}}
               (select-keys (:written three) [:seon.source/write-back-refused :seon.source/path :seon.program/conflict-digests]))
            "a declaration changed on both sides is a conflict naming the file, the declaration and its digests")
        (is (= (:digest one) (get (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})) callee))
            "a conflict writes nothing"))
      (finally
        (doseq [b branches] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))
        (support/delete-root! root)))))

(deftest ^{:seon.test/long "Analyses of a one-file fixture onto H, one branch adding a helper and replacing its caller, and one write-back (one analysis of the candidate)."
           :seon.test/long-ms 20000}
  an-added-helper-is-written-before-the-replaced-function-calling-it
  ;; A branch extracts a helper by `defn`: the new declaration and the
  ;; replaced function calling it write back together, the helper first, so
  ;; the candidate analyzes and reindexes to exactly the branch's program.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        suffix (id/id)
        fixture-ns (symbol (str "wbadd" suffix ".core"))
        root (support/temp-root (str "write-back-addition-test-" suffix))
        path (str "src/wbadd" suffix "/core.clj")
        helper [:seon.fn/sym (symbol (str fixture-ns) "helper")]
        caller [:seon.fn/sym (symbol (str fixture-ns) "caller")]
        c (keyword (str "write-back-addition-c-" suffix))]
    (io/make-parents (io/file root path))
    (spit (io/file root path) (fixture-source fixture-ns))
    (try
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})
            _ (support/transacted! h-conn (fn/reconcile-tx (db/db h-conn) entities (mapv program/row-identity entities)))
            base (db/commit-id (db/db h-conn))
            _ (registry/fork! {:seon.store/store store :seon.store/branch c :seon.cluster.registry/from base :seon.db/db (db/db h-conn)})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (support/transacted! c-conn (support/replacement-tx (db/db c-conn) helper "(defn helper\n  \"Add two.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 2))" {}))
          (support/transacted! c-conn (support/replacement-tx (db/db c-conn) caller "(defn caller\n  \"Call the helper.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (helper x))" {}))
          (let [written (source/write-back! {:seon.db/connection c-conn :seon.fn/root root
                                             :seon.source/accepted {:seon.program/identities #{helper caller}
                                                                    :seon.source/base-commit-id base
                                                                    :seon.source/commit-id (db/commit-id (db/db c-conn))}})
                on-disk (digests (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]}))]
            (is (nil? (:seon.source/write-back-refused written)) "the addition and its caller write back")
            (is (= (mapv #(:seon.program/definition-digest (db/pull (db/db c-conn) [:seon.program/definition-digest] %)) [helper caller])
                   (mapv on-disk [helper caller]))
                "the file reindexed from disk declares the branch's helper and caller"))
          (finally (agent/release-context! candidate))))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch c})
        (support/delete-root! root)))))

(deftest a-merged-db-only-task-gets-a-fresh-task-file
  ;; A task that lives only in the database (a chain `seon.task/chain!` made at
  ;; runtime) has no task file; its merge writes one, started from an empty
  ;; vector as a fresh namespace's file starts from empty text.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        suffix (id/id)
        task-ns (str "tq.wbfresh" suffix)
        path (str "resources/seon/tasks/" task-ns ".edn")
        identity [:seon.task/sym (symbol task-ns "a")]
        root (support/temp-root (str "write-back-fresh-task-" suffix))
        c (keyword (str "write-back-fresh-task-c-" suffix))]
    (try
      (let [base (db/commit-id (db/db h-conn))
            _ (registry/fork! {:seon.store/store store :seon.store/branch c :seon.cluster.registry/from base :seon.db/db (db/db h-conn)})
            candidate (agent/acquire-context! target nil {:seon.agent/branch c})
            c-conn (:seon.db/connection candidate)]
        (try
          (is (= {:seon.task/changed? true}
                 (select-keys (db/with-branch {:seon.db/connection c-conn}
                                              #(task/index-tasks! {:seon.db/connection c-conn
                                                                   :seon.task/files {path "[#:seon.task{:name \"a\" :intent \"Do it.\"}]"}}))
                              [:seon.task/changed? :seon.error/message]))
              "the branch holds the task in the database only")
          (let [written (source/write-back! {:seon.db/connection c-conn :seon.fn/root root
                                             :seon.source/accepted {:seon.program/identities #{identity}
                                                                    :seon.source/base-commit-id base
                                                                    :seon.source/commit-id (db/commit-id (db/db c-conn))}})
                projection (db/carried-projection (db/db c-conn))]
            (is (= [identity] (get-in written [:seon.source/files 0 :seon.source/added])) "the task is added to a new file")
            (is (= [(symbol task-ns "a")]
                   (mapv :seon.task/sym (task/file-declarations projection path (slurp (io/file root path)))))
                "the fresh file declares the merged task"))
          (finally (agent/release-context! candidate))))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch c})
        (support/delete-root! root)))))
