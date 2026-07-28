(ns projection-review
  "Generate the 2026-07-28 consumer review of Seon's error projections."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as-alias flow]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.run :as run]
            [seon.cluster.work :as work]
            [seon.error :as error]
            [seon.instrument :as instrument]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.util Date]))

(def ^:private output-path
  "docs/prds/sci-execution-runtime/research/projection-review-2026-07-28.md")

(def ^:private at #inst "2026-07-28T12:00:00.000-00:00")
(def ^:private process "4242-1700000000000")
(def ^:private live-processes #{process})

(def ^:private caps
  {:seon.config.eval.result/max-depth 6
   :seon.config.eval.result/max-collection 8
   :seon.config.eval.result/max-string 96
   :seon.config.eval.result/max-nodes 512})

(def ^:private base-model-request
  {:seon.ai/endpoint "http://127.0.0.1:1/chat/completions"
   :seon.ai/model "projection-probe"
   :seon.ai/api-key-variable "SEON_PROJECTION_REVIEW_KEY_ABSENT"
   :seon.ai/prompt "Return one word."
   :seon.ai/timeout-ms 500})

(defn- pp-str [value]
  (with-out-str (pprint/pprint value)))

(defn- fenced [language value]
  (str "```" language "\n" value
       (when-not (str/ends-with? value "\n") "\n")
       "```\n"))

(defn- with-db [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      (d/transact connection
                  [{:seon.cluster.agent/id "root"}
                   {:seon.cluster.agent/id "agent-a"}
                   {:seon.cluster.message/id "projection-trigger"
                    :seon.cluster.message/to
                    [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "Exercise the error path."
                    :seon.cluster.message/at at}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- durable-fact [db error-id]
  (let [pulled
        (d/pull db
                '[* {:seon.error/run [:seon.cluster.run/id]
                     :seon.error/agent [:seon.cluster.agent/id]}]
                [:seon.error/id error-id])]
    (cond-> (dissoc pulled :db/id)
      (:seon.error/run pulled)
      (assoc :seon.error/run
             [:seon.cluster.run/id
              (:seon.cluster.run/id (:seon.error/run pulled))])
      (:seon.error/agent pulled)
      (assoc :seon.error/agent
             [:seon.cluster.agent/id
              (:seon.cluster.agent/id (:seon.error/agent pulled))]))))

(defn- found [connection]
  (problems/problems @connection
                     {:seon.cluster.run/live-processes live-processes}))

(defn- commit-error!
  [connection id source extra]
  (let [request
        (merge {:seon.error/source source
                :seon.error/id id
                :seon.error/at at
                :seon.error/process process
                :seon.error/basis-t (:max-tx @connection)
                :seon.sci.admit/caps caps
                :seon.config.error/recurrence-limit 3
                :seon.config.error/escalate-to "root"}
               extra)]
    (d/transact connection (error/commit-tx @connection request))
    (durable-fact @connection id)))

(defn- rendered-error
  [connection fact reason]
  (let [notice (error/notice
                (cond-> {:seon.error/fact fact}
                  reason (assoc :seon.error/reason reason)))
        problem-value (found connection)]
    {:ai (:seon.render/output
          (render/render {:seon.render/unit notice
                          :seon.render/kind :seon.render/ai}))
     :log (:seon.render/output
           (render/render {:seon.render/unit notice
                           :seon.render/kind :seon.render/log}))
     :raw fact
     :problems problem-value
     :problems-log (:seon.render/output
                    (render/render {:seon.render/unit problem-value
                                    :seon.render/kind :seon.render/log}))}))

(defn- output-blocks [{:keys [ai log raw problems problems-log]}]
  (str "Actual AI projection:\n\n"
       (fenced "text" (if (nil? ai) "nil" ai))
       "\nActual log line:\n\n"
       (fenced "text" (if (nil? log) "nil" log))
       "\nActual raw durable fact (EDN; payload already bounded by "
       "`seon.sci.admit`):\n\n"
       (fenced "clojure" (pp-str raw))
       "\nActual `seon.problems` value:\n\n"
       (fenced "clojure" (pp-str problems))
       "\nActual `seon.problems` log projection:\n\n"
       (fenced "text" problems-log)))

(defn- cluster-handle [connection]
  {:seon.store/branch-connection connection
   :seon.cluster.run/process process
   :seon.cluster.wake/channel (async/chan (async/sliding-buffer 1))
   :seon.ai/primary
   {:seon.ai/endpoint "http://127.0.0.1:1/v1"
    :seon.ai/model "primary-probe"
    :seon.ai/api-key-variable "SEON_PROJECTION_PRIMARY_KEY"
    :seon.ai/timeout-ms 200}
   :seon.ai.retry/strategy
   {:seon.ai.retry/base-delay-ms 1
    :seon.ai.retry/multiplier 2.0
    :seon.ai.retry/jitter-fraction 0.0
    :seon.ai.retry/maximum-delay-ms 1
    :seon.ai.retry/maximum-retries 0
    :seon.ai.retry/maximum-total-delay-ms 0}
   :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
   :seon.config.eval/time-limit-ms 1000
   :seon.config/on-core-error :panic
   :seon.config.error/recurrence-limit 3
   :seon.sci.admit/caps caps})

(defn- next-work-request []
  {:seon.cluster.run/process process
   :seon.cluster.work/now at})

(defn- drive!
  [cluster-handle limit]
  (let [connection (:seon.store/branch-connection cluster-handle)]
    (loop [passes 0
           reports []]
      (let [request (next-work-request)
            next (work/next-work @connection request)]
        (if (or (nil? next) (>= passes limit))
          reports
          (recur (inc passes)
                 (conj reports
                       (cluster.loop/turn
                        {:seon.cluster.loop/cluster cluster-handle
                         :seon.cluster.work/next next}
                        (:seon.cluster.work/now request)))))))))

(defn- eval-case []
  (with-db
    (fn [connection]
      (with-redefs [ai/complete
                    (fn [_] {:seon.ai/text "(no-such-fn 1)"})]
        (drive! (cluster-handle connection) 5))
      (let [receipt (-> (d/q '[:find [(pull ?receipt [*]) ...]
                               :where
                               [?receipt :seon.cluster.eval/status :error]]
                             @connection)
                        first
                        (dissoc :db/id))
            problem-value (found connection)]
        {:ai (:seon.render/output
              (render/render {:seon.render/unit problem-value
                              :seon.render/kind :seon.render/ai}))
         :log nil
         :raw receipt
         :problems problem-value
         :problems-log (:seon.render/output
                        (render/render {:seon.render/unit problem-value
                                        :seon.render/kind
                                        :seon.render/log}))}))))

(defn- transition-case []
  (with-db
    (fn [connection]
      (d/transact connection
                  (into
                   (run/open-tx
                    {:seon.cluster.run/id "run-stale"
                     :seon.cluster.run/agent
                     [:seon.cluster.agent/id "agent-a"]
                     :seon.cluster.run/opened-at at})
                   (run/claim-tx
                    {:seon.cluster.run/id "run-stale"
                     :seon.cluster.run/process process
                     :seon.cluster.run/lease-until
                     #inst "2026-07-28T12:01:00.000-00:00"
                     :seon.cluster.run/now at})))
      (let [refusal
            (seon.cluster.store/transact!
             connection
             (run/close-tx
              {:seon.cluster.run/id "run-stale"
               :seon.cluster.run/process process
               :seon.cluster.run/claim-epoch 0
               :seon.cluster.run/closed-at at
               :seon.cluster.run/now at}))
            fact (commit-error! connection "err-transition-stale"
                                refusal
                                {:seon.cluster.run/id "run-stale"
                                 :seon.cluster.agent/id "agent-a"})]
        (rendered-error connection fact nil)))))

(defn- flow-fault-source [failure]
  {::flow/pid :seon.cluster.loop/loop
   ::flow/status :running
   ::flow/state {:seon.cluster.loop/turns 7
                 :seon.store/branch-connection
                 (Object.)}
   ::flow/count 8
   ::flow/cid :seon.cluster.wake/wake
   ::flow/msg :projection-review
   ::flow/op :step
   ::flow/step :seon.cluster.loop/step
   ::flow/ex failure})

(defn- fault-case []
  (with-db
    (fn [connection]
      (let [fact
            (commit-error!
             connection
             "err-core-fault"
             (flow-fault-source
              (ex-info "injected core fault"
                       {:seon.error/kind :projection-review/core-fault}))
             {})]
        (rendered-error connection fact :no-attributable-agent)))))

(defn- model-value-case [id value]
  (with-db
    (fn [connection]
      (let [fact (commit-error! connection id value {})]
        (rendered-error connection fact nil)))))

(defn- model-cases []
  (let [no-credential (ai/complete base-model-request)
        connect-refused
        (with-redefs [ai/credential (constantly "local-test-key")]
          (ai/complete base-model-request))
        unparseable
        (ai/completion-text
         {"choices" [{"message" {"role" "assistant"}}]
          "request_id" "projection-review"})]
    [{:label "No credential"
      :value no-credential
      :output (model-value-case "err-model-no-credential" no-credential)}
     {:label "Connect refused at localhost"
      :value connect-refused
      :output (model-value-case "err-model-connect-refused" connect-refused)}
     {:label "Unparseable decoded body"
      :value unparseable
      :output (model-value-case "err-model-unparseable" unparseable)}]))

(defn- failover-case []
  (with-db
    (fn [connection]
      (let [requests (atom [])
            primary-failure
            {:seon.error/kind :seon.ai/transport-failure
             :seon.error/message "Connection refused"
             :seon.error/data
             {:seon.ai/error-class :transport-before-send
              :seon.ai/request-transmitted? false
              :seon.ai/response-started? false
              :seon.ai/output-observed? false}}
            completer
            (fn [request]
              (let [ordinal (count @requests)]
                (swap! requests conj request)
                (if (zero? ordinal)
                  primary-failure
                  {:seon.ai/text "(my.run/complete \"backup answered\")"})))
            handle
            (assoc (cluster-handle connection)
                   :seon.ai/backup
                   {:seon.ai/endpoint "http://127.0.0.1:2/v1"
                    :seon.ai/model "backup-probe"
                    :seon.ai/api-key-variable "SEON_PROJECTION_BACKUP_KEY"
                    :seon.ai/timeout-ms 200})]
        (with-redefs [ai/complete completer]
          (drive! handle 2))
        (let [fact (-> (found connection)
                       :seon.problems/error-signatures first
                       :seon.error/fact)
              backup-request (second @requests)
              output (rendered-error connection fact :failover)]
          (assoc output
                 :ai (:seon.ai/system backup-request)
                 :backup-request
                 (select-keys backup-request
                              [:seon.ai/model :seon.ai/system
                               :seon.ai/prompt])))))))

(defn- instrumentation-case []
  (with-db
    (fn [connection]
      (try
        (instrument/apply! {:seon.config/on-core-error :panic
                            :seon.sci.admit/caps caps})
        (let [failure (try
                        (error/value "not a fact")
                        (catch Throwable thrown thrown))
              fact (commit-error! connection "err-instrumentation"
                                  (flow-fault-source failure)
                                  {})]
          (rendered-error connection fact :no-attributable-agent))
        (finally
          (instrument/remove!))))))

(defn- storm-case []
  (with-db
    (fn [connection]
      (let [source
            (flow-fault-source
             (ex-info "recurring injected core fault"
                      {:seon.error/kind :projection-review/recurring}))
            facts
            (mapv
             (fn [occurrence]
               (commit-error! connection
                              (str "err-storm-" occurrence)
                              source
                              {:seon.error/at
                               (Date. (+ (inst-ms at) occurrence))}))
             (range 1 7))
            messages
            (->> (d/q '[:find [(pull ?message
                                     [:seon.cluster.message/id
                                      :seon.cluster.message/content]) ...]
                         :where
                         [?message :seon.cluster.message/about _]
                         [?message :seon.cluster.message/to ?agent]
                         [?agent :seon.cluster.agent/id "root"]]
                       @connection)
                 (sort-by :seon.cluster.message/id)
                 vec)
            latest (last facts)]
        (assoc (rendered-error connection latest :recurring)
               :root-messages messages
               :final-notification-log
               (:seon.render/output
                (render/render
                 {:seon.render/unit
                  (error/notice
                   {:seon.error/fact (nth facts 2)
                    :seon.error/reason :recurring
                    :seon.error/occurrence 3
                    :seon.error/notification-limit 3
                    :seon.error/notification :final})
                  :seon.render/kind :seon.render/log})))))))

