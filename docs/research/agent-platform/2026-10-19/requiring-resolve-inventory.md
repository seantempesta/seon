# requiring-resolve inventory (rr-inventory, 2026-10-19)

Evidence only; root decides. Method: `rg` over `src/`, `script/`, `test/` for `requiring-resolve`, `resolve`, `ns-resolve`, `find-var`, `intern`, `the-ns`, `var-get`, `ns-publics`, `ns-interns`; comments and docstrings excluded by hand (10 prose sites in `src`/`script`). Each production site is classified from its enclosing form and the require graph. The graph is parsed from each file's `(ns ...)` form with comments and docstrings removed, and a cycle is a path from the target namespace back to the calling namespace through static `:require` edges. Scratch output: `tmp/rr-inventory/` (`analyze.py`, `reqs.py`, `classify.py`, `gen.py`, `final.json`).

## Counts

- Raw matches in `src`, `script`, `test`: 578 lines. Of these, 527 are code sites. 188 are in `src` and `script`, of which 10 are prose (docstrings and comments), leaving **178 production sites**. 339 are in `test`.
- Production sites by class:

| Class | Count | Meaning |
|---|---|---|
| (a) load cycle | 63 | target namespace reaches the calling namespace through static requires; 34 distinct cycles |
| (b) optional or heavy | 9 | guarded by `find-ns`, or a library absent under babashka |
| (c) named by data | 35 | symbol comes from data, config, a schema, a route, or a call argument |
| (e) lazy by choice | 61 | no static cycle exists in the require graph; a static require would not create one |
| enumerate | 10 | `ns-interns` / `ns-publics` snapshots, not a name lookup |

- Class (d), a test reaching a private var: only 1 site, `test/seon/turn/batch_test.clj:582` (`seon.sci.eval/copy-host-var`). No production site reaches a private var.
- Tests (339 sites, 103 files): `test-reach` 266 (test resolves a `seon.*` var by quoted name; 63 are in `test/seon/turn/batch_test.clj`, mostly `seon.test-support/with-running-agent` and `mcp-reply`), `test-enum` 31, `test-fixture` 28 (`intern` builds fixture Vars), `test-other` 14. No test site is part of a cycle, since tests are leaves.

Reach-analysis note: the opening calls the runtime treats as open are listed in `seon.fn` (the set naming `requiring-resolve`, `resolve`, `find-var`, at `src/seon/fn.clj:418`) and `seon.sci.eval` (`run-time-resolution`, `src/seon/sci/eval.clj:2744`). I did not verify the "about 3,400 of 7,686 functions" figure; that needs the reach index.

## Top 10 cycles by production sites

Cycle column: the static require path, which is the reverse of the lazy call. Callers are the enclosing functions that resolve lazily; targets are the functions they reach.

| # | Sites | Static path (lazy call goes the other way) | Calling functions (lazy side) | Target functions (static side) |
|---|---|---|---|---|
| 1 | 7 | `seon.test` → `seon.cluster.source` | `seon.cluster.source/contribution`, `members-of`, `merge-members`, `record-results-at-head!`, `uncovered-functions` | `seon.test/admit-run`, `host-exclusions`, `observable-changes`, `reaching-selection`, `selection-admission`, `selection-revisions` |
| 2 | 5 | `seon.error` → `seon.db` | `seon.db/error-explain-problem`, `error-problem-sentence`, `error-render-ai`, `error-render-html`, `error-scalar-text` | `seon.error/explain-problem`, `problem-sentence`, `render-ai`, `render-html`, `scalar-text` |
| 3 | 5 | `seon.schema.datahike` → `seon.schema` | `seon.schema/schema-datahike-*` (5 seams) | `seon.schema.datahike/storable-attribute-in?`, `storable-properties-in`, `assert-storable-schema!`, `database-attributes-in`, `malli->datahike-attr-in` |
| 4 | 4 | `seon.cluster.agent` → `seon.context` → `seon.problems` → `seon.test` | `seon.test/acquire-context!`, `attempt-writers`, `release-context!`, `routing` | `seon.cluster.agent/acquire-context!`, `creation-tx`, `release-context!`, `routing` |
| 5 | 4 | `seon.context` → `seon.turn` | `seon.turn/context-end-summary-tx`, `context-model-context`, `context-step-members`, `context-step-verdict` | `seon.context/end-summary-tx`, `model-context`, `step-members`, `step-verdict` |
| 6 | 4 | `seon.schema` → `seon.error.refusal` | `seon.error.refusal/schema-seam` | `seon.schema/declaration-projection`, `explain-candidate-value`, `handed-projection`, `identity-only-projector` |
| 7 | 3 | `seon.call-preparation` → `seon.db` | `seon.db/call-preparation-plan-for`, `call-preparation-report-snapshot`, `call-preparation-snapshot` | `seon.call-preparation/plan-for`, `report-snapshot`, `snapshot` |
| 8 | 3 | `seon.render` → `seon.render.value` | `seon.render.value/render-agent-render-profile`, `declared-projection`, `viewer` | `seon.render/agent-render-profile`, `declared-projection`, `viewer` |
| 9 | 2 | `seon.cluster` → `seon.schedule` → `seon.maintenance` | store-lock calls `acquire-root-store!` and `release-root-store!` at `src/seon/maintenance.clj:293` and `:297` | `seon.cluster/acquire-root-store!`, `release-root-store!` |
| 10 | 2 | `seon.cluster.boot` → `seon.health` | `seon.health` (`boot-comparisons`, readiness) at `src/seon/health.clj:535`, `:588` | `seon.cluster.boot/boot-layers`, `readiness` |

The remaining 24 cycle sites are each a separate single-site cycle (for example `seon.cluster.registry` ↔ `seon.cluster.source`, `seon.sci.admit` ↔ `seon.sci.kernel`, `seon.ref` ↔ `seon.sci.lookup`, `seon.turn` ↔ `seon.cluster.agent`, `seon.error` ↔ `seon.task`).

### Wrong-side evidence (for root to judge)

