# Agent Guide: REPL, `@seon.ctx/ctx`, `result/e<n>`, and MCP

Every Seon agent — including `:agent/root`, autonomous branch workers, and external coding-agent sessions via MCP — operates inside an isolated Copy-on-Write Jolt runtime on its own Datahike branch using the exact same primitives.

## 1. Live Runtime `result/e<n>` Refs (`seon.code/eval-forms`)

Every successful evaluation binds its live Clojure return value to a monotonic qualified symbol `result/e<n>` in the `result` namespace, with execution telemetry (`:seon.eval/ms`, `:seon.eval/ticks`, `:seon.eval/bytes`, `:seon.eval/out`) on `(meta #'result/e<n>)`.

- Pass `result/e1` directly into subsequent evals (e.g., `(count result/e1)`, `(:ok? result/e1)`, `(user/pin! result/e1)`).
- Evals are bounded via `jolt.host/run-interruptible` (`:seon.config/eval-ticks` 100M, `:seon.config/eval-bytes` 256 MB) so runaway loops fail cleanly as tool errors without harming sibling calls or the standing server.
- Eval outputs format through `seon.model.wire/format-eval-output` as **100% valid Clojure EDN maps** (`{:seon.eval/turn ... :seon.eval/handle result/e<n> :seon.eval/ok? ... :seon.eval/ms ... :seon.eval/ticks ... :seon.eval/value ...}` — zero fake `; ` comment headers), preferring pure `:seon.ui/hiccup` renderers discovered by `seon.render.fit/discover-renderers` and falling back to the 4-stage breadth-before-depth printer (`seon.render/render-value` -> `seon.render/hiccup->text`) with `(user/expand! ...)` drill-ins and live schema breadcrumbs. Returning an entity map or lookup ref (e.g., `(user/health)`, `[:seon.code.ns/name 'seon.web]`, `[:seon.code.doc/id "docs/render.md"]`) surfaces it automatically as a live entity card on the unified surface.
- Evaluation errors pass through `seon.code.parse/enrich-eval-error`, appending class-level recovery hints (e.g., `deftest` persistence via `user/add-fn!`, Malli predicate symbols vs `:number`, `user/run-tests!` 4 GB budget override, Clojure regex escaping, container return shapes, Datahike `:memory` UUID `:id`, and `(user/db)` vs `@ctx`).
## 2. Reactive `@seon.ctx/ctx`, Unified Context Pipeline & `user/*` Helpers

Each agent process holds a private reactive `@seon.ctx/ctx` (`seon.ctx`, aliased as `user/ctx`) backed by `datahike.reads` invalidation (`O(|Δ|)` refresh) and Malli-validated `swap!` write-back (`seon.ctx.tree/diff-entity-tx`). `[datahike.api :as d]` and `[clojure.string :as str]` are pre-aliased in `user`. Agents self-assemble purely from Datahike graph edges (`:seon.agent/tasks`, `:seon.agent/forked-from`, `:seon.agent/target`, `:seon.agent/merge-target`, `:seon.agent/example-refs`, `:seon.agent/read-branch`, `:seon.agent/router?`) — `:seon.agent/role`, `:seon.agent/prefix-mode`, scalar `:seon.agent/task`, untyped `:seon.agent/memory`, and arbitrary truncation caps are retired.

