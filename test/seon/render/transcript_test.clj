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
            [seon.cluster.loop :as loop]
            [seon.cluster.run :as run]
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

(deftest admitted-top-level-string-is-terminal-text
  (let [bounded-result (ns-resolve 'seon.render.transcript 'bounded-result)
        render-unit {:seon.print/options {}}
        string-node {:seon.print/face :seon.print/string
                     :seon.print/value "Agent: juniper\nNamespace: my.agents.juniper"}
        nested-node {:seon.print/face :seon.print/map
                     :seon.print/entries
                     [[{:seon.print/face :seon.print/keyword
                        :seon.print/value :text}
                       string-node]]}
        ;; A TRUNCATED STRING CARRIES WHAT IT CUT AND WHAT CUT IT — the
        ;; declared face requires both, and the gate now runs under the
        ;; contracts that say so.
        truncated-node {:seon.print/face :seon.print/truncated-string
                        :seon.print/value "Agent: juni"
                        :seon.print/length 42
                        :seon.print/bound-by
                        :seon.config.eval.result/max-string}]
    ;; TERMINAL, AND STILL ONE LINE OF DATA. The node is taken as-is — no
    ;; renderer, no floor — but it prints quoted like every other face
    ;; (see the truncated case below), because splicing a string's own
    ;; newlines into `#:seon.repl{:value …}` breaks the one response map
    ;; the agent reads back into lines it cannot tell from its own forms.
    (is (= "\"Agent: juniper\\nNamespace: my.agents.juniper\""
           (bounded-result render-unit {} (pr-str string-node))))
    (is (= "{:text \"Agent: juniper\\nNamespace: my.agents.juniper\"}"
           (bounded-result render-unit {} (pr-str nested-node))))
    (is (= "\"Agent: juni…\""
           (bounded-result render-unit {} (pr-str truncated-node))))))

(deftest selected-run-keeps-status-outside-agent-visible-text
  (let [selected-identities
        (ns-resolve 'seon.render.transcript 'selected-run-identities)
        unit {:seon.db/db ::database
              :seon.cluster.run/id "selected-run"
              :seon.cluster.run/agent
              {:seon.cluster.agent/id "selected-agent"}
              :seon.sci.admit/caps caps}]
    (with-redefs-fn
      {selected-identities
       (constantly
        {:seon.render.transcript/selected-run-id "selected-run"
         :seon.render.transcript/selected-agent-id "selected-agent"
         :seon.render.transcript/selected-run-error nil})
       #'run/render-ai (constantly "Run selected-run · opened at epoch")
       #'transcript/render-ai
       (constantly "my.agents.selected=> (+ 1 2)\n3")
       #'run/render-html
       (constantly [:article {:class "seon-run-status"} "Run selected-run"])
       #'transcript/render-html
       (constantly [:section {:class "seon-transcript"} "(+ 1 2)\n3"])}
      (fn []
        (is (= "my.agents.selected=> (+ 1 2)\n3"
               (transcript/render-run-ai unit)))
        (is (= [:section {:class "seon-run-transcript"}
                [:article {:class "seon-run-status"} "Run selected-run"]
                [:section {:class "seon-transcript"} "(+ 1 2)\n3"]]
               (transcript/render-run-html unit)))))))

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
                 {:db/id 1 :seon.cluster.message/content "hello"}})
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
              :seon.cluster.agent/id "test"
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
       [{:seon.cluster.agent/id agent-id}
        {:seon.cluster.run/id "terminal-values"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (java.util.Date. 0)}
        {:seon.cluster.eval/id "terminal-result"
         :seon.cluster.eval/run [:seon.cluster.run/id "terminal-values"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (java.util.Date. 1)
         :seon.cluster.eval/read-basis-transaction 17
         :seon.cluster.eval/output "once\n"
         :seon.cluster.eval/result-edn "1"
         :seon.cluster.eval/source "(swap! executions inc)"}
        {:seon.cluster.eval/id "terminal-error"
         :seon.cluster.eval/run [:seon.cluster.run/id "terminal-values"]
         :seon.cluster.eval/ordinal 1
         :seon.cluster.eval/at (java.util.Date. 2)
         :seon.cluster.eval/error "stored error"
         :seon.cluster.eval/source "(throw (Exception. \"source error\"))"}
        {:seon.cluster.eval/id "terminal-string"
         :seon.cluster.eval/run [:seon.cluster.run/id "terminal-values"]
         :seon.cluster.eval/ordinal 2
         :seon.cluster.eval/at (java.util.Date. 3)
         :seon.cluster.eval/result-edn
         (pr-str {:seon.print/face :seon.print/string
                  :seon.print/value "alpha\nbeta"})
         :seon.cluster.eval/source "(identity \"alpha\\nbeta\")"}
        {:seon.cluster.eval/id "terminal-nested-string"
         :seon.cluster.eval/run [:seon.cluster.run/id "terminal-values"]
         :seon.cluster.eval/ordinal 3
         :seon.cluster.eval/at (java.util.Date. 4)
         :seon.cluster.eval/result-edn
         (pr-str
          {:seon.print/face :seon.print/map
           :seon.print/entries
           [[{:seon.print/face :seon.print/keyword
              :seon.print/value :text}
             {:seon.print/face :seon.print/string
              :seon.print/value "alpha\nbeta"}]]})
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
              (transcript/render-run-ai
               (assoc (unit connection)
                      :seon.cluster.run/id "terminal-values"
                      :seon.cluster.run/agent
                      {:seon.cluster.agent/id agent-id})))]
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
    :seon.cluster.agent/id agent-id
    :seon.sci.admit/caps render-caps}))

