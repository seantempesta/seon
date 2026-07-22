---
type: research
status: complete
tags: [research, agent, database]
---

# Live namespaces render defect audit

## Finding

The `[namespaces]` failure in turn `va1uj4ncpg9u` is a frame-ceiling bug in
the namespaces block's selected database acquisition. It is not fallout from
the `seon.ns.source` extraction.

The coordinated checkpoint deliberately ran the writer with a legal 64 KiB
frame ceiling. The namespaces block still asks `db/execute-many` to return as
much as 3,735,552 shallow-weight units in one response, with a source comment
that explicitly budgets against a fixed 4 MiB frame. The selected pull member
alone permits 3,145,728 units, and the optional namespace catalog permits
another 524,288. The failing turn did not need the optional catalog: its deep
namespace pull already exceeded the negotiated frame.

The writer correctly replaces an oversized encoded response with the bounded
`frame-too-large` protocol failure. `seon.db/execute-many` converts that failed
protocol response into a `:seon.error/*` map, which has no `::db/results`.
`acquire-namespace-rows!` does not check the top-level error. It destructures
members from the absent `::db/results`, observes nil members, and then records
only `(::db/results selected)` as its error data. This produces the exact live
prompt text:

```clojure
{:seon.error/message "Namespace selected member failed."
 :seon.error/data nil
 :seon.error/kind :core-bug}

```

Thus the `nil` is a secondary error-reporting defect; it discarded the useful
frame-ceiling response. The primary defect is the unpaged response whose bound
exceeds the negotiated session frame.

## Evidence

- `src/seon/agent/ctx/namespaces.cljs:206-216` defines one deep pull selector
  containing namespace source, require-edge components, every reverse function
  (including function source), every reverse schema, and every reverse test.
  A single namespace row can therefore be large.
- `src/seon/agent/ctx/namespaces.cljs:278-306` groups the deep pull, namespace
  transaction query, and optional full namespace catalog into one response.
  Its member limits are 3 MiB, 8 KiB, and 512 KiB respectively.
- `src/seon/agent/ctx/namespaces.cljs:409-417` sets the grouped response limit
  to 3,735,552 and says it leaves room beneath "the 4 MiB frame ceiling."
  The ceiling is negotiated and may legally be 64 KiB; it is not a constant
  available to this block.
- `src/seon/agent/ctx/namespaces.cljs:418-424` immediately destructures
  `(::db/results selected)` and, on failure, stores that same absent slot
  instead of the top-level `selected` error.
- `src/seon/db.cljs:1130-1145` sends the one grouped request and converts a
  failed protocol response through `response-error`; `src/seon/db.cljs:282-300`
  shows that conversion returns `:seon.error/message`, `:seon.error/kind`, and
  `:seon.error/data`, not `::db/results`.
- `src/seon/db/transport/uds.cljc:895-917` catches an oversized encoded response
  and emits `protocol/frame-too-large-failure`; the bounded failure's exact
  message and configuration key are at
  `src/seon/db/protocol.cljc:1352-1363`.
- `logs/operator/writer/0c83a7a9-2186-4f58-b4ce-cf7bdf88ade6.log:2-7`
  records the checkpoint writer starting at 04:05:22 with
  `maximum-frame-bytes 65536`. The failing prompt was rendered at 04:06:52.
- `logs/operator/writer/65f05327-6d9d-4d6d-bffb-371ee3d4e0d6.log:2-7`
  records the restored writer starting at 04:07:33 with
  `maximum-frame-bytes 4194304`.
- The active program ledger records the same checkpoint sequence at
  `docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:993-1011`:
  64 KiB applied, boot proof passed, the agent drive found this failure, then
  the ordinary manifest was restored.

## Read-only reproduction

The recorded turn points at rendered transaction `536870946`. Against the
restored 4 MiB writer, this read-only MCP evaluation rendered the block
successfully even when pinned to that historical database value:

