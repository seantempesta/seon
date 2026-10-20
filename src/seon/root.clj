(ns seon.root
  "Root: the control process (lane F2, notes/option3-F1-flow.md; proto/flow f2-final
  proto.control's api, reader, boot and API). Boots the database
  (Datahike, E4a index), forks the single-threaded fork server before any thread exists,
  then runs ONE root graph of four procs:
    :api          :io     the entrance: owns the callers' reply promises and the agents'
                          graphs; routes every request and answer as data
    :writer       :mixed  the only d/transact, d/branch! and merge caller, with group
                          commit (drain the in-port, one commit per branch, one flush)
    :fork-server  :io     the fork server's command pipe, its events, its restart
    :reconciler   :io     main's commits through a bounded buffer (every commit's datoms; the hook a
                          reconciler and the dashboard plug into)
  and one proc per agent (`agent-proc`, added and removed with flow/update!), its stand-in:
  it owns root's end of the agent's socket and its process, and passes Flow's pause, resume and stop on to the
  agent's own loop (seon.step/run-loop), which runs the same step-fn contract.

  Ordering is dataflow: every proc starts and parks on its inputs. Shutdown flows too:
  the entrance closes, agents drain and exit, the writer's in-port closes, the writer
  drains and flushes last, the fork server is reaped, then flow/stop.

  Agents transact facts on their own branch; root reacts: the writer applies them, and
  only root's merge! advances main."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.query.execute] [seon.db :as pdb] [seon.lib :as lib]
            [seon.sock :as sock] [jolt.async.remote :as remote] [konserve.jolt.fasl :as codec] [seon.fx :as fx] [seon.fork-server :as fs] [seon.agent-proc :as ap]
            [seon.writer :as writer] [seon.reconcile :as reconcile] [seon.system :as system] [seon.program :as program] [seon.step :as step]
            [seon.config :as cfg] [seon.contract :as contract] [seon.task :as task] [konserve.jolt.lmdb :as klmdb] [clojure.java.shell]
            [clojure.core.async :as a]
            ;; the agent side, loaded here so the fork server and every agent inherit it compiled
            [seon.turn] [seon.fixtures] [my.fx] [my.task] [my.turn] [my.branch] [clojure.core.async.flow :as flow] [clojure.test]
            [taoensso.trove :as trove]))


;; ====================================================================================
;; The api: the entrance
;; ====================================================================================

(defn- no-agent {:malli/schema [:=> [:cat :keyword :string] :any]}
  [kind why]
  (case kind
    :eval [{:id -1 :tag :err :err {:message why} :handle nil :printed why :ms 0.0 :alloc-bytes 0}]
    :tests {:pass 0 :fail 0 :error 1 :reports [{:type :error :message why}]}
    :merge-tests {:merged? false :error why}
    :clone {:ok false :error why}
    {:ok false :error why}))

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
  (if-let [{:keys [p]} (get-in st [:pending rid])]
    (-> (reply! st p r) (update :pending dissoc rid) (update :writes disj rid))
    st))

(defn- view! {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/fn [:* :any]] :seon.root/api-state]}
  [st f & args] (apply update st :view f args))

