# One derivation, joint design: five functions (revision 2)

Agent view with ctx-atom, for root's second review. It implements the owner's narrowing in `notes/one-derivation-2026-10-26.md` ("Narrowed to the implementation") and root's rulings on the first review (`notes/one-derivation-joint-design-review-2026-10-26.md`). It replaces revision 1's "the model reads a print of the page": the owner withdrew that ("html(ctx) will remove details that the agent needs"), and with it went revision 1's theme map in the text, render markers, wrapper elision, hiccup as a printer kind, the chat-part call form, `:changed` paths, `:seon.eval/replay?` and root's second ctx. Line numbers are main's at e622c0b1b.

## The chain
```
(ctx db e) --> map --> (view ctx) --> [{:key k :value v ...} ...] --+--> (html part) --> hiccup --> page   (Datastar patch by key)
                                                                    +--> (ai part)   --> text   --> model  (whole once, then diff)
                                          (diff prev next), by key, for both;  memo per part: [renderer value] identical?
```
- The page and the model get **the same parts in the same order**. They differ only in detail: the page may collapse a part, while the model's text carries what the agent needs within budget, and its cuts say how to read the rest.
- Both faces default to the one printer, `ops-hiccup` and `text` from one selection under one budget. A schema's `:seon.render/html` or `:seon.render/ai` overrides that face, for that value only.

