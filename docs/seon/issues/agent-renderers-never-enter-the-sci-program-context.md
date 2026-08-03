---
type: issue
status: open
severity: blocker
tags: [issue, render, sci, architecture]
---

# Run agent renderers through the guarded SCI program context

## Problem

The only render router resolves and invokes JVM Vars. Functions defined in the
cluster's live SCI program context therefore cannot render, even though the
settled design requires every agent-authored AI and HTML renderer to execute
through SCI's interrupt boundary.

The missing boundary also makes `seon.render.hiccup/raw` unsafe as a future
agent-render result: its safety documentation assumes an agent cannot call it,
while ruling #20 says every function is callable.

This finding is **in flight (schema-edn-consolidation lane)** because the two
render files are modified; their current diffs only update schema-resource
comments.

## Evidence

- `docs/prds/sci-execution-runtime/plan/README.md:1333-1352,1671-1682`
  requires guarded SCI execution for agent render and context code.
- `src/seon/render.clj:282-305,331-382` uses only `requiring-resolve` and direct
  JVM Var invocation.
- `src/seon/sci/eval.clj:71-92,1210-1233` owns a separate live SCI context.
- `src/seon/render/hiccup.clj:68-77` exposes public unescaped `Raw` construction
  while claiming agent-authored code cannot call it.
- A load-only probe defined `my.audit.renderer/render-ai` in SCI. Calling it in
  SCI returned `"SCI-only"`; the router returned
  `:seon.render/unresolvable` for the same symbol.

## Owner

The one `seon.render` projection-invocation boundary, composed with
`seon.sci.eval`'s cluster context and `seon.render.hiccup` admission.

## Acceptance

- A database/SCI renderer resolves at the cluster basis and executes under the
  one `:interrupt-fn` and time limit.
- A loop, throw, or refused output becomes a flat durable error with renderer
  provenance and cannot wedge a proc.
- HTML safety derives from the admitted grammar, not from a callability claim;
  an agent cannot emit unescaped bytes by calling `raw`.
- Hot-reloaded compiled system projections continue to use the same router,
  without a second render path.

## Owner design gate — 2026-08-02

The grounded options and recommendation are in
`docs/prds/sci-execution-runtime/research/agent-renderer-design-2026-08-02.md`.
The recommendation is one small guarded invocation kernel for every
agent-driven render, with definitions installed once in the cluster's live SCI
context, admitted semantic output on every cache miss, and no compiled
first-party bypass. `raw` remains a post-admission serializer composition
marker; calling it inside a renderer cannot carry unescaped bytes through the
admitted Hiccup boundary.

Load-only measurements on JDK 26.0.1 / 18 processors found:

- small arm → interpreted Var call → admission → disarm:
  10.250 µs p50 / 34.042 µs p95 for a trivial Hiccup value;
- full `seon.sci.eval/evaluate` for the same call:
  635.750 µs p50 / 941.208 µs p95 and about 1.40 MB allocated per call; and
- the small kernel admitting a 250-event Hiccup fixture:
  2.448 ms p50 / 3.337 ms p95 and about 10.9 MB allocated, including a
  206,169-character receipt-oriented print projection.

The design therefore recommends extracting the invocation kernel rather than
reusing the REPL evaluator, and making successful render admission omit the
`result-edn` sink while retaining the same bounded walk. That saving is
unverified until prototyped. The issue remains open pending owner rulings D1–D4
and implementation/live proof.

## Research update — 2026-08-02 render-model map

Implementation was stopped by owner instruction while the vocabulary and
declaration model settled. The complete counted change map is
`docs/prds/sci-execution-runtime/research/render-model-2026-08-02.md`.

The program graph currently contains zero functions returning
`:seon.render/ai`, zero returning `:seon.render/html`, 48 functions accepting
the retiring `:seon.render/unit`, and 33 functions whose output refs include
the collapsing `:seon.render/hiccup`. The vocabulary/contracts/default
validation must therefore land atomically before the guarded router can
validate any declared renderer. The report also found that the ruled per-call
cache is not implemented: `src/seon/render/web.clj:528-583` suppresses only
after rebuilding the complete watched page.

This issue remains open. Its acceptance now includes declared thing → schema
default → floor resolution, exact program-graph validation, the shared cluster
SCI `ctx`, per-call caching, and end-to-end throw/non-Hiccup/`raw`/interpreted
loop proofs. No implementation evidence was produced in this research lane.