(defn- full-agent-ai
  ;; the complete /ai projection as one string: joined unit outputs
  ;; (walk/prose died with its authored headers — census 2026-08-28)
  [db]
  (->> (walk/neighborhood
        {:seon.db/db db
         :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
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
    {:seon.cluster.agent/id agent-id
     :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.transcript]}
    {:seon.cluster.agent/id peer-id}
    {:seon.problems/id "problem-transcript"}
    {:seon.cluster.message/id "outside-0"
     :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/content "Start with the failed deployment."
     :my.message/reason "An external observation, not this agent's decline."
     :seon.cluster.message/at (at 0)}
    {:seon.cluster.message/id "peer-1"
     :seon.cluster.message/from [:seon.cluster.agent/id peer-id]
     :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/about [:seon.problems/id "problem-transcript"]
     :seon.cluster.message/content "Repair the owning namespace."
     :seon.cluster.message/at (at 1000)}
    {:seon.cluster.run/id "run-result"
     :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
     :seon.cluster.run/opened-at (at 1500)}
    {:seon.cluster.eval/id "eval-result"
     :seon.cluster.eval/run [:seon.cluster.run/id "run-result"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 2000)
     :seon.cluster.eval/comment ";; calculate the answer"
     :seon.cluster.eval/ns [:seon.ns/name 'my.agents.transcript]
     :seon.cluster.eval/output "side effect\n"
     :seon.cluster.eval/read-basis-transaction 41
     ;; THE STORED NODE IS THE ADMITTED PRINT NODE production writes, not a
     ;; bare datum: the handle is derived from the node's face (a face that
     ;; kept only a name never held the value), so a fixture that stores raw
     ;; EDN is asserting a shape the writer never produces.
     :seon.cluster.eval/result-edn
     "#:seon.print{:face :seon.print/number, :value 42}"
     :seon.cluster.eval/source "(do (println \"side effect\") (+ 20 22))"}
    {:seon.cluster.message/id "send-2"
     :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/to [:seon.cluster.agent/id peer-id]
     :seon.cluster.message/content "Check the repaired namespace."
     :seon.cluster.message/at (at 3000)}
    {:seon.cluster.run/id "run-wait"
     :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
     :seon.cluster.run/opened-at (at 3250)}
    {:seon.cluster.eval/id "eval-wait"
     :seon.cluster.eval/run [:seon.cluster.run/id "run-wait"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 3500)
     :seon.cluster.eval/result-edn
     "{:my.run/disposition :wait :my.run/note \"waiting for the peer review\"}"
     :seon.cluster.eval/source "(my.run/wait \"waiting for the peer review\")"}
    {:seon.cluster.message/id "decline-3"
     :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/to [:seon.cluster.agent/id peer-id]
     :seon.cluster.message/about [:seon.problems/id "problem-transcript"]
     :seon.cluster.message/content "I cannot make the requested edit."
     :my.message/reason "The namespace is owned by another agent."
     :seon.cluster.message/at (at 4000)}
    {:seon.cluster.run/id "run-error"
     :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
     :seon.cluster.run/opened-at (at 4250)}
    {:seon.cluster.eval/id "eval-error"
     :seon.cluster.eval/run [:seon.cluster.run/id "run-error"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 4500)
     :seon.cluster.eval/result-edn
     "{:seon.error/kind :seon.sci.eval/refused}"
     :seon.cluster.eval/error "No such namespace: missing.function"
     :seon.cluster.eval/triage-edn (arithmetic-triage-edn)
     :seon.error/kind :seon.sci.eval/refused
     :seon.problems/id "problem-eval-error"
     :seon.cluster.eval/interrupted-at (at 4501)
     :seon.cluster.eval/source "(missing.function/call)"}
    {:seon.cluster.message/id "self-4"
     :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/content "A self-addressed continuity note."
     :seon.cluster.message/at (at 5000)}]))

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
                    (admit/result-handle result-eid) ", "
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
       [{:seon.cluster.agent/id agent-id}
        {:seon.cluster.run/id "run-error-without-triage"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (at 0)}
        {:seon.cluster.eval/id "eval-error-without-triage"
         :seon.cluster.eval/run
         [:seon.cluster.run/id "run-error-without-triage"]
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
               [:seon.cluster.run/id bootstrap-run-id]
               :seon.cluster.eval/ordinal ordinal
               :seon.cluster.eval/source (str "(identity " ordinal ")")}
              {:seon.cluster.eval/id row-id
               :seon.cluster.eval/run
               [:seon.cluster.run/id bootstrap-run-id]
               :seon.cluster.eval/ordinal ordinal
               :seon.cluster.eval/at (at 0)
               :seon.cluster.eval/result-edn (pr-str ordinal)}]))
         (range bootstrap-count))
        messages
        (concat
         (map (fn [index]
                {:seon.cluster.message/id (str "middle-" index)
                 :seon.cluster.message/to
                 [:seon.cluster.agent/id agent-id]
                 :seon.cluster.message/content
                 (str "middle history " index " " (apply str (repeat 80 "x")))
                 :seon.cluster.message/at (at (+ 100 index))})
              (range 40))
         (map (fn [index]
                {:seon.cluster.message/id (str "newest-" index)
                 :seon.cluster.message/to
                 [:seon.cluster.agent/id agent-id]
                 :seon.cluster.message/content (str "newest history " index)
                 :seon.cluster.message/at (at (+ 1000 index))})
              (range 6)))]
    (db/transact!
     connection
     (into [{:seon.cluster.agent/id agent-id}
            {:seon.cluster.run/id bootstrap-run-id
             :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
             :seon.cluster.run/opened-at (at 0)}
            {:seon.cluster.message/id (bootstrap/task-message-id agent-id)
             :seon.cluster.message/to
             [:seon.cluster.agent/id agent-id]
             :seon.cluster.message/content (bootstrap/task-message)
             :seon.cluster.message/at (at 0)}]
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
            bootstrap-task-id (bootstrap/task-message-id agent-id)
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
         [{:seon.cluster.agent/id agent-id}
          {:seon.cluster.run/id bootstrap-run-id
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 0)}
          {:seon.cluster.eval/id "bootstrap-receipt"
           :seon.cluster.eval/run [:seon.cluster.run/id bootstrap-run-id]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 1)
           :seon.cluster.eval/result-edn ":bootstrap"
           :seon.cluster.eval/source "(identity :bootstrap)"}

          {:seon.cluster.run/id "original"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 100)}
          {:seon.cluster.eval/id "original-receipt"
           :seon.cluster.eval/run [:seon.cluster.run/id "original"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 101)
           :seon.cluster.eval/result-edn ":original"
           :seon.cluster.eval/source "(identity :original)"}
          {:seon.cluster.eval/id "original-comment"
           :seon.cluster.eval/run [:seon.cluster.run/id "original"]
           :seon.cluster.eval/ordinal 1
           :seon.cluster.eval/at (at 102)
           :seon.cluster.eval/source "; original comment"}

          {:seon.cluster.run/id "curated"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 200)
           :seon.cluster.run/supersedes
           [[:seon.cluster.run/id "original"]]}
          {:seon.cluster.eval/id "curated-receipt"
           :seon.cluster.eval/run [:seon.cluster.run/id "curated"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 201)
           :seon.cluster.eval/result-edn ":curated"
           :seon.cluster.eval/source "(identity :curated)"}

          {:seon.cluster.run/id "proof"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 300)
           :seon.cluster.run/supersedes
           [[:seon.cluster.run/id "curated"]]}
          {:seon.cluster.eval/id "proof-receipt"
           :seon.cluster.eval/run [:seon.cluster.run/id "proof"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 301)
           :seon.cluster.eval/result-edn ":proof"
           :seon.cluster.eval/source "(identity :proof)"}
          {:seon.cluster.eval/id "proof-comment"
           :seon.cluster.eval/run [:seon.cluster.run/id "proof"]
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
       [{:seon.cluster.agent/id agent-id}
        {:seon.cluster.agent/id peer-id}
        {:seon.test/sym "target-fact"}
        {:seon.cluster.message/id "about-test"
         :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/to [:seon.cluster.agent/id peer-id]
         :seon.cluster.message/about [:seon.test/sym "target-fact"]
         :seon.cluster.message/content "Inspect the test fact."
         :seon.cluster.message/at (at 0)}
        {:seon.cluster.run/id "run-malformed"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (at 500)}
        {:seon.cluster.eval/id "eval-malformed"
         :seon.cluster.eval/run [:seon.cluster.run/id "run-malformed"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (at 1000)
         :seon.cluster.eval/result-edn "{"
         :seon.cluster.eval/source "("}])
      (let [ai (transcript/render-ai (unit connection))]
        ;; the message is the form that reads it, naming its own identity
        (is (str/includes? ai "seon.cluster.message/format-ai"))
        (is (str/includes? ai "\"about-test\""))
        (is (str/includes? ai "user=> ("))
        (is (str/includes? ai ":seon.cluster.eval/result-edn \"{\""))
        (assert-no-session-narration ai)))))

