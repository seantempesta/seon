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

(deftest next-build-can-recover-an-unavailable-publication
  (let [!recorded (atom [])]
    (with-redefs [error/record! #(swap! !recorded conj %)]
      (is (true? (admission/begin-publication!)))
      (is (true?
            (admission/mark-unavailable!
              {:seon.error/raw (js/Error. "import failed")
               ::admission/reason "JavaScript import failed"})))
      (is (= :unavailable (::admission/status (admission/state))))
      (is (true? (admission/begin-publication!))
          "the next build owns a fresh recovery publication")
      (is (= :publishing (::admission/status (admission/state))))
      (is (= 1 (count @!recorded))
          "recovery does not duplicate the rejected generation's fault"))))

(deftest owned-publication-failure-with-its-token-still-records-one-fault
  (let [!recorded (atom [])]
    (with-redefs [error/record! #(swap! !recorded conj %)]
      (is (true? (admission/begin-publication!)))
      (let [publication (::admission/publication (admission/state))]
        (is (true?
              (admission/mark-unavailable!
                {:seon.error/raw (js/Error. "owned failure")
                 ::admission/publication publication
                 ::admission/reason "owned failure"})))
        (is (= 1 (count @!recorded)))
        (is (= :unavailable (::admission/status (admission/state))))
        (is (= publication (::admission/publication (admission/state)))
            "the failed publication's identity survives the transition")))))

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

(defn- acquired-program []
  {::db/branch-head
   {:db-name "admission-test"
    :t 42
    :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000042"}
   ::db/results
   [{:seon.db.protocol/success? true
     :datahike.query/result []}
    {:seon.db.protocol/success? true
     :datahike.query/result []}]})

(defn- with-publication-seams
  [{::keys [committed-projection current-projection activate-projection!
            reconcile-projection! record!]}
   body]
  (let [original-execute-many db/execute-many
        original-committed-projection admission/committed-projection
        original-current-projection schema/current-projection
        original-activate-projection! schema/activate-projection!
        original-reconcile-projection! instrument/reconcile-projection!
        original-record! error/record!]
    (set! db/execute-many (fn [_] (js/Promise.resolve (acquired-program))))
    (set! admission/committed-projection
          (or committed-projection
              (constantly
                {:seon.schema.projection/fingerprint 42
                 :seon.schema.projection/function-contracts {}})))
    (set! schema/current-projection (or current-projection (constantly nil)))
    (set! schema/activate-projection! (or activate-projection! identity))
    (set! instrument/reconcile-projection! reconcile-projection!)
    (set! error/record! record!)
    (-> (js/Promise.resolve (body))
        (.finally
          (fn []
            (set! db/execute-many original-execute-many)
            (set! admission/committed-projection original-committed-projection)
            (set! schema/current-projection original-current-projection)
            (set! schema/activate-projection! original-activate-projection!)
            (set! instrument/reconcile-projection!
                  original-reconcile-projection!)
            (set! error/record! original-record!))))))

(deftest superseded-publication-failure-cannot-poison-the-newer-publication
  (async done
    (let [!recorded (atom [])]
      (-> (with-publication-seams
            {::reconcile-projection! (constantly {::instrument/ok? true})
             ::record! #(swap! !recorded conj %)}
            (fn []
              (is (true? (admission/begin-publication!)))
              (let [stale (::admission/publication (admission/state))]
                (-> (admission/publish-committed!)
                    (.then
                      (fn [result]
                        (is (true? (::admission/published? result)))
                        (is (true? (admission/begin-publication!))
                            "a newer build acquires the next publication")
                        (is (false?
                              (admission/mark-unavailable!
                                {:seon.error/raw
                                 (js/Error. "stale rehost failure")
                                 ::admission/publication stale
                                 ::admission/reason "superseded"}))
                            "a superseded failure transitions nothing")
                        (is (= :publishing
                               (::admission/status (admission/state)))
                            "the newer publication remains open")
                        (is (empty? @!recorded)
                            "no core fault records for a superseded occurrence")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e]
                    (is false (str "superseded publication threw — " e))
                    (done)))))))

(deftest concurrent-prepare-loses-retention-as-ordinary-supersession
  (async done
    (let [!recorded (atom [])]
      (-> (with-publication-seams
            {::reconcile-projection! (constantly {::instrument/ok? true})
             ::record! #(swap! !recorded conj %)}
            (fn []
              (is (true? (admission/begin-publication!)))
              (js/Promise.all
                #js [(admission/prepare-committed! {})
                     (admission/prepare-committed! {})])))
          (.then
            (fn [results]
              (let [[a b] (array-seq results)
                    prepared (filter ::admission/prepared? [a b])
                    refused (remove ::admission/prepared? [a b])]
                (is (= 1 (count prepared))
                    "exactly one concurrent settlement retains the generation")
                (is (= 1 (count refused)))
                (is (map? (:seon/error (first refused)))
                    "the loser receives an ordinary refusal value")
                (is (empty? @!recorded)
                    "lost retention is supersession, never a core fault")
                (let [publication (admission/admit-prepared! (first prepared))]
                  (is (true? (::admission/published? publication)))
                  (is (admission/available?))))
              (done)))
          (.catch (fn [e]
                    (is false (str "concurrent prepare threw — " e))
                    (done)))))))

(deftest committed-publication-opens-only-after-verification
  (async done
    (let [!activated (atom nil)]
      (-> (with-publication-seams
            {::activate-projection! #(reset! !activated %)
             ::reconcile-projection! (constantly {::instrument/ok? true})
             ::record! (constantly nil)}
            admission/publish-committed!)
            (.then
              (fn [result]
                (is (true? (::admission/published? result)))
                (is (false? (::admission/recovered? result)))
                (is (= 42 (::admission/generation result)))
                (is (= 42 (::admission/generation (admission/state))))
                (is (admission/available?))
                (is (= 42 (:seon.schema.projection/fingerprint @!activated)))
                (done)))
            (.catch (fn [error]
                      (is false (str "publication threw — " error))
                      (done)))))))

(deftest committed-publication-can-activate-without-process-wide-wrappers
  (async done
    (let [!activated (atom [])
          !reconciled (atom 0)]
      (-> (with-publication-seams
            {::activate-projection! #(swap! !activated conj %)
             ::reconcile-projection!
             (fn [_]
               (swap! !reconciled inc)
               {::instrument/ok? true})
             ::record! (constantly nil)}
            (fn []
              (-> (admission/prepare-committed!
                    {::admission/instrument? false})
                  (.then admission/admit-prepared!)
                  (.then (fn [_] (admission/publish-committed!))))))
          (.then
            (fn [result]
              (is (true? (::admission/published? result)))
              (is (admission/available?))
              (is (= 0 @!reconciled)
                  "the process selection persists across publications")
              (is (= 2 (count @!activated))
                  "both exact committed projections still activate")
              (is (false?
                    (get-in result
                            [::admission/instrumentation
                             ::instrument/enabled?])))
              (done)))
          (.catch (fn [error]
                    (is false (str "publication threw — " error))
                    (done)))))))

(deftest prepared-publication-stays-closed-through-an-injected-completion
  (async done
    (let [!effects (atom [])]
      (-> (with-publication-seams
            {::activate-projection!
             (fn [projection]
               (swap! !effects conj :projection-activated)
               projection)
             ::reconcile-projection! (constantly {::instrument/ok? true})
             ::record! (constantly nil)}
            #(admission/prepare-committed! {}))
            (.then
              (fn [preparation]
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
                  (is (admission/available?)))
                (done)))
            (.catch (fn [error]
                      (is false (str "preparation threw — " error))
                      (done)))))))

(deftest publication-reconciles-from-the-projection-captured-before-replay
  (async done
    (let [projection-a {:seon.schema.projection/fingerprint 1
                      :seon.schema.projection/function-contracts {}}
        projection-b {:seon.schema.projection/fingerprint 2
                      :seon.schema.projection/function-contracts {}}
        !active (atom projection-a)
        !reconciliations (atom [])]
      (-> (with-publication-seams
            {::committed-projection (constantly projection-b)
             ::current-projection #(deref !active)
             ::activate-projection! #(reset! !active %)
             ::reconcile-projection!
             (fn [request]
               (swap! !reconciliations conj request)
               {::instrument/ok? true})
             ::record! (constantly nil)}
            (fn []
              (is (true? (admission/begin-publication!))
                  "publication captures projection A before replay")
              (reset! !active projection-b)
              (admission/publish-committed!)))
            (.then
              (fn [result]
                (is (true? (::admission/published? result)))
                (is (= [{::instrument/old-projection projection-a
                         ::instrument/new-projection projection-b}]
                       @!reconciliations)
                    "publication removes A wrappers after replay activates B")
                (is (identical? projection-b @!active))
                (is (= 2 (::admission/generation (admission/state))))
                (done)))
            (.catch (fn [error]
                      (is false (str "publication threw — " error))
                      (done)))))))

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
        (is (= :starting (::admission/status (admission/state))))))))

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

(deftest committed-projection-consumes-ordinary-authority-rows
  (let [projection
        (admission/committed-projection
          {::admission/schema-rows
           [[:probe.entity/id ":string"]]
           ::admission/function-contract-rows
           [["probe.entity/find" "[:=> [:cat :string] :string]"]]})]
    (is (= :string
           (get-in projection
                   [:seon.schema.projection/forms :probe.entity/id])))
    (is (= [:=> [:cat :string] :string]
           (get-in projection
                   [:seon.schema.projection/function-contracts
                    'probe.entity/find])))))

(deftest failed-publication-retries-once-and-records-once
  (async done
    (let [!attempts (atom 0)
          !recorded (atom [])]
      (-> (with-publication-seams
            {::reconcile-projection!
             (fn [_]
               (if (= 1 (swap! !attempts inc))
                 (throw (js/Error. "injected first wrapper failure"))
                 {::instrument/ok? true}))
             ::record! #(swap! !recorded conj %)}
            admission/publish-committed!)
          (.then
            (fn [result]
              (is (true? (::admission/published? result)))
              (is (true? (::admission/recovered? result)))
              (is (= 2 @!attempts))
              (is (= 1 (count @!recorded)))
              (is (admission/available?))
              (done)))
          (.catch (fn [error]
                    (is false (str "publication threw — " error))
                    (done)))))))

(deftest closed-preparation-can-retry-without-recording-a-fault
  (async done
    (let [!attempts (atom 0)
          !recorded (atom [])]
      (-> (with-publication-seams
            {::reconcile-projection!
             (fn [_]
               (swap! !attempts inc)
               (throw (js/Error. "restore preparation failure")))
             ::record! #(swap! !recorded conj %)}
            #(admission/prepare-committed!
               {::admission/record-failures? false}))
          (.then
            (fn [result]
              (is (false? (::admission/prepared? result)))
              (is (= 2 @!attempts) "the existing repair attempt remains intact")
              (is (empty? @!recorded)
                  "a disposable restore projection failure writes no core fact")
              (is (= :unavailable (::admission/status (admission/state))))
              (done)))
          (.catch (fn [error]
                    (is false (str "preparation threw — " error))
                    (done)))))))

(deftest deterministic-repair-failure-stays-unavailable
  (async done
    (let [!attempts (atom 0)
          !recorded (atom [])]
      (-> (with-publication-seams
            {::reconcile-projection!
             (fn [_]
               (swap! !attempts inc)
               (throw (js/Error. "deterministic wrapper failure")))
             ::record! #(swap! !recorded conj %)}
            admission/publish-committed!)
          (.then
            (fn [result]
              (is (false? (::admission/published? result)))
              (is (= 2 @!attempts))
              (is (= 1 (count @!recorded)))
              (is (= :unavailable (::admission/status (admission/state))))
              (is (not (admission/available?)))
              (admission/unavailable)
              (admission/unavailable)
              (is (= 1 (count @!recorded))
                  "boundary refusals never create an error census")
              (done)))
          (.catch (fn [error]
                    (is false (str "publication threw — " error))
                    (done)))))))

(defn- unavailable-kind
  [result]
  (or (:seon.error/kind result)
      (get-in result [:seon/error :seon.error/kind])
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
              (runtime/resume! {:seon.agent/id "closed-agent"})]
          (-> (js/Promise.all
                #js [message-result start-result delegate-result
                     runtime-resume-result])
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
                {:db-after ::unused :tx-data []})
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
              #js [wake drive
                   (loop/run-loop!
                     {:seon.agent/id "closed-agent"}
                     "closed-run")
                   (schedule/fire-due-schedules!
                     {:seon.agent/now (js/Date.)})
                   ((deref #'loop/run-tick!) {} (js/Date.))])
            (.then
              (fn [results]
                (let [[wake-result drive-result loop-result
                       schedule-result tick-result]
                      (array-seq results)]
                  (is (= :seon.runtime/unavailable
                         (unavailable-kind wake-result)))
                  (is (= :seon.runtime/unavailable
                         (unavailable-kind drive-result)))
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
    (let [!effects (atom [])
          original-db db/db
          original-pull db/pull
          original-current-run run/current-run
          original-current-agent-id db/current-agent-id
          original-transact db/transact!]
      (set! db/db
            (fn
              ([] (js/Promise.resolve ::database))
              ([_] (js/Promise.resolve ::database))))
      (set! db/pull
            (fn
              ([{:seon.db/keys [ref]}]
               (js/Promise.resolve
                 {:seon.agent/id (second ref)
                  :seon.agent/parent {:seon.agent/id "draining-agent"}}))
              ([_selector eid]
               (js/Promise.resolve {:seon.agent/id (second eid)}))
              ([_database _selector eid]
               (js/Promise.resolve {:seon.agent/id (second eid)}))))
      (set! run/current-run (constantly nil))
      (set! db/current-agent-id (constantly "draining-agent"))
      (set! db/transact!
            (fn [& _]
              (swap! !effects conj :terminate-write)
              (js/Promise.resolve {:seon.db/ok? true})))
      (-> (js/Promise.all
            #js [(lifecycle/wait "drain")
                 (lifecycle/complete "")
                 (lifecycle/pause)
                 (lifecycle/terminate "draining-agent")])
          (.then
            (fn [results]
              (let [[wait-result complete-result pause-result terminate-result]
                    (array-seq results)]
                (is (string? (:seon.error/message wait-result))
                    "wait reached its ordinary no-open-run diagnosis")
                (is (string? (:seon.error/message complete-result))
                    "complete reached its ordinary no-open-run diagnosis")
                (is (string? (:seon.error/message pause-result))
                    "pause reached its ordinary no-open-run diagnosis")
                (is (= :terminated terminate-result))
                (is (= [:terminate-write] @!effects)
                    "terminate performs only its durable transition"))))
          (.catch (fn [error]
                    (is false (str "drain control threw — " error))))
          (.finally
            (fn []
              (set! db/db original-db)
              (set! db/pull original-pull)
              (set! run/current-run original-current-run)
              (set! db/current-agent-id original-current-agent-id)
              (set! db/transact! original-transact)))
          (.then (fn [_] (done)))))))

(deftest available-baseline-preserves-domain-validation-and-schedule-effects
  (async done
    (restore-test-admission!)
    (let [!effects (atom [])
          original-db db/db
          original-execute-many db/execute-many]
      (set! db/db
            (fn
              ([] (js/Promise.resolve ::database))
              ([_] (js/Promise.resolve ::database))))
      (set! db/execute-many
            (fn [_]
              (swap! !effects conj :schedule-scan)
              (js/Promise.resolve
                {::db/results
                 [{:seon.db.protocol/success? true
                   :datahike.query/result []}
                  {:seon.db.protocol/success? true
                   :datahike.query/result []}
                  {:seon.db.protocol/success? true
                   :datahike.query/result []}
                  {:seon.db.protocol/success? true
                   :datahike.pull/result {}}]})))
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
                  (is (string? (:seon.error/message message-result)))
                  (is (not= :seon.runtime/unavailable
                            (unavailable-kind message-result))
                      "available admission reaches ordinary message validation")
                  (is (= [] (:seon.agent.schedule/fired schedule-result)))
                  (is (= {:schedule-scan 1}
                         (frequencies @!effects)))
                  (done))))
            (.catch (fn [e]
                      (is false (str "available boundary threw — " e))
                      (done)))
            (.finally
              (fn []
                (set! db/db original-db)
                (set! db/execute-many original-execute-many)))))))
