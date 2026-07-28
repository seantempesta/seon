(ns seon.sci.eval-test
  "Acceptance for the guarded eval (N3, C7).

  DRAFT FOR ORCHESTRATOR SEAL REVIEW (drafted 2026-07-27). Every test
  runs a REAL sci evaluation with a REAL armed boundary — there is no
  fake interrupt-fn here, because the one thing worth proving is that
  the mechanism stops what it claims to stop.

  The deadlines are short (a few hundred ms) and the runaway cases are
  genuinely unbounded, so a regression does not slow the suite: it
  fails it."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- run
  ([source] (run source 2000))
  ([source time-limit-ms]
   (eval/evaluate {:seon.cluster.run.form/source source
                   :seon.sci.admit/caps caps
                   :seon.sci.eval/time-limit-ms time-limit-ms
                   ;; development disposition: a codec hole must be loud
                   ;; here of all places
                   :seon.config/on-core-error :panic})))

(defn- deadlined
  "Evaluate on another thread so a runaway FAILS the suite rather than
  hanging it — the guard being tested is exactly the one that should
  make this unnecessary."
  [source time-limit-ms]
  (let [task (future (run source time-limit-ms))]
    (or (deref task 10000 nil)
        (do (future-cancel task) ::hung))))

;;; PRESENCE IS THE STATE (owner ruling 2026-07-28): there is no
;;; status enum on an evaluation. These three disjoint readers ARE the
;;; state model this suite asserts.

(defn- cut?
  "The time limit fired: the evaluation carries its cut instant."
  [evaluation]
  (some? (:seon.cluster.eval/interrupted-at evaluation)))

(defn- failed?
  "The form failed on its own: an error with no cut instant."
  [evaluation]
  (and (some? (:seon.cluster.eval/error evaluation))
       (not (cut? evaluation))))

(defn- ok?
  "The form produced a value: no error and no cut instant."
  [evaluation]
  (and (nil? (:seon.cluster.eval/error evaluation))
       (not (cut? evaluation))))

;;; ---------------------------------------------------------------------------
;;; The ordinary path
;;; ---------------------------------------------------------------------------

(deftest the-request-is-what-the-contract-says-it-is
  ;; the dial is REQUIRED, so a caller cannot forget to decide
  (is (seon.schema/valid-candidate-value?
       :seon.sci.eval/request
       {:seon.cluster.run.form/source "(+ 1 1)"
        :seon.sci.admit/caps caps
        :seon.sci.eval/time-limit-ms 1000
        :seon.config/on-core-error :panic}))
  (is (not (seon.schema/valid-candidate-value?
            :seon.sci.eval/request
            {:seon.cluster.run.form/source "(+ 1 1)"
             :seon.sci.admit/caps caps
             :seon.sci.eval/time-limit-ms 1000}))
      "no dial, no evaluation"))

(deftest the-diagnostics-are-recorded-and-are-not-limits
  (let [evaluation (run "(reduce + (map inc (range 500)))")
        record (:seon.sci.admit/record evaluation)]
    (is (ok? evaluation))
    (is (= 125250 (:seon.sci.admit/value evaluation)))
    (testing "fn-entries counted the interpreted work"
      (is (pos? (:seon.eval/fn-entries record))))
    (is (= :ok (:seon.eval/outcome record)))
    (is (int? (:seon.eval/duration-ms record)))
    (is (int? (:seon.eval/allocated-bytes record))
        "-1 is honest when the platform cannot measure; nil is not")))

(deftest a-fork-per-evaluation-means-no-leakage
  (run "(def leaked 1)")
  (let [evaluation (run "leaked")]
    (is (failed? evaluation)
        "one evaluation's def cannot reach the next")
    (is (= :seon.sci.eval/evaluation-failed
           (:seon.error/kind (:seon.sci.admit/value evaluation))))))

(deftest the-dispositions-are-callable-and-come-back-as-values
  (let [evaluation (run "(my.run/complete \"done\")")]
    (is (ok? evaluation))
    (is (= {:my.run/disposition :completed :my.run/result "done"}
           (:seon.sci.admit/value evaluation))
        "the loop reads its disposition out of exactly this"))
  (let [evaluation (run "(my.run/wait \"later\")")]
    (is (= :wait (:my.run/disposition (:seon.sci.admit/value evaluation))))))

;;; ---------------------------------------------------------------------------
;;; The armed boundary — time is the only limit
;;; ---------------------------------------------------------------------------