(deftest about-identity-resolution-pulls-one-deterministic-ordered-id-vector
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.agent/id agent-id}
        {:seon.problems/id "about-first"}
        {:seon.problems/id "about-second"}
        {:seon.cluster.message/id "about-message-first"
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/about [:seon.problems/id "about-first"]
         :seon.cluster.message/content "Inspect the first problem."
         :seon.cluster.message/at (at 0)}
        {:seon.cluster.message/id "about-message-second"
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/about [:seon.problems/id "about-second"]
         :seon.cluster.message/content "Inspect the second problem."
         :seon.cluster.message/at (at 1000)}])
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

(deftest receipt-content-enters-the-shared-capped-floor
  (support/with-database
    (fn [connection]
      (let [result (into {}
                         (map (fn [index]
                                [(keyword "audit" (str "field-" index))
                                 (str "long-value-" index)]))
                         (range 40))
            narrow-caps (assoc caps
                               :seon.config.eval.result/max-collection 3
                               :seon.config.eval.result/max-string 8)]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          {:seon.cluster.run/id "run-capped"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 0)}
          {:seon.cluster.eval/id "eval-capped"
           :seon.cluster.eval/run [:seon.cluster.run/id "run-capped"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 1000)
           :seon.cluster.eval/result-edn (pr-str result)
           :seon.cluster.eval/source "(identity result)"}])
        (let [ai (transcript/render-ai
                  (unit connection narrow-caps))]
          ;; THE CUT IS THE ONE ELISION VALUE, and it says what it omitted,
          ;; the bound that made it, and the identity to ask again with —
          ;; the word "elided" was the retired transcript marker's prose.
          (is (str/includes? ai "…"))
          (is (str/includes? ai "more children of 40"))
          (is (str/includes? ai
                             (str "bounded by "
                                  :seon.render.profile/max-children)))
          (is (str/includes? ai "requery "))
          (is (str/includes? ai "eval-capped"))
          (is (not (str/includes? ai ":audit/field-39")))
          (assert-no-session-narration ai))))))

