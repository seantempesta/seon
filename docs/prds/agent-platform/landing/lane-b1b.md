---
type: report
status: implementation in progress
created: 2026-09-21
tags: [agent-platform, boot, operator]
---

# B1b implementation evidence

The rewrite is in progress. Five of eight canonical destructive drills have positive iteration results below;
remaining drills and default replacement/platform gate are **pending**.
The owner authorized temporary boot/tool breakage between commits. Default has
not been stopped or replaced by this lane; the orchestrator owns that checkpoint.

## Changes and scope

`26143d5f2` adds destructive store admission after acquiring the existing sibling
FileLock, before database probing or deletion. The lock inode is retained.
Filesystem measurements moved to `seon.fs`; collection and log rotation moved to
`seon.maintenance`; bounded tooling subprocess execution moved to
`seon.cluster.process`. Publication, registry, schema/projection, search and SCI
acquisition semantics remain the installed owners.

The tooling-only changed-test report lock now refuses busy requests immediately.
Its former lifecycle queue/holder files were removed; existing subprocess execution
and reaping bounds remain. It has a process-local reservation before opening the
kernel lock, preventing a same-process second descriptor close from dropping the
held fcntl lock. This is tooling resource exclusion, not boot/reset lifecycle state.
A narrow concurrent-request regression remains pending.

## Early evidence (not completed boot)

`bin/seon help` and BB client namespace loading return successfully without launching
a JVM. Pure argv validation refuses reset without force, invalid cluster paths,
a cluster argument to down, and unknown commands.

First owned scratch child pid 36477/start `2026-09-21T22:35:32.815Z` reported
prepl 53899 before program loading, but the initially chosen `user` accept Var
failed because Clojure's server requires its namespace. Replaced with core
`io-prepl`'s supported `:valf` argument. Exact-root `down --force` reaped that child.

The retained second scratch child is pid 36681/start
`2026-09-21T22:37:46.670Z`, prepl 53938, root `tmp/b1b-initial-root`.
The real transport evaluation `(+ 1 1)` returned `2` before full program loading
and after a later source-analysis refusal. The later refusal retained its listener,
partial instance and store holder. That early observation alone did not prove full boot; subsequent complete evidence appears below.

Canonical source analysis exposed retired claim/lifecycle references in tests.
After conversion it exposed duplicate lint identity `4217c301d9de`: two warnings
at `src/seon/cluster/process.clj:96:1` for redundant declarations of
`matching-process-handle` and `process-start-instant`. Removing the useless declare
unblocks the input; the underlying identity class is filed separately as
[lint-identities-collide-for-multiple-findings-at-one-location](../../../seon/issues/lint-identities-collide-for-multiple-findings-at-one-location.md).

Exact diagnostic duplicate rows (identical identity, differing message):

```clojure
{:seon.lint/id "4217c301d9de"
 :seon.lint/file [:seon.fn.file/relative-path "src/seon/cluster/process.clj"]
 :seon.lint/type :redundant-declare :seon.lint/level :warning
 :seon.lint/message "Redundant declare: matching-process-handle"
 :seon.lint/row 96 :seon.lint/col 1 :seon.schema.admission/source :core}
{:seon.lint/id "4217c301d9de"
 :seon.lint/file [:seon.fn.file/relative-path "src/seon/cluster/process.clj"]
 :seon.lint/type :redundant-declare :seon.lint/level :warning
 :seon.lint/message "Redundant declare: process-start-instant"
 :seon.lint/row 96 :seon.lint/col 1 :seon.schema.admission/source :core}
```

## Size snapshot (2026-09-21, intermediate)

Actual `wc -l`: `bin/seon` 26; `script/seon/operator.clj` 368;
`src/seon/cluster/boot.clj` 437; total 831 against the approximately 900 target.
Boot exceeds its individual 420 target by 17 lines because the installed projection,
coherence, search and acquisition sequence is preserved until its owning cuts.
Final counts, exact path inventory and additions/deletions will follow the drills.

## First completed scratch boot

Publication produced `6ab1b46f-7eba-5cd7-b6d5-8bc9b09eb4f4`. Reloading the
new cluster/boot owners and replacing the partial instance IN THE SAME scratch JVM
produced readiness with `:seon.boot/missing-layers []`, one root agent,
`:seon.boot/ready-ms 9154`, prepl 54258, wanted web port 7994, actual URL
`http://127.0.0.1:54263`. HTTP GET `/` returned 200. This is a moved boot-path proof,
not one of the eight recorded drills and not a default replacement.

The existing long-lived MCP process returned transport failure with `error:null`
for explicit scratch-root JVM and SCI requests. Tool restoration remains pending;
raw operator PREPL remains usable. No successful MCP reconnection is claimed.

## Review and canonical iteration update (2026-09-21)

Read the implementation brief, integrated B1b specification and Fable's review of
`076827cc9` end to end. Fable #2 and #3 are addressed: the operator now reads its
boot allowance from the declared `:seon.config.operator/boot-bound-ms` fact, and
missing-endpoint diagnostics distinguish a live exact-root JVM and name its
identities. #4 preserves the installed three-key recovery refusal check: neither
`seon.error` nor `seon.error.refusal` supplies an error predicate to reuse. #5
conflates two drills: `start-during-reset` (6) already has its winner in the test
JVM; `same-lock-through-reset-boot` (8) intentionally uses a controlled child.
The specification therefore needs no correction on that point.

