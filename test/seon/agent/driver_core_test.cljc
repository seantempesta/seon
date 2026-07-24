(ns seon.agent.driver-core-test
  "Portable claim, cursor, attempt, and recovery authority regressions."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [seon.agent.driver :as driver]
   [seon.agent.interaction :as interaction]
   [seon.agent.loop.core :as loop.core]
   [seon.agent.run.core :as run.core]
   [seon.agent.turn.core :as turn.core]
   [seon.schema :as schema]
   [seon.runtime.recovery.core :as recovery.core]))

(def agent-id "agent-u2")
(def run-id "run-u2")
(def claimant-a "101@2026-07-23T01:00:00Z")
(def claimant-b "202@2026-07-23T01:01:00Z")
(def old-beat #inst "2026-07-23T01:00:00.000-00:00")
(def now #inst "2026-07-23T01:01:00.000-00:00")

(def base-run
  {:seon.agent/id agent-id
   :seon.agent.run/id run-id
   :seon.agent.run/status :open
   :seon.agent.run/last-beat-at old-beat})

(deftest acquisition-reacquisition-and-steal-are-distinct
  (testing "a queued interaction attaches through the first claim CAS"
    (let [plan
          (run.core/claim-plan
           (assoc base-run
                  :seon.agent.interaction/id "interaction-u2"
                  :seon.agent.run/attached? false)
           claimant-a now 1000 nil)]
      (is (= :attach-acquire
             (:seon.agent.run/claim-transition plan)))
      (is (= [:db.fn/cas [:seon.agent/id agent-id]
              :seon.agent/run nil [:seon.agent.run/id run-id]]
             (first (:seon.db/tx-data plan))))))
  (testing "first acquisition asserts both absent values"
    (let [plan (run.core/claim-plan base-run claimant-a now 1000 nil)]
      (is (= :acquire (:seon.agent.run/claim-transition plan)))
      (is (= 1 (:seon.agent.run/claim-epoch plan)))
      (is (some #{[:db.fn/cas [:seon.agent.run/id run-id]
                   :seon.agent.run/claimant nil claimant-a]}
                (:seon.db/tx-data plan)))
      (is (some #{[:db.fn/cas [:seon.agent.run/id run-id]
                   :seon.agent.run/claim-epoch nil 1]}
                (:seon.db/tx-data plan)))))
  (testing "released ownership increments without displacing anyone"
    (let [plan (run.core/claim-plan
                (assoc base-run :seon.agent.run/claim-epoch 7)
                claimant-b now 1000 nil)]
      (is (= :reacquire (:seon.agent.run/claim-transition plan)))
      (is (= 8 (:seon.agent.run/claim-epoch plan)))
      (is (not-any? #(and (= :seon.agent.run/last-beat-at (nth % 2 nil))
                          (= (nth % 3 nil) (nth % 4 nil)))
                    (:seon.db/tx-data plan)))))
  (testing "steal asserts the observed stale beat before replacing custody"
    (let [plan (run.core/claim-plan
                (assoc base-run
                       :seon.agent.run/claimant claimant-a
                       :seon.agent.run/claim-epoch 7)
                claimant-b now 1000 nil)
          tx (:seon.db/tx-data plan)]
      (is (= :steal (:seon.agent.run/claim-transition plan)))
      (is (= 8 (:seon.agent.run/claim-epoch plan)))
      (is (= [:db.fn/cas [:seon.agent.run/id run-id]
              :seon.agent.run/last-beat-at old-beat old-beat]
             (second tx))))))

(deftest every-held-work-builder-carries-the-two-operation-fence
  (let [fence (run.core/run-fence agent-id run-id 8)]
    (is (= 2 (count fence)))
    (is (= fence (subvec (run.core/beat-tx-data
                           agent-id run-id 8 now nil)
                         0 2)))
    (is (= fence (subvec (run.core/release-tx-data agent-id run-id 8)
                         0 2)))
    (is (= fence (subvec (run.core/close-tx-data
                           agent-id run-id 8 :completed now)
                         0 2)))))

(deftest input-consumption-is-an-explicit-edge
  (let [input [:seon.agent.message/id "message-u2"]
        tx (:seon.db/tx-data
            (run.core/claim-plan base-run claimant-a now 1000 input))]
    (is (some #{[:db/add [:seon.agent.run/id run-id]
                :seon.agent.run/consumed-input input]}
              tx))))

