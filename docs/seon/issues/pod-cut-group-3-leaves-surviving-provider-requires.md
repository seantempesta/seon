---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, cleanup]
---

# Remove surviving requires of pod provider namespaces

## Problem

The Group 3 cut deletes pod provider, generation, and embedding namespaces that surviving source and retained CLJS claim tests still require.

## Evidence

Post-cut `rg` finds direct requires in `src/seon/client.cljs`, `src/seon/web/serve.cljs`, `src/seon/agent/ctx/driver.cljs`, and `src/my/kb.cljc`; retained A/C/D CLJS tests also require the deleted namespaces.

## Repair evidence

The seam-repair wave removed the dead provider-backed `my.kb/recall` and
`my.kb/source-stats` surfaces instead of relocating their Group 3/4
dependencies. `my.kb` now loads on the JVM through its surviving database and
schema owners. Ten retained CLJS tests whose subjects or provider dependencies
died with the pod were deleted:

- `test/my/kb_test.cljs`
- `test/seon/ai_test.cljs`
- `test/seon/retry_test.cljs`
- `test/seon/ai/{anthropic,generate_code,openai_compat}_test.cljs`
- `test/seon/client/provider_routing_test.cljs`
- `test/seon/embed_test.cljs`
- `test/seon/agent/ctx/driver_test.cljs`
- `test/seon/web/serve_test.cljs`

The remaining direct Group 3 references are not repaired in this wave:

- protected Group 5 `src/seon/client.cljs` still requires `seon.ai` and its
  deleted pod provider namespaces;
- render-held Group 6 `src/seon/agent/ctx/driver.cljs` and
  `src/seon/web/serve.cljs` still require deleted `seon.ai`.

JVM requires of `seon.embed` in `.clj` source and tests resolve the surviving
`src/seon/embed.clj` owner and are not dangling pod references.

## Owner

Later seam repair must delete each dead consumer or use its already-surviving JVM owner without restoring a pod namespace.

## Acceptance

No surviving source, configuration, or retained test requires a Group 3 pod namespace, and no compatibility namespace exists.
