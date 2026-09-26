# Landing: agent test and auto-check worlds (lane agent-test-worlds-opus, bundle B1), 2026-09-26

Seon's auto-check (`seon.test.accretion/auto-check`) generated every argument, including
`:seon.db/database-value`. That produced an empty Datahike value with no installed schema. The fix: an
argument whose declared schema names a supplied default is now produced by call preparation's own supplier
from the candidate's environment (`seon.call-preparation/snapshot` + `supply`), and it is fed into
Malli's generator as a registry overlay with a `:gen/gen` property
(`reference-code/malli/src/malli/generator.cljc:468`, `-create-from-gen`).

## Broken / not done (read first)

1. **Dogfood #6 is NOT fixed, and it has a second, larger cause outside my files.** A REPL `deftest`
   member cannot call `(my.plan/current)` because **call preparation does nothing in every function
   acquired from program rows**. Evidence (branch `agent-test-worlds-opus`, default archive 5bed888d0),
   from one test body run by `my.test/check`:
   - `(plan-now)`, an agent defn whose body is `(my.plan/current)`, gave `refused argument count at []`:
     nothing was supplied.
   - `((fn [] (my.plan/current {:seon.agent/id "root"}))) ` gave `refused request at [:seon.db/db]`:
     not even the database was supplied.
   - `(eval '(my.plan/current {:seon.agent/id "root"}))` in the same body gave `{}`: supplied.
   - JVM replay: in the member context `sci.core/eval-string*` of `(my.plan/current {})` does run the hook,
     and answers `:seon.call-preparation/unavailable-key :seon.agent/id`.
   So SCI's call path reads `:call-preparation-hook` from the ctx that the *fn closure* captured
   (`reference-code/sci/src/sci/impl/analyzer.cljc:1796`). An acquired function was evaluated in the
   memoized program base (`seon.sci.eval/acquire!` → `base-ctx`, `src/seon/sci/eval.clj:3355-3377`),
   and that base carries no custody connection, so `hook` passes arguments through unchanged
   (`src/seon/call_preparation.clj` `hook`: `(nil? database) arguments`). This covers every acquired
   function, not only tests: another agent calling mine, and anything after a reload. At its own REPL an
   agent's same-context definitions survive as objects, which is why `(my.plan/current)` works there.
   **Owner decision needed** (sci/eval.clj, call_preparation.clj, the SCI fork; none of them are mine):
   - (a) *simplest:* the hook reads the environment of the ctx that armed the current evaluation
     (`kernel/with-arm` already binds the arm per thread), not the closure's. About 10 lines in
     call_preparation.clj plus kernel.clj. It sacrifices "suppliers never read a dynamic".
   - (b) **recommended:** S12's ruled retirement: defaults applied by the callee's armed wrapper,
     which reads the custody the execution binds. This removes the SCI hook and its closure dependence.
     It is the already-ruled plan step and costs hours.
   - (c) acquire agent rows into each execution's own fork instead of the shared memoized base.
     This loses the shared-base speedup (389 ms → about 29 ms per member).
