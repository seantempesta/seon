---
title: "Card CO-5: Reach acquires the requested closure"
type: reference
status: active
created: 2026-10-08
---

# CO-5 Reach acquires the requested closure

Pattern P1 (merged.md D2: Astra's demand acquisition; Opus's delta advance shelved). Lane: Opus (reach correctness
under cycles, retargeted edges, unresolved names). Split into CO-5a (one shape) and CO-5b (the rest) if one does not fit.

## What exists
`reach-declarations` builds whole-attribute maps (`reach-attribute`, 11 attributes) for any request; a one-test
check reached 98 names and 489 schema nodes while acquisition held 8,374 declarations and 3,503 schemas: 301 ms,
698 MB cold, 0.65 ms warm (Astra P). Any `defn` changes `:seon.program/definition-digest`'s revision, so the next
check re-reads whole attributes.

## Change
Walk the frontier from the requested tests: per round, read the frontier names' edges and leaves by AVET/EAVT
(or `pull-many`) and add unseen names; keep unresolved names and their absence so a later definition is noticed;
run the existing SCC/digest code on the acquired graph. Per-node data is keyed by the node's entity content
(definition digest plus its edge datoms), so unchanged nodes are reused across `defn`s. A request whose closure is the
program does one union walk.

## Functions
- CHANGE `seon.test.runner/reach-graph` (private): frontier acquisition; `reach-declarations` stays for whole-program
  callers until none remains (then deleted in the same card's follow-up).

## Proof
Extend `seon.test-reaching-test/reach-digests-follow-only-changed-closures`: add 200 unrelated fixture
declarations; the reads of a one-test request are unchanged (`:seon.profile/reads` equal with and without them) and
digests equal the parent's. Existing cycle, retargeted-edge, concurrent and fresh-branch tests stay green.

## Cost
Expected: ∝ closure (98 names: a few ms, low MB). Parent 301 ms / 698 MB cold.

## Commit
`bin/lane commit <lane> -m "CO-5: reach acquires the requested closure" --test seon.test-reaching-test src/seon/test/runner.clj test/seon/test_reaching_test.clj`

## Dependencies
CO-0, CO-4 (same file).
