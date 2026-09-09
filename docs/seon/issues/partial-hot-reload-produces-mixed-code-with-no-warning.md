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

2026-09-09, bounded turn-rename lane: default PID 77143's failed proc move
left `seon.cluster.agent` unable to resolve the new `seon.turn/step` during
development adoption. After the draft was stashed, MCP runtime status still
refused `seon.cluster/readiness` output: its installed projection required
`:seon.turn/episode-runs` while the reader returned the old work key.
The namespace debug endpoint had returned HTTP 500. Fresh scratch adoption
of the draft converged, so this is a live reload boundary; it is not limited
to manual single-namespace REPL reload. Exact commits and source identities
are recorded in the turn rename landing note. The owner controls recovery.

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

### Live reload evidence, 2026-09-05

Default PID 68696: reapplying `seon.instrument/apply!` with the running
cluster's caps and error mode, but without its schema projection state,
exceeded the 60-second MCP timeout. The evaluation continued running.
A JVM thread dump showed `collect-contracts!` → Malli collection →
`schema/active-forms` → `candidate-forms` → `packaged-forms` →
`schema.edn/resource-population` → repeated schema file reads.
The snapshot is `tmp/design-lab-instrument-threads.json`; reproduce by
calling instrumentation outside `schema/call-with-projection-state`.
This is a missing-input boundary defect, not evidence that a larger timeout
is appropriate. Refuse missing projection before collection, and document
the complete cluster-scoped call in the REPL skill.

An independent stale-request defect also surfaced in this session:
an open debug registration predating cursor support supplied nil to
`seon.render.data/at`. Its contract failure aborted the shared render pass,
leaving unrelated tabs stale. Normalize the optional cursor at its consuming
boundary and prove the still-open stream receives the next update.

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
one bounded `:seon.config/missing-effective` error. The new cluster's real SCI evaluator
still exercised the old Var:

```clojure
(seon.config/effective (seon.db/db) "dogfood-missing-cluster")
⟹ {}
```

The naked empty map is exactly the old misleading face: it neither says the
cluster is absent nor lists the available cluster names. This proves the
staleness surface can hide a newly landed agent-facing diagnostic even when
the operator just reported a successful new-cluster boot.

## Juniper development UI, 2026-09-06

After host reloading the context selection UI in the isolated
`tmp/juniper-context-live` root, the open debug page showed a typed invalid
read: `:seon.context.contribution/agent` was absent from the cluster schema.
The new host code loaded successfully, while the cluster still held its older
indexed source/schema commit. HTTP 200 did not establish a working page.
The browser's old numeric subject also resolved to a maintenance schedule
following an earlier refork; the namespace URL without `subject` correctly
selects the current owner.

The owner now requires an opted-in dev cluster to retain its agent facts and
converge functions, schemas, tests, loaded behavior, and UI automatically on
source publication. Repeated destructive refork is not the remedy. An Astra
implementation is extending the existing publication/indexing owners; until
its live proof passes, host reload alone must not be reported as convergence.

## Publication now validates before development reload, 2026-09-06

`seon.fresh-operator/init-form` previously issued a fixed sequence of
`require :reload` calls before `seon.cluster/refresh-source!` built or validated
the stable filesystem manifest. A syntax or schema failure could therefore
leave the live JVM running changed code even though publication refused. The
operator now only ensures the existing publication owners are loaded; the
stable-manifest and scratch-branch publication owners decide first, and the
existing development reconciliation path reloads admitted changed namespaces.
A focused generated-form regression proves that the publication form contains
no `:reload` operation and still invokes the one `refresh-source!` validator.

This does not make Clojure namespace loading transactional. Once validation
succeeds, a load-time exception in a changed namespace can still leave Vars
from earlier forms in that namespace updated. The reconciliation owner must
eventually make that admitted-generation transition coherent; reintroducing a
pre-validation reload would only move the same risk earlier.

The first live publication after removing the reload roster refused an invalid
incremental manifest before any reload. The diagnostic showed a nil
`:seon.ns/name`, but that was the first failing branch of Malli's row union, not
an actual nil namespace in the manifest. Inspecting the unwrapped
`build-manifest` result found 246 artifacts and no nil namespace identity. The
real rows lacked the required `:seon.schema.admission/source` provenance, and
artifact call edges retained analyzer vectors even though the declared
cardinality-many `:seon.fn/calls` value is a set. Canonical artifact admission
now adds `:core` provenance and normalizes call edges at that boundary. The
focused artifact regression asserts both facts. This was a dishonest producer
shape exposed by universal output instrumentation, rather than stale schema
validation.

The next incremental adoption exposed the same nil-as-absence defect in its
change request: absent current and desired artifacts were stored as optional
keys with nil values. The refresh planner now omits those keys, so the existing
`plan-file-change` contract receives the exact open-map shape it declares.

The cached-manifest admission now validates both the process-local analysis
cache and the persisted source artifact against
`:seon.fn.manifest/manifest`. An invalid cache takes the existing complete
scratch-build path before any incremental function-symbol query. The focused
regression uses the observed malformed artifact shape and proves
`manifest-function-symbols` is never reached. This keeps instrumentation strict
and makes an older or corrupt incremental cache disposable rather than an
authority.
