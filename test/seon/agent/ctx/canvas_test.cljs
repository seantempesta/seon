(ns seon.agent.ctx.canvas-test
  "Remote canvas acquisition without changing the retained local renderer."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [seon.agent]
    [seon.agent.ctx.canvas :as canvas-ctx]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.eval]
    [seon.render.canvas :as canvas]))

(def ^:private agent-id "tst-canvas-remote")

(def ^:private database
  {:datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :max-tx 42})

(deftest ordinary-formatting-tail-preserves-the-current-caller-bytes
  (let [response
        {:seon.render/hiccup [:div "human"]
         :seon.render/ai "Agent meaning"
         :seon.render.canvas/wired
         {::canvas/source ::canvas/derived
          ::canvas/value 'seon.render.canvas/welcome}}
        expected (@#'canvas-ctx/rendered-canvas-text response nil 2000)
        original-query db/query
        original-pull db/pull
        reads (atom 0)
        fail-read (fn [& _]
                    (swap! reads inc)
                    (throw (js/Error. "ordinary tail read the database")))]
    (try
      (set! db/query fail-read)
      (set! db/pull fail-read)
      (testing "the pure tail consumes only its three ordinary inputs"
        (is (= expected
               (@#'canvas-ctx/rendered-canvas-text response nil 2000)))
        (is (string?
              (@#'canvas-ctx/rendered-canvas-text
                (assoc-in response
                          [:seon.render.canvas/wired ::canvas/value]
                          'my.canvas/current)
                "(defn current [_] {:seon.render/ai \"Agent meaning\"})"
                2000)))
        (is (zero? @reads)))
      (finally
        (set! db/query original-query)
        (set! db/pull original-pull)))))

(deftest remote-members-are-bounded-and-history-is-explicit
  (let [candidate (@#'canvas-ctx/candidate-member agent-id)
        history (@#'canvas-ctx/history-member
                 agent-id [:seon.agent/purpose])]
    (testing "candidate discovery is one bounded relation"
      (is (= protocol/query-operation (::protocol/operation candidate)))
      (is (= [agent-id] (::protocol/arguments candidate)))
      (is (pos? (:datahike.resource/max-work candidate)))
      (is (pos? (:datahike.resource/max-results candidate)))
      (is (pos? (:datahike.resource/max-result-weight candidate)))
      (is (some #{'(pull ?fn [:seon.fn/read-attrs])}
                (tree-seq coll? seq (::protocol/query-form candidate)))))
    (testing "all watched attrs share one history query"
      (is (= protocol/query-operation (::protocol/operation history)))
      (is (not (contains? history ::protocol/history?)))
      (is (= [agent-id [:seon.agent/purpose]]
             (::protocol/arguments history)))
      (is (some #{'(max ?tx)}
                (tree-seq coll? seq (::protocol/query-form history)))))))

(deftest explicit-and-configured-pins-skip-candidate-acquisition
  (async done
    (let [calls (atom 0)
          original-execute-many db/execute-many
          acquire! (fn [agent]
                     (canvas-ctx/acquire-canvas! agent-id agent nil))]
      (set! db/execute-many
            (fn [_]
              (swap! calls inc)
              (js/Promise.reject (js/Error. "pin performed a query"))))
      (-> (js/Promise.all
            #js [(acquire! {:seon.agent/id agent-id
                            :seon.render.canvas/content [:p "explicit"]})
                 (acquire! {:seon.agent/id agent-id
                            :seon.agent/ctx
                            [{:seon.agent.ctx/name :canvas
                              :seon.render.canvas/content [:p "configured"]}]})])
          (.then
            (fn [results]
              (let [explicit (aget results 0)
                    configured (aget results 1)]
                (is (zero? @calls))
                (is (= ::canvas/content
                       (get-in explicit [::canvas/wired ::canvas/source])))
                (is (= ::canvas/configured
                       (get-in configured [::canvas/wired ::canvas/source]))))))
          (.catch (fn [error]
                    (is false (str "pin acquisition threw: " (.-message error)))))
          (.finally (fn []
                      (set! db/execute-many original-execute-many)
                      (done)))))))

(deftest database-member-failures-are-data
  (async done
    (let [original-execute-many db/execute-many
          requests (atom [])
          responses
          (atom
            [{::db/results
              [{::protocol/success? true
                :datahike.query/result
                #{["my.canvas/view"
                   "[:=> [:cat :map] [:map [:seon.render/hiccup [:vector :any]]]]"
                   100 false {:seon.fn/read-attrs [:seon.agent/purpose]}]}}]}
             {::db/results
              [{::protocol/success? false
                ::protocol/error
                {:seon.error/message "history failed"}}]}])]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> (js/Promise.resolve
            (canvas-ctx/acquire-canvas!
              agent-id {:seon.agent/id agent-id} database))
          (.then
            (fn [result]
              (is (= "Canvas history acquisition failed."
                     (:seon.error/message result)))
              (reset! responses
                      [{::db/results
                        [{::protocol/success? false
                          ::protocol/error
                          {:seon.error/message "candidate failed"}}]}])
              (canvas-ctx/acquire-canvas!
                agent-id {:seon.agent/id agent-id} database)))
          (.then
            (fn [result]
              (is (= "Canvas candidate member failed."
                     (:seon.error/message result)))
              (is (= [database
                      (assoc database :history true)
                      database]
                     (mapv ::db/db @requests)))))
          (.catch (fn [error]
                    (is false (str "error-path probe threw: " (.-message error)))))
          (.finally (fn []
                      (set! db/execute-many original-execute-many)
                      (done)))))))

(deftest derived-canvas-carries-the-selected-renderers-read-attributes
  (async done
    (let [original-execute-many db/execute-many
          requests (atom [])
          responses
          (atom
           [{::db/results
             [{::protocol/success? true
               :datahike.query/result
               #{["my.canvas/view"
                  "[:=> [:cat :map] [:map [:seon.render/hiccup [:vector :any]]]]"
                  100 false
                  {:seon.fn/read-attrs
                   [:my.orders/status :my.orders/total]}]}}]}
            {::db/results
             [{::protocol/success? true
               :datahike.query/result
               #{[:my.orders/status 101]
                 [:my.orders/total 102]}}]}])]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> (canvas-ctx/acquire-canvas!
           agent-id {:seon.agent/id agent-id} database)
          (.then
           (fn [result]
             (is (= 'my.canvas/view
                    (get-in result [::canvas/wired ::canvas/value])))
             (is (= #{:my.orders/status :my.orders/total}
                    (:seon.fn/read-attrs result)))
             (is (= [database (assoc database :history true)]
                    (mapv ::db/db @requests))
                 "candidate and history reads stay behind seon.db")
             (is (every?
                  #(every? (fn [member]
                             (= protocol/query-operation
                                (::protocol/operation member)))
                           (::db/members %))
                  @requests)
                 "canvas acquisition sends typed database query members")))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/execute-many original-execute-many)
             (done)))))))

