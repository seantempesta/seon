---
type: research
status: completed
tags: [research, agent, eval]
---

# Observer report — react (d1/d2/d3)

Full turn-by-turn read of `transcripts/react-d3.txt` (the false-complete
drive, and the only one of the three that exercised the web
search→fetch→blob probe — highest priority). `react-d1`/`react-d2` (both
`:turn-limit`, no completion attempt) verified by tool histogram + targeted
grep for the garbage-checklist markers.

## react-d3 (agent OGU-2607071908, 5 turns, outcome RED false-complete)

| turn | tools-used | criteria-hit | garbage-flags | verbatim-sample-if-flagged |
|---|---|---|---|---|
| 1 | (contract only) | n/a | none | — |
| 2 | web/search, web/fetch, fs/read-file, search/grep | **O5 confirmed CLEAN**: `web/search` → real `:seon.agent.web/backend :serper`, 3 real SERP rows with url/rank/title/snippet, honest `:hint`; `web/fetch` → full 14-key envelope renders VERBATIM at this size (958-ish chars, under the 1500 store-cap), `blob-hash` visible and present, `:status 200`, `:total-tokens 7421`, `:preview-tokens 2000` — no elision even though the fetch fits in the "at-risk" W1 class the prior A7 audit flagged (small page here, so W1 doesn't trigger) | **minor: grep line-text loses per-line indentation** — see below | `"line-text": "12\t\n13\t\n14\tclass ReactTest(unittest.TestCase):\n15\tdef test_input_cells_have_a_value(self):\n16\tinput = InputCell(10)\n17\tself.assertEqual(input.value, 10)\n18\t"` — real file has 4/8-space Python indentation before `def`/`input =`/`self.assert…`; the grep render shows all lines flush against the tab-numbered prefix, stripping the leading whitespace that a Python reader needs to see block structure. Low severity (the agent used `fs/view`, not this grep output, for its actual edit) but worth a source check — either grep strips leading spaces or only tab-stops them oddly. |
| 3 | fs/view, fs/replace! (single clean edit, InputCell/ComputeCell rewrite) | replace! criterion met: `ok? true`, `:range-after [1 61]`, `:lines-added 61`/`:lines-removed 15`, excerpt shape-preserving (clips at 463 tokens with the loud marker, valid EDN up to the cut) | none | — |
| 4 | shell/run-bg! → job-89ddafa4 | run-bg! criterion met | none | — |
| 5 | shell/job-status, shell/job-output (**correctly targets its own job-89ddafa4, not a stale one**), shell/run (foreground diagnostic ×2), plan/done!, message/user, complete | job-status/job-output clean, honest, full pytest traceback rendered verbatim (4 failed/10 passed with real `AssertionError`/`IndexError` bodies — no clipping needed, well under cap); foreground diagnostic `shell/run -B -m pytest -v` genuinely printed `14 passed in 0.02s` | **OBS-4** (see below) | see below |

### OBS-4 — the "false-complete" here was NOT a fabrication; the render was
honest and the agent's claim matched what it actually saw (MEDIUM,
reclassification — not a garbage flag against render quality, but a
correction to how the outcome should be read)

Sequence, all verbatim from the render (turn 5):

1. `run-bg!` pytest → exited 1, **"react_test.py FFF..........F [100%] … 4
   failed, 10 passed"** (real traceback, honestly rendered).
2. Agent diagnoses "stale `.pyc`", runs a **foreground** sanity check:
   `python -c "from react import InputCell; i = InputCell(10);
   print(i.value)"` → `"10\n"` (genuinely works).
3. Agent runs **foreground** `python -B -m pytest react_test.py -v` →
   renders the FULL verbatim pytest summary, all 14 test names individually
   listed `PASSED`, ending `"============================== 14 passed in
   0.02s =============================="`.
4. Agent calls `(complete "All 14 reactive-cell tests pass. …")` — this is
   an EXACT, non-fabricated restatement of the runtime output it had just
   seen one eval ago.

The oracle graded this RED (1 failed, 13 passed) — but the agent's own
in-transcript evidence shows a genuine 14/14 GREEN run on the same file,
same test command, one turn before completing. This is either real
pytest/`.pyc`-cache-sensitivity in the underlying implementation (order- or
cache-dependent flakiness in the callback/dependency-propagation logic — a
product bug, not a render bug) or an oracle-side re-run discrepancy; either
way, **the render the agent saw was honest and its claim was grounded in a
real, just-observed green result** — this is categorically different from
poker-d1/d3's fabricated pass counts (15/36 claimed against a 37-test file
the agent never actually saw pass). `defects.md`'s D-GATE-BG groups
react-d3 with poker's "fabricated counts… the very dishonesty the gate is
meant to stop" — the render evidence does not support lumping react-d3 into
that framing. It IS still true the complete-gate should have required a
FRESH run right before `(complete …)` (gate coverage gap stands as
documented), but "dishonesty" is the wrong word for this specific drive.
Recommend the driver's writeup note this distinction if D-GATE-BG is cited
again.

## react-d1 (agent RXu-2607071900, outcome RED — turn-limit, no search)

Tool histogram: `fs/read-file` ×85(dup across turns), `fs/replace!` ×41,
`fs/view` ×40, `fs/write-file` ×84, `shell/run`/`run-bg!`/`job-status`/
`job-output` heavily used (31/49/25/57 respectively, cumulative-transcript
counts). Per README, this drive burned its budget on file reads/writes and
never reached `web/search` — consistent with model pacing, not a tool
defect. Spot-checked several `fs/replace!` and `shell/job-output` blocks via
`grep`/`sed`: envelopes shape-preserving, loud truncation markers present at
the expected offsets, no mixed-unit rows found, no stale cross-agent job-id
usage detected in the sampled turns (this drive minted and consumed its own
job-ids consistently in the sampled windows). No new garbage flags found in
the sampled turns; a full turn-by-turn pass was not completed for this
drive given its size (~9900 lines of transcript-only content) relative to
the higher-priority false-complete drives.

## react-d2 (agent fhi-2607071904, outcome RED — turn-limit)

Tool histogram: `fs/read-file` ×78, `fs/replace!` ×102, `fs/view` ×20,
`shell/job-output`/`job-status`/`run-bg!` (29/24/26). Same pattern as
react-d1 — no `web/search` reached. Spot-checked completion-adjacent turns
and several `fs/replace!` ambiguous-anchor retries: candidates flow present
and honest, no garbage flags found in sampled turns. Not fully read
turn-by-turn (lower priority — no completion attempt, no false-complete
risk to cross-check).

## Verdict for this task

**One confirmed render-quality observation (OBS-4, reclassification, not a
defect) and one minor cosmetic flag (grep indentation stripping).** The O5
Serper fix is genuinely clean end-to-end (search → fetch → blob, honest
envelopes, no `::results []`). No instance of OBS-1 (job leak) was ACTED ON
in this task's drives (react-d3 correctly used its own job-id throughout),
though the leaked jobs list was still visible as inert noise (see
`poker.md` OBS-1 — `job-85c32513` and poker's own job-ids appear in
react-d3's `BACKGROUND JOBS` section too, confirmed via grep count). The
main actionable item from this task is the D-GATE-BG mischaracterization
(OBS-4) — worth a note back to the driver, not a tooling fix.
