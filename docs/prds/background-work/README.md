---
type: prd
status: active
tags: [prd, agent, flow, runtime, database]
queue: [messaging-implementation-wave, green-bare-gate]
---

# Agent-facing background work

## Current decision

Background work remains an explicit execution mode of the one durable effect
request, not a job abstraction. `(my.background/background (f request))`
accepts one direct declared capability call, opens the ordinary `seon.effect`
receipt before dispatch, submits the handler to the cluster work launcher's
bounded `:io` arm, and returns the receipt lookup ref without waiting. The same
effect identity, handler declaration, settlement transaction, and interruption
law serve foreground and background calls (`src/my/background.clj:17-34`;
`src/seon/effect.clj:418-552`).

The landed implementation is deliberately narrower than arbitrary detached
evaluation. It does not create a scheduler, a job entity, a second guarded SCI
evaluation, or a replay path. Capability ownership is the declared
`:seon.effect/capability` fact, while workload remains a separate derived
program fact (`src/seon/effect.clj:140-158,437-460`;
`docs/seon/architecture/toolkit.md:131-148`).

This PRD stays **active but queued**. Its execution and persistence mechanisms
have landed; its agent lifecycle surface is paused on the
`messaging-implementation-wave`, then the program's green bare gate. The
current `my.run/wait`, `my.run/complete`, and `my.message/send` source still
implements the pre-2026-08-06 contracts (`src/my/run.clj:16-47`;
`src/my/message.clj:17-59`). The owner has now ruled target-aware wait-on-send,
one admitted value for send and complete, explicit addressing, no inferred
replies, and `result/<eid>` eval handles
(`docs/prds/sci-execution-runtime/plan/README.md:778-817`).

## Current implementation evidence

| Boundary | Current owner and evidence | Status |
|---|---|---|
| Explicit agent surface | `my.background/background`, `poll`, and `await` are implemented at `src/my/background.clj:17-97`; their schemas are `resources/seon/schemas/my.background.edn:1-27`. | Landed, but `await` uses the pre-redesign wait contract. |
| One effect receipt | `seon.effect/open-call`, `settle-call`, `interrupt-call`, and recovery stamps own open-before-dispatch, settle-once, and no-refire at `src/seon/effect.clj:194-312`; `request!` owns foreground/background dispatch at `src/seon/effect.clj:418-566`. | Landed. |
| Declared capability facts | Handler resolution reads `:seon.effect/capability` from the function row at `src/seon/effect.clj:437-460`; indexing lifts that fact at `src/seon/fn.clj:303-356`. | Landed. |
| Bounded IO execution | The work launcher declares an `::io-submission` arm at `src/seon/flow.clj:383-469`, constructs its bounded buffer from config at `src/seon/flow.clj:496-528`, and exposes non-waiting `submit!` at `src/seon/flow.clj:610-650`. | Landed. |
| Stop completion | Launcher stop closes admission, settles/cancels accepted IO work, awaits drain and proc completion at `src/seon/flow.clj:530-608`; cluster stop invokes it before connection release at `src/seon/cluster.clj:2238-2266`. | Landed. |
| Terminal delivery | `:seon.effect/to` is in the computed wake set and routes through the existing mailbox at `src/seon/cluster/wake.clj:78-93,224-240`. | Landed. |
| Run attachment | The run transaction attaches every unanswered terminal result in settlement order at `src/seon/cluster/run.clj:234-290`; work derivation opens a result-only run at `src/seon/cluster/work.clj:567-627`. | Landed. |
| Binary result tier | Binary staging, publication, and bounded reads are owned by `src/seon/blob.clj:185-312`; effect settlement uses that owner at `src/seon/effect.clj:331-384`. | Landed. |
| Volatile context pane | Pending receipts, attached results, and long foreground-call feedback derive in `src/seon/effect.clj:568-657` and render after the stable prefix at `src/seon/render/walk.clj:625-643`. | Landed; its guidance text must follow the messaging decision below. |

The focused proofs cover macro shape and non-acknowledging poll/await
(`test/my/background_test.clj:7-52`), result-only run opening and suffix
placement (`test/seon/background_test.clj:11-86`), durable exact binary results
across the inline threshold (`test/seon/background_blob_test.clj:89-164`),
effect once-only and recovery behavior (`test/seon/effect_test.clj:64-116,138-208,243-285`),
and bounded nonblocking IO plus joined stop (`test/seon/flow_test.clj:336-379`).
These are source-level proofs, not the still-required isolated-cluster
graduation proof.

## Re-grounding: stale pre-refresh claims

