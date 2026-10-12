# File backed fixture experiment

Konserve range reads are implemented and pushed on `seon/tiered-range-reads`, commit `26444d372bf97de219d55c06d5d23bd6f5cfc634`. The fork batch stops at the Datahike file-frontend slice: its three overlay errors still reproduce, and a smaller probe exposes the earlier empty-file read hidden by cleanup. No Datahike implementation, Seon fixture conversion, dependency bump or default restart was accepted.

## Fork batch result, 2026-10-12

`konserve.tiered/TieredStore` now implements `konserve.protocols/PBinaryRangeStore` by selecting the frontend or backend exactly as its binary read method does and delegating the bounded read. The authoritative checkout is `reference-code/konserve`, whose origin is `https://github.com/seantempesta/konserve.git`; its parent was the pinned `e4334756413917305968333bf305e658c177765d`. The public range API is synchronous. Inherited reads do not populate the frontend or materialize the entire binary value.

- Parent regression: two new tests, 23 assertions, 22 missing-protocol errors. [Output](fixture-hour/fork-batch/fork-batch-konserve-red.txt).
- Changed regression: two tests, 26 assertions, zero failures/errors. It covers inherited and local binary keys, frontend precedence, both read policies, missing keys, exact byte ranges, partial final ranges, offsets beyond the end and zero-length ranges. [Output](fixture-hour/fork-batch/fork-batch-konserve-green.txt).
- A 4 KiB read from a 32 MiB file allocated 30,712 bytes in 0.562 ms for an inherited key and 30,728 bytes in 0.465 ms for a local key. Both are below the existing file-range regression's 1 MiB allocation ceiling. This is new protocol functionality: the same parent operation throws, so there is no parent latency improvement ratio.
- Konserve's own `clojure -M:test`: 92 tests, 1,397 assertions, zero failures/errors. [Full output](fixture-hour/fork-batch/fork-batch-konserve-full.txt). The commit adds 13 net source lines and 73 test lines. It is pushed to our fork; no pull request was opened.

The retained Datahike file-frontend patch was applied to `c9e57c6fb65365d5ce92b59854fb02ea59054fe7` on `seon/overlay-file-frontend`. `bb kaocha --focus datahike.test.overlay-test` still returns **3 tests, 36 assertions, 3 errors, 0 failures**, both with Datahike's original Konserve pin and after selecting the new range-enabled commit. [Original-pin output](fixture-hour/fork-batch/fork-batch-datahike-red.txt), [new-pin output](fixture-hour/fork-batch/fork-batch-datahike-current.txt), [exact candidate patch](fixture-hour/fork-batch/datahike-candidate.patch).

The [smaller probe](fixture-hour/fork-batch/overlay-cause.clj) places connection release directly around the operation that opens it, exposing the earlier error without changing production cleanup. Run it from the Datahike checkout with `clojure -M:test <absolute-path-to-probe>` after applying the candidate patch. It connects the file overlay successfully, then `datahike.versioning/branch!` fails in `datahike.writing/read-head` with **“File is empty or deleted during read”, `{:length -1 :expected [20 8]}`**. [Original-pin cause](fixture-hour/fork-batch/fork-batch-datahike-cause.txt), [new-pin cause](fixture-hour/fork-batch/fork-batch-datahike-cause-current.txt).

The producer path is Konserve's EDN read-through warming. `konserve.tiered/TieredStore` schedules `warm!` on a background `go` and returns the inherited value before that write completes. `konserve.impl.defaults/io-operation` opens the final file through `konserve.filestore/BackingFilestore` with `CREATE`; `konserve.impl.defaults/update-blob` later writes its replacement and moves it into place. A subsequent frontend read can therefore encounter an empty destination during the unfinished warm. This source path explains the empty-file observation; the probe does not yet control the interleaving or prove a repaired implementation. The existing Konserve test explicitly describes nonblocking warming, so changing it requires a further producer change with its own regression. No cleanup exception filter, retry, extra lock, fallback read or Seon workaround was added.

The full overlay test acquires its first overlay connection before entering the bracket that releases it; `branch!` throws before that bracket. The still-held connection then causes base deletion to report `:database-has-active-connections`, masking the empty-file exception. Correcting that test bracket would improve evidence but would not repair the file frontend. The existing task `seon.test-support/file-backed-branch-without-republication` remains blocked on this dependency path; its proof remains red.