- **Cycle 1 (`seon.test` ↔ `seon.cluster.source`).** The merge and done gate in `cluster.source` lazily calls test-selection helpers (`selection-revisions`, `reaching-selection`, `admit-run`) that live in `seon.test`, which requires `cluster.source` statically. Evidence suggests the selection owner should not depend on the gate, or the gate should receive the selection as a value.
- **Cycle 2 (`seon.db` ↔ `seon.error`).** `db` calls five error renderers (`explain-problem`, `render-ai`, `render-html`, ...). The `db.clj` comment (lines 51–54) already names this as a load-cycle boundary. Rendering inside the database namespace is the wrong side. The database could return the error value and leave rendering to the edge.
- **Cycle 3 (`seon.schema` ↔ `seon.schema.datahike`).** Five `-in` helpers are reached lazily from `schema`, and `schema.datahike` requires `schema`. The Datahike translation looks like it wants to be a leaf that receives the registry as an argument.
- **Cycle 4 (`seon.test` → agent lifecycle).** Test harness wiring (`acquire-context!`, `release-context!`, `routing`) is called lazily from the agent side, and the test namespace is a static dependency of `problems`/`context`. The test harness should be the caller, not the callee.
- **Cycle 5 (`seon.context` ↔ `seon.turn`).** `turn` calls four step functions in `context`, which requires `turn`. Step verdict and members look like turn data.
- **Cycle 6 (`seon.error.refusal` ↔ `seon.schema`).** `error.refusal` is portable (`^{:seon/babashka true}`). Its `schema-seam` reaches back to `schema` for projections. The producer that builds the diagnostic could attach the projection, so `refusal` need not depend on `schema`.
- **Cycle 7 (`seon.call-preparation` ↔ `seon.db`).** `db` resolves three snapshot functions from `call-preparation`, which requires `db`.
- **Cycle 8 (`seon.render` ↔ `seon.render.value`).** `render.value` resolves three `render` functions lazily, and `render` requires `render.value`.
- **Cycle 9 (`seon.maintenance` ↔ `seon.cluster`).** Maintenance takes and releases the root store lock through `cluster`. A lock acquisition belongs to `cluster`, which should pass the store in.
- **Cycle 10 (`seon.health` ↔ `seon.cluster.boot`).** `health` reads `boot-layers` and `readiness` lazily. The boot constants could live in a leaf namespace that both can require.

## Layer inversions visible in the (e) and (b) rows (not cycles)

- `seon.task` calls `my.agent/retire-branch!` by name (`src/seon/task.clj:1545`). Its comment says a `my` namespace is never required there, so this is a `seon` to `my` call, not a cycle.
- `seon.ref` calls `my.program/source` (`src/seon/ref.clj:592`).
- `my.program/native!` calls `seon.sci.eval/evaluate-native!` (`src/my/program.clj:542`).
- `seon.program`, documented as "pure declaration identities", calls `seon.db` (`src/seon/program.cljc:213`, `:217`, `:712`, `:724`, `:774`, `:882`, `:962`, `:976`). No static cycle exists, so a static require would load the database into a pure namespace.

## Fences found while classifying (for root)

- `src/seon/cluster/agent.clj:699`: `(try (requiring-resolve stage) (catch Exception _ nil))`. The try/catch around a name lookup is a fence under CLAUDE.md, and a bad stage name should be refused as data.
- `src/seon/call_preparation.clj:939`: `(try (requiring-resolve symbol-name) ...)`, same shape.
- `src/seon/error/refusal.clj:654`: uses the retired word `*world*` in `(ns-resolve 'seon.batch '*world*)`.

## Caveats

- Classes (b) and (e) are inferred from comments, `find-ns` guards and the require graph. They are not confirmed by runtime evidence. Classes (c) and enumerate are by symbol source, and several (c) rows are reflection (`find-var` on a symbol from an analysed definition), which is the case the owner's "open to reach analysis" concern is about.
- `script/seon/operator.clj` (class b) generates forms that resolve JVM namespaces at call time; I inferred that the operator is not loaded in the JVM. I did not verify it.
- The require parser is regex-based over `(ns ...)` forms, excluding comments and docstrings. Two earlier passes gave wrong cycles (through a comment in `db.clj` and a `^{:seon/babashka true}` metadata map in `error/refusal.clj`); both are fixed here. A cycle claim should be re-checked with `clj-kondo` or a JVM `require` before it is acted on.
- Git blame was not run (time).

## Production table

Columns: site (file:line), enclosing function, expression (trimmed to 120 characters), target (quoted symbol, or `computed`), class, and the reason from the surrounding code.