## 1. `(ctx db e)` → map
- **Contract:** `[:=> [:cat :seon.host/db pos-int?] :map]`. It returns the entity e and its units, pulled once by the pattern its schema's `:seon.render/units` derives, plus its environment. After that it is patched per transaction, at the cost of the change.
- **Takes:** a db (an agent's branch, or main) and an entity. It is any entity, not only an agent, so every page (root, the user, a task, `/walk/<eid>`) is the same chain.
- **Is:** `seon.ctx/build` (ctx.clj:93) and `seon.ctx/patch`, which already exist: 6 contracted calls per change at any session size (`seon.ctx-test/the-patch-costs-the-change-not-the-session`), equal to a fresh build after 150 random changes. What's new is generalising `build`'s root from `:seon.agent/key` to any entity id (+20).
- **Order:** an entry's first transaction t, which the patch knows when the member appears. This is a derived index, never a stored vector. A small ordered value, such as tile order, may be a `[:vector]` key.
- **Sharing (ctx-atom):**
  - The patch changes only along each changed path, so every unchanged member stays `identical?`. Slice 1's proof checks this after random changes.
  - The root map is new on every transaction (`:t`, `:commit`), so nothing caches on the whole map.
  - A build (first hold, resume, clone, branch switch: ctx.clj:352, 356) makes all-new objects, so the memo misses once, everywhere. The cost proof says so.
- **Replaces:**
  - `seon.render/walk` (render.clj:272, 41 lines), `one` (230, 42), `relevant` (221, 9) and `forward` (216, 5): every page's pull;
  - `seon.context/records` (context.clj:557, 21): the conversation's read.

## 2. `(view ctx)` → ordered keyed parts
- **Contract:** `[:=> [:cat :map] [:vector [:map [:key :any] [:value :any] [:as {:optional true} :keyword]]]]`.
- **Is:** the one step that decides what is relevant and in what order. The layout is its output, as data. It is overridable like any render: a branch's schema entry for `:seon.ctx/ctx` names a different view fn.
- **Takes:** the map from (1). For an agent, by default:
  - `[:task]`: the task, its plan and what it cites;
  - `[:run]`;
  - `[:screen form]`: one part per form in `:seon.agent/screen`, its newest replayed value;
  - `[:conversation n]`: one part per eval, message and turn in t order, each its record;
  - `[:side]`: root's agents, on root's page and the sidebar.

  Root's view and a task's view are view fns of their own entity's schema, each a few lines.
- **Replay is explicit and already recorded** (root's ruling 3). `:seon.agent/screen` lists the replayed forms (screen.clj:11), and `:seon.eval/by "screen"` marks each eval they make (seon.facts.edn:123). The view puts a replayed form's newest eval in its `[:screen form]` part and leaves the rest in the conversation. **Nothing is added.**
- **Replaces:**
  - the agent page's tile gathering, `seon.render.ui/agent` (ui.clj:1012, 70);
  - `chat`'s split into list and tiles (ui.clj:962, 50), `rows` (804, 11), `shown-as` (783, 5) and `tile-id` (841, 9);
  - the request's assembly order: `request` (context.clj:601, 51), `run-message` (583, 8), `screen-message` (591, 10), `turn-input` (743, 12) and `messages` (652, 6);
  - `run-state` (280, 36) moves into the `[:task]` part's value; it is moved, not counted as deleted.

## 3. `(html part)` → hiccup
- **Contract:** `[:=> [:cat :map] :any]`. It returns the part's value through its schema's `:seon.render/html` if declared, else `emit/ops-hiccup` of the printer's selection under the part's budget (ruling 5). The same budget as the text face means a large value (an @ctx, a big map) is drawn to its cuts, never whole. Revision 1's 19.8k-token @ctx tile is this fix, not a special case.
- **Takes:** one part. Styling is the stylesheet's existing semantic classes (input.css `@layer components`, `.kicker`, `.seon-print-*`); there is no theme map.
- **The page** is a layout of parts' hiccup, sent by Datastar keyed by part key. A collapsed part, such as an eval line, is the same part with its details in a `<details>`.
- **Replaces:**
  - `component` (ui.clj:896, 39), `chat-part` (850, 46) and `page` (1132, 37), down to one eval-part render and one layout of parts;
  - `ctx`'s unbudgeted table (747, 36), drawn through the budgeted printer instead;
  - `tile` (935, 10) and `view` (788, 6).

## 4. `(ai part)` → text
- **Contract:** `[:=> [:cat :map] :string]`. It returns the part's value through its schema's `:seon.render/ai` if declared, else `print/text` of the same selection under the same budget, after a one-line header naming the part's key.
- **The model reads this,** never the hiccup (ruling 1). An eval part's ai face is the record's existing ai face, `seon.context/eval-messages` (context.clj:372, declared at seon.facts.edn:154); a message's is `message-messages` (405). These stay.
- **The task part's ai face is the story.** It stays the first part, so the first send is the stable prefix (the agent loop's invariant 2). The stage boundary is a diff of `[:task]` plus root's message as a conversation part, and the earlier stage's request stays a byte-exact prefix.
- **The debug toggle shows the parts' ai text** as each turn sent it. That is the request as recorded, a list of part keys and the turn's t, read back by `(ai part)` of the parts at that t.
- **Replaces:**
  - the debug page's own producer: `debug-input` (ui.clj:817, 24), `source` (1204, 14), `sent-parts-html` (1218, 25), `sent-request` (1243, 35), `debug` (1169, 25), `root-context` (1194, 10) and `debug-value` (proc.clj:226, 25), down to one page of parts' ai text (+30);
  - `parts-of` (context.clj:688, 23), `sent-split` (719, 14), `messages-tokens` (711, 8) and `story` (733, 10), down to a read of the recorded keys (+15);
  - the context's own render, `render` (755, 33);
  - root's `:ai` system face is root's view's parts, so the `render/ai` faces in `seon.render.ai` stay only where a value earns them (error, problem rows for MCP: server.clj:233, 258).

## 5. `(diff prev next)` by part key
- **Contract:** `[:=> [:cat [:vector :map] [:vector :map]] [:map [:changed [:vector :map]] [:added [:vector :map]] [:removed [:vector :any]]]]`, keyed by `:key`. One diff serves both faces: the page sends the changed parts' hiccup as Datastar patches, and the model gets the changed parts' ai text.
- **Rule 7, in the diff itself:**
  - A part counts as unchanged for the model only while its last whole send is still readable above. A part the history cut dropped, or folded to its line, is sent whole again.
  - The task's open reasons are always sent whole.
  - A seen-value reference `(:out result/eN)` is used only for a part still readable.
- **Where the screen's whole lives (the review's §2):** screen parts accrete as conversation entries, as today's screen evals do. The base of a diff is the newest whole send above, never a tail rewritten each request.
- **The memo is one per part:** `{key [f v out]}`, a hit when both the renderer value f (never its symbol, since a redefined fn re-renders) and the part value v are `identical?` (ruling 2). It is one pointer compare per part; there is no `:changed` set from the patch and no read-set index.
- **Replaces:**
  - `seon.screen/diff` (screen.clj:25, 23) and `seon.context/as-diff` (446, 8);
  - the render proc's read-set index: `comp-entry` (proc.clj:368, 22, its `d/with-reads`), `unindex`/`index`/`index-each`/`reindex`/`touched`/`db-key` (327–367, 41), `comps-touched`/`comps-of`/`drop-comps`/`invalidate`/`advance-main`/`hold-comps` (401–462, 55), `walk?` (319, 8) and `ready?` (291, 13).

