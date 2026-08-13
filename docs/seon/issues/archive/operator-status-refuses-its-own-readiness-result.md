---
type: issue
status: resolved
severity: friction
tags: [issue, operator, diagnostics, contract]
---

# Operator status refuses its own readiness result

## Problem

Calling `seon.operator/status` in a healthy isolated cluster REPL throws an
instrumentation invalid-output exception instead of returning status data.
The failure wall claims missing `:seon.error/kind` and `/message` keys and a
missing nested problems value.

## Evidence

Live proof on 2026-08-12 at commit `16f022fc9`:

```text
seon.operator/status violated its contract (invalid-output):
{:seon.operator/clusters
 [#:seon.operator{:readiness
                  #:seon.problems{:problems [{:value nil,
                                              :message "missing required key"}]}}]
 :seon.error/kind [{:value nil, :message "missing required key"}]
 :seon.error/message [{:value nil, :message "missing required key"}]}
```

The external `bin/seon --root ... status` command still reported the cluster
alive. The exploration bypassed this broken diagnostic by reading the public
`seon.operator.runtime/running-instances` atom in the same JVM.

## Owner

`seon.operator/status` and its declared output schema must agree on the
success shape. Diagnostics must return truthful data rather than throw a
secondary contract wall.

## Acceptance

- In-JVM `status` returns the healthy cluster status value.
- Empty readiness problems validate as the success shape.
- A real operator failure returns the flat error arm with required error keys.

## Closure — 2026-08-13

`seon.operator/status` now declares `[:or :seon.operator/status :seon.error/value]` (`src/seon/operator.clj:83-86`, verified 2026-08-13).
