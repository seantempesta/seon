(ns seon.retry-test
  "The composable async-retry primitive (seon.retry):

     - the PURE strategy combinators are lazy delay-seqs that compose:
       multiplicative (exponential), randomize (jitter), clamp-delay
       (per-wait ceiling), max-retries (count bound), max-duration (total
       backoff ceiling)
     - the ^:async executor [[with-retry!]] runs an errors-as-values thunk
       against a strategy: a TRANSIENT result (per the `:seon.retry/retry?`
       predicate) is retried until the strategy runs out; a non-retryable
       result is surfaced on the FIRST attempt; on exhaustion the LAST
       (error) result flows through; `:seon.retry/override` lets a result
       dictate the next delay (Retry-After).

   Pure-path: the thunk is a counting stub returning canned maps — no
   network, no conn. Delays in tests are tiny (≤ a few ms) so the suite
   stays fast."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.ai]
    [seon.retry :as retry]))

;; ============================================================
;; Pure combinators — strategy = a seq of delays (ms).
;; ============================================================

(deftest multiplicative-is-exponential
  (is (= [500 1000 2000 4000 8000]
         (take 5 (retry/multiplicative-strategy 500 2)))))

(deftest additive-is-linear
  (is (= [100 250 400 550]
         (take 4 (retry/additive-strategy 100 150)))))

(deftest clamp-and-max-retries-compose
  (is (= [500 1000 2000 2000]
         (vec (-> (retry/multiplicative-strategy 500 2)
                  (retry/clamp-delay 2000)
                  (retry/max-retries 4))))))

(deftest max-duration-stops-on-cumulative-cap
  ;; cumulative-after-each: 500, 1500, 3500, 7500 — a 4000ms cap keeps
  ;; the first three (cum ≤ 4000), drops the 4000 delay (cum 7500 > 4000).
  (is (= [500 1000 2000]
         (vec (-> (retry/multiplicative-strategy 500 2)
                  (retry/max-retries 9)
                  (retry/max-duration 4000))))))

