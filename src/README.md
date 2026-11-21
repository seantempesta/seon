# src/

Seon's platform source tree (`seon.*`), organized into cohesive `< 8k` token namespaces:

- `seon.main`, `seon.server`, `seon.switchover`, `seon.store`, `seon.schema.*`, `seon.merge`, `seon.git*` — Boot, HTTP/MCP routing, Malli-derived LMDB store, 3-way merge, and atomic Git write-back.
- `seon.transactor*`, `seon.reconciler`, `seon.agents`, `seon.model*`, `seon.test-runner`, `seon.browser` — Static Flow graph procs.
- `seon.code*` — Lossless Clojure/Markdown/EDN parsing, CST `rewrite-clj` editing, refactoring, reach graph, and eval execution.
- `seon.fork*` — COW `ForkParent`, persistent worker runtime, and `user/*` REPL helpers (`helpers`, `calls`, `container`).
- `seon.health*` — Pure derived structural rules, contract checks, efficiency advisories, and learning metrics.
- `seon.render*`, `seon.web*` — 120 fps `datahike.reads` incremental walk, Living Canvas & Chronicle, context tiles, and Clojure-data CSS.
- `seon.bench*` — Parallel isolated cluster trials (`cluster`, `helpers`) and shared streaming quota proxy (`proxy`).

See [docs/README.md](../docs/README.md) for the full documentation map. Every green `seon_merge` writes touched files here back from Datahike `:main` automatically.
