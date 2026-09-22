---
type: landing
status: complete
created: 2026-09-22
tags: [agent-platform, test, selection, evidence]
---

# Gate input mismatch

## Finding

The cold refusal was real, but neither of the two documentation-only commits was
an input. The prepared source entity carried
`f0abf92703126d67ed694e61748fea07c7dfee3a13be80048a937b238a5aa5ea`;
the HEAD snapshot carried
`3012994aed81da26c14dd72c1cbb30a733f3d31a0021c5a11c90db8699a4388b`.

The exact probe was:

```clojure
(let [requested (cache/external-input-digests "." (cache/input-digests "."))
      artifact (clojure.edn/read-string
                (slurp "target/test-published-bases/e0a0e8b0f9618295b68945167c9ac168d6bd130ecbae2e0091c2a39e9f994c38/base/build/current-src.edn"))
      published (cache/external-input-digests
                 "." (:seon.source/relative-file-digests artifact))]
  {:requested-count (count requested)
   :published-count (count published)
   :requested-digest (cache/input-evidence-digest requested)
   :published-digest (cache/input-evidence-digest published)
   :differences (cache/differing-inputs published requested)})
```

The request had 302 external inputs and the exported publication inventory had
283. Exactly 19 requested inputs were absent from the publication inventory:

```text
test/fixtures/call_graph_fidelity/declarations.txt absent bf0f0bfef5ef7c3f010a67d61e1a01e1b78c9db9c4309208ea3bff33d8c59e38
test/fixtures/call_graph_fidelity/dispatch.txt absent d1a9d40634665c605bfc5dbff4b4f86dd28cde3a63ba42fab151b50ad8420195
test/fixtures/call_graph_fidelity/implementation.txt absent eebc33c1aa12852a07ea2fc880f33fe74c2b1e54a538817f123ee7eee03d165d
test/fixtures/call_graph_fidelity/implementations.txt absent d002967bd08532e8857b58bcca4ad57c2661e027d0e965a9b9e0901a41c71d64
test/fixtures/call_graph_fidelity/references.txt absent 4a7c4823e1b74505423c42f0cc8d08527e5567aaf8f627ffe1d77498c289849b
test/fixtures/program_facts_s1/source.txt absent 9db4c33783faa528908c7342b7bfbc2330ce1ec6a8acb9a8c374c5dd85523cd6
test/resources/publication-program/seon/id.clj.txt absent 10d5a50d46612a7fe9f539f7cb130a5dd9dfac71c5070c1b1de63ec9971d5644
test/resources/publication-program/seon/id_test.clj.txt absent 6f758b070120903728a3104b402b5d0901cbfa5c2bec5c96c838799bfb2347b1
test/seon/dev/hook_convergence_probe.py absent 68c62eb195b893ae2cd0c47d516caf9d747a800c599cfccf3b0ed66014480b83
test/seon/dev/status_lock_probe.py absent 0b5a480a7950c81b69b1c4421761b1b7500a679703b2bf269962e65f64cc7794
test/seon/html_views_browser.cjs absent f33b871d58e71477ee7da2654a763f431afe564f1e24aaee3a855dff6e9e55ab
test/seon/html_views_fixture_browser.cjs absent f321962d446388cc0f1dcc1ef0fdcdbc775d8b353141451f58d8d60f471a23fa
test/seon/html_views_inspect.cjs absent 96bacb7ed5192da1252afa4af7a0570c68fa266a570833a10b890d090475c2ed
test/seon/render/page_feed_adoption_probe.py absent 8122f2d0060d4df25344e3eec0f53c29974dedfe8e566a5a144655d4ff0e9dc3
test/seon/render/page_feed_browser_probe.cjs absent 8a3604f3486dcd4aadc84d5a3391969ccaa052048552820998d596cc2d7b8ddd
test/seon/render/page_feed_layout_probe.cjs absent 14345223a27bb2d553b81b9cad4ca403854c230a838a3a9516a3a3df76b9b58c
test/seon/render/page_feed_probe.py absent d6d4d2f579c38dd3fafe66d528ba383415952d68058f1e828c6e0b0d8361551f
test/seon/render/page_feed_thread_probe.py absent fa03a024ea9cd2ec02aeac0c816352d2a3965e5a2eff98edc6ffea06d0bed1d9
test/seon/render/root_walk_probe.py absent a581da71a37d48937a42643e87d952949a04d92543daadb7874753bb6053eba6
```

