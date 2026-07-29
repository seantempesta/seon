(load-file "tmp/f4-drives/scripts/f4_common.clj")

(ns f4-drives.live
  "F4 live drives that run inside one JVM and one dedicated store root."
  (:require [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [datahike.api :as d]
            [f4-drives.common :as common]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.work :as work]))

(def parallel-agent-ids
  (mapv #(format "parallel-%02d" %) (range 1 7)))

(def parked-agent-ids
  (mapv #(format "parked-%03d" %) (range 1 100)))

(defn completion-prompt
  "A strict, agent-specific one-form request."
  [agent-id]
  (str "F4_PARALLEL " agent-id
       ". Return exactly one executable Clojure form and no prose or "
       "Markdown: (my.run/complete \"" agent-id "-ok\")."))

(defn closed-wave!
  "Wait for every message's run to close and return timing+census."
  [connection triggered]
  (let [runs
        (common/await-db
         "every concurrent run to close"
         connection
         #(common/closed-runs % (:f4-drives/messages triggered))
         180000)
        db @connection]
    {:f4-drives/runs runs
     :f4-drives/timings-ms
     (into {}
           (map (fn [[agent-id run]]
                  [agent-id
                   (common/duration-ms
                    (:f4-drives/triggered-at triggered) run)]))
           runs)
     :f4-drives/census
     (into {}
           (map (fn [[agent-id run]]
                  [agent-id
                   (common/run-census
                    db (:seon.cluster.run/id run))]))
           runs)}))

(defn verify-independent-wave!
  "Prove each trigger, run, result, and attempt stayed with its agent."
  [wave]
  (doseq [[agent-id census] (:f4-drives/census wave)]
    (common/assert-drive!
     (= agent-id (:f4-drives/agent census))
     "A run crossed agent ownership."
     {:f4-drives/agent agent-id :f4-drives/census census})
    (common/assert-drive!
     (= 1 (count (:f4-drives/attempts census)))
     "A real-model run did not commit exactly one attempt."
     {:f4-drives/agent agent-id :f4-drives/census census})
    (common/assert-drive!
     (some #(str/includes? (second %) (str agent-id "-ok"))
           (:f4-drives/forms census))
     "The frozen plan did not carry its own agent-specific answer."
     {:f4-drives/agent agent-id :f4-drives/census census})
    (common/assert-drive!
     (some #(str/includes?
             (str (:seon.cluster.eval/result-edn %))
             (str agent-id "-ok"))
           (:f4-drives/receipts census))
     "The terminal receipt did not carry its own agent-specific answer."
     {:f4-drives/agent agent-id :f4-drives/census census}))
  true)

(defn parallel-drive!
  "Run six real model turns from one simultaneous commit."
  []
  (common/stamp "DRIVE 1: N-agent parallel")
  (let [instance (common/start-local! "f4-parallel")
        connection (common/connection instance)]
    (try
      (let [prompts (into {} (map (juxt identity completion-prompt))
                          parallel-agent-ids)
            triggered (common/install-agents-and-triggers!
                       connection prompts)
            fleet
            (common/await-value
             "at least five simultaneous mid-turn fleet rows"
             (fn []
               (let [agents (common/fleet-agents instance)
                     mid (count
                          (filter #(= :mid-turn
                                      (:seon.oversight/state %))
                                  agents))]
                 (when (>= mid 5)
                   {:f4-drives/mid-turn mid
                    :f4-drives/agents agents
                    :f4-drives/html (common/fleet-html instance)})))
             30000)
            _ (common/write-text!
               (str common/evidence-root "/parallel-fleet.html")
               (:f4-drives/html fleet))
            wave (closed-wave! connection triggered)
            _ (verify-independent-wave! wave)
            attempt-starts
            (->> (:f4-drives/census wave)
                 vals
                 (mapcat :f4-drives/attempts)
                 (map second)
                 sort
                 vec)
            spread-ms
            (- (inst-ms (last attempt-starts))
               (inst-ms (first attempt-starts)))
            result
            {:f4-drives/drive :parallel
             :f4-drives/verdict :pass
             :f4-drives/web-url
             (get-in instance
                     [:seon.boot/advertisement :seon.render.web/url])
             :f4-drives/simultaneous-mid-turn
             (:f4-drives/mid-turn fleet)
             :f4-drives/attempt-start-spread-ms spread-ms
             :f4-drives/fleet-agents (:f4-drives/agents fleet)
             :f4-drives/timings-ms (:f4-drives/timings-ms wave)
             :f4-drives/census (:f4-drives/census wave)}]
        (common/stamp "PASS drive 1: "
                      (:f4-drives/simultaneous-mid-turn result)
                      " mid-turn; attempt-start spread "
                      spread-ms " ms")
        result)
      (finally
        (cluster/stop! instance)))))

(defn parked-drive!
  "Arm 100 agents, measure idle, then wake ten at once."
  []
  (common/stamp "DRIVE 2: 100 parked, wake 10")
  (let [instance (common/start-local! "f4-parked")
        connection (common/connection instance)]
    (try
      (let [threads-before (common/thread-dump-counts "parked-before")
            heap-before (common/gc-used-mb)
            began (System/nanoTime)
            _ (common/create-agents! connection parked-agent-ids)
            armed
            (common/await-value
             "100 armed agent graphs"
             #(when (= 100 (common/armed-count instance))
                (common/armed-count instance))
             30000)
            arm-ms (/ (- (System/nanoTime) began) 1000000.0)
            threads-after (common/thread-dump-counts "parked-after")
            heap-after (common/gc-used-mb)
            added 99
            heap-kib-per-agent
            (/ (* 1024.0 (- heap-after heap-before)) added)
            virtual-per-agent
            (/ (double
                (- (:f4-drives/virtual threads-after)
                   (:f4-drives/virtual threads-before)))
               added)
            wake-ids (subvec parked-agent-ids 0 10)
            triggered
            (common/trigger-existing!
             connection
             (into {} (map (juxt identity completion-prompt)) wake-ids))
            mid
            (common/await-value
             "at least eight of ten woken agents mid-turn"
             (fn []
               (let [wanted (set wake-ids)
                     agents
                     (filter
                      #(contains? wanted (:seon.cluster.agent/id %))
                      (common/fleet-agents instance))
                     count-mid
                     (count
                      (filter #(= :mid-turn
                                  (:seon.oversight/state %))
                              agents))]
                 (when (>= count-mid 8) count-mid)))
             30000)
            wave (closed-wave! connection triggered)
            _ (verify-independent-wave! wave)
            result
            {:f4-drives/drive :parked-100
             :f4-drives/verdict :pass
             :f4-drives/armed armed
             :f4-drives/arm-ms arm-ms
             :f4-drives/threads-before threads-before
             :f4-drives/threads-after threads-after
             :f4-drives/virtual-threads-per-added-agent virtual-per-agent
             :f4-drives/heap-before-mib heap-before
             :f4-drives/heap-after-mib heap-after
             :f4-drives/heap-kib-per-added-agent heap-kib-per-agent
             :f4-drives/woken-mid-turn mid
             :f4-drives/wake-timings-ms (:f4-drives/timings-ms wave)}]
        (common/assert-drive!
         (<= 1.8 virtual-per-agent 2.2)
         "A two-proc agent graph did not cost about two virtual threads."
         result)
        (common/stamp "PASS drive 2: " armed " armed in "
                      (format "%.1f" arm-ms) " ms; "
                      (format "%.2f" virtual-per-agent)
                      " virtual threads and "
                      (format "%.1f" heap-kib-per-agent)
                      " KiB per added agent")
        result)
      (finally
        (cluster/stop! instance)))))

