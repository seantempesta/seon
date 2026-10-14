(ns seon.turn.world
  "The one world an entrant hands the one REPL path (`:seon.batch/world`):
  one constructor, `world`, for agents and test members, `with-world` to re-establish it on any
  thread, where its run is recorded, and the fault stage that records a
  diagnostic in it. Every stage reads the world from its request."
  (:require [clojure.test.check.generators :as gen]
            [seon.batch]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.schema :as schema]))

;; `seon.batch` requires nothing of Seon, so the call sink's predicate and
;; generator are registered by the world's owner.
(schema/register-core-predicate! 'seon.batch/sink? seon.batch/sink?)

(schema/register-core-predicate! 'seon.batch/owned? seon.batch/owned?)
(schema/register-core-predicate! 'seon.batch/stamped-clock? seon.batch/stamped-clock?)

(def sink-generator
  "Fresh, empty call sinks."
  (gen/fmap (fn [_] (seon.batch/sink)) (gen/return nil)))

(def owned-generator
  "Fresh owned-work counts at zero."
  (gen/fmap (fn [_] (java.util.concurrent.atomic.AtomicLong.)) (gen/return nil)))

;;; ---------------------------------------------------------------------------

(defn- execution-environment
  "The environment of an execution handed none (a fixture's): its cluster and
  connection. Call preparation supplies a world's members only in an
  evaluation that has an environment (`seon.instrument/calling-environment`),
  so a world without one silently supplied nothing."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name :seon.db/connection] :seon.env/environment]}
  [cluster conn]
  (env/environment {:seon.boot/cluster-name cluster :seon.db/connection conn}))

(def ^:private carried
  "The request keys a world carries as given."
  [:seon.boot/cluster-name :seon.db/connection :seon.sci.eval/ctx
   :seon.db.process/id :seon.agent/id :seon.agent/branch :seon.turn/id :seon.task/id
   :seon.render/profile :seon.batch/sink :seon.sci.eval/time-limit-ms :seon.batch/settlement
   :seon.agent/context-source])

(defn- admission
  "The admission caps and evaluation time limit of `cluster`'s effective
  configuration on `conn`'s current value, read once per world; a refused
  configuration throws its error."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name :seon.db/connection]
                  [:map [:seon.sci.admit/caps :seon.sci.admit/caps]
                   [:seon.sci.eval/time-limit-ms :seon.sci.eval/time-limit-ms]]]}
  [cluster conn]
  (let [effective (config/effective (db/db conn) cluster)
        caps (config/result-caps effective)]
    (when (:seon.error/at caps)
      (throw (ex-info (:seon.error/message caps) caps)))
    {:seon.sci.admit/caps caps
     :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms effective)}))

(defn world
  "The one world an evaluation runs in, an agent's or a test member's: its
  identity, custody (the connection and the writer), its environment with that
  environment's store and routing, its SCI context (the caller's fork) and
  its call sink, as the request gives them, and its cluster's admission caps
  and evaluation time limit (a given time limit wins). It holds no database value. An agent's world is this value plus its Flow graph and task facts; a
  test member's is this value alone."
  {:malli/schema [:=> [:cat :seon.batch/world-request] :seon.batch/world-base]}
  [{conn :seon.db/connection cluster :seon.boot/cluster-name environment :seon.env/environment
    writer :seon.db/writer :as request}]
  (let [environment (or environment (execution-environment cluster conn))]
    (merge (admission cluster conn)
           (select-keys request carried)
           {:seon.db/writer (or writer db/transact!)
            :seon.env/environment environment}
           (seon.batch/environment-members environment))))

(defn basis
  "The world's connection's current value: the value a batch starts from
  (`:seon.batch/basis`). No world holds a database value (owner ruling
  2026-09-28): a caller that needs one reads its connection."
  {:malli/schema [:=> [:cat :seon.batch/world] :seon.db/database-value]}
  [{conn :seon.db/connection}]
  (db/db conn))

(defn with-world
  "Run `f` with the world's bindings re-established on this thread: its
  custody (the connection, when it has one) for the elided seon.db arities,
  which read its current value, and the running evaluation's observation
  scope (`:seon.batch/scope`, the observer's bindings). A body moved to
  another thread (a compute-pool hop) carries the world as a value and calls
  this; nothing is inherited from the submitting thread."
  {:malli/schema [:=> [:cat :seon.batch/world [:=> [:cat] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "Any body runs in the world: its value passes through unchanged." :gen/elements [nil false 0 "" :k [] {}]}]]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "Any body runs in the world: its value passes through unchanged." :gen/elements [nil false 0 "" :k [] {}]}]]}
  [world f]
  (db/with-branch (select-keys world [:seon.db/connection])
                        #(with-bindings (cond-> (merge (:seon.batch/scope world {})
                                                       {#'seon.batch/*world* world})
                                          ;; a form's reads land in its own sink
                                          (:seon.batch/reads world)
                                          (assoc #'db/*read-evidence-sink* (:seon.batch/reads world)))
                           (f))))


(defn settlement-connection
  "Where the world's run is recorded: its settlement connection (a test agent
  records on its requester's branch), else its own connection."
  {:malli/schema [:=> [:cat :seon.batch/world] [:maybe :seon.db/connection]]}
  [world]
  (or (get-in world [:seon.batch/settlement :seon.db/connection]) (:seon.db/connection world)))

(defn record-fault
  "One diagnostic recorded as a core fault by its owner (`seon.fault/fault!`)
  in the world's environment, attributed to the world's agent, so it is
  delivered to whoever can act on it. Answers the receipt. A world without
  an environment or process has nowhere to record: that is the stage's own
  failure, one more diagnostic."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/diagnostic] :seon.fault/recorded]}
  [{{environment :seon.env/environment process :seon.db.process/id agent-id :seon.agent/id} :seon.batch/world}
   {stage :seon.batch/stage message :seon.error/message :as diagnostic}]
  (when-not (and environment process)
    (throw (ex-info "The world has no environment and process to record a fault in."
                    {:seon.batch/diagnostic diagnostic})))
  (fault/fault! environment (or (:seon.error/throwable diagnostic)
                                (ex-info message (dissoc diagnostic :seon.error/throwable)))
                (cond-> {:seon.error/layer (keyword "seon.batch" (name stage))
                         :seon.error/operation `seon.batch/evaluate-batch
                         :seon.db.process/id process}
                  agent-id (assoc :seon.agent/id agent-id))))

