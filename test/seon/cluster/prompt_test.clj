(ns seon.cluster.prompt-test
  "Sealed acceptance draft for the derived prompt (N3, package 1).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The prompt is a
  projection, so the acceptance is about PRESENCE and ABSENCE, never
  exact prose: the trigger's content is in it, the agent's namespace is
  in it, and the interrupted warning is in it exactly when the facts
  that cause it are. A test that pinned wording would break on every
  edit and prove nothing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.prompt :as prompt]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.util Date]))

(def ^:private attributes
  [:seon.cluster.agent/id :seon.cluster.agent/run
   :seon.cluster.run/id :seon.cluster.run/agent :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at :seon.cluster.run/process
   :seon.cluster.run/claim-epoch :seon.cluster.run/lease-until
   :seon.cluster.run/plan-digest :seon.cluster.run/forms
   :seon.cluster.run.form/id :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal :seon.cluster.run.form/source
   :seon.cluster.eval/id :seon.cluster.eval/run :seon.cluster.eval/ordinal
   :seon.cluster.eval/claim-epoch :seon.cluster.eval/at
   :seon.cluster.eval/status :seon.cluster.eval/result-edn
   :seon.cluster.eval/error
   :seon.cluster.message/id :seon.cluster.message/to
   :seon.cluster.message/content :seon.cluster.message/at
   :seon.cluster.message/from
   :seon.db/trigger])

(def ^:private now (Date. 1700000000000))
(def ^:private agent-id "agent-a")
(def ^:private message-id "message-1")

(defn- with-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection (schema.datahike/malli->datahike-schema attributes))
      (d/transact connection
                  [{:seon.cluster.agent/id agent-id}
                   {:seon.cluster.message/id message-id
                    :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                    :seon.cluster.message/content "count the widgets"
                    :seon.cluster.message/at now}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(def ^:private request
  {:seon.cluster.agent/id agent-id
   :seon.cluster.message/id message-id})

(deftest a-clean-prompt-carries-the-trigger-and-the-namespace
  (with-database
    (fn [connection]
      (let [text (prompt/prompt (d/db connection) request)]
        (is (string? text))
        (is (str/includes? text "count the widgets")
            "what the agent was asked")
        (is (str/includes? text (str "my.agents." agent-id))
            "where its defns land")
        (is (not (str/includes? (str/lower-case text) "interrupt"))
            "and nothing about a crash that did not happen")))))

(deftest a-cut-fold-is-presented-once
  (with-database
    (fn [connection]
      ;; a prior run: two forms, form 0 done, form 1 interrupted
      (d/transact connection
                  [{:seon.cluster.run/id "run-0"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at now
                    :seon.cluster.run/claim-epoch 1
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
                    :seon.cluster.eval/claim-epoch 1
                    :seon.cluster.eval/at now
                    :seon.cluster.eval/status :done
                    :seon.cluster.eval/result-edn "2"}
                   {:seon.cluster.eval/id "e-1"
                    :seon.cluster.eval/run [:seon.cluster.run/id "run-0"]
                    :seon.cluster.eval/ordinal 1
                    :seon.cluster.eval/claim-epoch 1
                    :seon.cluster.eval/at now
                    :seon.cluster.eval/status :interrupted}])
      (let [text (prompt/prompt (d/db connection) request)
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
                    :seon.cluster.run/claim-epoch 1
                    :seon.cluster.run/closed-at now}])
      (let [text (prompt/prompt (d/db connection) request)]
        (is (str/includes? (str/lower-case text) "interrupt")
            "an agent whose request vanished must be told it vanished")))))

(deftest the-warning-is-not-shadowed-by-the-run-being-planned
  ;; The live crash drill measured this: the loop CLAIMS BEFORE it calls
  ;; the model, so by the time a prompt is derived the agent's newest run
  ;; is the one this prompt is for. Reading the newest run found a clean
  ;; run and warned about nothing — the warning was derivable before the
  ;; claim and gone after it, so the live agent never saw it. This is the
  ;; exact sequence, in order.
  (with-database
    (fn [connection]
      ;; a crashed run: cut before its plan, custody already released by
      ;; boot recovery, then settled closed
      (d/transact connection
                  [{:seon.cluster.run/id "run-crashed"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at (Date. 1000)
                    :seon.cluster.run/claim-epoch 2
                    :seon.cluster.run/closed-at (Date. 2000)}])
      (testing "before the new run opens, the warning is derivable"
        (is (str/includes?
             (str/lower-case (prompt/prompt (d/db connection) request))
             "interrupt")))

      ;; claim-early: the loop opens and claims the NEW run, and only
      ;; then derives the prompt for it
      (d/transact connection
                  [{:seon.cluster.run/id "run-new"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at (Date. 3000)
                    :seon.cluster.run/claim-epoch 1
                    :seon.cluster.run/process "live-1"}
                   {:seon.cluster.agent/id agent-id
                    :seon.cluster.agent/run [:seon.cluster.run/id "run-new"]}])
      (testing "AFTER it opens, the warning must still be there — this is
                the prompt the live agent actually reads"
        (let [text (prompt/prompt (d/db connection) request)]
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
                    :seon.cluster.run/claim-epoch 1
                    :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))
                    :seon.cluster.run/closed-at (Date. 2000)}
                   {:seon.cluster.run.form/id "ff-0"
                    :seon.cluster.run.form/run [:seon.cluster.run/id "run-fine"]
                    :seon.cluster.run.form/ordinal 0
                    :seon.cluster.run.form/source "(+ 1 1)"}
                   {:seon.cluster.eval/id "ee-0"
                    :seon.cluster.eval/run [:seon.cluster.run/id "run-fine"]
                    :seon.cluster.eval/ordinal 0
                    :seon.cluster.eval/claim-epoch 1
                    :seon.cluster.eval/at (Date. 1500)
                    :seon.cluster.eval/status :done
                    :seon.cluster.eval/result-edn "2"}
                   {:seon.cluster.run/id "run-now"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at (Date. 3000)
                    :seon.cluster.run/claim-epoch 1
                    :seon.cluster.run/process "live-1"}
                   {:seon.cluster.agent/id agent-id
                    :seon.cluster.agent/run [:seon.cluster.run/id "run-now"]}])
      (is (not (str/includes?
                (str/lower-case (prompt/prompt (d/db connection) request))
                "interrupt"))
          "a previous run that completed cleanly warns about nothing"))))