```clojure
(let [v (seon.db/as-of (await (seon.db/db)) 536870946)
      r (await (seon.agent.ctx.namespaces/namespaces-block
                 {:seon.agent/id "light-roses-smoke"
                  :seon.db/db v}
                 nil))]
  (select-keys r [:seon.render/ai :seon.render/error]))

```

The result contained a normal `:seon.render/ai` namespaces body and no render
error. The same owning function also renders normally at the current database
value. Database contents and the current source are therefore sufficient when
the 4 MiB assumption holds; the variable that distinguishes the live failure
is the writer's negotiated 64 KiB ceiling.

A second read-only call to private acquisition owner
`acquire-namespace-rows!`, pinned to the same historical database value,
returned 17 selected namespace rows. Their ordinary `pr-str` was 683,063
characters. This is not the Transit byte count, but it independently proves
that the selected response is nowhere near a 64 KiB-safe bound; the restored
4 MiB session is what allowed it through. The exact selected names were the
fresh home namespace, eight `my.*` toolkit namespaces, six `seon.agent.*`
namespaces, `seon.db`, and `seon.schema`.

## History

This predates tonight's renames and extraction.

`git blame` attributes the fixed-4-MiB grouped acquisition at
`src/seon/agent/ctx/namespaces.cljs:409-417` to commits `db365729d` and
`722f58b48` on 2026-07-16. The optional catalog additions at
`src/seon/agent/ctx/namespaces.cljs:278-306` are from `2559be091` on
2026-07-19. Commit `9ce4366a` on 2026-07-21 changed
`seon.agent`, `seon.client`, `seon.eval`, `seon.analyzer-info`, and introduced
`seon.ns.source`; it did not change `seon.agent.ctx.namespaces` or its
acquisition. Replaying the recorded database value under the restored ceiling
also falsifies a persisted require-edge/schema break from that extraction.

## Minimal in-place fix

The functional fix belongs in
`src/seon/agent/ctx/namespaces.cljs`: replace the one deep, multi-namespace
pull response with cursor/index-based bounded pages whose individual response
budget is below the supported 64 KiB floor. Page namespace identities first,
then acquire each namespace's source/require edges and its reverse
function/schema/test rows in bounded pages, preserving the existing final row
shape and the one pinned database value. The directly maintained precedent is
`src/seon/runtime/admission.cljs:195-267`, which uses 32-row pages and a 60,000
max-result-weight specifically to stay beneath the 64 KiB floor.

In the same owner, check `(:seon.error/message selected)` before accessing
`::db/results`, and pass the complete `selected` value to
`acquisition-error`. That is the minimal diagnostic correction, but it is not
by itself a functional fix: it would turn the prompt's misleading `nil` into a
useful frame error while the namespaces block still failed.

A one-line reduction of the grouped max-result-weight is not sufficient. It
would merely substitute a grouped resource-limit member for the transport
failure, and the present deep selector provides no continuation with which to
recover the omitted namespace members.

## Safety and proof

The change is safe to implement now only as a bounded acquisition refactor,
not as a guessed constant adjustment. Its production owner is solely
`src/seon/agent/ctx/namespaces.cljs`; focused proof belongs in
`test/seon/agent/ctx/namespaces_test.cljs`. No `seon.ns.source`, analyzer,
schema, transaction, or turn-loop source needs to change.

Focused gates:

1. Extend `remote-acquisition-is-bounded-and-selection-scoped` with a
   multi-page fixture and assert every request is pinned to the same database
   value and capped at or below 60,000.
2. Add a regression in which the old aggregate exceeds 64 KiB but the paged
   acquisition produces byte-equivalent formatted namespace context.
3. Assert a top-level database error retains its full error data instead of
   becoming `Namespace selected member failed ... nil`.
4. Run `bin/test-cljs seon.agent.ctx.namespaces-test my.ns-test`, followed by
   the full `bin/test-cljs` checkpoint.
5. For live graduation, temporarily run the coordinated 64 KiB manifest again
   and execute a fresh `/agents/run`; the prompt must contain a real
   `[namespaces]` body and no namespaces render failure. This final proof
   requires operator coordination because lifecycle operations were forbidden
   for this audit.
