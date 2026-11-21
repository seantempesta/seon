# Agent Guide: REPL, `@seon.ctx/ctx`, `result/e<n>`, and MCP

Every Seon agent — including `:agent/root`, autonomous branch workers, and external coding-agent sessions via MCP — operates inside an isolated Copy-on-Write Jolt runtime on its own Datahike branch using the same primitives.

## 1. Live Runtime `result/e<n>` Refs (`seon.code/eval-forms`)

Every successful evaluation binds its live Clojure return value to a monotonic qualified symbol `result/e<n>` in the `result` namespace, with execution telemetry (`:seon.eval/ms`, `:seon.eval/ticks`, `:seon.eval/bytes`, `:seon.eval/out`) on `(meta #'result/e<n>)`.

- Pass `result/e1` directly into subsequent evals (e.g., `(count result/e1)`, `(:ok? result/e1)`, `(user/pin! result/e1)`).
- Evals are bounded via `jolt.host/run-interruptible` (`:seon.config/eval-ticks` 100M, `:seon.config/eval-bytes` 256 MB) so runaway loops fail cleanly as tool errors without harming sibling calls or the standing server.
- Returning an entity map or lookup ref whose schema declares `:seon.render/hiccup` (e.g., `(user/health)`, `[:seon.code.ns/name 'seon.web]`, `[:seon.code.doc/id "docs/render.md#1-incremental-datahikereads-walk-reagent-event-wiring-seonrender"]`) automatically surfaces it as a live tile in the Workspace view.
- Evaluation errors pass through `seon.code.parse/enrich-eval-error`, appending targeted class-level recovery hints (e.g., `deftest` persistence via `user/add-fn!`, Malli predicate symbols vs `:number`, `user/run-tests!` 4 GB budget override, Clojure regex escaping, `fs-read`/`sh!` return map shapes, Datahike `:memory` UUID `:id`, and `(user/db)` vs `seon.ctx/*`).

## 2. Reactive `@seon.ctx/ctx`, Worker Context Tiles & `user/*` Helpers

Each agent process has a private reactive `@seon.ctx/ctx` (`seon.ctx`, aliased as `user/ctx`) backed by `datahike.reads` invalidation (`O(|Δ|)` refresh) and Malli-validated `swap!` write-back. `[datahike.api :as d]` and `[clojure.string :as str]` are pre-aliased in `user`.

**What a normal worker sees in `<walk-context>` (derived by `seon.rules.context/derive-context`, 43 editable `:seon.rule/*` entities):**
- **`ctx-tile` (`seon.render.tiles/ctx-tile`):** Role, branch, durable memory, and static Core REPL reference (or Container Workspace tools when `:mode :container`).
- **`task-tile` & `task-lineage-tile`:** Assigned `:seon.task/*` (resolved branch-first, then `:main`) plus deduplicated `<task-chain>` and `<historical-tasks>` (plan & template boilerplate automatically deduplicated on concrete tasks).
- **Rule-grounded situational tiles:** Explicitly named namespaces/vars (`:tag/mentioned`, full untruncated source up to 12k chars + covering tests), specificity-ranked Datahike/Malli schemas (`schema-tile` with `[unique]`/`[indexed]` markers, renderer var, and scope-prioritized `Used by:`), schema renderers (`:tag/schema-renderer`), in-scope schema consumers (`:tag/schema-consumer`), lexical search hits when a task is underspecified, and delegation-tree overlap boosts (`:tag/tree-overlap` for entities read/curated by `>= 2` agents in the tree).
- **`ns-tile` (`seon.render.ns-tile/ns-tile`):** Adapts automatically between compact `ns-summary-strip` (when a broad parent scope is superseded by explicitly named target namespaces) and full `ns-tile-full` (Public API signatures/docs/Malli schemas, `Private helpers:`, `Requires:` aliases, covering tests + `Test ns requires:`, auto-linked docs, working schemas, and static health alerts).
- **Ephemeral tail (`seon.render.tail` + `<rule-tail>`):** Volatile `:ephemeral-tail` rule tiles, live branch diff summary, and turn budget usage.
### Available `user/*` Helpers (`seon.fork.helpers`, `seon.fork.calls`, `seon.fork.container`)

