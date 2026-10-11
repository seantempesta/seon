# C4 runner regression evidence

`seon.test/select` derives the requested reach and adds pending obligations to its candidates. Named requests must select their declared reach; only incremental requests carry earlier outstanding work forward. The change removes that widening from named requests, with no new machinery and no extra reads. Candidate work remains proportional to the selected reach and, for incremental requests, pending obligations.

Task: `seon.test/a-member-body-reads-the-world-its-seeds-prepared`.

## Reproduction

On HEAD 7c4f74ab9, isolated check run 449291e64d7e reproduced all six named Vars: 9 pass, 12 fail, 1 error, 4,926 ms. Operator diagnostic: `data/clusters/default/logs/operator-check-20261012T025456.793.edn`.

- Four seed tests: the synthetic `seon.test.seed-preparation-test/run-seeded` omitted the member connection that `seon.test/member-result` passes to `seon.test/bounded-result`. Its seed writes inherited the outer member's custody and refused a foreign branch. Pass the fixture connection explicitly. The ordered-seed body also destructured the retired database member from the execution handle; read the current database from its connection instead.
- `seon.test-reaching-test/a-reaching-request-selects-what-changed-since-its-previous-run`: pending red candidates leaked into named requests, then eligibility refused them because the narrowed request did not reach them. Run 61c8bcd33e0a exposes `:seon.test/coverage-unknown` instead of the test helper's empty-set rendering. Restrict pending candidates to incremental requests at their producer.
- `seon.sci.eval-test/absent-program-function-is-reported-before-namespace-lookup`: its nil database fails the armed `seon.sci.eval/loaded-database` contract before testing an absent function. Give it a real fixture database; remove the production Var redefinition. The same missing-function data assertions pass.

Intermediate run 61c8bcd33e0a: four of six green; 15 pass, 6 fail, 1 error, 4,992 ms. Run 16fc9efe0766 proves the selection fix green; its ordered-seed candidate predates the corrected connection read.

## Dependency seams

Pinned Clojure 3bc2b3e91: `clojure.core/bound-fn*` captures dynamic bindings; `clojure.test/test-vars` dispatches fixtures and `clojure.test/test-var` reports thrown body errors. No thread machinery changes. Malli dd13ff811: `malli.core/validator` and `malli.core/-instrument` own validation; the synthetic seed runner now declares its two arities over arbitrary seed data and a body thunk, returning `:seon.test.runner/captured-result`. Datahike 61bca1edd: temporal comparison remains the existing `seon.db/since` and `seon.db/as-of` flow; no database change.

## Health and foreign boundaries

Default readiness positive; runtime health faulted (one open fault, two failed tests). Default was never stopped, reset, restarted or loaded with these files. Foreign in-flight edits in `seon.db`, `seon.program`, squash and reconcile tests were excluded from every candidate. Full working-tree lint (`clj-kondo --lint src script test --cache false`) completed in 6,852 ms: zero errors, 1,541 existing warnings.

## Installed candidate proof

Run d521b3daabf0: all six regressions plus unchanged-red reuse, member-world isolation, constructor world, seed keyword indexing and observed-script-change selection are green (11 named non-long Vars, 37 assertions, zero failures/errors, 9,974 ms). Candidate publication 4,265 ms, adoption 868 ms; throwaway boot 16,447 ms, total check 37,525 ms. These are bounded integration checks across branch preparation and recorded runs, not ordinary single-form latency. Memory was not sampled; no memory claim is made. Root's broad tier and default restart remain central work.