(def self-instruction
  (str "F4_SELF_STEP. Return exactly five executable Clojure forms and "
       "no prose or Markdown. The first four forms are each "
       "(my.message/send \"capper\" "
       "\"Return exactly one executable Clojure form and no prose: "
       "(my.run/complete \\\\\"self-step-ok\\\\\").\"). "
       "The fifth form is (my.run/complete \"seed-step-ok\")."))

(defn episode-cap-drive!
  "Drive a self-message chain into a planted cap and reset it outside."
  []
  (common/stamp "DRIVE 4: episode cap")
  (let [instance
        (common/start-local!
         "f4-episode"
         {:seon.config.run/max-episode-runs 3})
        connection (common/connection instance)]
    (try
      (common/create-agents! connection ["capper"])
      (let [first-trigger
            (common/trigger-existing!
             connection {"capper" self-instruction})
            capped
            (common/await-db
             "a conserved self-trigger deferred at episode cap"
             connection
             (fn [db]
               (let [deferred (work/deferred-triggers db "capper")
                     episode (work/episode-runs db "capper")
                     current
                     (d/q '[:find ?run .
                            :where
                            [?agent :seon.cluster.agent/id "capper"]
                            [?agent :seon.cluster.agent/run ?run]]
                          db)]
                 (when (and (= 3 episode)
                            (>= (count deferred) 2)
                            (nil? current))
                   {:f4-drives/episode-runs episode
                    :f4-drives/deferred deferred
                    :f4-drives/basis-t (:max-tx db)
                    :f4-drives/commit-id
                    (:datahike.value/commit-id
                     (d/committed-value-identity db))})))
             180000)
            basis-before (common/basis connection)
            _ (Thread/sleep 1000)
            basis-after (common/basis connection)
            _ (common/assert-drive!
               (= basis-before basis-after)
               "The episode-cap refusal wrote database facts."
               {:f4-drives/before basis-before
                :f4-drives/after basis-after})
            outside-content
            (str "F4_OUTSIDE_RESET. Return exactly one executable Clojure "
                 "form and no prose: (my.run/complete \"outside-reset-ok\").")
            outside
            (common/trigger-existing!
             connection {"capper" outside-content})
            outside-message (get-in outside [:f4-drives/messages "capper"])
            outside-run
            (common/await-db
             "the outside trigger to open and close past a deferred self-trigger"
             connection
             (fn [db]
               (let [run (common/run-for-trigger db outside-message)]
                 (when (:seon.cluster.run/closed-at run) run)))
             180000)
            outside-census
            (common/run-census
             @connection (:seon.cluster.run/id outside-run))
            result
            {:f4-drives/drive :episode-cap
             :f4-drives/verdict :pass
             :f4-drives/planted-cap 3
             :f4-drives/capped capped
             :f4-drives/zero-write-before basis-before
             :f4-drives/zero-write-after basis-after
             :f4-drives/outside-run outside-run
             :f4-drives/outside-census outside-census
             :f4-drives/outside-trigger-to-close-ms
             (common/duration-ms
              (:f4-drives/triggered-at outside) outside-run)}]
        (common/assert-drive!
         (some #(str/includes? (second %) "outside-reset-ok")
               (:f4-drives/forms outside-census))
         "The outside trigger did not reset the episode gate."
         result)
        (common/stamp "PASS drive 4: cap 3 deferred one trigger with "
                      "zero writes; outside trigger closed in "
                      (:f4-drives/outside-trigger-to-close-ms result) " ms")
        result)
      (finally
        (cluster/stop! instance)))))

