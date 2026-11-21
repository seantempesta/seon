(ns seon.health-perf-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.code :as code]
            [seon.health :as health]
            [seon.health.efficiency :as eff]
            [seon.health.static :as hs]
            [seon.test-fixture :refer [with-test-store]]))

(defn- bulk-perf-txs [db run-id n-nss]
  (let [pad (apply str (repeat 650 " "))]
    (mapcat
     (fn [i]
       (let [nsym (symbol (str "seon.perf.m" run-id "-" i))
             path (str "src/seon/perf/m" run-id "_" i ".clj")
             src (str "(ns " nsym " (:require [clojure.string :as str]))\n"
                      "(defn f1 {:malli/schema [:=> [:cat :string] :string]} [s] (str/trim s))\n"
                      "(defn f2 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))\n"
                      "(defn f3 {:malli/schema [:=> [:cat :seon.host/db] :map]} [db] "
                      ";;" pad "\n{:ok (some? db)})\n")]
         (code/file-import-tx db path src false)))
     (range n-nss))))

(deftest ^{:cost/ticks 120000000} report-parity-and-allocation-budget-test
  (testing "seon.health/report preserves exact problem classes/counts, records reads, and stays well under budget"
    (with-test-store [{:keys [conn]}]
      (let [run-id (subs (str (random-uuid)) 0 8)
            pad (apply str (repeat 650 " "))
            tx1 (concat
                 (bulk-perf-txs @conn run-id 25)
                 (code/file-import-tx
                  @conn (str "src/seon/perf/diag_" run-id ".clj")
                  (str "(ns seon.perf.diag-" run-id " (:require [clojure.set :as set] [clojure.string :as str] [datahike.api :as d]))\n"
                       "(defn ^:seon.flow/edge edge-op! [a] (swap! a inc))\n"
                       "(defn caller {:malli/schema [:=> [:cat :string] :string]} [s] (helper s))\n"
                       "(defn helper {:malli/schema [:=> [:cat :string] :string]} [s] (str/trim s))\n"
                       "(defn bad-scan [db xs] ;;" pad "\n (mapcat #(d/datoms db :aevt %) xs))\n"
                       "(defn bad-doc \"Missing `seon.perf.diag-" run-id "/ghost`.\" [x] x)\n"
                       "(defn bad-gen {:malli/schema [:=> [:cat :int] :any]} [x] (edge-op! (atom x)))\n"
                       "(defn bad-c {:malli/schema [:=> [:cat :number] :int]} [x] x)\n"
                       "(defn merge-ok {:malli/schema [:=> [:cat :seon.host/conn :map :seon.store/branch] :seon.merge/plan]} [c m b] {:ok? true})\n")
                  false)
                 [{:seon.test/digest (str "perf-t1-" run-id)
                   :seon.test/var 'seon.perf/speed-test
                   :seon.test/kind :deftest
                   :seon.test/pass? true
                   :seon.test/ms 15.0
                   :seon.test/ticks 100000
                   :seon.test/bytes 1000000}])]
        (d/transact conn {:tx-data tx1})
        (d/transact conn {:tx-data [{:seon.test/digest (str "perf-t2-" run-id)
                                     :seon.test/var 'seon.perf/speed-test
                                     :seon.test/kind :deftest
                                     :seon.test/pass? true
                                     :seon.test/ms 1300.0
                                     :seon.test/ticks 15000000
                                     :seon.test/bytes 50000000}]})
        (let [db1 @conn
              cold (code/measure-call #(health/report db1))
              rep1 (:val cold)
              classes (set (keys (:counts rep1)))
              bad-c-subs (set (keep #(when (= :invalid-contract (:class %)) (:subject %)) (:problems rep1)))]
          (is (false? (:ok? rep1)))
          (is (contains? classes :nested-db-scan))
          (is (contains? classes :unused-require))
          (is (contains? classes :used-before-defined))
          (is (contains? classes :broken-doc-ref))
          (is (contains? classes :impure-generatable-fn))
          (is (contains? classes :invalid-contract))
          (is (contains? classes :slow-test))
          (is (contains? classes :cost-regression))
          (is (= #{(str "seon.perf.diag-" run-id "/bad-c")} bad-c-subs)
              "Valid :seon.merge/plan contract must pass transitive custom schema validation")
          ;; Add an unrelated non-code datom to advance tx & miss report-commit-cache, while per-form/per-ns caches are warm!
          (d/transact conn {:tx-data [{:seon.agent/id (keyword "perf" (str "ag-" run-id))
                                       :seon.agent/branch :main
                                       :seon.agent/role :worker}]})
          (let [db2 @conn
                warm-hit (code/measure-call #(health/report db2))
                ;; Force report-cache miss by editing 1 single form in 1 small ns:
                _ (d/transact conn {:tx-data (code/file-import-tx
                                              @conn (str "src/seon/perf/m" run-id "_0.clj")
                                              (str "(ns seon.perf.m" run-id "-0 (:require [clojure.string :as str]))\n"
                                                   "(defn f1 {:malli/schema [:=> [:cat :string] :string]} [s] (str/upper-case (str/trim s)))\n"
                                                   "(defn f2 {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))\n"
                                                   "(defn f3 {:malli/schema [:=> [:cat :seon.host/db] :map]} [db] "
                                                   ";;" pad "\n{:ok (some? db)})\n")
                                              false)})
                db3 @conn
                [warm-edit rset] (reads/with-reads (fn [] (code/measure-call #(health/report db3))))
                rep3 (:val warm-edit)]
            (is (= (:ok? rep1) (:ok? (:val warm-hit)) (:ok? rep3)))
            (is (= (:counts rep1) (:counts (:val warm-hit)) (:counts rep3)))
            (is (= (:problems rep1) (:problems (:val warm-hit)) (:problems rep3)))
            (is (seq rset) "Cached health/report must preserve datahike.reads dependencies")
            (is (< (:bytes warm-hit) 10000000) "Warm report-cache hit stays well below 10 MB")
            (is (< (:bytes warm-edit) 50000000) "Warm 1-form-edit health/report stays well below 50 MB")
            (is (< (:bytes warm-edit) (:bytes cold)) "Warm 1-form-edit allocates fewer bytes than cold")))))))

(deftest ^{:cost/ticks 80000000} single-form-hash-invalidation-and-catches-test
  (testing "Changing a single form hash invalidates ONLY that form's static check (no stale cache false-greens)"
    (with-test-store [{:keys [conn]}]
      (let [run-id (subs (str (random-uuid)) 0 8)
            ns-a (str "seon.inv.a" run-id)
            ns-b (str "seon.inv.b" run-id)
            path-a (str "src/seon/inv/a" run-id ".clj")
            path-b (str "src/seon/inv/b" run-id ".clj")]
        (d/transact conn {:tx-data (concat
                                    (code/file-import-tx @conn path-a
                                                         (str "(ns " ns-a " (:require [datahike.api :as d]))\n"
                                                              "(defn target [db xs] (count xs))\n")
                                                         false)
                                    (code/file-import-tx @conn path-b
                                                         (str "(ns " ns-b ")\n"
                                                              "(defn clean-b [x] (+ x 1))\n")
                                                         false))})
        (let [rep-clean (health/report @conn)
              f-b-hash (:seon.code.form/hash (d/pull @conn '[:seon.code.form/hash] [:seon.code.form/key (str ns-b "/clean-b")]))]
          (is (not-any? #(= :nested-db-scan (:class %)) (:problems rep-clean)))
          (is (contains? @#'eff/form-static-cache [f-b-hash false]) "Unchanged form is cached by form hash")
          ;; Edit ONLY ns-a/target -> introduce a :nested-db-scan!
          (d/transact conn {:tx-data (code/file-import-tx @conn path-a
                                                          (str "(ns " ns-a " (:require [datahike.api :as d]))\n"
                                                               "(defn target [db xs] (mapcat #(d/datoms db :aevt %) xs))\n")
                                                          false)})
          (let [rep-bad (health/report @conn)
                nested (filter #(= :nested-db-scan (:class %)) (:problems rep-bad))]
            (is (= 1 (count nested)))
            (is (= (str ns-a "/target") (:subject (first nested))))
            (is (contains? @#'eff/form-static-cache [f-b-hash false]) "Untouched ns-b form stays cached"))
          ;; Fix ns-a/target back -> :nested-db-scan clears immediately!
          (d/transact conn {:tx-data (code/file-import-tx @conn path-a
                                                          (str "(ns " ns-a " (:require [datahike.api :as d]))\n"
                                                               "(defn target [db xs] (count xs))\n")
                                                          false)})
          (is (not-any? #(= :nested-db-scan (:class %)) (:problems (health/report @conn))))))))

  (testing "Compiler-exact :seon.code.var/catches flags silent catch-alls on non-edge src vars"
    (with-test-store [{:keys [conn]}]
      (let [run-id (subs (str (random-uuid)) 0 8)
            ns-c (symbol (str "seon.catch.demo" run-id))
            ev (code/eval-forms @conn {:agent-id :ag/catch-test :turn 1 :default-ns ns-c}
                                (str "(ns " ns-c ")\n"
                                     "(defn bad-swallow [x] (try (inc x) (catch Throwable _ nil)))\n"
                                     "(defn ^:seon.flow/edge ok-edge-catch [x] (try (inc x) (catch Throwable _ nil)))\n"
                                     "(defn ok-rethrow [x] (try (inc x) (catch Exception e (throw e))))\n"
                                     "(defn ok-specific-catch [x] (try (inc x) (catch clojure.lang.ExceptionInfo _ nil)))"))]
        (try
          (is (:ok? ev))
          (d/transact conn {:tx-data (:tx-data ev)})
          (let [db @conn
                direct (hs/silent-catch-warnings db ns-c)
                scoped (health/static-problems-for-scope db ns-c)
                rep (health/report db)
                catch-warns (filter #(= :silent-catch-all (:class %)) (:problems rep))]
            (is (:ok? rep) "Silent catch-all is a :warning advisory, never blocking merge")
            (is (= 1 (count direct)))
            (is (= (str ns-c "/bad-swallow") (:subject (first direct))))
            (is (= :warning (:severity (first direct))))
            (is (some #(= :silent-catch-all (:class %)) scoped))
            (is (= 1 (count catch-warns)))
            (is (= (str ns-c "/bad-swallow") (:subject (first catch-warns)))))
          (finally
            (remove-ns ns-c)))))))
