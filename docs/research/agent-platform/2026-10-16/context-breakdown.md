---
type: research
status: evidence
created: 2026-10-16
tags: [research, context, agent-platform, chain]
---

# Context breakdown: chain-b7ab51e090d5 (seon.ref "wildcard-citations-are-prose"), live run 2026-10-15

Read-only research lane (Haiku). Evidence for root; no decisions made. Token estimates use about 4 characters per token.
Limits: the full `seon.context/model-context` text was not assembled whole. Parts were reconstructed separately (help, current-state, must-reads, task plan, merge template). The debug page `http://127.0.0.1:7994/agent/<id>/debug` was not fetched. The older chains `attempt/faec011a7057` and `attempt/54c541b9172a` were not analysed (they are not on `:chain-b7ab51e090d5`; they belong to `:chain-10d1fd5956a9`, not queried).

Evaluation counts: the `:seon.cluster.eval/ordinal` resets to 0 at each model turn, so counts below are forms, not turns.

## Summary, ranked by cost to the run

1. The merge step (`attempt/cca4c2e69d66`, 94 forms over about 38 turns) spent roughly 25 turns guessing how to run tests and reading `:pending` tests. `resources/seon/help.md` never says how to run a test or what `:pending` means. (VERIFIED)
2. 25 of 94 merge-step forms are `(in-ns ...)`. The step block carries `(in-ns (quote seon.ref))` as a render operation, and the agent re-emits an `in-ns` before almost every turn. (VERIFIED block text; cause INFERRED)
3. The merge block's proofs show one green chain test (`:seon.context/proofs` `{:counts {:green 1}}`). The refused red tests are not shown, so the agent went looking for the refusal through `step-verdict`, `red-test-error` and `accept-merge!` source. (VERIFIED block; cause INFERRED)
4. Three merge-step replies were prose with no form and got "Your reply had no form; only comments/prose. Send a form." (VERIFIED, producer `seon.turn` text). Each cost a model call.
5. Merge-step must-reads are 10,237 characters (about 2,560 tokens) on every call. The first entry is the unresolved `seon.render.unknown/*` problem, the same wildcard the task is about. (VERIFIED content; producer not traced)
6. The agents invented function names that do not exist anywhere in `src`: `my.program/ns-decls`, `my.program/ns-extract-source`, `grep-file`, `seon.ref/parser`. Each returned an error. (VERIFIED absence in `src`, `resources`, `docs/seon`)
7. The system message `(help)` renders 29,737 characters (about 7,400 tokens); `help.md` itself is 7,631 characters (about 1,900 tokens). It is sent with every call. The composition of the rest is INFERRED.
8. Attempt `attempt/2aaec4432ae0` ran 36 forms over 12 turns, all reads, and never defined the proof. Task `b7ab51e090d5` has a plan of 894 characters (about 220 tokens) and empty `:seon.task/instructions`. (VERIFIED sizes; the cause is INFERRED)
9. `:pending` appears in `help.md` line 17 as one of three proof states, with no definition. The merge agent wrote prose twice that `:pending` tests "never ran". (VERIFIED text)
10. Operational, for root: my own read of `seon.bootstrap/help-value` plus `seon.context/current-state` on the chain branch took 2,086 ms and allocated 2,861 MB (the reply flagged `:slow`). Current-state on the merge branch took 487 ms and 1,055 MB. Both are over the one-second rule. (VERIFIED from the reply)

## 1. Wasted space

### 1.1 Must-reads on the merge step (about 2,560 tokens, every call)

