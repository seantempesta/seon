(ns seon.agent-proc
  "An agent's proc in root: one proc of the root graph per agent, added and removed by
  the api with flow/update!. It owns root's end of the agent's socket, a jolt.async.remote
  end (session RC1): the agent's frames arrive as values on the end's :in, which IS this
  proc's :from in-port, and what it sends the agent is put on the end's :out. It owns the
  agent's process and passes Flow's pause, resume and stop on to the agent's own graph (seon.repl/transport-step).
  From proto/flow f2-final proto.control (session F2)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.contract :as contract :refer [defn defn-]] [seon.lib :as lib] [seon.config :as cfg] [seon.sock :as sock] [seon.step :as step]
            [jolt.async.remote :as remote] [clojure.core.async :as a] [clojure.core.async.flow :as flow]))

(defn unencodable
  "What root's end sends the agent in place of message v, which the codec threw t encoding (the
  end's :encode-error, jolt.async.remote, with the end's :in channel in). The typed
  :seon.agent/unencodable, as the answer v was (a :reply's :result, an :fx-reply's outcome, under
  v's :k, so the agent's wait for it gets it). For a request of root's (its rid: :id, a clone's :k)
  the asker is root, not the agent: the error goes to this proc's own :in as a frame
  ({:op :unsendable :rid}, agent-frame answers the rid with it), and the agent is sent an answer
  no wait holds (its transport takes it as an unawaited one). The end stays open. See
  docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data."
  {:malli/schema [:=> [:cat :seon.host/chan :map :seon.host/throwable] :seon.wire/seon-msg]}
  [in v t]
  (let [e (contract/error :seon.agent/unencodable #'seon.agent-proc/unencodable
                          (str "a " (some-> (:op v) name) " message holds a value the wire cannot encode: " (ex-message t))
                          {:message-op (:op v)})
        rid (if (= :clone (:op v)) (:k v) (:id v))]
    (case (:op v)
      :fx-reply (merge e (select-keys v [:op :k]))
      (:reply :fd :branched) (assoc (select-keys v [:op :k]) :result e)
      (do (when (int? rid) (a/put! in {:op :unsendable :rid rid :error e}))
          {:op :reply :id rid :result e}))))

(defn- to-proc!
  "Send m to the agent's process: onto the end's :out (its writer pump frames it). While a
  descriptor is in its handshake (:holding), m waits in :held. The put never blocks this proc
  (core.async put!: one the channel cannot take now waits on it, in order, holding no thread):
  an agent that stops reading its socket left the blocking put (>!!) parked in this proc's
  step, so the proc took nothing more from its agent, its stall frame included (docs/processes.md#a-stalled-agent).
  An agent that reads nothing sends nothing, so the stall check sees it quiet."
  {:malli/schema [:=> [:cat :map :seon.wire/seon-msg] :map]}
  [st m]
  (cond (:dead? st) st
        (:holding st) (update st :held (fnil conj []) m)
        :else (do (a/put! (:out (:end st)) m) st)))

(defn- end-hold
  "St with the clone handshake of request rid ended, whatever ended it (its fd sent, the clone answered or failed, the request
  never sent): what waited behind it (:held) goes out in order, up to a clone request held behind it, which goes out and holds
  in turn. A hold ended only by :fd-ready waited for good when a clone failed first, and a second clone sent while one held
  overwrote the first's hold, so neither's end released it (docs/processes.md#the-socket-and-the-agent-proc)."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st rid]
  (if (not= rid (:holding st))
    st
    (loop [xs (seq (:held st)) st (dissoc st :holding :held)]
      (if-let [[x & more] xs]
        (if-let [c (::clone x)]
          (-> st (to-proc! c) (assoc :holding (::rid x) :held (vec more)))
          (recur more (to-proc! st x)))
        st))))

(defn- send-fd!
  "The descriptor fd to the agent, in a frame of its own ({:op :fd :k k}, SCM_RIGHTS). The
  agent asked for it ({:op :fd-ready}) after taking the request before it, and nothing was
  put on :out since (held), so the writer pump is idle: this frame cannot interleave with
  another. Then what was held goes out."
  {:malli/schema [:=> [:cat :map :int :seon.lib/fd] :map]}
  [st k fd]
  (sock/send! (:fd (:end st)) {:op :fd :k k} fd)
  (end-hold st k))

(defn- hang-up!
  "Close root's end: the agent (if any) reads the end frame; this proc's :from closes."
  {:malli/schema [:=> [:cat :map] :any]}
  [st] (remote/close! (:end st)))

(defn- status {:malli/schema [:=> [:cat :map :map] :map]}
  [st m] {:op :status :agent (:id st) :set m})

(defn- busy
  "The agent's status as root records it: :busy while a request that is work is in flight; a read (a :context read,
  an eval of origin :context) is in flight without making it busy, so a read writes nothing to main (the owner's ruling, a read
  never writes: docs/decisions.md; docs/server.md#ops)."
  {:malli/schema [:=> [:cat :map] [:enum :busy :idle]]}
  [st] (if (some (complement :read?) (vals (:inflight st))) :busy :idle))

(defn- stop!
  "Stop the agent by draining it: :stop, which its transport reads at once (the run in flight ends between turns,
  seon.turn/work!) and its REPL answers after the requests before it by exiting, each ending by its own bound (an eval's
  tick budget): nothing in flight is cut. With no process, root's end hung up; a dead one, nothing. One that never exits
  is a stall, which ends it while root stops (seon.system/back-op's :stalled), and the stop's own bound
  (:seon.config.root/stop-ms) ends root. See docs/processes.md#the-agent-process. Answers st."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (cond (or (:stop-sent st) (:dead? st)) st
        (:pid st) (assoc (to-proc! st {:op :stop}) :stop-sent true)
        :else (do (hang-up! st) (assoc st :stop-sent true))))

