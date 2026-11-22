# Agent Guide: REPL, `@seon.ctx/ctx`, `result/e<n>`, and MCP

Every Seon agent — including `:agent/root`, autonomous branch workers, and external coding-agent sessions via MCP — operates inside an isolated Copy-on-Write Jolt runtime on its own Datahike branch using the exact same primitives.

## 1. Live Runtime `result/e<n>` Refs (`seon.code/eval-forms`)

Every successful evaluation binds its live Clojure return value to a monotonic qualified symbol `result/e<n>` in the `result` namespace, with execution telemetry (`:seon.eval/ms`, `:seon.eval/ticks`, `:seon.eval/bytes`, `:seon.eval/out`) on `(meta #'result/e<n>)`.

- Pass `result/e1` directly into subsequent evals (e.g., `(count result/e1)`, `(:ok? result/e1)`, `(user/pin! result/e1)`).
- Evals are bounded via `jolt.host/run-interruptible` (`:seon.config/eval-ticks` 100M, `:seon.config/eval-bytes` 256 MB) so runaway loops fail cleanly as tool errors without harming sibling calls or the standing server.
- Eval outputs format through `seon.model.wire/format-eval-output` — preferring pure `:seon.ui/hiccup` renderers discovered by `seon.render.fit/discover-renderers` and falling back to the 4-stage breadth-before-depth printer (`seon.render/render-value` -> `seon.render/hiccup->text`) with `(user/expand! ...)` drill-ins and live schema breadcrumbs. Returning an entity map or lookup ref (e.g., `(user/health)`, `[:seon.code.ns/name 'seon.web]`, `[:seon.code.doc/id "docs/render.md"]`) surfaces it automatically as a live entity card on the unified surface.
- Evaluation errors pass through `seon.code.parse/enrich-eval-error`, appending class-level recovery hints (e.g., `deftest` persistence via `user/add-fn!`, Malli predicate symbols vs `:number`, `user/run-tests!` 4 GB budget override, Clojure regex escaping, container return shapes, Datahike `:memory` UUID `:id`, and `(user/db)` vs `seon.ctx/*`).

## 2. Reactive `@seon.ctx/ctx`, Unified Context Pipeline & `user/*` Helpers

Each agent process holds a private reactive `@seon.ctx/ctx` (`seon.ctx`, aliased as `user/ctx`) backed by `datahike.reads` invalidation (`O(|Δ|)` refresh) and Malli-validated `swap!` write-back. `[datahike.api :as d]` and `[clojure.string :as str]` are pre-aliased in `user`.

**How agent context is built (`seon.ctx.project`, `seon.ctx/related-updates`, `seon.rule`, `seon.render.fit`, `seon.render.wire`):**
- **Generic 2-read world discovery (`seon.ctx.project/agent-world-datoms`):** Zero hand-tuned pull lists — 1 forward `:eavt` read on `agent-eid` (merged across `:main` + branch by latest `:tx`) plus reverse `:avet` reads across every `:db.type/ref` attribute whose `:seon.schema/ref-targets` targets `:seon.agent/entity`.
- **Native Datahike `d/q` rules (`seon.rule/step-rules`):** Editable `:seon.rule/*` entities (persisted 1-to-1 to `resources/seon/rules.edn`) execute native `:seon.rule/query` clauses with shared recursive `%` rules (`seon.rule/default-datalog-rules`: `calls*`, `needs*`, `compatible-fn`, `render-fn`, `schema-doc`, `var-doc`) inside `datahike.reads/with-reads`, skipping untouched rules in `O(|Δ|)`.
- **3-signal rank + Complete-Thought Upgrade Ladder (`seon.render.fit/fit-projections`):** Ranks candidates by urgency/anchor + native `?tx`/`:seon.agent.eval/turn` recency + cumulative touches/convergence (`:seon.config/rank-weights`), pulls each entity once, discovers pure `:seon.ui/hiccup` renderers via `seon.render.fit/discover-renderers`, and fits `:seon.view/token-budget` using the `already-shown` zero-repetition queue (`seon.ctx.project/compute-shown-delta`, least-volatile -> most-volatile KV-cache ordering).
- **Pure Hiccup `<ephemeral-tail>` (`seon.render.entities`):** Volatile branch status + turn budget, scoped/system health, neighborhood activity, unread inbox, Chronicle anchors, and optional live `WKWebView` frame, rendered through the same `seon.render/hiccup->text` pipeline.

