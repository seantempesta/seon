---
type: research
status: error and schema renders implemented REPL-first; installed at root's next restart; the other components are designs
created: 2026-09-27
lane: feedback-renders-opus
proof: tmp/feedback-renders/proof.clj (`(proof/proofs)`), results tmp/feedback-renders/proof-results.edn
jvm: default pids 51590 and after; the landed code was evaluated as scratch copies (`fr.error`, `fr.render.schema`), never over default's Vars
---
# Feedback renders: errors and schemas first

**What exists.** A thrown failure is shown through `seon.error/reply-value` (a `select-keys` of class, frames, member,
the whole expected schema and a prose message). A returned error goes through `seon.error/render-ai` (a prose
sentence). `register!` returns its key, so a registration shows 5 tokens and nothing about its consequences.

**Approach.** Each render is one selection of data from the value and one database value. Both faces render that same
selection: the value printer prints it for AI, and `render-html` lays it out as hiccup. A `:seon.render/form` face gives
the requery form, which is shown and never run. The test for each output: is it concise, and can the agent fix the
problem from this reply alone, with no second evaluation? Every case below was fixed from the reply alone, and the fix
was evaluated.

## 1. Errors (`src/seon/error.clj`, `resources/seon/schemas/seon.error.edn`)

**The render.** `reply-value` is the one selection. The `:seon.error/base` pair (`render-ai`, `render-html`,
`render-form`) and the thrown path (`shown-failure` calls `reply-value`) both use it. The selection contains:

- `:seon.error/message`: for a contract refusal, who refused which argument.
- `:seon.error/problems`: only the failing paths. Each has the expected schema at that path (Malli
  `mu/get-in` through refs, pruned to `:description`/`:min`/`:max`) and the offending value there. A result refusal
  also has `:input` and `:result-contract`.
- `:seon.error/fix-form`, which is data. Each failing value is replaced by a repaired one:
  - Malli's `mt/string-transformer` decoding of the offending value, when that decoding is valid: `"4"` becomes `4`,
    and `"seon.error/reply-value"` becomes the symbol.
  - Otherwise one bounded `mg/generate` example (size 1, seed 0).
  - World members are left out, and arguments holding symbols are quoted.
  - It is offered only when the problem's value is found at its place, so a collection-member problem gets none.
