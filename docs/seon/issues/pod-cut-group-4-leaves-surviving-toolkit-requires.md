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

## Repair evidence

The completed Group 4 seam repair is commit `aa766168e`.

The seam-repair wave fixed each live portable owner without restoring a pod
namespace:

- `my.plan` now loads the agent entity schema from `seon.agent.core`.
- `my.kb` deleted the old `my.data`/`seon.items`-shaped aggregate and recall
  surfaces that had no surviving execution owner.
- `my.skills` deleted the effectful in-eval load/unload and pod filesystem
  scan/render path instead of binding deleted `seon.agent.ctx.admin`.
- `config/system.edn` no longer seeds `my.data`, `my.ns`, or the deleted
  `seon.agent` orchestration surface.
- eleven retained CLJS tests with direct Group 4 requires were deleted:
  `test/my/{request_schema,skills}_test.cljs`,
  `test/seon/{config,ctx,error_record,internal_boundary,route,runtime/recovery,schema}_test.cljs`,
  `test/seon/agent/multiagent_test.cljs`, and
  `test/seon/agent/ctx/canvas_test.cljs`. Group 3's seam commit separately
  deletes the overlapping `my.kb`, provider-generation, driver, and web-serve
  tests.

The issue remains open because deletion is blocked at two already-scheduled
owners:

- protected Group 5 `src/seon/client.cljs` still requires `seon.agent`,
  `seon.agent.ctx.admin`, `seon.repl.autocomplete`,
  `seon.runtime.recovery`, `seon.items`, `my.kb.shared`, `my.data`, and
  `my.ns`;
- render-held Group 6 `src/seon/agent/ctx/driver.cljs` still requires
  `my.data` and `my.ns`, while `src/seon/web/serve.cljs` still requires
  `seon.agent`.

## Owner

Later seam repair must point each live consumer at its already-surviving owner or delete the dead consumer without restoring a pod namespace.

## Acceptance

No surviving source, configuration, or retained test requires a Group 4 namespace, and no compatibility namespace exists.
