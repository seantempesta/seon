---
title: "SR-ENUMa: One store enumeration per collection"
type: reference
status: ready
created: 2026-10-08
---

# SR-ENUMa: One store enumeration per collection

Ledger row SR-ENUMa (group E). Task `seon.maintenance/collection-marks-slowly-and-records-nothing`. Source:
`storage-audit/storage-audit-astra.md` finding 4, `storage-audit-opus.md` finding 10. Model: Sonnet. Host-bound
(store collection and a stored result schema): throwaway check.

## What exists (verified at `1fcdbf47a`)

One successful `seon.maintenance/collect-store!` (`src/seon/maintenance.clj:522-604`) lists every konserve key
eight times (26.75 s each on default's 166,693 keys):

1. `collection-observation` before (`:528`; `count (k/keys …)` at `:313`)
2. `konserve-key-set` for `held-before` (`:532`; `:368-372`)
3. the reachable extension's `(count (k/keys gc-store …))` in `seon.cluster.registry/collect!`
   (`src/seon/cluster/registry.clj:581`)
4. Datahike's `gc-storage` sweep (konserve's own enumeration)
5. `collection-observation` after the first sweep (`:554`)
6. and 7. the second, "verification" `registry/collect!` (`:569`): its extension count and its sweep
8. `collection-observation` after (`:570`)

`dry-run-store!` (`:474-520`) adds a `konserve-key-set` (`:491`) to the extension count.

`held-before` exists because the referenced-digest set contained content digests that were never konserve keys
(`root-verification`'s docstring, `:374-422`); SR-L makes every `:seon.blob/digest` attribute a real blob reference
and deletes the unstored-digest branch, so after SR-L every referenced digest must exist.

## Change

- `collect-store!`: one `registry/collect!` (the first); delete the second sweep, `:seon.operator.collect/verification-pass-swept`,
  the three `collection-observation` calls and `held-before`. Bytes stay (`fs/tree-bytes`, a directory walk, not a
  konserve enumeration): `collection-observation` becomes `collection-bytes` returning only
  `:seon.operator.collect/bytes`.
- `root-verification` `[operation-store evidence]`, contract
  `[:=> [:cat :seon.store/store [:map [:seon.operator.collect/branches :seon.operator.collect/branches] [:seon.operator.collect/digests [:set :seon.blob/digest]]]] [:map [:seon.operator.collect/roots-verified? :boolean] [:seon.operator.collect/unverified-branch {:optional true} :seon.store/branch] [:seon.operator.collect/unverified-digest {:optional true} :seon.blob/digest]]]`:
  every referenced digest is checked with `(k/exists? konserve digest {:sync? true})`; `:unstored-digests` goes (SR-L
  deleted its meaning). Keep `commit-reopens?` per branch.
- `seon.cluster.registry/collect!`: delete the `key-count` atom and its `count (k/keys …)`; the inventory loses
  `:seon.cluster.registry/key-count`.
- `dry-run-store!`: no `konserve-key-set`; `root-verification` without `held-before`.
- Object counts (`objects-before`, `objects-after`, `swept-objects` from the sweep) are absent until SR-ENUMb returns
  them from konserve's sweep: make `:seon.operator.collect/objects-before`/`objects-after` optional in
  `resources/seon/schemas/seon.operator.collect.edn` and `seon.maintenance.result.edn` (absence is no key), keep
  `swept-objects` (the candidate count). Delete `verification-pass-swept` and `unstored-digests` from both schemas and
  from `seon.maintenance.edn` if declared there.

## Files

- `src/seon/maintenance.clj`
- `src/seon/cluster/registry.clj`
- `resources/seon/schemas/seon.operator.collect.edn`, `resources/seon/schemas/seon.maintenance.result.edn`,
  `resources/seon/schemas/seon.maintenance.edn`
- `test/seon/maintenance_test.clj`, `test/seon/maintenance_schema_test.clj`, `test/seon/cluster/program_change_test.clj`
  (each reads a deleted key: `rg "verification-pass-swept|unstored-digests|objects-before|key-count" test`)

## Proof

- New `seon.maintenance-test/a-collection-enumerates-the-store-once`: on a fixture store with 10,000 konserve keys
  (blobs written through `seon.blob`), time one bare `(count (k/keys store {:sync? true}))` and one
  `seon.maintenance/collect!`; assert the collection takes at most `2×` the bare enumeration plus 200 ms, and its
  result has no `verification-pass-swept`. (The ledger's counting konserve wrapper needs a store-construction seam
  Seon does not have: `seon.cluster.store/open-store!` builds the konserve store from config; if the lane finds an
  injectable `:store` value there, count `-keys` calls instead and assert exactly one.)
- Existing green: `seon.maintenance-test/collection-reports-and-verifies-the-exact-store`,
  `…/a-branch-that-commits-after-its-root-was-recorded-still-verifies`,
  `seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings`,
  `seon.cluster.registry-test`.
- Publish and adopt the schema change on a branch of the live store (the throwaway check does this).

`--test seon.maintenance-test --test seon.maintenance-schema-test --test seon.blob-publication-test --test seon.cluster.registry-test --test seon.cluster.program-change-test`

## Cost

Expected O(store keys) once (the sweep) plus O(branches) reopen plus O(declared references) `exists?`. Measured parent:
about eight enumerations, 26.75 s each on default (storage audit). On default this is about 3.5 minutes less per
collection; report the fixture timings on parent and change.

## Commit

`bin/lane commit <lane> -m "SR-ENUMa: one store enumeration per collection; roots verify by existence" --test seon.maintenance-test --test seon.maintenance-schema-test --test seon.blob-publication-test --test seon.cluster.registry-test --test seon.cluster.program-change-test src/seon/maintenance.clj src/seon/cluster/registry.clj resources/seon/schemas/seon.operator.collect.edn resources/seon/schemas/seon.maintenance.result.edn resources/seon/schemas/seon.maintenance.edn test/seon/maintenance_test.clj test/seon/maintenance_schema_test.clj test/seon/cluster/program_change_test.clj`

## Deleted

The second sweep, five key enumerations, `konserve-key-set`, `held-before`, the extension's key count, two result
keys. About −60 lines.

## Dependencies

SR-L (blob references declared; deletes the unstored branch), SR-S (`maintenance.clj`), UL-8b (`registry.clj`, three
lines). SR-ENUMb (konserve sweep counts) after this.
