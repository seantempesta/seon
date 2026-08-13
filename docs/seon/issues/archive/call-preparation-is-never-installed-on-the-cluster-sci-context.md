---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, agent, sci, live-drive]
---

# Install call preparation on the cluster's sci context

## Resolution (2026-08-08, P17 S2)

**Fixed in `seon.sci.eval/cluster-ctx` and `build-base-ctx`, in
`seon.sci.kernel/invoke`, in `seon.call-preparation`, and — the half this
issue did not know about — in the maintained sci fork. Commit `1029a4de7`,
fork commit `6ee57c9`. Evidence:
[p17-s2-notes-2026-08-08.md](../../../prds/sci-execution-runtime/research/p17-s2-notes-2026-08-08.md).**

**The filed diagnosis was correct and INCOMPLETE, and the missing half was
the larger one.** Installing the state was necessary and did not fix the
call. This issue quotes S1's claim that production binds first-party
functions as `sci/new-var` forwarders, so the sci-Var-only hook gate was
harmless. It does not.
`seon.sci.eval/bind-first-party-namespaces!` wraps a host Var only when some
namespace row REFERS that symbol; every other compiled first-party function
is installed as its raw `clojure.lang.Var`, and sci's hook node was generated
only for `sci.lang.Var` callees. Measured on an acquired context,
`seon.cluster/ensure-entity!`, `seon.db/pull` and `my.message/send` are all
raw host Vars. With the state installed and a correct plan compiled, the
2-argument call STILL failed with the identical arity error. The fork now
keys on any identity-bearing Var.

The stated acceptance is met in full: `cluster-ctx` installs the state and
registers the row listener; the `db?`/`connection?` dispatch landed as the
supplied default's own compiled value schema so no `seon.db` positional
shortcut changed meaning; and the class regression
(`an-acquired-cluster-context-prepares-its-calls`) exercises the ACQUIRED
cluster context, never a scratch one. Recurring gate:
`bin/test seon.call-preparation-test` — 14 tests, 127 assertions, 0 failures.

The 2026-08-13 complete run caught a stale construction in that same class
regression after the environment became state carried by the acquired ctx.
The fixture used `env/carry`, whose supplied plain environment cannot replace
the acquired ctx's authoritative state, so the two elided calls were evaluated
without the fixture connection. The regression now installs the fixture's
environment state with `env/carry-state`; production call preparation remains
unchanged.

**Live proof, the arc's exact call**, on cluster `s3` in an isolated root
through the shared SCI ctx: `(seon.cluster/ensure-entity! "91331-…" {…})`
with two arguments returned its creation result and committed the agent.
The first production call preparation.

**It exposed a second defect immediately**, which is what unblocking a dead
path is supposed to do:
[`seon.db/transact!` returns a different shape depending on a dynamic var](../transact-returns-a-different-shape-depending-on-a-dynamic-var.md).
`ensure-entity!` was repaired at its own site here; the class is that issue's.

## Problem

Call preparation derives correct invocation plans, caches them, and passes its
own tests — and is **inert at every live call site**, because the sci ctx never
carries the state the hook reads.

`seon.call-preparation/hook` begins
(`src/seon/call_preparation.clj:878-884`):

```clojure
[ctx callee arguments]
(let [call-state (get ctx carrier)          ; carrier = :seon.call-preparation/state
      environment (env/of ctx)
      sym (when (and call-state environment) (var-symbol callee))
      ...]
  (if-not (and database (not (error-value? database)))
    arguments                                ; <- always taken
    ...))
```

`carrier` is `:seon.call-preparation/state` (`call_preparation.clj:85-87`), and
the ONE function that puts it on a ctx is `install`
(`call_preparation.clj:89-96`), whose docstring states the gap plainly:

> S2 calls this in `seon.sci.eval/cluster-ctx` beside the projection state;
> until then a probe or test attaches it to a scratch ctx.

**S2 never landed.** `install` has no caller in `src/` at all — its only caller
in the tree is `test/seon/call_preparation_test.clj:362`, which attaches it to a
scratch ctx. So the suite is green on a mechanism that is dead in production.

Verified on the live default cluster (pid 31475), the acquired ctx keys are:

```clojure
[... call-preparation-hook ... seon.env/environment seon.schema/projection
 seon.sci.eval/custody seon.sci.eval/projection-state ...]
```

`call-preparation-hook` is present; `:seon.call-preparation/state` is absent.
`call-state` is therefore nil on every call, `sym` is nil, and the hook returns
its arguments untouched every time.

## How it surfaced

Cluster `default`, 2026-08-08, whole-system arc drive. Root was asked to create
three agents. It authored exactly the right call, eliding the connection as
call preparation is meant to allow:

```clojure
(cluster/ensure-entity! "31475-1786181598529"
  {:seon.cluster.agent/id "inventory"
   :seon.ns/name "arc.inventory"
   :seon.cluster/name "default"})
```

All three calls failed identically:

```text
Wrong number of args (2) passed to: seon.cluster/ensure-entity!
```

Reproduced independently through `eval_clj` in door mode against the same
shared ctx, so it is not a turn-local condition.

The plan itself is correct. Derived live from the same database value:

```clojure
{"seon.cluster/ensure-entity!"
 {:seon.call-preparation/by-supplied-count {3 …, 2 …}   ; 2 IS planned
  :seon.call-preparation/empty? false}}
```

The plan for the exact 2-argument call exists and is not ambiguous. Nothing is
wrong with derivation; the plan is simply never consulted.

`seon.db/q` and `seon.db/pull` keep working with an elided database only
because they carry their own shorter arities (ruling #41's positional
shortcut), which masked the failure — every function that relies on call
preparation instead of its own arity is unreachable when an argument is
elided.

## Why it is a blocker

An agent cannot create another agent, and more generally cannot call any
contracted function that declares a suppliable argument without knowing and
threading cluster custody by hand. The whole-system arc's Stage 1 delegation
could not be performed by root for this reason.

## Not fixed here, deliberately

The one-line change — calling `install` in `seon.sci.eval/cluster-ctx` — would
arm the hook on every call in the cluster at once, and the owning docstring
says S2 also owns "the complete argument transformation with its three failure
faces" and "the `db?`/`connection?` predicate dispatch that preserves ruling
\#41's positional shortcut". Arming the hook without that dispatch risks
changing the meaning of existing elided `seon.db` calls. That is larger than a
drive-time repair.

## Acceptance

- `seon.sci.eval/cluster-ctx` installs call-preparation state, so an acquired
  cluster ctx carries `:seon.call-preparation/state`.
- The predicate dispatch and the three failure faces land with it, and existing
  positional-shortcut `seon.db` calls keep their current meaning.
- One regression proving the class dead: a contracted function outside
  `seon.db` that declares `:seon.db/connection`, called from a real turn with
  that argument elided, receives it. The regression must exercise the ACQUIRED
  cluster ctx, not a scratch ctx built by the test — a scratch-ctx test is what
  let this ship.

## Owner

`src/seon/sci/eval.clj` (`cluster-ctx`) with `src/seon/call_preparation.clj`.
