# Seon Documentation Map

Seon is a self-improving Clojure/Jolt runtime where autonomous agents work in isolated Copy-on-Write OS processes on Datahike branches, prove changes by reach, and merge into `:main` with zero outside infrastructure.

Every document below is parsed losslessly into `:seon.code.doc/*` entities in Datahike (`seon.code/read-md`) so agents can pull any section directly into `@seon.ctx/ctx` or their Level 2 workspace tiles.

## Current System Docs

- [principles.md](principles.md): Founding engineering principles, design criteria, and when to stop and raise the alarm.
- [architecture.md](architecture.md): The 8-proc static Flow graph, COW `ForkParent`, Malli -> Datahike auto-derivation, `O(|Δ|)` rails, and `Datahike :main === Git main` write-back.
- [agent.md](agent.md): Agent REPL handbook — `@seon.ctx/ctx`, live `result/e<n>` runtime refs, `user/*` helpers, multi-tool evals, branch sync, and `seon_*` MCP tools.
- [render.md](render.md): Unified 120fps `datahike.reads` incremental walk, Reagent `:on-click`/`:on-submit` wiring, schema-attached `:seon.render/hiccup` tiles, 3-level progressive-disclosure UI (`/`, `/agent/<slug>`, `/root/debug`), and single-source wire assembly with XML session framing + ephemeral viewport screenshots.
- [health.md](health.md): Pure derived health rules (`:blocking-in-flow-proc`, `:state-outside-flow`, `:retyped-attribute`, `:schema-breaks-stored-data`, `:used-before-defined`, `:unschemed-attribute`, `:failing-test`, `:proc-fault`) and fail-fast reach testing + seed=42 generative checks.
- [plan.md](plan.md): Active architectural roadmap, multiplatform standalone app + embedded browser strategy (`webview/webview` + optional headless `Blitz`), and open backlog items.
- [decisions.md](decisions.md): Owner rulings and architectural decisions with dates and verbatim context.
- [forks.md](forks.md): Tracked library forks (`chez`, `jolt`, `datahike`, `konserve`, `malli`, `persistent-sorted-set`, `http-client`) and what each carries.