The following statements were removed or reframed. “Pre-refresh” line numbers
identify the 2026-08-03 README version that this refresh replaces.

| Stale claim | Current evidence |
|---|---|
| The effect owner did not exist and had to land first (`docs/prds/background-work/README.md`, pre-refresh lines 78-81, 451-454, 562-567). | `src/seon/effect.clj:1-7,194-312,418-566` is the current effect owner; `resources/seon/schemas/seon.effect.edn:18-94` declares its receipt and transition shapes. |
| The work launcher admitted only compute and still needed an IO arm and `submit!` (`docs/prds/background-work/README.md`, pre-refresh lines 82-83, 458-460). | The IO input, bounded admission, execution, and public `submit!` are current at `src/seon/flow.clj:383-469,496-528,610-650`. |
| Wake routing and run opening still needed `:seon.effect/to` and `:seon.cluster.run/background-results` (`docs/prds/background-work/README.md`, pre-refresh lines 83-86, 468-471). | Routing is current at `src/seon/cluster/wake.clj:78-93,224-240`; unanswered attachment and result-only runs are current at `src/seon/cluster/run.clj:234-290` and `src/seon/cluster/work.clj:567-627`. |
| `seon.blob` accepted only complete UTF-8 strings and lacked staged binary writes and chunk reads (`docs/prds/background-work/README.md`, pre-refresh lines 85-86, 455-457, 514-518). | `stage-binary!`, `with-publication!`, `put-binary!`, and `read-chunk` are current at `src/seon/blob.clj:185-312`; the exact-byte regression is `test/seon/background_blob_test.clj:89-164`. |
| Launcher stop merely called nonjoining `flow/stop` and connection safety was future work (`docs/prds/background-work/README.md`, pre-refresh lines 87-88, 510-513). | `stop-work-launcher!` awaits both the submission drain and proc completion at `src/seon/flow.clj:584-608`; `src/seon/cluster.clj:2262-2266` orders that join before connection release. |
| Architecture still described replay classes and needed reconciliation (`docs/prds/background-work/README.md`, pre-refresh lines 89-93). | The current target explicitly says nothing re-executes and there are no replay classes at `docs/seon/architecture/toolkit.md:131-148`. |
| The old shell quarry was a current negative source reference (`docs/prds/background-work/README.md`, pre-refresh lines 533-536). | `src-old/` and `test-old/` were deleted; the ruling records Git history as the archive at `docs/prds/sci-execution-runtime/plan/README.md:560-576`. The current negative boundary is the surviving one-effect implementation above. |
| The seven-step implementation order presented every background mechanism as unbuilt (`docs/prds/background-work/README.md`, pre-refresh lines 450-474). | All seven source mechanisms now exist. What remains is lifecycle/handle reconciliation and integrated live proof, not another implementation of receipts, launcher IO, blobs, wake routing, or context. |

The rest of the original decision was verified and survives: explicit rather
than automatic backgrounding, one direct capability call, no arbitrary SCI
body, one receipt identity, terminal facts instead of a status enum, no
acknowledgement fact, no synchronous wait in SCI, payload-free losable wakes,
and no refire after interruption. Their current owners are the source paths in
the implementation table, rather than the superseded design report.

## Current dependency edge

### Landed dependencies this PRD builds on

- The no-replay effect contract is sealed in
  `docs/seon/architecture/toolkit.md:131-148` and implemented by
  `src/seon/effect.clj:194-312,418-566`.
- The declared capability graph is indexed by `src/seon/fn.clj:303-356` and
  queried by `src/seon/effect.clj:140-158,437-460`; no name, namespace, or
  workload hand list is a dependency.
- The bounded cluster work launcher and its root executors are the sole
  process-local execution owner (`src/seon/flow.clj:383-469,496-650`).
- Background results use the existing per-agent graph and mailbox. Durable
  facts are `:seon.effect/notify`, terminal `:seon.effect/to`, and the reverse
  `:seon.cluster.run/background-results` connection
  (`resources/seon/schemas/seon.effect.edn:18-94`;
  `resources/seon/schemas/seon.cluster.run.edn:1-61`).
- Binary results use the one staged blob publication and sweep permit path at
  `src/seon/blob.clj:185-312`; background work does not own another blob path.
- The owner approved explicit backgrounding, duration feedback, and the
  volatile background pane on 2026-08-03; those requirements are implemented
  by `src/seon/effect.clj:568-657` and remain additive.

### Genuine active-queue waits

