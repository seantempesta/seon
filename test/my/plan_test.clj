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
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(def ^:private t0-ms 1786500000000)

(defn- at
  [offset]
  (java.util.Date. (long (+ t0-ms offset))))

(deftest plan-terminal-formatters-preserve-database-errors
  (let [failure {:seon.error/kind ::read-failed
                 :seon.error/message "plan read failed"}]
    (is (= failure (plan/format-item-ai failure)))
    (is (= failure (plan/format-ready-items-ai failure)))
    (is (= failure (plan/format-plan-ai failure)))))

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

(deftest plan-items-are-ordinary-agent-linked-database-facts
  (with-plan
    (fn [connection]
      (let [item-id "ordinary-fact"]
        (db/transact!
         connection
         [{:db/id "new-plan-item"
           :my.plan.item/id item-id
           :my.plan.item/title "Inspect the facts"
           :my.plan.item/agent [:seon.cluster.agent/id "alice"]}])
        (let [pulled
              (db/pull
               @connection
               [{:my.plan.item/_agent
                 [:my.plan.item/id :my.plan.item/title]}]
               [:seon.cluster.agent/id "alice"])]
          (is (= [{:my.plan.item/id item-id
                   :my.plan.item/title "Inspect the facts"}]
                 (:my.plan.item/_agent pulled))))
        (db/transact!
         connection
         [{:db/id [:my.plan.item/id item-id]
           :my.plan.item/title "Inspect the updated facts"}])
        (is (= "Inspect the updated facts"
               (get-in
                (db/pull
                 @connection
                 [{:my.plan.item/_agent
                   [:my.plan.item/id :my.plan.item/title]}]
                 [:seon.cluster.agent/id "alice"])
                [:my.plan.item/_agent 0 :my.plan.item/title])))
        (is (= [item-id]
               (mapv :my.plan.item/id
                     (plan/ready @connection "alice"))))
        (is (= ["Inspect the updated facts"]
               (mapv :my.plan.item/title
                     (plan/ready @connection "alice"))))))))

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
              source (plan/render-plan-ai current)
              ai (plan/format-plan-ai current)
              evaluated
              (sci.eval/evaluate
               {:seon.cluster.run.form/source source
                :seon.cluster.run.form/ns [:seon.ns/name 'fixture.plan]
                :seon.cluster.agent/id "alice"
                :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                :seon.sci.admit/caps
                (config/result-caps (support/effective-config))
                :seon.sci.eval/time-limit-ms 5000
                :seon.config/on-core-error :panic})
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
            (is (str/includes? source "my.plan/format-plan-ai"))
            (is (str/includes? source "my.plan/plan"))
            (is (= ai (:seon.sci.admit/value evaluated)))
            (is (seon.schema/valid-candidate-value? :seon.render/ai source))
            (is (seon.schema/valid-candidate-value? :seon.render/hiccup html))
            (is (str/includes? ai "Current work"))
            (is (= :section (first html)))))))))

(defn- item-count
  [database]
  (or (db/q '[:find (count ?item) .
              :where [?item :my.plan.item/id]]
            database)
      0))

