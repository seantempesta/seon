---
title: "Learning Seon: mined data, verified REPL scenarios, a context ranker, and RL versus supervised training"
type: research
status: lane learn-seon (Opus), 2026-10-19; measured on default (JVM-mode reads, SCI only as the non-root agents learn-seon-probe and learn-seon-env-1) and on the embed server; no production code
created: 2026-10-19
---

# Learning Seon

Lane learn-seon (Opus). The owner asked on 2026-10-19 whether we can train our own small decision model on data mined
from our code: contracts, generators and the call graph give a known path from input to output, and that path can
become REPL scenarios where agents run live code. The model would rank context for the next form ("dynamic context")
and teach agents to solve problems in the fewest forms. He also asked whether RL beats supervised training, and what a
LoRA on his laptop (M5 Max, 128 GB) would be trained on.

This page answers with what was measured today. The sibling lane learn-tools ([learn-tools.md](learn-tools.md))
covers which local models fit, MLX trainers and throughput, so none of that is repeated here. Scratch files are in
`tmp/learn-seon/` (gitignored); every number that matters is copied into this page.

## Broken things first

1. **This lane killed default with an OutOfMemoryError at 12:14 local (pid 58529).**
   - **What I ran.** A JVM-mode sweep of `malli.generator/sample` over all 3,769 loaded contracts' input schemas, with
     the live schema registry. Each sample ran in a `future`, and I gave up on it after 500 ms with `deref`.
   - **Why it ran out of memory.** A future that times out keeps running, so runaway generators piled up inside the
     4 GB heap. The schema resources declare 67 custom `:gen/gen` generators, some of which build class loaders and
     input streams.
   - **Evidence kept.** `data/heap/java_pid58529.hprof` (3.7 GB), `data/heap/hs_err_pid58529.log` and
     `hs_err_pid58529.jfr`.
   - **Recovery.** I restarted it with `bin/seon start`. HEAD bb678fb5a differs from the dead JVM's b224d0e50 only in
     docs. It was ready in 8.0 s as pid 9766, with the store retained.
   - **Rule from this.** A generator sweep over Seon's named schemas runs in a check JVM, never on default. The
     scenario generator must use plain-data generators or recorded real values (§2).
2. **A `def` whose initializer reads the database fails when the branch's program is installed again.**
   - **The probe.** As learn-seon-probe, I ran
     `(def sits (->> (seon.mine/situations (seon.db/db) :chain-806cdff35fd2) (take 20) vec))`. It answered its value.
   - **What failed.** The next request recorded an error from `seon.sci.eval/install-row!`: "Cannot interpret
     learn-seon.scratch/sits: seon.mine/situations refused argument 0 `main`: expected :seon.db/database-value ... got
     a map".
   - **Existing task.** This is the class filed as `seon.sci.eval/a-def-of-a-database-value-is-reinterpreted-with-its-render`.
     It reproduces with the database read inline in the initializer, not only through a `def` of a database value.
   - **Consequence for scenarios.** Scenarios bind their inputs with `let`, never with `def`.
3. **Two thirds of the post-nuke session data is gone, and what remains is small.**
   - Main holds 8 graded attempts, 2 of them green. Runs 7 to 9 went with the nuke and survive only as their evidence
     pages.
   - Any learned model trained on Seon-native sessions alone is data-starved today. §1 names the larger sources.

## Summary

| question | answer, measured |
|---|---|
| What can we mine now? | 7,615 declarations, 3,769 loaded contracts, about 16.4k program-internal call pairs, 3,407 test vars, 987 tasks (925 citing 2,843 functions), 8 graded attempts (295 situations), and 35,355 `eval_clj` calls by our own Claude lanes in 1,830 session transcripts |
| Can contracts generate inputs? | Plain-data contracts: 188 of a random 200 generate valid inputs, 0.1 ms each. 1,241 contracts need a live binding (database, connection, context), so they need recorded values, not generators. 1,879 use named Seon schemas and are untested here (see Broken 1) |
| Verified scenarios, end to end | 20 scenarios over `seon.mine/classify`, `seon.mine/rank` and `seon.mine/report`: all 20 verified on an SCI branch, 5 of 20 degenerate (empty or nil goal). One request ran all 20 in 509 ms (25 ms each); the forms alone took 0.2-50 ms |
| Episode cost (RL) | New agent branch plus first request: 149 ms. A warm trivial request: 42-46 ms, 23 MB. A `defn` request: 160 ms, 136 MB |
| Ranker on frozen EmbeddingGemma | Retrieving a task's referenced functions from its masked plan: frozen hit@10 0.143, MRR 0.069. A linear query head trained in under 1 s gives hit@10 0.315, MRR 0.159 when split by task, and 0.222 / 0.119 with whole namespaces held out |
| Where turns go | In the 8 kept attempts the first `defn` comes at call 6-27. Before it, 14-49 forms are reads and searches. The two greens spent 17 of 23 and 20 of 32 calls before their first write |
| Recommendation | First the ranker in `must-reads` (§3), then the scenario suite as an offline benchmark, then SFT, then DPO; GRPO last. Ranked plan in §6 |

