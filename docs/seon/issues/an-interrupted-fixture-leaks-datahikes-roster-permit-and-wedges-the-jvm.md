---
type: issue
status: open
severity: blocker
tags: [issue, platform, datahike, gc-guard, test-support, bounded-execution, absence-as-health, class/p1]
created: 2026-09-16
---

# An interrupted in-process test leaks Datahike's roster permit and wedges every later fixture in that JVM

## Observed (default, 2026-09-16 ~06:30Z, pid 27828, after `bin/seon reset --force`)

Every db-backed in-process test run stopped arriving. `seon.test/run` reported
its bound honestly:

```
"seon.render.transcript-test/error-receipt-without-triage-has-an-execution-error-face"
never arrived for :seon.test/run within the declared :seon.test/remaining-ms
bound of 20000 ms.
```

…but raising the bound to 100000 ms changed nothing, and the JVM was at 0%
CPU. `seon.test-support/with-database` ALONE — with a body that only counts
agents — never returned either (>130 s):

```clojure
(seon.test-support/with-database (fn [c] (count (seon.db/q '[:find ?e :where [?e :seon.agent/id]] @c))))
```

Thread dump of the blocked platform thread:

```
clojure.core$promise$reify.deref(core.clj:7261)
clojure.core.async$fn__5801.invoke(async.clj:161)          ; <!!
datahike.gc_guard$acquire_reachability_permit_BANG_        ; gc_guard.cljc:190
datahike.versioning$branch_BANG_                           ; versioning.cljc:212
```

The gate's own state names the cause exactly — no sweep, one permit held,
twenty-one waiters queued behind it forever:

```clojure
(let [s @(get @@(resolve 'datahike.gc-guard/reachability-gates)
              #uuid "348b77ab-4a40-4b20-9457-9b966b4b4df9")]
  {:sweep  (:datahike.gc-guard/sweep s)      ;=> nil
   :roster (:datahike.gc-guard/roster s)     ;=> #:datahike.gc-guard{:mode :roster, :token 174, …}
   :waiting-modes (frequencies …)})          ;=> {:roster 21}
```

## Root cause

`datahike.versioning/branch!` takes the EXCLUSIVE `:roster` permit and
releases it in a `finally`
(`reference-code/datahike/src/datahike/versioning.cljc:225-235`). Acquisition
itself blocks in `core.async/<!!`
(`reference-code/datahike/src/datahike/gc_guard.cljc:203`).

`seon.test/run` bounds a test by starting it on a virtual thread and, when the
bound fires, CANCELLING it with an interrupt
(`src/seon/test.clj:130`: `(finally (when-not (.isDone task) (.cancel task true)))`).
If that interrupt lands while the thread is parked in `<!!` AFTER the gate has
already granted the permit and put it on the `ready` channel, the permit is
granted to nobody: the gate records `:roster` as held, the would-be holder is
dead, and nothing ever releases it. Every later `branch!` in that JVM — that
is, every `with-database` for EVERY lane — queues behind it forever.

The bound expiry that starts this is easy to hit: on a freshly reset JVM the
cold `seon.test-support/database-base` takes ~15 s to realize, so a 20 s
default bound routinely fires inside the first `with-database`.

## Why this is the project's recurring failure class

1. `acquire-reachability-permit!` with `:sync? true` has NO bound. AGENTS §2.3
   requires both halves: the wait is event-driven but unbounded, so one lost
   permit becomes a silent wedge instead of a report naming what never
   arrived. A hang is worse than a failure.
2. The gate's held permit carries no holder identity and no acquisition
   instant, so nothing can observe "held by a thread that no longer exists".
   Absence of the release is read as health.
3. `seon.test/run`'s cancellation is correct in intent but is a lossy
   interrupt across a resource boundary it does not own.

## Wanted

- Bound `acquire-reachability-permit!`'s synchronous wait and refuse with a
  typed diagnostic naming the holding token, the wait, and the queue depth.
  It is a vendored dependency (`reference-code/datahike`), so this is a fork
  change with its upstream note.
- Make a granted-but-unreceived permit impossible: deliver the permit and
  register the holder in one step, or have the acquiring side release on
  `InterruptedException` before rethrowing.
- Record holder identity (thread, acquisition instant) on the permit so a
  leak is a QUERY, not a thread dump.
- Until then, `seon.test/run`'s bound firing inside a fixture should be
  reported as a suspected leak rather than a plain "never arrived".

## Recovery for a wedged JVM

`bin/seon stop default` + `bin/seon start` clears it (the gate is per-JVM
state). The orchestrator owns `default`; a lane must not restart it.

An in-place release is possible but was NOT performed by the reporting lane,
because double-releasing a permit whose holder is merely slow would corrupt
the gate:

```clojure
;; ONLY when the dump shows no live thread inside branch!
(datahike.gc-guard/release-reachability-permit!
  (:datahike.gc-guard/roster @(get @@(resolve 'datahike.gc-guard/reachability-gates) STORE-ID)))
```

## Impact observed

Blocked the transcript / web-debug reds lane from proving any db-backed test
in process on `default` (2026-09-16). See
[transcript-web-debug-reds-2026-09-16](../../prds/steward-platform/research/transcript-web-debug-reds-2026-09-16.md).

## Call-graph publication observation — 2026-09-17 assignment

Default PID 53320's publication thread `Clojure Connection seon.cluster/default
2806` was observed WAITING in `datahike.gc-guard/acquire-reachability-permit!`
(`gc_guard.cljc:203`) → `datahike.versioning/branch!` (`versioning.cljc:231`)
→ `seon.cluster.registry/branch!` (`registry.clj:206`). Its operator client
had announced “branch publication started”. This is the same waiting seam;
the call-graph lane did not establish the holder's identity or infer a new
cause from that stack alone.

The lane ended only its own operator client PID 23608 after verifying its
exact command identity (client exit 143). Default was not signalled, restarted,
or stopped; no permit was released. The JVM-side publication wait remains an
owner recovery/verification boundary. See the
[call-graph landing note](../../prds/steward-platform/research/call-graph-fidelity-fix-2026-09-17.md).
