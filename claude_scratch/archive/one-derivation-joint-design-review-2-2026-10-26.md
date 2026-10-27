# Second independent review: the joint design, revision 2 (one derivation)

Read-only, 2026-10-26. Design read at `jolt/webui-root-chat` 8d8895d53 (after ca1ccbf4d; it includes the owner's "one patch mechanism" edit). Read against: the first review (notes/one-derivation-joint-design-review-2026-10-26.md), notes/one-derivation-2026-10-26.md ("Narrowed to the implementation"), docs/decisions.md 2026-10-26 entries, and main at e622c0b1b: seon.ctx, seon.screen, seon.context, seon.render, seon.render.ui, seon.render.proc, seon.repl, seon.agent, seon.turn, my.task. Nothing was run. Every file:line cited in the design was checked against main and matches.

## Verdict

**Approve with named changes.** The shape is now right: five functions, the model reads `(ai part)`, one render site, every page moved off the walk, no replay attribute, no size special case. Revision 1's hiccup-print machinery is gone. What remains are four correctness holes, all at the request (model) side, and a few deletions that callers still need. None of them needs a redesign. Each needs a sentence in the design and a proof in its slice. The top changes are ranked in §6.

## 1. Does it reduce to the chain plus the memo?

Mostly. These are still extra, or missing, at the edges of the chain:

| # | Item | Section | Need |
|---|---|---|---|
| 1 | **`[:task]`'s value is not in ctx.** `run-state` (context.clj:280) is a derivation over the db: `ref/references`, `docs/resolve-ref`, `docs/callers` and `similar`. It is not a pull. "Moved into the `[:task]` part's value" leaves out who computes it. `(view ctx)` takes only the map, so either ctx carries a derived task value, or view takes the db too. | §2 | Real, but unstated. It is a sixth input to the chain. Name it, for example as a ctx environment entry computed once per stage, pinned (see §3d). |
| 2 | **`[:side]` in the agent's view.** Root's agents (their statuses) live on main, not on the agent's branch, so the agent's ctx cannot render the sidebar live. An agent page is therefore root's `[:side]` parts plus the agent's parts, composed by root. | §2, "Where it runs" | Say so. Otherwise the agent shows a stale sidebar from its fork point. |
| 3 | **The request's existing history machinery has no stated fate.** `printed-run` (context.clj:454, about 62 lines: per-record cache, folds, `:same-as`, `canon`, the screen's `as-diff` branch), `as-sent` (516) and `kept` (525, the history cut). The design deletes `as-diff` and `request` but not these. Then "diff with rule 7, +60" is either a rewrite of `kept`, `printed-run` and `as-sent` (more than 60 lines), or a second mechanism beside them. | §5 | The design must say that `printed-run`/`kept` **are** the model side of `(diff prev next)` (kept and adapted), or what replaces them. This is the one place a second mechanism could quietly survive. |
| 4 | **The system prompt is not a part.** The tokens table keeps "system 502", but `(view ctx)` lists no `[:system]` part, and nothing says where it goes. Today `request` sends the *current* `system-text` each turn (context.clj:640), not the one its `help-ref` names. | §2, tokens table | Add it as the first part, pinned like the story. |
| 5 | **An @ctx eval reads the past.** It is recorded as `{:schema :basis}` (repl.clj:391), and the page rebuilds ctx as of t (`based-value`, ui.clj:480: a full `seon.ctx/build` on `d/as-of`). So "the debug toggle is the one place a past value is read" is false. This is a second one, and a full build per @ctx eval shown. | §4 | Keep it; it is correct, since an @ctx eval is not replayed. State it, and memo it by `[basis]`, which never changes. |
| 6 | Per-turn record of the sent part keys and t, for the debug page | §4 | Needed. It must also record each part's **mode** (whole, line, diff), as `:seon.turn/sent` does today (`[:line id]`, `[:eval id {:since h}]`). Keys and t alone cannot reproduce what was sent. |

## 2. Did root's rulings land?

