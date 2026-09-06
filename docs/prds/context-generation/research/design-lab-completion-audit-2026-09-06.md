---
type: research
status: complete
tags: [research, context, render]
---

# Design Lab first inspection slice completion audit — 2026-09-06

This is a point-in-time audit of repository HEAD `bbb9d73ad` and the live
evidence recorded on 2026-09-06. It is not a second roadmap. I read
`../plan/design-lab-prd-2026-09-05.md` and the current
`../plan/unsettled.md` end to end. “Proved” below requires a positive focused
or browser assertion of the requested behavior; the absence of an observed
error is not proof. “Incomplete” means an owning mechanism exists but an
acceptance fact is absent or currently falsified. “Missing” means the requested
fact is not represented by the current implementation.

| First-slice requirement | Status | Current evidence | Exact remaining fact |
|---|---|---|---|
| Bounded actual stored attributes and references, including both directions | **Proved** | `src/seon/render/data.clj:91-182` reads exclusive bounded EAVT outgoing pages and bounded AVET incoming pages from one database value, returning raw `e/a/v/tx/added`, identities, snapshot and continuations. `test/seon/render/data_test.clj:64-133` positively covers paging, refs, missing subjects, stale continuations, weight refusal and no writes. The latest browser checkpoint drew 34 actual nodes and 33 actual references (`../plan/unsettled.md:11-22`). | No first-slice action. Multi-hop accumulation and displayed-fact coverage are later experiments, not properties of this one-hop observation. |
| Reference navigation without losing the viewing namespace | **Proved** | `src/seon/render/web.clj:754-796` links outgoing ref values to the target, incoming entity IDs to the source, and attributes to `[:db/ident a]`; changing subject clears both snapshot-bound cursors. `test/seon/render/web_test.clj:743-800` covers both directions, an unlinked scalar, preserved viewer/output/bounds and cleared cursors. The browser probe physically clicked a node and retained the viewing namespace (`../plan/unsettled.md:15-17`). | No first-slice action. |
| Arbitrary subject, independent viewer, and agentless read-only inspection | **Incomplete** | `src/seon/render/web.clj:168-220` represents viewer and subject independently and round-trips both. `test/seon/render/web_test.clj:719-741` proves the canonical agentless GET/feed leaves the database basis unchanged and creates no owner. | Run the specified same-subject fixture through two viewer aliases and the agentless route. Current evidence proves separable request fields and one navigation, not the acceptance case that both viewers stay separately labelled for the same subject. |
| Header identifies the observation and its world | **Incomplete** | `src/seon/render/web.clj:798-819` shows viewer, subject, database snapshot, requested output and explicit pull bounds. The prior browser observation positively saw viewer, subject, basis/commit identity and output (`design-lab-inspection-slice-2026-09-05.md:11-20`). | Show and assert the observed program source digest and schema projection fingerprint. Neither value is present in the current debug header. |
| Ordered renderer candidates and the exact selection used for the action | **Incomplete** | `src/seon/render.clj:207-237,310-484` derives the ordered explicit-value, explicit-request, namespace, schema and floor stages and records that exact decision in call static evidence. `src/seon/render/web.clj:965-987,1193-1232` reads selection from the captured `render-call`, rather than deciding twice. `test/seon/render_simplification_test.clj:83-189` positively covers arity compatibility, precedence and captured floor selection. | The UI still omits each candidate's declared arities/contracts, the supplied argument, source/function navigation and alternate-preview action required by the PRD. More critically, the current live plan-item case selects `my.plan/render-item-html` and then refuses its pulled input because `:my.plan.item/agent` is `{:db/id 31810}`. Selection and invocation therefore are not yet proved to answer one connected question for an arbitrary referenced entity. Cause remains under investigation. |
| Actual selected output, with flat failures visible | **Incomplete** | `src/seon/render/web.clj:989-1010,1198-1232` displays the actual return of the captured `render-call` and preserves acquisition/render errors. A namespace browser observation showed real heading, docstring and declaration before a requery-bearing omission (`design-lab-inspection-slice-2026-09-05.md:11-20`). | Make the live plan-item selected call succeed with the same prepared argument selection checked, then assert its displayed captured output. The earlier 63,913-character HTML result was wholly reduced to an elision (`design-lab-inspection-slice-2026-09-05.md:411-417`), so readable useful-prefix output is also not generally proved. |
| Producing Clojure form from actual acquisition/invocation evidence | **Missing** | The current captured evidence records producer, argument, declaration, selection and return (`src/seon/render.clj:474-484`), but no executed source form; `src/seon/render/web.clj:1193-1232` consequently has none to display. The current owner direction requires the form beside the rendered value and forbids reconstructing it from printed output (`../plan/unsettled.md:102-116`). | Record the actual form at the existing call construction/evaluation seam and render that retained evidence. Do not synthesize a plausible form from the producer, argument or printed result. |
| Raw structure and datoms remain secondary but inspectable | **Proved** | `src/seon/render/web.clj:821-850` puts structural data and raw datom evidence behind disclosures while `:989-1010` makes the rendered result the primary panel. `test/seon/render/web_test.clj:802-858` positively asserts result-first order, optional actual function doc and collapsed raw evidence. | No first-slice action. |
| Prompt comparison is secondary and labels historical versus computed values | **Incomplete** | Prompt acquisition is opt-in, so it does not block initial inspection paint (`src/seon/render/web.clj:616-723,2453-2470`). | Current `debug-prompt` chooses a captured prompt *or* a prospective prompt. It does not show the requested historical capture and newly computed preview as two separately labelled values when both exist. |
| Relevant database changes repaint the same open page | **Incomplete** | A prior same-tab proof saw a namespace doc appear and disappear at new bases without reload (`design-lab-inspection-slice-2026-09-05.md:339-344`), and `test/seon/render/web_test.clj:913-933` positively exercises a real socket feed after a transaction. | Repeat the database-change proof on the current integrated data/graph/selection/output surface. The latest browser graph probe explicitly did not perform it (`../plan/unsettled.md:20-22`). |
| Rendering-code evaluation repaints without browser refresh | **Incomplete** | The running topology gives evaluation its own input and invalidates retained calls in `src/seon/cluster.clj:430-443,2330-2343` and `src/seon/render/web.clj:1777-1925`. `test/seon/render/web_test.clj:1015-1058` positively covers two evaluation signals interleaved with database wakes. | Re-evaluate an actual selected helper while the same browser page remains open and assert its displayed output changes and restores. The focused test substitutes the page function and offers channel values directly; it does not prove MCP evaluation through browser delivery. |
| Unchanged or unrelated facts reuse acquisition, discovery and invocation | **Incomplete — current falsifier red** | `src/seon/render/web.clj:1060-1137` retains the bounded observation through the existing read-evidence mechanism, and the unchanged-database case is covered at `test/seon/render/web_test.clj:935-956`. | The latest focused unrelated-transaction test reports 2 passes, 2 failures: observation stays at one call, but discovery and invocation rise from 1 to 2 (`../plan/unsettled.md:133-154`). The first slice cannot claim relevant-input caching until that exact regression reaches 1/1/1 for the unrelated transaction and 2/2/2 only for selected-data change. |
| Current browser interaction and responsive layout | **Proved for the bounded one-hop graph, not for the milestone as a whole** | The committed browser probe positively checked one Cytoscape instance, retained viewport/selection, edge detail, physical navigation, 390-pixel containment and no JavaScript errors on the actual 34-node/33-reference model (`../plan/unsettled.md:11-22`). | The same run did not prove live database update, code update, two viewers, producing form or successful plan-item output. Its clean JavaScript console cannot certify those unexercised paths. |

## Smallest coherent remaining integration action

Close the selected-input mismatch at the existing `render-call` boundary with
one regression whose pulled plan item includes a reference and whose displayed
selection, prepared argument and return all come from the same captured call.
At that same evidence boundary, retain the actual producing Clojure form and
show it beside the return. These are the two missing inputs to a decisive
already-open-browser proof; neither warrants a new route, renderer, cache or
graph owner.

Then run one bounded browser sequence over the existing debug route: inspect the
same subject through two viewers and the agentless route; change and restore one
selected datom; re-evaluate and restore the selected helper; make one unrelated
transaction. Assert the header including program identity, producing form,
ordered selection, prepared argument, actual output, linked data and graph after
each relevant change, and assert no discovery/invocation increase for the
unrelated change. That single sequence closes the connected first-slice claim;
it does not claim the later N-hop, generated-context, temporal-teaching or
displayed-fact-coverage experiments are implemented.