(deftest an-interpreted-infinite-loop-dies-at-the-limit
  (let [started (System/nanoTime)
        evaluation (deadlined "(loop [] (recur))" 300)
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
    (is (not= ::hung evaluation) "the limit is the limit")
    (is (cut? evaluation))
    (is (inst? (:seon.cluster.eval/interrupted-at evaluation))
        "the cut instant is the one fact — presence is the state")
    (is (= :time (:seon.eval/outcome (:seon.sci.admit/record evaluation))))
    (is (< elapsed-ms 5000)
        (str "died in " elapsed-ms "ms — the deadline, not luck"))
    (testing "and the agent is told what happened, as a value"
      (is (= :seon.sci.eval/time-limit
             (:seon.error/kind (:seon.sci.admit/value evaluation))))
      (is (re-find #"(?i)time"
                   (:seon.cluster.eval/error evaluation))))))

(deftest an-agent-cannot-catch-the-interrupt
  ;; sci's try refuses to hand the interrupt to a user catch clause, and
  ;; sandboxed code cannot forge the marker
  (let [evaluation (deadlined
                    "(try (loop [] (recur)) (catch Throwable _ :swallowed))"
                    300)]
    (is (not= ::hung evaluation))
    (is (cut? evaluation))
    (is (not= :swallowed (:seon.sci.admit/value evaluation)))))

(def ^:private ordinary-source-value-generator
  (gen/one-of
   [gen/small-integer
    gen/boolean
    gen/string-alphanumeric
    gen/keyword
    (gen/return nil)
    (gen/vector gen/small-integer 0 8)]))

(def ^:private failing-source-generator
  (gen/elements
   ["(throw (ex-info \"x\" {:probe true}))"
    "(/ 1 0)"
    "(no-such-fn 1)"
    "(recur)"
    "#{"
    "(let [x])"
    "#foo/bar [1]"
    "#=(System/exit 1)"
    "(java.io.File. \"/etc/passwd\")"]))

(deftest generated-sources-compose-fork-guard-and-admission
  (let [check
        (tc/quick-check
         100
         (prop/for-all
          [ordinary ordinary-source-value-generator
           failing-source failing-source-generator]
          (let [ordinary-evaluation (deadlined (pr-str ordinary) 300)
                failed-evaluation (deadlined failing-source 300)
                evaluations [ordinary-evaluation failed-evaluation]]
            (and
             (ok? ordinary-evaluation)
             (= ordinary (:seon.sci.admit/value ordinary-evaluation))
             (failed? failed-evaluation)
             (every?
              (fn [evaluation]
                (and
                 (not= ::hung evaluation)
                 (map? evaluation)
                 ;; Presence is the state: exactly one of these facts
                 ;; describes every completed guarded composition.
                 (= 1 (count (filter true?
                                     [(ok? evaluation)
                                      (failed? evaluation)
                                      (cut? evaluation)])))
                 (string? (:seon.cluster.eval/result-edn evaluation))
                 (do (edn/read-string
                      (:seon.cluster.eval/result-edn evaluation))
                     true)
                 (seon.schema/valid-candidate-value?
                  :seon.sci.eval/evaluation evaluation)))
              evaluations))))
         :seed 202607280802)]
    (test-support/assert-check! check
                                "Guarded evaluation composition failed.")))

;;; ---------------------------------------------------------------------------
;;; The honest ceiling — stated, not papered over
;;; ---------------------------------------------------------------------------

(deftest a-blocking-host-call-is-NOT-stopped-by-the-time-limit
  ;; Found by the totality property above, and it is not a defect: the
  ;; interrupt-fn fires on interpreted fn body entrances, and a thread
  ;; parked inside a HOST call never enters one. sci says so itself
  ;; (reference-code/sci/doc/interrupt.md, closing note: for hard
  ;; guarantees run untrusted code in a separate process).
  ;;
  ;; This test exists so the ceiling is a KNOWN, RECURRING fact rather
  ;; than a docstring claim: what covers this case is the caller's
  ;; submission backstop (whose firing IS a bug report, n3-plan §4.4)
  ;; and the process boundary — never this deadline.
  (let [task (future (run "(deref (promise))" 200))
        outcome (deref task 1500 ::still-running)]
    (is (= ::still-running outcome)
        "the time limit did NOT stop it — if this ever passes by
         returning, the mechanism changed and the ceiling moved")
    (future-cancel task)))

;;; ---------------------------------------------------------------------------
;;; The single owner of the interrupt question
;;; ---------------------------------------------------------------------------

(deftest interrupted?-recognises-only-the-real-marker
  (is (false? (eval/interrupted? (ex-info "ordinary" {}))))
  (is (false? (eval/interrupted? (RuntimeException. "ordinary"))))
  (is (true? (eval/interrupted? (ex-info "shaped" {:sci.impl/interrupt false})))
      "PRESENCE is the whole test, not the value: sandboxed code cannot
       set this key at all, so anything carrying it came from sci")
  (is (true? (eval/interrupted?
              (try ((requiring-resolve 'sci.interrupt/interrupt!) "x")
                   (catch Throwable failure failure))))))
