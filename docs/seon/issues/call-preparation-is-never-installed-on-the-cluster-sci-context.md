---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, sci, live-drive]
---

# Install call preparation on the cluster's sci context

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
