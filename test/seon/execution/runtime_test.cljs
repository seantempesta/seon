(ns seon.execution.runtime-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as str]
   [seon.agent.ctx :as ctx]
   [seon.db :as db]
   [seon.db.coordinate :as coordinate]
   [seon.execution.runtime :as runtime]))

(def point
  {::coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   ::coordinate/branch :db
   ::coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   ::coordinate/t 42})

(defn- call-with-pull-result
  [result request observed]
  (let [original db/pull]
    (set! db/pull
          (fn
            ([pull-request]
             (reset! observed
                     {:seon.execution.runtime-test/request pull-request
                      :seon.execution.runtime-test/context
                      (db/current-tx-context)})
             (js/Promise.resolve result))
            ([_ _]
             (js/Promise.reject
              (js/Error. "runtime prompt used positional pull")))))
    (-> (db/with-tx-context
          {::db/coordinate point}
          #(runtime/render-prompt! request))
        (.finally (fn [] (set! db/pull original))))))

(deftest literal-whole-prompt-uses-the-inherited-coordinate
  (async done
    (let [observed (atom nil)
          entity {:db/id 1
                  :seon.agent/id "agent-1"
                  :seon.render/ai (pr-str "literal whole prompt")}]
      (-> (call-with-pull-result
           entity {:seon.agent/id "agent-1"} observed)
          (.then
           (fn [rendered]
             (is (= "literal whole prompt" (:seon.render/text rendered)))
             (is (= [:prompt]
                    (mapv :seon.agent.ctx/name
                          (:seon.agent.ctx/rendered-blocks rendered))))
             (is (= point
                    (get-in @observed
                            [:seon.execution.runtime-test/context
                             ::db/coordinate])))
             (is (nil? (get-in @observed
                               [:seon.execution.runtime-test/request
                                ::db/coordinate]))
                 "the read inherits C rather than resolving or restating it")
             (is (= [:seon.agent/id "agent-1"]
                    (get-in @observed
                            [:seon.execution.runtime-test/request ::db/ref])))
             (is (some #(= '{:seon.agent/ctx [*]} %)
                       (get-in @observed
                               [:seon.execution.runtime-test/request
                                ::db/pull-pattern])))
             (done)))
          (.catch
           (fn [error]
             (is false (str "literal whole-prompt render rejected: " error))
             (done)))))))

(deftest literal-profile-selects-the-database-owned-blocks
  (async done
    (let [observed (atom nil)
          entity
          {:seon.agent/id "agent-1"
           :seon.agent.ctx/cache-breakpoint 6
           :seon.agent/ctx
           [{:seon.agent.ctx/name :alpha
             :seon.agent.ctx/priority 20
             :seon.render/ai (pr-str "stored alpha")}
            {:seon.agent.ctx/name :beta
             :seon.agent.ctx/priority 5
             :seon.render/ai (pr-str "stored beta")}
            {:seon.agent.ctx/name :human-only
             :seon.agent.ctx/priority 30
             :seon.render/html [:p "human"]}]}
          profile
          [{:seon.agent.ctx/name :alpha
            :seon.agent.ctx/priority 7
            :seon.render/ai "profile alpha"}
           {:seon.agent.ctx/name :beta
            :seon.agent.ctx/priority 5}]]
      (-> (call-with-pull-result
           entity
           {:seon.agent/id "agent-1" :seon.agent.ctx/profile profile}
           observed)
          (.then
           (fn [rendered]
             (is (= [:beta :alpha]
                    (mapv :seon.agent.ctx/name
                          (:seon.agent.ctx/rendered-blocks rendered))))
             (is (= ["stored beta" "profile alpha"]
                    (mapv :seon.render/text
                          (:seon.agent.ctx/rendered-blocks rendered))))
             (is (not (str/includes? (:seon.render/text rendered)
                                     ctx/stable-boundary))
                 "profile renders preserve the existing no-boundary contract")
             (done)))
          (.catch
           (fn [error]
             (is false (str "literal profile render rejected: " error))
             (done)))))))

(deftest unresolved-slots-are-local-and-database-errors-are-not-missing-agents
  (async done
    (let [observed (atom nil)
          entity
          {:seon.agent/id "agent-1"
           :seon.agent/ctx
           [{:seon.agent.ctx/name :literal
             :seon.agent.ctx/priority 1
             :seon.render/ai (pr-str "literal sibling")}
            {:seon.agent.ctx/name :pending
             :seon.agent.ctx/priority 2
             :seon.render/ai (pr-str 'my.prompt/render)}]}
          database-error
          {:seon.error/message "authority unavailable"
           :seon.error/kind :core-bug}]
      (-> (call-with-pull-result
           entity {:seon.agent/id "agent-1"} observed)
          (.then
           (fn [rendered]
             (is (= [:literal :pending]
                    (mapv :seon.agent.ctx/name
                          (:seon.agent.ctx/rendered-blocks rendered))))
             (is (= "literal sibling"
                    (get-in rendered
                            [:seon.agent.ctx/rendered-blocks 0
                             :seon.render/text])))
             (is (str/includes?
                  (get-in rendered
                          [:seon.agent.ctx/rendered-blocks 1 :seon.render/text])
                  "my.prompt/render"))
             (call-with-pull-result
              database-error {:seon.agent/id "agent-1"} observed)))
          (.then
           (fn [rendered]
             (is (= [:database]
                    (mapv :seon.agent.ctx/name
                          (:seon.agent.ctx/rendered-blocks rendered))))
             (is (str/includes? (:seon.render/text rendered)
                                "authority unavailable"))
             (is (not= "" (:seon.render/text rendered)))
             (done)))
          (.catch
           (fn [error]
             (is false (str "local-error render rejected: " error))
             (done)))))))

(deftest empty-and-missing-agents-render-the-empty-existing-shape
  (async done
    (let [observed (atom nil)
          empty-render {:seon.render/text ""
                        :seon.agent.ctx/rendered-blocks []}]
      (-> (call-with-pull-result
           {} {:seon.agent/id "empty"} observed)
          (.then
           (fn [rendered]
             (testing "an existing agent with no prompt data"
               (is (= empty-render rendered)))
             (call-with-pull-result
              nil {:seon.agent/id "missing"} observed)))
          (.then
           (fn [rendered]
             (testing "a genuinely missing agent"
               (is (= empty-render rendered)))
             (done)))
          (.catch
           (fn [error]
             (is false (str "empty/missing render rejected: " error))
             (done)))))))
