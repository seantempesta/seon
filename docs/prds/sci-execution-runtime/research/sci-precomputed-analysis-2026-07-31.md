---
type: research
status: active
tags: [research, sci, caching, falsification]
---

# Precomputed sci analysis for first-party execution — measured

Falsification lane, 2026-07-31. Question from plan ruling #21 and its
who-for addendum: can first-party functions run INTERPRETED from corpus
source, with the sci analysis derived once per basis, so that agent-path
execution recovers total interrupt coverage and basis fidelity while
keeping "agents call any function"?

**Verdict: feasible, and CHEAPER AND SIMPLER than the question assumes —
but not as an "AST cache".** The reusable unit is not the analyzed node;
it is the fully EVALUATED base ctx. Forks inherit it for ~40 ns. The
whole first-party corpus installs interpreted in ~490 ms cold / ~215 ms
warm, retains ~3 MB once per process, and costs +21 % on the heaviest
agent-path function measured. Two sci mechanics make the naive
"pre-analyze, eval later" shape actively WRONG, and both are recorded
below with reproductions.

Sci revision under measurement: `reference-code/sci` @ `1305a90a`
(2026-07-29). All probe scripts are committed under
`tmp/sci-precompute/` (`bench.clj`, `p1_reuse.clj`, `p1b_leak.clj`,
`p2_interpreted.clj`, `p3_interrupt.clj`, `p4_corpus.clj`,
`p5_classes.clj`, `p6_final.clj`, `p7_scale.clj`). Machine: this laptop,
`clojure -M:dev`, JDK 26, `-Xmx512m`.

## 0. Headline numbers

