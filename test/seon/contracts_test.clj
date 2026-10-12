(ns seon.contracts-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [seon.contracts :as contracts]
            [seon.db :as db]))

(deftest a-generated-task-is-the-declaration-its-task-file-holds
  (let [database (db/db)
        generated (contracts/task database 'seon.fs/attributes)
        stored (db/pull database [:seon.task/ns :seon.task/name :seon.task/intent :seon.task/references]
                        [:seon.task/sym 'seon.fs/contract-attributes])]
    (is (= generated (dissoc stored :seon.task/references)))
    (is (= '#{seon.fs/attributes seon.contracts/gaps} (set (:seon.task/references stored)))
        "the writer derives the names the generated intent cites")))

(deftest the-report-partitions-uncontracted-source-functions-once
  (let [database (db/db)
        {:seon.contracts/keys [eligible host-bound unused named-by-task]} (contracts/report database)
        parts (map set [eligible host-bound unused named-by-task])]
    (is (some #{'seon.fs/attributes} named-by-task) "a function whose contract task exists is skipped")
    (is (not-any? #{'seon.contracts/gaps} (concat eligible host-bound unused named-by-task)))
    (is (= (reduce + (map count parts)) (count (apply set/union parts))) "the partitions are disjoint")))

(deftest an-absent-function-is-one-absent-gap
  (is (= [{:seon.fn/sym 'seon.contracts-test/absent :seon.contracts/absent true}]
         (contracts/gaps (db/db) 'seon.contracts-test/absent))))

(deftest a-contract-task-is-not-its-own-finding
  (let [found (contracts/findings (db/db) ['seon.fs/attributes])]
    (is (= #{'seon.fs/attributes} (set (keys found))))
    (is (not (contains? (found 'seon.fs/attributes) 'seon.fs/contract-attributes))
        "the contract task citing its function is not a task its work filed")))

(deftest a-generated-intent-sends-a-nil-input-to-its-caller
  ;; The call evidence is not read: the rule is in every generated intent.
  (doseq [function-symbol ['seon.fs/attributes 'seon.contracts/report]]
    (let [intent (:seon.task/intent (contracts/task (db/db) function-symbol))]
      (is (re-find #"change the caller so it does not pass nil" intent))
      (is (re-find #"absence is no key" intent)))))
