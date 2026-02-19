(ns seon.ctx-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.ctx :as ctx]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:private test-dir (atom nil))
(def ^:private test-conn (atom nil))

(defn- temp-dir []
  (str "tmp/test-ctx-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn- setup-datalevin! []
  (let [dir (temp-dir)
        conn (d/get-conn dir ctx/datalevin-schema)]
    (reset! test-dir dir)
    (reset! test-conn conn)
    conn))

(defn- teardown-datalevin! []
  (when-let [conn @test-conn]
    (try (d/close conn) (catch Exception _)))
  ;; Clean up temp dir
  (when-let [dir @test-dir]
    (try
      (let [f (java.io.File. dir)]
        (doseq [child (reverse (file-seq f))]
          (.delete child)))
      (catch Exception _))))

(use-fixtures :each
  (fn [f]
    (setup-datalevin!)
    (try (f) (finally (teardown-datalevin!)))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest create-get-update-destroy-test
  (testing "Full lifecycle: create, get, update, destroy"
    (let [a (ctx/create! {::ctx/instance-id "t001"
                          ::ctx/initial-value {:count 0}
                          ::ctx/persist? false
                          ::ctx/sse-push? false})]
      (is (some? a) "create! returns an atom")
      (is (= {:count 0} @a) "initial value is set")

      ;; get-atom
      (is (= a (ctx/get-atom {::ctx/instance-id "t001"})))

      ;; get-value
      (is (= {:count 0} (ctx/get-value {::ctx/instance-id "t001"})))

      ;; update!
      (ctx/update! {::ctx/instance-id "t001"
                    ::ctx/f assoc
                    ::ctx/args [:count 42]})
      (is (= {:count 42} (ctx/get-value {::ctx/instance-id "t001"})))

      ;; destroy
      (is (true? (ctx/destroy! {::ctx/instance-id "t001"})))
      (is (nil? (ctx/get-atom {::ctx/instance-id "t001"})))
      (is (false? (ctx/destroy! {::ctx/instance-id "t001"}))
          "destroying non-existent returns false"))))

(deftest get-nonexistent-test
  (testing "Getting non-existent instance returns nil"
    (is (nil? (ctx/get-atom {::ctx/instance-id "xxxx"})))
    (is (nil? (ctx/get-value {::ctx/instance-id "xxxx"})))))

;;; ---------------------------------------------------------------------------
;;; Persistence Tests
;;; ---------------------------------------------------------------------------

(deftest persistence-round-trip-test
  (testing "Create with persist?, update, then load! gets latest"
    (let [conn @test-conn
          a (ctx/create! {::ctx/conn conn
                          ::ctx/instance-id "p001"
                          ::ctx/initial-value {:hello "world"}
                          ::ctx/persist? true
                          ::ctx/sse-push? false
                          ::ctx/debounce-ms 0})]
      ;; Update the atom
      (swap! a assoc :key "value")

      ;; Wait for debounced persist (debounce-ms=0 but still scheduled)
      (Thread/sleep 100)

      ;; Load from Datalevin
      (let [loaded (ctx/load! {::ctx/conn conn
                               ::ctx/instance-id "p001"})]
        (is (some? loaded) "loaded data is not nil")
        (is (= "value" (:key loaded)))
        (is (= "world" (:hello loaded))))

      (ctx/destroy! {::ctx/instance-id "p001"}))))

(deftest manual-persist-test
  (testing "persist! manually saves current value"
    (let [conn @test-conn
          _a (ctx/create! {::ctx/conn conn
                           ::ctx/instance-id "p002"
                           ::ctx/initial-value {:manual true}
                           ::ctx/persist? false
                           ::ctx/sse-push? false})]
      ;; Manual persist
      (ctx/persist! {::ctx/conn conn ::ctx/instance-id "p002"})

      ;; Load
      (let [loaded (ctx/load! {::ctx/conn conn ::ctx/instance-id "p002"})]
        (is (= true (:manual loaded))))

      (ctx/destroy! {::ctx/instance-id "p002"}))))

;;; ---------------------------------------------------------------------------
;;; Non-serializable Values
;;; ---------------------------------------------------------------------------

(deftest non-serializable-stripped-test
  (testing "Non-serializable values are stripped on persist"
    (let [conn @test-conn
          a (ctx/create! {::ctx/conn conn
                          ::ctx/instance-id "ns01"
                          ::ctx/initial-value {:safe "data"}
                          ::ctx/persist? false
                          ::ctx/sse-push? false})]
      ;; Add a non-serializable value (an atom)
      (swap! a assoc :unsafe (atom :nope) :also-safe 42)

      ;; Manual persist
      (ctx/persist! {::ctx/conn conn ::ctx/instance-id "ns01"})

      ;; Load - unsafe key should be gone
      (let [loaded (ctx/load! {::ctx/conn conn ::ctx/instance-id "ns01"})]
        (is (= "data" (:safe loaded)))
        (is (= 42 (:also-safe loaded)))
        (is (nil? (:unsafe loaded)) "non-serializable value stripped"))

      (ctx/destroy! {::ctx/instance-id "ns01"}))))

;;; ---------------------------------------------------------------------------
;;; List Instances
;;; ---------------------------------------------------------------------------

(deftest list-instances-test
  (testing "List active instances"
    (ctx/create! {::ctx/instance-id "l001"
                  ::ctx/namespace 'seon.health
                  ::ctx/persist? false
                  ::ctx/sse-push? false})
    (ctx/create! {::ctx/instance-id "l002"
                  ::ctx/namespace 'seon.trading
                  ::ctx/persist? false
                  ::ctx/sse-push? false})

    (let [instances (ctx/list-instances {})
          ids (set (map ::ctx/instance-id instances))]
      (is (contains? ids "l001"))
      (is (contains? ids "l002"))

      ;; Check namespace is tracked
      (let [l001 (first (filter #(= "l001" (::ctx/instance-id %)) instances))]
        (is (= 'seon.health (::ctx/namespace l001)))
        (is (inst? (::ctx/created-at l001)))))

    (ctx/destroy! {::ctx/instance-id "l001"})
    (ctx/destroy! {::ctx/instance-id "l002"})))

;;; ---------------------------------------------------------------------------
;;; Multiple Instances Coexist
;;; ---------------------------------------------------------------------------

(deftest multiple-instances-test
  (testing "Multiple instances coexist independently"
    (let [a1 (ctx/create! {::ctx/instance-id "m001"
                           ::ctx/initial-value {:x 1}
                           ::ctx/persist? false
                           ::ctx/sse-push? false})
          a2 (ctx/create! {::ctx/instance-id "m002"
                           ::ctx/initial-value {:x 2}
                           ::ctx/persist? false
                           ::ctx/sse-push? false})]
      ;; Update one, other is unaffected
      (swap! a1 assoc :x 100)
      (is (= 100 (:x @a1)))
      (is (= 2 (:x @a2)) "other instance unaffected")

      (ctx/destroy! {::ctx/instance-id "m001"})
      (ctx/destroy! {::ctx/instance-id "m002"}))))

(deftest load-without-instance-test
  (testing "load! works without an active instance"
    (let [conn @test-conn]
      ;; Create, persist, destroy
      (ctx/create! {::ctx/conn conn
                    ::ctx/instance-id "ld01"
                    ::ctx/initial-value {:persisted true}
                    ::ctx/persist? false
                    ::ctx/sse-push? false})
      (ctx/persist! {::ctx/conn conn ::ctx/instance-id "ld01"})
      (ctx/destroy! {::ctx/instance-id "ld01"})

      ;; Load after destroy still works from Datalevin
      (let [loaded (ctx/load! {::ctx/conn conn ::ctx/instance-id "ld01"})]
        (is (= true (:persisted loaded)))))))

;;; ---------------------------------------------------------------------------
;;; ID Generation
;;; ---------------------------------------------------------------------------

(deftest generate-id-test
  (testing "generate-id produces 4-char hex strings"
    (let [id (ctx/generate-id)]
      (is (string? id))
      (is (= 4 (count id)))
      (is (re-matches #"[a-f0-9]{4}" id))))

  (testing "generate-id produces unique values"
    (let [ids (set (repeatedly 100 ctx/generate-id))]
      (is (> (count ids) 90) "should produce mostly unique IDs"))))

;;; ---------------------------------------------------------------------------
;;; Client Tracking
;;; ---------------------------------------------------------------------------

(deftest client-tracking-lifecycle-test
  (testing "Client tracking: register, count, unregister"
    (ctx/create! {::ctx/instance-id "c001"
                  ::ctx/persist? false
                  ::ctx/sse-push? false
                  ::ctx/track-clients? true})

    ;; Initially no clients
    (is (= 0 (ctx/client-count {::ctx/instance-id "c001"})))
    (is (= #{} (ctx/clients {::ctx/instance-id "c001"})))

    ;; Register clients (use sentinel objects)
    (let [ch1 (Object.)
          ch2 (Object.)]
      (is (true? (ctx/register-client! {::ctx/instance-id "c001" ::ctx/channel ch1})))
      (is (= 1 (ctx/client-count {::ctx/instance-id "c001"})))

      (is (true? (ctx/register-client! {::ctx/instance-id "c001" ::ctx/channel ch2})))
      (is (= 2 (ctx/client-count {::ctx/instance-id "c001"})))
      (is (= #{ch1 ch2} (ctx/clients {::ctx/instance-id "c001"})))

      ;; Unregister one
      (is (true? (ctx/unregister-client! {::ctx/instance-id "c001" ::ctx/channel ch1})))
      (is (= 1 (ctx/client-count {::ctx/instance-id "c001"})))
      (is (= #{ch2} (ctx/clients {::ctx/instance-id "c001"}))))

    (ctx/destroy! {::ctx/instance-id "c001"})))

(deftest client-tracking-disabled-test
  (testing "Client operations on non-tracking instance return false"
    (ctx/create! {::ctx/instance-id "c002"
                  ::ctx/persist? false
                  ::ctx/sse-push? false
                  ::ctx/track-clients? false})

    (is (false? (ctx/register-client! {::ctx/instance-id "c002" ::ctx/channel (Object.)})))
    (is (nil? (ctx/clients {::ctx/instance-id "c002"})))
    (is (= 0 (ctx/client-count {::ctx/instance-id "c002"})))

    (ctx/destroy! {::ctx/instance-id "c002"})))

(deftest client-nonexistent-instance-test
  (testing "Client operations on nonexistent instance return false"
    (is (false? (ctx/register-client! {::ctx/instance-id "nope" ::ctx/channel (Object.)})))
    (is (false? (ctx/unregister-client! {::ctx/instance-id "nope" ::ctx/channel (Object.)})))
    (is (nil? (ctx/clients {::ctx/instance-id "nope"})))
    (is (= 0 (ctx/client-count {::ctx/instance-id "nope"})))))

;;; ---------------------------------------------------------------------------
;;; Render Function
;;; ---------------------------------------------------------------------------

(deftest set-render-fn-test
  (testing "set-render-fn! stores render function"
    (ctx/create! {::ctx/instance-id "r001"
                  ::ctx/persist? false
                  ::ctx/sse-push? false
                  ::ctx/track-clients? true})

    (let [my-fn (fn [_] [:div "hello"])]
      (is (true? (ctx/set-render-fn! {::ctx/instance-id "r001" ::ctx/render-fn my-fn})))
      (is (= my-fn (:render-fn (ctx/get-entry {::ctx/instance-id "r001"})))))

    (is (false? (ctx/set-render-fn! {::ctx/instance-id "nope" ::ctx/render-fn identity})))

    (ctx/destroy! {::ctx/instance-id "r001"})))

(deftest force-push-nonexistent-test
  (testing "force-push! on nonexistent instance returns false"
    (is (false? (ctx/force-push! {::ctx/instance-id "nope"})))))

;;; ---------------------------------------------------------------------------
;;; Namespace Helpers
;;; ---------------------------------------------------------------------------

(deftest instances-for-namespace-test
  (testing "instances-for-namespace filters by namespace"
    (ctx/create! {::ctx/instance-id "n001"
                  ::ctx/namespace 'seon.health
                  ::ctx/persist? false
                  ::ctx/sse-push? false
                  ::ctx/track-clients? true})
    (ctx/create! {::ctx/instance-id "n002"
                  ::ctx/namespace 'seon.health
                  ::ctx/persist? false
                  ::ctx/sse-push? false
                  ::ctx/track-clients? true})
    (ctx/create! {::ctx/instance-id "n003"
                  ::ctx/namespace 'seon.trading
                  ::ctx/persist? false
                  ::ctx/sse-push? false
                  ::ctx/track-clients? true})

    (let [health-instances (ctx/instances-for-namespace 'seon.health)
          ids (set (map ::ctx/instance-id health-instances))]
      (is (= 2 (count health-instances)))
      (is (contains? ids "n001"))
      (is (contains? ids "n002"))
      (is (not (contains? ids "n003"))))

    (is (= 1 (count (ctx/instances-for-namespace 'seon.trading))))
    (is (= 0 (count (ctx/instances-for-namespace 'seon.nonexistent))))

    ;; Test namespace client aggregation
    (ctx/register-client! {::ctx/instance-id "n001" ::ctx/channel :ch1})
    (ctx/register-client! {::ctx/instance-id "n002" ::ctx/channel :ch2})
    (is (= #{:ch1 :ch2} (ctx/clients-for-namespace 'seon.health)))
    (is (= 2 (ctx/client-count-for-namespace 'seon.health)))
    (is (= 0 (ctx/client-count-for-namespace 'seon.trading)))

    (ctx/destroy! {::ctx/instance-id "n001"})
    (ctx/destroy! {::ctx/instance-id "n002"})
    (ctx/destroy! {::ctx/instance-id "n003"})))
