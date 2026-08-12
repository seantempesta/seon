---
type: research
status: active
tags: [testing, performance, datahike]
---

# Full-suite speed incident, 2026-08-03

## Result

The full suite's dominant cost was not cluster boot. The shared database
fixture rebuilt the complete first-party program manifest with clj-kondo for
each fresh database population. The suite performed that population 586 times.

The fix builds one newly populated in-memory Datahike base per test JVM, then
forks its sealed immutable database value into a distinct branch for each test.
Schema plus source population therefore happen once, while every test retains
its own branch head, connection, writer, schema evolution, datoms, and history.
The private base connection is never exposed to tests and is never transacted
after publication.

One additional fixture correction moves the REPL-parity database from `:each`
to `:once`. Those cases receive only an immutable database value, never the
connection, perform no transactions, and construct a fresh SCI ctx for every
REPL session. This preserves case isolation while removing 68 identical
database populations.

| Gate | Runner wall | Tests | Assertions | Failures | Errors |
|---|---:|---:|---:|---:|---:|
| Before | 36m 16.351s | 862 | 4,276 | 3 | 0 |
| After | blocked before terminal summary | — | — | — | 1 observed before stop |

The after gate was stopped at the repository's explicit foreign-lane boundary.
Its frozen launch snapshot included in-flight uncommitted changes to
`resources/seon/schema.edn`, `src/seon/cluster/run.clj`, `src/seon/fn.clj`,
`src/seon/program.cljc`, and `src/seon/schema.clj`. The first test in
`seon.cluster.armed-test` booted, then failed after its 20-second backstop while
waiting for a required database fact. That namespace does not use the changed
shared database fixture. Continuing would have produced neither a valid
correctness gate nor a comparable wall time, so the invocation was interrupted
and its log retained at `tmp/test-profiles/final-2026-08-03/runner.log`.

The three baseline failures were the already-filed config-derivation defect.
Commit `744ed9ef1` fixed that unrelated issue after the baseline invocation had
copied its source tree; it is not attributed to this performance work.

## Reproducible profile

The profiler is
`research/scripts/profile-test-suite-2026-08-03.clj`. It can launch `bin/test`
or parse a completed timestamped log with `--input`. It writes the complete EDN
profile plus namespace and test TSVs. Per-test output separates:

- body time between `BEGIN test` and `END test`;
- pre-BEGIN time since the namespace began or the preceding test ended, which
  captures `:each` fixture setup; and
- their effective sum.

This distinction matters: Clojure's `:each` fixture executes before the
runner's `BEGIN test` report event. Without it, REPL parity appeared to contain
mostly subsecond tests while 164.240 seconds of fixture work was unassigned.

Baseline command and artifacts:

```sh
clojure -M \
  docs/prds/sci-execution-runtime/research/scripts/profile-test-suite-2026-08-03.clj \
  --input tmp/orchestrator/full-suite-2026-08-03.log \
  --output tmp/test-profiles/baseline-2026-08-03
```

The parser reported zero anomalies. Its wall measurement is the timestamped
runner span from `START` through the final `END namespace`, not shell startup or
post-run cleanup.

## Direct fixture falsifier

A warm-JVM isolation probe timed one complete `with-database` setup repeatedly
before the test body:

| Operation | Median |
|---|---:|
| Fresh connection plus full population | 2,361 ms |
| `cluster/populate-source!` | 2,359 ms |
| `seon.fn/index!` | 2,294 ms |
| `seon.fn/build-manifest` | 1,990 ms |
| Population transaction after a supplied manifest | about 300 ms |
| Create/connect/release/delete | 1–2 ms |

Supplying one already-built immutable manifest reduced the same complete setup
to a 398.5 ms median (372–446 ms observed). Multiplying 586 fixture populations
by 2.361 seconds predicts 1,383.5 seconds, or 23.1 minutes. That explains the
bulk of the 36.3-minute run; the 38 measured cluster boots cannot.

The manifest contained 153 file artifacts and 2,739 program identities. The
production supplied-manifest path already existed and is regression-covered by
`seon.fn-test/indexing-uses-a-prebuilt-manifest-without-analysis`; the fixture
now uses that owner rather than adding another indexing mechanism.

## Baseline attribution

Worst namespaces before the fix:

| Namespace | Total | Tests | Dominant cause |
|---|---:|---:|---|
| `seon.dev.fresh-operator-test` | 243.575s | 26 | fresh JVM/operator lifecycle |
| `seon.cluster.boot-test` | 234.917s | 27 | boot-tower behavior |
| `seon.repl-parity-test` | 185.242s | 69 | 164.240s pre-BEGIN fixture setup |
| `seon.cluster.message-test` | 167.510s | 12 | generated histories with fresh databases |
| `seon.problems-test` | 154.337s | 9 | generated projections with fresh databases |
| `seon.reconcile-test` | 150.952s | 7 | generated populations with fresh databases |
| `seon.cluster.wake-test` | 129.428s | 10 | generated wake histories with fresh databases |
| `seon.render.walk-test` | 109.176s | 12 | generated walks with fresh databases |
| `seon.render.transcript-test` | 102.721s | 9 | generated histories with fresh databases |

