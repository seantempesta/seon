---
type: issue
status: resolved
severity: blocker
tags: [issue, turn, context, read-evidence, fixture, class/p1]
---

# A seeded opening stores no read evidence, so the first wake re-emits the whole opening

Observed 2026-09-09 14:16 on `default` (republished `6aa1be3c…`, Juniper
seeded by the fixture's `install!`, root's first message as the wake): the
prompt carried the opening TWICE — identity, plan, inbox, settings, and the
namespace data query at ~97 ms each, then the same five again at ~7 ms —
fifteen evaluations instead of six. After root's SECOND message only the
inbox and settings re-emitted, which is the ruled behaviour (§14).

So the since-diff worked once it had evidence to compare against. The
first pass had none: the evaluations the fixture's seed stored for turn 0
carry no `:seon.eval` read evidence, every read looks "changed", and the
first wake appends a second copy of the opening. Any opening produced
outside the ordinary evaluation point (a fixture, a control, a probe)
that skips evidence has the same effect.

Also in the same prompt, same owner: `▲` markers copied verbatim from
PRD §18a into the help lines; `(help)` sixth instead of first; the inbox
rendered as a text table on its second emission (two rows) and as data on
the first — the value renderer's shape must not depend on row count;
placeholder `(+ 1 1)` virtual turns in the history; the derived Tools
line carrying `my.run`'s stale docstring and internal helpers.

## Fix

- There is one evaluation point (§13/§15); seeding an opening goes through
  it, so evidence is stored with every read — a fixture never writes
  evaluation rows by hand.
- Regression on the canonical fixture: seed, wake once, the prompt holds
  the opening exactly once; wake again with one changed read, exactly one
  system-turn evaluation appends.

## Verified cause, 2026-09-09 resume

The later default capture had evidence counts `[4 1 4 3 6 1]` on all six
opening evaluations. Missing `:seon.eval/*` attributes alone did not establish
missing evidence: the current owning attribute is
`:seon.cluster.eval/read-evidence`. The fixture's `system-turn` path already
uses `evaluate-sources` and its settlement writer.

The newly exercised **agent-creation** path was different: its legacy
`seon.bootstrap/next-entry` generator returned no entry, closed the bootstrap
turn with zero evaluations, and the next system pass emitted the opening.
The canonical real-graph regression failed two assertions before repair.
Creation now uses `declared-sources`, the same generator as `system-turn`,
and retains `resume-turn` → `evaluate-sources` → ordinary settlement. The
regression also seeds the order fixture, wakes it once, verifies each opening
source occurs exactly once, then changes one read and verifies one append.
The remaining presentation defects below this issue's original observation
are the next ordered slice; this note remains open until they are handled.

## Live race and presentation repair

The fresh scratch boot reproduced help sixth plus duplicated reads even with
read evidence present. A system pass evaluated against pre-compaction
history, then committed after fixture cleanup. `system-turn` now admits an
append inside the serial writer only while its originating history remains
current. The deterministic regression interleaves two real SCI system passes
and verifies one retained opening. The live scratch reseed now contains
exactly six evaluations with help first (5,803 bytes).

The same slice removes revision triangles, disables table inference at the
value renderer, removes arithmetic placeholders from the no-provider and
virtual-turn paths, renames the ruled surface to `my.turn`, declares internal
helpers so Tools excludes them, and projects plan dependencies as ids.
The landing note contains the complete scratch prompt. Default reseeding
and the final trial remain the final assigned slice, so this issue stays open
until that live verification is recorded.

## Resolution (2026-09-15 triage)

surface: context-generation

The original missing-evidence attribution was already falsified in this note. Fix `6aca09cce` routes creation through the shared system generator; `e915d2de0` orders the opening and guards its append against changed history. At HEAD `91d5547b5`, `src/seon/turn.clj:2080–2090` executes generated sources through `evaluate-sources`; `:2180–2192` checks the current history inside Datahike's serial transaction before appending. `test/seon/loop_proof_test.clj:508–527` asserts no unchanged system append and one copy of each stable seeded source after the first real proc wake. `test/seon/help_test.clj:17` covers help-first storage. Current `test/seon/render/value_test.clj` includes collection-cardinality invariance; the focused fast run is recorded in the landing note. These are current mechanism and regression evidence, not a new reseed of the owner's default cluster.
