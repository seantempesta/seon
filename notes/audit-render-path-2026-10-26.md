# Audit: the render/context path, merged 2026-10-26 (independent, read-only)

Verdict: **mostly generic with named exceptions.** The primitive is sound. The layout built on it is task-shaped, and a second hand-written change-tracking system sits beside the database's.

## (a) Genuinely generic and reusable
- `seon.render/html`, `render-of`, `schema-of`, `declared`, `print-html` (render.clj:93-165). Selection is one path: override, then schema-declared render, then the one printer. It is generic over any data. Its `nested` hook makes a nested value call its own schema's render.
- Compact renders (ui.clj `task-page`, `cited`, `status`, `related`, 893-942) return `[#'render/html data opts]`. The page expands the call and the model reads the same data. This is the right shape and nothing is stripped. (They are still task-specific, see (d).)
- `part-faces` and `part-html` (render.clj:~170-195) work for any part map.
- `seon.ctx/patch` (ctx.clj:195) is generic over any entity schema. It answers `:changed` paths and keeps untouched members `identical?`.
- Printer CSS: `.seon-print-*` classes (input.css:102-126) are semantic and shared by all printer output.

## (b) One-offs, duplicates, hard-coded shapes
1. **`render-ctx` is a hard-coded task layout, not a generic render of ctx** (render.clj:~196-250). It names `:task`, `:related`, `:status`, `:run`, `:tile` and the `:turns`, `:evals`, `:messages` and `:runs` keys, and it picks `(last (sort-by t-of (vals (:runs c))))`. It is one big function, which is what the owner said not to build ("sub renders so it isn't one giant function"). The fix: render-ctx walks ctx's keys and calls each key's schema-declared render. `:task`, `:status` and the rest become `:seon.render/html` declarations on schemas, composed by key, with no knowledge of them in render.clj.
2. **A second dependency tracker.** Parts carry hand-built `:read` keys: `context/task-key`, `cited-key`, `related-key` and `renders` (context.clj:357-389). These recompute the plan's references and re-seek the newest tx of each entity by hand. `render/drawn` (render.clj:~215) memoizes on those keys plus `identical?` on `:from`. Meanwhile render/proc.clj:450-540 already caches by `d/with-reads` and indexes datoms to components. That is two memo systems, one of them an approximation ("Not read: a cited file's text on disk" admits it is incomplete). Fix: draw parts inside `d/with-reads`, and let ctx's `:changed` plus the reads decide. This is exactly check (6).
3. **Two layouts and two part caches.** `ui/chat` + `component` + `chat-part` (ui.clj:739-890, walk/`comp-id` based) coexist with `ui/parts-page` + `agent-part` (ui.clj:951-1015). Both hand-write the same page chrome, the same three-column grid and the same sidebar, and the sidebar and top bar are copied. Delete one.
4. **Special cases by key in the generic path.** `part-faces` branches on `(= :eval (first (:key p)))`. `agent-part` does `case (first key)` over `:eval :message :turn :caption`. `chat-part` does `case schema` over turn and eval. `chat` and `tile-id` branch on `:seon.db/eval-fact`. `conversation-keys` is defined at ui.clj:~948. Fix: these are per-schema renders (`eval-fact` renders itself, opened or not, from an `:opened` prop).
5. **Region and order are baked into render-ctx** (`:region :task/:chat/:tiles`, ordering by `first-t`). Layout belongs to the layout render declared on the schema. The `:region` hard-codes the three-column page, and `parts-page` filters by it.
6. **`context.clj:648-654` filters `:region :task` and renders `:seon.render/render-ctx`.** The model's page is a task-only slice by name. This is a special case in the one path both consumers should share.
7. `entry` in render-ctx (render.clj) edits values by hand: `(dissoc :seon.turn/run)` and `(update :seon.turn/basis select-keys [:max-turns])`. The declared `:seon.render/keys` is the generic way, and this is a second cut beside it. It also deviates from "nothing stripped".
8. `render/walk` and `one` (render.clj:~270-340) are an entity pager with `:seon.render/parts` and limit handling. It is only used by the old `chat`. It dies with finding 3.

## (c) CSS
- `ui.clj` has 205 `:class` attributes, of which about 124 are multi-utility strings (3 or more tokens). `proc.clj`, `system.clj` and `render.clj` use almost none.
- Repeated utility strings, with counts: `"flex flex-col gap-1"` x7, `"tabular-nums text-text-400"` x7, `"text-2xs text-text-500"` x6, `"flex flex-col gap-2"` x4, `"text-2xs uppercase tracking-wider text-text-500"` x4, `"rounded-md bg-base-900 p-2 min-w-0 overflow-x-auto"` x3 (identical in `component`, `tile` and `agent-part`), `"mx-auto flex max-w-screen-2xl ..."` x3 (the page shell, copied in `chat` and `parts-page`).
- Semantic classes do exist (`tile`, `kicker`, `muted`, `seon-prose`, `seon-eval`, `.seon-print-*`), and the new compact renders (`task-page`, `cited`, `status`, `related`) share `tile` + `kicker`. But `input.css` is only 145 lines. The app-chrome and conversation classes (tile variants, row, side-link, page grid, meta text) are not extracted. Nested renders share classes only inside the printer and the four task renders. The rest repeat utilities.

## (d) Works only for task data
- `task-page`, `cited`, `status` and `related` call `context/task-data`, `cited`, `turn-state` and `related` directly (ui.clj:900-942). Their schemas are `:seon.render/task-page` etc., value shapes `{:var}`, `{:task id}` and `{:task :turn :last}`: pseudo-schemas whose values are keys to re-query. That is a render keyed to a query, not "a function plus the data it renders" (ruling 2). Cleaner: the data (task-data, cited) is ctx's own pull, and the render is over that data.
- `context/task-data`, `cites` and `task-key` are called from render-ctx via `fs`, and render.clj lists them in its contract (`:cites :task-key :cited-key :related-key`). Generic code takes task-named callbacks.
- `[:run]` (`:turns-taken`) adds a derived value to the render input (render.clj, `(assoc ... :turns-taken (count ...))`): a minor ruling-3 grazing, computed in the render, not stored.

## Rulings scorecard
1 one path: partly (render-ctx is one function, but ui has two layouts). 2 generic: no for the layout, yes for html/printer. 3 no derived data stored: holds (ctx.clj is data only). 4 same hiccup: holds, except `entry`'s stripping. 5 CSS: weak. 6 nothing twice: fails (task-key family vs `d/with-reads`; chat vs parts-page). 7 no special case: fails (key `case` branches).

Recommended order: (1) delete `chat`/`walk`/`component` or `parts-page`; (2) move `:task/:status/:related/:cited` renders onto ctx data and `d/with-reads`, then delete the `*-key` fns; (3) turn the key `case`s into schema renders; (4) extract the repeated utility strings into `input.css` classes.
