---
type: design-review
created: 2026-09-25
verdict: ACCEPT-AFTER-FIXES
reviewed-design: task-context-install-design-2026-09-25.md at 82f73c7dd
source-pin: dc4f71d67
supersedes: review 595048666
scope: Read-only independent review; this document is the only authored file
---

# Task context installation: round 3 independent review

**ACCEPT-AFTER-FIXES: the seven numbered corrections below are required before the affected activation.** Accept the owner's architecture: the context is the rendering of the agent entity; declared references connect its graph; render-function presence decides inclusion without mode keywords; acquisition joins whole frontiers; definitions precede uses; each node renders once through the shared composer. Do not restore the separate task-view envelope or taught-tool exclusion list from round 2.

What exists is a per-entity acquisition feeding `neighborhood`, with additional acquisition inside namespace and agent renderers. The principal Clojure composition is one held database value → declared edge joins → ordered acquired values → existing render selection and retention → `units`. The remaining problems are specific inconsistencies in that composition, not a reason to add another walker, registry, evaluator or cache.

## Evidence and limits

`D` means [the reviewed design](task-context-install-design-2026-09-25.md), specifically **82f73c7dd**; `Q` means [query/render building block](query-render-building-block-2026-09-25.md) at the source pin. The earlier eight findings are recoverable in **595048666**, not appended here as a second plan. Read the revised design, prior review, relevant building-block sections, language requirements, supplied AGENTS.md, and Clojure, REPL and Datahike skills. Inspected the source owners, dependency implementation and first-party callers cited below.

Source citations and counts are pinned to **dc4f71d67**, HEAD when review began. HEAD advanced to **f29fa7634** during review; `git diff dc4f71d67 HEAD -- src resources test reference-code` was empty at that check. HEAD later advanced through **54c550e4c**, including another lane's schema-shape changes. A final path diff confirmed the counted walker, namespace-render, agent, turn, bootstrap and issue-opening files still matched the source pin. Other citations retain their pinned coordinates; no foreign installation is inferred. Counts use committed files, not another lane's dirty source/schema edits. Datahike is **4157916b5a1b8d80d7f809c889bce1b098b1d75d**; Malli is **befcd302a8e626a67d84b80aabee0cd1b38dadb6**.

**No tests, Clojure evaluations, reloads, adoption, provider calls, status mutations or source edits were run.** Loadability below is static dependency review, not a successful load. No foreign failure was diagnosed or used to stop. The design's timings and node counts are author-reported, not repeated measurements. No runtime timing or memory evidence was acquired. The parallel render-graph research document was not present as a tracked file at the check; no evidence is attributed to it.

## Disposition of the original eight fixes

“Closed” here closes a design objection, not an implementation witness. Where the replacement still fails a requirement, that is stated rather than accepting D §11's “Applied” as proof.

