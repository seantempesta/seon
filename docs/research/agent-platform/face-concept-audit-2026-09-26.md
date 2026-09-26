---
type: audit
status: complete
created: 2026-09-26
tags: [agent-platform, rendering, vocabulary]
---

# Remove redundant rendering roles; name print types accurately

Seon already has renderer attachment keys, declared function outputs, and consumers that know whether they need text or HTML. Delete the additional presentation selector carried between those owners; retain the floor printer's real value distinctions under Clojure's term **type**.

## Evidence boundary and result

**Default is faulted, not green.** Both `bin/seon status` and MCP `runtime_status` reported PID 39484, start `2026-09-25T22:11:57Z`, eight open error signatures, 80 failed tests and 41 unknown failed-test observations. All readiness layers were present; one agent's three procs answered. Reported faults include schema publication refusing an undeclared renderer contract, canonical-definition refusing a schema form, surviving test referrers preventing deletion, and an existing namespace alias collision. These are observations, not diagnoses of this audit's subject. No stop, reset, reload, adoption, test, provider run or source edit was performed.

The source baseline is **`ff381f1ab`**. The principal audited files (`render.clj`, `render/value.clj`, `render/web.clj`, `print.cljc`, `sci/admit.clj`, both render/print schema resources and `input.css`) remained byte-identical to that baseline when checked later in the audit. Other lanes advanced HEAD and edited unrelated/adjacent owners; later check-in also showed in-flight print/schema edits. The inventory remains pinned to the earlier baseline, not those uncommitted edits. References below use the baseline, not a promise that the moving checkout retains those line numbers. Appendices inventory the baseline and separately identify generated CSS. The only authored file is this report; ownership ledger entry `audit-face-concept-astra` grants this path only.

**CONFIRMED:** there is no `:seon.render/face`, face-role attribute, face-profile schema or face-named config dial in the swept source population. The redundant AI/HTML selector exists under **`:seon.render/output`**, including a third `:seon.render/form` alternative. A search only for `face` misses it. No new role fact or role registry should be installed.

**CONFIRMED:** `:seon.print/face` means a print-node classification, not AI versus HTML. Deleting that information would lose distinctions needed to print and retrieve values. Rename it. Likewise, `face` passed to `Sink/-token` means syntax classification, not consumer identity.

**PLAUSIBLE deletion estimate:** 129 existing source/schema lines in the explicitly counted core cut, replaced by approximately 40–60 lines of ordinary pair selection/normalization and failure handling: **69–89 net lines removed**. This is a budget for a future implementation, not a measured diff. Renames account for zero net deletion. Additional argument removals, obsolete diagnostic schemas, UI selector plumbing and profile-label removal are excluded from this total rather than double-counted or guessed. Actual source/test change in this lane: **0 / 0 lines**.

`CONFIRMED` below means the cited code or live observation establishes the finding. `PLAUSIBLE` means the proposed removal still needs installed behavioral proof. Neither label claims tests ran. Every face concept receives **DELETE** or **RENAME**; necessary information is explicitly preserved. The latest owner addition changes the audit's recommendations, not this lane's one-file/no-tests boundary.

## 1. Ground the surviving names