(defn- set-agent
  "Merge m into agent id's view entry. A :killed agent stays :killed."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.agent/id :map] :seon.root/api-state]}
  [st id m]
  (if (get-in st [:view :agents id])
    (update-in st [:view :agents id]
               (fn [a] (let [a' (cond-> (merge a (dissoc m :ready :evals-add))
                                  (and (:ready m) (= :spawning (:status a))) (assoc :status :idle)
                                  (:evals-add m) (update :evals #(vec (take-last 20 (into (or % []) (:evals-add m))))))]
                         (if (and (= :killed (:status a)) (not (#{:killed :dead} (:status a')))) (assoc a' :status :killed) a'))))
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

(def ^:private agent-launcher
  "One launcher for every agent's proc (its step is the Var #'agent-proc)."
  (flow/process #'ap/agent-proc))

(defn- start-agent
  "Register a new agent: its socket (root keeps one end; the other goes to whoever forks
  it), its proc definition (added to the graph by update! at the end of the step). Root's
  end is a jolt.async.remote end (lane RC1): its :in is the stand-in's :from, so the
  agent's port is an ordinary channel. Answers [st id agent-end-fd]."
  {:malli/schema [:=> [:cat :seon.root/api-state :map] [:tuple :seon.root/api-state :seon.agent/id :seon.lib/fd]]}
  [st {:keys [branch task cloned-from]}]
  (let [n (inc (:next-id st))
        id (keyword (str "a" n))
        ;; the entity ids this agent mints on its branch (its own block: L1's lease)
        lease (+ (:lease-base st) (* n cfg/EID-LEASE))
        branch (or branch (keyword (str "agent-" n)))
        [r a] (sock/socketpair)
        ;; fiber pumps: an end costs no thread of its own (root may run a thousand agents)
        end (remote/open r {:codec {:encode codec/encode :decode codec/decode} :pump :fiber :buf cfg/AGENT-BUF})]
    [(-> st (assoc :next-id n ::update true)
         (assoc-in [:agents id] {:branch branch :lease lease})
         (assoc-in [:agent-procs id] {:proc agent-launcher
                                      :args {:id id :end end :branch branch
                                             :to-writer (:to-writer st) :back (:back st) :events (:events st)
                                             :fx-kinds (system/fx-owners (get-in st [:system :procs]))}
                                      :chan-opts {:in {:buf-or-n cfg/AGENT-BUF}}})
         (assoc-in [:view :agents id] (cond-> {:branch branch :status :spawning :task task :pid nil
                                               :forked-ms nil :mem-mb nil :evals [] :tests nil}
                                        cloned-from (assoc :cloned-from cloned-from))))
     id a]))

(defn- end-agent
  "The agent's process is gone and its stand-in saw its last frame: its proc leaves the
  graph (update! at the end of the step) and its branch's connection is released."
  {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :map] :seon.root/api-state]}
  [st o {:keys [agent rid pid inflight]}]
  (let [{:keys [branch]} (get-in st [:agents agent])
        killed? (= :killed (get-in st [:view :agents agent :status]))
        why (if rid "agent stopped" "agent died")]
    (step/out! o :events (step/event {:type (if killed? :killed :dead) :agent agent}))
    (let [st (reduce (fn [st r] (deliver-rid st r (no-agent (get-in st [:pending r :kind]) why))) st inflight)
          st (cond-> st rid (deliver-rid rid {:stopped true :agent agent}))]
      (-> st (update :agents dissoc agent) (update :agent-procs dissoc agent) (assoc ::update true)
          (set-agent agent {:status (if killed? :killed :dead) :ended-ms (System/currentTimeMillis) :running nil})))))

(defn- rid! {:malli/schema [:=> [:cat :seon.root/api-state] [:tuple :seon.root/api-state :int]]}
  [st] (let [r (inc (:rid st))] [(assoc st :rid r) r]))

(defn- pend {:malli/schema [:=> [:cat :seon.root/api-state :int [:maybe [:or :seon.host/promise :map]] :keyword [:maybe :seon.agent/id]] :seon.root/api-state]}
  [st rid p kind agent] (if p (assoc-in st [:pending rid] {:p p :kind kind :agent agent}) st))

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
      :spawn (let [opts (:opts m)
                   holder (and (:branch opts)
                               (some (fn [[aid a]] (when (and (= (:branch opts) (:branch a)) (not (:ended-ms a))
                                                              (not (#{:dead :killed} (:status a))))
                                                     aid))
                                     (get-in st [:view :agents])))]
               (cond
                 holder (reply! st p {:error (str "branch " (:branch opts) " is held by live agent " holder)
                                      :branch (:branch opts) :agent holder})
                 (>= (count (:agents st)) cfg/MAX-AGENTS) (reply! st p {:error "too many live agents" :max cfg/MAX-AGENTS})
                 :else (let [[st aid fd] (start-agent st opts)
                             {:keys [branch lease]} (get-in st [:agents aid])]
                         (step/out! o :to-writer {:op :spawn :agent aid :branch branch :lease lease :ns (lib/now-ns) :fd fd :dest {:spawn aid}})
                         (reply! st p {:id aid}))))
      :clone (let [parent (:parent m)]
               (if (or (nil? (get-in st [:agents parent])) (>= (count (:agents st)) cfg/MAX-AGENTS))
                 (reply! st p {:ok false :error (str "no live agent " parent)})
                 (let [[st aid fd] (start-agent st (merge {:task (get-in st [:view :agents parent :task])} (:opts m) {:cloned-from parent}))
                       {:keys [branch lease]} (get-in st [:agents aid])
                       [st rid] (rid! st)]
                   ;; the clone's socket goes to its parent with the request
                   (to-agent (pend st rid p :clone parent) parent
                             {:op :req :rid rid :kind :clone :new aid :branch branch :pass-fd fd :lease lease
                              :msg {:op :clone :agent aid :branch branch}}))))
      (:eval :tests :merge-tests :stats :ping :merge-main)
      (if (nil? (get-in st [:agents id]))
        (reply! st p (no-agent op (str "no live agent " id)))
        (let [[st rid] (rid! st)
              msg (case op
                    :eval (cond-> {:op :eval :forms (:forms m)} (:timeout-ms m) (assoc :timeout-ms (:timeout-ms m)) (:origin m) (assoc :origin (:origin m)))
                    (:tests :merge-tests) {:op :tests :timeout-ms (:timeout-ms m)}
                    :stats {:op :stats}
                    :ping {:op :ping}
                    :merge-main {:op :merge-main :data-attrs (:data-attrs m)})]
          (to-agent (pend st rid p op id) id {:op :req :rid rid :kind op :msg msg :merge-branch (:merge-branch m) :data-attrs (:data-attrs m)})))
      ;; requests for the writer: answered by rid
      :write (let [[st rid] (rid! st)
                   req (:req m)
                   dest (case (:op req) :merge-main {:merge-main (:agent req) :rid rid} :merge {:merged (:agent req) :rid rid} {:rid rid})]
               (step/out! o :to-writer (assoc (dissoc req :agent) :dest dest))
               (-> (pend st rid p (:op req) nil) (update :writes (fnil conj #{}) rid)))
      :stop (if (get-in st [:agents id])
              (let [[st rid] (rid! st)]
                (to-agent (pend st rid p :stop id) id {:op :drain :rid rid}))
              (reply! st p {:stopped true :agent id :already true}))
      :kill (cond-> (if (get-in st [:agents id])
                      (-> st (set-agent id {:status :killed}) (to-agent id {:op :kill}))
                      st)
              p (reply! p {:killed id}))
      :message (let [msg {:op :message :from (:from m :root) :body (:body m) :ns (sock/now-ns)}]
                 (reduce #(to-agent %1 %2 {:op :write :msg msg}) st (:to m)))
      :fs (let [[st rid] (rid! st)]
            (step/out! o :to-fs (assoc (:req m) :rid rid))
            (pend st rid p :fs nil))
      :state (reply! st p (:view st))
      :shutdown (assoc st :shutdown-p p)
      st)))

(defn- back-op
  "A message from a stand-in, a fork-server or writer event, or the error reader."
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
        (:rid dest) (deliver-rid st (:rid dest) r)
        (:agent dest) (to-agent st (:agent dest) {:op :write :msg (cond (:op r) r
                                                                          (= :branched (:op dest)) {:op :branched :k (:k dest) :ok (:ok r) :error (:error r) :cfg (:cfg r) :lease (:lease r)}
                                                                          :else (assoc r :op (:op dest) :k (:k dest)))})
        (:spawn dest) (if (:ok r) st (-> st (set-agent (:spawn dest) {:error (:error r)}) (to-agent (:spawn dest) {:op :failed :error (:error r)})))
        :else st))

    (:ev m)
    (case (:ev m)
      :forked (do (step/out! o :events (step/event {:type :spawned :agent (:agent m) :pid (:pid m) :fork-ms (:fork-ms m)}))
                  (when (= :killed (get-in st [:view :agents (:agent m) :status])) (lib/c-kill (:pid m) 9))
                  (-> st (set-agent (:agent m) {:pid (:pid m)})
                      (view! update-in [:server :agents-forked] (fnil inc 0))
                      (view! update-in [:stats :fork-ms] lib/push-window (:fork-ms m) 500)
                      (to-agent (:agent m) {:op :forked :pid (:pid m)})))
      :exit (if-let [aid (some (fn [[aid a]] (when (= (:pid m) (:pid a)) aid)) (get-in st [:view :agents]))]
              (set-agent st aid {:exit (:status m)})
              st)
      :fork-failed (let [why (str "fork refused: " (:active-threads m) " active threads")]
                     (-> st (set-agent (:agent m) {:error why}) (to-agent (:agent m) {:op :failed :error why})))
      :applied (-> st (view! update-in [:stats :lag-ms] lib/push-window (:lag-ms m) 200)
                   (view! assoc-in [:server :mismatch] (:mismatch m)))
      :load-errors (view! st update-in [:server :load-errors] (fnil into []) (:errors m))
      ;; fork requests the dead server never served fail; agents live on
      :server-down (do (step/out! o :events (step/event {:type :server-down :pid (:old m)}))
                       (reduce (fn [st [aid a]]
                                 (if (and (= :spawning (:status a)) (nil? (:pid a)) (get-in st [:agents aid]))
                                   (-> st (set-agent aid {:error "fork server went down before the fork"})
                                       (to-agent aid {:op :failed :error "fork server went down"}))
                                   st))
                               (view! st update :server assoc :status :restarting) (get-in st [:view :agents])))
      :server-lost (do (step/out! o :events (step/event {:type :server-lost}))
                       (view! st update :server assoc :status :lost))
      :server-up (do (step/out! o :events (step/event {:type :server-up :pid (:pid m)}))
                     (view! st update :server (fn [s] (-> s (assoc :status :up :pid (:pid m)) (update :restarts (fnil inc 0))))))
      st)

    :else
    (case (:op m)
      :status (set-agent st (:agent m) (:set m))
      :stat (case (:k m)
              :noise (view! st update :stats assoc :noise (:v m) :noise-ordered (:ordered m) :noise-first-bad (:bad m))
              (view! st update-in [:stats (:k m)] lib/push-window (:v m) 100000))
      :message (let [msg {:op :message :from (:from m) :body (:body m) :ns (sock/now-ns)}]
                 (reduce #(to-agent %1 %2 {:op :write :msg msg}) st (:to m)))
      ;; a proc's request (the reconciler's, the web's, MCP's): an ordinary entrance request
      ;; whose answer goes back to the proc as data
      :request (ext-op st o (assoc (:req m) :p {:route (:route m) :tag (:tag m)}))
      :clone-failed (to-agent st (:agent m) {:op :failed :error (:error m)})
      :ended (end-agent st o m)
      :writer (view! st (fn [v] (-> v (assoc-in [:writer :tx-count] (:tx-count m))
                                    (update-in [:writer :batch-ms] lib/push-window (:batch-ms m) 200)
                                    (update-in [:writer :recent] #(lib/push-window % [(lib/now-ns) (:n-tx m)] 2000))
                                    (update-in [:stats :batches] (fnil inc 0))
                                    (update-in [:stats :commits] (fnil + 0) (:commits m))
                                    (update-in [:stats :batched-tx] (fnil + 0) (:n-tx m)))))
      ;; the end of the flow: the writer has drained; the fork server goes last
      :drained (do (step/out! o :to-fs {:op :shutdown}) st)
      :fs-down (do (some-> (:shutdown-p st) (deliver {:drained true :fork-server (:pid m)})) st)
      ;; a defect in a root proc: recorded as data (its error map when it carries one)
      :error (view! st update :root-errors (fnil #(vec (take-last 20 (conj % %2))) []) (merge {:pid (:pid m) :message (:message m)} (:data m)))
      :kill-all (reduce (fn [st aid] (-> st (set-agent aid {:status :killed}) (to-agent aid {:op :kill}))) st (keys (:agents st)))
      st)))

(defn- api-one {:malli/schema [:=> [:cat :seon.root/api-state :seon.host/volatile :keyword [:maybe :map]] :seon.root/api-state]}
  [st o in m]
  (cond
    (and (= in :ext) (nil? m))
    ;; the entrance closed: agents drain and exit; then the writer's in-port closes
    (let [st (assoc st :closing true)]
      (reduce (fn [st aid] (let [[st rid] (rid! st)] (to-agent st aid {:op :drain :rid rid}))) st (keys (:agents st))))
    (= in :ext) (ext-op st o m)
    (nil? m) st
    :else (back-op st o m)))

(defn api-proc
  "The entrance. In :ext (the API's callers, each request with its reply promise: the
  promises live only here), :back (the agents' stand-ins and the error reader),
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
                 ::flow/in-ports {:ext (:ext args) :back (:back args)}
                 ::flow/out-ports {:to-writer (:to-writer args) :events (:events args)}))
  ([st tr]
   ;; the stop transition: nothing accepted is left unanswered
   (when (= tr ::flow/stop)
     (doseq [[_ {:keys [p kind]}] (:pending st) :when (not (map? p))] (deliver p (no-agent kind "root stopped"))))
   st)
  ([st in m]
   (let [o (volatile! {})
         ;; this message, then whatever else is already queued at the entrance and the back door
         st (loop [st (api-one st o in m) k 1]
              (if (< k cfg/API-BATCH)
                (if-let [[ch c-in] (some (fn [[ch c-in]] (when-let [x (a/poll! ch)] [x c-in]))
                                         (cond-> [[(:back st) :back]] (< (count (:writes st)) cfg/API-WRITES) (conj [(:ext st) :ext])))]
                  (recur (api-one st o c-in ch) (inc k))
                  st)
                st))
         st (if (::update st)
              (do (flow/update! @(:graph st) {:procs (merge (get-in st [:system :procs]) (:agent-procs st)) :conns (get-in st [:system :conns])})
                  (dissoc st ::update))
              st)]
     ;; the writer's in-port closes once the entrance is closed and every agent has ended
     (when (and (:closing st) (empty? (:agents st)) (not (:writer-closed st)))
       (a/close! (:to-writer st)))
     (doseq [[id msg] (::to-agents st)] (step/out! o [id :in] msg))
     (doseq [[route msg] (::replies st)] (step/out! o route msg))
     [(cond-> (dissoc st ::to-agents ::replies)
        (and (:closing st) (empty? (:agents st))) (assoc :writer-closed true)
        ;; at the bound of writer requests in flight: read answers only, not the entrance
        true (assoc ::flow/input-filter (if (>= (count (:writes st)) cfg/API-WRITES) #(not= :ext %) identity)))
      @o])))

;; ====================================================================================
;; Boot
;; ====================================================================================

(defn- warm!
  "Run, before the fork server is forked, every code path agents and the server take,
  so forked children inherit it compiled."
  {:malli/schema [:=> [:cat :seon.host/conn] :any]}
  [conn]
  (let [db @conn]
    (dotimes [_ 3]
      (d/q '[:find ?n . :in $ ?v :where [?e :var/name ?v] [?e :var/doc ?n]] db 'app.m0/f1)
      (d/q '[:find (count ?e) . :where [?e :var/name]] db)
      (d/q '[:find [?n ...] :where [?e :ns/name ?n]] db)
      (d/pull db '[*] [:var/name 'app.m0/f1])
      (d/pull db '[:task/name {:task/refs [:var/name]}] [:task/name :app.m0/tidy])
      (d/db-with db [{:note/text "warm" :note/agent :warm}])
      (d/with db [{:note/text "warm" :note/agent :warm}])
      (sock/frame {:op :reply :items [{:id 1 :printed "x" :ms 0.1}]}))
    (let [nsx (create-ns 'seon.warm)]
      (binding [*ns* nsx]
        (refer-clojure)
        (refer 'clojure.test :only '[deftest is testing])
        (eval '(defn warm-f [x] (* 2 x)))
        (eval '(deftest warm-t (is (= 4 (warm-f 2)))))
        (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/run-tests nsx)))
      (remove-ns 'seon.warm))))

(declare call!)

(defn start!
  "Boot the control process, the fork server and the root graph. opts:
  {:datoms n :path dir (durable) :backend :lmdb|:file :log? true
   :max-running n (parallel attempts) :provider p (the attempts' model; default the
   stand-in seon.fixtures/reply, or DeepSeek from SEON_AI_* when SEON_AI_PROVIDER is
   openai-compat)}."
  {:malli/schema [:=> [:cat :map] :seon.root/sys]}
  [{:keys [datoms log? path backend max-running provider] :or {datoms 2000 max-running reconcile/default-max-running}}]
  (lib/ignore-sigpipe!)
  (when-not log? (trove/set-log-fn! nil))
  (let [t0 (lib/now-ns)
        ;; agents write their own branches, so the store is shared: a durable store, at a
        ;; fresh directory of this root's own (deleted by stop!) when none is given
        given (or path (System/getenv "SEON_STORE"))
        path (or given (str (or (System/getenv "SEON_STORE_ROOT") "/tmp") "/seon-store-" (random-uuid)))
        backend (or backend (some-> (System/getenv "SEON_BACKEND") keyword) :lmdb)
        {store-cfg :cfg conn :conn recovered? :recovered?} (pdb/open! backend path datoms)
        open-ms (lib/ms-since t0)
        t-pre (lib/now-ns)
        _ (pdb/preload! @conn)
        preload-ms (lib/ms-since t-pre)
        recovered (if recovered? (vec (sort (disj (d/branches conn) :db))) [])
        load-ms (lib/ms-since t0)
        t1 (lib/now-ns)
        ;; this root's boot id (attempts of an earlier boot are reaped); its fact is written
        ;; through the writer once the graph runs, and that commit is the reconciler's first pass
        boot (str (random-uuid))
        provider (or provider (if (= "openai-compat" (System/getenv "SEON_AI_PROVIDER"))
                                {:kind :openai-compat :base-url (System/getenv "SEON_AI_BASE_URL")
                                 :model (System/getenv "SEON_AI_MODEL") :api-key-env (System/getenv "SEON_AI_API_KEY_ENV")}
                                {:kind :scripted :script 'seon.fixtures/reply}))
        program (program/load-all! @conn)
        program-ms (lib/ms-since t1)
        contracts (contract/instrument! '[seon.contract seon.profile])
        _ (warm! conn)
        _ (lib/raise-open-files! (+ 256 (* 2 cfg/MAX-AGENTS)))
        _ (lib/static!)
        db @conn
        ;; physics: the fork server is forked before any thread exists
        {:keys [pid fd]} (fs/fork-server db)
        events-in (a/chan (a/sliding-buffer 8192))
        chans {:ext (a/chan 1024) :back (a/chan 4096) :to-writer (a/chan cfg/WRITER-BUF)
               :server-ev (a/chan 4096) :stop-ch (a/promise-chan)
               ;; outside agents' attempts handed back for judgement (seon.entrance)
               :judge (a/chan 64)}
        view {:agents {} :writer {:tx-count 0} :stats {}
              :server {:agents-forked 0 :pid pid :status :up :restarts 0}
              :store {:backend backend :path path :recovered? (boolean recovered?)
                      :recovered-branches recovered :open-ms (lib/r2 open-ms) :preload-ms (lib/r2 preload-ms)}
              :boot {:load-ms (lib/r2 load-ms) :program (assoc program :ms (lib/r2 program-ms))
                     :contracts (select-keys contracts [:armed :unchanged :invalid :ms]) :guarantees (if contract/on? :on :off)}}
        graph-p (promise)
        root-procs-p (promise)
        ;; what the declaration (resources/seon/system.edn) names as :bind/<name>
        bindings (merge (select-keys chans [:ext :back :to-writer :server-ev :judge])
                        {:view view :graph graph-p :lease-base (pdb/lease-base conn)
                         :system root-procs-p :events events-in
                         :conn conn :store-cfg store-cfg :durable? (= :lmdb (get-in store-cfg [:store :backend]))
                         :fork-server-pid pid :fork-server-fd fd :boot boot :provider provider
                         :api-from-writer-buf (+ cfg/WRITER-BUF cfg/WRITER-BATCH cfg/API-WRITES)
                         :flush-ms cfg/FLUSH-MS :reconciler-buf cfg/RECONCILER-BUF
                         :model-lanes cfg/MODEL-LANES :model-rate (when (pos? cfg/MODEL-RATE) cfg/MODEL-RATE)})
        decl (system/read-decl system/path)
        built (system/build decl bindings nil)
        root-procs (:procs built) root-conns (:conns built)
        _ (deliver root-procs-p built)
        g (flow/create-flow {:procs root-procs :conns root-conns :stop-timeout-ms 10000})
        _ (deliver graph-p g)
        {root-errors :error-chan} (flow/start g)
        ;; an edge reader: Flow's error channel (every proc's, agents included), each
        ;; defect fed to the api as data
        errors (doto (Thread. (fn []
                                (let [rd (fn [] (a/alts!! [root-errors]))]
                                  (loop []
                                    (let [[e c] (rd)]
                                      (if (nil? e)
                                        nil
                                        (do (a/>!! (:back chans) {:op :error :pid (::flow/pid e)
                                                                  :message (or (some-> (::flow/ex e) ex-message) (str e))
                                                                  :data (some-> (::flow/ex e) ex-data)})
                                            (recur))))))))
                 (.setDaemon true) (.start))
        sys (merge chans {:cfg store-cfg :conn conn :graph g :t0 t0
                          :owned-store (when-not given path) :boot boot
                          :bindings bindings
                          :events-in events-in :events-mult (a/mult events-in) :errors errors})
        ]
    (flow/resume g)
    ;; dataflow start: the boot fact's commit wakes the reconciler on main as it is
    @(call! sys {:op :write :req {:op :tx :branch :db :tx-data (task/boot-tx boot max-running)}})
    sys))

;; ====================================================================================
;; The API: callers outside the graph. Each call puts one request on :ext.
;; ====================================================================================

(defn- call!
  "Put m (with a fresh reply promise) on the entrance; a promise. After stop! the
  entrance is closed and the promise answers {:ok false :error \"root stopped\"}."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.root/request] :seon.host/promise]}
  [sys m]
  (let [p (promise)]
    (when-not (a/>!! (:ext sys) (assoc m :p p))
      (deliver p (no-agent (:op m) "root stopped")))
    p))

(defn state
  "The current state value (plain EDN), from the api's view. With {:datoms true}, main's
  datom count too, taken here in the caller from main's db value (O(datoms): a poll
  should not ask for it)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys [:maybe :map]] :map]]}
  ([sys] (state sys nil))
  ([sys {:keys [datoms]}]
  (let [v (deref (call! sys {:op :state}) 5000 {})
        now (lib/now-ns)
        recent (reduce + 0 (keep (fn [[t n]] (when (< (- now t) 1000000000) n)) (get-in v [:writer :recent])))
        db @(:conn sys)
        {:keys [batches commits batched-tx lag-ms fork-ms]} (:stats v)]
    (-> v
        (update :writer #(-> % (dissoc :recent :batch-ms) (assoc :tx-per-s (double recent) :batch-p50-ms (lib/p50 (:batch-ms %)))))
        (assoc :main (cond-> {:t (:max-tx db)} datoms (assoc :datoms (count (d/datoms db :eavt)))))
        (update :server assoc :lag-ms (lib/p50 lag-ms) :fork-ms-p50 (lib/p50 fork-ms))
        (update :store merge (when batches {:batches batches :commits commits :tx-per-batch (lib/r2 (/ (double (or batched-tx 0)) batches))}))))))

(defn stats "The raw counters and windows." {:malli/schema [:=> [:cat :seon.root/sys] [:maybe :map]]}
  [sys] (:stats (deref (call! sys {:op :state}) 5000 {})))

(defn events
  "A fresh tap of every event {:type :spawned|:ready|:reply|:tx|:merged|:tests|:killed|:dead ...}."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.host/chan]}
  [sys]
  (let [c (a/chan (a/sliding-buffer 1024))] (a/tap (:events-mult sys) c) c))

(defn spawn!
  "Fork an agent from the fork server onto a new branch of main; answers its id at once
  (status :spawning until its hello). opts {:branch kw :task string}; an existing :branch
  is resumed."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.root/spawn-opts] :seon.agent/id]}
  [sys opts]
  (let [r @(call! sys {:op :spawn :opts opts})]
    (if (:id r) (:id r) (throw (ex-info (str (:error r)) (dissoc r :error))))))

(defn clone!
  "Fork a live agent into a new agent on a branch of its branch. A promise of
  {:ok true :id new-id} or {:ok false :error}."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise] [:=> [:cat :seon.root/sys :seon.agent/id :map] :seon.host/promise]]}
  ([sys agent-id] (clone! sys agent-id {}))
  ([sys agent-id opts] (call! sys {:op :clone :parent agent-id :opts opts})))

(defn- agent-entry {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] [:maybe :map]]}
  [sys id] (get-in (deref (call! sys {:op :state}) 5000 {}) [:agents id]))

(defn submit!
  "Send forms (a string) to the agent; a promise of the reply vector, one map per form.
  opts {:timeout-ms n}: each form is bounded at n ms; {:origin :outside|:ui}: who asked,
  recorded with each evaluation."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id :string] :seon.host/promise] [:=> [:cat :seon.root/sys :seon.agent/id :string [:maybe :map]] :seon.host/promise]]}
  ([sys agent-id forms-string] (submit! sys agent-id forms-string nil))
  ([sys agent-id forms-string {:keys [timeout-ms origin]}]
   (call! sys (cond-> {:op :eval :id agent-id :forms forms-string} timeout-ms (assoc :timeout-ms timeout-ms) origin (assoc :origin origin)))))

(defn judge!
  "Hand an outside agent's attempt to the reconciler for judgement, as a turn loop's end
  is: proofs in a fork, the branch read, the agent retired. attempt {:attempt :task :env
  :agent}. nil."
  {:malli/schema [:=> [:cat :seon.root/sys :map] :nil]}
  [sys attempt] (a/>!! (:judge sys) attempt) nil)

(defn run-tests!
  "The agent forks itself and runs its deftests in the child; a promise of
  {:pass :fail :error :reports [...]}."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise] [:=> [:cat :seon.root/sys :seon.agent/id :int] :seon.host/promise]]}
  ([sys agent-id] (run-tests! sys agent-id 30000))
  ([sys agent-id timeout-ms] (call! sys {:op :tests :id agent-id :timeout-ms timeout-ms})))

(defn agent-stats! "A promise of the agent's numbers now." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise]}
  [sys agent-id] (call! sys {:op :stats :id agent-id}))

(defn- write! {:malli/schema [:=> [:cat :seon.root/sys :seon.writer/request] :seon.host/promise]}
  [sys req] (call! sys {:op :write :req req}))

(defn apply!
  "Re-read root's system declaration (seon.system/path, or the file at p) and change the
  running graph to it: only the procs whose declaration changed are stopped, started or
  rewired; the others keep running with their queued messages. A promise of Flow's
  update! answer {:started :stopped :kept :lost :ms}."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :seon.host/promise] [:=> [:cat :seon.root/sys :string] :seon.host/promise]]}
  ([sys] (apply! sys system/path))
  ([sys p] (call! sys {:op :apply :decl (system/read-decl p) :bindings (:bindings sys)})))

(defn merge!
  "Root merges the agent's branch into main (seon.merge, lane M1); a promise of {:merged?
  :tx ...}, or :seon.merge/behind when main changed since the branch's merge base (merge
  main first), :seon.merge/unmerged, :seon.merge/uncontracted. Unless opts has :gate?
  false, the agent's tests run first in a fork of it. opts {:data-attrs}: the data that
  merges beside the program (default :all: every attribute that is neither program nor
  observation)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise] [:=> [:cat :seon.root/sys :seon.agent/id :map] :seon.host/promise]]}
  ([sys agent-id] (merge! sys agent-id {}))
  ([sys agent-id {:keys [gate? data-attrs] :or {gate? true data-attrs :all}}]
   (let [ag (agent-entry sys agent-id)]
     (cond
       (not (:branch ag)) (doto (promise) (deliver {:merged? false :error (str "no agent " agent-id)}))
       (not gate?) (write! sys {:op :merge :branch (:branch ag) :agent agent-id :data-attrs data-attrs})
       (#{:dead :killed} (:status ag)) (doto (promise) (deliver {:merged? false :error "agent not running, so its tests cannot run; merge with {:gate? false}"}))
       :else (call! sys {:op :merge-tests :id agent-id :merge-branch (:branch ag) :data-attrs data-attrs :timeout-ms 30000})))))

(defn merge-main!
  "git merge main, on the agent's branch (seon.merge): main's changes since the merge base
  come into the branch and the agent's db; each conflict stays on the branch as an unmerged
  entry. A promise of {:ok true :applied [key] :conflicts [...] :t}. opts {:data-attrs}
  (default :all)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise]
                  [:=> [:cat :seon.root/sys :seon.agent/id :map] :seon.host/promise]]}
  ([sys agent-id] (merge-main! sys agent-id {}))
  ([sys agent-id {:keys [data-attrs] :or {data-attrs :all}}]
   (let [ag (agent-entry sys agent-id)]
     (if (or (nil? ag) (#{:dead :killed} (:status ag)))
       (doto (promise) (deliver {:ok false :applied [] :conflicts [] :error (str "no live agent " agent-id)}))
       ;; the agent owns its branch's writes: it merges main itself
       (call! sys {:op :merge-main :id agent-id :data-attrs data-attrs})))))

(defn branch-db
  "The writer's current db value of branch (:db is main), for root's reads: a promise of
  {:ok true :db db :base-t t} (base-t: main's t at the branch's merge base)."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.db/branch] :seon.host/promise]}
  [sys branch] (write! sys {:op :branch-db :branch branch}))

(defn branch-diff "The agent's branch versus main. A promise." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise]}
  [sys agent-id]
  (if-let [b (:branch (agent-entry sys agent-id))]
    (write! sys {:op :branch-diff :branch b})
    (doto (promise) (deliver {:ok false :error (str "no agent " agent-id)}))))

(defn branch-info "The writer's view of an agent's branch: {:datoms :t :log}. A promise." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :seon.host/promise]}
  [sys agent-id]
  (write! sys {:op :branch-info :branch (:branch (agent-entry sys agent-id))}))

(defn transact-main!
  "Root's own write to main. A promise. opts {:flush? true}: answered only after the
  group flush that makes it durable (for a write an outside effect follows)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.db/tx-data] :seon.host/promise]
                  [:=> [:cat :seon.root/sys :seon.db/tx-data [:maybe :map]] :seon.host/promise]]}
  ([sys tx-data] (transact-main! sys tx-data nil))
  ([sys tx-data {:keys [flush?]}] (write! sys (cond-> {:op :tx :branch :db :tx-data tx-data} flush? (assoc :flush? true)))))

(defn send-message!
  "Deliver body to every agent in tos (encoded once); the agent takes it with (messages)."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.agent/id] :any] :nil]}
  [sys tos body] (call! sys {:op :message :to (vec tos) :body body}) nil)

(defn interrupt!
  "Interrupt the agent's evaluation in progress: SIGUSR1, run by Chez at the agent's next
  safe point, which also ends a blocking host call (preemption is physics: no message
  reaches a running native loop). nil."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id]
  (some-> (:pid (agent-entry sys agent-id)) (lib/c-kill lib/SIGUSR1))
  nil)

(defn kill! "SIGKILL the agent's process (through its stand-in). nil." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id] (call! sys {:op :kill :id agent-id}) nil)

