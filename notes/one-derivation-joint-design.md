# One derivation, joint design (revision 3)

Agent view with ctx-atom. Root approved revision 2 with eight named changes after the second independent review (`joint-design-review-2.md`), and this revision makes them. It implements the owner's narrowing ("Narrowed to the implementation", `notes/one-derivation-2026-10-26.md`) and the owner's later rulings: evals run once; what changed between turns arrives as one "Since your last turn" message; each role on the wire has one meaning. Line numbers are main's at e622c0b1b.

## The chain
```
(ctx db e) --> map --> (view ctx) --> parts [{:key k :value v} ...]        --+--> (html part) --> hiccup --> page   (Datastar patch by key)
                                                                               +--> (ai part)   --> text   --> the request's assembly --> model
            (diff prev next) by key: the page's patches;  "Since your last turn": ctx's data diff, paths and values;  memo per part: [the fn the var holds now, value] identical?
```
- The page and the model get **the same parts**, differing in detail: the page may collapse a part, while the model's text carries what the agent needs within budget, and its cuts say how to read the rest.
- The order differs:
  - the page shows parts in the view's order;
  - the model gets the starting page once, then each turn's data diff of ctx: paths into ctx and their new values, in the order they happened.
- Both faces default to the one printer, `ops-hiccup` and `text` from one selection under one budget. A schema's `:seon.render/html` or `:seon.render/ai` overrides that face, for that value only.