| Evidence, pinned dependency | Guarantee and naming consequence |
|---|---|
| Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`, `reference-code/clojure/src/clj/clojure/core.clj:3490–3501` | `class` returns the Java class; `type` reads `:type` metadata or falls back to class. **Type**, not face, is Clojure's term. This does not authorize using metadata as a new Seon authority. |
| Same file `:3693–3696`; `core_print.clj:89` | `print-method` dispatches on a keyword `:type` or class; `print-dup` dispatches on class. Printed value classification is legitimate and independent of destination. |
| Fipp `255a19061fdc20d67eca09a17e9af282aa8bf909`, `reference-code/fipp/src/fipp/visit.cljc:8–52` | Visitor methods name Clojure categories (`visit-map`, `visit-vector`, `visit-record`, etc.). Fipp does **not** call these faces or establish a `kind` field here. |
| Same revision, `reference-code/fipp/src/fipp/engine.cljc:10–19,24–64,79–83` | Document nodes dispatch on their first element; serialized layout instructions use **`:op`** (`:text`, `:line`, `:begin`, etc.). Use operation for Fipp instructions, not for Seon's map/string/elision classification. Do not claim Fipp supplies Seon's print-node schema. |
| Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, `reference-code/malli/src/malli/core.cljc:1874–1898` | A `:multi` schema uses its declared `:dispatch` function to select a validator. Rename the Seon discriminant and keep Malli's dispatch, rather than replacing it with another validation engine. |
| Same file `:2585–2597,2637–2649`; first-party `src/seon/render.clj:357–368,497–500`; `src/seon/schema.clj:376–379,3833–3862` | `m/form`, `m/properties`, `m/-function-info`, and `m/validator` expose existing declaration information. Renderer attachments and arity outputs already supply the answer; retain the projection's compiled contracts and validator reuse. |

Recommended spelling is **`:seon.print/node-type`**, rather than `:seon.print/type`: `:seon.print/type` already denotes a node representing a type (`print.edn:152–155`). This avoids confusing a classification key with one of its values. Suggested related names:

| Existing concept | Verdict / confidence | Replacement and why |
|---|---|---|
| `:seon.print/face`, local `face` holding that key's value | **RENAME — CONFIRMED** | `:seon.print/node-type`, local `node-type`. The node's original semantic category is not inferable from the renderer attachment or consumer. For example list/vector/set all carry `items`; record/map carry `entries`. |
| `:seon.print/node-face` | **RENAME — CONFIRMED** | `:seon.print/node-shape`: Malli schema for one node, dispatched by `node-type`; the iterative `node?` continues to validate descendants. “Shape” describes the existing schema, not another type registry. |
| `node-face-validator*`, `node-face-validator` | **RENAME — CONFIRMED** | `node-shape-validator*`, `node-shape-validator`. Keep the existing delayed compiled validator and its invalidation/publication obligations. |
| `scalar-faces`, `structural-faces` | **RENAME — CONFIRMED** | `scalar-node-types`, `structural-node-types`. These are category sets used for layout and traversal, not permitted render destinations. |
| `unknown-face`, `unknown-face-error` | **RENAME — CONFIRMED** | `unknown-node-type`, `unknown-node-type-error`. Preserve the actual offending node type and total diagnostic rendering. |
| `Sink/-token`'s `face`; `token-face` | **RENAME — CONFIRMED** | `token-type`. Separator, delimiter and tag are lexical categories, not value classes or AI/HTML. Ground “token” in this protocol's existing contract, and “type” in Clojure's classification terminology. |
| `face-class` | **RENAME — CONFIRMED** | `type-css-class`; it builds `seon-print-<type>` CSS classes for both tokens and structural nodes. Output CSS class values already do not contain face. |
| Stock number/character “print faces” | **RENAME — CONFIRMED** | “printed representations”; these describe Clojure literals and suffixes, not output selection. |

The implementation should revise `docs/seon/architecture/vocabulary.md` **once**, in place, with the first five rows' node-type/shape meaning and the token-type distinction, citing the dependency and Seon sides above. No new role term is justified. This audit does not edit that file. Existing vocabulary rows `:118–120,129,138` need to say renderer, text/HTML and print options without teaching a face abstraction. Historical source quotations remain historical, not implementation instructions.

## 2. Complete concept ledger

Line counts here describe the cited region or exact affected lines; they are **not** all deletable lines. The appendix lists every lexical occurrence separately by file and line so small docstrings and aliases cannot disappear from the sweep.

| ID / concept; site and size | Information and existing authority | Verdict, replacement and confidence |
|---|---|---|
| D1 consumer selector; `resources/seon/schemas/seon.render.edn:177–178` (2); selection request `:85–88` (4); `src/seon/render.clj:673–694` (22), `:696–723` (28) | AI/HTML/form keyword, copied into a request. AI/HTML is already fixed by the consumer and corresponds directly to its attachment key. | **DELETE — CONFIRMED redundancy; PLAUSIBLE cut.** Consumers enter the relevant pair function. Common code may receive the already selected renderer/callable and values, not an enum that it repeatedly translates back into a function. Keep selection evidence, precedence, ambiguity, and supplied defaults. |
| D2 selection helpers; `render.clj:434–439` (6), `:488–532` (45), `:566–606` (41), `:619–671` (53) | Thread `output` through attachment lookup, namespace matching, declared matching and floor selection. | **DELETE — CONFIRMED redundancy; PLAUSIBLE cut.** At AI/HTML entry, use the corresponding attachment key and contract question; share input matching and schema lookup. Do not duplicate the whole selector. Pass actual candidate functions/data through subsequent stages. Attachment keys themselves remain lawful. |
| D3 duplicate output-schema input; `render.clj:725–730` (6), `:1297–1307` (11); `render.edn:74–83` (10), `:181` (1) | `producer` receives `_output-schema` but never reads it. `invoke-producer` passes it on. The candidate-request schema is otherwise unreferenced in production. | **DELETE — CONFIRMED.** Remove unused argument and retired candidate request/output-schema declaration with callers. A schema passed to a genuinely polymorphic contract matcher (`schema.clj:3843,3857`) is real input and survives. |
| D4 invocation and output checks; `render.clj:1110–1141` (32), `:1297–1369` (73), `:1371–1404` (34), `:1489–1664` (176 inspected lines of larger function) | Selected function plus a second output selector; `raw-output` cases on it after guarded invocation. | **DELETE — CONFIRMED redundancy; PLAUSIBLE cut.** Keep one guarded invocation and its declared output contract. Each pair entry handles terminal text/Hiccup or recursively renders other declared data. Remove the extra destination-based validation ladder. Return-before-print values and failure diagnostics must remain intact. |
| D5 recursive projection target; `render.clj:1145–1295` (151), especially `:1242,1261`; `render/value.clj:450–585` (136) | Carries `output` at each node, picks child producers and bounds traversal differently. | **DELETE — CONFIRMED duplicate consumer decision; PLAUSIBLE cut.** Separate structural acquisition/budgeting from emission. The consumer supplies the applicable pair function at the edge; ordinary shared traversal has no AI/HTML enum. Do not erase the real profile or attribute-path context. |
| D6 projected-node target; `seon.print.edn:241–254` (14); `print.cljc:224–228,286–290,311–313,767–771` (18); `render.clj:1241–1243,1259–1262` (7) | `{node-type projected, output AI-or-HTML, value …}` re-encodes which attachment produced a value. | **DELETE — CONFIRMED duplicate selector; PLAUSIBLE representation cut.** Carry projected data under its existing `:seon.render/ai` or `/html` key, or pass it directly to the consumer's emitter. No separate target field. An HTML result can be a string, so do **not** infer destination from `string?`. Preserve nested text quoting and Hiccup escaping. |
| D7 floor round trip; `render/value.clj:625–672` (48), `:734–755` (22) | `render-ai`/`render-html` immediately turn into `render-prepared(unit, output)`, which invokes `prepare(unit, output)`, which emits both results, then selects one. | **DELETE — CONFIRMED redundancy; PLAUSIBLE cut.** Prepare the common bounded tree once; pair functions call `render-ai-data` or `render-html-data` (`:674–685`) directly. Preserve the missing-root refusal. Make the shared tree/continuation behavior real before dropping HTML's different traversal policy. |
| D8 typed unknown output; `render.clj:963–977` (15), `:1050–1058` (9), `:1110–1126` (17); `render.edn:137,147`; `seon.render.unknown.edn:10–11` (2) | Output name copied into a diagnostic; `unknown-output` dispatches it back into `unknown-ai`/`unknown-html`. | **DELETE — CONFIRMED duplication; PLAUSIBLE diagnostic-field cut.** Call the pair directly after `refused`. Preserve failure provenance using the actual entry operation and selected function. Before-selection failures cannot be explained by a nonexistent producer; entry operation is necessary. `unknown-output` has **no production callers**, but tests call it at `test/seon/render_coverage_test.clj:534,544,570`; convert those behaviors, not silently drop coverage. |
| D9 third form destination; `render.clj:566–573,603,1360–1369,1406–1487`; `render.edn:158–160,178` | Read/requery forms and authored source are being treated as a presentation alternative. They carry real executable/read semantics. | **DELETE** the presentation role; **RENAME — CONFIRMED** surviving operation as read/requery form generation. Keep forms as ordinary declared data and existing read/eval ownership. Removing the third enum member does not authorize deleting requery or generated-source evaluation. Deletion beyond the selector is **PLAUSIBLE** and outside the count. |
| D10 web graph local `face`; `render/web.clj:3314–3319` (6), callers `:3344,3353,3360,3369`; labels `:3341,3358–3359` | A helper reintroduces a destination argument despite each caller already asking for AI or HTML. | **DELETE — CONFIRMED.** Call the two render entries explicitly; share request construction only. Refusal display is a browser HTML concern. Delete “face” from labels: “AI text”, “HTML”. |
| D11 graph CSS; `web.clj:3357–3359` (3); `resources/public/css/input.css:1392–1395,1419–1420` (6 selector lines); generated `output.css:3186,3190,3194,3197,3256,3259` (6) | Grid container and two output columns. A separate `seon-graph-face` role restates columns already marked `seon-graph-ai`/`seon-graph-html`. | **DELETE — CONFIRMED** common role class; style `:is(.seon-graph-ai, .seon-graph-html)` and their sibling relation. **RENAME — CONFIRMED** grid container `seon-graph-faces` → `seon-graph-outputs`; it describes an actual layout container. Regenerate ignored CSS; do not forget its served bytes. |
| R1 node discriminant, enum, constructors, emit/case and validators; `print.cljc`, `sci/admit.clj`, `render/value.clj`, `render.clj`, `render/transcript.clj`, `seon.print.edn` | Nil, boolean, map, vector, record, opaque object, elision, projection etc. Needed independently of consumer; attachment/contract cannot reconstruct an admitted node's original type. | **RENAME — CONFIRMED**, using §1's node-type/shape names. Exact sites and counts are in Appendix A. Keep closed grammar, ordinary Clojure multimethod dispatch and Malli validation. |
| R2 lexical token categories and CSS helper; `print.cljc:20,219–245,272,284–310,658–669` | Token styling and separator behavior. The consumer cannot tell a string token from a delimiter merely from the attachment key. | **RENAME — CONFIRMED**, `token-type`, `type-css-class`; no role/profile is added. |
| R3 admission frame `::face`; `sci/admit.clj:294–297,382,388,446,462` (8 affected lines) | Collection type retained while an iterative frame accumulates children; existing `::kind` says frame operation (`::items`/`::entries`). These are different facts. | **RENAME — CONFIRMED**, `::node-type`. Do not collapse into `::kind` and overwrite frame state. |
| R4 `payload-face`; `effect.clj:61–63` (3), callers `:99,101` | Labeled payload text with a token estimate. No selector or hidden role. | **RENAME — CONFIRMED**, `payload-text`. Preserve the text/estimate operation. |
| R5 `readiness-face`; `cluster.clj:676,710` (2) | Readiness observation without the separate problems member. It is actual selected data, not a destination. | **RENAME — CONFIRMED**, `readiness-summary`; preserve the subtraction and value. |
| R6 database identity “face”; `render/value.clj:185,189` (2) | A printable database identity and an issue-name citation. | **RENAME — CONFIRMED**, “database identity representation”. Update the prose/citation to a path or explicit historical reference without carrying “face” into active source text. Do not rewrite the historical issue itself in this lane. |
| R7 SCI host/default “face”; `sci/eval.clj:4263` (1) | Actual dynamic print binding after `sci/binding` unwinds. | **RENAME — CONFIRMED**, “host/default print binding”. This is neither a renderer nor a node type. |
| R8 all other teaching prose | AI/HTML “face”, diagnostic “face”, “per-face” node shape, current error “face”, and CSS comments. | **DELETE — CONFIRMED** redundant AI/HTML role wording; **RENAME — CONFIRMED** real things as output, representation, diagnostic block, node type or token type. Appendix A locates every line. No runtime behavior changes for these prose edits. |

Two details prevent a blind rename from being a correct fix:

- `print.cljc:789` reads `(:?_current-ns_?/face node)` when emitting `:seon.print/unknown-face`. **CONFIRMED producer defect in the inspected source:** that key is not the `::face` key used on `:788` or by constructors. The unknown-node-type cut must use the actual discriminant and review the error schema's ability to represent an absent/unsupported discriminant; merely renaming both spellings preserves the defect. `unknown-face` currently aliases the closed known-face enum (`seon.print.edn:395`), which cannot describe every unknown keyword. No runtime reproduction was attempted.
- `namespace-stage` maps HTML to `:seon.render/hiccup` but leaves AI as `:seon.render/ai` (`render.clj:625`); `function-accepts-and-returns-in?` compares output schema forms by equality (`schema.clj:3860–3862`), plus a special source-producer path at `render.clj:633–634`. **CONFIRMED source mismatch with a general “returns text” rule:** a bare `:string` is not equal to `:seon.render/ai`. A derived contract index must implement the owner rule rather than preserve that accident. Do not stamp a face-role fact to compensate. Behavioral extent remains **PLAUSIBLE** until probed on current installed definitions.

## 3. The selector's route through the program

These are source traces, not timings or claims of execution. Each arrow crosses an existing function boundary.

1. **HTML page:** `render/web.clj:489` or `walk-request:3197–3214` stamps `/html` → `walk/neighborhood:716,729` → `declared-acquisition:659,674` → per-member request/call id `:765–774` → `render/render-call:1495` → `selection:678` → stage helpers → selected renderer → `raw-output:1312,1323` → `invoked:1133` → `invocation-unknown:1114,1124`. The browser knew HTML before the first stamp. **DELETE — CONFIRMED redundant propagation; PLAUSIBLE simplification.** Acquire members independently, then apply the HTML entry. Keep component ordering, reached attributes and read evidence.
2. **Prompt/history:** `turn.clj:1883`, `render.clj:1938`, `render/transcript.clj:2133`, and `render/walk.clj:847–850` stamp `/ai` → the same selection/invocation path. **DELETE — CONFIRMED.** Use the AI entry. Saved shown text must remain saved text, never reevaluated or re-clipped.
3. **Floor recursion:** pair entry `render/value.clj:748,755` → `render-prepared:735` → `prepare:635` → `value-node:583` → `value-node*:452` → `render/project-node:1294` → `project-node*:1176` → `project-entry:1145` / `project-children:1156` → selected projection stamped with `/output` → `print/emit ::projected:767` → `Sink/-fragment`. **DELETE — CONFIRMED repetition; PLAUSIBLE restructuring.** A rename to `target` or `format` would keep the same smell. The tree's node types survive; the AI/HTML enum does not.
4. **HTML error rendering that requests text:** `error/render-html:1828` stamps `/html` → `refusal-text` → offending-value rendering `:931–939` → `prepare(unit, /html)` → extracts **text**. **CONFIRMED coupling:** the flag affects traversal/bounds even though the final requested field is text. **DELETE — PLAUSIBLE cut** after a common bounded tree exists; preserve whole evidence and continuations rather than switching to today's clipped AI result as a shortcut.
5. **Debug previews:** `selected-unit-experiment:1195–1215` → `debug-render-experiment:1519–1562` → `selection-inspection` and render-source calls → `debug-selected-output:1108–1124` → `debug-preview-html:1039–1044`. **DELETE — CONFIRMED internal propagation.** The browser may legitimately choose which of the two outputs to inspect, but translate that UI choice once to a function; do not send it through the value grammar. Keep the user's choice in the URL if needed, as browser UI state.

The generic selector also makes `:seon.render/output` mean **two incompatible things**: a destination keyword on requests, and actual rendered content on walk result units (`walk.clj:791–802`; `web.clj:387,510,515`; `seon.render.walk.edn:42,83`). **DELETE — CONFIRMED** the request selector; **RENAME — CONFIRMED** result content to its actual attachment member `/ai` or `/html` at production. There is no license to delete rendered bytes. Similarly, `:seon.render.call/output` is an invocation's returned data, not a face enum; it is outside the removal.

## 4. Similar indirections: retain the information, remove duplicate authority

| Site / count | Verdict and basis |
|---|---|
| `render.clj:62–91` (30), `:123–195` (73); `seon.render.profile.edn:1–21` (21); `seon.config.render.agent.edn:1–11` (11) | A presentation profile carries numeric token/depth/child bounds and composition. **RENAME — CONFIRMED** any description of it as a “face profile” to **print options / presentation limits**. Existing profile name need not be renamed solely to remove face. This information is **not** implied by AI versus HTML: MCP and agents can use different bounds. Keep these actual values explicit; no face enum in config was found. |
| `render.clj:171–195` (25), `render/value.clj:287–303` (17 inspected lines) | `target-profile` enriches limits with identity/call requery coordinates; floor `render-profile` also handles missing coordinates. **RENAME — CONFIRMED** to a name describing requery enrichment (e.g. `requery-options`); **PLAUSIBLE** consolidate enrichment at entry. “Target” here is a retrievable subject, not an AI/HTML destination. Preserve identity, offset and refusal rather than treating all profile plumbing as redundant. |
| Profile id producers `render.clj:80`, `cluster.clj:452`; readers `render.clj:935–936`, `print.cljc:991,1001,1048`, `cluster/prompt.clj:263`, `render/transcript.clj:993`; schema references in Appendix C | `:seon.render.profile/id` is a coarse `agent`/`mcp` label. It does **not** uniquely identify the numeric limits and is not used to choose the emitter in these readers. **DELETE — PLAUSIBLE** label where actual bound/count/requery evidence already answers the diagnostic; otherwise **RENAME — PLAUSIBLE** the genuinely required observation provenance as the invoking operation. Do not replace actual options with this label or key caches solely by it. No confirmed deletion count assigned until cost-history consumers are traced through stored facts. |
| `render.clj:822,863` (2); whole cache keys `:804–831,860–866` | Output selector is included after selected function and again inside selection evidence. **DELETE — PLAUSIBLE** that redundant key material once entry and payload semantics no longer depend on it. Today removing it alone can alias distinct prepared outputs. Preserve content-valid argument, program/private-callable and projection dependencies. Cache lifetime and full cache retirement are separate assignments. |
| `seon.render.edn:226–243` (18) | `requested-output` is another AI/HTML enum in an error declaration with no production constructor/reference found outside schema resources. **DELETE — PLAUSIBLE** obsolete error shape/enum after checking persisted referrers and publication. A historical error's operation/projection can be meaningful; don't indiscriminately strip stored evidence. Not counted as a proven live retirement. |
| `render/value.clj:397–400` (4) | Floor special-cases `:my.plan.item/needs` only for AI. **DELETE — CONFIRMED misplaced policy; PLAUSIBLE move** to the owning plan renderer/schema. The floor should not derive a domain presentation rule from the consumer selector. Count transfers as transfers. |
| `render/walk.clj:770`, `web.clj:1196,1521,1562,3316` (5) | Call IDs embed the output enum. **DELETE — PLAUSIBLE** duplicate destination component when actual entry/renderer identity and subject already distinguish calls. Preserve disjoint text/HTML cached observations until the cache model changes atomically. |
| `render.clj:1375–1376,1392–1393`; other `:seon.fn/external-sink`/`:seon.fn/projection-boundary` declarations | These are analysis facts naming external sinks and projection boundaries, not a render-time selector. Their information can support reach/bypass audits even when a helper is not attached to a schema. **RENAME** any face terminology in their teaching; **PLAUSIBLE** redundancy of particular declarations requires separate producer/consumer evidence. Do not globally delete effect-analysis facts in this cut. |

The core approach is ordinary functions over explicit values: acquire data and limits, select the relevant declared pair at the consumer boundary, invoke through the existing contract/default-supply owner, and emit from one bounded structural tree. It adds no role object, registry, dispatcher, hidden dynamic target or profile-to-output table.

Cost follows the actual inputs, not the spelling change. Node-type dispatch is constant-time per visited node; iterative admission/validation/emission retain their traversal costs. Renaming it has no expected asymptotic speedup. Current namespace selection sorts and examines public functions (`render.clj:629–639`): at least O(F log F) ordering plus F contract applicability checks per selection, where F is the candidate namespace's public function count. A contract-derived candidate index can move classification to changed contract/private-definition inputs, but applicability still depends on the argument and supplied defaults. This audit does not authorize a second cache. Concrete print limits change when effective configuration or caller overrides change; requery coordinates change with the subject/path. They are not recomputed because the destination has a new name. The proposed common tree costs O(V) traversal and O(V + emitted bytes) retained data for V visited nodes, subject to existing sorting and renderer-call costs; it must remain bounded rather than claiming O(total unbounded value). Parent/change timing and allocation measurements are owed at implementation, not inferred from this source audit.

## 5. What can actually disappear

Count physical lines inclusively; these ranges are disjoint. “Replacement” includes logic that must survive elsewhere within the **same** owner, not work quietly deferred to a second implementation. Every whole-function deletion below remains **PLAUSIBLE** until installed proof; redundancy/unused input findings are **CONFIRMED** as above.

| Existing range | Gross lines removed | Replacement / condition |
|---|---:|---|
| `src/seon/render.clj:725–730` | 6 | Inline selection extraction at pair entry; remove `_output-schema`. |
| `src/seon/render.clj:1050–1058` | 9 | Tests call `unknown-ai` or `unknown-html` after `refused`; no production caller. |
| `src/seon/render.clj:1309–1369` | 61 | Declared invocation contract + output-specific terminal normalization; preserve nil/source/refusal semantics. |
| `src/seon/render.clj:566–573` | 8 | Fixed floor function at each pair entry; read-form generation has its own existing operation. |
| `src/seon/render.clj:594–606` | 13 | Inline data at the respective entry; actual data contract and escaping remain. |
| `src/seon/render/value.clj:734–741` | 8 | Pair entries read their prepared field with the existing missing-root refusal. |
| `resources/seon/schemas/seon.render.edn:74–83,181` | 11 | Retire unused candidate request and output-schema name, preserving general schema matcher APIs. |
| `resources/seon/schemas/seon.render.edn:177–178` | 2 | No replacement output enum. Callers/contracts change in the same slice. |
| `resources/seon/schemas/seon.print.edn:246–249` | 4 | Existing AI/HTML attachment keys describe projected data. |
| `src/seon/render.clj:965,972,1124`; `seon.render.edn:137,147`; `seon.render.unknown.edn:10–11` | 7 | Remove selector plumbing, retain entry-operation provenance for unavailable producer diagnostics. |
| **Total gross / estimated replacement / estimated net** | **129 / 40–60 / −69 to −89** | **PLAUSIBLE**, not measured or independently implemented. |

The easiest isolated retirement is 20 lines (the 9-line production-unused dispatcher and 11-line retired candidate-schema declaration), with test/canonical-population updates still required. The core cut must not claim it deletes the entire 151-line recursive projector, 176-line inspected render-call body, 97 face-matching lines in `print.cljc`, or 36 in its schema resource. Those mostly own real work and are rename/rewire sites. No net source/test changes were made here.

## 6. Loadable slices and the scripted sweep

All actions in this section are a **future implementation plan**; none ran in this audit. Coordinate file ownership before each slice, preserve foreign edits, and publish schema resources with their consumers. No temporary second face registry or dual current API.

1. **Vocabulary and presentation names (RENAME/DELETE, CONFIRMED).** Update vocabulary once, active design language, `payload-text`, `readiness-summary`, host print-binding comment and AI/HTML labels. Remove the graph role class and rename its grid class in Hiccup/CSS together; run the existing `npm run css:build` (`package.json:11`) to update the ignored served `output.css` (`.gitignore:51`). No data/selection change. CSS acceptance needs browser paint, not merely compiled CSS.
2. **Print-node and token names (RENAME, CONFIRMED; compatibility PLAUSIBLE).** One scripted full-set edit across owners, schemas, examples and test fixtures, then lint, load and hand-fix. Rename fully qualified keys, aliased keys, locals, schema names, generator names/references, diagnostics and canonical envelope strings together. Recompile the delayed node validator through the publication owner; a `defonce` delay otherwise retains the old schema. Fix the explicit unknown-discriminant defect at its producer. Keep the iterative validation/traversal shape.
3. **Remove unused selector scaffolding (DELETE, CONFIRMED; installation PLAUSIBLE).** Remove `_output-schema` through `producer`/`invoke-producer` and pair callers; retire the obsolete candidate schema; convert unknown-output tests to the pair. This is a small loadable cut before structural changes. Keep the currently used output enum until all its consumers have been converted.
4. **Consumer entry and contract selection (DELETE, PLAUSIBLE).** Convert prompt/history, browser and debug to explicit pair entries; expose diagnostic selection through those entries without introducing an inspection face-role. Preserve precedence and ambiguity, namespace-private SCI identity, same-arity matching and schema-name defaults. Retire `raw-output`'s destination ladder with equivalent declared-data handling. Convert call IDs/cache keys atomically with their semantics; don't flush or replace the shared store as a shortcut.
5. **One tree and direct emission (DELETE, PLAUSIBLE).** At the existing floor owner, eliminate `prepare`'s destination argument, target-dependent recursion and `render-prepared`; render projected data under attachment keys and select the emitter at the consumer. Keep the budget and requery once. This slice depends on the bounded shared-tree work already designed in `one-render-path-design-2026-09-25.md:139–171`; coordinate rather than duplicate it. HTML currently visits the full value while AI fits it, so this is not safe as a mechanical parameter deletion. Remove the enum and remaining schema members only in the loadable slice that retires their last callers.
6. **Diagnostics/options and final sweep (DELETE/RENAME, PLAUSIBLE).** Resolve persisted `requested-output` and profile-label evidence; remove redundant labels only where operation and concrete limits replace their information. Complete active docs and generated resources. Root owns integration/platform/bulk proof once; implementing lanes use focused installed requests on their own cluster branches. This audit runs none.

### Mechanical replacement recipe, not an executed script

Use one finite file manifest from `rg --files src script resources test` plus ignored `resources/public/css/output.css` as a **generated** output. The full audit's read-only recipe is embedded below; put the reusable implementation sweep/check in the implementation owner's ordinary check location when that lane is authorized. No script was written to a second file by this audit.

| Exact name/category | One-pass replacement |
|---|---|
| `:seon.print/face`, `::print/face`, `::face` in **seon.print only** | `:seon.print/node-type`, `::print/node-type`, `::node-type` |
| Namespaced EDN `#:seon.print` declaration members `:face`, `:node-face`, `:unknown-face`, `:unknown-face-error` | `:node-type`, `:node-shape`, `:unknown-node-type`, `:unknown-node-type-error`; these unqualified spellings resolve inside the namespaced map and must be included explicitly |
| `::face` in **seon.sci.admit** frame data | `::node-type`; do not alter `::kind` frame operation |
| `node-face-validator*`, `node-face-validator`, `:seon.print/node-face`, local alias `::node-face` | `node-shape-validator*`, `node-shape-validator`, `:seon.print/node-shape`, `::node-shape` |
| `scalar-faces`, `structural-faces`, `unknown-face-error`, `unknown-face` | `scalar-node-types`, `structural-node-types`, `unknown-node-type-error`, `unknown-node-type` |
| `face-class`; token locals `face`, `token-face` | `type-css-class`; `token-type` only at token protocol/implementation/method sites |
| Node constructor/dispatch locals `face` | `node-type`, including the `[face token-face]` binding → `[node-type token-type]` |
| Admission's canonical string `#:seon.print{:face …}` | `#:seon.print{:node-type …}`; update precomputed prefixes and byte-size accounting together |
| `payload-face`, `readiness-face` | `payload-text`, `readiness-summary` |
| Graph `face` local, `seon-graph-face` class | **Delete** as described above, not lexical rename into a new dispatch helper |
| `seon-graph-faces` | `seon-graph-outputs`; stylesheet/input markup changed together |
| Prose | Explicit context table in §2; do not replace every word with “type” |