(defn pause-agent!
  "Flow's pause of the agent's proc (flow/pause-proc on the root graph), passed on to the
  agent's own loop: its form in flight finishes, then it holds requests. nil."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id] (flow/pause-proc (:graph sys) agent-id) nil)

(defn resume-agent! "Flow's resume of the agent's proc. nil." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id] (flow/resume-proc (:graph sys) agent-id) nil)

(defn agent-health
  "Flow's ping of the agent's proc: {:status :running|:paused :count :state}, or :unknown
  when it does not answer within ms."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.root/health] [:=> [:cat :seon.root/sys :seon.agent/id :int] :seon.root/health]]}
  ([sys agent-id] (agent-health sys agent-id 1000))
  ([sys agent-id ms]
   (if-let [m (flow/ping-proc (:graph sys) agent-id :timeout-ms ms)]
     {:status (::flow/status m) :count (::flow/count m) :state (::flow/state m)}
     :unknown)))

(defn agent-ping
  "A ping answered by the agent's own process loop (it answers after its form in flight):
  {:status :count :held :state}, or :unknown within ms."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.agent/id] :seon.root/health] [:=> [:cat :seon.root/sys :seon.agent/id :int] :seon.root/health]]}
  ([sys agent-id] (agent-ping sys agent-id 1000))
  ([sys agent-id ms] (let [r (deref (call! sys {:op :ping :id agent-id}) ms :unknown)] (if (and (map? r) (:status r)) r :unknown))))