## 1. `(ctx db e)` → map
- **Contract:** `[:=> [:cat :seon.host/db pos-int?] :map]`. It returns entity e and its units, pulled once by the pattern its schema's `:seon.render/units` derives, plus its environment, then patched per transaction at the cost of the change.
- **Is:** `seon.ctx/build` (ctx.clj:93) and `seon.ctx/patch`, which exist. The patch is 6 contracted calls per change at any session size, and equals a fresh build after 150 random changes.
- **New:** `build`'s root generalised from `:seon.agent/key` to any entity id, and a patch driver from main's transaction reports for root's, the user's and task pages, beside today's watch on `agent/state` (ctx.clj:343-360). +50.
- **Everything view reads is in this pull (root's change 1):**
  - the task;
  - its proofs and their results, as the turn's observe records them;
  - the done rule's verdict and reasons;
  - the run;
  - merges from main (`:seon.tx/merged`);
  - related sections, as facts written when the plan changes;
  - the stage's story: `run-state`'s value (context.clj:280, its references, resolved refs, callers and similar), computed once at the stage's first turn and written as a fact on the run, so the view reads it and never queries.
  No part's value is computed outside ctx.
- **Order:** an entry's first transaction t, a derived index (never a stored vector).
- **Sharing (ctx-atom):**
  - The patch changes only along each changed path, so every unchanged member stays `identical?` (slice 1's proof).
  - The root map is new each transaction, so nothing caches on it.
  - A build makes all-new objects, so the memo misses once, everywhere (the cost proof says so).
- **Replaces:** `seon.render/walk` (render.clj:272), `one` (230), `relevant` (221), `forward` (216); `seon.context/records` (557).

## 2. `(view ctx)` → parts
- **Contract:** `[:=> [:cat :map] [:vector [:map [:key :any] [:value :any]]]]`. It is **pure in ctx** (root's change 1).
- **Is:** the one step that decides what is relevant and in what order. The layout is its output, as data, overridden by a branch's schema entry for `:seon.ctx/ctx`.
- **An agent's parts:**
  - `[:system]`, the help page, pinned per session;
  - `[:task]`, the stage's story fact;
  - `[:proofs]` and `[:verdict]`;
  - `[:run]`;
  - `[:merges]`;
  - `[:related]`;
  - `[:conversation n]`: the agent's evals, turns and taken messages, in t order.
- **The sidebar is not a part (root's change 5):** root's agents live on main, so the sidebar is page chrome root renders around an agent's parts.
- **Evals run once.** An eval's record is written once. A re-run happens only on resume, to rebuild the environment. There are no screen forms, no replay and no `:seon.eval/by "screen"`.
- **Every change is told; noise is a data defect** (the owner: "all changes should be worth telling (if we have weird naming and unnecessary updates and machinery instead of actual values we have the wrong design)"). There is no filter. A value that changes without meaning is kept out of ctx or out of its pulled parts at its producer. Measured on a1 below, today's noise and its fix:
  - the environment's `:t` and `:commit`, and `:seon.agent/answered-t`, counters: kept out of the pulled parts (ctx's metadata, not its data);
  - the context read recorded as an eval (`[:evals "e54"]`, 22.6k characters): no longer an eval in 4b, since the outside read is the parts' ai;
  - the screen evals (`e55`–`e58`, 7.0k): deleted with the screen in 4a;
  - a run pulled whole with every turn's basis (`[:runs "…#9"]`, 6.6k): the run part pulls its id, status, turn and outcome (its schema's `:seon.render/keys`); the basis stays a record, one form away;
  - the agent's own evals (`e53`, `e59`, `e60`): their changes are told by their tool answers in the same request, so the data diff starts after them, by provenance (`:seon.tx/eval`, ctx-atom's). That is a time window on what this request already carries, not a filter.
- **Big values stay small by their own shape:** a cited var's change is its path and new head, `[:task :cites 'ns/f :head] "(defn f [x] …)"`, because ctx holds the head for a cited var and the source is one form away.
- **Replaces:**
  - `seon.render.ui/agent`'s tile gathering (ui.clj:1012);
  - `chat`'s split (962), `rows` (804), `shown-as` (783), `tile-id` (841);
  - the request's assembly order: `request` (context.clj:601), `run-message` (583), `screen-message` (591), `messages` (652).
  - `run-state` moves to the stage-start fact; it is moved, not deleted.

## 3. `(html part)` → hiccup
- **Contract:** `[:=> [:cat :map] :any]`. The part's value goes through its schema's `:seon.render/html` if declared, else `emit/ops-hiccup` of the printer's selection under the part's budget. A large value is drawn to its cuts, never whole. Styling is the stylesheet's semantic classes.
- **Pure in `[f v]`:** the budgets it reads come in v (the part carries its budget) or are named in f, never read from config at render time. A mutation test changes the budget config and sees the page change.
- **A render that throws answers its part as its error** and every other part renders: today's `view-pass` failure point (proc.clj:502), moved to the part.
- **Where it runs:**
  - The agent renders its own parts, since its ctx and render fns are in its process. Root forwards them and adds the sidebar.
  - An agent with no process is drawn by root from its branch, once and unpatched, with main's renders wherever the branch's render is not on main, captioned so. It is discarded when the agent starts.
- **Replaces:**
  - `component` (ui.clj:896), `chat-part` (850), `page` (1132), `tile` (935), `view` (788), down to one eval-part render and one layout;
  - `ctx`'s unbudgeted table (747);
  - the draw-asking machinery: `ask-draws` (proc.clj:390), `*drawn*` (ui.clj:469), `*wanted*` (474), `branch-render` (492), `drawer` (503), `seon.repl/draw` (repl.clj:460);
  - `pass-view` (proc.clj:463), `view-pass` (502), `forget` (448), down to a pass over parts.

## 4. `(ai part)` → text
- **Contract:** `[:=> [:cat :map] :string]`. The part's value goes through its schema's `:seon.render/ai` if declared, else `print/text` of the same selection under the same budget, after a one-line `[key]` header.
- **One selection for both faces:** today `render/html` and `render/ai` each make their own selection (`print-html`/`print-ai`, render.clj:113-132). The printer already answers both from one, `select/show` with `:outputs #{:text :hiccup}` (select.clj:849, `emit/emit-both`), so a part's two default faces are one `show`, and the page and the model can never differ in what was cut.
- **Pure in `[f v]`, with no folds (root's change 3).** It never refers to another part. An eval part's ai face is the record's existing one, `eval-messages` (context.clj:372); a message's is `message-messages` (405).
- **An @ctx eval reads the past:** it is recorded as its basis (repl.clj:391) and drawn as of t (`based-value`, ui.clj:480). Its basis never changes, so it is memoed by its basis. This is the second as-of read, alongside the debug page.
- **Replaces:**
  - `seon.context/render` (755), and its callers: `seon.judge` (judge.clj:22) and the outside read (turn.clj:353, `seon_context`), which move to the parts in slice 4b;
  - `story` (733);
  - `parts-of` (688) and `sent-split` (719), which go when the debug page moves (4c).

## 5. `(diff prev next)` by part key
- **Contract:** `[:=> [:cat [:vector :map] [:vector :map]] [:map [:changed [:vector :map]] [:added [:vector :map]] [:removed [:vector :any]]]]`, comparing values by `=`.
- **The page:** a changed or added part is re-rendered (through the memo) and sent as a Datastar patch by its key. An identical value sends nothing.
- **The model:** "Since your last turn" is ctx's data diff since the agent's last request: each changed path into ctx and its new value, in t order (`[:messages "m3"] {:from :owner :body …}`, `[:task :stage] :implement`, `[:proofs 'ns/x-test :status] :red`), so the agent reads it as the data it is and changes it with the same paths (`swap! ctx assoc-in …`). A changed part's new text may go with it where it helps, even with some duplication.
- **The memo:** `{key [f v out]}`, a hit when f (the fn the var holds now, never the var) and v are both `identical?`.
- **Replaces:** the read-set index: `comp-entry` (proc.clj:368), 327–367, 401–462, `walk?` (319), `ready?` (291).

## The request's assembly (root's changes 2 and 3)
The request is no function of the chain. It is the assembly of what was sent, plus this turn's data diff of ctx.
- **The prefix is pinned.** It is the system prompt, fixed for the session, and the starting page: each part's ai text at the session's start (or resume), as sent.
  - Its bytes are kept and reused from `:seon.turn/sent`'s recorded text, never re-rendered. An ai override, a resume or a new generation changes only what is sent after it.
  - The task's stage changes arrive only in "Since your last turn".
- **Each turn appends:** the agent's prose and eval calls, their tool answers, then one "Since your last turn:" message.
  - It is ctx's data diff: each changed path and its new value, in t order (a message taken at the turn's start is the new entry `[:messages id]`, turn.clj:154).
  - A changed part's rendered text may go with its path where it helps.
  - When nothing changed, it says "nothing".
