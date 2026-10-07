(ns seon.sci.kernel-test
  "The agent interpreter reaches what a Clojure REPL in this JVM reaches, and
  every context evaluated on an armed thread serves the one arm governing it.

  Both classes were refusals that prevented no correctness failure
  (docs/research/agent-platform/agent-restrictions-and-rollback-2026-09-26.md
  items 2 and 5): `System/nanoTime`, `Math/abs`, `future` and
  `(require 'malli.core)` did not resolve, and a context opened inside an
  armed evaluation was refused as \"a different SCI context\"."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.error :as error]
            [seon.error.refusal :as error.refusal]
            [seon.print.value :as print.value]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as test-support]))

(defn- evaluate
  "One evaluation through the agent's evaluate path in `ctx`, against the
  executing member's database: an agent's evaluation always has one, and its
  shown value renders a `:seon.render/unit`, which requires it."
  [ctx source time-limit-ms]
  (test-support/evaluate {:seon.db/db (db/db (:seon.db/connection (test-support/execution-handle nil)))
                  :seon.schema/projection (schema/handed-projection)
                  :seon.cluster.eval/source source
                  :seon.sci.admit/caps (config/result-caps config/defaults)
                  :seon.sci.eval/time-limit-ms time-limit-ms
                  :seon.sci.eval/ctx ctx}))

(defn- value
  [ctx source]
  (let [evaluation (evaluate ctx source 5000)]
    (is (nil? (:seon.cluster.eval/error evaluation)))
    (:seon.sci.admit/value evaluation)))

(deftest the-interpreter-resolves-the-classpath-and-loaded-namespaces
  (let [ctx (eval/build-base-ctx (schema/handed-projection))]
    (testing "java.lang short names, as Clojure's default imports"
      (is (int? (value ctx "(System/nanoTime)")))
      (is (= 1 (value ctx "(Math/abs -1)")))
      (is (= "x" (value ctx "(try (throw (IllegalStateException. \"x\")) (catch IllegalStateException e (ex-message e)))"))))
    (testing "any classpath class by its full name, and through import"
      (is (uuid? (value ctx "(java.util.UUID/randomUUID)")))
      (is (inst? (value ctx "(do (import 'java.util.Date) (Date. 0))")))
      (is (= 3 (value ctx "(.size (java.util.ArrayList. [1 2 3]))"))))
    (testing "a namespace the JVM loaded requires as itself"
      (is (nil? (value ctx "(require '[malli.core :as m])")))
      (is (true? (value ctx "(m/validate int? 1)")))
      (is (false? (value ctx "(m/validate int? \"1\")"))))
    (testing "an unloaded namespace and an unknown class still answer by name"
      (is (re-find #"Could not find namespace"
                   (str (:seon.cluster.eval/error (evaluate ctx "(require 'no.such.loaded-namespace)" 5000)))))
      (is (re-find #"no\.such\.Klass"
                   (str (:seon.cluster.eval/error (evaluate ctx "(no.such.Klass.)" 5000))))))
    (testing "future runs and is cut by the evaluation's deadline"
      (is (= 1 (value ctx "@(future 1)")))
      (let [started (System/nanoTime)
            evaluation (evaluate ctx "@(future (loop [] (recur)))" 300)]
        (is (= :time (get-in evaluation [:seon.sci.admit/record :seon.eval/outcome])))
        (is (< (quot (- (System/nanoTime) started) 1000000) 5000))))))

(deftest a-nested-context-inherits-the-outer-arm
  (let [outer (eval/build-base-ctx (schema/handed-projection))
        nested (eval/build-base-ctx (schema/handed-projection))]
    (kernel/with-arm
      outer 60000
      (fn [_]
        (testing "another context, and a fork of the armed one, evaluate under the outer arm"
          (is (= 3 (value nested "(+ 1 2)")))
          (is (= 3 (value (sci/fork outer) "(+ 1 2)"))))))
    (testing "the nested evaluation dies at the OUTER deadline, not its own"
      (let [started (System/nanoTime)
            evaluation (kernel/with-arm
                         outer 300
                         (fn [_] (evaluate nested "(loop [] (recur))" 600000)))]
        (is (= :time (get-in evaluation [:seon.sci.admit/record :seon.eval/outcome])))
        (is (< (quot (- (System/nanoTime) started) 1000000) 5000))))))

