---
type: prd
status: active
tags: [prd, architecture, agent, database]
---

# Program synthesis 2026-07-21 — cleanup-first sci transition

The program goal is and always was **cleanup**: deps, build targets,
packages, unembarrassing source, bugs and rough edges chased down. The
sci pivot is the biggest cleanup lever (−~5,100 production LOC, −~2,800
test LOC, −6 shadow builds, one compiler instead of two), not a
replacement goal. This document reconciles the 2026-07-21 audit
evidence, de-risk probes, and owner rulings into the ordered
work-package series. It supersedes the exploration framing above the
U-ledger in [[roadmap]]; the U-ledger rows remain the unit history.

## Evidence base (all committed, dated 2026-07-21)

Audits: [[research/audit-host-robustness-2026-07-21]] ·
[[research/audit-deletion-inventory-2026-07-21]] ·
[[research/audit-db-parallelism-isolation-2026-07-21]] ·
[[../generate-code/research/audit-execute-code-pipeline-2026-07-21]] ·
[[research/audit-benchmark-pkg-readiness-2026-07-21]] ·
[[research/audit-doc-drift-2026-07-21]] ·
[[../generate-code/research/llm-retry-fallback-resilience-2026-07-21]]

De-risk probes (all executed, all favorable):
[[research/probe-shared-var-protection-2026-07-21]] (stamping, no sci
patch) · [[research/probe-writer-connection-pool-2026-07-21]] (pool
viable, recipe + numbers) ·
[[research/probe-interrupt-core-merge-2026-07-21]] (safe with caveats;
with-ctx lazy-forcing requirement; perf tax accepted at agent scale).

## Owner rulings ledger (2026-07-21)

1. LLM timeout is fallback-eligible.
2. Planning fallback = Muse; DeepSeek is the implementer model.
   Experiment: DeepSeek thinking for planning vs no-thinking for
   implementation.
3. npm: per-cluster installs under `data/clusters/<name>/pkgs/`, shared
   downloads (bun global cache); package/metadata areas clearly separate
   per cluster on BOTH JS and JVM sides.
4. Maven/CLJ third-party code runs in a separate disposable JVM package
   host (it will get dirty); Seon-level resilience: crash → easy
   restart, lose only runtime state.
5. Writer stays flavor-shared + server-side default read
   ceilings/deadlines; writer-per-cluster only if ceilings prove
   insufficient.
6. cljs.js bootstrap KEPT as a quarantined experimental artifact for
   the diffusion oracle (real CLJS analyzer is irreplaceable for form
   analysis; separate build, so size is moot). U11 deletes every
   production reference; rg-enforced zero `src/` requires; rot waits
   for the diffusion lane. Diffusion is experimental, not program
   scope.
7. Config: no magic numbers in source. Every operational limit is a
   named aero key → database fact at boot; agent-relevant limits render
   into context; defaults computed from hardware where sensible; caps
   reject with steering error values, sized for high parallel
   throughput.
8. Docs: keep current program PRDs + `docs/seon/architecture/` current;
   archive every other PRD folder. One ledger, one architecture truth.
9. Vocabulary: the dependency's own words, accurate not clever.
   Value-browser "drill" → get-in/path names (at the Stage 1.5
   boundary); "kill drill" is temporary prose only.
10. Implementer preamble (mandatory in every spec): read the
    `reference-code/` source you interface with; report (a) whether a
    better seam exists now that the source is understood and (b) what
    upstream calls each thing — use their terms.

## Strong / weak / PoC map

**STRONG (keep, build on):** writer multi-class dispatcher (parallel
reads, per-database mutation serialization, reject-not-queue overflow,
durable request-id idempotency + recovery); pod session (256 in-flight,
deadlines, cancel, reconnect); host message contract, cluster-scoping
refusals, receipt-before-run recording with CAS terminal, settle-once
cancel, restore-by-replay; U2 registry + hot-swap; U3 graduation; U5
computed toolkit ledger; generate-code parse→DAG→failure-isolated
execution + CAS-claim fix-up scheduler; `seon.retry` (the `again` port)
as sole LLM retry authority with per-attempt provenance; toolkit
benchmark parity (shell/fs/web/search/plans).

**WEAK (bounded fixes):** oversize error frames escape `settle!`;
acceptor-loop single-catch; global schema snapshot race; regex-classified
interrupts; agent queries carry no caps; print floods; thin fix-up
worker context (Stage 6 unbuilt); K3 not graduated for planning (two
live timeouts, no fallback); B8/B11 intermittents; branch-qualified
eval-cljs hang; two topological orderers over `:seon.ns/require-edges`.

**PoC (redesign settled by probes/rulings, implement):** host writer
channel (one deadline-less locked socket → pool per recipe);
interruption coverage (merge `sci.interrupt/clojure-core`); shared-var
exposure (stamp `:sci/built-in`); cancel ghost-execution
(future-cancel + generation check); npm/maven execution placement
(disposable per-cluster package hosts); U13 install design
(per-cluster roots, staged-then-atomic).

## Work-package series (dependency order)

Model policy: I (orchestrator) write each spec with the hard decisions
resolved; Codex `gpt-5.6-sol` implements — `medium` effort for W0/W3/W6
mechanism work, `low` for mechanical/doc packages. Every spec carries
the ruling-10 grounding preamble, exact owned paths, falsifiable gates,
and shared-tree/path-limited-commit rules.

