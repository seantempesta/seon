---
type: issue
status: open
severity: cleanup
tags: [issue, cleanup, runtime]
---

# Remove requires left by the Group 1 pod cut

## Problem

The Group 1 deletion leaves the pod client and one retained D—COVERED test
requiring deleted namespaces. Neither seam may gain a compatibility path.

## Evidence

- `src/seon/client.cljs:65` requires deleted `seon.ai.typeahead`.
- `src/seon/client.cljs:209` requires deleted
  `seon.agent.ctx.typeahead-steps`.
- `test/seon/ai/dispatch_test.cljs:8` requires deleted
  `seon.diffusion.gemma`.
- Final surviving source/config has no other require or direct build entry for
  a Group 1 namespace.

## Owner

The Group 5 `seon.client` deletion owns the two client requires. The surviving
provider-dispatch test owner owns removal of its obsolete DiffusionGemma
dependency when its retained JVM coverage is reconciled.

## Acceptance

- Group 5 deletes `seon.client` without replacing either namespace.
- Surviving source, tests, and config have no require or direct build entry for
  a Group 1 namespace.
- No shim, port, or replacement namespace is introduced.