2. **The member world still has no agent row and no agent id** (my half of #6, not landed). Probe:
   `seon.cluster.agent/creation-tx` onto the request branch costs 88 ms and makes the agent visible in
   the member. `acquire-context!` with an agent id costs 626 ms against 31 ms, and still leaves
   `:seon.agent/id` absent from the member ctx's environment (`fork-cluster-ctx` strips it; only
   evaluation entrances scope it). Seeding alone makes no documented example pass while item 1 stands,
   so I did not add that cost. Whether a test world should seed the executing agent, or branch from the
   agent's own commit (which deletes the checkpoint and overlay machinery, about 200 lines, and the
   1.0 s `clean-request-handle`), is against harness design §2 step 1 ("never inherit its
   faults/messages"). That is an owner ruling.
3. The q refusal's `:seon.error/offending` still holds a `datalog.parser.type.Placeholder` host object
   (db.clj, bundle B2). No db.clj patch was needed for this slice.
4. I leaked one request branch during a probe (`:agent-d86065655bcc`, an exception before `try`). I found
   it by its connection and overlay row, released it and retired it in the same session. There are 49
   unconnected `agent-*` branches in default's roster from other runs. Retention GC reclaims them;
   they are not mine.
5. Ugly output: an armed input refusal of `seon.call-preparation/supply` (one missing key) rendered as
   182 KB over MCP. The instrument explanation items nest render-elision maps inside their own
   location keys.

## Landed (dogfood #13 and the auto-check "actual" issue)

- `supplied-registry` (new, contracted, private): for each supplied-default row, it calls `supply` in the
  candidate ctx's environment (scoped with `:my.program/executing-ctx`, as `hook` does). Each value it
  gets becomes a `gen/return` on that schema key. It supplies a database value or a scalar (the agent
  id). A connection, the program context (a map of live members) and a refusal map keep their
  generators, so seeded cases never write into the agent's world. Matching is by value schema name
  wherever it occurs, which is a superset of call preparation's slot and map-entry placement.
- `observation`: `actual` is the whole returned value. It is the refusal's `:seon.error/offending` only
  when the armed output check refused (`:seon.instrument/check :output`). A call whose record outcome is
  not `:ok` threw: it fails with its whole error and no output explanation. The old `(or offending
  returned)` produced three defects: it showed the callee symbol as actual for a throw, the lookup ref
  for a pull throw, and the parsed clause for a *returned* `seon.db/q` refusal that the contract
  declared.
- `advisories`: "declare the error branch" is advised only for a returned error, never for a throw.

## Evidence (JVM scratch copy of the new namespace against a real candidate on my branch)

The candidate came from `sci.eval/evaluate-candidate` in the hosted agent's ctx. Old = installed
`seon.test.accretion/auto-check`, new = scratch copy, same request and seed.

| candidate | old | new |
|---|---|---|
| `agent-ids` `[:=> [:cat :seon.db/database-value] [:vector :string]]`, seed 227112335144092358 | failed 1/25: `seon.db/q cannot read uninstalled attribute :seon.agent/id` on `max-eid 0` db (18.7 ms) | **passed 25/25** (14.0 ms) |
| `dre-fifth` `(* x x x x x)`, seed 464313113631840478 | failed 16/25, actual `user/dre-fifth`, why "should be an integer" | failed 16/25, args `[6209]`, actual `seon.sci.kernel/failure-value — … (ArithmeticException) … long overflow`, no why |
| map entry `[:map [:seon.db/db :seon.db/database-value]]` | passed (counted the 9-key refusal map!) | passed 25/25, reading the agent's db (9.9 ms) |
| `wrong-shape` returns `"x"` for `:int` | failed, arg empty db | failed, actual `"x"`, why "should be an integer", arg = agent branch db |
| `who` `[:=> [:cat :seon.agent/id] :int]` | failed on generated `"0"` | passed: the candidate env supplies `mcp/agent-test-worlds-opus` |
| `quot x 0`, seed 424242 | — | failed 1/25, actual ArithmeticException "Divide by zero", explanation nil |

`supplied-registry` measured 3.4 ms and supplied `#{:seon.agent/id :seon.db/database-value}`. Its declared
input and output validated with `m/validate` against the real request and value, and so did
`auto-check`'s `:seon.test.accretion/auto-check-result`.

Pre-commit static checks: `seon.contracts-compile-test/check` over both changed files gave `[]` (13 contracts
read, 367 ms). The committed-archive `every-declared-contract-compiles…` passed (reused), and
`every-packaged-schema-resource-reads-as-edn` passed (reused). clj-kondo: 0 errors, 0 warnings.

## Changes

- `src/seon/test/accretion.clj` +58 −9. `test/seon/test/accretion_test.clj` +46 (one class regression:
  `auto-check-calls-in-the-agents-world-and-keeps-a-throw-whole`). Net +95, and no parallel path was
  added: the auto-check now calls the one supplier owner instead of a generator.
- Diversity note: a supplied schema is generated as one constant (the agent's db and id). A property
  over varied agent ids needs its own schema.

## Root runs

    bin/test-check default --test seon.test.accretion-test/auto-check-calls-in-the-agents-world-and-keeps-a-throw-whole
    bin/test-check default --ns seon.test.accretion-test
    bin/test-check default --ns seon.sci.eval-test   # evaluate-candidate / auto-check-candidate callers

Adoption: `accretion.clj` changes a loaded namespace, so default needs to adopt the commit (**RESET NEEDED**
or `start --head`) before the dogfood lane retries cycle 4's `book-titles`.
