(ns seon.task
  "Runtime tasks (spec 7.8): create a task, assign it to an agent's branch and
  record a merge proposal. Tasks live on the cluster, so every write goes to
  the task home through the one task writer (`seon.issue/adopt-tasks-tx`)."
  {:seon/tasks #{"2fe9c6b7fe31"}}
  (:require [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.id :as id]
            [seon.issue :as issue]))

(defn home
  "The connection tasks live on, seen from `database`: root's home branch (root's
  row names it, as a message to root is delivered there) through this process's
  connection to it; nil when `database` is that branch or no root row names another."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:maybe :seon.db/connection]]}
  [database]
  (let [branch (db/q '[:find ?branch . :where [?root :seon.agent/id "root"] [?root :seon.agent/branch ?branch]] database)]
    (when (and (keyword? branch) (not= branch (get-in database [:config :branch])))
      (db/branch-connection database branch))))

(defn home-connection
  "The connection a task write from `connection` goes through: the task home's, else `connection`."
  {:malli/schema [:=> [:cat :seon.db/connection] :seon.db/connection]}
  [connection]
  (or (home (db/db connection)) connection))

(defn- refused
  "The task refusal value naming `rule`, the `holder` namespace and the offending value."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.task/refused :seon.task/holder :seon.schema/value]
                  :seon.task/refused-error]}
  [operation rule holder offending]
  (refusal/diagnostic (java.util.Date.) :seon.task/ingestion operation
                      {:seon.error/message (str holder " is refused: " (name rule) ".")
                       :seon.task/refused rule :seon.task/holder holder :seon.error/offending offending}))

(defn- written
  "Write `tx` on the task home of `connection` under that home's custody."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/tx-data]
                  [:or :seon.db/transaction-report :seon.error/value]]}
  [connection tx]
  (let [target (home-connection connection)]
    (db/call-with-custody {:seon.db/connection target} #(db/transact! target tx))))

(defn- task-entity
  "`declaration` as the runtime task entity `agent-id` writes, under its handle."
  {:malli/schema [:=> [:cat :seon.task/declaration :seon.agent/id] :seon.task/task]}
  [{:seon.task/keys [ns name kind] :as declaration} agent-id]
  (assoc declaration :seon.task/id (issue/task-handle ns name kind) :seon.task/created-by [:seon.agent/id agent-id]))

(defn- task-call
  "The task writer's call for `row` against `basis` on the task home of
  `connection`, or [] when that home already holds its declaration. A revised
  declaration throws the writer's conflict with base, current and proposed."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.task/task :seon.task/basis] :seon.db/tx-data]}
  [connection entity basis]
  (let [handles #{(:seon.task/id entity)}]
    (if (empty? (issue/adopt-tasks-tx (db/db (home-connection connection)) [entity] handles basis))
      []
      [[:db.fn/call #'issue/adopt-tasks-tx [entity] handles basis]])))

(defn- refusal-data
  "Call `f`; the task writer's thrown refusal answers as its data."
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value]] :seon.schema/value]}
  [f]
  (try (f) (catch clojure.lang.ExceptionInfo failure
             (if (:seon.task/refused (ex-data failure)) (ex-data failure) (throw failure)))))

(defn create!
  "Create a runtime task on the cluster from its declaration (7.8). Its handle is
  the aspect `[ns name kind]`: the same declaration again changes nothing; a
  different one refuses with base, current and proposed unless `:seon.task/basis`
  names the revision I read (7.16).

  Example:
  (seon.task/create! {:seon.task/ns 'my.note :seon.task/name \"titles trim\"
                      :seon.task/kind :function :seon.task/intent \"Trim note titles.\"})"
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]
                             [:seon.task/ns :seon.task/ns] [:seon.task/name :seon.task/name] [:seon.task/kind :seon.task/kind]
                             [:seon.task/intent :seon.task/intent] [:seon.task/must-read {:optional true} :seon.task/must-read]
                             [:seon.task/evidence {:optional true} :seon.task/evidence]
                             [:seon.task/basis {:optional true} :seon.task/basis]]]
                  [:or [:map [:seon.task/id :seon.task/id] [:seon.task/changed? :seon.task/changed?]]
                   :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id :as request}]
  (let [entity (task-entity (select-keys request [:seon.task/ns :seon.task/name :seon.task/kind :seon.task/intent
                                            :seon.task/must-read :seon.task/evidence]) agent-id)
        tx (refusal-data #(task-call connection entity (:seon.task/basis request {})))
        report (if (and (vector? tx) (seq tx)) (written connection tx) tx)]
    (if (:seon.error/at report)
      report
      {:seon.task/id (:seon.task/id entity) :seon.task/changed? (boolean (seq tx))})))

(defn assign!
  "Root assigns the task `:seon.task/assigned` to the agent `:seon.task/agent`
  working on `:seon.task/branch`, binding the revision it reviews now.

  Example:
  (seon.task/assign! {:seon.task/assigned \"a1b2c3d4e5f6\" :seon.task/agent [:seon.agent/id \"mcp/fix\"]
                      :seon.task/branch :fix})"
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.task/assigned :seon.task/assigned]
                             [:seon.task/agent :seon.task/agent] [:seon.task/branch :seon.task/branch]]]
                  [:or :seon.db/transaction-report :seon.task/refused-error :seon.error/value]]}
  [{connection :seon.db/connection handle :seon.task/assigned agent :seon.task/agent branch :seon.task/branch}]
  (let [database (db/db (home-connection connection))
        revision (issue/task-revision database handle)
        agent-id (:seon.agent/id (db/pull database [:seon.agent/id] agent))]
    (cond (:seon.error/at revision) revision
          (nil? revision) (refused 'seon.task/assign! :unknown-task 'seon.task handle)
          (not (string? agent-id)) (refused 'seon.task/assign! :unassigned 'seon.task agent)
          :else (written connection [{:seon.task/assignment (id/id [handle agent-id branch]) :seon.task/assigned handle
                                      :seon.task/revision revision :seon.task/agent [:seon.agent/id agent-id]
                                      :seon.task/branch branch}]))))

