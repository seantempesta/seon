(ns seon.cluster.agent
  "The agent-graph blueprint: every agent is its own sequential process
  hosted as a running flow (F1, sealed contract
  docs/prds/sci-execution-runtime/plan/f1-agent-graph-contracts-2026-07-28.md).

  ONE BLUEPRINT stamps EVERY agent's graph — a pure function of
  (agent-id, cluster-handle) returning a `create-flow` definition. Two
  agents differ only in the agent-id their procs carry and the mailbox
  channel routed to them. No dispatcher, no active set, no scheduler
  entity: parked between episodes (one parked virtual thread + ~8.5 KB
  per proc, flow-mechanics §1), kicked off by the messages the agent
  receives, pausable/resumable through flow's own graph commands,
  parallel across agents by construction.

  THREE PROCS, all pinned `:io`, all var step-fns through the one
  `seon.flow/var-process` seam (F0(a)):

  - `:seon.agent/mailbox` — total and instant: forward one payload-free
    `:seon.agent/episode` signal downstream and count deliveries. It never reads
    the database (the turn pass owns the basis) and never blocks (its
    downstream conn is sliding-1). It exists so the graph answers
    ping/pause within microseconds at ALL times — the turn transform
    contains a multi-second provider call, and a single-proc graph
    would go deaf for its length. The pause boundary IS the episode
    boundary: pausing the graph pauses the mailbox instantly, so no
    new episode begins, while an in-flight turn runs to its durable
    terminal.
  - `:seon.agent/turn` — one episode pass per signal, the central pass's proven
    shape narrowed to one agent: settle this agent's orphan, pin ONE
    database value, derive `next-agent-work`, execute the situation
    through the surviving `seon.turn/turn` owner (custody law,
    pre-provider capture, terminal transactions all unchanged), then
    self-rewake into this agent's OWN mailbox when more remains.
  - `:seon.agent/schedule` — one disposable timer and fact listener scoped to this
    agent. It derives due nominal instants, atomically commits fire+message,
    and waits on `:io`; it never polls another agent's schedules.

  Evaluation currently runs inline in the turn proc. The separate bounded
  work launcher remains available through `seon.flow/submit!!`; the turn
  does not presently enter it. See the recorded work-submission issue.
  `:mixed` appears nowhere — `var-process` refuses it at construction.

  THE GRAPH IS DERIVED STATE — never stored, always re-derivable from
  (agent-id + the handle's dials). Start = stamp → start → resume → join
  the cluster's one fault channel → register the routing entry → prime
  one wake. The prime is the conservation idiom verbatim: the first
  pass derives the agent's work from FACTS, so anything committed
  before the graph existed is seen, and a wake lost to any gap costs
  nothing the start prime does not recover.

  THE ROUTING ENTRY is a process-local atom (started entries by agent id;
  mailbox channels by recipient ENTITY id, because that is what a
  committed `message/to` datom carries) — a disposable artifact rebuilt
  by starting at boot, never a fact. The cluster's one `wake/route!`
  listener delivers through it; `:seon.flow/db<->flow` (hosted in the cluster's own
  graph, R7) closes the agent-created-while-the-cluster-runs window by
  deriving unstarted agents with work or schedules under a payload-free wake,
  woken by the starting attribute the creation itself asserts.

  Crash walk: everything on any channel is losable by the transport
  law. Buffered wakes → boot re-stamps every graph and primes each
  mailbox once. Boot closes prior open turns and interrupts unfinished
  evaluations; nothing resumes the interrupted execution. No row
  depends on a channel for recovery. A source submission is a value on
  the agent's request port until the settlement that opens its run: a
  crash before it leaves no fact, and its caller's failed call is its
  record."
  (:require [seon.error.refusal]
            [clojure.core.async :as async]
            [clojure.core.protocols :as core.protocols]
            [clojure.core.async.impl.protocols :as async.protocols]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [seon.await :as await]
            [seon.batch]
            [seon.bootstrap :as bootstrap]
            [seon.ai :as ai]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.db :as db]
            ;; loaded for agents: the root opening names (seon.cluster.status/agents {})
            [seon.cluster.status]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.squash :as squash]
            [seon.cluster.store :as store]
            [seon.id :as id]
            [seon.env :as env]
            [seon.error :as error]
            [seon.fault :as fault]
            [seon.schema :as schema]
            [seon.program :as program]
            [seon.render.route :as route]
            [seon.flow :as seon.flow]
            [seon.schedule :as schedule]
            [seon.task :as task]
            [seon.cluster.wake :as wake]
            [seon.sci.eval :as sci.eval]
            [seon.sci.reader :as reader]
            [seon.schema.edn :as schema.edn])
  (:import [java.util Date LinkedList]
           [java.util.concurrent ExecutorService]
           [java.util.concurrent.atomic AtomicReference]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(deftype CountedSlidingBuffer [^LinkedList buffer ^long capacity dropped]
  async.protocols/UnblockingBuffer
  async.protocols/Buffer
  (full? [_] false)
  (remove! [_] (.removeLast buffer))
  (add!* [this value]
    (when (= (.size buffer) capacity)
      (async.protocols/remove! this)
      (swap! dropped inc))
    (.addFirst buffer value)
    this)
  (close-buf! [_])
  clojure.lang.Counted
  (count [_] (.size buffer))
  async.protocols/Capacity
  (capacity [_] capacity)
  core.protocols/Datafiable
  (datafy [_]
    {:type 'CountedSlidingBuffer
     :count (.size buffer)
     :capacity capacity
     :dropped @dropped}))

(defn wake-channel
  "A sliding-one wake channel whose overwritten signals remain observable."
  {:malli/schema [:=> [:cat] :seon.flow/channel]}
  []
  (async/chan (CountedSlidingBuffer. (LinkedList.) 1 (atom 0))))

;;; ---------------------------------------------------------------------------
;;; Namespace assignment
;;; ---------------------------------------------------------------------------

(defn steward-call
  "Make `agent-id` the steward of `namespace-name` only when it has none, and
  only on the branch the agent works on.

  Invoked as `[:db.fn/call #'steward-call agent-id namespace-name]`, so
  Datahike supplies the mid-transaction database and the decision is made
  where the fact is — no caller pre-read that the writer would re-decide.
  Assignment is not stewardship: a second agent assigned the same namespace
  leaves the first agent's stewardship standing. A row whose
  `:seon.agent/branch` names another branch than this database's (a hosted
  agent's row on its cluster) claims nothing here: its namespace's faults
  route to this branch's stewards, and its claims belong on its own branch."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id :seon.ns/name]
                  :seon.store/transaction-data]}
  [database agent-id namespace-name]
  (let [steward (db/q '[:find ?steward .
                        :in $ ?namespace-name
                        :where
                        [?namespace :seon.ns/name ?namespace-name]
                        [?namespace :seon.ns/steward ?steward]]
                      database namespace-name)
        works-on (db/q '[:find ?branch . :in $ ?id :where [?agent :seon.agent/id ?id] [?agent :seon.agent/branch ?branch]]
                       database agent-id)]
    (doseq [read [steward works-on]]
      (when (or (:seon.db/invalid-read read) (:seon.schema/expected-value read))
        (throw (ex-info (:seon.error/message read) read))))
    (if (or steward (and works-on (not= works-on (get-in database [:config :branch]))))
      []
      [[:db/add [:seon.ns/name namespace-name] :seon.ns/steward
        [:seon.agent/id agent-id]]])))

