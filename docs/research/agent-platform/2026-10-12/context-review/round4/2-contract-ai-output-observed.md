---
title: "Context review round 4: the exact first-call model context for seon.ai/contract-output-observed?"
type: research
status: complete
created: 2026-10-12
---

# Context review round 4: seon.ai/contract-output-observed?

- Task: `seon.ai/contract-output-observed?` (handle `5a453fc84f7a`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call)
- Default: git HEAD 8d14faeee, cluster `default`; method as round 3 (a8d4a784e), see README.md
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat`, the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message. Message content is verbatim.
- Total estimated tokens (`seon.ai.tokens/estimate`): **4163**

## Message 1: role system (6920 characters, 2162 estimated tokens)

```system
[#:seon.help{:title "Who you are",
  :lines
    ["You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. Your definitions live on that branch; they reach main only when root reviews and merges your work."
     "The program is data: functions, tests, schemas and your task are entities you read and define at this REPL."]}
 #:seon.help{:title "Reading your context",
  :lines
    ["Your current-state block is the last part of every request; it is already there, and (seon.context/current-state) reads it again."
     ":seon.context/task is what must become true; its :seon.task/intent states the conditions. :seon.context/step is :implement, make the proofs green, or :prove, write red tests for the intent; its :seon.context/start holds the form that enters the owner's namespace."
     ":seon.context/proofs are the deftests that show the task done, each with its :seon.test/source and a :seon.context/proof-status: :red fails, with its :seon.context/proof-failures, :pending has no result for your current code yet, :green passes. They run after each eval request and the next block shows their status."
     ":seon.context/must-reads are what your task's intent cites in backticks, each resolved once under :seon.ref/name or :seon.ref/path: a function's :seon.fn/source and :malli/schema, a schema's :seon.ref/schema-form, a test's :seon.test/source, a task's :seon.task/intent, a document's heading. Their :seon.ref/references are the names their own content cites, each a summary and its read; :seon.render/operations read the rest. One with a :seon.ref/problem-kind did not resolve; its :seon.ref/message says what to do."
     ":seon.context/turns holds :my.agent/turns-left, the model calls you have left, and :seon.context/namespace, the namespace you are in now."
     ":seon.render/operations are forms to evaluate, not data: send one to run it. A namespaced map prints as #:seon.task{:intent ...}, its keys :seon.task/intent and so on."]}
 #:seon.help{:title "Done",
  :lines
    ["Done is every proof green on your branch and every condition of the intent holding. The proofs, then the tests reaching your change, run by themselves after each eval request; all green completes your task and tells root. There is no done call."
     "A test already green proves nothing: the evidence is a red proof turning green."
     "A condition you cannot check at this REPL goes to root. You never run bin/seon, a shell or a file command."]}
 #:seon.help{:title "How work proceeds",
  :lines
    ["1. Read the owner of what the task names: (doc seon.db/q), (my.program/source 'seon.db/q), (my.program/callers 'seon.db/q), (my.program/search :seon.task/intent)."
     "2. Enter the namespace that owns it: (in-ns 'the.owner.ns). A definition changes the namespace it is evaluated in."
     "3. Define a function in the namespace that owns it, its contract written on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)). It replaces the definition on your branch."
     "4. Call it on real inputs and read the value. A body's locals exist only inside their binding: call the function, or redefine it with the probe inside."
     "5. A :prove step writes a deftest naming its task in :seon/tasks: (deftest ^{:seon/tasks #{'the.task.ns/name}} increments (is (= 3 (inc 2))))."
     "A resolved function reference shows a function's contract as the :malli/schema you write. A defn without one is admitted with a warning in :findings, and merge refuses it."
     "A contract names registered schemas: (my.program/source :seon.task/intent) reads one; (seon.schema/register! :comp.order/qty [:int {:min 1}]) names a new concept."
     "Change code at the REPL, never in files: no slurp, spit, clojure.java.io or shell. Root writes your definitions back to files when it merges. Delete one with (my.program/ns-unmap! 'the.owner.ns/increment)."]}
 #:seon.help{:title "The REPL",
  :lines
    ["Send only Clojure forms, each starting at column zero; prose goes in ;; comments. Code quoted in prose at column zero is evaluated."
     "Never send tool-call markup: no XML, DSML or JSON tool calls. The REPL reads only Clojure."
     "Each reply is data: :value, :error, :findings, and :result, a symbol naming the live value that later forms use in its place. A cut value shows the form that reads the rest."
     ":ms and :alloc-mb are the form's cost; ordinary forms take milliseconds. Each :findings entry names what went wrong and its :seon.repl/fix."
     "Return values to inspect them; never println: printed output is kept and re-read in every later context."
     "Find more: (doc my.message/send), (my.program/source 'my.message/send), (dir my.program)."]}
 #:seon.help{:title "Data",
  :lines
    ["Every value has a named Malli schema: reuse the names on the data flow. Keys are fully namespaced keywords; symbols stay symbols, never strings."
     "Absence is no key: never pass, store or return nil. An optional input is an optional key, [:map [:x {:optional true} :int]]; never [:maybe ...], :any or :some."
     "Fix a bad value in the function that produces it: never weaken a contract or a proof to pass, and add no fallback, retry, catch or guard around it."
     "Never redefine Vars around a call: no with-redefs, alter-var-root, intern or load-file. Code you write holds no atom: state lives in database facts."]}
 #:seon.help{:title "Ask root or give the task back",
  :lines
    ["Ask: my.message/send with :my.message/to \"root\" and :my.message/content, then (my.turn/wait {:my.turn/note \"Waiting for root's answer.\"}) as the reply's last form pauses until the answer arrives."
     "Write what you learn into your task: (my.task/refine! {:seon.task/intent \"Make `seon.db/q` answer its refusal; see docs/seon/specs/merge.md#1-the-merge-request.\"}) rewrites its intent on your branch. A reference is a backticked fully qualified symbol or keyword, a task `ns/task-name`, or a path with a #heading anchor; your next block resolves each one."
     "A wrong or impossible task goes back with its reason: (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"})."]}
 #:seon.help{:title "Clojure and Malli gotchas",
  :lines
    ["In Malli [:= nil] and [:enum nil] are errors: nil lands in the properties slot; a literal is [:= {} nil]. A contract is [:=> [:cat in1 in2] out], every input inside :cat."
     "A keyword lookup is falsy for absent, nil and false alike; test presence with (contains? {:k false} :k) or (find {:k false} :k)."
     "map and filter are lazy and escape (binding ...) and try; realize them with mapv, into or run! there."
     "Write fully qualified symbols, (seon.db/q ...); an alias works only if (ns-aliases *ns*) shows it."
     "An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never resend it unchanged."]}]
