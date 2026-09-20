(ns seon.render.test
  "Entity projections for tests; execution remains in seon.test."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.id :as id]
            [seon.repl :as repl]
            [seon.test :as test]
            [seon.render.block :as block]
            [seon.render.route :as route]))

(def ^:private evidence-selector
  '[:db/id :seon.test/sym :seon.test/source :seon.fn/form-span
    :seon.fn/calls])

(defn- evidence [unit]
  (let [entity (or (:seon.render/value unit) unit)]
    (if-let [database (:seon.db/db unit)]
      (let [stored (db/pull database evidence-selector [:seon.test/sym (:seon.test/sym entity)])
            result (if (:seon.test/run entity) entity
                       (test/recorded-result database (:seon.test/sym entity)))]
        (if-let [failure (first (filter :seon.error/at [stored result]))]
          (assoc entity :seon.test/reach-unknown (:seon.error/message failure))
          (merge entity stored result)))
      entity)))

(defn- state [entity]
  (cond
    (pos? (get entity :seon.test/error-count 0)) "error"
    (pos? (get entity :seon.test/fail-count 0)) "fail"
    (and (or (:seon.test/unchanged entity) (pos? (get entity :seon.test/pass-count 0)))
         (= 0 (:seon.test/fail-count entity))
         (= 0 (:seon.test/error-count entity))) "pass"
    :else "unrun or incomplete"))

(defn- failures [entity]
  (sort-by (juxt #(get % :seon.test.failure/reported-file "")
                 #(get % :seon.test.failure/line 0) :seon.test.report/id)
           (:seon.test.failure/reports entity)))

(defn render-ai
  "Read a test's assertion entities and named changed dependencies."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (let [entity (evidence unit) test-name (:seon.test/sym entity)
        text (str "Test " test-name ": " (state entity)
                  (when (and (:seon.db/db unit) (qualified-symbol? test-name))
                    (str "\n" (test/host-text (:seon.db/db unit) test-name)))
                  (if (seq (:seon.test.failure/reports entity))
                    (str "\n" (str/join "\n\n" (map test/failure-text (failures entity))))
                    (when-let [legacy (:seon.test/failure-message entity)] (str "\n" legacy)))
                  (when-let [unknown (:seon.test/reach-unknown entity)] (str "\n" unknown)))]
    (str (when (:seon.test/unchanged entity)
           (str (repl/source-text
                 (list 'clojure.core/identity
                       (select-keys entity [:seon.test/unchanged :seon.test/recorded-basis-t
                                            :seon.test/run-basis-t :seon.test.run/basis-t
                                            :seon.test.run/program-digest :seon.test.run/input-digest]))) "\n"))
         (repl/source-text (list 'clojure.core/identity text)) "\n"
         (repl/source-text (list 'seon.test/recorded-result (list 'seon.db/db)
                                (list 'quote test-name))) "\n"
         (repl/source-text (list 'seon.test/changed-since-green (list 'seon.db/db) (list 'quote test-name))) "\n"
         (repl/source-text (list 'seon.test/host (list 'seon.db/db) (list 'quote test-name))))))

(defn- function-link [target]
  [:a {:href (str (route/path :seon.render.route/namespace
                             {:namespace (namespace (symbol target))})
                 "#" (block/surface-id (keyword target)))} target])

(defn- failure-html [entity failure]
  (let [test-name (:seon.test/sym entity)
        path (:seon.test.failure/reported-file failure)
        line (:seon.test.failure/line failure)
        site (when path (str path ":" line))
        anchor (when path (block/surface-id
                           (keyword "seon.test.failure.site"
                                    (or (:seon.test.report/id failure) (id/id failure)))))
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
     (when (:seon.test/unchanged entity)
       [:p "Unchanged; reused the result recorded at :t "
        (or (:seon.test/recorded-basis-t entity) "unavailable")
        ", tested basis :t " (or (:seon.test/run-basis-t entity) "unavailable") "."])
     (when (and (:seon.db/db unit) (qualified-symbol? test-name))
       [:p {:class "seon-test-host"} (test/host-text (:seon.db/db unit) test-name)])
     (into [:div {:class "seon-test-failures"}]
       (map #(failure-html entity %) (failures entity)))
     (when (and (empty? (:seon.test.failure/reports entity)) (:seon.test/failure-message entity))
       [:pre (:seon.test/failure-message entity)])
     (into [:ul]
       (map (fn [target] [:li (function-link target)])
             (:seon.fn/calls entity)))
     (when-let [database (:seon.db/db unit)]
       (let [changed (test/changed-since-green database test-name)]
         [:section [:h4 "Changed since last green"]
          (if (:seon.error/at changed) [:p (:seon.error/message changed)]
              (into [:ul] (map (fn [target] [:li (function-link target)]) changed)))]))]))
