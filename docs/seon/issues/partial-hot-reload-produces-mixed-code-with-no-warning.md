---
type: issue
status: open
severity: friction
tags: [issue, runtime, operator, class/n3, wave/general]
---

# Partial hot reload leaves a live JVM running mixed old and new code

## Current scope

The 2026-07-30 source-publication replacement removed this risk from database
program indexing: clj-kondo analyzes files without evaluating application
source, and `current-src` publication refuses if the running JVM lacks a newly
added analyzer dependency. This issue remains open only for deliberate REPL
hot reload of running behavior, where reloading one caller still does not
reload its callees or reapply instrumentation automatically.

## Problem

`(require 'ns :reload)` reloads ONE namespace, not its dependencies. A live
JVM can therefore end up with a NEW caller and an OLD callee, and the only
symptom is a confusing failure deep inside the callee.

Observed 2026-07-29 while priming the owner's live cluster (pid 8515), which
had been running since before the priming code existed:

1. `bin/seon index default` → `No such var: seon.cluster/index!` (the JVM
   predated the function; the code was correct in the tree).
2. `(require 'seon.cluster :reload)` fixed that, and then:
   `seon.fn/index! violated its contract (invalid-input):
   #:seon.ancestor{:digest ["disallowed key"]}` — the reloaded
   `seon.cluster/index!` passed a key that the STALE `seon.fn`'s closed
   input schema did not yet allow.
3. Reloading `seon.fn` as well, then re-running `seon.instrument/apply!`,
   made `bin/seon index default` succeed (730 operations, then
   `:converged? true, :operations 0` on the second run).

Armed instrumentation caught the skew, which is the system working as
designed — without it, a new caller could have transacted against an old
contract silently. But the diagnosis took three attempts and the error named
a schema rather than the actual problem ("your JVM is running mixed code").

## Owner

The reload/staleness seam — the MCP eval path and/or `seon.instrument`, which
already knows every instrumented var and could compare what is loaded against
what is on disk.

## Acceptance

- A live JVM can be asked whether any loaded namespace is stale relative to
  the source tree, and the answer names the namespaces to reload.
- A contract violation whose cause is version skew says so, rather than only
  naming the schema that refused.
- Reloading a namespace re-applies instrumentation for it automatically, or
  the operator/eval path tells the caller to (today it must be done by hand,
  which is easy to forget and produces the same confusing failure).

## Related

- The "long-lived JVM serves the code it loaded at startup" wart, documented
  in `docs/TRANSFER_PROMPT.md` and the flow skill's degraded-start reference.
- [[a-test-fixture-deleted-tracked-files-through-symlinks]] — the same
  evening, the same theme: a mechanism that is correct in isolation behaving
  surprisingly in a live, shared, long-running environment.

## Data-session dogfood, 2026-08-04

`bin/seon start codex-repl-dogfood-0804` created a new sovereign branch but
added it to the existing JVM at PID 3885. `runtime_status` immediately reported
15 `:seon.problems/stale-vars`.

Current source at commit `89fe1a287` makes an absent configuration row return
one bounded `:seon.config/missing-effective` error. The new cluster's real door
still exercised the old Var:

```clojure
(seon.config/effective (seon.db/db) "dogfood-missing-cluster")
;; => {}
```

The naked empty map is exactly the old misleading face: it neither says the
cluster is absent nor lists the available cluster names. This proves the
staleness surface can hide a newly landed agent-facing diagnostic even when
the operator just reported a successful new-cluster boot.
