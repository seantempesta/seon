# Baseline of regressions and open reds, 2026-10-25 (main at 888a728e2)

Read-only compilation from notes/merge-log.md, notes/perf-2026-10-24.md, notes/root-handoff-2026-10-25.md, docs/plan.md, task entries, notes/research-2026-10-25/ (regressions.md is a snapshot at 3ffd30317, older than main), notes/dogfood-and-docs-2026-10-25.md. Nothing was run. "task:" = owning task name. Status: open / closed / unconfirmed.

## 1. Reds and flakes in tests and checks

| Item | Evidence | Task | Status |
|---|---|---|---|
| seon.agent-test/a-blocked-agent-ends-with-its-root errored once: seon.test-store/ask :port nil (root did not listen); passed alone; "unexplained, not reproduced" | merge-log 0ddd1141b | a-process-root-opens-the-shared-store-under-load (seon.test-store) | open (flake, cause unknown). The same task also covers a-blocked-agent-ends-on-its-socket-eof, red once under load (merge-log b432b3e8, 3 recurrences) |
| seon.root-test/main-follows-its-source-tree-across-restarts intermittent under parallel load | merge-log 2773b36a, ec914e3c (walk reported it) | seon.test-store.edn:6 | unconfirmed (never reproduced; not seen since) |
| seon.root-test/root-threads-are-what-root-declares {:gc-sweepers [0 1]}: machine-state dependent | merge-log 9bda779cd; fix c6605dd7a (seon.main/-main pins the sweeper pool at 0) | gc-sweepers-declared | closed, "did not reproduce" before or after the fix: unconfirmed under load |
| seon.agent-test/an-agents-test-run-is-its-proofs-and-what-its-change-reaches nil result under load (3 batches) | merge-log dcb524da, 7e869f9e; fixed by e8d9a575 test-store-once (31/31) | test-store-once | closed (not seen since) |
| seon.prove-test/a-session-prove-of-main-runs-in-a-prove-agent...: run key moves between two asks | task seon.proofs a-prove-runs-key-does-not-move-between-two-asks; test-only revert 3a3ef957f | a-prove-runs-key-does-not-move-between-two-asks | task still in tree, status unconfirmed. The follow-up "an unread :proofs key" from prove-key (merge-log fd5fe513f) is also open |
| seon.source-test/a-fixed-change-costs-the-same-at-any-program-size borderline (n^0.60 vs 0.60 allowed, bytes 1.04x), green alone | merge-log 3651da4f | fixed-change-cost / ticks-are-exact | closed by Chez pin fa6a4c73 (205b0dc41), unconfirmed in full runs |
| Cost proofs red under load: vector-cost a-search-reads-each-row-once, program-cost a-probed-entity-is-sought-not-scanned | research regressions.md #9; fixed 205b0dc41 | a-cost-proof-is-the-same-under-load | closed. 64 KB per collection on the stored index's read path remains, measured on the task; ticks differ 0.1-0.5% between a shard process and a fresh one (ticks-are-exact-2, cause not found): open |
| seon.program-cost-test: divergence and a-change-re-folds were red on main | merge-log b432b3e8; fixed by 333a3709 bounds | divergence-bound | closed |
| seon.print-test/the-text-stays-inside-its-budget red at one seed on several branches | merge-log a60054af (oldest entry) | none found | unconfirmed (not in later logs) |
| seon.reader-test/the-laws-hold-over-generated-replies seed reds (backtick/backslash) | merge-log 2773b36a, f5e87cdb (a696fdac) | the-reader-laws-hold-on-a-lone-backtick | closed |
| bin/check robust red in the main tree only (config/local.edn leak: lag 8997 ms, 2614 vars, 14 prove reds) | merge-log 35c8fcb2, 231845e4, 465e693d | check-explicit-config | closed |
| bin/check mcp-proof red (:both-recorded-outside false) | merge-log a2b0f7889 | jolt/mcp-check | closed |
| bin/check durable-read red (:main-runs-the-merged-f1 false) | merge-log fbb279900; was a hand-written steps file (a4fcc53f0). Separate: durable-read crashed alone with FileNotFoundException | durable-steps-seed; durable-read-runs-alone (branch parked, unmerged; "main has most of it") | closed for the first; open (parked) for the standalone crash |
| Merges reverted when red: health (1ef9f0e9), fork-wakes-on-exit + drivers-out3 (e9e73765, 60108008), page branch (8a9acf07: 35 reds), first-moves (ee9d5e61: root-test red), LPT shard dealing (55928d282); all re-merged except the LPT one | git log, merge-log | none | closed |
| proof-ends: seon.agent-test a-proof-run-ends-with-its-agent HANGS (fifo read); 2 seon.e2e reds | merge-log ac7d3fb2, fixed 35c8fcb2 | proof-ends-arity | closed |
| Standing root problems: seon.watch died ("references.md No such file"); stale-pin on docs/forks.md#jolt; 223 stale-sections (206 -> 132 -> 223 by pair) | merge-log ed0e3e1a1; dogfood note section 2 | a-vanished-file-is-a-fact (fixed, watcher closes on restart); a-section-goes-stale-when-what-it-cites-changes-meaning (waits on the owner) | watcher closed; stale sections open (noise, not drift); stale-pin unconfirmed |
| Agent branch's own reds rerun at its judge | root-handoff "Filed follow-ups" | none named | open, unconfirmed |

