---
type: prd
status: draft
tags: [prd, agent, context, architecture]
---

# The One Renderer

*Draft for the owner's markup, 2026-08-14. Sources: the day's four
audits, the archaeology of the first implementation, and the 47-row gap
census — linked in §9. Vocabulary note: "producer" is retired (it was
already a legacy spelling); this document says **function output** (what
any function returns) and **render output** (a declared cleanup face for
a shape — inline data or a function symbol).*

## 0. The problem and the scope

Every rendering defect found this cycle — silent truncation, narrated
results, placeholder swallows, split code fences, prompt bloat, stale
cached blocks, thousand-line garbage pages — is one disease: **there is
no single well-thought-out pipeline, so every blow-up was papered over
locally instead of fixed at the system.** The scope of this PRD is the
ENTIRE content rendering system for AI and HTML outputs, start to
finish: one coherent pipeline where nothing can go sideways, no data is
ever silently swallowed, development panics hard, and production
degrades to designed faces — never to garbage, never to absence.

```text
function output (whole)
  → storage admission        declared caps, honest elisions   [seam B]
  → derivation               history unit / walk membership, from facts
  → projection selection     :seon.render/ai | /html | /form
  → render output or floor   declared face, or derived data face
  → fit                      THE one budget: profile, form-aware,
                             elision values                   [seam A]
  → face assembly            prompt bytes | page hiccup
  → delivery                 provider wire | SSE morphs
```

Every stage is **total** (never throws into the pipeline), **honest**
(typed error values, never silence), **bounded** (declared budgets),
and **contract-checked at its boundary**. A value that skips a stage is
unconstructable, not discouraged.

## 1. The architecture

**Function outputs flow whole.** No function bounds, truncates,
summarizes, or narrates what it returns.

**Budgeting happens at the floor, never at the function-output level.**
The fit/floor boundary (seam A) is the single place the consumer
profile's budget applies, identically for both projections, emitting
elision values — count, path, requery identity — never bare cuts. The
only other place content legally shrinks is declared storage-admission
caps (seam B, the `seon.sci.admit` config-fact family), which also
emit honest elisions. `bounded-text` is internal machinery of these
two seams; any bounding call inside ordinary code is the defect,
asserted by graph query.

**Render outputs clean up; they never shrink.** A declared
`:seon.render/ai` or `/html` face exists so a shape shows something
designed instead of garbage. It may be declared **inline** (literal
template data in the schema props — the cheap common case) or as a
**function symbol** (the few surfaces that earn one). Most shapes need
neither: the derived floor renders any registered shape as a readable,
identity-first data face, and unregistered values as fitted printing.
Decency never requires a declaration.

**Results are data.** In result position the pipeline renders the
VALUE. Never an `/ai` prose face substituted for it — the audit's root
defect (`seon.render/project-node*`) put narration where data belongs,
destroying up to 98.8% of queried content. Prose belongs to declared
instruction entities alone; after the rip-out, "prose on purpose" =
"is an instruction entity" — a one-line predicate.

**One derivation, three faces.** The agent's **history unit** — the
ordered form+value entries derived at render time from
message/run-form/receipt facts, never stored as a transcript — feeds:

- the model's `/ai` context: a FLAT interleaved event log (no turn
  containers in the model's face — revived first-implementation
  ruling), with reserved result glyphs (`⟹` deliberately not
  comment-shaped: agents were observed fabricating results as `; ⟹`
  comments);
- the CHAT view: the `/html` projection per block; an entry with no
  real html face becomes a compact chip (value kind, size, basis) that
  EXPANDS INLINE to the full pretty-printed, highlighted data — same
  renderer as debug, same bytes, never a bounce, never dropped;
- the DEBUG view: always the `/ai` content, formatted — pretty
  spacing, syntax highlighting; character-faithful modulo whitespace
  and color, with a raw-bytes toggle underneath. Turn sections are
  HTML-face chrome derived from receipt facts, checked against the
  stored SHA — an unaligned hash renders one unsegmented block with a
  visible note, never a guessed boundary.

