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

## 2. Reach-Selected Fail-Fast Testing (`seon.test-runner`, `seon.fork`)

Tests run in isolated COW forked children (`seon.fork/run-test-worker!`) selected strictly by transitive var reach from changed forms and schemas.

- **Two-tier fail-fast:** Fast tests (`<= 150 ms`, `:seon.config/fast-test-ms`) execute first; any failure short-circuits slower shards immediately.
- **Content-addressed green cache:** Verdicts are keyed by transitive Merkle reach digest (`:seon.test/digest`), so unchanged tests reuse cached green verdicts in `0 ms`.
- **Seed=42 Malli generative checks:** Any changed function with a `:malli/schema` runs 100 deterministic `seed = 42` generative samples automatically.
- **Anti-test-deletion gate:** `seon.test-runner` compares candidate merges against `:main` and blocks merges that silently delete existing tests (`:deleted-main-tests`).
- **Cost budgets:** Tests declaring `:cost/ticks` or `:cost/bytes` fail automatically on overruns.
