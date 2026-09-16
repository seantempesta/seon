(ns seon.render.test
  "Entity projections for tests; execution remains in seon.test."
  (:require [seon.db :as db]
            [seon.repl :as repl]
            [seon.render.route :as route]))

(defn render-ai
  "Read a test's stored evidence and show exact calls for running it."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (let [entity (or (:seon.render/value unit) unit)
        test-name (:seon.test/sym entity)
        state (cond
                (pos? (get entity :seon.test/error-count 0)) "error"
                (pos? (get entity :seon.test/fail-count 0)) "fail"
                (and (number? (:seon.test/pass-count entity))
                     (= 0 (:seon.test/fail-count entity))
                     (= 0 (:seon.test/error-count entity))) "pass"
                :else "unrun or incomplete")]
    (str ";; Test " test-name ": " state ". Read its latest evidence and called functions.\n"
         ";; Run with explicit connection conn: "
         (pr-str (list 'seon.test/run (list 'var (symbol test-name)) 'conn)) "\n"
         ";; Check from my agent: "
         (pr-str (list 'my.test/check {:seon.test/changed [test-name]})) "\n"
         (repl/source-text
           (list 'seon.db/pull
             (list 'quote
               (cond-> [:seon.test/sym :seon.test/pass-count :seon.test/fail-count
                        :seon.test/error-count :seon.test/run-basis-t
                        :seon.test/failing-assertions :seon.test/failure-message
                        {:seon.test/run [:seon.test.run/id :seon.test.run/basis-t]}
                        {:seon.fn/calls [:seon.fn/sym]}]
                 (:seon.test/reach-digest entity) (conj :seon.test/reach-digest)))
             [:seon.test/sym test-name])))))

(defn render-html
  "Render stored test evidence and namespace links for its called functions."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [entity (or (:seon.render/value unit) unit)
        test-name (:seon.test/sym entity)
        calls (if-let [database (:seon.db/db unit)]
                (:seon.fn/calls
                  (db/pull database '[{:seon.fn/calls [:seon.fn/sym]}]
                           [:seon.test/sym test-name]))
                (:seon.fn/calls entity))]
    [:section {:class "seon-family-entry seon-test"}
     [:h3 [:a {:href (route/path :seon.render.route/namespace
                       {:namespace (namespace (symbol test-name))})} test-name]]
     [:pre (render-ai unit)]
     (into [:dl]
       (for [[attribute value]
             (select-keys entity
               [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count
                :seon.test/run-basis-t :seon.test/reach-digest
                :seon.test/failing-assertions :seon.test/failure-message])]
         [:div [:dt (str attribute)] [:dd [:pre (pr-str value)]]]))
     (into [:ul]
       (for [called calls
             :let [target (when (map? called) (:seon.fn/sym called))]
             :when target]
         [:li [:a {:href (route/path :seon.render.route/namespace
                           {:namespace (namespace (symbol target))})} target]]))]))
