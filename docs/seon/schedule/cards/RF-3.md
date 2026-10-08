---
title: "Card RF-3 Which declarations a branch cannot prove"
type: reference
status: active
created: 2026-10-08
---

# RF-3 Which declarations a branch cannot prove

Design: `docs/research/agent-platform/2026-10-08/repl-first/repl-first.md` §2 B4. Lane: Opus.

CLAUDE.md: host-bound status is computed per declaration, never from a namespace roster. Installed:
`:seon.fn/host-bound?` per function (`seon.fn/host-bound-callers`), meaning SCI cannot interpret its body; on
default 86 true, 5,598 false. A branch defn of such a declaration keeps the compiled Var, so its branch evidence
proves nothing: RF-2 refuses it today.

Not installed: a declaration SCI can interpret but that runs compiled under the reaching tests because its callers
are compiled platform code (the evaluator, writer, test runner, boot, Flow procs). Its branch definition is never
executed by the tests, so green evidence is not about it.

## Options (root rules)

1. **(recommended)** Execution evidence: the test runner records, per member, the definition digests whose
   interpreted (branch) bodies executed (SCI's call hook already counts calls per armed Var:
   `:seon.repl/calls`). RF-2 then requires each committed digest to appear in some green member's executed set.
   No classification at all: evidence decides. Cost: one set per member, bounded by the reached graph.
2. Static: a declaration reached (reverse call graph) from any function whose own `:seon.fn/host-bound?` is true is
   platform. Cheap, but over-classifies: nearly everything is reached from some compiled caller.
3. A declared `:seon.fn/platform? true` metadata on the owners (evaluator, writer, runner, boot): a hand list,
   which CLAUDE.md forbids as a roster.

## Proof

Option 1: `seon.test.runner-test/a-member-records-the-branch-digests-it-executed` (new).
