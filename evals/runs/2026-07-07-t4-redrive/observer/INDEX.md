---
type: research
status: completed
tags: [research, agent, eval]
---

# Observer INDEX — T4 2026-07-07 re-drive, render-quality read

Dedicated render-quality pass over all 12 byte-exact drive transcripts
(`../transcripts/*.txt`), independent of the driver's outcome/gate analysis
(`../README.md`, `../defects.md`). This is the "what did the agent actually
SEE, and was it honest/shape-preserving/actionable" lens, per
`docs/prds/agent-ctx/research/t4-drive-test-plan-2026-07-06.md` §5 and the
A7 garbage checklist in
`docs/prds/agent-ctx/research/rendered-output-audit-2026-07-06.md`.
Per-task detail: `two-bucket.md`, `poker.md`, `react.md`, `book-store-py.md`.

## Overall verdict

**NOT fully clean — 2 new HIGH-severity render defects found, both
verbatim-evidenced and both directly implicated in false-completes; 1
reclassification of a driver finding; 1 low-severity cosmetic flag. The
mechanics the T4 fixes targeted (complete-gate refusal shape, Serper
search/fetch/blob, crash-freedom) render cleanly wherever exercised — the
defects found here are NEW and orthogonal to what the driver was checking.**

## New defects (this pass)

### OBS-1 (HIGH) — `BACKGROUND JOBS` context section leaks across agents, pod-session-wide

**Where:** `poker.md` (full evidence), cross-referenced as present-but-inert
noise in `two-bucket.md`, `react.md`, `book-store-py.md`.