- **Where today's history machinery goes:**

  | function (context.clj) | fate |
  |---|---|
  | `kept` (525, 32), the history cut that jumps once and holds | **moved** into the assembly unchanged, over the conversation's sent messages |
  | `printed-run` (454, 62): per-record cache, `fold-of`, `:same-as`, canon | **moved** into the assembly as its rules over the conversation; its screen branches (15) **deleted** |
  | `fold-of` (425), `folded` (366), `ref-of` (420) | **moved** with `printed-run`; a fold or reference only to a part still readable whole (rule 7) |
  | `as-sent` (516, 9) | **moved**: what each sent part is, whole or line, recorded with its mode |
  | `as-diff` (446, 8) | **deleted** in 4b, its last reader then gone |
  | `turn-input` (743, 12), `seon.screen/input` (70, 7) | **kept**: `my.task/status` reads them (my/task.clj:56) |
  | `messages-tokens` (711, 8) | **kept**: `seon.turn`'s cache check (turn.clj:224) |

- **Each turn records** the parts it sent with each one's key, mode (whole, line, diff entry) and t, so the debug page (4c) shows exactly what was sent.

## The wire: one meaning per role
| role | carries | changes |
|---|---|---|
| system | the rules: the help page, pinned for the session | never |
| user, first | the starting page: each part `[key]` then its ai text | never: the cached prefix |
| assistant | prose plus eval tool calls: the agent's own evals, the only tool calls | appended |
| tool | each call's answer | appended |
| user, one per turn | "Since your last turn:", ctx's data diff, each changed path and its new value in order; a message is the new `[:messages id]` entry; "nothing" when nothing changed | appended, never rewritten |

Nothing else pretends to be an eval. A person's message is a new entry in ctx, so it appears once, as its path in the data diff, and the page's transcript shows the same entry, one patch, with no re-send.

