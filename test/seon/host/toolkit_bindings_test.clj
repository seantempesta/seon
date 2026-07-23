(ns seon.host.toolkit-bindings-test
  "Localized JVM proof for the plan, knowledge, and skills host bindings."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sci.core :as sci]
   [seon.host.context :as context]))

(defn- unconnected-writer
  []
  (context/writer-session
   {::context/writer-socket-path "tmp/u8-toolkit-unused.sock"
    ::context/database-name "u8-toolkit"}))

(def expected-bindings
  {'my.plan
   '#{active! blocked! document done! drop! list-open move! needs! next
      plan! reconcile! reopen! status step! tree}
   'my.kb '#{recall remember}
   'my.kb.shared '#{instructions}
   'my.skills '#{list load unload}})

(deftest toolkit-functions-use-the-one-host-registry
  (let [base (context/build-base! (unconnected-writer))
        registry @(::context/registry base)]
    (doseq [[lib expected] expected-bindings]
      (testing (str lib)
        (is (= expected
               (into #{}
                     (filter expected)
                     (keys (::context/vars (get registry lib)))))
            "every promoted toolkit function is a registered host wrapper")))))

(deftest plan-wrappers-run-the-landed-source-contract
  (let [base (context/build-base! (unconnected-writer))
        ctx (context/fork-context base)]
    (is (= []
           (sci/eval-string*
            ctx
            "(do (require '[my.plan :as plan]) (plan/next {}))")))
    (let [result
          (sci/eval-string*
           ctx
           "(do (require '[my.plan :as plan]) (plan/active! {}))")]
      (is (false? (:my.plan/ok? result)))
      (is (re-find #"active!" (:my.plan/error result))))))
