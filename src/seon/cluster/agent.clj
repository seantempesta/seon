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
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cache :as cache]
            [seon.await :as await]
            [seon.batch]
            [seon.bootstrap :as bootstrap]
            [seon.ai :as ai]
            [seon.turn :as turn]
            [seon.context :as context]
            [seon.fn]
            [seon.test.runner :as runner]
            [seon.config :as config]
            [seon.db :as db]
            ;; loaded for agents: the root opening names (seon.cluster.status/agents {})
            [seon.cluster.status]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.merge :as merge]
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
            [seon.cluster.message :as message]
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
                               '(:require [my.message] [my.turn] [seon.db :as db] [seon.program :as program]
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
  "Render an agent node: id, the namespace its latest evaluation ended in (else its home namespace), steward, the
  task it holds (`seon.task/agent-task`), the tasks naming its namespace as subject, and its status. The fleet
  belongs to the fleet page."
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
            database (:seon.db/db unit)
            ;; where the agent's REPL is: its SCI context keeps `*ns*` between eval requests
            namespace-name (or (when database
                                 (turn/previous-ending-namespace database (:db/id (db/pull database [:db/id] [:seon.agent/id agent-id]))))
                               namespace-name)
            held (when database
                   (some->> (task/agent-task database agent-id) (vector :seon.task/id)
                            (db/pull database [:seon.task/sym]) :seon.task/sym))
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
                   held (conj (entry "Task" [:code (str held)]))
                   (seq tasks)
                   (conj (entry "Tasks" (into [:span] (interpose " " (map #(vector :code (str %)) (sort tasks))))))
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

(defn- receive-message!
  "Write a received message once on this mailbox's connection; return whether it was new."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.message/message] :boolean]}
  [connection value]
  (if (db/q '[:find ?m . :in $ ?id :where [?m :seon.message/id ?id]]
            (db/db connection) (:seon.message/id value))
    false
    (let [result (db/with-branch
                  {:seon.db/connection connection}
                  #(db/transact! connection [[:db.fn/call #'message/send-call value]]))]
      (if (:db-after result)
        true
        (throw (ex-info "The recipient could not write its received message." result))))))

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
          ::flow/in-ports {:seon.agent/wake (:seon.cluster.wake/channel args)
                           :seon.agent/inbox (:seon.agent/inbox args)}
          :seon.agent/deliveries 0))
  ([state transition]
   (when (= ::flow/stop transition) (async/close! (:seon.agent/inbox state)))
   state)
  ([state input value]
   (if (= input :seon.agent/inbox)
     (do
       ;; The message's listened datom sends the one wake through this mailbox.
       (receive-message! (:seon.db/connection state) value)
       [state nil])
     [(update state :seon.agent/deliveries inc)
      {:seon.agent/episode [:seon.agent/wake]}])))

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
                           (:seon.cluster.wake/channel handle)
                           :seon.agent/inbox (:seon.agent/inbox handle)
                           :seon.db/connection (:seon.db/connection handle)}
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
  "A fresh registry: the one process-local atom of one execution's live objects.
  `:seon.agent/started` by agent id (the management view); `:seon.agent/channels` by
  recipient ENTITY id (the wake handler's one-lookup delivery);
  `:seon.agent/contexts` the execution handles acquired on this execution's branch,
  by `[branch agent-id]`; `:seon.agent/children` the registry of each other branch
  acquired through it, by branch; `:seon.flow/graph` the cluster graph, joined
  before it resumes."
  {:malli/schema [:=> [:cat] :seon.agent/routing]}
  []
  (atom {:seon.agent/started {} :seon.agent/channels {} :seon.agent/contexts {}}))

