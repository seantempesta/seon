---
type: research
status: active
tags: [research, render, sci, architecture]
---

# Agent renderer execution — owner design gate

Use one small guarded invocation kernel for every render call made on behalf of
an agent, with renderer definitions installed once in the cluster's live SCI
context and invoked on each cache miss. Do not send render calls through the
full REPL `evaluate` operation, and do not retain a compiled first-party bypass:
the former measured 62× slower than the small kernel at p50 for a trivial call,
while the latter contradicts the settled who-for rule and leaves a compiled
renderer able to wedge the current render proc. Every returned value crosses
the one bounded admission walk before kind validation. A successful render
does not need the receipt-oriented `result-edn` sink; a failure becomes one flat
durable error per changed call/error signature. `raw` remains a serializer
composition marker after admission, never an admitted renderer output: an
agent may call it, but the marker carries no authority across the boundary.

## Options

| Option | Guarantee | Implementation cost and risk | Operational trade-off | Capability given up | How an agent can still get it wrong |
|---|---|---|---|---|---|
| **1. Recommended — one render-call kernel for every agent-driven render; definitions installed once** | One resolution owner, one live cluster `ctx`, one `:interrupt-fn`, render-class `time-limit`, one admission walk, kind grammar, and one flat failure shape for first-party and agent-authored renderers. Interpreted loops are interrupted. | Medium. Extract the arm/invoke/admit/catch/finally kernel from `seon.sci.eval/evaluate`; make the router resolve through the program graph/live ctx; submit cache misses through the existing bounded compute owner; attach render provenance and deduplicate durable failures by the existing call-result cache identity. The main risk is accidentally rebuilding REPL/session behavior or creating a second admission codec. | A slow cache miss delays the current complete render pass until success or the render limit. In today's single cluster render proc, watched pages behind it wait too; the wake is sliding-1, so obsolete wakes do not accumulate. A throw becomes an error immediately; an interpreted infinite loop becomes a time-limit error; non-Hiccup becomes an error after admission. | Agent renderers cannot emit unescaped bytes or depend on process-local objects surviving admission. No direct compiled fast lane for an agent-driven render. | It can query too much, return a huge but still admitted tree, churn bytes, depend on a compiled host call that does not re-enter SCI, or return valid but poor Hiccup. Caps and the cache bound damage; review and measurement still matter. |
| 2. Route every call through existing `evaluate` | Reuses today's complete guarded evaluation, failure conversion, admission, and diagnostics with little new SCI code. Slow/throw/loop/non-Hiccup outcomes match option 1 after the kind check. | Low initial code, high semantic and performance risk. `evaluate` also reads source, observes namespace/session definitions, computes program rows, captures print state, and creates receipt data that rendering does not own (`src/seon/sci/eval.clj:1396-1597`). Constructing source forms for ordinary calls also reintroduces a parser boundary. | The trivial measured call was 0.636 ms p50 / 0.941 ms p95 and allocated about 1.40 MB per call before Flow submission or database work. Every uncached block pays REPL bookkeeping. | Gives up the "effectively free to dumbly call" cache target and much of the 16 ms frame budget under multi-block churn. | It can still return expensive admitted output, make compiled host calls, or return bad Hiccup; the extra machinery does not improve those cases. |
| 3. Guard only agent-authored renderers; invoke first-party Vars directly | Agent rows get option 1. First-party hot reload stays exactly as today. Provenance is derived, not listed: resolve the `:seon.fn/sym` row, read the asserting transaction of its canonical `:seon.fn/source` datom, and use `seon.schema/admission-from-asserting-transaction`; missing provenance fails closed as agent (`src/seon/sci/eval.clj:903-933,980-1035`; `src/seon/schema.clj:567-593`). | Medium and deceptively risky. The router gains two invocation semantics and must keep error, admission, output grammar, caching, and hot reload identical across them. | Agent slow/throw/loop/non-Hiccup behaves as option 1. A slow or looping compiled first-party renderer has no interpreted body entrance, so the `:interrupt-fn` cannot cancel it; a submit backstop may report the wedge but does not stop the host call (`reference-code/sci/doc/interrupt.md:50-65`; `src/seon/sci/eval.clj:908-920`). | Gives up the settled guarantee that context assembly and render code executed for an agent cannot lock up the system (`plan/README.md:1671-1682`). | It can deliberately select an expensive public first-party function through its renderer, or expose a latent first-party loop. Provenance classification does not make compiled work interruptible. |
| 4. Pre-render output at definition/install time | If the renderer and output are static, later reads are just a cached value. A slow/throw/loop is paid or refused at install; non-Hiccup can refuse install. | High semantic risk. A function definition can be installed once; its result cannot be, because the unit, immutable database value, kind, distance, and transient inputs are call arguments. Invalidating output on those values reconstructs the ruled per-call cache under a second name. | Excellent reads for genuinely constant output; wrong or stale output for database-driven blocks. Installation itself becomes a hot failure boundary and may delay publication. | Gives up database reactivity and live input. It contradicts "write a function of the database; only database changes re-render" (`plan/README.md:1100-1130`). | It can read undeclared state at install, bake one basis into every future page, or make installation slow or effectful. **Reject this option.** |

