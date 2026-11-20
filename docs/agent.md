# Agent Guide: REPL, `@seon.ctx/ctx`, `result/e<n>`, and MCP

Every Seon agent — including `:agent/root` and external coding-agent/IDE sessions via MCP — works inside an isolated Copy-on-Write Jolt runtime on its own Datahike branch using the exact same primitives.

## 1. Live Runtime `result/e<n>` Refs (`seon.code/eval-forms`)

Every successful evaluation binds its real live Clojure return value to a monotonic qualified symbol `result/e<n>` (e.g. `result/e1`, `result/e2`) in the `result` namespace, with execution telemetry stored on `(meta #'result/e<n>)`.

- Pass `result/e1` directly into any Clojure function on subsequent evals (e.g. `(count result/e1)`, `(:ok? result/e1)`, `(user/pin! result/e1)`).
- Evals are tick- and byte-bounded via `jolt.host/run-interruptible` so runaway loops fail cleanly as tool errors without harming sibling tool calls or the standing server.
- If an eval returns an entity map or lookup ref vector whose schema defines `:seon.render/hiccup` (e.g. `(user/health)`, `[:seon.code.ns/name 'seon.web]`, `[:seon.code.doc/id "docs/render.md#0"]`, `(d/pull (user/db) '[*] [:seon.code.var/name 'seon.web/web-proc])`), Seon records `:seon.agent.eval/result-schema` and automatically surfaces it as a live tile in the Level 2 Agent Workspace.

## 2. Reactive `@seon.ctx/ctx` and `user/*` Helpers (`seon.ctx`, `seon.fork`)

Each agent process has a private reactive `@seon.ctx/ctx` (also aliased as `user/ctx`) backed by per-clause `datahike.reads` invalidation (`O(|Δ|)` refresh, `identical?` unchanged subtrees) and `swap!` write-back through Malli. Its live `:seon.ctx/snapshot` tile (`seon.render.ui/ctx-tile`) is automatically included first in `<walk-context>` with colocated `{:mode :agent}` REPL affordances right beside the live data, and `[datahike.api :as d]` + `[clojure.string :as str]` are pre-aliased in `user`.

Available helpers in `user`:
- `(user/db)` / `(user/conn-of)` — Current branch Datahike DB snapshot and connection (query directly with `(d/datoms ...)` or `(d/pull ...)`).
- `(user/source 'ns/var | 'ns | "docs/file.md#sec" | :seon.schema/key)` — Read exact source form(s), Markdown doc section(s), or Malli schema straight from Datahike.
- `(user/callers 'ns/fn)` / `(user/callees 'ns/fn)` / `(user/tests-for 'ns)` — Indexed call-graph navigation & reaching test discovery (`O(|neighborhood|)`).
- `(user/remove-var! 'ns/var)` / `(user/diff)` — Safely remove moved/obsolete vars (checks callers first, retracts form & var, unmaps in memory) and inspect exact branch diffs + introduced health errors before `(user/ready!)`.
- `(user/doc! "docs/file.md#sec" "body")` / `(user/doc! "docs/file.md#sec" "## Heading" "body")` — Edit or add a lossless Markdown doc section directly on the agent's branch in Datahike (3-way merges & writes back to `docs/*.md`).
- `(user/register-schema! :seon.area/key malli-form {:doc "..." :render 'ns/fn})` — Validate with `seed = 42`, derive Datahike attributes, and install live (writes back to `resources/seon/schemas/*.edn` on merge).
- `(user/remember! "fact")` / `(user/forget! idx-or-fact)` / `(user/tune-context! {:extra-namespaces [...] :extra-schemas [...] :recent-data-limit N})` — Persist durable facts and customize what `<walk-context>` pulls.
- `(user/compact! "concise summary")` / `(user/compact! {:keep-last 1 :summary "..."})` — **Actually retracts** old `:seon.agent.eval/*` entities via `[:db/retractEntity eid]` (no archive flags!), shrinking active working context while Datahike's native `d/history` retains every retracted eval & seen message ID forever.
- `(user/past-evals {:limit 15 :grep "..."})` / `(user/as-of tx)` / `(user/history 'ns/fn)` — Native Datahike time-travel over retracted evals, historical DB snapshots, and past versions of code/docs/schemas.
- `(user/ask! :ns/<subsys> "prompt")` / `(user/await! [c1 c2])` / `(user/call! :ns/<subsys> "prompt")` / `(user/answer! cid val)` / `(user/fail! cid err)` / `(user/cancel! cid)` / `(user/calls)` — Flow-native async & awaitable inter-agent calls with lazy `:ns/<ns>` spawning, cycle rejection, Malli `:result-schema` checks, and zero-timer error settlement.
- `(user/canvas! {:title "..." :summary "..." :body [:div [:svg ...]]})` / `(user/clear-stage!)` — Replace the agent's live `:canvas` presentation in place (every call becomes a Datahike history step navigable via `◀ / ▶`, preserved across compactions via `d/history`).
- `(user/set-render! 'my.ui/canvas)` — Set `:seon.agent/render-fn` (`(defn canvas [ctx] ...)`) on `@ctx`; unmerged branch custom render functions execute fault-isolated inside the warm branch COW worker and update `:canvas` in realtime.
- `(user/merge-self!)` — Request a verified 3-way merge of the agent's branch into `:main` (or call `(user/ready!)` for automated reach-test + auto-merge).
- `(user/transact! tx-data)` — Transact validated datoms onto the current branch and refresh `@ctx`.
- `(user/health)` — Run `seon.health/report` on the current branch DB (returns a renderable `:seon.health/report` map).
- `(user/search "term")` — Search vars, schemas, and docs indexed in Datahike.
- `(user/pin! item)` / `(user/unpin! item)` — Pin or unpin an entity ref or `result/e<n>` value into `:seon.agent/pinned` on `@ctx`, updating both the human UI tiles and model walk context simultaneously.
- `(user/show-user! {:viewing :app/billing :mode :canvas :sidebar? true})` — Programmatically navigate or update the user's persistent Datahike viewport (`:user/default`), morphing their browser live over SSE.
- `(user/spawn-agent! {:id :area/worker :scope 'seon.area :role :worker :goal "..."})` — Spawn a child agent on its own Datahike branch.
- `(user/send-message! :target/agent :report "body")` — Send a durable inter-agent message via `:main`.
- `(user/ready!)` / `(user/wait-for-mail!)` — Mark branch ready for reach-testing + auto-merge, or pause awaiting messages.
- `(user/config)` / `(user/config :seon.config/key)` — Read effective config (`agent-entity -> :seon.config/root -> defaults`).

## 3. Native MCP Tools (`POST /mcp`, `bin/mcp`)

External sessions connect to the standing server via `bin/mcp` (zero-logic stdio relay to `POST /mcp`) using 5 tools that execute directly inside Seon:

- `seon_repl` (`code`, optional `branch`, `agent`) — Evaluate forms in a persistent COW branch runtime; returns `:handle` (`result/e<n>`), `:result-schema`, `:value`, `:ms`, `:ticks`.
- `seon_health` — Full derived + live `seon.health/report`.
- `seon_test` (`branch`, optional `force`) — Minimal reach-selected, fail-fast tests + seed=42 generative checks.
- `seon_merge` (`branch`, optional `message`) — Generic Malli-driven 3-way merge onto `:main` + local Git write-back + hot-load into root & `ForkParent`.
- `seon_status` — Live snapshot of `:main` tx, Git commit, branches, tasks, agents, and schemas.
