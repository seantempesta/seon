---
type: prd
status: active
tags: [prd, agent, architecture, database]
---

# The agent runtime design

One sentence: agents are database rows that think — each a sci CONTEXT on
a HOST, calling every ecosystem through pure-data function calls, durable
only as facts, cheap enough to run by the hundred and to lose without
drama.

Evidence base: B1 (`research/b1-eval-corpus-divergence-2026-07-20`,
green, 0 blockers), C1 (`research/c1-jvm-host-scale-2026-07-20`, all
gates pass), the seam study (`research/sci-routing-seam-2026-07-20`,
zero-fork), and the source-cleanup register's measured system truth. B2
(production anchoring for the Bun tier) is pending and gates only the
OPTIONAL js-eval tier, not this core.

## 1. Topology

Per cluster (unchanged clusters stay isolated stacks; nothing here is
cross-cluster):

    writer JVM        — sole Datahike authority (untouched by this design)
    agent-host JVM    — N sci contexts over one shared program base;
                        speaks the writer's UDS protocol as a client
    client pod (Bun)  — web UI, LLM adapters, prompt rendering, and the
                        cluster's shared JS capability host
    [bun sci child]   — OPTIONAL js-eval tier, only if a real agent
                        program proves js-bound (B2 + C2 rule it)

Execution children as a per-agent fleet are DELETED. A cluster failure
never crosses clusters; within a cluster, only the writer is
system-critical.

## 2. The agent model

- **Context** (sci's own word): the agent's private world — its
  namespaces and vars between evals. Process-local, disposable.
- **Host**: a process that runs contexts (JVM host) or serves capability
  calls (pod as JS host).
- **Binding table**: the allowlisted function surface a context sees.
  Extension is data: registering a namespace in the shared load-fn
  registry makes it lazily require-able in every live context
  (seam study, probed).
- The DURABLE agent is database facts: def sources in the one program
  corpus, plan, transcript, memory. A context is a cache of those facts;
  park = drop it; restore = fork the shared base + replay defs
  (~40 ms / 200 forms, measured). No snapshot machinery, ever.

## 3. The eval boundary (the one seam)

parse → repair → route → execute → envelope → persist-corrected.

- Parse/repair: existing owners (read-repair, preflight,
  augment-ns-source) unchanged.
- ROUTE: placement is a pure derivation over the persisted require graph
  (`:seon.ns/require-edges`). Each namespace maps to a host; a call into
  a remote-hosted namespace resolves to a wrapper var provisioned by the
  registry-backed `:load-fn` (stock sci; no fork). The agent perceives
  one platform.
- Execute: sci eval on a pooled thread, deadline + interrupt
  (`sci.interrupt`), one eval per form, Promise/future results settled
  by the runtime (eval once; await/deref the value; never re-eval).
- Envelope: every result and every failure is a value in the standard
  `:seon/error` shape with steering text; capability calls use the
  fs.cljs template envelope + one new field (below).
- Persist-corrected: the stored form is the corrected/working idiom with
  the resolved value (the augment-ns-source pattern, generalized);
  transcript renders the stored record; blobs keep raw truth.

## 4. Capabilities — the universal remote call

Every non-local ability is a function with a pure-data transit boundary:

- db → writer (existing, measured ~2 ms)
- Java/heavy compute → host binding tables (allowlisted classes)
- npm/JS → the pod's capability server (one Bun process serves the
  cluster; agents never hold a JS runtime)
- OS tools → the shell/fs capability family (existing gates)

Contract: one namespaced map in; value or `:seon/error` out; capability
gating at the door; transit-capable data only; big values by blob ref.
EFFECTFUL calls carry `:seon.capability/op-id`, and the capability
records a receipt fact — after any crash the "did it happen?" question
is answered by query, never by resend-and-hope. Reads/pure transforms
retry freely.

Host outage = capability calls return steering error values; db and pure
work continue; a derived status line appears in context while the facts
support it and heals when they don't. No notification queue.

## 5. The graduation pipeline (code earns compilation)

- Tier 0 (nursery): sci interprets agent code. Instant, sandboxed,
  per-context. Fast enough for orchestration (12 ms p50 turns).
- Tier 1 (graduated): a fn whose fingerprint passes the gate is compiled
  ONCE from corpus source — JVM `eval` → bytecode → HotSpot for
  data/db work; the real CLJS compiler → pod bundle for js-needing
  work — and installed in the shared binding table. Var-epoch bump
  re-links every call site safely (JIT-proven).
