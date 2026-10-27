;; Prototype (2026-10-25, design agent-loop section 8, option 3): core.async and Flow on a
;; process's one thread. Carrier 0 of Jolt's fiber pool is drained by the calling thread
;; and no carrier thread is started; jolt.io-poller's rounds run on the same thread when the
;; run queue is empty, so no poller thread either. The process stays one OS thread, so it
;; forks as safely as today, while its go blocks, Flow procs and jolt.async.remote ends
;; (pump :fiber) work as anywhere. Evaluated at an agent's REPL; the Jolt change would put
;; mode! in the runtime and make <!! on the main thread run `run-until`.
(ns lab.main-carrier
  (:require [clojure.core.async :as a] [jolt.scheme :as scm] [jolt.ffi :as ffi]))

(def ^:private pstate @(resolve 'jolt.io-poller/state))
(def ^:private poller-round @(resolve 'jolt.io-poller/poller-round))
(def ^:private process-events! @(resolve 'jolt.io-poller/process-events!))
(def ^:private kevent-put! @(resolve 'jolt.io-poller/kevent-put!))
(def ^:private nonblock! @(resolve 'jolt.io-poller/nonblock!))

(defn mode!
  "Make this process's fibers run on its own thread: one carrier, marked started so no
  carrier thread is forked, and the poller set up with its kqueue and wake pipe but no
  thread. Call before anything spawns a fiber (a fresh agent, or a child just forked)."
  []
  (scm/eval-string "(begin (jolt-fiber-carrier-count-set! 1) (set! jolt-fiber-pool-started? #t) (jolt-fiber-ensure-carriers!))")
  (let [pfds (ffi/alloc 8)]
    (assert (zero? (jolt.io-poller/c-pipe pfds)) "pipe")
    (let [r (ffi/read pfds :int 0) w (ffi/read pfds :int 4) kq (jolt.io-poller/c-kqueue)
          ch (ffi/alloc 32)]
      (nonblock! r) (nonblock! w)
      (swap! pstate assoc :fds {} :pending {} :pipe [r w] :kq kq :started? true :cancelled #{} :threads {})
      (kevent-put! ch 0 r @(resolve 'jolt.io-poller/EVFILT-READ) @(resolve 'jolt.io-poller/EV-ADD))
      (jolt.io-poller/c-kevent kq ch 1 ffi/null 0 ffi/null)
      {:kq kq :pipe [r w]})))

(def ^:private to-delete (volatile! #{}))

(defn- drain! [] (scm/eval-string "(sa-fiber-run-all)"))

(defn- queue-empty? [] (scm/eval-string "(not (jolt-carrier-head (vector-ref jolt-fiber-carriers 0)))"))

(defn- io-round!
  "One poller round on this thread: block in kevent until a registered fd is ready, then
  resume its waiters onto carrier 0's queue."
  []
  (let [evs (poller-round (:kq @pstate) @to-delete)]
    (cond (nil? evs) nil
          (empty? evs) (vreset! to-delete #{})
          :else (let [[woken nd] (process-events! evs)]
                  (vreset! to-delete nd)
                  (doseq [f woken] (jolt.host/fiber-resume f))))))

(defn run-until
  "Run this process's fibers on this thread until (done?) is true: drain the run queue, and
  when it is empty wait for I/O readiness. Answers the rounds of I/O it waited."
  [done?]
  (loop [rounds 0]
    (drain!)
    (cond (done?) rounds
          (not (queue-empty?)) (recur rounds)
          ;; nothing queued and no fd anyone waits on: nothing can ever wake a fiber here
          (and (empty? (:fds @pstate)) (empty? (:pending @pstate))) {:stuck rounds}
          :else (do (io-round!) (recur (inc rounds))))))

(defn take!!
  "<!! on the main thread: the value, running fibers and I/O until ch has one."
  [ch]
  (let [box (volatile! ::none)]
    (a/take! ch #(vreset! box %))
    (run-until #(not= ::none @box))
    @box))
