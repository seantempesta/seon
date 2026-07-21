---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Unexpected pod crash live proof — 2026-07-15

## Result

The source-frozen default cluster distinguishes planned shutdown from an exact
contained workload crash, replaces only through the canonical coordinator,
and performs one database-derived recovery without fabricating work.

Before the control, two consecutive contained `bin/seon restart` transitions
classified pod, writer, watcher, and aggregate clean. The database retained
attachment `54b5b7e7-51fb-3220-b079-81a81914d86f/:db`.

## Live control

The live CLJS REPL opened run `ce888bkrri27` for root through
`seon.agent.run/open-run!`, then opened turn `r44vme5wtmkl` through
`seon.agent.turn/open-turn!`. Its body returned a deliberately unresolved
Promise only after the turn row was committed. Read-back proved an open run,
a running turn, and the body-started occurrence before any process signal.

The selected pod record named containment generation
`58c773a2-59d6-49af-ae77-7ef6e9568e8d` and workload PID `29015`. Sending
`SIGKILL` to that exact workload produced the owner's terminal value:

```text
generation=58c773a2-59d6-49af-ae77-7ef6e9568e8d
status=drained
trigger=workload-exit
anchor_exit=-9
```

The next ordinary `bin/seon restart` reported:

```text
restart: forced
  pod: forced reason=unexpected-exit generation=58c773a2-59d6-49af-ae77-7ef6e9568e8d trigger=workload-exit
  writer: clean generation=d4acbc73-1131-4507-a414-cb7d263ccb4f trigger=requested
  watcher: clean generation=92bc9b6a-0ccc-41a0-af1c-9404c505aed8 trigger=requested
```

This is the intended classification: exact containment absence permits
replacement, but an unrequested pod exit can never become clean application
evidence.

## Database read-back

Cold boot derived and committed exactly one recovery anchor,
`d3id7f6lpw2u`, with reason `:unexpected-exit`. The same database then showed:

- root's current run pointer absent;
- run `ce888bkrri27` closed with reason `:crashed`;
- turn `r44vme5wtmkl` marked `:interrupted`;
- no eval rows attached to that turn; and
- the original attachment at transaction `536871009`.

An immediate second restart classified every component and the aggregate
clean. Read-back still contained exactly the same one recovery anchor, no
current run, and the same attachment at transaction `536871012`. Recovery is
therefore idempotent and creates neither a replacement run nor a fabricated
eval/result suffix.

## Remaining boundary

This closes unexpected pod workload replacement and cold recovery for one
committed running turn. It does not claim arbitrary agent-eval child memory
containment, provider cancellation, restore/undo, or the later three-form
partial-commit fixture. Those remain in their owning roadmap units.
