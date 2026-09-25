---
type: review
status: implement after fixes
created: 2026-09-25
design-pin: 1273211e8
source-pin: 48ceb9cad
scope: Independent review of the query/render building block; documentation only
---

# Query and render building block — independent review

**Verdict: implement after fixes.** Keep one public seon.render/units entrance, declared reads as data, existing selection/call preparation, and one shared continuation operation. Do not implement the proposed +44-line core unchanged: its source executor does not capture reads, its request/result contracts do not cover its consumers, and its child-unit rule restarts the budget. Fix the P1 contracts below in the owning design before implementation. This verdict does not approve the still-unresolved floor implementation.

Today the database owner executes reads, walk acquires entity concerns, render-call retains one face, and the floor prepares values. The smallest composition joins those owners in place: acquire a declared source once, retain its evidence, select/render reached concerns under one request budget, and return units with source-bound continuations. Domain acquisition and ordinary REPL settlement remain their existing functions.

The owner's “No more duplicating systems” is the acceptance criterion. A new name around the old loops is insufficient; replacing useful acquisition policies with a general query interpreter would also miss it.

## Evidence and limits

Reviewed [the first-pass design](query-render-building-block-2026-09-25.md) at 1273211e8, AGENTS.md, [duplicate-paths audit](duplicate-paths-audit-2026-09-25.md) §§3 and 11 (audit pin 056ef6fe0), [one-render-path design](one-render-path-design-2026-09-25.md) §§1–3, and [floor-shell-order review](floor-shell-order-review-2026-09-25.md). Applied the data-oriented-clojure, datahike, and historical clojurescript skills; read the context-generation turn PRD §§13–15 for saved text, live results, and ordinary settlement.

Source references below use **48ceb9cad**, the design's source pin, unless a historical commit is supplied. The four requested source files — render.clj, render/walk.clj, render/data.clj and db.clj — were byte-identical between that pin and inspected HEAD 19e9fe5f4. The reviewed design also matched its assigned commit. Caller evidence includes bootstrap, web, transcript, eval, error, the render/read schemas, call-preparation and SCI kernel.

| Dependency source inspected | Gitlink |
|---|---|
| reference-code/datahike | 4157916b5a1b8d80d7f809c889bce1b098b1d75d |
| reference-code/malli | befcd302a8e626a67d84b80aabee0cd1b38dadb6 |

Malli's existing schema/properties/entries and cached validator/explainer are sufficient for the new request declarations (reference-code/malli/src/malli/core.cljc:2561, 2592, 2782, 2637, 2653); no schema mirror is needed.

**No P0 demonstrated.** The P1 findings are source-backed counterexamples to the proposed guarantees. No JVM probe, application evaluation, test, bin/seon, provider call, or runtime mutation was performed. The design's faulted-JVM/two-stage-selection observation is historical evidence from its author, not a fresh observation by this reviewer. Installation, adoption, arming, timings and memory remain unverified. An earlier review's foreign syntax failure is not a current failure established here. The unrelated dirty http-kit submodule was preserved.

## Archaeology: four generations checked by commit

| Generation sampled | Checked evidence | Verified result and correction |
|---|---|---|
| G0, January/February JVM | e041ef5c6:src/seon/ns/view.clj:82–119; 6e013a325:src/seon/render.clj, find-renderer addition; 37bc72b00:src/seon/graph/context.clj:302–327 | Value metadata → schema property → default is real. find-renderer queries candidate functions and ranks key inclusion by specificity/recency/name; it is neither today's full contract validation nor viewer-namespace selection. Context build really performs seed acquisition → topological ordering → per-entity text. Its signature at this commit uses conn, seed, depth, not the table's db-name. The claim that the **same four-level selection** already existed here is too strong. |
| G1, pod/quarry | 9e44815f5:src-old/seon/render.cljc:273–288, 312–325, 1058–1132 | Verified value slot/schema/generic resolution, the single unwrap, and the recursive handle installed at 1107–1108. Explicit string/vector slots are terminal. No viewer-namespace contract-fit stage appears in this ladder. missing-render at 1047–1056 gives an AI diagnostic but returns nil for HTML; do not cite it as total two-face failure handling. The subsequent quarry deletion is real: 099cdfa99. |
| G3, fresh blocks | 6dcda1ab9:src/seon/render/block.clj:1–43; f2a6ec0f2:same path:181–199, 201–275; 7ba92be65 commit explanation | The block-as-morph-target and explicit held database are real. The initial 347-line file announces a contract draft; the later file contains real unit/surface bodies even though its header remains stale. “The funeral” removes **two prompt blocks**, moving their meaning to run rendering and updating behavioral assertions; it is evidence of convergence in place, not another whole-tree replacement. block/surface-id survives at the current pin. |
| G4, neighborhood | 1a499558a:src/seon/render/walk.clj:328–410; current pin:same path:535–573, 710–807, 926–974 | The initial walk already carried the database and bounded hops, nodes and per-path cycles. It returned a nested node/neighbours shape and called render/render, not today's flat render-call units. Flat units are a later evolution. The namespace remains present and has evolved in place through the current acquisition/render split. This generation did not end in a tree cut. |

