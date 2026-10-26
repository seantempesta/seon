# The context's known problems, from the last live run (design-build, 1/10 merged)

These are the problems a paid run must not start with. They come from the db1 run on main f564b314c (thinking off, the old ten requests): 1 of 10 merged, 6 capped at 30 turns, 1 failed itself, 2 hung. The evidence is each run's turns, read from a copy of the run's store: the dumps `scratchpad/db1-dump/*-{first,last,turns}.txt` and the readers' analyses `scratchpad/analysis-{A,B,C,D}-*.md`. scratchpad is `/private/tmp/claude-501/-Users-sean-src-seon-next/2489cf55-c241-4f3c-b807-be3323bdfd1f/scratchpad`. The full analysis is [context-for-20-turns-2026-10-26.md](context-for-20-turns-2026-10-26.md).

Each problem is stated at its producer: the request set, the page's pull or render, or the template's text. Its owner is in brackets.

## P1. Four of the ten requests asked for work main had already done [the request set: this branch, done]

- **The requests:** transport (aed2344517 made it true), proof-never-ran (`seon.context/turn-state` answers `:unknown`; `context_test` :1067), long-name and map (`print_test` :343 and :335 pass).
- **Cost:** 112 of the 273 turns.
- **Evidence:** long-name turn 20, "With a handle it already works"; turn 22, "Both pass". map turn 25 ran the existing test green, then invented a different bug. proof-never-ran turn 3, "turn-state looks right: no result -> :unknown", then 27 more turns.
- **Cause:** the request set (written 2026-10-24) was never checked against main again.
- **Fix:** `seon.fixtures/requests` is ten requests, each with a reproduction run on main before use (this branch, 02cd1f209), and a template test holds the shape. Before each paid run the reproductions are evaluated on main again; one that answers right is replaced.

## P2. The page did not show the tests that already cover the cited vars [the page's pull: D1, jolt/the-page-holds-what-the-work-needs]

- **Evidence:**
  - long-name, map and proof-never-ran: their own green tests were not on the page, and each agent found them only after 22 to 25 turns.
  - short-history: had to find the `kept` tests (context_test :282) at turn 27.
  - mixed: never saw `rows-show-whole-then-one-cut` or `problem-rows` (print_test :248, :256).
  - summary: never saw the test that pins the line format (context_test :929), and broke it.
- **Cause:** the page cites vars by name only. Their callers among the deftests (`my.agent/callers`) and those tests' status are not pulled.

## P3. The page cited names, not code, and sometimes the wrong names [the page's pull and the related search: D2 and D5, the-page-holds-what-the-work-needs; related search: agent view]

- **Evidence:**
  - mixed cited `seon.print/seen`, but the site is `seon.print.select/fill-rows`.
  - summary cited `seon.ref/shown`, which is already right; the site is `seon.context/related-view` (found at turn 19: "Now I see the real gap").
  - proof-never-ran did not cite `turn-state`.
  - Every run fetched each source itself, and private vars cost 2 turns each.
- **Cause:** the related search ranks by meaning over the plan's words, not by what draws the output the plan describes. The cited vars carry no source.

## P4. Cited doc sections were addresses, and no single form reads one [the page's pull: D3; the help page]

- **Evidence:**
  - short-history: turns 2–26 went to reading one section, through `seon.ref/lookup` (it does not exist), `parse` arities, and 19 turns hunting for a db handle.
  - long-name: turns 0–10. map: turns 11–22 ("I'm spending too long on docs plumbing"). transport: turns 3–8.
- **Cause:** the page holds the section's address, not its text, and the help page names no working call.

## P5. The request carried no reproduction [the request set: done in P1; the page: D4]

- **Evidence:** map and mixed spent 9 turns each guessing inputs. The task entry on disk carried map's reproduction (`seon.print.edn:14`), but the page showed the one-line request.
- **Fix:** every request in the new set carries its reproduction line (P1). When a request comes from a task entry, its full plan goes on the page (D4).

## P6. Agents read on past what they needed, in the wrong order [the template's text: this branch, done]

- **Evidence:** short-history had `kept`'s source at turn 1, proof-never-ran its answer at turn 3, long-name a reproduction at turn 12 and mixed one at turn 15. None of them wrote the proof. mixed patched without a red proof, on the wrong branch.
- **Fix:** the `:whole` and `:work` texts (fee9d3b2d, af581e1f1) say: reproduce first; if the output is already right, `fail!` at once; once it is wrong, stop reading and write the deftest red. The template test asserts each step and their order.

## P7. Proofs built on the live db and on hash order [the template's text: this branch, done]

- **Evidence:** summary's proof read `docs/source.md` from the live store, and its assertion did not match its own fix. map's proof was red because of `zipmap`'s order, not because of the floor.
- **Fix:** the texts say to build the input as the function's existing tests build theirs: never the live db, and vector literals where order matters.

## P8. Agents hung after their last turn [full-run-reds]

- **a10:** blocked on a test child (89901) that stayed alive with a server listening and three LMDB envs open. This was most likely the judge's wide run after the cap, a test starting a server in-process.
- **a6:** idle in `kevent`, with no child and no TCP fd, waiting on root.
- **Evidence:** `scratchpad/measure/db1-hang/`, sent to full-run-reds.
- **Open.** A paid run waits for facts, never a timer on the wait (AGENTS §4):
  - for a10, the forked-child fix (436e6e5213), verified on main;
  - for a6, either its wait on root fixed at its cause, or the stall work (jolt/agent-loop: the stall fact, the `:stalled` problem, the stall task) on main, so that a hang is seen and becomes root's task instead of spending unnoticed.
- **Holds now:** neither. This line is updated when one holds, naming which.

## P9. A whole task's refused run was held, not told why [the merge gate: this branch, in progress]

The judge sent a verdict only to a task that does not merge itself, so a `:whole` task that ended red was held for root and its session never heard why. The judge also selected only the branch's own tests, so a branch that deletes one of main's tests is not judged by it.

**Fix (this branch):**
- a whole task's refused run gets its verdict;
- the judge runs main's tests that the branch removed, from main's definition;
- the page shows `:merge` readiness each turn.