(defn namespace-seed-call
  "Read an absent agent namespace from its source at the transaction boundary."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ns/name]
                  :seon.store/transaction-data]}
  [database namespace-name]
  (if (db/q '[:find ?namespace . :in $ ?name
              :where [?namespace :seon.ns/name ?name]]
            database namespace-name)
    []
    (let [source (pr-str (list 'ns namespace-name
                               '(:require [my.message] [my.turn] [seon.db]
                                          [seon.bootstrap :refer [help dir doc]]
                                          [clojure.test :refer [deftest is]])))
          events (reader/read
                  {:seon.sci.reader/text source
                   :seon.config.eval.result/max-source (count source)})
          _ (when (map? events)
              (throw (ex-info (:seon.error/message events) events)))
          event (first events)
          _ (when-let [failure (:seon.sci.reader/error event)]
              (throw (ex-info (:seon.error/message failure) failure)))
          row (program/declaration-row
               (db/carried-projection database) event :contracted :agent)]
      (when-not row
        (let [message (str "No namespace declaration row derives for " namespace-name
                           "; an agent namespace name must be an unqualified symbol.")]
          (throw (ex-info message {:seon.error/message message
                                   :seon.error/operation `namespace-seed-call
                                   :seon.ns/name namespace-name}))))
      [row])))

(defn branch-call
  "Assign `agent-id` the branch the transacting database holds.

  Invoked as `[:db.fn/call #'branch-call agent-id]`, so the writer records the
  branch its own connection holds; custody is never inferred from a cluster
  name, which several worlds in one store share."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  :seon.store/transaction-data]}
  [database agent-id]
  [[:db/add [:seon.agent/id agent-id] :seon.agent/branch
    (get-in database [:config :branch])]])

(defn creation-tx
  "Create one agent with its namespace in this database branch.

  Transaction data. Without an explicit `:seon.agent/branch` the agent's
  branch is the one the transacting connection holds (`branch-call`).
  The agent names the cluster it is created in (`:seon.agent/cluster`),
  whose config is its own. Context is derived from the entity graph, so
  agent creation stores no blocks or other presentation state. The namespace assignment is not
  unique — several agents may share one namespace — so stewardship is
  decided inside the transaction by `steward-call`: the creating agent
  stewards a namespace that has none yet, and never displaces an existing
  steward."
  {:malli/schema [:=> [:cat :seon.agent/creation-request]
                  :seon.agent/creation-tx]}
  [{agent-id :seon.agent/id
    namespace-name :seon.ns/name
    cluster-name :seon.cluster/name
    branch :seon.agent/branch}]
  (let [namespace-ref [:seon.ns/name namespace-name]]
    (-> [[:db.fn/call #'namespace-seed-call namespace-name]
         (cond-> {:db/id (str "agent:" agent-id)
                  :seon.agent/id agent-id
                  ;; the agent's config is its cluster's (seon.config/agent-cluster)
                  :seon.agent/cluster cluster-name
                  :seon.agent/namespace namespace-ref
                  :seon.agent/settings {:seon.config/agent (str "agent:" agent-id)}
                  :seon.agent/runtime {:seon.runtime/agent (str "agent:" agent-id)}}
           branch (assoc :seon.agent/branch branch))]
        (cond-> (nil? branch) (conj [:db.fn/call #'branch-call agent-id]))
        (conj [:db.fn/call #'steward-call agent-id namespace-name]))))

(defn situation-form
  "Return the opening question and bare root form for an agent situation."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [_unit]
  {:seon.repl/comment
   "; A new run just opened. Why am I awake — do I have messages?"
   :seon.repl/form '(help)})

(def ^:private identity-selector
  '[:seon.agent/id
    {:seon.agent/namespace
     [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}
    :seon.agent/cluster])

(defn identity-form
  "Return the database read that reproduces an agent's identity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [unit]
  {:seon.repl/comment ";; I should know my identity, namespace, and its steward."
   :seon.repl/form (list 'seon.db/pull (list 'quote identity-selector)
                         [:seon.agent/id (:seon.agent/id unit)])})

(defn whoami
  "Describe the current agent's identity, namespace, and cluster.

  SCI supplies missing database and agent entries in the request map.
  Pass :seon.agent/id to inspect another agent, or :seon.db/db
  to query an explicit snapshot.
  Use seon.db/pull when the identity attributes themselves are needed as data."
  {:malli/schema
   [:=> [:cat :seon.agent/identity-request]
    [:or [:maybe :string] :seon.error/value]]}
  [request]
   (let [agent-data (db/pull (:seon.db/db request) identity-selector
                           [:seon.agent/id (:seon.agent/id request)])]
     (if (:seon.db/invalid-read agent-data)
       agent-data
       (when-let [agent-id (:seon.agent/id agent-data)]
         (let [namespace-name
               (get-in agent-data [:seon.agent/namespace :seon.ns/name])
               cluster-name (:seon.agent/cluster agent-data)]
           (str "Agent     " agent-id
                (when namespace-name (str "\nNamespace " namespace-name))
                (when cluster-name (str "\nCluster   " cluster-name))))))))

(defn render-identity-ai
  "My identity as data: my id, my namespace and its steward, offering
  `(help)`."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.agent/identity-view :seon.db/error-result]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [agent-data (db/pull database identity-selector [:seon.agent/id agent-id])]
    (if (db/read-refusal? agent-data)
      agent-data
      (assoc agent-data :seon.render/operations
             (cond-> ['(help)] (= "root" agent-id) (conj '(seon.cluster.status/agents {})))))))

(defn render-id-ai
  "The identity concern read from its identifying attribute, as data."
  {:malli/schema [:=> [:cat :seon.agent/id :seon.db/database-value]
                  [:or :seon.agent/identity-view :seon.db/error-result]]}
  [agent-id database]
  (render-identity-ai {:seon.db/db database :seon.agent/id agent-id}))

(defn render-identity-html
  "Render an agent node: id, namespace, steward, the tasks naming its namespace
  as subject, and its status. The fleet belongs to the fleet page."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or [:maybe :seon.render/hiccup] :seon.error/value]]}
  [unit]
  (let [agent-data (if-let [database (:seon.db/db unit)]
                     (when-let [agent-id (:seon.agent/id unit)]
                       (db/pull database
                                '[:seon.agent/id :seon.agent/archived-tx
                                  {:seon.agent/namespace [:seon.ns/name :seon.task/tasks {:seon.ns/steward [:seon.agent/id]}]}
                                  {:seon.turn/_agent [:seon.turn/closed-tx]}]
                                [:seon.agent/id agent-id]))
                     (or (:seon.render/value unit) unit))]
    (if (or (:seon.db/invalid-read agent-data) (:seon.schema/expected-value agent-data))
      agent-data
      (let [{agent-id :seon.agent/id {namespace-name :seon.ns/name tasks :seon.task/tasks
                                      {steward :seon.agent/id} :seon.ns/steward} :seon.agent/namespace} agent-data
            turns (:seon.turn/_agent agent-data)
            agent-link #(vector :a {:href (route/path :seon.render.route/agent {:id %})} %)
            entry (fn [label value] [:div [:dt label] [:dd value]])]
        (when agent-id
          [:article {:class "seon-family-entry seon-agent-identity-entry"}
           [:header [:h3 "Identity"]]
           (into [:dl]
                 (cond-> [(entry "Agent" (agent-link agent-id))]
                   namespace-name
                   (conj (entry "Namespace" [:a {:href (route/path :seon.render.route/namespace {:namespace (str namespace-name)})}
                                             [:code (str namespace-name)]]))
                   steward (conj (entry "Steward" (agent-link steward)))
                   namespace-name
                   (conj (entry "Tasks" (if (seq tasks) (into [:span] (interpose " " (map #(vector :code (str %)) (sort tasks)))) "None")))
                   (:seon.db/db unit)
                   (conj (entry "Status" (str (cond (:seon.agent/archived-tx agent-data) "Archived"
                                                    (some (complement :seon.turn/closed-tx) turns) "Turn open"
                                                    :else "Idle")
                                              " · " (count turns) (if (= 1 (count turns)) " turn" " turns"))))))])))))

(defn render-id-html
  "Render the identity unit as a compact labeled card.

  The unit's stored value is `:seon.agent/id`; call preparation
  supplies the database, so the card reads id, namespace, and cluster from the
  same three attributes [[whoami]] reads."
  {:malli/schema [:=> [:cat :seon.agent/id :seon.db/database-value]
                  :seon.render/hiccup]}
  [agent-id database]
  (let [rendered (render-identity-html {:seon.db/db database
                                        :seon.agent/id agent-id})]
    (cond
      (or (:seon.db/invalid-read rendered) (:seon.schema/expected-value rendered))
      [:article {:class "seon-family-entry seon-agent-identity-entry"}
       [:p {:class "seon-agent-identity-unavailable"}
        (:seon.error/message rendered)]]

      (nil? rendered)
      [:article {:class "seon-family-entry seon-agent-identity-entry"}
       [:p {:class "seon-agent-identity-unavailable"}
        (str "No agent entity carries the id " (pr-str agent-id) ".")]]

      :else rendered)))

(defn render-situation-ai
  "Render the live situation as concise orientation for the agent.

  When selected for the stored agent entity, derive the same live situation
  that `(help)` returns. When selected for that returned value, render it
  directly. Both paths therefore have one orientation value and no stored
  presentation duplicate."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [doc-line (fn [documented-var]
                   (first (str/split-lines (:doc (meta documented-var)))))
        situation
        (if (contains? unit :seon.agent/unread-message-count)
          unit
          (when (and (:seon.db/db unit) (:seon.agent/id unit))
            (bootstrap/situation (:seon.db/db unit)
                                 (:seon.agent/id unit))))]
    (when (and situation (not (:seon.bootstrap/missing-agent-id situation)))
      (str "You are agent " (:seon.agent/id situation)
           " in namespace "
           (second (:seon.agent/namespace-ref situation)) ". "
           "You have " (:seon.agent/unread-message-count situation)
           " unread message"
           (when-not (= 1 (:seon.agent/unread-message-count situation))
             "s")
           ". " (:seon.turn/turns-remaining situation)
           " turns remain in this episode."
           (when-let [trigger (:seon.turn/trigger situation)]
             (str " This run exists because of message " (second trigger) "."))
           "\nInjected callables: help — " (doc-line #'bootstrap/help)
           " dir — " (doc-line #'bootstrap/dir)
           " doc — " (doc-line #'bootstrap/doc)
           "\nEvery run ends with my.turn/wait (a reply is my.message/send first) or my.task/send-back!; "
           "an undisposed run is unfinished work."))))

(defn render-creation-ai
  "`:seon.render/ai` — the compact result of creating or resuming an agent."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [agent-id (:seon.agent/id unit)]
    (str "Agent " agent-id " · namespace " (:seon.ns/name unit)
         " · cluster " (:seon.cluster/name unit) ".")))

(defn render-creation-html
  "`:seon.render/html` — the compact agent-creation result card."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [agent-id (:seon.agent/id unit)]
    [:article {:class "seon-family-entry seon-agent-creation-entry"}
     [:h3 (str "Agent " agent-id)]
     [:dl
      [:div [:dt "Namespace"] [:dd [:code (str (:seon.ns/name unit))]]]
      [:div [:dt "Cluster"] [:dd (:seon.cluster/name unit)]]]]))

(defn assigned-to
  "Agent ids assigned to work in `namespace-name`, sorted; empty only when none.
  An unreadable database refuses with its invalid read, so a failed read can
  never look unowned and drive a caller to create an owner.

  Assignment is not stewardship: any assigned agent evaluates in the
  namespace, and stewardship only decides where that namespace's faults and
  requests are routed. Surfaces that need one evaluating agent take the first."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ns/name]
                  [:vector :seon.agent/id]]}
  [db namespace-name]
  (let [ids (db/q '[:find [?agent-id ...]
                    :in $ ?namespace-name
                    :where
                    [?namespace :seon.ns/name ?namespace-name]
                    [?agent :seon.agent/namespace ?namespace]
                    [?agent :seon.agent/id ?agent-id]]
                  db namespace-name)]
    (when (or (:seon.db/invalid-read ids) (:seon.schema/expected-value ids))
      (throw (ex-info (:seon.error/message ids) ids)))
    (vec (sort ids))))

(defn steward-of
  "The agent id stewarding `namespace-name`, or nil only when none does.
  An unreadable database refuses with its invalid read.

  Stewardship is the declared `:seon.ns/steward` fact on the namespace, not
  an inversion of assignment: several agents may be assigned one namespace,
  and exactly one of them stewards it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ns/name]
                  [:maybe :seon.agent/id]]}
  [db namespace-name]
  (let [steward (db/q '[:find ?agent-id .
                        :in $ ?namespace-name
                        :where
                        [?namespace :seon.ns/name ?namespace-name]
                        [?namespace :seon.ns/steward ?agent]
                        [?agent :seon.agent/id ?agent-id]]
                      db namespace-name)]
    (when (or (:seon.db/invalid-read steward) (:seon.schema/expected-value steward))
      (throw (ex-info (:seon.error/message steward) steward)))
    steward))

;;; ---------------------------------------------------------------------------
;;; The agent proc steps
;;; ---------------------------------------------------------------------------

(defn mailbox-step
  "The mailbox transform, in Flow's four arities.
  Total and instant: one payload-free `:seon.agent/episode` signal downstream per
  wake, deliveries counted in the ping map. The wake channel arrives as
  an in-port — the same channel the routing entry names — so listener
  routing, the start prime, and the self-rewake all target ONE edge."
  {:malli/schema [:function [:=> [:cat] [:map]] [:=> [:cat :map] :map] [:=> [:cat :map :keyword] :map] [:=> [:cat :map :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "core.async.flow supplies per-port messages of different declared shapes and accepts heterogeneous non-nil output messages; the port determines each message contract.", :gen/elements [nil false 0 "" :k [] {}]}]] [:tuple :map [:maybe [:map-of :keyword [:vector [:some {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "core.async.flow supplies per-port messages of different declared shapes and accepts heterogeneous non-nil output messages; the port determines each message contract.", :gen/elements [false 0 "" :k [] {}]}]]]]]]]}
  ([]
   {:ins {}
    :outs {:seon.agent/episode
           "One payload-free episode signal: a wake says only \"look\"."}
    :workload :io
    :ping-map-fn (fn [state] (select-keys state [:seon.agent/deliveries]))})
  ([args]
   (assoc args
          ::flow/in-ports {:seon.agent/wake (:seon.cluster.wake/channel args)}
          :seon.agent/deliveries 0))
  ([state _transition]
   state)
  ([state _input _message]
   [(update state :seon.agent/deliveries inc)
    {:seon.agent/episode [:seon.agent/wake]}]))

(defn- interrupted?
  "True when `failure`'s cause chain holds an `InterruptedException`. The walk
  stops at the first repeated link, so a cyclic chain ends."
  {:malli/schema [:=> [:cat :seon.error/throwable] :boolean]}
  [failure]
  (loop [link failure seen #{}]
    (cond
      (or (nil? link) (contains? seen link)) false
      (instance? InterruptedException link) true
      :else (recur (.getCause ^Throwable link) (conj seen link)))))

(defn turn-step
  "The agent graph's turn proc: `seon.turn/step`, marking its running thread.

  Flow runs a transform on its proc's own loop thread
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:305`).
  For the extent of ONE transform this publishes that thread in the
  handle's `:seon.agent/turn-thread` holder, which is exactly the work an
  orderly stop may interrupt. On exit it clears the holder AND the thread's
  interrupt status under the holder's monitor: a stop's interrupt that the
  transform absorbed must never reach Flow's loop, where an interrupted
  `alts!!` leaves its handler registered and would consume the queued
  `::flow/stop` (`clojure/core/async.clj:356`). The other arities are
  `seon.turn/step`'s own."
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat :map] :map]
                  [:=> [:cat :map :keyword] :map]
                  [:=> [:cat :map :keyword :seon.schema/value]
                   [:tuple :map [:maybe [:map [::flow/report [:vector :seon.turn.loop/pass-report]]]]]]]}
  ([] (turn/step))
  ;; the agent's request port is an in-port of this proc, beside the episode conn
  ([args] (assoc (turn/step args) ::flow/in-ports
                 {:seon.agent/request (:seon.agent/requests (:seon.turn.loop/cluster args))}))
  ([state transition] (turn/step state transition))
  ([state input message]
   (let [^AtomicReference holder
         (:seon.agent/turn-thread (:seon.turn.loop/cluster state))
         world seon.batch/*world*]
     ;; THE PASS COUNTS ITS OWN CALLS: a fresh sink in the proc's world for this one
     ;; transform, which `seon.turn/step` reads into its reply's cost
     (with-bindings (if world {#'seon.batch/*world* (assoc world :seon.batch/sink (seon.batch/sink))} {})
     (if-not holder
       (turn/step state input message)
       (do
         (locking holder (.set holder (Thread/currentThread)))
         (try
           (turn/step state input message)
           ;; the declared case: this transform was interrupted by its own
           ;; stop (`interrupt-turn!` marks the holder served), whether the
           ;; interrupt arrives bare or wrapped (Datahike's async layer throws
           ;; it inside an `ex-info`). Interrupted execution never resumes;
           ;; the queued stop follows, so this is no fault.
           (catch Throwable failure
             (if (and (= ::interrupted (.get holder)) (interrupted? failure))
               [state nil]
               (throw failure)))
           (finally
             (locking holder
               (.set holder nil)
               ;; a stop's interrupt, already served
               (Thread/interrupted))))))))))

;;; ---------------------------------------------------------------------------
;;; The ONE blueprint
;;; ---------------------------------------------------------------------------

(defn graph-definition
  "The ONE blueprint: (agent-id, handle) → a `create-flow` definition.
  Pure data — `create-flow` allocates no threads, so a stamped
  definition costs nothing until started. The handle already carries
  this agent's own mailbox and schedule channels plus its started-ready
  completion permit (`start-flow!` puts them there), so the definition is a
  projection of its request. The one
  conn rides `(sliding-buffer 1)`: a wake says only \"look\", the turn
  pass derives ALL of this agent's work from one fresh database value,
  so coalescing is free by the same argument that made the central
  pass's wake safe."
  {:malli/schema [:=> [:cat :seon.agent/blueprint-request] :map]}
  [{handle :seon.turn.loop/cluster agent-id :seon.agent/id}]
  ;; Every proc in this agent's graph carries the cluster's environment
  ;; SCOPED to this agent, so work leaving a proc on any thread still names
  ;; which cluster and which agent it belongs to.
  (let [environment (env/scope (env/of handle)
                               {:seon.agent/id agent-id})]
    {:procs
      {:seon.agent/mailbox
       {:proc (seon.flow/var-process
               #'mailbox-step :io
               (env/carry {:seon.cluster.wake/channel
                           (:seon.cluster.wake/channel handle)}
                          environment))}
       :seon.agent/turn
       {:proc (seon.flow/var-process
               #'turn-step :io
               (env/carry {:seon.turn.loop/cluster handle
                           :seon.agent/id agent-id}
                          environment))
        :chan-opts
        {:seon.agent/episode
         {:buf-or-n (CountedSlidingBuffer. (LinkedList.) 1 (atom 0))}}}
       :seon.agent/schedule
       {:proc (seon.flow/var-process
               #'schedule/schedule-step :io
               (env/carry {:seon.turn.loop/cluster handle
                           :seon.agent/id agent-id
                           :seon.schedule/channel
                           (:seon.schedule/channel handle)}
                          environment))}}
     :conns [[[:seon.agent/mailbox :seon.agent/episode] [:seon.agent/turn :seon.agent/episode]]]}))

;;; ---------------------------------------------------------------------------
;;; The routing entry and the lifecycle
;;; ---------------------------------------------------------------------------

(defn routing
  "A fresh roster: the one process-local atom of one execution's live objects.
  `:seon.agent/started` by agent id (the management view); `:seon.agent/channels` by
  recipient ENTITY id (the wake handler's one-lookup delivery);
  `:seon.agent/contexts` the execution handles acquired on this execution's branch,
  by `[branch agent-id]`; `:seon.agent/children` the roster of each other branch
  acquired through it, by branch; `:seon.flow/graph` the cluster graph, joined
  before it resumes."
  {:malli/schema [:=> [:cat] :seon.agent/routing]}
  []
  (atom {:seon.agent/started {} :seon.agent/channels {} :seon.agent/contexts {}}))

(defn- child-roster!
  "The roster `roster` records for `branch`, recorded when absent or released.
  The child names its parent, so the release that owns its branch removes the
  record; a released child still recorded is replaced, never reused."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.agent/branch] :seon.agent/routing]}
  [roster branch]
  (let [fresh (doto (routing) (swap! assoc :seon.agent/parent roster))]
    (get-in (swap! roster update-in [:seon.agent/children branch]
                   #(if (and % (not (:seon.agent/released? @%))) % fresh))
            [:seon.agent/children branch])))

(defn started
  "The started entry for `agent-id`, or nil."
  {:malli/schema [:=> [:cat :seon.agent/routing
                       :seon.agent/id]
                  [:maybe :seon.agent/started]]}
  [routing agent-id]
  (get-in @routing [:seon.agent/started agent-id]))

(declare submit-source-in-projection start-flow!)

(defn- segmenter
  "The function that segments a submission's text into its ordered sources:
  `seon.turn/planned-sources` for a model reply unless the request names another
  stage, which must declare exactly that default's contract. A symbol that
  resolves to nothing, or to a function with another contract, refuses by name."
  {:malli/schema [:=> [:cat [:maybe :qualified-symbol] :seon.agent/id]
                  [:or :seon.instrument/callable :seon.agent/source-submission-refused-error]]}
  [stage agent-id]
  (let [default #'turn/planned-sources
        chosen (if stage (try (requiring-resolve stage) (catch Exception _ nil)) default)]
    (if (and (var? chosen) (= (:malli/schema (meta chosen)) (:malli/schema (meta default))))
      chosen
      (seon.error.refusal/error :seon.agent/source-submission-refused-error
       {:seon.error/layer :seon.agent/source-submission
        :seon.error/operation `submit-source!
        :seon.agent/refused-source-agent agent-id
        :seon.error/message "The segmenter must name a function declaring seon.turn/planned-sources' contract."
        :seon.error/member :seon.agent/segmenter
        :seon.error/expected (:malli/schema (meta default))
        :seon.error/offending stage}))))

(defn submit-source!
  "Submit system-authored source as one run of the agent, delivered as a value.

  Parsing, delimiter repair, evaluation, result bindings, and settlement are
  the same owners used for a model reply. The run request (the input of
  `seon.turn/system-run-call`) and its staged reply travel to the started agent's
  request port; its turn proc asks the writer's decision, evaluates, and the
  run's open is written once, by the form's first durable write or else by
  the settlement, then answers the returned reply port.
  Nothing is written here. The writer decides at the pass whether the agent is
  busy or its admission closed; a full request port refuses as busy. A
  supplied starting namespace is the caller's parse-time decision; the open
  refuses if the agent's assignment changed."
  {:malli/schema
   [:=> [:catn [:request :seon.agent/source-submission-request]]
    [:or :seon.agent/source-submission-result :seon.agent/source-submission-refused-error]]}
  [{handle :seon.turn.loop/cluster :as request}]
  ;; The submission thread otherwise pays the cold projection rebuild
  ;; (measured 728 → 147 ms per source turn); the handle carries its world.
  (if-let [projection-state (:seon.sci.eval/projection-state handle)]
    (schema/call-with-projection-state
     projection-state
     #(submit-source-in-projection request))
    (submit-source-in-projection request)))

(defn- submit-source-in-projection
  {:malli/schema [:=> [:cat :seon.agent/source-submission-request]
                  [:or :seon.agent/source-submission-result :seon.agent/source-submission-refused-error]]}
  [{handle :seon.turn.loop/cluster
    routing :seon.agent/routing
    agent-id :seon.agent/id
    starting-ns :seon.turn/starting-ns
    stage :seon.agent/segmenter
    text :seon.cluster.reply/text}]
  (let [connection (:seon.db/connection handle)
        segment (segmenter stage agent-id)
        database @connection
        namespace-name
        (cond
          ;; a requested name is where the run starts, as `in-ns` would: the
          ;; run writer creates its namespace entity when it has none yet
          (vector? starting-ns) (second starting-ns)
          starting-ns
          (let [namespace-row (db/pull database [:seon.ns/name] starting-ns)]
            (if (:seon.db/invalid-read namespace-row)
              namespace-row
              (:seon.ns/name namespace-row)))
          ;; segmentation reads where the writer will start the run: where
          ;; the agent's previous run ended, else its assigned namespace
          :else
          (or (some->> (db/pull database [:db/id] [:seon.agent/id agent-id]) :db/id
                       (turn/previous-ending-namespace database))
              (db/q '[:find ?namespace-name .
                    :in $ ?agent-id
                    :where
                    [?agent :seon.agent/id ?agent-id]
                    [?agent :seon.agent/namespace ?namespace]
                    [?namespace :seon.ns/name ?namespace-name]]
                    database agent-id)))]
    (cond
      (map? segment) segment

      (or (:seon.db/invalid-read namespace-name)
          (:seon.schema/expected-value namespace-name))
      (assoc namespace-name :seon.agent/refused-source-agent agent-id)

      (nil? namespace-name)
      (seon.error.refusal/error :seon.agent/source-submission-refused-error
       {:seon.error/layer :seon.agent/source-submission
        :seon.error/operation `submit-source!
        :seon.agent/refused-source-agent agent-id
        :seon.error/message "Source submission requires an agent with an assigned namespace."
        :seon.error/member :seon.agent/namespace
        :seon.error/expected :seon.ns/name})

      :else
      (let [max-source
            (get-in handle [:seon.sci.admit/caps
                            :seon.config.eval.result/max-source])
            sources (segment text namespace-name max-source)]
        (if (:seon.cluster.reply/no-forms sources)
          (assoc sources :seon.agent/refused-source-agent agent-id)
          ;; A SUBMISSION'S RUN IS ITS OWN IDENTITY, minted here. It was
          ;; `turn/next-id` over this caller's connection value: the agent's
          ;; previous run is answered when its settlement is applied, before
          ;; the connection commits it, so the count was stale and the new
          ;; run repeated the settled run's id (the writer refused it
          ;; agent-already-running). The request port serializes submissions;
          ;; nothing needs two submitters to derive one id.
          (let [run-id (id/id)
                staged-reply (turn/stage-reply! connection text)
                reply (async/promise-chan)
                request
                {:seon.turn/id run-id
                 :seon.agent/reply reply
                 :seon.blob/staged-writes (:seon.blob/staged-writes staged-reply)
                 :seon.agent/run-request
                 (merge (dissoc staged-reply :seon.blob/staged-writes)
                        ;; only a requested start is the caller's; the writer decides the rest
                        (cond-> {:seon.agent/id agent-id :seon.turn/id run-id :seon.db.process/id (:seon.db.process/id handle) :seon.turn/opened-tx "datomic.tx" :seon.turn/reply-size (count (pr-str sources)) :seon.turn/sources sources}
                          starting-ns (assoc :seon.turn/starting-ns [:seon.ns/name namespace-name])))}
                ;; the request port is the started graph's; starting is idempotent
                requests (:seon.agent/requests
                          (start-flow! {:seon.turn.loop/cluster handle :seon.agent/id agent-id
                                 :seon.agent/routing routing}))]
            (if (async/offer! requests request)
              {:seon.turn/id run-id :seon.agent/reply reply}
              (seon.error.refusal/error :seon.agent/source-submission-refused-error
               {:seon.error/layer :seon.agent/source-submission
                :seon.error/operation `submit-source!
                :seon.agent/refused-source-agent agent-id
                :seon.error/message "The agent is busy: a submission already waits on its request port."
                :seon.error/member :seon.agent/requests
                :seon.error/expected :seon.agent/request
                :seon.error/offending run-id}))))))))

(defn evaluate-source!
  "Evaluate `text` as one ordinary run of the agent and answer that run once it
  has settled: `submit-source!`, then the pass's reply awaited under the
  cluster's `:seon.config.agent/turn-completion-backstop-ms`.

  The run is one value from submission to settlement: the agent's turn proc
  receives it on its request port and answers on its reply port after the one
  transaction that opens, evaluates and settles it. A run the writer refuses
  to open answers its evaluated values, unrecorded, with the refusal beside
  them. At the bound the answer is the await's
  declared timeout carrying the run id; a pass that escaped answers its error
  value as the refusal, and one a stop ended closes its reply (the await's
  closed error); nothing replays. The run starts
  in the requested namespace, else where the agent's previous run ended, else
  its assigned namespace; a form moves the evaluation elsewhere with `in-ns`,
  as at a REPL."
  {:malli/schema [:=> [:cat :seon.agent/source-submission-request]
                  [:or :seon.agent/source-submission-result :seon.agent/source-submission-refused-error
                   :seon.await/timeout-error :seon.await/closed-error]]}
  [{handle :seon.turn.loop/cluster agent-id :seon.agent/id :as request}]
  (let [bound (:seon.config.agent/turn-completion-backstop-ms
               (config/effective @(:seon.db/connection handle) (:seon.cluster/name handle)))]
    (if-not (pos-int? bound)
      (seon.error.refusal/error :seon.agent/source-submission-refused-error
       {:seon.error/layer :seon.agent/source-submission
        :seon.error/operation `evaluate-source!
        :seon.agent/refused-source-agent agent-id
        :seon.error/message "The cluster declares no turn-completion bound to await the run under."
        :seon.error/member :seon.config.agent/turn-completion-backstop-ms
        :seon.error/offending bound})
      (let [{run-id :seon.turn/id reply :seon.agent/reply :as submitted} (submit-source! request)]
        (if-not reply
          submitted
          (let [answer (await/await!
                        {:seon.await/bound {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
                                            :seon.await/config-value bound}
                         :seon.await/diagnostic {:seon.error/layer :seon.agent/source-submission
                                                 :seon.error/operation `evaluate-source!
                                                 :seon.error/offending run-id}
                         :seon.await/port-operations [reply]})]
            (cond
              ;; THE ANSWER IS THE SETTLEMENT'S OWN VALUE. The pass reports the
              ;; database its terminal settlement left (`:seon.turn.loop/basis`);
              ;; callers read the run there, never on a later connection value.
              ;; A settlement the writer refused before the run opened left no
              ;; run; with no evaluated form to answer, its refusal is the answer.
              (and (:seon.turn.loop/refusal answer)
                   (empty? (:seon.turn.loop/evaluated-sources answer))
                   (let [basis (:seon.turn.loop/basis answer)]
                     (or (nil? basis)
                         (nil? (db/q '[:find ?run . :in $ ?id :where [?run :seon.turn/id ?id]]
                                     basis run-id)))))
              (assoc (:seon.turn.loop/refusal answer) :seon.agent/refused-source-agent agent-id)

              ;; A refused settlement keeps every evaluated value (recorded when
              ;; the run opened, else answered unrecorded, each naming the
              ;; refusal), and the refusal is answered beside them, never dropped.
              (:seon.turn.loop/outcome answer)
              (cond-> (merge {:seon.turn/id run-id}
                             (select-keys answer [:seon.turn.loop/refusal :seon.turn.loop/evaluated-sources :seon.turn.loop/tests
                                                 :seon.turn.loop/stages :seon.cost/counts]))
                (:seon.turn.loop/basis answer) (assoc :seon.db/db (:seon.turn.loop/basis answer)))
              ;; At the bound the run may still settle: the timeout carries its id.
              ;; A closed reply (retirement stopped the graph first) settled nothing.
              (:seon.await/elapsed-ms answer) (assoc answer :seon.turn/id run-id)
              :else (assoc answer :seon.agent/refused-source-agent agent-id))))))))