The range commit changes no EDN connection behavior. `datahike.store/ready-store` still invokes `konserve.tiered/sync-on-connect` for `:tiered`. A separate timing of that synchronization was not completed, and no claim is made that `:tiered` is adequate or that the file overlay is accepted. The scoped threshold patch, Seon paired fixture measurement, shell/two-cluster regressions and heavy selection were not rerun in this batch because slice 2 failed. No honest new fixture bound has been established.

At entry, `bin/seon status` and MCP `runtime_status` both reported default available but degraded (six recorded errors, one unowned namespace, untested-hot warnings). A read-only MCP JVM probe on root/default confirmed the loaded TieredStore still lacks the range protocol; the form took 1 ms, the request 40.3 ms, with 0.2 MB request allocation and 6.2 MB entrance allocation. It loaded no changed source. Full Seon `clj-kondo --lint src script test --cache false --parallel` completed in 7,391 ms with **0 errors**, 1,621 warnings. [Lint output](fixture-hour/fork-batch/fork-batch-lint.txt).

The failed Datahike candidate was preserved and reversed. Both dependency checkouts again match Seon's gitlinks; the Konserve branch and pushed commit remain available. No Seon source, dependency coordinate or shared index change belongs to this batch. Other lanes' changing cluster, schema, test-runner, task and documentation files were not touched or loaded; they did not cause this stop. Default was never stopped, reset or restarted, and no worktree was created. Root should not restart for this report: the combined dependency/fixture commit has not been made.

## Previous fixture experiment

## Measured checks

All checks used `bin/seon check` and its disposable JVM, not a reload or restart of default. [Complete check diagnostics](fixture-hour/checks.edn) retain candidate identities, results and operator phase times.

| Candidate | Probe | Fixture ms | Body-thread allocation | Result |
|---|---|---:|---:|---|
| Parent `f70e48517339b77180d9dd95e930cf9613b709dc` | `seon.test-support-test/a-file-backed-fixture-costs-a-branch` | 42,662.58 | 33,176,695,288 bytes | 1 pass, 3 failures including the unchanged body bound |
| `44921195253ac8759b721d5075d3e0bccecef792` | Same probe, private `:tiered` file frontend | 2,646.97 | Below the unchanged 128 MB assertion; exact amount not printed | 2 passes, time assertion red |
| `8448edc60b50fe363fb66cdc6755dcc1976fad59` | Same probe, reuse the executing handle's projection | 4,843.77 | Below the unchanged 128 MB assertion; exact amount not printed | 2 passes, time assertion red |

These are separate throwaway processes while other lanes were running, not quiet paired samples. The fixture did improve over the parent, but neither candidate meets its proof and the variation does not establish that carrying the projection regressed execution.

The last candidate also ran `seon.shell.jvm-test/binary-output-is-byte-exact-on-both-sides-of-the-inline-ceiling`. It failed before the shell: the fixture had not bound its new branch with `seon.db/with-branch`. That binding is present in the retained experiment patch but has not been checked again. No test assertion or bound changed.

## Library composition and next slice

Pinned source read: Datahike `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`, Konserve `e4334756413917305968333bf305e658c177765d`, Malli `dd13ff81139159778edb0a67b491eccc0baa991d`.

- `datahike.versioning/branch!` reads one selected commit and shares its stored roots. This is the fixture operation required; `datahike.versioning/fork-database` copies all store keys and is not its replacement.
- `datahike.store/ready-store` for `:tiered` calls `konserve.tiered/sync-on-connect`, which calls `konserve.tiered/perform-sync` and enumerates and copies missing backend keys. It runs before the requested branch is opened. This is a source-confirmed whole-backend operation, **not yet a separately timed attribution of the remaining milliseconds**.
- `datahike.store`'s existing `:datahike/overlay` frontend avoids that synchronization and keeps branch identity separate. Its `overlay-frontend` currently constructs only a memory store. The proposed optional `:frontend-config` uses Konserve's own store construction and deletion. Existing callers retain the memory default; file callers supply a private path.
- `seon.blob/stage-file!` assumes a direct file store. The experiment resolves the receiving file frontend in `seon.blob/file-store-directory`, whose output contract is `:seon.store/dir`, and stages beside that directory. Malli validates the directory at the producer. The initial attempt to contract the pre-existing throw was rejected by the edit hook; the retained experiment removes that guard and declares the directory contract.

