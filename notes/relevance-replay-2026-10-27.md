# What to surface each turn: a replay of recorded runs (2026-10-27)

The owner: "optimize via ranking what other rendered context we are showing based on its likely utility". This replays the agents' recorded turns and asks which query, ranked by the shipped relevance, would have surfaced what each agent needed next.

**Recommendation.** Rank the page's other context with relevance against the plan plus a decayed trajectory of the agent's own evals (half-life one turn; three ties). Show items entering at 60% and leaving below 40%, ordered by when they entered. Cap the part at about 800 tokens. Do not add the touched items as seeds. Expect about 7 items a turn and about 1 changing a turn, a ~130-byte diff where a re-render costs ~1 KB. The trajectory's gain over the plan alone is within noise on this data; its case is that it follows the agent when the plan stops describing what it is doing, at the same cost.

## Data and method

- **Runs.** The standing store, read only through root's `seon.root/branch-db`: the 10 runs on main (0.7's tasks; `seon.server/seon-agents-answers-what-is-live` twice). Each run's own evals are on its branch: 99 agent turns. The blind agent's a7 was not on main's runs, so it is not in this replay.
- **Candidates.** Each kind's relevance pool, as `seon.context/relevance` makes it: the 100 nearest by the query, the plan's cited vars and their graph neighbourhood. Program functions, wiki sections and schema keys; tests and tasks are not ranked yet. One list across kinds, ordered by chance (the judged calibration of docs/vector.md#relevance).
- **Label.** At turn t, what the agent named in its code at turns t+1..t+3 that neither the plan nor its turns up to t had named. The agent's own API and workflow are left out: `my.*`, `seon.turn/*`, `seon.agent/db` and `state`, error, task and run keys. Unfiltered, they were the commonest names, `seon.turn/hand-back!` 23 times and `my.agent/prove` 16. 44 turns have a label left; 28 of 129 labels were tests, which nothing ranks.
- **Queries.** All are scored by the shipped five features and weights, with the query vector and its words varied:
  - q1, the plan;
  - q2, the last eval (its code and result line, embedded as a query);
  - q3, Σ 0.5^(age/h) over the evals so far, h = 1 or 3 turns;
  - q4, the plan plus q3;
  - and the same with the named items given as touched (relevance's zt, and as seeds).

## Results (44 labelled turns)

| query | R@5 | R@10 | precision at 0.6 (lines a turn) | recall within 100 / 200 / 400 / 800 / 1,600 / 3,200 tokens |
|---|---|---|---|---|
| q1 plan | 0.358 | 0.402 | 0.13 (7.3) | 0.22 / 0.37 / 0.45 / 0.48 / 0.48 / 0.53 |
| q2 last eval | 0.343 | 0.373 | 0.11 (5.8) | 0.27 / 0.31 / 0.40 / 0.51 / 0.55 / 0.60 |
| q3 decayed, h 1 | **0.377** | **0.441** | 0.12 (5.6) | 0.28 / 0.31 / 0.46 / **0.56** / 0.58 / 0.63 |
| q3 decayed, h 3 | 0.369 | 0.441 | 0.12 (5.8) | 0.29 / 0.31 / 0.47 / 0.56 / 0.58 / 0.61 |
| q4 plan + q3, h 1 | 0.358 | 0.420 | 0.12 (6.5) | 0.26 / 0.37 / 0.45 / 0.47 / 0.57 / 0.60 |
| q4 plan + q3, h 3 | 0.366 | 0.420 | 0.12 (6.5) | 0.27 / 0.37 / 0.45 / 0.48 / 0.57 / 0.61 |
| q4 h 1, touched | 0.266 | 0.349 | 0.08 (12.4) | 0.21 / 0.32 / 0.37 / 0.47 / 0.62 / 0.68 |
| q1, touched | 0.368 | 0.373 | 0.08 (13.3) | 0.17 / 0.36 / 0.39 / 0.49 / 0.53 / 0.62 |

**Churn of the shown set** (at 60%; tokens ≈ name and first sentence / 4):

| query | rule | shown a turn | units changed a turn | diff a turn | re-render a turn |
|---|---|---|---|---|---|
| q1 plan | plain cut / hysteresis | 6.3 | 0.8 | 110 B | 791 B |
| q3, h 1 | plain cut 0.6 | 4.7 | 4.2 | 271 B | 544 B |
| q3, h 1 | hysteresis 0.6 / 0.4 | 6.7 | 1.4 | 174 B | 835 B |
| q4, h 1 | plain cut 0.6 | 5.7 | 4.1 | 242 B | 703 B |
| q4, h 1 | hysteresis 0.6 / 0.4 | 7.0 | **1.0** | **127 B** | 985 B |

What the tables say:
- **The queries tie at 5.** At 10 and past 400 tokens, a trajectory finds more: 0.56 against the plan's 0.48 at 800 tokens. The plan alone saturates by 400–800 tokens; a trajectory keeps finding up to about 1,600. 44 turns cannot separate differences of 0.02–0.04.
- **Touched items as seeds hurt.** The graph around what the agent named floods the 60% band (12–13 lines a turn) with its neighbours.
- **Hysteresis is what makes a moving query byte-stable.** A plain cut on a trajectory changes 4 of ~5 lines every turn; entering at 60% and leaving at 40% changes 1. A turn's diff is about an eighth of a re-render.
- **Precision at 0.6 against this label is 0.12.** An agent names few of the lines it would have found useful. On judged usefulness, the same 60% band was 0.80 useful on fresh pages (docs/vector.md#relevance). The two labels bracket it.

## Not measured, and what would move it

- More runs. 44 labelled turns from 7 tasks is the whole recorded set.
- Tests as candidates: 22% of the next reads were tests, which relevance does not rank yet.
- Whether showing the lines changes what an agent does (a paid comparison).
- Embedding each turn costs one `:embed` request a turn (~25 ms); the trajectory is a weighted sum of vectors already embedded.

Scripts and data: `notes/relevance-lab/replay*.py`, `churn.py`, and the reads `r3.clj`-`r5.clj` (read only, root's REPL on the standing store).

## The selector on a live REPL agent (2026-10-27, evening)

Built as `seon.context/selected` (the page's related part and `(my.task/related)`): units at 60%+ against `seon.context/focus` (the plan, then each eval, decayed with a half-life of one item, so the plan is a prior the agent's work takes over), appended in entry order, never dropped, under 800 tokens. On a replay variant the plan-decaying-first focus scored R@10 0.441 and 0.55 within 800 tokens (plan alone 0.402 / 0.48; plan held at full weight 0.411 / 0.49).

Worked through my root as an outside agent (a2) on `seon.ref/an-alias-is-resolved-in-its-namespace`, the blind agent's own first evals replayed:
- **Cold** (the plan alone), two units entered: `docs/render.md#cuts-in-a-display-namespace-that-are-not-cuts-of-output` (69%) and `seon.context/similar` (60%), both noise for this task, and append-only keeps them.
- **After two turns** (sources of `seon.ref/references`, `candidate`, `names`, `span-candidate`, `names-on-db?`), the focus followed the work: the best now were `seon.render.ui/cited-of` 55%, `seon.ref/names-on-db?` 54%, `seon.ref/alias-form?` 51%, `seon.ref/candidate` 48%. That is what the task needs; the blind agent found these by hand. None reached 60%, so the page added nothing.
- **Cause:** the chance's calibration was fitted on plan-shaped queries (the judge's pages). A focus made of code-heavy evals scores lower on that scale, so a fixed 60% is too strict once the agent works, and too loose for the plan's cold guess.
- **Next:** calibrate the chance under the trajectory focus itself, with the judge labelling each kind's first five at replayed turns, and fit it per kind as the plan-focused table was. Then re-test on this REPL agent, then live agents.
