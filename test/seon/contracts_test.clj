(ns seon.contracts-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [seon.contracts :as contracts]
            [seon.db :as db]))

(deftest a-generated-task-is-the-declaration-its-task-file-holds
  (let [database (db/db)
        generated (contracts/task database 'seon.fs/attributes)
        stored (db/pull database [:seon.task/ns :seon.task/name :seon.task/kind :seon.task/intent
                                  :seon.task/must-read :seon.task/evidence]
                        [:seon.task/sym 'seon.fs/contract-attributes])]
    (is (= generated (-> stored (update :seon.task/must-read set) (update :seon.task/evidence set))))))

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
  (is (= {'seon.fs/attributes #{}} (contracts/findings (db/db) ['seon.fs/attributes]))))

(deftest a-generated-intent-sends-a-nil-input-to-its-caller
  ;; The call evidence is not read: the rule is in every generated intent.
  (doseq [function-symbol ['seon.fs/attributes 'seon.contracts/report]]
    (let [intent (:seon.task/intent (contracts/task (db/db) function-symbol))]
      (is (re-find #"change the caller so it does not pass nil" intent))
      (is (re-find #"absence is no key" intent)))))
