---
type: issue
status: resolved
severity: friction
tags: [issue, render, web, runtime, wave/context-fixes]
---

# Preserve source execution identity through the actual page refresh

## Problem

A read-only MCP JVM evaluation triggers the existing runtime-evaluation render
signal. A watched debug page then submits the same authored source again,
creating another ordinary agent run even though its source and inputs did not
change.

## Evidence

The fresh `tmp/juniper-context-live` cluster on 2026-09-06 displayed repeated
Juniper identity runs after MCP reads. The existing invalidation owner already
retained source-run identities while discarding generated output
(`ab558c858`). However, `render-step` passed its runtime-evaluation flag through
`render-pass` into `page-refresh`, where both the debug and ordinary branches
replaced those retained calls with `{}` again. Source regeneration consequently
could not find the preserved run identity and submitted new work.

The previous `authored-source-invocation-reuses-one-stored-run-across-presentations`
regression exercised invalidation and then called `render-source-call` directly,
bypassing `page-refresh`. It now passes the invalidated state through the actual
runtime-evaluation page refresh before asserting a single submission.

The frozen pre-fix gate `run.piA8mT` reproduced exactly this failure, pooled
and in isolated confirmation: expected one submission, observed two. The
namespace ran 20 tests and 183 assertions with one failure and zero errors.
No source/SCI change was part of that frozen baseline.

The fresh indexed fork at commit `6a9de9bb-9859-5a42-8c5d-54b201562cf4`
proved the smaller identity source executes and stores the correct terminal
value. It also falsified completeness of the page-refresh fix: ordinary
settlement writes produced 41 source runs. A bounded read of the first run's
13 dependencies found exactly one stale dependency: call preparation's
`[:find (max ?tx) . :in $ ?sym :where [?function :seon.fn/sym ?sym]
[?function _ _ ?tx]]` query for `seon.cluster.agent/whoami`. The wildcard
attribute records `:all`, so the evaluation's own settlement invalidates it.
The other twelve dependencies, including the actual identity pull, remained
current. The owned cluster was stopped, preserving these facts.

The existing supplied-default basis already excludes its cache-coherence
reads from semantic evaluation evidence. Its sibling contract-basis query
now follows the same rule at one `contract-transaction` owner. The existing
evidence regression now covers cold plan compilation and warm revalidation,
plus a real supplied-default declaration change that must still invalidate.
`run.zXvdL8` passed all 16 call-preparation tests and 150 assertions with
zero failures or errors.

Gate `run.5AeVrO` passed the source-reuse regression but was not green:
79 tests, 527 assertions, ten failures and five errors across the broader
web namespace. Failures include superseded prospective-prompt markup and
source-execution fixtures with absent agents. They are not proof of this
fix and have not all been attributed against a frozen baseline.

## Follow-up: derive plan freshness from the acquired program identity

The compiled call-preparation plan contains insertion positions and argument
validators, not evaluation results. SCI's installed callable and the acquired
Malli projection do not already provide that plan. However, the separate
contract freshness query may be removable: `seon.sci.kernel/program-function`
already exposes the committed function row carried by the SCI context, and
committed installation updates it. The current hook reduces the callee to a
string before calling `plan`, which loses that identity. A bounded follow-up
should pass the acquired contract identity through this existing owner and
prove both live redefinition and standalone database callers before removing
the query; it must not add another cache. The immediate fix preserves current
plan semantics and excludes only the internal basis observation.

## Owner

`src/seon/render/web.clj` has one invalidation owner:
`invalidate-runtime-derived-state`. Downstream page and pass functions consume
that state without repeating cache clearing. Runtime evaluation still forces
derivation; regenerated source is reused only when the existing source,
producer, program/projection identity, agent/namespace, and stored read evidence
remain valid.

## Acceptance

- The existing source-reuse regression fails before the fix and passes through
  the actual page refresh afterward, with one source submission.
- A fresh watched Juniper page retains its run identity after a read-only MCP
  evaluation while continuing to repaint.
