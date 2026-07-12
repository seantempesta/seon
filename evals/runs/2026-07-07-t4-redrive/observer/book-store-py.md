---
type: research
status: completed
tags: [research, agent, eval]
---

# Observer report — book-store-py (d1/d2/d3)

All three drives GREEN and honest (per README/oracle). Lower priority for
false-complete cross-checking; verified completion-turn grounding on all
three plus spot-checked the anchored-edit / `#code` heredoc / shell paths
for garbage.

## Completion-turn grounding — all three honest and render-clean

`book-store-py-d3` (agent cGr-2607071918), turn 13, verbatim:

```
(seon.agent.shell/job-status {:seon.agent.shell/job-id "job-2b720f20"})
; ⟹ {…, :seon.agent.testrun/result
       {:seon.agent.testrun/ok? true, :seon.agent.testrun/framework :pytest,
        :seon.agent.testrun/passed 20, :seon.agent.testrun/failed 0,
        :seon.agent.testrun/errors 0, :seon.agent.testrun/failures []},
     :seon.agent.shell/exit 0, …}
```

followed immediately by `(complete "All 20 tests pass. …")` — the agent's
own reply narrates "result/JzT-2607071920 shows 20 passed, 0 failed, exit
0" citing the REAL var, matching the `(complete …)` claim exactly. Same
pattern confirmed for `book-store-py-d1`/`d2` (both `(complete "…all 20
tests pass…")` immediately preceded by an honest `job-status` showing
`passed 20, failed 0`). No fabrication, no render gap — the gate-blindness
class (D-GATE-BG) never manifested here because these three agents happened
to complete right after seeing a genuine green (unlike poker-d1/d3's stale
data or react-d3's cache-flakiness case).

## "Values you just computed this run" recap block — clean, unit-consistent

All three drives render a per-turn recap list right before the loop-status
line, e.g. (`book-store-py-d3`, turn 13):

```
;   result/uXb-2607071920 ⟹ "_cheaper_than_two_groups_each_of_five_and_three(\n self,\n ):\n b…"⟨1952 tokens⟩ ; ‹partial view› — the COMPLETE value is result/uXb-260707…⟨clipped — result/uXb-2607071920 holds it whole⟩
```

Checked all `⟨N tokens⟩` / `(N of M …)` markers across the three
transcripts: **144 instances, all consistently labeled in TOKENS, zero
mixed-unit (chars-vs-tokens) rows found** — the prior A7 audit's Finding U1
("mixed units within one row") does not reproduce anywhere in this live
session. Positive confirmation the token-reporting convention held.

## Anchored-edit / `#code` heredoc path

Multiple malformed-heredoc self-corrections observed (agents mistyping the
sentinel or truncating a large heredoc mid-form), e.g.
`book-store-py-d3.txt` raw ~32483-32500:

```
; ⟹ ✗ READ ERROR — this form did not parse, so it DEFINED NOTHING.
; Unexpected EOF. at line 2, col 245: …
; This form was likely TRUNCATED because it was too large to emit — it ran
; past your output budget and ended mid-form, so it DEFINED NOTHING and
; sent NOTHING. Don't try to re-emit the whole thing: STORE the long
; content as data … For large LITERAL text, my.blob/put! it in ~2K-token
; chunks, then my.blob/concat! …
```

This is a clean, actionable parse-error render (checklist item 2/3: honest,
names the fix, no silent drop) — the agent self-corrected on the next form
and succeeded (`fs/replace!` with the same intent, `ok? true`,
`range-after`/`lines-added`/`lines-removed` present). No garbage flag.

## Cross-agent job leak (OBS-1, see `poker.md`) — present but inert

`job-85c32513` and other prior drives' job-ids (`job-80758f3d`,
`job-434278e6`, etc.) are visible in all three book-store drives'
`BACKGROUND JOBS` sections (confirmed via `grep -c` — 45/51/42 occurrences
respectively across the cumulative transcripts). None of the three
book-store agents ever polled a stale id — each correctly used the job-id
its own `run-bg!` call returned. Noise, not a defect trigger, in this task.

## Verdict for this task

**Clean.** No NEW render defects found; the one cross-cutting issue
(OBS-1, cross-agent job leak) is present as inert noise but was never acted
on. Token-unit consistency (U1 from the prior audit) holds throughout. All
three completions are grounded in a genuine, honestly-rendered green test
run one eval before `(complete …)`.
