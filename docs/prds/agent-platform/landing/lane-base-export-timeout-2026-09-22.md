---
type: landing
status: implemented; cold gate belongs to orchestrator
created: 2026-09-22
---

# Base export timeout

## Cause and scope

At baseline `5361d0dfbaad746274c52152e500b3ca0490a809`, preparation sent
**one** PREPL form. `cache/prepare-base!` called `refresh-source!` and then
`publication-base!`, which called `refresh-source!` internally. Those are
the two SOURCE sequences in `tmp/prepare-head-base-2026-09-22c.log`.
There was no third socket request. The observer selected
`operator-boot-bound-ms`, **300,000 ms**, not the ordinary 30,000 ms bound.
The export then emitted no events until its return. This is legitimate
full-store work outlasting the wrong operation's silent read allowance.

The one requested `jcmd 15000 Thread.print` sample is retained at
`tmp/base-export-threads.txt` (08:32:36Z). Original connection 43 was
RUNNABLE, elapsed 491.33 s, CPU 177724.71 ms. Frames:

- `FressianWriter.writeString` / `writeObject` / `writeIterator`
- `konserve.serializers.FressianSerializer._serialize` (`serializers.cljc:53`)
- `konserve.impl.defaults/update-blob` (`defaults.cljc:104`)
- `konserve.core/assoc` (`core.cljc:371`)
- `seon.cluster.export/reidentify-at!` (`export.clj:277` at baseline)
- `seon.cluster.export/export!` (`export.clj:335`)
- `seon.cluster/publication-base!` (`cluster.clj:2285`)

The same sample caught reproduction connection 53 in
`ProcessImpl.waitFor` → `seon.cluster.process/run-process!` → `clone!` →
`copy-store!` → `export!`. The copy was approximately 1.3 s old. The
original timed-out request was still active; disconnect is not termination.
Later read-only thread inventories positively showed both exports ended
before their destinations were removed.

The source inventory contained **898,237 entries**, `du -sh` **248 GiB**.
Copying is proportional to the full store's files; re-identification reads
and rewrites every reachable head/commit, including each record's schema
payload. This is not changed-input publication work. No reachable-only
fixture or B4 mechanism is introduced. Source store data is untouched.

## Dependency and request evidence

Pinned Datahike: `006e634ae955c186619adb5f3868cca29d8c97fb`.
`reference-code/datahike/src/datahike/connector.cljc:159` validates stored
store identity against connect-time identity; every reachable commit can
become a branch source. Export supplies the final path-derived identity.
Re-identification therefore occurs once per full export, before atomic rename.

Pinned Konserve: `07377c27c8288b7484f0aa7b82e8158b415985be`.
`reference-code/konserve/src/konserve/core.cljc:348` owns top-level `assoc`;
`filestore.clj:870` opens the raw file store; `serializers.cljc:34-53` owns
Fressian round-tripping. The export walks head/commit keys with a visited
set and changes stored config, not database datoms or index roots.
Clojure's real `io-prepl` emits output and one terminal result
(`reference-code/clojure/src/clj/clojure/core/server.clj:194-244`).

The source-read-only reproduction deliberately isolated export from the
compound form's publication writes. `tmp/base-export-probe.clj` sent this
form through `seon.operator/prepl-value!`, with
`(operator/operator-boot-bound-ms {})` and an observer printing output:

```clojure
(let [held (seon.cluster/acquire-root-store!
            "/Users/sean/src/seon/data/store")]
  (try
    (seon.cluster.export/export!
     {:seon.store/store held
      :seon.export/parent-dir "/Users/sean/src/seon/tmp/base-export-probe"})
    (finally
      (seon.cluster/release-root-store!
       "/Users/sean/src/seon/data/store"))))
```

`tmp/base-export-probe.log` records its timeout. File timestamps establish
08:32:35.220909Z client start and 08:37:35.245951Z timeout output,
**300.025 s**. Destination parent mtime after the final atomic rename was
08:40:39.582628Z: **484.34 s** to publication. This latter duration is a
filesystem-observed completion, not a received PREPL terminal value.

## Owner fix

