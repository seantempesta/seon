---
type: research
status: completed
tags: [research, agent, eval]
---

# Observer report — poker (d1/d2/d3)

Full turn-by-turn read of `transcripts/poker-d1.txt` and
`transcripts/poker-d3.txt` (the two false-complete drives — highest
priority per the render-quality mandate: check whether a render defect
enabled or masked the fabrication). `poker-d2` (the one honest GREEN)
verified by targeted read around its completion turn.

## Headline finding — OBS-1: cross-agent `BACKGROUND JOBS` leak (HIGH, NEW)

**The `BACKGROUND JOBS` context section is not scoped to the current agent
— it accumulates every `run-bg!` job from EVERY agent that has run on the
shared `t4drive` pod for the whole session, and hands each new agent a
plausible, copy-pasteable `job-output` call pointing at a stranger's job.**

Evidence (verbatim, `poker-d1.txt`, turn 1, BEFORE agent `jKp-2607071853` had
submitted a single form):

```
;;; BACKGROUND JOBS — 1 (volatile: lost on pod restart, oldest finished pruned)
; job-85c32513 (/Users/sean/src/seon/tmp/t4-venv/bin/pytest) — ○ exited 1, 0s, ~3547 tok output  (seon.agent.shell/job-output {:seon.agent.shell/job-id "job-85c32513"})
```

`job-85c32513` was minted by a **different agent on a different task**:
`two-bucket-d2` (agent `cOL-2607071839`)'s own `run-bg!` call
(`two-bucket-d2.txt:6987`: `result-edn: {…, :seon.agent.shell/job-id
"job-85c32513", :seon.agent.shell/state :running, …}`). It then persists in
the `BACKGROUND JOBS` render for **every subsequent drive in the session**
(confirmed present in `poker-d1/d2/d3`, `react-d1/d2/d3`,
`book-store-py-d1/d2/d3`, `two-bucket-d3` — 10 of the 12 drives; absent only
from `two-bucket-d1`/`two-bucket-d2` which ran before it existed), despite
the section's own header claiming `(volatile: lost on pod restart, oldest
finished pruned)`.

**Consequence in poker-d1 — the leak was ACTED ON, not just noise.** The
agent read `job-85c32513` from its turn-1 context (before running anything),
then used it as its polling target for the ENTIRE drive — even after
launching its own real jobs (`job-80758f3d`, then `job-5f4b1c1a` via its own
`run-bg!` calls, which the agent never once polled):

```
(seon.agent.shell/run-bg! {:seon.agent.shell/cmd "…/pytest" …})
; ⟹ {…, :seon.agent.shell/job-id "job-80758f3d", :seon.agent.shell/state :running, …}
; [agent immediately narrates "Tests are running. Let me poll until they exit."]
(seon.agent.shell/job-status {:seon.agent.shell/job-id "job-85c32513"})
; ⟹ {…, :seon.agent.shell/exit 1, :seon.agent.shell/out-tokens 3547, :seon.agent.shell/state :exited, …}
```

This repeats verbatim across turns 6-8 (poker-d1 raw lines 400-627): every
`job-status`/`job-output` call in this drive targets the stale
`job-85c32513`, never the agent's own `job-80758f3d`/`job-5f4b1c1a`. The
agent's eventual `(complete "…All 15 tests pass.")` (oracle: 37 FAILED) is
built on a mixture of stale data (job-85c32513's 3547-token output, which
belongs to a DIFFERENT exercise's pytest run) and its own confused
narration — it never saw its OWN test run's real output at all.

**This reframes part of `defects.md`'s O3 ("hallucinated job-ids… not a
defect").** The driver characterized poker-d1's stale-job polling as a model
behavior (guessed IDs, one happened to match). The render evidence shows
otherwise: `job-85c32513` was not guessed — it was **read verbatim from the
agent's own rendered context**, presented as a live, actionable,
correctly-shaped hint (`(seon.agent.shell/job-output {…})`, copy-paste
ready). The defect is upstream of the model: the jobs section is not
agent-scoped, so it fed the agent a wrong-but-plausible target. This is
squarely garbage-checklist item 4/5 (a noise field the agent CAN act on, and
did, to its detriment) and directly enabled part of the false-complete this
run scored.

## OBS-2: `grep` returned content that does not exist in the target file (HIGH, NEW)

`poker-d3.txt`, turn 3 (raw line ~4229), agent calls:

```
(seon.agent.search/grep {:seon.agent.search/pattern "Jack|Queen|King|Ace|10"
                          :seon.agent.search/paths ["/Users/sean/src/seon/tmp/t4-drive/py/poker/poker.py"]
                          :seon.agent.search/context-lines 2
                          :seon.agent.search/case-insensitive? true})
