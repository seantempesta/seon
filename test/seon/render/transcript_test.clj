(ns seon.render.transcript-test
  (:require [clojure.core.async :as async]
            [clojure.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]

            [seon.config :as config]
            [seon.render :as render]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.transcript :as transcript]
            [seon.render.walk :as walk]
            [seon.repl :as repl]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(def ^:private property-seed 2026073104)
(def ^:private agent-id "transcript-agent")
(def ^:private peer-id "transcript-peer")
(def ^:private caps
  (assoc (config/result-caps (support/effective-config))
         :seon.config.eval.result/max-depth 12
         :seon.config.eval.result/max-collection 64
         :seon.config.eval.result/max-string 4096
         :seon.config.eval.result/max-source 1048576
         :seon.config.eval.result/max-nodes 4096))

(declare unit)

;;; `admitted-top-level-string-is-terminal-text` lived here while the
;;; transcript re-rendered a stored PRINT NODE into agent-visible text
;;; through its own private `bounded-result`. The shown-text cut
;;; (ff9507c1b) dissolved that step: an evaluation stores the exact text
;;; its value renderer produced (`:seon.eval/shown`) and every projection
;;; emits it unchanged (`seon.repl/value-text`, `src/seon/repl.clj:117`).
;;; The surviving class — saved shown text is terminal and no later
;;; profile re-prints it — is proven at its owner by
;;; `seon.repl-test/history-preserves-shown-text-without-applying-a-later-profile`,
;;; and at this namespace's own boundary by
;;; `historical-shown-text-keeps-its-original-elision` below.

(deftest selected-run-keeps-status-outside-agent-visible-text
  ;; STATUS CANNOT LEAK INTO AGENT-VISIBLE TEXT BECAUSE THE TURN CONCERN
  ;; EMITS NONE. The turn's evaluations reach the agent through the prompt
  ;; (`seon.repl/text`), so this pair's AI arm is empty by construction
  ;; rather than by a composition that has to keep the two apart
  ;; (`src/seon/render/transcript.clj:783`). The composition this test used
  ;; to assert — a `seon-run-transcript` section wrapping a status article
  ;; and a transcript section — is the retired second grammar.
  (let [unit {:seon.turn/id "selected-run"
              :seon.turn/agent {:seon.agent/id "selected-agent"}
              :seon.sci.admit/caps caps}]
    (is (= "" (transcript/render-run-ai unit)))
    (is (= "" (transcript/render-run-ai (assoc unit :seon.db/db ::database))))
    ;; AN UNAVAILABLE OBSERVATION IS THE TYPED UNKNOWN, never silence: no
    ;; database means the HTML arm refuses and names what was missing.
    (let [refusal (transcript/render-run-html unit)]
      (is (= :seon.render.transcript/selected-run-unavailable
             (:seon.error/kind refusal)))
      (is (str/includes? (:seon.error/message refusal) "selected run")))))

(deftest durable-history-entries-never-invent-executions
  (let [history (ns-resolve 'seon.render.transcript 'history)
        opened-at (java.util.Date. 0)
        base {:seon.render.transcript/at opened-at
              :seon.render.transcript/run-opened-at opened-at
              :seon.render.transcript/read-basis 17}
        candidates
        [(merge base
                {:seon.render.transcript/kind :message
                 :seon.render.transcript/id "message"
                 :seon.render.transcript/entity
                 {:db/id 1 :seon.message/content "hello"}})
         ;; ONE ENTITY PER (run, ordinal): a frozen form with no terminal
         ;; fact is an ORDINARY evaluation that has not settled, not a
         ;; second entry kind. `repl/text` gives it a prompt and no
         ;; response, which is what the `:input` kind used to mean.
         (merge base
                {:seon.render.transcript/kind :eval
                 :seon.render.transcript/id "submitted-form"
                 :seon.render.transcript/source "(future-work)"
                 :seon.render.transcript/namespace 'my.agents.test
                 :seon.render.transcript/entity {:db/id 2}})
         (merge base
                {:seon.render.transcript/kind :eval
                 :seon.render.transcript/id "stored-evaluation"
                 :seon.render.transcript/source "(+ 1 2)"
                 :seon.render.transcript/namespace 'my.agents.test
                 :seon.render.transcript/result "3"
                 :seon.render.transcript/entity {:db/id 3}})
         (merge base
                {:seon.render.transcript/kind :run
                 :seon.render.transcript/id "undisposed-run"
                 :seon.render.transcript/entity {:db/id 4}})]
        unit {:seon.db/db ::database
              :seon.agent/id "test"
              :seon.sci.admit/caps caps}]
    (with-redefs-fn
      {history (constantly candidates)
       #'db/q (constantly 'my.agents.test)
       #'render/render-call
       (fn [_] (throw (ex-info "current values are not executions" {})))}
      (fn []
        (let [entries (transcript/history-entries unit)
              bytes (mapv :seon.render.history/bytes entries)]
          (is (= [[:seon.render.transcript/entry :eval "submitted-form"]
                  [:seon.render.transcript/entry :eval "stored-evaluation"]]
                 (mapv :seon.render.history/call-id entries)))
          (is (= ["my.agents.test=> (future-work)"
                  "my.agents.test=> (+ 1 2)\n#:seon.repl{:value 3}"]
                 bytes)
              "a submitted form has a prompt and no response; a settled one
               answers with the one REPL response map")
          (is (not-any? #(or (str/includes? % "hello")
                             (str/includes? % "db/pull")
                             (str/includes? % "undisposed-run"))
                        bytes)))))))

(deftest stored-evaluations-are-terminal-transcript-values
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.agent/id agent-id}
        {:seon.turn/id "terminal-values" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
        {:seon.cluster.eval/id "terminal-result"
         :seon.cluster.eval/run [:seon.turn/id "terminal-values"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (java.util.Date. 1)
         :seon.cluster.eval/read-basis-transaction 17
         :seon.cluster.eval/output "once\n"
         :seon.eval/shown "1"
         :seon.cluster.eval/source "(swap! executions inc)"}
        {:seon.cluster.eval/id "terminal-error"
         :seon.cluster.eval/run [:seon.turn/id "terminal-values"]
         :seon.cluster.eval/ordinal 1
         :seon.cluster.eval/at (java.util.Date. 2)
         :seon.cluster.eval/error "stored error"
         :seon.cluster.eval/source "(throw (Exception. \"source error\"))"}
        {:seon.cluster.eval/id "terminal-string"
         :seon.cluster.eval/run [:seon.turn/id "terminal-values"]
         :seon.cluster.eval/ordinal 2
         :seon.cluster.eval/at (java.util.Date. 3)
         ;; THE STORED TEXT IS WHAT THE VALUE RENDERER SHOWED — a string
         ;; result was shown quoted, so its own newlines can never be read
         ;; back as lines of the session.
         :seon.eval/shown (pr-str "alpha\nbeta")
         :seon.cluster.eval/source "(identity \"alpha\\nbeta\")"}
        {:seon.cluster.eval/id "terminal-nested-string"
         :seon.cluster.eval/run [:seon.turn/id "terminal-values"]
         :seon.cluster.eval/ordinal 3
         :seon.cluster.eval/at (java.util.Date. 4)
         :seon.eval/shown (pr-str {:text "alpha\nbeta"})
         :seon.cluster.eval/source "(identity {:text \"alpha\\nbeta\"})"}])
      (let [receipt-render repl/response
            receipt-calls (atom 0)
            rendered
            (with-redefs [render/render-call
                          (fn [_]
                            (throw (ex-info "stored result rediscovered a renderer" {})))
                          repl/response
                          (fn [unit]
                            (swap! receipt-calls inc)
                            (receipt-render unit))]
              ;; THE AGENT'S OWN HISTORY IS THE AI PROJECTION. The turn
              ;; concern emits no text of its own (`render-run-ai`), so the
              ;; stored evaluations are read back where the agent reads
              ;; them: its transcript.
              (transcript/render-ai
               (assoc (unit connection)
                      :seon.turn/id "terminal-values"
                      :seon.turn/agent
                      {:seon.agent/id agent-id})))]
        (is (= 4 @receipt-calls))
        ;; ONE FORM PER PROMPT LINE, one response map under it. Printed
        ;; output is its own key rather than bytes spliced ahead of the
        ;; value, which is exactly what made the old grammar unreadable.
        (is (str/includes? rendered "user=> (swap! executions inc)\n#:seon.repl{"))
        (is (str/includes? rendered ":out \"once\\n\""))
        (is (str/includes? rendered
                           "user=> (throw (Exception. \"source error\"))"))
        (is (str/includes? rendered "stored error"))
        (is (str/includes? rendered ":error "))
        (is (str/includes? rendered "user=> (identity \"alpha\\nbeta\")\n#:seon.repl{"))
        (is (str/includes? rendered
                           "user=> (identity {:text \"alpha\\nbeta\"})\n#:seon.repl{"))
        (is (str/includes? rendered ":value {:text \"alpha\\nbeta\"}")
            "the stored node is what the value reads back as")
        (is (not (str/includes? rendered "t=17")))))))

