---
type: landing
status: landed; proven 9f39ae83d -> fix on a scratch root; default not adopted
created: 2026-09-23
---

# Lane citation-deletion (2026-09-23)

## Defect

`default` could not adopt HEAD 3e48425b1 on its existing store. Readiness refused with
"Program indexing transaction was refused. seon.db/transact! refused transaction data at
[40555 :seon.issue.citation/file]: expected the required key :seon.issue.citation/file"
(evidence: `tmp/orchestrator/move/move3-2114.log`, frame `seon.fn/index!` fn.clj:3583,
phase `:seon.fn/population`).

Cause: commit 8bc917872 deleted `bin/test-fast` and `bin/_test-slot`. The program index
(`seon.fn/reconcile-tx-in`) `retractEntity`s a removed file's identity; Datahike's
retractEntity also retracts the refs pointing at it, so each citation component
(`:seon.issue.citation/citation`, whose `:seon.issue.citation/file` is required) was left
with only its id and the whole-entity validator refused the transaction. On default's
store 27 citation components referenced the two deleted files; no other ref attribute
referenced them (probe over every `:db.type/ref` attribute). A from-zero boot never has
the stale components, which is why only incremental adoption saw it.

## Fix (src/seon/fn.clj, +30 / -9)

1. `reconcile-tx-in`: the citation components whose `:seon.issue.citation/file` names a
   removed identity join `removed`, so they are `retractEntity`d in the removal's own
   transaction ("surviving references to a removed declaration require repair in the same
   transaction"). Cost: one AVET seek per removed identity plus one per citation of it;
   zero work when nothing is removed. The note text is untouched; the next issue index
   finds the token uncited (it no longer resolves) and writes nothing for it.
2. `file-identities`: the Datalog join on a variable file attribute was replaced by index
   seeks (new private `seek`, which throws a refused read with its whole refusal). Same
   result set, measured on the scratch store: one 55-declaration file 990 ms -> 15 ms;
   60 files `:seon.fn/file` 893 ms -> 118 ms, `:seon.lint/file` 836 ms -> 14 ms. Without
   it the regression's two changed-path index calls exceeded the 5 s test bound.

The ref stays required; no note was rewritten.

## Regression (test/seon/issue_test.clj)

`seon.issue-test/a-deleted-cited-file-leaves-uncited-text-in-one-incremental-index`: index
a file fact, index a note citing it, index the file's deletion incrementally
(`:seon.source/previous-database`, the path changed and absent from the digests); the
deletion commits, the file and the citation are gone, and re-indexing the note refuses
nothing and keeps the issue.

- RED on the parent program: scratch root at 9f39ae83d, test body run under an isolated
  acquired context: `{:error 1 :pass 3}`, the exact production refusal
  "Program indexing transaction was refused ... [41733 :seon.issue.citation/file]".
- GREEN on the fix: `bin/test-check --root <scratch> default --test ...` run 1622f7956e35,
  pass 9 fail 0 error 0 (within the 5 s bound); rerun 7bd658d1c17f reused.
- `seon.cluster.publication-inputs-test` (callers of `file-identities` through
  `seon.fn/index!`): run 1e439ffd6bb3, 11 executed, 45 pass, 0 fail; one long member
  excluded by declaration.

## Live proof: 9f39ae83d -> fix, no worktree

Proof revision 658f6777b = HEAD 2ff62cc0c plus this diff, built with `git commit-tree`
from a temporary index (no branch, no worktree); the operator archived it.

1. `seon.operator/request!` nuke --force with `:seon.source/revision "9f39ae83d"` on
   `tmp/citation-deletion/root`: ready, `:missing-layers []`.
2. `start --head` with `:seon.source/revision "658f6777b..."` (`move-to-head!`): exit 0,
   readiness `:missing-layers []`, adopted. After: `bin/test-fast` and `bin/_test-slot`
   have no file entity, 0 citation components lack their file, 909 citations remain.
   (An earlier proof revision 32b8c6bfe, holding fix 1 only, also moved 9f39ae83d to ready.)
3. Contracts of `seek`, `file-identities` and `reconcile-tx-in` compile against the
   scratch store's carried projection (schemas used: `:seon.db/database-value`,
   `:seon.schema/value`, `:seon.db/datoms`, all already registered).