- `cache/prepare-base!` sends the single complete `publication-base!` request
  and identifies its operation as `:export`. The removed preliminary refresh
  did not include deleted stored paths; the existing publication owner does.
- `operator/operation-bound-ms` selects by operation, independently of output
  observers. Export, initialization and ordinary requests use their declared
  config facts. Socket expiry names the missing output/result event and bound,
  explicitly retaining an unknown operation outcome.
- `export!` reports copy start/completion, completed re-identification records,
  completed fallback branch migration work, and final store publication.
- `:seon.config.operator/export-bound-ms` rehomes the export clone's existing
  600,000 ms allowance beside the silence and boot facts. Both child and client
  read that declaration. No newly tuned numeric allowance is added. The
  client allowance is renewed by events; it is not total request duration.
  The new config leaf is optional for existing cluster config values, with a
  shipped positive default; adding it does not narrow those map contracts.

## Regression and preparation proof

One regression, `seon.dev.base-export-bound-test`, uses the canonical
published operator-root helper and a real `clojure.core.server/io-prepl`
listener with its actual process identity/port. It verifies operation bounds
with and without an observer, real output delivery for a 1.6 s request under
1 s read intervals, and the base client's explicit operation/observer data.
The bound observer delegates to the real socket sender; it does not fake
PREPL. The separate preparation-request data assertion captures the client
options; the complete request is exercised by the preparation proof below.

Before (`--paths` only the test, HEAD 5361d0dfb): run **04a7d55dad5e**,
**6 executed, 0 reused, 77 assertions, 7 failures, 0 errors**.
Log: `tmp/base-export-before.log`. Six operation-bound assertions and the
base request's missing `:export` declaration failed.

Initial after: run **a95ad17aa05b**, **6 executed, 0 reused, 77 assertions,
0 failures, 0 errors**, 1682/1682 registered/instrumented contracts;
regression body **3.4405 s**. Log: `tmp/base-export-after.log`.

The committed [measurement script](measure-base-export-2026-09-22.clj)
runs the child's `cache/prepare-base!` path in one JVM, through a real PREPL,
on a canonical scratch root. The orchestrator command was **not run**.

The first attempt failed in the redundant preliminary refresh:
`NoSuchFileException` for deleted `test/seon/error_class_schema_test.clj`.
Raw log: `tmp/base-export-preparation.log`; full exception was retained in
`tmp/base-export-preparation-failure.edn`. Removing that refresh gives the
existing owner the complete stored/current path union on its first request.

Successful command, from isolated `tmp/base-export-wt` at 5361d0dfb plus
owned paths (reference-code linked to the pinned checkout):

```sh
clojure -J-Dseon.test.published-base=/Users/sean/src/seon/target/test-published-bases/b771bf4fa00eea263a2b679e89aa58fce34471659c38a5c1e11c7c43786797e0/base \
  -M:test docs/prds/agent-platform/landing/measure-base-export-2026-09-22.clj
```

| Clock | Result |
|---|---|
| `(seon.test.cache/prepare-base! root "." destination {})` | **179920.572375 ms**, returned exact destination |
| Publication | one SOURCE request; 193 inputs; 3252 schemas / 960 functions; 3673 contract rows |
| Export | 72 records; copy/re-identification/publication events observed |
| Artifacts | store directory and manifest positively verified |
| Heap after completed preparation | 1,293,842,304 used / 4,563,402,752 committed bytes; point-in-time, not peak/delta |

Raw log: `tmp/base-export-preparation-2.log`; root:
`tmp/base-export-wt/tmp/base-export-proof-57eb42eaec67`, removed by the script
only after the synchronous request completed. This uses the new committed
measurement script for the explicitly requested preparation path; the older
whole-publication lifecycle script uses retired CLI flows and was not run.

## Live and isolation boundaries

Foreign dirty docs and all foreign/untracked source were preserved. Tests
used HEAD plus explicit owned paths; preparation used 5361d0dfb plus those
paths, not foreign changes to db/fn/config/agent/program. Subsequent shared
HEAD advanced externally to feafab1b3; that does not change the recorded
preparation identity.