(declare agent-frame)

(defn- agent-in
  "A message from the api (or the writer's answer, routed by the api)."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map :seon.step/outputs]]}
  [st m]
  (case (:op m)
    ;; an answer to this agent's ask on the api's reply route (the test service's, seon.prove; its
    ;; the render proc's)
    nil [(cond-> (to-proc! st {:op :reply :k (:tag m) :result (:r m)})
           (contains? (:gates st) (:tag m)) (-> (update :gates disj (:tag m)) (update :fx-out (fnil dec 1)))) nil]
    :req (let [{:keys [rid msg kind]} m
               read? (= :context (:origin msg))]
           (let [out (assoc msg (if (= kind :clone) :k :id) rid)
                 ;; a clone sent while another clone's handshake holds waits behind it as a request, and holds when it goes out
                 queued? (and (= kind :clone) (:holding st))
                 st (cond-> (if queued? (update st :held (fnil conj []) {::clone out ::rid rid}) (to-proc! st out))
                      ;; a clone's socket follows in its own frame once the agent asks
                      (and (= kind :clone) (not queued?)) (assoc :holding rid)
                      true (update :inflight assoc rid (cond-> (assoc (select-keys m [:kind :branch :new :pass-fd]) :read? read?)
                                                         ;; an eval's text, for its :started event
                                                         (string? (:forms msg)) (assoc :form (:forms msg))))
                      ;; what root last asked of it, for a stall or a death (seon.agent/last-op)
                      true (assoc :last-op (cond-> {:kind kind :since-ms (System/currentTimeMillis)}
                                             (string? (:forms msg)) (assoc :form (:forms msg)))))]
             ;; a read is in flight without making the agent busy: root records nothing of it
             [st (if read? nil
                   {:to-api [(status st (cond-> {:status :busy}
                                          (= kind :eval) (assoc :running {:form (:forms msg)
                                                                          :since-ms (System/currentTimeMillis)})))]})]))
    :write [(cond-> (to-proc! st (:msg m)) (= :fx-reply (:op (:msg m))) (update :fx-out (fnil dec 1))) nil]
    ;; root's stall check (seon.system's timer): a request in flight, no frame from the agent for longer than the stall bound
    ;; and no effect of its outstanding is a stall, observed once per quiet spell. It stays only for what the agent cannot
    ;; say itself (seon.agent/quiescent!): an eval pinned in a blocking foreign call; it goes with task
    ;; jolt.host/a-blocking-call-on-a-main-carrier-parks
    :stall? (let [quiet (- (:now-ms m) (:last-frame-ms st (:now-ms m)))]
              (if (and (seq (:inflight st)) (not (:stall-seen st)) (> quiet (:stall-ms m)) (zero? (:fx-out st 0)) (not (:dead? st)))
                [(assoc st :stall-seen true)
                 {:to-api [{:op :stalled :agent (:id st)
                            :error (contract/error :seon.agent/stalled #'seon.agent-proc/agent-in
                                                   (str "agent " (name (:id st)) " sent nothing for " quiet " ms with a request in flight")
                                                   {:agent (:id st) :quiet-ms quiet :last-op (:last-op st)})}]}]
                [st nil]))
    :forked (do (when (and (:kill? st) (not (:dead? st))) (lib/kill! (:pid m) 9))
                [(assoc st :pid (:pid m)) nil])
    ;; a process that never existed reads as dead: hang up root's end, the reader sees EOF
    :failed (do (hang-up! st) [st {:to-api [(status st {:error (:error m)})]}])
    :drain [(assoc (stop! st) :draining (:rid m)) nil]
    :kill (do (if (:pid st) (lib/kill! (:pid st) 9) (hang-up! st))
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
      ;; the agent deadlocked (seon.agent/quiescent!: no fiber can run, nothing it asked root owed): its stall, said by its
      ;; source, once per quiet spell (its evidence, the fiber dump among it, root's stall task captures); quiet since the frame before it
      :stalled (let [quiet (- (System/currentTimeMillis) (:last-frame-ms st (System/currentTimeMillis)))]
                 (when-not (:stall-seen st)
                   (step/out! o :to-api {:op :stalled :agent id
                                         :error (contract/error :seon.agent/stalled #'seon.agent-proc/agent-frame
                                                                (str "agent " (name id) " deadlocked with a request in flight: no fiber can run, and it waits on nothing root owes it")
                                                                {:agent id :quiet-ms quiet :last-op (:last-op st)})}))
                 (assoc st :stall-seen true))
      ;; a test of its run started (seon.agent/run-tests-forked): what it waits on now, which a
      ;; stall names
      :testing (assoc st :last-op {:kind :tests :form (:test m)})
      :hello (do (when (:cloned-from m)
                   ;; a clone: its pid arrives here (the fork server never saw it)
                   (when (:kill? st) (lib/kill! (:pid m) 9))
                   (step/out! o :events (step/event {:type :spawned :agent id :pid (:pid m) :cloned-from (:cloned-from m)})))
                 (step/out! o :to-api (status st (cond-> {:forked-ms (lib/r2 (:start-ms m)) :mem-mb (:mem-mb m) :t (:t m) :ready true :phases (:phases m)}
                                              (:cloned-from m) (assoc :pid (:pid m))
                                              (seq (:load-errors m)) (assoc :load-errors (:load-errors m))
                                              (:seon.process/env-mismatch m) (assoc :seon.process/env-mismatch (:seon.process/env-mismatch m))
                                              (seq (:seon.agent/missing-cites m)) (assoc :seon.agent/missing-cites (:seon.agent/missing-cites m)))))
                 ;; root's interrupt cannot reach it (its SIGUSR1 handler never ran: a process not
                 ;; forked from a main thread): a recorded problem, to whoever can act on it
                 (when (false? (:interrupt-ok m))
                   (step/out! o :to-api {:op :problem :class :agent :agent id
                                         :error (contract/error :seon.agent/interrupt-unreachable #'seon.agent-proc/agent-frame
                                                                "root's interrupt (SIGUSR1) does not reach this agent: its handler did not run at boot, so a runaway eval can only be killed" {})}))
                 ;; its program did not load: main's as it was forked (seon.fork-server/stood), or its branch's where it resumed; each failure
                 ;; a recorded problem of this agent, so no agent starts without its program silently
                 ;; its branch's merge base was swept (docs/storage.md#collection): it runs main's program, and the lost base is its problem, as typed
                 (when-let [e (:seon.db/no-merge-base m)]
                   (step/out! o :to-api {:op :problem :class :agent :agent id :error e}))
                 (doseq [{:keys [ns message form lead]} (:load-errors m)]
                   (step/out! o :to-api {:op :problem :class :agent :agent id
                                         :error (contract/error :seon.agent/load-failed #'seon.agent-proc/agent-frame
                                                                (str "its program did not load in " ns (when lead (str ", at the form " lead)) ": " message)
                                                                (cond-> {:ns ns} form (assoc :form form)))}))
                 ;; a section its config cites that its store does not hold: the agent runs citing the rest, and each missing address is a
                 ;; recorded problem of this agent, with the one thing not to do about it
                 (doseq [a (:seon.agent/missing-cites m)]
                   (step/out! o :to-api {:op :problem :class :agent :agent id
                                         :error (contract/error :seon.agent/cite-missing #'seon.agent-proc/agent-frame
                                                                (str "the store lacks section " a ", which this agent's config cites: it was made without that cite. "
                                                                     "The store is older than its wiki; a reindexing start (bin/system start --reindex) erases the store")
                                                                {:seon.doc/address a})}))
                 ;; its socket to root cannot raise SIGIO: it ends with root only when it next reads
                 (when-let [e (:unwatched m)] (step/out! o :to-api {:op :problem :class :agent :agent id :error e}))
                 (when-let [x (:misaligned m)]
                   (step/out! o :to-api {:op :problem :class :agent :agent id
                                         :error (contract/error :seon.agent/fork-misaligned #'seon.agent-proc/agent-frame
                                                                (str "its branch or image is not at the commit it was branched at: " (sedn/encode x)) x)}))
                 (step/out! o :events (step/event {:type :ready :agent id :start-ms (:start-ms m) :phases (:phases m)}))
                 (cond-> st (:cloned-from m) (assoc :pid (:pid m)) (false? (:interrupt-ok m)) (assoc :interrupt-unreachable true)))
      ;; its eval's record could not be written on its branch (its entity ids are spent):
      ;; the typed error is recorded on main as its problem
      :fd-ready (send-fd! st (:k m) (get-in st [:inflight (:k m) :pass-fd]))
      ;; merge main into this agent's branch, on main's program: root's writer decides and writes it (seon.writer's :merge-main), and
      ;; its answer is the agent's wait's (seon.agent/merge-main!)
      :merge-main (do (step/out! o :to-writer {:op :merge-main :branch (:branch st) :dest {:agent id :op :reply :k (:k m)}}) st)
      :branch-me (let [branch (get-in st [:inflight (:k m) :branch])]
                   (step/out! o :to-writer {:op :clone-branch :from (:branch st) :branch branch
                                       :dest {:agent id :op :branched :k (:k m)}})
                   st)
      (:cloned :clone-failed)
      (let [{:keys [new pass-fd]} (get-in st [:inflight (:k m)])
            ;; the clone's socket end: the parent has it (or failed), root's copy closes now
            _ (some-> pass-fd sock/close!)
            st (answer-rid (end-hold st (:k m)) o (:k m) (if (= :cloned (:op m)) {:ok true :id new} (:error m)))]
        (when (= :clone-failed (:op m)) (step/out! o :to-api {:op :clone-failed :agent new :error (:error m)}))
        (step/out! o :to-api (status st {:status (busy st)}))
        st)
      ;; a request of root's the end's codec could not encode (unencodable): never sent, so answered here
      :unsendable (let [{:keys [pass-fd]} (get-in st [:inflight (:rid m)])
                        _ (some-> pass-fd sock/close!)
                        st (answer-rid (end-hold st (:rid m)) o (:rid m) (:error m))]
                    (step/out! o :to-api (status st {:status (busy st) :running nil}))
                    st)
      :synced (do (step/out! o :to-api (status st {:t (:t m) :load-errors (:load-errors m)})) st)
      ;; the back-pressure check's flood: counted, in order
      :noise (let [ok (= (:i m) (:noise st))
                   ordered (and (:noise-ordered st true) ok)
                   bad (or (:noise-bad st) (when-not ok [(:i m) (:noise st)]))]
               (step/out! o :to-api {:op :stat :k :noise :v (inc (:noise st)) :ordered ordered :bad bad})
               (assoc st :noise (inc (:noise st)) :noise-ordered ordered :noise-bad bad))
      ;; an eval's in-flight output (seon.repl/streaming), a piece at a time over the socket (which drops nothing): appended to
      ;; the part's whole text so far, held here until its :end, and that whole sent to the web proc's :streams in-port (Flow's
      ;; dynamic route, a sliding buffer: a dropped message loses nothing, the next holds it all); display only
      :stream (if (:end m)
                (do (step/out! o [:web :streams] (dissoc m :op)) (update st ::streams dissoc (:stream m)))
                (let [text (conj (get-in st [::streams (:stream m)] []) (:text m))]
                  (step/out! o [:web :streams] (assoc (dissoc m :op) :text text))
                  (assoc-in st [::streams (:stream m)] text)))
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
      ;; the agent committed its branch (it owns the branch's writes): the writer's next flush
      ;; covers it
      :committed (do (step/out! o :to-writer {:op :agent-commit :branch (:branch st) :t (:t m)})
                     ;; its memory and its turn (when it moved) in one status; :turn is kept in root's view, never a fact on main
                     (when (or (:mem-mb m) (:turn m)) (step/out! o :to-api (status st (select-keys m [:mem-mb :turn]))))
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
      ;; the agent's message passes on whole (its :names, :proofs, ...), never re-listed key by key here: a key listed here
      ;; and not there was how an agent's :proofs never reached the service; the proc sets only what is its to say (who, which branch)
      :prove (do (step/out! o :to-api {:op :request :req (assoc (dissoc m :k) :agent id :branch (:branch st))
                                         :route [id :in] :tag (:k m)})
                 st)
      ;; the agent launches a task of its own (my.task/launch!): root makes it, as a person's sentence
      :launch (do (step/out! o :to-api {:op :request :req (cond-> {:op :ask :text (:text m) :agent id} (:template m) (assoc :template (:template m))) :route [id :in] :tag (:k m)})
                  st)
      ;; the agent moves the person's view (my.user/focus!): root writes the signed-in user's focus
      :focus (do (step/out! o :to-api {:op :request :req {:op :focus :key (:key m)} :route [id :in] :tag (:k m)})
                 st)
      ;; the agent asks its branch's merge gate run (seon.agent/judge!): quiet for as long as an effect it awaits, so no stall
      :gate (do (step/out! o :to-api {:op :request :req {:op :gate :branch (:branch st) :tests (:tests m) :prio (:prio m 0)} :route [id :in] :tag (:k m)})
                (-> st (update :gates (fnil conj #{}) (:k m)) (update :fx-out (fnil inc 0))))
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
                              :inflight (into (sorted-map) (update-vals (:inflight st) :kind)) :fx-out (:fx-out st)
                              ;; its stall: whether the agent said one (seon.agent/quiescent!), and when its last frame came (nil: none yet)
                              :stall-seen (boolean (:stall-seen st)) :last-frame-ms (:last-frame-ms st)
                              ;; a clone's handshake it holds (the request id) and how many messages wait behind it: a held agent reads as held
                              :holding (:holding st) :held (count (:held st))})})
  ([args] (assoc (select-keys args [:id :end :branch :pid])
                 :inflight {} :noise 0 :fx-out 0 :fx-kinds (:fx-kinds args)
                 ::flow/in-ports {:from (:in (:end args))}
                 ::flow/out-ports {:to-writer (:to-writer args) :to-api (:back args) :events (:events args)}))
  ([st tr]
   (case tr
     ::flow/pause (if (:pid st) (to-proc! st {:op :pause}) st)
     ::flow/resume (if (:pid st) (to-proc! st {:op :resume}) st)
     ;; told to stop, never waited on (docs/flow.md#the-five-rules): an agent proc leaves while root runs only once its
     ;; death is recorded (its end's EOF, :dead); at root's own stop the process reads :stop, or its end's close
     ::flow/stop (stop! st)
     st))
  ([st in m]
   (case in
     :in (agent-in st m)
     ;; a value from the agent; nil: the end's :in closed (the process is gone)
     :from (let [o (volatile! {})
                 ;; any frame but the stall itself is progress: the first after a stall ends it
                 progress? (and (some? m) (not= :stalled (:op m)))
                 _ (when (and (:stall-seen st) progress?) (step/out! o :to-api {:op :spoke :agent (:id st)}))
                 st (agent-frame (cond-> st progress? (assoc :stall-seen false)) o (if (nil? m) {:op :dead} m))]
             ;; progress, or its end, is a frame the stall evidence reads as the agent speaking; the stall itself is not
             [(cond-> st (or progress? (nil? m)) (assoc :last-frame-ms (System/currentTimeMillis))) @o])
     [st nil])))
