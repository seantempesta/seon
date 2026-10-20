(ns seon.agent-proc
  "An agent's stand-in in root: one proc of the root graph per agent, added and removed by
  the api with flow/update!. It owns root's end of the agent's socket and its process,
  and passes Flow's pause, resume and stop on to the agent's own loop. It folds each
  eval's nREPL answers into reply items. From proto/flow f2-final proto.control (lane F2)."
  (:require [seon.lib :as lib] [seon.sock :as sock] [seon.wire :as wire] [seon.step :as step]
            [seon.config :as cfg] [clojure.edn] [clojure.core.async :as a] [clojure.core.async.flow :as flow]))

(defn- ->wire
  "An internal request for the agent as its wire message: eval as nREPL's `eval`, every
  other op as seon/<op>."
  [m]
  (if (= :eval (:op m))
    (cond-> {"op" "eval" "id" (str (:id m)) "code" (:forms m)} (:timeout-ms m) (assoc "seon/timeout-ms" (:timeout-ms m)))
    (wire/seon->wire m)))

(defn- to-proc!
  "Send m to the agent's process on its socket (with pass-fd, a descriptor goes along)."
  ([st m] (to-proc! st m nil))
  ([st m pass-fd] (when-not (:dead? st) (if pass-fd (wire/send! (:fd st) (->wire m) pass-fd) (wire/send! (:fd st) (->wire m))))))

(defn- fold-item
  "One form's nREPL answer (with the out/err text that came before it) as a reply item."
  [m out]
  (let [st (set (get m "status"))
        err? (or (st "eval-error") (st "interrupted"))
        em (some-> (get m "seon/error") wire/text-> clojure.edn/read-string)
        t #(some-> (get m %) wire/text->)]
    (cond-> {:id (get m "seon/ordinal") :tag (if err? :err :ret) :form (t "seon/form")
             :ms (some-> (get m "seon/us") (/ 1000.0)) :alloc-bytes (get m "seon/alloc-bytes")}
      (not err?) (assoc :printed (t "value") :ret (t "value") :handle (some-> (get m "seon/handle") keyword))
      err? (assoc :printed (:message em) :err em :err-text (:err out))
      (get m "seon/tokens") (assoc :tokens (get m "seon/tokens"))
      (get m "seon/truncated") (assoc :truncated? true)
      (get m "seon/elisions") (assoc :elisions (get m "seon/elisions"))
      (get m "seon/recorded") (assoc :recorded (symbol (t "seon/recorded")))
      (get m "seon/record-error") (assoc :record-error (t "seon/record-error"))
      (seq (:out out)) (assoc :out (:out out)))))

(defn- fold
  "The answers of one eval request (in order) -> its reply items. `out`/`err` text goes
  onto the next form's item (the form that printed it)."
  [answers]
  (loop [[m & more :as ms] answers acc {:items [] :out {}}]
    (if-not (seq ms)
      (:items acc)
      (let [st (set (get m "status"))]
        (cond
          (or (contains? m "value") (st "eval-error") (st "interrupted"))
          (recur more (-> acc (update :items conj (fold-item m (:out acc))) (assoc :out {})))
          (contains? m "out") (recur more (update-in acc [:out :out] str (wire/text-> (get m "out"))))
          (contains? m "err") (recur more (update-in acc [:out :err] str (wire/text-> (get m "err"))))
          :else (recur more acc))))))

(defn- status [st m] {:op :status :agent (:id st) :set m})

(defn- busy [st] (if (seq (:inflight st)) :busy :idle))

(defn- await-dead
  "Wait (bounded) on the stand-in's own :from for the socket's :dead (EOF: the process is gone)."
  [from ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [[m _] (a/alts!! [from (a/timeout (max 1 (- deadline (System/currentTimeMillis))))])]
        (cond (nil? m) false
              (= :dead (get-in m [:msg :op])) true
              :else (recur))))))

