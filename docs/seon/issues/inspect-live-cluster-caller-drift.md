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

The 2026-07-14 lane-integration audit re-ran the complete offline Inspect
suite: 314 tests passed with eight expected environment-gated skips in 7.86
seconds. This sharpens the boundary rather than closing it: pure task/scorer
logic is healthy, while no operator-backed CLJ/CLJS/restart/cleanup journey was
exercised. The active PRD's older 293/eight count is stale documentation.

Current `bin/seon` exposes target-level `up`, `down`, `restart`, structured
`status`, one artifact manifest, and scoped reset—not the per-pod/create/
destroy/bench-bundle surface these callers assume. The remaining cluster
lifecycle, lease, and artifact-flavor contract is roadmap work; callers cannot
safely reconstruct it with subprocess strings, arbitrary writer eval, or
guessed ports.

The unified development MCP is now complete and proves dynamic CLJ and CLJS
discovery for an already-owned cluster. That removes the old transport gap but
does not create, lease, freeze, restart, or release Inspect sample clusters.
Live Inspect must consume the operator lifecycle/lease boundary rather than
shelling through MCP as a substitute supervisor.

The bounded caller migration on 2026-07-14 removed the unsafe fallbacks that
could be changed independently of that missing seam:

- `cluster.py` no longer invokes removed create, fork, per-pod restart,
  destroy, or `bench-bundle` operations. Lease-dependent paths raise
  `ClusterLeaseUnavailable` before any subprocess or model call and name the
  missing identity, artifact, endpoint, restart, and release fields.
- `typeahead_corpus.py` no longer starts or restarts ACME, defaults web/writer
  ports, or embeds the old ACME port pair in a generated sample. Static corpus
  capture requires explicit endpoints; a restart-bearing sample requires an
  injected ownership-fenced restart transition.
- the Inspect README now marks per-sample and live restart modes paused while
  retaining static explicitly provisioned URL mode and the offline scorer/
  choreography tests.

The first operator-owned half is now present. `bin/seon status --edn` projects
the configured cluster/database identity, canonical artifact flavor and
digests, exact owned process identities, and dynamically discovered web, CLJ,
and CLJS endpoints. Missing process records with live listeners surface as a
foreign ownership conflict, and the current semantic `up`, `restart`, and
`down` transitions remain target-scoped. This is sufficient for ACME's fixed
target to stop guessing ports and artifact identity.

The exact remaining blocker is the per-sample lease rather than endpoint
discovery. The operator still has no lease owner/token, isolated target
coordinate allocator, frozen artifact selection, or token-fenced
create/restart/release contract. Consequently Inspect cannot safely allocate
concurrent sample process/cache/database namespaces or prove that cleanup owns
the target it would stop. The read-only legacy frozen-bundle identity helper
remains solely for offline contamination fixtures; no live caller can build or
select that artifact through a removed command.

The source/evidence audit found two additional migration edges behind the same
owner. `typeahead_corpus.py` requires explicit endpoints now, but still sends
raw `datahike.api` forms through the writer REPL and reads
`data/clusters/<cluster>/blobs/<hash>` directly. That bypasses the typed
database/debug/blob surfaces and assumes a checkout-local cluster layout.
Also, selected run paths copy native Inspect `.eval` logs on a best-effort
basis and suppress copy errors. Lease finalization must make the complete raw
evidence bundle mandatory; a score without its log/coordinate bundle is not an
accepted run.

The frozen Qwen 3.5 2B BFCL baseline makes that loss concrete. Its native log
preserves ten sample ids, zero scores, pod agent ids, three-to-seven-turn
`:no-forms` closures, eval counts, and model configuration, but no Seon database
coordinate, prompt/reply blob refs, eval rows, or transcript. The later live
ACME database and its history contain no sampled `neat-rice-taste` identity, so
the exact model replies cannot be reconstructed. The run is scoreable but not
forensically adequate for deciding whether context, parser repair, or model
reasoning caused the failure.

The explicitly provisioned static-URL path now closes that evidence-loss edge:
`POST /agents/run` returns the final complete database coordinate and the
ordered exact prompt/reply/error bundle for every turn, and the Python solver
stores it unchanged in native sample metadata. A 2026-07-15 live ACME BFCL
smoke preserved four Qwen 3.5 2B turns that cluster cleanup would otherwise
have made inaccessible. The first wrong-identity JSON call and three empty
replies are directly visible in the `.eval`. This does not close the issue:
concurrent per-sample allocation, restart, and cleanup still require the
ownership-fenced lease described below.

A generated-workflow probe exposed one remaining static-path contradiction.
`planning.fetch_eval_rows` still sent a raw sentinel-printing form to the
writer socket, but the writer now speaks io-prepl event maps. The query reached
the writer while the Python parser failed as `JSONDecodeError`, leaving the
native milestone scorer without rows. The composition door now derives an
ordered `eval_evidence` projection from the same final database value and exact
request turn set already used for counts. It includes only eval id/time/source,
success, and present narration; results, stdout, and error stacks stay out.
Milestone Inspect code consumes this response and the obsolete raw writer
read-back is removed from that accepted path.

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
- Typeahead/planning read-back consumes the lease's typed MCP/debug/blob and
  database boundaries. No arbitrary writer form or direct cluster-directory
  blob read remains in an accepted live path, and missing native Inspect logs
  fail evidence finalization rather than being silently ignored.
