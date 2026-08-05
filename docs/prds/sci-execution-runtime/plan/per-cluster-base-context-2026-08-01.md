---
type: prd
status: active
tags: [prd, sci, agent]
---

# The per-cluster live program graph

Owner rulings #27, #28, #29 (2026-08-01, `plan/README.md`). This document
owns one mechanism: **the sci context that IS a cluster's live program
graph** — where it comes from, what it shares with the process, what it
costs, how it stays current, and how it dies.

It answers the owner's verbatim question ("then we need multiple base
contexts? look into SCI and how to do this efficiently") and the ruling
#29 expansion (the graph is live, not rebuilt per turn).

Everything below is measured on this machine on 2026-08-01 against the
live `default` cluster (1,469 `:seon.fn` rows, 189 `:seon.ns` rows, 572
`:seon.schema` rows, 0 agent-authored namespaces) or in a
`clojure -M:dev` JVM. Probe: `tmp/per-cluster-base-ctx-probe.clj`; the
database-backed probes were run through `mcp__seon__eval_clj` against
`default` read-only and are reproduced inline.

## 0. The one-paragraph answer

Yes, multiple base contexts — and they are nearly free. A base ctx costs
**0.1–1 ms and ~20 KB**; twenty of them in one JVM cost **2.0 ms and
0.31 MB total**. The expensive thing everyone remembers (the ~283 ms
"substrate") is not `sci/init` at all, it is `acquire!`, which ruling #29
removes from the hot path entirely. So: **per-cluster `sci/init` (option
a), no sci fork change required for the boundary itself.** One small sci
fork change is still warranted, for a different and much narrower reason
— a residue of 17 process-global writable Vars that survive independent
`init` calls (§3.4).

## 1. What a base context actually costs

`src/seon/sci/eval.clj:147` builds it: one `sci/init` with `:namespaces`
(the interrupt-aware `clojure.core`/`clojure.string`, a derived
`clojure.test`, `my.run`, `my.message`, `seon.schema`), four `:classes`,
the process `:interrupt-fn`, then two `add-namespace!` calls for
`dir`/`doc`.

The probe rebuilds that EXACT form by reading it back with
`clojure.repl/source-fn`, so the measurement cannot drift from the source.

| measurement | value |
|---|---|
| one base ctx, warm | **0.1–1.0 ms** |
| N=1 held | 1.0 ms, 0.02 MB |
| N=5 held | 0.8 ms total (0.2 ms each), 0.08 MB (0.02 MB each) |
| N=20 held | **2.0 ms total (0.1 ms each), 0.31 MB (0.02 MB each)** |
| bare `(sci/init {})` | < 1 ms |
| `sci/fork` | 0.20 µs, 0.13 KB |

Per-cluster init is **1,400× cheaper than the ~17 ms Datahike fork that
creates the cluster in the first place**. It is not a cost worth designing
around. Cost scales linearly with N and holds flat per ctx, because
sci's default namespace map (`sci.impl.namespaces/namespaces`) is a
process-level `def` that `init-env!` merges without copying
(`reference-code/sci/src/sci/impl/opts.cljc:17-62`): each `init` allocates
only the small per-context overlay.

### 1.1 Correcting the record on "489 ms / 3 MB"

`plan/repl-session-context-2026-08-01.md:22-23` attributes 489 ms / 3 MB
to "the interpreted-corpus substrate … once per process". That number is
**`acquire!`, not `sci/init`**, and it never described context creation.
Measured today on `default`:

| measurement | value |
|---|---|
| `acquire!` median (7 samples: 286/281/283/279/284/282/284) | **283 ms** |
| `acquire!` retained, 20 held | 34.1 MB (**1.75 MB each**) |

(The sibling lane's 78.3 KB figure in
`research/sci-session-persistence-2026-08-01.md:43` measured a delta on a
smaller corpus; on `default` today the retained figure is 1.75 MB.)

### 1.2 Where the 283 ms actually goes — measured breakdown

| component | median ms | share |
|---|---|---|
| `schema/projection-from-database` | **211** | 74 % |
| repeated `admission-source` per row (1,469 × 0.049 ms) | **~72** | 25 % |
| `install-loaded-first-party-namespaces!` | 5.8 | 2 % |
| `install-program-doc!` | 0.11 | < 1 % |
| the three Datalog queries themselves | **< 1.0** | ~0 % |
| interpreted agent rows on `default` | 0 (no agent namespaces) | — |