## 1. What we can mine today

All counts were read on default at bb678fb5a in JVM mode, read-only.

### Inventory

| source | count | where | extraction effort |
|---|---|---|---|
| Declarations | 7,615 `:seon.fn/sym` (5,345 with docstrings, 4,459 with a stored spec) | main | none: datoms, about 0.8 s for all counts |
| Loaded contracts | 3,769 Vars with `:malli/schema` in `seon.*` namespaces: 649 plain data, 1,879 using named Seon schemas, 1,241 needing a live binding | JVM | minutes: input schema via `malli.core/children`, registry from `seon.schema/projection-from-database` |
| Generators | plain data: 188 of 200 sampled generate valid inputs (12 `:malli.generator/no-generator`), 20.8 ms for all 200 | JVM | trivial for plain data; named schemas only in a check JVM (Broken 1) |
| Static call graph | 144,033 `:seon.fn/calls` datoms (library calls included), 145,498 `:seon.fn/call-arities`; about 16.4k caller-callee pairs inside the program | main | none |
| Profile cells | live per-Var cells in the JVM (calls, self and total time, allocation, reads, steps; cold and warm halves); 79 warm and 36 cold `:seon.fn.cost` entities stored on main | JVM and main | none for counts. There are no caller-callee edges at run time (§1.2) |
| Tests | 3,407 test vars on main (3,580 `deftest` forms in 415 files), 14,908 `is` assertions, 1,424 `with-database` uses | main and `test/` | inputs are fixture code, not data. The values a test passes are visible only at run time (§1.2) |
| Tasks | 987 with a plan; 925 cite at least one program function, 2,843 citations in all | main | none: `:seon.task/references` |
| Kept agent sessions | 26 branches: 8 chain attempts (runs 10-11), 14 outside-agent branches, a merge branch and checkpoints. 1,070 distinct evaluations and 624 calls across them | store | `seon.mine/kept-situations`: 521 ms and 329 MB for all 8 attempts |
| Mined situations | 295 situations; 562 shown forms (read 208, probe 95, progress 60, search 56, error 50, navigation 35, re-read 22, unresolved 15, contract refusal 15, repeat 6); mean 664 shown tokens per step; 40 waste charges in 36 steps | derived | none |
| Grades | 8 on main: 2 done, 6 capped | main | none |
| Our own lanes' REPL sessions | 35,355 `mcp__seon__eval_clj` calls in 1,830 Claude Code transcripts: 21,232 SCI, 13,928 JVM, 195 other. 18.8 MB of code and 69 MB of replies; about 6.1k replies (17%) carry an error. By month: Jul 64, Aug 1,144, Sep 20,448, Oct 13,699 | `~/.claude/projects/-Users-sean-src-seon/` (5.7 GB) | 10 s to scan (`tmp/learn-seon/scan_transcripts.py`). The forms predate many API changes, so they are stale in a different way from code (§7) |
| Git history | 18,647 commits, 2,639 since 2026-10-01 | repository | not recommended as training data (§7) |

**What this means.**
- The live sessions are the only data where the agent saw Seon's own context and acted on it. Today that is 295 steps.
- The lanes' transcripts are 100 times larger. They hold the forms of competent agents, and each reply is what came
  back.
- The static program (contracts, call graph, task citations) is complete and costs nothing to read. It is the
  source for scenarios (§2) and for the ranker's labels (§3).

### 1.2 Recording an execution trace

**What exists.** `seon.profile/bracket` counts every armed call once per outermost entry. It also counts into the
evaluating world's call sink, keyed by `[sym digest]`. `seon.profile/measure` returns per-symbol calls, self time and
the calling thread's allocation.
- There is no caller → callee edge at run time: the bracket's `callee-frame` holds only the sums it needs for self
  time.
