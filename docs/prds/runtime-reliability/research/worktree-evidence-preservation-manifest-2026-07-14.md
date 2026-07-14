---
type: research
status: active
tags: [research, archive, database, agent]
---

# Worktree evidence preservation manifest — 2026-07-14

## Scope and safety state

This is the non-destructive inventory required before retiring the old
autocomplete, Inspect, planning, and gym worktrees. It refines the lane
disposition in [[inspect-autocomplete-lane-integration-audit-2026-07-14]] and
the live ownership findings in
[[dependency-shadow-mcp-acme-audit-2026-07-14]]. The active roadmap remains
the implementation-state and sequencing authority.

The inventory read Git metadata, directory sizes, process ownership, and
small evidence files. It did not stop a process, open a database through a
writer, copy or exhaustively hash a database, switch a branch, edit an old
worktree, or remove any path. The protected untracked current-tree file
`docs/prds/repl-autosuggest/research/shared-schema-section-2026-07-13.md` was
not read, hashed, or changed.

**This manifest is not cleanup authorization.** Stable and display-v3 still
own live legacy ACME processes, no database has yet been archived and read
back, and the content-addressed archive root has not been selected.

Snapshot time was 2026-07-14 in `America/New_York`. Sizes are filesystem
allocation from `du -sk`, so they are suitable for capacity planning but are
not content identities.

## Registered worktrees

| Worktree | Branch or state | HEAD | Tracked state | Untracked state | Initial disposition |
|---|---|---|---|---|---|
| `/Users/sean/src/seon` | `codex/runtime-reliability-refactor` | `9843e318` | Clean at snapshot | One protected research file | Keep; current integration checkout. Its offline legacy-layout ACME cluster requires a separate archive before current ACME may start. |
| `/Users/sean/src/seon-display-v3` | `repl-autosuggest/display-v3` | `b7be18be` | 83 paths: generated bundle, `shadow-cljs.edn`, and 81 reference-link type changes | `node_modules`, `src-needle/.venv`, `src-needle/checkpoints` | Preserve database and tune exports; reject the four unique display commits as implementation imports. |
| `/Users/sean/src/seon-fn-surface` | `repl-autosuggest/fn-surface-pin` | `17a82314` | Generated ACME bundle | `node_modules` | Reproducibly discard generated state after confirming committed lint evidence. |
| `/Users/sean/src/seon-pin` | Detached | `93c8d8ad` | `shadow-cljs.edn` | `test/seon/needle_lora_audit_test.cljs` | Preserve audit fixture and historical default cluster until the current Inspect replacement proves equivalent evidence. |
| `/Users/sean/src/seon-plan-fix` | Detached | `7c08240e` | Clean | `node_modules` | Reproducibly discard after ordinary ignored-file check. |
| `/Users/sean/src/seon-plan-pilot` | Detached | `299b37f7` | Generated ACME bundle | `node_modules` | Preserve the ACME training-seed database with a read-only staging proof. |
| `/Users/sean/src/seon-stable` | `repl-autosuggest/stable` | `609c4006` | Five paths: ACME source/config, fair report/scorer, generated bundle | Four paths: `acme/CLAUDE.md`, continuation design/probe, shared-schema note | Preserve fair/continuation evidence and live database; do not import dirty ACME state. |
| `/Users/sean/src/seon-toolkit-gaps` | `repl-autosuggest/toolkit-gaps-pin` | `299b37f7` | Generated ACME bundle | `node_modules` | No unique database evidence identified; verify duplicate/reproducible status before discard. |
| `/Users/sean/src/seon/.claude/worktrees/gym-metric-validation` | `gym-metric-validation` | `684445a2` | Staged paid-test rename plus eight changed retired-gym scenarios | None | Preserve one patch artifact as migration history; never restore the deleted harness. |

The tracked binary-patch SHA-256 values, useful for detecting later mutation
before archival, are:

| Worktree | `git diff HEAD --binary` SHA-256 |
|---|---|
| display-v3 | `39ba4266b60650db9822633446c03131afab5eca5d2bf55bfb4005286c3090f2` |
| fn-surface | `08a6d6f14f7144bf188e0aef67cd94f7eef97cad43cacd700653c119697d8327` |
| pin | `6fea740433dac5b2de989f24002cb0c33d06f510030951a293816d0b16b94cd6` |
| plan-pilot | `81654dac27212eeec06d12dc3f83a60e18459466e43b670d7a2e01bb859c8e37` |
| stable | `d6ce2d1a6db37deb856ad52891ba2e23540c9dada806cef28ae91d268fd920ff` |
| toolkit-gaps | `0b24df80baf56f59e50ad06c22b5e232c1309f9e42b37fb5c4fd73802fa86a7d` |
| gym validation | `a2402005ddb93045f53d76c9f65f1ae0c2d932543f8978566db155aa60005168` |

