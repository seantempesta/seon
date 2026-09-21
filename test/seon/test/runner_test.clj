(ns seon.test.runner-test
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [seon.test.bounds :as bounds]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [sci.core :as sci]
            [seon.db :as db]
            [seon.config :as config]
            [seon.env :as env]
            [seon.fn :as program-fn]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.program :as program]
            [seon.test :as seon-test]
            [seon.test.arm :as arm]
            [seon.test.cache :as cache]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]
            [seon.test.runner :as runner]
            [seon.test-runner-failure-fixture]
            [seon.test-support :as test-support]))

(deftest selection-is-one-function-on-both-hosts
  (let [report-options (#'runner/report-options)]
   (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "both-hosts")
     (test-support/transacted! connection
       [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
         :seon.source/test-input-digest (id/digest 64 [:both-hosts :inputs])}])
     (let [ctx (test-support/fork-cluster-ctx connection)
           evaluation (sci.eval/evaluate
                       {:seon.sci.eval/ctx ctx
                        :seon.cluster.eval/source
                        "(clojure.test/deftest custody-observation (clojure.test/is (= \"both-hosts\" (:seon.cluster/name (seon.db/pull (seon.db/db) [:seon.cluster/name] [:seon.cluster/name \"both-hosts\"])))))"
                        :seon.cluster.eval/ns [:seon.ns/name 'seon.test.runner-test]
                        :seon.sci.admit/caps (config/result-caps config/defaults)
                        :seon.sci.eval/time-limit-ms 120000 :seon.config/on-core-error :panic})
           _ (when-not (:seon.program/row evaluation)
               (throw (ex-info "SCI test declaration did not produce a program row." evaluation)))
           declaration (program/declaration-row (seon.schema/handed-projection) (:seon.program/row evaluation) :all :agent)
           target (symbol "seon.test.runner-test" "custody-observation")]
       (is (map? declaration) (pr-str evaluation))
       (test-support/transacted! connection [declaration])
       (sci.eval/install-evaluated-rows!
        {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
         :seon.sci.eval/installations [{:seon.program/row declaration :seon.sci.eval/evaluation evaluation}]})
       (let [database (db/db connection)
             request {:seon.db/db database :seon.test.run/cluster [:seon.cluster/name "both-hosts"]
                      :seon.test/identities #{target}}
             host (seon-test/select request)
             interpreted (test-support/agent-value
                          ctx (str "(seon.test/select (assoc '" (pr-str (dissoc request :seon.db/db))
                                   " :seon.db/db (seon.db/db)))") 'seon.test.runner-test)
             admission (runner/worker-request-admission request)
             provenance (:seon.test.run/provenance admission)]
         (is (some #(= target (:seon.test/sym %)) (:seon.test.run/members host)) (pr-str host))
         (is (= host interpreted) (pr-str interpreted))
         (test-support/transacted! connection [[:db.fn/call seon-test/admit-run admission]])
         (let [executed (#'runner/run-task!
                         {:seon.test.runner/task-namespace "seon.test.runner-test"
                          :seon.test.runner/task-symbols [target]}
                         {::runner/report-options report-options
                          :seon.db/db database :seon.db/connection connection :seon.sci.eval/ctx ctx
                          :seon.schema/projection (db/carried-projection database)
                          :seon.test/class-loader (clojure.lang.RT/baseLoader)
                          :seon.db/custody-request {:seon.db/connection connection}})
               recorded (runner/commit-results!
                         connection {:seon.test.run/provenance provenance
                                     :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                                     :seon.test/run-at (:seon.test.run/at provenance)
                                     :seon.test.run/terminated? true
                                     :seon.test.runner/results (:seon.test.runner/task-results executed)})]
           (is (vector? recorded) (pr-str recorded))
           (is (= #{[1 0 0]}
                  (db/q '[:find ?pass ?fail ?error :in $ ?run-id ?symbol
                          :where [?run :seon.test.run/id ?run-id] [?run :seon.test.run/members ?member]
                                 [?member :seon.test.member/symbol ?symbol]
                                 [?member :seon.test.member/pass-count ?pass]
                                 [?member :seon.test.member/fail-count ?fail]
                                 [?member :seon.test.member/error-count ?error]]
                        (db/db connection) (:seon.test.run/id provenance) target))))))))))

(deftest a-cold-worker-does-not-arm-its-base-around-host-test-bodies
  (test-support/preserving-instrumentation-state
   (fn []
     (let [projection (#'runner/packaged-test-projection "arm-extent-regression")
           _base-init (#'runner/initialize-contracts!
                       "arm-extent-regression" [] projection)
           base (schema/call-with-projection
                 projection
                 #(deref (var-get #'test-support/database-base)))
           _base-acquired (sci.eval/acquire!
                           {:seon.sci.eval/ctx @(::test-support/sci-context base)
                            :seon.db/db (db/db (::test-support/connection base))})
           sizes-before (#'runner/sci-base-namespace-sizes
                         (var-get #'test-support/database-base))
           _selected-init (#'runner/initialize-contracts!
                           "arm-extent-regression"
                           ['seon.test.runner-test] projection)
           base-ctx @(::test-support/sci-context base)
           independent-ctx #(sci.eval/build-base-ctx projection)
           observed (atom [])
           thread-ids (atom [])
           probe-ns (create-ns (symbol (str "seon.worker-arm-probe." (id/id))))
           probe (intern probe-ns 'probe (fn []))]
       (try
         (alter-meta!
          probe assoc :test
          #(let [value (kernel/with-arm
                        (independent-ctx) 1000 (fn [_] :inside))]
             (swap! observed conj value)
             (swap! thread-ids conj (.threadId (Thread/currentThread)))
             (is (= :inside value))))
         (let [result (#'runner/run-resolved-tests!
                       {:seon.sci.eval/ctx base-ctx}
                       {::runner/task-symbols [(symbol probe)]}
                       [probe])]
           (is (= [:inside] @observed)
               "worker initialization must not leave its base armed across a host test body")
           (is (nil? (#'runner/ambient-drift
                      {::runner/snapshot-sci-base sizes-before}
                      {::runner/snapshot-sci-base
                       (#'runner/sci-base-namespace-sizes
                        (var-get #'test-support/database-base))}))
               "worker program acquisition precedes the test's drift snapshot")
           (is (= 0 (:seon.test/fail-count (first result))) (pr-str result))
           (is (= 0 (:seon.test/error-count (first result))) (pr-str result))
           (is (= :after
                  (kernel/with-arm (independent-ctx) 1000 (fn [_] :after)))
               "the pooled worker thread is unarmed after the body returns"))
         (let [exchange-ids ["serial/host-task-1" "serial/host-task-2"]
               input (str (apply str
                                 (map #(str (pr-str
                                             {::runner/worker-command :run
                                              ::runner/worker-task
                                              {::runner/task-symbols
                                               [(symbol probe)]}
                                              ::runner/exchange-id %})
                                            "\n")
                                      exchange-ids))
                          (pr-str {::runner/worker-command :stop
                                   ::runner/exchange-id "serial/stop"})
                          "\n")
               output (java.io.StringWriter.)
               executor (java.util.concurrent.Executors/newSingleThreadExecutor)]
           (try
             (with-redefs-fn
               {#'runner/reassert-contracts! (fn [_ _] nil)
                #'runner/ambient-snapshot (constantly {})
                #'runner/ambient-drift (fn [_ _] nil)
                #'runner/resolve-admitted-test
                (delay (fn [_] probe))}
               #(#'runner/serve-worker-commands!
                 "serial"
                 (java.io.BufferedReader. (java.io.StringReader. input))
                 (java.io.PrintWriter. output true)
                 {::runner/task-executor executor
                  ::runner/resolution {:seon.sci.eval/ctx base-ctx}}))
             (finally
               (.shutdownNow executor)))
           (let [prefix (var-get #'runner/protocol-prefix)
                 events (mapv #(edn/read-string (subs % (count prefix)))
                              (str/split-lines (str output)))
                 completed (filterv #(= :task-complete
                                         (::runner/worker-event %))
                                    events)]
             (is (= exchange-ids (mapv ::runner/exchange-id completed))
                 (pr-str events))
             (is (every? #(= "serial" (::runner/worker-id %)) completed)
                 (pr-str events))
             (is (every? #(nat-int? (::runner/task-elapsed-ms %)) completed)
                 (pr-str completed))
             (is (every? #(zero? (get-in % [::runner/task-summary
                                             ::runner/error-count]))
                         completed)
                 (pr-str completed))
             (is (apply = (take-last 2 @thread-ids))
                 "serial tasks reuse one execution thread, so thread-local leaks remain observable")))
         (let [exchange-id "serial/bounded-host-task"
               input (str (pr-str {::runner/worker-command :run
                                   ::runner/worker-task
                                   {::runner/task-symbols [(symbol probe)]}
                                   ::runner/exchange-id exchange-id})
                          "\n"
                          (pr-str {::runner/worker-command :stop
                                   ::runner/exchange-id "serial/bounded-stop"})
                          "\n")
               output (java.io.StringWriter.)
               executor (java.util.concurrent.Executors/newSingleThreadExecutor)]
           (try
             (with-redefs-fn
               {#'runner/reassert-contracts! (fn [_ _] nil)
                #'runner/ambient-snapshot (constantly {})
                #'runner/ambient-drift (fn [_ _] nil)
                #'runner/run-task! (fn [_ _] (Thread/sleep 5000))
                #'bounds/exchange-seconds (fn [_ _] 1)}
               #(#'runner/serve-worker-commands!
                 "serial"
                 (java.io.BufferedReader. (java.io.StringReader. input))
                 (java.io.PrintWriter. output true)
                 {::runner/task-executor executor
                  ::runner/resolution {:seon.sci.eval/ctx base-ctx}}))
             (finally
               (.shutdownNow executor)))
           (let [prefix (var-get #'runner/protocol-prefix)
                 completed (->> (str/split-lines (str output))
                                (map #(edn/read-string (subs % (count prefix))))
                                (filter #(= :task-complete
                                            (::runner/worker-event %)))
                                first)]
             (is (= exchange-id (::runner/exchange-id completed)) (pr-str completed))
             (is (true? (::runner/worker-task-bound completed)) (pr-str completed))
             (is (pos? (::runner/task-elapsed-ms completed)) (pr-str completed))))
         ;; The worker's own bound only produces a terminal event if it fires
         ;; while the coordinator is still listening. Ordinary and declared-long
         ;; tasks both have to sit strictly inside the exchange bound.
         (doseq [task [{::runner/task-symbols ['ordinary]}
                       {::runner/task-symbols ['declared-long]
                        ::runner/task-long-ms 900000}]]
           (is (< (bounds/exchange-seconds (or (::runner/task-long-ms task) 0) 0)
                  (#'runner/task-exchange-bound-seconds task))
               (str "the worker task bound must fire inside the coordinator exchange bound: "
                    (pr-str task))))
         (finally
           (remove-ns (ns-name probe-ns))))))))

(deftest no-double-execution
  (let [host-projection (schema/handed-projection)]
   (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "claim-authority")
     (test-support/transacted! connection
       [{:seon.source/digest (or (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
                                 (id/digest 64 [:fixture :program]))
         :seon.source/test-input-digest (id/digest 64 [:claims :inputs])}])
     (let [child (.start (ProcessBuilder. ["cat"]))
           release (fn [process]
                     (.destroy ^Process process)
                     (.get (.onExit ^Process process) test-support/event-backstop-seconds
                           java.util.concurrent.TimeUnit/SECONDS))]
       (with-open [owned (test-support/closeable child release)]
         (let [process-row (fn [^java.lang.ProcessHandle handle]
                             (let [pid (.pid handle)
                                   start (java.util.Date/from (.get (.startInstant (.info handle))))]
                               {:seon.db.process/id (id/id [pid start])
                                :seon.db.process/pid pid :seon.db.process/start-instant start}))
               child-row (process-row (.toHandle ^Process @owned))
               parent-row (process-row (java.lang.ProcessHandle/current))
               _ (test-support/transacted! connection [child-row parent-row])
               database (db/db connection)
               run (runner/provenance database)
               other-run (assoc run :seon.test.run/id (id/id))
               first-symbol 'seon.test-cache-test/resolved-classpath-preserves-order-and-rebases-only-checkout-roots
               symbols [first-symbol 'seon.test-runner-failure-fixture/passing-example
                        'seon.test-runner-failure-fixture/failing-example
                        'seon.test-runner-failure-fixture/repeated-identical-error]
               deadline (java.util.Date. (+ (System/currentTimeMillis)
                                           (* 1000 test-support/event-backstop-seconds)))
               admission {:seon.test.run/provenance run
                          :seon.test.run/cluster [:seon.cluster/name "claim-authority"]
                          :seon.test.run/input-digest (id/digest 64 [:claims :inputs])
                          :seon.test.run/policy :named :seon.test.run/include-long? false
                          :seon.test.run/deadline deadline
                          :seon.test.run/members
                          (mapv #(hash-map :seon.test.member/symbol %
                                           :seon.test.member/reasons #{:named}) symbols)}
               _ (test-support/transacted!
                  connection [[:db.fn/call seon-test/admit-run admission]
                              [:db.fn/call seon-test/admit-run
                               (assoc admission :seon.test.run/provenance other-run)]])
               claim-request (fn [provenance process]
                               (merge (select-keys process [:seon.db.process/pid :seon.db.process/start-instant])
                                      {:seon.test.run/id (:seon.test.run/id provenance)
                                       :seon.test.member/worker [:seon.db.process/id (:seon.db.process/id process)]
                                       :seon.test.member/claimed-at (java.util.Date.)
                                       :seon.test.member/host :seon.test.host/isolated-snapshot
                                       :seon.test.run/deadline deadline}))
               claim! #(test-support/transacted! connection [[:db.fn/call runner/claim-member %]])
               first-claim (claim! (claim-request run child-row))
               second-claim (claim! (claim-request other-run parent-row))
               claim-t (fn [report]
                         (first (keep #(when (= :seon.test.member/claim-tx (:a %)) (:v %)) (:tx-data report))))
               completion (fn [provenance process claim results terminated?]
                            {:seon.test.run/provenance provenance
                             :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                             :seon.test/run-at (:seon.test.run/at provenance)
                             :seon.test.member/worker [:seon.db.process/id (:seon.db.process/id process)]
                             :seon.test.member/claim-tx claim
                             :seon.test.run/terminated? terminated?
                             :seon.test.runner/results results})
               second-results (schema/call-with-projection
                               host-projection
                               #(runner/run-vars! (mapv requiring-resolve (rest symbols)) {}))
               second-completion (completion other-run parent-row (claim-t second-claim) second-results false)]
           (is (= 1 (count (filter #(= :seon.test.member/claim-tx (:a %)) (:tx-data first-claim)))))
           (is (= 3 (count (filter #(= :seon.test.member/claim-tx (:a %)) (:tx-data second-claim)))))
           (test-support/transacted! connection [[:db.fn/retractEntity [:seon.test/sym (nth symbols 2)]]])
           (let [recorded (runner/commit-results! connection second-completion)]
             (is (vector? recorded) (pr-str recorded)))
           (is (nil? (:db/id (db/pull (db/db connection) [:db/id] [:seon.test/sym (nth symbols 2)])))
               "Completion never recreates a deleted program row.")
           (is (= 2 (db/q '[:find (count-distinct ?report) . :in $ ?id [?membership ...]
                            :where [?run :seon.test.run/id ?id]
                                   [?run ?membership ?member]
                                   [?member :seon.test.member/failures ?report]]
                          (db/db connection) (:seon.test.run/id other-run)
                          [:seon.test.run/members :seon.test.run/covered-by]))
               "Seven identical errors share one report; passes have no reports.")
           (is (= :seon.test/claim-conflict
                  (:seon.test/execution-refusal (db/transact! connection [[:db.fn/call runner/claim-member
                                                              (claim-request run parent-row)]])))
               "Recorded counts without termination do not release the process.")
           (is (vector? (runner/commit-results! connection (assoc second-completion :seon.test.run/terminated? true))))
           (is (= :seon.test/report-conflict
                  (:seon.test/execution-refusal
                   (runner/commit-results!
                    connection (assoc-in second-completion
                                         [:seon.test.runner/results 1 :seon.test.failure/reports 0 :seon.test.failure/actual]
                                         "different content with the same captured signature")))))
           (release child)
           (let [reclaimed (claim! (assoc (claim-request other-run parent-row)
                                          :seon.test.run/dead-workers
                                          [(select-keys child-row [:seon.db.process/pid :seon.db.process/start-instant])]))
                 result (schema/call-with-projection
                         host-projection #(runner/run-var! (requiring-resolve first-symbol)))
                 late (completion run child-row (claim-t first-claim) [result] true)
                 accepted (completion other-run parent-row (claim-t reclaimed) [result] true)]
             (is (not= (claim-t first-claim) (claim-t reclaimed)))
             (is (= :seon.test/claim-replaced (:seon.test/execution-refusal (runner/commit-results! connection late))))
             (is (vector? (runner/commit-results! connection accepted)))
             (let [replay (test-support/transacted! connection [[:db.fn/call runner/record-tx accepted]])]
               (is (empty? (filter #(contains? #{"seon.test.member" "seon.test.report"} (namespace (:a %)))
                                   (:tx-data replay)))))
             (is (= :seon.test.run/immutable
                    (:seon.test/execution-refusal
                     (runner/commit-results! connection
                                             (update-in accepted [:seon.test.runner/results 0 :seon.test/pass-count] inc)))))
             (is (empty? (filter #(= :seon.test.member/claim-tx (:a %))
                                 (:tx-data (claim! (claim-request run parent-row))))))
             (is (= 4 (db/q '[:find (count-distinct ?member) . :in $ ?id [?membership ...]
                             :where [?run :seon.test.run/id ?id]
                                    [?run ?membership ?member]
                                    [?member :seon.test.member/completed-tx]]
                            (db/db connection) (:seon.test.run/id other-run)
                            [:seon.test.run/members :seon.test.run/covered-by])))))))))))

(deftest platform-claims-and-original-bounds-govern-bulk
  (let [host-projection (schema/handed-projection)]
   (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "platform-claim")
     (test-support/transacted! connection
       [{:seon.source/digest (or (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
                                 (id/digest 64 [:fixture :program]))
         :seon.source/test-input-digest (id/digest 64 [:platform :inputs])}])
     (let [child (.start (ProcessBuilder. ["cat"]))]
       (with-open [owned (test-support/closeable
                          child #(do (.destroy ^Process %)
                                     (.get (.onExit ^Process %) test-support/event-backstop-seconds
                                           java.util.concurrent.TimeUnit/SECONDS)))]
         (let [row (fn [^java.lang.ProcessHandle process]
                     (let [pid (.pid process)
                           start (java.util.Date/from (.get (.startInstant (.info process))))]
                       {:seon.db.process/id (id/id [pid start])
                        :seon.db.process/pid pid :seon.db.process/start-instant start}))
               parent (row (java.lang.ProcessHandle/current))
               other (row (.toHandle ^Process @owned))
               _ (test-support/transacted! connection [parent other])
               run (runner/provenance (db/db connection))
               deadline (java.util.Date. (+ (System/currentTimeMillis) (* 1000 test-support/event-backstop-seconds)))
               platform 'seon.test-runner-failure-fixture/failing-example
               bulk 'seon.test-cache-test/resolved-classpath-preserves-order-and-rebases-only-checkout-roots
               admission {:seon.test.run/provenance run
                          :seon.test.run/cluster [:seon.cluster/name "platform-claim"]
                          :seon.test.run/input-digest (id/digest 64 [:platform :inputs])
                          :seon.test.run/policy :named :seon.test.run/include-long? false
                          :seon.test.run/deadline deadline
                          :seon.test.run/members [{:seon.test.member/symbol platform :seon.test.member/reasons #{:platform}}
                                                  {:seon.test.member/symbol bulk :seon.test.member/reasons #{:named}}]}
               _ (test-support/transacted! connection [[:db.fn/call seon-test/admit-run admission]])
               other-run (runner/provenance (db/db connection))
               _ (test-support/transacted!
                  connection [[:db.fn/call seon-test/admit-run
                               (assoc admission :seon.test.run/provenance other-run)]])
               request (fn [provenance process]
                         (merge (select-keys process [:seon.db.process/pid :seon.db.process/start-instant])
                                {:seon.test.run/id (:seon.test.run/id provenance)
                                 :seon.test.member/worker [:seon.db.process/id (:seon.db.process/id process)]
                                 :seon.test.member/claimed-at (java.util.Date.)
                                 :seon.test.member/host :seon.test.host/in-process
                                 :seon.test.run/deadline deadline}))
               claim (test-support/transacted! connection [[:db.fn/call runner/claim-member (request run parent)]])
               claim-t (first (keep #(when (= :seon.test.member/claim-tx (:a %)) (:v %)) (:tx-data claim)))
               refused #(db/transact! connection [[:db.fn/call runner/claim-member %]])]
           (is (= #{platform}
                  (set (db/q '[:find [?symbol ...] :in $ ?tx
                               :where [?member :seon.test.member/claim-tx ?tx]
                                      [?member :seon.test.member/symbol ?symbol]] (:db-after claim) claim-t))))
           (is (= :seon.test/claim-conflict (:seon.test/execution-refusal (refused (request run other))))
               "A different worker cannot claim bulk while platform is pending.")
           (is (= :seon.test/claim-conflict (:seon.test/execution-refusal (refused (request other-run parent))))
               "The same JVM cannot overlap groups across runs in this authority.")
           (is (= :seon.test/process-state-unknown
                  (:seon.test/execution-refusal (refused (update (request run other) :seon.db.process/pid inc)))))
           (is (= :seon.test.runner/worker-exchange-bound
                  (:seon.test/execution-refusal (refused (assoc (request run other) :seon.test.member/claimed-at deadline)))))
           (is (= :seon.test.runner/worker-exchange-bound
                  (:seon.test/execution-refusal (refused (assoc (request run other) :seon.test.run/deadline
                                                  (java.util.Date. (inc (inst-ms deadline))))))))
           (let [result (schema/call-with-projection
                         host-projection #(runner/run-var! (requiring-resolve platform)))
                 recorded (runner/commit-results!
                           connection {:seon.test.run/provenance run
                                       :seon.test/run-basis-t (:seon.test.run/basis-t run)
                                       :seon.test/run-at (:seon.test.run/at run)
                                       :seon.test.member/worker [:seon.db.process/id (:seon.db.process/id parent)]
                                       :seon.test.member/claim-tx claim-t
                                       :seon.test.run/terminated? true
                                       :seon.test.runner/results [result]})]
             (is (= 1 (:seon.test/fail-count (first recorded))))
             (is (empty? (filter #(= :seon.test.member/claim-tx (:a %))
                                 (:tx-data (test-support/transacted!
                                            connection [[:db.fn/call runner/claim-member (request run other)]])))))
             (is (= 1 (db/q '[:find (count ?member) . :in $ ?id
                             :where [?run :seon.test.run/id ?id]
                                    [?run :seon.test.run/members ?member]
                                    [?member :seon.test.member/completed-tx]]
                            (db/db connection) (:seon.test.run/id run))))))))))))

(deftest ^{:seon.test/fixture-observation
           "Verifies refusal before published-root/fresh-store acquisition and graph selection; no expensive fixture is acquired."}
  expensive-fixtures-require-a-declared-observation
  (let [root (doto (io/file "tmp" (str "fixture-reason-" (id/id))) .mkdirs)
           file (io/file root "declarations.clj")
           namespace-name (symbol (str "seon.fixture.reason-" (id/id)))
           reason "Observes store-global blob deletion, which a branch cannot isolate."
           support (program-fn/build-artifact
                    {:seon.fn/source-path "test/seon/test_support.clj"
                     :seon.fn.file/first-party-functions []})
           known (vec (keep :seon.fn/sym (:seon.fn.file/rows support)))]
       (try
         (spit file
               (str "(ns " namespace-name
                    " (:require [clojure.test :refer [deftest]] [seon.test-support :as support]))\n"
                    "(deftest unreasoned (support/populate-published-root! \"unused\"))\n"
                    "(deftest ^{:seon.test/fixture-observation " (pr-str reason)
                    "} reasoned (support/populate-published-root! \"unused\"))\n"
                    "(deftest ordinary (support/with-database (fn [_] nil)))\n"
                    "(deftest fresh (support/with-database {:seon.test-support/fresh-store? true} (fn [_] nil)))\n"))
         (load-file (str file))
         (let [manifest
               {:seon.fn.manifest/artifacts
                [support (program-fn/build-artifact
                          {:seon.fn/source-path (str file)
                           :seon.fn.file/first-party-functions known})]}
               selected #(vector (ns-resolve namespace-name %))
               expensive (#'runner/expensive-fixture-tests manifest)]
           (is (contains? expensive (symbol (str namespace-name) "unreasoned")))
           (is (contains? expensive (symbol (str namespace-name) "reasoned")))
           (is (contains? expensive (symbol (str namespace-name) "fresh")))
           (is (not (contains? expensive (symbol (str namespace-name) "ordinary"))))
           (is (thrown? clojure.lang.ExceptionInfo
                        (#'runner/verify-fixture-observations! manifest (selected 'unreasoned))))
           (let [refusal (try
                           (#'runner/verify-fixture-observations!
                            manifest (mapv #(ns-resolve namespace-name %)
                                           '[unreasoned reasoned ordinary fresh unreasoned]))
                           (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
                 expected [(symbol (str namespace-name) "fresh")
                           (symbol (str namespace-name) "unreasoned")]]
             (is (= expected (:seon.test/syms refusal)))
             (is (= expected (:seon.test/syms refusal))
                 "One refusal names every undeclared test, sorted and without duplicates.")
             (is (= (first expected) (:seon.test/sym refusal))))
           (is (nil? (#'runner/verify-fixture-observations! manifest (selected 'reasoned))))
           (is (nil? (#'runner/verify-fixture-observations! manifest (selected 'ordinary))))
           (is (thrown? clojure.lang.ExceptionInfo
                        (#'runner/verify-fixture-observations! manifest (selected 'fresh)))))
         (let [unscoped (fn [f] (binding [test/*testing-vars* []] (f)))]
           (is (thrown? clojure.lang.ExceptionInfo
                        (unscoped #(test-support/populate-published-root!
                                    (str (io/file root "refused"))))))
           (is (not (.exists (io/file root "refused"))))
           (is (thrown? clojure.lang.ExceptionInfo
                        (unscoped #(test-support/with-database
                                    {:seon.test-support/fresh-store? true} (fn [_] nil)))))
           (is (= reason (unscoped #(runner/fixture-observation!
                                     'seon.test-support/with-fresh-database
                                     {:seon.test/fixture-observation reason}))))
           (is (thrown? clojure.lang.ExceptionInfo
                        (unscoped #(runner/fixture-observation!
                                    'seon.test-support/with-fresh-database
                                    {:seon.test/fixture-observation "   "})))))
         (finally
           (remove-ns namespace-name)
           (test-support/delete-recursively! root)))))

(deftest the-canonical-platform-tier-preserves-file-local-uncertainty
  (test-support/with-database
    (fn [connection]
      (let [manifest @test-support/source-manifest
            declarations (#'runner/platform-declarations manifest)
            platform-vars (mapv #(requiring-resolve (symbol %)) (keys declarations))
            report (seon-test/host
                    (db/db connection)
                    'seon.cluster.registry-test/a-concurrent-create-wave-loses-nothing)]
        (is (seq platform-vars) "An absent platform tier is not a proof.")
        (is (= :seon.test.host/in-process (:seon.test/host report)) (pr-str report))
        (is (nil? (#'runner/verify-platform-tier-carries-no-destructive-drill!
                   manifest platform-vars)))
        (doseq [test-symbol '[seon.dev.fresh-operator-reset-test/source-syntax-refuses-before-lock-or-destruction
                             seon.dev.fresh-operator-reset-test/managed-root-cleanup-loads-no-program-and-never-follows-symlinks]]
          (let [test-var (requiring-resolve test-symbol)
                refusal (try
                          (#'runner/verify-platform-tier-carries-no-destructive-drill!
                           manifest [test-var])
                          (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
            (is (nil? (get declarations test-symbol)))
            (is (= [{:seon.test/sym test-symbol
                     :seon.test.runner/destructive-path
                     [test-symbol 'seon.operator.state/cleanup-root-under-lock!]}]
                   (:seon.test.runner/destructive-platform-tests refusal))
                "Scratch custody does not exempt a declared destroyer from the first tier.")))
        (println "Canonical platform destroyer check:" (count platform-vars) "tests admitted")))))

(deftest ^{:seon.test/fixture-observation
           "Observes platform refusal from analyzed destroyer declarations and generated test source; no expensive fixture is acquired. Run: bin/test seon.test.runner-test"}
  the-platform-tier-declares-no-destructive-drill
  ;; The platform tier runs FIRST on every bin/test invocation. A test there
  ;; that deletes a filesystem path deletes before the run has produced any
  ;; evidence — on 2026-09-17 that emptied the development store
  ;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).
  ;; The owners are RESOLVED against the program graph and the reach is
  ;; derived from :seon.fn/calls, so neither a rename nor metadata drift can
  ;; leave the checker walking to nothing and reporting the tier healthy.
  (let [root (doto (io/file "tmp" (str "destructive-tier-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.destructive-" (id/id)))
        support (program-fn/build-artifact
                 {:seon.fn/source-path "test/seon/test_support.clj"
                  :seon.fn.file/first-party-functions []})
        operator (program-fn/build-artifact
                  {:seon.fn/source-path "src/seon/operator.clj"
                   :seon.fn.file/first-party-functions []})
        known (vec (keep :seon.fn/sym (concat (:seon.fn.file/rows support)
                                              (:seon.fn.file/rows operator))))]
    (try
      (spit file
            (str "(ns " namespace-name
                 " (:require [clojure.test :refer [deftest]] [seon.test-support :as support]))\n"
                 "(defn- indirect [] (support/populate-published-root! \"unused\"))\n"
                 "(deftest ^{:seon.test/platform \"probe\"} drill (indirect))\n"
                 "(deftest ^{:seon.test/platform \"probe\"} ordinary"
                 " (support/with-database (fn [_] nil)))\n"))
      (load-file (str file))
      (let [manifest {:seon.fn.manifest/artifacts
                      [support operator
                       (program-fn/build-artifact
                        {:seon.fn/source-path (str file)
                         :seon.fn.file/first-party-functions known})]}
            selected #(vector (ns-resolve namespace-name %))
            rows (#'runner/manifest-rows manifest)]
        (let [owner-rows (#'runner/destructive-owner-rows rows)
              owners (into {} (map (juxt :seon.fn/sym :seon.fn/destroys)) owner-rows)]
          (is (seq owner-rows) "the analyzed source declares its destructive owners")
          (is (every? #(and (string? %) (seq %)) (vals owners))
              "every owner says what it destroys")
          (is (contains? owners 'seon.test-support/populate-published-root!)
              "the declaration at the definition is admitted as a program fact")
          (is (str/includes? (get owners 'seon.test-support/populate-published-root! "")
                             "store")
              (pr-str owners)))
        (let [drifted (mapv #(dissoc % :seon.fn/destroys) rows)
              refusal (try (#'runner/destructive-owner-rows drifted)
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))]
          (is (some? refusal)
              "a program declaring nothing refuses instead of reporting the tier clean")
          (is (= :seon.fn/destroys
                 (:seon.test.runner/destructive-owner-attribute
                  (ex-data refusal)))))
        (let [refusal (try (#'runner/verify-platform-tier-carries-no-destructive-drill!
                            manifest (selected 'drill))
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))
              offender (first (:seon.test.runner/destructive-platform-tests
                               (ex-data refusal)))]
          (is (some? refusal) "a platform test reaching a destructive owner refuses")
          (is (= (symbol (str namespace-name) "drill") (:seon.test/sym offender)))
          (is (= [(symbol (str namespace-name) "drill")
                  (symbol (str namespace-name) "indirect")
                  'seon.test-support/populate-published-root!]
                 (:seon.test.runner/destructive-path offender))
              "the refusal carries the call path to the owner")
          (is (str/includes? (ex-message refusal) (str namespace-name "/drill"))))
        (is (nil? (#'runner/verify-platform-tier-carries-no-destructive-drill!
                   manifest (selected 'ordinary)))
            "an ordinary platform test is admitted"))
      (finally
        (remove-ns namespace-name)
        (test-support/delete-recursively! root)))))

(deftest assertion-report-uses-bounded-value-renderer
  (let [ctx (sci/init {:namespaces
                       {'large.fixture
                        (into {} (map (fn [n] [(symbol (str "value" n)) n]))
                              (range 2000))}})
        options {:seon.print/length 4 :seon.print/level 3
                 :seon.render/profile
                 {:seon.render.profile/id ::assertion
                  :seon.render.profile/token-budget 64
                  :seon.render.profile/max-depth 3
                  :seon.render.profile/max-children 3
                  :seon.render.profile/max-string-length 40
                  :seon.render.profile/composition :single-line}}
        event {:type :fail :file "runner_test.clj" :line 99
               :var #'assertion-report-uses-bounded-value-renderer
               :expected :small :actual ctx}
        capture (atom {::runner/order [] ::runner/results {}})
        counters (ref test/*initial-report-counters*)
        output
        (with-out-str
          (binding [test/*test-out* *out*
                    test/*report-counters* counters
                    test/*testing-vars* [#'assertion-report-uses-bounded-value-renderer]]
            (#'runner/capture-and-report-event!
             options capture #{'seon.test.runner-test}
             (fn [_] (throw (ex-info "Raw assertion reporter bypass" {})))
             (atom #{}) event)))
        result (first (#'runner/captured-results @capture))]
    (is (= 1 (:fail @counters)))
    (is (= 1 (:seon.test/fail-count result)))
    (is (= [(#'runner/failure-identity options
             'seon.test.runner-test/assertion-report-uses-bounded-value-renderer event)]
           (:seon.test/failing-assertions result)))
    (is (str/includes? output "assertion-report-uses-bounded-value-renderer"))
    (is (str/includes? output "runner_test.clj:99"))
    (is (str/includes? output ":seon.print/omitted"))
    (is (str/includes? output (#'runner/report-value options ctx)))
    (is (< (count output) 1500) "The small supplied profile bounds the SCI world.")
    (is (not (str/includes? output "value1999")))))

(deftest nested-runs-own-their-counters-and-evidence
  (let [namespace-name (symbol (str "seon.nested-report-probe." (id/id)))
        namespace-object (create-ns namespace-name)
        counters (ref test/*initial-report-counters*)
        events (atom [])
        contexts (atom [])
        nested (atom [])
        output (java.io.StringWriter.)
        declare-test (fn [test-name body]
                       (let [v (intern namespace-object test-name (fn []))]
                         (alter-meta! v assoc :test body)
                         v))]
    (try
      (let [children
            [(declare-test 'green #(is true))
             (declare-test 'red #(is false "expected nested red"))
             (declare-test 'interrupted
                           #(throw (InterruptedException. "expected nested interruption")))]
            outer
            (declare-test
             'outer
             (fn []
               (doseq [[v expected] (map vector children
                                        [[1 0 0] [0 1 0] [0 0 1]])]
                 (let [result (runner/run-var! v)]
                   (swap! nested conj result)
                   (swap! contexts conj (mapv :name (map meta test/*testing-vars*)))
                   (is (= expected (mapv result [:seon.test/pass-count
                                                 :seon.test/fail-count
                                                 :seon.test/error-count])))))))
            result
            (binding [test/*report-counters* counters
                      test/*testing-vars* [#'nested-runs-own-their-counters-and-evidence]
                      test/*testing-contexts* ["enclosing gate"]
                      test/*test-out* output
                      test/report #(swap! events conj %)]
              (runner/run-var! outer))]
        (is (= test/*initial-report-counters* @counters))
        (is (empty? @events) "No nested event reaches the enclosing reporter.")
        (is (= [['outer] ['outer] ['outer]] @contexts))
        (is (= (symbol (str namespace-name) "outer") (:seon.test/sym result)))
        (is (= [3 0 0] (mapv result [:seon.test/pass-count
                                     :seon.test/fail-count :seon.test/error-count])))
        (is (nil? (:seon.test/failing-assertions result)))
        (is (= [0 1 1] (mapv #(count (:seon.test/failing-assertions %)) @nested)))
        (is (str/includes? (:seon.test/failure-message (second @nested))
                           "expected nested red"))
        (is (str/includes? (:seon.test/failure-message (last @nested))
                           "expected nested interruption"))
        (is (not (str/includes? (str output) "enclosing gate"))))
      (finally (remove-ns namespace-name)))))

(deftest unused-workers-own-no-checkout
  (let [root (doto (io/file "tmp" (str "unused-workers-" (random-uuid))) .mkdirs)
        snapshot (io/file root "snapshot")
        serial (io/file root "serial")
        confirmation (io/file root "confirmation")
        admitted (delay
                   (cache/worker-checkout! (str snapshot) (str serial))
                   {::runner/worker-id "serial"})
        task {::runner/task-id "admitted"}]
    (try
      (.mkdirs (io/file snapshot "src"))
      (spit (io/file snapshot "src" "identity.clj") "immutable snapshot bytes")
      (is (= [] (#'runner/run-task-pool! nil [] admitted [] [])))
      (is (not (realized? admitted)))
      (is (not (.exists serial)))
      (is (not (.exists confirmation)))
      (with-redefs-fn
        {#'runner/stop-worker! (fn [_] nil)
         #'runner/execute-worker-task! (fn [_ worker admitted-task]
                                        (assoc admitted-task ::runner/executed-by
                                               (::runner/worker-id worker)))}
        #(is (= [(assoc task ::runner/executed-by "serial")]
                (#'runner/run-task-pool! nil [] admitted [] [task]))))
      (is (= "immutable snapshot bytes"
             (slurp (io/file serial "src" "identity.clj"))))
      (is (not (.exists confirmation)))
      (finally (test-support/delete-recursively! root)))))

(deftest default-red-does-not-launch-confirmation
  (test-support/with-database
   (fn [connection]
    (let [database (db/db connection)
          ctx (sci.eval/cluster-ctx database connection)
          _ (is (nil? (:seon.db/db (sci.eval/acquired-program ctx))))
          resolution {:seon.db/db database :seon.db/connection connection
                      :seon.sci.eval/ctx ctx
                      :seon.schema/projection (schema/projection-from-database database)
                      :seon.test/class-loader (clojure.lang.RT/baseLoader)}
          task {::runner/task-id "default-red"
              ::runner/task-ordinal 0
              ::runner/task-namespace "seon.test-runner-failure-fixture"
              ::runner/task-symbols ['seon.test-runner-failure-fixture/failing-example]}
        red (assoc (#'runner/run-task! task resolution) ::runner/executed-by "pool-1")
        launches (atom 0)
        outcome (atom nil)]
    (with-out-str
      (with-redefs-fn
        {#'runner/confirmation-symbols (constantly #{})
         #'runner/run-task-pool! (fn [& _] [red])
         #'runner/confirm-parallel-failure! (fn [& _] (swap! launches inc))}
        #(reset! outcome
                 (#'runner/run-parallel-stage!
                  [] nil {:seon.fn.manifest/artifacts []} (atom []) [task]))))
    (is (= (runner/program-digest database)
           (runner/program-digest (:seon.db/db (sci.eval/acquired-program ctx)))))
    (is (= 0 (get-in red [::runner/task-summary ::runner/error-count])))
    (is (= 1 (get-in red [::runner/task-summary ::runner/test-count])))
    (is (= 0 @launches))
    (is (= [red] (::runner/task-results @outcome)))
    (is (= 1 (get-in @outcome [::runner/task-summary ::runner/fail-count])))
    (is (= "pool-1" (::runner/executed-by red)))
    (is (= 1 (count (:seon.test/failing-assertions
                    (first (::runner/task-results red))))))
    (is (str/includes? (::runner/task-output red) "deliberate broken-test evidence"))
    (is (= [#'seon.test-runner-failure-fixture/failing-example]
           (#'runner/confirmation-vars
            [#'seon.test-runner-failure-fixture/passing-example
             #'seon.test-runner-failure-fixture/failing-example]
            #{'seon.test-runner-failure-fixture/failing-example})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'runner/confirmation-vars [] #{'missing/test})))))))

(deftest initialization-acquires-one-projection
  ;; TWO call shapes, ONE acquisition between them. `arm/initialize-contracts!`
  ;; is the acquiring seam: its two-argument arity asks
  ;; `packaged-test-projection` exactly once and carries that same value
  ;; through loading, arming and its returned arming value. The runner's own
  ;; wrapper is the worker's shape — a worker already holds the projection its
  ;; arming acquired, so it HANDS that value in and the seam acquires nothing.
  ;; A second acquisition on either path would arm against a projection no
  ;; caller holds (AGENTS §2.1).
  (test-support/preserving-instrumentation-state
   (fn []
     (let [acquire @#'arm/packaged-test-projection
           acquisitions (atom [])
           counting
           (fn [body]
             (with-redefs-fn
               {#'arm/packaged-test-projection
                (fn [role]
                  (let [projection (acquire role)]
                    (swap! acquisitions conj projection)
                    projection))}
               body))
           prove-armed!
           (fn [label]
             (let [program (#'arm/declared-program-namespaces)
                   armable (instrument/armable program)]
               (is (seq armable) "An absent program cannot prove complete arming.")
               (is (empty? (set/difference armable (instrument/instrumented)))
                   (str label ": every armable program Var carries its real "
                        "contract wrapper."))))
           acquired
           (counting
            #(arm/initialize-contracts! "one-projection" ['seon.test.runner-test]))]
       (is (= 1 (count @acquisitions))
           "the acquiring arity asks for exactly one projection")
       (is (identical? (first @acquisitions)
                       (:seon.test.runner/projection acquired))
           "and hands that same value back in its arming value")
       (prove-armed! "arm/initialize-contracts!")
       (let [handed (:seon.test.runner/projection acquired)
             worker (counting
                     #(#'runner/initialize-contracts!
                       "one-projection" ['seon.test.runner-test] handed))]
         (is (= 1 (count @acquisitions))
             "the worker shape acquires nothing of its own")
         (is (identical? handed (:seon.test.runner/projection worker))
             "it arms against the projection its caller already holds")
         (prove-armed! "runner/initialize-contracts!"))))))

(deftest executor-submissions-carry-the-callers-handed-projection
  ;; `on-caller-loader` pinned the submitting thread's CLASSLOADER and
  ;; conveyed nothing else, so any runner work that hopped to an executor
  ;; thread ran with no handed projection at all: seon.db then reported
  ;; Datahike's base attributes as the only registered candidates. The
  ;; class this kills is "a wrapper that conveys one part of the caller's
  ;; frame" — the pinned loader without the bindings that came with it.
  (test-support/with-database
   (fn [_]
     (let [handed (schema/handed-projection)
           executor (java.util.concurrent.Executors/newSingleThreadExecutor)
           observed (promise)]
       (is (some? handed) "The fixture hands its projection to this thread.")
       (try
         (.execute executor
                   ^Runnable (#'runner/on-caller-loader
                              (fn [] (deliver observed (schema/handed-projection)))))
         (is (identical? handed
                         (deref observed
                                (long (* 1000 test-support/event-backstop-seconds))
                                ::never-arrived)))
         (finally (.shutdownNow executor)))))))

(deftest the-exchange-bound-derives-from-the-long-declaration
  ;; §2.3: a bound that ignores the declaration is a tuned constant standing
  ;; in for an observable event. A `:seon.test/long` test says it runs past
  ;; the ordinary per-exchange bound; `:seon.test/long-ms` says how long, so
  ;; the bound derives from the declaration instead of expiring the test the
  ;; program already admitted would take longer.
  (let [root (doto (io/file "tmp" (str "long-bound-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.long-bound-" (id/id)))
        reason "Boots two co-hosted clusters against a real store."
        plain-reason "Forks a published root once."
        default-bound (#'runner/exchange-bound-seconds)
        allowance-seconds (inc default-bound)
        allowance-ms (* 1000 allowance-seconds)
        priming-seconds (quot (+ bounds/fixture-priming-ms 999) 1000)
        support (program-fn/build-artifact
                 {:seon.fn/source-path "test/seon/test_support.clj"
                  :seon.fn.file/first-party-functions []})
        known (vec (keep :seon.fn/sym (:seon.fn.file/rows support)))]
    (try
      (spit file
            (str "(ns " namespace-name
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest ^{:seon.test/long " (pr-str reason)
                 " :seon.test/long-ms " allowance-ms "} allowed (is true))\n"
                 "(deftest ^{:seon.test/long " (pr-str plain-reason)
                 "} declared (is true))\n"
                 "(deftest ordinary (is true))\n"))
      (load-file (str file))
      (let [manifest {:seon.fn.manifest/artifacts
                      [support (program-fn/build-artifact
                                {:seon.fn/source-path (str file)
                                 :seon.fn.file/first-party-functions known})]}
            declarations (#'runner/long-declarations manifest)
            all-vars (mapv #(ns-resolve namespace-name %)
                           '[allowed declared ordinary])
            task-for (fn [name-symbol]
                       (first (#'runner/test-tasks
                               all-vars [(ns-resolve namespace-name name-symbol)]
                               declarations)))]
        (is (= {(symbol (str namespace-name) "allowed")
                {:seon.test/long reason :seon.test/long-ms allowance-ms}
                (symbol (str namespace-name) "declared")
                {:seon.test/long plain-reason}}
               declarations)
            "both halves of the declaration are lifted onto the program row")
        (let [task (task-for 'allowed)
              bound (#'runner/task-exchange-bound-seconds task)]
          (is (true? (::runner/task-long? task)))
          (is (= allowance-ms (::runner/task-long-ms task)))
          (is (= (+ allowance-seconds priming-seconds) bound)
              "the declared allowance plus measured priming bounds the exchange")
          (is (> bound default-bound))
          (let [notice (#'runner/task-bound-notice "pool-1" task bound)]
            (is (str/includes? notice (str "bound=" (+ allowance-seconds priming-seconds) "s")) notice)
            (is (str/includes? notice (str ":seon.test/long-ms " allowance-ms)) notice)
            (is (str/includes? notice reason) notice)))
        (let [task (task-for 'declared)]
          (is (true? (::runner/task-long? task)))
          (is (nil? (::runner/task-long-ms task)))
          (is (= default-bound (#'runner/task-exchange-bound-seconds task))
              "a declaration without an allowance keeps the default bound")
          (is (str/includes? (#'runner/task-bound-notice "pool-1" task default-bound)
                             "(default per-exchange bound)")))
        (let [task (task-for 'ordinary)]
          (is (false? (::runner/task-long? task)))
          (is (= default-bound (#'runner/task-exchange-bound-seconds task))
              "an undeclared test keeps the default bound"))
        (is (nil? (#'runner/verify-long-declarations-indexed! declarations all-vars))
            "the indexed rows agree with the Vars that declared them")
        (let [refusal (try (#'runner/verify-long-declarations-indexed!
                            (dissoc declarations (symbol (str namespace-name) "allowed"))
                            all-vars)
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))]
          (is (some? refusal)
              "an unindexed declaration refuses instead of reading the row as NOT-LONG")
          (is (= [(symbol (str namespace-name) "allowed")]
                 (mapv :seon.test/sym
                       (:seon.test.runner/drifted-long-declarations
                        (ex-data refusal)))))))
      (finally
        (remove-ns namespace-name)
        (test-support/delete-recursively! root)))))

(deftest a-declared-long-exchange-widens-the-silence-horizon
  ;; The suite watchdog and the per-exchange bound cannot both be tuned
  ;; constants: when a declared allowance exceeds the silence horizon, the
  ;; watchdog would dump every JVM for a wait the program declared legal.
  (let [progress (atom {::runner/description "probe"
                        ::runner/at-nanos (System/nanoTime)})]
    (swap! progress assoc-in [::runner/silence-allowances "task-a"] 630)
    (#'runner/announce! progress "BEGIN probe")
    (is (= {"task-a" 630} (::runner/silence-allowances @progress))
        "an announcement never drops a declared allowance from the horizon")
    (swap! progress update ::runner/silence-allowances dissoc "task-a")
    (is (= {} (::runner/silence-allowances @progress))))
  (let [namespace-object (create-ns (symbol (str "bounds-fixture-" (random-uuid))))
        test-var (intern namespace-object 'long-body (fn [] nil))
        progress (atom {})]
    (try
      (alter-meta! test-var assoc :seon.test/long "Measured body"
                   :seon.test/long-ms 900001)
      (#'runner/progress-event! progress {:type :begin-test-var :var test-var})
      (is (= 631 (get-in @progress [::runner/silence-allowances :test-body]))
          "fast reporter events carry the declaration without a worker exchange")
      (let [began (::runner/at-nanos @progress)
            transitions (atom [])]
        (add-watch progress :bound-transition
                   (fn [_ _ _ after] (swap! transitions conj after)))
        (#'runner/progress-event! progress {:type :end-test-var :var test-var})
        (remove-watch progress :bound-transition)
        (is (every? #(or (get-in % [::runner/silence-allowances :test-body])
                        (> (::runner/at-nanos %) began))
                    @transitions)
            "the watchdog never sees the old timestamp under a reduced horizon"))
      (is (empty? (::runner/silence-allowances @progress)))
      (is (= 951 (#'runner/task-exchange-bound-seconds
                  {::runner/task-long-ms 900001} 50000))
          "the actual worker priming measurement travels into its exchange")
      (finally (remove-ns (ns-name namespace-object))))))

(deftest a-namespace-declared-long-reaches-every-test-row
  ;; A namespace of real-boot drills declares the cost ONCE on its ns form
  ;; (seon.cluster.armed-test, concurrency-streams, program-restart, …). The
  ;; Var-side reader inherited that; the static indexer read only the deftest
  ;; Var, so a published base answered NOT-LONG for twelve real drills and
  ;; the row-reading checker refused every cold gate. One rule now serves both
  ;; seams: seon.program/test-markers, deftest winning per attribute.
  (let [root (doto (io/file "tmp" (str "ns-long-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.ns-long-" (id/id)))
        namespace-reason "Every test here boots a real cluster."
        own-reason "This one also forks a published root."]
    (try
      (spit file
            (str "(ns ^{:seon.test/long " (pr-str namespace-reason)
                 " :seon.test/long-ms 600000} " namespace-name
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest inherits (is true))\n"
                 "(deftest ^{:seon.test/long " (pr-str own-reason)
                 "} overrides (is true))\n"))
      (let [rows (:seon.fn.file/rows
                  (program-fn/build-artifact
                   {:seon.fn/source-path (str file)
                    :seon.fn.file/first-party-functions []}))
            by-symbol (into {} (keep (fn [row]
                                       (when-let [s (:seon.test/sym row)]
                                         [s row])))
                            rows)]
        (is (= 2 (count by-symbol)) (pr-str (keys by-symbol)))
        (is (= {:seon.test/long namespace-reason :seon.test/long-ms 600000}
               (select-keys (get by-symbol (symbol (str namespace-name) "inherits"))
                            [:seon.test/long :seon.test/long-ms]))
            "a namespace-declared long reaches the row of a test that declares nothing")
        (is (= {:seon.test/long own-reason :seon.test/long-ms 600000}
               (select-keys (get by-symbol (symbol (str namespace-name) "overrides"))
                            [:seon.test/long :seon.test/long-ms]))
            "the deftest's own reason wins while it still inherits the allowance"))
      (finally
        (test-support/delete-recursively! root)))))

(deftest a-namespace-declared-allowance-is-not-declaration-drift
  ;; The cold platform gate refused eight seon.dev.fresh-operator-reset-test
  ;; tests: the namespace form declares :seon.test/long AND
  ;; :seon.test/long-ms, both lifted onto every row, while the drift check
  ;; resolved the REASON through the Var-then-namespace rule and the
  ;; ALLOWANCE from Var metadata alone. The row was exactly what the source
  ;; declared, so the refusal named a drift that did not exist. Both halves
  ;; resolve the same way now, and a genuinely unindexed allowance still
  ;; refuses.
  (let [root (doto (io/file "tmp" (str "ns-allowance-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.ns-allowance-" (id/id)))
        namespace-reason "Every test here boots a real isolated operator root."
        allowance-ms 600000
        symbol-for (fn [n] (symbol (str namespace-name) (str n)))]
    (try
      (spit file
            (str "(ns ^{:seon.test/long " (pr-str namespace-reason)
                 " :seon.test/long-ms " allowance-ms "} " namespace-name
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest inherits (is true))\n"))
      (load-file (str file))
      (let [manifest {:seon.fn.manifest/artifacts
                      [(program-fn/build-artifact
                        {:seon.fn/source-path (str file)
                         :seon.fn.file/first-party-functions []})]}
            declarations (#'runner/long-declarations manifest)
            all-vars [(ns-resolve namespace-name 'inherits)]
            drifted (fn [rows]
                      (mapv :seon.test/sym
                            (:seon.test.runner/drifted-long-declarations
                             (ex-data
                              (try (#'runner/verify-long-declarations-indexed!
                                    rows all-vars)
                                   nil
                                   (catch clojure.lang.ExceptionInfo failure
                                     failure))))))]
        (is (= {(symbol-for 'inherits)
                {:seon.test/long namespace-reason
                 :seon.test/long-ms allowance-ms}}
               declarations)
            "the namespace declaration reaches the row whole")
        (is (nil? (#'runner/verify-long-declarations-indexed! declarations
                                                             all-vars))
            "a namespace-level allowance agrees with the row that lifted it")
        (is (= [(symbol-for 'inherits)]
               (drifted (update declarations (symbol-for 'inherits)
                                dissoc :seon.test/long-ms)))
            "a declared allowance missing from the row still refuses by name")
        (is (= [(symbol-for 'inherits)]
               (drifted (dissoc declarations (symbol-for 'inherits))))
            "an unindexed declaration still refuses by name"))
      (finally
        (remove-ns namespace-name)
        (test-support/delete-recursively! root)))))

(deftest one-rule-answers-both-lifting-seams
  ;; The static indexer and the loaded-Var indexer must not be able to
  ;; disagree about what a test declared.
  (is (= {} (program/test-markers nil nil)))
  (is (= {:seon.test/long "ns"} (program/test-markers {} {:seon.test/long "ns"})))
  (is (= {:seon.test/long "var"}
         (program/test-markers {:seon.test/long "var"} {:seon.test/long "ns"}))
      "the deftest wins on conflict")
  (is (= {:seon.test/long "var" :seon.test/long-ms 42}
         (program/test-markers {:seon.test/long "var"} {:seon.test/long-ms 42}))
      "each attribute is decided on its own")
  (is (= {} (program/test-markers {:seon.test/long nil} {}))
      "a declared nil is no declaration")
  (is (= {:seon.test/platform "ns"}
         (program/test-markers {} {:seon.test/platform "ns"}))
      "the platform marker is lifted by the same one rule")
  (is (= {:seon.test/fixture "ns"}
         (program/test-markers {} {:seon.test/fixture "ns"}))
      "so is the fixture marker, which a namespace declares once")
  (is (= {:seon.test/platform "var"}
         (program/test-markers {:seon.test/platform "var"}
                               {:seon.test/platform "ns"}))
      "the deftest wins on conflict for every marker"))

(deftest the-platform-tier-partitions-on-the-indexed-fact-not-var-metadata
  ;; The tier partition used to read Var metadata while the long marker read
  ;; the published row. One partition, two authorities: a base that never
  ;; indexed the declaration still answered PLATFORM from the Var, so nothing
  ;; could refuse the drift the `long` side already refuses. The fact is the
  ;; authority now, and the Vars below keep their metadata precisely so a
  ;; partition that fell back to it would fail this test.
  (let [root (doto (io/file "tmp" (str "platform-fact-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.platform-" (id/id)))
        namespace-reason "Every test here exercises the boot sequence."
        own-reason "This one also re-arms instrumentation."]
    (try
      (spit file
            (str "(ns ^{:seon.test/platform " (pr-str namespace-reason) "} "
                 namespace-name
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest inherits (is true))\n"
                 "(deftest ^{:seon.test/platform " (pr-str own-reason)
                 "} overrides (is true))\n"))
      (load-file (str file))
      (let [manifest {:seon.fn.manifest/artifacts
                      [(program-fn/build-artifact
                        {:seon.fn/source-path (str file)
                         :seon.fn.file/first-party-functions []})]}
            declarations (#'runner/platform-declarations manifest)
            inherits (symbol (str namespace-name) "inherits")
            overrides (symbol (str namespace-name) "overrides")
            all-vars (mapv #(ns-resolve namespace-name %) '[inherits overrides])
            partition-with
            (fn [rows]
              (#'runner/test-selection
               [namespace-name]
               {::runner/include-long? true
                ::runner/long-declarations {}
                ::runner/platform-declarations rows
                ::runner/selected-symbols :all}))]
        (is (= {inherits {:seon.test/platform namespace-reason}
                overrides {:seon.test/platform own-reason}}
               declarations)
            "a namespace-declared platform reason reaches every test row, and the deftest's own reason wins")
        (let [selection (partition-with declarations)]
          (is (= #{inherits overrides}
                 (into #{} (map #'runner/var-symbol)
                       (::runner/platform selection)))
              "the partition selects the platform tier from the indexed rows")
          (is (empty? (::runner/selected selection))))
        (let [selection (partition-with (dissoc declarations overrides))]
          (is (= #{inherits}
                 (into #{} (map #'runner/var-symbol)
                       (::runner/platform selection)))
              "a row the manifest does not carry is NOT platform, however the Var is annotated")
          (is (= #{overrides}
                 (into #{} (map #'runner/var-symbol)
                       (::runner/selected selection)))))
        (is (nil? (#'runner/verify-platform-declarations-indexed!
                   declarations all-vars))
            "the indexed rows agree with the Vars that declared them")
        (let [refusal (try (#'runner/verify-platform-declarations-indexed!
                            (dissoc declarations overrides) all-vars)
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))]
          (is (some? refusal)
              "an unindexed declaration refuses instead of running a platform regression in the bulk tier")
          (is (= [overrides]
                 (mapv :seon.test/sym
                       (:seon.test.runner/drifted-platform-declarations
                        (ex-data refusal)))))))
      (finally
        (remove-ns namespace-name)
        (test-support/delete-recursively! root)))))

(deftest the-bare-namespace-set-is-derived-from-indexed-facts
  ;; `find test -name '*_test.clj'` answered gate membership before, and was
  ;; wrong in both directions: it missed `seon.repl-parity-test`, whose
  ;; deftests a macro emits so the file indexes a namespace and zero
  ;; `:seon.test/sym` rows, and "has test rows" would have admitted
  ;; `seon.test-runner-failure-fixture`, whose `failing-example` asserts
  ;; (= 5 (+ 2 2)) on purpose. Membership and exclusion are different facts.
  (let [root (doto (io/file "tmp" (str "bare-set-" (id/id))) .mkdirs)
        gate-file (io/file root "gate.clj")
        macro-file (io/file root "macro.clj")
        fixture-file (io/file root "fixture.clj")
        source-file (io/file root "source.clj")
        gate-ns (symbol (str "seon.fixture.bare-gate-" (id/id)))
        macro-ns (symbol (str "seon.fixture.bare-macro-" (id/id)))
        fixture-ns (symbol (str "seon.fixture.bare-fixture-" (id/id)))
        source-ns (symbol (str "seon.fixture.bare-source-" (id/id)))
        fixture-reason "Deliberate failure evidence another test asserts over."
        ;; The artifacts carry canonically built rows; only the relative path
        ;; is re-keyed, because the path root IS the input under test and
        ;; `build-artifact` records the probe's own tmp location.
        rooted (fn [source-path published-path]
                 (assoc (program-fn/build-artifact
                         {:seon.fn/source-path source-path
                          :seon.fn.file/first-party-functions []})
                        :seon.fn.file/relative-path published-path))]
    (try
      (spit gate-file
            (str "(ns " gate-ns " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest ordinary (is true))\n"))
      ;; Indexes a namespace and no test rows: its deftests are macro-emitted.
      (spit macro-file (str "(ns " macro-ns ")\n(defn emitted [] true)\n"))
      (spit fixture-file
            (str "(ns ^{:seon.test/fixture " (pr-str fixture-reason) "} "
                 fixture-ns
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest deliberately-red (is (= 5 (+ 2 2))))\n"))
      (spit source-file (str "(ns " source-ns ")\n(defn ordinary [] true)\n"))
      (let [fixture-artifact (rooted (str fixture-file) "test/fixture.clj")
            manifest {:seon.fn.manifest/artifacts
                      [(rooted (str gate-file) "test/gate.clj")
                       (rooted (str macro-file) "test/macro.clj")
                       fixture-artifact
                       (rooted (str source-file) "src/source.clj")]}
            derived (#'runner/bare-namespaces manifest)]
        (is (some :seon.test/fixture (:seon.fn.file/rows fixture-artifact))
            "the marker reaches the row through the same one lifting rule")
        (is (= [gate-ns macro-ns] derived)
            "every namespace indexed under the test root, fixture namespaces excluded, source namespaces never admitted")
        (let [refusal (try (#'runner/bare-namespaces
                            {:seon.fn.manifest/artifacts
                             [(rooted (str source-file) "src/source.clj")]})
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))]
          (is (some? refusal)
              "a manifest with no test-rooted namespace refuses instead of running nothing and reporting success")
          (is (= "test" (:seon.test.runner/test-source-root
                          (ex-data refusal))))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest the-deliberate-failure-fixtures-declare-their-exclusion
  ;; The bare gate no longer excludes these by filename, so the two
  ;; namespaces whose tests are material rather than members must say so.
  ;; Losing either declaration turns the bare gate permanently red.
  (doseq [namespace-name '[seon.test-runner-failure-fixture my.examples-fixture]]
    (require namespace-name)
    (let [reason (:seon.test/fixture (meta (find-ns namespace-name)))]
      (is (string? reason) (str namespace-name " declares no :seon.test/fixture reason"))
      (is (not (str/blank? reason))))))

;;; ---------------------------------------------------------------------------
;;; The schema restore derives from FACTS
;;; ---------------------------------------------------------------------------

(defn- probe-environment
  "One cluster snapshot entry shaped exactly like `live-cluster-schema-states`:
  the connection whose facts decide the projection, and the projection value."
  [connection projection]
  (env/environment {:seon.boot/cluster-name "restore-probe"
                    :seon.db/connection connection
                    :seon.schema/projection projection
                    :seon.db/basis-t (db/basis-t (db/db connection))}))

(defn- declare-schema-key!
  "Commit one synthetic declaration the way the canonical rows express it."
  [connection schema-key]
  (test-support/transacted!
   connection (schema/canonical-schema-rows {schema-key :string})))

(defn- retract-schema-key!
  "Retract the synthetic declaration; its past remains in history."
  {:malli/schema [:=> [:cat :seon.db/connection :qualified-keyword]
                  :seon.db/transaction-report]}
  [connection schema-key]
  (test-support/transacted!
   connection [[:db/retractEntity [:seon.schema/key schema-key]]]))

(deftest ^{:seon.test/platform
           "Moving part: the live cluster projection an in-process run leaves behind."}
  a-committed-retraction-survives-the-restore-and-is-named-a-committed-change
  ;; 2026-09-17: a probe turn deliberately retracted four declarations from
  ;; `default` DURING a run, and the restore put them back from its entering
  ;; snapshot — a mirror acting against the writer (AGENTS §2.1). The
  ;; projection then disagreed with the cluster's own committed facts until
  ;; someone advanced it by hand.
  (test-support/with-database
    (fn [connection]
      (let [retracted :seon.test.runner-test.probe/retracted-during-run
            added :seon.test.runner-test.probe/added-during-run]
        (declare-schema-key! connection retracted)
        (let [entering (schema/projection-from-database (db/db connection))
              state (atom (probe-environment connection entering))
              before {"restore-probe" [state @state]}]
          (is (contains? (:seon.schema.projection/forms entering) retracted)
              "the run enters with the declaration its facts declared")
          ;; The run commits both directions while it is in flight.
          (retract-schema-key! connection retracted)
          (declare-schema-key! connection added)
          (let [report (runner/restore-live-cluster-schema! before)
                row (first report)
                forms (:seon.schema.projection/forms (:seon.schema/projection @state))]
            (is (= 1 (count report)) (pr-str report))
            (is (= "restore-probe" (:seon.cluster/name row)))
            (is (not (contains? forms retracted))
                "the declaration its own writer retracted STAYS retracted")
            (is (contains? forms added)
                "and the one its writer added is in the projection the cluster now holds")
            (is (= [(str retracted)] (:seon.test.runner/committed-removed row))
                "the retraction is named a committed change, by key")
            (is (= [(str added)] (:seon.test.runner/committed-added row))
                "so is the addition")
            (is (nil? (:seon.test.runner/drift-added row)))
            (is (nil? (:seon.test.runner/drift-removed row)))
            (is (empty? (runner/schema-restore-drift report))
                "a committed change is the writer doing its job, never a test error")))))))

(deftest ^{:seon.test/platform
           "Moving part: the live cluster projection an in-process run leaves behind."}
  a-registration-that-committed-nothing-is-restored-away-and-named-drift
  ;; The other direction, and the original disease: a run registers a
  ;; declaration into the projection a live cluster's writer compiles
  ;; against, commits nothing, and every later write on that cluster is
  ;; refused. The facts never held the key, so the facts remove it.
  (test-support/with-database
    (fn [connection]
      (let [leaked :seon.test.runner-test.probe/never-committed
            entering (schema/projection-from-database (db/db connection))
            state (atom (probe-environment connection entering))
            before {"restore-probe" [state @state]}]
        (swap! state update-in [:seon.schema/projection :seon.schema.projection/forms]
               assoc leaked [:string {:seon.db/identity true}])
        (let [report (runner/restore-live-cluster-schema! before)
              row (first report)]
          (is (= 1 (count report)) (pr-str report))
          (is (= [(str leaked)] (:seon.test.runner/drift-added row))
              "the leaked key is named, not merely counted")
          (is (nil? (:seon.test.runner/committed-removed row)))
          (is (not (contains? (:seon.schema.projection/forms
                               (:seon.schema/projection @state))
                              leaked))
              "and the cluster is left holding what its facts declare")
          (is (= report (runner/schema-restore-drift report))
              "an uncommitted registration IS the run's own failure"))))))

(deftest ^{:seon.test/platform
           "Moving part: the live cluster projection an in-process run leaves behind."}
  a-run-that-touched-no-declaration-reports-nothing
  (test-support/with-database
    (fn [connection]
      (let [entering (schema/projection-from-database (db/db connection))
            state (atom (probe-environment connection entering))]
        (is (empty? (runner/restore-live-cluster-schema!
                     {"restore-probe" [state @state]}))
            "no schema activity, nothing to report")
        (is (= entering (:seon.schema/projection @state))
            "and nothing to change"))))
  ;; A snapshot with no connection has no authority to derive from. That is
  ;; the typed unknown, never silence (AGENTS §2.4).
  (let [environment (env/environment {:seon.boot/cluster-name "restore-probe"})
        report (runner/restore-live-cluster-schema!
                {"restore-probe" [(atom environment) environment]})]
    (is (= 1 (count report)))
    (is (string? (:seon.test.runner/schema-authority-unavailable (first report))))
    (is (empty? (runner/schema-restore-drift report))
        "an unavailable observation is not a test failure")))
