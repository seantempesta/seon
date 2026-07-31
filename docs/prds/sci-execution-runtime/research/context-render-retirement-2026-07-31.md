---
type: research
status: active
tags: [research, render, context]
---

# Context/render retirement inventory and implementation ordering

Read-only audit, 2026-07-31. Enumerates everything in fresh `src/`,
`resources/`, and `test/` that the ruled context/render redesign
(`plan/README.md:1259-1303`) obsoletes or overlaps, with what replaces it, what
genuinely blocks the cut, and which tests die with it. Then derives the wave
order from the traced dependencies.

Predecessors, whose claims this lane re-verified against current source rather
than re-citing: `render-current-state-2026-07-31.md`,
`context-walk-synthesis-2026-07-31.md`,
`render-invalidation-caching-2026-07-31.md`,
`agent-entity-graph-audit-2026-07-31.md`.

## The ruled target, in one paragraph

Blocks are the one render unit in both projections; there is no static
scaffold path. Membership is DERIVED by a recursive walk over the agent's
entity discovering schema'd data — no hand-seeded block vectors. Resolution is
one chain: explicit render keys on the value → a same-schema render function in
a governing namespace (viewer's, else the data's owner) → the schema-attached
default → the structural floor; the slot-redirect step retires. Invalidation is
Datahike's attribute revisions read off the database value, not a writer-side
E/A/V index, and the walk must read via concrete pull selectors because a
wildcard pull reduces to `:all`. Ordering v1 is naive last-change transaction
basis: no pins, no bands, no hysteresis.

## The standing law that shapes the plan

Per the great-deletion law, a deletion slice is blocked ONLY by a real
implementation dependency — something live still calls the path and the
surviving owner genuinely cannot serve it yet. Most rows below are blocked by
exactly one thing: a renderer or a fact that does not exist. Two rows are
blocked by nothing and can be cut today (§"Cut-now list").

## 1. The retirement inventory

### 1.1 Hand-seeded block membership

| Item | file:line | Replaced by | Blocked by | Tests pinning it |
|---|---|---|---|---|
| `render.agent/blocks` — the eleven-block seed vector | `src/seon/render/agent.clj:440-499` | walk-derived membership (`block/derived` becomes the walk) | every AI block below having a walk-reachable owner (rows 1.2) | `test/seon/context_pilot_test.clj:121-155` (`agent-birth-seeds-the-first-prompt` asserts the installed name set EQUALS `agent/blocks`), `test/seon/cluster/prompt_test.clj:97,247,256,271,391` |
| `render.agent/seed-tx` | `src/seon/render/agent.clj:501-517` | nothing — creation writes no block set | `render.agent/blocks` dying | same as above |
| `render.root/blocks` — root's seven-block seed | `src/seon/render/root.clj:221-260` | walk-derived membership rooted at root's entity + a walk-reachable fleet representation | **root's fleet reach** (row 1.2 `fleet-oversight`); `:tokens`/`:reply` are fact-free streaming demos with no entity to walk from | `test/seon/render/root_test.clj:10`, `test/seon/render/web_test.clj:355,366,378,409,420` |
| `render.root/seed-tx` | `src/seon/render/root.clj:262-280` | nothing | `render.root/blocks` dying | `test/seon/render/web_test.clj:355+` (page assembly) |
| `:seon.cluster.agent/seed-blocks` request key + `creation-tx`'s block branch | `src/seon/cluster/agent.clj:85-104` (branch at `:96-98,103-104`); schema `resources/seon/schema/agent.edn` | creation writes namespace + agent identity only; the walk supplies membership | both seed vectors dying | `test/seon/cluster/agent_test.clj`, `test/seon/context_pilot_test.clj:121` |
| root seeding call site | `src/seon/cluster.clj:758-765` | nothing | `render.root/seed-tx` dying | `test/seon/cluster/boot_test.clj` |
| `block/blocks` (the installed pull) and `block/membership` (installed ∪ derived + collision refusal) | `src/seon/render/block.clj:205-243`, `:269-297` | one derivation: the walk | rows 1.2 complete | `test/seon/render/block_test.clj:364,375,641,654,670`; `test/seon/context_test.clj:541` (`membership-collision-property`) |
| `block/derived` — the `[]` stub | `src/seon/render/block.clj:245-261` | THE WALK. This is the named N5 slot and it is where walk-derived membership lands; it is not deleted, it is filled | the walk producing block-shaped candidates (needs a stable derived name per node) | `test/seon/context_test.clj:541` |
| `block/install-tx` | `src/seon/render/block.clj:1059+` | retained ONLY if an agent may still install a block by hand; otherwise dies with the seeds | owner decision: does an agent author a block, or only a render fn? | `test/seon/render/block_test.clj:641-670` |

