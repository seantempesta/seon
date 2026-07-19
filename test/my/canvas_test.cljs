(ns my.canvas-test
  "The single agent-facing canvas surface: reusable hiccup controls plus one
   canonical final dual-render envelope."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [my.canvas :as canvas]
    [seon.db :as db]
    [seon.web.reactive.transform :as transform]))

(deftest button-builds-reusable-hiccup-and-routes-through-call-gate
  (let [h (canvas/button {:my.canvas/label "Approve"
                          :my.canvas/handler 'approve!
                          :my.canvas/data {:my.order/id "o-7"}})
        [_ attrs label] h]
    (is (= :button (first h)))
    (is (= (list 'approve! {:my.order/id "o-7"}) (:on-click attrs)))
    (is (= "Approve" label))
    (is (str/includes? (:class attrs) "cursor-pointer"))
    (let [wired (transform/transform-hiccup "x" 'my.agent.x h)
          action (:data-on:click (second wired))]
      (is (str/includes? action "/agent/x/call?fn=my.agent.x%2Fapprove!"))
      (is (str/includes? action "args="))
      (is (nil? (:on-click (second wired)))))))

(deftest controls-compose-as-hiccup-without-envelope-extraction
  (let [input (canvas/input {:my.canvas/field :my.demo/note
                             :my.canvas/label "Note"
                             :my.canvas/placeholder "type…"})
        select (canvas/select {:my.canvas/field :my.demo/tier
                               :my.canvas/options [["free" "Free"] ["pro" "Pro"]]})
        toggle (canvas/toggle {:my.canvas/field :my.demo/live
                               :my.canvas/label "Live updates"})
        form (canvas/form {:my.canvas/handler 'save-note!
                           :my.canvas/label "Save"
                           :my.canvas/controls [input select toggle]})
        rendered (pr-str form)]
    (is (= :form (first form)))
    (is (= 'save-note! (:on-submit (second form))))
    (is (= 3 (count (re-seq #"seon_[A-Za-z0-9_-]+" rendered))))
    (is (str/includes? rendered ":type \"checkbox\""))
    (is (some #(and (vector? %) (= :button (first %))) (drop 2 form)))
    (let [wired (transform/transform-hiccup "x" 'my.agent.x form)]
      (is (str/includes? (:data-on:submit (second wired))
                         "/agent/x/call?fn=my.agent.x%2Fsave-note!")))))

(deftest view-is-the-one-dual-render-boundary
  (let [h [:section [:h2 "Status"]]
        response (canvas/view {:my.canvas/content h
                               :my.canvas/ai "Current status."})]
    (is (= h (:seon.render/hiccup response)))
    (is (= "Current status." (:seon.render/ai response)))))

(deftest view-omits-an-absent-ai-twin
  (let [h [:section [:h2 "Visual only"]]
        response (canvas/view {:my.canvas/content h})]
    (is (= {:seon.render/hiccup h} response))))

(deftest canvas-content-uses-the-eval-current-namespace
  (is (= 'my.orders/dashboard
         (@#'canvas/qualify-content 'dashboard :my.orders)))
  (is (= 'my.shared/dashboard
         (@#'canvas/qualify-content 'my.shared/dashboard :my.orders)))
  (is (= [:h2 "literal"]
         (@#'canvas/qualify-content [:h2 "literal"] :my.orders))))

(deftest show-refuses-a-mistyped-renderer-before-changing-the-canvas
  (async done
    (let [original-pull db/pull
          original-transact db/transact!
          transacts (atom 0)
          database {:db-name "test"
                    :t 42
                    :as-of nil
                    :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}]
      (set! db/pull
            (fn
              ([_request] (js/Promise.resolve nil))
              ([_pattern _ref] (js/Promise.resolve nil))
              ([_database _pattern _ref] (js/Promise.resolve nil))))
      (set! db/transact! (fn [& _requests]
                           (swap! transacts inc)
                           (js/Promise.resolve nil)))
      (-> (canvas/show! {:my.canvas/content 'mistyped
                         :seon.agent/id "agent-1"
                         :seon.eval/ns 'my.orders
                         :seon.db/db database})
          (.then
           (fn [result]
             (is (= :agent (:seon.error/kind result)))
             (is (str/includes? (:seon.error/message result)
                                "my.orders/mistyped"))
             (is (str/includes? (:seon.error/message result)
                                "returns Hiccup through my.canvas/view"))
             (is (zero? @transacts))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/pull original-pull)
             (set! db/transact! original-transact)
             (done)))))))

(deftest show-pins-an-existing-renderer-at-the-injected-database-value
  (async done
    (let [original-pull db/pull
          original-transact db/transact!
          request (atom nil)
          renderer 'my.orders/view
          database {:db-name "test"
                    :t 42
                    :as-of nil
                    :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}]
      (set! db/pull
            (fn
              ([_request]
               (js/Promise.resolve {:seon.fn/sym renderer}))
              ([_pattern _ref]
               (js/Promise.resolve {:seon.fn/sym renderer}))
              ([_database _pattern _ref]
               (js/Promise.resolve {:seon.fn/sym renderer}))))
      (set! db/transact! (fn [& values]
                           (reset! request (first values))
                           (js/Promise.resolve
                            {:db-before database
                             :db-after database
                             :tx-data [] :tempids {} :tx-meta {}})))
      (-> (canvas/show! {:my.canvas/content renderer
                         :seon.agent/id "agent-1"
                         :seon.eval/ns 'my.orders
                         :seon.db/db database})
          (.then
           (fn [_]
             (is (= database (:seon.db/db @request)))
             (is (= [{:seon.agent/id "agent-1"
                      :seon.render.canvas/content renderer}]
                    (:seon.db/tx-data @request)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/pull original-pull)
             (set! db/transact! original-transact)
             (done)))))))

(deftest state-awaits-the-remote-pull-before-returning-data
  (async done
    (let [original db/pull
          database {:db-name "test"
                    :t 42
                    :as-of nil
                    :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}
          request (atom nil)]
      (set! db/pull
            (fn
              ([input]
               (reset! request input)
               (js/Promise.resolve {:my.demo/count 3}))
              ([_database _pattern _ref]
               (js/Promise.resolve {:my.demo/count 3}))))
      (-> (canvas/state {:my.canvas/attributes [:my.demo/count]
                         :seon.db/db database
                         :seon.agent/id "agent-1"})
          (.then
           (fn [value]
             (is (= {:my.demo/count 3} value))
             (is (= database (:seon.db/db @request)))
             (is (= [:seon.agent/id "agent-1"] (:seon.db/ref @request)))
             true))
          (.catch (fn [exception] (is false (str exception))))
          (.finally
           (fn []
             (set! db/pull original)
             (done)))))))
