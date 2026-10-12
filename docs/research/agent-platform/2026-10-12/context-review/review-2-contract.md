---
title: "Context review 2 (contract-task lens): seon.ai/contract-output-observed?"
type: research
status: complete
created: 2026-10-12
---

# Context review 2: contract-task lens

Reviewed: `docs/research/agent-platform/2026-10-12/context-review/2-contract-ai-output-observed.md` (cold read, no Seon source consulted).

## 1. First five forms

1. `(doc seon.ai/output-observed?)`: the docstring should say what boundary it checks. Quote: `:seon.render/operations [(doc seon.ai/output-observed?) ...]`.
2. `(my.program/source 'seon.ai/output-observed?)`: I need the body, its current schema, and what `evidence` is. Quote: `:arglists ([evidence])`.
3. `(my.fs/section "docs/seon/architecture/contracts-guide.md")`: the "cheapest schema" rule is only named in the intent, so I read it. Quote: `(my.fs/section "docs/seon/architecture/contracts-guide.md")`.
4. `(my.program/tests-reaching {:seon.program/subject 'seon.ai/output-observed?})`: to find real evidence values for the contract.
5. `(my.program/callers {:seon.program/subject 'seon.ai/output-observed?})`, then `(in-ns 'seon.ai)` and `(defn output-observed? {:malli/schema ...} [evidence] ...)`.

Guesses:
- The contract key: the system example says `{:malli/schema [...]}`, but the must-read entry says `:seon.fn/spec`.
- The `database` argument to `seon.contracts/gaps`: `:arglists ([database function-symbol])` names no source for it.
- Whether the proof runs by itself: "`:seon.context/proof-status :pending`" with no run form.
- What "done" means for "no slower": no baseline or measure form.
- The schema for `evidence`: nothing in the context gives its shape.

## 2. Undefined terms

- `contracted` in `(contracted 'seon.ai/output-observed?)`: a proof body call, never explained.
- `database` and `:seon.db/database-value` in `:arglists ([database function-symbol])`.
- `:seon.contracts/absent [:= true]` and `:seon.fn.contract/finding-row` in the `:seon.fn/spec` string.
- "the error variants it returns" in the intent: `:seon.error/value` and `:seon.schema/validation-refusal` appear only in the gaps spec, never as this function's variants.
- "reaching tests": defined only by the op `my.program/tests-reaching`.
- "a finding" and "file a task on the producer": no verb is given.
- `:my.agent/turns-left 100`: no meaning or consequence.
- `:seon.task/step :implement` and `:seon.context/start`: unexplained.
- `:seon.ref/tokens 3228`: unexplained.

## 3. Contradictions and boxes

- **Two contract keys.** System: `{:malli/schema [:=> [:cat :int] :int]}`. Must-read: `:seon.fn/spec "[:=> [:cat ...]]"`. The agent cannot tell which key to write.
- **Task-filing with no verb.** Intent: "file a task on the producer ... and say so in your reply." System: "Ask root with my.message/send". No task-filing move is allowed, so a bad producer value has no allowed exit.
- **Contract without a body.** The rule is "change code at the REPL", and `defn` replaces a whole function. The body appears only as an unevaluated op, so the agent must copy a body it has not seen.
- **Done is not checkable from the context.** Done = "`seon.contracts/gaps` answers []", but the call is not given.
- The rules "Never widen to :any, [:maybe ...] or :map" with no proof shape to check against box the agent in: a refused real value has no described correct fix except by reading the guide.

## 4. Noise (approximate tokens of 2078)

- System gotchas on `some`, `map`/`filter`, `#()` (lines 34-36): ~90, unused by this task.
- `:comp.order/qty` example in system line 26: ~30, unrelated.
- `:seon.ref/tests` for `gaps`: 11 names, ~160; only the four `output-observed` tests matter.
- `:seon.ref/tests` for `output-observed?`: four `seon.ai-test` reasoning and streaming tests, ~60; unclear they reach this contract.
- `:seon.test/source` repeats the proof name and the intent's "Done" clause, ~80.
- `:seon.task/status :working` appears twice, ~10.
- `:seon.ref/path`, `:seon.ref/line 1`, `:seon.ref/tokens`: ~25 of metadata.
- Intent's last sentence repeats system line 27, ~40.

## 5. Format

- Operations are shown as forms beside data, and nothing says whether to run them. `:seon.context/start :source (my.program/source ...)` looks like a value but holds a form.
- The intent is one run-on string of about 110 words, with the done rule last.
- The `#:seon.context{...}` prefix hides which keys belong to `seon.context` and which to `seon.task`, and the nested maps are unlabeled.
- `:seon.ref/tests` lists run 11 lines deep and mix two namespaces.
- No elision marks appear in this file.

## 6. Missing

- The current source of `seon.ai/output-observed?`, with its shape.
- The argument value and call form for `seon.contracts/gaps`.
- The error variants `output-observed?` returns.
- A real `evidence` value, or a pointer to where to get one.
- How to file a producer task, and the form to run the proof.
- The cost budget behind "no slower".
- What `my.fs/section` returns and whether reading the guide counts as a turn.

## 7. Top 5 changes

1. **Give the done check as a runnable form.** In the intent's last sentence, after "Done when", add: "run `(seon.contracts/gaps <database> 'seon.ai/output-observed?)` where `<database>` is the database value your eval is bound to". Without it, "done" is unverifiable.
2. **Make the body and evidence visible.** Add the result of `my.program/source` for `seon.ai/output-observed?` and one real `evidence` value to the must-reads, or flag the operations as "run these first".
3. **Name one contract key.** Reword the system example and the must-read's `:seon.fn/spec` so they agree on `:malli/schema` (or say which one is current).
4. **Give task-filing a verb.** Add to system line 29: "File a producer task with <verb>, or send it to root with my.message/send." Without this, the intent's escape route has no move.
5. **Trim the noise.** Delete the `some`/`map`/`#()` gotchas and the `:comp.order/qty` example. Cut `:seon.ref/tests` for `gaps` to the `output-observed` tests, drop the duplicate `:seon.task/status`, and remove `:seon.ref/line` and `:seon.ref/tokens`.