(deftest durable-turn-cursor-and-attempt-builders
  (let [fence (run.core/run-fence agent-id run-id 3)
        attempt-id "attempt-u2"
        open-tx
        (turn.core/open-attempt-tx-data
         fence "turn-u2" attempt-id
         {:seon.ai.attempt/ordinal 0
          :seon.ai.attempt/config-digest
          "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          :seon.ai.attempt/deadline-at now
          :seon.ai.attempt/provider :deepseek
          :seon.ai.attempt/adapter :openai-compat
          :seon.ai.attempt/outer-timeout-ms 10000
          :seon.ai.attempt/stream? false})]
    (is (= fence (subvec open-tx 0 2)))
    (is (some #{[:db.fn/cas [:seon.agent.turn/id "turn-u2"]
                :seon.agent.turn/phase :rendered :attempt-open]}
              open-tx))
    (is (= :open
           (:seon.ai.attempt/outcome
            (first (filter map? open-tx)))))
    (is (= 3
           (turn.core/next-attempt-ordinal
            [{:seon.ai.attempt/ordinal 0}
             {:seon.ai.attempt/ordinal 2}])))))

(deftest phase-error-close-terminalizes-receipts-and-releases-custody
  (let [fence (run.core/run-fence agent-id run-id 3)
        tx
        (turn.core/phase-error-close-tx-data
         fence agent-id run-id "turn-u2" :attempt-open ["attempt-u2"]
         now "provider configuration failed")]
    (is (= fence (subvec tx 0 2)))
    (is (some #{[:db.fn/cas [:seon.agent.turn/id "turn-u2"]
                :seon.agent.turn/phase :attempt-open :attempt-open]}
              tx))
    (is (some #{[:db.fn/cas [:seon.ai.attempt/id "attempt-u2"]
                :seon.ai.attempt/outcome :open :crashed]}
              tx))
    (is (some #(and (map? %)
                    (= :published (:seon.agent.turn/phase %))
                    (= :error (:seon.agent.turn/status %)))
              tx))
    (is (some #(and (map? %)
                    (= :closed (:seon.agent.run/status %))
                    (= :error (:seon.agent.run/closed-reason %)))
              tx))
    (is (some #{[:db/retract [:seon.agent.run/id run-id]
                :seon.agent.run/claimant]}
              tx))
    (is (some #{[:db/retract [:seon.agent/id agent-id]
                :seon.agent/run]}
              tx))))

(deftest external-close-terminalizes-the-active-turn-in-the-same-transaction
  (let [fence (run.core/run-fence agent-id run-id 3)
        tx
        (turn.core/terminal-close-tx-data
         fence agent-id run-id "turn-u2" :evaling []
         now :interrupted :superseded
         "The run closed :superseded before the active turn published.")]
    (is (= fence (subvec tx 0 2)))
    (is (some #{[:db.fn/cas [:seon.agent.turn/id "turn-u2"]
                :seon.agent.turn/phase :evaling :evaling]}
              tx))
    (is (some #(and (map? %)
                    (= :published (:seon.agent.turn/phase %))
                    (= :interrupted (:seon.agent.turn/status %)))
              tx))
    (is (some #(and (map? %)
                    (= :closed (:seon.agent.run/status %))
                    (= :superseded (:seon.agent.run/closed-reason %)))
              tx))
    (is (some #{[:db/retract [:seon.agent.run/id run-id]
                :seon.agent.run/claimant]}
              tx))
    (is (some #{[:db/retract [:seon.agent/id agent-id]
                :seon.agent/run]}
              tx))))

(deftest phase-eligibility-is-policy-data
  (is (loop.core/eligible?
       #{:seon.agent.driver.capability/interaction}
       (assoc base-run
              :seon.agent.interaction/id "interaction-u2"
              :seon.agent.interaction/status :pending)))
  (is (= :interaction
         (loop.core/next-step
          (assoc base-run
                 :seon.agent.interaction/id "interaction-u2"
                 :seon.agent.interaction/status :pending))))
  (is (loop.core/eligible?
       #{:seon.agent.driver.capability/render}
       base-run))
  (is (not
       (loop.core/eligible?
        #{:seon.agent.driver.capability/eval}
        base-run)))
  (is (loop.core/eligible?
       #{:seon.agent.driver.capability/eval}
       (assoc base-run
              :seon.agent.run/current-turn
              {:seon.agent.turn/phase :reply-ready}))))

