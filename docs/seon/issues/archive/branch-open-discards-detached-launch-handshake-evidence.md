---
type: issue
status: resolved
severity: friction
tags: [issue, runtime, tooling]
---

# Preserve detached launch handshake evidence on branch open failure

## Problem

`bin/seon branch open <name>` can fail with `Failed to launch a detached Seon
process` while the reported `:seon.dev.process/error` is empty. The detached
helper's exit status, stdout, parsed descriptor, and failed handshake field are
discarded, so the operator cannot distinguish helper failure from a contract
mismatch after successful publication.

This currently masks the prerequisite for reproducing the separate
[[branch-qualified-eval-cljs-database-read-stays-pending]] defect.

## Evidence

On 2026-07-21, both `bin/seon branch open verify-branch-eval` and
`bin/seon branch open brread` failed at `script/seon/dev/process.clj`'s detached
launch validation with an empty error string. Default stayed ready and neither
attempt left retained branch intent. The validation only places trimmed stderr
in exception data even though its decision also depends on the helper exit,
stdout JSON, and eleven parsed descriptor fields.

## Owner

The detached-process launch handshake in `script/seon/dev/process.clj` and its
focused operator tests. Strengthen that owner in place; do not add branch-only
launch or logging behavior.

## Acceptance

- A failed detached launch reports bounded, structured evidence that identifies
  whether the helper failed, emitted invalid JSON, or violated a named handshake
  field.
- A focused test proves empty stderr cannot erase the decisive launch evidence.
- `bin/seon branch open` starts a retained branch pod or reports the exact
  violated launch contract, and failure cleanup leaves no process or branch
  residue.

## Resolution

The detached launch exception now retains the helper exit, bounded stderr and
stdout, parsed launch descriptor, and expected generation, socket, result,
application-result, and shutdown-grace fields. Diagnostic text is capped at
4,096 characters and has focused nil, whitespace, and oversized-input proof.

The launch failure itself was transient: three subsequent real branch launches
reached ready with valid handshake descriptors and closed cleanly. The complete
operator gate passes 292 tests and 1,634 assertions.