The archive (6c1079c8d), tree split (f25e34594), and quarry deletion (099cdfa99) are genuine large historical events. They support the warning about losing useful seams. They do **not** establish “each generation” or “lost all three things every time.” G5 also contains proposals, not proof of installed entrances.

Eleven is a useful inventory count, not eleven equivalent implementations: it mixes a selector (find-renderer), producers/assemblers, designs, and nested wrappers used in the same generation. The owner's “about ten times” is supported qualitatively. Revise the archaeology to distinguish executable entry, composition helper, and proposed entry; preserve the lessons without claiming an identical four-stage function or universal destruction history.

The strongest historical lesson is narrower and actionable: preserve the explicit source and recursion context **through caller conversions**, and prove their continued use. Both the surviving block and walk code show that this can be done without replacing a tree.

## P1-1 — Publishing replay-read unchanged loses read evidence and boundary guarantees

Design §0.6 says replay-read “records read evidence”; §3a depends on that for query units and retention. **False at the pin.**

db.clj:962–1026 calls d/q-with-evidence, pull-plan-with-evidence, or d/index-page and decodes the result. The query arm takes only :datahike.query/result; the pull arms take the result and plan for decoding. None calls append-query-evidence!, append-pull-evidence!, or append-read-evidence!. Binding a sink around this function cannot capture the dependency evidence it discards.

The public owners do the missing work: q at db.clj:2398–2421 performs additional query checks, appends evidence at 2416, and converts dependency failures; pull-call validates the pulled result and appends evidence at 2568–2576. append-query-evidence! at 755–797 records even an empty database result's dependencies. replay-read is an internal comparison executor invoked by read-evidence-current? at 1166; its lack of capture is not proof that public read acquisition can omit capture.

There is a second placement problem. render-call's fast reuse decision precedes selection (1511–1538), and its captured sink is created at 1586, after selection. The sink reaches the kernel only during invocation (1103–1115). “Resolve first inside the call's captured-read scope” is therefore a change to retention control flow, not a twelve-line source substitution. Query/subject identity must participate in reuse, and query acquisition, selection dependencies, and nested child reads must reach the retained evidence.

**Smallest fix:** keep the operation dispatch in seon.db, but make the public declared-read entrance delegate to the existing public read owners with an explicit database and the original options. Share that executor with currency replay while keeping currency comparison capture-neutral where required. Do not copy their validation/decoding/capture logic into render. Reject malformed requests through the owning boundary; do not call the existing internal executor “total” merely because its unknown-operation arm returns a value. Its permissive pull-arguments vector can still deliver an invalid arity or dependency exception.

Extend the existing render retention entry to describe the resolved source and its read evidence. Reuse only after source, program/selection inputs, and reads agree. Prove an initially empty query invalidates on insertion, a relevant update/retraction invalidates, an unrelated write reuses, and a changed template at the same occurrence cannot reuse the old output. Keep actual successful read results as values, including nil and false.

## P1-2 — The public signature is a useful direction, not a complete contract

The proposed call-request extension is not merely “one new member”:

