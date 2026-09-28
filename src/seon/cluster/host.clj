(ns seon.cluster.host
  "A branch agent hosted in its branch's own execution.

  `host!` composes existing owners: `acquire-context!` acquires the named
  branch into the child roster the cluster's roster records, `creation-tx`
  creates the agent on the cluster (naming its home branch) and on that
  branch, and the ordinary `armer-step` runs in a
  graph of its own on the execution's roster, owning the branch's
  `wake/route!` listener. The roster holds the live objects under `::host`;
  admission is the agent row's `:seon.agent/admission-closed-tx`, which the
  run writer checks. `retire!` closes admission on both rows and stops
  through `seon.cluster/disarm-agents!`, unchanged."
  (:require [clojure.core.async :as async]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal]
            [seon.flow :as flow]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn- agent-entity
  "The agent's entity on `database` with its branch and admission, nil when
  absent, or the read's refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :nil [:map [:db/id :int]] :seon.db/invalid-read-error]]}
  [database agent-id]
  (let [entity (db/pull database [:db/id :seon.agent/branch :seon.agent/admission-closed-tx]
                     [:seon.agent/id agent-id])]
    (cond (:seon.error/at entity) entity
          (:db/id entity) entity)))

(defn- refusal
  "The flat refusal a host decision answers."
  {:malli/schema [:=> [:cat :qualified-symbol :string :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "The refused value a host decision names: a transaction id, a branch or an agent id." :gen/elements [1 :db "agent"]}] :seon.agent/id] :seon.cluster.host/refused-error]}
  [operation message member offending agent-id]
  (seon.error.refusal/diagnostic
   (java.util.Date.) :seon.cluster/host operation
   {:seon.error/message message :seon.error/member member
    :seon.error/offending offending :seon.agent/id agent-id ::refused-agent agent-id}))

(defn home-call
  "Admit `agent-id` as the agent homed on `branch`, decided by this writer.

  Invoked as `[:db.fn/call #'home-call ...]` on the cluster and on the branch,
  so the decision reads the in-transaction database: two hosts racing for one
  identity serialize here, not at a pre-read. A row whose admission is closed,
  or which names another home branch, refuses by name; the matching row stays
  as it is; an absent one is created by `creation-tx`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.agent/branch
                       :seon.ns/name :seon.cluster/name]
                  :seon.store/transaction-data]}
  [database agent-id branch namespace-name cluster-name]
  (let [existing (agent-entity database agent-id)
        ;; a transaction function's throw is its writer's refusal
        refuse! #(throw (ex-info (:seon.error/message %) %))]
    (cond
      (:seon.error/at existing) (refuse! existing)
      (:seon.agent/admission-closed-tx existing)
      (refuse! (refusal `home-call "The hosted agent's admission is closed."
                        :seon.agent/admission-closed-tx agent-id agent-id))
      (and existing (not= branch (:seon.agent/branch existing)))
      (refuse! (refusal `home-call
                        (str "Agent " agent-id " is homed on " (:seon.agent/branch existing)
                             ", not on " branch ".")
                        :seon.agent/branch (:seon.agent/branch existing) agent-id))
      existing []
      :else (agent/creation-tx {:seon.agent/id agent-id :seon.ns/name namespace-name
                                :seon.agent/branch branch :seon.cluster/name cluster-name}))))

(defn- start!
  "Admit the agent as homed on the execution's branch, on the cluster and on
  that branch (each writer decides, `home-call`), then start the branch's
  armer graph; the armer's init registers the route listener."
  {:malli/schema [:=> [:cat ::request :seon.agent/execution-handle]
                  [:or ::host :seon.db/transaction-refused-error]]}
  [{cluster-handle :seon.turn.loop/cluster agent-id :seon.agent/id namespace-name :seon.ns/name}
   execution]
  (let [connection (:seon.db/connection execution)
        branch (:seon.agent/branch execution)
        roster (:seon.agent/routing execution)
        ;; The agent is an entity of the cluster naming its home branch, and of that
        ;; branch: a message to or from it resolves on either, and root lists it.
        ;; The first writer's refusal is the answer; nothing starts.
        refused (some (fn [target]
                        (let [result (db/call-with-custody {}
                                       #(db/transact! target [[:db.fn/call #'home-call agent-id branch namespace-name
                                                               (:seon.cluster/name cluster-handle)]]))]
                          (when-not (:db-after result) result)))
                      [(:seon.db/connection cluster-handle) connection])]
   (if refused
    refused
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
       ::execution execution ::parent (:seon.db/connection cluster-handle)
       :seon.flow/graph graph :seon.flow/executor executor}))))

