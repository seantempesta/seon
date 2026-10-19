---
type: issue
status: open (a fix measured, not committed: over the cost gate)
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

## Attempt (lane runtime-fix3, 2026-10-20): measured, not committed

Structural fix tried: the derivers of one cache kind (the qualified keyword their entry-point calls name, read from
their source with edamame and matched to `:seon.fn/keywords`; 13 kinds with two or more derivers on the loaded
program, `:seon.render/invocation` among them) follow each other in `seon.sci.runtime/compiled-names`: when one does
not run compiled, none does. The proof (kept as `docs/research/agent-platform/2026-10-20/runtime-fix3-cache-kind.patch`)
went green, but the cost table measured, against HEAD in check JVMs one after another: `:hub-change-sequence`
4,888 -> 5,124-5,151 MB (bound 5,053), `:hub-behavior-change` 1,745 -> 2,050-2,062 MB, `:similar-by-name-warm`
31 -> 142-144 MB. The same candidate without the kind rule measured 4,868 MB / 31 MB, so the rule is the whole cost:
a hub change reaches one deriver of each embed kind (`:seon.embed/store`, `:seon.embed/embedding`, `:seon.embed/index`)
and its partners then key per runtime, re-deriving the store and the index. Options for root: (1) take the rule and
raise the gate (the exact semantics, +5% on the sequence); (2) key a kind's entries by the runtime's change to its
derivers only (the kind's own reach, not the whole delta), so a hub change that leaves the embed derivations' answers
unchanged shares them; (3) make each such cache owner one function per kind (writer and reader in one body), so the
existing reach covers it, converting `seon.render/retain-invocation!`/`invocation` and the twelve other pairs.