**Read, Context, Search & Navigate:**
- `(user/context)` / `(user/context 'ns | :seon.ns/task-id | {:task ... :scope ... :extra-namespaces [...]})` — Render the **exact same** rule-derived `<walk-context>` + `<ephemeral-tail>` that internal Seon agents receive on the wire. External sessions should call this first on every task.
- `(user/db)` / `(user/main-db)` / `(user/conn-of)` — Branch DB snapshot, `:main` DB snapshot, and branch connection. Prefer indexed `(d/datoms db :avet/:aevt :attr ...)` over scanning `d/q`.
- `(user/source 'ns/fn ...)` / `(user/source 'ns 1 80)` / `(user/source "docs/file.md#slug")` / `(user/source :seon.schema/key)` — Variadic source/doc/schema lookup with optional positional `start end` line range or `{:lines [start end]}`.
- `(user/grep-src "pat" | ["p1" "p2"] {:ns 'ns})` — Cached single-pass source search returning `:sym`, `:key`, `:line`, `:text`.
- `(user/ns-outline 'ns1 'ns2)` — Token counts, form list, local deps, and callers for one or more namespaces.
- `(user/callers 'ns/fn)` / `(user/callees 'ns/fn)` / `(user/tests-for 'ns)` — Indexed call-graph and reaching-test lookup.
- `(user/search "term")` — Full index search across vars, schemas, and docs.
- `(user/help)` / `(user/help 'edit-fn!)` — Built-in helper reference.

**Declarative Context Rules & Curation (`seon.rules.context/install-rule-helpers!`):**
- `(user/rules)` / `(user/rules :layout)` — List active `:seon.rule/*` entities (all or by `:seon.rule/category`).
- `(user/put-rule! {:seon.rule/id :rule.x/y :seon.rule/stratum 50 :seon.rule/when [...] :seon.rule/assert [...]})` — Create or update a live context rule in Datahike (immediately updates both Browser UI and LLM Wire; persists to `resources/seon/rules.edn` on merge).
- `(user/toggle-rule! :rule.id false|true)` — Enable/disable a rule live.
- `(user/explain-context)` / `(user/explain-context :agent/id {:target :wire|:html})` — Inspect exact derived tiles, priorities, layouts, tags, `Why:` provenance, and fired rules.
- `(user/diff-rules :agent/id rules-a rules-b)` — A/B compare Control vs Test rulesets (added/removed facts, rule firing diffs, layout diffs).
- `(user/curate! [[:seon.code.var/name 'ns/fn] ...])` — Persist agent/task-curated lookup refs (`:seon.agent/curated`, `:seon.task/curated`) to share discoveries across the delegation tree and boost multi-agent overlap (`:tag/tree-overlap`).

