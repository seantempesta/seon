# Cost bounds on contracts: a lab on the render pipeline (2026-10-26)

**The question** (the owner's idea, root's brief): can a function's contract carry a cost bound, a plain function from its actual arguments to worst-case ticks and bytes? Three uses: (1) a generative cost test checks the bound against what the function measures on generated inputs; (2) a caller's bound is composed from its callees' bounds; (3) a render draws the call graph with each node's bound.

**Where it ran.** Lab code only, nothing in src. A worktree root on main 92ef28a976, which holds build 2 (`seon.ctx/changes`), through root's REPL. The code is in notes/cost-bounds-lab/ (lab-bounds-1 to 6, loaded in order). The db was the lab root's main: 3,048 vars and 420 sections, with agent sessions added by `d/db-with`.

## What worked

**1. A bound that sketches the algorithm, checked generatively.** The bound for `seon.ctx/changes` (lab-bounds-1, lab-bounds-2) is the walk's own sketch, `lab-walk-of`. It counts:
- the keys visited where the two sides are not `identical?`;
- the elements compared by `=`;
- the changes and their paths.

Each count is priced at a calibrated per-step cost. The inputs came from a test.check generator of ctx-shaped records (evals, runs, tasks, cited vars with forms, in ctx's own sorted maps), each followed by 0 to 5 edits that share structure the way the patch does: a new eval, an eval edited, the plan edited, a cited form redefined, an eval gone.

| check | cases | measured / bound, ticks (min, median, max) | bytes (min, median, max) | over |
|---|---|---|---|---|
| bound priced from `seon.cost/units` | 60 | 1.6, 3.9, 7.3 | 3.9, 7.0, 1008 | most |
| bound with measured per-step constants | 200 | 0.12, 0.28, 1.0 | 0.24, 0.36, 1.0 | 0 |
| mutation: `changes` with no `identical?` skip, same bound | 200 | 0.16, 0.66, **1,173** | 0.24, 1.3, **1,253** | red |

The test can fail: the mutation is caught at about 1,000× over. The sound bound is about 3× loose at the median, because its per-key constant was calibrated on a 1,000-member collection.

**2. Composition predicts.** Today's later-turn request (`seon.context/world`) is `build` at c0, `advance` to c1, and `page` at both. Each piece's bound is linear in the session's measures (evals, cited vars, runs). It was calibrated on four shapes: a base, plus evals, plus cites, plus runs. world's bound is the sum of its callees' bounds (lab-bounds-5).

| held-out shape (evals, runs, cites) | world ticks: bound / measured | bytes: bound / measured | measured / bound |
|---|---|---|---|
| 40, 1, 20 | 8.81M / 8.81M | 49.1 / 49.1 MB | 1.00 |
| 100, 5, 15 (a1's size) | 9.50M / 9.74M | 51.1 / 52.1 MB | 1.02 |
| 200, 3, 40 | 20.4M / 17.6M | 112.9 / 99.7 MB | 0.86 to 0.88 (the eval window caps build) |
| 25 random shapes, generated | | | ticks 0.83 to 1.08, bytes 0.84 to 1.07; none over 1.25 |

At the same shape, the design's per-change path costs about 20k ticks and 0.1 MB, flat in every shape measured: `patch` 15k to 25k ticks, `changes` 4k to 6k ticks. That is about 1/450 of today's world.

**3. The render.** `lab-graph-hiccup` (lab-bounds-6) is the graph as hiccup: each node's bound at a chosen shape, and the total at its root. It is drawn by the one printer, as text for the model and as HTML via `seon.web.html/html`.

Editing a bound (page0's bytes per cite set to 0) changes the next draw: world goes from 51.1 to 36.6 MB, with nothing to invalidate, because the graph is a function of the bounds. This is the REPL loop the owner described: edit the sketch, see the total.

## What was hard
- **Unit-op pricing is not a bound.** `changes` costs 927 ticks and 2,980 B for each key it visits. That is several sorted-map gets through a comparator fn, the `members?` scan of a node's values, and `distinct` over `concat`, against about 60 ticks priced from the unit table. Each change costs 3,788 ticks and 30.8 KB, mostly its sort key (`str/join` of the printed path). The real constants come from measuring the function's own steps on cases that isolate each one, which is what the cost proofs' `:per-n` already does. The bound states the shape; measurement gives the constants.
- **A db argument has no generator.** `:seon.host/db` is a predicate, so `mg/generate` cannot make one. Generating meant writing a domain generator of sessions (its shape: evals, runs, cites) over a seed db. The bound of a db function is then written in that generator's measures, not in the db value. Anything the generator does not vary is invisible. Against a1's own measured world (302 MB a turn, at about a1's shape), the composed bound at (100, 5, 15) says 51 MB, 6× low. a1 has what the lab's sessions lack: related search over vectors, proofs with their test source, and large eval values.
- **A cache is a hidden input.** `page1` costs a quarter of `page0` on the same shape (`render/html`'s memo reuses page0's parts). A bound over a function with a cache must take the cache's state as an argument. Otherwise it is a bound on the cold call only.
- **Composition is over measures, not values.** A caller passes its callees values it computed (build's record to page). To compose without running them, each bound must also answer the measure of its output. Here the session's shape served throughout.
- **Root's REPL:** a `(do (load-file …) (f …))` is analysed before load runs, so they are two evals; `sys` is the eval's local, so main's db is held in a var first. Both are known (render-investigation finding 15).

## Found in passing (filed, not fixed)
- **The page can pick a run that holds only a stub.** After build 2 holds a task once, its other runs hold `{:db/id}` stubs. `seon.render`'s page takes the newest run by its first transaction (`render.clj`, `run (last (sort-by t-of …))`) and reads its `:task`. Runs made in one transaction tie on that order, so the page can pick a stub run. `seon.task/task` then gets nil and its contract throws. Reproduction: lab-session with `:r 3` and its runs in one `d/db-with`.
- **Production is safe today:** runs made one transaction each draw for 2 to 6 runs. The fix is the page resolving a stub through the record's `:ix` (or picking the task's holder), never assuming the newest run holds it. This is ctx-atom's (build 2).

## The shape I'd propose
- `:seon.cost/bound` in the attribute map beside `:malli/schema`: `(fn [& args] {:ticks :bytes :out m})`. `:out` is the measure of the answer (counts, sizes) that a caller's bound passes on to the next callee.
- Its terms are the algorithm's steps (keys visited, entities reached, changes made), each step's constant measured once on an isolating case. This replaces the hand-picked `:sizes` and `:per-n` of a cost proof, not the proof itself.
- A domain generator per input kind is the one place a db-taking function's inputs come from. Its measures name what the bound may depend on (session shape, values' bytes, vectors present).
- The generative cost test is `cost/proof` over the generator: measured/bound ≤ 1.25 on every generated case, plus a looseness report (the median ratio).
- The graph render is a view over the bounds, so editing a bound redraws it at once.

## Not measured
- Bounds for agent view's walk. Its lab code and the ratom port were not loaded; render-design's per-change table (25k to 518k ticks) stands as theirs.
- Composition through a real stub, a callee replaced by a contract-valid function returning its bound's `:out` measure. It is designed above, not run.
- a1 itself. Its 6× gap is explained by the inputs it has and the lab's sessions do not, not verified on it.