### 1.2 The individual seeded blocks — what each needs before it can die

Each row is one member of `render.agent/blocks` / `render.root/blocks`. "Blocked
by" names the genuine implementation dependency, per the cut-first law.

| Block | Projection | file:line | Replaced by | Blocked by | Tests pinning it |
|---|---|---|---|---|---|
| `:identity` | `seon.context/identity-ai` | `src/seon/context.clj:63-77` | the agent family lens `seon.render.agent/agent-ai` (`agent.clj:62-80`), which the walk already calls at the root node | nothing structural — the sentence moves into `agent-ai`. Cheap. | `test/seon/context_test.clj:207,266`; `test/seon/cluster/prompt_test.clj:97` |
| `:execution` | `seon.context/execution-ai` — the reply-grammar scaffold, always present, 322 static chars | `src/seon/context.clj:247-258` | ruled: "REPL instructions become schema'd facts on the agent's entity, reached by the same walk and rendered by ordinary renderers" (`README:1266-1270`) | **an instruction fact family + its renderer + `creation-tx` writing it** (W1). S1 measured that removing the grammar loses the only teaching of the valid response shape (`s1-shadow/README.md:64-65`) — do not cut before the replacement exists | `test/seon/cluster/prompt_test.clj:391` (`the-execution-grammar-is-always-present`); `test/seon/context_pilot_test.clj:121` |
| `:peers` | `seon.context/peers-ai` | `src/seon/context.clj:79-114` | the agent family lens querying peers, or a walk-reachable population fact | no agent→agent ref exists; the walk reaches a peer only at distance ≥3 through `message/from`. **A renderer must own the send grammar** (S1 loss list) | `test/seon/cluster/prompt_test.clj:247,256`; `test/seon/context_test.clj:480` |
| `:settlement` | `seon.context/settlement-ai` | `src/seon/context.clj:115-166` | the run family lens `seon.cluster.run/render-ai` | the runs this agent ASKED FOR belong to other agents; reachable only via `message/from`+`message/about` at distance ≥3. Needs either a message-lens projection or a distance the prompt actually spends | `test/seon/cluster/prompt_test.clj:291`; `test/seon/context_test.clj:266` |
| `:assignments` | `seon.context/assignment-ai` | `src/seon/context.clj:172-210` | the message family lens `seon.cluster.message/render-ai` rendering its `about` join | message lens must carry the decline grammar and the exact problem identity string | `test/seon/cluster/message_assignment_test.clj`; `test/seon/cluster/problem_routing_test.clj` |
| `:trigger` | `seon.context/trigger-ai` | `src/seon/context.clj:212-245` | the message family lens on the triggering message, reached from the run | **HARD BLOCKER.** The trigger is `:seon.db/trigger` tx-META on the run's creating transaction (`src/seon/cluster/message.cljc` `trigger`), and transaction entities are excluded as apparatus (`src/seon/render/walk.clj:253-287`). A walk structurally cannot reach it. Needs the trigger as an ordinary ref on the run, or apparatus? admitting tx entities | `test/seon/cluster/prompt_test.clj:225,271`; `test/seon/context_test.clj:710,746`; `test/seon/context_pilot_test.clj:158` |
| `:namespace` | `seon.render.agent/namespace-ai` (the walk, as ONE block) | `src/seon/render/agent.clj:362-407` | the walk becomes the membership; this wrapper block disappears into `block/derived` | rows above | `test/seon/render/agent_test.clj:260`; `test/seon/context_pilot_test.clj:158-199,270-408` |
| `:agent-header` | html, `agent-header-html` | `src/seon/render/agent.clj:90-123` | the agent family html lens `agent-html` (`agent.clj:81-89`) — a duplicate today | nothing structural | `test/seon/render/web_test.clj:378` |
| `:message-bar` | html, `seon.render.web/message-bar-html` | `src/seon/render/web.clj:119-180` | the page scaffold, not a block: it renders from no entity | needs a home — the human input surface is not derivable from any fact. Owner decision | `test/seon/render/web_test.clj:959,978,1001` (`the-bar-is-never-patched`) |
| `:transcript` | html only | `src/seon/render/agent.clj:227-250` | walk over the agent's runs/receipts | **no AI transcript exists anywhere in fresh `src/`** (S3 owns it). The html twin can convert earlier than the ai one | `test/seon/render/agent_test.clj:130,144,247` |
| `:focus` | html only, focus+rail | `src/seon/render/agent.clj:328-360` | a presentation composition over walked nodes | rail ordering is commit order, not recency (`agent.clj:294-297`); rail bound reuses the eval `max-collection` dial (`agent.clj:335-339`) | `test/seon/render/agent_test.clj:182,216` |
| root `:fleet-oversight` | `seon.oversight/block-ai`, `/block-html` | `src/seon/oversight.clj:281-295` | a root-scoped walk neighbour, or the cluster as a walkable entity | **HARD BLOCKER for root.** Oversight is derived from LIVE FLOW PINGS (`src/seon/oversight.clj:84-155`), not database facts. A fact-walk cannot reach it. S1 named this exactly: a pure walk "fixes root's trigger blindness by creating fleet blindness" | `test/seon/oversight_test.clj` |
| root `:header`, `:problems`, `:agents`, `:messages` | html | `src/seon/render/root.clj:68-216` | walk over root's entity + the cluster's agents/messages | root's entity does not ref the other agents; same population problem as `:peers` | `test/seon/render/web_test.clj:355-420` |
| root `:tokens`, `:reply` | html, streaming demos | `src/seon/render/root.clj:158-201` | nothing — they render no facts | they are the standing live proof of per-block morph under streaming (`root.clj:250-255`). Cutting them removes a proof surface; replace the proof first | `test/seon/render/web_test.clj:729,804,839` |

