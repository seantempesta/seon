(ns seon.agent-proc
  "An agent's proc in root: one proc of the root graph per agent, added and removed by
  the api with flow/update!. It owns root's end of the agent's socket, a jolt.async.remote
  end (session RC1): the agent's frames arrive as values on the end's :in, which IS this
  proc's :from in-port, and what it sends the agent is put on the end's :out. It owns the
  agent's process and passes Flow's pause, resume and stop on to the agent's own loop.
  From proto/flow f2-final proto.control (session F2)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.contract :as contract :refer [defn defn-]] [seon.lib :as lib] [seon.sock :as sock] [seon.step :as step]
            [jolt.async.remote :as remote] [clojure.core.async :as a] [clojure.core.async.flow :as flow]))

(defn- to-proc!
  "Send m to the agent's process: onto the end's :out (its writer pump frames it). While a
  descriptor is in its handshake (:holding), m waits in :held."
  {:malli/schema [:=> [:cat :map :seon.wire/seon-msg] :map]}
  [st m]
  (cond (:dead? st) st
        (:holding st) (update st :held (fnil conj []) m)
        :else (do (a/>!! (:out (:end st)) m) st)))

(defn- send-fd!
  "The descriptor fd to the agent, in a frame of its own ({:op :fd :k k}, SCM_RIGHTS). The
  agent asked for it ({:op :fd-ready}) after taking the request before it, and nothing was
  put on :out since (held), so the writer pump is idle: this frame cannot interleave with
  another. Then what was held goes out."
  {:malli/schema [:=> [:cat :map :int :seon.lib/fd] :map]}
  [st k fd]
  (sock/send! (:fd (:end st)) {:op :fd :k k} fd)
  (reduce to-proc! (dissoc st :holding :held) (:held st)))

(defn- hang-up!
  "Close root's end: the agent (if any) reads the end frame; this proc's :from closes."
  {:malli/schema [:=> [:cat :map] :any]}
  [st] (remote/close! (:end st)))

(defn- status {:malli/schema [:=> [:cat :map :map] :map]}
  [st m] {:op :status :agent (:id st) :set m})

(defn- busy {:malli/schema [:=> [:cat :map] [:enum :busy :idle]]}
  [st] (if (seq (:inflight st)) :busy :idle))

(def stop-bound-ms
  "How long an agent may take to exit after :stop before it is SIGKILLed. A guard, not a
  config key (GUARDS finding 3: a clock standing in for the exit event); it goes with
  seon.root/shutdown-and-death-are-dataflow."
  2000)

(defn- await-dead
  "Wait (bounded) on the end's :in for its close (the process is gone): true, or false
  past ms."
  {:malli/schema [:=> [:cat :seon.host/chan :int] :boolean]}
  [from ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [left (- deadline (System/currentTimeMillis))]
        (if (<= left 0)
          false
          (let [t (a/timeout left)
                [m c] (a/alts!! [from t])]
            (cond (= c t) false
                  (nil? m) true
                  :else (recur))))))))

(declare agent-frame)

(defn- agent-in
  "A message from the api (or the writer's answer, routed by the api)."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map :seon.step/outputs]]}
  [st m]
  (case (:op m)
    ;; an answer to this agent's ask on the api's reply route (the test service's, seon.prove; its
    ;; the render proc's)
    nil [(to-proc! st {:op :reply :k (:tag m) :result (:r m)}) nil]
    :req (let [{:keys [rid msg kind]} m]
           (let [st (cond-> (to-proc! st (assoc msg (if (= kind :clone) :k :id) rid))
                      ;; a clone's socket follows in its own frame once the agent asks
                      (= kind :clone) (assoc :holding rid)
                      true (update :inflight assoc rid (cond-> (select-keys m [:kind :branch :new :pass-fd])
                                                         ;; an eval's text, for its :started event
                                                         (string? (:forms msg)) (assoc :form (:forms msg))))
                      ;; what root last asked of it, for a stall or a death (seon.agent/last-op)
                      true (assoc :last-op (cond-> {:kind kind :since-ms (System/currentTimeMillis)}
                                             (string? (:forms msg)) (assoc :form (:forms msg)))))]
             [st {:to-api [(status st (cond-> {:status :busy}
                                        (= kind :eval) (assoc :running {:form (:forms msg)
                                                                        :since-ms (System/currentTimeMillis)})))]}]))
    :write [(cond-> (to-proc! st (:msg m)) (= :fx-reply (:op (:msg m))) (update :fx-out (fnil dec 1))) nil]
    ;; root's stall check (seon.root's timer): a request in flight, no frame from the agent
    ;; for longer than the stall bound and no effect of its outstanding is a stall, observed once
    ;; per quiet spell
    :stall? (let [quiet (- (:now-ms m) (:last-frame-ms st (:now-ms m)))]
              (if (and (seq (:inflight st)) (not (:stall-seen st)) (> quiet (:stall-ms m)) (zero? (:fx-out st 0)) (not (:dead? st)))
                [(assoc st :stall-seen true)
                 {:to-api [{:op :stalled :agent (:id st)
                            :error (contract/error :seon.agent/stalled #'seon.agent-proc/agent-in
                                                   (str "agent " (name (:id st)) " sent nothing for " quiet " ms with a request in flight")
                                                   {:agent (:id st) :quiet-ms quiet :last-op (:last-op st)})}]}]
                [st nil]))
    :forked (do (when (and (:kill? st) (not (:dead? st))) (lib/c-kill (:pid m) 9))
                [(assoc st :pid (:pid m)) nil])
    ;; a process that never existed reads as dead: hang up root's end, the reader sees EOF
    :failed (do (hang-up! st) [st {:to-api [(status st {:error (:error m)})]}])
    :drain (let [st (if (:pid st) (to-proc! st {:op :stop}) (do (hang-up! st) st))]
             [(assoc st :draining (:rid m) :stop-sent true) nil])
    :kill (do (if (:pid st) (lib/c-kill (:pid st) 9) (hang-up! st))
              [(assoc st :kill? true) nil])
    [st nil]))