### Available `user/*` Helpers (`seon.fork.helpers`, `seon.fork.calls`, `seon.fork.container`, `seon.repl`)

**Read, Context, Search & Navigate:**
- `(user/context)` / `(user/context 'ns | :ns/<task-id> | {:task ... :scope ... :extra-namespaces [...]})` — Render the exact rule-derived Upgrade-Ladder `<walk-context>` + `<ephemeral-tail>` that internal Seon agents receive on the wire.
- `(user/db)` / `(user/main-db)` / `(user/conn-of)` — Branch DB snapshot, `:main` DB snapshot, and branch connection. Prefer indexed `(d/datoms db :avet/:aevt :attr ...)` over scanning `d/q`.
- `(user/source 'ns/fn ...)` / `(user/source 'ns 1 80)` / `(user/source "docs/file.md#slug")` / `(user/source :seon.schema/key)` — Variadic source/doc/schema lookup with optional line range or `{:lines [start end]}`.
- `(user/grep-src "pat" | ["p1" "p2"] {:ns 'ns})` — Cached single-pass source search returning `:sym`, `:key`, `:line`, `:text`.
- `(user/ns-outline 'ns)` / `(user/ns-requires 'ns)` — Token counts, form list, local deps, callers, and parsed `:require` specs.
- `(user/callers 'ns/fn)` / `(user/callees 'ns/fn)` / `(user/tests-for 'ns)` / `(user/search "term")` — Indexed call-graph, `:input`/`:output` schema edges, reaching tests, and full-index search.
- `(user/expand! 'result/e1)` / `(user/help)` / `(user/help 'edit-fn!)` — Drill into elided eval outputs or inspect any helper.

**Context Introspection, Native Datalog Rules & Empirical Lab (`seon.ctx/install-ctx-helpers!`, `seon.rule.context/install-rule-helpers!`):**
- `(user/context-trace)` / `(user/context-trace :agent/id {:target :wire :token-budget 16000})` — Inspect every fired/skipped native `d/q` rule, `datahike.reads` skip count, generic 2-read world discovery, merged pull pattern, chosen renderer, and token cost.
- `(user/diff-context)` / `(user/diff-context rules-a rules-b)` — A/B diff projected entities, pull patterns, and token budgets across rule sets or `:main` vs branch.
- `(user/rules)` / `(user/rules :context)` / `(user/put-rule! {:seon.rule/id :rule.x/y :seon.rule/stratum 50 :seon.rule/query [...] :seon.rule/assert [...]})` / `(user/toggle-rule! :rule.x/y false|true)` — Inspect and live-edit native Datahike `d/q` rules (persists to `resources/seon/rules.edn` on merge).
- `(user/explain-context)` / `(user/diff-rules :agent/id rules-a rules-b)` / `(user/curate! [[:seon.code.var/name 'ns/fn] ...])` — Inspect tile provenance and share curated discoveries across the delegation tree.
- **Empirical Precision/Recall & `d/as-of` Hyperparameter Lab:** Track Precision (% of shown blocks and tokens later touched in evals) and Recall Gap (manual `source`/`grep-src`/`d/pull` lookups for entities already in the candidate pool), and replay historical `(d/as-of db tx)` snapshots across `:seon.config/rank-weights` and `:breadth-first` vs `:depth-first` Upgrade Ladder profiles via `(user/context-scoreboard)` and `(user/compare-hyperparams)`.

