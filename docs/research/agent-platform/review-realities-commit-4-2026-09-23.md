---
type: review
status: changes required
created: 2026-09-23
---

# Review: realities commit 4

Reviewed `678009fcd` and documentation follow-up `c34b814c9`; source citations below refer to `678009fcd`, not concurrent
working-tree edits. Verdict: **changes required**. Read AGENTS.md, README §7 and lane-realities-one-lifecycle.md. This is
a static diff/call-path review, not a rerun of the landing's scratch-cluster proofs.

1. **P1 — Config protection misses provenance-owned rows (claim 3 falsified).** `src/seon/config.clj:677` excludes
   referenced configs only from adopted identities. But `:687` still supplies the single config process (`:37`), and
   `src/seon/reconcile.cljc:351` includes entities by adoption **or first-write process**; `:399` retracts managed
   entities absent from desired. With retained history, apply A, attach a cluster to A, then apply B: A is still selected
   for retraction and its required ref makes B refuse. The patch helps configs originally written by another process, not
   every config. Smallest fix: scope config reconciliation's managed set to the target cluster/unowned inherited rows at
   the writer, including provenance; regress sequential A/B applies using the real config writer.

2. **P1 — Execution ignores admission reservations; unfinished work can overlap (2/4).** `src/seon/test.clj:1059`–`:1069`
   makes pending members covered-by another request, but `:1489`–`:1505` executes the pre-admission `runnable` list
   anyway. Concurrent identical requests can both run the same body. Worse, timeout recording sets completed-tx without
   terminated-tx (`src/seon/test/runner.clj:2829`–`:2836`), while admission only tests completed-tx: another request can
   start before actual exit. Smallest fix: execute only writer-reserved members, retain unfinished ownership until
   observed exit, and settle that exit through the recorder; test overlapping requests.

3. **P1 — A request can report green with excluded long tests (4).** Selection produces long-excluded at
   `src/seon/test.clj:696`; `:1537` builds `excluded` from destructive/deferred only, and `:1549` uses that incomplete
   set. A namespace request with a passing short test and a skipped long test returns passed? true despite the declared
   guarantee in `resources/seon/schemas/seon.test.edn:308`. Smallest fix: include long-excluded in the verdict; regress
   this mixed request.

4. **P1 — Member setup/cleanup is not exception-safe (2).** After acquisition at `src/seon/test.clj:1327`, resolution and
   registry inspection (`:1339`, `:1349`) have no enclosing cleanup scope. A throw strands the child; the outer finally
   (`:1533`) releases only the request handle. Watcher cleanup (`:139`) has no recorded failure path;
   `src/seon/cluster/agent.clj:719`–`:730` stops on its first release failure, potentially skipping unlink/context
   removal. Smallest fix: bracket acquisition immediately, transfer cleanup ownership only after thread start, attempt
   each cleanup, and surface primary plus cleanup causes.

5. **P2 — The shared entrance is used, but host custody still diverges (1).** Member acquisition/release
   (`src/seon/test.clj:1327`, `:1362`) and canonical fixture acquisition/release (`test/seon/test_support.clj:698`–`:703`)
   call the agent owners. Forking/custody also call production functions (`:372`, `:651`); no copied ordinary branch
   allocator/unlinker remains. However, `src/seon/test.clj:1354`–`:1361` deliberately omits the member connection for JVM
   Vars; the new regression requires nil custody (`test/seon/test/one_request_test.clj:18`). Ordinary host tests therefore
   do not receive agent-equivalent db/conn elision. Smallest fix: make the production entrance serve host members with
   their branch custody; retain explicit host-platform exceptions.

6. **P2 — Throwable causes are discarded (5 falsified).** New `bounded-result` catch at `src/seon/test.clj:161` keeps only
   ex-message; surviving resolution catches (`:1189`–`:1194`) likewise discard ex-data, cause chain and frames. Smallest
   fix: retain the full throwable through the existing error owner or rethrow it after cleanup; regress a nested exception
   carrying ex-data.

7. **P2 — Contracts and replacement coverage are incomplete (5).** No literal :any was added in the reviewed source/schema
   diff, but rewritten fixture functions lack contracts (`test/seon/test_support.clj:660`, `:692`, `:705`), and bounded-result's callback is merely ifn? (`src/seon/test.clj:113`). New coverage proves isolation/reuse/pending/exclusion
   (`test/seon/test/one_request_test.clj:70`–`:110`), not noncooperative exit, admission overlap or cleanup failure.
   Duration tests only synthesize reporter elapsed time (`test/seon/test/duration_test.clj:52`–`:56`). Old base/run-owned
   tests were removed, but deletion alone does not supply these surviving behavior proofs. Smallest fix: complete
   signatures and one regression per class.

Claim 2 detail: exit is observed by Thread.join (`src/seon/test.clj:139`, `:141`), never inferred from Future
cancellation. A body that never exits retains its branch, connection, context and watcher indefinitely; no cancellation is
requested. The returned unfinished value and tally name its branch (`:1377`, `:1588`), so it is visible to that caller.
The recorder explicitly strips the branch (`:1509`), and the watcher never records eventual exit. Persist that
association/settlement for recovery.

Claim 3, issue.clj: `src/seon/issue.clj:190`–`:203` correctly reads keys and schemas from one projection at the citation
owner; it is not a test-specific special case. It does not fix the stale projection described at `:194`: newly added
citation declarations remain invisible until that projection advances. Verify incremental new-citation adoption and fix
projection acquisition at its producer if it fails.

Claim 4 otherwise: positive terminated per-member evidence (`src/seon/test.clj:475`), input identity (`:561`), current
gate walk (`:634`), and each member's tested-basis reach comparison (`:729`–`:767`) support conservative reuse; missing
analysis refuses at `:670`. All-reused selection starts no handle/body (`:1491`). Findings 2/3 qualify this.

Claim 6: **not a new stamp**. `src/seon/sci/eval.clj:2294`–`:2301` compares Datahike's commit IDs, allowing sibling
connections at one commit to reuse acquisition. Datahike `fbd1ad2d10fb1261ef7092737a02801537c16e70`, `reference-code/datahike/src/datahike/db.cljc:385`–`:398`, returns identity only for attached committed raw values; speculative
values return nil. Custody is repointed separately (`src/seon/sci/eval.clj:2429`–`:2453`).

Boundary/timing: source/tests/resources unchanged; no gates, reload, boot or other lane sessions touched. `bin/seon status` 0.144 s; MCP runtime_status 1.097 s total (internal phases unavailable): default alive, three errored receipts,
turn ping unknown. Review-document creation including hook feedback took 1.4 s (phase split unavailable). Other inspection
operations <1 s. These observations do not prove this slice adopted on default.

Markdown-hook warnings also cite unrelated historical gitlinks outside this file; those files were left untouched.
