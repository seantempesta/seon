(ns my.canvas-test
  "The single agent-facing canvas surface: reusable hiccup controls plus one
   canonical final dual-render envelope."
  (:require
    [cljs.test :refer [deftest is testing]]
    [clojure.string :as str]
    [my.canvas :as canvas]
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
