# One derivation: the agent's ctx, rendered once, for the user and the model (direction, 2026-10-26)

The owner's direction, gathered from the rulings of 2026-10-26 (docs/decisions.md "The web UI is the render of ctx" and the answers after it): the agent's record is derived once, as `@ctx`, and everything anyone sees of it is one render of that value. This is a simplification: it deletes producers, it does not add one.

## The shape
1. **Pull once.** ctx is one pull per agent on its branch, its pattern derived from the schemas' `:seon.render/units`; each transaction's datoms patch it. The conversation's order is read from facts (an eval's number, a transaction's t), never a stored growing vector. Small ordered values (a layout's tiles, steps) may be `[:vector T]`.
2. **Render once, by schema.** One `render value`: the render the value's schema declares (`:seon.render/html`), else the one printer's hiccup. Renders call `render` on their children. The page is `(render ctx)`; layout is the render `:seon.ctx/ctx` declares, overridden like any render, on a branch, seen at once. Nothing is a special case, `@ctx` included.
3. **Two outputs of one hiccup.** HTML for the user (the theme map expanded into Tailwind utilities); the one printer's compact print for the model (semantic classes in hiccup shorthand, the theme map once, each component marked with its render function, cuts that say what they left and how to read it). The model gets the whole hiccup once, then diffs by component key.
4. **Replay is explicit.** Every eval is live in the sense that its tile shows its current value; whether a form is re-executed each turn (the screen's forms) or its recorded value is pulled is an attribute written when the form is set up, never inferred.
5. **Cache by value.** A render's result is cached by (render function, value); an equal value never re-renders; the patch's changed paths recompute along the path only. The content key of jolt/one-content-keyed-derivation is the candidate key.
6. **The two sides.** The screen side: tiles for evals with a render or marked replayed, plus ctx's task, proofs, open reasons and run. The chat side: prose, each eval collapsed and expandable. The model's screen is the screen side.
7. **Diffs never hide what matters.** "Unchanged" only for a part the model can still read in full above; anything the history cut dropped is sent whole again; the task's open reasons always whole.
8. **Caching is accretion.** Each request is the previous plus the new part, up to the screen's diffs; the history cut jumps in one deliberate step when the budget fills and then holds. Nothing already sent is ever rewritten mid-request.

## What it deletes
The page's separate walk, the screen's separate producer (`seon.screen/show` becomes the screen side's render), the request's own read of the records (`seon.context/records` becomes ctx's ordered conversation), `:seon.render/ai` faces wherever the hiccup serves. What stays: the history cut, the folds, the screen diffs, the one printer.

## Who does what
- agent view r3 + ctx-atom: the joint design and its build (render, layout, two sides, the model's print), with a mock of one real agent's page beside the exact text its model reads.
- related-sections: the screen's diffs and the history cut move onto the render of ctx; related search becomes a tile.
- agent loop: messages as entries in both ctxs; the stage boundary as a conversation entry; resume rebuilds ctx at a commit.
- live agents: measures each step (turns to green, cache share, tokens per turn, repeats) against today's numbers.
- stability lead: unaffected (zygote, one mode); keeps the render proc's isolation (a throwing render is its own error tile).
- root: vets the design against this note, gets an independent review, merges in slices.

## The acceptance test (owner, 2026-10-26)
"I want this to be something that will be adaptable and will work for any agent with whatever data they happen to be working with automatically rendered with our defaults and the agents can override them and it's simple to explain to the agents."
- Any value in ctx renders sensibly with no render declared (the one printer's hiccup).
- One override mechanism for a value, a key, a tile and the layout: a schema entry naming a render fn, registered on the branch, seen at once.
- The agents' explanation fits in a few lines on the help page; if it cannot, the design is too complicated.

## Simpler still (owner, 2026-10-26)
"We don't need a render function for everything in fact not rendering data we don't think is particularly relevant is helpful. We have the one value printer which can output both text for the ai and hiccup right?"
- The one printer is the default for every value: one pass into ops, `print/text` for the model and `emit/ops-hiccup` for the page, the same selection and cuts.
- A declared render is the exception, only where a value earns a custom view; its hiccup is drawn on the page and printed for the model.
- Not everything in ctx is shown: the layout and screen show what is relevant; the rest is one form away, named by a cut. Leaving irrelevant data out is a feature.

## Narrowed to the implementation (owner, 2026-10-26)
"I think the html(ctx) will remove details that the agent needs for the ai context so it isn't that formula"; "Make it so that the data dependencies are clear and we have good functions for processing the data".
`(ctx db agent)` → map (one pull, patched per transaction) → `(view ctx)` → ordered keyed parts (what is relevant, in what order; layout as data) → per part `(html part)` → hiccup → page, and `(ai part)` → text → model; both default to the one printer (ops-hiccup and text from one selection), a schema's `:seon.render/html` or `:seon.render/ai` overrides that face only; `(diff prev next)` by part key for both (rule 7); cache by (renderer, part value). The page may collapse details; the model's text carries what the agent needs within budget; they differ in detail, never in which parts.
