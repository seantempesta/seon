(ns seon.db.writer-read-spend-test
  "Bounded per-identity read-spend attribution at the writer."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.lru :as lru]
            [seon.db.writer :as writer]))

(defn- agent-identity [agent-id]
  {:seon.db/user [:seon.agent/id agent-id]
   :seon.db/process [:seon.db.process/id :seon.db.process/repl]})

(deftest accrue-read-spend-totals-per-identity
  (let [window (-> (lru/weighted-lru 8 0)
                   (writer/accrue-read-spend
                    (agent-identity "agent-a")
                    {:datahike.resource/work 100
                     :datahike.resource/result-count 3
                     :datahike.resource/result-weight 40
                     ::writer/read-spend-nanos 1000})
                   (writer/accrue-read-spend
                    (agent-identity "agent-a")
                    {:datahike.resource/work 50
                     ::writer/read-spend-nanos 500})
                   (writer/accrue-read-spend
                    (agent-identity "agent-b")
                    {:datahike.resource/work 7}))
        entries (lru/weighted-entries window)]
    (is (= {::writer/read-spend-requests 2
            ::writer/read-spend-work 150
            ::writer/read-spend-results 3
            ::writer/read-spend-result-weight 40
            ::writer/read-spend-nanos 1500}
           (get entries (agent-identity "agent-a")))
        "one identity accumulates across its requests")
    (is (= 7 (::writer/read-spend-work
              (get entries (agent-identity "agent-b"))))
        "identities do not share totals")
    (testing "an evidence-free sample still counts the request"
      (let [window (writer/accrue-read-spend
                    window (agent-identity "agent-c") {})]
        (is (= {::writer/read-spend-requests 1
                ::writer/read-spend-work 0
                ::writer/read-spend-results 0
                ::writer/read-spend-result-weight 0
                ::writer/read-spend-nanos 0}
               (get (lru/weighted-entries window)
                    (agent-identity "agent-c"))))))
    (testing "unattributed reads aggregate under the empty identity"
      (let [window (writer/accrue-read-spend
                    window {} {:datahike.resource/work 9})]
        (is (= 9 (::writer/read-spend-work
                  (get (lru/weighted-entries window) {}))))))))

(deftest read-spend-window-is-structurally-bounded
  (let [limit 4
        window (reduce (fn [window i]
                         (writer/accrue-read-spend
                          window
                          (agent-identity (str "agent-" i))
                          {:datahike.resource/work 1}))
                       (lru/weighted-lru limit 0)
                       (range 40))
        entries (lru/weighted-entries window)]
    (is (= limit (count entries))
        "the LRU evicts least-recently-active identities")
    (is (contains? entries (agent-identity "agent-39"))
        "the most recent identity is always retained")
    (is (not (contains? entries (agent-identity "agent-0")))
        "the oldest identity was evicted")))
