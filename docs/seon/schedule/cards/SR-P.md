---
title: "SR-P: a blob is written only with its fact"
type: card
status: ready after blob-konserve
created: 2026-10-08
---

# SR-P: A blob is written only with its fact

Ledger row SR-P (group E). Source: `storage-audit/storage-audit-opus.md` slice P; `storage-audit-astra.md` finding 2.
Model: Sonnet. Authority: the storage-roles rule "every blob a fact names is staged and published with that fact's
transaction" (ledger §3.17).

SR-D (`0c97d0bdd`) removed the last `src` caller of `seon.blob/put!` (the error producer). `put!` and `put-binary!`
publish a blob and release the reachability permit with no fact transaction, so a collection between publication and a
later fact may sweep it: the bug class SR-D fixed. With no `src` caller left (`rg "blob/put!|blob/put-binary!" src`
is empty), deleting both makes the class unwritable.

## Files

- `src/seon/blob.clj`
- `test/seon/blob_test.clj`, `test/seon/blob_publication_test.clj`, `test/seon/ai_stream_fold_test.clj`,
  `test/seon/cluster/registry_test.clj`, `test/seon/render/runtime_test.clj`, `test/seon/render/transcript_test.clj`,
  `test/seon/render/value_test.clj`, `test/seon/render/web_test.clj`

## Functions

Delete from `seon.blob` (HEAD `blob.clj:325-345`; in blob-konserve's working tree `:359-378`):

- `put!` `[connection content]`, contract `[:=> [:cat :seon.db/connection :seon.blob/content] :seon.blob/digest]`
- `put-binary!` `[connection input]`, contract `[:=> [:cat :seon.db/connection :seon.blob/input-stream] :seon.blob/write-result]`

Keep `:seon.blob/write-result` (another `blob.clj` function returns it in the working tree).

Seams the callers use instead (unchanged): `seon.blob/stage!` `[connection content] → :seon.blob/staged-write`
(HEAD `:206`), `seon.blob/stage-binary!` `[connection input] → :seon.blob/staged-write` (`:239`),
`seon.blob/with-publication!` `[connection staged-writes commit-roots!]` (`:303`): publishes, runs `commit-roots!`
under the permit, releases.

## Callers converted (one scripted sweep over the eight test files, then hand-fix)

- A test that stores text and then names it in a fact: stage, and transact that fact inside `with-publication!`:
  `(let [w (blob/stage! connection text)] (blob/with-publication! connection [w] #(db/transact! connection [fact-naming (:seon.blob/digest w)])) (:seon.blob/digest w))`.
- A test that wants a stored blob no fact names (reclamation tests: `blob_publication_test.clj:163`,
  `cluster/registry_test.clj:148`): say so explicitly,
  `(let [w (blob/stage! connection text)] (blob/with-publication! connection [w] (constantly (:seon.blob/digest w))))`.
- `put-binary!` in `blob_test.clj:110,140,164,198`:
  `(let [w (blob/stage-binary! connection input)] (blob/with-publication! connection [w] #(dissoc w :seon.blob/staged-path :seon.blob/staged-octets)))`;
  their assertions (bounded allocation, digest, size) stay as they are.
- `render/value_test.clj:1370` resolves `seon.blob/put!` with `requiring-resolve`: convert the same way.

No new test helper: each site shows whether its blob has a fact.

## Proof

New `seon.blob-test/blobs-publish-only-with-a-commit`:

```clojure
(deftest blobs-publish-only-with-a-commit
  (is (nil? (ns-resolve 'seon.blob 'put!)))
  (is (nil? (ns-resolve 'seon.blob 'put-binary!))))
```

The converted tests stay green.

`--test seon.blob-test --test seon.blob-publication-test --test seon.ai-stream-fold-test --test seon.cluster.registry-test --test seon.render.runtime-test --test seon.render.transcript-test --test seon.render.value-test --test seon.render.web-test`

## Cost

D: none at runtime; −20 source lines; tests grow by one `let` per site.

## Commit

`bin/lane commit <lane> -m "SR-P: delete seon.blob/put! and put-binary!; a blob is written only with its fact" --test seon.blob-test --test seon.blob-publication-test --test seon.ai-stream-fold-test --test seon.cluster.registry-test --test seon.render.runtime-test --test seon.render.transcript-test --test seon.render.value-test --test seon.render.web-test src/seon/blob.clj test/seon/blob_test.clj test/seon/blob_publication_test.clj test/seon/ai_stream_fold_test.clj test/seon/cluster/registry_test.clj test/seon/render/runtime_test.clj test/seon/render/transcript_test.clj test/seon/render/value_test.clj test/seon/render/web_test.clj`

## Dependencies

SR-D (landed `0c97d0bdd`). `src/seon/blob.clj` and `test/seon/blob_test.clj` are held by blob-konserve (BLOB-K) and
`test/seon/cluster/registry_test.clj`'s neighbour `registry.clj` by led-cacheb: claim after blob-konserve releases.
