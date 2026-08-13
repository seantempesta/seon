---
type: issue
status: open
severity: friction
tags: [issue, test, class/n3, wave/dev-tooling-face-hygiene]
---

# A stale language-specific clj-kondo cache entry blocks correct code

## Problem

`.clj-kondo/.cache/v1/{clj,cljc,cljs}/<ns>.transit.json` records a namespace's
var arities per LANGUAGE. When a namespace changes file extension, or when one
language's entry is not refreshed after an arity change, the stale entry keeps
answering — and clj-kondo reports `invalid-arity` for a call the source plainly
supports. The finding names the callee and never the cache, so it reads as an
impossible error.

Twice in two days, mirror images of each other:

- 2026-08-07 morning — `.clj-kondo/.cache/v1/cljc/seon.schema.transit.json`
  was left over from when `seon.schema` was a `.cljc`. It recorded
  `identity-attr?` as one-arity, and the CLJS branch of `seon/reconcile.cljc`
  linted against it, so the edit hook BLOCKED a correct two-arity call.
  ([research](../../prds/sci-execution-runtime/research/declaration-population-per-item-2026-08-07.md))
- 2026-08-07 evening — `.clj-kondo/.cache/v1/clj/seon.reconcile.transit.json`
  was left over from when `seon.reconcile` was a `.clj`. It recorded
  `identity-attributes` as zero-arity while the fresh `cljc/` entry correctly
  held `[0 1]`. This one did not merely warn: `seon.fn/build-manifest` refuses
  the index on any `:level :error` finding, so `seon.test-support`'s fixture
  threw "Static program analysis found blocking errors" and EVERY test in
  `seon.reconcile-test` errored — 8 tests, none of them related to the change.

Both cost roughly half an hour to attribute by hand.

## Evidence

```
$ ls .clj-kondo/.cache/v1/*/seon.reconcile*
.clj-kondo/.cache/v1/clj/seon.reconcile.transit.json     # stale, arities #{0}
.clj-kondo/.cache/v1/cljc/seon.reconcile.transit.json    # fresh, arities #{0 1}

$ rm .clj-kondo/.cache/v1/clj/seon.reconcile.transit.json
$ bin/test seon.reconcile-test
Ran 8 tests containing 20 assertions. 0 failures, 0 errors.
```

`src/seon/reconcile.cljc` is a `.cljc` and has been for some time. A `clj/`
cache entry for it cannot be correct by construction.

## Acceptance criteria

- A namespace's cache entries for languages its current file does not provide
  are not consulted (or are pruned when the file's extension changes). The
  invariant is derivable: the file on disk names which languages exist.
- When the index refuses, the refusal prints the `:level :error` findings it
  refused on, not the complete finding list — the four errors were buried in
  several hundred `:warning` entries in a single line.
- Ideally the `invalid-arity` finding, or the refusal around it, names the
  cache entry it came from. Today neither does.

## Owner

`bin/seon-hook` / `seon.fn` (analysis and index refusal), with the cache
lifecycle question belonging to whoever owns `.clj-kondo` configuration.

## Workaround

Delete the offending entry. If an `invalid-arity` (or any resolution finding)
contradicts the source, `ls .clj-kondo/.cache/v1/*/<ns>.transit.json` and
remove any entry whose language the current file does not provide.
