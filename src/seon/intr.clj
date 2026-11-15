(ns seon.intr
  "Copied from session R2's r2.intr (the session design papers in git history), which replaces T1's throwing
  handler: T1's never stopped a form that catches Throwable and left a binding behind.

  Stopping a running eval in a single-threaded agent with Jolt's own mechanism,
  `jolt.host/run-interruptible`: by root's interrupt (a signal), or by the work the form
  did (its tick budget: procedure calls and loop back-edges, never elapsed time; DECISIONS,
  If nothing has changed, we do nothing; no clock or timer decides (three exceptions)).

  Each eval runs under its own interrupt token (`jolt.host/make-interrupt`) and pushes
  it on this process's stack. SIGUSR1, root's interrupt, sets every token on the stack
  (the whole request stops, nested evals included); its handler does not throw.
  A form past its tick budget is stopped by run-interruptible itself and answers the
  typed :seon.repl/ticks-spent, an error of that form (the request goes on).
  A handler that set a token also arms Chez's engine timer for one tick, so
  run-interruptible's own timer handler runs at once instead of at its next
  100,000-tick poll. That handler escapes by continuation: the body's own
  try/catch cannot swallow the interrupt, and run-interruptible restores the
  dynamic-binding stack and refuses to escape while a counted lock is held.
  A signal also ends a blocking foreign call with EINTR, so both reach FFI waits.

  Conditions (T1's): the process descends from Chez's main thread on every fork
  (else the signal is lost); every wait loop retries EINTR."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn- defchecked]] [jolt.scheme :as scm] [seon.lib :as lib] [seon.profile :as profile] [jolt.io-poller :as poller]))

;; The live bounded evals of this process, innermost first. The signal handlers run
;; on this (the only) thread at a Chez safe point and must find them; this is the
;; process's one interrupt cell.
(defonce ^{:private true :seon.flow/edge "the interrupt stack Chez's interrupt handler reads, outside any step: physics, not a proc's state"} stack (atom ()))

;; The stops stop! made, by the thread whose flag it set ({thread token}): a flag one of them set after its run ended is that stop's, which
;; the thread's next run consumes; any other flag found at a run's start is an interrupt that came between runs, and stops that run.
(defonce ^{:private true :seon.flow/edge "which stop set a thread's interrupt flag, read where the flag is: physics, not a proc's state"} stopped (atom {}))

(defn- set-token!
  "Set e's token for cause: true when it was not set before."
  {:malli/schema [:=> [:cat :map :keyword] :boolean]}
  [e cause]
  (if (jolt.host/interrupted? (:token e))
    false
    (do (vreset! (:cause e) cause)
        (jolt.host/interrupt! (:token e))
        true)))

;; root's interrupt that found no eval running: between two forms of a request, the next
;; bounded eval starts stopped; idle, it is cleared by the next request (seon.repl/eval-code).
(defonce ^{:private true :seon.flow/edge "an interrupt delivered while none is armed, read by Chez's interrupt handler: physics, not a proc's state"} pending (atom false))

(defn interrupt?
  "Is e what ends a step or an eval, never an error of the code it cut: root's interrupt (ThreadDeath) or a Jolt budget's end
  (ex-data :jolt/interrupted, thrown at run-interruptible's frame). A catching edge rethrows it, so the step's bound holds (seon.ratom's
  catching run, task seon.ratom/the-core-is-correct-before-a-reader-moves)."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [e] (or (instance? ThreadDeath e) (boolean (:jolt/interrupted (ex-data e)))))

(defn clear-pending! {:malli/schema [:=> [:cat] :boolean]}
  [] (reset! pending false))

(defn forked!
  "A forked child's interrupt state cleared: none of its parent's evals on its stack, no interrupt of its parent's
  pending, so the child's own evals run (a test child forked by an agent with root's interrupt pending answered every
  eval \"interrupted by root\": task seon.agent/a-test-child-starts-as-a-test-runner-does). true."
  {:malli/schema [:=> [:cat] :boolean]}
  [] (reset! stack ()) (reset! pending false) true)

(defn- on-usr1 {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [es @stack]
    (if (seq es)
      (let [newly (boolean (some true? (mapv #(set-token! % :interrupted) es)))]
        ;; a form blocked in a wait (a channel take) reaches no tick: its thread's own
        ;; interrupt flag, read by the wait when the signal wakes it, ends it as
        ;; Thread.interrupt ends a blocked <!! (cleared when the eval ends, in run). The
        ;; eval's thread, not this handler's: on a main carrier the eval is a fiber, a thread
        ;; of its own, and the handler runs beneath it in the loop
        (run! #(.interrupt ^Thread (:thread %)) es)
        newly)
      (do (reset! pending true) false))))

(defchecked ^:private install-kick* [:=> [:cat :int :seon.host/fn] :any]
  (scm/eval-string
    "(lambda (sig f) (register-signal-handler sig (lambda (s) (when (eq? #t (f)) (set-timer 1)))))"))

;; Outside any run-interruptible the engine timer has Chez's default handler, which
;; raises; a kick that arrives just outside one (a token set between push and entry)
;; must do nothing there. An agent's fibers run on its main carrier, whose drain installs the
;; scheduler's own handler while it runs them.
(defchecked ^:private quiet-timer* [:=> [:cat] :any] (scm/eval-string "(lambda () (timer-interrupt-handler (lambda () (void))))"))

(defn install!
  "Register root's interrupt handler (a forked child inherits it): it arms the engine timer
  for one tick when a token was newly set, so the stop merges at once."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (quiet-timer*)
  (install-kick* lib/SIGUSR1 #(profile/aside on-usr1))
  ;; on a main carrier the loop beneath may be waiting with nothing else to wake it: the signal does
  (when (jolt.host/main-carrier?) (poller/wake-on-signals! [lib/SIGUSR1]))
  true)

(defn exit-at-eof!
  "End this process when its socket to root is closed, whatever it is doing: SIGIO (the
  socket asked for it, seon.sock/signal-at-input!) is taken at the next safe point, as
  root's SIGUSR1 is, and the process exits 0 only when (gone?) peeks EOF; input arriving is
  the loop's to read. The second source of an interrupt here, beside root's. wake? (default true): a main carrier's idle loop also
  waits on the signal. A test child says false: a signal waited on counts as something that can wake the process
  (jolt.io-poller/awaited?), so the loop stays in kevent and Jolt never tells a test that waits for good; left out, a wait nothing
  can end throws its typed deadlock in the test. See docs/merging.md#a-test-child-is-a-fork-of-a-live-agent."
  {:malli/schema [:function [:=> [:cat :seon.host/fn] :boolean] [:=> [:cat :seon.host/fn :boolean] :boolean]]}
  ([gone?] (exit-at-eof! gone? true))
  ([gone? wake?]
   (install-kick* lib/SIGIO (fn [] (profile/aside (fn [] (when (gone?) (lib/c-exit 0)) false))))
   (when (and wake? (jolt.host/main-carrier?)) (poller/wake-on-signals! [lib/SIGIO]))
   true))

(declare run)

(defn- rethrow
  "What a run that threw ex throws: its budget's typed error, a ThreadDeath when root or its asker stopped it (entry e),
  else ex itself."
  {:malli/schema [:=> [:cat :any :map [:maybe pos-int?] [:maybe pos-int?]] :any]}
  [ex e ticks bytes]
  (let [d (ex-data ex)]
    (cond
      (:jolt/bytes-spent d)
      (throw (ex-info "the form spent its byte budget"
                      (contract/error :seon.repl/bytes-spent #'seon.intr/run
                                      (str "the form allocated " (:jolt/bytes-spent d) " bytes, its budget of " bytes
                                           ", and was stopped: a value growing without end, or more data than one form should make")
                                      {:bytes (:jolt/bytes-spent d) :budget bytes})))
      (:jolt/ticks-spent d)
      (throw (ex-info "the form spent its tick budget"
                      (contract/error :seon.repl/ticks-spent #'seon.intr/run
                                      (str "the form ran " (:jolt/ticks-spent d) " engine ticks (procedure calls and loop"
                                           " back-edges), its budget of " ticks ", and was stopped: a loop that never ends,"
                                           " or more work than one form should do")
                                      {:ticks (:jolt/ticks-spent d) :budget ticks})))
      ;; root's interrupt, whichever way it ended the form: the escape, or a
      ;; blocked wait thrown out by the thread's interrupt flag
      (or (:jolt/interrupted d) (= :interrupted @(:cause e))) (throw (ThreadDeath.))
      :else (throw ex))))

(defn run
  "(thunk) under its own token, stopped by root's SIGUSR1, once it has run ticks engine ticks, or
  once it has allocated bytes (nil: no budget of that kind). Throws a java.lang.ThreadDeath (an Error: every (catch Exception e) lets it pass) when
  root stopped it, or the typed :seon.repl/ticks-spent {:ticks n} or :seon.repl/bytes-spent {:bytes
  n} when a budget ran out."
  {:malli/schema [:function [:=> [:cat [:maybe pos-int?] :seon.host/fn] :any]
                  [:=> [:cat [:maybe pos-int?] [:maybe pos-int?] :seon.host/fn] :any]
                  [:=> [:cat [:maybe pos-int?] [:maybe pos-int?] :any :seon.host/fn] :any]]}
  ([ticks thunk] (run ticks nil thunk))
  ([ticks bytes thunk] (run ticks bytes nil thunk))
  ;; tok: the token its asker made (jolt.host/make-interrupt), so the asker can stop it (stop!); nil, one of its own
  ([ticks bytes tok thunk]
  (let [tok (or tok (jolt.host/make-interrupt))
        e {:token tok :cause (volatile! nil) :thread (Thread/currentThread)}
        mine (filter #(= (:thread e) (:thread %)) @stack)]
    ;; an enclosing eval on this thread already stopped, or root's interrupt is pending: this one does not start (another
    ;; thread's stopped run, a server op stop! ended on another worker, is not this one's)
    (when (or @pending (some #(deref (:cause %)) mine))
      (throw (ThreadDeath.)))
    ;; the outermost run on its thread: a flag stop! set after the run before it ended was that stop's (recorded in stopped), and is
    ;; consumed; a flag no stop explains is an interrupt that came between runs, and this run does not start (never cleared unread)
    (when (and (empty? mine) (Thread/interrupted))
      (let [stale (get @stopped (:thread e))]
        (swap! stopped dissoc (:thread e))
        (when-not stale (throw (ThreadDeath.)))))
    (swap! stack conj e)
    (try (if (or ticks bytes) (jolt.host/run-interruptible tok thunk ticks bytes) (jolt.host/run-interruptible tok thunk))
         (catch Throwable ex (rethrow ex e ticks bytes))
         (finally
           (swap! stack (fn [s] (doall (remove #(identical? % e) s))))
           ;; a stop of this run that set its flag while it ran is answered by this run's end (the flag is cleared below)
           (swap! stopped (fn [m] (if (identical? tok (get m (:thread e))) (dissoc m (:thread e)) m)))
           ;; the flag on-usr1 set is this eval's: never the next request's
           (Thread/interrupted))))))

(defn stop!
  "Stop the run under token tok as SIGUSR1 stops all: it and the runs nested on its thread are set and its thread
  interrupted (a blocked wait ends too); a run not yet started stops as it starts. true when the run was live."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [tok]
  (let [es @stack
        e (some #(when (identical? tok (:token %)) %) es)]
    (if e
      (do (run! #(set-token! % :interrupted) (filter #(= (:thread e) (:thread %)) es))
          ;; recorded before the flag is set, so the thread's next run knows the flag for this stop's if this run has ended meanwhile.
          ;; The one window left (the run ends between the two) stops the next run loudly, typed, never silently
          (swap! stopped assoc (:thread e) tok)
          (.interrupt ^Thread (:thread e))
          true)
      (do (jolt.host/interrupt! tok) false))))