| Original fix | Round-3 disposition | Evidence and remaining obligation |
|---|---|---|
| **1. Refresh from real dependencies** | **Superseded in its proposed remedy; not fully closed** | D §2.6 explicitly refutes source constants; §5 uses captured plans, requests and pulls, retains unknown/`:all`, and makes context first. That is the right authority. The restart claim and positive-outside classification still need correction **5**. Deferring all scoped refresh is no longer required if these obligations are met. |
| **2. Do not rely on relevance to prevent effects** | **Closed as a design finding** | D §0.5 removes that claim and makes the effect producer fix a prerequisite of T2; §9.6 requires a no-replay witness. This does not prove today's completion calls safe. W2 must not acquire T2's filtering early or represent existing refresh as repaired. |
| **3. Reference families and explicit precedence** | **Superseded for taught-tool policy; not fully closed for coverage** | D §4.1 names edges and data gaps; §4.3 specifies N=0–3 and makes N=3 the agent default. No exclusion modes remain. But the measured five queries do not implement the table, and the claim that all must-reads have positive distance is false for transitive function/namespace must-reads. Corrections **1–2**. |
| **4. Traversal and honest work bounds** | **Superseded for the task-specific walker; not fully closed for bounds** | D replaces the generic acquisition in place and uses one composer. It names caps, deadlines and cuts (§4.2), but does not connect them to the actual query options or justify unconditional index-only cost. Correction **1**. |
| **5. One declared value and both faces before activation** | **Closed for the old envelope/fallback and split activation; replacement prerequisites incomplete** | D §4.6 makes W1 unused and W2 depend on Q S2/S3. The old unsupported-composition fallback disappears. The proposed schema “pair” is a form producer, and task HTML and pure node inputs remain unspecified. Correction **3** before W2. |
| **6. Assignment, migration and MCP** | **Closed for assignment/error/MCP decisions; migration ordering not closed** | D §§4.6, 6 require a standing root task and existing-agent assignment; distinguish absent agent/task/dangling link; name MCP's shared-REPL identity prerequisite. W2 nevertheless removes the sole issue-opening caller while promising unmigrated workers keep it. Correction **7**. |
| **7. Renderer reads and callable expansion** | **Closed for the stated direction and map-shaped program calls; implementation coverage incomplete** | D §§4.1, 4.4 use `{:seon.program/subject 'f}`, remove situation acquisition from AI renderers, and keep current root observations callable. Agent HTML and namespace HTML still read; schema expansion is not schema content. Correction **3**. |
| **8. Exclusive retirement credit and loadable slices** | **Not closed** | D assigns Q4 to T0 and names six bootstrap tests, but Q still books Q4/S5; the new T0 also removes `ordered-episode`, whose test callers are omitted. C still has production callers. The gross figure mixes count methods and the partial net is wrong. Correction **7** and ledger below. |

## Required corrections

### 1. Make the five-query walk implement the declared graph, with real bounds

**D §§1c, 2.2, 4.1–4.2, 10 Q1.** The relational value join is correct for native values: `[?e ?a ?v] [?t ?i ?v]`, with declared `[attribute identity-attribute]` pairs, resolves symbols/keywords/handles through their identity indexes. `:seon.task/tasks` already has `:seon.db/index true` (`resources/seon/schemas/seon.task.edn:11`), so the reverse task-handle join has an indexed holder side too. Do not replace these joins with per-node pulls.

The AVET performance claim needs its actual guarantee. Datahike `query/execute.cljc:458–486`, `var-attr-avet-pairs`, uses point seeks only when one upstream relation binds both attribute and value, all attributes are indexed, and the pair set passes its selectivity threshold. `:548–587` shows the fallback scan/filter paths. The query remains correct on fallback; “never by store size” (D §2.2) does not follow. Record the actual plan/seeks for the final queries, including the variable-attribute forward-ref query, before asserting that cost bound.

The five queries in D §2.2 are a prototype, not the generic algorithm in §4.1. They omit ordinary reverse refs such as namespace → functions and agent → messages, and hard-code only the arity-component path and reverse task relation. Their value-pair list omits the new agent/task link, forward `task/tasks`, and `fn/see-also`. Merely binding different frontier entities cannot give those query bodies the missing relations. Supply the final parameterized query shapes, or revise the fixed-five claim and its measured coverage. Include stored schema → schema references (`:seon.schema/references`, `resources/seon/schemas/seon.schema.edn:83`) when promising referenced definitions; this is an existing relation, not a data gap.

Components need an explicit rule. D §1c says they are “never nodes,” but `:seon.agent/runtime` and `:seon.runtime/turns` are components and runtime/turn have their own render pairs (`seon.agent.edn:36–53`; `seon.runtime.edn:1–23`; `seon.turn.edn:3–8`). Excluding component refs from query 1 and joining only function arities cannot reach the claimed turn graph. Specify which owned values become render units and how component paths are traversed within a level, without requiring an identity for every owned child.

