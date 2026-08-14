---
type: research
status: complete
date: 2026-08-14
tags: [research, render, print, web, context, class/n1, class/n11, wave/one-renderer]
---

# One-renderer gap census — the complete current-tree register

READ-ONLY census. Nothing under `src/`, `resources/`, or `test/` was edited
by this lane. Four implementation lanes are live in the render files while
this was written; this document is their next work order, produced as paper
only.

## Scope, snapshot, and method

Censused against
[one-renderer-prd-2026-08-14.md](../plan/one-renderer-prd-2026-08-14.md)
**§0 (the whole-pipeline scope correction, panic-hard-in-dev) and §1 (the
target architecture)**, read end to end at commits `1b18d0665` → `efde4a65d`
→ `e8e545ca1`. Named inputs read end to end:
[results-as-data-audit](results-as-data-audit-2026-08-14.md),
[context-clipping-census](context-clipping-census-2026-08-14.md),
[transcript-view-design](transcript-view-design-2026-08-14.md),
[ui-verification](ui-verification-2026-08-14.md), and ledger entries 27-32 in
[design-ideas-ledger-2026-08-13](../plan/design-ideas-ledger-2026-08-13.md).

**Snapshot: HEAD `38f18880b`, working tree as of 07:5x.** The tree is moving
under this census — between two tool calls twenty minutes apart the clipping
lane deleted `print/bounded-text`, re-shaped `seon.sci.admit`, added and then
removed `config/default.edn` dials and a `seon.config.note.edn` schema, and
extended from six files to eleven. Every "in flight" mark below is true at
that snapshot and must be re-derived, not trusted, before a wave starts.

Method: every claim is a `file:line` read at the snapshot, or a derived count
from the schema registry. Producer inventory is derived, not hand-listed:

```bash
cat resources/seon/schemas/*.edn | tr '\n' ' ' \
  | grep -oE ":seon\.render/ai +[a-z][a-zA-Z0-9.*+!?<>=_-]*/[a-zA-Z0-9.*+!?<>=_-]+"
```

→ **42 distinct declared `/ai` producers**, 285 of the 358 references being
the one error-family `seon.error/render-ai`. That derivation is the member
list; the table below names the ones with proven or read defects.

## Already landed today — NOT re-censused

Confirmed at the snapshot and excluded from the register:

| Landed | Commit | Confirmed by |
|---|---|---|
| `/data` route hands its schema projection | `55de97d4d` | issue archived |
| CSS content hiding (rip-out #7) | `d294ac876` | issue archived; census tier-3 #13-16 |
| Bounded text routed through print elisions | `67956fa3f` | `seon.print` elision path |
| Walk placeholder / HTML-only distance-cap markers | `8e85ea9dd` | `render/walk.clj` |
| SCI map pairs render without `Map.Entry` casts | `a5d071f94` | `seon/error.clj` |
| The lint judge itself | `746a87d51` | `src/seon/render/lint.clj` |
| Namespace referenced-schemas by identity | `38f18880b` | `render/ns.clj` |

## Part 1 — per-stage contract coverage (PRD §0)

The eight stages, with what actually checks the boundary today.

| # | Stage | Owner (file) | Boundary contract today | Verdict |
|---|---|---|---|---|
| 1 | production (whole value) | ~42 declared producers + every `my.*` read | Malli `:malli/schema` on public producers, instrumented from the program graph | **partial** — the contract types the SHAPE, nothing asserts the value is whole/unbounded/unnarrated |
| 2 | storage admission | `src/seon/sci/admit.clj:236-244`, `print.cljc:841` (`admit-string`, in flight) | declared caps as config facts; `::truncated-string` → counted elision at `print.cljc:735-800` | **sound in mechanism**, see #B1 for the honesty gap |
| 3 | derivation (history / walk membership) | `render/walk.clj:511` `neighborhood`, `:893` `history`, `render/transcript.clj` | `neighborhood` has a schema; several walk publics do not (6 of 12 in `walk.clj`) | **weak** — the derivation's output is not contract-checked before projection |
| 4 | projection selection | `render.clj:178-320` | `candidates` + `producer` are schema'd; ambiguity is a typed error | **the strongest stage** — see §4 audit |
| 5 | producer or floor | `render.clj:369-400` `invoke-selected`; floor `render/value.clj:626,633` | `valid-projection?` (`render.clj:402-406`) checks the producer's output against the projection's output schema — **then silently discards the result on failure** (`:468-474`) | **checked and swallowed** — the single largest §0 gap |
| 6 | fit | `print.cljc:909` `fit`, `:854` `fit-text` | `fit` is schema'd; profile derived per call when absent | **sound in shape, unsound in policy** (#F1, #F2) |
| 7 | face assembly | `render.clj:517` `fit-terminal`; `render/web.clj:259` `surface-html`; `cluster/prompt.clj` | `render-ai`/`render-html` type the terminal output; `surface-html` is schema'd | **partial** — nothing checks the assembled page or prompt against a contract before delivery |
| 8 | delivery | `render/hiccup.clj:479` `->string`; the SSE feed at `web.clj:1643` | `->string` is the only schema'd public in `hiccup.clj` (1 of 6) | **weakest** — a non-hiccup value reaching the serializer is the escaped-EDN defect (#G2) |

**Contract density, measured** (public `defn` count vs. those carrying
`:malli/schema`): `render.clj` 13/9, `print.cljc` 15/13,
`render/value.clj` 15/14, `render/transcript.clj` 10/9,
`render/walk.clj` 12/6, `render/web.clj` 14/3, `render/hiccup.clj` 6/1.
The pipeline is well-typed where values are small and untyped exactly where
they become pages and bytes — stages 7 and 8.

## Part 2 — the register

Columns: **§** = the PRD property violated (§0 stage number, or §1 property);
**named by** = which prior audit named it, or NEW; **disposition** = §2's
rip-out row where one exists; **blast** = estimated files touched / test
namespaces affected; **flight** = in-flight status at the snapshot.

### A. Stage 1 — production: producers that narrate or bound

| # | Seam (`file:line`) | § | Named by | Disposition | Blast | Flight |
|---|---|---|---|---|---|---|
| A1 | `src/seon/render.clj:437-496` `project-node*` substitutes a declared `/ai` producer for the VALUE at every depth | §1 results-are-data | results-as-data seam 1 | §2 #1 DELETE | 1 src / 4-6 tests (`render_test`, `walk_test`, `transcript_test`, `print_test`) | not started |
| A2 | `src/seon/cluster/run.clj:1869` `render-ai` | §1 results-are-data | audit 1a | §2 #3 REPLACE with attribute face | 1 src + 1 schema / 2 | not started |
| A3 | `src/seon/cluster/run.clj:1977` `render-form-ai`, `:1993` `render-receipt-ai` | §1 results-are-data | audit 1b + `run-renderer-narrates-forms-and-receipts` | §2 #3 | (same as A2) | not started |
| A4 | `src/seon/problems.clj:432` `stale-var-ai`, `:448` `missing-model-ai`, `:363` prose pair | §1 results-are-data | audit 1c | §2 #3 — the `:seon.fn` row, or a typed error VALUE | 1 src + 1 schema / 1 | not started |
| A5 | `src/seon/cluster/message.clj:437` `render-ai` | §1 results-are-data | audit 1d | §2 #3 | 1 src + 1 schema / 2 | not started |
| A6 | `src/seon/error.clj:1044` `render-ai` + the 9 prose variants (`ai-prose`, `index-refusal-prose`, `mcp-prose`, `edit-prose`, `unclassified-prose`, `time-limit-prose`, `refusal-prose`, `instrumentation-prose`, `elision-prose`) — 325 of 358 declarations | §1 declared-producers-for-special-surfaces | audit 1e | KEEP as the error card family (PRD `e8e545ca1` makes this the seat of the prod error face) — but the result-position substitution (A1) must stop applying it to pulled error ROWS | 1 src / 3 | not started |
| A7 | `src/seon/cluster.clj:152` `render-ai` + the config family `/ai` | §1 results-are-data | audit 1f | §2 #3 | 2 src + 2 schema / 2 | not started |
| A8 | The remaining narrating producers found by the derivation and read at the snapshot: `seon.effect/render-ai` (`effect.clj:60`), `seon.db/render-transaction-ai` (`db.clj:1911`), `seon.db/render-diff-ai` (`db.clj:1642`), `seon.ai/model-ai` (`ai.clj:194`), `seon.ai/provider-ai` (`ai.clj:261`), `seon.maintenance/render-report-ai` (`maintenance.clj:410`), `my.run/render-namespace-ai` (`my/run.clj:17`), `my.plan/render-item-ai` (`my/plan.clj:881`), `my.note/render-note-ai` (`my/note.clj:54`), `my.background/render-ai` (`my/background.clj:10`), `seon.test.accretion/render-ai` (`accretion.clj:311`), `seon.cluster.agent/render-situation-ai` (`agent.clj:122`) | §1 results-are-data | **NEW** — the audit named 8 families from one capture; the registry derivation finds 12 more that narrate | §2 #3, same class. Owner question §6.1 decides whether each keeps a tiny ORDERING face | 12 src + 12 schema / ~10 | not started |
| A9 | `src/seon/cluster/instruction.clj:73` `instruction-ai` | §1 prose-on-purpose | audit seam 5 | KEEP — this becomes the ONE-LINE predicate ("is this an instruction entity") after A1. Needs a marker so it is distinguishable in result position | 1 src / 1 | working tree modified |
| A10 | `src/seon/print.cljc:283` `render-elision-ai` declared at `seon.print.edn:250` as the elision's `/ai` | §1 one-elision-representation | audit seam 4 | §2 #5 UNIFY — delete the English tail; the elision VALUE is the representation | 1 src + 1 schema + 2 callers (`my/plan.clj:948,985`) / 2 | see F3 |
| A11 | `src/seon/db.clj:1666` — second spelling of the same elision sentence | §1 one-elision-representation | audit seam 4 | §2 #5 | 1 src / 1 | not started |

### B. Stage 2 — storage admission

| # | Seam | § | Named by | Disposition | Blast | Flight |
|---|---|---|---|---|---|---|
| B1 | `src/seon/sci/admit.clj:236-244` → `print.cljc:841` `admit-string` | §0 stage 2 | clipping census compliant #6 | KEEP — this is seam 2 and it is the correct shape. The in-flight refactor (`::bound-by` carried into the elision) is an improvement; assert it stays the ONLY admission cap | — | in flight (clipping lane) |
| B2 | `src/seon/sci/reader.cljc` private 1 MiB source cap | §0 stage 2 honesty | clipping census #12 + filed issue | Declare it as a config fact under the seam-2 family | 1 src + 1 schema / 1 | filed, not started |

### C. Stage 3 — derivation

| # | Seam | § | Named by | Disposition | Blast | Flight |
|---|---|---|---|---|---|---|
| C1 | `:seon.context.contribution/characters` does not exist; `cluster/prompt.clj:151-180` computes and drops the running length | §1 one-derivation-three-faces | transcript-view-design (b) | ACCRETE the one optional key; segmentation derives with the stored SHA as its check | 1 src + 1 schema / 2 | working tree modified (`prompt.clj`) |
| C2 | `src/seon/render/ns.clj:105-111` `read-edn`, `:113-127` `schema-form-refs` — `catch Throwable → nil` | §0 honest at every stage | **NEW** | Typed refusal, not nil. A malformed stored schema form becomes "no references" today | 1 src / 1 | ns.clj committed `38f18880b`; these catches survive |
| C3 | `src/seon/render/data.clj:35` `(try (edn/read-string …) (catch Throwable _ nil))` | §0 honest | **NEW** | Typed refusal for an unreadable path parameter | 1 src / 1 | not started |
| C4 | `src/seon/render/walk.clj` — 6 of 12 publics carry no `:malli/schema` | §0 stage 3 contract | **NEW** | Contract the derivation's outputs so stage 4 cannot be handed an unchecked value | 1 src / 2 | not started |
| C5 | `docs/seon/issues/render-walk-maintains-a-derived-edge-hand-list.md` | §2.2 derive-or-die | filed | Derive the edge list | 1 src / 1 | filed |

### D. Stage 4 — projection selection

| # | Seam | § | Named by | Disposition | Blast | Flight |
|---|---|---|---|---|---|---|
| D1 | `src/seon/render.clj:218-222` `explicit-producer` reads `(get value output)` — any value map carrying a `:seon.render/ai` KEY has that value invoked as a producer symbol | §0 unconstructable | **NEW — needs one probe** | If a pulled registry/schema row can carry `:seon.render/ai` as data, this is a live confusion of data with declaration. Probe before ruling | 1 src / 1 | not started |
| D2 | `src/seon/render.clj:159-170` `producer-argument` merges the value's own keys into the render unit before `function-accepts-in?` | §0 stage-4 contract | **NEW — behavioural note, not yet a proven defect** | Selection fits against a MERGED map, so a producer declaring one common key can be selected for an unrelated value; the `schema-producer` specificity filter (`:253-262`) only mitigates pulled entities | 1 src / 1 | not started |

### E. Stage 5 — producer or floor

| # | Seam | § | Named by | Disposition | Blast | Flight |
|---|---|---|---|---|---|---|
| E1 | `src/seon/render.clj:468-474` — when `valid-projection?` is false, or when the producer returned an error VALUE, `project-node*` returns the unprojected `node` and says nothing | §0 **panic-hard-in-dev**, honest | **NEW — the largest §0 gap** | PANIC at the boundary in dev naming producer + value + contract; in prod emit the one error card. This is where "the wrong shape silently becomes something else" lives | 1 src / 4-6 | not started |
| E2 | `src/seon/render/value.clj:365-372` `attribute-label`, `:374-391` `map-components`, `:393-398` `components-text` — the floor's second, non-EDN map face (`nominal-at: …`, `:db/id:`, no braces) | §1 one readable EDN face | audit seam 3 | §2 #4 DELETE the second face | 1 src / 2-3 | not started |
| E3 | `src/seon/render/value.clj:194-203` `admitted-projection` pins `:seon.config/on-core-error :record` | §0 panic policy | **NEW** | The floor can never panic in dev regardless of the dial. Thread the request's dial | 1 src / 1 | not started |
| E4 | `src/seon/render/value.clj:130-133` `counted-size` swallows to nil; `:165-175` `window` catch → typed error | §0 honest | **NEW (first half)** | `counted-size` nil is indistinguishable from "not counted"; `window`'s catch is honest and stays | 1 src / 1 | not started |
| E5 | 12 producer-side bounding sites (clipping census tier 1-2 #1-#12) | §1 values-flow-whole (ledger 32) | clipping census | §2 #6 DELETE the bounds | 11 src / ~9 | **IN FLIGHT** — `my/note.clj`, `my/message.clj`, `render/agent.clj`, `render/ns.clj` done in tree; `effect.clj`, `sci/eval.clj`, `ai.clj`, `cluster.clj`, `edit.clj`, `flow.clj`, `test/runner.clj` moving |
| E6 | The in-flight lane's own first shape routed producer sites through a public `print/bounded-text` and converted the elision to English with `render-elision-ai` — i.e. producer-side bounding plus seam-4 narration, the exact two things ledger 32 and audit seam 4 forbid. `bounded-text` has since been deleted from `print.cljc` and replaced by the private `bounded-text` (`:834`) + public `admit-string` (`:841`) | §1 two-seams | **NEW (this census)** | The correction is already underway; the register row exists so the wave asserts it rather than assuming it. The unconstructability lock (§5.1) is what makes it stick | — | in flight, mid-correction |

### F. Stage 6 — fit

| # | Seam | § | Named by | Disposition | Blast | Flight |
|---|---|---|---|---|---|---|
| F1 | `src/seon/print.cljc:854` `fit-text` cuts with `subs` at a character count — no form or fence awareness | §1 form-aware fit | §2 #8 | FIX inside the owner: whole-form elision units; a mid-form or mid-fence cut unconstructable | 1 src / 2 | owner rewritten in flight, **the form-awareness itself not started**; §3 archaeology-gated |
| F2 | `src/seon/render.clj:68-103` `request-profile` derives the profile from the database when the caller omits it; `:65-66` `default-agent-profile` is a namespace-load global substituted when derivation yields nothing | §2.1 / ledger 28 p1 class kill | ledger 28 (names this exact site) | DELETE the fallback; a missing profile is a typed refusal naming the caller | 1 src / 3-5 (every render fixture that omits the profile) | ledger-28 lane in flight elsewhere; this site not yet |
| F3 | `docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md` — `::token-budget` in `render/transcript.clj:814` defaults to `0` (renders nothing but its marker); `render/ns.clj` defaults to nil (unbounded) | §1 bounding-at-two-seams | clipping census, adjacent | Make the budget a config fact supplied by the request | 2 src + 1 schema / 2 | filed |

### G. Stage 7 — face assembly

| # | Seam | § | Named by | Disposition | Blast | Flight |
|---|---|---|---|---|---|---|
| G1 | `src/seon/render.clj:790-802` `renderer-failure` returns `"Renderer unavailable."` / the placeholder div; emitted at `render/walk.clj:586-590`, `render/web.clj:279-281`, `:579-581`, `:622-623` | §0 **BANNED dev output** | ui-verification (67 and 69 occurrences measured), `namespace-page-repeats-renderer-unavailable`, lint `:renderer-unavailable` | REPLACE: dev panics; prod renders the one error card. The typed error is ALREADY retained on the unit (`:seon.error/value`) — only the FACE throws it away | 3 src + 1 css / 3-4 | not started |
| G2 | `docs/seon/issues/walk-units-render-their-hiccup-as-escaped-edn-text.md` — 29 of 138 units on `/` paint their own Hiccup as an escaped EDN string inside `span.seon-print-elision` | §1 one pipeline, §0 stage 7/8 | ui-verification | An `/html` projection reached the VALUE printer instead of the DOM emitter — the stage-7/8 boundary that checks nothing | 2 src / 2 | filed, blocker |
| G3 | Three independent block-identity derivations: `render/block.clj:61` `surface-id` (shell, transcript, fleet, ns), `render/value.clj:46-67` `node-id` → `seon-value-<sha24>` (every walk unit, via `web.clj:244-250`), and the debug pane's own `debug-ai-<agent>` section ids | §1 **same block identities everywhere** | **NEW** | Unify, or declare the mapping. The PRD's "same block identities everywhere, so live morphs serve every face" cannot hold across chat/debug/walk with three schemes | 3 src / 3 | not started |
| G4 | `node-id` hashes `[agent root path]` where a walk path is positional (`[:seon.render.walk/neighbours 31]`) | §1 block identity / morph economy | **NEW — hypothesis, needs one probe** | If a neighbour is inserted, every downstream unit's id changes and every morph target churns. Evidence: the `data-walk-path` attribute in the G2 issue's captured HTML. Probe before ruling | 1 src / 1 | not started |
| G5 | `docs/seon/issues/debug-pages-receive-block-patches-for-elements-they-do-not-have.md` | §1 block identity | filed | Same class as G3 | 1 src / 1 | filed |
| G6 | `render/web.clj:589-595` — the `:seon.debug-context-status` span sits INSIDE the verbatim `[:section]`, so a text extraction returns `"captured" + prompt` | §1 honesty-is-tested | transcript-view-design (a) | Move the status out; give the capture element a derived stable id | 1 src / 1 | working tree modified (session-view lane) |
| G7 | `docs/seon/issues/the-debug-ai-pane-never-wraps.md` — widest `<pre>` line measured 23,552 px in a 615 px container | §1 debug face | ui-verification | `white-space: pre-wrap` (presentation only, survives the falsifier) | 1 css / 1 | filed |
| G8 | `render.clj:839-843` + `:979-981` — `walk-error` returns a `pr-str`'d error MAP as the walk's `/ai` string; the top-level `catch Throwable` converts any pipeline failure into that string | §0 honest, §1 errors-are-values | **NEW** | The value is honest but it is stringified at the wrong stage and the blanket catch is the swallow that makes stage failures indistinguishable | 1 src / 2 | not started |

### H. Stage 8 — delivery

| # | Seam | § | Named by | Disposition | Blast | Flight |
|---|---|---|---|---|---|---|
| H1 | `src/seon/render/hiccup.clj` — 1 of 6 publics carries a contract; `->string` (`:479`) accepts whatever it is handed | §0 stage 8 | **NEW** | Contract the serializer's input as `:seon.render/hiccup`; a non-hiccup value must panic in dev (this is G2's root) | 1 src / 1 | not started |
| H2 | `docs/seon/issues/render-package-proc-reruns-unchanged-renderers.md` — `web.clj:329-378` derives the complete walk before comparing retained evidence | §2 #10 cache invalidation | PRD §2 #10 | Invalidate on producer change; suppress renderer execution, not just serialization | 1 src / 1 | filed |
| H3 | `docs/seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md`; `agent-pages-overflow-a-phone-viewport.md` | §0 delivery | ui-verification | Fold into the delivery wave | 2 src + css / 2 | filed |

### I. Cross-stage — silent swallows and the panic-policy gap

Every site where the pipeline reports health in the absence of signal. This
is the project's named recurring failure class, and §0 makes it the policy
target.

| # | Swallow site | What absence reads as | Dev panic today? |
|---|---|---|---|
| I1 | `render.clj:468-474` producer output fails its contract → unprojected node | "the floor rendered it" | **no** |
| I2 | `render.clj:469-470` producer returned an error value → unprojected node | "no producer was declared" | **no** |
| I3 | `render.clj:790-802` + 4 emission sites | "the renderer is unavailable" (67 times on one page) | **no** — and the div is BANNED by §0 |
| I4 | `render/value.clj:194-203` floor pins `on-core-error :record` | "the value had no faults" | **no, structurally** |
| I5 | `render/ns.clj:105-111`, `:113-127` catch → nil | "this schema references nothing" | **no** |
| I6 | `render/data.clj:35` catch → nil | "no path was requested" | **no** |
| I7 | `render.clj:979-981` blanket walk catch → error string | "the walk produced this text" | **no** |
| I8 | `render/value.clj:130-133` `counted-size` catch → nil | "not counted" | **no** |
| I9 | `render/web.clj:1917`, `:1934` catch → nil (`route-namespace`, digest read) | "no such route / no such value" | **no** |
| I10 | `render/web.clj:806` prospective-context catch | honest — emits `:unavailable` with a typed diagnostic | n/a, **keep as the model** |
| I11 | `render/transcript.clj:541-547` `read-result` catch → `::unreadable?` | honest — typed | n/a, **keep as the model** |

**Where R41's dial does not reach the render path.** The dial
(`:seon.config/on-core-error`) is threaded correctly into `sci.kernel/invoke`
from `render.clj:369-400`, so a producer BODY throwing respects it. It does
not reach: (a) the floor, which hardcodes `:record` (I4); (b) any of the
contract checks — `valid-projection?`, `hiccup?`, `render-ai`'s
`invalid-ai-output`, `render-html`'s `invalid-html-output` — none of which
consult the dial at all; (c) stages 7 and 8, which have no dial-aware seam.
So the §0 policy is currently **unimplemented for every contract violation**
and implemented only for thrown exceptions inside producer bodies. That is
one concentrated piece of work (a dial-aware `stage-refusal` helper called at
each of the eight boundaries), not eleven scattered ones.

## Part 3 — §4 "what stays exactly as is": verified, with three corrections

§4 lists: candidates selection, the declared-producer mechanism, the
elision-value schema, `seon.ai.tokens/estimate`, the render profile, block
identity and Datastar morphs, the capture facts.

| §4 item | Verdict | Evidence |
|---|---|---|
| `seon.render` candidates selection | **SOUND, keep.** Ordered chain, deterministic sort so insertion order cannot decide, ambiguity is a typed error, self-re-entrance made unconstructable by carrying `:seon.render/rendering` | `render.clj:178-320`, `:437-460` |
| the declared-producer mechanism | **SOUND as a mechanism**; what changes is only WHERE it is consulted (never in result position) | `render.clj:293-320` |
| the elision-value schema | **SOUND, keep** — count, path, offset, total, requery identity all present | `seon.print.edn:232-271` |
| `seon.ai.tokens/estimate` as the size unit | **SOUND, keep** | `print.cljc:909-943` |
| the capture facts as the honesty baseline | **SOUND, keep** — proven to 3 tokens in Drive 1 | transcript-view-design, ground truth |
| the render profile as the database-derived fit policy | **CORRECTION 1** | see below |
| block identity and Datastar morphs | **CORRECTION 2** | see below |
| (implicit in "the elision-value schema") | **CORRECTION 3** | see below |

**PRD correction 1 — the profile POLICY stays; its FALLBACK is a §2.1
defect.** `render.clj:68-103` re-derives the profile from the database when
the caller omits it, and `:65-66` substitutes a namespace-load global built
from `config/defaults` when derivation yields nothing — a cluster silently
rendering at another cluster's budget. Ledger 28 names this exact site as the
first execution of the p1 class kill. §4 should read: *the render profile as
the database-derived fit policy, supplied by the caller; the derivation
fallback and `default-agent-profile` are deleted with ledger 28.*

**PRD correction 2 — block identity is not one mechanism today.** §1 promises
"same block identities everywhere, so live morphs serve every face", and §4
lists block identity as unchanged. There are three derivations at the
snapshot: `block/surface-id` (injective, documented, correct — `block.clj:61-96`),
`value/node-id` → `seon-value-<sha24>` for every walk unit
(`value.clj:46-67`, called from `web.clj:244-250`), and the debug pane's own
section ids. The chat face (ledger 30) and debug face (ledger 31) are
specified to share block identities with each other and with the walk; that
is not constructible over three schemes. §4 should carry the unification as
work, not as a stay-as-is. G4 adds a probe question: `node-id` hashes a
POSITIONAL walk path, so an inserted neighbour may renumber every downstream
id — unproven, cheap to probe, and it decides whether unification is a rename
or a redesign.

**PRD correction 3 — the elision SCHEMA stays, its declared `/ai` does
not.** `seon.print.edn:250` declares `:seon.render/ai seon.print/render-elision-ai`
on the elision value itself. That declaration IS audit seam 4 — the English
tail. §2 #5 ("unify on the elision value") and §4 ("the elision-value schema
stays") are consistent only if §4 is read as *the schema, not its narration*.
Worth one clarifying clause so a lane does not preserve the producer while
deleting its callers.

## Part 4 — the dependency-ordered wave plan

Each wave states what must precede it, its unconstructability lock (PRD §5),
and whether the archaeology lane (§7a, the old implementation's
render/print/transcript system) gates it.

**Wave 0 — settle the in-flight tree (blocks everything).** The clipping
lane's eleven files must land in their ledger-32 shape (bounds DELETED, no
producer calling any bounding primitive, no `render-elision-ai` at a producer
site) before any wave edits the same files. Gate: `bin/test` on the affected
namespaces plus a re-derived working-tree snapshot. *Archaeology: no.*

