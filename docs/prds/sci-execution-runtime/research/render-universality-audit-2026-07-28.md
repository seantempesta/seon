---
type: research
status: active
tags: [prd, research]
---

# Render universality audit

## Question and answer

The owner's question is whether Seon has one universal rendering system, should
stop inventing parallel ones, and should make more of the system renderable in
AI, HTML, log, and later output kinds.

**Answer: no, not in production today.** The universal contract is sound and
small: a unit carries output-kind keys whose values are qualified projection
symbols; `seon.render/render` late-resolves and invokes the selected symbol; and
`seon.render/kinds` derives the open kind set from the unit
(`src/seon/render.clj:76-98,102-147`;
`src/seon/schema/render.edn:12-48`). Error notices and problems values already
declare kinds. The N4 draft makes blocks units and supplies the reusable
generic-default plus producer-selected-specialist pattern
(`src/seon/render/block.clj:126-189,250-317`;
`src/seon/schema/block.edn:148-191`, landed in `6dcda1ab9`).

The contract is not universal in use yet. At the audit's initial source
snapshot, production `src/` contained no call to `seon.render/render` outside
the router itself; every call was in tests. The concurrent projection revision
then converted error message delivery, failover context, and nested problems
composition to the router and added the first producer-selected specialist.
Prompt assembly remains a separate AI renderer, process logging composes its
own text, N4's block pipeline remains stubbed after its contract package, and
three program-graph families advertise projection symbols that do not resolve.

The exact deltas are:

- route the remaining ordinary AI, HTML, and log consumers through
  `seon.render/render`;
- replace `seon.cluster.prompt`'s four-section formatter with the AI view of
  the agent's ordered blocks;
- finish N4's block unit, surface, page, and pipeline implementation;
- give every entity family below one derived unit-build point, with generic
  defaults and specialists selected there from the value's own attributes;
- make every projection symbol published by the schema catalog resolve; and
- retain direct stderr only at a documented recursion fence where the ordinary
  error/render path itself has failed.

## Audit basis and classification

This audit read the fresh `src/` tree, not `src-old/`. The target is
`docs/seon/architecture/ui.md:47-87,132-145`: rendering is one open projection
contract; AI is the prompt consumer, HTML is the surface consumer, log is the
process-log consumer; and prompt and page derive from the same blocks at one
immutable database value. N4 package 1 landed during the audit in `6dcda1ab9`;
the final census below includes that commit.

Classifications:

- **THROUGH-THE-ROUTER** — the producer builds a unit with
  `:seon.render/<kind>` and the consumer calls only `seon.render/render`.
  Internal string construction inside the selected projection is expected.
- **justified-local** — text is an error/refusal value, durable serialization,
  wire encoding, identifier, path, or last-resort recursion-fence diagnostic;
  it is not presentation of a unit to an output-kind consumer.
- **REINVENTION** — consumer-facing presentation is assembled or a projection
  function is invoked outside the one router.

The line is semantic, not a grep rule. `str` inside `seon.error/ai-prose` is the
implementation of a selected projection. `str` in a store refusal is the
message field of an error value. `str` in `seon.cluster.prompt/prompt` is a
second AI render pipeline.

## Current consumer-facing projections