[Datahike patch](fixture-hour/datahike-file-frontend.patch) applies cleanly to the pinned fork. It extends the existing isolation test over both memory and file frontends. It was applied on `seon/overlay-file-frontend` and run through `bb kaocha --focus datahike.test.overlay-test`: 3 tests, 36 assertions, 3 errors, 0 assertion failures. The memory iteration completes its assertions in each suite; the file iteration ends with `:database-has-active-connections` during base-store cleanup, naming one overlay `:db` connection. Cleanup obscures the earlier failure, so the primary file-frontend cause is not established. [Complete Kaocha output](fixture-hour/datahike-overlay.log). The patch was reversed after the owner changed the sequence; the branch still points at the original gitlink, with no fork commit or push. [Seon experiment](fixture-hour/seon-tiered-experiment.patch) retains the measured approach and its later branch-binding correction; it still uses `:tiered` and needs conversion after the fork change. These are proposals, not accepted implementation.

The next implementation uses a new `:datahike/overlay` with an optional file frontend, opens its connection, calls `seon.cluster.registry/branch!` from the execution handle's captured commit, and opens that branch through the same configuration. It passes the handle's captured projection and binds the branch around the body. Releasing the overlay deletes its private frontend through Konserve and leaves its backend held by the executing test. No second store registry, cache, evaluator or synchronization path is needed.

The standalone publication fixtures (`seon.test-support/populate-published-root!` and `seon.test-support/populate-published-operator-root!`) still need independent durable stores for boot, GC and subprocess subjects. They remain unchanged. A complete solution for those callers is not proved by the file frontend patch; it must retain their private physical-store semantics. Do not claim all of the historical fixture hour is removed.

## Previous acceptance result

The owner withdrew the invented one-second target, authorized a 10,000 ms regression limit based on twice the prior 4,843.77 ms maximum, and then assigned the real multi-cluster blob-threshold bug to this slice. The 128,000,000-byte assertion is unchanged. The owner requires a Seon-only commit, with no dependency bump.

The producer change uses `seon.config/effective` and `seon.env/of` on the connection's carried database metadata. Both `seon.blob/binary-threshold` and `seon.blob/inline-or-stage` use that selected configuration. A new arity of `seon.blob/stage-binary!` accepts the named threshold schema explicitly; shell, web and effect callers pass the configuration value they already acquired. Removing `seon.config`'s unused `seon.error` require avoids the resulting namespace cycle. The low-level binary test fixture was converted from hand-written configuration attributes to canonical cluster/configuration setup. These changes are retained in [the candidate patch](fixture-hour/scoped-threshold-candidate.patch).

Candidate `85c56d35ffaa67b50593bc6ad3e962639c988da9` ran through `bin/seon check` on HEAD plus the lane's files. Run `69a777e48ffa`: **12 passes, 4 failures, 1 error; 84,121 ms for the three-test request**. The test JVM ran from that candidate archive; foreign working-tree edits were not included. [Per-test results and costs](fixture-hour/scoped-results.edn), [operator diagnostics](fixture-hour/scoped-check.edn), and [failure output](fixture-hour/scoped-check.log).

| Test | Body ms | Body-thread bytes | Outcome |
|---|---:|---:|---|
| `seon.blob-threshold-test/blob-threshold-belongs-to-the-connections-cluster` | 21,832.99 | 1,310,523,936 | Six behavior assertions pass; declared body duration fails |
| `seon.shell.jvm-test/binary-output-is-byte-exact-on-both-sides-of-the-inline-ceiling` | 30,694.51 | 568,674,240 | Five assertions pass; binary range read errors |
| `seon.test-support-test/a-file-backed-fixture-costs-a-branch` | 29,432.16 | 407,252,112 | Inherited declaration passes; time/allocation assertions and declared duration fail |

The fixture's inner `seon.profile/measure` reports **29,207.42 ms / 391,195,856 bytes**. That does not reproduce the earlier 2,646.97–4,843.77 ms / under-128-MB result. The run waited for the shared check slots and used a later HEAD/template; these are not quiet paired samples, so no regression or gain is attributed to the threshold fix. `datahike.store/ready-store` still invokes whole-backend synchronization for `:tiered`; its contribution to this larger sample has not been separately timed. No bound was raised to make this run pass.

The threshold fix removes the earlier shell failure: output above the selected threshold now becomes a blob. Reading that blob then throws: “No implementation of method: :-bget-range of protocol: #'konserve.protocols/PBinaryRangeStore found for class: konserve.tiered.TieredStore”. The pinned Konserve source implements `PBinaryKeyValueStore` on `TieredStore`, but not `PBinaryRangeStore`. `konserve.core/bget-range` delegates to that optional protocol. This is a concrete dependency gap, not foreign working-tree breakage.

