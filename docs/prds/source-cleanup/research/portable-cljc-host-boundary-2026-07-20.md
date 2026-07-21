---
type: research
status: complete
tags: [research, prd, runtime, architecture]
---

# Portable CLJC and host boundary audit

## Decision

The observed `src/my` `.cljs` to `.cljc` translations were not an independent
source-cleanup unit. They were an incomplete fragment of SCI execution-runtime
U5, the host toolkit port. They must not be restored or committed by
themselves.

At audit HEAD `73672f34`, every questioned source path is clean. The five
deleted/untracked translation pairs and the related `src/seon/host/context.clj`
diff recorded earlier in
[[stage2-freeze-readiness-refresh-2026-07-20]] are absent, and current Git
history contains no commit that owns those translations. This report therefore
does not infer who created or removed them and does not claim to review their
lost bytes. It determines the semantic boundary from the durable path census,
the current source, and the active SCI roadmap.

The active authority is [[../../sci-execution-runtime/roadmap]] U5, not a new
source-cleanup issue. Source-cleanup Stage 2 cared about the edits only because
an unidentified moving source boundary prevented its atomic freeze. With the
diff gone, that particular moving-source falsifier is resolved; the other
Stage-2 lifecycle, worktree, and terminology blockers remain unaffected.

## Preserved observation

The earlier read-only census observed these pairs at HEAD `c7700584`:

```text
D  src/my/kb.cljs                 ?? src/my/kb.cljc
D  src/my/kb/shared.cljs          ?? src/my/kb/shared.cljc
D  src/my/plan.cljs               ?? src/my/plan.cljc
D  src/my/plan/internal.cljs      ?? src/my/plan/internal.cljc
D  src/my/ui.cljs                 ?? src/my/ui.cljc

```

It also named a related `src/seon/host/context.clj` portability boundary but
explicitly found no durable owner. At this audit's HEAD all six areas have no
worktree diff, the tracked toolkit files retain their `.cljs` names, and the
only current `host/context.clj` changes are the committed U1-U4 series ending
at `b7808e35`.

## Exact semantic unit

U5 must make one dependency-coherent JVM-loaded toolkit, not mechanically
rename portable-looking files.

The current host loader in `seon.host.context` hard-codes eight `.cljs` source
paths, derives namespace names by stripping `.cljs`, splits sources into
top-level definition blocks, rejects blocks carrying async/JS/database markers,
and evaluates the remaining blocks in file order. Its own result ledger admits
that failures are retained rather than solved. C1 measured 42 candidate pure
blocks, only 25 loaded, and 17 failed. Those failures were unresolved private
helpers or aliases such as the database-ID, plan-internal, context-operation,
protocol, and UI helper families—not evidence that changing five filename
extensions alone makes the toolkit portable.

The corrected U5 row at `73672f34` therefore has the coherent order:

1. replace the block/file-order sketch with a dependency-ordered context
   loader derived from parsed namespace requires;
2. provision the complete database-ID, plan-internal, and context-operation
   families through U2's one wrapper registry;
3. only then promote the genuinely portable namespaces and implement the
   measured 17 standard-library shims plus three private capability
   implementations.

This is one mechanism strengthening. A second loader, a hand-maintained
dependency order, or a parallel host toolkit would conflict with the active
architecture.

## Dependencies and existing mechanisms

| Dependency or mechanism | Selected evidence | Constraint on U5 |
|---|---|---|
| SCI | `reference-code/sci` at `be4021d`, with JIT `45bcf0f`, selected by the `:host` alias | Contexts remain `sci/fork` values over one shared base; namespace provisioning uses SCI's one load function. |
| Wrapper registry | `src/seon/host/context.clj`, U2 commit `65bb052b` | `register-wrappers!` plus the shared `registry-load-fn` is the only capability binding path; host ports register there. |
| U4 corpus/replay | `b7808e35`, `seon.host.record`, and `restore-context-defs!` | Toolkit loading must preserve recorded namespace/require semantics and restart replay; it cannot invent a second corpus representation. |
| Require-edge data | `seon.host.record` namespace require-edge builders and `seon.eval`'s persisted `:seon.ns/require-edges` | Parse or reuse the existing concrete require shape, then topologically order candidates; do not create an umbrella dependency model. |
| C1 loader ledger | [[../../sci-execution-runtime/research/c1-jvm-host-scale-2026-07-20]] | The acceptance target is zero portable-loader failures, not a larger admitted block count. |
| C2 portability inventory | [[../../sci-execution-runtime/research/c2-js-bound-audit-2026-07-20]] | No agent-visible function genuinely requires JS eval; the remaining platform work is 17 small standard-library shims and three private capability implementations. |

## Architecture and sequencing ruling

The proposed translations align with the architecture only as outputs of U5:
`.cljc` is appropriate when both the Bun/CLJS and JVM host boundaries consume
the same pure mechanics. They conflict with the current architecture if landed
alone because `build-base!` still opens the old `.cljs` paths and the loader
still admits blocks before their private dependencies exist. A bare rename can
therefore turn the current honest partial ledger into an immediate missing-file
failure without closing a single C1 gap.

The SCI roadmap also records that U5 is held until U3 because both units touch
`src/seon/host/context.clj`. U4 itself is committed and verified; a new U5 edit
does not conflict semantically with U4 if it preserves the U2 registry and U4
record/replay contracts, but concurrent or unreviewed edits to that owner would
violate the shared-tree boundary.

No existing issue note found by the targeted durable-doc search claims these
five translations as a standalone defect. The roadmap already records both
the unit and the corrected design, so creating another issue would duplicate
authority.

## Required proof and commit boundary

The next owner should take U5 only after U3 releases `host/context.clj`, and
should release it as one coherent commit series whose first executable boundary
contains the loader plus every dependency needed by its admitted toolkit
slice. A commit containing only the five extension changes is not releasable.

Focused acceptance must prove work and behavior:

- the production `build-base!` report reaches zero failures for every toolkit
  function classified portable by the computed rule;
- a deterministic dependency-order test supplies candidates out of order and
  proves private helpers load before their public consumers without a hand
  list;
- fresh and already-forked contexts resolve representative pure, database,
  plan-internal, schema, and UI functions through the same registry/load path;
- restart replay still evaluates a pre-restart definition whose body calls a
  newly ported toolkit function;
- CLJ and CLJS tests both consume the promoted `.cljc` owners, so the rename
  cannot hide a platform branch; and
- `bin/test-writer` and `bin/test-cljs` pass. Because the unit changes the host
  base and replay inputs, rerun the combined host-kill/pod-restart drill before
  release; retain the zero-fact-loss and post-restart definition result.

Only after those proofs and an explicit path release may source-cleanup Stage 2
count the U5 paths as stable inputs to its later atomic rename freeze.
