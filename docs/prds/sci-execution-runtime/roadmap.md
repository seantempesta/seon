---
type: prd
status: active
tags: [prd, agent, architecture, database]
---

# Sci execution runtime roadmap

## Outcome

Replace the execution child's self-host `cljs.js` engine with sci's
JIT-tier evaluation, exploring two variants to a measured decision:

- **Variant B — sci-JIT Bun children**: engine swap inside the existing
  per-agent child. Full semantic compatibility (native `^:async`/`await`
  over real Promises, js interop, agent macros); burst retention returns
  (~90 MB settled vs 416 MB permanent); small-form eval 10-16x faster;
  in-process interruption via `:interrupt-fn`.
- **Variant C — JVM sci agent host**: one JVM process beside the writer,
  a sci context per agent (22.7 KB marginal at N=100 via structural
  sharing), `Thread/interrupt` containment, database calls become plain
  synchronous calls over the existing UDS protocol's JVM client side.
  Covers the 42% pure + 46% db-boundary toolkit surface; the 12%
  js-bound surface stays on a Bun tier.

They are not exclusive: B is the safe engine swap with no topology
change; C is the deeper simplification decided on B's usage data. The
Bun client pod (web UI, LLM, loop, rendering) is out of scope and
remains the application host under every variant.

## Evidence base

[[../source-cleanup/research/sci-execution-child-feasibility-2026-07-20]]
(measured probe: retention, perf ratios, four semantic gaps, JVM context
sharing, port inventory, bb impossibility) built on
[[../source-cleanup/research/child-footprint-bisect-2026-07-20]] and
[[../source-cleanup/research/bun-shared-memory-options-2026-07-20]].
Reproducible harness: `tmp/sci-probe/`. Sci checkout at the JIT commit
(`45bcf0f`, reference only — sci is not yet a dependency).

## Known blockers (from the probe; each needs a closing gate)

1. General sci vs cljs.js semantic audit beyond the four probed gaps —
   drive the full agent test corpus (the eval/repl behavioral tests)
   through a sci engine before any cutover.
2. The 91 MB eager-schema band is orthogonal: fixed by lazy validator
   compilation at admission (register lever 3) — sequence it with or
   before B so the win is compounded, not attributed wrongly.
3. Retention re-proof at production anchoring (the probe anchored less
   live state than a real child).
4. Bundle-proportional ~60 MB floor for B — the child bundle must stay
   small; C makes the floor shared-once.
5. C only: GC blast-radius proof (OOME containment beyond one lucky
   run), the js-bound 12% tier design, and pod/host protocol for turn
   dispatch.
6. Sync contract: defs in a sci context must persist to the program
   graph through the SAME one corpus mechanism (no second registry);
   note sci value-defs actually improve on self-host here.

## Exploration order

### B1 — sci engine behind the existing eval boundary

Prototype `seon.eval`'s engine seam: the child hosts a sci context
armed with the same admitted bindings; the eval envelope, receipts,
`maybe-await-value`, augment-ns-source, and instrumentation flow
unchanged. Gate: the full existing CLJS eval/repl test selection green
against the sci engine in the harness (not yet wired into production);
divergence list written.

**Status: DONE — green with divergences (2026-07-20).** Evidence:
[[research/b1-eval-corpus-divergence-2026-07-20]]. The adapter
(`tmp/sci-probe/src/probe/adapter.cljs`) satisfies the production eval
envelope over `sci/eval-string+`; the ported corpus
(`src/probe/corpus.cljs`, 33 tests / 80 assertions naming their
production sources) is green 3/3 runs under the vendored bun.
0 blockers; 9 adapter-work items (error-prose synthesis,
warning→catch-site classification, binding-table provisioning replaces
guarded-load's bundle trick, sci resolution queries for
prose/preflight, instrumentation over sci vars, print-fn→ALS bridge,
setup-agent-ns! sci form, cljs.test-in-ctx, timeout prose);
5 improvements (value defs persist, in-process loop interrupt,
async-try quirk absent, direct defmacro, defs-as-data); 3 cosmetic.
Perf: 200-form burst 37–43 ms through the full envelope path vs
143 ms self-host (raw sci 8.8–13.6 ms).

### B2 — retention + perf at production anchoring

One real agent driven end-to-end on a sci child (branch cluster):
memory per phase, burst retention, turn latency vs today's child. Gate:
retention returns at production anchoring; no eval-latency regression.

### C1 — JVM host skeleton

The probe's JVM harness grown to: sci context per agent, admitted
bindings loaded once and shared, UDS client to the writer, thread-per-
eval with interrupt + deadline. Gate: N=100 contexts, one real turn's
worth of eval work each, marginal-memory and interrupt proofs repeated
at that scale; OOME blast-radius test repeated 20x.

### C2 — tier split design

The js-bound 12% inventory hardened into a computed rule (which agent
programs REQUIRE a JS runtime — detectable from their require/interop
surface, never a hand list); dispatch design for pure/db agents to the
JVM host and js-bound agents to a Bun child; one sync contract across
both.

### Decision gate

B vs B+C ruled by the owner on: B2's production numbers, C1's scale
proofs, and the measured share of live agent programs that are
js-bound. Architecture docs and the one-mechanism table update ride the
decision, not the exploration.