**How agent context is built (`seon.ctx`, `seon.ctx.project`, `seon.ctx.touches`, `seon.rule`, `seon.render.fit`, `seon.render.wire`):**
- **Generic 2-read world discovery (`seon.ctx.project/agent-world-datoms`):** Zero hand-tuned pull lists — 1 forward `:eavt` read on `agent-eid` (merged across `:main` + branch by latest `:tx`) plus reverse `:avet` reads across every `:db.type/ref` attribute whose `:seon.schema/ref-targets` targets `:seon.agent/entity`.
- **Derived in-memory touches (`seon.ctx.touches/derived-agent-touches`):** Zero persisted `:seon.agent.eval/touched` or `:seon.agent.eval/touched-refs` LMDB writes — uncapped per-entity touch counts, novelty, and branch writes derive from live in-memory `datahike.reads` (`seon.ctx.touches/record-live-reads!`), raw `d/changes` branch mutations (`seon.ctx.touches/compute-branch-writes`), and eval mentions.
- **ONE candidate gatherer & native Datahike `d/q` rules (`seon.ctx/gather-agent-candidates`, `seon.rule/step-rules`):** Editable `:seon.rule/*` entities (persisted 1-to-1 to `resources/seon/rules.edn`) execute native `:seon.rule/query` clauses with shared recursive `%` rules (`seon.rule/default-datalog-rules`: `calls*`, `needs*`, `compatible-fn`, `render-fn`, `schema-doc`, `var-doc`) inside `datahike.reads/with-reads`, skipping untouched rules in `O(|Δ|)`.
- **3-signal rank + Complete-Thought Upgrade Ladder (`seon.render.fit/fit-projections`):** Ranks candidates by urgency/anchor + native `?tx`/`:seon.agent.eval/turn` recency + derived touches/convergence (`:seon.config/rank-weights`), pulls each entity once, discovers pure `:seon.ui/hiccup` renderers via `seon.render.fit/discover-renderers`, and fits `:seon.view/token-budget` using the `already-shown` zero-repetition queue (`seon.ctx.project/compute-shown-delta`, least-volatile -> most-volatile KV-cache ordering).
- **Unified agent-prefix & pure Hiccup `<ephemeral-tail>` (`seon.model.messages/render-prefix-agents`, `seon.render.tails`, `seon.render.wire/assemble-unified-tail`):** Forked parent sessions and example sessions assemble mechanically from `:seon.agent/forked-from` / `:seon.agent/example-refs`; volatile branch status + turn budget, scoped/system health, neighborhood activity, unread inbox, Chronicle anchors, compaction hints, and optional live `WKWebView` frames render through `seon.render.tails` -> `seon.render/hiccup->text`.

### Available `user/*` Helpers (`seon.fork.helpers`, `seon.fork.calls`, `seon.fork.container`, `seon.repl`)

Helpers are exported declaratively via `^:seon.repl/export` (`seon.fork.helpers`) plus scoped installers (`seon.ctx/install-ctx-helpers!`, `seon.task.gate/install-user-helpers!`, `seon.signals.scoreboard/install-user-helpers!`, `seon.repl/install-repl-helpers!`).

**Read, Context, Search & Navigate:**
- `(user/context)` / `(user/context 'ns | :ns/<task-id> | {:task ... :scope ... :extra-namespaces [...]})` — Render the exact rule-derived Upgrade-Ladder `<walk-context>` + `<ephemeral-tail>` that internal Seon agents receive on the wire.
- `(user/explore! ref {:token-budget 2400})` / `(user/similar q k opts)` / `(user/task)` — Explore any entity through `seon.render.surface/project-entity-view` (`:target :wire`), run semantic vector search via `seon.vector`, or inspect the current agent's assigned `:seon.task/*`.
- `(user/db)` / `(user/main-db)` / `(user/conn-of)` — Branch DB snapshot, `:main` DB snapshot, and branch connection. Prefer indexed `(d/datoms db :avet/:aevt :attr ...)` over scanning `d/q`.
- `(user/source 'ns/fn ...)` / `(user/source 'ns 1 80)` / `(user/source "docs/file.md#slug")` / `(user/source :seon.schema/key)` — Variadic source/doc/schema lookup with optional line range or `{:lines [start end]}`.
- `(user/grep-src "pat" | ["p1" "p2"] {:ns 'ns})` — Cached single-pass source search returning `:sym`, `:key`, `:line`, `:text`.
- `(user/ns-outline 'ns)` / `(user/ns-requires 'ns)` — Token counts, form list, local deps, callers, and parsed `:require` specs.
- `(user/callers 'ns/fn)` / `(user/callees 'ns/fn)` / `(user/tests-for 'ns)` / `(user/search "term")` — Indexed call-graph, `:input`/`:output` schema edges, reaching tests, and full-index search.
- `(user/expand! 'result/e1)` / `(user/help)` / `(user/help 'edit-fn!)` — Drill into elided eval outputs or inspect any helper.

