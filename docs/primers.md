# Seon Agent Primers
Just-in-time behavioral primers projected dynamically into agent context via declarative `:seon.rule/*` Datalog rules.
## Messaging

- When the user says "Hello" or asks a question you can answer directly from context, reply immediately in clear, warm Markdown with **0 tool calls**.
- Do not invent tasks, edit files, or run speculative REPL lookups for simple greetings or questions.
- For complex or multi-step work, update live visual progress via `user/set-status!` and delegate or research cleanly.
- Always finish with a clear Markdown reply (headings, tables, diffs, backtick entity links, or live `hiccup` / `seon-view` blocks when helpful) summarizing the answer or outcome.
## Live Progress

- Whenever multi-step work takes more than a quick step, keep the human informed with a live visual status card instead of a long blank spinner:
  `(user/set-status! {:label "Inspecting schemas" :detail "Checking live Datahike edges" :pct 40 :stage :researching :svg "<svg viewBox=\"0 0 120 24\" width=\"120\" height=\"24\"><rect x=\"2\" y=\"6\" width=\"48\" height=\"12\" rx=\"6\" fill=\"#f0b429\"/></svg>"})`
- Updates upsert `:seon.ui.status/entity` cleanly and render through `seon.render.status/render-status-card` (with safe SVG sanitization and progress bar) right in the human's Session view.
- Call `(user/clear-status!)` (or set `:pct 100 :stage :done`) when done, then send your final Markdown answer.
## Data Modeling

- Follow `docs/rules-of-thumb.md`: fix the data model first (true `:db.type/ref` edges, all real datoms preserved on entities, zero process flags), write declarative `:seon.rule/*` Datalog rules to derive signals/projections, use multiple pure `ns-map -> ns-map` renderers (`-chip`/`-row`/`-card`/`-full`) for distinct footprints, and use rule `:params` for situational detail on one card.
- In the REPL, `@ctx` is your live 2-index tree (`:eavt` forward + `:avet` reverse refs like `:seon.agent.eval/_agent-ref`): plain Clojure `(swap! ctx ...)` writes diffs back through Malli validation (`[:db/retractEntity]` on removed reverse-ref elements). Use `(user/ready!)` to signal your own session done. In pure functions/renderers, never deref `@ctx` — receive values/`db` as arguments and transact via Datahike functions when writing.
- Use wrapped Datahike primitives (`(user/db)`, `(user/main-db)`, `d/q`, `d/pull`, `d/datoms`, `d/history`, `seon.vector/near`, `user/transact!`) — bind `(let [db (user/db)] ...)` ONCE outside loops instead of calling `(user/db)` inside `for`/`doseq`, and prefer indexed `:avet` lookups over scanning all vars.
- Register co-located multi-segment Malli schemas via `(user/register-schema! :my.domain/field ...)` and `:my.domain/entity` (`{:seon.db/attributes true :seon.render/hiccup ...}`) with real `:db.type/ref` edges (`:seon.db/to`), `:seon.db/identity true`, `:seon.db/index true`, and `:gen/elements` or `:examples`.
## Editing