Replacing `seon.blob/read-chunk` with `konserve.core/bget` would discard its range-allocation guarantee: both file-channel implementations of `konserve.protocols/-read-binary` allocate a buffer for the entire stored payload before invoking the callback. This is source evidence, not a new measured benchmark. `seon.blob-test/large-binary-write-and-small-chunk-have-bounded-allocation` already requires a 4 KiB range to allocate under 1 MiB. No all-blob read, frontend-only shortcut or Seon implementation of the missing library protocol was added.

The next library work is `PBinaryRangeStore` on `konserve.tiered/TieredStore`, with the same frontend/backend read policy and tests of inherited and local binary keys, exact ranges and bounded allocation. It belongs in Konserve. The separate Datahike file-frontend patch remains a follow-up and still has the three overlay-check errors documented above. Neither fork has been changed or pushed in this attempt.

The fault-storage/publication-adoption sample and remaining reaching tests were not run after these failures. No green combined slice, new paired performance proof or measured new round total is claimed. The latest patch also includes a program/write-isolation test added after the candidate snapshot; that test is not claimed as run. The configuration producer change has positive behavior evidence, but bare connections without a carried cluster still need their callers checked before it can be accepted. Exact source diffs are preserved for root; the lane's uncommitted source changes are removed after recording them so other lanes do not inherit an unaccepted implementation.

## Verification and coordination

The scoped-threshold candidate plus its later test edit passed full `clj-kondo --lint src script test --cache false --parallel`: **6,427 ms, 0 errors, 1,620 warnings**. [Lint output](fixture-hour/scoped-lint.log). No claim is made that lint proves the unexecuted tests.