(defn fenced?
  "True when this agent is QUARANTINED: started, routed, mailbox closed.

  DERIVED from the two process-local artifacts that already exist —
  presence of the routing entry and the channel's own `closed?` — so
  there is no quarantine flag, no fenced-id set, and nothing to keep in
  sync. The state is real and is the one `seon.turn`'s terminal
  settlement fence creates: the agent must take no further pass over
  its still-running receipt until boot recovery marks that receipt
  interrupted, so its mailbox is closed IN PLACE while its entry stays,
  and `start-flow!`'s idempotence then leaves it alone until the next boot.

  It is a total answer for the three states an agent can be in:
  unstarted (no entry) is `false`, live is `false`, fenced is `true`. An
  ordinarily stopped agent never reads `true` because `stop-flow!` drops
  the entry BEFORE closing the channel — that ordering is what lets
  `wake/delivery` read a closed reachable route as the fence rather
  than as a teardown race."
  {:malli/schema [:=> [:cat :seon.agent/routing
                       :seon.agent/id]
                  :boolean]}
  [routing agent-id]
  (boolean
   (some-> (started routing agent-id)
           :seon.cluster.wake/channel
           async.protocols/closed?)))

(defn channels
  "The current entity-id → mailbox-channel map, for `wake/route!`."
  {:malli/schema [:=> [:cat :seon.agent/routing]
                  [:map-of :int :seon.flow/channel]]}
  [routing]
  (:seon.agent/channels @routing))