**Context Introspection, Native Datalog Rules & Empirical Lab (`seon.ctx/install-ctx-helpers!`, `seon.render.fit`, `seon.signals.scoreboard`):**
- `(user/context-trace)` / `(user/context-trace :agent/id {:target :wire :token-budget 16000})` — Inspect every fired/skipped native `d/q` rule, `datahike.reads` skip count, generic 2-read world discovery, merged pull pattern, chosen renderer, and token cost.
- `(user/diff-context)` / `(user/diff-context rules-a rules-b)` — A/B diff projected entities, pull patterns, and token budgets across rule sets or `:main` vs branch.
- `(seon.render.fit/explain-candidates (user/db) candidates opts)` / `(seon.render.fit/explain-entity-fit (user/db) ref opts)` — Inspect 3-signal ranking scores, every discovered renderer variation, measurements, and selection/drop reasons.
- `(user/rules)` / `(user/rules :context)` / `(user/put-rule! {:seon.rule/id :rule.x/y :seon.rule/stratum 50 :seon.rule/query [...] :seon.rule/assert [...]})` / `(user/toggle-rule! :rule.x/y false|true)` — Inspect and live-edit native Datahike `d/q` rules (persists to `resources/seon/rules.edn` on merge).
- `(user/curate! [[:seon.code.var/name 'ns/fn] ...])` / `(user/render-preview! hiccup-or-renderer opts)` — Share curated discoveries across the delegation tree or preview Hiccup/renderers across responsive widths.
- **Empirical Precision/Recall & `d/as-of` Hyperparameter Lab:** Track Precision (% of shown blocks and tokens later touched in evals) and Recall Gap (manual `source`/`grep-src`/`d/pull` lookups for entities already in the candidate pool), and replay historical `(d/as-of db tx)` snapshots across `:seon.config/rank-weights` and `:breadth-first` vs `:depth-first` Upgrade Ladder profiles via `(user/context-scoreboard)` and `(user/compare-hyperparams)`.

**Structural Code, Refactor, Doc & Schema Editing (never spit `.clj` files on disk):**
- `(user/edit-fn! 'ns/fn old new)` / `(user/add-fn! 'ns "(defn ...)")` / `(user/set-meta! 'ns/fn {:malli/schema [...] :cost/ticks 50000})` — CST-safe `rewrite-clj` editors with upfront `:malli/schema` validation and automatic topological form ordering (`seon.code.cst.ns/topo-order-ns-forms`).
- `(user/add-require! 'ns '[lib :as a])` / `(user/prune-requires! 'ns)` / `(user/unused-requires 'ns)` / `(user/revert-ns! 'ns)` — AST-accurate require management and clean namespace revert to merge-base.
- `(user/move-vars! {:from 'a :to 'a.sub :alias 'sub :vars '[f1 f2] :include-deps? true})` / `(user/remove-var! 'ns/v)` / `(user/plan-rewrite ...)` / `(user/rewrite-graph! ...)` / `(user/rename-ns! ...)` / `(user/rename-schema! ...)` / `(user/migrate-data! ...)` / `(user/validate-migration ...)` — Safe graph-wide refactoring and schema migrations.
- `(user/doc! "docs/file.md#slug" "body")` / `(user/doc! "docs/file.md#slug" "## Heading" "body")` — Lossless Markdown section editor (`String` body required; auto-updates `:seon.code.doc/references`).
- `(user/register-schema! :seon.area/<key> malli-form {:doc "..." :examples [...]})` — Validate with `seed = 42`, derive Datahike attributes, and persist to `resources/seon/schemas.edn` on merge (renderers are normal pure functions discovered via `:seon.code.var/input` + `:seon.code.var/output` `:seon.ui/hiccup`).
- `(user/transact! tx-data)` — Transact Malli-validated datoms onto the current branch.

