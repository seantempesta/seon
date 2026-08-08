---
type: issue
status: open
severity: blocker
tags: [issue, bootstrap, context, live-drive]
---

# Substitute the bootstrap plan's namespace placeholder before it is evaluated

## Problem

Bootstrap plan forms reach evaluation with a literal `{{seon.ns/name}}`
template placeholder instead of the agent's namespace. The forms fail, and the
failures cascade: the `in-ns` never happens, so the definitions the plan makes
afterwards are unresolvable, and the very first thing a fresh agent's history
shows is its own teaching material erroring.

## Evidence

Cluster `default` (pid 79576), fresh boot at 2026-08-08T04:31, run
`bootstrap:root`. Two of its recorded forms carry the raw placeholder:

```clojure
(in-ns '{{seon.ns/name}})
```

```clojure
(seon.db/q '[:find ?spec . :in $ ?sym
             :where [?f :seon.fn/sym ?sym] [?f :seon.fn/spec ?spec]]
           "{{seon.ns/name}}/largest")
```

The correctly substituted forms `(in-ns 'my.agents.root)` and
`"my.agents.root/largest"` also exist in the same cluster, so substitution
works on one path and not the other.

Three durable `:seon.sci.eval/evaluation-failed` errors at 04:31:06 read
`Unable to resolve symbol: largest`, and two
`:seon.instrument/contract-violated` errors at the same instant read:

```text
seon.program/declaration-row violated its contract (invalid-output):
{:seon.ns/name [{:value nil, :message "missing required key"}],
 :seon.schema.admission/source [{:value nil, :message "missing required key"}],
 :seon.schema/key [{:value nil, :message "missing required key"}],
 :seon.schema/form [{:value nil, :message "missing required key"}]}
```

`:seon.ns/name` missing is consistent with an unresolved namespace
substitution reaching `seon.program/declaration-row`.

## Owner

The bootstrap plan owner that materializes `:seon.bootstrap.plan/forms` into
`:seon.cluster.run.form/source` rows.

## Acceptance

- No `:seon.cluster.run.form/source` ever contains `{{`.
- A fresh cluster boots with zero `:seon.sci.eval/evaluation-failed` and zero
  `:seon.instrument/contract-violated` facts from `bootstrap:root`.
- One class regression asserts the materialized forms are placeholder-free for
  an agent whose namespace is not `my.agents.root`.