(deftest randomize-stays-within-band
  ;; ± 0.5 jitter on a constant 1000 ⇒ every delay in [500, 1500].
  (let [ds (take 50 (retry/randomize-strategy (retry/constant-strategy 1000) 0.5))]
    (is (every? #(<= 500 % 1500) ds))
    (is (every? int? ds))))

;; ============================================================
;; Executor — with-retry! over an errors-as-values thunk.
;; ============================================================

(defn- retryable?
  "Mirrors seon.agent.turn/llm-retryable?: transport | 429 | 5xx."
  [r]
  (let [e (:seon.ai/error r) s (:seon.ai/status e)]
    (boolean
      (and e (or (true? (:seon.ai/transport? e))
                 (= 429 s)
                 (and (int? s) (<= 500 s 599)))))))

(defn- counting-thunk
  "A thunk returning `resps` in order (last repeats); `!n` counts calls."
  [!n resps]
  (fn [] (let [i (swap! !n inc)]
           (js/Promise.resolve (nth resps (dec i) (last resps))))))

;; tiny delays — the executor mechanics, not real backoff timing.
(def ^:private test-strategy (retry/max-retries (retry/constant-strategy 1) 4))

(def ok    {:text "hi"})
(def e429  {:text "" :seon.ai/error {:seon.ai/msg "HTTP 429" :seon.ai/status 429}})
(def e503  {:text "" :seon.ai/error {:seon.ai/msg "HTTP 503" :seon.ai/status 503}})
(def e400  {:text "" :seon.ai/error {:seon.ai/msg "HTTP 400" :seon.ai/status 400}})
(def etrans {:text "" :seon.ai/error {:seon.ai/msg "fetch failed" :seon.ai/transport? true}})

(defn- drive!
  ([resps] (drive! resps nil))
  ([resps override]
   (let [!n (atom 0)]
     (-> (retry/with-retry!
           (cond-> {:seon.retry/thunk    (counting-thunk !n resps)
                    :seon.retry/strategy test-strategy
                    :seon.retry/retry?   retryable?}
             override (assoc :seon.retry/override override)))
         (.then (fn [res] (assoc res :seon.retry/calls @!n)))))))

(deftest rate-limit-then-success
  (async done
    (-> (drive! [e429 ok])
        (.then (fn [{:seon.retry/keys [result retries calls]}]
                 (testing "429 then 200 → recover, exactly one retry"
                   (is (= 2 calls))
                   (is (= 1 retries))
                   (is (= ok result))
                   (is (nil? (:seon.ai/error result))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest server-error-then-success
  (async done
    (-> (drive! [e503 ok])
        (.then (fn [{:seon.retry/keys [result retries calls]}]
                 (is (= 2 calls))
                 (is (= 1 retries))
                 (is (= ok result))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest transport-error-then-success
  (async done
    (-> (drive! [etrans ok])
        (.then (fn [{:seon.retry/keys [result retries calls]}]
                 (is (= 2 calls))
                 (is (= 1 retries))
                 (is (= ok result))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest client-error-never-retries
  (async done
    (-> (drive! [e400])
        (.then (fn [{:seon.retry/keys [result retries calls]}]
                 (testing "400 is a real error — surfaced on the FIRST attempt"
                   (is (= 1 calls))
                   (is (= 0 retries))
                   (is (= 400 (:seon.ai/status (:seon.ai/error result)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest exhaustion-surfaces-last-error
  (async done
    ;; persistent 503 — capped at max-retries 4 ⇒ 5 calls, then the
    ;; (error) resp flows through unchanged (errors-as-values, no throw).
    (-> (drive! [e503])
        (.then (fn [{:seon.retry/keys [result retries calls]}]
                 (is (= 5 calls) "1 initial + 4 retries (the strategy bound)")
                 (is (= 4 retries))
                 (is (= 503 (:seon.ai/status (:seon.ai/error result))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest retry-after-override-dictates-the-delay
  (async done
    ;; The override maps a result → the next delay (ms). Here it returns a
    ;; tiny clamped value from a Retry-After; we assert the executor USED
    ;; it (the on-retry hook captures the delay it actually waited).
    (let [!delays (atom [])
          !n      (atom 0)
          e429+ra {:text "" :seon.ai/error {:seon.ai/msg "429"
                                            :seon.ai/status 429
                                            :seon.ai/retry-after-ms 3}}]
      (-> (retry/with-retry!
            {:seon.retry/thunk    (counting-thunk !n [e429+ra ok])
             :seon.retry/strategy (retry/max-retries (retry/constant-strategy 9999) 4)
             :seon.retry/retry?   retryable?
             :seon.retry/override (fn [r] (some-> (get-in r [:seon.ai/error
                                                             :seon.ai/retry-after-ms])
                                                  (min 50)))
             :seon.retry/on-retry (fn [{:seon.retry/keys [delay-ms]}]
                                    (swap! !delays conj delay-ms))})
          (.then (fn [{:seon.retry/keys [retries]}]
                   (testing "the wait came from Retry-After (3ms), NOT the 9999ms strategy"
                     (is (= 1 retries))
                     (is (= [3] @!delays)))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ============================================================
;; Retry-After parsing (seon.ai/parse-retry-after-ms).
;; ============================================================

(deftest parse-retry-after-seconds
  (is (= 30000 (seon.ai/parse-retry-after-ms "30")))
  (is (= 0 (seon.ai/parse-retry-after-ms "0"))))

(deftest parse-retry-after-blank-or-nil
  (is (nil? (seon.ai/parse-retry-after-ms nil)))
  (is (nil? (seon.ai/parse-retry-after-ms "")))
  (is (nil? (seon.ai/parse-retry-after-ms "   "))))

(deftest parse-retry-after-http-date
  ;; an HTTP-date ~10s in the future resolves to a non-negative ms delay.
  (let [future (js/Date. (+ (js/Date.now) 10000))
        ms     (seon.ai/parse-retry-after-ms (.toUTCString future))]
    (is (number? ms))
    (is (<= 0 ms 11000))))