The worst individual effective tests were the generated state/history
properties: message histories 138.444s, reconcile convergence 137.072s, wake
routing 113.107s, problems omission 86.591s, and transcript totality 85.817s.
No property trial count or assertion changed.

The second cut removes the remaining repeated transaction cost. Forking and
retiring a child branch measured 0.59–1.41 ms over 500 cycles; a child
transaction measured about 1.23 ms median. Sixteen concurrent child branches
were falsified for isolation: child-only schema and data were absent from the
base and every sibling.

## Isolation preserved

### Immutable source analysis

`seon.test-support/source-manifest` is an ordinary `def` holding a delay. It is
derived once per newly launched test JVM from `seon.fn/source-roots`. It is not
`defonce`, not serialized, not stored across runs, and not a database. The
runner requires every selected namespace before executing any test, so the
first force observes the complete copied source tree for that invocation.

The one per-JVM base uses `:commit-graph? false`, `:keep-history? true`, and a
memory store. Disabling the commit-ID graph prevents every child transaction
from retaining another immutable commit object in the store; ordinary current,
`as-of`, and history behavior remains available. Tests whose subject is
commit-ID branching own a production-shaped store instead.

Each `with-database` call retains these independent resources and transitions:

- a distinct active branch and branch head;
- its own connection, writer, immutable database values, and history;
- its own source-digest seal and optional extra schema;
- connection release and branch retirement before its lease can be reused.

Datahike removes a deleted branch from the branch roster but retains its head
key. The fixture therefore leases a bounded set of branch names and overwrites
a retired lease with the same immutable base on reuse. A 100-cycle falsifier
showed the underlying memory-store key count growing from 10 to 111 with the
commit graph enabled, but only from 8 to 9 with the fixture configuration. A
lease is returned only after Datahike accepts branch deletion; teardown failure
quarantines the name instead of risking mutable-state reuse. The whole memory
store is released and deleted by the new JVM's shutdown hook.

Two explicit escape paths preserve stronger isolation contracts. A supplied
`:seon.test-support/database-id` still creates a fresh physical store because
the store ID is the test subject. Blob tests request
`:seon.test-support/fresh-store?` because Konserve blob keys are store-global,
outside Datahike branch facts. No test sees data written by another test.

### REPL parity

The namespace fixture applies config once and binds only `@connection`, an
immutable database value. Searches of the namespace found no transaction call
and no access to the connection outside the fixture. `repl-session` constructs
a new `seon.sci.eval/cluster-ctx` for each case. Focused proof retained 69 tests
and 69 assertions and reduced namespace runtime from 185.216s to 24.414s.

### Session images

Five same-JVM session-image tests formerly created disk-backed stores that were
discarded. They now use fresh memory stores populated from the cached immutable
manifest. Session blobs are store-global, so these tests do not use the shared
branch base. The one cross-two-fresh-JVM test remains file-backed because
persistence across a process boundary is its subject. The focused namespace
remained 8 tests / 44 assertions and fell from 72.746s to 30.324s; the genuine
two-JVM case alone accounted for 19.510s.

## Cluster-boot audit

The 23 successful boots in `seon.cluster.boot-test` were examined only after
the fixture cause was proved. Eighteen are the subject of their tests. Five are
setup convenience and could be replaced with direct, isolated branch seeding;
one unrelated packaged-source test also creates an unused private root. They
remain the next named optimization boundary after a clean-tree full profile;
boot-tower subjects retain their real boots. No test launches a new JVM unless
a process boundary is itself under test.

## Retained-run reaping

At incident start, 34 interrupted run roots occupied 3.9 GB under
`tmp/test-runs`. `bin/test` now reaps at the start of the next invocation:

- every root whose recorded PID is alive is preserved;
- the three newest inactive roots younger than 24 hours are preserved;
- inactive roots older than 24 hours or beyond the newest three are deleted;
- only direct `run.*` directories under the canonical run parent qualify; and
- deletion uses `seon.fs/delete-recursively!`, whose explicit-root walk never
  follows symlinks. Successful-run cleanup uses the same owner.

An isolated proof retained an old live-PID root and the three newest inactive
roots, reaped an old root and two excess recent roots, and preserved an external
sentinel reached by a symlink inside the deleted root. `seon.fs-test` passed 1
test / 5 assertions through a generated test-run parent.

## Current-code guarantee and its boundary

`bin/test` copies the mutable first-party roots `bin`, `config`, `resources`,
`script`, `src`, and `test` into a new per-invocation root before starting a new
JVM. That freezes the first-party bytes present at launch, including dirty and
untracked files in those roots; edits after launch cannot change the test JVM.
The printed Git SHA identifies `HEAD`, but does not describe dirty or untracked
bytes, so it is not by itself a complete content identity.

