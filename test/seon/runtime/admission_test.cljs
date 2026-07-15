(ns seon.runtime.admission-test
  (:require
    [cljs.test :refer [async deftest is use-fixtures]]
    [seon.agent :as agent]
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.loop :as loop]
    [seon.agent.message :as message]
    [seon.agent.run :as run]
    [seon.agent.runtime :as runtime]
    [seon.agent.schedule :as schedule]
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

(use-fixtures :each
  {:before reset-admission!
   :after restore-test-admission!})

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
