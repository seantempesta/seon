(ns seon.sanitize-canvas-convergence-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.render.sanitize :as sanitize]
            [seon.render.markdown.parse :as mdp]
            [seon.render.surface.presence :as presence]))

(defn- demo-2arg-canvas [_db agent]
  [:div {:class "card" :onclick "evil()"}
   [:script "boom"]
   [:h3 (str "Hello " (:seon.agent/label agent))]
   [:button {:type "button" :on-click '(seon.web/navigate-url! "/chat")} "Chat"]
   [:button {:type "button" :on-click '(clojure.core/eval 42)} "Bad"]])

(defn- demo-1arg-hiccup-renderer [{ag :seon.agent/entity}]
  {:seon.ui/hiccup [:div {:class "card"} [:p (str "1-arg " (:seon.agent/label ag))]]})

(deftest shared-allowlist-sanitizer-and-action-policy-test
  (testing "Default strict mode strips scripts, unsafe URLs, onclick, and buttons"
    (is (= [:div {:class "box"} "ok"]
           (sanitize/sanitize-hiccup [:div {:class "box" :onclick "evil()" :data-on:click "x"} [:script "x"] "ok"])))
    (is (= [:a "click"]
           (sanitize/sanitize-hiccup [:a {:href "javascript:alert(1)"} "click"])))
    (is (= [:div "safe"]
           (mdp/sanitize-hiccup [:div {:style "background:expression(1)"} [:iframe {:src "https://x"}] "safe"])))
    (is (nil? (sanitize/sanitize-hiccup [:button {:on-click '(seon.web/navigate-url! "/chat")} "Go"]))))
  (testing "Canvas allow-seon-actions? mode permits allowlisted seon.web actions only"
    (is (= [:button {:type "button" :on-click '(seon.web/navigate-url! "/chat")} "Go"]
           (sanitize/sanitize-hiccup [:button {:type "button" :on-click '(seon.web/navigate-url! "/chat")} "Go"]
                                     {:allow-seon-actions? true})))
    (is (= [:button "Bad"]
           (sanitize/sanitize-hiccup [:button {:on-click '(clojure.core/eval 1)} "Bad"]
                                     {:allow-seon-actions? true})))
    (is (= [:button "BadStr"]
           (sanitize/sanitize-hiccup [:button {:on-click "alert(1)"} "BadStr"]
                                     {:allow-seon-actions? true})))))

(deftest canvas-presence-2arg-1arg-and-user-mode-test
  (testing "2-arg reactive canvas-fn sanitizes XSS & disallows non-seon.web actions"
    (let [node (presence/render-live-canvas-presence
                {:main-db nil :branch-db nil :viewing-id :agent/root
                 :agent {:seon.agent/id :agent/root :seon.agent/label "Root"
                         :seon.agent/canvas-fn 'seon.sanitize-canvas-convergence-test/demo-2arg-canvas}
                 :expert-mode? false})
          s (pr-str node)]
      (is (vector? node))
      (is (clojure.string/includes? s "Hello Root"))
      (is (clojure.string/includes? s "seon.web/navigate-url!"))
      (is (not (clojure.string/includes? s "evil()")))
      (is (not (clojure.string/includes? s ":script")))
      (is (not (clojure.string/includes? s "clojure.core/eval")))
      (is (not (clojure.string/includes? s "Live Agent Canvas")))))
  (testing "1-arg standard ns-map -> :seon.ui/hiccup renderer also works as canvas-fn"
    (let [node (presence/render-live-canvas-presence
                {:main-db nil :branch-db nil :viewing-id :agent/root
                 :agent {:seon.agent/id :agent/root :seon.agent/label "Root"
                         :seon.agent/canvas-fn 'seon.sanitize-canvas-convergence-test/demo-1arg-hiccup-renderer}
                 :expert-mode? true})
          s (pr-str node)]
      (is (clojure.string/includes? s "1-arg Root"))
      (is (clojure.string/includes? s "Live Agent Canvas")))))