(defn fenced-route?
  "True when `channel` is the CURRENT route for `agent-eid` and closed.

  This is the delivery-side quarantine predicate. Identity matters: a
  stale channel retained by a caller after re-start is not the agent's
  fence, and a closed non-agent route is not represented here at all."
  {:malli/schema [:=> [:cat :seon.agent/routing
                       :int
                       :seon.flow/channel]
                  :boolean]}
  [routing agent-eid channel]
  (boolean
   (and (identical? channel (get (:seon.agent/channels @routing) agent-eid))
        (async.protocols/closed? channel))))

(declare stop-flow! start-claimed-flow! stop-graph! interrupt-turn! branch-route-key)

(defn- throw-causes!
  "Rethrow the first of `causes` whole, carrying every later one as suppressed."
  {:malli/schema [:=> [:cat [:vector :seon.error/throwable]] :nil]}
  [causes]
  (when-let [[first-cause & later] (seq causes)]
    (doseq [cause later] (.addSuppressed ^Throwable first-cause cause))
    (throw first-cause)))

(defn- stop-owned-graphs!
  "Stop and join every graph started in the owned `routing`, refusing new starts.

  The routing is marked released in one `swap-vals!`, so no later start is
  claimed; starts already in flight settle, then every started agent is stopped,
  each attempted even after an earlier join fails. Any failure throws with the mark kept, so no new graph
  starts: the caller keeps its connection, branch and custody for diagnosis and
  a later release, which joins what remains."
  {:malli/schema [:=> [:cat :seon.agent/routing] :nil]}
  [routing]
  (let [marked (second (swap-vals! routing assoc :seon.agent/released? true))]
    ;; each start's and acquisition's failure reaches its own caller through
    ;; the shared delay; none can be claimed after the mark
    (doseq [pending (concat (vals (::starting marked))
                            (keep ::acquiring (vals (:seon.agent/contexts marked))))]
      (try @pending (catch Throwable _ nil)))
    (let [causes (into [] (keep (fn [agent-id]
                                  (try (stop-flow! {:seon.agent/id agent-id :seon.agent/routing routing})
                                       nil
                                       (catch Throwable cause cause))))
                       (sort (keys (:seon.agent/started @routing))))]
      (throw-causes! causes))))

(defn release-context!
  "Release an execution handle once every graph it owns has exited.

  An owning handle first stops and joins every graph started in its routing
  (`stop-owned-graphs!`, which marks it released and settles its in-flight
  acquisitions), then releases every owning handle of each child roster it
  records, through this same function; a failed join or a failed descendant
  throws before anything closes, so the subtree stays reachable for a retry.
  Live handles own nothing; borrowed branches persist. Then the connection release,
  the branch unlink, the removal of the handle's own context entry and of its
  roster from the parent's children are
  each attempted even when an earlier one throws; the first cause is then
  rethrown whole, carrying every later cause as suppressed."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle] :nil]}
  [handle]
  (let [owns-connection? (:seon.agent/owns-connection? handle)
        owns-branch? (:seon.agent/owns-branch? handle)
        attempt (fn [step] (try (step) nil (catch Throwable cause cause)))
        _ (when (or owns-connection? owns-branch?)
            (let [roster (:seon.agent/routing handle)]
              (wake/unlisten! {:seon.cluster.wake/connection (:seon.db/connection handle)
                               :seon.cluster.wake/key (branch-route-key (:seon.agent/branch handle))})
              (stop-owned-graphs! roster)
              (throw-causes!
               (into [] (keep (fn [execution] (attempt #(release-context! execution))))
                     (for [child (vals (:seon.agent/children @roster))
                           execution (vals (:seon.agent/contexts @child))
                           :when (or (:seon.agent/owns-connection? execution)
                                     (:seon.agent/owns-branch? execution))]
                       execution)))))
        causes
        (into []
              (keep attempt)
              [#(when owns-connection?
                  (store/release-branch! (:seon.db/connection handle)))
               #(when owns-branch?
                  (registry/retire-branch!
                   {:seon.store/store (:seon.store/store handle)
                    :seon.store/branch (:seon.agent/branch handle)}))
               ;; the handle's own context entry leaves the roster it was
               ;; acquired in, borrowed handles included: a released handle
               ;; that stayed recorded held its branch for every later
               ;; retire (reds-triage, 2026-09-28)
               #(swap! (:seon.agent/routing handle) update :seon.agent/contexts dissoc
                       [(:seon.agent/branch handle) (:seon.agent/id handle)])
               #(let [roster (:seon.agent/routing handle)]
                  (when-let [parent (and (or owns-connection? owns-branch?)
                                         (:seon.agent/parent @roster))]
                    (swap! parent update :seon.agent/children
                           (fn [children]
                             (cond-> children
                               (identical? roster (get children (:seon.agent/branch handle)))
                               (dissoc (:seon.agent/branch handle)))))))])]
    (throw-causes! causes))
  nil)

(defn- acquire-once!
  "Acquire `key` in `roster`'s contexts once per concurrent demand, holding no monitor.
  `swap-vals!` reserves the key with a delay of `(acquire previous)`; a caller
  finding one in flight derefs it, so same-key callers share one handle and
  other keys run in parallel. A released roster claims nothing: the
  reservation refuses by name (`::roster-released`), and `release-context!`
  settles every claim made before its mark. Settling (the handle, or previous
  on failure) applies only while this reservation holds the key."
  {:malli/schema [:=> [:cat :seon.agent/routing
                       [:tuple :seon.agent/branch [:maybe :seon.agent/id]]
                       [:fn clojure.core/ifn?]]
                  :seon.agent/execution-handle]}
  [roster key acquire]
  (let [path [:seon.agent/contexts key]
        reserve (fn [held]
                  (let [entry (get-in held path)]
                    (when (:seon.agent/released? held)
                      (throw (ex-info "This execution's roster is released; acquire through a live one."
                                      {::refused ::roster-released :seon.agent/branch (first key)
                                       :seon.error/message "This execution's roster is released; acquire through a live one."
                                       :seon.error/expected :seon.agent/routing})))
                    (if (::acquiring entry)
                      held
                      (assoc-in held path (assoc entry ::acquiring (delay (acquire entry)))))))
        entry (get-in (second (swap-vals! roster reserve)) path)
        pending (::acquiring entry)
        previous (not-empty (dissoc entry ::acquiring))
        settle! (fn [value]
                  (swap! roster
                         (fn [held]
                           (cond (not (identical? pending (::acquiring (get-in held path)))) held
                                 value (assoc-in held path value)
                                 :else (update held :seon.agent/contexts dissoc key)))))]
    (try
      (let [acquired @pending]
        (settle! acquired)
        acquired)
      (catch Throwable failure
        (settle! previous)
        (throw failure)))))