### 1.3 Ordering — authored bands and priorities

| Item | file:line | Replaced by | Blocked by | Tests pinning it |
|---|---|---|---|---|
| `:seon.render.block/band` enum `[:anchor :program :authored :continuity :dynamic]`, declared AUTHORED not derived | `resources/seon/schema/block.edn:43-48` | ordering v1: last-change transaction basis, derived (`README:1298-1303`) | derived membership existing to order (W4) | `test/seon/context_test.clj:47` (`contribution-band-references-the-block-band-owner`) |
| `block/band-ordinal` | `src/seon/render/block.clj:184-192` | a per-node last-change basis derivation | same | `test/seon/render/block_test.clj:364,375` (via `blocks` order) |
| `block/ordered` — `(band ordinal, priority, name)` | `src/seon/render/block.clj:194-203` | descending/ascending last-change basis, name as the only tie-break | same | as above; `test/seon/context_test.clj:207` (`context-determinism-property`) |
| `:seon.render.block/priority` (and the priority literals in both seed vectors) | `resources/seon/schema/block.edn:41`; every entry of `agent.clj:440-499`, `root.clj:221-260` | nothing — v1 has no pins | seeds dying (W4) | `test/seon/render/block_test.clj:641-670` |
| `:seon.context.contribution/band` | `resources/seon/schema/context.edn:78-81`; written at `src/seon/context.clj:264-285` | drop the column, or record the derived basis instead | the band attribute dying | `test/seon/context_test.clj:47,424` (`prompt-reduction-ledger-property`) |
| the `"\n\n"` flat join as the whole assembly | `src/seon/cluster/prompt.cljc:205-207` | unchanged shape, new order | ordering derivation existing | `test/seon/context_test.clj:424` |

Note on calibration: bands are the ONE ordering authority today and the schema
comment already refuses a name→band table (`block.edn:43-48`). This is not a
second mechanism to untangle; it is one mechanism to swap.

### 1.4 The two resolution chains, the redirect step, and the two floors

