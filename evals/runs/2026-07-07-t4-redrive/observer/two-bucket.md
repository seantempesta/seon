---
type: research
status: completed
tags: [research, agent, eval]
---

# Observer report — two-bucket (d1/d2/d3)

Dedicated render-quality read of the byte-exact transcripts
(`transcripts/two-bucket-d{1,2,3}.txt`), independent of the driver's
outcome/gate analysis in `../README.md` / `../defects.md`. Method: full read
of the transcript-only (eval-history) slice per turn for d1 (gate-refusal
drive); targeted verbatim verification (via `grep`/`sed` on the raw
byte-exact `.txt`) for d2/d3 against the same garbage checklist, since d2/d3
repeat the same tool surface (`fs/replace!` × 20, `fs/view`, `shell/run`) as
d1 with no new tool shapes.

## two-bucket-d1 (agent IRQ-2607071835, 20 turns, outcome RED — gate refused)

| turn | tools-used | criteria-hit | garbage-flags | verbatim-sample-if-flagged |
|---|---|---|---|---|
| 1 | grep | shape-preserving, honest, handle ok | none | — |
| 2-8 | fs/view, fs/replace! (several `not-found` retries), grep | shape-preserving; anchored-edit refusals are clean errors-as-values | none | `{:seon.agent.fs/ok? false, :seon.error/message "text not found — re-read the file and copy the EXACT text…", :seon.error/data {…/candidates []}}` — honest, actionable |
| 9-13 | shell/run (foreground, 5×), fs/replace! | `shell/run` result renders `:seon.agent.testrun/result` inline (parsed pass/fail counts) alongside `:out`/`:err` — clean, no elision at this size | none | — |
| 14-18 | shell/run ×18 total (per README histogram), fs/replace! | test-run counts stayed consistent turn-over-turn (`5 failed, 4 passed` → `2 failed, 7 passed`); no stale-job artifact (two-bucket never uses `run-bg!`, so the cross-agent job-leak class — see poker/react below — never surfaces here) | none | — |
| 19 | `(complete "All 8 tests pass…")` | **complete-gate fires** — refused verbatim, shape-preserving, honest, cites the real last-seen counts | none (this is the fix WORKING, confirmed render-side) | `{:seon.db/ok? false, :seon.db/error {:seon.error/message "complete refused — your latest test run is RED (5 failed, 4 passed). Run the tests and SEE a green result render before completing…"}}` |
| 20 | turn-limit | n/a | none | — |

**Independent confirmation of the driver's headline claim:** the refusal
envelope (turn 19, eval `dVB-2607071839`/analog) is byte-exact, shape-valid
EDN, states the loud reason, and is fully actionable (`pause` /
`message/user` named as the honest exits). No render defect enabled or
obscured this refusal — the fix is genuinely visible and legible in what the
agent saw.

## two-bucket-d2 (agent cOL-2607071839, outcome RED — gate refused)

Tool histogram (own forms only, deduped from the cumulative transcript):
`fs/replace!` ×20, `fs/view` ×4, `plan/done!` ×2, `shell/run` (foreground)
×10, plus `run-bg!` ×4 per the README table. **This is the drive that FIRST
minted `job-85c32513`** (its own `run-bg!` call, `; result-edn:
{…job-id "job-85c32513", state :running…}` at raw line 6987) — the job that
then leaks into every later drive's context for the rest of the session (see
`poker.md`/`react.md`/`book-store-py.md` — Finding OBS-1). Within
two-bucket-d2 itself this is harmless (it's the drive's own job); flagged
here only as the origin point.

| turn-class | tools-used | criteria-hit | garbage-flags | verbatim-sample-if-flagged |
|---|---|---|---|---|
| early | grep, fs/view, fs/replace! (several ambiguous-anchor retries) | anchored-edit candidates flow present and honest | none | — |
| mid | shell/run ×10 foreground | testrun counts render inline and consistently (`7 passed, 2 failed`) | none | — |
| completion attempt | `(complete …)` | gate refused — same shape as d1 | none | `"complete refused — your latest test run is RED (2 failed, 7 passed)…"` (README verbatim, render-confirmed via the same envelope shape as d1) |
| end | turn-limit | n/a | none | — |

## two-bucket-d3 (agent XAQ-2607071847, outcome RED — turn-limit, no complete attempt)

Tool histogram: `fs/replace!` ×20 only (own forms) — this drive never
reached a test run before running out of turns (README: "0/0 closed
attempts, ran out of turns"). `job-85c32513` (and by this point also
`job-80758f3d`) already appear in this drive's `BACKGROUND JOBS` context
section from turn 1 (leak confirmed, see `poker.md` Finding OBS-1) even
though this agent never itself calls `run-bg!` — i.e. the leaked section is
pure noise here, never referenced or acted on by this particular agent (no
misleading consequence in this drive, unlike poker-d1/d3).

| turn-class | tools-used | criteria-hit | garbage-flags | verbatim-sample-if-flagged |
|---|---|---|---|---|
| all turns | fs/view, grep, fs/replace! (repeated ambiguous-anchor churn — never reached a run) | anchored-edit envelopes shape-preserving and honest throughout | **OBS-1 present but inert** — `BACKGROUND JOBS` section shows `job-85c32513`/`job-80758f3d` (two-bucket-d2's and poker-d1's jobs) from turn 1, agent never queries them | `; job-85c32513 (…/pytest) — ○ exited 1, 0s, ~3547 tok output (seon.agent.shell/job-output {:seon.agent.shell/job-id "job-85c32513"})` present at turn 1, before this agent ran anything |

## Verdict for this task

Render mechanics are clean and load-bearing-correct on the anchored-edit and
foreground-shell paths: candidates-flow, testrun counts, and the
complete-gate refusal all render honestly, shape-preserving, and
actionably — this is the class of drive where the T4 fixes visibly work.
The one cross-cutting defect touching this task is OBS-1 (cross-agent
`BACKGROUND JOBS` leak, detailed in `poker.md`), which is present but inert
here since two-bucket agents don't rely on backgrounded jobs for their test
loop.
