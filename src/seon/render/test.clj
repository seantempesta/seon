(ns seon.render.test
  "Entity projections for tests; execution remains in seon.test."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.repl :as repl]
            [seon.test :as test]
            [seon.render.block :as block]
            [seon.render.route :as route]))

(def ^:private evidence-selector
  '[:db/id :seon.test/sym :seon.test/source :seon.test/pass-count :seon.test/fail-count
    :seon.test/error-count :seon.test/run-basis-t :seon.test/reach-unknown
    :seon.test/failure-message
    :seon.fn/form-span
    {:seon.test/failures [* {:seon.test.failure/file [:db/id :seon.fn.file/path]}]}
    {:seon.test/run [:seon.test.run/id :seon.test.run/branch]}
    {:seon.fn/calls [:seon.fn/sym]}])

(defn- evidence [unit]
  (let [entity (or (:seon.render/value unit) unit)]
    (if-let [database (:seon.db/db unit)]
      (let [stored (db/pull database evidence-selector [:seon.test/sym (:seon.test/sym entity)])]
        (if (:seon.error/kind stored) (assoc entity :seon.test/reach-unknown (:seon.error/message stored))
            (merge entity stored)))
      entity)))

(defn- state [entity]
  (cond
    (pos? (get entity :seon.test/error-count 0)) "error"
    (pos? (get entity :seon.test/fail-count 0)) "fail"
    (and (pos? (get entity :seon.test/pass-count 0))
         (= 0 (:seon.test/fail-count entity))
         (= 0 (:seon.test/error-count entity))) "pass"
    :else "unrun or incomplete"))

(defn- failures [entity]
  (sort-by (juxt #(get-in % [:seon.test.failure/file :seon.fn.file/path] "")
                 #(get % :seon.test.failure/line 0) :seon.test.failure/ordinal)
           (:seon.test/failures entity)))

(defn render-ai
  "Read a test's assertion entities and named changed dependencies."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (let [entity (evidence unit) test-name (:seon.test/sym entity)
        text (str "Test " test-name ": " (state entity)
                  (if (seq (:seon.test/failures entity))
                    (str "\n" (str/join "\n\n" (map test/failure-text (failures entity))))
                    (when-let [legacy (:seon.test/failure-message entity)] (str "\n" legacy)))
                  (when-let [unknown (:seon.test/reach-unknown entity)] (str "\n" unknown)))]
    (str (repl/source-text (list 'clojure.core/identity text)) "\n"
         (repl/source-text (list 'seon.db/pull (list 'quote evidence-selector)
                                [:seon.test/sym test-name])) "\n"
         (repl/source-text (list 'seon.test/changed-since-green (list 'seon.db/db) test-name)))))

(defn- function-link [target]
  [:a {:href (str (route/path :seon.render.route/namespace
                             {:namespace (namespace (symbol target))})
                 "#" (block/surface-id (keyword target)))} target])

(defn- failure-html [entity failure]
  (let [test-name (:seon.test/sym entity)
        path (get-in failure [:seon.test.failure/file :seon.fn.file/path])
        line (:seon.test.failure/line failure)
        file-id (get-in failure [:seon.test.failure/file :db/id])
        site (when path (str path ":" line))
        anchor (when path (block/surface-id
                           (keyword "seon.test.failure.site"
                                    (str file-id "-" line "-" (:seon.test.failure/id failure)))))
        href (when path
               (str (route/path :seon.render.route/namespace
                               {:namespace (namespace (symbol test-name))}) "#" anchor))]
    [:section {:class "seon-test-failure"}
     [:h4 (name (:seon.test.failure/type failure)) " "
      (if href [:a {:href href :data-file path :data-line line} site] "source site unavailable")]
     [:pre (test/failure-text failure)]
     (when path
       [:details {:id anchor :open true :data-file path :data-line line
                  :data-source-start (first (:seon.fn/form-span entity))
                  :data-source-end (second (:seon.fn/form-span entity))}
        [:summary site]
        [:pre (or (:seon.test/source entity) "The enclosing test source is unavailable.")]])]))

(defn render-html
  "Render assertion claims and links to source sites and changed functions."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [entity (evidence unit) test-name (:seon.test/sym entity)]
    [:section {:class "seon-family-entry seon-test"}
     [:h3 (function-link test-name) " — " (state entity)]
     (into [:div {:class "seon-test-failures"}]
       (map #(failure-html entity %) (failures entity)))
     (when (and (empty? (:seon.test/failures entity)) (:seon.test/failure-message entity))
       [:pre (:seon.test/failure-message entity)])
     (into [:ul]
       (keep (fn [{target :seon.fn/sym}] (when target [:li (function-link target)]))
             (:seon.fn/calls entity)))
     (when-let [database (:seon.db/db unit)]
       (let [changed (test/changed-since-green database test-name)]
         [:section [:h4 "Changed since last green"]
          (if (:seon.error/kind changed) [:p (:seon.error/message changed)]
              (into [:ul] (map (fn [{target :seon.fn/sym}] [:li (function-link target)]) changed)))]))]))
