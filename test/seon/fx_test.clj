(ns seon.fx-test
  "The effect owner and worker step-fns as plain functions (no graph): admission by workers,
  priority order, budget refused, the queue bound, and a worker answering a defect."
  (:require [clojure.test :refer [deftest is testing]] [jolt.deps] [seon.fx :as fx] [seon.config :as cfg]
            [clojure.core.async.flow :as flow]))

(defn- owner [workers] (fx/owner-proc {:kind :t :workers workers :work nil :back nil}))
(defn- req [k & {:as more}] (merge {:req {:k k} :dest {:agent :a1 :op :fx-reply :k k}} more))

(deftest owner-admits-by-workers-and-priority
  (let [[st o] (fx/owner-proc (owner 1) :in (req 1))
        _ (is (= [1] (map #(get-in % [:req :k]) (:to-workers o))) "a free worker takes the first request")
        [st o] (fx/owner-proc st :in (req 2))
        _ (is (empty? (:to-workers o)) "no free worker: queued")
        [st _] (fx/owner-proc st :in (req 3 :prio 5))
        [st o] (fx/owner-proc st :done {:dest {:agent :a1 :op :fx-reply :k 1} :outcome {:value 1}})]
    (is (= [{:dest {:agent :a1 :op :fx-reply :k 1} :r {:value 1}}] (:out o)) "the outcome goes to its asker")
    (is (= [3] (map #(get-in % [:req :k]) (:to-workers o))) "the higher priority runs next")
    (is (= 1 (:busy st)))))

(deftest owner-refuses-a-spent-budget
  (let [[st _] (fx/owner-proc (owner 1) :in (req 1 :budget 10 :budget-key :task-a))
        [st _] (fx/owner-proc st :done {:dest {:agent :a1 :op :fx-reply :k 1} :outcome {:value 1} :budget-key :task-a :cost 10})
        [_ o] (fx/owner-proc st :in (req 2 :budget 10 :budget-key :task-a))]
    (is (= :seon.fx/budget-spent (get-in (first (:out o)) [:r :seon.error/type])))))

(deftest owner-stops-reading-at-its-bound
  (let [st (reduce (fn [st k] (first (fx/owner-proc st :in (req k)))) (owner 0) (range (cfg/value :seon.config.fx/buf)))]
    (is (false? ((::flow/input-filter st) :in)))
    (is (true? ((::flow/input-filter st) :done)))))

(deftest a-worker-answers-a-defect-as-data
  (let [st (fx/worker-proc {:kind :t :perform (fn [_] (throw (ex-info "boom" {}))) :owner :fx/t})
        [_ o] (fx/worker-proc st :work (assoc (req 1) :in-ns 0 :taken-ns 0))
        out (first (get o [:fx/t :done]))]
    (is (= :seon.fx/defect (get-in out [:outcome :seon.error/type])))
    (is (= {:agent :a1 :op :fx-reply :k 1} (:dest out)))))

;; ---- write-back answers what happened (audit FENCES #21) ------------------------------

(defn- scratch-dir []
  (let [d (str (System/getProperty "user.dir") "/target/tmp/fx-test-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(deftest a-failed-rename-is-an-io-error-and-leaves-no-tmp
  (let [d (scratch-dir)
        target (str d "/occupied")
        _ (.mkdirs (java.io.File. (str target "/inside")))
        r (fx/write-file {:req {:path target :text "text"}})]
    (is (= :seon.fx/io-error (:seon.error/type r)) "renaming over a non-empty directory fails, and says so")
    (is (pos? (:errno r)) "with rename(2)'s errno")
    (is (= target (:path r)))
    (is (not (.exists (java.io.File. ^String (:tmp r)))) "the staged file is removed")
    (is (= ["occupied"] (vec (.list (java.io.File. d)))) "nothing else is left beside it"))
  (let [r (fx/write-file {:req {:path "/nonexistent-dir/x.clj" :text "text"}})]
    (is (= :seon.fx/io-error (:seon.error/type r)) "a missing directory: the stage itself fails")))

(deftest a-write-back-answers-the-bytes-on-disk
  (let [d (scratch-dir)
        p (str d "/f.clj")
        text "(def s \"héllo — world\")\n"
        r (fx/write-file {:req {:path p :text text}})]
    (is (= {:value {:bytes (.length (java.io.File. p)) :path p}} r))
    (is (= (+ (count text) 1 2) (get-in r [:value :bytes])) "bytes on disk, not characters: e-acute 2, em dash 3")
    (is (= text (slurp p)))
    (is (= ["f.clj"] (vec (.list (java.io.File. d)))) "no staged file left")))

(deftest a-dependency-is-resolved-through-its-owner
  ;; task seon.fx/a-dependency-is-fetched-through-its-owner (the PKG plan, owner-approved):
  ;; the :deps performer resolves a deps map with Jolt's own jolt.deps and answers the roots
  ;; to load from, or its one typed error naming what did not resolve; no network here (a
  ;; :local/root library made in this test's scratch dir)
  (let [d (scratch-dir)
        lib (str d "/tinylib")
        _ (.mkdirs (java.io.File. (str lib "/src/tiny")))
        _ (spit (str lib "/deps.edn") "{:paths [\"src\"]}")
        _ (spit (str lib "/src/tiny/core.clj") "(ns tiny.core)\n(defn hello [] :hi)\n")
        r (fx/resolve-deps {:req {:deps {'tiny/tiny {:local/root lib}}}})]
    (is (some #(= (str lib "/src") %) (get-in r [:value :roots])) "the library's absolute source root")
    (let [bad (fx/resolve-deps {:req {:deps {'nowhere/nothing {:local/root (str d "/missing")}}}})]
      (is (= :seon.fx/unresolved (:seon.error/type bad)) "a dependency that cannot be resolved is its typed error")
      (is (contains? (set (:deps bad)) 'nowhere/nothing) "naming it"))))

(deftest a-missing-local-root-fails-in-jolts-resolver
  ;; task jolt.deps/a-missing-local-root-is-unresolved: red until Jolt's own resolver refuses it
  (let [d (scratch-dir)
        r (try (jolt.deps/resolve-deps {'nowhere/nothing {:local/root (str d "/missing")}} ".") nil
               (catch Throwable e (ex-data e)))]
    (is (contains? (set (map :coord (:unresolved r))) 'nowhere/nothing))))

(deftest a-merges-deferred-tests-read-from-the-runners-report
  ;; the merge gate (root's ruling, 2026-10-22) runs them through prove's one entry (land12):
  ;; a test it names RED is red (:error when it errored), one it does not name passed or was
  ;; green at its digest, and a run that never reached its tests (the plan or the build
  ;; failed) leaves every one :error, never a pass
  (let [tests ['seon.root-test/boots 'seon.root-test/threads 'seon.check-test/clean]
        out (str "prove: 3 keys (3 tests, 0 check steps), 2 to run\n"
                 "RED seon.root-test/threads {:pass 3, :fail 1, :error 0}\n"
                 "RED seon.check-test/clean {:pass 0, :fail 0, :error 1}\n"
                 "SHARD 2 tests, 0 green, 2 red, in 4123 ms\nTEST FAILED (logs: target/prove)\nPROVE FAILED\n")]
    (is (= {'seon.root-test/boots :pass 'seon.root-test/threads :fail 'seon.check-test/clean :error} (fx/test-results out 1 tests)))
    (is (= {'seon.root-test/boots :pass} (fx/test-results "prove: 1 keys (1 tests, 0 check steps), 0 to run\nPROVE OK\n" 0 ['seon.root-test/boots]))
        "reused green at its digest")
    (is (= {'seon.root-test/boots :error} (fx/test-results "TEST FAILED (bin/build seon: target/prove/build.log)\nPROVE FAILED\n" 1 ['seon.root-test/boots]))
        "a run that never reached its tests is no green")
    (is (= ["nice" "-n" "15" "bin/prove" "--tests" "seon.check-test" "seon.root-test"] (fx/test-command ['seon.check-test 'seon.root-test])))))
