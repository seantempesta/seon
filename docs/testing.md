# Testing

How a test is written in Seon: on a small world, with what it adds, cheaply, one default way.

## Testing in the world

A test runs on **the world**, a small db shaped like production, plus what the test adds. Never build a test on the real program, the real wiki or a store of its own: those cost seconds and a store, the world about a millisecond.

```clojure
(ns app.x-test
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [datahike.api :as d]))

(deftest f-is-recorded-with-its-contract
  (let [db (t/with (t/world)
                   (t/code 'app.x "(ns app.x)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"))]
    (is (= "[:=> [:cat :int] :int]" (:var/contract (d/entity db [:var/name 'app.x/f]))))))
```

1. **```my.test/world```**: the base world, a db value. It holds a program (`app.calc`: `add`, `inc2`, `total`, the macro `twice`; `app.report/line`, which calls `app.calc/total`), its tests (`app.calc-test`), a wiki page (`docs/calc.md`, citing them) and a task (`app.calc/total-is-exact`). Use these before adding your own. `(t/world {:history? true})` keeps history, for a test of `d/history` or `d/as-of`.
2. **```my.test/with```**: the db with each fixture added in order. A value: nothing is written. Most tests need only this.
3. **The fixtures:**
   - `(t/code 'ns.name "source text")`: a namespace as an import records it;
   - `(t/page "docs/x.md" "# X\n\ntext citing app.calc/add")`: a wiki page and its sections; a citation of a name the db does not hold is dropped at load;
   - `(t/task 'ns "name" "plan" #{'ns-test/a-proof} #{"app.calc/total-is-exact"})`: a held task, its proofs and needs (the last two optional);
   - `(t/without 'app.calc/inc2)`: the world without that var and its form;
   - plain tx-data, or `(fn [db] tx-data)`.
4. **```my.test/cost```**: `{:ticks :bytes :ms :value}` of a thunk, to look at a cost at the REPL. A claim about how work grows is a **cost proof**, `seon.cost/proof` at small `:sizes` ([cost](cost.md)), never a ratio of two readings.

Every test runs under a budget of engine ticks and is red past it ([tick budget](merging.md#a-tests-tick-budget)); a world test costs a few million. Run what your change reaches with `(my.agent/prove)`; it gives each test's verdict and cost.

When the world is not enough, compose: it is a db value and every fixture is data. A test that must transact through a connection (the writer, a merge) uses a branch of the test store (```seon.test-store/test-branch!```) and says why in its comment.

## A proof is focused

A proof makes one claim, statable in a sentence, about what a behaviour does, not how. It fails when the behaviour is broken, and its failure says what is wrong. See it red first: copy the base's file into the tree (`git show <base>:<path> > <path>`), run the proof, copy yours back; never `git stash`, whose stack every worktree shares. An assertion that decides neither correctness nor done is removed; a proof another already makes is merged into it; a process test whose claim a world can show moves to the world.

Build a failure message only on failure: `clojure.test` evaluates an `is` message whether or not it passes, so a costly diagnosis goes inside `(when-not ...)` of the claim. To see a value from a test, fail an assertion that carries it.

Its input is the smallest that still shows the claim. To make a test cheaper, restate its claim and find the input that shows exactly that; shrinking sizes is the last resort, and a cost proof shrunk too far can no longer judge growth.

## A root costs what it boots

A test that runs a process (a root, its fork server, an agent) waits on boots, not on its own work. A root boots to `ROOT LISTENING` in a few seconds; the fork server does the first fork's work as the boot ends (```seon.fork-server/preload```), so a later spawn takes 0.1-0.3 s; `bin/build seon` warms the binary's compile cache with the tests (```seon.check/warm-tests```). So:
- **Share a root.** ```seon.agent-test/in-agent``` and ```seon.agent-test/with-agent``` use the shard's shared root (```seon.test-store/shared-root!```, [merging](merging.md#a-merge-runs-what-the-change-reaches)); a bound a task can state (an agent's heap) rides `:seon.task/config`, not a root argument. Only a test whose subject is a root's life or death, or whose agent merges into main, takes its own; such tests share one boot where their subject allows (`seon.agent-test/an-agent-ends-when-its-root-is-gone`: three agents, one root).
- **A root imports the dirs `bin/test` does.** The fork server loads the test namespaces through Jolt's AOT cache only when `:seon.config.source/dirs` holds their files; `in-agent` passes no dirs unless a test names them.
- **A subject that is not the program boots without it.** A test whose subject is the store sets `source/dirs` and `root/wiki-dirs` to `[]` (never on a seeded store, where that retracts the program and wiki). A restart's claims ride a scratch tree of one file, never a clone of the checkout.
- **Repeating a process is not a stronger claim.** One cycle is a test; `bin/check lifecycle` does the repetitions.

## A test reads the store itself

A test reads a root's branches from the store in its own process, never by asking the root: ```seon.test-store/read-branch```, ```seon.test-store/read-main``` and ```seon.test-store/read-task-branch```, through one read connection per store path (```seon.test-store/reader```). An agent commits a request's writes before it replies, and the writer commits before it answers, and LMDB's readers see every process's commits ([storage](storage.md)). In `in-agent`, a function among the forms runs in the test's process with `{:root :names}`. A read through root costs a request, an eval and a commit on root's main (about 14 ms against 1 ms direct). Ask root only when the claim is that root answers, or for state it holds only in memory (a proc's state).

## Test kinds

A test is what its deftest's metadata states: each metadata key an entity schema owns is written on the test's var as that attribute's fact (```seon.program/def-tx```), so a test is named as any entity is (```seon.db/most-specific```). Three sets of attributes:
- **`:seon.test/test`**: `:seon.test/estimate`, `{:ticks n}` worked from what it must do (optional `:bytes`, `:asks`, `:execs`). It is given the shared world (`my.test/world`, `seon.test-store/test-db`, `test-branch!`) and runs in process.
- **`:seon.test/system-test`**: also `:seon.test/root`, the root's arguments it asks for (`[]`: the shard's shared root). It is given a running system.
- **`:seon.test/binary-test`**: also `:seon.test/execs`, the commands it runs (`["target/seon"]`, `["bin/check"]`).

Caps are config: `:seon.config.proofs/test-ticks-cap`, `system-test-asks-cap`, `binary-test-execs-cap`.

**The law** (```seon.problems/undeclared-tests```, a caused `:undeclared-test`): a test a branch adds or changes states its estimate within its cap, names its root when it reaches `:seon.config.proofs/system-vars`, names its commands when it reaches `exec-vars`, and makes no store of its own (`own-store-vars`). Its reach is read from the compiler's facts (`:form/refs`) through the test namespaces' code. A test the branch did not change is not judged.
