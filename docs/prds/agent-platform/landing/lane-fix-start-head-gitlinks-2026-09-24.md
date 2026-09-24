---
type: landing
date: 2026-09-24
lane: fix-start-head-gitlinks
status: landed
---

# Fix: a changed gitlink publishes by its pin during `start --head`

`seon.cluster/refresh-source!` checked containment by canonical relativization,
which follows the archive's gitlink symlinks out of `data/source/<sha>/`. The
check is now `publication-paths`: a declared gitlink keeps its logical path. The
existing pin inventory `seon.test.cache/gitlink-digests` identifies it, and that
inventory is read only when some path escapes. Every other path must still
canonicalize inside the source directory. The same vector feeds the
publication roots, so the containment check and publication see one identity.
Diagnosis: `docs/research/agent-platform/start-head-19d948023-readiness-2026-09-23.md`.

## Broken / not proven

- Default was **not** moved. It still runs archive ce73846828. Root's next
  `bin/seon start --head` is the installed proof on default.
- The scratch moves are slow. This change did not cause that, and it is not
  sub-second. A from-zero boot of the base commit took ready-ms 211,896, and
  the operator gave up at its 300 s bound while the JVM went on to readiness.
  The gitlink move had ready-ms 95,224, adopt-ms 57,859 and total-ms 186,649.
  `seon.cluster/refresh-source!` accounted for 172,515 ms inclusive over 3
  calls. The existing issue
  `docs/seon/issues/a-three-file-changed-path-publication-takes-a-minute.md`
  covers this class. The dependency-class cache missed on both launches.
- After the move, readiness listed 13 `:stale-vars`, including
  `seon.cluster/publication-paths`. That is an observation from the adopted
  scratch root and was not investigated.

## Evidence

The seam probe ran in default's JVM (read-only) on the 19d948023 archive.
`relative-path` gave `../../../reference-code/babashka-process` and
`../../../reference-code/clojure`, while `src/seon/id.clj` stayed relative. Both
gitlinks are keys of `gitlink-digests`, and reading it (from `dependency-pins.txt`)
took 0.92 ms.

The move was proved on the disposable root `tmp/fix-start-head-gitlinks/root-1`,
through `seon.operator/request!` `:start :head?` with `:seon.source/revision`:

1. The root booted on base e45e27d73. Its datahike pin differs from the fix
   commit's, and the store started empty.
2. It then moved to 2614d0353 (the fix, on top of b626390b2's datahike gitlink).
   The move exited 0, reported `:seon.boot/missing-layers []`, adopted commit-id
   `6ab546eb-…` and stopped the old pid 31779. The new pid is 36293.
   Phases: archive-ms 765, classpath-ms 551, share-ms 24, down-ms 12,394,
   launch-ms 114,682, ready-ms 95,224, adopt-ms 57,859, total-ms 186,649.
3. The published row `reference-code/datahike` has digest `1d55c718…0b15`. That
   is `sha-256` of the new pin 620721d6, and it equals `gitlink-digests`.
4. The regression ran as
   `bin/test-check --root tmp/fix-start-head-gitlinks/root-1 default --ns seon.cluster.publication-serialize-test`.
   Result: executed 3, pass 7, fail 0, error 0, 2,862 ms.
   `a-linked-gitlink-publishes-by-its-pin-while-an-escaping-file-refuses`
   checks three things: a linked gitlink keeps its logical path, a symlinked
   ordinary file escaping the root refuses, and a `..` traversal refuses. The
   earlier absolute-outside-path test still passes.
5. The probe was timed in the scratch JVM. Three gitlinks took 0.99 ms (this
   includes the pin read). An ordinary path took 0.10 ms, which is the same work
   as the old check.

## Changes

- `src/seon/cluster.clj`: new `publication-paths` (contracted, private), and
  `refresh-source!` now uses it. +20/−8.
- `test/seon/cluster/publication_serialize_test.clj`: one class regression, +33.
- Commit 2614d0353.

## Follow-up commits (coordinator extension)

**732b34072**: an unreadable `on-core-error` dial panics. The fault committer's
mode reader in `arm-agents!` used to be `(or dial :record)`. It is now
`core-error-mode`, which answers the dial as read. When the dial cannot be read,
it prints the `config/effective` refusal whole
(`SEON CORE-ERROR MODE UNREADABLE (panic): …`) and answers nil, and
`seon.fault/policy` turns nil into `:panic`. Census S1:
`docs/research/agent-platform/ambiguous-nil-producers-2026-09-24.md`.

- Regression:
  `test/seon/cluster/core_error_mode_test.clj`. It checks that the configured
  dial equals `config/effective`, that an unknown cluster answers nil and
  `fault/policy` gives `:panic`, and that the printed refusal names
  `:seon.config/missing-effective`.
- **Not done (outside ownership, `src/seon/flow.clj`):** the census also asks to
  carry the read refusal into the fault fact. The committer step
  (`seon.flow/fault-committer-step`, `mode (read-core-error-mode)` inside its
  `try`) has no slot for it. A reader that threw would lose the fault through
  `report-committer-loss!`, so the refusal is printed rather than thrown. The
  flow owner should give the committer a reader result that can carry a refusal.

**f2e164bdb**: `seon.cluster/read-advertisement` deleted (−30 lines).
`rg read-advertisement` over src/ test/ script/ bin/ resources/ found only its
own definition. In default's database, the only reverse references to its row
are 5 `:seon.test/adoption-identities` (test evidence), with no calls.

**Proof.** The scratch root moved to f2e164bdb: exit 0, `:missing-layers []`,
adopted. Phases: archive-ms 956, down-ms 750, launch-ms 153,340,
ready-ms 129,584, adopt-ms 68,165, total-ms 224,054. That is again far over one
second; see the issue cited above. The adoption notes listed SCI
interpretation failures in foreign test namespaces (entrance-supplier, turn-loop,
mcp), which were not investigated.

- On that root, `bin/test-check … --ns seon.cluster.core-error-mode-test` gave
  pass 6, fail 0, 11,137 ms.
- `--ns seon.cluster.publication-serialize-test` gave pass 7, fail 0, 16,075 ms.
  The same run took 2,862 ms before this move. The runs are over the 5 s default
  bound, and the cause was not investigated.
- After the move, `seon.cluster/read-advertisement` resolves to no Var and no row.
  `core-error-mode` has a row, and for `"default"` it answered `:panic`.

Net: cluster.clj +32/−38 across the three commits, tests +55.
The scratch root was taken down and deleted after these runs.
