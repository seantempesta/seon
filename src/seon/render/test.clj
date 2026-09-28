(ns seon.render.test
  "Entity projections for tests; execution remains in seon.test."
  (:require [seon.db :as db]
            [seon.id :as id]
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

(def ^:private failure-keys
  "What one failed assertion says: its kind, site, contexts and claim."
  [:seon.test.failure/type :seon.test.failure/reported-file :seon.test.failure/line
   :seon.test.failure/testing-contexts :seon.test.failure/message
   :seon.test.failure/expected :seon.test.failure/actual])

(defn render-ai
  "A test's result as data: its state, where it runs, its failed assertions
  and, unless green, the definitions changed since it last passed."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render.test/view]}
  [unit]
  (let [entity (evidence unit) test-name (:seon.test/sym entity) database (:seon.db/db unit)
        read? (and database (qualified-symbol? test-name))
        shown-state (state entity)]
    (cond-> (merge {:seon.test/sym test-name :seon.render.test/state shown-state}
                   (select-keys entity [:seon.test/unchanged :seon.test/recorded-basis-t :seon.test/run-basis-t
                                        :seon.test/failure-message :seon.test/reach-unknown]))
      ;; the host report without the symbol the view already names
      read? (assoc :seon.render.test/host (let [host (test/host database test-name)]
                                            (cond-> host (map? host) (dissoc :seon.test/sym))))
      (seq (:seon.test.failure/reports entity))
      (assoc :seon.test.failure/reports (mapv #(select-keys % failure-keys) (failures entity)))
      (and read? (not= "pass" shown-state))
      (assoc :seon.render.test/changed-since-green (test/changed-since-green database test-name)))))

(defn render-form
  "The read that finds this test's recorded result again. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'seon.test/recorded-result '(seon.db/db)
        (list 'quote (:seon.test/sym (or (:seon.render/value unit) unit)))))

(defn- member-outcome
  "One run member's outcome: :error, :fail, :pass, or :unfinished before it completed."
  {:malli/schema [:=> [:cat :map] [:enum :error :fail :pass :unfinished]]}
  [member]
  (cond
    (not (:seon.test.member/completed-tx member)) :unfinished
    (pos? (get member :seon.test.member/error-count 0)) :error
    (pos? (get member :seon.test.member/fail-count 0)) :fail
    :else :pass))

(defn render-run-ai
  "A test run as data: when, at what basis on which branch, how many members
  passed, failed, erred or did not finish, and which were red by name."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render.test/run-view]}
  [unit]
  (let [run (or (:seon.render/value unit) unit)
        outcomes (group-by member-outcome (:seon.test.run/members run))
        red (sort (map :seon.test.member/symbol (concat (:error outcomes) (:fail outcomes))))]
    (cond-> (assoc (select-keys run [:seon.test.run/id :seon.test.run/at :seon.test.run/basis-t :seon.test.run/branch])
                   :seon.render.test/outcomes (update-vals outcomes count))
      (seq red) (assoc :seon.render.test/red (vec red)))))

(defn render-run-form
  "The read that finds this run whole again. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'seon.db/pull ''[*] [:seon.test.run/id (:seon.test.run/id (or (:seon.render/value unit) unit))]))

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