| Item | file:line | Replaced by | Blocked by | Tests pinning it |
|---|---|---|---|---|
| Chain B — `walk/projection` (redirect → viewer overrides → own declaration → family → floor) | `src/seon/render/walk.clj:154-179` | the one chain in `seon.render` | chain A gaining redirect-free viewer overrides, or overrides retiring too | `test/seon/context_pilot_test.clj:319` (`the-viewers-override-wins-over-the-family-and-holds-for-the-walk`), `:270,298,347` |
| `:seon.render/redirect` step (RETIRED by ruling #2) | `src/seon/render/walk.clj:172-179`; schema `resources/seon/schema/walk.edn:37-39` | nothing | nothing structural — no production caller sets it; the ruling retires it "until a concrete need names it" | `test/seon/render/block_test.clj:920,936` (`a-slot-may-steer-its-hop-to-another-projection`, `a-redirected-hop-needs-no-declaration-on-the-neighbor`) |
| `block/entity-slot` 2-arity (the redirect emitter) + its consumption in `expand` | `src/seon/render/block.clj:127-166` (2-arity at `:160-166`), consumed `:690-701` | 1-arity only | same | `test/seon/render/block_test.clj:920,936` |
| `:seon.render/overrides` (viewer map, always `{}` in production) | `src/seon/render/walk.clj:172-179`; `resources/seon/schema/walk.edn:41-46`; callers `src/seon/render/agent.clj:177,426` | the governing-namespace step of the unified chain (viewer-constancy retained) | the namespace step being WIRED — today `:seon.render/namespace` has zero production callers | `test/seon/context_pilot_test.clj:319` |
| Chain A step 2 `namespace-declaration` — built, unwired | `src/seon/render.clj:135-145` | the governing-namespace step, actually wired (one line in `block/unit`, `block.clj:332-364`) | a decision: name convention `render-<kind>` vs the quarry's COMPUTED rule (render-capability derived from the fn's Malli output schema, `src-old/seon/agent/ctx/render_fns.cljc:23-46`). The house rule favours computed | `test/seon/render/value_test.clj:316` (`namespace-defined-override-wins-over-the-schema-default`) |
| HTML floor ×2 — `value/render-html` (`render.clj:163`) vs `block/data-panel` | `src/seon/render/value.cljc:877-888`; `src/seon/render/block.clj:913-1006`; floor chosen at `src/seon/render.clj:160-164` vs supplied at `src/seon/render/agent.clj:177,426` | ONE floor per kind | pick one: `data-panel` uses the one admission codec, `render-html` is the quarry-proven richer renderer with `:seon.eval/opaque` tokens. Owner call | `test/seon/render/block_test.clj:552,603,626`; `test/seon/render_test.clj:135`; `test/seon/render/value_test.clj:349` |
| AI floor ×2 — `value/render-ai` vs `block/data-prose` | `src/seon/render/value.cljc:871-875`; `src/seon/render/block.clj:1008-1053` | same | same | same |
| Two bounding codecs — `value/sample` vs `sci.admit/admit` | `src/seon/render/value.cljc:14-23,798`; `src/seon/render/block.clj:969-974` | one codec, once the floors collapse | floor decision | filed: `docs/seon/issues/value-admission-render-walk-overlap.md` |
| `:seon.render/floor` as a REQUIRED caller-supplied key | `resources/seon/schema/walk.edn:48-51`; `src/seon/render/walk.clj:353-357` | the kind's own floor, derived (as chain A already does at `render.clj:160-164`) | floor collapse | `test/seon/context_pilot_test.clj:298` |

### 1.5 Invalidation and the read seam

| Item | file:line | Replaced by | Blocked by | Tests pinning it |
|---|---|---|---|---|
| Wildcard pull `'[*]` in the walk | `src/seon/render/walk.clj:361` (node), `:307` (refs) | concrete attribute selectors per family | **each family's renderer must NAME its attributes** (W2). `pull [*]` reduces to `:all` exactly like `d/entity` does (`pull_api.cljc:22-36`, probed) — so today every render is a wake-on-every-commit render | `test/seon/context_pilot_test.clj:270-408` (all neighbourhood tests) |
| Wildcard pull in block membership / entity units | `src/seon/render/block.clj:239`, `:511`; `src/seon/render/agent.clj:220,340`; `src/seon/render/web.clj:818` | same | same | `test/seon/render/block_test.clj:755,779`; `test/seon/render/agent_test.clj:144` |
| `d/entity` in a render-adjacent path | `src/seon/cluster/source.clj:238` — the ONLY `d/entity` call in fresh `src/` | a pull with a concrete selector | trivial; this is not on the render path today | `test/seon/cluster/source_test.clj` |
| Direct `datahike.api` in render namespaces (ruling 22a) | `src/seon/render/walk.clj:72`, `block.clj:57`, `agent.clj:54`, `root.clj:27`, `value.cljc:33`, `web.clj:52`; also `src/seon/context.clj:45` | the `seon.db` facade (`plan/seondb-facade-contract-spec.md`, awaiting owner review) | the facade existing. `seon.render` itself is already clean — it requires no database | every render test that constructs a db |
| Unconditional render wake | `src/seon/cluster/wake.cljc:180-186,212` | **STAYS.** Ruling #2(1) puts selectivity in the woken pass via attribute revisions; the listener does no matching | — | `test/seon/cluster/wake_test.clj:269,297` |
| `wake/wake-attributes` `#{:seon.cluster.message/to :seon.cluster.agent/id}` | `src/seon/cluster/wake.cljc:78-93` | **NOT retired by this design.** This is mailbox/armer ROUTING, not render invalidation; its two members are the routing `case`'s own arms and the set exists so the C2 disjointness property compares two computed sets | would only change under ruling 21's per-agent render proc | `test/seon/cluster/wake_test.clj:87,129,297` |

