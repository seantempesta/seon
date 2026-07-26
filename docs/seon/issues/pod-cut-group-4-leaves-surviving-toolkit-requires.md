---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, cleanup]
---

# Remove surviving requires of pod toolkit namespaces

## Problem

The Group 4 cut deletes pod toolkit and orchestration namespaces that surviving source, configuration, and retained CLJS claim tests still require.

## Evidence

Post-cut `rg` finds direct requires in `src/my/plan.cljc`, `src/my/kb.cljc`, `src/my/skills.cljc`, `src/seon/agent/ctx/driver.cljs`, `src/seon/web/serve.cljs`, `src/seon/client.cljs`, and `config/system.edn`; retained CLJS claim tests also require the deleted namespaces.

## Owner

Later seam repair must point each live consumer at its already-surviving owner or delete the dead consumer without restoring a pod namespace.

## Acceptance

No surviving source, configuration, or retained test requires a Group 4 namespace, and no compatibility namespace exists.
