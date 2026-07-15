---
type: issue
status: open
severity: blocker
tags: [issue, flow, pod]
---

# Freeze the restore pod artifact in confirmed intent

## Problem

The retained restore intent binds the exact writer jar but not the client,
bootstrap, or complete application artifact selected for reconstruction. A
source-only rebuild can publish a new manifest whose writer digest is unchanged;
`restore!` then accepts that manifest and may start the restore pod from client
bytes that were not part of the confirmed plan.

Human source-freeze coordination narrows the risk but is not durable crash
recovery authority. A retained intent must reject executable drift by itself.

## Original evidence

`src/seon/dev/restore.clj` currently requires only
`:seon.dev.restore/writer-artifact-digest`. `derive-initial-intent!` copies that
one value from the manifest. On resume, `script/seon/dev/restore_state.clj`
compares the retained writer digest with the newly read manifest and current jar,
but passes the newly read manifest's application/client/bootstrap values into
`seon.dev.process/specs`.

The watcher dependency proves current client bytes against the manifest supplied
to that invocation. It does not prove that this is the manifest confirmed by
the retained intent. Therefore a new source-only manifest can be internally
consistent and still cross the confirmed restore generation.

## Current implementation

The immutable intent now carries one closed artifact identity containing the
application, client, bootstrap, CSS, and writer digests. The sole plan digest
commits that complete map. Planning and applying independently hash the current
output closure and require equality with the published manifest. Resume repeats
that proof immediately, stops the watcher with the pod and writer before `U`,
rehashes the frozen closure after the watcher is terminal, and rechecks it
again immediately before spawning the restore pod.

Focused artifact proof is green at 18 tests and 66 assertions; the combined
restore and CLI gate is green at 51 tests and 221 assertions. The issue remains
open until crash retry and a source-edit/live-watcher falsifier prove the same
closed behavior against a real retained intent.

## Owner

The one `seon.dev.restore` immutable intent and
`seon.dev.restore-state` plan/apply/resume boundary, consuming the canonical
artifact manifest and existing on-disk digest functions.

## Acceptance

- The read-only plan freezes the exact application, client, bootstrap, CSS, and
  writer digests needed by the restore runtime, or one closed canonical artifact
  identity that contains those values.
- Apply freshens and requires whole-plan equality before publication.
- Resume requires the current manifest to equal the frozen artifact identity and
  independently hashes every executable/output component before starting a
  writer, watcher, or pod.
- Watcher output drift with an unchanged writer jar fails before process start;
  a newly published manifest cannot silently widen a retained intent.
- Crash/retry and live source-edit falsifiers prove the same retained generation
  either reconstructs from exact frozen bytes or stays closed.