(deftest selected-execution-failures-use-ordinary-error-data
  (let [wired {::canvas/source ::canvas/derived
               ::canvas/value 'my.canvas/broken}
        error {:seon.error/message "renderer failed"
               :seon.error/kind :agent}
        response (@#'canvas-ctx/selected-canvas-response
                   wired {:seon.execution/ok? false
                          :seon.execution/error error})]
    (is (= error (:seon.render/error response)))
    (is (not (contains? response :seon.db/error)))
    (is (str/includes? (:seon.render/ai response) "renderer failed"))))

(deftest missing-selected-canvas-function-gives-exact-repair-guidance
  (let [renderer 'my.orders/view
        wired {::canvas/source ::canvas/derived
               ::canvas/value renderer}
        response
        (@#'canvas-ctx/selected-canvas-response
         wired
         {:seon.execution/ok? false
          :seon.execution/error
          {:seon.error/message
           "The selected function is absent from the current database program."
           :seon.error/kind :agent
           :seon.error/data
           {:seon.execution/function-symbol renderer}}})
        text (@#'canvas-ctx/rendered-canvas-text response nil 2000)]
    (is (str/includes? (get-in response
                               [:seon.render/error :seon.error/message])
                       "Canvas renderer my.orders/view"))
    (is (str/includes? (get-in response
                               [:seon.render/error :seon.error/message])
                       "returns Hiccup through my.canvas/view"))
    (is (str/includes? text "my.orders/view"))
    (is (str/includes? text "absent from the current database program"))
    (is (str/includes? text "Define that exact qualified function"))
    (is (str/includes? text "Define the replacement before removing"))))

(deftest selected-canvas-response-accepts-bare-hiccup
  (let [wired {::canvas/source ::canvas/derived
               ::canvas/value 'my.canvas/view}
        response (@#'canvas-ctx/selected-canvas-response
                  wired
                  {:seon.execution/ok? true
                   :seon.execution/value [:div "canvas"]})]
    (is (= [:div "canvas"] (:seon.render/hiccup response)))
    (is (= wired (:seon.render.canvas/wired response)))))

(deftest selected-canvas-call-uses-the-renderers-system-input
  (let [entity {:seon.agent/id agent-id}
        call (@#'canvas-ctx/selected-canvas-call
              agent-id entity 'seon.render.canvas/welcome)
        input (first (:seon.execution/arguments call))]
    (is (= entity (:seon.agent/entity input)))
    (is (not (contains? input :seon.render/entity))
        "the canvas acquisition key stops at the renderer boundary")))