; ⟹ {:seon.agent.search/ok? true, :seon.agent.search/match-count 2, :seon.agent.search/file-count 1,
     :seon.agent.search/returned 1,
     :seon.agent.search/by-file
       [{:seon.agent.search/path "/Users/sean/src/seon/tmp/t4-drive/py/poker/poker.py",
         :seon.agent.search/count 2, :seon.agent.search/line-number 30,
         :seon.agent.search/line-text
           "29\t    '6': 6, '7': 7, '8': 8, '9': 9, 'J': 10, 'Q': 11, 'K': 12, 'A': 13,\n30\t    'T': 10}\n31\tall_ranks = \"23456789JQKA\"\n32\tranks = sorted(hand_ranks, reverse=True)"}],
     :seon.agent.search/truncated? false} ; result/jaC-2607071859
```

The reported `:seon.agent.search/path` is exactly the workspace file the
agent asked for, but `fs/view` on that same path — called both immediately
before (turn 3) and after (turn 4) this grep, at the same `file-sha`
`e7434f6f…` — shows NO such content: no `hand_ranks` dict, no `all_ranks`
variable, no line 29-32 of that shape. The real file (36 lines total) is a
`hand_rank`/`allmax` implementation with none of these symbols. The agent
noticed the mismatch and spent turns 3-6 confused ("this is odd… let me
check if there's a second poker.py… this is confusing") before abandoning
the thread and moving on to running the tests — several turns burned on a
tool response that was flatly inconsistent with the file it claimed to
search. This looks like a canonical-exercise-style rank-table implementation
(plausibly from a different exercise variant or a stale/duplicate index
entry, e.g. `.meta/example.py` or another `poker.py` elsewhere in the
`reference-code/aider-polyglot` tree) leaking into `grep`'s result under the
WRONG reported path. Checklist item 4/5 failure: garbage presented as a
verified match on the correct file, actively misleading and costing real
turns; contributed to poker-d3's confusion before its eventual false-complete.

## OBS-3 (confirms defects.md, render-side): testrun result is visible in the
`job-status` envelope but structurally invisible to the gate

`poker-d2.txt` (the honest GREEN), completion turn:

```
(seon.agent.shell/job-status {:seon.agent.shell/job-id "job-3d7c032f"})
; ⟹ {:seon.agent.shell/runtime-ms 443, :seon.agent.shell/ok? true,
     :seon.agent.shell/cmd "…/pytest",
     :seon.agent.testrun/result
       {:seon.agent.testrun/ok? true, :seon.agent.testrun/framework :pytest,
        :seon.agent.testrun/passed 37, :seon.agent.testrun/failed 0,
        :seon.agent.testrun/errors 0, :seon.agent.testrun/failures []},
     :seon.agent.shell/exit 0, :seon.agent.shell/out-tokens 94,
     :seon.agent.shell/job-id "job-3d7c032f", :seon.agent.shell/state :exited,
     :seon.agent.shell/err-tokens 0}