Same block identities across every face, so live morphs serve all of
them.

## 2. The failure policy — three faces, one fact, zero new machinery

**Development panics hard.** Any render-path contract violation — a
render output failing its declared shape, a stage handed a value it
cannot face, a budget applied outside seam A, a fence split — PANICS
at the stage boundary naming the function, the value, and the
contract. No degraded output exists in dev: the `renderer unavailable`
placeholder is BANNED (a swallow wearing a label), no partial pages,
no silently smaller results.

**Production never crashes.** The same violation becomes one ordinary
`:seon.error` FACT through the existing evidence-complete diagnostic
constructor — then renders like every other value through the pipeline
itself:

- the HUMAN face: one polite, concise, designed card (the `seon.error`
  card family): a plain sentence of what failed, the identity to
  requery or report, consistent styling, deduplicated on repeat;
- the AGENT face: the flat error value in its context — data it can
  query, diagnose, and act on. Agents never crash and can always fix
  their own fuckups: the error names the failing function; when it is
  agent-authored the agent redefines it and green-to-install gates the
  fix. The self-repair loop closes with zero new machinery.

The R41 dial (`:seon.config/on-core-error`) selects the half — inverted
from the archive's mistake: **panic-on is the development default**
(the old dial defaulted off: absence-as-health inside the guard
itself), and no-silent-swallowing is a GRAPH QUERY over catch sites,
never a convention (eleven old catch sites bypassed the dial).
`missing-render` is the model for the banned placeholder: name the
unresolved symbol so defining it self-heals the block on the next
render.

## 3. The rip-out register

Refs are repo paths at today's HEAD unless marked; ✓ = re-verified at
the bytes by the orchestrator, not relayed from a lane.

