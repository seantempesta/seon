(ns my.plan-test
  "Fact-first plan derivation, transitions, and current-state rendering."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [my.plan :as plan]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema]
            [seon.test-support :as support]))

(def ^:private t0-ms 1786500000000)

(defn- at
  [offset]
  (java.util.Date. (long (+ t0-ms offset))))

(defn- with-plan
  [f]
  (support/with-database
    (fn [connection]
      (db/transact! connection
                    [{:db/id "plan-ns"
                      :seon.ns/name 'fixture.plan}
                     {:seon.cluster.agent/id "alice"
                      :seon.cluster.agent/namespace "plan-ns"}
                     {:seon.cluster.agent/id "bob"}])
      (f connection))))

(defn- add
  ([connection id title]
   (add connection id title {}))
  ([connection id title more]
   (plan/add! (merge {:my.plan.item/id id
                      :my.plan.item/title title}
                     more)
              connection "alice")))

(deftest authored-status-is-derived-from-presence-and-edges
  (with-plan
    (fn [connection]
      (add connection "root" "Ship"
           {:my.plan/anchor? true})
      (add connection "prepare" "Prepare"
           {:my.plan.item/parent [:my.plan.item/id "root"]})
      (add connection "verify" "Verify"
           {:my.plan.item/parent [:my.plan.item/id "root"]
            :my.plan.item/needs #{[:my.plan.item/id "prepare"]}})
      (is (= ["prepare"] (mapv :my.plan.item/id
                                (plan/ready @connection "alice"))))
      (let [view (plan/plan @connection "alice")]
        (is (= [:my.plan.item/id "root"] (:my.plan/anchor view)))
        (is (= ["prepare"] (mapv :my.plan.item/id (:my.plan/ready view))))
        (is (= ["verify"] (mapv :my.plan.item/id (:my.plan/blocked view)))))
      (plan/complete! "prepare" (at 1) connection "alice")
      (is (= ["verify"] (mapv :my.plan.item/id
                               (plan/ready @connection "alice"))))
      (testing "no status attribute exists or lands on an item"
        (is (not (contains? (:schema @connection) :my.plan.item/status)))
        (is (nil? (:my.plan.item/status
                   (db/pull @connection '[*]
                            [:my.plan.item/id "verify"]))))))))

(deftest a-drained-parent-becomes-ready-for-verify-and-close
  (let [check
        (tc/quick-check
         12
         (prop/for-all [child-count (gen/choose 1 4)]
           (support/with-database
             (fn [connection]
               (let [agent-id (str "agent-" child-count)
                     root-id (str "root-" child-count)]
                 (db/transact! connection
                               [{:seon.cluster.agent/id agent-id}])
                 (plan/add! {:my.plan.item/id root-id
                             :my.plan.item/title "Verify the whole"}
                            connection agent-id)
                 (doseq [index (range child-count)]
                   (plan/add!
                    {:my.plan.item/id (str "child-" child-count "-" index)
                     :my.plan.item/title (str "Child " index)
                     :my.plan.item/parent [:my.plan.item/id root-id]}
                    connection agent-id))
                 (let [before (set (map :my.plan.item/id
                                        (plan/ready @connection agent-id)))]
                   (doseq [index (range child-count)]
                     (plan/complete! (str "child-" child-count "-" index)
                                     (at index) connection agent-id))
                   (and (= child-count (count before))
                        (not (contains? before root-id))
                        (= [root-id]
                           (mapv :my.plan.item/id
                                 (plan/ready @connection agent-id)))))))))
         :seed 49134711)]
    (is (:result check) (pr-str check))))

(deftest derived-obligations-remain-native-fact-queries
  (with-plan
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.message/id "question"
         :seon.cluster.message/to [:seon.cluster.agent/id "alice"]
         :seon.cluster.message/content "Answer the question."
         :seon.cluster.message/at (at 1)}
        {:seon.cluster.run/id "open-run"
         :seon.cluster.run/agent [:seon.cluster.agent/id "alice"]
         :seon.cluster.run/opened-at (at 2)}
        {:seon.test/sym "fixture.plan/failing"
         :seon.test/ns [:seon.ns/name 'fixture.plan]
         :seon.test/pass-count 0
         :seon.test/fail-count 1
         :seon.test/error-count 0
         :seon.test/run-basis-t (db/basis-t @connection)
         :seon.test/run-at (at 3)}])
      (let [view (plan/plan @connection "alice")
            sources (mapv :my.plan/obligation-source
                          (:my.plan/obligations view))]
        (is (= [:message :run :test] sources))
        (is (empty? (:my.plan/ready view)))
        (is (empty? (:my.plan/blocked view))))
      (db/transact!
       connection
       [{:seon.cluster.run/id "answer-run"
         :seon.cluster.run/agent [:seon.cluster.agent/id "alice"]
         :seon.cluster.run/trigger [:seon.cluster.message/id "question"]
         :seon.cluster.run/opened-at (at 4)}])
      (is (= [:run :run :test]
             (mapv :my.plan/obligation-source
                   (:my.plan/obligations
                    (plan/plan @connection "alice"))))))))

(deftest rebirth-uses-only-current-facts-and-honest-elision
  (with-plan
    (fn [connection]
      (let [limit (:seon.config.render.agent/max-children (config/defaults))]
        (add connection "open" "Current work" {:my.plan/anchor? true})
        (doseq [index (range (+ limit 3))]
          (let [id (format "done-%02d" index)]
            (add connection id (str "Completed " index))
            (plan/complete! id (at index) connection "alice")))
        (let [current (plan/plan @connection "alice")
              recent (:my.plan/recent-completions current)
              older (:my.plan/older-completions current)
              ai (plan/render-plan-ai current)
              html (plan/render-plan-html current)]
          (testing "the view reconstructs from the current database value"
            (is (= ["open"] (mapv :my.plan.item/id (:my.plan/ready current))))
            (is (= limit (count recent)))
            (is (= 3 (:seon.print/omitted older)))
            (is (= [:seon.cluster.agent/id "alice"]
                   (:seon.print/requery-id older)))
            (is (= :children (:seon.print/elision-unit older))))
          (testing "declared shapes accept the rebuilt values and renders"
            (is (seon.schema/valid-candidate-value? :my.plan/view current))
            (is (seon.schema/valid-candidate-value? :seon.render/ai ai))
            (is (seon.schema/valid-candidate-value? :seon.render/hiccup html))
            (is (str/includes? ai "Current work"))
            (is (= :section (first html)))))))))