Plan-fix has no tracked diff, so the SHA-256 of its empty patch is omitted.

## Live process and port ownership

Four legacy ACME processes remain live. All have parent PID 1 and use the old
checkout-relative launch path, so no current operator owns their lifecycle.

| Checkout | Role | PID | Started | Port | Observed working directory and command |
|---|---|---:|---|---:|---|
| stable | Node pod | 31038 | 2026-07-13 14:51:49 | 7980 | `/Users/sean/src/seon-stable`; `node out-acme/client/main.js` |
| stable | JVM writer | 30873 | 2026-07-13 14:51:44 | 7981 | `/Users/sean/src/seon-stable`; old `seon.server.boot`, database name `acme`, path `data/clusters/acme/store` |
| display-v3 | Node pod | 52189 | 2026-07-12 21:56:04 | 7982 | `/Users/sean/src/seon-display-v3`; `node out-acme/client/main.js` |
| display-v3 | JVM writer | 45003 | 2026-07-12 21:52:55 | 7983 | `/Users/sean/src/seon-display-v3`; old `seon.server.boot`, database name `acme`, path `data/clusters/acme/store` |

The process working directories were verified with `lsof`, and the four ports
were verified as listening sockets. No process command or working directory
matched fn-surface, pin, plan-fix, plan-pilot, toolkit-gaps, or gym validation.
Absence from this snapshot must be checked again immediately before removal.

The live writers use GraalVM Java 25, old Datahike/Konserve revisions, the
retired server boot, and the old `store` layout. They must not be adopted by
starting a current writer against the same path.

## Database and blob inventory

| Worktree and logical cluster | Database path and size | Blob path and size | Ownership and required disposition |
|---|---|---|---|
| current checkout `acme` | `data/clusters/acme/store`, 101,348 KiB | `data/clusters/acme/blobs`, 112,112 KiB | No live owner; current operator rejects the legacy layout. Unique store plus 38 blobs absent from display-v3. Archive and read back separately before migration or removal. |
| display-v3 `acme` | `data/clusters/acme/store`, 4,324,736 KiB | `data/clusters/acme/blobs`, 110,572 KiB | Live writer PID 45003. Content-addressed archive plus restore/read-back is mandatory. |
| fn-surface `acme` | `data/clusters/acme/store`, 15,576 KiB | None | No live owner observed. Compare against another cluster or classify as reproducible before discard. |
| fn-surface `default` | No `store` directory | `data/clusters/default/blobs`, 2,188 KiB | Blob-only generated residue; discard only after proving no referenced unique blob. |
| pin `default` | No `store` directory | `data/clusters/default/blobs`, 92,288 KiB | Historical LoRA audit evidence; preserve until replacement evidence exists. |
| plan-pilot `acme` | `data/clusters/acme/store`, 372,328 KiB | `data/clusters/acme/blobs`, 9,664 KiB | Named training-legal seed by the old runbook. Archive and prove read-only staging before removal. |
| stable `acme` | `data/clusters/acme/store`, 31,188 KiB | `data/clusters/acme/blobs`, 13,672 KiB | Live writer PID 30873. Archive and read back after coordinated quiescence. |
| stable `default` | `data/clusters/default/store`, 104 KiB | None | Small historical database; classify after read-only identity check. |
| toolkit-gaps `acme` | `data/clusters/acme/store`, 42,616 KiB | `data/clusters/acme/blobs`, 852 KiB | No unique evidence identified; duplicate needs a hash or database-level comparison. |
| toolkit-gaps `default` | No `store` directory | `data/clusters/default/blobs`, 2,188 KiB | Same allocated size as fn-surface, but size is not proof of duplication. |

Plan-fix and gym validation have no local cluster directory. The logical names
above come from their paths; the two live ACME identities also come from the
writer command lines. Basis transaction, schema identity, and root hash were
not inferred from raw Konserve files. Capturing them safely requires either
the owning live writer's read-only API before shutdown or a read-only reopen of
a copied archive with the matching historical dependency basis.

