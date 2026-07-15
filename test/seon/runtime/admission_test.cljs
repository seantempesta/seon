(ns seon.runtime.admission-test
  (:require
    [cljs.test :refer [async deftest is use-fixtures]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.loop :as loop]
    [seon.agent.message :as message]
    [seon.agent.run :as run]
    [seon.agent.runtime :as runtime]
    [seon.agent.schedule :as schedule]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.error :as error]
    [seon.instrument :as instrument]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]))

(defn- reset-admission! []
  (reset! @#'admission/!state
          {::admission/status :starting}))

(defn- restore-test-admission! []
  (reset! @#'admission/!state
          {::admission/status :available
           ::admission/generation 0}))

(defonce ^:private !schema-state-before (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! !schema-state-before (schema/snapshot-state))
             (reset-admission!))
   :after (fn []
            (schema/restore-state! @!schema-state-before)
            (schema/relink-registry!)
            (restore-test-admission!))})

(deftest publication-state-has-one-owner-and-one-fault
  (let [!recorded (atom [])]
    (with-redefs [error/record! #(swap! !recorded conj %)]
      (is (true? (admission/begin-publication!)))
      (is (false? (admission/begin-publication!))
          "a second publisher cannot acquire the closed transition")
      (is (true?
            (admission/mark-unavailable!
              {:seon.error/raw (js/Error. "publish failed")
               ::admission/reason "publish failed"})))
      (is (false?
            (admission/mark-unavailable!
              {:seon.error/raw (js/Error. "duplicate")
               ::admission/reason "duplicate"})))
      (is (= 1 (count @!recorded))
          "one failed publication occurrence records once")
      (is (= :unavailable (::admission/status (admission/state))))
      (is (= :seon.runtime/unavailable
             (get-in (admission/unavailable)
                     [:seon/error :seon.error/kind]))))))

(deftest planned-quiesce-has-one-owner-and-preserves-generation
  (restore-test-admission!)
  (is (true? (admission/begin-quiesce!)))
  (is (false? (admission/available?))
      "new executable work is refused immediately")
  (is (true? (admission/quiescing?))
      "already-owned loops can distinguish planned drain from a core fault")
  (is (= {::admission/status :quiescing
          ::admission/generation 0}
         (admission/state))
      "the accepted program generation remains observable during drain")
  (is (re-find #"planned maintenance"
               (get-in (admission/unavailable)
                       [:seon/error :seon.error/message]))
      "planned refusal never claims that a core publication fault occurred")
  (is (false? (admission/begin-quiesce!))
      "a repeated caller cannot acquire a second lifecycle transition")
  (is (false? (admission/begin-publication!))
      "publication cannot steal a quiescing runtime"))

(defn- with-publication-seams [reconcile! record! body]
  (with-redefs [db/*conn* (atom ::database)
                admission/committed-projection
                (fn [_database]
                  {:seon.schema.projection/fingerprint 42
                   :seon.schema.projection/function-contracts {}})
                schema/current-projection (constantly nil)
                schema/activate-projection! identity
                instrument/reconcile-projection! reconcile!
                error/record! record!]
    (body)))

(deftest committed-publication-opens-only-after-verification
  (let [!activated (atom nil)]
    (with-redefs [db/*conn* (atom ::database)
                  admission/committed-projection
                  (fn [_database]
                    {:seon.schema.projection/fingerprint 42
                     :seon.schema.projection/function-contracts {}})
                  schema/current-projection (constantly nil)
                  schema/activate-projection! #(reset! !activated %)
                  instrument/reconcile-projection!
                  (constantly {::instrument/ok? true})]
      (let [result (admission/publish-committed!)]
        (is (true? (::admission/published? result)))
        (is (false? (::admission/recovered? result)))
        (is (= 42 (::admission/generation result)))
        (is (= 42 (::admission/generation (admission/state))))
        (is (admission/available?))
        (is (= 42 (:seon.schema.projection/fingerprint @!activated)))))))

(deftest prepared-publication-stays-closed-through-an-injected-completion
  (let [!effects (atom [])]
    (with-redefs [db/*conn* (atom ::database)
                  admission/committed-projection
                  (fn [_database]
                    {:seon.schema.projection/fingerprint 42
                     :seon.schema.projection/function-contracts {}})
                  schema/current-projection (constantly nil)
                  schema/activate-projection!
                  (fn [projection]
                    (swap! !effects conj :projection-activated)
                    projection)
                  instrument/reconcile-projection!
                  (constantly {::instrument/ok? true})]
      (let [preparation (admission/prepare-committed! {})]
        (is (true? (::admission/prepared? preparation)))
        (is (= :publishing (::admission/status (admission/state))))
        (is (false? (admission/available?))
            "verified wrappers remain hidden while completion is pending")

        (let [wrong-publication
              (admission/admit-prepared!
                (assoc preparation ::admission/generation 43))]
          (is (false? (::admission/published? wrong-publication)))
          (is (= :publishing (::admission/status (admission/state))))
          (is (false? (admission/available?))
              "a result for another generation cannot open admission"))

        (swap! !effects conj :completion-verified)
        (let [publication (admission/admit-prepared! preparation)]
          (is (true? (::admission/published? publication)))
          (is (= [:projection-activated :completion-verified] @!effects))
          (is (= 42 (::admission/generation (admission/state))))
          (is (admission/available?)))))))

(deftest publication-reconciles-from-the-projection-captured-before-replay
  (let [projection-a {:seon.schema.projection/fingerprint 1
                      :seon.schema.projection/function-contracts {}}
        projection-b {:seon.schema.projection/fingerprint 2
                      :seon.schema.projection/function-contracts {}}
        !active (atom projection-a)
        !reconciliations (atom [])]
    (with-redefs [db/*conn* (atom ::database-b)
                  admission/committed-projection (constantly projection-b)
                  schema/current-projection #(deref !active)
                  schema/activate-projection! #(reset! !active %)
                  instrument/reconcile-projection!
                  (fn [request]
                    (swap! !reconciliations conj request)
                    {::instrument/ok? true})]
      (is (true? (admission/begin-publication!))
          "publication captures attachment A before replay")
      (reset! !active projection-b)
      (let [result (admission/publish-committed!)]
        (is (true? (::admission/published? result)))
        (is (= [{::instrument/old-projection projection-a
                 ::instrument/new-projection projection-b}]
               @!reconciliations)
            "publication removes A wrappers after replay activates B")
        (is (identical? projection-b @!active))
        (is (= 2 (::admission/generation (admission/state))))))))

(deftest detach-removes-the-active-projection-and-is-idempotent
  (let [projection-a {:seon.schema.projection/fingerprint 1
                      :seon.schema.projection/function-contracts {}}
        !active (atom projection-a)
        !reconciliations (atom [])]
    (restore-test-admission!)
    (with-redefs [schema/current-projection #(deref !active)
                  schema/activate-projection! #(reset! !active %)
                  instrument/reconcile-projection!
                  (fn [request]
                    (swap! !reconciliations conj request)
                    {::instrument/ok? true})]
      (let [first-result (admission/detach!)
            second-result (admission/detach!)]
        (is (true? (::admission/detached? first-result)))
        (is (true? (::admission/detached? second-result)))
        (let [[first-reconcile second-reconcile] @!reconciliations
              first-empty (::instrument/new-projection first-reconcile)
              second-empty (::instrument/new-projection second-reconcile)]
          (is (identical? projection-a
                          (::instrument/old-projection first-reconcile)))
          (is (= {} (:seon.schema.projection/forms first-empty)))
          (is (identical? first-empty
                          (::instrument/old-projection second-reconcile))
              "the repeated detach starts from the activated empty projection")
          (is (= {} (:seon.schema.projection/forms second-empty)))
          (is (identical? second-empty @!active)))
        (is (= {::admission/status :starting} (admission/state)))))))

(deftest failed-detach-keeps-the-old-projection-retryable
  (let [projection-a {:seon.schema.projection/fingerprint 1
                      :seon.schema.projection/function-contracts {}}
        !active (atom projection-a)
        !attempts (atom 0)
        !accepted-empty (atom nil)
        !recorded (atom [])]
    (restore-test-admission!)
    (with-redefs [schema/current-projection #(deref !active)
                  schema/activate-projection! #(reset! !active %)
                  instrument/reconcile-projection!
                  (fn [request]
                    (if (= 1 (swap! !attempts inc))
                      {::instrument/ok? false
                       ::instrument/verification-gaps
                       [{::instrument/sym 'probe.attach/stale}]}
                      (do
                        (reset! !accepted-empty
                                (::instrument/new-projection request))
                        {::instrument/ok? true})))
                  error/record! #(swap! !recorded conj %)]
      (let [first-result (admission/detach!)]
        (is (false? (::admission/detached? first-result)))
        (is (map? (:seon/error first-result)))
        (is (= :unavailable (::admission/status (admission/state))))
        (is (identical? projection-a @!active)
            "failed detach never activates the empty projection")
        (let [retry-result (admission/detach!)]
          (is (true? (::admission/detached? retry-result))
              "the retained old projection can be detached on retry")
          (is (identical? @!accepted-empty @!active))
          (is (= :starting (::admission/status (admission/state)))))
        (is (= 1 (count @!recorded))
            "one failed detach occurrence records one core fault")))))

(deftest fresh-database-opens-after-detach-regressions
  (async done
    (-> (client/open-agent-conn!)
        (.then (fn [conn] (d/release conn)))
        (.then (fn [_] (is true) (done)))
        (.catch (fn [error]
                  (is false (str "fresh database failed after detach: " error))
                  (done))))))

(deftest failed-publication-retries-once-and-records-once
  (let [!attempts (atom 0)
        !recorded (atom [])]
    (with-publication-seams
      (fn [_]
        (if (= 1 (swap! !attempts inc))
          (throw (js/Error. "injected first wrapper failure"))
          {::instrument/ok? true}))
      #(swap! !recorded conj %)
      (fn []
        (let [result (admission/publish-committed!)]
          (is (true? (::admission/published? result)))
          (is (true? (::admission/recovered? result)))
          (is (= 2 @!attempts))
          (is (= 1 (count @!recorded)))
          (is (admission/available?)))))))

(deftest closed-preparation-can-retry-without-recording-a-fault
  (let [!attempts (atom 0)
        !recorded (atom [])]
    (with-publication-seams
      (fn [_]
        (swap! !attempts inc)
        (throw (js/Error. "restore preparation failure")))
      #(swap! !recorded conj %)
      (fn []
        (let [result
              (admission/prepare-committed!
                {::admission/record-failures? false})]
          (is (false? (::admission/prepared? result)))
          (is (= 2 @!attempts) "the existing repair attempt remains intact")
          (is (empty? @!recorded)
              "a disposable restore projection failure writes no core fact")
          (is (= :unavailable (::admission/status (admission/state)))))))))

(deftest deterministic-repair-failure-stays-unavailable
  (let [!attempts (atom 0)
        !recorded (atom [])]
    (with-publication-seams
      (fn [_]
        (swap! !attempts inc)
        (throw (js/Error. "deterministic wrapper failure")))
      #(swap! !recorded conj %)
      (fn []
        (let [result (admission/publish-committed!)]
          (is (false? (::admission/published? result)))
          (is (= 2 @!attempts))
          (is (= 1 (count @!recorded)))
          (is (= :unavailable (::admission/status (admission/state))))
          (is (not (admission/available?)))
          (admission/unavailable)
          (admission/unavailable)
          (is (= 1 (count @!recorded))
              "boundary refusals never create an error census"))))))

(defn- unavailable-kind
  [result]
  (or (get-in result [:seon/error :seon.error/kind])
      (get-in result [:seon.db/error :seon.error/kind])))

(deftest closed-agent-boundaries-refuse-before-owning-effects
  (async done
    (let [!effects (atom [])
          record! #(swap! !effects conj %)]
      (with-redefs [db.id/allocate!
                    (fn [_]
                      (record! :allocate)
                      (js/Promise.reject (js/Error. "allocation must not run")))
                    db/entity
                    (fn [_]
                      (record! :entity-read)
                      (throw (js/Error. "hosting read must not run")))
                    error/record! (fn [_] (record! :fault-record))]
        (let [message-result
              (message/message!
                {:seon.agent.message/content "closed"
                 :seon.agent.message/from message/user-ref})
              start-result (agent/start! {})
              delegate-result
              (agent/delegate!
                {:seon.agent.message/content "closed task"})
              runtime-resume-result
              (runtime/resume! {:seon.agent/id "closed-agent"})
              agent-resume-result
              (agent/resume! {:seon.agent/id "closed-agent"})]
          (-> (js/Promise.all
                #js [message-result start-result delegate-result
                     runtime-resume-result agent-resume-result])
              (.then
                (fn [results]
                  (doseq [result (array-seq results)]
                    (is (= :seon.runtime/unavailable
                           (unavailable-kind result))))
                  (is (empty? @!effects)
                      "refusal performs no allocation, hosting read, or fault record")
                  (done)))
              (.catch (fn [e]
                        (is false (str "closed boundary threw — " e))
                        (done)))))))))

(deftest closed-loop-and-schedule-boundaries-start-no-work
  (async done
    (let [!effects (atom [])
          wake ((loop/wake-handler {:seon.agent/id "closed-agent"})
                {:seon.db/db ::unused
                 :seon.db/attr-index {}})
          drive (loop/drive-run! {:seon.agent/id "closed-agent"})]
      (with-redefs [db/query
                    (fn [& _]
                      (swap! !effects conj :schedule-scan)
                      [])
                    run/close-overdue-runs!
                    (fn [_]
                      (swap! !effects conj :watchdog)
                      (js/Promise.resolve {}))]
        (-> (js/Promise.all
              #js [(loop/run-loop!
                     {:seon.agent/id "closed-agent"}
                     "closed-run")
                   (schedule/fire-due-schedules!
                     {:seon.agent/now (js/Date.)})
                   ((deref #'loop/run-tick!) (js/Date.))])
            (.then
              (fn [results]
                (let [[loop-result schedule-result tick-result]
                      (array-seq results)]
                  (is (= :seon.runtime/unavailable
                         (unavailable-kind wake)))
                  (is (= :seon.runtime/unavailable
                         (unavailable-kind drive)))
                  (is (= :seon.runtime/unavailable
                         (unavailable-kind loop-result)))
                  (is (= [] (:seon.agent.schedule/fired schedule-result)))
                  (is (= :seon.runtime/unavailable
                         (unavailable-kind schedule-result)))
                  (is (= :seon.runtime/unavailable
                         (unavailable-kind tick-result)))
                  (is (empty? @!effects)
                      "closed loop/ticker performs no scan or watchdog write")
                  (done))))
            (.catch (fn [e]
                      (is false (str "closed loop boundary threw — " e))
                      (done))))))))

(deftest drain-controls-remain-available-while-admission-is-closed
  (async done
    (let [!effects (atom [])]
      (with-redefs [run/current-run (constantly nil)
                    db/current-agent-id (constantly "draining-agent")
                    db/transact!
                    (fn [& _]
                      (swap! !effects conj :terminate-write)
                      (js/Promise.resolve {:seon.db/ok? true}))
                    runtime/unhost!
                    (fn [_]
                      (swap! !effects conj :unhost)
                      {:seon.agent.runtime/unhosted? true})]
        (-> (js/Promise.all
              #js [(lifecycle/wait "drain")
                   (lifecycle/complete "")
                   (lifecycle/pause)
                   (lifecycle/terminate "draining-agent")])
            (.then
              (fn [results]
                (let [[wait-result complete-result pause-result terminate-result]
                      (array-seq results)]
                  (is (false? (:seon.db/ok? wait-result))
                      "wait reached its ordinary no-open-run diagnosis")
                  (is (false? (:seon.db/ok? complete-result))
                      "complete reached its ordinary no-open-run diagnosis")
                  (is (false? (:seon.db/ok? pause-result))
                      "pause reached its ordinary no-open-run diagnosis")
                  (is (= :terminated terminate-result))
                  (is (= [:terminate-write] @!effects)
                      "terminate reached its ordinary durable control write")
                  (done))))
            (.catch (fn [e]
                      (is false (str "drain control threw — " e))
                      (done))))))))

(deftest available-baseline-preserves-domain-validation-and-schedule-effects
  (async done
    (restore-test-admission!)
    (let [!effects (atom [])]
      (with-redefs [db/query
                    (fn [& _]
                      (swap! !effects conj :schedule-scan)
                      [])]
        (-> (js/Promise.all
              #js [(-> (message/message!
                         {:seon.agent.message/content " "
                          :seon.agent.message/from message/user-ref})
                       (.catch #(throw (ex-info "message boundary" %))))
                   (-> (schedule/fire-due-schedules!
                         {:seon.agent/now (js/Date.)})
                       (.catch #(throw (ex-info "schedule boundary" %))))])
            (.then
              (fn [results]
                (let [[message-result schedule-result]
                      (array-seq results)]
                  (is (false? (:seon.db/ok? message-result)))
                  (is (not= :seon.runtime/unavailable
                            (unavailable-kind message-result))
                      "available admission reaches ordinary message validation")
                  (is (= [] (:seon.agent.schedule/fired schedule-result)))
                  (is (= {:schedule-scan 1}
                         (frequencies @!effects)))
                  (done))))
            (.catch (fn [e]
                      (is false (str "available boundary threw — " e))
                      (done))))))))