## 2. Silently skipped coverage

| Item | Evidence | Task | Status |
|---|---|---|---|
| bin/prove left out 662 of 686 tests (29 keys planned) after agent-conversation: a quoted my.agent/prove in seon.render.ui/did made every test reach a process | merge-log 1ea5bc781; AGENTS §3 rule f08826aa9; fix 7850f1196 | a-test-reaches-what-it-calls | closed (proof seon.render-test/a-render-reaches-no-process) |
| Root cause remains: every test reaches every render through the contract registry and a schema's :seon.render/* properties. Left out: 247 at 76d9e6421/0595c749d, 239 after 330f01988 (the judge merge deleted a process-reaching path; not a fix), 240 now (+1 process test from a-section-reads-at-each-level). 247 pure tests still left out (e.g. seon.render-test/nothing-is-shown-twice, seon.context-test/each-thing-is-said-once); any render change moves every test's digest, so all rerun | task seon.program.edn:84 | a-contract-check-does-not-reach-every-render (proof seon.program-test/only-a-test-that-starts-a-process-is-left-out) | open, not started. Fix: reach graph's schema rows stop taking :seon.render/* values as neighbours (seon.program/form-row) |
| The merge's "prove OK" leaves no reusable greens at main's digest (150.7 s cold re-run on an unchanged tree); stale-binary refusals re-run | perf note 2026-10-24; merge-log 59bc42ec, ca82ac5a, 3ffd30317, 52b374f02 | prove-key (fd5fe513f) | the repeat-ask part closed (13.7 s -> 0.48 s); stale-binary re-runs after a docs commit during a verification recur (330f01988, 52b374f02): open |
| Process tests are left out of bin/prove (run with bin/test); fit-tasks' five red proofs kept off main (6cff75578) | AGENTS §3; merge-log | none | by design |
| A proof that never ran reads :no-assertions (a test run records only how many tests ran, not which finished) | dogfood note gap 4; task text on seon.context | a-proof-that-never-ran / unrun-proof | open |
| `(my.agent/prove)` answers :ran 0 :deferred with no reason (process test class unexplained) | dogfood note gap 4 | the deferred-reasons task (filed in 867ed0eb7) | open |
| Full run (bin/test --all, bin/check 100) daily; the last full green is not recorded in the log | AGENTS §3 | none | unconfirmed |
| Cost proof for a function capped by a budget: rule silent | dogfood note section 1 gap 6 | none | open (minor) |

## 3. Cost and perf regressions (numbers)

| Item | Numbers | Evidence | Task | Status |
|---|---|---|---|---|
| Context render per turn | 115M ticks, 512 MB vs 14.3M, 59 MB earlier; fixed to 1.3M ticks (from 92M) | perf note; merge-log 11d34aacd | context-render-observes-what-changed | closed. Residue an-observed-retraction-leaves-no-residue: open |
| No-op bin/build | 31 s -> 0.6-1.4 s | merge-log 3d41eb35a | build-and-plan-do-nothing-twice | closed |
| One eval commit in an agent | 440-449k ticks, 3.19-3.24 MB per commit (eval itself 137-151k, 0.5 MB) | task a-one-eval-commit-costs-what-it-writes; handoff | same | open (root ruled "D stays") |
| Render walk per row | ~6 KB a row; page-value pulls every eval of an agent (pull of one eval 87k ticks, 506 KB) | handoff; costs.md | a-walk-counts-its-rows-flat | open |
| Root memory after boot | 1,031-1,575 MB (+8-20% on 765-1,460 MB); target is 16 GB machine | perf note | none | open, "watch it"; unconfirmed whether still so |
| d/db-with of the import | 1,515M ticks, 3.66 GB, 4.7 s CPU | perf note | none | open (not a regression) |
| import-tx, reach-index | 447M ticks 2.47 GB; reach-index one walk 247M ticks, 1.16 GB | perf note, corrected | none | open (not regressions) |
| ts/test-db | 128M ticks, 626 MB per make | perf note | none | open |
| Root boot to LISTENING | 14.7-38.1 s (kept store, load 7.5-9.7); 21.9 s new store; phases unrecorded | perf note | none | unconfirmed (to measure quiet) |
| Test selection of one namespace costs 2.4 s, more than the whole tree | perf note | none | open |
| Agent first render and first turn are cold (warm! skips d/with-reads, comp-entry, request path) | branch-review follow-up | warm! task (filed from review) | open |
| Prompt cache: history cut drops the oldest record each turn past budget; boundary moves every turn when history <= 1 step | tasks seon.context (review follow-ups) | those two tasks | open |
| seon.repl/record-details stores any plain value whole with an O(value) walk, unbounded | review follow-up | task seon.repl | open |
| Prompt tokens | 3,329 -> 405 per request; 7-turn run 45.6k -> 25.1k (improvement, for reference) | merge-log 76d9e6421 | clean-path | closed |