The replacement program should read each file once, apply a longest-first exact-name map and a per-file/per-function local-name map to its original text, assert expected occurrence counts, then write once. Order-independent single-pass replacement prevents renaming `node-face` inside `node-face-validator` twice. A global string replacement of `face` is **wrong**: it changes `surface`, `interface`, historical blobs, and the placeholder namespace instead of fixing the bad lookup. Rename bindings and their references within their scopes; inspect every diff before publication. This is a scripted mechanical sweep, not permission for production regex logic.

After the script: lint the touched namespaces; publish/adopt/reload/arm the complete slice; inspect the installed schema and function inputs; run focused behavioral proof and hand-fix real exceptions. Stale dependency lint findings go through the existing dependency-analysis owner, not code distortion. Include fixtures, retained artifact readers and CSS generation in the manifest, even though the initial search roots were production code. Keep exact saved shown text immutable.

### Literal substring requirement

The owner asks for no face-containing identifier/key/schema/docstring/CSS class. **A concept-clean result is achievable; an unqualified `rg -i face` zero is not the same requirement.** It also matches Java/Clojure/Datahike interfaces and the current DOM “surface” vocabulary. Appendix B makes these visible rather than silently claiming zero.

- **RENAME — CONFIRMED genuinely needed DOM concept:** `surface-id` → `block-id` or `element-id` (prefer existing “block” owner), `:seon.render/surface-id` → `:seon.render/block-id`, `surface-html` → `block-html`, `agent-view-surface` → `agent-view-content`, literal `surface-` DOM prefix → `block-` if literal zero is required. Update every caller, schema min-length (`render.edn:205` currently min 9), fragment link, CSS selector and morph address together. Existing algorithm `render/block.clj:61–96` is 36 lines and remains; prefix capacity must be checked. These are element identities, not output roles; zero deletion claimed. Vocabulary `:74,123–124` must stop calling them surfaces in that literal sweep.
- **RENAME — CONFIRMED prose-only substring matches:** “surfaces/surfaced” → “views/reports/reported” as context requires; ordinary “interface” prose → the specific API/contract where accurate. This adds no abstraction.
- **External identifier boundary — CONFIRMED:** `datahike.db.interface` at `src/seon/db.clj:20`, `src/seon/schema.clj:29`; Clojure's `definterface` in `fn.clj:1059,1074` and `issue/detect.clj:124,130`; `clojure.lang.IFn$` interface checks at `instrument.clj:68–78`. These are not face concepts and cannot be safely globally renamed: they name actual dependency namespaces, language forms or Java APIs. Rename Seon's local `interface` binding to `implemented-class` if literal identifier hygiene is desired, but do not obfuscate real dependency names to satisfy grep. This audit explicitly does **not** promise literal zero across dependency spellings. The implementation acceptance check must distinguish those exact references from Seon-authored face concepts; if literal zero even there is required, that is a dependency/API change outside this rendering cut.

