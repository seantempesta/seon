---
title: "Round 2 block, 1-forgive, before"
type: research
status: complete
created: 2026-10-12
---

# 1-forgive, before (task handle 90f6088b30d3)

The current-state block value as the agent's REPL reply shows it (display budget 2,000 on default af0a94471); the finding's value-tokens is the whole block. Method: README.md.

```clojure
#:seon.repl{:findings [{:seon.repl/finding :large-value,
  :seon.eval/value-tokens 3755,
  :seon.eval/shown-tokens 1927,
  :seon.repl/fix
    "A value this big usually means a query or function returns more than it should: select what you need."}], :result result/e227ae5358d20, :ms 130, :alloc-mb 244.5, :value #:seon.context{:turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.sci.forgive},
 :task
   #:seon.task{:id "90f6088b30d3",
    :sym seon.sci.forgive/forgiving-forgiven-text-changes-nothing,
    :intent
      "`seon.sci.forgive/forgive` is idempotent: forgiving its `:seon.batch/text` again yields no `:seon.batch/repairs` and the same `:seon.batch/spans`. Today a dropped stray closer joins tokens ("…+497 (subs (get-in result/e227ae5358d20 [:seon.context/task :seon.task/intent]) 190),
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
          "(deftest ^{:seon/tasks #{'seon.sc"…+713 (subs (get-in result/e227ae5358d20 [:seon.context/proofs :seon.context/proof-list 0 :seon.test/source]) 33)}
       {:seon.test/sym
          seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-te"…+236 (subs (get-in result/e227ae5358d20 [:seon.context/proofs :seon.context/proof-list 1 :seon.test/source]) 64)}
       {:seon.test/sym
          seon.sci.forgive-test/the-repairs-account-for-every-change,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.s"…+371 (subs (get-in result/e227ae5358d20 [:seon.context/proofs :seon.context/proof-list 2 :seon.test/source]) 32)}]},
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
        (my.program/source 'seon.sci.forgive/forgive)
        (my.program/callers
         {:seon.program/subject 'seon.sci.forgive/forgive})
        (…2 (get-in result/e227ae5358d20 [:seon.context/must-reads 0 :seon.render/operations 3]))],
     :arglists ([text mode]),
     :seon.ref/callers
       [seon.sci.forgive/segments seon.sci.forgive/step
        seon.sci.forgive-test/forgiving-again-changes-nothing?],
     :seon.ref/tests
       [seon.sci.forgive-test/a-markdown-reply-reads-only-its-forms
        seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart
        seon.sci.forgive-test/an-unbalanced-text-names-each-repair
        seon.sci.forgive-test/each-segment-carries-the-repairs-from-its-start-to-the-next
        …7 (drop 4 (get-in result/e227ae5358d20 [:seon.context/must-reads 0 :seon.ref/tests]))]}
    {:seon.ref/name seon.sci.forgive/scan,
     :seon.ref/callers
       [seon.sci.forgive/failure-step seon.sci.forgive/forgive],
     :seon.fn/spec
       "[:=> [:cat :string :boolean :boo"…+159 (subs (get-in result/e227ae5358d20 [:seon.context/must-reads 1 :seon.fn/spec]) 32),
     :doc
       "One pass over `text`: the forgiv"…+126 (subs (get-in result/e227ae5358d20 [:seon.context/must-reads 1 :doc]) 32),
     :seon.render/operations
       [(doc seon.sci.forgive/scan)
        …3 (drop 1 (get-in result/e227ae5358d20 [:seon.context/must-reads 1 :seon.render/operations]))],
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
     :seon.render/operations
       [(doc seon.sci.forgive/segments)
        …3 (drop 1 (get-in result/e227ae5358d20 [:seon.context/must-reads 2 :seon.render/operations]))],
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
    #:seon.ref{:name seon.sci.forgive/step,
     :callers
       [seon.sci.forgive/next-form seon.sci.forgive-test/positions],
     :tests
       [seon.sci.forgive-test/a-form-read-mid-text-has-the-texts-own-line-and-column],
     …4 (get-in result/e227ae5358d20 [:seon.context/must-reads 3])}
    {:seon.ref/name :seon.batch/text,
     :seon.ref/line 1,
     :seon.ref/schema-form
       […2 (get-in result/e227ae5358d20 [:seon.context/must-reads 4 :seon.ref/schema-form])],
     :seon.render/operations [(doc :seon.batch/text)]}
    #:seon.ref{:name :seon.batch/repairs,
     :schema-form [:vector :seon.batch/repair],
     :line 1,
     …1 (get-in result/e227ae5358d20 [:seon.context/must-reads 5])}
    #:seon.ref{:name :seon.batch/spans,
     :schema-form [:vector :seon.batch/span],
     :line 1,
     …1 (get-in result/e227ae5358d20 [:seon.context/must-reads 6])}
    …4 (drop 7 (get-in result/e227ae5358d20 [:seon.context/must-reads]))]}}
```