| Site | Enclosing function | Expression | Target | Class | Reason |
|---|---|---|---|---|---|
| src/seon/cluster/agent.clj:1083 | seon.cluster.agent/adopt-cluster-declarations! | (let [changes ((requiring-resolve 'seon.cluster/branch-declaration-changes) (db/db connection) projection)] | seon.cluster/branch-declaration-changes | a | cycle: seon.cluster -> seon.cluster.agent |
| src/seon/cluster/registry.clj:814 | seon.cluster.registry/retained-before | base ((requiring-resolve 'seon.cluster.source/branch-merge-base) database) | seon.cluster.source/branch-merge-base | a | cycle: seon.cluster.source -> seon.cluster.registry |
| src/seon/cluster/source.clj:481 | seon.cluster.source/record-results-at-head! | (let [admission ((requiring-resolve 'seon.test/selection-admission) | seon.test/selection-admission | a | cycle: seon.test -> seon.cluster.source |
| src/seon/cluster/source.clj:485 | seon.cluster.source/record-results-at-head! | [[:db.fn/call (requiring-resolve 'seon.test/admit-run) | seon.test/admit-run | a | cycle: seon.test -> seon.cluster.source |
| src/seon/cluster/source.clj:850 | seon.cluster.source/contribution | (let [selected ((requiring-resolve 'seon.test/reaching-selection) | seon.test/reaching-selection | a | cycle: seon.test -> seon.cluster.source |
| src/seon/cluster/source.clj:936 | seon.cluster.source/members-of | (let [revisions ((requiring-resolve 'seon.test/selection-revisions) database) | seon.test/selection-revisions | a | cycle: seon.test -> seon.cluster.source |
| src/seon/cluster/source.clj:952 | seon.cluster.source/members-of | ((requiring-resolve 'seon.test/host-exclusions) database nil true (vec (sort observed))))] | seon.test/host-exclusions | a | cycle: seon.test -> seon.cluster.source |
| src/seon/cluster/source.clj:990 | seon.cluster.source/merge-members | base (try ((requiring-resolve 'seon.test/observable-changes) base database changed) | seon.test/observable-changes | a | cycle: seon.test -> seon.cluster.source |
| src/seon/cluster/source.clj:1219 | seon.cluster.source/judged-merge! | gate ((requiring-resolve 'seon.cluster/candidate-gate!) source held-store s write! (when-not refused files)) | seon.cluster/candidate-gate! | a | cycle: seon.cluster -> seon.cluster.source |
| src/seon/cluster/source.clj:1224 | seon.cluster.source/judged-merge! | observed (try ((requiring-resolve 'seon.context/judged-input) head judged-db task-sym nil) | seon.context/judged-input | a | cycle: seon.context -> seon.cluster.source |
| src/seon/cluster/source.clj:1410 | seon.cluster.source/uncovered-functions | folded (member-contributions database seeds ((requiring-resolve 'seon.test/selection-revisions) database))] | seon.test/selection-revisions | a | cycle: seon.test -> seon.cluster.source |
| src/seon/db.clj:56 | seon.db/error-explain-problem | (delay (requiring-resolve 'seon.error/explain-problem))) | seon.error/explain-problem | a | cycle: seon.error -> seon.db |
| src/seon/db.clj:58 | seon.db/error-problem-sentence | (delay (requiring-resolve 'seon.error/problem-sentence))) | seon.error/problem-sentence | a | cycle: seon.error -> seon.db |
| src/seon/db.clj:60 | seon.db/error-scalar-text | (delay (requiring-resolve 'seon.error/scalar-text))) | seon.error/scalar-text | a | cycle: seon.error -> seon.db |
| src/seon/db.clj:62 | seon.db/error-render-ai | (delay (requiring-resolve 'seon.error/render-ai))) | seon.error/render-ai | a | cycle: seon.error -> seon.db |
| src/seon/db.clj:64 | seon.db/error-render-html | (delay (requiring-resolve 'seon.error/render-html))) | seon.error/render-html | a | cycle: seon.error -> seon.db |
| src/seon/db.clj:66 | seon.db/call-preparation-snapshot | (delay (requiring-resolve 'seon.call-preparation/snapshot))) | seon.call-preparation/snapshot | a | cycle: seon.call-preparation -> seon.db |
| src/seon/db.clj:68 | seon.db/call-preparation-report-snapshot | (delay (requiring-resolve 'seon.call-preparation/report-snapshot))) | seon.call-preparation/report-snapshot | a | cycle: seon.call-preparation -> seon.db |
| src/seon/db.clj:70 | seon.db/call-preparation-plan-for | (delay (requiring-resolve 'seon.call-preparation/plan-for))) | seon.call-preparation/plan-for | a | cycle: seon.call-preparation -> seon.db |
| src/seon/db.clj:5474 | seon.db/stored-namespace-context | (delay (requiring-resolve 'seon.fn/stored-namespace-context))) | seon.fn/stored-namespace-context | a | cycle: seon.fn -> seon.db |
| src/seon/error.clj:1112 | seon.error/proof | owed (when (symbol? sym) ((requiring-resolve 'seon.task/obligations) database sym)) | seon.task/obligations | a | cycle: seon.task -> seon.fn -> seon.error |
| src/seon/error/refusal.clj:240 | seon.error.refusal/schema-seam | (delay {:handed (requiring-resolve 'seon.schema/handed-projection) | seon.schema/handed-projection | a | cycle: seon.schema -> seon.error.refusal |
| src/seon/error/refusal.clj:241 | seon.error.refusal/schema-seam | :declared (requiring-resolve 'seon.schema/declaration-projection) | seon.schema/declaration-projection | a | cycle: seon.schema -> seon.error.refusal |
| src/seon/error/refusal.clj:242 | seon.error.refusal/schema-seam | :packaged (requiring-resolve 'seon.schema.edn/packaged-forms) | seon.schema.edn/packaged-forms | a | cycle: seon.schema.edn -> seon.error.refusal |
| src/seon/error/refusal.clj:243 | seon.error.refusal/schema-seam | :projector (requiring-resolve 'seon.schema/identity-only-projector) | seon.schema/identity-only-projector | a | cycle: seon.schema -> seon.error.refusal |
| src/seon/error/refusal.clj:244 | seon.error.refusal/schema-seam | :explain (requiring-resolve 'seon.schema/explain-candidate-value) | seon.schema/explain-candidate-value | a | cycle: seon.schema -> seon.error.refusal |
| src/seon/health.clj:535 | seon.health/regressions | (boot-comparisons records @(requiring-resolve 'seon.cluster.boot/boot-layers))))) | seon.cluster.boot/boot-layers | a | cycle: seon.cluster.boot -> seon.health |
| src/seon/health.clj:588 | seon.health/report | #((requiring-resolve 'seon.cluster.boot/readiness) instance)) | seon.cluster.boot/readiness | a | cycle: seon.cluster.boot -> seon.health |
| src/seon/maintenance.clj:293 | seon.maintenance/with-root-store! | held ((requiring-resolve 'seon.cluster/acquire-root-store!) path)] | seon.cluster/acquire-root-store! | a | cycle: seon.cluster -> seon.schedule -> seon.maintenance |
| src/seon/maintenance.clj:297 | seon.maintenance/with-root-store! | ((requiring-resolve 'seon.cluster/release-root-store!) path))))) | seon.cluster/release-root-store! | a | cycle: seon.cluster -> seon.schedule -> seon.maintenance |
| src/seon/profile.clj:854 | seon.profile/pain | ([] (pain (let [dials @(requiring-resolve 'seon.config/defaults)] | seon.config/defaults | a | cycle: seon.config -> seon.db -> seon.profile |
| src/seon/ref.clj:592 | seon.ref/at-level | source #((requiring-resolve 'my.program/source) database reference)] | my.program/source | a | cycle: my.program -> seon.ref |
| src/seon/ref.clj:601 | seon.ref/at-level | (if (= :doc level) ((requiring-resolve 'seon.sci.lookup/function-documentation) database reference) (source)) | seon.sci.lookup/function-documentation | a | cycle: seon.sci.lookup -> seon.ref |
| src/seon/render/value.clj:69 | seon.render.value/render-agent-render-profile | (delay (requiring-resolve 'seon.render/agent-render-profile))) | seon.render/agent-render-profile | a | cycle: seon.render -> seon.render.value |
| src/seon/render/value.clj:73 | seon.render.value/render-declared-projection | (delay (requiring-resolve 'seon.render/declared-projection))) | seon.render/declared-projection | a | cycle: seon.render -> seon.render.value |
| src/seon/render/value.clj:75 | seon.render.value/render-viewer | (delay (requiring-resolve 'seon.render/viewer))) | seon.render/viewer | a | cycle: seon.render -> seon.render.value |
| src/seon/render/value.clj:77 | seon.render.value/walk-with-related | (delay (requiring-resolve 'seon.render.walk/with-related))) | seon.render.walk/with-related | a | cycle: seon.render.walk -> seon.render.value |
| src/seon/render/web.clj:103 | seon.render.web/cluster-ensure-entity! | (delay (requiring-resolve 'seon.cluster/ensure-entity!))) | seon.cluster/ensure-entity! | a | cycle: seon.cluster -> seon.render.web |
| src/seon/repl.clj:25 | seon.repl/cluster-agent-armed | (delay (requiring-resolve 'seon.cluster.agent/started))) | seon.cluster.agent/started | a | cycle: seon.cluster.agent -> seon.context -> seon.repl |
| src/seon/repl.clj:132 | seon.repl/render-agent-render-profile | (delay (requiring-resolve 'seon.render/agent-render-profile))) | seon.render/agent-render-profile | a | cycle: seon.render -> seon.error -> seon.repl |
| src/seon/schema.clj:43 | seon.schema/schema-edn-packaged-forms | (delay (requiring-resolve 'seon.schema.edn/packaged-forms))) | seon.schema.edn/packaged-forms | a | cycle: seon.schema.edn -> seon.schema |
| src/seon/schema.clj:45 | seon.schema/schema-datahike-storable-attribute-in? | (delay (requiring-resolve 'seon.schema.datahike/storable-attribute-in?))) | seon.schema.datahike/storable-attribute-in? | a | cycle: seon.schema.datahike -> seon.schema |
| src/seon/schema.clj:47 | seon.schema/schema-datahike-storable-properties-in | (delay (requiring-resolve 'seon.schema.datahike/storable-properties-in))) | seon.schema.datahike/storable-properties-in | a | cycle: seon.schema.datahike -> seon.schema |
| src/seon/schema.clj:49 | seon.schema/schema-datahike-assert-storable-schema! | (delay (requiring-resolve 'seon.schema.datahike/assert-storable-schema!))) | seon.schema.datahike/assert-storable-schema! | a | cycle: seon.schema.datahike -> seon.schema |
| src/seon/schema.clj:51 | seon.schema/schema-datahike-database-attributes-in | (delay (requiring-resolve 'seon.schema.datahike/database-attributes-in))) | seon.schema.datahike/database-attributes-in | a | cycle: seon.schema.datahike -> seon.schema |
| src/seon/schema.clj:53 | seon.schema/schema-datahike-malli->datahike-attr-in | (delay (requiring-resolve 'seon.schema.datahike/malli->datahike-attr-in))) | seon.schema.datahike/malli->datahike-attr-in | a | cycle: seon.schema.datahike -> seon.schema |
| src/seon/schema.clj:3453 | seon.schema/database-projection | (delay (requiring-resolve 'seon.db/carried-projection))) | seon.db/carried-projection | a | cycle: seon.db -> seon.schema |
| src/seon/schema.clj:3975 | seon.schema/canonical-schema-entities | ((requiring-resolve 'seon.program/definition-digest) entity))))) | seon.program/definition-digest | a | cycle: seon.program -> seon.schema |
| src/seon/sci/admit.clj:61 | seon.sci.admit/sci-kernel-interrupted? | (delay (requiring-resolve 'seon.sci.kernel/interrupted?))) | seon.sci.kernel/interrupted? | a | cycle: seon.sci.kernel -> seon.sci.admit |
| src/seon/task.clj:1545 | seon.task/retire-branch! | (delay (requiring-resolve 'my.agent/retire-branch!))) | my.agent/retire-branch! | a | cycle: my.agent -> seon.cluster.agent -> seon.task |
| src/seon/test.clj:145 | seon.test/acquire-context! | (delay (requiring-resolve 'seon.cluster.agent/acquire-context!))) | seon.cluster.agent/acquire-context! | a | cycle: seon.cluster.agent -> seon.context -> seon.problems -> seon.test |
| src/seon/test.clj:205 | seon.test/attempt-writers | (delay {:agent (requiring-resolve 'seon.cluster.agent/creation-tx) | seon.cluster.agent/creation-tx | a | cycle: seon.cluster.agent -> seon.context -> seon.problems -> seon.test |
| src/seon/test.clj:206 | seon.test/attempt-writers | :turn (requiring-resolve 'seon.turn/open-tx) | seon.turn/open-tx | a | cycle: seon.turn -> seon.turn.batch -> my.program -> seon.test |
| src/seon/test.clj:1964 | seon.test/release-context! | (delay (requiring-resolve 'seon.cluster.agent/release-context!))) | seon.cluster.agent/release-context! | a | cycle: seon.cluster.agent -> seon.context -> seon.problems -> seon.test |
| src/seon/test.clj:1967 | seon.test/routing | (delay (requiring-resolve 'seon.cluster.agent/routing))) | seon.cluster.agent/routing | a | cycle: seon.cluster.agent -> seon.context -> seon.problems -> seon.test |
| src/seon/test.clj:1970 | seon.test/seed-cluster-facts! | (delay (requiring-resolve 'seon.cluster/seed-cluster-facts!))) | seon.cluster/seed-cluster-facts! | a | cycle: seon.cluster -> seon.cluster.agent -> seon.context -> seon.problems -> seon.test |
| src/seon/test/runner.clj:1458 | seon.test.runner/loaded-identity | (delay @(requiring-resolve 'seon.sci.eval/loaded-identity))) | seon.sci.eval/loaded-identity | a | cycle: seon.sci.eval -> seon.test.runner |
| src/seon/turn.clj:62 | seon.turn/cluster-agent-acquire-context! | (delay (requiring-resolve 'seon.cluster.agent/acquire-context!))) | seon.cluster.agent/acquire-context! | a | cycle: seon.cluster.agent -> seon.turn |
| src/seon/turn.clj:64 | seon.turn/context-model-context | (delay (requiring-resolve 'seon.context/model-context))) | seon.context/model-context | a | cycle: seon.context -> seon.turn |
| src/seon/turn.clj:66 | seon.turn/context-step-verdict | (delay (requiring-resolve 'seon.context/step-verdict))) | seon.context/step-verdict | a | cycle: seon.context -> seon.turn |
| src/seon/turn.clj:68 | seon.turn/context-step-members | (delay (requiring-resolve 'seon.context/step-members))) | seon.context/step-members | a | cycle: seon.context -> seon.turn |
| src/seon/turn.clj:70 | seon.turn/context-end-summary-tx | (delay (requiring-resolve 'seon.context/end-summary-tx))) | seon.context/end-summary-tx | a | cycle: seon.context -> seon.turn |
| src/seon/turn/batch.clj:373 | seon.turn.batch/attribute-change-tx | ((requiring-resolve 'seon.cluster/attribute-change-tx) | seon.cluster/attribute-change-tx | a | cycle: seon.cluster -> seon.turn -> seon.turn.batch |
| script/seon/operator.clj:383 | seon.operator/head-capture-form | store# ((resolve '~'seon.cluster/acquire-root-store!) ~store-dir)] | seon.cluster/acquire-root-store! | b | operator script resolves JVM namespaces at call time (inferred from script) |
| src/seon/db.clj:296 | seon.db/connection-projection-state | (ns-resolve 'running-instances) | running-instances | b | optional namespace seon.operator.runtime, find-ns guarded |
| src/seon/db.clj:2004 | seon.db/projection-builder | (let [loaded (or (some-> (find-ns 'seon.sci.eval) (ns-resolve 'loaded-identity) deref deref) {}) | seon.sci.eval | b | optional namespace seon.sci.eval, find-ns guarded |
| src/seon/db.clj:2005 | seon.db/projection-builder | instances (some-> (find-ns 'seon.operator.runtime) (ns-resolve 'running-instances) | seon.operator.runtime | b | optional namespace seon.operator.runtime, find-ns guarded |
| src/seon/error/refusal.clj:51 | seon.error.refusal/cache-seam | (delay (when @basis (requiring-resolve 'seon.cache/lookup)))) | seon.cache/lookup | b | basis-guarded: Caffeine cache absent in babashka (comment) |
| src/seon/error/refusal.clj:131 | seon.error.refusal/prepared-call-prefix | (when-let [preparing (some-> (find-ns 'seon.call-preparation) (ns-resolve 'preparing))] | seon.call-preparation | b | optional namespace seon.call-preparation, find-ns guarded |
| src/seon/error/refusal.clj:245 | seon.error.refusal/schema-seam | :humanize (requiring-resolve 'malli.error/humanize)})) | malli.error/humanize | b | malli.error loaded lazily in a portable (babashka) namespace |
| src/seon/error/refusal.clj:569 | seon.error.refusal/interpreted-frames | (when-let [stacktrace (and throwable (find-ns 'sci.core) (resolve 'sci.core/stacktrace))] | sci.core/stacktrace | b | optional library sci.core, checked with find-ns |
| src/seon/error/refusal.clj:654 | seon.error.refusal/current-world | (some-> (find-ns 'seon.batch) (ns-resolve '*world*) deref)) | seon.batch | b | optional namespace seon.batch, find-ns guarded; uses retired word *world* |
| script/seon/dev/mcp.clj:72 | seon.dev.mcp/tools-form | (defn- tools-form [] (str "((requiring-resolve '" entrance "/tools))")) | computed | c | text of an entrance form built as a string for an agent to eval |
| script/seon/dev/mcp.clj:76 | seon.dev.mcp/call-form | (str "((requiring-resolve '" entrance "/call) " | computed | c | text of an entrance form built as a string for an agent to eval |
| src/seon/ai.clj:498 | seon.ai/coercion-function | (requiring-resolve coercion)) | computed | c | coercion function named in AI config data |
| src/seon/call_preparation.clj:939 | seon.call-preparation/supply-from-row | resolved (try (requiring-resolve symbol-name) | computed | c | symbol-name from supplied row |
| src/seon/cluster/agent.clj:699 | seon.cluster.agent/segmenter | chosen (if stage (try (requiring-resolve stage) (catch Exception _ nil)) default)] | computed | c | plugin stage named by request data; try/catch on resolve failure (fence) |
| src/seon/cluster/source.clj:401 | seon.cluster.source/resolve-population | (requiring-resolve populate) | computed | c | population function named by a source file's data |
| src/seon/embed.clj:487 | seon.embed/texts-of | (let [build (requiring-resolve text-fn) | computed | c | text function named by configuration/data |
| src/seon/error/refusal.clj:508 | seon.error.refusal/var-digest | (let [target (ns-resolve namespace-object (symbol (name function)))] | computed | c | namespace object named by a frame or caller |
| src/seon/instrument.clj:397 | seon.instrument/definition-location | (let [{:keys [file line]} (some-> (find-var function-symbol) meta)] | computed | c | function symbol from contract or call |
| src/seon/instrument.clj:1095 | seon.instrument/collect-contracts! | (find-var function-symbol))) | computed | c | function symbol from contract or call |
| src/seon/instrument.clj:1163 | seon.instrument/apply! | (find-var function-symbol)))) | computed | c | function symbol from contract or call |
| src/seon/instrument.clj:1359 | seon.instrument/replaced-roots | (find-var function-symbol))] | computed | c | function symbol from contract or call |
| src/seon/maintenance.clj:57 | seon.maintenance/result-entity | (if-let [producer (requiring-resolve projection)] | computed | c | producer symbol from projection data |
| src/seon/program.cljc:844 | seon.program/namespace-stub? | (map requiring-resolve '[seon.db/q seon.db/history seon.db/entities-changed-after | computed | c | computed symbol, source not classified |
| src/seon/program.cljc:923 | seon.program/namespace-stub? | (map requiring-resolve '[seon.db/basis-t seon.db/changed-attributes | computed | c | computed symbol, source not classified |
| src/seon/program.cljc:938 | seon.program/namespace-stub? | (let [[diff q carried] (map requiring-resolve '[seon.db/datoms-diff seon.db/q seon.db/carried-projection]) | computed | c | computed symbol, source not classified |
| src/seon/program.cljc:986 | seon.program/namespace-stub? | (let [[carried content-revisions read-at] (map requiring-resolve '[seon.db/carried-projection seon.db/content-revisions  | computed | c | computed symbol, source not classified |
| src/seon/program.cljc:1031 | seon.program/namespace-stub? | (let [[revisions read-at] (map requiring-resolve '[seon.db/content-revisions seon.db/read-at]) | computed | c | computed symbol, source not classified |
| src/seon/ref.clj:386 | seon.ref/library-definition | v (when loaded? (find-var reference)) | computed | c | find-var of a user-written reference symbol |
| src/seon/ref.clj:622 | seon.ref/named? | :else (and (find-ns (symbol (namespace reference))) (find-var reference)))))) | computed | c | find-var of a user-written reference symbol |
| src/seon/schedule.clj:606 | seon.schedule/invoke-handler | (if-let [handler (some-> (find-ns (symbol (namespace function))) (ns-resolve (symbol (name function))))] | computed | c | scheduled handler named by a schedule entry |
| src/seon/schema.clj:234 | seon.schema/runtime-predicate | (let [resolved (requiring-resolve predicate) | computed | c | predicate symbol named in a schema |
| src/seon/schema.clj:273 | seon.schema/loaded-predicate-var | (ns-resolve (symbol (name predicate)))))) | computed | c | predicate symbol named in a schema |
| src/seon/schema.clj:1204 | seon.schema/register-core-predicate! | (when-not (and resolved (identical? f @(requiring-resolve predicate))) | computed | c | predicate symbol named in a schema |
| src/seon/schema.clj:4151 | seon.schema/identity-only-descriptors-in | projection-var (requiring-resolve projection-symbol)] | computed | c | projection symbol named in data |
| src/seon/schema.clj:4152 | seon.schema/identity-only-descriptors-in | (when-not (ifn? (var-get projection-var)) | computed | c | computed symbol, source not classified |
| src/seon/schema.clj:4304 | seon.schema/predicate-callable | (some-> predicate requiring-resolve deref)))) | computed | c | predicate symbol named in a schema |
| src/seon/sci/eval.clj:397 | seon.sci.eval/build-base-ctx | (ns-resolve host (symbol (name qualified)))) | computed | c | host name list at install |
| src/seon/sci/eval.clj:1140 | seon.sci.eval/install-jvm-root! | (when-let [host-var (ns-resolve host-namespace (symbol (name function-symbol)))] | computed | c | host name from install table |
| src/seon/sci/eval.clj:2726 | seon.sci.eval/runtime-rows! | (when-let [host-var (find-var sym)] | computed | c | find-var of a symbol from analysed reads |
| src/seon/sci/kernel.clj:341 | seon.sci.kernel/program-definition? | (ns-resolve (symbol (name function-symbol))) | computed | c | symbol from an analysed definition |
| src/seon/test.clj:1817 | seon.test/resolve-loaded | (requiring-resolve test-symbol))) | computed | c | computed symbol, source not classified |
| src/seon/test/runner.clj:548 | seon.test.runner/resolve-loaded | (find-var qualified-symbol))) | computed | c | computed symbol, source not classified |
| src/seon/web/jvm.clj:285 | seon.web.jvm/handler-var | (deref (ns-resolve namespace symbol))) | computed | c | handler var named by route data |
| src/seon/web/jvm.clj:387 | seon.web.jvm/projection | (some-> symbol requiring-resolve deref)) | computed | c | projection symbol named by data |
| script/seon/agent_cost.clj:145 | seon.agent-cost/group-run | graph-changes (when-let [revisions (ns-resolve 'seon.test 'selection-revisions)] | seon.test | e | ns-resolve of a test-namespace symbol; no cycle checked |
| script/seon/config_load_timing.clj:22 | seon.config-load-timing/-main | (contains? ((requiring-resolve 'seon.schema/registered-schemas)) | seon.schema/registered-schemas | e | benchmark script resolves at call time |
| script/seon/config_load_timing.clj:24 | seon.config-load-timing/-main | (println ((requiring-resolve 'seon.render.value/core-text) result)) | seon.render.value/core-text | e | benchmark script resolves at call time |
| script/seon/operator.clj:387 | seon.operator/head-capture-form | [branch# ((resolve '~'seon.cluster.registry/branch-commit-id) | seon.cluster.registry/branch-commit-id | e | no static cycle in the require graph; lazy by choice |
| script/seon/operator.clj:389 | seon.operator/head-capture-form | ((resolve '~'seon.cluster.registry/branches) store#)) | seon.cluster.registry/branches | e | no static cycle in the require graph; lazy by choice |
| script/seon/operator.clj:392 | seon.operator/head-capture-form | ((resolve '~'seon.cluster/release-root-store!) ~store-dir) | seon.cluster/release-root-store! | e | no static cycle in the require graph; lazy by choice |
| script/seon/operator.clj:473 | seon.operator/launch-form | ((resolve '~'seon.cluster.boot/request!) | seon.cluster.boot/request! | e | no static cycle in the require graph; lazy by choice |
| script/seon/operator.clj:496 | seon.operator/launch-form | ((resolve '~'seon.cluster/release-root-store!) ~(:seon.operator/capture-heads request))) | seon.cluster/release-root-store! | e | no static cycle in the require graph; lazy by choice |
| src/my/program.clj:542 | my.program/native! | ((requiring-resolve 'seon.sci.eval/evaluate-native!) ctx form)) | seon.sci.eval/evaluate-native! | e | my layer to seon: no static cycle found |
| src/seon/call_preparation.clj:77 | seon.call-preparation/error-output-validators | (def ^:private error-output-validators (delay (requiring-resolve 'seon.error/declared-output-validators))) | seon.error/declared-output-validators | e | no static cycle in the require graph; lazy by choice |
| src/seon/call_preparation.clj:78 | seon.call-preparation/error-declared-schema-keys | (def ^:private error-declared-schema-keys (delay (requiring-resolve 'seon.error/declared-schema-keys))) | seon.error/declared-schema-keys | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster.clj:2548 | seon.cluster/adopt-entities! | (require-committed! ((requiring-resolve 'seon.task/adopt-tasks-call) | seon.task/adopt-tasks-call | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster.clj:2758 | seon.cluster/isolated-run | ((requiring-resolve 'seon.test.platform/check-files!) | seon.test.platform/check-files! | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster.clj:2787 | seon.cluster/reaching-run | ((requiring-resolve 'seon.test/run) | seon.test/run | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster.clj:2796 | seon.cluster/reaching-run | :seon.source/tally (cond-> (if run ((requiring-resolve 'seon.test/tally) run) | seon.test/tally | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster/source.clj:479 | seon.cluster.source/record-results-at-head! | ((requiring-resolve 'seon.test.runner/commit-results!) | seon.test.runner/commit-results! | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster/source.clj:540 | seon.cluster.source/index-tasks! | (let [task-files (requiring-resolve 'seon.task/task-files) | seon.task/task-files | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster/source.clj:546 | seon.cluster.source/index-tasks! | basis (require-committed! ((requiring-resolve 'seon.task/task-basis) (or previous (db/db connection)) files) | seon.task/task-basis | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster/source.clj:549 | seon.cluster.source/index-tasks! | ((requiring-resolve 'seon.task/index-tasks!) | seon.task/index-tasks! | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster/source.clj:552 | seon.cluster.source/index-tasks! | findings (require-committed! ((requiring-resolve 'seon.task/task-findings) (db/db connection)) | seon.task/task-findings | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster/source.clj:1311 | seon.cluster.source/red-test-verdicts | red? (requiring-resolve 'seon.test.runner/red-result?)] | seon.test.runner/red-result? | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster/source.clj:1426 | seon.cluster.source/uncovered-refusal | (let [red? (requiring-resolve 'seon.test.runner/red-result?) | seon.test.runner/red-result? | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster/source.clj:1486 | seon.cluster.source/done-refusals | red? (requiring-resolve 'seon.test.runner/red-result?) | seon.test.runner/red-result? | e | no static cycle in the require graph; lazy by choice |
| src/seon/context.clj:455 | seon.context/judged-input | root (when (and run-id chained? (string? cluster)) ((requiring-resolve 'seon.merge/checkout) main cluster)) | seon.merge/checkout | e | no static cycle found; candidate for static require (verify) |
| src/seon/db.clj:5469 | seon.db/program-definition-digest | (delay (requiring-resolve 'seon.program/definition-digest))) | seon.program/definition-digest | e | no static cycle in the require graph; lazy by choice |
| src/seon/db.clj:5471 | seon.db/program-behavior-digest | (delay (requiring-resolve 'seon.program/behavior-digest))) | seon.program/behavior-digest | e | no static cycle in the require graph; lazy by choice |
| src/seon/db.clj:5476 | seon.db/program-shapes-in | (defonce ^:private program-shapes-in (delay (requiring-resolve 'seon.program/shapes-in))) | seon.program/shapes-in | e | no static cycle in the require graph; lazy by choice |
| src/seon/db.clj:5477 | seon.db/program-canonical-entity | (defonce ^:private program-canonical-entity (delay (requiring-resolve 'seon.program/canonical-row))) | seon.program/canonical-row | e | no static cycle in the require graph; lazy by choice |
| src/seon/effect.clj:550 | seon.effect/error-output-validators | (delay (requiring-resolve 'seon.error/declared-output-validators))) | seon.error/declared-output-validators | e | no static cycle in the require graph; lazy by choice |
| src/seon/error.clj:2080 | seon.error/library-calling-form | (let [shortest (some->> (:arglists (meta (resolve sym))) (sort-by count) first)] | computed | e | reflection: arglists of a symbol named in a reference |
| src/seon/error.clj:2097 | seon.error/candidate-calling-form | (when (resolve sym) | computed | e | reflection: symbol loaded? |
| src/seon/error/refusal.clj:164 | seon.error.refusal/frame-function | (find-var (symbol frame-ns simple)))) | computed | e | reflection on stack frame names |
| src/seon/fn/analyzer.clj:942 | seon.fn.analyzer/qualify-schema-symbols | (when (ns-resolve 'clojure.core value) | clojure.core | e | asks whether symbol is a clojure.core var |
| src/seon/program.cljc:62 | seon.program/derived-program-attributes | (let [project (requiring-resolve 'seon.schema.datahike/storable-properties-in)] | seon.schema.datahike/storable-properties-in | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:213 | seon.program/edn-round-trip-symbol? | (let [history ((requiring-resolve 'seon.db/history) database)] | seon.db/history | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:217 | seon.program/edn-round-trip-symbol? | ((requiring-resolve 'seon.db/q) | seon.db/q | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:320 | seon.program/derived-shape | #?(:clj (let [vector-shaped? (requiring-resolve 'seon.schema.datahike/vector-shaped-attr-in?)] | seon.schema.datahike/vector-shaped-attr-in? | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:325 | seon.program/derived-shape | ((requiring-resolve 'seon.schema.datahike/malli->datahike-schema-in) | seon.schema.datahike/malli->datahike-schema-in | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:712 | seon.program/namespace-stub? | (let [projection (try ((requiring-resolve 'seon.db/carried-projection) database) | seon.db/carried-projection | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:724 | seon.program/namespace-stub? | index-page (requiring-resolve 'seon.db/index-page) | seon.db/index-page | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:774 | seon.program/namespace-stub? | (let [q (requiring-resolve 'seon.db/q) | seon.db/q | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:882 | seon.program/namespace-stub? | (let [datoms (requiring-resolve 'seon.db/undecoded-datoms)] | seon.db/undecoded-datoms | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:962 | seon.program/namespace-stub? | (let [diff (requiring-resolve 'seon.db/datoms-diff) | seon.db/datoms-diff | e | no static cycle in the require graph; lazy by choice |
| src/seon/program.cljc:976 | seon.program/namespace-stub? | ::commit ((requiring-resolve 'seon.db/commit-id) database) ::revisions revisions))))) | seon.db/commit-id | e | no static cycle in the require graph; lazy by choice |
| src/seon/render/value.clj:71 | seon.render.value/config-defaults | (delay (requiring-resolve 'seon.config/defaults))) | seon.config/defaults | e | no static cycle in the require graph; lazy by choice |
| src/seon/repl.clj:134 | seon.repl/config-defaults | (delay (requiring-resolve 'seon.config/defaults))) | seon.config/defaults | e | no static cycle in the require graph; lazy by choice |
| src/seon/schema.clj:57 | seon.schema/schema-datahike-storage-schema | (ns-resolve 'seon.schema.datahike 'storage-schema))) | seon.schema.datahike | e | no static cycle in the require graph; lazy by choice |
| src/seon/schema.clj:61 | seon.schema/schema-datahike-value-schema | (ns-resolve 'seon.schema.datahike 'value-schema))) | seon.schema.datahike | e | no static cycle in the require graph; lazy by choice |
| src/seon/schema.clj:1797 | seon.schema/register-candidate! | (ns-resolve 'admit))] | admit | e | ns-resolve 'admit: target namespace not identified (check) |
| src/seon/schema.clj:1820 | seon.schema/register-candidate! | (ns-resolve 'registration-check))] | registration-check | e | ns-resolve 'registration-check: target namespace not identified (check) |
| src/seon/task.clj:1548 | seon.task/submit-work! | (delay (requiring-resolve 'seon.flow/submit!))) | seon.flow/submit! | e | no static cycle found; candidate for static require (verify) |
| src/seon/task.clj:1551 | seon.task/record-fault! | (delay (requiring-resolve 'seon.fault/fault!))) | seon.fault/fault! | e | no static cycle found; candidate for static require (verify) |
| src/seon/task.clj:1554 | seon.task/process-id | (delay (comp (requiring-resolve 'seon.cluster.process/id) (requiring-resolve 'seon.cluster.process/current-identity)))) | seon.cluster.process/id | e | no static cycle found; candidate for static require (verify) |
| src/seon/test.clj:207 | seon.test/attempt-writers | :context (requiring-resolve 'seon.effect/*request-context*)})) | seon.effect/*request-context* | e | no static cycle in the require graph; lazy by choice |
| src/seon/test/runner.clj:1406 | seon.test.runner/task-identity | :else [((requiring-resolve 'seon.task/task-revision) database (:seon.task/id task)) | seon.task/task-revision | e | no static cycle in the require graph; lazy by choice |
| src/seon/turn.clj:72 | seon.turn/test-run | (delay (requiring-resolve 'seon.test/run))) | seon.test/run | e | no static cycle in the require graph; lazy by choice |
| src/seon/turn.clj:74 | seon.turn/test-latest-results | (delay (requiring-resolve 'seon.test.runner/latest-results))) | seon.test.runner/latest-results | e | no static cycle in the require graph; lazy by choice |
| src/seon/turn.clj:76 | seon.turn/test-request-changes | (delay (requiring-resolve 'seon.test/request-changes))) | seon.test/request-changes | e | no static cycle in the require graph; lazy by choice |
| src/seon/turn.clj:78 | seon.turn/test-reaching | (delay (requiring-resolve 'seon.test/reaching))) | seon.test/reaching | e | no static cycle in the require graph; lazy by choice |
| src/seon/turn.clj:80 | seon.turn/test-branch-tests | (delay (requiring-resolve 'seon.test/branch-tests))) | seon.test/branch-tests | e | no static cycle in the require graph; lazy by choice |
| src/seon/turn.clj:5264 | seon.turn/test-stale | (delay (requiring-resolve 'seon.test/stale))) | seon.test/stale | e | no static cycle in the require graph; lazy by choice |
| src/seon/cluster.clj:2474 | seon.cluster/development-arming-identities | (keys (ns-interns loaded)))))) | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/problems.clj:397 | seon.problems/stale-vars | (sort-by key (ns-interns loaded-namespace)))))) | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/program.cljc:249 | seon.program/edn-round-trip-symbol? | (keys (ns-publics namespace-name))))))) | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/sci/eval.clj:323 | seon.sci.eval/load-host-namespace | (ns-interns host))) | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/sci/eval.clj:1931 | seon.sci.eval/install-first-party-namespaces! | (ns-interns host-namespace) | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/sci/eval.clj:1969 | seon.sci.eval/install-host-core! | (ns-publics 'clojure.core))) | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/sci/eval.clj:2723 | seon.sci.eval/runtime-rows! | (ns-interns host)))) | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/sci/eval.clj:3621 | seon.sci.eval/interpret-overlay! | (nil? (get (ns-interns namespace-name) (symbol (name sym))))) | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/test.clj:2900 | seon.test/arm-first-loads! | interns (for [n (remove before (all-ns)) s (keys (ns-interns n))] (symbol (str n) (str s)))] | computed | enum | enumerates a namespace's Vars, not a name lookup |
| src/seon/test/runner.clj:514 | seon.test.runner/run-selected-tests | bindings (if host? (ns-interns namespace-object) | computed | enum | enumerates a namespace's Vars, not a name lookup |

## Test sites (summary)

Per-file counts are in the classification (`tmp/rr-inventory/final.json`). The top test files by `test-reach` are `test/seon/turn/batch_test.clj` (63), `test/seon/cluster/reload_measure.clj` (16), `test/seon/cluster/db_flow_test.clj` (12), and `test/seon/sci/eval_test.clj` (12). Most test-reach targets are `seon.test-support/with-running-agent` (27), `seon.cluster` (11), `seon.turn` (10), and `seon.db` (8). Test resolution is class (e), since tests are not part of any cycle. The one private reach is class (d) at `test/seon/turn/batch_test.clj:582`.