- The GATE is a trust promotion, not a speed switch: schema-valid,
  test-covered, and differential-tested (same tests green in tier 0 and
  tier 1) — compiled code is outside the sandbox fence.
- The compiled artifact is a derived, fingerprinted projection of the
  corpus: source edit → fingerprint change → tier drops back to nursery
  until re-graduation. Derive-don't-store, applied to compilation.
- Anti-churn dial: a fn re-edited within its cooling window waits.

## 6. File-backed and fingerprinted inputs

Content (files, blobs) stays outside the database; the CONTENT HASH is
the database fact. Rendering is pure over (database value +
fingerprints); any change is a visible transacted cache-bust. This is
the same key discipline as the graduation tier and the render memo —
one idea, applied at every freshness seam.

## 7. Failure model and drills (graduation gates for the design itself)

- Agent error → value (unchanged law).
- Runaway → deadline + interrupt; proven 10/10 with unaffected
  bystanders.
- Memory bomb → OOME containment (20/20 observed) + growth-triggered
  context recycling; honest posture: strong evidence, not certainty.
- Host death → supervisor restart; contexts replay from facts;
  interrupted turns surface as derived recovery notices (the fixed
  recovery path). DRILL REQUIRED before cutover: kill -9 the host
  mid-wave, assert full fleet restore + honest notices + zero fact loss.
- Pod death → js capabilities error-value while down; UI reconnects;
  agents continue stateless work. DRILL REQUIRED likewise.
- Graduated-fn failure → its cluster's host at worst; canary clusters
  stage promotions.

## 8. What gets deleted (the design's justification)

Per-agent execution children and their spawn/containment machinery; the
self-host compiler in the eval path (and its 416 MB retention class);
the async ceremony in agent code (await teaching, Promise-leak class);
per-child eager schema compilation. Every mechanism this design adds
(host process, wrapper registry, graduation gate) displaces at least one
of these.

## 9. Migration spine (reversible at every step)

1. Host speaks the existing execution protocol (pod cannot tell hosts
   from children). Tier assignment is per-agent data.
2. New agents land on the host; existing agents stay on children.
3. Toolkit port lands (.cljc for the 46% db-boundary; capability
   proxies absorb the js-bound tail unless C2's audit proves otherwise).
4. Await-corpus migration pass for persisted agent code (measured,
   small).
5. Steering/context re-alignment: every skill, docstring, warning
   example teaches the sync idiom (standing law: guidance follows
   behavior).
6. Drills pass → children retire agent-by-agent → deletion commit.
7. Architecture docs + one-mechanism table update at cutover, not
   before.

## 10. Standing risks (institutional paranoia list)

R-A graduated code is unfenced — the gate is the security boundary.
R-B tier-0/tier-1 semantic drift — differential tests are permanent.
R-C correlated in-flight loss within a cluster host — drills + recycle
policy; two hosts per cluster remains the escape hatch.
R-D sci JIT youth (Bun tier only) — pinned, fuzz-backed, fallback path.
R-E nursery bounce churn — cooling-window dial.
R-F context guidance drift during migration — step 5 is not optional.

## Build order (first three units)

U1 host-skeleton productionization: the C1 harness grown into
`seon.host` speaking the execution protocol against a branch cluster;
gates = protocol conformance suite + the kill drill.
**DONE (2026-07-20)** — `seon.host` + `seon.host.context` serve the
child message contract over transit-UDS (per-agent sci contexts over one
shared base, pooled eval with deadline + sci interrupt, errors as
values); conformance suite green under `bin/test-writer` (18 tests /
60 assertions; full writer gate 251/1958 green); §7 kill drill PASS
twice on a private drill writer (20 contexts mid-wave, kill -9:
20/20 in-flight child-exited error values, 20/20 fleet restore with
~130 ms context rebuild after the ~8-10 s JVM restart, zero fact
loss). Recorded seams: def-persistence/corpus tee + `register!`
admission (U2), authored invocation, `seon.execution` `.cljc`
promotion, render entrypoints stay pod-served. Evidence: the U1
section of [[roadmap]].
U2 wrapper registry + capability envelope op-id: the seam study's
registry-backed load-fn as the one provisioning mechanism; gates =
cross-context lazy provisioning + idempotent-receipt proof.
U3 graduation gate walking skeleton: fingerprint → both-tier test run →
JVM eval install → epoch re-link, for ONE real corpus fn end-to-end.
