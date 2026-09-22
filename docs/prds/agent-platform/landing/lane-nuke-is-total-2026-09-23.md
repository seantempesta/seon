---
type: landing
status: landed; default restored from an archive; HEAD from-zero blocked by gitlink pins
created: 2026-09-23
---

# Lane nuke-is-total (2026-09-23)

Owner rulings: "If things are fucked. Then nuke it and rebuild and pay the cost
now." / "nuke CANNOT refuse. Nuke drops everything and always returns a stable
proper state." Plan §7 row "Schema change and reset": the nuclear option rebuilds
from COMMITTED inputs, surfaces any failure with its full cause, and never leaves
a JVM with a deleted store.

## 1. Cause of the 21:13Z refusal

`bin/seon reset --force` at 21:13Z (pid 36006) deleted `data/store` and refused
after 38.4 s with `class clojure.lang.Keyword cannot be cast to class
java.lang.Number`, no frame. Reproduced in pid 36006's REPL (MCP `eval_clj`, JVM
mode) by calling the failing step directly:

```clojure
(try (seon.cluster/refresh-source! "/Users/sean/src/seon/data/clusters")
     (catch Throwable e ... (Throwable->map-style chain and seon frames)))
```

15,178 ms; one `java.lang.ClassCastException`, top Seon frames
`seon.cluster$retired_attributes cluster.clj:1092` ←
`declaration_changes :1148` ← `accrete_schema_population_BANG_ :1409` ←
`populate_source_BANG_ :1483` ← `seon.cluster.source$publish_BANG_ source.clj:497`
← `full_source_refresh_BANG_ :1814` ← `refresh_source_BANG_ :2301`.

