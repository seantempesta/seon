---
title: "Context review round 3: the exact first-call model context for seon.sci.forgive/forgiving-forgiven-text-changes-nothing"
type: research
status: complete
created: 2026-10-12
---

# Context review round 3: seon.sci.forgive/forgiving-forgiven-text-changes-nothing

- Task: `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` (handle `90f6088b30d3`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call)
- Default: git HEAD 321697b08, cluster `default`; method as round 1 (b050829f5), see README.md
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat`, the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message. Message content is verbatim.
- Total estimated tokens (`seon.ai.tokens/estimate`): **4409**

## Message 1: role system (6046 characters, 1889 estimated tokens)

```system
[#:seon.help{:title "Who you are",
  :lines
    ["You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. Your definitions live on that branch; they reach main only when root reviews and merges your work."
     "The program is data: functions, tests, schemas and your task are entities you read and define at this REPL."]}
 #:seon.help{:title "Reading your context",
  :lines
    ["Your current-state block is the last part of every request; it is already there, and (seon.context/current-state) reads it again."
     ":seon.context/task is what must become true; its :seon.task/intent states the conditions. :seon.context/step is :implement, make the proofs green, or :prove, write red tests for the intent; its :seon.context/start holds the form that enters the owner's namespace."
     ":seon.context/proofs are the deftests that show the task done, each with a :seon.context/proof-status: :red fails, :pending has no result for your current code yet, :green passes."
     ":seon.context/must-reads are what your task names: a function's doc, arglists and :malli/schema, a schema's form; their :seon.render/operations read its source, callers and reaching tests. One with a :seon.ref/problem-kind did not resolve; its :seon.ref/message says what to do."
     ":seon.render/operations are forms to evaluate, not data: send one to run it. A namespaced map prints as #:seon.task{:intent ...}, its keys :seon.task/intent and so on."]}
 #:seon.help{:title "Done",
  :lines
    ["Done is every proof green on your branch and every condition of the intent holding. The proofs, then the tests reaching your change, run by themselves after each eval request; all green completes your task and tells root. There is no done call."
     "A test already green proves nothing: the evidence is a red proof turning green."
     "A condition you cannot check at this REPL goes to root. You never run bin/seon, a shell or a file command."]}
 #:seon.help{:title "How work proceeds",
  :lines
    ["1. Read the owner of what the task names: (doc seon.db/q), (my.program/source 'seon.db/q), (my.program/callers 'seon.db/q), (my.program/search :seon.task/intent)."
     "2. Enter the namespace that owns it: (in-ns 'the.owner.ns). Your own :seon.context/namespace is scratch: a definition there changes no owner."
     "3. Define a function in the namespace that owns it, its contract written on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)). It replaces the definition on your branch."
     "4. Call it on real inputs and read the value. A body's locals exist only inside their binding: call the function, or redefine it with the probe inside."
     "5. A :prove step writes a deftest naming its task in :seon/tasks: (deftest ^{:seon/tasks #{'the.task.ns/name}} increments (is (= 3 (inc 2))))."
     "A must-read shows a function's contract as the :malli/schema you write. A defn without one is admitted with a warning in :findings, and merge refuses it."
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
     "A wrong or impossible task goes back with its reason: (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"})."]}
 #:seon.help{:title "Clojure and Malli gotchas",
  :lines
    ["In Malli [:= nil] and [:enum nil] are errors: nil lands in the properties slot; a literal is [:= {} nil]. A contract is [:=> [:cat in1 in2] out], every input inside :cat."
     "A keyword lookup is falsy for absent, nil and false alike; test presence with (contains? {:k false} :k) or (find {:k false} :k)."
     "map and filter are lazy and escape (binding ...) and try; realize them with mapv, into or run! there."
     "Write fully qualified symbols, (seon.db/q ...); an alias works only if (ns-aliases *ns*) shows it."
     "An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never resend it unchanged."]}]
