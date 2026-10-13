(ns seon.step-test
  "The off-the-shelf step templates' default proofs and the handoffs they gate
  (`docs/seon/schedule/cards/TASK-CHAIN.md#4-off-the-shelf-templates`)."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.task :as task]
            [seon.test]
            [seon.test-support :as support]))

(def ^:private templates
  "The five off-the-shelf templates, in chain order."
  '[seon.step/survey seon.step/define seon.step/design seon.step/prove seon.step/implement])

(deftest ^{:seon/tasks #{'seon.task/chain-intake-approval}}
  survey-waits-for-the-latest-user-approval
  ;; TASK-CHAIN I3 (`docs/seon/schedule/cards/TASK-CHAIN.md#interfaces`)
  (support/with-running-agent
   (fn [running _ connection]
     (let [chain! (requiring-resolve 'seon.task/chain!)
           answer! (requiring-resolve 'my.agent/answer!)
           survey-task 'work/survey-fix
           _ (chain! connection {:seon.task/ns 'work :seon.task/name "fix" :seon.task/intent "Fix the observed failure."
                                 :seon.task/answer-schema :seon.task/approval}
                     templates {})
           {question :seon.test/question survey :seon.db/connection handle :seon.turn.loop/cluster}
           (support/user-reply! running "survey" "Approve this intent?" "Not yet")
           replies (fn [] (db/q '[:find [?id ...] :in $ ?q
                                  :where [?c :seon.message/id ?q] [?m :seon.message/caused-by ?c] [?m :seon.message/id ?id]]
                                (db/db survey) question))
           latest (fn [seen] (first (remove (set seen) (replies))))
           answer (fn [reply approved?] (answer! {:seon.db/connection survey :seon.task/sym survey-task
                                                  :my.message/id reply :seon.answer/value {:seon.task/approved? approved?}}))
           survey-green? (fn [] (:seon.test/passed? (seon.test/run {:seon.test/policy :named :seon.test/execution handle
                                                                    :seon.test/recording-connection survey
                                                                    :seon.test/task survey-task
                                                                    :seon.test/changed ['seon.step-test/survey-handoff]})))
           first-reply (latest [])]
       (is (false? (survey-green?)) "no answer: the send and the wait alone never complete Survey")
       (answer first-reply false)
       (is (false? (survey-green?)) "a rejection stays red")
       (support/user-post! running "survey" question "Approve")
       (let [approving (latest [first-reply])]
         (answer approving true)
         (support/user-post! running "survey" question "On second thought, no")
         (answer (latest [first-reply approving]) false)
         (is (false? (survey-green?)) "an older approval followed by a rejection stays red")
         (support/user-post! running "survey" question "Approve now")
         (answer (latest [first-reply approving]) true)
         (is (true? (survey-green?)) "the latest matching approval makes the Survey proof green")
         (support/transacted! connection [[:db/add [:seon.task/id (task/task-handle 'work "fix")]
                                           :seon.task/intent "Fix the observed failure and its neighbor."]])
         (is (false? (survey-green?)) "an approval of an earlier intent stays red"))))))