This is the only exception to the DELETE/RENAME ledger: lexical coincidences in external API names are not face concepts. No semantic face concept is left unclassified. A final `rg -n -i face src script resources` should be reviewed completely, plus `rg --no-ignore` for generated CSS; a token-aware face/faces check must have zero Seon concept hits. A renamed target/profile that still encodes AI versus HTML fails the semantic check even if grep is empty.

## 7. Risks and owed proof

- **Persisted grammar and bytes:** admission canonical EDN embeds `:face`; the longer `:node-type` spelling changes byte lengths, blob digests and fixtures. Unknown-node diagnostics must handle old artifacts without corrupting current grammar. Inventory retained readers first; use an explicit historical decoder only if existing data requires one, never two current producer grammars. If “no old spelling anywhere in source” prevents that decoder, root must decide artifact retirement against the durability contract; do not reset default to conceal it.
- **Classification collisions:** `::type` already has meaning, and `admit/::kind` is a frame operation. The proposed `node-type` avoids both. Enum validation and total unknown handling need to agree on actual absent/unsupported values.
- **String versus HTML:** HTML may itself be text. Output shape cannot replace attachment-key authority for an already attached renderer. Nested string quoting, semantic reconstruction, escape safety and exact saved shown text must be covered.
- **Bounds and termination:** current HTML bypasses AI fitting. Removing the flag before one bounded tree/continuation path exists can eagerly traverse arbitrary data or clip evidence without retrieval. Keep query-work bounds separate from print limits. Do not increase a timeout to pass this cut.
- **Defaults and recursion:** preserve supplied database/unit inputs and caller precedence, inline function versus rendered-data handling, selected-producer reentry prevention, refusal visibility and schema/attribute identity. An output-only index cannot replace applicability/default supply.
- **Cache and diagnostics:** a changed renderer can be attached under both keys; dropping output from old keys prematurely creates collisions. Entry-operation provenance must identify a failure before any renderer was selected. Profile IDs are not content digests.
- **CSS/DOM:** source CSS edits alone do not update ignored served output; DOM prefix changes break links and SSE morph targets unless atomic. Keep print token classes and grid layout behavior while removing the word.
- **In-flight ownership:** render/tree, error and graph-pair lanes have adjacent changes. Coordinate the cut at the owning files; do not modify another lane's session, resume it, or work around its errors here. No worktree or snapshot was needed for this documentary audit.

