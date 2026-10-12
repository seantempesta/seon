---
title: "Context review 1: the exact first-call model context for seon.sci.forgive/forgiving-forgiven-text-changes-nothing"
type: research
status: complete
created: 2026-10-12
---

# Context review 1: seon.sci.forgive/forgiving-forgiven-text-changes-nothing

- Task: `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` (handle `90f6088b30d3`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call; `no-provider` untouched)
- Default: git HEAD 214c61330, cluster `default`, basis-t 536871283 (commit-id `6acce7dd-6423-5f97-8e18-dd61d5d403d5`)
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat` (config default), the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message.
- Total estimated tokens (`seon.ai.tokens/estimate`, chars/token calibration): **2659**

## Message 1: role system (2290 characters, 715 estimated tokens)

```system
["You are an agent at a REPL on your own branch; only what root merges reaches the cluster."
 "Your task and proofs are in your block: (seon.context/current-state). Send forms; prose goes in ;; comments."
 "Your block names your :seon.context/namespace. Define a function in the namespace that owns it: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)); every function has a contract."
 "Change code at the REPL, never in files (no slurp, spit or io): (in-ns 'the.owner.ns) then (defn ...) replaces the function there; root writes files back when it merges."
 "A body's locals exist only inside their binding: call the function on inputs, or redefine it with the probe inside."
 "A test proves a task by naming it in :seon/tasks: (deftest ^{:seon/tasks #{'the.task.ns/name}} increments (is (= 3 (inc 2))))."
 "Register a schema with (seon.schema/register! :comp.order/qty [:int {:min 1}])."
 "Your task's proofs and the tests reaching your change run after each eval request; green proofs end your task and tell root."
 "Find more: (doc my.message/send), (my.program/source 'my.message/send), (dir my.program)."
 "Ask root with my.message/send, :my.message/to \"root\" and :my.message/content."
 "A reply is data: :value, :error, :findings and :result, a live symbol for later forms."
 "Gotcha: never pass or store nil; absence is no key. Fix the caller, not the contract: an optional input is an optional key, [:map [:x {:optional true} :int]], never [:maybe ...]."
 "Gotcha: in Malli [:= nil] and [:enum nil] are errors (nil lands in the properties slot); a literal is [:= {} nil]. A contract is [:=> [:cat in1 in2] out], inputs inside :cat."
 "Gotcha: a keyword lookup is falsy for absent, nil and false alike; test presence with (contains? m :k) or (find m :k)."
 "Gotcha: (some f xs) returns f's first truthy result, not the element: (some (fn [x] (when (even? x) x)) [1 2 3]) is 2."
 "Gotcha: map and filter are lazy and escape (binding ...) and try; use mapv, into or run! there."
 "Gotcha: write fully qualified symbols, (seon.db/q ...); an alias works only if (ns-aliases *ns*) shows it. #() literals do not nest."
 "Gotcha: an error is a map; read :seon.error/type, :seon.error/message and :seon.error/problems, then change the form; never resend it unchanged."]