(deftest memory-pressure-holds-until-usage-falls-below-its-resume-bound
  ;; Both OOMs of 2026-09-26 admitted new work into a JVM already past most of
  ;; its heap. A held pressure refuses by name, naming the work in flight when
  ;; it began, and clears once the tenured pool's collection usage is below
  ;; its resume bound. Bounds are data on the pressure value, so this world
  ;; holds its own state and never pressures the process it runs in.
  (let [pressure {::kernel/pool "G1 Old Gen" ::kernel/used-bytes 1 ::kernel/max-bytes 10
                  ::kernel/enter-bytes 7 ::kernel/resume-bytes 0
                  ::kernel/entered-at (java.util.Date.)
                  ::kernel/in-flight [["root" "run-1"]]}
        held (volatile! pressure)]
    (testing "at or above its resume bound it holds"
      (is (= "G1 Old Gen" (::kernel/pool (kernel/memory-pressure held))))
      (is (some? @held)))
    (testing "the refusal names the bound and the work in flight"
      (let [refusal (kernel/memory-pressure-error (kernel/memory-pressure held))]
        (is (= :seon.sci.kernel/arming (:seon.error/layer refusal)))
        (is (= [["root" "run-1"]] (::kernel/in-flight refusal)))
        (is (re-find #"memory pressure" (:seon.error/message refusal)))))
    (testing "below its resume bound it clears and admits"
      (vreset! held (assoc pressure ::kernel/resume-bytes Long/MAX_VALUE))
      (is (nil? (kernel/memory-pressure held)))
      (is (nil? @held)))))

;;; SCHEMA PAIRS SELECT; VALUES STAY WHOLE (owner 2026-09-26, "Three points";
;;; render-specificity audit slice 1). Each pair's source is read back and its
;;; forms evaluated in a world holding the value under its handle, as the
;;; viewing agent's REPL evaluates them.

(defn- failure
  [ctx source]
  (:seon.sci.admit/value (evaluate ctx source 5000)))

(deftest an-agents-exception-is-whole-and-its-reply-is-the-authors-evidence
  ;; The value keeps its whole chain and first-party frames; the base error
  ;; pair (seon.error/render-ai) shows the exception's class, message and the
  ;; author's SCI stacktrace as data, with no evaluator frame.
  (let [ctx (eval/build-base-ctx (schema/handed-projection))
        divide (failure ctx "(/ 1 0)")
        reply (error/reply-value nil divide)]
    (is (seq (mapcat :seon.error/frames (:seon.error/chain divide))))
    (is (= 'java.lang.ArithmeticException (symbol (str (:seon.error/exception-class reply)))))
    (is (= (:seon.sci.eval/stacktrace divide) (:seon.sci.eval/stacktrace reply)))
    (is (not-any? #(str/includes? (print.value/bounded-text reply 100000) %) ["seon.sci.eval$" "sci/eval.clj" "sci.impl."])
        "no evaluator frame is shown")))

(deftest a-contract-refusals-reply-is-the-failing-path-and-an-example
  ;; A program function resolves in an acquired agent context (a bare
  ;; interpreter holds no program namespaces): the member's, forked. The reply
  ;; (seon.error/reply-value) is data the agent fixes the call from: the
  ;; producer's message, the failing path with its expectation, value and one
  ;; generated example, and its frames.
  (let [ctx (test-support/fork-cluster-ctx
             (:seon.db/connection (test-support/execution-handle nil)))
        refusal (failure ctx "(seon.error.refusal/frame-function \"x\")")
        reply (error/reply-value nil refusal)]
    (is (= 'seon.error.refusal/frame-function (:seon.error/operation refusal)))
    (is (str/starts-with? (:seon.error/message reply)
                          "seon.error.refusal/frame-function refused argument 0"))
    (is (= [{:seon.error/path [] :seon.error/expected :symbol :seon.error/offending "x"}]
           (mapv #(dissoc % :seon.error/example) (:seon.error/problems reply))))
    (is (symbol? (get-in reply [:seon.error/problems 0 :seon.error/example]))
        "one generated example of the expectation, labelled as an example")
    (is (not (contains? reply :seon.error/fix-form)) "no call is synthesized from the caller's input")
    (is (= (:seon.sci.eval/stacktrace refusal) (:seon.sci.eval/stacktrace reply)) "the author's location")
    (is (not-any? #(contains? reply %) [:seon.error/expected-shape :seon.error/chain :seon.instrument/malli])
        "instrument bookkeeping stays on the value, unshown")))

(deftest a-failures-cost-follows-its-distinct-frames-not-its-depth
  ;; waste-rank 2026-10-07: a recursion's StackOverflowError kept 7,969 SCI
  ;; frames and a thrown 1,000-link chain converted and classified 1,000 x
  ;; 209 JVM frames (187 ms, 757 MB in failure-value alone). A link keeps
  ;; only the frames it does not share with the link enclosing it, and the
  ;; SCI stacktrace keeps each distinct frame once.
  (let [ctx (eval/build-base-ctx (schema/handed-projection))
        overflow (failure ctx "(defn deep [n] (inc (deep (inc n)))) (deep 0)")
        chained "(throw (reduce (fn [c i] (ex-info (str \"link \" i) {:i i} c)) (ex-info \"root\" {}) (range 999)))"
        chain (failure ctx chained)
        frames (mapcat :seon.error/frames (:seon.error/chain chain))]
    (testing "a recursion's stacktrace is its distinct frames, outermost last"
      (is (= 'java.lang.StackOverflowError (:seon.error/exception-class overflow)))
      (is (apply distinct? (:seon.sci.eval/stacktrace overflow)))
      (is (< (count (:seon.sci.eval/stacktrace overflow)) 10) "was 7,969")
      (is (= 'user (first (peek (:seon.sci.eval/stacktrace overflow))))))
    (testing "every link is kept; a link shares no frames with the link enclosing it"
      (is (= 1000 (count (:seon.error/chain chain))))
      (is (= [{:i 998} "root"] [(:seon.error/data (first (:seon.error/chain chain)))
                                (:seon.error/message (peek (:seon.error/chain chain)))]))
      (is (seq frames))
      (is (<= (count (filter :seon.error/frames (:seon.error/chain chain))) 3)
          "the links built at one site share their frames with the outermost"))
    (testing "the producer's allocation does not grow with the chain"
      (let [thrown (try (sci/eval-string chained) (catch Throwable t t))
            bean ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
            thread (.getId (Thread/currentThread))
            before (.getThreadAllocatedBytes bean thread)
            started (System/nanoTime)
            value (kernel/failure-value {::kernel/time-limit-kind ::eval/time-limit
                                         ::kernel/failure-kind ::eval/evaluation-failed}
                                        thrown (kernel/unarmed-record (System/nanoTime)))
            allocated (- (.getThreadAllocatedBytes bean thread) before)
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
        (is (= 1000 (count (:seon.error/chain value))))
        (is (< allocated (* 50 1024 1024)) "was 757 MB before the frames-in-common rule")
        (is (< elapsed-ms 100) "was 187 ms")))))

(deftest an-error-holding-a-ten-thousand-deep-value-keeps-its-error
  ;; deep-value lane 2026-10-07: the error's data walks (refusal/referenced,
  ;; bounded-cost) recursed once per level and overflowed the stack, so the
  ;; reply was a bare StackOverflowError. They fold over an explicit stack.
  (let [ctx (eval/build-base-ctx (schema/handed-projection))
        deep (failure ctx "(let [deep (reduce (fn [v _] [v]) [] (range 10000))] (throw (ex-info \"deep\" {:v deep})))")
        reply (error/reply-value nil deep)]
    (is (= 'clojure.lang.ExceptionInfo (:seon.error/exception-class deep)))
    (is (= "deep" (:seon.error/message (first (:seon.error/chain deep)))))
    (is (error.refusal/within-evidence-bound-chain? (:seon.error/chain deep))
        "the 20,000-character value is held within the evidence bound")
    (is (string? (print.value/bounded-text reply 4000)))))