Calibration: the "hand list" instinct misfires on `wake-attributes`. It is two
attributes that a `case` in the same file switches on, kept as a set precisely
so a property can check it against `loop/committed-attributes`. Leave it.

### 1.6 Facts the walk needs and does not have

Not retirements — the prerequisites the retirements are blocked on.

| Missing fact / edge | Evidence | Consequence today |
|---|---|---|
| `:seon.ns/requires` is `[:set :symbol]`, not a ref | `resources/seon/schema/program.edn:42` | namespace→namespace is not a graph edge; "requires at distance 2" is unimplementable as a ref walk. Recommended fix: a derived edge inside `walk/refs` resolving symbols through `:seon.ns/name` (no schema change, external namespaces handled by producing no connection) |
| The agent's own `:seon.ns` row carries only `:seon.ns/name` | `src/seon/cluster/agent.clj:99-104`; `:seon.ns/source` is REQUIRED by the family map (`program.edn:49-57`) | the CENTRE of the agent's context matches no family and renders through the floor as raw datoms. Concrete defect |
| No `:seon.render/ai` or `/html` on `:seon.ns` or `:seon.fn` | grep of `resources/seon/schema/` finds render keys only in `message.edn:38`, `error.edn:77`, `run.edn:17,59,66,86` | the single largest build gap: namespaces and functions have no family lens at all |
| `:seon.schema` rows (559) have zero inbound/outbound refs; `:seon.fn/spec` is a string | `resources/seon/schema/program.edn:10` | an agent cannot walk from a function to its contract |
| The run's trigger is tx-meta, not an attribute | `:seon.db/trigger` on the creating transaction; tx entities excluded at `src/seon/render/walk.clj:253-287` | blocks the `:trigger` block's retirement |
| No instruction/system-message fact family | `rg "AGENTS.md|CLAUDE.md|instructions" src/` returns only a token-estimator comment and one docstring | blocks `:execution`'s retirement |
| No AI transcript projection anywhere | `transcript-html` is html-only (`src/seon/render/agent.clj:227-250`); repo-wide grep finds no ai twin | blocks the transcript's retirement and is the largest single context loss S1 measured |
| No `:seon.render/distance` config dial | default `1` written once at `src/seon/render/block.clj:168-178`; no `:seon.config.render/*` depth fact | "configurable depth" is a request key only |
| Silent reverse-ref truncation | `src/seon/render/walk.clj:216,232` keeps newest `max-collection` per attribute with no elision marker, while the node budget elides loudly (`:368-372`) | a 104-function namespace shows 32 and says nothing |

### 1.7 Cut-now list — blocked by nothing

Two rows have no implementation dependency and can land immediately, before any
wave:

1. **The redirect step** (`walk.clj:172-179`, `block.clj:160-166,690-701`,
   `walk.edn:37-39`) — retired by ruling #2, zero production callers. Delete
   with `block_test.clj:920,936`.
2. **`:identity`'s prose into `agent-ai`** (`context.clj:63-77` →
   `agent.clj:62-80`) — the walk already roots at the agent entity and already
   calls the agent family lens, so the sentence moves with no new fact.

## 2. The waves, derived from the traced dependencies

The critical path is **facts → renderers → membership → ordering**. Everything
else is genuinely parallel. Delivery conversion is not on the path at all.

### W0 — the free cuts (parallel with anything)

- **Owned:** `src/seon/render/walk.clj` (redirect only), `src/seon/render/block.clj`
  (`entity-slot` 2-arity + its `expand` consumption only),
  `resources/seon/schema/walk.edn`, `test/seon/render/block_test.clj:920-950`.
- Deletes: redirect step, 2-arity `entity-slot`, its schema key, its two tests.
- Risk if cut early: none. No production caller.

### W1 — data model: the facts the walk must find

Blocks W2 (renderers need something to render) and W4 (membership needs the
facts).

