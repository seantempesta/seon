---
type: issue
status: superseded
severity: friction
tags: [issue, instrumentation, render, contract, class/bounded-output]
---

# Contract evidence carries the offending argument twice, and busts its own bound

## Disposition — 2026-09-15

Implementation: `a65985098`, with final wording in `1fd81b2be`; dependency selection `a24ad11b7`.

The serialized argument copy was already removed at `6acd8818e`. Current construction retains the actual object; the target no longer bounds stored result objects by a separate serialization ceiling. A live default probe confirms identical object references at both semantic coordinates and no `:seon.instrument/args` key (4,283 ms). The existing object-retention/allocation regression passes. The error grammar renders only the offending problem coordinate under the value-render profile; no second serialized representation was added.

Implementation commit and gate results: [refusal-grammar landing](../../../prds/context-generation/research/refusal-grammar-2026-09-15.md).


Found 2026-09-08 by `instrumented-gate-backlog-2` while draining the armed
gate's reds
([landing note](../../../prds/context-generation/research/instrumented-gate-backlog-2-landing-2026-09-08.md)).

`seon.instrument-test/registry-sized-contract-evidence-is-bounded-at-construction`
asserts that a constructed contract-violation value stays under 1,024
estimated tokens — the declared equivalent of the former 4,096-character
ceiling. It measures **1,117** at HEAD.

The bound is not merely exceeded; it is exceeded by a DUPLICATE. Measured on
the suite's own reproduction (an oversized schema registry handed to an
`[:=> [:cat :int] :int]` contract), with `seon.ai.tokens/estimate`:

| key | estimated tokens |
|---|---|
| `:seon.error/data` | 1,015 |
| ‣ `:seon.instrument/args` | **385** |
| ‣ `:seon.error/diagnostic-offending` | **383** |
| ‣ everything else in `:seon.error/data`, all fifteen keys | 247 |
| `:seon.error/message` + `:seon.instrument/contract-violated` + `:seon.error/kind` | 43 |

`:seon.error/diagnostic-offending` is the bounded argument as DATA;
`:seon.instrument/args` is `pr-str` of that same bounded argument. One fact,
two spellings, 768 of the 1,015 inner tokens. Every other key in the value —
the function, the arm, the expected schema, the problem count and paths, the
caller, the layer — costs 247 tokens together.

## Why it matters

This is the ruled shape the vocabulary table calls a mirror: the string is
derivable from the value and nothing decides which one a reader should trust.
Tightening `seon.instrument/contract-evidence-caps` would buy the bound back
by making BOTH copies smaller — a tuned constant standing in for a structural
duplication, which is the repair this project calls a defect on sight.

## What the repair has to reckon with

`:seon.instrument/args` is a declared attribute
(`resources/seon/schemas/seon.error.edn:108-110`), read by `seon.error` at
`src/seon/error.clj:419-421,440,665`, and asserted by
`test/seon/error_test.clj:400-425,614-622,666`,
`test/seon/flow_test.clj:859-861` and `test/seon/problems_test.clj:148`. So
this is a one-owner refactor across the error fact family, not a local edit —
and `test/seon/error_test.clj` was held by a concurrent lane when this was
found.

## Acceptance criteria

- One spelling carries the bounded offending argument; the other is derived
  at the reader, or deleted with its declaration in the same commit.
- `registry-sized-contract-evidence-is-bounded-at-construction` is green
  without changing its 1,024-token bound and without narrowing
  `contract-evidence-caps`.

## Re-observed 2026-09-15 21:10Z — the count is missing from the count refusal

`(seon.problems/problems db)` on default (jvm mode) was refused with
"seon.problems/problems refused argument count at []: expected the declared
arglists, got an argument count of. Fix: Call one of the declared arglists."
The sentence omits the count and never names the declared arglists it tells
the caller to use. Same class: the grammar's "got <value>" slot rendered
nothing for an integer. Belongs to the refusal-grammar lane's regression set.
