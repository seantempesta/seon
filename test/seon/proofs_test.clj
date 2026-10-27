(ns seon.proofs-test
  "prove (task seon.proofs/prove-runs-what-a-change-reaches): a key runs exactly when no green
  result is stored at its reach digest, so an unchanged tree runs nothing, a change runs what
  reaches it (through macros, schemas and the inputs with no program facts), a bin/check step
  is keyed like a test by what its functions reach, and the run is shards whose greens are
  stored."
  (:require [clojure.test :refer [deftest is testing]] [clojure.string :as str] [datahike.api :as d]
            [seon.test-store :as ts] [seon.db :as pdb] [seon.program :as program] [seon.proofs :as proofs] [seon.cost :as cost]
            [seon.program-test :refer [record]] [seon.source :as source] [seon.fx :as fx] [seon.config] [seon.contract :as contract] [seon.profile :as profile] [test-runner :as runner] [konserve.jolt.lmdb :as klmdb]))

(defn- prog []
  (record (ts/test-db)
          'user "(ns app.pv-calc)"
          'app.pv-calc "(defn add [a b] (+ a b))"
          'app.pv-calc "(defn neg [a] (- a))"
          'app.pv-calc "(defmacro twice [x] `(* 2 ~x))"
          'app.pv-calc "(defn half {:malli/schema [:=> [:cat :app.pv-calc/n] :int]} [x] (quot x 2))"
          'user "(ns app.pv-calc-test (:require [clojure.test :refer [deftest is]] [app.pv-calc :as c]))"
          'app.pv-calc-test "(deftest add-test (is (= 3 (c/add 1 2))))"
          'app.pv-calc-test "(deftest twice-test (is (= 4 (c/twice 2))))"
          'user "(ns app.pv-neg-test (:require [clojure.test :refer [deftest is]] [app.pv-calc :as c]))"
          'app.pv-neg-test "(deftest neg-test (is (= -1 (c/neg 1))))"
          'app.pv-neg-test "(deftest half-test (is (= 2 (c/half 4))))"))

(def ^:private ts '#{app.pv-calc-test/add-test app.pv-calc-test/twice-test app.pv-neg-test/neg-test app.pv-neg-test/half-test})

(defn- schema [db k form] (d/db-with db [{:schema/key k :schema/form form}]))

(defn- digests [db env] (select-keys (program/reach-digests db {:env env :tree "t" :config {}}) ts))

(defn- scratch-dir []
  (let [d (str (System/getProperty "user.dir") "/target/tmp/prove-" (random-uuid))] (.mkdirs (java.io.File. d)) d))

(defn- stored
  "The results the store holds at the digests of cand ({key digest}): what bin/test reads."
  [conn cand] (proofs/stored-results @conn (vec (vals cand))))

(defn- store!
  "Record ran ({key result}) on conn through seon.proofs/record!, as root's writer does (one commit)."
  ([conn ran] (store! conn ran {}))
  ([conn ran failures] (proofs/record! (fn [tx] (d/transact conn {:tx-data tx})) @conn {:run {:id "proofs-test"} :ran ran :failures failures})))

(defn- prove!
  "What bin/test does for the candidate db: plan against base digests, run the rest, record them on conn."
  [base db env conn]
  (let [cand (digests db env)
        p (proofs/plan base cand (stored conn cand) #{})
        ran (proofs/run-tests! (:run p) cand (fn [_]))]
    (store! conn ran)
    (assoc p :cand cand :ran ran)))

(deftest an-unchanged-tree-runs-nothing-and-reuses-everything
  (ts/test-branch! (fn [_ conn]
  (let [db (schema (prog) :app.pv-calc/n "int?")
        first-run (prove! nil db "e" conn)
        second-run (prove! (:cand first-run) db "e" conn)]
    (is (= ts (set (:run first-run))) "nothing stored yet: every test runs")
    (is (every? proofs/green? (vals (:ran first-run))) "and each is green")
    (is (= [] (:run second-run)) "the same tree again: nothing runs")
    (is (= ts (set (keys (:reused second-run)))) "every result is reused from its digest")
    (is (= (:cand first-run) (:cand second-run)) "the digests are the tree's, not the run's")
    (is (= {} (stored conn {})) "a branch holding no test reads no greens (an agent died on (vals {}), nil)")))))

(deftest a-one-defn-change-runs-only-what-reaches-it
  (ts/test-branch! (fn [_ conn]
  (let [db (schema (prog) :app.pv-calc/n "int?")
        base (:cand (prove! nil db "e" conn))
        db' (record db 'app.pv-calc "(defn neg [a] (- 0 a))")
        r (prove! base db' "e" conn)]
    (is (= '[app.pv-neg-test/neg-test] (:run r)) "neg changed: only the test reaching it runs")
    (is (= '[app.pv-neg-test/neg-test] (:selected r)) "and only it is selected against the base")
    (is (proofs/green? (get (:ran r) 'app.pv-neg-test/neg-test)))
    (testing "a proof always judges: selected, and reused when green at its digest"
      (let [p (proofs/plan base (:cand r) (stored conn (:cand r)) '#{app.pv-calc-test/add-test})]
        (is (= '[app.pv-calc-test/add-test app.pv-neg-test/neg-test] (:selected p)))
        (is (= [] (:run p)) "both are green at their digests now")))))))

(deftest a-macro-schema-or-config-change-widens-the-selection
  (ts/test-branch! (fn [_ conn]
  (let [db (schema (prog) :app.pv-calc/n "int?")
        base (:cand (prove! nil db "e" conn))
        run-of (fn [db env] (:run (proofs/plan base (digests db env) (stored conn (digests db env)) #{})))]
    (is (= '[app.pv-calc-test/twice-test] (run-of (record db 'app.pv-calc "(defmacro twice [x] `(+ ~x ~x))") "e"))
        "a macro's body changed: the test expanding it runs, not the others in its namespace")
    (is (= '[app.pv-neg-test/half-test] (run-of (schema db :app.pv-calc/n "pos-int?") "e"))
        "a schema entry changed: the test reaching the contract naming it runs")
    (is (= (sort-by str ts) (run-of db "e2"))
        "deps or a pin changed (:env, the inputs with no program facts): every test runs")))))

(deftest a-check-step-is-keyed-by-what-its-function-reaches
  (let [db (record (prog) 'user "(ns app.pv-check (:require [app.pv-calc :as c]))"
                   'app.pv-check "(defn smoke [] (c/add 1 2))" 'app.pv-check "(defn idle [] (c/neg 1))")
        roots {"smoke" '[app.pv-check/smoke] "idle" '[app.pv-check/idle]}
        dg (fn [db] (program/reach-digests db {:env "e" :tree "t" :config {}} roots))
        before (dg db)
        after (dg (record db 'app.pv-calc "(defn add [a b] (+ b a))"))]
    (is (= #{"smoke" "idle"} (set (keys before))) "each step its own digest")
    (is (not= (before "smoke") (after "smoke")) "add changed: the step whose function calls it moves")
    (is (= (before "idle") (after "idle")) "the step not reaching add keeps its digest, so its green is reused")
    (testing "a step reading the whole tree is keyed by the tree; the others by their functions"
      (let [all (fn [roots] (zipmap (keys roots) (map str (keys roots))))
            sd (proofs/step-digests all {:env "e" :tree "t1" :config {} :base "b1"})]
        (is (= (set (keys proofs/check-steps)) (set (keys (:digests sd)))))
        (is (= [] (:missing sd)))
        (is (not= ((:digests sd) "laws") ((:digests (proofs/step-digests all {:env "e" :tree "t2" :config {} :base "b1"})) "laws")))
        (testing "health reads what the tree caused since its base: another base is another key, and no base no key"
          (is (not= ((:digests sd) "health") ((:digests (proofs/step-digests all {:env "e" :tree "t1" :config {} :base "b2"})) "health")))
          (is (= ((:digests sd) "laws") ((:digests (proofs/step-digests all {:env "e" :tree "t1" :config {} :base "b2"})) "laws")) "laws reads the tree alone")
          (is (= ["health"] (:missing (proofs/step-digests all {:env "e" :tree "t1" :config {}})))))))
    (testing "a step runs a seon.check mode (the binary, a store branch) exactly when check-steps names functions for it"
      (is (= #{"laws" "lmdb-kill" "lmdb-sem" "forks"} (set (remove proofs/step-needs-binary? (keys proofs/check-steps)))) "laws reads the tree, lmdb-kill and lmdb-sem have binaries of their own, forks runs Jolt")
      (is (every? proofs/step-needs-binary? ["smoke" "fx" "durable" "e2e-loop" "mg-check"])))
    (testing "a step whose function the program does not define (renamed) has no digest and is named missing"
      (is (= {} (program/reach-digests db {:env "e" :tree "t" :config {}} {"gone" '[app.pv-check/renamed]})))
      (is (= ["smoke"] (:missing (proofs/step-digests (fn [roots] (dissoc (zipmap (keys roots) (map str (keys roots))) "smoke")) {:env "e" :tree "t" :config {} :base "b"})))))))

(deftest a-run-that-names-nothing-leaves-out-what-reaches-a-process
  ;; owner, 2026-10-23: tests shouldn't be running agent turns, not automatically at least. A
  ;; step (or test) whose function reaches a process var (a fork, an agent, a root booted for it:
  ;; seon.program/process-vars) is classified by the one walk and left out of a run that names
  ;; nothing; it runs when named. A shell call is no process (audit 2026-10-26, item 7): a test
  ;; that only shells runs in a plan that names nothing
  (let [db (record (prog) 'user "(ns app.pv-boot (:require [app.pv-calc :as c] [seon.lib]))"
                   'app.pv-boot "(defn smoke [] (seon.lib/fork! {:child (fn [] nil) :refused identity :parent identity}))"
                   'app.pv-boot "(defn calc [] (c/add 1 2))")
        idx (program/reach-index db {:env "e" :tree "t" :config {}} {"smoke" '[app.pv-boot/smoke] "calc" '[app.pv-boot/calc]})
        process (set (keep (fn [[k v]] (when (:process? v) k)) idx))
        cand {"smoke" (:digest (idx "smoke")) "calc" (:digest (idx "calc")) 'app.pv-calc-test/add-test "d"}]
    (is (= #{"smoke"} process) "the step that forks reaches a process; the one that adds does not")
    (is (= (program/reach-digests db {:env "e" :tree "t" :config {}} {"smoke" '[app.pv-boot/smoke] "calc" '[app.pv-boot/calc]})
           (update-vals idx :digest))
        "the walk's digests are reach-digests'")
    (is (= {"calc" (:digest (idx "calc")) 'app.pv-calc-test/add-test "d"} (apply dissoc cand (proofs/left-out (keys cand) process false #{})))
        "a run that names nothing selects every key but it"))
  (testing "through the plan bin/prove and bin/test run (test-runner/plan-of), on a checkout of two tests"
    (let [d (scratch-dir)
          files {"deps.edn" "{}"
                 "test/app/pt_calc_test.clj" "(ns app.pt-calc-test (:require [clojure.test :refer [deftest is]]))\n(deftest adds (is (= 3 (+ 1 2))))\n"
                 "test/app/pt_shell_test.clj" "(ns app.pt-shell-test (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh]))\n(deftest shells (is (zero? (:exit (sh/sh \"true\")))))\n"
                 "test/app/pt_fork_test.clj" "(ns app.pt-fork-test (:require [clojure.test :refer [deftest is]] [seon.lib :as lib]))\n(deftest forks (is (lib/fork! {:child (fn [] nil) :refused identity :parent identity})))\n"}
          _ (doseq [[p t] files] (.mkdirs (.getParentFile (java.io.File. (str d "/" p)))) (spit (str d "/" p) t))
          _ (clojure.java.shell/sh "git" "-C" d "init" "-q")
          _ (clojure.java.shell/sh "git" "-C" d "add" ".")
          auto (runner/plan-of d "tests" false nil (ts/program-store))
          named (runner/plan-of d "tests" false ["app.pt-fork-test"] (ts/program-store))]
      (is (= #{'app.pt-calc-test/adds 'app.pt-shell-test/shells} (set (:run-tests auto))) "named nothing: the in-process test runs, the shelling one too (a shell is no process), the forking one does not")
      (is (= ['app.pt-fork-test/forks] (:left auto)) "and the plan says it left that one out")
      (is (= ['app.pt-fork-test/forks] (:run-tests named)) "named, it runs")
      (testing "a plan of tests and check steps walks the program once: one reach graph serves both indexes, and the next plan reads it stored"
        (profile/begin!)
        (runner/plan-of d "both" false nil (ts/program-store))
        (let [calls (fn [v] (or (some #(when (= v (:var %)) (:calls %)) (profile/touched)) 0))]
          (is (= 0 (calls 'seon.program/reach-graph)) "the plan built no reach graph: the two before it stored one in the program store")
          (is (= 1 (calls 'seon.program/reach-update)) "and brought the stored one to the tree once: one graph serves both indexes"))))))

(deftest shards-split-by-namespace-and-each-result-is-stored
  (record (ts/test-db) 'user "(ns app.pv-red-test (:require [clojure.test :refer [deftest is use-fixtures]]))"
          'app.pv-red-test "(def ^:dynamic *fx* nil)"
          'app.pv-red-test "(use-fixtures :each (fn [f] (binding [*fx* :on] (f))))"
          'app.pv-red-test "(deftest red-test (is (= 1 2)))"
          'app.pv-red-test "(deftest fixture-test (is (= :on *fx*)))")
  (prog)
  (ts/test-branch! (fn [_ conn]
  (let [tests '[app.pv-red-test/red-test app.pv-red-test/fixture-test app.pv-calc-test/add-test app.pv-calc-test/twice-test app.pv-neg-test/neg-test]
        dg (zipmap tests ["d-red" "d-fx" "d-add" "d-twice" "d-neg"])
        notes (atom [])
        ran (proofs/run-tests! tests dg #(swap! notes conj %))
        db (store! conn ran {'app.pv-red-test/red-test [{:type :fail :message "1 is not 2"}]})]
    (is (= [["app.pv-calc-test/add-test" "app.pv-calc-test/twice-test"] ["app.pv-neg-test/neg-test"] ["app.pv-red-test/fixture-test" "app.pv-red-test/red-test"]]
           (sort-by first (map #(sort (mapv str %)) (runner/shards tests 3 (zipmap tests [1 1 1 1 2])))))
        "a namespace within its share of the measured ms stays in one shard")
    (is (= 2 (count (runner/shards tests 2 {}))) "never more shards than jobs")
    (is (not (contract/error? db)) "every result recorded")
    (is (= (set tests) (set (keys ran))) "every test has a result")
    (is (= {:pass 0 :fail 1 :error 0} (select-keys (ran 'app.pv-red-test/red-test) [:pass :fail :error])))
    (is (proofs/green? (ran 'app.pv-red-test/fixture-test)) "its namespace's fixtures ran around it")
    (is (= (count tests) (count (filter :seon.test/started @notes))) "each test named as it starts")
    (let [found (stored conn dg)]
      (is (= (set (vals dg)) (set (keys found))) "each result stored at its digest, the red too")
      (is (every? #(proofs/green? (found (dg %))) (remove #{'app.pv-red-test/red-test} tests)) "each green stored green")
      (is (= {:pass 0 :fail 1 :error 0} (select-keys (found "d-red") [:pass :fail :error])) "the red is stored red: reused as red, not run again")
      (is (= "1 is not 2" (:message (first (:failures (found "d-red"))))) "with the failures it recorded"))))))

(deftest a-refused-commit-or-a-throwing-fixture-is-recorded-red
  ;; review 2026-10-22: a write's declared error was dropped (read as stored), and a fixture's
  ;; throw went only to printed output with a guessed message; the store's refused write is
  ;; :seon.proofs/unrecorded, never read as recorded
  (prog)
  (record (ts/test-db) 'user "(ns app.pv-fx-test (:require [clojure.test :refer [deftest is use-fixtures]]))"
          'app.pv-fx-test "(use-fixtures :once (fn [f] (throw (ex-info \"the fixture's store is gone\" {:store :gone}))))"
          'app.pv-fx-test "(deftest never-runs (is true))")
  (let [ran (proofs/run-tests! '[app.pv-calc-test/add-test] '{app.pv-calc-test/add-test "d-add"} (fn [_]))
        ;; a result the schema refuses (a duration that is no number): the commit is refused whole
        ;; the writer refuses the commit (root's writer answers an error map)
        unrecorded (proofs/record! (fn [_] {:seon.error/type :seon.writer/refused :seon.error/operation 'seon.writer/write :seon.error/message "refused"})
                                   nil {:run {:id "proofs-test"} :ran ran})
        notes (atom [])
        thrown (proofs/run-tests! '[app.pv-fx-test/never-runs] '{app.pv-fx-test/never-runs "d-never"} #(swap! notes conj %))
        t (thrown 'app.pv-fx-test/never-runs)]
    (is (proofs/green? (ran 'app.pv-calc-test/add-test)) "the test passed")
    (is (= :seon.proofs/unrecorded (:seon.error/type unrecorded)) "and the refused commit of its result is answered, never read as recorded")
    (is (contract/error? unrecorded) (str unrecorded))
    (is (= 1 (:error t)))
    (is (some #(and (= 'app.pv-fx-test/never-runs (:test %)) (= :error (:type (:seon.test/report %)))) @notes) "the unreported test is reported, as an error")
    (is (re-find #"the fixture's store is gone" (:message t)) "the throw's own message, not a guess")
    (is (= "{:store :gone}" (:data t)) "and its data")))

(deftest each-test-has-its-own-budget
  ;; task seon.proofs/one-prove-path: the bound is per test, so a test that never ends is red,
  ;; named, as the typed :seon.test/ticks-spent, and the run goes on: the next test is green,
  ;; and each result says the ticks it did
  (record (ts/test-db) 'user "(ns app.pv-budget-test (:require [clojure.test :refer [deftest is]]))"
          'app.pv-budget-test "(deftest spins (loop [] (recur)))"
          'app.pv-budget-test "(deftest swallows (try (loop [] (recur)) (catch Throwable _ nil)) (is true))"
          'app.pv-budget-test "(deftest after (is (= 3 (+ 1 2))))")
  (let [notes (atom [])
        ran (proofs/run-tests! '[app.pv-budget-test/spins app.pv-budget-test/swallows app.pv-budget-test/after] {} #(swap! notes conj %) 1000000)
        unbounded (proofs/run-tests! '[app.pv-budget-test/after] {} (fn [_]) nil)
        spent (some #(when (= :seon.test/ticks-spent (get-in % [:seon.test/report :seon.error/type])) %) @notes)]
    (is (= {:pass 0 :fail 0 :error 1} (select-keys (ran 'app.pv-budget-test/spins) [:pass :fail :error])) "the endless test is red")
    (is (= 'app.pv-budget-test/spins (:test spent)) "named, as the typed error")
    (is (= 1000000 (get-in spent [:seon.test/report :budget])))
    (is (<= 1000000 (:ticks (ran 'app.pv-budget-test/spins))) "it did its budget's work")
    (is (= 1 (:error (ran 'app.pv-budget-test/swallows))) "the test's own catch cannot swallow its budget's end")
    (is (proofs/green? (ran 'app.pv-budget-test/after)) "the next test ran, under a budget of its own")
    (is (pos-int? (:ticks (unbounded 'app.pv-budget-test/after))) "an unbounded run (bin/test with 0) counts ticks too")
    (is (pos-int? (:ticks (ran 'app.pv-budget-test/after))) "each result carries the ticks it did")))

(deftest shards-are-dealt-by-measured-work
  ;; pure-tests 2026-10-23: dealt by count, the full run's shards ended 437, 397, 110 and 68 s
  ;; into it; by each test's last measured ms they end together
  (is (= '#{[x/a] [y/b y/c z/d]} (set (runner/shards '[x/a y/b y/c z/d] 2 '{x/a 10 y/b 3 y/c 3 z/d 3})))
      "the longest alone, the rest together")
  (is (= '#{[p/a p/c q/e] [p/b p/d]} (set (runner/shards '[p/a p/b p/c p/d q/e] 2 '{p/a 5 p/b 5 p/c 5 p/d 5 q/e 1})))
      "a namespace longer than a shard's share is split into share-sized parts")
  (is (= 2 (count (runner/shards '[x/a y/b y/c] 2 '{x/a 10}))) "a test never measured counts as the mean of those measured"))

(defn plan-input
  "The input of size n: n keys with stored greens, one in a hundred moved since."
  [n]
  (let [cand (into {} (for [i (range n)] [(symbol "app.pv" (str "t" i)) (str "d" i)]))
        moved (set (take (max 1 (quot n 100)) (keys cand)))
        cand' (into cand (for [k moved] [k (str (cand k) "'")]))]
    [cand cand' (into {} (for [[_ dg] cand] [dg {:pass 1 :fail 0 :error 0}])) #{}]))

(deftest selection-is-proportional-to-what-changed
  (let [[base cand found] (plan-input 1000)]
    (is (= 10 (count (:run (proofs/plan base cand found #{})))) "1000 keys, 10 moved: 10 run"))
  (cost/proof
    {:of 'seon.proofs/plan :input 'seon.proofs-test/plan-input
     :n "keys (tests and check steps), a hundredth of them moved"
     :work (str "one pass: per key a digest compare and a lookup of its green, into a map of the reused (a hash map's "
                "inserts, log32 deep); then sorting what is selected (n/100 keys). Measured 2026-10-22: ~630 ns and ~1.1 KB per key "
                "at 10k keys (6.3 ms), bytes exactly linear, wall time a little steeper (the map's depth and GC)")
     :growth :n-log-n
     :per-n {:ns 100 :bytes 120}
     :fixed {:ns 20000 :bytes 40000 :calls 1}
     :sizes [100 300 1000 10000]}))

(defn- checkout!
  "A git checkout in a scratch dir holding files ({path text}), every file added."
  [files]
  (let [d (scratch-dir)]
    (doseq [[p t] files] (.mkdirs (.getParentFile (java.io.File. (str d "/" p)))) (spit (str d "/" p) t))
    (clojure.java.shell/sh "git" "-C" d "init" "-q")
    (clojure.java.shell/sh "git" "-C" d "add" ".")
    d))

(defn- close-stores!
  "Close the envs of a test's own scratch stores (store paths). program-db keeps its branch's
  connection for the process (its db reads the store), so a shard running these tests held one
  env per scratch store and past nine an open failed with MDB_SEM_UNDO_FULL
  (docs/failure-points.md#lmdb-robust-locks): a test's own store ends with the test."
  [& paths] (run! klmdb/close-env! paths))

(defn- calls-of
  "{var calls} of the contracted functions thunk called."
  [thunk] (profile/begin!) (thunk) (into {} (map (juxt :var :calls)) (profile/touched)))

(deftest a-failed-import-leaves-no-branch
  ;; task seon.proofs/a-failed-import-leaves-no-branch: an import that threw left its tree's branch
  ;; behind, and every later plan of that tree read the partial program: 0 tests, and TEST OK
  ;; (a merge's conflict markers, 2026-10-25). Now the branch exists only once the import committed
  (let [good "(ns app.fi-calc)\n(defn add [a b] (+ a b))\n"
        d (checkout! {"deps.edn" "{}"
                      "src/app/fi_calc.clj" "(ns app.fi-calc)\n<<<<<<< HEAD\n(defn add [a b] (+ a b))\n=======\n(defn add [a b] (+ b a))\n>>>>>>> main\n"
                      "test/app/fi_calc_test.clj" "(ns app.fi-calc-test (:require [clojure.test :refer [deftest is]] [app.fi-calc :as c]))\n(deftest adds (is (= 3 (c/add 1 2))))\n"
                      "test/app/fi_empty_test.clj" "(ns app.fi-empty-test)\n"})
        store (str (scratch-dir) "/store")
        ;; a store's branches, or :no-store: a check never opens (and so makes) a missing path
        branches #(if-not (pdb/store? store)
                    :no-store
                    (let [{:keys [conn]} (pdb/open! :lmdb store 0 :db)] (try (set (d/branches conn)) (finally (d/release conn)))))
        thrown (try (runner/plan-of d "tests" false nil store) nil (catch Throwable t t))]
   (try
    (is (some? thrown) "a file that does not read: the plan throws")
    ;; the full run of 2026-10-26 passed the store's own error (MDB_SEM_UNDO_FULL, scratch stores
    ;; left open) as this throw, and then read a store it had half made
    (is (not (#{:lmdb-error :db-does-not-exist} (:type (ex-data thrown)))) "the file's, not the store's")
    (is (= #{:db} (branches)) "and leaves no branch named for its tree")
    (spit (str d "/src/app/fi_calc.clj") good)
    (clojure.java.shell/sh "git" "-C" d "add" ".")
    (let [p (runner/plan-of d "tests" false nil store)]
      (is (contains? (:digests p) 'app.fi-calc-test/adds) "the tree read: its test is planned")
      (is (= '[app.fi-empty-test] (:no-test p)) "a test namespace the program db holds no test of is named, never left as an empty OK"))
    (finally (close-stores! store)))))

(deftest a-process-plans-on-more-scratch-stores-than-its-undo-entries
  ;; the full run of 2026-10-26: this namespace's scratch stores stayed open (program-db keeps its
  ;; branch's connection), nine of them by its end, and the shard's next opens failed with
  ;; MDB_SEM_UNDO_FULL (each open env takes one of kern.sysv.semume's 10). A test's own store ends
  ;; with the test, so a process plans on any number in turn
  (let [d (checkout! {"deps.edn" "{}" "src/app/ps_calc.clj" "(ns app.ps-calc)\n(defn add [a b] (+ a b))\n"
                      "test/app/ps_calc_test.clj" "(ns app.ps-calc-test (:require [clojure.test :refer [deftest is]]))\n(deftest adds (is true))\n"})
        planned (vec (for [_ (range 12)
                           :let [store (str (scratch-dir) "/store")]]
                       (try (contains? (:digests (runner/plan-of d "tests" false nil store)) 'app.ps-calc-test/adds)
                            (finally (close-stores! store)))))]
    (is (= (repeat 12 true) planned) "twelve stores in turn, each planned")))

(deftest a-plan-reads-the-kept-program-db-of-its-tree
  ;; task seon.proofs/a-plan-keeps-a-program-db-per-tree: the program db is a function of the
  ;; git tree, kept as a branch named by the tree's id; a tree never seen is the nearest kept
  ;; one brought current by refresh-tx; only a store with no tree imports the whole program
  (let [calc "(ns app.kc-calc)\n(defn add [a b] (+ a b))\n"
        d (checkout! {"deps.edn" "{}"
                      "src/app/kc_calc.clj" calc
                      "test/app/kc_calc_test.clj" "(ns app.kc-calc-test (:require [clojure.test :refer [deftest is]] [app.kc-calc :as c]))\n(deftest adds (is (= 3 (c/add 1 2))))\n"
                      "test/app/kc_other_test.clj" "(ns app.kc-other-test (:require [clojure.test :refer [deftest is]]))\n(deftest other (is true))\n"})
        store (str (scratch-dir) "/store")
        ;; stores that keep no tree, each made once: what a whole import gives
        [fresh fresh2 fresh3] (repeatedly 3 #(str (scratch-dir) "/fresh"))
        plan #(runner/plan-of d "tests" false nil store)
        first-calls (calls-of plan)
        p1 (plan)]
   (try
    (is (= 1 (first-calls 'seon.source/import-tx)) "an empty store: the tree is imported once")
    (let [again (calls-of plan)]
      (is (nil? (again 'seon.source/import-tx)) "the same tree: its kept branch, no import")
      (is (nil? (again 'seon.source/refresh-tx)) "and nothing to bring current")
      (is (= (:digests p1) (:digests (plan))) "and the same digests"))
    (spit (str d "/src/app/kc_calc.clj") "(ns app.kc-calc)\n(defn add [a b] (+ b a))\n")
    (let [edited (calls-of plan)
          p2 (plan)
          whole (runner/plan-of d "tests" false nil fresh)]
      (is (nil? (edited 'seon.source/import-tx)) "an edited tree: no import")
      (is (= 1 (edited 'seon.source/refresh-tx)) "the nearest kept tree brought current, once")
      (is (= (:digests whole) (:digests p2)) "the same digests a whole import of the edited tree gives")
      (is (not= (get-in p1 [:digests 'app.kc-calc-test/adds]) (get-in p2 [:digests 'app.kc-calc-test/adds])) "the test reaching the edit moved")
      (is (= (get-in p1 [:digests 'app.kc-other-test/other]) (get-in p2 [:digests 'app.kc-other-test/other])) "the other did not"))
    (spit (str d "/src/app/kc_calc.clj") calc)
    (let [back (calls-of plan)]
      (is (nil? (back 'seon.source/refresh-tx)) "the first tree again: its kept branch")
      (is (= (:digests p1) (:digests (plan)))))
    (testing "a moved file and a deleted one: the kept program is what a whole import of the tree gives"
      (let [files-of (fn [db] (source/source-files db))]
        ;; a namespace moved to a new file and renamed, its body kept: git's rename detection would
        ;; list only the new path, and the old namespace would stay in the kept program
        (let [body (apply str (for [i (range 40)] (str "(deftest t" i " (is (= " i " " i ")))\n")))]
          (spit (str d "/test/app/kc_big_test.clj") (str "(ns app.kc-big-test (:require [clojure.test :refer [deftest is]]))\n" body))
          (runner/program-db d store)
          (.delete (java.io.File. (str d "/test/app/kc_big_test.clj")))
          (spit (str d "/test/app/kc_bigger_test.clj") (str "(ns app.kc-bigger-test (:require [clojure.test :refer [deftest is]]))\n" body)))
        (let [kept (runner/program-db d store) fresh (runner/program-db d fresh2)]
          (is (= (files-of fresh) (files-of kept)))
          (is (nil? (seon.db/eid kept :ns/name 'app.kc-big-test)) "the old namespace is gone"))
        (.delete (java.io.File. (str d "/test/app/kc_bigger_test.clj")))
        (let [kept (runner/program-db d store) fresh (runner/program-db d fresh3)]
          (is (= (files-of fresh) (files-of kept)))
          (is (not (some #{"test/app/kc_bigger_test.clj"} (files-of kept))) "a deleted file is gone"))))
    (finally (close-stores! store fresh fresh2 fresh3)))))

(defn kept-checkout-of
  "program-db's arguments for a checkout whose src holds n functions (app.kp-big) and the
  process's one program store, which then keeps its tree on a branch of its own (the first call,
  not measured, imports it)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :string :string]]}
  [n]
  (let [d (checkout! {"deps.edn" "{}"
                      "src/app/kp_big.clj" (apply str "(ns app.kp-big)\n" (for [i (range n)] (str "(defn f" i " [x] (+ x " i "))\n")))
                      "test/app/kp_big_test.clj" "(ns app.kp-big-test (:require [clojure.test :refer [deftest is]] [app.kp-big :as b]))\n(deftest f0 (is (= 1 (b/f0 1))))\n"})
        store (ts/program-store)]
    (runner/program-db d store)
    [d store]))

(deftest a-kept-tree-costs-its-lookup-not-its-program
  ;; task seon.proofs/a-plan-keeps-a-program-db-per-tree: an unchanged tree's program db is its
  ;; kept branch, whatever the program's size; a one-file change costs that file
  ;; (seon.source/refresh-tx's proofs in seon.source-test: constant in the program's other files)
  (cost/proof
    {:of 'test-runner/program-db :input 'seon.proofs-test/kept-checkout-of
     :n "functions in the checkout's src"
     :work (str "the tree's id (git, a child process: no ticks here), the store opened and the tree's "
                "branch found and connected: a pointer read; nothing of the program is read or imported")
     :growth :constant
     ;; measured 2026-10-23: 1.17M ticks and 4.6 MB at 10, 100 and 1,000 functions (the store's open
     ;; and the branch's connect); a whole import was ~1.8G ticks for the 2,400 forms of src and test
     :fixed {:ticks 1300000.0 :bytes 5000000.0}
     :sizes [10 100 1000]}))

;; ---- every verdict is kept: a digest green and red is flaky (red, never reused as green, never run)

(deftest a-digest-green-and-red-is-flaky-red-and-never-runs
  ;; the merge-cost proof was red once and green on its next run at one digest: with one verdict
  ;; kept per digest the red was gone, and the green reused
  (ts/test-branch!
   (fn [_ conn]
     (let [green {:test 'app.f-test/t :digest "d-flaky" :pass 1 :fail 0 :error 0}
           red {:test 'app.f-test/t :digest "d-flaky" :pass 0 :fail 1 :error 0}
           fails {'app.f-test/t [{:type :fail :message "1 is not 2"}]}
           cand {'app.f-test/t "d-flaky" 'app.f-test/g "d-green" 'app.f-test/r "d-red" 'app.f-test/n "d-none"}]
       (store! conn {'app.f-test/t green})
       (store! conn {'app.f-test/t red} fails)
       (store! conn {'app.f-test/g (assoc green :test 'app.f-test/g :digest "d-green")})
       (store! conn {'app.f-test/r (assoc red :test 'app.f-test/r :digest "d-red")} {'app.f-test/r [{:type :fail :message "1 is not 2"}]})
       (let [found (stored conn cand)
             {:keys [run reused selected]} (proofs/plan nil cand found #{})
             f (found "d-flaky")]
         (is (:flaky f) "a digest that holds a green and a red is flaky")
         (is (not (proofs/green? f)) "judged red, never reused as green")
         (is (= {:type :fail :flaky true :message "flaky: green and red at digest d-flaky (seon.proofs/flaky)"} (first (:failures f)))
             "its failures lead with one saying so, then the red's own")
         (is (= "1 is not 2" (:message (second (:failures f)))))
         (is (not (proofs/green? (found "d-red"))) "a red alone is reused red, with its failures")
         (is (= ['app.f-test/n] run) "only the digest with no result runs: the flaky and the red are reused, never run again")
         (is (= #{'app.f-test/t 'app.f-test/r 'app.f-test/n} (set selected)) "the flaky one and the red are selected: they judge")
         (is (= #{'app.f-test/t 'app.f-test/g 'app.f-test/r} (set (keys reused))))
         (is (= [] (proofs/results-tx {:id "r"} {'app.f-test/t f} {}))
             "a flaky result, judged and not run, records nothing")
         (is (= [] (proofs/results-tx {:id "r"} {'app.f-test/u (assoc red :digest "d-unreported" :unreported true)} {}))
             "a test that never reported (its namespace's run threw first) has no verdict to keep"))))))

(deftest the-results-keep-every-verdict-a-digest-had
  ;; :seon.proof/digest is an identity: a second result at it is the same entity, so its earlier
  ;; status lives in the history (:seon.config.store/keep-history?), which stored-results reads
  (ts/test-branch!
   (fn [_ conn]
     (let [rec! (fn [& rs] (store! conn (into {} (for [[dg s] rs] [dg (assoc {:test 'app.f-test/t :digest dg :pass 0 :fail 0 :error 0} s 1)]))
                                   (into {} (for [[dg s] rs :when (not= s :pass)] [dg [{:type s :message "red"}]]))))]
       (rec! ["d-once" :pass] ["d-twice" :fail] ["d-red" :error])
       (rec! ["d-twice" :pass])
       (let [r (proofs/stored-results @conn ["d-once" "d-twice" "d-red" "d-none"])]
         (is (proofs/green? (r "d-once")))
         (is (= {:pass 0 :fail 0 :error 1} (select-keys (r "d-red") [:pass :fail :error])) "a red alone is reused red")
         (is (:flaky (r "d-twice")) "red then green at one digest: flaky, though its newest status is green")
         (is (= "red" (:message (second (:failures (r "d-twice"))))) "with the red's own failures after the flaky one")
         (is (not (proofs/green? (r "d-twice"))))
         (is (nil? (r "d-none"))))))))

(deftest a-test-whose-child-died-has-no-verdict
  ;; root, 2026-10-27 (THE PLAN 0.7, the blind agent's run): a test the run's child died in
  ;; (seon.agent/test-run-answer's report with its :exit) was an :error result, so the judge held
  ;; a task whose change never reached it as a stray red. A death is no verdict: the test has no
  ;; result (a proof the child died in is unknown, an unrelated test is nothing to the judge),
  ;; as its digest keeps none (seon.prove/result-tx)
  (let [run {:reports [{:type :pass :var "#'a.b-test/ok"}
                       {:type :fail :var "#'a.b-test/red"}
                       {:type :pass :var "#'a.b-test/died"}
                       {:type :error :var "#'a.b-test/died" :exit {:exit 1} :message "test process died: {:exit 1} while #'a.b-test/died ran"}]}]
    (is (= '{a.b-test/ok :pass a.b-test/red :fail} (proofs/results run)))))

