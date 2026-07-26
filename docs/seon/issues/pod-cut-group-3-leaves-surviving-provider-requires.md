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

## Owner

Later seam repair must delete each dead consumer or use its already-surviving JVM owner without restoring a pod namespace.

## Acceptance

No surviving source, configuration, or retained test requires a Group 3 pod namespace, and no compatibility namespace exists.
