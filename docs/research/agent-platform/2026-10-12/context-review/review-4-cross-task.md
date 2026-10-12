---
title: "Context review 4: cross-task review of the three first-call contexts"
type: research
status: complete
created: 2026-10-12
---

# Context review 4: cross-task review of the three first-call contexts

- Lens: cross-task. Lane: review-4.
- Read only: `1-implement-forgive-forgiving-forgiven.md`, `2-contract-ai-output-observed.md`, `3-implement-merge-first-facts.md` in this directory. No Seon source or docs were read.
- Token figures are rough (about four characters per token), except the totals the files themselves report.

## Cross-task: what is shared, and is the base enough?

**Shared across all three:**

- Message 1 (system) is byte-identical in all three: 2290 characters, 715 estimated tokens, the same sixteen quoted lines (`"You are an agent at a REPL on your own branch; ..."` through `"Gotcha: an error is a map; ..."`).
- The user-message envelope has the same keys in the same order: `#:seon.context{:task :proofs :step :must-reads :turns}`. `:turns` is `{:my.agent/turns-left 100, :seon.task/status :working, :seon.context/namespace ...}` in each.
- `:step` is `{:seon.task/step :implement, :seon.task/status :working, :seon.context/start {:in-ns ..., :source (my.program/source '...)}}` in each.
- Each must-read carries the same operations vector, `:seon.render/operations [(doc X) (my.program/source 'X) (my.program/callers ...) (my.program/tests-reaching ...)]`.

**Not shared:** no must-read appears in more than one task. Each task brings its own reach, so the base is the only thing the three have in common.

**Verdict: enough to start, not enough to finish.** The base teaches the REPL verbs (`in-ns`, `defn`, `doc`, `source`, `dir`, `deftest` with `:seon/tasks`, `register!`, `my.message/send` to root) and the gotchas. Those are enough to send a first form in each task. The envelope is not explained at all, and three things each task needs are missing from the base:

1. **Reach (the concept to teach).** Each must-read is a function the task's change reaches. `:seon.ref/callers` are what a change to it can break; `:seon.ref/tests` are what checks it; the proofs are the only success signal. File 1 gives `:seon.ref/callers [seon.sci.forgive/segments seon.sci.forgive/step ...]` with no word on what a caller list means. File 3 gives `:seon.ref/tests` for `seon.db/vector-query-call` with `…390` after eight names and no hint what to do with a change this wide. The base should say this once, and each task then only has to name its own reach.
2. **Operations are forms to run, not data.** `:seon.render/operations` lists forms. The base names three (`doc`, `my.program/source`, `dir`). The envelopes use at least five, including `my.program/callers`, `my.program/tests-reaching`, and `my.fs/section` (file 2, the contracts guide). An agent has to guess that these are forms to evaluate and what they return.
3. **What "done" means.** The base says "green proofs end your task and tell root". File 2's intent adds a second condition: "Done when `seon.contracts/gaps` answers [] for it and its reaching tests stay green and no slower." The base does not say that the intent's conditions are part of done, and that a condition the agent cannot check at the REPL goes to root.

## 1. First forms

The three share one shape. The first forms are `(in-ns 'NS)` and `(my.program/source 'SUBJECT)` for the subject named in `:step`, then `(doc ...)` on each must-read. Guesses in all three:

- **Task 1:** the mode values for `:seon.batch/mode` (undefined in the envelope); how to call `forgive` on a stray-closer string to observe the repair; where the red proof's body is (cut, see section 5).
- **Task 2:** how to test whether `seon.ai/output-observed?` is contracted before defining one (`contracted` is only in the proof's source); whether `defn` on an uncontracted function warns or refuses; what `[]` from `seon.contracts/gaps` looks like once complete.
- **Task 3:** what `bin/seon check` is and whether the agent can run it (the base forbids files and IO); what "check JVM" means; the source of the numbers in the intent, which cite `(m6-notes.md, ...)`, a document no must-read names.

Done is guessed in all three. Task 1 can be shown green by its proof. Task 2 needs `gaps` to answer `[]` and no timing regression. Task 3's red proof runs only as its JVM's first merge.

## 2. Undefined terms, cross-task

- `:seon.task/step`, `:implement`, `:seon.task/status :working`: no list of other steps; no meaning given.
- `:proofs` with `:seon.context/proof-status :pending`: "pending" is never defined. The base implies it means red.
- `:my.agent/turns-left 100`: no meaning. Is 0 a failure?
- `:seon.ref/callers`, `:seon.ref/tests`, `:seon.ref/line 1`: never explained.
- `#:seon.task{...}`, `#:seon.context{...}`, `#:seon.ref{...}`: the namespaced-map reader shorthand is unexplained.
- `:seon.fn/spec` (strings in all three) versus `:malli/schema` (base line 22, the only contract key the base mentions).
- `#:seon.ref{:name :seon.batch/dropped, :problem-kind :unresolved, :line 1}` (file 1) and `#:seon.ref{:problem-kind :unresolved, :path "bin/seon check", :line 1}` (file 3): what an unresolved must-read is, and that the agent must file it.
- `seon.profile/measure` (file 3): one must-read with no example.

## 3. Contradictions and boxes

- **Nil and `:maybe`.** The base says `never [:maybe ...]`. File 3's must-read for `seon.merge/merge!` has `[:maybe :seon.fn/root]` in its spec. An agent copying the spec copies the banned form, and the base gives no rule for which to follow. The fix is at the producer (the spec), not the base.
- **Contract key.** Base line 22 teaches `{:malli/schema [...]}`. Every must-read shows `:seon.fn/spec`. The base never connects them.
- **Block location.** Base line 22: "Your task and proofs are in your block: (seon.context/current-state)." The block is already the user message, so the agent does not know whether to re-call it.
- **File-writing box.** Base line 23: "Change code at the REPL, never in files". File 3's proof says "run it alone through bin/seon check". File 2 says "file a task on the producer". The base gives one outbound route, `my.message/send` to root, and no shell.
- **Uncontracted defn.** Base line 22: "every function has a contract." It does not say what the REPL does when a defn has none. Task 2 exists because of exactly that.

## 4. Noise

- **Unused gotchas (about 150 tokens per call, in all three).** `(some f xs)`, lazy `map` and `filter`, `#()` nesting, and the `seon.schema/register!` example with `comp.order`. None of the three tasks touches them.
- **Test lists (about 120 to 170 tokens each).** File 2's `:seon.ref/tests` for `seon.contracts/gaps` has twelve names, most of them "contract-coverage" tests the agent will not run by hand. File 1 lists eight tests for `forgive` and `segments`. File 3 lists eight tests for `vector-query-call`, then `…390`. Three names and a count would serve.
- **Repeated operations (about 25 tokens per must-read, about 250 across the three).** Refs with only `(doc X)`, such as `:seon.batch/text`, `:seon.batch/repairs` and `:seon.batch/spans` in file 1, carry no value at all.
- **`:seon.ref/line 1`** appears on about fourteen refs across the three. It means nothing.
- **Start source repeated.** `:step`'s `(my.program/source 'SUBJECT)` repeats the subject's own source, which the envelope already offers as an operation.

## 5. Format

- **Elisions cut the rules.** File 1's intent ends at `falls outside eve"…+430`, which is mid-word, and drops the rule itself. File 3's proof is `` Red proof: `my.root-t"…+210 `` (its name cut), and its body is `;; red only as its JVM's first merge (run it alone through bin/seon check); a facts merge is warm 6.4 ms and 1 "…+2167`. The proof is the one thing that defines success, and it is hidden in files 1 and 3. File 2's proof is complete: `(contracted 'seon.ai/output-observed?)`.
- **Bare counts.** `…1`, `…3`, `…4`, `…390` say how many are hidden, not what. An agent cannot tell whether `…390` tests are worth a query.
- **Mixed schema forms.** `:seon.fn/spec` is a string in all three; `:seon.ref/schema-form` is an EDN vector (file 1, `[:string …1]`). A reader has to know both forms exist.

## 6. Missing, cross-task

- How to file a task or send one back to its stage (file 2 requires it).
- What `bin/seon check` is, and whether the agent can run it.
- How to test that a function is contracted, and what `[]` from `seon.contracts/gaps` means.
- How to measure cost with `seon.profile/measure`, and what a timing regression looks like.
- What the other step values are, and what the agent does at `:implement`.

## 7. Top 5 changes, ranked across the three tasks

1. **Explain the envelope in the base.** Add a short "Reading your context" block before the first message's end: `:task` is what must become true; `:proofs` are red tests you must turn green (`:pending` means red now); `:must-reads` are the change's reach, where `:seon.ref/callers` are what your change can break and `:seon.ref/tests` are what checks it; `:seon.render/operations` are forms to evaluate, not data. Replace "Your task and proofs are in your block: (seon.context/current-state)" with where the block actually is.
2. **Define done once.** Add: "Done means every proof green on your branch and every condition in the intent holds. A condition you cannot check here goes to root with `my.message/send`." This answers task 2's "Done when ... no slower" and task 3's `bin/seon check`.
3. **Fix the contract vocabulary.** Change base line 22 to name `:seon.fn/spec` as where a contract is shown, and add one line: "A defn without a contract is admitted with a warning; add the contract before you finish." Fix `[:maybe :seon.fn/root]` in `seon.merge/merge!`'s spec at its producer (a task), so the base's rule holds.
4. **Give the outbound route and say what the shell is for.** Name how to file a task or send one back, and state that a proof naming `bin/seon check` is run by root, not the agent. Task 3 then has a defined outcome instead of a guess.
5. **Trim and un-elide.** Remove the four unused gotchas and the `register!` example; reduce test lists to three names plus a count; drop `:seon.ref/line 1` and the operations list on refs whose only operation is `doc`; expand each elision to name what it hides, and never cut a proof's body, only its comment.

Producer-side, not base: file 1's `:seon.batch/*` refs marked unresolved, file 3's `m6-notes.md` citation, and the `:maybe` in `seon.merge/merge!`'s spec each need a task on their owning producer.