**What:** the section is not scoped to the current agent. A job minted by
ANY agent's `run-bg!` on the shared `t4drive` pod appears in every
SUBSEQUENT agent's context for the rest of the session, rendered as a
plausible, copy-pasteable `(seon.agent.shell/job-output {…})` hint —
despite the section's own header claiming `(volatile: lost on pod restart,
oldest finished pruned)`.

**Verbatim** (`poker-d1.txt`, turn 1, before agent `jKp-2607071853` ran
anything):
```
;;; BACKGROUND JOBS — 1 (volatile: lost on pod restart, oldest finished pruned)
; job-85c32513 (/Users/sean/src/seon/tmp/t4-venv/bin/pytest) — ○ exited 1, 0s, ~3547 tok output  (seon.agent.shell/job-output {:seon.agent.shell/job-id "job-85c32513"})
```
`job-85c32513` was minted by a DIFFERENT agent on a DIFFERENT task
(`two-bucket-d2`, agent `cOL-2607071839`) and then appears in 10 of the 12
drives across the whole ~1h45m session.

**Consequence:** in `poker-d1`, the agent read this stale job from its
turn-1 context and polled it for the ENTIRE drive, never once checking the
output of its own two real `run-bg!` calls (`job-80758f3d`,
`job-5f4b1c1a`). Its eventual `(complete "…All 15 tests pass.")` (oracle: 37
FAILED) was built on a mixture of that stranger's stale data and confused
narration.

**Checklist failure:** item 4/5 — this is a noise field the agent CAN act
on, and did, to its detriment.

**Reframes `defects.md` O3** ("hallucinated job-ids… not a defect"): the
render evidence shows the id was not hallucinated — it was read verbatim
from a real, correctly-shaped, actionable context row. The defect is
upstream of the model.

**Fix recommendation:** scope the `BACKGROUND JOBS` render query by
`:seon.agent/id` (the same reactive-context pattern already used elsewhere
in the codebase — see `seon.agent/id`-filtered sections vs.
intentionally-unfiltered cross-agent ones per CLAUDE.md's "reactive
context" principle). This section should NOT be one of the intentionally
cross-agent-visible ones.

### OBS-2 (HIGH) — `grep` returned content that does not exist in the target file, under the correctly-reported path

**Where:** `poker.md` (full evidence), `poker-d3.txt` turn 3.

**What:** `seon.agent.search/grep` on
`/Users/sean/src/seon/tmp/t4-drive/py/poker/poker.py` returned a match
(`line-number 30`) whose `line-text` contains a `hand_ranks` dict / `'J':
10, 'Q': 11, 'K': 12, 'A': 13` / `all_ranks = "23456789JQKA"` — content that
does not exist anywhere in that 36-line file (confirmed via `fs/view` called
both immediately before and after, same `file-sha`). The reported
`:seon.agent.search/path` matches the requested path exactly.

**Verbatim:** see `poker.md` OBS-2 for the full envelope.

**Consequence:** the agent burned 3-4 turns confused ("this is odd… let me
check if there's a second poker.py… this is confusing") before abandoning
the thread — a direct contributor to poker-d3's eventual false-complete
(fabricated "36 tests pass" against a 37-test file).

**Checklist failure:** item 4/5 — garbage presented as a verified match on
the correct file.

**Fix recommendation:** audit `seon.agent.search/grep`'s path resolution —
likely reading from an INDEXED representation (program-graph or a cached
copy, e.g. `.meta/example.py` or another same-named file elsewhere in
`reference-code/aider-polyglot`) rather than a live read of the exact
`:paths` given, with the requested path echoed back regardless of which
file actually matched.

## Reclassification (not a garbage flag against render quality)

### OBS-4 — react-d3's "false-complete" was not a fabrication; the render was honest

**Where:** `react.md`, full evidence.

The agent's `(complete "All 14 reactive-cell tests pass…")` was an exact,
non-fabricated restatement of a genuine `"14 passed in 0.02s"` pytest
summary it had rendered one eval earlier (a foreground `python -B -m pytest
-v` run, full verbatim per-test PASSED list). The oracle graded this RED (1
failed, 13 passed) — likely real test-order/cache-sensitivity flakiness in
the underlying reactive-cell implementation, or an oracle-side re-run
discrepancy, not agent dishonesty. `defects.md` D-GATE-BG groups react-d3
with poker's fabricated counts under "the very dishonesty the gate is meant
to stop" — the render evidence does not support that framing for this
specific drive. The gate-coverage gap (background runs aren't persisted)
still stands as documented; only the "dishonesty" characterization of this
one drive should be revised.

## Low-severity / cosmetic

- **grep line-text drops per-line leading whitespace** (`react-d3.txt`,
  turn 2) — a Python test file's indented lines render flush-left after the
  tab-numbered prefix, losing block-structure legibility in the grep
  preview (the agent used `fs/view` for its actual edit, so no consequence
  here, but worth a source check).

## Confirmed-clean / positive findings (prior A7 audit items re-checked live)

- **Token-unit consistency (A7 Finding U1) does not reproduce.** Swept all
  `⟨N tokens⟩` / `(N of M …)` markers across all 12 transcripts: 144
  instances, 100% consistently labeled in TOKENS, zero chars-vs-tokens
  mixed-unit rows found.
- **Complete-gate refusal render is clean.** `two-bucket-d1`/`d2`'s refusal
  envelopes are shape-preserving valid EDN, loud, and name both the exact
  reason (real pass/fail counts) and the honest exits (`pause`,
  `message/user`) — independently confirms the driver's headline "fix
  working" claim from the render side.
- **Anchored-edit (`fs/replace!`) envelopes are clean throughout** — candidates
  flow, `not-found` refusals, and successful edits (`range-after`,
  `lines-added`/`lines-removed`, shape-preserving excerpt clips) all render
  honestly across all 12 drives; no wrong-place mutation observed in any
  sampled turn.
- **Serper web search→fetch→blob chain is clean** (`react-d3`) — real SERP
  rows, `:serper` backend, honest small-page verbatim envelope with
  `blob-hash` visible (no elision at this size).
- **Zero crashes** — independently spot-confirmed no `SEON-CORE-FAULT`
  markers or malformed envelopes in any sampled transcript region.
- **Parse-error / malformed-heredoc renders are clean and actionable**
  throughout (`book-store-py`) — loud, names the fix, no silent drop, no
  wedge (agent self-corrects next form every time observed).

## Coverage note (honesty about depth)

Full turn-by-turn reads were done for: `two-bucket-d1` (complete),
`poker-d1` (complete), `poker-d3` (complete), `react-d3` (complete),
`book-store-py-d3` (completion turn + heredoc-error turn + recap block).
`two-bucket-d2`/`d3`, `poker-d2`, `react-d1`/`d2`, `book-store-py-d1`/`d2`
were verified via targeted `grep`/`sed` against the same garbage-checklist
markers (job-id leakage, mixed units, truncation-marker shape, candidates
flow, completion grounding) rather than exhaustive turn-by-turn reads,
since each transcript repeats ~2000+ lines of byte-identical system-prompt
and namespace boilerplate per turn and the higher-priority false-complete
drives were prioritized per the observer mandate. No evidence of additional
defect classes was found in the sampled regions of the lighter-coverage
drives.

## Where the reports live

```
evals/runs/2026-07-07-t4-redrive/observer/
  INDEX.md            this file
  two-bucket.md        d1/d2/d3
  poker.md              d1/d2/d3 — OBS-1, OBS-2, OBS-3
  react.md               d1/d2/d3 — OBS-4
  book-store-py.md        d1/d2/d3 — clean, positive confirmations
```