(deftest plan-reconciles-a-complete-authored-tree-in-one-transaction
  (with-plan
    (fn [connection]
      (let [initial
            [{:my.plan.item/title "Ship"
              :my.plan/label "root"
              :my.plan/children
              [{:my.plan.item/title "Prepare"
                :my.plan/label "prepare"}
               {:my.plan.item/title "Verify"
                :my.plan/label "verify"
                :my.plan/after ["prepare"]}]}]
            before (db/basis-t @connection)
            created (plan/plan! initial @connection connection "alice")
            after-create (db/basis-t @connection)
            root-id (get (:my.plan/ids created) "root")
            prepare-id (get (:my.plan/ids created) "prepare")
            verify-id (get (:my.plan/ids created) "verify")]
        (is (= {:my.plan/added 3
                :my.plan/changed 0
                :my.plan/retracted 0}
               (:my.plan/diff created)))
        (is (= (inc before) after-create)
            "the complete tree commits through exactly one transaction")
        (is (= 3 (item-count @connection)))
        (is (= [prepare-id]
               (mapv :my.plan.item/id (plan/ready @connection "alice"))))
        (let [edited
              [{:my.plan.item/id root-id
                :my.plan.item/title "Ship"
                :my.plan/label "root"
                :my.plan/children
                [{:my.plan.item/id prepare-id
                  :my.plan.item/title "Prepare better"
                  :my.plan/label "prepare"}
                 {:my.plan.item/title "Publish"
                  :my.plan/label "publish"}]}]
              changed (plan/plan! edited @connection connection "alice")]
          (is (= {:my.plan/added 1
                  :my.plan/changed 1
                  :my.plan/retracted 1}
                 (:my.plan/diff changed)))
          (is (= "Prepare better"
                 (db/q '[:find ?title .
                         :in $ ?id
                         :where
                         [?item :my.plan.item/id ?id]
                         [?item :my.plan.item/title ?title]]
                       @connection prepare-id)))
          (is (nil? (db/pull @connection '[*]
                             [:my.plan.item/id verify-id])))
          (is (= 3 (item-count @connection))))))))

(deftest plan-converges-without-a-transaction-and-refuses-ambiguity
  (with-plan
    (fn [connection]
      (let [document [{:my.plan.item/title "Root"
                       :my.plan/label "root"
                       :my.plan/children
                       [{:my.plan.item/title "Same"}
                        {:my.plan.item/title "Same"}]}]
            created (plan/plan! document @connection connection "alice")
            root-id (get (:my.plan/ids created) "root")
            children
            (db/q '[:find [?id ...]
                    :in $ ?root-id
                    :where
                    [?root :my.plan.item/id ?root-id]
                    [?child :my.plan.item/parent ?root]
                    [?child :my.plan.item/id ?id]]
                  @connection root-id)
            ambiguous
            [{:my.plan.item/id root-id
              :my.plan.item/title "Root"
              :my.plan/children [{:my.plan.item/title "Same"}]}]
            basis (db/basis-t @connection)
            refusal (plan/plan! ambiguous @connection connection "alice")]
        (is (= 2 (count children)))
        (is (= :my.plan/ambiguous-identity (:seon.error/kind refusal)))
        (is (true? (:my.plan/ambiguous-identity refusal)))
        (is (= basis (db/basis-t @connection)))
        (let [exact
              [{:my.plan.item/id root-id
                :my.plan.item/title "Root"
                :my.plan/children
                (mapv (fn [id]
                        {:my.plan.item/id id :my.plan.item/title "Same"})
                      (sort children))}]
              first-round (plan/plan! exact @connection connection "alice")
              converged-basis (db/basis-t @connection)
              second-round (plan/plan! exact @connection connection "alice")]
          (is (zero? (:my.plan/added (:my.plan/diff first-round))))
          (is (true? (:my.plan/converged? second-round)))
          (is (= {:my.plan/added 0
                  :my.plan/changed 0
                  :my.plan/retracted 0}
                 (:my.plan/diff second-round)))
          (is (= converged-basis (db/basis-t @connection))))))))

(deftest plan-is-basis-fenced-and-cannot-touch-derived-obligations
  (with-plan
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.message/id "question"
         :seon.cluster.message/to [:seon.cluster.agent/id "alice"]
         :seon.cluster.message/content "Answer me."
         :seon.cluster.message/at (at 10)}])
      (let [observed @connection
            _ (db/transact! connection
                            [{:seon.cluster.message/id "later"
                              :seon.cluster.message/to
                              [:seon.cluster.agent/id "alice"]
                              :seon.cluster.message/content "Also answer me."
                              :seon.cluster.message/at (at 11)}])
            stale (plan/plan! [{:my.plan.item/title "Must not commit"}]
                              observed connection "alice")]
        (is (= :seon.db/rejected (:seon.error/kind stale)))
        (is (= :transaction/stale-basis
               (get-in stale [:seon.error/data :error])))
        (is (zero? (item-count @connection))))
      (let [before
            (set (map :my.plan/obligation-id
                      (:my.plan/obligations (plan/plan @connection "alice"))))
            applied
            (plan/plan! [{:my.plan.item/title "Authored only"}]
                        @connection connection "alice")
            after
            (set (map :my.plan/obligation-id
                      (:my.plan/obligations (plan/plan @connection "alice"))))]
        (is (= #{"question" "later"} before))
        (is (= before after))
        (is (= 1 (:my.plan/added (:my.plan/diff applied))))))))

