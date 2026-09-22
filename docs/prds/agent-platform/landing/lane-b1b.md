---
type: report
status: implementation complete; integration pending
created: 2026-09-21
tags: [agent-platform, boot, operator]
---

# B1b implementation and verification

Eight named boot/reset drills have positive canonical `seon.test/run` iteration
results. **Default replacement, the cold integration gate, and the installed MCP
connection reload remain pending and orchestrator-owned.** Default was never
stopped or replaced by this lane. The platform-guard regression is red on ten
source-publication tests; its exact refusal is recorded below, not attributed to
baseline without a comparison. An additional export test request was explicitly
fixture-excluded, and a `bin/test-check` smoke reached the installed request owner
but exceeded its explicitly supplied 5000 ms bound. Neither is claimed green.

Read end to end: the [implementation brief](../../../research/agent-platform/b1b-implementation-brief-2026-09-21.md),
[integrated B1b specification](../plan/lane-b1b-operator-and-boot-rewrite.md), and
[Fable review of 076827cc9](../../../research/agent-platform/b1b-fable-review-076827cc9-2026-09-21.md).
The assignment's temporary between-commit load/tool breakage exception was used;
this report separates early listener evidence, complete boot, and recorded drills.

## Landed shape

Implementation checkpoints: `26143d5f2` (store admission), `076827cc9`
(operator/boot replacement), and `f49187619` (contracts, bounds, drill harness and
review fixes), followed by this final verification slice.

The BB client discovers exact `(pid, start-instant)` identities, delegates connected
commands to the hosting JVM, and retains the captured set for down/reset. Positive
PID reuse makes an advertisement stale; unavailable OS identity refuses. A delayed
down never rediscovers and signals a replacement. Cold launch opens the REPL before
loading the program or acquiring its store. A store-lock loser exits and the client
awaits its actual `onExit`; a later boot failure leaves the listener and acquired
layers usable. Readiness includes the actual served URL and wanted port.

Store destruction occurs after acquiring the existing sibling FileLock and before
probing/deleting store content. Boot retains a root-store reference across source
publication and uses that same holder through ready. Failed release keeps the lock
and listener. Forced single-instance stop checks every sibling key, including
reservations, under the instance monitor and uses `Runtime.halt` only for a proven
singleton; this avoids waiting on a shutdown hook that needs that monitor.
Advertisements use atomic EDN replacement.

Filesystem measurement moved to `seon.fs`; collection and log rotation moved to
`seon.maintenance`; bounded tooling subprocess execution moved to
`seon.cluster.process`. Existing publication, registry, schema/projection,
coherence, search, SCI and test selection owners remain installed. This does not
implement A1/B2/B3/B4. `bin/test-check` still calls `seon.test/check-request`.

The changed-test tooling lock now refuses busy requests immediately, replacing
its lifecycle queue/holder files. A same-process reservation precedes opening the
kernel lock, so closing a second descriptor cannot release a held fcntl lock.
Closing the acquired FileChannel releases the lock. The existing subprocess and
reaping bounds remain. This exclusion protects one tooling report, not boot/reset.

## Canonical iteration evidence

[Raw outcomes and exact program/input digests](b1b-results-2026-09-21.txt) are
retained with the [reproducible runner](b1b-drill.clj). The runner connects to an
explicit existing scratch host, arms through `seon.test.arm/arm-contracts!`, carries
the real database projection and calls `seon.test/run`. It does not launch or
replace its host. Physical fixtures use the canonical published-store helper with
an explicit observation explaining why a database branch cannot prove OS lock
exclusion; no hand-rostered schemas or mock stores are used. Source tests' canonical
manifest base is produced by `seon.cluster/publication-base!`.

