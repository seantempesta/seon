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

## Form, evaluation, and selection ownership

The current database stores more than one representation of a form/evaluation
relationship:

- A run owns `:seon.cluster.run/forms`, a cardinality-many component ref
  (`resources/seon/schemas/seon.cluster.run.edn:5-7`). Each form also stores the
  reverse `:seon.cluster.run.form/run`, plus ordinal, author, source, and parse
  namespace (`resources/seon/schemas/seon.cluster.run.form.edn:1-29`).
- An evaluation stores its run and ordinal, and also repeats the form's source
  and namespace (`resources/seon/schemas/seon.cluster.eval.edn:1-16,21-82`).
  `receipt-start-call` writes those copies even though `settlement-form` later
  finds the form by the same run and ordinal and reads source/namespace from the
  form (`src/seon/cluster/run.clj:1074-1137,1139-1165`).
- The run planner writes both the run's component edge and the form's run edge
  (`src/seon/cluster/run.clj:710-740`). Thus `run/forms` versus `form/run`, and
  `form/{source,ns,ordinal}` versus `eval/{source,ns,ordinal,run}`, are stored
  mirrors rather than distinct facts.

Datahike component semantics make the forward owner meaningful. Retracting an
entity discovers component-valued outgoing datoms and recursively emits
`retractEntity` for their values
(`reference-code/datahike/src/datahike/db/transaction.cljc:830-839,997-1014`).
Consequently the durable shape should follow ownership from run to form to its
evaluation; a backlink collection is unnecessary for traversal because
Datalog can query the reverse of either ref.

The smallest coherent target is:

1. Keep `run/forms` as the one run-to-form relationship and its component
   ownership. Keep source, parse namespace, ordinal, author, and refresh
   provenance on the form.
2. Add one cardinality-one component ref on the form to its evaluation. Move
   the existing evaluation result, error, output, timing, read evidence, and
   diagnostic metadata under that ref unchanged. These are evaluation facts,
   not form copies.
3. In the same migration wave, stop writing and stop reading
   `eval/run`, `eval/ordinal`, `eval/source`, and `eval/ns`, and stop writing
   `form/run`. Derive the run through the reverse `run/forms` edge and the
   evaluation through the form's component ref. This is consolidation, not an
   additional mirror. A temporary dual-write would create the duplicate model
   the change is meant to remove, so writers, queries, schemas, and fixtures
   should change together.
4. Preserve evaluation identity stability. The writer already derives the
   evaluation identity from `(run-id, ordinal)` before transaction entity ids
   exist (`src/seon/cluster/run.clj:607-623,1074-1081`). That identity may remain
   unchanged even when its stored navigation becomes form-owned.

No stored `result/eN` symbol is needed. `seon.sci.eval/bind-result!` derives the
symbol from the form ordinal inside the turn fork; its docstring explicitly
states that the fork supplies agent/run scope and ordinal is the remaining
distinguishing projection (`src/seon/sci/eval.clj:533-547`). Persisting the
symbol would mirror a deterministic presentation name and would be ambiguous
without its run/turn scope. UI and transcript code can derive `result/eN` from
the owning form's ordinal whenever it presents that evaluation.

Authorship and context selection remain separate facts. A form's
`:seon.cluster.run.form/author` says `:agent` or `:system`; it records who
authored executable source. A locked preview remains a system-authored run form
whose evaluation is referenced by
`:seon.context.contribution/evaluations`. The contribution means the agent
selected that already stored result into context; it must not rewrite the form
as agent-authored or copy the form/result under the agent. Agent-authored forms
continue to enter ordinary run history, while locked previews enter selected
context through contribution refs. Both render through the same form and
evaluation entities.

This consolidation has a deliberately larger reader-update radius than adding
one direct `eval/form` backlink: transcript, work selection, settlement,
curation, problem routing, and context comparison currently join evaluations
by run plus ordinal. The implementation should inventory those joins from the
program graph, update them in one bounded migration, and retain one regression
that retracting a run retracts its forms and their evaluations while a context
contribution cannot silently retain a dangling evaluation ref. Adding
`eval/form` while retaining all existing run/ordinal/source/ns fields would be
smaller code churn but would preserve every duplicate and add another one.

## Counterexample: repeated execution of one source

The preceding form-owned evaluation recommendation needs one refinement. Two
candidate models expose it:

**A. One accreting entity per executed form.** The entity carries run-local
ordinal, author, parse namespace, source text, refresh lineage, and all eventual
evaluation metadata. This removes the form/evaluation join and is the simplest
query shape. It still creates a new entity with the same potentially large
source string for every automatic refresh. The current refresh writer proves
this is normal rather than hypothetical: it creates a successor run/form at
ordinal zero, copies the prior source and namespace, and links `refreshes`
(`src/seon/cluster/run.clj:1010-1072`). A therefore minimizes entity count and
joins but not repeated source facts.

**B. Stable source plus run occurrences.** A content-addressed source entity
owns the exact form-source bytes once. Each run owns ordered component
occurrences. An occurrence carries ordinal, author, parse namespace and refresh
lineage and points to the stable source. Its evaluation metadata belongs to
that occurrence. This adds one ordinary hop when rendering, but a refresh adds
only a new occurrence, result metadata, and refs; it does not assert the source
string again. For repeated renderer refreshes, B minimizes actual duplicate
facts while keeping queries finite and direct.

The literal variant “stable source entity with many evaluation component
children” is insufficient. The same bytes can execute in different namespaces,
runs, and ordinals. `result/eN` is scoped to the turn fork and derived from the
run occurrence's ordinal (`src/seon/sci/eval.clj:533-547`); authorship and
refresh lineage are likewise occurrence facts. Hanging evaluations directly
from source would require each evaluation to point back to an occurrence or
repeat run/ordinal/ns, recreating the mirrors. The useful B shape is therefore
source → bytes, run → component occurrences, occurrence → component evaluation.

A minimal map is:

```clojure
{:seon.cluster.run/id "source:…"
 :seon.cluster.run/reply "<exact submitted reply>"
 :seon.cluster.run/forms
 [{:seon.cluster.run.form/id "[:seon.cluster.run.form/id … 0]"
   :seon.cluster.run.form/ordinal 0
   :seon.cluster.run.form/author :system
   :seon.cluster.run.form/ns [:seon.ns/name 'my.agents.juniper]
   :seon.cluster.run.form/source [:seon.cluster.form.source/digest "sha256…"]
   :seon.cluster.run.form/refreshes [:seon.cluster.run.form/id "…prior…"]
   :seon.cluster.run.form/evaluation
   {:seon.cluster.eval/id "[run-id 0]"
    :seon.cluster.eval/at #inst "…"
    :seon.cluster.eval/result-edn "…"
    :seon.cluster.eval/read-basis-transaction 123
    :seon.cluster.eval/read-evidence […]}}]}

{:seon.cluster.form.source/digest "sha256…"
 :seon.cluster.form.source/text "(my.plan/plan \"juniper\")"}
```

The digest must cover exact UTF-8 form-source bytes only. Namespace must not be
part of source identity: the same string in two namespaces is the same source
artifact but two different executions. Conversely, normalized EDN or the read
form is not an acceptable identity because comments, CRLF, reader spelling and
repair output are user-visible source. The reader preserves exact event spans,
and reply planning intentionally attaches surrounding comments/prose to the
executed form source (`src/seon/cluster/reply.clj:129-159,242-267`). The stable
source stores that final planned form-source byte string. The run's existing
raw reply remains the authority for the exact unsplit submission, because no
set of planned slices should be asked to reconstruct it.

Failure cases make the boundaries concrete:

- Same source bytes, different namespace: share source entity; keep distinct
  occurrences and evaluations.
- Renderer emits changed code: new digest/source entity and occurrence;
  `refreshes` connects occurrences, never source entities.
- Renderer emits identical code after relevant data changes: reuse source
  entity; new occurrence/evaluation records the new basis and result.
- Reader repair changes executable bytes: the repaired planned source gets its
  own digest; raw reply still records what was submitted.
- Agent enters the same source as a preview used earlier: share source bytes,
  preserve separate occurrences with `author :agent` versus `:system`.
- Agent locks a preview: contribution references that system occurrence's
  evaluation. Locking does not mint another occurrence or change authorship.

For repeated rendering, B is the better stored model despite its one extra
join. A is attractive only if source values are assumed tiny and refresh rare,
which contradicts the active renderer path. B also avoids backlink arrays:
reverse Datalog traversal answers source-to-occurrences and
evaluation-to-occurrence. The implementation should not add a source-to-
evaluations collection or store `result/eN`; both are derivable mirrors.

