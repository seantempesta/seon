---
type: prd
status: active
tags: [prd, agent]
---

# Agentic tool refinement roadmap

## Outcome

Make Seon's ordinary functions usable by increasingly small models through
the normal dynamic namespace context. Graduate on a frozen representative
Inspect AI suite with at least 90% deterministic success overall, explicit
per-category floors, honest infrastructure-failure accounting, and durable
restart/read-back evidence.

## Current position — 2026-07-14

The dedicated branch and worktree are established. Prior worktree and patch
audits found no safe missing source commit to cherry-pick: the stable planning,
Inspect, toolkit, and function-surface gains are integrated or superseded.
Display-v3's valid findings remain requirements for one database-derived,
versioned export; its renderer and synthetic-card implementations are rejected.

The isolated ACME checkout initially failed because pinned submodules and the
locked npm closure were absent. After initializing the selected dependency
sources and running `npm ci`, the current writer, `acme-client`, bootstrap,
CSS, watcher, writer, and pod built successfully. The ordinary `acme/root`
MCP coordinate then selected an older checkout's live pod. `bin/acme` now
honors an explicit `SEON_CLUSTER_DIR`; the lane is live as
`acme-agentic-tool-refinement` at port 8094 and the repository MCP server
proved the selected pod's cwd and PID.

The first fresh ordinary-agent render is the current baseline:

- namespaces: 21,839 estimated tokens;
- canvas: 148;
- plan: 130;
- function menu: 258; and
- transcript: 157.

The namespace block correctly renders the current namespace in full and all
sixteen configured required namespaces as inert compact cards with public
function names, named arguments, complete input/output contracts, and schema
definitions. Its size and relevance have not yet passed the small-model test.

## Experimental contract

Inspect AI owns all simulations, tasks, solvers, and scorers. The lane freezes
ordinary system prompt and context-block prose during a tool-surface experiment.
Permitted refinement surfaces are namespace placement, default requires,
function identity, line-one description, argument/key names, complete Malli
input/output schemas, honest envelopes, and consolidation of overlapping
functions in their existing owner.

Failures are classified as tool absent, tool not required, wrong selection,
unclear identity, unclear description, opaque schema, unclear arguments,
overlap, misleading envelope, unactionable error, missing fact, plan failure,
verification failure, sandbox/bridge failure, model reasoning failure, or
benchmark/scorer failure.

## Ordered work

1. Audit the live ordinary-agent namespace surface by namespace, callable,
   schema closure, repeated tokens, public/internal eligibility, and normal
   task category. Preserve full contracts while removing only proven noise or
   duplication through the one renderer/program graph.
2. Inventory the installed Inspect and inspect-evals catalogs, exact local
   model/provider client, and selected sandbox implementation. Freeze small
   deterministic development, milestone, and blind memberships before tuning.
3. Establish raw-model and unchanged-Seon baselines with a 4B-or-smaller model,
   then probe 3B, 2B, 1.5B, and sub-1B models where locally practical.
4. Cluster failures and change the smallest current owner. Verify through a
   focused mechanical test, the original live ACME REPL form, and the exact
   failed Inspect samples.
5. Compare equal-budget arms: no explicit plan, small-model-authored plan,
   large planning proposal encoded by the small executor, and the optional
   pretransacted diagnostic plan. Prove database outcome, provenance,
   expectation-checked close, report-before-close, and restart resumption.
6. Freeze the surface, open the blind set once, preserve raw logs, dataset and
   dependency locks, model/artifact identity, scorecard, classifications, and
   ACME restart/read-back evidence.

## Open blockers

- `inspect-source-dependency-is-not-content-pinned.md` — the mutable local
  Inspect source dependency prevents reproducible scored claims.
- `inspect-live-cluster-caller-drift.md` — concurrent per-sample live clusters
  still need the operator's ownership-fenced lease and coordinate contract.
- `autocomplete-data-quality-pipeline-drift.md` — runtime and Inspect need one
  structured, versioned, schema-closed export.
- `deprecated-skill-render-functions-indexed.md` — stale public functions remain
  eligible distractors.

No blocker authorizes another harness or context-coaching path.