(deftest interaction-receipts-share-the-held-run-fence
  (let [interaction-id "interaction-u2"
        fence (run.core/run-fence agent-id run-id 3)
        start
        (interaction/start-tx-data
         {:seon.agent/id agent-id
          :seon.agent.run/id run-id
          :seon.agent.run/claim-epoch 3
          :seon.agent.interaction/id interaction-id})
        interrupted
        (interaction/error-tx-data
         {:seon.agent/id agent-id
          :seon.agent.run/id run-id
          :seon.agent.run/claim-epoch 3
          :seon.agent.interaction/id interaction-id
          :seon.agent.interaction/observed-status :running
          :seon.agent.interaction/terminal-status :interrupted
          :seon.agent.interaction/error
          {:seon.error/message "claimant stopped"
           :seon.error/kind :agent}
          :seon.agent.interaction/settled-at now})]
    (is (= fence (subvec start 0 2)))
    (is (= [:db.fn/cas [:seon.agent.interaction/id interaction-id]
            :seon.agent.interaction/status :pending :running]
           (last start)))
    (is (= fence (subvec interrupted 0 2)))
    (is (some
         #{[:db.fn/cas [:seon.agent.interaction/id interaction-id]
            :seon.agent.interaction/status :running :interrupted]}
         interrupted))
    (is (some
         #{[:db/add [:seon.agent.interaction/id interaction-id]
            :seon.agent.interaction/error
            {:seon.error/message "claimant stopped"
             :seon.error/kind :agent}]}
         interrupted))))

