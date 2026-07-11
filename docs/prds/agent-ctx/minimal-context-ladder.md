---
type: prd
status: active
tags: [prd, agent, index]
---

# The context rebuild — old surfaces retire, their ideas return tested

**The point (owner, 2026-07-11): the old context tree's SURFACES retire, but
their IDEAS are the inventory.** Each old block held an idea — some good
(the live tile, reactive warnings, shared instructions), some accreted
guesswork. The rebuild is: carefully add NEW blocks based on the ideas the
old blocks held, each colocated, each tested to confirm it works as
intended. Deletion applies only where an idea isn't useful. Nothing is
ported verbatim; everything that returns is rebuilt on the minimal base and
earns a ledger row.

**Why (the poison principle — this is NOT about tokens):** bad information
in context poisons behavior, the old tree is an initial guess plus pile-on
(bloated, self-repeating, probably self-contradictory), and attributing a
bad drive to a specific line after the fact is intractable. The asymmetry
that decides everything: **omission is recoverable and attributable** (an
agent hits a wall, the transcript shows the wall, one milestone adds one
fragment), **inclusion is neither** (poison hides in the pile). So evidence
attaches at INSERTION time, and the safest posture is minimal.

**Vocabulary (owner, 2026-07-11): no parallel vocabulary. Clojure has
functions, schemas, and tests** — agent-facing surfaces and docs say
"functions" (never "verbs"), capability milestones have proper names (never
"rungs"), and a new noun is a parallel-system risk. Phase 0 below sweeps the
existing docs into compliance.

Evidence ledger: `evals/runs/2026-07-10-minimal-buildup/README.md`.
Standing rules: colocation (a block carries its own teaching; context is
purely additive — removing a block removes every word about it), variant
teaching gated on DB datoms, config→DB at boot, NO symptom-side hacks,
three testing surfaces only, implementation = opus agents against written
specs.

## Phase 0 — cleanup FIRST (owner-ordered)

Before any further capability work:

1. **Vocabulary sweep**: "verb(s)" → "function(s)" across `docs/**`, the
   `CLAUDE.md` files, and every AGENT-FACING string (docstrings, block
   teaching, card headers). ~100 hits in the core docs alone; the vision/
   history docs get a light pass (they are records, not guidance). Code
   IDENTIFIERS containing "verb" (e.g. the repl routing helpers, the
   `:recent-verbs` block name) are listed and renamed as their own
   coordinated unit — symbol renames in the shared tree are atomic,
   orchestrator-scheduled, and the `:recent-verbs` block belongs to the
   typeahead lane (coordinate, don't clobber).
2. **Milestone naming**: "rung N" → the milestone names (table below — real block/namespace names);
   this doc, the roadmap, the ledger README headers, and memory notes
   updated. The ledger's historical run data keeps its recorded labels —
   history isn't rewritten, only living guidance.
3. **Deprecation labeling**: every legacy block surface that is NOT in the
   target vision gets an explicit `DEPRECATED` header — docstring line 1 of
   its render function ("DEPRECATED — reference for the <idea> milestone;
   see minimal-context-ladder") and its row in this doc — so the old code
   reads unambiguously as reference material, never as the live pattern to
   imitate.
4. **Doc reconciliation**: `docs/seon/architecture/context.md` + `toolkit.md`
   updated to name the rebuild as the governing arc; stale references to
   the skills system as load-bearing get the deprecation framing.

Phase 0 is a single opus unit against this spec; the orchestrator reviews
the sweep diff (vocabulary changes are cheap to make and expensive to
half-make).

## Capability milestones (formerly "rungs")

**Naming rule (owner, 2026-07-11): a milestone is named by the block or
namespace it validates — real, REPL-discoverable names only.** No metaphor
nouns ("memory", "attention", "canvas", "collaboration" are retired
coinages). Likewise `:batch`/`:stream` are the names (never "Mode A/B" —
chat shorthand that leaked; Phase 0 sweeps it from living docs).

| milestone | old label | status |
|---|---|---|
| **`repl`** | rung 0 | CLOSED — fabrication contained (`:batch`) / eliminated (`:stream`); cross-model verdicts; cards suffice for correct first calls |
| **`namespaces`** | rung 1 | GREEN both models; exposed + fixed the cross-turn current-ns core bug |
| **`plan`** | rung 2 | GREEN both models incl. a real mid-drive pod restart |
| **`db`** | rung 4 ("store"/"memory") | NEXT — `schema/register!` / `db/transact!` / `db/query` round-trip, recall across turns AND a restart; validates the v3.1 restart-survival line; decides whether any db teaching beyond cards + error envelopes is needed |
| **`warnings`** | rung W ("attention") | derived problem surfaces — instrumentation errors, test failures, why-complete-refused — render until fixed |
| **`live-tile`** | rung L ("canvas") | the human's live view returns with its own teaching; the interactive plan tile (in flight) is the pattern |
| **`subagents`** | rung M ("collaboration") | children status + outcome routing, per the multiagent spec's win conditions |
| **`soul`** | rung I ("identity") | owner-gated — define what it IS before any block exists |

Standing check at every milestone: re-run the `repl` milestone's
fabrication metrics on the accepted variant, and earlier milestones'
oracles must not regress.

## The final vision — the target block set

The end-state `system.edn` tree. Every block colocates its own teaching,
renders from DB state, and vanishes (or shrinks to its empty-state teaching)
when its state is empty. Sizes are fixed-prefix estimates.

| block | covers | teaching it carries | ~tokens |
|---|---|---|---|
| **system-text** (the `:seon.config/system-text` datom) | global REPL mechanics ONLY — form execution, interleaved results, result re-reference, movement, restart survival, errors-as-data, delivery | the whole block IS teaching; every line has a provenance note | ~500 |
| **`:namespaces`** | WHERE the agent is and WHAT exists: current-ns full source, home-required namespaces as compact cards (function heads + docstring line 1 + schema), more-exist-query-don't-guess | full-vs-cards policy, movement/update semantics | ~6.4k |
| **`:plan`** | durable work state: goal anchor, open frontier, recently-done | decompose-first (empty state), close-when-work-lands, discovered-steps-under-the-plan | 0.1–0.2k |
| **`:warnings`** | current problems: instrumentation errors, failing tests, faults, refused completes — render-until-fixed, self-healing | what each surface means + the one next action, per surface, only when present | 0 when healthy |
| **`:live-tile`** | the human's live canvas | render-don't-narrate, how to put a view up, that the human watches THIS | ~0.3k |
| **`:shared-instructions`** | DB-backed standing instructions (human- or agent-added, cross-agent) | renders only when rows exist; each instruction is its own teaching | 0 when empty |
| **`:subagents`** | children + their outcomes, orphans | renders only when children exist; spawn/outcome semantics | 0 when none |
| **`:transcript`** | the spine: interleaved messages + evals, oldest-first, age-band decayed | masthead grammar + the mode-gated repl fragment (`:batch`/`:stream`) | grows, decayed |
| *(`:soul` — shape TBD by the owner at its milestone; expected: a few lines, possibly inside system-text)* | who the agent is | — | tiny |

**Deliberately NOT blocks** (pull, not push): the `my.kb` worked manual and
any former skill-body depth — the agent reads them on demand (the db is
self-describing); retrieval (`relevant-source`'s idea) starts as a search
the agent CALLS, promoted to a pushed block only if a drive proves the need;
`jobs`/`usage`/`inventory`/`findings` stay ideas-on-the-shelf unless a
milestone drive shows the gap. The skills SYSTEM (catalog + loadable bodies)
retires: its job dissolves into cards (proven), state-gated block teaching,
and pull references.

Expected steady-state fixed prefix: **~8–9k tokens** for a healthy agent
(vs ~28k today), with problem surfaces adding tokens only while problems
exist. But the metric that matters is the poison one: every line in the
tree is attributable to evidence.

## The idea inventory — every old surface → its idea → its replacement

| old surface (prio) | the idea it holds | replacement | tested by |
|---|---|---|---|
| shipped `ctx/system-text` def (~4–5k) | REPL mechanics; register!-before-transact; namespaced keys; schemas-enforced; attributes-not-kinds; concept doctrine | v3.x holds the REPL mechanics (measured); the data-rule ideas are `db`-milestone candidates — the runtime already teaches most REACTIVELY (`transact!` rejects unregistered attrs with a guiding envelope); a text line enters only where the envelope isn't enough | `db` drives; per-line inclusion bar |
| `soul` (5, SOUL.md) | persistent identity/persona | `soul` milestone; identity as DB state; drive law already ran soul OFF | owner-defined win condition |
| `agents` (8, AGENTS.md, ~14.1k) | house rules / operating instructions | MINED per-line: global+load-bearing → system-text candidates; block-specific → that block's teaching; the rest presumed pile-on | per-line inclusion bar |
| `shared-instructions` (10) | DB-backed standing instructions all agents see | rebuilt state-gated (keeps its real name) | add instruction → behavior changes → remove → reverts |
| `skills-catalog` (12) + always-on bodies (16, ~2.9k) | discoverable on-demand expertise | dissolves: cards + state-gated teaching + pull references | the `live-tile` milestone drives tile-building with NO skill body |
| `namespaces` (20) | place + visible code | **kept — the proven floor** | `repl`/`namespaces` milestones |
| `live-tile` (35) | the human watches a canvas, not a chat log | rebuilt at the `live-tile` milestone | observer-scored followability drive |
| `warnings` (40) | reactive self-healing problem surfaces | rebuilt at the `warnings` milestone | agent recovers from its own turns-old breakage unprompted |
| `jobs` (42) | background-job visibility | likely covered by cards + result envelopes (proven in `repl`-milestone drives); rebuild only on demonstrated blindness | long-job + restart drive |
| `test-failures` (43) | WHY the complete-gate refused | the `warnings` milestone (the gate already enforces) | turns-to-understand-refusal with/without |
| `plan` (45) | durable work state | **kept — rebuilt and proven** | the `plan` milestone |
| `recent-verbs` (46) / `plan-ledger` (47) | typeahead offer menus | the typeahead lane's live experiment — exempt; `recent-verbs` also carries a vocabulary rename (Phase 0, coordinated) | their arc's measures |
| `relevant-source` (48) | retrieval beyond current-ns | pull-first (a search the agent calls); pushed block only if a discovery-task drive proves the need | discovery-task oracle |
| `subagents` (96) | children + outcomes | rebuilt at the `subagents` milestone | multiagent spec win conditions |
| `findings` (97) | durable findings outliving the transcript | the `db` milestone decides — findings-as-db-facts may subsume it | store-then-recall across restart |
| `transcript` (100) | the spine | **kept** | every drive |
| `usage`, `inventory` (families, not in the default tree) | spend awareness; store inventory | ideas on the shelf | — |
| `my.kb` manual ns | the worked DB-memory manual | PULL reference — read on demand, never pushed | the `db` milestone |

## The inclusion bar (what it takes to enter the context, forever)

Every fragment — a system-text line, a block header sentence, a card policy —
enters only with all three:

1. **Colocation** — it lives with the block whose state it describes and
   renders exactly when that state holds.
2. **A ledger row** — added at a drive boundary under a variant tag; at
   least one drive per model class (iterate on Spark, gate on DeepSeek)
   showing the target behavior moved or a wall came down, with the
   `repl` milestone's fabrication metrics not regressing.
3. **A colocated provenance note** — `;; evidence: <ledger row>` beside the
   teaching string in source, so line→test mapping is greppable and any
   provenance table is DERIVED, never hand-maintained. We cannot attribute
   failures to lines after the fact, so evidence attaches at insertion and
   nothing enters without it.

Corollary: confusion in a transcript → reword THAT fragment → new variant
tag → redrive. Two failed rewords = remove the fragment and reconsider the
mechanism (context can't fix a code problem).

## Progressive graduation — the minimal MATURES INTO the system

Not a big-bang cutover (owner-confirmed): the minimal tree matures in place
until it IS `system.edn`, in individually-safe steps — the system being
built is the system being run, which is what makes it sustainable.

1. **Now (safe, immediate):** the measured system-text graduates to
   `system.edn`'s `:seon.config/system-text` — every cluster gets the
   evidenced v3.x lines instead of the shipped guess; blocks untouched.
2. **Continuous (already happening):** rebuilt blocks are the SAME specs
   both trees reference — the `plan` milestone's teaching reached the default
   automatically. No fork ever exists.
3. **Tree parity (after `db` + `warnings` + `live-tile` = daily-work
   coverage):** `system.edn`'s TREE switches to the rebuilt tree — one
   coordinated commit (owner + both lanes; the default pod is shared). The
   old tree survives only as `config/legacy.edn` for comparison drives,
   with an expiry date — never a fallback.
4. **The retires land with the switch:** the shipped system-text def, the
   identity file-block reads, the skills catalog/bodies, plus their dead
   code paths (a disabled surface is still a poison vector). Ideas not yet
   rebuilt stay in the inventory, not in the tree.
5. **Steady state:** ONE tree; every later milestone lands directly in it;
   maintenance IS the loop that built it — confusion → reword the block's
   own lines → redrive → ledger row → provenance note.

## System-text v3.1 — line provenance

| line | added | evidence |
|---|---|---|
| live-REPL framing / persistence | v0 | all drives |
| prose-in-parens execution rule | v2 | reduced, not eliminated, both models; mechanics contain the rest |
| concrete glyph prohibition | v2 | interview-endorsed both models |
| interleaved results + parallel threads | v0 | 60+ drives |
| result-KNOWLEDGE (ids/shas arrive interleaved) | v1 | observer-driven; unseen-value fabrication not recurred in Mode A |
| result/<id> re-reference | v0 | used correctly |
| movement (in-ns / ns / require / redefinition) | `namespaces` milestone | its GREENs |
| restart-survival (defn/deftest/register! survive; bare def + atom values don't) | v3.1 | source-verified, UNDRIVEN — validate at the `db` milestone |
| errors-as-data | v0 | solid |
| message/complete/wait | v0 | solid |
| no-form/delivery rule | v3 | fixed 2 live failures, both models, n=2 |

## Method invariants (do not drift)

- Iterate wording on **Spark** (fast, ~0-fab noise), **gate on DeepSeek**
  (the model that reveals defects); never accept a variant without the gate.
- Smallest-n that changes a decision; transcripts over statistics; every
  scorer check stated verbatim in the task text.
- One drive at a time; `SEON_CONFIG` + provider key exported on every
  cluster create AND restart; check for `SEON-STUB-LLM` after provider boots.
- Implementation units are **opus** seon-agents against a written spec; the
  orchestrator keeps this doc, the roadmap, and the ledger current after
  every unit.
- No parallel vocabulary: functions, schemas, tests; milestones named by
  the real block/namespace they validate; `:batch`/`:stream`, never
  "Mode A/B".

## Cutover status (2026-07-11 — DONE, running)

`system.edn` now carries the graduated v3.1 system-text (single source —
`minimal.edn` inherits it) and the evidenced tree: `:namespaces` +
`:plan` (with the html twin — the human's live plan tile) + `:transcript`,
plus root's three KEPT derived fault surfaces (`:core-faults`,
`:instrumentation-gaps`, `:orphaned-agents` — 0 tokens when healthy;
first formally validated at the `warnings` milestone). Root's live-tile
dashboard, skills catalog/bodies, soul/agents file-blocks, and every
other legacy block are OUT of the running tree; the old tree is frozen
in `config/legacy.edn` (comparison drives only, expires after
`db`+`warnings`+`live-tile`). Live proof: `cluster reset default` →
pod boots on DeepSeek (`:stream` per-model default), `/` 200, the debug
render shows exactly the expected blocks + the v3.1 text. acme is
UNAFFECTED tree-wise (its agent-context replaces wholesale) but inherits
the graduated system-text on its next restart (coordination.md flagged).

## Hacks → production (the consolidated fix-it-all plan, owner-ordered 2026-07-11)

Sources: the dual-code-paths registry (docs/seon/orchestrator/issues/
dual-code-paths-registry.md — THE canonical list; open rows summarized
here, the registry stays the tracker), the two 2026-07-11 audits
(research/claude-md-audit + research/vocabulary-audit), and this arc's
own known defects. Ordered; each is an opus unit against a written spec.

1. **Phase 0 sweep** (task #6, spec above): verbs→functions (~110 living-doc
   hits; toolkit.md is fully verb-framed; poison surface = agent-facing
   strings in ctx.cljs/menu.cljs/client.cljs seeds), rungs→milestone names
   (incl. RENAMING THIS FILE — "ladder" is retired vocabulary; new name
   `context-rebuild.md`), Mode A/B→`:batch`/`:stream`, DEPRECATED headers
   on legacy block render fns. Identifier renames listed for coordination
   (11 symbols + `:recent-verbs`, typeahead-lane-owned). Owner decisions
   queued: D1 diffusion "canvas-text" homonym (recommend: exempt,
   documented), D2 "store" for durable-store paths (legit) vs db-synonym
   uses (fix), D3 "root world" idiom, D4 `dispatch-repl-verb!` →
   `dispatch-repl-form!`.
2. **Harness consolidation** (task #4): port the milestone harness into
   src-inspect-ai (contracts→datasets, oracles+fab-analyze→scorers, model
   column), retire tmp/*-drive.sh + the tmp/t4-* fixtures into proper
   homes, FIX the broken bundle sha fence (hashes the loader only — use
   main.js.sha256/build-dir hash, the bench harness precedent).
3. **Post-cutover deletions** (after a few days' stability): the shipped
   `ctx/system-text` def (still the silent fallback when the datom is
   absent — a live poison vector; make absence LOUD instead), the in-code
   `default-ctx-blocks` legacy entries (config/legacy.edn becomes their
   only home until expiry), the identity file-block read paths, the skills
   catalog/body loading machinery.
4. **Detector gap #10**: multi-line bare-map fabricated results evade the
   line-shaped claim regexes (`:batch` only; 5 residual turns in the v0
   runs).
5. **Registry open rows, tooling class** (each small): C55/C56 bare
   `[:cat :map]` request schemas (named open request schemas per the C54
   pattern), C58 inline `:seon.agent/id` shape duplication (mechanical),
   C30 sourceless schema-keyword ns replay stubs, C59 sequential
   eval-batch! tee loss (latent hazard), C53 two candidate scorers (owner
   call: parameterize the band), C49 skill-body two-corpora duplication
   (owner call — may DISSOLVE with the skills retirement in this arc),
   C25/C29 (perf notes, measure first), C60/C40 (owner-deferred, observe).
   C26 (db/query tuple misreading) belongs to the `db` milestone's
   teaching, not a code unit.
6. **Env-coupled suite flake**: 18 preflight-repair failures once, green
   on identical re-run — root-cause session (ambient compile-state
   pollution suspected).
7. **acme home-requires superset** hand-duplication (acme.edn's own
   comment admits the drift risk) — derive or document per the
   shared-shape rule.

## Resume-here (for a fresh session)

The default cluster RUNS the rebuilt tree (cutover above). In-flight at
wind-down (2026-07-11): the interactive plan tile unit (html twin live on
the pod; final polish/report may be unfinished — check `git log` +
`src/my/plan/internal.cljs` state), tasks #4/#5/#6/#7 queued with full
specs, the `db` milestone is the next capability drive. Session state:
memory `project_context_rebuild_2026_07_10.md`; per-drive evidence:
`evals/runs/2026-07-10-minimal-buildup/README.md`.

## Open defects riding this arc

- #10 multi-line bare-map strip gap (fabricated indented maps evade the
  line-shaped claim regexes — 5 residual turns in the v0 runs).
- Env-coupled suite flake (18 preflight-repair failures once, green on
  identical re-run) — recorded, not root-caused.
- Introspection-shadowing sweep beyond `ns-interns`/`ns-publics` (fixed via
  computed `core-macro-head?`) — check `ns-aliases` et al.
- **datahike-CLJS recursive-rule executor broken past depth 1** (plan-tile
  unit find, 2026-07-11): `(descendant ?r ?x)` on a 3-deep chain returns
  only depth 1 on every pod runtime (JVM correct on the same store) —
  `my.plan` roll-ups print "0 of 0 steps done" in REAL prompts and the
  frontier mislists undrained roots as ready; suite blind (all test trees
  depth-1). Own unit: depth-2 regression test first, then fix
  `execute-recursive-rule`; then re-unify `plan-block-html`'s build-forest
  deviation back onto `rollup`/`ready-leaves`/`anchor`.
- The interactive plan tile: LIVE on the cutover pod (html twin + 4
  morph-survivable signals; zero agent-token cost — `*.internal` is
  context-hidden). Remaining: fresh-boot slot/SCI verification, browser
  click-through, one full suite.
