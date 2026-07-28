---
type: architecture
status: active
tags: [architecture, agent]
---

# Empirical laws

These constraints summarize repeatable behavioral findings. They remain in the
target only while revalidation supports them. Dated runs, scores, model names,
sample sizes, and acceptance evidence belong in PRD research and roadmaps.

## Context

- **Render-prominence.** A composition function's value is its worked example.
  Simple-call functions may render compactly; composition functions render the
  complete relevant shape.
- **Minimal standing context.** Skills remain absent by default. Prefer the
  owning namespace, schema, or docstring before adding prompt prose; admit a
  standing instruction only after behavioral evidence supports it.
- **Cache stability.** Aged transcript clips and stable-prefix material remain
  byte-identical. Eviction does not reflow retained content.
- **Database-deterministic context body.** The same agent rendered from the
  same immutable database value produces the same cacheable-body bytes.
  A root-only free dynamic tail may add no more than roughly 50 estimated
  tokens of live operational telemetry after every cache boundary; the exact
  sent prompt blob is its historical authority. Filesystem reads and
  process-local caches never alter the database-derived body.
- **No synthetic compaction.** Bound raw transcript history by age and total
  rendered cost. Durable intent and knowledge live as queryable plan/database
  facts; never replace history with a model-authored summary.
- **Qualified examples.** Context and worked examples use names that resolve in
  the namespace where the agent will evaluate them.

## Honesty

- **Derived surfaces outrank narration.** Prefer values computed from database
  facts over prose that restates them.
- **Termination is truthful.** A bound, timeout, interruption, or failing gate
  cannot masquerade as successful completion.
- **Partial values identify their bounds.** A clipped or paged result remains
  addressable and never presents itself as complete.

## Runtime

- **Process jobs stay narrow.** The cluster JVM performs transactions, runs
  agents and guarded renders, owns the program graph and render flow, and
  serves HTTP/SSE for its one store. Leaf runtimes are disposable native-effect
  capacity. Supervision, bounded evals, and Integrant component restart protect
  the cluster JVM; process walls do not split its co-located responsibilities.
- **Claims and receipts outrank process memory.** A run's
  `:seon.agent.run/process`, epoch, and heartbeat establish custody; the turn
  phase and attempt/eval receipts establish admitted and completed work.
  Recovery derives from those facts.
- **Same source or same artifact.** Cross-runtime policy is one portable
  `.cljc` core or one shared compiled artifact. Each tier adds one native leaf,
  with reader conditionals only at entry expressions. Hand-mirrored wrappers
  and duplicated run loops are not interfaces.
- **Replacement is one mechanism.** When consumers move to the portable owner,
  the superseded path is deleted. Compatibility shims and dual-maintained
  run loops, renderers, or capability surfaces violate the architecture.
- **Scheduling is Flow.** Runtime owners are `core.async.flow` procs with
  `step-fn`s, bounded workload channels, `conns`, a `graph-def`, and a report
  channel. Custom launchers implement `flow.spi/ProcLauncher`; flow-monitor is
  the operational surface. Database facts remain the durable work record.
- **Build once, fork everywhere.** Expensive values are derived once and
  forked by consumers — never rebuilt, never shared mutably: the sci base
  `ctx` forks per evaluation; a database value at a basis is a free fork
  (`as-of`/`since` are temporal forks); every cluster branch forks from the
  same immutable bootstrap ancestor containing indexed code and initialization
  pages; and a proc restart derives from facts at `init` state 0. Structural
  sharing keeps the ancestor single while branch divergence remains local.
  Mutation exists in exactly one serial transaction path per physical store.
- **Reset, never migrate.** A cluster reconciles to current code and schema by
  resetting and reinstalling from the manifest and program facts. No data
  migration path exists; a live proof runs on a freshly reset cluster at the
  exact artifact it claims to prove.

## Limits

- **Limits are circuit breakers, never governors.** Every protective limit is
  a schema'd configuration fact with units, calibration provenance, and a
  default at least two orders of magnitude above legitimate measured P99.9
  work. Crossing it is exceptional containment, not normal scheduling.
- **Firing is loud.** A crossed limit records the owning fault and returns one
  flat steering error naming the governing config fact. It never silently
  slows, drops, truncates as complete, or retries.
- **No shadow defaults.** Runtime code contains no numeric fallback for a
  protective limit. A missing required fact is a configuration/readiness
  error.

## Measurement

- **Measure the behavior being changed.** Aggregate cost metrics do not replace
  an adoption, correctness, or interaction measure for the affected mechanism.
- **Sample variance.** Behavioral conclusions use enough independent samples to
  distinguish a repeatable effect from one run.
- **Predicates match semantics.** Scorers and guards recognize the mechanism,
  not one alias, spelling, or exact prose rendering.
- **Battery-wide acceptance.** A local improvement remains only when it does not
  regress the broader behavioral battery.
- **Hermetic fixtures.** Concurrent tests own isolated filesystem and database
  state.

## Testing

The full decision record is [[011-tests-find-design-issues]].

- **Edge-case count is a design verdict.** Old code is studied, never
  re-derived from scratch: each problem it already met becomes one specific
  unit test at the surviving owner, and each rule becomes a generative
  property. When a mechanism keeps demanding new edge-case tests instead of
  one property, the implementation shape is wrong — stop fencing and
  re-plan; too many edge cases means the design, not the test suite, needs
  work (owner ruling 2026-07-26).
- **Tests find design issues; structure retains them.** A recurring failure
  class is dissolved by moving its invariant to one choke point, then keeping
  exactly one regression per class. Fencing a symptom with many point tests is
  the anti-pattern.
- **Schemas generate the edge cases.** Generative round-trips over registered
  shapes are the standing totality properties; hand-enumerated edge lists do
  not scale to the contract surface.
- **Every proof is claimed by a recurring surface.** A test or live proof
  counts only while a discovery gate or checkpoint list re-runs it; green once
  is not coverage. Orphan detection is itself computed, never a hand list.
- **Fixture load paths are not the live boot path.** A mechanism proven
  through fixtures still owes one live-boundary proof of its real
  boot/acquisition path; the two are separate proof classes.
- **Localized tests belong to lanes; full suites belong to frozen trees.** A
  lane runs the tests for its own boundary; complete suites are integration
  checkpoints over a coherent frozen tree.
- **Assertions target facts, transitions, envelopes, and Datahike
  `:db.fn/cas` outcomes.** `:db.fn/cas` is reserved for facts two processes
  race to win exactly once: plan freeze from absent to digest, and run claim
  from no process to the process record together with a claim-epoch increment.
  Never exact prose.

- **No datom string-size limit exists; amplification is the real cost.**
  Datahike validates `:db.type/string` as a string rather than imposing a Seon
  byte ceiling. Indexed values amplify storage and query work, so bulk content
  belongs in the content-addressed blob store behind a small ref fact.
  Fressian's chunk buffer is not a database value bound. Measurements and
  conditions live in the active PRD research.

## Process

- **Live behavior falsifies inspection.** Context, web UI, and function changes
  are checked through the actual agent-facing boundary as well as structural
  tests.
- **Use the existing experiment surface.** Per-agent context overrides and the
  sanctioned Inspect tasks provide experiments without another evaluator or
  cluster-wide reset path.

The research authority for dated evidence is the relevant active or archived
PRD `research/` directory. [[roadmap]] records current acceptance evidence and
revalidation work.