### One recorded turn, both ways (a1, this worktree's root)
Today, after the system prompt (538 tokens):
```
user  Your task and what it cites, the same for this whole run: {:task #:seon.task{…} …}                       4,545
user  {:by "screen" :code "(seon.screen/show)" :response {:handle result/e49, :value {:page {:seon.render/html […]}}}}   672
user  {:by "screen" :code "(my.task/status)" :response {:handle result/e50, :value {:proofs […] …}}}                   100
user  {:by "screen" :code "(my.branch/changes)" :response {:handle result/e51, :value {}}}                              26
user  {:by "screen" :code "(my.task/related)" :response {:handle result/e52, :value {:read … :sections […]}}}            700
```
This design:
```
user  Your starting page: each part is [key] and its value. After this, each turn ends with one message, "Since your last turn", listing what changed.
      [:task]  {:task #:seon.task{…} …}
      [:proofs] {seon.render-test/a-view-that-throws-answers-its-page-with-the-error :pass}
      [:run] "#6 · running · turn 3 of 40"
      [:related] {:read … :sections […]}
…assistant / tool: the agent's own evals, as today…
user  Since your last turn:
      [:messages "m3"] {:from :owner :body "check the proofs before you merge"}
      [:proofs seon.render-test/a-view-that-throws-answers-its-page-with-the-error :status] :fail
      [:proofs seon.render-test/a-view-that-throws-answers-its-page-with-the-error :failure] {:expected 1 :got 2}
      [:runs "seon.render/a-render-defect-answers-the-pages-it-holds#9" :turn] 2
```

## Tokens
**The starting page, a1** (characters ÷ 3.2, `:seon.config.ai/chars-per-token-prior`):

| | today | this design |
|---|---|---|
| system | 538 | 538 |
| story / `[:task]` | 4,545 | 4,545 |
| screen / the other parts | 1,498 (`show` 672, `status` 100, `changes` 26, `related` 700) | ≈ 830 (`[:proofs]` and `[:verdict]` ≈ 100, `[:run]` ≈ 30, `[:related]` 700) |