- There is no value: arguments are measured only as a size N on sampled calls.

**How big a full trace would be (measured armed calls):**

| call | armed calls | distinct Vars | allocated |
|---|---|---|---|
| `seon.mine/classify` of 8 forms | 17 | 3 | 126 KB |
| `seon.mine/rank` of 20 situations | 1 | 1 | 17 KB |
| `seon.mine/report` of 8 grades | 12 | 4 | 47 KB |
| `seon.mine/situations` of one attempt | 2,475 | 78 | 28 MB |
| `seon.mine/kept-situations` (8 attempts) | 19,363 | 90 | 263 MB |
| a whole SCI eval request (scenario requests) | 6,400-64,000 | – | 23-315 MB |

- A per-call event of about 40 bytes (two Var ids, an input digest, an output digest and a duration) costs 0.25-2.6 MB per
  eval request.
- Most of those calls are platform calls the request makes on the agent's behalf, not the agent's own computation.
  A full trace is the wrong grain.

**Cheap design.**
- **Fold edges.** Extend the world's sink to count `[caller callee]` pairs. The bracket keeps no caller identity today:
  `callee-frame` holds sums only, so this needs one more thread-local slot holding the current entry's symbol. That
  costs one map entry per distinct edge. The request above with 78 distinct Vars holds a few
  hundred edges, a few KB.
- **Values only at the form boundary, which already exists.** The evaluation stores the form and its printed value.
  The scenario generator wants the input value too:
  - an input that is a recorded value: a structural digest;
  - otherwise: the requery form that rebuilds it.

  Both are references, not copies. Recorded values are what the 1,241 live-binding contracts need instead of
  generators.
- **Recording cost.** It stays inside the bracket's existing two clock reads. Proof: `seon.profile/measure` on an
  ordinary request, before and after. Not built.

## 2. Scenario generation: the prototype

**What a scenario is.** A scenario is data:
- a goal, stated as the value to produce, with a one-line description;
- a starting context: the bindings it gives;
- the minimal form sequence that reaches the goal;
- the function it exercises.

It is verified by running the minimal form through the one REPL path, as a non-root agent on its own branch, and
comparing with the goal value computed independently in JVM mode. Compiled and interpreted evaluators therefore check
each other.

### What was run

- **Three functions:**
  - `seon.mine/classify`, a pure function of form text;
  - `seon.mine/rank`, a fold over situations;
  - `seon.mine/report`, aggregation and quantiles.
- **Generated inputs, from recorded real data:**
  - `classify`: 4-9 forms drawn at random from the 562 real shown evaluations (source, error, renderer);
  - `rank`: the first n (5-34) situations of a random kept branch, given as a requery form;
  - `report`: a random subset of 3-8 of main's real grades, given as a requery form by assignment.
- **Goals:** three projections per function. Examples: class frequencies; the sources classed re-read or repeat; the
  top producer; wasted turns summed; the median turns to green; attempts per commit window.
- **Cost of generating the 20, in JVM mode:** 351 ms (`tmp/learn-seon/scenarios.edn`).
- **How they ran:** as agent learn-seon-probe, `let`-bound, with each minimal form wrapped in `(= want form)`
  (`tmp/learn-seon/all-let.clj`).

### Results

| function | scenarios | verified | degenerate goal | form ms | form allocation |
|---|---|---|---|---|---|
| `seon.mine/classify` | 7 | 7 | 2 (`[]`, no re-read in the sample) | 0.19-0.87 | 0.1-1.1 MB |
| `seon.mine/rank` | 7 | 7 | 3 (`nil`, `{}`, `{}`: no waste in the first n steps) | 31-50 | 38-84 MB (reading the situations from the branch) |
| `seon.mine/report` | 6 | 6 | 0 | 1.0-1.9 | 1.4 MB |

**Cost per scenario:**

| how it ran | wall | allocation | notes |
|---|---|---|---|
| all 20 in one SCI request | 509 ms in total, about 25 ms each | 986 MB in total | |
| one scenario in its own request, `let` | 42-209 ms | 23-83 MB | |
| one scenario in its own request, `def` | 655 ms | 315 MB | stages: settle 272 ms, test step 160 ms, evaluate 127 ms |
| the first request of a new agent, which creates its branch | 149-780 ms | | |