Future focused proof should cover each behavior class once: both attachment keys (including a renderer attached under both), viewing-namespace text/Hiccup selection and ambiguity, arbitrary declared intermediate data, inline symbol/function/data, nested projections and escaping, every node category and unknown type, deep iterative validation, bounded hostile/large values with retrieval, exact history bytes, missing root, selected renderer failure, retained cache separation and browser grid paint. This is an acceptance list, **not evidence that those tests passed**.

## 8. Read-only runtime evidence and reproducibility

The REPL skill was consulted for the following host-JVM inspection. Explicit root `/Users/sean/src/seon`, cluster `default`, namespace `user`, mode `jvm`, `read_only true`, timeout 1000 ms. No defs or SCI mutations:

```clojure
(let [start (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      forms (:seon.schema.projection/forms projection)
      runtime (Runtime/getRuntime)]
  {:audit/loaded
   (into {} (map (fn [s]
                   [s (select-keys (meta (find-var s)) [:file :line :arglists])])
                 '[seon.render/render-ai seon.render/render-html
                   seon.render/selection seon.render.value/prepare seon.print/emit]))
   :audit/schema-forms
   (select-keys forms [:seon.render/face :seon.render/output
                       :seon.render.profile/profile :seon.print/face
                       :seon.print/node-face])
   :audit/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)
   :audit/heap-used-bytes (- (.totalMemory runtime) (.freeMemory runtime))})
```