**The data diff, measured on a1 (root's ruling on telling every change).** One real turn on this worktree's root:
- a1 held `c0 = @ctx`;
- the owner sent it a message;
- its turn read its context;
- it evaluated `(defn f …)`.

The path diff of `c0` against `@ctx` today has 11 paths and 37,387 characters (≈ 11.7k tokens):

| path | characters | fate under the design |
|---|---|---|
| `[:evals "e54"]` (the context read as an eval) | 22,618 | gone in 4b |
| `[:evals "e55"]`–`[:evals "e58"]` (screen evals) | 7,011 | gone in 4a |
| `[:runs "…#9"]` (a new run, pulled with every turn's basis) | 6,622 | ≈ 100: its id, status, turn, outcome |
| `[:evals "e53"]`, `"e59"`, `"e60"` (its own evals) | 1,882 | told by their tool answers in the same request |
| `[:seon.agent/answered-t]`, `[:t]`, `[:commit]` | 62 | kept out of the parts |
| the message (not in ctx today: `take!` does not write it, slice 6) | 0 | ≈ 80: `[:messages "m3"] {:from :owner :body …}` |

**Under the design, that turn's diff is ≈ 180 characters, about 55 tokens.**

**Per turn on a recorded sequence (root's change 8).** The recorded a-map run is `seon.print/a-map-shows-its-first-entry#2`: 29 model turns, re-rendered by related-sections with main's history cut (`a-map-per-turn-2026-10-26.md`).

| | today, per turn | this design, per turn |
|---|---|---|
| system + story | 962 | 962, pinned |
| conversation | 24,213 | 24,213 (the same records; the cut jumps at turns 13 and 28) |
| screen / "Since your last turn" | 804 | ≈ 20: `[:runs id :turn] n` every turn, plus the proofs' paths at the 3 turns their results changed (0, 26, 28); no messages were taken |
| **total** | **25,979** | **≈ 25,195 (−3.0%)** |

- On this run the screen was only 3.1% of each request, so the token saving is small. The conversation is where the tokens are, and the design leaves it as it is.
- The design's gain here is that the agent reads one consistent shape (its own ctx's paths), not that it reads fewer tokens.
- A message-heavy or long-running agent's numbers are slice 4b's live measurement.

## What it deletes, and the src delta (root's change 6)
Deletions are function sizes on main, with the second review's spot-checks. Additions are estimates, ±50%.

| deleted | lines |
|---|---|
| every page's walk: `walk`, `one`, `relevant`, `forward` | −97 |
| the read-set index, `ready?`, `walk?` | −139 |
| `page-value` (40), `debug-value` (25) | −65 |
| `pass-view` (39), `view-pass` (30), `forget` (7) | −76 |
| the draw-asking machinery: `ask-draws`, `*drawn*`, `*wanted*`, `branch-render`, `drawer`, `repl/draw` | −52 |
| the screen: `seon.screen` less `input` (68), `seon.turn/screen!` and its calls (16), the screen branches (15), the `:screen` origin (2), `seon.agent/screen` (14), the proc's screen views (72), the handlers (4), the page's screen fold (13) | −204 |
| `seon.context/as-diff` | −8 |
| the request's assembly order: `request`, `run-message`, `screen-message`, `messages`, `records`, `render` (`turn-input` kept) | −129 |
| the debug read-back: `parts-of`, `sent-split`, `story` (`messages-tokens` kept) | −47 |
| walk layouts and the debug page in ui.clj (17 functions) | −445 |
| **deleted** | **≈ −1,262** |

| added | lines |
|---|---|
| `ctx` over any entity, with main's patch driver | +50 |
| the stage's story as a fact at the stage's first turn | +20 |
| `view`: an agent's, root's, a task's, the default | +130 |
| `html` and `ai` part, pure, with the budget in the part | +40 |
| `diff` and the per-part memo | +40 |
| a page as a layout of parts, the sidebar as root's chrome, one eval-part render, a part pass with its failure point | +165 |
| the debug page as the parts each turn sent | +30 |
| the request's assembly: the pinned prefix, "Since your last turn" as ctx's path diff, the moved `printed-run`, `kept` and `as-sent` rules | +70 |
| the agent renders its parts; root forwards them; a processless agent with defaults | +60 |
| `take!` writes the message; `:from` as a ref | +15 |
| **added** | **≈ +620** |

**Net ≈ −640, within −330 to −950 given the additions' error.** The second review put revision 2's shape at −560 to −630. This revision also deletes the whole screen and the draw-asking machinery, keeps the 27 lines callers still need, and adds the ctx driver and the story fact.

## The agents' help text
> Your page is `(view @ctx)`: keyed parts, in order, sent to you whole at the start of your session. After that, each turn ends with one message, "Since your last turn": what changed in your ctx, as paths into it and their new values, in the order it happened (a message someone sent you is a new `[:messages id]` entry), so you can read it as data and change it with the same paths. Your tool calls are your own evals, and each runs once. Each part has two faces, html for the user and ai for you, and both default to the one printer, so any value reads well with no render. To change how a key looks on the page, register its schema with `{:seon.render/html f}` (f: value → hiccup); to change what you read, `{:seon.render/ai f}` (value → text); the layout is `:seon.ctx/ctx`'s view.

**Its proof:** a fresh agent `swap!`s a key no schema names (a vector of maps). It reads a table in its text and sees one on the page. It then registers an html render for that key and sees it on the page at once, with its own text unchanged.

## Slices (root's change 7)
Each slice gets its own `bin/prove`, a browser check, and a live measurement by live-agents before merge.

1. **ctx-atom:** `(ctx db e)` over any entity, with main's patch driver; the sharing proof (every path not changed stays `identical?`); order by first t; the stage's story and related sections as facts in ctx.
2. **agent view:** `(view ctx)` for an agent, pure in ctx; the noisy values kept out of the pulled parts (`answered-t`, the run's basis); `(html part)`/`(ai part)`, pure, with the budget in the part; the agent page as a layout of parts, rendered in the agent and forwarded by root with the sidebar as chrome; the per-part memo; the part pass with its failure point. Old and new producers coexist from 2 to 5, and the window is kept short.
3. **related-sections:** `(diff prev next)` and the page's patches; ctx's path diff for "Since your last turn" (a cited var's head, a proof's status and failure as paths). It deletes nothing of the request's yet.
4. The request, split so each part is measured alone:
   - **4a (agent view):** the screen deleted: no screen forms, no `:screen` origin, no round trip. Today's request path stays, less its screen. Measured free of charge on an outside run.
   - **4b (agent view + related-sections):** the request as the pinned starting page plus "Since your last turn"; `printed-run`, `kept` and `as-sent` moved into it; `as-diff` deleted; `seon.judge` and the outside read (`seon_context`) on the parts. The paid, measured run: turns to green, cache share, tokens per turn on a recorded sequence.
   - **4c (agent view):** the debug page as the parts each turn sent, with their modes.
5. **agent view:** every other page (root's, the user's, a task's, `/walk/<eid>`) on the chain. Delete the walk, the read-set index and the walk layouts.
6. **agent loop:** `take!` writes the message; `:from` as a ref (the store erase, at root's moment). This is independent of 2–5; only its stage-boundary part needs 4b.
7. **root-as-one-system:** root and the user as agent entities; root's eval line.
