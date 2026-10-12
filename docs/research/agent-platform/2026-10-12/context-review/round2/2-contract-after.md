---
title: "Round 2 block, 2-contract, after"
type: research
status: complete
created: 2026-10-12
---

# 2-contract, after (task handle 59cc831bfd08)

The current-state block value as the agent's REPL reply shows it (display budget 2,000 on default af0a94471); the finding's value-tokens is the whole block. Method: README.md.

```clojure
#:seon.repl{:findings [{:seon.repl/finding :large-value,
  :seon.eval/value-tokens 1033,
  :seon.eval/shown-tokens 963,
  :seon.repl/fix
    "A value this big usually means a query or function returns more than it should: select what you need."}], :result result/ef59c2d05b769, :ms 141, :alloc-mb 304.2, :value #:seon.context{:turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.ai},
 :task
   #:seon.task{:id "59cc831bfd08",
    :sym seon.ai/contract-output-observed?,
    :must-read #{seon.ai/output-observed? seon.contracts/gaps},
    :intent
      "Give `seon.ai/output-observed?` a complete Malli contract that checks what its boundary is for, with the cheapest schema that checks it (`docs/seon/architecture/contracts-guide.md`): registered names, every input and output, the error variants it returns. A contract that refuses a real value (a reaching test, a live call, your probe) is a finding, not friction: if the contract misread the value's shape, fix the contract and cite the observed shape; if the producer emits a bad value, keep the contract, file a task on the producer naming `seon.ai/output-observed?` in its must-read with the exact form, the refused value's essence and the Malli explain, and say so in your reply. Never widen to :any, [:maybe ...] or :map to go green. Done when `seon.contracts/gaps` answers [] for it and its reaching tests stay green and no slower."},
 :proofs
   #:seon.context{:counts {:pending 1},
    :proof-list
      [{:seon.test/sym
          seon.contract-coverage-test/output-observed-is-contracted,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.ai/contract-output-observed?}} output-observed-is-contracted
  (contracted 'seon.ai/output-observed?))"}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start {:seon.render/operations [(in-ns 'seon.ai)]}},
 :must-reads
   [{:seon.ref/name seon.ai/output-observed?,
     :arglists ([evidence]),
     :seon.render/operations
       [(my.program/source 'seon.ai/output-observed?)
        (my.program/callers
         {:seon.program/subject 'seon.ai/output-observed?})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.ai/output-observed?})]}
    {:seon.ref/name seon.contracts/gaps,
     :doc
       "What keeps `function-symbol` from a complete contract in `database`.",
     :arglists ([database function-symbol]),
     :malli/schema
       [:=>
        [:cat :seon.db/database-value :seon.fn/sym]
        [:or
         [:vector
          [:or
          :seon.fn.contract/finding-row
          [:map
          [:seon.fn/sym :seon.fn/sym]
          […2 (get-in result/ef59c2d05b769 [:seon.context/must-reads 1 :malli/schema 2 1 1 2 2])]]]]
         :seon.error/value
         :seon.db/invalid-read-error
         :seon.schema/validation-refusal]],
     :seon.render/operations
       [(my.program/source 'seon.contracts/gaps)
        (my.program/callers
         {:seon.program/subject 'seon.contracts/gaps})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.contracts/gaps})]}
    {:seon.ref/heading "Function contracts guide",
     :seon.ref/path "docs/seon/architecture/contracts-guide.md",
     :seon.render/operations
       [(my.fs/section "docs/seon/architecture/contracts-guide.md")]}]}}
```
