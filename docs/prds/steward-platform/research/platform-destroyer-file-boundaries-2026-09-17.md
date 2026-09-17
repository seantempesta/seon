---
type: research
status: active
tags: [testing, platform-tier, destructive, program-facts]
---

# Platform destroyer selection must retain real file boundaries

Batch 115 A refused before running tests. Evidence:
`tmp/orchestrator/gate-results/batch-115.log:1090`, retained snapshot
`tmp/test-runs/run.KjByqA`, published manifest
`target/test-published-bases/1120f631dc32d26814843110ce5704ce522b530e76bc76d14176ad5893f632d6/base/manifest.edn`.
The exception report's registry-test path was only
`[test :seon.test.runner/reached-through-declared-subject]`, not an actual
path to a destroyer.

Read end to end: the supplied AGENTS.md, the named
[destructiveness research](destructive-tests-derived-2026-09-17.md), the
[existing store-wipe issue](../../../seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md),
and the data-oriented Clojure and testing skills. Relevant seams read:
`seon.test/host`, `seon.fn/tests-reaching`, the runner's destroyer checker
and fixture-demand selector, and `seon.test.selection/reaching-tests`.
The dependency ledger is entirely first-party: analyzed manifest artifacts
and stored Datahike program rows supply the same call/reference facts;
`selection/reaching-tests` owns conservative unresolved-file selection.
No dependency behavior or filesystem deletion policy changes.

## Established cause

The fs admission has **no** `:seon.fn/destroys` metadata. Neither does
`create-store!`. The indexed owner remains
`seon.operator/cleanup-root-under-lock!` (plus the two published-root
fixture owners). No declaration was removed or weakened.

`seon.test.runner/tests-reaching-rows` flattened all non-seed rows into one
artificial `remaining-program` artifact. `selection/reaching-tests` correctly
widens an unresolved file reference to that file's tests. The helper made
that file mean the entire program.

In the retained manifest, the unresolved reference is on the file row for
`src/seon/test/runner.clj`: `seon.cluster/start!`. Its real graph path is:

```text
seon.cluster/start!
→ seon.cluster/stand-boot-layers!
→ seon.cluster/stand-cluster-runtime!
→ seon.cluster/seed-root-agent!
→ seon.schedule/root-maintenance-seed-call
→ seon.operator/reap-dead-roots!
→ seon.operator/cleanup-root-under-lock!
```

Git archaeology places the synthetic `remaining-program` artifact in
`e0dded0c6` (fixture-observation selection), and unresolved-file widening in
`3f0be21ed` (call-graph fidelity). Those two mechanisms interact at this
helper; moving delete admission in `c4d1be3ac` is not the established cause.

The registry test has no such path. A read-only MCP query on default pid
33583 independently returned `:seon.test.host/in-process` for
`seon.cluster.registry-test/a-concurrent-create-wave-loses-nothing`.
`seon.fn/tests-reaching` in reverse returned 108 tests for the operator
owner; its rendered list was explicitly elided, so the complete list is
not claimed as read evidence. The retained manifest's complete graph walk
confirmed the registry test is not reached.

The committed read-only reproduction
[platform-destroyer-file-boundaries-2026-09-17.clj](platform-destroyer-file-boundaries-2026-09-17.clj)
uses the production selector: the old grouping selects **99/99 declared
platform rows** in this retained manifest; retaining its real artifacts
selects **one**. Batch A's admitted platform tier had 97 Vars after its
other selection rules; declared rows and admitted Vars are distinct counts.

That one real reach is the Flow graph census:

```text
seon.flow-configuration-test/every-built-graph-proc-declares-a-specific-workload
→ seon.cluster.agent/graph-definition
→ seon.schedule/schedule-step
→ seon.schedule/fire-due!
→ seon.operator/reap-dead-roots!
→ seon.operator/cleanup-root-under-lock!
```