- resources/seon/schemas/seon.render.edn:21–35 currently requires value, database and SCI context. A query-only request fails that contract; a plain value without database/SCI also fails, contrary to the total floor requirement. Make source alternatives exclusive by **key presence**, so a supplied nil is a real value. Refuse both-source and neither-source requests explicitly.
- walk/unit requires lookup, distance, path and found-depth. Arbitrary scalar results have no entity lookup. The current neighborhood loop also does **not** copy its computed call-id onto its returned member-unit (walk.clj:765–802). The output schema and identity construction must cover anonymous values and occurrences without inventing entities.
- A face-specific rendered string/Hiccup is insufficient for the promised shared continuation service. Return the selected structure/cut coordinates and resolved source alongside unit identity and the requested face, using the existing prepared-value members where possible. AI and HTML need common structural decisions; their explicitly different declared renderers remain legitimate per-face choices.
- A raw read-request describes q/pull/pull-many/index-page. It does not describe the walk's default entity acquisition, which reads installed declarations, traverses a bounded graph, assembles concern units, and resolves namespace connections (walk.clj:408–573). “query (entity default)” is not an existing fifth operation. Keep that policy in walk and name its composition into units.

The subject template also needs a precise substitution rule. State which argument positions contain a parent **lookup**, how literal occurrences are represented, how missing parent subject refuses, and whether index-page options permit substitution. The risk note currently limits substitution to args/pull arguments, leaving index-page out. Q3's existing agent-faults-query binds a scalar agent id (error.clj:1913–1923); substituting a lookup vector for that id is not equivalent. Adapt the query's bindings explicitly rather than stripping identity with a generic heuristic.

**Smallest fix:** declare source and result shapes in the existing render schema population, with separate value-only and database-backed requirements and no second request registry. Keep entity acquisition as a producer of ordinary unit requests. Accept the existing ordered acquisition members through a declared composition shape, or have the entity request delegate to that owner; do not pretend a single pull reproduces it. Budget and price these declarations and caller conversions.

## P1-3 — Query cardinality and ordinary evaluation are unresolved

Design §3a says the read result **is one value**; §3d says one concern query is **one unit, never one per row**. But A1 says render-call **per row**, and the prompt-history consumer needs independently selectable saved evaluation units. A q scalar, a relation of tuples, a pull-many vector and a concern's vector of faults cannot all mean “map render-call over the result.”

History is not a bare query loop. seon.eval/of-agent (eval.clj:9–63) distinguishes absent agent from empty history, preserves required projection fields, orders by turn transaction/turn id/ordinal, and adds the evaluation transaction as :t. walk/history additionally excludes the active reply turn while preserving generated evaluations and supplies per-evaluation ids, subjects, basis and bytes (walk.clj:926–974). Replacing that with twelve lines of declared Datalog is not yet demonstrated. Keep of-agent as the existing domain owner; do not fork its semantics into a new query literal.

The opening example has another double execution: units executes its query to render a result, then the opening evaluates the derived read form through the REPL. That runs the read twice, potentially on different bases; skipping the second evaluation would instead bypass read → eval → record → print.

**Smallest fix:** one arbitrary value/read result stays one root value by default. Declared concern boundaries produce child units; ordered history exposes already-defined evaluation boundaries through its owner, not by guessing from collection shape. Source descriptors can be derived without executing them. For an opening, derive the form from that descriptor, let ordinary evaluation execute it once, then send its actual result and retained source provenance through the same rendering entrance. Rendering must not pre-execute the read or record a second evaluation. Form formatting is ordinary repl/source-text work, not a new interpreter.

This means “one call” is a valid **presentation boundary**, not a promise that all query policy, teaching dependencies, settlement and layout disappear. Specify the two lifecycles in the design, including :seon.render/form callers; changing bootstrap's form output to AI text is not a caller-neutral substitution.

## P1-4 — Child units currently multiply B and can lose recursion ownership

The cost sentence at design:196–199 says “floor under B per unit.” §3d charges a query reference to the parent's B and appends the fully rendered child afterward. A parent producing k query children can therefore spend approximately (k + 1)B in one request. Charging the small reference does not charge the child.

Distance is insufficient on its own. It limits entity hops, but the design does not say how renderer-returned query descendants consume it, how a query returning its own descriptor terminates, or how producer exclusion survives a new child unit. Putting the query in the child's id detects changed inputs; it does not prevent recursion or bound acquisition.

**Smallest fix:** carry the same floor allocation context through all newly rendered units/descendants of one request. A child's visible content, reference, diagnostic, layout and legend consume that request's remaining allowance; no recursive units call starts a fresh profile. Retain the existing producer-chain guard and name the query/unit work bound and its refusal/continuation. Only reached/admitted query children execute, rather than eagerly querying every child before selection. Query work/deadlines remain separately bounded.

