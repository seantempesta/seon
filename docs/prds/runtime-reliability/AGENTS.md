---
type: orchestrator
status: active
tags: [orchestrator, prd, database, flow, agent]
---

# Runtime reliability refactor — working context

## Current state

The CLJS pod works end to end, but process boot, agent mint/resume, program
reconciliation, schema restoration, and live rendering still contain duplicate
or overly broad paths. A warm web mint spends roughly eight seconds doing
cluster-wide work. A grown-store open feed repeatedly invokes expensive
render/SCI work and produces transient RSS sawtoothing around 1.4–2.5 GB.

The core source/live audits, integrated design, ID study, and Datahike
fork/restore study are complete. No refactor implementation has begun on this
branch. The authoritative target is
[[provenance-and-lifecycle-design]] and the ordered implementation/commit plan
is [[roadmap]].

## How to run it

```bash
bin/seon status pod
bin/seon restart pod
bin/test-cljs
curl -fsS http://127.0.0.1:7890/agents >/dev/null
```

Use the default pod/store for live proof. Leave the ACME pod alone unless its
owner explicitly places it in scope. Use the `browser-automation` skill for UI
drives; verify long-lived gzip SSE with a Node gunzip client because the browser
bridge does not proxy it reliably.

## Load-bearing findings

- `/agents/new` currently re-enters cluster startup. About 7.8–8.1 seconds of an
  8.43–8.89 second mint is unrelated cluster work.
- Core function/test builders reread files per var and run again inside ghost
  pruning. Deletion should be an exact reconciliation result, not a second scan.
- Native Datahike schema and persistent indexes already reopen from the
  Konserve root. Complete schema reassertion on every open is redundant.
- Transaction metadata is processed against `db-before`; the provenance attrs
  and ref targets require one explicit un-attributed genesis base case.
- The durable provenance model is only `:seon.db/user` plus
  `:seon.db/process`. User refs the existing root/human/agent; processes are
  boot, config, and REPL. No duplicate user table and no turn/eval transaction
  metadata; ordinary durable turn/eval domain records remain.
- Config is exact authority for declared populations/attributes. It must repair
  that subset after partial state, preserve outside facts, and write nothing
  when converged.
- Malli source is currently overloaded/truncated and entity-schema decomposition
  is a stale derived projection. Store full canonical forms and rebuild one
  validated registry/catalog projection.
- Instrumentation currently scans all functions on every mint and Shadow reload,
  misses same-key schema changes, and relies on a duplicate Malli roster. The
  target is one boot-only exact-data call plus one old/new dependency-aware
  delta operation after committed definition/schema changes.
- Program reconstruction safely loads strict declarations only. Arbitrary eval
  effects and process-local values are never replayed.
- UI state belongs to one subscription per normalized view key. Actual
  `seon.db` read results, not provenance or keyword literals, determine dirty
  render units.
- The current ID has only 140,608 choices per minute and a collision can become
  a Datahike upsert. Only `:seon.agent/id` gets package-owned readable words;
  every other generated persistent identity gets the compact package adapter
  through one schema-driven atomic allocator.

## Settled — do not re-litigate

- This is reliability/provenance, not authentication or authorization.
- Persist resulting facts, not algorithm traces or derived status.
- Provenance is transaction metadata and is per datom; it is not entity
  ownership or reconciliation authority.
- Root is an existing agent/user. Agents are their own user identities.
- Boot, config, and REPL are process refs, not users or operation enums.
- Current core and config are independent optional operation overlays, never a
  stored mode. A crash-recovery intent freezes supplied canonical payloads only
  until the fenced transition completes; later config-free cold boot preserves
  database facts without applying current source or `config/system.edn`.
- The serialized allocator enforces generated-value uniqueness across every
  generator-managed identity attribute in one logical database/branch through
  indexed lookups. Lookup refs remain attribute-qualified; no global identity
  registry or duplicate universal-id datom is added.