| Ruling | Landed? |
|---|---|
| 1. The model reads `(ai part)` only | Yes (§4). `eval-messages` and `message-messages` stay, and the ai faces in seon.render.ai are kept. |
| 2. One diff over keyed parts; a per-part memo keyed on the render fn's value | Yes as written. But the memo's key is incomplete for the ai face (§3b), and `printed-run`'s fate is open (§1 #3). |
| 3. No replay attribute; `:seon.agent/screen` + `:seon.eval/by "screen"` | Yes. |
| 4. The screen's round trip deleted | **Partly.** The deletion list includes `seon.screen/input` (screen.clj:70) and `seon.context/turn-input` (743). But `my.task/status` reads `screen/input` (my/task.clj:56), which `seon.turn/screen!` fills from `turn-input` (turn.clj:206). The design keeps `my.task/status` "the same print", so those 19 lines move rather than go. `messages-tokens` (listed under −55) is used by `seon.turn`'s cache check (turn.clj:224-225). `context/render` is also called by `seon.judge` (judge.clj:22) and the outside agent's read (turn.clj:353). Neither is in a slice. |
| 5. One render site, the agent; processless from its branch with main's defaults | **Partly.** The site is right. But the machinery it retires is not listed for deletion: `ask-draws` (proc.clj:390), `*drawn*`/`*wanted*`/`branch-render`/`drawer` (ui.clj:469-509), and `seon.repl/draw` (repl.clj:460). That is about 60 lines, under-claimed. The sidebar is #2 above. |
| 6. No size or styling special cases | Yes. The html face draws under the printer's budget; styling is the stylesheet's classes. |
| 7. Every page off the walk | Yes (slice 5). |
| 8. Like-for-like tokens | **Partly.** The table compares one snapshot like for like. It does not compare **per turn**, which is where the change bites (§3d, §6 change 1). |

The owner's latest ("replay updates the db … diffs/patches, nothing moved") landed in 8d8895d53: the prefix holds, and the diff is appended.

## 3. Correctness risks

### (a) Upserting one eval entity per screen form each turn

- **Identity.** `:seon.eval/id` is the handle's name, `"eN"`, which is new on every eval (repl.clj:442, 418). An upsert needs a stable per-form id, for example `"<agent-key>/screen/<code>"`. Meanwhile `:response :handle` changes every turn, and so does the cost (`:ms :ticks :bytes`). The design's +10 does not say which id. Consequences:
  - The page's `eval-eN` anchors (ui.clj:885) for earlier handles stop resolving.
  - `result/eN` printed in old text names a value that is now only in history. In process it still resolves, through the interned var, and on resume it does not.