- Search prior work first with `(user/search ...)`, `(user/grep-src ...)`, `(user/ns-outline 'ns)` (each form entry has `:sym`, `:key`, `:lines`, `:chars`, `:tokens`), `(user/source 'ns/f1 'ns/f2)` or `(user/source 'ns [1 120])` line slicing (avoid dumping whole large files via `println`/`spit`; use `(user/expand! 'result/e<n> {:out? true})` when truncated), or direct Datahike `(d/pull db '[*] ref)` / `(d/datoms db :avet :seon.code.var/ns 'ns)`.
- Use CST structural editors (`user/edit-fn!`, `user/add-fn!`, `user/set-meta!`, `user/add-require!`, `user/prune-requires!`, `user/move-vars!`, `user/remove-var!`, `user/revert-ns!`) — never write `.clj` files directly on disk.
- `(user/add-fn! 'ns "(defn ...)")` overwrites existing functions in place and auto-toposorts — never call `user/remove-var!` before `user/add-fn!`. To create a brand-new namespace, call `(user/add-fn! 'my.new.ns "(ns my.new.ns (:require ...))")` first, then add functions with `user/add-fn!`. Batch contract updates with `(user/set-meta! {'ns/f1 {...} 'ns/f2 {...}})` and inspect `:ok?`.
- `(user/edit-fn! 'ns ...)` edits ONLY the `(ns ...)` header form — pass `'ns/fn` to edit a function. Use `(user/revert-ns! 'ns)` to cleanly restore any branch-edited or branch-created namespace back to merge-base.
- Batch 3–5 independent reads or `user/add-fn!` edits as separate parallel `repl` tool calls in one turn (with a 4–8 word `:intent`) instead of dripping 1 helper per turn.
- Design to the lower bound: prefer indexed `:avet` Datahike reads over full scans, keep namespaces `< 8,000` tokens and functions `< 120` lines, and preserve or improve `:cost/ticks`.
## Contracts
- Every added or edited public `defn` must carry a docstring and `:malli/schema` metadata (attach via `defn` attr-map or `(user/set-meta! 'ns/fn {...})`).
- Use scoped domains and concrete `[:map ...]` or co-located schemas instead of bare `:map`/`:any` on pure functions (avoid `:any` where `true`/`-3` breaks), and use Clojure predicate symbols (`number?`, `pos-int?`, never `:number`).
- Set realistic `:cost/ticks` (`>= 5000` for collection/map helpers, `>= 50000` for multi-pass helpers), `:cost/bytes`, and executable Malli examples (`[:=> {:examples [[[arg] expected]]} [:cat ...] ret]`) on pure helpers so `seed = 42` generative checks pass warm and cold.
- Mark external I/O, network, process, or atom-boundary functions `^:seon.flow/edge` (skipped by generative proofs) and return typed error data instead of silent catch-alls.
## Delegation
- Fan out independent sub-questions or scoped edits in parallel with `(user/ask! :ns/sub "...")` and collect results with `(user/await! call-ids)`.
- Inspect child work via `(user/inspect-branch :branch/...)` or `(user/calls)`, critique weak or unverified answers, and launch follow-up work when evidence is missing.
- When acting as a callee, settle incoming calls explicitly via `(user/answer! call-id result)` (or `(user/fail! call-id err)`), and track delegation progress with `(user/monitor)`.
## Proofs

- `(user/run-tests! 't1 't2)` is variadic and returns all results together — never hide earlier calls inside `(do (user/run-tests! ...) (user/run-tests! ...))`; `(user/run-tests!)` runs reach-selected tests for touched namespaces.
- Verify `(user/health)` reports `0` errors, `0` `:oversized-namespace`, `0` `:broken-doc-ref`, and `0` `:unused-require` (run `(user/health)` and `(user/diff)` as parallel tool calls instead of `(do ...)`).
- When working an assigned task on a branch that should merge, signal YOUR session done once all proofs and health checks are green via `(user/ready!)` (during review, never set `:seon.agent/ready?` on `:seon.agent/target`).
- Always conclude with a clear, honest plain-prose reply (marked with observed `[M]` evidence) so the user knows exactly what was proved and done.
## History & Time-Travel
- Compacted (retracted) turns omit earlier tool calls/outputs from prompt history, while branch code, schemas, and runtime defs remain cumulative.
- Gaps in `:seon.eval/turn` numbers (`1` -> `5`) show exactly which turns were compacted; full datoms remain intact in Datahike history.
- Inspect omitted evals via `(user/past-evals {:limit 15 :grep "..."})` or `(seon.store/agent-historical-evals (user/db) (:seon.agent/id @ctx))`.
- Time-travel any DB snapshot before a retraction with `(user/as-of tx)` or `(d/history (user/db))` and `(user/history (quote ns/fn))`.
## Ctx Atom
- `@ctx` (`user/ctx` / `@seon.ctx/ctx`) is your live 2-index Datahike tree (`:eavt` forward + `:avet` reverse refs like `:seon.agent.eval/_agent-ref`).
- Read selectively (`(keys @ctx)`, `(:seon.agent/tasks @ctx)`, `(mapv :seon.agent.eval/turn (:seon.agent.eval/_agent-ref @ctx))`) rather than printing the entire deref.
- Mutate via plain Clojure `(swap! ctx ...)`: `assoc` scalar attrs, `update` `:seon.agent/pinned` or `:seon.agent/curated`, or `filterv` `:seon.agent.eval/_agent-ref` (dropped maps transact `[:db/retractEntity eid]` automatically via `seon.ctx.tree/diff-entity-tx`).
- On forked agents, `(:seon.agent/forked-from @ctx)` expands the parent agent subtree, so `(swap! ctx update-in [:seon.agent/forked-from :seon.agent.eval/_agent-ref] ...)` edits parent evals directly; for bulk/cross-entity updates or sets like `:seon.agent/tasks`, use `(user/transact! [...])` + `d/q` / `d/pull`.
## Writing General Rules & Renderers
- **Diagnose what an agent saw and why before editing:**
  - `(user/context-trace)` (or `(user/context-trace :agent/id {:target :wire :token-budget 16000})`) shows every fired/skipped `:seon.rule/*` rule, `datahike.reads` skip count, 2-read world datoms, merged pull pattern, and fitted blocks.
  - `(user/diff-context rules-a rules-b)` A/B diffs projected entities, pull patterns, and token budgets between rule sets without guessing.
  - `(seon.render.fit/explain-candidates (user/db) candidates {:target :wire})` and `(seon.render.fit/explain-entity-fit (user/db) ref {:target :wire})` expose 3-signal ranking scores, every discovered renderer variation, measured `:tokens`/`:area`/`:min-width`, and exact selection or drop reasons.
