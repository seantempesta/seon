(ns seon.sci.kernel-test
  "The agent interpreter reaches what a Clojure REPL in this JVM reaches, and
  every context evaluated on an armed thread serves the one arm governing it.

  Both classes were refusals that prevented no correctness failure
  (docs/research/agent-platform/agent-restrictions-and-rollback-2026-09-26.md
  items 2 and 5): `System/nanoTime`, `Math/abs`, `future` and
  `(require 'malli.core)` did not resolve, and a context opened inside an
  armed evaluation was refused as \"a different SCI context\"."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]))

(defn- evaluate
  "One evaluation through the agent's evaluate path in `ctx`."
  [ctx source time-limit-ms]
  (eval/evaluate {:seon.schema/projection (schema/handed-projection)
                  :seon.cluster.eval/source source
                  :seon.sci.admit/caps (config/result-caps config/defaults)
                  :seon.sci.eval/time-limit-ms time-limit-ms
                  :seon.config/on-core-error :panic
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
