---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, adoption, identity, boot, class/n3]
---

# The running instance and `loaded-identity` give two answers to "which program is loaded"

## Evidence (default pid 94554, 2026-09-26 ~00:58Z, JVM-mode read-only probe)

| Place | Value |
|---|---|
| `(:seon.source/commit-id (get @seon.operator.runtime/running-instances "default"))` | `6ab710a0-8e4e-595b-8bcd-0757573ccf16` |
| `@seon.sci.eval/loaded-identity` | `{:seon.source/commit-id #uuid "6ab714b1-dabb-5427-85eb-60ceddec3173"}` |

The instance value is built once by `seon.cluster.boot/start!`, so its commit id is the boot commit. `loaded-identity`
(`src/seon/sci/eval.clj:1366`) is advanced by development adoption (`advance-loaded-identity!`) and gates every
detecting execution (`call-detecting-program-change`). After any adoption the two differ. A reader of the instance
(status, `db.clj` and `test/runner.clj` read `running-instances`) sees a program the JVM no longer runs, with nothing
marking it as historical.

## Owner and acceptance

The owner is the process root (`resources/seon/operator/runtime.clj`) together with boot.

Acceptance: exactly one current answer. Either `loaded-identity` moves into the process root beside `running-instances`
and the instance carries no program identity, or the instance field is renamed and declared as the boot observation
(`:seon.boot/…`), with every current-program reader converted to `loaded-identity`. One regression adopts one commit and
asserts that every reader answers the adopted identity.

Member of [class-loaded-artifacts-lack-source-identity](class-loaded-artifacts-lack-source-identity.md). Found by
`docs/research/agent-platform/system-state-opinion-2026-09-26.md` §2 and §9.
