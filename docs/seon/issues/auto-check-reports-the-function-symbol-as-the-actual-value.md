---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, test, accretion, auto-check, error-report]
---

# The auto-check reports the function's symbol as "actual" when the call threw

## Observed (both archives, 052552039 and fbd0897df, 2026-09-26)

```
user=> (defn dre-sq "Square x." {:malli/schema [:=> [:cat :int] :int]} [x] (* x x x))
seon.test.accretion/install-refusal
…
Auto-check seed 733254833444966175
arguments: [2097152]
expected: :int
actual: user/dre-sq
why: ["should be an integer"]
```

On fbd0897df, `(* x x x x)` gave the same report with `arguments: [55109]`.

2097152³ and 55109⁴ both exceed `Long/MAX_VALUE`, so the call threw `ArithmeticException:
integer overflow`. The refusal itself is right. The report is wrong: it names the function where
the thrown exception belongs, and the `why` claims an output-schema failure. An agent reading it
looks for a non-integer return and finds none.

## Expected

A throwing auto-check call reports the exception's class, message and `ex-data` ("Keep the whole
cause"). It never reports the callee symbol as a value.

## Regression

Contract `[:=> [:cat :int] :int]` with body `(* x x x)`: the refusal names `ArithmeticException`
and the generated argument.

## Evidence 2026-09-26 (dogfood-repl-opus): the generated world has no schema, and "actual" is the lookup ref

Default archive a1b01c92a, branch `dogfood-repl-opus-3`: a docstring-only redefinition of `my.issue/status`
reported `Auto-check … arguments: [{:seon.db/db #datahike/DB {… :max-tx 536870912 :max-eid 0}, :seon.issue/id "0"}]
expected: :map actual: [:seon.issue/id "0"] why: ["invalid type"]`. On a real database, `seon.issue/status`
answers the declared not-found refusal for a missing id (verified on the next branch). So the generator built
an **empty database without the issue schema**, the pull threw on the unknown lookup attribute, and the report
shows the thrown `ex-data` entity id as the "actual" return value. **Which model is wrong:** a
`:seon.db/database-value` argument is generated as an arbitrary empty Datahike value, not a database of the
function's declared world. Supplied inputs (the database, the agent) are defaults supplied by declared schema
name at the REPL, so the auto-check should supply the same defaults and not generate them.

## Evidence 2026-09-26 (dogfood-repl-opus, cycle 4): an agent cannot install any function that reads its own data

Branch `dogfood-repl-opus-9`, archive 10d6fdff5. The agent registered `:dogfood.book/id`, `:dogfood.book/title` and
`:dogfood.book/book` (`:seon.program/partition :seon.data`), wrote one book, and pulled it back. Then:

```clojure
(defn book-titles "Sorted titles of every stored book."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :dogfood.book/title]]}
  [db] (vec (sort (seon.db/q '[:find [?t ...] :where [_ :dogfood.book/title ?t]] db))))
```

The install was refused: `auto-check 1/25`, and the arguments were
`[#datahike/DB {… :max-tx 536870912 :max-eid 0}]`, a generated **empty database without the agent's schema**.
The actual value was `seon.db/q cannot read uninstalled attribute :dogfood.book/title`, and it was printed as a
vector of map entries (`[[:seon.db/invalid-read true] [:seon.db/refused-read-operation seon.db/q] …]`) with nine
repeated `"should be a string"` lines. On the agent's real database the query answers. So the generated world, not the function, made
the call fail. Every data-reading function must therefore declare a read refusal it can never meet in its own world, just to
pass the check. That is a contract written for the generator, not for the program. This raises the class from friction:
it blocks ordinary agent work on data.

Follow-up on branch `dogfood-repl-opus-10` (archive 04f3a159b): with the honest contract
`[:or [:vector :dogfood.book/title] :seon.db/error-result]`, returning `seon.db/q`'s refusal unchanged, the
install was **still** refused. The report said `actual: [{:seon.sci.admit/type "datalog.parser.type.Placeholder"} :dogfood.book/title ?t]`.
At the REPL, `seon.db/q`'s uninstalled-attribute refusal carries `:seon.error/offending [#object[datalog.parser.type.Placeholder]
:dogfood.book/nope ?t]`, which is the *parsed* clause holding a datalog-parser host object, not the authored
`[_ :dogfood.book/nope ?t]`. This is consistent with that host object failing `:seon.db/error-result` validation, so
no contract admits the function. **Which model is wrong (second part):** the read refusal stores the parser's
internal value instead of the agent's clause as it was written (store the authored form, never a host
object). No declared alternative can then accept the refusal. I did not run the Malli validation myself: `malli.core`
is not resolvable from agent SCI code (`Unable to resolve symbol: malli.core/validate`).
