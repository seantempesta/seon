(ns seon.proofs-test
  "prove: a key runs exactly when no green
  result is stored at its reach digest, so an unchanged tree runs nothing, a change runs what
  reaches it (through macros, schemas and the inputs with no program facts), a bin/check step
  is keyed like a test by what its functions reach, and the run is shards whose greens are
  stored."
  (:require [seon.lib] [seon.agent] [clojure.test :refer [deftest is testing]] [clojure.string :as str] [datahike.api :as d] [clojure.java.shell]
            [seon.test-store :as ts] [my.test :as mt] [seon.db :as pdb] [seon.program :as program] [seon.proofs :as proofs] [seon.cost :as cost]
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

(defn- digests [db env] (select-keys (ts/reach-digests db {:env env :tree "t" :config {}}) ts))

(defn- scratch-dir []
  (let [d (str (seon.config/checkout) "/target/tmp/prove-" (random-uuid))] (.mkdirs (java.io.File. d)) d))

(defn- with-dir
  "f called with a fresh verdict cache directory under this checkout's target/tmp, removed after."
  [f]
  (let [dir (str (seon.config/checkout) "/target/tmp/verdicts-" (random-uuid))]
    (try (f dir) (finally (seon.lib/remove-tree! dir)))))

(defn- stored
  "The verdicts the cache at dir holds for cand ({key digest}): what bin/test reads."
  [dir cand] (fx/stored-results dir cand))

(defn- store!
  "Cache ran ({key result}) at dir through seon.fx/write-verdicts!, as a run does."
  ([dir ran] (store! dir ran {}))
  ([dir ran failures] (fx/write-verdicts! dir {:id "proofs-test"} ran failures)))

(defn- prove!
  "What bin/test does for the candidate db: plan against base digests, run the rest, cache them at dir."
  [base db env dir]
  (let [cand (digests db env)
        p (proofs/plan base cand (stored dir cand) #{})
        ran (proofs/run-tests! (:run p) cand (fn [_]))]
    (store! dir ran)
    (assoc p :cand cand :ran ran)))

(deftest ^{:seon.test/estimate {:ticks 10000000}} an-unchanged-tree-runs-nothing-and-reuses-everything
  (with-dir (fn [dir]
  (let [db (schema (prog) :app.pv-calc/n "int?")
        first-run (prove! nil db "e" dir)
        second-run (prove! (:cand first-run) db "e" dir)]
    (is (= ts (set (:run first-run))) "nothing stored yet: every test runs")
    (is (every? proofs/green? (vals (:ran first-run))) "and each is green")
    (is (= [] (:run second-run)) "the same tree again: nothing runs")
    (is (= ts (set (keys (:reused second-run)))) "every result is reused from its digest")
    (is (= (:cand first-run) (:cand second-run)) "the digests are the tree's, not the run's")
    (is (= {} (stored dir {})) "a branch holding no test reads no greens (an agent died on (vals {}), nil)")))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-one-defn-change-runs-only-what-reaches-it
  (with-dir (fn [dir]
  (let [db (schema (prog) :app.pv-calc/n "int?")
        base (:cand (prove! nil db "e" dir))
        db' (record db 'app.pv-calc "(defn neg [a] (- 0 a))")
        r (prove! base db' "e" dir)]
    (is (= '[app.pv-neg-test/neg-test] (:run r)) "neg changed: only the test reaching it runs")
    (is (= '[app.pv-neg-test/neg-test] (:selected r)) "and only it is selected against the base")
    (is (proofs/green? (get (:ran r) 'app.pv-neg-test/neg-test)))
    (testing "a proof always judges: selected, and reused when green at its digest"
      (let [p (proofs/plan base (:cand r) (stored dir (:cand r)) '#{app.pv-calc-test/add-test})]
        (is (= '[app.pv-calc-test/add-test app.pv-neg-test/neg-test] (:selected p)))
        (is (= [] (:run p)) "both are green at their digests now")))))))

(deftest ^{:seon.test/estimate {:ticks 20000000}} a-macro-schema-or-config-change-widens-the-selection
  (with-dir (fn [dir]
  (let [db (schema (prog) :app.pv-calc/n "int?")
        base (:cand (prove! nil db "e" dir))
        run-of (fn [db env] (:run (proofs/plan base (digests db env) (stored dir (digests db env)) #{})))]
    (is (= '[app.pv-calc-test/twice-test] (run-of (record db 'app.pv-calc "(defmacro twice [x] `(+ ~x ~x))") "e"))
        "a macro's body changed: the test expanding it runs, not the others in its namespace")
    (is (= '[app.pv-neg-test/half-test] (run-of (schema db :app.pv-calc/n "pos-int?") "e"))
        "a schema entry changed: the test reaching the contract naming it runs")
    (is (= (sort-by str ts) (run-of db "e2"))
        "deps or a pin changed (:env, the inputs with no program facts): every test runs")))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-check-step-is-keyed-by-what-its-function-reaches
  (let [db (record (prog) 'user "(ns app.pv-check (:require [app.pv-calc :as c]))"
                   'app.pv-check "(defn smoke [] (c/add 1 2))" 'app.pv-check "(defn idle [] (c/neg 1))")
        roots {"smoke" '[app.pv-check/smoke] "idle" '[app.pv-check/idle]}
        dg (fn [db] (ts/reach-digests db {:env "e" :tree "t" :config {}} roots))
        before (dg db)
        after (dg (record db 'app.pv-calc "(defn add [a b] (+ b a))"))]
    (is (= #{"smoke" "idle"} (set (keys before))) "each step its own digest")
    (is (not= (before "smoke") (after "smoke")) "add changed: the step whose function calls it moves")
    (is (= (before "idle") (after "idle")) "the step not reaching add keeps its digest, so its green is reused")
    (testing "a step reading the whole tree is keyed by the tree; the others by their functions"
      (let [all (fn [roots] (zipmap (keys roots) (map str (keys roots))))
            sd (proofs/step-digests all {:env "e" :tree "t1" :config {} :base "b1"})]
        (is (= (set (keys (remove (comp #{:mg} val) proofs/check-steps))) (set (keys (:digests sd)))) "every step but mg-check, whose keys are its contracts' (mg-roots), has a digest")
        (is (= [] (:missing sd)))
        (is (not= ((:digests sd) "laws") ((:digests (proofs/step-digests all {:env "e" :tree "t2" :config {} :base "b1"})) "laws")))
        (testing "health reads what the tree caused since its base: another base is another key, and no base no key"
          (is (not= ((:digests sd) "health") ((:digests (proofs/step-digests all {:env "e" :tree "t1" :config {} :base "b2"})) "health")))
          (is (= ((:digests sd) "laws") ((:digests (proofs/step-digests all {:env "e" :tree "t1" :config {} :base "b2"})) "laws")) "laws reads the tree alone")
          (is (= ["health"] (:missing (proofs/step-digests all {:env "e" :tree "t1" :config {}}))))))
      (testing "the forks and lmdb steps are keyed by the pins: a pin bump selects them, a config key outside the pins does not"
        (let [all (fn [roots] (zipmap (keys roots) (map str (keys roots))))
              at (fn [cfg] (:digests (proofs/step-digests all {:env "e" :tree "t" :config cfg :base "b"})))
              before (at {:seon.pin/jolt "j1" :seon.pin/lmdb "l1" :seon.config.agent/max-turns "40"})]
          (doseq [step ["forks" "lmdb-kill" "lmdb-lock" "lmdb-crash"]]
            (is (not= (before step) ((at {:seon.pin/jolt "j2" :seon.pin/lmdb "l1" :seon.config.agent/max-turns "40"}) step)) (str step ": jolt's pin moved"))
            (is (= (before step) ((at {:seon.pin/jolt "j1" :seon.pin/lmdb "l1" :seon.config.agent/max-turns "41"}) step)) (str step ": a config key is no pin"))))))
    (testing "a step runs a seon.check mode (the binary, a store branch) exactly when check-steps names functions for it"
      (is (= #{"laws" "lmdb-kill" "lmdb-lock" "lmdb-crash" "forks" "health"} (set (remove proofs/step-needs-binary? (keys proofs/check-steps)))) "laws reads the tree, the lmdb steps have binaries of their own, forks runs Jolt, health reads the program store's kept trees")
      (is (every? proofs/step-needs-binary? ["smoke" "fx" "e2e-loop" "mg-check"])))
    (testing "a step whose function the program does not define (renamed) has no digest and is named missing"
      (is (= {} (ts/reach-digests db {:env "e" :tree "t" :config {}} {"gone" '[app.pv-check/renamed]})))
      (is (= ["smoke"] (:missing (proofs/step-digests (fn [roots] (dissoc (zipmap (keys roots) (map str (keys roots))) "smoke")) {:env "e" :tree "t" :config {} :base "b"})))))))

(deftest ^{:seon.test/estimate {:ticks 20000000}} each-pure-contract-is-a-key-of-the-generative-check
  ;; owner, 2026-10-29: the contract check is per function, keyed by digest like a test. A pure
  ;; contracted function's key is its reach (its form, what it calls, the schemas its contract
  ;; names); a change re-checks only the contracts it reaches (docs/checks.md#the-generative-check-is-per-function)
  ;; the namespaces are the real ones (mg-roots' purity is by namespace); the functions are new
  (let [db (-> (record (ts/test-db) 'user "(ns seon.template)"
                       'seon.template "(defn mgk-a {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
                       'seon.template "(defn mgk-b {:malli/schema [:=> [:cat :int] :int]} [x] (inc (mgk-a x)))"
                       'seon.template "(defn mgk-c {:malli/schema [:=> [:cat :seon.cost/row] :int]} [x] (dec x))"
                       'seon.template "(defn mgk-d [x] (inc x))"
                       'seon.template "(defn mgk-step {:malli/schema :seon.step/step-fn} [& args] args)"
                       'user "(ns seon.sock)"
                       'seon.sock "(defn kill {:malli/schema [:=> [:cat :int] :int]} [pid] pid)")
               (schema :seon.step/step-fn "map?") (schema :seon.cost/row "map?"))
        inputs {:env "e" :tree "t" :config {}}
        keys-of (fn [db] (proofs/mg-roots (program/reach-graph db nil)))
        dg (fn [db] (ts/reach-digests db inputs (keys-of db)))
        before (dg db)
        after-a (dg (record db 'seon.template "(defn mgk-a {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))"))
        after-n (dg (schema db :seon.cost/row "some?"))]
    (is (= #{"mg/seon.template/mgk-a" "mg/seon.template/mgk-b" "mg/seon.template/mgk-c"} (set (keys (keys-of db))))
        "a pure contracted function is a key; an uncontracted one, a proc's step-fn and a host namespace's function are not")
    (is (= 'seon.template/mgk-a (proofs/mg-fn "mg/seon.template/mgk-a")))
    (is (not-any? #(= (before %) (after-a %)) ["mg/seon.template/mgk-a" "mg/seon.template/mgk-b"])
        "mgk-a changed: its key and its caller's move")
    (is (= (before "mg/seon.template/mgk-c") (after-a "mg/seon.template/mgk-c")) "a contract that does not reach it keeps its digest")
    (is (not= (before "mg/seon.template/mgk-c") (after-n "mg/seon.template/mgk-c")) "the schema its contract names changed: it moves")
    (is (= (select-keys before ["mg/seon.template/mgk-a" "mg/seon.template/mgk-b"]) (select-keys after-n ["mg/seon.template/mgk-a" "mg/seon.template/mgk-b"]))
        "and the contracts not naming it keep theirs")
    (testing "the plan runs only the contracts whose digest has no green verdict"
      (let [found (into {} (for [dg (vals before)] [dg {:pass 1 :fail 0 :error 0}]))
            p (proofs/plan nil after-a found #{})]
        (is (= ["mg/seon.template/mgk-a" "mg/seon.template/mgk-b"] (:run p)))
        (is (= ["mg/seon.template/mgk-c"] (keys (:reused p))))
        (is (= [] (:run (proofs/plan nil before found #{}))) "an unchanged tree runs nothing")))
    (testing "a contract key runs in a test shard: it needs no binary"
      (is (every? #(and (proofs/mg-key? %) (not (proofs/step-needs-binary? %))) (keys before)))
      (is (proofs/mg-key? proofs/mg-registry))
      (is (not (proofs/mg-key? "mg-check"))))
    (testing "the registry's key is the schema registry: a schema change moves it, a function's change does not"
      (let [g #(program/reach-graph % nil)
            rd #(proofs/mg-registry-digest (g %) "e")]
        (is (not= (rd db) (rd (schema db :seon.cost/row "some?"))) "a schema entry changed")
        (is (= (rd db) (rd (record db 'seon.template "(defn mgk-a {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))"))) "a function changed")))
    (testing "contracts are dealt among the shards that run, by their measured ms; with no test shard, one"
      (let [mg {"mg/a" "1" "mg/b" "2" "mg/c" "3"}]
        (is (= [[[] mg]] (runner/deal-mg [] mg {} {})) "no test shard: one process for all")
        (is (= [] (runner/deal-mg [] {} {} {})))
        (let [dealt (runner/deal-mg '[[t1 t2] [t3]] mg '{t1 100 t2 100 t3 1} {"mg/a" 50 "mg/b" 50 "mg/c" 1})]
          (is (= 2 (count dealt)) "the contracts start no shard")
          (is (= mg (apply merge (map second dealt))) "each in exactly one")
          (is (= #{"mg/a" "mg/b" "mg/c"} (set (mapcat (comp keys second) dealt))))
          (is (= 3 (count (mapcat (comp keys second) dealt))))
          (is (= #{"mg/a" "mg/b" "mg/c"} (set (keys (second (second dealt))))) "the lighter shard takes them: t3's has 1 ms of tests"))))))

(deftest ^{:seon.test/estimate {:ticks 30000000}} a-run-that-names-nothing-leaves-out-what-reaches-a-process
  ;; owner, 2026-10-23: tests shouldn't be running agent turns, not automatically at least. A
  ;; step (or test) whose function reaches a process var (a fork, an agent, a root booted for it:
  ;; seon.program/process-vars) is classified by the one walk and left out of a run that names
  ;; nothing; it runs when named. A shell call is no process (audit 2026-10-26, item 7): a test
  ;; that only shells runs in a plan that names nothing
  (let [db (record (prog) 'user "(ns app.pv-boot (:require [app.pv-calc :as c] [seon.lib]))"
                   'app.pv-boot "(defn smoke [] (seon.lib/fork! {:child (fn [] nil) :refused identity :parent identity}))"
                   'app.pv-boot "(defn calc [] (c/add 1 2))")
        idx (ts/reach-index db {:env "e" :tree "t" :config {}} {"smoke" '[app.pv-boot/smoke] "calc" '[app.pv-boot/calc]})
        process (set (keep (fn [[k v]] (when (:process? v) k)) idx))
        cand {"smoke" (:digest (idx "smoke")) "calc" (:digest (idx "calc")) 'app.pv-calc-test/add-test "d"}]
    (is (= #{"smoke"} process) "the step that forks reaches a process; the one that adds does not")
    (is (= (ts/reach-digests db {:env "e" :tree "t" :config {}} {"smoke" '[app.pv-boot/smoke] "calc" '[app.pv-boot/calc]})
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

(deftest ^{:seon.test/estimate {:ticks 10000000}} shards-split-by-namespace-and-each-result-is-stored
  (record (ts/test-db) 'user "(ns app.pv-red-test (:require [clojure.test :refer [deftest is use-fixtures]]))"
          'app.pv-red-test "(def ^:dynamic *fx* nil)"
          'app.pv-red-test "(use-fixtures :each (fn [f] (binding [*fx* :on] (f))))"
          'app.pv-red-test "(deftest red-test (is (= 1 2)))"
          'app.pv-red-test "(deftest fixture-test (is (= :on *fx*)))")
  (prog)
  (with-dir (fn [dir]
  (let [tests '[app.pv-red-test/red-test app.pv-red-test/fixture-test app.pv-calc-test/add-test app.pv-calc-test/twice-test app.pv-neg-test/neg-test]
        dg (zipmap tests ["d-red" "d-fx" "d-add" "d-twice" "d-neg"])
        notes (atom [])
        ran (proofs/run-tests! tests dg #(swap! notes conj %))
        db (store! dir ran {'app.pv-red-test/red-test [{:type :fail :message "1 is not 2"}]})]
    (is (= [1 2 2] (sort (map count (proofs/shards tests 3 (zipmap tests [1 1 1 1 2])))))
        "a namespace over half a shard's share is split: five tests of 6 ms over three shards")
    (is (= (set tests) (set (apply concat (proofs/shards tests 3 (zipmap tests [1 1 1 1 2]))))) "and none is lost")
    (is (= 2 (count (proofs/shards tests 2 {}))) "never more shards than jobs")
    (is (not (contract/error? db)) "every result recorded")
    (is (= (set tests) (set (keys ran))) "every test has a result")
    (is (= {:pass 0 :fail 1 :error 0} (select-keys (ran 'app.pv-red-test/red-test) [:pass :fail :error])))
    (is (proofs/green? (ran 'app.pv-red-test/fixture-test)) "its namespace's fixtures ran around it")
    (is (= (count tests) (count (filter :seon.test/started @notes))) "each test named as it starts")
    (let [found (stored dir dg)]
      (is (= (set (vals dg)) (set (keys found))) "each result stored at its digest, the red too")
      (is (every? #(proofs/green? (found (dg %))) (remove #{'app.pv-red-test/red-test} tests)) "each green stored green")
      (is (= {:pass 0 :fail 1 :error 0} (select-keys (found "d-red") [:pass :fail :error])) "the red is stored red: reused as red, not run again")
      (is (= "1 is not 2" (:message (first (:failures (found "d-red"))))) "with the failures it recorded"))))))

;; docs/merging.md#verdicts-are-a-cache: a run asks the cache for a test's green just before the test would start, so a green
;; another run stored since this run's plan is reused, never run again (owner, 2026-11-10: ten agents asking for the whole
;; suite "just want to know if the tests have passed"); a step toward the test service owning each (test, digest) item
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-green-stored-since-the-plan-is-reused-not-run
  (prog)
  (let [tests '[app.pv-calc-test/add-test app.pv-calc-test/twice-test]
        dg '{app.pv-calc-test/add-test "d-add" app.pv-calc-test/twice-test "d-twice"}
        notes (atom [])
        asked (atom [])
        ;; another run stored twice-test's green at its digest after this run was planned
        reused (fn [t d] (swap! asked conj [t d]) (when (= t 'app.pv-calc-test/twice-test) {:digest d :pass 1 :fail 0 :error 0}))
        ran (proofs/run-tests! tests dg #(swap! notes conj %) nil reused)
        started (set (keep :seon.test/started @notes))]
    (is (= '[[app.pv-calc-test/add-test "d-add"] [app.pv-calc-test/twice-test "d-twice"]] (sort-by str @asked)) "each test asked for at its digest")
    (is (started 'app.pv-calc-test/add-test) "a test with no stored green runs")
    (is (not (started 'app.pv-calc-test/twice-test)) "one stored green since the plan never starts")
    (is (= {:reused true :pass 1} (select-keys (ran 'app.pv-calc-test/twice-test) [:reused :pass])) "its result is the stored green, said reused")
    (is (some #(and (:reused %) (= 'app.pv-calc-test/twice-test (:test %))) @notes) "and told as it is reused, as a run's result is as it ends")
    (is (proofs/green? (ran 'app.pv-calc-test/add-test)))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-refused-commit-or-a-throwing-fixture-is-recorded-red
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
        ;; a cache that refuses the write (its directory is a file): the verdict is not cached
        unrecorded (let [f (str (seon.config/checkout) "/target/tmp/not-a-dir-" (random-uuid))]
                     (spit f "") (try (fx/write-verdicts! f {:id "proofs-test"} ran {}) (finally (.delete (java.io.File. f)))))
        notes (atom [])
        thrown (proofs/run-tests! '[app.pv-fx-test/never-runs] '{app.pv-fx-test/never-runs "d-never"} #(swap! notes conj %))
        t (thrown 'app.pv-fx-test/never-runs)]
    (is (proofs/green? (ran 'app.pv-calc-test/add-test)) "the test passed")
    (is (= :seon.fx/io-error (:seon.error/type unrecorded)) "and the refused write of its verdict is answered, never read as cached")
    (is (contract/error? unrecorded) (str unrecorded))
    (is (= 1 (:error t)))
    (is (some #(and (= 'app.pv-fx-test/never-runs (:test %)) (= :error (:type (:seon.test/report %)))) @notes) "the unreported test is reported, as an error")
    (is (re-find #"the fixture's store is gone" (:message t)) "the throw's own message, not a guess")
    (is (= "{:store :gone}" (:data t)) "and its data")))

;; Estimate: two tiny tests, one building a vector of 100k (~1M ticks, ~2 MB), and their runner = ~5000000.
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-test-past-its-bytes-budget-ends-typed
  ;; docs/merging.md#a-tests-tick-budget, task seon.proofs/a-tests-bytes-and-wall-are-budgets-as-its-ticks-are (owner: "we don't
  ;; accept large overruns for ticks, mem or time"): bytes are a budget as ticks are, ten times what the deftest states
  (record (ts/test-db) 'user "(ns app.pv-bytes-test (:require [clojure.test :refer [deftest is]]))"
          'app.pv-bytes-test "(deftest ^{:seon.test/estimate {:ticks 100000000 :bytes 1000}} allocates (is (= 100000 (count (vec (range 100000))))))"
          'app.pv-bytes-test "(deftest ^{:seon.test/estimate {:ticks 100000000 :bytes 100000000}} within (is (= 100000 (count (vec (range 100000))))))")
  (let [notes (atom [])
        ran (proofs/run-tests! '[app.pv-bytes-test/allocates app.pv-bytes-test/within] {} #(swap! notes conj %))
        spent (some #(when (= :seon.test/bytes-spent (get-in % [:seon.test/report :seon.error/type])) (:seon.test/report %)) @notes)]
    (is (= 1 (:error (ran 'app.pv-bytes-test/allocates))) "past ten times its stated bytes: the test's own error")
    (is (= 10000 (:budget spent)) (pr-str spent))
    (is (> (:bytes spent) 10000) "naming what it allocated")
    (is (proofs/green? (ran 'app.pv-bytes-test/within)) "within its budget: green")))

(deftest ^{:seon.test/estimate {:ticks 10000000}} each-test-has-its-own-budget
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

;; docs/merging.md#a-test-child-is-a-fork-of-a-live-agent: a test a test child ends from outside (its wall bound: the child throws
;; :seon.test/wall-spent into the test's fiber) is that test's own :error, named, and the namespace goes on. Red before: the throw ended the namespace, the test answered no verdict and every later test was reported
;; red unrun ("run threw before this test reported"; tests' batch through 7888, 2026-11-13: 33 no-verdicts, 91 unrun reds)
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-test-ended-from-outside-is-its-own-error-and-the-namespace-goes-on
  (record (ts/test-db) 'user "(ns app.pv-ended-test (:require [clojure.test :refer [deftest is]]))"
          'app.pv-ended-test "(deftest parks (is true))"
          'app.pv-ended-test "(deftest after (is (= 3 (+ 1 2))))")
  (let [notes (atom [])
        ;; what an agent's test child's wall bound does when it lands while the test's start notice is parked on a full link: the
        ;; typed error thrown into the test's fiber there, outside clojure.test's try (thrown here directly, so no timing decides)
        note! (fn [x]
                (swap! notes conj x)
                (when (= 'app.pv-ended-test/parks (:seon.test/started x))
                  (throw (ex-info "the test ran past its wall bound (#'app.pv-ended-test/parks)"
                                  {:seon.error/type :seon.test/wall-spent :test 'app.pv-ended-test/parks}))))
        ran (proofs/run-tests! '[app.pv-ended-test/parks app.pv-ended-test/after] {} note! 50000000)
        ended (some #(when (and (= 'app.pv-ended-test/parks (:test %)) (= :error (get-in % [:seon.test/report :type]))) (:seon.test/report %)) @notes)]
    (is (= {:pass 0 :fail 0 :error 1} (select-keys (ran 'app.pv-ended-test/parks) [:pass :fail :error])) "the ended test is red")
    (is (not (:unreported (ran 'app.pv-ended-test/parks))) "reported as itself, never as a namespace's throw")
    (is (= :seon.test/wall-spent (:seon.error/type ended)) (str "its report carries the typed error that ended it: " (pr-str (dissoc ended :actual))))
    (is (re-find #"wall bound" (str (:message ended))) "and its message")
    (is (proofs/green? (ran 'app.pv-ended-test/after)) "and the next test ran, green")))

;; docs/merging.md#a-tests-tick-budget: a test's budget is ten times the ticks its deftest states it should cost (the owner,
;; 2026-11-12: measured over ten times the estimate stops the work), never past the cap; a test that states none runs under the
;; cap, and no history is read. Red before: the budget was max(4e9 floor, 10 x the median of its greens), whatever it stated
(deftest ^{:seon.test/estimate {:ticks 60000000}} a-tests-budget-is-ten-times-its-stated-estimate
  (record (ts/test-db) 'user "(ns app.pv-own-budget-test (:require [clojure.test :refer [deftest is]]))"
          'app.pv-own-budget-test "(deftest ^{:seon.test/estimate {:ticks 100000}} estimated-spins (loop [] (recur)))"
          'app.pv-own-budget-test "(deftest ^{:seon.test/estimate {:ticks 3000000}} within (loop [i 0] (when (< i 1000000) (recur (inc i)))) (is true))"
          'app.pv-own-budget-test "(deftest ^{:seon.test/estimate {:ticks 999999999999}} estimated-over-the-cap (loop [] (recur)))"
          'app.pv-own-budget-test "(deftest stated-none (is true))")
  (let [ts '[app.pv-own-budget-test/estimated-spins app.pv-own-budget-test/within app.pv-own-budget-test/estimated-over-the-cap app.pv-own-budget-test/stated-none]
        ;; the estimate's budget proved with it on (:seon.config.proofs/ticks-decide, off by the owner's ruling until a test's count is its own)
        ran (with-redefs [seon.config/value (let [v seon.config/value] (fn [k & more] (if (= k :seon.config.proofs/ticks-decide) true (apply v k more))))]
              (proofs/run-tests! ts {} (fn [_]) 50000000))]
    (is (= 1000000 (:budget (ran 'app.pv-own-budget-test/estimated-spins))) "ten times its estimate")
    (is (= 1 (:error (ran 'app.pv-own-budget-test/estimated-spins))) "past it: red, named")
    (is (< (:ticks (ran 'app.pv-own-budget-test/estimated-spins)) 5000000) "after about its budget's work, not the cap's")
    (is (proofs/green? (ran 'app.pv-own-budget-test/within)) "a test inside ten times its estimate is green")
    (is (= 30000000 (:budget (ran 'app.pv-own-budget-test/within))))
    (is (= 50000000 (:budget (ran 'app.pv-own-budget-test/estimated-over-the-cap))) "and never past the cap")
    (is (= 50000000 (:budget (ran 'app.pv-own-budget-test/stated-none))) "a test that states none runs under the cap")))

(deftest ^{:seon.test/estimate {:ticks 100000}} shards-are-dealt-by-measured-work
  ;; pure-tests 2026-10-23: dealt by count, the full run's shards ended 437, 397, 110 and 68 s
  ;; into it; by each test's last measured ms they end together
  (is (= '#{[x/a] [y/b y/c z/d]} (set (proofs/shards '[x/a y/b y/c z/d] 2 '{x/a 10 y/b 3 y/c 3 z/d 3})))
      "the longest alone, the rest together")
  (is (= '#{[p/a p/c q/e] [p/b p/d]} (set (proofs/shards '[p/a p/b p/c p/d q/e] 2 '{p/a 5 p/b 5 p/c 5 p/d 5 q/e 1})))
      "a namespace over half a shard's share is split into parts of at most that")
  (is (= 2 (count (proofs/shards '[x/a y/b y/c] 2 '{x/a 10}))) "a test never measured counts as the mean of those measured"))

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-test-s-weight-in-the-deal-is-its-median-ms-not-its-largest
  ;; 2026-10-29: a merge's 217 tests were dealt by the largest ms any verdict measured; one 17 s test
  ;; once measured 625 s in a hung run, so a shard ended at 62 s beside three at 640-746 s
  ;; (docs/merging.md#a-merge-runs-what-the-change-reaches)
  (let [dir (str (seon.config/checkout) "/target/tmp/typical-ms-" (random-uuid))
        rec (fn [t dg ms] (fx/write-verdicts! dir {:id "proofs-test"} {t {:test t :digest dg :pass 1 :fail 0 :error 0 :ms ms}} {}))]
    (try (doseq [[dg ms] [["d1" 17000] ["d2" 625000] ["d3" 16000] ["d4" 18000] ["d5" 15000]]] (rec 'app.pv-slow-test/hung-once dg ms))
         (rec 'app.pv-slow-test/once "d1" 4000)
         (is (= {'app.pv-slow-test/hung-once 17000 'app.pv-slow-test/once 4000}
                (fx/typical-ms dir '[app.pv-slow-test/hung-once app.pv-slow-test/once app.pv-slow-test/never-run]))
             "the median of its verdicts, whatever their digests; a test with none is left out")
         (finally (seon.lib/remove-tree! dir)))))

(defn plan-input
  "The input of size n: n keys with stored greens, one in a hundred moved since."
  [n]
  (let [cand (into {} (for [i (range n)] [(symbol "app.pv" (str "t" i)) (str "d" i)]))
        moved (set (take (max 1 (quot n 100)) (keys cand)))
        cand' (into cand (for [k moved] [k (str (cand k) "'")]))]
    [cand cand' (into {} (for [[_ dg] cand] [dg {:pass 1 :fail 0 :error 0}])) #{}]))

(deftest ^{:seon.test/estimate {:ticks 1000000}} selection-is-proportional-to-what-changed
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
     :fixed {:ns 20000 :bytes 40000}
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
  connection for the process (its db reads the store), so a shard running these tests would hold
  one env per scratch store for its life: a test's own store ends with the test
  (docs/failure-points.md#lmdb-locks)."
  [& paths] (run! (fn [p] (run! klmdb/close-env! (cons p (ts/generations p)))) paths))

(defn- calls-of
  "[{var calls} of the contracted functions thunk called, and thunk's answer]."
  [thunk] (profile/begin!) (let [r (thunk)] [(into {} (map (juxt :var :calls)) (profile/touched)) r]))

;; docs/testing.md#testing-in-the-world: the world (my.test/world) is built before any test, never inside the first one's budget.
;; seon.program-test/a-config-entry-moves-the-program-digest measured 91,663 ticks and 3,306,725 at one digest, both under bin/test:
;; the run in which it was the first to read the world built it (~3.2M) inside its 300k estimate's budget, red on main. Cost: the two
;; worlds' builds once a process, ~3.2M each, which every test that reads them no longer pays.
(deftest ^{:seon.test/estimate {:ticks 8000000}} the-world-is-built-before-any-test-reads-it
  (proofs/warm-fixtures!)
  (is (every? realized? [@#'my.test/plain-world @#'my.test/history-world])
      "both worlds are fixtures: an agent forces them before its first test fork, a shard before its first test"))

;; docs/merging.md#verdicts-are-a-cache: the kept program store is the repository's, read by every checkout's plans and shards at
;; once, so one past its bound is never moved or removed: the next tree imported starts the key's next generation
;; (seon.test-store/generations), and a run reads its tree from the generation that holds it. A store moved aside whole under its
;; readers sent every run whose plan had kept its tree there to import the program again inside a test's tick budget (a
;; 1,000,305,016-tick red, tests-4, 2026-11-14). Run A holds its tree's db in generation 0; tree B, past the bound, goes to
;; generation 1; A's db is read whole and A's branch found again by program-db, nothing imported.
;; Estimate: A's whole import ~20M (a-failed-import's); B's whole import into the empty generation 1 ~20M; A's program-db again,
;; two branch? seeks (a generation each) and an open each ~3M; A's read of its forms 1M; git children and tree-ids ~1M. Estimated 45,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 45000000} :seon.test/execs ["git"]} a-new-trees-store-never-replaces-a-store-a-run-reads
  (let [d (checkout! {"deps.edn" "{}" "src/app/mv_calc.clj" "(ns app.mv-calc)\n(defn add [a b] (+ a b))\n"})
        dir (scratch-dir)
        store (str dir "/ps-0123abcd")
        a (fx/tree-id d)
        names #(set (filter (fn [n] (= "app.mv-calc" (namespace n))) (d/q '[:find [?n ...] :where [?v :var/name ?n]] %)))
        db-a (runner/program-db d store a 1)]
    (try
      (spit (str d "/src/app/mv_calc.clj") "(ns app.mv-calc)\n(defn add [a b] (+ b a))\n(defn sub [a b] (- a b))\n")
      (clojure.java.shell/sh "git" "-C" d "add" ".")
      (let [b (fx/tree-id d)
            db-b (runner/program-db d store b 1)
            [calls db-a2] (calls-of #(runner/program-db d store a 1))]
        (is (= [(str store "-1") store] (ts/generations store)) "tree B, past the bound, started generation 1; generation 0 is where it was")
        (is (= #{'app.mv-calc/add} (names db-a)) "run A reads its tree to the end, its store not moved: its program, not B's")
        (is (= #{'app.mv-calc/add 'app.mv-calc/sub} (names db-b)) "and B's db is B's tree")
        (is (and (nil? (calls 'seon.source/import-tx)) (nil? (calls 'seon.source/refresh-tx))) "A's branch is found in generation 0: nothing imported")
        (is (= #{'app.mv-calc/add} (names db-a2)) "and it is A's program")
        (is (.isFile (java.io.File. store "data.mdb")) "generation 0 is where it was, with its data")
        (let [stage (java.io.File. (str store "-2.stage-left-behind"))]
          (.mkdirs stage)
          (is (= [(str store "-1") store] (ts/generations store)) "a stage left behind is no generation")
          (is (= (str store "-2") (ts/next-generation store)) "and does not move the next one")
          (ts/shared-program-store! (str dir "/ps") "0123abcd")
          (is (not (.exists stage)) "and a stage no process holds is collected")))
      (finally (close-stores! store)))))

(deftest ^{:seon.test/estimate {:ticks 40000000}} a-failed-import-leaves-no-branch
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
    ;; a store's own error (one a full run once passed as this throw) is not the file's, and a plan
    ;; that throws reads no store it half made
    (is (not (#{:lmdb-error :db-does-not-exist} (:type (ex-data thrown)))) "the file's, not the store's")
    (is (= #{:db} (branches)) "and leaves no branch named for its tree")
    (spit (str d "/src/app/fi_calc.clj") good)
    (clojure.java.shell/sh "git" "-C" d "add" ".")
    (let [p (runner/plan-of d "tests" false nil store)]
      (is (contains? (:digests p) 'app.fi-calc-test/adds) "the tree read: its test is planned")
      (is (= '[app.fi-empty-test] (:no-test p)) "a test namespace the program db holds no test of is named, never left as an empty OK"))
    (finally (close-stores! store)))))

(deftest ^{:seon.test/estimate {:ticks 25000000}} two-runs-share-a-new-trees-branch
  ;; docs/source.md: two prove processes on a tree neither had seen both
  ;; found its branch absent and both made it; Datahike refused the second ("Branch already exists",
  ;; 2026-10-29). The second run's step is run here after the first's, with the absence it had
  ;; checked: it ends with the same whole branch, and no staging branch is left
  (let [d (checkout! {"deps.edn" "{}" "src/app/tr_calc.clj" "(ns app.tr-calc)\n(defn add [a b] (+ a b))\n"
                      "test/app/tr_calc_test.clj" "(ns app.tr-calc-test (:require [clojure.test :refer [deftest is]] [app.tr-calc :as c]))\n(deftest adds (is (= 3 (c/add 1 2))))\n"})
        store (str (scratch-dir) "/store")
        tree (fx/tree-id d)
        keep! (fn [keep! runner/keep-tree! args] (apply runner/keep-tree! args))
        {:keys [conn cfg]} (pdb/open! :lmdb store 0 :db)
        b (runner/tree-branch tree)]
   (try
    (runner/keep-tree! conn cfg d tree b (d/branches conn))
    (let [first-facts (count (d/datoms @(pdb/connect (assoc cfg :branch b)) :eavt))]
      (runner/keep-tree! conn cfg d tree b #{:db})
      (is (= #{:db b} (set (d/branches conn))) "one branch for the tree, no staging branch left")
      (is (= first-facts (count (d/datoms @(pdb/connect (assoc cfg :branch b)) :eavt))) "the second import added nothing to it"))
    (is (contains? (:digests (runner/plan-of d "tests" false nil store)) 'app.tr-calc-test/adds) "a plan reads it")
    (finally (d/release conn) (close-stores! store)))))

(deftest ^{:seon.test/estimate {:ticks 50000000}} a-process-plans-on-more-scratch-stores-than-its-undo-entries
  ;; docs/failure-points.md#lmdb-crash-safety: a test's own store ends with the test (program-db keeps its
  ;; branch's connection, so close-stores! closes it), and a process plans on any number of stores in turn.
  ;; No system-wide limit stands behind the count any more (docs/failure-points.md#lmdb-locks: no semaphore),
  ;; so two stores in turn show the claim at the smallest n (eleven was the old semaphore's count: 220M ticks of the same
  ;; proof); each store made costs ~20 M ticks, its seed. Its mutation is still owed: task seon.proofs/the-scratch-stores-proof-has-a-mutation
  (let [d (checkout! {"deps.edn" "{}" "src/app/ps_calc.clj" "(ns app.ps-calc)\n(defn add [a b] (+ a b))\n"
                      "test/app/ps_calc_test.clj" "(ns app.ps-calc-test (:require [clojure.test :refer [deftest is]]))\n(deftest adds (is true))\n"})
        planned (vec (for [_ (range 2)
                           :let [store (str (scratch-dir) "/store")]]
                       (try (contains? (:digests (runner/plan-of d "tests" false nil store)) 'app.ps-calc-test/adds)
                            (finally (close-stores! store)))))]
    (is (= (repeat 2 true) planned) "two stores in turn, each planned")))

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
        ;; each plan is the one whose calls are counted: a plan is a tree id's git children and the store's open
        [first-calls p1] (calls-of plan)]
   (try
    (is (= 1 (first-calls 'seon.source/import-tx)) "an empty store: the tree is imported once")
    (let [[again p] (calls-of plan)]
      (is (nil? (again 'seon.source/import-tx)) "the same tree: its kept branch, no import")
      (is (nil? (again 'seon.source/refresh-tx)) "and nothing to bring current")
      (is (= (:digests p1) (:digests p)) "and the same digests"))
    (spit (str d "/src/app/kc_calc.clj") "(ns app.kc-calc)\n(defn add [a b] (+ b a))\n")
    (let [[edited p2] (calls-of plan)
          whole (runner/plan-of d "tests" false nil fresh)]
      (is (nil? (edited 'seon.source/import-tx)) "an edited tree: no import")
      (is (= 1 (edited 'seon.source/refresh-tx)) "the nearest kept tree brought current, once")
      (is (= (:digests whole) (:digests p2)) "the same digests a whole import of the edited tree gives")
      (is (not= (get-in p1 [:digests 'app.kc-calc-test/adds]) (get-in p2 [:digests 'app.kc-calc-test/adds])) "the test reaching the edit moved")
      (is (= (get-in p1 [:digests 'app.kc-other-test/other]) (get-in p2 [:digests 'app.kc-other-test/other])) "the other did not"))
    (spit (str d "/src/app/kc_calc.clj") calc)
    (let [[back p] (calls-of plan)]
      (is (nil? (back 'seon.source/refresh-tx)) "the first tree again: its kept branch")
      (is (= (:digests p1) (:digests p))))
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

(defn kept-store-with-branches
  "program-db's arguments for a small checkout whose tree a store of its own keeps (the first call, not measured,
  imports it), the store holding n branches in all: the tree's, :db and the rest empty branches of :db, made here, so
  the count is the test's and no earlier size's."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :string :string]]}
  [n]
  (let [d (checkout! {"deps.edn" "{}"
                      "src/app/kp_big.clj" "(ns app.kp-big)\n(defn f0 [x] (+ x 0))\n"
                      "test/app/kp_big_test.clj" "(ns app.kp-big-test (:require [clojure.test :refer [deftest is]] [app.kp-big :as b]))\n(deftest f0 (is (= 1 (b/f0 1))))\n"})
        store (str (ts/program-store) "-branches-" n "-" (random-uuid))]
    (runner/program-db d store)
    (let [{:keys [conn]} (pdb/open! :lmdb store 0 :db)
          have (count (d/branches conn))]
      (try (doseq [i (range (max 0 (- n have)))] (d/branch! conn :db (keyword (str "kp-extra-" i))))
           (finally (d/release conn))))
    [d store]))

(deftest ^{:seon.test/estimate {:ticks 80000000}} a-kept-tree-costs-its-lookup-not-its-program
  ;; task seon.proofs/a-plan-keeps-a-program-db-per-tree: an unchanged tree's program db is its kept branch, never its
  ;; program; a one-file change costs that file (seon.source/refresh-tx's proofs in seon.source-test). What grows is the
  ;; branches the store holds: Deep fixes measured 35.4k ticks, 102 KB and 3 konserve reads a branch (2026-11-10, notes
  ;; open-is-o-branches-2026-11-11.md); program-db's own read of them (d/branches) is gone (seon.db/branch?), and what
  ;; is left is seon.db/ids! reseeding the id allocator from every head at an open, which Deep fixes and Optimization
  ;; are taking out: then this is :constant at about 0.6 M ticks, and tightened
  (cost/proof
    {:of 'test-runner/program-db :input 'seon.proofs-test/kept-store-with-branches
     :n "branches the program store holds"
     :work (str "the tree's id (git, a child process: no ticks here), the store opened (seon.db/ids! reads each branch's head),"
                " the tree's branch asked for and connected; nothing of the program is read or imported")
     :growth :linear
     ;; measured 2026-11-10 at 1 10 30 100 300 branches: 1.22M 1.41M 1.89M 3.57M 8.36M ticks, 4.76 5.32 6.72 11.6 25.7 MB:
     ;; 24.0k ticks and 70.2 KB a branch (Deep fixes' 35.4k and 102 KB before program-db stopped listing them) on 1.19M and 4.7 MB
     :fixed {:ticks 1200000.0 :bytes 4700000.0}
     :per-n {:ticks 24000.0 :bytes 70000.0}
     :sizes [3 10 30]}))

;; ---- every verdict is kept: a digest green and red is flaky (no verdict, a :platform problem, never run again)

(deftest ^{:seon.test/estimate {:ticks 500000}} a-digest-green-and-red-is-flaky-no-verdict-and-never-runs
  ;; the merge-cost proof was red once and green on its next run at one digest: with one verdict
  ;; kept per digest the red was gone, and the green reused
  (with-dir
   (fn [dir]
     (let [green {:test 'app.f-test/t :digest "d-flaky" :pass 1 :fail 0 :error 0 :ticks 1000}
           red {:test 'app.f-test/t :digest "d-flaky" :pass 0 :fail 1 :error 0 :ticks 30000}
           fails {'app.f-test/t [{:type :fail :message "1 is not 2"}]}
           cand {'app.f-test/t "d-flaky" 'app.f-test/g "d-green" 'app.f-test/r "d-red" 'app.f-test/n "d-none"}]
       (store! dir {'app.f-test/t green})
       (store! dir {'app.f-test/t red} fails)
       (store! dir {'app.f-test/g (assoc green :test 'app.f-test/g :digest "d-green")})
       (store! dir {'app.f-test/r (assoc red :test 'app.f-test/r :digest "d-red")} {'app.f-test/r [{:type :fail :message "1 is not 2"}]})
       (let [found (stored dir cand)
             {:keys [run reused selected]} (proofs/plan nil cand found #{})
             f (found "d-flaky")]
         (is (:flaky f) "a digest that holds a green and a red is flaky")
         (is (zero? (+ (:fail f) (:error f))) "no verdict: never a red that holds a landing or shows main red (owner, 2026-11-14)")
         (is (= {:green 1000 :red 30000} (:seon.proofs/ticks f)) "both runs' ticks, the evidence seon.prove/flaky-problems files")
         (is (= ["1 is not 2"] (mapv :message (:seon.proofs/red-failures f))) "and the red run's own failures")
         (is (not (proofs/green? (found "d-red"))) "a red alone is reused red, with its failures")
         (is (= ['app.f-test/n] run) "only the digest with no result runs: the flaky and the red are reused, never run again")
         (is (= #{'app.f-test/t 'app.f-test/r 'app.f-test/n} (set selected)) "the flaky one and the red are selected: they judge")
         (is (= #{'app.f-test/t 'app.f-test/g 'app.f-test/r} (set (keys reused))))
         (is (= {:recorded 0} (store! dir {'app.f-test/t f}))
             "a flaky result, judged and not run, writes nothing")
         (is (= {:recorded 0} (store! dir {'app.f-test/u (assoc red :digest "d-unreported" :unreported true)}))
             "a test that never reported (its namespace's run threw first) has no verdict to keep"))))))

(deftest ^{:seon.test/estimate {:ticks 100000}} a-test-whose-child-died-has-no-verdict
  ;; root, 2026-10-27 (THE PLAN 0.7, the blind agent's run): a test the run's child died in
  ;; (seon.agent/test-run-answer's report with its :exit) was an :error result, so the judge held
  ;; a task whose change never reached it as a stray red. A death is no verdict: the test has no
  ;; result (a proof the child died in is unknown, an unrelated test is nothing to the judge),
  ;; as its digest keeps none (seon.fx/write-verdicts!)
  (let [run {:reports [{:type :pass :var "#'a.b-test/ok"}
                       {:type :fail :var "#'a.b-test/red"}
                       {:type :pass :var "#'a.b-test/died"}
                       {:type :error :var "#'a.b-test/died" :exit {:exit 1} :message "test process died: {:exit 1} while #'a.b-test/died ran"}]}]
    (is (= '{a.b-test/ok :pass a.b-test/red :fail} (proofs/results run)))))


(deftest ^{:seon.test/estimate {:ticks 100000}} a-gates-verdict-is-a-fact-at-the-digest-it-deferred
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: a process test the gate judged holds that verdict at its digest; a later run that defers it
  ;; there leaves it, one whose digest moved defers it again; one the run never reached is no pass
  (let [t 'app.f-test/t u 'app.f-test/u
        old {t {:seon.test/digest "d1" :seon.test/status :deferred} u {:seon.test/digest "d2" :seon.test/status :deferred}}
        tx (proofs/gate-tx old {:results {t :pass u :fail} :failures {u [{:type :fail :message "no"}]} :log "/l"})
        by (into {} (map (juxt :var/name identity)) tx)]
    (is (= :green (:seon.test/status (by t))))
    (is (= "d1" (:seon.test/digest (by t))))
    (is (= [{:type :fail :message "no"}] (:seon.test/failures (by u))))
    (is (= :red (:seon.test/status (by u))))
    (let [[f] (proofs/gate-tx old {:results {t :error} :failures {} :log "/l"})]
      (is (= :red (:seon.test/status f)))
      (is (str/includes? (:message (first (:seon.test/failures f))) "/l") "never reached says where to read why"))
    (is (= [] (proofs/gate-tx {} {:results {t :pass}})) "a test with no digest either side has no fact to hold")
    (is (= "g" (:seon.test/digest (first (proofs/gate-tx {} {:results {t :pass} :digests {t "g"}})))) "no row on the branch: judged at the gate's")
    (testing "a run that defers it again"
      (let [held {t {:seon.test/digest "d1" :seon.test/status :green}}]
        (is (= [] (proofs/result-tx held {} {} {} {t "d1"} #{t})) "at the same digest its verdict stands")
        (is (= :deferred (:seon.test/status (first (proofs/result-tx held {} {} {} {t "d2"} #{t})))) "a moved digest is unjudged")))))

(deftest ^{:seon.test/estimate {:ticks 100000}} a-run-records-a-few-facts-and-a-run-that-changes-nothing-records-none
  ;; task seon.proofs/a-verdict-is-an-entity-that-names-its-environment (R2a): seon.proofs/result-tx, what an agent's run
  ;; writes on its branch beside the cache: the digest, status, failing assertions (as data) and running
  ;; sums of each test that ran, and the digest and status of one reused; only what differs; and a test whose
  ;; reach digest moved, not judged by the run, loses its facts (a status is the verdict at the digest it has now)
  (let [red {:test 'app.f-test/t :digest "d1" :pass 0 :fail 1 :error 0 :ms 5 :ticks 100 :bytes 1000}
        green (assoc red :digest "d2" :pass 1 :fail 0)
        failure {'app.f-test/t [{:type :fail :message "no" :expected "1" :actual "2"} {:type :fail :message "nor" :expected "3" :actual "4"}]}
        at {'app.f-test/t "d1"}
        tx1 (proofs/result-tx {} {'app.f-test/t red} failure {} at)
        held #(into {} (remove (comp #{:var/name} key)) (first %))]
    (is (= :red (:seon.test/status (first tx1))))
    (is (= "d1" (:seon.test/digest (first tx1))))
    (is (= (failure 'app.f-test/t) (:seon.test/failures (first tx1))) "every failing assertion kept, as data, as the child reported it")
    (is (= {:seon.test/ticks 100 :seon.test/bytes 1000} (select-keys (first tx1) [:seon.test/ticks :seon.test/bytes])))
    (is (= [] (proofs/result-tx {'app.f-test/t (held tx1)} {} {} {'app.f-test/t {:digest "d1" :pass 0 :fail 1 :error 0 :failures (failure 'app.f-test/t)}} at))
        "reused at the digest and status the branch holds: nothing to write")
    (let [tx2 (proofs/result-tx {'app.f-test/t (assoc (held tx1) :seon.test/ticks 300)} {'app.f-test/t green} {} {} {'app.f-test/t "d2"})]
      (is (= :green (:seon.test/status (first (remove vector? tx2)))) "fixed and run again: green")
      (is (= [[:db/retract [:var/name 'app.f-test/t] :seon.test/failures (:seon.test/failures (first tx1))]] (filter vector? tx2)) "the failures are retracted on green")
      (is (= (:ticks green) (:seon.test/ticks (first (remove vector? tx2)))) "a new digest starts the sums over: this run's alone"))
    (let [at-d2 (assoc (held tx1) :seon.test/digest "d2" :seon.test/status :green :seon.test/ticks 100)
          tx3 (proofs/result-tx {'app.f-test/t (dissoc at-d2 :seon.test/failures)} {'app.f-test/t green} {} {} {'app.f-test/t "d2"})]
      (is (= (+ 100 (:ticks green)) (:seon.test/ticks (first tx3))) "the same digest adds"))
    (let [tx4 (proofs/result-tx {'app.f-test/t (held tx1)} {} {} {'app.f-test/t {:digest "d9" :pass 1 :fail 0 :error 0}} {'app.f-test/t "d9"})]
      (is (= :green (:seon.test/status (first (remove vector? tx4)))) "a reused green at a new digest")
      (is (= 4 (count tx4)) "its digest and status written, its two sums and its failures retracted (the old digest's)"))
    (let [held1 (held tx1)]
      (is (= (set (for [[k v] held1] [:db/retract [:var/name 'app.f-test/t] k v]))
             (set (proofs/result-tx {'app.f-test/t held1} {} {} {} {'app.f-test/t "d5"})))
          "not judged by this run and its digest moved: every fact retracted, its status unknown")
      (is (= [] (proofs/result-tx {'app.f-test/t held1} {} {} {} at)) "not judged, its digest unchanged: its verdict stands"))))

;; docs/sharing.md#what-a-branchs-nodes-are: a branch keeps a test's facts only where something reads them
(deftest ^{:seon.test/estimate {:ticks 100000}} a-test-worth-a-fact-is-a-proof-a-red-or-one-held
  (let [green {:digest "d" :pass 1 :fail 0 :error 0} red {:digest "d" :pass 0 :fail 1 :error 0}]
    (is (= '{a/proof {:digest "d" :pass 1 :fail 0 :error 0}}
           (proofs/facts-worth-keeping {} '#{a/proof} '{a/proof {:digest "d" :pass 1 :fail 0 :error 0} a/other {:digest "d" :pass 1 :fail 0 :error 0}}))
        "a green proof is kept, a green that is no proof is not")
    (is (= #{'a/red} (set (keys (proofs/facts-worth-keeping {} #{} {'a/red red 'a/other green})))) "a red is kept")
    (is (= #{'a/turned} (set (keys (proofs/facts-worth-keeping {'a/turned {:seon.test/status :red}} #{} {'a/turned green 'a/other green}))))
        "one the branch holds a fact of is kept, so a red gone green never reads as red")
    ;; with no green's fact held, a run going red at one digest writes :red, not :flaky: it fails the done rule alike, and the cache's two files say flaky
    (is (= :red (:seon.test/status (first (proofs/result-tx {} {'a/t (assoc red :ms 1 :ticks 1 :bytes 1)} {} {} {'a/t "d"})))))
    (is (:flaky (proofs/verdict "d" green (assoc red :failures []))) "green and red at one digest are flaky in the cache")))

(deftest ^{:seon.test/estimate {:ticks 300000}} a-run-has-the-shards-its-work-is-worth
  (let [ts '[a/t a/u a/v a/w]]
    (is (= 1 (proofs/worth-shards ts 4 '{a/t 1000 a/u 1000 a/v 1000 a/w 1000} 30000)) "seconds of work: one")
    (is (= 2 (proofs/worth-shards ts 4 '{a/t 15000 a/u 15000 a/v 15000 a/w 15000} 30000)) "one for each min-ms")
    (is (= 4 (proofs/worth-shards ts 4 '{a/t 200000 a/u 200000 a/v 200000 a/w 200000} 30000)) "never past jobs")
    (is (= 3 (proofs/worth-shards ts 4 '{a/t 25000 a/u 25000} 30000)) "a test never measured counts as the mean")
    ;; a run's tests are dealt by seon.proofs/estimated-ms, which gives every unmeasured test its class's configured estimate:
    ;; 234 process tests never measured (a gate's fresh checkout) were one shard and 763 s when an unknown counted as 1 ms
    (let [gate (mapv #(symbol "a" (str "p" %)) (range 234))
          est (proofs/estimated-ms gate {} (set gate))]
      (is (= (seon.config/value :seon.config.proofs/unmeasured-process-ms) (est (first gate))) "an unmeasured process test is its estimate")
      (is (= 4 (proofs/worth-shards gate 4 est 30000)) "so a run of them none measured is worth every shard, not one"))
    (is (= (seon.config/value :seon.config.proofs/unmeasured-ms) ((proofs/estimated-ms '[a/t] {} #{}) 'a/t)) "an in-process test is its own class's")
    (is (= 900 ((proofs/estimated-ms '[a/t] '{a/t 900} '#{a/t}) 'a/t)) "and a measured test is its measurement")))

;; docs/merging.md#a-merge-runs-what-the-change-reaches: a run takes as many shards as free memory holds, at most the configured
;; jobs, never fewer for the load average (bin/test: load counts other sessions' niced work)
(deftest ^{:seon.test/estimate {:ticks 50000}} a-run-takes-the-shards-free-memory-holds
  (is (= 4 (proofs/memory-jobs 4 40000 2000 8000)) "memory for many: the configured ceiling")
  (is (= 2 (proofs/memory-jobs 4 12500 2000 8000)) "memory for two above the floor: two")
  (is (= 1 (proofs/memory-jobs 4 7000 2000 8000)) "below the floor: one, never none"))

;; docs/merging.md#a-slow-test-runs-last: asked only of what would run, out of a plain and a merge's run, in a named or full one
(deftest ^{:seon.test/estimate {:ticks 100000}} a-slow-test-runs-last-unless-named-or-the-full-run
  (let [asked (atom nil)
        reach {:digests '{a/slow "d1" a/quick "d2" a/green "d3"} :process #{}
               :slow-of (fn [ds] (reset! asked ds) (select-keys '{a/slow 9000 a/green 9000} (keys ds)))}
        plan (fn [all? names] (proofs/plan-of reach "tests" all? names nil (fn [_] {"d3" {:pass 1 :fail 0 :error 0}})))
        auto (plan false nil)]
    (is (= '{a/slow "d1" a/quick "d2"} @asked) "only the tests that would run are asked about: a green at its digest costs nothing")
    (is (= '[a/quick] (:run-tests auto)) "a plain run leaves it out")
    (is (= '{a/slow 9000} (:slow auto)) "and says so, with what it measured")
    (is (= '[a/quick] (:run-tests (plan :merge nil))) "a merge's run leaves it out too")
    (is (some #{'a/slow} (:run-tests (plan false ["a/slow"]))) "named, it runs")
    (is (some #{'a/slow} (:run-tests (plan true nil))) "and the full run runs it")))

(deftest ^{:seon.test/estimate {:ticks 1000000}} the-program-a-test-judges-is-its-processs-branch
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: a test that walks the program reads test-runner/program. In a process
  ;; that holds a branch (an agent's test child) that is the branch's db itself: nothing is imported, and the test judges
  ;; the agent's change. Red before: such a test composed program-db of the checkout's tree, so in an agent's prove, where
  ;; no plan had kept the tree, it imported the whole program inside its own budget (3.87 G ticks of 4.0 G, the stray reds
  ;; of 2026-11-10) and judged root's checkout, never the branch
  (let [w (my.test/world) before @seon.agent/state]
    (try (reset! seon.agent/state {:db w})
         (is (identical? w (test-runner/program)) "the branch's db, as held: no import, and the branch's own program")
         (finally (reset! seon.agent/state before)))))

(deftest ^{:seon.test/estimate {:ticks 50000}} a-red-at-bases-digest-or-red-at-base-is-mains
  ;; docs/tasks.md#the-done-rule: of the reds of a run, those the branch's change did not make
  (let [results {'t/same :error 't/based :fail 't/mine :error 't/new :error 't/ok :pass}
        cand {'t/same "d1" 't/based "d2x" 't/mine "d3x" 't/new "d4" 't/ok "d5x"}
        base {'t/same "d1" 't/based "d2" 't/mine "d3" 't/ok "d5"}
        found {"d2" {:fail 1} "d3" {:pass 1}}]
    (is (= #{'t/same 't/based} (proofs/main-red results cand base found))
        "its digest is base's, or base's verdict at it is red; green at base, new on the branch, or passing: the branch's")
    (is (= #{} (proofs/main-red results cand nil {})) "with no base nothing is main's")))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-run-runs-a-sample-of-its-reused-greens-again
  ;; the owner's plan, 2026-11-10: a writer change reused 226 of 233 process tests' greens and five were red. Whatever the
  ;; next missed input is, a run runs n reused greens of each class again, chosen by the tree (seon.proofs/reuse-audit)
  (let [green {:pass 1 :fail 0 :error 0}
        reused (merge (into {} (for [i (range 30)] [(symbol "app.ra-test" (str "p" i)) green]))
                      (into {} (for [i (range 30)] [(symbol "app.ra-test" (str "q" i)) green]))
                      {'app.ra-test/red {:pass 0 :fail 1 :error 0} 'app.ra-test/flaky (assoc green :flaky true) "a-step" green})
        process (set (for [i (range 30)] (symbol "app.ra-test" (str "p" i))))
        a (proofs/reuse-audit reused process "tree-a" 10)]
    (is (= 20 (count a)) "n of each class")
    (is (= 10 (count (filter process a))) "ten that start a process, ten that do not")
    (is (not-any? #{'app.ra-test/red 'app.ra-test/flaky "a-step"} a) "never a red, a flaky one or a check step")
    (is (= a (proofs/reuse-audit reused process "tree-a" 10)) "the same tree, the same ones")
    (is (not= a (proofs/reuse-audit reused process "tree-b" 10)) "another tree, others")
    (is (= [] (proofs/reuse-audit reused process "tree-a" 0)) "none at 0")))

;; docs/source.md#tests-run-by-reach: a test is keyed by what its run read (recorded reads, ninja's depfiles): its verdict
;; holds each read's digest, and it is reused while every one still has it, whatever else moved
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-verdict-is-reused-while-everything-its-run-read-is-unchanged
  (let [rd {'a/f "f1" 'a/g "g1" 'a/h "h1" [:schema :a/e] "e1" :seon.proofs/covers #{:var :schema :config}}
        raw {:fns #{'a/f 'a/g} :entities #{:a/e :a/missing}}
        k (seon.proofs/reads-key rd raw)
        held {'a.b-test/t [{:digest "old" :pass 1 :fail 0 :error 0 :reads k}]}
        static {'a.b-test/t "static" 'a.b-test/u "su"}]
    (is (= {'a/f "f1" 'a/g "g1" [:schema :a/e] "e1" [:schema :a/missing] nil} k) "each read with its digest; a read of nothing is a miss")
    (is (= {'a.b-test/t "old" 'a.b-test/u "su"} (seon.proofs/read-digests static rd held))
        "nothing it read moved: looked up at its verdict's digest (a test with none at its static one)")
    (is (= "old" (get (seon.proofs/read-digests static (assoc rd 'a/h "h2") held) 'a.b-test/t)) "a var it never called moved: still reused")
    (is (= "static" (get (seon.proofs/read-digests static (assoc rd 'a/g "g2") held) 'a.b-test/t)) "a var it called moved: it runs")
    (is (= "static" (get (seon.proofs/read-digests static (assoc rd [:schema :a/missing] "m1") held) 'a.b-test/t)) "a schema it missed now exists: it runs")
    (is (= "static" (get (seon.proofs/read-digests static rd {'a.b-test/t [{:digest "old" :pass 1 :fail 0 :error 0}]}) 'a.b-test/t))
        "a verdict with no record of its reads is never reused by them")
    (is (= "static" (get (seon.proofs/read-digests static rd {'a.b-test/t [{:digest "old" :reads {'a/f "f1" [:file "x.md"] nil}}]}) 'a.b-test/t))
        "a read of a kind the index does not hold whole (files, in an agent's plan) is never taken for a miss")))

(defn- throws-here [] (throw (ex-info "thrown here" {})))

(deftest ^{:seon.test/estimate {:ticks 3000000}} an-errors-verdict-names-where-it-was-thrown
  ;; docs/machine.md#reading-a-red. Found 2026-11-12 (tests): a test's verdict kept "posix_spawn failed (errno 2)" and no
  ;; place, since an #error prints its type and message only; finding the spawn took a trace of every call. Cost: one throw
  ;; and its print (~3M ticks).
  (let [e (try (throws-here) nil (catch Throwable e e))
        rep (seon.agent/test-report {:type :error :message "Uncaught exception, not in assertion." :expected nil :actual e} "#'demo-test/t")
        [f] (get (proofs/failures {:reports [rep]}) 'demo-test/t)]
    (is (some #(str/includes? % "throws_here") (:at f)) (str "the stored failure names the function that threw: " (pr-str (:at f))))
    (is (<= 1 (count (:at f)) (seon.config/value :seon.config.proofs/error-frames)) "its first frames, at most the config's")))

(deftest ^{:seon.test/estimate {:ticks 200000}} every-check-step-has-a-bound-a-never-measured-one-too
  ;; docs/clocks.md#time-decides-the-owners-exceptions: runtime's landing hung 58 min in the orphan and resume steps, which had no
  ;; measured run and so no bound; every wait ends (AGENTS §4). A step never measured takes the largest measured median
  (let [k (seon.config/value :seon.config.check/step-end-factor) floor (seon.config/value :seon.config.agent/stall-ms)
        b (proofs/step-bounds ["config" "orphan" "resume"] {"config" 4000 "e2e-loop" 90000})]
    (is (= #{"config" "orphan" "resume"} (set (keys b))) "every step has one")
    (is (= (max floor (* k 4000)) (b "config")) "a measured step: the factor times its median, floored")
    (is (= (max floor (* k 90000)) (b "orphan") (b "resume")) "a never-measured step: the largest measured median's")))

;; docs/source.md#tests-run-by-reach: a test that drives an agent or a root records only what its own process called, so its
;; green is reused at its digest only, never by recorded reads (context's finding, 2026-11-12: a change to seon.repl/edit!, which
;; only an agent's eval called, left seon.repl-test/a-namespace-edit-names-the-var-that-holds-the-text at its recorded reads).
;; T1, in process: one index of three tests.
(deftest ^{:seon.test/estimate {:ticks 500000}} a-process-tests-green-is-never-reused-by-what-it-recorded
  (is (= '[a/pure b/also-pure] (proofs/reads-reusable {'a/pure {:process? false} 'c/drives-an-agent {:process? true} 'b/also-pure {}}))
      "every test but the one that reaches a process of its own"))

;; docs/merging.md#verdicts-are-a-cache: a granted test the agent's child can't run (a process test, seon.agent/granted-selection)
;; is answered as an :error naming why, :not-run: no verdict of its own, so it is never written to the cache. Found by runtime's
;; review, 2026-11-13: answer-ran left out only :reused and died reports, so that error was cached as a red at the test's real
;; digest, which a later run would reuse and never run the test. Estimate: one fold of three reports, ~50k ticks.
(deftest ^{:seon.test/estimate {:ticks 100000}} a-wall-time-end-is-no-verdict-and-never-cached
  ;; docs/principles.md#6-nothing-changed-nothing-runs-a-change-costs-what-it-touched: a test ended at its wall bound
  ;; (:seon.test/wall-spent, seon.agent/arm-wall!) read the machine's load, not its own work: 21 root-starting tests ended so in the
  ;; service at load, green alone, and their cached reds made them flaky at their digests (root.log, 2026-11-14). Its end stops the
  ;; hang; it is never a verdict. Estimate: two reports read, ~0.1M
  (let [answer {:reports [{:type :pass :var "#'a.x-test/ran"}
                          {:type :error :var "#'a.p-test/waits" :seon.error/type :seon.test/wall-spent
                           :message "Uncaught exception, not in assertion." :actual "the test ran past its wall bound of 36700 ms"}]
                :digests {'a.x-test/ran "d1" 'a.p-test/waits "d2"}}
        {:keys [ran]} (proofs/answer-ran answer)]
    (is (= #{'a.x-test/ran} (set (keys ran))) "only the test that ended by its own work is a verdict to cache")))

(deftest ^{:seon.test/estimate {:ticks 100000}} a-test-not-run-is-no-verdict-and-never-cached
  (let [answer {:reports [{:type :pass :var "#'a.x-test/ran"}
                          {:type :error :var "#'a.p-test/boots" :not-run true :message "a.p-test/boots reaches a process of its own"}]
                :digests {'a.x-test/ran "d1" 'a.p-test/boots "d2"}}
        {:keys [ran]} (proofs/answer-ran answer)]
    (is (= #{'a.x-test/ran} (set (keys ran))) "only the test that ran is a verdict to cache")))

;; Estimate: three predicate calls = ~10000.
(deftest ^{:seon.test/estimate {:ticks 10000}} a-waived-pass-keeps-its-rows
  ;; docs/cost.md#an-accepted-overrun-and-its-ratchet (web-2's ask): a cost proof that passes by its waiver keeps its rows with its
  ;; green, so the waiver is read without being taken off; a plain pass keeps nothing, a failure is kept as always
  (is (runner/kept-report? {:type :pass :actual {:rows [] :verdict {:ok? true :accepted {:task "t/x"}}}}) "a waived pass")
  (is (not (runner/kept-report? {:type :pass :actual {:rows [] :verdict {:ok? true}}})) "a plain pass")
  (is (runner/kept-report? {:type :fail :message "over"}) "a failure"))

;; docs/source.md#tests-run-by-reach: a test that reads the checkout raw keys its green by the files it read, not the whole tree
;; Estimate: 5 run-reads calls, each a few string compares and one stat, under 1M ticks, plus one slurp of a page
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-green-that-read-the-checkout-raw-keys-the-files-it-read
  (let [root (seon.config/checkout) ks [[:env] [:tree] [:config :seon.env/schemas]]]
    (profile/reads-begin!)
    (slurp (str root "/docs/prompt.md"))
    (is (= {:fns #{'a/t} :files #{"docs/prompt.md"} :keys [[:env] [:config :seon.env/schemas]]}
           (proofs/run-reads {:fns [] :files (profile/opened)} 'a/t ks))
        "the page it read, through Jolt's own record, in place of [:tree]")
    (is (= (conj ks [:root]) (:keys (proofs/run-reads {:files #{(str root "/docs/prompt.md")}} 'a/t (conj ks [:root]))))
        "a test reaching a process keeps the tree: what the process reads is out of sight")
    (is (= ks (:keys (proofs/run-reads {:files #{(str root "/docs")}} 'a/t ks))) "a directory listed keeps the tree")
    (is (= ks (:keys (proofs/run-reads {} 'a/t ks))) "a run that noted nothing keeps the tree")
    (is (= #{} (:files (proofs/run-reads {:files #{"/tmp/x.edn"}} 'a/t ks))) "a file outside the checkout is no read of it")))

;; docs/merging.md#verdicts-are-a-cache: a verdict is a :seon.proof entity in the store, found by one seek of its identity, and what
;; its run read is datom keys, so what moves it is found by seon.db/results-moved.
;; Estimate: a world with 2 forms (~2M), one graph (~2M), one verdict-tx and db-with (~0.5M), seeks and digests (<0.2M): 5,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-verdict-is-written-to-the-store-and-found-by-one-seek
  (let [d1 (seon.lib/digest "run one") d2 (seon.lib/digest "run two")
        w0 (mt/with (mt/world) (mt/code 'pt.vs "(ns pt.vs)\n(defn add [x y] (+ x y))\n"))
        g (program/reach-graph w0 nil)
        t 'pt.vs-test/adds
        basis {:seon.db/branch :db :seon.db/commit (parse-uuid "00000000-0000-0000-0000-000000000001") :seon.db/t 1}
        tx (proofs/verdict-tx w0 g basis {t {:digest d1 :pass 1 :fail 0 :error 0}} {t [{:var 'pt.vs/add}]} {})
        db (pdb/db-with w0 tx)
        add-form (:e (first (d/datoms w0 :avet :form/id (first (get-in g [:defs 'pt.vs/add])))))]
    (is (= {d1 {:digest d1 :pass 1 :fail 0 :error 0}} (proofs/verdicts-at db {t d1})) "found at its reach digest by one seek")
    (is (= {} (proofs/verdicts-at db {t d2})) "and not at another digest")
    (is (contains? (set (:seon.proof/read-keys (d/entity db (:e (first (d/datoms db :avet :seon.proof/test t)))))) (pr-str [:ea add-form :form/code]))
        "what it read holds the called function's form")
    (let [redefined (d/with db ((mt/code 'pt.vs "(ns pt.vs)\n(defn add [x y] (+ y x))\n") db))
          moved (pdb/results-moved db (:tx-data redefined))
          ks (set (:seon.proof/read-keys (d/entity db (:e (first (d/datoms db :avet :seon.proof/test t))))))]
      (is (= #{(:db/id (d/entity db (:e (first (d/datoms db :avet :seon.proof/test t)))))} moved)
          "redefining the function it called moves its verdict, found by what it read")
      (is (not= (pdb/reads-digest db ks nil) (pdb/reads-digest (:db-after redefined) ks nil))
          "and what it read no longer holds what it did"))))

;; docs/merging.md#verdicts-are-a-cache (root, 2026-11-15; data's six reds): a verdict another runner made (:seeded, carried once from
;; bin/test's file cache) answers nothing for the image: a test whose verdict depends on which process runs it was green under bin/test
;; and red in the image at one digest, and the judge took the green. verdicts-at finds only :live and :full verdicts. Estimate: a world
;; (~2M), one graph (~2M), two verdict-txs and db-withs (~1M), four seeks: 5,000,000
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-verdict-another-runner-made-answers-nothing-for-the-image
  (let [d1 (seon.lib/digest "image run") d2 (seon.lib/digest "bin/test run")
        w0 (mt/with (mt/world) (mt/code 'pt.vr "(ns pt.vr)\n(defn add [x y] (+ x y))\n"))
        g (program/reach-graph w0 nil)
        t 'pt.vr-test/adds
        basis {:seon.db/branch :db :seon.db/commit (parse-uuid "00000000-0000-0000-0000-000000000001") :seon.db/t 1}
        live (proofs/verdict-tx w0 g basis {t {:digest d1 :pass 0 :fail 1 :error 0}} {t [{:var 'pt.vr/add}]} {})
        seeded (mapv #(assoc % :seon.proof/route :seeded) (proofs/verdict-tx w0 g basis {t {:digest d2 :pass 1 :fail 0 :error 0}} {t [{:var 'pt.vr/add}]} {}))
        db (pdb/db-with w0 (into live seeded))]
    (is (= :fail (cond (pos? (:fail (get (proofs/verdicts-at db {t d1}) d1) 0)) :fail :else :none)) "the image's own red is found")
    (is (= {} (proofs/verdicts-at db {t d2})) "a green another runner made (:seeded) answers nothing for the image")))

;; docs/storage.md#what-a-read-scanned: a plan finds a verdict at its digest, and a green at another digest while what its run read
;; still holds what it did (reuse by the one read record); a change to what it read ends the reuse.
;; Estimate: a world with 2 forms (~2M), one graph (~2M), a verdict written (~0.5M), three lookups with digests (<0.5M): 5,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-plan-finds-a-verdict-and-reuses-a-green-while-its-reads-hold
  (let [d1 (seon.lib/digest "run one") d2 (seon.lib/digest "run two")
        w0 (mt/with (mt/world) (mt/code 'pt.vr "(ns pt.vr)\n(defn add [x y] (+ x y))\n"))
        g (program/reach-graph w0 nil)
        t 'pt.vr-test/adds
        basis {:seon.db/branch :db :seon.db/commit (parse-uuid "00000000-0000-0000-0000-000000000001") :seon.db/t 1}
        rdb (pdb/db-with w0 (proofs/verdict-tx w0 g basis {t {:digest d1 :pass 1 :fail 0 :error 0}} {t [{:var 'pt.vr/add}]} {}))
        green {:pass 1 :fail 0 :error 0}]
    (is (= {d1 (assoc green :digest d1)} (proofs/stored-verdicts rdb w0 {t d1} [t])) "found at its digest")
    (is (= {d2 (assoc green :digest d2)} (proofs/stored-verdicts rdb w0 {t d2} [t]))
        "at another digest, reused while what it read holds what it did")
    (is (= {} (proofs/stored-verdicts rdb w0 {t d2} [])) "never for a test that may not be reused by its reads")
    (let [w1 (mt/with w0 (mt/code 'pt.vr "(ns pt.vr)\n(defn add [x y] (+ y x))\n"))]
      (is (= {} (proofs/stored-verdicts rdb w1 {t d2} [t])) "and no longer once a function it called moved"))))
;; docs/storage.md#what-a-read-scanned: reuse by reads consults a test's NEWEST green only (by its basis :t), as the plan's read-digests
;; does: a cold plan walked every green of every test (median 9, max 36, of 21,715 verdicts), each ~410 read keys read and digested on
;; db, and three session agents planned 16 min each at 17.2B ticks (root, 2026-11-16; runs/stop-overran-40284). Red before: an older
;; green whose reads hold was reused though the newest's do not. Estimate: two worlds (~4M), one graph (~2M), two verdict-txs (~1M),
;; one lookup: 8,000,000
(deftest ^{:seon.test/estimate {:ticks 8000000}} reuse-by-reads-consults-a-tests-newest-green-only
  (let [d1 (seon.lib/digest "older run") d2 (seon.lib/digest "newer run") d3 (seon.lib/digest "the plan's digest")
        w0 (mt/with (mt/world) (mt/code 'pt.vr "(ns pt.vr)\n(defn add [x y] (+ x y))\n"))
        w1 (mt/with w0 (mt/code 'pt.vr "(ns pt.vr)\n(defn add [x y] (+ y x))\n"))
        t 'pt.vr-test/adds
        basis (fn [n] {:seon.db/branch :db :seon.db/commit (parse-uuid (str "00000000-0000-0000-0000-00000000000" n)) :seon.db/t n})
        older (proofs/verdict-tx w0 (program/reach-graph w0 nil) (basis 1) {t {:digest d1 :pass 1 :fail 0 :error 0}} {t [{:var 'pt.vr/add}]} {})
        newer (proofs/verdict-tx w1 (program/reach-graph w1 nil) (basis 2) {t {:digest d2 :pass 1 :fail 0 :error 0}} {t [{:var 'pt.vr/add}]} {})
        rdb (pdb/db-with w0 (into older newer))]
    (is (= {} (proofs/stored-verdicts rdb w0 {t d3} [t]))
        "the newest green's reads no longer hold on db: no reuse, though an older green's still do")
    (is (= #{d3} (set (keys (proofs/stored-verdicts rdb w1 {t d3} [t])))) "and the newest's, holding, is reused")
    ;; the batch digests what reads-digest digests one set at a time, byte for byte (root's review): every stored reads-digest still matches
    (let [ks (into #{} (map :v) (d/datoms rdb :aevt :seon.proof/read-keys))]
      (is (seq ks))
      (is (= {:all (pdb/reads-digest w0 ks nil) :one (pdb/reads-digest w0 #{(first (sort ks))} nil)}
             (pdb/reads-digests w0 {:all ks :one #{(first (sort ks))}}))
          "reads-digests is reads-digest, for every set")
      (is (= (seon.lib/digest "[[a] []]") (seon.lib/digest-parts [(.getBytes "[[") (.getBytes "a") (.getBytes "] []]")])) "and digest-parts is digest of the joined text"))))

(deftest ^{:seon.test/estimate {:ticks 200000}} the-fast-lane-line-sums-each-tests-own-cost
  ;; a verdict holds its called functions' cumulative costs (:fns) beside its own; a grep for the first :ticks took a
  ;; function's (bin/land printed ~13 trillion ticks for 580 tests, 2026-11-16): the sum is each result's own
  (let [ran {'a.b-test/one {:pass 1 :fail 0 :error 0 :fns {"#'a.b/f" {:calls 3 :ticks 9000000000 :bytes 7000000000}} :ticks 1200 :bytes 3400}
             'a.b-test/two {:pass 1 :fail 0 :error 0 :ticks 800 :bytes 600 :fns {}}
             'a.b-test/none {:pass 0 :fail 0 :error 1 :ticks nil}}]
    (is (= {:n 3 :ticks 2000 :bytes 4000} (proofs/own-cost ran)) "each test's own top-level work, never a called function's")
    (is (= {:n 0 :ticks 0 :bytes 0} (proofs/own-cost {})) "a shard that ran nothing did nothing")))

;; seon.agent/a-tests-result-is-read-in-one-seek-at-its-digest (B): a result's read keys are :seon.proof/read-keys datoms, AVET-indexed,
;; never one :seon.proof/reads value: a value of ~45 KB a result rode every :eavt leaf Datahike restored (a cold status read 270k ticks
;; / 1.24 MB, result/a45aadc3), and its readers were found only by an in-process index built from every test's value (~0.9B ticks /
;; 4.8 GB at 2,349 tests, result/b472c685). Key datoms cost a status read nothing, since every result read is :aevt or :avet (the cause
;; of the one value, ~118k ticks a status :eavt seek, result/aeeb6ff5, is gone with it); a test's newest green is its newest written.
;; Red first: verdict-tx wrote one value and no key datom. Estimate: two worlds (~4M), three verdict-txs, one plan's reuse (~1M).
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-results-read-keys-are-indexed-datoms
  (let [w0 (mt/with (mt/world) (mt/code 'pt.vk "(ns pt.vk)\n(defn add [x y] (+ x y))\n"))
        w1 (mt/with w0 (mt/code 'pt.vk "(ns pt.vk)\n(defn add [x y] (+ y x))\n"))
        t 'pt.vk-test/adds
        basis (fn [n] {:seon.db/branch :db :seon.db/commit (parse-uuid (str "00000000-0000-0000-0000-00000000000" n)) :seon.db/t n})
        tx (fn [w n] (proofs/verdict-tx w (program/reach-graph w nil) (basis n) {t {:digest (seon.lib/digest (str "run " n)) :pass 1 :fail 0 :error 0}} {t [{:var 'pt.vk/add}]} {}))
        ;; three greens, each its own transaction: the newest written (on w1) last, though its basis :t is the lowest
        rdb (-> w0 (pdb/db-with (tx w0 3)) (pdb/db-with (tx w0 2)) (pdb/db-with (tx w1 1)))
        plan (seon.lib/digest "the plan's digest")]
    (is (every? #(and (seq (:seon.proof/read-keys %)) (not (contains? % :seon.proof/reads))) (tx w0 3))
        "a result carries its read keys as datoms, and no reads value")
    (let [txs (mapcat (fn [[w n]] (tx w n)) [[w0 3] [w0 2] [w1 1]])
          k (first (sort (:seon.proof/read-keys (first (tx w1 1)))))]
      ;; a result is one entity by its content (verdict-id): two runs reading the same content to the same status are one
      (is (= (count (into #{} (comp (filter #(contains? (:seon.proof/read-keys %) k)) (map :seon.proof/id)) txs))
             (count (d/datoms rdb :avet :seon.proof/read-keys k)))
          "a key finds every result that read it, by one AVET seek"))
    (is (= #{plan} (set (keys (proofs/stored-verdicts rdb w1 {t plan} [t])))) "the newest written green, whose reads hold on w1, is reused")
    (is (= {} (proofs/stored-verdicts rdb w0 {t plan} [t])) "and only it: older greens' reads hold on w0, the newest's do not")))

;; E checklist step 0 (root's order): a read key names what was read at datom level, a function by its form's code, so a derived
;; attribute of the form moving (:form/keywords moved in 40 of 40 sampled reuse misses, result/be937ff1) leaves the result's
;; reads-digest where it was, and the form's code moving still moves it. Red first: read-keys named the whole form ([:e form]).
;; Estimate: one small world and graph (~4M), one verdict-tx (~0.5M), three reads-digests (<0.1M): 5,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-forms-derived-attribute-moving-moves-no-result
  (let [w0 (mt/with (mt/world) (mt/code 'pt.dk "(ns pt.dk)\n(defn add [x y] (+ x y))\n"))
        g (program/reach-graph w0 nil)
        t 'pt.dk-test/adds
        tx (proofs/verdict-tx w0 g {:seon.db/branch :db :seon.db/commit (parse-uuid "00000000-0000-0000-0000-000000000001") :seon.db/t 1}
                              {t {:digest (seon.lib/digest "run") :pass 1 :fail 0 :error 0}} {t [{:var 'pt.dk/add}]} {})
        ks (:seon.proof/read-keys (first tx))
        form (:e (first (d/datoms w0 :avet :form/id (first (get-in g [:defs 'pt.dk/add])))))
        derived (pdb/db-with w0 [[:db/add form :form/keywords :pt.dk/added]])
        recoded (pdb/db-with w0 [[:db/add form :form/code "pt.dk/another-code"]])]
    (is (= (pdb/reads-digest w0 ks nil) (pdb/reads-digest derived ks nil)) "a derived attribute of a form it read moves nothing")
    (is (not= (pdb/reads-digest w0 ks nil) (pdb/reads-digest recoded ks nil)) "the form's code moving moves it")))


;; docs/checks.md#the-generative-check-is-per-function: the [:fn]-without-:gen rule reads each schema's type by malli's walk, so a
;; map's key named :fn (the cost canary's [:map [:fn :qualified-symbol]], mg/registry red on main 2026-11-16) is no [:fn pred]
;; Estimate: three small schemas walked (~50k each) and the registry's every form parsed and walked once by mg-registry-gaps
;; (~1.5k forms at ~4k each, ~6M; the first estimate, 200k, missed that whole-registry walk) = ~8000000
(deftest ^{:seon.test/estimate {:ticks 8000000}} a-map-key-named-fn-is-no-fn-schema
  (let [gaps #'seon.proofs/fn-without-gen]
    (is (empty? (gaps [:map [:fn :qualified-symbol]])) "a key named :fn is a key")
    (is (= 1 (count (gaps [:map [:x [:fn even?]]]))) "a [:fn pred] value with no :gen is a gap")
    (is (empty? (gaps [:fn {:gen 'clojure.test.check.generators/int} even?])) "one with :gen is not")
    (is (not-any? #{:seon.cost/call-over-floor} (seon.proofs/mg-registry-gaps)) "the canary's error schema generates")))

;; THE FOCUS step (1): a keyed entity is read by its content attribute: a config entry by :config/form, a schema entry by
;; :schema/form, a file by :file/text, never [:av key v], whose content is the entity's id and so never moved when the value was
;; edited (a result that read an edited entry was reusable). Red first: read-keys keyed them [:av ...].
;; Estimate: one small world (~4M), two entity writes and four reads-digests (<0.5M): 6,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 6000000}} an-edited-config-entry-moves-the-results-that-read-it
  (let [w0 (-> (mt/world)
               (d/db-with [{:config/key :pt.ck/limit :config/form "3"}]))
        ks (proofs/read-keys w0 (program/reach-graph w0 nil) 'pt.ck-test/t nil [[:config :pt.ck/limit]])
        cfg-e (:e (first (d/datoms w0 :avet :config/key :pt.ck/limit)))
        edited (d/db-with w0 [[:db/add cfg-e :config/form "4"]])]
    (is (= #{(pr-str [:ea cfg-e :config/form])} ks) (pr-str ks))
    (is (not= (pdb/reads-digest w0 ks nil) (pdb/reads-digest edited ks nil)) "an edited config value moves what read it")))

;; THE FOCUS step (1), root's results db: a plan reads the results branch from the store of the db it plans, so a process holding no
;; agent's state (root) reuses results as an agent does; root's plan reused nothing (seon.agent/results-db read only an agent's
;; :store-conn). Red first: with no :store-conn, results-db answered nil. Estimate: a test branch and one more (~2M), a seek (~10k): 3,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-plan-in-a-process-with-no-agent-reads-the-results-branch
  (ts/test-branch!
    (fn [cfg conn]
      (let [main (:branch cfg)
            rb (pdb/on-main main (seon.config/value :seon.config.proofs/results-branch))
            _ (d/branch! conn main rb)]
        (try
          (with-redefs [seon.agent/state (atom {})]
            (is (some? (#'seon.agent/results-db (d/db conn))) "the results branch, from the planned db's store, with no agent state"))
          (finally (d/delete-branch! conn rb)))))))

;; THE FOCUS (C) (task seon.proofs/a-test-forks-from-the-standing-system-and-a-binary-test-reruns-only-for-its-binary): selection is
;; the tests whose reach digest moved and the readers of the change's datoms (seon.db/results-moved: their read-key datoms), so a
;; test that read a schema entry is selected when a branch edits that entry, whether or not its digest moved, and run, never reused
;; at that digest; a test that did not read it is neither. Red first: selection had no readers half (neither test selected).
;; Estimate: the world and 3 namespaces' facts (~4M), the reach graph of main the test reads its form from (~3M), the test plan's
;; base and branch graphs and digests (~8M: two graphs), two namespaces loaded (~1M), the branch's content diff (one descent per
;; content attribute, ~0.2M), ~5 AVET seeks a moved datom and a newest green a test hit (~20k), three
;; reads-digests of one key (<0.1M): ~16M, 20,000,000 ticks (the first estimate, 10M, left out the test's own graph and the
;; plan's second graph).
(deftest ^{:seon.test/estimate {:ticks 20000000}} a-schema-entry-edit-reruns-its-readers-only
  (let [calc "(ns pt.sr)\n(defn add [x y] (+ x y))\n(defn neg [x] (- x))\n"
        reader-src "(ns pt.sr-test (:require [clojure.test :refer [deftest is]] [pt.sr :as c]))\n(deftest reads-the-entry (is (= 3 (c/add 1 2))))\n"
        other-src "(ns pt.sr-other-test (:require [clojure.test :refer [deftest is]] [pt.sr :as c]))\n(deftest reads-another (is (= -1 (c/neg 1))))\n"
        reader 'pt.sr-test/reads-the-entry other 'pt.sr-other-test/reads-another
        main (-> (mt/with (mt/world) (mt/code 'pt.sr calc) (mt/code 'pt.sr-test reader-src) (mt/code 'pt.sr-other-test other-src))
                 (d/db-with [{:schema/key :pt.sr/thing :schema/form "[:map [:pt.sr/x :int]]"}]))
        entry (pdb/eid main :schema/key :pt.sr/thing)
        neg-form (:e (first (d/datoms main :avet :form/id (first (get-in (program/reach-graph main nil) [:defs 'pt.sr/neg])))))
        ;; a green each, on the results branch, whose recorded reads are the keys given, digested on main as its run's were
        green (fn [t ks] {:seon.proof/id (proofs/verdict-id t (seon.lib/digest (str t)) :pass) :seon.proof/test t
                          :seon.proof/reach-digest (seon.lib/digest (str t)) :seon.proof/status :pass :seon.proof/route :live
                          :seon.proof/ran-on #{:db}
                          :seon.proof/reads-digest (pdb/reads-digest main (into #{} (map pr-str) ks) nil)
                          :seon.proof/read-keys (into #{} (map pr-str) ks)})
        rdb (pdb/db-with main [(green reader [[:ea entry :schema/form]]) (green other [[:ea neg-form :form/code]])])
        branch (d/db-with main [{:schema/key :pt.sr/thing :schema/form "[:map [:pt.sr/x :string]]"}])]
    (is (and entry neg-form) "the entry and the other test's function are entities of main")
    (try (doseq [t [reader-src other-src]] (binding [*ns* (create-ns 'user)] (load-string t)))
         (with-redefs [seon.agent/state (atom {}) seon.agent/results-db (fn [_] rdb)]
           (let [plan (seon.agent/test-plan branch main #{})]
             (is (= #{reader} (:read-moved plan)) "the edit's datoms reach the result that read the entry, and only it")
             (is (contains? (set (:selected plan)) reader) "the reader is selected, though its reach digest did not move")
             (is (contains? (set (:run plan)) reader) "and run: what it read moved, so no verdict of it is reused")
             (is (not (contains? (set (:selected plan)) other)) "the test that did not read the entry is not selected")))
         (finally (run! remove-ns '[pt.sr pt.sr-test pt.sr-other-test])))))

;; THE FOCUS step (1): one public read of a test's result, seon.agent/results-at, for any process: a test's stored result on the
;; results branch at the digest the plan gives it, and nothing for a test with none. Red first: no such read existed (a stand-in
;; answering {} for every test). Estimate: one small world and a verdict-tx (~5M), two seeks: 6,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 6000000}} results-at-answers-a-tests-stored-result-and-nothing-for-one-without
  (let [w0 (mt/with (mt/world) (mt/code 'pt.ra "(ns pt.ra)\n(defn add [x y] (+ x y))\n"))
        t 'pt.ra-test/adds u 'pt.ra-test/never-ran d (seon.lib/digest "the plan's digest")
        rdb (pdb/db-with w0 (proofs/verdict-tx w0 (program/reach-graph w0 nil) {:seon.db/branch :db :seon.db/commit (parse-uuid "00000000-0000-0000-0000-000000000001") :seon.db/t 1}
                                               {t {:digest d :pass 1 :fail 0 :error 0}} {t [{:var 'pt.ra/add}]} {}))]
    (with-redefs [seon.agent/test-plan (fn [_ _ _] {:digests {t d u (seon.lib/digest "another")}})
                  seon.agent/results-db (fn [_] rdb)]
      (let [r (seon.agent/results-at w0 #{t u})]
        (is (= {:digest d :pass 1 :fail 0 :error 0} (get r t)) (pr-str r))
        (is (not (contains? r u)) "a test with no result at its digest is left out")))))

;; THE FOCUS step (1) (C) slice 2, tests' half: what a test read of schema entries (Datahike's recorder, [:ea e :schema/form], e a
;; content id) rides its result, so a schema edit reaches a test whose writes were checked against the entry (seon.db/checked
;; records it). Red first: the runner recorded nothing of what a test read. Estimate: one test run in-process (~1M), one seek: 2,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-tests-schema-reads-ride-its-result
  (let [w (mt/world)
        e 4242424242
        _ (create-ns 'pt.sk-test)
        v (intern 'pt.sk-test (with-meta 'reads-an-entry {:test (fn [] (count (d/datoms w :eavt e :schema/form)))}) nil)
        r (get (proofs/run-tests! ['pt.sk-test/reads-an-entry] {'pt.sk-test/reads-an-entry "d"} (fn [_])) 'pt.sk-test/reads-an-entry)]
    (is (contains? (:schema-reads r) [:ea e :schema/form]) (pr-str (select-keys r [:schema-reads :pass :error])))))

(def ^:private cold-sizes "The read keys a result holds, in the cold read's proof." [10 100 1000])

(defn- cold-path
  "Where the cold read's proof keeps its store of n keys a result: the system's temp dir, never the checkout (owner, 2026-11-16: no
  test edits the worktree), a directory of this process's own (on-disk-path's reason)."
  {:malli/schema [:=> [:cat pos-int?] :string]}
  [n] (str (System/getProperty "java.io.tmpdir") "/seon-cold-results-" (.pid (java.lang.ProcessHandle/current)) "-" n))

(defn cold-results-of
  "[db cand] for verdicts-at: 32 results of 32 tests, each holding n read keys (:seon.proof/read-keys datoms), on a store on disk of
  its own read through a 4 KB node cache, so every node read is a cold decode as the standing store's are; cand asks for 4 of them.
  The last resort (docs/storage.md): the in-memory test store decodes no node."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [path (cold-path n)
        _ (seon.lib/remove-tree! path)
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
        _ (d/release conn)
        c (pdb/connect (assoc cfg :store-cache-bytes 4096))
        basis {:seon.db/branch :db :seon.db/commit (parse-uuid "00000000-0000-0000-0000-000000000001") :seon.db/t 1}
        ts (mapv #(symbol "pt.cold-test" (str "t" %)) (range 32))
        ent (fn [i t] (let [rd (seon.lib/digest (str "reads " i))]
                        {:seon.proof/id (proofs/verdict-id t rd :pass) :seon.proof/test t :seon.proof/reach-digest (seon.lib/digest (str "reach " i))
                         :seon.proof/status :pass :seon.proof/route :live :seon.proof/ran-on #{(:seon.db/branch basis)} :seon.proof/reads-digest rd
                         :seon.proof/read-keys (into #{} (map (fn [j] (pr-str [:ea (+ 1000 (* i 10000) j) :form/code]))) (range n))}))]
    (d/transact c {:tx-data (vec (map-indexed ent ts))})
    [@c (into {} (for [i [3 11 19 27]] [(ts i) (seon.lib/digest (str "reach " i))]))]))

;; seon.agent/a-tests-result-is-read-in-one-seek-at-its-digest: a cold read of a result costs nothing of what it read. Red first
;; (result/d322f7cb, 4 candidates, keys then one :seon.proof/reads value): verdicts-at read each attribute through :eavt, whose leaf
;; Datahike restores with every result's reads in it: 1.3 MB at 10 keys, 8.7 MB at 100, 70 MB at 1,000; through :aevt 477 KB at each.
;; Estimate: 3 stores made and 32 results written (~10M each), 3 sizes measured cold a few times (~0.5M each): 40,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 40000000}} a-results-read-costs-nothing-of-its-reads-value
  (try
    (cost/proof
      {:of 'seon.proofs/verdicts-at :input 'seon.proofs-test/cold-results-of
       :n "read keys each result holds"
       :work (str "4 candidates, each a seek of its test's results and four :aevt reads of small attributes, every node read cold: "
                  "the path's depth, log of the store's datoms (32 n key datoms), never a result's keys decoded")
       :growth :log
       ;; a cold node read ~24 KB and ~5k ticks (docs/cost.md); 4 candidates x 5 reads on a path of cold misses, one level more as
       ;; the store grows (the first estimate, constant, missed the depth: 10 -> 100 keys added one level, 1,000 none, result/ab69a994)
       :fixed {:ticks 100000 :bytes 400000}
       :per-n {:ticks 25000 :bytes 150000}
       :sizes cold-sizes})
    (finally (doseq [n cold-sizes :let [path (cold-path n)] :when (pdb/store? path)]
               (d/release (pdb/connect (assoc (pdb/store-cfg :lmdb path) :store-cache-bytes 4096)) true)
               (seon.lib/remove-tree! path)))))

;; seon.agent/a-tests-result-is-read-in-one-seek-at-its-digest, the plan's memory (root's seon_done ran out of its agent's 2.5 GB building
;; it, result/b624f125): a judge's plan looks up stored results only for what it selects (the tests its change reached, and the proofs).
;; Red first: it looked up every test's (2,301 at an agent: 4.2B of the plan's 5.2B ticks, 19 GB allocated, result/de6441cd), so an
;; unselected test's stored green came back in :reused.
;; Estimate: three test plans of a ~14-form world (~5M ticks each) and one result written (~0.5M): 20M ticks
(deftest ^{:seon.test/estimate {:ticks 20000000}} a-judges-plan-looks-up-only-what-it-selects
  (let [src "(ns pt.lk)\n(defn a [x] (inc x))\n(defn b [x] (dec x))\n"
        tests "(ns pt.lk-test (:require [clojure.test :refer [deftest is]] [pt.lk :as k]))\n(deftest uses-a (is (= 2 (k/a 1))))\n(deftest uses-b (is (= 0 (k/b 1))))\n"
        w (mt/with (mt/world) (mt/code 'pt.lk src) (mt/code 'pt.lk-test tests))]
    (try (binding [*ns* (create-ns 'user)] (load-string src) (load-string tests))
         (let [p0 (seon.agent/test-plan w nil #{'pt.lk-test/uses-b})
               basis {:seon.db/branch :db :seon.db/commit (parse-uuid "00000000-0000-0000-0000-000000000001") :seon.db/t 1}
               ;; main holds a green of uses-b at its digest
               main (pdb/db-with w (proofs/verdict-tx w (:graph p0) basis {'pt.lk-test/uses-b {:digest (get (:digests p0) 'pt.lk-test/uses-b) :pass 1 :fail 0 :error 0}}
                                                      {'pt.lk-test/uses-b [{:var 'pt.lk/b}]} {}))
               branch (mt/with main (mt/code 'pt.lk "(ns pt.lk)\n(defn a [x] (+ x 1))\n(defn b [x] (dec x))\n"))
               plan (seon.agent/test-plan branch main #{})]
           (is (= '[pt.lk-test/uses-a] (:selected plan)) "the change reaches uses-a alone")
           (is (= #{} (set (remove (set (:selected plan)) (keys (:reused plan))))) "and no result is looked up for a test it did not select")
           (is (contains? (:reused (seon.agent/test-plan branch main #{'pt.lk-test/uses-b})) 'pt.lk-test/uses-b)
               "a test asked for by name is looked up, as results-at asks"))
         (finally (run! remove-ns '[pt.lk pt.lk-test])))))

;; THE FOCUS (E) step 1 (data's condition): a result's identity is its whole content (test, reads digest, status), so two branches'
;; results never conflict at a merge: a different status at one reach digest is another entity (flaky is a query), and the lookup at
;; a reach digest answers the newest. Red first: the identity was (test, reach digest) with a write-once status, so the second
;; status was refused. Estimate: one small world (~4M), two verdict-txs and writes (~1M): 6,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 6000000}} two-statuses-at-one-reach-digest-are-two-results
  (let [w0 (mt/with (mt/world) (mt/code 'pt.fl "(ns pt.fl)\n(defn add [x y] (+ x y))\n"))
        g (program/reach-graph w0 nil) t 'pt.fl-test/adds d (seon.lib/digest "one digest")
        basis {:seon.db/branch :db :seon.db/commit (parse-uuid "00000000-0000-0000-0000-000000000001") :seon.db/t 1}
        green (proofs/verdict-tx w0 g basis {t {:digest d :pass 1 :fail 0 :error 0}} {t [{:var 'pt.fl/add}]} {})
        red (proofs/verdict-tx w0 g basis {t {:digest d :pass 0 :fail 1 :error 0 :failures [{:type :fail :message "flaked"}]}} {t [{:var 'pt.fl/add}]} {})
        db (pdb/db-with (pdb/db-with w0 green) red)]
    (is (= 2 (count (d/datoms db :avet :seon.proof/test t))) "both results are kept, neither refused")
    (is (= #{:pass :fail} (set (for [dt (d/datoms db :avet :seon.proof/test t)] (:v (first (d/datoms db :eavt (:e dt) :seon.proof/status)))))))
    (is (= 1 (:fail (get (proofs/verdicts-at db {t d}) d))) "the lookup at the reach digest answers the newest written")))


;; THE FOCUS (E), results ride the one :merge (data, agreed with tests2): a result is one entity for every run of it, and each branch it ran
;; on is one of its :seon.proof/ran-on, so two branches' runs of one result never conflict at a merge (it unions them) and a red is reused
;; by every branch that ran it. Red first: :seon.proof/basis held one run, the second branch's replaced the first's, and a merge of the two
;; was a conflict at [:datum e :seon.proof/basis] (data's measure on a branch pair, 2026-11-16). Estimate: one small world (~4M), two
;; verdict-txs and writes (~1M): 6,000,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 6000000}} a-result-holds-each-branch-it-ran-on-and-its-red-is-each-runners
  (let [w0 (mt/with (mt/world) (mt/code 'pt.rb "(ns pt.rb)\n(defn add [x y] (+ x y))\n"))
        g (program/reach-graph w0 nil) t 'pt.rb-test/adds d (seon.lib/digest "one reach digest")
        basis (fn [b n] {:seon.db/branch b :seon.db/commit (parse-uuid (str "00000000-0000-0000-0000-00000000000" n)) :seon.db/t n})
        red (fn [b n] (proofs/verdict-tx w0 g (basis b n) {t {:digest d :pass 0 :fail 1 :error 0 :failures [{:type :fail :message "red"}]}} {t [{:var 'pt.rb/add}]} {}))
        db (pdb/db-with (pdb/db-with w0 (red :main/a 1)) (red :main/b 2))
        es (d/datoms db :avet :seon.proof/test t)]
    (is (= 1 (count es)) "one result: its identity is its content")
    (is (= #{:main/a :main/b} (into #{} (map :v) (d/datoms db :eavt (:e (first es)) :seon.proof/ran-on))) "each branch it ran on")
    (is (= #{:main/a :main/b} (get-in (proofs/verdicts-at db {t d}) [d :writer :branches])) "and its red is reused by each branch that ran it")))

;; docs/merging.md#the-test-service (web's report, result/c529a48e: :pass 894 :fail 2 for 2 named tests, no failing report named, no red):
;; a run's counts are its items' own, never their batch's answer, which every item of a batch shares and which holds the other tests
;; that ran in its child. Red first: the counts summed each item's (:answer r), so a batch's failures elsewhere were counted once an
;; item and named by no report. Estimate: one map assembly over 2 items and 3 reports (~50k ticks): 200,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 200000}} a-runs-counts-are-its-items-own-never-their-batchs
  (let [batch {:pass 3 :fail 2 :error 0
               :reports [{:type :pass :var "#'a.b-test/one"} {:type :pass :var "#'a.b-test/two"}
                         {:type :fail :var "#'a.b-test/other" :message "not this run's"}]}
        items {'a.b-test/one {:pass 1 :fail 0 :error 0 :answer batch}
               'a.b-test/two {:pass 1 :fail 0 :error 0 :answer batch}}
        r (#'seon.prove/run-answer {:selected '[a.b-test/one a.b-test/two]} items)]
    (is (= [2 0 0] [(:pass r) (:fail r) (:error r)]) "each item counted once, by its own counts")
    (is (= [] (:red r)) "and nothing red: the batch's other failure is not this run's")))

;; docs/merging.md#a-merge-runs-what-the-change-reaches (web, result/cd4ba881): a session's own task (:user, driven from outside) hands
;; back through seon_done, which the judge takes as work, so its process tests are the gate's, never left unjudged by its kind; a
;; person's own :user task is not judged. Red first: unjudged kept every task not :work/:merge/:whole off the gate.
;; Estimate: two calls on the test db, one root read each (~50k): 300,000 ticks.
(deftest ^{:seon.test/estimate {:ticks 300000}} a-sessions-task-is-judged-by-the-gate-as-work
  (let [db (ts/test-db) tests ['a.b-test/p]
        kind-why #(some-> (seon.task/unjudged db % tests) :why (str/includes? "isn't judged by the gate"))]
    (is (not (kind-why {:seon.task/kind :user :seon.task/model "outside"})) "a session's task is the gate's, as work is")
    (is (kind-why {:seon.task/kind :user :seon.task/model "claude-sonnet"}) "a person's :user task driven by its own model is not")))
