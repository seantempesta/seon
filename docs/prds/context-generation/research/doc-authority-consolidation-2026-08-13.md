---
type: research
status: complete
date: 2026-08-13
tags: [documentation, architecture]
---

# Documentation authority consolidation — 2026-08-13

## Verdict

The owner's leaning is correct: dissolve
[docs/conventions.md](../../../conventions.md). Nothing in it needs that file
to survive. Most of its ideas are already binding in
[AGENTS.md](../../../../AGENTS.md), operationalized in a skill, or properly
owned by an architecture domain. The genuinely useful residue is small:
namespace/file organization, docstring presentation, and the instrumentation
step after manual Var re-evaluation. Those belong in the
`data-oriented-clojure` and `repl` skills (with the existing namespace
stewardship reference), not in another repository-wide authority.

The architecture directory still earns a place, but not in its present form.
Keep the map and five durable domain contracts: context, data model, agent
runtime, UI, and observability. Shrink each to intended boundaries and
mechanisms. Dissolve the `toolkit`, `laws`, and `library-grounding` documents:
the first is a drifting catalog written in vocabulary the new baseline has
retired, the second mixes binding law with dated measurements, and the third
duplicates the dependency ledgers that specialized skills already maintain.

[docs/TRANSFER_PROMPT.md](../../../TRANSFER_PROMPT.md) should remain only as a
short orchestrator handoff document: where the working edge is, how this owner
works, the three-field handoff, and orchestration lessons that are not binding
repository law. Its project mentality, skills catalog, development loop,
commands, and standing rules now duplicate `AGENTS.md`.

I read every audited file end to end. The architecture risk checks below use
three concrete claims per document against `HEAD`; they are spot checks, not a
full architecture claim audit. The Seon MCP runtime tools were unavailable in
this session, so this documentation-only audit uses repository and vendored
source evidence. No audited file was edited.

## Per-document verdict

Risk means the likelihood that the document will misdirect a reader before its
next intentional maintenance pass, not the importance of its subject.

