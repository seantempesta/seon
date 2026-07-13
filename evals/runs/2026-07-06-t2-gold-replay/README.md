---
type: research
status: completed
tags: [research, agent]
---

# T2 — gold-patch replay harness (falsification test for A2's cascade)

Replays the SWE-bench Verified **frozen dev slice** (10 instances,
`evals/runs/2026-07-05-slice4-dev-pass/dev-ids.txt`) gold-patch hunks through
the PURE anchored-edit cascade `seon.agent.fs.match/decide` — the exact matcher
the pod's `fs/replace!` / `fs/insert!` verbs use — and scores every hunk against
a SEPARATE `git apply` oracle.

**Hard gate (spec §T2): WRONG = 0.** A WRONG is a hunk the cascade *applied* but
to content that disagrees with the git-apply post-image — a wrong-place
mutation. **Result: WRONG = 0. The gate passes.**

## TL;DR

- **15 gold hunks across all 10 dev-slice instances, 0 skipped.** 100 % land as
  **stage-1 exact** applies; **0 WRONG**; the line-oracle final content equals
  `git apply` for **every** file (0 cross-check failures).
- Gold hunks carry full unified-diff context, so their anchors are **unique** —
  the near-window and normalization rescue stages are never *needed* here (they
  are exercised by `match.cljc`'s own unit suite, not by clean gold hunks). This
  test proves the **safety** property (never mutate the wrong place), not the
  rescue coverage.
- The **refusal guarantee** is exercised directly by a single-line-anchor probe
  (pass 4): **8 hunks whose chosen anchor is whole-line-ambiguous → 8 / 8
  correctly REFUSED, 0 guessed.** The cascade never mutates at a guessed
  location even when the anchor recurs (e.g. `return` occurs **40×** in the
  pylint file; `        )` occurs 9× in flask — both refused).
- **Ergonomics:** zero over-refusals on the real gold hunks (all 15 applied
  cleanly). No suspiciously-high refusal rate on valid, well-anchored edits.

## Metrics

| metric | value |
|---|---|
| instances (ran / total) | 10 / 10 |
| gold hunks scored (n) | **15** |
| exact (stage 1) | 15 (**100.0 %**) |
| near-rescue (stage 2) | 0 (0.0 %) |
| norm-rescue (stage 3) | 0 (0.0 %) |
| refused | 0 (0.0 %) |
| **WRONG (hard gate)** | **0** |
| oracle cross-check failures | 0 |

Hunk shape: 8 pure-insertion hunks (context + additions, no deletions), 7 hunks
containing deletions. Per-instance class breakdown: every instance is 100 %
`exact` (see `per-instance.edn` / `detail.json`).

## The four passes (per hunk)

1. **Real anchor** — `find` = context + deleted lines exactly as the pre-image;
   `replace` = context + added lines; `near` = the true running region.
   Multi-hunk files apply top-to-bottom, recomputing content between hunks; the
   offset advances only on hunks that actually anchored. → the metrics table
   above. **0 WRONG.**
2. **Same full-context find, NO near window** — does a full-context anchor stay
   unique without the window? **multi-match hunks: 0** (every gold anchor is
   unique on its own), so nothing to disambiguate — near-window is redundant for
   clean gold hunks.
3. **Minimal anchor = deleted lines only, no near** — 7 hunks have deletions;
   **0 ambiguous, 0 guessed** (the contiguous deleted blocks are unique).
4. **Single-line anchor, no near** — the most-ambiguous single line in each hunk
   (a legal but under-specified anchor an agent might pick). Ambiguity measured
   by **whole-line equality** (what stage-3 line normalization resolves on).
   **8 whole-line-ambiguous anchors → 8 / 8 refused, 0 guessed.** Examples
   (all correctly refused):

   | instance | single-line anchor | whole-line occurrences |
   |---|---|---|
   | pylint-dev__pylint-4604 | `            return` | 40 |
   | pallets__flask-5014 | `        )` | 9 |
   | sympy__sympy-15875 | `                return` | 7 |
   | mwaskom__seaborn-3187 | `    else:` | 3 |
   | django__django-11299 | `            if isinstance(child, Node):` | 2 |
   | pytest-dev__pytest-7490 | `    if not item.config.option.runxfail:` | 2 |

## Cascade-behaviour observation (not a defect)

`sphinx-doc__sphinx-8269` `sphinx/builders/linkcheck.py`: the intended line
167 —
`                    response = requests.get(req_url, stream=True, config=self.app.config,`
(20-space indent) — also appears as an **offset substring** inside line 183
(24-space indent: the same call one nesting level deeper). A naive
*substring* matcher counts 2 occurrences and would be forced to refuse or
guess. The cascade's stage-3 normalization is **whole-line-based**, so it sees
exactly **one** whole-line match and correctly applies at line 167. This is a
real robustness property of the line-anchored design: line semantics
disambiguates substring collisions that would trip a character-substring
matcher. (Verified live: `decide` on the raw pre-image returns
`action :apply, stage :normalized, ranges [[167 167]]`.)

## Honest limitations

- **Rescue stages (2–4 of the cascade) are not exercised by gold hunks.** Clean
  full-context gold patches on Unix-LF repos never trigger the near-window,
  CRLF/trailing-whitespace normalization, or ambiguity-refusal paths *for the
  real anchor*. This harness therefore validates the **no-wrong-place** invariant
  and (via the synthetic single-line probe) the **refusal** invariant; it does
  NOT measure how well the rescue stages recover degraded anchors. That coverage
  lives in `match.cljc`'s unit suite. A future variant could degrade gold
  anchors (strip context, inject CRLF) to exercise rescue — out of scope for the
  WRONG=0 gate.
- Passes 2 and 3 come up empty (0 ambiguous) precisely because gold anchors are
  well-formed — reported as-is rather than engineered to produce activity.

## Retired harness

This one-off JVM replay harness was retired with the paused JVM application.
Its recorded artifacts remain as dated evidence; the active implementation is
covered behaviorally by `seon.agent.fs.match-test` through the canonical CLJS
test runner. The deleted harness depended on the removed `:test` alias and was
not a valid operator or CI path.

## Artifacts

- `summary.txt` — the metrics block above (verbatim harness output).
- `detail.json` — one record per hunk: class, `wrong?`, decision stage/action,
  the four-pass fields, `find`/`replace`, `cascade-new` vs `oracle-content`.
- `per-instance.edn` — hunk/file counts per instance.
- Retained behavioral gate: `test/seon/agent/fs/match_test.cljc`. Cascade under
  test: `src/seon/agent/fs/match.cljc` (`decide`).