(defn- agent-in
  "A message from the api (or the writer's answer, routed by the api)."
  [st m]
  (case (:op m)
    :req (let [{:keys [rid msg kind]} m]
           (to-proc! st (assoc msg (if (= kind :clone) :k :id) rid) (:pass-fd m))
           (let [st (cond-> (update st :inflight assoc rid (select-keys m [:kind :branch :new :merge-branch :pass-fd]))
                      (= kind :ping) (update :pings conj rid))]
             [st {:to-api [(status st (cond-> {:status :busy}
                                        (= kind :eval) (assoc :running {:form (let [f (:forms msg)] (if (> (count f) 200) (str (subs f 0 200) " …") f))
                                                                        :since-ms (System/currentTimeMillis)})))]}]))
    :write (do (to-proc! st (:msg m)) [st nil])
    :bytes (do (when-not (:dead? st) (sock/send-frame! (:fd st) (:bytes m))) [st nil])
    :forked (do (when (and (:kill? st) (not (:dead? st))) (lib/c-kill (:pid m) 9))
                [(assoc st :pid (:pid m)) nil])
    ;; a process that never existed reads as dead: hang up root's end, the reader sees EOF
    :failed (do (sock/hang-up! (:fd st)) [st {:to-api [(status st {:error (:error m)})]}])
    :drain (do (if (:pid st) (to-proc! st {:op :stop}) (sock/hang-up! (:fd st)))
               [(assoc st :draining (:rid m) :stop-sent true) nil])
    :kill (do (if (:pid st) (lib/c-kill (:pid st) 9) (sock/hang-up! (:fd st)))
              [(assoc st :kill? true) nil])
    [st nil]))

(defn- answer-rid [st o rid r]
  (step/out! o :to-api {:dest {:rid rid} :r r})
  (update st :inflight dissoc rid))

