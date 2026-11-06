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

## A root costs what it boots

A test that runs a process (a root, its fork server, an agent) waits on boots, not on its own work: `bin/test --all` on 2026-11-06, at load 3-8 on 4 shards, was 1,931 s of tests serial (9.5 min wall), and the slowest tests did 2-9 M ticks of their own in 60-220 s. Measured phases of a root on the seeded process store (idle machine): process start 0.8 s, boot to `ROOT LISTENING` about 5 s (program load 0.7 s, source read 0.65 s, the rest the graph and the boot fact), the first agent fork 2.7 s (the fork server loads main's test namespaces, 1.9 s, and its index, 0.6 s), then an attach 0.1 s and an eval 0.03 s. A root is therefore about 8 s whatever the test does with it; a shared root (`seon.test-store/shared-root!`) pays it once per shard and arguments.

- **A root imports the dirs `bin/test` does.** The fork server loads main's test namespaces through Jolt's AOT cache only when `:seon.config.source/dirs` holds their files (`seon.program/required!`); a root given `["src"]` evaluated 2,274 test forms one by one at its first fork, 11.6 s a root instead of 1.9 s (first attach 13.2 s instead of 2.65 s). `seon.agent-test/in-agent` therefore passes no dirs unless a test names them, and every test shares the one default root.
- **A subject that is not the program boots without it.** A store of a test's own holds no program and no wiki, and a root on it imports the checkout (4,500 forms) and loads 39 pages (15-20 s). A test whose subject is the store (`seon.root-test/bare`) sets `source/dirs` and `root/wiki-dirs` to `[]`. Never on a seeded store: there the same arguments retract the program and the wiki (14-32 s).
- **A main's run is every test main holds.** `main-is-proved-at-boot-with-the-config-on-nobody-asking` ran 1,152 tests (140-180 s) to read two verdicts, because main was a branch of the process store; its own store imported from `[src, patterns_test.clj]` holds two (12 s).
- **Repeating a process is not a stronger claim.** `root-threads-are-what-root-declares` ran three cycles, each a process of its own (82 s); `bin/check lifecycle` repeats the cycle ten times, the test reads one (24 s).

Floors: a test whose subject is a root's life (boot, kill, restart, a generation) takes 8-12 s per boot it makes; `source-test/a-restart-catches-up-and-a-new-generation-resumes` (three boots, two loop turns and a `bin/build seon` of the generation) takes about 100 s.