## Where it runs: one render site (ruling 4)
- **The agent renders its own page.** Its ctx and its render fns are in its process already. Root's page for an agent asks it for the parts' html (the `:render` op from ctx slice 1, `ask-draws` proc.clj:390). A parked agent answers (part 3).
- **An agent with no process** is rendered by root from its record with main's defaults: the same `(ctx db e)` and `(view ctx)` on its branch, once, unpatched, with the structure render captioned "drawn with main's defaults". No second patched ctx lives in root.
- **Root's own page** is root's ctx, rendered by root.
- **The screen's per-turn round trip to root goes** (ruling 3; the review's −100): the agent evaluates its screen forms itself and renders their parts.
  - `seon.agent/screen` (agent.clj:516, 14);
  - the proc's `screen-view?` (539, 5), `drop-view` (544, 14), `screen-hiccup` (558, 22), `screen-of` (580, 9), `index-of` (589, 6) and `screen-due?` (595, 10);
  - `seon.screen/show` (58, 12), `last-screen` (48, 10) and `input` (70, 7);
  - the handlers at root.clj:399 and agent_proc.clj:222.

## Messages and the user (ctx-atom's side, root's rulings)
- **Root and the user are agent entities.** Each one's page is the chain over its own entity on main.
- **A message is a part in both ctxs.** `:seon.message/from` becomes a ref to the sender's agent entity; the store is erased, never migrated, at root's moment. The receiver's part appears when `seon.turn/take!` writes the message on its branch in the turn that took it, which keeps root's wake count (`:seon.run/taken`) honest.
- **The flow:**
  1. The owner's input becomes `/call` and then `message!`.
  2. A fact is written on main, in the user's ctx.
  3. The message reaches the agent's inbox.
  4. The agent's next turn takes it before the screen and writes it on its branch, where it becomes a conversation part.
  5. The agent's reply is written on its branch as a part.
  6. The agent renders the changed parts and root's page forwards them as Datastar patches.
- **Missing today:** the write in step 4, `:from` as a ref, and root's eval line on its own page (root-as-one-system's slice).

## Like-for-like tokens (ruling 7)
Measured from a1's recorded request on this worktree's root. Each screen form's text is the real printer's output as sent. Tokens are characters ÷ 3.2 (`:seon.config.ai/chars-per-token-prior`), the system's own estimate.

| part of the request | today | under the chain |
|---|---|---|
| system | 502 | 502, the same |
| the story / `[:task]` ai | 4,537 | 4,537, the same text |
| `(seon.screen/show)`: the page's hiccup printed for the model | 546 | **0**: the model no longer reads the page; its `[:run]` part is about 30 |
| `(my.task/status)` / `[:screen (my.task/status)]` | 101 | 101, the same print, with failures, done-rule reasons and turn count whole |
| `(my.branch/changes)` | 27 | 27 |
| `(my.task/related)` | 702 | 702 |
| **screen total** | **1,376** | **≈ 860** |

- **Nothing the agent needs is lost.** The model reads `my.task/status`'s own print; the page's proofs tile may collapse it (ui.clj:722 shows status and assert/got only). The owner's "html removes details" holds for the page alone, by design.
- **Not measured:** a model agent under the chain, meaning turns to green, cache share and tokens per turn. Today's model-agent numbers are screen 11,848 whole and 4,458 as diffs, and the cut's shared prefix at 93.7%. Slice 4 measures these on a real run before it merges.
- Revision 1's page-print numbers, from parsing HTML in Python, are withdrawn: the model does not read the page.

