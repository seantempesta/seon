(ns seon.task
  "Runtime tasks (spec 7.8): create a task, assign it to an agent's branch and
  record a merge proposal. Tasks live on the cluster, so every write goes to
  the task home through the one task writer (`seon.task/adopt-tasks-tx`)."
  {:seon/tasks #{"2fe9c6b7fe31"}}
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [malli.core :as m]
            [malli.error :as me]
            [malli.registry :as mr]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.refusal :as refusal]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.schema :as schema]))

;;; DECLARATIONS (spec 7.1-7.16): declarations from resources/seon/tasks/<ns>.edn; subjects
;;; and proofs are the `:seon.task/tasks` handles program entities carry.

(defn- replacement-tx
  "The delta from one stored task to its desired facts, over the `owned` attributes
  only: a declared attribute whose value already holds contributes nothing, a changed
  one is retracted and asserted, an absent desired one is retracted."
  {:malli/schema [:=> [:cat :map :map [:set :qualified-keyword]] [:vector :seon.schema/value]]}
  [current desired owned]
  (let [eid (:db/id current)
        normalized (fn [value]
                     (cond
                       (and (map? value) (= #{:db/id} (set (keys value)))) (:db/id value)
                       (coll? value) (set (map #(if (map? %) (get % :db/id %) %) value))
                       :else value))
        changed (into (sorted-set)
                      (for [attribute (into (set (keys current)) (keys desired))
                            :when (and (not (contains? #{:db/id :seon.task/id} attribute))
                                       (contains? owned attribute)
                                       (not= (normalized (get current attribute))
                                             (normalized (get desired attribute))))]
                        attribute))
        asserted (select-keys desired changed)]
    (cond-> (mapv (fn [attribute] [:db/retract eid attribute])
                  (filter #(contains? current %) changed))
      (seq asserted) (conj (assoc asserted :db/id eid)))))

(defn task-handle
  "The task handle of an identifying aspect: seon.id/id over [ns name kind] (3b.6)."
  {:malli/schema [:=> [:cat :seon.task/ns :seon.task/name :seon.task/kind] :seon.task/handle]}
  [ns name kind]
  (id/id [ns name kind]))

(defn task-path?
  "Whether a repository-relative path names a task file."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path]
  (let [file (io/file path)]
    (and (= "resources/seon/tasks" (.getParent file)) (str/ends-with? (.getName file) ".edn"))))

(defn- file-namespace
  "The owning namespace a task file's name states."
  {:malli/schema [:=> [:cat :string] :seon.task/ns]}
  [path]
  (symbol (str/replace-first (.getName (io/file path)) ".edn" "")))

(defn task-files
  "Task file texts under `directory`: every file, or the changed `paths`; a deleted file is \"[]\"."
  {:malli/schema [:=> [:cat :string [:maybe [:sequential :string]]] :seon.task/files]}
  [directory paths]
  (into (sorted-map)
        (map (fn [path] (let [file (io/file directory path)] [path (if (.isFile file) (slurp file) "[]")])))
        (filter task-path? (or paths (map #(str "resources/seon/tasks/" (.getName %))
                                          (.listFiles (io/file directory "resources/seon/tasks")))))))

(defn- read-error
  "The owner's declared read or write refusal, when `result` is one."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :seon.error/base]]}
  [result]
  (when (refusal/error? result) result))

(defn- declared
  "A stored or proposed task's `:seon.task/declaration`: its declared members, sets as sets."
  {:malli/schema [:=> [:cat :seon.schema/projection [:maybe :map]] [:maybe :seon.task/declaration]]}
  [projection task]
  (when task
    (update-vals (select-keys task (map first (m/children (m/deref (mr/schema (:seon.schema.projection/registry projection)
                                                                              :seon.task/declaration)))))
                 #(if (sequential? %) (set %) %))))

(defn- file-declarations
  "Read one task file with edamame's all-forms reader (nothing evaluated), require one
  vector of valid entries with empty sets absent, and verify each supplied handle."
  {:malli/schema [:=> [:cat :seon.schema/projection :string :string] [:vector :seon.task/task]]}
  [projection path text]
  (let [ns (file-namespace path)
        refuse-task! #(functions/task-refused! 'seon.task/file-declarations %1 ns %2 %3)
        forms (try (edamame/parse-string-all text)
                   (catch clojure.lang.ExceptionInfo failure (refuse-task! :unreadable-file text {:seon.error/throwable failure})))
        _ (when-not (task-path? path) (refuse-task! :misplaced-file path {}))
        _ (when-not (and (= 1 (count forms)) (vector? (first forms))) (refuse-task! :not-one-vector forms {}))
        ;; Only an empty set at an optional set member is absence (#{} clears);
        ;; every other member, empty or not, meets the closed entry schema.
        registry (:seon.schema.projection/registry projection)
        absent-when-empty (into #{} (keep (fn [[k props s]] (when (and (:optional props) (= :set (m/type (m/deref-all s)))) k)))
                                (m/children (m/deref (mr/schema registry :seon.task/entry))))
        entries (mapv #(if (map? %) (into {} (remove (fn [[k v]] (and (absent-when-empty k) (= #{} v)))) %) %) (first forms))]
    (when-let [explanation (m/explain (mr/schema registry :seon.task/file) entries)]
      (refuse-task! :invalid-entry (first forms) {:seon.task/explanation (me/humanize explanation)}))
    (mapv (fn [{:seon.task/keys [id name kind] :as entry}]
            (let [handle (task-handle ns name kind)]
              (when (and id (not= id handle)) (refuse-task! :handle-mismatch entry {:seon.error/expected handle}))
              (assoc entry :seon.task/id handle :seon.task/ns ns)))
          entries)))

(defn- stored-tasks
  "The stored tasks whose `attribute` (`:seon.task/ns` or `:seon.task/id`) is one of `values`, indexed once by handle."
  {:malli/schema [:=> [:cat :seon.db/database-value [:enum :seon.task/ns :seon.task/id] [:set [:or :seon.task/ns :seon.task/handle]]]
                  [:or [:map-of :seon.task/handle :map] :seon.db/invalid-read-error]]}
  [database attribute values]
  (let [entities (if (contains? (:schema database) attribute)
               (db/q database '[:find [(pull ?e [*]) ...] :in $ ?a [?v ...] :where [?e ?a ?v]] attribute (vec values))
               [])]
    (or (read-error entities) (into {} (map (juxt :seon.task/id identity)) entities))))

(defn task-basis
  "The declarations stored now for the files' namespaces: the basis a caller reads with its proposal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/files] [:or :seon.task/basis :seon.db/invalid-read-error]]}
  [database files]
  (let [stored (stored-tasks database :seon.task/ns (set (map file-namespace (keys files))))]
    (or (read-error stored) (update-vals stored #(declared (db/carried-projection database) %)))))

(defn- task-delta
  "The writer's delta from `stored` to `by-id` tasks, by handle; a handle whose stored
  declaration differs from the caller's `basis` refuses with base, current and proposed."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :seon.task/handle :map] [:map-of :seon.task/handle :map]
                       :seon.task/basis] [:vector :seon.schema/value]]}
  [projection stored by-id basis]
  (let [owned (set (keys (m/entries (m/deref (mr/schema (:seon.schema.projection/registry projection) :seon.task/declaration)))))]
    (into []
          (mapcat (fn [id]
                    (let [current (declared projection (stored id)) wanted (declared projection (by-id id))]
                      (cond
                        (= current wanted) []
                        (not= (get basis id) current)
                        (functions/task-refused! 'seon.task/tasks-tx :revision-conflict (:seon.task/ns (or (by-id id) (stored id))) id
                                                 (cond-> {} (get basis id) (assoc :seon.task/base (get basis id))
                                                   current (assoc :seon.task/current current) wanted (assoc :seon.task/proposed wanted)))
                        (nil? wanted) [[:db/retractEntity (:db/id (stored id))]]
                        current (replacement-tx (stored id) (by-id id) owned)
                        :else [(by-id id)]))))
          (sort (into (set (keys stored)) (keys by-id))))))

(defn tasks-tx
  "The writer's delta from stored tasks to the declared `files`, by handle. A touched
  task whose current declaration differs from the caller's `basis` refuses with base,
  current and proposed (7.16); unchanged input is empty; a removed entry retracts.
  A runtime task (`:seon.task/created-by`) is outside every file's declaration."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/files :seon.task/basis] [:vector :seon.schema/value]]}
  [database files basis]
  (let [projection (db/carried-projection database)
        proposed (into [] (mapcat (fn [[path text]] (file-declarations projection path text))) files)
        by-id (into {} (map (juxt :seon.task/id identity)) proposed)
        stored (stored-tasks database :seon.task/ns (set (map file-namespace (keys files))))]
    (when (read-error stored) (throw (ex-info (:seon.error/message stored) stored)))
    (when-let [id (some (fn [[id n]] (when (< 1 n) id)) (frequencies (map :seon.task/id proposed)))]
      (functions/task-refused! 'seon.task/tasks-tx :duplicate-id (:seon.task/ns (by-id id)) id {}))
    ;; A runtime task (7.8, its writer recorded) is no file's declaration: never retracted here.
    (task-delta projection (into {} (remove (comp :seon.task/created-by val)) stored) by-id basis)))

(defn task-revision
  "The reviewed revision of the task `handle` in `database`: seon.id/digest over its declaration; nil when absent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/revision :seon.db/invalid-read-error]]}
  [database handle]
  (let [stored (stored-tasks database :seon.task/id #{handle})]
    (or (read-error stored)
        (some->> (stored handle) (declared (db/carried-projection database)) vector (id/digest 64)))))

(defn adopt-tasks-tx
  "Reconcile the selected `handles` into a development database from the `rows` the
  exact published source holds for them, against the `basis` the adoption read at
  the destination: a competing edit since refuses (7.16); a handle absent from
  `rows` retracts."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map] [:set :seon.task/handle] :seon.task/basis]
                  [:vector :seon.schema/value]]}
  [database entities handles basis]
  (let [stored (stored-tasks database :seon.task/id handles)]
    (when (read-error stored) (throw (ex-info (:seon.error/message stored) stored)))
    (task-delta (db/carried-projection database) stored (into {} (map (juxt :seon.task/id #(dissoc % :db/id))) entities) basis)))

(defn- task-handles
  "Every stored task handle, or with `retracted?` every handle `database`'s history
  retracted; none when the database declares no tasks."
  {:malli/schema [:=> [:cat :seon.db/database-value :boolean] [:or [:set :seon.task/handle] :seon.db/invalid-read-error]]}
  [database retracted?]
  (if (contains? (:schema database) :seon.task/id)
    (let [handles (if retracted?
                    (let [history (db/history database)]
                      (or (read-error history) (db/q '[:find [?h ...] :where [_ :seon.task/id ?h _ false]] history)))
                    (db/q database '[:find [?h ...] :where [_ :seon.task/id ?h]]))]
      (or (read-error handles) (set handles)))
    #{}))

(defn adopt-tasks-call
  "Transaction data reconciling `destination`'s tasks to every task `source`, the
  published database, holds: the whole set, since an adopted commit never proved its
  tasks admitted. A held task the source's history retracted was published and is
  deleted; one the source never held is the destination's own and stays. The basis
  is `destination`, the value this call read. Empty when they agree."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value]
                  [:or :seon.db/tx-data :seon.db/invalid-read-error]]}
  [source destination]
  (let [published (task-handles source false) retracted (task-handles source true) held (task-handles destination false)
        failure (some read-error [published retracted held])
        handles (when-not failure (into published (filter retracted) held))
        entities (when handles (stored-tasks source :seon.task/id handles))
        basis (when handles (stored-tasks destination :seon.task/id handles))]
    (or failure (read-error entities) (read-error basis)
        (let [entities (vec (vals entities))
              basis (update-vals basis #(declared (db/carried-projection destination) %))]
          (if (empty? (adopt-tasks-tx destination entities handles basis)) [] [[:db.fn/call #'adopt-tasks-tx entities handles basis]])))))

(defn index-tasks!
  "Reconcile task files through the writer against the declarations the caller read
  (`:seon.task/basis`); an unchanged set writes nothing. Refusals return. Without a
  basis it reads one now, which guards only writes after this call, not a stale file."
  {:malli/schema [:=> [:cat :seon.task/request]
                  [:or :seon.task/report :seon.task/refused-error :seon.task/conflict-error
                   :seon.db/invalid-read-error :seon.db/transaction-refused-error]]}
  [{connection :seon.db/connection files :seon.task/files basis :seon.task/basis}]
  (let [database (db/db connection)
        basis (or basis (task-basis database files))]
    (or (read-error basis)
        (try (if (empty? (tasks-tx database files basis))
               {:seon.task/changed? false}
               (let [report (db/transact! connection [[:db.fn/call #'tasks-tx files basis]])]
                 (or (read-error report) {:seon.task/changed? true :seon.db/transaction-report report})))
             (catch clojure.lang.ExceptionInfo failure
               (if (:seon.task/refused (ex-data failure)) (ex-data failure) (throw failure)))))))

(defn task-edges
  "A task's subjects (functions, namespaces), proofs (deftests), and the definitions,
  namespaces and tasks whose must-read names one of its subjects, over one held value."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] [:or :seon.task/edges :seon.db/invalid-read-error]]}
  [database handle]
  (let [holders (db/q database '[:find ?s ?proof :in $ ?t :where [?e :seon.task/tasks ?t]
                                 (or (and [?e :seon.test/sym ?s] [(ground true) ?proof])
                                     (and (or [?e :seon.fn/sym ?s] [?e :seon.ns/name ?s]) [(ground false) ?proof]))] handle)
        subjects (when-not (read-error holders) (set (keep (fn [[s proof]] (when-not proof s)) holders)))
        referrers (when subjects
                    (db/q database '[:find [?h ...] :in $ [?target ...]
                                     :where (or (and [?e :seon.fn/must-read ?target]
                                                     (or [?e :seon.fn/sym ?h] [?e :seon.test/sym ?h] [?e :seon.ns/name ?h]))
                                                (and [?e :seon.task/must-read ?target] [?e :seon.task/id ?h]))] (vec subjects)))]
    (or (read-error holders) (read-error referrers)
        {:seon.task/subjects subjects :seon.task/proofs (set (keep (fn [[s proof]] (when proof s)) holders))
         :seon.task/referrers (set referrers)})))

(defn open-tasks
  "The task handles a definition still lists; listing is openness (spec 7.2, 7.6)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:tuple :qualified-keyword :symbol]]
                  [:or [:set :seon.task/handle] :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [database program-identity]
  (let [pulled (db/pull database [:seon.task/tasks] program-identity)]
    (or (read-error pulled) (set (:seon.task/tasks pulled)))))

(defn task-findings
  "`:seon.task/dangling` handles listed in code with no task entity, by holder (the
  program gate refuses them), and `:seon.task/triage` tasks with no subject or proof."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :seon.task/findings :seon.db/invalid-read-error]]}
  [database]
  (let [dangling (db/q database '[:find ?t ?h :where [?e :seon.task/tasks ?t] (not [_ :seon.task/id ?t])
                                  (or [?e :seon.fn/sym ?h] [?e :seon.test/sym ?h] [?e :seon.ns/name ?h])])
        triage (db/q database '[:find [?t ...] :where [_ :seon.task/id ?t] (not [_ :seon.task/tasks ?t])])]
    (or (read-error dangling) (read-error triage)
        {:seon.task/dangling (update-vals (group-by first dangling) #(set (map second %)))
         :seon.task/triage (set triage)})))

(defn candidate-dangling
  "The `:seon.task/dangling` handles, by holder, of the program `destination` would
  hold after taking `candidate`'s rows for `identities`: those holders' task edges
  from `candidate`, every other holder's from `destination`, against the tasks
  admitted on `destination`. Task rows never ride the merge (7.13)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set]
                  [:or :seon.task/dangling :seon.db/invalid-read-error]]}
  [candidate destination identities]
  (let [edges #(if (contains? (:schema %) :seon.task/tasks)
                 (db/q '[:find ?t ?a ?h :in $ [?a ...] :where [?e :seon.task/tasks ?t] [?e ?a ?h]]
                       % [:seon.fn/sym :seon.test/sym :seon.ns/name])
                 #{})
        mine (edges candidate) theirs (edges destination) admitted (task-handles destination false)
        touched? (fn [[_ attribute holder]] (contains? identities [attribute holder]))]
    (or (some read-error [mine theirs admitted])
        (update-vals (group-by first (remove (comp admitted first) (concat (filter touched? mine) (remove touched? theirs))))
                     #(set (map (fn [[_ _ holder]] holder) %))))))

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
  (assoc declaration :seon.task/id (task-handle ns name kind) :seon.task/created-by [:seon.agent/id agent-id]))

(defn- task-call
  "The task writer's call for `row` against `basis` on the task home of
  `connection`, or [] when that home already holds its declaration. A revised
  declaration throws the writer's conflict with base, current and proposed."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.task/task :seon.task/basis] :seon.db/tx-data]}
  [connection entity basis]
  (let [handles #{(:seon.task/id entity)}]
    (if (empty? (adopt-tasks-tx (db/db (home-connection connection)) [entity] handles basis))
      []
      [[:db.fn/call #'adopt-tasks-tx [entity] handles basis]])))

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
  (seon.task/create! {:seon.task/ns 'my.turn :seon.task/name \"titles trim\"
                      :seon.task/kind :function :seon.task/intent \"Trim reply titles.\"})"
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
  working on `:seon.task/branch`, binding the revision it reviews now. Answers
  the assignment, offering the agent's proposal once a test naming the task
  passes on its branch.

  Example:
  (seon.task/assign! {:seon.task/assigned \"a1b2c3d4e5f6\" :seon.task/agent [:seon.agent/id \"mcp/fix\"]
                      :seon.task/branch :fix})"
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.task/assigned :seon.task/assigned]
                             [:seon.task/agent :seon.task/agent] [:seon.task/branch :seon.task/branch]]]
                  [:or :seon.task/assignment-view :seon.task/refused-error :seon.error/value]]}
  [{connection :seon.db/connection handle :seon.task/assigned agent :seon.task/agent branch :seon.task/branch}]
  (let [database (db/db (home-connection connection))
        revision (task-revision database handle)
        agent-id (:seon.agent/id (db/pull database [:seon.agent/id] agent))]
    (cond (:seon.error/at revision) revision
          (nil? revision) (refused 'seon.task/assign! :unknown-task 'seon.task handle)
          (not (string? agent-id)) (refused 'seon.task/assign! :unassigned 'seon.task agent)
          :else (let [assignment (id/id [handle agent-id branch])
                      report (written connection [{:seon.task/assignment assignment :seon.task/assigned handle
                                                   :seon.task/revision revision :seon.task/agent [:seon.agent/id agent-id]
                                                   :seon.task/branch branch}])]
                  (if (:seon.error/at report)
                    report
                    {:seon.task/assignment assignment :seon.task/assigned handle :seon.agent/id agent-id
                     :seon.task/branch branch
                     :seon.render/operations ['(my.program/propose-merge! {})]})))))

(defn assignment
  "What `agent-id` on `branch` is assigned in the cluster value `database`: its
  task's proofs, owning namespace and key, refused when the task changed since
  root reviewed it. None, or several, refuses `:unassigned` by name."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.task/branch]
                  [:or :seon.task/assigned-work :seon.task/refused-error :seon.error/value]]}
  [database agent-id branch]
  (let [assigned (db/q '[:find ?key ?handle ?revision :in $ ?id ?branch
                         :where [?agent :seon.agent/id ?id] [?a :seon.task/agent ?agent] [?a :seon.task/branch ?branch]
                         [?a :seon.task/assignment ?key] [?a :seon.task/assigned ?handle] [?a :seon.task/revision ?revision]]
                       database agent-id branch)
        [[key handle revision]] (when-not (map? assigned) (seq assigned))
        current (when handle (task-revision database handle))
        edges (when handle (task-edges database handle))]
    (cond (some :seon.error/at [assigned current edges]) (some #(when (:seon.error/at %) %) [assigned current edges])
          (and (= 1 (count assigned)) (not= revision current))
          (refused 'seon.task/assignment :revised-since-assignment 'seon.task {:seon.task/revision revision :seon.task/current current})
          (= 1 (count assigned))
          {:seon.task/key key :seon.task/assigned handle :seon.task/proposes [:seon.task/assignment key]
           :seon.task/ns (:seon.task/ns (db/pull database [:seon.task/ns] [:seon.task/id handle]))
           :seon.task/proofs (:seon.task/proofs edges)}
          :else (refused 'seon.task/assignment :unassigned 'seon.task {:seon.agent/id agent-id :seon.store/branch branch
                                                                      :seon.task/assignment (mapv first assigned)}))))

;;; STEPS (spec task-steps): a task's ordered steps are components of its row, written at the task
;;; home like every task write; they sit outside the declaration, so a step never revises the task.

(def ^:private step-selector
  [:db/id :seon.step/position :seon.step/text :seon.step/status {:seon.step/done-tx [:db/id]}])

(defn- step-rows
  "The stored steps of task `handle` with their entity ids, in position order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] [:or [:vector :map] :seon.db/invalid-read-error]]}
  [database handle]
  (let [pulled (db/pull database [{:seon.task/steps step-selector}] [:seon.task/id handle])]
    (or (read-error pulled)
        (vec (sort-by :seon.step/position (:seon.task/steps pulled))))))

(defn task-steps
  "The steps of task `handle` in `database`, in position order: position, text, status and, once
  done, the completing transaction. Empty when the task has none or is absent.

  ## Costs

  One pull of the task's steps; proportional to their number."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] [:or [:vector :seon.step/view] :seon.db/invalid-read-error]]}
  [database handle]
  (let [rows (step-rows database handle)]
    (if (:seon.error/at rows)
      rows
      (mapv (fn [row] (cond-> (select-keys row [:seon.step/position :seon.step/text :seon.step/status])
                        (:seon.step/done-tx row) (assoc :seon.step/done-tx (get-in row [:seon.step/done-tx :db/id]))))
            rows))))

(defn step-summary
  "The current step's text and progress \"done/total\" of `steps`; empty for no steps."
  {:malli/schema [:=> [:cat [:vector :seon.step/view]] [:map [:seon.task/current-step {:optional true} [:string {:min 1}]]
                                                        [:seon.task/progress {:optional true} [:string {:min 3}]]]]}
  [steps]
  (if (empty? steps)
    {}
    (cond-> {:seon.task/progress (str (count (filter #(= :done (:seon.step/status %)) steps)) "/" (count steps))}
      (some #(= :current (:seon.step/status %)) steps)
      (assoc :seon.task/current-step (:seon.step/text (first (filter #(= :current (:seon.step/status %)) steps)))))))

(defn steps-call
  "The writer's transaction data for the step `op` on task `handle`, decided against the steps
  stored now: `[:plan texts]` replaces them (the first current), `[:append text]` adds one (current
  when none is), `[:done]` completes the current step and makes the next open one current. With
  no current step `[:done]` is empty, so a repeat never completes two."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle :seon.task/step-op] :seon.db/tx-data]}
  [database handle op]
  (let [rows (step-rows database handle)
        owner [:seon.task/id handle]
        step (fn [position text status] {:seon.step/position position :seon.step/text text :seon.step/status status})
        first-with (fn [status] (first (filter #(= status (:seon.step/status %)) rows)))]
    (case (first op)
      :plan (cond-> (mapv (fn [row] [:db/retractEntity (:db/id row)]) rows)
              (seq (second op))
              (conj {:db/id owner :seon.task/steps (vec (map-indexed #(step %1 %2 (if (zero? %1) :current :open)) (second op)))}))
      :append [{:db/id owner
                :seon.task/steps [(step (inc (reduce max -1 (map :seon.step/position rows))) (second op)
                                        (if (first-with :current) :open :current))]}]
      :done (if-let [current (first-with :current)]
              (cond-> [[:db/add (:db/id current) :seon.step/status :done]
                       [:db/add (:db/id current) :seon.step/done-tx "datomic.tx"]]
                (first-with :open) (conj [:db/add (:db/id (first-with :open)) :seon.step/status :current]))
              []))))

(defn agent-task
  "The handle of the task `agent-id` holds in `database` (the task home's): its latest assignment's
  task; nil when it holds none. One indexed query.

  ## Costs

  Measured 1.2 ms on default (2026-09-28)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :seon.task/handle :seon.db/invalid-read-error]]}
  [database agent-id]
  (let [held (db/q '[:find ?handle ?tx :in $ ?id
                     :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/assigned ?handle ?tx]]
                   database agent-id)]
    (or (read-error held) (first (apply max-key second [nil 0] (seq held))))))

(defn steps-view
  "The steps of `handle` as the agent reads them, with the calls its state offers."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] [:or :seon.task/steps-view :seon.db/invalid-read-error]]}
  [database handle]
  (let [steps (task-steps database handle)]
    (or (read-error steps)
        (merge {:seon.task/id handle :seon.task/steps steps
                :seon.render/operations (if (some #(= :current (:seon.step/status %)) steps)
                                          ['(my.task/done!)]
                                          ['(my.task/step! {:seon.step/text "Next step"})])}
               (step-summary steps)))))

(defn steps!
  "Apply the step `op` to task `handle` at the task home, answering the task's steps. An unknown
  task refuses `:unknown-task`; `[:done]` with no current step refuses `:no-current-step`.

  ## Costs

  One transaction at the home, under the run bound (measured in the landing note)."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.task/handle :seon.task/step-op]
                  [:or :seon.task/steps-view :seon.task/refused-error :seon.error/value]]}
  [connection handle op]
  (let [target (home-connection connection)
        database (db/db target)
        known (db/pull database [:seon.task/id] [:seon.task/id handle])
        rows (when (:seon.task/id known) (step-rows database handle))]
    (cond (:seon.error/at known) known
          (nil? (:seon.task/id known)) (refused 'seon.task/steps! :unknown-task 'seon.task handle)
          (:seon.error/at rows) rows
          (and (= [:done] op) (not-any? #(= :current (:seon.step/status %)) rows))
          (refused 'seon.task/steps! :no-current-step 'seon.task handle)
          :else (let [report (written connection [[:db.fn/call #'steps-call handle op]])]
                  (if (:seon.error/at report) report (steps-view (db/db target) handle))))))

(defn- refuted
  "The `proofs` observed red on `database` (the proposing branch) at a reach
  no green member of the same test shares, as `[test reach-digest]`: each
  failed at the program before the change (health plan §5, owner ruling: a
  task made from a fault is proven only red first)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :symbol]]
                  [:or [:set [:tuple :symbol [:string {:min 64 :max 64}]]] :seon.db/error-result]]}
  [database proofs]
  (let [members (db/q '[:find ?test ?reach ?fail ?error :in $ [?test ...]
                        :where [?member :seon.test.member/symbol ?test]
                               [?member :seon.test.member/reach-digest ?reach]
                               [?member :seon.test.member/fail-count ?fail]
                               [?member :seon.test.member/error-count ?error]]
                      database (vec proofs))]
    (if (map? members)
      members
      (let [green (into #{} (keep (fn [[proof reach fail failure]] (when (zero? (+ fail failure)) [proof reach]))) members)]
        (into #{} (keep (fn [[proof reach fail failure]]
                          (when (and (pos? (+ fail failure)) (not (green [proof reach]))) [proof reach])))
              members)))))

(defn propose!
  "Record on the cluster the merge task for the assigned `work` and one proposal
  of the commit `view` names on its branch: its proofs at their definition digests, the run and its tested
  program, and each proof observed red on the branch before the change
  (`:seon.task/refuted`, read from the branch `connection` holds). The merge task's aspect is `[ns \"merge <key>\" :merge]`, so a repeat
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
        red (refuted (db/db connection) (into #{} (map first) (:seon.task/proved view)))
        view (cond-> (assoc view :seon.task/proposal (id/id [key (:seon.task/commit view)]) :seon.task/merge (:seon.task/id entity))
               (seq red) (assoc :seon.task/refuted red))
        report (refusal-data #(written connection (conj (task-call connection entity {}) (assoc view :seon.task/proposes proposes))))]
    (cond (map? red) red
          (:seon.error/at report) report
          :else view)))

(defn decide!
  "Root records its `outcome` on the merge task `merge-task`, closing it: `proposals`
  lists only merge tasks without one. Answers the closed merge task.

  Example:
  (seon.task/decide! connection \"0123456789ab\" :declined)"
  {:malli/schema [:=> [:cat :seon.db/connection :seon.task/merge :seon.task/outcome]
                  [:or :seon.task/decided :seon.error/value]]}
  [connection merge-task outcome]
  (let [report (written connection [[:db/add [:seon.task/id merge-task] :seon.task/outcome outcome]])]
    (if (:seon.error/at report) report {:seon.task/id merge-task :seon.task/outcome outcome})))

(def resolution-selector
  "The pull selector of what `resolution` reads of a task: a caller that
  pulls tasks for its own view adds this and passes each pulled task to
  `task-resolution`, instead of `resolution` pulling it again."
  [:seon.task/id :seon.task/outcome :seon.task/signal {:seon.task/subject [:db/id]}
   {:seon.task/faults [:seon.error/signature]}])

(defn task-resolution
  "`resolution` of a task already pulled with `resolution-selector` on
  `database`: the same derivation without the pull.

  ## Costs

  Per fault signature three indexed queries and two pulls
  (`seon.error/resolution`); none for a task with an outcome or no faults."
  {:malli/schema [:=> [:cat :seon.db/database-value :map]
                  [:or :nil :seon.task/resolution :seon.db/error-result]]}
  [database task]
  (let [signatures (keep :seon.error/signature (:seon.task/faults task))]
    (cond
      (:seon.error/at task) task
      (:seon.task/outcome task) {:seon.task/closed-by :seon.task/outcome}
      (seq signatures)
      (let [resolved (mapv #(error/resolution database %) signatures)]
        (cond (some :seon.error/at resolved) (some #(when (:seon.error/at %) %) resolved)
              (every? some? resolved) {:seon.task/closed-by :seon.task/faults :seon.task/resolutions resolved}))
      (and (:seon.task/signal task) (:db/id (:seon.task/subject task)))
      (let [subject (db/stored-entity database (:db/id (:seon.task/subject task)))
            satisfied? (schema/projection-validator (db/carried-projection database) (:seon.task/signal task))]
        (when-not (satisfied? subject) {:seon.task/closed-by :seon.task/signal})))))

(defn resolution
  "Why task `handle` is closed, or nil while it is open: derived from what
  the task answers, never a stored flag (health plan §5).

  - A task answering faults (`:seon.task/faults`) is closed when every one
    is resolved (`seon.error/resolution`: its producer changed and, for a
    fault the task answers, the task's proof holds).
  - A task made from a warning signal is closed once its subject no longer
    satisfies the named signal schema (`:seon.task/signal`), validated against
    the subject's stored attributes.
  - A merge task is closed by root's outcome.

  An authored task answering none of these is open while a definition names
  it (`seon.task/open-tasks`); it has no resolution here.

  ## Examples

  (seon.task/resolution (seon.db/db) \"6cc772cb3f60\")"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle]
                  [:or :nil :seon.task/resolution :seon.db/error-result]]}
  [database handle]
  (task-resolution database (db/pull database resolution-selector [:seon.task/id handle])))