**Testing, Health, Telemetry & Shipping:**
- `(user/run-tests! 'ns-test/var | 'ns ...)` — Variadic reach-aware test runner with 4 GB allocation override. For Datahike tests use `(seon.test-fixture/with-test-store [{:keys [conn]}] ...)` or `{:store {:backend :memory :id (random-uuid)} :schema-flexibility :read :keep-history? false}`.
- `(user/health)` / `(user/diff)` / `(user/inspect-branch :branch/name)` / `(user/gate-status)` / `(user/metrics)` / `(user/autofix-health!)` — Inspect branch health, 3-way diffs, task gates, efficiency metrics, and run safe static auto-fixes.
- `(user/ready!)` / `(user/finish-review! "summary")` / `(user/wait-for-mail!)` — Signal task completion for automated reach-test + auto-merge, finish a forked review with branch-to-branch merge-back (`seon.transactor.merge/handle-fork-merge-back!`), or pause for mail.
- **Forked review proofs & bounded 6-turn auto-merge cutoff (`seon.task.gate/evaluate-review-outcome`, `seon.task.gate/review-cutoff-ops`):** Spawned reviews fork off the worker branch (`:seon.agent/forked-from`, `:seon.agent/merge-target`, `:seon.agent/fork-commit`, `:seon.config/max-tool-turns 6`). `evaluate-review-outcome` verifies observable Datahike mutations since `:seon.agent/fork-commit` (`:compacted-evals`, `:cleared-errors`, `:curated-context`, `:improved-system`, `:wrote-review-summary`) and blocks `:review-hijacked-ready` or `:review-no-op`; hitting the 6-turn cutoff auto-merges when proofs pass or safely pauses the reviewer and unpauses the parent with a `:review-note` via `review-cutoff-ops` so workers never deadlock.
- `(user/record-summary! {:kind :milestone :title "..." :body "..." :refs [...]})` / `(user/start-bench-run! opts)` — Persist a Chronicle milestone or launch an async parallel isolated benchmark batch.

**Delegation, Live Status, Time-Travel & Viewport:**
- `(user/ask! :ns/<subsys> "prompt")` / `(user/await! [c1 c2])` / `(user/call! ...)` / `(user/answer! cid val)` / `(user/fail! cid err)` / `(user/cancel! cid)` / `(user/calls)` — Flow-native recursive inter-agent calls with cycle rejection and Malli `:result-schema` validation.
- `(user/create-task! {...})` / `(user/assign-task! :ns/<task-id>)` / `(user/assign-tasks! ...)` / `(user/update-task! {...})` / `(user/save-example! :agent/id :example/<name>)` — Multi-task (`:seon.agent/tasks`) and example session management.
- `(user/remember! "note")` / `(user/remember! :target/id "note")` / `(user/tune-context! {...})` / `(user/pin! item)` / `(user/unpin! item)` — Durable `:seon.agent/summary` notes (retiring untyped `:seon.agent/memory`), pins, and context tuning.
- `(user/set-status! {:label "..." :detail "..." :pct 40 :stage :researching :svg "..."})` / `(user/clear-status!)` — Upsert/clear live `:seon.ui.status/entity` progress cards (`seon.render.status/render-status-card`).
- `(user/compact! "summary")` / `(user/past-evals {:limit 15 :grep "..."})` / `(user/as-of tx)` / `(user/history 'ns/fn)` — True `[:db/retractEntity]` compaction with full `d/history` time-travel.
- `(user/show-user! {:viewing :agent/id :mode :session|:context|:system|:inspect :as-of-tx tx})` — Move the user's browser viewport or `d/as-of` time-travel cursor live via `:user/default` (also accepts legacy `:canvas|:workspace|:debug`).
- `(user/chat! "msg")` / `(user/chat! :agent/id "msg")` / `(user/monitor)` / `(user/monitor :agent/id)` — Chat with any agent (resetting view to live) and inspect its parent request, merge-gate progress, recursive children work tree, recent eval intents, and latest reply.
- `(user/spawn-agent! {...})` / `(user/send-message! :target :report "body")` / `(user/config :seon.config/eval-ticks)` — Process management and config lookup.

**Container Workspace Tools (`seon.fork.container`, active only when `SEON_EXEC_BRIDGE_URL` is set):**
- `(user/sh! "cmd" {:cwd "/app" :timeout-sec 60})` -> `{:exit N :out "..." :err "..."}`
- `(user/fs-read "/app/file" {:lines [1 120]})` -> `{:ok? true :lines N :content "..."}`
- `(user/fs-write "/app/file" "content")` -> `{:ok? true :bytes N}`
- `(user/fs-patch "/app/file" "old" "new" {:all? false})` -> `{:ok? true :replacements N}`

