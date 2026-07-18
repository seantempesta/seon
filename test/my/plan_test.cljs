(ns my.plan-test
  "Focused contracts for the writer-backed asynchronous planning surface."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [my.plan :as plan]
    [my.plan.internal :as internal]
    [seon.db :as db]
    [seon.schema :as schema]))

(def ^:private database
  {:db-name "plan-test"
   :t 536870912
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000002"})

(def ^:private agent-id "agent-a")

(def ^:private rows
  [{:my.plan/id "root" :my.plan/title "Ship" :my.plan/status :open
    :my.plan/goal "working release" :my.plan/created-at (js/Date. 1)
    :my.plan/agent {:seon.agent/id agent-id}}
   {:my.plan/id "done" :my.plan/title "Prepare" :my.plan/status :done
    :my.plan/created-at (js/Date. 2)
    :my.plan/agent {:seon.agent/id agent-id}
    :my.plan/parent {:my.plan/id "root"}}
   {:my.plan/id "ready" :my.plan/title "Verify" :my.plan/status :open
    :my.plan/created-at (js/Date. 3)
    :my.plan/agent {:seon.agent/id agent-id}
    :my.plan/parent {:my.plan/id "root"}
    :my.plan/needs [{:my.plan/id "done"}]}])

(deftest ordinary-rows-derive-tree-readiness-and-progress
  (testing "one immutable row set supplies every derived planning view"
    (is (= ["ready"]
           (mapv :my.plan/id (internal/ready-leaves-from-rows rows))))
    (is (= {:my.plan/done 1 :my.plan/total 2 :my.plan/done? false}
           (internal/plan-rollup-from-rows rows "root")))
    (is (= ["done" "ready"]
           (mapv :my.plan/id
                 (:my.plan/_parent
                   (internal/subtree-from-rows rows "root")))))))

(deftest html-renderer-matches-the-dynamic-render-interface
  (async done
    (let [original-query db/query]
      (set! db/query (fn [_] (js/Promise.resolve rows)))
      (-> (internal/plan-block-html
           {:seon.db/db database :seon.agent/id agent-id}
           (fn [_] (js/Promise.resolve [])))
          (.then
           (fn [hiccup]
             (is (vector? hiccup))
             (is (str/includes? (pr-str hiccup) "Ship"))))
          (.finally (fn [] (set! db/query original-query)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest mutation-is-fenced-by-the-read-snapshot
  (async done
    (let [request (atom nil)
          original-pull db/pull
          original-transact db/transact!
          pull-stub (fn [_database _selector _lookup-ref]
                      (js/Promise.resolve
                        (some #(when (= "ready" (:my.plan/id %)) %) rows)))]
      ;; Direct positional calls compile to the CLJS arity property.
      (set! (.-cljs$core$IFn$_invoke$arity$3 pull-stub) pull-stub)
      (set! db/pull pull-stub)
      (set! db/transact!
            (fn [& values]
              (reset! request (first values))
              (js/Promise.resolve
               {:db-before database :db-after database
                :tx-data [] :tempids {} :tx-meta {}})))
      (-> (plan/done! {:my.plan/id "ready" :seon.db/db database})
          (.then
            (fn [result]
              (is (true? (:my.plan/ok? result)))
              (is (= database (::db/db @request)))
              (is (= database (::db/expected-db @request)))
              (is (= :done (get-in @request [::db/tx-data 0 :my.plan/status])))))
          (.finally
            (fn []
              (set! db/pull original-pull)
              (set! db/transact! original-transact)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest reconcile-compiler-is-pure-over-ordinary-rows
  (let [document [{:my.plan/id "root" :my.plan/title "Ship"
                   :my.plan/goal "working release"
                   :my.plan/_parent
                   [{:my.plan/id "ready" :my.plan/title "Verify better"
                     :my.plan/needs [{:my.plan/id "done"}]}]}]
        compiled (internal/compile-reconcile
                   rows "reconcile!" [:seon.agent/id agent-id]
                   document {} (js/Date. 4))]
    (is (nil? (:my.plan.internal/error compiled)))
    (is (= 1 (get-in compiled
                     [:my.plan.internal/diff :my.plan/updated])))
    (is (some #(= "Verify better" (:my.plan/title %))
              (:my.plan.internal/transaction-data compiled)))))

(defn- row
  [id title status created-at & {:as attributes}]
  (merge {:my.plan/id id
          :my.plan/title title
          :my.plan/status status
          :my.plan/created-at (js/Date. created-at)
          :my.plan/agent {:seon.agent/id agent-id}}
         attributes))

(defn- compile-with-generated-ids
  [existing document]
  (let [preview (internal/compile-reconcile
                  existing "reconcile!" [:seon.agent/id agent-id]
                  document {} (js/Date. 10))
        ids (into {}
                  (map-indexed
                    (fn [index allocation-key]
                      [allocation-key (str "generated-" index)]))
                  (:my.plan.internal/allocation-keys preview))]
    (internal/compile-reconcile
      existing "reconcile!" [:seon.agent/id agent-id]
      document ids (js/Date. 10))))

(deftest row-derivations-preserve-dependency-semantics
  (let [graph [(row "root" "Root" :open 0)
               (row "need" "Need" :open 1
                    :my.plan/parent {:my.plan/id "root"})
               (row "work" "Work" :open 2
                    :my.plan/parent {:my.plan/id "root"}
                    :my.plan/needs [{:my.plan/id "need"}])]]
    (is (true? (internal/ready-from-rows? graph "need")))
    (is (true? (internal/blocked-from-rows? graph "work")))
    (is (false? (internal/ready-from-rows? graph "work")))
    (let [done-graph (mapv #(if (= "need" (:my.plan/id %))
                              (assoc % :my.plan/status :done)
                              %)
                           graph)]
      (is (false? (internal/blocked-from-rows? done-graph "work")))
      (is (true? (internal/ready-from-rows? done-graph "work"))))))

(deftest drained-parent-becomes-ready-to-close
  (let [graph [(row "root" "Root" :open 0)
               (row "a" "A" :done 1 :my.plan/parent {:my.plan/id "root"})
               (row "b" "B" :done 2 :my.plan/parent {:my.plan/id "root"})]]
    (is (true? (internal/ready-from-rows? graph "root")))
    (is (= ["root"]
           (mapv :my.plan/id (internal/ready-leaves-from-rows graph))))
    (is (= {:my.plan/done 2 :my.plan/total 2 :my.plan/done? true}
           (internal/plan-rollup-from-rows graph "root")))))

(deftest active-step-wins-the-position-anchor
  (let [graph [(row "root" "Root" :open 0 :my.plan/goal "Ship")
               (row "ready" "Ready" :open 1
                    :my.plan/parent {:my.plan/id "root"})
               (row "active" "Active" :active 2
                    :my.plan/parent {:my.plan/id "root"})]
        anchor (internal/anchor-from-rows graph)]
    (is (= "active" (get-in anchor [:my.plan/step :my.plan/id])))
    (is (true? (:my.plan/active? anchor)))
    (is (= ["root" "active"]
           (mapv :my.plan/id (:my.plan/chain anchor))))))

(deftest tree-assembly-is-cycle-safe-and-agent-scoped
  (let [other (assoc (row "other" "Other" :open 4)
                     :my.plan/agent {:seon.agent/id "agent-b"})
        graph (conj rows other
                    (row "cycle-a" "Cycle A" :open 5
                         :my.plan/parent {:my.plan/id "cycle-b"})
                    (row "cycle-b" "Cycle B" :open 6
                         :my.plan/parent {:my.plan/id "cycle-a"}))]
    (is (= 5 (count (internal/rows-for-agent graph
                                             [:seon.agent/id agent-id]))))
    (is (= ["root" "other"]
           (mapv :my.plan/id (internal/forest-from-rows graph))))
    (is (= #{"cycle-a" "cycle-b"}
           (set (internal/descendant-ids-from-rows graph "cycle-a"))))))

(deftest open-document-prunes-completed-subtrees
  (let [tree {:my.plan/id "root" :my.plan/title "Root" :my.plan/status :open
              :my.plan/_parent
              [{:my.plan/id "done" :my.plan/title "Done" :my.plan/status :done
                :my.plan/_parent
                [{:my.plan/id "hidden" :my.plan/title "Hidden"
                  :my.plan/status :open}]}
               {:my.plan/id "kept" :my.plan/title "Kept"
                :my.plan/status :open}]}
        pruned (internal/prune-done tree)]
    (is (= ["kept"] (mapv :my.plan/id (:my.plan/_parent pruned))))
    (is (nil? (internal/prune-done
                {:my.plan/id "done" :my.plan/status :done})))))

(deftest plan-key-validation-is-schema-derived-and-recursive
  (is (nil? (internal/check-plan-keys
              "plan!" {:my.plan/title "Root"
                        :my.plan/children [{:my.plan/title "Child"}]})))
  (let [top (internal/check-plan-keys
              "plan!" {:my.plan/title "Root" :my.plan/gol "typo"})
        child (internal/check-plan-keys
                "plan!" {:my.plan/title "Root"
                          :my.plan/children
                          [{:my.plan/title "Child" :my.plan/xpect "typo"}]})]
    (is (str/includes? (:my.plan/error top) ":my.plan/gol"))
    (is (str/includes? (:my.plan/error top) ":my.plan/goal"))
    (is (str/includes? (:my.plan/error child) ":my.plan/expect")))
  (is (nil? (internal/check-request-keys
              "done!" {:my.plan/id "x" :foreign/value true}
              :my.plan/id-request))))

(deftest reconcile-schema-declares-document-prefill
  (let [definition (schema/schema-definition :my.plan/reconcile-request)
        entry (some #(when (and (vector? %)
                                (= :my.plan/tree (first %))) %)
                    (rest definition))]
    (is (= 'my.plan/document (:seon.render/prefill-fn (second entry))))))

(deftest plan-compiler-authors-one-tree-and-resolves-label-dependencies
  (let [document [{:my.plan/title "Ship" :my.plan/goal "Working release"
                   :my.plan/children
                   [{:my.plan/title "Prepare" :my.plan/ref "prepare"
                     :my.plan/description "Set up"}
                    {:my.plan/title "Verify" :my.plan/ref "verify"
                     :my.plan/after ["prepare"]}]}]
        compiled (compile-with-generated-ids [] document)
        tx (:my.plan.internal/transaction-data compiled)
        verify (some #(when (= "Verify" (:my.plan/title %)) %) tx)]
    (is (nil? (:my.plan.internal/error compiled)))
    (is (= {:my.plan/added 3 :my.plan/dropped 0 :my.plan/updated 0}
           (:my.plan.internal/diff compiled)))
    (is (= "Set up" (:my.plan/description
                       (some #(when (= "Prepare" (:my.plan/title %)) %) tx))))
    (is (= 1 (count (:my.plan/needs verify))))
    (is (= #{:root "prepare" "verify"}
           (set (keys (:my.plan.internal/labels compiled)))))))

(deftest reconcile-round-trip-has-zero-delta
  (let [document (internal/prune-done (internal/forest-from-rows rows))
        compiled (internal/compile-reconcile
                   rows "reconcile!" [:seon.agent/id agent-id]
                   document {} (js/Date. 5))]
    (is (nil? (:my.plan.internal/error compiled)))
    (is (empty? (:my.plan.internal/transaction-data compiled)))
    (is (= {:my.plan/added 0 :my.plan/dropped 0 :my.plan/updated 0}
           (:my.plan.internal/diff compiled)))))

(deftest reconcile-updates-mints-and-drops-in-one-transaction
  (let [existing (conj rows
                       (row "drop" "Drop" :open 4
                            :my.plan/parent {:my.plan/id "root"}))
        document [{:my.plan/id "root" :my.plan/title "Ship"
                   :my.plan/goal "working release"
                   :my.plan/children
                   [{:my.plan/id "ready" :my.plan/title "Verify better"
                     :my.plan/description "sharpened"}
                    {:my.plan/title "Publish"}]}]
        compiled (compile-with-generated-ids existing document)
        tx (:my.plan.internal/transaction-data compiled)]
    (is (= {:my.plan/added 1 :my.plan/dropped 1 :my.plan/updated 1}
           (:my.plan.internal/diff compiled)))
    (is (some #(= "Verify better" (:my.plan/title %)) tx))
    (is (some #(= "Publish" (:my.plan/title %)) tx))
    (is (some #(= [:db.fn/retractEntity [:my.plan/id "drop"]] %) tx))))

(deftest reconcile-protects-done-and-foreign-identities
  (let [done-doc [{:my.plan/id "done" :my.plan/title "Rewrite history"}]
        foreign [(assoc (row "foreign" "Foreign" :open 5)
                        :my.plan/agent {:seon.agent/id "agent-b"})]
        done-result (internal/compile-reconcile
                      rows "reconcile!" [:seon.agent/id agent-id]
                      done-doc {} (js/Date. 6))
        foreign-result (internal/compile-reconcile
                         (into rows foreign) "reconcile!"
                         [:seon.agent/id agent-id]
                         [{:my.plan/id "foreign" :my.plan/title "Steal"}]
                         {} (js/Date. 6))]
    (is (str/includes? (:my.plan.internal/error done-result) "reopen!"))
    (is (str/includes? (:my.plan.internal/error foreign-result)
                       "not in your open tree"))))

(deftest reconcile-resolves-one-idless-root-without-reminting
  (let [document [{:my.plan/title "Ship renamed"
                   :my.plan/children
                   [{:my.plan/id "ready" :my.plan/title "Verify"}]}]
        compiled (internal/compile-reconcile
                   rows "reconcile!" [:seon.agent/id agent-id]
                   document {} (js/Date. 7))]
    (is (nil? (:my.plan.internal/error compiled)))
    (is (true? (:my.plan.internal/resolved-root? compiled)))
    (is (= "root" (:my.plan.internal/root-id compiled)))
    (is (= 0 (:my.plan/added (:my.plan.internal/diff compiled))))))

(deftest reconcile-refuses-ambiguous-idless-roots-and-children
  (let [two-roots [(row "r1" "One" :open 0)
                   (row "r2" "Two" :open 1)]
        roots-result (internal/compile-reconcile
                       two-roots "reconcile!" [:seon.agent/id agent-id]
                       [{:my.plan/title "Third"}] {} (js/Date. 8))
        duplicate-kids [(row "root" "Root" :open 0)
                        (row "a1" "Alpha" :open 1
                             :my.plan/parent {:my.plan/id "root"})
                        (row "a2" "Alpha" :open 2
                             :my.plan/parent {:my.plan/id "root"})]
        child-result (internal/compile-reconcile
                       duplicate-kids "reconcile!" [:seon.agent/id agent-id]
                       [{:my.plan/id "root" :my.plan/title "Root"
                         :my.plan/children [{:my.plan/title "Alpha"}]}]
                       {} (js/Date. 8))]
    (is (str/includes? (:my.plan.internal/error roots-result) "r1"))
    (is (str/includes? (:my.plan.internal/error roots-result) "r2"))
    (is (str/includes? (:my.plan.internal/error child-result) "a1"))
    (is (str/includes? (:my.plan.internal/error child-result) "a2"))))

(deftest reconcile-refuses-malformed-references-and-duplicate-identities
  (let [unknown-label (compile-with-generated-ids
                        [] [{:my.plan/title "Root"
                             :my.plan/children
                             [{:my.plan/title "Child"
                               :my.plan/after ["missing"]}]}])
        bad-need (compile-with-generated-ids
                   [] [{:my.plan/title "Root"
                        :my.plan/needs [{:wrong/id "missing"}]}])
        duplicate (internal/compile-reconcile
                    rows "reconcile!" [:seon.agent/id agent-id]
                    [{:my.plan/id "root" :my.plan/title "Root"
                      :my.plan/children
                      [{:my.plan/id "root" :my.plan/title "Again"}]}]
                    {} (js/Date. 9))]
    (is (str/includes? (:my.plan.internal/error unknown-label)
                       "unknown label"))
    (is (str/includes? (:my.plan.internal/error bad-need)
                       "unrecognizable :my.plan/needs"))
    (is (str/includes? (:my.plan.internal/error duplicate)
                       "appears twice"))))

(defn- eval-row
  [id ok? source & [kind]]
  (cond-> {:seon.eval/id id :seon.eval/ok? ok? :seon.eval/source source
           :seon.eval/error "failed"}
    kind (assoc :seon.eval/error-data
                (pr-str {:seon.error/kind kind}))))

(deftest wedge-counts-live-same-call-failures
  (let [source "(schema/register! :my.rows (map [:string]))"
        rows [(eval-row "e1" false source :invalid-input)
              (eval-row "e2" false source :invalid-input)
              (eval-row "e3" false source :invalid-input)]
        wedge (internal/wedge rows 3)]
    (is (= "schema/register!" (:my.plan/root-sym wedge)))
    (is (= :invalid-input (:my.plan/root-kind wedge)))
    (is (= 3 (:my.plan/fail-count wedge)))
    (is (= "e1" (:my.plan/episode wedge)))
    (is (nil? (internal/wedge rows 4)))))

(deftest wedge-resets-only-on-success-of-the-same-call
  (let [source "(schema/register! :my.rows (map [:string]))"
        failures [(eval-row "e1" false source)
                  (eval-row "e2" false source)]
        unrelated (conj failures (eval-row "u" true "(defn fixed [] 1)")
                        (eval-row "e3" false source))
        reset (conj failures (eval-row "ok" true source)
                    (eval-row "e3" false source))]
    (is (= 3 (:my.plan/fail-count (internal/wedge unrelated 3))))
    (is (nil? (internal/wedge reset 2)))))

(deftest escalation-marker-is-stable-and-addressable
  (let [marker (internal/consult-marker "step-a" "eval-a")]
    (is (= marker (internal/consult-marker "step-a" "eval-a")))
    (is (str/includes? marker ":my.plan/step \"step-a\""))
    (is (str/includes? marker ":my.plan/episode \"eval-a\""))))

(deftest transaction-reports-and-errors-map-to-plan-results
  (is (= {:my.plan/ok? true :my.plan/id "step"}
         (internal/write-result "done!" "step"
                                {:db-before database :db-after database
                                 :tx-data [] :tempids {}})))
  (let [failure (internal/write-result
                  "done!" "step"
                  {:seon.error/message "writer unavailable"})]
    (is (false? (:my.plan/ok? failure)))
    (is (str/includes? (:my.plan/error failure) "writer unavailable"))))