- **Owned:** `resources/seon/schema/{agent,run,program}.edn`,
  `src/seon/cluster/agent.clj` (`creation-tx`), `src/seon/cluster/run.cljc`
  (`open-call` only), `src/seon/fn.clj` (`namespace-row`),
  `src/seon/render/walk.clj` (`refs` — the derived requires edge).
- Lands: (a) an instruction fact family on the agent entity carrying the reply
  grammar and any global instruction file content; (b) the run's trigger as an
  ordinary ref so it is walkable; (c) `:seon.ns/source` on the agent's own
  namespace row so it matches the family; (d) the derived `:seon.ns/requires`
  edge inside `walk/refs`; (e) optionally `:seon.fn` → `:seon.schema` refs.
- **Risk:** (b) duplicates provenance if the tx-meta trigger stays; retire the
  tx-meta read in the same commit or the two can diverge.
- **Parallel with:** W2's namespace/fn lens authoring is blocked on (c)+(d);
  everything else in W2 is not.

### W2 — renderer authoring (the largest wave; three independent lanes)

Blocks W4. Nothing here blocks anything else.

- **W2a — the corpus lenses.** `:seon.ns` and `:seon.fn` family renderers with
  the ruled distance gradient (0 = name, 1 = signatures + docstrings, deeper =
  bodies). Owned: `resources/seon/schema/program.edn` (render keys), one new
  render namespace for the corpus lenses. Quarry: the old gradient and its
  "real source once, derived member cards only when source is absent" rule
  (`old-context-assembly-2026-07-29.md:364-448`).
- **W2b — the agent-facing lenses.** `agent-ai` absorbing identity + peers +
  the send grammar; `message/render-ai` absorbing assignment + trigger prose;
  `run/render-ai` absorbing settlement. Owned: `src/seon/render/agent.clj`
  (renderers only, not the seed), `src/seon/cluster/message.cljc` (render fns),
  `src/seon/cluster/run.cljc` (render fns).
- **W2c — the AI transcript (S3).** The single largest context gap. Owned: a
  transcript projection beside `transcript-html` in `src/seon/render/agent.clj`,
  or a receipt-family lens. **Do not run W2c in the same lane as W2b** — both
  touch `agent.clj`; give W2c the file and move W2b's agent lens into it, or
  sequence them.
- **W2d — root's fleet reach.** Either the cluster becomes a walkable entity
  with `:seon.oversight/*` derived at render time, or root keeps a renderer
  that queries live pings. Owned: `src/seon/oversight.clj`,
  `src/seon/render/root.clj` (renderers only).
- **Risk:** authoring lenses against the OLD chain and then re-authoring after
  W3. Mitigate by having W2 write only `:seon.render/ai` / `:seon.render/html`
  schema properties and plain `defn`s — neither changes under W3.

### W3 — one resolution chain, one floor per kind

Independent of W1/W2 in semantics; contested only on `block.clj`.

- **Owned:** `src/seon/render.clj`, `src/seon/render/walk.clj` (`projection`),
  `src/seon/render/value.cljc`, `src/seon/render/block.clj` (floors and the
  `unit` builder), `resources/seon/schema/{walk,render}.edn`.
- Lands: chain B collapses into `seon.render`; viewer overrides become the
  governing-namespace step (wired, and preferably COMPUTED from the fn's Malli
  output schema rather than the `render-<kind>` name convention); one floor per
  kind; the two bounding codecs heal
  (`docs/seon/issues/value-admission-render-walk-overlap.md` closes).
- **Blocked by:** an owner ruling on which floor survives and on
  convention-vs-computed. Both are named open questions in the prior audits.
- **Parallel note:** W2 must not edit `block.clj` while W3 owns it. W2's lenses
  live in family namespaces, so this is achievable.

### W4 — walk-derived membership; delete the seeds

The dependency-critical cut. Blocked by W1 + W2 (all four sub-lanes) and by W3
(otherwise membership resolves through a chain that is about to change).

- **Owned:** `src/seon/render/block.clj` (`derived`, `blocks`, `membership`),
  `src/seon/render/agent.clj` (seed), `src/seon/render/root.clj` (seed),
  `src/seon/cluster/agent.clj` (`creation-tx` block branch),
  `src/seon/cluster.clj:758-765`, `src/seon/context.clj` (delete the superseded
  AI projections), `src/seon/cluster/prompt.cljc` (selection reads the walk).
