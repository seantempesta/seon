(ns seon.issue.task-ingestion-test
  "Task ingestion (spec 7.9): keyword-valued edges, task EDN by id, lookups both ways."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn]
            [seon.issue :as issue]
            [seon.test-support :as test-support]))

(def ^:private subject-source
  (str "(ns tq.a (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f {:seon/tasks #{:tq.a.task/x} :seon/must-read ['tq.a/g]} [v] v)\n"
       "(defn g {:seon/tasks #{:tq.a.task/x}} [v] v)\n"
       "(deftest ^{:seon/tasks #{:tq.a.task/x}} proves (is (= 1 (f 1))))\n"))

(def ^:private task-files
  {"resources/seon/tasks/tq.a.edn" "[#:seon.task{:id :tq.a.task/x :intent \"Prove ingestion.\" :must-read #{tq.a/g}}]"})

(defn- rows-of [source]
  (let [root (io/file "tmp" "task-ingestion-test" (str (random-uuid)))]
    (try (io/make-parents (io/file root "tq/a.clj"))
         (spit (io/file root "tq/a.clj") source)
         (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
         (finally (test-support/delete-recursively! root)))))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (some #(:seon.task/refused (ex-data %)) (take-while some? (iterate ex-cause e))))))

(deftest ingested-tasks-answer-both-ways-and-reingestion-writes-nothing
  (test-support/with-database
   (fn [connection]
     (let [rows (rows-of subject-source)]
       (is (:db-after (db/transact! connection (seon.fn/reconcile-tx @connection rows []))))
       (is (:seon.db/transaction-report (issue/index-tasks! {:seon.db/connection connection :seon.task/files task-files})))
       (is (= {:subjects '#{tq.a/f tq.a/g} :proofs '#{tq.a/proves} :must-read-referrers '#{tq.a/f :tq.a.task/x}}
              (issue/task-edges @connection :tq.a.task/x)))
       (is (= #{:tq.a.task/x} (issue/open-tasks @connection '[:seon.fn/sym tq.a/g])))
       (is (not-any? #(contains? % :tq.a.task/x) (vals (issue/task-findings @connection))))
       (is (= {} (issue/index-tasks! {:seon.db/connection connection :seon.task/files task-files})))
       (is (empty? (seon.fn/reconcile-tx @connection rows [])))
       (let [edited (rows-of (str/replace subject-source "(defn g {:seon/tasks #{:tq.a.task/x}} [v] v)" "(defn g [v] v)"))]
         (is (:db-after (db/transact! connection (seon.fn/reconcile-tx @connection edited []))))
         (is (= {:subjects '#{tq.a/f} :proofs '#{tq.a/proves} :must-read-referrers #{}}
                (issue/task-edges @connection :tq.a.task/x))))))))

(deftest malformed-annotations-duplicate-and-dangling-ids-refuse-by-name
  (let [head "(ns tq.a (:require [clojure.test :refer [deftest is]] [tq.b :as-alias b]))\n"]
    (is (= :not-a-set-of-ids (refusal #(rows-of (str head "(defn ^:seon/tasks f [v] v)\n")))))
    (is (= :auto-resolved-id (refusal #(rows-of (str head "(defn f {:seon/tasks #{::b/x}} [v] v)\n")))))
    (is (= :metadata-after-test-name (refusal #(rows-of (str head "(deftest t {:seon/tasks #{:tq.a.task/x}} (is true))\n")))))
    (is (= :unquoted-link (refusal #(rows-of (str head "(defn f {:seon/must-read [clojure.core/inc]} [v] v)\n"))))))
  (test-support/with-database
   (fn [connection]
     (let [refused #(refusal (fn [] (issue/tasks-tx @connection {"resources/seon/tasks/tq.a.edn" %})))]
       (is (= :duplicate-id (refused "[#:seon.task{:id :tq.a.task/x :intent \"a\"} #:seon.task{:id :tq.a.task/x :intent \"b\"}]")))
       (is (= :invalid-entry (refused "[#:seon.task{:id :tq.a.task/x :intnet \"a\"}]")))
       (is (= :misplaced-id (refused "[#:seon.task{:id :tq.b.task/x :intent \"a\"}]"))))
     (is (:db-after (db/transact! connection (seon.fn/reconcile-tx @connection (rows-of subject-source) []))))
     (is (contains? (:dangling (issue/task-findings @connection)) :tq.a.task/x)))))