(defn- child-registry!
  "The registry `registry` records for `branch`, recorded when absent or released.
  The child names its parent, so the release that owns its branch removes the
  record; a released child still recorded is replaced, never reused."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.agent/branch] :seon.agent/routing]}
  [registry branch]
  (let [fresh (doto (routing) (swap! assoc :seon.agent/parent registry))]
    (get-in (swap! registry update-in [:seon.agent/children branch]
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
  acquisitions), then releases every owning handle of each child registry it
  records, through this same function; a failed join or a failed descendant
  throws before anything closes, so the subtree stays reachable for a retry.
  Live handles own nothing; borrowed branches persist. Then the connection release,
  the branch unlink, the removal of the handle's own context entry and of its
  registry from the parent's children are
  each attempted even when an earlier one throws; the first cause is then
  rethrown whole, carrying every later cause as suppressed."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle] :nil]}
  [handle]
  (let [owns-connection? (:seon.agent/owns-connection? handle)
        owns-branch? (:seon.agent/owns-branch? handle)
        attempt (fn [step] (try (step) nil (catch Throwable cause cause)))
        _ (when (or owns-connection? owns-branch?)
            (let [registry (:seon.agent/routing handle)]
              (wake/unlisten! {:seon.cluster.wake/connection (:seon.db/connection handle)
                               :seon.cluster.wake/key (branch-route-key (:seon.agent/branch handle))})
              (stop-owned-graphs! registry)
              (throw-causes!
               (into [] (keep (fn [execution] (attempt #(release-context! execution))))
                     (for [child (vals (:seon.agent/children @registry))
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
               ;; the handle's own context entry leaves the registry it was
               ;; acquired in, borrowed handles included: a released handle
               ;; that stayed recorded held its branch for every later
               ;; retire (reds-triage, 2026-09-28)
               #(swap! (:seon.agent/routing handle) update :seon.agent/contexts dissoc
                       [(:seon.agent/branch handle) (:seon.agent/id handle)])
               #(let [registry (:seon.agent/routing handle)]
                  (when-let [parent (and (or owns-connection? owns-branch?)
                                         (:seon.agent/parent @registry))]
                    (swap! parent update :seon.agent/children
                           (fn [children]
                             (cond-> children
                               (identical? registry (get children (:seon.agent/branch handle)))
                               (dissoc (:seon.agent/branch handle)))))))])]
    (throw-causes! causes))
  nil)

(defn- acquire-once!
  "Acquire `key` in `registry`'s contexts once per concurrent demand, holding no monitor.
  `swap-vals!` reserves the key with a delay of `(acquire previous)`; a caller
  finding one in flight derefs it, so same-key callers share one handle and
  other keys run in parallel. A released registry claims nothing: the
  reservation refuses by name (`::registry-released`), and `release-context!`
  settles every claim made before its mark. Settling (the handle, or previous
  on failure) applies only while this reservation holds the key."
  {:malli/schema [:=> [:cat :seon.agent/routing
                       [:tuple :seon.agent/branch [:maybe :seon.agent/id]]
                       [:fn clojure.core/ifn?]]
                  :seon.agent/execution-handle]}
  [registry key acquire]
  (let [path [:seon.agent/contexts key]
        reserve (fn [held]
                  (let [entry (get-in held path)]
                    (when (:seon.agent/released? held)
                      (throw (ex-info "This execution's registry is released; acquire through a live one."
                                      {::refused ::registry-released :seon.agent/branch (first key)
                                       :seon.error/message "This execution's registry is released; acquire through a live one."
                                       :seon.error/expected :seon.agent/routing})))
                    (if (::acquiring entry)
                      held
                      (assoc-in held path (assoc entry ::acquiring (delay (acquire entry)))))))
        entry (get-in (second (swap-vals! registry reserve)) path)
        pending (::acquiring entry)
        previous (not-empty (dissoc entry ::acquiring))
        settle! (fn [value]
                  (swap! registry
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
        (let [result (db/with-branch {:seon.db/connection connection}
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
  as the MCP tool does. One registry per execution retains the handles and
  private SCI objects across evaluation boundaries: the handle's own for its
  live branch, and for any other branch the child registry the handle's records,
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
   (let [parent-registry (:seon.agent/routing handle)
         parent (:seon.db/connection handle)
         ;; A released execution claims nothing, refused by name before its
         ;; released connection is read (Datahike refuses that read as
         ;; "Connection has been released.", which named no registry).
         _ (when (some-> parent-registry deref :seon.agent/released?)
             ;; the released routing names itself as `start-flow!`'s own refusal does
             (throw (ex-info "This execution's registry is released; acquire through a live one."
                             {::refused ::registry-released :seon.agent/id agent-id
                              :seon.error/message "This execution's registry is released; acquire through a live one."
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
     (when-not (and parent-registry branch)
       (throw (ex-info "Acquisition requires the handle's registry and an explicit agent branch."
                       {:seon.agent/id agent-id :seon.agent/branch branch})))
     (when (and (:seon.agent/isolate? options) (= branch live-branch))
       (throw (ex-info "Isolation cannot borrow the live cluster branch."
                       {:seon.agent/branch branch})))
     (let [isolated? (not= branch live-branch)
           acquire-in
           (fn [registry]
     (try
     (acquire-once! registry [branch agent-id] (fn [previous]
       (let [;; An entry still acquiring its first handle holds no connection yet.
             branch-owner (some (fn [[_ execution]] (when (:seon.db/connection execution) execution))
                                (:seon.agent/contexts @registry))
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
                     (let [result (db/with-branch {:seon.db/connection connection}
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
                    (cond-> (assoc (env/of base) :seon.agent/routing registry)
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
                            ;; The execution owns the registry its graphs start in, so
                            ;; its release can stop and join them before closing.
                            :seon.agent/routing registry
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
       ;; refused and retries through `child-registry!`, which replaces it.
       (catch Throwable failure
         (when (and isolated?
                    (:seon.agent/released?
                     (swap! registry #(cond-> % (empty? (:seon.agent/contexts %))
                                      (assoc :seon.agent/released? true)))))
           (swap! parent-registry update :seon.agent/children
                  #(cond-> % (identical? registry (get % branch)) (dissoc branch))))
         (throw failure))))]
       (if-not isolated?
         (acquire-in parent-registry)
         ;; A child released between `child-registry!` and the reservation is
         ;; replaced once; a released parent refuses the retry by name too.
         (try (acquire-in (child-registry! parent-registry branch))
              (catch clojure.lang.ExceptionInfo refusal
                (if (= ::registry-released (::refused (ex-data refusal)))
                  (acquire-in (child-registry! parent-registry branch))
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
          inbox (async/chan (:seon.config.agent/inbox-size settings))
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
                              :seon.agent/inbox inbox
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
                              ;; the registry when a fault fires: interrupt the
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
                 :seon.agent/inbox inbox
                 :seon.agent/requests requests
                 :seon.schedule/channel schedule-channel
                 :seon.turn.loop/completion completion
                 :seon.agent/turn-backstop-state turn-backstop-state
                 :seon.flow.actions/paused? paused?}]
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
      (let [result (db/with-branch
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
      (async/close! (:seon.agent/inbox entry))
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
;;; launch-actions: facts and registry in, actions out (R1)
;;; ---------------------------------------------------------------------------

(defn- follow
  "The actions that make `open`'s graph follow its intent `intent` (the
  cluster's :paused wins), given whether the registry holds it and has paused it."
  {:malli/schema [:=> [:cat :seon.flow.actions/open-attempt :seon.task/execution :boolean :boolean]
                  :seon.flow.actions/actions]}
  [open intent started? paused?]
  (let [action (fn [op] [(assoc (select-keys open [:seon.task/assigned :seon.task/assignment :seon.agent/id
                                                   :seon.task/branch :seon.chain/branch])
                                :seon.flow.actions/op op)])]
    (case intent
      (:running :paused) (cond (not started?) (mapv #(assoc % :seon.task/execution intent) (action :start-flow))
                               (and (= :paused intent) (not paused?)) (action :pause)
                               (and (= :running intent) paused?) (action :resume)
                               :else [])
      :stopped (if started? (action :stop) [])
      :cancelled (action :cancel))))

(defn launch-actions
  "The actions db<->flow takes next, as a vector.
  From `facts` read on main and the `registry`'s live
  graphs: each open attempt's graph follows its task's intent (`:held` and a
  paused cluster read as `:paused`); each startable leaf with no open attempt,
  oldest first, gets its next attempt and its flow while the open attempts that
  count (running, paused or held) are fewer than `max-running`, numbered one more than its attempts made and
  forking main, or the end commit of a latest attempt db<->flow relaunched, except a chain task's (`chains`),
  which lives on its chain branch and forks nothing; an invalid
  leaf gets a problem. Pure; a second pass over the same facts plans the same
  attempts, so their writes are no-ops by identity."
  {:malli/schema [:=> [:cat :seon.flow.actions/facts :seon.flow.actions/registry :seon.config.agent/max-running]
                  :seon.flow.actions/actions]}
  [{:seon.flow.actions/keys [startable invalid open attempts-made relaunch created chains branches cluster]}
   {:seon.flow.actions/keys [started paused]} max-running]
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
                                    (let [number (inc (get attempts-made handle 0))
                                          key (id/id [handle number])
                                          chain (get chains handle)
                                          row (merge {:seon.task/assigned handle :seon.task/assignment key
                                                      :seon.agent/id (str "attempt/" key)}
                                                     (if chain
                                                       ;; a task selects its branch by data, else its chain's
                                                       {:seon.task/branch (get branches handle chain) :seon.chain/branch chain}
                                                       {:seon.task/branch (keyword (str "attempt-" key))
                                                        :seon.flow.actions/fork-point (get relaunch handle :main)}))]
                                      [(assoc row :seon.flow.actions/op :attempt :seon.task.attempt/number number)
                                       (assoc row :seon.flow.actions/op :start-flow :seon.task/execution :running)])))
                       fresh)
        problems (mapv (fn [handle] {:seon.flow.actions/op :problem :seon.flow.actions/class :invalid-task
                                     :seon.task/assigned handle
                                     :seon.task/plan "Restore the step's obligations at its fork point: an :implement step needs tagged proofs; a :prove step needs its own tagged proofs or a successor task declaring it in :seon.task/needs."})
                       (sort (remove attempted invalid)))]
    (into problems (concat followed launches))))

(defn- read-failure
  "The read refusal among `reads`, or nil."
  {:malli/schema [:=> [:cat [:sequential :seon.schema/value]] [:maybe :seon.db/invalid-read-error]]}
  [reads]
  (some #(when (and (map? %) (:seon.error/at %)) %) reads))

(defn- basis-valid?
  "Whether `sym` has admission obligations on main `database`, the value its attempt forks."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym]
                  [:or :boolean :seon.db/invalid-read-error :seon.db/pull-budget-error]]}
  [database sym]
  (cache/lookup ::basis-valid [(db/commit-id database) sym]
                (constantly 1)
                #(let [owed (task/obligations database sym)]
                   (cond
                     (:seon.error/at owed) owed
                     (seq (:seon.task/proofs owed)) true
                     (= :prove (:seon.task/step owed))
                     (let [successor (db/q '[:find ?next . :in $ ?s :where [?t :seon.task/sym ?s]
                                            [?next :seon.task/needs ?t]] database sym)]
                       (if (map? successor) successor (some? successor)))
                     :else false))
                (fn [value] (not (and (map? value) (:seon.error/at value))))))

(defn- chain-read
  "What db<->flow reads of the chain whose first task is `first-handle` on its branch head `head`: along the first
  task's `:seon.task/next` path only (the branch also inherits main's unrelated tasks, which are not its work), the
  tasks that start (execution :running, latest attempt neither :done nor :merged, no attempt lacking an outcome),
  every open attempt (`seon.task/open-attempt?`), attempts made, creation order and the chain `branch` of each."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.chain/branch :seon.task/handle]
                  [:or [:map [:seon.flow.actions/startable :seon.flow.actions/startable]
                        [:seon.flow.actions/open :seon.flow.actions/open]
                        [:seon.flow.actions/attempts-made :seon.flow.actions/attempts-made]
                        [:seon.flow.actions/created :seon.flow.actions/created]
                        [:seon.flow.actions/chains :seon.flow.actions/chains]
                        [:seon.flow.actions/branches {:optional true} :seon.flow.actions/branches]]
                   :seon.db/invalid-read-error]]}
  [head branch first-handle]
  (loop [handle first-handle
         read {:seon.flow.actions/startable #{} :seon.flow.actions/open [] :seon.flow.actions/attempts-made {}
               :seon.flow.actions/created {} :seon.flow.actions/chains {}}]
    (let [task (db/pull head [:db/id :seon.task/execution :seon.task/attempts-made :seon.task/branch
                              {:seon.task/next [:seon.task/id]}]
                        [:seon.task/id handle])
          created (when (:db/id task) (db/q '[:find ?tx . :in $ ?h :where [_ :seon.task/id ?h ?tx]] head handle))
          rows (when (:db/id task)
                 (db/q '[:find [(pull ?a [:seon.task/assignment :seon.task.attempt/number :seon.task.attempt/end
                                          :seon.task/outcome {:seon.task/agent [:seon.agent/id]}]) ...]
                         :in $ ?t :where [?a :seon.task/attempt-of ?t]]
                       head (:db/id task)))]
      (cond
        (read-failure [task created rows]) (read-failure [task created rows])
        ;; a path whose task the head lacks ends there
        (nil? (:db/id task)) read
        :else
        (let [execution (:seon.task/execution task)
              latest (last (sort-by :seon.task.attempt/number rows))
              starts? (and (= :running execution) (not (#{:done :merged} (:seon.task/outcome latest)))
                           (every? :seon.task/outcome rows))
              open (for [row (sort-by :seon.task/assignment rows) :when (task/open-attempt? row)]
                     {:seon.task/assigned handle :seon.task/assignment (:seon.task/assignment row)
                      :seon.agent/id (get-in row [:seon.task/agent :seon.agent/id])
                      :seon.task/branch (:seon.task/branch task branch)
                      :seon.task/execution execution :seon.chain/branch branch})
              read (cond-> (-> read
                               (update :seon.flow.actions/open into open)
                               (assoc-in [:seon.flow.actions/chains handle] branch)
                               (assoc-in [:seon.flow.actions/created handle] created))
                     starts? (update :seon.flow.actions/startable conj handle)
                     (:seon.task/branch task) (assoc-in [:seon.flow.actions/branches handle] (:seon.task/branch task))
                     (:seon.task/attempts-made task) (assoc-in [:seon.flow.actions/attempts-made handle]
                                                               (:seon.task/attempts-made task)))
              after (get-in task [:seon.task/next :seon.task/id])]
          (if after (recur after read) read))))))

(defn chain-facts
  "What db<->flow reads of every chain on main `database` before it plans: each chain fact whose branch the store
  holds now (read after the pass's lifecycle, so a branch it deleted is never read), its branch head read once
  (`seon.db/branch-database`, a store read needing no open connection) and released after use (`chain-read`).
  `branches` is the store's branches read after the lifecycle (`seon.cluster.registry/branches`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.registry/branches]
                  [:or [:map [:seon.flow.actions/startable :seon.flow.actions/startable]
                        [:seon.flow.actions/open :seon.flow.actions/open]
                        [:seon.flow.actions/attempts-made :seon.flow.actions/attempts-made]
                        [:seon.flow.actions/created :seon.flow.actions/created]
                        [:seon.flow.actions/chains :seon.flow.actions/chains]
                        [:seon.flow.actions/branches {:optional true} :seon.flow.actions/branches]]
                   :seon.db/invalid-read-error]]}
  [database branches]
  (let [chains (db/q '[:find ?b ?h :where [?c :seon.chain/branch ?b] [?c :seon.chain/first ?h]] database)
        empty-read {:seon.flow.actions/startable #{} :seon.flow.actions/open [] :seon.flow.actions/attempts-made {}
                    :seon.flow.actions/created {} :seon.flow.actions/chains {}}]
    (or (read-failure [chains])
        (reduce (fn [facts [branch first-handle]]
                  (let [head (when (contains? branches branch) (db/branch-database database branch))
                        read (if head
                               (try (chain-read head branch first-handle) (finally (source/release! [head])))
                               empty-read)]
                    (if (:seon.error/at read) (reduced read) (merge-with into facts read))))
                empty-read (sort chains)))))

(defn- chain-ends
  "The finished attempts root advances past, read on main `database` among the store's `branches`: on each chain's
  `:seon.task/next` path, every attempt that ended :done holding no outcome, whose task has a next (a last task's
  done goes to the merge), with its chain branch, task and end commit."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.registry/branches]
                  [:vector [:map [:seon.chain/branch :seon.chain/branch] [:seon.task/assignment :seon.task/assignment]
                            [:seon.task/assigned :seon.task/handle] [:seon.task.attempt/commit :seon.task.attempt/commit]]]]}
  [database branches]
  (let [chains (db/q '[:find ?b ?h :where [?c :seon.chain/branch ?b] [?c :seon.chain/first ?h]] database)]
    (when (map? chains) (throw (ex-info (:seon.error/message chains "db<->flow could not read the chains.") chains)))
    (into [] (mapcat (fn [[branch first-handle]]
                       (when-let [head (when (contains? branches branch) (db/branch-database database branch))]
                         (try
                           (let [rows (db/q '[:find ?k ?h ?c :in $ [?h ...]
                                              :where [?t :seon.task/id ?h] [?t :seon.task/next _] [?a :seon.task/attempt-of ?t]
                                              [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end :done]
                                              [?a :seon.task.attempt/commit ?c] (not [?a :seon.task/outcome _])]
                                            head (task/chain-handles head first-handle))]
                             (when (map? rows) (throw (ex-info (:seon.error/message rows "db<->flow could not read a chain's ends.") rows)))
                             (for [[k h c] (sort rows)]
                               {:seon.chain/branch branch :seon.task/assignment k :seon.task/assigned h :seon.task.attempt/commit c}))
                           (finally (source/release! [head]))))))
          (sort chains))))

(defn- done-judged
  "What the done rule (`seon.cluster.source/done-refusals`) judges for chain task `handle` at `at-end`, its chain
  `branch` at the attempt's end commit: its proofs field, the next task's, the tests the branch defines or changes
  and those reaching the functions it changed since its merge base with main (`seon.cluster.source/merge-base`,
  `seon.program/own-changed-identities`; `seon.fn/gate-sets`), their current results there
  (`seon.context/current-results`), those main `database` holds green at its own reach, and the newest run they
  came from, else the newest the branch holds (an attempt's start runs its tests, so a done end always has one)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.chain/branch :seon.task/handle]
                  :seon.source/judged]}
  [database at-end branch handle]
  (let [task (db/pull at-end [:seon.task/sym :seon.task/proofs {:seon.task/next [:seon.task/proofs]}] [:seon.task/id handle])
        proofs (set (:seon.task/proofs task))
        next-proofs (set (get-in task [:seon.task/next :seon.task/proofs]))
        base-commit (source/merge-base database (db/commit-id at-end) (db/commit-id database))
        base (when (uuid? base-commit) (db/commit-database at-end base-commit))
        delta (cond (:seon.error/at base-commit) base-commit
                    base (try (program/own-changed-identities base at-end source/merge-bound)
                              (finally (source/release! [base])))
                    :else #{})
        of (fn [attribute] (into #{} (keep (fn [[a s]] (when (= attribute a) s))) (when (set? delta) delta)))
        reached (if (seq (of :seon.fn/sym)) (seon.fn/gate-sets {:seon.db/db at-end :seon.fn/seeds (of :seon.fn/sym)}) [])
        members (when (sequential? reached) (-> proofs (into (of :seon.test/sym)) (into reached)))
        results (when members (context/current-results at-end members (:seon.task/sym task)))
        held (when (vector? results) (context/current-results database members nil))
        failure (some #(when (and (map? %) (:seon.error/at %)) %) [task delta reached results held])
        newest (fn [runs] (some->> (seq runs) (apply max-key second) first))
        run-id (when (vector? results)
                 (or (newest (keep #(when-let [[_ id] (:seon.test/run %)] [id (:seon.test/run-basis-t %)]) results))
                     (newest (db/q '[:find ?id ?t :where [?r :seon.test.run/id ?id] [?r :seon.test.run/basis-t ?t]] at-end))))]
    (when failure (throw (ex-info (:seon.error/message failure "db<->flow could not build a chain task's judgment.") failure)))
    (when-not (string? run-id)
      (throw (ex-info "A chain task ended done on a branch that recorded no test run."
                      {:seon.error/message "A chain task ended done on a branch that recorded no test run."
                       :seon.task/assigned handle :seon.chain/branch branch})))
    (cond-> {:seon.source/members members :seon.source/results results :seon.task/proofs proofs
               :seon.program/identities delta :seon.test.run/id run-id :seon.test.run/branch branch}
        (seq next-proofs) (assoc :seon.source/next-proofs next-proofs)
        (seq held) (assoc :seon.source/green-on-target
                          (into #{} (comp (remove runner/red-result?) (filter #(pos? (:seon.test/pass-count % 0)))
                                          (map :seon.test/sym))
                                held)))))

(defn launch-facts
  "What db<->flow reads on main `database` before it plans.
  The ready leaves (every need merged) split by obligations on main, which
  their attempts fork, with open attempts, attempts made, the relaunched leaves' fork commits
  (`seon.task/relaunched`) and creation order; joined by every chain's (`chain-facts`), read on its branch among the
  store's `branches`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.registry/branches]
                  [:or :seon.flow.actions/facts :seon.db/invalid-read-error]]}
  [database branches]
  (let [ready (task/ready database)
        leaves (when (set? ready) (vec ready))
        syms (when leaves (db/q '[:find ?h ?s :in $ [?h ...] :where [?t :seon.task/id ?h] [?t :seon.task/sym ?s]]
                                database leaves))
        open (db/q '[:find ?h ?k ?id ?b ?i :in $ %
                     :where [?a :seon.task/attempt-of ?t] (open-attempt ?a)
                     [?a :seon.task/assigned ?h] [?a :seon.task/assignment ?k] [?a :seon.task/branch ?b]
                     [?a :seon.task/agent ?g] [?g :seon.agent/id ?id] (execution ?t ?i)]
                   database task/ready-rules)
        made (when leaves (db/q '[:find ?h ?m :in $ [?h ...] :where [?t :seon.task/id ?h] [?t :seon.task/attempts-made ?m]]
                                database leaves))
        relaunched (mapv (fn [handle] [handle (task/relaunched database handle)]) leaves)
        created (when leaves (db/q '[:find ?h ?tx :in $ [?h ...] :where [_ :seon.task/id ?h ?tx]] database leaves))
        owed (when (and syms (not (map? syms)))
               (mapv (fn [[handle sym]] [handle (basis-valid? database sym)]) syms))
        proved (into #{} (keep (fn [[handle valid?]] (when (true? valid?) handle))) owed)
        chains (chain-facts database branches)]
    (or (read-failure (concat [ready syms open made created chains] (map second owed) (map second relaunched)))
        (merge-with into
        {:seon.flow.actions/startable (into #{} (filter proved) leaves)
         :seon.flow.actions/invalid (into #{} (remove proved) leaves)
         :seon.flow.actions/open (mapv (fn [[h k id b i]] {:seon.task/assigned h :seon.task/assignment k :seon.agent/id id
                                                        :seon.task/branch b :seon.task/execution i})
                                    open)
         :seon.flow.actions/attempts-made (into {} made)
         :seon.flow.actions/relaunch (into {} (keep (fn [[handle row]] (when row [handle (:seon.task.attempt/commit row)])))
                                        relaunched)
         :seon.flow.actions/created (into {} created)
         :seon.flow.actions/cluster :running}
        chains))))

(defn- started-entries
  "Every started entry `routing` holds, its own and its branch children's."
  {:malli/schema [:=> [:cat :seon.agent/routing] [:sequential :seon.agent/started]]}
  [routing]
  (concat (vals (:seon.agent/started @routing))
          (mapcat #(vals (:seon.agent/started @%)) (vals (:seon.agent/children @routing)))))

(defn registry-facts
  "What db<->flow reads of `routing` before it plans.
  The started graphs by agent, its own and its branch children's, and the
  paused among them."
  {:malli/schema [:=> [:cat :seon.agent/routing] :seon.flow.actions/registry]}
  [routing]
  (let [entries (started-entries routing)]
    {:seon.flow.actions/started (into #{} (map :seon.agent/id) entries)
     :seon.flow.actions/paused (into #{} (keep #(when (:seon.flow.actions/paused? %) (:seon.agent/id %))) entries)}))

(defn branch-handle
  "The cluster handle `cluster-handle` seen from the branch `execution`.
  Its connection, contexts and registry are the branch's, while wakes for
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
  agent, an unrouted recipient wakes the cluster's db<->flow, and a commit whose
  attributes meet the cluster's render interest wakes the cluster's render proc,
  since the agent's page reads its branch (`seon.render.web/agent-connection`)."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/execution-handle :seon.agent/id
                       :seon.task/execution]
                  :seon.agent/started]}
  [cluster-handle execution agent-id intent]
  (let [registry (:seon.agent/routing execution)
        route (wake/route! (merge {:seon.cluster.wake/connection (:seon.db/connection execution)
                                   :seon.cluster.wake/channels (fn [] (channels registry))
                                   :seon.cluster.wake/fenced? (fn [eid channel] (fenced-route? registry eid channel))
                                   :seon.flow/plan-channel (:seon.cluster.wake/channel cluster-handle)
                                   :seon.cluster.wake/key (branch-route-key (:seon.agent/branch execution))}
                                  ;; a cluster that serves no view carries neither, and its branches deliver no render wake
                                  (set/rename-keys (select-keys cluster-handle [:seon.render.web/render-channel
                                                                                :seon.render.web/interest])
                                                   {:seon.render.web/render-channel :seon.cluster.wake/render-channel})))]
    (when (map? route)
      (throw (ex-info (:seon.error/message route "The branch's wake declarations cannot route.") route)))
    (start-flow! {:seon.turn.loop/cluster (branch-handle cluster-handle execution)
                  :seon.agent/id agent-id :seon.agent/routing registry :seon.task/execution intent})))

(defn- write!
  "Transact `tx` on `connection` under its own custody; throws the writer's refusal."
  {:malli/schema [:=> [:cat :seon.db/connection [:or :seon.db/tx-data :seon.store/transaction]] :seon.db/transaction-report]}
  [connection tx]
  (let [report (db/with-branch {:seon.db/connection connection} #(db/transact! connection tx))]
    (when-not (:db-after report)
      (throw (ex-info (:seon.error/message report "db<->flow's write was refused.") report)))
    report))

(defn- attempt-agent
  "The creation request of the agent an attempt action names, in the cluster `cluster-name`."
  {:malli/schema [:=> [:cat :seon.flow.actions/action :seon.cluster/name] :seon.agent/creation-request]}
  [{agent-id :seon.agent/id key :seon.task/assignment branch :seon.task/branch} cluster-name]
  {:seon.agent/id agent-id :seon.agent/branch branch :seon.cluster/name cluster-name
   :seon.ns/name (symbol (str "my.attempt-" key))})

(defn- write-attempt!
  "Write an `:attempt` action on main before anything runs.
  The agent's creation and the attempt row are ONE transaction, so the branch
  forked from main after it holds both; nothing is written when the attempt
  already is."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.flow.actions/action] :nil]}
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
  "Fork an attempt's `branch` toward main at `fork-point` unless the store holds it: main's head, or a relaunched
  attempt's end commit, so its work carries over and its commit-graph fork point stays that attempt's
  (`seon.cluster.registry/fork-point` walks through it), so its merge takes both attempts' changes. Every need merged
  before the attempt was planned (`seon.task/ready`), so main holds what they made."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.store/branch :seon.flow.actions/fork-point] :nil]}
  [handle branch fork-point]
  (let [database (db/db (:seon.db/connection handle))
        main (get-in database [:config :branch])
        store (handle-store handle)
        result (when-not (contains? (registry/branches store) branch)
                 (registry/fork! {:seon.store/store store :seon.store/branch branch :seon.branch/target main
                                  :seon.db/db (if (uuid? fork-point) (db/commit-database database fork-point) database)
                                  :seon.cluster.registry/from (if (uuid? fork-point) fork-point main)}))]
    (when (:seon.error/at result) (throw (ex-info (:seon.error/message result "The attempt's fork was refused.") result)))
    nil))

(defn- seed-attempt!
  "Write attempt `key`'s agent and row onto the branch `connection` holds when its fork point predates them
  (a branch forked before its row was written), at fork: the agent under main's entity id (its identity first, then its
  creation upserts onto it) and the row (`seon.task/attempt-rows-tx`), naming its task by identity in the
  branch's world, so the agent reads its assignment there and its end names main's entities."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.db/connection :seon.flow.actions/action] :nil]}
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
  Fork its branch at its fork point,
  acquire the branch execution, write its agent and attempt there when the fork point predates them
  (`seed-attempt!`), and start its flow.
  A repeat finds the branch, its rows and the started graph and does nothing new."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.flow.actions/action] :nil]}
  [handle {agent-id :seon.agent/id branch :seon.task/branch intent :seon.task/execution fork-point :seon.flow.actions/fork-point
           :as action}]
  (fork-attempt! handle branch (or fork-point :main))
  (let [execution (acquire-context! handle nil {:seon.agent/branch branch})]
    (seed-attempt! handle (:seon.db/connection execution) action)
    (start-branch-flow! handle execution agent-id (or intent :running))
    nil))

(defn- write-chain-attempt!
  "Write a chain task's `:attempt` action on its chain branch, never on main, through a connection held for this
  action: the agent's creation (homed on the chain branch) and the attempt row in ONE transaction; nothing when the
  branch already holds the attempt."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.flow.actions/action] :nil]}
  [handle {h :seon.task/assigned agent-id :seon.agent/id branch :seon.chain/branch works-on :seon.task/branch :as action}]
  (registry/on-branch (handle-store handle) branch
             (fn [connection]
               (let [database (db/db connection)
                     tx (task/attempt-tx database h agent-id (or works-on branch))
                     known? (db/q '[:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]] database agent-id)]
                 (when (map? tx) (throw (ex-info (:seon.error/message tx "The chain attempt could not be derived.") tx)))
                 (when (seq tx)
                   (write! connection (into (if known? [] (creation-tx (attempt-agent action (:seon.cluster/name handle)))) tx)))
                 nil))))

(defn- start-chain-attempt!
  "Start a chain task's `:start-flow` action: a NEW agent's graph on its chain branch, acquired as that agent's
  execution (`acquire-context!` with the branch), under its intent. It forks no branch and writes nothing on main."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.flow.actions/action] :nil]}
  [handle {agent-id :seon.agent/id branch :seon.chain/branch works-on :seon.task/branch intent :seon.task/execution
           key :seon.task/assignment h :seon.task/assigned :as action}]
  (let [works-on (or works-on branch)
        store (handle-store handle)]
    ;; a task naming its own branch: its task, agent and attempt row are placed there from the chain branch, so its
    ;; agent reads its assignment in its own world (as `seon.task/assign!` places a task on an agent's branch)
    (when (not= works-on branch)
      (registry/on-branch store branch
                (fn [on-chain]
                  (registry/on-branch store works-on
                            (fn [there]
                              (when-let [refused (task/task-placed! (db/db on-chain) there h)]
                                (throw (ex-info (:seon.error/message refused "The task could not be placed on its branch.") refused)))
                              ;; by identity, never by the chain branch's entity ids: the task's branch forked elsewhere
                              (let [rows (task/attempt-rows-tx (db/db on-chain) (db/db there) key)
                                    known? (db/q '[:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]] (db/db there) agent-id)]
                                (when (map? rows) (throw (ex-info (:seon.error/message rows "The attempt's rows could not be derived.") rows)))
                                (when (seq rows)
                                  (write! there (into (if known? [] (creation-tx (attempt-agent action (:seon.cluster/name handle))))
                                                      (map #(dissoc % :db/id)) rows)))))))))
    (start-branch-flow! handle (acquire-context! handle nil {:seon.agent/branch works-on}) agent-id (or intent :running)))
  nil)

(declare write-problem!)

(defn- bail-chain!
  "Bail chain attempt `key` of task `h` on chain `branch` to root with `reason` (TASK-CHAIN §2.7), through cluster
  `handle`: first its problem task on main, named by the attempt (`write-problem!`), so a repeat writes none; then
  ONE transaction on the chain branch, decided at the writer (`seon.task/hold-tx`): the attempt `:closed` with its
  reason and the task `:held`, with `tx-data` (a refused merge's type). The branch and the chain fact stay; nothing
  else runs."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.chain/branch :seon.task/assignment :seon.task/handle
                       :seon.task/reason :seon.db/tx-data]
                  :nil]}
  [handle branch key h reason tx-data]
  (write-problem! handle {:seon.flow.actions/op :problem :seon.task/assignment key :seon.task/assigned h
                          :seon.flow.actions/class :unfinished-task
                          :seon.task/plan (str "Chain attempt " key " of task " h " on branch " (name branch) " bailed: " reason
                                               " It is :closed and the task :held; the branch and its chain fact are kept."
                                               " Change the task on its branch and run it again with (my.root/run! {:seon.task/assigned \""
                                               h "\"}), abandon the chain, or build a new one from the branch"
                                               " (seon.task/chain! with :seon.chain/from " branch ").")})
  (registry/on-branch (handle-store handle) branch #(write! % (into [[:db.fn/call #'task/hold-tx key reason]] tx-data)))
  nil)

(defn- chain-bails
  "The ended chain attempts root bails, read through `store` on main `database` (`seon.task/chain-attempts`):
  every attempt holding no outcome whose end is neither :done (judged by the done rule and the final merge, which
  bail their own refusals) nor :cancelled, with its reason."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/store]
                  [:vector [:map [:seon.chain/branch :seon.chain/branch] [:seon.task/assignment :seon.task/assignment]
                            [:seon.task/assigned :seon.task/handle] [:seon.task/reason :seon.task/reason]]]]}
  [database store]
  (let [attempts (task/chain-attempts database store)]
    (when (:seon.error/at attempts) (throw (ex-info (:seon.error/message attempts "db<->flow could not read the chains.") attempts)))
    (into [] (keep (fn [{branch :seon.chain/branch key :seon.task/assignment end :seon.task.attempt/end :as row}]
                     (when (and end (not (:seon.task/outcome row)) (not (#{:done :cancelled} end)))
                       (registry/on-branch store branch
                                  (fn [connection]
                                    (let [{why :seon.task.attempt/reason}
                                          (db/pull (db/db connection) [:seon.task.attempt/reason] [:seon.task/assignment key])]
                                      (assoc (select-keys row [:seon.chain/branch :seon.task/assignment :seon.task/assigned])
                                             :seon.task/reason (str "The attempt ended " end (if why (str ": " why) ".")))))))))
          attempts)))

(defn- bail-chains!
  "Bail every ended chain attempt root does not advance or merge (`chain-bails`) through cluster `handle`."
  {:malli/schema [:=> [:cat :seon.agent/context-source] :nil]}
  [handle]
  (doseq [{branch :seon.chain/branch key :seon.task/assignment h :seon.task/assigned reason :seon.task/reason}
          (chain-bails @(:seon.db/connection handle) (handle-store handle))]
    (bail-chain! handle branch key h reason []))
  nil)

(defn- refused-reason
  "The reason a chain task's done was refused by the done rule's `refusals`."
  {:malli/schema [:=> [:cat :seon.source/refusals] :seon.task/reason]}
  [refusals]
  (str "The done rule refused its done: " (str/join ", " (sort (into #{} (map #(str (:seon.error/type %))) refusals))) "."))

(defn- merge-refused-reason
  "The reason a chain's final merge was refused by `refusal`: its type and its producer's message."
  {:malli/schema [:=> [:cat :seon.error/value] :seon.task/reason]}
  [{refused :seon.error/type message :seon.error/message}]
  (str "The final merge refused it, " refused (if message (str ": " message) ".")))

(defn- advance-chains!
  "Advance every chain past its finished attempts (`chain-ends`) through cluster `handle`: when the done rule
  (`seon.cluster.source/done-refusals`) refuses nothing over the chain branch at the attempt's end commit, ONE
  transaction on the chain branch through a connection held for this action, decided at the writer
  (`seon.task/advance-tx`), so a repeat writes nothing. A refused done bails to root (`bail-chain!`)."
  {:malli/schema [:=> [:cat :seon.agent/context-source] :nil]}
  [handle]
  (let [store (handle-store handle)
        database @(:seon.db/connection handle)]
    (doseq [{branch :seon.chain/branch key :seon.task/assignment h :seon.task/assigned commit :seon.task.attempt/commit}
            (chain-ends database (registry/branches store))]
      (let [refusals (registry/on-branch store branch
                                (fn [on-chain]
                                  (let [at-end (db/commit-database (db/db on-chain) commit)]
                                    (when-not at-end
                                      (throw (ex-info "The chain branch retains no end commit of its finished attempt."
                                                      {:seon.error/message "The chain branch retains no end commit of its finished attempt."
                                                       :seon.task/assignment key :seon.chain/branch branch})))
                                    (let [refusals (try (source/done-refusals at-end (done-judged database at-end branch h))
                                                        (finally (source/release! [at-end])))]
                                      (when (empty? refusals) (write! on-chain [[:db.fn/call #'task/advance-tx key]]))
                                      refusals))))]
        (when (seq refusals) (bail-chain! handle branch key h (refused-reason refusals) [])))))
  nil)

(defn- chain-finishes
  "The terminal attempts root merges, read on main `database` among the store's `branches`: on each chain's
  `:seon.task/next` path, every attempt of a task with no next that ended :done holding no outcome (a refused merge
  closes it), which main does not hold yet (its one merge copies it there), with its chain branch, task and end
  commit."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.registry/branches]
                  [:vector [:map [:seon.chain/branch :seon.chain/branch] [:seon.task/assignment :seon.task/assignment]
                            [:seon.task/assigned :seon.task/handle] [:seon.task.attempt/commit :seon.task.attempt/commit]]]]}
  [database branches]
  (let [chains (db/q '[:find ?b ?h :where [?c :seon.chain/branch ?b] [?c :seon.chain/first ?h]] database)]
    (when (map? chains) (throw (ex-info (:seon.error/message chains "db<->flow could not read the chains.") chains)))
    (into [] (mapcat (fn [[branch first-handle]]
                       (when-let [head (when (contains? branches branch) (db/branch-database database branch))]
                         (try
                           (let [rows (db/q '[:find ?k ?h ?c :in $ [?h ...]
                                              :where [?t :seon.task/id ?h] (not [?t :seon.task/next _]) [?a :seon.task/attempt-of ?t]
                                              [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end :done]
                                              [?a :seon.task.attempt/commit ?c] (not [?a :seon.task/outcome _])]
                                            head (task/chain-handles head first-handle))]
                             (when (map? rows) (throw (ex-info (:seon.error/message rows "db<->flow could not read a chain's ends.") rows)))
                             (for [[k h c] (sort rows)
                                   :when (nil? (:db/id (db/pull database [:db/id] [:seon.task/assignment k])))]
                               {:seon.chain/branch branch :seon.task/assignment k :seon.task/assigned h :seon.task.attempt/commit c}))
                           (finally (source/release! [head]))))))
          (sort chains))))

(defn- merge-step
  "The merge step root appends after a final merge `refused` by its gate (MERGE-STEP): `seon.step.merge/template`'s
  owned members on main `database`, the merge branch the refusal kept as its `:seon.task/branch`, the refusal's type
  and its red tests; nil when the refusal kept no merge branch (it refused before the gate built one)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.error/value] [:maybe :seon.task/task-map]]}
  [database refused]
  (when-let [kept (:seon.source/candidate refused)]
    (let [template (task/template database 'seon.step.merge/template)]
      (when (:seon.error/at template)
        (throw (ex-info (:seon.error/message template "The merge step's template is not ingested.") template)))
      (cond-> (assoc template :seon.task/branch kept :seon.task/refusal (:seon.error/type refused))
        (seq (:seon.source/red refused)) (assoc :seon.task/refused-tests (set (:seon.source/red refused)))))))

(defn- merge-chains!
  "Merge every finished chain (`chain-finishes`) to main once through cluster `handle`: when the done rule
  (`seon.cluster.source/done-refusals`) refuses nothing over the chain branch at the terminal attempt's end commit,
  `seon.merge/merge!` with that attempt as its source, the files proven against the cluster's checkout. A refused
  done or a refused merge bails to root (`bail-chain!`), the merge's refusal its reason (`merge-refused-reason`) and
  its type kept on the attempt (`:seon.task/refusal`). Answers whether any merged."
  {:malli/schema [:=> [:cat :seon.agent/context-source] :boolean]}
  [handle]
  (let [store (handle-store handle)
        connection (:seon.db/connection handle)
        database @connection]
    (boolean
     (some identity
           (doall
            (for [{branch :seon.chain/branch key :seon.task/assignment h :seon.task/assigned commit :seon.task.attempt/commit}
                  (chain-finishes database (registry/branches store))]
              (let [refusals (registry/on-branch store branch
                                        (fn [on-chain]
                                          (let [at-end (db/commit-database (db/db on-chain) commit)]
                                            (try (source/done-refusals at-end (done-judged database at-end branch h))
                                                 (finally (source/release! [at-end]))))))]
                (if (seq refusals)
                  (do (bail-chain! handle branch key h (refused-reason refusals) []) false)
                  (let [root (merge/checkout database (:seon.cluster/name handle))
                        merged (db/with-branch {:seon.db/connection connection}
                                               #(apply merge/merge! (assoc handle :seon.store/store store) key
                                                       (when (string? root) [root])))]
                    (if (:seon.error/at merged)
                      (let [step (merge-step database merged)
                            first-handle (db/q '[:find ?h . :in $ ?b :where [?c :seon.chain/branch ?b] [?c :seon.chain/first ?h]]
                                               database branch)]
                        (bail-chain! handle branch key h
                                     (cond-> (merge-refused-reason merged)
                                       step (str " Its merge branch " (name (:seon.task/branch step)) " is kept, and a held merge step"
                                                 " after task " h " works on it: run it with my.root/run!."))
                                     (cond-> [[:db/add [:seon.task/assignment key] :seon.task/refusal (:seon.error/type merged)]]
                                       step (conj [:db.fn/call #'task/next-tx first-handle step])))
                        false)
                      true))))))))))

(defn- finish-chains!
  "Publish, then retire, every chain main `handle`'s connection holds merged (TASK-CHAIN §2.6): its terminal
  attempt on main closed `:merged`. Due while main records for it no git commit and no publication refusal, its
  merge commit derived from the merge itself (`seon.merge/merge-commit`), so a restart between acceptance and its
  answer publishes exactly once; a merge that moved nothing publishes nothing. Once published, or with nothing to
  publish, its branch is deleted (`seon.task/delete-branch!`, which joins its graphs) and its chain fact
  retracted. A `:seon.merge/waiting` publication or a recorded refusal keeps both, for root."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing] :nil]}
  [handle routing]
  (let [connection (:seon.db/connection handle)
        store (handle-store handle)
        database @connection
        rows (db/q '[:find ?c ?b ?a ?k :where [?c :seon.chain/branch ?b] [?a :seon.task/branch ?b]
                     [?a :seon.task/outcome :merged] [?a :seon.task/assignment ?k]]
                   database)]
    (when (map? rows) (throw (ex-info (:seon.error/message rows "db<->flow could not read the merged chains.") rows)))
    (doseq [[fact branch a key] (sort-by second rows)]
      (let [recorded #(db/pull @connection [:seon.task/git-commit :seon.task/publication-refusal] a)
            accepted (source/merge-accepted connection (merge/merge-commit database a))
            _ (when (:seon.error/at accepted) (throw (ex-info (:seon.error/message accepted) accepted)))
            moved? (seq (:seon.program/identities accepted))
            {git :seon.task/git-commit refused :seon.task/publication-refusal} (recorded)
            published (when (and moved? (not git) (not refused)) (merge/publish! handle key))]
        (when (:seon.error/at published)
          (throw (ex-info (:seon.error/message published "The chain's publication was refused.") published)))
        (when (or (not moved?) (:seon.task/git-commit (recorded)))
          (when (contains? (registry/branches store) branch)
            (when-let [refused (task/delete-branch! {:seon.db/connection connection :seon.store/store store
                                                     :seon.agent/routing routing :seon.env/environment (env/of handle)}
                                                    branch)]
              (throw (ex-info (:seon.error/message refused "The merged chain's branch was not deleted.") refused))))
          (write! connection [[:db/retractEntity fact]])))))
  nil)

(defn- entry-registry
  "The registry, `routing` or one of its branch children, holding `agent-id`'s started entry."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.agent/id] [:maybe :seon.agent/routing]]}
  [routing agent-id]
  (some #(when (get-in @% [:seon.agent/started agent-id]) %)
        (cons routing (vals (:seon.agent/children @routing)))))

(defn started-custody
  "The custody `evaluate-source!` takes to enter `agent-id` where its graph runs under `routing` (the cluster's
  registry or one of its branch children, as a chain agent runs on its chain branch): the started entry's own
  handle and registry, or nil when no graph of `agent-id` runs there."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.agent/id]
                  [:maybe [:map [:seon.turn.loop/cluster :map] [:seon.agent/routing :seon.agent/routing]
                           [:seon.agent/id :seon.agent/id]]]]}
  [routing agent-id]
  (when-let [registry (entry-registry routing agent-id)]
    {:seon.turn.loop/cluster (:seon.turn.loop/cluster (started registry agent-id))
     :seon.agent/routing registry :seon.agent/id agent-id}))

(defn- follow!
  "Make `agent-id`'s started graph follow the action's op.
  Pause or resume it in place (recorded on its entry), stop and join it letting
  its current pass finish, or interrupt and stop it; the next pass closes a cancelled attempt (`close!`)."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing :seon.flow.actions/action] :nil]}
  [handle routing {op :seon.flow.actions/op agent-id :seon.agent/id}]
  (when-let [registry (entry-registry routing agent-id)]
    (case op
      :pause (do (flow/pause (:seon.flow/graph (started registry agent-id)))
                 (swap! registry assoc-in [:seon.agent/started agent-id :seon.flow.actions/paused?] true))
      :resume (do (flow/resume (:seon.flow/graph (started registry agent-id)))
                  (swap! registry assoc-in [:seon.agent/started agent-id :seon.flow.actions/paused?] false))
      ;; a stop lets the current pass finish; only a cancel interrupts it
      :stop (stop-flow! {:seon.agent/id agent-id :seon.agent/routing registry :seon.agent/finish? true})
      :cancel (stop-flow! {:seon.agent/id agent-id :seon.agent/routing registry})))
  nil)

(defn- write-problem!
  "Write the problem task a `:problem` action names, by identity (its class and its assignment, else its task), so a
  repeat writes nothing."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.flow.actions/action] :nil]}
  [handle {key :seon.task/assignment h :seon.task/assigned class :seon.flow.actions/class intent :seon.task/plan}]
  (let [created (task/create! {:seon.db/connection (:seon.db/connection handle) :seon.agent/id "root"
                               :seon.task/ns 'seon.task :seon.task/name (str (name class) "-" (or key h))
                               :seon.task/plan intent})]
    (when (:seon.error/at created) (throw (ex-info (:seon.error/message created "The problem task was refused.") created)))
    nil))

(declare apply-lifecycle!)

(defn apply-actions!
  "Apply db<->flow's `actions` in order, through `handle` and its `routing`: `launch-actions`' and `next-actions`'.
  An action that fails stops the rest: its failure is thrown again naming the action
  (`:seon.flow.actions/action`, the cause kept) for db<->flow's one catch (`failed-pass!`)."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing :seon.flow.actions/actions] :nil]}
  [handle routing actions]
  (doseq [{op :seon.flow.actions/op :as action} actions]
    (try (case op
           :attempt (if (:seon.chain/branch action) (write-chain-attempt! handle action) (write-attempt! handle action))
           :start-flow (if (:seon.chain/branch action) (start-chain-attempt! handle action) (start-attempt! handle action))
           :problem (write-problem! handle action)
           (:pause :resume :cancel) (follow! handle routing action)
           ;; an open assignment's :stop follows its intent; an ended one's names its entity
           :stop (if (:db/id action) (apply-lifecycle! handle routing action) (follow! handle routing action))
           (:merge :publish :close :relaunch :delete-branch) (apply-lifecycle! handle routing action))
         ;; a translation: the failure names the action it stopped, so the boundary can end its assignment
         (catch Exception cause
           (throw (ex-info (or (ex-message cause) "db<->flow's action failed.") {:seon.flow.actions/action action} cause)))))
  nil)

(defn- branch-end
  "The end and reason `branch`'s head records on attempt `key`, read from main `database`'s store; {} when the head
  records none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch :seon.task/assignment]
                  [:map [:seon.task.attempt/end {:optional true} :seon.task.attempt/end]
                   [:seon.task.attempt/reason {:optional true} :seon.task.attempt/reason]]]}
  [database branch key]
  (if-let [head (db/branch-database database branch)]
    (try (let [row (db/q '[:find (pull ?a [:seon.task.attempt/end :seon.task.attempt/reason]) . :in $ ?k
                           :where [?a :seon.task/assignment ?k]]
                         head key)]
           (if (keyword? (:seon.task.attempt/end row)) (select-keys row [:seon.task.attempt/end :seon.task.attempt/reason]) {}))
         (finally (source/release! [head])))
    {}))

(defn- kept?
  "Whether `agent-id`'s merged branch stays on main `database`: its agent's cluster keeps ended branches. A
  successor forks main, so no successor holds it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :boolean]}
  [database agent-id]
  (true? (config/agent-cluster-dial database agent-id :seon.config.agent/keep-ended-branches?)))

(defn- publication-due?
  "Whether an assignment's publication is due, from its `row` on main: its merge commit recorded, its merge
  moved declarations or tasks (`:seon.task/merged-declarations`, which `seon.merge/merge!` writes beside its merge
  commit), and no git commit and no publication refusal recorded, whether or not the merge completed its task (a refinement
  merged before its agent ends publishes too). A merge moving no declaration is never due."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{commit :seon.task/merge-commit moved :seon.task/merged-declarations
    git :seon.task/git-commit refused :seon.task/publication-refusal}]
  (boolean (and commit moved (not git) (not refused))))

(defn- branch-admission-end
  "The `:seon.agent/admission-closed-tx` agent `agent-id`'s row holds on `branch`'s head, read from main
  `database`'s store; nil when it holds none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch :seon.agent/id] [:maybe :seon.agent/admission-closed-tx]]}
  [database branch agent-id]
  (when-let [head (db/branch-database database branch)]
    (try (db/q '[:find ?t . :in $ ?id :where [?g :seon.agent/id ?id] [?g :seon.agent/admission-closed-tx ?t]] head agent-id)
         (finally (source/release! [head])))))

(defn- taskless-agents
  "Every agent on main `database` whose branch the store's `branches` holds, is not main's and that no assignment
  names: its end, main's row's else its branch head's, whether `started` runs its graph and, once ended, how
  many declarations its branch holds unmerged."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/store :seon.cluster.registry/branches :seon.flow.actions/started]
                  [:vector :seon.flow.actions/held-agent]]}
  [database store branches started]
  (let [rows (db/q '[:find ?g ?id ?b :in $ ?main
                     :where [?g :seon.agent/id ?id] [?g :seon.agent/branch ?b] [(not= ?b ?main)]
                     (not [_ :seon.task/agent ?g])]
                   database (get-in database [:config :branch]))
        _ (when (map? rows) (throw (ex-info (:seon.error/message rows "db<->flow could not read the taskless agents.") rows)))]
    (into [] (keep (fn [[g id b]]
                     (when (contains? branches b)
                       (let [end (or (:seon.agent/admission-closed-tx (db/pull database [:seon.agent/admission-closed-tx] g))
                                     (branch-admission-end database b id))]
                         (cond-> {:db/id g :seon.agent/id id :seon.task/branch b
                                  :seon.flow.actions/started? (contains? started id)}
                           end (assoc :seon.agent/admission-closed-tx end
                                      :seon.flow.actions/unmerged (source/unmerged-count database store branches b)))))))
          (sort-by second rows))))

(defn- allowance-left
  "How many relaunches task `h` has left on main `database`: `:seon.config.run/relaunches` of agent `agent-id`'s
  cluster less the task's relaunched attempts; an unreadable dial throws."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle :seon.agent/id] :seon.flow.actions/allowance-left]}
  [database h agent-id]
  (let [cluster (config/agent-cluster database agent-id)
        relaunches (when (string? cluster) (:seon.config.run/relaunches (config/effective database cluster)))
        used (db/q '[:find (count ?b) . :in $ ?h :where [?b :seon.task/assigned ?h] [?b :seon.task.attempt/relaunch _]]
                   database h)]
    (when-not (and (int? relaunches) (or (nil? used) (int? used)))
      (throw (ex-info "db<->flow could not read the relaunch allowance."
                      {:seon.error/message "db<->flow could not read the relaunch allowance."
                       :seon.config.run/relaunches relaunches :seon.agent/id agent-id})))
    (max 0 (- relaunches (or used 0)))))

(defn lifecycle-facts
  "What db<->flow reads before it derives the ended assignments' and taskless agents' actions (`next-actions`).
  Every assignment on main `database` whose branch the store's `branches` holds (db<->flow's one read of them,
  the basis every head and comparison below reads, so a branch deleted since still answers what it held): its end on main, else on its branch head
  (one branch read per assignment main holds no end for or has not merged), its branch head commit, whether main merged it, whether
  `registry` runs its graph (its exit is its absence), whether its publication is due, whether main records a
  refusal its merge returned, ended to merge, whether its branch stays once merged (`kept?`), and, exited with an
  end main has not decided that does not simply merge, whether its branch holds work main lacks and how many
  relaunches its task has left (`allowance-left`); then every taskless agent's end and unmerged change
  (`taskless-agents`), so an ended one holding an unmerged change is listed here for root."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/store :seon.cluster.registry/branches :seon.flow.actions/registry]
                  :seon.flow.actions/held]}
  [database store branches {started :seon.flow.actions/started}]
  (let [rows (db/q '[:find ?a ?k ?h ?b ?id
                     :where [?a :seon.task/assignment ?k] [?a :seon.task/assigned ?h] [?a :seon.task/branch ?b]
                     [?a :seon.task/agent ?g] [?g :seon.agent/id ?id]]
                   database)
        _ (when (map? rows) (throw (ex-info (:seon.error/message rows "db<->flow could not read the assignments.") rows)))]
    (into (into [] (keep (fn [[a k h b id]]
                     (when (contains? branches b)
                       (let [{main-end :seon.task.attempt/end :as row}
                             ;; an assignment holding none of these yet pulls as nil
                             (or (db/pull database [:seon.task.attempt/end :seon.task/outcome
                                                    :seon.task/merge-commit :seon.task/merged-declarations
                                                    :seon.task/git-commit :seon.task/refusal :seon.task/publication-refusal] a)
                                 {})
                             head (registry/branch-commit-id {:seon.store/store store :seon.store/branch b
                                                              :seon.cluster.registry/branches branches})
                             ;; main's history records the merge of this head (`seon.merge/merged?`)
                             merged? (and (uuid? head) (merge/merged? database head))
                             ;; the branch is read while it holds something unmerged
                             {on-branch :seon.task.attempt/end reason :seon.task.attempt/reason}
                             (when (or (nil? main-end) (not merged?)) (branch-end database b k))
                             ;; a cancelled task's attempt holding no end of its own is closed :cancelled
                             end (or main-end on-branch
                                     (when (= :cancelled (task/execution database h))
                                       :cancelled))
                             kind (some-> end task/end-kind)
                             started? (contains? started id)
                             refused? (boolean (and (:seon.task/refusal row) (not= :merged (:seon.task/outcome row))))
                             ;; work and allowance decide only an exited end main has not decided that does not simply merge
                             judged? (and end (nil? main-end) (not started?) (or refused? (#{:relaunch :close} kind)))
                             publish? (publication-due? row)]
                         (when (uuid? head)
                           (cond-> {:db/id a :seon.task/assignment k :seon.task/assigned h :seon.task/branch b
                                    :seon.agent/id id :seon.flow.actions/head head
                                    :seon.flow.actions/started? started? :seon.flow.actions/exited? (not started?)
                                    :seon.flow.actions/merged? merged?
                                    :seon.flow.actions/refused? refused?
                                    :seon.flow.actions/work-left? (boolean (and judged? (pos-int? (source/unmerged-count database store branches b))))
                                    :seon.flow.actions/allowance-left (if judged? (allowance-left database h id) 0)
                                    :seon.flow.actions/kept? (boolean (and (= :merge kind) (kept? database id)))}
                             end (assoc :seon.task.attempt/end end)
                             reason (assoc :seon.task.attempt/reason reason)
                             refused? (assoc :seon.task/refusal (:seon.task/refusal row))
                             publish? (assoc :seon.flow.actions/publish? true)
                             main-end (assoc :seon.flow.actions/main-end main-end)))))))
          (sort-by second rows))
          (taskless-agents database store branches started))))

(defn- unfinished-intent
  "Root's problem text for ended assignment `row`, closed with its work unfinished: no relaunch left, or an end
  that only root decides."
  {:malli/schema [:=> [:cat :seon.flow.actions/held-assignment] :seon.task/plan]}
  [{key :seon.task/assignment h :seon.task/assigned branch :seon.task/branch end :seon.task.attempt/end
    reason :seon.task.attempt/reason work-left? :seon.flow.actions/work-left? refusal :seon.task/refusal}]
  (str "Attempt " key " of task " h " ended " (name end) (when reason (str ": " reason))
       (when refusal (str ", and its merge was refused " refusal)) ". Nothing was merged to main;"
       " its branch " (name branch) " is kept. "
       (if work-left? "It holds declarations main lacks, and no relaunch is left" "Its program is unchanged from main")
       ": close it with (my.root/close! {:seon.task/assignment \"" key "\" :seon.task/reason why})."))

(defn assignment-actions
  "The actions that carry ended assignment `row` through its lifecycle, in order, from its end's kind
  (`seon.task/end-kind`) and the observations db<->flow acquired (`lifecycle-facts`); an assignment not ended only
  publishes what root merged of it. An ended graph that has not exited only stops. After exit, with main's end undecided: a merge that returned a refusal, or a :capped or
  :stalled end, relaunches from its end commit while its branch holds work and its task has allowance left, else
  it closes with root's `:unfinished-task` problem, as a :given-back or :error end always does; a :cancelled end
  closes with nothing for root; a :done or :sent-back end merges (a send-back's merge carries its target's
  reopening, `seon.task/ready-rules`' `leaf-done`). Merged work publishes, then its branch, unless kept, is
  deleted."
  {:malli/schema [:=> [:cat :seon.flow.actions/held-assignment] :seon.flow.actions/actions]}
  [{end :seon.task.attempt/end main-end :seon.flow.actions/main-end merged? :seon.flow.actions/merged?
    started? :seon.flow.actions/started? exited? :seon.flow.actions/exited?
    kept? :seon.flow.actions/kept? publish? :seon.flow.actions/publish? allowance :seon.flow.actions/allowance-left
    work-left? :seon.flow.actions/work-left? refused? :seon.flow.actions/refused? :as row}]
  (let [action (fn [op extra] (into (assoc (select-keys row [:db/id :seon.task/assigned :seon.task/assignment :seon.agent/id
                                                              :seon.task/branch :seon.task.attempt/end])
                                           :seon.flow.actions/op op)
                                    extra))
        kind (some-> end task/end-kind)
        undecided? (nil? main-end)
        continue? (and undecided? (or refused? (= :relaunch kind)))
        merge? (and (= :merge kind) (not refused?) (or undecided? (= :merge (task/end-kind main-end))))]
    (cond
      ;; the graph stops first, so what follows reads the head its last pass left
      (and end (not exited?)) (if started? [(action :stop {})] [])
      (and continue? work-left? (pos? allowance))
      [(action :relaunch {:seon.task.attempt/relaunch
                          (str "Ended " (name end) (when refused? " and its merge was refused")
                               " with work main lacks: relaunched from its end commit, "
                               (dec allowance) " relaunches left.")})]
      ;; root's problem first: once main holds the end, no pass derives it again
      (or continue? (and undecided? (= :close kind)))
      [(action :problem {:seon.flow.actions/class :unfinished-task :seon.task/plan (unfinished-intent row)})
       (action :close {})]
      :else
      (cond-> []
        (and merge? (not merged?)) (conj (action :merge {}))
        (and undecided? (= :cancel kind)) (conj (action :close {}))
        ;; publication reads main alone: the merge commit, then the checkout
        publish? (conj (action :publish {}))
        ;; deletion reads main after the merge and its publication: their own writes wake the next pass
        (and merge? merged? (not kept?) (not publish?)) (conj (action :delete-branch {}))))))

(defn- taskless-actions
  "The actions that carry taskless agent `row` through its end, in order (`next-actions`): ended, its graph
  stops (`:stop`), then, nothing unmerged, its branch is deleted (`:delete-branch`); ended with an unmerged
  change, or not ended, none."
  {:malli/schema [:=> [:cat :seon.flow.actions/held-agent] :seon.flow.actions/actions]}
  [{end :seon.agent/admission-closed-tx started? :seon.flow.actions/started? unmerged :seon.flow.actions/unmerged :as row}]
  (let [action #(assoc (select-keys row [:db/id :seon.agent/id :seon.task/branch]) :seon.flow.actions/op %)]
    (cond-> []
      (and end started?) (conj (action :stop))
      (and end (zero? unmerged)) (conj (action :delete-branch)))))

(defn next-actions
  "The actions that carry each ended assignment and taskless agent in `held` through its lifecycle, as a vector,
  in order. Derived from the facts alone, never from a marker, so a pass after any crash derives what is left:
  each ended assignment's actions come from its one owner (`assignment-actions`).
  A taskless agent (a row naming no `:seon.task/assignment`) follows the same rule with merged read as nothing
  unmerged (`taskless-actions`).
  Pure; each action is idempotent by identity, by head = merge base or by the git commit main records."
  {:malli/schema [:=> [:cat :seon.flow.actions/held] :seon.flow.actions/actions]}
  [held]
  (into [] (mapcat #(if (:seon.task/assignment %) (assignment-actions %) (taskless-actions %))) held))

(defn- failed-pass!
  "db<->flow's one catch, the Flow proc's boundary, for its pass's thrown `failure`: Seon's own fault, never the
  candidate's (a merge's returned refusal is recorded by `:merge` itself, `apply-lifecycle!`). The assignment a
  failed `:attempt`, `:start-flow` or `:merge` names ends `:error` on main with the failure's message
  as its reason, so no later pass derives that action again (`seon.task/ready` reads the end as terminal), and
  root gets its `:unfinished-task` problem; the failure is recorded as db<->flow's fault."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.error/throwable] :nil]}
  [handle failure]
  (let [{key :seon.task/assignment h :seon.task/assigned branch :seon.task/branch op :seon.flow.actions/op :as action}
        (:seon.flow.actions/action (ex-data failure))
        cause (or (when action (ex-cause failure)) failure)
        connection (:seon.db/connection handle)
        reason (or (not-empty (ex-message cause)) "db<->flow's action failed.")
        owned? (and key (#{:attempt :start-flow :merge} op)
                    (int? (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment key]))))]
    (when owned?
      (write! connection [[:db/add [:seon.task/assignment key] :seon.task.attempt/end :error]
                          [:db/add [:seon.task/assignment key] :seon.task.attempt/reason reason]])
      (write-problem! handle {:seon.flow.actions/op :problem :seon.task/assignment key :seon.task/assigned h
                              :seon.flow.actions/class :unfinished-task
                              :seon.task/plan (str "Attempt " key " of task " h " failed in db<->flow's " (name op) ": "
                                                     reason " It ended :error; its branch " (name branch) " is kept: close it with"
                                                     " (my.root/close! {:seon.task/assignment \"" key "\" :seon.task/reason why}).")}))
    (fault/fault! (env/of handle) cause {:seon.error/layer :seon.flow/plan :seon.error/operation `db<->flow
                                         :seon.db.process/id (:seon.db.process/id handle)})
    nil))

(defn- close!
  "Write a `:close` or `:relaunch` action's end on main through `connection` and merge nothing else: ONE
  transaction with the assignment's end facts its branch head holds and the action's relaunch beside them
  (`seon.task/relaunched` then forks its next attempt at that end commit), carrying the branch and commit they
  came from as transaction metadata. A `:cancelled` end is db<->flow's own: it writes the end, its reason and the
  `:closed` outcome, and root has nothing to merge or decide."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.flow.actions/action] :nil]}
  [connection {a :db/id key :seon.task/assignment branch :seon.task/branch end :seon.task.attempt/end
               relaunch :seon.task.attempt/relaunch}]
  (let [head (db/branch-database (db/db connection) branch)]
    (try
      (let [facts (cond-> (if (= :cancelled end)
                            {:seon.task.attempt/end :cancelled :seon.task.attempt/reason "The task's intent is :cancelled."
                             :seon.task/outcome :closed}
                            (dissoc (db/q '[:find (pull ?a [:seon.task.attempt/end :seon.task.attempt/commit
                                                            :seon.task.attempt/reason]) .
                                            :in $ ?k :where [?a :seon.task/assignment ?k]]
                                          head key)
                                    :db/id))
                    relaunch (assoc :seon.task.attempt/relaunch relaunch))]
        (write! connection {:tx-data (mapv (fn [[attribute value]] [:db/add a attribute value]) (sort facts))
                            :tx-meta {:seon.squash/source-branch branch :seon.squash/source-commit (db/commit-id head)}}))
      (finally (source/release! [head])))
    nil))

(defn- release-branch!
  "Release the connections `routing` holds on `branch` after its graphs stopped; the branch stays."
  {:malli/schema [:=> [:cat :seon.agent/routing :seon.store/branch] :nil]}
  [routing branch]
  (when-let [child (get-in @routing [:seon.agent/children branch])]
    (doseq [execution (vals (:seon.agent/contexts @child))
            :when (:seon.agent/owns-connection? execution)]
      (release-context! execution)))
  nil)

(defn- apply-lifecycle!
  "Apply one of `next-actions`' `action`s through cluster `handle` and its `routing`."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing :seon.flow.actions/action] :nil]}
  [handle routing {op :seon.flow.actions/op a :db/id agent-id :seon.agent/id branch :seon.task/branch :as action}]
  (let [connection (:seon.db/connection handle)]
    (case op
      ;; the checkout the merged files are proven against before acceptance, as publish! will write them
      ;; a returned refusal is the candidate's own: recorded on the assignment, the next derivation relaunches or
      ;; closes it (`assignment-actions`); only a thrown failure is Seon's (`failed-pass!`)
      :merge (let [root (merge/checkout (db/db connection) (:seon.cluster/name handle))
                   merged (db/with-branch {:seon.db/connection connection}
                                          #(apply merge/merge! (assoc handle :seon.store/store (handle-store handle))
                                                          (:seon.task/assignment action)
                                                          (when (string? root) [root])))]
               (when (:seon.error/at merged)
                 ;; an assignment holds no merge step: the merge branch its refusal kept is retired here
                 (when-let [kept (:seon.source/candidate merged)]
                   (registry/retire-branch! {:seon.store/store (handle-store handle) :seon.store/branch kept}))
                 (write! connection [[:db/add a :seon.task/refusal (:seon.error/type merged)]])))
      ;; a refused publication is recorded on the assignment by publish! itself; the throw records the fault
      :publish (let [published (merge/publish! handle (:seon.task/assignment action))]
                 (when (:seon.error/at published)
                   (throw (ex-info (:seon.error/message published "The publication was refused.") published))))
      (:close :relaunch) (close! connection action)
      ;; the stop lets the pass that wrote the end finish, then joins it
      :stop (do (when-let [registry (entry-registry routing agent-id)]
                  (stop-flow! {:seon.agent/id agent-id :seon.agent/routing registry :seon.agent/finish? true}))
                (release-branch! routing branch))
      :delete-branch (when-let [refused (task/delete-branch! {:seon.db/connection connection :seon.store/store (handle-store handle)
                                                              :seon.agent/routing routing :seon.env/environment (env/of handle)}
                                                             branch)]
                       (throw (ex-info (:seon.error/message refused "The merged assignment's branch was not deleted.") refused))))
    nil))

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

(defn- run-pass!
  "One db<->flow pass over cluster `handle` and its `routing`: the ended assignments' lifecycle first
  (`next-actions`: stopped, then, derived again after the stop so it reads their exit, merged to main, then,
  derived again after the merge so it reads its outcome, published, relaunched or closed; deleted), so the plan reads them ended (P2); then the attempts are written and Flow follows them (R1); then the agents with
  work that no attempt names start. The first failure stops the pass and reaches db<->flow's one catch."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/routing] :nil]}
  [handle routing]
  (let [connection (:seon.db/connection handle)]
    ;; a finished chain merges first, so this pass's lifecycle publishes it and retires its branch
    (merge-chains! handle)
    (finish-chains! handle routing)
    (let [lifecycle! #(let [actions (next-actions (lifecycle-facts @connection (handle-store handle) (registry/branches (handle-store handle))
                                                                    (registry-facts routing)))]
                        (apply-actions! handle routing actions)
                        actions)]
      ;; a stop and a merge each change what the derivation reads (the graph's exit; the merge's outcome or its
      ;; refusal), so what follows them is derived in this pass and the plan never reads an ended attempt as open
      (let [applied? (fn [op actions] (some #(= op (:seon.flow.actions/op %)) actions))
            derived (lifecycle!)
            derived (if (applied? :stop derived) (lifecycle!) derived)]
        (when (applied? :merge derived) (lifecycle!))))
    ;; a chain advances on its branch before the plan reads it, so the plan starts its next task in this pass;
    ;; every other end bails to root, so the plan never starts its task again until root runs it
    (advance-chains! handle)
    (bail-chains! handle)
    (let [db @connection
          ;; the branches read after the lifecycle: a chain branch this pass deleted is never read
          facts (launch-facts db (registry/branches (handle-store handle)))
          max-running (:seon.config.agent/max-running (config/effective db (:seon.cluster/name handle)))]
      (when (or (:seon.error/at facts) (not (int? max-running)))
        (throw (ex-info "db<->flow could not read what to plan."
                        (if (:seon.error/at facts) facts {:seon.config.agent/max-running max-running}))))
      (apply-actions! handle routing (launch-actions facts (registry-facts routing) max-running)))
    ;; db<->flow starts the agents whose custody is its own execution's branch: a branch inherits rows that
    ;; name its parent's, and those agents belong to the parent's db<->flow. A row naming no branch still
    ;; reaches `start-flow!`, whose acquisition refuses it by name.
    (let [db @connection
          own-branch (get-in db [:config :branch])
          agents (into [] (keep (fn [[id branch]] (when (#{own-branch ::none} branch) id)))
                       (db/q '[:find ?id ?branch
                               :where [?agent :seon.agent/id ?id]
                               [(get-else $ ?agent :seon.agent/branch ::none) ?branch]]
                             db))
          unstarted (remove #(contains? (:seon.agent/started @routing) %) agents)]
      (doseq [agent-id (sort (agents-to-start db (set unstarted)))]
        (start-flow! {:seon.turn.loop/cluster handle :seon.agent/id agent-id :seon.agent/routing routing})))
    nil))

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
     (let [handle (:seon.turn.loop/cluster state)]
       (try (run-pass! handle (:seon.agent/routing state))
            (catch Exception failure (failed-pass! handle failure)))
       [state nil]))))
