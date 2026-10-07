# Batch B review (opus, lane review-batch-b)

Commits 1758624f4, 90fd4919e, c362de9d7 (bin/lane), 8e42e0889, 9a33f54f4 (seon.agent.loop producers, help text,
context examples). Astra's review (review-batch-b-review-astra-batch-b.md) verified finding by finding below. Default
runs a8e3bde5b: `seon.agent.loop` is **not installed** (`(find-ns 'seon.agent.loop)` → nil in SCI), so the producers
were exercised from a disposable scratch JVM namespace (`scratch.review-b-loop`, loaded from the file, removed after),
unarmed. Armed execution and publication are unverified. Shell findings were reproduced in scratch Git repos under
/bin/bash 3.2.57 (`#!/usr/bin/env bash` resolves to it).

## Crossed commits (649829332, bd5e0e8c9): cause confirmed, not bin/lane

bd5e0e8c9 carries lane heap-retained's five paths under lane error-frames' message. The message is byte-equal to the
session scratchpad file `scratchpad/msg.txt` (diff: one trailing blank line). Lanes share one scratchpad directory, and
six lane transcripts (no-print-hash, printers-errors, heap-retained, error-frames, printers-rest, commit-validation)
build `-m "$(cat scratchpad/msg.txt)"` from that one file. Timeline from the transcripts: heap-retained wrote msg.txt at
11:38:31 JST ("Branch writers and Flow graphs..."), commit-validation rewrote it at 11:40:09 and committed 649829332 at
11:44 with whatever it then held; error-frames wrote msg.txt at 12:06:12 (commit 7a6878374 at 12:10); heap-retained
re-ran its commit at 12:20:45 reading msg.txt, so bd5e0e8c9 (12:26:19) got error-frames' text. bin/lane committed the
message it was handed; its post-commit assertion compares with that same message, so it could not see the crossing.

Guard added (`message_owned`): bin/lane prints `== lane L commits: <first line>`, refuses a first line beginning with
another live lane's name (also with dashes as spaces, e.g. "error frames: ..." from heap-retained), and refuses a
subject equal to one among the last 200 commits (bd5e0e8c9's subject equals 7a6878374's). Lanes must keep messages in
lane-named files. 649829332's subject was not yet used and names no lane; only the shared-file practice prevents that
class.

## Findings, ranked (all FIXED unless marked)

1. **P1 bin/lane: `docs/../src/x.clj` commits source with lint, check and claims skipped** (Astra 1, reproduced:
   committed `src/a.clj`, then exit 3 after HEAD moved). Same class: `src/*` or `:(top)` pathspecs could sweep other
   lanes' files, since claims compare strings and git expands globs. Fix: `valid_path` refuses empty, `.`/`..`
   components, leading `/` or `:`, `* ? [ \` and `//` in claim and commit. Proof: both spellings refused, exit 2.
