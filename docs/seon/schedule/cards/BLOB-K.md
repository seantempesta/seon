# BLOB-K Blobs are written compressed and immutable

New card (lane blob-konserve's Seon side). Konserve fork commit `e4334756413917305968333bf305e658c177765d`
("bassoc: take metadata and compress binary values through the store compressor") adds
`(k/bassoc store key val meta opts)` with `{:compress? true}` (`reference-code/konserve/src/konserve/core.cljc:673-702`);
`{:immutable? true}` metadata lets the filestore defer the sync of a new content-addressed key
(`reference-code/konserve/src/konserve/impl/defaults.cljc:72-77`, opt-in, fork commit `08465d6`). Lane: Sonnet.

## State of the tree (2026-10-08)
The Seon side is already written but UNCOMMITTED in the shared tree: `src/seon/blob.clj` (diff: `compression-dials`,
`compression`, `compress?`, `publish-staged!`'s new arity) and the untracked
`resources/seon/schemas/seon.config.blob.edn`; `reference-code/konserve` points at `e4334756` (working-tree
submodule change); `deps.edn` still pins `8700ca7a…`. `src/seon/blob.clj` and `test/seon/blob_test.clj` are claimed
by `led-storage`, `deps.edn` by `dials-16gb`. This card finishes and lands that work; it waits for both releases or
root assigns the files.

## Files
`deps.edn`, `reference-code/konserve` (submodule pointer), `src/seon/blob.clj`,
`resources/seon/schemas/seon.config.blob.edn`, `config/default.edn` (only if dials need shipped values there),
`test/seon/blob_test.clj`.

## Functions
- `deps.edn`: `org.replikativ/konserve` `:git/sha "e4334756413917305968333bf305e658c177765d"` (`deps.edn:37-39`).
  The fork commit must be pushed to `github.com/seantempesta/konserve` first (root pushes personal forks).
- SCHEMA `seon.config.blob.edn` (as in the tree): `:seon.config.blob/compress?` boolean default true,
  `:compress-min-bytes` `[:int {:min 0}]`, `:compress-max-bytes` `[:int {:min 1}]` default 16777216 (16 MiB), each
  `:seon.config/dial true`. The min's default is **1024** (owner's approved defaults: "blob compression floor ~1 KB");
  the lane's 256 is Q3 in README-context.md.
- `seon.blob/compression` (private) `{:malli/schema [:=> [:cat :seon.db/connection] [:map [:seon.config.blob/compress? :boolean] [:seon.config.blob/compress-min-bytes [:int {:min 0}]] [:seon.config.blob/compress-max-bytes [:int {:min 1}]]]]}`:
  reads the three dials once per publication; a missing dial throws the installed refusal diagnostic (as written).
  Add the contract: the tree's version has none, and CLAUDE.md contracts private functions too.
- `seon.blob/compress?` (private) `{:malli/schema [:=> [:cat :map nat-int?] :boolean]}`.
- `seon.blob/publish-staged!` (private, `src/seon/blob.clj:164`)
  `{:malli/schema [:=> [:cat :seon.db/connection :map :seon.blob/staged-write] :seon.blob/staged-write]}`:
  `(k/bassoc store digest (staged-source staged) {:immutable? true} {:sync? true :compress? (compress? compression size)})`.
  The caller (`with-publication!`'s `run!`, `:319`) reads `compression` once per publication.
- `verify-stored!` must read back through `k/bget`, which decompresses (`core.cljc:680-683`); confirm it does not
  compare raw on-disk length.

## Proof
`seon.blob-test/published-blob-is-compressed-and-deferred` (new): on the canonical tiered test branch, publish a
64 KB repetitive UTF-8 text through `blob/stage!` + `with-publication!`; `(k/get-meta store digest)` has
`:compressed? true`, `:immutable? true` and `:size` 65536; `(seon.blob/get connection digest)` `=` the text; a 100 B
text is stored with no `:compressed?`.

## Other tests reaching it
`seon.blob-test` (all), `seon.blob-threshold-test`, `seon.blob-publication-test`, `seon.error-storage-bytes-test`.

## Cost
Proportional to the published bytes: one LZ4 pass per blob inside the bounds (64 KiB blocks, fork `c73cbde`: ~5-7 µs
per few-KB value); none outside them. Storage shrinks by the content's compression ratio.

## Deleted
Nothing.

## Commit
`bin/lane commit <lane> -m "BLOB-K: blobs written compressed and immutable" --test seon.blob-test seon.blob-threshold-test seon.blob-publication-test deps.edn reference-code/konserve src/seon/blob.clj resources/seon/schemas/seon.config.blob.edn test/seon/blob_test.clj`

## Dependencies
The fork push; releases of `led-storage` (blob.clj, blob_test.clj) and `dials-16gb` (deps.edn). Schema publication
proven by adopting on a branch of the live store (new dials).

## Reset
None (old uncompressed blobs stay readable: konserve reads by metadata).