Saved historical evaluation text is different: it has already been rendered and is selected as whole units by prompt/select, without charging it through a new floor. A later user-requested expansion is a new bounded request. These distinctions prevent both fresh B at every descendant and accidental re-clipping of history.

Use a stable occurrence identity (parent occurrence plus structural path) for morphing; include source/query contents in cache-validity evidence. An ever-growing query-in-id scheme is unnecessary and its hashing/coordinate cost is not zero. Two occurrences of one source may need different DOM ids while sharing valid acquisition evidence.

## P1-5 — Source plus t does not make a continuation valid forever

Design §3e improves the previous split endpoints by naming the source, but repeats the unresolved continuation mistakes from floor review P1-2/P1-9/P1-10:

1. **t is not a database identity.** Datahike as-of wraps a supplied origin database and requires temporal indexes (api/impl.cljc:153–183). It does not locate a branch/store from a transaction number, retain the origin, or pin code, schema projection, profile and query function semantics. GC traverses reachable branch/commit/index roots subject to retention (gc.cljc:22–80). A current retained branch's temporal history can answer older reads, but that conditional guarantee is not “valid forever.”
2. **Rendered paths need not be source paths.** A renderer may return a smaller value with different keys/order. Applying that path to the raw query result or rerunning today's renderer can find different data. A digest of the raw artifact has the same problem.
3. **Live sources are wrongly excluded.** The prior review explicitly includes the existing result-objects carrier and repl/live-response lookup. “Unsaved value has no expander” discards a working lifetime guarantee and pushes toward the unapproved durable-capture project.
4. **data/at cannot stay unchanged.** It preserves missing versus nil, which must survive, but uses raw contains?/get and nth (data.clj:54–68); these can hash composite keys, run comparators, or realize sequences. It has no structural map-key/value roles or held-order set ordinals. parse-cursor at 27–44 silently turns malformed coordinates into the root. Naming this function does not satisfy the earlier safe-navigation review.

**Smallest fix:** one existing render owner resolves a validated source reference under explicit custody and then selects/navigates the represented value. Support existing live result identity while retained, an already-existing admitted artifact while retained, and a declared query tied to its actual database/branch lineage and basis. For transformed output, continue the held representation or a reproduction with checked program/projection/source evidence; otherwise return a typed stale/gone/unavailable result. Do not claim reconstruction of historical shown text from current renderers.

get_value and /data should only decode coordinates and encode the common result. Their shared entrance must own source resolution as well as navigation: requiring both adapters to load a digest into v before calling units would leave the acquisition duplication intact. An external query continuation is a checked source reference, not browser-supplied arbitrary executable source. Preserve nil/missing, empty/beyond-end, absolute offset progress, typed structural steps, and current-versus-historical labeling.

Reuse the prior review's minimal live-object plus exact saved-text option; no new historical blob writes or result registry belong to this cut. “Not retained” is an honest terminal result, not a reason to re-evaluate a saved form.

## P1-6 — Selection/defaults and failure handling cannot be declared unchanged

The four-level order is correct and must remain per face. Its installed completeness is not established by the design, and these source seams still matter to this building block:

- namespace-stage fits [argument] before supplied positional defaults (render.clj:617–637). A renderer requiring its second database input can be rejected before call-preparation supplies it.
- Nested project-node* chooses explicit or declared producers only (1226–1229); it does not apply the namespace stage. Therefore collection elements do not currently get the advertised full ladder.
- raw-output enforces terminal text/Hiccup/form (1316–1376). Returning smaller ordinary data containing a query child is not accepted by leaving that check unchanged.
- Inline callable invocation is not established by explicit-value-stage: non-symbols go to inline-rendered, which passes ordinary data to the floor (592–604). The named-call hook leaves unidentified callees untouched (call_preparation.clj:1538–1542). Distinguish a schema-less inline function receiving its value from a contracted callable receiving defaults; do not silently display the function as an object.

**Smallest fix:** make the existing selection/invocation owners handle these cases, coordinated with the floor work. Derive applicability from the existing preparation plan and declared face role, without executing suppliers or renderers speculatively during candidate selection. Reuse the retained Malli contracts and schema-name defaults; caller arguments still win. Nested reached values use this same policy with the current producer excluded. Relax output handling only to declared supported data, then process it under the same budget. Recognize a query child at an explicitly declared render-composition boundary; an arbitrary inspected map containing :seon.render/query must not silently become a database operation.