Two facts fall out and both matter:

- **The database is not the problem.** All the queries together run in
  under a millisecond. The projection's own three queries measure 0.0025,
  0.0023 and 0.90 ms for 572, 415 and 1,469 rows. The 211 ms is inside
  `projection-from-rows` — registering 572 schema forms.
- **`admission-source` is recomputed per row.** `acquire!`'s
  `agent-authored?` closure calls
  `schema/admission-from-asserting-transaction` once per program row
  (`src/seon/sci/eval.clj:747-751, 838-857`) although `default` has
  exactly **one** distinct source transaction. That is ~72 ms of pure
  repetition, and it is a memoization over the row's `?source-tx`, not a
  design question.

`projection-from-database` already accepts a reusable projection for
fingerprint-based reuse, but `acquire!` never passes one — and passing it
only halves the cost (229 ms → 119 ms), because the fingerprint itself
walks every row. **Caching the projection VALUE on the cluster and
recomputing it on a program-fact event is the right shape; per-turn
fingerprint reuse is not.**

## 2. Falsification — both halves of ruling #27, and a correction

Two independent base ctxs in one JVM, plus forks of each. Full script in
`tmp/per-cluster-base-ctx-probe.clj`; the corpus-backed half ran against
`default`'s live connection.

### 2.1 Isolation (the half the ruling requires): PASSES

```
A: redefining clojure.core/map -> REFUSED: Built-in var #'clojure.core/map is read-only.
B: clojure.core/map still      -> clojure.core$map
A: my.message/send after       -> :poisoned
B: my.message/send after       -> my.message$send          ; UNTOUCHED
A: (isa? ::child ::parent)     -> true
B: (isa? ::child ::parent)     -> false                     ; UNTOUCHED
B: my.shared/improve           -> ISOLATED: Unable to resolve symbol: improve
```

Independent `sci/init` calls give independent `copy-var` Var objects, an
independent `global-hierarchy`, and an independent `*loaded-libs*` — all
three are constructed per `init-env!` call. Built-in core vars are
protected by sci's own `with-writeable-var` gate
(`reference-code/sci/src/sci/impl/vars.cljc:283-292`), so the
process-shared default namespace map is not a leak.

### 2.2 Sharing (the half the ruling asserts): PARTLY FALSE TODAY

The ruling says sci's shared-Var fork behavior is the propagation
mechanism. Measured, `sci/fork` propagates in **exactly one** of three
cases:

| what an agent's fork does | sibling fork / base sees it? | why |
|---|---|---|
| `def` of a name whose base entry is a **sci Var** | **YES** | `eval-def` finds the existing Var and `bindRoot`s the shared object (`evaluator.cljc:25-47`) |
| `def` of a name whose base entry is a **host `clojure.lang.Var`** | **no** | `eval-def` wraps the host var in a NEW sci Var in the fork's own env copy — the host var is never mutated |
| `def` of a **brand-new** name | **no** | interned only in the fork's copied env map (`sci/fork` is `(update ctx :env #(atom @%))`, `core.cljc:318-323`) |

Proof of all three:

```
;; sci Var in the base -> propagates to base and to sibling forks
:base-entry-class "class sci.lang.Var"
:fork1-def ":ok"   :fork2-sees ":A-IMPROVED"   :base-sees ":A-IMPROVED"

;; host var (what acquire! installs for first-party namespaces) -> no propagation
:after-A-base "#'my.message/send"     ; still the host var
:A-sibling-fork-sees "clojure.lang.AFunction$1@5307b26d"   ; the ORIGINAL fn

;; brand-new name -> fork-local only
:sibling-sees-new-name [:ERR "Unable to resolve symbol: g"]
```

**Consequence for the design.** Under today's per-run `fork` + `acquire!`,
intra-cluster sharing does not come from sci at all — it comes from the
database, because every agent's next run reinstalls every program row.
The tiny surface where fork DOES propagate is precisely the surface that
leaks across clusters today. Ruling #29 resolves this cleanly and in the
direction the owner already chose: **stop forking per run.** When agents
in a cluster share ONE live ctx, all three rows above become "YES"
trivially, and the boundary is the cluster because the ctx is.