- Deletes: both seed vectors, both `seed-tx`s, `:seon.cluster.agent/seed-blocks`,
  the installed/derived join and its collision refusal, and the five
  `seon.context` AI projections whose prose moved into lenses in W2.
- **Risk if cut before W2c:** the prompt loses all history. S1 measured this and
  refused to call it a win. This is the one place where cutting early produces a
  materially worse agent, and the standing law's "real implementation
  dependency" clause applies squarely.

### W5 — ordering v1: naive last-change basis

Blocked by W4 (there must be derived members to order). Same files as W4, so
serial with it.

- **Owned:** `src/seon/render/block.clj` (`band-ordinal`, `ordered`),
  `resources/seon/schema/block.edn` (band enum, priority),
  `resources/seon/schema/context.edn` (contribution band),
  `src/seon/context.clj` (`contribution-row`).
- Deletes: the band enum, `band-ordinal`, priority, `:seon.context.contribution/band`.
- Adds: order by each node's last-change transaction basis, derived from the
  database value, name as the only tie-break. **The tie-break matters** — the
  ordered-collection law refuses letting a set walk decide order.
- **Risk:** oscillation. The ruling explicitly accepts it: hysteresis waits for
  a MEASURED oscillation. Do not pre-build bands "just in case".

### W6 — invalidation swap: attribute revisions

Independent of W4/W5 in semantics but touches the walk's pull sites, so it
follows W2 (renderers must name their attributes first). The web/wake half can
run in parallel with W1-W3.

- **Owned (half A, parallel-safe):** `src/seon/render/web.clj`,
  `src/seon/cluster/wake.cljc` (no change expected — record the finding),
  plus the `seon.db` facade if the owner clears the contract spec.
- **Owned (half B, after W2):** `src/seon/render/walk.clj`,
  `src/seon/render/block.clj`, `src/seon/render/agent.clj` — every `'[*]` pull
  becomes a concrete selector.
- Lands: each render retains its dependency attribute set plus the
  per-attribute revisions it last saw, and answers "am I stale?" in O(|deps|)
  map lookups against the db value it already holds
  (`query.cljc:2568-2589,2963-2975`). No writer-side index. The unconditional
  render wake stays.
- **Risk:** narrowing before the renderers are stable produces selectors that
  under-read. Fail open to `:all` on any render failure — the named historical
  defect (`failed-page-render-retains-stale-dependencies.md`).

### W7 — delivery conversion (packages / keyframes / revisions)

**Recommend staying TARGET.** The complete-snapshot pipeline is
correct-by-construction and measured; serialize-once fan-out and new-tab cost
are not currently measured problems (`render-current-state-2026-07-31.md`, open
question 6). Nothing in the ruled context/render design depends on it.

### Parallelism summary

| Slot | W0 | W1 | W2a | W2b | W2c | W2d | W3 | W6a |
|---|---|---|---|---|---|---|---|---|
| Can start now | yes | yes | after W1(c,d) | yes | yes | yes | after owner ruling | yes |
| File conflicts | walk.clj/block.clj (small, land first) | schema edn, cluster/agent, cluster/run, fn.clj | program.edn + new ns | message.cljc, run.cljc | agent.clj | oversight.clj, root.clj | render.clj, walk.clj, value.cljc, block.clj | web.clj |

W2b and W2c both want `agent.clj`; W0 and W3 both want `walk.clj`/`block.clj`
(W0 is a few lines and should land first). W4 and W5 are serial on the same
files and are the spine's tail.

## 3. Test families that die, and the property that replaces each class

Per the testing law: one regression per class, and the replacement asserts the
SURVIVING mechanism.

