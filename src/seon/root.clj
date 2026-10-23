(ns seon.root
  "Root: the control process (session F2, notes/archive/option3-F1-flow.md; proto/flow f2-final
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
  agent's own loop (seon.step/run-loop), which runs the same step-fn contract.

  Ordering is dataflow: every proc starts and parks on its inputs. Shutdown flows too:
  the api closes, agents drain and exit, the writer's in-port closes, the writer
  drains and flushes last, the fork server is reaped, then flow/stop.

  Agents transact facts on their own branch; root reacts: the writer applies them, and
  only root's merge! advances main."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.query.execute] [seon.db :as pdb] [seon.lib :as lib]
            [seon.sock :as sock] [jolt.async.remote :as remote] [konserve.jolt.fasl :as codec] [seon.fx :as fx] [seon.fork-server :as fs] [seon.agent-proc :as ap]
            [seon.writer :as writer] [seon.source :as source] [seon.reconcile :as reconcile] [seon.system :as system] [seon.program :as program] [seon.step :as step]
            [seon.config :as cfg] [seon.contract :as contract] [seon.print :as print] [seon.task :as task] [seon.docs :as docs] [seon.error :as err] [seon.agent :as agent] [konserve.jolt.lmdb :as klmdb] [clojure.java.shell]
            [clojure.core.async :as a]
            ;; the agent side, loaded here so the fork server and every agent inherit it compiled
            [seon.turn] [seon.fixtures] [my.fx] [my.task] [my.turn] [my.branch] [clojure.core.async.flow :as flow] [clojure.core.async.flow.spi :as spi] [clojure.test]
            [taoensso.trove :as trove]))


;; ====================================================================================
;; The api: where requests come in
;; ====================================================================================

;; ---- problems root observes (session D2): recorded as seon.error facts on main -----------