A verified corpus of 10k scenarios therefore costs about 4-8 minutes of default's time in batched requests. Episode
cost is in §4.

### What the prototype taught

1. **Filter degenerate goals.** Five of 20 goals were empty or nil, and a model would satisfy them by guessing. The
   generator must refuse such a goal: one equal to a constant, or equal to what a neighbouring function in the call
   graph returns on the same input. That neighbour check also makes the scenario test whether the agent found the right
   function, which is the skill the live runs lack (§3).
2. **Inputs as requery forms beat literals.** The `rank` and `report` scenarios carried 100-400 byte requery forms
   instead of tens of KB of literal situations. That is the same rule as "references, not copies".
3. **Single-form scenarios test localization, not composition.** For a function f, the minimal form is
   `(projection (f input))`. Composite scenarios come from the call graph: take a caller g of f, set g's output as the
   goal and give f's input. Then either one form calling g, or several forms composing what g composes, both verify.
   The shortest correct sequence is the reward signal for "fewest forms".
4. **Coverage.**
   - **Plain data:** 649 contracts, 94% of which generate inputs.
   - **Named Seon schemas:** 1,879. Their generation must be measured in a check JVM first.
   - **Live-binding:** 1,241. They need recorded real values.

     Every eval request already stores its forms. Recording arguments by reference at the form boundary (§1.2) turns
     every live session into input material.

## 3. The decision model for dynamic context

### The cheapest model, prototyped

The ranker scores candidate context units for the next step. Its first job is the one with free labels: **given
a task's plan, which program functions will the work touch?** Each of the 925 tasks on main cites its functions. In
the plan text, each cited symbol is replaced by `…`, and the masked plan is the query.
- **Corpus:** the 7,534 declaration documents from embed-scale (`title: sym | text: arglists + docstring`, 768-dim
  EmbeddingGemma 2 bf16).
- **Script:** `tmp/learn-seon/retrieval.py`.
- **Cost:** embedding 924 plans took 2.9 s, and training took 0.3-1.5 s on CPU (numpy).

| model | split | test pairs | hit@1 | hit@10 | hit@50 | MRR |
|---|---|---|---|---|---|---|
| frozen, `query` prefix | by task (739 / 185) | 546 | 0.029 | 0.143 | 0.304 | 0.069 |
| frozen, `code` prefix | by task | 546 | 0.026 | 0.136 | 0.275 | 0.062 |
| linear query head W (768×768, init I, full softmax over all declarations, 10 epochs) | by task | 546 | 0.077 | **0.315** | 0.513 | **0.159** |
| same head, 30 epochs | by task | 546 | 0.035 | 0.152 | 0.310 | 0.078 (overfit: 0.60 hit@10 on train) |
| frozen, `query` prefix | **namespace held out** (745 / 179) | 532 | 0.039 | 0.148 | 0.310 | 0.080 |
| linear head, 10 epochs | namespace held out | 532 | 0.066 | **0.222** | 0.415 | **0.119** |

**Reading the table.**
- A head trained in under a second doubles MRR when split by task.
- When whole namespaces are held out, it improves MRR by half. The rest of the by-task gain was the head memorising
  which namespaces tasks live in.
- The namespace split is the honest number for a new area of the code, and both splits matter in practice: most
  tasks touch known namespaces.
- Training is fast enough to retrain on every merge.

**The call graph gives another feature at no cost.** For the 732 tasks citing two or more functions, I took one
cited function and asked whether another is near it in the call graph (9,748 ordered pairs, undirected program edges).
- Another cited function is within 1 hop 11.7% of the time. The 1-hop set has 17 members on average.
- Within 2 hops, 34% of the time, from a set of 275.

