(ns seon.check-test
  "bin/check's store (owner, 2026-10-21: one store, many branches, as in production): the run
  seeds one store (seon.check/seed); every step's root opens it on a main of its own, a
  branch of the seed (seon.db/open!, as seon.root/start! does), names its agents' branches
  under that main, and a recovery step reopens the same store on the same main."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [clojure.core.async :as a] [seon.check] [seon.source] [clojure.string]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest steps-are-branches-of-one-store
  ;; a store on disk of this test's own, the last resort: what is proved is how bin/check
  ;; opens its LMDB store from separate roots and reopens it after a kill; removed at the end
  (let [path (str (System/getProperty "user.dir") "/target/check-test-" (random-uuid))
        seed (pdb/open! :lmdb path 300 :db)
        a (pdb/open! :lmdb path 300 :smoke-1)
        b (pdb/open! :lmdb path 300 :drain-1)]
    (try
      (testing "the run seeds the store once; each step's main is a new branch of it"
        (is (:seeded? seed))
        (is (not (:seeded? a)))
        (is (not (:seeded? b)))
        (is (not (:recovered? a)))
        (is (= [:smoke-1 :drain-1] (mapv (comp pdb/main-branch :cfg) [a b])))
        (is (= (get-in seed [:cfg :store :id]) (get-in a [:cfg :store :id]) (get-in b [:cfg :store :id])))
        (is (= (d/commit-id @(:conn seed)) (d/merge-base (:conn a) :smoke-1 :db) (d/merge-base (:conn b) :drain-1 :db)))
        (is (= (count (d/datoms @(:conn seed) :eavt)) (count (d/datoms @(:conn a) :eavt)))))
      (testing "a step's writes stay on its main"
        (d/transact (:conn a) [{:note/text "smoke's"}])
        (is (= #{"smoke's"} (notes @(:conn a))))
        (is (= #{} (notes @(:conn b))))
        (is (= #{} (notes @(:conn seed)))))
      (testing "agents' branches are named under each root's main, so steps never share one; on :db (production) as given"
        (is (= :smoke-1/agent-1 (pdb/on-main :smoke-1 :agent-1)))
        (is (= :smoke-1/agent-1 (pdb/on-main :smoke-1 :smoke-1/agent-1)))
        (is (= :smoke-1 (pdb/on-main :smoke-1 :db)))
        (is (not= (pdb/on-main :smoke-1 :agent-1) (pdb/on-main :drain-1 :agent-1)))
        (is (= :agent-1 (pdb/on-main :db :agent-1)))
        (d/branch! (:conn a) :smoke-1 :smoke-1/agent-1)
        (d/branch! (:conn b) :drain-1 :drain-1/agent-1)
        (is (= [:agent-1] (pdb/of-main :smoke-1 (d/branches (:conn a)))))
        (is (= [:agent-1] (pdb/of-main :drain-1 (d/branches (:conn a))))))
      (testing "a recovery step reopens the same store on the same main"
        (pdb/sync! (:cfg a))
        (run! #(d/release (:conn %)) [a b seed])
        (let [r (pdb/open! :lmdb path 300 :smoke-1)]
          (is (:recovered? r))
          (is (not (:seeded? r)))
          (is (= #{"smoke's"} (notes @(:conn r))))
          (d/release (:conn r))))
      (finally
        (clojure.java.shell/sh "rm" "-rf" path)))))

(defn- entries "The names in directory p (none when it does not exist)." [p]
  (set (map #(.getName %) (or (.listFiles (java.io.File. p)) []))))

(deftest a-check-leaves-its-store-as-it-found-it
  ;; task seon.check/a-run-takes-a-branch-and-deletes-it: a check step run against a store
  ;; works on a branch of it (its main, check-<step>) and deletes what it made when it is
  ;; green; a test's branch of the test store is deleted by its fixture (seon.test-store/test-branch!);
  ;; neither makes a store. The store given is this test's own (the last resort: what is
  ;; proved is what a run leaves in the store it is given); removed at the end.
  (let [dir (System/getProperty "user.dir")
        path (str dir "/target/runs/check-test-given-" (random-uuid))
        _ (d/release (:conn (pdb/open! :lmdb path 300 :db)))
        branches #(let [c (d/connect (pdb/store-cfg :lmdb path))] (try (d/branches c) (finally (d/release c))))
        before (branches)
        ;; the step's own runs directory: other tests (bin/test's shards run at once) make theirs
        ;; under target/runs while this one runs, so only a directory of its own shows what it made
        runs (str dir "/target/runs/check-test-runs-" (random-uuid))
        _ (.mkdirs (java.io.File. runs))
        run (ts/binary-sh "env" (str "SEON_STORE=" path) (str "SEON_RUNS=" runs) "SEON_WEB_PORT=0" "bin/check" "config" :dir dir)]
    (try
      (is (= 0 (:exit run)) (str "the step is green: " (:out run)))
      (is (= before (branches)) "the store's branch set holds nothing the step made")
      (is (= #{} (entries runs)) "and no new store or run directory exists")
      (testing "a test's branch of the test store is deleted when the test ends"
        (let [seen (fn [cfg conn] [(:branch cfg) (d/branches conn) (count (d/datoms @conn :eavt))])
              [b1 in1 datoms] (ts/test-branch! seen)
              [b2 in2] (ts/test-branch! seen)]
          (is (pos? datoms) "the fixture hands the test a branch holding the seed")
          (is (in1 b1) "the branch exists while the test runs")
          (is (not (in2 b1)) "a later test sees no branch the earlier one made")
          (is (= (disj in1 b1) (disj in2 b2)) "and nothing else changed")))
      (finally (clojure.java.shell/sh "rm" "-rf" path (str path "-agents") runs)))))

(deftest records-are-read-at-the-agents-commit
  ;; task seon.check/mcp-reads-the-agents-records-once-committed: the branch is read again at
  ;; each of the agent's commit events, never before one when its records are not there yet
  (let [ev (a/chan 8)
        reads (atom 0)
        ;; the branch shows the records from its second read on: after a commit
        read #(if (= 1 (swap! reads inc)) [] [:r1 :r2])]
    (doseq [e [{:type :reply :agent :a1} {:type :commit :agent :a2 :t 5} {:type :commit :agent :a1 :t 7} {:type :ready :agent :a3}]]
      (a/>!! ev e))
    (is (= [:r1 :r2] (#'seon.check/await-records ev :a1 read #(= 2 (count %)))))
    (is (= 2 @reads) "once at the start, once at a1's commit")
    (is (= {:type :ready :agent :a3} (a/poll! ev)) "it waited through a1's commit, not a2's or a reply")))

(deftest the-probe-says-what-it-took
  ;; task seon.check/every-wait-is-the-event-it-means: the system check awaits the probe's
  ;; own event for its third message, not a window
  (let [ev (a/chan 4)
        st (#'seon.check/probe-proc {:tag "one" :events ev})
        [st o1] (#'seon.check/probe-proc st :in 1)
        [_ o2] (#'seon.check/probe-proc st :in 2)]
    (is (= {:events ev} (:clojure.core.async.flow/out-ports st)))
    (is (= [[{:type :probe :tag "one" :got 1}] [{:type :probe :tag "one" :got 2}]]
           (map #(mapv (fn [e] (dissoc e :at-ms)) (:events %)) [o1 o2])))))

(deftest every-test-root-starts-through-start-root!
  ;; task seon.check/a-test-root-names-its-store-and-main: read from the program of test/ (the
  ;; compiler's references, not a text search over files), no test var but the helper's one
  ;; launcher runs bin/root or `./seon root` through binary-sh, so every test root names its
  ;; store and main and waits on its listening line
  (let [files (into {} (for [^java.io.File f (file-seq (java.io.File. "test")) :when (.endsWith (.getName f) ".clj")]
                         [(.getPath f) (slurp f)]))
        db (d/db-with (ts/test-db) (seon.source/import-tx (ts/test-db) files))
        launchers (set (for [[v src] (d/q '[:find ?v ?s :where [?e :var/name ?v] [?e :var/form ?f]
                                             [?f :form/refs seon.test-store/binary-sh] [?f :form/src ?s]] db)
                             ;; binary-sh is the primitive the one launcher calls; this test names both in its query
                             :when (not (#{'seon.test-store/binary-sh 'seon.check-test/every-test-root-starts-through-start-root!} v))
                             :when (some #(clojure.string/includes? src %) ["bin/root" "seon root" "\"./seon\" \"root\""])]
                         v))]
    (is (seq files))
    (is (= #{'seon.test-store/root-sh} launchers))))
