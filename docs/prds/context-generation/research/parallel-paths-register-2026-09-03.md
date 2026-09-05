---
type: research
status: complete
tags: [research, context, render, deletion, architecture]
---

# REPL-first parallel paths register — 2026-09-03

**Status:** source census for ruling 56 implementation planning; no production
change is authorized here.

**Question.** Which current paths make the same semantic decision that the
REPL-first context design assigns to one walk, one render-selection chain, one
print floor, and one recorded basis?

**Authorities read end to end:**

- `docs/prds/context-generation/plan/repl-first-behavior-2026-09-03.md`
  (591 lines);
- `docs/prds/context-generation/plan/repl-first-context-design-2026-09-02.md`
  (284 lines);
- `docs/prds/context-generation/research/repl-first-probes-2026-09-02.md`
  (186 lines);
- `docs/prds/context-generation/research/hardcoded-context-census-2026-08-28.md`
  (category 3 is the narration-face inventory).

The census read every source owner named below end to end and used `rg` over
`src/` and `test/` for callers. Line counts and citations are the working-tree
bytes read on 2026-09-03. `src/seon/render/web.clj` was an explicitly protected,
foreign in-flight edit (2,155 lines rather than the seed's older count), so this
register observes it but does not attribute or alter it. `git log -S` dates the
few origins for which history changes the disposition: the bounded transcript
projection entered in `64ea0a5ba`, render-cost facts in `53448d227`, the
`my.run` face in `1559764d9`, and root-pull membership diff in `c98535249`.

## Result in one sentence

There are **three semantic context assemblers** today—generated opening
(`seon.bootstrap`), generic render history (`seon.render.walk/history`), and the
independent transcript projection (`seon.render.transcript`)—wrapped by **two
live prompt glue paths** (`seon.render.web/context-pass` and
`seon.cluster.prompt`) plus a prospective-debug reassembly. The REPL-first
design should make generated eval entries the sole semantic history; the web
proc may retain and join their bytes, and the prompt owner may capture the
finished prompt, but neither should select context or shrink graph distance.

Dispositions use the behavior authority's labels: B1–B12 and G. “MERGE” means
move the surviving responsibility into the named owner before deleting the old
path; “DELETE” names an already sufficient replacement; “KEEP” means the path
has a distinct authority; “DECIDE” gives the owner three priced options.
`behavior:N-M` below abbreviates the fully read
`docs/prds/context-generation/plan/repl-first-behavior-2026-09-03.md:N-M`;
within a table cell, a later `:N-M` continues the immediately preceding file.

**Source-size ledger.** `seon.render.walk` 829 lines;
`seon.render.transcript` 1,009; `seon.cluster.prompt` 268;
`seon.render.web` 2,155 working-tree lines; `seon.bootstrap` 794;
`seon.render` 982; `seon.render.ns` 675; `seon.sci.eval` 2,357; `my.run`
154; `seon.render.route` 55; `seon.db` 2,038; `seon.print` 949;
`seon.render.value` 638; `seon.sci.admit` 527; `seon.cluster` 2,987;
`seon.context` 214; `seon.eval.drive` 461. Rows name an owner or say “inside”
one of these owners; this is the line count for each registered path.

## 1. Context and prompt assembly

| path / mechanism (owner size) | evidence and production callers | behavior | disposition |
|---|---|---|---|
| Root selector and neighbourhood acquisition (`seon.render.walk`, 829 lines) | `root-selector` derives scalar, identity, forward-ref, and reverse-ref attributes from installed schema (`src/seon/render/walk.clj:86-151`); `root-acquisition` performs one pull (`:337-410`); `neighborhood` turns members into `/form` and `/ai` render calls (`:519-608`). `history` is called by the web render proc (`src/seon/render/web.clj:1157`) and prospective debug (`:558`), plus tests. | G1 has the same schema-derived bidirectional walk (`behavior:457-475`), but G2 requires evaluated form entries and demand closure rather than current member narration (`behavior:477-493`). Supports G1; contradicts B2/G2 where it emits authored text such as `renderer unavailable` (`walk.clj:594-607`). | **MERGE INTO the generation owner described by G.** Keep schema-derived root selection and the one-pull acquisition; replace `neighborhood`'s direct render-to-history projection with generated form/value eval entries. |
| Generic render history (`seon.render.walk/history`, inside 829-line owner) | `generic-history-entries` builds `ns=> form\nvalue`, memo bytes, and calls metadata (`src/seon/render/walk.clj:730-794`); public `history` runs neighbourhood twice, once for `/form`, once for `/ai` (`:796-829`). Production caller is `web/context-pass` (`web.clj:1157`); debug calls it separately (`:558`). | Superficially B2/B7, but the form is an authored render projection rather than the stored result of one generated eval; G4 requires each system entry to be stored as an eval (`behavior:554-569`). | **DELETE after MERGE INTO generated eval history.** Replacement: the same ordered form + settled value entries B7 uses, with each discovery/diff entry an eval. This removes the second `/form` rendering pass too. |
| Live render-proc retention and join (`seon.render.web`, 2,155 working-tree lines) | `append-history` maintains one newest logical call (`src/seon/render/web.clj:1062-1114`); `history-segments`/`history-text` join retained `:seon.render.history/bytes` (`:1116-1126`); `context-pass` listens before deriving and returns the joined text (`:1128-1172`). `seon.cluster.prompt` receives this contribution via flow; no second semantic caller. | Supports B7 ordering and event-driven delivery. It is not itself a source of truth, but today it retains the output of the obsolete walk-history assembler. | **KEEP the event-driven retention/join; MERGE its input INTO generated eval entries.** This proc-state memo is losable and re-derivable, exactly the channel/memo role; it must not decide membership, summaries, or omission. |
| Prompt contribution/budget loop (`seon.cluster.prompt`, 268 lines) | `history-contributions` retrieves the retained render contribution (`src/seon/cluster/prompt.clj:151-180`); `acquire-within-budget` retries the complete acquisition at successively smaller graph distance (`:182-232`); `prompt` is called by the run loop (`src/seon/cluster/loop.clj:1369`). | Distance shrink contradicts B4's per-value print floor and B8's compaction boundary; it can silently remove whole graph edges instead of emitting honest elisions. It also makes G1's default two-hop walk budget-dependent. | **DELETE the distance-shrink loop.** Replacement: one G-generation at the ruled distance, each value through `seon.print/fit`, then B8 compaction over evals if total context exceeds its budget. Keep only contribution ordering and the final bounded prompt/capture boundary. |
| Prospective-debug prompt reassembly (`seon.render.web`, inside 2,155-line owner) | `prospective-prompt` calls `render.walk/history` and independently joins entry bytes (`src/seon/render/web.clj:546-563`); it falls back to the durable captured prompt (`:571-584`). Its caller is the debug page only (`:584`). | Contradicts B10's one generation/two projections when uncaptured debug reconstructs a second AI prompt path. The capture fallback is honest evidence. | **MERGE INTO the same generation API used by running context.** Debug may display a captured prompt or explicitly labelled typed-unknown; it must not assemble its own approximation. Keep the captured-prompt read. |
| Independent transcript projection (`seon.render.transcript`, 1,009 lines) | It separately queries messages/receipts/forms (`src/seon/render/transcript.clj:27-535`), reads and narrates family values (`:537-807`), applies a fixed six-entry full-detail tail and summary selection (`:809-861`), then builds another durable history with another byte-prefix budget (`:895-969`). The only non-test AI caller is episode grading (`src/seon/eval/drive.clj:294-305`); its HTML twin is schema-selected for an agent (`resources/seon/schemas/seon.cluster.agent.edn:7`). | Partly serves B7, but conflicts with B2/B3/B4/B8/G by reconstructing and re-rendering history through fixed-tail policies rather than replaying settled generated and agent eval entries. The hardcoded six was introduced with the separate bounded renderer (`64ea0a5ba`). | **DELETE as a context/history mechanism after MERGE INTO generated eval history.** Episode grading and agent HTML should consume the same ordered entries through `/ai` or `/html`; no fixed-tail or “best summary” projection survives B8 compaction. |
| Generated opening episode (`seon.bootstrap`, 794 lines) | Candidate discovery and ordering spans `direct-candidates`, `listing-candidates`, and `pull-result` (`src/seon/bootstrap.clj:202-519`); `next-entry-in` matches stored generated receipts to the next candidate (`:536-606`); the run loop calls `next-entry` (`src/seon/cluster/loop.clj:1782`). Supervision forms remain hardcoded strings (`bootstrap.clj:644-740`) and `seed-tx` hand-assembles initial refs (`:742-794`). | It is the one current path already shaped as generated evals, supporting B1/B2/G3/G4, but its candidate/narration/supervision machinery predates G's schema walk and demand closure. | **MERGE INTO the G generation owner and then delete the candidate/supervision special cases.** Preserve generated-eval settlement and trigger-last ordering; derive arrival, teaching demand, and discovery from the same walk used on later turns. |
| Bootstrap's second source reader (`seon.bootstrap`, inside 794-line owner) | `calls-symbol?` parses stored Clojure source with `clojure.edn/read-string`, swallowing failures as false (`src/seon/bootstrap.clj:621-643`); supervision then recognizes specific called symbols (`:644-679`). No caller exists outside bootstrap's next-entry path. SCI already owns reading/evaluating agent forms. | Contradicts B12's no hidden narration and the one-reader/absence-is-not-health laws; parse failure becomes “does not call” rather than a diagnostic. | **DELETE with bootstrap supervision.** Replacement: declared program/eval facts and the single SCI reader at generated-eval settlement. Do not create a second source classifier for G demand closure. |

**Count.** The five seed namespaces do not mean five equal assemblers.
`bootstrap`, `walk/history`, and `transcript` make semantic entry selections;
`web/context-pass` is losable retention/join; `cluster.prompt` is final glue but
also wrongly reselects the walk by shrinking distance. Prospective debug is a
fourth semantic assembly only when no capture exists, and should disappear.

## 2. Render selection and hardcoded faces

| path / mechanism (owner size) | evidence and callers | behavior | disposition |
|---|---|---|---|
| The four-rung render function chain (`seon.render`, 982 lines) | `explicit-producer` reads a value-named function (`src/seon/render.clj:218-228`); `candidates` finds contracted publics in the viewer namespace (`:178-205`); `schema-producer` selects the greatest required-key schema face (`:230-272`); `attribute-producer` resolves schema metadata (`:274-279`); `producer` orders explicit → viewer candidates → declared schema face → floor (`:301-320`). `render-call` is the production caller (`:642-697`); tests directly probe the private selector. | This is already B3's one specificity chain (`behavior:114-166`), not four parallel mechanisms. Loud same-rung ambiguity is correct. | **KEEP as one owner.** Rename/refactor rungs in place to match B3 exactly; do not split registries. |
| Malli brute-force viewer candidates (`candidates`, inside 982-line owner) | Every selection scans the viewing namespace's public program rows and asks Malli whether each function accepts the value and returns the projection (`src/seon/render.clj:178-205`). Only `producer` calls it (`:309-315`). The probes measured ~0.3 ms, so this is not the current latency problem. | Supports B3 rung (b) and G5, but selection is reconstructed from publics on every call although contracts are program facts. | **MERGE INTO `seon.render/producer` via one program-graph query/index.** Preserve contract fit and loud ties; remove the per-render public scan only when the query can express the same specificity. This is cleanup, not a prerequisite for behavior. |
| Schema-property faces (`resources/seon/schemas/`, 366 `:seon.render/ai` occurrences in current bytes) | `schema-producer`/`attribute-producer` consume these declarations (`render.clj:230-299`). There are 285 declarations naming `seon.error/render-ai` across 59 files; the declarations are indexed at schema registration and selected in production by `producer`. | General schema faces are explicitly B3 rung (c), so deleting the property mechanism would contradict ruling 56. The 285 identical error declarations are a hand-repeated family default, however. | **KEEP schema metadata; MERGE repeated error defaults INTO the common error-value/schema registration owner.** One general error render function should be derivable for every `:seon.error/value`, while genuinely family-specific faces remain declarations. |
| Fourteen narration faces (hardcoded census category 3) | The dated census identifies message, run, plan, test, receipt/error, namespace, bootstrap, and other renderers that convert facts to English; examples include `render-ai` narration in `src/seon/render/ns.clj:277-305` and the dedicated `my.run` face below. They are called through the schema-property rung rather than direct call sites. | Contradict B0's data-first REPL, B2's form/value grammar, and B12's “no hand-authored narration” (`behavior:651-656`); many lack honest HTML twins, contradicting B10. | **DELETE narration, replacement: structured data through family-specific render functions only where they materially format a family, otherwise the print floor.** A face that survives must have both projections or the ruled HTML `<pre>` fallback and recorded render function provenance. |
| Render-call fallback identity | `producer` explicitly returns ``seon.render.value/render-ai-data`` or ``render-html-data`` at the floor (`src/seon/render.clj:316-319`), so even the default has a symbol. | Supports G6's requirement that the default be nameable. | **KEEP; record and display it.** It is evidence that no inference or special “default” sentinel is necessary. |

## 3. Describing a namespace

| path / mechanism (owner size) | evidence and callers | behavior | disposition |
|---|---|---|---|
| Acquisition-time function documentation (`seon.sci.eval`, 2,357 lines) | `program-documentation-selector` and config read program rows (`src/seon/sci/eval.clj:987-1018`); `program-documentation` creates a function-symbol map at context acquisition (`:1076-1105`); injected `doc` handles function symbols and `dir` lists names (`:1107-1156`); context installation calls it (`:1158-1172`). | Today only function symbols satisfy `doc`, while B6 requires polymorphic functions, namespaces, schemas, tests, values, and collections (`behavior:286-323`). It is also a frozen documentation mirror made at acquisition. | **MERGE INTO one live, polymorphic `doc`/`dir` data owner.** Retain injected bare names, but have calls query the passed database/program projection; do not prebuild a second documentation map. |
| Namespace renderer (`seon.render.ns`, 675 lines) | It independently queries namespace/function/schema facts (`src/seon/render/ns.clj:21-89`), parses schema closure with a hard cap of 40 (`:95-220`), assembles namespace data (`:277-305`), and owns bespoke AI budget/elision (`:311-506`) plus HTML layout (`:512-638`). Its form render functions merely emit `(dir ns)`/`(doc sym)` (`:644-660`); schema metadata selects its AI/HTML entry points (`:662-675`). | It contains most of B6's target data and B10's twin, but duplicates documentation lookup and print policy. | **MERGE query/data projection INTO polymorphic `doc`; MERGE both outputs INTO the ordinary B3 chain; DELETE its private budget loop in favor of `seon.print/fit`.** The 675-line all-in-one owner should shrink to data derivation plus thin twins. |
| Dedicated `my.run` namespace face (`my.run`, 154 lines) | `render-namespace-ai` handwrites a special description (`src/my/run.clj:17-39`); `resources/seon/schemas/my.run.edn:11-15` attaches it; only tests call it directly, while production reaches it through `seon.render/producer`. It entered in `1559764d9`. | Contradicts B6's one polymorphic namespace description and B12's no authored narration. | **DELETE.** Replacement: `(doc my.run)` through the one namespace data projection and ordinary print/render chain. |
| Namespace web route (`seon.render.route`, 55 lines; web owner above) | The route table maps `/namespace/{namespace}` and aliases to the page mechanism (`src/seon/render/route.clj:5-27`); the web walk request derives a namespace root (`src/seon/render/web.clj:1758-1774`) and page response delivers packages (`:1776-1805`). The route does not author namespace meaning. | Serves B10 transport, but the current page is not yet guaranteed to be the exact `/html` twin of `(doc ns)`. | **KEEP route/transport; MERGE page content INTO the same `(doc ns)` generation.** Counting descriptions, there are three today (SCI function-only doc map, general namespace renderer, `my.run` special); the route is a consumer, not a fourth description. |

## 4. Diff and change delivery

| path / mechanism (owner size) | evidence and callers | behavior | disposition |
|---|---|---|---|
| `seon.db/diff` read-at-basis mechanism (`seon.db`, 2,038 lines) | `diff-plan` validates one pure program Var (`src/seon/db.clj:1459-1531`); the owner invokes it at basis and current (`:1618-1630`); `identity-diff` returns added/removed/changed keyed by installed identity (`:1632-1661`); public `diff` supplies both bases and a requery form (`:1742-1781`). Direct callers are agents/tests; G has not yet generated it. | This is the valid B5 basis comparison, including additions and deletions. It must widen from Var-only to pure q/pull spellings (`behavior:267-274`). | **KEEP as the single semantic diff owner; ACCRETE q/pull forms in place.** Generated G4 entries call this mechanism, never `since`. |
| M13 AI-only diff face (`seon.db/render-diff-ai`) | It renders counts and the sentence “full data elided” (`src/seon/db.clj:1663-1688`); only tests/direct agent calls reach it, and the schema property makes it the general face. Existing issue: `docs/seon/issues/db-diff-render-bypasses-print-fit-and-has-no-html.md`. | Directly contradicts B5's family-rendered additions/deletions and B10/B12. | **DELETE after MERGE INTO ordinary rendering.** Replacement: structured diff data whose changed family values use their B3 renderer, bounded by `seon.print/fit`, with an HTML twin. |
| Root-membership diff (`seon.render.walk/membership-diff`) | Compares member identity maps and reports add/remove/change (`src/seon/render/walk.clj:412-430`). `rg` finds no production caller; only `test/seon/render/root_pull_test.clj:312-367` calls it. It arrived with root-pull work (`c98535249`). | Duplicates B5 semantics without recorded basis or a runnable requery form. | **DELETE.** Replacement: each walk discovery query is a generated eval and later `seon.db/diff` against that eval's recorded basis. Delete its isolated regression with it. |
| Vendored editscript | Runtime dependency exists only at `deps.edn:14`; `rg` finds no `src/` or `test/` call. Remaining uses are research scripts/docs. The earlier investigation rejects positional diff without identity keying (`docs/prds/context-generation/research/general-diff-and-render-2026-08-13.md:429-441`). | Does not serve B5 in production; carrying it suggests a second diff engine. | **DELETE from runtime dependencies and the vendored submodule when no other roadmap owner claims it.** Replacement: identity-keyed `seon.db/diff`; keep dated research scripts as evidence, with explicit research deps if rerun. |
| Read-evidence replay equality | Each captured database read stores request, dependency revision, and the full stable result (`src/seon/db.clj:214-294`, `:369-386`); `read-evidence-current?` uses revision comparison and replays the request to compare full values when revision evidence is unavailable (`:432-452`). `seon.render/render-call` is its production caller (`src/seon/render.clj:655`). | This is cache invalidation, not B5's user-visible diff. Persisting the full result duplicates eval result bytes and was measured at 223 KB for six evals. | **MERGE currentness INTO dependency revisions; DELETE durable `:seon.db/read-result`.** If a read cannot declare/recompute revision evidence, treat the cache as stale and re-render. B5's recorded basis supplies historical comparison; cache proof need not store a second result. |
| Web keyframe/delta packages (`seon.render.web`, protected 2,155-line owner) | `next-package` computes changed rendered fragments (`src/seon/render/web.clj:696-722`); `package-patches` chooses a contiguous delta only when smaller, otherwise a keyframe (`:733-743`); render/delivery paths call them (`:1024`, `:1523`). | This is B10 delivery-unit compression, not semantic B5 diff. It operates after HTML render and may legitimately use fragment equality. | **KEEP.** Do not merge transport packages with `seon.db/diff`; assert separately that a revision gap gets a keyframe and removed blocks produce an honest delivery operation. |

## 5. Bounding, admission, and elision

| path / mechanism (owner size) | evidence and callers | behavior | disposition |
|---|---|---|---|
| Total print floor (`seon.print`, 949 lines) | Elision rendering lives at `src/seon/print.cljc:283-304`; elision values/requery identity at `:686-786`; `fit` owns the budget loop at `:908-943`. It is called by generic rendering (`src/seon/render.clj:524`), value rendering (`src/seon/render/value.clj:504,605`), MCP (`src/seon/cluster.clj:378`), and tests. | It is B4's one presentation authority, but today's loop halves strings to zero, then children, then depth—the measured destructive order (`behavior:181-200`). | **KEEP and refactor in place.** Make it the only presentation omission decision: shape-preserving, breadth/depth first, strings last with a useful floor, one pasteable requery form per elision. |
| Render profile | `seon.render/profile` derives database-backed caps (`src/seon/render.clj:45-103`); the schema requires token, depth, child, and composition fields (`resources/seon/schemas/seon.render.profile.edn:1-18`). Profiles are carried in render requests. | Supports B4's explicit consumer policy and “values carry their world.” Current knobs cause per-collection clipping in addition to the total token budget. | **KEEP as policy data; MERGE omission semantics INTO `seon.print/fit`.** A consumer may choose a budget, but no downstream owner invents another ladder. Decide B4.1 before deleting depth/child knobs. |
| Admission caps (`seon.sci.admit`, 527 lines) | Node count, collection width, string, and depth caps are enforced while admitting external values (`src/seon/sci/admit.clj:94-209`, `:339-375`, `:451-527`); config defaults are large execution-safety bounds (`config/default.edn:55-64`). | These are total boundary safety, not presentation. Merging them into the render budget would make values unsafe or silently presentation-shaped before rendering. | **KEEP as a distinct realization boundary.** Admission may refuse/cap an impossible value; visible omission remains `seon.print` data. Name the distinction in contracts/tests. |
| `/data` value windows (`seon.render.value`, 638 lines) | Page limits are capped by admission width (`src/seon/render/value.clj:110-119`); `window` returns explicit offset/total slices (`:135-175`); route preparation admits, enriches elisions, and fits (`:465-539`). `render-ai-data` appends another authored elision sentence (`:541-547`). | Explicit pagination serves B11 digging; its additional prose/omission policy contradicts B4/B12. | **KEEP explicit window/requery; DELETE the authored suffix and any second fitting policy.** A returned window is data; `seon.print/fit` alone decides how it displays. |
| Namespace private fit ladder (`seon.render.ns`, 675 lines) | It separately budgets sections/rows and emits its own omissions (`src/seon/render/ns.clj:311-506`), then makes independent HTML section choices (`:512-638`). | Duplicates B4 and risks AI/HTML divergence under B10. | **DELETE.** Replacement: namespace doc data rendered through the standard `/ai` and `/html` chain, with `seon.print/fit` the sole omission authority. |
| Transcript tail, summaries, and prefix fit (`seon.render.transcript`, 1,009 lines) | Fixed `recent-entry-count` is 6 (`src/seon/render/transcript.clj:27-31`); `best-summary` and `projection` pick full vs summarized entries (`:803-861`); `history-entries` runs a second whole-history prefix loop (`:908-969`). | Duplicates B4 and conflicts with B8's ruled compaction over evals. Old entries can silently change representation without a compaction fact. | **DELETE with transcript projection.** Replacement: per-entry `seon.print/fit`, then explicit B8 compaction/regeneration when total context exceeds budget. |
| Prompt distance shrink (`seon.cluster.prompt`, 268 lines) | The retry loop decrements distance until estimated prompt tokens fit (`src/seon/cluster/prompt.clj:182-232`). | Omits entire graph paths without an elision or requery identity; contradicts B4/B8/G1/B11. | **DELETE.** Replacement: fixed ruled walk distance + honest value elisions + compaction. |
| MCP projection and blob spill (`seon.cluster`, 2,987 lines; seed name `seon.cluster/mcp-project`, not a separate namespace) | `mcp-project` admits the result, but applies `print/fit` only after the serialized artifact exceeds the blob threshold (`src/seon/cluster.clj:339-427`); ordinary smaller results cross unfit. The 2026-09-03 probe observed fit reducing strings to zero characters at this MCP result projection. MCP reply is the sole caller (`:436-437`). | The spill threshold is storage policy; presentation still must obey B4. Using one threshold to trigger the other conflates them. | **MERGE presentation INTO unconditional `seon.print/fit` with an explicit MCP consumer profile; KEEP blob spill/retrieval as separate storage policy.** Never use payload size as permission to bypass the print floor. |

**Count.** Six places currently decide visible omission: `seon.print/fit`,
render-profile knobs, `render.ns`, `render.transcript`, prompt distance shrink,
and conditional MCP fitting. `render.value/window` is a seventh bound but is
legitimate explicit pagination; `sci.admit` is an eighth bound but is legitimate
boundary safety. They do not agree: only `seon.print` produces canonical
elision values, while transcript summaries, namespace omissions, graph-distance
loss, and the MCP threshold encode different semantics.

## 6. Evals, read evidence, captures, and stored bytes

| stored family / memo | evidence and readers/writers | authority or duplication | disposition |
|---|---|---|---|
| Eval result EDN and blob | Successful SCI eval serializes one admitted print node (`src/seon/sci/eval.clj:1804-1826`). Settlement stores inline `:seon.cluster.eval/result-edn`, or for oversized results stores the full bytes as `:seon.cluster.eval/result-blob` plus a fitted result window (`src/seon/cluster/run.clj:84-158`). Transcript, drive, rendering, and tests read result EDN/blob. | The full inline EDN or blob is the durable outcome authority. The fitted inline window beside a blob is a derived access memo, not a second full authority. | **KEEP one full artifact plus an explicitly derived bounded window.** Do not store the same full bytes in both attributes; the schema/reader should make authority vs window unambiguous. |
| Per-read full `:seon.db/read-result` inside eval evidence | `captured-read`/`captured-read-entry` attach a stable full result (`src/seon/db.clj:214-294`); `read-evidence-tx` persists it (`:369-386`); currentness may replay and compare it (`:432-452`). The eval receipt schema admits these rows (`resources/seon/schemas/seon.cluster.eval.edn:9-25`). | Duplicate. The eval result already records what the form returned; read evidence needs dependencies/revisions sufficient to decide reuse, not every intermediate full value. Probe evidence measured 223,182 result characters for six evals. | **DELETE `:seon.db/read-result` after revision currentness becomes total.** Keep request/plan/revision/basis. An unprovable currentness check returns stale/unknown, never “healthy.” |
| `:seon.render.history/bytes` | Walk builds it as rendered entry text (`src/seon/render/walk.clj:783-790`); web proc retains and joins it (`src/seon/render/web.clj:1062-1126`). It is proc state, not a database attribute; tests construct it directly. | Legitimate losable render memo today, although its source becomes obsolete. It duplicates no durable authority. | **KEEP only as a derived proc memo; change its source to generated eval entries.** It may vanish on graph rebuild and be regenerated from facts. |
| Exact `:seon.context.capture/prompt` | Capture policy explicitly records the exact external AI input before provider call (`src/seon/context.clj:30-43`, `:154-200`); contribution rows omit duplicate text and retain hash/position/token/block evidence (`:136-152`). Debug reads the capture (`src/seon/render/web.clj:512-584`). | Authority, not memo: program/profile/history can change, so the bytes actually sent cannot later be reconstructed honestly. It is the receipt for an external crossing. | **KEEP.** Continue storing exactly one prompt and component hashes/metadata, not a second copy per contribution. |

## 7. Render provenance: known, then lost

| seam | evidence | loss and behavior | disposition |
|---|---|---|---|
| Selection | `producer` returns the selected symbol, including explicit floor symbols (`src/seon/render.clj:301-320`). | G6 requires this exact decision for `/ai` and `/html`; nothing needs to infer it. | **KEEP and carry forward.** |
| Static/read evidence | `call-static-evidence` receives selected render function and records program/schema dependencies (`src/seon/render.clj:322-340`); `render-call` retains an in-memory call entry with `:seon.render.call/producer` (`:642-697`). Root web acquisition also manually records render function (`src/seon/render/web.clj:841-860`). | Identity is known at both generic and root render seams but survives only in proc-local call evidence. | **MERGE INTO one open render-result/evidence value** shared by AI history and HTML packages; delete the root-specific manual copy. |
| Cost fact | `render-cost-fact` accepts `selected` but records only shape key, profile, estimated tokens, and time (`src/seon/render.clj:357-367`); its schema has exactly those four attributes (`resources/seon/schemas/seon.render.cost.edn:1-13`). AI render calls transact it only when connection/call-id are present (`render.clj:700-710`); read-only HTML deliberately does not transact. This omission entered with the fact in `53448d227`. | This is the precise G6 hole named by the behavior authority (`behavior:600-629`). Adding only a cost-fact field would still leave HTML provenance unavailable. | **DECIDE—three options below.** |
| History/prompt contribution | Generic history stores call id/output/lookup-ref/distance and rendered bytes, but not render function (`src/seon/render/walk.clj:759-790`); prompt contributions hardcode block name `:walk` (`src/seon/cluster/prompt.clj:173-179`). | The chosen symbol is discarded before the AI context is assembled, contradicting G6 even though it was known. | **MERGE INTO the shared render evidence envelope; display the render function from data, never a block-name convention.** |
| Web package | Packages retain fragment ids/strings/revisions (`src/seon/render/web.clj:668-743`) but not the render function selected for each block. | B10/G6 require `data-rendered-by` (or equivalent) on the same entry. Re-deriving after package creation can select differently after hot reload. | **MERGE render function into the block/package value before delivery.** Transport need not persist a second fact; it must not discard the decision. |

**Owner decision: where does provenance become durable and available to both
projections?**

1. **Recommended — return one open render-evidence envelope from the existing
   render-call owner.** It carries render function, output projection, call id, value
   identity/basis, and rendered value. Generated eval history stores the
   render function with the entry; HTML block/package data carries the same field;
   existing cost facts accrete render function/output when a write-owning receipt seam
   already transacts. **Guarantee:** the selected function, including the floor,
   is recorded at the authority's decision and both projections display the
   same fact. **Cost:** medium cross-owner contract change through
   `seon.render`, walk/generation, and web package construction. **Give up:** the
   current convenience of render calls returning a bare string/Hiccup value.
2. **Transact a render-cost/provenance fact for every AI and HTML render.**
   **Guarantee:** one queryable durable ledger for both. **Cost:** high—read-only
   web requests become writes, rendering can trigger itself through database
   listeners, and repeated page views create unbounded facts unless another
   retention mechanism is invented. **Give up:** read-only rendering and simple
   event causality.
3. **Derive render function later from retained value/profile/program basis.**
   **Guarantee:** none unless every input and exact program basis is retained and
   selection is replayed. **Cost:** deceptively low edits, high storage and
   replay complexity; hot reload can change the answer. **Give up:** the owner
   law that the deciding authority records its decision. This option should be
   rejected.

## 8. Other weak or parallel mechanisms found

| mechanism | evidence and callers | behavior | disposition |
|---|---|---|---|
| Dynamically bound walk context vs explicit render request | Most rendering receives database, projection, caps, profile, and viewing namespace in the request, but `seon.render/walk` also consults dynamic `*walk-context*` and otherwise fetches a current connection (`src/seon/render.clj:808-848`). Agent code can call it directly; render acquisition binds the dynamic context. | Violates “values carry their world” and weakens B11 replayability: the same form can silently read a different cluster/current connection. | **MERGE INTO SCI call preparation/supplied defaults.** Declare absent walk inputs and supply them from the scoped environment; caller wins. Delete the dynamic var/fetch path. |
| Two result-EDN readers | `seon.eval.drive/read-result` reads settled result EDN for model grading (`src/seon/eval/drive.clj:127-139`); `seon.render.transcript/read-result` separately reads the same family for narration (`src/seon/render/transcript.clj:537-558`). | Two consumers independently decide unreadable-result behavior; transcript disappears under B7, leaving one need. | **DELETE the transcript reader with transcript projection; KEEP the grading reader or MERGE both into the eval-result authority if another consumer remains.** Stored result EDN is data, so `clojure.edn` is correct here; this is distinct from the bootstrap source-reader defect. |
| Tuned constants standing in for events | Transcript tail 6 (`src/seon/render/transcript.clj:27-31`), namespace schema closure 40 (`src/seon/render/ns.clj:95-100`), prompt default distance 2 (`src/seon/cluster/prompt.clj:43-44`), and value default page width 8 (`config/default.edn:88-93`) are separate. | Distance and page width can be legitimate declared policies; tail and closure caps hide “why omitted” without canonical elision. | **DELETE transcript tail and private namespace closure cap; KEEP distance/page width as named profile/config facts only when omission is explicit and requeryable.** No bare constant decides health or context membership. |

## If we could delete only three things

Delete **(1) `seon.render.transcript` as a history/context projection**, after
its episode-grading and HTML consumers move to the generated eval stream; this
removes 1,009 lines plus the second database query family, fixed six-entry tail,
summary ladder, result reader, and history budget, at the medium cost of moving
those two consumers. Delete **(2) prompt distance shrink and every private
presentation ladder (`cluster.prompt`, `render.ns`, transcript, conditional
MCP fit)** after `seon.print/fit` grows the ruled smart strategy; this has the
highest behavioral payoff—one honest omission contract—but costs coordinated
fixture updates across prompt, namespace, MCP, and render tests. Delete **(3)
bootstrap's candidate/supervision assembler after G's generated-eval walk can
produce B1**; this is the largest semantic simplification because arrival and
later turns become one mechanism, but it is also the highest-risk deletion and
must wait until a fresh isolated cluster proves B1 trigger-last, teaching-before-
use, and restart continuation. These three remove mechanisms, not authorities:
`seon.db/diff`, `seon.print/fit`, schema-derived acquisition, durable eval
results, exact prompt captures, admission caps, and web keyframe/delta delivery
all survive.

## Implementation ordering implied by the register

1. Accrete render function/output provenance to the render decision and carry it in one
   open evidence envelope (owner option 1).
2. Make `seon.print/fit` the complete B4 floor, then remove the four competing
   presentation ladders without changing admission or storage spill.
3. Build G's generated form/value entry stream using the surviving schema root
   selector and `seon.db/diff` at recorded bases.
4. Point web retention, episode grading, HTML pages, and prospective debug at
   that stream; then delete transcript history and walk generic history.
5. Replace bootstrap candidate/supervision with the same generator; prove B1 on
   a fresh isolated cluster before deletion.
6. Merge SCI documentation and namespace projection into polymorphic `doc` and
   `dir`; delete the `my.run` face and other narration faces family by family.
7. Remove full read-result evidence and editscript only after revision
   currentness and identity-diff coverage prove the absence case loudly.

This order preserves a working authority before each parallel path is removed;
it does not create a `v2` namespace or temporary second registry.
