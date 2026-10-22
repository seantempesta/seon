(ns seon.intr
  "Copied from session R2's r2.intr (../notes/archive/option3-R2-nrepl.md), which replaces T1's throwing
  handler: T1's never stopped a form that catches Throwable and left a binding behind.

  Stopping a running eval in a single-threaded agent with Jolt's own mechanism,
  `jolt.host/run-interruptible`: by root's interrupt (a signal), or by the work the form
  did (its tick budget: procedure calls and loop back-edges, never elapsed time; owner,
  2026-10-21: no clocks or timers).

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
  (:require [seon.contract :as contract :refer [defn defn- defchecked]] [jolt.scheme :as scm] [seon.lib :as lib]))

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

;; root's interrupt that found no eval running: between two forms of a request, the next
;; bounded eval starts stopped; idle, it is cleared by the next request (seon.repl/eval-code).
(defonce ^:private pending (atom false))
(defn clear-pending! {:malli/schema [:=> [:cat] :boolean]}
  [] (reset! pending false))

(defn- on-usr1 {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [es @stack]
    (if (seq es)
      (let [newly (boolean (some true? (mapv #(set-token! % :interrupted) es)))]
        ;; a form blocked in a wait (a channel take) reaches no tick: the thread's own
        ;; interrupt flag, read by the wait when the signal wakes it, ends it as
        ;; Thread.interrupt ends a blocked <!! (cleared when the eval ends, in run)
        (.interrupt (Thread/currentThread))
        newly)
      (do (reset! pending true) false))))

(defchecked ^:private install-kick* [:=> [:cat :int :seon.host/fn] :any]
  (scm/eval-string
    "(lambda (sig f) (register-signal-handler sig (lambda (s) (when (eq? #t (f)) (set-timer 1)))))"))

;; Outside any run-interruptible the engine timer has Chez's default handler, which
;; raises; a kick that arrives just outside one (a token set between push and entry)
;; must do nothing there. An agent runs no fibers, so no scheduler handler is replaced.
(defchecked ^:private quiet-timer* [:=> [:cat] :any] (scm/eval-string "(lambda () (timer-interrupt-handler (lambda () (void))))"))

(defn install!
  "Register root's interrupt handler (a forked child inherits it): it arms the engine timer
  for one tick when a token was newly set, so the stop merges at once."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (quiet-timer*)
  (install-kick* lib/SIGUSR1 on-usr1)
  true)

(defn exit-at-eof!
  "End this process when its socket to root is closed, whatever it is doing: SIGIO (the
  socket asked for it, seon.sock/signal-at-input!) is taken at the next safe point, as
  root's SIGUSR1 is, and the process exits 0 only when (gone?) peeks EOF; input arriving is
  the loop's to read. The second source of an interrupt here, beside root's (task
  seon.agent/an-agent-ends-with-its-root)."
  {:malli/schema [:=> [:cat :seon.host/fn] :boolean]}
  [gone?]
  (install-kick* lib/SIGIO (fn [] (when (gone?) (lib/c-exit 0)) false))
  true)

(defn run
  "(thunk) under its own token, stopped by root's SIGUSR1 or once it has run ticks engine
  ticks (nil: no budget). Throws ex-info {:interrupted true} when root stopped it, or the
  typed :seon.repl/ticks-spent {:ticks n} when its budget ran out."
  {:malli/schema [:=> [:cat [:maybe pos-int?] :seon.host/fn] :any]}
  [ticks thunk]
  ;; an enclosing eval already stopped, or root's interrupt is pending: this one does not start
  (when (or @pending (some #(deref (:cause %)) @stack))
    (throw (ex-info "interrupted by root" {:interrupted true})))
  (let [tok (jolt.host/make-interrupt)
        e {:token tok :cause (volatile! nil)}]
    (swap! stack conj e)
    (try (if ticks (jolt.host/run-interruptible tok thunk ticks) (jolt.host/run-interruptible tok thunk))
         (catch Throwable ex
           (let [d (ex-data ex)]
             (cond
               (:jolt/ticks-spent d)
               (throw (ex-info "the form spent its tick budget"
                               (contract/error :seon.repl/ticks-spent 'seon.repl/eval-code
                                               (str "the form ran " (:jolt/ticks-spent d) " engine ticks (procedure calls and loop"
                                                    " back-edges), its budget of " ticks ", and was stopped: a loop that never ends,"
                                                    " or more work than one form should do")
                                               {:ticks (:jolt/ticks-spent d) :budget ticks})))
               ;; root's interrupt, whichever way it ended the form: the escape, or a
               ;; blocked wait thrown out by the thread's interrupt flag
               (or (:jolt/interrupted d) (= :interrupted @(:cause e))) (throw (ex-info "interrupted by root" {:interrupted true}))
               :else (throw ex))))
         (finally
           (swap! stack (fn [s] (doall (remove #(identical? % e) s))))
           ;; the flag on-usr1 set is this eval's: never the next request's
           (Thread/interrupted)))))
