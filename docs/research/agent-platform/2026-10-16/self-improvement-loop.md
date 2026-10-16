---
title: "Context self-improvement loop: fewer model turns per task, measured on a benchmark that cannot be gamed"
type: research
status: owning plan, 2026-10-16 (recursive goal; learn from all gathered data, no repeated benchmark runs)
---

# Context self-improvement loop

Owner, 2026-10-16: "How can we setup a recursive self improvement loop with you running deepseek agents and tweaking
things in between runs to lower the amount of turns an agent needs to achieve their task? We can mine our tasks for good
examples and develop a benchmark. No cheating though and just putting the answers in the help text exactly! How can we
enforce that? Lets try self audits from gemini flash so we get an outside perspective."


Owner, later the same day:
- "I want recursive self improvement. That's the goal."
- "No we don't want to benchmark the agents several times. I want a way to use all the data we are gathering to learn
  from it. This needs to be recursive so it improves over time not a consensus that's expensive and we have limited
  changes."
- "No automatic re-runs in the system until we have shit under control and I say so."

So this plan replaces the earlier design, which ran a fixed task set several times with train, dev and held-out
splits. Nothing runs twice for measurement. Every real task runs once, as itself, merges as usual, and keeps its
branch. The data is everything those runs record.

## What exists

- **Kept branches.** Every ended branch stays as history (`:seon.config.agent/keep-ended-branches?`, shipped true).
  Each evaluation an agent made is on its branch: the form, the value's printed text and cuts, the selected render,
  errors, timing, and the agent's next form.
- **A grader for one attempt.** Lane bench, 912c9341e, built `seon.competency/grade`:
  - end and green;
  - model calls and calls to green;
  - forms by class (read, re-read, probe, progress, wait, navigation, search, repeat, unresolved, contract refusal,
    error);
  - tokens, wall time, evaluation time and allocation.

  It grades a kept attempt read-only in about 70 ms. Its repeat launcher and the 7-task set
  (`test/seon/competency/bench-v1.edn`) are not used by this plan.
- **First data point.** The live run's chain (`live-run.md`, attempt 5548298627d3) took 27 calls, 16 of them spent
  adding a require to an existing `ns` form.
- **The render bench** (lane render-bench) measures each render on pinned real samples: tokens, cuts, cost, text
  violations. It is the proof row for render fixes. It is a deterministic measurement of code, not a model run.

## Learning from every step, not from repeated runs

A run is not one data point; each step is. One chain of 27 calls holds 27 decisions, each with the context the agent
had and what it did next. That is why repeats are unnecessary: the signal is per situation, and situations recur
across different real tasks.

1. **Record situations.** The miner reads every kept branch as of each evaluation. It writes one situation record per
   step: what was shown (value schema, selected render, tokens, cuts, errors, which references the context resolved)
   and what the agent did next. It runs over data only, in milliseconds per attempt, and never starts a model call.
2. **Attribute waste to a cause.** A step is a wasted turn when the next form:
   - follows an elision form or reads the same handle (a cut hid what was needed);
   - re-reads something already shown, or calls `doc`, `source` or a requery on it (the render or context left it
     out);
   - repeats a refused or erroring form (the error did not say how to fix it);
   - searches for an owner the plan could have named (the task text was untyped).

   The cause is the producer that made what was shown: a render, a printer cut, a docstring, an error constructor,
   help text, a task template. Charging is deterministic from the data.
3. **Rank causes** by wasted turns, summed over all recorded steps and weighted by how often the situation occurs.
4. **Fix the top cause** as a task from a template (below), proven by its red deftest. It runs once as real work and
   merges.
5. **Learn whether it worked from later real work.** For each cause, the miner tracks its waste rate per occurrence by
   commit: wasted turns per occurrence of that situation, before and after the fixing commit. The occurrences come
   from whatever real tasks run next. The headline trend is turns per task and calls to green over time.
   - A fix whose rate does not fall is reopened, with the new evidence in its task.
   - Nothing is re-run to check it.
6. **New causes come from the unexplained.** Waste no current cause explains is clustered: embed the step's shown
   context and next form with EmbeddingGemma 2 (`embedding/fast/`), then group the steps by similarity. A recurring
   cluster becomes a new named cause, and the class list grows from the data instead of being fixed up front.

Each fix is targeted at a measured cause and checked by that cause's rate on later, different tasks. That costs
nothing beyond the work we were doing anyway, and the signal accumulates with every task.

**Honesty.**
- A fix must be general: it changes a producer for a class of situation, never text that answers one task. The leak
  rule stays: context may name a concept's owner, never a task's solution.
- Because a fix is judged on later tasks it was not written for, overfitting to one task shows up as a rate that
  does not fall.
- A render that hides everything gets no cuts, but the dig rate on later runs catches it.

## Recursive: Seon improves Seon

Owner, 2026-10-16: "I want recursive self improvement. That's the goal." The loop above is the first stage: root
optimises and lanes build. It becomes recursive in three steps. Each step happens only when the previous one
lowers the waste rates on later real work faster than its predecessor did.