(defn- adopt-cluster-declarations!
  "Install on the work branch `connection` the declarations its cluster adopted after it forked; nil.

  The JVM's code writes what the cluster's `projection` declares, so a branch
  forked before an adoption converges at each acquisition, before any write.
  A refused adoption throws its error. `seon.cluster` requires this namespace,
  so its owner is resolved by name."
  {:malli/schema [:=> [:cat :seon.db/connection [:or :nil :seon.schema/projection]] :nil]}
  [connection projection]
  (when projection
    (let [changes ((requiring-resolve 'seon.cluster/branch-declaration-changes) (db/db connection) projection)]
      (when (seq changes)
        (let [result (db/call-with-custody {:seon.db/connection connection}
                       #(schema/call-with-projection projection
                          (fn [] (db/transact! connection {:tx-data changes}))))]
          (when-not (:db-after result)
            (throw (ex-info "The branch could not adopt its cluster's declarations." result)))))))
  nil)

(defn acquire-context!
  "Acquire a coherent execution handle without starting any graph.

  The ordinary arity reads the agent's explicit branch. Live execution borrows
  the handle's own connection: its branch is the live branch, read from the
  connection value the handle carries, never re-derived from a cluster name.
  An isolation request allocates a fresh branch off the captured commit (or
  current head); a named branch without isolation selects an existing branch,
  as the MCP tool does. One roster per execution retains the handles and
  private SCI objects across evaluation boundaries: the handle's own for its
  live branch, and for any other branch the child roster the handle's records,
  so a branch execution's contexts, graphs and release are its own."
  {:malli/schema
   [:function
    [:=> [:cat :seon.agent/context-source :seon.agent/id]
     :seon.agent/execution-handle]
    [:=> [:cat :seon.agent/context-source [:maybe :seon.agent/id]
          :seon.agent/acquisition-options]
     :seon.agent/execution-handle]]}
  ([handle agent-id] (acquire-context! handle agent-id {}))
  ([handle agent-id options]
   (let [parent-roster (:seon.agent/routing handle)
         parent (:seon.db/connection handle)
         ;; A released execution claims nothing, refused by name before its
         ;; released connection is read (Datahike refuses that read as
         ;; "Connection has been released.", which named no roster).
         _ (when (some-> parent-roster deref :seon.agent/released?)
             ;; the released routing names itself as `start-flow!`'s own refusal does
             (throw (ex-info "This execution's roster is released; acquire through a live one."
                             {::refused ::roster-released :seon.agent/id agent-id
                              :seon.error/message "This execution's roster is released; acquire through a live one."
                              :seon.error/expected :seon.agent/routing})))
         database (db/db parent)
         ;; The handle carries its world: the branch its connection holds is live.
         live-branch (get-in database [:config :branch])
         source-ctx (or (:seon.sci.eval/base-ctx handle)
                        (:my.program/base-ctx handle)
                        (:seon.sci.eval/ctx handle))
         branch (or (:seon.agent/branch options)
                    (when (:seon.agent/isolate? options)
                      (keyword (str "agent-" (id/id))))
                    (when agent-id
                      (:seon.agent/branch
                       (db/pull database '[:seon.agent/branch]
                                [:seon.agent/id agent-id]))))
         ;; The handle's own store: a test request's overlay holds commits the
         ;; file store never sees. Options name no store, so the calling
         ;; evaluation's world cannot supply one over it.
         held-store (or (:seon.store/store handle) (:seon.store/store (env/of source-ctx)))]
     (when-not (and parent-roster branch)
       (throw (ex-info "Acquisition requires the handle's roster and an explicit agent branch."
                       {:seon.agent/id agent-id :seon.agent/branch branch})))
     (when (and (:seon.agent/isolate? options) (= branch live-branch))
       (throw (ex-info "Isolation cannot borrow the live cluster branch."
                       {:seon.agent/branch branch})))
     (let [isolated? (not= branch live-branch)
           acquire-in
           (fn [roster]
     (try
     (acquire-once! roster [branch agent-id] (fn [previous]
       (let [;; An entry still acquiring its first handle holds no connection yet.
             branch-owner (some (fn [[_ execution]] (when (:seon.db/connection execution) execution))
                                (:seon.agent/contexts @roster))
             create? (and isolated? (nil? previous)
                          (or (:seon.agent/isolate? options)
                              (nil? (:seon.agent/branch options))))
             _ (when (and isolated? (nil? previous) (nil? held-store))
                 (throw (ex-info "Isolated acquisition requires the held store."
                                 {:seon.agent/branch branch})))
             from (or (:seon.cluster.registry/from options) (db/commit-id database))
             created (when create?
                       (registry/fork!
                        {:seon.store/store held-store
                         :seon.store/branch branch
                         :seon.db/db database
                         :seon.cluster.registry/from from}))
             _ (when (and create? (not (:seon.cluster/created? created)))
                 (throw (ex-info "Isolation requires a fresh owned branch."
                                 {:seon.agent/branch branch})))
             allocated (atom nil)]
         (try
           (let [borrowed (or (:seon.db/connection branch-owner)
                            (when (and isolated? (not create?) (nil? previous))
                            (registry/active-branch-connection
                             {:seon.store/store held-store :seon.store/branch branch})))
                 connection (or (:seon.db/connection previous)
                                (when-not isolated? parent)
                                borrowed
                                (let [connection (store/open-branch! held-store branch)]
                                  (reset! allocated connection)
                                  connection))
                 ;; A branch opened at the captured commit holds that commit's
                 ;; immutable value, so its projection is the carried one.
                 _ (when @allocated
                     (db/carry-connection-projection-state!
                      connection
                      (sci.eval/projection-state
                       @connection
                       (if (= (db/commit-id database) (db/commit-id @connection))
                         (db/carried-projection database)
                         (schema/projection-from-database
                          @connection (db/carried-projection database)))
                       (:seon.cluster/name handle))))
                 ;; The fork owner retains the merge base; acquisition assigns
                 ;; the agent on the connection it just acquired.
                 _ (when (and create? agent-id)
                     (let [result (db/call-with-custody {:seon.db/connection connection}
                                    #(db/transact! connection
                                      [{:db/id [:seon.agent/id agent-id]
                                        :seon.agent/branch branch}]))]
                       (when-not (:db-after result)
                         (throw (ex-info "Branch assignment refused." result)))))
                 _ (adopt-cluster-declarations! connection (db/carried-projection database))
                 selected (db/db connection)
                 held-base (or (when-not isolated? source-ctx)
                               (:seon.sci.eval/base-ctx previous)
                               (:seon.sci.eval/base-ctx branch-owner))
                 ;; A branch no execution holds yet gets its program as one
                 ;; copy, acquired for `selected` (`sci.eval/branch-ctx`).
                 base (or held-base
                          (sci.eval/branch-ctx
                           source-ctx selected connection
                           (sci.eval/projection-state selected
                            (db/carried-projection selected) (:seon.cluster/name handle))
                           {:seon.env/environment (or (env/of handle) (env/of source-ctx))}))
                 ;; A row the program cannot install is recorded by the
                 ;; acquisition (an agent's row as its mistake, a core row as a
                 ;; core fault) and the rest of the program arms. Only a
                 ;; database that cannot store that record refuses here. The
                 ;; recorder binds the acquired branch's custody itself.
                 acquired (if held-base
                            (sci.eval/acquire! {:seon.sci.eval/ctx base :seon.db/db selected})
                            (:seon.sci.eval/acquisition base))
                 _ (when-let [failure (:seon.sci.eval/acquisition-recording-error acquired)]
                     (throw (ex-info "Program acquisition could not record its refusals."
                                     {:seon.sci.eval/acquisition-recording-error failure})))
                 _ (env/replace-environment!
                    (:seon.sci.eval/projection-state base)
                    (cond-> (assoc (env/of base) :seon.agent/routing roster)
                      held-store (assoc :seon.store/store held-store)))
                 forked (when agent-id
                          (sci.eval/fork-for-turn
                           (cond-> {:seon.sci.eval/ctx base
                                    :seon.db/db selected :seon.agent/id agent-id}
                             previous (assoc :seon.sci.eval/agent-ctx
                                             (:seon.sci.eval/ctx previous)))))
                 ;; handles the fresh context could not bind: a core fault
                 ;; delivered to this agent; the acquisition stands
                 _ (when-let [unbound (:seon.sci.eval/unbound-results forked)]
                     (fault/fault! (or (env/of handle) (env/of base))
                                   (ex-info (:seon.error/message unbound) unbound)
                                   {:seon.error/layer :seon.sci.eval/fork
                                    :seon.error/operation 'seon.sci.eval/fork-for-turn
                                    :seon.db.process/id (:seon.db.process/id handle)
                                    :seon.agent/id agent-id}))
                 ctx (if forked (:seon.sci.eval/ctx forked) base)
                 acquired (cond->
                           {:seon.cluster/name (:seon.cluster/name handle)
                            :seon.db/connection connection
                            :seon.source/commit-id (db/commit-id selected)
                            :seon.schema/projection (db/carried-projection (db/db connection))
                            :seon.sci.eval/ctx ctx
                            :seon.sci.eval/base-ctx base
                            :seon.env/environment (env/of ctx)
                            ;; The execution owns the roster its graphs start in, so
                            ;; its release can stop and join them before closing.
                            :seon.agent/routing roster
                            :seon.agent/branch branch
                            ;; Reacquiring an isolated handle through itself keeps its mode.
                            :seon.agent/mode (or (:seon.agent/mode previous) (if isolated? :isolated :live))
                            :seon.agent/owns-connection?
                            (boolean (or @allocated (:seon.agent/owns-connection? previous)))
                            :seon.agent/owns-branch?
                            (boolean (or create? (:seon.agent/owns-branch? previous)))}
                            agent-id (assoc :seon.agent/id agent-id)
                            held-store (assoc :seon.store/store held-store))]
             acquired)
           (catch Throwable failure
             (when-let [connection @allocated]
               (try (store/release-branch! connection)
                    (catch Throwable cleanup (.addSuppressed failure cleanup))))
             (when create?
               (try (registry/retire-branch!
                     {:seon.store/store held-store :seon.store/branch branch})
                    (catch Throwable cleanup (.addSuppressed failure cleanup))))
             (throw failure))))))
       ;; A failed first acquisition leaves no empty child recorded. Emptiness is
       ;; decided in the child's own swap, which marks it released, so a
       ;; concurrent acquisition either reserved first (the child stays) or is
       ;; refused and retries through `child-roster!`, which replaces it.
       (catch Throwable failure
         (when (and isolated?
                    (:seon.agent/released?
                     (swap! roster #(cond-> % (empty? (:seon.agent/contexts %))
                                      (assoc :seon.agent/released? true)))))
           (swap! parent-roster update :seon.agent/children
                  #(cond-> % (identical? roster (get % branch)) (dissoc branch))))
         (throw failure))))]
       (if-not isolated?
         (acquire-in parent-roster)
         ;; A child released between `child-roster!` and the reservation is
         ;; replaced once; a released parent refuses the retry by name too.
         (try (acquire-in (child-roster! parent-roster branch))
              (catch clojure.lang.ExceptionInfo refusal
                (if (= ::roster-released (::refused (ex-data refusal)))
                  (acquire-in (child-roster! parent-roster branch))
                  (throw refusal)))))))))

(defn start-flow!
  "Start one agent's graph: stamp → start → resume → route → prime.
  Idempotent per agent (an started agent is left alone — db<->flow's
  derive-all pass makes repeats ordinary). The graph's error channel
  joins the cluster's ONE fault channel tagged with the agent
  (`close? false`, so this agent's stop never closes the committer's
  inbox). The routing entry is registered BEFORE the prime, and the
  prime is last: its pass derives from facts, so a message committed
  before this graph existed is answered by construction. Orphans of a
  previously dead process are settled by that same first pass — step 1
  of the turn transform — so the wedge cannot return through the
  starting gap. Returns the started entry.

  No monitor: `swap-vals!` claims the agent in the routing value with a
  delay of `start-claimed-flow!`, so concurrent callers (db<->flow proc, direct
  source installers) deref one starting and acquire one graph. A released
  routing or a stop in flight refuses by name rather than waiting. Refuses an agent id with
  no committed entity — db<->flow derives its set from facts, so a
  missing entity is a caller bug, never a nil routing key."
  {:malli/schema [:=> [:cat :seon.agent/start-request]
                  :seon.agent/started]}
  [{agent-id :seon.agent/id routing :seon.agent/routing :as request}]
  (let [reserve (fn [current]
                  (if (or (:seon.agent/released? current)
                          (get-in current [::stopping agent-id])
                          (get-in current [::starting agent-id])
                          (get-in current [:seon.agent/started agent-id]))
                    current
                    (assoc-in current [::starting agent-id] (delay (start-claimed-flow! request)))))
        current (second (swap-vals! routing reserve))
        refuse (fn [message expected]
                 (throw (ex-info message {:seon.error/message message
                                          :seon.agent/id agent-id
                                          :seon.error/expected expected})))
        previous (get-in current [:seon.agent/started agent-id])
        ^ExecutorService executor (:seon.flow/executor previous)]
    (cond
      (:seon.agent/released? current)
      (refuse "start-flow! refused: the owning execution has released this routing." :seon.agent/routing)
      (get-in current [::stopping agent-id])
      (refuse "start-flow! refused: the agent's graph is stopping." :seon.agent/graph-exited)
      (get-in current [::starting agent-id]) @(get-in current [::starting agent-id])
      (not (.isShutdown executor)) previous
      ;; A previous graph whose stop missed its deadline keeps its entry: its
      ;; executor's termination is the exit fact, so no new graph overlaps it.
      (.isTerminated executor)
      (do (stop-flow! {:seon.agent/id agent-id :seon.agent/routing routing})
          (start-flow! request))
      :else (refuse "start-flow! refused: the agent's previous graph has not exited."
                    :seon.agent/graph-exited))))

(defn- start-claimed-flow!
  "Start, publish and prime one agent's graph under the starting claim `start-flow!` reserved.
  Publication clears the claim; a failure clears it and rethrows whole."
  {:malli/schema [:=> [:cat :seon.agent/start-request] :seon.agent/started]}
  [{handle :seon.turn.loop/cluster
    agent-id :seon.agent/id
    routing :seon.agent/routing
    intent :seon.task/execution}]
  (try
    (let [connection (:seon.db/connection handle)
          eid (db/q '[:find ?agent .
                     :in $ ?id
                     :where [?agent :seon.agent/id ?id]]
                   @connection agent-id)
          _ (when (nil? eid)
              (throw (ex-info "start-flow! refused: no such agent in facts."
                              {:seon.agent/id agent-id
                               :seon.agent/no-such-agent agent-id})))
          execution (acquire-context! handle agent-id)
          _ (when-not (identical? routing (:seon.agent/routing execution))
              (throw (ex-info "start-flow! refused: the routing is not the one the agent's execution owns."
                              {:seon.error/message "start-flow! refused: the routing is not the one the agent's execution owns."
                               :seon.agent/id agent-id
                               :seon.error/expected :seon.agent/routing})))
          connection (:seon.db/connection execution)
          wake-ch (wake-channel)
          ;; ONE WAITING SUBMISSION: its caller holds it and awaits, so a
          ;; full port refuses the next as busy rather than dropping one.
          requests (async/chan 1)
          schedule-channel (async/chan (async/sliding-buffer 1))
          completion (async/chan 1)
          turn-backstop-state (atom nil)
          settings (merge (config/effective @connection (:seon.cluster/name handle))
                          (ai/agent-overlay @connection agent-id))
          turn-completion-backstop-ms
          (:seon.config.agent/turn-completion-backstop-ms settings)
          _ (async/>!! completion :seon.agent/ready)
          agent-handle (assoc (merge handle execution)
                              :seon.sci.eval/ctx (:seon.sci.eval/base-ctx execution)
                              :seon.sci.eval/projection-state
                              (:seon.sci.eval/projection-state (:seon.sci.eval/ctx execution))
                              :seon.sci.eval/agent-ctx (:seon.sci.eval/ctx execution)
                              :seon.flow/plan-channel
                              (:seon.cluster.wake/channel handle)
                              :seon.cluster.wake/channel wake-ch
                              :seon.agent/requests requests
                              :seon.schedule/channel schedule-channel
                              :seon.turn.loop/completion completion
                              :seon.agent/turn-backstop-state turn-backstop-state
                              ;; The cluster's own handle, never this agent's branch
                              ;; one: a hosted db<->flow's handle carries its cluster's
                              ;; (`seon.cluster.host`), the cluster db<->flow's is it.
                              :seon.agent/context-source
                              (or (:seon.agent/context-source handle)
                                  (merge (select-keys execution [:seon.store/store]) handle))
                              ;; The execution owner's stop request, read from
                              ;; the roster when a fault fires: interrupt the
                              ;; transform and request the graph's stop off
                              ;; the calling thread. A request, never a join.
                              :seon.fault/stop-request!
                              (fn []
                                (when-let [started (get-in @routing [:seon.agent/started agent-id])]
                                  (interrupt-turn! started)
                                  (seon.flow/stop-off-thread! (:seon.flow/graph started)))
                                nil)
                              :seon.config.agent/turn-completion-backstop-ms
                              turn-completion-backstop-ms
                              :seon.agent/turn-thread (AtomicReference.))
          ;; Flow starts a graph paused: only a :running intent resumes it (H6)
          paused? (not (contains? #{nil :running} intent))
          {graph :seon.flow/graph started :seon.flow/started
           executor :seon.flow/executor}
          (seon.flow/start-graph!
           {:seon.flow/projection-state
            (:seon.sci.eval/projection-state agent-handle)
            :seon.flow/resume? (not paused?)
            :seon.flow/graph-definition
            (graph-definition
             {:seon.turn.loop/cluster agent-handle
              :seon.agent/id agent-id})})
          entry {:seon.agent/id agent-id
                 :seon.agent/eid eid
                 :seon.turn.loop/cluster agent-handle
                 :seon.flow/graph graph
                 :seon.flow/started started
                 :seon.flow/executor executor
                 :seon.cluster.wake/channel wake-ch
                 :seon.agent/requests requests
                 :seon.schedule/channel schedule-channel
                 :seon.turn.loop/completion completion
                 :seon.agent/turn-backstop-state turn-backstop-state
                 :seon.flow.plan/paused? paused?}]
      (swap! routing
             (fn [current]
               (-> current
                   (assoc-in [:seon.agent/started agent-id] entry)
                   (assoc-in [:seon.agent/channels eid] wake-ch)
                   (update ::starting dissoc agent-id))))
      ;; the start prime — every mailbox start primes exactly once
      (async/offer! wake-ch :seon.agent/wake)
      entry)
    (catch Throwable failure
      (swap! routing update ::starting dissoc agent-id)
      (throw failure))))

(defn- interrupt-turn!
  "Interrupt the stopping agent's in-flight turn transform, if one runs.

  INTERRUPTED EXECUTION NEVER RESUMES, so an orderly stop does not wait for
  a turn to finish its provider call, evaluation wait or transaction wait.
  Only a thread inside a transform is interrupted (`turn-step` publishes
  it), under the holder's monitor, so the interrupt can never land after the
  transform returned. A parked proc loop is never interrupted: it takes the
  queued stop by itself. Interruption is a request, not termination; the
  caller still awaits the proc's stop transition. True when a transform
  was interrupted."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [entry]
  (if-let [^AtomicReference holder
           (:seon.agent/turn-thread (:seon.turn.loop/cluster entry))]
    (locking holder
      (let [thread (.get holder)]
        (if (instance? Thread thread)
          ;; the holder now shows the stop served this transform
          (do (.set holder ::interrupted) (.interrupt ^Thread thread) true)
          false)))
    false))

(defn- open-turn!
  "The agent's open turn id, nil when none, throwing a refused read."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id]
                  [:maybe :seon.turn/id]]}
  [connection agent-id]
  (let [run-id (turn/open-for-agent @connection [:seon.agent/id agent-id])]
    (when (map? run-id)
      (throw (ex-info "Stop could not read the agent's open turn."
                      (assoc run-id :seon.agent/id agent-id))))
    run-id))

