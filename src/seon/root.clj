(ns seon.root
  "Root: the control process (session F2, the session design papers in git history; proto/flow f2-final
  proto.control's api, reader, boot and API). Boots the database
  (Datahike, E4a index), forks the single-threaded fork server before any thread exists,
  then runs ONE root graph of four procs:
    :api          :io     where requests come in: owns the callers' reply promises and the agents'
                          graphs; routes every request and answer as data
    :writer       :mixed  the only d/transact, d/branch! and merge caller, with group
                          commit (drain the in-port, one commit per branch, one flush)
    :fork-server  :io     the fork server's command pipe, its events, its restart
    :reconciler   :io     main's commits through a bounded buffer (every commit's datoms; the hook a
                          reconciler and the dashboard plug into)
  and one proc per agent (`agent-proc`, added and removed with flow/update!):
  it owns root's end of the agent's socket and its process, and passes Flow's pause, resume and stop on to the
  agent's own Flow graph (seon.repl/serve!), whose procs run the same step-fn contract.

  Ordering is dataflow: every proc starts and parks on its inputs. Shutdown flows too:
  the api closes, agents drain and exit, the writer's in-port closes, the writer
  drains and flushes last, the fork server is reaped, then flow/stop.

  Agents transact facts on their own branch; root reacts: the writer applies them, and
  only a merge task's octopus merge (the reconciler's, seon.writer) advances main."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.query.execute] [clojure.main] [clojure.string :as str] [seon.db :as pdb] [seon.ctx.wire :as wire] [seon.lib :as lib]
            [seon.sock :as sock] [jolt.async.remote :as remote] [konserve.jolt.fasl :as codec] [seon.fx :as fx] [seon.fork-server :as fs] [seon.agent-proc :as ap]
            [seon.writer :as writer] [seon.problems :as problems] [seon.prove] [seon.cron] [seon.vector :as vector] [seon.source :as source] [seon.reconcile :as reconcile] [seon.system :as system] [seon.program :as program] [seon.step :as step]
            [seon.config :as cfg] [seon.contract :as contract] [seon.print :as print] [seon.task :as task] [seon.template :as tpl] [seon.render :as render] [seon.doc :as doc] [seon.error :as err] [seon.agent :as agent] [konserve.jolt.lmdb :as klmdb] [datahike.versioning] [clojure.java.shell]
            [clojure.core.async :as a]
            ;; the agent side, loaded here so the fork server and every agent inherit it compiled
            [seon.turn] [my.fx] [my.task] [my.user] [my.turn] [my.render] [my.branch] [my.doc] [my.test] [clojure.core.async.flow :as flow] [clojure.test]
            [taoensso.trove :as trove]))


;; ====================================================================================
;; The api: where requests come in
;; ====================================================================================

;; ---- problems root observes (session D2): recorded as seon.error facts on main -----------

