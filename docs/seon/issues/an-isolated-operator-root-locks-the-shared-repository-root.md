---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, boot, concurrency]
---

# An isolated `--root` operator command locks the shared repository root

## Problem

`--root` is the documented mechanism for a destructive drill or a second
deployment, and the guarantee AGENTS.md states for it is that "root-scoped
discovery makes the shared root unreachable by construction". Lifecycle
locking does not honour it. Every operator transition — in ANY root — takes
one exclusive lock on the SHARED repository's file, blocking with no timeout
and no message.

So N lanes, each with its own isolated operator root, serialize on one lock
they never asked for, and a lane whose command is queued behind a slow one
just hangs. There is nothing on stdout to say it is waiting, what it is
waiting for, or who holds it.

## Evidence

`script/seon/fresh_operator.clj:221-231`:

```clojure
(defn- with-operator-lock
  [_root transition]                                   ; the root is discarded
  (let [directory (operator.state/control-root (repository-root))
        path (fs/path directory "lifecycle.lock")]
    (fs/create-dirs directory)
    (with-open [file (RandomAccessFile. (str path) "rw")
                channel (.getChannel file)]
      (.lock channel)                                  ; blocking, no deadline
      (transition))))
```

The `root` argument is bound to `_root` and never read; the path comes from
`(repository-root)` unconditionally.

Observed live on 2026-08-08 at 03:30Z. `bin/seon --root tmp/schema-env-root
reset --force` produced no output and created nothing in its root for six
minutes. `lsof` on the babashka process showed a single open file:

```
bb  35945  sean  4u  REG  /Users/sean/src/seon/data/operator/lifecycle.lock
```

`lsof` on that path showed SIX operator commands queued on it, spanning at
least three different isolated roots plus the shared one, the oldest running
11m35s:

```
bb 33320   11:35      bb 34100   10:21      bb 35079   08:21
bb 35945   06:39      bb 37288   03:55      bb 39147   00:40
```

`sample` on the waiter confirmed it was parked in the blocking `.lock` call,
not doing work.

Two things make this worse than plain contention:

1. **It is silent.** `.lock` blocks forever with nothing printed. The sibling
   lock helper in `script/seon/dev/state.clj:40-62` does this correctly — it
   polls `try-lock` against a deadline and throws a named
   `"Timed out waiting for the Seon lifecycle lock."` So the repository
   already contains the honest version of this mechanism, and the operator
   path does not use it.
2. **It taxes every lane at once.** This is the velocity class the standing
   order names: a serialized 10-minute operator lock is a multiplier on every
   fix cycle, and it converts "boot your own scratch cluster, clusters are
   cheap" into a queue.

Related but distinct: this is the mechanism behind reports that clusters
"cannot boot tree-wide" during a busy session. Boot is not broken; it is
queued. `seon.cluster.cohost-boot-test` booted two real clusters at 03:17Z
in the same session, because the test harness's own root does not go through
this operator path.

## Owner

`script/seon/fresh_operator.clj` (`with-operator-lock`).

## Acceptance criteria

- The lifecycle lock path is derived from the operator root the command was
  given, so two isolated roots never contend and `--root` isolation holds for
  lifecycle transitions as the documentation already promises.
- Waiting for a lock is observable: a bounded wait that names the lock and the
  holder's process identity rather than a silent block, following
  `script/seon/dev/state.clj:40-62` rather than inventing a third form.
- A regression drives two concurrent operator commands in two different
  `--root` directories and asserts both complete without serializing.

## Resolution (2026-08-08)

The class named here is "one shared lock silently serializes work that the
`--root` contract says is isolated". It is now unrepresentable at the seam:
`with-operator-lock` cannot lock a root other than the one it was given,
because the only path it can build is
`seon.operator.state/root-lifecycle-lock-path` of the SELECTED root, and the
root argument is the sole input.

- `script/seon/fresh_operator.clj` — `with-operator-lock` takes the selected
  root plus the command text and calls the one lock owner. `-main` passes the
  command line, so a queued command names what it is waiting behind.
- `resources/seon/operator/state.clj` — `with-lifecycle-lock!` is the one lock
  mechanism: it derives nothing, it takes the path. Waiting polls, announces
  after 1 s and every 5 s thereafter with the lock path plus the holder's pid,
  start instant, command, and hold duration read from a `*.holder.edn` record
  written beside the lock, and refuses at a 15-minute backstop with the same
  facts as flat `ex-data` (`:seon.operator/lifecycle-lock-timeout`). A holder
  whose process is dead is reported as a stale record, since the kernel has
  already released its lock. `with-control-lock!` keeps the installation-wide
  file and is now documented as cross-root work only (reaping dead roots).
- `script/seon/dev/state.clj` — `with-lock` was the second, quieter copy of
  this mechanism; it now derives its named path and delegates, so there is one
  waiting/announcing implementation.

A POSIX hazard surfaced while proving it, and the regression is what caught
it: a file lock belongs to the PROCESS, and closing ANY descriptor for that
file drops it. A second thread polling `tryLock` in the same JVM therefore
released the holder's lock on every failed poll — the real operator
subprocess sailed straight through a lock the test still believed it held.
`with-lifecycle-lock!` now claims the path in an in-process owner map before
any thread opens a descriptor, so only the owning thread ever holds one.

Evidence, `bash tmp/lock-live-proof.sh` with a holder on root A:

```text
=== root B (isolated) while A is held ===
● no recorded JVMs to stop            real 0m0.092s
=== root A (same root) while A is held ===
! waiting 1069 ms for the operator lifecycle lock
  /Users/sean/src/seon/tmp/lock-live-a/data/operator/root-lifecycle.lock
  — held by pid 45376 (started 2026-08-08T03:41:13.570Z)
  running `live proof holder` since 2026-08-08T03:41:13.597Z
● no recorded JVMs to stop            real 0m6.036s
```

Regression: `isolated-operator-roots-do-not-serialize-on-one-lifecycle-lock`
in `test/seon/dev/fresh_operator_test.clj` (15 assertions, green). It holds
one root's lock and asserts a real `bb -m seon.fresh-operator` command in
another root completes before the release, that a same-root wait announces
and refuses naming the holder, and — the falsifier for the old code — that a
real command in the HELD root does not exit within 2.5 s and prints the
announcement, which only holds when the command locks the selected root's
file.

Residual, recorded rather than fixed: cross-root sweeps (`reap-dead-roots!`)
still take only the installation control lock, so they are not excluded
against a live command in another root. They act only on roots whose creator
process is dead and refuse their own root, so no live root's transition is in
scope; if that ever changes, the sweep must take each target root's lifecycle
lock too.
