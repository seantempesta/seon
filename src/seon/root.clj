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
    :reconciler   :io     main's commits through a sliding buffer of one (the hook a
                          reconciler and the dashboard plug into)
  and one proc per agent (`agent-proc`, added and removed with flow/update!), its stand-in:
  it owns root's end of the agent's socket and its process, and passes Flow's pause, resume and stop on to the
  agent's own loop (seon.step/run-loop), which runs the same step-fn contract.

  Ordering is dataflow: every proc starts and parks on its inputs. Shutdown flows too:
  the entrance closes, agents drain and exit, the writer's in-port closes, the writer
  drains and flushes last, the fork server is reaped, then flow/stop.

  Agents transact facts on their own branch; root reacts: the writer applies them, and
  only root's merge! advances main."
  (:require [datahike.api :as d] [datahike.query.execute] [seon.db :as pdb] [seon.lib :as lib]
            [seon.sock :as sock] [seon.wire :as wire] [seon.fork-server :as fs] [seon.agent-proc :as ap]
            [seon.writer :as writer] [seon.reconcile :as reconcile] [seon.program :as program] [seon.step :as step]
            [seon.config :as cfg] [clojure.core.async :as a] [clojure.core.async.flow :as flow] [clojure.test]
            [taoensso.trove :as trove]))


;; ====================================================================================
;; The api: the entrance
;; ====================================================================================

(defn- no-agent [kind why]
  (case kind
    :eval [{:id -1 :tag :err :err {:message why} :handle nil :printed why :ms 0.0 :alloc-bytes 0}]
    :tests {:pass 0 :fail 0 :error 1 :reports [{:type :error :message why}]}
    :merge-tests {:merged? false :error why}
    :clone {:ok false :error why}
    {:ok false :error why}))

(defn- deliver-rid
  "Deliver r to the caller waiting on rid; answers st."
  [st rid r]
  (if-let [{:keys [p]} (get-in st [:pending rid])]
    (do (deliver p r) (-> st (update :pending dissoc rid) (update :writes disj rid)))
    st))

(defn- view! [st f & args] (apply update st :view f args))

(defn- set-agent
  "Merge m into agent id's view entry. A :killed agent stays :killed."
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
  [st id m]
  (if (get-in st [:agents id])
    (update st ::to-agents (fnil conj []) [id m])
    st))

