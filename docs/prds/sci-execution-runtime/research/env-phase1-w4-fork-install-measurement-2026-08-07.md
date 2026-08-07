---
type: research
status: complete
tags: [research, runtime, sci]
---

# Phase 1 W4 — per-fork installation cost, measured

Read end to end before this lane started, and stated as required:
[seon-env-phase1-specs-2026-08-07.md](../plan/seon-env-phase1-specs-2026-08-07.md)
(section W4 is this lane's contract),
[seon-env-prd-2026-08-07.md](../plan/seon-env-prd-2026-08-07.md) (the sealed
design, including Phase 0 finding 2), and
[env-phase0-runtime-ctx-hook-2026-08-07.md](env-phase0-runtime-ctx-hook-2026-08-07.md)
(the finding's provenance). All three read in full, not searched.

This is a measurement, not a mechanism. No `src/` file was touched, no
suite was run, no shared cluster was reached.

## Verdict

**Recommend the hybrid, and it is cheaper than either pure option.**

The measurement changed the shape of the question. Two facts do most of
the work:

1. **The hazard's blast radius is smaller than "the environment".** Each
   cluster has its own base ctx, so a base-pinned function can never
   resolve *another cluster's* environment. Within one cluster, every
   cluster-scoped member (connection, projection, cluster name) is
   identical in the base and in every fork of it — pinning them is
   harmless. Only **turn-scoped** members (agent id, run id, form ordinal,
   evidence sink) are wrong, and only for functions that declare one.
2. **Eager is expensive and lazy is not free either.** Re-creating a
   1000-function corpus in every turn fork costs **60–120 ms and 12–23 MB
   per fork** — against a `sci/fork` baseline of **225 ns**. Lazy is three
   orders of magnitude cheaper for a realistic turn (0.4–0.8 ms for five
   entry points) **but only if the corpus is flat**: sci refuses a
   definition whose callee is unresolved, so a chained corpus drags its
   transitive callee closure in and lazy collapses back onto eager
   (66–114 ms at N=1000).

So: pin what is safe to pin, re-create only what is not. The set to
re-create is a program-graph query — "which functions declare a
turn-scoped environment member" — never a hand list. Section
[[#Recommendation]] states it precisely, with the two constraints the
implementation must absorb.

## Method

Everything below was measured on this machine (Darwin 25.5.0, JDK with
`jdk.incubator.vector`, Clojure 1.12.5) through `clojure -M:dev`
load-only. sci is `:local/root "reference-code/sci"` in `deps.edn`, so the
submodule working tree is the sci on the classpath; the two hook probes
require branch `seon-env-hook-probe` (commit `a072c8e`, superproject pin
untouched), the three cost probes do not.

Timing harness: warm rounds, then the **median** of five to seven timed
rounds, min and max reported alongside so the reader can see the spread.
sci's own JIT warmup is paid before the measured rounds (`warm-jit!`);
without it the N=10 case reported five times the per-defn cost of N=1000,
which was a measurement artifact, not a real fixed cost.

Memory is `Runtime.totalMemory - freeMemory` after four `System/gc` rounds
with the forks held live. That is indicative, not a heap dump — read the
per-defn figures as an order of magnitude with a consistent methodology
across rows, not as exact retained size.

### Why no scratch-cluster measurement of the real installed program

Attempted, and abandoned for a reason worth recording rather than
retried: `bin/seon --root tmp/w4-root init` currently fails with
`:malli.core/invalid-schema {:schema :seon.sci.kernel/arm}`, so no
`current-src` branch can be published and no cluster boots
(`✗ The cluster instance failed above the REPL: No current-src branch is
published`). The cause is a sibling lane's **uncommitted** working-tree
edit in `src/seon/sci/kernel.clj` (W2's interrupt-arm work — `arm?` is
registered as a core predicate at `kernel.clj:304` and used as
`:seon.sci.kernel/arm` in the `:malli/schema` at `kernel.clj:320,342`,
but the value schema itself is not registered). Foreign, in flight, and
not this lane's to fix. The scratch root was torn down and removed.

It would not have changed a number anyway, and this is the important
part: **the real installed program has no agent-authored interpreted
defns at all today.** `acquire!` filters function rows to those whose
asserting transaction's admission source is `:agent`
(`src/seon/sci/eval.clj:1100-1117`, `admission-source` at `:813-816`);
first-party functions are installed as *host Vars* by
`install-loaded-first-party-namespaces!` (`:826`, via `forwarding-host-var`
at `:818-824`). Host Vars do not pin a ctx — the call-preparation hook
fires for them with the caller's runtime ctx (Phase 0). So the corpus this
lane must price is a corpus that does not exist yet, and a synthetic one
sized from evidence is the only honest instrument.

### Sizing the synthetic corpus from evidence

`w4_corpus_shape.clj` reads every top-level `defn`/`defn-` in first-party
`src/` with the Clojure reader (`:read-cond :allow`, `:features #{:clj}`,
`*read-eval*` off — no regex over source text) and reports the
distribution. 81 files, 1428 defns, 20 forms unreadable:

| | min | p25 | median | p75 | p90 | p99 | max |
|---|---|---|---|---|---|---|---|
| source chars | 40 | 184 | **336** | 664 | 1232 | 2572 | 6046 |
| reader nodes | 6 | 23 | **40** | 74 | 128 | 259 | 563 |

First-party source is a proxy for agent-authored source, not the same
thing; agents plausibly write *smaller* functions. Treating first-party as
the reference therefore biases every cost figure **upward**, which is the
safe direction for a "when does eager become unacceptable" question.

## Measurements

### The baseline everything is compared against

`sci/fork` on a base with no corpus: **225 ns** (min 180, max 330). Forking
is free. Every millisecond below is installation, not forking.

Empty forks retain **362 bytes each** (50 forks, 18 KB total).

### Install cost is linear in defn size

`w4_install_scaling.clj`, 500 independent defns per size, median of seven
rounds:

| reader nodes | chars | per-defn install |
|---|---|---|
| 34 | 149 | 22.7 µs |
| 44 | 188 | 24.2 µs |
| 64 | 266 | 27.7 µs |
| 104 | 422 | 34.4 µs |
| 184 | 752 | 55.6 µs |
| 344 | 1424 | 92.6 µs |

Fit: **≈ 20 µs fixed + ≈ 0.21 µs per reader node.** At the measured src/
median (40 nodes) that is **≈ 24 µs**; at p90 (128 nodes) **≈ 37 µs**; at
p99 (259 nodes) **≈ 65 µs**.

These defns are plain `let` chains. The main probe's corpus uses `reduce`,
`into {}`, `->>`, `mapv` and anonymous fns, and costs more per node —
59–63 µs at 130 nodes where the simple shape costs 37 µs. **The honest
per-defn band for realistic agent code is 25–65 µs**, and idiom density
matters about as much as size.

### Eager: re-create all N in every turn fork

`w4_fork_install_cost.clj`. Three synthetic corpora, all measured against
the src/ distribution above; note that even the "small" generator lands at
130 nodes (≈ src/ p90), so these are conservative:

| corpus | N | median nodes | forms pre-read | reading + eval | per-defn (pre-read) |
|---|---|---|---|---|---|
| small (130 nodes) | 100 | 130 | **6.3 ms** | 8.2 ms | 63 µs |
| small | 1000 | 130 | **59.2 ms** | 88.8 ms | 59 µs |
| mid (224 nodes) | 10 | 224 | **2.7 ms** | 3.5 ms | 271 µs |
| mid | 100 | 224 | **17.0 ms** | 18.2 ms | 170 µs |
| mid | 1000 | 224 | **119.7 ms** | 141.8 ms | 120 µs |
| large (553 nodes) | 100 | 553 | **35.5 ms** | 42.8 ms | 355 µs |

Reading the durable `:seon.fn/source` string rather than starting from a
cached form adds **7–33 %**. Caching read forms per cluster is a real but
second-order saving; it does not change any decision below.

The N=10 row is noise-dominated (one round is 10 installs; the per-defn
figure is inflated by residual warmup) — read N=100 and N=1000 as the
signal.

**Scaling is linear in N**, as expected: 10× the corpus is ~10× the time
(59 ms → 119 ms is sublinear only because the mid corpus is bigger per
defn).

### Eager memory: the number that actually decides this

Forks held live, four GC rounds:

| corpus | forks | retained | per fork | per defn per fork |
|---|---|---|---|---|
| empty | 50 | 18 KB | 362 B | — |
| small N=1000 | 10 | 125 MB | **12.5 MB** | 12.5 KB |
| mid N=100 | 50 | 118 MB | **2.36 MB** | 23.6 KB |
| mid N=1000 | 10 | 232 MB | **23.2 MB** | 23.2 KB |

**An interpreted defn costs 12–24 KB of retained analyzed nodes, per
fork.** This is the cost eager cannot amortize: it is paid again by every
concurrent turn. Ten concurrent turns over a 1000-function corpus is
125–232 MB of interpreter node trees that are byte-for-byte the same
program.

### Lazy: install only what the turn calls

Two shapes, because they behave completely differently:

**Flat corpus** (every function independent) — the best case:

| corpus N | entry points k | installed | total |
|---|---|---|---|
| 1000 | 1 | 1 | **0.15 ms** |
| 1000 | 5 | 5 | **0.76 ms** |
| 1000 | 20 | 20 | **2.34 ms** |
| 100 | 5 | 5 | **0.43 ms** |

Lazy is independent of N and linear in k, at the same 25–65 µs per defn.

**Chained corpus** (`f_i` calls `f_(i-1)`) — the realistic case for an
agent that builds on its own committed functions:

| corpus N | entry points k | installed (closure) | total |
|---|---|---|---|
| 1000 | 1 | 501 | **66.1 ms** |
| 1000 | 5 | 834 | **103.5 ms** |
| 1000 | 20 | 953 | **114.5 ms** |

At k=20 the closure is 95 % of the corpus and lazy has become eager with
extra bookkeeping.

The reason is a hard sci property, probed:

> `(defn a [] (undefined-callee 1))` → **refused at definition**,
> `"Unable to resolve symbol: undefined-callee"`.

**sci resolves callees when the definition is analyzed, not when it is
called.** So a lazy installer cannot install one function; it must install
that function's transitive callee closure, computable from
`:seon.fn/calls` in the program graph. Lazy's win is therefore a property
of the *call graph's shape*, not of laziness.

### Call cost, for amortization

One call of a mid-sized installed interpreted function (two frames deep):
**7.3 µs**. So installing one function costs about as much as 3–8 calls of
it. Installation is not amortized away by a turn that calls its functions
a handful of times; it is amortized only if the alternative is installing
functions the turn never calls.

## The correctness hazard, demonstrated (part c)

`w4_base_pinning_hazard.clj`, using the Phase 0 technique (the maintained
fork's `:call-preparation-hook` reading `:seon.env/environment` off the
runtime ctx). A host leaf `my/who-am-i` declares one turn-scoped member
(`:seon.cluster.agent/id`) and one cluster-scoped member
(`:seon.db/connection`); a program function `(defn report-identity []
(my/who-am-i))` is evaluated into the **base** ctx exactly as `acquire!`
does today, then called from a turn fork carrying a different environment.

**1. Silently wrong, for turn-scoped members only.** Base environment has
`:agent-BASE-PLACEHOLDER`; the fork's environment has `:agent-TURN-7`.
Result:

```clojure
#:saw{:agent :agent-BASE-PLACEHOLDER, :connection :conn-CLUSTER-A}
```

`:silently-wrong? true` for the agent id; `:cluster-member-still-correct?
true` for the connection — the base and the fork agree on it by
construction. Re-evaluating the same `defn` inside the fork returns
`:agent-TURN-7`, `:correct? true`.

**2. Loud, if the member is absent from the base.** With the base
environment carrying only cluster members, the same call short-circuits
without entering the callee:

```clojure
#:seon.error{:kind :seon.env/unavailable
             :message "Cannot call my/who-am-i: [:seon.cluster.agent/id]
                       absent from the environment on the runtime ctx."}
```

This is a design lever, not an accident: **whether the failure is silent
or loud is decided entirely by what boot puts in the base environment.**

**3. No cross-cluster leak through forking.** Cluster A's base and cluster
B's base are different ctx objects; forks of each resolve
`:conn-CLUSTER-A` and `:conn-CLUSTER-B` respectively,
`:cross-cluster-leak-via-forks? false`. The only cross-cluster path is a
**function object** crossing clusters (probed: A's fn object called
against B's fork still returns `:conn-CLUSTER-A`), which the sci report's
standing invariant already forbids — fn objects never cross a turn
boundary; they round-trip through source.

**4. `sci/fork` shares every non-`:env` ctx key by identity.**
`fork` is `(update ctx :env (fn [env] (atom (assoc @env :sci/generation
…))))` and nothing else
(`reference-code/sci/src/sci/core.cljc:340-346`). Probed: an atom on the
base ctx **is** the atom on the fork (`:same-atom-identity? true`), and a
fork's `swap!` is visible to the base (`:base-sees-forks-write? true`).

This is a live trap for the lazy option. `seon.sci.kernel`'s
`::installed-functions` atom (`src/seon/sci/kernel.clj:139-161`) is the
existing lazy-install bookkeeping, and it is **one set shared by the base
and all its forks**. A lazy installer built on `ensure-function!` as it
stands would see a base-installed function as already installed and skip
re-creating it in the fork — reproducing precisely the hazard it exists to
fix. The installed-set must become per-fork.

## Can eager leave the critical path? (pre-warming)

`w4_prewarm_viability.clj`. Eager's 60–120 ms only hurts if the turn waits
for it, so: can a fork be pre-installed in the background and given its
environment at turn start?

- **`assoc` the environment after installing: FAILS.** The function pins
  the pre-`assoc` ctx object, so it reads `:agent-PREWARM`, not
  `:agent-TURN`. `:works? false`.
- **A per-fork holder installed before the corpus: WORKS.** Put a
  `volatile!` under `:seon.env/environment` when the fork is created,
  install the corpus, then `vreset!` it at turn start. Reads
  `:agent-TURN`, `:works? true`, and the same fork serves a second turn
  correctly (`:reusable-across-turns? true`).
- **Holders stay isolated.** 16 pre-warmed forks, 16 holders, 16 virtual
  threads: `:all-correct? true`, no mismatches.

So eager's *latency* is escapable, at the price of one mutable box per
fork. Two honest caveats before anyone reaches for it: the memory cost is
**not** escapable (a warm pool of ten forks over a 1000-function corpus is
still 125–232 MB), and the box must be read through one owner — under the
PRD's mutability rule it qualifies as invocation-local coordination only
if it is created with the fork, written once at turn start, and never
consulted as a "current environment" slot by anything else.

## The knee

Where eager becomes unacceptable, for a turn-latency budget and a
concurrency level:

| corpus N (mid-sized defns) | eager per fork | 10 concurrent turns |
|---|---|---|
| 10 | ~1.7 ms | ~0.24 MB |
| 100 | **17 ms** | 24 MB |
| 250 | ~30 ms | 59 MB |
| 500 | ~60 ms | 118 MB |
| 1000 | **120 ms** | **232 MB** |

- **Below ~100 functions**, eager is defensible: 17 ms of turn latency and
  2.4 MB per fork are lost in the noise of a model call.
- **Around 250–500 functions**, eager starts costing 30–60 ms per turn and
  tens of MB per concurrent turn. This is the knee for latency.
- **At 1000 functions**, eager is 120 ms per turn and 23 MB per fork.
  Memory is the binding constraint before latency is: at the parallel-suite
  concurrency the test-infrastructure spec targets, this is hundreds of MB
  of duplicated interpreter node trees.

The memory knee arrives earlier than the latency knee, and it arrives at a
corpus size an accreting agent reaches quickly. Eager is not a design that
survives the system's own goal.

## Recommendation

**Hybrid, in three parts.** In dependency order:

### 1. Base pins the cluster, never the turn — and this is enforced by construction

The base ctx's environment carries **only cluster-scoped members**:
`:seon.db/connection`, `:seon.schema/projection`,
`:seon.boot/cluster-name`, and the capability handles. It carries **no**
`:seon.cluster.agent/id`, `:seon.cluster.run/id`,
`:seon.cluster.run.form/ordinal`, and no evidence sink.

This costs nothing and converts the entire silent-wrongness class into a
loud flat error (measured above: absent ⇒ `:seon.env/unavailable`,
callee not entered). It also means the *majority* of a corpus — every
function that declares only cluster-scoped members, or none — is correct
when installed once into the base, exactly as today. Zero per-fork cost
for those.

Because the base is per-cluster, no amount of pinning can leak across
clusters. That risk was assumed by the open question and is refuted.

### 2. Re-create in the fork only the turn-scoped closure

The set to re-create is a query, not a list: the functions whose
`:seon.fn.arity/input-refs` declare a turn-scoped member, **plus their
transitive callers** (a caller of a turn-scoped function must itself be
re-created, or it will call the base-pinned copy) — computable over
`:seon.fn/calls`, the same edges `seon.fn/tests-reaching` already walks.
Note the direction: sci's definition-time resolution forces the *callee*
closure to exist, but a fork re-creating a function must also re-create
everything that *calls* it.

Cost: k × 25–65 µs where k is the size of that closure, plus 12–24 KB per
function per fork. If turn identity is declared by a handful of leaves —
which is the expected shape, since agent id and run id are wanted by
recording and messaging surfaces, not by ordinary computation — k is
small and the per-turn cost stays well under a millisecond. **If that
closure ever approaches the whole corpus, the design has a different
problem** (too many functions declaring turn identity) and the query will
say so loudly, which is the point of deriving it.

The installed-set bookkeeping must move off the shared ctx atom:
`::installed-functions` is one set for a base and all its forks
(measured), so it becomes per-fork state created with the fork.

### 3. Keep pre-warming in reserve, do not build it now

The holder mechanism works and is isolated 16/16, so if the turn-scoped
closure ever does grow large, eager's latency can be moved off the
critical path without redesigning anything. But it buys latency only, not
memory, and it introduces a mutable box. Build it when a measurement asks
for it, not before.

### What this rejects, and why

- **Pure eager** — 120 ms and 23 MB per fork at N=1000, paid by every
  concurrent turn, to fix a hazard that affects a minority of functions
  and (with part 1) fails loudly rather than silently.
- **Pure lazy** — correct, but its advertised win depends on the call
  graph being flat. Probed: sci refuses an unresolved callee at definition
  time, so a chained corpus makes lazy cost 66–114 ms at N=1000. A design
  whose cost is 0.15 ms or 114 ms depending on how the agent happened to
  factor its code is not a design anyone can reason about.

### What the Phase 2 conversation still has to decide

1. Is the cluster-scoped / turn-scoped split of the environment's members
   exactly as listed in part 1? The PRD's member list does not currently
   mark them, and part 1's guarantee is only as good as that marking. It
   should be a declared property of each member, queryable — not a
   convention.
2. `:seon.db/db` is listed in the PRD as "supplied at basis by the
   provider, not stored". Under part 1 that is automatically safe (the
   provider derefs `(d/db connection)` at call preparation, and the
   connection is cluster-scoped). Worth confirming explicitly, because if
   a *pinned* database value ever lands in the base environment it becomes
   the silent-staleness case again.
3. Whether the turn-scoped closure is computed once per cluster commit
   (it is program-graph data, identical across forks — the same argument
   Phase 0 made for gating hook consultation) or per fork. Once per
   commit, cached on the projection, is the obvious answer and shares
   machinery with the S1 plan gating.

## Probe inventory

Preserved under
[`env-phase1-w4-probes/`](env-phase1-w4-probes/) with a
[reproduction runbook](env-phase1-w4-probes/RUN.md); worked from copies in
`tmp/env-probes/`, which is where a rerun should put them. Each exposes a
`run` returning one data map.

| file | what it measures | result |
|---|---|---|
| `w4_corpus_shape.clj` | realistic defn size, from `src/` with the reader | 1428 defns; median 336 chars / 40 nodes, p90 1232 / 128 |
| `w4_install_scaling.clj` | install cost vs defn size | 20 µs + 0.21 µs/node; 24 µs at the median |
| `w4_fork_install_cost.clj` | fork baseline, eager totals, lazy flat vs chained, call cost, memory | eager N=1000: 120 ms / 23 MB per fork; lazy flat k=5: 0.76 ms; lazy chained k=5: 103 ms |
| `w4_base_pinning_hazard.clj` | the hazard scoped: turn vs cluster members, cross-cluster, fork key sharing | silent for turn-scoped, loud when absent, no cross-cluster leak, `::installed-functions` shared |
| `w4_prewarm_viability.clj` | can eager leave the critical path | `assoc`-after-install fails; per-fork holder works, 16/16 isolated |

Two of these are graduation candidates as class regressions rather than
probes, and both assert a property no current test claims:

- **the base environment contains no turn-scoped member** — a query over
  the constructed base, asserting the loud-refusal property part 1 depends
  on. This is the one that keeps the whole recommendation honest.
- **`sci/fork` shares non-`:env` ctx keys** — a regression pinning the
  reason per-fork state cannot live in a plain ctx atom, so the next
  person to add one is stopped by a test rather than by a silent
  cross-fork read.

## Reported friction and ugly output

1. **`bin/seon init` fails with a raw Malli internal.** The whole
   diagnostic is `✗ :malli.core/invalid-schema` followed by
   `{:type :malli.core/invalid-schema, :message :malli.core/invalid-schema,
   :data {:schema :seon.sci.kernel/arm, :form :seon.sci.kernel/arm}}`.
   Nothing names the *function* whose `:malli/schema` referenced the
   unregistered key, the file, or the line — the operator is told a
   keyword is invalid and left to grep for it. A publication refusal
   should say which declaration is unsatisfied and where it is written.
   (The underlying breakage is a sibling lane's uncommitted
   `src/seon/sci/kernel.clj` edit, reported above; the *face* is a
   standing defect independent of that.)

2. **`bin/seon --root … status` prints eight `record unreadable … The
   external claim is invalid.` lines from the SHARED root's claim
   directory** (`data/operator/claims/roots/*.edn`) while operating an
   isolated root under `tmp/`. Two problems in one face: the message is
   cryptic ("external claim is invalid" says nothing about what is wrong
   or whether the operator should care), and a root-scoped command is
   surfacing another root's bookkeeping — which is exactly what `--root`
   exists to make unreachable.

3. **`bin/seon --root PATH start` refuses a non-existent root with
   `✗ --root requires an existing isolated operator-root directory.`**
   Correct, but it does not say to create it, and every other operator
   command creates what it needs. Cost one cycle.

4. **The sci arity error still names the host function, not the sci Var
   or the call site.** Carried over from Phase 0 and hit again here while
   shaping the hook probes; unchanged, and worth fixing when the hook
   lands.

5. **`Unable to resolve symbol: undefined-callee`** (sci, at definition
   time) is the right refusal but gives no location — no line, no
   enclosing defn. For an agent that just committed a function, "which of
   my definitions has the unresolved name" is the whole question. This one
   is upstream in the maintained fork and is a small, real improvement.
