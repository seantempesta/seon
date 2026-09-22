---
type: landing
status: implemented and verified on scratch root
created: 2026-09-22
---

# Hook: one live publication request

README §4 row 1.5's hook half and one-lifecycle §2 row 13's transport are
implemented. Publication remains **paused**. The save-time candidate/test gate,
re-enabling the hook, platform proof and repository-default adoption remain
orchestrator work. This slice does not claim those later mechanisms.

## Installed change

`bin/seon-hook` resolves the selected live advertisement and calls the installed
`seon.operator/prepl-value!` once with `refresh-source!`'s existing positional
request. It waits synchronously and prints accepted/refused/degraded terminal
EDN. Refusals retain the throwing owner's error data; unavailable or malformed
transport evidence is degraded. Progress output is forwarded, never parsed.
The optional total-wait argument on the existing client prevents output events
from renewing the hook's configured wait. Timeout means outcome unknown, not
proof of JVM termination; the existing publication monitor remains the serializer.
No event collector or alternative transport was introduced.

Deleted: source queue, worker admission/dispatch, batch ids, result files,
result-file readers, per-edit `bin/seon init --dev` child, and its post-adoption
`bin/test-check` child. The shared review worker remains: it is not a publication
worker. `bin/seon` already has only generic CLI dispatch and no hook-specific
branch; its supported manual `init --dev` command remains. The hook-only
`--result-file` option and writer were removed from `script/seon/operator.clj`.
`.codex/hooks.json` is unchanged. The entire pause/resume history and
`:current-source {:enabled false}` are unchanged; two configuration comment
lines now describe the installed transport accurately.

The shell-write scan returns its discovered paths beside its syntax refusal.
The main hook combines these with named paths and invokes publication once,
after syntax/schema checks. Thus a pathless shell write reaches the same request.
Its existing per-session digest observation remains, as B1 §2d requires.

The owner's scope extension is implemented at the producer:

- `load-development-definitions!` returns namespaces only after their individual
  `require :reload` calls complete, in reload order.
- The adoption function carries that vector and its already-computed arming
  identities through successful instrumentation and the adoption transaction.
- `:seon.source/refresh-result` combines `:seon.source/published` and the declared
  `:seon.source/adoption-result`. Its members are
  `:seon.source/reloaded-namespaces` and `:seon.source/arming-identities`.
- An unchanged adoption returns empty collections. Publication without a
  development cluster also returns empty collections. The existing published
  result contract remains available to its other producers.

Arming identities are precisely the producer's supplied selection, including
schema referrers. They are **not a measurement of how many wrappers the existing
broad instrumentation implementation actually replaced**. That consumer belongs
to A1. The core terminal reply is large because the producer supplies 6,969
identities; no clipping or invented subset hides this remaining cost.

## Dependency and execution boundary

Clojure gitlink `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`,
`reference-code/clojure/src/clj/clojure/core/server.clj:228`, evaluates one received
form and emits its terminal `:ret`; `io-prepl` at `:275` prints event values.
The first-party client is `script/seon/operator.clj:118`, with the runtime
adapter in `resources/seon/operator/prepl.clj:5`. Inputs are the advertisement,
printed form, output observer and declared wait bounds. Work is one connection,
one form and its events. Namespace selection and compilation remain the existing
producer's work, proportional to the selected stored dependency closure; no
hook-side dependency graph or compilation algorithm was added.

## Scratch proof and foreign boundary

Initial CLI and MCP status observed repository default PID 51528 with no missing
readiness layers. It was never published into, stopped or reset by this lane.
MCP tools were available. All publication proof used
`tmp/hook-one-request-root` and its own JVM.

The shared-tree reset refused on another lane's source/contract mismatch:
`seon.program/arity-row` reported six source bindings and seven declared slots
for `[candidate authored projection bootstrap caps [policy force?]]`. That JVM
was stopped using exact-root process discovery. No foreign source was changed.
The authorized snapshot was created at `1ada780500fba57f60066dfb153ae8147a78ce3d`,
with only this lane's changes and the vendored dependencies linked in.

From-zero reset on that snapshot returned all readiness layers present,
**164,223 ms**, source commit `6ab2d858-8c6d-5da5-8246-a4491bc9a403`. This exercised
the new schema members and result contracts. The launch session subsequently
ended and the child was observed absent; starting the retained scratch store
returned readiness in **14,424 ms**. The subsequent JVM was PID 72523, start
instant `2026-09-22T19:35:50.691Z`, kept under an owned foreground shell until
cleanup. The later schema edit only adds member descriptions; the field shapes
are those exercised by the from-zero reset.