```

## Message 2: role user (8064 characters, 2520 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:sym
      seon.sci.forgive/forgiving-forgiven-text-changes-nothing,
    :evidence
      #{"docs/research/agent-platform/2026-10-10/designed-task-examples.md"},
    :intent
      "`seon.sci.forgive/forgive` is idempotent: forgiving its `:seon.batch/text` again yields no `:seon.batch/repairs` and the same `:seon.batch/spans`. Today a dropped stray closer joins tokens (\"a]a\" -> \"aa\"), a quote before an inserted closer falls outside every span (\"('\"), and a closer inserted after a lone backslash becomes a character literal (\"(\\\\\"). Every change stays recorded as a `:seon.batch/repair` (a replaced closer carries both `:seon.batch/dropped` and `:seon.batch/inserted`). The fix lands in `seon.sci.forgive/scan`, which `seon.sci.forgive/forgive` calls; the consumers `seon.sci.forgive/step` and `seon.sci.forgive/segments` read the `:seon.batch/forgiven` it answers.",
    :must-read
      #{seon.sci.forgive/scan seon.sci.forgive/step
        seon.sci.forgive/forgive seon.sci.forgive/segments}},
 :proofs
   #:seon.context{:counts {:pending 3},
    :proof-list
      [{:seon.test/sym
          seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-text-changes-nothing}}
  a-stray-closer-keeps-tokens-apart
  ;; A dropped closer is replaced by a space; a dangling quote or backslash stays outside the inserted closer.
  (are [text forgiven-text spans] (= [forgiven-text spans]
                                     ((juxt :seon.batch/text :seon.batch/spans) (forgive/forgive text :code)))
    \"a]a\"        \"a a\"         [[0 1 1 1] [2 3 1 3]]
    \"1]2\"        \"1 2\"         [[0 1 1 1] [2 3 1 3]]
    \"(f a]b)\"    \"(f a b)\"     [[0 7 1 1]]
    \"(+ 1 2) [3\" \"(+ 1 2) [3]\" [[0 7 1 1] [8 11 1 9]]
    \"\"           \"\"            []
    \"('\"         \"()'\"         [[0 2 1 1] [2 3 1 3]]
    \"(\\\\\"        \"()\\\\\"        [[0 2 1 1] [2 3 1 3]]))"}
       {:seon.test/sym
          seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-text-changes-nothing}}
  forgiving-forgiven-text-changes-nothing
  (test-support/assert-check!
   (tc/quick-check 300 (prop/for-all [text text-gen] (forgiving-again-changes-nothing? text))
                   :seed 20261010 :max-size 40)))"}
       {:seon.test/sym
          seon.sci.forgive-test/the-repairs-account-for-every-change,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-text-changes-nothing}}
  the-repairs-account-for-every-change
  ;; Model: undoing the recorded repairs gives back the agent's text, so no fix goes unrecorded.
  (test-support/assert-check!
   (tc/quick-check 300 (prop/for-all [text text-gen] (= text (unrepair (forgive/forgive text :code))))
                   :seed 20261010 :max-size 40)))"}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start
      {:seon.render/operations [(in-ns 'seon.sci.forgive)]}},
 :must-reads
   [{:seon.ref/name seon.sci.forgive/forgive,
     :doc
       "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.",
     :arglists ([text mode]),
     :malli/schema
       [:=> [:cat :string :seon.batch/mode] :seon.batch/forgiven],
     :seon.render/operations
       [(my.program/source 'seon.sci.forgive/forgive)
        (my.program/callers
         {:seon.program/subject 'seon.sci.forgive/forgive})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.sci.forgive/forgive})]}
    {:seon.ref/name seon.sci.forgive/scan,
     :doc
       "One pass over `text`: the forgiven text, each top-level form's span `[start end line column]` in it, the repairs, and whether `text`'s own delimiters balance.",
     :arglists ([text reply? repair?]),
     :malli/schema
       [:=>
        [:cat :string :boolean :boolean]
        [:map
         [:seon.batch/text :string]
         [:seon.batch/spans :seon.batch/spans]
         [:seon.batch/repairs :seon.batch/repairs]
         [:seon.sci.forgive/balanced? :boolean]]],
     :seon.render/operations
       [(my.program/source 'seon.sci.forgive/scan)
        (my.program/callers
         {:seon.program/subject 'seon.sci.forgive/scan})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.sci.forgive/scan})]}
    {:seon.ref/name seon.sci.forgive/segments,
     :doc
       "Each form's source in the forgiven text, the text above it as its comment.",
     :arglists ([text] [text mode]),
     :malli/schema
       [:function
        [:=> [:cat :string] :seon.batch/segmented]
        [:=> [:cat :string :seon.batch/mode] :seon.batch/segmented]],
     :seon.render/operations
       [(my.program/source 'seon.sci.forgive/segments)
        (my.program/callers
         {:seon.program/subject 'seon.sci.forgive/segments})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.sci.forgive/segments})]}
    {:seon.ref/name seon.sci.forgive/step,
     :doc "The next read step at the cursor in `mode`.",
     :arglists ([stage mode {v :seon.batch/version}]),
     :malli/schema
       [:=>
        [:cat :seon.batch/read-stage :seon.batch/mode
         :seon.batch/read-state]
        :seon.batch/read-step],
     :seon.render/operations
       [(my.program/source 'seon.sci.forgive/step)
        (my.program/callers
         {:seon.program/subject 'seon.sci.forgive/step})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.sci.forgive/step})]}
    {:seon.ref/path
       "docs/research/agent-platform/2026-10-10/designed-task-examples.md",
     :seon.ref/heading "Designed tasks, worked end to end",
     :seon.render/operations
       [(my.fs/section
         "docs/research/agent-platform/2026-10-10/designed-task-examples.md")]}
    #:seon.ref{:name :seon.batch/text,
     :schema-form
       [:string
        {:description
           "Exact text of a batch: what is read, evaluated, stored and rendered."}]}
    #:seon.ref{:name :seon.batch/repairs,
     :schema-form [:vector :seon.batch/repair]}
    #:seon.ref{:name :seon.batch/spans,
     :schema-form [:vector :seon.batch/span]}
    #:seon.ref{:name :seon.batch/repair,
     :schema-form
       [:map
        {:description
           "One change the forgiving scan made to an unbalanced text: closers (or a string's quote) inserted on a line, or a stray closer dropped from it."}
        [:seon.batch/line pos-int?]
        [:seon.batch/at
         {:description
            "Where in the forgiven text the change was made; the segment whose span starts at or before it owns the repair."}
         nat-int?]
        [:seon.batch/inserted {:optional true} :string]
        [:seon.batch/dropped {:optional true} :string]]}
    #:seon.ref{:name :seon.batch/dropped,
     :problem-kind :unresolved,
     :message
       "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}
    #:seon.ref{:name :seon.batch/inserted,
     :problem-kind :unresolved,
     :message
       "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}
    #:seon.ref{:name :seon.batch/forgiven,
     :schema-form
       [:map
        {:description
           "A text forgiven by seon.sci.forgive/forgive: the text, each top-level form's span in it, and the repairs when its delimiters did not balance."}
        [:seon.batch/text :seon.batch/text]
        [:seon.batch/spans :seon.batch/spans]
        [:seon.batch/repairs {:optional true} :seon.batch/repairs]]}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.sci.forgive}}
```
