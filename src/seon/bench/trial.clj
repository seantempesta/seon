(ns seon.bench.trial
  "Extracted child namespace of `seon.bench.cluster`."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.bench.proxy :as proxy]
            [seon.model :as model]
            [seon.store :refer [rm-rf!]]
            [seon.bench.helpers :as bh]
            [jolt.fs :as fs]
            [jolt.process :as proc]
            [clojure.core.async.flow :as flow]))

(defn trial-ids
  "Derives `{:id :branch :agent-id}` keywords for `task` at 0-based `idx`."
  {:malli/schema [:=> [:cat :int :map] [:map [:id :keyword] [:branch :keyword] [:agent-id :keyword]]], :cost/ticks 600}
  [idx task]
  (let [trial-id (or (:id task) (keyword "trial" (str "t" (inc (long idx)))))]
    {:id trial-id
     :branch (or (:branch task) (when (:chat-agent task) :branch/root) (keyword "branch" (str "trial-" (name trial-id))))
     :agent-id (or (:chat-agent task) (:agent-id task) (keyword "bench" (name trial-id)))}))

(defn trial-instance-opts
  "Merges a trial's per-task overrides over run-level `opts` and boot defaults."
  {:malli/schema [:=> [:cat :keyword :map :map] :map] :cost/ticks 400}
  [trial-id task opts]
  {:id trial-id :repo-dir (or (:repo-dir task) (:repo-dir opts))
   :bin-path (or (:bin-path task) (:bin-path opts)) :seed-store (or (:seed-store task) (:seed-store opts))
   :model-base-url (or (:model-base-url task) (:eff-base-url opts)) :model (or (:model task) (:model opts))
   :model-stub? (if (contains? task :model-stub?) (:model-stub? task) (:model-stub? opts))
   :max-os-procs (or (:max-os-procs task) (:max-os-procs opts) 2)
   :max-test-workers (or (:max-test-workers task) (:max-test-workers opts) 2)
   :timeout-ms (or (:boot-timeout-ms opts) 60000)})

(defn trial-task-plan
  "Resolves a trial inline task map, task-entity id, scope, goal, and body text."
  {:malli/schema [:=> [:cat :map] [:map [:task-map [:maybe :map]] [:task-id [:maybe :keyword]]
                                   [:scope [:maybe :any]] [:goal :string] [:body [:maybe :string]]]] :cost/ticks 300}
  [task]
  (let [task-map (when (map? (:task task)) (:task task))
        task-id (or (:id task-map) (when (keyword? (:task task)) (:task task)))]
    (cond-> {:task-map task-map :task-id task-id :scope (or (:scope task) (:scope task-map))
             :goal (str (or (:goal task) (:title task-map) "Complete assigned task."))
             :body (or (:body task) (when task-id "Work your assigned task, verify your changes pass tests and health, then reply concisely with your summary."))}
      (map? (:config task)) (assoc :config (:config task))
      (some? (:auto-merge? task)) (assoc :auto-merge? (boolean (:auto-merge? task))))))

(defn trial-spawn-op
  "Builds the `/op {:op :spawn-agent}` payload for a trial worker."
  {:malli/schema [:=> [:cat :keyword :keyword [:map [:goal :string]]] :map]
   :cost/ticks 200}
  [agent-id branch {:keys [goal scope task-id config auto-merge?]}]
  (cond-> {:op :spawn-agent :id agent-id :branch branch :goal goal}
    scope (assoc :scope scope)
    task-id (assoc :task task-id)
    (map? config) (assoc :config config)
    (some? auto-merge?) (assoc :auto-merge? (boolean auto-merge?))))

(defn trial-message-op
  "Builds the `/op {:op :send-message}` payload carrying the trial body, or nil."
  {:malli/schema [:=> [:cat :keyword [:maybe :string]] [:maybe :map]]
   :cost/ticks 150}
  [agent-id body]
  (when body
    {:op :send-message :from :user :to agent-id :type :chat :body (str body)}))