Option 1 already takes the valid part of option 4: evaluate or bind the
**definition** once, then invoke the live Var per render call. Agent functions
are evaluated when defined and installed from a committed row only when cold
acquisition needs them (`src/seon/sci/eval.clj:795-850,980-1147`);
`cluster-ctx` performs that acquisition once (`src/seon/sci/eval.clj:1212-1237`).
Pre-rendering the **result** is a different and invalid proposal.

## Owner decisions requested

**OWNER DECISION D1 — APPROVE OPTION 1.** Every call made for an agent's AI or
HTML render, including first-party render functions and context assembly,
enters the same small guarded invocation kernel. Provenance remains evidence
and cache metadata; it does not select a bypass. This is the direct reading of
the SCI-only render ruling (`plan/README.md:1333-1352`) and the later who-for
addendum (`plan/README.md:1671-1682`).

**OWNER DECISION D2 — INSTALL DEFINITIONS ONCE; INVOKE VALUES PER CACHE MISS.**
The router resolves the symbol against the cluster's live SCI context and calls
the live Var. It never calls `cluster-ctx`, never re-evaluates the definition,
and never builds a source string. Resolution may cache the Var, not its
dereferenced function root, and must invalidate with the program/code revision;
resolving the live Var on each miss is also acceptable and measured below as
negligible. The per-function-call byte cache remains the ruled mechanism
(`plan/README.md:1370-1386`).

**OWNER DECISION D3 — ADMIT EVERY GUARDED RETURN, BUT DO NOT BUILD SUCCESS
RECEIPT EDN.** The invocation remains armed while the one `seon.sci.admit`
bounded walk realizes and projects the value, then applies the requested kind's
grammar. A successful render consumes the semantic admitted value and bytes; it
does not persist a receipt and should not materialize
`:seon.cluster.eval/result-edn`. This must be an optional sink over the same
admission traversal, not a render-specific codec. The 250-event measurement
below makes this a required falsifier: current `admit` spent 2.45 ms p50 and
allocated 10.9 MB while also producing a 206,169-character print projection.
The saving from omitting that sink is **unverified** until prototyped.

**OWNER DECISION D4 — `raw` HAS NO AUTHORITY ACROSS RENDER ADMISSION.** Keep
escaping as the default. `Raw` may remain the explicit serializer marker used
by trusted compiled delivery composition *after* admitted fragments have been
serialized; current legitimate uses splice already-serialized page/debug
fragments (`src/seon/render/web.clj:1051-1110`). Every guarded renderer output,
first-party or agent-authored, is admitted before `hiccup?`. Current admission
turns a `Raw` record into ordinary data, which `hiccup?` refuses; the probe
below observed exactly that. Make this order an explicit invariant and
regression rather than relying on the false claim that agents cannot call
`raw` (`src/seon/render/hiccup.clj:61-83`).

The legitimate cost of D4 is narrow and explicit: an agent renderer cannot
embed an arbitrary third-party HTML fragment, `<script>` body, or pre-escaped
string. It can emit structured Hiccup, escaped text, style maps/attributes, and
ordinary links. A future sanitized-fragment capability would have to return an
admitted safe representation; `raw` itself never becomes that capability.

## Recommended call contract

The existing `render/render` remains the one router. Its invocation request
needs enough data to make safety and evidence structural:

```clojure
{:seon.sci.eval/ctx ctx
 :seon.db/db db
 :seon.cluster.agent/id agent-id
 :seon.render/projection 'my.audit.renderer/render-html
 :seon.render/kind :seon.render/html
 :seon.render.block/name :my.audit/example
 :seon.render/unit unit
 :seon.sci.eval/time-limit-ms render-time-limit-ms
 :seon.sci.admit/caps caps
 :seon.config/on-core-error on-core-error}
```

Implementation depends on closing
`docs/seon/issues/work-submission-can-block-before-its-time-limit.md`: the
kernel must enter the repaired bounded compute submission path before the
render `time-limit` can be claimed to bound proc latency. This dependency does
not block the present design or load-only measurements, but it does block the
implementation's live safety proof.

Resolution is basis-faithful: the renderer symbol must have a public
`:seon.fn/sym` row at the request database value; its provenance comes from the
asserting transaction rather than namespace or path naming. The callable is
resolved from that cluster's already-acquired live `ctx`. No JVM
`requiring-resolve` decides whether an agent-authored renderer exists.

