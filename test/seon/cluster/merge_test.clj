(ns seon.cluster.merge-test
  "Prepare, gate and named accept of a candidate branch's replacements
  (`seon.cluster.source/prepare-merge!`, `accept-merge!`)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.id :as id]
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
              before (db/commit-id (db/db h-conn))]
          (is (false? (:seon.test/passed? red)))
          (is (= :seon.source/merge (:seon.error/layer (source/accept-merge! target store red))))
          (is (= before (db/commit-id (db/db h-conn))))))
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
              "the merged proposal has nothing left to merge")))
      (testing "a head change to a merged entity refuses by name with both digests; then it is a conflict"
        (write! c-conn [(fn-entity c-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] (- 3 2))")])
        (let [proposal (prepare)
              callee [:seon.fn/sym (sym "merge-callee")]
              expected (digest h-conn "merge-callee")
              moved (write! h-conn [(fn-entity h-conn "merge-callee" "(defn merge-callee {:malli/schema [:=> [:cat] :int]} [] (- 2 1))")])
              refused (source/accept-merge! target store proposal)]
          (is (true? (:seon.test/passed? proposal)))
          (is (= [:seon.source/expected-digests {callee expected} {callee (digest h-conn "merge-callee")}]
                 ((juxt :seon.error/member :seon.error/expected :seon.error/offending) refused)))
          (is (= moved (db/commit-id (db/db h-conn))) "the refused merge takes zero datoms"))
        (is (seq (:seon.program/conflict (prepare)))))
      (finally
        (cluster.agent/release-context! candidate)
        (doseq [b (conj @made c)] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(deftest ^{:seon.test/long "One prepare: one nested seon.test/run on a new candidate commit."
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
      (let [proposal (source/prepare-merge! target store c proofs)
            before (db/commit-id (db/db h-conn))
            refusal (source/accept-merge! target store proposal)]
        (is (true? (:seon.test/passed? proposal)) "the test owner selects no test and refuses nothing")
        (is (= [(sym "merge-alone")] (:seon.error/offending refusal)))
        (is (= before (db/commit-id (db/db h-conn))))
        (testing "a program write on S after the run refuses the named run"
          (let [s (cluster.agent/acquire-context! target nil {:seon.agent/branch (:seon.source/candidate proposal)})]
            (try (write! (:seon.db/connection s) [(entity (db/db (:seon.db/connection s)) :fn "merge-alone" "(defn merge-alone {:malli/schema [:=> [:cat] :int]} [] 1)")])
                 (finally (cluster.agent/release-context! s))))
          (is (= "The tested program changed after the named run."
                 (:seon.error/message (source/accept-merge! target store proposal)))))
        (registry/retire-branch! {:seon.store/store store :seon.store/branch (:seon.source/candidate proposal)}))
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
        refused-tests #(set (map (juxt :seon.test/sym :seon.source/verdict) (:seon.source/red-tests %)))]
    (try
      (testing "a green proof with another reaching test red on the merge refuses, naming that test"
        (write! c-conn [(fn-entity c-conn "gate-callee" (callee 2))])
        (let [proposal (prepare)
              before (db/commit-id (db/db h-conn))
              refusal (source/accept-merge! target store proposal)]
          (is (= [(sym "gate-exact")] (:seon.source/red proposal)) "the gate ran it red")
          (is (= :seon.source/red-test-error (:seon.error/type refusal)))
          (is (= #{[(sym "gate-exact") :red]} (refused-tests refusal)))
          (is (= #{(:seon.test.run/id proposal)} (set (map :seon.test.run/id (:seon.source/red-tests refusal))))
              "each red test names the run that recorded it")
          (is (= before (db/commit-id (db/db h-conn))) "main's head is unchanged")))
      (testing "a test already red on the target refuses separately, named for root"
        (write! h-conn [(test-entity h-conn "gate-broken" "(clojure.test/deftest gate-broken (clojure.test/is (= 0 (seon.cluster.merge-test/gate-callee))))")])
        (seon.test/run {:seon.test/policy :named :seon.test/execution target :seon.test/recording-connection h-conn
                        :seon.test/changed [(sym "gate-broken")]})
        (write! c-conn [(fn-entity c-conn "gate-callee" (callee "(* 1 1)"))])
        (let [proposal (prepare)
              before (db/commit-id (db/db h-conn))
              refusal (source/accept-merge! target store proposal)]
          (is (= [(sym "gate-broken")] (:seon.source/red proposal)))
          (is (= :seon.source/red-on-target-error (:seon.error/type refusal)))
          (is (= #{[(sym "gate-broken") :red-on-target]} (refused-tests refusal)))
          (is (= before (db/commit-id (db/db h-conn))) "main's head is unchanged")))
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

(deftest ^{:seon/tasks #{'seon.cluster.source/a-landing-is-one-git-commit}}
  a-landed-merge-is-one-git-commit-of-exactly-its-paths
  (let [root (checkout! "land-commit-test" {"src/a.clj" "(ns a)\n" "src/b.clj" "(ns b)\n" "notes.txt" "x\n"})
        acceptance {:seon.task/sym 'tq.land/area :seon.task/merge "0123456789ab" :seon.agent/id "land-agent"
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