(defn- at
  [offset]
  (java.util.Date. (long (+ 1785500000000 offset))))

(defn- unit
  ;; THE FIXTURE HANDS THE ENVIRONMENT, exactly like production
  ;; (`support/fork-cluster-ctx`): a ctx built around the fixture carries no
  ;; connection and no environment, so every supplied default is inert in it.
  ;; There is no token budget here any more — the transcript renders the
  ;; history its query admitted and `seon.print/fit` at the AI boundary is
  ;; the one place presentation elides.
  ([connection] (unit connection caps))
  ([connection render-caps]
   {:seon.db/db @connection
    :seon.db/connection connection
    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
    :seon.sci.eval/time-limit-ms 1000
    :seon.config/on-core-error :record
    :seon.agent/id agent-id
    :seon.sci.admit/caps render-caps}))

(defn- full-agent-ai
  ;; the complete /ai projection as one string: joined unit outputs
  ;; (walk/prose died with its authored headers — census 2026-08-28)
  [db]
  (->> (walk/neighborhood
        {:seon.db/db db
         :seon.render.walk/lookup [:seon.agent/id agent-id]
         :seon.render/output :seon.render/ai
         :seon.render/distance 2
         :seon.sci.eval/ctx (sci.eval/cluster-ctx db)
         :seon.sci.eval/time-limit-ms 1000
         :seon.config/on-core-error :record
         :seon.sci.admit/caps caps})
       (keep :seon.render/output)
       (clojure.string/join "\n")))

(defn- nodes
  [hiccup]
  (filter vector? (tree-seq sequential? seq hiccup)))

(defn- html-entries
  [rendered]
  (into
   []
   (keep
    (fn [node]
      (let [attributes (when (map? (nth node 1 nil)) (nth node 1))]
        (when (= "seon-transcript-entry" (:class attributes))
          {:id (:data-transcript-id attributes)
           :kind (keyword (:data-transcript-kind attributes))
           :detail (keyword (:data-transcript-detail attributes))
           :dom-id (:id attributes)}))))
   (nodes rendered)))

(defn- html-elided
  [rendered]
  (or
   (some
    (fn [node]
      (let [attributes (when (map? (nth node 1 nil)) (nth node 1))]
        (some-> (:data-transcript-elided attributes) parse-long)))
    (nodes rendered))
   0))

(defn- html-entry-node
  [rendered entry-id]
  (some
   (fn [node]
     (let [attributes (when (map? (nth node 1 nil)) (nth node 1))]
       (when (= entry-id (:data-transcript-id attributes))
         node)))
   (nodes rendered)))

(def ^:private forbidden-session-narration
  ["Form 0 returned"
   "Form 0 failed"
   "It printed:"
   "(comment "
   ";; transcript/entry"
   ";; transcript/elided"
   "is still running"
   "was interrupted"])

(defn- assert-no-session-narration
  [rendered]
  (doseq [forbidden forbidden-session-narration]
    (is (not (str/includes? rendered forbidden))
        (str "session contains invented display grammar: " forbidden))))

(defn- arithmetic-triage-edn
  []
  (try
    (/ 1 0)
    (catch Throwable throwable
      (pr-str (main/ex-triage (Throwable->map throwable))))))

