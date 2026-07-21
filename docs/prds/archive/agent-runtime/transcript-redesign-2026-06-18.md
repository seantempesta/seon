---
type: prd
status: active
tags: [prd, agent, ctx]
---

# Transcript redesign — threaded, REPL-faithful, `result/<id>` vars

## Status (2026-06-18)

- DONE — `result/<id>` value vars (commit 4221edf, `seon.eval`).
- DONE — the RENDER lane in `seon.ctx`: turn-grouped `<transcript note=…>`
  with per-turn `<turn id=… evals=N/M>` + woken-by `<user>`/`<from agent=…>`,
  per-eval REPL-faithful `format-eval-row` (`=> value ;; result/<id>`,
  failures `=> ✗ …` with no handle, clipped `(N of M)`), the
  RESULT VARS / EVAL MECHANICS system-prompt updates, and the rewritten
  `agent_context_test` byte-pins. LIVE-VERIFIED: a real driven agent turn
  replied with `;;` comments + a form and REFERENCED a prior `result/<id>`
  var — no XML/`=>` mimicry.
- OPEN (out of the render lane's scope) — `seon.eval/render-result-edn`'s
  large-collection clip guide + the `*1 *2 *3` error teaching still teach
  the retired `(result :<id>)` API; they should move to `result/<id>` to
  match the redesign. `seon.eval` is committed/owned elsewhere — flagged,
  not changed here.


## TL;DR

Replace the `<transcript>` section's per-eval render (a separate de-`;;`'d
"narration" blob + the eval result shown as a `;; ⇒ (result :id)` comment)
with a **threaded, REPL-faithful, minimally-XML-bounded** transcript:

- The agent's output is rendered **natural** — its `;;` comments and forms
  exactly as it wrote them (fixed up), never wrapped in per-form tags.
- Eval results render as **REPL output** (`=> value`), visually distinct from
  `;;` comments. (Agents were confusing output-values-shown-as-comments with
  comments they meant to use — observed live, ari-2606180804, 2026-06-18.)
- Each turn is wrapped `<turn id=… evals=N/M>` with the user's message in
  `<user>…</user>`, threading the conversation.
- Stable **domain ids** (`:seon.agent.turn/id`, `:seon.eval/id`) — never
  numeric eids (internal, unstable across replay/store rebuild,
  hallucination-prone).
- Eval values are exposed as **`result/<id>` vars** — the agent references
  `result/auC-2606181147` directly. The `(result …)` fn API is retired from
  the surface.

Scope: the **transcript lane only** — `seon.ctx/format-eval-row` + the
transcript-section assembly, `seon.repl.internal/parse-forms` (prose capture,
already on the branch as the WIP commit 9dc4848), the `result/<id>` mechanism
in `seon.eval`, and the system-prompt RESULT VARS / EVAL MECHANICS text.
Another agent owns `<findings>` / `core-default-ctx` — **do not touch them**;
coordinate on `ctx.cljs` (different fns, same file → sequence commits).

## The rendered shape

```
<transcript note="These are your PAST turns. The runtime adds each form's `=> result` line and the `;; result/<id>` after it — never write `=>` or `;; result/` lines yourself. To reuse any result, reference result/<id> directly; it is a live var.">
<turn id=KMX-2606181147 evals=2/2>
<user>store ~100 papers and show the top 10 by citations</user>
;; register the paper schema
(seon.schema/register! :my.kb.paper/title :string)
=> :my.kb.paper/title ;; result/auC-2606181147
;; transact the papers
(seon.db/transact! {:seon.db/tx-data [...]})
=> {:seon.db/tx-count 100} ;; result/fE9-2606181147
</turn>
<turn id=Qp2-2606181150 evals=2/3>
<user>now make a tile showing them</user>
;; top 10 by citations
(seon.db/query {:seon.db/query '[:find ?t ?c :where ...] :seon.db/limit 10})
=> [["Attention Is All You Need" 9001] ["BERT" 7400] …] ;; result/rT5-2606181150 (10 of 847)
;; define the tile
(defn my-kb-tile [_] {:seon.render/hiccup [:div "…"]})
=> #'my.agent.ari/my-kb-tile ;; result/wK1-2606181150
;; sum the citations
(reduce + cites)
=> ✗ undeclared var: cites — you never bound it. Bind or pass it, then re-eval.
</turn>
</transcript>
```

### Rules

- **Tags:** `<transcript>` (carries `note=`), `<turn id=… evals=N/M>`,
  `<user>…</user>`. For an agent-woken turn, `<user>` becomes
  `<from agent=…>…</from>` (or similar) so a human vs agent trigger is clear.
  **No `<eval>` tag** — the agent's output is plain REPL.
- **`<user>` source:** the turn's `:seon.agent.turn/woken-by` →
  `:seon.agent.message/content`; `from` selects the label. No woken-by → omit
  the line. (NOT `prompt-text`, which is the in-memory full prompt.)
