# Seon Agent Primers
Just-in-time behavioral primers projected dynamically into agent context via declarative `:seon.rule/*` Datalog rules.
## Messaging
- When the user says "Hello" or asks a question you can answer directly from context, reply immediately in clear, warm plain prose with **0 tool calls**.
- Do not invent tasks, edit files, or run speculative REPL lookups for simple greetings or questions.
- For complex or multi-step work, update live visual progress via `user/set-status!` and delegate or research cleanly.
- Always finish with a clear plain-prose reply summarizing the answer or outcome for the user.
## Live Progress
- Whenever multi-step work takes more than a quick step, keep the human informed with a live visual status card instead of a long blank spinner:
  `(user/set-status! {:label "Inspecting schemas" :detail "Checking live Datahike edges" :pct 40 :stage :researching :svg "<svg viewBox=\"0 0 120 24\" width=\"120\" height=\"24\"><rect x=\"2\" y=\"6\" width=\"48\" height=\"12\" rx=\"6\" fill=\"#f0b429\"/></svg>"})`
- Updates upsert `:seon.ui.status/entity` cleanly and render through `seon.render.status/render-status-card` (with safe SVG sanitization and progress bar) right in the human's Session view.
- Call `(user/clear-status!)` (or set `:pct 100 :stage :done`) when done, then send your final plain-prose answer.
## Data Modeling
- Model durable user and domain data directly in Datahike rather than ad-hoc files: inspect your live reactive state via `@seon.ctx/ctx` and `(user/db)`.
- Register co-located multi-segment Malli schemas via `(user/register-schema! :my.domain/field ...)` and `:my.domain/entity` (`{:seon.db/attributes true}`) with real `:db.type/ref` edges (`:seon.db/to`), `:seon.db/identity true`, `:seon.db/index true`, and `:gen/elements` or `:examples`.
- Pair entity schemas with pure `ns-map -> ns-map` Hiccup renderers returning `{:seon.ui/hiccup ...}` (mechanically discovered via `seon.render.fit/discover-renderers`) and verify layouts across widths with `(user/render-preview! ...)`.
## Editing
- Search prior work first with `(user/search ...)`, `(user/grep-src ...)`, `(user/ns-outline ...)`, and `(user/source 'ns/fn)` (which returns a `String`); extend or replace existing mechanisms instead of duplicating.
- Use CST structural editors (`user/edit-fn!`, `user/add-fn!`, `user/set-meta!`, `user/add-require!`, `user/prune-requires!`, `user/move-vars!`, `user/remove-var!`) — never write `.clj` files directly on disk.
- Batch independent reads and edits into multiple `repl` tool calls in one turn, put a crisp 4–8 word present-tense `:intent` on each call, and inspect large values directly through `result/e<n>`.
- Design to the lower bound: prefer indexed `:avet` Datahike reads over full scans, keep namespaces `< 8,000` tokens and functions `< 120` lines, and preserve or improve `:cost/ticks`.
## Contracts
- Every added or edited public `defn` must carry a docstring and `:malli/schema` metadata (attach via `defn` attr-map or `(user/set-meta! 'ns/fn {...})`).
- Use scoped domains and concrete `[:map ...]` or co-located schemas instead of bare `:map`/`:any` on pure functions, and use Clojure predicate symbols (`number?`, `pos-int?`, never `:number`).
- Include `:cost/ticks`, `:cost/bytes`, and executable Malli examples (`[:=> {:examples [[[arg] expected]]} [:cat ...] ret]`) on pure helpers so `seed = 42` generative checks verify them mechanically.
- Mark external I/O, network, process, or atom-boundary functions `^:seon.flow/edge` and return typed error data instead of silent catch-alls.
## Delegation
- Fan out independent sub-questions or scoped edits in parallel with `(user/ask! :ns/sub "...")` and collect results with `(user/await! call-ids)`.
- Inspect child work via `(user/inspect-branch :branch/...)` or `(user/calls)`, critique weak or unverified answers, and launch follow-up work when evidence is missing.
- When acting as a callee, settle incoming calls explicitly via `(user/answer! call-id result)` (or `(user/fail! call-id err)`), and track delegation progress with `(user/monitor)`.
## Proofs
- Run `(user/run-tests!)` (reach-selected `clojure.test` + 100-sample `seed = 42` Malli generative checks) and verify `(user/health)` reports `0` errors, `0` `:oversized-namespace`, `0` `:broken-doc-ref`, and `0` `:unused-require`.
- When working an assigned task on a branch that should merge, call `(user/ready!)` once all proofs and health checks are green.
- Always conclude with a clear, honest plain-prose reply (marked with observed `[M]` evidence) so the user knows exactly what was proved and done.