## 4. Behaviour and UX regressions agents or the owner hit

| Item | Evidence | Task | Status |
|---|---|---|---|
| A design task from a request cannot register proofs: my.task/update! takes only plan and instructions; every deftest is a :stray-red with no :do (3 of 3 dogfood agents) | dogfood gap 1 | filed 2026-10-25 (update! takes proofs, ruled) | open (blocks design runs) |
| An eval answers only its last form's value; prompt says each form's (3 of 3) | dogfood gap 2 | ruled: each form's value under one handle | open |
| One-line request has nothing to start from (~10 evals each, my.agent/grep ~30 unranked names, 350 ms, 240 MB a call) | dogfood gap 3 | related-sections; grep task | open (related-sections gated) |
| (my.task/status) answers :seon.turn/not-observed for an outside agent; seon_done answers only {:judging} | dogfood gap 4 | status and done-verdict tasks | open |
| (my.agent/help "references") answered nil | dogfood gap 5 | a-section-reads-at-each-level | closed (67c065320, help never nil) |
| 54 sections without a lead | dogfood note | 54 leads written, :leadless-section problem | closed |
| Outside agent's :context is the old block, not what a model is sent; in-ns persists and breaks doc; second attach to a held task answers :seon.server/defect wrapping :seon.root/refused | dogfood gap 6; perf note "Also seen" | outside-context task; none for the attach one | open |
| A late-admitted agent (5th run, max-running 4) got :seon.render/no-location for its whole run; browser pages read a lagging main | task a-late-agent-gets-its-screen; handoff | same | the screen part fixed (c79a6394f); the browser-page follow-up open |
| transport error docstring says "retried after a pause" but the run ends :error | seon.model task | seon.model task | open |
| a partial branch in the kept program store spreads to every tree built from it | task seon.program a-kept-trees-program-is-its-trees-import | same | open (defect, filed not fixed) |
| await-reply! reads other keys' replies as unknown ops; printed-run keys :parts by bare id | review follow-ups | two tasks | open |
| Embeddings: an agent's own new function or plan has no vector until merged | task seon.vector | seon.vector task | open |
| bin/update skipped its jolt/merges steps all session (looked for .worktrees/merges, tree is ../next-merges) | merge-log 8a9acf07 | fixed by vocab (2773b36a) | closed |
| Stale derived stores (target/check-store, target/runs/test-store) made roots unopenable after config key change | merge-log 35c8fcb2, 87c036a7 | a-kept-store-is-keyed-by-its-schema | closed |
| A long keyword prints a bare `prefix...+N` with no unit; a map whose first entry fits only without the cut reserve answers insufficient-floor-budget | tasks seon.print | two tasks | open (minor) |

## 5. Fixed today (2026-10-25), closed

- bin/prove hollow plan (662 tests left out): 7850f1196 / a3a88f2aa.
- Context render 8x: 11d34aacd. No-op build 30 s: 3d41eb35a. bin/check mcp-proof: a2b0f7889. Durable-read harness red: a4fcc53f0. Cost proofs under load: 205b0dc41. Prove repeat ask: fd5fe513f.
- Dead merge path deleted and one prove path (fbb279900, f83b736e1); a platform error closes when its cause is gone (0595c749d); a red at its digest is reused (52b374f02); the judge asks the test service (330f01988).
- Agent-conversation review fixes F1-F9 (1ea5bc781); help never nil, leads, docs at levels (67c065320); dogfood gaps filed as 11 tasks (51adce708).
- Earlier days, for reference: gc sweepers declared, test-store-once, check-explicit-config, rename-run, reader backtick.