So once the agent has one owner (a proof's subject, the first `source` it reads), the graph neighbourhood is a strong
candidate set. Embedding score, graph distance from what the agent has already touched, unit kind, size in tokens and
age are the inputs of the real ranker. A logistic or small MLP head over them is the recipe in
[embedding/report.md](../2026-10-16/embedding/report.md).

### Why localization is the target

In the 8 kept attempts, the first progress form (`defn` and the like) came at:
- call 6, 11, 17, 17, 20, 22 and 27;
- never, in one attempt.

Before it, 14-49 forms were reads and searches. The two greens spent 17 of 23 calls and 20 of 32 calls before their
first write. Finding the owner is most of the turns to green.

The ranker attacks that phase. Gain is turns, so my estimate is a hypothesis until `(my.root/turns-report)` measures
it. If the must-reads hold a task's real owners at roughly twice today's rate, a third to half of the reads and searches
before the first write should go. That is 3-8 calls off a 23-32 call green.

### Training signal from sessions, once there is enough

- **Positive:** a shown unit whose symbol the next form names.
  - 178 of 295 situations (60%) have a next form naming a qualified symbol shown in the current step.
  - A unit read again later (`:re-read`) is a positive that was shown too weakly.
  - A unit read just before a green proof counts double.
- **Negative:** units shown and never named again before the attempt ends.
- **Waste:** the miner's charges (`:follows-cut`, `:re-read`, `:repeats-error`) label units that were shown too
  weakly or too strongly.
- **Volume:** today, 295 situations of mean 664 tokens over 8 attempts, which is too few to train on alone. The 35k
  transcript calls add the same "next form names a shown symbol" signal at scale, with Claude as the actor.
- **Split:** train by task, evaluate on held-out tasks and namespaces.

### How it plugs into `seon.context`

The provider caches the prompt prefix: 87-91% of prompt tokens in runs 9-11 were cached. Any ranker that reorders
or removes units already sent breaks that cache and costs more than it saves. So a ranking decision is made once, at a
point where the context is still new.

1. **At attempt start, in the cached prefix.** `seon.context/must-reads` already adds the functions a plan cites
   (`seon.context/plan-owners`). Add the ranker's top k uncited owners, read at the level `plan-owners` chooses.
   - Decided once, on `seon.context/attempt-start`'s database. It joins the attempt sections, which `block-views`
     lays before the history, so it is cached like them.
   - This is a render input, not a new mechanism: the ranker is an ordinary function of the task and the program,
     and its result is cached in `seon.cache` by content (task plan digest, program identity, model and head digest).
2. **When an exchange ages.** `seon.context/exchange-ai` decides once, when an exchange stops being newest, whether
   its reply stays whole or goes by reference (today: by size against `:seon.config.context/reference-tokens`). The
   predicted usefulness of that reply joins the size as a second input. That replaces a fixed dial with a learned
   one, at the point where the decision is already made once.
3. **In the turn tail, which is uncached anyway.** Next to the step section's refusals, offer the two or three
   best-ranked unread neighbours of what the agent just touched, as operations: `(my.program/source 'x)`. They are
   shown and never run, like every offered form.

Nothing here is a parallel renderer: each is an input to a render that already exists.

## 4. RL versus supervised

### The environment is cheap (measured)

| episode part | Seon (measured today) | SWE-style container (learn-tools, published) |
|---|---|---|
| reset: a fresh agent branch plus its SCI context | 149 ms (first request of a new agent) | seconds to minutes |
| step with no definition | 42-46 ms, 23 MB | 100 ms to seconds |
| step with a `defn` (settle, reaching tests) | 160 ms, 136 MB | seconds |
| reward | goal-value equality (scenario) or the task's proofs (`seon.cluster.source/done-refusals`) | the test suite |

**Environment design:**
- **Environment.** A non-root agent id per episode, which gives a branch of main and a fork of the shared SCI
  context: two pointers.
- **Episode.**
  - It starts from the scenario's starting context rendered by `seon.context/current-state`.
  - The actions are forms through the one REPL path.
  - The observation is the reply exactly as an agent sees it.
  - It ends at goal reached, `my.turn/wait` or the cap.
- **Reward:**
  - success: 1;
  - minus 0.05 per form;
  - minus 0.1 per agent error;
  - minus a cost term from the reply's `:seon.repl/ms` and `:seon.repl/alloc-mb` (10× over what the form should cost
    is wrong work, as everywhere in Seon).

  Real tasks use the same reward with their proofs.
- **Cleanup.** Ended branches are kept, by policy. Thousands of rollouts would grow the store, so rollout agents need
  `:seon.config.agent/keep-ended-branches?` false on their own config. Retention GC reclaims the rest. Branch cost per
  episode is not measured yet.

**The environment is not the bottleneck: model generation is.**
- A 30B-A3B local model at about 100 tok/s (learn-tools) writing 5 forms of about 80 tokens takes about 4 s.
- Prefill of a 4k-token scenario context at about 3,800 tok/s takes about 1 s.
- So an episode is about 5-6 s, of which the environment is under 1 s.

