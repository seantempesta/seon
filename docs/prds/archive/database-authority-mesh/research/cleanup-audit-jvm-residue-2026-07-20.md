---
type: research
status: complete
tags: [research, database, architecture]
---

# JVM/Clojure residue audit — 2026-07-20

Read-only audit of every JVM-compiled Clojure file after the runtime
refactor removed the embedded-Datahike JVM application, Integrant
lifecycle, core.async pod topology, JVM renderer/web server, and the
nREPL application path (preserved at tag
`runtime-reliability-pre-refactor-2026-07-13`).

## Dependency ledger (files read)

- `deps.edn` (paths + `:writer`, `:writer-test`, `:build`, `:lint`,
  `:cljs` aliases — no `:dev`/`:nrepl` alias remains)
- `dev/nrepl.clj`
- All 15 `src/**/*.clj`: `seon/dev/{docstring,markdown,restore}.clj`,
  `seon/db/{registry,writer,server,restore_admin,executor,backend,program}.clj`,
  `seon/db/transport/uds.clj`, `seon/db/datahike/schema.clj`,
  `seon/indexing.clj`, `seon/embed.clj`, `seon/embed/preflight.clj`
- `src/seon/embed.cljs` (CLJS counterpart check)
- `script/seon/dev/*.clj` roster and `script/seon/dev/cli.clj`
- Headers of `test/seon/{execution_process,authority_density,embed_writer}_test.clj`
  and the full `test/**/*.clj` file list
- `rg` sweeps: `integrant`, `nrepl`, `seon.(agent|eval|render|web|ui)`
  across `src`, `test`, `script`, `bin`, `deps.edn`, `shadow-cljs.edn`

## Classification table

| File | Class | Basis |
|---|---|---|
| `src/seon/db/registry.clj` | (a) writer | live connection registry for the sole Datahike writer |
| `src/seon/db/writer.clj` | (a) | canonical request interpretation; core.async use is JVM-side and legitimate |
| `src/seon/db/server.clj` | (a) | composition root wiring registry/executor/writer/transport |
| `src/seon/db/executor.clj` | (a) | bounded admission/fairness scheduling |
| `src/seon/db/backend.clj` | (a) | Datahike/Konserve config adapter |
| `src/seon/db/transport/uds.clj` | (a) | UDS delivery only |
| `src/seon/db/program.clj` | (a) | compiled-program fact reconciliation at the authority |
| `src/seon/db/restore_admin.clj` | (a) | isolated-writer restore boundary |
| `src/seon/db/datahike/schema.clj` | (a) | Malli→Datahike bridge |
| `src/seon/embed.clj` | (a) | JVM Proximum/HNSW + Gemini embedding owner |
| `src/seon/embed/preflight.clj` | (a) | `--preflight` gate invoked by `seon.db.server/-main` |
| `src/seon/embed.cljs` | not a duplicate | pod thin client over UDS; complements `embed.clj` (docstring diagram states the split explicitly, `src/seon/embed.cljs:1-30`) |
| `src/seon/indexing.clj` | (b) dev/build | compile-time macros over the CLJS analyzer env; consumed by `src/seon/client.cljs`, `src/seon/agent/ctx/namespaces.cljs` |
| `src/seon/dev/markdown.clj` | (b) | markdown lint/fix for the edit hook |
| `src/seon/dev/docstring.clj` | (b) | docstring lint hook (see open question 1) |
| `src/seon/dev/restore.clj` | (b) | pure restore state machine; required by `seon.db.server` and `restore_admin` (so also load-bearing for (a)) |
| `script/seon/dev/*.clj` (17 files) | (b) | `bin/seon` operator (`bin/seon:6` → `-m seon.dev.cli`); on `:writer-test`/`:cljs` extra-paths only |
| `dev/nrepl.clj` | **(c) RESIDUE** | see below |
| `dev/storage-shootout.js` | (d)-adjacent, out of lane | JS scratch benchmark in `dev/`; flagging for the owner |

## (c) residue detail

### `dev/nrepl.clj` — dead, safe to delete today

- Evidence: `dev/nrepl.clj:9` — "Once connected, use (go) to start the
  **Integrant** system"; `dev/nrepl.clj:36` — `(require 'user)`.
  Integrant and the `user` dev namespace were removed (no first-party
  `user.clj` exists; only `reference-code/` copies).
- Removed mechanism served: the nREPL application path / Integrant JVM app.
- Callers: none. `deps.edn` has no `:dev` or `:nrepl` alias and no
  `nrepl`/`cider` dependency anywhere, and `dev/` is on no classpath
  (`:paths ["src" "resources"]`; aliases add only `test`/`script`). The
  file cannot even load — `nrepl.server` and `cider.nrepl` are not
  resolvable. `rg` finds no reference outside docs history.
- Deletion: **safe today**, no blockers. (`shadow-cljs.edn:8`
  `:nrepl {:port 0}` is Shadow's own embedded nREPL and unrelated.)

No other (c) files found. `rg 'integrant'` matches only docs/history and
`reference-code/`. No `.clj` requires `seon.web`, `seon.ui`, `seon.render`,
or `seon.eval`; the `:seon.agent/id` literals in `seon.db.writer`
(lines 1345, 1355, 1377, 1437) are the writer's own root-agent
bootstrap/provenance facts, not a JVM agent runtime.

## JVM tests

All `test/**/*.clj` files target the current writer (`seon.db.*`),
dev tooling (`test/seon/dev/*` ↔ `script/seon/dev/*`), or Bun-child
proofs (`execution_process_test.clj`, `authority_density_test.clj` —
both spawn real Bun clients against the writer). `core.async` appears
only in `test/seon/db/executor_test.clj`, matching the executor's
legitimate JVM use. No test asserts Integrant, JVM web/render, or
embedded-application behavior.

## Ordered deletion plan

1. Delete `dev/nrepl.clj` — zero callers, zero blockers.
2. (Owner call) delete or relocate `dev/storage-shootout.js`; if both go,
   remove the now-empty `dev/` directory.

## Open questions

1. `src/seon/dev/docstring.clj:193` deliberately reimplements
   `seon.agent.ctx.namespaces/{hidden-ns-name?,test-ns-name?}` because the
   JVM linter cannot load pod CLJS. Documented duplication, not residue;
   a `.cljc` extraction would remove it if ever desired.
2. Untracked top-level `locks/` and `--help/` directories exist in the
   working tree (git status); outside this lane's JVM scope but likely
   operator/CLI stray output worth a look.
