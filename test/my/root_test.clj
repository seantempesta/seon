(ns my.root-test
  "Root's tools over merge requests: listing the open ones, closing one with its reason, and the one close
  owner's answer. Each test asserts one behavior on data."
  (:require [clojure.test :refer [deftest is]]
            [my.root :as my.root]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(defn- with-merge-request
  "Run `f` with a connection whose root holds an assigned task with one merge request recorded on main, and
  that request's merge task."
  [f]
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "my-root" {})
     (cluster/seed-root-agent! connection "my-root" cluster/boot-process-identity)
     (test-support/assign-task! connection "root")
     (let [database (db/db connection)
           branch (get-in database [:config :branch])
           work (task/assignment database "root" branch)
           requested (task/request-merge! connection "root" work
                                          {:seon.task/branch branch :seon.task/commit (random-uuid)
                                           :seon.task/proved #{['tq.assigned/proves-root (apply str (repeat 64 "a"))]}
                                           :seon.task/run-id "run-my-root" :seon.task/program (apply str (repeat 64 "b"))})]
       (f connection (:seon.task/merge requested))))))

(defn- root-context
  "Root's supplied world on `connection`, as call preparation hands it to my.root."
  [connection]
  (let [ctx (test-support/fork-cluster-ctx connection)]
    {:seon.db/connection connection :seon.sci.eval/ctx ctx :my.program/base-ctx ctx
     :seon.store/store (:seon.store/store (test-support/execution-handle connection))}))

(deftest instructions-are-the-laws-root-applies
  (is (every? string? (:my.root/laws (my.root/instructions)))))

(deftest merge-requests-lists-the-open-request-with-its-merge-and-close-offered
  (with-merge-request
   (fn [connection merge-task]
     (let [[request :as open] (my.root/merge-requests {:seon.db/db (db/db connection)})]
       (is (= [merge-task] (mapv :seon.task/merge open)))
       (is (= (list 'my.root/merge! {:seon.task/merge merge-task}) (first (:seon.render/operations request))))))))

(deftest close-records-closed-with-its-reason-and-the-request-leaves-the-open-list
  (with-merge-request
   (fn [connection merge-task]
     (let [closed (my.root/close! {:my.program/context (root-context connection) :seon.task/merge merge-task
                                   :seon.task/reason "The change belongs in seon.db."})]
       (is (= {:seon.task/id merge-task :seon.task/outcome :closed :seon.task/reason "The change belongs in seon.db."} closed))
       (is (= {:seon.task/closed-by :seon.task/outcome} (task/resolution (db/db connection) merge-task)))
       (is (= [] (my.root/merge-requests {:seon.db/db (db/db connection)})))))))

(deftest merging-a-task-with-no-open-request-answers-the-refusal-naming-it
  (with-merge-request
   (fn [connection _]
     (let [refused (my.root/merge! {:my.program/context (root-context connection) :seon.task/merge "000000000000"})]
       (is (= "000000000000" (:seon.error/offending refused)))))))