A scratch MCP JVM read with explicit root/cluster returned the adopted commit,
`:refresh-result [:and :seon.source/published :seon.source/adoption-result]`,
and `:leaf-loaded true` in 8 ms. Exact clocks, forms, complete terminal values,
namespace lists, arming identities and adoption transaction observations are in
`tmp/hook-one-request-evidence/final-clocks.edn` (the earlier replay remains in
`clocks.edn`). The committed reproducible harness is
`test/seon/dev/hook_measure.clj`:

```sh
SEON_HOOK_STATE_DIR=/Users/sean/src/seon/tmp/hook-one-request-evidence/final-clock-state \
  bb --classpath test:script:src:resources -m seon.dev.hook-measure \
  /Users/sean/src/seon/tmp/hook-one-request-root
```

Run from the dedicated source snapshot. The harness refuses other checkout/root
names, restores its edited source in `finally`, counts actual installed-client
calls around real hook events, and independently reads the cluster's commit and
adoption transaction before/after the refusing request. No publication is mocked.

| Case | 1.2b final ms | Hook event ms | Requests | Reloaded namespaces | Arming selection | Terminal |
|---|---:|---:|---:|---|---:|---|
| No-change save | 300.562 | 216.897 | 1 | `[]` | 0 | accepted |
| Leaf docstring | 5527.306 | 6256.770 | 1 | `[my.note]` | 3 | accepted |
| Core docstring, `seon.id` | 21038.291 | 24798.721 | 1 | 374; full vector in raw result | 6969 | accepted |
| Shell write, discovered leaf | — | 5724.828 | 1 | `[my.note]` | 3 | accepted |
| Unresolved symbol in leaf | — | 1784.105 | 1 | no successful adoption | — | refused by `seon.cluster/refresh-source!`, static program analysis |

The refusal left **both the commit and adoption transaction unchanged**. Every
accepted result's commit matched the independent cluster read. No-change also
left that complete adoption observation unchanged. Heap-used observations after
the final five requests were 1,677,133,024; 2,535,464,408; 2,567,447,112;
2,535,364,336; and 2,490,310,528 bytes. These are point-in-time heap readings, not allocations
or retained-memory comparisons. PID 87624's post-replay RSS was 5,716,304 KiB.

The 1.2b rows time publication/adoption; these hook rows also include the hook's
existing lint and shell scan. The source snapshots and loaded namespace sets
also differ. This is a side-by-side record, **not a causal performance comparison**.
The latency targets remain unmet. Removing a Babashka envelope does not remove
analysis, reload closure compilation or broad arming.

An initial core clock and a later preparation request correctly refused because
this lane changed scratch test files after their published capture. Their
`Source changed during development adoption` diagnostics named the exact paths.
Those are retained in `first-clocks.err` and `prepare-final.err`; they are not
accepted timings. The complete owned inputs were then published together before
continuing. This exercised the existing digest safeguard without weakening it.

## Regression and verification boundary

The replacement socket regression drives the actual installed prepl client with
accepted, refused and malformed terminal values, verifies a single form per
connection, and proves paused publication sends nothing. A second regression
proves continuous output cannot extend the total hook wait. The live harness
covers the real publisher, shell-write dispatch, empty no-change result, and
refused adoption preservation. It replaces the old convergence test whose
private-helper call supplied a nil store and an incomplete invented instance;
armed contracts correctly rejected that fixture before its body could run.
Queue/result-file tests were removed with their mechanisms.

Focused checks use `bin/test-fast --paths` from the isolated snapshot; its
advertisement link routes recording to the scratch JVM, never repository default.
The existing immutable published base was six commits behind this snapshot.
The first recording attempt had no advertisement link and correctly refused
absent `current-src`; it is not a pass. A mistakenly main-checkout test launch
was interrupted before projection acquisition/admission, and its snapshot was
removed; it recorded no test result in default.

The first recorded run was `4eb4bc343205`: 16 executed, 24 assertions, 0 failures,
18 errors. Two classes were fixture defects: file objects passed to a declared
string directory argument, and the obsolete nil-store convergence fixture. The
invalid directory input exposed a secondary unnamed-callable error while
rendering its contract failure. These owned callers now pass strings. The timeout
regression's writer cleanup also needed to accept the expected broken pipe after
the client closes; its corrected recorded run passed that test. Final focused
results and cleanup are appended below. No suite, cold gate or platform tier ran.


## Completed focused checks and legacy clock

Final focused command (from the isolated checkout):

```sh
bin/test-fast --paths bin/seon-hook script/seon/operator.clj \
  src/seon/cluster.clj resources/seon/schemas/seon.source.edn \
  test/seon/dev/hook_test.clj test/seon/dev/edit_feedback_test.clj \
  test/seon/dev/hook_measure.clj test/seon/cluster/publication_convergence_test.clj \
  -- seon.dev.hook-test seon.dev.edit-feedback-test
```

