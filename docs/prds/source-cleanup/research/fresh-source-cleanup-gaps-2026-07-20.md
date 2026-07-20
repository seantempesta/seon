---
type: research
status: complete
tags: [research, architecture, web, database, agent]
---

# Fresh source-cleanup gap audit (2026-07-20)

This pass began from current source after the six original cleanup reports. It
records only additions or corrections to the source-cleanup PRD; it does not
restate their inventories.

## Missing work confirmed

### Router still has two declaration authorities

`src/seon/route.cljs:89-113` owns seeded database route rows, while
`src/seon/web/router.cljs:228-305` owns a literal static supplement containing
product, lifecycle, debug, data, and operator routes. The latter file itself
flags the secondary POST doors for migration. This conflicts with
`docs/seon/architecture/ui.md:304-316`, where reitit is a pure derivation of
route datoms. Tracked by
[[../../../seon/issues/static-routes-bypass-database-route-authority]].

Do not blindly turn every row into an agent-writable datom. Classify first:
ordinary product/debug routes are database route data; optional operator doors
derive from launch-bound capabilities; static assets and a proven
pre-database readiness endpoint may remain bootstrap code.

### Live functions carry false deprecation claims

`seon.agent.ctx/file-block`, `file-block-ai`, and `file-block-html` are active
manifest/render mechanisms. `my.skills/skill-block` is installed by the live
load path and tested. Their docstrings nevertheless begin with `DEPRECATED`,
which makes current functions appear eligible for deletion and leaves them in
the program graph as contradictory callable rows. The already-open authority
is [[../../../seon/issues/deprecated-skill-render-functions-indexed]]. Stage 5
must resolve it, not merely rename fixture prose.

### Localized authorities are part of the client rename

`src/seon/AGENTS.md` still names “Pod process lifecycle” and describes `.cljs`
as the JavaScript pod. Nested source authorities and their adapter symlinks are
active implementation inputs, not living-doc sweep trivia. Stage 2 must update
them under the same source freeze and verify no authority teaches the retired
term afterward.

### Configuration refresh still needs an executable proof

The config design proposes refreshing
`db/install-configuration-context!` from committed-transaction delivery.
`seon.db.internal/enter-tx-context!` uses Node/Bun `AsyncLocalStorage.enterWith`,
which changes the current async context and descendants; it is not established
that already-created long-lived fibers observe replacement values. Before
selecting this design, prove two pre-existing independent fibers both see the
new configuration. If not, move the live singleton acquisition to the
operation/session owner instead of adding another ambient cache.

## Repeated scans that did not justify new cleanup work

- Direct `datahike.api` calls under `src/seon/db/`, JVM authority tests, and
  the embedding authority/preflight are dependency-boundary code. The known
  CLJS canvas test remains the actionable production-boundary violation.
- `seon.worker-eval` and `seon.worker-validator` are standalone downstream
  diffusion-oracle build products, not a second Seon agent execution path.
- Filesystem calls inside `seon.agent.fs`, `my.blob`, bootstrap/artifact
  loading, logging, and config loading belong to their concrete filesystem
  seams. A generic filesystem wrapper would add rather than remove a mechanism.
- Character counts used for substring indexes, column alignment, storage
  weights, or machine evidence are not human-visible size estimates. Human
  displays still use `seon.ai.tokens/estimate`.
- Process-local atoms owning compiler state, IPC correlation, log handles, or
  invocation-local accumulation are not database-state violations merely
  because they are mutable.

## Plan corrections made from this audit

- Stage 1.5 now owns the universal browser and child-value transport.
- Stage 2 includes localized instruction authorities in the atomic rename.
- Stage 4 includes route-authority collapse beside config reconciliation.
- Stage 5 retains and wires usage normalization and resolves the false
  deprecation issue.