| Site | Current output | Classification | Evidence and disposition |
|---|---|---|---|
| `seon.error/notice` + AI/log projections | One error fact becomes steering prose or a structured log line | THROUGH-THE-ROUTER | `src/seon/error.clj:367-395` builds the unit, selects the Malli AI specialist from fact attributes, and declares log; `:449-454` is the local router-result helper. Add HTML here. |
| Error explanation messages | Stored message content for the interrupted/escalation agent | THROUGH-THE-ROUTER after the concurrent correction | `src/seon/error.clj:666-686` builds a recipient-specific notice and obtains its AI output through the router before storing the historical message content. |
| Failover backup system segment | AI context explaining the primary failure | THROUGH-THE-ROUTER after the concurrent correction | `src/seon/cluster/loop.cljc:669-684` builds the notice from the committed fact and `:failover` reason, then requests its AI kind. |
| `seon.problems` | Actionable AI receipt warnings and the whole-cluster problem log | THROUGH-THE-ROUTER | `src/seon/problems.clj:153-197` declares AI only when errored receipts exist and log for any problem; `:199-240` routes each nested error notice for byte-identical log composition. HTML is the missing kind. |
| Agent prompt | Identity, interruption, trigger content, and execution instructions | REINVENTION | `src/seon/cluster/prompt.cljc:122-149,160-184` builds the complete AI text, and `src/seon/cluster/loop.cljc:609-614` consumes it directly. This is the largest second rendering system. |
| Instrumentation violation | Flat agent/core error value with human explanation and bounded evidence | justified-local as an error value; THROUGH-THE-ROUTER when presented | `src/seon/instrument.clj:120-168` constructs the boundary error/refusal, which must exist without a renderer. `src/seon/error.clj:383-389,433-447` selects and implements the detailed Malli AI specialist from the normalized fact's own attributes. HTML remains. |
| Agent, provider, reply-parser, and schema errors | Flat values or refusals a caller must branch on | justified-local | `src/my/run.cljc:41-79`, `src/seon/ai.cljc:194-215,325-434`, `src/seon/cluster/reply.cljc:96-166`, and `src/seon/schema/internal.cljc:239-323` construct boundary data that must remain legible when no renderer is available. Their eventual notices/surfaces should route; their message fields should not disappear. |
| Evaluation success/failure | Printed output, error message, and durable result projection | justified-local | `src/seon/sci/eval.clj:252-281,345-370` bounds printed output and returns an evaluation/error value. These are receipt facts, not final prompt/page/log presentation. |
| Receipt `result-edn` | Finite readable EDN of the admitted eval value | justified-local | `src/seon/sci/admit.clj:425-475` performs bounded structural admission and then `pr-str`; `src/seon/cluster/run.cljc:504-519` stores the string. It is a durable serialization/projection for replay and inspection, not an output-kind render. A future receipt renderer consumes it. |
| Run failure string | Durable reason a run closed before a plan | justified-local | `src/seon/cluster/loop.cljc:561-580` stores the flat error message so the failure does not evaporate. It is source data for prompt/problem/run renders, not itself a render. |
| Run, config, reconcile, store, registry, ancestor, export, wake, and boot refusals | `ex-info` or flat error messages naming an atomic refusal | justified-local | Representative owners: `src/seon/cluster/run.cljc:157-162`; `src/seon/config.cljc:81-106`; `src/seon/reconcile.cljc:57-64`; `src/seon/cluster/store.clj:178-184,415-475`; `src/seon/cluster/wake.cljc:119-134`; `src/seon/cluster.clj:128-142`. A refusal message is part of the value/exception contract and must exist without a renderer. Render the resulting fact later; do not remove the message. |
| Core-fault panic and drop output | Human stderr lines | REINVENTION except the recursion fence | `src/seon/cluster.clj:590-607` uses `println`/`ex-message`/`pr-str` for ordinary fault presentation although the fault is also normalized. `:498-503` is justified-local: durable fault handling itself failed, so stderr is the last recursion fence. |
| Export slow-path warning | Human stderr warning plus data | REINVENTION | `src/seon/cluster/export.clj:91-99,195-200` owns a local warning formatter. Its docstring predates the log kind and explicitly expects replacement by the logging owner. |
| Instrumentation-zero warning | Human stderr warning | REINVENTION | `src/seon/instrument.clj:199-204` prints a separate development diagnostic. The condition is a process fact that can build a log unit; it is not a refusal message returned to a caller. |
| Router failure messages | Flat errors for missing, unresolvable, or throwing projections | justified-local | `src/seon/render.clj:120-147`. The router is on the error path and must return legible error values without recursively rendering them. |
| N4 block and Hiccup package 1 | Block units, surfaces/pages, generic HTML panel, Hiccup serialization | THROUGH-THE-ROUTER by contract, not live yet | `src/seon/render/block.clj:126-189` delegates block projection to the router; `:250-317` defines selection and the generic HTML default. Bodies still throw `awaits implementation`. `src/seon/render/hiccup.clj:173-254` is the HTML serializer after projection, not another unit renderer; its transform bodies are also stubs. Landed in `6dcda1ab9`. |
| Program-graph entity catalog | AI/HTML declarations for functions, schemas, and namespaces | REINVENTION/false coverage | `src/seon/schema.cljc:538-575,1121-1190` publishes six symbols under absent `seon.render.handlers.*` namespaces. No fresh-tree producer can successfully route them today. |