## 3. Native MCP Tools (`POST /mcp`, `bin/mcp`) & `bin/seon`

External sessions connect to the standing server (`http://127.0.0.1:9998`) via `bin/mcp` (zero-logic stdio relay) using 5 native tools:
- `seon_repl` (`code`, optional `branch`, `agent`, `task`, `scope`, `context`) — Evaluate Clojure in an isolated COW branch runtime. Automatically prepends `<walk-context>` + `<ephemeral-tail>` on Turn 1 (or whenever `task`, `scope`, or `context: true` is passed; set `context: false` to suppress) and prepends `<unread-messages>` on subsequent turns when mail arrives.
- `seon_health` — Full derived + live `seon.health/report`.
- `seon_test` (`branch`, optional `force`) — Minimal reach-selected, fail-fast tests + `seed = 42` generative checks.
- `seon_merge` (`branch`, optional `message`) — Malli-driven 3-way merge onto `:main` + local Git write-back + live hot-load / automatic atomic switch-over.
- `seon_status` — Live snapshot of `:main` tx, Git commit, branches, tasks, agents, and schemas.

The thin shell relay `bin/seon` exposes the same `/op` endpoints (`status`, `health`, `test`, `eval`, `var`, `schema`, `import`, `diff`, `merge`, `call`, `browser`, `start`, `build`, `take-over`, `self-test`, `switch-over`).
## 4. AOT Stdlib Tools (`jolt.fs`, `jolt.process`, `borkdude.text-diff`) & Batch Hygiene Task Mining

- **Pre-aliased `jolt.fs` (`fs`), `jolt.process`, and `borkdude.text-diff`:** `deps.edn` AOT-bakes `jolt.fs`, `jolt.process`, and `borkdude.text-diff` (`:jolt/build {:include [jolt.fs jolt.process borkdude.text-diff]}`) for `0 ms` runtime load. `seon.fork.helpers/install-agent-helpers!` pre-aliases `[jolt.fs :as fs]` in the REPL `user` namespace (e.g., `(fs/regular-file? p)`, `(fs/create-dirs dir)`, `(fs/delete-tree dir)`). Subprocess edges (`seon.browser`, `seon.vector`, `seon.bench.cluster`) use `jolt.process` with `:shutdown destroy-tree`, and CST edits return Wu et al. O(NP) multi-hunk unified diffs via `seon.code.cst/unified-diff` (`borkdude.text-diff`).
- **Batch Hygiene & Duplication Task Mining (`seon.code.mine`, `:template/hygiene-cleanup`):**
  - `(seon.code.mine/mine-hygiene-findings (user/db))` — O(N) indexed Datahike scan for AST-hash clones, independent cross-namespace duplicate helpers, raw Java IO/process sites, oversized namespaces, and large functions.
  - `(seon.code.mine/ingest-jolt-metrics-findings (user/db) metrics-edn)` — Pure converter mapping `jlt-commons/jolt-metrics` EDN reports (high cyclomatic/cognitive complexity, NCD near-duplicate pairs, churn/coupling hotspots) into verified `[:seon.code.var/name ...]` / `[:seon.code.ns/name ...]` findings.
  - `(seon.code.mine/mine-hygiene-tasks-tx (user/db) {:metrics-edn m :limit 8 :exclude-scopes #{...}})` — Emits idempotent `:seon.task/*` transaction maps using `:template/hygiene-cleanup`, `:seon.task/curated` lookup refs, `seon.code.reach/reaching-tests`, and `---`-separated rough 1–2 sentence plans (never reopening `:seon.task/done? true` tasks).
- **Curated Refs, Template Expansion & Cold-Start Doc Discovery:** `:op :create-task` (`seon.transactor.spawn/handle-create-task!`) forwards normalized `:curated` lookup refs onto `:seon.task/curated` and expands optional `:from-template` defaults. On cold-start underspecified tasks, `seon.ctx.ground/situational-candidates` queries `seon.vector/near` for both top 6 semantic vars (`:seon.code.var/name`) and top 2 semantic docs (`:seon.code.doc/id`).