(defn- answer-rid {:malli/schema [:=> [:cat :map :seon.host/volatile :int :any] :map]}
  [st o rid r]
  (step/out! o :to-api {:dest {:rid rid} :r r})
  (update st :inflight dissoc rid))

(defn- agent-frame
  "A frame from the agent's process."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o m]
  (let [id (:id st)]
    (case (:op m)
      ;; a test of its run started (seon.agent/run-tests-forked): what it waits on now, which a
      ;; stall names
      :testing (assoc st :last-op {:kind :tests :form (:test m)})
      :hello (do (when (:cloned-from m)
                   ;; a clone: its pid arrives here (the fork server never saw it)
                   (when (:kill? st) (lib/c-kill (:pid m) 9))
                   (step/out! o :events (step/event {:type :spawned :agent id :pid (:pid m) :cloned-from (:cloned-from m)})))
                 (step/out! o :to-api (status st (cond-> {:forked-ms (lib/r2 (:start-ms m)) :mem-mb (:mem-mb m) :t (:t m) :ready true :phases (:phases m)}
                                              (:cloned-from m) (assoc :pid (:pid m))
                                              (seq (:load-errors m)) (assoc :load-errors (:load-errors m)))))
                 ;; root's interrupt cannot reach it (its SIGUSR1 handler never ran: a process not
                 ;; forked from a main thread): a recorded problem, to whoever can act on it
                 (when (false? (:interrupt-ok m))
                   (step/out! o :to-api {:op :problem :class :agent :agent id
                                         :error (contract/error :seon.agent/interrupt-unreachable #'seon.agent-proc/agent-frame
                                                                "root's interrupt (SIGUSR1) does not reach this agent: its handler did not run at boot, so a runaway eval can only be killed" {})}))
                 ;; its branch's program did not load where it resumed: each failure a recorded problem
                 (doseq [{:keys [ns message]} (:load-errors m)]
                   (step/out! o :to-api {:op :problem :class :agent :agent id
                                         :error (contract/error :seon.agent/load-failed #'seon.agent-proc/agent-frame
                                                                (str "its branch's program did not load in " ns ": " message) {:ns ns})}))
                 ;; its socket to root cannot raise SIGIO: it ends with root only when it next reads
                 (when-let [e (:unwatched m)] (step/out! o :to-api {:op :problem :class :agent :agent id :error e}))
                 (when-let [x (:misaligned m)]
                   (step/out! o :to-api {:op :problem :class :agent :agent id
                                         :error (contract/error :seon.agent/fork-misaligned #'seon.agent-proc/agent-frame
                                                                (str "its branch or image is not at the commit it was branched at: " (sedn/encode x)) x)}))
                 (step/out! o :events (step/event {:type :ready :agent id :start-ms (:start-ms m) :phases (:phases m)}))
                 (cond-> st (:cloned-from m) (assoc :pid (:pid m))))
      ;; its eval's record could not be written on its branch (its entity ids are spent):
      ;; the typed error is recorded on main as its problem
      :fd-ready (send-fd! st (:k m) (get-in st [:inflight (:k m) :pass-fd]))
      :branch-me (let [branch (get-in st [:inflight (:k m) :branch])]
                   (step/out! o :to-writer {:op :clone-branch :from (:branch st) :branch branch
                                       :dest {:agent id :op :branched :k (:k m)}})
                   st)
      (:cloned :clone-failed)
      (let [{:keys [new pass-fd]} (get-in st [:inflight (:k m)])
            ;; the clone's socket end: the parent has it (or failed), root's copy closes now
            _ (some-> pass-fd sock/close!)
            st (answer-rid st o (:k m) (if (= :cloned (:op m)) {:ok true :id new} (:error m)))]
        (when (= :clone-failed (:op m)) (step/out! o :to-api {:op :clone-failed :agent new :error (:error m)}))
        (step/out! o :to-api (status st {:status (busy st)}))
        st)
      :synced (do (step/out! o :to-api (status st {:t (:t m) :load-errors (:load-errors m)})) st)
      ;; the back-pressure check's flood: counted, in order
      :noise (let [ok (= (:i m) (:noise st))
                   ordered (and (:noise-ordered st true) ok)
                   bad (or (:noise-bad st) (when-not ok [(:i m) (:noise st)]))]
               (step/out! o :to-api {:op :stat :k :noise :v (inc (:noise st)) :ordered ordered :bad bad})
               (assoc st :noise (inc (:noise st)) :noise-ordered ordered :noise-bad bad))
      :received (do (step/out! o :to-api {:op :stat :k :msg-us :v (/ (- (:recv-ns m) (:sent-ns m)) 1000.0)}) st)
      :send (do (step/out! o :to-api {:op :message :from id :to (:to m) :body (:body m)}) st)
      ;; the agent began request rid's eval, or forked rid's test child: root's events say so
      :started (do (step/out! o :events (step/event (cond-> {:type :started :agent id :rid (:id m)}
                                                         (get-in st [:inflight (:id m) :form]) (assoc :form (get-in st [:inflight (:id m) :form])))))
                   st)
      :tests-started (do (step/out! o :events (step/event {:type :tests-started :agent id :rid (:id m) :pid (:pid m)})) st)
      :reply (let [st (answer-rid st o (:id m) (:response m))]
               (step/out! o :to-api (status st {:evals-add [(:response m)] :t (:t m) :status (busy st) :running nil}))
               (step/out! o :events (step/event {:type :reply :agent id :response (:response m)}))
               st)
      :tests (let [result (:result m)
                   ;; a run the agent could not fork is its refused answer, answered as it is
                   ran? (not (contract/error? result))
                   st (answer-rid st o (:id m) result)]
               (step/out! o :to-api (status st (cond-> {:mem-mb (:mem-mb m) :status (busy st)} ran? (assoc :tests result) (not ran?) (assoc :error result))))
               (when ran? (step/out! o :events (step/event {:type :tests :agent id :ms (:ms result) :pass (:pass result) :fail (:fail result) :error (:error result)})))
               st)
      :stats (let [st (answer-rid st o (:id m) (:result m))]
               (step/out! o :to-api (status st {:mem-mb (:mem-mb (:result m)) :status (busy st)}))
               st)
      ;; what a test run of the agent's ran (seon.agent/run-tests-forked: seon.proofs/results-tx),
      ;; recorded on main by root's writer: a refused request is this agent's problem, as its other facts' are
      :record (do (step/out! o :to-writer {:op :tx :branch :db :tx-data (:tx-data m) :dest {:agent-facts [id]}}) st)
      ;; the agent committed its branch (it owns the branch's writes): the writer's next flush
      ;; covers it
      :committed (do (step/out! o :to-writer {:op :agent-commit :branch (:branch st) :t (:t m)})
                     (when (:mem-mb m) (step/out! o :to-api (status st {:mem-mb (:mem-mb m)})))
                     ;; its eval errors since its last commit: root counts them (seon.error)
                     (when (:errors m) (step/out! o :to-api {:op :errors :agent id :entries (:errors m)}))
                     (step/out! o :events (step/event {:type :commit :agent id :branch (:branch st) :t (:t m) :txs (:txs m)}))
                     st)
      ;; an effect (my.fx): to its kind's owner proc; the answer comes back like the
      ;; writer's, {:dest {:agent :op :fx-reply :k}} through the api
      :fx (let [owner (keyword "fx" (name (:kind m)))
                st (update st :fx-out (fnil inc 0))]
            (if (contains? (:fx-kinds st) owner)
              (do (step/out! o [owner :in] (cond-> {:req (:req m) :agent id :prio (:prio m 0)
                                                :dest {:agent id :op :fx-reply :k (:k m)}}
                                         (:budget m) (assoc :budget (:budget m) :budget-key (or (:budget-key m) id))))
                  st)
              (to-proc! (update st :fx-out dec)
                        (merge {:op :fx-reply :k (:k m)}
                               (contract/error :seon.fx/unknown-kind #'seon.agent-proc/agent-frame (str "no effect kind " (:kind m)))))))
      ;; the agent's turn asks the test service to prove its branch at t (seon.prove): one run for
      ;; whoever asked for that key; the answer comes back on this proc's :in
      :prove (do (step/out! o :to-api {:op :request :req (cond-> {:op :prove :agent id :branch (:branch st) :t (:t m) :digest (:digest m) :mode (:mode m)}
                                                                (:names m) (assoc :names (:names m)))
                                         :route [id :in] :tag (:k m)})
                 st)
      :merged-main (let [st (answer-rid st o (:id m) (:result m))]
                     (step/out! o :events (step/event {:type :merged-main :agent id :result (:result m)}))
                     (step/out! o :to-api (status st {:status (busy st)}))
                     st)
      ;; the end's :in closed: the agent's end frame, EOF or EPIPE (every holder of its
      ;; socket is gone); the end's pumps closed the descriptor
      :dead (do (doseq [[_ {:keys [pass-fd]}] (:inflight st)] (some-> pass-fd sock/close!))
                (step/out! o :to-api {:op :ended :agent id :rid (:draining st) :pid (:pid st) :inflight (vec (keys (:inflight st)))})
                (assoc st :dead? true :inflight {}))
      st)))

(defn agent-proc
  "ONE agent's proc in the root graph, added by the api's update! at spawn and
  removed when the agent has ended. It owns root's end of the
  agent's socket and its process, and runs Flow's lifecycle on the real process:
    start (init)   nothing to open: the api has asked the writer for the fork;
    pause/resume   Flow's own, and passed on: the agent's loop finishes its form, then
                   holds requests ({:op :pause}); while paused this proc takes nothing,
                   so frames stay in the socket and senders are pushed back;
    stop           {:op :stop} to the agent (it exits after the requests before it),
                   then await its death (its socket's EOF), SIGKILL past the bound.
  In :in (from the api), :from (its socket, via the reader). Out :to-writer, :to-api,
  :events (external channels)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:id "agent id" :end "root's end of its socket (jolt.async.remote)" :branch "its branch"}
       :ins {:in "requests from the api, and the writer's answers it routes"} :outs {}
       ;; socket writes are short; a full socket blocks only this agent's proc
       :workload :io
       :ping-map-fn (fn [st] {:id (:id st) :pid (:pid st) :dead? (boolean (:dead? st)) :noise (:noise st)
                              ;; what root asked of it and holds unanswered, rid and kind: what a stuck agent is owed
                              :inflight (into (sorted-map) (update-vals (:inflight st) :kind)) :fx-out (:fx-out st)})})
  ([args] (assoc (select-keys args [:id :end :branch :pid])
                 :inflight {} :noise 0 :fx-out 0 :fx-kinds (:fx-kinds args)
                 ::flow/in-ports {:from (:in (:end args))}
                 ::flow/out-ports {:to-writer (:to-writer args) :to-api (:back args) :events (:events args)}))
  ([st tr]
   (case tr
     ::flow/pause (if (:pid st) (to-proc! st {:op :pause}) st)
     ::flow/resume (if (:pid st) (to-proc! st {:op :resume}) st)
     ::flow/stop (do (when-not (:dead? st)
                       (when-not (:stop-sent st)
                         (if (:pid st) (to-proc! st {:op :stop}) (hang-up! st)))
                       (when-not (await-dead (:in (:end st)) stop-bound-ms)
                         (some-> (:pid st) (lib/c-kill 9))
                         (hang-up! st)
                         (await-dead (:in (:end st)) 5000)))
                     (assoc st :dead? true))
     st))
  ([st in m]
   (case in
     :in (agent-in st m)
     ;; a value from the agent; nil: the end's :in closed (the process is gone)
     :from (let [o (volatile! {})
                 ;; any frame is progress (the stall check reads it); the first after a stall ends it
                 _ (when (and (:stall-seen st) (some? m)) (step/out! o :to-api {:op :spoke :agent (:id st)}))
                 st (agent-frame (assoc st :last-frame-ms (System/currentTimeMillis) :stall-seen false) o (if (nil? m) {:op :dead} m))]
             [st @o])
     [st nil])))