Each row is `path published requested`; `absent` is the published value. The
retained request snapshot is `tmp/test-runs/run.YOTKDk`; the published artifact
is named in the probe.

The Datahike pin was not different. `git ls-tree` at `3f1b50ba9`, at
`09e8ba533`, and the live index all named `6dd49e5e`; the retained
`dependency-pins.txt` named the same pin. The mismatch was authority drift:
`seon.cluster/source-snapshot` and partial `full-source-refresh!` sealed the
filtered program-fact inventory, while `seon.test.runner` requested a digest of
the complete declared inventory. Nonindexed fixture inputs therefore existed on
only the requesting side.

## Landed behavior

- `seon.test.cache` owns selection and hashing of external input evidence.
- Full and partial publication seal the complete declared input inventory.
  Program indexing remains filtered; no gate input was added or removed.
- Exported source artifacts carry the authoritative input digest and exact
  external-input map. New bases use that prepared inventory; old bases without
  it use their exported file inventory, so the historical 19 omitted paths are
  diagnosable.
- `seon.test/select` adds aggregate and per-path published/requested digests to
  `:seon.error/diagnostic-evidence` when the carried inventories prove both
  aggregate values. The launcher prints every differing path and both values.
- Regressions cover a three-way changed/added/removed input difference and a
  documentation-only edit that remains selectable.

No dependency implementation changed. The pinned Datahike seam remained
`reference-code/datahike` at `6dd49e5e`; publication and selection only consume
its existing immutable database and query guarantees.

## Verification

`clojure -M:test -e '(require (quote seon.test) (quote seon.test.runner)
(quote seon.cluster) (quote seon.test.cache)) (println :loaded)'` loaded HEAD
plus the changed paths and printed `:loaded` in 12.6 seconds.

The final required command was run against snapshot `tmp/test-runs/run.54hjj2`:

```text
bin/test-fast --paths src/seon/test/cache.clj src/seon/cluster.clj \
  resources/seon/schemas/seon.test.selection.edn \
  resources/seon/schemas/seon.source.edn src/seon/test.clj \
  src/seon/test/runner.clj test/seon/test/selection_test.clj \
  test/seon/test_cache_test.clj -- \
  seon.test.selection-test seon.test-cache-test
```

It acquired the packaged projection, armed 1,693 contracts, and ran 18 tests / 257
assertions. Both new regressions and every `seon.test-cache-test` passed. The
namespace command remained red on two pre-existing boundaries outside this cut:

- `fileless-sci-tests-use-the-same-selection` took 8,842 ms against its declared
  5,000 ms bound.
- the existing refused-read case in
  `selection-derives-bases-obligations-and-exact-symbol-reach` returned a base
  error without the complete `seon.fn/declared-reference-edges` facet.

Those same two failures reproduced in the preceding focused run. No gate,
`--prepare-head-base`, platform suite, or `default` operation was run. The cold
gate and replacement base proof remain orchestrator-owned.

The test snapshot reported that foreign edits to `src/seon/cluster/boot.clj`
were excluded and HEAD bytes were used. The operator/boot lane's files and the
two pre-existing dirty documentation files were not edited or staged. The MCP
`runtime_status` and `eval_clj` surfaces were unavailable; `bin/seon status`
reported the already-running default as degraded, so no REPL mutation or reload
verification was claimed. Repository hooks independently queued source
publication receipts for edited files; this lane did not inspect, resume, or
operate those receipts.

## Clock and size

This cut changes the identity carried through the existing publication path; it
does not add another publication pass or cache. The committed measurement script
was not run because the owner reserved cold publication and platform proof. The
final focused armed run began at `10:41:43Z` and ended at `10:43:21Z` (98 seconds,
including the unrelated 80-second selection regression). The implementation and
regressions were 248 insertions and 13 deletions before this note.