Reuse of `:seon.fn/reference-to` also needs more than a description edit. Its installed declaration is on **a ref attribute**, `:seon.schedule.task/function` (`seon.schedule.task.edn:3`). The reader at `src/seon/fn.clj:1692–1711` treats that datom's target as an **eid**, then matches `[?target :seon.fn/sym]`; it does not resolve a symbolic value. The other reader, `src/seon/test.clj:388`, selects attributes for reach invalidation. Generalizing the key is viable, but retain/refine its storage-sensitive meaning: ref edges already target eids, value edges join through the declared identity. Explicitly review what declaring `must-read`/`see-also` means for call/test reach. A description-only change neither teaches the existing reader symbol resolution nor proves that reading a function means calling it.

Finally, pass bounds into the database query request before materialization. `seon.db/q` forwards normalized requests to `d/q-with-evidence` (`src/seon/db.clj:2404–2455`); Datahike owns `:max-work`, `:max-results`, `:max-result-weight` (`query.cljc:4596`, `:4652`). `src/seon/render/ns.clj:40–50` demonstrates explicit cap mapping. A visited-set cap after the join does not enforce D's edge-read cap. Name the deadline input, bound propagation, and refusal-to-cut behavior, including partially completed levels. This preserves the intended small composition.

### 2. Separate traversal edges from definition order; prove the actual cycles and depth

**D §§2.3, 4.1, 4.3.** Standard Kahn ordering on **use → definition** edges emits uses first. Say explicitly that ordering reverses those edges, or removes zero-outdegree definitions first. More fundamentally, reference reachability is not uniformly a use-before-definition constraint.

The final graph contains guaranteed two-way relations: task → subject through reverse `task/tasks`, then subject → task through forward `task/tasks`; namespace → function through reverse `fn/ns`, then function → namespace through `fn/ns`. Both directions are promised by §4.1. The measured edge list does not include all those declarations, so its zero-cycle result cannot validate the final ordering. Retain all graph edges, including those into already visited nodes; deduplicate expansion, not dependency evidence. Define the ordering relation, deterministic cycle handling, and which concrete node is rendered before its later back-reference. No extra mode keywords are needed.

For turns, use the declared opening transaction (`:seon.turn/opened-tx`) and a stable tie-break. The earliest surviving datom transaction is not a demonstrated creation transaction. D admits it did not verify placed turn order (§2.3). Chronology must survive shared referenced messages and cycle handling; it cannot rest on a claim that all time-bearing entities lack mutual references.

N=3 from an agent gives a direct task subject at level 2 positive distance. A must-read attached to that function or namespace is at level 3, hence distance **0**, contrary to §4.1's “always … ≥ 1.” A direct task-root sample also excludes the agent's other branches and cannot establish identical total agent-root counts (§4.3). State the intended treatment of these transitive must-reads and measure the agent-root graph. Keep the owner's ordinary distance rule unless an explicit requirement justifies a different acquisition rule.

### 3. Supply real task/schema pairs and pure inputs for both faces

**D §§0.2–0.3, 4.2, 4.4–4.6.** Correctly identifying the missing pairs is progress, but `seon.render.ns/schema-form` is **not a renderer of the schema definition**. At HEAD, `src/seon/render/ns.clj:726–730` returns `(doc <schema-key>)` under `:seon.render/form`; it does not read or render `:seon.schema/form`. Assigning it to two faces would present an expansion call, not the required definition. Supply the actual AI/HTML pair over the acquired schema row, and name both task faces. A legal render composition may delegate to existing source/value rendering; it must not execute the suggested `doc` call inside presentation. Compile the contracts against the packaged projection with Malli's `validator`/`explainer` (`core.cljc:2637`, `:2653`).