## Source producers versus stored transcripts

The preview mismatch is a contract defect. `render-source-call` marks every AI
request with `:seon.render.call/source-output?`; `render/render-call` then
considers every non-floor string authored source (`src/seon/render.clj:
1073-1106`). A run's declared AI renderer is `transcript/render-run-ai`, which
instead returns already stored forms and results (`resources/seon/schemas/
seon.cluster.run.edn:62-67`; `src/seon/render/transcript.clj:945-962`). Executing
that text again can repeat effects. Reader classification cannot recover the
missing intent: historical source can be syntactically executable.

Reuse the existing `:seon.render/source` return schema. Actual source-producing
functions declare it; terminal text functions retain their contracts. The
index already records arity output refs and return shapes (`src/seon/program.cljc:
542-587`), and the acquired immutable projection carries indexed contracts.
Malli's function-arity/input/output operations are the existing same-arity
selection idiom (`src/seon/schema.clj:3131-3155`). Inspect the input-compatible
arity's top-level source return, including nullable/error union arms; a source
reference nested inside an arbitrary returned collection is insufficient.
Neither function names, string syntax, nor a run-valued input establishes it.

Every selected, attribute, and alternative preview then uses the same existing
source-call step. Its render-call entry receives `source` only for a declared
source return. Source submits through the existing `submit-source!` owner and
retains `source-run-id`; terminal text displays directly without parsing.
AI namespace matching must also admit the existing source return contract.
No output selector, registry, evaluator, or cache is added. The existing
request hint remains solely to select the debug query-generating floor; it
cannot establish execution eligibility for a selected producer.

The eventual terminal composition must carry its existing run id and
`:seon.context.contribution/evaluations` in the request/call entry. Terminal
transcript locking references those original evaluations without a new run.
Whole-run locking already belongs to `append-tx`, which derives all terminal
evaluation refs at the writer. Exact subset locking needs that same request
and writer to accept the existing evaluations field and validate membership,
ownership and settlement atomically; silently locking the whole run is not
exact subset selection. Terminal text with no evaluation refs is displayable
but is not evidence that an evaluation occurred.

This is not implemented by copying a run id from an arbitrary renderer input:
that renderer may display a summary or subset. Without explicit data proving
the displayed evaluation selection, no terminal lock is exposed. The current
implementation slice retains actual source-run locking and leaves terminal
evaluation-reference composition unresolved.

The current namespace producer is mixed: distance zero returns a bare namespace
spelling, distance one emits ns/def/register forms, and larger distances return
a vector mixing an ns form and summary data (`src/seon/render/ns.clj:461-529`).
An unconditional source contract would misdescribe that behavior. It must
remain terminal until its owner makes its intent uniform; this source-contract
slice must not execute its display text accidentally.

The recurring proof shows a source producer submits through the ordinary run
owner while a terminal transcript submits nothing even when its text contains
effectful forms. It checks indexed output references and preserves the stored
evaluation population. Terminal locking remains unresolved as described above.

Contract matching also reuses the schema projection's existing compiled-value
holder. Construction and materialization retain the function schemas they
already compile; their arity input validators are realized once in that holder.
`function-matching-outputs-in` returns declared output forms for accepting
arities. The existing accepts/returns helpers and source eligibility share
those arities, with no renderer-specific compiler or additional cache. The
same-arity regression refuses new Malli compilation after the initial query.
Derived projections already receive a fresh compiled holder, preserving schema
isolation when declarations change.

Verification: `bin/test seon.render-source-test seon.render-simplification-test`
passed 24 tests / 209 assertions on 2026-09-06 (run.64tIru). The earlier
run.x4ukvB failed on missing fixture profile/form facts and a stale explicit-id
identity-source expectation; those were corrected. Live preview readiness is
not established by this gate: publication instrumentation and the previously
faulted render proc remain separate integration work.


The previously protected cluster renderer rewrite was subsequently reviewed by
root and included with the lifecycle checkpoint: `render-ai` produces the
ordinary pull source and `format-ai` returns terminal cluster text. Root's
live pure probe checked generated source plus the pulled configuration,
instruction, and toolkit description. Namespace producer changes remain
outside this slice, with its mixed-output defect retained above.
