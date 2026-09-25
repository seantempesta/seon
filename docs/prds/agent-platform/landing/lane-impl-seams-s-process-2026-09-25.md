---
type: evidence
status: landed (be3226eaa, 48d550c22, 4fbf072b5); reds on adopted 6ab6f5fb classified and the caused-by-timing one fixed
---
# Lane impl-seams-s-process-opus: lost-error seams cut S-process (2026-09-25)

Design: `docs/research/agent-platform/lost-error-seams-design-2026-09-25.md` (`3b3173a05`), cut S-process:
A07, G05, then C06 and G12. `seon.cluster.process/run-process!` stays the one subprocess owner. Its two callers'
private copies in `issue.clj` and `test/cache.clj` now go through it.

## Broken or unproved first

- **Not run by this lane (LOAD RULE):** `seon.cluster.subprocess-test`, `seon.issue-test`, and the cache suites.
  Root runs them (commands below). Every changed body was run first as a scratch-namespace copy in default's JVM
  (evidence below).
- **Not adopted into default.** Hook publication reports `:on`, but after the edits `seon.cluster.process/join-output`
  was not resolvable in default's JVM, and the old `await-subprocess-value` still was. Root adopts the committed paths.
- **Two new undeclared refusal keys:** `:seon.operator.subprocess/survivors` (a vector of `(pid, start-instant)`
  identities) and `:seon.operator.subprocess/unjoined` (a vector of `:stdout`/`:stderr`). They join the refusal's
  existing undeclared `pid`, `start-instant` and `reaped?`. `resources/seon/schemas/seon.operator.subprocess.edn` was
  not granted to this lane, so it does not declare them. The refusal still validates, because
  `:seon.operator.state/deadline-exceeded-error` is an open map. The schema owner should declare all five keys.
- **The interrupt path is not covered.** If the caller's thread is interrupted inside the `.waitFor` loop,
  `run-process!` throws before it terminates the child or joins the captures (unchanged from HEAD). Closing that
  path means handling a set interrupt flag inside `terminate-subprocess!`'s `onExit` gets. That was more than this
  cut's budget. Recorded as a risk.
- **An orphaned grandchild is still not signalled.** With `sh -c "sleep 3 & echo x"` and a 300 ms deadline, the root
  exits and `sleep` is reparented away from the tree, so `terminate-subprocess!` cannot see it. The refusal is now
  honest about this: `reaped? true` together with `unjoined [:stdout :stderr]`. At HEAD it reported only `reaped? true`.
- **C06 changes behavior for roots with no git history.** `seon.issue/notes` on a directory where git fails
  (for example exit 128 outside any repository) now refuses. At HEAD it returned notes with no `opened` dates. A
  `git archive` snapshot nested under the checkout's `tmp/` still resolves the enclosing repository, so git exits 0.
  A snapshot outside any repository now refuses.
- **The nonzero-exit refusal does not carry stderr.** The owner rule is that output is referenced by blob digest,
  never copied, and `note-opened` and the cache readers have no store. So the refusal names `argv`, `exit` and the
  root, which is enough to rerun the command. It does not name git's message.
- **The `:seon.issue/git-unbounded-error` name now misfits.** That schema (`seon.issue.edn:130`) now covers the
  nonzero-exit refusal, which it validates, as probed. A timeout is now the process owner's own
  `deadline-exceeded-error`. The name should be revisited by the schema owner.
- **Out of scope, in the dependency:** babashka.process's `:in` copier catches its exception and only prints it
  (`reference-code/babashka-process/src/babashka/process.cljc:416-421`). `run-process!` passes `:in` for string
  input. No issue was filed. The input path belongs to the A08-class owner in `shell/jvm.clj`, not to this cut.

## Reds on adopted HEAD 6ab6f5fb (root, 23:30Z), classified

- **issue-test, 110 pass / 24 fail / 2 error (`verify-reds.out.d/023.txt`): PRE-EXISTING, not caused by this cut.**
  At `6ab6ae2a` (`verify-6ab6ae2a-results.txt.d/057.txt`) the same members were red: 107 pass / 25 fail / 2 error.
  After normalising `fn--N` names, the red member set is identical except that `issue-worker-opening-links-its-issue`
  is now green. The reds are real-note drift (renamed or archived notes, missing `:seon.issue/keys`), a fixture that
  writes the symbol `seon.db/*conn*` as `:seon.issue/id`, and a stale definition digest for
  `seon.cluster/require-coherent-program!`. The new `issue-history-refuses-a-failed-git-read` passed (12 executed
  against 11). No note under test lacks git history, so the nonzero-exit refusal did not fire.
