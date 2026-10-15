---
title: "Context self-improvement loop: fewer model turns per task, measured on a benchmark that cannot be gamed"
type: research
status: proposal for the owner, 2026-10-16
---

# Context self-improvement loop

Owner, 2026-10-16: "How can we setup a recursive self improvement loop with you running deepseek agents and tweaking
things in between runs to lower the amount of turns an agent needs to achieve their task? We can mine our tasks for good
examples and develop a benchmark. No cheating though and just putting the answers in the help text exactly! How can we
enforce that? Lets try self audits from gemini flash so we get an outside perspective."

## What exists (measured, not planned)

- **The bench machinery.** `seon.competency/launch!`, `seon.competency/grade` and `seon.competency/table` in
  `test/seon/competency.clj` (BENCH-1, BENCH-3; `docs/seon/schedule/cards/README-bench.md`). The design and its
  strategy dials (S1-S8) are in `docs/research/agent-platform/2026-10-08/competency/competency-spec.md`.
- **Noise floor (E0).** One cell, T1 typed × 5, varied by 2 of 5 green across batches. So a 5-run difference of 2 is
  noise; 3 or more of 5, or non-overlapping ranges of calls to green, is signal.
- **Cost (E0+E1).** 65 DeepSeek-flash runs cost US$0.099 in total.
- **Prior result (E1).** Typed task wording beat prose, and became the default.
- **Live corpus.** Only 4 model attempts survive (`context-patterns.md`, beba51b31), because branches were deleted at
  publication. Lane keep-receipts is making every ended branch kept by default (owner ruling 10-16).
- **Where the turns go.** The classifier dataset is `context-patterns.edn`. Known waste:
  - history elision drops a just-read source, which the agent then re-reads;
  - `in-ns` churn;
  - `dir` lists no deftests;
  - unrun proofs were shown red.

  Lane context-fix is fixing the top items now.
- **Task stock.** About 929 filed tasks in 106 `resources/seon/tasks/*.edn` files, plus git history full of
  "fix + regression test" commits.
- **Outside auditors.** Gemini via `bin/agy-agent` (`.agents/skills/agy-lanes/SKILL.md`). Flash-high is the measured
  best auditor: in the 10-14 audit run its 11 findings all quoted real code. The weekly Gemini quota is **22% left
  (resets 2026-10-21)**, so audits must be rationed: about one Flash-high audit per accepted change.
- **Gap in the launcher.** `launch!` predates task chains. It assigns at main on a per-agent branch. It must run
  through the real setup transaction (`seon.task/chain!`, P3/P4) so the bench measures the pipeline agents actually
  use.

## The loop (one iteration, about 30-60 min, about US$0.10-0.50 of DeepSeek)

1. **Measure.** Run the train set at the current HEAD, n = 5 per task, plus 5 more where a cell is near the
   threshold. The grade per run is calls to green (a capped run counts as cap + 1), green share, tokens, and wasted
   forms by class.
2. **Mine.** Classify every model form of the run from the kept branches, using the `context-patterns` classifier
   promoted to code. Rank the waste classes by turns lost.
3. **Hypothesise.** Root writes one change at the producer of the top class, plus its predicted turns saved.
   - The producer is help, a render function, a docstring, a contract message, a dial default or a template.
   - The change must be general: it states the class of situation it helps, never a task.
4. **Pre-judge (optional, for anything non-trivial).** Flash-low judges the proposal against the waste evidence before
   it is built (agy use 2).
5. **Build and prove.** Commit through `bin/lane commit` as usual, plus the leak check (below).
6. **Re-measure.** Run train + dev at the candidate.
   - **Accept** when train turns drop beyond the noise floor, dev does not regress, and the green share does not drop.
   - **Revert** otherwise. A revert is data too: record it.
7. **Audit.** A blind Flash-high audit of the accepted diff (spec below). Every finding is reproduced by root before
   anyone acts on it.
8. **Record.** Append one row to `bench-ledger.md`: HEAD, change, hypothesis, predicted vs measured turns per set,
   audit verdict, cost.

Every K = 5 accepted changes, a **separate lane** runs the held-out test set and reports only aggregates. Root never
sees per-task output from it (below).

## The benchmark

Twenty to thirty tasks, each meeting competency-spec §1: typed plan, red proof, an oracle that goes red to green, and
"cannot be copied".

- **Replay tasks (SWE-bench-style, from our own history).**
  - Source: a past commit that fixed something and added a regression test.
  - Setup, written as data in `chain!`'s setup transaction: the fix's declarations reverted on a fresh chain branch,
    which makes the proof red.
  - Plan: written from the filed task or issue text, never from the diff.
  - Oracle: the commit's own fix.
  - Candidates come from `git log` with a test added under `test/` and a filed task retired in the same commit. The
    seon.ref wildcard task and the 10-15 fixes are the first examples.
- **Competency tasks.** T1-T4 typed (they exist), T9h, and the T14 family once paging lands. These cover generic REPL
  skills.
- **Variants.** Each task gets 2 paraphrased plans, and where possible renamed namespaces and symbols (seeded by the
  run). A gain that survives renaming and paraphrase is general; a gain that disappears under them was memorised.
- **Splits.** About 40% train (the optimiser sees everything), 20% dev (aggregate per task only), and 40% held-out
  test. The test set is authored by a lane that does not optimise, and is only run by it.

## Enforcing "no cheating"

No single rule stops a capable optimiser, so the plan stacks mechanisms. Each catches what the others miss.