### Three ways to train

| | SFT on minimal traces | preference pairs (DPO) | GRPO-style rollouts |
|---|---|---|---|
| data | verified minimal form sequences from scenarios; green attempts; transcript forms whose next step succeeded | same scenario, k rollouts: the shorter correct one beats the longer or failed one; graded attempts of the same task | online: groups of 8 rollouts per scenario, advantage relative to the group |
| available today | scenarios: unlimited (25 ms each to verify); green Seon attempts: 2; transcripts: 35k calls, unfiltered | needs rollouts of a local model (none yet) or repeated attempts per task (the owner rules out repeated live runs) | needs a local model serving rollouts |
| compute | about 40-110 min per 1k 2k-token examples for a 7B (learn-tools estimate, not measured) | similar to SFT | a 64-scenario × 8-rollout step is about 512 × 5.5 s ≈ 47 min unbatched; 100 steps ≈ 3 days. Batched generation might cut that 4-8×, unmeasured |
| what it teaches | the REPL dialect, Seon's names, where things live (decays as code changes) | preferring fewer forms and cheaper probes | the policy itself, including recovery from errors |
| risk | imitates the reference sequence, not the search; memorises names | reward hacking on short wrong answers if goals are weak (5 of 20 were degenerate) | slow; reward sparsity on real tasks |

**Recommendation: the cheapest step with a measurable gain is not a model of the agent at all.** It is the ranker
(§3): no GPU, seconds to train, and it acts on the 60-75% of turns spent before the first write.

For the agent model, the order follows learn-tools' order, with Seon-specific data:
1. SFT on verified scenario traces plus filtered transcripts.
2. DPO on multi-rollout scenario pairs, once a local model serves.
3. GRPO only after both show a gain on the held-out scenario suite.

Offline data (mined chains, transcripts) seeds SFT. Online rollouts are cheap in Seon only because the environment
is, and they are still bound by generation.

## 5. Fine-tuning the agent model: data and evaluation

### Data, in order of value

