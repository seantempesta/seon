(ns seon.proofs-test
  "prove (task seon.proofs/prove-runs-what-a-change-reaches): a key runs exactly when no green
  result is stored at its reach digest, so an unchanged tree runs nothing, a change runs what
  reaches it (through macros, schemas and the inputs with no program facts), a bin/check step
  is keyed like a test by what its functions reach, and the run is shards whose greens are
  stored."
  (:require [clojure.test :refer [deftest is testing]] [clojure.string :as str] [datahike.api :as d]
            [seon.test-store :as ts] [seon.program :as program] [seon.proofs :as proofs] [seon.cost :as cost]
            [seon.program-test :refer [record]] [seon.fx :as fx] [seon.config] [seon.profile :as profile] [test-runner :as runner]))

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

(defn- digests [db env] (select-keys (program/reach-digests db {:env env :tree "t"}) ts))

(defn- scratch-dir []
  (let [d (str (System/getProperty "user.dir") "/target/tmp/prove-" (random-uuid))] (.mkdirs (java.io.File. d)) d))

(defn- prove!
  "What bin/test does for the candidate db: plan against base digests, run the rest."
  [base db env dir]
  (let [cand (digests db env)
        p (proofs/plan base cand (proofs/greens dir (vec (vals cand))) #{})
        ran (proofs/run-tests! (:run p) cand (fn [_]))]
    (proofs/store-greens! #(fx/write-file {:req %}) dir ran)
    (assoc p :cand cand :ran ran)))

(deftest an-unchanged-tree-runs-nothing-and-reuses-everything
  (let [db (schema (prog) :app.pv-calc/n "int?") dir (scratch-dir)
        first-run (prove! nil db "e" dir)
        second-run (prove! (:cand first-run) db "e" dir)]
    (is (= ts (set (:run first-run))) "nothing stored yet: every test runs")
    (is (every? proofs/green? (vals (:ran first-run))) "and each is green")
    (is (= [] (:run second-run)) "the same tree again: nothing runs")
    (is (= ts (set (keys (:reused second-run)))) "every result is reused from its digest")
    (is (= (:cand first-run) (:cand second-run)) "the digests are the tree's, not the run's")
    (is (= {} (proofs/greens dir (vec (vals {})))) "a branch holding no test reads no greens (an agent died on (vals {}), nil)")))

(deftest a-one-defn-change-runs-only-what-reaches-it
  (let [db (schema (prog) :app.pv-calc/n "int?") dir (scratch-dir)
        base (:cand (prove! nil db "e" dir))
        db' (record db 'app.pv-calc "(defn neg [a] (- 0 a))")
        r (prove! base db' "e" dir)]
    (is (= '[app.pv-neg-test/neg-test] (:run r)) "neg changed: only the test reaching it runs")
    (is (= '[app.pv-neg-test/neg-test] (:selected r)) "and only it is selected against the base")
    (is (proofs/green? (get (:ran r) 'app.pv-neg-test/neg-test)))
    (testing "a proof always judges: selected, and reused when green at its digest"
      (let [p (proofs/plan base (:cand r) (proofs/greens dir (vec (vals (:cand r)))) '#{app.pv-calc-test/add-test})]
        (is (= '[app.pv-calc-test/add-test app.pv-neg-test/neg-test] (:selected p)))
        (is (= [] (:run p)) "both are green at their digests now")))))

(deftest a-macro-schema-or-config-change-widens-the-selection
  (let [db (schema (prog) :app.pv-calc/n "int?") dir (scratch-dir)
        base (:cand (prove! nil db "e" dir))
        run-of (fn [db env] (:run (proofs/plan base (digests db env) (proofs/greens dir (vec (vals (digests db env)))) #{})))]
    (is (= '[app.pv-calc-test/twice-test] (run-of (record db 'app.pv-calc "(defmacro twice [x] `(+ ~x ~x))") "e"))
        "a macro's body changed: the test expanding it runs, not the others in its namespace")
    (is (= '[app.pv-neg-test/half-test] (run-of (schema db :app.pv-calc/n "pos-int?") "e"))
        "a schema entry changed: the test reaching the contract naming it runs")
    (is (= (sort-by str ts) (run-of db "e2"))
        "config, deps or a pin changed (:env, the inputs with no program facts): every test runs")))

(deftest a-check-step-is-keyed-by-what-its-function-reaches
  (let [db (record (prog) 'user "(ns app.pv-check (:require [app.pv-calc :as c]))"
                   'app.pv-check "(defn smoke [] (c/add 1 2))" 'app.pv-check "(defn idle [] (c/neg 1))")
        roots {"smoke" '[app.pv-check/smoke] "idle" '[app.pv-check/idle]}
        dg (fn [db] (program/reach-digests db {:env "e" :tree "t"} roots))
        before (dg db)
        after (dg (record db 'app.pv-calc "(defn add [a b] (+ b a))"))]
    (is (= #{"smoke" "idle"} (set (keys before))) "each step its own digest")
    (is (not= (before "smoke") (after "smoke")) "add changed: the step whose function calls it moves")
    (is (= (before "idle") (after "idle")) "the step not reaching add keeps its digest, so its green is reused")
    (testing "a step reading the whole tree is keyed by the tree; the others by their functions"
      (let [all (fn [roots] (zipmap (keys roots) (map str (keys roots))))
            sd (proofs/step-digests all {:env "e" :tree "t1" :base "b1"})]
        (is (= (set (keys proofs/check-steps)) (set (keys (:digests sd)))))
        (is (= [] (:missing sd)))
        (is (not= ((:digests sd) "laws") ((:digests (proofs/step-digests all {:env "e" :tree "t2" :base "b1"})) "laws")))
        (testing "health reads what the tree caused since its base: another base is another key, and no base no key"
          (is (not= ((:digests sd) "health") ((:digests (proofs/step-digests all {:env "e" :tree "t1" :base "b2"})) "health")))
          (is (= ((:digests sd) "laws") ((:digests (proofs/step-digests all {:env "e" :tree "t1" :base "b2"})) "laws")) "laws reads the tree alone")
          (is (= ["health"] (:missing (proofs/step-digests all {:env "e" :tree "t1"})))))))
    (testing "a step runs a seon.check mode (the binary, a store branch) exactly when check-steps names functions for it"
      (is (= #{"laws" "lmdb-kill"} (set (remove proofs/step-needs-binary? (keys proofs/check-steps)))) "laws reads the tree, lmdb-kill has a binary of its own")
      (is (every? proofs/step-needs-binary? ["smoke" "fx" "durable" "e2e-loop" "mg-check"])))
    (testing "a step whose function the program does not define (renamed) has no digest and is named missing"
      (is (= {} (program/reach-digests db {:env "e" :tree "t"} {"gone" '[app.pv-check/renamed]})))
      (is (= ["smoke"] (:missing (proofs/step-digests (fn [roots] (dissoc (zipmap (keys roots) (map str (keys roots))) "smoke")) {:env "e" :tree "t" :base "b"})))))))

(deftest a-run-that-names-nothing-leaves-out-what-reaches-a-process
  ;; owner, 2026-10-23: tests shouldn't be running agent turns, not automatically at least. A
  ;; step (or test) whose function reaches a process var (a fork, a shell, a root booted for it:
  ;; seon.program/process-vars) is classified by the one walk and left out of a run that names
  ;; nothing; it runs when named
  (let [db (record (prog) 'user "(ns app.pv-boot (:require [app.pv-calc :as c] [seon.lib]))"
                   'app.pv-boot "(defn smoke [] (seon.lib/fork! {:child (fn [] nil) :refused identity :parent identity}))"
                   'app.pv-boot "(defn calc [] (c/add 1 2))")
        idx (program/reach-index db {:env "e" :tree "t"} {"smoke" '[app.pv-boot/smoke] "calc" '[app.pv-boot/calc]})
        process (set (keep (fn [[k v]] (when (:process? v) k)) idx))
        cand {"smoke" (:digest (idx "smoke")) "calc" (:digest (idx "calc")) 'app.pv-calc-test/add-test "d"}]
    (is (= #{"smoke"} process) "the step that forks reaches a process; the one that adds does not")
    (is (= (program/reach-digests db {:env "e" :tree "t"} {"smoke" '[app.pv-boot/smoke] "calc" '[app.pv-boot/calc]})
           (update-vals idx :digest))
        "the walk's digests are reach-digests'")
    (is (= {"calc" (:digest (idx "calc")) 'app.pv-calc-test/add-test "d"} (runner/automatic cand process))
        "a run that names nothing selects every key but it"))
  (testing "through the plan bin/prove and bin/test run (test-runner/plan-of), on a checkout of two tests"
    (let [d (scratch-dir)
          files {"deps.edn" "{}"
                 "test/app/pt_calc_test.clj" "(ns app.pt-calc-test (:require [clojure.test :refer [deftest is]]))\n(deftest adds (is (= 3 (+ 1 2))))\n"
                 "test/app/pt_shell_test.clj" "(ns app.pt-shell-test (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh]))\n(deftest shells (is (zero? (:exit (sh/sh \"true\")))))\n"}
          _ (doseq [[p t] files] (.mkdirs (.getParentFile (java.io.File. (str d "/" p)))) (spit (str d "/" p) t))
          _ (clojure.java.shell/sh "git" "-C" d "init" "-q")
          _ (clojure.java.shell/sh "git" "-C" d "add" ".")
          auto (runner/plan-of d "tests" false nil)
          named (runner/plan-of d "tests" false ["app.pt-shell-test"])]
      (is (= ['app.pt-calc-test/adds] (:run-tests auto)) "named nothing: the in-process test runs, the shelling one does not")
      (is (= ['app.pt-shell-test/shells] (:left auto)) "and the plan says it left that one out")
      (is (= ['app.pt-shell-test/shells] (:run-tests named)) "named, it runs")
      (testing "a plan of tests and check steps walks the program once: one reach graph serves both indexes"
        (profile/begin!)
        (runner/plan-of d "both" false nil)
        (is (= 1 (some #(when (= 'seon.program/reach-graph (:var %)) (:calls %)) (profile/touched))) "the plan built one reach graph (none counted: instrumentation is off)")))))

(deftest shards-split-by-namespace-and-each-result-is-stored
  (record (ts/test-db) 'user "(ns app.pv-red-test (:require [clojure.test :refer [deftest is use-fixtures]]))"
          'app.pv-red-test "(def ^:dynamic *fx* nil)"
          'app.pv-red-test "(use-fixtures :each (fn [f] (binding [*fx* :on] (f))))"
          'app.pv-red-test "(deftest red-test (is (= 1 2)))"
          'app.pv-red-test "(deftest fixture-test (is (= :on *fx*)))")
  (prog)
  (let [dir (scratch-dir)
        tests '[app.pv-red-test/red-test app.pv-red-test/fixture-test app.pv-calc-test/add-test app.pv-calc-test/twice-test app.pv-neg-test/neg-test]
        dg (zipmap tests ["d-red" "d-fx" "d-add" "d-twice" "d-neg"])
        notes (atom [])
        ran (proofs/run-tests! tests dg #(swap! notes conj %))
        unstored (proofs/store-greens! #(fx/write-file {:req %}) dir ran)]
    (is (= [["app.pv-calc-test/add-test" "app.pv-calc-test/twice-test"] ["app.pv-neg-test/neg-test"] ["app.pv-red-test/fixture-test" "app.pv-red-test/red-test"]]
           (sort-by first (map #(sort (mapv str %)) (runner/shards tests 3 (zipmap tests [1 1 1 1 2])))))
        "a namespace within its share of the measured ms stays in one shard")
    (is (= 2 (count (runner/shards tests 2 {}))) "never more shards than jobs")
    (is (= [] unstored) "every green written")
    (is (= (set tests) (set (keys ran))) "every test has a result")
    (is (= {:pass 0 :fail 1 :error 0} (select-keys (ran 'app.pv-red-test/red-test) [:pass :fail :error])))
    (is (proofs/green? (ran 'app.pv-red-test/fixture-test)) "its namespace's fixtures ran around it")
    (is (= (count tests) (count (filter :seon.test/started @notes))) "each test named as it starts")
    (is (every? #(.isFile (java.io.File. (proofs/result-path dir (dg %)))) (remove #{'app.pv-red-test/red-test} tests)) "each green stored at its digest")
    (is (= #{"d-fx" "d-add" "d-twice" "d-neg"} (set (keys (proofs/greens dir (vec (vals dg)))))) "only greens are stored and reused: a red runs again")))

(deftest a-failed-write-or-a-throwing-fixture-is-recorded-red
  ;; review 2026-10-22: a write's declared error was dropped (read as stored), and a fixture's
  ;; throw went only to printed output with a guessed message
  (prog)
  (record (ts/test-db) 'user "(ns app.pv-fx-test (:require [clojure.test :refer [deftest is use-fixtures]]))"
          'app.pv-fx-test "(use-fixtures :once (fn [f] (throw (ex-info \"the fixture's store is gone\" {:store :gone}))))"
          'app.pv-fx-test "(deftest never-runs (is true))")
  (let [ran (proofs/run-tests! '[app.pv-calc-test/add-test] '{app.pv-calc-test/add-test "d-add"} (fn [_]))
        unstored (proofs/store-greens! #(fx/write-file {:req %}) "/dev/null/results" ran)
        notes (atom [])
        thrown (proofs/run-tests! '[app.pv-fx-test/never-runs] '{app.pv-fx-test/never-runs "d-never"} #(swap! notes conj %))
        t (thrown 'app.pv-fx-test/never-runs)]
    (is (proofs/green? (ran 'app.pv-calc-test/add-test)) "the test passed")
    (is (= ['app.pv-calc-test/add-test] (map :key unstored)) "and the failed write of its result is answered, never read as stored")
    (is (re-find #"Not a directory|io|staging" (str (:message (first unstored)))) (str unstored))
    (is (= 1 (:error t)))
    (is (some #(and (= 'app.pv-fx-test/never-runs (:test %)) (= :error (:type (:seon.test/report %)))) @notes) "the unreported test is reported, as an error")
    (is (re-find #"the fixture's store is gone" (:message t)) "the throw's own message, not a guess")
    (is (= "{:store :gone}" (:data t)) "and its data")))

(deftest shards-are-dealt-by-measured-work
  ;; pure-tests 2026-10-23: dealt by count, the full run's shards ended 437, 397, 110 and 68 s
  ;; into it; by each test's last measured ms they end together
  (is (= '#{[x/a] [y/b y/c z/d]} (set (runner/shards '[x/a y/b y/c z/d] 2 '{x/a 10 y/b 3 y/c 3 z/d 3})))
      "the longest alone, the rest together")
  (is (= '#{[p/a p/c q/e] [p/b p/d]} (set (runner/shards '[p/a p/b p/c p/d q/e] 2 '{p/a 5 p/b 5 p/c 5 p/d 5 q/e 1})))
      "a namespace longer than a shard's share is split into share-sized parts")
  (is (= 2 (count (runner/shards '[x/a y/b y/c] 2 '{x/a 10}))) "a test never measured counts as the mean of those measured"))

(deftest a-tree-changed-since-the-plan-stores-nothing
  ;; re-review 2026-10-22: a shard requires its namespaces from disk seconds or minutes after the
  ;; plan took its digests; an edit in between ran code the digests do not name, so a green
  ;; proven on it is never stored under them
  (prog)
  (let [here (System/getProperty "user.dir")
        shard (fn [tree] (let [f (str (scratch-dir) "/shard-0.edn") dg (str "proof-tree-" (random-uuid))]
                           (spit f (pr-str {:tests '[app.pv-calc-test/add-test] :digests {'app.pv-calc-test/add-test dg} :tree tree}))
                           (binding [*out* (java.io.StringWriter.)] (#'runner/shard! f))
                           (.isFile (java.io.File. (proofs/result-path (seon.config/value :seon.config.source/test-results-dir) dg)))))]
    (is (shard (fx/tree-id here)) "the tree the plan named: the green is stored")
    (is (not (shard "a-tree-before-an-edit")) "another tree: nothing stored")))

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
     :sizes [100 1000 10000]}))