The namespace AI dump is **specified to be fixed, not fixed on HEAD**: `render-ai` at `ns.clj:732–763` still queries, then calls `render-data`; `render-data:349–386` acquires namespace functions/schemas. D's doc/counts/`dir` replacement is the right direction, but counts require an acquired value or an explicit bounded/unknown result. The function selector at `ns.clj:29–35` cannot serve every node kind as §4.2 suggests: it has no task intent, schema form, namespace doc/counts or turn observations. Name schema-derived/selective acquisition for each pair's actual input, including distance-zero values and the namespace's exceptional doc/count face.

Extend W2/C's caller conversion to the HTML face. `render-identity-html` (`agent.clj:312–350`) pulls identity and calls cluster status; namespace `render-html` (`ns.clj:765–773`) calls `render-data`. Removing only the named AI reads cannot establish “no renderer reads the database” or the same acquired value for both faces. Keep membership traversal separate from face selection; if presence is determined only from schema pairs, explicitly reconcile that with the value/viewer overrides in §4.4's ladder.

### 4. Keep render retention dependent on all inputs and recompose the complete context

**D §2.4, §3 items 5–6, §9.5.** Reusing `render-call` retention is correct. The observed 133 unchanged digests prove content stability, not cache hits, validity, or complete prompt composition. `src/seon/render.clj:783–827`, `:859–875` already track reached producer program rows, projection, selected callable/arguments, request environment and captured reads. Add content identity without discarding those dependencies. Define whether “producer program identity” means this reached evidence; a whole-program commit key would invalidate unrelated nodes on every publication, contradicting §9.5.

A function/test definition digest is sufficient only for the values actually supplied to its pair; changed membership, viewer selection, helper code, distance and profile may still change rendering. Keep evidence for those inputs. “Composer re-emits only changed nodes” must mean HTML patching or reuse while constructing the **complete current context**. It must not omit unchanged required definitions from a fresh prompt. Request-local render-once and cross-turn cache reuse are distinct guarantees.

### 5. Finish evidence-based refresh without reconstructing results from dependency evidence

**D §5.** Accept captured evidence as the authority. Do not return to source constants or mine shown text. Preserve unknown/`:all` evidence and empty-query insertions, and acquire current context before selecting retained reads. These resolve the central round-2 objection.

The remaining claim “after a restart … the node set comes from the recorded evaluation's evidence” is unsupported. `seon.db/read-evidence` (`src/seon/db.clj:892–942`) deliberately omits read result payloads by default, retaining plans, revisions, requests and optional result digests. Those inputs do not in general encode the walk's presence-filtered, depth-bounded, deduplicated node set. Use the single newly acquired graph's membership; after restart reacquire once on the ordinary path. Specify how that value reaches the planner when the public walk returns rendered units, without another store or a second evaluation.

“Positively outside” must be proved over **every captured dependency** of the retained evaluation. A literal argument is not necessarily an entity identity. An unbound-entity pattern with a bound ordinary value can acquire a newly relevant row; being non-`:all` is not proof of an outside subject. Resolve recognized eid/lookup/identity constraints, preserve source-database/branch coordinates, and conservatively keep mixed or unattributable plans. Add the indirect-result/binding, empty insertion, mixed-dependency and changed-task cases to §9.6. Only all-known-outside subjects may be skipped under the proposed scoped rule.

### 6. Gate the opening on the complete usable graph, not just W1's existence

**D §§4.5–4.6, 6.** Keep W1 unused and activate contracts, acquisition, pairs, shared floor and the assigned agent's opening together. W1's “nothing calls it” is narrower than “no behavior change”: publishing a pair can already change existing render selection, so declare how pair publication and implementation loading stay coherent.

W2 calls `my.render/walk`, which Q books in **Q2/S5**, not S2/S3 alone. Add that actual adapter dependency or place its conversion in the same activation. Require the configured agent lookup, not the sample literal `"root"` for every agent. Preserve the opening/help and historical observations without treating a form-returning renderer as an evaluation request. The effect-evidence repair remains mandatory before enabling T2's changed selection; context-first must not execute refresh candidates twice during the transition.