Observed: `render-ai` line 1371 `[request]`, `render-html` 1388 `[request]`, `selection` 673 destructuring `:seon.render/output`, `prepare` 625 `([unit] [unit output])`, `emit` 650. Installed schema forms included the three-member render output enum, numeric profile, the 24-member print enum and `node-face`'s `:multi {:dispatch :seon.print/face}`. No `:seon.render/face` entry was returned. This confirms the relevant old seams are present; it is not byte-level proof of every loaded body or their behavior.

The form measured **0.317 ms**, returned a `ret` event in **7 ms**, and sampled **5,143,337,264 bytes** of JVM heap in use (process occupancy, not allocation attributable to the audit). Tool wall time was approximately 0.7 s. The MCP presentation window elided deep schema members and automatically retained a **32,822-byte** result artifact, digest `fd0d1f3e09a4c4edbd5312535d039b1876684b8f502dac9a6600ed941dd2014f`; the complete envelope reported `windowed? true`, `retrievable? true`. This was an automatic tool result artifact, not an authored file or program mutation. The elided schema portions are not claimed as individually inspected live. All eight existing branch-error observations were also returned; no missing health evidence was treated as a pass.

Boot (reported readiness 32,980 ms), reload, publication, tests and render-path performance were **not measured by this audit**. Historical over-second status counters are not fresh benchmarks. There is no performance improvement claim. Source scans completed in milliseconds; no measurement script was placed outside this report. Source-only findings and proposed deletion counts remain bounded by these verification limits.

Active teaching sources requiring the future language correction include `docs/research/agent-platform/references-as-refs-design-2026-09-25.md:399–425,527,537` (its no-face-fact ruling is correct; “face map” naming is not needed), `query-render-building-block-review-2026-09-25.md:123`, `language-requirements-2026-09-24.md:177`, `one-render-path-design-2026-09-25.md:92–95,139–171,386,425`, and `task-context-install-design-2026-09-25.md:370,484–485,499,528`. Dated research should retain historical evidence with a correction link, rather than falsely rewriting an old measurement. None was edited here. The data-oriented Clojure and REPL skills were read; their consulted rendering/probe guidance does not justify a new face fact. This assignment's one-file boundary prevents unrelated stale-skill citation edits.

## Appendix A. Every semantic face/faces lexical site

This expands the initial `rg -i face src script resources/seon/schemas` sweep to all production resources, including ignored generated CSS. Each row lists **every matching line**, not only the first match. Counts are distinct lines, not occurrences or deletion estimates. Classification applies to every listed site, with mixed rows split by §2 and the note. Baseline tracked files are read from Git so concurrent edits cannot move this evidence.

