---
type: issue
status: resolved
severity: blocker
created: 2026-10-20
tags: [issue, sci, branch-runtime, review3-astra]
---

# A branch cache writer and reader use different keys

`seon.sci.eval/bind-analysed!` retains compiled functions whose static reach is unchanged, while `seon.sci.runtime/keyed-entries` adds a runtime digest only when cache operations are reached through SCI. Two parts of one cache protocol therefore disagree when a changed helper reaches the writer but not its reader.

A production reproduction changes only `seon.render/invocation-weight` to return 2048 on a disposable database. On its acquired branch, `seon.render/retain-invocation!` runs analysed and writes the runtime-keyed entry. `seon.render/invocation` remains compiled and reads the bare key. Immediately reading the entry just returned by the writer gives `nil`. If main previously held the same key, retaining `:review3/value :branch` then reading returns values `[:branch :main]`; expected `[:branch :branch]`. The signatures and supplied evidence are valid, and no host-bound exception lies between these functions.

Red proofs: `seon.runtime-review3-astra-new-test/cache-reader-and-writer-agree-in-one-runtime` (expected `[2 2]`, observed `[2 nil]` cold and `[2 1]` after main fills its key), `seon.runtime-review3-astra-new-test/real-invocation-cache-reader-and-writer`, and `seon.runtime-review3-astra-real-test/a-branch-cache-reader-cannot-read-the-entry-it-replaced`. Candidates `172f18537a2df6df97adbabe25e6bf4aec5da6c3` / `84f7fb4dd309dca3ff587987c73b8dc85a3047dc`, runs `062ec5294c34` / `45b51eefd740`.

This is distinct from the projection-holder defect: these operations all use `seon.cache`, but the compiled/analysed boundary splits the key. The runtime must preserve the cache owner's write/read/evict semantics, including mixed compiled and analysed consumers.

Evidence and limits: [independent review](../../research/agent-platform/2026-10-20/runtime-review3-astra.md). Reproducible probe source: [review patch](../../research/agent-platform/2026-10-20/runtime-review3-astra-probes.patch); it is evidence, not an installed change.

## Resolution (lane runtime-fix4, root option 3)

Every cache kind some function reads or evicts (`seon.cache/present`, `seon.cache/evict!`) is keyed by one function,
which derives it too ("One derivation, every consumer"): its readers call that function, so the reach that decides
whether it runs compiled decides for every call of the kind, and no new rule joins `seon.sci.runtime/compiled-names`.
The ten kinds read apart from their deriver: `:seon.render/invocation` (`seon.render/invocation` reads through
`seon.render/retain-invocation!`), `:seon.db/basis` (`seon.db/hold-basis!`), `:seon.db/projection-alias`
(`seon.db/alias!`), the projection kinds (`seon.db/content-projection`, read by `seon.db/resolved` and
`seon.db/nearest-base`), `:seon.embed/embedding` (inlined into `seon.embed/layered`, its only writer deleted),
`:seon.sci.eval/program-context` (`seon.sci.eval/derive-program-context` keeps what `seon.sci.eval/program-context`
derives), `:seon.sci.runtime/analysed` (`seon.sci.runtime/analysed`, read by `seon.sci.runtime/bind`),
`:seon.test.runner/current-result-head` (`seon.test.runner/held-answer!`), `:seon.test.runner/reach-index-by-revision`
(`seon.test.runner/reach-entries`) and `:seon.test.runner/result` (`seon.test.runner/retained-result`, which
`seon.test/carried-results`, `seon.agent-cost/cache-miss` and the branch-reuse test call). The census row
`seon.sci.open-derivers-test/a-cache-kind-read-apart-from-its-derivation-has-one-keyer` is red when a kind gains a
second keyer; `seon.sci.eval-test/a-cache-kinds-writer-and-reader-key-it-alike-on-a-branch` is Astra's reproduction.
The cost table is unchanged (`:hub-change-sequence` 4,899-4,904 -> 4,885-4,894 MB, `:similar-by-name-warm`
31.1-31.3 -> 30.7-30.9 MB, check JVMs one after another). An earlier attempt made all derivers of a kind run
analysed together and cost +250 MB on the sequence (its patch:
`docs/research/agent-platform/2026-10-20/runtime-fix3-cache-kind.patch`).

The synthetic probe `seon.runtime-review3-astra-new-test/cache-reader-and-writer-agree-in-one-runtime` (a hand-built
writer and reader of one kind in two functions) stays as written: such a pair in the program is red in the census row.