| Drill | Run | Pass/fail/error | Measured ms | Basis / completion |
|---|---|---|---:|---|
| 1 cold-start | `59ec4d92c3c4` | 13/0/0 | 144131 | 536871009 / 536871011 |
| 2 concurrent-start | `f0f948403d29` | 12/0/0 | 51596 | 536871003 / 536871005 |
| 3 stop-instance | `26284d8b6c6c` | 6/0/0 | approximately 80000 | 536870988 / 536870990 |
| 4 down-unresponsive | `95dcf27dd30b` | 13/0/0 | 55858 | 536871052 / 536871054 |
| 5 reset-one-jvm | `b54213d0cab0` | 21/0/0 | 158108 | 536871062 / 536871064 |
| 6 start-during-reset | `0b9e7d0a1484` | 11/0/0 | 166166 | 536871050 / 536871052 |
| 7 reset-loses-replacement-race | `09dc5e02d391` | 12/0/0 | 58111 | 536871021 / 536871023 |
| 8 same-lock-through-reset-boot | `ab51b53f1450` | 18/0/0 | 153453 | 536871046 / 536871048 |

These are separate iterations as source evolved, not a single final-snapshot cold
gate. Drill 2's helper later gained an actual-loser-exit assertion, exercised in
7 and 8. Drill 5's later invalid-root/config checks initially called the internal
parser with public CLI syntax; final inspection caught that false-positive risk.
They now execute the real CLI and assert the specific refusal plus unchanged old
identity/data. Its final retry `b54213d0cab0` passes all 21 assertions, as listed above. The older
`47ba19ff0306` run (15/0/0, 136271 ms) is retained in raw evidence.

Drill 1 proves early REPL, complete zero-store publication, HTTP 200, one database
fact, JVM/SCI evaluation, and usable REPL after injected web failure. At 55 s its
thread sample identified `seon.fn/index!`'s canonical transaction and writer-side
owning-ancestor validation in `seon.db/write-owned-values-error`.

Drill 2 uses the SAME cluster name for winner and contenders, pauses before the
advertisement, and proves real typed foreign-lock refusal before and after the
advertisement without changing it. Drill 3 covers siblings, stale instance stop,
and failed branch release retaining the fence. Drill 4 additionally proves stale
advertisement omission, unknown identity refusal and forced-stop refusal with a
sibling reservation before SIGSTOP/down.

Drill 5 positively observes the actual returned old branch before reset and its
absence afterward, old data deletion, unchanged sibling lock inode and preserved
outside symlink sentinel. Drill 6 runs its winner IN the test JVM, positively
asserts entering instrumented boot/process Vars, and proves foreign exclusion at
two controlled pauses while the winner remains paused. Drill 7 covers captured-old
identity versus replacement and the destructive loser leaving winner data intact.
Drill 8 uses a controlled real child, checks the SAME FileLock and FileChannel
before deletion and after full ready, proves foreign exclusion at both points,
then KILL/onExit releases that same inode and a fresh process acquires it.

Ordinary events use the canonical 20 s backstop. The declared boot dial is
`:seon.config.operator/boot-bound-ms` = 300000 ms, based on measured 144131 ms
zero-store boot. Drill bounds are 300000, 180000, 120000, 120000, 360000, 360000,
240000 and 360000 ms respectively, covering their measured constituent cold boots,
publication and competing subjects. These authorized cold costs do not justify
minute-scale ordinary work. All child cleanup attempts run even after one failure;
unknown exit/release retains the root instead of deleting an occupied store.

Focused results: tooling busy exclusion `8ed74a43d301` (2/0/0), log rotation
`e5071486067d` (3/0/0), physical collection `80bde33b0825` (8/0/0). Later requests
correctly reused the latter two unchanged. CLI help loads without a JVM.

## Failures that improved the proof

- Initial listener accept in `user` failed because core server requires a
  namespace; using supported `io-prepl :valf` fixed it. Reaped child 36477.
- Stop run `d332313c3d02` refused missing expensive-fixture observation; the
  canonical helper now receives its real OS-exclusion reason.
- Concurrent run `0ab03f6db5e8` (11/1/0, 47610 ms) exposed a published fixture
  predating the new required config dial. Re-exported the canonical publication.
- Race run `ce4456281356` (11/1/0, 54341 ms) caught a still-live losing child.
  The client now finds the actual nested typed lock refusal and awaits its child.
- Continuity run `50d0d3a90fab` (15/0/1, 142282 ms) parsed diagnostic stdout as
  EDN. The controlled subject now waits for actual exit and both stream EOFs;
  the fresh lock probe also positively asserts exit zero.
