(ns seon.health-perf-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.code :as code]
            [seon.health :as health]
            [seon.health.efficiency :as eff]
            [seon.health.static :as hs]
            [seon.test-fixture :refer [with-test-store]]
            [clojure.java.io :as io]
            [seon.git :as git]
            [seon.health.contracts :as contracts]
            [seon.merge :as merge]
            [seon.schema :as schema]
            [seon.store :as store]))

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
          (is (contains? @@#'eff/form-static-cache [f-b-hash false]) "Unchanged form is cached by form hash")
          ;; Edit ONLY ns-a/target -> introduce a :nested-db-scan!
          (d/transact conn {:tx-data (code/file-import-tx @conn path-a
                                                          (str "(ns " ns-a " (:require [datahike.api :as d]))\n"
                                                               "(defn target [db xs] (mapcat #(d/datoms db :aevt %) xs))\n")
                                                          false)})
          (let [rep-bad (health/report @conn)
                nested (filter #(= :nested-db-scan (:class %)) (:problems rep-bad))]
            (is (= 1 (count nested)))
            (is (= (str ns-a "/target") (:subject (first nested))))
            (is (contains? @@#'eff/form-static-cache [f-b-hash false]) "Untouched ns-b form stays cached"))
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

(deftest ^{:cost/ticks 120000000} merge-three-way-ignores-main-drift-and-conflicts-accurately-test
  (testing "three-way produces exact merge/conflict plans without scanning ours-ds and export-branch-ref! reuses precomputed plan"
    (with-test-store [{:keys [conn cfg]}]
      (let [src-v0 "(ns seon.mperf.app)\n\n(defn alpha {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))\n\n(defn beta {:malli/schema [:=> [:cat :int] :int]} [x] (* x 2))\n"
            _ (d/transact conn {:tx-data (code/file-import-tx nil "src/seon/mperf/app.clj" src-v0 false)})
            _ (store/branch! conn :main :branch/feat)
            bconn (store/connect cfg :branch/feat)
            repo (io/file (str "/tmp/seon-git-plan-test-" (System/nanoTime)))]
        (try
          (doseq [b (range 15)]
            (d/transact conn {:tx-data (vec (for [i (range 10)]
                                              {:seon.task/id (keyword "seon.mperf" (str "t-" b "-" i))
                                               :seon.task/title (str "Unrelated main task " b "-" i)
                                               :seon.task/done? false}))}))
          (d/transact conn {:tx-data [{:seon.code.form/key "seon.mperf.app/alpha"
                                       :seon.code.form/src "(defn alpha {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 10))"
                                       :seon.code.form/hash "1111111111111111"}]})
          (d/transact bconn {:tx-data [{:seon.code.form/key "seon.mperf.app/beta"
                                        :seon.code.form/src "(defn beta {:malli/schema [:=> [:cat :int] :int]} [x] (* x 99))"
                                        :seon.code.form/hash "2222222222222222"}]})
          (let [plan (merge/three-way conn cfg :branch/feat)]
            (is (:ok? plan))
            (is (empty? (:conflicts plan)))
            (is (= #{"src/seon/mperf/app.clj"} (:touched-paths plan)))
            (is (= [[:seon.code.form/key "seon.mperf.app/beta"]] (mapv :entity (:entity-diffs plan))))
            (.mkdirs repo)
            (git/git! (.getPath repo) "init" "-q" "-b" "main")
            (spit (io/file repo "README.md") "init\n")
            (git/git! (.getPath repo) "add" "README.md")
            (git/git! (.getPath repo) "-c" "user.name=Seon" "-c" "user.email=seon@local" "commit" "-q" "-m" "init")
            (let [exp (with-redefs [merge/three-way (fn [& _] (throw (ex-info "three-way called twice!" {})))]
                        (git/export-branch-ref! conn cfg (.getPath repo) :branch/feat plan))
                  subj (str/trim (git/git! (.getPath repo) "log" "-1" "--format=%s" (:ref exp)))]
              (is (= "refs/heads/seon/feat" (:ref exp)))
              (is (= #{"src/seon/mperf/app.clj"} (:touched-paths exp)))
              (is (<= (count subj) 72))
              (is (not (str/starts-with? subj "seon :branch/"))))
            (d/transact conn {:tx-data (:tx-data plan)})
            (let [rendered (code/render-path @conn "src/seon/mperf/app.clj")]
              (is (str/includes? rendered "(+ x 10)"))
              (is (str/includes? rendered "(* x 99)"))))
          (d/transact bconn {:tx-data [{:seon.code.form/key "seon.mperf.app/alpha"
                                        :seon.code.form/src "(defn alpha {:malli/schema [:=> [:cat :int] :int]} [x] (- x 5))"
                                        :seon.code.form/hash "3333333333333333"}]})
          (let [cplan (merge/three-way conn cfg :branch/feat)]
            (is (false? (:ok? cplan)))
            (is (some #(= [:seon.code.form/key "seon.mperf.app/alpha"] (:entity %)) (:conflicts cplan))))
          (finally
            (d/release bconn)
            (when (.exists repo) (store/rm-rf! repo))))))))

(deftest ^{:cost/ticks 60000000} caused-by-branch-short-circuit-and-empty-touched-nss-test
  (testing "caused-by-branch and missing-contract-violations short-circuit cleanly with zero full scans on empty touched-nss"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (code/file-import-tx nil "src/seon/sc/base.clj"
                                                      "(ns seon.sc.base)\n(defn nocontract [x] x)\n" false)})
      (store/branch! conn :main :branch/sc)
      (let [bconn (store/connect cfg :branch/sc)]
        (try
          (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/seon/sc/base.clj"
                                                           "(ns seon.sc.base)\n(defn nocontract [x] (inc x))\n" false)})
          (let [[m-set r-set] (reads/with-reads #(contracts/missing-contract-violations @conn @bconn #{}))
                [m-vec r-vec] (reads/with-reads #(contracts/missing-contract-violations @conn @bconn []))
                [c-set rc-set] (reads/with-reads #(health/caused-by-branch @conn @bconn {} #{}))
                [c-vec rc-vec] (reads/with-reads #(health/caused-by-branch @conn @bconn {} []))
                c-hit (health/caused-by-branch @conn @bconn {} ['seon.sc.base])]
            (is (= [] m-set))
            (is (= [] m-vec))
            (is (empty? r-set) "Empty #{} touched-nss must execute zero DB reads")
            (is (empty? r-vec) "Empty [] touched-nss must execute zero DB reads")
            (is (= [] c-set))
            (is (= [] c-vec))
            (is (empty? rc-set) "caused-by-branch on {} #{} must short-circuit with zero DB reads")
            (is (empty? rc-vec) "caused-by-branch on {} [] must short-circuit with zero DB reads")
            (is (some #(= :missing-contract (:class %)) c-hit)
                "Non-empty touched-nss still catches missing contracts via shared var-snapshot"))
          (finally (d/release bconn)))))))

(deftest ^{:cost/ticks 120000000} test-efficiency-o-live-vars-scaling-test
  (testing "test-snapshot and test-efficiency-warnings stay O(|live-vars|) with many superseded historical rows"
    (with-test-store [{:keys [conn]}]
      (doseq [batch (partition-all 40 (range 80))]
        (d/transact conn {:tx-data (vec (mapcat (fn [i]
                                                  [{:seon.test/digest (str "hist-fast-" i) :seon.test/var 'seon.o/fast-test
                                                    :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 5.0 :seon.test/ticks 1000 :seon.test/bytes 4096}
                                                   {:seon.test/digest (str "hist-slow-" i) :seon.test/var 'seon.o/slow-test
                                                    :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 50.0 :seon.test/ticks 10000 :seon.test/bytes 4096}
                                                   {:seon.test/digest (str "hist-reg-" i) :seon.test/var 'seon.o/reg-test
                                                    :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 30.0 :seon.test/ticks 100000 :seon.test/bytes 1000000}])
                                                batch))}))
      (d/transact conn {:tx-data [{:seon.test/digest "cur-fast-0001" :seon.test/var 'seon.o/fast-test
                                   :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 6.0 :seon.test/ticks 1200 :seon.test/bytes 4096}
                                  {:seon.test/digest "cur-slow-0001" :seon.test/var 'seon.o/slow-test
                                   :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 1450.0 :seon.test/ticks 10000 :seon.test/bytes 4096}
                                  {:seon.test/digest "cur-reg-0001" :seon.test/var 'seon.o/reg-test
                                   :seon.test/kind :deftest :seon.test/pass? true :seon.test/ms 350.0 :seon.test/ticks 9000000 :seon.test/bytes 50000000}]})
      (let [db @conn
            tsnap (eff/test-snapshot db)
            [warns rset] (reads/with-reads #(eff/test-efficiency-warnings db tsnap))
            by-class (group-by :class warns)
            ms-reads (filter #(= :seon.test/ms (:a (first %))) rset)]
        (is (= 3 (.size ^java.util.HashMap (:top2 tsnap))))
        (is (= ["seon.o/slow-test"] (mapv :subject (:slow-test by-class))))
        (is (= ["seon.o/reg-test" "seon.o/slow-test"] (vec (sort (map :subject (:cost-regression by-class))))))
        (is (<= (count ms-reads) 6)
            (str "Expected O(|live-vars|) <= 6 :seon.test/ms point reads, got " (count ms-reads)))))))

(deftest ^{:cost/ticks 60000000} heavy-eval-non-cycling-and-suppression-test
  (testing "heavy-eval emits 1 non-cycling telemetry notice and suppresses on resolved incoming call or zero-edit branch"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (code/file-import-tx nil "src/seon/he/demo.clj"
                                                      "(ns seon.he.demo)\n(defn f [x] x)\n" false)})
      (store/branch! conn :main :branch/he-edit)
      (store/branch! conn :main :branch/he-ro)
      (let [ce (store/connect cfg :branch/he-edit)
            cr (store/connect cfg :branch/he-ro)
            cid (random-uuid)]
        (try
          (d/transact ce {:tx-data (concat
                                    (code/file-import-tx @ce "src/seon/he/demo.clj"
                                                         "(ns seon.he.demo)\n(defn f [x] (inc x))\n" false)
                                    [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/he-edit :seon.agent.eval/turn 1
                                      :seon.agent.eval/handle 'result/e1 :seon.agent.eval/code "1" :seon.agent.eval/bytes 157286400}
                                     {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/he-edit :seon.agent.eval/turn 2
                                      :seon.agent.eval/handle 'result/e2 :seon.agent.eval/code "2" :seon.agent.eval/bytes 209715200}])})
          (let [w1 (filter #(= :heavy-eval (:class %)) (eff/eval-efficiency-warnings @ce :ag/he-edit))]
            (is (= 1 (count w1)))
            (is (= ":ag/he-edit" (:subject (first w1))))
            (is (= "Telemetry notice (non-blocking, immutable eval ledger — do not retry or retract): 2 heavy evals on branch (max 200 MB)"
                   (:message (first w1)))))
          (d/transact ce {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/he-edit :seon.agent.eval/turn 3
                                     :seon.agent.eval/handle 'result/e3 :seon.agent.eval/code "3" :seon.agent.eval/bytes 1000}
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/he-edit :seon.agent.eval/turn 4
                                     :seon.agent.eval/handle 'result/e4 :seon.agent.eval/code "4" :seon.agent.eval/bytes 188743680}]})
          (let [w2 (filter #(= :heavy-eval (:class %)) (eff/eval-efficiency-warnings @ce :ag/he-edit))]
            (is (= 1 (count w2)))
            (is (= ":ag/he-edit" (:subject (first w2))))
            (is (not (str/includes? (:message (first w2)) "result/e")))
            (is (str/includes? (:message (first w2)) "3 heavy evals on branch (max 200 MB)")))
          (d/transact conn {:tx-data [{:seon.agent.call/id cid :seon.agent.call/caller :agent/root
                                       :seon.agent.call/callee :ag/he-edit :seon.agent.call/prompt "do work"
                                       :seon.agent.call/status :resolved}]})
          (is (empty? (filter #(= :heavy-eval (:class %))
                              (eff/eval-efficiency-warnings @ce :ag/he-edit nil {:main-db @conn :branch-edited ["seon.he.demo/f"]}))))
          (d/transact cr {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/he-ro :seon.agent.eval/turn 1
                                     :seon.agent.eval/handle 'result/e1 :seon.agent.eval/code "1" :seon.agent.eval/bytes 209715200}]})
          (is (empty? (filter #(= :heavy-eval (:class %)) (eff/eval-efficiency-warnings @cr :ag/he-ro))))
          (is (empty? (filter #(= :heavy-eval (:class %)) (eff/eval-efficiency-warnings @cr :ag/he-ro nil {:branch-edited []}))))
          (finally (d/release ce) (d/release cr)))))))

(deftest ^{:cost/ticks 80000000} format-merge-commit-message-and-seed42-gen-test
  (testing "format-merge-commit-message synthesizes Conventional Commits mechanically across fix/feat/docs/test/refactor"
    (with-test-store [{:keys [conn]}]
      (let [fid (random-uuid)
            _ (d/transact conn {:tx-data [{:seon.agent/id :agent/c1 :seon.agent/branch :branch/c1-fix
                                           :seon.agent/role :worker :seon.agent/task :seon.health/perf-fix}
                                          {:seon.task/id :seon.health/perf-fix
                                           :seon.task/title "Eliminate O(history) test scan in health"
                                           :seon.task/summary "Fetch top-2 live test eids via indexed eavt"}
                                          {:seon.fix/id fid :seon.fix/error-class :alloc-budget-overrun
                                           :seon.fix/symptom "Health OOMs" :seon.fix/root-cause "O(history) scan"
                                           :seon.fix/change-summary "Switch test-efficiency-warnings to O(|live-vars|)"
                                           :seon.fix/before-after "148ms/72MB -> 19ms/8MB"}]})
            fix-plan {:entity-diffs [{:entity [:seon.fix/id fid] :status :added :path nil}
                                     {:entity [:seon.code.var/name 'seon.health.efficiency/test-snapshot] :status :added :path "src/seon/health/efficiency.clj"}
                                     {:entity [:seon.code.var/name 'seon.health/caused-by-branch] :status :modified :path "src/seon/health.clj"}
                                     {:entity [:seon.schema/key :seon.demo/score] :status :added :path "resources/seon/schemas.edn"}]
                      :touched-paths #{"src/seon/health/efficiency.clj" "src/seon/health.clj" "resources/seon/schemas.edn"}}
            msg-fix (git/format-merge-commit-message @conn :branch/c1-fix fix-plan)
            subj-fix (first (str/split-lines msg-fix))
            msg-docs (git/format-merge-commit-message nil :branch/docs-edit
                       {:entity-diffs [{:entity [:seon.code.doc/id "docs/render.md#top"] :status :modified :path "docs/render.md"}]
                        :touched-paths #{"docs/render.md" "AGENTS.md"}})
            msg-test (git/format-merge-commit-message nil :branch/test-only
                       {:entity-diffs [{:entity [:seon.code.var/name 'seon.health-perf-test/my-test] :status :added :path "test/seon/health_perf_test.clj"}]
                        :touched-paths #{"test/seon/health_perf_test.clj"}})
            msg-long (git/render-merge-commit-message
                       {:branch :branch/long :task {:title (apply str (repeat 200 "x"))}})]
        (is (<= (count subj-fix) 72))
        (is (str/starts-with? subj-fix "fix(health):"))
        (is (str/includes? msg-fix "Branch: :branch/c1-fix"))
        (is (str/includes? msg-fix "agent :agent/c1"))
        (is (str/includes? msg-fix "Task: :seon.health/perf-fix — Eliminate O(history) test scan in health"))
        (is (str/includes? msg-fix "Summary: Fetch top-2 live test eids via indexed eavt"))
        (is (str/includes? msg-fix "Fix: :alloc-budget-overrun — 148ms/72MB -> 19ms/8MB"))
        (is (str/includes? msg-fix "Schemas: +1 (:seon.demo/score)"))
        (is (str/includes? msg-fix "Vars: +1 added, ~1 modified"))
        (is (str/includes? msg-fix "Files: 3 touched"))
        (is (str/starts-with? msg-docs "docs("))
        (is (str/includes? msg-docs "Docs: AGENTS.md, docs/render.md"))
        (is (str/starts-with? msg-test "test("))
        (is (<= (count (first (str/split-lines msg-long))) 72)))))
  (testing "render-merge-commit-message passes 100-sample seed=42 generative check"
    (let [res (schema/stress-var nil 'seon.git/render-merge-commit-message
                                 {:samples 100 :seed 42 :measure-fn code/measure-call})]
      (is (:ok? res) (pr-str res))
      (is (= 100 (:ran res))))))

(deftest merge-byte-array-and-derived-vector-retraction-test
  (testing "merge-one-entity compares byte[] by value and retracts derived :seon.vector/of without false conflicts"
    (let [b {:seon.media/id 1 :seon.vector/coarse (byte-array [1 2 3])}
          o {:seon.media/id 1 :seon.vector/coarse (byte-array [1 2 3])}
          r1 ((var seon.merge/merge-one-entity) [:seon.media/id 1] b o nil #{})
          r2 ((var seon.merge/merge-one-entity) [:seon.vector/of 99] {:seon.vector/of 99 :seon.vector/coarse (byte-array [1])} {:seon.vector/of 99 :seon.vector/coarse (byte-array [2])} nil #{})]
      (is (empty? (:conflicts r1)))
      (is (= [[:db/retractEntity [:seon.media/id 1]]] (:ops r1)))
      (is (empty? (:conflicts r2)))
      (is (= [[:db/retractEntity [:seon.vector/of 99]]] (:ops r2))))))