(deftest a-missing-value-says-why-and-names-no-result-handle
  ;; THE CLASS: an evaluation that stored nothing must never render as an
  ;; evaluation that produced nothing. It states the reason and the bytes it
  ;; reached, and it emits NO `:result` — the handle is ablated, so a later
  ;; form naming it gets an ordinary unresolved symbol rather than a lie.
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.agent/id agent-id}
        {:seon.cluster.run/id "run-missing"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (at 0)}
        {:seon.cluster.eval/id "eval-missing"
         :seon.cluster.eval/run [:seon.cluster.run/id "run-missing"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (at 1000)
         :seon.eval/missing :over-bound
         :seon.eval/size 8388608
         :seon.cluster.eval/source "(range)"}])
      (let [ai (transcript/render-ai (unit connection))]
        (is (str/includes? ai ":value #:seon.eval{:missing :over-bound"))
        (is (str/includes? ai ":size 8388608"))
        (is (not (str/includes? ai ":result result/")))
        (assert-no-session-narration ai)))))

(deftest reasoning-is-html-only-and-inline-blob-history-has-one-disclosure
  (support/with-database
    (fn [connection]
      (let [reasoning "First line of thought\nThen the detail."
            digest (apply str (repeat 64 "d"))
            base-attempt
            {:seon.ai.attempt/run [:seon.cluster.run/id "run-reasoning"]
             :seon.ai.attempt/at (at 500)
             :seon.ai/endpoint "https://provider.invalid"
             :seon.ai/model "fixture-thinking"
             :seon.ai.attempt/settings-edn "{}"}]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          {:seon.cluster.run/id "run-reasoning"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 0)}
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
       (into [{:seon.cluster.agent/id agent-id}]
             (map (fn [index]
                    {:seon.cluster.message/id (str "bounded-" index)
                     :seon.cluster.message/to
                     [:seon.cluster.agent/id agent-id]
                     :seon.cluster.message/content (str "message " index)
                     :seon.cluster.message/at (at index)}))
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
    [(cond-> {:seon.cluster.message/id id
              :seon.cluster.message/ordinal source-index
              :seon.cluster.message/to
              [:seon.cluster.agent/id
               (if (= :message-out event-kind) peer-id agent-id)]
              :seon.cluster.message/content content
              :seon.cluster.message/at event-at}
       (not= :message-in event-kind)
       (assoc :seon.cluster.message/from
              [:seon.cluster.agent/id agent-id])
       (= :message-self event-kind)
       (assoc :seon.cluster.message/to [:seon.cluster.agent/id agent-id])
       (contains? #{:message-about :message-decline} event-kind)
       (assoc :seon.cluster.message/about [:seon.test/sym "generated-target"])
       (= :message-decline event-kind)
       (assoc :my.message/reason (str "declined: " content)))]
    (let [run-id (str "run-" id)
          source (if (= :receipt-invalid event-kind)
                   "("
                   (str "(identity " source-index ")"))]
      ;; ONE ENTITY PER (run, ordinal): the frozen source rides the
      ;; evaluation it belongs to.
      [{:seon.cluster.run/id run-id
        :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
        :seon.cluster.run/opened-at event-at}
       (cond-> {:seon.cluster.eval/id id
                :seon.cluster.eval/run [:seon.cluster.run/id run-id]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/source source
                :seon.cluster.eval/at event-at}
         (= :receipt-result event-kind)
         (assoc :seon.cluster.eval/result-edn (pr-str source-index))
         (= :receipt-error event-kind)
         (assoc :seon.cluster.eval/error content)
         (= :receipt-interrupted event-kind)
         (assoc :seon.cluster.eval/interrupted-at event-at)
         (= :receipt-wait event-kind)
         (assoc :seon.cluster.eval/result-edn
                (pr-str {:my.run/disposition :wait
                         :my.run/note content}))
         (= :receipt-invalid event-kind)
         (assoc :seon.cluster.eval/result-edn "{")
         (= :receipt-mixed event-kind)
         (assoc :seon.cluster.eval/result-edn
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
                    rows (into [{:seon.cluster.agent/id agent-id}
                                {:seon.cluster.agent/id peer-id}
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
                     (admit/result-handle evaluation) ", "
                     ":out \"side effect\\n\"}")
                (:seon.render.history/printed-value (first entries)))
             "the printed value is the one REPL response, output as its own key")
         (let [facts (db/pull database
                              [:seon.cluster.eval/ordinal
                               :seon.cluster.eval/result-edn
                               :seon.cluster.eval/comment
                               :seon.cluster.eval/output
                               {:seon.cluster.eval/ns [:seon.ns/name]}
                               {:seon.cluster.eval/run [:seon.cluster.run/id]}]
                              evaluation)
               in-memory
               (assoc selected
                      :seon.cluster.run/id
                      (get-in facts [:seon.cluster.eval/run :seon.cluster.run/id])
                      :seon.cluster.loop/evaluated-sources
                      [{:seon.cluster.eval/ordinal (:seon.cluster.eval/ordinal facts)
                        :seon.cluster.loop/admitted-form
                        {:seon.cluster.eval/source (:seon.render.history/form (first entries))
                         :seon.cluster.eval/ns
                         [:seon.ns/name (get-in facts [:seon.cluster.eval/ns :seon.ns/name])]}
                        :seon.sci.eval/evaluation
                        ;; An in-memory evaluation carries the handle the fork
                        ;; bound, exactly as `evaluate-sources` assoc's it; a
                        ;; stored one derives the same handle from its entity
                        ;; id, so the two projections are the same bytes.
                        (assoc (select-keys facts [:seon.cluster.eval/result-edn
                                                   :seon.cluster.eval/comment
                                                   :seon.cluster.eval/output])
                               :seon.repl/handle
                               (admit/result-handle evaluation))}])]
           (is (= (mapv :seon.render.history/bytes entries)
                  (mapv :seon.render.history/bytes
                        (transcript/history-entries in-memory))))
           (is (= [] (transcript/history-entries
                      (assoc in-memory :seon.cluster.loop/evaluated-sources [])))))
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
        [{:seon.cluster.agent/id agent-id}]
        (mapcat
         (fn [ordinal]
           (let [run-id (str "history-run-" ordinal)]
             [{:seon.cluster.run/id run-id
               :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
               :seon.cluster.run/opened-at (java.util.Date. (* 1000 ordinal))
               :seon.cluster.run/closed-at
               (java.util.Date. (+ 500 (* 1000 ordinal)))}
              {:seon.cluster.eval/id (str "history-eval-" ordinal)
               :seon.cluster.eval/run [:seon.cluster.run/id run-id]
               :seon.cluster.eval/ordinal 0
               :seon.cluster.eval/at (java.util.Date. (+ 100 (* 1000 ordinal)))
               :seon.cluster.eval/result-edn (str (inc ordinal))
               :seon.cluster.eval/source (str "(+ " ordinal " 1)")}]))
         (range 3))))
      (let [database @connection
            derived (transcript/agent-history
                     {:seon.db/db database :seon.cluster.agent/id agent-id})
            runs (:seon.render.transcript/runs derived)
            ai (transcript/format-history-ai derived)
            rows (db/pull-many
                  database
                  '[:db/id :seon.cluster.run/id :seon.cluster.run/opened-at
                    :seon.cluster.run/closed-at :seon.cluster.run/agent]
                  (mapv :db/id
                        (:seon.cluster.run/_agent
                         (db/pull database [:seon.cluster.run/_agent]
                                  [:seon.cluster.agent/id agent-id]))))
            html (transcript/render-history-html rows database)]
        (testing "runs come back newest first"
          (is (= ["history-run-2" "history-run-1" "history-run-0"]
                 (mapv :seon.cluster.run/id runs))))
        (testing "the AI projection is the run loop's own bytes"
          (is (str/includes? ai "Run history-run-2, opened "))
          (is (str/includes? ai "=> (+ 2 1)\n#:seon.repl{:value 3")
              "the actual namespace prompt and the stored result")
          (is (< (.indexOf ai "history-run-2") (.indexOf ai "history-run-0"))
              "newest first in the text as well"))
        (testing "the AI projection renders the saved history without another read form"
          (is (= ai (transcript/render-history-ai rows database))))
        (testing "the HTML projection states the same runs, labeled historical"
          (is (= [:h2 "History (3 runs)"] (nth html 2)))
          (is (= "Historical run — its results are stored, not fresh"
                 (last (nth (nth html 3) 2))))
          (is (str/includes? (hiccup/->string html) "(+ 2 1)"))
          (is (= (mapv :seon.cluster.run/id runs)
                 (into [] (comp (drop 3) (map #(last (last (nth % 3)))))
                       html))
              "one entry per derived run, in the derivation's order"))))))

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
                    {:seon.cluster.agent/id "one-grammar-agent"
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
                    :seon.cluster.agent/id "one-grammar-agent"})
           cluster (merge defaults
                          {:seon.db/connection connection
                           :seon.cluster/name "one-grammar"
                           :seon.db.process/id "one-grammar-test"
                           :seon.sci.eval/ctx base
                           :seon.cluster.wake/channel channel
                           :seon.render/context-channel channel
                           :seon.cluster.loop/completion channel
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
           sources (loop/planned-sources
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
                 (loop/evaluate-sources
                  {:seon.cluster.loop/cluster cluster
                   :seon.db/db database
                   :seon.sci.eval/ctx (:seon.sci.eval/ctx forked)
                   :seon.cluster.agent/id "one-grammar-agent"
                   :seon.cluster.eval/ordinal 0
                   :seon.ns/name 'my.agents.one-grammar
                   :seon.cluster.reply/sources sources}))
               prepared (run/record-evaluated-tx
                         {:seon.cluster.loop/cluster cluster
                          :seon.db/db database
                          :seon.cluster.run/id "one-grammar-stored"
                          :seon.cluster.run/agent
                          [:seon.cluster.agent/id "one-grammar-agent"]
                          :seon.cluster.run/starting-ns
                          [:seon.ns/name 'my.agents.one-grammar]
                          :seon.cluster.run/reply reply
                          :seon.cluster.run/opened-at opened-at
                          :seon.cluster.run/closed-at (java.util.Date.)
                          :seon.cluster.loop/evaluated-sources outcomes})
               committed (blob/with-publication!
                           connection (:seon.blob/staged-writes prepared)
                           #(db/transact! connection
                                          (:seon.db/tx-data prepared)))
               stored-db @connection
               ;; b. THE STORED HISTORY, queried back out of the database.
               stored-unit (assoc (unit connection)
                                  :seon.cluster.agent/id "one-grammar-agent")
               stored-entries (transcript/history-entries stored-unit)
               stored-bytes (mapv :seon.render.history/bytes stored-entries)
               ;; a. THE PAGE'S IN-MEMORY RECORDS, the shape the debug page
               ;;    hands the transcript. The handle is the one fact an
               ;;    in-memory record cannot derive from itself: production's
               ;;    `evaluate-sources` resolves the frozen evaluation's
               ;;    entity id after each form and assoc's `:seon.repl/handle`
               ;;    when — and only when — the node actually held the value
               ;;    (ruling 59c). This fixture persists into a second run id
               ;;    rather than standing up custody, so the handles come from
               ;;    the evaluations that were actually stored, derived
               ;;    through the SAME predicate the loop uses. Everything else
               ;;    must be identical BY DERIVATION.
               stored-handles
               (mapv (fn [ordinal]
                       (let [stored (db/pull
                                     stored-db
                                     [:db/id :seon.cluster.eval/result-edn
                                      :seon.cluster.eval/result-blob]
                                     [:seon.cluster.eval/id
                                      (run/receipt-identity
                                       "one-grammar-stored" ordinal)])]
                         (when (and (int? (:db/id stored))
                                    (admit/restorable-node
                                     (:seon.cluster.eval/result-edn stored)))
                           (admit/result-handle (:db/id stored)))))
                     (range (count outcomes)))
               page-unit (assoc (unit connection)
                                :seon.cluster.agent/id "one-grammar-agent"
                                :seon.cluster.run/id "one-grammar-stored"
                                :seon.cluster.loop/evaluated-sources
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
                 :seon.cluster.agent/id "one-grammar-agent"
                 :seon.sci.eval/ctx base
                 :seon.render.walk/lookup
                 [:seon.cluster.agent/id "one-grammar-agent"]
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

           (testing "a handle names only a value the node actually held"
             ;; RULING 59c, and the reason page and store agree. `(def x 1)`
             ;; admits to a Var face and `(in-ns …)` to an object face — names,
             ;; not values — so neither carries `:result`, in memory or from
             ;; the store. A handle a later turn cannot resolve is worse than
             ;; no handle.
             (is (not (str/includes? (nth stored-bytes 0) ":result"))
                 (str "a Var face named a handle: " (nth stored-bytes 0)))
             (is (not (str/includes? (nth stored-bytes 2) ":result"))
                 (str "an object face named a handle: " (nth stored-bytes 2)))
             (is (str/includes? (nth stored-bytes 1) ":result result/e")
                 (str "an ordinary value keeps its handle: "
                      (nth stored-bytes 1))))

           (testing "the response the agent reads back"
             (let [joined (str/join "\n" stored-bytes)]
               (is (str/includes? joined "my.agents.one-grammar=> (def x 1)"))
               (is (str/includes? joined ";; a definition worth keeping")
                   "the agent's comment, verbatim, above the prompt it introduces")
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
                                    (run/receipt-identity
                                     "one-grammar-stored" 3)])]
               (is (= 2 (:seon.print/length setter))
                   "the evaluation that set *print-length* stores what it set"))
             ;; AND THE SESSION KEEPS IT. An agent's context IS a REPL
             ;; session, so a `set!` holds until the agent changes it; the
             ;; turn's fork now carries the binding from one form to the
             ;; next (`a-set-of-print-length-does-not-survive-the-next-form`,
             ;; resolved). The FOLLOWING form therefore stores the length it
             ;; inherited and prints under it.
             (let [follower (db/pull stored-db
                                     [:seon.print/length]
                                     [:seon.cluster.eval/id
                                      (run/receipt-identity
                                       "one-grammar-stored" 4)])
                   clipped (nth stored-bytes 4)]
               (is (= 2 (:seon.print/length follower))
                   "the following form inherits the session's print length")
               (is (str/includes? clipped "(vec (range 40))"))
               (is (str/includes? clipped ":value [0 1 ...]")
                   (str "the value is printed under the agent's own choice: "
                        clipped))))

           (testing "ruling 45: nothing emitted is comment-shaped"
             (doseq [line (str/split-lines (str/join "\n" stored-bytes))]
               (when (str/starts-with? (str/trim line) ";")
                 (is (str/includes? line "a definition worth keeping")
                     (str "a comment-shaped line the agent did not write: "
                          line))))))
         (finally (async/close! channel)))))))