(defn ancestor-branch
  "The one live ancestor branch in the dedicated store roster."
  [store]
  (let [branches (registry/roster store)
        ancestors
        (filter #(str/starts-with? (name %) "ancestor-") branches)]
    (common/assert-drive!
     (= 1 (count ancestors))
     "The F4 store did not have exactly one source ancestor."
     {:f4-drives/branches branches})
    (first ancestors)))

(defn two-cluster-drive!
  "Run two branches in one JVM, reset one, and prove sibling isolation."
  []
  (common/stamp "DRIVE 5: two clusters, one JVM")
  ;; Earlier drives have stopped before this boundary. Rebuild the dedicated
  ;; scratch store here so its roster contains exactly the source ancestor
  ;; shared by these two clusters and no historical drive branches.
  (common/delete-tree! common/runtime-root)
  (let [a (common/start-local! "f4-two-a")
        b (common/start-local! "f4-two-b")
        connection-a (common/connection a)
        connection-b (common/connection b)]
    (try
      (let [trigger-a
            (common/install-agents-and-triggers!
             connection-a {"two-a" (completion-prompt "two-a")})
            trigger-b
            (common/install-agents-and-triggers!
             connection-b {"two-b" (completion-prompt "two-b")})
            both-mid
            (common/await-value
             "both clusters mid-model simultaneously"
             #(when (and (pos? (common/mid-turn-count a))
                         (pos? (common/mid-turn-count b)))
                {:f4-drives/a (common/fleet-agents a)
                 :f4-drives/b (common/fleet-agents b)})
             30000)
            wave-a (closed-wave! connection-a trigger-a)
            wave-b (closed-wave! connection-b trigger-b)
            _ (verify-independent-wave! wave-a)
            _ (verify-independent-wave! wave-b)
            b-before (common/basis connection-b)
            b-url-before
            (get-in b [:seon.boot/advertisement :seon.render.web/url])
            store (:seon.store/store a)
            ancestor (ancestor-branch store)
            _ (cluster/stop! a)
            reset
            (registry/reset-cluster!
             {:seon.store/store store
              :seon.boot/cluster-name "f4-two-a"
              :seon.ancestor/branch ancestor})
            b-after-reset (common/basis connection-b)
            _ (common/assert-drive!
               (= b-before b-after-reset)
               "Resetting cluster A moved cluster B's branch head."
               {:f4-drives/b-before b-before
                :f4-drives/b-after b-after-reset})
            a2 (common/start-local! "f4-two-a")]
        (try
          (let [a-agent-count
                (d/q '[:find (count ?agent) .
                       :where [?agent :seon.cluster.agent/id _]]
                     @(common/connection a2))
                b-follow
                (common/trigger-existing!
                 connection-b
                 {"two-b" (str "F4_B_AFTER_A_RESET. Return exactly one "
                               "executable Clojure form and no prose: "
                               "(my.run/complete \"two-b-after-reset-ok\").")})
                b-follow-wave (closed-wave! connection-b b-follow)
                b-url-after
                (get-in b
                        [:seon.boot/advertisement :seon.render.web/url])
                result
                {:f4-drives/drive :two-clusters
                 :f4-drives/verdict :pass
                 :f4-drives/process-pid-a
                 (get-in a [:seon.boot/advertisement :seon.boot/pid])
                 :f4-drives/process-pid-b
                 (get-in b [:seon.boot/advertisement :seon.boot/pid])
                 :f4-drives/both-mid-turn both-mid
                 :f4-drives/first-wave-a
                 (:f4-drives/timings-ms wave-a)
                 :f4-drives/first-wave-b
                 (:f4-drives/timings-ms wave-b)
                 :f4-drives/reset reset
                 :f4-drives/a-agent-count-after-reset a-agent-count
                 :f4-drives/b-basis-before b-before
                 :f4-drives/b-basis-after-reset b-after-reset
                 :f4-drives/b-url-before b-url-before
                 :f4-drives/b-url-after b-url-after
                 :f4-drives/b-follow-timing-ms
                 (:f4-drives/timings-ms b-follow-wave)}]
            (common/assert-drive!
             (= 1 a-agent-count)
             "Reset cluster A did not return to ancestor/root-only state."
             result)
            (common/assert-drive!
             (= b-url-before b-url-after)
             "Cluster B's live web endpoint changed during A's reset."
             result)
            (common/stamp "PASS drive 5: pid "
                          (:f4-drives/process-pid-a result)
                          " hosted both; A reset to root-only while B basis "
                          "and URL stayed byte-equal")
            result)
          (finally
            (cluster/stop! a2))))
      (finally
        ;; A may already be stopped; stop! is instance-addressed/idempotent.
        (cluster/stop! a)
        (cluster/stop! b)))))

(defn -main
  "Run the four in-process F4 drives and persist raw EDN evidence."
  []
  (common/delete-tree! common/runtime-root)
  (common/ensure-directory! common/evidence-root)
  (let [result
        {:f4-drives/source-head (common/git-head)
         :f4-drives/local-model common/local-model
         :f4-drives/no-auth-target
         (common/local-targets
          {:seon.config.ai/endpoint common/local-endpoint
           :seon.config.ai/model common/local-model
           :seon.config.ai/timeout-ms 120000})
         :f4-drives/parallel (parallel-drive!)
         :f4-drives/parked (parked-drive!)
         :f4-drives/episode-cap (episode-cap-drive!)
         :f4-drives/two-clusters (two-cluster-drive!)}]
    (common/write-edn!
     (str common/evidence-root "/in-process-drives.edn") result)
    (common/stamp "ALL IN-PROCESS F4 DRIVES COMPLETE")
    (shutdown-agents)
    (System/exit 0)))

(-main)
