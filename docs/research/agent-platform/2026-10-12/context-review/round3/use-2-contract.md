---
title: "Usefulness review, contract lens: round 3 first-call context for seon.ai/contract-output-observed?"
type: research
status: complete
created: 2026-10-12
---

# Usefulness review, contract lens (round 3, use-2)

Reviewed only `docs/research/agent-platform/2026-10-12/context-review/round3/2-contract-ai-output-observed.md`: the first-call context for `seon.ai/contract-output-observed?` (3019 estimated tokens). Token figures are my estimates, scaled to the file's stated section totals (system 1889, user 1130); rows are rounded.

## 1. Ledger

### Message 1, role system (1889 tokens)

| Section | Verdict | Tokens | Why, or what to shorten to |
|---|---|---|---|
| Who you are | SHORTEN | 115 | Orientation I already have from the task. To one line: "You are a Seon agent at a Clojure REPL on your branch." |
| Reading your context: task, step, proofs, must-reads lines | KEEP | 250 | I need what `:seon.context/step`, the proof statuses and `:seon.ref/problem-kind` mean. |
| Reading your context: current-state and render-form lines | SHORTEN | 120 | The current-state block is already in my context. Keep only: render operations are forms to run. |
| Done | KEEP | 130 | Completion rule: proofs green plus intent conditions holding. No done call. |
| How work proceeds, steps 1-4 and the contract line | KEEP | 400 | Owner lookup, `in-ns`, `defn` with `:malli/schema`, contracts name registered schemas. |
| How work proceeds, step 5 (`:prove` deftest example) and delete line | DROP | 120 | Step is `:implement`; no test to write now. |
| The REPL: reply shape and find-more forms | KEEP | 100 | Needed to read replies. |
| The REPL: `println`, `:ms` and tool-call markup lines | SHORTEN | 80 | Keep "return values, never println". The rest is behaviour guard. |
| The REPL: tool-call markup, `:ms` rule | DROP | 40 | Not used for a contract. |
| Data: named schemas, namespaced keys, absence is no key | KEEP | 110 | Governs every contract I write. |
| Data: "fix a bad value at its producer" | KEEP | (in the 110) | Matches the intent's "never widen". |
| Data: redefine Vars, atoms | DROP | 80 | No `with-redefs` or atoms in a contract task. |
| Ask root: `my.message/send` line | KEEP | 70 | The intent names `my.message/send` for a bad producer. |
| Ask root: `my.turn/wait` and send-back example | DROP | 75 | Only needed if the intent is impossible. |
| Gotchas: `[:= nil]` / `[:enum nil]`, falsy keyword lookup | KEEP | 100 | Both bite a contract. |
| Gotchas: lazy `map`/`filter`, fully qualified symbols, error map | DROP | 90 | Generic Clojure, not this task. |

Message 1 totals: KEEP 1160, SHORTEN 315, DROP 404 (about 1889).

### Message 2, role user (1130 tokens)

| Section | Verdict | Tokens | Why, or what to shorten to |
|---|---|---|---|
| Turns, task id, symbol, evidence, namespace | KEEP | 105 | `:seon.context/namespace seon.ai` is needed for `in-ns`. `turns-left` is minor. |
| Task intent | KEEP | 200 | The contract, the cases and the done rule. |
| Proofs (one pending deftest, source) | KEEP | 100 | The test I must turn green. |
| Step and start form | KEEP | 50 | Gives `(in-ns 'seon.ai)`. |
| Must-read `seon.ai/output-observed?` | KEEP | 150 | Only arglists. Its doc, schema and body are absent (section 2). |
| Must-read `seon.contracts/gaps` | KEEP | 230 | The done criterion and the return shape. |
| Must-read contracts guide | KEEP | 60 | A pointer only; its text needs a form run. |
| Must-read `my.message/send` | SHORTEN | 150 | Keep the signature and `:malli/schema` (about 60). Drop the three render forms until the failure branch. |

Message 2 totals: KEEP 980 (includes about 85 tokens of EDN wrapper spread across rows), SHORTEN 150.

### Totals

- KEEP: about 2150 tokens (71%)
- SHORTEN: about 465 tokens (15%)
- DROP: about 404 tokens (13%)

## 2. What is missing

1. **The target itself.** The must-read for `seon.ai/output-observed?` shows only `arglists ([evidence])`. There is no `:doc`, no `:malli/schema`, no body, and no list of the error variants it returns. The intent asks for "every input and output" and "the error variants it returns", so I need the shape of `evidence` and the returns. I would expect the doc, the body, and any existing contract on the target's entry.
2. **The current `seon.contracts/gaps` answer for the target.** The done criterion is "answers `[]`". The context shows no current finding row, so I cannot tell what is wrong today. I would expect the finding row as data.
3. **Registered schemas for evidence.** The intent says "registered names", and the context names none for `evidence`. I would expect a short list or a search.
4. **Reaching tests and their cost.** The intent requires "reaching tests stay green and no slower". The context lists no reaching tests and no baseline timing. I would expect the test names and their last durations.
5. **The proof's helper.** The pending deftest calls `(contracted 'seon.ai/output-observed?)`. `contracted` is not shown, so I cannot tell what the proof checks.
6. **The contracts guide text.** The pointer is `(my.fs/section ...)`, a form to run. Until I run it, the "cheapest schema" rule is unknown.
7. **The database binding.** `seon.contracts/gaps` takes a database value, but the context names no bound variable or accessor for the current database.

## 3. First five forms

1. `(my.program/source 'seon.ai/output-observed?)`. From the must-read, not a guess.
2. `(my.fs/section "docs/seon/architecture/contracts-guide.md")`. From the must-read, not a guess.
3. `(doc seon.ai/output-observed?)`. GUESS: the context gives no docstring, so this assumes `doc` works on this symbol.
4. `(seon.contracts/gaps <database> 'seon.ai/output-observed?)`. GUESS: I do not know how to name the current database value from this context.
5. `(my.program/search :seon.ai/evidence)`. GUESS: that a registered schema for `evidence` exists under this name.

## 4. Verdict

Mostly signal in what it chose to carry, but it is not well balanced. About 71% of the tokens are usable for this contract, and the task block (intent, proof, step, the `gaps` schema) is the reason the context is worth having. Against that, roughly 28% is boilerplate I would not use here: REPL and Malli orientation that a generic agent could use on any task, the send-back and `turn/wait` paths, the Var-redefinition and lazy-sequence warnings. The worst failure is the gap, not the noise: the one function I must contract appears only as its arglists, with no doc, schema, body, or error variants. I would spend my first three forms just to see the target, and the context gives me no sign that its shape exists or where it lives.
