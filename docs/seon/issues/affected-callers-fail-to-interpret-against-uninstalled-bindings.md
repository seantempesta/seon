---
type: issue
status: open
severity: blocking
created: 2026-09-24
tags: [issue, sci, acquisition, branch, interpretation]
---

# Affected callers fail to interpret against uninstalled bindings

## Problem

A branch that changes one interpretable `:core` function interprets its
reverse closure (`seon.sci.eval/acquire-program!`, `interpreted` =
overridden plus `seon.fn/reverse-closure` affected). Many interpretable
affected callers then fail to analyze. Each failure is a `:core` row refusal,
so the branch's whole acquisition refuses and every test and evaluation on it
refuses (`seon.test` resolution, `seon.sci.eval` evaluation boundary).

Observed 2026-09-24 on default (pid 70372), inside a `seon.test/run` member
fixture branch, after stamping a new `:seon.program/definition-digest` on
`seon.render.hiccup/class-value` (reverse closure of 77 declarations). The
distinct refusal messages included:

- `Cannot interpret seon.render.web/derive-page: Unable to resolve symbol: stream-strip-id`
  (a same-namespace private helper outside the interpreted set)
- `Cannot interpret seon.cluster/serve!: seon.operator.runtime/running-instances does not name an installed SCI Var`
  (a non-function Var: `defonce`/`def` has no function row)
- `Cannot interpret seon.cluster.agent-test/parallel-trial: Unable to resolve symbol: async/chan`
  (an alias to a dependency namespace SCI does not carry)
- `Cannot interpret seon.render.web/handler: Unable to resolve symbol: ring/router`

Evidence blob on default: `09c6bf69041d471233d59fe8394c804d130f8a74bfcd921cd76d1911b2ca9a82`.

## Reproduce

Inside a `seon.test/run` member: `support/with-database`, stamp
`[:db/add [:seon.fn/sym 'seon.render.hiccup/class-value] :seon.program/definition-digest <new digest>]`,
`eval/acquire!` a fresh `eval/cluster-ctx`, read
`(:seon.test/acquisition-refusals (eval/acquired-program ctx))`.

## Not this defect

Host-bound affected callers and rows in unloaded namespaces no longer refuse
the acquisition (lane fix-unloaded-host-bound-row, 2026-09-24). This issue is
the interpretable remainder of the same closure.