- **subprocess-test, 15 pass / 1 fail (`017.txt`): PRE-EXISTING, and a test timing defect exposing an owner race.**
  `foreign-process-deadline-reaps-a-late-descendant` was red on every recorded run today (`verify-1955` 009,
  `verify-fresh` 028). The JDK's macOS `WatchService` is `sun.nio.fs.PollingWatchService`; a create event took
  **2009 ms** to arrive. So the test wrote its spawn signal at about the 2000 ms deadline. The probe:

  | Spawn signal at | Child alive after refusal | Refusal |
  |---|---|---|
  | 1990 ms | yes | `reaped? true` |
  | 2000–2010 ms | no child spawned | `reaped? true` |

  A fork racing termination escapes `terminate-subprocess!`'s one tree walk. It is filed as
  `docs/seon/issues/a-descendant-forked-during-termination-escapes-and-reads-as-reaped.md`, for an owner ruling. The
  test now polls for its files every 10 ms under the same backstop instead of watching. The watcher, its `with-open`
  and two imports are deleted. Proof: with the signal at about 70 ms (the file poll), the installed owner reaps the
  child and returns in 3024 ms with `reaped? true`.

## Freeze-then-kill (root ruling, after the reds)

`terminate-subprocess!` now works in this order:
1. `freeze-tree!`: SIGSTOP (OS `kill -STOP`) every live member, walking `ProcessHandle.descendants` again until a walk
   finds no new pid;
2. TERM every frozen member;
3. `kill -CONT` them;
4. wait up to `grace-ms`;
5. freeze again;
6. SIGKILL the frozen set;
7. await exit, returning survivors by `(pid, start-instant)`.

Two helpers were added: `signal-pids!`, which is bounded, discards output, and refuses naming the kill's pid if kill
itself hangs; and `freeze-tree!`. The single walk captured before TERM is deleted.