- No arbitrary eval replay and no promise to reconstruct non-database state.
- No persisted UI dependency/subscription/dirty/output-cache entities.
- Every mutable runtime cell is classified: irreducible handle, DB-derived
  projection with rebuild/invalidation, missing fact moved to Datahike, or
  duplicate deleted. Self-contained ephemeral registries are fine when restart
  loss is harmless; no atom is a second durable authority.
- No second implementation, `v2` namespace, or compatibility path. Migrate and
  delete the superseded live mechanism in the same phase.
- `datahike.api/with` is a test oracle; production deltas are explicit Clojure
  data processing.
- Tests assert structural behavior, not context wording.
- Commit and live-prove each roadmap phase before starting the next.

## Open owner choices

No owner choice remains before implementation. Route
removal becomes absence in canonical config; protected core schema collisions
fail loudly; runtime-captured reads are the dependency truth; stored
`:seon.fn/read-attrs` is deleted after recency proof; expensive HTML twins are
windowed while exact raw AI text remains available; over-budget agent renders
fail loudly rather than silently clipping data; the rolling header rate is
removed; and read-only `as-of`, isolated writable forks, and full known-state
restore are all in this refactor. The exact branch/switch lifecycle and pinned
Datahike changes are specified in the completed focused source audit.

The Phase 5 operator door is deliberately explicit: ordinary
`bin/seon restart pod` preserves database config; `bin/seon config apply
--config <path>` applies a selected manifest; `--empty` selects the explicit
empty manifest and is distinct from no input. These config subcommands are
target semantics and are not available until Phase 5 lands.

## Ordered next steps

1. Commit the integrated design/research package.
2. Execute [[roadmap]] Phase 0 baseline observability.
3. Land the atomic identity allocator, then split cluster boot from mint/resume
   and live-prove sub-second warm mint.
4. Land genesis/provenance, then the exact reconciler/config recovery surface.
5. Correct schema/program/runtime reconstruction and delete pruner/healer paths.
6. Land as-of simulation, isolated writable forks, and the quiesced full-restore
   lifecycle.
7. Unify live subscriptions/feeds, add observed-read invalidation, then bound
   legitimate renders.
8. Run the full cold/restart/agent-workflow/browser/feed/CPU/RSS acceptance
   matrix and graduate the PRD.

## Entry points

- [[roadmap]] — dependency order, state-transition work, proof, and commit
  boundaries.
- [[provenance-and-lifecycle-design]] — authoritative target decisions.
- [[system-audit-2026-07-12]] — current code writer/reader/effect inventory.
- [[research/provenance-users-processes-and-ids-2026-07-12]] — live provenance
  queries, migration evidence, and original compact-ID analysis.
- [[research/config-schema-runtime-restoration-2026-07-12]] — Datahike reopen,
  config exactness, genesis ordering, and Malli restoration.
- [[research/runtime-reconstruction-and-replay-boundary-2026-07-12]] — safe
  declaration loading vs inspection vs replication recovery.
- [[research/reactive-ui-dependency-routing-2026-07-12]] — Reitit/Datahike/
  Datastar/Hyperlith source audit and performance design.
- [[research/human-readable-word-ids-datahike-and-tokenization-2026-07-12]] —
  final package, collision, Datahike-index, storage, and tokenizer evidence.
- [[research/datahike-as-of-fork-and-restore-2026-07-12]] — final Datahike/
  Konserve branch, simulation, coordinate, and live-restore evidence.
- [[research/incremental-instrumentation-2026-07-12]] — current global call
  sites, Malli ref/wrapper behavior, exact-data target, and acceptance matrix.
- [[docs/seon/architecture/agent-runtime]], [[docs/seon/architecture/data-model]],
  and [[docs/seon/architecture/ui]] — ideal system docs to keep current.
- `src/seon/client.cljs`, `src/seon/state.cljs`, `src/seon/db.cljs`,
  `src/seon/db/internal.cljs`, and `src/seon/web/datastar.cljs` — main code
  entry points.