```

## Message 2: role user (6404 characters, 2001 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:id "5a453fc84f7a",
    :sym seon.ai/contract-output-observed?,
    :intent
      "Give `seon.ai/output-observed?` a complete Malli contract that checks what its boundary is for, with the cheapest schema that checks it: registered names, every input and output, the error variants it returns. A contract that refuses a real value (a reaching test, a live call, your probe) is a finding, not friction: if the contract misread the value's shape, fix the contract and cite the observed shape; if the producer emits a bad value, keep the contract, send root the finding with `my.message/send`, naming `seon.ai/output-observed?` with the exact form, the refused value's essence and the Malli explain, and say so in your reply. Never widen to :any, [:maybe ...] or :map to go green. Done when `seon.contracts/gaps` answers [] for it and its reaching tests stay green and no slower. Read `docs/seon/architecture/contracts-guide.md`."},
 :proofs
   #:seon.context{:counts {:pending 1},
    :proof-list
      [{:seon.test/sym
          seon.contract-coverage-test/output-observed-is-contracted,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.ai/contract-output-observed?}} output-observed-is-contracted
  (contracted 'seon.ai/output-observed?))",
        :seon.ref/references
          [{:seon.ref/name seon.contract-coverage-test/contracted,
            :doc
              "Assert `function-symbol` has no contract gap in the executing member's world.",
            :seon.render/operations
              [(my.program/source
              'seon.contract-coverage-test/contracted)]}]}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start {:seon.render/operations [(in-ns 'seon.ai)]}},
 :must-reads
   [{:seon.ref/name seon.ai/output-observed?,
     :seon.fn/source
       "(defn- output-observed?
  [evidence]
  (if (or (contains? evidence ::text-received)
          (contains? evidence ::reasoning-received))
    (or (pos? (get evidence ::text-received 0))
        (pos? (get evidence ::reasoning-received 0)))
    true))",
     :seon.render/operations
       [(my.program/callers 'seon.ai/output-observed?)
        (my.program/tests-reaching 'seon.ai/output-observed?)]}
    {:seon.ref/name my.message/send,
     :seon.fn/source
       "(defn send
  \"Offer a message to the recipient's Flow inbox and return its accepted identity.

  Accepted means queued. `:my.message/keep? true` also stores my own copy.
  A resend may carry `:seon.message/id`; the recipient writes that identity once.
  An undeliverable value names recipient, intent and cause. A running recipient
  without a graph is hosted by db<->flow's next pass; resend after it runs.\"
  {:malli/schema [:=> [:cat :my.message/send-request]
                  [:or :seon.message/accepted-result :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id :as request}]
  (message/send! request connection agent-id))",
     :malli/schema
       [:=>
        [:cat :my.message/send-request]
        [:or :seon.message/accepted-result :seon.error/value]],
     :seon.render/operations
       [(my.program/callers 'my.message/send)
        (my.program/tests-reaching 'my.message/send)],
     :seon.ref/references
       [{:seon.ref/name :seon.message/id,
         :seon.render/operations [(my.program/source :seon.message/id)]}
        {:seon.ref/name seon.cluster.message/send!,
         :doc
           "Offer a message without parking; keep a sender copy only under `:my.message/keep?`.",
         :seon.render/operations
           [(my.program/source 'seon.cluster.message/send!)]}]}
    {:seon.ref/name seon.contracts/gaps,
     :seon.fn/source
       "(defn gaps
  \"What keeps `function-symbol` from a complete contract in `database`.

  Its `seon.fn/contract-findings` rows, or one absent row when nothing defines
  it; empty when the contract is complete; a refused read as it came.\"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:or [:vector [:or :seon.fn.contract/finding-row
                                 [:map [:seon.fn/sym :seon.fn/sym] [:seon.contracts/absent [:= true]]]]]
                   :seon.error/value :seon.db/invalid-read-error :seon.schema/validation-refusal]]}
  [database function-symbol]
  (let [defined (db/q '[:find ?e . :in $ ?s :where [?e :seon.fn/sym ?s]] database function-symbol)]
    (cond
      (db/read-refusal? defined) defined
      (nil? defined) [{:seon.fn/sym function-symbol :seon.contracts/absent true}]
      :else (fn/contract-findings database {:seon.fn.contract/selected #{function-symbol}}))))",
     :malli/schema
       [:=>
        [:cat :seon.db/database-value :seon.fn/sym]
        [:or
         [:vector
          [:or
          :seon.fn.contract/finding-row
          [:map [:seon.fn/sym :seon.fn/sym] …1]]]
         :seon.error/value
         :seon.db/invalid-read-error
         :seon.schema/validation-refusal]],
     :seon.render/operations
       [(my.program/callers 'seon.contracts/gaps)
        (my.program/tests-reaching 'seon.contracts/gaps)],
     :seon.ref/references
       [{:seon.ref/name seon.fn/contract-findings,
         :doc
           "Incomplete function contracts ranked by their number of current callers: every function row, private included, or only `:seon.fn.contract/selected`.",
         :seon.render/operations
           [(my.program/source 'seon.fn/contract-findings)]}
        {:seon.ref/name seon.db/q,
         :doc
           "Run a Datalog query over explicit inputs or the current database value.",
         :seon.render/operations [(my.program/source 'seon.db/q)]}
        {:seon.ref/name seon.db/read-refusal?,
         :doc
           "True for this owner's declared read refusals: a `:seon.db/invalid-read-error` (its `:seon.db/invalid-read` member) or a missing-projection refusal (its `:seon.schema/expected-value` member).",
         :seon.render/operations
           [(my.program/source 'seon.db/read-refusal?)]}]}
    {:seon.ref/heading "Function contracts guide",
     :seon.ref/path "docs/seon/architecture/contracts-guide.md",
     :seon.render/operations
       [(my.fs/section "docs/seon/architecture/contracts-guide.md")]}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.ai}}
```