On success the kernel returns the admitted semantic value plus diagnostics
needed for observation. On failure it returns one flat `:seon.error` value
whose data includes agent, renderer symbol, kind, block name, and basis
transaction. `block/surface` turns that value into the existing error surface;
the render owner commits it through the existing error fact owner only when the
call's result changes to a new error signature. Repeated wakes that hit the same
cached error do not recommit it. This transition fence is required because the
current cluster listener wakes rendering for every transaction
(`src/seon/render/web.clj:528-583`); committing the same failure on every pass
would feed itself.

The render invocation-class limit follows the existing metadata/config rule:
one normal default, with a function metadata override lifted into the program
row for genuine exceptions (`plan/README.md:1359-1369`). The exact default is
**unverified** and should be selected only after representative renderer
measurements. There is no per-call knob and no render-local timer family.

### Failure behavior under the recommendation

| Renderer behavior | Result | Effect on page and proc |
|---|---|---|
| Slow but finishes inside the limit | Admitted result, then AI/HTML grammar | Current complete pass waits. Its result is cached; equal later calls skip execution. |
| Throws | Flat error with renderer provenance | Error surface replaces only that block; a new error signature commits once. The pass continues after the call settles. |
| Interpreted infinite loop | SCI's uncatchable interrupt becomes a flat time-limit error | The current pass waits to the render limit, then continues with an error surface. It cannot wedge forever. |
| One compiled host call never returns | Known residual: SCI sees no interpreted body entrance | The submit owner can report a wedge but cannot cancel that call. Full elimination depends on the interpreted first-party corpus substrate ruled at `plan/README.md:1657-1670`; until then this is a named remaining safety limit, not a claimed guarantee. |
| Returns non-Hiccup for HTML | Admission succeeds or bounds the value; `hiccup?` refuses the semantic result | One `:not-hiccup` error surface; no bytes from the refused value reach the serializer. |
| Returns `raw` anywhere | Admission removes the `Raw` authority; post-admission Hiccup validation refuses the resulting data | One error surface. The unescaped string never reaches HTML serialization. |

## Worked example — agent definition to page

1. Agent `renderer-a`, assigned namespace `my.audit.renderer`, evaluates a
   contracted renderer through the ordinary turn door:

   ```clojure
   (defn render-html
     {:malli/schema
      [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
     [unit]
     [:section {:class "audit-card"}
      [:h2 "Audit"]
      [:p (get-in unit [:seon.render/value :my.audit/message])]])
   ```

2. The terminal transaction publishes its `:seon.fn/sym`, source, contract,
   namespace ref, and provenance. The same live cluster `ctx` already contains
   the interpreted Var; a cold cluster evaluates that definition once during
   acquisition (`src/seon/sci/eval.clj:795-850,980-1147`).
3. A schema'd value or schema default points `:seon.render/html` at
   `my.audit.renderer/render-html`. The walk discovers the value and the router
   resolves the function from the program graph at the page's immutable
   database value, preserving the one precedence chain
   (`docs/seon/architecture/ui.md:86-99`).
4. On a cache miss the render proc submits the call to the existing bounded
   compute owner. The small invocation kernel arms the cluster guard, invokes
   the SCI Var with the unit, admits the result while still armed, validates
   Hiccup, records diagnostics, and disarms in `finally`.
5. `block/surface` supplies the stable block id; the serializer escapes text
   and attributes. `page-of` includes the resulting bytes in the complete page
   snapshot, equality suppression removes unchanged output, and the existing
   `mult`/sliding-1 feed delivers the changed block
   (`src/seon/render/block.clj:200-274`; `src/seon/render/web.clj:306-370,528-583`).
6. A later relevant database transaction causes another call only when the
   per-call dependency/code revision says the cache is stale. A redefinition
   changes the live Var/code revision; the next miss uses the new body without
   rebuilding the cluster context.

## Measurements

The load-only probe used OpenJDK 26.0.1, 18 reported processors, the maintained
SCI revision, one `build-base-ctx`, the approved result caps, and an interpreted
one-argument renderer. The proposed small kernel was composed directly from
the current private `arm`, an SCI Var invocation, `admit/admit`, and `stop!` in
`finally`; it did not include Flow submission, a database/provenance query, or
cache lookup. It is a design probe, not production code.

