---
type: research
status: active
tags: [sci, operator, test]
---

# Adoption rows — 2026-09-08

Read the AGENTS.md lane rules (the turn PRD §10 copy), all three assigned
issues, the plan README and working edge end to end. Read turn PRD
§§13–15 and the Clojure, REPL, testing, schema and database skills.

## Dependency ledger and initial evidence

- SCI owns reusable contexts and copy-on-write forks:
  `reference-code/sci/src/sci/core.cljc:330–351`. Acquisition and committed
  row installation are `src/seon/sci/eval.clj`'s existing owners.
- Datahike database values are immutable; acquisition receives the cluster
  database after reconciliation. The published database is an input to
  reconciliation, not the database passed to `acquire!` at this HEAD.
- The existing durable fault owner is `seon.cluster/commit-fault!`, shared
  with the Flow committer. Development acquisition now supplies that owner
  through the already-declared `:seon.flow/commit-fault!` callback contract.
- Malli `function-schema` forwards its options to `schema`:
  `reference-code/malli/src/malli/core.cljc:3083`. First-party idiom:
  `test/seon/program_test.clj`'s `parsed-contract` supplies projection
  compile options.

Initial `default` PID 45036 was alive; root footprint 18.83 GiB. MCP health
reported unknown with `Read timed out`; subsequent JVM `(+ 1 1)` returned
2 in 1 ms. This is connectivity evidence, not clean Flow health.
`src/seon/boot.clj` is absent; `seon.boot/refused` is constructed by the
private refusal helper in `src/seon/cluster.clj`.

The shared tree contained unrelated edits, including SCI evaluation tests,
runner files and fixtures. They were preserved; the regression has its own
`test/seon/adoption_rows_test.clj`. The page-feed web-server hunk is outside
this assignment. Tests use HEAD plus the named paths and at most 3 workers.

## Verification

The regression retains the canonical database before adding an
agent namespace, then exercises the development acquisition owner against
the cluster database. It requires one durable fault naming the invalid row
and successful evaluation of the valid sibling function.

Slice 1 gate: `SEON_TEST_WORKERS=3 bin/test --paths
src/seon/sci/eval.clj src/seon/cluster.clj
resources/seon/schemas/seon.sci.eval.edn test/seon/adoption_rows_test.clj
-- seon.adoption-rows-test` passed: **1 test, 10 assertions, 0 failures,
0 errors**. Subject execution 18,166 ms; published-base preparation
68,673 ms; coordinator/test phase 44 seconds. Successful isolated root
`run.xrBYJV` was removed by the gate.

The armed pre-fix probe identified the immediate defect precisely:
acquisition reconstructed both function rows without the required
`:seon.schema.admission/source`. Both failed `install-row!`'s input
contract, including the valid sibling. Acquisition now carries the
provenance derived from the cluster database. The current refresh path
already passed the cluster database; this evidence does not confirm the
issue's historical source-snapshot attribution. The dedicated development
acquisition seam takes the cluster connection directly and captures its
database, preventing a source snapshot from being supplied at that seam.

The separate mixed-host/SCI-generation issue remains open. This assignment
does not establish quiescence or atomic publication across host Vars,
SCI state and projection carriers. No default stop, restart or refork is
authorized or performed.

### First live attempt

`bin/seon init --dev default --changed src/seon/sci/eval.clj` exited 1
after waiting behind other publication commands. Its complete log measured
20,865 bytes. The terminal failure was `IndexOutOfBoundsException` at
`seon.fn/exact-source:142`, before development acquisition. That namespace
had unrelated edits on entry; this lane did not alter them. The existing
issue `docs/seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md`
already records this failure boundary.

The subsequent MCP JVM convergence query was:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      database @(:seon.boot/cluster-connection instance)
      adopted (seon.db/q '[:find ?v .
                          :where [?e :seon.cluster/name "default"]
                          [?e :seon.source/commit-id ?v]] database)
      current (:seon.source/commit-id
               (seon.cluster.source/current (:seon.store/store instance)))]
  {:seon.probe/adopted adopted
   :seon.probe/current current
   :seon.probe/converged? (and (some? adopted) (some? current)
                              (= adopted current))})
