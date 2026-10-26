# Rendering: one system of composed functions (design, 2026-10-26, as agreed with the owner)

This replaces every earlier revision. The owner's rulings of 2026-10-26 (docs/decisions.md): one rendering system; the data describes the page; a render is a function and the data it renders; Reagent's rule for what redraws, taking advantage of immutability; the pull pattern is explicit, stored in ctx and editable by the agent; nothing derived is stored; never print thousands of items; use Clojure and library words. Its evidence: the field's prior art (notes/research-reactive-render-2026-10-26.md), an independent design from our own code (notes/design-second-opinion-render-2026-10-26.md), and ctx-atom's measurements on a live agent (render-workload-a1-2026-10-26.md and the pattern-driven patch below).

## The pieces
1. **The pull pattern is data in ctx** (`:seon.ctx/pattern`): a Datahike pull pattern from the agent's entity. The agent `swap!`s it for more or less.
2. **`pull` and its update.** `(pull db eid pattern)` gives the pulled tree plus an index from eid to the paths where that entity sits. After a transaction, the update re-pulls only the entities the datoms touched and copies the path up to the root, so every untouched subtree stays `identical?`. It is proved equal to a fresh pull by a generative test, and its cost grows with the transaction, not with ctx.
3. **Newest-first windows.** An opt-in Datahike pull option (`:newest n`), owner-approved as a safe addition: a reverse seek on the index that stops after n, with a count. Older items get a cut naming the form that reads them.
4. **ctx's schema is generated from the pattern.** It is the entity schemas (agent, run, eval, task, var …), which mirror the Datahike attributes and refs, composed along the pattern's joins. It is never written by hand, so it cannot drift from the pattern. When the agent edits the pattern, the schema follows.
5. **`render-ctx` is a plain function registered as ctx's schema's render** (`:seon.render/html`). It returns hiccup holding ctx's values. An agent overrides it, or any render, by registering its own on its branch.
6. **A render is `(fn [opts & children] hiccup)`.** opts is a map with fully qualified keys, declared in the render's contract. A render that needs the db declares `:seon.db/db`; one that needs the whole ctx declares `:seon.ctx/ctx`. Most declare neither and draw their children.
7. **The walk** draws the hiccup tree:
   - `[f opts & children]` (Reagent's form) calls `f`. For each key `f` declares that the call's opts lack, it fills in the top-level `:seon.db/db` or `:seon.ctx/ctx` handed to `render-ctx`. Explicit opts win.
   - Plain data in the tree is drawn by its schema's render, else the printer.
   - The page writer and the model's text both take this one tree, so the page and the model read the same hiccup.
8. **One cache, keyed by what each render declares:**
   - declares nothing: cached by its children's identity (pure Reagent);
   - declares `:seon.db/db`: also keeps the db ranges it read (`d/with-reads`) and recomputes only when a transaction touches them;
   - declares `:seon.ctx/ctx`: keyed on ctx's identity, so it redraws on every change. The cost is visible in its declaration; take a slice as children where possible.
9. **`diff`.** One walk that skips `identical?` subtrees: ctx's diff and the page's diff. The model's turn message is the two diffs; the page patches with the second.

The model's request: system; the page emitted at the session's first turn; then per turn the evals (assistant), their answers (tool) and the turn's two diffs (user). It renders nothing itself.

Things that exist only when rendered, such as the functions a plan cites or the related sections from an embedding search, are computed by renders that declare `:seon.db/db`. They are never stored, and their db reads are what invalidates them.

## How exactly we know what changed
A transaction's datoms → the eid → paths index → only those entities re-pulled, paths copied → every other subtree `identical?` → the cache misses only for calls whose children sit on a changed path, or whose recorded db reads the datoms touch → those redraw → the diff walks only new objects.

## Measured on a1 (ctx-atom)
| change | entities rebuilt | patch | render calls reused by identity |
|---|---|---|---|
| new eval | 2 | 0.23 ms | 19 of 22 |
| cited function redefined | 8 | 2.3 ms | 40 of 61 |
| new caller | 2 | 0.36 ms | 19 of 22 |
| plan edit | 5 | 0.41 ms | 22 of 31 |
| fresh build, for comparison | 102 | 28–35 ms | 0 of 191 |

`identical?` 1 µs; `=` on 1 MB 0.2 ms. Today the model's request rebuilds ctx and draws the task twice every turn (about 280 ms and 300 MB, whatever changed), and the task is held under each of the agent's runs (3 times on a1). Both go in this design.

## What is deleted
- The parts layer (`render-ctx` as parts, `part-faces`, `part-html`, `drawn`, the key cases).
- The extra caches beside the one: `render/drawn`; root's component cache, whose `d/with-reads` becomes the cache's db side; `step-units`' compares; and `context/printed-run`.
- The hand-built keys (`task-key`, `cited-key`, `related-key`, `first-t` per comparison).
- `context/world`'s rebuild and double render, and `context/records` re-reading the conversation.
- The schemas' units and parts properties, the second layout, and `seon.render.system` (root's page is the same walk over root's ctx).

## Before anything is built
The owner asked to "investigate and see if that works for everything": a REPL prototype on a live agent that draws every page we have today (the agent page, root's page, the debug page and the model's request) through these pieces, with its work measured. Every place the design does not fit is reported, not worked around.
