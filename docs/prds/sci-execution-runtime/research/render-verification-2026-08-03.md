---
type: research
status: complete
tags: [prd, render, research, flow, sci]
---

# Render Implementation Verification — 2026-08-03

## Verdict

The render landing is not fully verified. Namespace ownership, deterministic
ambiguity, inert legacy attributes, SCI time limiting, fragment-only delivery,
stale-revision keyframe repair, and the unique schema-match rule all survived
independent probes. Three claims did not:

- the package proc re-invoked eight renderers for a one-block change;
- repeated `:seon.render/ai` context renders re-invoked all 12 renderers; and
- the crash probe's cold-recovery half is blocked because a stopped isolated
  cluster refuses to reacquire `seon.bootstrap/help`.

No production source was edited. All live work used isolated roots under
`tmp/`; both roots are down with zero live or orphan JVMs.

## Scope and dependency ledger

The audited landing is `094127076` through `e1c0f5e7d`, interpreted against
the current tree at `2fbdc9790059ee800d50e4e0fdbb3b9985734c1e`. The governing
spec is [[render-simplification-audit-2026-08-03]].

The dependency sources used to judge the boundaries were:

- SCI `2db3358cba91`, especially
  `reference-code/sci/doc/interrupt.md` and
  `reference-code/sci/src/sci/core.cljc`;
- core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`, especially
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` and
  `flow/impl.clj`;
- Datahike `0e8601d7f2f6`, especially its transaction
  and listener implementations; and
- http-kit `238a85cc555a`, for the SSE write-state
  boundary.

First-party owners were `src/seon/render.clj`, `src/seon/render/walk.clj`,
`src/seon/render/web.clj`, `src/seon/cluster/prompt.clj`, and
`src/seon/sci/eval.clj`, together with the render simplification, web, and
prompt tests.

## Method

The focused recurring gate passed:

```sh
bin/test seon.render-simplification-test seon.render.web-test \
  seon.cluster.prompt-test
```

Independent probes lived in the gitignored repository file
`tmp/render_verify_probes.clj` and ran through `clojure -M:dev:test`. They used
fresh in-memory databases for selection, context, time-limit, and package
economics checks. The crash check used these isolated operator roots:

- `tmp/render-verify-root`
- `tmp/render-verify-crash-root`

## Probe results

### 1. Owning namespace selection

**Pass.** Data owned by namespace B and reached from namespace A selected B's
qualifying renderer; A's renderer did not execute. After B's renderer row was
removed and a fresh SCI context acquired, neither A nor B executed for that
value. B's declared schema producer/floor rendered it instead.

### 2. Deterministic ambiguity

**Pass.** Two qualifying functions in B returned one flat
`:seon.render/ambiguous` value naming both fully qualified symbols. Reversing
the candidate discovery order returned the same sorted candidate vector.

### 3. Deleted marker protocol

**Pass.** A renderer returned Hiccup containing `data-slot` and `data-ref`.
The attributes survived unchanged as inert ordinary markup. These searches
returned no production matches:

```sh
rg -n "block/(slot|entity-slot|expand|select)|data-slot|data-ref" src/seon
```

### 4. Non-terminating interpreted renderer

**Pass.** An interpreted renderer containing an infinite loop was stopped in
31 ms under a configured 25 ms time limit. Its block became one flat
`:seon.sci.kernel/time-limit` result. The same page walk contained five units;
the timed-out block was isolated while two ordinary sibling render outputs
were still present.

### 5. Package-proc economics and delivery

**Mixed; the renderer-invocation claim fails.** A settled 14-fragment page was
changed through one namespace-source fact. The next real `render-pass`
observed:

- eight renderer invocations, not one;
- one `surface-html` serialization for the changed fragment;
- one fragment in the delta;
- the delta bytes for a contiguous tab; and
- the cached keyframe bytes for a stale revision.

The instrumented pass also observed three total Hiccup serialization calls and
two package-frame serializations: `next-package` deliberately constructs both
keyframe and delta event bytes. The delivery contract is sound, but renderer
execution is not retained. `page-result` performs the complete neighborhood
walk at `src/seon/render/web.clj:329-331` and compares retained evidence only at
lines 367-378. See
[[render-package-proc-reruns-unchanged-renderers]].

### 6. Mid-pass stop and recovery

**Atomicity pass; recovery blocked.** In the second isolated root, a live-only
redefinition paused `surface-html` during a render pass. While paused:

- the database had advanced from basis transaction `536870953` to
  `536870954`;
- the settled package remained revision `1` at basis transaction `536870953`;
  and
- no partial package was delivered.

The JVM was then stopped forcibly at the blocked render transform. This proves
the pass does not publish a partial package before it returns.

Cold reopen failed before graph readiness with:

```text
seon.bootstrap/help does not name an installed SCI Var
```

The stack reaches `sci.core/install-namespace-bindings!` from
`seon.sci.eval/acquire!` at `src/seon/sci/eval.clj:1068`. A second isolated
root reproduced the same cold-start failure, and the clean second root had no
agent-authored renderer. Recovery re-derivation and the absence of run
re-execution therefore remain unproved. See
[[cold-cluster-reopen-refuses-bootstrap-help-binding]].

### 7. AI projection ownership and caching

**Ownership pass; retained-bytes claim fails.** A real context render followed
the same B-owned selection and schema-floor fallback established in probe 1.
Two calls at the same database value returned identical context bytes and the
same renderer sequence, but each call executed all 12 renderers. The prompt
owner calls one deliberately uncached fresh walk at
`src/seon/cluster/prompt.clj:1-8,40-69`. See
[[ai-context-bypasses-render-proc-retained-bytes]].

### 8. Multi-match consumers

**Pass; the archived issue is genuinely closed.** The only production caller
of `matching-shapes-in` is `schema-producer` at
`src/seon/render.clj:100-112`. It retains all distinct producers, sorts them,
and returns one ambiguity value when more than one remains. An independent
probe forced two matching schema producers and observed that error naming both
symbols. No production caller silently takes the first match.

## Ranked findings

1. **Blocker:** [[cold-cluster-reopen-refuses-bootstrap-help-binding]] blocks
   the required recovery proof and cold cluster restart generally.
2. **Friction:** [[render-package-proc-reruns-unchanged-renderers]] disproves
   the package proc's renderer-economics claim.
3. **Friction:** [[ai-context-bypasses-render-proc-retained-bytes]] disproves
   retained-byte parity between AI and HTML projections.

## Calibration

The landing has genuinely strong boundaries worth preserving:

- renderer ownership is fact-derived and never falls back across namespaces;
- ambiguity is flat, loud, complete, and independent of definition order;
- schema-property fallback follows the same unique-or-ambiguous rule;
- deleted `data-slot` and `data-ref` spellings have no execution semantics;
- SCI time limiting isolates one bad interpreted block without losing sibling
  blocks;
- changed delivery contains only changed fragment bytes;
- stale revisions repair from the cached complete keyframe; and
- a mid-pass JVM stop did not expose a partially advanced package.

The recurring tests are green, but their package assertions establish delivery
shape and serialization reduction rather than the requested exact renderer
invocation count. The independent instrumentation is what exposed that gap.