```

At that observation, adopted was `6aa08518-86e0-5fe6-bec7-6584cdaa5d02`,
current was `6aa0859a-3a76-5d93-8483-112dd3860298`, convergence false.
Both values must be present; two missing values never count as convergence.

## Slice 2 — diagnostic evidence

The row diagnostic now constructs cause evidence rather than embedding the
original exception data and its execution projection. The boot refusal
helper excludes the top-level execution projection. No presentation
clipping or new renderer was added.

The actual CLI transport owner is `script/seon/fresh_operator.clj`, not the
absent `src/seon/boot.clj`. Its typed boot-refusal arm now reports the
message and offense instead of the entire prepl event history and source
form. This necessary diagnostic edit extends the initially named paths to
that existing owner. A real Clojure `io-prepl` connection verifies it;
`reference-code/clojure/src/clj/clojure/core/server.clj:240–257` supplies
the returned exception data and submitted form.

`seon.adoption-diagnostic-test` measures **1,380 bytes** for a row refusal
whose input carries a **99,000-byte** projection payload, and **159 bytes**
for the operator's typed refusal over the real prepl. The diagnostic gate
also reruns acquisition: **3 tests, 19 assertions, 0 failures, 0 errors**.
Command: `SEON_TEST_WORKERS=3 bin/test --paths src/seon/sci/eval.clj
src/seon/cluster.clj script/seon/fresh_operator.clj
test/seon/adoption_diagnostic_test.clj -- seon.adoption-diagnostic-test
seon.adoption-rows-test`. Subject times: 49 ms, 4 ms and 34,281 ms;
coordinator/test phase 69 seconds.

The second explicit live adoption reached SCI acquisition and JVM
instrumentation, then refused to stamp the commit because source changed
during adoption. Its terminal refusal measured **151 bytes**, two lines;
the full progress/warning log was 63,670 bytes. A convergence retry follows.

## Slice 3 — projection-aware contract census

`every-public-schedule-contract-compiles` uses the canonical fixture's
projection compile options and requires a nonempty subject census.
`SEON_TEST_WORKERS=3 bin/test --paths test/seon/schedule_test.clj --
seon.schedule-test` passed **8 tests, 64 assertions, 0 failures, 0 errors**;
the fast suite had the same tally. Gate coordinator/test phase: 61 seconds.

Audited `rg -n '\(m/(function-schema|schema)' test/`. Other first-party
schema compilations already supply projection options. The remaining
unqualified calls are intentional: `program_test.clj` compiles generated
built-in-only shapes; `search_test.clj` compiles an explicit `:fn` predicate;
`registry_isolation_test.clj` asserts a private named schema is unavailable
in Malli's default registry. Calls to `function-schemas` inspect the global
registry and do not compile contracts.

## Final live verification and platform boundary

The final explicit `bin/seon init --dev default --changed
src/seon/sci/eval.clj` exited **0** and printed `development cluster
converged`. Its log measured 56,660 bytes including ordinary warnings and
progress. MCP independently read both commit IDs as
**`6aa08a34-675a-5c0b-b268-afdbf5d35d21`**, convergence **true**. A direct
probe of the in-place-adopted `acquisition-refusal` with the same
99,000-byte projection payload returned **1,162 bytes** (without the boot
wrapper; its cause text differs from the test). No default stop, refork or
restart occurred. This proves in-place adoption and the diagnostic helper;
it is not an atomic-generation or browser-paint claim.

The first platform attempt failed before worker readiness: its HEAD runner
launched `pool-4` although `SEON_TEST_WORKERS=3` prepared only three
checkouts. The missing checkout could not load `seon.test.runner`. See
[the worker-count issue](../../../seon/issues/platform-worker-count-exceeds-prepared-checkouts.md).

The retry supplied `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=6` and
`SEON_TEST_WORKERS=3` to the same `bin/test --paths … --platform` gate,
including all owned production and test paths. It reported **workers=3**,
**81 tests, 448 assertions, 1 failure, 0 errors** in a 184-second
coordinator/test phase. Its independent confirmation reproduced
`seon.test-support-test/an-instrumentation-test-restores-the-entering-contracts-on-failure`
at `test_support_test.clj:74`: the test expects all instrumentation to be
removed. Separately, the cohost boot test added eight instrumented fixture
Vars to its worker. The existing
[instrumentation-fixture issue](../../../seon/issues/a-platform-test-leaves-its-worker-stripped-of-every-contract.md)
tracks that family. Both test files and the runner contained unrelated
uncommitted edits on entry and remain untouched by this lane. These are the
exact platform verification boundaries, not green platform evidence.

Commits: acquisition **`152f11a68`**; diagnostics **`4d47b62d0`**; the final
path-limited commit contains the schedule census and this verification note.
No `--all` or `--full` run was used. The owned failed roots were checked
for live JVM holders before deletion; successful scoped roots were removed
by the gate. No scratch cluster or worktree was created.
