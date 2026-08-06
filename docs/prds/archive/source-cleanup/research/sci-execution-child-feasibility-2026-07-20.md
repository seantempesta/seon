---
type: research
status: active
tags: [research, agent, architecture]
---

# sci execution-child feasibility (2026-07-20)

Can a sci-based execution runtime replace the self-host cljs.js child?
Live code and measurements, two variants: sci-in-Bun (with the new JIT
tier) and a JVM sci agent-host (owner extension: the JVM is already
resident as the database server, so a JVM-side sci host could be a
simplification with true sharing). Baseline problem, from
[[child-footprint-bisect-2026-07-20]]: the self-host child costs
~90 MB program load + ~91 MB eager admission, 180 MB idle-ready,
220 MB after one prompt+eval, and one heavy turn permanently inflates
it to 416 MB. Bare vendored-bun floor is 5.9 MB.

## Harness (reproducible)

All probes live in `tmp/sci-probe/` (committed with `git add -f`;
`tmp/` is gitignored):

- `deps.edn` + `src/probe/main.cljs` — the Bun harness: CLJS 1.12.145,
  sci from `reference-code/sci` (`:local/root`; HEAD `be4021d`,
  containing the JIT commit `45bcf0f` and the ctx-scoped
  `:unrestricted` from #1065), malli 0.20.0, and the real compiled
  `seon.ai.tokens` + `seon.schema` in the binding table. Built with
  `build.sh` (`cljs.main -t nodejs -O simple`, 3.7 MB bundle), driven
  by `run.sh`: stdin-gated phases, external `vmmap` per phase against
  the vendored `reference-code/bun/build/release/bun` (same binary as
  the bisect), `MIMALLOC_OS_TAG=240` for honest labels.
- `src/probe/dbg.cljs`, `src/probe/dbg2.cljs` — isolated async-gap and
  interrupt probes.
- `jvm/` — the JVM sci host probe (same phase protocol, `jvm/run.sh`,
  `-Xmx512m`).
- `bb/probe.bb` — the babashka boundary demo.
- `inventory.bb` — the `src/my` port-cost classifier.

Self-host comparison numbers were measured on the live default pod
through the one production mechanism (`seon.eval/eval` against
`@seon.repl/!compile-state` after `dev-init!`, best-of-5 per form,
config singleton passed as the third argument).

## Variant A: sci-in-Bun

### Footprint per phase (vmmap Physical footprint, vendored bun)

| Phase | jit run | jit-off run | self-host child (bisect) |
|---|---|---|---|
| bundle loaded + sci ctx, settled | 59.4 MB | 61.8 MB | 89.1 MB (artifact only) |
| + binding table warmed | 59.4 MB | 61.8 MB | 180.4 MB (ready) |
| after all workloads + gap probes | 100.0 MB | 100.9 MB | 220.8 MB (prompt+eval) |
| after 10× ~14 MB pr-str burst | 94.3 MB | 92.7 MB | 416.3 MB |
| post-GC retention (final) | **89.2 MB** | **90.6 MB** | **416.3 MB (permanent)** |

- **Burst retention RETURNS.** During the burst, JSC heap capacity
  reached 870–1035 MB and RSS 1.2–1.5 GB; after `Bun.gc(true)` + 2 s,
  JSC capacity fell to 37–44 MB and Physical footprint to ~90 MB —
  mimalloc dirty pages (Memory Tag 240) decommitted from ~300 MB peak
  to ~80 MB. The self-host child's fatal flaw (220→416 MB permanent;
  bisect finding 4, capacity 348 MB pinned) does not reproduce here.
  Caveat: this harness holds far less live state (17 MB live heap vs
  the child's 124 MB), so part of the difference is less anchoring —
  but the peak here was *larger* than the child's burst and still came
  back.
- **Baseline scales with the compiled bundle, not with sci.** The
  3.7 MB harness bundle (cljs.core + malli + seon.schema + sci) settles
  at ~60 MB; the child's 7.5 MB release bundle loaded to 104.6 MB. The
  executed-top-level band is proportional to how much compiled CLJS the
  child ships. A sci child wins by *keeping the compiled surface small*
  (runtime + toolkit host fns) — agent code becomes sci data instead of
  executed JS top-levels — not by anything intrinsic to sci.
- The ~91 MB admission band was NOT modeled here (no db session); it is
  orthogonal to sci-vs-self-host and would recur unless made lazy
  (lever 3 of [[bun-shared-memory-options-2026-07-20]]).

### Performance (best-of-N, same forms, vendored bun / live pod)

| Workload | self-host child (eval = compile+run) | sci interp | sci JIT | AOT-compiled CLJS |
|---|---|---|---|---|
| tight loop 1e6 | 2.20 ms | 21.8 ms | 3.9 ms | 0.25 ms |
| plan transform, 1000 nodes | 22.96 ms | 4.5 ms | 6.7 ms | 0.66 ms |
| heavy pr-str (~14 MB out) | 294.7 ms | 238.7 ms | 253.6 ms | — |
| 100 defns + 100 calls (200 forms) | 143.0 ms | 8.8 ms | 13.6 ms | — |

Honest reading:

- JIT proof holds: the tight loop is 5.6× faster with the JIT than
  interpreted (21.8→3.9 ms; `SCI_DISABLE_JIT=1` A/B on the same
  bundle). JSC gains are smaller than the ADR's node/V8 numbers (35×).
- Each sci row includes re-analysis (fresh `eval-string*` per
  iteration), so allocation-heavy shapes (plan transform, defn burst)
  show the JIT *slower* than the interpreter — template compilation
  cost with no warm reuse. Warm-invocation wins from ADR 0014 apply to
  repeated calls of already-compiled fns, which these evals never do.
- The decisive ratio for agent ergonomics is eval latency on ordinary
  forms: sci evaluates small forms ~10–16× faster than the self-host
  child's compile-per-form (143 ms vs 9–14 ms for 200 forms), while
  the child wins raw numeric throughput 1.8× (its output IS compiled
  JS). Mixed real work (pr-str-heavy) is a wash — dominated by
  compiled cljs.core either way.

### The four semantic gaps

1. **`^:async`/`await`: WORKS.** sci has native support (`^:async` fn
   meta + `await`, `sci.impl.async-macro` — transforms bodies to
   Promise chains over genuine `js/Promise`,
   `sci/impl/namespaces.cljc:1270-1293`). Measured in the harness:
   `(defn ^:async slow-inc [x] (let [v (await (js/Promise.resolve x))] (inc v)))`
   → calling it returns a native `js/Promise` resolving to 42; a
   host `^:async` db verb returns its envelope through one await
   (`{:seon.db/ok? true}` observed). Top-level bare `(await …)` fails
   ("Unable to resolve symbol: await") — the *same* restriction as
   self-host, so the `maybe-await-value` contract (eval once, await a
   Promise value into data) ports unchanged: eval returns the Promise,
   the host awaits it. `reference-code/partial-cps` is not needed for
   this contract; it stays relevant only if one wanted synchronous-
   looking await over a different execution substrate (it is the cljc
   CPS transform datahike's lean-cps PSS uses) — noted, not built.
   Harness-side trap found while probing: a `try` in expression
   position inside a *compiled-CLJS* `^:async` fn becomes an awaited
   async IIFE and silently unwraps Promise values — this bit the
   probe, not sci.
2. **Macros: WORK.** Core macros (`->>`, `cond->`, `when-let`)
   evaluate correctly in-ctx; user `defmacro` defined through sci eval
   works (`unless` probe → `:yes`). Finding: `src/my/**.cljs` contains
   ZERO `defmacro` (`rg defmacro src/my/` is empty; the only repo
   defmacros are JVM-side `seon.indexing`), so the agent-facing macro
   surface is cljs.core's — which sci reimplements — plus agent-authored
   macros, which sci supports natively (unlike self-host, where
   defmacro requires the two-pass macro-ns dance).
3. **Instrumentation: WORKS.** A malli-validating wrapper installed
   around a sci var from the host
   (`sci/alter-var-root` + `m/validate`/`m/explain` running as normal
   compiled CLJS) returns the errors-as-values envelope on bad input:
   `(add2 40)` → 42; `(add2 :kw)` →
   `{:seon/error {:seon.error/kind :seon.error/invalid-input …}}`.
   Wrappers survive because sci call sites deref vars per call
   (the JIT's var-epoch cache invalidates on `alter-var-root`).
4. **Var/namespace semantics: WORK, and are BETTER than self-host.**
   `(def counter 1)` then `counter` in a later eval reads 1 (the
   self-host cross-`eval-str` bare-value-def gap does not exist);
   redef of values and fns is visible immediately (JIT included);
   `(ns my.scratch)` + `(in-ns 'user)` + cross-ns `resolve` work;
   `ns-publics` enumerates every agent def as data (name → sci var
   with meta), so the program-graph sync model has a direct hook.

### Containment bonus (measured, `dbg2`)

sci's `:interrupt-fn` cancels a tight CPU loop **in-process**: a
`(loop [i 0] (recur (inc i)))` was stopped 200 ms into its budget, and
a sandboxed `try/catch` cannot swallow the interrupt
(`sci.interrupt/interrupt!`'s private marker; both probes returned
`:host-caught "budget exceeded"`). The self-host child documents the
opposite (`seon.eval` timeout caveat: "a tight CPU loop … can NOT be
cancelled here"). This weakens the case for process-per-agent being
the only containment shape.

## Variant B: JVM sci agent-host

Owner framing: a dedicated JVM sci host process (NOT inside the
writer; it speaks the existing UDS protocol like any client), so one
runtime family and true sharing.

### 1. Marginal footprint per context (measured, `jvm/`)

One JVM (`-Xmx512m`), shared "program" data built once (500 compiled
Malli schemas + a 20 k-entry program graph), then N contexts each
holding the shared binding table plus 10 own defns:

| Measure | Value |
|---|---|
| used heap, baseline (clojure + sci + malli loaded) | 22 MB |
| used heap after shared schemas + program | 22 MB |
| first agent context | ~1 MB |
| **marginal per context, N=100** | **22.7 KB** |
| used heap with 100 live contexts | 24 MB |
| process Physical footprint (Xmx512m) | ~300 MB |
| cross-context isolation (var leak check) | isolated |

The sharing that is impossible across Bun processes is free on the
JVM: persistent structures and compiled Malli validators are shared by
reference; a context costs tens of KB, not 180 MB. At N=100 that is
one ~300 MB process (footprint tracks -Xmx commit; used heap says a
much smaller cap would hold) versus 18–22 GB of Bun children.
100 contexts × the plan workload ran in 152 ms total.

### 2. Interruption and blast radius (measured)

- Runaway `(loop [i 0] (recur (inc i)))` on a thread:
  `Thread/interrupt` + an `:interrupt-fn` that calls
  `sci.interrupt/interrupt!` stopped it; join returned in 0 ms;
  thread dead. In-sandbox `catch Exception` could NOT swallow it
  (host received the interrupt).
- Memory bomb (`(vec (range 4e9))`): `OutOfMemoryError "Java heap
  space"` surfaced on the eval thread; the process survived and
  another agent context answered immediately after (`(agent-fn-0 20)`
  → 40, used heap back to 21 MB). HONEST caveat: OOME lands on
  whichever thread allocates as the heap fills — survival of the other
  99 agents is likely (the bomber holds the dominant allocation) but
  not guaranteed the way a per-child SIGKILL is. Heap pressure is a
  shared-fate axis that process-per-agent does not have. Native-crash
  blast radius is also process-wide, as with any shared host.
- Net: interruption is *stronger* than Bun Workers (cooperative-only)
  and roughly equal to sci-in-Bun's interrupt-fn; kill-certainty is
  *weaker* than process children.

### 3. Port-cost inventory

`src/my/**.cljs`, 137 public defns (heuristic classifier
`inventory.bb`, body-token based):

| Class | Count | Share | Port meaning |
|---|---|---|---|
| pure data (`:pure`) | 57 | 42% | portable as-is (`.cljc` rename) |
| db-boundary (`db/…` calls, most `^:async`) | 63 | 46% | `.cljc`-able: on the JVM the awaited-Promise contract becomes a plain **synchronous** protocol call or future — the async question inverts and gets simpler |
| genuinely js-bound (js interop / Promise idioms beyond db) | 17 | 12% | needs a real port (canvas/render string building is data; the js touches are mostly `js/Date`, `js->clj`, blob/fetch edges) |

Live agent-eval sample: the fresh default cluster holds only 11
`:seon.eval/source` rows, 6 js-bound — but those are this research
arc's own `bun:jsc`/`Bun.gc` probes, not organic agent work; the
sample is too small and polluted to carry weight. The `src/my`
inventory is the honest ground: about half the agent-facing surface is
db-boundary code whose JVM form is *simpler* (blocking calls), ~42%
moves unchanged, ~12% is a real port. Agent-authored `js/`-touching
history (canvas snippets, fetch) would not run on a JVM host —
canvas/UI agents stay CLJS-shaped.

bb calibration: a bb process with an extra sci context idles at
**10.1 MB** Physical footprint (`bb/probe.bb`, bb 1.12.212).

### 4. Transport

Confirmed reusable, nothing Bun-specific: the client side of the
database protocol is `src/seon/db/transport/uds.cljc` — JVM code
(`connect!` :261, `call!` :270, Transit + length framing over
`java.nio` UnixDomainSocketAddress) with explicit `:bb` reader
conditionals kept "loadable by the operator" (file header comment),
and it is already consumed from the JVM by
`script/seon/dev/restore_state.clj` and `script/seon/dev/branch.clj`.
A JVM sci host would use the exact client the operator already uses.

### The babashka question, answered definitively

bb evaluates **Clojure via JVM sci**; it cannot execute compiled CLJS
JS, and the packaged CLJS artifact is meaningless to it. Demonstrated
(`bb/probe.bb`):

| Form | Result under bb sci |
|---|---|
| pure plan-shaped transform | ✓ `{:ok 100}` unchanged |
| `(seon.db/transact! …)` with a sync host fn | ✓ `{:ok true}` — and no await needed |
| `(.then (seon.db/transact! …) …)` (today's Promise idiom) | ✗ "Method then … not allowed" |
| `(defn ^:async f [] (await …))` | ✗ "Unable to resolve symbol: await" (async transform is CLJS-only) |
| `(js/Date.now)` / `(.toFixed 3.14159 2)` | ✗ no `js` ns / no such JVM method |

So "run the packaged CLJS under bb" is a category error, but a
JVM-side sci agent runtime for pure-data + db-boundary workloads
(88% of the toolkit surface by the inventory above) is real and is
exactly variant B — with bb itself as evidence that a sci host with
the UDS client (its `:bb` conditionals exist for this) runs in ~10 MB.

## Three-column comparison

| Axis | self-host Bun child (today) | sci-JIT Bun child | JVM sci host (N agents / process) |
|---|---|---|---|
| idle-ready footprint | 180 MB | ~60 MB + session band (admission unmodeled) | ~300 MB total; **22.7 KB marginal/agent** |
| after heavy burst | 416 MB, permanent | ~90 MB, returns | 24 MB used heap, returns |
| N=100 steady | 18–22 GB (worst ~40 GB) | bounded by (60 + admission)×N | one process, ~300 MB-class |
| eval latency (200 small forms) | 143 ms | 9–14 ms | (not measured; JVM sci analysis is same family as bb: fast) |
| numeric throughput | compiled JS (2.2 ms/1e6) | 3.9 ms jit / 21.8 ms interp | JVM sci interp (no jit tier on JVM) |
| `^:async`/await | native CLJS | native sci (`^:async` + `await`), Promise contract ports | inverted: blocking calls / futures — simpler, but not source-compatible with js idioms |
| macros | two-pass self-host dance | user defmacro works directly | works (bb-proven) |
| instrumentation | malli wrappers on globals | malli wrapper on sci var, proven envelope | same, host-side |
| defs as data | compile-state + globalThis stash | `ns-publics` → vars with meta, direct | same |
| runaway CPU loop | NOT cancellable (documented) | interrupt-fn cancels, unswallowable (measured) | Thread/interrupt cancels, unswallowable (measured) |
| kill certainty | SIGKILL per child | SIGKILL per child | shared heap: OOM contained in probe, not guaranteed |
| js interop / canvas code | full | full (`:classes {'js …}`) | none — 12% of toolkit + any agent js history needs porting |

## Honest blockers (no recommendation — architecture is the owner's)

1. **sci is not cljs.js**: agent code today is compiled by the real
   CLJS compiler; sci is a reimplementation with its own divergences
   (e.g. the `==` path-dependence documented in ADR 0014). Any
   migration inherits sci's semantics, not CLJS's.
2. The ~91 MB **admission/projection band is untouched** by either
   variant; without the lazy-materialization lever a sci-in-Bun child
   still pays it per process (the JVM host dissolves it via sharing —
   that is variant B's structural win).
3. Variant A's ~60 MB baseline is **bundle-proportional**; a real sci
   child needs render + db session + toolkit compiled in, so its floor
   lands between 60 MB and today's 90 MB unless the require closure
   shrinks (same lever 2 as the bisect).
4. JVM host **loses js-native agent code** (12% of toolkit, all canvas
   `js/` snippets agents have authored) and changes the async idiom
   agents are trained on; it also reintroduces shared-fate memory
   pressure that per-child SIGKILL avoids.
5. The self-host child's **burst-retention pathology was not
   reproduced under an anchored live-sized heap**; the ~90 MB return
   here is strong but not proof that a production-sized sci child
   returns equally well.
6. Perf: sci-JIT does not reach compiled-CLJS speed on JSC (1.8× vs
   self-host on numerics); workloads that are genuinely loop-heavy
   regress unless warm fns are reused across evals.