(defn- model-family-section [cases]
  (str
   "## 4. Model-call failures\n\n"
   "All three values came from `seon.ai` without an external or paid call. "
   "The localhost refusal used the JDK HTTP client against port 1; the "
   "unparseable case used the pure decoded-document owner that `complete` "
   "calls after JSON decoding.\n\n"
   (apply str
          (for [{:keys [label value output]} cases]
            (str "### " label "\n\n"
                 "Leaf value returned by `seon.ai`:\n\n"
                 (fenced "clojure" (pp-str value))
                 "\n"
                 (output-blocks output)
                 "\n")))
   "### Critique\n\n"
   "A model needs the actionable distinction encoded in "
   "`:seon.ai/error-class`—missing credential cannot improve with time, a "
   "refused connection is free to fail over, and an unparseable 2xx body is "
   "terminal because output was observed. The AI prose hides all of that in "
   "`data-edn`; without pulling and parsing the fact, the reader sees three "
   "generic “stopped work” notices. An operator gets the kind and id but not "
   "the disposition-driving fields on the grep line.\n\n"
   "Before: `An error stopped work: Connection refused "
   "(:seon.ai/transport-failure). Work already under way may or may not have "
   "completed; nothing was retried and nothing re-executed.`\n\n"
   "After: `The primary model was not called: the localhost connection was "
   "refused before send. This attempt cost nothing; a configured backup may "
   "run immediately.`\n\n"
   "Before log: `kind=:seon.ai/transport-failure ... "
   "message=\"Connection refused\"`\n\n"
   "After log: `kind=:seon.ai/transport-failure phase=transport-before-send "
   "transmitted=false output=false disposition=failover-now ...`\n\n"))