| Authority | Distinct job today | Staleness risk and three-claim HEAD spot check | Overlap with `AGENTS.md` | Final verdict |
|---|---|---|---|---|
| [AGENTS.md](../../../../AGENTS.md) | The one binding repository instruction authority: operating law, design laws, vocabulary, development/test/operation/collaboration runbook. | Baseline, not scored. Its explicit precedence rule is at [AGENTS.md:1](../../../../AGENTS.md#L1). | N/A. Other agent-facing authorities must point here, not restate it. | **Keep.** Every standing rule and settled term lives here once; change it in the same commit as the code/ruling that changes the rule. |
| [docs/conventions.md](../../../conventions.md) | A second agent-facing coding and runtime standards manual, plus examples. | **High.** Most content duplicates deeper owners; its bare-run test claim is wrong (details below). | Heavy overlap with sections 1–5, especially [data/schema](../../../../AGENTS.md#L284), [REPL workflow](../../../../AGENTS.md#L425), and [testing](../../../../AGENTS.md#L497). | **Dissolve completely.** Move the small unique residue to skills/reference owners; delete the pointer from `AGENTS.md`. |
| [docs/seon/architecture/architecture.md](../../../seon/architecture/architecture.md) | System thesis, deployment topology, cross-domain map, and pointers to domain contracts. | **High.** **Hit:** fenced store/branch topology matches [src/seon/cluster/store.clj:2](../../../../src/seon/cluster/store.clj#L2) and [src/seon/cluster.clj:1613](../../../../src/seon/cluster.clj#L1613). **Hit:** per-agent graphs match [src/seon/cluster/agent.clj:341](../../../../src/seon/cluster/agent.clj#L341). **Miss:** its unmarked `my.*` catalog at [architecture.md:625](../../../seon/architecture/architecture.md#L625) does not match HEAD's actual `src/my/` namespaces. | Repeats most of [AGENTS.md §1](../../../../AGENTS.md#L63), all five design laws, transport, hot reload, testing, and much vocabulary. | **Keep, cut by roughly two thirds.** It should orient and link, not repeat each domain or enumerate APIs. |
| [docs/seon/architecture/context.md](../../../seon/architecture/context.md) | Intended prompt acquisition, rendered history, continuity, ordering, and shared agent/human projection. | **Medium.** **Hit:** all three projections exist at [resources/seon/schemas/seon.render.edn:20](../../../../resources/seon/schemas/seon.render.edn#L20). **Hit:** retained history is implemented at [src/seon/render/walk.clj:849](../../../../src/seon/render/walk.clj#L849) and [src/seon/render/web.clj:843](../../../../src/seon/render/web.clj#L843). **Hit:** cluster/agent instruction refs exist at [resources/seon/schemas/seon.cluster.edn:7](../../../../resources/seon/schemas/seon.cluster.edn#L7) and [resources/seon/schemas/seon.cluster.agent.edn:46](../../../../resources/seon/schemas/seon.cluster.agent.edn#L46). | Repeats values-carry-world, derive-state, total rendering, config, and context-not-callability from sections 1–3. | **Keep, trim.** Own only context semantics and invariants; send config mechanics to `seon-context-config`, render delivery to UI, and transcript forensics to observability. |
| [docs/seon/architecture/data-model.md](../../../seon/architecture/data-model.md) | Intended durable graph, identity/ref relationships, presence-based state, and semantic ownership of fact families. | **High.** **Hit:** context-capture facts match [resources/seon/schemas/seon.context.capture.edn:11](../../../../resources/seon/schemas/seon.context.capture.edn#L11). **Hit:** routes remain code at [src/seon/render/route.clj:5](../../../../src/seon/render/route.clj#L5). **Miss:** it says runs do not own forms through a component collection at [data-model.md:145](../../../seon/architecture/data-model.md#L145), but HEAD declares `:seon.cluster.run/forms` as a component set at [resources/seon/schemas/seon.cluster.run.edn:5](../../../../resources/seon/schemas/seon.cluster.run.edn#L5) and transacts it at [src/seon/cluster/run.clj:696](../../../../src/seon/cluster/run.clj#L696). | Its modeling laws duplicate [AGENTS.md §3](../../../../AGENTS.md#L284), `data-modeling`, and `datahike`. | **Keep, reframe.** Retain semantic relationships and why; replace the hand-maintained exhaustive attribute census with schema queries/links. |
| [docs/seon/architecture/agent-runtime.md](../../../seon/architecture/agent-runtime.md) | Intended run custody, per-agent graph, SCI turn fork, receipt settlement, scheduling, and crash recovery. | **High.** **Hit:** one graph blueprint per agent is current at [src/seon/cluster/agent.clj:341](../../../../src/seon/cluster/agent.clj#L341). **Hit:** fresh generation-aware fork plus agent-def restoration is current at [src/seon/sci/eval.clj:1541](../../../../src/seon/sci/eval.clj#L1541). **Miss:** “creation produces an idle agent; inbound messages open bounded runs” at [architecture.md:599](../../../seon/architecture/architecture.md#L599) omits the current system-authored generated opening owned by [src/seon/bootstrap.clj:1](../../../../src/seon/bootstrap.clj#L1) and the generated-run contract at [resources/seon/schemas/seon.cluster.run.edn:47](../../../../resources/seon/schemas/seon.cluster.run.edn#L47). | Repeats boot/Flow/crash/errors/transport laws and much of the `seon-flow-architecture` and `repl` skills. | **Keep, correct and trim.** Own durable state transitions and recovery; skills own how to edit/probe them. |
| [docs/seon/architecture/ui.md](../../../seon/architecture/ui.md) | Intended pages, render projections, block identity, routing, browser mutation boundary, and live Datastar delivery. | **Medium.** **Hit:** the route table exactly matches [src/seon/render/route.clj:5](../../../../src/seon/render/route.clj#L5). **Hit:** revisioned keyframe/delta packages exist at [src/seon/render/web.clj:685](../../../../src/seon/render/web.clj#L685). **Hit:** per-tab sliding-one taps exist at [src/seon/render/web.clj:1249](../../../../src/seon/render/web.clj#L1249). | Repeats total bounded rendering, transport buffers, workload law, and vocabulary; delivery mechanics overlap `datastar-web-ui`. | **Keep, trim.** Own the user-visible architecture and mutation boundary; move current route/feed procedures and tuning into `datastar-web-ui`. |
| [docs/seon/architecture/observability.md](../../../seon/architecture/observability.md) | Intended evidence spine connecting messages, runs, prompt captures, attempts, receipts, errors, and operator evidence. | **Medium-high.** **Hit:** prompt/basis/contributions exist at [resources/seon/schemas/seon.context.capture.edn:11](../../../../resources/seon/schemas/seon.context.capture.edn#L11). **Hit:** attempt settings and failover links exist at [resources/seon/schemas/seon.ai.edn:18](../../../../resources/seon/schemas/seon.ai.edn#L18). **Miss:** its source-authority list names nonexistent `src/seon/render/root.clj` at [observability.md:241](../../../seon/architecture/observability.md#L241); HEAD has `agent`, `ns`, `transcript`, `walk`, and `web`, but no root owner file. | Repeats error classes, provenance, recovery, and operator facts from sections 1, 3, and 6. | **Keep, trim.** Own the forensic questions and joins, not a file-by-file implementation roster. |
| [docs/seon/architecture/toolkit.md](../../../seon/architecture/toolkit.md) | Protected-vs-editable capability boundary plus a hand-written catalog of `my.*` namespaces. | **High.** **Hit:** `seon.effect/request!` exists at [src/seon/effect.clj:693](../../../../src/seon/effect.clj#L693). **Hit:** the Lucene-derived search owner exists at [src/seon/search.clj:1](../../../../src/seon/search.clj#L1). **Miss:** the “intended” namespace list at [toolkit.md:71](../../../seon/architecture/toolkit.md#L71) names mostly absent namespaces and omits current `my.background`, `my.edit`, `my.message`, `my.note`, and `my.run`; see [src/my/background.clj:1](../../../../src/my/background.clj#L1) through [src/my/web.clj:1](../../../../src/my/web.clj#L1). | The protected effect boundary and every-function-callable rule already live in sections 1 and 3; `toolkit` itself is retired vocabulary at [AGENTS.md:398](../../../../AGENTS.md#L398). | **Dissolve.** Move the small capability-boundary thesis into the architecture map; move canvas to UI, branch work to its PRD, and derive actual functions/namespaces from the program graph. |
| [docs/seon/architecture/laws.md](../../../seon/architecture/laws.md) | A mixture of empirical findings, binding design rules, exact measurements, test policy, and process advice. | **High.** **Hit:** per-agent Flow graphs and explicit workloads match [src/seon/cluster/agent.clj:341](../../../../src/seon/cluster/agent.clj#L341) and [src/seon/flow.clj:128](../../../../src/seon/flow.clj#L128). **Unverified as a HEAD fact:** the 8.5 KB/1,000-agent measurement at [laws.md:74](../../../seon/architecture/laws.md#L74) is repeated in a source docstring, not continuously proven. **Qualified miss:** “no data migration path exists” at [laws.md:118](../../../seon/architecture/laws.md#L118) is too absolute while [src/seon/cluster/export.clj:65](../../../../src/seon/cluster/export.clj#L65) deliberately uses `datahike.migrate` for export/import. | Duplicates the five binding laws, testing law, operating law, and archaeology mentality. Exact measurements violate architecture's own timeless/evidence boundary. | **Dissolve.** Binding survivors go to `AGENTS.md`; mechanism-specific implications go to skills; dated numbers stay only in PRD research. |
| [docs/seon/architecture/library-grounding.md](../../../seon/architecture/library-grounding.md) | A cross-domain map from concepts to first-party owners, vendored files, and pinned revisions. | **Medium-high.** **Hit:** all nine pinned submodule hashes match HEAD. **Hit:** route/Datastar owners exist at [src/seon/render/route.clj:1](../../../../src/seon/render/route.clj#L1) and [src/seon/render/web.clj:1](../../../../src/seon/render/web.clj#L1). **Miss:** the schema seam points to `src/seon/schema/datahike.cljc` at [library-grounding.md:36](../../../seon/architecture/library-grounding.md#L36), but HEAD's owner is [src/seon/schema/datahike.clj](../../../../src/seon/schema/datahike.clj). | Duplicates the archaeology/dependency-ledger rule and the source maps embedded in specialized skills. | **Dissolve.** Each specialized skill maintains its exact first-party/dependency seam; bounded research records revisions used for a dated result. |
| [docs/TRANSFER_PROMPT.md](../../../TRANSFER_PROMPT.md) | Orientation, current-edge pointers, owner working style, handoff shape, and accumulated orchestration lessons. | **High in current form.** It embeds current-state prose at [TRANSFER_PROMPT.md:203](../../../TRANSFER_PROMPT.md#L203), despite assigning that authority to `unsettled.md`; it also repeats almost all binding mentality and workflow. | Heavy overlap with “How we work,” REPL, testing, operating, and collaboration. | **Keep as a short orchestrator-only handoff.** Never embed current state or duplicate binding law. |

## `docs/conventions.md` idea disposition

Classification: **A** = already covered by `AGENTS.md`; **B** = operationally
covered by a skill; **C** = architecture-owned; **D** = unique and worth
retaining elsewhere; **E** = stale or contradicted. Multiple letters mean the
idea currently has more than one legitimate destination; it still does not
justify `conventions.md`.

| Idea in `docs/conventions.md` | Class | Existing owner or destination |
|---|---|---|
| Agent-readable contracts: namespaced keys, schemas, queryable program facts | A, B | [AGENTS.md §2.2](../../../../AGENTS.md#L186) and [§3](../../../../AGENTS.md#L284); `data-oriented-clojure`. |
| Runtime tiers, one process/many clusters, Flow scheduling, guarded eval | A, B, C | [AGENTS.md §1](../../../../AGENTS.md#L63); `seon-flow-architecture`; the architecture map. |
| `.clj` versus genuinely portable `.cljc` | B, C | `data-oriented-clojure` and the capability boundary in the architecture map. The CLJS/pod warning is already binding in `AGENTS.md §1`. |
| Sibling `.internal` namespace for plumbing | D | Move the concise “only when plumbing obscures the public contract” rule to `data-oriented-clojure`; it is code organization, not architecture. |
| Malli purpose and `::` keyword tutorial | A, B | Contract requirements are in `AGENTS.md §3`; namespaced-key mechanics belong in `data-oriented-clojure`. The generic `::` tutorial can simply disappear. |
| Shipped EDN schema population and runtime registration | A, B | `AGENTS.md §3`, then `data-modeling` for design and `datahike` for admission/transact mechanics. |
| Open Malli maps and accretion/breakage | A | [AGENTS.md §2.5](../../../../AGENTS.md#L262) is the binding authority. |
| Identity/unique/component/index/no-history facets | B | `data-modeling` and `datahike`, grounded at the bridge owner. |
| Shared shapes declared once | B | `data-modeling` (“Shared shapes”) owns it. |
| Transaction provenance instead of domain `created-*` attrs | A, B | `AGENTS.md §3`; `data-modeling` and `datahike` transaction metadata. |
| Request/response schemas and map-in versus named positional public functions | A, B | `AGENTS.md §3`; `data-modeling` and `data-oriented-clojure`. |
| Private helpers may be positional and unspecced | D | Put beside the public-function rule in `data-oriented-clojure`; no repository-wide second manual is needed. |
| Optional input defaulting and absent-not-nil persistence | A, B | `AGENTS.md §3`; `data-oriented-clojure` owns the present-nil `:or` trap. |
| Errors as flat values, semantic failure versus schema shape, transaction-function refusal | A, B | [AGENTS.md §§1 and 2.4](../../../../AGENTS.md#L63); `data-oriented-clojure` and `datahike`. |
| Host/interpreted instrumentation and re-running `instrument/apply!` after manual Var replacement | A, D | Contract instrumentation is binding in `AGENTS.md §2.4`; move the manual-reload step to `repl`, where reload-before-retest already lives. Keep implementation detail in `seon.instrument`'s docstring. |
| Schema introspection examples | B | `data-modeling`/`datahike` discovery; exact function faces are program facts. |
| `seon.db` as the one database namespace and snapshot-once reading | A, B | `AGENTS.md §3`; `datahike`. |
| EAV composition, no type/kind stamps | A, B | `AGENTS.md §3`; `data-oriented-clojure`, `data-modeling`, and `datahike`. |
| Opaque-value contracts, honest generators, authored contract refusals | A, B | `AGENTS.md §3`; `data-oriented-clojure`, `data-modeling`, and `clojure-testing`. |
| Test-code exemptions, examples, generative tests, canonical database fixture | A, B | [AGENTS.md §5](../../../../AGENTS.md#L497); `clojure-testing`. |
| Test command semantics | E | [conventions.md:494](../../../conventions.md#L494) says bare `bin/test` runs every discovered namespace. HEAD says bare is the platform tier plus tests reaching changed code; [bin/test:45](../../../../bin/test#L45) exposes `--all` and `--full`. Keep command semantics only in `AGENTS.md §5`, `bin/test --help`, and `clojure-testing`. |
| Anti-pattern catalog | A, B | Every substantive ban is already in `AGENTS.md` or the matching skill. Delete examples that teach no additional invariant. |
| One file per namespace, schemas in EDN, `test/` mirrors `src/` | D | Move the concise file-layout rule to `data-oriented-clojure`. Concrete schema layout belongs to `data-modeling`. |
| Namespace docstrings | D | Keep the full format only in [docs/seon/concepts/namespace-stewardship.md](../../../seon/concepts/namespace-stewardship.md); add a short pointer in `data-oriented-clojure`. |
| Public function docstring first-line grammar | B | Already covered by `data-oriented-clojure`; enforcement lives in [script/seon/dev/docstring.clj](../../../../script/seon/dev/docstring.clj). |
| Context/render/system-message model | A, C | `AGENTS.md` render vocabulary and total-boundary law; `architecture/context.md` and `architecture/ui.md`. |
| Comment levels | A | Exact binding rule already appears at [AGENTS.md:477](../../../../AGENTS.md#L477). |
| SSE/Datastar delivery pattern | B, C | `datastar-web-ui` for current mechanics; `architecture/ui.md` for intended boundary. |
| Hot reload versus source publication | A, B | [AGENTS.md §1](../../../../AGENTS.md#L63) and [§4](../../../../AGENTS.md#L425); `repl` and `seon-flow-architecture`. |
| Numeric limits/defaults and documented sources | A, B | [AGENTS.md §2.3](../../../../AGENTS.md#L216); `seon-context-config` and `data-modeling`. |

### Does anything resist dissolution?

No. Three useful clusters are not yet prominent enough in `AGENTS.md`, but all
have better homes:

1. `.internal`/file-layout guidance → `data-oriented-clojure`.
2. Namespace and function docstring presentation → the existing namespace
   stewardship reference plus `data-oriented-clojure`.
3. Manual Var reload removes an instrumentation wrapper → `repl`, with the
   implementation explanation remaining in `seon.instrument`'s docstring.

Keeping `conventions.md` for those three fragments would preserve hundreds of
duplicated lines and the false test-run description.

## `TRANSFER_PROMPT.md` deduplication

| Section | Orchestrator-unique residue | Duplicate to remove or replace with a pointer |
|---|---|---|
| “What Seon is” | None. | `AGENTS.md §1` now owns the system summary. |
| “The one thing to internalize” | None. | The second-implementation/archaeology mentality is now in `AGENTS.md` “How we work here.” |
| “Read these, in this order” | Keep only “working edge first,” with direct pointers to `unsettled.md`, the roadmap/rulings, and issue index. | Remove its restatement of each document's role; [AGENTS.md §4](../../../../AGENTS.md#L425) and [§8](../../../../AGENTS.md#L669) own documentation authority and pointers. |
| “Load the skills” | None beyond a pointer to `.agents/skills/`. | The skill trigger list and maintenance law are already in `AGENTS.md §4` and the live skill catalog. |
| “The loop that works” | Independent audit may remain as an orchestrator sequencing reminder if it is not made a universal implementation recipe. | Archaeology, REPL falsification, test-forward proof, live proof, and friction reporting are binding in `AGENTS.md`. |
| “Your instant feedback loop” | None. | Commands and MCP workflow belong in `AGENTS.md §§4–6`, `bin/* --help`, and skills. |
| “The warts” | None as a separate section. | Dirty-tree safety, stale JVMs, tool rot, and churn are all in `AGENTS.md §§6–7`. Partial old clusters are an operating issue/skill note, not orientation law. |
| “Start here: the current working edge” | Keep pointers only. | Delete all embedded wave status. Current state belongs exclusively to `plan/unsettled.md`; hard-coded state in an orientation doc is stale by construction. |
| “The mentality” and the dated 2026-08-01 additions | None as binding law. | Move any still-missing reusable lesson to `AGENTS.md` or a skill once, then delete the duplicate. Most are now verbatim or near-verbatim in `AGENTS.md`. |
| “If you are coordinating work” | Only details that are genuinely orchestration-only and not already binding. | Lane ownership, no nested delegation, no sandbox, tool split, and review discipline are already in `AGENTS.md §7`. |
| “Working with the owner” | **Keep.** This is owner-specific collaboration context: decisive technical options, hands-on design gates, and the simplicity question. | Remove rules already stated in `AGENTS.md` (parallel systems, derive state, one mechanism). |
| “Handoff details” | **Keep.** `STATE`, `IN FLIGHT`, and `PENDING THE OWNER` are a genuinely unique session-transfer contract. | Do not add implementation status below the template; the handoff supplies it at transfer time. |
| “Evergreen orchestration lessons” | **Keep selectively.** Retain lessons about orchestrator evidence quality, lane topology/contention, stopping/resuming lanes, independent verification, measuring inherited claims, and owner-facing coordination. | Promote binding engineering rules once to `AGENTS.md` or the matching skill, then delete them here. Move incident-specific mechanics (fixtures, Flow control, lock composition, Datahike migration, vocabulary) to dated research or specialized skills. “Add to this, never reset” must be removed; it guarantees unbounded duplication. |

The retained transfer document should be short enough to reread on every
handoff and should contain no claim about what wave is complete.

## Recommended final set

This is the minimal maintained authority set. Historical ADRs and dated PRD
research remain evidence/history, not additional current instruction
authorities.

| Authority | Exclusive job | Maintenance rule |
|---|---|---|
| [AGENTS.md](../../../../AGENTS.md) | Binding repository law, settled vocabulary, and the shared development/operation/collaboration runbook. | One standing rule or term lives here once; update in the same commit that changes the ruled behavior. |
| [docs/seon/architecture/architecture.md](../../../seon/architecture/architecture.md) | Short target-system thesis, topology, cross-domain boundaries, and links. | No implementation census, exact API catalog, dated measurement, work order, or repeated repository law. |
| [docs/seon/architecture/context.md](../../../seon/architecture/context.md) | Intended context acquisition, history/continuity, ordering, and agent/human shared-view semantics. | Describe stable contracts and failure invariants; mechanics that an editor follows belong to skills. |
| [docs/seon/architecture/data-model.md](../../../seon/architecture/data-model.md) | Intended durable relationships, identities, presence semantics, and fact-family ownership. | Schemas are the exhaustive census; architecture explains relationships and links/query examples, never manually mirrors every attribute. |
| [docs/seon/architecture/agent-runtime.md](../../../seon/architecture/agent-runtime.md) | Intended per-agent graph, run/receipt transitions, generated/ordinary episode flow, and recovery. | Update with every transition-shape change; keep edit/probe recipes in Flow/REPL/Datahike skills. |
| [docs/seon/architecture/ui.md](../../../seon/architecture/ui.md) | Intended pages/render/canvas/control boundary and human-visible delivery guarantees. | Keep route/feed implementation instructions in `datastar-web-ui`; architecture states guarantees and ownership. |
| [docs/seon/architecture/observability.md](../../../seon/architecture/observability.md) | Intended forensic questions and the evidence chain that answers them. | Describe joins and honest unknowns; do not roster source files or duplicate the schema census. |
| [docs/TRANSFER_PROMPT.md](../../../TRANSFER_PROMPT.md) | Orchestrator entry/handoff: current-edge pointers, owner working style, handoff fields, and orchestration-only lessons. | Pointers, never embedded current state; no binding mentality, skills catalog, commands, or implementation loop. |
| `docs/prds/sci-execution-runtime/plan/{README.md,unsettled.md}` | The one order/ruling ledger and the one current working edge. | All current state/order/dates live here and nowhere in orientation or architecture. |
| `docs/seon/issues/{README.md,index.md}` | Issue lifecycle/query contract and ranked open queue. | Findings enter one issue; only the owner/index mechanism ranks them. |
| `.agents/skills/*` | Task-specific, source-grounded mechanics for editing, probing, and testing one specialized boundary. | Every mechanical claim is line-grounded and reverified when touched; no repository ethos or competing architecture. |
| Bounded PRDs and `research/` | Dated evidence, measurements, inventories, rejected designs, and acceptance records. | Immutable historical conditions; current rulings/state are linked upward rather than silently rewritten. |

Not in the final maintained set: `docs/conventions.md`,
`architecture/toolkit.md`, `architecture/laws.md`, and
`architecture/library-grounding.md`.

## Dissolution and deduplication worklist

Order matters: remove poison before cosmetic shrinking.

1. **Correct active architecture poison first.** Update the run/form
   relationship, generated opening episode, current `my.*`/capability wording,
   and observability source ownership. These are factual corrections, not part
   of deletion work.
2. **Dissolve `docs/conventions.md`.** Move `.internal` and file-layout rules to
   `data-oriented-clojure`; move manual instrumentation-after-reload to `repl`;
   make the namespace stewardship doc the sole long-form namespace-docstring
   owner; rely on existing `data-modeling`, `datahike`, `clojure-testing`,
   `datastar-web-ui`, `seon-flow-architecture`, and `seon-context-config` for
   the rest. Remove the `AGENTS.md §8` conventions pointer.
3. **Shrink the architecture index.** Retain thesis/topology/domain map. Delete
   the duplicated glossary rows now authoritative in `AGENTS.md`, repeated
   domain paragraphs, test/process runbook, and API/catalog detail.
4. **Reframe `data-model.md`.** Delete the exhaustive identity/attribute
   census. Replace it with durable relationship diagrams/queries and direct
   schema-family links. The admitted EDN population remains the exact census.
5. **Trim `context.md`, `agent-runtime.md`, `ui.md`, and `observability.md`.**
   Each keeps its exclusive guarantees. Move editor-facing mechanics to the
   matching skill and exact file rosters to localized owner docs/docstrings.
6. **Dissolve `toolkit.md`.** Move the protected/effect-boundary paragraph into
   the architecture index; generalized canvas/control intent into `ui.md` and
   `ui-canvas`; `my.branch` into its bounded PRD; namespace/function discovery
   into program-graph queries. Do not retain a prose namespace catalog.
7. **Dissolve `laws.md`.** Binding laws that are not already in `AGENTS.md`
   require an explicit owner ruling before addition. Move mechanism-specific
   implications to skills and leave every number/sample/model/date in its
   research record.
8. **Dissolve `library-grounding.md`.** Put each live seam in exactly one
   specialized skill/localized owner. A dated PRD records the dependency
   revisions used by its evidence; no global pinned-hash table is maintained.
9. **Reduce `TRANSFER_PROMPT.md`.** Keep the four unique jobs identified above.
   Replace embedded working-edge prose with links. Remove “add to this, never
   reset”; curate or relocate every evergreen lesson.
10. **Run a final reference/vocabulary sweep.** Update pointers to deleted
    authorities and reject the legacy terms `toolkit`, `my.plan`/bare plan,
    and `desk` on current code-bearing/architecture surfaces except where
    explicitly described as historical vocabulary.

## Contradictions and poison list — worst first

1. **Retired capability vocabulary plus a false catalog.** `toolkit.md` and
   the architecture index teach a `my.*` surface headed by `my.blob`,
   `my.data`, `my.kb`, `my.ns`, `my.plan`, `my.skills`, and `my.ui`. Most do not
   exist at HEAD; current namespaces include `my.background`, `my.edit`,
   `my.message`, `my.note`, and `my.run`. Worse, the new baseline explicitly
   marks `toolkit`, grants, and allowlists as legacy spellings for the rule
   “every function is callable” at [AGENTS.md:398](../../../../AGENTS.md#L398),
   and marks `my.plan` as legacy vocabulary for the todo at
   [AGENTS.md:380](../../../../AGENTS.md#L380). This can cause an agent to design
   against absent owners while believing architecture authorized them.
2. **The durable run/form relationship is stated backwards.**
   [data-model.md:145](../../../seon/architecture/data-model.md#L145) says a run
   does not own forms through a component collection. HEAD both keeps the
   form's forward `/run` ref and adds each form to the run's component
   `:seon.cluster.run/forms` set at
   [resources/seon/schemas/seon.cluster.run.edn:5](../../../../resources/seon/schemas/seon.cluster.run.edn#L5)
   and transacts that ownership at
   [src/seon/cluster/run.clj:696](../../../../src/seon/cluster/run.clj#L696).
   This is exactly the kind of schema-design claim that must not be wrong.
3. **Fresh-agent execution omits the generated opening episode.** The runtime
   architecture still presents creation-as-idle followed by inbound-message →
   model-call → frozen-plan as the whole opening story. The baseline now names
   the generated opening episode and reduce as distinct current mechanisms at
   [AGENTS.md:395](../../../../AGENTS.md#L395), grounded in
   [src/seon/bootstrap.clj:1](../../../../src/seon/bootstrap.clj#L1). A runtime
   designer following the old story can rebuild the deleted bootstrap-plan
   shape or miss the real generated-form path.
4. **`TRANSFER_PROMPT.md` stores current state in the wrong authority.** Its
   “Start here” section embeds a completed-wave snapshot at
   [TRANSFER_PROMPT.md:203](../../../TRANSFER_PROMPT.md#L203), while both that
   document and `AGENTS.md` say `unsettled.md` alone owns the working edge.
   Even an accurate snapshot becomes poison as soon as the next landing moves
   the edge.
5. **Empirical `laws.md` violates the architecture/evidence boundary.** It
   carries exact heap, timing, throughput, storage, and census numbers inside
   an always-current architecture document, although architecture says dated
   measurements live in PRD research. Repetition in a source docstring is not
   revalidation. The document also states “no data migration path exists”
   while a deliberate export/import owner uses `datahike.migrate`; at minimum
   the claim must be narrowed to schema evolution of live clusters.
6. **`data-model.md` is already an incomplete census.** Beyond the direct
   component contradiction, HEAD run facts include current `background-results`
   and `undisposed-at` shapes at
   [resources/seon/schemas/seon.cluster.run.edn:67](../../../../resources/seon/schemas/seon.cluster.run.edn#L67),
   demonstrating why prose cannot remain the exact attribute authority.
7. **Observability names a nonexistent owner.** The source-authority section
   cites `src/seon/render/root.clj`; root rendering currently lives through
   the existing `agent`, `ns`, `transcript`, `walk`, and `web` owners. This is
   a small instance of a high-risk class: a reader is directed to a file that
   cannot falsify the prose.
8. **The global library map has already drifted.** It points schema derivation
   at `src/seon/schema/datahike.cljc`; HEAD owns it at
   [src/seon/schema/datahike.clj](../../../../src/seon/schema/datahike.clj).
   The hashes happen to be current, but the first-party seam does not.
9. **`conventions.md` misstates the ordinary test gate.** Bare `bin/test` no
   longer runs every `*_test` namespace. The maintained semantics are the
   platform tier followed by tests reached from changed code; `--all` adds all
   non-long tests and `--full` adds all tests. A workflow authority that
   overstates what ran produces false green claims.
10. **Current architecture still uses retired “desk” language.**
    `agent-runtime.md` says uncontracted definitions remain in agents' desks,
    while [AGENTS.md:420](../../../../AGENTS.md#L420) settles “the agent's defs”
    and lists “the desk” only as a legacy spelling. Vocabulary drift here is
    easy to remove and should not survive the consolidation.

## Consolidation acceptance test

The consolidation is complete when a new session can answer each question from
one place:

- “What must I do?” → `AGENTS.md` or one triggered skill.
- “What is Seon intended to be?” → the short architecture map and exactly one
  domain contract.
- “What is true now and what comes next?” → `unsettled.md` and the roadmap.
- “Why was this decided or measured?” → one ADR/PRD/research record.
- “What is broken?” → the issue authority.
- “How do I hand this orchestration to the owner/next session?” → the slim
  transfer document.

No answer should require consulting `conventions.md`, a prose function catalog,
a second law list, or a global dependency-hash roster.
