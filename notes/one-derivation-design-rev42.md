# One derivation, revision 4.2: two outputs, and the model reads the page

Agent view, for an independent review and then root (AGENTS §2). It applies the owner's two rulings of 2026-10-26 on top of revision 4.1 (`notes/one-derivation-design-rev4.md`), and it replaces 4.1 where they disagree:
- "no we don't store derived data in the db or the ctx atom";
- "there are only two outputs too: @ctx returns data just like any repl eval and then we need to show the UI and the diffs to the ctx and the html at every turn as formal data".

## 1. Two outputs
1. **An eval's answer.** Any eval, `@ctx` included, is answered by the REPL with its value, printed by the one printer, as a tool message. There is no other face of a value.
2. **The UI as formal data, every turn.**
   - The first turn of a session (or a resume) sends the whole page as hiccup.
   - Each later turn sends two diffs:
     - **ctx's diff:** `seon.ctx/patch`'s `:changed` paths and their values since the agent's last turn;
     - **the page's diff:** each part whose hiccup changed, by part key, with its new hiccup; parts added and removed by key.
   - Both are data, printed by the one printer.

**The model reads the same hiccup the user sees.** The page and the model are no longer two faces of a part: the part's hiccup is drawn as HTML for the user and printed as data for the model. A detail the page folds (an eval's whole response under its one line) is still present in the hiccup data, inside its `<details>`, so the model has it.

## 2. What this deletes
| deleted | where | why |
|---|---|---|
| `:seon.render/ai` on every schema, and `seon.render/ai`, `print-ai` | schemas (9 keys: render, error, facts, problems, task), render.clj | the model reads hiccup; a value's text is the printer's |
| `seon.render.ai` (the hand-written `:ai` faces: system, problems, task, run, error, sections) | render/ai.clj, 222 lines | root's context becomes root's page as hiccup |
| `part-faces`' text face and the `:page`/`:value` split | render.clj | one face, the hiccup |
| ctx's derived members, `:born`, `order` | seon.ctx (ctx-atom, done on jolt/ctx-atom) | no derived data in ctx |
| the screen (already gone), "Since your last turn" (4.1 §5) | context.clj | the two diffs above are the world message |

What stays as functions, not faces:
- `seon.context/eval-messages` (context.clj:361) and `message-messages` (394): what the tool message and a person's message are. They are the request's producers, no longer declared as `:seon.render/ai`.
- The MCP server's error text (server.clj): it prints errors with the printer.

## 3. Renders, memoised by value; order computed in view
- **`(view c db)`: the record and the agent's branch db as of its t.** The conversation is ordered by each member's first transaction, `(seon.context/first-t db e)` (ctx-atom's, one seek, never changes). view memoises it by entity id in the memo below.
- **The task part.** Its value is the task. Its schema's declared render is `seon.context/task-page`: run-state (context.clj:280) over the task and the db as of t, drawn as hiccup with what it cites, what to prove and its write set.
  - The render is in seon.context. render.clj resolves a declared render by symbol at draw time (render.clj:150), so `seon.render` → `seon.context` is no require cycle.
  - The parts the page folds (a cited var's source, a long plan) are inside `<details>`, present in the data.
- **The related part.** Its render is `seon.context/related` over the plan's vectors, drawn as hiccup.
- **One memo, by value.** `{part-key [fns value db-key hiccup]}`, a hit when the fns and value are identical and the db key is equal.
  - The db key is ctx-atom's tracking: a story or related part keeps its hiccup while every transaction since it was drawn answers `(seon.context/story-reads? datoms)` false.
  - Any other part's db key is nil: its render reads only its value.

## 4. The wire, revised
| role | carries |
|---|---|
| system | the rules, pinned per session |
| user, first | the whole page as hiccup data, plus the theme map once (§5) |
| assistant | the agent's prose and eval calls |
| tool | the eval's answer: value, out, err, error and cost, the REPL's, printed |
| user, each later turn | `[from <sender>]` each message taken (a person, root's stage, a red verdict; 4.1 §5 entries); `[ctx]` ctx's diff, paths and values; `[page]` the parts whose hiccup changed, by key, with their new hiccup |

- **Who caused a change decides where it is told, once.** The agent's own eval's value is in its tool answer. The page diff carries the parts it changed, but for the agent's own eval's conversation part it sends the key and the handle its tool answer gave, `[:eval "e14"] result/e14`.
  - That is a reference to text the model read in this same request, so it is still readable (rule 7), never a dangling fold.
  - Without it, every eval's whole response would be sent twice, once as its answer and once inside its part's `<details>`. **This is the one point to measure (§6) and for the owner to rule on.**

## 5. Hiccup tokens: a compact vocabulary
The model now reads hiccup, so its size is the cost to watch.
- **Semantic classes in hiccup shorthand**, `[:section.part …]`, `[:span.badge.fail …]`. There are no utility classes in a render.
- **The theme map** (class → Tailwind utilities, `resources/public/css/input.css` `@layer components`) is sent once with the first page, so an agent that edits a render knows what each class draws. It is sent again only when it changes.
- **The vocabulary of revision 4.1 §4**: part, reference, references, badge, code, prose, change, ripple. A render returns those elements and values. The printer prints the hiccup compactly (one line per short element, values by their own structure under the part's budget).
- **No layout-only wrappers in a render.** The page's grid is the layout's, which is chrome, and the model gets the parts' hiccup in view order, not the layout's.

## 6. Measurements (slice by slice, live-agents before and after)
- **The first page's hiccup, printed, against today's starting page** (system + story + screen). Revision 1 measured a1's page at about 5.2k tokens without @ctx. That was a Python estimate, withdrawn; the slice measures it with the real printer.
- **A turn's world message** (ctx diff + page diff) against today's, on the recorded a-map run's 29 turns.
- **With and without §4's reference** for the agent's own eval parts: the duplicate's cost.
- **Turns to green and cache share** on a live run, by live-agents, before merge.

## 7. The prefix proof
`seon.context-test/the-prefix-stays-byte-identical-across-turns` holds. The request is system, the page as hiccup at t0, then for each turn the assistant's calls, their tool answers, and the world message of its diffs. Each term is read as of its t, so within a session each request is a byte prefix of the next, except at the history cut's jumps (4.1 §1). Drift across a process is detected by each term's digest and its input digests (4.1).

## 8. Split with ctx-atom
- **ctx-atom (done, on jolt/ctx-atom, 2 commits past main):** the derived members, `:born` and `order` deleted; `first-t` and `story-reads?` added.
- **Agent view, on a branch built on jolt/ctx-atom, merged with it:**
  - `(view c db)`;
  - the task and related parts as declared renders in seon.context;
  - the one memo;
  - `my.task/related` and the tests ctx-atom's branch left red;
  - then the deletions of §2 and the two-diff world message, with related-sections for the request's assembly (4b).

## 9. Open, for root and the owner
- **(a)** §4's reference for the agent's own eval parts. Without it the page diff resends each eval's whole response. Recommendation: keep the reference, and measure both.
- **(b)** The theme map is sent once per session. This brings back revision 1's theme map, which revision 2 dropped on root's ruling (styling as the stylesheet's classes). Now that the model reads hiccup, the map is what explains the classes. Root rules.