(defn- report []
  (let [eval-output (eval-case)
        transition-output (transition-case)
        fault-output (fault-case)
        model-output (model-cases)
        failover-output (failover-case)
        instrumentation-output (instrumentation-case)
        storm-output (storm-case)]
    (str
     "---\n"
     "type: research\n"
     "status: active\n"
     "tags: [prd, research]\n"
     "---\n\n"
     "# Error projection consumer review\n\n"
     "Generated by `research/scripts/projection_review.clj` on 2026-07-28. "
     "Every database is a fresh in-memory Datahike cluster with the canonical "
     "boot-derived attributes installed. No external network or paid model "
     "call occurred. Text under “Actual” is returned by the maintained "
     "projection functions; it is not rewritten for this document.\n\n"
     "## Dependency ledger and method\n\n"
     "- Datahike: the pinned checkout under `reference-code/datahike/`; "
     "`schema.datahike/malli->datahike-schema`, `d/transact`, `d/q`, and "
     "`d/pull` follow the fixture in `test/seon/problems_test.clj`.\n"
     "- SCI: `reference-code/sci` at the repository pin; the eval case enters "
     "`seon.sci.eval/evaluate` through `seon.cluster.loop/turn`, which writes "
     "the running and terminal receipts.\n"
     "- core.async Flow: the escaped-Throwable fixture uses Flow's real "
     "`::flow/ex`, `::flow/pid`, `::flow/op`, and `::flow/cid` report shape "
     "before `seon.error/commit-tx`.\n"
     "- Model leaf: `seon.ai/complete` produces missing-credential and "
     "localhost-refusal values; `seon.ai/completion-text` produces the "
     "decoded-body failure without a server.\n"
     "- Projections: error notices and problems values are projected through "
     "`seon.render/render`; consumers never name an implementation function.\n\n"
     "The raw fact is bounded at its producer: error payloads pass through "
     "`seon.sci.admit`, while receipts store the evaluator's already-admitted "
     "strings. `seon.problems` is always re-derived from the final immutable "
     "database value.\n\n"

     "## 1. Agent eval error — real evaluator and terminal receipt\n\n"
     "The model reply was `(no-such-fn 1)`. The run loop froze it as the plan, "
     "called the real evaluator, and committed the terminal error receipt.\n\n"
     (output-blocks eval-output)
     "\n### Critique\n\n"
     "There is no AI projection at all: the receipt does not declare "
     "`:seon.render/ai`, and the next prompt only derives interrupted or "
     "pre-plan failures. A model therefore cannot see the exact bad form, "
     "ordinal, or evaluator guidance unless another context block later "
     "surfaces `seon.problems`. The operator line is useful but omits the form "
     "source and error kind because neither is stored on the receipt. Worse, "
     "the raw `result-edn` contains `#object[...]` identities for SCI's "
     "callstack volatile and namespace. Those addresses are unstable, not "
     "actionable evidence, and make the allegedly ordinary EDN unreadable by "
     "a normal EDN reader.\n\n"
     "Before: `nil` (no AI render).\n\n"
     "After: `Form 0 failed during evaluation: Unable to resolve symbol: "
     "no-such-fn. The run did not retry it. Inspect the receipt and revise the "
     "remaining plan from current facts.`\n\n"
     "Before log: `seon.problems errored-receipt receipt=... run=... "
     "ordinal=0 error=\"...\"`\n\n"
     "After log: `seon.problems errored-receipt receipt=... run=... ordinal=0 "
     "source=\"(no-such-fn 1)\" kind=:seon.sci.eval/evaluation-failed "
     "error=\"...\"`\n\n"

     "## 2. Transition refusal — stale epoch close\n\n"
     "A real run was opened and claimed at epoch 1. "
     "`seon.cluster.store/transact!` then attempted `run/close-tx` at epoch 0, "
     "returning the transition's flat refusal; that value was committed "
     "through the same error transaction used by the run loop.\n\n"
     (output-blocks transition-output)
     "\n### Critique\n\n"
     "The most prominent sentence is garbage: a structurally recognized flat "
     "refusal is called `An unclassified clojure.lang.PersistentArrayMap`. "
     "The fact's kind is only the umbrella `:seon.cluster.run/refused`; the "
     "actionable rule `:seon.cluster.run/stale-epoch` survives only inside the "
     "printed `data-edn`. The prose also treats a safe atomic refusal as work "
     "that “may or may not have completed,” which is false for this "
     "transaction: Datahike aborted the close. The refusal carries requested "
     "epoch 0, but it does not carry actual epoch 1, so that comparison cannot "
     "be fixed in a projection alone.\n\n"
     "Before: `An error stopped work: An unclassified "
     "clojure.lang.PersistentArrayMap arrived where an error was expected. "
     "(:seon.cluster.run/refused). Work already under way may or may not have "
     "completed.`\n\n"
     "After: `The close of run-stale was refused atomically by "
     ":seon.cluster.run/stale-epoch for requested claim epoch 0. Nothing from "
     "this close committed. Re-read the run before deciding whether a new "
     "transition is eligible.`\n\n"
     "Before log: `kind=:seon.cluster.run/refused "
     "message=\"An unclassified clojure.lang.PersistentArrayMap ...\"`\n\n"
     "After log: `kind=:seon.cluster.run/refused "
     "rule=:seon.cluster.run/stale-epoch transition=seon.cluster.run/close-call "
     "run=run-stale requested-epoch=0 committed=false ...`\n\n"

     "## 3. Escaped Throwable — fault fact and root message\n\n"
     "An `ExceptionInfo` was placed in Flow's transform-error report shape. "
     "`error/commit-tx` committed the normalized fact and the exact "
     "explanation message addressed to root.\n\n"
     (output-blocks fault-output)
     "\n### Critique\n\n"
     "The AI projection correctly tells root why it was contacted, but spends "
     "most of its budget on generic non-retry clauses and evidence coordinates. "
     "The useful first questions are which proc/op failed, whether an agent/run "
     "was affected, and what root should inspect next. The log is much better "
     "for an operator, though its full signature and process dominate a line "
     "whose primary grep dimensions are kind, proc, op, and run.\n\n"
     "Before: `An error stopped work in :seon.cluster.loop/loop: injected core "
     "fault ... Evidence: error err-core-fault ...`\n\n"
     "After: `The run-loop :step failed with "
     ":projection-review/core-fault. No agent or run could be attributed. "
     "Inspect error err-core-fault; the proc survived and no work was "
     "re-executed.`\n\n"
     "Before log: `seon.error id=... at=... kind=... class=... proc=... "
     "op=... cid=... basis-t=... process=... signature=... message=...`\n\n"
     "After log: `seon.error kind=... proc=... op=... cid=... run=- id=... "
     "message=... process=... basis-t=... sig=...`\n\n"

     (model-family-section model-output)

     "## 5. Failover — primary error fact and backup system segment\n\n"
     "The run loop made one primary attempt returning a before-send transport "
     "failure, committed its error fact and attempt row, then made exactly one "
     "backup request. The AI text below is the backup request's actual "
     "`:seon.ai/system` value.\n\n"
     (output-blocks failover-output)
     "\nActual bounded backup request fields:\n\n"
     (fenced "clojure" (pp-str (:backup-request failover-output)))
     "\n### Critique\n\n"
     "This is the clearest consumer failure. The backup model needs three "
     "facts: the primary did not run, failover is safe because nothing was "
     "transmitted, and it should answer the unchanged user request. Instead it "
     "receives operator clauses (`Evidence: error …, basis-t …`), generic "
     "uncertainty that contradicts the before-send evidence, and duplicate "
     "non-retry instructions. The backup cannot pull the error id or use the "
     "basis transaction.\n\n"
     "Before: the full system segment above.\n\n"
     "After: `The primary model was not called: its connection failed before "
     "send, so no output exists and this failover is safe. You are the one "
     "backup attempt. Answer the unchanged user request below; do not wait for "
     "or reconstruct a primary response.`\n\n"
     "The operator log should remain a separate projection; no log rewrite is "
     "needed specifically for the backup context.\n\n"

     "## 6. Instrumentation violation — panic mode, wrong-shaped call\n\n"
     "`instrument/apply!` enabled `:panic`, then `error/value` was called with "
     "a string instead of `:seon.error/fact`. Malli's reporter threw Seon's "
     "flat `:seon.instrument/contract-violated` value, which was normalized as "
     "an escaped Flow fault.\n\n"
     (output-blocks instrumentation-output)
     "\n### Critique\n\n"
     "The AI message contains Malli's deeply nested humanization but delays the "
     "single most useful fact—`seon.error/value` received a string. More "
     "seriously, the durable fact does not contain the instrumentation "
     "reporter's `fn`, schema, or bounded args at all: normalization admits "
     "the Flow map, sees its Throwable only as an opaque marker, and retains "
     "only the Throwable's message/ex-data-derived kind. Therefore neither "
     "AI nor log projection can recover the expected shape or offending value "
     "from the fact. Root needs the function, input/output arm, expected shape, "
     "and bounded offending value as first-class normalized evidence.\n\n"
     "Before: `seon.error/value violated its contract (invalid-input): ...`\n\n"
     "After: `Contract violation in seon.error/value input: expected "
     ":seon.error/fact, received \"not a fact\". The call was stopped before "
     "the function ran.`\n\n"
     "Before log: `kind=:seon.instrument/contract-violated ... message=...`\n\n"
     "After log: `kind=:seon.instrument/contract-violated fn=seon.error/value "
     "arm=input expected=:seon.error/fact args=\"[\\\"not a fact\\\"]\" ...`\n\n"

     "## 7. Storm bound — what root actually receives\n\n"
     "The same Flow fault source was committed six times with recurrence "
     "limit 3. Every occurrence remained a fact. These are the actual root "
     "messages after the sixth commit:\n\n"
     (fenced "clojure" (pp-str (:root-messages storm-output)))
     "\nThe latest fact and aggregate projections are:\n\n"
     (output-blocks storm-output)
     "\n### Critique\n\n"
     "Root receives four messages for limit 3: ordinary escalation messages "
     "for occurrences 1 and 2; at occurrence 3, both "
     "`:no-attributable-agent` and `:recurring`; then silence. The prose never "
     "states `occurrence=3` or `limit=3`, so the recurring message says “often "
     "enough” without the only numbers that make it actionable. The duplicate "
     "third-occurrence messages read like two incidents unless root compares "
     "their error ids. `seon.problems` does supply the final count, but only in "
     "the log suffix.\n\n"
     "Before: two distinct messages at the limit, one generic escalation and "
     "one generic recurrence notice.\n\n"
     "After: one message at the limit: `Core fault "
     ":projection-review/recurring reached 3 occurrences in process "
     "4242-1700000000000 (notification limit 3). Further occurrences remain "
     "in seon.problems but will not message you. Latest error: err-storm-3.`\n\n"
     "Before log: `... occurrences=6` only in the aggregate report.\n\n"
     "After log: keep the aggregate suffix, and add `occurrence=3 limit=3 "
     "notification=final` to the final emitted message's companion log line.\n\n"

     "## Findings that a projection change cannot repair\n\n"
     "- The eval receipt's `result-edn` contains unstable SCI `#object` "
     "identities, and the receipt does not retain a directly projectable error "
     "kind or form source.\n"
     "- The normalized instrumentation fact loses the reporter's `fn`, schema, "
     "and args because the Flow Throwable becomes opaque in `data-edn`.\n"
     "- The transition refusal does not carry the actual epoch, only the "
     "requested epoch and rule.\n"
     "- At the storm limit, `commit-tx` emits both ordinary and recurring "
     "messages. Changing that cardinality is routing, not rendering.\n\n"
     "These need producer/normalizer/routing decisions. A projection must not "
     "invent the missing evidence or silently alter delivery.\n\n"
     "## Proposed hot-reloadable revision list\n\n"
     "Each item below is exactly one existing projection defn change and can "
     "be approved independently. None changes durable facts or routing.\n\n"
     "1. **`seon.error/ai-prose`: reason-specific lead sentence.** For "
     "`:failover`, omit id/class/basis-t, omit generic uncertainty, and state "
     "the before-send/no-output evidence already present in the admitted flat "
     "value; tell the backup only how to proceed.\n"
     "2. **`seon.error/ai-prose`: refusal branch.** For "
     "`:seon.cluster.run/refused`, parse the already bounded `data-edn` once "
     "and surface rule, transition, run, and requested epoch; state that the "
     "transaction committed nothing and omit the generic uncertainty clause.\n"
     "3. **`seon.error/ai-prose`: core-fault ordering.** Lead with "
     "proc/op/kind and attribution, collapse the two non-retry sentences into "
     "one, and put the error id last. Keep class and basis transaction out of "
     "agent prose.\n"
     "4. **`seon.error/ai-prose`: recurring wording.** Say this is the final "
     "notification for the signature and that later occurrences remain in "
     "`seon.problems`; do not claim an unavailable numeric occurrence.\n"
     "5. **`seon.error/log-line`: grep-first ordering.** Emit kind, proc, op, "
     "cid, and run before id/time/process/signature; abbreviate only the field "
     "name `signature` to `sig`, never the value.\n"
     "6. **`seon.error/log-line`: promoted flat-value evidence.** When "
     "`data-edn` is a flat model error, append error-class, transmitted, "
     "response-started, and output-observed fields. This makes the disposition "
     "evidence grep-able without changing the fact.\n"
     "7. **`seon.problems/log-report`: aggregate-first error line.** Place "
     "`occurrences=N` immediately after kind/signature rather than at the far "
     "right of the composed line, so recurrence is visible before coordinates "
     "and message text.\n\n"
     "The first approval should be item 1: it removes known backup-model noise "
     "without changing durable facts, routing, log output, or delivery counts. "
     "Item 2 is the next highest-value truthfulness fix.")))

