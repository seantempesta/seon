---
title: "Context review 2: the exact first-call model context for seon.ai/contract-output-observed?"
type: research
status: complete
created: 2026-10-12
---

# Context review 2: seon.ai/contract-output-observed?

- Task: `seon.ai/contract-output-observed?` (handle `59cc831bfd08`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call; `no-provider` untouched)
- Default: git HEAD 214c61330, cluster `default`, basis-t 536871283 (commit-id `6acce7dd-6423-5f97-8e18-dd61d5d403d5`)
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat` (config default), the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message.
- Total estimated tokens (`seon.ai.tokens/estimate`, chars/token calibration): **2078**

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

## Message 2: role user (4362 characters, 1363 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:sym seon.ai/contract-output-observed?,
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
       [(my.fs/section "docs/seon/architecture/contracts-guide.md")]}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.ai}}
```

