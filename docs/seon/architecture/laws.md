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

- **Process jobs stay narrow.** The writer performs transactions and emits the
  committed feed; it never executes agent code or serves HTTP. The web-render
  process performs trusted database-value derivation and HTTP/SSE delivery; it
  never executes agent code. Claimants execute agent work. The Bun leaf host is
  disposable native-effect capacity. Process replacement does not broaden a
  role.
- **Claims and receipts outrank process memory.** A run's claimant, epoch, and
  heartbeat establish custody; the turn phase and attempt/eval receipts
  establish admitted and completed work. Recovery derives from those facts.
- **Same source or same artifact.** Cross-runtime policy is one portable
  `.cljc` core or one shared compiled artifact. Each tier adds one native leaf,
  with reader conditionals only at entry expressions. Hand-mirrored wrappers
  and duplicated drivers are not interfaces.
- **Replacement is one mechanism.** When consumers move to the portable owner,
  the superseded path is deleted. Compatibility shims and dual-maintained
  drivers, renderers, or capability surfaces violate the architecture.

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