(defn- after-projection
  [label {:keys [ai log problems-log]}]
  (str "### " label "\n\n"
       (fenced "text" ai)
       "\n"
       (fenced "text" (or log problems-log))
       "\n"))

(defn- after-report []
  (let [eval-output (eval-case)
        transition-output (transition-case)
        fault-output (fault-case)
        model-output (model-cases)
        failover-output (failover-case)
        instrumentation-output (instrumentation-case)
        storm-output (storm-case)]
    (str
     "## After revisions (2026-07-28, approved)\n\n"
     (after-projection "Agent eval error" eval-output)
     (after-projection "Transition refusal" transition-output)
     (after-projection "Escaped Throwable" fault-output)
     (apply str
            (for [{:keys [label output]} model-output]
              (after-projection (str "Model call — " label) output)))
     (after-projection "Failover" failover-output)
     (after-projection "Instrumentation violation" instrumentation-output)
     "### Storm bound\n\n"
     (fenced "clojure" (pp-str (:root-messages storm-output)))
     "\n"
     (fenced "text" (:final-notification-log storm-output))
     "\n"
     (fenced "text" (:problems-log storm-output)))))

(let [existing (slurp output-path)
      marker "\n## After revisions (2026-07-28, approved)"
      marker-at (str/index-of existing marker)
      baseline (if marker-at (subs existing 0 marker-at) existing)]
  (spit output-path
        (str (str/trimr baseline) "\n\n" (str/trimr (after-report)) "\n")))
(println output-path)