1. **The fixes are Seon tasks.** Each ranked cause becomes a task through `seon.task/chain!`, one per render or
   producer, so the lanes are file-disjoint. Its proofs are the render bench row on the pinned real samples (no cut,
   cost within its budget) plus a test of the information the agent needed. DeepSeek chains work them, and root's
   ordinary merge accepts them. General printer changes stay with our lanes. The agents fixing the context work
   inside the context they are fixing, and their own runs are mined in the next round.
2. **The loop's machinery is in scope.** The miner, the ranker, the task template that turns a cause into a task, help
   and the step templates are ordinary Seon code and data. A change to them is judged by the same signal: the waste rates
   and turns per task on later real work.
3. **Seon agents optimise.** A Seon chain takes over choosing and specifying the fixes: it reads the ranked causes and
   writes the task with its red proof and predicted saving. Root keeps merging, and the owner keeps the fixed points.

### The first template: a clipped output (owner, 2026-10-16)

Deftests are the mechanism that advances tasks: a task's red proofs guide its agent, and its fix is code updates
and/or data updates. There are no question tasks and no answer verb.

The miner finds calls whose output the printer cut, across all sessions on all branches. Each case becomes one task
made from one template, with these references:
- the function;
- its selected render;
- the pinned sample values;
- the sessions where agents saw the cut output, each as an evaluation on its branch at its `t` (read through the
  `:seon.ref/at` tools).

Its proof is the render bench row on those samples: the render's output is not cut, and its cost stays within budget.
That row is red today. Every existing printer law test stays green.

The agent reads the render, the printer's general rules and those sessions, then makes the row green by one of three
moves:
- **A render code change.** The render selects what the agents needed and offers elision forms for the rest.
- **A data update.** The cut is good (great output, with elision forms that say how to read more), so the agent
  records that on the render's own declaration, as an attribute the bench row reads.
- **`my.task/send-back!`.** Use it, with a reason and a counterexample, when the test is wrong or the task should not
  be done. That includes when the right fix is in the printer: the printer is compiled platform code that a chain
  branch cannot prove, so root takes that change to a lane that proves it through `bin/seon check`.

**The good-cut attribute is judged in the data.** The miner shows whether agents still needed to dig after a cut
marked good, and a case that keeps costing turns comes back up. Since the declaration is the attribute's owner, it is
not a list kept beside the proof. A changed render is a new declaration, so the verdict is judged again; nothing has to
be invalidated by hand.

**Instructions the template carries:**
- the value printer stays general, with no key or schema named in it;
- its token budget is a fixed dial;
- all specificity belongs in render functions;
- renders return data.

Fixed points, which no optimiser changes:
- the miner's waste definitions and the grader;
- the leak rule;
- the call cap and the model;
- the CLAUDE.md laws;
- no automatic re-runs.


## Phases

| Phase | What | Proof |
|---|---|---|
| M1 | The miner: one situation record per step over every kept branch, read-only, with the shown context, the cause candidates and the next form | a deftest over a fixture chain: the records name each step's render, cuts and next form; the cost per attempt is measured |
| M2 | Waste attribution and ranking by cause; the per-cause rate by commit | a deftest: the live run's 16 ns-require calls are charged to one cause |
| M3 | The first template, a clipped output (above); the miner files its tasks through `seon.task/chain!` | a filed task's red bench row turns green by its fix |
| M4 | Unexplained waste clustered by embeddings into proposed new causes | the clusters over the kept branches, reviewed by root before any becomes a cause |
| M5 | A Seon chain chooses and specifies fixes | its fixes lower the waste rates at least as well as root's did |

Today the corpus is small: one model attempt with 27 calls, plus whatever the next real tasks produce. The first
real tasks to run are the known fixes, which are ordinary work anyway; their runs are the corpus.

## Research notes

(Kept from the earlier benchmark design; the leakage notes still apply to the leak rule.)

- **Eval-to-prompt leakage is the known failure.** Few-shot or help content drawn from the eval set raises the score
  without raising real performance. Keep a locked test set separate from what feeds the prompt, and use fresh unseen
  cases for confirmation.
- **Canaries have known weaknesses.** A canary GUID can be stripped, and a model can memorise the canary itself. That
  is why the leak test also fingerprints the answer itself, not only the canary.
- **Format overfitting.** Gains that depend on surface cues can be exposed by scrambling non-semantic fields
  (identifiers, order) and paraphrasing while preserving the answer. That is the rename/paraphrase variant rule.
- **Held-out splits that are not publicly accessible** (SWE-bench Pro: public / held-out / commercial) are the
  standard defence for agent benchmarks, and harness and scaffold differences move scores a lot. So the harness here
  is fixed, and only context producers vary.

Sources:
- [CapBencher: built-in alarm for test-set overfitting](https://arxiv.org/pdf/2505.18102)
- [Forget What You Know about LLM Evaluations (format/prompt overfitting)](https://arxiv.org/html/2502.07445v1)
- [The eval set that started leaking into your prompt](https://tianpan.co/blog/2026/06/01/the-eval-set-that-started-leaking-into-your-prompt)
- [When your test set leaks into fine-tuning](https://tianpan.co/blog/2026-05-17-test-set-leaks-into-fine-tuning)
- [SWE-bench Pro paper (public / held-out / commercial splits)](https://web3.arxiv.org/pdf/2509.16941)
- [SWE-bench contamination debate](https://codesota.com/news/swe-bench-contamination-debate)