(defn- agent-frame
  "A frame from the agent's process."
  [st o m]
  (let [id (:id st)]
    (case (:op m)
      :hello (do (when (:cloned-from m)
                   ;; a clone: its pid arrives here (the fork server never saw it)
                   (when (:kill? st) (lib/c-kill (:pid m) 9))
                   (step/out! o :events (step/event {:type :spawned :agent id :pid (:pid m) :cloned-from (:cloned-from m)})))
                 (step/out! o :to-api (status st (cond-> {:forked-ms (lib/r2 (:start-ms m)) :mem-mb (:mem-mb m) :t (:t m) :ready true
                                                     :interrupt-ok (:interrupt-ok m)}
                                              (:cloned-from m) (assoc :pid (:pid m)))))
                 (step/out! o :events (step/event {:type :ready :agent id :start-ms (:start-ms m)}))
                 (cond-> st (:cloned-from m) (assoc :pid (:pid m))))
      :branch-me (let [{:keys [branch]} (get-in st [:inflight (:k m)])]
                   (step/out! o :to-writer {:op :clone-branch :from (:branch st) :branch branch
                                       :dest {:agent id :op :branched :k (:k m)}})
                   st)
      (:cloned :clone-failed)
      (let [{:keys [new pass-fd]} (get-in st [:inflight (:k m)])
            ;; the clone's socket end: the parent has it (or failed), root's copy closes now
            _ (some-> pass-fd sock/close!)
            st (answer-rid st o (:k m) (if (= :cloned (:op m)) {:ok true :id new} {:ok false :error (:error m)}))]
        (when (= :clone-failed (:op m)) (step/out! o :to-api {:op :clone-failed :agent new :error (:error m)}))
        (step/out! o :to-api (status st {:status (busy st)}))
        st)
      :synced (do (step/out! o :to-api (status st {:t (:t m) :load-errors (:load-errors m)})) st)
      ;; the back-pressure check's flood: counted, in order
      :noise (let [ok (= (:i m) (:noise st 0))
                   ordered (and (:noise-ordered st true) ok)
                   bad (or (:noise-bad st) (when-not ok [(:i m) (:noise st 0)]))]
               (step/out! o :to-api {:op :stat :k :noise :v (inc (:noise st 0)) :ordered ordered :bad bad})
               (assoc st :noise (inc (:noise st 0)) :noise-ordered ordered :noise-bad bad))
      :received (do (step/out! o :to-api {:op :stat :k :msg-us :v (/ (- (:recv-ns m) (:sent-ns m)) 1000.0)}) st)
      :send (do (step/out! o :to-api {:op :message :from id :to (:to m) :body (:body m)}) st)
      :pong (if-let [rid (peek (:pings st))]
              (answer-rid (update st :pings pop) o rid (dissoc m :op))
              st)
      :reply (let [st (answer-rid st o (:id m) (:items m))]
               (step/out! o :to-api (status st {:evals-add (:items m) :mem-mb (:mem-mb m) :t (:t m) :status (busy st) :running nil}))
               (step/out! o :events (step/event {:type :reply :agent id :items (:items m)}))
               st)
      :tests (let [{:keys [kind merge-branch]} (get-in st [:inflight (:id m)])
                   result (:result m)
                   green? (and (zero? (:fail result 0)) (zero? (:error result 0)))
                   summary (select-keys result [:pass :fail :error :test])
                   st (if (and (= kind :merge-tests) green?)
                        ;; a gated merge's green run goes on to the writer; its answer is the merge's
                        (do (step/out! o :to-writer {:op :merge :branch merge-branch :dest {:merged id :rid (:id m) :tests summary}})
                            (update st :inflight dissoc (:id m)))
                        (answer-rid st o (:id m) (if (= kind :merge-tests)
                                                   {:merged? false :tests summary :reports (vec (remove #(= :pass (:type %)) (:reports result)))}
                                                   result)))]
               (step/out! o :to-api (status st {:tests result :mem-mb (:mem-mb m) :status (busy st)}))
               (step/out! o :events (step/event {:type :tests :agent id :ms (:ms result) :pass (:pass result) :fail (:fail result) :error (:error result)}))
               st)
      :stats (let [st (answer-rid st o (:id m) (:result m))]
               (step/out! o :to-api (status st {:mem-mb (:mem-mb (:result m)) :status (busy st)}))
               st)
      :tx (do (step/out! o :to-writer {:op :tx :branch (:branch st) :agent id :tx-data (:tx-data m)
                                  :dest {:agent id :op :tx-reply :k (:k m)}})
              st)
      :dead (do (sock/close! (:fd st))
                (doseq [[_ {:keys [pass-fd]}] (:inflight st)] (some-> pass-fd sock/close!))
                (step/out! o :to-api {:op :ended :agent id :rid (:draining st) :pid (:pid st) :inflight (vec (keys (:inflight st)))})
                (assoc st :dead? true :inflight {}))
      st)))

(defn agent-proc
  "ONE agent's stand-in: a proc of the root graph, added by the api's update! at spawn and
  removed when the agent has ended. It owns root's end of the
  agent's socket and its process, and runs Flow's lifecycle on the real process:
    start (init)   nothing to open: the api has asked the writer for the fork;
    pause/resume   Flow's own, and passed on: the agent's loop finishes its form, then
                   holds requests ({:op :pause}); while paused this proc takes nothing,
                   so frames stay in the socket and senders are pushed back;
    stop           {:op :stop} to the agent (it exits after the requests before it),
                   then await its death (kqueue), SIGKILL past the bound.
  In :in (from the api), :from (its socket, via the reader). Out :to-writer, :to-api,
  :events (external channels)."
  {:malli/schema step/step-schema}
  ([] {:params {:id "agent id" :fd "root's end of its socket" :branch "its branch"}
       :ins {:in "requests from the api, and the writer's answers it routes"} :outs {}
       ;; socket writes are short; a full socket blocks only this agent's proc
       :workload :io
       :ping-map-fn (fn [st] {:id (:id st) :pid (:pid st) :dead? (boolean (:dead? st)) :inflight (count (:inflight st))})})
  ([args] (assoc (select-keys args [:id :fd :branch :pid])
                 :inflight {} :pings clojure.lang.PersistentQueue/EMPTY
                 ::flow/in-ports {:from (:from args)}
                 ::flow/out-ports {:to-writer (:to-writer args) :to-api (:back args) :events (:events args)}))
  ([st tr]
   (case tr
     ::flow/pause (do (when (:pid st) (to-proc! st {:op :pause})) st)
     ::flow/resume (do (when (:pid st) (to-proc! st {:op :resume})) st)
     ::flow/stop (do (when-not (:dead? st)
                       (when-not (:stop-sent st)
                         (if (:pid st) (to-proc! st {:op :stop}) (sock/hang-up! (:fd st))))
                       (when-not (await-dead (get-in st [::flow/in-ports :from]) cfg/STOP-BOUND-MS)
                         (some-> (:pid st) (lib/c-kill 9))
                         (sock/hang-up! (:fd st))
                         (await-dead (get-in st [::flow/in-ports :from]) 5000)))
                     (assoc st :dead? true))
     st))
  ([st in m]
   (case in
     :in (agent-in st m)
     :from (let [o (volatile! {})
                 w (:msg m)
                 st (if-let [internal (cond (contains? w :op) w            ; the reader's own {:op :dead}
                                            (contains? w "op") (wire/wire->seon w))]
                      (agent-frame st o internal)
                      ;; an nREPL answer to an eval: kept until its done, then one reply
                      (let [rid (some-> (get w "id") parse-long)
                            st (update-in st [:answers rid] (fnil conj []) w)]
                        (if (some #{"done"} (get w "status"))
                          (agent-frame (update st :answers dissoc rid) o
                                       {:op :reply :id rid :items (fold (get-in st [:answers rid]))
                                        :mem-mb (some-> (get w "seon/mem-mb") parse-double) :t (get w "seon/t")})
                          st)))]
             [st @o])
     [st nil])))