Cause: `retired-attributes` sorted `(keys (:schema database))` before selecting
idents. Datahike's `:schema` also maps each attribute entity id to its ident
(`reference-code/datahike/src/datahike/db/transaction.cljc:120`), so the keys
mix longs and keywords. Minimal reproduction on a memory store with one attribute
installed: numeric keys `[1]`, same exception (14 ms). The code was the
then-uncommitted hunk of `8ce91fbaa` (committed 21:14:50Z, after the 21:13:19Z
JVM start loaded it; loaded `retired-attributes` at line 1063 vs 1048 in the file)
— i.e. HEAD code by the time of diagnosis. The fix (select idents before sorting)
landed as `c692a5c11` (committed from the shared file by the schema lane with this
lane's exact hunk); regression `test/seon/cluster/retired_attributes_test.clj`
(`2c7026ebd`). A `bin/test-fast` run of it refused before execution because the
recording authority needs a published `current-src` (default had no store); the
regression is proven by the memory-store probe above and by both nuke boots below
passing through `retired-attributes`, not by a recorded test run.

## 2. Restore of `default`

HEAD `bc8a1fa68` + `c692a5c11` as a frozen `git archive` in
`tmp/nuke-source-bc8a1fa68` (0.55 s, 163 MB, `reference-code` symlinked; every
gitlink matched its checkout). `tmp/nuke-source-bc8a1fa68/bin/seon --root
/Users/sean/src/seon reset --force`: exit 0, wall 116.59 s, pid **43581**, start
`2026-09-22T21:19:47.718Z`, `:seon.boot/ready-ms 97,588`, missing layers `[]`,
problems `{}`, agent count 1, source commit `6ab2f153-7102-5b22-992f-ea5896f2e90b`.
`bin/seon status` 0.10 s healthy; MCP `runtime_status` answers `alive`, health
`observed`, procs reply. Log `tmp/orchestrator/nuke-is-total-restore-2026-09-23.log`.
Web bound 62013 because 7994 was held by pid 35930 (a java process of another
lane) at the time.

**Limit:** this `default` runs from the archive, so
`seon.fs/source-directory` = `tmp/nuke-source-bc8a1fa68`; lane hook publications
relativize to `../../src/...` and publish nothing
([issue](../../../seon/issues/an-archive-booted-default-relativizes-hook-paths-outside-its-source.md)).
It rejoins the files with `bin/seon stop && bin/seon start` from the repository —
but not until HEAD's from-zero defect below is fixed, because that publication
writes gitlink pins. Keep `tmp/nuke-source-bc8a1fa68` while pid 43581 lives.

## 3. The total nuke (`2c7026ebd`, `00d50f31b`)

`bin/seon nuke --force` (and `reset --force`, until the fresh-branch reset of §7
replaces it — one function, `seon.operator/nuke!`):

1. **Committed inputs only.** `committed-source!` builds `<root>/data/source/<sha>`
   from `git archive <sha>` (HEAD for the CLI), once per commit (staging dir, then
   atomic move; the directory's existence is completion). Each gitlink is linked
   to its checkout when the checkout's TRACKED bytes are at the pin, else archived
   at the pin (then `clojure -X:deps prep` prepares it — http-kit compiles Java).
   The replacement JVM is launched with that directory as its classpath source
   (`launch-child!`, `-Dseon.repository.root=<snapshot>`). No working-tree hunk
   of any lane is loaded or indexed.
2. **Full cause.** Every operator and boot catch on the request path hands the
   caught Throwable to the one constructor `seon.error.refusal/diagnostic`
   (F0 adds `:seon.error/chain`): `boot.clj` `diagnostic` 4-arity used by
   `start!` (`:boot-failed`), `request!` (`:operation-failed`) and
   `readable-response`; `operator.clj` `diagnostic` 4-arity used by `request!`,
   `-main`, `nuke-attempt!` and the malformed-PREPL-result catch (which now
   rethrows with its cause). `down!` returns graceful-request failures as
   `:seon.operator/graceful-failures` instead of `(catch Exception _ nil)`;
   `stop!` treats an absent advertisement file as the declared case and lets an
   unreadable one surface (was `(catch Exception _ nil)`).
3. **No JVM beside a deleted store.** `nuke-attempt!` downs the root's processes,
   launches, and unless readiness has no missing layers terminates every process
   of the root by exact identity. One retry covers a transient loss (a racing
   start winning the replacement gap); a second failure returns both attempts'
   complete values and phases with `:seon.operator/process-exit? true`.

Remaining declared-case catches left as they are: `terminate!`
(`TimeoutException` → forcible destroy), `force-stop!` (a halted JVM drops the
socket → `::disconnected`, then awaits exit), `prepl-value!` socket timeout
(rethrown as a named refusal).

## 4. Drills (scratch root `tmp/nuke-drill-root`, removed after; no holder)

| Drill | Subject | Result | Wall |
|---|---|---|---|
| broken uncommitted hunk, HEAD `2c7026ebd` pre-`00d50f31b` | top-level `(throw ...)` appended to `src/seon/cluster/boot.clj` | refused before the REPL: `Error building classpath ... [http-kit/http-kit]` (archived pin unprepared) — fixed in `00d50f31b` | 3.68 s |
| same hunk, HEAD `535ce45cf` | `bin/seon --root tmp/nuke-drill-root nuke --force` | both attempts refused by [gitlink pins](../../../seon/issues/gitlink-pins-refuse-every-from-zero-publication.md) (`seon.fn$require_committed_BANG_ fn.clj:51`); both JVMs (52551, 53236) terminated; `status`: no live JVM; exit 1 | 120.67 s (attempts 58.9 s, 50.3 s) |
| same hunk, committed revision `f40378571` (HEAD `cd95c73f5` + hashed pins, `git commit-tree` on a scratch index, on no branch) | `seon.operator/nuke!` with `:seon.source/revision` | **ready**: pid 56655, `ready-ms 74,544`, missing `[]`, problems `{}`, no failed attempt | 95.48 s (source 3.57 s, down 81 ms, launch 91.8 s) |

The hunk was removed after each drill; `git diff src/seon/cluster/boot.clj` empty.
Logs: `tmp/orchestrator/nuke-is-total-drill-2026-09-23.log`,
`tmp/orchestrator/nuke-is-total-drill-positive-2026-09-23.log`.

## 5. Decision for the owner (the plan does not make it)

When HEAD itself cannot boot (as at 21:13Z and at every HEAD since `678009fcd`),
the nuke cannot reach readiness from committed inputs. Implemented: option A.

- **A (simplest; implemented).** Two attempts from HEAD, then no JVM and both
  complete causes, exit 1. Guarantee: never a half JVM beside a deleted store,
  never a hidden defect. Cost: none extra. Gives up: `default` is absent until
  HEAD is fixed.
- **B (recommended).** A, then fall back to the newest first-parent ancestor
  of HEAD that reaches readiness (bounded, e.g. three commits, each ~95 s),
  reporting HEAD's failure as the result's first member. Guarantee: always a
  ready `default` when any recent commit boots; HEAD's defect stays loud. Cost:
  up to ~5 min more on a broken HEAD. Gives up: `default` then runs an older
  program than HEAD.
- **C.** Nuke then rejoin the files automatically (stop, start from the
  repository; on failure restart from the archive). Guarantee: `default` is the
  files whenever the working tree publishes. Cost: two resumes (~30 s each) plus a
  working-tree publication. Gives up: in-flight hunks re-enter `default`, which
  §7 keeps out of the nuke.

## TIMINGS (every operation over 1 s)

| Operation | Wall ms | Phases |
|---|---|---|
| `refresh-source!` reproduction in pid 36006 | 15,178 | refused at `retired-attributes` |
| `bin/test-fast` on the regression | 36,290 | snapshot 4 s; refused at recording (no `current-src`) |
| restore: `reset --force` from `bc8a1fa68` archive | 116,590 | ready 97,588 |
| drill 1 (classpath) | 3,680 | — |
| drill 2 (HEAD `535ce45cf`, refused twice) | 120,670 | 58,936 + 50,292 launch |
| drill 3 (ready) | 95,480 | source 3,569; down 81; launch 91,806; ready 74,544 |
| `committed-source!` first build (no prep) | 1,770 | archive + 20 gitlinks |

Everything over 10 s here is the from-zero boot, recorded in
[from-zero-boot-takes-minutes](../../../seon/issues/from-zero-boot-takes-minutes.md)
(three rows added), plus the 15 s single refresh (the same cold indexing path).

## Commits and paths

- `c692a5c11` (landed by the schema lane with this lane's hunk) — `src/seon/cluster.clj`.
- `2c7026ebd` — `src/seon/cluster/boot.clj`, `script/seon/operator.clj`,
  `test/seon/cluster/retired_attributes_test.clj`.
- `00d50f31b` — `script/seon/operator.clj`.
- this note and the two issues.

Verification limits: no recorded test run (the recorder needed a store while
default was down); the nuke's failure-path proof is drill 2 (a real HEAD defect),
not a constructed one; the racing-start retry is unexercised. F0's chain appears in
the operator's own diagnostics (`bin/seon status` on an empty root printed
`:seon.error/chain`); the boot-side chain through `request!` is covered by the same
constructor but was not observed on a failing JVM boot after F0.
