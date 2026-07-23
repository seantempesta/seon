(ns seon.host.guard-context-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [sci.ctx-store]
            [seon.host.eval :as eval]
            [seon.host.guard :as guard]))

(defn- policy [interpreter-step-budget]
  {::guard/interpreter-step-budget interpreter-step-budget
   ::guard/mode :enforce
   ::guard/invocation-class :agent-eval
   ::guard/interpreter-step-budget-config-key
   :seon.config.guard/agent-eval-interpreter-step-budget
   ::guard/deadline-config-key :seon.config.guard/deadline-ms
   ::guard/output-config-key :seon.config.guard/output-cap})

(deftest retained-context-resets-interpreter-steps-for-a-second-session
  (let [holder (guard/holder)
        retained-ctx
        (sci/init {:interrupt-fn
                   (fn []
                     (when-let [current-holder
                                (::guard/holder (sci.ctx-store/get-ctx))]
                       (guard/check! current-holder)))})
        retained-ctx (assoc retained-ctx
                            ::guard/holder holder
                            :interrupt-fn (guard/interrupt-fn holder))
        session-a {:test.session/ctx retained-ctx}
        session-b {:test.session/ctx retained-ctx}]
    (guard/call!
     {::guard/holder holder
      ::guard/policy (policy 1000)
      ::guard/evaluate!
      #(sci/eval-string* (:test.session/ctx session-a)
                         "(defn retained-loop [n] (loop [x n] (if (zero? x) :done (recur (dec x)))))")})
    (testing "the second session's invocation budget replaces the prior reset"
      (let [thrown
            (try
              (guard/call!
               {::guard/holder holder
                ::guard/policy (policy 5)
                ::guard/evaluate!
                #(sci/eval-string* (:test.session/ctx session-b)
                                   "(retained-loop 100)")})
              nil
              (catch Throwable throwable throwable))
            steering (guard/steering-error! holder thrown)]
        (is (= :budget (:seon.error/kind steering)))
        (is (= 6 (guard/interpreter-steps-used holder)))
        (is (= 5 (get-in steering
                         [:seon.error/data
                          ::guard/initial-interpreter-step-budget])))))))

(deftest captured-output-crossing-stops-the-eval
  (let [holder (guard/holder)
        ctx (assoc (sci/init {:interrupt-fn (guard/interrupt-fn holder)})
                   ::guard/holder holder
                   :interrupt-fn (guard/interrupt-fn holder))
        session {:seon.host.session/interrupt-lock (Object.)
                 :seon.host.session/interrupt-fired? (atom false)
                 :seon.host.session/worker-phase (atom :idle)}
        envelope
        (guard/call!
         {::guard/holder holder
          ::guard/policy (policy 1000)
          ::guard/evaluate!
          #(eval/eval-form! session ctx 'user
                            "(do (print \"abcdefghijklmnop\") :unreached)"
                            8)})]
    (is (false? (:seon.eval/ok? envelope)))
    (is (:seon.eval/interrupted? envelope))
    (is (= :agent (get-in envelope [:seon/error :seon.error/kind])))
    (is (= :seon.config.guard/output-cap
           (get-in envelope
                   [:seon/error :seon.error/data ::guard/config-key])))))