### Non-presentation `str`, `format`, and `pr-str`

The remaining fresh-tree sites are not rival renderers:

- `src/seon/cluster/loop.cljc:178-181,245-250`,
  `src/seon/cluster/run.cljc:399-452`, and
  `src/seon/error.clj:250-257` derive digests and stable identities.
- `src/seon/cluster.clj:181-198,237-280,382-392`,
  `src/seon/cluster/store.clj:146-172`, and the ancestor/registry/export path
  builders turn platform paths and process identities into protocol data.
- `src/seon/ai.cljc:178-215,325-390` builds and decodes provider wire data.
- `src/seon/schema.cljc:131-184` canonicalizes data for fingerprints, and its
  later `pr-str` calls encode schema forms or deterministic sort keys.
- `src/seon/reconcile.cljc:371-398` uses `pr-str` only as a deterministic sort
  key.

Those sites should not be forced through `seon.render`; doing so would confuse
data encoding with consumer presentation.

## Coverage shopping list

Projection declarations belong on derived units, not as identical stored
symbols on every entity. “Add a render” below therefore means: pull/derive the
value at one database value, build a unit carrying the selected qualified
symbols, then let consumers request kinds through the router.

| Unit family | Coverage today | AI render should show | HTML render should show | Log render should show |
|---|---|---|---|---|
| Error notice | AI + log | Concise steering for the recipient; specialists for transition refusal, Malli violation, provider/failover, and recurrence | Compact error card with kind/message, affected run/proc, evidence link/id, recurrence, and expandable admitted data | One stable grep-friendly line; specialist fields without changing the common identity tail |
| Problems value | AI for errored receipts + log for every non-empty family | Only actionable current problems, grouped and deduplicated, with the next fact to inspect | Root problems page grouped by error signature, wedged/failed runs, and errored receipts; counts, filters, and links | Existing report, including nested errors routed through their own units |
| Run | None | Current purpose/cause, state derived from attributes, plan progress, interruption/failed reason, and next eligible action | Timeline/card with cause message, process/epoch/lease, forms and receipt progress, attempt chain, and terminal outcome | One state/transition snapshot with run, agent, process, epoch, and failure |
| Eval receipt | Durable `result-edn`, no renders | Form source, status, bounded output/result/error, with cappedness explicit | Expandable form/result/output/error card with timing and diagnostic fields | One line with receipt/run/ordinal/epoch/status and error id/message when present |
| Message | Prompt reads raw content; no unit | Content plus sender/cause/about context needed by the recipient | Chat bubble/event with time, direction, related error/run, and delivery context | Envelope identity, recipient, time, and about ref; content summarized/bounded |
| Model attempt | No renders | Why this target ran, whether it was safe/free/paid, disposition, and relation to the prior attempt | Attempt-chain timeline with model, ordinal, delay/failover edge, HTTP/transport evidence, error, and outcome | One key-value line containing the payment-safety evidence and disposition |
| Agent | No renders | Identity, current run/queue, purpose/context summary, and problems relevant to this agent | Root fleet card and detailed agent header/status; same underlying unit at different HTML specialists if needed | Lifecycle snapshot with agent, current run, queued work, and state |
| Config singleton | No renders | Effective behavior relevant to the agent, omitting secrets and low-level noise | Effective/default/override comparison, provenance, validation state, and safe controls | Applied digest and changed/invalid keys; never credential values |
| Cluster | No renders | Root-only health summary: process identity, current work, problems, capacity, and configured behavior | System overview composing agents, problems, config, and process/flow health | Lifecycle/readiness line with cluster, branch, process, basis, and fault/drop state |
| Function/schema/namespace catalog units | Declared AI + HTML, but symbols do not resolve | Compact source/schema summary and discoverability context | Inspectable source, schema, dependencies, and validation state | Usually omit; add only for an operator event, not because every family must emit every kind |

Not every unit needs every kind. The open-kind contract makes omission cheap
and meaningful. The recommended default is AI + HTML for facts shared by agent
and human; add log when an operator actually greps or tails that family.

## Generic default plus specialist selection