| Measurement | Value | Probe |
|---|---|---|
| `seon.render.walk/neighborhood` (distance 2) compiled | **3 407 µs** | p2 |
| the same walk fully interpreted, compiled deps host-bound | **4 121 µs (+21 %, +714 µs)** | p2 |
| `walk/transacted` (pure, mid-size) compiled → interpreted | 0.17 → 0.52 µs (3.1×, +0.35 µs) | p2 |
| `walk/family` (small, calls compiled `schema/matching-shapes`) | 7.29 → 7.25 µs (no measurable delta) | p2 |
| interpreted loop calling compiled `d/q`, 200 ms limit | **stopped at 207 ms** (+7 ms), 80 536 fn entrances | p3 |
| the same loop COMPILED (today's shape) | **0 entrances, ran past the deadline to completion** | p3 |
| install the whole first-party corpus interpreted (51 files) | **489 ms cold / 215 ms warm** | p6 |
| retained heap of the interpreted corpus | **~1.6–4.3 MB** (one per process) | p5/p6 |
| `sci/fork` | **0.04 µs** | p7 |
| corpus size | 1 503 `:seon.fn` rows (1 496 with source), 189 `:seon.ns`, 1.17 MB source, median fn 391 chars | p4/p7 |
| first-party fns containing a host interop form | **169 / 1 496 = 11 %** | p7 |

## 1. What is actually cacheable

### 1.1 The eval path, from source

`sci/eval-string*` is a loop of `parser/parse-next` + `eval-form`
(`reference-code/sci/src/sci/impl/interpreter.cljc:99-109`). `eval-form*`
does the analysis and the evaluation in one place
(`interpreter.cljc:44-62`): it makes a fresh `gensym` parent, a
`:closure-bindings` volatile, calls `ana/analyze ctx form true`, sizes an
`object-array` from the recorded binding count, and finally calls
`(types/eval analyzed ctx bindings)`.

That last line is the whole answer to "is the analyzed value reusable":
**`ctx` and the binding array are EVAL-TIME ARGUMENTS, not closed over by
the node.** An analyzed node is an object implementing `types/eval`; the
binding-array size is the only extra datum you must retain with it.
`bench/analyze-form` and `bench/eval-node` in `tmp/sci-precompute/bench.clj`
are that split lifted verbatim.

### 1.2 Measured: analysis is NOT the expensive phase

Probe p1, one ctx, `(+ 1 2)` (50 000 reps):

```
full eval-string   2.49 µs
eval-form (parsed) 0.63 µs
analyze only       0.69 µs
cached-node eval   0.13 µs
```

So for a trivial form the pipeline is roughly **reader 1.86 µs (75 %),
analysis 0.50 µs (20 %), evaluation 0.13 µs (5 %)**. Caching the analysis
saves 0.5 µs; caching the READ saves nearly four times as much. This
matches `sci-door-ctx-sharing-2026-07-31.md` (full evaluate 100.8 µs,
reader/program-row pipeline ≈ 40× the real work): **the reader, not the
analyzer, is the pipeline's cost.**

For a form that does real work the analysis vanishes into the noise —
`(fib 18)`, 2 000 reps:

```
full eval-string   130.4 µs
eval-form (parsed) 114.5 µs
analyze+eval each  115.9 µs
cached-node eval   118.4 µs
```

Analyze-each-time and cached-node are within measurement noise of each
other. **An analyzed-node cache buys ~0.5 µs per form. It is not the
mechanism that pays for this design.**

### 1.3 What the node closes over — two falsified assumptions

**(a) The node captures the ANALYZING ctx's Var objects.** A symbol in
value position analyzes to `(sci.impl.types/->Node (faster/deref-1 v) …)`
where `v` is the Var resolved at analysis time
(`analyzer.cljc:2276-2298`). Probe p1e: analyze `(fib 18)` against ctx
`c1`, evaluate the node against a *separately built* `c2` that has the
identical source; then redefine `fib` on `c1` only:

```
eval cached c1-node on c2:                     2584
after redefining fib on c1, node eval'd on c2: :C1-REDEFINED
```

The node followed `c1`. **A node cached across a REBUILT ctx at the same
basis is silently wrong** — no exception, just another program's answer.
Node reuse is valid only for the analyzing ctx and its FORKS (p1c: a
base-analyzed node evaluates correctly on two forks, because `sci/fork`
copies the env map into a new atom but shares the Var objects,
`core.cljc:318-323`).

**(b) Analysis of a `def`/`defn` MUTATES the analyzing ctx.**
`analyze-def` calls `init-var!` at analysis time, which interns a fresh
Var into the analyzing ctx's env (`analyzer.cljc:765-790, 799-810`), and
`eval-def` then `bindRoot`s that *shared* Var object
(`evaluator.cljc:25-47`). Probe p1b:

```
control (def analyzed AND evaluated on the fork)
  fork sees :from-fork    base sees: unresolved   <- correct isolation

precompute shape (def ANALYZED on base, evaluated on one fork)
  f1 sees :A
  f2 sees :A   <- never evaluated it
  base sees :A <- leaked
```

**Pre-analyzing agent-authored `def` forms against the shared base
destroys per-agent isolation.** Any pre-analysis cache must therefore be
restricted to code that is *supposed* to be shared (the first-party
corpus), and agent-authored source must continue to be analyzed on the
agent's own ctx.

### 1.4 The real reusable unit

Since a fork shares Vars with its base and costs 0.04 µs, and since
evaluating a `defn` is what creates the callable interpreted fn, the
correct shape is:

> Evaluate the interpreted corpus ONCE into a per-basis base ctx.
> Every agent fork inherits every interpreted first-party fn for free.

No node cache, no invalidation of individual nodes, no binding-array
bookkeeping. The "sci cache" the owner asked for is a **ctx holder**, not
an AST store.

## 2. The cost of interpreted first-party code

Probe p2 installs the entire `src/seon/render/walk.clj` source into a sci
ctx whose nine required namespaces are host-bound, then calls the
interpreted `neighborhood` against a real in-memory database seeded with
`seon.test-support/with-database` + `seed-cluster!` + one agent. The
interpreted result is `=` to the compiled result (asserted in the probe).

```
install (parse+analyze+eval of the whole 833-line file):  ~89 ms

neighborhood, distance 2   compiled 3407 µs   interpreted 4121 µs  (+21 %)
transacted (pure)          compiled 0.17 µs   interpreted 0.52 µs  (3.1×)
family (calls compiled)    compiled 7.29 µs   interpreted 7.25 µs  (0 %)
```

The pattern is the important part, and it is the mixed boundary doing its
job: **interpretation taxes only the interpreted nodes.** `family` spends
its time inside compiled `schema/matching-shapes` and shows no
measurable tax at all. `neighborhood` spends most of its time in compiled
Datahike pulls, queries and compiled renderers, so a 3.1× tax on its own
glue code shows up as +21 % end to end. `transacted` — pure map
transformation, the worst case — is 3.1×, but 3.1× of 0.17 µs.

### 2.1 Per-turn budget (ruling #21 addendum)

Under the who-for policy, context assembly is agent-path code and runs
guarded, so **+714 µs per neighborhood walk is a PER-TURN cost for every
agent**, not an occasional agent-dive cost. Stated plainly:

- one guarded walk per turn: **+0.7 ms/turn**;
- ten walks per turn: **+7 ms/turn**;
- against a model round trip measured in seconds, both are < 0.5 % of
  turn latency, and both are smaller than the ~17 ms cluster fork.

The mitigation the addendum names is the correct one and it dominates
this number: the call-grain render cache makes a cached call skip
execution entirely, so the interpreted tax is paid only on the calls that
actually re-execute. **The per-turn interpretation budget is not the
reason to reject guarded assembly; it is a rounding error next to one
uncached walk.**

## 3. Interrupt coverage — the gain, measured

Probe p3. An INTERPRETED `loop/recur` calls compiled `datahike.api/q`
every iteration; the guard is the same shape as
`seon.sci.eval/interrupt-guard` (`src/seon/sci/eval.clj:260-280`); the
deadline flag flips at 200 ms.

```
one interpreted iteration (incl. the compiled d/q):  6 780 µs
outcome:                                             :interrupted
deadline 200 ms, actually stopped at:                207 ms  (+7 ms)
interpreted fn entrances during the run:             80 536
```

Control — the same loop as a COMPILED host fn called from sci, which is
exactly today's ruling-#20 host-binding shape:

```
outcome: completed (never interrupted)   interpreted entrances: 0
```

The overshoot is bounded by one host call (`d/q` here ≈ 6.8 ms;
observed overshoot 7 ms), which is the honest residual: the interrupt
fires at interpreted fn entrances (`fns.cljc:52,77`;
`reference-code/sci/doc/interrupt.md:6,50`), so a runaway *inside a single
compiled call* is still bounded only by the submit-level wedge backstop.
Interpreting the first-party glue converts "unbounded compiled loop"
into "bounded by one leaf call" — which is the entire point of the
ruling.

### 3.1 THE BLOCKER: a fn captures its creating ctx's interrupt-fn

`sci.impl.fns/fun` reads `(:interrupt-fn ctx)` at fn CREATION time, once,
outside the returned function (`fns.cljc:39-40, 63-64`). Probe p1g:

```
base-created fn, called from a fork:  base-guard hits 1   fork-guard hits 0
fork-created fn, called from a fork:  base-guard hits 0   fork-guard hits 1
```

`seon.sci.eval/fork` mints a NEW `interrupt-guard` per fork and assocs it
onto the forked ctx (`src/seon/sci/eval.clj:282-289`). **Therefore a
corpus installed into the shared base would run under the BASE's guard,
which no evaluation ever arms — every interpreted first-party call would
be completely unguarded, the exact opposite of the goal.**

The fix is a simplification, not a workaround: the guard is already
thread-scoped (`ThreadLocal` armed state, `eval.clj:263-278, 303-317`),
so **one process-wide stable guard shared by the base and every fork** is
both correct and strictly simpler than today's per-fork guard. Arming
stays per thread per evaluation. This must land before any base-installed
interpreted corpus.

## 4. Scale, precompute, memory, invalidation

### 4.1 Precompute

Probe p6, all 51 first-party `src/**/*.clj{,c}` files installed
interpreted into one ctx:

```
cold  489 ms
warm  215 ms   (second full pass, JIT hot)
```

Per-file worst cases: `seon/cluster/run.cljc` 38–47 ms,
`my/message.cljc` 26 ms, `seon/render/walk.clj` 22 ms. Against the
budgets: cluster fork ≈ 17 ms, boot target ≤ 10 s. **~0.5 s once per
JVM per corpus basis is acceptable**; ~0.5 s per cluster fork is not, and
does not have to be paid — a fork of an already-built base ctx is 0.04 µs
and inherits everything (§1.4).

### 4.2 Memory

Retained heap after installing the corpus: 1.6 MB, 2.9 MB and 4.3 MB
across three runs of p4/p5/p6 (GC-noisy; order **~3 MB**). This is ONE
per process, not per agent: `sci/fork` copies the env map into a new atom
(`core.cljc:318-323`), a shallow persistent-map copy, and the Vars and
their interpreted fns are shared. Per-agent marginal memory ≈ 0, which is
consistent with the measured ~8.5 KB per parked flow proc being the
dominant per-agent cost.

### 4.3 The invalidation key — one mechanism with the render cache

From §1.3(a): a stale node is SILENTLY WRONG. That rules out keying
individual nodes and re-attaching them to a rebuilt ctx. Key the CTX.

Argument for the same scalar `render-invalidation-caching-2026-07-31.md`
uses: Datahike already publishes, per commit, a map
`:datahike.cache/attribute-revisions {attr commit-id}` plus a fail-closed
`:datahike.cache/conservative-revision` for merges and schema commits
(that document §1.4, §3, `datahike/query.cljc:2568-2589`). The corpus's
attributes are `:seon.fn/source`, `:seon.ns/source` and the schema
attributes. So:

- the base-ctx holder is keyed by `[store-id branch]` plus the revisions
  of exactly those corpus attributes;
- an unchanged revision set means the existing base ctx is valid at this
  basis — inherit it;
- any change to a corpus attribute, and any conservative revision (schema
  commit, merge, branch move), rebuilds the base ctx;
- nothing else is a cache key, and there is no second invalidation
  mechanism to keep in sync with the render cache.

This also disposes of the "same basis, rebuilt ctx" hazard by
construction: two ctxs at one basis are never both live, because the
holder returns the one it built.

## 5. The mixed-boundary policy

### 5.1 The computed rule

- **Interpreted:** every function that has a `:seon.fn/source` row in the
  corpus — first-party (`:core` provenance) and agent-authored (`:agent`)
  alike. That is a query, not a list.
- **Host:** everything with no corpus row, i.e. third-party dependency
  internals, bound as sci namespaces. Outside the corpus by definition.
- **Fallback:** a corpus function whose analysis fails falls back to its
  compiled Var — LOUDLY (§5.4).

### 5.2 How often interop bites

Per-FUNCTION rate over the real corpus rows (p7): **169 of 1 496 fns
(11 %)** contain a host interop form. Per FILE the rate looks much worse
(23/51 with a naive ctx) purely because one interop form fails a whole
file — which is an artifact of file-granularity installation, not of the
design.

And most of that 11 % is not a real obstacle: sci resolves interop
against its `:classes` table, so the question is whether the class table
can be COMPUTED. Probe p5/p6 derived a class table from the source itself
(a crude regex over class-shaped tokens, 73 → 106 entries) and moved the
corpus from 28/51 to **37/51 files installing cleanly**, with every
residual failure in exactly two classes:

1. **classes the crude derivation missed** — all of them appear in
   `(:import …)` forms. The corpus already stores those as facts:
   `:seon.ns/imports` with `:seon.ns.import/local` and
   `:seon.ns.import/target-class` (`src/seon/sci/eval.clj:463-509`). A
   real implementation derives `:classes` from those rows and this class
   disappears. The probe's regex is the limitation, not the corpus.
2. **sci's `clojure.core` is missing Clojure 1.11/1.12 core functions.**
   Probe p6, against `sci.interrupt/clojure-core`:

   ```
   parse-long parse-double parse-uuid random-uuid abs update-vals iteration
     -> ALL MISSING ("Unable to resolve symbol")
   ```

   `namespaces.cljc:1781` copies `random-uuid` for CLJS only. This blocks
   first-party code (`seon/render/data.clj`, `seon/cluster/loop.cljc`,
   `seon/test/runner.clj`) AND it silently blocks agents writing ordinary
   modern Clojure. It is a gap in our own vendored sci fork and is
   trivially fixable there.

**Neither residual class is intrinsic.** With corpus-derived `:classes`
and the core gap closed, the expected interpreted coverage of the
first-party corpus is near-total, with a small genuine remainder
(reflection-heavy leaves like `seon.cluster.store`'s `FileChannel` lock
and `seon.sci.eval`'s own `ManagementFactory` — which must stay compiled
anyway, being the guard's own machinery).

### 5.3 A defect found in the CURRENT host binding

`install-loaded-first-party-namespaces!` binds `(ns-interns host-ns)` —
raw `clojure.lang.Var` objects (`src/seon/sci/eval.clj:636-660`). The
analyzer derefs only *sci* Vars (`analyzer.cljc:2276-2298`,
`(utils/var? v)`); a raw JVM Var falls through to `:else v`, so
interpreted code that reads a first-party **constant** receives the Var
OBJECT. Reproduced in p2 before the fix:

```
class clojure.lang.Var cannot be cast to class java.lang.Number
  at seon.render.walk line 592  ->  (quot (long tokens/chars-per-token) …)
```

Call position works (a Var is `IFn`), so today's live path has not tripped
it; value position does not. `sci/copy-var*` fixes it and was used for
every measurement in this document — but `copy-var*` derefs at copy time
(`core.cljc:111-136`), which SNAPSHOTS the value and therefore loses the
hot-reload liveness the current docstring claims ("a re-evaluated `defn`
changes the next host call without reacquisition"). Filed as
`docs/seon/issues/host-bound-first-party-vars-break-in-value-position.md`.
The interpreted-corpus design removes the whole trade-off for first-party
code, since first-party Vars stop being host-bound at all.

### 5.4 Loud fallback

A corpus function that cannot be installed interpreted must not silently
become a compiled call. At precompute time, each failure commits a fault
fact carrying the `:seon.fn/sym`, the sci error value, and the derived
class (missing class / missing core fn / other), routed through the one
`:seon.config/on-core-error` dial (R41: dev panics, prod degrades and
warns). The set of fallbacks is then a QUERY — "which functions are not
under the guard at this basis" — which is exactly the honesty ruling #21
demands, and it is also the work list for closing the gap.

## 6. Recommendation

1. **Land the one process-wide interrupt guard first** (§3.1). Replace
   the per-fork guard in `seon.sci.eval/fork` with one stable guard
   created with the process and shared by base and forks; arming stays
   thread-scoped. Without this the whole design is silently unguarded.
   This is a deletion, not an addition.
2. **Fix the vendored sci core gap** (§5.2.2): add `parse-long`,
   `parse-double`, `parse-uuid`, `parse-boolean`, `random-uuid`, `abs`,
   `update-vals`, `update-keys`, `iteration` to `sci.interrupt/clojure-core`
   / the fork's `clojure.core`. Small, independent, unblocks both the
   corpus and agent-written code.
3. **Build a per-basis base-ctx holder, not an AST cache** (§1.4, §4.3).
   One holder per `[store-id branch]`, keyed by the corpus attributes'
   Datahike revisions plus the conservative revision — the SAME scalar
   the render cache uses. It lives in the init phase and is unwound
   there, alongside the schema projection holder; a cluster fork
   inherits the ctx rather than rebuilding it. It is a `defonce` holder
   of derived state, not durable state: losing it costs 0.5 s.
4. **Derive `:classes` from `:seon.ns/imports` corpus rows** (§5.2). No
   hand list, ever. Same for the interpreted/host split: corpus
   membership is the rule.
5. **Keep agent-authored code analyzed on the agent's own ctx** (§1.3b).
   Never pre-analyze an agent `def` against the shared base.
6. **Do NOT build an analyzed-node cache.** It buys ~0.5 µs/form (§1.2),
   is silently wrong across ctx rebuilds (§1.3a), and is unnecessary once
   the base ctx is the cached unit. If a future measurement shows the
   pipeline is still reader-bound, cache the READ (75 % of the trivial-form
   pipeline), not the analysis.

### Acceptance measurements

- `neighborhood` interpreted-vs-compiled ratio ≤ 1.3× at distance 2
  (measured 1.21×), asserted by a recurring test, not a one-off lane run.
- An interpreted first-party loop that calls compiled Datahike stops
  within one leaf-call duration of its time limit (measured 207 ms for a
  200 ms limit with a 6.8 ms leaf).
- Zero interpreted first-party fns run with an un-armed guard: a test
  arms a fork, calls a base-installed interpreted fn, and asserts the
  fork's guard counted entrances (today: 0 — see §3.1).
- Base-ctx build ≤ 750 ms cold at the current corpus size, and a cluster
  fork adds < 1 ms.
- The fallback set is queryable and its size is asserted non-increasing
  across a basis.
- A generative round trip: for N corpus fns with pure schemas, the
  interpreted and compiled calls return `=` values.

## 7. Open, not measured here

- The true interpreted-coverage rate with corpus-derived `:classes` (this
  lane used a regex derivation and reached 37/51 files; the corpus rows
  should push it much higher — measure it when the derivation is real).
- Macro-bearing first-party namespaces: none of the measured files
  exported a macro consumed interpretively; `copy-var*` of a raw Clojure
  macro Var is untested here.
- Multi-basis processes: two clusters at different corpus bases each need
  a base ctx (~0.5 s, ~3 MB). Not a problem at today's scale; worth a
  bound if a process hosts many divergent bases.
