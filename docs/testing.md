# Testing

How a test is written in Seon: on a small world, with what it adds, cheaply, by one default way.

## Testing in the world

How to write a test here: it runs on **the world**, a small db shaped like production, plus what the test adds. Never
build a test on the real program, the real wiki or a store of its own: those cost seconds and a
store, where the world costs about a millisecond and nothing (the owner, 2026-10-30).

```clojure
(ns app.x-test
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [datahike.api :as d]))

(deftest f-is-recorded-with-its-contract
  (let [db (t/with (t/world)
                   (t/code 'app.x "(ns app.x)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"))]
    (is (= "[:=> [:cat :int] :int]" (:var/contract (d/entity db [:var/name 'app.x/f]))))))
```

1. **`(t/world)`**: the base world, a db value. It already holds a program (`app.calc`: `add`,
   `inc2`, `total`, the macro `twice`; `app.report/line`, which calls `app.calc/total`), its tests
   (`app.calc-test`), a wiki page (`docs/calc.md`, citing them) and a task
   (`app.calc/total-is-exact`). Use these before adding your own. `(t/world {:history? true})` is
   the same world on a db that keeps history, for a test that reads `d/history` or `d/as-of`.
2. **`(t/with db fixture ...)`**: db with each fixture added, in order. A value: nothing is written,
   and the world is left as it was. Most tests need only this.
3. **The fixtures** (each answers a fixture you pass to `with`):
   - `(t/code 'ns.name "source text")`: a namespace, as an import records it (forms, vars,
     contracts, references between vars).
   - `(t/page "docs/x.md" "# X\n\ntext citing app.calc/add")`: a wiki page and its sections.
   - `(t/task 'ns "name" "plan" #{'ns-test/a-proof} #{"app.calc/total-is-exact"})`: a held task,
     its proofs, and the tasks it needs, by id (`"ns/name"`); the last two are optional.
   - `(t/without 'app.calc/inc2)`: the world without that var and its form, to test what a missing
     or deleted definition does.
   - plain tx-data, or `(fn [db] tx-data)`, for anything else.

   A page cites a var by its name (`app.calc/add`) and links to a section relative to the page
   itself (`[calc](calc.md#calc)` from `docs/y.md`). A citation of a name the db does not hold is
   dropped when the page loads (it is no citation); one that later loses its var is a dead citation.
4. **`(t/cost thunk)`**: `{:ticks :bytes :ms :value}`, the work `(thunk)` does, to look at a cost at
   the REPL. A test that claims how work grows with its input is a **cost proof**, the one way:
   `seon.cost/proof` with the function, its input function, the `:growth` (`:linear`, `:constant`)
   and small `:sizes` (`[10 30 100]` show a slope as well as `[1000 4000]`), never a ratio of two
   `t/cost` readings ([cost](cost.md); the tests in seon.ref-test are examples).

**Every test runs under a ceiling of work**, `:seon.config.agent/test-ticks` engine ticks; a test
past it is red. Write tests far under it: a world test usually costs a few million ticks (a few
ms), and the ceiling is coming down to about a second of work (2e8 ticks) once the suite's heavy
tests run on the world. Keep a test's input the smallest that still shows the error it guards. Run the tests your
change reaches with `(my.agent/prove)`: its answer gives each test's verdict and cost (`bin/test`'s
shard log prints `ended <test> <n> ticks` for each), and `(t/cost #(...))` measures any call at the
REPL; you never read result files.

**When the world is not enough**, compose: `t/world` is a db value and every fixture is data, so
`(reduce d/db-with ...)`, your own `(fn [db] ...)` fixtures, or `d/q` over any db you build all
work. A test that must transact through a connection (the writer, a merge) uses a branch of the
test store (`seon.test-store/test-branch!`); say why in its comment.