This is the one place where the design must be stated plainly rather than
inherited: **`fork` is not the intra-cluster sharing mechanism and cannot
be made into one without deep-copying Vars, which defeats the point.**
The mechanism is a single shared env per cluster.

### 2.3 Where forking survives

Not everywhere. `evaluate` today takes the caller's ctx as given "so the
fold shares defs" (`src/seon/sci/eval.clj:892-930`); with a live cluster
ctx that is satisfied by construction. A fork remains the right tool for
one genuine case: **speculative evaluation that must not touch the graph**
— a grader dry-run, a contract falsifier, an admission probe. Those get
`(sci/fork cluster-ctx)` explicitly and discard it. Ordinary agent turns
do not fork.

## 3. What is shared, what is per-cluster

### 3.1 Process-shared and safe (immutable or mechanism-only)

- **sci's default namespace map** — `sci.impl.namespaces/namespaces`, a
  top-level `def` merged by every `init`. 717 entries, 694 sci Vars, of
  which **631 carry `:sci/built-in` and are read-only**. Shared by
  construction, cheap, correct.
- **`:classes`** — four JVM class objects. Immutable.
- **The interrupt guard** (`process-interrupt-guard`,
  `src/seon/sci/eval.clj:296-297`) — a `ThreadLocal` plus the one
  `interrupt-fn`. It carries no program state; arming is per thread per
  evaluation. **Keep it process-wide.** Rebuilding it per cluster would
  multiply the `EvaluationArm` class-identity hazard for no gain.
- **Compiled first-party JVM Vars** — `install-loaded-first-party-namespaces!`
  binds `ns-interns` host Vars. These are the same objects in every
  cluster, and that is correct: they are Seon's own compiled code, not
  agent-writable program state. Measured above, `eval-def` never mutates
  a host Var, so an agent cannot poison the JVM through one.

### 3.2 Per-cluster (the mutable program graph)

- the env atom and every sci Var interned in it;
- `global-hierarchy` (`derive`/`defmulti` state) — already per-`init`;
- `*loaded-libs*` — already per-`init`;
- the `copy-var`'d agent surface (`my.run`, `my.message`, `seon.schema`,
  the derived `clojure.test`) — already per-`init`;
- the acquired schema projection and program-doc map;
- every agent-authored namespace, function and test installed from rows.

### 3.3 Can `sci/init` reuse prebuilt pieces?

It already does, and it is the reason the cost is 0.1 ms. `opts/init`
accepts `:env` (an existing env atom) and `init-env!` skips installing the
default namespaces when the env already has them
(`opts.cljc:236-273`, `opts.cljc:20-26`). We do not need that door:
passing `:env` would share the atom, which is the leak we are removing.
Nothing further to expose in the fork for this purpose.

### 3.4 The residue — the one sci fork change worth making

Two independent `init` calls still share Var **objects** for a small set
that is defined at sci's top level rather than per `init-env!`. Measured
by object identity across two fresh contexts:

| shared across independent `init`s | count |
|---|---|
| `clojure.core` dynamic vars: `*ns* *file* *read-eval* *data-readers* *default-data-reader-fn* *reader-resolver* *suppress-read* *warn-on-reflection* *unchecked-math* *assert* *clojure-version*` | 11 |
| `clojure.core/unquote` | 1 |
| `clojure.walk/macroexpand-all` | 1 |
| `clojure.lang` interface entries (`IFn IDeref IAtom IAtom2`) | 4 |
| NOT shared (fresh per init): `global-hierarchy`, `my.message/*`, `my.run/*`, `clojure.test/*`, `seon.schema/*` | — |

None of these carry `:sci/built-in`, so they are writable, so
`(alter-var-root #'clojure.walk/macroexpand-all …)` or a root rebind of
`*warn-on-reflection*` in cluster A reaches cluster B — after the
per-cluster change lands. `macroexpand-all` is a bare `sci.lang/->Var`
top-level `def` with no built-in marker
(`reference-code/sci/src/sci/impl/namespaces.cljc:2450-2466`); the
dynamic vars come from `sci.impl.utils` top-level defs (`utils.cljc:322,
374`). Compare `loaded-libs**`, which sci DOES mark
(`namespaces.cljc:1313-1321`).

