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
