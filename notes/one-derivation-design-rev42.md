# One derivation, revision 4.2: two outputs, and the model reads the page

Agent view, for root. It applies the owner's two rulings of 2026-10-26 on top of revision 4.1 (`notes/one-derivation-design-rev4.md`), replacing it where they disagree, with the independent review folded in (`notes/one-derivation-design-rev42-review.md`: approve with changes; its five changes applied below). The rulings:
- "no we don't store derived data in the db or the ctx atom";
- "there are only two outputs too: @ctx returns data just like any repl eval and then we need to show the UI and the diffs to the ctx and the html at every turn as formal data".

## 1. Two outputs
1. **An eval's answer.** Any eval, `@ctx` included, is answered by the REPL with its value, printed by the one printer, as its tool message.
2. **The UI as formal data, every turn.** The first turn of a session (or a resume) sends the page whole. Each later turn sends the page's diff (each part whose hiccup changed, by key) and ctx's diff (paths and values), split by §4's rule so each change is told once.

**ctx's diff (root's ruling, 2026-10-26; ctx-atom builds it in seon.ctx):**
- **A pure function, `(diff ctx0 ctx1)`.** ctx0 and ctx1 are the agent's record as of the turn's start t and its end t, each a build or patch of the db as of that t. Nothing accumulates in ctx.
- **Where it compares.** The union of the `:changed` paths of the transactions in (t0, t1] bounds it. Those paths come from the db too: patch ctx0 by each of those transactions' datoms, from history, and union their `:changed`. So 4b's re-derivation of a recorded turn gets the same bytes. A path whose value is equal at both ends is dropped.
- **Shape:** `{path [old new]}` in ctx's own shape. A member that went is `[old nil]`, and a new one is `[nil new]`.
- **Cost proof:** calls linear in the changed paths, never in ctx.

## 2. The model's page: the same hiccup, exactly as each render returned it
The owner, after approval, 2026-10-26: "don't leave anything out I want the agent to understand problems with the rendering functions"; "Keep things simple so we can easily modify the system".
- **No projection, no stripping, no theme map.** The model reads each part's hiccup exactly as its render returned it: classes, `data-*` and `on*` attributes, wrappers. A render's bug is visible in what the agent reads, and the fix for a large page is a more compact render, which the agent can see and make. Today's renders return their utility classes themselves (no expansion at HTML emit), so there is no theme map to send.
- **Drawn eager, with no per-tab state.** The model's draw opens every fold. The user's page is the same hiccup.
- **Measured** (a1, live, 2026-10-26): the page as returned, without the conversation, is 4,501 tokens (task 3,052, related 822, proofs 314, verdict 206, run 107), against 5,226 for that agent's request today. Nothing is stripped.