- What: `(seon.context/current-state-ai (seon.context/current-state db "attempt/cca4c2e69d66"))` gives `:seon.context/must-reads` at 10,237 characters. The first entry is `{:seon.ref/problem-kind :unresolved, ... :seon.ref/name :seon.render.unknown/*}`. Its operation is `(my.program/search "*")`.
- Where: `src/seon/context.clj` `current-state-ai` and `must-read-ai`, which render the must-reads for the attempt. The producer of the must-read list is not traced (it comes through `task-sections`, not read here). Carried in `attempt-sections`, so it is cached with the attempt prefix.
- Evaluations caused: none directly. The merge attempt re-read `seon.ref/span-candidate` and `seon.ref/references` repeatedly (ordinals 0-3 of the first turns; `(my.program/source 'seon.ref/span-candidate)` appears about 6 times in the merge attempt). INFERRED.
- Estimated tokens: about 2,560 per call. The chain's own problem text is echoed back to the agent as a must-read.
- Fix at producer: a must-read whose problem names the task's own subject should be shown once, as the task's problem, not as a must-read section. Measure before changing.

### 1.2 Help system message (about 7,400 tokens, every call)

- What: `seon.bootstrap/help-value` renders 29,737 characters. `resources/seon/help.md` is 7,631 characters. The remaining about 22,000 characters come from the render (`seon.bootstrap/render-help-ai`), not verified here.
- Where: `src/seon/bootstrap.clj` `help-value` (line 44) and `render-help-ai` (line 71).
- Evaluations caused: none directly; every call pays it.
- Fix at producer: measure which sections of the 29.7k characters the merge agent ever used. Cut what no step needs, then re-check `help.md` against the render.

### 1.3 Repeated `in-ns` on every form (25 of 94 merge-step forms)