(defn assignment
  "What `agent-id` on `branch` is assigned in the cluster value `database`: its
  task's proofs, owning namespace and key, refused when the task changed since
  root reviewed it; else the legacy issue it works (`:seon.issue/agent`), whose
  proofs are its tests. Neither, or several, refuses `:unassigned` by name."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.task/branch]
                  [:or :seon.task/assigned-work :seon.task/refused-error :seon.error/value]]}
  [database agent-id branch]
  (let [assigned (db/q '[:find ?key ?handle ?revision :in $ ?id ?branch
                         :where [?agent :seon.agent/id ?id] [?a :seon.task/agent ?agent] [?a :seon.task/branch ?branch]
                         [?a :seon.task/assignment ?key] [?a :seon.task/assigned ?handle] [?a :seon.task/revision ?revision]]
                       database agent-id branch)
        issues (db/q '[:find ?issue (distinct ?test) :in $ ?id
                       :where [?agent :seon.agent/id ?id] [?i :seon.issue/agent ?agent] [?i :seon.issue/id ?issue]
                       [?i :seon.issue/tests ?t] [?t :seon.test/sym ?test]]
                     database agent-id)
        [[key handle revision]] (when-not (map? assigned) (seq assigned))
        current (when handle (issue/task-revision database handle))
        edges (when handle (issue/task-edges database handle))]
    (cond (some :seon.error/at [assigned issues current edges]) (some #(when (:seon.error/at %) %) [assigned issues current edges])
          (and (= 1 (count assigned)) (not= revision current))
          (refused 'seon.task/assignment :revised-since-assignment 'seon.task {:seon.task/revision revision :seon.task/current current})
          (= 1 (count assigned))
          {:seon.task/key key :seon.task/assigned handle :seon.task/proposes [:seon.task/assignment key]
           :seon.task/ns (:seon.task/ns (db/pull database [:seon.task/ns] [:seon.task/id handle]))
           :seon.task/proofs (:seon.task/proofs edges)}
          (and (empty? assigned) (= 1 (count issues)))
          (let [[[issue tests]] (seq issues)]
            {:seon.task/key issue :seon.task/proposes [:seon.issue/id issue] :seon.task/proofs (set tests)
             :seon.task/ns (symbol (namespace (first (sort tests))))})
          :else (refused 'seon.task/assignment :unassigned 'seon.task {:seon.agent/id agent-id :seon.store/branch branch
                                                                      :seon.task/assignment (mapv first assigned)}))))

(defn propose!
  "Record on the cluster the merge task for the assigned `work` and one proposal
  of the commit `view` names on its branch: its proofs at their definition digests, the run and its tested
  program. The merge task's aspect is `[ns \"merge <key>\" :merge]`, so a repeat
  proposal finds it; the recurrence key `[key commit]` records one proposal per
  commit. Answers the proposal view."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id :seon.task/assigned-work
                       [:map [:seon.task/branch :seon.task/branch] [:seon.task/commit :seon.task/commit]
                        [:seon.task/proved :seon.task/proved] [:seon.task/run-id :seon.task/run-id]
                        [:seon.task/program :seon.task/program]]]
                  [:or :seon.task/proposal-view :seon.task/conflict-error :seon.error/value]]}
  [connection agent-id {:seon.task/keys [key ns proposes]} view]
  (let [entity (task-entity {:seon.task/ns ns :seon.task/name (str "merge " key) :seon.task/kind :merge
                       :seon.task/intent (str "Merge branch " (name (:seon.task/branch view)) "'s change for " key ".")}
                      agent-id)
        view (assoc view :seon.task/proposal (id/id [key (:seon.task/commit view)]) :seon.task/merge (:seon.task/id entity))
        report (refusal-data #(written connection (conj (task-call connection entity {}) (assoc view :seon.task/proposes proposes))))]
    (if (:seon.error/at report) report view)))
