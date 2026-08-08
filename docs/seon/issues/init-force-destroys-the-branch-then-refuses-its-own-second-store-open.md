---
type: issue
status: open
severity: blocker
tags: [issue, operator, store]
---

# `init NAME --force` destroys the branch, then refuses its own second store open

## Problem

`bin/seon init NAME --force` deletes the named cluster branch and then fails
to fork its replacement, because its second arm tries to open a store the
same process already opened:

```text
✗ the store at …/data/clusters/store is held by a live process
{:seon.cluster.store/dir "…/data/clusters/store",
 :seon.cluster.store/lock-file "…/data/clusters/store.lock",
 :seon.error/kind :seon.cluster.store/refused,
 :seon.cluster.store/rule :seon.cluster.store/held-elsewhere}
```

Nothing external holds it. `open-store!`'s own docstring says the flock
refuses "including a second open in this same process"
(`src/seon/cluster/store.clj:271-284`), and that is what is happening: the
refusal is the command colliding with itself.

The result is destructive and not self-recovering. The branch is gone, the
replacement was never created, and re-running the same command cannot
repair it — there is nothing left to destroy and the second arm still
refuses. The cluster is only recoverable by noticing that the branch no
longer exists and running the NON-force `bin/seon init NAME` instead, which
is not something the error suggests.

This is the class the archived issue
[refork-held-a-store-across-the-arm-that-released-it](archive/refork-held-a-store-across-the-arm-that-released-it.md)
closed on 2026-08-07 ("each arm acquires its own store"; commit
`8882c2b1c`). It is back, or a second path with the same shape was never
converted. That issue's own summary names this exact consequence:
"`bin/seon init NAME --force` on the JVM's only cluster DESTROYED the old
branch and failed to fork the new one."

## Evidence

Tool-exercise lane, 2026-08-07 23:41–23:50, isolated operator root
`tmp/tool-exercise-operator`, at HEAD (`ad3f8a3d7`).

1. Before: the root had the `tools` branch and a 43 MB store.
2. `bin/seon --root tmp/tool-exercise-operator init tools --force` → the
   refusal above.
3. Branch roster read directly from the store afterwards:

   ```clojure
   (registry/roster s)  ;; => #{:db :current-src}
   ```

   `tools` is gone.
4. Re-running `init tools --force` twice more produced the identical
   refusal — no recovery.
5. Nothing external held the lock across all of it: `lsof` on
   `store.lock` returned nothing, and `bin/seon --root … status` opened the
   store successfully the whole time ("roster readable via offline-jvm").
6. `bin/seon --root … init tools` (no `--force`) then succeeded:
   `● tools forked :cluster-tools from :current-src commit 6a76a7b4-…`,
   which confirms the store and the fork path are both fine and isolates
   the fault to the `--force` composition.

## An operator census that contradicts itself

Adjacent and worth fixing with it. Immediately before step 4, in the same
shell second:

```text
$ bin/seon --root tmp/tool-exercise-operator down --force
PROCESS RECORD CENSUS root=…/tmp/tool-exercise-operator records=0 unreadable=0
● no recorded JVMs to stop
● flock free; roster readable (2 branches)

$ bin/seon --root tmp/tool-exercise-operator init tools --force
✗ the store … is held by a live process
```

`down` reports the flock FREE and `init` reports it HELD, one second apart.
Both cannot be true. A diagnostic that contradicts the next command sends
the reader hunting for an orphan process that does not exist — which is
what happened here, and cost this lane a `lsof`/`ps`/`sample` detour before
the self-collision became visible.

## Expected

The destroy arm and the fork arm each acquire and release their own store,
so the composed verb cannot collide with itself — the archived fix, applied
wherever this second path lives. Failing that, the command must be atomic:
a `--force` that cannot fork does not destroy.

The refusal must also be able to tell "another process holds this" from "I
already hold this". `::held-elsewhere` is a false statement in the
self-collision case, and it is the reason this reads as environment churn
rather than as a bug in the command.

## Acceptance

- `bin/seon init NAME --force` on an existing branch destroys and reforks
  it in one run, proven by a regression that drives the real composed
  operator verb (the archived issue's regression did this — extend or
  restore it so this path is covered too).
- After any failed `--force`, the named branch still exists.
- `down` and `init` never disagree about whether the store is held.