**Structural Code, Refactor, Doc & Schema Editing (never spit `.clj` files on disk):**
- `(user/edit-fn! 'ns/fn old new)` / `(user/add-fn! 'ns "(defn ...)")` / `(user/set-meta! 'ns/fn {:malli/schema [...] :cost/ticks 50000})` — CST-safe `rewrite-clj` editors with upfront `:malli/schema` validation and automatic topological form ordering (`seon.code.cst.ns/topo-order-ns-forms`).
- `(user/add-require! 'ns '[lib :as a])` / `(user/prune-requires! 'ns)` / `(user/unused-requires 'ns)` — AST-accurate require management.
- `(user/move-vars! {:from 'a :to 'a.sub :alias 'sub :vars '[f1 f2] :include-deps? true})` / `(user/remove-var! 'ns/v)` / `(user/plan-rewrite ...)` / `(user/rewrite-graph! ...)` / `(user/rename-ns! ...)` / `(user/rename-schema! ...)` / `(user/migrate-data! ...)` / `(user/validate-migration ...)` — Safe graph-wide refactoring and schema migrations.
- `(user/doc! "docs/file.md#slug" "body")` / `(user/doc! "docs/file.md#slug" "## Heading" "body")` — Lossless Markdown section editor (`String` body required; auto-updates `:seon.code.doc/references`).
- `(user/register-schema! :seon.area/<key> malli-form {:doc "..." :examples [...]})` — Validate with `seed = 42`, derive Datahike attributes, and persist to `resources/seon/schemas.edn` on merge (renderers are normal pure functions discovered via `:seon.code.var/input` + `:seon.code.var/output` `:seon.ui/hiccup`).
- `(user/transact! tx-data)` — Transact Malli-validated datoms onto the current branch.

**Testing, Health, Telemetry & Shipping:**
- `(user/run-tests! 'ns-test/var | 'ns ...)` — Variadic reach-aware test runner with 4 GB allocation override. For Datahike tests use `(seon.test-fixture/with-test-store [{:keys [conn]}] ...)` or `{:store {:backend :memory :id (random-uuid)} :schema-flexibility :read :keep-history? false}`.
- `(user/health)` / `(user/diff)` / `(user/inspect-branch :branch/name)` / `(user/gate-status)` / `(user/metrics)` / `(user/autofix-health!)` — Inspect branch health, 3-way diffs, task gates, efficiency metrics, and run safe static auto-fixes.
- `(user/ready!)` / `(user/merge-self!)` / `(user/wait-for-mail!)` — Trigger automated reach-test + auto-merge, or pause for mail.
- `(user/record-summary! {:kind :milestone :title "..." :body "..." :refs [...]})` / `(user/start-bench-run! opts)` — Persist a Chronicle milestone or launch an async parallel isolated benchmark batch.

**Delegation, Memory, Time-Travel & Viewport:**
- `(user/ask! :ns/<subsys> "prompt")` / `(user/await! [c1 c2])` / `(user/call! ...)` / `(user/answer! cid val)` / `(user/fail! cid err)` / `(user/cancel! cid)` / `(user/calls)` — Flow-native recursive inter-agent calls with cycle rejection and Malli `:result-schema` validation.
- `(user/create-task! {...})` / `(user/assign-task! :ns/<task-id>)` / `(user/assign-tasks! ...)` / `(user/update-task! {...})` / `(user/save-example! :agent/id :example/<name>)` — Task and example session management.
- `(user/remember! "fact")` / `(user/forget! idx-or-fact)` / `(user/tune-context! {...})` / `(user/pin! item)` / `(user/unpin! item)` — Durable memory, pins, and context tuning.
- `(user/compact! "summary")` / `(user/past-evals {:limit 15 :grep "..."})` / `(user/as-of tx)` / `(user/history 'ns/fn)` — True `[:db/retractEntity]` compaction with full `d/history` time-travel.
- `(user/show-user! {:viewing :agent/id :mode :canvas|:workspace|:debug :as-of-tx tx})` — Move the user's browser viewport or `d/as-of` time-travel cursor live via `:user/default`.
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
