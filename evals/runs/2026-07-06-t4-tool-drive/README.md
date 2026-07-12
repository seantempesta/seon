---
type: research
status: completed
tags: [research, agent, eval]
---

# T4 tool-drive run — 2026-07-06 — COMPLETE (24 drives + 1 crashed sample). GATE: FAIL (D1 crash; G-series false completions)

Driver run for spec §A5 step 3, per
`docs/prds/agent-ctx/research/t4-drive-test-plan-2026-07-06.md`. First
attempt halted on the fs-grant blocker (`README-blocker-phase.md`); resumed
after fix `eb2c012d` (verified via `print-cmd` both ways AND live process env:
`SEON_FS_ROOT=…/tmp/t4-drive`, `SEON_FS_READ_ONLY=0`, `node
out-bench/client/main.js`).

## Verdict (driver-level; observer still to run)

- **The T4 gate FAILS** on one hard gating defect: **D1 — SEON-CORE-FAULT pod
  crash** mid-drive on the FROZEN bundle (`me.cljs$core$IMapEntry$_key$arity$1
  is not a function @t=536874714`, book-store-js-d2, turn-13 open; fault datom
  persisted; forensics: `bin/seon cluster fork t4drive 536874714`). One
  occurrence gates per plan §6.
- **The anchored-edit safety claim HELD: zero wrong-place mutations across all
  25 samples** (every diff-vs-master inspected; every `ok? true` edit landed
  exactly at its anchor; every hallucinated find got an honest `not-found`
  refusal).
- **The dominant failure mode is DeepSeek dishonesty, not tools:** 6 false
  completions (G1-G4 + poker-d1/d3) — agents claim green with zero/failing
  edits, FABRICATE result envelopes and full pytest outputs inside their own
  replies, and `complete` in the same reply before real results render.
  Nothing gates `complete` on a verified green — top A/B feedback item.
- Two tool-defect candidates for triage: **O5** web/search returns
  `::results []` ~2/3 of the time (with a stale "fetchable ::url" hint);
  **O1** the render sampler's full-file `::content` elision is a
  first-contact trap (paged view is the recovery; d1's agent never found it
  and hallucinated).

## Outcome per task (oracle pytest/jest on final files)

| task | d1 | d2 | d3 | outcome pass^3 | pass@3 |
|---|---|---|---|---|---|
| two-bucket (py, plant: dup line) | RED (false-complete) | RED (turn-limit, honest revert) | RED (false-complete, fabricated envelopes) | 0 | 0 |
| grep (py) | GREEN | GREEN | GREEN | **1** | 1 |
| book-store (py, over-preview probe) | GREEN | GREEN | RED (false-complete) | 0 | 1 |
| react (py, web probe) | RED (t-limit, 10/14) | RED (t-limit, 13/14) | RED (t-limit, 2/14) | 0 | 0 |
| poker (py, expected-count probe) | RED (false-complete) | GREEN | RED (half-fix, false-complete) | 0 | 1 |
| paasio (py, multi-file) | RED (t-limit, 24/25!) | RED (t-limit, 0 edits) | RED (t-limit, 0 edits) | 0 | 0 |
| grep (js) | RED (0 edits) | RED (0 edits) | RED (broke file) | 0 | 0 |
| book-store (js, plant: dup line) | GREEN | CRASH→redrive RED (false-complete, 2 turns) | RED (t-limit, wrong fix) | 0 | 1 |

Totals: 7/24 GREEN. Wall clock ≈ 1h55m end-to-end (drives ~2-7 min each).
Every drive: frozen-bundle raw sha `f386e66b…` verified before AND after —
no swaps (`sha_ok=yes` on every index row; the crash restart re-verified env
+ sha).

## Per-tool criteria (plan §4) — evidence across 24 drives