(defn- turn-completion-failure!
  "Record (`seon.fault/fault!`) and throw the failure for a graph that did not exit in bound."
  {:malli/schema [:=> [:cat :map [:maybe :seon.turn/id] [:int {:min 1}]] :nil]}
  [entry run-id timeout-ms]
  (let [agent-id (:seon.agent/id entry)
        diagnostic
        (turn/turn-completion-error
         agent-id run-id timeout-ms :seon.agent/stop :seon.agent/turn-completed
         [:seon.agent/graph-exited])
        failure (ex-info (:seon.error/message diagnostic) diagnostic)
        handle (:seon.turn.loop/cluster entry)]
    ;; one stored, delivered core fault (`fault!` throws, caused by `failure`,
    ;; only when it cannot be stored); the refusal still throws below
    (fault/fault! (env/of handle) failure
                  {:seon.error/layer :seon.agent/agent-graph
                   :seon.error/operation `stop-flow!
                   :seon.db.process/id (:seon.db.process/id handle)
                   :seon.agent/id agent-id})
    (throw failure)))

(defn- cancel-turn-backstop!
  "Cancel the active turn bound an interrupted transform left armed.

  An escaped transform deliberately leaves its bound armed so quiescence
  cannot hide it (`seon.turn/arm-turn-completion-backstop!`). After an
  orderly stop observed the proc's exit and records the interruption, that
  bound would fire later as a second, false fault."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [entry]
  (if-let [backstop (some-> (:seon.agent/turn-backstop-state entry) deref)]
    (async/offer! (:seon.agent/cancel backstop) :seon.agent/interrupted)
    false))

(defn- record-interruption!
  "Close the agent's open turn as interrupted once its proc has exited.

  The same writer function boot recovery uses (`seon.turn/recover-call`):
  inside the transaction it stamps unfinished evaluations and effects
  interrupted and closes the turn; a turn the proc closed itself is a no-op.
  Returns the interrupted turn id, or nil when no turn was open."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.turn/id]]}
  [entry]
  (let [agent-id (:seon.agent/id entry)
        connection (:seon.db/connection (:seon.turn.loop/cluster entry))
        run-id (open-turn! connection agent-id)]
    (when run-id
      ;; The graph's own branch, never the releasing caller's custody.
      (let [result (db/call-with-custody
                    {:seon.db/connection connection}
                    #(db/transact!
                      connection
                      (turn/recover-tx {:seon.turn/id run-id
                                        :seon.turn/now (Date.)})))]
        (when-not (:db-after result)
          (throw
           (ex-info "Stop could not record the interrupted turn."
                    {:seon.error/message
                     "Stop could not record the interrupted turn."
                     :seon.agent/id agent-id
                     :seon.turn/id run-id
                     :seon.error/data result})))
        run-id))))

(defn stop-flow!
  "Orderly stop of one agent's graph, idempotent.
  Request stop, INTERRUPT the in-flight turn transform (`:seon.agent/finish?` lets
  it finish instead: an ended attempt's own pass wrote its end), and join the graph's
  executor (every proc loop exited) before dropping its routing entry or
  closing channels. Interrupted execution never resumes: an in-flight provider call,
  evaluation wait or transaction wait unwinds instead of running to its end,
  and the open turn is then closed with its unfinished evaluations stamped
  interrupted by the writer function boot recovery uses. An idle completion
  permit is not a stop acknowledgement: Flow may already have selected the
  next wake. Only the exited proc threads prove no later transform can write
  after cleanup. The wait spends the caller's `:seon.await/deadline-nanos`,
  else the declared allowance `:seon.config.agent/turn-completion-backstop-ms`.
  Host work that ignores the interrupt keeps its thread until it returns; a
  graph that does not exit refuses teardown loudly, naming the agent and open
  turn, and the entry remains: `start-flow!` refuses until the executor terminates. Stop drops conn contents —
  safe by the transport law; triggers are rows and survive.

  THE ORDER OF THE LAST STEPS IS LOAD-BEARING, not stylistic: completion is
  observed before destructive cleanup, then the entry is dropped BEFORE its
  channel is closed, so `wake/route!` can never reach a channel this function
  closed. That is what makes a
  closed-but-reachable route mean exactly one thing — the terminal
  settlement fence — and lets `fenced-route?` make the exact route
  recognizable to `wake/delivery` without a flag. Closing first would
  put an ordinary teardown into the same state and the recognition
  would become a guess."
  {:malli/schema [:=> [:cat :seon.agent/stop-request] :nil]}
  [{agent-id :seon.agent/id routing :seon.agent/routing :as request}]
  (let [reserve (fn [current]
                  (if (or (get-in current [::starting agent-id])
                          (get-in current [::stopping agent-id])
                          (nil? (get-in current [:seon.agent/started agent-id])))
                    current
                    (assoc-in current [::stopping agent-id] (delay (stop-graph! request)))))
        current (second (swap-vals! routing reserve))]
    (if-let [starting (get-in current [::starting agent-id])]
      ;; An start in flight settles first so its graph is the one stopped; its
      ;; failure reaches its own caller through the same delay.
      (do (try @starting (catch Throwable _ nil))
          (stop-flow! request))
      (some-> (get-in current [::stopping agent-id]) deref)))
  nil)

(defn- stop-graph!
  "Stop, interrupt (unless the request says `:seon.agent/finish?`) and join one started graph under
  the stopping claim `stop-flow!` reserved, then drop its route before closing it. The claim is cleared on
  exit; a graph that missed its bound keeps its entry."
  {:malli/schema [:=> [:cat :seon.agent/stop-request] :nil]}
  [{agent-id :seon.agent/id
    routing :seon.agent/routing
    finish? :seon.agent/finish?
    deadline-nanos :seon.await/deadline-nanos}]
  (try
    (let [entry (started routing agent-id)]
      (let [{connection :seon.db/connection
             timeout-ms :seon.config.agent/turn-completion-backstop-ms}
            (:seon.turn.loop/cluster entry)]
        (flow/stop (:seon.flow/graph entry))
        ;; an ended attempt's stop lets its pass finish: that pass wrote the end
        (when-not finish? (interrupt-turn! entry))
        (when (seon.flow/join-graph!
               (into
                (select-keys entry [:seon.flow/executor])
                {:seon.await/bound
                {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
                 :seon.await/config-value timeout-ms
                 :seon.await/deadline-nanos
                 (or deadline-nanos (+ (System/nanoTime) (* 1000000 timeout-ms)))}
                :seon.await/diagnostic {:seon.error/layer :seon.agent/agent-graph
                                        :seon.error/operation `stop-flow!
                                        :seon.error/expected :seon.agent/graph-exited}}))
          (turn-completion-failure! entry (open-turn! connection agent-id) timeout-ms)))
      (cancel-turn-backstop! entry)
      (record-interruption! entry)
      (swap! routing
             (fn [current]
               (-> current
                   (update :seon.agent/started dissoc agent-id)
                   (update :seon.agent/channels dissoc
                           (:seon.agent/eid entry)))))
      (async/close! (:seon.cluster.wake/channel entry))
      ;; A submission the stopped graph never took is answered closed now:
      ;; its caller's await ends instead of spending its bound.
      (when-let [requests (:seon.agent/requests entry)]
        (async/close! requests)
        (loop [] (when-let [request (async/poll! requests)]
                   (async/close! (:seon.agent/reply request))
                   (recur))))
      (async/close! (:seon.turn.loop/completion entry)))
    (finally (swap! routing update ::stopping dissoc agent-id)))
  nil)

;;; ---------------------------------------------------------------------------
;;; db<->flow — hosted in the cluster's own graph (R7)
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; plan: facts and roster in, actions out (R1)
;;; ---------------------------------------------------------------------------