| # | Mechanism today | Where | Disposition |
|---|---|---|---|
| 1 | `project-node*` substituting `/ai` faces in result position (audit: 30.5% of result positions data, 66.7% prose; an entity pull reached the agent as a 79-char sentence — 98.8% of queried data destroyed) | `src/seon/render.clj:445-495` | DELETE — values render as data |
| 2 | `seon.print` sink emitting raw `/ai` fragments below root | `src/seon/print.cljc:107-112` | DELETE |
| 3 | Narrating faces — census: 42 declared `/ai` faces, 20 narrate; the audit's named seams: run `src/seon/cluster/run.clj:1913-1966` (pull → sentence), stale-var `src/seon/problems.clj:434-438` (pull → reboot instruction), message `src/seon/cluster/message.clj:460-471`, error `src/seon/error.clj:604-627`, cluster + config twin `src/seon/cluster.clj:155-168`; the remaining 12+ are enumerated in the census register | audit + census docs, §9 | REPLACE with attribute faces or inline render outputs |
| 4 | The floor's second map face (`:seon.schedule.fire/nominal-at` reaches the agent as unqueryable `nominal-at:`) | `src/seon/render/value.clj:365-372, 393-398` | DELETE — one readable EDN face, namespaces intact |
| 5 | Two elision representations + `render-elision-ai` English narration | `src/seon/print.cljc:283-301`, `src/seon/db.clj:1666` | UNIFY on the elision value |
| 6 | Function-side bounding, census tier 1-2 (12 sites) — worst: notes past #50 vanish uncounted (`src/my/note.clj:266` with private `notes-limit 50`; the compliant contrast is `src/my/plan.clj:810-818` in the same directory); agent print output cut unmarked (`src/seon/sci/eval.clj:299-308`); `[clipped]` token inventor that also rewrites real `…` to `...` (`src/seon/render/ns.clj:234-240`) | census doc, §9, per-site table | DELETE the bounds; values flow whole to seam A/B (in flight, corrected to boundary-only shape) |
| 7 | CSS content hiding — 4 rules, worst `max-height:10rem; overflow:hidden` clipping 55/138 units, ~80% of a unit unreachable | was `resources/public/css/input.css:1153-1158` + 3 siblings | DONE (`d294ac876`) ✓ archived |
| 8 | `subs` by character limit inside the fit owner (`::face ::truncated-string`), slicing serialized hiccup so fences/forms cut mid-content — this mangled the live agent's teaching demo | `src/seon/print.cljc:835, 850` ✓ | FIX inside the owner — form-aware fit (in flight, clip-ripout) |
| 9 | Hand `/html` declarations demanded for decency | — | DISSOLVED by the derived floor (`0f1374d5c`; 67→12 placeholders landed `8e85ea9dd`, cached-package residue → #10) |
| 10 | Retained packages serving replaced render functions (13 stale placeholder blocks after a hot fix) | filed: `docs/seon/issues/retained-render-packages-survive-producer-replacement.md` | Chain-hash invalidation (revival, §4) |
| 11 | NO contract check at any of the eight stage boundaries; on a render output failing `valid-projection?` the code RETURNS THE UNPROJECTED NODE silently — no error, no fact | `src/seon/render.clj:468-474` ✓ (`(if (valid-projection? …) … node)`) | BUILD the stage-contract layer (§2) |
| 12 | Block identity derived three ways: `block/surface-id`, `value/node-id` (`seon-value-<sha24>` per walk unit), and the debug pane's own ids | census register row, §9 | UNIFY on one derivation |

## 4. Revivals — the archive already built the hard parts

Quarry root: `git show 9e44815f5:src-old/<path>` — the first
implementation's last content commit before the `099cdfa99` deletion
([full archaeology with per-item excerpts](../research/render-archaeology-2026-08-14.md)).

- **Revive verbatim:** `9e44815f5:src-old/seon/ui/clojure.cljc` ✓
  (exists, exactly 192 lines, ns `seon.ui.clojure` "Highlight Clojure
  source as server-rendered hiccup") — single-pass, total by contract
  (unterminated string degrades to EOF), morph-safe, dependency-free;
  the fresh tree has NO highlighter. Class rename `hljs-*` →
  `seon-print-*`; its degrade-to-plain fallback routes through the
  strict dial instead of swallowing.
- **Revive adapted:** the **sample → emit** two-phase bounding (bound
  the structure, THEN print — nothing oversized is ever cut); the
  Oppen-style `fits?`/`emit` printer; the capped Writer;
  `dominant-string-entry` (shape-general 70%-payload rule); per-block
  **output-byte chain hashes** (render output changes → text changes →
  hash changes → invalidation from exactly that block forward —
  rip-out #10 becomes unconstructable); the **drill protocol** (closed
  request map, four-axis bounds including bytes, clients may only
  narrow, indexed non-drillable keys); `strict-fail!`'s catch-site
  order (error fact classified agent-vs-core → dev panic → prod face,
  siblings untouched); `render/chat.cljc` bubbles.
- **Revived rulings (code stays dead):** flat `/ai` event log;
  reserved glyphs as single-source defs; error-run coalescing with a
  teaching line; byte-stability above the cache breakpoint.
- **Correctly deleted, stays deleted:** the seven old render dials
  (today's single profile wins); the pod-fused transcript code.
- **Genuinely new work (no prior art):** form-aware fit (the old
  `clip-string` was the same bare `subs` we are removing); turn
  segmentation from contribution rows (needs one accretive key,
  `:seon.context.contribution/characters`, already computed and
  dropped today).

## 5. What stays exactly as is

Verified sound by the census: `seon.render` candidates selection; the
declared-render-output mechanism; the elision-value schema;
`seon.ai.tokens/estimate` as the size unit; the capture facts as the
honesty baseline. **Census corrections accepted:** the render
profile's *policy* stays but its `request-profile` derivation fallback
and the `default-agent-profile` namespace-load global are ledger-28
defects to delete; block identity is work (#12), not a stay; the
elision *schema* stays while its narrating face goes (#5).

## 6. The property suite — loud drift alarms, never fragile tests

The design defines its tests as PROPERTIES over the pipeline —
generative wherever a value is an input (registered schemas make
generators free; the green-to-install auto-check is the precedent),
seeded, with test.check SHRINKING every failure to the minimal
reproducing input. Banned outright: exact-string expectations, pinned
counts, golden HTML.

1. **Totality** — any generated value (adversarial unregistered ones
   included) renders end to end without a throw: a face or ONE error
   fact, never an exception, never absence.
2. **Round-trip honesty** — a data face's text reads back through the
   reader equal to the value modulo declared elisions; every elision
   carries count + requery identity; requerying reaches the content.
3. **Boundedness** — for any value and any budget: output ≤ budget AND
   no fence or form is ever split (whole-form elision only).
4. **No function-side bounding** — the graph-query census: the two
   seams are the only bounding callers; subject-present by
   construction.
5. **Results are data** — every generated result position reads back
   through the reader; prose only under declared instruction entities.
6. **Accounting exactness** — assembled prompt estimate == sum of
   per-contribution costs == wire bytes (the drive's 3-token proof
   becomes a property).
7. **Page lint** — for GENERATED agent histories, rendered pages pass
   `seon.render.lint/check` (no placeholders, splits, duplicated
   subtrees, soup) — the UI's generative test, shrinking to the
   minimal history that reproduces.
8. **Face equivalences** — chat entries ∪ chips == debug entries ==
   capture content; debug pretty face character-equal to `/ai` modulo
   whitespace/color; block identities stable across faces.
9. **Failure faces** — for generated defective render outputs: dev
   panics naming the stage; prod yields exactly one error fact whose
   three faces all render; the catch-site census holds.

The census's TEST AUDIT rides wave 1: existing tests these properties
subsume are DELETED (a smaller suite is a desired outcome), fragile
exact-shape tests are rewritten as properties, the rest stand.

## 7. The wave plan ([register + full plan](../research/one-renderer-gap-census-2026-08-14.md))

0. **Settle the in-flight tree** (re-derive census marks past snapshot
   `38f18880b`; the running fence-fix lands here).
1. **Stage contracts + the panic seam** — §2 implemented; the
   catch-site graph query; dial panic-on in dev; the test audit.
2. **Results are data** — rip-outs #1-#5 (project-node* and the 20
   narrating faces).
3. **Form-aware fit** — sample→emit revival + the new form-awareness
   (rip-outs #6, #8).
4. **Chat + debug faces** — tokenizer revival, chips, pretty-data,
   turn chrome (the §1 three-faces contract).
5. **One block identity + delivery** — rip-outs #10, #12; chain-hash
   invalidation.
6. **Derivation hygiene** — floating; the profile-fallback deletions.

Each wave lands WITH its §6 properties. Waves 3-4 are archaeology-
gated revivals, not fresh builds.

## 8. Open questions for the owner

1. **Attribute-face curation**: when the narrating faces die, do those
   shapes keep tiny declared faces that ORDER attributes (likely as
   inline render outputs) but never prose — or is the floor's
   identity-first ordering enough everywhere?
2. **The `/form` projection**: does the third face join wave 2 (each
   entry carrying its regenerating form) or wait for the drive series
   to demand it?
3. **Chat default timing**: does the agent page default to the chat
   face immediately at wave 4, or stay on debug until the chat face
   has survived one live drive?

## 9. Sources

- [results-as-data audit](../research/results-as-data-audit-2026-08-14.md) — the 30.5%/66.7% measurement and nine-seam list
- [context-clipping census](../research/context-clipping-census-2026-08-14.md) — 16 violations, the two-seams evidence
- [render archaeology](../research/render-archaeology-2026-08-14.md) — the first implementation's pipeline, verdicts per item
- [one-renderer gap census](../research/one-renderer-gap-census-2026-08-14.md) — the 47-row register and wave plan
- [transcript view design](../research/transcript-view-design-2026-08-14.md) — the honest-faces mechanics and class vocabulary
- [UI verification walk](../research/ui-verification-2026-08-14.md) — the live-page evidence
- [context ablation](../research/context-ablation-2026-08-14.md) — live model behavior vs context composition
- Ledger rulings 27-32 in [design-ideas-ledger-2026-08-13.md](design-ideas-ledger-2026-08-13.md)
