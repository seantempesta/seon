(ns seon.retry
  "Composable async retry — a general resilience primitive (NOT
   LLM-specific). A STRATEGY is a lazy seq of delays in milliseconds; the
   number of ATTEMPTS is `(inc (count strategy))` (the seq is the waits
   BETWEEN attempts). The pure builder/manipulator combinators below
   (strategy-as-seq, the design of the JVM `again` lib ported to native
   CLJS) compose with `->`:

     (-> (multiplicative-strategy 500 2)  ; 500, 1000, 2000, …
         (randomize-strategy 0.5)         ; ± jitter
         (clamp-delay 20000)              ; per-wait ceiling
         (max-retries 4)                  ; at most 4 retries
         (max-duration 60000))            ; total backoff ceiling

   The `^:async` executor [[with-retry!]] runs a thunk against a strategy,
   awaiting a `js/Promise`-based delay between attempts (core.async-free —
   the pod's native async model). The thunk returns errors AS VALUES
   (never throws); a per-result `:seon.retry/retry?` predicate decides
   whether a result is worth retrying, so the executor is reusable for any
   errors-as-values async call. A `:seon.retry/override` hook lets a
   result dictate the next delay (e.g. honoring an HTTP `Retry-After`).

   WHY a port, not the lib: the `again` lib (reference-code/again) IS the
   proven design here, but it is JVM-ONLY — its executor `with-retries*`
   blocks on `Thread/sleep` and catches thrown `Exception`s, neither of
   which exists in the pod (native async/await; errors-are-values). So the
   PURE combinators (platform-agnostic ~40 lines) are ported faithfully
   and only the executor is rewritten async + errors-as-values. The one
   deliberate divergence: our manipulators take the STRATEGY FIRST (`->`
   threading) where again takes it LAST (`->>`).

   FUTURE (not built — `again` also has it): a circuit-breaker
   (consecutive-failure trip + half-open probe) would compose as another
   manipulator; add it here when a caller needs it."
  (:require [seon.schema :as schema]))

;;; ============================================================
;;; STRATEGY shape — a (possibly infinite) seq of delays in ms. Specced
;;; as `sequential?` so instrumentation NEVER realizes an infinite
;;; builder (a `:sequential` walk would hang on `(iterate …)`).
;;; ============================================================

