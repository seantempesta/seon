---
type: issue
status: closed
severity: cleanup
tags: [issue, agent, schema]
---

# parse-forms option map uses a bare key

## Problem

`seon.repl.internal/parse-forms` accepted the public option map
`{:strip-fences? boolean}`. The bare `:strip-fences?` key conflicted with the
maintained rule that every Clojure map key is fully namespaced, even though the
function's emitted parse-entry maps were already corrected.

## Evidence

[[../../../prds/source-cleanup/research/parse-forms-entry-boundary-2026-07-20]]
re-audited the parser and callers. The function schema, destructuring,
diffusion retrieval/oracle callers, worker validator, portable oracle server,
and tests all preserved the bare option key. This was independent of the
historical parse-entry issue, whose emitted entry keys and precise union were
already complete.

## Resolution

The Stage 5 integration migrated the one option contract atomically to
`:seon.repl/strip-fences?` in the parser schema and destructuring, its private
delegation, every maintained source and portable-script caller, and the
focused tests. No legacy key, compatibility adapter, or second option
normalizer remains.

## Proof

- `bin/test-parser`: 46 tests / 369 assertions, zero failures and errors.
- `bin/test-cljs --test=seon.repl.internal-test`: 46 tests / 370 assertions,
  zero failures, errors, and compiler warnings.
- Diffusion oracle, retrieval, and scaffold selectors: 21 tests / 149
  assertions, zero failures, errors, and compiler warnings.
- The `worker-validator` build completed with zero warnings, and its one-shot
  smoke returned one parsed form. The portable `parse-raw` oracle smoke also
  returned one parsed form.

Implementation: `f49268cd`.
