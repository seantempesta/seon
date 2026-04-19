(ns seon.ai.datalevin-test
  "Tests for seon.ai.datalevin - Datalevin storage for AI sessions and messages."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as conn]
            [seon.ai.datalevin :as dl]
            [seon.test-utils]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:private test-dir "tmp/test-datalevin")

(defn- create-test-conn []
  (d/create-conn test-dir dl/datalevin-schema))

(defn- cleanup-test-db []
  (let [dir (java.io.File. test-dir)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defn with-test-conn [f]
  (cleanup-test-db)
  (let [conn (create-test-conn)
        fake-mgr {::conn/port 0
                  ::conn/connections (atom {:seon.ai {::conn/connection conn}})}]
    (try
      (binding [db/*direct-mode* true
                db/*conn-manager* fake-mgr]
        (dl/reset-stats!)
        (f))
      (finally
        (d/close conn)
        (cleanup-test-db)))))

(use-fixtures :each with-test-conn)

;;; ---------------------------------------------------------------------------
;;; Stats Tests
;;; ---------------------------------------------------------------------------

(deftest stats-returns-namespaced-keys-test
  (testing "stats returns map with namespaced keys"
    (let [s (dl/stats)]
      (is (map? s))
      (is (contains? s ::dl/write-count))
      (is (contains? s ::dl/error-count))
      ;; ::last-write-at is optional — absent until first write
      (is (contains? s ::dl/session-writes))
      (is (contains? s ::dl/message-writes)))))

(deftest stats-initial-values-test
  (testing "stats has correct initial values after reset"
    (dl/reset-stats!)
    (let [s (dl/stats)]
      (is (= 0 (::dl/write-count s)))
      (is (= 0 (::dl/error-count s)))
      (is (nil? (::dl/last-write-at s)))
      (is (= 0 (::dl/session-writes s)))
      (is (= 0 (::dl/message-writes s))))))

;;; ---------------------------------------------------------------------------
;;; Configuration Tests
;;; ---------------------------------------------------------------------------

(deftest enabled-toggle-test
  (testing "set-enabled! toggles write behavior"
    (is (true? @dl/enabled?) "enabled by default")

    (dl/set-enabled! false)
    (is (false? @dl/enabled?))

    (dl/set-enabled! true)
    (is (true? @dl/enabled?))))

;;; ---------------------------------------------------------------------------
;;; Write Operation Tests
;;; ---------------------------------------------------------------------------

(deftest save-session-increments-stats-test
  (testing "successful save-session! increments stats"
    (dl/reset-stats!)
    (let [entity {:seon/id "ses-test123"
                  :seon.ai/status :active}
          result (dl/save-session! entity)
          s (dl/stats)]
      (is (true? result))
      (is (= 1 (::dl/write-count s)))
      (is (= 1 (::dl/session-writes s)))
      (is (some? (::dl/last-write-at s))))))

(deftest save-message-increments-stats-test
  (testing "successful save-message! increments stats"
    (dl/reset-stats!)
    (let [entity {:seon/id "msg-test123"
                  :seon.ai/session-id "ses-test123"
                  :seon.ai/role "assistant"
                  :seon.ai/content "Hello"}
          result (dl/save-message! entity)
          s (dl/stats)]
      (is (true? result))
      (is (= 1 (::dl/write-count s)))
      (is (= 1 (::dl/message-writes s))))))

(deftest save-disabled-returns-false-test
  (testing "writes return false when disabled"
    (dl/set-enabled! false)
    (try
      (let [entity {:seon/id "ses-disabled"}
            result (dl/save-session! entity)]
        (is (false? result)))
      (finally
        (dl/set-enabled! true)))))

;;; ---------------------------------------------------------------------------
;;; Query Operation Tests
;;; ---------------------------------------------------------------------------

(deftest count-entities-test
  (testing "count-entities returns session and message counts"
    ;; Save some data first
    (dl/save-session! {:seon/id "ses-count1"})
    (dl/save-session! {:seon/id "ses-count2"})
    (dl/save-message! {:seon/id "msg-count1" :seon.ai/session-id "ses-count1"})

    (let [counts (dl/count-entities)]
      (is (map? counts))
      (is (= 2 (:sessions counts)))
      (is (= 1 (:messages counts))))))

(deftest query-sessions-test
  (testing "query-sessions retrieves stored sessions"
    (dl/save-session! {:seon/id "ses-query1"
                       :seon.ai/status :active})

    (let [sessions (dl/query-sessions {:limit 10})]
      (is (vector? sessions))
      (is (>= (count sessions) 1))
      (is (some #(= "ses-query1" (::dl/entity-id %)) sessions)))))

(deftest dl-get-session-test
  (testing "dl-get-session finds session by logical ID"
    (dl/save-session! {:seon/id "ses-find1"
                       :seon.ai/status :active
                       :seon.ai/namespace "seon.test"})

    (let [session (dl/dl-get-session "ses-find1")]
      (is (some? session))
      (is (= "ses-find1" (:seon/id session)))
      (is (= "seon.test" (:seon.ai/namespace session))))))

(deftest dl-get-session-not-found-test
  (testing "dl-get-session returns nil for non-existent session"
    (let [session (dl/dl-get-session "ses-nonexistent")]
      (is (nil? session)))))

(deftest dl-get-messages-test
  (testing "dl-get-messages retrieves messages for session"
    (let [session-id "ses-msgs"]
      (dl/save-session! {:seon/id session-id})
      (dl/save-message! {:seon/id "msg-1"
                         :seon.ai/session-id session-id
                         :seon.ai/role "user"
                         :seon.ai/content "Hello"
                         :seon.ai/timestamp (java.time.Instant/now)})
      (dl/save-message! {:seon/id "msg-2"
                         :seon.ai/session-id session-id
                         :seon.ai/role "assistant"
                         :seon.ai/content "Hi there"
                         :seon.ai/timestamp (java.time.Instant/now)})

      (let [messages (dl/dl-get-messages session-id)]
        (is (vector? messages))
        (is (= 2 (count messages)))))))

;;; ---------------------------------------------------------------------------
;;; Update Operation Tests
;;; ---------------------------------------------------------------------------

(deftest update-session-test
  (testing "update-session! modifies existing session"
    (dl/save-session! {:seon/id "ses-update"
                       :seon.ai/status :active})

    (dl/update-session! {:seon/id "ses-update"
                         :seon.ai/status :completed
                         :seon.ai/cost-usd 0.05})

    (let [session (dl/dl-get-session "ses-update")]
      (is (= :completed (:seon.ai/status session)))
      (is (= 0.05 (:seon.ai/cost-usd session))))))

;;; ---------------------------------------------------------------------------
;;; Schema Bridge Tests
;;; ---------------------------------------------------------------------------

(deftest datalevin-schema-derived-test
  (testing "datalevin-schema is a non-empty map derived from entity schemas"
    (is (map? dl/datalevin-schema))
    (is (pos? (count dl/datalevin-schema))))

  (testing "entity-id has unique identity constraint"
    (is (= :db.unique/identity
           (get-in dl/datalevin-schema [:seon.ai.datalevin/entity-id :db/unique]))))

  (testing "core attributes have correct Datalevin types"
    (is (= :db.type/string
           (get-in dl/datalevin-schema [:seon.ai.datalevin/entity-id :db/valueType])))
    (is (= :db.type/keyword
           (get-in dl/datalevin-schema [:seon.ai.datalevin/entity-type :db/valueType])))
    (is (= :db.type/instant
           (get-in dl/datalevin-schema [:seon.ai.datalevin/stored-at :db/valueType]))))

  (testing "AI attributes have correct types"
    (is (= :db.type/string
           (get-in dl/datalevin-schema [:seon.ai/session-id :db/valueType])))
    (is (= :db.type/keyword
           (get-in dl/datalevin-schema [:seon.ai/status :db/valueType])))
    (is (= :db.type/double
           (get-in dl/datalevin-schema [:seon.ai/cost-usd :db/valueType])))
    (is (= :db.type/long
           (get-in dl/datalevin-schema [:seon.ai/input-tokens :db/valueType])))
    (is (= :db.type/string
           (get-in dl/datalevin-schema [:seon.ai.claude/message-type :db/valueType]))))

  (testing "tx metadata attributes are included"
    (is (contains? dl/datalevin-schema :seon.db.tx/at))
    (is (contains? dl/datalevin-schema :seon.db.tx/caller))))