(defn stop-agent!
  "Flow's stop for one agent: it exits after the requests before the stop; root awaits
  its death, SIGKILLs past SEON_STOP_BOUND_MS. Answers {:stopped true ...} or :timeout."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :any]}
  [sys agent-id]
  (let [p (call! sys {:op :stop :id agent-id})]
    (if (= ::late (deref p cfg/STOP-BOUND-MS ::late))
      (do (kill! sys agent-id) (deref p 5000 :timeout))
      @p)))

(defn freeze! "SIGSTOP the agent's process. nil." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id] (some-> (:pid (agent-entry sys agent-id)) (lib/c-kill lib/SIGSTOP)) nil)
(defn thaw! "SIGCONT the agent's process. nil." {:malli/schema [:=> [:cat :seon.root/sys :seon.agent/id] :nil]}
  [sys agent-id] (some-> (:pid (agent-entry sys agent-id)) (lib/c-kill lib/SIGCONT)) nil)

(defn fs-call! "Ask the fork server for {:op :stats} or {:op :static}." {:malli/schema [:=> [:cat :seon.root/sys :seon.root/request] :any]}
  [sys msg] (deref (call! sys {:op :fs :req msg}) 30000 :timeout))

(defn health
  "Flow's own observation of every root proc (its ping-map-fn), or :unknown within ms."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] [:map-of :keyword :seon.root/health]] [:=> [:cat :seon.root/sys :int] [:map-of :keyword :seon.root/health]]]}
  ([sys] (health sys 1000))
  ([sys ms]
   (into {} (for [pid [:api :writer :fork-server :flusher :reconciler]]
              [pid (if-let [m (flow/ping-proc (:graph sys) pid :timeout-ms ms)]
                     {:status (::flow/status m) :count (::flow/count m) :state (::flow/state m)}
                     :unknown)]))))

