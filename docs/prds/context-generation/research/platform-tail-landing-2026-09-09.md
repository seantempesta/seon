---
type: research
status: complete
date: 2026-09-09
tags: [research, operator, render, test]
---

# Platform tail

Entering HEAD `053a71446`. Read AGENTS.md and its copied PRD §10 lane
rules first. Default was alive at PID 40078, HTTP 7994, PREPL 63396;
MCP health answered. Inherited untracked `build/`, `workers/`, and
`config/virtual-turns.edn` are preserved. No foreign session is operated.
All test commands use `SEON_TEST_WORKERS=3`; no full suite is requested.

## Slice 1: advertise every web binding

Read the ephemeral-port issue end to end. Adoption now retains the server;
its older rebind is gone. Boot already advertised the bound address, but
the file write was outside `seon.cluster/serve!`. The binding owner now
accepts the instance, writes its actual URL/port before returning the updated
instance, and closes the new listener if publication fails. Boot publishes
that returned instance. This moves the existing mechanism; it adds no
second advertisement writer or port derivation.

Dependency ledger: http-kit `reference-code/http-kit/src/org/httpkit/server.clj:21`
and `:27` exposes the actual bound port; `:34` returns stop completion.
First-party binding is `seon.render.web/start!`, and cluster advertisement
serialization remains `seon.cluster/write-advertisement!`. The regression
uses `seon.test-support/with-database`, real SCI acquisition, armed contracts,
and two real listeners. Keeping the first listener bound forces a different
fallback port, then the test reads the file immediately after the second bind.

Fresh scratch root: `tmp/platform-tail-root`, cluster `platform-tail`.
The first start correctly refused an unpublished root; after root-scoped
down and `init`, start used the committed
`turn_schema_no_provider_2026_09_09.edn` manifest. The committed
[probe](platform_tail_web_probe_2026_09_09.clj) installs the canonical Juniper
fixture: no-provider **true**, provider attempts **0**.

MCP JVM probe `(platform-tail-web-probe-2026-09-09/rebind!)` completed in
**5 ms** and read these exact advertisement bytes after the bind:

```edn
{:seon.boot/cluster-name "platform-tail", :seon.boot/prepl-host "127.0.0.1", :seon.boot/prepl-port 65370, :seon.boot/pid 49469, :seon.boot/start-instant #inst "2026-09-09T11:52:08.550-00:00", :seon.render.web/url "http://127.0.0.1:65427", :seon.render.web/port 65427}
```

The file includes a trailing newline. Before rebind, HTTP was **65374**;
afterward **65427**, with the same PID and PREPL. GET
`/ns/my.agents.juniper/debug?prompt=true` at the advertised URL returned
**200 / 63,977 bytes / 1.214981 s**. This proves served HTML, not browser paint.
The live probe exercised the new function loaded at scratch boot, not a
default lifecycle change. No new RESET NEEDED: no schema or captured service
input changed. Default was never stopped, restarted, or reforked.

Fast snapshot and required namespace gate: **1 test / 9 assertions / zero
failures/errors**. Namespace command:
`SEON_TEST_WORKERS=3 bin/test --paths src/seon/cluster.clj test/seon/cluster/web_binding_test.clj -- seon.cluster.web-binding-test`.
The matching `bin/test --platform --paths` command passed **83 tests / 490
assertions / zero failures/errors**. Coordinator/test phases were **22 s**
and **49 s**. Successful roots `run.nklXSh` and `run.V3GdQe` removed themselves.
Logs: `tmp/platform-tail-web-gate.log`, `tmp/platform-tail-web-platform.log`.
All completed gate/operator launchers exited. Scratch remains live only for
the next assigned history observation; final cleanup is recorded below.

Default in-place adoption converged to published commit
`6aa14915-4985-5eb7-a0ad-7a0ada2f075f`; the loaded binding's arguments are
`[instance dials]`. An initial comparison probe incorrectly passed a database
to `source/current` and received a contract refusal; the corrected probe
passes the instance's store. Neither probe changed default's lifecycle.
There was no foreign load/gate boundary and no worktree was needed.

Slice 1 owns only `src/seon/cluster.clj`,
`test/seon/cluster/web_binding_test.clj`, the web probe, this landing note,
and the ephemeral-port issue's move into `archive/`. The index is untouched.