- Existing changed-code and closed-page invalidation behavior remains intact.

## Live resolution

On 2026-09-06 the owned `juniper-context` cluster was freshly forked from
commit `6a9deb67-3354-5d81-be93-c47a6ca95bf1`, program digest
`be49a7ffc93a1514627057eedc290b4f7a9f14efb4186d2d3589f3b64ee8165b`.
No Var was hot reloaded. The watched `/ns/my.agents.juniper/debug` page kept
exactly one terminal source run,
`source:b3ffc62b-2723-47ac-a884-af1ee23a0eb4`, while read-only MCP returns
requested refresh. The render proc advanced from 21 to 29 passes with one
watched page and its runtime-evaluation buffer drained to zero. All sixteen
stored read dependencies remained current. The stored evaluation contained
the executed two-line `render-identity-text` / `whoami` source and the correct
Juniper identity string, without an evaluation error.

The committed `render_source_reuse_probe_2026_09_06.clj` records the bounded
snapshot and refresh proof. This is an execution-reuse proof for that indexed
program; later UI cap edits require their own final published fork. The
broader web gate failures above remain an integration limitation, not a green
claim. The separate freshness-query dissolution remains a follow-up design.

## Unresolved follow-up: wildcard pull freshness

The resolution above remains valid for the stale call-preparation dependency it
measured. A later Juniper fallback source exposed a different conservative
dependency: `(seon.db/pull (quote [*]) 32367)` retained twelve dependencies,
and the only stale one was the pull's `:datahike.read/attributes :all` revision.
An unrelated transaction advances the database commit id, so that wildcard
read currently forces the otherwise unchanged source to execute again.

A wildcard makes `pull-spec-attribute-dependencies` return `:all`, and
`pull-dependency-plan` then cannot add a narrower entity-ref attribute set
(`reference-code/datahike/src/datahike/pull_api.cljc:107-180`). Seon's
`dependency-revision` represents `:all` with the whole committed database
commit id (`src/seon/db.clj:391-422`). Datahike's cache context maintains
attribute revisions and one conservative revision; it has no entity revision
index. Transaction reports contain changed datoms, but that history is not
retained in the read revision, so an entity-scoped freshness decision cannot
be derived from the current and retained cache contexts
(`reference-code/datahike/src/datahike/writing.cljc:576-605`).

The smallest shared exact fix is result-digest replay at the existing read
evidence owner. `append-pull-evidence!` already captures the exact replay
request and logical pull result (`src/seon/db.clj:332-350`), and
`read-evidence-current?` already replays a request when its revision differs
(`src/seon/db.clj:510-538`). Retain a SHA-256 digest of the canonical EDN result
beside durable read evidence, then compare it with the digest of the replayed
result when the `:all` revision changes. `seon.schema/canonical-data-string`
already gives maps and sets order-independent canonical bytes
(`src/seon/schema.clj:489-536`). This stores a fixed-size digest rather than a
possibly large wildcard result. An unchanged entity stays current across an
unrelated transaction; any changed value, added or retracted attribute, or
changed component expansion changes the replay result and invalidates it.
Values that cannot be represented as canonical EDN retain the current
conservative behavior.

The existing process-local retained-result path is deliberately limited to
bounded reads (`src/seon/db.clj:242-288,437-458`) and cannot solve durable
evaluation reuse: durable evidence intentionally omits the result, while a
wildcard pull is unbounded by that contract. Widening it would retain the giant
result the constraint excludes. Adding entity revision state to Datahike would
also solve this, but it adds a new maintained index and transaction-history
contract for a case the existing replay seam can decide exactly.

A focused regression should capture a wildcard pull of one entity, retain only
its request and digest, transact an unrelated entity, and assert current;
changing, adding, and retracting an attribute on the pulled entity must each
assert stale. A component child change should be included because wildcard
pull automatically expands component refs. The source-execution regression
then proves this freshness result prevents a second evaluation rather than
only proving the lower-level digest comparison.
