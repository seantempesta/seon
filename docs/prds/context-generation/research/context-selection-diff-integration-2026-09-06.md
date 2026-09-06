---
type: research
status: complete
tags: [research, context, render, database]
---

# Locked context evaluation versus refreshed evaluation

Date: 2026-09-06

This note answers one bounded question: how the current context selection and
render cache can show a locked evaluation beside a refreshed evaluation of the
same authored source, refresh automatically when its reads change, and then
append or compact the selection by reference. It does not propose another
executor, cache, or result representation.

## Current facts and owners

`seon.context/selection` returns contribution rows in position order and keeps
only the original evaluation entity ids in
`:seon.context.contribution/evaluations`; it copies neither source nor result
(`src/seon/context.clj:77-109`). `append-tx` resolves a closed run to all of its
terminal evaluation refs inside the transaction and appends those refs
atomically (`src/seon/context.clj:117-188`). Reusing a contribution id with
different refs is deliberately a conflict today (`src/seon/context.clj:146-175`).

The transcript already consumes this representation directly. When
`selected-evaluations` is present, `history` pulls exactly those evaluation
entities, joins each evaluation's run and ordinal to the stored run-form source,
and renders the stored result (`src/seon/render/transcript.clj:320-334,
549-583,868-881`). This is the locked baseline. It remains stable when current
domain facts change because result and source are stored on the run/evaluation
entities; no evaluation is invoked while formatting it.

The debug page already acquires that selection and renders every contribution
through the same transcript owner (`src/seon/render/web.clj:1317-1342,
1848-1852`). Its current action posts a candidate run id to
`context-response`, which invokes `append-tx` (`src/seon/render/web.clj:1325-1332,
3414-3429`).

The authored-source cache already owns refresh. `render-source-call` first asks
the ordinary renderer for source, then either retains the run or calls the one
executor, `seon.cluster.agent/submit-source!`; completion is observed on a
later database wake and formatted by `transcript/render-run-ai`
(`src/seon/render/web.clj:1517-1612`). `submit-source!` parses through
`cluster.loop/planned-sources` and creates an ordinary durable system run
(`src/seon/cluster/agent.clj:636-712`; `src/seon/cluster/loop.clj:145-162`).

Every completed source run contributes its stored
`:seon.cluster.eval/read-evidence` to the retained invocation
(`src/seon/render/web.clj:1450-1464,1583-1600`). The common call cache accepts a
retained invocation only when `seon.db/read-evidence-current?` still holds
(`src/seon/render.clj:590-606,1050-1108`). Database wakes compute candidate call
ids from the same evidence and re-enter the debug/context render pass
(`src/seon/render/web.clj:2293-2320,2592-2644`). Therefore a changed relevant
read automatically causes the existing renderer invocation to regenerate its
source and the existing source-call path to submit it. An unrelated database
change retains the run. No button, timer, polling loop, or second cache belongs
in this path.

The apparent old-run preference at `src/seon/render/web.clj:1549` is not by
itself a stale-result bug: before that line, `render/render-call` rejects the
stale invocation by read evidence and replaces the matching captured bucket
entry with a fresh source-only invocation (`src/seon/render.clj:1055-1108`).
The source call then observes that fresh entry. A regression should cover this
whole sequence because testing `reusable-source-run-id` alone would miss the
cache replacement that makes it work.

Datahike history is useful for database-read explanations, but it is not the
baseline store here. Seon creates databases with history enabled
(`config/default.edn:4`), and `seon.db/as-of` delegates to Datahike's `as-of`
wrapper after checking the temporal index (`src/seon/db.clj:1424-1445,
1501-1514`; `reference-code/datahike/src/datahike/api/impl.cljc:153-183`).
`seon.db/diff` replays a declared pure database function at an old basis and
the current database and requires a derivable row identity
(`src/seon/db.clj:1750-1910`). It cannot represent arbitrary SCI evaluation
effects, stdout, errors, or multiple forms, so it should remain the detailed
data diff inside source that explicitly calls it, not become a second transcript
or evaluation diff mechanism.

## Minimal implementation

