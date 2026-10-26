# One derivation: the agent's ctx, rendered once, for the user and the agent

The owner's direction of 2026-10-26 (docs/decisions.md, the entries from "The web UI is the render of ctx" on): the agent's record is derived once, as `@ctx`, and everything anyone sees of it is a pure function of the database at given transaction points. It replaces four derivations that redid the same work (the page's walk, `@ctx`, the screen's forms, the request's own read of the records). The approved design and its slices: notes/one-derivation-joint-design.md (agent view r3, with ctx-atom); its two independent reviews: notes/one-derivation-joint-design-review-2026-10-26.md and -review-2-.

## The chain (five functions, one direction)
1. **`(ctx db-at-t agent)` → map.** One pull of the agent's record on its branch as of t, its pattern from the schemas' `:seon.render/units`; kept current by patching each transaction's datoms, and equal to a fresh pull at any t. ctx holds data only: anything that changes every eval without meaning (a handle, a cost counter, a timestamp) is kept out, because every change to ctx is told.
2. **`(view ctx)` → ordered keyed parts.** Pure in ctx: the task (its stage's story written as a fact at the stage's first turn), proofs and their results, the verdict, the run, merges, related sections, the conversation (evals and messages in the order of their first transaction). Layout is view's output, as data; an agent overrides it like any render.
3. **`(html part)` and `(ai part)`.** The one printer's two faces of one selection (`select/show` with `:outputs #{:text :hiccup}`): hiccup for the page (details collapsible), text for the agent (what it needs within budget, cuts that say how to read the rest). A value's schema may declare `:seon.render/html` or `:seon.render/ai` to override that face only; most values need neither, and data not worth showing is not a part.
4. **`(diff prev next)` by part key.** One diff serves the page (Datastar patches in place) and the agent (the world's changes).
5. **Memo** by the identity of the part's value and of the fns that draw it: an equal value never re-renders; a redefined render does.

## What the agent reads, and why it is byte stable
The request for turn n is a pure function of the db at the session's turn points t0 < t1 < … < tn; no sent text is stored.
- **system**: the rules (fixed for the session).
- **user, first**: the page, `(ai (view (ctx t0)))` whole: the cached prefix.
- per earlier turn k: **assistant** = the agent's prose and its own eval calls (evals run once; re-run only when resuming a session); **tool** = each eval's answer with its ripple effects, from its record as of its t: the value and output, what it redefined (contracts, docstrings, and the sections citing them), what re-rendered, what else it changed in ctx; **user** = the world between t_k and t_{k+1}, in fixed labelled sections: `[from <sender>]` messages taken, `[changes]` (paths into ctx and their new values: the view diff, less what the tool answers already showed), `[page]` the rendered parts that changed.
- Every change from outside (a person's message, the task's stage, a proof's result, a verdict, main moving under the branch) arrives as an inbox entry carrying its ctx-shaped diff: one delivery for messages and changes.
- The history cut (jump and hold) and folds are one pure rule over that sequence.
- Renders are program forms and the program is in the db with history, so rendering turn k uses the code as of t_k; a later redefinition never changes an old turn's bytes. Turn n's request is turn n-1's plus the new tail, byte for byte, so the provider's prefix cache holds by construction.
- Recorded per turn: its t, `:seon.turn/sent` as part keys (never text), the provider's counts. The debug view re-derives the text from the db, which is also the reproducibility test.

## The page
The same parts' html faces at the current t, patched in place by the same diff. The agent renders its own parts (its ctx and its renders live in its process; root's page asks it through `:render`); an agent with no process is rendered once from its branch with main's defaults. Root's page is root's ctx rendered; the sidebar is root's page chrome.

## The agents' explanation (the acceptance test: it must stay this short)
Everything in `@ctx` is shown to you and the user the same way. Put data in with `swap!`. To change how a key looks, register its schema with `{:seon.render/html your/fn}` (or `:seon.render/ai` for what you read). Tool calls are your own evals and run once; the last message each turn is what changed in your world since your last turn, as paths into `@ctx` and their new values.

## Deleted by it (≈ −640 net, measured per slice)
The page's walk (after slice 5), `seon.screen` whole and the screen forms, the request's own assembly in `seon.context` (moved into the pure request function), the story block's duplicate of the task, the separate `:ai` renders where the printer serves, root's screen round trip.