The failure claim also needs correction. renderer-failure (render.clj:1770–1806) **prepares** two faces and message tx-data; it does not commit a core fault, throw, or stop a graph. walk currently invokes it only when it found an owner, and otherwise prints the placeholder. It is not proof of §3d's “recorded and thrown at the entrance.” Reuse existing fault classification/delivery at the execution boundary, render ownerless failures diagnostically too, and state exactly who commits/delivers before panic. Ordinary declared read refusals are values; a renderer returning an error value is distinct from a renderer that failed to return (the existing invocation envelope preserves this).

Siblings can continue under the applicable record policy. Panic must remain observable and terminate the owning execution; “siblings render” is not an unconditional promise. Do not add transactions to a pure page derivation merely to make that sentence true.

## Can each consumer really make one call?

| Consumer | Review disposition |
|---|---|
| MCP evaluation result | Yes for presentation after ordinary evaluation/settlement, with explicit profile/custody or the projection-free value case. Artifact admission, if needed for an existing transport contract, cannot run a separate unsafe walker before the shared entrance. Removing MCP's parallel evaluator is separate work. |
| get_value and /data | Yes only when the common operation includes source resolution, typed navigation and preparation; two adapter-side blob/query acquisitions are not one entrance. |
| Namespace/agent page | Yes for rendering the walk owner's acquired units, including declared concerns and fleet oversight. “One entity default read-request” has not been shown equivalent to root-acquisition. surface-html, morph ids and SSE remain. |
| Agent opening | Not the stated query-render-then-evaluate sequence. Derive ordinary executable forms, settle them once, render each result through the common boundary. Keep bootstrap membership, ordering, demonstrations and origin metadata. |
| Prompt history | Yes as one call to render an ordered declared collection of saved evaluation units. Keep of-agent's semantics, active-turn exclusion, per-evaluation identity/basis and whole-unit admission. No rerendering saved shown text under B. |
| Agent-facing walk | Yes, a thin my.* custody adapter. Its branch filter selects unit paths; that is not automatically equivalent to data/at within one value. Keep or explicitly map that distinction. Old pr-str bytes need not be preserved when deleting the old extra printer. |
| Session/outline/ledger | Yes for their final presentation calls; not proof that their acquisition functions disappear. Pending evaluated-sources, comments, branch errors, messages, effects, ordering, calibration and contributed-byte accounting survive in domain owners. |
| Status/runtime observation | Yes, including no-database boot/fault cases. Requiring an armed SCI context for every scalar/diagnostic would break this consumer. |
| Test reports | Yes after native expected/actual evidence is retained by the test owner. That migration and schema cost are not supplied by units. |
| Error values/fault concern | Yes; one acquired result can feed both faces, with declared error composition. Preserve the requested observation data and empty/failure distinction. The proposed narrower selector is a semantic decision, not a free replacement for observation-selector. |
| my.program reports | Yes for returned report values. Its read/write verbs stay their own functions; presentation must not rerun them. |

“One call” should mean every consumer delegates the **same responsibility**. It must not mean deleting every query in a view or teaching the generic renderer how to assemble turns, effects and test evidence.

## P1-7 — Reorder adoption so the first lift does not strand callers

A2 changes neighborhood from rendered units to acquisition-only and converts page-result. But bootstrap/listing-candidates (bootstrap.clj:346–365) consumes its rendered :seon.render/form outputs, and agent-facing walk (render.clj:1939–1970) consumes its rendered units. Their conversions wait until A6/A4. HEAD could load while those behaviors are broken. A1 likewise claims to delete all of history while retaining a per-row render-call loop and without a declared replacement for its output record.

Use this corrected order in the existing design, not a second implementation plan:

1. **Resolve contracts and dependencies first.** Specify source alternatives, unit boundaries, shared B, continuation basis, capture and retained-output evidence. Root verifies the intended loaded selection/defaults; the review does not restart anything. Carry forward the prior floor review's blockers, rather than treating “as revised” as proof that a revision landed.
2. **Correct the database executor at its owner.** Share existing public read semantics and prove capture/refusals/bounds. No generic renderer query engine and no currency-purpose merger.
3. **Lift the rendering loop with all affected callers working in the same activation.** Keep neighborhood temporarily as a thin delegate to units over its existing acquisition, or convert every caller in that cut. There must be one active loop. Preserve :form behavior until bootstrap's ordinary-settlement conversion is complete. Keep entity acquisition in walk; never duplicate it inside units.
4. **Activate budget/recursion and one source-navigation entrance together with both retrieval adapters.** Retire old windows, raw-path interpretation and MCP reconstruction in their owning audit groups. Temporary face emitters may remain only as encoders of the shared selected structure. No second active generic selection.
5. **Convert one real concern, then opening/history.** Faults are a good end-to-end witness: one query execution, captured empty/nonempty reads, two faces, bounded nested content and continuation. Preserve the error data contract. Convert opening without pre-execution; preserve saved history units and of-agent. Remove ambient walk custody after its callers delegate. Remove outer history retention only when membership, child code/read changes and unrelated-write reuse have all been proved.
6. **Convert conditional domain views last.** First attach the audit's behavior → surviving-function map. Retire exactly the now-redundant adapters; do not delete session/outline/ledger meaning to meet a line estimate.

This is accretion in the existing owners, not another whole-tree cut. Every temporary public name delegates to the one implementation and has a named retirement. No worktree, second renderer, cache, result registry, or replacement acquisition DSL is warranted.

## Deletion accounting against the audit

The arithmetic of the Q1–Q6 ledger is internally correct: **274 direct + 340 conditional = 614 old definition lines; 42 + 60 + 44 = 146 estimated additions; net −468.** The listed Q ranges do not overlap each other or the audit's booked render retirements. Transcript §3.3 was an inventory, so naming those definitions is not double counting by itself.

However, there are three corrections before treating that as an integration budget:

| Finding | Smallest correction |
|---|---|
| **P1: audit C7 conflicts with the chosen executor.** C7 books replay-read's 65 lines for deletion alongside stable-read-result 5, read-result-digest 12, changed-read-attributes 16 and index-evidence-current 41 (139 total). The building block retains replay-read and unchanged read-currency semantics, but its “not recounted” list omits C7. | Suspend C7's 119-net saving in the combined plan. At minimum withdraw its 65-line executor deletion if the body stays. If the body becomes a thin delegate, count its actual removed body/replacement once and reprice the remaining currency work separately. Do not add the audit's full 2,287 net to this design's 468. |
| **P2: adoption rows do not reconcile with the Q ledger.** A1–A4/A6–A8 total **143 added / 626 deleted**, excluding A5; the Q ledger says **146 / 614**. The extra twelve deleted lines are A2's partial neighborhood branch/wrapper, while §4 gives the mixed neighborhood function zero credit. | Use one range/definition ledger, distinguish moved loop lines from deleted lines, and reconcile the three added-line difference. Do not count a move twice or bill a fragment as a whole-definition retirement. |
| **P2: the scope of additions differs.** The +44 core includes eight schema lines, while the audit ceilings are source-only before callers/declarations/tests. The plan promises one regression per slice but does not price them. | Reprice source, declarations, caller edits and tests separately after the P1 fixes. The +44/+146 figures are estimates with missing work, not measured ceilings or permission to exceed the line law. |

Q1's 49 and Q3's 66 should be **conditional replacements**, not immediately spendable credit, until the history and faults behavior maps exist. Q2's 159 is a credible retirement inventory, but still needs the explicit-custody adapter and branch semantics. Q4–Q6 remain conditional:

- direct-candidates owns teaching/membership/key chaining, not just map render-call;
- derive-context! retains aggregate membership and every child's program/read validity, plus history segments and ledger data (web.clj:2537–2610); retaining individual children alone does not detect a newly appended child;
- candidate-history handles in-flight evaluated-sources and comments, and acquire-ledger-data derives emissions/outcomes, groups evaluations, joins effects and calculates calibrations (transcript.clj:668–737, 1856–1880). These are mixed functions until their surviving owners are named.

Q7 correctly claims **zero**. entity-observation combines outgoing and incoming pages across ref attributes, tracks attribute offsets, validates snapshot/eid/direction, and reports identity completeness (data.clj:124–235). One index-page request cannot replace all that. Preserve it as a query-work policy; presentation B is a different guarantee.