(defn trial-progress
  "Builds the live progress snapshot for one trial from a `/op {:op :status}` poll."
  {:malli/schema [:=> [:cat :keyword [:map [:agents [:maybe [:sequential :map]]]] [:maybe :map]]
                  [:map [:status :keyword] [:evals :int] [:alive? :boolean]]] :cost/ticks 300}
  [agent-id status-msg trace]
  (let [agent-row (some #(when (= agent-id (:id %)) %) (:agents status-msg))]
    (cond-> {:status (or (:status agent-row) :running) :evals (long (or (:evals agent-row) 0)) :alive? true}
      (:last-snippet trace) (assoc :last-snippet (:last-snippet trace))
      (:last-error trace) (assoc :last-error (:last-error trace)))))

(defn callable-specialist-id?
  "Returns true when `agent-id` identifies a callable subsystem specialist (`:ns/*` or `:specialist/*`)."
  {:malli/schema [:=> [:cat :any] :boolean] :cost/ticks 200}
  [agent-id]
  (boolean (and (keyword? agent-id)
                (contains? #{"ns" "specialist"} (namespace agent-id)))))

(defn- settled-call-count [final-trace]
  (if (and (map? final-trace) (contains? final-trace :settled-delegated-calls))
    (long (or (:settled-delegated-calls final-trace) 0))
    (long (or (:delegated-calls final-trace) 0))))

(defn trial-outcome
  "Computes a trial's merge/ok flags from its per-op responses, task options, health, verification, and trace."
  {:malli/schema [:=> [:cat [:map [:task :map] [:ag-snap [:maybe :map]] [:eval-res [:maybe :map]]
                             [:agent-res [:maybe :map]] [:test-res [:maybe :map]] [:merge-res [:maybe :map]]
                             [:final-trace {:optional true} [:maybe :map]]
                             [:health-res {:optional true} [:maybe :map]]
                             [:verify-res {:optional true} [:maybe :map]]]]
                  [:map [:already-merged? :boolean] [:merged? :boolean] [:require-merge? :boolean]
                   [:halted-not-merged? :boolean] [:ok? :boolean]]] :cost/ticks 600}
  [{:keys [task ag-snap eval-res agent-res test-res merge-res final-trace health-res verify-res]}]
  (let [already-merged? (= :merged (:status ag-snap))
        empty-merge? (boolean (and (not already-merged?) (:ok? merge-res) (contains? merge-res :touched-paths) (empty? (:touched-paths merge-res))))
        merged? (boolean (or already-merged? (and (:ok? merge-res) (not empty-merge?))))
        require-merge? (if (contains? task :require-merge?) (:require-merge? task) (boolean (or (:task task) (:merge? task))))
        halted-not-merged? (and require-merge? (not merged?))
        min-delegated (long (or (:min-delegated-calls task) 0))
        delegated-ok? (or (not (pos? min-delegated)) (>= (settled-call-count final-trace) min-delegated))
        reply-ok? (or (not (:chat-agent task)) (not (str/blank? (str (or (:last-reply final-trace) "")))))
        health-ok? (if (map? health-res) (boolean (:ok? health-res)) true)
        verify-ok? (if (map? verify-res) (boolean (:ok? verify-res)) true)]
    {:already-merged? already-merged? :merged? merged? :require-merge? require-merge?
     :halted-not-merged? halted-not-merged?
     :ok? (boolean (and (if eval-res (:ok? eval-res) true)
                        (if agent-res (get-in agent-res [:wait :ok?]) true)
                        (if test-res (:ok? test-res) true)
                        (if merge-res (:ok? merge-res) true)
                        (not halted-not-merged?) delegated-ok? reply-ok? health-ok? verify-ok?))}))

(defn trial-failure
  {:malli/schema [:=> [:cat [:map [:timeout-ms :int] [:agent-res [:maybe :map]] [:test-res [:maybe :map]]
                             [:merge-res [:maybe :map]] [:ag-snap [:maybe :map]] [:final-trace [:maybe :map]]
                             [:health-res [:maybe :map]] [:halted-not-merged? :boolean]
                             [:task {:optional true} [:maybe :map]] [:verify-res {:optional true} [:maybe :map]]]] [:maybe :string]] :cost/ticks 15000000}
  [m] (bh/trial-failure m))

(defn trial-telemetry
  {:malli/schema [:=> [:cat [:maybe :keyword] [:maybe :map]] :map] :cost/ticks 400}
  [category trace] (bh/trial-telemetry category trace))

(defn trial-result
  "Assembles one trial result map from its op responses and friction trace."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 400}
  [{:keys [id category arm pair-id port boot-ms elapsed-ms outcome ag-snap eval-res agent-res test-res merge-res
           health-res verify-res final-trace failure]}]
  (let [timed-out? (boolean (= :timeout (get-in agent-res [:wait :error])))
        status (cond (:ok? outcome) (if (:merged? outcome) :merged :passed) timed-out? :timeout :else :failed)]
    (merge (cond-> {:id id :status status :timed-out? timed-out? :port port :boot-ms boot-ms :elapsed-ms elapsed-ms
                    :ok? (boolean (:ok? outcome)) :merged? (boolean (:merged? outcome)) :agent-status (:status ag-snap)
                    :evals (long (or (:evals final-trace) (:evals ag-snap) 0)) :eval eval-res :agent agent-res
                    :test test-res :merge merge-res :health-ok? (boolean (:ok? health-res)) :verify-ok? (if verify-res (boolean (:ok? verify-res)) true) :verify-reason (:reason verify-res)
                    :last-snippet (:last-snippet final-trace) :last-error failure}
             arm (assoc :arm arm)
             pair-id (assoc :pair-id pair-id))
           (trial-telemetry category final-trace))))

(defn result->stored-trial
  {:malli/schema [:=> [:cat :map] :map], :seon.gen/skip true, :cost/ticks 500000}
  [result-row]
  (let [row-status (cond (:ok? result-row) (if (:merged? result-row) :merged :passed)
                         (or (:timed-out? result-row) (= :timeout (:status result-row))) :timeout
                         :else :failed)
        err-text (or (:last-error result-row) (:error result-row))
        telemetry-keys (into [:category :arm :pair-id :turn-overrun? :child-merged? :total-kv-hit-pct
                              :error-samples :failing-tests :eval-trace :verify-ok? :verify-reason]
                             (map second bh/telemetry-numeric-pairs))]
    (merge
      (cond-> {:id (:id result-row) :status row-status :port (:port result-row)
               :evals (long (or (:evals result-row) 0)) :elapsed-ms (long (or (:elapsed-ms result-row) 0))
               :boot-ms (long (or (:boot-ms result-row) 0)) :merged? (boolean (:merged? result-row))
               :health-ok? (boolean (:health-ok? result-row)) :alive? false}
        (:last-snippet result-row) (assoc :last-snippet (:last-snippet result-row))
        err-text (assoc :last-error (str err-text)))
      (select-keys result-row telemetry-keys))))
