# Seon Documentation Map

Seon is a self-improving Clojure/Jolt runtime where autonomous agents work in isolated Copy-on-Write OS processes on Datahike branches, prove changes by reach, and merge into `:main` with zero outside infrastructure.

Every document in `docs/` (plus `AGENTS.md`) is parsed losslessly into `:seon.code.doc/*` entities in Datahike (`seon.code.parse/parse-md-sections`) so agents can read any section via `(user/source "docs/<file>.md#<slug>")`, edit it via `(user/doc! ...)`, and automatically see matching sections in `ns-tile` (`seon.code.reach/ns-linked-docs` links `docs/<stem>.md` to `seon.<stem>*` and matches namespace mentions).

## Current System Docs

- [principles.md](principles.md): Founding engineering principles, design criteria, and enforcement map.
- [architecture.md](architecture.md): Static Flow graph, COW `ForkParent`, Malli -> Datahike derivation, cold-boot AOT overlay sync, and `Datahike :main === Git` write-back.
- [agent.md](agent.md): Agent REPL handbook — `@seon.ctx/ctx`, `result/e<n>`, all `user/*` code/doc/container/benchmark helpers, normal worker context tiles, and `seon_*` MCP tools.
- [render.md](render.md): Unified 120 fps `datahike.reads` incremental walk (`lru-get!` read-replay), Living Canvas & Chronicle (`#cv-bench-runs`, `#cv-living-hero`), Reagent events, schema-attached tiles, Clojure-data CSS (`seon.web.styles`), and single-source LLM wire assembly.
- [health.md](health.md): Pure derived health rules (`:blocking-in-flow-proc`, `:invalid-contract`, `:used-before-defined`, `:unschemed-attribute`, `:oversized-namespace`, `:large-function`, etc.), reach testing + `seed = 42` generative checks, eval error hints, and the `:seon.fix/*` class-fix ledger.
- [bench.md](bench.md): Parallel isolated Seon A/B trials (`seon.bench.cluster`, `seon.bench.proxy`), real `:seon.task/*` no-rigging discipline, Harbor / Terminal-Bench 2.1 Host-Bridge Mode (`seon.fork.container`), and measured baselines.
- [plan.md](plan.md): Shipped phases, active architectural roadmap, and backlog synchronized with `resources/seon/tasks.edn`.
- [decisions.md](decisions.md): Owner rulings and architectural decisions with dates and verbatim context.
- [forks.md](forks.md): Tracked library forks (`jolt`, `chez`, `datahike`, `konserve`, `malli`, `persistent-sorted-set`) and what each carries.
