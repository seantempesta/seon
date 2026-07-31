---
type: research
status: active
tags: [research, documentation]
---

# Documentation reconciliation against the 2026-07-31 rulings

The owner rulings of 2026-07-31 (`plan/README.md:1259-1303`) settled four
things the documentation corpus contradicted in several places at once:
blocks are the ONE render unit in both projections with NO static scaffold
path; invalidation is Datahike attribute revisions, not the old E/A/V index;
resolution is explicit value render keys → same-schema namespace fn (viewer's,
then owner's) → schema-attached default → floor, with the slot-redirect step
retired; and ordering v1 is the pure naive last-change transaction basis — no
pins, no bands, no hysteresis.

This lane made the present-tense authority docs non-contradictory. It did not
touch `plan/README.md`'s dated ruling ledger, dated `research/*.md` evidence,
or any skill file.

## Conflicts found → resolution

| # | Conflict | Evidence | Resolution |
|---|---|---|---|
| 1 | Root `AGENTS.md` named `program-synthesis-2026-07-21.md` as "the active program roadmap" while that file's own header says it sequences nothing and points at `plan/README.md` | `AGENTS.md:379-383` (before); `program-synthesis-2026-07-21.md:11-18` | Pointer moved to `plan/README.md` + `plan/unsettled.md`; the old anchor named as superseded and non-sequencing |
| 2 | `context.md` "Order = stability" specified a Bayesian change/no-change estimator, a `p(change) / (tokens × (1-p))` key, a frozen epoch, a hysteresis threshold, and six inviolable semantic bands | `context.md:441-501` (before) | Rewritten as "Order = last change": position is the latest basis transaction among the datoms a block read, ascending; no bands, pins, or hysteresis; banding deferred until a MEASURED oscillation. Transcript-window and pull-first-relevance policies preserved as CONTENT policies, not ordering bands (`context.md:441-497`) |
| 3 | `context.md` "Installed block (explicit override)" made `install-tx` + priority a second, stored assembly path | `context.md:313-325` (before) | Replaced by "a fact on the agent entity (the same walk, no second path)"; the retired mechanism named explicitly (`context.md:313-329`) |
| 4 | `architecture.md` glossary defined **block** by the stored `:seon.cluster.agent/blocks` collection and priority order | `architecture.md:165-170` (before) | Redefined as the one render unit in both projections, derived by the walk, ordered by last change (`architecture.md:165-176`) |
| 5 | `architecture.md` glossary entries **seed-copy** and **`install-tx`** described the stored membership mechanism as current | `architecture.md:226-235` (before) | Replaced by one **the walk** entry carrying the full resolution chain; seed-copy/install-tx/priority/band named RETIRED (`architecture.md:233-242`) |
| 6 | `architecture.md` section "Seed-copy, not merge" | `architecture.md:515-523` (before) | Rewritten as "One walk, no second assembly path", including the no-static-scaffold consequence (`architecture.md:523-534`) |
| 7 | `architecture.md` see-also and UI paragraph described the "three-band cache gradient" and the "seed-copy + install-tx override model" | `architecture.md:629-632, 668-681` (before) | Updated to last-change ordering and the derived-walk block model |
| 8 | `ui.md` §"Seed-copy — one collection, no merge" and §"Installing and removing — the one override" were the full stored-membership contract (~54 lines) | `ui.md:160-213` (before) | Replaced by §"Membership is the walk — one derivation, no stored set", carrying the ruled four-step resolution chain, the explicit retirement of the slot-redirect step, and a superseded note pointing at the sealed context-blocks package. The still-true parts kept: global-vs-per-agent decided by the data the render queries; `:seon.render.block/name` is not a datahike identity (`ui.md:160-205`) |
| 9 | `ui.md` "prompt == page" claimed AI renders are formatted in `:seon.render.block/priority` order | `ui.md:216` | Now "in last-change order ([[context]])" |
| 10 | `ui.md` "byte-stable cache prefix at low priority"; "how the prompt bands by dynamism"; "top-level surfaces in priority order"; downstream composition naming `install-tx` | `ui.md:283, 293-297, 367, 741` (before) | All four restated against the derived walk and last-change order |
| 11 | `data-model.md` §4.2 specified `:seon.render.block/priority` and `:seon.render.block/band` as current attributes, and `:seon.cluster.agent/blocks` as "sorted by priority at render" | `data-model.md:472, 483-510` (before) | §4.2 given a superseded banner naming exactly what retires and what survives; the `:seon.cluster.agent/blocks` row marked historical. The attribute table itself left in place — the replacement facts land with the render/context contract rewrite, and inventing them here would be a second design authority (`data-model.md:472, 485-500`) |
| 12 | `data-model.md` plan render referenced "[[context]] band 1" | `data-model.md:1108` | Band reference dropped |
| 13 | `observability.md` forensic composition listed "seed-copy block override via `seon.render.block/install-tx`" as a current mechanism | `observability.md:357` | Now "the derived block walk over a database value" |
| 14 | `plan/context-blocks-contracts-2026-07-28.md` presented the sealed stored-membership contract as `status: active` | whole file | `status: superseded` + a banner naming the four rulings, what retires, and what survives as quarry (omission = nil-punning, the router request shape, the presence doctrine) |
| 15 | `plan/context-walk-experiment-protocol.md` framed the walk as a candidate ("blocks remain the production path"), and its S4 stage specified "two generational bands with hysteresis" | `context-walk-experiment-protocol.md:9-15, 48-52` (before) | Header re-scoped: the walk is the ruled direction, the staged experiment MECHANIC is what survives. S4 rewritten to MEASURE the naive order and to record an oscillation if one exists, rather than to build bands (`:9-16, :49-55`) |
| 16 | `plan/ui-conversion-plan-2026-07-29.md` slices assume block seed sets and `:seon.render.block/band :anchor` | `ui-conversion-plan-2026-07-29.md:274-275` | One bracketed partial-supersession note added at the top (`:25-29`); the plan's page structure, morph targeting, grammar refusal, and route work are unaffected and were left alone |
| 17 | `plan/unsettled.md` dated blocks assert "blocks survive only as static scaffold" (`:771`) and "static blocks reduced to scaffold" (`:942`) | as cited | ONE bracketed pointer line added below the top header (`unsettled.md:13-17`); the dated blocks are left verbatim as the record of what was believed when |
| 18 | `docs/seon/issues/index.md` failed its own checker: nine rows named notes already archived as resolved, and the eval-time-schema row said `friction` where the note says `blocker` | `bin/issues-index --check` exited 1 with nine `scheduled-note-is-not-open` plus one `schedule-severity-mismatch` | Schedule repaired; `--check` is clean at 8 open / 786 archived |
| 19 | `index.md`'s header said "GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`", but `seon.dev.issues/run!` accepts only `[]` or `["--check"]` and both call `check!` | `script/seon/dev/issues.clj:196-215` | Header rewritten to describe the hand-owned schedule and what the checker validates |
| 20 | `issues/README.md` and `issues/AGENTS.md` both called `bin/issues-index` a severity-inventory generator that "must not overwrite the schedule" | `README.md:60-61`, `AGENTS.md:16-19` (before) | Both restated: the command validates only |
| 21 | Issue note `issues-index-checker-disagrees-with-the-schedule-convention` was open, asking to decide one home for the schedule | note body | RESOLVED and archived: the checker already validates the schedule form (`script/seon/dev/issues.clj:131-157`), so the one home is the hand-owned schedule with its destination column. Proof recorded in the note: `--check` clean, and removing one open note's row makes it exit 1 with `missing-schedule-row` |

## Left deliberately

- **`plan/README.md`** — the dated ruling ledger. Superseded rulings stay; the
  2026-07-31 ruling already names what it supersedes. Not edited.
- **`docs/prds/**/research/*.md`** — evidence documents. `old-context-assembly-2026-07-29.md`,
  `context-pilot-2026-07-28.md`, `renderable-corpus-*-2026-07-28.md`,
  `simplification-catalog-2026-07-28.md`, and `query-invalidation`/E-A-V material
  describe what was true or believed at their dates. Left as evidence.
- **`data-model.md` §4.2's attribute table** — marked historical rather than
  rewritten. The replacement attribute set is a design decision owned by the
  render/context contract rewrite, not by a documentation lane.
- **Archived PRDs** (`docs/prds/archive/**`) — historical by construction.
- **`config/system.edn` references** (`context.md:544`, `data-model.md:1173,1182`)
  — NOT resolved. Both `config/default.edn` and `config/system.edn` exist in the
  tree, and the `seon-context-config` skill calls the manifest model deleted.
  Whether the architecture docs should name `default.edn` is a config-ownership
  question, not a render-ruling one; flagged here rather than guessed.
- **`toolkit.md:250-253`** ("preserve every other block dial") uses the old
  block-dial vocabulary loosely. It describes `my.ns` selections, which are
  ordinary entity facts under the new model; left as low-value churn.

## Skills

No skill file was edited (out of scope by lane rule). Swept
`.agents/skills/*/SKILL.md` for stored-block membership, scaffold, priority/band
ordering, slot redirect, and E/A/V invalidation claims: **none found**.
`datastar-web-ui`, `ui-canvas`, and `seon-context-config` speak of blocks only
as morph targets and of the deleted pod manifest, both of which remain accurate.
No skill lane is needed for the 2026-07-31 rulings.

## Commits

- `ee9cf1b07` — architecture docs + root `AGENTS.md` + the two plan context docs.
- `b85b23bae` — issue schedule, its convention docs, and the resolved checker note.
- this file plus the `unsettled.md` / `ui-conversion-plan` pointers.
