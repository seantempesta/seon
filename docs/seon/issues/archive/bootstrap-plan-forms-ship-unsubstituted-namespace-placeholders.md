---
type: issue
status: superseded
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

## Narrowed, 2026-08-08 (whole-system-arc observer lane)

Cluster `default` (pid 31475). Still open, but the failure is now precisely
bounded — and it is the opposite way round from what the acceptance criterion
above anticipates.

Across all 114 recorded `:seon.cluster.run.form/source` rows, **exactly 2**
carry a placeholder, and both belong to `bootstrap:root`:

```clojure
(in-ns '{{seon.ns/name}})
(seon.db/q '[:find ?spec . :in $ ?sym
             :where [?f :seon.fn/sym ?sym] [?f :seon.fn/spec ?spec]]
           "{{seon.ns/name}}/largest")
```

The distinct `in-ns` forms in the cluster:

```clojure
["(in-ns '{{seon.ns/name}})"     ; bootstrap:root — raw
 "(in-ns 'my.agents.root)"
 "(in-ns 'arc.inventory)"        ; created at runtime — substituted
 "(in-ns 'arc.health)"
 "(in-ns 'arc.timeline)"]
```

So substitution works for agents created at runtime — the three arc agents all
received correct forms and their `in-ns` succeeded — and fails only for the
**root bootstrap at cluster boot**. The acceptance criterion should be inverted
accordingly: the non-root case is the one that already passes.

One consequence for whoever fixes this: because the three runtime agents' `in-ns`
succeeded, the `No such namespace: arc.inventory` error they still hit at
`(largest)` is *not* caused by this issue. That is a separate defect, filed as
[Report a wrong-arity call as an arity error, not a missing namespace](wrong-arity-call-reports-no-such-namespace.md).

## Closure — 2026-08-13

No authored plan EDN and no placeholder substitution survive in `src/seon/bootstrap.clj`; openings are generated (ruling 24).
