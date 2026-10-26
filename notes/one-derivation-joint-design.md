# One derivation, joint design: the page and the model's request as one render of @ctx

Agent view (render, layout, the printer and request side) with ctx-atom (the ctx side, `ctx-side-of-webui-design-2026-10-26.md`, folded in below). It answers root's reference (`one-derivation-2026-10-26.md`), its eight points and its deletion list, under the owner's rulings of 2026-10-26. Status: for root's check and an independent review before anything is built.

## The core: the page is html(ctx); the model's context is print(html(ctx))
The owner, 2026-10-26: "I feel like we are close to one simple design without all the manual scaffolding and rules we are setting up for the webui and the context generation."

```
@ctx  --:seon.render/html-->  hiccup  ---> HTML (the theme expanded)            = the page the user sees
                                      `--> the one printer (budget, cuts, folds) = the model's context
```
- **One generation.** `@ctx` is rendered once, by its schema's `:seon.render/html` (the layout), into one hiccup tree. That tree is the page. The model's context is the one printer applied to the same tree: whole once, then diffs by component key, with the printer's budget, cuts, folds and the history cut acting on that one tree. Model and user read the same thing.
- **The printer's rules are the only context rules,** and they apply to any tree:
  - what a cut leaves and the form that reads it;
  - folds (an earlier part as its line);
  - `:same-as` and seen-value references, only to what is still readable above;
  - the history cut, which jumps once and holds;
  - diffs by component key, never "unchanged" for what was cut.
  There is no rule specific to the request, the screen or the story.
- **`:seon.render/ai` is a rare escape hatch,** for a value whose model view should differ from its page (a vector as its length and first rows, a blob as a one-line summary). It is not a second generation: the printer meets it as a node in the tree and prints its text there.
- **What that deletes, measured by the size of each function that goes** (the section "What it deletes" below has the list):
  - the story block: the task is a tile;
  - the screen's own producer: the screen is the tile side;
  - the records read for the conversation: the chat side;
  - the separate debug page: what was sent is the printed page, recorded per turn;
  - the hand-written `:ai` renders (`seon.render.ai`, root's system face included: root's context is print(html(root's ctx)));
  - the parts of `seon.context` that assemble the request;
  - the page walk and its read-set index.
  - Total: **≈1,414 src lines go, ≈450 come, net ≈ −960.**
- **What remains of the context machinery** is `seon.context/kept` (the history cut), the system prompt (help), the data producers that agents and tiles call (`refused`, `near`, `related`, `similar`), and `printed-run`'s rules, moved into the printer as its rules over a conversation's nodes.
- **The prefix:** the first print of the page (the task tile at its top) takes the story's place as the stable prefix. A stage boundary (a new task) is a diff of the task tile plus root's message as a chat entry, so the earlier stage's request remains a byte-exact prefix (the agent loop's invariant 2).

## The shape (root's eight points, each with how)

**1. Pull once.**
- ctx is one pull per agent, `seon.ctx/build` on its branch, with the pattern derived from the schemas' `:seon.render/units`.
- Each transaction patches it with `seon.ctx/patch`: 6 contracted calls per change at any session size (`seon.ctx-test/the-patch-costs-the-change-not-the-session`).
- The one addition is that the patch also answers `:changed #{path}` and each new member's t.
- Order comes from facts: each entry's first transaction (its t), which the patch knows when the member appears and a build gets by one seek of its identity datom.
- The conversation is the members sorted by t once; each patch then appends at the end, because a new entity's t is the newest. There is no stored vector (root: an append to a `[:vector]` rewrites it, quadratic). A small ordered value, such as tile order, may be a `[:vector]` key in ctx.
- Where it runs: the agent's own @ctx lives in its process. Root keeps a ctx per **viewed** agent page, built by the same `build` on the branch and patched by the same `patch` from the writer's reports. It is dropped when no tab views it, never kept for every agent. That is the same two functions in two processes, equal at each t (the patch's property test run on root's side), so a parked or gone agent's page still renders.

**2. Render once, by schema.**
- One function, `(render v)`: the `:seon.render/html` its schema declares, else the one printer's hiccup (`emit/ops-hiccup`: maps as grids, same-keyed maps as tables, code as code, prose as prose). A render calls `render` on its children.
- The page is `(render ctx)`. Layout is the render `:seon.ctx/ctx` declares (root's ruling: no layout attribute). It is overridden like any render, by a schema entry on the branch, seen at once (slice 1's `branch-render` and `{:op :render}`).
- An @ctx eval in the conversation shows collapsed: the page already is it. Measured on a1 below, a whole @ctx drawn as a tile is 19.8k of the page's 25k tokens.
- **Not everything is shown.** The layout chooses what is relevant, and the rest is one form away, named by a cut (owner: "not rendering data we don't think is particularly relevant is helpful").