(defn- follow
  "The actions that make `open`'s graph follow its intent `intent` (the
  cluster's :paused wins), given whether the roster holds it and has paused it."
  {:malli/schema [:=> [:cat :seon.flow.plan/open-attempt :seon.task/execution :boolean :boolean]
                  :seon.flow.plan/actions]}
  [open intent started? paused?]
  (let [action (fn [op] [(assoc (select-keys open [:seon.task/assigned :seon.task/assignment :seon.agent/id
                                                   :seon.task/branch])
                                :seon.flow.plan/op op)])]
    (case intent
      (:running :paused) (cond (not started?) (mapv #(assoc % :seon.task/execution intent) (action :start-flow))
                               (and (= :paused intent) (not paused?)) (action :pause)
                               (and (= :running intent) paused?) (action :resume)
                               :else [])
      :stopped (if started? (action :stop) [])
      :cancelled (action :cancel))))

(defn plan
  "The actions db<->flow takes next, as a vector.
  From `facts` read on main and the `roster`'s live
  graphs: each open attempt's graph follows its task's intent (`:held` and a
  paused cluster read as `:paused`); each startable leaf with no open attempt,
  oldest first, gets its next attempt and its flow while the open attempts that
  count (running, paused or held) are fewer than `max-running`; an invalid
  leaf gets a problem. Pure; a second pass over the same facts plans the same
  attempts, so their writes are no-ops by identity."
  {:malli/schema [:=> [:cat :seon.flow.plan/facts :seon.flow.plan/roster :seon.config.agent/max-running]
                  :seon.flow.plan/actions]}
  [{:seon.flow.plan/keys [startable invalid open ended created basis cluster]}
   {:seon.flow.plan/keys [started paused]} max-running]
  (let [running? (= :running cluster)
        intent (fn [{intent :seon.task/execution}]
                 (if (or (not running?) (#{:held :paused} intent)) :paused intent))
        followed (into [] (mapcat #(follow % (intent %) (contains? started (:seon.agent/id %))
                                           (contains? paused (:seon.agent/id %))))
                       (sort-by :seon.task/assignment open))
        attempted (into #{} (map :seon.task/assigned) open)
        counted (count (filter (comp #{:running :paused :held} :seon.task/execution) open))
        fresh (when running?
                (->> startable (remove attempted) (sort-by (juxt #(get created % 0) identity))
                     (take (max 0 (- max-running counted)))))
        launches (into [] (mapcat (fn [handle]
                                    (let [number (inc (get ended handle 0))
                                          key (id/id [handle number])
                                          row {:seon.task/assigned handle :seon.task/assignment key
                                               :seon.agent/id (str "attempt/" key)
                                               :seon.task/branch (keyword (str "attempt-" key))}]
                                      [(assoc row :seon.flow.plan/op :attempt :seon.task.attempt/number number
                                              :seon.flow.plan/fork-point (or (not-empty (get basis handle)) :main))
                                       (assoc row :seon.flow.plan/op :start-flow :seon.task/execution :running)])))
                       fresh)
        problems (mapv (fn [handle] {:seon.flow.plan/op :problem :seon.flow.plan/class :invalid-task
                                     :seon.task/assigned handle})
                       (sort (remove attempted invalid)))]
    (into problems (concat followed launches))))

(defn- read-failure
  "The read refusal among `reads`, or nil."
  {:malli/schema [:=> [:cat [:sequential :seon.schema/value]] [:maybe :seon.db/invalid-read-error]]}
  [reads]
  (some #(when (and (map? %) (:seon.error/at %)) %) reads))

(defn plan-facts
  "What db<->flow reads on main `database` before it plans.
  The frontier's leaves split by validity (a leaf is valid with at least one
  proof), the open attempts with their task's intent, ended counts, creation
  order, and the end commits of each leaf's done needs."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :seon.flow.plan/facts :seon.db/invalid-read-error]]}
  [database]
  (let [frontier (task/frontier database)
        leaves (when (set? frontier) (vec frontier))
        syms (when leaves (db/q '[:find ?h ?s :in $ [?h ...] :where [?t :seon.task/id ?h] [?t :seon.task/sym ?s]]
                                database leaves))
        open (db/q '[:find ?h ?k ?id ?b ?i
                     :where [?a :seon.task/attempt-of ?t] (not [?a :seon.task.attempt/end _])
                     [?a :seon.task/assigned ?h] [?a :seon.task/assignment ?k] [?a :seon.task/branch ?b]
                     [?a :seon.task/agent ?g] [?g :seon.agent/id ?id] [?t :seon.task/execution ?i]]
                   database)
        ended (db/q '[:find ?h (count ?a) :where [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end _]
                      [?t :seon.task/id ?h]]
                    database)
        created (when leaves (db/q '[:find ?h ?tx :in $ [?h ...] :where [_ :seon.task/id ?h ?tx]] database leaves))
        basis (when leaves (db/q '[:find ?h ?c :in $ % [?h ...]
                                   :where [?t :seon.task/id ?h] (needs* ?t ?n) (self-or-under ?n ?l)
                                   [?a :seon.task/attempt-of ?l] [?a :seon.task.attempt/end :done]
                                   [?a :seon.task.attempt/commit ?c]]
                                 database task/frontier-rules leaves))
        proved (when (and syms (not (map? syms)))
                 (into #{} (keep (fn [[handle sym]]
                                   (let [edges (task/task-edges database sym)]
                                     (when (seq (:seon.task/proofs edges)) handle))))
                       syms))]
    (or (read-failure [frontier syms open ended created basis])
        {:seon.flow.plan/startable (into #{} (filter proved) leaves)
         :seon.flow.plan/invalid (into #{} (remove proved) leaves)
         :seon.flow.plan/open (mapv (fn [[h k id b i]] {:seon.task/assigned h :seon.task/assignment k :seon.agent/id id
                                                        :seon.task/branch b :seon.task/execution i})
                                    open)
         :seon.flow.plan/ended (into {} ended)
         :seon.flow.plan/created (into {} created)
         :seon.flow.plan/basis (reduce (fn [m [h c]] (update m h (fnil conj #{}) c)) {} basis)
         :seon.flow.plan/cluster :running})))

(defn- started-entries
  "Every started entry `routing` holds, its own and its branch children's."
  {:malli/schema [:=> [:cat :seon.agent/routing] [:sequential :seon.agent/started]]}
  [routing]
  (concat (vals (:seon.agent/started @routing))
          (mapcat #(vals (:seon.agent/started @%)) (vals (:seon.agent/children @routing)))))

(defn roster-facts
  "What db<->flow reads of `routing` before it plans.
  The started graphs by agent, its own and its branch children's, and the
  paused among them."
  {:malli/schema [:=> [:cat :seon.agent/routing] :seon.flow.plan/roster]}
  [routing]
  (let [entries (started-entries routing)]
    {:seon.flow.plan/started (into #{} (map :seon.agent/id) entries)
     :seon.flow.plan/paused (into #{} (keep #(when (:seon.flow.plan/paused? %) (:seon.agent/id %))) entries)}))

(defn branch-handle
  "The cluster handle `cluster-handle` seen from the branch `execution`.
  Its connection, contexts and roster are the branch's, while wakes for
  db<->flow still go to the cluster's plan channel."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/execution-handle] :seon.agent/context-source]}
  [cluster-handle execution]
  (merge cluster-handle
         (select-keys execution [:seon.db/connection :seon.sci.eval/ctx :seon.sci.eval/base-ctx
                                 :seon.env/environment :seon.agent/routing :seon.agent/branch])
         {:seon.agent/context-source (or (:seon.agent/context-source cluster-handle) cluster-handle)
          :seon.sci.eval/projection-state (:seon.sci.eval/projection-state (:seon.sci.eval/ctx execution))
          :seon.turn.loop/stream-channel (async/chan (async/sliding-buffer 1))
          :seon.turn.loop/completion (async/promise-chan)}))

(defn branch-route-key
  "The key of the wake route db<->flow registers on `branch`'s connection."
  {:malli/schema [:=> [:cat :seon.store/branch] :seon.cluster.wake/key]}
  [branch]
  (keyword "seon.agent.route" (str (when-let [space (namespace branch)] (str space ".")) (name branch))))

(defn start-branch-flow!
  "Start `agent-id`'s graph on `execution`'s branch under `intent`.
  Answers its started entry.
  The branch's wake route is registered first: its mailbox wakes route to the
  agent, and an unrouted recipient wakes the cluster's db<->flow."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/execution-handle :seon.agent/id
                       :seon.task/execution]
                  :seon.agent/started]}
  [cluster-handle execution agent-id intent]
  (let [roster (:seon.agent/routing execution)
        route (wake/route! {:seon.cluster.wake/connection (:seon.db/connection execution)
                            :seon.cluster.wake/channels (fn [] (channels roster))
                            :seon.cluster.wake/fenced? (fn [eid channel] (fenced-route? roster eid channel))
                            :seon.flow/plan-channel (:seon.cluster.wake/channel cluster-handle)
                            :seon.cluster.wake/key (branch-route-key (:seon.agent/branch execution))})]
    (when (map? route)
      (throw (ex-info (:seon.error/message route "The branch's wake declarations cannot route.") route)))
    (start-flow! {:seon.turn.loop/cluster (branch-handle cluster-handle execution)
                  :seon.agent/id agent-id :seon.agent/routing roster :seon.task/execution intent})))

(defn- write!
  "Transact `tx` on `connection` under its own custody; throws the writer's refusal."
  {:malli/schema [:=> [:cat :seon.db/connection [:or :seon.db/tx-data :seon.store/transaction]] :seon.db/transaction-report]}
  [connection tx]
  (let [report (db/call-with-custody {:seon.db/connection connection} #(db/transact! connection tx))]
    (when-not (:db-after report)
      (throw (ex-info (:seon.error/message report "db<->flow's write was refused.") report)))
    report))

(defn- attempt-agent
  "The creation request of the agent an attempt action names, in the cluster `cluster-name`."
  {:malli/schema [:=> [:cat :seon.flow.plan/action :seon.cluster/name] :seon.agent/creation-request]}
  [{agent-id :seon.agent/id key :seon.task/assignment branch :seon.task/branch} cluster-name]
  {:seon.agent/id agent-id :seon.agent/branch branch :seon.cluster/name cluster-name
   :seon.ns/name (symbol (str "my.attempt-" key))})

(defn- write-attempt!
  "Write an `:attempt` action on main before anything runs.
  The agent's creation and the attempt row are ONE transaction; nothing is
  written when the attempt already is."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.flow.plan/action] :nil]}
  [handle {h :seon.task/assigned agent-id :seon.agent/id branch :seon.task/branch :as action}]
  (let [connection (:seon.db/connection handle)
        database (db/db connection)
        tx (task/attempt-tx database h agent-id branch)
        known? (db/q '[:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]] database agent-id)]
    (when (map? tx) (throw (ex-info (:seon.error/message tx "The attempt could not be derived.") tx)))
    (when (seq tx)
      (write! connection (into (if known? [] (creation-tx (attempt-agent action (:seon.cluster/name handle)))) tx)))
    nil))

(defn handle-store
  "The store `handle` holds: its own, else its SCI context's environment's, as acquisition reads it."
  {:malli/schema [:=> [:cat :seon.agent/context-source] [:maybe :seon.store/store]]}
  [handle]
  (or (:seon.store/store handle)
      (:seon.store/store (env/of (or (:seon.sci.eval/base-ctx handle) (:seon.sci.eval/ctx handle))))))

(defn- fork-attempt!
  "Fork the attempt's `branch` from its `fork-point` unless the store holds it.
  The fork point is main's head, or the end commit of the task's need (a nested
  fork toward main from the need's branch); a need whose branch is gone forks
  main, which its merge reached."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.store/branch :seon.flow.plan/fork-point] :nil]}
  [handle branch fork-point]
  (let [database (db/db (:seon.db/connection handle))
        store (handle-store handle)
        main-branch (get-in database [:config :branch])
        commit (when (set? fork-point) (first (sort fork-point)))
        parent (when commit (db/q '[:find ?b . :in $ ?c :where [?a :seon.task.attempt/commit ?c] [?a :seon.task/branch ?b]]
                                  database commit))
        source (when (and (keyword? parent) (contains? (registry/roster store) parent))
                 (db/branch-database database parent))
        result (when-not (contains? (registry/roster store) branch)
                 (registry/fork! (if source
                                   {:seon.store/store store :seon.store/branch branch :seon.db/db source
                                    :seon.cluster.registry/from commit :seon.branch/target main-branch}
                                   {:seon.store/store store :seon.store/branch branch :seon.db/db database
                                    :seon.cluster.registry/from main-branch})))]
    (when (:seon.error/at result) (throw (ex-info (:seon.error/message result "The attempt's fork was refused.") result)))
    nil))

(defn- seed-attempt!
  "Write attempt `key`'s agent and row onto the branch `connection` holds when its fork point predates them
  (a need's end commit): the agent under main's entity id (its identity first, then its creation upserts onto
  it) and the row with its task (`seon.task/attempt-rows-tx`), so the agent reads its assignment in its own
  world and the end copy names main's entities."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.db/connection :seon.flow.plan/action] :nil]}
  [handle connection {key :seon.task/assignment agent-id :seon.agent/id :as action}]
  (let [main (db/db (:seon.db/connection handle))]
    (when-not (db/q '[:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]] (db/db connection) agent-id)
      (let [eid (:db/id (db/pull main [:db/id] [:seon.agent/id agent-id]))]
        (write! connection (into (if (int? eid) [[:db/add eid :seon.agent/id agent-id]] [])
                                 (creation-tx (attempt-agent action (:seon.cluster/name handle)))))))
    (when key
      (let [rows (task/attempt-rows-tx main (db/db connection) key)]
        (when (map? rows) (throw (ex-info (:seon.error/message rows "The attempt's rows could not be derived.") rows)))
        (when (seq rows) (write! connection rows))))
    nil))

(defn- start-attempt!
  "Start a `:start-flow` action's graph under its intent.
  Fork its branch, acquire the branch execution, write its agent and attempt
  there when the fork point predates them (`seed-attempt!`), and start its flow.
  A repeat finds the branch, its rows and the started graph and does nothing new."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.flow.plan/facts :seon.flow.plan/action] :nil]}
  [handle facts {h :seon.task/assigned agent-id :seon.agent/id branch :seon.task/branch
                 intent :seon.task/execution :as action}]
  (fork-attempt! handle branch (or (not-empty (get-in facts [:seon.flow.plan/basis h])) :main))
  (let [execution (acquire-context! handle nil {:seon.agent/branch branch})]
    (seed-attempt! handle (:seon.db/connection execution) action)
    (start-branch-flow! handle execution agent-id (or intent :running))
    nil))

(defn- entry-roster
  "The roster, `routing` or one of its branch children, holding `agent-id`'s started entry."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.agent/id] [:maybe :seon.agent/routing]]}
  [routing agent-id]
  (some #(when (get-in @% [:seon.agent/started agent-id]) %)
        (cons routing (vals (:seon.agent/children @routing)))))

(defn- follow!
  "Make `agent-id`'s started graph follow the action's op.
  Pause or resume it in place (recorded on its entry), stop and join it, or
  stop it and end its open attempt `:cancelled`."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing :seon.flow.plan/action] :nil]}
  [handle routing {op :seon.flow.plan/op agent-id :seon.agent/id key :seon.task/assignment}]
  (when-let [roster (entry-roster routing agent-id)]
    (case op
      :pause (do (flow/pause (:seon.flow/graph (started roster agent-id)))
                 (swap! roster assoc-in [:seon.agent/started agent-id :seon.flow.plan/paused?] true))
      :resume (do (flow/resume (:seon.flow/graph (started roster agent-id)))
                  (swap! roster assoc-in [:seon.agent/started agent-id :seon.flow.plan/paused?] false))
      (:stop :cancel) (stop-flow! {:seon.agent/id agent-id :seon.agent/routing roster})))
  (when (= :cancel op)
    (write! (:seon.db/connection handle)
            [[:db/add [:seon.task/assignment key] :seon.task.attempt/end :cancelled]
             [:db/add [:seon.task/assignment key] :seon.task.attempt/reason "The task's intent is :cancelled."]]))
  nil)

(defn- write-problem!
  "Write the problem task a `:problem` action names, by identity, so a repeat writes nothing."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.flow.plan/action] :nil]}
  [handle {h :seon.task/assigned class :seon.flow.plan/class}]
  (let [created (task/create! {:seon.db/connection (:seon.db/connection handle) :seon.agent/id "root"
                               :seon.task/ns 'seon.task :seon.task/name (str (name class) "-" h) :seon.task/kind :work
                               :seon.task/intent (str "Task " h " is :running but cannot start: it names no proof. "
                                                      "Tag a failing deftest with it, or hold it.")})]
    (when (:seon.error/at created) (throw (ex-info (:seon.error/message created "The problem task was refused.") created)))
    nil))

(defn apply-plan!
  "Apply db<->flow's `actions` over `facts` in order.
  Through `handle` and its `routing`.
  Each action's failure is recorded as db<->flow's fault and the rest still apply."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing :seon.flow.plan/facts
                       :seon.flow.plan/actions]
                  :nil]}
  [handle routing facts actions]
  (doseq [{op :seon.flow.plan/op :as action} actions]
    (try (case op
           :attempt (write-attempt! handle action)
           :start-flow (start-attempt! handle facts action)
           :problem (write-problem! handle action)
           (:pause :resume :stop :cancel) (follow! handle routing action))
         (catch Exception failure
           (fault/fault! (env/of handle) failure
                         {:seon.error/layer :seon.flow/plan :seon.error/operation `apply-plan!
                          :seon.db.process/id (:seon.db.process/id handle)
                          :seon.agent/id (:seon.agent/id action "root")}))))
  nil)

(defn- branch-end
  "The end `branch`'s head records on attempt `key`, read from main `database`'s store; nil when none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch :seon.task/assignment]
                  [:maybe :seon.task.attempt/end]]}
  [database branch key]
  (when-let [head (db/branch-database database branch)]
    (try (let [end (db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                         head key)]
           (when (keyword? end) end))
         (finally (source/release! [head])))))

(defn unlanded-ends
  "The ended attempts db<->flow has not landed on main `database`.
  Each attempt no end copy names (`:seon.squash/attempt`) whose branch the
  `store` still holds, with its end read on main, else on its branch's head:
  one branch read per such attempt, which is open or just ended."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/store] :seon.flow.plan/ends]}
  [database store]
  (let [rows (db/q '[:find ?a ?k ?h ?b ?id
                     :where [?a :seon.task/assignment ?k] [?a :seon.task/assigned ?h] [?a :seon.task/branch ?b]
                     [?a :seon.task/agent ?g] [?g :seon.agent/id ?id] (not [_ :seon.squash/attempt ?a])]
                   database)
        _ (when (map? rows) (throw (ex-info (:seon.error/message rows "db<->flow could not read the attempts.") rows)))
        roster (registry/roster store)]
    (into [] (keep (fn [[a k h b id]]
                     (when (contains? roster b)
                       (when-let [end (or (:seon.task.attempt/end (db/pull database [:seon.task.attempt/end] a))
                                          (branch-end database b k))]
                         {:db/id a :seon.task/assignment k :seon.task/assigned h :seon.task/branch b
                          :seon.agent/id id :seon.task.attempt/end end}))))
          (sort-by second rows))))

(defn- ended-problem!
  "Write root's problem task for the error end `ended`, named by its attempt, so a repeat writes nothing."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.flow.plan/ended-attempt [:maybe :seon.task.attempt/reason]] :nil]}
  [connection {key :seon.task/assignment h :seon.task/assigned branch :seon.task/branch end :seon.task.attempt/end} reason]
  (let [created (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns 'seon.task
                               :seon.task/name (str "attempt-ended-" key) :seon.task/kind :work
                               :seon.task/intent (str "Attempt " key " of task " h " ended " (name end)
                                                      (when reason (str ": " reason)) ". Nothing was copied to main;"
                                                      " its branch " (name branch) " is kept. Copy it by hand with"
                                                      " seon.cluster.squash/squash-merge!, or destroy it with"
                                                      " my.agent/retire-branch!.")})]
    (when (:seon.error/at created)
      (throw (ex-info (:seon.error/message created "The ended attempt's problem task was refused.") created)))
    nil))

(defn- record-error-end!
  "Land the error end `ended` on main through `connection` and copy nothing else.
  Root's problem task first, then ONE transaction with the attempt's end facts
  main lacks and the end copy's provenance as transaction metadata."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.flow.plan/ended-attempt] :nil]}
  [connection {a :db/id key :seon.task/assignment branch :seon.task/branch :as ended}]
  (let [database (db/db connection)
        head (db/branch-database database branch)]
    (try
      (let [selector [:seon.task.attempt/end :seon.task.attempt/commit :seon.task.attempt/reason]
            ours (dissoc (db/pull database selector a) :db/id)
            theirs (dissoc (db/q '[:find (pull ?a [:seon.task.attempt/end :seon.task.attempt/commit
                                                   :seon.task.attempt/reason]) .
                                   :in $ ?k :where [?a :seon.task/assignment ?k]]
                                 head key)
                           :db/id)
            facts (if (:seon.task.attempt/end ours) {} theirs)]
        (ended-problem! connection ended (:seon.task.attempt/reason (merge theirs ours)))
        (write! connection {:tx-data (mapv (fn [[attribute value]] [:db/add a attribute value]) (sort facts))
                            :tx-meta {:seon.squash/attempt a :seon.squash/source-branch branch
                                      :seon.squash/source-commit (db/commit-id head)}}))
      (finally (source/release! [head])))
    nil))

(defn- open-merge-request?
  "Whether an undecided merge request answers attempt `a` on main `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id] :boolean]}
  [database a]
  (some? (db/q '[:find ?m . :in $ ?a
                 :where [?r :seon.task/of-assignment ?a] [?r :seon.task/merge ?m] [?t :seon.task/id ?m]
                 (not [?t :seon.task/outcome _])]
               database a)))

(defn- release-branch!
  "Release the connections `routing` holds on `branch` after its graphs stopped; the branch stays."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.store/branch] :nil]}
  [routing branch]
  (when-let [child (get-in @routing [:seon.agent/children branch])]
    (doseq [execution (vals (:seon.agent/contexts @child))
            :when (:seon.agent/owns-connection? execution)]
      (release-context! execution)))
  nil)

(defn- detached!
  "Run `work` as io work on `handle`'s work launcher, its failure recorded as db<->flow's fault;
  inline when no launcher accepts it."
  {:malli/schema [:=> [:cat :seon.agent/context-source :string [:=> [:cat] :nil]] :nil]}
  [handle submission-id work]
  (let [environment (env/of handle)
        launcher (:seon.flow/work-launcher environment)
        submitted (when launcher
                    (seon.flow/submit!
                     launcher
                     {:seon.env/environment environment :seon.flow/workload :io :seon.flow/submission-id submission-id
                      :seon.flow/work-fn (fn [_] (work))
                      :seon.flow/complete!
                      (fn [{failure :seon.flow/throwable}]
                        (when failure
                          (fault/fault! environment failure
                                        {:seon.error/layer :seon.flow/plan :seon.error/operation `detached!
                                         :seon.db.process/id (:seon.db.process/id handle)}))
                        nil)}))]
    (when-not submitted (work))
    nil))

(defn land-ends!
  "Land each of `ends` on main, then stop its graph and settle its branch (P2).
  A :done or :sent-back end is copied by `seon.cluster.squash/copy!` (ONE main
  transaction); an error end copies nothing and becomes root's problem task.
  The graph stops without interrupting the pass that wrote the end, and is joined.
  A merge request still open keeps the branch until it closes (`seon.task/close!`),
  landing it with no root when `:seon.config.root/land?` says so; else a successful
  end deletes its branch unless `:seon.config.agent/keep-ended-branches?` keeps it;
  an error end keeps it. Each end's failure is db<->flow's fault and the rest still land."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing :seon.flow.plan/ends] :nil]}
  [handle routing ends]
  (let [connection (:seon.db/connection handle)]
    (doseq [{a :db/id agent-id :seon.agent/id branch :seon.task/branch end :seon.task.attempt/end :as ended} ends]
      (try
        (let [copied? (#{:done :sent-back} end)]
          (if copied?
            (db/call-with-custody {:seon.db/connection connection}
                                  #(squash/copy! connection branch nil {:seon.squash/attempt a}))
            (record-error-end! connection ended))
          (when-let [roster (entry-roster routing agent-id)]
            (stop-flow! {:seon.agent/id agent-id :seon.agent/routing roster :seon.agent/finish? true}))
          (let [database (db/db connection)
                keep? (true? (config/agent-cluster-dial database agent-id :seon.config.agent/keep-ended-branches?))]
            (cond
              (and copied? (open-merge-request? database a))
              (do (release-branch! routing branch)
                  (when (= :done end)
                    (detached! handle (str "land-" (name branch)) #(do (turn/land-ended! handle agent-id) nil))))
              (and copied? (not keep?))
              (when-let [refused (task/delete-branch! {:seon.db/connection connection :seon.store/store (handle-store handle)
                                                       :seon.agent/routing routing :seon.env/environment (env/of handle)}
                                                      branch)]
                (throw (ex-info (:seon.error/message refused "The ended attempt's branch was not deleted.") refused)))
              :else (release-branch! routing branch))))
        (catch Exception failure
          (fault/fault! (env/of handle) failure
                        {:seon.error/layer :seon.flow/plan :seon.error/operation `land-ends!
                         :seon.db.process/id (:seon.db.process/id handle) :seon.agent/id agent-id})))))
  nil)

(defn- agents-to-start
  "Unstarted agents with work, or schedules whose timers need a running proc."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.agent/id]]
                  [:set :seon.agent/id]]}
  [database candidates]
  (let [rows (db/q '[:find [?id ...] :in $ [?id ...]
                    :where [?agent :seon.agent/id ?id]
                    [_ :seon.schedule.task/owner ?agent]]
                  database (vec candidates))
        _ (when (seon.error.refusal/error? rows)
            (throw (ex-info "Agent starting could not read schedule owners." rows)))
        scheduled (set rows)]
    (into scheduled
          (filter #(turn/more-agent-work? database {:seon.agent/id %}))
          (remove scheduled candidates))))

(defn db<->flow
  "db<->flow transform, in Flow's four arities.
  Derive unstarted agents with work or schedules under a payload-free wake,
  then start those agents, sorted for determinism. A COMMITTED AGENT CREATION IS
  AN START WAKE, and that is a declaration rather than a claim:
  `:seon.agent/id` carries `:seon.wake/starts true`, which
  `wake/starting-attributes` derives and `wake/route!` offers here on
  every assertion — so an agent created with work while the cluster runs is
  started by this same pass. An idle agent waits for work or a schedule.
  The listener also offers here when it sees a `to`-ref with no routing
  entry (the created-and-messaged-in-one-commit belt). AND THIS PROC
  PRIMES ITSELF AT `::flow/resume`, so an agent committed before it was
  reading has its pending work observed by the first pass rather than by
  a wake that was never sent — see the transition arity.
  Coalescing on its sliding-1 in-port is safe by the standard argument.
  L8 holds by construction: starting writes nothing, and the prime is an
  `offer!`. A quiescence request acknowledges that every earlier start wake
  has settled before cluster teardown stops agent graphs."
  {:malli/schema [:function [:=> [:cat] [:map]] [:=> [:cat :map] :map] [:=> [:cat :map :keyword] :map] [:=> [:cat :map :keyword :seon.schema/value] [:tuple :map [:maybe [:map-of :keyword [:vector [:some {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "core.async.flow supplies per-port messages of different declared shapes and accepts heterogeneous non-nil output messages; the port determines each message contract.", :gen/elements [false 0 "" :k [] {}]}]]]]]]]}
  ([]
   {:ins {}
    :outs {}
    :workload :io
    :ping-map-fn (fn [state]
                   (assoc {}
                          :seon.agent/started-count
                          (count (:seon.agent/started @(:seon.agent/routing
                                            state)))))})
  ([args]
   ;; THE ROUTE LISTENER'S LIFETIME IS THIS PROC'S. Flow calls this arity on
   ;; the thread that starts the graph (`flow/impl.clj:263`), so a supplied
   ;; route is registered before `start-graph!` returns and before boot's
   ;; prime; the stop transition and the quiescence request remove it.
   ;; A declaration population that cannot route answers its refusal and
   ;; registers nothing (wake/route!): this proc records it as its fault.
   (let [registered (some-> (:seon.cluster.wake/route args) wake/route!)]
     (when (map? registered)
       (fault/fault! (env/of args) registered
                     {:seon.error/layer :seon.cluster.wake/registration
                      :seon.error/operation `db<->flow
                      :seon.db.process/id (:seon.db.process/id (:seon.turn.loop/cluster args))
                      :seon.error/declared-schema :seon.cluster.wake/unroutable-declarations-error})))
   (assoc args
          ::flow/in-ports {:seon.flow/plan (:seon.cluster.wake/channel
                                  (:seon.turn.loop/cluster args))}))
  ([state transition]
   ;; THE DB<->FLOW PRIMES ITSELF, because a missed wake must never decide
   ;; whether an agent has a graph. Agents committed BEFORE this proc was
   ;; reading — boot recovery, a fixture that seeds before it starts a
   ;; cluster, a task assignment racing the listener's registration — left
   ;; no wake to receive, and the derive-all pass reads facts, so the only
   ;; safe prime is an unconditional one at the transition that makes this
   ;; proc live. `::flow/resume` is that transition: a proc starts paused
   ;; and `flow/resume` moves it to running
   ;; (`reference-code/core.async/.../flow/impl.clj:209-217`), and a
   ;; pause/resume cycle re-primes for exactly the same reason. The prime is
   ;; an `offer!` into this proc's own sliding-1 in-port, so it neither
   ;; parks nor accumulates, and a repeated pass is ordinary.
   ;;
   ;; Boot ALSO calls this transform directly and synchronously
   ;; (`src/seon/cluster.clj:2965`). That is not a second starting path: it is
   ;; how boot publishes readiness: every currently required graph is started,
   ;; which an asynchronous prime cannot promise. This one covers
   ;; every other way a graph starts.
   (when (= ::flow/resume transition)
     (async/offer! (:seon.cluster.wake/channel
                    (:seon.turn.loop/cluster state))
                   :seon.flow/plan-prime))
   (when (= ::flow/stop transition)
     (some-> (:seon.cluster.wake/route state) wake/unlisten!))
   state)
  ([state _input message]
   (cond
     (nil? message)
     [state nil]

     (:seon.agent/quiesce message)
     (do
       ;; Nothing new is routed while the agent graphs unwind.
       (some-> (:seon.cluster.wake/route state) wake/unlisten!)
       (async/put! (:seon.agent/quiesce message) :seon.agent/quiesced)
       [state nil])

     :else
     (let [handle (:seon.turn.loop/cluster state)
           routing (:seon.agent/routing state)
           connection (:seon.db/connection handle)
           db @connection
           ;; db<->flow starts the agents whose custody is its own execution's
           ;; branch: a branch inherits rows that name its parent's, and those
           ;; agents belong to the parent's db<->flow. A row naming no branch still
           ;; reaches `start-flow!`, whose acquisition refuses it by name.
           own-branch (get-in db [:config :branch])
           agents (into [] (keep (fn [[id branch]] (when (#{own-branch ::none} branch) id)))
                        (db/q '[:find ?id ?branch
                                :where [?agent :seon.agent/id ?id]
                                [(get-else $ ?agent :seon.agent/branch ::none) ?branch]]
                              db))
           unstarted (remove #(contains? (:seon.agent/started @routing) %) agents)]
       ;; each start fails alone: its fault is recorded and the pass goes on
       (doseq [agent-id (sort (agents-to-start db (set unstarted)))]
         (try (start-flow! {:seon.turn.loop/cluster handle
                            :seon.agent/id agent-id
                            :seon.agent/routing routing})
              (catch Exception failure
                (fault/fault! (env/of handle) failure
                              {:seon.error/layer :seon.flow/plan :seon.error/operation `start-flow!
                               :seon.db.process/id (:seon.db.process/id handle) :seon.agent/id agent-id}))))
       ;; the ended attempts land on main first, so the plan reads them ended (P2)
       (try (land-ends! handle routing (unlanded-ends @connection (handle-store handle)))
            (catch Exception failure
              (fault/fault! (env/of handle) failure
                            {:seon.error/layer :seon.flow/plan :seon.error/operation `unlanded-ends
                             :seon.db.process/id (:seon.db.process/id handle)})))
       ;; the attempts: write first, then make Flow follow (R1)
       (let [db @connection
             facts (plan-facts db)
             max-running (:seon.config.agent/max-running (config/effective db (:seon.cluster/name handle)))]
         (if (or (:seon.error/at facts) (not (int? max-running)))
           (fault/fault! (env/of handle)
                         (ex-info "db<->flow could not read what to plan."
                                  (if (:seon.error/at facts) facts {:seon.config.agent/max-running max-running}))
                         {:seon.error/layer :seon.flow/plan :seon.error/operation `db<->flow
                          :seon.db.process/id (:seon.db.process/id handle)})
           (apply-plan! handle routing facts (plan facts (roster-facts routing) max-running))))
       [state nil]))))
