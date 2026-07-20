---
type: issue
status: resolved
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

## Root cause

Two admission state-machine defects turned an ordinary reload race into a
recorded core fault:

1. **A superseded publication could poison its successor.** Every boot emits
   two shadow builds in rapid succession. Build A's `:build-complete` chain
   published and began `rehost-agent-runtimes!`; build B's `:build-start`
   flipped admission `:available` → `:publishing` mid-rehost. `resume!`
   correctly refused (`seon.runtime/unavailable`), but
   `rehost-agent-runtimes!` threw, and the **stale** chain A's `.catch`
   called `admission/mark-unavailable!` — which had no publication ownership
   and transitioned the **newer** publication B to `:unavailable`, recording
   the core fault that the `:crash` dial converted into an exit
   (`src/seon/client.cljs` `:build-complete` catch;
   `src/seon/runtime/admission.cljs` `transition-unavailable!`).
2. **Concurrent prepares faulted on lost retention.**
   `prepare-committed!`'s ownership check (`:publishing` without
   `::prepared-generation`) is not an atomic acquisition, so two overlapping
   `publish-committed!` chains both proceeded; the loser's
   `retain-prepared-generation!` failure was thrown into the
   fault-recording catch ("Verified program generation lost publication
   ownership") — reproduced live at 14:43 on 2026-07-20 and also crashed the
   pod.

## Fix

One mechanism, in the admission owner plus its one reload caller:

- `src/seon/runtime/admission.cljs` — admission state carries a monotonic
  `::publication` counter, incremented whenever publication is acquired
  (`begin-publication!`, `detach!`) and preserved across `:available` /
  `:unavailable` transitions. `mark-unavailable!` accepts an optional
  `::publication`: a caller whose publication was superseded transitions
  nothing and records no fault. `prepare-committed!` treats lost
  `retain-prepared-generation!` as ordinary supersession — an
  errors-as-values refusal (`::prepared? false` + `:seon/error`), never a
  thrown core fault.
- `src/seon/client.cljs` — `shadow-build-notify!` `:build-complete` captures
  the publication at entry and scopes both failure catches to it;
  `rehost-agent-runtimes!` ends quietly ("rehost superseded by a newer
  publication") when admission closed mid-rehost, because the newest
  publication's own `:build-complete` rehosts every agent.

## Proof

- `bin/test-cljs --test=seon.runtime.admission-test`: 20 tests / 118
  assertions, 0 failures — including new regressions
  `superseded-publication-failure-cannot-poison-the-newer-publication`,
  `owned-publication-failure-with-its-token-still-records-one-fault`, and
  `concurrent-prepare-loses-retention-as-ordinary-supersession`.
- Full `bin/test-cljs`: 1287 tests / 5835 assertions, 0 failures, 0 errors.
- Live: the original crash reproduced twice on 2026-07-20 with pre-fix code
  (14:37 and 14:43 pod logs, `SEON-CORE-FAULT` + `:crash` exit). After the
  fix, a forced reload storm (four watched-source touches in ~10 s during
  active rehosting) produced "reload: rehost superseded by a newer
  publication", "reload: committed publication nil" (refused loser), then
  "reload: agent runtimes rehosted {:seon.client/reinstalled
  ["fresh-dancers-behave" "root"]}" — pod stayed alive, heartbeats resumed,
  `grep -c SEON-CORE-FAULT` = 0 in the surviving pod log, and `bin/seon
  status` returned ready after restart.
