---
title: "SR-L: blob references are declared, vector members included"
type: reference
status: ready after led-cacheb
created: 2026-10-08
---

# SR-L: Blob references are declared, vector members included

Ledger row SR-L (group E). Sources: `storage-audit/storage-audit-opus.md` finding 6 and slice D2,
`storage-audit-astra.md` finding 1; existing issue
`docs/seon/issues/blob-roots-are-derived-from-digest-shape-not-from-a-declared-fact.md`. Model: Sonnet.

`seon.cluster.registry/blob-digest-attributes` (`src/seon/cluster/registry.clj:439-477`) decides which attributes
hold blob keys by resolving each schema form to `:seon.blob/digest` through references, `:and` and `:or`. Two errors:

- **Missed (deletion risk):** `:seon.effect/content-blobs` is `[:vector :seon.blob/digest]`
  (`seon.effect.edn:274`). Its cells carry the attribute with one digest each
  (`seon.schema.datahike/encode-entity`, `schema/datahike.clj:663-697`: `{:seon.db.vector/position i attr [element]}`)
  and `seon.effect` also asserts digests on it directly (`effect.clj:330-334`), so its AEVT values are real blob keys
  the collector never marks.
- **False roots:** content digests that never name a stored blob declare `:seon.blob/digest`:
  `:seon.db/read-result-digest` (`seon.db.edn:120`), `:my.fs/digest` (`my.fs.edn:29`, which `my.edit/*-digest` and
  `my.fs/*-digest` reach), `:my.web.body/digest` (`my.web.body.edn:3`), `:my.shell.output/digest`
  (`my.shell.output.edn:3`), `:seon.dev.mcp.artifact/id` (`seon.dev.mcp.artifact.edn:2`). On default 34 of 61 digest
  datoms name nothing stored.

## Files

- `src/seon/cluster/registry.clj`
- `resources/seon/schemas/seon.db.edn`, `my.fs.edn`, `my.web.body.edn`, `my.shell.output.edn`,
  `seon.dev.mcp.artifact.edn`
- `test/seon/cluster/registry_test.clj`

## Step 0 (probe, a disposable branch, before editing)

On a test branch, record one effect with two `:seon.effect/content-blobs` and read
`(d/datoms db :aevt :seon.effect/content-blobs)`: confirm the values are the two digest strings (not cell entity ids).
If they are entity ids, stop and report: the walk must then follow `:seon.db.vector/cells`.

## Schema changes

Each false-root attribute declares a content digest that is not a blob key, the existing non-identity form used in
`seon.source.edn:34`: `[:and {:seon.db/identity false} :seon.source/digest]` (`:seon.source/digest` is
`[:re #:seon.db{:identity true} "^[0-9a-f]{64}$"]`, `seon.source.edn:31-32`; the `:and` drops identity). Keep each
attribute's other properties (`:seon.db/index`, `:seon.db/identity` on `:seon.dev.mcp.artifact/id` stays `true`:
write `[:and {:seon.db/identity true …existing props} :seon.source/digest]` there). `:seon.blob/digest` then means "a
blob key" and nothing else.

## Function changed

`blob-digest-attributes` (private, `[db]`): add one `cond` branch to `digest-schema?` before `:else`:

```clojure
(and (vector? form) (#{:vector :set :sequential} (first form)))
(digest-schema? (last form) seen)
```

(the element schema is the last child; a properties map may sit at index 1). Update the comment above it: a collection
of digests is a root (its cells carry the digests); a map or entity schema containing a digest member still is not.

## Proof

New tests in `test/seon/cluster/registry_test.clj` (read its existing blob tests first and reuse their branch fixture):

- `seon.cluster.registry-test/a-vector-of-blob-digests-is-a-root`: an effect with `:seon.effect/content-blobs` of two
  published blobs; `(registry/referenced-blobs connection #{branch} false)` contains both digests.
- `seon.cluster.registry-test/every-blob-digest-attribute-names-stored-content`: on the test world, for every
  attribute `#'registry/blob-digest-attributes` returns, every current datom's value is `konserve.core/exists?` in the
  store, and none of the six retired attributes is returned.

Existing `seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings` and
`seon.maintenance-test/collection-reports-and-verifies-the-exact-store` stay green.

`--test seon.cluster.registry-test seon.blob-publication-test seon.maintenance-test seon.schema-test`

Schema resources change: `bin/lane commit` publishes and adopts the candidate on disposable branches (CLAUDE.md
"publish and adopt").

## Cost

Expected: unchanged walk (one schema read per branch, SR-G2 makes it once per projection); D: 34 fewer false roots on
default, and the verifier's `unstored-digests` count (`maintenance.clj:374-420`) drops to 0. Deleting that branch is
SR-ENUMa's (it rewrites the verification).

## Commit

`bin/lane commit <lane> -m "SR-L: blob roots are declared: digest vectors are roots, content digests are not blobs" --test seon.cluster.registry-test seon.blob-publication-test seon.maintenance-test seon.schema-test src/seon/cluster/registry.clj resources/seon/schemas/seon.db.edn resources/seon/schemas/my.fs.edn resources/seon/schemas/my.web.body.edn resources/seon/schemas/my.shell.output.edn resources/seon/schemas/seon.dev.mcp.artifact.edn test/seon/cluster/registry_test.clj`

## Dependencies

led-cacheb holds `registry.clj`: claim after it releases. SR-G2 and SR-ENUMa follow.