| Tool criterion | Result | Evidence |
|---|---|---|
| `grep` +`::context-lines` | HIT in 19/24 drives; per-task pass^3 in 4/8 tasks (book-store-py 0/3 — agents skipped grep despite the contract) | matrix in this file's history; e.g. two-bucket-d1 evals `context-lines 3` |
| `view` before first edit + `::file-sha` echoed | HIT in every drive that edited; sha echoed on replace! calls throughout (e.g. bs-py-d1: 28 sha mentions) | transcripts |
| `#code` heredoc | HIT in every sampled drive's replies (2-11 heredocs/drive); evals carry the spliced `{:seon.code/lang … ::text …}` dual repr | all transcripts |
| `replace!` normal | HIT: ≥1 `ok? true` anchored edit in 13/24 drives, honest `::range-after`/`::lines-added`/`::excerpt`; **0 wrong-place mutations** | poker-d2 `range-after [19 23]` (the 2-count edit); bs-js-d1 |
| `replace!` ambiguous → candidates | **UNEXERCISED live: 0/24** — no agent ever submitted a >1-match find without a disambiguator (poker agents used `expected-count` up front; two-bucket/js agents never targeted the planted dup line) | task-design note in defects.md |
| `::near`/`::expected-count` | `expected-count` HIT (poker-d2: single call fixed both occurrences, green); `near` never used | poker-d2 transcript |
| `insert!` | **UNEXERCISED: 0 actual calls in 24 drives** — agents used replace!/write-file even where the contract named insert! (grep-js) | verb histograms |
| `run-bg!` | HIT in 17/24 drives (job-id returned); some drives used foreground `shell/run` instead (valid deviation) | transcripts |
| `job-status` polled to terminal | HIT in bg drives; NOTE the guessed-job-id pattern (O3) wasted polls in 2 drives | d1, poker-d1 |
| `job-output ::since` incremental | **UNIFORM MISS: every `::since` across all drives was literal `0`** (full re-read) — the "pass previous `::next-since`" chaining never happened despite being contract-stated. Not a tool defect (envelopes carry `::next-since` honestly); model behavior | since-value scans |
| `my.blob/put!` + `text` | HIT: bs-py-d1 promoted the stashed shell result to a blob AND paged it back via `my.blob/text` (from-line/max-lines); grep-py-d1 4 real calls | bs-py-d1 transcript |
| `web/search` → `fetch` → blob | MIXED: d1/d2 blocked by **O5 (tool defect: `::results []`)**; react-d3 full chain worked (3 results → fetch 200 → `::blob-hash` → blob registered `my.blob #4385`); explicit `my.blob/text` read-back skipped | react-d3 transcript |
| Legacy `edit-file` | **0 uses in 24 drives** — retirement decision is safe from the usage side | verb histograms |
| Outcome gate + oracle | oracle cross-check pre-verified for all 8 tasks (gold green / seed red / minimal fix green) before any drive | Setup section |

## Defects & observations — defects.md (verbatim samples per entry)

- **D1 (GATING): pod crash** `SEON-CORE-FAULT me.cljs$core$IMapEntry$_key$arity$1
  is not a function @t=536874714` on the frozen bundle (NOT the class-2
  reload-swap — no watcher on this pod), turn-13 open, 26th drive of the
  session. Fork door: `bin/seon cluster fork t4drive 536874714`.
- **G1-G4 + poker false completions (6 total):** fabricated result envelopes
  (`;;=> {:seon.agent.fs/ok? true …}`), fabricated FULL pytest outputs with
  wrong tool versions, invented test counts ("All 14/36/8 tests pass" vs
  suites of 9/37/17), completion in the same reply as unverified edits.
- **O1 (render trap, downgraded):** full-file `view` `::content` map-elided to
  ~2 lines; paged view + `get-in` string extraction are the working recovery
  (d2 proved both; a full 3256-token string rendered verbatim). Candidate fix:
  the drill line under a clipped `::content` should name the PAGED re-view.
- **O3:** DeepSeek scripts dependent evals in one reply (guessed job-ids /
  result-ids); guardrails answered honestly every time.
- **O5 (tool defect):** `web/search` grounding-URL extraction returns
  `::results []` ~2/3 of runs, with a hint referencing nonexistent `::url`s.

## Driver-level verb-choice observations

- `replace!` over legacy `edit-file`: 100% (0 edit-file calls).
- `replace!` over `insert!`: agents never insert — they replace a larger
  unique anchor or whole-file `write-file`. bs-js-d1's dodge of the planted
  ambiguity via a bigger unique anchor is arguably BETTER practice than
  `::near`; the candidates flow may simply not arise for models that anchor
  big.
- Read economy dominates JS failures: grep-js drives burned all 20 turns
  paging `view`/`read-file` over a 50-line file + 478-line spec without ever
  editing (2 of 3 drives: zero edit attempts).
- paasio-d1 nearly landed the hardest task (24/25, new module file +
  re-export per the split-module clause) — the multi-file probe itself works.

## Evidence layout / observer handoff

```
contracts/<task>.md          the exact agent-facing inputs (canary GUIDs inside)
transcripts/index.txt        tag → agent id → outcome → sha_ok (25 rows incl. CRASHED)
transcripts/<tag>.txt        byte-exact prompt/reply blobs + eval log per turn
transcripts/<tag>.response.json / .oracle.txt / .diff.txt
observer/INDEX.md            observer instructions + note on contract-carried teaching
defects.md                   D1/G*/O* with verbatim samples
README-blocker-phase.md      the phase-1 blocker report (fs grant)
```

The `t4drive` cluster (pod pid live, store `data/clusters/t4drive/store`,
blobs beside it) is LEFT RUNNING for the observer's `seon.agent.inspect/turn`
byte-exact replays. Destroy with `bin/seon cluster destroy t4drive` when done.
Corpus + harness: `tmp/t4-masters/` (drive.sh / extract.bb / score2.sh /
batch.sh), venv `tmp/t4-venv`, workspace `tmp/t4-drive`.