(defn hosted
  "The host running on `branch` under `cluster-handle`, or nil when none is."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/branch] [:maybe ::host]]}
  [cluster-handle branch]
  (some-> (get-in @(:seon.agent/routing cluster-handle) [:seon.agent/children branch])
          deref ::host deref))

(defn host!
  "Host `agent-id` on the existing `branch`, once per branch execution.

  Concurrent callers share one start: the execution's roster reserves `::host`
  with a delay, as `arm!` reserves an arming. A failed start clears its claim
  and rethrows whole; a refused admission clears its claim and answers the
  writer's refusal. A branch hosts one agent: a request naming another agent
  than the one running there answers `::refused-error` by name. Answers the
  host value `retire!` and `seon.cluster.agent/evaluate-source!` take (its
  `:seon.turn.loop/cluster` and `:seon.agent/routing`)."
  {:malli/schema [:=> [:cat ::request]
                  [:or ::host ::refused-error :seon.db/transaction-refused-error]]}
  [{cluster-handle :seon.turn.loop/cluster branch :seon.agent/branch agent-id :seon.agent/id :as request}]
  ;; A running host answers from the roster; reacquiring the branch per call
  ;; measured 409 ms warm against 240 ms for the first host (default, 2026-09-26).
  (let [host (or (hosted cluster-handle branch)
                 (let [execution (agent/acquire-context! cluster-handle nil {:seon.agent/branch branch})
                       roster (:seon.agent/routing execution)
                       claim (::host (swap! roster update ::host #(or % (delay (start! request execution)))))]
                   ;; A refused start holds nothing: its claim goes, and so does the
                   ;; execution acquired for it (a retired agent's branch otherwise
                   ;; keeps a connection its retirement cannot see).
                   (letfn [(unclaim! [] (identical? claim (::host (first (swap-vals! roster (fn [held] (cond-> held (identical? claim (::host held)) (dissoc ::host))))))))]
                     (try (let [started @claim]
                            (when (and (:seon.error/at started) (unclaim!))
                              (agent/release-context! execution))
                            started)
                          (catch Throwable failure
                            (when (unclaim!)
                              (try (agent/release-context! execution)
                                   (catch Throwable cleanup (.addSuppressed failure cleanup))))
                            (throw failure))))))]
    (cond
      (:seon.error/at host) host
      (= agent-id (:seon.agent/id host)) host
      :else (refusal `host! (str "Branch " branch " hosts " (:seon.agent/id host) ", not " agent-id ".")
                     :seon.agent/id (:seon.agent/id host) agent-id))))

(defn retire!
  "Retire a host: close its agent's admission, on its branch row and then its
  cluster row (one transaction each), then stop.

  After the admission commit the run writer refuses every later run for the
  agent by name; runs that committed first are joined by
  `seon.cluster/disarm-agents!` (armer quiescence removes the route listener,
  agent graphs join, the armer graph joins, all under one deadline). A missed
  join is answered (the await owner's timeout naming the missing exit) with
  admission closed and the execution held, so a retry after exit completes.
  Then the execution is released. A refused read or a refused admission close
  is answered and nothing further happens."
  {:malli/schema [:=> [:cat ::host] [:or :nil :seon.db/invalid-read-error :seon.db/transaction-refused-error
                                     :seon.await/timeout-error]]}
  [{handle :seon.turn.loop/cluster roster :seon.agent/routing agent-id :seon.agent/id
    execution ::execution :as host}]
  ;; The branch entity first: its run writer refuses every later run from then
  ;; on. The cluster entity then records that this host is closed.
  (or (some (fn [connection]
              (let [entity (agent-entity @connection agent-id)]
                (cond
                  (:seon.error/at entity) entity
                  (and entity (not (:seon.agent/admission-closed-tx entity)))
                  (let [result (db/call-with-custody {}
                                 #(db/transact! connection
                                    [[:db/add (:db/id entity) :seon.agent/admission-closed-tx "datomic.tx"]]))]
                    (when-not (:db-after result) result)))))
            (keep identity [(:seon.db/connection handle) (::parent host)]))
      (cluster/disarm-agents! host)
      (do (swap! roster dissoc ::host)
          (agent/release-context! execution)
          nil)))
