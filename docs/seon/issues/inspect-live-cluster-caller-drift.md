---
type: issue
status: open
severity: friction
tags: [issue, agent, component, database, research]
---

# Inspect live callers use retired cluster lifecycle contracts

## Problem

Inspect's offline harness is current, but its live cluster callers still invoke
removed `bin/seon`/`bin/acme` operations and connect through hard-coded ports.
A green offline suite therefore does not prove that pod-backed CLJ, CLJS,
typeahead, restart, or multi-cluster evaluation can run safely.

This blocks live Inspect and paid/model acceptance work. It does not block the
offline Inspect suite or ordinary default-cluster development.

## Evidence

The dependency/Shadow/MCP audit found:

- `src-inspect-ai/src/seon_inspect/cluster.py` invokes removed `bench-bundle`,
  `cluster create`, `cluster destroy`, and per-pod restart operations, and
  derives ephemeral port files outside the current target contract;
- `bench_common.py` connects directly to writer port `7891`;
- `typeahead_corpus.py` runs old `bin/acme start pod` / `restart pod` commands,
  defaults to web port `7980` and writer port `7981`, and reads the legacy
  cluster layout directly;
- associated docstrings and runbooks describe the same retired supervisor,
  frozen-bundle, registry, and port behavior.

Current `bin/seon` exposes target-level `up`, `down`, `restart`, structured
`status`, one artifact manifest, and scoped reset—not the per-pod/create/
destroy/bench-bundle surface these callers assume. The remaining cluster
lifecycle, lease, and artifact-flavor contract is roadmap work; callers cannot
safely reconstruct it with subprocess strings, arbitrary writer eval, or
guessed ports.

This is distinct from `acme-operator-migration-drift.md`. That issue owns the
ACME process/artifact/database migration itself; this issue owns Inspect's
live consumers after the current operator boundary exists.

## Owner

`src-inspect-ai/src/seon_inspect/cluster.py`, `bench_common.py`,
`typeahead_corpus.py`, and their tests/runbooks, consuming the one structured
operator lifecycle/lease/artifact contract rather than owning a parallel
supervisor.

## Acceptance

- The operator exposes one structured cluster lease with cluster/database
  identity, artifact digest/flavor, owned process identities, dynamically
  discovered web/CLJ/CLJS endpoints, and bounded create/restart/release
  transitions. Lease cleanup is idempotent and ownership-fenced.
- Every live Inspect caller and active runbook consumes that contract. No
  removed verb, `pod-<name>` convention, hard-coded writer/web port, direct
  registry mutation, arbitrary Clojure lifecycle form, or private port-file
  naming remains.
- Frozen/live artifact identity is pinned per sample through the operator's
  manifest. A restart either preserves the declared artifact/config lease or
  fails loudly; concurrent samples cannot share mutable Shadow/cache/database
  state accidentally.
- Timeout, failed boot, stale lease, foreign port owner, partial restart, and
  evaluator cancellation preserve evidence and release only owned resources;
  they never destroy another sample, ACME, or the default cluster.
- The existing offline Python suite stays green, then one operator-backed live
  smoke proves CLJ read-back, CLJS/pod execution, typeahead corpus generation,
  restart continuity, dynamic endpoint discovery, and complete lease cleanup.
