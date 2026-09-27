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
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.error :as error]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as test-support]))

(defn- evaluate
  "One evaluation through the agent's evaluate path in `ctx`, against the
  executing member's database: an agent's evaluation always has one, and its
  shown value renders a `:seon.render/unit`, which requires it."
  [ctx source time-limit-ms]
  (eval/evaluate {:seon.db/db (db/db (:seon.db/connection (test-support/execution-handle nil)))
                  :seon.schema/projection (schema/handed-projection)
                  :seon.cluster.eval/source source
                  :seon.sci.admit/caps (config/result-caps config/defaults)
                  :seon.sci.eval/time-limit-ms time-limit-ms
                  :seon.sci.eval/ctx ctx}))

(defn- value
  [ctx source]
  (let [evaluation (evaluate ctx source 5000)]
    (is (nil? (:seon.cluster.eval/error evaluation)) (pr-str source evaluation))
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
        (is (= :time (get-in evaluation [:seon.sci.admit/record :seon.eval/outcome])) (pr-str evaluation))
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
        (is (= :time (get-in evaluation [:seon.sci.admit/record :seon.eval/outcome])) (pr-str evaluation))
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

(defn- shown
  "`source`'s forms evaluated where `handle` names `value`, and the size of
  the transcript they print (source plus results) in estimated tokens."
  [source handle value]
  (let [world (sci/init {:namespaces {(symbol (namespace handle)) {(symbol (name handle)) value}}})
        results (mapv #(sci/eval-form world %) (read-string (str "[" source "\n]")))]
    {:results results
     :tokens (long (Math/ceil (/ (+ (count source) (count (pr-str results)))
                                 tokens/shipped-chars-per-token)))}))

(deftest an-agents-exception-is-whole-and-its-pair-shows-what-clojure-main-shows
  (let [ctx (eval/build-base-ctx (schema/handed-projection))
        handle 'result/e1
        divide (failure ctx "(/ 1 0)")
        thrown (failure ctx "(throw (ex-info \"boom\" {:a 1}))")
        source (kernel/render-ai {:seon.render/value divide :seon.repl/handle handle})
        {:keys [results tokens]} (shown source handle divide)]
    (testing "the value keeps its first-party frames: nothing is trimmed for presentation"
      (is (seq (mapcat :seon.error/frames (:seon.error/chain divide))) (pr-str divide)))
    (testing "the pair: clojure.main's message, then the author's location read from the held value"
      (is (= [";; Execution error (ArithmeticException) at (REPL:1:1)." ";; Divide by zero"]
             (take 2 (str/split-lines source))) source)
      (is (= [(:seon.sci.eval/stacktrace divide)] results) source)
      (is (str/includes? source (str "(:seon.error/chain " handle ")")) "the whole chain is one read away")
      ;; The author's location is read as `(:seon.sci.eval/stacktrace handle)`;
      ;; an evaluator frame would print as its class or file.
      (is (not-any? #(str/includes? source %) ["seon.sci.eval$" "sci/eval.clj" "sci.impl."])
          "no evaluator frame is shown")
      (is (<= tokens 200) source))
    (testing "an ex-info's data is shown per cause"
      (let [source (kernel/render-ai {:seon.render/value thrown :seon.repl/handle handle})]
        (is (= [{:a 1}] (peek (:results (shown source handle thrown)))) source)))
    (testing "without a handle only bounded comments are said"
      (is (every? #(str/starts-with? % ";; ")
                  (str/split-lines (kernel/render-ai {:seon.render/value divide})))))))

(deftest a-contract-refusals-pair-shows-operation-expected-offending-fix-and-location
  ;; A program function resolves in an acquired agent context (a bare
  ;; interpreter holds no program namespaces): the member's, forked.
  (let [ctx (test-support/fork-cluster-ctx
             (:seon.db/connection (test-support/execution-handle nil)))
        handle 'result/e2
        refusal (failure ctx "(seon.error.refusal/frame-function \"x\")")
        source (error/render-contract-ai {:seon.render/value refusal :seon.repl/handle handle})
        {:keys [results tokens]} (shown source handle refusal)]
    (is (= 'seon.error.refusal/frame-function (:seon.error/operation refusal)) (pr-str refusal))
    (is (str/starts-with? source ";; seon.error.refusal/frame-function refused its ") source)
    (is (= [{:seon.error/path [] :seon.error/expected :symbol :seon.error/offending "x"}]
           (mapv #(dissoc % :seon.error/fix) (first results)))
        source)
    (is (string? (:seon.error/fix (ffirst results))))
    (is (= (:seon.sci.eval/stacktrace refusal) (second results)) "the author's location")
    (is (str/includes? source "(doc seon.error.refusal/frame-function)") "its contract is one read away")
    (is (not-any? #(str/includes? source %) ["seon.instrument/" ":seon.error/doc" "expected-shape"])
        "instrument bookkeeping stays on the value, unshown")
    (is (<= tokens 400) source)))