```

## Message 2: role user (6221 characters, 1944 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:sym
      seon.sci.forgive/forgiving-forgiven-text-changes-nothing,
    :intent
      "`seon.sci.forgive/forgive` is idempotent: forgiving its `:seon.batch/text` again yields no `:seon.batch/repairs` and the same `:seon.batch/spans`. Today a dropped stray closer joins tokens (\"a]a\" -> \"aa\"), a quote before an inserted closer falls outside eve"…+430,
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
  ;; A "…+618}
       {:seon.test/sym
          seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-te"…+236}
       {:seon.test/sym
          seon.sci.forgive-test/the-repairs-account-for-every-change,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-te"…+339}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start
      {:in-ns seon.sci.forgive,
       :source
         (my.program/source
          'seon.sci.forgive/forgiving-forgiven-text-changes-nothing)}},
 :must-reads
   [{:seon.ref/name seon.sci.forgive/forgive,
     :seon.fn/spec
       "[:=> [:cat :string :seon.batch/mode] :seon.batch/forgiven]",
     :doc
       "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.",
     :seon.render/operations
       [(doc seon.sci.forgive/forgive)
        (my.program/source …1)
        (my.program/callers …1)
        …1],
     :arglists ([text mode]),
     :seon.ref/callers
       [seon.sci.forgive/segments seon.sci.forgive/step
        seon.sci.forgive-test/forgiving-again-changes-nothing?],
     :seon.ref/tests
       [seon.sci.forgive-test/a-markdown-reply-reads-only-its-forms
        seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart
        seon.sci.forgive-test/an-unbalanced-text-names-each-repair
        seon.sci.forgive-test/each-segment-carries-the-repairs-from-its-start-to-the-next
        seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing
        seon.sci.forgive-test/recorded-replies-read-into-the-same-segments
        seon.sci.forgive-test/the-reader-reads-a-reply-by-its-line-rules
        …4]}
    {:seon.ref/name seon.sci.forgive/scan,
     :seon.ref/callers
       [seon.sci.forgive/failure-step seon.sci.forgive/forgive],
     :seon.fn/spec
       "[:=> [:cat :string :boolean :boolean] [:map [:seon.batch/text :string] [:seon.batch/spans :seon.batch/spans] [:seon.batch/repair"…+63,
     :doc
       "One pass over `text`: the forgiven text, each top-level form's span `[start end line column]` in it, the repairs, and whether `t"…+30,
     :seon.render/operations [(doc seon.sci.forgive/scan) …3],
     :arglists ([text reply? repair?]),
     :seon.ref/tests
       [seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart
        seon.sci.forgive-test/the-repairs-account-for-every-change
        seon.sci.forgive-test/valid-clojure-is-unchanged]}
    {:seon.ref/name seon.sci.forgive/segments,
     :seon.fn/spec
       "[:function [:=> [:cat :string] :seon.batch/segmented] [:=> [:cat :string :seon.batch/mode] :seon.batch/segmented]]",
     :doc
       "Each form's source in the forgiven text, the text above it as its comment.",
     :seon.render/operations [(doc seon.sci.forgive/segments) …3],
     :arglists ([text] [text mode]),
     :seon.ref/callers
       [seon.sci.forgive/segments
        seon.sci.forgive-test/allocated-reading
        seon.turn/commented-forms seon.turn/segmented-sources],
     :seon.ref/tests
       [seon.sci.forgive-test/a-markdown-reply-reads-only-its-forms
        seon.sci.forgive-test/a-reply-reads-in-allocation-linear-in-its-length
        seon.sci.forgive-test/an-unbalanced-text-names-each-repair
        seon.sci.forgive-test/each-segment-carries-the-repairs-from-its-start-to-the-next
        seon.sci.forgive-test/recorded-replies-read-into-the-same-segments
        seon.sci.forgive-test/the-reader-reads-a-reply-by-its-line-rules
        seon.sci.forgive-test/tool-markup-among-code-is-dropped-at-read
        seon.sci.forgive-test/tool-markup-in-a-reply-is-prose]}
    {:seon.ref/name seon.sci.forgive/step,
     :seon.ref/callers
       [seon.sci.forgive/next-form seon.sci.forgive-test/positions],
     :seon.ref/tests
       [seon.sci.forgive-test/a-form-read-mid-text-has-the-texts-own-line-and-column],
     :seon.fn/spec
       "[:=> [:cat :seon.batch/read-stage :seon.batch/mode :seon.batch/r"…+33,
     :doc "The next read step at the cursor in `mode`.",
     :seon.render/operations [(doc seon.sci.forgive/step) …3],
     :arglists ([…3])}
    {:seon.ref/name :seon.batch/text,
     :seon.ref/line 1,
     :seon.ref/schema-form [:string …1],
     :seon.render/operations [(doc :seon.batch/text)]}
    {:seon.ref/name :seon.batch/repairs,
     :seon.ref/schema-form [:vector :seon.batch/repair],
     :seon.ref/line 1,
     :seon.render/operations [(doc :seon.batch/repairs)]}
    {:seon.ref/name :seon.batch/spans,
     :seon.ref/schema-form [:vector :seon.batch/span],
     :seon.ref/line 1,
     :seon.render/operations [(doc :seon.batch/spans)]}
    {:seon.ref/name :seon.batch/repair,
     :seon.ref/line 1,
     :seon.ref/schema-form […6],
     :seon.render/operations […1]}
    #:seon.ref{:name :seon.batch/dropped,
     :problem-kind :unresolved,
     :line 1}
    #:seon.ref{:name :seon.batch/inserted,
     :problem-kind :unresolved,
     :line 1}
    {:seon.ref/name :seon.batch/forgiven,
     :seon.ref/line 1,
     :seon.ref/schema-form […5],
     :seon.render/operations […1]}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.sci.forgive}}
```