The inventory deliberately does not publish a whole-database SHA-256. Hashing
every byte of a live multi-gigabyte mutable database would not provide a
consistent snapshot and would impose avoidable I/O. The archive step below
must quiesce its owner, copy the complete cluster atomically, then hash the
closed archive.

The current-checkout legacy cluster is already closed and small enough for a
read-only payload identity. Its 8,697-file relative-path/content manifest is
`108fb08a0bfa5620736f7c083c3ada0e2928f7dba58bc9c4be6f458a6f2436eb`.
It shares 1,966 byte-identical blobs with display-v3 but retains 38 additional
blobs and an independent 6,693-file store, so it is not a duplicate. Exact
evidence and historical dependency locks are in
[[legacy-acme-archive-readback-runbook-2026-07-14]].

## Small unique evidence hashes

### Stable scorer, probe, and authority drift

| Artifact | SHA-256 | Meaning and disposition |
|---|---|---|
| `docs/prds/repl-autosuggest/research/continuation-drive-design-2026-07-13.md` | `9af4bb2809be1041dfeb17f24668a45dfc3fac07ffe5ddd826f9b70c6dc06151` | Decoding-shape design and measurements; preserve, do not treat as accuracy evidence. |
| `src-needle/scripts/cont_probe.py` | `9df829f34331f066cec8cb3a35d3981c4235043cab4f248240cf1240a5eb9231` | Probe/scanner specification; preserve for Inspect reconstruction, not direct import. |
| `src-needle/scripts/fair_score.py` | `8eba9f6c505f4c06bd3c640bed5b05d2ab2bb1a6be70239426a40f59c484c45d` | Fair-scoring specification bound to retired fixtures; preserve, rebuild through Inspect. |
| `docs/prds/repl-autosuggest/research/fair-scoring-2026-07-12.md` | `568d7c7f19c8629515172bcea2ce36557bc073a0636e1d0b7c09953568e7befb` | Completed scoring evidence. |
| `docs/prds/repl-autosuggest/research/shared-schema-section-2026-07-13.md` | `130de33e8d4fdd062fc30033aeefbb3ad24aa8915d1e4e5506065dd86ffaa751` | Stable-worktree copy only; distinct from the protected current-tree file, which was not inspected. |
| `acme/CLAUDE.md` | `ac98ad7de2561428995938943cc3af4975965a052fee151de618502a543698e6` | Untracked instruction drift; reconcile content into the proper authority before archival, never restore as a regular `CLAUDE.md`. |
| pin `test/seon/needle_lora_audit_test.cljs` | `94b2ded83b48f37e5e4399a898ab0af9c6aca8aeed5b087a5a8021ca3880db43` | Historical staged-world audit fixture. |

The continuation probe was reproduced historically with:

```bash
cd src-needle
.venv/bin/python scripts/cont_probe.py selftest
.venv/bin/python scripts/cont_probe.py run \
  --model Qwen/Qwen3.5-2B-Base --tag q2b --arm cont-few \
  --limit 16 --stop both

```

The self-test proves scanner behavior and historical bundle extraction. It
does not prove autocomplete accuracy or make the old serving path current.

### Stable continuation raw outputs

The 14 files under `src-needle/data/kt3redux/cont_probe/` total 382,447 bytes.

