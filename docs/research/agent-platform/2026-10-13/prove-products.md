---
title: "A Prove step declares its product by symbol"
type: research
status: decided and implemented (lane f46b-satisfied)
basis: HEAD 665491c31; Run 16 F46, Run 17 F46b
---

# A Prove step declares its product by symbol

Lane f46b-satisfied, 2026-10-13. Friction: Run 17 F46b and Run 16 F46 (`docs/research/agent-platform/2026-10-12/`).

## What exists

`seon.task/obligations` derives a Prove step's product as the deftests *already tagged* with a task that
`:seon.task/needs` the step. `seon.context/step-status` judges the step over that set. Done requires its new reds to
be exactly the product. Satisfied requires the fork point to have held the whole product red, with nothing new.
Neither rule can see a test that nobody has written yet:

- **Run 16 (F46).** Prove's instructions asked for a table and a `quick-check` law. When the table was written red,
  the product was `{table}` and new-red was `{table}`, so the step was done before the law existed.
- **Run 17 (F46b).** Main held only the table, so the product was `{table}` and the fork point held it red. The step
  was `:satisfied` with 0 model calls, and the law could never be added.

The proofs the step owes were prose in `:seon.task/instructions`, so no rule could read them.

## Count

`resources/seon/tasks` holds 3 Prove steps:

- `seon.error/prove-a-blank-error-message-is-replaced` asks for one table.
- `seon.fs/prove-a-path-under-a-root-answers-true` asks for one table.
- `seon.fs/prove-an-equal-root-and-target-have-no-intermediate-paths` asks for a table and a law.

The multi-proof case is 1 of 3, and it is exactly the case both runs failed.

## Decision: (c) an authored `:seon.task/product` on the Prove step

`:seon.task/product` is a set of qualified deftest symbols, authored on the Prove step, one for each test it must
write. `obligations` answers the declared names together with the tagged tests. A declared name that no test holds
yet has no head entry, so both rules see the gap without further change:

- **Satisfied** needs `(true? (get fork-point s))` for every product test. An absent test is not in the fork point,
  so the step stays `:working` and the model turn runs (fixes F46b).
- **Done** needs every product test designed-red at the head. An absent test is not, so writing the table alone is
  not done (fixes F46).
- **Done** changes `(= new-red product)` to `(every? product new-red)`. Without this, a product partly held red on
  main and partly new could never be done. Satisfied is still checked first, so "nothing new" still reads as
  satisfied.

```clojure
#:seon.task{:name "prove-…" :step :prove
            :product #{seon.fs-test/intermediate-paths-lists-the-roots-from-root-to-target-exclusive
                       seon.fs-test/intermediate-paths-are-the-root-and-strict-ancestors-of-the-target}
            :instructions "…"}
```

The proofs are still deftests carrying the task in `:seon/tasks`. The declaration only names, ahead of time, which
deftests the step owes. The agent sees each one under `seon.context/step-section`'s `:seon.context/product-tests`,
`:pending` until it exists. A step that declares nothing behaves as before.

**Rejected.**

- **(a) Backticked names in the instructions.** A not-yet-existing symbol is an unresolved reference, which
  ingestion reports as a problem on the task. Picking which references are tests means guessing from the name
  (`-test` namespaces), which is forbidden.
- **(b) A count or kind field.** It cannot say *which* test is missing, and step-status would still need names to
  judge red or green. A set of names is no larger and is exact.

## Changes

| File | Change |
|---|---|
| `resources/seon/schemas/seon.task.edn` | `:seon.task/product` is `[:set :qualified-symbol]` with a description. It becomes an optional member of `:seon.task/entry`, `:seon.task/declaration` and `:seon.task/task` (stored; ingestion is schema-derived) |
| `src/seon/task.clj` | `seon.task/obligations` pulls `:seon.task/product` and joins it with the tagged product |
| `src/seon/context.clj` | `seon.context/step-status`: Prove done is `(every? product new-red)`; docstring revised |
| `resources/seon/tasks/seon.fs.edn` | The intermediate-paths Prove step declares its table and its law, and its instructions name them |
| `docs/seon/architecture/vocabulary.md` | The "product" row |

## Proofs (red on HEAD 665491c31, green with this change)

- `seon.task-test/obligations-select-by-step`: new row `prove-declared`. Its declared `law` (held by no test) and
  `table` (tagged on its successor) are its product. On HEAD the closed entry schema refused the file.
- `seon.context-test/each-step-kind-is-done-by-its-one-rule`, two new rows:
  - A declared test absent beside one the fork point held red stays `:working`. This row guards F46b.
  - The absent test supplied red beside the held one is `:done`. This row was red on HEAD (`(= new-red product)`).

## Known gaps

- `seon.task/create!` and `my.task/refine!` still list their keys by hand, so a runtime-created Prove step cannot
  declare a product yet. `create!` is a hand list beside a schema, a defect of its own.
- A declared test that exists but is not tagged with the successor counts as product, but Implement will not own
  it.
- A rerun of a chain whose Implement already merged cannot be done, because its product is green. That is the
  general rule: the product must be red.