- **Write general Datalog `:seon.rule/*` rules (`user/put-rule!`, persisted to `resources/seon/rules.edn`):**
  - Query over `$main`, `$branch`, and `$wm` (plus `?agent` and shared `%` rules such as `calls*`, `needs*`, `compatible-fn`, `render-fn`, `schema-doc`, `var-doc`), joining real schema and `:db.type/ref` graph edges or working-memory facts so rules compound across tasks.
  - **Never hardcode** specific entity names, task IDs, retired role/mode enums (`:seon.agent/role`, `:seon.agent/prefix-mode`), or arbitrary truncation caps.
  - Emit dynamic turn-by-turn signals (errors, diagnostics, nudges, compaction hints) in `:zone :ephemeral-tail` so the merge-base-anchored `<walk-context>` KV-cache prefix stays pinned (`:zone :static-prefix` is reserved for stable session anchors).
- **Write pure `ns-map -> ns-map` `:seon.ui/hiccup` renderers:**
  - Define pure, non-private, non-`^:seon.flow/edge` `defn`s with `:malli/schema [:=> [:cat [:map [:my.ns/entity :my.ns/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]` (declare any situational rule `:params` as optional keys in the input map, and set realistic `:cost/ticks`).
  - Provide compact, medium, and full footprints (`-chip` ~180px, `-row` ~220px, `-card` ~360px, `-full` ~540px) with `:data-min-width` / `:seon.ui/min-width` hints so `seon.render.fit/discover-renderers` and `seon.render.fit/upgrade-ladder` auto-discover and fit them in **both** LLM wire context (`:target :wire`) and the Web UI (`:target :html`) with zero manual wiring.
- **Core invariant:** Always fix the underlying data model, general rule, or pure renderer so every future agent benefits — never patch a single agent instance or add caller guards. Verify green with `(user/run-tests!)` and `(user/health)` (`0` errors, `0` `:broken-doc-ref`, `0` `:unused-require`), then finish cleanly via `(user/finish-review! summary)` (or `(user/ready!)`).
## Interactive Markdown & Canvas
- Replies render through `seon.render.markdown/render-markdown-blocks` with full CommonMark/GFM support: headings, pipe tables, blockquotes & GitHub alerts (`> [!NOTE]`/`[!TIP]`/`[!WARNING]`), task checklists (`- [x]`/`- [ ]`), strikethrough, and safe links/images.
- **Backtick deep-links:** citing a var (`` `seon.web/eval-snippet!` ``), namespace (`` `seon.web.router` ``), task, rule, agent, schema keyword, or doc path in backticks automatically renders a hovercard-enabled bookmarkable link.
- **Interactive fenced blocks:** use `` ```diff `` for colorized diffs, `` ```clojure `` / `` ```repl `` for Copy + 1-click **Run in REPL** (`seon.web/eval-snippet!`), `` ```hiccup `` for live sanitized inline HTML/SVG cards, and `` ```seon-view `` (e.g. `{:var seon.render.status/render-status-card :data {...}}` or `{:ref [:seon.task/id ...]}`) for live reactive cards.
- **UI preview & graph helpers:** `(user/ui-patch! 'ns/fn old new)` edits a `:seon.ui/hiccup` renderer and evaluates its derived preview in one turn (0 DB writes); `(user/render-preview! 'ns/fn)` renders ephemeral multi-width previews (`:ok? true`, `:persisted? false`); `(user/explore! ref)` projects any entity via the Upgrade Ladder; `(user/similar q)` runs semantic vector search.
- **Pre-bound REPL aliases (`repl` & `user`):** `d` (`datahike.api`), `str` (`clojure.string`), `fs` (`jolt.fs`, e.g. `(fs/exists? path)`), and `proc` (`jolt.process`, e.g. `(proc/sh ...)` / `proc/process`) are pre-required; call `(user/help)` or `(user/help "term")` to inspect all installed `user/*` helpers live.