- **`evals=N/M`:** ok-count / total evals in the turn (derived from each
  eval's `:seon.eval/ok?`).
- **`=> value`:** plain REPL output. Small values shown inline; large values
  clipped with `(N of M)`.
- **`;; result/<id>`:** trails a successful eval's value — the live var
  handle. `<id>` = `:seon.eval/id`. Failed evals get **no** `result/<id>`
  (no value to retrieve); their payload is the `=> ✗ …` guidance.
- **Errors** render `=> ✗ <crystal-clear guidance>`: parse → reader message +
  line:col + caret + "this form DEFINED NOTHING; fix & re-eval"; compile →
  names the undeclared var + "define it first / typo?"; runtime → the error
  value + "errors are values — read & adapt." Never a stack trace.
- **Repaired forms** are shown fixed (parinfer, already on the branch); clean
  delimiter fixes are silent ("agent never knows it was broken"); the form
  shown is the repaired one. (Breadcrumb optional/configurable.)
- **Prose** the agent wrote without `;;` is captured as `;;` comments (WIP
  commit 9dc4848), shown above its form — never dropped.

## `result/<id>` vars (retire the `(result …)` API)

Each **successful** eval auto-binds its value as `result/<id>`:

1. Set the value at `globalThis.result.<munged-id>` and register `<id>` in the
   `result` namespace's analyzer defs in the agent's compile-state — the SAME
   def-into-a-ns mechanism the runtime already uses for agent fns
   (`my.agent.x/foo`), so `result/auC-2606181147` resolves with **zero
   `undeclared-var` warning**.
2. **Cap per session:** keep the last N evals' vars (e.g. 200), undef older
   ones to bound memory (vars retain their values). Resets on process restart
   (vars don't survive — same as today's globalThis stash).
3. **Graceful miss:** a reference to a pruned / prior-session `result/<id>`
   must NOT throw a raw `undeclared-var`. Special-case `result/*` in the
   undeclared-var path (the A.4 failed-def/undeclared machinery) to return the
   helpful value: "that result isn't live (prior session or pruned) — re-run
   its form to recompute it." Keep the errors-are-values philosophy.

Confirm `result` doesn't collide with an existing global/ns; if it does, pick a
short reserved ns and update the spec + `note=`.

The legacy `seon.eval/lookup-result` / `(result …)` fn may remain as the
graceful-miss resolver but is **removed from the taught surface** — no
`(result :id)` appears in the transcript or system prompt.

## System-prompt changes (`seon.ctx`)

- **RESULT VARS:** "Every eval's value is a live var `result/<id>`, the id
  shown after its `=>`. Reference `result/<id>` to reuse it — never re-run a
  computed form. A clipped display is not a clipped value; dig in with ordinary
  Clojure." Drop `(result :id)`.
- **EVAL MECHANICS:** add "The `<transcript>` is read-only history. The runtime
  adds the `=> result` and `;; result/<id>` lines — never write `=>`,
  `;; result/`, or any tag yourself. Your reply is only `;;` comments and
  forms."

## Mimicry safety (the worry, addressed)

1. Only envelope/human tags (`<transcript>`/`<turn>`/`<user>`) — nothing in the
   agent's own output shape to copy; its forms render exactly as it'd type
   them.
2. `note=` disclaims the runtime annotations in-band.
3. The context still ends in a clean `<your-ns>=>` cursor (the strongest
   "type here" signal), no XML.
4. **Graceful even if it mimics:** the hardened `parse-forms` reads `<…>` and
   `=>` as bare/invalid tokens → dropped as narration; real forms still run. A
   mimic degrades to "annotations ignored, forms execute," not a broken turn.
5. **Verify live:** drive a real agent post-implementation and confirm replies
   are forms + `;;`, not tags/`=>`. If mimicry appears, the parser contains it
   and we can coarsen boundaries.

## Test plan (`bin/test-cljs`, fresh JVM)

| Scenario | Assert |
| --- | --- |
| round-trip | rendered transcript re-parses to the same forms + `;;` comments; prose comes back as `;;` |
| `result/<id>` resolves | a recent eval's value is referenceable as `result/<id>` (no undeclared warning) |
| graceful miss | a pruned / unknown `result/<id>` yields the "not live — re-run" value, not a raw error |
| clipped value | `=> … (N of M)` + the `result/<id>` handle present |
| output ≠ comment | result renders on a `=>` line, never as `;;` |
| errors per kind | parse / compile / runtime each render `=> ✗` with the actionable message; no `result/<id>` |
| user threading | a woken-by turn renders `<user>` from the message content; no-woken-by turn omits it |
| success summary | `evals=N/M` matches the turn's ok / total |
| session cap | older `result/*` vars pruned past the cap; recent ones live |
| mimicry (live) | a driven agent replies with forms + `;;`, never tags / `=>` |

## Open / coordinate

- `ctx.cljs` is shared with the agent doing `<findings>`/`core-default-ctx`.
  Different fns, same file — confirm their state and sequence commits to avoid
  a mixed working tree.
- `result` ns-name collision check (above).
- Agent-woken-turn label (`<from agent=…>` shape) — finalize wording.
