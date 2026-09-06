---
type: research
status: complete
tags: [research, context, render]
---

# Design Lab first inspection slice completion audit — 2026-09-06

## Updated checkpoint at `b979cbac1`

The earlier table below is retained as a dated audit of `c94e68e11`, not the
current implementation status. The following evidence supersedes its affected
rows:

- **Alternate execution is implemented.** The ordered comparison invokes every
  distinct compatible candidate for AI and HTML through `render-call`, retaining
  the actual result. Both projections show selected, shadowed, rejected and
  missing choices. The selection owner supplies the stages; the UI does not
  implement a second preference algorithm (`e8b2038d4`, `f8f0d4ca9`).
- **Shared caching is proven by invocation counts.** Eight focused checks passed
  131 assertions. Distinct display IDs reuse one SCI invocation; unrelated
  transactions preserve it; changed queried facts invoke again. Observation and
  selection reuse are covered separately. This is not a full-suite verdict.
- **Live invalidation is reverified with that cache installed.** The title update
  at basis 536871429 reached both paired projections. A selected renderer SCI
  restoration reached HTML at unchanged basis 536871430. The same graph instance,
  container, zoom and pan survived without navigation or JavaScript errors. See
  `debug-live-feed-proof-2026-09-06.md` for exact evidence and reproducible probe.
- **Complete acquired output and responsive containment are verified.** The
  namespace browser checks actual function definitions in AI and summaries in
  HTML; 1440- and 390-pixel document widths stayed contained. Rendering caps are
  disabled by owner direction; query-work bounds remain separate. The root
  entity-id regression was fixed and covered through generic invocation.

The refreshed two-viewer and controls probes at `44eed6021` also pass on the
ranked layout: same subject and snapshot, namespace selection for `my.plan`,
schema selection for `seon.flow`, actual argument/contract disclosures and
function/ref navigation. MCP before and after those read-only probes returned
basis 536871430 and the same sole agent entity 31810: inspecting agentless
namespaces created neither agent nor transaction.

**Still incomplete:** the requested actual producing source and ordinary stored
form/result integration. Renderer invocation evidence must not be relabelled as
an executed source string. The existing source-run implementation work remains
paused under the owner's visualization-first steering. No parser, interpreter,
or run-storage redesign was added in this checkpoint. Full context generation,
settled agent-authored customization, and broader graph scale trials remain
unproven. The active goal is therefore not marked complete.

## Earlier audit