**Wave 1 — the stage-contract spine (E1, I1-I9, H1, E3, G8).** One
dial-aware refusal seam called at all eight boundaries; dev panics naming
producer + value + contract; prod emits the one error card
(`seon.error` family, per `e8e545ca1`). Everything else in this census
reports through it, which is why it is first and not last. **Precedes** every
face wave, because the `renderer unavailable` rip-out (G1) has nothing to
replace itself with until the error card exists. Lock: §5.4 lint clean, plus
a new property — no render-path function may return a value failing its
declared output contract without the refusal seam firing.
*Archaeology: no — this is new policy, not a revival.*

**Wave 2 — results are data (A1-A8, A10-A11, E2).** Delete the result-position
substitution, delete the floor's second map face, unify the elision. Requires
wave 1 (a producer whose face is deleted must fail loudly, not silently fall
back). Requires the owner's §6.1 answer (do the narrated shapes keep tiny
ORDERING faces?) before A8's twelve producers are touched — that answer sizes
this wave between 8 and 20 files. Lock: §5.3, the results-as-data fraction as
a suite property (baseline 30.5%, target 100% minus declared instruction
entities). *Archaeology: no.*

**Wave 3 — the fit owner learns forms (F1, F2, F3).** Form-aware whole-form
elision inside `print.cljc`; delete the profile fallback; make the token
budget a supplied fact. **GATED ON ARCHAEOLOGY (§7a)** — the PRD's quarry-first
rule: the first implementation rendered REPL transcripts for months and its
fit/pprint/truncation code must be read before this is written. Requires wave
2 only where it touches the same `print.cljc` regions. Lock: §5.1 (the two
seams are the only bounding callers, by graph query) and §5.2 (adversarial
budget regression: no cut inside a form or fence).
*Archaeology: **YES**, hard gate.*