The running shell now also executes a launch-time snapshot of its own script
bytes. The baseline JVM reached its terminal summary successfully but the
outer shell later parsed concurrently edited `bin/test` bytes and failed. The
snapshot prevents a long-running invocation from changing control flow after
launch while preserving the same per-run source copy.

The guarantee does not extend to every projected top-level path. Large
dependencies such as `reference-code/` are symlinked into the run root, and the
shared dependency-class cache is also external; concurrent edits there are not
frozen. Nor does it apply to a long-lived application JVM: re-evaluating a Var
changes loaded behavior, but ordinary file edits do not republish database
program facts. Existing cluster branches, including any live `default`
cluster, remain sovereign at their older published commit until explicitly
initialized/reforked. This work never used or mutated the default operator root
or live `default` cluster.

The test fixtures do not reuse a previously published store. Each invocation
creates and populates a new in-memory base from that invocation's copied
source, and the base dies with that JVM. This guarantee depends on `bin/test`
launching a new JVM per invocation; a future long-lived runner would need an
explicit per-invocation base reset keyed to the copied source tree. Boot tests
create private operator roots inside the invocation and may reuse state only
where a single test explicitly exercises restart/reopen behavior.

## Dependency ledger and commits

- Datahike `0e8601d7f2f6`:
  `reference-code/datahike`, exercised through `datahike.api` and the existing
  memory-store fixture.
- clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`:
  `reference-code/clj-kondo`, invoked by `seon.fn/build-manifest` through
  `seon.fn.analyzer`.
- Malli `80138076960e`:
  `reference-code/malli`, supplying the admitted schema population.
- Proximum `9846d3e79e1a`:
  `reference-code/proximum`, supplying Datahike's branch and commit graph.
- First-party owners: `seon.fn/build-manifest`, `seon.fn/index!`,
  `seon.cluster/populate-source!`, `seon.test-support/with-database`, and
  `seon.test.runner`.

Path-limited commits, none pushed:

- `4146a7e45` — timestamp every test progress event;
- `5ebb1bf07`, `53a46f4f7`, `8ff666a7b` — reproducible profiling and offline
  attribution;
- `7156e092f` — bounded, no-follow retained-run reaping;
- `0cc33e9dd` — immutable manifest reuse and REPL-parity fixture scope;
- `c2857ae5c` — isolated child branches from one populated memory base;
- `68b14fd68` — memory stores for same-JVM session-image tests;
- `7d1a34b4b` — freeze the running `bin/test` shell bytes.

## Verification

Focused gates after the fix:

- `seon.test-support-test`: 6 tests / 16 assertions / 0 failures / 0 errors,
  with its explicit full-manifest reconstruction retained;
- `seon.repl-parity-test`: 69 tests / 69 assertions / 0 failures / 0 errors,
  28.87s shell wall;
- branch-fixture integration selection: 93 tests / 350 assertions / 0
  failures / 0 errors, 42.376s runner wall; and
- `seon.sci.session-image-test`: 8 tests / 44 assertions / 0 failures / 0
  errors, 30.324s runner wall.

The worst fixture-heavy focused namespaces now compare as follows:

| Namespace | Before | After | Tests after |
|---|---:|---:|---:|
| `seon.cluster.message-test` | 167.510s | 2.161s | 12 |
| `seon.reconcile-test` | 150.952s | 11.421s | 7 |
| `seon.custody-stability-test` | 62.512s | 4.451s | 4 |
| `seon.sci.session-image-test` | 72.746s | 30.324s | 8 |

The focused branch selection retained every generative trial and assertion.
The deliberately expensive canonical fixture test still rebuilds the manifest
it is asserting, accounting for 6.77s of its 9.715s namespace wall. The full
after wall and terminal count comparison remain unclaimed until the unrelated
in-flight source snapshot reaches a coherent gate.

## Owner-ruled recurring and full tiers

The 2026-08-03 owner ruling supersedes bare-full semantics. Test metadata is
now the single selection authority: a non-blank `:seon.test/long` reason on a
var or namespace declares process/boot-bound coverage. Bare `bin/test` runs
everything else and prints every skipped symbol plus `bin/test --full`.
Explicit namespace selection, `bin/test --full`, and
`SEON_TEST_FULL=1 bin/test` remain complete selections.

The final default profile at
`tmp/test-profiles/default-tier-final-2026-08-03` passed 813 tests / 4,018
assertions with zero failures or errors in 174.935 seconds, skipping 35 named
tests. That is 74.9% faster than the last 697.50-second full gate. Sharing one
published operator root reduced the explicit 28-test / 133-assertion boot
namespace from 171.541 seconds to about 71.7 seconds without removing an
assertion.

The explicit full rerun is blocked on a foreign render-lane census change:
five former test namespaces are deleted and one replacement namespace is
present, so the loaded metadata contains 848 tests rather than the required
883. No full result is claimed for that snapshot. The defect analysis,
ranking, remaining choke-point refactors, and exact boundary are recorded in
`test-defect-analysis-2026-08-03.md`.
