---
title: "Context review 3, lens implement-merge"
type: research
status: complete
created: 2026-10-12
---

# Context review 3, lens implement-merge

Reviewed: `docs/research/agent-platform/2026-10-12/context-review/3-implement-merge-first-facts.md` (task `seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts`), read cold.

## 1. First five forms

1. `(seon.context/current-state)`. The system line says "Your task and proofs are in your block" and names this form. The user message already shows a block, so I would check whether the live block differs.
2. `(doc seon.db/vector-query-call)`. The intent names it as the allocator. Its doc line is visible, but its operations are elided to `…1`.
3. `(my.program/source 'seon.db/vector-query-call)`. I read the body before changing anything.
4. `(in-ns 'seon.merge)`. The step says `:in-ns seon.merge`, and the system says to define in the owning namespace.
5. `(seon.profile/measure (fn [] (my.root/merge! {...})))`. The arglist is `[{context :my.program/context, key :seon.task/assignment}]`. I have no value for either.

Guesses: which namespace I may edit (`seon.merge` by the block, `seon.db` by the allocator); the context and assignment values; how to run the red proof ("run it alone through bin/seon check"); the cost bound (the proof text is cut off at `"…+210`); what "done" means (green proof, or a number under budget).

## 2. Undefined terms

- `seon.profile/measure` and `seon.profile/thunk`: used, never explained.
- "Red proof": `"Red proof: \`my.root-t"…+210,`. "Red" is never defined.
- "warm", "fresh JVM's first merge", "check JVM": "warm 6.4 ms and 1 MB ... fresh JVM's first merge 417 ms". No way to reach a fresh JVM from the REPL is given.
- `root`: "only what root merges reaches the cluster". Root's role is not described.
- `:seon.task/step :implement`, `:seon.task/status :working`, `:seon.context/start`: not explained.
- `:seon.fn/root`, `:seon.merge/execution`, `:seon.source/conflict-error`: in spec strings only.
- `astra-cost-handoff/preparation` (a caller): unexplained.
- `#:seon.ref{:problem-kind :unresolved, :path "bin/seon check"}`: unexplained.
- `:malli/schema`: the system example uses this key, but the context shows `:seon.fn/spec`. The agent cannot tell which key to write.

## 3. Contradictions

- "Change code at the REPL, never in files (no slurp, spit or io)" versus the intent's "(run it alone through bin/seon check)". The only test path named is outside the REPL.
- The proof is "red only as its JVM's first merge", but "Your task's proofs ... run after each eval request". The proof cannot show red on my branch, so I cannot observe the red state I must fix.
- "Define a function in the namespace that owns it" versus `:seon.context/namespace seon.merge`, while the allocation is in `seon.db`. No allowed move is stated for editing `seon.db`.
- Gotcha: "never [:maybe ...]". Yet the displayed spec is `"[:=> [:cat :seon.merge/execution :seon.task/assignment [:maybe :seon.fn/root]] ..."`, and `my.root/merge!` has the same `:maybe` pattern.

## 4. Noise

- `:seon.ref/tests` lists for `vector-query-call` (`…390` elided), `seon.profile/measure` (`…58`) and `seon.merge/merge!` name db-flow, registry and agent tests. None is needed for this task. About 400 tokens.
- `:seon.render/operations` repeats `(doc …)`, `(my.program/source …)` and `(my.program/callers …)` on every ref. About 200 tokens.
- `:seon.ref/line 1` (twice) and the unresolved `bin/seon check` ref are placeholders.
- Message 1's gotchas on `some`, `#()` and `map` are not used here. About 150 tokens.

Estimate: about 800 of the 2668 tokens (roughly 30%).

## 5. Format

- Message 2 is one EDN blob, three levels deep (`#:seon.context{:task #:seon.task{...}}`).
- Elisions cut the words that matter: `"…+210,`, `"…+2167}]},`, `"…+91,`, `"…+770,`, `(my.program/callers …1)`, `…390]}`. The red proof and the cost bound are both cut.
- `:seon.context/namespace seon.merge` is on line 158 of the blob, after everything else. The edit target comes last.
- `:seon.context/start` holds the test's source form as a value, so the proof appears twice.

## 6. Missing

- The full proof source with its assertion and budget number.
- How to run one proof on my branch, and whether `bin/seon check` is available to me.
- A worked call of `my.root/merge!` with a context and an assignment, or how to obtain them.
- Which namespace I may edit, and whether `seon.db` is in scope.
- A concrete "done": a green proof, a number under budget, or both.
- A pointer to the first-call derivation itself. The intent says only "the first three-way and projection reads".

## 7. Top 5 changes

1. Print the red proof in full, with its assertion and budget, instead of `"…+210`. It is the definition of done.
2. State how to run the proof: a REPL form, or an explicit "not runnable in the REPL; reproduce it this way." Resolve the `bin/seon check` contradiction in message 1 and in the intent.
3. Put a "Start here" line before the EDN: the namespace to edit (`seon.merge`), the suspected allocator (`seon.db/vector-query-call`), and whether to edit it or ask root. Move `:seon.context/namespace` to the top.
4. Make the `:maybe` gotcha match the displayed specs. Either reword the rule for `merge!`'s spec, or flag `merge!` as a known exception. Name one key for the contract (`:malli/schema` or `:seon.fn/spec`).
5. Drop the `:seon.ref/tests` lists beyond this task's three tests, the `:seon.ref/line 1` and unresolved entries, and the repeated render operations. Keep only the gotchas on nil, presence and lazy `map`. This saves about 800 tokens.