Run **`061fde25ed20`** recorded **15 executed, 0 reused, 111 assertions,
0 failures, 0 errors**; 1,699 contracts were armed. Program digest:
`5198848b1ca78b92e37961d80e749d712b494f1ec9a86141811d756045edef00`.
Log: `tmp/hook-one-request-evidence/focused-green.log`.
The immediately preceding run had 112 assertions including its duration failure:
the existing schema-admission test took 43,249.731 ms but had only a textual long
reason, so its enforced bound remained 5,000 ms. Its explicit 60,000 ms bound now
records that measurement and the previous 59.518-second observation. The fresh
rerun above, not the earlier timed-out run, supplies green evidence.

The required committed legacy clock was also run unchanged:

```sh
PUBLICATION_CLOCK_RESUME=1 \
  docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh \
  tmp/hook-one-request-wt tmp/hook-one-request-root
```

Its existing `head` cluster was created and started on the same scratch JVM,
after the from-zero default boot. No additional JVM was launched for it. Its
clock scope remains CLI publication/adoption, separate from the hook clocks.

| Legacy script row | ms | Namespace reloads | Observed wrapper replacements |
|---|---:|---:|---:|
| adopt-first | 454.579 | 0 | 0 |
| adopt-nochange | 416.414 | 0 | 0 |
| adopt-noncore | 5983.238 | 1 | 3 |
| adopt-core | 28018.659 | 374 | 1681 |

The script returned exit 0 and positively observed PID 72523 exiting. Its
separate optional sweep reported unavailable: `retention-cutoff` refused missing
`:seon.config.db/snapshot-window-ms` on a cluster. No retention setting or GC
owner was changed. Logs and its reload/storage/compile artifacts were copied to
`tmp/hook-one-request-evidence/legacy-clock/` before resetting the scratch root.

Final review preserved the original `development-arming-identities` input
(`namespaces`); only its computed result is carried outward. Successfully
reloaded namespaces remain a separate exact observation. The focused transport
run above precedes this one-argument correction; a final from-zero boot and live
replay below verify the final producer without claiming another full focused run.

Production delta: hook −129 lines, client +18, cluster +7, schemas +7 = **−97**.
Tests, including the 112-line scratch clock/regression harness, net **−76**.
Configuration comments have zero net lines. The result is **−173 code/test
lines**, not the plan's approximate −350 estimate. Documentation and retained
measurement output are counted separately.


## Final producer replay

The final source and schema descriptions booted from zero in **126,062 ms**,
with no missing readiness layers: PID 87624, start instant
`2026-09-22T19:55:55.432Z`, initial commit
`6ab2ddc7-5b00-59d3-90f8-e6ffb5c2646c`. The five-case live harness exited 0.
The table above reports this final replay, including the preserved original
arming selection. Accepted commit ids were:

| Case | Adopted commit |
|---|---|
| No-change | `6ab2ddc7-5b00-59d3-90f8-e6ffb5c2646c` |
| Leaf | `6ab2de89-5bd1-530a-82cb-a46b1ec82785` |
| Core | `6ab2de98-b970-5fd6-81ec-c4445c69f62a` |
| Shell write | `6ab2deaa-9c76-51c2-9cc3-263e0fdc27ef` |

The refused request retained the shell-write commit and the identical adoption
transaction. Raw evidence includes complete namespace vectors, arming selections,
request forms, terminal results and before/after reads. The scratch runtime log
also contains `SEON CORE FAULT (dev panic): Program acquisition refused.`; this
slice does not claim agent-turn, SCI acquisition or browser proof. Those owners
were not changed. Final logs are retained in
`tmp/hook-one-request-evidence/{reset-final.log,final-clocks.edn,final-runtime.log}`.


## Final load boundary and cleanup

The shared working tree's final direct `require` was attempted after stopping the
scratch JVM. It refused at `seon.schema.edn/validate-resource-placement!`:
`:datahike.budget/observed` is declared in `seon.db.edn` but belongs in
`datahike.budget.edn`. Those concurrent schema-placement edits are outside this
lane. Log: `tmp/hook-one-request-evidence/final-shared-load.log`. No foreign file
was changed. The final from-zero boot and live replay above prove the loadable
slice at the recorded snapshot plus these owned paths; shared-tree load and the
orchestrator's cold/platform gate are **not claimed green**.

`bin/seon --root /Users/sean/src/seon/tmp/hook-one-request-root down` returned
`:seon.operator/process-exit? true` for PID 87624 and its recorded start instant.
The keeper shell and all owned probe/test shells ended. `ps` found no such PID;
`lsof +D` found no holder of either disposable root. The scratch store and source
snapshot were removed without following their dependency/cache symlinks.
Evidence is retained in `tmp/hook-one-request-evidence/`; repository default was
not mutated. Publication remains disabled and `.codex/hooks.json` is unchanged.
