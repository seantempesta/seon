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

A later live probe found the corresponding client-closure failure: ACME's
watcher had successfully hot reloaded newer flavor-owned output while status
still accepted the older published client digest. The operator now derives the
current client digest from both the flavor's client output and its Shadow
runtime directory. Watcher readiness fails closed when those bytes drift, and
status exposes only non-secret process identity digests plus the PID start
stamp. The running ACME target consequently changed from false-ready to
degraded without a restart.

The first default startup after that fail-closed check exposed a deeper
publication-order defect. The source build used a one-shot Shadow `compile`
and published client digest `72f21886…`; the newly started managed watcher then
completed `:client` and `:test` with digest `9be6d787…`, so watcher readiness
correctly remained false and the writer/pod never started. Shadow's one-shot
dev build has no worker information. A watch worker adds its process id, server
token, worker client id, and Node devtools client during configuration, so the
two closures cannot be byte-identical while live reload is enabled. The owning
source is `shadow.cljs.devtools.api/compile*`,
`shadow.cljs.devtools.server.worker.impl/build-configure`, and
`shadow.build.targets.node-script/configure` in `reference-code/shadow-cljs`.

## Bounded implementation

Artifact manifest version 6 records a content-addressed runtime root. A source
build copies the completed bootstrap, execution entry file, and all JavaScript
modules imported by that entry file into one checkout-layout-preserving
directory. The root identity includes the bootstrap digest, raw execution-entry
digest, and complete imported-runtime digest. Publication verifies all three
before its atomic move and refuses an existing content address whose bytes do
not verify. Development-only links retain the current source and asset behavior
without copying that mutable corpus into this runtime-focused slice.

`seon.dev.process/specs` injects the manifest's runtime root only into the pod
environment. The runtime's existing `SEON_RUNTIME_ROOT` path mechanism
therefore resolves the immutable bootstrap without a second loader or eval
path. The bootstrap and execution-entry digests are also part of the pod spec,
so readiness hashes the exact published members and fails when their bytes
differ. The launch descriptor replaces the watcher's mutable execution path
with the manifest's immutable path only after proving that the source descriptor
selected the configured flavor and build. Only the current manifest format is
readable.

The client now has one output owner as well. A source artifact build prepares
the writer, bootstrap, and CSS, then starts the managed Shadow watcher while
still holding the checkout artifact lock. The watcher completes every
flavor-owned build before `seon.dev.artifact` hashes its actual client closure
and publishes the manifest once. Its process record carries a temporary,
non-admitted marker only inside the signal-safe startup bracket;
`admit-watcher-artifact!` re-hashes the current closure and atomically binds the
published digest to that same live process before ordinary process
reconciliation can admit it. No one-shot client compiler or post-mutation
manifest refresh remains.

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
  target's bootstrap and execution bytes remain unchanged after the second
  build.
- Simultaneous default and ACME proof reports the on-disk bootstrap digest each
  pod actually resolves and matches it to that pod's manifest.

## Verification

- Focused artifact/process tests pass 75 tests and 357 assertions.
- A deterministic sequential-flavor test publishes default bytes, changes the
  mutable build output, publishes ACME bytes, and proves both content-addressed
  roots retain their own digest and content.
- A process test proves only the pod receives the manifest runtime root and
  that readiness changes from true to false after its bootstrap member is
  mutated.
- Client-closure tests prove the digest owns both output and Shadow runtime
  bytes, and that a completed watcher becomes unready after either admitted
  closure changes. A live `bin/acme status --edn` probe reports the same target
  degraded while leaving its writer and pod alive.
- Focused operator tests prove source publication orders static build, managed
  watcher flush, digest derivation, and one manifest publication; rejects a
  source build without that watcher owner; admits the watcher only when its
  current bytes equal the published digest; rejects drift between flush and
  admission; and reverses a newly acquired watcher if publication fails.
- The final simultaneous default/ACME rebuild and resolved-path evidence remain
  open acceptance work; no ACME process was disturbed by the default proof.
- Live default proof at `99afc40f` and `4f3db199` found and corrected two
  distinct identity defects: the old operator digest included a path while the
  Bun child hashed raw bytes, and Shadow's development entry imports a separate
  841-module runtime rather than being self-contained. A clean supervised
  restart now launches from the immutable complete closure. The manifest's raw
  execution digest matches `shasum -a 256` of its exact entry file, and a real
  root render completes without an identity rejection, missing import, or
  pre-ready child exit.