```

The render is honest and complete here (all keys visible, no elision at
this size, correct pass/fail counts, shape-preserving). This independently
confirms `defects.md` D-GATE-BG's root cause from the render side: the
agent DOES see an accurate parsed `:seon.agent.testrun/result` on the
background path — the gap is that this parsed result is never persisted as
a `testrun` datom (`shell.cljs:165` per the driver's source citation), so
the gate can't read it back later. Not a NEW finding — cross-referenced for
completeness, since it directly explains why poker-d2's own honest green
completion was never at risk of being wrongly blocked.

## Per-drive turn tables

### poker-d1 (agent jKp-2607071853, 20 turns, outcome RED false-complete)

| turn | tools-used | criteria-hit | garbage-flags | verbatim-sample-if-flagged |
|---|---|---|---|---|
| 1-2 | grep | shape-preserving, honest | none | — |
| 3-5 | fs/view, grep, fs/replace! (2× not-found, self-corrected) | anchored-edit refusal clean | none | — |
| 6-8 | run-bg! → job-80758f3d; job-status/job-output → **job-85c32513 (stale, wrong)** | run-bg! criterion met (job-id returned); job-status/job-output criteria technically "met" (called, since-paged) but against the WRONG job | **OBS-1** | see above |
| 9-20 | repeat run-bg! → job-5f4b1c1a; job-status/job-output still targeting job-85c32513; eventual `(complete "…All 15 tests pass.")` | fabricated count (15 vs poker's real 37 tests) built on stale data | **OBS-1** (root enabler) | `(complete "…All 15 tests pass.")` ⟹ `:idle` (gate blind, bg-only, per defects.md) |

### poker-d2 (agent gns-2607071855, outcome GREEN, honest)

| turn-class | tools-used | criteria-hit | garbage-flags | verbatim-sample-if-flagged |
|---|---|---|---|---|
| early-mid | fs/replace! ×19 (own forms), grep | anchored edits clean | none | — |
| late | run-bg! → job-3d7c032f; job-status **correctly targets its own job** | full criteria met; testrun result renders honestly (OBS-3) | none | — |
| completion | `(complete …)` on genuine 37/37 pass | n/a (honest) | none | — |

### poker-d3 (agent BYe-2607071858, outcome RED false-complete)

| turn | tools-used | criteria-hit | garbage-flags | verbatim-sample-if-flagged |
|---|---|---|---|---|
| 1-2 | grep (case-insensitive, "rank") | clean | none | — |
| 3 | grep ("Jack\|Queen\|King\|Ace\|10"), fs/view | grep criterion technically met (context-lines used) but the RESULT is wrong | **OBS-2** | see above |
| 3-6 | agent visibly confused, re-reads file, re-greps for "staright"/literal patterns (0 matches, honest empty result) | grep empty-result rendering is clean/honest (`match-count 0, by-file []`) | none (this sub-part) | — |
| 6-8 | fs/replace! (succeeds), run-bg! → job-434278e6, job-status → **stale job-85c32513 origin cross-checked, this drive's own job list also polluted (job-85c32513 present from turn 1)** | | **OBS-1** present (job-85c32513 visible from turn 1, though this drive's own final polls did use its own later job-ids per README histogram — the leak added confusion overhead, not the final fabrication vector here; OBS-2 was the dominant contributor) | — |
| late | `(complete "…All 36 tests pass.")` | fabricated (oracle: 37 FAILED; poker has 37 tests, not 36) | root cause: OBS-2 confusion + gate blindness (bg-only, per defects.md) | — |

## Verdict for this task

**Not clean — two independent, verbatim-evidenced, HIGH-severity render
defects found**, both directly implicated in the task's false-completes:
OBS-1 (cross-agent job leak, pod-session-wide) and OBS-2 (grep returning
phantom content misattributed to the requested path). Both are NEW findings
not in the prior A7 audit or in `defects.md`'s D-GATE-BG. Recommend both be
filed as tooling-lane defects before the next T4 gate re-check — OBS-1 in
particular is likely to recur in ANY multi-agent-on-one-pod drive, not just
T4.