| Probe | Samples / warm-up | p50 | p95 | p99 | Allocated per call |
|---|---:|---:|---:|---:|---:|
| Direct SCI Var call, no guard/admission (calibration only) | 5,000 / 100 | 0.125 µs | 0.375 µs | 0.417 µs | 88 B |
| Small arm → invoke → admit → disarm, `[:div "SCI-only"]` | 2,000 / 100 | 10.250 µs | 34.042 µs | 77.291 µs | 11,052 B |
| Full `evaluate` of the same call | 500 / 25 | 635.750 µs | 941.208 µs | 1,263.667 µs | 1,404,729 B |
| Small kernel, trivial renderer returning a 250-event Hiccup fixture | 300 / 30 | 2.448 ms | 3.337 ms | 4.688 ms | 10,884,625 B |

The 250-event call's admission produced a 206,169-character
`:seon.cluster.eval/result-edn`. The older pipeline benchmark measured
0.847–0.965 ms p95 for render + serialize + complete package at 250 events but
explicitly excluded SCI evaluation (`render-pipeline-design-2026-07-29.md:232-252`).
The p95s must not be added as if they were one distribution. They establish
that guarded invocation of a small value is cheap, full `evaluate` is the wrong
kernel, and bounded output admission/allocation—not Var resolution—is the hot
cost to falsify.

One 10 ms interpreted-loop sample was interrupted in 13.25 ms wall time after
132,407 function entries; this is calibration, not a latency distribution. An
interpreted throw was caught immediately by the prototype boundary. Admitting
`(hiccup/raw "<script>alert(1)</script>")` produced ordinary data
`{:string "<script>…</script>" :seon.sci.admit/type
"seon.render.hiccup.Raw"}`, and `hiccup?` returned false.

The orchestrator's independent live measurement of full `cluster-ctx` was
636 ms (`plan/unsettled.md:185-188`). This lane did not repeat it against the
protected live `default` cluster. After JVM load, 100 independent
`build-base-ctx` samples measured 47.625 µs p50 / 104.916 µs p95; that is not a
cluster acquisition measurement. Neither operation belongs on the render path.

## Dependency ledger

| Dependency or mechanism | Selected revision | Source and first-party seam read |
|---|---|---|
| SCI | `6de15683b752` | `reference-code/sci/doc/interrupt.md`; `reference-code/sci/src/sci/interrupt.cljc`; live context and invocation owner in `src/seon/sci/eval.clj:156-384,795-850,1212-1237,1396-1597` |
| core.async + Flow | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `reference-code/core.async/.../flow.clj`, `flow/impl.clj`, and buffers; bounded submission call in `src/seon/cluster/loop.clj:531-562,1373-1446`; current render proc in `src/seon/render/web.clj:528-690` |
| Datahike | `0e8601d7f2f6` | Program rows carry `:seon.fn/sym` and source datoms (`resources/seon/schema.edn:1960-1989`); asserting-transaction provenance is derived by `src/seon/schema.clj:567-593` and already consumed by `src/seon/sci/eval.clj:903-1035` |
| Render router and grammar | current tree at this design | JVM-only resolution/invocation in `src/seon/render.clj:282-305,331-382`; block grammar/error boundary in `src/seon/render/block.clj:200-274`; raw/escaping in `src/seon/render/hiccup.clj:61-147`; delivery in `src/seon/render/web.clj:306-370,528-690` |
| Render pipeline design | `render-pipeline-design-2026-07-29.md` | Shared serialization, current/target distinction, buffer law, frame measurements, and explicit exclusion of SCI timing (`:62-190,192-332,510-588,620-641`) |

## Non-goals

- No production implementation, schema edit, test, or live cluster mutation.
- No new render router, registry, executor, proc, channel, timer family, or
  error store.
- No redesign of resolution precedence, the walk, cache invalidation, package/
  keyframe delivery, Datastar morphs, or per-tab backpressure.
- No claim that the current single cluster render proc already provides
  block-parallel publication or that the target per-call cache is implemented.
- No hard guarantee for an unbounded compiled host call; eliminating that
  residual belongs to the interpreted first-party corpus substrate already
  ruled in the program roadmap.
- No general HTML sanitizer, browser action policy, CSP design, or agent route
  surface. This gate settles only renderer execution, admitted output, and
  `raw` at that boundary.

## Questions for the owner

1. Approve D1: one guarded invocation kernel for **every** agent-driven render,
   with no provenance-selected compiled bypass?
2. Approve D2: definitions are installed once, while the live SCI Var is
   resolved and invoked on each cache miss rather than pre-rendering output?
3. Approve D3: the same admission traversal with no successful-render
   `result-edn` sink, subject to a measured prototype before implementation?
4. Approve D4: `raw` remains usable only after renderer admission for trusted
   serializer composition; renderer returns can never carry unescaped bytes?
5. Approve error persistence only on a changed call/error signature, using the
   one ruled call-result cache to prevent a render-error commit loop?
6. Confirm that the exact render invocation-class `time-limit` remains a
   measurement decision under the existing metadata/config rule, not a number
   selected in this design gate?
