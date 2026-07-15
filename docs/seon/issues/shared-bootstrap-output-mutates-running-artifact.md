---
type: issue
status: open
severity: blocker
tags: [issue, component, cljs, flow]
---

# Keep a running target's bootstrap artifact immutable

## Problem

Default and ACME publish different manifests but both builds replace the same
`out/bootstrap` directory. The checkout-global source-build lock serializes
those writes, yet a later flavor build can still mutate the self-host input of
an already-running pod after that pod's manifest was admitted.

## Evidence

The ready default manifest records bootstrap digest `58401112…` and publication
time `22:53:36`. The ready ACME manifest records bootstrap digest `f0a30715…`
and publication time `22:55:37`. Current files under `out/bootstrap` were last
written at `22:55:34`, during the later ACME build.

`seon.dev.artifact/build-source!` invokes the flavor's Shadow command for the
shared `bootstrap` build, and `output-manifest` hashes the fixed
`out/bootstrap` path. `seon.eval/init-bootstrap!` later resolves that same path
through `seon.platform/artifact-path`; the default process is still reported
ready even though the bytes at its runtime path no longer match its manifest.

The source-artifact lock in `bf8cf3b5` correctly prevents concurrent fixed-path
corruption and canonicalizes the writer jar. It does not establish
post-publication immutability for bootstrap or other shared runtime members.

## Bounded implementation

Artifact manifest version 3 now records a content-addressed runtime root. A
source build copies the completed bootstrap into
`tmp/seon-runtime-artifacts/<bootstrap-digest>/out/bootstrap`, verifies the
copy against the digest before atomic publication, and refuses an existing
content address whose bytes do not verify. Development-only links retain the
current source and asset behavior without copying that mutable corpus into this
bootstrap-focused slice.

`seon.dev.process/specs` injects the manifest's runtime root only into the pod
environment. The runtime's existing `SEON_RUNTIME_ROOT` path mechanism
therefore resolves the immutable bootstrap without a second loader or eval
path. The bootstrap digest is also part of the pod spec, so readiness hashes
the exact published directory and fails when its bytes differ. Version 1/2
manifests remain readable for already-running targets; the next source build
publishes version 3.

## Owner

The artifact coordinates and runtime-root contract in
`seon.dev.artifact`, `seon.dev.config`, `seon.dev.process`,
`seon.platform`, and `seon.eval`, with the independent downstream distribution
PRD owning the immutable package form.

## Acceptance

- A running pod resolves the exact bootstrap member bound by its admitted
  artifact manifest for its whole process lifetime.
- Compatible targets either reuse one verified byte-identical canonical
  bootstrap or publish to distinct/content-addressed roots; a later build never
  mutates another target's admitted path.
- The pod's process identity changes when its bootstrap identity changes.
- Deterministic tests publish two flavor manifests and prove that the first
  target's bootstrap bytes and resolution remain unchanged after the second
  build.
- Simultaneous default and ACME proof reports the on-disk bootstrap digest each
  pod actually resolves and matches it to that pod's manifest.

## Verification

- Focused operator tests pass 21 tests and 83 assertions.
- A deterministic sequential-flavor test publishes default bytes, changes the
  mutable build output, publishes ACME bytes, and proves both content-addressed
  roots retain their own digest and content.
- A process test proves only the pod receives the manifest runtime root and
  that readiness changes from true to false after its bootstrap member is
  mutated.
- No pod was restarted or reset in this bounded unit. The final simultaneous
  default/ACME rebuild and resolved-path evidence remain open acceptance work.
