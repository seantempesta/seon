(ns evolving-session-exploration
  (:require [clojure.pprint :as pprint]
            [seon.cluster.work :as work]
            [seon.db :as db]
            [seon.test-support :as support]))

(def agent-id "evolving")
(def passive-agent-id "passive")

(defn- query-new-messages
  [database shown-basis subject-id]
  (db/q
   '[:find ?id ?tx ?user-id ?process-id
     :in $current $delta ?agent-id
     :where
     [$current ?agent :seon.cluster.agent/id ?agent-id]
     [$delta ?message :seon.cluster.message/to ?agent ?tx]
     [$current ?message :seon.cluster.message/id ?id]
     [$delta ?tx :seon.db/user ?user]
     [$current ?user :seon.cluster.agent/id ?user-id]
     [$delta ?tx :seon.db/process ?process]
     [$current ?process :seon.db.process/id ?process-id]]
   database
   (db/since database shown-basis)
   subject-id))

(defn- query-plan-change
  [database shown-basis subject-id]
  (db/q
   '[:find ?instruction-id ?text ?tx ?user-id ?process-id
     :in $current $delta ?agent-id
     :where
     [$current ?agent :seon.cluster.agent/id ?agent-id]
     [$delta ?agent :seon.cluster.agent/instructions ?instruction ?tx]
     [$current ?instruction :seon.cluster.instruction/id ?instruction-id]
     [$current ?instruction :seon.cluster.instruction/text ?text]
     [$delta ?tx :seon.db/user ?user]
     [$current ?user :seon.cluster.agent/id ?user-id]
     [$delta ?tx :seon.db/process ?process]
     [$current ?process :seon.db.process/id ?process-id]]
   database
   (db/since database shown-basis)
   subject-id))

(defn- print-section
  [label value]
  (println (str "\n=== " label " ==="))
  (pprint/pprint value))

(defn- run-probe
  [connection]
  (db/transact!
   connection
   [{:seon.db.process/id "repl"}
    {:seon.ns/name 'my.agents.root}
    {:seon.cluster.agent/id "root"
     :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.root]}
    {:seon.ns/name 'my.agents.evolving}
    {:seon.cluster.agent/id agent-id
     :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.evolving]}
    {:seon.ns/name 'my.agents.passive}
    {:seon.cluster.agent/id passive-agent-id
     :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.passive]}])

  ;; Retained T0 has already taught the listing/read vocabulary and shown this
  ;; database basis. Generation below is only the suffix after that basis.
  (let [shown-basis (db/basis-t @connection)
        message-tx
        (db/transact!
         connection
         {:tx-data
          [{:seon.cluster.message/id "t1-message"
            :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
            :seon.cluster.message/content "Continue from the retained history."
            :seon.cluster.message/at #inst "2026-08-12T18:00:00.000-00:00"}]
          :tx-meta
          {:seon.db/user [:seon.cluster.agent/id "root"]
           :seon.db/process [:seon.db.process/id "repl"]}})
        after-message (:db-after message-tx)
        message-delta (query-new-messages after-message shown-basis agent-id)
        message-basis (db/basis-t after-message)
        t1-forms
        [{:seon.repl/form
          (list
           'db/q
           (list 'quote
                 '[:find ?id ?tx ?user-id ?process-id
                   :in $current $delta ?agent-id
                   :where
                   [$current ?agent :seon.cluster.agent/id ?agent-id]
                   [$delta ?message :seon.cluster.message/to ?agent ?tx]
                   [$current ?message :seon.cluster.message/id ?id]
                   [$delta ?tx :seon.db/user ?user]
                   [$current ?user :seon.cluster.agent/id ?user-id]
                   [$delta ?tx :seon.db/process ?process]
                   [$current ?process :seon.db.process/id ?process-id]])
           '(db/db)
           (list 'db/since '(db/db) shown-basis)
           agent-id)}
         {:seon.repl/comment "; Root sent a new message."
          :seon.repl/form '(my.message/read "t1-message")}]
        work-after-message
        (work/next-agent-work
         after-message
         {:seon.cluster.agent/id agent-id
          :seon.cluster.run/process "probe-process"})]
    (print-section "T1 shown basis" shown-basis)
    (print-section "T1 delta rows" message-delta)
    (print-section "T1 generated suffix" t1-forms)
    (print-section "T1 wake-derived work" work-after-message)
    (print-section "T1 self-erasure at appended basis"
                   (query-new-messages after-message message-basis agent-id))

    (let [passive-shown-basis (db/basis-t after-message)
          before-work
          (work/next-agent-work
           after-message
           {:seon.cluster.agent/id passive-agent-id
            :seon.cluster.run/process "probe-process"})
          plan-tx
          (db/transact!
           connection
           {:tx-data
            [{:seon.cluster.instruction/id :passive-plan
              :seon.cluster.instruction/text
              "Inspect the new source facts, then wait for an explicit message."}
             [:db/add [:seon.cluster.agent/id passive-agent-id]
              :seon.cluster.agent/instructions
              [:seon.cluster.instruction/id :passive-plan]]]
            :tx-meta
            {:seon.db/user [:seon.cluster.agent/id "root"]
             :seon.db/process [:seon.db.process/id "repl"]}})
          after-plan (:db-after plan-tx)
          plan-delta
          (query-plan-change after-plan passive-shown-basis passive-agent-id)
          plan-basis (db/basis-t after-plan)
          after-work
          (work/next-agent-work
           after-plan
           {:seon.cluster.agent/id passive-agent-id
            :seon.cluster.run/process "probe-process"})
          t2-forms
          [{:seon.repl/comment "; Root updated my plan."
            :seon.repl/form
            (list
             'db/pull
             '(db/db)
             (list 'quote
                   '[{:seon.cluster.agent/instructions
                      [:seon.cluster.instruction/id
                       :seon.cluster.instruction/text]}])
             [:seon.cluster.agent/id passive-agent-id])}]]
      (print-section "T2 shown basis" passive-shown-basis)
      (print-section "T2 provenance delta rows" plan-delta)
      (print-section "T2 generated passive suffix" t2-forms)
      (print-section "T2 work before/after passive change"
                     {:before before-work :after after-work})
      (print-section "T2 self-erasure at appended basis"
                     (query-plan-change after-plan plan-basis
                                        passive-agent-id)))))

(defn -main
  "Run the evolving-session delta and provenance exploration."
  [& _]
  (support/with-database run-probe))