(def ^:private agent-launcher
  "One launcher for every agent's proc (its step is the Var #'agent-proc)."
  (flow/process #'ap/agent-proc))

(defn- start-agent
  "Register a new agent: its socket (root keeps one end; the other goes to whoever forks
  it), its proc definition (added to the graph by update! at the end of the step), and
  the reader told where its frames go. Answers [st id agent-end-fd]."
  [st {:keys [branch task cloned-from]}]
  (let [n (inc (:next-id st))
        id (keyword (str "a" n))
        branch (or branch (keyword (str "agent-" n)))
        [r a] (sock/socketpair)
        from (a/chan cfg/AGENT-BUF)]
    ;; the reader reads this socket from now on, into this agent's :from
    (a/>!! (:reg st) [r from])
    (sock/poke! (:wake st))
    [(-> st (assoc :next-id n ::update true)
         (assoc-in [:agents id] {:branch branch})
         (assoc-in [:agent-procs id] {:proc agent-launcher
                                      :args {:id id :fd r :branch branch :from from
                                             :to-writer (:to-writer st) :back (:back st) :events (:events st)}
                                      :chan-opts {:in {:buf-or-n cfg/AGENT-BUF}}})
         (assoc-in [:view :agents id] (cond-> {:branch branch :status :spawning :task task :pid nil
                                               :forked-ms nil :mem-mb nil :evals [] :tests nil}
                                        cloned-from (assoc :cloned-from cloned-from))))
     id a]))

(defn- end-agent
  "The agent's process is gone and its stand-in saw its last frame: its proc leaves the
  graph (update! at the end of the step) and its branch's connection is released."
  [st o {:keys [agent rid pid inflight]}]
  (let [{:keys [branch]} (get-in st [:agents agent])
        killed? (= :killed (get-in st [:view :agents agent :status]))
        why (if rid "agent stopped" "agent died")]
    (step/out! o :to-writer {:op :release :branch branch})
    (step/out! o :events (step/event {:type (if killed? :killed :dead) :agent agent}))
    (let [st (reduce (fn [st r] (deliver-rid st r (no-agent (get-in st [:pending r :kind]) why))) st inflight)
          st (cond-> st rid (deliver-rid rid {:stopped true :agent agent}))]
      (-> st (update :agents dissoc agent) (update :agent-procs dissoc agent) (assoc ::update true)
          (set-agent agent {:status (if killed? :killed :dead) :ended-ms (System/currentTimeMillis) :running nil})))))

(defn- rid! [st] (let [r (inc (:rid st))] [(assoc st :rid r) r]))

(defn- pend [st rid p kind agent] (if p (assoc-in st [:pending rid] {:p p :kind kind :agent agent}) st))

(defn- ext-op [st o m]
  (let [{:keys [op p id]} m]
    (case op
      :spawn (let [opts (:opts m)
                   holder (and (:branch opts)
                               (some (fn [[aid a]] (when (and (= (:branch opts) (:branch a)) (not (:ended-ms a))
                                                              (not (#{:dead :killed} (:status a))))
                                                     aid))
                                     (get-in st [:view :agents])))]
               (cond
                 holder (do (deliver p {:error (str "branch " (:branch opts) " is held by live agent " holder)
                                        :branch (:branch opts) :agent holder}) st)
                 (>= (count (:agents st)) cfg/MAX-AGENTS) (do (deliver p {:error "too many live agents" :max cfg/MAX-AGENTS}) st)
                 :else (let [[st aid fd] (start-agent st opts)
                             {:keys [branch]} (get-in st [:agents aid])]
                         (step/out! o :to-writer {:op :spawn :agent aid :branch branch :ns (lib/now-ns) :fd fd :dest {:spawn aid}})
                         (deliver p {:id aid})
                         st)))
      :clone (let [parent (:parent m)]
               (if (or (nil? (get-in st [:agents parent])) (>= (count (:agents st)) cfg/MAX-AGENTS))
                 (do (deliver p {:ok false :error (str "no live agent " parent)}) st)
                 (let [[st aid fd] (start-agent st (merge {:task (get-in st [:view :agents parent :task])} (:opts m) {:cloned-from parent}))
                       {:keys [branch]} (get-in st [:agents aid])
                       [st rid] (rid! st)]
                   ;; the clone's socket goes to its parent with the request
                   (to-agent (pend st rid p :clone parent) parent
                             {:op :req :rid rid :kind :clone :new aid :branch branch :pass-fd fd
                              :msg {:op :clone :agent aid :branch branch}}))))
      (:eval :tests :merge-tests :stats :ping)
      (if (nil? (get-in st [:agents id]))
        (do (deliver p (no-agent op (str "no live agent " id))) st)
        (let [[st rid] (rid! st)
              msg (case op
                    :eval (cond-> {:op :eval :forms (:forms m)} (:timeout-ms m) (assoc :timeout-ms (:timeout-ms m)))
                    (:tests :merge-tests) {:op :tests :timeout-ms (:timeout-ms m)}
                    :stats {:op :stats}
                    :ping {:op :ping})]
          (to-agent (pend st rid p op id) id {:op :req :rid rid :kind op :msg msg :merge-branch (:merge-branch m)})))
      ;; requests for the writer: answered by rid
      :write (let [[st rid] (rid! st)
                   req (:req m)
                   dest (case (:op req) :merge-main {:merge-main (:agent req) :rid rid} :merge {:merged (:agent req) :rid rid} {:rid rid})]
               (step/out! o :to-writer (assoc (dissoc req :agent) :dest dest))
               (-> (pend st rid p (:op req) nil) (update :writes (fnil conj #{}) rid)))
      :stop (if (get-in st [:agents id])
              (let [[st rid] (rid! st)]
                (to-agent (pend st rid p :stop id) id {:op :drain :rid rid}))
              (do (deliver p {:stopped true :agent id :already true}) st))
      :kill (if (get-in st [:agents id])
              (-> st (set-agent id {:status :killed}) (to-agent id {:op :kill}))
              st)
      :message (let [b (wire/encode (wire/seon->wire {:op :message :from (:from m :root) :body (:body m) :ns (sock/now-ns)}))]
                 ;; encoded once, the same bytes to every socket
                 (reduce #(to-agent %1 %2 {:op :bytes :bytes b}) st (:to m)))
      :fs (let [[st rid] (rid! st)]
            (step/out! o :to-fs (assoc (:req m) :rid rid))
            (pend st rid p :fs nil))
      :state (do (deliver p (:view st)) st)
      :shutdown (assoc st :shutdown-p p)
      st)))

(defn- back-op
  "A message from a stand-in, a fork-server or writer event, or the error reader."
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
                                                                          (= :branched (:op dest)) {:op :branched :k (:k dest) :ok (:ok r) :error (:error r)}
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
      :message (let [b (wire/encode (wire/seon->wire {:op :message :from (:from m) :body (:body m) :ns (sock/now-ns)}))]
                 (reduce #(to-agent %1 %2 {:op :bytes :bytes b}) st (:to m)))
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
      :error (view! st update :root-errors (fnil #(vec (take-last 20 (conj % %2))) []) (:message m))
      :kill-all (reduce (fn [st aid] (-> st (set-agent aid {:status :killed}) (to-agent aid {:op :kill}))) st (keys (:agents st)))
      st)))

(defn- api-one [st o in m]
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
  {:malli/schema step/step-schema}
  ([] {:params {:graph "a promise of this graph" :root-procs "the root procs' definitions" :root-conns "their conns"}
       :ins {:from-writer "writer answers and stats" :from-fs "fork-server events"} :outs {:to-fs "fork-server requests"}
       :workload :io
       :ping-map-fn (fn [st] {:agents (count (:agents st)) :pending (count (:pending st)) :closing (boolean (:closing st))})})
  ([args] (assoc args :agents {} :agent-procs {} :pending {} :rid 0 :next-id 0
                 ::flow/in-ports {:ext (:ext args) :back (:back args)}
                 ::flow/out-ports {:to-writer (:to-writer args) :events (:events args)}))
  ([st tr]
   ;; the stop transition: nothing accepted is left unanswered
   (when (= tr ::flow/stop)
     (doseq [[_ {:keys [p kind]}] (:pending st)] (deliver p (no-agent kind "root stopped"))))
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
              (do (flow/update! @(:graph st) {:procs (merge @(:root-procs st) (:agent-procs st)) :conns (:root-conns st)})
                  (dissoc st ::update))
              st)]
     ;; the writer's in-port closes once the entrance is closed and every agent has ended
     (when (and (:closing st) (empty? (:agents st)) (not (:writer-closed st)))
       (a/close! (:to-writer st)))
     (doseq [[id msg] (::to-agents st)] (step/out! o [id :in] msg))
     [(cond-> (dissoc st ::to-agents)
        (and (:closing st) (empty? (:agents st))) (assoc :writer-closed true)
        ;; at the bound of writer requests in flight: read answers only, not the entrance
        true (assoc ::flow/input-filter (if (>= (count (:writes st)) cfg/API-WRITES) #(not= :ext %) identity)))
      @o])))

;; ====================================================================================
;; The reader: the one reader of every agent's socket
;; ====================================================================================

(defn- start-reader!
  "ONE thread reads every agent's socket (an edge reader: a blocking kevent). Each frame
  goes to that agent's :from channel. When that channel is full (the stand-in paused or
  slow) the frame becomes ONE pending put and the socket leaves the kqueue, so its buffer
  fills and the agent's own send waits; the put's completion brings it back (through
  :reg and a poke). After a socket's last frame comes {:op :dead}: EOF, every holder of
  the agent's end is gone. Which channel each socket feeds is this loop's own map, told
  by the api on :reg. Ends when stop-ch has a value."
  [{:keys [reg wake wake-r stop-ch]}]
  (let [kq (sock/kqueue)
        evs (sock/kq-buffer 256)]
    (sock/kq-watch! kq wake-r true)
    (doto (Thread.
            (fn []
              (letfn [(pump [peers fd]
                        ;; deliver fd's frames until none is whole, the channel is full, or EOF
                        (let [{:keys [conn rd ch]} (get peers fd)]
                          (loop []
                            (let [v (wire/poll-msg! rd)]
                              (cond
                                (= v :none) peers
                                (= v :eof) (do (sock/kq-watch! kq fd false) (sock/release-conn! conn)
                                               ;; the socket's last message: never dropped
                                               (a/put! ch {:msg {:op :dead}})
                                               (dissoc peers fd))
                                (a/offer! ch {:msg v}) (recur)
                                :else (do (sock/kq-watch! kq fd false)
                                          (a/put! ch {:msg v} (fn [_] (a/put! reg [:resume fd]) (sock/poke! wake)))
                                          (assoc-in peers [fd :held] true)))))))]
                (loop [peers {}]
                  (let [peers (loop [peers peers]
                                (if-let [[x y] (a/poll! reg)]
                                  (if (= x :resume)
                                    (recur (if (get peers y)
                                             ;; held until this put completed: the socket's next frames, then the kqueue again
                                             (let [p (pump (update peers y dissoc :held) y)]
                                               (when (and (get p y) (not (get-in p [y :held]))) (sock/kq-watch! kq y true))
                                               p)
                                             peers))
                                    (do (sock/kq-watch! kq x true) (recur (assoc peers x (let [c (sock/conn x)] {:conn c :rd (wire/reader c) :ch y})))))
                                  peers))
                        fds (sock/kq-wait kq evs 256 50)
                        peers (reduce (fn [peers fd]
                                        (cond (= fd wake-r) (do (sock/discard! wake-r) peers)
                                              (and (get peers fd) (not (get-in peers [fd :held]))) (pump peers fd)
                                              :else peers))
                                      peers fds)]
                    ;; stop!: the wait's 50 ms bound (or a poke) is this thread's exit latency
                    (when-not (a/poll! stop-ch) (recur peers)))))))
      (.setDaemon true) (.start))))

;; ====================================================================================
;; Boot
;; ====================================================================================

(defn- warm!
  "Run, before the fork server is forked, every code path agents and the server take,
  so forked children inherit it compiled."
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
      (sock/frame {:op :reply :items [{:id 1 :printed "x" :ms 0.1}]})
      (wire/encode (wire/seon->wire {:op :reply :items [{:id 1 :printed "x" :ms 0.1}]})))
    (let [nsx (create-ns 'seon.warm)]
      (binding [*ns* nsx]
        (refer-clojure)
        (refer 'clojure.test :only '[deftest is testing])
        (eval '(defn warm-f [x] (* 2 x)))
        (eval '(deftest warm-t (is (= 4 (warm-f 2)))))
        (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/run-tests nsx)))
      (remove-ns 'seon.warm))))

(defn start!
  "Boot the control process, the fork server and the root graph. opts:
  {:datoms n :path dir (durable) :backend :lmdb|:file :log? true
   :on-main f (called with every main commit seen, in the :reconciler proc)}."
  [{:keys [datoms log? path backend on-main] :or {datoms 2000}}]
  (lib/ignore-sigpipe!)
  (when-not log? (trove/set-log-fn! nil))
  (let [t0 (lib/now-ns)
        path (or path (System/getenv "SEON_STORE")
                 (some-> (System/getenv "SEON_STORE_ROOT") (str "/" (random-uuid))))
        backend (or backend (some-> (System/getenv "SEON_BACKEND") keyword) :lmdb)
        {:keys [cfg conn recovered?]} (if path (pdb/open! backend path datoms)
                                          (let [[cfg conn] (pdb/create! datoms)] {:cfg cfg :conn conn}))
        open-ms (lib/ms-since t0)
        t-pre (lib/now-ns)
        _ (when path (pdb/preload! @conn))
        preload-ms (lib/ms-since t-pre)
        recovered (if recovered? (vec (sort (disj (d/branches conn) :db))) [])
        load-ms (lib/ms-since t0)
        t1 (lib/now-ns)
        program (program/load-all! @conn)
        program-ms (lib/ms-since t1)
        _ (warm! conn)
        _ (lib/raise-open-files! (+ 256 (* 2 cfg/MAX-AGENTS)))
        _ (lib/static!)
        db @conn
        ;; physics: the fork server is forked before any thread exists
        {:keys [pid fd]} (fs/fork-server db)
        [wake-r wake] (sock/socketpair)
        events-in (a/chan (a/sliding-buffer 8192))
        chans {:ext (a/chan 1024) :back (a/chan 4096) :to-writer (a/chan cfg/WRITER-BUF)
               :server-ev (a/chan 4096) :reg (a/chan (* 4 cfg/MAX-AGENTS)) :stop-ch (a/promise-chan)}
        view {:agents {} :writer {:tx-count 0} :stats {}
              :server {:agents-forked 0 :pid pid :status :up :restarts 0}
              :store (if path
                       {:backend backend :path path :recovered? (boolean recovered?)
                        :recovered-branches recovered :open-ms (lib/r2 open-ms) :preload-ms (lib/r2 preload-ms)}
                       {:backend :memory})
              :boot {:load-ms (lib/r2 load-ms) :program (assoc program :ms (lib/r2 program-ms))}}
        graph-p (promise)
        root-conns [[[:writer :to-fork-server] [:fork-server :cmd]]
                    [[:writer :to-api] [:api :from-writer]]
                    [[:reconciler :to-api] [:api :from-writer]]
                    [[:writer :main] [:reconciler :main]]
                    [[:fork-server :to-api] [:api :from-fs]]
                    [[:api :to-fs] [:fork-server :ext]]]
        root-procs-p (promise)
        root-procs
            {:api {:proc (flow/process #'api-proc)
                   ;; room for every answer the writer can owe while the api is not reading
                   :chan-opts {:from-writer {:buf-or-n (+ cfg/WRITER-BUF cfg/WRITER-BATCH cfg/API-WRITES)}}
                           :args {:wake wake :view view :ext (:ext chans) :back (:back chans) :graph graph-p
                                  :root-procs root-procs-p :root-conns root-conns
                                  :reg (:reg chans) :to-writer (:to-writer chans) :events events-in}}
                     :writer {:proc (flow/process #'writer/writer-proc)
                              :args {:conn conn :cfg cfg :recovered recovered :boot-main db :durable? (= :lmdb (get-in cfg [:store :backend]))
                                     :to-writer (:to-writer chans) :events events-in}}
                     :fork-server {:proc (flow/process #'fs/fork-server-proc)
                                   :args {:conn conn :pid pid :fd fd :server-ev (:server-ev chans)}}
                     :reconciler {:proc (flow/process #'reconcile/reconciler-proc)
                                  ;; a reconciler reads main's latest db: a sliding buffer of one
                                  :chan-opts {:main {:buf-or-n (a/sliding-buffer 1)}}
                                  :args {:on-main on-main}}}
        _ (deliver root-procs-p root-procs)
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
                                                                  :message (or (some-> (::flow/ex e) ex-message) (str e))})
                                            (recur))))))))
                 (.setDaemon true) (.start))
        sys (merge chans {:cfg cfg :conn conn :graph g :wake wake :wake-r wake-r :t0 t0
                          :events-in events-in :events-mult (a/mult events-in) :errors errors})
        sys (assoc sys :reader (start-reader! sys))]
    (flow/resume g)
    sys))

;; ====================================================================================
;; The API: callers outside the graph. Each call puts one request on :ext.
;; ====================================================================================

(defn- call!
  "Put m (with a fresh reply promise) on the entrance; a promise. After stop! the
  entrance is closed and the promise answers {:ok false :error \"root stopped\"}."
  [sys m]
  (let [p (promise)]
    (when-not (a/>!! (:ext sys) (assoc m :p p))
      (deliver p (no-agent (:op m) "root stopped")))
    p))

(defn state
  "The current state value (plain EDN), from the api's view. With {:datoms true}, main's
  datom count too, taken here in the caller from main's db value (O(datoms): a poll
  should not ask for it)."
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

(defn stats "The raw counters and windows." [sys] (:stats (deref (call! sys {:op :state}) 5000 {})))

(defn events
  "A fresh tap of every event {:type :spawned|:ready|:reply|:tx|:merged|:tests|:killed|:dead ...}."
  [sys]
  (let [c (a/chan (a/sliding-buffer 1024))] (a/tap (:events-mult sys) c) c))

(defn spawn!
  "Fork an agent from the fork server onto a new branch of main; answers its id at once
  (status :spawning until its hello). opts {:branch kw :task string}; an existing :branch
  is resumed."
  [sys opts]
  (let [r @(call! sys {:op :spawn :opts opts})]
    (if (:id r) (:id r) (throw (ex-info (str (:error r)) (dissoc r :error))))))

(defn clone!
  "Fork a live agent into a new agent on a branch of its branch. A promise of
  {:ok true :id new-id} or {:ok false :error}."
  ([sys agent-id] (clone! sys agent-id {}))
  ([sys agent-id opts] (call! sys {:op :clone :parent agent-id :opts opts})))

(defn- agent-entry [sys id] (get-in (deref (call! sys {:op :state}) 5000 {}) [:agents id]))

(defn submit!
  "Send forms (a string) to the agent; a promise of the reply vector, one map per form.
  opts {:timeout-ms n}: each form is bounded at n ms."
  ([sys agent-id forms-string] (submit! sys agent-id forms-string nil))
  ([sys agent-id forms-string {:keys [timeout-ms]}]
   (call! sys (cond-> {:op :eval :id agent-id :forms forms-string} timeout-ms (assoc :timeout-ms timeout-ms)))))

(defn run-tests!
  "The agent forks itself and runs its deftests in the child; a promise of
  {:pass :fail :error :reports [...]}."
  ([sys agent-id] (run-tests! sys agent-id 30000))
  ([sys agent-id timeout-ms] (call! sys {:op :tests :id agent-id :timeout-ms timeout-ms})))

(defn agent-stats! "A promise of the agent's numbers now." [sys agent-id] (call! sys {:op :stats :id agent-id}))

(defn- write! [sys req] (call! sys {:op :write :req req}))

(defn merge!
  "Root merges the agent's branch into main; a promise of {:merged? :tx ...}. Unless
  opts has :gate? false, the agent's tests run first in a fork of it."
  ([sys agent-id] (merge! sys agent-id {}))
  ([sys agent-id {:keys [gate?] :or {gate? true}}]
   (let [ag (agent-entry sys agent-id)]
     (cond
       (not (:branch ag)) (doto (promise) (deliver {:merged? false :error (str "no agent " agent-id)}))
       (not gate?) (write! sys {:op :merge :branch (:branch ag) :agent agent-id})
       (#{:dead :killed} (:status ag)) (doto (promise) (deliver {:merged? false :error "agent not running, so its tests cannot run; merge with {:gate? false}"}))
       :else (call! sys {:op :merge-tests :id agent-id :merge-branch (:branch ag) :timeout-ms 30000})))))

(defn merge-main!
  "git merge main, on the agent's branch. A promise of {:updated? :t :datoms} or
  {:updated? false :conflicts [...]}."
  [sys agent-id]
  (let [ag (agent-entry sys agent-id)]
    (if (or (nil? ag) (#{:dead :killed} (:status ag)))
      (doto (promise) (deliver {:updated? false :error (str "no live agent " agent-id)}))
      (write! sys {:op :merge-main :branch (:branch ag) :agent agent-id}))))

(defn branch-diff "The agent's branch versus main. A promise." [sys agent-id]
  (if-let [b (:branch (agent-entry sys agent-id))]
    (write! sys {:op :branch-diff :branch b})
    (doto (promise) (deliver {:ok false :error (str "no agent " agent-id)}))))

(defn branch-info "The writer's view of an agent's branch: {:datoms :t :log}. A promise." [sys agent-id]
  (write! sys {:op :branch-info :branch (:branch (agent-entry sys agent-id))}))

(defn transact-main! "Root's own write to main. A promise." [sys tx-data] (write! sys {:op :tx :branch :db :tx-data tx-data}))

(defn send-message!
  "Deliver body to every agent in tos (encoded once); the agent takes it with (messages)."
  [sys tos body] (call! sys {:op :message :to (vec tos) :body body}) nil)

(defn interrupt!
  "Interrupt the agent's evaluation in progress: SIGUSR1, run by Chez at the agent's next
  safe point, which also ends a blocking host call (preemption is physics: no message
  reaches a running native loop). nil."
  [sys agent-id]
  (some-> (:pid (agent-entry sys agent-id)) (lib/c-kill lib/SIGUSR1))
  nil)

(defn kill! "SIGKILL the agent's process (through its stand-in). nil." [sys agent-id] (call! sys {:op :kill :id agent-id}) nil)

(defn pause-agent!
  "Flow's pause of the agent's proc (flow/pause-proc on the root graph), passed on to the
  agent's own loop: its form in flight finishes, then it holds requests. nil."
  [sys agent-id] (flow/pause-proc (:graph sys) agent-id) nil)

(defn resume-agent! "Flow's resume of the agent's proc. nil." [sys agent-id] (flow/resume-proc (:graph sys) agent-id) nil)

(defn agent-health
  "Flow's ping of the agent's proc: {:status :running|:paused :count :state}, or :unknown
  when it does not answer within ms."
  ([sys agent-id] (agent-health sys agent-id 1000))
  ([sys agent-id ms]
   (if-let [m (flow/ping-proc (:graph sys) agent-id :timeout-ms ms)]
     {:status (::flow/status m) :count (::flow/count m) :state (::flow/state m)}
     :unknown)))

(defn agent-ping
  "A ping answered by the agent's own process loop (it answers after its form in flight):
  {:status :count :held :state}, or :unknown within ms."
  ([sys agent-id] (agent-ping sys agent-id 1000))
  ([sys agent-id ms] (let [r (deref (call! sys {:op :ping :id agent-id}) ms :unknown)] (if (and (map? r) (:status r)) r :unknown))))

(defn stop-agent!
  "Flow's stop for one agent: it exits after the requests before the stop; root awaits
  its death, SIGKILLs past SEON_STOP_BOUND_MS. Answers {:stopped true ...} or :timeout."
  [sys agent-id]
  (let [p (call! sys {:op :stop :id agent-id})]
    (if (= ::late (deref p cfg/STOP-BOUND-MS ::late))
      (do (kill! sys agent-id) (deref p 5000 :timeout))
      @p)))

(defn freeze! "SIGSTOP the agent's process. nil." [sys agent-id] (some-> (:pid (agent-entry sys agent-id)) (lib/c-kill 17)) nil)
(defn thaw! "SIGCONT the agent's process. nil." [sys agent-id] (some-> (:pid (agent-entry sys agent-id)) (lib/c-kill 19)) nil)

(defn fs-call! "Ask the fork server for {:op :stats} or {:op :static}." [sys msg] (deref (call! sys {:op :fs :req msg}) 30000 :timeout))

(defn health
  "Flow's own observation of every root proc (its ping-map-fn), or :unknown within ms."
  ([sys] (health sys 1000))
  ([sys ms]
   (into {} (for [pid [:api :writer :fork-server :reconciler]]
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
      (sock/poke! (:wake sys))
      (some-> ^Thread (:reader sys) (.join 5000))
      (some-> ^Thread (:errors sys) (.join 5000))
      (a/close! (:events-in sys))
      (sock/close! (:wake sys)) (sock/close! (:wake-r sys))
      {:stopped true :drained drained :graph graph :agents-left 0 :ms (lib/r2 (lib/ms-since t0))})))



