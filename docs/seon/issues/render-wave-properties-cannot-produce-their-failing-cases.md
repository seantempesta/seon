---
type: issue
status: open
severity: friction
tags: [issue, testing, render, context]
---

# The render wave's seeded properties cannot produce their failing cases

## Problem

Five properties landed in this wave read as adversarial coverage and
are not. Each was replayed at its own seed.

- `p1-membership-is-complete-or-loudly-elided`
  (`test/seon/render/walk_test.clj`). The predicate
  `(or (empty? missing) (elision? result))` accepts ANY elision node
  anywhere in the tree as proof for ANY omission — it never ties a
  marker to the entity it covers. Its generator creates only
  registered-family agents, so it structurally cannot produce the class
  in `render-walk-silently-drops-entities-outside-registered-families.md`.
- `p5-shared-instruction-leaves-are-byte-identical` (same file). Its
  generator is `gen/elements` over four fixed instruction ids run 100
  times: four distinct cases and 96 redundant trials, each recomputing
  two complete neighbourhoods.
- The transcript budget-floor property
  (`test/seon/render/transcript_test.clj:392`) draws
  `extra-budget (gen/choose 0 800)`. Replaying the exact seeded draw
  sequence: ZERO of 40 trials drew 0, so the property never once ran at
  the derived minimum it claims to hold at. The smallest draw was 3, and
  a uniform draw over 801 values reaches the floor with probability
  about 5 % for any seed.
- `(is (pos? floor))` (`transcript_test.clj:274`) cannot fail:
  `minimum-token-budget` bottoms out at the always-present HTML
  `<section>` wrapper; an agent with no history and a nil database both
  return 16.
- `tight-budgets-pull-only-a-budget-derived-newest-candidate-set`
  asserts `(<= % (max 6 floor))`, which restates
  `projection`'s own `candidate-limit` formula with a `<=`. It can only
  fail if `pull-many` received more ids than the limited queries
  returned, which is structurally impossible.

The transcript generators are happy-path only. Across all 40 seeded
trials the longest generated content is 38 characters (about 9 tokens)
and no content contains a non-alphanumeric character — never empty,
never unicode, never a newline, quote, or backslash. So
`tokens/clip-str` and the entire `best-summary` preview search are
never exercised on a payload that clips, and `readable-source?` is
never asked the escape-sensitive question it exists to answer. The test
helper's own `\"([^\"]+)\"` regex would mis-parse a quoted id, degrading
the property to vacuity rather than failing.

`a-tight-budget-degrades-then-elides-loudly` uses `(+ floor 180)`,
a hand-tuned margin nothing derives.

## Acceptance

Each listed property either produces its failing case or is replaced.
Concretely: P1's marker must NAME the omission it covers and its
generator must emit entities outside every registered family; P5 is an
example test or gains a real generator; the transcript budget property
weights the derived floor explicitly (`gen/frequency` or a separate
example) rather than hoping a uniform draw hits it; the trivially-true
assertions are deleted or strengthened to something that can fail; and
the content generator produces empty, unicode, newline, quote, and
clip-sized payloads. One check per property that it fails when the
behavior it names is broken — verified by breaking it.

Genuinely good and worth keeping as the model:
`p6-every-active-cap-is-loud` is a biconditional
`(= (< width (count agent-ids)) (reverse-elision? result))` whose
generator spans both sides, and the transcript's timestamp-collision
generator (`(gen/choose 0 8)` offsets over up to 18 events) hammers the
`(time, kind, id)` tie-break hard.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`
