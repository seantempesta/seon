---
type: issue
status: open
severity: cleanup
tags: [issue, cleanup, runtime]
---

# Remove requires left by the Group 1 pod cut

## Problem

The Group 1 deletion leaves two protected pod-client requires of deleted
namespaces. Neither seam may gain a compatibility path.

## Evidence

- The completed Group 1 seam repair is commit `8a09d615e`.
- Fixed in this seam: `test/seon/ai/dispatch_test.cljs` was deleted. It tested
  the deleted mutable pod dispatch registry and required four deleted pod
  namespaces: Group 1's `seon.diffusion.gemma`, plus Group 3's
  `seon.ai.dispatch`, `seon.ai.anthropic`, and `seon.ai.openai-compat`.
- The surviving contract is already exercised by
  `test/seon/ai/provider_test.cljc` on the JVM runner: immutable hosted-provider
  descriptor data, `adapter-core` resolution, and exclusion of
  `:diffusiongemma` and `:typeahead` from hosted rows.
- Writer test discovery returns `[seon.ai.provider-test]`. A focused
  `bin/test-writer seon.ai.provider-test` attempt stopped before namespace
  loading because this checkout has no current compiled program artifact; it
  did not produce a test verdict.
- A post-fix exact require scan over source, tests, scripts, configuration, and
  build EDN finds only the two protected Group 5 references below.
- `src/seon/client.cljs:65` requires deleted `seon.ai.typeahead`.
- `src/seon/client.cljs:209` requires deleted
  `seon.agent.ctx.typeahead-steps`.

## Owner

The Group 5 `seon.client` deletion owns the two remaining client requires.

## Acceptance

- Group 5 deletes `seon.client` without replacing either namespace.
- Surviving source, tests, and config have no require or direct build entry for
  a Group 1 namespace.
- No shim, port, or replacement namespace is introduced.
