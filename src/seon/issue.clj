(ns seon.issue
  "Issues connect authored problem statements to program identities."
  (:require [seon.db :as db]
            [seon.repl :as repl]))

(defn status
  "Read the issue and test outcomes; verification follows assignment."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]]]
                  [:or :map :seon.error/value]]}
  [{database :seon.db/db issue-id :seon.issue/id}]
  (let [row (db/pull database '[:db/id :seon.issue/id :seon.issue/title :seon.issue/status :seon.issue/severity
                                  :seon.issue/problem :seon.issue/path :seon.issue/opened :seon.issue/commits
                                  :seon.issue/agent :seon.issue/budget :seon.issue/resolved-tx
                                  {:seon.issue/members [:seon.issue/id]}
                                  {:seon.issue/tests [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count
                                                       {:seon.test/run [:seon.test.run/id :seon.test.run/basis-t]}]}
                                  {:seon.issue/functions [:seon.fn/sym {:seon.fn/ns [:seon.ns/name]}]}
                                  {:seon.issue/errors [:seon.error/signature {:seon.error/occurrences [:seon.error.occurrence/count]}]}]
                     [:seon.issue/id issue-id])]
    (if-not (:seon.issue/title row)
      {:seon.error/kind :seon.issue/not-found :seon.error/message (str "No current issue " issue-id)}
      (let [started (db/q '[:find ?tx . :in $ ?issue :where [?issue :seon.issue/agent _ ?tx]]
                          database (:db/id row))
            test-rows (mapv
                       (fn [test-value]
                         (let [basis (get-in test-value [:seon.test/run :seon.test.run/basis-t])
                               state (cond
                                       (not (:seon.test/run test-value)) :unrun
                                       (or (pos? (get test-value :seon.test/fail-count 0))
                                           (pos? (get test-value :seon.test/error-count 0))) :red
                                       (and started basis (>= basis started)
                                            (pos? (get test-value :seon.test/pass-count 0))
                                            (= 0 (:seon.test/fail-count test-value))
                                            (= 0 (:seon.test/error-count test-value))) :verified
                                       :else :unverified)]
                           (assoc test-value :seon.issue.test/state state)))
                       (sort-by :seon.test/sym (:seon.issue/tests row)))]
        (assoc row :seon.issue/tests test-rows
                   :seon.issue/check-form
                   (list 'my.test/check {:seon.test/changed (mapv :seon.test/sym test-rows)}))))))

(defn render-ai
  "Emit the ordinary read for this issue; its tests define completion."
  {:malli/schema [:=> [:cat [:or :seon.issue/issue :seon.render/unit]] :seon.render/source]}
  [unit]
  (let [row (or (:seon.render/value unit) unit)]
    (str ";; My issue. Its tests define done; (my.test/check ...) runs them.\n"
         (repl/source-text (list 'my.issue/status {:seon.issue/id (:seon.issue/id row)})))))

(defn render-html
  "Show the issue and current test outcomes as one block."
  {:malli/schema [:=> [:cat [:or :seon.issue/issue :seon.render/unit]] :seon.render/hiccup]}
  [unit]
  (let [row (or (:seon.render/value unit) unit)
        view (if-let [database (:seon.db/db unit)]
               (status {:seon.db/db database :seon.issue/id (:seon.issue/id row)}) row)]
    [:section {:class "seon-family-entry seon-issue"}
     [:h3 (:seon.issue/title view)]
     [:p (:seon.issue/problem view)]
     [:p (str "Status: " (:seon.issue/status view))]
     (into [:ul] (map (fn [test-value]
                       [:li (str (:seon.test/sym test-value) " — "
                                 (get test-value :seon.issue.test/state :unrun))])
                     (:seon.issue/tests view)))]))