(defn- gone
  "The one error map of a request no live agent can take (agent id, or nil)."
  {:malli/schema [:=> [:cat [:maybe :keyword] :string] :seon.root/no-agent]}
  [id why] (contract/error :seon.root/no-agent 'seon.root/api-proc why {:agent id}))

(defn- refused
  "Root's API refusing a request, as the one error map; members describe the refusal."
  {:malli/schema [:=> [:cat :qualified-symbol :string :map] :seon.root/refused]}
  [op why members] (contract/error :seon.root/refused op why members))

(def ^:private tail-bytes
  "How much of a dead agent's stderr its death records: the last bytes of its file."
  2048)

(defn stderr-tail
  "The last tail-bytes of the stderr file at path (seon.agent/stderr-path), or nil: no path
  (an in-memory store), the file gone, or nothing written."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  [path]
  (let [f (some-> path (java.io.File.))]
    (when (and f (.exists f) (pos? (.length f)))
      (with-open [r (java.io.RandomAccessFile. f "r")]
        (let [n (.length r) k (min n tail-bytes) b (byte-array k)]
          (.seek r (- n k))
          (.readFully r b)
          (String. b "UTF-8"))))))

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

(defn- record-death
  "Agent aid's death, folded, and answered to the requests it died in (inflight): its died
  map completed with the exit status the fork server reaped (nil: never known, for a clone
  or an orphan, which are not the server's children)."
  {:malli/schema [:=> [:cat :seon.root/api-state :keyword [:maybe :seon.lib/exit-status] :map [:sequential :int]] :seon.root/api-state]}
  [st aid exit died inflight]
  (let [e (assoc died :exit exit :seon.error/message (str "agent " (name aid) " died" (exit-text exit)))
        st (problem st (err/occurrence e :agent {:agent aid :task (get-in st [:view :agents aid :task]) :form (get-in e [:last-op :form])}
                                       (System/currentTimeMillis)))]
    (reduce (fn [st r] (deliver-rid st r e)) st inflight)))

(defn- reply!
  "Answer a caller: an outside caller's promise is delivered; a proc's reply route
  ({:route [pid in] :tag t}: a proc is a caller too, request/reply as data) gets
  {:tag t :r r} as an output at the end of this step. Answers st."
  {:malli/schema [:=> [:cat :seon.root/api-state [:or :seon.host/promise :map] :any] :seon.root/api-state]}
  [st p r]
  (if (map? p)
    (update st ::replies (fnil conj []) [(:route p) {:tag (:tag p) :r r}])
    (do (deliver p r) st)))

(defn- deliver-rid
  "Answer the caller waiting on rid; answers st."
  {:malli/schema [:=> [:cat :seon.root/api-state :int :any] :seon.root/api-state]}
  [st rid r]
  (let [[st' {:keys [p]}] (step/answered st rid)]
    (if p (-> (reply! st' p r) (update :writes disj rid)) st)))

(defn- view! {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/fn [:* :any]] :seon.root/api-state]}
  [st f & args] (apply update st :view f args))

(def gone-statuses "Agent statuses of a process gone (:seon.agent/status)." (:seon.status/gone (contract/properties :seon.agent/status)))
(def ^:private stopped-statuses (:seon.status/stopped (contract/properties :seon.agent/status)))

(defn- failed
  "What went wrong in agent id's view change m (a its entry before), as the one error map,
  or nil: a spawn or fork refused (:error, its error map); a red test run or a refused merge of an agent
  with no task (a task's attempt records its own at its end, seon.problems/report)."
  {:malli/schema [:=> [:cat :seon.agent/id :map :map] [:maybe :seon.error/base]]}
  [id a m]
  (let [{:keys [fail error reports]} (:tests m) r (:merged m)]
    (cond
      (:error m) (:error m)
      (:task a) nil
      (pos? (+ (or fail 0) (or error 0)))
      (contract/error :seon.agent/failed 'seon.agent/run-tests
                      (str "tests red" (some->> (first (remove #(= :pass (:type %)) reports)) :var (str ": "))) {:agent id})
      (contract/error? r) r)))

(defn- set-agent
  "Merge m into agent id's view entry, recording what went wrong in it (`failed`). A stopped
  agent stays stopped: :killed (a kill) or :ended (root stopped it because its attempt ended)."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.agent/id :map] :seon.root/api-state]}
  [st id m]
  (if-let [a (get-in st [:view :agents id])]
    (let [a' (cond-> (merge a (dissoc m :ready :evals-add))
               (and (:ready m) (= :spawning (:status a))) (assoc :status :idle)
               (:evals-add m) (update :evals #(vec (take-last 20 (into (or % []) (:evals-add m))))))
          e (failed id a m)]
      (cond-> (assoc-in st [:view :agents id] (if (and (stopped-statuses (:status a)) (not (gone-statuses (:status a')))) (assoc a' :status (:status a)) a'))
        e (problem (err/occurrence e :agent {:agent id :task (:task a)} (System/currentTimeMillis)))
        ;; the agents with an eval in flight: the stall check's set
        (contains? m :running) (update ::busy (fnil (if (:running m) conj disj) #{}) id)))
    st))

(defn- to-agent
  "Send m to agent id's proc: an output to [id :in] (the resolver routes it, the agent is a
  proc of this graph), collected in the state and emitted at the end of the step, after
  any update! that added the agent."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.agent/id :map] :seon.root/api-state]}
  [st id m]
  (if (get-in st [:agents id])
    (update st ::to-agents (fnil conj []) [id m])
    st))

(defn- spawn-failed
  "Agent id's spawn or fork refused (why, and its cause when another proc refused it): the
  one error map in its view and sent to its proc, which hangs up."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.agent/id :string [:maybe :seon.error/base]] :seon.root/api-state]}
  [st id why cause]
  (let [e (contract/error :seon.agent/failed 'seon.root/spawn! why (cond-> {:agent id} cause (assoc :cause cause)))]
    (-> st (set-agent id {:error e}) (to-agent id {:op :failed :error e}))))

(def ^:private agent-launcher
  "One launcher for every agent's proc (its step is the Var #'agent-proc)."
  (flow/process #'ap/agent-proc))

(defn- start-agent
  "Register a new agent: its socket (root keeps one end; the other goes to whoever forks
  it), its proc definition (added to the graph by update! at the end of the step). Root's
  end is a jolt.async.remote end (session RC1): its :in is the agent proc's :from, so the
  agent's port is an ordinary channel. Answers [st id agent-end-fd]."
  {:malli/schema [:=> [:cat :seon.root/api-state :map] [:tuple :seon.root/api-state :seon.agent/id :seon.lib/fd]]}
  [st {:keys [branch task cloned-from]}]
  (let [n (inc (:next-id st))
        id (keyword (str "a" n))
        ;; named under root's main, so roots sharing a store never share a branch (seon.db/on-main)
        branch (pdb/on-main (pdb/conn-main (:conn st)) (or branch (keyword (str "agent-" n))))
        [r a] (sock/socketpair)
        ;; fiber pumps: an end costs no thread of its own (root may run a thousand agents)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode} :pump :fiber :buf (cfg/value :seon.config.agent/inbox-size)})]
    [(-> st (assoc :next-id n ::update true)
         (assoc-in [:agents id] {:branch branch})
         (assoc-in [:agent-procs id] {:proc agent-launcher
                                      :args {:id id :end end :branch branch
                                             :to-writer (:to-writer st) :back (:back st) :events (:events st)
                                             :fx-kinds (system/fx-owners (get-in st [:system :procs]))}
                                      :chan-opts {:in {:buf-or-n (cfg/value :seon.config.agent/inbox-size)}}})
         (assoc-in [:view :agents id] (cond-> {:branch branch :status :spawning :task task :pid nil
                                               :forked-ms nil :mem-mb nil :evals [] :tests nil}
                                        cloned-from (assoc :cloned-from cloned-from))))
     id a]))

(defn- end-agent
  "The agent's process is gone and its agent proc saw its last frame: its proc leaves the
  graph (update! at the end of the step) and its branch's connection is released."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :map] :seon.root/api-state]}
  [st o {:keys [agent rid pid inflight]}]
  (let [{:keys [branch]} (get-in st [:agents agent])
        v (get-in st [:view :agents agent])
        ;; stopped by root: a kill, or the end of its attempt (:ended); else it died
        stop (stopped-statuses (:status v))
        ;; neither stopped by root nor killed: a death, with what root saw of it (session D2)
        died (when-not (or rid stop)
               (contract/error :seon.agent/died 'seon.root/api-proc (str "agent " (name agent) " died" (exit-text (:exit v)))
                               {:agent agent :pid (or pid (:pid v)) :exit (:exit v)
                                :stderr (stderr-tail (agent/stderr-path (get-in st [:view :store :agents-dir]) agent))
                                :last-op (some-> (:running v) (select-keys [:form :since-ms]) (assoc :kind :eval))}))
        e (or died (gone agent "agent stopped"))]
    (step/out! o :events (step/event {:type (or stop :dead) :agent agent}))
    (let [st (cond
               (nil? died) (reduce (fn [st r] (deliver-rid st r e)) st inflight)
               ;; the exit status is known (reaped first), or never will be (a clone, an
               ;; orphan of a server that went down): now
               (or (:exit v) (:cloned-from v) (:orphan v)) (record-death st agent (:exit v) died inflight)
               ;; else when the fork server reaps it (:exit): the requests it died in are
               ;; answered then, with the status that names the death
               :else (assoc-in st [::dying (or pid (:pid v))] [agent died inflight]))
          st (cond-> st rid (deliver-rid rid {:stopped true :agent agent}))]
      (-> st (update :agents dissoc agent) (update :agent-procs dissoc agent) (assoc ::update true)
          (set-agent agent {:status (or stop :dead) :ended-ms (System/currentTimeMillis) :running nil})))))

(defn- pend
  "st with caller p awaiting the answer to a new request id (seon.step/pend), and the id."
  {:malli/schema [:=> [:cat :seon.root/api-state [:maybe [:or :seon.host/promise :map]] :keyword [:maybe :seon.agent/id]] [:tuple :seon.root/api-state :int]]}
  [st p kind agent] (step/pend st (when p {:p p :kind kind :agent agent})))

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
               (reply! (assoc st :system built) p (select-keys r [:started :stopped :kept :lost :ms])))
      :spawn (let [opts (cond-> (:opts m) (:branch (:opts m)) (update :branch #(pdb/on-main (pdb/conn-main (:conn st)) %)))
                   holder (and (:branch opts)
                               (some (fn [[aid a]] (when (and (= (:branch opts) (:branch a)) (not (:ended-ms a))
                                                              (not (gone-statuses (:status a))))
                                                     aid))
                                     (get-in st [:view :agents])))]
               (cond
                 holder (reply! st p (refused 'seon.root/spawn! (str "branch " (:branch opts) " is held by live agent " holder)
                                              {:branch (:branch opts) :agent holder}))
                 (>= (count (:agents st)) (cfg/value :seon.config.root/max-agents)) (reply! st p (refused 'seon.root/spawn! "too many live agents" {:max (cfg/value :seon.config.root/max-agents)}))
                 :else (let [[st aid fd] (start-agent st opts)
                             branch (get-in st [:agents aid :branch])]
                         (step/out! o :to-writer {:op :spawn :agent aid :branch branch :task (:task opts) :attempt (:attempt opts) :ns (profile/now-ns) :fd fd :dest {:spawn aid}})
                         (reply! st p {:id aid}))))
      :clone (let [parent (:parent m)]
               (cond
                 (nil? (get-in st [:agents parent])) (reply! st p (gone parent (str "no live agent " parent)))
                 (>= (count (:agents st)) (cfg/value :seon.config.root/max-agents)) (reply! st p (refused 'seon.root/clone! "too many live agents" {:max (cfg/value :seon.config.root/max-agents)}))
                 :else (let [[st aid fd] (start-agent st (merge {:task (get-in st [:view :agents parent :task])} (:opts m) {:cloned-from parent}))
                       branch (get-in st [:agents aid :branch])
                       [st rid] (pend st p :clone parent)]
                   ;; the clone's socket goes to its parent with the request
                   (to-agent st parent
                             {:op :req :rid rid :kind :clone :new aid :branch branch :pass-fd fd
                              :msg {:op :clone :agent aid :branch branch}}))))
      (:eval :tests :merge-tests :stats :merge-main)
      (if (nil? (get-in st [:agents id]))
        (reply! st p (gone id (str "no live agent " id)))
        (let [[st rid] (pend st p op id)
              msg (case op
                    :eval (cond-> {:op :eval :forms (:forms m)} (:ticks m) (assoc :ticks (:ticks m)) (:origin m) (assoc :origin (:origin m)))
                    (:tests :merge-tests) (cond-> {:op :tests} (:ticks m) (assoc :ticks (:ticks m))
                                            (get-in st [:view :agents id :task]) (assoc :task (get-in st [:view :agents id :task])))
                    :stats {:op :stats}
                    :merge-main {:op :merge-main :data-attrs (:data-attrs m)})]
          (to-agent st id {:op :req :rid rid :kind op :msg msg :merge-branch (:merge-branch m) :data-attrs (:data-attrs m)})))
      ;; requests for the writer: answered by rid; a collection only while no agent is live,
      ;; the store's only writer then being root's writer (seon.db/collect!)
      :write (if (and (= :collect (get-in m [:req :op])) (seq (:agents st)))
               (reply! st p (refused 'seon.root/collect "a collection waits until no agent is live" {:agents (vec (sort (keys (:agents st))))}))
               (let [[st rid] (pend st p (get-in m [:req :op]) nil)
                   req (:req m)
                   dest (case (:op req) :merge-main {:merge-main (:agent req) :rid rid} :merge {:merged (:agent req) :rid rid} {:rid rid})]
               (step/out! o :to-writer (assoc (dissoc req :agent) :dest dest))
               (update st :writes (fnil conj #{}) rid)))
      :kill (cond-> (if (get-in st [:agents id])
                      ;; :ended when root stops it because its attempt ended (the reconciler says so)
                      (-> st (set-agent id {:status (if (:ended m) :ended :killed)}) (to-agent id {:op :kill}))
                      st)
              p (reply! p {:killed id}))
      ;; an effect asked by root itself or a root proc (the reconciler's write-back): to its
      ;; kind's owner, answered by rid like the writer's answers
      :fx (let [owner (keyword "fx" (name (:kind m)))]
            (if (contains? (system/fx-owners (get-in st [:system :procs])) owner)
              (let [[st rid] (pend st p :fx nil)]
                (step/out! o [owner :in] {:req (:req m) :prio 0 :dest {:rid rid}})
                st)
              (reply! st p (contract/error :seon.fx/unknown-kind 'seon.fx/call (str "no effect kind " (:kind m))))))
      :fs (let [[st rid] (pend st p :fs nil)]
            (step/out! o :to-fs (assoc (:req m) :rid rid))
            st)
      :state (reply! st p (:view st))
      :errors (reply! st p (::errors st))
      :shutdown (assoc st :shutdown-p p)
      (cond-> st p (reply! p (refused 'seon.root/request (str op " is not one of root's requests") {}))))))

(defn- back-op
  "A message from an agent proc, a fork-server or writer event, or the error reader."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :map] :seon.root/api-state]}
  [st o m]
  (cond
    (:dest m)
    (let [{:keys [dest r]} m]
      (cond
        (:merged dest) (let [r (cond-> r (:tests dest) (assoc :tests (:tests dest)))]
                         (step/out! o :events (step/event {:type :merged :agent (:merged dest) :result r}))
                         (-> st (set-agent (:merged dest) {:merged r}) (deliver-rid (:rid dest) r)))
        (:merge-main dest) (let [aid (:merge-main dest)]
                             (step/out! o :events (step/event {:type :merged-main :agent aid :result (dissoc r :sync)}))
                             (cond-> st
                               (:sync r) (to-agent aid {:op :write :msg (:sync r)})
                               true (deliver-rid (:rid dest) (dissoc r :sync))))
        ;; the recorded errors' write was answered: the next window may write
        (:errors-flushed dest) (dissoc st ::errors-flight)
        (:rid dest) (deliver-rid st (:rid dest) r)
        (:agent dest) (to-agent st (:agent dest) {:op :write :msg (cond (:op r) r
                                                                          (= :branched (:op dest)) (cond-> {:op :branched :k (:k dest) :cfg (:cfg r) :lease (:lease r)} (contract/error? r) (assoc :error r))
                                                                          :else (assoc r :op (:op dest) :k (:k dest)))})
        (:spawn dest) (if (contract/error? r) (spawn-failed st (:spawn dest) (:seon.error/message r) r) st)
        :else st))

    (:ev m)
    (case (:ev m)
      :forked (do (step/out! o :events (step/event {:type :spawned :agent (:agent m) :pid (:pid m) :fork-ms (:fork-ms m)}))
                  (when (stopped-statuses (get-in st [:view :agents (:agent m) :status])) (lib/c-kill (:pid m) 9))
                  (-> st (set-agent (:agent m) {:pid (:pid m)})
                      (view! update-in [:fork-server :agents-forked] (fnil inc 0))
                      (view! update-in [:stats :fork-ms] lib/push-window (:fork-ms m) 500)
                      (to-agent (:agent m) {:op :forked :pid (:pid m)})))
      :exit (let [st (if-let [aid (some (fn [[aid a]] (when (= (:pid m) (:pid a)) aid)) (get-in st [:view :agents]))]
                       (set-agent st aid {:exit (:status m)})
                       st)]
              ;; a death waiting for its exit status: recorded now
              (if-let [[aid died inflight] (get-in st [::dying (:pid m)])]
                (-> st (update ::dying dissoc (:pid m)) (record-death aid (:status m) died inflight))
                st))
      :fork-failed (spawn-failed st (:agent m) (str "fork refused: " (:active-threads m) " active threads") nil)
      :applied (-> st (view! update-in [:stats :lag-ms] lib/push-window (:lag-ms m) 200)
                   (view! assoc-in [:fork-server :mismatch] (:mismatch m)))
      :load-errors (view! st update-in [:fork-server :load-errors] (fnil into []) (:errors m))
      ;; fork requests the dead server never served fail; agents live on
      ;; the dead server's children are orphans: no server reaps them, so a death waiting
      ;; for its exit status is answered now, and a later one at once
      :fork-server-down (do (step/out! o :events (step/event {:type :fork-server-down :pid (:old m)}))
                       (as-> (reduce (fn [st [aid a]]
                                       (if (and (= :spawning (:status a)) (nil? (:pid a)) (get-in st [:agents aid]))
                                         (spawn-failed st aid "fork server went down before the fork" nil)
                                         (cond-> st (:pid a) (set-agent aid {:orphan true}))))
                                     (view! st update :fork-server assoc :status :restarting) (get-in st [:view :agents])) st
                         (reduce (fn [st [_ [aid died inflight]]] (record-death st aid nil died inflight))
                                 (dissoc st ::dying) (::dying st))))
      :fork-server-lost (do (step/out! o :events (step/event {:type :fork-server-lost}))
                       (view! st update :fork-server assoc :status :lost))
      :fork-server-up (do (step/out! o :events (step/event {:type :fork-server-up :pid (:pid m)}))
                     (view! st update :fork-server (fn [s] (-> s (assoc :status :up :pid (:pid m)) (update :restarts (fnil inc 0))))))
      st)

    :else
    (case (:op m)
      :status (set-agent st (:agent m) (:set m))
      :stat (case (:k m)
              :noise (view! st update :stats assoc :noise (:v m) :noise-ordered (:ordered m) :noise-first-bad (:bad m))
              (view! st update-in [:stats (:k m)] lib/push-window (:v m) 100000))
      :message (let [msg {:op :message :from (:from m) :body (:body m) :ns (profile/now-ns)}]
                 (reduce #(to-agent %1 %2 {:op :write :msg msg}) st (:to m)))
      ;; a proc's request (the reconciler's, the web's, MCP's): an ordinary api request
      ;; whose answer goes back to the proc as data
      :request (ext-op st o (assoc (:req m) :p {:route (:route m) :tag (:tag m)}))
      :clone-failed (spawn-failed st (:agent m) "clone refused" (:error m))
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
      :fs-down (do (some-> (:shutdown-p st) (deliver {:drained true :fork-server (:pid m)})) st)
      ;; a defect in a root proc: recorded as data (its error map when it carries one), and
      ;; the message the step took answered with it (Flow's catch dropped the step's outputs):
      ;; the api's own throw answers every message its step took (::lost)
      :error (let [e (if (contract/error? (:data m))
                       (:data m)
                       (contract/error :seon.root/proc-defect (proc-sym st (:pid m)) (or (:message m) "a defect with no message") {:pid (:pid m)}))
                   taken (distinct (cond-> (if (= :api (:pid m)) (::lost st) []) (:msg m) (conj (:msg m))))]
               ;; the view keeps the newest 20 and counts them all (renders say how many it left)
               (-> (reduce (fn [st x] (answer-taken st o (:pid m) x e)) (cond-> st (= :api (:pid m)) (dissoc ::lost)) taken)
                   (view! update :root-errors (fnil #(vec (take-last 20 (conj % %2))) []) (merge {:pid (:pid m) :message (:message m)} (:data m)))
                   (view! update :root-errors-total (fnil inc 0))
                   (problem (err/occurrence e :platform {} (System/currentTimeMillis)))))
      ;; an agent's eval errors since its last commit (seon.error/note), counted across agents
      :errors (let [task (get-in st [:view :agents (:agent m) :task])]
                (reduce (fn [st s] (problem st (err/sent->occurrence s (:agent m) task))) st (:entries m)))
      ;; an effect owner's failed outcome (seon.fx/owner-proc)
      :problem (problem st (err/occurrence (:error m) (:class m) {:agent (:agent m) :task (get-in st [:view :agents (:agent m) :task])}
                                           (System/currentTimeMillis)))
      ;; an agent's proc observed it stalled (seon.agent-proc :stall?)
      :stalled (-> st (set-agent (:agent m) {:stalled (:quiet-ms (:error m))})
                   (problem (err/occurrence (:error m) :agent {:agent (:agent m) :task (get-in st [:view :agents (:agent m) :task])
                                                               :form (get-in m [:error :last-op :form])}
                                            (System/currentTimeMillis))))
      ;; the stall timer: each agent with an eval in flight checks its own quiet time
      :stall-check (let [now (System/currentTimeMillis)]
                     (reduce (fn [st id] (to-agent st id {:op :stall? :now-ms now :stall-ms (:stall-ms st)})) (dissoc st ::stall-due) (::busy st)))
      :kill-all (reduce (fn [st aid] (-> st (set-agent aid {:status :killed}) (to-agent aid {:op :kill}))) st (keys (:agents st)))
      st)))

(defn- answer-taken
  "st with the caller of x, a message proc pid's throwing step took, answered the defect e
  (task `seon.root/a-proc-answers-every-request-it-took`): the answer its :dest names
  (`seon.root/back-op`), its promise or reply route when it came in at the api or as a
  proc's request, the request pending under its :rid, or, for an answer the proc took
  ({:tag :r}), that answer again on the proc's :answers route. An answer that was already an
  error is not answered again: the proc had its one answer, and a step that throws on an
  error would throw on the next."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :keyword :any :seon.error/base] :seon.root/api-state]}
  [st o pid x e]
  (cond
    (not (map? x)) st
    (:dest x) (back-op st o {:dest (:dest x) :r e})
    (:p x) (reply! st (:p x) e)
    (= :request (:op x)) (reply! st {:route (:route x) :tag (:tag x)} e)
    (int? (:rid x)) (deliver-rid st (:rid x) e)
    (and (contains? x :tag) (contains? x :r) (not (contract/error? (:r x))))
    (reply! st {:route [pid :answers] :tag (:tag x)} e)
    :else st))

(defn- api-one {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :keyword [:maybe :map]] :seon.root/api-state]}
  [st o in m]
  (some->> m (vswap! (::taken st) conj))
  (cond
    (and (= in :ext) (nil? m))
    ;; the api closed: agents drain and exit; then the writer's in-port closes
    (let [st (assoc st :closing true)]
      (reduce (fn [st aid] (let [[st rid] (step/pend st nil)] (to-agent st aid {:op :drain :rid rid}))) st (keys (:agents st))))
    (= in :ext) (ext-op st o m)
    (nil? m) st
    :else (back-op st o m)))

(defn api-proc
  "The api. In :ext (the API's callers, each request with its reply promise: the
  promises live only here), :back (the agent procs and the error reader),
  :from-writer and :from-fs (answers and events). Out :to-writer, :to-fs, :events, and
  [agent :in] for each agent (routed by the resolver).
  Agents are procs of this graph: a step that spawned or ended agents applies ONE
  flow/update! at its end (the graph, a live handle, comes in :graph). A closed :ext
  starts the flowing shutdown."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:graph "a promise of this graph" :system "a promise of the built system (seon.system/build)"}
       :ins {:from-writer "writer answers and stats" :from-fs "fork-server events"} :outs {:to-fs "fork-server requests"}
       :workload :io
       :ping-map-fn (fn [st] {:agents (count (:agents st)) :pending (count (:pending st)) :closing (boolean (:closing st))})})
  ;; :system is the root graph's declared procs and conns as built (seon.system), changed by
  ;; seon.root/apply!; agents' procs join them at each update!
  ([args] (assoc (dissoc args :system) :agents {} :agent-procs {} :pending {} :rid 0 :next-id 0
                 :system @(:system args)
                 ;; what this step took, kept across Flow's catch (which keeps the state from
                 ;; before the step): a host object of this proc's own
                 ::taken (volatile! [])
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
         ;; while an agent has an eval in flight, the stall check runs every half stall bound
         st (if (and (seq (::busy st)) (not (::stall-due st)) (pos? (:stall-ms st)) (:back st))
              (let [back (:back st)]
                ;; a go block parks on the timer (Jolt's take! with a callback forks a thread per
                ;; pending take: session D2's finding, seen as one thread per root in lifecycle)
                (a/go (a/<! (a/timeout (quot (:stall-ms st) 2))) (a/>! back {:op :stall-check}))
                (assoc st ::stall-due true))
              st)
         st (if (::update st)
              (do (flow/update! @(:graph st) {:procs (merge (get-in st [:system :procs]) (:agent-procs st)) :conns (get-in st [:system :conns])})
                  (dissoc st ::update))
              st)]
     ;; the writer's in-port closes once the api is closed and every agent has ended
     (when (and (:closing st) (empty? (:agents st)) (not (:writer-closed st)))
       (a/close! (:to-writer st)))
     (doseq [[id msg] (::to-agents st)] (step/out! o [id :in] msg))
     (doseq [[route msg] (::replies st)] (step/out! o route msg))
     (vreset! (::taken st) [])
     [(cond-> (dissoc st ::to-agents ::replies)
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
  (let [db (d/db-with @conn [{:db/id "ns" :ns/name 'seon.warm :ns/doc "warm"}
                              {:db/id "f" :form/id "seon.warm/f" :form/src "(defn f [x] x)" :form/ns "ns" :form/order 0 :form/defines ["v"]}
                              {:db/id "v" :var/name 'seon.warm/f :var/ns "ns" :var/doc "warm" :var/form "f" :var/arity 1}
                              {:task/name :seon.warm/tidy :task/plan "warm" :task/status :held :task/refs ["v"]}])]
    (dotimes [_ 3]
      (d/q '[:find ?n . :in $ ?v :where [?e :var/name ?v] [?e :var/doc ?n]] db 'seon.warm/f)
      (d/q '[:find (count ?e) . :where [?e :var/name]] db)
      (d/q '[:find [?n ...] :where [?e :ns/name ?n]] db)
      (d/pull db '[*] [:var/name 'seon.warm/f])
      (d/pull db '[:task/name {:task/refs [:var/name]}] [:task/name :seon.warm/tidy])
      (d/db-with db [{:note/text "warm" :note/agent :warm}])
      (d/with db [{:note/text "warm" :note/agent :warm}])
      (sock/frame {:op :reply :items [{:id 1 :printed "x" :ms 0.1}]})
      ;; the one value printer, its text and hiccup paths and a cut
      (print/print-value {:a [1 2 3] :b "x" :c (vec (range 300))} {:handle 'result/h0 :budget 200 :outputs #{:text :hiccup}}))
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

(defn- import-source!
  "Main's program follows Seon's own source (seon.source): root's dirs read through the
  :source effect (their files, the tree's HEAD commit and its reference-code pins, and the
  tree's inputs with no program facts, seon.fx/inputs). When what they read differs from
  what main last imported (the digest on root's :seon.root/source fact, or none recorded),
  main's program becomes theirs in one transaction (seon.source/replace-tx: each form
  analyzed in the running generation's namespaces, never run; data untouched); a tree that
  reads the same imports nothing. Either way the fact is this boot's (:boot), holding the
  inputs only when the running binary was built from this very tree (target/seon.tree,
  written by bin/build) and why not otherwise (:unshared), so agents never key results by a
  previous root's inputs or by code the binary does not run. Answers the fact in force."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [sys {:keys [root dirs]}]
  (let [t0 (profile/now-ns)
        r @(call! sys {:op :fx :kind :source :req {:root root :dirs dirs :binary-tree (str (cfg/checkout) "/target/seon.tree")}})
        files (:value r)
        digest (when files (err/digest (sedn/encode [files (:pins r)])))
        held (:seon.root/source (d/pull @(:conn sys) [:seon.root/source] [:seon.root/id :root]))
        built? (and (:inputs r) (= (:binary r) (:tree (:inputs r))))
        this-boot (fn [fact] (cond-> (assoc (dissoc fact :inputs :unshared) :boot (:boot sys))
                               built? (assoc :inputs (:inputs r))
                               (and (:inputs r) (not built?)) (assoc :unshared (str "the binary was built from tree " (or (:binary r) "unknown")
                                                                                       ", the source is tree " (:tree (:inputs r))))))
        fact (if (and digest (= digest (:digest held)))
               (this-boot held)
               (let [tx (when files (source/replace-tx @(:conn sys) files))
                     w (when tx @(call! sys {:op :write :req {:op :tx :branch :db :tx-data tx}}))]
                 (this-boot (cond-> {:root root :files (count files) :forms (count (d/datoms @(:conn sys) :aevt :form/id)) :ms (profile/ms-since t0)}
                              (:commit r) (assoc :commit (:commit r))
                              (seq (:pins r)) (assoc :pins (:pins r))
                              (and w (not (contract/error? w))) (assoc :digest digest :retracted (count (filter #(and (vector? %) (= :db/retractEntity (first %))) tx)))
                              (contract/error? r) (assoc :error (:seon.error/message r))
                              (contract/error? w) (assoc :error (:seon.error/message w))))))]
    @(call! sys {:op :write :req {:op :tx :branch :db :tx-data [{:seon.root/id :root :seon.root/source fact}]}})
    fact))

(defn wiki-tx
  "The wiki (the checkout's docs/) onto main db, written in the boot fact's own commit so main
  holds it before any agent forks: the help page every agent reads first is one of its pages
  (seon.context/help-text). An unchanged wiki writes nothing. A wiki the one load refuses (a
  link to no section, seon.docs/load-dir-tx) is a recorded :platform error (seon.error, what
  my.root/problems shows) and main keeps the wiki it held: the boot goes on."
  {:malli/schema [:=> [:cat :seon.host/db :string :int] [:vector :any]]}
  [db checkout ms]
  (let [w (docs/load-dir-tx db checkout "docs")]
    (if (contract/error? w)
      (second (err/flush-tx (err/fold {} (err/occurrence w :platform {} ms)) db))
      w)))

(defn- previous-end
  "How the root before this boot on main ended: the stop fact it wrote, or, when it wrote
  none (SIGKILL, a crash: nothing could be written), :unclean, found now. nil on a main no
  root ran on."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :seon.root/end]]}
  [db]
  (let [{:seon.root/keys [boot stopped]} (d/pull db [:seon.root/boot :seon.root/stopped] [:seon.root/id :root])]
    (when boot
      (if (= boot (:boot stopped)) stopped {:boot boot :cause :unclean :at-ms (System/currentTimeMillis)}))))

(defn start!
  "Boot the control process, the fork server and the root graph on its main, a branch of
  the one store (owner, 2026-10-21: one store, many branches, as in production). opts:
  {:path dir (the store, created when missing, holding the schema and, given :datoms n,
   about n datoms of an example program on :db; a root holds no program it was not given)
   :main branch (root's main; an existing branch is resumed; :db is the seed itself)
   :backend :lmdb|:file :log? true :max-running n (parallel attempts) :provider p (the
   attempts' model) :stall-ms (a guard, seon.agent-proc/stall-ms, for the checks that
   move it) :source s}. s: Seon's own source
   is the program, {:root dir :dirs [\"src\"] :branch \"jolt/seon-merges\"}: main's program follows
   root's dirs at boot (import-source!: replaced when they read otherwise than main's last
   import), and each merge that changes them
   is committed on branch (the reconciler's write-back); absent, the config's
   :seon.config.source/* (no :seon.config.source/root: no source). What opts leave out is the config's: :seon.config.root/store-path,
  :seon.config.root/main (resumed; made from :db when the store has none), :seon.config.store/backend, :seon.config.agent/max-running and the provider of
  :seon.config.ai/*. The config in force is written on main's root entity."
  {:malli/schema [:=> [:cat :map] :seon.root/sys]}
  [{:keys [datoms log? path main backend max-running provider stall-ms source] :or {datoms 0}}]
  (lib/ignore-sigpipe!)
  (when-not log? (trove/set-log-fn! nil))
  (let [t0 (profile/now-ns)
        ;; agents write their own branches, so the store is shared: the one durable store
        ;; (the kept runs store, bin/check's, a test's). This root resumes its main, a branch
        ;; of the store's seed, and names its agents' branches under it; a run's record is
        ;; main's commit history (owner, 2026-10-22: one main, resumed)
        path (or path (cfg/value :seon.config.root/store-path))
        main (or main (keyword (cfg/value :seon.config.root/main)))
        backend (or backend (cfg/value :seon.config.store/backend))
        max-running (or max-running (cfg/value :seon.config.agent/max-running))
        {store-cfg :cfg conn :conn recovered? :recovered?} (pdb/open! backend path datoms main)
        ;; each agent's stderr goes to a file here (seon.agent/stderr-path), read at its death
        agents-dir (pdb/agents-dir store-cfg)
        _ (.mkdirs (java.io.File. agents-dir))
        open-ms (profile/ms-since t0)
        t-pre (profile/now-ns)
        _ (pdb/preload! @conn)
        preload-ms (profile/ms-since t-pre)
        recovered (if recovered? (pdb/of-main main (d/branches conn)) [])
        load-ms (profile/ms-since t0)
        t1 (profile/now-ns)
        ;; this root's boot id (attempts of an earlier boot are reaped); its fact is written
        ;; through the writer once the graph runs, and that commit is the reconciler's first pass
        boot (str (random-uuid))
        source (or source (when-let [r (get cfg/config :seon.config.source/root)]
                            {:root r :dirs (cfg/value :seon.config.source/dirs) :branch (cfg/value :seon.config.source/branch)}))
        provider (or provider (cfg/provider cfg/config))
        ;; the config in force, written on main's root entity with the boot fact
        config (-> (cfg/with-provider cfg/config provider) (assoc :seon.config.agent/max-running max-running))
        program (program/load-all! @conn)
        program-ms (profile/ms-since t1)
        contracts (contract/instrument! '[seon.edn seon.config seon.contract seon.profile seon.print seon.print.select seon.print.emit])
        _ (warm! conn)
        _ (lib/raise-open-files! (+ 256 (* 2 (cfg/value :seon.config.root/max-agents))))
        _ (lib/static!)
        db @conn
        ;; physics: the fork server is forked while this is the one active thread (Jolt's carriers,
        ;; timer and watcher parked: the child resets them, seon.lib/fork!; docs/failure-points.md, Physics)
        {:keys [pid fd]} (fs/fork-server db)
        ;; then the GC's sweeper threads, a declared number (Chez would grow them by timing)
        sweepers (lib/gc-sweepers! (cfg/value :seon.config.root/gc-sweepers))
        events-in (a/chan (a/sliding-buffer 8192))
        ;; every event, for the API's taps (seon.root/events) and the render proc's (W2)
        events-mult (a/mult events-in)
        chans {:ext (a/chan 1024) :back (a/chan 4096) :to-writer (a/chan (cfg/value :seon.config.root/writer-buf))
               :fork-server-ev (a/chan 4096)
               ;; outside agents' attempts handed back for judgement (seon.server)
               :judge (a/chan 64)}
        view {:agents {} :writer {:tx-count 0} :stats {}
              :fork-server {:agents-forked 0 :pid pid :status :up :restarts 0}
              :store {:backend backend :path path :main main :agents-dir agents-dir :recovered? (boolean recovered?)
                      :recovered-branches recovered :open-ms (lib/r2 open-ms) :preload-ms (lib/r2 preload-ms)}
              :boot {:gc-sweepers sweepers :load-ms (lib/r2 load-ms) :program (assoc program :ms (lib/r2 program-ms))
                     :contracts (select-keys contracts [:armed :unchanged :invalid :ms]) :instrument (if contract/on? :on :off)}}
        graph-p (promise)
        root-procs-p (promise)
        ;; what the declaration (resources/seon/system.edn) names as :bind/<name>
        bindings (merge (select-keys chans [:ext :back :to-writer :fork-server-ev :judge])
                        {:view view :graph graph-p
                         :system root-procs-p :events events-in :events-mult events-mult
                         :conn conn :store-cfg store-cfg :durable? (= :lmdb (get-in store-cfg [:store :backend]))
                         :fork-server-pid pid :fork-server-fd fd :boot boot :provider provider :source source
                         :api-from-writer-buf (+ (cfg/value :seon.config.root/writer-buf) (cfg/value :seon.config.root/writer-batch) (cfg/value :seon.config.root/api-writes))
                         :stall-ms (or stall-ms ap/stall-ms) :reconciler-buf (cfg/value :seon.config.root/reconciler-buf)
                         :model-workers (cfg/value :seon.config.ai/max-in-flight) :model-rate (when (pos? (cfg/value :seon.config.ai/rate)) (cfg/value :seon.config.ai/rate))
                         :web-port (cfg/value :seon.config.web/port)})
        decl (system/read-decl (cfg/value :seon.config.root/system-path))
        built (system/build decl bindings nil)
        root-procs (:procs built) root-conns (:conns built)
        _ (deliver root-procs-p built)
        g (flow/create-flow {:procs root-procs :conns root-conns :stop-timeout-ms 10000})
        _ (deliver graph-p g)
        {root-errors :error-chan} (flow/start g)
        ;; an edge reader: Flow's error channel (every proc's, agents included), each
        ;; defect fed to the api as data, with the message the step took (:msg), which the
        ;; api answers (task `seon.root/a-proc-answers-every-request-it-took`)
        errors (doto (Thread. ^Runnable (fn []
                                (let [rd (fn [] (a/alts!! [root-errors]))]
                                  (loop []
                                    (let [[e c] (rd)]
                                      (if (nil? e)
                                        nil
                                        (do (a/>!! (:back chans) {:op :error :pid (::flow/pid e)
                                                                  :message (or (some-> (::flow/ex e) ex-message) (str e))
                                                                  :data (some-> (::flow/ex e) ex-data)
                                                                  :msg (::flow/msg e)})
                                            (recur)))))))
                              "seon-flow-errors")
                 (.setDaemon true) (.start))
        sys (merge chans {:cfg store-cfg :conn conn :graph g :t0 t0
                          :store-path path :boot boot
                          :bindings bindings
                          :events-in events-in :events-mult events-mult :errors errors})
        ]
    (flow/resume g)
    ;; dataflow start: the boot fact's commit wakes the reconciler on main as it is; a root
    ;; whose boot fact was refused cannot start anything, so its refusal ends the boot
    ;; with it, how the root before it on main ended (its stop fact, or :unclean)
    (let [prev (previous-end db)
          r @(call! sys {:op :write :req {:op :tx :branch :db :tx-data (cond-> (-> (task/boot-tx boot max-running) (into (cfg/facts config cfg/sources))
                                                                                  (into (wiki-tx db (cfg/checkout) (System/currentTimeMillis))))
                                                                         prev (conj {:seon.root/id :root :seon.root/previous-end prev}))}})]
      (when (contract/error? r) (throw (ex-info (str "root's boot fact was refused: " (:seon.error/message r)) r))))
    (when source (import-source! sys source))
    sys))

;; ====================================================================================
;; The API: callers outside the graph. Each call puts one request on :ext.
;; ====================================================================================

(defn- call!
  "Put m (with a fresh reply promise) on the api; a promise. After stop! the
  api is closed and the promise answers :seon.root/no-agent (\"root stopped\")."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.root/request] :seon.host/promise]}
  [sys m]
  (let [p (promise)]
    (when-not (a/>!! (:ext sys) (assoc m :p p))
      (deliver p (gone nil "root stopped")))
    p))

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
  of written signatures that main does not hold; the api's defect when it could not answer
  (task `seon.root/a-proc-answers-every-request-it-took`)."
  {:malli/schema [:=> [:cat :seon.root/sys] [:or :seon.error/fold :seon.error/base]]}
  [sys] @(call! sys {:op :errors}))

(defn events
  "A fresh tap of every event {:type :spawned|:ready|:reply|:tx|:merged|:tests|:killed|:ended|:dead ...}."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.host/chan]}
  [sys]
  (let [c (a/chan (a/sliding-buffer 1024))] (a/tap (:events-mult sys) c) c))

(defn request
  "The op (named as its fn below: spawn! clone! submit! run-tests! kill! merge!
  merge-main!) on agent id with args, as the api request it is: one table for these fns
  and the dashboard's calls (seon.web). a: the agent's entry in root's view, which merge!
  and merge-main! read; an op the agent cannot take answers its refusal."
  {:malli/schema [:=> [:cat :symbol [:maybe :seon.agent/id] [:maybe :map] [:maybe :map]] :map]}
  [op id args a]
  (let [live? (boolean (and a (not (gone-statuses (:status a)))))
        data-attrs (:data-attrs args :all)]
    (case op
      spawn! {:op :spawn :opts (or args {})}
      clone! {:op :clone :parent id :opts (or args {})}
      submit! (cond-> {:op :eval :id id :forms (:forms args)} (:ticks args) (assoc :ticks (:ticks args)) (:origin args) (assoc :origin (:origin args)))
      run-tests! (cond-> {:op :tests :id id} (:ticks args) (assoc :ticks (:ticks args)))
      kill! {:op :kill :id id}
      merge-main! (if live?
                    {:op :merge-main :id id :data-attrs data-attrs}
                    (gone id (str "no live agent " id)))
      merge! (cond (not (:branch a)) (refused 'seon.root/merge! (str "no agent " id) {:agent id})
                   (false? (:gate? args)) {:op :write :req {:op :merge :branch (:branch a) :agent id :data-attrs data-attrs}}
                   live? {:op :merge-tests :id id :merge-branch (:branch a) :data-attrs data-attrs}
                   :else (refused 'seon.root/merge! "agent not running, so its tests cannot run; merge with {:gate? false}" {:agent id}))
      (refused 'seon.root/request (str op " is not one of root's requests") {}))))

(defn- agent-entry {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] [:maybe :map]]}
  [sys id] (get-in @(call! sys {:op :state}) [:agents id]))

(defn- request!
  "Call root's op on agent id with args (request): a promise of its answer, or of its
  refusal."
  {:malli/schema [:=> [:cat :seon.root/sys :symbol [:maybe :seon.agent/id] [:maybe :map]] :seon.host/promise]}
  [sys op id args]
  (let [r (request op id args (when (#{'merge! 'merge-main!} op) (agent-entry sys id)))]
    (if (contract/error? r) (doto (promise) (deliver r)) (call! sys r))))

(defn spawn!
  "Fork an agent from the fork server onto a new branch of main; answers its id at once
  (status :spawning until its hello). opts {:branch kw :task string}; an existing :branch
  is resumed."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.root/spawn-opts] :seon.agent/id]}
  [sys opts]
  (let [r @(request! sys 'spawn! nil opts)]
    (if (:id r) (:id r) (throw (ex-info (:seon.error/message r) r)))))

(defn clone!
  "Fork a live agent into a new agent on a branch of its branch. A promise of
  {:ok true :id new-id}, or its refusal (the one error map)."
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

(defn judge!
  "Hand an outside agent's attempt to the reconciler for judgement, as a turn loop's end
  is: proofs in a fork, the branch read, the agent retired. attempt {:attempt :task :env
  :agent}. nil."
  {:malli/schema [:=> [:cat :seon.root/sys :map] :nil]}
  [sys attempt] (a/>!! (:judge sys) attempt) nil)

(defn run-tests!
  "The agent forks itself and runs its deftests in the child; a promise of
  {:pass :fail :error :reports [...]} (the run is bounded by its work: :seon.config.agent/test-ticks)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise] [:=> [:cat :seon.root/sys :seon.agent/id pos-int?] :seon.host/promise]]}
  ([sys agent-id] (request! sys 'run-tests! agent-id nil))
  ([sys agent-id ticks] (request! sys 'run-tests! agent-id {:ticks ticks})))

(defn agent-stats! "A promise of the agent's numbers now." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise]}
  [sys agent-id] (call! sys {:op :stats :id agent-id}))

(defn- write! {:malli/schema [:=> [:cat :seon.root/sys :seon.writer/request] :seon.host/promise]}
  [sys req] (call! sys {:op :write :req req}))

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

(defn merge!
  "Root merges the agent's branch into main (seon.merge, session M1); a promise of {:merged?
  :tx ...}, or :seon.merge/behind when main changed since the branch's merge base (merge
  main first), :seon.merge/unmerged, :seon.merge/uncontracted. Unless opts has :gate?
  false, the agent's tests run first in a fork of it (red: :seon.merge/red). opts {:data-attrs}: the data that
  merges beside the program (default :all: every attribute that is neither program nor
  observation)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise] [:=> [:cat :seon.root/sys :seon.agent/id :map] :seon.host/promise]]}
  ([sys agent-id] (merge! sys agent-id {}))
  ([sys agent-id opts] (request! sys 'merge! agent-id opts)))

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

(defn interrupt!
  "Interrupt the agent's evaluation in progress: SIGUSR1, run by Chez at the agent's next
  safe point, which also ends a blocking host call (preemption is physics: no message
  reaches a running native loop). nil."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id]
  (some-> (:pid (agent-entry sys agent-id)) (lib/c-kill lib/SIGUSR1))
  nil)

(defn kill! "SIGKILL the agent's process (through its agent proc). nil." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id] (request! sys 'kill! agent-id nil) nil)

(defn fs-call! "Ask the fork server for {:op :stats} or {:op :static}." {:malli/schema [:=> [:cat :seon.root/sys :seon.root/request] :any]}
  [sys msg] @(call! sys {:op :fs :req msg}))

(defn stop!
  "Stop root by letting the flow drain: the api closes (later calls answer \"root
  stopped\"); every agent is sent :stop and exits after the requests before it (SIGKILL
  past seon.agent-proc/stop-bound-ms); the writer's in-port closes, it commits and flushes what it
  took; the fork server is killed and reaped; then flow/stop (every proc exited), the
  reader, the error reader. Nothing accepted is lost.
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
        p (promise)]
    (a/>!! (:ext sys) {:op :shutdown :p p})
    (a/close! (:ext sys))
    (when (= ::late (deref p (+ ap/stop-bound-ms 1000) ::late))
      (a/>!! (:back sys) {:op :kill-all}))
    (let [drained (deref p 15000 :timeout)
          graph (flow/stop (:graph sys))]
      (some-> ^Thread (:errors sys) (.join 5000))
      (a/close! (:events-in sys))
      ;; the store is released and kept, whoever gave its path
      (d/release (:conn sys))
      (when (= :lmdb (:backend (:store (:cfg sys)))) (klmdb/close-env! (:store-path sys)))
      (cond-> {:stopped true :cause cause :drained drained :graph graph :agents-left 0 :ms (lib/r2 (profile/ms-since t0))}
        (contract/error? fact) (assoc :stop-fact-refused fact))))))

;; ====================================================================================
;; Root's threads: a declared number (session THREADS)
;; ====================================================================================

(defn declared-threads
  "The threads root runs, as {:roster {name-stem n} :gc-sweepers n} (stems as
  seon.lib/thread-roster folds them). Without a system, the process's own: main, Jolt's
  shutdown watcher, timer, io poller and fiber carriers, and the GC sweepers fixed at boot.
  The timer starts at the first timeout and the io poller at the first socket wait (an
  agent's), so a root has both once its first agent runs.
  With a running sys, also one thread per :mixed proc of its declaration (Flow runs :io
  procs, agent procs included, on the carriers), its two edge readers (Flow's
  error channel, the fork server's socket) and, while the web
  proc listens, the web server's acceptor and sweeper."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :seon.root/sys] :map]]}
  ([] {:roster {"main" 1 "jolt-shutdown" 1 "jolt-timer" 1 "jolt-io-poller" 1 "jolt-carrier" (lib/fiber-carriers)}
       :gc-sweepers (cfg/value :seon.config.root/gc-sweepers)})
  ([sys]
   (let [procs (vals (:procs @(get-in sys [:bindings :system])))
         mixed (count (filter #(= :mixed (or (:workload (spi/describe (:proc %))) :mixed)) procs))
         ;; the web proc's listener, when it bound its port (its :web event in root's view):
         ;; ring-chez-adapter's acceptor and its connection sweeper, each a future (a thread
         ;; of Jolt's agent pool; task seon.web/web-server-threads-are-joined-at-stop)
         web? (true? (get-in (state sys) [:web :listening]))]
     (update (declared-threads) :roster merge
             (cond-> {"async-mixed" mixed "seon-flow-errors" 1 "seon-fs-events" 1}
               web? (assoc "clojure-agent-pool" 2))))))

(defn threads
  "The threads this process runs now, measured as declared-threads declares them, and :os,
  the OS's count (seon.lib/nthreads): the roster's sum plus the sweepers when nothing
  else runs."
  {:malli/schema [:=> [:cat] :map]}
  []
  {:roster (lib/thread-roster) :gc-sweepers (lib/gc-sweepers! -1) :os (lib/nthreads)})



