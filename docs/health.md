# System Health, Reach Testing, and the `:seon.fix/*` Ledger

System Health (`seon.health`, `seon.health.static`, `seon.health.contracts`, `seon.health.efficiency`, `seon.health.metrics`) is a pure query over Datahike facts with zero polling, zero duplicated state, and identical rendering in the Web UI and agent `@seon.ctx/ctx`.

## 1. Derived Structural Rules (`seon.health/report`, `:error` blocks merge)

Every health report computes pure structural, schema, and global integrity checks:

- `:blocking-in-flow-proc`: Flags any `*-proc` var in `src/` whose transitive call graph reaches `<!!`, `>!!`, `alts!!`, or `Thread/sleep` without an explicit `^:seon.flow/edge` tag.
- `:used-before-defined`: Flags same-namespace forward var references that would break cold AOT builds; carries a `:fix` descriptor so `(user/autofix-health!)` or `(user/edit-fn!)` auto-reorders forms topologically via `seon.code.cst.ns/topo-order-ns-forms`.
- `:invalid-contract` (`seon.health.contracts/invalid-contract-violations`): Validates every `:malli/schema` metadata form upfront (catching invalid schema types like `:number` instead of `number?` on Turn 1; also enforced in `seon.code.edit/add-fn!` and `set-meta!`).
- `:unschemed-attribute`: Flags any `:seon.*/*` attribute installed in Datahike lacking a registered Malli `:seon.schema/key`.
- `:retyped-attribute` & `:schema-breaks-stored-data` (`seon.health/caused-by-branch`): Blocks any merge that alters an installed Datahike attribute's `:db/valueType`/`:db/cardinality` or fails validation against existing stored datoms.
- `:minified-form` & `:dangling-declare`: Prevents collapsed single-line multi-form blobs and orphaned `(declare ...)` statements.
- `:failing-test` & `:proc-fault`: Global view of failed `:seon.test.run/*` digests and unresolved `:seon.health.fault/*` runtime incidents.

## 2. Efficiency Advisories & Scope-Routed Alerts (`:warning`, never blocking)

Advisories route by `:seon.agent/scope` subtree so specialists see their own namespace issues while `:agent/root` sees the roll-up:

- **Static code advisories (in specialist `ns-tile`, zero KV-cache churn):**
  - `:oversized-namespace`: Non-test `src/` namespaces `> 8,000` estimated tokens (all current namespaces sit `< 8k` tok).
  - `:large-function`: Functions exceeding the 120-line / 7.2k-char budget (candidates for extracting clean pure helpers).
  - `:nested-db-scan`: `d/datoms :aevt` or `d/q` inside loop bodies (`O(N * M)` scans).
  - `:unused-require`: Unused `:require` entries detected via AST analysis (cleanable via `(user/prune-requires! 'ns)`).
  - `:state-outside-flow`: Top-level `atom`/`volatile!`/`promise`/`agent` in `src/` not tagged `^:seon.flow/edge`.
- **Live telemetry & outlier advisories (in ephemeral tail, zero KV-cache churn):**
  - `:slow-test` (`> 1000 ms`), `:cost-regression` (`> 2.5x` ticks or bytes jump), `:low-cache-hit` (`< 60%` KV cache), `:heavy-eval` (`> 128 MB` or `> 50M` ticks), `:slow-render` (`> 40 ms`), and `:slow-wire-build` (`> 120 ms` or `> 80 MB`). Transient faults auto-retract via `[:db/retractEntity ...]` once a subsequent run clears under budget (preserving history in `d/history`).

## 3. Reach-Selected Fail-Fast Testing (`seon.test-runner`, `seon.fork`, `seon.code.reach`)

Tests execute in isolated COW forked children (`seon.fork/run-test-worker!`) selected strictly by transitive var reach from changed forms and schemas:

- **Two-tier fail-fast:** Fast tests (`<= 150 ms`, `:seon.config/fast-test-ms`) execute first; any failure short-circuits slower shards immediately.
- **Content-addressed green cache:** Verdicts key on transitive Merkle reach digest (`:seon.test/digest`), reusing unchanged green verdicts in `0 ms`.
- **`seed = 42` Malli generative checks:** Every changed function with a `:malli/schema` runs 100 deterministic `seed = 42` generative samples automatically.
- **Anti-test-deletion gate & cost budgets:** Blocks merges that delete `:main` tests (`:deleted-main-tests`), enforces `:cost/ticks` and `:cost/bytes` ceilings, and surfaces eval recovery hints via `seon.code.parse/enrich-eval-error`.

## 4. The `:seon.fix/*` Class-Fix Ledger

Whenever benchmarking or dogfooding uncovers a recurring agent or runtime failure class, record the verified fix as a `:seon.fix/*` entity in Datahike `:main` (attributes: `:seon.fix/id`, `:seon.fix/error-class`, `:seon.fix/symptom`, `:seon.fix/root-cause`, `:seon.fix/change-summary`, `:seon.fix/regression-test`, `:seon.fix/merge-tx`, `:seon.fix/before-after`, `:seon.fix/created-ms`). Query all past class fixes at any time via `(d/datoms (user/main-db) :aevt :seon.fix/id)` so new sessions never rediscover solved failure classes.