The hook was already disabled on entry; no hook publication is claimed.
A targeted live reload rebuilt the `config/defaults` form, reloaded only
`seon.cluster.export`, and called the installed arming owner. The return
projection refused `mcp-effective`'s map; raw PREPL showed the export bound
600000 and a config value valid under the live database projection. This is
recorded as degraded MCP evidence, not a successful adoption. Before further
recovery completed, default was externally replaced: PID 15000 → 21908 at
08:50:41Z. This lane never stopped/reset/restarted default. The replacement
initially reported missing cluster projection state during boot.

`tmp/test-runs/run.lhqEgp` and `tmp/base-export-probe` were deleted after
exact-root process inventories were empty and live export frames had ended.
Cleanup used `seon.fs/delete-recursively!`, which does not follow symlinks.
Final test/load/commit and replacement readiness results follow below.

## Final verification

Final focused command:

```sh
bin/test-fast --paths config/default.edn resources/seon/schemas/seon.config.operator.edn \
  script/seon/operator.clj src/seon/cluster/export.clj src/seon/test/cache.clj \
  test/seon/dev/base_export_bound_test.clj -- \
  seon.test-cache-test seon.dev.base-export-bound-test
```

Snapshot HEAD **bf37bed933d161b8ba6e6655f81c7e849e4fa325**, root
`tmp/test-runs/run.2qmrdr`, run **46c6844a35af**: **6 executed, 0 reused,
77 assertions, 0 failures, 0 errors**. Arming: **1682/1682**, 1676 program
armable Vars. Regression body **3.489841 s**. Log:
`tmp/base-export-final-tests.log`. The fast runner removed its snapshot.

The replacement default's raw PREPL answered:

```clojure
(do
  (seon.cluster/project-next-prepl-value!
   {:seon.dev.mcp/read-only? true :seon.dev.mcp/project? false})
  {:export-bound (:seon.config.operator/export-bound-ms seon.config/defaults)
   :progress (with-out-str
               ((ns-resolve 'seon.cluster.export 'progress!) "live proof"))})
;; {:export-bound 600000, :progress "seon: EXPORT live proof\n"}
```

Log: `tmp/base-export-live-final.log`. This proves loaded export behavior;
it does not prove successful cluster boot/adoption. At 08:55:46Z the
externally replaced PID 21908's status still refused with
`count not supported on this type: Keyword`; MCP reported missing cluster
projection state. Those are retained foreign lifecycle boundaries, not green
status. They did not prevent the armed focused test run or raw export probe.

Load checks passed: `clojure -M -e "(require 'seon.test.cache)"` in the
shared checkout; `clojure -M -e "(require 'seon.test.cache 'seon.cluster.export)"`
in the owned snapshot; and
`bb --classpath script:src:resources -e "(require 'seon.operator)"`.
Logs: `tmp/base-export-head-load.log`, `tmp/base-export-owned-load.log`,
`tmp/base-export-client-load.log`.

The final regression adds direct observation of the real
`export/reidentify!` owner's completed-record output through the socket.
Before, run **ce5b3c5e7c69**: **6 executed, 0 reused, 79 assertions,
8 failures, 0 errors** (`tmp/base-export-regression-before.log`). The
additional failing assertion is absent export progress, alongside the
seven bound/request-data failures. The 1.6 s progress-continuity case itself
passes before and after, proving the socket already honors continuing events.

After the direct export-output assertion and final helper contracts,
run **e3f047686dea** passed: **6 executed, 0 reused, 79 assertions,
0 failures, 0 errors** (`tmp/base-export-regression-after.log`). This is the
final regression verdict; earlier 77-assertion runs are intermediate evidence.
The production diff contains 62 added / 28 removed lines across five files;
the regression is 85 lines, and its reusable preparation measurement is 50
lines. Documentation records the separate boot/MCP limitations rather than
claiming the cold gate or platform proof ran.

Final snapshot `run.iLmSfR` armed 1684/1684 contracts; the regression body
measured 4.879695 s. The old snapshot worktree had no exact-root process
holder before cleanup.