The scratch root was stopped (`bin/seon --root ... down`) and deleted. Logs:
`tmp/citation-deletion/{nuke,nuke2,move,move2}.log`.

## Hot path: unchanged adoption

`seon.cluster/refresh-source!` of unchanged `src/seon/issue.clj` on the scratch root,
3 runs: proof revision 1 (fix 1 only, ungated query) 487 / 393 ms; final 419 / 334 / 330 ms.
The unchanged path never reaches `seon.fn/index!` (0 calls in the profile). The removal
query is skipped when nothing is removed: ungated, it cost 11.9 ms on an empty input.

## TIMINGS

| Operation | Wall ms | Justification / status |
|---|---|---|
| scratch nuke at 9f39ae83d (1st) | 130 420 (launch 120 008, ready 86 933, source 9 723) | From-zero boot of the whole program, which the spec required. Over 10 s: a DEFECT already filed in `docs/seon/issues/from-zero-boot-takes-minutes.md` (lanes do not append to shared notes; the orchestrator folds this row in). |
| scratch nuke at 9f39ae83d (2nd, for the final diff) | 120 990 (launch 110 627, ready 78 709) | Same defect as the row above. |
| move 9f39 -> 32b8c6bfe | 152 284 (launch 106 375, ready 94 412, adopt 40 989) | Resume plus adoption of 76 commits of program difference. Over 10 s: a DEFECT; see `a-three-file-changed-path-publication-takes-a-minute.md`. The adopt profile puts 70 s in `seon.schema/call-with-projection` x578 and 21 s in `seon.fn/index!` x2. |
| move 9f39 -> 658f6777b | 156 279 (launch 110 095, ready 95 878, adopt 39 221, archive 1 704, classpath 1 360, source 3 242, down 3 042) | Same defect as the row above. |
| red run of the regression on 9f39 | 10 006 | Parent `file-identities` (4.3 s for 10 calls) plus cold arming. The fix removes most of this cost. |
| test-check, regression, first run | 20 701 request (body under the 5 s bound) | The request's own overhead on a fresh scratch JVM (selection and first acquisition), not the body. Over 10 s: a DEFECT belonging to the test runner, not to this slice; not investigated here. |
| test-check seon.issue-test | 53 374 / 39 445 | 10 and 7 members executed. Over 10 s: same runner defect as the row above. |
| test-check publication-inputs | 16 260 | 11 members, each with an incremental index. Over 10 s: same runner defect. |
| file-identities, 60 files (old / new) | 893 / 118 | New cost is proportional to the named files' declarations. |
| unchanged refresh-source! | 330-419 | Sub-second. |

## Reds outside this slice (seon.issue-test on the scratch, final revision)

Seven members were red, and none of them touches the changed code:
`a-token-naming-two-identities-is-reported-never-guessed` (the fixture wrote the symbol
`seon.db/*conn*` as `:seon.issue/id`); `cited-identities-resolve-through-one-derived-resolver`
(it expects notes that no longer exist at HEAD, e.g. `complete-publication-takes-seventy-seconds`,
and git-derived opened dates, while the archive has no `.git`);
`detector-only-issue-starts-and-settles-from-its-subject`,
`indexed-issues-replace-facts-and-retract-removed-notes`, `issue-worker-creation-is-atomic`,
`issue-worker-opening-links-its-issue`, `unchanged-issue-adoption-writes-no-issue-datoms`.
They use `seon.issue/index!` and the fixtures only; this diff changes `seon.fn` program
indexing. A parent-program run was not made, because default is read-only for this lane.
Route them with the three questions.

## Verification limits

- `default` was not touched (read-only probes only). RESET NEEDED: no. Default adopts this
  fix at the orchestrator's next move to HEAD.
- The red was shown on 9f39ae83d, whose `fn.clj` differs from HEAD's only outside
  `reconcile-tx-in`/`file-identities`. HEAD itself cannot boot on an existing store; that
  is this defect.