1. **Verified scenario traces (§2).**
   - Unlimited and verified by execution.
   - Each comes with its context exactly as `seon.context` renders it, so the model learns from the context it will
     see.
   - The `chat` format of mlx-lm: system is help plus the attempt sections, each assistant turn is a form, each user
     turn is the reply.
   - Key every example by the definition digests it reaches (the profile sink's `[sym digest]` keys). When a digest
     changes, the example is regenerated, never reused. That is the same rule as test reuse by reach digest.
2. **Our lanes' transcripts.**
   - 21,232 SCI and 13,928 JVM `eval_clj` calls; Oct alone is 13,699.
   - Filter: drop calls whose reply is an error and was then repeated; keep calls whose value the session's next call
     used.
   - Re-validate every form against today's program: the form must still read, and its qualified symbols must still
     resolve. A form naming a retired symbol is dropped, not taught. The transcripts carry many retired words from
     September.
3. **Green Seon attempts.** 2 today, plus whatever each live run adds. Too few to train on. They are the held-out
   check that matters most.
4. **Not git history.** Commit diffs teach code as of a date, and Seon's code changes every day. Weights should learn
   the dialect, while the database supplies code at the right digest (learn-tools reaches the same split).

### Evaluation (turns metric, held out)

- **Offline, freely repeatable.** A held-out scenario suite split by namespace, with rename and paraphrase variants
  per the self-improvement loop's leak rules. It measures solved rate, forms per solved scenario, errors, ms and MB.
  It is a test of code, not a live run, so rerunning it breaks no ruling.
- **Live, each task runs once.** The owner rules out repeated live runs, so the model is assigned per task by a hash of
  the task id: half the next real tasks run the adapter, half run the base model. Compare
  `(my.root/turns-report)`'s turns to green and calls before the first write per arm. With today's two greens per run
  of four, a difference of 5 turns needs about 20-30 attempts per arm to be visible. That is 6-8 live runs.

## 6. Ranked plan, cheapest measurable step first

| # | step | cost | proof, red first | expected gain | metric |
|---|---|---|---|---|---|
| 1 | **Ranker in `must-reads`**: top-k uncited owners from the frozen embedding plus a linear head over the masked-plan training set, retrained at merge, cached by content | 1-2 days, Sonnet after this design; CPU only | a deftest: for the held-out tasks, the block's must-reads contain the cited owner at hit@10 ≥ 0.2 (frozen 0.148 today) | hit@10 for a new namespace 0.148 → 0.22; calls before the first write, a hypothesis of 3-8 fewer | `turns-report`, plus the miner's calls before first progress |
| 2 | **Edge fold in the profile sink** (`[caller callee]` per world) plus form-boundary argument references | 1 day, Opus (bracket is hot path) | `seon.profile/measure` on an ordinary request: no regression, and edges present | graph features for #1 and #3; real inputs for the 1,241 live-binding contracts | request cost unchanged |
| 3 | **Scenario generator v1**: plain-data and recorded-value inputs, the degenerate and neighbour filter, single-call and caller-composed goals, a requery form for each given; verified in batched SCI requests on a non-root branch | 2-3 days | a deftest over three functions: every scenario verifies, none is degenerate, each sibling's value differs | a benchmark of 1-10k scenarios at about 25 ms each to verify | solved rate, forms per solve |
| 4 | **Model benchmark on the scenario suite**: base local model (learn-tools picks) against DeepSeek flash, same rendered context | 1 day after #3 | suite numbers committed as evidence | tells whether a local model is good enough to roll out on | forms per solve |
| 5 | **Exchange-aging input**: predicted usefulness joins size in `seon.context/exchange-ai` | 1 day | a deftest that a reply named by the next form in the fixture chain stays whole | fewer `:follows-cut` and `:reads-handle` charges | the miner's waste by rule |
| 6 | **SFT LoRA** on scenario traces plus filtered transcripts | 1-2 days of data work, hours of training (learn-tools) | held-out suite, namespace split | unknown; must beat #4's base by a margin | suite, then the live A/B |
| 7 | **DPO** on multi-rollout scenario pairs | days | same | fewer forms per solve | same |
| 8 | **GRPO** | weeks of wall time on the laptop | same | only if 6-7 plateau | same |

## 7. Risks and mitigations

**Overfitting our own codebase.**
- *Seen today:* the linear head scored 0.60 hit@10 on train against 0.15-0.32 on held-out tasks. Half its by-task gain
  disappears when namespaces are held out.
- *Mitigation:*
  - Always report the namespace-held-out number.
  - Keep heads small (low rank or diagonal next).
  - Prefer features that generalise, such as graph distance and kind, over identity.

**Stale data after code changes.**
- *Problem:* a scenario, label or trace is true of the definitions it ran against.
- *Mitigation:* key each by the definition digests it reached and regenerate on change. This is the test-reuse rule
  (reach digest), not a new mechanism.
  - Rankers re-embed only changed declarations: embed-scale's content keys already do this.
  - Transcript forms are re-validated against today's program before use.

**Reward hacking.**
- *Problem:* 5 of 20 prototype goals were degenerate.
- *Mitigation:*
  - The degenerate and neighbour filter.
  - Goal values must not appear in the starting context, per the self-improvement loop's leak rule: context may name an
    owner, never an answer.
  - Paraphrased and renamed variants in the held-out suite.

**Prompt cache regression.**
- *Problem:* a ranker that edits sent context loses 87-91% cached tokens.
- *Mitigation:* rank only at decision points made once (§3).

**Store growth from rollouts.**
- *Problem:* kept branches are shipped true.
- *Mitigation:* rollout agents run with `:seon.config.agent/keep-ended-branches?` false. Measure branch cost per episode
  before any batch of more than 100.

**The JVM as an environment host.**
- *Problem:* generator sweeps and runaway forms can take default down (Broken 1).
- *Mitigation:*
  - Bulk generation runs in a check JVM.
  - Rollouts run as non-root agents with SCI's interrupt.
  - Rollouts never use host futures: a timed-out future keeps running.

**Small Seon-native corpus.**
- *Problem:* 295 situations.
- *Mitigation:* scenarios and transcripts carry training. Live sessions are kept for evaluation, where their
  realism matters most.

## Files

- `tmp/learn-seon/scan_transcripts.py`: the transcript census.
- `tmp/learn-seon/scenarios.edn` and `tmp/learn-seon/all-let.clj`: the 20 scenarios and their verifying forms.
- `tmp/learn-seon/task-refs.jsonl` and `tmp/learn-seon/retrieval.py`: the masked-plan retrieval set (924 tasks) and the
  frozen and linear-head evaluation. Run with `SPLIT=ns` for the namespace split.