1. **Messaging implementation wave.** It must first replace the current
   text-only `send`/`complete`, inferred-reply, and note-only wait contracts
   with the 2026-08-06 one-value and target-aware lifecycle. Background work
   must consume that settled contract; it must not preserve a parallel form of
   the old `:my.run/note` wait (`src/my/run.clj:16-47`;
   `src/my/message.clj:17-59`;
   `docs/prds/sci-execution-runtime/plan/README.md:790-817`).
2. **Result-handle seam and identity verdict.** The ruled `result/<eid>` eval
   handle still has an in-flight SCI resolution probe and id archaeology
   verdict (`docs/prds/sci-execution-runtime/plan/README.md:778-789`). The
   background surface cannot choose whether its durable effect lookup ref is
   separate, aliased, or rendered through that syntax until the owner rules
   the question below.
3. **Green bare gate on a coherent tree.** After messaging and handle
   reconciliation, run the focused background/effect/flow tests and the bare
   gate before the live graduation drill. Foreign deletion and sweep slices
   are shared-tree weather, not semantic background dependencies; they matter
   only to obtaining a coherent checkpoint.

## Effect of the 2026-08-06 messaging redesign

- **One value for send and complete:** capability handlers already settle one
  admitted value, so receipt settlement does not change. The result must stay
  data; it must not be coerced into message or reply text.
- **Wait on the send's return value:** current
  `(my.background/await result-ref note)` calls the old
  `(my.run/wait note)` and is therefore not the target contract
  (`src/my/background.clj:79-97`; `src/my/run.clj:16-30`). Background awaiting
  needs the owner ruling below before it can be rewritten.
- **Explicit addressing and no inferred reply:** background settlement already
  records its initiating agent explicitly through `:seon.effect/notify` and
  terminal `:seon.effect/to`; it does not use derived-reply synthesis
  (`src/seon/effect.clj:477-520,250-287`). This assumption survives.
- **`result/<eid>` handles:** current background calls return the durable
  `[:seon.effect/id effect-id]` lookup ref, while the August 6 ruling describes
  eval receipt handles derived from Datahike entity ids
  (`src/seon/effect.clj:469-521`;
  `docs/prds/sci-execution-runtime/plan/README.md:778-789`). The old README's
  generic “result ref” wording no longer decides whether these are one syntax.
- **Bare wait is refused:** background work never needs a bare wait, but its
  old note-only wait is no longer sufficient merely because it is nonblank.
  The lifecycle must name the actual effect receipt or a value derived from it.

## Remaining proof and exit

After the two owner rulings and messaging implementation land:

1. update the background lifecycle surface and volatile guidance without
   changing effect receipt identity or settlement;
2. prove target-aware background waiting closes the current run, consumes no
   thread, and opens exactly one fresh result run when terminal facts commit;
3. prove continuing work still receives every unanswered result once even when
   a sliding-1 mailbox coalesces terminal wakes;
4. rerun exact binary results on both sides of the inline threshold and joined
   launcher stop; and
5. in an isolated operator root, interrupt one active background effect and
   query that it became terminal without refire while a second effect settles
   and renders with its ruled handle.

This PRD exits only after those proofs, the focused suites, and the green bare
gate pass on one coherent published source commit. It does not wait for a new
effect owner, blob tier, launcher graph, wake path, or background-result schema;
those dependencies are already landed.

## Open design questions (2026-08-06)

1. **How does a background receipt participate in target-aware wait?**
   **Option A (recommended):** make the durable effect ref itself an admitted
   wait target, so the form is `(my.run/wait effect-ref)`; this gives send and
   background work one lifecycle primitive but widens the wait target schema.
   **Option B:** make `my.background/await` return a distinct wait-target value
   consumed by `my.run/wait`; this keeps effect-specific validation local but
   adds one wrapper concept. **Option C:** retain the current
   `my.background/await ref note` disposition as a separate wait arm; this is
   the smallest source edit but preserves parallel lifecycle syntax after the
   one-wait redesign.
2. **What handle should a completed background effect render and accept?**
   **Option A (recommended):** render `result/<effect-receipt-eid>` and let it
   resolve to the existing effect receipt entity; agents learn one handle
   syntax and no new identity is allocated, but `poll` must accept that ordinary
   resolved value. **Option B:** keep `[:seon.effect/id effect-id]` for durable
   capability receipts and reserve `result/<eid>` for eval receipts; the data
   models stay explicit, but agents must learn two result-reference forms.
   **Option C:** render both as aliases for one transition period; discovery is
   easiest, but it creates a temporary dual surface contrary to the one-
   mechanism direction.
