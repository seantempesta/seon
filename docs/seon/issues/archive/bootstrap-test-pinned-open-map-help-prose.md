---
type: issue
status: resolved
severity: cleanup
tags: [issue, bootstrap, test]
---

# Keep the bootstrap plan test structural

## Problem

`seon.bootstrap-test/shipped-default-is-thirteen-edn-authored-form-maps`
pinned one editorial sentence from the bootstrap help text, including its line
break. A legitimate rewording left the same open-map teaching in place but
made the bare gate red.

## Evidence

The focused reproduction ran 4 tests containing 36 assertions and failed only
the exact help-text containment at `test/seon/bootstrap_test.clj:81`. Reading
`resources/seon/bootstrap.edn` and `seon.bootstrap/packaged-forms` confirmed
that the shipped plan still has 13 ordered EDN-authored form maps. The help
still says that extra keys are ignored, and the surrounding assertions already
prove the concrete open map contract and the absence of `{:closed true}`.

## Owner

The structural bootstrap-plan regression in `seon.bootstrap-test`.

## Acceptance

The test proves that the shipped plan retains 13 ordered form maps and teaches
the open-map behavior without pinning sentence wording or line wrapping. The
focused `seon.bootstrap-test` namespace passes with a nonzero test count.

## Resolution

Resolved by the commit that archives this note. The teaching assertion now
checks only the stable semantic fragment that extra keys are ignored; the
existing structural assertions continue to protect plan count, order,
namespace designation, and open-contract examples.

## Proof

`bin/test seon.bootstrap-test` passes 4 tests containing 36 assertions with
zero failures and zero errors.