| File:line sites | Matching lines | Verdict / concept |
|---|---:|---|
| `resources/public/css/input.css`: 107, 201, 473, 1332, 1334, 1392, 1393, 1394, 1395, 1417, 1419, 1420 | 12 | DELETE / CONFIRMED — D11 role class; RENAME grid/output/diagnostic/type prose; preserve all styling. |
| `resources/public/css/output.css`: 3186, 3190, 3194, 3197, 3256, 3259 | 6 | DELETE/RENAME / CONFIRMED — generated D11 selectors; regenerate from input.css. |
| `resources/seon/schemas/seon.dev.mcp.edn`: 4, 9 | 2 | DELETE / CONFIRMED — R8 redundant AI/HTML role wording; say AI text, HTML, or rendered observation. |
| `resources/seon/schemas/seon.print.edn`: 2, 53, 55, 58, 62, 67, 76, 81, 86, 94, 99, 103, 107, 113, 118, 124, 132, 136, 146, 150, 154, 158, 163, 167, 182, 190, 196, 202, 244, 256, 261, 332, 351, 367, 395, 396 | 36 | RENAME / CONFIRMED — R1; node-type enum, node-shape multi schema, unknown-node-type; preserve classification. |
| `resources/seon/schemas/seon.render.edn`: 253, 255 | 2 | DELETE / CONFIRMED — R8 redundant AI/HTML role wording; say AI text, HTML, or rendered observation. |
| `script/seon/dev/mcp.clj`: 691 | 1 | DELETE / CONFIRMED — R8 redundant AI/HTML role wording; say AI text, HTML, or rendered observation. |
| `src/seon/cluster.clj`: 676, 710 | 2 | RENAME / CONFIRMED — R5 readiness-summary. |
| `src/seon/cluster/boot.clj`: 586 | 1 | DELETE / CONFIRMED — R8 redundant AI/HTML role wording; say AI text, HTML, or rendered observation. |
| `src/seon/config.clj`: 52 | 1 | DELETE / CONFIRMED — R8 redundant AI/HTML role wording; say AI text, HTML, or rendered observation. |
| `src/seon/effect.clj`: 61, 99, 101 | 3 | RENAME / CONFIRMED — R4 payload-text. |
| `src/seon/error.clj`: 472, 1185 | 2 | DELETE / CONFIRMED — redundant HTML role wording; RENAME current representation at 1185. |
| `src/seon/print.cljc`: 20, 31, 37, 53, 59, 70, 76, 98, 128, 132, 135, 136, 140, 141, 142, 144, 145, 146, 147, 148, 151, 155, 157, 164, 168, 173, 175, 178, 182, 186, 189, 219, 221, 239, 240, 241, 244, 245, 272, 284, 285, 308, 309, 310, 391, 396, 491, 498, 512, 524, 587, 597, 603, 628, 629, 652, 658, 667, 669, 721, 738, 742, 780, 782, 786, 788, 789, 795, 800, 811, 825, 846, 859, 869, 872, 914, 921, 923, 924, 925, 934, 936, 938, 942, 959, 960, 965, 995, 1089, 1110, 1154, 1174, 1191, 1202, 1205, 1209, 1214 | 97 | RENAME / CONFIRMED — R1/R2, node and token types; §1 exact-name table; unknown lookup defect at 789. |
| `src/seon/render.clj`: 336, 576, 577, 595, 596, 622, 1027, 1241, 1259, 1266 | 10 | RENAME / CONFIRMED — R1 at 1241,1259,1266; DELETE redundant role prose at 336,576,577,595,596,622; RENAME diagnostic block at 1027. |
| `src/seon/render/transcript.clj`: 987 | 1 | RENAME / CONFIRMED — R1 elision node. |
| `src/seon/render/value.clj`: 185, 189, 476, 507, 512, 552, 559, 567, 568, 579, 589, 592, 594, 596, 598 | 15 | RENAME / CONFIRMED — R1 constructors/locals and R6 identity representation. |
| `src/seon/render/web.clj`: 3303, 3304, 3314, 3341, 3344, 3353, 3357, 3358, 3359, 3360, 3369 | 11 | DELETE / CONFIRMED — D10/D11 helper/role labels/common class; RENAME actual grid container. |
| `src/seon/sci/admit.clj`: 6, 159, 160, 184, 223, 224, 231, 238, 254, 284, 294, 295, 296, 297, 340, 350, 358, 362, 366, 379, 382, 386, 388, 446, 462, 483, 493, 584, 606, 627, 635, 698, 699, 700 | 34 | RENAME / CONFIRMED — R1/R3; node types, frame node-type and canonical envelope strings. |
| `src/seon/sci/eval.clj`: 4263 | 1 | RENAME / CONFIRMED — R7 host/default print binding. |
| `src/seon/test/accretion.clj`: 380 | 1 | RENAME / CONFIRMED — R8 rendered install refusal / rendered error body; real presentation, no role fact. |
| `src/seon/test/runner.clj`: 103 | 1 | RENAME / CONFIRMED — R8 rendered install refusal / rendered error body; real presentation, no role fact. |

**Total: 239 matching lines**, including generated CSS. Renaming these lines does not delete their necessary node/type/representation information.

## Appendix B. Every remaining literal substring match

These are the other results of the required broad `rg -i face`, excluded from Appendix A only because the match is part of another word. Every site is retained here. The DOM identities and local/prose names receive RENAME verdicts in §6; real dependency identifiers are explicitly marked external, not quietly counted as removals.

