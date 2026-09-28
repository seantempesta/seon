# One writer for first population and adoption — 2026-09-28

`index!` already reconciles desired program entities inside the Datahike writer.
Use that operation for an empty store too, handing the construction projection
through `schema/call-with-projection`. Delete the separate fresh-store writer,
its commit helper, and the refusal of an already populated branch.

The current writer database still decides the diff. `construction-projection`
reads construction metadata first, then the explicitly handed projection when
the writer's database has no metadata or schema entities. After population,
stored declarations remain authoritative. Contracts and final-report validation
remain enabled; no validation bypass or second population path is added.
Datahike `db/transaction.cljc` supplies the in-transaction database to a
`:db.fn/call`; `schema/handed-projection` supplies the bound immutable value.

Source change: DB +8/-3, indexer +12/-59 (net -52 together). Remove the obsolete
10-line fresh-store refusal test; add a 30-line 1/10/100-identity regression
asserting one writer call, convergence and exact program rows. Existing
`fresh-population-flattens-with-its-supplied-declarations` proves empty-store
population and exclusive component ownership. Root runs these tests centrally;
this lane started no tests and no drill.

## Evidence

Default restarted to c537242c0 during this work; it remained faulted with 17
open faults. All live writes used a disposable branch with explicit custody.
The thin MCP rejects the old root argument; subsequent probes use its installed
cluster/agent interface (`astra-one-writer`). An early probe under root collided
with an already-running run; the dedicated agent avoids that shared entrance.

Current canonical rows were derived once at the live REPL: 14418 rows, 16.177 s.
That O(program) acquisition prepares the complete-population comparison, not an
ordinary incremental operation. The first attempted input was a historical
published population carrying retired `:seon.fn.arity/order` facts and refused;
no fields were stripped to manufacture parity. Current canonical derivation
replaced that input. An exploratory doc edit also refused its definition digest;
the successful adoption proof changes file digests, whose actual consumer is
publication, and restores those rows from the held source database.

An archive checker creates two empty memory stores under the same current
projection, installs native declarations through `accrete-schema-population!`,
then invokes either the former compile-and-transact writer or `reconcile-call`.
Both use `db/transact!` and its final validator. Each store is released in finally.
No new JVM boot, operator drill or test request is involved.

Final archive basis 08888c8b31f6828ff855d730575ff2cccff307f5, Datahike
76c2a9e936ad1468716551192e75637634ce0420:

| Empty population | Former fresh writer | Reconcile writer |
|---|---:|---:|
| Transaction datoms | 526884 | 526884 |
| Program identities | 14418 | 14418 |
| Time, unarmed archive | 11411.831 ms | 8939.425 ms |

Every normalized program row is equal, including numeric values and portable
refs, not merely counts or the old probe's numeric-value erasure. Transaction
provenance and store-local ids are intentionally outside program-row equality.
This full-population work remains O(program size); no incremental speedup is
claimed from that 21.7% reduction. Earlier pinned-dependency run also matched:
13099.710 → 10560.694 ms.

On live-store branches, the candidate `index!` adopts 1/10/100 changed file
identities without an explicit previous database. It restores exactly the
source's portable rows and reports exactly 1/10/100 changed identities. Three
parent/candidate observations, with the latter two alternating, give medians:

| Identities | Installed parent ms | Scratch candidate ms |
|---|---:|---:|
| 1 | 48.984 | 56.760 |
| 10 | 66.028 | 62.080 |
| 100 | 133.584 | 137.350 |

The candidate outer function is unarmed; installed helpers are armed. These
small differences are within the 20%/50 ms thresholds; no adoption latency gain
is claimed. Indexing uses one reconciliation transaction; there are no fresh
population phases. Successful branch proofs retired their connections/branches.

## Checks and ownership

Archive lint src/script/test, cache false: 0 errors, 1610 warnings, 7.065 s.
Contract compilation: 502 files, 5.457 s, the same ten foreign docstring/markdown
script findings; no new findings. Whole-repository contracts are not clean.
The foreign boundary excludes health/cache/profile/instrumentation edits,
`stamp-transaction`, turn work, and the separately pending loaded-files patch.
The fn_test file's later analyzer-test deletion belongs to that next slice.

Exact central tests:

- `seon.index-reconcile-test/indexing-an-existing-population-uses-one-writer`
- `seon.fn-test/fresh-population-flattens-with-its-supplied-declarations`

Reproduction: run `population-input.clj` in the dedicated scratch MCP namespace,
then `population-probe.clj` from the candidate archive. The generated 30 MB input
is local evidence, not committed. `index-candidate.clj` and `adoption-probe.clj`
are the exact scratch candidate/live branch probe; envelopes are in
`adoption-result.json`, the empty result in `population-result.edn`, all under
`tmp/astra-one-writer-20260928/`.

The one-writer proof is complete. Root can delete the retired `bin/seon drill`
command; no lane change to that foreign-owned operator file is included.
