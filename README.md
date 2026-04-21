# seon

A Clojure runtime designed so AI agents can write, own, and evolve software reliably. Every namespace is wired in as a `core.async.flow` process with a typed message envelope and an injected, schema-validated state atom; functions are discovered via Malli schema contracts queried from a Datalog graph (Datalevin / Datahike on LMDB) rather than by name lookup or file imports.

This is the **current canonical seon project**. Active development.

## Orientation

- [`AGENT.md`](AGENT.md) — guide for AI agents working in this repo
- [`CLAUDE.md`](CLAUDE.md) — agent index and operating rules
- [`ORCHESTRATOR.md`](ORCHESTRATOR.md) — orchestration model
- [`docs/seon/_dashboard.md`](docs/seon/_dashboard.md) — component map
- [`docs/seon/vision/index.md`](docs/seon/vision/index.md) — design vision
- [`docs/seon/concepts/namespace-as-process.md`](docs/seon/concepts/namespace-as-process.md) — the core primitive

## Lineage

This project consolidated ~18 months of experiments documented in predecessor repositories.

**Predecessors** (chronological):

- [seon-2024-10-xtdb-biff](https://github.com/seantempesta/seon-2024-10-xtdb-biff) — first XTDB+Biff exploration (Oct 2024); introduced the `seon.repl` namespace pattern
- [seon-2024-10-kit-migration](https://github.com/seantempesta/seon-2024-10-kit-migration) — concurrent Kit framework experiment (Oct 2024 → Jan 2025); 45 commits exploring agentic-runtime ideas with ClJS/Reagent
- [seon-2025-02-architecture](https://github.com/seantempesta/seon-2025-02-architecture) — **primary architectural realization** (Feb–Mar 2025); the ~72KB README in that repo documents namespace-as-process, code-graph, schema-discovery, REPL-pipeline, and multi-agent isolation appearing together for the first time
- [seon-2025-11-trading-domain](https://github.com/seantempesta/seon-2025-11-trading-domain) — immediate git ancestor (Nov–Dec 2025); the codebase that became this repo via `git mv` on 2025-12-13

This repo's first commit (2025-12-13) was a copy of `ml-options-trading`, since published as [seon-2025-11-trading-domain](https://github.com/seantempesta/seon-2025-11-trading-domain). The architectural patterns it implements trace back through [seon-2025-02-architecture](https://github.com/seantempesta/seon-2025-02-architecture) to the October 2024 XTDB+Biff and Kit migration experiments.