- What: the block's step section carries `:seon.render/operations [(in-ns (quote seon.ref))]` (the merge block `:seon.context/step`). The agent then wrote `(in-ns 'seon.ref)`, `(in-ns 'user)`, `(in-ns 'seon.ref-test)` and `(in-ns 'seon.render.ns-test)` before almost every form.
- Where: `seon.context` step section; the namespace the agent sits in is `:seon.context/namespace` (`agent-namespace`, `src/seon/context.clj` `current-state`).
- Evaluations caused, examples (ordinal, source): `0 (in-ns 'seon.ref-test)`, `0 (in-ns 'seon.render.ns-test)`, `0 (in-ns 'user)` (several), `0 (in-ns 'seon.ref)`.
- Estimated tokens: about 10 tokens per form, so about 250 tokens over the attempt. The real cost is the lost evaluations' context switching, not tokens.
- Fix at producer: state the namespace once in `:seon.context/namespace` and drop the operation when it is already the agent's current namespace, or say in `help.md` that the namespace persists between forms (the `eval_clj` tool description says it does).

### 1.4 Plan and instructions of the chain task

- Plan: 894 characters (about 220 tokens). Instructions: 0 characters. Proofs: `[seon.ref-test/a-citation-ending-in-a-wildcard-is-prose]`.
- Not wasteful; listed for completeness. The plan's "Done when" names the proof. The plan does not name the owner of `span-candidate` by its fully qualified symbol in the body that the agent saw (it does name it; the agent still searched 3 times).

## 2. Confusing parts

### 2.1 `:pending` and "run the tests" are undefined in the context

- What (help.md line 17): "`:seon.context/proofs` are the deftests that show your step done, each `:red`, `:pending` or `:green`; they run by themselves after each eval request and the next block shows them."
- Where: `resources/seon/help.md` line 17. The render of proofs is `:seon.context/proofs` (`src/seon/context.clj` `current-state`).
- What the agent did (merge attempt, ordinals 1-3 of several turns): wrote prose "The refused tests are all `:pending` because the gate's test run ... " (a no-form reply), then `(require '[clojure.test :refer [run-tests]])` and `(run-tests 'seon.render.ns-test)`, then `(clojure.test/test-vars [...])`, then `(binding [*test-out* *out*] ...)` which failed with `Unable to resolve var`.
- Evaluations caused: ordinals with `run-tests` or `test-vars` in the merge attempt: about 16 forms. Two errors: `Unable to resolve symbol: run-tests` (in `seon.render.ns-test`) and `Unable to resolve var: seon.render.ns-test/a-cited-schema-shows-its-key-...`. Output of the working `run-tests` was 86 to 155 characters (not a result table).
- Estimated tokens: about 16 forms times about 60 tokens each, about 1,000 tokens, plus about 25 turns.
- Known issue: `docs/seon/issues/clojure-test-in-the-agent-repl-reports-nowhere-and-cannot-run-an-agents-namespace.md` (status `fixed-unproven`) describes the same failure. The context does not teach the proof path. VERIFIED.
- Fix at producer: define `:pending` in `help.md` in one line (never run on this branch, so run it with its reach), and teach one proof-running form or make the proof results carry their status and message in the block.

### 2.2 The merge block hides the refused tests

- What: the block's `:seon.context/proofs` shows `{:counts {:green 1} :proof-list [...]}`, one green chain test. The merge step's `refusal-section` (in `seon.cluster.agent`) lists "Red on the merge branch" from the refusal, and the merge-step producer (`merge-step`) sets `:seon.task/proofs` to the refused tests when there are any. The block the agent read did not show them as proofs.
- Evaluations caused: `(:seon.source/refusals (seon.context/step-verdict))` (ordinal 1), `(seon.context/step-verdict)` (ordinal 2), `(my.program/source :seon.task/refusal)` (ordinal 0), `(my.program/source :seon.source/red-test-error)` (ordinal 1), `(my.program/search "accept-merge!")`. The agent was reconstructing the refusal by reading the source of the merge gate.
- Where: `seon.cluster.agent` `merge-step` and `refusal-section` (src/seon/cluster/agent.clj around line 2335 and 2360); the block assembly in `seon.context` `current-state`. VERIFIED that the block omits them; INFERRED that the refused tests were not in `:seon.task/proofs` when the block was rendered, or were filtered by `task-sections`.
- Fix at producer: the proofs section of a merge step must list the refused tests with their status and failure, from the same value that `merge-step` writes.

### 2.3 Prose-only replies (three turns)

- What: "Your reply had no form; only comments/prose. Send a form." (merge attempt ordinal 0 three times, the second and third starting with `; Now I can see...` and `; The refused tests are all...`, and `Now the picture is completely clear...`).
- Where: the error text is in `src/seon/turn.clj` around lines 2546-2569. The rule that a reply must carry a form is not in `help.md`. VERIFIED that the messages exist in the producer; the model's reason for prose is INFERRED (it was reasoning about a refusal it could not see; see 2.2).
- Estimated tokens: three turns, about 150 tokens each.
- Fix at producer: when the agent has nothing to evaluate, the block should show the next form to run (the refusal's proof), not leave the agent to write analysis.

### 2.4 Invented names

- `(my.program/ns-decls 'seon.ref)` (attempt 2aaec, ordinal 1, error), `(my.program/ns-extract-source 'seon.ref)` (merge, error), `(grep-file "test/seon/ref_test.clj")` (merge, error `Unable to resolve symbol`), `(seon.ref/parser)` (attempt 2aaec, ordinal 0, error).
- A search for each in `src`, `resources` and `docs/seon` finds no hit. VERIFIED.
- `my.fs/section` exists (`src/my/fs.clj`) and the agent used it, twice, correctly.
- Fix at producer: `help.md` lists the `my.*` tools the agent may call. A file-grep form in help would have replaced the three guesses. INFERRED that the agent needed one.

### 2.5 `(my.program/source "span-candidate")` with a string argument

- Attempt 2aaec, ordinal 0 of the last group, error. The name is a symbol in every other call. Help does not say whether `my.program/source` takes a string. INFERRED: unclear argument type.

### 2.6 Lint refusal not recorded as an error

- The chain attempt `attempt/cec65a8d91f7` wrote an `is` message with `pr-str`, which the lint refused. Its 19 stored forms carry no `:seon.cluster.eval/error`. The refusal is a finding in the reply (`:seon.repl/findings`), not a stored error. INFERRED from the stored fields; the reply was not reread.
- Fix at producer: store lint refusals on the evaluation so that the attempt record shows them.

## 3. Incomplete information (what the agent searched for)

### 3.1 Refusal detail for the merge step

- See 2.2. The agent needed the refused test names, the failure of each, and the run that failed. It searched for them with `step-verdict`, `red-test-error`, `current-results` (`my.program/source 'seon.test.runner/current-results`, which then failed with `Invalid keyword: :seon.test/result/status`), and `(my.program/tests-reaching 'seon.ref/span-candidate)`.
- The `current-results` lookup returned an error; the correct keyword is not in help. VERIFIED error text.
- Fix at producer: the merge block shows each refused test with its status, failure and run id.

### 3.2 How to read the lint of the changed file

- Merge attempt: 8 forms to find the lines of `test/seon/ref_test.clj` (`my.fs/section`, `split-lines`, `doseq`, an `IndexOutOfBoundsException` on line 3 of a `let`). Then the lint was fixed in about 15 evaluations.
- Fix at producer: the refusal section gives the file and the lint finding with its line, as data, so the agent does not have to slice the file.

### 3.3 The chain attempt had to find the owner

- Attempt 2aaec: 36 forms in 12 turns, all reads of `seon.ref/span-candidate`, `references`, `candidate`, `document`, `parsed`, `cite-once`, `seon.ref-test`. The plan says the owner is `seon.ref`, and `seon.ref/span-candidate` is named by the plan. The agent still spent about 12 turns on reads and did not define anything before the cap.
- INFERRED cause: the plan says what is wrong but not which declaration to change first (the body names `span-candidate` only through the "Problem" paragraph). Root may want to compare with the successful attempt `cec65a8d91f7`, which went straight to `span-candidate` (ordinals 0-2) and fixed it.

## 4. Unclear instructions

### 4.1 Merge step template

- `resources/seon/tasks/seon.step.merge.edn`, 1,270 characters (about 320 tokens): "your proofs are the tests the gate found red ... and show what is still refused after each evaluation". The template does not say how to run them, and says "Fix each here with ordinary REPL work".
- The agent read the template and then did not know how to get the proofs green. VERIFIED that the template has no run instruction; INFERRED that this is why 2.1 happened.
- Fix at producer: the template names the one proof-running form (the same form the block shows), so the agent runs it instead of guessing.

### 4.2 `:seon.context/step` status `:done` at the start of the merge step

- The merge block showed `{:seon.task/status :done, :seon.context/start ...}` (100 characters). The step is not finished; the status is probably the previous step's verdict. INFERRED from the name, not traced in `seon.context/step-verdict`.
- Fix at producer: say `:done` means the step's previous attempt was done, or leave it out at the start of an attempt.

## 5. Anything else

- Older chains (`:chain-10d1fd5956a9`, attempts `faec011a7057`, `54c541b9172a`) were not analysed. They should be compared before any fix is judged; the same guess-the-test-runner pattern is the first thing to check.
- Attempt `attempt/cec65a8d91f7` shows 19 forms and no stored errors, yet fixed `seon.ref/span-candidate` and wrote the proof. Its context was the most useful of the three (it had the owner's source and proof). Compare its first 3 forms with attempt 2aaec's first 3 forms: the difference is the first read.
- Cost of this research itself: `seon.bootstrap/help-value` plus `seon.context/current-state` on the chain branch took 2,086 ms and 2,861 MB allocated. The merge-branch `current-state-ai` took 487 ms and 1,055 MB. Both exceed the one-second rule in CLAUDE.md, "Make ordinary work sub-second". Root may want a task for the help-value render cost.

## Pointers

- Context assembler: `src/seon/context.clj` `model-context` (line 909), `current-state` (771), `current-state-ai` (828), `block-views` (843).
- Help: `src/seon/bootstrap.clj` `help-value` (44), `resources/seon/help.md`.
- Merge step: `src/seon/cluster/agent.clj` `merge-step` and `refusal-section` (about 2335-2370); `resources/seon/tasks/seon.step.merge.edn`.
- Turn errors: `src/seon/turn.clj` (2546-2569).
- Known issue: `docs/seon/issues/clojure-test-in-the-agent-repl-reports-nowhere-and-cannot-run-an-agents-namespace.md`.