| File:line sites | Matching lines | Disposition |
|---|---:|---|
| `resources/public/css/input.css`: 21, 484, 486, 487, 491, 495, 500, 558, 620, 1056, 2181, 2185, 2195, 2203, 2207, 2214, 2219, 2220, 2222, 2223, 2226, 2236, 2239, 2242, 2245, 2248, 2255, 2265, 2273, 2281 | 30 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `resources/public/css/output.css`: 1761, 1765, 1769, 1774, 3997, 4004, 4012, 4016, 4023, 4030, 4033, 4036, 4039, 4042, 4052, 4055, 4058, 4061, 4064, 4067, 4070, 4078, 4086 | 23 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `resources/seon/schemas/seon.dev.mcp.edn`: 12 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `resources/seon/schemas/seon.render.edn`: 205 | 1 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `resources/seon/schemas/seon.render.package.edn`: 3, 11 | 2 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `resources/seon/schemas/seon.schedule.fire.edn`: 10 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/ai/tokens.cljc`: 133 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/cluster.clj`: 1076, 1549, 2217, 2222 | 4 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/cluster/agent.clj`: 462 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/cluster/boot.clj`: 512 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/cluster/message.clj`: 105 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/context.clj`: 382 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/db.clj`: 20, 437, 826, 827 | 4 | External API/language references and/or adjacent prose; exact exceptions and safe local renames in §6. No face concept. |
| `src/seon/error.clj`: 327 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/fn.clj`: 1059, 1074 | 2 | External API/language references and/or adjacent prose; exact exceptions and safe local renames in §6. No face concept. |
| `src/seon/instrument.clj`: 68, 77, 78 | 3 | External API/language references and/or adjacent prose; exact exceptions and safe local renames in §6. No face concept. |
| `src/seon/issue.clj`: 952 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/issue/detect.clj`: 124, 130, 224 | 3 | External API/language references and/or adjacent prose; exact exceptions and safe local renames in §6. No face concept. |
| `src/seon/oversight.clj`: 300, 307 | 2 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `src/seon/problems.clj`: 7, 479, 494, 510, 531 | 5 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/profile.clj`: 270 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/reconcile.cljc`: 426 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/render/block.clj`: 5, 15, 16, 30, 42, 61, 66, 70, 74, 84 | 10 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `src/seon/render/hiccup.clj`: 509 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/render/ns.clj`: 541 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/render/test.clj`: 67, 74 | 2 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `src/seon/render/transcript.clj`: 641, 660, 1224, 1313, 1759, 1762, 2276, 2285, 2409 | 9 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `src/seon/render/value.clj`: 220 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/render/web.clj`: 35, 276, 280, 311, 313, 315, 341, 379, 404, 440, 532, 3382, 3434, 3829, 3833 | 15 | RENAME / CONFIRMED — block/element identity, markup/CSS/link references or their prose; atomic DOM migration, §6. |
| `src/seon/schedule.clj`: 14 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/schema.clj`: 29 | 1 | External API/language references and/or adjacent prose; exact exceptions and safe local renames in §6. No face concept. |
| `src/seon/schema/internal.cljc`: 4 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/sci/admit.clj`: 681 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/sci/eval.clj`: 1925, 2158, 3177 | 3 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/sci/kernel.clj`: 407 | 1 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |
| `src/seon/turn.clj`: 5477, 5524 | 2 | RENAME / CONFIRMED — surface/view/block or surfaced/reported prose; no AI/HTML role. |

## Appendix C. Followed selector and profile-key references

Every literal `seon.render/output`, `seon.render/requested-output`, `seon.render/output-schema` and `seon.render.profile/id` line in the baseline production roots appears below. This deliberately includes schema names and error-layer names: not every hit is a destination. §2–§4 distinguishes request selectors (DELETE), returned values (RENAME to their attachment), genuine contract schemas/options (retain information) and historical diagnostic provenance (PLAUSIBLE retirement). Aliased local `output` parameters and dispatch sites are traced in §2–§3; they are not missed merely because this appendix uses fully qualified keys.

| File:line sites | Matching lines | Concept and decision |
|---|---:|---|
| `resources/seon/schemas/seon.print.edn`: 238, 240, 246, 327, 329, 341, 360, 376 | 8 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `resources/seon/schemas/seon.render.cost.edn`: 2 | 1 | §4 profile-id: PLAUSIBLE DELETE label; preserve actual limits, requery and needed provenance. |
| `resources/seon/schemas/seon.render.edn`: 82, 83, 88, 137, 147, 233, 242 | 7 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `resources/seon/schemas/seon.render.profile.edn`: 10, 11 | 2 | §4 profile-id: PLAUSIBLE DELETE label; preserve actual limits, requery and needed provenance. |
| `resources/seon/schemas/seon.render.unknown.edn`: 11 | 1 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `resources/seon/schemas/seon.render.walk.edn`: 42, 83 | 2 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `src/seon/cluster.clj`: 452 | 1 | §4 profile-id: PLAUSIBLE DELETE label; preserve actual limits, requery and needed provenance. |
| `src/seon/cluster/prompt.clj`: 263 | 1 | §4 profile-id: PLAUSIBLE DELETE label; preserve actual limits, requery and needed provenance. |
| `src/seon/error.clj`: 937, 1828 | 2 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `src/seon/print.cljc`: 71, 81, 769, 771, 991, 1001, 1048 | 7 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `src/seon/render.clj`: 80, 283, 291, 296, 463, 492, 525, 578, 579, 597, 678, 701, 727, 730, 822, 863, 936, 965, 998, 1052, 1112, 1124, 1130, 1242, 1261, 1293, 1299, 1310, 1331, 1335, 1347, 1351, 1362, 1366, 1480, 1484, 1495, 1604, 1938 | 39 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `src/seon/render/transcript.clj`: 993, 1587, 2133 | 3 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `src/seon/render/value.clj`: 631 | 1 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `src/seon/render/walk.clj`: 716, 791, 802, 849 | 4 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `src/seon/render/web.clj`: 204, 247, 387, 489, 510, 515, 860, 861, 1138, 1202, 1251, 1446, 1520, 1797, 2944, 3202, 3315, 3476 | 18 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |
| `src/seon/turn.clj`: 1883 | 1 | D1–D9 / §4: DELETE duplicate selector; preserve returned data, schema checking and concrete limits. |

## Appendix D. File sizes and pinned source identity

Git commit `ff381f1ab` fixes the complete bytes of every tracked file in the inventories. The table gives physical line counts and SHA-256 for files containing a semantic face term; this is evidence identity, not another runtime version scheme. Generated `output.css` is the local observed artifact and is explicitly not in Git.

| File | Lines | SHA-256 |
|---|---:|---|
| `resources/public/css/input.css` | 2466 | `18337f99fd489743b575632574bb2c9e1166896fee15b4ee7cf30db5ab9f988f` |
| `resources/public/css/output.css` | 4366 | `7b1c0fd38d9723c87f57445e8aeb32b72d0e325927c1e4bcd2e416c25797d33a` |
| `resources/seon/schemas/seon.dev.mcp.edn` | 64 | `1d8135d02ca0a101ccf6a957a5523b143b9a44bc5bf287b07cb6baace9a2ce95` |
| `resources/seon/schemas/seon.print.edn` | 396 | `67c2e76bf906a402425db8954fa05b5856eb8e67d7cba39bf98ec48d3c73da35` |
| `resources/seon/schemas/seon.render.edn` | 259 | `9eb9302f92119467acb120e8c40fcd3a876d3d23adb1d617ff7c742ffad34757` |
| `script/seon/dev/mcp.clj` | 964 | `d6cec0a3f0d6a4bc26a3e006daf997288b0c58c276c3065e0aa0834dec08f440` |
| `src/seon/cluster.clj` | 3740 | `c7ea39c3d55c4de127c19d32ed566d6f7e5a2fe34b952d14a941ebeeb68b5d64` |
| `src/seon/cluster/boot.clj` | 724 | `ed14139933688b59bbddd40fb757c1a6fdbec4fb901e396050bbd31813461c3a` |
| `src/seon/config.clj` | 861 | `96852b7ef66a1808b94f359c340dc4fbaf2fdb3d20a945e92a52a6be3ddf159c` |
| `src/seon/effect.clj` | 929 | `9a7d31869786ad5e71f91ae3ab1a87b8ee27fdbb1b348d3ee13778c1d1098361` |
| `src/seon/error.clj` | 2451 | `11fdfc3e02fb201330eec2bf79ecc95a88c881084180ac9ac01dde21adfc1a82` |
| `src/seon/print.cljc` | 1307 | `6be6494d21d8637ed96cbb00fd9ffaf962b7354154a25735332ec1787165041c` |
| `src/seon/render.clj` | 1969 | `0aacffb2015e430c7c214c52996cba3b3a4c74394b7d35aaa1c92df39be32185` |
| `src/seon/render/transcript.clj` | 2486 | `f5c79628661583926d57c6162d0befcc693de7ff0f08ef75ab43b7a5a86d3aa4` |
| `src/seon/render/value.clj` | 755 | `ff676209ede56e29076ed8024339eba8d34aea4622bd834524855163531ad2c1` |
| `src/seon/render/web.clj` | 3901 | `75ae272f230c5b3a2c8bb5f9a8a66b63d2fbc179353133c08d6295437e200bae` |
| `src/seon/sci/admit.clj` | 885 | `d3c5e0481d5f90bb72e061ed1f73b7f7558647a2a3d5411f08fa67b6e7be3824` |
| `src/seon/sci/eval.clj` | 4640 | `fd313a6762a60d3e937d93a648696630290ec6a1dad929f8946d70a9e180f26b` |
| `src/seon/test/accretion.clj` | 414 | `0b5ce75d1d139e16eac3b83a21d3bdb7caf91f39f87cbbd81b73c02c6e433cff` |
| `src/seon/test/runner.clj` | 1885 | `f480a185ccdcf9197d643b0b8281acd6f560ff2ce37f6f90c7ef555976f6cb28` |

Reproduce without writing files or running tests:

```sh
rg -n -i face src script resources/seon/schemas
rg -n -i face src script resources --max-columns 240 --max-columns-preview
rg --no-ignore -n -i face resources/public/css/output.css
rg -n "seon.render/(output|output-schema|requested-output)|seon.render.profile/id" src script resources/seon/schemas
git diff --name-only ff381f1ab -- src/seon/render.clj src/seon/print.cljc resources/seon/schemas/seon.print.edn
```

For immutable line references use `git show ff381f1ab:<path>` and count lines from 1. Semantic lexical membership in Appendix A is the Python expression `re.search(r"(?i)(?<![A-Za-z])faces?(?![A-Za-z])", line)`; Appendix B is the complement within case-insensitive substring matches. It includes namespace-qualified keys, hyphenated names and CSS classes, and excludes `surface`/`interface` only into the **visible second inventory**, not from the audit. An initial trial of this lookbehind with default `rg` was unsupported; the inventory was recomputed with Python, not reported as a zero-match result.