R1/R2/R3 retain exclusive ownership of floor, nested projection and transport reconstruction retirements. C3 owns defaults planning, C8 error double-rendering, R10 native test-report migration. The building block consumes those changes; it cannot book their lines again.

## Smaller hybrid and what stays distinct

I agree with Opus's **option A**, a declared read template beside the concern's schema, for simple declarative reads. Read it through the existing projection/Malli properties and invalidate by those declaration inputs. Do not add a universal refusal of identity-less pulled maps: owned components and partial observations legitimately lack identities; only a continuation promising an entity identity needs that proof.

Use option B's **existing domain function** where acquisition already has ordering, projection, validation or composition semantics. Its output remains plain data/unit requests handed to units. Do not translate of-agent, the entity walk or ledger assembly into a new general “read program” just to make every consumer spell one q. Option C is useful only for explicitly declared renderer-produced children under the same budget/provenance rules; it must not become arbitrary shape-triggered execution.

The hybrid keeps Opus's one entrance and schema-local query data, the pod's recursion continuity without restoring its callable registry, the block's occurrence/morph identity, and the earlier review's held-representation continuation. It corrects the claim that everything is already an unchanged render-call operation.

These owners must remain distinct:

- database read execution/capture versus since-diff comparison and refresh;
- query-work bounds/deadlines versus the one presentation allowance;
- entity/concern acquisition policy versus renderer selection and terminal face encoding;
- source/occurrence identity versus cache-validity inputs and historical basis;
- ordinary REPL evaluation/settlement versus rendering its actual result;
- exact saved shown text and whole-unit prompt admission versus new live-value rendering;
- live object custody, retained artifact bytes and database history, with their different lifetimes;
- diagnostic face construction versus durable core-fault recording/delivery and panic;
- native test evidence/effect receipts/domain analysis versus how they are displayed;
- source-code formatting, HTTP/SSE/MCP envelopes and DOM targeting versus value interpretation.

**P2 authority cleanup:** the one-render-path design still contains an instruction to remove explicit/namespace stages immediately after restating the four-level owner ruling (§1). Remove that stale instruction in the owning revision. The latest owner order governs this review. Skill source line numbers have also drifted (for example datahike's q/pull/read-evidence citations); the current definitions used here are q:2365, pull:2588, read-evidence:895. No skill file is edited under this one-file assignment.

## Required proof and landing

The revised design should make these finite witnesses its acceptance evidence, through the existing installed test process when implementation is authorized:

1. Every read operation records the right evidence; empty → inserted, changed/retracted, unrelated-write reuse, malformed request and query-bound refusal have distinct outcomes.
2. Literal nil/false, scalar q, relation q, pull-many, one faults concern and ordered saved history have declared, stable unit boundaries. An opening read executes and settles exactly once.
3. Root and nested values obey the same four-level per-face order, supplied map/positional defaults use the held database, caller arguments win, and renderer-returned data cannot restart a producer/budget.
4. Many query children remain within the one request budget; cycles/work exhaustion name a continuation or refusal. Two occurrences retain separate addresses without duplicating valid acquisition.
5. MCP and browser retrieve the same selected fragment from live/query/artifact sources. Include transformed-output paths, wrong branch/basis, absent live object, missing artifact, malformed cursor, nil versus missing, empty/beyond-end, and strict absolute-offset progress.
6. History shown bytes remain exact; generated forms, demonstrations, selected run semantics, errors, effects and ledger accounting survive their conversions. Record and panic preserve their different execution behavior.

The floor review's hostile-value, deep serialization, safe structural navigation and actual-emitted-byte requirements remain prerequisites, not duplicated new test suites. Performance must include source acquisition, selection/invocation, cache hit/miss work and both emissions; the two tiny read timings in the first pass cannot price a page, a query tree, or a whole-artifact parse. Report parent/change latency and memory, reached units/reads and retained-cache behavior. Complexity includes domain query work, candidate fitting, source/path traversal and emitted structure; “one selection per unit” is a call count, not a constant-time bound.

Landing scope: **this review file only**. Source/test/schema line delta: **0**. Runtime timings, memory, publication, adoption and arming: **not measured/not performed**, per assignment. Static verification consists of pinned source/caller inspection, four historical generation samples, ledger reconciliation and documentation diff checking. No foreign lane was resumed, messaged or edited. Commit only this path with git commit --only; release it after that documentation commit. The commit id is reported outside the file to avoid a self-referential amend.
