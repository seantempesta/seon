(ns seon.step
  "The step-fn contract as the one process abstraction (session F2; proto/flow f2-final
  proto.step and proto.proc). Root's procs run under
  core.async.flow. A process that must stay single-threaded so it can fork (the fork
  server, an agent) runs the SAME step-fn on its own thread with `run-loop`, and
  receives Flow's lifecycle as data on its transport:
    {:op :pause}   the pause transition; then only :resume, :stop and :ping are taken,
                   every other message is held, in order
    {:op :resume}  the resume transition; the held messages are taken first
    {:op :ping}    answered at once on the same port: {:op :pong :status :count :state},
                   :state being the step-fn's own :ping-map-fn of its state
    {:op :stop}    the stop transition, then run-loop returns (the caller exits)
  A message taken while the step is running (a nested read inside a form, in an agent)
  is the step's own business; control messages it meets there are pushed back for the
  loop."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.core.async :as a] [clojure.core.async.flow :as flow] [seon.contract :refer [defn defn-]]))

;; The contract every step-fn Var carries is the registered :seon.step/step-fn
;; (resources/seon/schemas/seon.core.edn): describe, init, transition, transform.

(defn control-op
  "The lifecycle op of a transport message, or nil for ordinary data."
  {:malli/schema [:=> [:cat :any] :seon.step/control]}
  [m] (when (map? m) (#{:pause :resume :ping :stop} (:op m))))

(defn run-loop
  "Run step (a Var holding a four-arity step-fn) on this thread until :stop or EOF.
  recv! answers [in-id msg], or nil at EOF; send! takes [out-id msg]. Answers the final
  state, after the ::flow/stop transition."
  {:malli/schema [:=> [:cat [:or :seon.host/var :seon.host/fn] :any :seon.host/fn :seon.host/fn] :any]}
  [step args recv! send!]
  (let [ping-map-fn (or (:ping-map-fn (step)) identity)]
    (loop [st (step args) status :running held clojure.lang.PersistentQueue/EMPTY n 0]
      (let [from-held? (and (= status :running) (seq held))
            im (if from-held? (peek held) (recv!))
            held (if from-held? (pop held) held)]
        (if (nil? im)
          (step st ::flow/stop)
          (let [[in m] im]
            (case (control-op m)
              :pause (recur (if (= status :running) (step st ::flow/pause) st) :paused held n)
              :resume (recur (if (= status :paused) (step st ::flow/resume) st) :running held n)
              :ping (do (send! in {:op :pong :status status :count n :held (count held) :state (ping-map-fn st)})
                        (recur st status held n))
              :stop (step st ::flow/stop)
              (if (= status :paused)
                (recur st status (conj held im) n)
                (let [[st outs] (step st in m)]
                  (doseq [[port msgs] outs, msg msgs] (send! port msg))
                  (recur st status held (inc n)))))))))))

;; ---- helpers every proc's transform uses ---------------------------------------------

(defn out!
  "Add message m to port's outputs in the volatile o (a transform's outputs, built up)."
  {:malli/schema [:=> [:cat :seon.host/volatile [:or :keyword [:tuple :keyword :keyword]] :any] :nil]}
  [o port m] (vswap! o update port (fnil conj []) m) nil)

;; Request/answer with credit (AGENTS §3: `{:id :reply}`, exactly one answer), the asker's
;; side, once for every proc: a request id from the state's :rid counter, and what awaits its
;; answer under :pending. Root's api, the reconciler and the render proc keep their asks so.

(defn pend
  "st with v awaiting the answer to a new request id (v nil: an id nothing awaits), and the
  id. Answers [st' rid]."
  {:malli/schema [:=> [:cat [:map [:rid :int] [:pending :map]] :any] [:tuple :map :int]]}
  [st v] (let [rid (inc (:rid st))] [(cond-> (assoc st :rid rid) (some? v) (assoc-in [:pending rid] v)) rid]))

(defn ask
  "A proc's request to root's api, through the server: st with v pending under a new id,
  and the message for the proc's :to-api port; the api answers {:tag rid :r r} on route
  ([pid in]). Answers [st' message]."
  {:malli/schema [:=> [:cat [:map [:rid :int] [:pending :map]] [:tuple :keyword :keyword] :any :map] [:tuple :map :map]]}
  [st route v req] (let [[st rid] (pend st v)] [st {:op :request :req req :route route :tag rid}]))

(defn answered
  "The answer to request rid taken: st without rid's entry, and what awaited it (nil: nothing,
  or answered already). Answers [st' v]."
  {:malli/schema [:=> [:cat [:map [:pending :map]] :any] [:tuple :map :any]]}
  [st rid] [(update st :pending dissoc rid) (get-in st [:pending rid])])

(defn ask-edge
  "An edge's request (a caller outside the graph, on a thread of its own: the web's handler, root's
  REPL surface, root's stop): m with a fresh promise as :p, put on the proc's channel ch, and that
  promise, which the proc delivers its one answer to. A channel already closed answers closed at
  once: every request is answered, none left waiting on a proc that will never take it. The one
  way an edge asks a proc (seon.main-test/an-edge-asks-one-way). Given p, the answer goes to that
  promise (a caller that is also waiting on something else delivered to it: seon.root/await-db)."
  {:malli/schema [:function [:=> [:cat :seon.host/chan :map :any] :seon.host/promise]
                  [:=> [:cat :seon.host/chan :map :any :seon.host/promise] :seon.host/promise]]}
  ([ch m closed] (ask-edge ch m closed (promise)))
  ([ch m closed p]
   (when-not (a/>!! ch (assoc m :p p)) (deliver p closed))
   p))

(defn event "m stamped with the wall-clock ms, for the events channel." {:malli/schema [:=> [:cat :map] :map]} [m] (assoc m :at-ms (System/currentTimeMillis)))

(defn error-msg "An exception's message, or its class." {:malli/schema [:=> [:cat :seon.host/throwable] :string]} [^Throwable e] (or (ex-message e) (str (type e))))
