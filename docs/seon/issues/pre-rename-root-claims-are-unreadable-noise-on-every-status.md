---
type: issue
status: open
severity: friction
tags: [issue, operator, boot]
---

# Pre-rename root claims are unreadable noise on every `status`

## Problem

`bin/seon status` prints one `record unreadable …: The external claim is
invalid.` line per stale root claim, before any of its actual output. The
claims are not corrupt: they were written before the rename pass and record
the creator as `#:seon.dev.process{:pid … :start-instant …}`, while
`root-claim?` now requires `:seon.boot/pid` / `:seon.boot/start-instant`.

Every lane pays this on every status, and it teaches the reader to skim past
refusals — the diagnostic-quality cost the "loud failures" ethos is meant to
prevent. Most of these roots (`tmp/o4-delegation-diagnosis-root` and
friends) no longer exist.

## Evidence

Observed 2026-08-08 04:31 while proving an unrelated operator fix:

```text
record unreadable /Users/sean/src/seon/data/operator/claims/roots/5387ef50-….edn: The external claim is invalid.
… four more …
orphan seon JVMs: none
```

The named file:

```clojure
#:seon.operator.claim{:root "…/tmp/o4-delegation-diagnosis-root"
                      :creator #:seon.dev.process{:pid 4883
                                                  :start-instant "2026-08-05T15:19:11.630Z"}
                      …}
```

`:start-instant` is also a STRING there, where the current shape is an inst.

## Owner

`resources/seon/operator/state.clj` (`root-claim?`, `read-claim-records`) and
whoever owns claim hygiene for roots that no longer exist.

## Acceptance criteria

- A stale claim for a root that no longer exists is reclaimed rather than
  reported forever — operator state is disposable, so deletion is the
  expected repair, not migration.
- What remains unreadable says WHICH key it did not recognise, so the reader
  can tell a rename leftover from a truncated write.
- `bin/seon status` on a healthy installation prints no refusal lines.
