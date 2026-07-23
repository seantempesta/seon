(ns u1-guard-calibration
  "Counting-only SCI guard calibration corpus for U1."
  (:require [sci.core :as sci]
            [sci.interrupt :as interrupt]
            [seon.host.guard :as guard]))

(def corpus
  [{:calibration/class :agent-eval
    :calibration/label :scalar
    :calibration/source "(+ 20 22)"}
   {:calibration/class :agent-eval
    :calibration/label :collection-transform
    :calibration/source
    "(into [] (map (fn [x] {:n x :square (* x x)})) (range 500))"}
   {:calibration/class :agent-eval
    :calibration/label :native-reduce
    :calibration/source "(reduce + (range 10000))"}
   {:calibration/class :plan
    :calibration/label :plan-projection
    :calibration/source
    "(mapv (fn [n] {:my.plan/step n :my.plan/status :pending}) (range 250))"}
   {:calibration/class :authored-render
    :calibration/label :authored-render
    :calibration/source
    "(let [render (fn [rows] (mapv (fn [row] [:li {:data-id (:id row)} (:label row)]) rows))] (render (mapv (fn [n] {:id n :label (str \"row-\" n)}) (range 250))))"}])

(defn- policy [invocation-class]
  {::guard/fuel 0
   ::guard/mode :count
   ::guard/invocation-class invocation-class
   ::guard/fuel-config-key
   (case invocation-class
     :agent-eval :seon.config.guard/agent-eval-fuel
     :authored-render :seon.config.guard/authored-render-fuel
     :plan :seon.config.guard/plan-fuel)
   ::guard/deadline-config-key :seon.config.guard/deadline-ms
   ::guard/output-config-key :seon.config.guard/output-cap})

(defn -main
  "Print raw counting-only calibration rows as EDN."
  [& _]
  (let [holder (guard/holder)
        context (sci/init
                 {:namespaces {'clojure.core interrupt/clojure-core
                               'clojure.string interrupt/clojure-string}
                  :interrupt-fn (guard/interrupt-fn holder)})]
    (doseq [iteration (range 10)
            {:calibration/keys [class label source]} corpus]
      (let [started (System/nanoTime)
            value (guard/call!
                   {::guard/holder holder
                    ::guard/policy (policy class)
                    ::guard/evaluate! #(sci/eval-string* context source)})
            elapsed (- (System/nanoTime) started)]
        (prn {:calibration/tier :jvm
              :calibration/iteration iteration
              :calibration/class class
              :calibration/label label
              :calibration/steps (guard/steps-used holder)
              :calibration/elapsed-ns elapsed
              :calibration/output-chars (count (pr-str value))})))))
