(ns seon.client-initialization-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.agent :as agent]
   [seon.agent.loop :as agent-loop]
   [seon.client :as client]
   [seon.db :as db]
   [seon.launch :as launch]
   [seon.runtime.admission :as admission]))

(def ^:private digest
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(defn- descriptor []
  {::launch/runtime {::launch/execution-digest digest}})

(defn- with-program-builders
  [core schemas body]
  (let [original-core client/index-core!
        original-schemas client/index-schemas]
    (set! client/index-core! (fn [] core))
    (set! client/index-schemas (fn [] schemas))
    (try
      (body)
      (finally
        (set! client/index-core! original-core)
        (set! client/index-schemas original-schemas)))))

(deftest initialization-is-one-deterministic-complete-value
  (let [namespace-row
        {:seon.ns/name :example.core
         :seon.ns/source "(ns example.core)"}
        function-row
        {:seon.fn/sym "example.core/identity"
         :seon.fn/ns [:seon.ns/name :example.core]
         :seon.fn/source "(defn identity [value] value)"
         :seon.fn/spec "[:=> [:cat :example/id] :example/id]"
         :seon.fn/created-at (js/Date. 1)}
        schema-row
        {:seon.schema/key :example/id
         :seon.schema/form ":int"
         :seon.schema/created-at (js/Date. 2)}
        build (deref #'client/database-initialization)
        forward
        (with-program-builders
          [function-row namespace-row]
          [schema-row]
          #(build (descriptor)))
        reverse
        (with-program-builders
          [namespace-row function-row]
          [schema-row]
          #(build (descriptor)))]
    (is (= forward reverse))
    (is (= digest (:seon.execution/artifact-digest forward)))
    (is (= [:seon.ns/name :seon.fn/sym :seon.schema/key]
           (mapv (fn [row]
                   (cond
                     (:seon.ns/name row) :seon.ns/name
                     (:seon.fn/sym row) :seon.fn/sym
                     (:seon.schema/key row) :seon.schema/key))
                 (:seon.db/program forward))))
    (is (not-any? #(or (contains? % :seon.fn/created-at)
                       (contains? % :seon.schema/created-at))
                  (:seon.db/program forward)))
    (is (= [{:seon.user/id "user"}
            {:my.kb.shared/id "shared"}]
           (:seon.db/initial-data forward)))))

(deftest invalid-complete-program-fails-before-session-open
  (async done
    (let [original-descriptor launch/process-launch-descriptor
          original-open db/open-session!
          original-core client/index-core!
          original-schemas client/index-schemas
          opened? (atom false)]
      (set! launch/process-launch-descriptor (descriptor))
      (set! client/index-core!
            (fn []
              [{:seon.ns/name :example.core
                :seon.ns/source "(ns example.core)"}
               {:seon.fn/sym "example.core/broken"
                :seon.fn/ns [:seon.ns/name :example.core]
                :seon.fn/source "(defn broken [value] value)"
                :seon.fn/spec
                "[:=> [:cat :example/missing] :example/missing]"}]))
      (set! client/index-schemas
            (fn []
              [{:seon.schema/key :example/id :seon.schema/form ":int"}]))
      (set! db/open-session!
            (fn [_]
              (reset! opened? true)
              (js/Promise.resolve {})))
      (-> (client/open-database-session!
           {:seon.client/initialize? true})
          (.then
           (fn [_]
             (is false "invalid complete projection was admitted")))
          (.catch
           (fn [_]
             (testing "the full schema and function projection is validated first"
               (is (false? @opened?)))))
          (.finally
           (fn []
             (set! launch/process-launch-descriptor original-descriptor)
             (set! client/index-core! original-core)
             (set! client/index-schemas original-schemas)
             (set! db/open-session! original-open)
             (done)))))))

(defn- shadow-ready-state
  []
  (let [owner (js-obj)]
    (assoc @client/!state
           ::client/launch-capability {::client/autonomous? true}
           ::client/advertisement-owner owner
           ::client/advertisement-interest-key :runtime-advertisement
           ::client/resumable-agent-ids ["root"])))

(deftest completed-reload-ensures-before-publication-and-rehosting
  (async done
    (let [original-state @client/!state
          original-attached? db/attached?
          original-open client/open-database-session!
          original-begin admission/begin-publication!
          original-publish admission/publish-committed!
          original-unavailable admission/mark-unavailable!
          original-resume agent/resume!
          original-install agent-loop/install-ticker!
          original-heartbeat client/start-heartbeat!
          effects (atom [])
          finish-resume (atom nil)
          rehost-started-resolve (atom nil)
          rehost-started
          (js/Promise.
           (fn [resolve _] (reset! rehost-started-resolve resolve)))
          finish (atom nil)
          finished (js/Promise. (fn [resolve _] (reset! finish resolve)))]
      (reset! client/!state (shadow-ready-state))
      (set! db/attached? (constantly true))
      (set! admission/begin-publication!
            (fn [] (swap! effects conj :close) true))
      (set! client/open-database-session!
            (fn [request]
              (swap! effects conj [::ensure-acquire request])
              (js/Promise.resolve {::db/db {:db-name "default"}})))
      (set! admission/publish-committed!
            (fn []
              (swap! effects conj :publish)
              (js/Promise.resolve
               {::admission/published? true
                ::admission/instrumentation {}})))
      (set! admission/mark-unavailable!
            (fn [_] (swap! effects conj :unavailable) true))
      (set! agent/resume!
            (fn [request]
              (swap! effects conj [::resume request])
              (@rehost-started-resolve true)
              (js/Promise.
               (fn [resolve _]
                 (reset! finish-resume resolve)))))
      (set! agent-loop/install-ticker!
            (fn [] (swap! effects conj :ticker)))
      (set! client/start-heartbeat!
            (fn []
              (swap! effects conj :heartbeat)
              (@finish true)))
      (is (true? (client/shadow-build-notify! {:type :build-start})))
      (is (true? (client/shadow-build-notify! {:type :build-complete})))
      (-> rehost-started
          (.then
           (fn [_]
             (is (= [:close
                     [::ensure-acquire {::client/initialize? true}]
                     :publish
                     [::resume {:seon.agent/id "root"}]]
                    @effects)
                 "ticker waits for the one rehost Promise")
             (@finish-resume
              {:seon.agent.runtime/resumed? true
               :seon.agent/id "root"})
             finished))
          (.then
           (fn [_]
             (is (= [:close
                     [::ensure-acquire {::client/initialize? true}]
                     :publish
                     [::resume {:seon.agent/id "root"}]
                     :ticker
                     :heartbeat]
                    @effects)
                 "reload has one ensure/acquire, publication, and rehost order")))
          (.catch
           (fn [error]
             (is false (str "completed reload rejected: " error
                            "\n" (.-stack error)))))
          (.finally
           (fn []
             (reset! client/!state original-state)
             (set! db/attached? original-attached?)
             (set! client/open-database-session! original-open)
             (set! admission/begin-publication! original-begin)
             (set! admission/publish-committed! original-publish)
             (set! admission/mark-unavailable! original-unavailable)
             (set! agent/resume! original-resume)
             (set! agent-loop/install-ticker! original-install)
             (set! client/start-heartbeat! original-heartbeat)
             (done)))))))

(deftest failed-reload-ensure-keeps-admission-closed-and-skips-rehost
  (async done
    (let [original-state @client/!state
          original-attached? db/attached?
          original-open client/open-database-session!
          original-begin admission/begin-publication!
          original-publish admission/publish-committed!
          original-unavailable admission/mark-unavailable!
          original-resume agent/resume!
          original-install agent-loop/install-ticker!
          original-heartbeat client/start-heartbeat!
          effects (atom [])
          admission-open? (atom true)
          finish (atom nil)
          finished (js/Promise. (fn [resolve _] (reset! finish resolve)))]
      (reset! client/!state (shadow-ready-state))
      (set! db/attached? (constantly true))
      (set! admission/begin-publication!
            (fn []
              (reset! admission-open? false)
              (swap! effects conj :close)
              true))
      (set! client/open-database-session!
            (fn [_]
              (swap! effects conj :ensure-acquire)
              (js/Promise.reject (js/Error. "ensure failed"))))
      (set! admission/publish-committed!
            (fn []
              (reset! admission-open? true)
              (swap! effects conj :publish)
              (js/Promise.resolve {::admission/published? true})))
      (set! admission/mark-unavailable!
            (fn [_]
              (reset! admission-open? false)
              (swap! effects conj :unavailable)
              (@finish true)
              true))
      (set! agent/resume!
            (fn [_]
              (swap! effects conj :rehost)
              (js/Promise.resolve {:seon.agent.runtime/resumed? true})))
      (set! agent-loop/install-ticker!
            (fn [] (swap! effects conj :ticker)))
      (set! client/start-heartbeat!
            (fn [] (swap! effects conj :heartbeat)))
      (is (true? (client/shadow-build-notify! {:type :build-start})))
      (is (true? (client/shadow-build-notify! {:type :build-complete})))
      (-> finished
          (.then
           (fn [_]
             (is (false? @admission-open?))
             (is (= [:close :ensure-acquire :unavailable] @effects)
                 "failed ensure cannot publish, rehost, tick, or heartbeat")))
          (.catch
           (fn [error]
             (is false (str "failed ensure proof rejected: " error
                            "\n" (.-stack error)))))
          (.finally
           (fn []
             (reset! client/!state original-state)
             (set! db/attached? original-attached?)
             (set! client/open-database-session! original-open)
             (set! admission/begin-publication! original-begin)
             (set! admission/publish-committed! original-publish)
             (set! admission/mark-unavailable! original-unavailable)
             (set! agent/resume! original-resume)
             (set! agent-loop/install-ticker! original-install)
             (set! client/start-heartbeat! original-heartbeat)
             (done)))))))
