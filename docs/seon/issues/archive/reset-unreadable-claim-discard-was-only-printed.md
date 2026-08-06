---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, process, database]
---

# Make forced reset prove the flock before discarding unreadable claims

## Problem

`bin/seon reset --force` printed that it would discard unreadable process
claims after proving the store flock free, but the reset path neither probed
the flock nor deleted those exact claim files. It then deleted the managed
cluster tree and could leave a live JVM holding an unlinked lock inode.

## Evidence

On 2026-08-05, reset reported the unreadable claim
`data/operator/claims/processes/cd06bca9-d2d0-48ea-b99b-d5e4232facd0.edn`
and promised a flock-gated discard, then completed while leaving that claim
present. PID 97815 still matched the claim's exact start instant and held the
now-unlinked prior store-lock inode while a later JVM held the newly created
lock file.

## Owner

`script/seon/fresh_operator.clj` owns destructive reset sequencing.
`resources/seon/operator/state.clj` owns the shared canonical store-lock path.

## Acceptance

- Reset probes the canonical store lock with a non-blocking exclusive flock
  without opening Datahike or reading the store format.
- A held flock refuses loudly and leaves every unreadable claim untouched.
- A free flock permits deletion of only the enumerated unreadable claim paths,
  printing each exact path and validation reason.
- The reset then cleans, republishes `:current-src`, and reforks `default`
  without manual claim deletion.

## Resolution

Resolved by the rename pass Unit 10 commit. The focused regression held the
real lock from another process and observed the refusal, then proved that only
the enumerated unreadable file was removed. The live reset printed the exact
discard reason, reclaimed the managed root, published commit
`6a73834f-a9c9-5628-8ef6-339fa1bb950e`, and reforked `default` from commit
`6a738377-5197-5963-b31d-bf3ce2c2a1db` without manual file deletion.
