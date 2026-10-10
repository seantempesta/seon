(ns ^{:seon.ns/context-relevant? true} my.agent
  "Read and update my record through request maps."
  (:refer-clojure :exclude [identity])
  (:require [seon.agent :as agent]
            [seon.cluster.branch :as cluster.branch]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.run :as run]))

(defn identity
  "Read my identity, assigned namespace, and its steward.

  Returns :my.agent/id, with :my.agent/namespace and :my.agent/steward
  when assigned. My database and identity are supplied.

  Example:
  (my.agent/identity)"
  {:malli/schema [:=> [:cat :seon.agent/db-request] [:or :my.agent/identity :seon.error/value]]}
  [request]
  (agent/identity (:seon.db/db request) (:seon.agent/id request)))

(defn done
  "End my session now; a later outside wake may start another session.

  Finish only after verifying the requested result and sending any reply.
  Call preparation supplies my database and agent identity.
  Returns {:my.turn/disposition :wait :my.turn/note \"Session complete.\"}; the
  turn interprets this value when it is the result of a form.

  Example:
  (my.agent/done {})"
  {:malli/schema [:=> [:cat :seon.agent/db-request] [:or :my.turn/wait :seon.error/value]]}
  [_request]
  (run/wait "Session complete."))

(defn settings
  "Read my setting overrides of cluster defaults.

  Returns a map of :seon.config setting overrides, {} when I override
  nothing. Omitted settings inherit cluster defaults;
  seon.agent/effective-settings reads effective values.

  Example:
  (my.agent/settings)"
  {:malli/schema [:=> [:cat :seon.agent/db-request] [:or :my.agent/settings :seon.error/value]]}
  [request]
  (agent/settings (:seon.db/db request) (:seon.agent/id request)))

(defn settings!
  "Change supplied setting overrides and return my resulting overrides.

  Supply per-agent dials, the fully qualified :seon.config keys that
  :seon.config/agent-overlay declares. Omitted keys retain their values.
  Returns a map of :seon.config setting overrides for my agent. My connection
  and identity are supplied.

  Example:
  (my.agent/settings! {:seon.config.eval/time-limit-ms 10000})"
  {:malli/schema [:=> [:cat :seon.config/agent-overlay :seon.db/connection :seon.agent/id]
                  [:or :seon.config/agent-overlay :seon.error/value]]}
  [overrides connection agent-id]
  (agent/settings! overrides connection agent-id))

(defn branch
  "Read the branch my context holds and its live or isolated mode.

  The branch is the one my supplied connection holds. The mode is the one my
  acquisition of that branch decided (seon.cluster.agent/acquire-context!),
  read from its entry in my execution's roster; neither is derived from a cluster name.

  Example:
  (my.agent/branch)"
  {:malli/schema [:=> [:cat [:map [:seon.agent/id :seon.agent/id]
                             [:my.program/context :my.program/context]]]
                  [:map [:seon.agent/branch :seon.agent/branch]
                   [:seon.agent/mode :seon.agent/mode]]]}
  [{agent-id :seon.agent/id
    {connection :seon.db/connection roster :seon.agent/routing} :my.program/context}]
  ;; Mode is relative to the acquiring parent, so only this agent's own entry answers.
  (let [held (get-in (db/db connection) [:config :branch])
        mode (:seon.agent/mode (get-in (some-> roster deref) [:seon.agent/contexts [held agent-id]]))]
    (when-not (and held mode)
      (throw (ex-info "No acquisition of this agent holds its connection's branch."
                      {:seon.agent/id agent-id :seon.agent/branch held})))
    {:seon.agent/branch held :seon.agent/mode mode}))

;;; ---------------------------------------------------------------------------
;;; Lane branches: the one entrance an agent and the MCP `branch` tool share
;;; ---------------------------------------------------------------------------

(defn- branch-refusal
  "A declared refusal of one branch operation."
  {:malli/schema [:=> [:cat :qualified-symbol :string :map] :seon.error/value]}
  [operation message data]
  (refusal/diagnostic (java.util.Date.) :my.agent/branch operation
                      (merge {:seon.error/message message :seon.error/member :seon.store/branch} data)))

(defn- own-branch
  "The branch my context's connection holds."
  {:malli/schema [:=> [:cat :my.agent/branch-context] :seon.store/branch]}
  [{connection :seon.db/connection}]
  (get-in @connection [:config :branch]))

(defn branches
  "Read my cluster's own branch and the store's branch roster.

  My context is supplied.

  Example:
  (my.agent/branches)"
  {:malli/schema [:=> [:cat :my.agent/branch-context] [:or :my.agent/branches :seon.error/value]]}
  [{store :seon.store/store :as context}]
  (if-not store
    (branch-refusal `branches "My context holds no store to read the roster from." {})
    {:my.agent/own-branch (own-branch context) :my.agent/roster (registry/roster store)}))

(defn create-branch!
  "Create a lane branch off my cluster head's current commit.

  The branch inherits every fact up to that commit; my cluster's own branch
  and an existing name refuse. Returns the branch, the commit it forks and
  that commit's basis. My context is supplied.

  Example:
  (my.agent/create-branch! {:seon.store/branch :my-lane})"
  {:malli/schema [:=> [:cat :my.agent/branch-request :my.agent/branch-context]
                  [:or :my.agent/created-branch :seon.error/value]]}
  [{lane :seon.store/branch} {store :seon.store/store connection :seon.db/connection :as context}]
  (cond
    (nil? store) (branch-refusal `create-branch! "My context holds no store to branch in." {})
    (= lane (own-branch context))
    (branch-refusal `create-branch! "The cluster's own branch is shared; name a lane branch." {:seon.store/branch lane})
    :else
    (let [source @connection
          from (db/commit-id source)
          result (registry/fork! {:seon.store/store store :seon.store/branch lane :seon.cluster.registry/from from
                                  :seon.db/db source})]
      (cond
        (:seon.error/at result) result
        (:seon.cluster/created? result)
        (assoc result :seon.cluster.registry/from from :seon.db/basis-t (db/basis-t source))
        :else
        (branch-refusal `create-branch! "The branch already exists; retire it or name another." {:seon.store/branch lane})))))

