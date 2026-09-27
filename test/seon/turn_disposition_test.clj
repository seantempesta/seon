(ns seon.turn-disposition-test
  "A refused disposition settles as the form's own complete evaluation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as support]
            [seon.turn :as turn]))

;; Root turn 62279 on `default` (2026-09-23 16:44Z) replied with a vector
;; holding `(my.agent/done)`. The disposition rule refused it, but the
;; replacement evaluation lacked its namespace, ending namespace, print
;; options and admission record, so `settle-batch!` refused the whole batch
;; and the agent never saw why.
(deftest a-refused-disposition-settles-through-resume
  (support/with-database
   (fn [connection]
     ;; The executing branch's own root agent and its program namespace entity.
     (let [namespace-name (get-in (db/pull @connection
                                           [{:seon.agent/namespace [:seon.ns/name]}]
                                           [:seon.agent/id "root"])
                                  [:seon.agent/namespace :seon.ns/name])
           process "disposition-process"
           run-id "disposition-run"
           source "['(+ 1 2) (my.agent/done)]"]
       (support/seed-cluster! connection "disposition-resume")
       (support/transacted!
        connection
        (turn/open-tx {:seon.turn/id run-id
                       :seon.turn/agent [:seon.agent/id "root"]
                       :seon.turn/opened-tx "datomic.tx"}))
       (support/transacted!
        connection
        (turn/plan-tx {:seon.turn/id run-id :seon.db.process/id process
                       :seon.turn/starting-ns [:seon.ns/name namespace-name]
                       :seon.turn/sources [{:seon.cluster.eval/source source}]}))
       (let [ctx (support/fork-cluster-ctx connection)
             cluster (support/cluster-handle
                      {:seon.db/connection connection
                       :seon.cluster/name "disposition-resume"
                       :seon.db.process/id process
                       :seon.sci.eval/ctx ctx})
             ;; The owner derives the resume situation from the stored turn.
             work (turn/next-agent-work @connection {:seon.agent/id "root"})
             report (#'turn/resume-turn
                     {:seon.turn.loop/cluster cluster
                      :seon.turn.loop/work work
                      :seon.turn.loop/now (java.util.Date.)
                      :seon.turn.loop/report
                      (fn [outcome forms-run & _]
                        {:seon.agent/id "root" :seon.turn/id run-id
                         :seon.turn.work/situation :resume
                         :seon.turn.loop/forms-run forms-run
                         :seon.turn.loop/outcome outcome})})
             settled (db/pull @connection
                              [:seon.cluster.eval/error :seon.eval/shown
                               {:seon.cluster.eval/ns [:seon.ns/name]}]
                              [:seon.cluster.eval/id (turn/receipt-identity run-id 0)])
             refusals (db/q '[:find [?error ...] :in $ ?operation
                              :where [?error :seon.error/operation ?operation]]
                            @connection 'seon.turn/settle-batch!)]
         (is (symbol? namespace-name))
         (is (= [:resume run-id 0]
                (mapv work [:seon.turn.work/situation :seon.turn/id
                            :seon.cluster.eval/ordinal]))
             (pr-str work))
         (is (= 1 (:seon.turn.loop/forms-run report)) (pr-str report))
         (is (str/includes? (str (:seon.cluster.eval/error settled))
                            "must be the last form of your reply")
             (pr-str settled))
         (is (string? (:seon.eval/shown settled)))
         (is (= namespace-name (get-in settled [:seon.cluster.eval/ns :seon.ns/name])))
         (is (empty? refusals) "settlement accepted the refused disposition"))))))
