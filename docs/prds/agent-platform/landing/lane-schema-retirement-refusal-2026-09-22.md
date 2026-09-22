---
type: landing
status: landed-on-refactor-agent-platform
created: 2026-09-22
tags: [agent-platform, schema, admission]
---

# Schema retirement preserves literal write evidence

README §4 row 1.3e, owner ruling option 2: write facts describe source independently of the schema population. The initial fact-gap stop is superseded by this ruling and implementation.

## Change and owner

`src/seon/fn.clj`, `writes-by-writer` at line 472, no longer filters observed qualified keywords through declared schema keys. Both analyzed-form and indexed-file production use the same producer. The now-unused schema query in `analyze-forms` is removed; private row production no longer transports the declaration set. The public `source-rows` arity remains compatible. The existing private-row regression's call is converted in `test/seon/fn_test.clj`.

The final transaction owner remains `src/seon/db.clj:3889`, `removed-definition-error`, reached through `write-deletion-error` and `write-report-error`. It already queries surviving `:seon.fn/writes` and schema/arity edges on the final database, so same-transaction conversion counts. No second guard, scan, cache, predicate or keyword-mention heuristic was added. Its output union now explicitly names `:seon.program/deletion-refused-error`; the refusal exposes the attribute/referrer relations and names them in its message. The named schema in `resources/seon/schemas/seon.program.edn` describes transient evidence; the existing write recorder owns durable error projection.

`test/seon/schema_retirement_test.clj` uses the canonical fixture and `program-fn-row`: analysis with the declaration absent must retain the write; a declared attribute with a surviving writer refuses retirement without advancing the basis; conversion and retirement together succeed. Native tuple retraction supplies the actual `[seon.db/transact! 2]` value, because this dependency's `retractAttribute` path rejects an absent heterogeneous tuple value.

Scope is the existing analyzer's lexical write-call evidence. Computed keys and transaction data built outside the call span are not inferred. Qualified literal keyword values inside the call may be conservatively included, as with the existing span join. `:seon.fn/references` holds function symbols, not attribute names. This slice does not use `:seon.fn/keywords` as a guard.

## Dependency seam and work