(defn- disposition-fixture [plan inventory]
  (let [schema-projection
        (schema/projection-from-rows
         {:seon.schema/schema-rows #{}
          :seon.schema/function-contract-rows #{}})
        planning-projection
        {:seon.execution/basis-t 7
         :seon.execution/commit-id "commit-7"
         :seon.execution/graph-digest "graph-7"
         :seon.execution/schema-projection schema-projection
         :seon.execution/schema-fingerprint
         (:seon.schema.projection/fingerprint schema-projection)
         :seon.execution/artifact-inventories
         {:seon.execution.inventory/availability :available
          :seon.execution.inventory/exports-by-tier {:jvm #{} :bun #{}}
          :seon.execution.inventory/digest "artifacts-7"}}]
    (driver/execution-plan-disposition
     {:seon.execution/plan plan
      :seon.execution/planning-projection planning-projection
      :seon.execution/tier-inventories inventory
      :seon.execution/invoking-tier :jvm
      :seon.execution/roots ['(+ 1 2)]
      :seon.execution/db-value
      {:t 7 :datahike/commit-id "commit-7"}})))

(def empty-manifests
  {:seon.execution/schema-manifest
   {:seon.execution/schema-keys #{}
    :seon.execution/predicate-functions #{}
    :seon.execution/attributes #{}}
   :seon.execution/capability-manifest
   {:seon.execution/required-bindings #{}
    :seon.execution/remote-bindings #{}
    :seon.execution/effects {}
    :seon.execution/native-leaves #{}
    :seon.execution/artifact-exports #{}}
   :seon.execution/unresolved []})

(deftest execution-plan-disposition-fails-before-dispatch-or-releases-for-handoff
  (let [inventories
        {:jvm {:seon.execution.inventory/tier :jvm
               :seon.execution.inventory/bindings #{}
               :seon.execution.inventory/remote-bindings #{}
               :seon.execution.inventory/pure-bindings #{}
               :seon.execution.inventory/digest "jvm"}
         :bun {:seon.execution.inventory/tier :bun
               :seon.execution.inventory/bindings #{"bun/leaf"}
               :seon.execution.inventory/remote-bindings #{}
               :seon.execution.inventory/pure-bindings #{}
               :seon.execution.inventory/digest "bun"}}
        release
        (disposition-fixture
         (merge empty-manifests
                {:seon.execution/placement :constrained
                 :seon.execution/eligible-tiers #{:bun}
                 :seon.execution/selected-tier :bun})
         inventories)
        steering
        (disposition-fixture
         (merge empty-manifests
                {:seon.execution/placement :unplannable
                 :seon.execution/eligible-tiers #{}
                 :seon.execution/unresolved
                 [{:seon.execution/reason :unresolved-symbol
                   :seon.execution/target "unknown/call"
                   :seon.execution/steering
                   "Define or install the unresolved function."}]})
         inventories)]
    (is (= :release (:seon.agent.driver/disposition release)))
    (is (= :bun (:seon.execution/selected-tier release)))
    (is (= :steering (:seon.agent.driver/disposition steering)))
    (is (= :agent
           (get-in steering
                   [:seon.agent.driver/error :seon.error/kind])))
    (is (= #{:jvm :bun}
           (get-in steering
                   [:seon.agent.driver/error :seon.error/data
                    :seon.execution/inspected-tiers])))))

(deftest exact-plan-missing-requirement-is-a-core-fault
  (let [result
        (disposition-fixture
         (-> empty-manifests
             (assoc :seon.execution/placement :constrained
                    :seon.execution/eligible-tiers #{:jvm}
                    :seon.execution/selected-tier :jvm)
             (assoc-in [:seon.execution/capability-manifest
                        :seon.execution/required-bindings]
                       #{"missing/leaf"}))
         {:jvm {:seon.execution.inventory/tier :jvm
                :seon.execution.inventory/bindings #{}
                :seon.execution.inventory/remote-bindings #{}
                :seon.execution.inventory/pure-bindings #{}
                :seon.execution.inventory/digest "jvm"}})]
    (is (= :core-fault (:seon.agent.driver/disposition result)))
    (is (= :core-bug
           (get-in result [:seon.agent.driver/error :seon.error/kind])))
    (is (= #{"missing/leaf"}
           (get-in result
                   [:seon.agent.driver/error :seon.error/data
                    :seon.execution/missing-capability-leaves])))))

(deftest recovery-preserves-live-custody-and-steals-only-expired-custody
  (let [claimed (assoc base-run
                       :seon.agent.run/claimant claimant-a
                       :seon.agent.run/claim-epoch 1)]
    (is (= :preserve
           (recovery.core/disposition claimed claimant-b
                                      #inst "2026-07-23T01:00:00.500-00:00"
                                      1000 true)))
    (is (= :steal
           (recovery.core/disposition claimed claimant-b now 1000 true)))
    (is (= :repair
           (recovery.core/disposition claimed claimant-b now 1000 false)))))

(deftest retry-and-fallback-policy-is-portable
  (let [transport {:seon.ai/error {:seon.ai/transport? true}}
        limited {:seon.ai/error {:seon.ai/status 429}}
        server {:seon.ai/error {:seon.ai/status 503}}
        client {:seon.ai/error {:seon.ai/status 400}}
        timeout {:seon.ai/error {:seon.ai/timeout? true}}]
    (is (turn.core/llm-retryable? transport))
    (is (turn.core/llm-retryable? limited))
    (is (turn.core/llm-retryable? server))
    (is (not (turn.core/llm-retryable? client)))
    (is (turn.core/llm-fallback-eligible? transport))
    (is (turn.core/llm-fallback-eligible? timeout))
    (is (not (turn.core/llm-fallback-eligible? client)))))

(deftest retry-bounds-come-from-the-acquired-config-projection
  (let [configuration
        {:seon.config.llm-retry/base-wait-ms 1
         :seon.config.llm-retry/growth-factor 2.0
         :seon.config.llm-retry/jitter-fraction 0.0
         :seon.config.llm-retry/maximum-wait-ms 7
         :seon.config.llm-retry/maximum-total-wait-ms 28
         :seon.config.llm-retry/default-retries 4}
        waits (vec (turn.core/llm-retry-strategy {} configuration))]
    (is (= 4 (count waits)))
    (is (every? #(<= % 7) waits))
    (is (<= (reduce + waits) 28))
    (is (= 2
           (count
            (turn.core/llm-retry-strategy
             {:seon.ai/agent-max-retries 3} configuration 1))))))

(deftest no-progress-streak-is-derived-from-trailing-durable-turns
  (let [observation
        [{:seon.eval/source "(+ 1 2)"
          :seon.eval/status :done
          :seon.eval/ok? true
          :seon.eval/result-edn "3"
          :seon.eval/progress? false}]
        repeated
        (assoc base-run
               :seon.agent.turn/_run
               [{:db/id 1 :seon.agent.turn/status :done
                 :seon.agent.turn/evals observation}
                {:db/id 2 :seon.agent.turn/status :done
                 :seon.agent.turn/evals observation}
                {:db/id 3 :seon.agent.turn/status :done
                 :seon.agent.turn/evals observation}])
        progressed
        (update-in repeated
                   [:seon.agent.turn/_run 2 :seon.agent.turn/evals 0]
                   assoc :seon.eval/progress? true)]
    (is (= 3 (loop.core/no-progress-streak repeated)))
    (is (zero? (loop.core/no-progress-streak progressed)))))

#?(:clj
   (deftest run-accounting-is-orthogonal-to-wire-streaming
     (let [run
           (assoc base-run
                  :seon.agent.run/turn-limit 2
                  :seon.agent.turn/_run
                  [{:seon.agent.turn/status :done
                    :seon.agent.turn/llm-attempts
                    [{:seon.ai.attempt/ordinal 0
                      :seon.ai.attempt/outcome :success
                      :seon.ai.attempt/reply-evaluation :first-form}]
                    :seon.agent.turn/evals
                    [{:seon.eval/id "eval-1"}
                     {:seon.eval/id "eval-2"}]}])
           close-reason #'driver/close-reason]
       (is (= :turn-limit
              (close-reason run {} now)))
       (is (nil?
            (close-reason
             (assoc-in
              run
              [:seon.agent.turn/_run 0
               :seon.agent.turn/llm-attempts 0
               :seon.ai.attempt/reply-evaluation]
              :batch)
             {}
             now))))))