The N4 shape is sufficient. `seon.render.block/select` takes the value plus an
ordered selection of a generic default and predicate/projection pairs; the
producer writes the returned symbol onto the unit
(`src/seon/render/block.clj:250-290`;
`src/seon/schema/block.edn:148-191`). The consumer never sees the rules.
Predicate failures fall through to the generic default, and all symbols remain
late-resolved.

What is missing is not expressiveness; it is unit-build ownership:

| Family | Build point today? | Finding |
|---|---|---|
| Errors | Yes: `seon.error/notice` | Correct location. It has the full fact plus recipient reason. It must select each kind's generic/specialist symbol here rather than branch inside a generic renderer. |
| Problems | Yes: `seon.problems/problems` | Correct location. It already derives the complete grouped value and conditionally declares kinds. |
| Blocks | Drafted: `seon.render.block/unit` | Correct general block build point, but unimplemented. A block's own projection keys remain authoritative; `select` is for a producer deriving a key, not a second registry over authored blocks. |
| Runs | No | `seon.cluster.run` has transition builders and private pull helpers, but no public pure value→unit boundary. Add one beside the run owner. |
| Receipts | No | Receipt start/settle transaction builders exist, but no derived unit builder. Do not put projection symbols into receipt transactions. |
| Messages | No | User triggers and error explanation messages are built at different write sites; reads often return only ids. Add a shared derived message-unit point after pull. |
| Attempts | No | `record-attempt!` builds durable transaction data. Selection there would store derived render symbols, so add a read-side attempt-unit builder over the pulled row. |
| Agents | No | Root seeding, work queries, and instance assembly are separate. Add one database-derived agent-unit builder consumed by root and agent blocks. |
| Config | Natural point exists, but no unit: `seon.config/effective` | Wrap the effective value in a derived unit and select specialists from present/invalid/drift attributes. |
| Cluster | No single database-derived point | The instance map is process-local while health/config/work are database facts. The root system block should be the composition point; do not store a synthetic cluster status entity merely to gain a builder. |
| Functions, schemas, namespaces | Catalog metadata exists, usable unit builder does not | Resolve or remove the advertised handlers, then build units from the catalog row plus pulled entity. |

Thus the pattern is expressible for every family, but it is currently
enforceable only for errors, problems, and the drafted block family. Runs,
receipts, messages, attempts, agents, clusters, and program-graph entities lack
an owned unit-build point; config has the value boundary but not the unit
wrapper.

## Confirmed issue notes

- [[prompt-assembly-bypasses-the-render-router]]
- [[stderr-presentations-bypass-the-log-render-kind]]
- [[program-graph-render-declarations-name-absent-functions]]

The projection-revisions lane closed the initial direct error-projection call
sites while this audit ran: message delivery, failover, nested-problems
composition, and their tests now request output kinds through the router. No
separate issue note is warranted for the superseded initial snapshot.

## Accretion order

1. **Accrete error coverage.** The corrected consumers use the router; next add
   error HTML and move any further family-specific projections to specialists
   selected in `notice`. Add problems HTML in the same slice.
2. **Make problems the first complete block.** Add concise AI plus the N4 HTML
   root problems page. This proves one unit serving prompt, page, and log.
3. **Replace the bespoke prompt.** Seed identity/instructions, trigger,
   interrupted/failed-run, and problems blocks; assemble their AI outputs in
   priority order through the same block walker used for HTML.
4. **Add run + receipt units.** They provide the block-page spine and make
   interruption, progress, eval result, and failure inspectable without custom
   page code.
5. **Add message + attempt units.** Build the transcript/chat twin and the
   failover/backoff chain from the facts already committed.
6. **Add agent units.** Root fleet cards and ordinary agent headers become
   specialists over one family, not another page model.
7. **Add config + cluster units.** Finish the root operations view after the
   lower-level facts it composes are renderable.
8. **Resolve program-graph renderers, then extend kinds only on demand.**
   Function/schema/namespace AI+HTML are valuable; SMS, metrics, or other kinds
   require one real consumer and no router change.

The graduation test is structural: a census of production consumers finds no
ordinary direct call to a projection function, prompt/page/log each request a
kind through `seon.render/render`, every advertised projection symbol resolves,
and each family above has one producer-owned unit builder or an explicit reason
it deliberately has no render.