**The fork change: add `:sci/built-in true` to those Vars' metadata.** It
is a metadata edit in two files with no behavioral surface beyond
"agents cannot root-rebind them", which is what we want. This is a
17-var closure of a residue, not a fork-scope redesign.

## 4. The three options

### (a) Per-cluster `sci/init` — RECOMMENDED

Build one base ctx per cluster at start; hold it on the instance.

- Cost: **0.1 ms, 20 KB per cluster.** 20 clusters = 2 ms, 0.31 MB.
- Risk: the §3.4 residue (17 vars), closed by a metadata edit.
- Invasiveness: `defonce base-ctx` becomes a function of the cluster;
  three call sites in `src/`, several in `test/` (§6).
- Satisfies ruling #27 in both directions once the shared-env change of
  ruling #29 lands.

### (b) One process init + per-cluster copy-on-write fork in our sci fork

Change `sci/fork` (or add `sci/fork-deep`) so a cluster-level fork copies
Var objects while agent-level forks keep sharing.

- What would have to change: `core.cljc:318-323` would walk
  `(:namespaces @env)` — **717 entries, 694 sci Vars** — and construct a
  new `sci.lang/->Var` per entry, preserving root, meta, `thread-bound`,
  `needs-ctx` and `watches` (`lang.cljc:70-120`). Every `deftype Var`
  field is mutable, so a shallow copy is not enough and a wrong copy is
  a silent aliasing bug. `dynamic?`, `alter-var-root`, `with-bindings`
  and the built-in gate all read those fields.
- Cost: strictly **more** than `init`, because `init` gets 694 of those
  vars for free by merging a shared map and only builds the ~60-var
  overlay. A copying fork must touch all 694.
- It also introduces a two-kind fork ("cluster fork" vs "agent fork")
  into a function whose whole virtue is that it has one meaning.
- **Rejected.** It is more code, more cost, and more semantics to buy
  something `init` already gives us for 0.1 ms.

### (c) Per-cluster init with a shared immutable substrate

This is what (a) IS. sci's default namespace map is already the shared
immutable substrate; `init` already reuses it without copying. There is
no separate third option to build — only the §3.4 residue to close, which
(a) includes.

**Recommendation: (a), plus the §3.4 metadata edit in our sci fork.**

## 5. The live graph — what leaves the per-turn path (ruling #29)

Today (`src/seon/cluster/loop.cljc:1023-1027`) every turn does
`(sci.eval/fork)` then `(acquire! ctx @connection)`. Item by item:

| `acquire!` work | today | after |
|---|---|---|
| `projection-from-database` (211 ms) | per turn | **cluster value**, recomputed on a program-fact event |
| `install-loaded-first-party-namespaces!` (5.8 ms) | per turn | **cluster boot**, plus the incremental event (§5.2) |
| `install-program-doc!` (0.11 ms) | per turn | **cluster boot** + same event |
| agent namespace / function / test rows | per turn | **cold path only** (§5.3) |
| `admission-source` per row (~72 ms) | per turn, recomputed | memoized per `?source-tx`; runs on the cold path only |
| the `fork` itself (0.2 µs) | per turn | **removed** — the turn evaluates in the cluster ctx |

**Nothing in `acquire!` is genuinely per-turn state.** The one thing that
looked like it — the schema projection threaded forward across ordinals
inside the fold — is graph state: a `seon.schema/register!` during a turn
mutates the graph, and under a live ctx it mutates the cluster's
projection directly instead of being rebuilt from facts at the next turn
boundary. The fold's existing forward-threading of `projection` becomes
an update of the cluster's held projection.

Per turn, after: **nothing**. The turn reads the cluster ctx and
evaluates.

### 5.1 First-party code tracks hot reload for free

`install-loaded-first-party-namespaces!` binds `ns-interns` — host Var
**objects**, whose identity survives re-evaluating a `defn`. So a live
cluster ctx sees hot-reloaded first-party behavior with no reinstall.
Only **new or deleted vars, and new namespaces**, need an event. This is
a genuine property of the current binding choice and it should be stated
in the docstring, because it is the reason §5.2 can be small.

### 5.2 External publications — the incremental update

Existing clusters are sovereign and are never source-synchronized
(root `AGENTS.md`; `bin/seon init` publishes to the `current-src` branch,
not to a cluster branch). So the incremental case is **not** cross-branch
propagation. Two real events remain:

