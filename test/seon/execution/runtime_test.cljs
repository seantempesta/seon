(ns seon.execution.runtime-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as str]
   [seon.agent.ctx :as ctx]
   [seon.db :as db]
   [seon.db.coordinate :as coordinate]
   [seon.db.protocol :as protocol]
   [seon.execution.runtime :as runtime]))

(def point
  {::coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   ::coordinate/branch :db
   ::coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   ::coordinate/t 42})

(defn- call-with-acquired-agent
  ([result request observed]
   (call-with-acquired-agent
     result request observed
     (fn [calls]
       (swap! observed assoc :seon.execution.runtime-test/calls calls)
       (js/Promise.resolve
         (mapv (fn [call]
                 {:seon.execution/ok? false
                  :seon.execution/error
                  {:seon.error/message
                   (str (:seon.execution/function-symbol call)
                        " is not loaded")}})
               calls)))))
  ([result request observed invoke-selected!]
   (let [original db/execute-many]
     (set! db/execute-many
           (fn [acquisition-request]
             (reset! observed
                     {:seon.execution.runtime-test/request acquisition-request
                      :seon.execution.runtime-test/context
                      (db/current-tx-context)})
             (js/Promise.resolve
              (if (and (map? result) (:seon.error/message result))
                result
                {::db/coordinate point
                 ::db/results
                 [{::protocol/success? true ::protocol/result result}
                  {::protocol/success? true
                   ::protocol/result
                   {:seon.config/system-text "frozen system"}}]}))))
     (-> (db/with-tx-context
           {::db/coordinate point}
           #(runtime/render-prompt! request invoke-selected!))
         (.finally (fn [] (set! db/execute-many original)))))))

(deftest literal-whole-prompt-uses-the-inherited-coordinate
  (async done
    (let [observed (atom nil)
          entity {:db/id 1
                  :seon.agent/id "agent-1"
                  :seon.render/ai (pr-str "literal whole prompt")}]
      (-> (call-with-acquired-agent
           entity {:seon.agent/id "agent-1"} observed)
          (.then
           (fn [rendered]
             (is (= "literal whole prompt" (:seon.render/text rendered)))
             (is (= "frozen system" (:seon.ai/system-prompt rendered)))
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
             (is (nil? (:seon.execution.runtime-test/calls @observed))
                 "a literal whole prompt invokes no selected function")
             (is (= [:seon.agent/id "agent-1"]
                    (get-in @observed
                            [:seon.execution.runtime-test/request ::db/members
                             0 ::protocol/entity-id])))
             (is (some #(= '{:seon.agent/ctx [*]} %)
                       (get-in @observed
                               [:seon.execution.runtime-test/request ::db/members
                                0 ::protocol/selector])))
             (is (= [:seon.config/id "cluster"]
                    (get-in @observed
                            [:seon.execution.runtime-test/request ::db/members
                             1 ::protocol/entity-id])))
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
      (-> (call-with-acquired-agent
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
      (-> (call-with-acquired-agent
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
             (call-with-acquired-agent
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

(deftest selected-symbol-blocks-share-one-child-local-invocation
  (async done
    (let [observed (atom nil)
          calls (atom [])
          entity
          {:seon.agent/id "agent-1"
           :seon.agent/ctx
           [{:seon.agent.ctx/name :literal
             :seon.agent.ctx/priority 1
             :seon.render/ai (pr-str "literal sibling")}
            {:seon.agent.ctx/name :first
             :seon.agent.ctx/priority 2
             :seon.render/ai (pr-str 'my.prompt/first)}
            {:seon.agent.ctx/name :second
             :seon.agent.ctx/priority 3
             :seon.render/ai (pr-str 'seon.prompt/second)}]}
          invoke-selected!
          (fn [selected]
            (swap! calls conj selected)
            (js/Promise.resolve
              [{:seon.execution/ok? true
                :seon.execution/value "first result"
                :seon.execution/source "(defn first [_] \"first result\")"}
               {:seon.execution/ok? true
                :seon.execution/value {:seon.render/ai "second result"}}]))]
      (-> (call-with-acquired-agent
            entity {:seon.agent/id "agent-1"} observed invoke-selected!)
          (.then
            (fn [rendered]
              (is (= 1 (count @calls))
                  "all selected symbols use one child-local call")
              (is (= ['my.prompt/first 'seon.prompt/second]
                     (mapv :seon.execution/function-symbol (first @calls))))
              (is (every?
                    #(nil? (get-in % [:seon.execution/arguments 0 :seon.db/db]))
                    (first @calls))
                  "selected functions receive ordinary input, never a db value")
              (is (= ["literal sibling" "first result" "second result"]
                     (mapv :seon.render/text
                           (:seon.agent.ctx/rendered-blocks rendered))))
              (done)))
          (.catch
            (fn [error]
              (is false (str "selected invocation rejected: " error))
              (done)))))))

(deftest derived-renderers-reuse-acquired-namespace-rows
  (async done
    (let [observed (atom nil)
          calls (atom [])
          entity
          {:seon.agent/id "agent-1"
           :seon.agent/ctx
           [{:seon.agent.ctx/name :namespaces
             :seon.agent.ctx/priority 20
             :seon.render/ai
             (pr-str 'seon.agent.ctx.namespaces/namespaces-block)}]}
          invoke-selected!
          (fn [selected]
            (swap! calls conj selected)
            (js/Promise.resolve
              (if (= 'seon.agent.ctx.namespaces/namespaces-block
                     (:seon.execution/function-symbol (first selected)))
                [{:seon.execution/ok? true
                  :seon.execution/value
                  {:seon.render/ai "namespace result"
                   :seon.agent.ctx.render-fns/current-ns :my.test
                   :seon.agent.ctx.render-fns/fn-rows
                   [{:seon.fn/sym "my.test/view"
                     :seon.fn/spec
                     (pr-str
                       '[:=> [:cat :map]
                         [:map [:seon.render/ai :string]]])
                     :seon.fn/private? false}]}}]
                [{:seon.execution/ok? true
                  :seon.execution/value "derived result"}])))]
      (-> (call-with-acquired-agent
            entity {:seon.agent/id "agent-1"} observed invoke-selected!)
          (.then
            (fn [rendered]
              (is (= 2 (count @calls))
                  "stored acquisition and derived execution are two bounded batches")
              (is (= ['seon.agent.ctx.namespaces/namespaces-block
                      'seon.agent.ctx.render-fns/render-fn-block-ai]
                     (mapv (comp :seon.execution/function-symbol first)
                           @calls)))
              (is (= [:namespaces :render-fn/view]
                     (mapv :seon.agent.ctx/name
                           (:seon.agent.ctx/rendered-blocks rendered))))
              (is (= ["namespace result" "derived result"]
                     (mapv :seon.render/text
                           (:seon.agent.ctx/rendered-blocks rendered))))
              (is (every?
                    #(nil? (get-in % [:seon.execution/arguments 0 :seon.db/db]))
                    (mapcat identity @calls))
                  "no derived call receives a local database value")
              (done)))
          (.catch
            (fn [error]
              (is false (str "derived renderer acquisition rejected: " error))
              (done)))))))

(deftest empty-and-missing-agents-render-the-empty-existing-shape
  (async done
    (let [observed (atom nil)
          empty-render {:seon.render/text ""
                        :seon.agent.ctx/rendered-blocks []
                        :seon.ai/system-prompt "frozen system"}]
      (-> (call-with-acquired-agent
           {} {:seon.agent/id "empty"} observed)
          (.then
           (fn [rendered]
             (testing "an existing agent with no prompt data"
               (is (= empty-render rendered)))
             (call-with-acquired-agent
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

(deftest absent-system-config-uses-the-shipped-system-text
  (async done
    (let [original db/execute-many]
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               {::db/coordinate point
                ::db/results
                [{::protocol/success? true
                  ::protocol/result {:seon.agent/id "agent-1"}}
                 {::protocol/success? true ::protocol/result nil}]})))
      (-> (db/with-tx-context
            {::db/coordinate point}
            #(runtime/render-prompt!
              {:seon.agent/id "agent-1"}
              (fn [_] (js/Promise.resolve []))))
          (.then
           (fn [rendered]
             (is (= ctx/system-text (:seon.ai/system-prompt rendered)))
             (done)))
          (.catch (fn [error] (is false (str error)) (done)))
          (.finally (fn [] (set! db/execute-many original)))))))

(deftest failed-prompt-acquisition-invokes-no-selected-functions
  (async done
    (let [original db/execute-many
          calls (atom 0)]
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               {::db/coordinate point
                ::db/results
                [{::protocol/success? false
                  ::protocol/error {:seon.error/message "authority failed"}}
                 {::protocol/success? true
                  ::protocol/result
                  {:seon.config/system-text "frozen system"}}]})))
      (-> (db/with-tx-context
            {::db/coordinate point}
            #(runtime/render-prompt!
              {:seon.agent/id "agent-1"}
              (fn [_]
                (swap! calls inc)
                (js/Promise.resolve []))))
          (.then
           (fn [rendered]
             (is (zero? @calls))
             (is (= "authority failed" (:seon.error/message rendered)))
             (is (= :core-bug (:seon.error/kind rendered)))
             (is (nil? (:seon.ai/system-prompt rendered)))
             (done)))
          (.catch (fn [error] (is false (str error)) (done)))
          (.finally (fn [] (set! db/execute-many original)))))))
