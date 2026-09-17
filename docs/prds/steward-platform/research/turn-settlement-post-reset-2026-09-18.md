---
type: research
status: landed
created: 2026-09-18
tags: [turn, settlement, symbols, reset]
---

# Turn settlement after the symbol reset

## Authority and boundary

This lane read `AGENTS.md` sections 0–5, `.agents/skills/datahike/SKILL.md`
(including “The three grammars of a reference”),
`.agents/skills/clojure-testing/SKILL.md`, the turn PRD §§13–15, the symbol
inventory §2, and program-facts PRD §1f G1–G3 end to end before editing.
The ruling applied here is that namespace/function names and observed call,
require, and test-subject names are symbol values. Only living entity
relations use refs; an unresolved observed name never earns a fabricated
entity.

The requested `bin/test-fast --paths ...` invocation refused because the
published graph required concurrently held caller files
`src/seon/test.clj` and `test/seon/cluster/source_test.clj`. A plain shared
tree run then encountered those lanes’ in-flight projection/config changes.
Per the assignment, verification continued in a clean HEAD worktree with
`reference-code` linked and only this lane’s commits cherry-picked. No cold
gate, `--all`, `--full`, or operation on `default` was performed.

## Class A/B — settlement program rows

Root: production and fixtures were both stale. `src/seon/turn.clj:1214`
reconstructed an already-symbolic row identity with `symbol`, while
`src/seon/sci/eval.clj` repeated the pre-reset conversion at installed-row,
candidate, namespace, and test-symbol read sites. The settlement tests still
built string identities and lookup-ref vectors for symbol-valued edges, and
some bypassed the analyzer that supplies the required digest.

Fix: row construction and installation now carry symbols without
string/symbol round trips. Canonical-fixture settlement regressions pass
analyzed declaration rows, assert `:seon.ns/name` is a symbol and
`:seon.ns/requires` is a symbol set, and use symbol values for every
symbol-valued edge.

The stale `settlement-mints-rows-for-unindexed-call-targets` expectation was
replaced at `test/seon/turn_test.clj:1271`. Its regression now proves all
three ruled facts together: the call and require tokens survive as values,
`seon.program/unresolved-callers` reports the absent callee, and neither a
function nor namespace row is fabricated.

Landed as `c7e63ba4b` plus focused fixups `cf6cd8df7`, `69696e72d`,
`88a7ab586`, and `b0c272486`.

## Class C — resumed namespace

Root: production had a real missing write, and the fixture had two stale
assumptions. The committed `:seon.sci.eval/ending-ns` was already a symbol,
so the old string expectation was wrong. Removing the fixture’s fabricated
namespace preseed exposed the production gap: bare `in-ns` changes the live
SCI namespace but emitted no declaration row because it creates neither a
function row nor a reader row. The following fold therefore selected the
right symbol but the entity ref could not resolve.

Fix: `src/seon/sci/eval.clj:2806` emits a real `:seon.ns` declaration for an
observed SCI namespace change when no other declaration row exists. This is
not an unresolved-name stub: `in-ns` has created the living SCI namespace.
`test/seon/turn_loop_test.clj:401` settles that row, derives the resumed
namespace from stored evaluations, and proves the next function is analyzed
under it. The expected qualified function name is constructed from data so
the regression does not accidentally register itself as that function’s gate
test.

Landed as `f755dd184` plus focused fixups `6c94bcd3f`, `ba2f0d5d1`, and
`a2aad60ab`.

## Class D — empty reads in index adoption

Root: fixture. The synthetic index-adoption rows had no installed unique
identity, so final whole-entity validation correctly refused their writes.
The later reads were empty because the fixture ignored the refused write, not
because the production adoption read joined symbols incorrectly.

Fix: `test/seon/cluster_test.clj:215` declares one synthetic unique identity
attribute and supplies an identity value on each synthetic row. The existing
production adoption mechanism is unchanged. Landed as `2d36c7f13`.

## Armed verification

The inherited baseline was 69 tests / 576 assertions, 11 failures / 9 errors.
An intermediate clean-snapshot run after classes A/B/D was 69 tests / 588
assertions, 1 failure / 3 errors; the one owned failure was class C.

The final clean-snapshot command was:

```text
bin/test-fast seon.turn-test seon.turn-loop-test seon.cluster-test
```

It armed 1,204 contracts in `:panic` mode and ran 69 tests / 592 assertions:
**1 failure / 2 errors**. Every assigned class and named failing test is now
green, including the resumed-namespace regression and added-index adoption.

The remaining result is a named inherited dependency, reproduced on clean
HEAD and recorded by
`docs/prds/steward-platform/research/audit2-blockers-2026-09-18.md`:

- two `seon.turn-loop-test` fixtures write unresolved required
  `:seon.config/agent` refs and are refused at the writer;
- `seon.cluster-test/schema-row-convergence-uses-the-stores-own-semantics`
  compares the canonical fixture population with a newer registered resource
  population and reports unrelated schema rows.

The orchestrator still owes the cold path-limited gate and platform proof.