**Structural Code, Doc & Schema Editing (never spit `.clj` files on disk):**
- `(user/edit-fn! 'ns/fn old new)` or `(user/edit-fn! 'ns/fn [[old1 new1] [old2 new2]])` — CST-safe `rewrite-clj` patch on balanced Clojure forms; automatically topologically reorders intra-namespace forms (`seon.code.cst.ns/topo-order-ns-forms`) so helpers never land after callers.
- `(user/add-fn! 'ns "(defn ...)")` — Add or replace a top-level form (including tests in `'ns-test`); validates `:malli/schema` upfront, auto-formats 1-line minified strings (>= 200 chars), and topologically reorders intra-namespace forms automatically.
- `(user/set-meta! 'ns/fn {:malli/schema [...] :cost/ticks 50000})` — Attach/update var metadata (validates Malli schema upfront).
- `(user/add-require! 'ns '[lib :as a])` / `(user/prune-requires! 'ns)` / `(user/unused-requires 'ns)` — AST-accurate require management.
- `(user/move-vars! {:from 'a :to 'a.sub :alias 'sub :vars '[f1 f2] :include-deps? true})` / `(user/remove-var! 'ns/v)` — Safe refactoring across namespaces.
- `(user/doc! "docs/file.md#slug" "body")` / `(user/doc! "docs/file.md#slug" "## Heading" "body")` — Edit or add a lossless Markdown section (strictly requires a `String` body).
- `(user/register-schema! :seon.area/key malli-form {:doc "..." :render 'ns/fn})` — Validate with `seed = 42`, derive Datahike attributes, and persist to `resources/seon/schemas.edn` on merge.
- `(user/transact! tx-data)` — Transact Malli-validated datoms onto the current branch.

**Testing, Health, Telemetry & Shipping:**
- `(user/run-tests! 'ns-test/var | 'ns ...)` — Variadic reach-aware test runner with 4 GB allocation override. For Datahike tests use `(seon.test-fixture/with-test-store [{:keys [conn]}] ...)` or `{:store {:backend :memory :id (random-uuid)} :schema-flexibility :read :keep-history? false}`.
- `(user/health)` / `(user/diff)` / `(user/diff {:health? true})` / `(user/metrics)` / `(user/autofix-health!)` — Inspect branch health, 3-way diffs, efficiency metrics, and run safe static auto-fixes (including topological reorder).
- `(user/ready!)` / `(user/merge-self!)` / `(user/wait-for-mail!)` — Trigger automated reach-test + auto-merge, request direct merge, or pause for mail.
- `(user/record-summary! {:kind :milestone :title "..." :body "..." :refs [...]})` — Persist a Chronicle milestone onto the Living Canvas.
- `(user/start-bench-run! opts)` — Launch an async parallel isolated benchmark batch streaming live to `http://127.0.0.1:9998/` (`#cv-bench-runs`).

**Delegation, Context, Time-Travel & UI:**
- `(user/ask! :ns/<subsys> "prompt")` / `(user/await! [c1 c2])` / `(user/call! ...)` / `(user/answer! cid val)` / `(user/fail! cid err)` / `(user/cancel! cid)` / `(user/calls)` — Flow-native recursive inter-agent calls with cycle rejection and Malli `:result-schema` validation.
- `(user/remember! "fact")` / `(user/forget! idx-or-fact)` / `(user/tune-context! {...})` / `(user/pin! item)` / `(user/unpin! item)` — Durable memory and `<walk-context>` tuning.
- `(user/compact! "summary")` / `(user/past-evals {:limit 15 :grep "..."})` / `(user/as-of tx)` / `(user/history 'ns/fn)` — True `[:db/retractEntity]` compaction with full `d/history` time-travel.
- `(user/canvas! {:title "..." :summary "..." :body [:div ...]})` / `(user/clear-stage!)` / `(user/set-render! 'my.ui/canvas)` / `(user/show-user! {:viewing :agent/id :mode :canvas|:workspace|:debug})` — Live Canvas presentation and viewport navigation.
- `(user/chat! "msg")` / `(user/chat! :agent/id "msg")` / `(user/monitor)` / `(user/monitor :agent/id)` — Start/continue a live chat (resetting Canvas to `:live`, identical to browser chat) and inspect any agent's parent request, own merge-gate progress, recursive children work tree, recent eval intents, and latest reply.
- `(user/spawn-agent! {...})` / `(user/send-message! :target :report "body")` / `(user/config :seon.config/key)` — Process management and config lookup.

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

The thin shell relay `bin/seon` exposes the same `/op` endpoints (`status`, `health`, `test`, `eval`, `var`, `schema`, `diff`, `merge`, `browser`, `start`, `build`, `take-over`).
