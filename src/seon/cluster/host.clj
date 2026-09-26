(ns seon.cluster.host
  "A branch agent hosted in its branch's own execution.

  `host!` composes existing owners: `acquire-context!` acquires the named
  branch into the child roster the cluster's roster records, `creation-tx`
  creates the agent on that branch, and the ordinary `armer-step` runs in a
  graph of its own on the execution's roster, owning the branch's
  `wake/route!` listener. The roster holds the live objects under `::host`;
  admission is the agent row's `:seon.agent/admission-closed-tx`, which the
  run writer checks. `retire!` closes admission in one transaction and stops
  through `seon.cluster/disarm-agents!`, unchanged."
  (:require [clojure.core.async :as async]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as flow]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn- agent-row
  "The agent's row on `database` with its branch and admission, or nil when absent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:maybe [:map [:db/id :int]]]]}
  [database agent-id]
  (let [row (db/pull database [:db/id :seon.agent/branch :seon.agent/admission-closed-tx]
                     [:seon.agent/id agent-id])]
    (when (:seon.error/at row) (throw (ex-info (str (:seon.error/message row)) row)))
    (when (:db/id row) row)))

(defn- start!
  "Create the agent on the execution's branch when absent, then start the
  branch's armer graph; the armer's init registers the route listener."
  {:malli/schema [:=> [:cat ::request :seon.agent/execution-handle] ::host]}
  [{cluster-handle :seon.turn.loop/cluster agent-id :seon.agent/id namespace-name :seon.ns/name}
   execution]
  (let [connection (:seon.db/connection execution)
        branch (:seon.agent/branch execution)
        roster (:seon.agent/routing execution)
        existing (agent-row @connection agent-id)
        refuse (fn [message member offending]
                 (throw (ex-info message {:seon.error/message message :seon.error/member member
                                          :seon.error/offending offending :seon.agent/id agent-id})))]
    (cond
      (:seon.agent/admission-closed-tx existing)
      (refuse "The hosted agent's admission is closed." :seon.agent/admission-closed-tx agent-id)
      (and existing (not= branch (:seon.agent/branch existing)))
      (refuse "The agent's row names another branch than the one hosting it."
              :seon.agent/branch (:seon.agent/branch existing))
      (nil? existing)
      (let [result (db/call-with-custody {}
                     #(db/transact! connection
                        (agent/creation-tx {:seon.agent/id agent-id :seon.ns/name namespace-name
                                            :seon.cluster/name (:seon.cluster/name cluster-handle)})))]
        (when-not (:db-after result) (throw (ex-info "Hosted agent creation refused." result)))))
    (let [armer-channel (async/chan (async/sliding-buffer 1))
          handle (merge cluster-handle
                        (select-keys execution [:seon.db/connection :seon.sci.eval/ctx :seon.sci.eval/base-ctx
                                                :seon.env/environment :seon.agent/routing :seon.agent/branch])
                        {:seon.sci.eval/projection-state
                         (:seon.sci.eval/projection-state (:seon.sci.eval/ctx execution))
                         :seon.cluster.wake/channel armer-channel
                         :seon.turn.loop/stream-channel (async/chan (async/sliding-buffer 1))
                         :seon.turn.loop/completion (async/promise-chan)})
          route {:seon.cluster.wake/connection connection
                 :seon.cluster.wake/channels (fn [] (agent/channels roster))
                 :seon.cluster.wake/fenced? (fn [eid channel] (agent/fenced-route? roster eid channel))
                 :seon.cluster.wake/armer-channel armer-channel
                 :seon.cluster.wake/key :seon.agent/route}
          {graph :seon.flow/graph executor :seon.flow/executor}
          (flow/start-graph!
           {:seon.flow/projection-state (:seon.sci.eval/projection-state handle)
            :seon.flow/graph-definition
            {:procs {:seon.agent/armer
                     {:proc (flow/var-process
                             #'agent/armer-step :io
                             (env/carry {:seon.turn.loop/cluster handle :seon.agent/routing roster
                                         :seon.cluster.wake/route route}
                                        (env/of handle)))}}
             :conns []}})]
      {:seon.turn.loop/cluster handle :seon.agent/routing roster :seon.agent/id agent-id
       ::execution execution :seon.flow/graph graph :seon.flow/executor executor})))

(defn host!
  "Host `agent-id` on the existing `branch`, once per branch execution.

  Concurrent callers share one start: the execution's roster reserves `::host`
  with a delay, as `arm!` reserves an arming. A failed start clears its claim
  and rethrows whole. Answers the host value `retire!` and
  `seon.cluster.agent/evaluate-source!` take (its `:seon.turn.loop/cluster`
  and `:seon.agent/routing`)."
  {:malli/schema [:=> [:cat ::request] ::host]}
  [{cluster-handle :seon.turn.loop/cluster branch :seon.agent/branch :as request}]
  (let [execution (agent/acquire-context! cluster-handle nil {:seon.agent/branch branch})
        roster (:seon.agent/routing execution)
        claim (::host (swap! roster update ::host #(or % (delay (start! request execution)))))]
    (try @claim
         (catch Throwable failure
           (swap! roster (fn [held] (cond-> held (identical? claim (::host held)) (dissoc ::host))))
           (throw failure)))))

(defn hosted
  "The host running on `branch` under `cluster-handle`, or nil when none is."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/branch] [:maybe ::host]]}
  [cluster-handle branch]
  (some-> (get-in @(:seon.agent/routing cluster-handle) [:seon.agent/children branch])
          deref ::host deref))

(defn retire!
  "Retire a host: close its agent's admission in one transaction, then stop.

  After the admission commit the run writer refuses every later run for the
  agent by name; runs that committed first are joined by
  `seon.cluster/disarm-agents!` (armer quiescence removes the route listener,
  agent graphs join, the armer graph joins, all under one deadline). A missed
  join throws with admission closed and the execution held, so a retry after
  exit completes. Then the execution is released."
  {:malli/schema [:=> [:cat ::host] :nil]}
  [{handle :seon.turn.loop/cluster roster :seon.agent/routing agent-id :seon.agent/id
    execution ::execution :as host}]
  (let [connection (:seon.db/connection handle)
        row (agent-row @connection agent-id)]
    (when (and row (not (:seon.agent/admission-closed-tx row)))
      (let [result (db/call-with-custody {}
                     #(db/transact! connection
                        [[:db/add (:db/id row) :seon.agent/admission-closed-tx "datomic.tx"]]))]
        (when-not (:db-after result) (throw (ex-info "Closing the host's admission was refused." result)))))
    (cluster/disarm-agents! host)
    (swap! roster dissoc ::host)
    (agent/release-context! execution)
    nil))