For #1, the blanket 900000 ms declarations are removed. Ordinary event waits use
the canonical 20 s backstop. The measured three-boot stop drill took approximately
80 s (a live thread sample identified installed Lucene rebuild/fsync), with a
120 s declaration; real cold child plus SIGSTOP/down took approximately 55 s,
with a 120 s declaration. The concurrent-start drill allows 180 s for three cold
process admissions; the replacement-gap drill allows 240 s for two boots and a
cold losing contender. The 9.154 s earlier measurement was repaired in-process
boot, **not** cold JVM plus zero-store publication. Cold-index drills temporarily
retain the previously authorized 600 s publication allowance plus their bounded
child operations (660–720 s total), pending their first complete measurement.
This is an explicitly uncalibrated inherited allowance, not a claimed measurement.

Two canonical `seon.test/run` iterations have recorded positive results:

| Drill | Run | Assertions | Recorded basis / completion |
|---|---|---|---|
| stop-instance (3) | `26284d8b6c6c` | 6 pass, 0 fail, 0 error | 536870988 / 536870990 |
| down-unresponsive (4) | `50588a6cf18d` | 8 pass, 0 fail, 0 error | 536870990 / 536870992 |

Both used program digest
`5758c1601fc571362e39ec2c2c5a77d6a8217c000026c8fbaa25e210af2b3d33`
and input digest
`4ce09a3c2a1dfe6fcfe3ea8a0940aa62b37c0f9fa86f4a43804272c4a8f5376f`.
An earlier stop iteration (`d332313c3d02`) recorded one error because the physical
fixture lacked its required observation explaining why a database branch could
not establish store-lock behavior. The fixture now supplies that observation to
the existing canonical helper; no alternate fixture or schema population was used.
Later helper/bound refinements still require final-source verification.

The freshly loaded BB MCP bridge proved JVM evaluation, explicit cluster database
custody and SCI evaluation (shown `2`, outcome `ok`). The existing long-lived MCP
server still needs its connection reload; fresh bridge success does not establish
that reload. Default replacement and the cut-level platform gate remain pending
and orchestrator-owned.

Concurrent-start (drill 2) passed canonically as run `f0f948403d29`: 12 pass,
0 fail, 0 error, measured 51596 ms, basis 536871003 / completion 536871005.
Program digest `3321673ecf0997960de01f0a8a47bdc5ea38ea785a5436d8f5a3316e33b80db7`;
input digest unchanged from the preceding rows. Exact child identities:
45154 / `2026-09-21T23:21:03.087Z` (winner),
45269 / `2026-09-21T23:21:16.580Z`, and
45296 / `2026-09-21T23:21:36.598Z` (losers), all reaped.
The earlier attempt `0ab03f6db5e8` measured 47610 ms with 11 pass / 1 fail:
the exported fixture predated the newly declared operator boot dial, so the
winner refused its absent database attribute. Re-exporting through the existing
canonical export owner supplied the current schema; no schema hand roster was
introduced. Both attempts positively observed typed foreign store-lock refusal.

Cold-start (drill 1) passed canonically as run `59ec4d92c3c4`: 13 pass,
0 fail, 0 error, measured **144131 ms**, basis 536871009 / completion 536871011.
Program digest `e3ba645753f6520992db75af35342733e7f2d0366877b5d390a1da34432754e8`;
input digest unchanged. Exact child 45377 / `2026-09-21T23:23:52.269Z` was reaped.
This is a complete empty-store child boot plus HTTP 200, explicit database custody,
JVM and SCI evaluations and injected later web failure with surviving REPL.
At 55 s, the child was in `seon.fn/index!`'s canonical transaction; its writer
was validating owning ancestors through `seon.db/write-owned-values-error`.
The measured complete path now replaces the provisional allowance above:
operator boot bound **300000 ms**, cold drill **300000 ms**, destructive
reset/continuity drills **360000 ms** including their extra cold contenders.
The approximate doubling is explicit, not a performance claim about later cuts.

Reset-one-jvm (drill 5) passed as run `47ba19ff0306`: 15 pass, 0 fail,
0 error, **136271 ms**, basis 536871013 / completion 536871015, program digest
`13f0d1bb7ef2776debdea11d4a990c09f9f9f030a39a80b9c2400efc537db7b1`.
Old child45612 / `2026-09-21T23:27:52.416Z` exited before replacement
45644 / `2026-09-21T23:28:13.320Z`; both were reaped. The old named branch
was positively present before reset and absent afterward; lock inode remained,
inside marker vanished, outside symlink sentinel survived. Invalid argv assertions
added afterward remain pending final verification.

The next source checkpoint also adds complete contracts to moved subprocess and
maintenance collection helpers, removes retired claim/census/lock schema rows and
old census-only assertions, and updates the REPL custody owner in AGENTS/skill
examples. Client loading and canonical publication/adoption passed; the new
contracts required named EDN-readable predicates, not anonymous function objects.
Remaining drill outcomes, tooling busy test, moved-helper focused checks and
final per-file counts remain pending.
