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
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.schema]
            [seon.sci.eval :as eval])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private caps
  {:seon.config.eval.result/max-depth 6
   :seon.config.eval.result/max-collection 8
   :seon.config.eval.result/max-string 64
   :seon.config.eval.result/max-nodes 256})

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

(deftest a-value-comes-back-admitted
  (let [evaluation (run "(+ 1 2)")]
    (is (= :done (:seon.cluster.eval/status evaluation)))
    (is (= 3 (:seon.sci.admit/value evaluation)))
    (is (= "3" (:seon.cluster.eval/result-edn evaluation)))
    (is (false? (:seon.sci.admit/capped? evaluation)))
    (is (seon.schema/valid-candidate-value?
         :seon.sci.eval/evaluation evaluation))))

(deftest the-diagnostics-are-recorded-and-are-not-limits
  (let [evaluation (run "(reduce + (map inc (range 500)))")
        record (:seon.sci.admit/record evaluation)]
    (is (= :done (:seon.cluster.eval/status evaluation)))
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
    (is (= :error (:seon.cluster.eval/status evaluation))
        "one evaluation's def cannot reach the next")
    (is (= :seon.sci.eval/evaluation-failed
           (:seon.error/kind (:seon.sci.admit/value evaluation))))))

(deftest failure-evidence-has-stable-object-markers
  (let [first-run (run "(no-such-fn 1)")
        second-run (run "(no-such-fn 1)")
        first-edn (:seon.cluster.eval/result-edn first-run)
        second-edn (:seon.cluster.eval/result-edn second-run)]
    (doseq [result-edn [first-edn second-edn]]
      (is (some? (edn/read-string result-edn)))
      (is (not (str/includes? result-edn "#object[")))
      (is (not (re-find #"0x[0-9a-f]+" result-edn))))
    (is (= (get-in (:seon.sci.admit/value first-run)
                   [:seon.error/data :seon.sci.eval/data :sci.impl/callstack])
           (get-in (:seon.sci.admit/value second-run)
                   [:seon.error/data :seon.sci.eval/data :sci.impl/callstack]))
        "SCI runtime objects project to stable markers across evaluations")))

(deftest the-dispositions-are-callable-and-come-back-as-values
  (let [evaluation (run "(my.run/complete \"done\")")]
    (is (= :done (:seon.cluster.eval/status evaluation)))
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
    (is (= :interrupted (:seon.cluster.eval/status evaluation)))
    (is (= :time (:seon.eval/outcome (:seon.sci.admit/record evaluation))))
    (is (< elapsed-ms 5000)
        (str "died in " elapsed-ms "ms — the deadline, not luck"))
    (testing "and the agent is told what happened, as a value"
      (is (= :seon.sci.eval/time-limit
             (:seon.error/kind (:seon.sci.admit/value evaluation))))
      (is (re-find #"(?i)time"
                   (:seon.cluster.eval/error evaluation))))))

(deftest an-infinite-lazy-sequence-dies-inside-the-boundary
  ;; the admission seam: realization happens while still armed, so an
  ;; unbounded sequence dies HERE rather than in the receipt writer
  (doseq [source ["(range)"
                  "(iterate inc 0)"
                  "(repeat {:a (range)})"
                  "(map (fn [x] (inc x)) (range))"]]
    (testing source
      (let [evaluation (deadlined source 400)]
        (is (not= ::hung evaluation))
        (is (contains? #{:done :interrupted}
                       (:seon.cluster.eval/status evaluation))
            "either bounded by the caps or stopped by the clock — never
             hung, and never an unrealized tail")
        (when (= :done (:seon.cluster.eval/status evaluation))
          (is (true? (:seon.sci.admit/capped? evaluation))
              "an infinite sequence that returns MUST have been capped")
          (is (vector? (:seon.sci.admit/value evaluation))))))))

(deftest an-agent-cannot-catch-the-interrupt
  ;; sci's try refuses to hand the interrupt to a user catch clause, and
  ;; sandboxed code cannot forge the marker
  (let [evaluation (deadlined
                    "(try (loop [] (recur)) (catch Throwable _ :swallowed))"
                    300)]
    (is (not= ::hung evaluation))
    (is (= :interrupted (:seon.cluster.eval/status evaluation)))
    (is (not= :swallowed (:seon.sci.admit/value evaluation)))))

;;; ---------------------------------------------------------------------------
;;; Nothing throws
;;; ---------------------------------------------------------------------------

(deftest every-failure-is-a-value
  (testing "an agent's own exception"
    (let [evaluation (run "(throw (ex-info \"my mistake\" {:a 1}))")]
      (is (= :error (:seon.cluster.eval/status evaluation)))
      (is (re-find #"my mistake" (:seon.cluster.eval/error evaluation)))
      (is (= :seon.sci.eval/evaluation-failed
             (:seon.error/kind (:seon.sci.admit/value evaluation))))))
  (testing "an unresolvable symbol"
    (is (= :error (:seon.cluster.eval/status (run "(no-such-fn 1)")))))
  (testing "read-eval, refused by sci's own reader inside the armed ctx"
    (let [evaluation (run "#=(System/exit 1)")]
      (is (= :error (:seon.cluster.eval/status evaluation)))
      (is (string? (:seon.cluster.eval/error evaluation)))))
  (testing "an unknown reader tag"
    (is (= :error (:seon.cluster.eval/status (run "#foo/bar [1]")))))
  (testing "unbalanced source"
    (is (= :error (:seon.cluster.eval/status (run "(+ 1")))))
  (testing "a host class the base does not expose"
    (is (= :error (:seon.cluster.eval/status
                   (run "(java.io.File. \"/etc/passwd\")"))))))

(deftest nothing-an-agent-can-write-throws-out-of-evaluate
  (let [check
        (tc/quick-check
         100
         (prop/for-all
          [source (gen/one-of
                   [(gen/fmap pr-str gen/any-printable)
                    (gen/elements
                     ["(throw (Exception. \"x\"))" "(/ 1 0)" "((fn []))"
                      ;; NOT the empty string: a form source is
                      ;; `[:string {:min 1}]` at the database attribute
                      ;; and in `evaluate`'s request, so an empty source
                      ;; cannot arrive — `reply/sources` never emits one
                      ;; and nothing can store one. Generating it tested
                      ;; an input the system makes unrepresentable.
                      "(recur)" "#{" "(let [x])" ":" "(def)"
                      "(clojure.string/upper-case 42)"
                      "(assoc nil :a)" "(first 1)"])])]
          (let [evaluation (deadlined source 300)]
            (and (not= ::hung evaluation)
                 (map? evaluation)
                 (contains? #{:done :error :interrupted}
                            (:seon.cluster.eval/status evaluation))
                 (string? (:seon.cluster.eval/result-edn evaluation))
                 (seon.schema/valid-candidate-value?
                  :seon.sci.eval/evaluation evaluation))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "evaluate threw or malformed: " (pr-str check)))))

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
