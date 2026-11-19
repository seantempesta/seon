# System Health and Fail-Fast Reach Testing

System Health (`seon.health`) is a pure query over Datahike facts with zero polling, zero duplicated state, and identical rendering in the Web UI and agent `@seon.ctx/ctx`.

## 1. Derived Structural Rules (`seon.health/report`)

Every health report computes six pure structural & global checks in milliseconds:

- `:blocking-in-flow-proc` (`:error`): Flags any `*-proc` var in `src/` whose transitive call graph reaches `<!!`, `>!!`, `alts!!`, or `Thread/sleep` without an explicit `^:seon.flow/edge` tag — preventing UI and Flow lockups before merge.
- `:state-outside-flow` (`:warning`): Flags top-level `atom`, `volatile!`, `promise`, or `agent` defs in `src/` not tagged `^:seon.flow/edge`.
- `:used-before-defined` (`:error`): Flags same-namespace forward var references without `(declare ...)` that would break cold AOT builds.
- `:unschemed-attribute` (`:error`): Flags any `:seon.*/*` attribute installed in Datahike (`(:schema db)`) lacking a registered Malli `:seon.schema/key`.
- `:retyped-attribute` & `:schema-breaks-stored-data` (`:error`, checked on merge in `seon.health/caused-by-branch`): Blocks any schema change that alters an installed Datahike attribute's `:db/valueType`/`:db/cardinality` or fails validation against existing stored datoms.
- `:failing-test` (`:error`): Global view of failed `:seon.test.run/*` digests across `:main` and active branches — zero passing tests clutter context.
- `:proc-fault` (`:error`): Unresolved Flow/runtime faults recorded in `:seon.health.fault/*`.

## 2. Efficiency Advisories & Scope-Routed Agent Context (`:warning`, never blocking)

Inefficiencies surface automatically as `:warning` advisories (`:ok?` remains `true`) routed by `:seon.agent/scope` subtree so each specialist sees only its own namespace issues while `:agent/root` sees the roll-up:

- **Static code advisories (rendered inside specialist `ns-tile`, zero KV-cache churn):**
  - `:nested-db-scan`: Detects `d/datoms :aevt` or `d/q` inside loop bodies (`O(N * M)` scans) without false-positiving on single-pass outer loops.
  - `:oversized-namespace`: Flags non-test `src/` namespaces `> 8k` estimated tokens as candidates to split into cohesive `2k–6k` tok child namespaces.
- **Live telemetry & runtime outlier advisories (rendered in ephemeral `<neighborhood-activity>` tail, zero KV-cache churn):**
  - `:slow-test` (`> 1000 ms`) and `:cost-regression` (`> 2.5x` ticks or bytes jump vs prior passing verdict on the same `:seon.test/var`).
  - `:low-cache-hit` (multi-turn KV-cache hit `< 60%`) and `:heavy-eval` (`> 128 MB`, `> 50M` ticks, or budget overrun).
  - `:slow-render` (`:web` frame `> 40 ms`) and `:slow-wire-build` (`:agents` context build `> 120 ms` or `> 80 MB`) — threshold-only deduplicated `:seon.health.fault/*` warnings that auto-retract via `[:db/retractEntity ...]` once a subsequent run passes cleanly under budget (retaining the incident in Datahike `d/history`).

## 3. Reach-Selected Fail-Fast Testing (`seon.test-runner`, `seon.fork`)

Tests run in isolated COW forked children (`seon.fork/run-test-worker!`) selected strictly by transitive var reach from changed forms and schemas.

- **Two-tier fail-fast:** Fast tests (`<= 150 ms`, `:seon.config/fast-test-ms`) execute first; any failure short-circuits slower shards immediately.
- **Content-addressed green cache:** Verdicts are keyed by transitive Merkle reach digest (`:seon.test/digest`), so unchanged tests reuse cached green verdicts in `0 ms`.
- **Seed=42 Malli generative checks:** Any changed function with a `:malli/schema` runs 100 deterministic `seed = 42` generative samples automatically.
- **Anti-test-deletion gate:** `seon.test-runner` compares candidate merges against `:main` and blocks merges that silently delete existing tests (`:deleted-main-tests`).
- **Cost budgets:** Tests declaring `:cost/ticks` or `:cost/bytes` fail automatically on overruns.