## 3. Renders memoised by value; order computed in view
- **`(view c db)`:** the record and the agent's branch db as of its t. The conversation's order is each member's first transaction, `seon.context/first-t` (jolt/ctx-atom), one seek per member. It is not memoised: the review cut the cache as more mechanism than the lookup it saves.
- **The cited vars as their own sub-parts** (root, 2026-10-26): `task-page` draws each var the plan cites as its own part, `[:task :cites sym]`, so a cited edit's page diff is that var's part alone, not the whole task data.
- **The task part's render, `seon.render.ui/task-page`.** It is task-data (context.clj:280) over the task and the db as of t, drawn as hiccup with what the task cites, what to prove and its write set. It lives in seon.render.ui, which already requires seon.context (ui.clj:19). Declared on `:seon.task/task`, it replaces `task-box` (ui.clj:357) wherever a task is drawn.
- **The related part's render, `seon.render.ui/related`:** over the plan's vectors.
- **One memo, by value:** `{part-key [fns value read-t hiccup]}`, a hit when the fns and the value are identical and `read-t` is the same.
  - `read-t` is the t of the newest transaction for which `seon.context/task data-reads?` is true, for the task and related parts. It is nil for the others.
  - Not tracked (ctx-atom's docstring): files on disk, namespace metadata, config. A change to one of them shows as drift by digest (4.1 §1), never as a stale byte inside a session.

## 4. The wire: each change told once
| role | carries |
|---|---|
| system | the rules, pinned per session |
| user, first | the page, whole: its parts' hiccup as returned, in view order, without the conversation |
| assistant | the agent's prose and eval calls |
| tool | the eval's answer: value, out, err, error and cost |
| user, each later turn | `[from <sender>]` each message taken (4.1 §5's entries); `[page]` each non-conversation part whose hiccup changed, by key; `[ctx]` each changed ctx path no part on the page draws |

**The rule, so a change is told once:**
- **The model's page leaves out the conversation parts:** the agent's evals, its turns and its messages. Its own transcript already carries them, as assistant calls, tool answers and `[from]` entries. That removes the review's triple telling of an eval and needs no reference.
- **What the agent's own eval caused** is in its tool answer, and is not told again in `[page]` or `[ctx]`. 4.1's "less what its own evals caused", by the transaction's `:seon.tx/eval`, is kept.
- **Anything else** is in `[page]` when a part draws it, and in `[ctx]` only when no part draws that path.

## 5. What this deletes, and what replaces it
| deleted | callers today | replaced by |
|---|---|---|
| `:seon.render/ai` on 8 schema keys (render, error, facts, problems, task) | the request's conversation picks its render through it (context.clj:401-407) | the request's producers, called directly: `eval-messages` (context.clj:361), `message-messages` (394) |
| `seon.render/ai`, `print-ai`, `rendered?`, `printed` | root's context (render/proc.clj:642, feeding `/context.txt`, root's debug tile and two docs pages); the stream's web-call line (proc.clj:748); MCP error text (server.clj:233, 258) | root's context: root's page, its parts' projected hiccup, with the `-data` functions (`system-data` and its kin) kept as part values. The stream line and MCP errors: the printer, with errors message-first at their producer (`seon.contract/error`) |
| `seon.render.ai`'s hand-written faces (render/ai.clj, 222 lines), less the `-data` functions | the above | the `-data` functions as part values |
| `:seon.render.config/root-context-budget` | root's context | the part's budget |
| `part-faces`' text face and the `:page`/`:value` split | view, repl/parts, proc | one face, the hiccup as returned |
| ctx's derived members, `:born`, `order` | (done on jolt/ctx-atom) | `first-t`, renders over the db |
| 14 test references to `:ai` faces | tests | rewritten with their slices |

## 6. Measure first (the review's order; live-agents before and after)
1. **a1's parts printed as projected hiccup by the real printer**, against today's starting request (system + task data + screen: 6,043 tokens past the system prompt).
2. **One recorded a-map turn's world message** (`[page]` + `[ctx]`), with and without the agent's own parts, against today's 25,979 tokens a turn.
3. **How often `task data-reads?` fires on the agent's own `defn`s** (each fire redraws the task part).

## 7. The prefix proof
`seon.context-test/the-prefix-stays-byte-identical-across-turns` holds: system, the page at t0, then per turn the calls, their answers and the world message. Within a session each request is a byte prefix of the next, except at the history cut's jumps; across a process, drift is detected by digest (4.1 §1).

## 8. Split with ctx-atom
- **ctx-atom (done on jolt/ctx-atom):** derived members, `:born` and `order` deleted; `first-t` and `task data-reads?` added.
- **Agent view, on a branch built on jolt/ctx-atom, merged with it:**
  - `(view c db)`;
  - `task-page` and `related` in seon.render.ui;
  - the memo with `read-t`;
  - `my.task/related` and the tests ctx-atom left red;
  - then §5's deletions and the world message (§4), with related-sections for the request's assembly (4b).

## 9. Settled after approval (2026-10-26)
- No projection and no theme map (the owner, above).
- Cited vars as sub-parts (root).
- The conversation parts are told by the transcript, once.