**Wave 4 — the faces (G1, G6, G7, C1, and the chat/debug projections).**
The `renderer unavailable` rip-out lands here because it needs wave 1's error
card. The debug face (ledger 31, pretty `/ai` with character-content
equality) and the chat face (ledger 30, per-block `/html` with inline-expanding
chips) share the pretty-data renderer and the tokenizer.
**GATED ON ARCHAEOLOGY (§7a)** for the tokenizer/pretty-printer and the old
agent-view/transcript UI. Requires wave 3 (both faces are fitted output).
Lock: §5.5 debug character-equality + chat chip completeness; the tokenizer
property `text = text-content ∘ highlight`. *Archaeology: **YES**.*

**Wave 5 — identity and delivery (G3, G4, G2, H2, H3, G5).** Unify the three
block-identity derivations, fix Hiccup-as-escaped-EDN at its stage-8 root,
invalidate packages on producer change. Requires wave 4, because the faces
decide which identities must be shared. G4's probe should run in wave 0 —
it is one command and it decides this wave's size. Lock: §5.4 lint as a
standing regression over the standard pages with subject-present enforcement.
*Archaeology: partial — the quarry's one-element morph is already mined in
`block.clj`'s docstring; the id scheme is not.*

**Wave 6 — derivation hygiene (C2-C5, B2).** The typed-refusal sweep through
the derivation stage and the reader's private cap. Independent of waves 2-5;
schedule it wherever a lane is free. *Archaeology: no.*

