;; Copy to test/seon/led_cache_probe_test.clj, then:
;; bin/seon check test/seon/led_cache_probe_test.clj --test seon.led-cache-probe-test --include-long
;; (add src paths to measure a change against HEAD).
(ns seon.led-cache-probe-test
  "Disposable measurement: the first named check on a fresh JVM and on a second
  new branch. Fails on purpose so the runner's report shows the records."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.pass-probe :as probe]
            [seon.test-support :as support]))

(defn- check-pass
  "One measured MCP SCI named check of `ns-name`/sq-test as `agent-id`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [running agent-id ns-name]
  (let [pass #'probe/mcp-pass
        world (assoc (#'probe/agent-world running agent-id)
                     ::probe/stages '#{seon.test/run seon.test/select seon.test/clean-request-handle
                                       seon.test.runner/reach-digests seon.test/host-exclusions})
        record (probe/window world (pass {"code" (str "(my.test/check {:seon.test/named ['" ns-name "/sq-test]})")
                                          "agent" agent-id "cluster" running}))]
    (select-keys record [::probe/wall-ms ::probe/allocated-bytes ::probe/checked-calls ::probe/top-calls
                         ::probe/top-ms ::probe/stages ::probe/error?])))

(defn- define!
  "Define sq and sq-test in `ns-name` as `agent-id`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [running agent-id ns-name]
  ((#'probe/mcp-pass {"code" (str "(ns " ns-name " (:require [clojure.test :refer [deftest is]]))"
                                  "(defn sq [x] (* x x)) (deftest sq-test (is (= 4 (sq 2))))")
                      "agent" agent-id "cluster" running})))

(defn- gate-cost
  "Reads, ms and allocated bytes of one gate-sets over every function symbol."
  {:malli/schema [:=> [:cat :seon.db/connection] :map]}
  [connection]
  (let [database (db/db connection)
        seeds (set (db/q '[:find [?s ...] :where [_ :seon.fn/sym ?s]] database))
        mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        sink (atom [])
        a0 (.getTotalThreadAllocatedBytes mx) t0 (System/nanoTime)
        tests (binding [db/*read-evidence-sink* sink]
                (functions/gate-sets {:seon.db/db database :seon.fn/seeds seeds}))]
    {:seeds (count seeds) :tests (count tests) :reads (count @sink)
     :ms (/ (- (System/nanoTime) t0) 1e6) :bytes (- (.getTotalThreadAllocatedBytes mx) a0)}))

(deftest ^{:seon.test/long "Disposable probe." :seon.test/long-ms 120000}
  first-check-costs
  (support/with-running-agent
   (fn [running _ connection]
     (let [a (str "probe-a-" (id/id)) b (str "probe-b-" (id/id))]
       (define! running a "scratch.pa")
       (let [cold (check-pass running a "scratch.pa")
             warm (check-pass running a "scratch.pa")
             _ (define! running b "scratch.pb")
             branch (check-pass running b "scratch.pb")
             gate (gate-cost connection)
             gate2 (gate-cost connection)]
         (is (= :show {:cold cold :warm warm :branch branch :gate gate :gate2 gate2})))))))