| File | Bytes | SHA-256 |
|---|---:|---|
| `examples-q08b-cont-few-bracket.md` | 7,939 | `e9a9f87901938168c9a8bba4c74023ee448cdebfce89e29522698f3adb87fc55` |
| `examples-q08b-cont-few-form.md` | 7,763 | `1e102c6cbe9d5b350618e3fc6d360ca68e18ee64abb9b8f348ddb300954a5bcf` |
| `examples-q2b-cont-few-bracket.md` | 29,030 | `e0c16852c5d1bd6a7de1e9bf76ce95ca7cab10fe81eb29b8162a9626ac854adb` |
| `examples-q2b-cont-few-form.md` | 13,766 | `016678d77e9f2d2da2a138662f53232a50e976e0634e580bde3e227f4b6d335f` |
| `examples-q2b16-cont-few-bracket.md` | 29,031 | `f1bee560cef065563a66225b62899e8c087bbf8e09721b012298898c02b145ad` |
| `examples-q2b16-cont-few-form.md` | 13,767 | `7ff05d16812b3d79e0aeeda760d4b139d3163e4db7e517265707d0822fa3b469` |
| `examples-q2brep-cont-few-form.md` | 13,730 | `a5b626dfb97d2cae3920d97cc466d7d2c2d528511db4ef63a5215fabd1bd515d` |
| `preds-q08b-cont-few-bracket.jsonl` | 8,842 | `ec02651265abb60eee3f7e734e9043fa1c936cc2ff3c6d2754d1403bee6265a1` |
| `preds-q08b-cont-few-form.jsonl` | 8,484 | `326f7a860fe1fa60bc6e3fb5d7d5d3dfadcc2745e6ffa29443035d79a971e906` |
| `preds-q2b-cont-few-bracket.jsonl` | 48,605 | `d7b0c046920e59f49a38f8c3dc6079cbfc213a56b28c5bd51a0508abf66a19de` |
| `preds-q2b-cont-few-form.jsonl` | 20,442 | `b755926f934e2b85af6d5726b2530fe7a10344274c1d1941695500fe9bff7f41` |
| `preds-q2b16-cont-few-bracket.jsonl` | 115,137 | `1685291956967c3b806a0e7245cff288588f3bd543bd3c1ee70592dc8d029d12` |
| `preds-q2b16-cont-few-form.jsonl` | 36,254 | `8060cb47b97c2e3f616de7bfb890d9ce58160fefeddecb0b32a74e52ab868d97` |
| `preds-q2brep-cont-few-form.jsonl` | 29,657 | `b1eed99394640219de170cdb44b4013b5227438fc21180e41c63b36c8ce89794` |

### Display-v3 tune/export artifacts

These seven ignored files total 6,085,001 bytes by logical size. The filesystem
directory occupies 5,956 KiB. Preserve each file by hash; do not import the
v3 builder or its obsolete card grammar.

| File | Bytes | SHA-256 |
|---|---:|---|
| `acme-2026-07-12-v2.jsonl` | 1,091,463 | `64b4fbe7c5690246cb793d7a775f3ed89d215e6d3b5a8acc50dbb96895268d8e` |
| `acme-2026-07-12-v2.meta.json` | 1,915 | `f41f4490c79639eba0e73adea47166d3792fc2a6e75f2c86e3aa506013315227` |
| `acme-2026-07-12-v3-raw.jsonl` | 891,883 | `a4b0351769df61fb3bb5b5fc3f2021403206bbdee23bfc2881d605a1a9239cd1` |
| `acme-2026-07-12-v3.jsonl` | 1,083,803 | `41595a0e688abe1f01d5b4c6d9278a5f3aa3d832d0744a63e0728c91b1bdef55` |
| `acme-2026-07-12-v3.meta.json` | 1,720 | `7c76a9c79027614be37af4969119c578a1d7e84ac33523b3ebb16e19e81189ac` |
| `acme-2026-07-12.jsonl` | 795,296 | `debdfe0d918f19ffcf3fcdbec96d567b9ca377203c55b467242bc61c2e529397` |
| `drafts-deepseek-2026-07-12.jsonl` | 2,218,921 | `8edf0e1d1782230018862be85aa55444ef37796d267b5962d63cee7bab610250` |

The v2 metadata names `build_v2.py` plus `split_forms.clj` as producer. The v3
metadata names `build_v3.py` plus the same splitter, records 211 rows, locks
seven held-out turn IDs, and binds the v3 output to the raw source hash above.
Those metadata files are part of the evidence and must travel with the JSONL.

## Content-addressed archive and read-back protocol

The live identities, exact historical dependency locks, capacity analysis, and
executable maintenance sequence are now recorded in
[[legacy-acme-archive-readback-runbook-2026-07-14]]. Read-only capture found:

- stable basis `536870984`, 202 schema attributes, schema SHA-256
  `0d98d8b1f1246b36703b3edd5b450a1470a153573a16352ee42882c7521b3199`,
  six blob projections, and 302 blob files; and
- display-v3 basis `536877667`, 269 schema attributes, schema SHA-256
  `aceac1c468682aa1c1428e2eb28a8b5ed7f1d8547c47d18d8eeeeb06cec18ec6`,
  533 blob projections, 1,966 blob files, and 29 installed
  autocomplete/typeahead attributes.

These are live checkpoints, not final archive identities. The final values must
be recaptured after the owning pod exits and before its writer exits.

The archive owner must choose a durable `ARCHIVE_ROOT` outside every worktree.
The following is the required protocol, not a command authorization for this
inventory unit:

1. Re-run `git worktree list --porcelain`, Git status including ignored and
   untracked paths, `lsof` working-directory ownership, and listening-port
   ownership. Diff the results against this manifest.
2. Coordinate a maintenance window for stable and display-v3. Stop one owning
   pod and confirm its HTTP port is closed. With that lane's writer still live
   and no remaining producer, record the final read-only database identity:
   logical database name, basis transaction, schema identity, latest
   transaction, and referenced blob count. Then stop that writer. Repeat for
   the other lane. Confirm every endpoint is closed and no process has a file
   open under either cluster path. Capturing identity before stopping the pod
   is only a pre-quiesce checkpoint because the pod can still race a write.
3. Copy each complete cluster directory and each small evidence set to a
   staging directory on the archive filesystem. Preserve relative paths,
   permissions, mtimes, producing checkout HEAD, Git status, dependency SHAs,
   and the manifest itself. Do not dereference external links into an
   accidental unbounded archive.
4. Produce a sorted SHA-256 manifest over the closed staged copy. Package it,
   hash the package, and promote it under
   `${ARCHIVE_ROOT}/sha256/<package-sha256>/`. Record byte count and file count.
5. Verify `shasum -a 256 -c` against the extracted package in a second empty
   directory. Compare recursive file count and allocated/logical byte counts.
6. With networking disabled and a temporary path outside all worktrees, open
   a copy read-only using the exact historical Datahike/Konserve revisions.
   Assert the recorded basis/schema/latest transaction and representative
   agent, eval, plan, and autocomplete facts. For plan-pilot, execute one
   current, read-only staging/export adapter against the copy and verify it
   writes only outside the archive.
7. Verify every small-artifact digest in this document from the extracted
   archive. Re-run the continuation scanner self-test from the preserved
   environment only if its dependencies are also recorded; otherwise preserve
   the raw outputs and report the environment as non-reproducible.
8. Add archive URI, package digest, restore command, read-back evidence, and
   owner acceptance to this manifest and
   [[docs/seon/issues/autocomplete-worktree-evidence-preservation]]. Only then
   may a worktree enter cleanup review.

## Safe cleanup order

1. Finish and live-prove the current default cluster.
2. Implement current flavor-aware ACME artifacts and the operator lease/state
   boundary; migrate Inspect and ACME callers before replacing any live lane.
3. Archive and read back stable and display-v3, then obtain explicit owner
   acceptance for stopping their legacy processes.
4. Archive and read back the current checkout's offline legacy ACME package;
   only then migrate or remove that conflicting `store/` layout.
5. Archive and stage-read plan-pilot. Preserve pin until Inspect reproduces
   its staged audit evidence.
6. Preserve the stable scorer/probe/report/raw outputs, display-v3 tune files,
   stable instruction drift, pin audit fixture, and gym binary patch.
7. Prove fn-surface, plan-fix, and toolkit-gaps generated/database state is
   duplicate or reproducible. Equal directory size is insufficient.
8. Re-run process, port, Git, ignored-file, and checksum checks immediately
   before removal. Remove accepted worktrees one at a time, never with a shared
   reset or discard command.
9. Verify retained archive checksums after each removal. Remove worktrees
   before considering branch deletion.
10. Delete only a branch whose commits are integrated, superseded, or explicitly
   rejected and whose unique dirty/ignored evidence has a verified archive.

## Remaining blockers

- No owner-approved durable archive root, package digest, restore proof, or
  owner acceptance exists. The internal APFS volume has ample staging capacity
  (about 1.0 TiB available), but same-volume staging is not a durable backup.
- Stable and display-v3 remain live and mutable.
- Live basis/schema/latest-transaction checkpoints are captured, but final
  post-pod-quiescence identities are not.
- The current checkout's offline legacy ACME cluster is uniquely identified at
  the byte level but lacks a content-addressed package and disposable-copy
  basis/schema/read-back proof. Its legacy layout blocks current ACME startup.
- Plan-pilot lacks the required current read-only staging proof.
- Pin has not been superseded by equivalent current Inspect evidence.
- Fn-surface, toolkit-gaps, and their same-sized blob residues lack a real
  duplicate/reproducible proof.
- The complete stable fair-scoring directory is larger than the specifically
  hashed scorer/report/continuation set. The archive owner must preserve its
  raw manifests, harness outputs, scored outputs, summaries, and hanger record
  together or explicitly classify each reproducible input before cleanup.