This is a point-in-time audit of repository HEAD `c94e68e11` and the live
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
| Arbitrary subject, independent viewer, and agentless read-only inspection | **Proved for the current two-viewer fixture** | `debug_two_viewers_probe_2026_09_06.cjs` positively asserts subject 32011 through `seon.flow` and `my.plan`, identical snapshot/ref graph and actual output, distinct viewer labels and selection stages. Before/after MCP queries found one root agent and unchanged basis 536870954; see `debug-live-feed-proof-2026-09-06.md`. | No first-slice action. This does not claim settled agent-authored customization. |
| Header identifies the observation and its world | **Proved** | Commit `54e63332c` adds indexed source digest from the handed sovereign database and projection fingerprint from the handed SCI context. The stronger two-viewer browser probe asserts both; the live-feed proof records exact values. | No first-slice action. Hot-reloaded JVM code and indexed source remain distinct identities. |
| Ordered renderer candidates and the exact selection used for the action | **Proved for inspection; alternate execution incomplete** | `c94e68e11` displays retained arguments and contracts/arities, linked candidate/selected function identities, actual precedence and AI/HTML controls. Candidate checks passed 84 assertions. `debug_candidate_controls_probe_2026_09_06.cjs` physically verifies argument disclosure, contract, projection round-trip and definition navigation without losing viewer/subject. | Alternate candidate execution remains unimplemented; links to a function do not imply it ran. |
| Actual selected output, with flat failures visible | **Proved for namespace and referenced plan-item fixtures** | The committed live-feed browser probe asserts actual plan output after the shared preparation fix, then observes a selected SCI renderer redefinition and restoration. The namespace browser probe shows useful bounded output. See `debug-live-feed-proof-2026-09-06.md` and `debug_browser_probe_2026_09_06.cjs`. | No first-slice action for these fixtures. This is not a universal readability proof for every value shape. |
| Producing Clojure form from actual acquisition/invocation evidence | **Missing** | The current captured evidence records producer, argument, declaration, selection and return (`src/seon/render.clj:474-484`), but no executed source form; `src/seon/render/web.clj:1193-1232` consequently has none to display. The current owner direction requires the form beside the rendered value and forbids reconstructing it from printed output (`../plan/unsettled.md:102-116`). | Record the actual form at the existing call construction/evaluation seam and render that retained evidence. Do not synthesize a plausible form from the producer, argument or printed result. |
| Raw structure and datoms remain secondary but inspectable | **Proved** | `src/seon/render/web.clj:821-850` puts structural data and raw datom evidence behind disclosures while `:989-1010` makes the rendered result the primary panel. `test/seon/render/web_test.clj:802-858` positively asserts result-first order, optional actual function doc and collapsed raw evidence. | No first-slice action. |
| Prompt comparison is secondary and labels historical versus computed values | **Proved for displayed comparison** | `c94e68e11` retains both observations with separate bases. Two focused Vars passed 83 assertions. `debug_prompt_browser_probe_2026_09_06.cjs` opens the disclosure and positively observes a real capture at basis 536870973 and nonempty computed preview at basis 536871155, without JavaScript errors. | Provider-byte parity remains a separate later context experiment; this proof establishes honest comparison labels and actual content. |
| Relevant database changes repaint the same open page | **Proved** | `debug_live_feed_probe_2026_09_06.cjs` received an actual title transaction and added dependency ref at basis 536870954, changing graph nodes/edges 2/1 to 3/2 while retaining container, instance, zoom and pan. Exactly one initial navigation; no JavaScript errors. | No first-slice action; exact fixture and mutation are persisted in the live-feed proof. |
| Rendering-code evaluation repaints without browser refresh | **Proved** | MCP SCI redefinition of selected `my.plan/render-item-html` reached the already-open browser at unchanged database basis; original definition restored. `289c9913f` fixes closed-page invalidation. Stronger two-viewer browser assertion verifies reopened headers; `c0ccb0373` extends the real-proc regression to zero watchers (81 pass, 0 fail, 0 error). | No first-slice action. The temporary SCI definition proof does not replace the later settled agent-definition proof. |
| Unchanged or unrelated facts reuse acquisition, discovery and invocation | **Proved by the focused counter regression** | `src/seon/render/web.clj:1060-1137` retains the bounded observation through the existing read-evidence mechanism. After `b080f42d1` bounded the selected namespace renderer's reads, `unrelated-transaction-reuses-debug-observation-and-render-call` passed with observation/discovery/invocation counts 1/1/1 initially, 1/1/1 after an unrelated transaction and 2/2/2 after the selected namespace changed (76 pass, 0 fail, 0 error, reported 2026-09-06). | Browser timing and useful output remain separate acceptance facts; this focused result proves the cache decision and call counts. |
| Current browser interaction and responsive layout | **Proved for the bounded one-hop graph** | The committed graph browser probe checks retained instance/viewport/selection, edge detail, physical navigation and 390-pixel containment. Separate live-feed and two-viewer probes now cover actual transactions, SCI evaluation and namespace preference. | Producing form, candidate disclosures and dual prompt comparison are separate incomplete requirements below; clean JavaScript alone does not certify them. |

## Remaining integration work

The current positive browser evidence closes the selected-input, header,
two-viewer and live-delivery gaps. Do not repeat those broad sequences merely
because this audit's previous dated rows were red.

Candidate contracts, supplied arguments, function navigation, projection controls
and separately labelled captured/prospective comparison now have focused and
actual browser proof. Alternate candidate execution remains incomplete.
Actual producing source is still missing from the debug inspection: `debug-producing-form-evidence-2026-09-06.md`
proves the current kernel directly applies a SCI Var. Reconstructing a plausible
call in the UI would not prove that source executed. Investigate the existing
reader, generated entry source and result bindings before changing that owner.

Latest owner direction drops the separate thinking field. Comments and forms
are ordinary source; the existing reader/evaluator produces actual ordered
results, which are never reparsed as executable source. The persisted primer
probe demonstrates that path on three forms, but does not yet connect it to
production debug invocation. This goal remains incomplete.

The owner's subsequent clarification chooses the normal durable source-run path,
not a parser invocation added to each renderer. The live two-form source run
proved ordinary result bindings and stored results after exposing missing pending
evaluation records in system-run creation. Its durable-source UI and production
submission/invariant fix remain incomplete; see
`debug-producing-source-integration-2026-09-06.md`. Default namespace output
still spends its fitted prefix on declaration source before useful summaries,
recorded in `docs/seon/issues/namespace-layout-confines-most-content-to-scroll-boxes.md`.