(defn- seed-populated-history!
  [connection]
  (db/transact!
   connection
   [{:seon.ns/name 'my.agents.transcript}
    {:seon.agent/id agent-id
     :seon.agent/namespace [:seon.ns/name 'my.agents.transcript]}
    {:seon.agent/id peer-id}
    {:seon.problems/id "problem-transcript"}
    {:seon.message/id "outside-0" :seon.message/to [:seon.agent/id agent-id] :seon.message/content "Start with the failed deployment." :my.message/reason "An external observation, not this agent's decline." :seon.message/inbox [:seon.agent/id agent-id]}
    {:seon.message/id "peer-1" :seon.message/from [:seon.agent/id peer-id] :seon.message/to [:seon.agent/id agent-id] :seon.message/about [:seon.problems/id "problem-transcript"] :seon.message/content "Repair the owning namespace." :seon.message/inbox [:seon.agent/id agent-id]}
    {:seon.turn/id "run-result" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
    {:seon.cluster.eval/id "eval-result"
     :seon.cluster.eval/run [:seon.turn/id "run-result"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 2000)
     :seon.cluster.eval/comment ";; calculate the answer"
     :seon.cluster.eval/ns [:seon.ns/name 'my.agents.transcript]
     :seon.cluster.eval/output "side effect\n"
     :seon.cluster.eval/read-basis-transaction 41
     ;; THE STORED TEXT IS THE SHOWN TEXT production writes: the evaluation
     ;; keeps what its value renderer showed, never a print node a later
     ;; reader would have to re-render (turn PRD §15; `:seon.eval/shown`).
     :seon.eval/shown "42"
     :seon.cluster.eval/source "(do (println \"side effect\") (+ 20 22))"}
    {:seon.message/id "send-2" :seon.message/from [:seon.agent/id agent-id] :seon.message/to [:seon.agent/id peer-id] :seon.message/content "Check the repaired namespace." :seon.message/inbox [:seon.agent/id peer-id]}
    {:seon.turn/id "run-wait" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
    {:seon.cluster.eval/id "eval-wait"
     :seon.cluster.eval/run [:seon.turn/id "run-wait"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 3500)
     :seon.eval/shown
     "{:my.turn/disposition :wait, :my.turn/note \"waiting for the peer review\"}"
     :seon.cluster.eval/source "(seon.run/wait \"waiting for the peer review\")"}
    {:seon.message/id "decline-3" :seon.message/from [:seon.agent/id agent-id] :seon.message/to [:seon.agent/id peer-id] :seon.message/about [:seon.problems/id "problem-transcript"] :seon.message/content "I cannot make the requested edit." :my.message/reason "The namespace is owned by another agent." :seon.message/inbox [:seon.agent/id peer-id]}
    {:seon.turn/id "run-error" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
    {:seon.cluster.eval/id "eval-error"
     :seon.cluster.eval/run [:seon.turn/id "run-error"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 4500)
     ;; AN ERROR IS TERMINAL: `:value` and `:error` are mutually exclusive
     ;; facts of one evaluation (`src/seon/repl.clj:155`), so a failed
     ;; evaluation stores no shown text beside its error.
     :seon.cluster.eval/error "No such namespace: missing.function"
     :seon.cluster.eval/triage-edn (arithmetic-triage-edn)
     :seon.error/kind :seon.sci.eval/refused
     :seon.problems/id "problem-eval-error"
     :seon.cluster.eval/interrupted-at (at 4501)
     :seon.cluster.eval/source "(missing.function/call)"}
    {:seon.message/id "self-4" :seon.message/from [:seon.agent/id agent-id] :seon.message/to [:seon.agent/id agent-id] :seon.message/content "A self-addressed continuity note." :seon.message/inbox [:seon.agent/id agent-id]}]))

(deftest populated-history-restores-the-repl-fidelity-checklist
  (support/with-database
    (fn [connection]
      (seed-populated-history! connection)
      (let [request (unit connection)
            result-eid (:db/id (db/pull @connection [:db/id]
                                        [:seon.cluster.eval/id "eval-result"]))
            ai (transcript/render-ai request)
            html-value (transcript/render-html request)
            html (hiccup/->string html-value)
            html-rows (html-entries html-value)]
        (testing "messages and eval receipts interleave by their stored time"
          (is (= ["outside-0" "peer-1" "eval-result" "send-2"
                  "eval-wait" "decline-3" "eval-error" "self-4"]
                 (mapv :id html-rows))))
        (testing "recent receipts reproduce prompt, input, output, and result"
          ;; A MESSAGE ENTERS THE AGENT'S CONTEXT AS SOURCE, not as prose:
          ;; `:seon.render/ai` returns the form the agent executes
          ;; (`seon.cluster.message/render-ai`), and the executed form's
          ;; printed result is the sentence. Asserting the sentence HERE was
          ;; asserting the superseded prose shape (AGENTS §2.4 vocabulary).
          (is (str/includes? ai "seon.cluster.message/format-ai")
              "each message is the ordinary read-and-format form")
          (is (str/includes? ai "\"peer-1\"")
              "naming the message it reads, so the agent can ask again")
          (is (str/includes? ai "\"decline-3\""))
          (is (str/includes? ai "\"outside-0\""))
          (is (not (str/includes? ai "t=41"))
              "the receipt's read basis remains metadata, not result text")
          (is (str/includes?
               ai
               (str ";; calculate the answer\n"
                    "my.agents.transcript=> (do (println \"side effect\") "
                    "(+ 20 22))\n"
                    "#:seon.repl{:value 42, :result "
                    (admit/result-handle "eval-result") ", "
                    ":out \"side effect\\n\"}"))
              "comment above, one form on the prompt line, one response map")
          (is (str/includes? ai "waiting for the peer review"))
          (is (str/includes? ai "Execution error (ArithmeticException) at"))
          (is (str/includes? ai "Divide by zero"))
          (assert-no-session-narration ai))
        (testing "every admitted entry renders in full"
          ;; The `:summary` detail existed only for the token ladder's
          ;; degradation step, whose driver had no caller and is deleted:
          ;; presentation elides once, at the AI boundary.
          (is (= (repeat 8 :full) (mapv :detail html-rows))))
        (testing "HTML is the same structure with stable entry ids"
          (is (= (block/surface-id :transcript) (get-in html-value [1 :id])))
          (doseq [{:keys [id kind dom-id]} html-rows]
            (is (= (block/surface-id
                    (keyword (str "seon.transcript." (name kind)) id))
                   dom-id)))
          (is (str/includes? html "waiting for the peer review")))))))

(deftest error-receipt-without-triage-has-an-execution-error-face
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.agent/id agent-id}
        {:seon.turn/id "run-error-without-triage" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
        {:seon.cluster.eval/id "eval-error-without-triage"
         :seon.cluster.eval/run
         [:seon.turn/id "run-error-without-triage"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (at 1000)
         :seon.cluster.eval/error "No such namespace: missing.function"
         :seon.cluster.eval/source "(missing.function/call)"}])
      (let [request (unit connection)
            ai (transcript/render-ai request)
            html-value (transcript/render-html request)
            html-entry (html-entry-node html-value
                                        "eval-error-without-triage")
            html-text (get-in html-entry [2 1 1])]
        ;; THE ERROR IS A KEY, NOT A LOOSE LINE. Clojure's own concise
        ;; execution-error face rides `:error` inside the one response map,
        ;; so it can never be mistaken for a form the agent wrote.
        (testing "the AI projection presents the form and an execution error"
          (is (str/includes? ai "user=> (missing.function/call)"))
          (is (str/includes? ai ":error \"Execution error"))
          (is (str/includes? ai "No such namespace: missing.function"))
          (is (not (str/includes? ai ":value "))
              "exactly one of :value and :error answers a form"))
        (testing "the HTML entry structurally identifies the same error face"
          (is (= "true" (get-in html-entry [1 :data-transcript-error])))
          (is (str/includes? html-text ":error \"Execution error"))
          (is (str/includes? html-text
                             "No such namespace: missing.function")))))))

(deftest the-transcript-is-whole-and-the-ai-boundary-elides-it
  ;; THE RULED SHAPE (owner ruling, 2026-09-07; AGENTS §2.4): presentation
  ;; elides in exactly ONE place, `seon.print/fit` at the AI context
  ;; generation boundary, which every producer's output crosses through
  ;; `seon.render/fit-terminal`. The transcript's own token ladder was a
  ;; second elision mechanism: its driver (`best-summary`) had no caller, and
  ;; the count it reported came from the HISTORY QUERY's limit, never from a
  ;; budget. So the transcript renders what its query admitted, and the cut
  ;; is made once, downstream, naming the bound it was made under.
  (support/with-database
    (fn [connection]
      (seed-populated-history! connection)
      (let [request (unit connection)
            ai (transcript/render-ai request)
            html-value (transcript/render-html request)
            html (hiccup/->string html-value)
            visible (html-entries html-value)]
        (testing "the transcript renders the whole query-bounded history"
          (is (= 8 (count visible)))
          (is (zero? (html-elided html-value))
              "the transcript makes no presentation cut of its own")
          (is (str/includes? html "eval-result")))
        (testing "and the AI context generation boundary cuts it, once"
          (let [tight (assoc (render/agent-render-profile
                              (config/defaults))
                             :seon.render.profile/token-budget 64)
                cut (render/render-ai
                     (assoc request
                            :seon.render/value ai
                            :seon.render/profile tight
                            :seon.render.call/id [::transcript-cut agent-id]))]
            (is (string? cut) (pr-str cut))
            (is (< (count cut) (count ai))
                "the AI projection is bounded by the render profile")
            (is (str/includes? cut "more characters")
                (str "the cut is an elision value naming what it omitted: "
                     (subs cut 0 (min 400 (count cut)))))
            (is (str/includes? cut "requery "))
            (is (str/includes? cut "transcript-cut")
                "and it carries the identity the reader asks again with")))
        (assert-no-session-narration ai)))))

(defn- seed-pinned-bootstrap-history!
  [connection]
  (let [bootstrap-run-id (bootstrap/run-id agent-id)
        bootstrap-count 12
        bootstrap-receipts
        (mapcat
         (fn [ordinal]
           (let [row-id (pr-str [bootstrap-run-id ordinal])]
             [{:seon.cluster.eval/id row-id
               :seon.cluster.eval/run
               [:seon.turn/id bootstrap-run-id]
               :seon.cluster.eval/ordinal ordinal
               :seon.cluster.eval/source (str "(identity " ordinal ")")}
              {:seon.cluster.eval/id row-id
               :seon.cluster.eval/run
               [:seon.turn/id bootstrap-run-id]
               :seon.cluster.eval/ordinal ordinal
               :seon.cluster.eval/at (at 0)
               :seon.eval/shown (str ordinal)}]))
         (range bootstrap-count))
        messages
        (concat
         (map (fn [index]
                {:seon.message/id (str "middle-" index) :seon.message/to [:seon.agent/id agent-id] :seon.message/content (str "middle history " index " " (apply str (repeat 80 "x"))) :seon.message/inbox [:seon.agent/id agent-id]})
              (range 40))
         (map (fn [index]
                {:seon.message/id (str "newest-" index) :seon.message/to [:seon.agent/id agent-id] :seon.message/content (str "newest history " index) :seon.message/inbox [:seon.agent/id agent-id]})
              (range 6)))]
    (db/transact!
     connection
     (into [{:seon.agent/id agent-id}
            {:seon.turn/id bootstrap-run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx" :seon.turn/trigger "bootstrap-message"}
            {:db/id "bootstrap-message" :seon.message/id "task0001" :seon.message/to [:seon.agent/id agent-id] :seon.message/content (bootstrap/task-message) :seon.message/inbox [:seon.agent/id agent-id]}]
           cat
           [bootstrap-receipts messages]))))

(deftest same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order
  (support/with-database
    (fn [connection]
      (seed-pinned-bootstrap-history! connection)
      (let [request (unit connection)
            ai (transcript/render-ai request)
            html-value (transcript/render-html request)
            html (hiccup/->string html-value)
            html-rows (html-entries html-value)
            bootstrap-run-id (bootstrap/run-id agent-id)
            bootstrap-count 12
            pinned-ids
            (mapv #(pr-str [bootstrap-run-id %]) (range bootstrap-count))
            bootstrap-task-id (bootstrap/task-message-id @connection agent-id)
            newest-ids (mapv #(str "newest-" %) (range 6))
            visible-ids (mapv :id html-rows)
            ;; Each pinned entry is located by its own prompt line AND the
            ;; response that answers it, so the ordering property is proven
            ;; over the one grammar rather than over a bare printed value.
            prompted (fn [ordinal]
                       (str "user=> (identity " ordinal
                            ")\n#:seon.repl{:value " ordinal))
            ai-positions
            (mapv #(.indexOf ai (prompted %)) (range bootstrap-count))
            ;; the task message is SOURCE like every other message: its own
            ;; identity is what the transcript names
            task-position
            (.indexOf ai (pr-str bootstrap-task-id))
            pinned-end
            (.indexOf ai (prompted (dec bootstrap-count)))
            ;; the newest message, located by its identity for the same
            ;; reason: the transcript renders the form that reads it
            newest-start (.indexOf ai (pr-str (first newest-ids)))]
        (is (zero? (html-elided html-value))
            "the transcript renders its whole query-bounded history")
        (is (= (into [(first pinned-ids) bootstrap-task-id]
                     (rest pinned-ids))
               (subvec visible-ids 0 (inc bootstrap-count))))
        (is (every? #(<= 0 %) ai-positions))
        (is (apply < ai-positions))
        (is (< (first ai-positions) task-position (second ai-positions)))
        (is (every? #(= :full (:detail %))
                    (take (inc bootstrap-count) html-rows)))
        (is (= newest-ids (subvec visible-ids (- (count visible-ids) 6))))
        (is (< pinned-end newest-start)
            "the pinned opening precedes the newest tail in the AI text")
        (is (< (.indexOf html (last pinned-ids))
               (.indexOf html (first newest-ids)))
            "and in the same order on the page")
        (assert-no-session-narration ai)))))

(deftest supersession-chains-vanish-from-the-history
  (support/with-database
    (fn [connection]
      (let [bootstrap-run-id (bootstrap/run-id agent-id)]
        (db/transact!
         connection
         [{:seon.agent/id agent-id}
          {:seon.turn/id bootstrap-run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx" :seon.turn/trigger "bootstrap-message"}
          {:seon.cluster.eval/id "bootstrap-receipt"
           :seon.cluster.eval/run [:seon.turn/id bootstrap-run-id]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 1)
           :seon.eval/shown ":bootstrap"
           :seon.cluster.eval/source "(identity :bootstrap)"}

          {:seon.turn/id "original" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
          {:seon.cluster.eval/id "original-receipt"
           :seon.cluster.eval/run [:seon.turn/id "original"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 101)
           :seon.eval/shown ":original"
           :seon.cluster.eval/source "(identity :original)"}
          {:seon.cluster.eval/id "original-comment"
           :seon.cluster.eval/run [:seon.turn/id "original"]
           :seon.cluster.eval/ordinal 1
           :seon.cluster.eval/at (at 102)
           :seon.cluster.eval/source "; original comment"}

          {:seon.turn/id "curated" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
          {:seon.cluster.eval/id "curated-receipt"
           :seon.cluster.eval/run [:seon.turn/id "curated"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 201)
           :seon.eval/shown ":curated"
           :seon.cluster.eval/source "(identity :curated)"}

          {:seon.turn/id "proof" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
          {:seon.cluster.eval/id "proof-receipt"
           :seon.cluster.eval/run [:seon.turn/id "proof"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 301)
           :seon.eval/shown ":proof"
           :seon.cluster.eval/source "(identity :proof)"}
          {:seon.cluster.eval/id "proof-comment"
           :seon.cluster.eval/run [:seon.turn/id "proof"]
           :seon.cluster.eval/ordinal 1
           ;; every entry the history orders carries the instant it orders by
           :seon.cluster.eval/at (at 302)
           :seon.cluster.eval/source "; proof comment"}])
        (let [db @connection
              full (transcript/render-html (unit connection))
              visible (mapv :id (html-entries full))]
          (is (zero? (html-elided full))
              "a superseded run is GONE from the history, never elided")
          (is (= "bootstrap-receipt" (first visible)))
          (is (= #{"bootstrap-receipt" "proof-receipt" "proof-comment"}
                 (set visible)))
          (is (db/pull db '[*]
                       [:seon.cluster.eval/id "original-receipt"]))
          (is (db/pull db '[*]
                       [:seon.cluster.eval/id "curated-receipt"])))))))

(deftest malformed-receipt-bytes-and-any-unique-about-stay-replayable
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.agent/id agent-id}
        {:seon.agent/id peer-id}
        {:seon.test/sym "target-fact"}
        {:seon.message/id "about-test" :seon.message/from [:seon.agent/id agent-id] :seon.message/to [:seon.agent/id peer-id] :seon.message/about [:seon.test/sym "target-fact"] :seon.message/content "Inspect the test fact." :seon.message/inbox [:seon.agent/id peer-id]}
        {:seon.turn/id "run-malformed" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
        {:seon.cluster.eval/id "eval-malformed"
         :seon.cluster.eval/run [:seon.turn/id "run-malformed"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (at 1000)
         ;; UNREADABLE BYTES ARE STILL THE BYTES THE AGENT SAW. Shown text
         ;; is an observation, not a serialization: nothing reads it back,
         ;; so a stored fragment reaches the history exactly as stored
         ;; rather than through a second "malformed" face.
         :seon.eval/shown "{"
         :seon.cluster.eval/source "("}])
      (let [ai (transcript/render-ai (unit connection))]
        ;; the message is the form that reads it, naming its own identity
        (is (str/includes? ai "seon.cluster.message/format-ai"))
        (is (str/includes? ai "\"about-test\""))
        (is (str/includes? ai "user=> ("))
        (is (str/includes? ai ":value {"))
        (assert-no-session-narration ai)))))

(deftest about-identity-resolution-pulls-one-deterministic-ordered-id-vector
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.agent/id agent-id}
        {:seon.problems/id "about-first"}
        {:seon.problems/id "about-second"}
        {:seon.message/id "about-message-first" :seon.message/to [:seon.agent/id agent-id] :seon.message/about [:seon.problems/id "about-first"] :seon.message/content "Inspect the first problem." :seon.message/inbox [:seon.agent/id agent-id]}
        {:seon.message/id "about-message-second" :seon.message/to [:seon.agent/id agent-id] :seon.message/about [:seon.problems/id "about-second"] :seon.message/content "Inspect the second problem." :seon.message/inbox [:seon.agent/id agent-id]}])
      (let [database @connection
            basis-before (:max-tx database)
            pull-many db/pull-many
            calls (atom [])]
        (with-redefs [db/pull-many
                      (fn [db-value selector entity-ids]
                        (swap! calls conj [selector entity-ids])
                        (pull-many db-value selector entity-ids))]
          (dotimes [_ 2]
            (let [rendered (transcript/render-ai (unit connection))]
              (is (str/includes? rendered "about-first"))
              (is (str/includes? rendered "about-second"))
              (is (str/includes? rendered ":seon.problems/id")))))
        (let [about-id-vectors
              (into []
                    (keep (fn [[selector entity-ids]]
                            (when (some #{:seon.problems/id} selector)
                              entity-ids)))
                    @calls)]
          (is (= 2 (count about-id-vectors))
              "each render resolves all about refs in one pull-many call")
          (is (every? vector? about-id-vectors)
              "pull-many receives its declared ordered collection")
          (is (apply = about-id-vectors)
              "the same transcript produces the same entity-id order"))
        (is (= basis-before (:max-tx @connection))
            "rendering twice commits no fault or other transaction")))))

;;; `receipt-content-enters-the-shared-capped-floor` asserted that the
;;; transcript re-applied the caller's caps to a STORED result — a second
;;; clipping spot, and the exact shape the one-clipping-spot ruling
;;; retired (owner, 2026-09-08; AGENTS §2.4). The projection is made ONCE,
;;; at evaluation time, and the evaluation stores the resulting shown text
;;; including its elision values; history renders those bytes unchanged.
;;; The surviving class is the test immediately below.

(deftest historical-shown-text-keeps-its-original-elision
  (support/with-database
    (fn [connection]
      (let [shown "(0 1 #:seon.print{:elided 8388608})"]
        (db/transact! connection
                      [{:seon.agent/id agent-id}
                       {:seon.turn/id "run-shown" :seon.turn/agent [:seon.agent/id agent-id]
                        :seon.turn/opened-tx "datomic.tx"}
                       {:seon.cluster.eval/id "eval-shown"
                        :seon.cluster.eval/run [:seon.turn/id "run-shown"]
                        :seon.cluster.eval/ordinal 0 :seon.cluster.eval/at (at 1000)
                        :seon.eval/shown shown :seon.cluster.eval/source "(range)"}])
        (let [ai (transcript/render-ai (unit connection))]
          (is (str/includes? ai shown))
          (assert-no-session-narration ai))))))

(deftest reasoning-is-html-only-and-inline-blob-history-has-one-disclosure
  (support/with-database
    (fn [connection]
      (let [reasoning "First line of thought\nThen the detail."
            digest (apply str (repeat 64 "d"))
            base-attempt
            {:seon.turn/_attempts [:seon.turn/id "run-reasoning"]
             :seon.ai.attempt/at (at 500)
             :seon.ai/endpoint "https://provider.invalid"
             :seon.ai/model "fixture-thinking"
             :seon.ai.attempt/settings-edn "{}"}]
        (db/transact!
         connection
         [{:seon.agent/id agent-id}
          {:seon.turn/id "run-reasoning" :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
          (assoc base-attempt
                 :seon.ai.attempt/id "reasoning-inline"
                 :seon.ai.attempt/ordinal 0)])
        (let [before (full-agent-ai @connection)]
          (db/transact!
           connection
           [[:db/add [:seon.ai.attempt/id "reasoning-inline"]
             :seon.ai.attempt/reasoning reasoning]])
          (let [after (full-agent-ai @connection)
                without-basis #(str/replace % #"basis=\d+" "basis=<same>")]
            (is (= (without-basis before) (without-basis after))
                "the complete agent projection is byte-identical when reasoning appears")
            (is (not (str/includes? after reasoning)))
            (is (not (str/includes? after digest)))))
        (db/transact!
         connection
         [(assoc base-attempt
                 :seon.ai.attempt/id "reasoning-blob"
                 :seon.ai.attempt/ordinal 1
                 :seon.ai.attempt/at (at 600)
                 :seon.ai.attempt/reasoning-blob digest
                 :seon.ai.attempt/reasoning-size (long (count reasoning)))])
        (let [request (assoc (unit connection)
                             :seon.db/connection connection)
              rendered
              (with-redefs [blob/get (fn [actual-connection actual-digest]
                                       (is (identical? connection actual-connection))
                                       (is (= digest actual-digest))
                                       reasoning)]
                (transcript/render-html request))
              disclosures
              (into []
                    (filter (fn [node]
                              (= "seon-attempt-reasoning"
                                 (get-in node [1 :class]))))
                    (nodes rendered))]
          (is (= 2 (count disclosures)))
          (is (= (first disclosures) (second disclosures))
              "inline and blob-backed history use the same disclosure block")
          (doseq [disclosure disclosures]
            (is (not (contains? (second disclosure) :open))
                "the disclosure is collapsed by default")
            (is (= "First line of thought…" (get-in disclosure [2 1 1])))
            (is (= reasoning (get-in disclosure [3 2 1 1])))))))))

(deftest the-history-query-bounds-what-the-transcript-pulls
  ;; THE ONLY BOUND ON THE CANDIDATE SET IS THE QUERY'S OWN
  ;; (`:seon.config.eval.result/max-nodes`), and it is query work, not
  ;; presentation: the caller's token budget never selected entries — the
  ;; ladder that would have honoured it had no driver. The pull stays
  ;; bounded, every admitted entry renders, and the AI boundary decides what
  ;; fits afterwards.
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       (into [{:seon.agent/id agent-id}]
             (map (fn [index]
                    {:seon.message/id (str "bounded-" index) :seon.message/to [:seon.agent/id agent-id] :seon.message/content (str "message " index) :seon.message/inbox [:seon.agent/id agent-id]}))
             (range 100)))
      (let [candidate-limit
            (:seon.config.eval.result/max-nodes caps)
            pulled (atom [])
            pull-many db/pull-many
            request (unit connection)
            html-value
            (with-redefs [db/pull-many
                          (fn [database selector entity-ids]
                            (swap! pulled conj (count entity-ids))
                            (pull-many database selector entity-ids))]
              (transcript/render-html request))]
        (is (seq @pulled) "the transcript pulls its candidates in bulk")
        (is (every? #(<= % candidate-limit) @pulled)
            "and never past the declared query-work bound")
        (is (= 100 (count (html-entries html-value)))
            "every admitted entry renders; nothing is budget-elided")
        (is (zero? (html-elided html-value)))))))

(def ^:private message-event-kinds
  #{:message-in :message-out :message-self :message-about :message-decline})

(def ^:private history-generator
  (gen/vector
   (gen/tuple
    (gen/elements [:message-in :message-out :message-self
                   :message-about :message-decline
                   :receipt-result :receipt-error
                   :receipt-interrupted :receipt-running :receipt-wait
                   :receipt-invalid :receipt-mixed])
    (gen/choose 0 8)
    (gen/fmap #(if (str/blank? %) "x" %) gen/string-alphanumeric))
   0 18))

(defn- generated-event
  [index [event-kind instant-offset content]]
  (let [id (str "event-" index)
        event-at (at (* 1000 instant-offset))
        message? (contains? message-event-kinds event-kind)]
    {:source-index index
     :event-kind event-kind
     :id id
     :kind (if message? :message :eval)
     :at event-at
     :content content}))

(defn- generated-rows
  [{:keys [source-index event-kind id content]
    event-at :at}]
  (if (contains? message-event-kinds event-kind)
    [(cond-> {:seon.message/id id :seon.message/to [:seon.agent/id
               (if (= :message-out event-kind) peer-id agent-id)] :seon.message/content content :seon.message/inbox [:seon.agent/id
               (if (= :message-out event-kind) peer-id agent-id)]}
       (not= :message-in event-kind)
       (assoc :seon.message/from
              [:seon.agent/id agent-id])
       (= :message-self event-kind)
       (assoc :seon.message/to [:seon.agent/id agent-id])
       (contains? #{:message-about :message-decline} event-kind)
       (assoc :seon.message/about [:seon.test/sym "generated-target"])
       (= :message-decline event-kind)
       (assoc :my.message/reason (str "declined: " content)))]
    (let [run-id (str "run-" id)
          source (if (= :receipt-invalid event-kind)
                   "("
                   (str "(identity " source-index ")"))]
      ;; ONE ENTITY PER (run, ordinal): the frozen source rides the
      ;; evaluation it belongs to.
      [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}
       (cond-> {:seon.cluster.eval/id id
                :seon.cluster.eval/run [:seon.turn/id run-id]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/source source
                :seon.cluster.eval/at event-at}
         (= :receipt-result event-kind)
         (assoc :seon.eval/shown (pr-str source-index))
         (= :receipt-error event-kind)
         (assoc :seon.cluster.eval/error content)
         (= :receipt-interrupted event-kind)
         (assoc :seon.cluster.eval/interrupted-at event-at)
         (= :receipt-wait event-kind)
         (assoc :seon.eval/shown
                (pr-str {:my.turn/disposition :wait
                         :my.turn/note content}))
         (= :receipt-invalid event-kind)
         (assoc :seon.eval/shown "{")
         (= :receipt-mixed event-kind)
         (assoc :seon.eval/shown
                (pr-str {:seon.error/kind :generated/refusal})
                :seon.cluster.eval/error content
                :seon.error/kind :generated/refusal
                :seon.problems/id (str "problem-" id)
                :seon.cluster.eval/interrupted-at event-at
                :seon.cluster.eval/output content))])))

(defn- expected-order
  [events]
  (sort-by
   (fn [{:keys [kind id source-index] event-at :at}]
     [(.getTime ^java.util.Date event-at)
      (case kind :message 0 :eval 1)
      (if (= :message kind) source-index id)])
   events))

(deftest ^{:seon.test/long
           "66.666 s pool: 40 fresh-branch generated histories with dual AI/HTML ordering and totality proofs."}
  every-generated-history-is-ordered-and-total
  (let [check
        (tc/quick-check
         40
         (prop/for-all
          [history history-generator]
          (support/with-database
            (fn [connection]
              (let [events (mapv generated-event (range) history)
                    rows (into [{:seon.agent/id agent-id}
                                {:seon.agent/id peer-id}
                                {:seon.test/sym "generated-target"}]
                               (mapcat generated-rows)
                               events)]
                (db/transact! connection rows)
                (let [request (unit connection)
                      ai (transcript/render-ai request)
                      html-value (transcript/render-html request)
                      html (hiccup/->string html-value)
                      html-rows (html-entries html-value)
                      visible-ids (mapv :id html-rows)
                      ordered-ids (mapv :id (expected-order events))
                      elided (html-elided html-value)
                      visible-id-set (set visible-ids)
                      events-by-id (into {} (map (juxt :id identity)) events)
                      visible-declines
                      (filter #(and (= :message-decline (:event-kind %))
                                    (contains? visible-id-set (:id %)))
                              events)]
                  (and
                   (zero? elided)
                   (= (count events) (count html-rows))
                   (= visible-ids ordered-ids)
                   (= (count visible-ids) (count (distinct visible-ids)))
                   ;; A MESSAGE IS SOURCE: the entry the agent reads is the
                   ;; form that reads the message, so the identity is what
                   ;; must be there — the content arrives when the form runs.
                   (every? (fn [{:keys [id]}]
                             (str/includes? ai (pr-str id)))
                           visible-declines)
                   (every?
                    (fn [id]
                      (let [{:keys [kind source-index]}
                            (get events-by-id id)]
                        (if (= :message kind)
                          (str/includes? ai (pr-str id))
                          (str/includes? ai
                                         (if (= :receipt-invalid
                                                (:event-kind
                                                 (get events-by-id id)))
                                           "user=> ("
                                           (str "(identity " source-index ")"))))))
                    visible-ids)
                   ;; Every entry roots the values it renders, so no reader
                   ;; ever meets a render-contract refusal where its value
                   ;; belongs.
                   (not (str/includes?
                         ai (str :seon.render.value/missing-root-identity)))
                   (not (str/includes?
                         html (str :seon.render.value/missing-root-identity)))
                   (not-any? #(str/includes? ai %)
                             forbidden-session-narration)))))))
         :seed property-seed)]
    (support/assert-check!
     check
     "Every transcript must preserve time order and totality.")))

(deftest selected-evaluations-project-only-their-stored-source-and-result
  (support/with-database
   (fn [connection]
     (seed-populated-history! connection)
     (let [database @connection
           evaluation (:db/id (db/pull database [:db/id]
                                      [:seon.cluster.eval/id "eval-result"]))
           selected (assoc (unit connection)
                           :seon.context.contribution/evaluations #{evaluation})
           basis (db/basis-t database)
           rendered (transcript/render-ai selected)]
       (is (integer? evaluation) "the selection must identify a real evaluation")
       (is (str/includes? rendered "(+ 20 22)"))
       (is (str/includes? rendered "42"))
       (is (not (str/includes? rendered "Start with the failed deployment.")))
       (is (not (str/includes? rendered "Repair the owning namespace.")))
       (is (= rendered (transcript/render-ai selected)))
       (let [entries (transcript/history-entries selected)]
         (is (= 1 (count entries)))
         (is (= "(do (println \"side effect\") (+ 20 22))"
                (:seon.render.history/form (first entries)))
             "the form is the form; its comment is a fact beside it")
         (is (= (str "#:seon.repl{:value 42, :result "
                     (admit/result-handle "eval-result") ", "
                     ":out \"side effect\\n\"}")
                (:seon.render.history/printed-value (first entries)))
             "the printed value is the one REPL response, output as its own key")
         (let [facts (db/pull database
                              [:seon.cluster.eval/ordinal
                               :seon.eval/shown
                               :seon.cluster.eval/comment
                               :seon.cluster.eval/output
                               {:seon.cluster.eval/ns [:seon.ns/name]}
                               {:seon.cluster.eval/run [:seon.turn/id]}]
                              evaluation)
               in-memory
               (assoc selected
                      :seon.turn/id
                      (get-in facts [:seon.cluster.eval/run :seon.turn/id])
                      :seon.turn.loop/evaluated-sources
                      [{:seon.cluster.eval/ordinal (:seon.cluster.eval/ordinal facts)
                        :seon.turn.loop/admitted-form
                        {:seon.cluster.eval/source (:seon.render.history/form (first entries))
                         :seon.cluster.eval/ns
                         [:seon.ns/name (get-in facts [:seon.cluster.eval/ns :seon.ns/name])]}
                        :seon.sci.eval/evaluation
                        ;; An in-memory evaluation carries the handle the fork
                        ;; bound, exactly as `evaluate-sources` assoc's it; a
                        ;; stored one derives the same handle from its entity
                        ;; id, so the two projections are the same bytes.
                        (assoc (select-keys facts [:seon.eval/shown
                                                   :seon.cluster.eval/comment
                                                   :seon.cluster.eval/output])
                               :seon.repl/handle
                               (admit/result-handle "eval-result"))}])]
           (is (= (mapv :seon.render.history/bytes entries)
                  (mapv :seon.render.history/bytes
                        (transcript/history-entries in-memory))))
           (is (= [] (transcript/history-entries
                      (assoc in-memory :seon.turn.loop/evaluated-sources [])))))
         (is (= [] (transcript/history-entries
                    (assoc selected :seon.context.contribution/evaluations #{})))))
       (is (= basis (db/basis-t @connection))
           "projection neither evaluates the source nor persists a duplicate")
       (is (= "" (transcript/render-ai
                   (assoc selected :seon.context.contribution/evaluations #{}))))))))

(deftest history-unit-derives-both-projections-from-one-bounded-derivation
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       (into
        [{:seon.agent/id agent-id}]
        (mapcat
         (fn [ordinal]
           (let [run-id (str "history-run-" ordinal)]
             [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx"}
              {:seon.cluster.eval/id (str "history-eval-" ordinal)
               :seon.cluster.eval/run [:seon.turn/id run-id]
               :seon.cluster.eval/ordinal 0
               :seon.cluster.eval/at (java.util.Date. (+ 100 (* 1000 ordinal)))
               :seon.eval/shown (str (inc ordinal))
               :seon.cluster.eval/source (str "(+ " ordinal " 1)")}]))
         (range 3))))
      (let [database @connection
            derived (transcript/agent-history
                     {:seon.db/db database :seon.agent/id agent-id})
            runs (:seon.render.transcript/runs derived)
            ai (transcript/format-history-ai derived)
            rows (db/pull-many
                  database
                  '[:db/id :seon.turn/id :seon.turn/opened-tx
                    :seon.turn/closed-tx :seon.turn/agent]
                  (mapv :db/id
                        (:seon.turn/_agent
                         (db/pull database [:seon.turn/_agent]
                                  [:seon.agent/id agent-id]))))
            html (transcript/render-history-html rows database)]
        (testing "runs come back newest first"
          (is (= ["history-run-2" "history-run-1" "history-run-0"]
                 (mapv :seon.turn/id runs))))
        (testing "the AI projection is the run loop's own bytes"
          (is (str/includes? ai "Run history-run-2, opened "))
          (is (str/includes? ai "=> (+ 2 1)\n#:seon.repl{:value 3")
              "the actual namespace prompt and the stored result")
          (is (< (.indexOf ai "history-run-2") (.indexOf ai "history-run-0"))
              "newest first in the text as well"))
        (testing "the attribute's own AI projection emits no duplicate bytes"
          ;; THE PROMPT IS THIS CONCERN'S AI PROJECTION. `format-history-ai`
          ;; is what the turn loop renders; the declared `:seon.render/ai`
          ;; pair deliberately emits nothing so a walk cannot print the same
          ;; history a second time (`src/seon/render/transcript.clj:1043`).
          (is (= "" (transcript/render-history-ai rows database))))
        (testing "the HTML projection heads the same turns, newest first"
          (is (= [:h2 "Turns (3)"] (nth html 2)))
          (is (= ["Turn history-run-2" "Turn history-run-1" "Turn history-run-0"]
                 (mapv #(second (nth % 2)) (drop 3 html)))
              "one header per derived turn, in the derivation's order")
          (is (= (mapv :seon.turn/id runs)
                 (mapv #(str/replace (second (nth % 2)) "Turn " "")
                       (drop 3 html))))
          ;; A TURN HEADER STATES THE TURN, NOT ITS EVALUATIONS — those
          ;; belong to the prompt pane (`render-run-html`'s docstring). The
          ;; header says how many there were, which is the queryable fact.
          (is (str/includes? (hiccup/->string html) "Evaluations"))
          (is (not (str/includes? (hiccup/->string html) "(+ 2 1)"))))))))

;;; ---------------------------------------------------------------------------
;;; ONE GENERATOR, END TO END
;;; ---------------------------------------------------------------------------

(deftest one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt
  ;; THE PROOF PRD §8 ASKS FOR AND THE AUDIT (B3) SAID COULD NOT BE WRITTEN.
  ;; One reply, evaluated ONCE through the ordinary path, then read three
  ;; ways: the page's in-memory records, the stored history unit, and the
  ;; provider prompt. Before this the prompt came from
  ;; `walk/generic-history-entries`, which paired a `pr-str` of the re-read
  ;; form with a rendered value — a second grammar the page never showed and
  ;; the history unit never produced, so the model read something no other
  ;; surface in the system could reproduce.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "one-grammar")
     (config/apply! {:seon.db/connection connection
                     :seon.boot/cluster-name "one-grammar"})
     (db/transact! connection
                   (agent/creation-tx
                    {:seon.agent/id "one-grammar-agent"
                     :seon.ns/name 'my.agents.one-grammar
                     :seon.cluster/name "one-grammar"}))
     (let [database @connection
           defaults (config/defaults)
           channel (async/chan 1)
           base (support/fork-cluster-ctx connection)
           forked (sci.eval/fork-for-turn
                   {:seon.sci.eval/ctx base
                    :seon.db/db database
                    :seon.db/connection connection
                    :seon.agent/id "one-grammar-agent"})
           cluster (merge defaults
                          {:seon.db/connection connection
                           :seon.cluster/name "one-grammar"
                           :seon.db.process/id "one-grammar-test"
                           :seon.sci.eval/ctx base
                           :seon.cluster.wake/channel channel
                           :seon.render/context-channel channel
                           :seon.turn.loop/completion channel
                           :seon.sci.admit/caps (config/result-caps defaults)
                           :seon.config.eval/time-limit-ms 5000
                           :seon.config/on-core-error :record})
           reply (str ";; a definition worth keeping\n"
                      "(def x 1)\n"
                      "(do (println \"hi\") 41)\n"
                      "(in-ns 'my.agents.probe)\n"
                      "(set! *print-length* 2)\n"
                      "(vec (range 40))\n"
                      "(/ 1 0)")
           sources (turn/planned-sources
                    reply
                    'my.agents.one-grammar
                    (:seon.config.eval.result/max-source
                     (config/result-caps defaults)))
           original-evaluate sci.eval/evaluate
           evaluations (atom 0)]
       (try
         (let [opened-at (java.util.Date.)
               outcomes
               (with-redefs
                 [sci.eval/evaluate (fn [request]
                                      (swap! evaluations inc)
                                      (original-evaluate request))]
                 (turn/evaluate-sources
                  {:seon.turn.loop/cluster cluster
                   :seon.db/db database
                   :seon.sci.eval/ctx (:seon.sci.eval/ctx forked)
                   :seon.agent/id "one-grammar-agent"
                   :seon.cluster.eval/ordinal 0
                   :seon.ns/name 'my.agents.one-grammar
                   :seon.cluster.reply/sources sources}))
               prepared (turn/record-evaluated-tx
                         {:seon.turn.loop/cluster cluster :seon.db/db database :seon.turn/id "one-grammar-stored" :seon.turn/agent [:seon.agent/id "one-grammar-agent"] :seon.turn/starting-ns [:seon.ns/name 'my.agents.one-grammar] :seon.turn/reply reply :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx" :seon.turn.loop/evaluated-sources outcomes})
               committed (blob/with-publication!
                           connection (:seon.blob/staged-writes prepared)
                           #(db/transact! connection
                                          (:seon.db/tx-data prepared)))
               stored-db @connection
               ;; b. THE STORED HISTORY, queried back out of the database.
               stored-unit (assoc (unit connection)
                                  :seon.agent/id "one-grammar-agent")
               stored-entries (transcript/history-entries stored-unit)
               stored-bytes (mapv :seon.render.history/bytes stored-entries)
               ;; a. THE PAGE'S IN-MEMORY RECORDS, the shape the debug page
               ;;    hands the transcript. The handle is the one fact an
               ;;    in-memory record cannot derive from itself: production's
               ;;    `evaluate-sources` resolves the frozen evaluation's
               ;;    entity id after each form and assoc's `:seon.repl/handle`
               ;;    when — and only when — the evaluation settled a value.
               ;;    With the live object retained in the fork, that question
               ;;    is simply "did this evaluation store shown text?"; the
               ;;    node predicate it used to ask died with the node. This
               ;;    fixture persists into a second run id rather than
               ;;    standing up custody, so the handles come from the
               ;;    evaluations that were actually stored. Everything else
               ;;    must be identical BY DERIVATION.
               stored-handles
               (mapv (fn [ordinal]
                       (let [stored (db/pull
                                     stored-db
                                     [:db/id :seon.cluster.eval/id :seon.eval/shown]
                                     [:seon.cluster.eval/id
                                      (turn/receipt-identity
                                       "one-grammar-stored" ordinal)])]
                         (when (and (int? (:db/id stored))
                                    (string? (:seon.eval/shown stored)))
                           (admit/result-handle (:seon.cluster.eval/id stored)))))
                     (range (count outcomes)))
               page-unit (assoc (unit connection)
                                :seon.agent/id "one-grammar-agent"
                                :seon.turn/id "one-grammar-stored"
                                :seon.turn.loop/evaluated-sources
                                (mapv (fn [outcome handle]
                                        (-> outcome
                                            (update :seon.sci.eval/evaluation
                                                    dissoc :seon.repl/handle)
                                            (cond->
                                             handle
                                             (assoc-in
                                              [:seon.sci.eval/evaluation
                                               :seon.repl/handle]
                                              handle))))
                                      outcomes
                                      stored-handles))
               page-bytes (mapv :seon.render.history/bytes
                                (transcript/history-entries page-unit))
               ;; c. THE PROVIDER PROMPT: what the model actually reads.
               prompt-entries
               (walk/history
                {:seon.db/db stored-db
                 :seon.agent/id "one-grammar-agent"
                 :seon.sci.eval/ctx base
                 :seon.render.walk/lookup
                 [:seon.agent/id "one-grammar-agent"]
                 :seon.render/distance 2
                 :seon.sci.admit/caps (config/result-caps defaults)
                 :seon.sci.eval/time-limit-ms 5000
                 :seon.config/on-core-error :record
                 :seon.render/captured-calls (atom {})})
               prompt-text (str/join "\n\n"
                                     (map :seon.render.history/bytes
                                          prompt-entries))
               responses (keep :seon.render.history/printed-value stored-entries)]
           (is (nil? (:seon.error/kind committed))
               (pr-str (select-keys committed [:seon.error/kind
                                               :seon.error/message])))
           (is (= 6 (count outcomes)) "six forms, six evaluations")
           (is (= 6 @evaluations)
               "EXACTLY ONE EVALUATION PER FORM: the page renders the records
                the loop produced, it never re-runs the source to show it")

           (testing "the same bytes on the page, in history, and in the prompt"
             (is (seq stored-bytes))
             (is (= (count page-bytes) (count stored-bytes)))
             ;; PER ENTRY, so a difference names itself instead of dumping
             ;; two vectors a reader has to diff by eye.
             (doseq [[ordinal page stored] (map vector (range)
                                                page-bytes stored-bytes)]
               (is (= page stored)
                   (str "form " ordinal
                        " renders differently in memory and from the store"
                        "\n  page:   " (pr-str page)
                        "\n  stored: " (pr-str stored))))
             (doseq [entry-bytes stored-bytes]
               (is (str/includes? prompt-text entry-bytes)
                   (str "the prompt must carry these bytes verbatim:\n"
                        entry-bytes))))

           (testing "a handle names a value the fork really holds"
             ;; RULING 59c's exclusion was about ADMITTED PRINT NODES: a Var
             ;; or object face kept a NAME, not the value, so a handle
             ;; pointing at it would not resolve. The live-result cut removed
             ;; the node entirely — `bind-result!` interns the ACTUAL object
             ;; under the evaluation's own handle, Vars and namespace objects
             ;; included (`src/seon/sci/eval.clj:519`) — so every settled
             ;; evaluation's handle resolves and every one of them carries it.
             (doseq [index (range (count stored-bytes))
                     :let [entry-bytes (nth stored-bytes index)]
                     :when (str/includes? entry-bytes "#:seon.repl{")]
               (is (str/includes? entry-bytes ":result result/e")
                   (str "a settled evaluation without its handle: "
                        entry-bytes))))

           (testing "the response the agent reads back"
             (let [joined (str/join "\n" stored-bytes)]
               ;; THE PROMPT LINE OPENS THE AGENT'S INPUT, comment included:
               ;; the comment is part of what the agent typed at that prompt,
               ;; so it follows `ns=> ` and the form sits on the next line
               ;; (`seon.repl/input-text`, `src/seon/repl.clj:205`).
               (is (str/includes?
                    joined
                    "my.agents.one-grammar=> ;; a definition worth keeping\n(def x 1)")
                   "the agent's comment, verbatim, on the prompt it introduces")
               (is (str/includes? joined ":out \"hi\\n\"")
                   ":out is its own key, never folded into :value")
               (is (str/includes? joined ":value 41"))
               (is (str/includes? joined ":ns my.agents.probe")
                   "the form that moved the session says where it landed")
               (is (= "my.agents.probe=> "
                      (subs (nth stored-bytes 3) 0 18))
                   "the fourth prompt line is in the namespace in effect")))

           (testing "every response carries the duration settlement measured"
             (is (seq responses))
             (is (every? #(str/includes? % ":ms ") responses)
                 (str "responses without :ms — "
                      (pr-str (remove #(str/includes? % ":ms ") responses)))))

           (testing "an error carries :error and never a value beside it"
             (let [failure (last stored-bytes)]
               (is (str/includes? failure "(/ 1 0)"))
               (is (str/includes? failure ":error"))
               (is (str/includes? failure "Divide by zero"))
               (is (not (str/includes? failure ":value")))))

           (testing "a form's own print options are stored and reach the response"
             ;; The form that CALLS `set!` records what it left in effect,
             ;; and the response prints under it. That is the half this range
             ;; fixed (audit C3): the keys used to be written and then read
             ;; under a different name, so no per-form print option ever
             ;; reached a response.
             (let [setter (db/pull stored-db
                                   [:seon.print/length :seon.print/level]
                                   [:seon.cluster.eval/id
                                    (turn/receipt-identity
                                     "one-grammar-stored" 3)])]
               (is (= 2 (:seon.print/length setter))
                   "the evaluation that set *print-length* stores what it set"))
             ;; AND THE SESSION KEEPS IT. An agent's context IS a REPL
             ;; session, so a `set!` holds until the agent changes it; the
             ;; turn's fork now carries the binding from one form to the
             ;; next (`a-set-of-print-length-does-not-survive-the-next-form`,
             ;; resolved). The FOLLOWING form therefore stores the length it
             ;; inherited — the binding is real, and SCI's own printing
             ;; inside the form runs under it.
             (let [follower (db/pull stored-db
                                     [:seon.print/length]
                                     [:seon.cluster.eval/id
                                      (turn/receipt-identity
                                       "one-grammar-stored" 4)])
                   clipped (nth stored-bytes 4)]
               (is (= 2 (:seon.print/length follower))
                   "the following form inherits the session's print length")
               (is (str/includes? clipped "(vec (range 40))"))
               ;; BUT PRESENTATION IS BOUNDED ONCE, BY THE RENDER PROFILE.
               ;; `*print-length*` as a second elision mechanism is exactly
               ;; what the one-clipping-spot ruling retired: the value
               ;; renderer nulls it before emitting
               ;; (`src/seon/render/value.clj:448`) and the cut it does make
               ;; is an elision value naming its own bound, never `...`.
               (is (not (str/includes? clipped "..."))
                   (str "a second, unnamed elision mechanism: " clipped))
               (is (str/includes? clipped ":seon.render.profile/max-children")
                   (str "the cut must name the bound that made it: "
                        clipped))))

           (testing "ruling 45: nothing emitted is comment-shaped"
             (doseq [line (str/split-lines (str/join "\n" stored-bytes))]
               (when (str/starts-with? (str/trim line) ";")
                 (is (str/includes? line "a definition worth keeping")
                     (str "a comment-shaped line the agent did not write: "
                          line))))))
         (finally (async/close! channel)))))))
