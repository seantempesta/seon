---
type: evidence
status: 3 commits landed (be3226eaa, 48d550c22, 4fbf072b5); focused tests await root
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