| Dying family | Files | Class it was fencing | Replacement (one property per class) |
|---|---|---|---|
| Seed-set identity | `context_pilot_test.clj:121-155` (installed names EQUAL `agent/blocks`) | "birth installs the right list" | **Membership completeness**: for any generated agent entity graph, every schema'd entity reachable within the requested distance appears exactly once in the derived membership, and nothing else does |
| Per-sentence prompt assertions | `prompt_test.clj:247,256,271,291,391`; `context_test.clj:266` | "this block's prose is present" | **Presence-implies-facts**: a projection appears in the prompt iff the facts that cause it exist at that basis (already the shape of `placement-and-omission-property`, `context_test.clj:266` — keep it, re-point it at the walk) |
| Band/priority ordering | `block_test.clj:364,375`; `context_test.clj:47` | "the authored order holds" | **Order determinism**: two derivations of one database value are byte-identical, and order is a total function of (last-change basis, name) — no set or hash-map walk decides a tie |
| Redirect / slot steering | `block_test.clj:920,936` | "a hop can be steered" | none — the mechanism retires. Delete both. |
| Viewer overrides | `context_pilot_test.clj:319` | "the viewer's lens wins and stays constant" | **Chain precedence**: one generative property over the unified chain asserting value keys > governing namespace > schema default > floor, with viewer-constancy across every hop (subsumes `value_test.clj:303,316,328,349`) |
| Two-floor duplication | `block_test.clj:552,603,626` vs `render_test.clj:135`, `value_test.clj:349` | "any value renders" | **Floor totality**: one generative round-trip — every generated value renders to the kind's grammar through exactly one floor, bounded by the one admission codec |
| Membership collision | `context_test.clj:541` | "installed vs derived collide loudly" | dies with the join; replaced by the membership-completeness property's uniqueness clause |
| Root block set | `root_test.clj:10`; `web_test.clj:355,378` | "root's page has these blocks" | **Page identity**: every derived block occupies its own stable `surface-id` and the page is their ordered concatenation (already `web_test.clj:355` — re-point, don't delete) |
| Install/upsert mechanics | `block_test.clj:641,654,670` | "seeding is idempotent" | dies if agents no longer install blocks by hand; survives unchanged if they do (owner decision, §1.1) |

Everything in `web_test.clj:409-540,619-880` (initial paint, changed-block-only
wire, reconnect-is-repaint, suppression-compares-bytes, slow-tab newest-wins)
survives untouched — the delivery pipeline is not part of this retirement.

## 4. What is in genuinely good shape

Calibration, not alarm:

- **The walk is real and correct.** Distance is spent per connection, bounded
  three ways, cycle-guarded per path, and every failure is a flat error node
  (`walk.clj:328-423`). It is new work, not a port — the old system's `depth`
  bounded structural nesting only.
- **Apparatus exclusion is presence-derived**, not a list
  (`walk.clj:253-287`), and reverse traversal needs no attribute enumeration
  (one generic datalog clause, `walk.clj:221-224`).
- **Family defaults already live on the Malli entity map** and are discovered
  with no registry (`walk.clj:132-148`). Adding a lens is one EDN property plus
  one defn.
- **Omission is nil-punning throughout**, so an html-only block costs the
  prompt zero tokens (`block.clj:472-475`), and a failed projection contributes
  a bounded named statement rather than vanishing (`prompt.cljc:106-114`) — the
  old confabulation defect is already repaired.
- **The prompt is not a second rendering system.** It routes through the one
  router (`prompt.cljc:183-196`); the 2026-07-28 audit line calling it "the
  largest second rendering system" is stale.
- **`wake-attributes` is not a hand list** (§1.5) and should survive this
  refactor unchanged.
- **`seon.render` itself requires no database** — the read seam is already
  clean at the router.

## 5. Findings that need an issue note (this lane is report-only)

1. The agent's own namespace row is a stub with no `:seon.ns/source`, so the
   centre of its context renders as raw datoms
   (`src/seon/cluster/agent.clj:99-104` vs `program.edn:49-57`). Concrete
   defect, not a preference.
2. Silent reverse-ref truncation (`walk.clj:216,232`) while the node budget
   elides loudly (`walk.clj:368-372`).
3. Every walk read is a wildcard pull, so every render is `:all`-dependent
   (`walk.clj:307,361`) — this is what makes selective invalidation impossible
   today.
4. 20+ source docstrings name schema files as `src/seon/schema/*.edn`; they
   live at `resources/seon/schema/*.edn`. Docstrings render into agent context
   (already recorded in `agent-entity-graph-audit-2026-07-31.md:439-449`; still
   unfiled).

## 6. Skill drift

- **`datastar-web-ui`** does not mention `seon.render.walk`, the primary+rail
  layout, or the two resolution chains; an agent loading it to work on the
  router would not learn chain B exists. Already reported by the render audit;
  repeating it because this retirement makes it more consequential — a lane
  cutting chain B needs to know the skill will still describe the old world.
- **No loaded skill claims the context/prompt assembly path at all.**
  `seon-context-config`'s description explicitly warns off "context-block
  manifests". Under the skills-blast-radius ruling this is the gap to close
  once W4 lands, not before.
- `data-oriented-clojure` and `seon-flow-architecture`: no drift found on any
  claim this lane checked.