- `:seon.error/offending`, `:expected` and `:member` for declared refusals.
- `:seon.error/in-namespace`: when a name is not found (SCI's `:sci.impl/symbol`, or `:seon.program/not-found`), the
  8 declared names in that namespace nearest by spelling. Only public names are shown for a qualified name.
- `:seon.error/sources`: for a removal refusal, the lines of each caller (at most 3) that mention the blocked subject.
- `:seon.instrument/arglists`, `:declared-arities` and `:caller`, and the agent's stacktrace frames.

**Facts it reads.** The error value itself. The database, in custody or the unit's, supplies `:seon.fn/sym`,
`:seon.fn/ns`, `:seon.fn/private?` and `:seon.fn/source`, plus the carried projection's registry.

**Cost.** 0–4 ms. A missing name costs 28–40 ms (one namespace query).

| Case (MCP code batch = agent turn, stub provider) | Before tokens | After tokens | Fix from reply alone |
|---|---|---|---|
| nested contract refusal `(total-area [{w 2 h 3} {w 1 h "4"}])` | 155 | 116 | fix-form `(total-area [... {:w 1 :h 4}])`, ran green |
| missing key `(area {:w 2})` | 161 | 103 | fix-form adds `:h 0`, green |
| first-party contract `(my.program/callers {:subject "seon.error/reply-value"})` | 147 | 131 | expected `[:or :symbol :qualified-keyword]` and fix-form `(callers '{:subject seon.error/reply-value})` |
| exception `(ratio 3 0)` | 73 | 73 | message, class and frames, unchanged |
| unresolved `(aera ...)` | 49 | 61 | `:in-namespace {scratch.fbr [area ratio total-area]}`, green |
| missing entity `'seon.error/replyvalue` | 111 | 73 | nearest `[reply-value ...]`, green |
| removal refusal `(ns-unmap! 'seon.error/evidence-path)` | **3,094** | 105 | offending callers plus `"56: [:a {:href (evidence-path id)} ...]"` |
| writer refusal (bad task id) | 157 | 135 | expected handle with its description ("seon.issue/task-handle returns it") |
| schema admission (unresolved reference) | 73 | 55 | **no**: producer defect, see below |
| nilable register! | 190 | 172 | the producer's message already names the fix, green |

Before and after for the nested contract refusal:

```clojure
;; before
{:seon.error/exception-class clojure.lang.ExceptionInfo,
 :seon.sci.eval/stacktrace [[scratch.fbr/total-area 3 1] [scratch.fbr 6 1]],
 :seon.error/member :seon.fn.arity/input,
 :seon.error/expected [:vector [:map [:scratch.fbr/w [:int {:min 0}]] [:scratch.fbr/h [:int {:min 0}]]]],
 :seon.error/message "scratch.fbr/total-area refused argument 0 at [1 :scratch.fbr/h]: expected an integer at least 0, got a string. Fix: Supply an integer at least 0 at [1 :scratch.fbr/h]."}
;; after
{:seon.error/message "scratch.fbr/total-area refused argument 0",
 :seon.error/problems [#:seon.error{:path [1 :scratch.fbr/h], :expected [:int {:min 0}], :offending "4"}],
 :seon.error/fix-form (scratch.fbr/total-area [#:scratch.fbr{:w 2, :h 3} #:scratch.fbr{:w 1, :h 4}]),
 :seon.sci.eval/stacktrace [[scratch.fbr/total-area 3 1] [scratch.fbr 6 1]]}
```

**What changed after seeing the outputs** (each iteration was re-run through both entrants):

- The first version put a generated whole request into the first-party fix. The problem's expectation was the
  argument's schema, not the leaf's, so it now resolves the leaf with `mu/get-in` over `m/deref-all`.
- A Datahike DB passed as `map?`, so `plain?` now accepts only persistent collections.
- An unquoted symbol in the fix evaluated to a function. Arguments holding symbols are now quoted.
- Every name in the namespace was 272 tokens and cut before the name the agent needed. It is now the nearest 8.
- The removal refusal showed the whole caller source (991 tokens). It now shows the lines that name the subject
  (105 tokens).
- The same problem appeared twice. Problems are now distinct.
- A reply holding only a message renders as that message. This keeps the `html_views` goldens.

**Deleted (second commit).**

- The prose chain in `error.clj`: `instrumentation-prose`, `notice-prose`, `refusal-text`, `refusal-data`,
  `reader-correction`, `evidence-prose`, `flat-data` and `sci-eval-docstring-parts`. The notice pair is now
  `ai-prose`.
- `render-contract-ai` and its `seon.instrument.edn` attachment.
- `reply-shown`, folded into `refusal-value-shown`. With an empty path, the requery's root is the handle itself.

Net `error.clj` since before the renders is **+99** (it was +436). `seon.render.ns` loses `schema-ai`, `schema-html`,
`schema-parts` and `schema-form` (−35). The schema entity's three faces are now `seon.render.schema`'s.

## 2. Schemas (`src/seon/render/schema.clj`, `seon.schema.edn`, `seon.schema.usage.edn`, `register!` in `schema.clj`)

**The render.** `register!` returns `{:seon.schema/key k :seon.schema/definition v}` (`:seon.schema/registration`). Its
pair and the schema entity's pair are `seon.render.schema/render-ai`/`render-html`, and its form face is the `usage`
read. `usage` reads the following, all from one database value:

- the committed form it replaces (`:previous`), or `:new?`;
- the stored count (AEVT, up to 10,000);
- how many of a sample of 200 stored values the new form refuses, with one `[entity value]` example;
- the referencing schemas (`:seon.schema/references` AVET) and function contracts (arity input, output and guard refs),
  8 of each named and the rest counted.

At walk distance 0 it shows only `(doc k)` and the description line, and reads nothing.

**Cost.** 15–42 ms. **Tokens.** 34 for a new key, 145 for a compatible change, 273 for a change the stored data fails.

**Before and after:**

```clojure
;; (seon.schema/register! :seon.task/intent [:string {:min 400}]); before: :seon.task/intent
{:seon.schema/key :seon.task/intent, :seon.schema/definition [:string {:min 400}],
 :seon.schema.usage/previous [:string {:min 1}], :seon.schema.usage/stored 241,
 :seon.schema.usage/checked 200, :seon.schema.usage/failing 90,
 :seon.schema.usage/failing-example [34504 "Every definition an evaluation makes …"],
 :seon.schema.usage/referenced-by [:seon.task/declaration :seon.task/entry :seon.task/task],
 :seon.schema.usage/contracts [seon.task/create!], …}
```

Today the agent sees `:seon.task/intent` and nothing else. The writer then refuses the batch's facts with a garbled
`facts-error` ("got a map missing :seon.task/intent :seon.error/unknown").

**Deletes.** `seon.render.ns/schema-ai`, `schema-html` and `schema-parts` (about 30 lines, `render/ns.clj`) lose their
declaration; the patch to delete them goes to their holder.

## 3. The rest: designs, in the order I recommend

1. **Test**: the verdict at its reaching digests (pass, fail with the minimal diff, stale or never-run), the reached
   definitions changed since its last green, and its duration against its bound. Reads `runner/latest-results`
   (60 ms after 39d94eef0).
2. **Namespace**: dir with contracts, the missing-contract count, red tests, tasks and recent changes. An index walk,
   about 5 ms (feedback-from-facts (a)).
3. **Removal**: done inside the error reply above (callers plus call lines).
4. **Task**: done-when as the named tests' latest verdicts, plus its subjects' faults.
5. **Merge proposal**: the diff by identity and the reaching results on the combined program.

The Var render belongs to the one-path lane. Agent, turn, message, branch, config, blob and read evidence follow the
same shape: one query, data for AI, hiccup for HTML, and a form face.

## 4. Producer defects found, and fixed (each with a tightened contract)

- **An unresolved reference names the missing key** (`seon.schema.edn/admit`, through
  `seon.schema/unresolved-reference-in`). `:seon.schema.edn/unresolved-reference` is now the key nothing declares, and
  `:seon.schema.edn/attribute` is the declaration that names it. The error schema requires both. The message adds "It
  names :k, which no schema declares."
- **Writer refusals report every failing path** (`seon.db/write-entity-error` over Malli's whole `:errors`, through
  the new `write-problem`). A missing key's offending value is the entity that is missing it, never
  `:seon.error/unknown`. This also fixes the garbled "got a map missing :seon.task/intent :seon.error/unknown".
  The output is `:seon.db.write/entity-refusal`, which refuses the placeholder and requires at least one problem.
  Measured on default: a bad task write now lists 5 problems (it listed 1).
- **The MCP and agent-turn text differed** because the value printer printed a returned error's `:seon.error/at`, and
  the two runs are two instants. This is not a printer difference. The reply leaves the timestamp out, and the proof's
  `:after-entrants-agree?` is true for every case.
- **Still open, not mine:**
  - `face-valid?` still requires a string AI face (render-selection lane);
  - the batch's `:installed? false` note is not in the shown reply (one-path lane).

## 5. Verification limits

- The landed code ran as scratch copies in default. The installed proof waits on root's restart:
  `(proof/installed?)` then switches the proof to `seon.error`/`seon.render.schema`.
- Contracts compile from `git archive HEAD` plus this diff: 0 findings. clj-kondo on `src script test`: 0 errors.
- No tests were run (lane rule).
