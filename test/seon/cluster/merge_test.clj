(ns seon.cluster.merge-test
  "Prepare, gate and named accept of a candidate branch's replacements
  (`seon.cluster.source/prepare-merge!`, `accept-merge!`)."
  (:require [babashka.process :as process]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.host :as host]
            [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.id :as id]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.task :as task]
            [seon.test]
            [seon.test-support :as support]))

(defn- sym [n] (symbol "seon.cluster.merge-test" n))

(defn- entity
  "One analyzed fixture declaration of this namespace: `fn` or `test`."
  [database kind n source]
  (support/program-entity database [(if (= kind :fn) :seon.fn/sym :seon.test/sym) (sym n)] source))

(defn- write! [connection entities] (support/transacted! connection (vec entities)) (db/commit-id (db/db connection)))

(deftest ^{:seon.test/long "Three prepares, each one nested seon.test/run on a new candidate commit (the save gate's cost, measured in lane-nsa-slice4-2026-09-23.md)."
           :seon.test/long-ms 90000}
  a-candidate-merges-only-its-tested-green-program
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        fn-entity #(entity (db/db %1) :fn %2 %3)
        proofs #{(sym "merge-check")}
        c (keyword (str "merge-test-c-" (id/id)))
        _ (write! h-conn [(fn-entity h-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] 1)")
                          (fn-entity h-conn "merge-caller" "(defn merge-caller {:malli/schema [:=> [:cat] :int]} [] (seon.cluster.merge-test/merge-callee))")])
        _ (write! h-conn [(entity (db/db h-conn) :test "merge-check" "(clojure.test/deftest merge-check (clojure.test/is (= 1 (seon.cluster.merge-test/merge-caller))))")])
        _ (registry/fork! {:seon.store/store store :seon.store/branch c
                             :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
        candidate (cluster.agent/acquire-context! target nil {:seon.agent/branch c})
        c-conn (:seon.db/connection candidate)
        digest #(:seon.program/definition-digest (db/pull (db/db %1) [:seon.program/definition-digest] [:seon.fn/sym (sym %2)]))
        made (volatile! [])
        prepare #(let [p (source/prepare-merge! target store c proofs)]
                   (some->> (:seon.source/candidate p) (vswap! made conj)) p)]
    (try
      (testing "an equal revert is unchanged: nothing to merge"
        (write! c-conn [(fn-entity c-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] (+ 0 1))")])
        (write! c-conn [(fn-entity c-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] 1)")])
        (is (= :seon.program/scope (:seon.program/missing-evidence (prepare)))))
      (testing "a red replacement is tested but its evidence refuses acceptance"
        (let [_ (write! h-conn [(fn-entity h-conn "merge-extra" "(defn merge-extra {:malli/schema [:=> [:cat] :int]} [] 0)")])
              _ (write! c-conn [(fn-entity c-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] 2)")])
              red (prepare)
              before (digest h-conn "merge-callee")]
          (is (false? (:seon.test/passed? red)))
          (is (= :seon.source/merge (:seon.error/layer (source/accept-merge! target store red))))
          ;; main may record its own side of the red (seon.test/change-reds); its program is unchanged
          (is (= before (digest h-conn "merge-callee")))))
      (testing "green: accept merges exactly the tested entities with lineage H C E; another entity's write never stales it"
        (let [_ (write! c-conn [(fn-entity c-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] (* 1 1))")])
              green (prepare)
              ;; Before, any head write (a turn, an evaluation, this agent) refused as a stale basis-t.
              h (write! h-conn (support/agents-tx (db/db h-conn) [(str "merge-test-" (id/id))]))
              tested (digest c-conn "merge-callee")
              _ (write! c-conn [(fn-entity c-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] 3)")])
              e (registry/branch-commit-id {:seon.store/store store :seon.store/branch (:seon.source/candidate green)})
              accepted (source/accept-merge! target store green)]
          (is (true? (:seon.test/passed? green)))
          (is (= {:seon.program/identities #{[:seon.fn/sym (sym "merge-callee")]} :seon.test.run/id (:seon.test.run/id green)
                  :seon.source/base-commit-id h :seon.source/commit-id (db/commit-id (db/db h-conn))}
                 accepted)
              "a plain value naming what merged, never a transaction report")
          (is (= tested (digest h-conn "merge-callee")) "the tested replacement, not C's later edit")
          (is (= #{h (first (:parents green)) e} (set (:datahike/parents (:meta (db/db h-conn))))))
          (is (= :seon.source/merge (:seon.error/layer (source/accept-merge! target store green)))
              "the merged change has nothing left to merge")))
      (testing "a head change to a merged entity refuses by name with both digests; then it is a conflict"
        (write! c-conn [(fn-entity c-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] (- 3 2))")])
        (let [prepared (prepare)
              callee [:seon.fn/sym (sym "merge-callee")]
              expected (digest h-conn "merge-callee")
              moved (write! h-conn [(fn-entity h-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] (- 2 1))")])
              refused (source/accept-merge! target store prepared)]
          (is (true? (:seon.test/passed? prepared)))
          (is (= [:seon.source/expected-digests {callee expected} {callee (digest h-conn "merge-callee")}]
                 ((juxt :seon.error/member :seon.error/expected :seon.error/offending) refused)))
          (is (= moved (db/commit-id (db/db h-conn))) "the refused merge takes zero datoms"))
        (is (seq (:seon.program/conflict (prepare)))))
      (finally
        (cluster.agent/release-context! candidate)
        (doseq [b (conj @made c)] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(deftest ^{:seon/tasks #{'seon.cluster.source/a-landing-refusal-carries-its-variant}
           :seon.test/long "One prepare: one nested seon.test/run on a new candidate commit."
           :seon.test/long-ms 60000}
  a-replaced-function-no-test-reaches-and-a-later-program-write-refuse
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        proofs #{(sym "merge-other")}
        c (keyword (str "merge-test-c-" (id/id)))
        _ (write! h-conn [(entity (db/db h-conn) :fn "merge-alone" "(defn merge-alone {:malli/schema [:=> [:cat] :int]} [] 1)")
                          (entity (db/db h-conn) :test "merge-other" "(clojure.test/deftest merge-other (clojure.test/is true))")])
        _ (registry/fork! {:seon.store/store store :seon.store/branch c
                             :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
        candidate (cluster.agent/acquire-context! target nil {:seon.agent/branch c})
        c-conn (:seon.db/connection candidate)]
    (try
      (write! c-conn [(entity (db/db c-conn) :fn "merge-alone" "(defn merge-alone {:malli/schema [:=> [:cat] :int]} [] (inc 0))")])
      (let [prepared (source/prepare-merge! target store c proofs)
            before (db/commit-id (db/db h-conn))
            refusal (source/accept-merge! target store prepared)]
        (is (true? (:seon.test/passed? prepared)) "the test owner selects no test and refuses nothing")
        (is (= [(sym "merge-alone")] (:seon.error/offending refusal)))
        (is (= :seon.source/test-evidence-error (:seon.error/type refusal)) "the refusal carries its variant")
        (is (= before (db/commit-id (db/db h-conn))))
        (testing "a program write on S after the run refuses the named run"
          (let [s (cluster.agent/acquire-context! target nil {:seon.agent/branch (:seon.source/candidate prepared)})]
            (try (write! (:seon.db/connection s) [(entity (db/db (:seon.db/connection s)) :fn "merge-alone" "(defn merge-alone {:malli/schema [:=> [:cat] :int]} [] 1)")])
                 (finally (cluster.agent/release-context! s))))
          (is (= "The tested program changed after the named run."
                 (:seon.error/message (source/accept-merge! target store prepared)))))
        (registry/retire-branch! {:seon.store/store store :seon.store/branch (:seon.source/candidate prepared)}))
      (finally
        (cluster.agent/release-context! candidate)
        (registry/retire-branch! {:seon.store/store store :seon.store/branch c})))))

(deftest ^{:seon/tasks #{'seon.cluster.source/merge-base-survives-collection}}
  a-fork-of-a-work-branch-compares-with-the-target-through-its-parents-changes
  ;; A forks H and replaces nest-mod and deletes nest-del; B forks A and adds
  ;; nest-new. B toward H carries A's inherited change and deletion too: B's
  ;; fork entity inherits A's base toward H, never A's t at B's fork.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        fn-entity #(entity (db/db %1) :fn %2 %3)
        a (keyword (str "merge-test-a-" (id/id)))
        b (keyword (str "merge-test-b-" (id/id)))
        _ (write! h-conn [(fn-entity h-conn "nest-mod" "(defn nest-mod {:malli/schema [:=> [:cat] :int]} [] 1)")
                          (fn-entity h-conn "nest-del" "(defn nest-del {:malli/schema [:=> [:cat] :int]} [] 2)")])
        _ (registry/fork! {:seon.store/store store :seon.store/branch a
                           :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
        a-context (cluster.agent/acquire-context! target nil {:seon.agent/branch a})
        a-conn (:seon.db/connection a-context)]
    (try
      (write! a-conn [(fn-entity a-conn "nest-mod" "(defn nest-mod {:malli/schema [:=> [:cat] :int]} [] 3)")])
      (support/transacted! a-conn [[:db/retractEntity [:seon.fn/sym (sym "nest-del")]]])
      (registry/fork! {:seon.store/store store :seon.store/branch b
                       :seon.cluster.registry/from (db/commit-id (db/db a-conn)) :seon.db/db (db/db a-conn)
                       :seon.branch/target (get-in (db/db h-conn) [:config :branch])})
      (let [b-context (cluster.agent/acquire-context! target nil {:seon.agent/branch b})
            b-conn (:seon.db/connection b-context)]
        (try
          (write! b-conn [(fn-entity b-conn "nest-new" "(defn nest-new {:malli/schema [:=> [:cat] :int]} [] 4)")])
          (let [{:keys [base branch three]} (source/compare-branch store b (db/db h-conn) (get-in (db/db h-conn) [:config :branch]))]
            (try
              (is (contains? (set (:seon.program/changed-on-branch three)) [:seon.fn/sym (sym "nest-mod")])
                  "A's inherited replacement")
              (is (contains? (set (:seon.program/retracted three)) [:seon.fn/sym (sym "nest-del")])
                  "A's inherited deletion")
              (is (contains? (set (:seon.program/added three)) [:seon.fn/sym (sym "nest-new")])
                  "B's own addition")
              (finally (source/release! [base branch]))))
          (finally
            (cluster.agent/release-context! b-context)
            (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))
      (finally
        (cluster.agent/release-context! a-context)
        (registry/retire-branch! {:seon.store/store store :seon.store/branch a})))))

(deftest ^{:seon/tasks #{'seon.cluster.source/a-red-reaching-test-refuses-acceptance}
           :seon.test/long "Two prepares and one nested seon.test/run on the target, each one nested seon.test/run (60 s is this namespace's measured bound for one)."
           :seon.test/long-ms 180000}
  a-red-reaching-test-refuses-acceptance
  ;; Before, accept-merge! gated only the proofs: a green proof merged a change
  ;; that turned another reaching test red, shown only as :seon.source/red.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        fn-entity #(entity (db/db %1) :fn %2 %3)
        test-entity #(entity (db/db %1) :test %2 %3)
        callee #(str "(defn gate-callee {:malli/schema [:=> [:cat] :int]} [] " % ")")
        c (keyword (str "merge-test-c-" (id/id)))
        _ (write! h-conn [(fn-entity h-conn "gate-callee" (callee 1))])
        _ (write! h-conn [(test-entity h-conn "gate-proof" "(clojure.test/deftest gate-proof (clojure.test/is (pos? (seon.cluster.merge-test/gate-callee))))")
                          (test-entity h-conn "gate-exact" "(clojure.test/deftest gate-exact (clojure.test/is (= 1 (seon.cluster.merge-test/gate-callee))))")])
        _ (registry/fork! {:seon.store/store store :seon.store/branch c
                           :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
        candidate (cluster.agent/acquire-context! target nil {:seon.agent/branch c})
        c-conn (:seon.db/connection candidate)
        made (volatile! [])
        prepare #(let [p (source/prepare-merge! target store c #{(sym "gate-proof")})]
                   (some->> (:seon.source/candidate p) (vswap! made conj)) p)
        refused-tests #(set (map (juxt :seon.test/sym :seon.source/verdict) (:seon.source/red-tests %)))
        ;; main may record its own side of a red (seon.test/change-reds); its program stays as it was
        callee-digest #(:seon.program/definition-digest (db/pull (db/db h-conn) [:seon.program/definition-digest]
                                                                 [:seon.fn/sym (sym "gate-callee")]))]
    (try
      (testing "a green proof with another reaching test red on the merge refuses, naming that test"
        (write! c-conn [(fn-entity c-conn "gate-callee" (callee 2))])
        (let [prepared (prepare)
              before (callee-digest)
              refusal (source/accept-merge! target store prepared)]
          (is (= [(sym "gate-exact")] (:seon.source/red prepared)) "the gate ran it red")
          (is (= :seon.source/red-test-error (:seon.error/type refusal)))
          (is (= #{[(sym "gate-exact") :red]} (refused-tests refusal)))
          (is (= #{(:seon.test.run/id prepared)} (set (map :seon.test.run/id (:seon.source/red-tests refusal))))
              "each red test names the run that recorded it")
          (is (= before (callee-digest)) "main's program is unchanged")))
      (testing "a test already red on the target refuses separately, named for root"
        (write! h-conn [(test-entity h-conn "gate-broken" "(clojure.test/deftest gate-broken (clojure.test/is (= 0 (seon.cluster.merge-test/gate-callee))))")])
        (seon.test/run {:seon.test/policy :named :seon.test/execution target :seon.test/recording-connection h-conn
                        :seon.test/changed [(sym "gate-broken")]})
        (write! c-conn [(fn-entity c-conn "gate-callee" (callee "(* 1 1)"))])
        (let [prepared (prepare)
              before (callee-digest)
              refusal (source/accept-merge! target store prepared)]
          (is (= [(sym "gate-broken")] (:seon.source/red prepared)))
          (is (= :seon.source/red-on-target-error (:seon.error/type refusal)))
          (is (= #{[(sym "gate-broken") :red-on-target]} (refused-tests refusal)))
          (is (= before (callee-digest)) "main's program is unchanged")))
      (finally
        (cluster.agent/release-context! candidate)
        (doseq [b (conj @made c)] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(defn- git!
  "Run git with `args` in `root`; its trimmed standard output, throwing on a non-zero exit."
  [root & args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" root args)]
    (when-not (zero? exit) (throw (ex-info err {:git args})))
    (str/trim out)))

(defn- checkout!
  "A disposable git checkout under the temp directory holding `files` (relative path to text), committed."
  [label files]
  (let [root (support/temp-root label)]
    (doseq [[path text] files] (io/make-parents (io/file root path)) (spit (io/file root path) text))
    (git! root "init" "-q")
    (git! root "config" "user.email" "land@test.invalid")
    (git! root "config" "user.name" "land-test")
    (git! root "add" ".")
    (git! root "commit" "-q" "-m" "fixture")
    root))

(defn- agent-eval
  "The last reply item of `code` evaluated as `agent-id` through `seon.mcp/call`, read back as data."
  [running agent-id code]
  (->> (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                       "arguments" {"code" code "agent" agent-id "cluster" running}})))
            "content")
       (map #(support/reply (get % "text")))
       (remove #(and (map? %) (contains? % :seon.repl/request)))
       last))

(def ^:private land-source
  ;; the functions and their tests in one file under test/, as root-land's report found the gate refusing it
  (str "(ns tq.land\n  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn area\n  \"The area.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  r)\n\n"
       "(defn side\n  \"The side.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  r)\n\n"
       "(deftest ^{:seon/tasks #{'tq.land/area}} area-test (is (= 25 (area 5))))\n\n"
       "(deftest ^{:seon/tasks #{'tq.land/side}} side-test (is (= 4 (side 2))))\n\n"
       "(deftest side-identity-test (is (= 3 (side 3))))\n"))

(defn- requested!
  "An own-branch agent assigned the task `<ns>/<task-name>` (`tq.land` by default) that evaluates `fix` in
  that namespace and requests its merge on its branch; the assignment's key, the merge request root merges."
  {:malli/schema [:function [:=> [:cat :string :seon.db/connection :string :string] :seon.task/assignment]
                  [:=> [:cat :string :seon.db/connection :symbol :string :string] :seon.task/assignment]]}
  ([running connection task-name fix] (requested! running connection 'tq.land task-name fix))
  ([running connection ns-sym task-name fix]
  (let [agent-id (str "land-" task-name "-" (id/id))
        {branch :seon.store/branch} (support/own-branch-agent! running agent-id (str "(ns " ns-sym ")"))
        id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns ns-sym
                                         :seon.task/name task-name :seon.task/kind :function :seon.task/intent "Fix."}))
        assigned (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection
                                :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})]
    (when (:seon.error/at assigned) (throw (ex-info (:seon.error/message assigned) assigned)))
    (let [defined (agent-eval running agent-id (str "(in-ns '" ns-sym ")\n" fix))
          requested (agent-eval running agent-id "(my.program/request-merge! {})")]
      ;; a refused fixture step is surfaced, never left for the merge to miss
      (if (:seon.task/merge-request (:seon.repl/value requested))
        (:seon.task/assignment assigned)
        (throw (ex-info "The agent requested no merge." {:defined defined :requested requested})))))))

(defn- merge!
  "Root's merge of `assignment` (`my.root/merge!`) through the cluster `execution`."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.task/assignment]
                  [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]}
  [execution assignment]
  ((requiring-resolve 'my.root/merge!) {:my.program/context (assoc execution :my.program/base-ctx (:seon.sci.eval/ctx execution)) :seon.task/assignment assignment}))

(defn- checkout-dial!
  "Name the disposable checkout `root` as the cluster's `:seon.config.fs/working-root` on `connection`:
  the checkout its merges are proven against and published into."
  [connection root]
  (support/apply-config! connection (:seon.cluster/name (support/execution-handle nil)) {:seon.config.fs/working-root root}))

(defn- pass!
  "One db<->flow pass over the running cluster `running`, as a wake runs it."
  [running]
  (let [handle (support/running-cluster running)]
    (cluster.agent/db<->flow {:seon.turn.loop/cluster handle :seon.agent/routing (:seon.agent/routing handle)}
                             :seon.flow/plan :seon.cluster.merge-test/wake)
    nil))

(defn- assignment-row
  "Assignment `key`'s merge and publication facts on main."
  [connection key]
  (db/pull (db/db connection) [:seon.task/outcome :seon.task/merge-commit :seon.task/git-commit :seon.task/refusal]
           [:seon.task/assignment key]))

(deftest ^{:seon/tasks #{'seon.merge/an-accepted-merge-is-published-by-the-next-pass
                         'seon.test/an-unloaded-agent-namespace-runs-interpreted-at-the-gate}
           :seon.test/long "Two own-branch agents' first requests, assignments and request runs, and two merges (each one prepare-merge!: a nested seon.test/run on a new candidate commit, 60 s is this namespace's measured bound for one)."
           :seon.test/long-ms 240000}
  an-accepted-merge-is-published-by-the-next-pass
  (support/with-running-agent
   (fn [running _ connection]
     (let [member (get-in (db/db connection) [:config :branch])
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" member 'user cluster-name]])
           path "test/tq/land.clj"
           root (checkout! "land-test" {path land-source})
           _ (checkout-dial! connection root)
           file (io/file root path)
           ;; the checkout's file declared at the cluster head unrun, as publication declares it
           entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})
           _ (support/transacted! connection (fn/reconcile-tx (db/db connection) entities (mapv program/row-identity entities)))
           execution (support/running-cluster running)
           digest #(:seon.program/definition-digest (db/pull (db/db %1) [:seon.program/definition-digest] [:seon.fn/sym %2]))
           ledger (io/file root "tmp/orchestrator/lanes/other-lane.paths")]
       (try
         (testing "a green request merges on main; the next pass publishes it in one git commit"
           (let [fix "(defn area\n  \"The area.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  (* r r))"
                 request (requested! running connection "area" fix)
                 text (slurp file)
                 before (git! root "rev-parse" "HEAD")
                 merged (merge! execution request)]
             (is (nil? (:seon.error/message merged)) "the merge answers no refusal")
             (is (= fix (:seon.fn/source (db/pull (db/db connection) [:seon.fn/source] [:seon.fn/sym 'tq.land/area])))
                 "main holds the agent's definition")
             (is (= :merged (:seon.task/outcome (assignment-row connection request))) "the assignment is merged")
             (is (uuid? (:seon.task/merge-commit (assignment-row connection request))) "main records the merge commit")
             (is (nil? (:seon.task/git-commit (assignment-row connection request))) "and no git commit yet")
             (is (= [text before] [(slurp file) (git! root "rev-parse" "HEAD")]) "the merge writes no file")
             (testing "a path another lane claims waits: the pass writes nothing"
               (io/make-parents ledger)
               (spit ledger (str path "\n"))
               (pass! running)
               (is (= [text before] [(slurp file) (git! root "rev-parse" "HEAD")]))
               (is (nil? (:seon.task/git-commit (assignment-row connection request))) "the publication stays due")
               (io/delete-file ledger))
             (let [measured (profile/measure #(pass! running))]
               (is (< (:seon.profile/ms measured) 1000) "one small file's publication pass stays under a second")
               (is (< (:seon.profile/alloc-bytes measured) (* 256 1024 1024)) "and allocates under 256 MB on its thread"))
             (let [{git-commit :seon.task/git-commit refused :seon.task/refusal} (assignment-row connection request)]
               (is (nil? refused) "the publication answers no refusal")
               (is (= git-commit (git! root "rev-parse" "HEAD")) "main records the checkout's new HEAD")
               (is (= before (git! root "rev-parse" "HEAD^")) "one commit on the previous head")
               (is (= [path] (str/split-lines (git! root "diff-tree" "--no-commit-id" "--name-only" "-r" git-commit)))
                   "carrying exactly the merged file"))
             (is (str/includes? (slurp file) fix) "the file holds the agent's span")
             (is (= (digest connection 'tq.land/area)
                    (:seon.program/definition-digest
                     (some #(when (= [:seon.fn/sym 'tq.land/area] (program/row-identity %)) %)
                           (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]}))))
                 "the file declares the definition main holds")
             (is (= "" (git! root "status" "--porcelain" "--" "test")) "nothing is left staged or dirty")
             (let [published (slurp file) head (git! root "rev-parse" "HEAD")]
               (pass! running)
               (is (= [published head] [(slurp file) (git! root "rev-parse" "HEAD")]) "a second pass changes nothing"))))
         (testing "a red reaching test answers the gate's refusal, decides nothing and writes nothing"
           (let [text (slurp file)
                 head (git! root "rev-parse" "HEAD")
                 request (requested! running connection "side"
                                     "(defn side\n  \"The side.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  (* r r))")
                 main (db/commit-id (db/db connection))
                 refused (merge! execution request)]
             (is (= :seon.source/red-test-error (:seon.error/type refused)))
             (is (= #{['tq.land/side-identity-test :red]}
                    (set (map (juxt :seon.test/sym :seon.source/verdict) (:seon.source/red-tests refused)))))
             (is (nil? (:seon.task/outcome (assignment-row connection request))) "the assignment stays open")
             (is (= main (db/commit-id (db/db connection))) "main is unchanged")
             (is (= text (slurp file)) "the file is unchanged")
             (is (= head (git! root "rev-parse" "HEAD")) "nothing is committed")))
         (finally (support/delete-root! root)))))))

(def ^:private shape-source
  ;; a producer, its consumer and the test reading both, over the schema resource's :tq.shape/radius
  (str "(ns tq.shape\n  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn area\n  \"The area.\"\n  {:malli/schema [:=> [:cat :tq.shape/radius] :int]}\n  [r]\n  r)\n\n"
       "(defn label\n  \"The area's label.\"\n  {:malli/schema [:=> [:cat :int] :string]}\n  [a]\n  (str a))\n\n"
       "(deftest ^{:seon/tasks #{'tq.shape/area}} area-test (is (= \"25\" (label (area 5)))))\n"))

(def ^:private shape-schemas
  ;; :radius is authored over two lines: a replaced value keeps that layout
  "#:tq.shape{:radius [:and {:description \"A radius.\"}\n                    :int]\n           :side   :int}\n")

(defn- schema-entities
  "The schema entities publication declares for `forms` over `database`'s projection."
  [database forms]
  (let [projection (reduce-kv (fn [p k v] (schema/projection-with-schema p k v {:seon.schema.admission/source :core}))
                              (db/carried-projection database) forms)]
    (schema/canonical-schema-entities projection forms)))

(deftest ^{:seon.test/long "One own-branch agent's first request, assignment and request run, and one land (one prepare-merge!: a nested seon.test/run on a new candidate commit, 60 s is this namespace's measured bound for one)."
           :seon.test/long-ms 180000}
  a-function-and-its-schema-land-as-one-unit
  (support/with-running-agent
   (fn [running _ connection]
     (let [member (get-in (db/db connection) [:config :branch])
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" member 'user cluster-name]])
           path "test/tq/shape.clj"
           schema-path "resources/seon/schemas/tq.shape.edn"
           root (checkout! "land-schema-test" {path shape-source schema-path shape-schemas})
           _ (checkout-dial! connection root)
           ;; the checkout's schemas, then its file, declared at the cluster head unrun, as publication declares them
           schemas (schema-entities (db/db connection) (edn/read-string shape-schemas))
           _ (support/transacted! connection (fn/reconcile-tx (db/db connection) schemas (mapv program/row-identity schemas)))
           entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})
           _ (support/transacted! connection (fn/reconcile-tx (db/db connection) entities (mapv program/row-identity entities)))
           execution (support/running-cluster running)
           fix (str "(seon.schema/register! :tq.shape/radius [:and {:description \"A radius.\"} [:int {:min 0}]])\n"
                    "(seon.schema/register! :tq.shape/area [:int {:min 0}])\n"
                    "(defn area\n  \"The area.\"\n  {:malli/schema [:=> [:cat :tq.shape/radius] :tq.shape/area]}\n  [r]\n  (* r r))\n"
                    "(defn label\n  \"The area's label.\"\n  {:malli/schema [:=> [:cat :tq.shape/area] :string]}\n  [a]\n  (str a))")]
       (try
         (let [request (requested! running connection 'tq.shape "area" fix)
               before (git! root "rev-parse" "HEAD")
               landed (merge! execution request)
               _ (pass! running)
               git-commit (:seon.task/git-commit (assignment-row connection request))
               main (db/db connection)
               file-schemas (edn/read-string (slurp (io/file root schema-path)))
               published (into {} (map (juxt :seon.schema/key :seon.program/definition-digest))
                               (schema/canonical-schema-entities (db/carried-projection main) file-schemas))
               held #(:seon.program/definition-digest (db/pull main [:seon.program/definition-digest] [:seon.schema/key %]))]
           (is (nil? (:seon.error/message landed)) "the merge answers no refusal")
           (is (= git-commit (git! root "rev-parse" "HEAD")) "the next pass published it")
           (is (= {:tq.shape/radius [:and {:description "A radius."} [:int {:min 0}]] :tq.shape/side :int
                   :tq.shape/area [:int {:min 0}]}
                  file-schemas)
               "the resource declares the replaced and the added schema beside the unchanged one")
           (is (str/includes? (slurp (io/file root schema-path))
                              ":radius [:and {:description \"A radius.\"}\n                    [:int {:min 0}]]")
               "the replaced value keeps its authored layout")
           (is (str/includes? (slurp (io/file root schema-path)) ":side   :int") "an unchanged entry keeps its text")
           (is (= {:tq.shape/radius (held :tq.shape/radius) :tq.shape/area (held :tq.shape/area)}
                  (select-keys published [:tq.shape/radius :tq.shape/area]))
               "the resource publishes the digests main holds")
           (is (= [:=> [:cat :tq.shape/area] :string]
                  (edn/read-string (:seon.fn/spec (db/pull main [:seon.fn/spec] [:seon.fn/sym 'tq.shape/label]))))
               "the consumer's contract names the new schema on main")
           (is (= #{path schema-path}
                  (set (str/split-lines (git! root "diff-tree" "--no-commit-id" "--name-only" "-r" git-commit))))
               "one git commit carries the function's file and its schema resource")
           (is (= before (git! root "rev-parse" "HEAD^"))))
         (finally (support/delete-root! root)))))))

(deftest ^{:seon/tasks #{'seon.cluster.source/a-landing-is-one-git-commit}}
  a-landed-merge-is-one-git-commit-of-exactly-its-paths
  (let [root (checkout! "land-commit-test" {"src/a.clj" "(ns a)\n" "src/b.clj" "(ns b)\n" "notes.txt" "x\n"})
        acceptance {:seon.task/sym 'tq.land/area :seon.task/assignment "0123456789ab" :seon.agent/id "land-agent"
                    :seon.test.run/id "abcdef012345" :seon.source/commit-id #uuid "6aca34f3-6d42-5b48-bdf7-5eba943d3d4a"}
        written {:seon.source/changed-paths ["src/a.clj"]
                 :seon.source/files [{:seon.source/path "src/a.clj" :seon.source/base-digest "x" :seon.source/candidate-digest "y"
                                      :seon.source/replaced []}]}]
    (try
      (spit (io/file root "src/a.clj") "(ns a)\n(def x 1)\n")
      (spit (io/file root "src/b.clj") "(ns b)\n(def y 2)\n")
      (testing "anything else staged in the index refuses and commits nothing"
        (git! root "add" "src/b.clj")
        (let [head (git! root "rev-parse" "HEAD")
              refused (source/commit! root written acceptance)]
          (is (= :seon.git/commit-error (:seon.error/type refused)))
          (is (= ["src/b.clj"] (:seon.error/offending refused)))
          (is (= head (git! root "rev-parse" "HEAD"))))
        (git! root "reset" "-q" "--" "src/b.clj"))
      (testing "the commit carries exactly the written paths and a message naming the task, merge, agent, run and commit"
        (let [head (git! root "rev-parse" "HEAD")
              sha (:seon.git/commit (source/commit! root written acceptance))
              message (git! root "log" "-1" "--format=%B" sha)]
          (is (= sha (git! root "rev-parse" "HEAD")))
          (is (= head (git! root "rev-parse" "HEAD^")) "one commit on the previous head")
          (is (= ["src/a.clj"] (str/split-lines (git! root "diff-tree" "--no-commit-id" "--name-only" "-r" sha))))
          (is (every? #(str/includes? message %) ["tq.land/area" "0123456789ab" "land-agent" "abcdef012345"
                                                  "6aca34f3-6d42-5b48-bdf7-5eba943d3d4a"]))
          (is (= "(ns b)\n(def y 2)\n" (slurp (io/file root "src/b.clj"))) "another file's working-tree change stays uncommitted")
          (is (= ["src/b.clj"] (str/split-lines (git! root "diff" "--name-only"))))))
      (finally (support/delete-root! root)))))

(deftest ^{:seon/tasks #{'seon.cluster.source/a-landing-is-one-git-commit}}
  a-landing-commit-waits-for-the-lane-commit-lock
  ;; `bin/lane commit` holds .git/lane-commit.lock (perl flock) around its staged-index
  ;; check and commit; a landing's commit! takes the same lock, so neither interleaves.
  (let [root (checkout! "land-lock-test" {"src/a.clj" "(ns a)\n"})
        acceptance {:seon.task/assignment "0123456789ab" :seon.test.run/id "abcdef012345"
                    :seon.source/commit-id #uuid "6aca34f3-6d42-5b48-bdf7-5eba943d3d4a"}
        written {:seon.source/changed-paths ["src/a.clj"]
                 :seon.source/files [{:seon.source/path "src/a.clj" :seon.source/base-digest "x" :seon.source/candidate-digest "y"
                                      :seon.source/replaced []}]}
        holder (process/process {:dir root} "perl" "-MFcntl=:flock" "-e"
                                "open(my $l, '>>', shift) or die; flock($l, LOCK_EX) or die; $| = 1; print \"locked\\n\"; <STDIN>;"
                                (str root "/.git/lane-commit.lock"))]
    (try
      (spit (io/file root "src/a.clj") "(ns a)\n(def x 1)\n")
      (is (= "locked" (.readLine (io/reader (:out holder)))) "a lane commit holds the lock")
      (let [head (git! root "rev-parse" "HEAD")
            landing (future (source/commit! root written acceptance))]
        (is (= ::waiting (deref landing 1000 ::waiting)) "the landing waits while the lane commit holds the lock")
        (is (= head (git! root "rev-parse" "HEAD")) "nothing is committed meanwhile")
        (.close ^java.io.OutputStream (:in holder))
        (let [landed (deref landing 10000 nil)]
          (is (= (git! root "rev-parse" "HEAD") (:seon.git/commit landed))
              "the landing commits once the lock is released")))
      (finally
        (.close ^java.io.OutputStream (:in holder))
        (support/delete-root! root)))))

(deftest ^{:seon/tasks #{'my.program/merge-requests-refuses-its-own-result-on-main}
           :seon.test/long "One own-branch agent's first request, assignment and request run."
           :seon.test/long-ms 120000}
  an-end-message-naming-a-merge-task-is-not-a-request
  ;; an agent's end message names its merge task (:seon.task/merge); the merge request root reads is the assignment
  (support/with-running-agent
   (fn [running _ connection]
     (let [member (get-in (db/db connection) [:config :branch])
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" member 'user cluster-name]])
           root (checkout! "proposals-message-test" {"test/tq/land.clj" land-source})
           entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})
           _ (support/transacted! connection (fn/reconcile-tx (db/db connection) entities (mapv program/row-identity entities)))
           assignment (requested! running connection "area"
                                  "(defn area\n  \"The area.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  (* r r))")
           agent-id (get-in (db/pull (db/db connection) [{:seon.task/agent [:seon.agent/id]}] [:seon.task/assignment assignment])
                            [:seon.task/agent :seon.agent/id])
           sent (message/send! {:my.message/to "root" :my.message/content "My task ended: done."
                                :seon.task/end :done}
                               connection agent-id)]
       (try
         (is (:seon.message/id sent) "the end message is recorded")
         (let [open ((requiring-resolve 'my.root/merge-requests) {:seon.db/db (db/db connection)})]
           (is (vector? open) "merge-requests answers its merge requests")
           (is (every? :seon.task/assignment open) "every entry is an assignment")
           (is (= [assignment] (filter #{assignment} (map :seon.task/assignment open)))
               "the agent's one assignment, not its end message"))
         (finally (support/delete-root! root)))))))

(deftest ^{:seon/tasks #{'seon.cluster.source/a-test-never-run-on-the-target-is-not-blamed-on-the-merge}
           :seon.test/long "One prepare (one nested seon.test/run on the candidate) and the gate's judgment of its red test against the target (60 s is this namespace's measured bound for one)."
           :seon.test/long-ms 120000}
  a-test-never-run-on-the-target-is-not-blamed-on-the-merge
  ;; A test red on the candidate that fails on the target too is not the change's,
  ;; though the target never ran it at its current reach.
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        h-conn (:seon.db/connection target)
        fn-entity #(entity (db/db %1) :fn %2 %3)
        test-entity #(entity (db/db %1) :test %2 %3)
        callee #(str "(defn unrun-callee {:malli/schema [:=> [:cat] :int]} [] " % ")")
        c (keyword (str "merge-test-c-" (id/id)))
        _ (write! h-conn [(fn-entity h-conn "unrun-callee" (callee 1))])
        _ (write! h-conn [(test-entity h-conn "unrun-proof" "(clojure.test/deftest unrun-proof (clojure.test/is (pos? (seon.cluster.merge-test/unrun-callee))))")
                          (test-entity h-conn "unrun-broken" "(clojure.test/deftest unrun-broken (clojure.test/is (= 0 (seon.cluster.merge-test/unrun-callee))))")])
        _ (registry/fork! {:seon.store/store store :seon.store/branch c
                           :seon.cluster.registry/from (db/commit-id (db/db h-conn)) :seon.db/db (db/db h-conn)})
        candidate (cluster.agent/acquire-context! target nil {:seon.agent/branch c})
        c-conn (:seon.db/connection candidate)
        made (volatile! [])]
    (try
      (write! c-conn [(fn-entity c-conn "unrun-callee" (callee "(* 1 1)"))])
      (let [prepared (source/prepare-merge! target store c #{(sym "unrun-proof")})
            _ (some->> (:seon.source/candidate prepared) (vswap! made conj))
            refusal (source/accept-merge! target store prepared)]
        (is (= [(sym "unrun-broken")] (:seon.source/red prepared)) "the gate ran it red")
        (is (= :seon.source/red-on-target-error (:seon.error/type refusal)) "it stops for root, not blamed on the change"))
      (finally
        (cluster.agent/release-context! candidate)
        (doseq [b (conj @made c)] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(deftest ^{:seon/tasks #{'seon.cluster.source/an-agents-repl-scratch-namespace-never-blocks-its-landing}
           :seon.test/long "One own-branch agent's first request, assignment and request run, and one land (one prepare-merge!: a nested seon.test/run on a new candidate commit, 60 s is this namespace's measured bound for one)."
           :seon.test/long-ms 120000}
  a-require-in-the-agents-user-namespace-lands-with-its-change
  (support/with-running-agent
   (fn [running _ connection]
     (let [member (get-in (db/db connection) [:config :branch])
           cluster-name (:seon.cluster/name (support/execution-handle nil))
           _ (db/transact! connection [[:db.fn/call #'host/home-call "root" member 'user cluster-name]])
           path "test/tq/land.clj"
           root (checkout! "scratch-land-test" {path land-source})
           _ (checkout-dial! connection root)
           entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})
           _ (support/transacted! connection (fn/reconcile-tx (db/db connection) entities (mapv program/row-identity entities)))
           execution (support/running-cluster running)]
       (try
         ;; agent r7-problems-prose required the namespace it fixed from `user`; its landing refused :one-file
         (let [request (requested! running connection "area"
                                   (str "(defn area\n  \"The area.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [r]\n  (* r r))"
                                        "\n(in-ns 'user)\n(require 'tq.land)"))
               landed (merge! execution request)
               _ (pass! running)
               git-commit (:seon.task/git-commit (assignment-row connection request))]
           (is (nil? (:seon.error/type landed)) "the merge answers no refusal")
           (is (= git-commit (git! root "rev-parse" "HEAD")) "the next pass publishes it as the checkout's new HEAD")
           (is (= [path] (str/split-lines (git! root "diff-tree" "--no-commit-id" "--name-only" "-r" git-commit)))
               "only the changed file is written"))
         (finally (support/delete-root! root)))))))