### 7. Reconcile migration, deletion credit and every surviving caller in the slice plan

**D §§4.6, 7; Q §§4–5.** W2's pure `render-identity-ai` deletes `agent.clj:301`, the only source call to `issue.opening/source`, while T3 claims that caller is removed later and unmigrated workers keep their opening until then. Either complete issue-worker migration before W2, or explicitly retain one ordinary opening acquisition for those workers until T3. Do not leave it in a renderer or silently remove their work.

T0 must also retire/convert `test/seon/render/episode_test.clj:42–47`, which directly calls `walk/ordered-episode`. The six bootstrap tests alone no longer describe all T0 callers. C cannot just remove acquisition definitions after visual parity: `src/seon/render/web.clj:2021–2030` calls both `root-pull-plan` and `root-acquisition`; `:2043–2058` retains that acquisition's shape. Root-pull, walk, entity-pair and schedule tests also use the API. Convert the surviving behavior through `graph`/`units` in the same loadable cut; delete only tests of retired behavior. Namespace `render-data` likewise calls the C helpers. No source load was attempted here, so these are concrete unresolved caller obligations, not an observed compiler failure.

Q still lists Q4's 62 old lines and its replacement, and S5 still converts bootstrap. D's “exclusive credit” does not revise that other ledger. Root must coordinate the owning documents before summing them. Keep this lane's one-file boundary.

## Retirement ledger checked against committed HEAD

The advertised **1,643** is arithmetically the sum of D's gross old-code credits, not a HEAD deletion. Every listed owner is still present. It mixes physical ranges with definition spans. For comparable counts, count from each top-level definition through its last nonblank/noncomment line, excluding separators; count the wholly retired issue file in full. Read the files through `git show dc4f71d67:<path>` and count text only; no Clojure reader or tests were executed.

| Slice/range | D credit | Comparable old spans | Qualification |
|---|---:|---:|---|
| T0 bootstrap `bootstrap.clj:204–731` | 502 | **502** | Includes Q4's 62; retire all callers. |
| T0 episode helpers and orderer `walk.clj:817–924` | ~108 | **100** | 108 physical lines; episode tests are additional callers. |
| W2 `declared-sources` + identity functions + situation AI | 93 + 35 | **128** | 54 + 39 + 35; issue caller inside the 39 cannot be separately credited to T3. |
| T2 `latest-evaluations` + `system-plan` | 74 | **74** | 33 + 41; rewrites preserve evidence obligations. |
| T3 issue-opening file | 233 | **233** | Conditional migration; includes namespace/comments. |
| C acquisition range `walk.clj:94–591` | ~498 | **482** | Includes helpers not named individually in D's list; whole-range credit remains conditional on all caller conversions. |
| C namespace helpers `ns.clj:59–103`, `:174–230` | 100 | **100** | Correct current locations; `render-data` remains a caller until converted. |
| **Gross** | **1,643** | **1,619** | Comparable candidate old spans, not approved net deletion or measured implementation diff. |

D's all-slices arithmetic **217 − 1,643 = −1,426** is correct on its estimates. Its T0+W1+W2+T2 subtotal is **202 − 812 = −610**, not **−711**. On comparable spans that subtotal is **202 − 804 = −602**, and the all-slices estimate would be **217 − 1,619 = −1,402**, before pricing missing pairs, complete queries, caller conversions and declarations. The 98-line `neighborhood` loop is described as moved into Q's `units`, so it earns no additional deletion credit here. Do not call any of these a measured final net.

## Landing boundary

Only this review document changes. **Source +0/−0; tests +0/−0.** The review accepts the owner's graph-render direction and rejects the claim that all eight original fixes are fully closed. Correct the seven items in the owning design before their activation; retain the specified installation, graph, order, face, refresh, cache, bound, timing and memory witnesses for implementation. This lane supplies no load, installation or performance pass and leaves unrelated edits and sessions untouched. Release this review path after the requested one-file commit.