(deftest a-missing-trigger-refuses
  (with-database
    (fn [connection]
      ;; the rule is named, not merely "an exception" — a stub that
      ;; throws must not satisfy this
      (is (= :seon.cluster.prompt/no-trigger
             (try
               (prompt/prompt (d/db connection)
                              {:seon.cluster.agent/id agent-id
                               :seon.cluster.message/id "nope"})
               ::committed
               (catch Exception failure
                 (:seon.cluster.prompt/rule (ex-data failure)))))))))

;;; ---------------------------------------------------------------------------
;;; The collaboration pieces: who else exists, who asked, what I was doing
;;; ---------------------------------------------------------------------------

(deftest a-lone-agent-is-not-told-about-a-population-it-does-not-have
  (with-database
    (fn [connection]
      (is (not (str/includes? (prompt/prompt (d/db connection) request)
                              "Other agents"))
          "the sentence is present exactly while the facts that cause
           it are — an agent alone in a cluster is told nothing about
           agents"))))

(deftest peers-are-named-so-delegation-is-possible-at-all
  (with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id "bob"}
                              {:seon.cluster.agent/id "carol"}])
      (let [text (prompt/prompt (d/db connection) request)]
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
      (let [text (prompt/prompt (d/db connection)
                                {:seon.cluster.agent/id agent-id
                                 :seon.cluster.message/id "from-bob"})]
        (is (str/includes? text "Agent bob sent you"))
        (is (str/includes? text "there are 25")))))
  (testing "and a message from outside names no sender rather than inventing one"
    (with-database
      (fn [connection]
        (is (str/includes? (prompt/prompt (d/db connection) request)
                           "You have been asked"))))))

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
         :seon.cluster.eval/claim-epoch 1
         :seon.cluster.eval/at (Date. 1500)
         :seon.cluster.eval/status :done
         :seon.cluster.eval/result-edn
         (pr-str {:my.run/disposition :wait
                  :my.run/note "asked bob for the prime count for the human"})}])
      (let [text (prompt/prompt (d/db connection) request)]
        (is (str/includes? text "asked bob for the prime count for the human")
            "the note my.run/wait promised the agent's next prompt")
        (is (str/includes? text "You paused your previous run")))))

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
           :seon.cluster.eval/claim-epoch 1
           :seon.cluster.eval/at (Date. 1500)
           :seon.cluster.eval/status :done
           :seon.cluster.eval/result-edn
           (pr-str {:my.run/disposition :completed
                    :my.run/result "done"})}])
        (is (not (str/includes? (prompt/prompt (d/db connection) request)
                                "You paused"))))))

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
           :seon.cluster.eval/claim-epoch 1
           :seon.cluster.eval/at (Date. 1500)
           :seon.cluster.eval/status :done
           :seon.cluster.eval/result-edn "#not-a-tag{"}])
        (is (string? (prompt/prompt (d/db connection) request)))))))