**3. Two outputs of one hiccup.**
- HTML for the user: the theme map is expanded into Tailwind utilities at the HTML render.
- The printer's compact print for the model:
  - Semantic class names in hiccup shorthand, such as `[:section.tile …]` and `[:span.badge.red …]`. The theme map (class → utilities) is shown once with the whole hiccup, and again only when it changes.
  - Each component is marked with the render function that made it and its key, printed compactly as `{:render seon.render.ui/tile :key [:proofs]}`, so an agent knows what to edit: the render fn or the theme entry.
  - The owner's correction applies: "compress, don't remove". Nothing about how the tree is styled is hidden; it is named once.
  - Layout-only wrappers that carry no class, text or key are elided. Values the printer prints well (code, prose, tables) stay values, not re-wrapped markup.
  - Cuts say what they left and the form that reads it.
- **A component whose input the model already reads prints as its render call.** The chat side's parts are renders of eval records that the conversation already sends whole, so the model's print of the chat side is the conversation itself (as today), never its markup a second time. This is the largest saving measured: a chat part is 112 tokens as markup, against about 10 as `[:chat-part {:key "c-1665"} result/e42]` beside the record already sent.
- The model gets the whole page once (at a session's start, and after a cut), then diffs by component key.

**4. Replay is explicit (owner: "an explicit thing not an inferred").**
- Every eval's tile shows its current value. Whether its form is re-executed each turn or its recorded value is pulled is one attribute, written when the form is set up: `:seon.eval/replay?`, true on the screen's forms (`:seon.agent/screen`: `(my.task/status)`, `(my.branch/changes)`, `(my.task/related)`), declared by the form, never inferred from the value.
- A replayed form's tile shows the newest execution's value. Any other eval's tile shows its record's value.
- This **deletes my record-versus-computed rule**, `seon.render.ui/live-key` on branch webui-root-chat, which is not merged. That branch keeps the toggle, root's context page and the sidebar.
- "As recorded at turn n" stays one click away because the record never changes.

**5. Cache by value.**
- The component key is the ctx path plus its render fn.
- The patch's `:changed` paths invalidate along the path only, root to leaf. Every other path's value is `identical?` to the cached one, because the patch keeps unchanged subtrees shared, so it is never rendered again and never compared by content.
- On jolt/one-content-keyed-derivation: its `encode` (canonical EDN, full SHA-256) is the right key for reuse **across** processes and boots. It is the wrong key for this in-process cache, because encoding costs the value's size on every lookup, where `identical?` on a shared subtree costs O(1).
- The memo stays a plain in-process map keyed by path, cleared with its ctx when the page is unviewed, with no db facts. The content key is left for a cross-process need, such as rendering for a parked agent from another process; none exists today.
- This replaces the render proc's read-set index (`d/with-reads`, `comps-touched`, `:comp-index`) for ctx's part.

**6. The two sides.**
- **The screen side**, live as the agent works:
  - the task, its proofs and open reasons, and the run;
  - a tile per eval whose value has a declared render or whose form is replayed (current value);
  - related sections as a tile, the same `(my.task/related)` value kept off the story (related-sections: the story's pulls made designs worse, 8/15 → 4/15).
  - The model's screen is the screen side's print.
- **The chat side:** prose (the model's and the user's), messages, and each eval collapsed to its one line (code ⇒ value · ms), expandable to the whole.
- Both are columns of `(render ctx)`'s layout; the sidebar is root's ctx's agents.

**7. Diffs never hide what matters (the live agents' finding).**
- The live agents found the screen said `:unchanged [[:proofs] [:refused]]` after the full screen had fallen under the history cut, and the model looped.
- The rule: `:since eN` and `:unchanged` only for a part the model can still read in full above. Anything the cut dropped or folded to its line is sent whole again.
- The task's open reasons are always sent whole.
- The same holds for the printer's seen-value reference `(:out result/eN)`: a reference only to a part still readable whole, else the value itself (the mixed-collection run repeated a probe 8 times on `(:out result/e89)`).
- This is small and goes into `seon.screen/diff` and `seon.context/printed-run` now, as related-sections' slice.

**8. Caching is accretion.**
- Request order: system, the story (the task, once), `{:history {:left-out N}}` right after the story, the turns, then the screen last. Everything before the screen is a stable prefix.
- The history cut jumps once to half the budget when it fills, then holds. Measured: shared prefix 22.5% → 93.7%, cache breaks 23 → 1, tokens per turn 35.1k → 27.3k.
- Nothing already sent is rewritten. A part shown whole stays whole until the next jump. The stage boundary (root's message at `:briefed`) is a conversation entry after the earlier stages' entries, changing none of them.
- `:same-as` stays an attribute of the entry's render, not a run counter (`seon.context/printed-run`).
- With ctx: turn n's diff is the members whose paths are in `:changed` since turn n-1's t. New members are appended to the conversation; changed screen components are replaced by their key.

## Messages, the user agent and root (ctx-atom's side, with root's rulings)
- **Root and the user are agent entities.** Each one's page is the render of its ctx on main, so the same layout serves root, the user and every agent.
- **A message is an entry in both ctxs.** `:seon.message/from` becomes a ref to the sender's agent entity. This is a schema change, so the store is erased, never migrated, at a quiet moment root picks, told first.
- The sender's ctx holds what it sent through the unit `:seon.message/_from`. The receiver's ctx holds it when its turn **takes** it: `seon.turn/take!` writes the same message entity, upserted by `:seon.message/id`, on the agent's branch, in the turn that took it. Writing it at take time rather than at send time keeps the conversation and root's wake count (`:seon.run/taken`) in agreement (the agent loop's invariant 1).

**The flow, end to end:**
1. The owner types in an agent's chat input.
2. `/call` reaches `seon.root/message!`.
3. A message fact on main (from: the user's agent entity, to: the run) appears in the user's ctx through its patch.
4. It enters the agent's inbox.
5. The agent's next turn takes it before the screen is evaluated (`take!` before `screen!`, as today) and writes it on the branch.
6. The agent's ctx patch appends it to the conversation by its t.
7. The agent's reply (a turn's prose, an eval) is written on the branch, and its ctx patch appends it.
8. Root's viewed ctx of that agent is patched from the writer's report. The changed components are re-rendered and sent as a Datastar patch to every tab on that page.
9. A reply addressed to the user (`my.agent/send-message`) is a message fact whose `:from` is the agent, so it appears in the user's ctx the same way.

**Missing today:**
- step 5's write on the branch;
- `:from` as a ref;
- root's per-viewed ctx;
- an eval line on root's own page: root's REPL is the server's `root-eval`. Once root is an agent entity, its input is the same `/call` → submit path as an agent's (the root-as-one-system slice).

## Root's debug toggle, kept
The toggle is "conversation | context" on every page. The context view is the request as recorded: each turn's sent parts, for a model agent; for root, which has no model turn, the context the render proc already holds. Both are built on webui-root-chat, and it stays as the view of the model's face.

## What it deletes, and the src estimate
Sizes are each function's line count on main today (`notes/measure/defsizes.py`). What goes is my reading of the design, for review.

| deleted | functions (lines) | lines |
|---|---|---|
| `seon.context`, the request's assembly | `run-state` 36, `turn-state` 43, `current-state` 7, `story` 10, `eval-messages` 33, `message-messages` 7, `ai-messages` 8, `ref-of` 5, `fold-of` 16, `folded` 6, `as-diff` 8, `as-sent` 9, `records` 21, `run-keys` 5, `run-message` 8, `screen-message` 10, `request` 51, `messages` 6, `turn-input` 12, `render` 33 | −334 |
| `seon.context`, the debug page's read-back | `parts-of` 23, `sent-split` 14, `messages-tokens` 8 | −45 |
| `seon.screen`, the screen's producer and its own diff | `show` 12, `last-screen` 10, `input` 7, `diff` 23 | −52 |
| `seon.render`, the walk and the `:ai` face | `walk` 41, `one` 42, `relevant` 9, `forward` 5, `entity-schemas` 6, `identity-schemas` 4, `print-ai` 11, `ai` 5, `rendered?` 7 | −130 |
| `seon.render.ai`, the hand-written `:ai` renders (system, problems, task, run, error) | the whole namespace but one escape-hatch example | −200 |
| `seon.render.proc`, walk pages, the read-set index, the screen's own render, root's system value | `page-value` 40, `debug-value` 25, `ready?` 13, `walk?` 8, `ask-branches` 25, the index (`index`, `unindex`, `index-each`, `reindex`, `touched`, `db-key`, `comps-touched`, `comps-of`, `drop-comps`, `invalidate`) 73, the screen (`screen-hiccup`, `screen-of`, `screen-due?`, `screen-view?`, `index-of`) 52, root's system value (`system-value`, `main-info`, `task-rows`, `proc-health-of`, most of) 60 | −296 |
| `seon.render.ui`, walk-driven layouts and the debug page | `page` 37, `rows` 11, `tile-id` 9, `tile` 10, `shown-as` 5, `view` 6, `component` 39, `agent` 70, `root` 27, `runs-of` 10, `debug` 25, `debug-input` 24, `source` 14, `sent-parts-html` 25, `sent-request` 35, `root-context` 10 | −357 |
| **deleted** | | **≈ −1,414** |

| added | where | lines |
|---|---|---|
| `patch` answers `:changed` and each member's t | `seon.ctx` | +30 |
| root's ctx per viewed page, from the writer's reports; the path cache | `seon.render.proc` | +90 |
| `:seon.ctx/ctx`'s layout render (screen side, chat side, sidebar), the chat part read from a ctx entry | `seon.render.ui` | +150 |
| hiccup as a printer value kind: theme map once, render markers, wrapper elision, the component diff, `printed-run`'s rules as the printer's | `seon.print`, `emit` | +110 |
| the request: the system prompt, then print(html(ctx)) whole or as its diff, recorded per turn (what the debug toggle shows) | `seon.context` | +40 |
| `:seon.eval/replay?` on screen forms | `seon.screen`, schema | +15 |
| `take!` writes the message; `:from` as a ref | `seon.turn`, schema | +15 |
| **added** | | **≈ +450** |

**Net ≈ −960 src lines.** The deletions are measured function sizes; the additions are estimates. Each slice reports its measured delta. On main this also removes the toggle's separate debug page, which the webui-root-chat merge adds: the toggle stays and shows the printed page.

## Cost per transaction, against today
- **Today:**
  - The render proc walks each viewed page's entity under `d/with-reads` and indexes the reads.
  - A commit's datoms look up the components they touched, one lookup per datom key.
  - The screen's forms run each turn.
  - The request re-reads the run's records from the branch (`context/records`) and prints them.
- **After:**
  - The patch costs the change (6 calls a change).
  - Components on `:changed` paths re-render: the new eval's chat part, plus the screen tiles whose values changed.
  - The request appends the new members' prints and replaces the changed screen components.
  - Nothing walks or re-reads the session.
- The cost proofs carry over:
  - a new eval redraws one component (`seon.render-cost-test/a-transaction-renders-what-it-touched`, rekeyed to paths);
  - the patch costs the change (`seon.ctx-test/the-patch-costs-the-change-not-the-session`);
  - a turn's request costs its new parts (`seon.render-cost-test/a-new-turn-costs-one-turns-messages`).

## Measurements, one real agent (a1 on this worktree's root, attached outside, 2026-10-26)
a1 has 42 evals: the task story, `source`, `grep`, the task's status, a `def`, a map, `related`, `@ctx` and `(my.task/current)`. Tokens are estimated at 3.2 characters a token (`:seon.config.ai/chars-per-token-prior`). The script is `notes/measure/hiccup_measure.py`; it is a prototype measure, not the implementation.

**Today, what its model is sent (one context read):**
| part | tokens |
|---|---|
| system | ≈500 |
| the task story | ≈4,500 |
| the screen | ≈1,400 |
| whole request | 6,400 |

An outside agent's request carries no conversation. For a model agent's conversation, the recorded a-map run is 27.3k tokens a turn after the history-cut fix, with the screen at 4,458 as diffs (11,848 whole).

**The page:**
| print | tokens |
|---|---|
| the page's HTML | 29.8k |
| its hiccup with every attribute | 29.1k |
| the compact print (semantic classes, theme map 733 tokens for 50 classes, markers, no Datastar attributes, wrappers elided) | 25.0k |
| of which the @ctx tile drawn whole | 19.8k |
| **the compact print with @ctx collapsed** | **≈5.2k** |
| the screen side alone (tiles) | 2.1k + 0.15k theme |
| a chat part as markup | ≈112 each |
| a chat part as its render call beside a record already sent | ≈10 each |
| **a typical turn's diff** (one new eval: its record, as today, plus its call `[:chat-part {:key …} result/eN]`, plus changed screen tiles) | **≈ the record + 10 + the changed tiles** (one status tile ≈100–300) |

**So the rendered page printed for the model is small enough to send whole once, about 5k tokens, against today's 6.4k request, which already includes the story.** The chat side costs the model nothing new, because its markup is calls on records the conversation already holds.

## Mock: a1's page beside what its model reads (same turn)
The page as the user sees it (light theme, desktop):
- top bar: `◆ seon` and `a1 · [conversation] context`
- sidebar: `● root main`, `○ a1 idle`, `○ user`
- screen side (right):
  - task: "seon.render/a-render-defect-answers-the-pages-it-holds · work · running"
  - proofs: "1 proof: 1 pass"
  - run: "#3 · running · up to 40 turns"
  - related: 3 sections
- chat side (centre): each line is `(my.task/current) ⇒ #:seon.task{:execution :running …} · 0.84 ms`, expandable; the owner's message `> check the proofs` appears as a prose line.

What its model reads for the screen side, whole once:
```clojure
;; theme: {tile "rounded-md bg-base-900 p-2 min-w-0 overflow-x-auto", kicker "text-2xs uppercase tracking-wider text-text-500", …}
[:aside.tiles
 [:section.tile {:render seon.render.ui/task :key [:task]}
  [:h2.kicker "task"] [:p "seon.render/a-render-defect-answers-the-pages-it-holds · work · running"]]
 [:section.tile {:render seon.render.ui/proofs :key [:proofs]}
  [:h2.kicker "proofs"] {seon.render-test/a-view-that-throws-answers-its-page-with-the-error :pass}]
 [:section.tile {:render seon.render.ui/run :key [:run]} [:h2.kicker "run"] [:p "#3 · running · up to 40 turns"]]
 [:section.tile {:render seon.render.ui/tile :key [:related]} [:h2.kicker "related"] …3 sections, (my.task/related)]]
```
The chat side is the conversation, printed as today. The next turn's screen, after a proof went red, is just this:
```clojure
{:since result/e44 :changed {[:proofs] [:section.tile {:render seon.render.ui/proofs :key [:proofs]} [:h2.kicker "proofs"] {seon.render-test/… :fail}]}}
```
`:since` refers to a screen still readable above (point 7).

## The agents' help text (the acceptance test)
> Everything in `@ctx` is shown to you and the user the same way: your page, printed. Put data in with `swap!`. Any value reads well with no render; what the page shows is chosen by its layout, and the rest is one form away. To change how a key looks, register its schema with `{:seon.render/html your/fn}`, where your fn takes the value and answers hiccup. To change the layout, do the same for `:seon.ctx/ctx`. A form you want re-run every turn goes on your screen (`:seon.agent/screen`); every other eval shows what it answered.

**The proof of the acceptance test:** a fresh agent `swap!`s a key no schema names (a vector of maps) and gets a readable table on the page and in its print. It then registers a render for that key and sees its hiccup on the page and in its print at once.

## Slices, in order (each red then green, measured; owners from root's note)
1. **related-sections:** point 7's rule in `seon.screen/diff` and `printed-run` now. Small, needs nothing else.
2. **ctx-atom:** `patch` answers `:changed` and each member's t; root keeps a ctx per viewed page (property test: equal to the agent's @ctx at each t).
3. **agent view:** the ctx-path render cache and `:seon.ctx/ctx`'s layout render (screen side, chat side, sidebar), replacing the walk for agent pages. Browser-checked. Cost proof: a new eval redraws one component.
4. **agent view:** hiccup as a printer value kind (theme map, markers, wrapper elision, component diff) and the request's screen as the screen side's print. Measured against the numbers above.
5. **agent loop:** `take!` writes the message; `:from` as a ref (the store erase, root's moment); the stage boundary as a conversation entry; resume rebuilds ctx at a commit.
6. **agent view + root-as-one-system:** root and the user as agent entities; their pages as renders of their ctx; root's eval line.
7. **Deletions:** the walk, the read-set index, `records`' read, the screen producer, the hand-written `:ai` faces. Each slice deletes what it replaces in the same merge.

## Open, for root
- Point 4's attribute name and home: `:seon.eval/replay?` on the eval record, or only on the screen form's declaration with the eval inheriting it. I'd put it on the form, since the form is set up once, and record it on each eval it makes.
- Whether the theme map is config (`resources/seon/config`), a schema property of the layout render, or a ctx key an agent can `swap!`. I'd make it a value the layout render holds, overridden like the layout.
