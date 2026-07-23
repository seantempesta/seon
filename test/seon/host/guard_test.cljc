(ns seon.host.guard-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.interrupt :as interrupt]
            [seon.host.guard :as guard]))

(def ^:private fuel-key :seon.config.guard.test/fuel)
(def ^:private deadline-key :seon.config.guard.test/deadline-ms)
(def ^:private output-key :seon.config.guard.test/output-cap)

(defn- policy
  [fuel mode]
  {::guard/fuel fuel
   ::guard/mode mode
   ::guard/invocation-class :agent-eval
   ::guard/fuel-config-key fuel-key
   ::guard/deadline-config-key deadline-key
   ::guard/output-config-key output-key})

(defn- guarded-context
  [holder]
  (sci/init
   {:namespaces {'clojure.core interrupt/clojure-core
                 'clojure.string interrupt/clojure-string}
    :interrupt-fn (guard/interrupt-fn holder)}))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch #?(:clj Throwable :cljs :default) throwable
      throwable)))

(defn- run-enforced
  [holder context source fuel]
  (let [throwable
        (caught
         (fn []
           (guard/call!
            {::guard/holder holder
             ::guard/policy (policy fuel :enforce)
             ::guard/evaluate! (fn [] (sci/eval-string* context source))})))]
    {:throwable throwable
     :error (guard/steering-error! holder throwable)
     :steps-used (guard/steps-used holder)}))

(deftest hostile-loop-stops-by-thread-free-fuel
  (let [holder (guard/holder)
        context (guarded-context holder)
        {:keys [throwable error steps-used]}
        (run-enforced holder context "(loop [] (recur))" 25)]
    (is (some? throwable))
    (is (= :budget (:seon.error/kind error)))
    (is (= 26 steps-used
           (get-in error [:seon.error/data ::guard/steps-used])))
    (is (= fuel-key
           (get-in error [:seon.error/data ::guard/config-key])))
    (is (str/includes? (:seon.error/message error) "Split the work"))
    (is (str/includes? (:seon.error/message error) (str fuel-key)))
    #?(:clj
       (is (not (.isInterrupted (Thread/currentThread)))
           "the fuel-only call supplied no deadline armer and never interrupts its thread"))))

#?(:clj
   (deftest deadline-policy-uses-the-same-door
     (let [holder (guard/holder)
           context (guarded-context holder)
           armed (atom 0)
           throwable
           (caught
            #(guard/call!
              {::guard/holder holder
               ::guard/policy (policy 100 :enforce)
               ::guard/arm-deadline!
               (fn [current-holder]
                 (swap! armed inc)
                 (guard/install-interrupted! current-holder (constantly true))
                 (fn [] (swap! armed dec)))
               ::guard/evaluate!
               (fn [] (sci/eval-string* context "(loop [] (recur))"))}))
           error (guard/steering-error! holder throwable)]
       (is (= :timeout (:seon.error/kind error)))
       (is (= 0 @armed) "the deadline leaf is always disarmed")
       (is (= deadline-key
              (get-in error [:seon.error/data ::guard/config-key]))))))

(deftest native-reduce-stops-at-the-same-fuel-door
  (let [holder (guard/holder)
        context (guarded-context holder)
        {:keys [error steps-used]}
        (run-enforced holder context "(reduce + (range))" 40)]
    (is (= :budget (:seon.error/kind error)))
    (is (= 41 steps-used))
    (is (= 41 (get-in error [:seon.error/data ::guard/steps-used])))))

(deftest identical-form-and-budget-use-identical-steps
  (let [holder (guard/holder)
        context (guarded-context holder)
        samples
        (mapv (fn [_]
                (:steps-used
                 (run-enforced holder context "(loop [] (recur))" 73)))
              (range 5))]
    (is (= [74 74 74 74 74] samples))))

(deftest counting-mode-measures-without-enforcing
  (let [holder (guard/holder)
        context (guarded-context holder)
        value
        (guard/call!
         {::guard/holder holder
          ::guard/policy (policy 0 :count)
          ::guard/evaluate! (fn []
                              (sci/eval-string*
                               context
                               "(reduce + (range 100))"))})]
    (is (= 4950 value))
    (is (pos? (guard/steps-used holder)))))

(deftest output-policy-uses-the-same-flat-steering-shape
  (let [holder (guard/holder)]
    (guard/reset! holder (policy 100 :enforce))
    (dotimes [_ 7] (guard/check! holder))
    (let [error (guard/policy-error! holder :agent)]
      (is (= :agent (:seon.error/kind error)))
      (is (= output-key
             (get-in error [:seon.error/data ::guard/config-key])))
      (is (= 7 (get-in error [:seon.error/data ::guard/steps-used])))
      (is (str/includes? (:seon.error/message error) "Reduce the input")))))

#?(:clj
   (deftest portable-guard-has-no-thread-or-executor-dependency
     (let [source (slurp "src/seon/host/guard.cljc")]
       (is (not (re-find #"\bThread\b|\bExecutor" source)))
       (is (= 1 (count (re-seq #"#\?\(" source)))
           "deadline arming is the only reader-conditional site"))))