clj-kondo gitlink `57252e07975710aa579b24f0d1b2b1e04195caa2` supplies keyword namespace/name/location facts (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:176`) and function-call spans (`reference-code/clj-kondo/analysis/README.md:123`). `seon.fn.analyzer` requests and normalizes those facts; the producer joins supplied entries by filename and span. Recomputed when source is analyzed; work follows analyzer usages and the corresponding file's keyword entries. Removing declaration membership adds no traversal and deletes a database-wide schema-key query from submitted-form analysis.

Datahike gitlink `cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`, `reference-code/datahike/src/datahike/db/transaction.cljc:1206`, runs the supplied final-report validator and rejects any non-nil refusal before commit. Seon's existing callback receives before/after databases and affected datoms; removed-definition checking uses indexed edge lookups in that final value. No dependency changes.

## Initial isolated live proof

Default was only inspected: `bin/seon status`, MCP `runtime_status`, and a read-only JVM producer probe. PID 28922 advertised a reachable prepl but lacked connection/program/config/flow readiness. No default reset, reload, adoption or runtime mutation occurred.

Before edit, the installed producer returned a write for `#{:example/attribute}` declarations and `{}` for the same analyzer input with no declarations, in 1 ms. After edit, the scratch-root read-only MCP probe returned `#:example.writer{write! #{:example/attribute}}` without any declaration input and showed the named owner output contract, in 1 ms. The exact analyzer input is retained in `tmp/orchestrator/schema-retirement-producer-repl.edn`.

The final armed in-process proof used the real canonical fixture, real source-row producer and real transaction writer. It supplied a complete canonical namespace row directly to `seon.fn/source-rows` because the canonical synthetic-function helper is currently broken (below). It did not replace any function or disarm contracts. Packaged initialization armed 1,685 contracts, 1,679 program-armable, across 107 program namespaces.

Exact evaluated form:

```clojure
(let [result (load-file "/Users/sean/src/seon/tmp/orchestrator/schema-retirement-direct-proof.clj")]
  (spit "/Users/sean/src/seon/tmp/orchestrator/schema-retirement-direct-result.edn"
        (pr-str result))
  result)
```

The form asserted seven conditions and completed in **739.459875 ms** (prepl 742 ms): absent declaration yields `#{:retirement.proof/value}`; retraction returns `:seon.db/transaction-refused true` with `retirement.proof/write!` as referrer; refused basis is unchanged; the converted function and schema retirement commit together. Result and exact form are retained in those two files. This is an armed, in-process behavioral proof, not a recorded test-run pass.

## Initial recorded-test and boot boundaries

The required focused command was run with HEAD plus only owned paths:

```sh
bin/test-fast --paths src/seon/fn.clj src/seon/db.clj resources/seon/schemas/seon.program.edn test/seon/schema_retirement_test.clj test/seon/fn_test.clj -- seon.schema-retirement-test
```

After isolating foreign hunks, it loaded the named test and armed all 1,685 contracts, then refused recording: **Test recording requires a published current-src**. Run `b90a732855cf`, zero executions, no recorded green. Log: `tmp/orchestrator/schema-retirement-fast-4.log`. No cold test gate or platform suite was run.

The cached canonical base `277ae2d23e467557e97a6d2a832bfd23a95551bf21dc3b0463b24914266aeead` is 49 commits behind the isolated HEAD and fails projection acquisition on deleted `seon.search/ping-map-fn?`. The scratch boot completed actual source publication before cluster initialization failed, so the second in-process attempt selected that root's actual `data/store` as the canonical fixture base. The fixture cloned it and branched from `current-src`; no hand-populated replacement base was invented.

That attempt reached another foreign boundary: `test/seon/test_support.clj:1026`, `program-row`, supplies `{:seon.ns/name ...}` to `source-rows`, whose `:seon.ns/ns` contract now requires `:seon.program/definition-digest`. The requested test therefore reports 1 test, 0 passes, 0 failures, 1 setup error, in 630.630166 ms. `program-fn-row` remains in the regression; the helper and excluded namespace producers were not changed. The direct proof supplies a complete namespace through `program/declaration-row` and exercises the same underlying producer and transaction owner. Evidence summary: `tmp/orchestrator/schema-retirement-fixture-limit.edn`. Related existing issue class: `docs/seon/issues/class-loaded-artifacts-lack-source-identity.md`.

Because this slice changes a schema resource, it ran:

```sh
bin/seon --root /Users/sean/src/seon/tmp/schema-retirement-root reset --force
```

Final isolated attempt: PID 37108, start `2026-09-22T17:28:35.669Z`, terminal refusal `17:30:53.578Z`, **137,909 ms**. Schema admission and source indexing completed; cluster population refused `my.agents.root` missing `:seon.program/definition-digest`. The operator additionally reported non-EDN evidence in that refusal. Healthy boot is not claimed. Log: `tmp/orchestrator/schema-retirement-boot-4.log`; retained JVM log and thread samples describe whole-program publication work; observed RSS 3.4–3.7 GB. The broad publication/adoption measurement script was not run past this failed boot; no successful publication-path clock is claimed.

Earlier attempts caught and corrected two authored error-schema mistakes (narrowing inherited fields, then treating transient referrers as storable); a subsequent attempt encountered the foreign reverse-closure schema dependency. Each owned scratch process was stopped or positively observed exited before the next attempt.

## Initial isolation and subsequent integration

The shared tree developed foreign syntax errors in excluded `sci/eval.clj` and later `test/seon/sci/branch_execution_test.clj`. More importantly, foreign reverse-closure changes appeared in `src/seon/fn.clj` after this lane began, with the companion `seon.sci.execution.edn` absent from HEAD. The initial `--paths fn.clj` snapshot therefore included a foreign reference to missing `:seon.fn/reverse-closure-result` and could not arm.

The authorized detached checkout `tmp/schema-retirement-wt` at `62487dbc31ee62c4df47b47f118e90d6d9d556b9` carries only this lane's changes; its reverse-closure section uses HEAD bytes. Vendored dependencies and the existing published-base cache are linked, not copied or modified. Its successful namespace loading, contract arming and direct proof are evidence for HEAD plus owned changes, not current shared-tree health or live adoption.

At the initial handoff, shared `fn.clj` had foreign hunks, so the tested slice was committed in the authorized isolated checkout. That ownership boundary is now closed: `39a337013` carried the production hunks verbatim and attributed them to `f6b175e6d`. The owner authorized restoring the three remaining lane files onto `refactor/agent-platform`; no branch switch was performed.

The final focused rerun again loaded and armed 1,685 contracts, then refused recording on missing `current-src` (run `fd2b7232af7f`, `tmp/orchestrator/schema-retirement-fast-final.log`). The last namespace-source and tuple-retraction corrections are also present in the final load check; direct proof above exercised these exact forms.

## Initial commit and cleanup evidence

The implementation was first committed as `e2ea35f87` in the isolated checkout: six paths, 175 insertions / 30 deletions. A fresh `clojure -M:test` process required `seon.cluster.boot` and `seon.schema-retirement-test`, printed `:committed-head-loaded`, and exited 0 (`tmp/orchestrator/schema-retirement-head-load.log`). The evidence-only amendment produced `f6b175e6d`, initially retained on `codex/schema-retirement-refusal`. Its content is now on the main refactor branch: production in `39a337013`, and remaining tests/note in this completion slice. The temporary branch is deleted after the completion commit.

All owned test, load, prepl and boot processes exited. `lsof -t +D` returned no holders for the scratch root or isolated checkout (empty evidence files, exit 1). The JVM log is retained as `tmp/orchestrator/schema-retirement-jvm.log`; the scratch store was removed without losing the proof. Dependency/cache links were unlinked before owned checkout cleanup, so recursive cleanup could not follow them. The isolated checkout was removed; unrelated shared edits were preserved.

## Main-branch completion and F4 correction

Production hunks landed verbatim in `39a337013`, whose body attributes them to `f6b175e6d`. On `refactor/agent-platform`, the owner-authorized checkout restored only this lane's regression, landing note and `fn_test.clj`. The latter's diff against HEAD is exactly one removed argument line at the existing `analysis-rows-by-file` test call.

The follow-up removes the nested `:seon.error/data` referrer copy from `removed-definition-error`, retaining the declared `:seon.program/referrers` member. This is README §7's F4 correction: no reconstituted error shape beside the owning member. The regression additionally asserts that the nested copy is absent. No schema resource changed in this follow-up.

Main HEAD plus the owned changes loaded `seon.cluster.boot` and `seon.schema-retirement-test` in a fresh JVM and exited 0 with `:head-plus-owned-loaded` (`tmp/orchestrator/schema-retirement-main-load.log`). No branch switch or additional checkout was needed.

The old cached fixture still predates search retirement, so an owned scratch reset supplied a current publication for the rerun. `bin/seon --root tmp/schema-retirement-main-root reset --force` exited 0 and reported **101,378 ms readiness**, zero missing layers, one agent, no problems, source commit `6ab2c318-8632-591a-a941-55301f3ef9bf`. PID 44240 started at `2026-09-22T18:02:21.836Z`. Its process was subsequently observed absent and MCP found no live advertisement; the readiness response is completed boot evidence, not a claim that this scratch service remains running. Log: `tmp/orchestrator/schema-retirement-main-boot.log`.

The owned JVM io-prepl evaluated:

```clojure
(load-file "/Users/sean/src/seon/tmp/orchestrator/schema-retirement-main-proof-runner.clj")
```

The runner selected the completed scratch publication as the canonical fixture base, initialized packaged contracts, and invoked the same direct production-source/transaction proof with the added absence assertion. Arming: **1,692 registered and instrumented, 1,686 program-armable, 107 program namespaces**. Result: **eight assertions passed, 2,118.052209 ms**, including cold canonical fixture acquisition; `:schema-retired? true` and `:referrer-copy-absent? true`. The unchanged-basis refusal names `retirement.proof/write!`; conversion and retirement commit together. Exact script/result: `tmp/orchestrator/schema-retirement-main-direct-proof.clj` and `tmp/orchestrator/schema-retirement-main-direct-result.edn`. This remains an armed in-process proof; no recorded-test green or platform-gate pass is claimed, and the earlier canonical synthetic-function helper boundary remains documented above.

The completion commit uses `--only` for `src/seon/db.clj`, the two test files and this note. The temporary `codex/schema-retirement-refusal` branch is deleted after that commit because its production content and remaining artifacts are now on `refactor/agent-platform`. No default mutation, provider request, foreign session operation or unrelated edit was performed. Owned shells are closed; final cleanup and commit identity are in the handoff summary.