1. **A leak test (automated, part of every commit's check).** A new deftest, `seon.bench-test/context-holds-no-bench-answer`.
   - For every bench task it takes the answer fingerprint: the identifiers the oracle creates or changes, distinctive
     literals and strings in the oracle and proof, and the proof's test name.
   - It fails if any context-producing source contains them in an answer-shaped way: `resources/seon/help.md`, the
     step templates `resources/seon/tasks/seon.step.*.edn`, the render functions, and the docstrings of `my.*` tools.
   - Names that already existed in the program before the task are allowed. The rule is that the context may name the
     owner of a concept, but may never contain the task's solution.
   - Each task also carries a canary GUID in its plan, and the test fails on that GUID appearing anywhere outside the
     task file.
2. **Held-out test set, sealed by commitment.**
   - The test set lives in its own files, owned and run by a separate lane (never root, never the optimiser).
   - Its SHA-256 is committed when the set is frozen, so later tampering is visible.
   - Root sees only aggregate scores.
   - The overfitting alarm: if train turns fall and held-out turns do not, after K changes, the last K changes are
     reviewed for leakage and the loop pauses.
   - Honest limit: root can technically read the files. The commitment plus the audit plus the generalisation gap make
     cheating detectable, not impossible.
3. **Paraphrase and rename robustness.** Every accepted gain must hold on the task's paraphrased and renamed variants
   (the "scramble non-semantic fields" diagnostic, below). A change that helps only one wording of a task is rejected.
4. **Separation of duties.** The optimiser may change context producers only. Changing any of these needs the owner's
   approval:
   - the grader (`seon.competency/grade`), task files, proofs or oracles;
   - the call cap, the model, or the bench config.

   The leak test and the bench files get their own lane ownership, so a context lane cannot edit them. This blocks the
   classic games: raising the cap, loosening proofs, choosing easier tasks.
5. **A generality rule per change.** Each change states the situation class it helps ("a reply over N tokens the agent
   asked to read"). The audit checks that the class is real and occurs outside the bench (count it across the kept
   branches and the live corpus, per CLAUDE.md "Count before designing").
6. **Outside audit by Gemini Flash.** Spec sketch below.

### Audit spec (Flash-high, read-only, archive tree; `AGY_TREE=archive`)

> Audit one accepted context change for overfitting to a benchmark. You see: the diff, the bench task plans and proofs
> (train and dev only), the measured before/after table, and the change's stated situation class. Do not edit. Answer,
> with quotes from the diff and the tasks: (1) does any added text give a task's solution, its specific names or
> literals, or steer toward one task's answer? (2) is the situation class real, and how often does it occur in
> `context-patterns.edn` and the kept branches outside the bench? (3) would the change help a plausible task not in
> the bench? Construct one. (4) did anything outside context producers change (grader, proofs, cap, tasks)?
> Verdict: general / suspicious / leak, with evidence.

Ration: 1 audit per accepted change (about 1-2% of weekly quota each), plus 1 Argon-low design audit of this plan
itself before phase 2. When Gemini is out, the same spec runs on GPT or Claude through agy's separate bucket (still an
outside model to root, though not to the Claude family).

## Phases

| Phase | What | Owner | Proof |
|---|---|---|---|
| L0 | Prerequisites: P3/P4 land (`chain!` is the setup transaction), branches kept, the grade reads end facts (context-patterns fix 6) | flow-pass, keep-receipts | a bench run's branch is readable after the merge or cap |
| L1 | Port `launch!` onto `chain!` (each run = a chain of one, its setup and config as data); promote the form classifier into code | Sonnet lane | a fixture-provider launch grades from a kept chain branch |
| L2 | Benchmark v1: mine 10 replay candidates (Haiku dig over git log, verified), author tasks + oracles + 2 paraphrases (Sonnet), split; freeze held-out with its hash | Haiku → Sonnet; held-out by a separate lane | every oracle red→green; leak test green on the empty context |
| L3 | Leak test + canaries + lane ownership of bench files | Sonnet | the leak test fails on a planted answer line in help.md, passes on HEAD |
| L4 | Baseline: train + dev + held-out at HEAD, n = 5 (about 150 runs, about US$0.25) | root + held-out lane | `bench-ledger.md` row 0 |
| L5 | Iterations: the loop above, root as optimiser, lanes for builds, Flash for audits | root | ledger rows; held-out check every 5 accepted changes |
| L6 | Automate the measuring half (one script: run sets → mine → table → ledger draft); keep hypothesis and acceptance with root | Sonnet | one command produces the ledger row |

The first iterations are cheap and already queued: context-fix's elision, `dir`, `in-ns`, must-read and help fixes
are exactly loop changes. Run L4's baseline on the commit before them and again after them, and they become loop
iteration 1 with a real before/after number.

## Decisions for the owner

1. **The split and the sealing.** 40/20/40 with a hash commitment and a separate held-out lane (recommended), or a
   simpler train/test 50/50 with root seeing test aggregates only.
2. **Audit rationing.** One Flash-high audit per accepted change (recommended, about 1-2% weekly each; quota is 22% now),
   or batch audits every 5 changes to stretch the quota.
3. **Who optimises.** Root (me) between runs, with lanes building (recommended to start), or a dedicated optimiser lane
   with root accepting. Either way, acceptance stays a measured rule, not a judgement.
4. **Budget.** Proposed: up to US$2 of DeepSeek per day of loop work (about 1,300 runs), and stop if a day's changes
   show no held-out gain.

## Research notes

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