(defn stop!
  "Stop root by letting the flow drain: the entrance closes (later calls answer \"root
  stopped\"); every agent is sent :stop and exits after the requests before it (SIGKILL
  past SEON_STOP_BOUND_MS); the writer's in-port closes, it commits and flushes what it
  took; the fork server is killed and reaped; then flow/stop (every proc exited), the
  reader, the error reader. Nothing accepted is lost.
  Answers {:stopped true :drained .. :graph true|{::flow/running #{pids}} :ms}."
  {:malli/schema [:=> [:cat :seon.root/sys] :map]}
  [sys]
  (let [t0 (lib/now-ns)
        p (promise)]
    (a/>!! (:ext sys) {:op :shutdown :p p})
    (a/close! (:ext sys))
    (when (= ::late (deref p (+ cfg/STOP-BOUND-MS 1000) ::late))
      (a/>!! (:back sys) {:op :kill-all}))
    (let [drained (deref p 15000 :timeout)
          graph (flow/stop (:graph sys))]
      (a/put! (:stop-ch sys) true)
      (some-> ^Thread (:errors sys) (.join 5000))
      (a/close! (:events-in sys))
      ;; a store this root made for itself goes with it
      (when-let [p (:owned-store sys)]
        (d/release (:conn sys))
        (klmdb/close-env! p)
        (clojure.java.shell/sh "rm" "-rf" p))
      {:stopped true :drained drained :graph graph :agents-left 0 :ms (lib/r2 (lib/ms-since t0))})))



