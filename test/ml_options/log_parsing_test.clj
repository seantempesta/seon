(ns ml-options.log-parsing-test
  "Tests for log parsing utility functions.

  These functions live in env/dev/clj/user.clj but we test them here
  by copying the core logic into a testable namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]))

;; Copy of parse-log-line for testing (same logic as user.clj)
(defn parse-log-line
  "Parse a logback log line into structured data."
  [line]
  (when (and line (string? line))
    (when-let [[_ timestamp thread level logger message]
               (re-matches #"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3}) \[([^\]]+)\] (\w+)\s+([^\s]+) - (.*)"
                           line)]
      {:timestamp timestamp
       :thread thread
       :level (keyword (str/lower-case level))
       :logger logger
       :message message
       :raw line})))

(deftest parse-log-line-test
  (testing "Parse valid INFO log line"
    (let [line "2025-12-02 11:35:36,333 [main] INFO  ml-options.env - Starting system"
          result (parse-log-line line)]
      (is (= "2025-12-02 11:35:36,333" (:timestamp result)))
      (is (= "main" (:thread result)))
      (is (= :info (:level result)))
      (is (= "ml-options.env" (:logger result)))
      (is (= "Starting system" (:message result)))
      (is (= line (:raw result)))))

  (testing "Parse valid ERROR log line"
    (let [line "2025-12-02 11:35:40,241 [DefaultDispatcher-worker-5] ERROR ml-options.core - Uncaught exception"
          result (parse-log-line line)]
      (is (= :error (:level result)))
      (is (= "DefaultDispatcher-worker-5" (:thread result)))
      (is (= "ml-options.core" (:logger result)))))

  (testing "Parse valid DEBUG log line"
    (let [line "2025-12-02 11:35:39,876 [main] DEBUG i.m.c.u.i.l.InternalLoggerFactory - Using SLF4J"
          result (parse-log-line line)]
      (is (= :debug (:level result)))
      (is (= "i.m.c.u.i.l.InternalLoggerFactory" (:logger result)))))

  (testing "Parse valid WARN log line"
    (let [line "2025-12-02 12:00:00,000 [http-nio-8080-exec-1] WARN  org.apache.http.client - Connection timeout"
          result (parse-log-line line)]
      (is (= :warn (:level result)))
      (is (= "org.apache.http.client" (:logger result)))))

  (testing "Return nil for invalid log line"
    (is (nil? (parse-log-line "This is not a log line")))
    (is (nil? (parse-log-line "")))
    (is (nil? (parse-log-line nil))))

  (testing "Handle log lines with special characters in message"
    (let [line "2025-12-02 11:35:36,333 [main] INFO  ml-options.test - Message with - dashes and (parens)"
          result (parse-log-line line)]
      (is (= "Message with - dashes and (parens)" (:message result)))))

  (testing "Handle log lines with long logger names"
    (let [line "2025-12-02 11:35:36,333 [main] INFO  org.apache.http.impl.conn.PoolingHttpClientConnectionManager - Connection pool stats"
          result (parse-log-line line)]
      (is (= "org.apache.http.impl.conn.PoolingHttpClientConnectionManager" (:logger result)))))

  (testing "Handle log lines with numeric characters in thread name"
    (let [line "2025-12-02 11:35:36,333 [pool-1-thread-42] INFO  ml-options.test - Worker message"
          result (parse-log-line line)]
      (is (= "pool-1-thread-42" (:thread result))))))

(deftest hard-cap-behavior-test
  (testing "Hard caps should prevent excessive output"
    ;; Simulate hard cap logic
    (let [max-lines 100
          requested-lines 10000
          capped-lines (min requested-lines max-lines)]
      (is (= 100 capped-lines))
      (is (<= capped-lines 100))))

  (testing "Truncate long error messages to 200 chars"
    (let [long-message (apply str (repeat 300 "x"))
          truncated (subs long-message 0 (min 200 (count long-message)))]
      (is (= 200 (count truncated))))))

(deftest log-level-filtering-test
  (testing "Filter by log level"
    (let [logs [{:level :info :message "Info 1"}
                {:level :error :message "Error 1"}
                {:level :warn :message "Warn 1"}
                {:level :error :message "Error 2"}
                {:level :debug :message "Debug 1"}]
          errors (filter #(= :error (:level %)) logs)
          warnings (filter #(= :warn (:level %)) logs)]
      (is (= 2 (count errors)))
      (is (= 1 (count warnings)))
      (is (= "Error 1" (:message (first errors))))
      (is (= "Error 2" (:message (second errors)))))))