1. **Same-JVM code reload** — the edit hook / a `require :reload` changes
   compiled first-party namespaces. Event: the reload itself. Granularity:
   **namespace**. Action: re-run `install-loaded-first-party-namespaces!`
   for the changed namespaces only (5.8 ms for all 189, so per-namespace
   is free) and recompute the program-doc map. Needed only when the
   namespace gained or lost a var; existing vars track automatically
   (§5.1).
2. **A refork** — `bin/seon init CLUSTER --force` destroys and reforks the
   branch. That is not incremental: it is a new program, and the cluster
   ctx is rebuilt from scratch (§6).

For agent-authored program facts committed by another agent in the same
cluster, no event is needed at all: same JVM, same live ctx, the def is
already there.

`seon.cluster.wake` is the right home if an event-driven trigger is ever
needed for a database-side program-fact change — it is already the
attribute-indexed `listen!`-derived delivery
(`src/seon/cluster/wake.cljc:6, 78-95, 163-189`) and program attributes
(`:seon.fn/source`, `:seon.ns/name`, `:seon.schema/form`) are exactly the
kind of attribute set `wake-attributes` names. **Do not build a second
watcher.** But note honestly: with the live-ctx design, the only
in-cluster writer of those attributes is the cluster itself, so this is a
guard for stateless resume and for a future out-of-process publisher, not
a mechanism the MVP exercises.

**An agent mid-turn when an update fires.** The update mutates the shared
env, exactly as another agent's `defn` does. That is the intended
semantics of ruling #27 and it is what a real Clojure REPL does when you
reload a namespace under a running thread. No quiescing, no barrier, no
version pinning — a design that needed one would be reintroducing the
per-turn snapshot ruling #29 deletes. The one thing that must hold is
that the update is a sequence of ordinary `def`s, so a reader either sees
the old root or the new one, never a torn state; sci Var roots are
`^:volatile-mutable` (`lang.cljc:70-86`), which gives exactly that.

### 5.3 Per-row containment on the cold path

`issues/acquire-has-no-per-row-containment.md` is unchanged in substance
and reduced in urgency: with the rebuild off the hot path, a poisoned row
can no longer re-throw every turn. It still must not abort a boot or a
resume, so containment belongs at the one place the cold path installs
rows — `install-row!` (`src/seon/sci/eval.clj:552`), wrapped by
`acquire!`'s row loop:

- each row installs inside its own containment; a failure becomes a flat
  `:seon.error` value recorded as a problems-family fact naming the row;
- the remaining rows install;
- the cluster starts with a graph that is missing exactly the poisoned
  definitions, and the agent sees why as ordinary derived context.

The alias-resolution edge named in that issue is the same fix and stays
with it. This design does not change that issue's acceptance criteria; it
moves the blast radius from "every turn forever" to "one boot".

## 6. Lifecycle

**Home.** The cluster instance map in
`seon.cluster/running-instances` (`src/seon/cluster.clj:185`), surfaced on
the loop handle built by `loop-handle` (`src/seon/cluster.clj:1033-1060`)
next to `:seon.cluster.loop/evaluate`. The handle already carries the
branch connection, the process identity, the caps and the time limit; the
cluster's ctx is the same kind of fact. Proposed key:
`:seon.sci.eval/ctx` on the instance, threaded onto the handle.

**Build.** At `start!`, after the branch connection is open and before the
loop graph starts: `(sci.eval/cluster-ctx connection)` = `sci/init` (0.1 ms)
plus one cold `acquire!` (283 ms today; ~211 ms of that is the projection and
~72 ms is the memoizable `admission-source` repetition, so the honest
post-fix boot figure is **~215 ms and falling**). Against a ~17 ms cluster
fork this is the dominant new boot cost and it is the correct place for
it — it is paid once instead of on every turn of every agent forever. It
is also the natural target for the next optimization, and it does not
block the ten-second start (ruling: start ≤ 10 s).

**Live.** Held for the cluster's lifetime. Agents evaluate in it. Nothing
reinstalls.

**Refork.** `refork!` destroys and recreates the branch: the ctx is
discarded and rebuilt from the new branch's facts. There is nothing to
release — it is unreachable and the GC takes it (0.02 MB, or 1.75 MB with
the corpus acquired).

