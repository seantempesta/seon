---
type: issue
status: resolved
severity: blocker
tags: [issue, config, database]
---

# Resolved root context emits a nil render slot during fresh apply

## Problem

Fresh default apply now passes page-plan identity and transaction EDN-slot
encoding, then correctly refuses the root context's component tree. The
resolved transaction contains `:seon.agent/ctx` child `:root-role` with
`:seon.render/ai nil`, although `config/system.edn` declares non-nil root-role
text.

## Evidence

- `tmp/seon-operator/resolved-manifest.edn` preserves the non-nil root-role
  source text.
- `tmp/seon-operator/cluster-apply/9a4a35d8-16d6-4687-afa5-3815203ee82e.edn`
  records the component refusal at `:seon.agent/ctx` and the nil child value.
- The strict transaction validator must retain this refusal: absent map keys
  are normalized before storage, but a produced nil at an acquired component
  child is not a valid render value.

## Owner and acceptance

The resolver already preserved the root-role render text. The fault was the
transaction validator in `src/seon/db/internal.cljc`: it decoded the complete
component tree, then recursively treated each already-logical child as encoded
storage again. The second EDN read interpreted the semicolon-prefixed root-role
text as a comment and returned nil.

Acceptance requires one logical decode per transaction value, the unchanged
strict component refusal, a fresh successful apply, and a five-process startup
unless an independent operator layer fails with separately filed evidence.

## Resolution

`validate-entity-values!` now constructs one logical validation tree and its
recursive ref walk validates those already-logical child maps without decoding
them again. The dual-tier portable database contract transacts a nested
semicolon-prefixed mixed-union string and proves the writer-bound value is its
quoted EDN representation.

- CLJ: `seon.db.portable-test` — 3 tests, 69 assertions, zero failures.
- CLJS: the focused portable contract — 1 test, 62 assertions, zero failures.
- Resolver contract on both CLJ and CLJS: 1 test, 3 assertions per tier, zero
  failures; the resolved root-role equals the manifest text, is a string, and
  contains no present nil slot.
- Fresh `bin/seon cluster apply default` succeeded in 37.41 seconds, created
  root, and stamped basis transaction `536871012`.

The requested five-process proof remains blocked outside this fix: reset
starts ordinary admission before initialization identity schema exists, apply
retains its writer generation, and successive source-unchanged startups publish
different release digests. Each blocker has its own open issue.