**Deviation from the ruling's wording.** The ruling's steps were freeze and then SIGKILL, with no TERM. I kept the TERM
grace, placed between the two freezes, because `seon.cluster.process-test` (not this lane's file) asserts TERM
semantics. It checks exit 143, the TERM-trap marker, and a grandchild forced only after grace. `shell/jvm.clj` also
passes a termination-grace config. Dropping TERM means deleting the trap handling that the grace provides. That is
the owner's call, not this lane's.

Probes: the scratch owner in default's JVM, with processes the probe started.
- The late spawner, sending its signal at 70, 1990, 1995, 2000, 2005 and 2010 ms against a 2000 ms deadline: no live
  child in any case.
- A TERM-ignoring fork loop (150 members, one every 5 ms, 300 ms deadline): the old owner leaked a member in **2 of
  6** runs while reporting `reaped? true`; the new owner leaked in **0 of 6**.
- The `process-test` shapes under the new owner:

  | Case | Exit | Other results | Time |
  |---|---|---|---|
  | grandchild ignoring TERM | 143 | `left []` | 649 ms, inside 500–2500 |
  | well-behaved tree | 143 | marker "term\n", `left []` | 315 ms |
  | zombie | — | returned as the survivor, still alive, others dead | — |

- The new regression tests, run through `clojure.test/test-var` in scratch copies of the test namespace:

  | Test | New owner | Old owner |
  |---|---|---|
  | `a-child-forked-as-termination-begins-never-escapes` (490/500/505/510 ms against a 500 ms deadline) | 8 pass, 3458 ms | passes too (probabilistic) |
  | `a-tree-forking-through-termination-leaves-no-live-member` (TERM-ignoring root forking every 10 ms) | 4 pass, 1423 ms and 1430 ms | **fails deterministically**: about 70 live members, with `reaped? true` |
  | `foreign-process-deadline-reaps-a-late-descendant` | 4 pass, 3404 ms | — |
  | `foreign-process-joins-its-output-observer-on-every-exit` | 5 pass, 1936 ms | — |

- The spawner now waits for a plain file, not a fifo. A fifo writer whose reader was killed blocks in an
  uninterruptible `open`. **My earlier fifo probes leaked 3 idle threads in default** (`clojure-agent-send-off-pool-37`,
  `-55` and `-56`, in `FileOutputStream.open0` on deleted fifos). They use no CPU and cannot be unblocked; they go at
  the next restart.

Size: `process.clj` +46/−17, `subprocess_test.clj` +70/−10.

## What changed

| Instances | File | Change |
|---|---|---|
| A07, G05 | `src/seon/cluster/process.clj` | `join-output` (replaces `await-subprocess-value`) reads each capture task's outcome against one deadline: its value, the Throwable it ended with, or the timeout sentinel. The bound-exceeded path now covers every case: a process timeout, a capture timeout, and a failed capture such as a throwing `observe-output!`. It terminates, then joins **every** capture again for one grace period, then refuses. The refusal names any process `survivors`, names `unjoined` captures, makes the first capture failure its cause, and suppresses the rest. It carries no output. |
| C06, G12 | `src/seon/issue.clj` | `note-opened` calls `run-process!`. A nonzero exit refuses (history root plus exit) instead of returning nil. The private deref, `destroy-tree` and expiry refusal are deleted; expiry is now the owner's refusal. |
| G12 | `src/seon/test/cache.clj` | One `git-output` helper through `run-process!` replaces the two copied process, deref and destroy-tree blocks in `input-paths` and `gitlink-digests`. The refusal names argv and exit instead of copying the whole process record (`:proc`, output). |

Deleted copies:
- `await-subprocess-value` (−7);
- the completed-only output and error-output joins (−9);
- the issue history spawn, deref, destroy-tree and expiry refusal (−13);
- the two test-cache spawn, deref, destroy-tree blocks (−23, replaced by a 14-line helper);
- `babashka.process` is no longer required by `issue.clj` or `test/cache.clj`.

## Size (`git diff --numstat`)

| File | + | − | net |
|---|---|---|---|
| src/seon/cluster/process.clj | 40 | 33 | +7 |
| src/seon/issue.clj | 25 | 25 | 0 |
| src/seon/test/cache.clj | 25 | 26 | −1 |
| **source** | 90 | 84 | **+6** (design estimated −11/+14) |
| test/seon/cluster/subprocess_test.clj | 22 | 0 | +22 |
| test/seon/issue_test.clj | 13 | 0 | +13 |

## Evidence (default pid 39484; scratch copies in `tmp/impl-seams-s-process/`; namespaces removed afterwards)

- **A07 reproduced at HEAD.** `sh -c "echo a; sleep 5"` with a 300 ms deadline and an observer that sleeps 1500 ms:
  the refusal returned at 319 ms with the observer still running (its state was nil at return and `:observer-done`
  later).
- **New owner** (`process_new.clj`):

  | Case | Time | Outcome |
  |---|---|---|
  | observer 600 ms, deadline 300 | 610 ms | refusal `:process-exit`, `reaped? true`; observer done at return |
  | observer 2500 ms | 1310 ms | refusal names `unjoined [:stdout]` |
  | throwing observer, `echo a; echo b` | 6 ms | refusal cause "observer refused a" |
  | `echo out; echo err >&2; exit 3` | 4 ms | `{exit 3, output "out\n", error-output "err\n"}` |
  | `sleep 3 & echo x`, deadline 300 | 1306 ms | `:stdout`, `reaped? true`, `unjoined [:stdout :stderr]` |

- **Issue history.** The new `note-opened` equals HEAD's on the checkout (2034 names), 267 ms against 250 ms. On a
  non-repository directory it refuses with `exit 128`. That refusal validates against
  `:seon.issue/git-unbounded-error` under `seon.schema/projection-from-database` of default's db.
- **Test cache.** `input-paths` gives 4016 paths, equal to HEAD, 33 ms against 29 ms. `gitlink-digests` gives 31
  pins, equal to HEAD, 17 ms against 17 ms. On a non-repository directory the refusal is `{argv, exit 128, ::root}`.
- `bb … -e "(require 'seon.cluster.process) (run-process! git ls-files)"` returns exit 0, so the owner still loads
  under babashka.
- clj-kondo reports 0 errors on all five files. The warnings are the same as HEAD's (the kondo hook flags the joined
  `future` at `process.clj:270`, and older test sites).

## Root runs

- `bin/test-check default --ns seon.cluster.subprocess-test` (the new row takes about 2 s, inside the 5 s bound;
  `foreign-process-deadline-covers-output-capture` now takes about 1.1 s because it joins after termination)
- `bin/test-check default --test seon.issue-test/issue-history-refuses-a-failed-git-read`
- `bin/test-check default --ns seon.issue-test`
- `bin/test-check default --ns seon.test-cache-test`
- `bin/test-check default --ns seon.test.gitlink-digest-test`
- `bin/test-check default --ns seon.cluster.publication-inputs-test`