The census is now ordinary-tier, retaining its body and assertions. The
workload-construction refusal test keeps its own platform declaration.
The checker still follows references as well as calls; no exception is
made for a graph definition that merely carries a callable.

## Fix and recurring proof

The runner hands intact artifacts to the same selector and adds only an
explicit seed artifact. Its other caller, expensive-fixture demand,
preserves those boundaries through its existing row transformations too.
The destructive checker, declarations, and selector are unchanged.

`the-canonical-platform-tier-preserves-file-local-uncertainty` acquires the
canonical fixture base, verifies the registry host, resolves the platform
set from that fixture's manifest, and calls the existing checker in the
same process. It asserts the platform set is nonempty. Existing synthetic
checker tests still assert destructive refusal and missing-owner refusal.

Verification uses the requested scoped command:

```sh
bin/test-fast --paths src/seon/test/runner.clj test/seon/test/runner_test.clj test/seon/flow_configuration_test.clj test/seon/cluster/registry_test.clj -- seon.cluster.registry-test seon.test.runner-test
```

No full `bin/test`, cluster boot, reset, publication, or other lane operation
is authorized or performed. Concurrent edits are excluded by the path
snapshot. The original invocation waited 1200 seconds for a slot and was
terminated by the orchestrator session restart, before executing tests.
After restart, `src/seon/test/runner.clj` also held another lane's uncommitted
`admit-run` implementation and error require. Those foreign hunks were
preserved and excluded: `tmp/reset-tier-wt` starts at `b7f3083a4`, links
`reference-code`, and overlays only this lane's four selection hunks and owned
test-file changes. The same `bin/test-fast --paths` command runs there.
Raw iteration output: `tmp/reset-tier-proof/test-fast-isolated.log`.
No foreign session was operated or contacted.


The first isolated iteration passed the canonical checker (**98 declared
platform tests admitted**) and all runner assertions, but exposed eleven
registry fixture errors: its source-store transaction authored schema rows
without `:seon.schema.admission/source`. The fixture had hand-rostered two
production schema attributes. It now derives the complete production
attribute population through `schema-datahike/malli->datahike-schema-in`
and constructs schema entities through `seon.schema/canonical-schema-rows`.
The stored rows are the synthetic blob aliases and their dependency closure,
derived from those canonical rows' `:seon.schema/references`. Installing every
unrelated render declaration without its program definition was correctly
refused in the next iteration; that attempted schema-only population is not
the final fixture. Only genuinely synthetic registry
attributes remain in `probe-schema`. Checked fixture writes replace the
manual report assertion. This keeps the real file store and branch/GC
observations; it does not introduce a published-root cloning fixture or a
new destroyer edge. The initial tally was 31 tests, 149 assertions,
0 failures, 11 errors; final iteration is `tmp/reset-tier-proof/test-fast-complete.log`.

The hook also reported an in-flight unmatched delimiter in another lane's
`test/seon/render/web_debug_test.clj`; that file was untouched and its
working-tree bytes were excluded by the isolated snapshot.


## Final verification and boundary

The requested scoped fast run exited **0**: **31 tests, 210 assertions,
0 failures, 0 errors**. Its in-process existing-checker output was:

```text
Canonical platform destroyer check: 98 tests admitted
Ran 31 tests containing 210 assertions.
0 failures, 0 errors.
```

The registry namespace completed its twelve real file-store tests from
02:50:46Z to 02:51:15Z. The complete named test run finished at 02:52:43Z.
The test process used the worker's contract arming (1111 instrumented
functions reported at startup). Cold gate and platform execution remain
owed by the orchestrator; this lane ran only fast iteration and the
checker in-process, as assigned.

The final path-limited commit takes this lane's isolated runner bytes,
leaving the other lane's uncommitted admission code in the shared file.
No shared file was restored. The test JVMs exited, and the owned scratch
worktree and its test snapshots were removed after retaining logs under
`tmp/reset-tier-proof/`.