(defn- gone
  "The one error map of a request no live agent can take (agent id, or nil)."
  {:malli/schema [:=> [:cat [:maybe :keyword] :string] :seon.root/no-agent]}
  [id why] (contract/error :seon.root/no-agent #'seon.root/gone why {:agent id}))

(defn- ended
  "The one error map of a request to agent id after it ended: root stopped it (a kill, its
  run's end) or it died, as root's view records (:ended-ms). Not gone: the agent was."
  {:malli/schema [:=> [:cat :keyword :string] :seon.root/ended]}
  [id why] (contract/error :seon.root/ended #'seon.root/ended why {:agent id}))

(defn- absent
  "Why agent id, whose entry in root's view is a (nil: root never had it), takes no request
  now: it ended (ended: the view records its :ended-ms), or root never had it (gone). The one
  answer the api, request and prove! give for an agent that is not there."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :keyword]] [:or :seon.root/ended :seon.root/no-agent]]}
  [a id]
  (if (and id (:ended-ms a))
    (ended id (str "agent " (name id) " ended"))
    (gone id (str "no live agent " id))))

(defn- refused
  "Root's API refusing a request, as the one error map; members describe the refused answer."
  {:malli/schema [:=> [:cat :qualified-symbol :string :map] :seon.root/refused]}
  [op why members] (contract/error :seon.root/refused op why members))

(defn- problem
  "Fold occurrence o into the api's recorded-error fold (seon.error/fold): one hash; the
  write follows once per window (flush-problems)."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.error/occurrence] :seon.root/api-state]}
  [st o] (-> st (update ::errors err/fold o) (assoc ::errors-dirty true)))

(defn- flush-problems
  "The signatures folded and not yet written, written to main as one transaction, when none
  is in flight (its answer releases the next); a repeat of a written signature writes
  nothing (seon.error/flush-tx)."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile] :seon.root/api-state]}
  [st o]
  (if (and (::errors-dirty st) (not (::errors-flight st)) (not (:writer-closed st)) (not (:closing st)))
    (let [[f tx] (err/flush-tx (::errors st) (some-> (:conn st) deref))]
      (if (seq tx)
        (do (step/out! o :to-writer {:op :tx :branch :db :tx-data tx :dest {:errors-flushed true}})
            (assoc st ::errors f ::errors-dirty false ::errors-flight true))
        (assoc st ::errors f ::errors-dirty false)))
    st))

(def ^:private observed-keys
  "The members of an agent's view entry recorded on its entity (seon.task/agent-tx)."
  [:status :pid :mem-mb :running :branch :forked-ms :ended-ms :stalled])

(defn- observed
  "What root records of agent entry a: its observed-keys, less :branch for an agent started on a task: its :branch is
  the one its start wrote (seon.task/start-tx; a second writer under the store's name for the branch would change
  what the index reads of its environment). Its task is named by its record (:seon.agent/task), never recorded again
  from the view."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [a] (cond-> (select-keys a observed-keys) (:task a) (dissoc :branch)))

(defn- record-agents
  "What root observed of its agents in this step, written to main as one transaction: an
  agent's facts (seon.task/agent-tx) when a member it records changed since before (its view
  entries at the step's start). Nothing changed, nothing written. Memory alone is no change:
  it moves with every allocation, and main moved under each read of a working agent (five
  commits in three reads, each merged into every branch and redrawn); it is written with the
  agent's next other change."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile [:maybe :map]] :seon.root/api-state]}
  [st o before]
  (let [now (get-in st [:view :agents])
        changed (for [[id a] now
                      :let [b (get before id)]
                      :when (and (not (:replaced a)) (not (identical? a b)) (not= (dissoc (observed a) :mem-mb) (dissoc (observed b) :mem-mb)))]
                  id)]
    (if (and (seq changed) (not (:writer-closed st)))
      (do
        (step/out! o :to-writer {:op :tx :branch :db :tx-data (into [] (mapcat #(task/agent-tx % (observed (get now %)))) changed)
                                 :dest {:agent-facts (vec changed)}})
        st)
      st)))

(defn- proc-sym
  "The step-fn of root proc pid, as its declaration names it (an agent's proc for an
  agent id)."
  {:malli/schema [:=> [:cat :seon.root/api-state [:maybe :keyword]] :qualified-symbol]}
  [st pid]
  (or (get-in st [:system :parts [:proc pid] 0 :proc])
      (when (get-in st [:view :agents pid]) 'seon.agent-proc/agent-proc)
      'clojure.core.async.flow/process))

(defn- exit-text {:malli/schema [:=> [:cat [:maybe :seon.lib/exit-status]] :string]}
  [x] (cond (:signal x) (str ": signal " (:signal x)) x (str ": exit " (:exit x)) :else ""))

(declare deliver-rid answer-taken)

(def ^:private not-restored
  "What a spawn at a commit (:commit in its opts) does not restore, as the spawn's answer says it: only a session's definitions are in the store.
  See docs/storage.md#compiled-code."
  ["the system's own code (src): the binary is main's"
   "main's changes since the session's base"
   "host state: atoms, open files, sockets, fibers"])

(declare answer-spawn insert-evals start-spawn no-room binary-program binary-current kill! prove-tree! stop!)

(defn- record-death
  "Agent aid's death, folded, and answered to the requests it died in (inflight): its died
  map completed with the exit status the fork server reaped (nil: never known, for a clone
  or an orphan, which are not the server's children)."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :keyword [:maybe :seon.lib/exit-status] :map [:sequential :int]] :seon.root/api-state]}
  [st o aid exit died inflight]
  (let [e (assoc died :exit exit :seon.error/message (str "agent " (name aid) (if (:ended died) " closed its socket and was ended by root" " died") (exit-text exit)))
        st (problem st (err/occurrence e :agent {:agent aid :task (get-in st [:view :agents aid :task]) :form (get-in e [:last-op :form])}
                                       (System/currentTimeMillis)))]
    ;; a spawn whose agent died before its hello is answered that death
    (reduce (fn [st r] (deliver-rid st o r e)) (answer-spawn st o aid e) inflight)))

(defn- reply!
  "Answer a caller: an outside caller's promise is delivered; a proc's reply route
  ({:route [pid in] :tag t}: a proc is a caller too, request/reply as data) gets
  {:tag t :r r} as an output of this step (seon.step/out! on o). Answers st."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile [:or :seon.host/promise :map] :any] :seon.root/api-state]}
  [st o p r]
  (if (map? p)
    (do (step/out! o (:route p) {:tag (:tag p) :r r}) st)
    (do (deliver p r) st)))

(defn- answer-spawn
  "Answer the caller of agent id's spawn (held under ::spawning since its :spawn) with r: {:id}
  at its hello, or the end it met first. Answers st."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :seon.agent/id :any] :seon.root/api-state]}
  [st o id r]
  (if-let [p (get-in st [::spawning id])]
    (-> (reply! st o p r) (update ::spawning dissoc id) (update ::restored dissoc id))
    st))

(defn- deliver-rid
  "Answer the caller waiting on rid; answers st."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :int :any] :seon.root/api-state]}
  [st o rid r]
  (let [[st' {:keys [p]}] (step/answered st rid)]
    (if p (-> (reply! st' o p r) (update :writes disj rid)) st)))

(defn- view! {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/fn [:* :any]] :seon.root/api-state]}
  [st f & args] (apply update st :view f args))

(def gone-statuses "Agent statuses of a process gone (:seon.agent/status)." (:seon.status/gone (contract/properties :seon.agent/status)))
(def ^:private stopped-statuses (:seon.status/stopped (contract/properties :seon.agent/status)))

(defn- failed
  "What went wrong in agent id's view change m (a its entry before), as the one error map,
  or nil: a spawn or fork refused (:error, its error map); a red test run of an agent
  with no task (a task's run records its own at its end, seon.problems/report)."
  {:malli/schema [:=> [:cat :seon.agent/id :map :map] [:maybe :seon.error/base]]}
  [id a m]
  (let [{:keys [fail error reports]} (:tests m)]
    (cond
      (:error m) (:error m)
      (:task a) nil
      (pos? (+ (or fail 0) (or error 0)))
      (contract/error :seon.agent/failed #'seon.root/failed
                      (str "tests red" (some->> (first (remove #(= :pass (:type %)) reports)) :var (str ": "))) {:agent id}))))

(defn- eval-head
  "What root's view keeps of an eval's response r: its handle (the form that reads the value, on the
  agent), the printed head the page shows (:text: the printer's own cut), its cost and, for an error,
  its type and message. Never a value, an :out or :err, or an error's :data: the view is a
  reference, and the record on the agent's branch holds the rest (docs/machine.md#roots-heap)."
  {:malli/schema [:=> [:cat :map] :map]}
  [r]
  (cond-> (select-keys r [:handle :text :forms :ms :ticks :bytes])
    (:ex r) (assoc :ex (select-keys (:ex r) [:seon.error/type :seon.error/message]))))

(defn- set-agent
  "Merge m into agent id's view entry, recording what went wrong in it (`failed`). A stopped
  agent stays stopped: :killed (a kill) or :ended (root stopped it because its run ended)."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :seon.agent/id :map] :seon.root/api-state]}
  [st o id m]
  (if-let [a (get-in st [:view :agents id])]
    (let [;; its hello names what of the program it was forked with did not load: the agent runs, and that is its first fact and its spawn's answer
          swp (when (and (:ready m) (seq (:load-errors m)))
                (contract/error :seon.agent/started-without-program #'seon.root/set-agent
                                (str "this agent started without part of its program: " (str/join ", " (distinct (map :ns (:load-errors m)))) " did not load")
                                {:errors (vec (:load-errors m))}))
          a' (cond-> (merge a (dissoc m :ready :evals-add))
               (and (:ready m) (= :spawning (:status a))) (assoc :status :idle)
               ;; a stall ends with the process
               (and (:stalled a) (gone-statuses (:status m))) (assoc :stalled nil)
               (:evals-add m) (update :evals #(let [n (cfg/value :seon.config.root/view-window)] (reduce (fn [v x] (lib/push-window v (eval-head x) n)) (or % []) (:evals-add m)))))
          e (failed id a m)]
      ;; a gone process stays gone: a late live status (a request its proc took after its end) is not news
      (cond-> (assoc-in st [:view :agents id] (if (and (gone-statuses (:status a)) (not (gone-statuses (:status a'))))
                                                (assoc a' :status (:status a) :running nil)
                                                a'))
        e (problem (err/occurrence e :agent {:agent id :task (:task a)} (System/currentTimeMillis)))
        ;; its hello: the spawn is answered now, the agent ready
        ;; its hello answers the spawn, whatever a request sent before it made its status
        (:ready m) (answer-spawn o id (cond-> {:id id} swp (assoc :started-without-program swp)
                                                              (get-in st [::restored id]) (assoc :not-restored not-restored)))
        ;; and it is the agent's first fact: a message from Seon (the system entity) to it, in its conversation as any message is, written
        ;; as a plain write (it wakes nothing): the agent's page and context show it from the pull they already make
        ;; once per standing set of errors: its id is the agent's and the errors', so a resume or a second hello that meets the same
        ;; errors finds it on main and writes nothing
        swp ((fn [st] (let [mid (lib/id (lib/digest (sedn/encode [:started-without-program (name id) (vec (sort-by (juxt (comp name :ns) :message) (:errors swp)))])))]
                        (when-not (pdb/eid @(:conn st) :seon.message/id mid)
                          (step/out! o :to-writer {:op :tx :branch :db
                                                :tx-data (task/message-tx mid {:seon.root/id :root} [(name id)]
                                                                          (str (:seon.error/message swp) "; each error: "
                                                                               (str/join "; " (map (fn [{:keys [ns message]}] (str ns ": " message)) (:errors swp))))
                                                                          (System/currentTimeMillis))})))
                       st))
        ;; and the evals given at its spawn are the first things its REPL queue holds, before any request the answer makes
        (:ready m) (insert-evals o id)
        ;; the agents with an eval in flight: the stall check's set (a gone one never)
        (contains? m :running) (update ::busy (fnil (if (and (:running m) (not (gone-statuses (:status a))) (not (gone-statuses (:status a')))) conj disj) #{}) id)))
    st))

(defn stall-state
  "What root's stall check and each agent proc hold now, a hang's evidence (bin/evidence asks it through root's server
  before a run past its bound is ended; it reads, never writes): the api proc's ::stall-armed, ::busy and ::lost (its ping's), and
  each agent proc's :inflight (each request's kind), :fx-out, :stall-seen and :last-frame-ms, read by one Flow ping of
  the graph, with :answered, every proc that answered it. The ping's bound is :seon.config.check/ping-ms: a proc
  mid-step that long answers nothing and is missing from :answered (an agent in ::busy and not there is mid-step), never
  read as nil. See docs/clocks.md#time-decides-the-owners-exceptions."
  {:malli/schema [:=> [:cat :seon.root/sys] :map]}
  [sys]
  (let [states (flow/ping (:graph sys) :timeout-ms (cfg/value :seon.config.check/ping-ms))
        state #(::flow/state (get states %))]
    ;; as the api's ping answers it (its :ping-map-fn): the armed flag (seon.step/timer), the busy set, what a throw lost
    {:root (select-keys (state :api) [::stall-armed ::busy ::lost])
     :agents (into (sorted-map)
                   (for [[pid m] states :let [s (::flow/state m)] :when (contains? s :inflight)]
                     [pid (assoc (select-keys s [:fx-out :stall-seen :last-frame-ms]) :inflight (update-vals (:inflight s) :kind))]))
     :answered (vec (sort (keys states)))}))

(defn- stall-task
  "Root's task to deal with agent id's stall s, when it works a task (seon.template/stall: one per
  agent and task, so a later stall of the same task writes none): written to main, held, worked by
  a Claude session through seon_attach (owner: \"a task that we can pre-fill with a template and we
  can give root\"). With it, its evidence is asked while the process is stuck (seon.fx/stall-evidence,
  the :evidence effect: sample, lsof, ps and its fiber dump, files under the runs directory's stalls/),
  whose answer is written into the task's cause (back-op). A refused write is the agent's problem, as
  its facts' are."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :seon.agent/id :map] :seon.root/api-state]}
  [st o id s]
  (let [{:keys [task pid]} (get-in st [:view :agents id])
        db (some-> (:conn st) deref)]
    (if (and task db (not (:writer-closed st)))
      (let [decl (tpl/stall id task (assoc s :pid pid
                                          ;; the api's open asks and the test service's state when it stalled: where a chain waits
                                          :api {:open (vec (sort (for [[rid {:keys [kind agent]}] (:pending st)] [rid kind agent])))
                                                :prove (::prove st)}))
            tx (when-not (pdb/eid db :seon.task/id (task/task-id (:seon.task/ns decl) (:seon.task/name decl)))
                 (task/create-tx db [decl]))]
        (cond (nil? tx) st
              (contract/error? tx) (problem st (err/occurrence tx :agent {:agent id :task (:task (get-in st [:view :agents id]))} (System/currentTimeMillis)))
              :else (let [tid (task/task-id (:seon.task/ns decl) (:seon.task/name decl))]
                      (step/out! o :to-writer {:op :tx :branch :db :tx-data tx :dest {:agent-facts [id]}})
                      ;; a hang is evidence: captured now, before a restart takes the process
                      (when (and pid (contains? (system/fx-owners (get-in st [:system :procs])) :fx/evidence))
                        (step/out! o [:fx/evidence :ask]
                                   {:req {:pid pid :dir (str (cfg/value :seon.config.root/runs-dir) "/stalls/" (:seon.task/name decl))
                                          :stderr (agent/stderr-path (get-in st [:view :store :agents-dir]) id)}
                                    :prio 0 :dest {:stall-evidence tid :agent id}}))
                      st)))
      st)))

(defn- to-agent
  "Send m to agent id's proc: an output of this step to [id :in] (seon.step/out! on o; the
  resolver routes it, the agent is a proc of this graph). Flow sends a step's outputs once its
  transform returns, so after any update! in it that added the agent."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :seon.agent/id :map] :seon.root/api-state]}
  [st o id m]
  (when (get-in st [:agents id]) (step/out! o [id :in] m))
  st)

(defn- insert-evals
  "Agent id's :evals (spawn!'s opts, held under ::inserting since its :spawn) sent to it as one write, which its REPL runs in order
  before any request the spawn's answer makes (docs/turn.md#an-eval-can-be-inserted). Answers st."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :seon.agent/id] :seon.root/api-state]}
  [st o id]
  (if-let [es (get-in st [::inserting id])]
    (-> st (to-agent o id {:op :write :msg {:op :evals :by "root" :evals es}}) (update ::inserting dissoc id))
    st))

(defn- signed-in-key
  "The :seon.agent/key of the user signed in on db (root's :seon.root/signed-in), who a person's ask and message are from, else op's refusal: no default. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol] [:or :string :seon.root/refused]]}
  [db op]
  (or (:seon.agent/key (:seon.root/signed-in (d/entity db [:seon.root/id :root])))
      (refused op "no user is signed in" {})))


(defn- agent-key-of
  "The key of agent id, whom messages are to: its name when a live agent has it or main records it (its process is not
  needed), else nil. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.agent/id] [:maybe :string]]}
  [st id]
  (when (or (get-in st [:agents id]) (pdb/eid @(:conn st) :seon.agent/key (name id))) (name id)))

(defn- message-write
  "The writer's request for one message: from `from` (a keyword or string: an agent's key, a user's included, its sender by seon.task/sender; :root, root's own, the system: seon.task/system-sender, as the reconciler's stage boundary sends it) to the agents with keys tos, its body: the one message entity
  on main (seon.task/message-tx, sent now), which the writer syncs before it answers dest; each recipient is then woken (back-op: the
  writer's answer is what wakes, never a commit). The one write of a person's message and an agent's send. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :seon.root/api-state :any [:sequential :string] :any :map] :map]}
  [st from tos body dest]
  {:op :tx :branch :db :flush? true :dest (assoc dest :wake (vec tos))
   :tx-data (task/message-tx (lib/unique-id) (if (= "root" (name from)) task/system-sender (task/sender (name from))) tos body (System/currentTimeMillis))})

(defn- deliver-message
  "An agent's send m ({:from :to [id ...] :body}) written as one message entity on main to every agent it names, live or not
  (message-write). An id no agent of this boot has is a problem against the sender, as a refused write is (an agent's send has no
  answer to carry it); the others still get it."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :map] :seon.root/api-state]}
  [st o m]
  (let [keys+ids (for [id (:to m)] [id (agent-key-of st id)])
        ks (vec (keep second keys+ids))]
    (when (seq ks) (step/out! o :to-writer (message-write st (:from m) ks (:body m) {:agent-facts [(:from m)]})))
    (reduce (fn [st [id k]]
              (if k st (problem st (err/occurrence (gone id (str "no agent " id " on main: the send is not written")) :agent {:agent (:from m)} (System/currentTimeMillis)))))
            st keys+ids)))

(defn- spawn-failed
  "Agent id's spawn or fork refused (why, and its cause when another proc refused it): the
  one error map in its view and sent to its proc, which hangs up."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :seon.agent/id :string [:maybe :seon.error/base]] :seon.root/api-state]}
  [st o id why cause]
  (let [e (contract/error :seon.agent/failed #'seon.root/spawn-failed why (cond-> {:agent id} cause (assoc :cause cause)))]
    (-> st (set-agent o id {:error e}) (answer-spawn o id e) (to-agent o id {:op :failed :error e}))))

(def ^:private agent-launcher
  "One launcher for every agent's proc (its step is the Var #'agent-proc)."
  (flow/process #'ap/agent-proc))

(defn- start-agent
  "Register a new agent: its socket (root keeps one end; the other goes to whoever forks
  it), its proc definition (added to the graph by update! at the end of the step). Root's
  end is a jolt.async.remote end (session RC1): its :in is the agent proc's :from, so the
  agent's port is an ordinary channel. Answers [st id agent-end-fd]."
  {:malli/schema [:=> [:cat :seon.root/api-state :map] [:tuple :seon.root/api-state :seon.agent/id :seon.lib/fd]]}
  [st {:keys [branch task cloned-from key]}]
  (let [;; a new agent is a fresh unique id (seon.lib/unique-id); a session's agent that continues keeps its own, its key's
        id (keyword (or key (lib/unique-id)))
        ;; named under root's main, so roots sharing a store never share a branch (seon.db/on-main)
        branch (pdb/on-main (pdb/conn-main (:conn st)) (or branch (keyword (str "agent-" (name id)))))
        [r a] (sock/socketpair)
        ;; fiber pumps: an end costs no thread of its own (root may run a thousand agents)
        in (a/chan (cfg/value :seon.config.agent/inbox-size))
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode} :pump :fiber :in in :encode-error (partial ap/unencodable in)
                            :buf (cfg/value :seon.config.agent/inbox-size)})]
    [(-> st (assoc ::update true)
         (assoc-in [:agents id] {:branch branch})
         (assoc-in [:agent-procs id] {:proc agent-launcher
                                      :args {:id id :end end :branch branch
                                             :to-writer (:to-writer st) :back (:back st) :events (:events st)
                                             :fx-kinds (system/fx-owners (get-in st [:system :procs]))}
                                      :chan-opts {:in {:buf-or-n (cfg/value :seon.config.agent/inbox-size)}}})
         (assoc-in [:view :agents id] (cond-> {:branch branch :status :spawning :task task :pid nil :boot (:boot st)
                                               :forked-ms nil :mem-mb nil :evals [] :tests nil}
                                        cloned-from (assoc :cloned-from cloned-from))))
     id a]))

(defn- end-agent
  "The agent's process is gone and its agent proc saw its last frame: its proc leaves the
  graph (update! at the end of the step) and its branch's connection is released."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :map] :seon.root/api-state]}
  [st o {:keys [agent rid pid inflight]}]
  (let [;; every request to it the api holds: the proc's in flight, and one sent in the step it ended
        ;; that never reached the proc or reached it after its end (each answered once: deliver-rid of an
        ;; answered rid is nothing); a :prove names the agent but the test service answers it
        inflight (into (vec inflight) (remove (set (conj inflight rid)))
                       (sort (keep (fn [[r {a :agent k :kind}]] (when (and (= a agent) (not= :prove k)) r)) (:pending st))))
        {:keys [branch]} (get-in st [:agents agent])
        v (get-in st [:view :agents agent])
        ;; stopped by root: a kill, or the end of its run (:ended); else it died
        stop (stopped-statuses (:status v))
        ;; neither stopped by root nor killed: a death, with what root saw of it (session D2)
        died (when-not (or rid stop)
               (contract/error :seon.agent/died #'seon.root/end-agent (str "agent " (name agent) " died" (exit-text (:exit v)))
                               {:agent agent :pid (or pid (:pid v)) :exit (:exit v)
                                :stderr (some-> (agent/stderr-path (get-in st [:view :store :agents-dir]) agent) (lib/tail-bytes (cfg/value :seon.config.root/death-stderr-bytes)))
                                :last-op (some-> (:running v) (select-keys [:form :since-ms]) (assoc :kind :eval))}))
        e (or died (ended agent (str "agent " (name agent) " ended")))]
    (step/out! o :events (step/event {:type (or stop :dead) :agent agent}))
    (let [st (cond
               (nil? died) (reduce (fn [st r] (deliver-rid st o r e)) (answer-spawn st o agent e) inflight)
               ;; the exit status is known (reaped first), or never will be (a clone, an
               ;; orphan of a server that went down, a process never forked): now
               (or (:exit v) (:cloned-from v) (:orphan v) (nil? (or pid (:pid v)))) (record-death st o agent (:exit v) died inflight)
               ;; else when the fork server reaps it (:exit): the requests it died in are
               ;; answered then, with the status that names the death. Its end closed, so it can never answer root
               ;; again: root ends it now (the kill its :kill makes), and its exit, which comes, answers
               ;; the requests it held: a process whose end closed while it lived is never waited on
               :else (do (lib/kill! (or pid (:pid v)) 9)
                         (assoc-in st [::dying (or pid (:pid v))] [agent (assoc died :ended true) inflight])))
          st (cond-> st rid (deliver-rid o rid {:stopped true :agent agent}))]
      (let [st (-> st (update :agents dissoc agent) (update :agent-procs dissoc agent) (update ::inserting dissoc agent) (assoc ::update true)
                   (set-agent o agent {:status (or stop :dead) :ended-ms (System/currentTimeMillis) :running nil}))
            {:keys [p opts]} (get-in st [::respawn agent])
            st (update st ::respawn dissoc agent)]
        ;; a Back's old process is gone: the same agent's new one is forked now (its entry replaces the ended one's)
        (cond (nil? opts) st
              (no-room st (count (:agents st))) (reply! st o p (no-room st (count (:agents st))))
              :else (start-spawn st o p opts))))))

(defn- pend
  "st with caller p awaiting the answer to a new request id (seon.step/pend), and the id."
  {:malli/schema [:=> [:cat :seon.root/api-state [:maybe [:or :seon.host/promise :map]] :keyword [:maybe :seon.agent/id]] [:tuple :seon.root/api-state :int]]}
  [st p kind agent] (step/pend st (when p {:p p :kind kind :agent agent})))

(defn- start-spawn
  "Start the agent opts names: its proc and socket, then the writer's :spawn. Answered at its hello (set-agent's :ready), or the end it meets first."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile [:or :seon.host/promise :map] :map] :seon.root/api-state]}
  [st o p opts]
  (let [[st aid fd] (start-agent st opts)
      branch (get-in st [:agents aid :branch])]
  ;; the agent's key named here, in root's boot: the child reads none from main
  (step/out! o :to-writer (cond-> {:op :spawn :agent aid :branch branch :task (:task opts) :ns (profile/now-ns) :fd fd :dest {:spawn aid}
                           :key (name aid)}
                    ;; a restore at a commit of a session's branch (docs/storage.md#compiled-code): the writer makes the branch from it
                    (:commit opts) (assoc :commit (:commit opts))))
  ;; answered at its hello (set-agent's :ready), or the end it meets first
  (cond-> (assoc-in st [::spawning aid] p)
    (:commit opts) (assoc-in [::restored aid] true)
    (seq (:evals opts)) (assoc-in [::inserting aid] (:evals opts)))))

(defn stale-binary
  "Why this root's binary is not current for its main, or nil: binary-current's answer for the tree main was last brought to
  from outside (db's :seon.root/source :tree, moved only by an import or a landing), {:binary :tree :reaches}, where what differs
  between the two trees reaches root's own code or a build input. What agents merged into main since is main's own and never a
  mismatch (the db is the source of truth). Unknown is never current (binary-current's own rule: a main with a source tree on a binary whose build
  recorded none names :missing :binary-tree), and a diff git cannot make is {:binary :tree :error}. Nil with no source tree
  (a store seeded with no source: nothing known). Agents of a stale binary would load main's forms over code it was not built
  from, so no agent is forked (no-room). See docs/source.md#a-boot-resumes-from-the-db."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :map]]}
  [db]
  (let [held (:tree (:seon.root/source (d/pull db [:seon.root/source] [:seon.root/id :root])))
        built (binary-program)
        r (binary-current db built held)]
    ;; a diff git cannot make is no answer of current: held as what the row says
    (if (contract/error? r) {:binary (get-in built [:inputs :tree]) :tree held :error (:seon.error/message r)} r)))

(defn- no-room
  "Why no agent can be started now, or nil: no fork server to fork it (refused before an agent proc and its socket are made: a socket the lost server closes would end that agent first, as a death), or too many live agents. n: the agents the start would leave, a Back's old one not among them."
  {:malli/schema [:=> [:cat :seon.root/api-state :int] [:maybe :map]]}
  [st n]
  (cond
    (= :lost (get-in st [:view :fork-server :status])) (fs/lost)
    (get-in st [:view :boot :stale-binary])
    (refused 'seon.root/spawn!
             (str "this root's binary was built from another git tree than the one its store's main was last brought to, so no agent is "
                  "forked: it would run the two mixed. " problems/stale-binary-acts)
             (get-in st [:view :boot :stale-binary]))
    (> n (cfg/value :seon.config.root/max-agents)) (refused 'seon.root/spawn! "too many live agents" {:max (cfg/value :seon.config.root/max-agents)})))

(defn- spawn-agent
  "A new agent from opts, or the refusal answered to p: its branch held by a live agent, no fork server, too many agents. Answered at its hello (set-agent's :ready), or the end it meets first."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile [:maybe [:or :seon.host/promise :map]] [:maybe :map]] :seon.root/api-state]}
  [st o p opts]
  (let [opts (cond-> opts (:branch opts) (update :branch #(pdb/on-main (pdb/conn-main (:conn st)) %)))
      ;; one live process per agent: the session's agent (its key is its id) is not started again while one holds it
      holder (or (and (:key opts) (get-in st [:agents (keyword (:key opts))]) (keyword (:key opts)))
                 (and (:branch opts)
                  (some (fn [[aid a]] (when (and (= (:branch opts) (:branch a)) (not (:ended-ms a))
                                                 (not (gone-statuses (:status a))))
                                        aid))
                        (get-in st [:view :agents]))))]
  (cond
    holder (let [{:keys [task]} (get-in st [:view :agents holder])]
             (reply! st o p (refused 'seon.root/spawn! (str "branch " (:branch opts) " is held by live agent " holder
                                                          (when task (str " working " task))
                                                          ": its seon_done or an end frees it")
                                     (cond-> {:branch (:branch opts) :agent holder} task (assoc :task task)))))
    (no-room st (inc (count (:agents st)))) (reply! st o p (no-room st (inc (count (:agents st)))))
    :else (start-spawn st o p opts))))

(defn- back-point
  "Where eval id of agent a's branch stands, {:commit :branch :t}, or why it can't be gone back to: the earliest commit of the branch holding its code's and response's last write, a run again's included and a reader's opening of it not (seon.ctx.wire/at-t), and a new branch's name for it, which no earlier back to it holds, as the head has moved since. See docs/turn.md#an-agent-goes-back-to-an-eval."
  {:malli/schema [:=> [:cat :seon.root/api-state :map :string] :map]}
  [st a id]
  (let [db (pdb/head (:conn st) (:branch a))
        e (pdb/eid db :seon.eval/id id)
        t (when e (apply max (map :tx (mapcat #(d/datoms db :eavt e %) [:seon.eval/code :seon.eval/response :seon.eval/result]))))
        at (when t (wire/at-t db t))
        commit (when (and at (= t (:max-tx at))) (d/commit-id at))]
    (cond
      (nil? e) (refused 'seon.root/back! (str "no eval " id " on this agent's branch") {:eval id})
      (nil? commit) (refused 'seon.root/back! (str "eval " id " has no commit of its own: a sweep took it, or a later eval shares it") {:eval id})
      :else {:commit commit :t t :branch (keyword (namespace (:branch a)) (str (name (:branch a)) "-back-" (:max-tx db)))})))

(defn request-name
  "The name of the request task text asks for on db: its first sentence's words (a sentence ends at a . ! or ? before a space or
  the end, so a dot inside a name, demo.math/double, is part of it), else \"work\"; words asked before take the next number, 2, 3, ...
  See docs/users.md#roots-session."
  {:malli/schema [:=> [:cat :seon.host/db :string] :string]}
  [db text]
  (let [words (re-seq #"[a-z0-9]+" (str/lower-case (re-find #"(?:[^.!?\n]|[.!?](?=[^\s.!?]))*" (str/triml text))))
        said (str/join "-" (or (seq words) ["work"]))
        free? #(nil? (pdb/eid db :seon.task/id (task/task-id 'request %)))]
    (if (free? said) said (some #(let [n (str said "-" %)] (when (free? n) n)) (iterate inc 2)))))

(defn- ext-op {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :seon.root/request] :seon.root/api-state]}
  [st o m]
  (let [{:keys [op p id]} m]
    (case op
      ;; seon.root/apply!: the declaration again; only the units whose declaration changed are
      ;; rebuilt, and update! keeps every proc it was handed unchanged (with its queue). The
      ;; api performs the update!, so it cannot replace itself: its own unit stays as booted
      ;; (changing the api's declaration takes a restart)
      :apply (let [sys (:system st)
                   built (system/build (:decl m) (:bindings m) (:parts sys))
                   built (-> built (assoc-in [:parts [:proc :api]] (get-in sys [:parts [:proc :api]]))
                             (assoc-in [:procs :api] (get-in sys [:procs :api])))
                   r (flow/update! @(:graph st) {:procs (merge (:procs built) (:agent-procs st)) :conns (:conns built)})]
               (reply! (assoc st :system built) o p (select-keys r [:started :stopped :kept :lost :ms])))
      :spawn (spawn-agent st o p (:opts m))
      ;; a person's Back to here: a new branch from the eval's commit, through the writer; its answer ends this process and starts the session's on it
      :back (let [a (get-in st [:view :agents id])]
              (cond
                (nil? (get-in st [:agents id])) (reply! st o p (absent a id))
                (not= :idle (:status a)) (reply! st o p (refused 'seon.root/back! (str id " is " (name (:status a)) ": it goes back when it is idle") {:agent id :status (:status a)}))
                (get-in st [::backing id]) (reply! st o p (refused 'seon.root/back! (str id " is already going back") {:agent id}))
                (no-room st (count (:agents st))) (reply! st o p (no-room st (count (:agents st))))
                :else (let [pt (back-point st a (:eval m))]
                        (if (contract/error? pt)
                          (reply! st o p pt)
                          (do (step/out! o :to-writer {:op :clone-branch :from (:commit pt) :branch (:branch pt) :dest {:back id}})
                              (assoc-in st [::backing id] {:p p :branch (:branch pt)}))))))
      ;; what a sentence says, or an agent's launch: a task from a template, released, answered with its id (:dest's :asked)
      :ask (let [db @(:conn st)
                 nm (request-name db (:text m))
                 template (get tpl/templates (:template m :user))
                 ;; who started it, the agent that launched it or the person asking: its agent is linked to them when it starts (seon.task/start-tx), and the reconciler tells them how it ends (seon.reconcile/tell-tx)
                 by (or (when (:agent m) (agent-key-of st (:agent m))) (signed-in-key db 'seon.root/ask!))]
             (cond
               (contract/error? by) (reply! st o p by)
               (not template)
               (reply! st o p (refused 'seon.root/ask! (str "no template " (:template m)) {:templates (vec (sort (keys tpl/templates)))}))
               :else
               (let [id (task/task-id 'request nm)
                     tx (tpl/request-tx db (tpl/from template {:seon.task/ns 'request :seon.task/name nm :seon.task/plan (:text m)}))]
                 (if (contract/error? tx)
                   (reply! st o p tx)
                   (let [[st rid] (pend st p :tx nil)]
                     (step/out! o :to-writer {:op :tx :branch :db :tx-data (conj (vec tx) [:db/add [:seon.task/id id] :seon.task/started-by [:seon.agent/key by]]) :dest {:asked id :rid rid}})
                     (update st :writes (fnil conj #{}) rid))))))
      ;; root's session (or any agent) moves the person's view: the signed-in user's focus to the agent named by key (docs/users.md#whats-on-screen)
      :focus (let [db @(:conn st)
                   by (signed-in-key db 'seon.root/focus!)]
               (cond
                 (contract/error? by) (reply! st o p by)
                 ;; an agent's key, or a task's id (my.task/launch! answers the task; its agent is made a moment later, and home follows it then)
                 (not (or (pdb/eid db :seon.agent/key (:key m)) (pdb/eid db :seon.task/id (:key m))))
                 (reply! st o p (gone (keyword (:key m)) (str "no agent or task " (:key m) " on main")))
                 :else (let [[st rid] (pend st p :tx nil)
                             to (if (pdb/eid db :seon.agent/key (:key m)) [:seon.agent/key (:key m)] [:seon.task/id (:key m)])]
                         (step/out! o :to-writer {:op :tx :branch :db :tx-data [[:db/add [:seon.user/name by] :seon.user/focus to]] :dest {:rid rid}})
                         (update st :writes (fnil conj #{}) rid))))
      :clone (let [parent (:parent m)]
               (cond
                 (nil? (get-in st [:agents parent])) (reply! st o p (absent (get-in st [:view :agents parent]) parent))
                 (>= (count (:agents st)) (cfg/value :seon.config.root/max-agents)) (reply! st o p (refused 'seon.root/clone! "too many live agents" {:max (cfg/value :seon.config.root/max-agents)}))
                 :else (let [opts (merge {:task (get-in st [:view :agents parent :task])} (:opts m) {:cloned-from parent})
                       [st aid fd] (start-agent st opts)
                       branch (get-in st [:agents aid :branch])
                       [st rid] (pend st p :clone parent)]
                   ;; the clone's socket goes to its parent with the request
                   (to-agent st o parent
                             {:op :req :rid rid :kind :clone :new aid :branch branch :pass-fd fd
                              :msg (cond-> {:op :clone :agent aid :branch branch} (:task opts) (assoc :task (:task opts)))}))))
      (:eval :tests :judge :stats :merge-main :render)
      (if (nil? (get-in st [:agents id]))
        (reply! st o p (absent (get-in st [:view :agents id]) id))
        (let [[st rid] (pend st p op id)
              msg (case op
                    :eval (cond-> {:op :eval :forms (:forms m)} (:ticks m) (assoc :ticks (:ticks m)) (:origin m) (assoc :origin (:origin m)))
                    :tests (cond-> {:op :tests} (:ticks m) (assoc :ticks (:ticks m)) (:names m) (assoc :names (:names m)) (:mode m) (assoc :mode (:mode m)) (:plan? m) (assoc :plan? true) (:process? m) (assoc :process? true) (:items m) (assoc :items (:items m))
                                            (get-in st [:view :agents id :task]) (assoc :task (get-in st [:view :agents id :task])))
                    :judge {:op :judge}
                    :stats {:op :stats}
                    :merge-main {:op :merge-main :data-attrs (:data-attrs m)}
                    ;; its page's parts drawn by its own renders (the render proc's, seon.render.proc/ask-parts)
                    :render {:op :render})]
          (to-agent st o id {:op :req :rid rid :kind op :msg msg})))
      ;; a message to an agent, live or not (the owner's from the page or the server, root's at a stage boundary): one entity on main
      ;; (seon.task/message-tx), synced before it is answered, so what follows (the turn that delivers it) finds it in the
      ;; store; the agent's turn reads it from main and writes the same entity on its branch (seon.turn/deliver!,
      ;; seon.agent/undelivered). No frame carries it: delivery is the fact. Its writer's answer wakes the agent (back-op)
      ;; root's session (key "root") is made at boot (root-session-boot-tx), so a message to root names it as any agent's does
      :message (if-let [k (agent-key-of st id)]
                 ;; who sends it: the sender the caller names (root's own, :root, as the reconciler's stage boundary sends; an agent's), else
                 ;; the signed-in person (the page's message!, which names none); a person's with no one signed in is refused
                 (let [from (if-some [f (:from m)] (name f) (signed-in-key @(:conn st) 'seon.root/message!))]
                   (if (contract/error? from)
                     (reply! st o p from)
                     (let [[st rid] (pend st p :tx nil)
                           w (message-write st from [k] (:body m) {:rid rid})]
                       (step/out! o :to-writer w)
                       (update st :writes (fnil conj #{}) rid))))
                 (reply! st o p (gone id (str "no agent " id " on main"))))
      ;; requests for the writer: answered by rid; a collection only while no agent is live,
      ;; the store's only writer then being root's writer (seon.db/collect!)
      :write (if (and (= :collect (get-in m [:req :op])) (seq (:agents st)))
               (reply! st o p (refused 'seon.root/collect "a collection waits until no agent is live" {:agents (vec (sort (keys (:agents st))))}))
               (let [[st rid] (pend st p (get-in m [:req :op]) nil)
                   req (:req m)
                   dest (case (:op req) :merge-main {:merge-main (:agent req) :rid rid} {:rid rid})]
               (step/out! o :to-writer (assoc (dissoc req :agent) :dest dest))
               (update st :writes (fnil conj #{}) rid)))
      ;; the merge gate's run, whoever asks (the reconciler's :gate, an agent's seon.agent/judge!): branch's candidate
      ;; (the writer's: the files the octopus merge would write), then the :tests effect over them on the gate checkout
      :gate (let [checkout (:gate-checkout (task/root @(:conn st)))]
              (if-not (and checkout (:branch (:source st)))
                (reply! st o p (contract/error :seon.root/no-gate-checkout #'seon.root/ext-op "this root has no checkout to run process tests on (:seon.config.source/gate-checkout, or a source root)"))
                (let [[st rid] (pend st p :gate nil)]
                  (step/out! o :to-writer {:op :merge-candidate :branch (:branch m) :data-attrs #{} :dest {:gate rid :tests (:tests m) :base (:base m) :repo checkout :prio (:prio m 0)}})
                  st)))
      ;; the test service (seon.prove): the request goes to its proc, the caller (a session, or an
      ;; agent's turn through its proc) is answered when the run it joined ends
      :prove (let [[st rid] (pend st p :prove (:agent m))]
               (step/out! o [:prove :req] (assoc (select-keys m [:branch :t :digest :agent :proofs :names :mode :checkout :tree]) :rid rid))
               st)
      :kill (cond-> (if (get-in st [:agents id])
                      ;; :ended when root stops it because its run ended (the reconciler says so)
                      (-> st (set-agent o id {:status (if (:ended m) :ended :killed)}) (to-agent o id {:op :kill}))
                      st)
              p (reply! o p {:killed id}))
      ;; an effect asked by root itself or a root proc (the reconciler's write-back): to its
      ;; kind's owner, answered by rid like the writer's answers
      :fx (let [owner (keyword "fx" (name (:kind m)))]
            (if (contains? (system/fx-owners (get-in st [:system :procs])) owner)
              (let [[st rid] (pend st p :fx nil)]
                (step/out! o [owner :ask] {:req (:req m) :prio 0 :dest {:rid rid}})
                st)
              (reply! st o p (contract/error :seon.fx/unknown-kind #'seon.root/ext-op (str "no effect kind " (:kind m))))))
      :fs (let [[st rid] (pend st p :fs nil)]
            (step/out! o :to-fs (assoc (:req m) :rid rid))
            st)
      :state (reply! st o p (:view st))
      :errors (reply! st o p (::errors st))
      ;; main's health (my.root/problems): the render proc's, kept by what each move of main changed
      ;; (seon.render.proc/health!), brought to (:db m) there and answered by rid at the fold's counts
      :health (let [[st rid] (pend st p :health nil)]
                ;; the fold goes with it: the render proc answers at these counts (seon.problems/with-fold), and keeps
                ;; it, so the banner, root's context and this answer are one count
                (step/out! o [:render :from-web] {:op :health :rid rid :db (:db m) :fold (::errors st)})
                st)
      :shutdown (assoc st :shutdown-p p)
      (cond-> st p (reply! o p (refused 'seon.root/request (str op " is not one of root's requests") {}))))))

(defn- back-op
  "A message from an agent proc, a fork-server or writer event, or Flow's error channel."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :map] :seon.root/api-state]}
  [st o m]
  (cond
    (:dest m)
    (let [{:keys [dest r]} m
          ;; a message committed: each recipient is woken, over the reconciler's :wake port (a commit notice wakes nothing)
          _ (when (and (:wake dest) (not (contract/error? r))) (doseq [k (:wake dest)] (step/out! o :to-reconciler {:agent k})))]
      (cond
        (:merge-main dest) (let [aid (:merge-main dest)]
                             (step/out! o :events (step/event {:type :merged-main :agent aid :result (dissoc r :sync)}))
                             (cond-> st
                               (:sync r) (to-agent o aid {:op :write :msg (:sync r)})
                               true (deliver-rid o (:rid dest) (dissoc r :sync))))
        ;; the branch of a Back to here is made: this process ends (replaced: its end writes no fact over the session's), and the session goes on from there, its task parked on the new process, which a message wakes (docs/turn.md#parked; the messages it took were counted on the old branch)
        (:back dest) (let [id (:back dest)
                           {:keys [p branch]} (get-in st [::backing id])
                           {:keys [key task status]} (get-in st [:view :agents id])
                           st (update st ::backing dissoc id)]
                       (cond
                         (contract/error? r) (reply! st o p r)
                         ;; it died or was given work while the branch was made: the session stays as it stands, the new branch unused
                         (not= :idle status) (reply! st o p (refused 'seon.root/back! (str id " is " (name status) ": it did not go back") {:agent id :status status}))
                         (no-room st (count (:agents st))) (reply! st o p (no-room st (count (:agents st))))
                         :else
                         (let [k (name id)
                               e (d/entity @(:conn st) [:seon.agent/key k])
                               ;; what a start records of a session's agent (seon.task/start-tx): this boot now holds it
                               ;; named in main's facts as a start names it; an agent on a task has its branch from its start alone (`observed`), so this is that write
                               named (pdb/off-main (pdb/conn-main (:conn st)) branch)
                               started (cond-> {:seon.agent/key k :seon.agent/boot (:boot st) :seon.agent/started-ms (System/currentTimeMillis)
                                                :seon.agent/max-turns (or (:seon.agent/max-turns e) (cfg/value :seon.config.agent/max-turns))}
                                         task (assoc :seon.agent/branch named))]
                           (step/out! o :to-writer {:op :tx :branch :db :tx-data (cond-> [started] task (into (cons {:seon.task/id task :seon.task/branch named} (task/move-tx task (:seon.task/outcome (d/entity @(:conn st) [:seon.task/id task])) :parked {}))))
                                                    :dest {:agent-facts [id]}})
                           ;; one live process per agent: the old one is stopped first, and the new one forked once its end is seen
                           ;; (end-agent), under the same id, so no message can reach either of two
                           (-> st (set-agent o id {:status :killed :replaced true}) (to-agent o id {:op :kill})
                               (assoc-in [::respawn id] {:p p :opts (cond-> {:branch branch :key k} task (assoc :task task))})))))
        ;; a merge gate's candidate is read (the :gate request): refused, or a changed namespace with no file on the branch
        ;; (made at the REPL, never written; it cannot be put over the checkout), is its answer, and no test runs: no files
        ;; would test main as it is and vouch for nothing; else its tests run with its files, answered by rid
        (:gate dest) (let [rid (:gate dest)
                           unread (vec (for [n (sort (keys (:unfiled r)))]
                                         {:ns n :reason "no :ns/file on the branch: its forms were never written to a file"}))]
                       (cond (contract/error? r) (deliver-rid st o rid r)
                             (seq unread) (deliver-rid st o rid {:unread unread})
                             ;; no process test reached: the writer's analysis of the candidate was the check
                             (empty? (:tests dest)) (deliver-rid st o rid {:value {:results {}}})
                             :else (do (step/out! o [:fx/tests :ask] {:req {:repo (:repo dest) :branch (:branch (:source st)) :files (:text r) :tests (:tests dest) :base (:base dest)}
                                                                     :prio (:prio dest 0) :dest {:rid rid}})
                                       st)))
        (:asked dest) (deliver-rid st o (:rid dest) (if (contract/error? r) r {:task (:asked dest)}))
        ;; the recorded errors' write was answered: the next window may write
        (:errors-flushed dest) (dissoc st ::errors-flight)
        ;; a stall's evidence was captured (stall-task): its files on the stall task's cause, on main
        (:stall-evidence dest) (let [tid (:stall-evidence dest) db (some-> (:conn st) deref)
                                     cause (some-> db (d/entity [:seon.task/id tid]) :seon.task/cause)]
                                 (cond (contract/error? r) (problem st (err/occurrence r :agent {:agent (:agent dest) :task tid} (System/currentTimeMillis)))
                                       (and cause (not (:writer-closed st)))
                                       (do (step/out! o :to-writer {:op :tx :branch :db :dest {:agent-facts [(:agent dest)]}
                                                                    :tx-data [[:db/add [:seon.task/id tid] :seon.task/cause
                                                                               (update cause :evidence merge (:files (:value r)))]]})
                                           st)
                                       :else st))
        (:rid dest) (deliver-rid st o (:rid dest) r)
        (:agent-facts dest) (cond-> st
                              (contract/error? r) (problem (err/occurrence r :agent {:agent (:agent-facts dest)} (System/currentTimeMillis))))
        (:agent dest) (to-agent st o (:agent dest) {:op :write :msg (cond (:op r) r
                                                                          (= :branched (:op dest)) (cond-> {:op :branched :k (:k dest) :cfg (:cfg r)} (contract/error? r) (assoc :error r))
                                                                          :else (assoc r :op (:op dest) :k (:k dest)))})
        (:spawn dest) (if (contract/error? r) (spawn-failed st o (:spawn dest) (:seon.error/message r) r) st)
        :else st))

    (:ev m)
    (case (:ev m)
      :forked (do (step/out! o :events (step/event {:type :spawned :agent (:agent m) :pid (:pid m) :fork-ms (:fork-ms m)}))
                  (when (stopped-statuses (get-in st [:view :agents (:agent m) :status])) (lib/kill! (:pid m) 9))
                  (-> st (set-agent o (:agent m) {:pid (:pid m)})
                      (view! update-in [:fork-server :agents-forked] (fnil inc 0))
                      (view! update-in [:stats :fork-ms] lib/push-window (:fork-ms m) 500)
                      (to-agent o (:agent m) {:op :forked :pid (:pid m)})))
      ;; the agent the exit is of: the death waiting on its pid, a live agent with it, or the last
      ;; one to end with it and no exit yet (stopped: none waits); never an entry whose exit is
      ;; recorded, whose pid the OS has given again
      :exit (let [mine (filter (fn [[_ a]] (= (:pid m) (:pid a))) (get-in st [:view :agents]))
                  st (if-let [aid (or (first (get-in st [::dying (:pid m)]))
                                      (some (fn [[aid]] (when (get-in st [:agents aid]) aid)) mine)
                                      (some->> (remove (fn [[_ a]] (:exit a)) mine) seq (apply max-key (fn [[_ a]] (:ended-ms a 0))) key))]
                       (set-agent st o aid {:exit (:status m)})
                       st)]
              ;; a death waiting for its exit status: recorded now
              (if-let [[aid died inflight] (get-in st [::dying (:pid m)])]
                (-> st (update ::dying dissoc (:pid m)) (record-death o aid (:status m) died inflight))
                st))
      ;; a fork the lost server was asked after its death failed every spawn still waiting
      ;; (:fork-server-down): one already answered is not failed again
      :fork-failed (cond-> st
                     (get-in st [::spawning (:agent m)])
                     (spawn-failed o (:agent m) (or (:reason m) (str "fork refused: " (:active-threads m) " active threads")) nil))
      :applied (-> st (view! update-in [:stats :lag-ms] lib/push-window (:lag-ms m) 200)
                   (view! assoc-in [:fork-server :mismatch] (:mismatch m)))
      ;; a throw in a fork server step: an error fact on root, the server serving on (seon.fork-server/server-step)
      :step-failed (problem st (err/occurrence (:error m) :platform {} (System/currentTimeMillis)))
      ;; the server made its standby: the view shows it has one again
      :standby (view! st assoc-in [:fork-server :standby] true)
      ;; the server made no standby (docs/processes.md#a-step-that-throws): a recorded platform problem, and the view shows it has none
      :standby-failed (-> st
                          (view! assoc-in [:fork-server :standby] false)
                          (problem (err/occurrence (contract/error :seon.fork-server/standby-failed #'back-op
                                                                   (str "the fork server made no standby: " (:active-threads m) " active threads")
                                                                   {:active-threads (:active-threads m) :gen (:gen m)})
                                                   :platform {} (System/currentTimeMillis))))
      :load-errors (view! st update-in [:fork-server :load-errors] (fnil into []) (:errors m))
      ;; fork requests the dead server never served fail; agents live on
      ;; the dead server's children are orphans: no server reaps them, so a death waiting
      ;; for its exit status is answered now, and a later one at once
      :fork-server-down (do (step/out! o :events (step/event {:type :fork-server-down :pid (:old m)}))
                       (as-> (reduce (fn [st [aid a]]
                                       (if (and (= :spawning (:status a)) (nil? (:pid a)) (get-in st [:agents aid]))
                                         (spawn-failed st o aid "fork server went down before the fork" nil)
                                         (cond-> st (:pid a) (set-agent o aid {:orphan true}))))
                                     (view! st update :fork-server assoc :status :restarting) (get-in st [:view :agents])) st
                         (reduce (fn [st [_ [aid died inflight]]] (record-death st o aid nil died inflight))
                                 (dissoc st ::dying) (::dying st))))
      :fork-server-lost (do (step/out! o :events (step/event {:type :fork-server-lost}))
                       (view! st update :fork-server assoc :status :lost))
      :fork-server-up (do (step/out! o :events (step/event {:type :fork-server-up :pid (:pid m)}))
                     (view! st update :fork-server (fn [s] (-> s (assoc :status :up :pid (:pid m)) (update :restarts (fnil inc 0))))))
      st)

    :else
    (case (:op m)
      ;; the test service answered a waiter (seon.prove)
      :prove-answer (deliver-rid st o (:rid m) (:r m))
      ;; a verdict as it lands, for the run keyed :key: on root's events, which a streaming :prove writes as a line
      :prove-verdict (do (step/out! o :events (step/event (-> m (dissoc :op) (assoc :type :prove-verdict)))) st)
      ;; a run's items held for the machine's load, and resumed (seon.prove/grant): its streaming client says so
      :prove-held (do (step/out! o :events (step/event (-> m (dissoc :op) (assoc :type :prove-held)))) st)
      ;; the render proc's health of main (:health): its value, held at the counts this api's fold put it at
      ;; (the fold it was asked with, and which it keeps), so it is the same count as root's page's
      :health-answer (deliver-rid st o (:rid m) (:r m))
      ;; what the test service holds, kept as it changes: a stall's evidence names it (stall-task)
      :prove-state (assoc st ::prove (:r m))
      :status (set-agent st o (:agent m) (:set m))
      :stat (case (:k m)
              :noise (view! st update :stats assoc :noise (:v m) :noise-ordered (:ordered m) :noise-first-bad (:bad m))
              (view! st update-in [:stats (:k m)] lib/push-window (:v m) 100000))
      :message (deliver-message st o m)
      ;; a proc's request (the reconciler's, the web's, MCP's): an ordinary api request
      ;; whose answer goes back to the proc as data
      :request (ext-op st o (assoc (:req m) :p {:route (:route m) :tag (:tag m)}))
      :clone-failed (spawn-failed st o (:agent m) "clone refused" (:error m))
      ;; the web proc's listener, when it changes (seon.web)
      :web (view! st assoc :web (select-keys m [:listening :port :error]))
      :ended (end-agent st o m)
      :writer (view! st (fn [v] (-> v (assoc-in [:writer :tx-count] (:tx-count m))
                                    (assoc-in [:writer :flushes] (:flushes m 0))
                                    (update-in [:writer :batch-ms] lib/push-window (:batch-ms m) 200)
                                    (update-in [:writer :recent] #(lib/push-window % [(profile/now-ns) (:n-tx m)] 2000))
                                    (update-in [:stats :batches] (fnil inc 0))
                                    (update-in [:stats :commits] (fnil + 0) (:commits m))
                                    (update-in [:stats :batched-tx] (fnil + 0) (:n-tx m)))))
      ;; the end of the flow: the writer has drained; the fork server goes last
      :drained (do (step/out! o :to-fs {:op :shutdown}) st)
      :fs-down (do (some-> (:shutdown-p st) (deliver {:drained true :fork-server (:pid m) :ended (:ended m)})) st)
      ;; a defect in a root proc: recorded as data (its error map when it carries one), and
      ;; the message the step took answered with it (Flow's catch dropped the step's outputs):
      ;; the api's own throw answers every message its step took (::lost)
      :error (let [e (if (contract/error? (:data m))
                       (:data m)
                       (contract/error :seon.root/proc-defect (proc-sym st (:pid m)) (or (:message m) "a defect with no message")
                                       ;; where it threw, and what it took (a message's op, or an answer's keys)
                                       (cond-> {:pid (:pid m)}
                                         (:in m) (assoc :seon.error/in (:in m))
                                         (map? (:msg m)) (assoc :took (or (:op (:msg m)) (vec (sort (keys (:msg m)))))))))
                   taken (distinct (cond-> (if (= :api (:pid m)) (::lost st) []) (:msg m) (conj (:msg m))))]
               ;; the defect is an event too, so whoever waits on root's events for what that proc
               ;; owed (a check awaiting :fork-server-up) hears that it never will, not silence
               (step/out! o :events (step/event {:type :proc-defect :pid (:pid m) :message (:seon.error/message e)}))
               ;; the view keeps the newest :seon.config.root/view-window and counts them all (renders say how many it left)
               (-> (reduce (fn [st x] (answer-taken st o (:pid m) x e)) (cond-> st (= :api (:pid m)) (dissoc ::lost)) taken)
                   (view! update :root-errors #(lib/push-window %1 %2 (cfg/value :seon.config.root/view-window)) (merge {:pid (:pid m) :message (:message m)} (:data m)))
                   (view! update :root-errors-total (fnil inc 0))
                   (problem (err/occurrence e :platform {} (System/currentTimeMillis)))))
      ;; an agent's errors since its last commit (seon.error/note, each of its class), counted across agents
      :errors (let [task (get-in st [:view :agents (:agent m) :task])]
                (reduce (fn [st s] (problem st (err/sent->occurrence s (:agent m) task))) st (:entries m)))
      ;; an effect owner's failed outcome (seon.fx/owner-proc)
      :problem (problem st (err/occurrence (:error m) (:class m) {:agent (:agent m) :task (get-in st [:view :agents (:agent m) :task])}
                                           (System/currentTimeMillis)))
      ;; an agent's proc observed it stalled (seon.agent-proc :stall?): a fact on the agent, what it
      ;; waits on with it, an open problem while it holds (seon.problems/stalled-agents); its next
      ;; frame (:spoke) or its end retracts it
      :stalled (let [s (cond-> {:quiet-ms (:quiet-ms (:error m))} (get-in m [:error :last-op]) (assoc :last-op (get-in m [:error :last-op])))]
                 ;; while root stops, a stalled agent is one that does not exit on :stop: the stall, a recorded fact,
                 ;; ends it (no clock of the stop's own: task seon.root/shutdown-and-death-are-dataflow)
                 (cond-> (-> st (set-agent o (:agent m) {:stalled s}) (stall-task o (:agent m) s))
                   (:closing st) (to-agent o (:agent m) {:op :kill})))
      :spoke (set-agent st o (:agent m) {:stalled nil})
      ;; the stall timer: each agent with an eval in flight checks its own quiet time
      :stall-check (let [now (System/currentTimeMillis)]
                     (step/fired! (::stall-timer st))
                     (reduce (fn [st id] (to-agent st o id {:op :stall? :now-ms now :stall-ms (:stall-ms st)})) st (::busy st)))
      st)))

(defn- answer-taken
  "st with the caller of x, a message proc pid's throwing step took, answered the defect e: the answer its :dest names
  (`seon.root/back-op`), its promise or reply route when it came in at the api or as a
  proc's request, the request pending under its :rid, or, for an answer the proc took
  ({:tag :r}), that answer again on the proc's :answers route; a fork it lost ends its agent's spawn as a refused fork
  does. An answer that was already an
  error is not answered again: the proc had its one answer, and a step that throws on an
  error would throw on the next."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :keyword :any :seon.error/base] :seon.root/api-state]}
  [st o pid x e]
  (cond
    (not (map? x)) st
    (:dest x) (back-op st o {:dest (:dest x) :r e})
    (:p x) (reply! st o (:p x) e)
    (= :request (:op x)) (reply! st o {:route (:route x) :tag (:tag x)} e)
    (int? (:rid x)) (deliver-rid st o (:rid x) e)
    (and (contains? x :tag) (contains? x :r) (not (contract/error? (:r x))))
    (reply! st o {:route [pid :answers] :tag (:tag x)} e)
    ;; a fork the step lost (it threw sending or taking it): its agent's spawn waits on the fork's own answer, which never
    ;; comes, so it ends as a refused fork does (:fork-failed), with why (2026-11-08: a fork whose message could not be
    ;; encoded left its spawn waiting with no error)
    (and (= :fork (:op x)) (:agent x))
    (cond-> st (get-in st [::spawning (:agent x)]) (spawn-failed o (:agent x) (:seon.error/message e) nil))
    :else st))

(defn- api-one {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :keyword [:maybe :map]] :seon.root/api-state]}
  [st o in m]
  (some->> m (vswap! (::taken st) conj))
  (cond
    (and (= in :ext) (nil? m))
    ;; the api closed: agents drain and exit; then the writer's in-port closes
    (let [st (assoc st :closing true)]
      (reduce (fn [st aid] (let [[st rid] (step/pend st nil)] (to-agent st o aid {:op :drain :rid rid}))) st (keys (:agents st))))
    (= in :ext) (ext-op st o m)
    (nil? m) st
    :else (back-op st o m)))

(defn api-proc
  "The api. In :ext (the API's callers, each request with its reply promise: the
  promises live only here), :back (the agent procs and Flow's error channel),
  :from-writer and :from-fs (answers and events). Out :to-writer, :to-fs, :events, and
  [agent :in] for each agent (routed by the resolver).
  Agents are procs of this graph: a step that spawned or ended agents applies ONE
  flow/update! at its end (the graph, a live handle, comes in :graph). A closed :ext
  starts the flowing shutdown."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:graph "a promise of this graph" :system "a promise of the built system (seon.system/build)"}
       :ins {:from-writer "writer answers and stats" :from-fs "fork-server events"} :outs {:to-fs "fork-server requests" :to-reconciler "a wake per recipient of a committed message, {:agent key}"}
       :workload :io
       :ping-map-fn (fn [st] {:agents (count (:agents st)) :closing (boolean (:closing st))
                              ;; every ask it holds unanswered, rid -> [kind agent]: what a waiter waits on
                              :pending (into (sorted-map) (for [[r {:keys [kind agent]}] (:pending st)] [r [kind agent]]))
                              ;; the stall check, a hang's evidence (stall-state): its armed flag, the agents it holds busy, what a throw lost
                              ::stall-armed (boolean (some-> (::stall-timer st) deref)) ::busy (or (::busy st) #{}) ::lost (::lost st)})})
  ;; :system is the root graph's declared procs and conns as built (seon.system), changed by
  ;; seon.root/apply!; agents' procs join them at each update!
  ([args] (assoc (dissoc args :system) :agents {} :agent-procs {} :pending {} :rid 0
                 :system @(:system args)
                 ;; what this step took, kept across Flow's catch (which keeps the state from
                 ;; before the step): a host object of this proc's own
                 ::taken (volatile! [])
                 ;; the stall check's timer: armed while an agent has an eval in flight (seon.step/timer)
                 ::stall-timer (step/timer)
                 ::errors {}
                 ::flow/in-ports {:ext (:ext args) :back (:back args)}
                 ::flow/out-ports {:to-writer (:to-writer args) :events (:events args)}))
  ([st tr]
   ;; the stop transition: nothing accepted is left unanswered
   (when (= tr ::flow/stop)
     (doseq [[_ {:keys [p]}] (:pending st) :when (not (map? p))] (deliver p (gone nil "root stopped"))))
   st)
  ([st in m]
   (let [o (volatile! {})
         before (get-in st [:view :agents])
         ;; a step that threw left what it took (Flow names only the first): lost, each
         ;; answered when the api's defect arrives at :error
         st (if-let [lost (seq @(::taken st))] (update st ::lost (fnil into []) lost) st)
         _ (vreset! (::taken st) [])
         ;; this message, then whatever else is already queued at the api and on :back
         st (loop [st (api-one st o in m) k 1]
              (if (< k (cfg/value :seon.config.root/api-batch))
                (if-let [[ch c-in] (some (fn [[ch c-in]] (when-let [x (a/poll! ch)] [x c-in]))
                                         (cond-> [[(:back st) :back]] (< (count (:writes st)) (cfg/value :seon.config.root/api-writes)) (conj [(:ext st) :ext])))]
                  (recur (api-one st o c-in ch) (inc k))
                  st)
                st))
         st (flush-problems st o)
         st (record-agents st o before)
         ;; while an agent has an eval in flight, the stall check runs every half stall bound
         ;; its armed flag is a host object (seon.step/timer): a step that took :stall-check and then threw cannot leave it armed for good
         _ (when (and (seq (::busy st)) (pos? (:stall-ms st)) (:back st))
             (step/arm! (::stall-timer st) (:back st) (quot (:stall-ms st) 2) {:op :stall-check}))
         st (if (::update st)
              (do (flow/update! @(:graph st) {:procs (merge (get-in st [:system :procs]) (:agent-procs st)) :conns (get-in st [:system :conns])})
                  (dissoc st ::update))
              st)]
     ;; the writer's in-port closes once the api is closed and every agent has ended
     (when (and (:closing st) (empty? (:agents st)) (not (:writer-closed st)))
       (a/close! (:to-writer st)))
     (vreset! (::taken st) [])
     [(cond-> st
        (and (:closing st) (empty? (:agents st))) (assoc :writer-closed true)
        ;; at the bound of writer requests in flight: read answers only, not the api
        true (assoc ::flow/input-filter (if (>= (count (:writes st)) (cfg/value :seon.config.root/api-writes)) #(not= :ext %) identity)))
      @o])))

;; ====================================================================================
;; Boot
;; ====================================================================================

(defn- warm!
  "Run, before the fork server is forked, every code path agents and the server take,
  so forked children inherit it compiled, over a definition and a task of its own (no seed
  needed). Answers what its reads found."
  {:malli/schema [:=> [:cat :seon.host/conn] :any]}
  [conn]
  ;; a definition and a task of warm!'s own, on a value never committed: boot needs no seed
  (let [db (pdb/speculate-db @conn [{:db/id "ns" :ns/name 'seon.warm :ns/doc "warm"}
                              {:db/id "f" :form/id "seon.warm/f" :form/src "(defn f [x] x)" :form/ns "ns" :form/order 0 :form/defines ["v"]}
                              {:db/id "v" :var/name 'seon.warm/f :var/ns "ns" :var/doc "warm" :var/form "f"}
                              {:task/name :seon.warm/tidy :task/plan "warm" :task/status :held :task/refs ["v"]}])]
    (dotimes [_ 3]
      (d/q '[:find ?n . :in $ ?v :where [?e :var/name ?v] [?e :var/doc ?n]] db 'seon.warm/f)
      (d/q '[:find (count ?e) . :where [?e :var/name]] db)
      (d/q '[:find [?n ...] :where [?e :ns/name ?n]] db)
      (d/pull db '[*] [:var/name 'seon.warm/f])
      (d/pull db '[:task/name {:task/refs [:var/name]}] [:task/name :seon.warm/tidy])
      (pdb/speculate-db db [{:note/text "warm" :note/agent :warm}])
      (pdb/speculate db [{:note/text "warm" :note/agent :warm}])
      (sock/frame {:op :reply :response {:handle 'result/e1 :text "x" :ms 0.1}})
      ;; the one value printer, its text and hiccup paths and a cut
      (print/print-value {:a [1 2 3] :b "x" :c (vec (range 300))} {:handle 'result/e0 :budget 200 :outputs #{:text :hiccup}}))
    (let [nsx (create-ns 'seon.warm)]
      (binding [*ns* nsx]
        (refer-clojure)
        (refer 'clojure.test :only '[deftest is testing])
        (eval '(defn warm-f [x] (* 2 x)))
        (eval '(deftest warm-t (is (= 4 (warm-f 2)))))
        (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/run-tests nsx)))
      (remove-ns 'seon.warm))
    ;; what the reads above found: its own definition and task, so they ran real paths
    {:doc (d/q '[:find ?n . :in $ ?v :where [?e :var/name ?v] [?e :var/doc ?n]] db 'seon.warm/f)
     :task (d/pull db '[:task/name {:task/refs [:var/name]}] [:task/name :seon.warm/tidy])}))

(declare call!)

(defn- source-digest
  "The digest of what read-source's answer r read of dirs: its git tree id (or, with none, the files), the pins and dirs, the
  same whether the tree was read whole or by its changes, so a result keyed by the inputs stays shared. A dir added moves it, though the tree didn't."
  {:malli/schema [:=> [:cat :map [:sequential :string]] :string]}
  [r dirs]
  (lib/digest (sedn/encode [(or (:tree r) (:value r)) (:pins r) (vec dirs)])))

(defn- unimportable
  "Why files ({relative path text}), read at the source root, can't become main's as tx-of would make them, or nil: each of a kind on the allow-list that does not read, by name, or the config its entries would state on db refused (seon.program/config-refused), each with the commit main last imported, which stays. So a seed or a re-index says which file and which commit last worked, and main stays as it was. See docs/source.md#a-file-that-does-not-load."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]] :any :any] [:maybe :map]]}
  [db files tx commit]
  (let [bad (for [[p t] (sort files) :when (and t (cfg/file-kind p)) :let [why (fx/unread-text p t)] :when why] (str p ": " why))
        bad (if (seq bad)
              bad
              (when-let [e (when (and tx (not (contract/error? tx))) (program/config-refused db tx))]
                [(str cfg/shipped-file ": " (:seon.error/message e))]))]
    (when (seq bad)
      (contract/error :seon.source/unanalyzable #'seon.root/unimportable
                      (str (str/join "; " bad) "; main stays as imported at commit " (or commit "none yet"))
                      {:files (vec (sort (keys files)))}))))

(defn import-of
  "What main's program on db becomes for r, what seon.fx/read-source answered of {:root :dirs}, held being root's
  :seon.root/source fact: {:digest} alone when main holds r's digest (nothing to import); {:digest :tx :fact} when it
  imports, tx the program facts (seon.source/tx-of, only the changes when r was read by its :paths) with the files'
  modes, and fact the source fact with the digest, its counts as main holds them after tx; {:fact :error} when it can't
  (a read with no files, or files that don't import: unimportable), fact saying what was read and why, with no digest,
  so the next import reads again. t0: the import's start, the fact's :ms. The one computation of an import:
  the seed (import-tree!) and a re-index (my.root/import!) differ only in how they write it. See docs/source.md#indexing-a-tree."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map] :map [:map [:root :string] [:dirs [:sequential :string]]] :int] :map]}
  [db held r {:keys [root dirs]} t0]
  (let [files (:value r)
        digest (when files (source-digest r dirs))
        changed? (boolean (:paths r))
        fact-on (fn [db n] (cond-> {:root root :dirs (vec dirs) :ms (lib/r2 (profile/ms-since t0))
                                    :files (if changed? (+ (count (d/datoms db :aevt :ns/file)) (count (d/datoms db :aevt :schema/file))) n)
                                    :forms (count (d/datoms db :aevt :form/id))}
                             (:commit r) (assoc :commit (:commit r))
                             (:branch r) (assoc :branch (:branch r))
                             ;; the tree it read: the next import reads only what differs from it
                             (:tree r) (assoc :tree (:tree r))
                             (seq (:pins r)) (assoc :pins (:pins r))
                             ;; a read that carries its files with an error (a gap with main) imports, and says so
                             (contract/error? r) (assoc :error (:seon.error/message r))))]
    (cond
      (nil? files) {:fact (fact-on db 0) :error r}
      (= digest (:digest held)) {:digest digest}
      :else
      (let [tx (source/tx-of db files changed?)
            bad (if (contract/error? tx) tx (unimportable db files tx (:commit held)))]
        (if bad
          {:digest digest :fact (assoc (fact-on db (count files)) :error (:seon.error/message bad)) :error bad}
          (let [tx (into (vec tx) (source/modes-tx db (:modes r)))]
            {:digest digest :tx tx
             ;; the counts read three attributes after tx: only their part of it is applied (seon.db/project-tx), never the whole import
             :fact (assoc (fact-on (pdb/speculate-db db (pdb/project-tx tx #{:form/id :ns/name :ns/file :schema/key :schema/file})) (count files))
                          :digest digest :retracted (count (filter #(and (vector? %) (= :db/retractEntity (first %))) tx)))}))))))

(defn baseline-of
  "wiki-baseline's answer for db: the plan read from db, the history asked of (ask {:root :sections}), the effect owner's
  answer (seon.fx/wiki-history), as root asks it through its api and a seed asks it directly."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/fn :string :string] :map]}
  [db ask checkout dir]
  (let [plan (doc/baseline-plan db (doc/pages checkout dir))]
    (if (or (empty? plan) (not (cfg/value :seon.config.root/wiki-baseline)))
      {}
      (let [r (ask {:root checkout :sections plan})]
        (if (contract/error? r)
          (doc/baseline-failed db plan (:seon.error/message r))
          (doc/baseline-reads db plan (:value r)))))))

(defn wiki-baseline
  "What each wiki section lacking a :seon.doc/read was last written against, from git (the
  :history effect, seon.fx/wiki-history), as the loader takes it: {address {:read :unread}} (seon.doc/baseline-reads).
  {} when every section has one. The history asked, the effect owner does the git; when it cannot be
  asked every section is read as of today and says why: its record is each section's
  :seon.doc/baseline-unread, which health shows as a :stale-section row."
  {:malli/schema [:=> [:cat :seon.root/sys :string :string] :map]}
  [sys checkout dir]
  (baseline-of @(:conn sys) #(deref (call! sys {:op :fx :kind :history :req %})) checkout dir))

(defn wiki-tx
  "The wiki (the checkout's :seon.config.root/wiki-dirs: docs/ and the top-level pages) onto main db, indexed by a seed (seed!) or a
  re-index (my.root/load-docs!): the system prompt every agent reads first is one of its pages
  (seon.ctx.wire/system-text), and the help page another (seon.ctx.wire/help-text). An unchanged wiki writes nothing. A link to no section refuses
  the write, :seon.db/no-target naming it (docs/references.md#the-wiki-in-the-store).

  baseline: what the sections with no record were last written against (wiki-baseline)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string] [:vector :any]]
                  [:=> [:cat :seon.host/db :string :map] [:vector :any]]]}
  ([db checkout] (wiki-tx db checkout {}))
  ([db checkout baseline]
   (doc/load-dir-tx db checkout (cfg/value :seon.config.root/wiki-dirs) baseline)))

(def ^:private root-session-plan
  "What root's session is for, its task's plan: what educates it, beside the docs it cites. See docs/users.md#roots-session."
  "You are root's session: the person talks to Seon through you. Answer the person yourself when you can. When they want work done, look first for a session already on it: (my.user/sessions) lists every session on main and how each stands; if one is on it, move their view to it with (my.user/focus! \"<its key>\") and tell them what it is doing. More: docs/users.md#roots-session")

(defn root-session-tx
  "Root's session, made at the boot of a main that has none (`root-session-boot-tx`), an ordinary agent made the ordinary way: an agent
  entity keyed \"root\" (a reserved key, as the user's is its name), linked to root by :seon.agent/root, its pattern written once as
  its role's name and its one addition, a join to whoever started it read by the user's own pattern ([:seon.db/agent-fact
  {:seon.agent/_agents :seon.db/user-fact}]: what either schema gains later reaches it with no write), its own render the stage (seon.render.ui/stage: the page a fresh system opens on), and the task request/root of the
  chat template (seon.template/request-tx), parked, its agent that entity, so the first message's wake spawns it. The user started it
  (:seon.task/started-by, the user's :seon.agent/agents: docs/users.md#who-started-whom). Root's own entity is untouched: an entity's id
  derives from its identity, so it cannot take a key too. See docs/users.md#roots-session."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :any]]}
  [db]
  (let [tx (tpl/request-tx db (tpl/from (tpl/templates :user) {:seon.task/ns 'request :seon.task/name "root" :seon.task/plan root-session-plan}))]
    (if (contract/error? tx)
      (throw (ex-info (str "root's session task was refused: " (:seon.error/message tx)) tx))
      ;; started by the user: the user's tree holds root's session and what it launches (docs/users.md#who-started-whom); drawn as a
      ;; stage (seon.render.ui/stage, its own render, which a person turns back to the conversation): the page a fresh system opens on
      (let [user (task/sender (cfg/value :seon.config.root/user-name))]
        (into [{:db/id "root-session" :seon.agent/key "root" :seon.agent/root [:seon.root/id :root] :seon.ctx/pattern [:seon.db/agent-fact {:seon.agent/_agents :seon.db/user-fact}]
                :seon.render/hiccup 'seon.render.ui/stage}
               (assoc user :seon.agent/agents "root-session")]
              (conj (vec tx) {:seon.task/id "request/root" :seon.task/agent "root-session" :seon.task/outcome :parked :seon.task/started-by user}))))))

(defn root-session-boot-tx
  "At boot, root's session: made when main has none (`root-session-tx`; the owner's ruling, root's agent is there from boot), else
  nothing: its pattern names its role's, so an edit of the agent's or the user's pattern reaches it with no write. See
  docs/users.md#roots-session."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :any]]}
  [db] (if (pdb/eid db :seon.agent/key "root") [] (root-session-tx db)))

(defn import-tree!
  "Main's program on conn brought to the source tree {:root :dirs}, only what it changed since root's :seon.root/source fact
  (import-of), written with the fact in one transaction while this is the store's only process (a seed); nothing when main
  holds its digest, and :error saying why when it can't import. t0: the caller's phase start, the fact's :ms. Answers
  {:imported? :digest :error}. See docs/source.md#indexing-a-tree."
  {:malli/schema [:=> [:cat :seon.host/conn [:map [:root :string] [:dirs [:sequential :string]]] :int] :map]}
  [conn {:keys [root dirs] :as source} t0]
  (let [held (:seon.root/source (d/pull @conn [:seon.root/source] [:seon.root/id :root]))
        r (fx/read-source {:req (cond-> {:root root :dirs dirs}
                                  ;; only what the tree changed since its last import: what main merged and the tree never held stays main's
                                  (and (:digest held) (:tree held)) (assoc :since (:tree held) :since-dirs (:dirs held [])))})
        {:keys [digest tx fact error]} (import-of @conn held r source t0)]
    (cond
      (contract/error? r) {:imported? false :error (select-keys r [:seon.error/type :seon.error/operation :seon.error/message])}
      error {:imported? false :error error}
      (nil? tx) {:imported? false :digest digest}
      :else (do (d/transact conn {:tx-data (conj tx {:seon.root/id :root :seon.root/source fact})})
                {:imported? true :digest digest}))))

(defn tasks-of
  "The task files' texts ({file name text}, a name being its owning namespace and .edn) as seon.task/load-tx takes them:
  {owning-ns declarations or error}. Each declaration is one :whole task (seon.template/request) that merges itself, so a
  need on another file's task is a need on it; the kind is derived here and never written in a file. See docs/tasks.md#task-files."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:map-of :symbol :any]]}
  [texts]
  (let [read (into (sorted-map)
                   (for [[n text] texts
                         :let [ns-sym (symbol (subs n 0 (- (count n) 4)))]]
                     [ns-sym (task/file-declarations ns-sym text)]))
        refused (into {} (filter (comp contract/error? val)) read)]
    (if (seq refused)
      read
      (merge (zipmap (keys read) (repeat []))
             (group-by :seon.task/ns (into [] (mapcat tpl/request) (apply concat (vals read))))))))

(defn task-files
  "The task files under dir (:seon.config.root/tasks-dir by default), read (tasks-of). See docs/tasks.md#task-files."
  {:malli/schema [:function [:=> [:cat] [:map-of :symbol :any]] [:=> [:cat :string] [:map-of :symbol :any]]]}
  ([] (task-files (cfg/value :seon.config.root/tasks-dir)))
  ([dir] (tasks-of (lib/dir-texts dir ".edn"))))

(defn changed-tx
  "The facts that bring db's index of the tree up to date with the files that changed (changed: {relative path text}, a
  removed file's text nil; modes: {path git mode}, a mode kept only for a file the allow-list names, as a read keeps it),
  as a seed indexes them and in its order (seed!): the source dirs' files (seon.fx/source-file?: seon.source/refresh-tx,
  seon.source/modes-tx), then on the db those make the wiki's pages
  (seon.doc/load-tx; a removed page loads empty, so its sections go) and the task files (seon.task/load-tx of tasks-of; a
  removed file declares none, so its tasks are cancelled). A path of none of these (native code, deps.edn, system.edn, a
  gitlink) is a build input, which the binary's build takes. What root writes on main for a Claude session's landing
  (bin/land's bridge, a temporary one). tasks-dir: the tree's task files' directory, relative to the tree (the files' paths
  are; nil: the tree holds no task files this root reads), so what a path is follows the tree, not this process's own task dir (a test root's is under its runs).
  See docs/source.md#a-landing-enters-main-as-data."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]] [:map-of :string :string] [:maybe :string]] :seon.db/tx-data]}
  [db changed modes tasks-dir]
  (let [under? (fn [p d] (if (= "." d) (not (str/includes? p "/")) (and (str/starts-with? p (str d "/")) (not (str/includes? (subs p (inc (count d))) "/")))))
        program? (fn [p] (and (fx/source-file? p) (some #(str/starts-with? p (str % "/")) (cfg/value :seon.config.source/dirs))))
        wiki (cfg/value :seon.config.root/wiki-dirs)
        pick (fn [f] (into {} (filter (comp f key)) changed))
        ;; a mode is a fact of a file the allow-list names (seon.source/modes-tx), as a read records it (seon.fx/read-source)
        code (into (source/refresh-tx db (pick program?)) (source/modes-tx db (into {} (filter #(and (program? (key %)) (cfg/file-kind (key %)))) modes)))
        db1 (pdb/speculate-db db code)
        pages (update-vals (pick (fn [p] (and (str/ends-with? p ".md") (some #(under? p %) wiki)))) #(or % ""))
        docs (if (seq pages) (doc/load-tx db1 wiki pages) [])
        db2 (pdb/speculate-db db1 docs)
        task-texts (into {} (for [[p t] changed :when (and tasks-dir (str/ends-with? p ".edn") (under? p tasks-dir))]
                              [(subs p (inc (count tasks-dir))) (or t "[]")]))
        tasks (if (seq task-texts) (task/load-tx db2 (tasks-of task-texts)) [])]
    (if (contract/error? tasks)
      (throw (ex-info (str "the landed task files were refused: " (:seon.error/message tasks)) tasks))
      (vec (concat code docs tasks)))))

(defn- seed!
  "Index the checkout into conn's branch while this is the store's only process: the source tree {:root :dirs}'s program
  (import-tree!; none when source is nil), then the wiki with its baseline (wiki-tx) until it states nothing more (its
  citations resolve to the program; a link to no section refuses the seed, :seon.db/no-target naming it), then the task
  files (task-files, seon.task/load-tx). A branch whose fact already
  holds the tree's digest is left as it is. Answers {:imported? :digest :forms}, with :tasks when the task files were
  refused, or :error. See docs/source.md#indexing-a-tree."
  {:malli/schema [:=> [:cat :seon.host/conn [:maybe [:map [:root :string] [:dirs [:sequential :string]]]] :int] :map]}
  [conn source t0]
  (let [r (if source (import-tree! conn source t0) {:imported? true})]
    (if-not (:imported? r)
      r
      (let [db (loop [db @conn]
                 (let [w (wiki-tx db (cfg/checkout) (baseline-of db #(fx/wiki-history {:req %}) (cfg/checkout) "docs"))]
                   (if (empty? w) db (recur (:db-after (d/transact conn {:tx-data w}))))))
            tasks (task/load-tx db (task-files))
            db (if (or (contract/error? tasks) (empty? tasks)) db (:db-after (d/transact conn {:tx-data tasks})))]
        (cond-> (assoc r :forms (count (d/datoms db :aevt :form/id)))
          (contract/error? tasks) (assoc :tasks tasks))))))

(defn landed-tx
  "What root writes on main for a change git landed (bin/land's bridge, docs/source.md#a-landing-enters-main-as-data):
  changed-tx of its changed files and modes, and root's :seon.root/source fact moved to the landed tree (its :tree,
  :commit and :digest, as an import of that tree records them), so the next re-index (my.root/import!) reads only what
  differs from that tree, not every file landed since the last import."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :string [:maybe :string]] [:map-of :string :string] :string :string] :seon.db/tx-data]}
  [db changed modes tree commit]
  (let [held (:seon.root/source (d/pull db [:seon.root/source] [:seon.root/id :root]))]
    ;; the tree's task files where the tree keeps them (its layout, :seon.config.source/tasks-dir), never this root's own task dir
    (cond-> (changed-tx db changed modes (cfg/value :seon.config.source/tasks-dir))
      held (conj {:seon.root/id :root
                  :seon.root/source (assoc held :tree tree :commit commit :digest (source-digest {:tree tree :pins (:pins held)} (:dirs held [])))}))))

(defn catch-up
  "What brings db's index of its checkout up to date with the files at root as they are now (docs/source.md#a-boot-catches-up-with-its-checkout):
  the files that differ from the tree db last indexed (its :seon.root/source :tree; seon.fx/changed-since) indexed as a landing is
  (landed-tx: code, pages and task files, the source fact moved to the tree now), less those left out and named under :skipped,
  which keep the fact at the tree it held, so the next catch-up reads them again: a file that does not read as its reader reads it
  (seon.fx/unreadable), a task file the changed task files are refused with (seon.task/load-tx), and a file a merged task main
  holds unsynced also changed (seon.problems/unsynced?: the checkout's text would replace the merge's). Answers {:tx :paths
  :skipped}, {:paths []} when nothing differs, nil when db holds no tree, or the read's error. Its caller writes :tx on the branch
  it brings up to date (boot!: main). opts: :tree, the tree to read what differs from (default db's held one), and :fact? false,
  which moves no source fact (changed-tx alone): a session's files written as a branch of the store, never main."
  {:malli/schema [:function [:=> [:cat :seon.host/db :string] [:maybe :map]]
                  [:=> [:cat :seon.host/db :string [:map [:tree {:optional true} :string] [:fact? {:optional true} :boolean]]] [:maybe :map]]]}
  ([db root] (catch-up db root {}))
  ([db root {:keys [fact?] :or {fact? true} :as opts}]
  (when-let [tree (or (:tree opts) (:tree (:seon.root/source (d/pull db [:seon.root/source] [:seon.root/id :root]))))]
    (let [r (fx/changed-since root tree)
          tasks-dir (cfg/value :seon.config.source/tasks-dir)]
      (cond
        (contract/error? r) r
        (empty? (:value r)) {:paths []}
        :else
        (let [unsynced (into {} (for [{:keys [e]} (d/datoms db :aevt :seon.task/declared) :when (problems/unsynced? db e)
                                      p (problems/changed-paths db e)]
                                  [p (:v (first (d/datoms db :eavt e :seon.task/id)))]))
              task? #(and (str/ends-with? % ".edn") (str/starts-with? % (str tasks-dir "/")))
              task-texts (into {} (for [[p t] (:value r) :when (task? p)] [(subs p (inc (count tasks-dir))) (or t "[]")]))
              refused (when (seq task-texts) (let [x (task/load-tx db (tasks-of task-texts))] (when (contract/error? x) (:seon.error/message x))))
              skipped (vec (for [[p t] (:value r)
                                 :let [why (cond (unsynced p) (str "merged task " (unsynced p) " changed it and main holds it unsynced: (my.root/sync! sys [\"" (unsynced p) "\"]), then start again")
                                                 (and t (fx/unreadable p t)) (fx/unreadable p t)
                                                 (and refused (task? p)) (str "the task files are refused: " refused))]
                                 :when why]
                             {:path p :reason why}))
              out (set (map :path skipped))
              changed (into {} (remove (comp out key)) (:value r))
              modes (into {} (remove (comp out key)) (:modes r))]
          {:paths (vec (keys changed)) :skipped skipped
           :tx (if (or (seq out) (not fact?))
                 (changed-tx db changed modes tasks-dir)
                 (landed-tx db changed modes (:tree r) (:commit r)))}))))))

(defn- kept-index!
  "The kept store at path (its :db) brought to a tree by index (a function of the connection answering import-tree!'s
  answer: a seed's, or the program alone), and keyed by the code that made it (program/index-code of roots under inputs, kept as
  :indexed-by on its :seon.root/source fact, read before the index, whose fact replaces it): when what the tree changed
  moves that code, or the store holds an index no key names, the store is removed and the tree indexed into it again from nothing, since a store brought up to
  date keeps what the old code made of each form whose text did not change, its id included
  (seon.program/account-diff-tx). Answers index's answer with the store's :program digest, or its error. See
  docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :string :seon.host/fn :seon.program/inputs [:vector :qualified-symbol]] :map]}
  [path index inputs roots]
  (let [once (fn []
               (let [{:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
                     fact #(:seon.root/source (d/pull @conn [:seon.root/source] [:seon.root/id :root]))
                     before (fact)
                     held (:indexed-by before)
                     r (index conn)
                     code (when (and (not (:error r)) (or (:imported? r) (nil? held))) (program/index-code @conn inputs roots))
                     ;; a store indexed before it held a key is no less stale: made by a code nobody recorded
                     moved? (and code (not= held code) (some? before))]
                 ;; the index's fact replaced the one held: the key is written again whenever the fact lacks it
                 (when (and (not moved?) (or code held) (not= (or code held) (:indexed-by (fact))))
                   (d/transact conn {:tx-data [{:seon.root/id :root :seon.root/source (assoc (fact) :indexed-by (or code held))}]}))
                 (let [out (cond (:error r) r
                                 moved? {:moved code}
                                 :else (assoc r :program (program/program-digest @conn) :indexed-by (or code held)))]
                   (pdb/sync! cfg)
                   (d/release conn)
                   out)))
        r (once)]
    (if (:moved r) (do (seon.lib/remove-tree! path) (once)) r)))

(defn seed-program!
  "Index the tree (source root and dirs from the config) into :db of the store at path (made when missing): seed!, so a
  root whose main is made from :db resumes it and indexes nothing (docs/machine.md#a-root-boots-from-a-seeded-store);
  the store kept between seeds and keyed by the code that indexes (kept-index!, as the build's index is). Answers
  {:digest :forms :ms :imported? :program} or the error that stopped it (a source the image cannot analyze, a read that
  failed). See docs/source.md#indexing-a-tree."
  {:malli/schema [:=> [:cat :string] :map]}
  [path]
  (let [t0 (profile/now-ns)
        root (get cfg/config :seon.config.source/root)
        r (kept-index! path #(seed! % {:root root :dirs (cfg/value :seon.config.source/dirs)} t0) (fx/inputs root) program/index-roots)
        out (if (:error r) (assoc (:error r) :imported? false) r)]
    (assoc out :ms (lib/r2 (profile/ms-since t0)))))

(defn built-program
  "The program of the tree at root as a seed indexes it, and the inputs bin/test keys verdicts by: {:program
  (seon.program/program-digest of the tree's index) :inputs (seon.fx/inputs, whose :tree is the tree's git id: what binary-current and the source fact compare)}, or the error of a read or an analysis.
  The index is the build's cache, a store other than the one store because it is regenerable from the tree at any time
  and root never reads it: kept between builds under base (bin/build's target/seon.index, keyed by the schema and layout:
  seon.db/kept-store!, so one of another layout is dropped, never opened), brought to the tree as a seed brings a store
  (import-tree!: only the files the tree changed) and keyed by the code that made it (kept-index!, roots index-roots by
  default), so a build pays for what changed. What bin/build records beside the binary it built (target/seon.program,
  `binary-program`). See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:function [:=> [:cat :string :string] :map] [:=> [:cat :string :string [:vector :qualified-symbol]] :map]]}
  ([root base] (built-program root base program/index-roots))
  ([root base roots]
   (let [inputs (fx/inputs root)
         r (kept-index! (str (pdb/kept-store! base) "/index") #(import-tree! % {:root root :dirs (cfg/value :seon.config.source/dirs)} (profile/now-ns)) inputs roots)]
     (if (:error r) (:error r) {:program (:program r) :inputs (assoc-in inputs [:config :seon.env/index] (or (:indexed-by r) "none"))}))))

(defn binary-program
  "What the running binary was built from, as bin/build recorded it beside the binary (target/seon.program,
  `built-program`'s answer), or nil when it recorded none. Read once, at boot. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (let [f (java.io.File. (str (cfg/checkout) "/target/seon.program"))]
    (when (.isFile f) (sedn/decode (slurp f)))))

(defn held-inputs
  "The inputs of the files this system's checkout holds, as bin/root start (or bin/update's no-restart landing) last computed
  them where git runs (the binary's `inputs` mode, seon.fx/inputs of the source root, else the checkout), read from its runs
  dir (runs/root-inputs.edn): what a boot hands its agents (booted-source), and what a landing that does not restart hands
  them again for the program it brings in. nil when none was written (a root under a starter: a test's). Root runs no git,
  since clojure.java.shell's futures are threads root does not declare (seon.root-test/root-threads-are-what-root-declares).
  See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (let [f (java.io.File. (str (cfg/value :seon.config.root/runs-dir) "/root-inputs.edn"))]
    (when (.isFile f) (sedn/decode (slurp f)))))

(defn booted-source
  "Root's :seon.root/source fact held, as this boot (id boot) states it: the git tree the binary was built from (built,
  `binary-program`'s :tree, as :binary-tree) and its program (:binary), absent when bin/build recorded none. When that tree is
  held's :tree, the one main was last brought to from outside (an import, a landing; what agents merged since is main's own and
  never a mismatch), the binary's inputs with the config in force (config-input, seon.fx/config-input), which agents key their
  test results by (seon.agent/test-inputs), and :program the binary's, the program those inputs describe; otherwise :program is
  main's at boot (main-digest, seon.program/program-digest) and no inputs. Another tree is seon.problems/stale-binary's row
  and refuses spawns: stale, stale-binary's answer at this boot, is held as :stale-binary for the row. A binary built from
  another tree and current for held's by reach (stale nil: what differs reaches nothing root runs, seon.root/binary-current)
  hands agents current-inputs, the inputs of held's tree (seon.fx/inputs of it), never its own: a binary current for
  another tree must not key verdicts by its own tree. Never imported. See docs/source.md#a-boot-resumes-from-the-db."
  {:malli/schema [:=> [:cat :map :string [:maybe :map] :string :map [:maybe :map] [:maybe :map]] :map]}
  [held boot built main-digest config-input stale current-inputs]
  (let [one? (and (get-in built [:inputs :tree]) (= (get-in built [:inputs :tree]) (:tree held)))
        ;; the inputs of what this boot read come first (bin/root start's, runs/root-inputs.edn): they are the files the
        ;; system judges, whatever tree id the build or the last landing recorded; a root started with none (a test's) keys
        ;; by its binary's record when that is held's tree
        inputs (or current-inputs (when one? (:inputs built)))]
    (cond-> (-> held (dissoc :inputs :binary :binary-tree :stale-binary) (assoc :boot boot :program (if one? (:program built) main-digest)))
      (:program built) (assoc :binary (:program built))
      (get-in built [:inputs :tree]) (assoc :binary-tree (get-in built [:inputs :tree]))
      ;; stale-binary's answer at this boot: what the :stale-binary row shows (seon.problems/stale-binary)
      stale (assoc :stale-binary stale)
      inputs (assoc :inputs (update inputs :config merge config-input)))))

(defn- build-input?
  "Is changed path p (with its git modes) something the binary bakes and no load of the db brings: a pin (a gitlink),
  native code, deps.edn, bin/build, .gitmodules, or a class of resource the build compiles in (seon.fx/env-class: the
  schemas, system.edn) or the shipped config. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :string :string :string] :boolean]}
  [p old-mode new-mode]
  (boolean (or (= "160000" old-mode) (= "160000" new-mode)
               (str/starts-with? p "native/") (#{"deps.edn" "bin/build" ".gitmodules"} p)
               (str/starts-with? p "resources/seon/config/")
               (#{:seon.env/schemas :seon.env/system :seon.env/deps} (fx/env-class p)))))

(defn binary-current
  "Whether a binary built from built's tree (binary-program's record, its :inputs :tree) runs tree's program as tree
  would have it built: nil when it does, else {:binary its tree :tree tree :reaches [what differs that it bakes]}. Equal
  trees are nil, and so is no tree (main holds no source fact: nothing known differs); a binary that recorded no tree
  against a main that holds one is unknown, never current ({:missing :binary-tree}). What differs is git's changed paths between the two trees: a build
  input (build-input?) is not current; a source file under src/ is not current when a var defined by one of its forms
  that changed (its text on db, main holding tree's program, is not in the file as the binary's tree has it) is in
  root-reach of db's reach graph, or when db holds no namespace of it (added or gone: what it held is unknown); a path
  of none of these that does not run from the checkout or main (tests, the wiki, task files, bin/'s scripts) is not current. Anything else (tests, the wiki, task files, agent-side code) is current: a process
  loads it from main's db. The one rule a spawn refusal, the stale-binary row and the gate's build all ask.
  Cost: one git diff, one reach graph of db (or g, when the caller holds one), O(changed files' forms).
  See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:maybe :map] [:maybe :string]] [:maybe :map]]
                  [:=> [:cat :seon.host/db [:maybe :map] [:maybe :string] [:maybe :map]] [:maybe :map]]]}
  ([db built tree] (binary-current db built tree nil))
  ([db built tree g]
   (let [from (get-in built [:inputs :tree])]
     (cond
       ;; no source fact: main was never brought to a tree, so nothing is known to differ
       (nil? tree) nil
       ;; a binary that recorded no tree, against a main that holds one, is unknown: never current
       (nil? from) {:binary nil :tree tree :reaches [] :missing :binary-tree}
       (= from tree) nil
       :else
       (let [changed (fx/tree-diff (cfg/checkout) from tree)]
         (if (contract/error? changed)
           ;; a diff git cannot make is no answer of current: the error, which a caller reads as not current
           changed
           (let [inputs (vec (for [[p om nm] changed :when (build-input? p om nm)] p))
                 src (for [[p] changed :when (and (str/starts-with? p "src/") (str/ends-with? p ".clj"))] p)
                 ;; what runs from the checkout or from main, never the binary: tests, the wiki, task files, bin/'s scripts
                 elsewhere? (fn [p] (or (str/starts-with? p "test/") (str/ends-with? p ".md") (str/starts-with? p "resources/seon/tasks/")
                                        (and (str/starts-with? p "bin/") (not= p "bin/build"))
                                        ;; a path the tree's id leaves out (:seon.config.source/tree-excludes): no build reads it
                                        (some #(or (= p %) (str/starts-with? p (str % "/"))) (cfg/value :seon.config.source/tree-excludes))))
                 ;; a changed path the rule cannot place (the web assets the build compiles, anything else) is not current
                 unplaced (vec (for [[p om nm] changed :when (not (or (build-input? p om nm) (some #{p} src) (elsewhere? p)))] p))
                 g (or g (program/reach-graph db (fx/declared-refs system/built-text)))
                 reach (program/root-reach g cfg/config)
                 ns-of (fn [p] (d/q '[:find ?n . :in $ ?p :where [?e :ns/file ?p] [?e :ns/name ?n]] db p))
                 ;; a form changed when its text on db is not in the file as the binary's tree has it (required!'s test)
                 changed-vars (fn [n p] (let [old (fx/tree-file (cfg/checkout) from p)]
                                          (for [[id src] (d/q '[:find ?id ?src :in $ ?n :where [?ns :ns/name ?n] [?f :form/ns ?ns] [?f :form/id ?id] [?f :form/src ?src]] db n)
                                                :when (not (and old (str/includes? old src)))
                                                [k s] (:defines (get (:rows g) id)) :when (= :var k)] s)))
                 hits (vec (for [p src :let [n (ns-of p)] v (if n (filter reach (changed-vars n p)) [(symbol p)])] v))
                 reaches (-> inputs (into unplaced) (into hits))]
             (when (seq reaches) {:binary from :tree tree :reaches reaches}))))))))

(defn- previous-end
  "How the root before this boot on main ended: the stop fact it wrote, or, when it wrote
  none (SIGKILL, a crash: nothing could be written), :unclean, found now. nil on a main no
  root ran on."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.root/end]]}
  [db]
  (let [{:seon.root/keys [boot stopped]} (d/pull db [:seon.root/boot :seon.root/stopped] [:seon.root/id :root])]
    (when boot
      (if (= boot (:boot stopped)) stopped {:boot boot :cause :unclean :at-ms (System/currentTimeMillis)}))))

(defn- boot!
  "Root's boot up to its first thread, on the main carrier (jolt.host/main-carrier-run!: one OS
  thread drives every fiber, so a go block a library starts is a fiber; a future is an OS thread of its
  own, and must have ended before the fork, which seon.lib/fork! refuses beside another thread): main opened on the
  store at path and resumed, caught up with the files of its source tree that changed since main last indexed it (catch-up; none
  read when nothing changed, and nothing for a root given no source tree), or, when nothing was ever indexed into it nor a root
  booted on it, the checkout indexed into it first (seed!); main's config held, its program digested (seon.program/program-digest), the
  contracts instrumented, the warm paths run and the heap made static, then the fork server forked from it (which preloads
  main's indexes for its agents: seon.fork-server/main), sharing all of that copy-on-write while this is the one thread
  there is (docs/failure-points.md, physics). Its return starts the process's threads (Jolt's carriers, timer and poller;
  main-carrier-run! returns into an ordinary process), and root's follow. open!'s answer with main's :db as forked, the
  fork server's :pid and :fd, the seed's answer when it seeded, main's :digest and each phase's ms. See
  docs/source.md#a-boot-resumes-from-the-db."
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string :int :seon.db/branch [:maybe :map] :int] :map]}
  [backend path datoms main source t0]
  (jolt.host/main-carrier-run!
    (fn []
      (let [;; first of all: no process holds the store yet (docs/storage.md#collection)
            compacted (when (and (= :lmdb backend) (cfg/value :seon.config.store/sole-writer?)) (pdb/compact-if-due! path))
            sole? (and (= :lmdb backend) (cfg/value :seon.config.store/sole-writer?))
            opened (pdb/open! backend path datoms main)
            ;; before any agent: a tree's branch left by an earlier root's end, its last run cut short, is dropped as that run would have
            dropped-trees (pdb/drop-trees! (:conn opened) main)
            ;; still the only process: old snapshots swept when enough commits have landed, and the freed pages compacted at once
            retained (when sole? (pdb/retain! (:conn opened) (cfg/value :seon.config.store/keep-commits)))
            compacted (if (pos? (:swept retained 0))
                        (do (d/release (:conn opened)) (pdb/compact-if-due! path))
                        compacted)
            {store-cfg :cfg conn :conn :as opened} (if (pos? (:swept retained 0)) (pdb/open! backend path datoms main) opened)
            ;; each agent's stderr goes to a file here (seon.agent/stderr-path), read at its death
            agents-dir (pdb/agents-dir store-cfg)
            _ (.mkdirs (java.io.File. agents-dir))
            open-ms (profile/ms-since t0)
            t1 (profile/now-ns)
            ;; a main nothing was ever indexed into or booted on is indexed now, once; every other boot resumes it
            seeded (when (every? nil? (vals (d/pull @conn [:seon.root/source :seon.root/boot] [:seon.root/id :root])))
                     (seed! conn source t1))
            ;; every other boot of a root with a source tree catches main up with the files that changed since main last indexed it
            ;; (docs/source.md#a-boot-catches-up-with-its-checkout); a root given no source (a test's) resumes as it was
            caught (when (and source (not seeded) (cfg/value :seon.config.root/catch-up-on-boot))
                     (let [c (catch-up @conn (:root source))]
                       (if (seq (:tx c)) (-> c (dissoc :tx) (assoc :t (:max-tx (:db-after (d/transact conn {:tx-data (:tx c)}))))) c)))
            seed-ms (profile/ms-since t1)
            t2 (profile/now-ns)
            ;; main's own config, before anything below reads one and before the fork server inherits it
            config-errors (program/hold-config! @conn)
            digest (program/program-digest @conn)
            digest-ms (profile/ms-since t2)
            contracts (contract/instrument! contract/boot-nss)
            ;; a binary built from another tree than main's: main's tree's inputs, which booted-source hands agents when the
            ;; binary is current for it by reach, as the plan that judged it current recorded them beside it (target/seon.inputs,
            ;; keyed by their :tree). A file read: root runs no git, since clojure.java.shell's futures are threads root does not
            ;; declare (seon.root-test/root-threads-are-what-root-declares, batch 28)
            ;; the inputs of the files this boot read, as bin/root start computed them just before it (runs/root-inputs.edn,
            ;; in its runs dir, written fresh each start, none under a starter: root runs no git, since clojure.java.shell's futures are threads root does not declare,
            ;; seon.root-test/root-threads-are-what-root-declares, batch 28); none under a starter (a test's root)
            main-inputs (held-inputs)]
        (warm! conn)
        (lib/raise-open-files! (+ 256 (* 2 (cfg/value :seon.config.root/max-agents))))
        (lib/static!)
        (let [db @conn]
          (merge opened (fs/fork-server db store-cfg)
                 {:db db :agents-dir agents-dir :open-ms open-ms :compacted compacted :retained retained :dropped-trees dropped-trees
                  :seeded seeded :caught-up caught :seed-ms seed-ms :config-errors config-errors :digest digest :digest-ms digest-ms :contracts contracts :main-inputs main-inputs}))))))

(defn- thrown-in
  "The fn throwable ex was thrown in: clojure.main's triage of its top frame (as seon.repl/located reads an eval's), a
  qualified symbol, or nil when the triage names none."
  {:malli/schema [:=> [:cat :any] [:maybe :symbol]]}
  [ex] (let [in (:clojure.error/symbol (clojure.main/ex-triage (assoc (Throwable->map ex) :clojure.error/phase :execution)))]
         (when (qualified-symbol? in) in)))

(defn- flow-error
  "Flow's error-channel value e as the api's :error message: the proc, the throw's message and
  data, the message its step took (:msg), and the fn it was thrown in (:in, clojure.main's triage
  of its top frame, as seon.repl/located reads an eval's): a case meeting nil said only \"No
  matching clause: \", 255 times from the reconciler, and where it threw was nowhere."
  {:malli/schema [:=> [:cat :map] :map]}
  [e]
  (let [ex (::flow/ex e)
        in (when ex (thrown-in ex))]
    (cond-> {:op :error :pid (::flow/pid e) :message (or (some-> ex ex-message) (str e)) :data (some-> ex ex-data contract/bounded) :msg (::flow/msg e)}
      (qualified-symbol? in) (assoc :in in))))

(defonce ^:private uncaught
  ;; where an error no thread, fiber or go body caught goes (uncaught!): kept under :early until this process's root listens,
  ;; which start! fails on; then root's :back, as a defect the api records on main
  (atom {:back nil :early []}))

(defn uncaught-message
  "An error ex that no one caught, in thread (nil: a go or fiber body, which the runtime reports with no thread), as the api's
  :error message: :pid :uncaught and its own typed defect (:seon.root/proc-defect, its operation the fn it was thrown in), so it
  lands on main as any proc's defect does, named, never only on stderr. See docs/errors.md#nothing-crashes-and-nothing-hangs."
  {:malli/schema [:=> [:cat :any :any] :map]}
  [thread ex]
  (let [in (thrown-in ex)
        where (if thread (str "thread " (.getName ^Thread thread)) "a go or fiber body")
        msg (str "uncaught in " where ": " (or (ex-message ex) (str ex)))]
    (cond-> {:op :error :pid :uncaught :message msg
             :data (contract/error :seon.root/proc-defect (or in 'clojure.core.async/go) msg
                                   (cond-> {:pid :uncaught} (ex-data ex) (assoc :data (contract/bounded (ex-data ex)))))}
      in (assoc :in in))))

(defn- uncaught!
  "The default uncaught-exception handler's body (start! sets it): ex's message to root's :back once root listens, else kept
  (:early) for start! to fail on. A :back too full to take it says so on stderr, never silently."
  {:malli/schema [:=> [:cat :any :any] :nil]}
  [thread ex]
  (let [m (uncaught-message thread ex) {:keys [back]} @uncaught]
    (cond (nil? back) (swap! uncaught update :early conj m)
          (a/offer! back m) nil
          :else (binding [*out* *err*] (println "uncaught, and root's :back is full:" (:message m)))))
  nil)

(defn- catch-uncaught!
  "This process's default uncaught-exception handler set to uncaught!, its errors kept until root listens (start! calls it
  first): the one place a thread's, a fiber's or a go body's uncaught error is taken, so none is only printed."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (reset! uncaught {:back nil :early []})
  (Thread/setDefaultUncaughtExceptionHandler (reify Thread$UncaughtExceptionHandler (uncaughtException [_ t ex] (uncaught! t ex))))
  nil)

(defn start!
  "Boot the control process, the fork server and the root graph on its main, a branch of
  the one store (DECISIONS, Branches as in production). opts:
  {:path dir (the store, created when missing, holding the schema and, given :datoms n,
   about n datoms of an example program on :db; a root holds no program it was not given)
   :main branch (root's main; an existing branch is resumed; :db is the seed itself)
   :backend :lmdb|:file :log? true :max-running n (parallel runs) :provider p (the
   runs' model) :started-by \"ns/test\" (the test whose process started it, :seon.root/started-by) :stall-ms (the stall bound, :seon.config.agent/stall-ms by default, for the checks that
   move it) :source s}. s: Seon's own source
   is the program, {:root dir :dirs [\"src\"] :branch \"jolt/seon-merges\"}: indexed into a main
   that holds none (boot!'s seed; every other boot resumes main and reads no file), and each merge
   that changes it is committed on branch (the reconciler's write-back); absent, the config's
   :seon.config.source/* (no :seon.config.source/root: no source). What opts leave out is the config's: :seon.config.root/store-path,
  :seon.config.root/main (resumed; made from :db when the store has none), :seon.config.store/backend, :seon.config.agent/max-running and the provider of
  :seon.config.ai/*. The config in force is written on main's root entity."
  {:malli/schema [:=> [:cat :map] :seon.root/sys]}
  [{:keys [datoms log? path main backend max-running provider stall-ms source started-by] :or {datoms 0}}]
  (lib/ignore-sigpipe!)
  (when-not log? (trove/set-log-fn! nil))
  ;; first of all, before anything spawns: an error no thread, fiber or go body catches reaches root (uncaught!), never only stderr
  (catch-uncaught!)
  (let [t0 (profile/now-ns)
        ;; agents write their own branches, so the store is shared: the one durable store
        ;; (the kept runs store, bin/check's, a test's). This root resumes its main, a branch
        ;; of the store's seed, and names its agents' branches under it; a run's record is
        ;; main's commit history (owner: one main, resumed)
        path (or path (cfg/value :seon.config.root/store-path))
        main (or main (keyword (cfg/value :seon.config.root/main)))
        backend (or backend (cfg/value :seon.config.store/backend))
        max-running (or max-running (cfg/value :seon.config.agent/max-running))
        source (or source (when-let [r (get cfg/config :seon.config.source/root)]
                            {:root r :dirs (cfg/value :seon.config.source/dirs) :branch (cfg/value :seon.config.source/branch)}))
        {store-cfg :cfg conn :conn recovered? :recovered? :keys [db agents-dir open-ms seeded caught-up seed-ms config-errors digest digest-ms contracts pid fd compacted retained dropped-trees main-inputs]}
        (boot! backend path datoms main source t0)
        recovered (if recovered? (pdb/of-main main (d/branches conn)) [])
        ;; this root's boot id (runs of an earlier boot are reaped); its fact is written
        ;; through the writer once the graph runs, and that commit is the reconciler's first pass
        boot (lib/unique-id)
        {in-force :config} (cfg/in-force)
        in-force-sources (cfg/boot-sources)
        provider (or provider (cfg/provider in-force))
        ;; the config in force, written on main's root entity with the boot fact; the merge gate's
        ;; checkout, when none is declared, is this root's own source root
        config (cond-> (-> (cfg/with-provider in-force provider) (assoc :seon.config.agent/max-running max-running))
                 (and (nil? (get in-force :seon.config.source/gate-checkout)) (:root source))
                 (assoc :seon.config.source/gate-checkout (:root source)))
        ;; then the GC's sweeper threads, a declared number (Chez would grow them by timing)
        sweepers (lib/gc-sweepers! (cfg/value :seon.config.root/gc-sweepers))
        ;; root's own, set after the fork server is forked: the fork server and its agents keep
        ;; Jolt's default, whose nursery a lower ratio would also cut (docs/machine.md#roots-heap)
        _ (lib/max-heap-free-ratio! (cfg/value :seon.config.root/max-heap-free-ratio))
        events-in (a/chan (a/sliding-buffer 8192))
        ;; every event, for the API's taps (seon.root/events) and the render proc's (W2)
        events-mult (a/mult events-in)
        chans {:ext (a/chan 1024) :back (a/chan 4096) :to-writer (a/chan (cfg/value :seon.config.root/writer-buf))
               :fork-server-ev (a/chan 4096)
               ;; outside agents' runs handed back for judgement (seon.server)
               ;; and waits on main asking the reconciler for its next quiet (await-db): one channel,
               ;; so a wait asked after a judge is taken after it
               :judge (a/chan 64)}
        ;; whether the binary is current for main, asked once: the boot view (no-room's refusal) and the source fact (the row) hold it
        stale (stale-binary db)
        view {:agents {} :writer {:tx-count 0} :stats {}
              :fork-server {:agents-forked 0 :pid pid :status :up :restarts 0}
              :store {:backend backend :path path :main main :agents-dir agents-dir :recovered? (boolean recovered?)
                      :recovered-branches recovered :open-ms (lib/r2 open-ms)}
              :boot {:gc-sweepers sweepers :compacted compacted :retained retained :dropped-trees dropped-trees :seeded seeded :caught-up caught-up :seed-ms (lib/r2 seed-ms)
                     :config-errors config-errors :digest digest :digest-ms (lib/r2 digest-ms)
                     :contracts (select-keys contracts [:armed :unchanged :invalid :ms]) :instrument (if contract/on? :on :off)
                     :stale-binary stale}}
        graph-p (promise)
        root-procs-p (promise)
        ;; this root's handle, for the merge gate's :tests effect, which proves its candidate through prove-tree! (bound as
        ;; :prove-tree): delivered below, before the graph is resumed, so no effect runs before it
        sys-p (promise)
        ;; what the declaration (resources/seon/system.edn) names as :bind/<name>
        bindings (merge (select-keys chans [:ext :back :to-writer :fork-server-ev :judge])
                        {:view view :graph graph-p
                         :system root-procs-p :events events-in :events-mult events-mult
                         :conn conn :store-cfg store-cfg :durable? (= :lmdb (get-in store-cfg [:store :backend]))
                         :fork-server-pid pid :fork-server-fd fd :boot boot :provider provider :source source
                         :api-from-writer-buf (+ (cfg/value :seon.config.root/writer-buf) (cfg/value :seon.config.root/writer-batch) (cfg/value :seon.config.root/api-writes))
                         :cron-tick (a/chan 1) :full-run-at (cfg/value :seon.config.proofs/full-run-at)
                         :stall-ms (or stall-ms (cfg/value :seon.config.agent/stall-ms)) :reconciler-buf (cfg/value :seon.config.root/reconciler-buf) :prove-main-buf (a/sliding-buffer 1) :render-main-buf (a/sliding-buffer 1) :web-streams-buf (a/sliding-buffer (cfg/value :seon.config.web/streams-buf))
                         :prove-tree (fn [on] (prove-tree! @sys-p on))
                         :item-workers (:workers (seon.prove/config-admission)) :model-workers (cfg/value :seon.config.ai/max-in-flight) :model-rate (when (pos? (cfg/value :seon.config.ai/rate)) (cfg/value :seon.config.ai/rate))
                         :web-port (cfg/value :seon.config.web/port)
                         :vectors-main-buf (a/sliding-buffer 1) :embed-batch (cfg/value :seon.config.vector/batch)
                         :embed-model (vector/model-id (cfg/value :seon.config.vector/model))
                         :embed-texts (cfg/value :seon.config.vector/texts)})
        decl (system/read-decl)
        built (system/build decl bindings nil)
        root-procs (:procs built) root-conns (:conns built)
        _ (deliver root-procs-p built)
        g (flow/create-flow {:procs root-procs :conns root-conns :stop-timeout-ms (cfg/value :seon.config.root/flow-stop-ms)})
        _ (deliver graph-p g)
        {root-errors :error-chan} (flow/start g)
        ;; Flow's error channel (every proc's, agents included) piped into :back, each defect
        ;; fed to the api as data, with the message the step took (:msg), which the api
        ;; answers; :back outlives it
        _ (a/pipe (a/pipe root-errors (a/chan 1 (map flow-error))) (:back chans) false)
        sys (merge chans {:cfg store-cfg :conn conn :graph g :t0 t0
                          :store-path path :boot boot
                          :bindings bindings
                          :events-in events-in :events-mult events-mult})
        _ (deliver sys-p sys)
        ]
    (flow/resume g)
    ;; dataflow start: the boot fact's commit wakes the reconciler on main as it is; a root
    ;; whose boot fact was refused cannot start anything, so its error ends the boot
    ;; with it, how the root before it on main ended (its stop fact, or :unclean)
    (let [prev (previous-end db)
          held (:seon.root/source (d/pull db [:seon.root/source] [:seon.root/id :root]))
          r @(call! sys {:op :write :req {:op :tx :branch :db :tx-data (cond-> (-> (task/boot-tx boot (cfg/value :seon.config.root/user-name)) (into (task/earlier-agents-tx db)) (into (task/config-tx config in-force-sources))
                                                                                  (into (root-session-boot-tx db)))
                                                                         prev (conj {:seon.root/id :root :seon.root/previous-end prev})
                                                                         ;; the program the binary was built from beside main's: one program, or seon.problems/stale-binary's row
                                                                         held (conj {:seon.root/id :root :seon.root/source (booted-source held boot (binary-program) digest (fx/config-input cfg/config (cfg/checkout)) stale
                                                                                                    ;; the inputs of what this boot read, whatever tree id a build recorded
                                                                                                    (when-not stale main-inputs))})
                                                                         ;; the test that started this boot (a test's main is its own: no other boot resumes it)
                                                                         started-by (conj {:seon.root/id :root :seon.root/started-by started-by}))}})]
      (when (contract/error? r) (throw (ex-info (str "root's boot fact was refused: " (:seon.error/message r)) r))))
    ;; what the first agent's fork would wait for is done now, as main stands (seon.fork-server/preload); nothing waits on its answer
    (call! sys {:op :fs :req {:op :preload :t (:max-tx @conn)}})
    ;; an error no one caught while this root started fails its start, its errors in the answer; from here they reach :back
    (let [early (:early (swap! uncaught assoc :back (:back sys)))]
      (when (seq early)
        (stop! sys :uncaught-at-start)
        (throw (ex-info (str "root's start met " (count early) " uncaught error(s): " (:message (first early))) {:uncaught early}))))
    sys))

;; ====================================================================================
;; The API: callers outside the graph. Each call puts one request on :ext.
;; ====================================================================================

(defn call!
  "Put m (with a fresh reply promise) on the api; a promise. After stop! the
  api is closed and the promise answers :seon.root/no-agent (\"root stopped\")."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.root/request] :seon.host/promise]}
  [sys m] (step/ask-edge (:ext sys) m (gone nil "root stopped")))

(defn view-numbers
  "Root's view v (the api's :state answer) with its windows as the numbers shown: the
  writer's batch p50, main's transactions per group commit (>= 1: one commit takes every
  queued transaction), the fork server's lag and fork p50s; the windows themselves dropped.
  The one decoration: `state` and the render proc's system value both read it."
  {:malli/schema [:=> [:cat :map] :map]}
  [v]
  (let [{:keys [batches commits batched-tx lag-ms fork-ms]} (:stats v)
        tpc (when (and commits (pos? commits)) (lib/r2 (/ (double (or batched-tx 0)) commits)))]
    (-> v
        (update :writer #(-> % (dissoc :recent :batch-ms) (assoc :batch-p50-ms (lib/p50 (:batch-ms %)) :tx-per-commit tpc :commits commits)))
        (update :fork-server assoc :lag-ms (lib/p50 lag-ms) :fork-ms-p50 (lib/p50 fork-ms))
        (update :store merge (when batches {:batches batches :commits commits :tx-per-commit tpc})))))

(defn state
  "The current state value (plain EDN), from the api's view. With {:datoms true}, main's
  datom count too, taken here in the caller from main's db value (O(datoms): a poll
  should not ask for it)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys [:maybe :map]] :map]]}
  ([sys] (state sys nil))
  ([sys {:keys [datoms]}]
  (let [v @(call! sys {:op :state})
        now (profile/now-ns)
        recent (reduce + 0 (keep (fn [[t n]] (when (< (- now t) 1000000000) n)) (get-in v [:writer :recent])))
        db @(:conn sys)]
    ;; the api's answer passes through as itself: a defect is not decorated as a view
    (if (contract/error? v)
      v
      (-> (view-numbers v)
          (assoc-in [:writer :tx-per-s] (double recent))
          (assoc :main (cond-> {:t (:max-tx db)} datoms (assoc :datoms (count (d/datoms db :eavt)))))
          (update :store merge (pdb/map-use (:cfg sys))))))))

(defn errors
  "The api's fold of the errors it saw (seon.error/fold): the true counts, including repeats
  of written signatures that main does not hold; the api's defect when it could not answer."
  {:malli/schema [:=> [:cat :seon.root/sys] [:or :seon.error/fold :seon.error/base]]}
  [sys] @(call! sys {:op :errors}))

(defn events
  "A fresh tap of every event {:type :spawned|:ready|:reply|:tx|:merged-main|:tests|:killed|:ended|:dead ...}."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.host/chan]}
  [sys]
  (let [c (a/chan (a/sliding-buffer 1024))] (a/tap (:events-mult sys) c) c))

(defn request
  "The op (named as its fn below: spawn! clone! submit! again! back! ask! run-tests! kill!
  merge-main! message! release! opened! focus!) on agent id with args, as the api request it is: one table for these fns
  and the dashboard's calls (seon.web). a: the agent's entry in root's view, which
  merge-main! reads; an op the agent cannot take answers its error. A branch reaches main
  only through a merge task (seon.reconcile's octopus merge)."
  {:malli/schema [:=> [:cat :symbol [:maybe :seon.agent/id] [:maybe :map] [:maybe :map]] :map]}
  [op id args a]
  (let [live? (boolean (and a (not (gone-statuses (:status a)))))
        data-attrs (:data-attrs args :all)]
    (case op
      spawn! {:op :spawn :opts (or args {})}
      clone! {:op :clone :parent id :opts (or args {})}
      submit! (cond-> {:op :eval :id id :forms (:forms args)} (:ticks args) (assoc :ticks (:ticks args)) (:origin args) (assoc :origin (:origin args)))
      ;; a person's step button: the agent runs its eval again in place, the call itself no record of the conversation (origin :context)
      again! {:op :eval :id id :origin :context
              :forms (sedn/encode (cond (:to-here args) (list 'seon.repl/again-to (:eval args))
                                        (:code args) (list 'seon.repl/again (:eval args) (:code args))
                                        :else (list 'seon.repl/again (:eval args))))}
      back! {:op :back :id id :eval (:eval args)}
      ask! (if (str/blank? (:task args)) (refused 'seon.root/ask! "say what you would like done" {}) (cond-> {:op :ask :text (str/trim (:task args))} (:template args) (assoc :template (:template args))))
      ;; the agent proves its branch through the test service as root's judge does (seon.agent/prove!
      ;; :judge, which keys the run by its head's digest): recorded on main, one run with any other asker's
      run-tests! {:op :judge :id id}
      kill! {:op :kill :id id}
      message! {:op :message :id id :body (:body args)}
      ;; my.root/run!'s one transaction: the held task (:task) released, its next run the reconciler's
      release! {:op :write :req {:op :tx :branch :db :tx-data (task/execution-tx [(:task args)] :running)}}
      ;; a reader opened an eval of agent key's page: the fact on the agent's entity on main (opened!), named by a lookup ref:
      ;; it adds to an agent that exists, and an unknown key is refused, never an agent made of the one fact (an upsert map made one)
      opened! {:op :write :req {:op :tx :branch :db :tx-data [[:db/add [:seon.agent/key (:key args)] :seon.agent/opened (:eval args)]]}}
      focus! {:op :focus :key (:key args)}
      merge-main! (if live?
                    {:op :merge-main :id id :data-attrs data-attrs}
                    (absent a id))
      (refused 'seon.root/request (str op " is not one of root's requests") {}))))

(defn- agent-entry {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] [:maybe :map]]}
  [sys id] (get-in @(call! sys {:op :state}) [:agents id]))

(defn- request!
  "Call root's op on agent id with args (request): a promise of its answer, or of its
  error."
  {:malli/schema [:=> [:cat :seon.root/sys :symbol [:maybe :seon.agent/id] [:maybe :map]] :seon.host/promise]}
  [sys op id args]
  (let [r (request op id args (when (= 'merge-main! op) (agent-entry sys id)))]
    (if (contract/error? r) (doto (promise) (deliver r)) (call! sys r))))

(defn spawn!
  "Fork an agent from the fork server onto a new branch of main; answers its id once it is
  ready (its hello), so what is sent to it next reaches a live agent; an agent that ends first
  (refused, stopped, or died, with its exit status) throws that end. opts {:branch kw :task
  string}; an existing :branch is resumed; :commit (a uuid) with a new :branch restores the session at that commit of its branch,
  the answer of request! naming what is not restored (not-restored)."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.root/spawn-opts] :seon.agent/id]}
  [sys opts]
  (let [r @(request! sys 'spawn! nil opts)]
    (if (:id r) (:id r) (throw (ex-info (:seon.error/message r) r)))))

(defn clone!
  "Fork a live agent into a new agent on a branch of its branch. A promise of
  {:ok true :id new-id}, or its refused answer (the one error map)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise] [:=> [:cat :seon.root/sys :seon.agent/id :map] :seon.host/promise]]}
  ([sys agent-id] (clone! sys agent-id {}))
  ([sys agent-id opts] (request! sys 'clone! agent-id opts)))

(defn submit!
  "Send forms (a string) to the agent; a promise of the reply vector, one map per form.
  opts {:ticks n}: each form stops after n engine ticks of work (an outside or ui origin
  takes :seon.config.agent/form-ticks by default); {:origin :outside|:ui}: who asked, recorded
  with each evaluation."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id :string] :seon.host/promise] [:=> [:cat :seon.root/sys :seon.agent/id :string [:maybe :map]] :seon.host/promise]]}
  ([sys agent-id forms-string] (submit! sys agent-id forms-string nil))
  ([sys agent-id forms-string opts] (request! sys 'submit! agent-id (assoc opts :forms forms-string))))

(defn message!
  "Send the signed-in user's message body (a string) to the agent: one message fact on main through root's writer (`message-write`), which wakes it, whether or not its process lives; the agent's next act delivers it. A promise of the writer's answer for that fact's transaction, or refused when main holds no agent of that id or key. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id :string] :seon.host/promise]}
  [sys agent-id body] (request! sys 'message! agent-id {:body body}))

(defn release!
  "Release the held task task-id (my.root/run!'s one transaction): the reconciler runs its next
  run. A promise of the writer's answer."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.task/id] :seon.host/promise]}
  [sys task-id] (request! sys 'release! nil {:task task-id}))

(defn judge!
  "Hand an outside agent's task to the reconciler for judgement, as a turn loop's end
  is: proofs in a fork, the branch read, the agent retired. work {:task :env
  :agent}. nil."
  {:malli/schema [:=> [:cat :seon.root/sys :map] :nil]}
  [sys work] (a/>!! (:judge sys) (assoc work :op :judge)) nil)

(defn await-db
  "Main's first db value (now or after a later commit) for which (pred db) holds: a watch on
  main's connection runs pred once per commit. No deadline, no polling: what an outside caller
  waiting on a fact parks on (the server's :done, a test). Named (what, a description), the
  wait is on a fact the reconciler's work makes (a judge's verdict, its quiescence report),
  and it ends, too, when nothing in flight can make pred true: it asks the reconciler (on
  :judge, ask-edge: behind any judge handed it before) for its first quiet
  after main's head as of the ask, and when that comes with pred still false on main, answers
  :seon.root/quiet-before-true (its :t, and what, the wait's description). That is final for a
  fact only root's own work makes (a judge that has not moved by quiet never will: seon_done);
  a caller waiting on outside input (a user's reply, another writer) asks again. A root that
  stopped answers :seon.root/no-agent (root stopped). So it answers a db or one of those errors. Unnamed, a fact another proc makes (the
  vector proc's embeddings, a problems report): only a commit ends it, since the reconciler's
  quiet says nothing of that proc's work (task seon.root/a-wait-on-main-ends-when-nothing-can-make-it-true)."
  {:malli/schema [:function [:=> [:cat [:map [:conn :seon.host/conn]] ifn?] :seon.host/db]
                  [:=> [:cat [:map [:conn :seon.host/conn] [:judge {:optional true} :seon.host/chan]] [:maybe :string] ifn?]
                   [:or :seon.host/db :seon.root/quiet-before-true :seon.root/no-agent]]]}
  ([sys pred] (await-db sys nil pred))
  ([sys what pred]
  (let [stopped (gone nil "root stopped")
        k (keyword "seon.root" (str "await-" (lib/unique-id)))
        a (:wrapped-atom (:conn sys))
        p (promise)
        ;; the watch first, then the value: a commit between reading main and adding the watch
        ;; was seen by neither, and the wait went on until a later commit that never came (a
        ;; 48-minute hang of seon.e2e-test/await-quiet-ends-on-a-recorded-defect under load)
        _ (add-watch a k (fn [_ _ _ db] (when (pred db) (deliver p db))))
        db0 @(:conn sys)
        _ (when what (step/ask-edge (:judge sys) {:op :quiet :after (:max-tx db0)} {:stopped true} p))]
    ;; the watch goes whatever ends the wait (an interrupt of a caller whose client left, a pred
    ;; that threw on the current value)
    (try (when (pred db0) (deliver p db0))
         (let [r @p]
           (cond (:stopped r) stopped
                 ;; quiet: main as it is now, which a commit racing the answer may have made true
                 (:quiet r) (let [db @(:conn sys)]
                              (if (pred db)
                                db
                                (contract/error :seon.root/quiet-before-true #'seon.root/await-db
                                                (str "root went quiet at t " (:quiet r) " with this still false: " what)
                                                {:t (:quiet r) :waits-on what})))
                 :else r))
         (finally (remove-watch a k))))))

(defn defect
  "A root proc's defect (a :platform problem) whose record main wrote after t, or nil: a step
  that threw dropped its outputs, so what it carried never ends. With nsname, only one raised
  by an operation of that namespace (the reconciler's, for a run waiting on its judge). Facts,
  not clocks."
  {:malli/schema [:function [:=> [:cat :seon.host/db :int] [:maybe :map]] [:=> [:cat :seon.host/db :int [:maybe :string]] [:maybe :map]]]}
  ([db t] (defect db t nil))
  ([db t nsname]
   (some #(when (some (fn [dt] (> (:tx dt) t)) (d/datoms db :eavt (:e %) :seon.error/last-ms))
            (let [x (into {} (d/entity db (:e %)))]
              (when (or (nil? nsname) (= nsname (some-> (:seon.error/operation x) namespace))) x)))
         (filter #(= :platform (:v %)) (d/datoms db :aevt :seon.error/class)))))

(defn prove!
  "Prove a reference to a db value, ref (the shape a kept db value is: {:branch b} its head, {:branch b :commit c} or
  {:branch b :t t} a point in its history; docs/storage.md#a-kept-db-value-is-a-reference): the one call every prover asks
  (my.agent/prove, the server's :prove, bin/prove through prove-tree!, main's commit run), the tests of namespaces names
  (a set of symbols; none: all it selects). The run is the test service's (seon.prove), keyed by the digest of what it reads
  (seon.program/run-digest), so every asker of one program at one point is one run. Main at its head is proved as main
  (mode :main by default). Another branch at its head is proved by the agent holding it, or one spawned onto it (whose
  spawn loads the branch's program over main's, seon.program/load-diff!). A point in the past always forks: an agent of
  the branch is at its head, not at that point, so one is spawned on a new branch made at that commit (a :t is found as
  its commit, datahike.versioning/commit-at-tx), which loads that point's program, runs, and is ended. With on
  ({:checkout :tree}, a session's: prove-tree!), the service runs its process tests, check steps and build too, on that
  checkout (docs/merging.md#the-test-service). A promise of the run's answer ({:key :ran :reused :pass :fail :error :red
  :reports ...}), the same run for every asker of that key."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :map] :seon.host/promise]
                  [:=> [:cat :seon.root/sys :map [:set :symbol]] :seon.host/promise]
                  [:=> [:cat :seon.root/sys :map [:set :symbol] [:enum :turn :judge :main]] :seon.host/promise]
                  [:=> [:cat :seon.root/sys :map [:set :symbol] [:enum :turn :judge :main] [:maybe [:map [:checkout :string] [:tree :string]]]] :seon.host/promise]]}
  ([sys ref] (prove! sys ref #{}))
  ([sys ref names] (prove! sys ref names nil))
  ([sys ref names mode] (prove! sys ref names mode nil))
  ;; on: a session's checkout and its tree (prove-tree!), on which the service runs the run's process tests, steps and build
  ([sys ref names mode on]
   (let [{:keys [branch commit t]} ref
         main (pdb/conn-main (:conn sys))
         b (if (= :main branch) main branch)
         ask (fn [ag mode]
               (let [db (pdb/head (:conn sys) (if ag (get-in (state sys) [:agents ag :branch]) main))]
                 (call! sys (cond-> {:op :prove :branch (if ag (:branch (get-in (state sys) [:agents ag])) :main) :t (:max-tx db) :mode mode
                                     :digest (program/run-digest db)}
                              ag (assoc :agent ag) (seq names) (assoc :names names) on (merge (select-keys on [:checkout :tree]))))))]
     (cond
       ;; no branch: an agent root does not hold (agent-ref answered nil), answered so, never a spawn of nothing
       (nil? b) (doto (promise) (deliver (refused 'seon.root/prove! "no branch to prove: the reference names none (an agent root does not hold?)" {})))
       ;; a point in the past: a new branch made at its commit, its own agent, ended once answered
       (or commit t)
       (let [c (or commit (:commit (datahike.versioning/commit-at-tx (:conn sys) b t)))
             ag (spawn! sys {:branch (keyword (str "at-" c)) :commit c})
             r @(ask ag (or mode :judge))]
         (kill! sys ag)
         (doto (promise) (deliver r)))
       (= b main) (ask nil (or mode :main))
       :else (let [live (some (fn [[aid a]] (when (and (= b (:branch a)) (not (gone-statuses (:status a)))) aid)) (:agents (state sys)))
                   ag (or live (spawn! sys {:branch b}))]
               (ask ag (or mode :turn)))))))

(defn agent-ref
  "The reference prove! takes for agent id's branch at its head, {:branch b}, or nil when root holds no such agent. See
  docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] [:maybe :map]]}
  [sys agent-id]
  (some->> (get-in (state sys) [:agents agent-id :branch]) (hash-map :branch)))

(defn run-tests!
  "The agent's branch proved by the test service as root's judge proves it (prove! in mode
  :judge: the run is recorded on main and is one run with any other asker's of its head); a
  promise of the run's answer {:pass :fail :error :reports [...]} (each test bounded by its
  work: :seon.config.agent/test-ticks)."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise]}
  [sys agent-id] (prove! sys (or (agent-ref sys agent-id) {}) #{} :judge))

(defn- write! {:malli/schema [:=> [:cat :seon.root/sys :seon.writer/request] :seon.host/promise]}
  [sys req] (call! sys {:op :write :req req}))

(defonce ^:private tree-runs
  ;; tree branch -> {:n runs of it in flight here} or {:dropping promise} while its last run's end drops it (prove-tree!)
  (atom {}))

(defn- tree-enter!
  "Count one more run of tree branch b in flight; while b is being dropped (its last run ended), wait for the drop first: a run
  never finds a branch that is going. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :keyword] :nil]}
  [b]
  (loop []
    (let [cur @tree-runs e (get cur b)]
      (cond (:dropping e) (do @(:dropping e) (recur))
            (compare-and-set! tree-runs cur (assoc cur b {:n (inc (:n e 0))})) nil
            :else (recur)))))

(defn- tree-leave!
  "One run of tree branch b ended: the last one marks b dropping, ends its agent ag (its end awaited on root's events, tapped
  before the kill) and drops the branch (the writer's :drop-tree-branch: the owner's ruling, the system drops a tree's branch once
  no proof of it runs), then lets a waiting run in. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :seon.root/sys :keyword [:maybe :seon.agent/id]] :nil]}
  [sys b ag]
  (loop []
    (let [cur @tree-runs e (get cur b)]
      (if (> (:n e 0) 1)
        (if (compare-and-set! tree-runs cur (assoc cur b {:n (dec (:n e))})) nil (recur))
        (let [p (promise)]
          (if (compare-and-set! tree-runs cur (assoc cur b {:dropping p}))
            ;; whatever the drop meets, a run waiting on it is let in and the tree's entry cleared: a throw here never strands one
            (try
              ;; its agent stopped first: the kill's own answer, given once root's view holds it stopped (the api's :kill), is the
              ;; wait's end; never an end event read from a tap, which slides and can drop the one event waited for
              (when (and ag (not (gone-statuses (get-in (state sys) [:agents ag :status]))))
                @(request! sys 'kill! ag nil))
              @(write! sys {:op :drop-tree-branch :branch b})
              nil
              (finally (swap! tree-runs dissoc b) (deliver p true)))
            (recur)))))))

(defn tree-branch
  "The branch a checkout's files are proved on, by the checkout's tree id (seon.fx/tree-id): every session on one tree shares it,
  its agent, its plan and its run key. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :string] :keyword]}
  [tree] (keyword (str "tree-" tree)))

(defn baked-paths
  "Of a checkout's changed paths, those a build bakes into the binary, so a process forked from the system's binary holds the
  system's value of them however the tree's branch states it: the schema resources (seon.config/baked-schema-forms), the
  shipped config (baked-shipped-text), system.edn (seon.system/baked-decl-text) and deps.edn (seon.fork-server/baked-deps-text).
  A tree's run that changes one says so (prove-tree!'s :baked): its in-process verdicts read main's bake for them, until the
  one call holds the runtime invariant for bakes. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat [:sequential :string]] [:vector :string]]}
  [paths]
  (filterv #(or (str/starts-with? % "resources/seon/schemas/") (= % cfg/shipped-file)
                (= % "resources/seon/system.edn") (= % "deps.edn"))
           paths))

(defn prove-tree!
  "Prove the files of checkout (a worktree of this root's repository) at tree, its tree id (seon.fx/tree-id, what bin/build records):
  their branch (tree-branch), made once from main with the checkout's catch-up written on it (catch-up :fact? false, as the writer's
  one :branch-for-tree op), its live agent or one forked onto it, and prove! of names in mode (default :judge). The run's answer,
  or a typed error: no tree on main to diff from, the catch-up's read failing, or a file it left out (:seon.root/catch-up-skipped,
  each named: a judgement of part of the files would be wrong). Once no run of it is in flight here, its agent ends and the branch
  is dropped (tree-leave!); a run that comes meanwhile waits for the drop and makes the branch again.
  See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :seon.root/sys [:map [:checkout :string] [:tree :string]]] :map]}
  [sys {:keys [checkout tree names mode]}]
  (let [b (tree-branch tree)
        c (catch-up @(:conn sys) checkout {:fact? false})]
    (cond
      (nil? c) (refused 'seon.root/prove-tree! "main holds no tree of its checkout to diff a session's files from" {:checkout checkout})
      (contract/error? c) c
      (seq (:skipped c)) (contract/error :seon.root/catch-up-skipped #'prove-tree!
                                         (str "the checkout's files could not all be read, so none is proved: "
                                              (str/join "; " (map #(str (:path %) ": " (:reason %)) (:skipped c))))
                                         {:skipped (:skipped c)})
      :else
      (do
        ;; counted before the branch is made or found: a last run's drop never takes it from under this one
        (tree-enter! b)
        ;; the run's count is given back however it ends (a throw included): a tree's branch is always dropped by its last run
        (let [ag-of (volatile! nil)]
         (try
        (let [w @(write! sys {:op :branch-for-tree :branch b :tx (:tx c)})
              full (pdb/on-main (pdb/conn-main (:conn sys)) b)
              live (fn [] (some (fn [[aid a]] (when (and (= full (:branch a)) (not (gone-statuses (:status a)))) aid)) (:agents (state sys))))
              ;; the spawn's answer: the new agent, or, for an asker who came second, the refusal naming the live holder
              s (when-not (or (contract/error? w) (live)) @(request! sys 'spawn! nil {:branch b}))
              ag (when-not (contract/error? w) (or (live) (:id s) (:agent s)))
              _ (vreset! ag-of ag)]
          (cond (contract/error? w) w
                (nil? ag) s
                ;; a verdict partly another program's says so: the baked files the tree changed (baked-paths), never silence
                :else (let [r @(prove! sys {:branch full} (set (map symbol names)) (or mode :judge) {:checkout checkout :tree tree})
                            baked (baked-paths (:paths c))]
                        (cond-> r (and (seq baked) (map? r) (not (contract/error? r))) (assoc :baked baked)))))
         (finally (tree-leave! sys b @ag-of))))))))

(defn store-clean!
  "A promise of the writer's answer once its store is clean ({:ok :commits :flushes}): every
  commit so far synced, the flush's notice to the api before it (seon.writer/writer-proc's :clean)."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.host/promise]}
  [sys] (write! sys {:op :clean}))

(defn apply!
  "Re-read root's system declaration (:seon.config.root/system-path, or the file at p) and change the
  running graph to it: only the procs whose declaration changed are stopped, started or
  rewired; the others keep running with their queued messages. A promise of Flow's
  update! answer {:started :stopped :kept :lost :ms}."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :seon.host/promise] [:=> [:cat :seon.root/sys :string] :seon.host/promise]]}
  ([sys] (apply! sys (cfg/value :seon.config.root/system-path)))
  ([sys p] (call! sys {:op :apply :decl (system/read-decl p) :bindings (:bindings sys)})))

(defn merge-main!
  "git merge main, on the agent's branch (seon.merge): main's changes since the merge base
  come into the branch and the agent's db; each conflict stays on the branch as an unmerged
  entry. A promise of {:ok true :applied [key] :conflicts [...] :t}, or :seon.root/no-agent. opts {:data-attrs}
  (default :all)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise]
                  [:=> [:cat :seon.root/sys :seon.agent/id :map] :seon.host/promise]]}
  ;; the agent owns its branch's writes: it merges main itself
  ([sys agent-id] (merge-main! sys agent-id {}))
  ([sys agent-id opts] (request! sys 'merge-main! agent-id opts)))

(defn branch-db
  "The writer's current db value of branch (:db is main), for root's reads: a promise of
  {:ok true :db db :base-t t} (base-t: main's t at the branch's merge base)."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.db/branch] :seon.host/promise]}
  [sys branch] (write! sys {:op :branch-db :branch branch}))

(defn union
  "The union of every branch's history (seon.union) as the writer keeps it: a promise of
  {:ok true :db (d/history U) :branches n}, U built at the first ask and kept current by
  every commit since. Query :db with plain d/q, d/pull, d/entity."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.host/promise]}
  [sys] (write! sys {:op :union}))

(defn branch-diff "The agent's branch versus main. A promise." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise]}
  [sys agent-id]
  (if-let [b (:branch (agent-entry sys agent-id))]
    (write! sys {:op :branch-diff :branch b})
    (doto (promise) (deliver (gone agent-id (str "no agent " agent-id))))))

(defn effect!
  "Root's own effect: kind with req through its owner proc. A promise of its outcome."
  {:malli/schema [:=> [:cat :seon.root/sys :keyword :map] :seon.host/promise]}
  [sys kind req] (call! sys {:op :fx :kind kind :req req}))

(defn transact-main!
  "Root's own write to main. A promise. opts {:flush? true}: answered only after the
  group flush that makes it durable (for a write an outside effect follows)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.db/tx-data] :seon.host/promise]
                  [:=> [:cat :seon.root/sys :seon.db/tx-data [:maybe :map]] :seon.host/promise]]}
  ([sys tx-data] (transact-main! sys tx-data nil))
  ([sys tx-data {:keys [flush?]}] (write! sys (cond-> {:op :tx :branch :db :tx-data tx-data} flush? (assoc :flush? true)))))

(defn opened!
  "A reader of agent key's page opened its eval eval-id: the fact on main, on the agent's own
  entity (:seon.agent/opened: an eval's id names it only within its agent), which its next turn
  writes on its branch as the eval's :seon.eval/opened (seon.agent/untaken-facts), so its
  context and its page read it from the same data. Never retracted. A promise of the writer's answer."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/key :seon.eval/id] :seon.host/promise]}
  [sys key eval-id] (request! sys 'opened! nil {:key key :eval eval-id}))

(defn interrupt!
  "Interrupt the agent's evaluation in progress: SIGUSR1, run by Chez at the agent's next
  safe point, which also ends a blocking host call (preemption is physics: no message
  reaches a running native loop). nil."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id]
  (some-> (:pid (agent-entry sys agent-id)) (lib/kill! lib/SIGUSR1))
  nil)

(defn ask!
  "Ask for what a sentence says (args {:task text :template name}): one released task on its own branch from that template (seon.template/templates, by default the one that works for a person), which the reconciler gives an agent. A promise of {:task id}, or the refusal. See docs/tasks.md."
  {:malli/schema [:=> [:cat :seon.root/sys [:map [:task :string] [:template {:optional true} :keyword]]] :seon.host/promise]}
  [sys args] (request! sys 'ask! nil args))

(defn focus!
  "Move the signed-in user's view to the page of the agent with key k (args {:key k}): their :seon.user/focus on main. A promise of the writer's answer, or the refusal when no user is signed in or no agent has the key. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :seon.root/sys [:map [:key :string]]] :seon.host/promise]}
  [sys args] (request! sys 'focus! nil args))

(defn back!
  "Take agent id's session back to just after its eval (args {:eval id}): a new branch from that eval's commit, the old branch kept as it is, this process ended and one started on the new branch under the same key, its task parked on it. Answers the new agent's id, or the refusal. See docs/turn.md#an-agent-goes-back-to-an-eval."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id [:map [:eval :string]]] :any]}
  [sys agent-id args]
  (let [r @(request! sys 'back! agent-id args)] (if (contract/error? r) r (:id r))))

(defn kill! "SIGKILL the agent's process (through its agent proc). nil." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id] (request! sys 'kill! agent-id nil) nil)

(defn stop!
  "Stop root by letting the flow drain: the api closes (later calls answer \"root
  stopped\"); every agent is interrupted and sent :stop, and exits after the requests before
  it (seon.agent-proc/stop!); the writer's in-port closes, it commits and flushes what it
  took; the fork server is killed and reaped; then flow/stop (every proc exited) and the
  reader. Every wait ends on its answer, none on a clock. Nothing accepted is lost.
  First the end is a fact on main, durable (:seon.root/stopped {:boot :cause :at-ms}; cause
  :stop! unless the caller names it: :stdin-eof, :signal), so every clean end says why; a
  refused stop fact still stops root, and the answer carries it (:stop-fact-refused).
  Answers {:stopped true :cause :drained .. :graph true|{::flow/running #{pids}} :ms}."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :keyword] :map]]}
  ([sys] (stop! sys :stop!))
  ([sys cause]
  (let [fact @(transact-main! sys [{:seon.root/id :root :seon.root/stopped {:boot (:boot sys) :cause cause :at-ms (System/currentTimeMillis)}}]
                              {:flush? true})
        t0 (profile/now-ns)
        ;; an api already closed (a root whose api ended on its own) answers at once that it is
        p (step/ask-edge (:ext sys) {:op :shutdown} (gone nil "root stopped"))]
    (a/close! (:ext sys))
    ;; a wait on main asked after this answers stopped at once (ask-edge's closed case)
    (a/close! (:judge sys))
    ;; parked on the drain, no bound: each agent interrupted and stopped exits (seon.agent-proc/stop!), the
    ;; writer flushes, the fork server ends; one that never does is a stall, recorded (task
    ;; seon.root/shutdown-and-death-are-dataflow)
    (let [drained @p
          graph (flow/stop (:graph sys))]
      ;; a wait put on :judge that the stopped reconciler never took is answered here
      (loop [] (when-let [m (a/poll! (:judge sys))] (some-> (:p m) (deliver {:stopped true})) (recur)))
      (a/close! (:events-in sys))
      ;; the store is released and kept, whoever gave its path
      (d/release (:conn sys))
      (when (= :lmdb (:backend (:store (:cfg sys)))) (klmdb/close-env! (:store-path sys)))
      (cond-> {:stopped true :cause cause :drained drained :graph graph :agents-left 0 :ms (lib/r2 (profile/ms-since t0))}
        (contract/error? fact) (assoc :stop-fact-refused fact))))))