- **History.** It holds: `:seon.config.store/keep-history?` is true (default.edn:117), and `:seon.eval/record` is cardinality one, so each turn's value stays as a retracted datom.
- **Provenance.** There is no `:seon.tx/eval` attribute on main (grep is empty); `seon.db/tx-meta` carries only `:seon.tx/runtime` and `:seon.tx/branch`. Provenance today is "the eval's entity and its `:seon.eval/agent` tx". After the upsert, the `:seon.eval/agent` datom keeps its **first** tx, so the latest run's tx must be read from the `:seon.eval/record` datom. `records` (context.clj:568) queries `[?e :seon.eval/agent ?a ?tx] [(>= ?tx ?since)]`, so with an upsert it sees each screen form **once, ever**. That is fine if the request is rewritten (slice 4), and silently wrong if any reader of `records` survives it.
- **Order.** "First t" puts the replayed part where it first appeared. That is right for the page's layout. The model's order is different: prefix, then diffs in send order. "The page and the model get the same parts in the same order" is false under §5's own prefix rule, so restate it as "the same parts". The design also does not say the order *within* one turn's diff. Today's invariant is that messages taken this turn come before the screen that shows what came after them (turn.clj:154-157). The diff must keep it: added conversation parts in t order, then the changed replayed evals.
- **Debug as-of.** It holds, with the mode caveat (§1 #6). `:seon.turn/basis :screen` (the per-turn screen eval ids) becomes the same ids every turn. `chat-part` folds a turn's screen evals by pulling them **now** (ui.clj:868-875), which would show every old turn's screen with today's values. This goes with `chat-part`, but `basis :screen` and `sent-split`'s screen bucket should be retired in the same slice.

### (b) The per-part memo on `identical?`: can a stale render be served?

Yes, in three ways:
1. **`(ai part)` is not a function of `[f v]` alone.** Today a record's text depends on the records before it: `fold-of seen canon`, `:same-as`, and whether what a fold points to is still sent whole (`as-sent`). A memo hit on `[f v]` can serve a fold `(:out result/eN)` after eN fell under the history cut. That is exactly rule 7's failure. Fix: `(ai part)` emits fold-free text, and folds and `:same-as` live only in the diff, which knows what is readable. Otherwise the memo key includes the readable set.
2. **Renders that read outside v.** These are config budgets (`cell`, `part`, `form-budget`, ui.clj:23-25), the db (`chat-part` and `component` pull, ui.clj:860, 913), dynamic vars (`*drawn*`), and the branch's schema registry. Each must be part of v, or of f's identity, or the memo serves stale output. The design's +40 for `html`/`ai` must make renders pure in `[f v]`; say so, and prove it with a mutation test that changes the budget config.
3. **f identity.** Key on the deref'd fn object, never on the var (`#'f` is identical across redefinitions). The design says "the renderer value f"; make that "the fn the var holds now".

It can also fail the other way: a replayed eval's record differs every turn (new handle, new cost), so `v` is never identical or `=`. That re-renders, which is merely wasteful, and **the diff reports it as changed every turn**. The part value of a replayed eval must be its response less `:handle :ms :ticks :bytes`, as context.clj:394 already does, and the diff must compare by `=` on that.

### (c) Root rendering a processless agent from its branch: a second derivation in disguise?

**No, as specified:** the same `(ctx db e)` and `(view ctx)`, built once, unpatched, and dropped when the agent starts. It is the same code at a second site, not a second producer. Three conditions keep it so:
- **Nothing patches it.** A processless agent's branch changes only by its own turns, so there is nothing to patch. If someone later adds a patch loop "for freshness", it becomes the second ctx the first review rejected.
- **It is discarded on spawn.** The live agent's parts replace it, and the two never coexist.
- **The caption tells the truth.** Overrides are data on the branch (`branch-render`, ui.clj:492, reads `:schema/form`), but the fns they name may exist only in the dead process. The caption "drawn with main's defaults" is honest only if root also uses main's renders for keys whose branch schema names an fn main lacks. Say "where the branch's render is not on main" rather than dropping all overrides.

### (d) The prefix: can anything in it change between turns?

Yes, five things, as written:
1. **`[:task]`.** Today the story is pinned to the session's first turn's t (context.clj:777-783, `story-t`, `d/as-of`), so it never changes within a stage. Under the chain it is a live part of ctx. `run-state`'s `:write-set` carries each cited var's source and callers on the agent's **branch**, so every edit the agent makes to its write set changes `[:task]`, and the whole 4.5k-token story is re-sent as a diff. Pin `[:task]`'s value at the stage's first t, as today, and let the stage boundary be its only change, as the design already intends.
2. **The system prompt.** It is not a part (§1 #4), and today it is current per request. Pin it the same way.
3. **The agent overrides `:seon.render/ai` mid-run, or a resume, or a new generation.** If the request is reassembled from the parts' ai text (through the memo), the earlier parts re-render and the prefix bytes change. The design says "never rewritten". That holds only if the prefix is the **previous request's bytes, held**, not a re-render. Today's `printed-run` holds its cache in process (`:printed`) and re-renders after a resume; the same exposure exists today, and the design should say which way it goes. Recommendation: hold the sent messages; an override changes only parts sent after it.
4. **Folds** (§3b.1).
5. **The history cut's jump.** This one is deliberate and fine (point 8).

## 4. The deletion list (−1,084) and net (≈ −550)

Spot-checks at main e622c0b1b. The function sizes are from each `defn` to the next.

| Item | Claim | Measured | Verdict |
|---|---|---|---|
| render.clj `forward` 216, `relevant` 221, `one` 230, `walk` 272 | −97 | 5 + 9 + 42 + 40 = 96 | Holds (all pages move in slice 5). |
| proc.clj `ready?` 291, `walk?` 319, 327-367, `comp-entry` 368, 401-462 | −139 | 13 + 8 + 41 + 22 + 55 = 139 | Holds. **Under-claimed:** `pass-view`/`view-pass` (463-531, about 70 lines) are built on `d/with-reads` and `page-value` and go too, as does `forget` (448). |
| screen round trip (agent.clj:516, proc.clj:539-604, screen.clj, handlers) | −113 | 14 + 66 + 29 + 4 = 113 | The sizes hold, but **`screen/input` and `turn-input` (19 lines) cannot go**: `my.task/status` reads them (my/task.clj:56, turn.clj:206). Net about −94. |
| ui.clj layouts and the debug page (17 fns) | −443 | 445 by `defn` spans | The sizes hold. Most of `chat-part`'s and `component`'s bodies are the eval-line and turn renders, which come back in the +140. That is counted, so fine. |
| request assembly (`request` 601, `run-message`, `screen-message`, `turn-input`, `messages`, `records`, `render`) | −141 | 51 + 8 + 10 + 12 + 6 + 21 + 33 = 141 | The sizes hold. `turn-input` stays (above), and `render` has two more callers (`seon.judge`, the outside read), which need the new path. |

Also unlisted:
- The render-site machinery from ruling 5 (−60).
- `messages-tokens` stays for turn.clj:224, or moves (+8).

Additions likely low:
- **`ctx` over any entity, +20.** `seon.ctx`'s patch driver is the watch on `agent/state` (ctx.clj:343-360). Root, the user and task pages need their own driver from main's transaction reports, the render proc's `moved`. Expect +40 to +60.
- **`diff` with rule 7, +60,** if `printed-run`, `kept` and `as-sent` are replaced rather than adapted (§1 #3).

**Net:** deletions of about −1,084 + 130 (unlisted) − 27 (can't go) ≈ −1,190. Additions of about +530, plus 30 to 100 (ctx driver, diff rewrite). So the net is roughly **−560 to −630**. The claim of ≈ −550 is credible, if anything slightly conservative, given §1 #3 is settled as "adapt `printed-run`/`kept`". Tests (render_test, render_cost_test, which call `page-value`, `comps-touched` and `touched`) need rewriting; that is not src, but it is real work for slice 5.

## 5. Slice order

Mostly safe. Two ordering bugs and one oversized slice:
- **Slice 3 cannot delete `as-diff`.** `printed-run` (context.clj:494) and `parts-of` (700) use it until slice 4 replaces the request. Move the deletion of `seon.screen/diff` and `as-diff` into slice 4, or wherever the screen diff's last reader goes.
- **Slice 4 is four changes behind one paid measurement:** the screen-eval upsert, the round-trip deletion, the request as parts, and the debug page. A regression in turns to green could not be attributed. Split it:
  - **4a: the upsert plus the round-trip deletion.** The agent evaluates its own forms, drops `(seon.screen/show)` from `seon.screen/forms`, and keeps today's request path. This is measurable at no paid cost: tokens per turn, and the cache share on a replayed outside run.
  - **4b: the request as parts' ai, whole then diffs.** This is the paid, measured run.
  - **4c: the debug page as parts' ai.**
- **Slice 2** moves the render site (agent renders, root forwards) while root's and task pages still use the walk. It is shippable as a transition, but say that two producers coexist from slice 2 to slice 5, and keep that window short.
- **Slice 6** (`:from` as a ref, the store erase) is independent of 2 to 5 and can run in parallel. Only its stage-boundary part needs 4b.
- **Missing from every slice:** `seon.judge`'s fresh-run messages (judge.clj:22) and the outside agent's read (turn.clj:335-353, `seon_context`) go onto the chain. Add them to 4b.

## 6. Verdict: approve with these changes, ranked

1. **Say how a changed replayed eval reads to the model, and measure it per turn.** Under §5 it is "its new value", whole. `my.task/status` changes every turn (its turn count), and `my.branch/changes` grows with every edit. Today each screen form is sent as its value-path diff from the last one (`seon.screen/diff` via `as-diff`): 11,848 whole against 4,458 as diffs per turn on a model agent. Deleting the value diff could cost up to about 2.6× on the screen side every turn, which the snapshot table does not show.
   - Either keep `seon.screen/diff` as the **ai text of a changed part**: the part-level diff decides *which* parts, and the value diff says *how* a changed one reads, when shorter, as `printed-run` does. That is one diff over parts with a text form for each part, not a second mechanism.
   - Or show per-turn numbers that whole values are cheap enough.

   Root rules. 4b measures either way.
2. **Pin the prefix.** `[:task]` and the system prompt are pinned at the stage's first t (as `story` does today, context.clj:733 and 777-783). The request's prefix is the previous request's **held bytes**, never a re-render. An ai override or a resume changes only what is sent after it.
3. **Make `(ai part)` pure in `[f v]` and fold-free.** Folds, `:same-as` and the history cut live in the diff, which knows what is still readable (rule 7). State that `printed-run`, `kept` and `as-sent` become that diff's model side, adapted, not a second mechanism beside it. The memo keys on the deref'd fn. A replayed eval's part value excludes `:handle` and the cost keys, and the diff compares by `=`.
4. **Specify the upsert.**
   - It uses a stable per-form `:seon.eval/id` (agent key plus code); the handle is still new each turn.
   - Within a turn's diff, taken messages and new evals come first, in t order, then the changed replayed evals (turn.clj:154's invariant).
   - Retire `:seon.turn/basis :screen` and `records`' since-query in the same slice.
   - Record each sent part's mode (whole, line or diff) with its key and t, for the debug page.
5. **Fix the edges of the render site and the deletion list.**
   - The sidebar is root's parts, composed by root.
   - List `ask-draws`, `*drawn*`/`*wanted*`/`branch-render`/`drawer` and `repl/draw` (about −60) and `pass-view`/`view-pass` (about −70) as deleted.
   - Keep `screen/input`/`turn-input` and `messages-tokens`, or name their new homes.
   - Put `seon.judge` and the outside read in slice 4b.
   - Move the `as-diff` deletion out of slice 3, and split slice 4 into 4a, 4b and 4c.
   - Name `[:task]`'s computed value (`run-state` over the db) as ctx's environment, and the @ctx eval's basis read as the second as-of read.