Slice 1 commit: **`c6db5d67c`**.

## Slice 2: numeric history lookup at HEAD

Read the history issue end to end at its actual `archive/` path. It was
already resolved/archived. Archaeology: `985a830b5` guarded the numeric
lookup before detecting message identity; `baa1dde54` recorded its
regression. The old issue incorrectly called that namespace detection;
this note corrects it from the exact historical diff. `ff9507c1b` stores shown text;
`0b7c8043c` / `a90ed5cce` accept those settlements in ordered episodes;
`595b0bf7c` replaces the old `history-entries` path with saved evaluations
and removes that helper/test. The current owner is `walk/history`, querying
`seon.eval/of-agent` and calling the evaluation schema's render pair.

The new recurring canonical regression is
`seon.render.web-debug-test/saved-history-preserves-shown-text-with-numeric-lookups`.
It positively requires Long ids for both agent and anonymous component, one
rendered evaluation, exact shown bytes, equivalent numeric/ref lookup, and
unchanged history after changing the component. It exercises HEAD production
source; no renderer patch is needed. Exact expected text:

```text
my.agents.history-probe=> (my.plan/plan {})
#:seon.repl{:value The component's shown text., :result result/ehistory-probe-evaluation}
```

This intentionally non-EDN shown text cannot be decoded to reconstruct the
component. Fast namespace proof: **9 tests / 41 assertions / zero failures
or errors**. The gate also includes `seon.render.episode-test` to verify the
shown-text/ordered-episode boundary cited by the assignment.

Read-only live command:
`(load-file "/Users/sean/src/seon/docs/prds/context-generation/research/platform_tail_history_probe_2026_09_09.clj")`.
The fresh scratch JVM returned **222 ms**, agent **35252**, plan **35259**,
**10 entries / 5,022 UTF-8 bytes**, equal numeric/ref histories, no-provider
true, and **0 provider attempts**. This uses the canonical Juniper fixture
and the current saved-history owner. It does not claim the deleted helper
was executable or that the obsolete render-proc context channel survived.

Required gate:
`SEON_TEST_WORKERS=3 bin/test --paths test/seon/render/web_debug_test.clj -- seon.render.web-debug-test seon.render.episode-test`
passed **10 tests / 50 assertions / zero failures/errors**, **26 s** in the
coordinator/test phase. Matching explicit platform gate passed **83 / 490 /
zero failures/errors**, **45 s**. Logs: `tmp/platform-tail-history-gate.log`,
`tmp/platform-tail-history-platform.log`. Successful roots `run.CPuPJR`
and `run.hk0CyL` removed themselves. No foreign boundary or worktree.

Root-scoped `down` reaped scratch PID **49469** and reported the flock free.
Process-table inspection found no Java/bb holder before removing
`tmp/platform-tail-root`; recursive removal did not follow symlinks. Default
remains PID **40078**, HTTP **7994**, PREPL **63396**. No RESET NEEDED is
introduced by this test/documentation slice.

Slice 2 owns the web-debug regression, the history probe, the archived
history issue, and this note. The index remains untouched.

Slice 2 commit: **`dd51f4df0`**.

## Slice 3: exact ordered-declaration boundary

Read `turn-rename-landing-2026-09-09.md` and
`loop-proof-landing-2026-09-09.md` end to end, including the former's
slice 3 program-declaration boundary and slice 5.2 cold-write section.
Also read the plan README, working edge, and binding PRD §10 and §§13–15.
The later loop proof measures three arithmetic forms; it does not expand
that result into a cold declaration-installation proof.

**Remaining: consolidation of ordered declaration calls, and a measured
cold ordinary-proc turn that installs dependent declarations.** These are
different from grouping settlement into one actual database transaction,
which the source already does. No declaration planner or dependency API
change is landed in this bounded slice; this is the explicitly authorized
boundary-recording outcome.

The exact current path:

- `src/seon/turn.clj:1037`, `receipt-settle-batch-tx`: when any request
  carries `:seon.program/row`, retain one ordered `receipt-settle-call` per
  request. Otherwise use the one `receipt-settle-batch-call`.
- `:1022`, `receipt-settle-batch-call`: explicitly refuses program rows
  with `:seon.turn/ordered-declarations-required`; it cannot silently flatten
  them against the entering database.
