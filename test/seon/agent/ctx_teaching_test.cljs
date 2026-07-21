(ns seon.agent.ctx-teaching-test
  "Tier-derived system teaching at the compiled prompt boundary."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as str]
   [my.plan.internal :as plan.internal]
   [seon.agent.ctx :as ctx]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.execution.runtime :as runtime]))

(defn- rendered-system-text [host-tier?]
  (ctx/render-system-text host-tier? ctx/system-text-shared))

(deftest system-teaching-selects-one-platform-contract
  (let [child (rendered-system-text false)
        host (rendered-system-text true)]
    (testing "child tier keeps the asynchronous JavaScript contract"
      (is (str/includes? child "platform contract: child"))
      (is (str/includes? child "js/"))
      (is (str/includes? child "ASYNC FORMS"))
      (is (not (str/includes? child "java.util.Date"))))
    (testing "host tier teaches synchronous JVM forms"
      (is (str/includes? host "platform contract: host"))
      (is (str/includes? host "java.util.Date"))
      (is (str/includes? host "synchronous"))
      (is (not (str/includes? host "NO JVM"))))
    (testing "both tiers carry the generated-code contract"
      (doseq [text [child host]]
        (is (str/includes? text "GENERATE CODE"))
        (is (str/includes? text "LAST VERSION WINS"))))))

(deftest configured-shared-body-uses-the-same-platform-renderer
  (let [shared "; configured shared teaching"
        child (ctx/render-system-text false shared)
        host (ctx/render-system-text true shared)]
    (is (str/includes? child shared))
    (is (str/includes? host shared))
    (is (str/includes? child "platform contract: child"))
    (is (str/includes? host "platform contract: host"))))

(deftest development-teaching-is-platform-neutral
  (doseq [_host-tier? [false true]]
    (is (not (str/includes? plan.internal/development-teaching
                            "ClojureScript")))
    (is (not (str/includes? plan.internal/development-teaching
                            "Promises")))
    (is (str/includes? plan.internal/development-teaching
                       "LAST VERSION WINS"))))

(deftest prompt-render-derives-tier-from-the-acquired-fact
  (async done
    (let [original-execute-many db/execute-many
          tier-result (atom nil)
          requests (atom [])
          render! (fn []
                    (runtime/render-prompt!
                     {:seon.agent/id "teaching-agent"}
                     (fn [_] (js/Promise.resolve []))))
          restore! (fn []
                     (set! db/execute-many original-execute-many)
                     (done))]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {::db/results
                [{::protocol/success? true
                  ::protocol/result {:seon.agent/id "teaching-agent"}}
                 {::protocol/success? true
                  ::protocol/result
                  {:seon.config/system-text "; configured shared teaching"}}
                 {::protocol/success? true ::protocol/result nil}
                 {::protocol/success? true ::protocol/result @tier-result}]})))
      (-> (render!)
          (.then
           (fn [child-render]
             (is (str/includes? (:seon.ai/system-prompt child-render)
                                "platform contract: child"))
             (reset! tier-result "/cluster/host.sock")
             (render!)))
          (.then
           (fn [host-render]
             (let [system-prompt (:seon.ai/system-prompt host-render)
                   tier-member (get-in (first @requests) [::db/members 3])]
               (is (str/includes? system-prompt "; configured shared teaching"))
               (is (str/includes? system-prompt "platform contract: host"))
               (is (= ["teaching-agent"] (::protocol/arguments tier-member)))
               (is (some #{'[?agent
                              :seon.execution.host/eval-socket-path
                              ?socket-path]}
                         (tree-seq coll? seq
                                   (::protocol/query-form tier-member)))))))
          (.catch (fn [error] (is false (str error))))
          (.finally restore!)))))
