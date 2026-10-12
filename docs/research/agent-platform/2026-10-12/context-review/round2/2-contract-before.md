---
title: "Round 2 block, 2-contract, before"
type: research
status: complete
created: 2026-10-12
---

# 2-contract, before (task handle 59cc831bfd08)

The current-state block value as the agent's REPL reply shows it (display budget 2,000 on default af0a94471); the finding's value-tokens is the whole block. Method: README.md.

```clojure
#:seon.repl{:findings [{:seon.repl/finding :large-value,
  :seon.eval/value-tokens 1370,
  :seon.eval/shown-tokens 1370,
  :seon.repl/fix
    "A value this big usually means a query or function returns more than it should: select what you need."}], :result result/e44573ce70cd8, :ms 116, :alloc-mb 243.6, :value #:seon.context{:turns
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
    :seon.context/start
      {:in-ns seon.ai,
       :source (my.program/source 'seon.ai/contract-output-observed?)}},
 :must-reads
   [{:seon.ref/name seon.ai/output-observed?,
     :seon.ref/callers [seon.ai/send-request],
     :seon.render/operations
       [(doc seon.ai/output-observed?)
        (my.program/source 'seon.ai/output-observed?)
        (my.program/callers
         {:seon.program/subject 'seon.ai/output-observed?})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.ai/output-observed?})],
     :arglists ([evidence]),
     :seon.ref/tests
       [seon.ai-test/a-reasoning-finish-settles-before-the-response-body-ends
        seon.ai-test/a-reasoning-only-time-limit-names-the-missing-assistant-text
        seon.ai-test/a-stop-interrupt-exits-the-http-leaf-with-the-flag-set
        seon.ai-test/closing-one-attempts-body-cannot-close-its-concurrent-peer]}
    {:seon.ref/name seon.contracts/gaps,
     :seon.ref/callers [seon.contract-coverage-test/contracted],
     :seon.fn/spec
       "[:=> [:cat :seon.db/database-value :seon.fn/sym] [:or [:vector [:or :seon.fn.contract/finding-row [:map [:seon.fn/sym :seon.fn/sym] [:seon.contracts/absent [:= true]]]]] :seon.error/value :seon.db/invalid-read-error :seon.schema/validation-refusal]]",
     :doc
       "What keeps `function-symbol` from a complete contract in `database`.",
     :seon.render/operations
       [(doc seon.contracts/gaps)
        (my.program/source 'seon.contracts/gaps)
        (my.program/callers
         {:seon.program/subject 'seon.contracts/gaps})
        (my.program/tests-reaching
         {:seon.program/subject 'seon.contracts/gaps})],
     :arglists ([database function-symbol]),
     :seon.ref/tests
       [seon.contract-coverage-test/a-contracted-function-has-no-gaps
        seon.contract-coverage-test/cluster-branch-is-contracted
        seon.contract-coverage-test/context-projection-is-contracted
        seon.contract-coverage-test/edn-encoded-is-contracted
        seon.contract-coverage-test/fs-attributes-is-contracted
        seon.contract-coverage-test/function-arities-in-is-contracted
        seon.contract-coverage-test/instruction-entity-changes-is-contracted
        seon.contract-coverage-test/output-observed-is-contracted
        seon.contract-coverage-test/query-params-is-contracted
        seon.contract-coverage-test/resource-population-is-contracted
        seon.contract-coverage-test/runtime-duration-is-contracted
        seon.contracts-test/an-absent-function-is-one-absent-gap]}
    {:seon.ref/heading "Function contracts guide",
     :seon.ref/tokens 3228,
     :seon.ref/line 1,
     :seon.ref/path "docs/seon/architecture/contracts-guide.md",
     :seon.render/operations
       [(my.fs/section "docs/seon/architecture/contracts-guide.md")]}]}}
```