- `:3361`, `settle-batch!`: sends the complete ordered vector and side
  effects to **one** `seon.db/transact!`. Multiple transaction-function calls
  are not multiple transactions.
- `:1234`, `pending-subject-resolution-tx`, called by `row-tx`: a function
  arriving after its test queries and resolves the earlier pending subject.
  Flattening both against the original database loses that earlier row.

Dependency ledger: Datahike's `db/transaction.cljc:1152` invokes each
`:db.fn/call` with the current mid-transaction database and splices its
returned operations before the next call. The dependency owns that ordering.
The prior `d/with` attempt is not a reusable writer simulation: the writer
uses transient indexes, and `PersistentSortedSet.java:951` refuses
`asTransient` when already editable (`Expected persistent set`). No new
simulation or duplicated declaration owner was introduced here.

Existing recurring surfaces: `seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped`
measures the first arithmetic turn, then three arithmetic forms on two
canonical forks; `batch-settlement-preserves-declaration-order` verifies
test-first/function-second subject resolution in an actual batch settlement.
The latter prepares its turn/evaluations with earlier writes. It is **not**
a cold ordinary-proc installation or a total three-write measurement.

To close the remaining proof, start an agent with no prior evaluations and
submit contracted dependent declarations through the ordinary no-provider
proc. Listen to every transaction until that turn closes, including
metadata-only writes; require three total reports, saved source before
execution, successful installations, resolved test subject, and executable
installed functions. Separately, reducing its ordered writer calls to one
requires the owner's pending planner/dependency decision; a three-report
result alone would not prove that reduction.

The committed read-only JVM
[declaration probe](platform_tail_declarations_probe_2026_09_09.clj) returns
exactly two `#'seon.turn/receipt-settle-call` operations for test then function,
and one `#'seon.turn/receipt-settle-batch-call` for the same evaluations
without program rows. Execution took **3 ms**, transactions submitted **0**.
This proves the loaded construction branch, not successful installation.
Pinned dependency commits: Datahike `cdcb5792db8bd599487f099437265d18a31164a5`,
persistent-sorted-set `e1a17bbe767c7801e67407c81f64efabfd2f1601`.

`bin/issues-index --check` exits **1**, as the owner's schedule still names
the now-archived ephemeral-port issue. It also reports the top-level
resolved no-credential issue and its stale schedule row, plus missing rows
for turn execution submission, additive system turns, browser observation,
and virtual-turn routing. These seven diagnostics are recorded in
`tmp/platform-tail-index-check.log`; no index row or unrelated issue was
edited. This is a schedule reconciliation boundary, not a failed code gate.

Fast HEAD snapshot: **23 tests / 363 assertions / zero failures/errors**.
Both canonical forks measured the cold arithmetic turn at **3 reports /
23 datoms (16 / 5 / 2)**, and the warmed three-form turn at **3 reports /
45 datoms (30 / 13 / 2)**. Both direct declaration-order regressions passed.
These numbers deliberately retain their exact scope; they are not the
unmeasured cold declaration-installation result.

Required final command:
`SEON_TEST_WORKERS=3 bin/test --paths docs/prds/context-generation/research/platform-tail-landing-2026-09-09.md docs/prds/context-generation/research/platform_tail_declarations_probe_2026_09_09.clj -- seon.turn-test`
passed **23 tests / 367 assertions / zero failures/errors**, **45 s** in the
coordinator/test phase. The matching explicit `--platform --paths` command
passed **83 / 490 / zero failures/errors**, **72 s**. Logs:
`tmp/platform-tail-declarations-gate.log` and
`tmp/platform-tail-declarations-platform.log`. Successful roots `run.d38lJz`
and `run.p8GN8l` removed themselves. The fast runner and both gate launchers
exited 0. All owned shells are ended; no scratch root/worktree remains.

Slice 3 owns only this note and the declaration-construction probe. It
introduces no production change or RESET NEEDED. All three bounded slices
have their selected green gate and a separate green platform run, capped
at three workers. Default lifecycle was never operated. The inherited
untracked paths are preserved, all local document links resolve, and Git
whitespace validation passes. The remaining declaration proof and schedule
reconciliation are explicit boundaries above.