One line: **0 settle → 1 stage contracts → 2 results-are-data → 3 fit
(archaeology-gated) → 4 faces (archaeology-gated) → 5 identity+delivery, with
6 floating.**

## Totals

| Disposition | Seams |
|---|---|
| DELETE (rip-out) | 17 (A1-A5, A7-A8 as one class of 12 producers counted once each where read, A10, A11, E2, E5, F2, G1) |
| FIX in the owner | 9 (F1, F3, E1, E3, H1, G2, G6, G8, H2) |
| ACCRETE / declare | 5 (C1, B2, C4, G3, I-seam refusal) |
| KEEP, asserted by a lock | 6 (B1, A6, A9, and the four sound §4 items) |
| PROBE before ruling | 2 (D1, G4) |
| FILED, folded into a wave | 8 (C5, F3, G5, G7, H2, H3, and the two ui-verification performance notes) |

**Named by:** 21 seams were named by the three prior audits; **17 are NEW in
this census** (A8's twelve extra narrating producers counted as one row, C2,
C3, C4, D1, D2, E1, E3, E4, G3, G4, G8, H1, I-series).

**The three biggest-blast items:** (1) **E1 + the I-series panic-policy gap** —
one seam, but every stage boundary and every render test's expectations move
with it; (2) **A1 `project-node*` plus the ~20 narrating producers** — 12-20
source files, 12-20 schema files, ~10 test namespaces, and it is the single
change that takes the agent's result positions from 30.5% data to ~100%;
(3) **G3 block-identity unification** — three derivations across `block.clj`,
`value.clj`, and `web.clj`, and it gates the chat and debug faces both.