(schema/register! :seon.retry/strategy 'sequential?)
(schema/register! :seon.retry/delay-ms :int)
;; a growth factor (2, 1.5, …) or jitter fraction (0.0–1.0) — a number,
;; not necessarily integral, so `:int` would be too tight.
(schema/register! :seon.retry/factor 'number?)
(schema/register! :seon.retry/jitter 'number?)
(schema/register! :seon.retry/count  :int)
(schema/register! :seon.retry/attempt :int)
;; a callable slot (thunk / predicate / hook) at a third-party-ish
;; boundary — a bare fn value, validated as `fn?`.
(schema/register! :seon.retry/callable 'fn?)

;;; ============================================================
;;; BUILDERS — seed a strategy.
;;; ============================================================

(defn constant-strategy
  "An infinite strategy of a CONSTANT delay (ms)."
  {:malli/schema [:=> [:catn [:seon.retry/delay-ms :seon.retry/delay-ms]]
                  :seon.retry/strategy]}
  [delay-ms]
  (repeat delay-ms))

(defn additive-strategy
  "An infinite strategy from `initial-ms`, linear backoff.

   Grows by `increment-ms` each step."
  {:malli/schema [:=> [:catn [:seon.retry/initial-ms :seon.retry/delay-ms]
                       [:seon.retry/increment-ms :seon.retry/delay-ms]]
                  :seon.retry/strategy]}
  [initial-ms increment-ms]
  (iterate #(+ % increment-ms) initial-ms))

(defn multiplicative-strategy
  "An infinite strategy from `initial-ms`, exponential backoff.

   Multiplied by `factor` each step (500, 1000, 2000, … for factor 2)."
  {:malli/schema [:=> [:catn [:seon.retry/initial-ms :seon.retry/delay-ms]
                       [:seon.retry/factor :seon.retry/factor]]
                  :seon.retry/strategy]}
  [initial-ms factor]
  (iterate #(* % factor) initial-ms))

;;; ============================================================
;;; MANIPULATORS — compose over a strategy (strategy in, strategy out).
;;; ============================================================

(defn- jitter-ms
  "`delay-ms` perturbed symmetrically by ± `factor` (a fraction), clamped
   non-negative and rounded to an int."
  [delay-ms factor]
  (let [span (* delay-ms factor)
        d    (- (* 2 span (js/Math.random)) span)]
    (max 0 (js/Math.round (+ delay-ms d)))))

(defn randomize-strategy
  "Jitter each delay by ± `factor` (0.0–1.0).

   Spreads retries so a fleet doesn't thunder-herd a recovering provider."
  {:malli/schema [:=> [:catn [:seon.retry/strategy :seon.retry/strategy]
                       [:seon.retry/jitter :seon.retry/jitter]]
                  :seon.retry/strategy]}
  [strategy factor]
  (map #(jitter-ms % factor) strategy))

(defn clamp-delay
  "Cap each individual delay at `max-ms` (a per-wait ceiling)."
  {:malli/schema [:=> [:catn [:seon.retry/strategy :seon.retry/strategy]
                       [:seon.retry/max-ms :seon.retry/delay-ms]]
                  :seon.retry/strategy]}
  [strategy max-ms]
  (map #(min % max-ms) strategy))

(defn max-retries
  "Bound the strategy to at most `n` retries (`take n`)."
  {:malli/schema [:=> [:catn [:seon.retry/strategy :seon.retry/strategy]
                       [:seon.retry/count :seon.retry/count]]
                  :seon.retry/strategy]}
  [strategy n]
  (take n strategy))

(defn max-duration
  "Keep delays while their CUMULATIVE sum stays within `total-ms`.

   A total-backoff ceiling so the executor never waits longer than this in
   aggregate (the run loop never hangs)."
  {:malli/schema [:=> [:catn [:seon.retry/strategy :seon.retry/strategy]
                       [:seon.retry/total-ms :seon.retry/delay-ms]]
                  :seon.retry/strategy]}
  [strategy total-ms]
  (map first
       (take-while (fn [[_ cum]] (<= cum total-ms))
                   (map vector strategy (rest (reductions + 0 strategy))))))

;;; ============================================================
;;; EXECUTOR — run a thunk against a strategy, ^:async.
;;; ============================================================

(defn ^:async ^:private sleep!
  "A `js/Promise` that resolves after `ms` — the non-blocking backoff wait
   (no core.async, no blocking sleep)."
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(schema/register! :seon.retry/with-retry-request
  [:map
   ;; () -> Promise<result> — the work to (re)attempt.
   [:seon.retry/thunk    :seon.retry/callable]
   ;; the delay seq; (inc (count strategy)) = max attempts.
   [:seon.retry/strategy :seon.retry/strategy]
   ;; (result) -> boolean — retry this result? (errors-are-values).
   [:seon.retry/retry?   :seon.retry/callable]
   ;; (result) -> ms|nil — override the next delay from the result
   ;; (e.g. a server Retry-After). nil ⇒ use the strategy's delay.
   [:seon.retry/override {:optional true} :seon.retry/callable]
   ;; (info-map) -> any — side-effecting hook fired before each wait
   ;; (logging); receives {:seon.retry/attempt :seon.retry/delay-ms
   ;; :seon.retry/result}.
   [:seon.retry/on-retry {:optional true} :seon.retry/callable]])

(schema/register! :seon.retry/with-retry-response
  [:map
   ;; the LAST thunk result — a success, or the final (exhausted) error.
   [:seon.retry/result  :any]
   ;; how many retries actually fired (0 = first attempt succeeded / was
   ;; non-retryable).
   [:seon.retry/retries :seon.retry/count]])

(defn ^:async with-retry!
  "Run `:seon.retry/thunk` against `:seon.retry/strategy`, with retries.

   `:seon.retry/thunk` is a `() -> Promise<result>`. Retries while
   `:seon.retry/retry?` says the
   result is transient AND the strategy has delays left. Errors are
   VALUES: the thunk never throws, and on exhaustion (or a non-retryable
   result) the LAST result is returned as-is — the caller surfaces its
   error shape. `:seon.retry/override`, when supplied, maps a result to a
   millisecond delay that REPLACES the strategy's next delay (e.g. an HTTP
   `Retry-After`). Returns `{:seon.retry/result … :seon.retry/retries n}`."
  {:malli/schema [:=> [:cat :seon.retry/with-retry-request]
                  :seon.retry/with-retry-response]}
  [{:seon.retry/keys [thunk strategy retry? override on-retry]}]
  (loop [delays  (seq strategy)
         retries 0]
    (let [result (await (thunk))]
      (if (or (nil? delays) (not (retry? result)))
        {:seon.retry/result result :seon.retry/retries retries}
        (let [wait (max 0 (or (and override (override result))
                              (first delays)))]
          (when on-retry
            (on-retry {:seon.retry/attempt  (inc retries)
                       :seon.retry/delay-ms wait
                       :seon.retry/result   result}))
          (await (sleep! wait))
          (recur (next delays) (inc retries)))))))