1. Derive a **comparison row** in `seon.context` from a contribution id and the
   current source-call run id. Pull the contribution's evaluation refs, derive
   its one run through `:seon.cluster.eval/run`, and derive the refreshed run's
   evaluation refs through `:seon.cluster.eval/_run`, ordered by ordinal. Refuse
   a contribution spanning runs or an open/nonterminal refreshed run. Return
   refs and run identities only. The baseline and refreshed text are then two
   calls to the existing transcript projection; do not store either string.

2. Persist the exact authored source on the source run through the existing raw
   reply facts. `:seon.cluster.run/reply`, `reply-blob`, and `reply-size` already
   are the authority for exact provider reply text
   (`resources/seon/schemas/seon.cluster.run.edn:25-28,76-84`). The ordinary
   reply freeze already applies the configured blob threshold and hands those
   fields to `run/plan-tx` in the same intent transaction as form and evaluation
   rows (`src/seon/cluster/loop.clj:1421-1456`). `plan-call` stores the raw reply
   beside the ordered parsed forms (`src/seon/cluster/run.clj:640-740`). Extend
   `:seon.cluster.run/system-run-request` with those same optional fields, have
   `submit-source!` stage the supplied source by the same threshold rule, and
   have `system-run-tx` pass the fields unchanged into `system-plan-tx`. This is
   an accretion to the existing run intent, not a source entity or cache.

   The raw reply is necessary even though the reader is span-faithful. Reader
   events preserve each exact substring, including CRLF and UTF-16 offsets
   (`test/seon/sci/reader_test.clj:136-145`), and reply planning attaches leading
   and trailing prose/comments to form sources (`src/seon/cluster/reply.clj:
   129-159,242-267`). But splitting deliberately changes representation and
   cannot prove every byte between forms after reconstruction. The run's raw
   reply is the exact restart-safe source; per-form source remains the execution
   and transcript unit. Results remain only on evaluation facts, so this adds no
   duplicate per-form source or output.

   Establish “same source” after restart by equality of the two runs' recovered
   raw reply strings plus the existing producer, selection input, program
   snapshot/projection identity, agent, and starting namespace evidence. During
   one process lifetime the invocation supplies those facts directly. Across a
   restart, the raw source and run/evaluation facts survive; code-generation
   identity must be regenerated by the current render call before a refresh is
   eligible. Superseded invocation entries are disposable and must not become
   the durable authority.

3. In `debug-context-html`, pair a locked contribution with the retained
   invocation whose old source-run evaluation refs it contains. Show two
   transcript renders labelled with run id and evaluation basis: the locked
   refs and, when the automatic refresh settles, the new source-run refs. While
   the refreshed run is open, show its ordinary pending state. The existing
   database wake completes the view; add no wait loop.

4. Keep **append** as the existing `append-tx` call with the refreshed run id
   and a fresh contribution id. Add one transaction function beside it for
   **compact**: request the existing contribution id plus refreshed run id,
   repeat the same agent/run/closed/terminal checks at transaction time, and
   replace only `:seon.context.contribution/evaluations`. Preserve the existing
   position. Use Datahike compare-and-swap against the old ref set (or explicit
   retract/add datoms decided from the mid-transaction database) so a stale UI
   cannot replace a newer selection. This changes refs only; the old evaluation
   facts and their provenance remain durable.

5. Extend `context-response` with an explicit append/compact action and the
   contribution id for compact. Both actions transact the functions above.
   Automatic refresh remains upstream in the render cache; these actions decide
   only how the already-stored refreshed refs enter agent context.

## Bounded proof

One integration regression should use a renderer whose source increments an
execution counter and reads one query dependency. Render once, lock its run,
change an unrelated fact (counter remains one), then change the relevant fact.
The database wake must produce exactly one new source run, counter two, while
the locked contribution still renders result one. The comparison must render
both stored results without another increment. Assert append produces two
ordered contribution ref sets; compact leaves one contribution at the original
position pointing to the refreshed evaluation refs. Also assert the same-source
pair is absent after producer, selection input, program snapshot, projection,
agent, or starting namespace changes.

The remaining custody edge is blob publication, not source identity. The source
submission path must use the same stage/transaction completion discipline as
the ordinary reply freeze so a run never points at an unavailable blob. The
comparison reader should use the existing inline-or-blob retrieval idiom and
return a flat unavailable-source error if the blob cannot be read. There is no
reason to retain superseded cache entries for restart safety once the run owns
its raw reply.
