(ns seon.cluster.prompt-test
  "Acceptance for the derived prompt (context-blocks seal, 2026-07-28).

  The prompt is a projection assembled from the agent's blocks through
  the ONE router, so the acceptance is about PRESENCE and ABSENCE,
  never exact prose: the trigger's content is in it, the agent's
  namespace is in it, and the interrupted warning is in it exactly when
  the facts that cause it are. A test that pinned wording would break
  on every edit and prove nothing.

  MIGRATED with the N3 seal revision: the request names the HELD RUN
  (the run's creating transaction records its trigger as tx-meta), the
  prose lives in `seon.context` projections selected by the agent's
  planted membership, and the fixture is the canonical database
  population — never a hand-installed attribute list."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.prompt :as prompt]
            [seon.test-support :as support])
  (:import [java.util Date]))

(def ^:private now (Date. 1700000000000))
(def ^:private agent-id "agent-a")
(def ^:private message-id "message-1")
(def ^:private run-id "run-current")

(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(def ^:private seed-blocks
  "The intended core seed membership (contract §3.5's table). Owned by
  the seed-and-membership package in production; planted here because a
  membership is the test's own data."
  [{:seon.render.block/name :identity :seon.render.block/band :anchor
    :seon.render.block/priority 0
    :seon.render/ai 'seon.context/identity-ai}
   {:seon.render.block/name :execution :seon.render.block/band :anchor
    :seon.render.block/priority 10
    :seon.render/ai 'seon.context/execution-ai}
   {:seon.render.block/name :peers :seon.render.block/band :anchor
    :seon.render.block/priority 20
    :seon.render/ai 'seon.context/peers-ai}
   ;; THE NEIGHBOURHOOD VIEW replaces the retired `:interruption` and
   ;; `:continuity` blocks (owner ruling 2026-07-28 post-midnight #2:
   ;; static context blocks shrink toward the scaffold). Every
   ;; behavioural class below is unchanged and now proves the SURVIVING
   ;; mechanism — the run and receipt family lenses, reached by walking
   ;; the agent's own connections.
   {:seon.render.block/name :namespace :seon.render.block/band :dynamic
    :seon.render.block/priority 80
    :seon.render/ai 'seon.render.agent/namespace-ai}
   {:seon.render.block/name :trigger :seon.render.block/band :dynamic
    :seon.render.block/priority 90
    :seon.render/ai 'seon.context/trigger-ai}])

(defn- with-database [body]
  (support/with-database
    (fn [connection]
      (d/transact connection
                  [{:seon.cluster.agent/id agent-id
                    :seon.cluster.agent/blocks seed-blocks}
                   {:seon.cluster.message/id message-id
                    :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                    :seon.cluster.message/content "count the widgets"
                    :seon.cluster.message/at now}])
      (body connection))))

(defn- open-run!
  "Open the held run the prompt derives for — claim-early, exactly as
  the loop does: the creating transaction records the trigger as
  tx-meta and the agent pointer names the run."
  ([connection] (open-run! connection message-id))
  ([connection trigger-message-id]
   (d/transact connection
               {:tx-data [{:seon.cluster.run/id run-id
                           :seon.cluster.run/agent
                           [:seon.cluster.agent/id agent-id]
                           :seon.cluster.run/opened-at (Date. 1700000003000)}
                          {:seon.cluster.agent/id agent-id
                           :seon.cluster.agent/run
                           [:seon.cluster.run/id run-id]}]
                :tx-meta {:seon.db/trigger
                          [:seon.cluster.message/id trigger-message-id]}})))

(def ^:private request
  {:seon.cluster.run/id run-id
   :seon.cluster.agent/id agent-id
   :seon.sci.admit/caps caps})

(defn- text-of [connection]
  (:seon.cluster.prompt/text (prompt/prompt (d/db connection) request)))

(deftest a-clean-prompt-carries-the-trigger-and-the-namespace
  (with-database
    (fn [connection]
      (open-run! connection)
      (let [text (text-of connection)]
        (is (string? text))
        (is (str/includes? text "count the widgets")
            "what the agent was asked")
        (is (str/includes? text (str "my.agents." agent-id))
            "where its defns land")
        (is (not (str/includes? (str/lower-case text) "interrupt"))
            "and nothing about a crash that did not happen")))))

(deftest the-loop-sends-exactly-the-returned-text
  ;; the rendered context IS the handoff: the text is exactly the
  ;; reduction of the contribution texts, and no consumer reruns a
  ;; projection to reconstruct it
  (with-database
    (fn [connection]
      (open-run! connection)
      (let [rendered (prompt/prompt (d/db connection) request)]
        (is (= (:seon.cluster.prompt/text rendered)
               (str/join "\n\n"
                         (map :seon.context.contribution/text
                              (:seon.context/contributions rendered)))))))))

(deftest a-cut-fold-is-presented-once
  (with-database
    (fn [connection]
      ;; a prior run: two forms, form 0 done, form 1 interrupted
      (d/transact connection
                  [{:seon.cluster.run/id "run-0"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at now
                    :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))
                    :seon.cluster.run/closed-at now}
                   {:seon.cluster.run.form/id "run-0-0"
                    :seon.cluster.run.form/run [:seon.cluster.run/id "run-0"]
                    :seon.cluster.run.form/ordinal 0
                    :seon.cluster.run.form/source "(+ 1 1)"}
                   {:seon.cluster.run.form/id "run-0-1"
                    :seon.cluster.run.form/run [:seon.cluster.run/id "run-0"]
                    :seon.cluster.run.form/ordinal 1
                    :seon.cluster.run.form/source "(my.fs/write! \"x\")"}
                   {:seon.cluster.eval/id "e-0"
                    :seon.cluster.eval/run [:seon.cluster.run/id "run-0"]
                    :seon.cluster.eval/ordinal 0
                    :seon.cluster.eval/at now
                    :seon.cluster.eval/result-edn "2"}
                   {:seon.cluster.eval/id "e-1"
                    :seon.cluster.eval/run [:seon.cluster.run/id "run-0"]
                    :seon.cluster.eval/ordinal 1
                    :seon.cluster.eval/at now
                    :seon.cluster.eval/interrupted-at now}])
      (open-run! connection)
      (let [text (text-of connection)
            lower (str/lower-case text)]
        (is (str/includes? lower "interrupt")
            "the agent is told its last run was cut")
        (is (str/includes? lower "may")
            "and told honestly: the effect MAY have happened")
        (is (= 1 (count (re-seq #"(?i)interrupt" text)))
            "ONE sentence, never per-eval markers")))))

(deftest a-lost-model-call-is-also-presented
  ;; the night ruling's other crash shape: cut before the plan existed,
  ;; so there are no receipts to derive from and nothing re-called it
  (with-database
    (fn [connection]
      (d/transact connection
                  [{:seon.cluster.run/id "run-0"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at now
                    :seon.cluster.run/closed-at now}])
      (open-run! connection)
      (is (str/includes? (str/lower-case (text-of connection)) "interrupt")
          "an agent whose request vanished must be told it vanished"))))

(deftest the-warning-is-not-shadowed-by-the-run-being-planned
  ;; The live crash drill measured this: the loop CLAIMS BEFORE it calls
  ;; the model, so by the time a prompt is derived the agent's newest run
  ;; is the one this prompt is for. Reading the newest run found a clean
  ;; run and warned about nothing — the warning was derivable before the
  ;; claim and gone after it, so the live agent never saw it.
  (with-database
    (fn [connection]
      ;; a crashed run: cut before its plan, custody already released by
      ;; boot recovery, then settled closed
      (d/transact connection
                  [{:seon.cluster.run/id "run-crashed"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at (Date. 1000)
                    :seon.cluster.run/closed-at (Date. 2000)}])
      ;; claim-early: the loop opens and claims the NEW run, and only
      ;; then derives the prompt for it
      (open-run! connection)
      (testing "AFTER the new run opens, the warning is there — this is
                the prompt the live agent actually reads"
        (let [text (text-of connection)]
          (is (str/includes? (str/lower-case text) "interrupt"))
          (is (= 1 (count (re-seq #"(?i)interrupt" text)))
              "still exactly one sentence, not one per prior run"))))))

(deftest a-clean-previous-run-warns-about-nothing
  ;; the other half of the rule: excluding the run being planned must not
  ;; turn every prompt into a warning
  (with-database
    (fn [connection]
      (d/transact connection
                  [{:seon.cluster.run/id "run-fine"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at (Date. 1000)
                    :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))
                    :seon.cluster.run/closed-at (Date. 2000)}
                   {:seon.cluster.run.form/id "ff-0"
                    :seon.cluster.run.form/run [:seon.cluster.run/id "run-fine"]
                    :seon.cluster.run.form/ordinal 0
                    :seon.cluster.run.form/source "(+ 1 1)"}
                   {:seon.cluster.eval/id "ee-0"
                    :seon.cluster.eval/run [:seon.cluster.run/id "run-fine"]
                    :seon.cluster.eval/ordinal 0
                    :seon.cluster.eval/at (Date. 1500)
                    :seon.cluster.eval/result-edn "2"}])
      (open-run! connection)
      (is (not (str/includes? (str/lower-case (text-of connection))
                              "interrupt"))
          "a previous run that completed cleanly warns about nothing"))))

(deftest a-missing-trigger-refuses
  (with-database
    (fn [connection]
      ;; the held run's creating transaction names NO trigger — a
      ;; prompt with nothing to answer is a caller bug
      (d/transact connection
                  [{:seon.cluster.run/id run-id
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at now}
                   {:seon.cluster.agent/id agent-id
                    :seon.cluster.agent/run [:seon.cluster.run/id run-id]}])
      ;; the rule is named, not merely "an exception" — a stub that
      ;; throws must not satisfy this
      (is (= :seon.cluster.prompt/no-trigger
             (:seon.cluster.prompt/rule
              (support/refusal-data
               #(prompt/prompt (d/db connection) request))))))))

;;; ---------------------------------------------------------------------------
;;; The collaboration pieces: who else exists, who asked, what I was doing
;;; ---------------------------------------------------------------------------

(deftest a-lone-agent-is-not-told-about-a-population-it-does-not-have
  (with-database
    (fn [connection]
      (open-run! connection)
      (is (not (str/includes? (text-of connection) "Other agents"))
          "the sentence is present exactly while the facts that cause
           it are — an agent alone in a cluster is told nothing about
           agents"))))

(deftest peers-are-named-so-delegation-is-possible-at-all
  (with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id "bob"}
                              {:seon.cluster.agent/id "carol"}])
      (open-run! connection)
      (let [text (text-of connection)]
        (is (str/includes? text "bob"))
        (is (str/includes? text "carol"))
        (is (str/includes? text "my.message/send")
            "and HOW to reach them, or naming them is a tease")
        (is (not (str/includes? text (str "Other agents in this cluster: "
                                          agent-id)))
            "an agent is never listed as its own peer")))))

(deftest the-sender-is-named-when-a-message-came-from-an-agent
  (with-database
    (fn [connection]
      (d/transact connection
                  [{:seon.cluster.agent/id "bob"}
                   {:seon.cluster.message/id "from-bob"
                    :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                    :seon.cluster.message/from [:seon.cluster.agent/id "bob"]
                    :seon.cluster.message/content "there are 25"
                    :seon.cluster.message/at now}])
      (open-run! connection "from-bob")
      (let [text (text-of connection)]
        (is (str/includes? text "Agent bob sent you"))
        (is (str/includes? text "there are 25")))))
  (testing "and a message from outside names no sender rather than inventing one"
    (with-database
      (fn [connection]
        (open-run! connection)
        (is (str/includes? (text-of connection) "You have been asked"))))))

(deftest a-pause-note-is-the-continuity-a-delegating-agent-has
  (with-database
    (fn [connection]
      ;; a previous run that ENDED in my.run/wait: the disposition is
      ;; the last form's admitted value, so the note is already durable
      ;; in that form's result-edn and nothing new is stored
      (d/transact
       connection
       [{:seon.cluster.run/id "run-paused"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (Date. 1000)
         :seon.cluster.run/closed-at (Date. 2000)
         :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))}
        {:seon.cluster.eval/id "run-paused-0"
         :seon.cluster.eval/run [:seon.cluster.run/id "run-paused"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (Date. 1500)
         :seon.cluster.eval/result-edn
         (pr-str {:my.run/disposition :wait
                  :my.run/note "asked bob for the prime count for the human"})}])
      (open-run! connection)
      (let [text (text-of connection)]
        (is (str/includes? text "asked bob for the prime count for the human")
            "the note my.run/wait promised the agent's next prompt")
        (is (str/includes? text "It paused, leaving this note")
            "the run's own lens, one hop from the agent"))))

  (testing "a previous run that COMPLETED leaves no pause note"
    (with-database
      (fn [connection]
        (d/transact
         connection
         [{:seon.cluster.run/id "run-done"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (Date. 1000)
           :seon.cluster.run/closed-at (Date. 2000)
           :seon.cluster.run/plan-digest (apply str (repeat 64 "b"))}
          {:seon.cluster.eval/id "run-done-0"
           :seon.cluster.eval/run [:seon.cluster.run/id "run-done"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (Date. 1500)
           :seon.cluster.eval/result-edn
           (pr-str {:my.run/disposition :completed
                    :my.run/result "done"})}])
        (open-run! connection)
        (is (not (str/includes? (text-of connection) "It paused"))))))

  (testing "and unreadable result-edn answers nil rather than taking the turn down"
    (with-database
      (fn [connection]
        (d/transact
         connection
         [{:seon.cluster.run/id "run-junk"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (Date. 1000)
           :seon.cluster.run/closed-at (Date. 2000)
           :seon.cluster.run/plan-digest (apply str (repeat 64 "c"))}
          {:seon.cluster.eval/id "run-junk-0"
           :seon.cluster.eval/run [:seon.cluster.run/id "run-junk"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (Date. 1500)
           :seon.cluster.eval/result-edn "#not-a-tag{"}])
        (open-run! connection)
        (is (string? (text-of connection)))))))

(deftest a-completed-disposition-does-not-mislabel-an-unstarted-suffix
  (with-database
    (fn [connection]
      (d/transact
       connection
       [{:seon.cluster.run/id "run-completed-early"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (Date. 1000)
         :seon.cluster.run/closed-at (Date. 2000)
         :seon.cluster.run/plan-digest (apply str (repeat 64 "d"))}
        {:seon.cluster.run.form/id "run-completed-early-0"
         :seon.cluster.run.form/run
         [:seon.cluster.run/id "run-completed-early"]
         :seon.cluster.run.form/ordinal 0
         :seon.cluster.run.form/source "(my.run/complete \"done\")"}
        {:seon.cluster.run.form/id "run-completed-early-1"
         :seon.cluster.run.form/run
         [:seon.cluster.run/id "run-completed-early"]
         :seon.cluster.run.form/ordinal 1
         :seon.cluster.run.form/source "(+ 1 1)"}
        {:seon.cluster.eval/id "run-completed-early-0"
         :seon.cluster.eval/run
         [:seon.cluster.run/id "run-completed-early"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (Date. 1500)
         :seon.cluster.eval/result-edn
         (pr-str {:my.run/disposition :completed
                  :my.run/result "done"})}])
      (open-run! connection)
      (let [text (text-of connection)]
        (is (str/includes? text "It completed."))
        (is (not (str/includes? text "interrupted"))
            "a terminal disposition intentionally leaves its suffix
             unstarted; it is not crash evidence")))))

(deftest the-execution-grammar-is-always-present
  (with-database
    (fn [connection]
      (open-run! connection)
      (let [text (text-of connection)]
        (is (str/includes? text "my.run/complete"))
        (is (str/includes? text "my.run/wait")
            "an agent that is never told the grammar cannot end a run")))))