- **W0 — containment hardening** (critical path; gate: hostile-eval
  battery — each audit vector's attack form evaluated live; cluster
  stays responsive, every other agent's turn completes):
  W0.1 merge interrupt core into `build-base!` + force/serialize eval
  results under `sci.ctx-store/with-ctx` (probe caveat) + pool
  fairness/queue bounds; W0.2 stamp base+registry vars `:sci/built-in`
  at build/registration + steering error prose; W0.3 `.cancel` the
  future + claimed-generation check (kills ghost execution); W0.4
  replace `writer-call!` single channel with the proven pool recipe
  (retained members, one in-flight each, close-and-replace on deadline,
  same-request-id write retry, interests pinned, size ≤ writer
  cpu-workers); W0.5 writer-side default read ceilings/deadlines
  (client caps become server defaults); W0.6 frame-write inside try,
  per-accept catch, capped output capture feeding the existing
  `::output` seam; W0.7 the battery test.
- **W1 — operational limits as config facts**: sweep magic numbers
  (connection cap 256, host pool 10, -Xmx512m, frame/slots/deadlines,
  retry policy, render/get-in caps, executor capacities) into aero →
  `:seon.config` facts; hardware-computed defaults; agent-relevant
  limits rendered as a derived context block; rejections name their
  config key. Subsumes source-cleanup's config-through-aero inventory.
- **W2 — LLM resilience**: `:seon.ai/agent-fallback-variants` (ordered,
  resolved frozen at turn open, consulted after retryable exhaustion or
  attempt timeout, per-call only, provenance recorded); timeout
  fallback-eligible; planning=kimi-k3→Muse fallback, workers=DeepSeek;
  the thinking/no-thinking DeepSeek experiment as a scored comparison.
- **W3 — host parity punch list** (U6 + U11 blockers): instrumentation
  over sci vars; host-side run-fence CAS; output capture parity;
  repair sub-loop/preflight parity; authored function invocation on the
  host tier (currently child-only — hard U11 blocker); typed interrupt
  classification (no message regex).
- **W4 — teaching/steering rewrites (U8)**: both system-texts (config
  override + ctx.cljs fallback — remove "NO JVM", async contract →
  sync idiom); skills (`datahike`/`data-oriented-clojure`/`ui-canvas`
  await sections; `clojurescript` skill dies at U11); `my.plan`
  development-teaching two-phrase fix; encode the owner teaching
  contract: specs first → dependency fns → main namespaces, any write
  order, parser orders, last version wins.
- **W5 — U11 cutover deletions**: band-by-band per the deletion
  inventory (12 rewiring points cited); renders migrate into the pod
  (its require closure already compiles there); child lane + cljs.js
  engine bands delete; bootstrap quarantine per ruling 6; `execution`
  protocol band promotes to `.cljc` killing the hand JVM schema
  projection; sequenced AFTER Stage 1.5's retire-while-sampling proof
  and W3. Get-in/path renames land here too (ruling 9).
- **W6 — packages**: U13 redesign (per-cluster roots, shared caches,
  staged-then-atomic install, config-fact policy, wrapper gen into the
  U2 registry with real docs/arglists); disposable per-cluster Bun
  package host + disposable JVM package host (same UDS envelope,
  stateless, respawn-on-crash); package-capabilities P1–P7 Phase 0
  (root pins, agents author `my.*` wrappers from goal tasks —
  [[../package-capabilities/roadmap]]) may start before U13 lands.
- **W7 — generate-code completion**: Stage 6 worker-context bundle
  (full plan visibility: planner reply, accepted prefix, failed eval
  ids, sibling status); the three terminal/retry issues; Stage 8 proof
  drive; long-list batching design deferred until MVP evidence.
- **W8 — doc/PRD hygiene**: archive all non-program PRD folders to
  `docs/prds/archive/` with status flipped (absorb runtime-reliability
  current-state first); delete empty gym-v2; architecture docs gain the
  affirmative sci-host description (today only two denials exist);
  fix root-authority replica drift; vocabulary table rows.
- **W9 — deps/targets cleanup**: pushed sci mirror for a publishable
  coordinate; alias audit; shadow build matrix shrink; the
  `.shadow-cljs-b2/` and `out-b2/` cruft; root package.json reconciled
  with per-cluster package design.
- **W10 — bug ledger chase-down** (continuous fill-in lane): B8/B11
  intermittents, branch-qualified eval-cljs hang, two-orderers
  convergence, remaining audit WEAKs not covered above.

Then **U10** (integration kill/restart tests with live agents) and
**U12** (the graduation gate: 100-agent cluster, real work, host kill +
pod restart, zero fact loss, no operator intervention) close the
program, with source-cleanup stages 2–5 completing in their own PRD at
the named boundaries.

## Sequencing and parallel portfolio

**Earliest unsettled contract:** W0 (containment) — everything fleet-
shaped depends on "an agent cannot take down or lock up the cluster".
**Integrated proof:** the W0.7 hostile battery green on a live cluster.
**Parallel portfolio (safe now):** W1, W2, W4, W8, W9 are independent
of W0 and of each other; W6 Phase 0 (package capabilities) and W7 are
independent; W10 fills idle slots. W3 follows W0; W5 follows W3 + the
Stage 1.5 boundary; U10/U12 last.
**Refills:** after W0 → W3; after W3 → W5; after any doc/config lane →
next W10 item.