- A preliminary drill 6 run had needlessly reloaded process/maintenance Vars,
  removing entering wrappers. It was not used as the final armed proof; the
  final run asserts that precondition and passes.
- The busy check exposed BB's unavailable FileLock close/release methods;
  FileChannel scope now owns release. An intermediate recorder refusal reproduced
  [immutable report line collisions](../../../seon/issues/moving-a-failing-assertion-conflicts-with-its-immutable-report.md),
  which remains an existing B4 issue rather than hidden work in this slice.
- Publication exposed redundant declarations with colliding lint identities;
  removed the redundant declarations and retained the existing
  [lint identity issue](../../../seon/issues/lint-identities-collide-for-multiple-findings-at-one-location.md).

## Review disposition and integration boundary

Fable #1: removed blanket 900000 ms waits and measured distinct paths. Its 9154 ms
reference was repaired IN-PROCESS boot, not cold JVM plus zero-store publication.
#2: hook config no longer owns operator timing; the declared operator fact does.
#3: unavailable endpoint diagnostics name the live exact-root identities.
#4: neither existing error owner exposes the suggested predicate, so the installed
three-key refusal check remains. #5 conflated drill 6's in-process winner with
drill 8's intentionally controlled child; the spec already describes both correctly.

The owned host pid 36681/start `2026-09-21T22:37:46.670Z` reached full readiness
in 9154 ms after repair, later serving prepl 54258 and URL
`http://127.0.0.1:54263` (wanted 7994). The freshly loaded BB MCP bridge proves JVM,
explicit database custody and SCI evaluation. The old long-lived installed MCP
server returned transport failure `error:null`; its connection reload is still
pending. Fresh bridge success does not prove the installed connection reloaded.

Canonical platform-guard run `807cd921066f` recorded 6/0/1: ten source-publication
tests reach the destructive fixture helper. Full names and diagnostic are in raw
results and the [existing admission-class issue](../../../seon/issues/platform-flow-census-reaches-root-cleanup-through-scheduler.md).
A separate owning-function probe positively confirms B1b cold-start is declared
and admitted, while start-during-reset refuses with the exact destructive path.
No platform green or baseline attribution is claimed. The orchestrator owns that
policy decision and final cut gate. The export regression remains fixture-excluded;
actual connected export/publication-base operations succeeded during setup.
`bin/test-check` reached its installed owner but its 5000 ms request bound fired.

The root orchestrator owns activation of AGENTS/testing guidance before the next
lane. This lane preserved unrelated working-edge/review documents and did not
push, create a worktree, or operate default.

## Size and ownership

Actual line counts at final source: `bin/seon` 26, BB client 387, boot 447: **860**
against approximately 900 total. Boot exceeds its individual 420 target by 27
lines because the installed projection/coherence/search/acquisition sequence must
remain until its owning cuts; the shell is 54 below 80 and client 13 below 400.
Moved maintenance is 1016, process 315, filesystem owner 409; tooling state 72;
real drill namespace 434 and test-only child 60. These moved owners are reported
separately, not hidden in the new-three-file budget. The mechanically generated
[per-file line inventory](b1b-file-counts-2026-09-21.md) lists every implementation
path in the lane commits plus this final owned source/test slice against launch
HEAD `995155f1e`, including removed files.

## Cleanup and final load

The final owned host load required boot, maintenance, process, filesystem, runner
and MCP namespaces successfully without starting another JVM. `bin/seon --root
tmp/b1b-initial-root down` returned the captured identity 36681 /
`2026-09-21T22:37:46.670Z` after actual exit. Its realized canonical fixture base
`tmp/fixture-bases/base-9162618621084851715` was removed by its shutdown owner.
A process-table check then found only the untouched default JVM (25658); all
drill subjects were gone. Removed the owned initial root and four physical export
bases. No foreign run root or fixture base was swept.

Final `clj-kondo` over the eleven pending source/test files reported zero errors
and 46 warnings (shadowed names, unused bindings/requires/private Vars and a
redundant `do`); this is not a warning-free lint claim. BB required the client,
tooling state and MCP namespaces successfully after the final description edit.
`git diff --check` passed.