**Stop.** `stop!` (`src/seon/cluster.clj`) unwinds instance-addressed:
`disarm-agents!`, release the branch connection, drop the store hold,
close the prepl socket, delete the advertisement. The ctx needs **no**
release step — it holds no file handle, socket or thread. Dropping the
instance from `running-instances` releases it. Explicitly: do NOT add a
close hook; there is nothing to close, and a hook would be a second
lifecycle to keep correct.

**Hot reload of `seon.sci.eval` itself.** Unchanged and still broken
(`issues/sci-eval-namespace-is-not-hot-reloadable.md`): the
`EvaluationArm` `deftype` gets a new class on reload while the `defonce`
guard retains the old one. Moving the ctx off `defonce` does not fix it
(the guard's `defonce` is the coupling) and does not worsen it. Keeping
the guard process-wide per §3.1 keeps that issue exactly one issue.

**Interaction with the parked hot ctx (ruling #28).** The parked-per-agent
hot ctx becomes unnecessary as a *program* cache — the program lives in
the cluster ctx and every agent already sees it. What remains genuinely
per-agent is REPL **session** state: the current namespace, `*1`/`*2`/`*3`,
and agent-local data defs. Under ruling #28 those are restorable facts,
not a retained ctx. This design therefore *simplifies* the session lane
rather than competing with it: the session-persistence question shrinks
from "how do we keep a whole interpreted graph warm per agent" to "what
per-agent bindings does a fresh turn restore". The sibling
`plan/stateless-resume-design-2026-08-01.md` did not exist at the time of
writing; when it lands, the cold-path rebuild described in §5.3 is the
same rebuild resume needs, and it should be one function with two callers.

## 7. Slice 1

**Change.** Replace the process `defonce base-ctx` with a per-cluster ctx
built at `start!` and held on the instance/handle; the turn evaluates in
it instead of `(fork)` + `acquire!`.

1. `src/seon/sci/eval.clj` — `base-ctx` `defonce` becomes a
   `build-base-ctx` function; `base` is deleted; add
   `cluster-ctx` = build + cold `acquire!`. The guard stays a process
   `defonce`.
2. `src/seon/cluster.clj` — `start!` builds the ctx, the instance holds
   it, `loop-handle` carries it.
3. `src/seon/cluster/loop.cljc:1023-1027` — drop the per-turn
   `fork`/`acquire!`; read the ctx and the projection from the handle.
4. `reference-code/sci` — mark the §3.4 residue `:sci/built-in`.
5. Test call sites that reach for `(sci.eval/base)` —
   `test/seon/cluster/turn_test.clj:570,649,741,1406,1522`,
   `test/seon/sci/eval_test.clj:266`,
   `test/seon/cluster/program_restart_test.clj:276` — take the fixture
   cluster's ctx instead.

Explicitly NOT in slice 1: per-row containment (§5.3, its own issue and
its own slice), the `admission-source` memoization (a separate one-line
win worth its own commit), the incremental-update event (§5.2 — nothing
in the MVP writes program facts from outside the cluster).

**Acceptance evidence — the two-clusters-one-JVM regression.** One test,
one class, both directions of ruling #27:

- start two clusters in ONE JVM;
- an agent in cluster A defines `my.probe/f` and redefines an existing
  corpus name;
- a *different* agent in cluster A sees both **immediately, with no
  reinstall** (ruling #27's sharing half, and the proof that ruling #29's
  live graph works);
- an agent in cluster B sees neither, and B's `global-hierarchy`,
  `*loaded-libs*` and `my.message/send` are untouched (the isolation half);
- a redefinition of a `clojure.core` name is refused in both.

The probe in §2 already runs every one of these assertions against real
contexts; the regression is that probe promoted into `test/` with the
cluster fixture supplying the two clusters. Per the testing rule, this is
ONE regression for the class — the class being "program state crossing a
cluster boundary" — not a test per symbol.

**Falsifier for the payoff.** Median turn latency before and after, on a
cluster with a real corpus: **283 ms per turn must become 0 ms per turn**,
and cluster boot must gain ~215 ms exactly once. If measured turn latency
does not drop by roughly that, the fork or the acquire is still on the hot
path and the slice is not done.