## What it deletes, and the src delta
Deletions are measured function sizes on main. Additions are estimates, ±50%.

| deleted | lines |
|---|---|
| every page's walk: `walk`, `one`, `relevant`, `forward` (render.clj) | −97 |
| the read-set index, `ready?`, `walk?` (proc.clj) | −139 |
| `page-value` (proc.clj:251, 40) and `debug-value` (226, 25) | −65 |
| the screen's round trip (agent.clj 14, proc.clj 66, screen.clj 29, the handlers at root.clj and agent_proc.clj 4) | −113 |
| `seon.screen/diff`, `seon.context/as-diff` | −31 |
| the request's assembly (`request`, `run-message`, `screen-message`, `turn-input`, `messages`, `records`, `render`) | −141 |
| the debug read-back (`parts-of`, `sent-split`, `messages-tokens`, `story`) | −55 |
| walk-driven layouts and the debug page (ui.clj: `agent`, `chat`, `rows`, `shown-as`, `tile-id`, `component`, `chat-part`, `page`, `tile`, `view`, `root`, `debug`, `debug-input`, `source`, `sent-parts-html`, `sent-request`, `root-context`) | −443 |
| **deleted** | **≈ −1,084** |

| added | lines |
|---|---|
| `ctx` over any entity | +20 |
| `view`: the agent's, root's, a task's and the default | +130 |
| `html`/`ai` part, with the budget on the html face | +40 |
| `diff` with rule 7, and the per-part memo | +60 |
| a page as a layout of parts; one eval-part render; the debug page as parts' ai | +140 |
| the request as the parts' ai, whole then diffs, with keys recorded per turn | +55 |
| the agent renders its own parts; root forwards them; a gone agent with defaults | +60 |
| `take!` writes the message; `:from` as a ref | +15 |
| **added** | **≈ +520** |

**Net ≈ −560, within −300 to −820 given the additions' error.**
- The review's −50 to −150 was for agent pages only, with revision 1's hiccup-print machinery added and the screen round trip left in.
- This revision moves every page off the walk (slice 5), drops that machinery, and counts the round trip.
- The `:ai` faces in `seon.render.ai` are kept (ruling 1), so nothing is claimed there.

## The agents' help text
> Your page is `(view @ctx)`: keyed parts, in order. Each part has two faces, html for the user and ai for you, and both default to the one printer, so any value reads well with no render. To change how a key looks on the page, register its schema with `{:seon.render/html f}` (f: value → hiccup); to change what you read, `{:seon.render/ai f}` (value → text); the layout is `:seon.ctx/ctx`'s view. Forms in `:seon.agent/screen` re-run every turn; every other eval shows what it answered. You get each part whole once, then only the parts that changed; a part folded away is sent whole again.

**Its proof:** a fresh agent `swap!`s a key no schema names (a vector of maps). It reads a table in its text and sees one on the page. It then registers an html render for that key and sees it on the page at once, with its own text unchanged.

## Slices (each red then green, measured; owners per root's note)
1. **ctx-atom:** `(ctx db e)` over any entity; the sharing proof (every path not changed stays `identical?`); order by first t.
2. **agent view:** `(view ctx)` for an agent and `(html part)`/`(ai part)` with the budgeted html face; the agent page as a layout of parts, rendered in the agent and forwarded by root; the per-part memo. Browser-checked. Cost proof: a new eval renders one part.
3. **related-sections:** `(diff prev next)` with rule 7, used by the page and by the request's screen parts. Delete `seon.screen/diff` and `as-diff`.
4. **agent view + related-sections:** the request as parts' ai, whole once then diffs; the screen round trip deleted; the debug page as parts' ai. Measured on a real model run (turns to green, cache share, tokens per turn) before merge.
5. **agent view:** every other page (root's, the user's, a task's, `/walk/<eid>`) as the chain over its entity. Delete the walk, the read-set index and the walk layouts.
6. **agent loop:** `take!` writes the message; `:from` as a ref (the store erase, root's moment); the stage boundary as a conversation part.
7. **root-as-one-system:** root and the user as agent entities; root's eval line.
