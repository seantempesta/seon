---
type: research
status: awaiting scope clarification; implementation not started
created: 2026-09-17
tags: [research, program-graph, indexing, sci]
---

# S1 grounding and acceptance boundary

S1 is **not implemented or ready for a gate**. No production function was
changed. The requested standing regression and Juniper live turn proof
remain outstanding.

## Authorities and seams read

Read the named program-facts PRD and `tmp/orchestrator/wave2/repl-rule.txt`
end to end, and AGENTS.md's requested sections. Read the following seams
before considering production edits:

- `src/seon/fn/analyzer.clj`, whole file.
- `src/seon/fn.clj:690–780`, `1040–1110`; additionally source extraction,
  namespace context, `var-row`, `analyzed-form`, `analyze-forms`,
  `analysis-rows-by-file`, `build-artifact`, `build-manifest`, `rows`,
  `desired-rows` and contract enrichment.
- `src/seon/program.cljc`: shapes, test markers, canonical-row,
  declaration-row and their supporting constructors.
- `src/seon/sci/reader.cljc`, whole file.
- `src/seon/sci/eval.clj:380–460`, `780–1010`; additionally reader-context,
  namespace binding rows, declared-row and evaluation's definition-row call.
- `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj`, whole file,
  and its analysis README's consumed output shapes.
- Existing fixture examples in `test/seon/fn_test.clj`,
  `test/seon/turn_test.clj`, `test/seon/program_test.clj`, and the
  three-argument in-process runner in `src/seon/test.clj`.

Applied the data-oriented-clojure, repl and clojure-testing skills. The
context-generation roadmap entry was read; the full historical working
edge was not reviewed (its tool output was truncated).

## Dependency ledger

clj-kondo supplies namespace definitions/usages, Var definitions/usages,
metadata and keyword ownership; Seon's analyzer normalizes those keys.
`seon.fn/var-row` lifts them; `seon.program/canonical-row` selects the
threaded shapes' owned attributes. SCI's current namespace bindings feed
`reader-context`; its Var metadata currently supplies `definition-row`.
`seon.fn/analyze-forms` already enriches submitted rows with edges later
in the turn path. Replacing the row constructor must preserve that
writer's transaction provenance and pending-call handling.

## Measured evidence

Default was alive at PID 53320, prepl 57929; MCP runtime status answered.
It reported one error signature and five failed tests, without attribution
to S1. The inherited config edit and subsequent foreign boot-test and
landing-note edits were left untouched.

An MCP JVM call of `seon.fn/analyze-form` in the existing `seon.id`
namespace returned `[{:seon.fn/calls #{[:seon.fn/sym "seon.id/id"]}} nil]`
for a source function calling `seon.id/id`; reported time 23 ms.
An earlier call using absent `user` returned the expected typed
`seon.fn/namespace-unresolvable` refusal, not a successful empty analysis.

The [saved read-only probe](program-facts-s1-analysis-probe-2026-09-17.clj)
ran through MCP in 8 ms. It returned these four identities:

```clojure
[[:seon.ns/name sample.s1]
 [:seon.fn/sym "sample.s1/value"]
 [:seon.fn/sym "sample.s1/caller"]
 [:seon.test/sym "sample.s1/example"]]
```

Schema rows were `[]` despite the literal registration in the source.
The caller's edge was `#{[:seon.fn/sym "sample.s1/value"]}`. Its indexed
coordinates were:

```clojure
{:seon.fn/file [:seon.fn.file/relative-path "<stdin>"]
 :seon.fn/form-span [185 251]}
```

The tool retained the full result at blob digest
`55761fe1e02a2eb2204dd671f01a837cd1b82a4a2b80b98b414219a8c62a9649`
(4165 bytes). These are immutable source-analysis probes, not tests or
agent evaluations. **In-process tests: zero; cold gates: zero.**

## Decisions requested

1. The literal exception `:seon.fn.file/*` does not include the existing
   declaration attributes `:seon.fn/file` and `:seon.fn/form-span`.
   An indexed declaration has them; an evaluated form must not fabricate
   them. Strict equality after only the specified exclusions cannot hold.
2. Literal source schema registration is omitted at the existing indexer
   seam. Keeping the requested schema acceptance requires fixing this
   additional producer behavior, not manually seeding the test's row.

Options sent to the owner:

- Recommended: retain the complete acceptance case, include the existing
  coordinate attributes in its exception, and fix source schema lifting
  in the existing indexer. Cost: additional indexer/regression work;
  no coordinate rename. Gives up the literal spelling of I1's exception.
- Retain acceptance and rename coordinate attributes into `seon.fn.file`.
  Cost: schema breakage and downstream consumer edits, with reset-boundary
  verification. Gives up S1's narrow source/evaluation scope.
- Narrow S1 to function/test analysis and defer schema parity. Cost: a
  smaller slice but incomplete I1 coverage. Gives up the stated acceptance.

## Diff and boundary

This commit contains the investigation, read-only probe and
[schema-indexing issue](../../../seon/issues/source-analysis-omits-literal-schema-declarations.md)
only. No indexed-versus-evaluated after-diff exists because no
implementation or turn proof was performed. No gate request is submitted:
the orchestrator must not treat this investigation as a completed S1 slice.
No test JVM, restart, scratch cluster, scratch worktree or background shell
was started. No protected production hunk was needed or edited.