(deftest plan-refuses-unresolved-subjects-before-transacting
  (with-plan
    (fn [connection]
      (let [basis (db/basis-t @connection)
            result
            (plan/plan!
             [{:my.plan.item/title "Investigate the missing subject"
               :my.plan.item/about ['fixture.plan/does-not-exist]}]
             @connection connection "alice")]
        (is (= :my.plan/subject-not-found (:seon.error/kind result)))
        (is (true? (:my.plan/subject-not-found result)))
        (is (= {:my.plan.item/about
                'fixture.plan/does-not-exist}
               (:seon.error/data result)))
        (is (= basis (db/basis-t @connection)))
        (is (zero? (item-count @connection)))))))

(deftest ready-subjects-follow-only-ready-items-in-stable-order
  (with-plan
    (fn [connection]
      (db/transact!
       connection
       [{:seon.fn/sym "fixture.plan/alpha"
         :seon.fn/ns [:seon.ns/name 'fixture.plan]
         :seon.fn/source "(defn alpha [] :alpha)"
         :seon.fn/arglists "([])"
         :seon.fn/private? false}
        {:seon.fn/sym "fixture.plan/beta"
         :seon.fn/ns [:seon.ns/name 'fixture.plan]
         :seon.fn/source "(defn beta [] :beta)"
         :seon.fn/arglists "([])"
         :seon.fn/private? false}])
      (add connection "dependency" "Dependency")
      (add connection "blocked" "Blocked"
           {:my.plan.item/needs #{[:my.plan.item/id "dependency"]}
            :my.plan.item/about ['fixture.plan/beta]})
      (add connection "ready" "Ready"
           {:my.plan.item/about
            ['fixture.plan/beta 'fixture.plan/alpha]})
      (let [subjects (plan/ready-subjects @connection "alice")]
        (is (= 2 (count subjects)))
        (is (= ["fixture.plan/beta" "fixture.plan/alpha"]
               (mapv #(db/q '[:find ?sym .
                              :in $ ?entity
                              :where [?entity :seon.fn/sym ?sym]]
                            @connection %)
                     subjects)))))))

(deftest plan-preserves-a-mixed-authored-subject-vector
  (with-plan
    (fn [connection]
      (let [about ['my.plan/plan! 'my.plan :my.plan.item/title]
            result (plan/plan!
                    [{:my.plan.item/id "mixed-about"
                      :my.plan.item/title "Use mixed subjects"
                      :my.plan.item/about about}]
                    @connection connection "alice")
            item (first (plan/ready @connection "alice"))]
        (is (= 1 (:my.plan/added (:my.plan/diff result))))
        (is (= about (:my.plan.item/about item)))
        (is (= ["my.plan/plan!" 'my.plan :my.plan.item/title]
               (mapv (fn [subject]
                       (or (db/q '[:find ?function .
                                   :in $ ?subject
                                   :where [?subject :seon.fn/sym ?function]]
                                 @connection subject)
                           (db/q '[:find ?namespace .
                                   :in $ ?subject
                                   :where [?subject :seon.ns/name ?namespace]]
                                 @connection subject)
                           (db/q '[:find ?key .
                                   :in $ ?subject
                                   :where [?subject :seon.schema/key ?key]]
                                 @connection subject)))
                     (plan/ready-subjects @connection "alice"))))))))
