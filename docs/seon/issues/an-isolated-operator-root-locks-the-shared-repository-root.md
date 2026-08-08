---
type: issue
status: open
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
