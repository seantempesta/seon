(ns seon.render.entity-pairs-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.ns :as render.ns]
            [seon.render.test :as render.test]
            [seon.render.walk :as walk]
            [seon.test-support :as support]))

(defn- exercise-pair
  [entity-lookup expected-ai expected-html]
  (support/with-database
   (fn [connection]
     (let [report
           (db/transact! connection
             [{:seon.ns/name 'entity-pairs.fixture}
              {:seon.agent/id "entity-pairs-agent"}
              {:seon.fn/sym "entity-pairs.fixture/function"
               :seon.schema.admission/source :core
               :seon.fn/ns [:seon.ns/name 'entity-pairs.fixture]}
              {:seon.test/sym "entity-pairs.fixture/test"
               :seon.schema.admission/source :core
               :seon.fn/calls [[:seon.fn/sym "entity-pairs.fixture/function"]]}
              {:seon.issue/id "entity-pairs-issue"
               :seon.issue/title "Render linked entities"
               :seon.issue/problem "Verify the entity pairs"
               :seon.issue/status :open :seon.issue/severity :cleanup
               :seon.issue/agent [:seon.agent/id "entity-pairs-agent"]
               :seon.issue/functions [[:seon.fn/sym "entity-pairs.fixture/function"]]
               :seon.issue/tests [[:seon.test/sym "entity-pairs.fixture/test"]]}])]
       (is (:db-after report) (pr-str report)))
     (let [database (db/db connection)
           entity (db/pull database '[*] entity-lookup)
           ctx (support/fork-cluster-ctx connection)
           request {:seon.db/db database :seon.sci.eval/ctx ctx
                    :seon.render/value entity
                    :seon.render/profile (render/agent-render-profile (config/defaults))
                    :seon.sci.admit/caps (config/result-caps (config/defaults))
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :panic
                    :seon.render/distance 1
                    :seon.render.walk/lookup [:seon.issue/id "entity-pairs-issue"]}
           acquisition (walk/root-acquisition request)]
       (is (= 1 (get-in acquisition [:seon.render.walk/members entity-lookup
                                     :seon.render.walk/found-depth])))
       (doseq [[output expected] [[:seon.render/ai expected-ai]
                                  [:seon.render/html expected-html]]]
         (let [request (assoc request :seon.render/output output)
               decision (render/selection request)
               rendered (if (= output :seon.render/ai)
                          (render/render-ai request) (render/render-html request))
               units (walk/neighborhood request)
               unit (first (filter #(= entity-lookup (:seon.render.walk/lookup %)) units))]
           (is (= expected (:seon.render.selection/selected decision)) (pr-str decision))
           (is (if (= output :seon.render/ai) (string? rendered) (hiccup/hiccup? rendered))
               (pr-str rendered))
           (is unit (pr-str units))
           (is (nil? (:seon.error/value unit)) (pr-str unit))
           (is (= rendered (:seon.render/output unit)) (pr-str unit))))
       (when (= :seon.fn/sym (first entity-lookup))
         (let [captured (atom [])
               source (binding [db/*read-evidence-sink* captured]
                        (render.ns/function-ai {:seon.db/db database :seon.render/value entity}))]
           (is (empty? @captured) "Rendering queries must not execute them")
           (is (= '(do (doc entity-pairs.fixture/function))
                  (read-string (str "(do\n" source "\n)"))))
           (is (str/includes? source "seon.fn/tests-reaching"))
           (is (str/includes? source ":_calls"))
           (is (not (str/includes? source "defn")))))
       (when (= :seon.test/sym (first entity-lookup))
         (doseq [[facts expected] [[{} "unrun or incomplete"]
                                   [{:seon.test/pass-count 1 :seon.test/fail-count 0 :seon.test/error-count 0} "pass"]
                                   [{:seon.test/fail-count 1} "fail"]
                                   [{:seon.test/error-count 1} "error"]]]
           (let [value (merge entity facts)
                 test-name (:seon.test/sym value)
                 source (render.test/render-ai {:seon.render/value value})
                 [summary evidence changed] (rest (read-string (str "(do\n" source "\n)")))]
             (is (every? #(not (str/starts-with? (str/triml %) ";")) (str/split-lines source))
                 "Rendered source carries no comment-prefixed prose")
             (is (= 'clojure.core/identity (first summary)) (pr-str summary))
             (is (= (str "Test " test-name ": " expected)
                    (first (str/split-lines (second summary))))
                 (pr-str summary))
             (is (= 'seon.db/pull (first evidence)) (pr-str evidence))
             (is (= [:seon.test/sym test-name] (last evidence)) (pr-str evidence))
             (is (= (list 'seon.test/changed-since-green (list 'seon.db/db) test-name) changed)
                 (pr-str changed))
             (is (hiccup/hiccup? (render.test/render-html {:seon.render/value value}))))))))))

(deftest function-entity-pair-is-selected-through-the-issue-walk
  (exercise-pair [:seon.fn/sym "entity-pairs.fixture/function"]
                 'seon.render.ns/function-ai 'seon.render.ns/function-html))

(deftest test-entity-pair-is-total-through-the-issue-walk
  (exercise-pair [:seon.test/sym "entity-pairs.fixture/test"]
                 'seon.render.test/render-ai 'seon.render.test/render-html))
