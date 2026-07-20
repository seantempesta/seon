---
type: issue
status: open
severity: blocker
tags: [issue, pod, agent]
---

# Reload rehost crash-loops while admission is publishing

## Problem

On 2026-07-20 the default cluster pod crash-looped: every boot reached
`reload #2` and died with

```text
SEON-CORE-FAULT reload: agent runtime rehost failed
{:seon.agent/id "chilly-hands-draw"
 :seon.agent.runtime/error "resume!: runtime program generation became unavailable"
 :seon/error {:seon.error/kind :seon.runtime/unavailable
              :seon.error/data {:seon.runtime.admission/status :publishing
                                :seon.runtime.admission/generation -2137247393}}}
seon.error/record!: on-core-error :crash — exiting after persisting the fault datom
```

(log `logs/operator/pod/3accc620-5585-4a20-a737-660f4b6537db.log`). The pod
exits, the supervisor restarts it, and the same rehost fails again — the
cluster is permanently down while the persisted agent population contains
that agent. `bin/seon up` reported ready and then drained within a minute.

## Suspected owner

The reload path racing agent rehost against runtime program publication
(`:seon.runtime.admission/status :publishing`): resume! treats a
mid-publication generation as unavailable and the `:crash` dial turns a
transient ordering condition into a persistent boot failure.

## Workaround applied

`bin/seon cluster reset default` (2026-07-20) — wiped the test database;
the fresh population boots cleanly. The prior population is lost, so the
reproduction now needs an agent created before an interleaved
reload/publication.

## Acceptance

A hot reload that lands while admission is `:publishing` either waits for
publication or retries rehost, and never converts the ordering race into a
core-fault crash loop. Regression proof: pod survives a reload issued during
publication with all persisted agents rehosted.
