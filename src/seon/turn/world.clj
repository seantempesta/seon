(ns seon.turn.world
  "The one world an entrant hands the one REPL path (`:seon.batch/world`):
  one constructor per entrant kind, `with-world` to re-establish it on any
  thread, where its run is recorded, and the fault stage that records a
  diagnostic in it. Every stage reads the world from its request."
  (:require [clojure.test.check.generators :as gen]
            [seon.batch]
            [seon.db :as db]
            [seon.fault :as fault]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]))

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

(defn agent-world
  "A Seon agent's world from its acquired execution (`agent/acquire-context!`):
  its cluster and branch, its connection, the writer, its SCI context forked
  onto that connection's current value, its id and
  task, render profile and settlement inputs. An MCP SCI run's world is this
  one (MCP only supplies the agent id and transport)."
  {:malli/schema [:=> [:cat :map :map] :seon.batch/world]}
  [{conn :seon.db/connection base :seon.sci.eval/base-ctx cluster :seon.cluster/name branch :seon.agent/branch
    environment :seon.env/environment}
   {agent-id :seon.agent/id turn-id :seon.turn/id task :seon.task/id profile :seon.render/profile
    limit :seon.sci.eval/time-limit-ms writer :seon.db/writer process :seon.db.process/id}]
  (let [db (db/db conn)]
    (cond-> {:seon.boot/cluster-name cluster
             :seon.db/connection conn
             :seon.db/writer (or writer db/transact!)
             :seon.sci.eval/ctx (eval/fork-cluster-ctx base db conn)
             :seon.sci.eval/base-ctx base
             :seon.batch/sink (seon.batch/sink)
             :seon.batch/stamped-ns (java.util.concurrent.atomic.AtomicLong. (System/nanoTime))
             :seon.sci.eval/time-limit-ms (or limit 30000)}
      agent-id (assoc :seon.agent/id agent-id)
      turn-id (assoc :seon.turn/id turn-id)
      environment (-> (assoc :seon.env/environment environment)
                      (merge (seon.batch/environment-members environment)))
      process (assoc :seon.db.process/id process)
      branch (assoc :seon.agent/branch branch)
      task (assoc :seon.task/id task)
      profile (assoc :seon.render/profile profile))))

(defn test-agent-world
  "A test agent's world: its own execution's branch, connection and context,
  its run recorded on the requesting agent's branch (`requester`)."
  {:malli/schema [:=> [:cat :map :seon.db/connection :map] :seon.batch/world]}
  [execution requester opts]
  (assoc (agent-world execution opts) :seon.batch/settlement {:seon.db/connection requester}))

(defn jvm-world
  "An MCP JVM evaluation's world: the agent's connection and the writer. The host evaluates; the agent's SCI context, when given, holds
  each value under its result handle."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name :seon.db/connection :map] :seon.batch/world]}
  [cluster conn {agent-id :seon.agent/id turn-id :seon.turn/id writer :seon.db/writer ctx :seon.sci.eval/ctx
                 environment :seon.env/environment process :seon.db.process/id profile :seon.render/profile}]
  (cond-> {:seon.boot/cluster-name cluster :seon.db/connection conn
           :seon.db/writer (or writer db/transact!)
           :seon.batch/sink (seon.batch/sink)
           :seon.batch/stamped-ns (java.util.concurrent.atomic.AtomicLong. (System/nanoTime))}
    agent-id (assoc :seon.agent/id agent-id)
    turn-id (assoc :seon.turn/id turn-id)
    ctx (assoc :seon.sci.eval/ctx ctx)
    environment (-> (assoc :seon.env/environment environment)
                    (merge (seon.batch/environment-members environment)))
    process (assoc :seon.db.process/id process)
    profile (assoc :seon.render/profile profile)))

(defn basis
  "The world's connection's current value: the value a batch starts from
  (`:seon.batch/basis`). No world holds a database value (owner ruling
  2026-09-28): a caller that needs one reads its connection."
  {:malli/schema [:=> [:cat :seon.batch/world] :seon.db/database-value]}
  [{conn :seon.db/connection}]
  (db/db conn))

(defn merge-world
  "Root's world on a merge branch: merge moves entities by identity and
  evaluates nothing, so it reads the same members as any agent world."
  {:malli/schema [:=> [:cat :map :map] :seon.batch/world]}
  [execution opts]
  (agent-world execution (merge {:seon.agent/id "root"} opts)))

(defn with-world
  "Run `f` with the world's bindings re-established on this thread: its
  custody (the connection, when it has one) for the elided seon.db arities,
  which read its current value, and the running evaluation's observation
  scope (`:seon.batch/scope`, the observer's bindings). A body moved to
  another thread (a compute-pool hop) carries the world as a value and calls
  this; nothing is inherited from the submitting thread."
  {:malli/schema [:=> [:cat :seon.batch/world [:=> [:cat] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "Any body runs in the world: its value passes through unchanged." :gen/elements [nil false 0 "" :k [] {}]}]]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "Any body runs in the world: its value passes through unchanged." :gen/elements [nil false 0 "" :k [] {}]}]]}
  [world f]
  (db/call-with-custody (select-keys world [:seon.db/connection])
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

