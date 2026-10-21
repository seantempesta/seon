(ns seon.intr
  "Copied from lane R2's r2.intr (notes/option3-R2-nrepl.md), which replaces T1's throwing
  handler: T1's never stopped a form that catches Throwable and left a binding behind.

  Stopping a running eval in a single-threaded agent with Jolt's own mechanism,
  `jolt.host/run-interruptible`, driven by signals.

  Each bounded eval runs under its own interrupt token (`jolt.host/make-interrupt`)
  and pushes {:token :deadline} on this process's stack. Two signal handlers set
  tokens; neither throws:
    SIGUSR1  root's interrupt: sets every token on the stack (the whole request
             stops, nested evals included);
    SIGALRM  a deadline: sets the tokens whose deadline passed and re-arms one
             ITIMER_REAL for the next earliest deadline.
  A handler that set a token also arms Chez's engine timer for one tick, so
  run-interruptible's own timer handler runs at once instead of at its next
  100,000-tick poll. That handler escapes by continuation: the body's own
  try/catch cannot swallow the interrupt, and run-interruptible restores the
  dynamic-binding stack and refuses to escape while a counted lock is held.
  A signal also ends a blocking foreign call with EINTR, so both reach FFI waits.

  Conditions (T1's): the process descends from Chez's main thread on every fork
  (else the signal is lost); every wait loop retries EINTR."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn- defchecked]] [jolt.scheme :as scm] [jolt.ffi :as ffi] [seon.lib :as lib]))

(ffi/load-library)
(lib/defc c-setitimer "setitimer" [:int :pointer :pointer] :int)
(lib/defc c-clock-gettime-nsec "clock_gettime_nsec_np" [:int] :uint64)

(defn now-ns {:malli/schema [:=> [:cat] :int]}
  [] (c-clock-gettime-nsec 8)) ; CLOCK_UPTIME_RAW, as T1's harness reads it

(def SIGUSR1 lib/SIGUSR1)
(def SIGALRM 14)

;; The live bounded evals of this process, innermost first. The signal handlers run
;; on this (the only) thread at a Chez safe point and must find them; this is the
;; process's one interrupt cell.
(defonce ^:private stack (atom ()))

(defn- set-token!
  "Set e's token for cause: true when it was not set before."
  {:malli/schema [:=> [:cat :map :keyword] :boolean]}
  [e cause]
  (if (jolt.host/interrupted? (:token e))
    false
    (do (vreset! (:cause e) cause)
        (jolt.host/interrupt! (:token e))
        true)))

(def ^:private itv (ffi/alloc 32))

(defn- arm-alarm!
  "One ITIMER_REAL for the earliest pending deadline on the stack, or disarmed."
  {:malli/schema [:=> [:cat] :int]}
  []
  (let [dl (some->> @stack (remove #(jolt.host/interrupted? (:token %))) (keep :deadline) seq (apply min))
        us (if dl (max 1 (quot (- dl (now-ns)) 1000)) 0)]
    (ffi/write itv :long 0 0) (ffi/write itv :int 0 8)
    (ffi/write itv :long (quot us 1000000) 16) (ffi/write itv :int (int (mod us 1000000)) 24)
    (c-setitimer 0 itv ffi/null)))

;; root's interrupt that found no eval running (between two forms of a request): the
;; next bounded eval starts stopped. Cleared when the agent answers `interrupt`.
(defonce ^:private pending (atom false))
(defn clear-pending! {:malli/schema [:=> [:cat] :boolean]}
  [] (reset! pending false))

(defn- on-usr1 {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [es @stack]
    (if (seq es)
      (boolean (some true? (mapv #(set-token! % :interrupted) es)))
      (do (reset! pending true) false))))

(defn- on-alrm {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [t (now-ns)
        due (filter #(some-> (:deadline %) (<= t)) @stack)]
    ;; a timed-out eval stops with everything nested inside it
    (let [set? (if-let [outer (last due)]
                 (boolean (some true? (mapv #(set-token! % :timeout)
                                            (concat (take-while #(not (identical? % outer)) @stack) [outer]))))
                 false)]
      (arm-alarm!)
      set?)))

(defchecked ^:private install-kick* [:=> [:cat :int :seon.host/fn] :any]
  (scm/eval-string
    "(lambda (sig f) (register-signal-handler sig (lambda (s) (when (eq? #t (f)) (set-timer 1)))))"))
;; without the one-tick kick: the token is seen at run-interruptible's next own poll
(defchecked ^:private install-poll* [:=> [:cat :int :seon.host/fn] :any]
  (scm/eval-string "(lambda (sig f) (register-signal-handler sig (lambda (s) (f))))"))

;; Outside any run-interruptible the engine timer has Chez's default handler, which
;; raises; a kick that lands just outside one (a token set between push and entry)
;; must do nothing there. An agent runs no fibers, so no scheduler handler is replaced.
(defchecked ^:private quiet-timer* [:=> [:cat] :any] (scm/eval-string "(lambda () (timer-interrupt-handler (lambda () (void))))"))

(defn install!
  "Register the two handlers (a forked child inherits them). kick? (default true) arms
  the engine timer for one tick when a token was newly set; false leaves it to the poll."
  {:malli/schema [:function [:=> [:cat] :boolean] [:=> [:cat :boolean] :boolean]]}
  ([] (install! true))
  ([kick?]
   (quiet-timer*)
   (let [i (if kick? install-kick* install-poll*)]
     (i SIGUSR1 on-usr1)
     (i SIGALRM on-alrm))
   true))

(defn run
  "(thunk) under its own token, stopped by root's SIGUSR1 or by timeout-ms (nil: none).
  Throws ex-info {:interrupted true} or {:timeout-ms n} when stopped."
  {:malli/schema [:=> [:cat [:maybe :int] :seon.host/fn] :any]}
  [timeout-ms thunk]
  (let [tok (jolt.host/make-interrupt)
        e {:token tok :cause (volatile! nil)
           :deadline (when timeout-ms (+ (now-ns) (* 1000000 (long timeout-ms))))}]
    ;; an enclosing eval already stopped: this one starts stopped too
    (when-let [c (or (some #(deref (:cause %)) @stack) (when @pending :interrupted))] (set-token! e c))
    (swap! stack conj e)
    (when timeout-ms (arm-alarm!))
    (try (jolt.host/run-interruptible tok thunk)
         (catch Throwable ex
           (if (:jolt/interrupted (ex-data ex))
             (throw (if (= :timeout @(:cause e))
                      (ex-info (str "timed out after " timeout-ms " ms") {:timeout-ms timeout-ms})
                      (ex-info "interrupted by root" {:interrupted true})))
             (throw ex)))
         (finally
           (swap! stack (fn [s] (doall (remove #(identical? % e) s))))
           (when timeout-ms (arm-alarm!))))))