(defn- shared-branch?
  "True when `lane` is the branch of the cluster my connection's facts name:
  the one branch every agent of that cluster shares. An agent's own branch is a
  fork that inherits the cluster's name row, so the comparison, never the
  spelling of the branch, tells them apart."
  {:malli/schema [:=> [:cat :my.agent/branch-context :seon.store/branch] :boolean]}
  [{connection :seon.db/connection} lane]
  (boolean (some #(= lane (cluster.branch/cluster-branch %))
                 (db/q '[:find [?name ...] :where [_ :seon.cluster/name ?name]] @connection))))

(defn- retire-own-branch!
  "Record the retirement of my own branch now and unlink it after my run exits.

  Admission closes on the agent rows my branch holds (the fact `retire-unclaimed!`
  and the run writer read); the owner's release of the execution (`release-context!`, or boot's
  `retire-unclaimed!`) unlinks the branch, so the run asking is never the one
  released. The close is written in my connection's custody, so the asking
  run's open rides it and that run settles; a refused close is the answer.
  Answers `:my.agent/retiring?`."
  {:malli/schema [:=> [:cat :seon.store/branch :my.agent/branch-context]
                  [:or :my.agent/retired-branch :seon.error/value]]}
  [lane {connection :seon.db/connection}]
  (let [open (db/q '[:find [?agent ...] :where [?agent :seon.agent/id _]
                     (not [?agent :seon.agent/admission-closed-tx])]
                   @connection)
        closed (when (seq open)
                 (db/call-with-custody {:seon.db/connection connection}
                   #(db/transact! connection (mapv (fn [row] [:db/add row :seon.agent/admission-closed-tx "datomic.tx"]) open))))]
    (if (:seon.error/at closed)
      closed
      {:seon.store/branch lane :my.agent/retired? false :my.agent/retiring? true})))

(defn retire-branch!
  "Retire a lane branch: its hosted agent first (admission closed, its graphs
  joined, its execution released), then any execution still held on it, then
  its roster entry. A branch an agent still holds refuses. My cluster's shared
  branch refuses. My own branch records its retirement now and unlinks once my
  run exits (`:my.agent/retiring?`). Its data goes when retention collects. My
  context is supplied.

  Example:
  (my.agent/retire-branch! {:seon.store/branch :my-lane})"
  {:malli/schema [:=> [:cat :my.agent/branch-request :my.agent/branch-context]
                  [:or :my.agent/retired-branch :seon.error/value]]}
  [{lane :seon.store/branch} {store :seon.store/store routing :seon.agent/routing :as context}]
  (cond
    (nil? store) (branch-refusal `retire-branch! "My context holds no store to retire from." {})
    (shared-branch? context lane)
    (branch-refusal `retire-branch! "The cluster's shared branch is never retired; name a lane branch or my own." {:seon.store/branch lane})
    (= lane (own-branch context)) (retire-own-branch! lane context)
    :else
    (let [child #(some-> routing deref (get-in [:seon.agent/children lane]) deref)
          retired (some-> (:seon.cluster.host/host (child)) deref ((requiring-resolve 'seon.cluster.host/retire!)))
          held (vec (:seon.agent/contexts (child)))
          agents (vec (keep (comp :seon.agent/id second) held))]
      (cond
        ;; the host's refusal (a refused read or admission close) is the answer
        (:seon.error/at retired) retired
        (seq agents)
        (branch-refusal `retire-branch! "Agents hold this branch; disarm them first."
                        {:seon.store/branch lane :seon.agent/id agents})
        :else
        (do (doseq [[_ execution] (sort-by (comp :seon.agent/owns-connection? second) held)]
              ((requiring-resolve 'seon.cluster.agent/release-context!) execution))
            ;; the roster owner's refusal (a connection this process still
            ;; holds on the branch) is the answer, never an escape
            (try (registry/retire-branch! {:seon.store/store store :seon.store/branch lane})
                 {:seon.store/branch lane :my.agent/retired? true}
                 (catch clojure.lang.ExceptionInfo refused
                   (if-let [rule (:seon.cluster.registry/refused (ex-data refused))]
                     (branch-refusal `retire-branch! (str (ex-message refused) "; release its connection, then retire it.")
                                     {:seon.store/branch lane :seon.error/expected rule})
                     (throw refused)))))))))