2. **P1 bin/lane: committed bytes need not be the proven candidate** (Astra 2). Fix: the proven tree is passed to the
   locked commit, which rebuilds the paths' candidate over current HEAD and refuses unless `git diff --quiet proven now
   -- PATHS`. Proof: a check stand-in editing the file mid-check → refused "changed after the check", nothing
   committed; stable edit commits. Not fixed, by design: other lanes' commits of disjoint paths between check and
   commit are accepted (the landed tree = new HEAD + proven paths was not itself linted). `bin/seon check` still
   snapshots the working tree itself (operator held by printers-errors); an edit during the check that is reverted
   before the lock would pass. Root: have `check!` take the candidate tree.
3. **P1 bin/lane: lock released while the commit child runs** (Astra 3). perl's flock fd was close-on-exec. Fix:
   `fcntl($lock, F_SETFD, 0)`, so `_commit-locked` (and git under it) inherits and holds the lock until it exits.
   Unverified: scratch message removal by an outer shell whose perl was SIGKILLed.
4. **P2 bin/lane: interrupted commits leaked scratch dirs and background lint** (reproduced: TERM left
   `lane-commit.*` and clj-kondo running; a failing `wait` under errexit ended the EXIT trap before `rm`). Fix: HUP/INT/
   TERM traps exit through the EXIT trap; the lint runs in its own process group (`set -m`) and the trap kills the group;
   trap commands cannot fail. Proof: TERM → 143, HUP → 129, nothing left. INT is ignored by backgrounded scripts, so
   it was not exercisable in the harness (a terminal Ctrl-C reaches the whole group).
5. **P2 bin/lane: a claimed directory's new file was linted/checked but not committed** (Astra 5, reproduced: exit 0,
   `src/d/y.clj` left untracked). Fix: under the lock every untracked file under the requested paths
   (`git ls-files --others --exclude-standard`) is added, matching the candidate's `git add -A`. Proof: both committed.
6. **P2 bin/lane: the unlocked early staged-index check races another lane's in-lock `git add`** (found in the 3×15
   run: 21 spurious "is staged in the shared index" refusals). Fix: the check runs only under the lock.
   Proof: 3 lanes × 15 concurrent commits (real clj-kondo, stub check): 45/45 landed, each with its own message and
   path, 0 crossed, 16 s, `git fsck` clean apart from dangling candidate trees.
7. **P1 producers: context unbounded** (Astra 4). `print/full` printed a 3,000-element value as 265,890 characters
   (default budget: 2,781); strings bypassed the printer; a `render-ai` error map would have been `str`-ed. Fix: no
   `print/full`; every value, sources included, goes through `render-ai` under its budget; a printer refusal is itself
   rendered as a value. Task 135d92ea1308 now ~4,956 tokens (was ~7,520), task-context 25 ms, text 43 ms (unarmed).
   **Owner call:** a long source now prints as an escaped string literal cut at the budget (`…+7799`), exactly as the
   agent's own REPL shows `source-fn`; the agent reruns the form or `my.program/source` for the rest. Multi-line
   strings printing as escaped literals is ugly output owned by the value printer (general improvement, not this file).
8. **P2 producers: related-task and error requeries evaluate symbols as functions** (Astra 6; verified in SCI: the
   unquoted form refuses with an `AFunction` for `:seon.task/must-read`, the quoted one returns 9 tasks). Fix: forms
   quote the symbol vector. Regression `related-task-requeries-quote-the-symbols-they-match`.
9. **P2 producers: namespace subjects broke helper contracts** (Astra 7; task 6a6e13871530's subject is `my.edit`).
   Fix: qualified subjects are functions, simple ones get a `(dir ns)` unit. Verified on that task.
10. **P2 producers: an unknown handle produced nil units.** Fix: refuses `:unknown-task` (`:seon.task/refused-error`).
    Regression `an-unknown-handle-refuses-by-name`. Regression `context-text-is-bounded-by-the-value-printer`.
11. **P3 producers: empty docstring summaries/examples printed blank indented lines** (`native-call-refusal`,
    `supplied-context`, `usage-form`). Fix: `(seq ...)` guards.
12. **NOT FIXED, root: examples stale** (Astra 8). system-message.md lacks the new help lines (generated against
    a8e3bde5b's help). RESET NEEDED, then regenerate with the README's form before the owner reads them.
13. **NOT FIXED, doc owners: non-runnable documented examples in the system message** (Astra 9, agreed:
    `example-url`, `proposal` unbound; fixture namespace `my.examples.nested-fixture` and `*-generator` helpers listed).
    The producer copies the owners' docs by design; fix at the docstrings/toolkit declaration (task already filed in
    seon.cluster.instruction.edn for fixtures). Rejected: a producer-side catalog.

## Verified, no defect

- Help text: in SCI on a disposable agent, `clojure.repl/apropos "steps"`, `(my.program/source 'my.task/steps)`,
  `(doc ...)`, `(dir my.task)`, `seon.turn/turns-left` (100), `(my.agent/done)` with no argument, `clojure.test/testing`
  and `deftest` resolve and answer. The Markdown line is accurate: a prose line is evaluated symbol by symbol
  (`I`, `will`, `check` unresolved; backticks read as syntax-quote). Note for root: CLAUDE.md's "prose becomes
  comments" stage is not implemented. `my.test/check` was not run (central test rule); its README run is author evidence.
- Producers read one database value; no writes or connections. Recorded failures are replaced per run
  (runner.clj failure-replacement-tx), so the failures unit is current. All 2,845 stored `:seon.fn/spec` strings read as
  EDN on default; an agent-authored spec with a regex would throw from `contract-keys` (unverified, low).
- Archive lint really lints HEAD + the paths (deletions via `git add -A`; a rename needs both paths named, then the
  archive and commit agree). Docs-only skip: docs/ is not in `seon.test.cache/input-roots`. No worktrees. bash 3.2 OK.

## Tests and proof

`seon.agent.loop-test` (5 tests) via `bin/lane commit --test seon.agent.loop-test`. Shell regressions were run in
scratch repos (not committed as tests): `..`/glob refusal, edit-during-check refusal, directory new file, TERM/HUP
cleanup, 3×15 concurrency.