Full `clj-kondo --lint src script test --cache false --parallel` on the shared working tree: 6,928 ms, two delimiter errors in `test/seon/branch_schema_test.clj` (the restart-safe lane's in-flight file), 1,622 warnings. The same complete lint on a `git archive` of committed HEAD `d50a30d219f373092aab5bfc215cfac0d03d14aa`: **6,920 ms, zero errors**, 1,615 warnings. This is HEAD verification, not a claim that either performance candidate passed.

Default initially reported degraded health: existing admission errors associated with round-waste, a recorded Keyword-to-Map.Entry error and untested-hot warnings. The edit hook's test-support REPL entrance repeatedly timed out; those outcomes are unknown, not passes. The candidate checks above provide the separate evidence. No default stop, reset or restart was performed. No worktree was made. Neither source nor dependency edits remain in the shared tree from this experiment; only this report and retained evidence/patches remain. Test bounds and assertions are unchanged.

The agent-hangs handoff arrived and its paired runner/test-support patch was included in the corrected candidate. Its own files and session were not edited or contacted. It now holds `resources/seon/tasks/seon.test-support.edn`; claiming that file was refused, so its task intent has not been edited. The task remains open, including the remaining tiered-sync cost; the configuration ambiguity now has six passing behavior assertions, while the binary range dependency gap remains. The shared tree now contains other active lanes' edits; they are outside this slice. No implementation or documentation commit has been made. The fork batch was authorized and then deferred by the owner's corrected sequence. Default now reports `:faulted`, one open fault and two agent-error signatures; this lane has not stopped, reset or restarted it. The failed candidate uses HEAD plus this lane's paths, excluding foreign edits to cluster, schema, merge and test owners.

Own-branch cleanup called `my.agent/retire-branch!` for `:agent-c96f6a4a0f5c`, returning `:my.agent/retiring? true` in 46 ms / 3.1 MB. Its reply also delivered the previously recorded `seon.cluster.registry/retire-branch!` “Connection has been released” error (signature `759d40be224b919f6fa4f856cc6fde854292594f7bdccafc95ca63a0b1bf1134`, occurrence timestamp 12:17:21Z). The cleanup owner's existing task is `seon.turn/a-closed-attempt-releases-its-connection-before-branch-deletion`; a requested retirement is not by itself proof of deletion. A subsequent read of `datahike.api/branches` still contained the branch; its unresolved lifecycle is retained for root under that existing task. File claims were released and the shared index was empty. A final shell poll was rejected by the hook because the agent-hangs lane’s `test/seon/cluster/agent_test.clj` was temporarily missing a closing delimiter; that foreign file was not repaired or loaded by this lane.

## Historical round cost

The original 2,750-result prefix has exactly 28 tests over 20 GB: **1,213,737.66 ms (20.23 minutes), 891.66 GB allocated**. [Per-test evidence](fixture-hour/historical-heavy.edn) is copied from that exact prefix. The live scratch file now contains more results, so selecting its whole contents would silently change the group to 39 tests. The older round spans revisions and is not a paired current-parent benchmark.

The 28-test candidate run was not performed: the binary shell range read errors and the latest fixture cost proof is red. Consequently there is no measured new round total, no before/after total to accept, and no claim that the round now costs fewer minutes. The table below preserves each historical member; “pending” means not measured on an accepted candidate.

| Test | Historical seconds | Historical GB | Historical failures/errors | Candidate seconds |
|---|---:|---:|---:|---:|
| `seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold` | 39.557 | 30.083 | 1/1 | pending |
| `seon.cluster.acquisition-test/acquisition-owns-only-its-isolated-resources` | 37.492 | 29.685 | 1/1 | pending |
| `seon.cluster.boot-test/stop-instance` | 44.033 | 35.928 | 0/0 | pending |
| `seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation` | 48.955 | 34.868 | 0/0 | pending |
| `seon.cluster.entrance-supplier-test/supplied-context-acquires-and-releases-an-isolated-evaluation` | 40.380 | 31.204 | 1/1 | pending |
| `seon.cluster.fault-storage-test/lossy-subthreshold-fault-evidence-says-it-is-capped` | 45.313 | 31.446 | 1/1 | pending |
| `seon.cluster.fault-storage-test/oversized-fault-evidence-is-bounded-and-retrievable` | 59.269 | 39.365 | 3/0 | pending |
| `seon.cluster.program-restart-test/an-agent-definition-survives-restart-and-another-agent-calls-it` | 44.019 | 36.680 | 5/7 | pending |
| `seon.cluster.publication-adoption-test/freshly-forked-host-already-has-its-published-source` | 41.195 | 33.438 | 0/0 | pending |
| `seon.cluster.publication-concurrency-test/concurrent-publications-return-one-committed-head` | 41.182 | 29.908 | 2/0 | pending |
| `seon.cluster.publication-declared-schema-test/publication-admits-optional-in-memory-offending-members` | 44.282 | 30.655 | 0/0 | pending |
| `seon.cluster.publication-host-test/live-init-keeps-the-host-process-and-launches-no-jvm` | 49.278 | 34.790 | 8/1 | pending |
| `seon.cluster.publication-inputs-test/an-unchanged-explicit-path-is-captured-once-and-keeps-the-head` | 44.781 | 29.859 | 1/0 | pending |
| `seon.concurrency-independence-test/n-agents-fold-independently-on-one-live-cluster` | 44.944 | 34.862 | 2/1 | pending |
| `seon.config-application-test/applied-values-shape-the-running-system` | 44.832 | 33.670 | 2/0 | pending |
| `seon.dev.fresh-operator-export-test/export-verb-produces-an-openable-queryable-store` | 94.451 | 31.804 | 5/1 | pending |
| `seon.fn-test/fresh-population-flattens-with-its-supplied-declarations` | 35.075 | 30.139 | 0/0 | pending |
| `seon.incremental-publication-test/non-program-inputs-have-their-own-publication-owner` | 33.824 | 21.500 | 12/0 | pending |
| `seon.sci.eval-instrumentation-test/an-instrumented-dev-cluster-builds-an-attempt-ready-prompt` | 44.178 | 37.890 | 0/0 | pending |
| `seon.shell.jvm-test/a-normal-exit-reaps-the-descendant-it-left-behind` | 43.956 | 31.785 | 0/0 | pending |
| `seon.shell.jvm-test/an-evaluations-deadline-reaps-the-child-it-admitted` | 43.428 | 30.382 | 0/0 | pending |
| `seon.shell.jvm-test/argv-stdin-and-nonzero-exit-remain-process-evidence` | 40.592 | 30.382 | 0/0 | pending |
| `seon.shell.jvm-test/binary-output-is-byte-exact-on-both-sides-of-the-inline-ceiling` | 39.480 | 31.778 | 0/0 | pending |
| `seon.shell.jvm-test/child-environment-is-complete-and-declared-overrides-win` | 38.126 | 30.383 | 0/0 | pending |
| `seon.shell.jvm-test/cwd-outside-roots-refuses-before-process-start` | 38.168 | 30.378 | 0/0 | pending |
| `seon.shell.jvm-test/stdout-and-stderr-drain-concurrently-without-loss` | 40.418 | 32.610 | 0/0 | pending |
| `seon.shell.jvm-test/time-limit-reaps-the-process-tree-and-marks-the-effect-interrupted` | 38.242 | 30.386 | 0/1 | pending |
| `seon.test.accretion-test/source-harvest-keeps-refusal-render-contracts-coherent` | 14.286 | 25.803 | 1/0 | pending |
