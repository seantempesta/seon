---
type: research
status: active
tags: [research, agent, database, schema]
---

# Agent startup audit — the birth transaction, and where its facts belong

Audit date 2026-07-31. Read-only on `src/`, `test/`, `resources/`. Live
evidence from the running `default` cluster (pid 35516, 1 agent `root`, 0
messages, 0 runs, config singleton eid 4011 / ns eid 4012 / agent eid 4013).

Companion to `agent-entity-graph-audit-2026-07-31.md` (what a walk can REACH)
and `context-walk-synthesis-2026-07-31.md` (what already exists on the owner's
framing). This document answers the other half: **what exists at the moment an
agent is born, and — under the 2026-07-31 rulings — which of those pieces must
become datoms and on which entity.**

The governing rulings this proposal serves:

- **Ruling 2026-07-31** — blocks are the one render unit, no static scaffold
  path; the system message, global instruction files, and REPL instructions
  "become schema'd facts on the agent's entity, reached by the same recursive
  entity walk" (`plan/README.md`, the block just above "Rulings 2026-07-31 #2").
- **Rulings 2026-07-31 #2** — (1) invalidation is attribute revisions and the
  walk must read via `pull` with concrete selectors, never `d/entity`;
  (2) resolution is value-keys → governing-namespace fn → schema-attached
  default → structural floor, slot-redirect RETIRED; (3) ordering v1 is pure
  naive last-change transaction basis, no pins, no bands — "even the system
  message finds the front naturally because it never changes."

---

## Part A — the audit

### A1. Every path that creates an agent today

There is exactly **one** production creation site in fresh `src/`.

| # | Path | file:line | Notes |
|---|---|---|---|
| 1 | `seon.cluster/seed-root-agent!` → `cluster.agent/creation-tx` | `src/seon/cluster.clj:748-767` (call at `:758`) | The ONLY production caller. Boot-time, idempotent by identity. |
| 2 | `seon.cluster.agent/creation-tx` (the formal shape) | `src/seon/cluster/agent.clj:84-104` | Pure tx-data. Contract `:seon.cluster.agent/creation-request` (`resources/seon/schema/agent.edn:9-17`). |
| 3 | Tests, direct | `test/seon/context_pilot_test.clj:62,125,412`; `test/seon/cluster/agent_namespace_test.clj:19,37,54`; `test/seon/cluster/program_restart_test.clj:116,278`; `test/seon/gen/loop_test.clj:57`; `test/seon/test_runner_test.clj:56` | Same function. |
| 4 | **A bare id datom** — an agent with no namespace and no blocks | `test/seon/context_pilot_test.clj:64` (`{:seon.cluster.agent/id "peer"}`) | Legal by construction: an entity IS its attributes. `armer-step` will arm it (`agent.clj:482-489`). |

**There is no `POST /agents` route.** The one Ring dispatcher
(`src/seon/render/web.clj:735-800`) has exactly four routes: `POST
/agent/{id}/message`, `GET /`, `GET /agent/{id}`, `GET /feed/{id}`. Nothing
creates an agent from the web.

**There is no agent-facing creation function.** `src/my/` contains only
`message.cljc` and `run.cljc`; neither mentions `:seon.cluster.agent/id` as a
creation target. **An agent cannot create another agent today.**

**Helper agents do not exist in fresh `src/`.** The `helper` of the S0/S1
context-walk baselines was planted by an experiment script on a scratch
cluster, not by any production path.

### A2. Arm, not create — the second half of "coming to exist"

Creation (a commit) and arming (a process artifact) are deliberately separate.

| Step | file:line | What it does |
|---|---|---|
| `armer-step` derives (agents in facts) − (armed set) | `src/seon/cluster/agent.clj:478-490` | One generic query `[_ :seon.cluster.agent/id ?id]`; a committed agent creation IS an arm wake. |
| `arm!` stamps → starts → resumes → routes → primes | `src/seon/cluster/agent.clj:343-404` | Refuses an id with no committed entity (`:373-376`). Writes NOTHING (`L8`). |
| `graph-definition` — the ONE blueprint | `src/seon/cluster/agent.clj:246-270` | Two procs (`::mailbox`, `::turn`), both `:io`. **Two agents differ only by agent-id and mailbox channel** (`agent.edn:26-30`) — there is no per-agent graph variation to model. |
| `my.agents.<id>` SCI namespace | `src/seon/sci/eval.clj:191-198` | Derived, never created: `(symbol (str "my.agents." agent-id))`. The evaluator binds sci's `*ns*` per form (`eval.clj:55-60`, `:767`); the loop re-derives the same name at plan freeze (`loop.cljc:789`) and at eval (`loop.cljc:996`). **No SCI namespace object is created at birth.** |

### A3. The birth transaction inventory

#### A3.1 An ORDINARY agent (`creation-tx` with default seed blocks)

`creation-tx` (`agent.clj:94-104`) returns TWO maps; the block vector defaults
to `render.agent/blocks` (`agent.clj:96-98` → `src/seon/render/agent.clj:440-499`,
11 blocks, measured live `(count seon.render.agent/blocks)` = 11).

| Entity | Attribute | Value | Count | Source |
|---|---|---|---|---|
| NS | `:seon.ns/name` | `my.agents.<id>` | 1 | `agent.clj:99-100` |
| AGENT | `:seon.cluster.agent/id` | the id string | 1 | `agent.clj:101` |
| AGENT | `:seon.cluster.agent/namespace` | → NS tempid | 1 | `agent.clj:102` |
| AGENT | `:seon.cluster.agent/blocks` | → 11 component blocks | 11 | `agent.clj:103-104` |
| BLOCK ×11 | `/name`, `/priority`, `/band`?, `:seon.render/ai`?, `:seon.render/html`? | see below | 44 | `render/agent.clj:452-499` |
| TX | `:db/txInstant` | commit instant | 1 | Datahike |
| **Total** | | | **≈59 datoms** | |

The eleven seed blocks, verbatim (`render/agent.clj:452-499`):

| name | band | priority | `:seon.render/ai` | `:seon.render/html` |
|---|---|---|---|---|
| `:identity` | `:anchor` | 0 | `seon.context/identity-ai` | — |
| `:execution` | `:anchor` | 10 | `seon.context/execution-ai` | — |
| `:peers` | `:anchor` | 20 | `seon.context/peers-ai` | — |
| `:agent-header` | `:anchor` | 25 | — | `seon.render.agent/agent-header-html` |
| `:message-bar` | `:anchor` | 30 | — | `seon.render.web/message-bar-html` |
| `:transcript` | `:dynamic` | 40 | — | `seon.render.agent/transcript-html` |
| `:focus` | `:dynamic` | 50 | — | `seon.render.agent/focus-html` |
| `:namespace` | `:dynamic` | 80 | `seon.render.agent/namespace-ai` | — |
| `:settlement` | `:dynamic` | 84 | `seon.context/settlement-ai` | — |
| `:assignments` | `:dynamic` | 85 | `seon.context/assignment-ai` | — |
| `:trigger` | `:dynamic` | 90 | `seon.context/trigger-ai` | — |

Measured live: `(mapv :seon.render.block/name (filter :seon.render/ai
seon.render.agent/blocks))` = `[:identity :execution :peers :settlement
:assignments :namespace :trigger]` — **7 AI blocks, 4 HTML-only**.

#### A3.2 ROOT (measured live, not inferred)

Root is created with `:seon.cluster.agent/seed-blocks []`
(`src/seon/cluster.clj:761-763`), then `root/seed-tx`
(`src/seon/cluster.clj:765-767` → `src/seon/render/root.clj:224-259`) installs
root's own 7 blocks. **Two transactions, by design.**

Live `d/datoms :eavt 4013`:

```
[4013 :seon.cluster.agent/blocks 4014..4020]   ; 7
[4013 :seon.cluster.agent/id "root"]
[4013 :seon.cluster.agent/namespace 4012]
```

Root's 7 blocks (live pull): `:header`(0,html), `:problems`(10,html),
`:fleet-oversight`(15,`:dynamic`, **ai** `seon.oversight/block-ai` + html),
`:agents`(20,html), `:messages`(30,html), `:tokens`(40,html), `:reply`(50,html).
**Exactly one AI block** — this is the measured cause of S0's 164-byte root
prompt, and it is a production defect, not a walk problem.

Root's namespace row, live: `{:db/id 4012, :seon.ns/name my.agents.root}` — one
datom. `:seon.ns/source` is ABSENT.

| Entity | Datoms |
|---|---|
| NS `my.agents.root` | 1 |
| AGENT `root` | 9 |
| 7 blocks | 23 |
| **Total** | **33** |

#### A3.3 Absent-but-needed at birth

| Absent | Consequence | Evidence |
|---|---|---|
| `:seon.ns/source` on `my.agents.<id>` | The ns row matches no registered entity family (`:seon.ns/ns` REQUIRES source, `resources/seon/schema/program.edn:49-57`), so `walk/projection` falls to the structural floor and renders raw datoms. **The centre of the agent's own context has no lens.** | live pull above; prior audit §1.4 |
| `:seon.db/user` / `:seon.db/process` on the creating tx | Root's creating tx entity is `#:db{:id 536870922, :txInstant #inst "2026-07-30T19:52:07"}` — **provenance-free**. `seed-root-agent!` calls `d/transact` with a bare vector (`cluster.clj:757-764`), not `store/transact!` with `:tx-meta`. Every other durable write in the loop carries provenance. | measured live |
| `:seon.cluster.agent/run` | Correct — absence IS idle. | `resources/seon/schema/run.edn:2` |
| Any message | 0 live. The agent is born mute and is woken only by a later `:seon.cluster.message/to`. | measured live |
| Any run / receipt / capture / attempt | 0 live. | measured live |
| A system-role message | **None exists.** `:seon.ai/system` (`resources/seon/schema/ai.edn:18`) is set at exactly ONE site — `src/seon/cluster/loop.cljc:881`, from the failover notice's `:seon.render/ai` projection (`loop.cljc:920-929`). The ordinary provider call sends `role:"user"` only (`src/seon/ai.cljc:219-221`). | grep + read |
| Any instruction-file fact | **`AGENTS.md`/`CLAUDE.md` are read NOWHERE in `src/`.** The only hit is a docstring in `src/seon/ai/tokens.cljc:16`. | `rg` over `src/` |
| Any memory family | No `:seon.*memory*` attribute in `resources/seon/schema/`. Durable recall is messages + the wait-note in a settled receipt's `result-edn`. | prior audit §6 |
| A ref to the cluster | The config singleton (`:seon.config/cluster "default"`, eid 4011, identity attribute `resources/seon/schema/config.edn:1-2`) has **zero inbound refs**. The walk cannot reach the cluster. | measured live |

### A4. Characterization — where each startup piece actually lives

#### (a) Database facts

- The agent identity, its namespace ref, and its block component set (A3).
- The config singleton (eid 4011, 27 dials) — committed by `seon.config/apply!`
  (`src/seon/config.cljc:237-252`) BEFORE the root agent (eid 4011 < 4013).
  Unreferenced by any agent.
- Program-graph rows (`:seon.fn` 1367, `:seon.ns` 121, `:seon.test` 622,
  `:seon.schema` 559) arrive by fork from `current-src`, not by agent creation.

#### (b) Hand-seeded render blocks

Two hard-coded Clojure `def`s, copied into every agent at birth:

- `src/seon/render/agent.clj:440-499` — the ordinary 11.
- `src/seon/render/root.clj:224-259` — root's 7.

Both are vectors of block MAPS transacted as component entities. This is the
copy-at-birth shape the quarry names as the old system's scaling failure
(`old-context-assembly-2026-07-29.md:28-33`), softened only by upsert-by-name
idempotence (`block/install-tx`, `src/seon/render/block.clj:1059-1101`).

**Dead code found:** `seon.render.agent/seed-tx`
(`src/seon/render/agent.clj:501-517`) has **zero callers** — `creation-tx`
inlines `render.agent/blocks` directly (`agent.clj:98`). Its root twin
`root/seed-tx` IS called (`cluster.clj:765`). The asymmetry is a smell: two
seeding idioms for one act.

#### (c) Hard-coded in functions — the prose an agent actually reads

Every byte of an agent's non-derived prompt is a string literal in a Clojure
function body. There is no fact behind any of it.

| Block | Function | file:line | Character of the text |
|---|---|---|---|
| `:identity` | `identity-ai` | `src/seon/context.clj:63-77` | Template over `agent-id` + `sci.eval/agent-namespace`. Per-agent by construction. |
| `:execution` | `execution-ai` | `src/seon/context.clj:247-258` | **Pure constant. Ignores its unit (`[_unit]`).** The reply grammar / "REPL instructions" — byte-identical for every agent in every cluster. |
| `:peers` | `peers-ai` | `src/seon/context.clj:79-113` | HYBRID: a derived agent-id list + ~350 chars of constant `my.message/send` grammar. |
| `:assignments` | `assignment-ai` | `src/seon/context.clj:172-210` | HYBRID: derived problem ids + constant `my.message/decline` grammar. |
| `:settlement` | `settlement-ai` | `src/seon/context.clj:135-170` | Derived + one framing sentence. |
| `:trigger` | `trigger-ai` | `src/seon/context.clj:212-245` | Derived from the run's `:seon.db/trigger` message. |
| `:namespace` | `namespace-ai` | `src/seon/render/agent.clj:362-407` | The walk. One framing sentence + `walk/prose`. |

The constant halves of `execution-ai`, `peers-ai`, and `assignment-ai` are the
"static scaffold" the 2026-07-31 ruling abolishes as a mechanism. Measured cost
in S1: 322 repeated characters per shadow (`s1-shadow/README.md:81-83`).

#### (d) Read from files at runtime

**Nothing agent-facing.** The only runtime file reads are:

- `seon.schema.edn/load!` — the classpath schema population, called at each
  namespace's top (`agent.clj:78`, `context.clj:57`, `walk.clj:81`, …).
- `config/default.edn` + an optional overlay, reconciled into the config
  singleton at boot (`src/seon/config.cljc:137-252`).

No `AGENTS.md`, no prompt template file, no instruction directory. **The
instruction-file half of the 2026-07-31 ruling has no implementation to
convert — it is a greenfield addition.**

---

## Part B — the placement proposal

Everything below is a proposal, clearly separated from the audit above. It
follows the three 2026-07-31 rulings and the existing naming conventions
(`:seon.cluster.agent/*` for facts ON the agent; a family namespace matching a
real code namespace that owns the data).

### B1. The design pressure: shared bytes buy prompt-cache prefix reuse

Under ordering v1 (naive last-change basis, ruling #2(3)), a fact that never
changes sorts to the front of every agent's prompt. If N agents each render
their OWN copy of the reply grammar, the bytes are equal but the entities are
not — and every future edit is N writes. If they all reference ONE entity
rendered by ONE function, the rendered bytes are byte-identical **by
construction**, and the provider's prefix cache sees the same opening tokens
across every agent in the cluster. That is the argument for a shared
instruction entity rather than per-agent instruction attributes.

The counter-pressure is sovereignty: a shared entity edited once changes every
agent's context at the next turn. §B5 open decision 1 owns that.

### B2. Proposed new family — `:seon.cluster.instruction`

**Fits no existing family.** Nothing in `resources/seon/schema/` carries
agent-facing durable prose. This is a NEW schema family (one new `.edn` file,
or an addition to `agent.edn` — file boundaries are editorial).

```clojure
;; resources/seon/schema/instruction.edn  (proposed)
{; The identity. A keyword, not a string: these are a small closed set
 ; authored by the system, and the keyword IS the prompt header the agent
 ; reads — the same three-roles argument :seon.render.block/name makes
 ; (resources/seon/schema/block.edn, "THE NAME IN THREE ROLES").
 :seon.cluster.instruction/id [:keyword {:seon.db/identity true}]

 ; The bytes. Rendered verbatim by the family's default :seon.render/ai.
 :seon.cluster.instruction/text [:string {:min 1}]

 :seon.cluster.instruction/instruction
 [:map {:seon.db/entity true
        :seon.render/ai seon.cluster.instruction/instruction-ai
        :seon.render/html seon.cluster.instruction/instruction-html}
  [:seon.cluster.instruction/id :seon.cluster.instruction/id]
  [:seon.cluster.instruction/text :seon.cluster.instruction/text]]

 ; THE EDGE. A plain ref set, deliberately NOT :seon.db/component:
 ; instructions are SHARED across agents, and a component ref would
 ; cascade-delete a shared entity when one agent is removed
 ; (block.edn's :seon.cluster.agent/blocks is component precisely
 ; because blocks are NOT shared).
 :seon.cluster.agent/instructions [:set :seon.db/ref]}
```

The `:seon.render/ai` / `:seon.render/html` properties on the entity map are
the family-lens idiom already used by `:seon.cluster.agent/agent`
(`resources/seon/schema/run.edn:15-23`) and the message/error/run families.
Under resolution ruling #2(2) this is step 3 (schema-attached default), so an
agent can still override it with a same-named defn in its own namespace.

**Walk reach: distance 1** from the agent root — one hop over
`:seon.cluster.agent/instructions`, exactly like `:seon.cluster.agent/namespace`.

**Seed rows (proposed), from today's constants:**

| `/id` | `/text` source today |
|---|---|
| `:reply-grammar` | `seon.context/execution-ai`'s whole body (`context.clj:253-258`) |
| `:messaging` | the constant tail of `peers-ai` (`context.clj:106-113`), with the live-peer list removed — that half becomes a walk product, §B3 |
| `:declining` | the constant tail of `assignment-ai` (`context.clj:203-210`) |
| `:global` | the user's `AGENTS.md`/`CLAUDE.md` bytes, ingested at `bin/seon init` time as a fact (greenfield — nothing reads these today) |
| `:system` | the system message, if one is ever wanted. Note it would still ride the USER role unless `loop.cljc:878-881` is changed; today `:seon.ai/system` is owned by the failover notice. |

Because these rows are shared, the natural seeding site is the **cluster
population**, not `creation-tx`: one upsert per row at boot (or in the
`current-src` ancestor so every fork inherits them), and `creation-tx` writes
only the REFS. That keeps the birth transaction small and makes an instruction
edit one write instead of N.

### B3. The cluster edge — peers and root's fleet from ONE ref

`peers-ai` and `oversight/block-ai` are both "tell me about the whole cluster"
projections that a pure walk from the agent cannot reach: peers are only
reachable at distance 2 THROUGH a shared message, so a freshly created agent
sees no peers at all, and root's fleet block is the hidden second context
mechanism `s1-shadow/README.md:114-116` warns against.

**Proposal:** one ref from every agent to the config singleton, which already
exists, is already the cluster's identity, and is already committed before the
first agent.

```clojure
;; resources/seon/schema/agent.edn  (proposed addition)
; The cluster this agent belongs to: a ref to the config singleton,
; identified by :seon.config/cluster (config.edn:1-2). Not a name string
; and not a new entity — the singleton IS the cluster in facts.
:seon.cluster.agent/cluster :seon.db/ref
```

Consequences, all derived and none name-based:

- **d1** — the agent reaches the cluster entity; its family lens renders the
  cluster's name and whatever dials are agent-relevant.
- **d2** — the cluster's REVERSE `:seon.cluster.agent/cluster` refs are every
  agent in the cluster. `peers-ai`'s list and root's fleet oversight are the
  same traversal at the same distance, satisfying "root gets the fleet without
  a second context mechanism" with no `root`-name rule (which the
  no-hand-lists ruling would refuse).
- The walk's generic reverse query (`src/seon/render/walk.clj:221-224`) already
  finds it — no per-attribute knowledge needed.

Cost: one datom per agent, written in `creation-tx`. Risk: at d2 the cluster
node fans out to EVERY agent, and `max-collection` (64 live) silently truncates
(prior audit §7.5). A cluster renderer should emit a bounded, name-sorted
projection rather than relying on the generic reverse expansion.

### B4. Disposition of every startup piece

| Piece today | file:line | Proposed home | Entity | Walk distance |
|---|---|---|---|---|
| agent id | `agent.clj:101` | unchanged | agent | 0 |
| namespace ref | `agent.clj:102` | unchanged | agent → ns | 1 |
| **`:seon.ns/source` for `my.agents.<id>`** | ABSENT | **write `"(ns my.agents.<id>)"` in `creation-tx`** so the row matches `:seon.ns/ns` and gets its family lens | ns | 1 |
| `identity-ai` prose | `context.clj:63-77` | **DELETE.** It states only agent-id + namespace, both of which the agent family lens (`seon.render.agent/agent-ai`, declared `run.edn:15-23`) renders from the facts at d0/d1. | — | 0–1 |
| `execution-ai` prose | `context.clj:247-258` | `:seon.cluster.instruction/text` on `:reply-grammar` | shared instruction | 1 |
| `peers-ai` grammar half | `context.clj:106-113` | `:messaging` instruction row | shared instruction | 1 |
| `peers-ai` derived list | `context.clj:97-102` | **DELETE the projection**; the list falls out of cluster-reverse at d2 (§B3) | cluster → agents | 2 |
| `assignment-ai` grammar half | `context.clj:203-210` | `:declining` instruction row | shared instruction | 1 |
| `assignment-ai` derived half | `context.clj:187-198` | **DELETE the projection**; message → `about` → problem is already d1→d2 from the agent | message/problem | 1–2 |
| `settlement-ai` | `context.clj:135-170` | keep as a render function for now — it joins runs the agent did NOT open, which the walk from THIS agent does not reach. Flag as an open reach question. | — | unreachable |
| `trigger-ai` | `context.clj:212-245` | **DELETE the projection**; the trigger message is a reverse `:seon.cluster.message/to` at d1, and S1 measured the walk renders it BETTER than the block (`s1-shadow/README.md:50-61`). Caveat: the trigger is identified by the run's tx `:seon.db/trigger`, which the walk excludes as apparatus (prior audit §7). | message | 1 |
| `namespace-ai` framing | `render/agent.clj:395-407` | becomes the walk itself; the framing sentence dies with the block | — | — |
| root's `fleet-oversight` | `render/root.clj:236-241` | superseded by the cluster edge (§B3) | cluster | 1–2 |
| root's 6 HTML blocks | `render/root.clj:225-259` | stay blocks — they are `:seon.render/html` projections with no AI half, and the ruling makes blocks the one render unit in BOTH projections | agent → blocks | apparatus |
| ordinary agent's 4 HTML blocks | `render/agent.clj:476-491` | same | agent → blocks | apparatus |
| `AGENTS.md` / `CLAUDE.md` | NOWHERE | `:global` instruction row, ingested at init | shared instruction | 1 |
| system message | NOWHERE | `:system` instruction row IF wanted; note `:seon.ai/system` is currently the failover notice's slot (`loop.cljc:881`) and reusing it needs a decision | shared instruction | 1 |
| creating-tx provenance | ABSENT | route `seed-root-agent!` through `store/transact!` with `:tx-meta {:seon.db/process …}` like every other durable write | tx entity | apparatus (excluded) |
| `render.agent/seed-tx` | `render/agent.clj:501-517` | **DELETE** — zero callers | — | — |

**Net effect on the birth transaction** (ordinary agent, under this proposal):

| | today | proposed |
|---|---|---|
| ns datoms | 1 | 2 (`+ :seon.ns/source`) |
| agent datoms | 13 | 3 + instruction refs (≈4) + 1 cluster ref ≈ 8 |
| block entities | 11 (44 datoms) | 4 HTML-only (≈16 datoms) |
| shared instruction rows written | 0 | 0 (seeded once per cluster, not per agent) |
| **total per agent** | **≈59** | **≈26** |

Creation stays one transaction, becomes less than half the datoms, and the AI
half of context becomes references rather than copies.

### B5. Open decisions the orchestrator must settle

1. **Instruction versioning vs. cluster sovereignty.** A shared entity edited
   in place changes every agent's next prompt — which is what "freshness
   outranks cache" (ruling 16, `README.md`) says we want, and is the OPPOSITE
   of the sovereignty rule that governs code facts. The alternatives:
   (a) mutate in place, every agent sees the new text next turn (simplest,
   maximal cache reuse, no history question); (b) instructions are immutable by
   identity — `:seon.cluster.instruction/id` carries a version or content
   digest, and "editing" mints a new row and repoints refs, so an old agent
   holding the old ref is genuinely sovereign; (c) mutate in place and rely on
   Datahike history for forensics. **Recommend (a)** — Datahike history already
   preserves the old value, so (c) is (a), and (b) creates a repoint fan-out
   with no named consumer. But this is a real decision, not an obvious one.
2. **Do instruction refs even belong on the AGENT, or on the CLUSTER?** If
   every agent in a cluster gets the same instruction set, `agent →
   instructions` is N copies of one fact and the honest edge is `cluster →
   instructions`, reached at d2 through §B3's cluster ref. Per-agent refs only
   earn their place when instructions genuinely differ per agent. **Recommend
   cluster-owned by default with a per-agent additive set** only if a concrete
   need appears — otherwise it is a hand-maintained per-agent list in disguise.
3. **What is the ordering key, exactly?** Ruling #2(3) says "last-change
   transaction basis, derived, never a stored timestamp." For a block whose
   render reads several entities, is the key `max` over the tx of every datom
   the render read (which pairs naturally with #2(1)'s dependency-attribute
   set), or the tx of the ROOT entity only? These differ: an instruction row
   never changes, but if the key is the agent's own last-change tx, every block
   on that agent moves together and nothing sorts stably. **The key must be
   per-render, over the render's own read set**, or the "system message finds
   the front naturally" claim does not hold.
4. **Transcript reach — messages are reverse refs at what depth?** At d1 the
   walk finds EVERY message to/from the agent, unordered, truncated silently at
   `max-collection` (64 live, prior audit §7.5, `walk.clj:216,232`). A
   transcript needs recency ordering and an aging projection
   (`transcript-aging-quarry-2026-07-29.md:84-140`), neither of which a generic
   reverse expansion provides. Either messages get a bounded, `at`-ordered
   family projection, or the transcript is not a walk product in v1.
5. **Does the agent's `:seon.ns/source` stub lie?** Writing
   `"(ns my.agents.<id>)"` makes the row match the family and gain a lens, but
   it also asserts source that no file contains and that the agent's later
   `in-ns`/require activity would not update. Alternative: relax
   `:seon.ns/ns`'s requirement of `/source` so an agent namespace is a legal
   member with none. **Recommend relaxing the entity schema** — a namespace
   with no file genuinely has no source, and inventing one is a stored
   derivation.
6. **Ordering position facts — none, per ruling #2(3).** Recorded here only to
   note the consequence: `:seon.render.block/priority` and
   `:seon.render.block/band` (`block.edn`) become UNUSED for context assembly
   the moment naive ordering lands. They still order the HTML page. Decide
   whether they stay (page-only) or the page also moves to naive ordering —
   two ordering authorities on one attribute set is exactly the duplicate the
   one-mechanism rule refuses.
7. **Can an agent create an agent?** Today nothing can except boot (A1). If
   creation IS context curation, the creating agent must be able to name the
   instruction set and the initial messages of its child, and that surface does
   not exist. It is a `my.*` function through the one door, not a new mechanism
   — but its request shape is a design decision.
8. **`settlement-ai` reaches runs this agent did not open** (`context.clj:151-166`
   queries every run with a plan-digest, then filters by asker). No walk from
   the agent reaches those runs. Either an `asked-for` edge becomes real, or
   settlement stays a hand-written projection and the "no static scaffold"
   ruling has its first named exception.

---

## Part C — findings to file, and skill drift

### C1. Defects found (report-only lane; no issue notes filed)

1. **Root has exactly one AI block** (`render/root.clj:236-241`) — the measured
   cause of the 164-byte root prompt. Production defect, already characterized
   in `s0-baseline/README.md:52-56`, still live.
2. **`seon.render.agent/seed-tx` is dead code** — zero callers
   (`render/agent.clj:501-517`); `creation-tx` inlines `render.agent/blocks`.
   Its root twin IS called. Two idioms for one act.
3. **Agent creation writes no provenance** — root's creating tx entity is
   `{:db/txInstant …}` and nothing else (measured live). `seed-root-agent!`
   uses `d/transact` with a bare vector (`cluster.clj:757-764`) where every
   other durable write uses `store/transact!` with `:tx-meta`.
4. **`:seon.ns/source` absent on agent namespaces** — carried from the prior
   audit (§1.4) and confirmed live; the agent's own namespace renders through
   the structural floor.
5. **The cluster is unreachable from any agent** — the config singleton has
   zero inbound refs (measured live).

### C2. Skill drift

Loaded: `data-modeling`, `datahike`, `data-oriented-clojure`. Claims checked
against current source held:

- `data-modeling`'s component-ref cascade claim — correct, and load-bearing for
  `:seon.cluster.agent/blocks` (`resources/seon/schema/block.edn`, the
  "EACH AGENT OWNS ITS COMPLETE SET" comment). This is precisely why the
  proposed `:seon.cluster.agent/instructions` must NOT be a component ref.
- `data-modeling`'s `{:seon.db/entity true}` marker claim — correct; the block
  family and `:seon.cluster.agent/agent` both rely on it.
- `datahike`'s `d/transact` two-shapes claim — correct, and both shapes appear
  in the creation path (`cluster.clj:757` bare vector vs `loop.cljc:702-716`
  arg-map with `:tx-meta`).
- `data-oriented-clojure`'s "derive at render, don't store" — the proposal
  above follows it; the only NEW stored facts proposed are instruction TEXT
  (genuinely authored data, underivable) and two refs.

**One gap, unchanged from `context-walk-synthesis-2026-07-31.md:530-539`: no
skill claims the context/prompt assembly surface.** An agent asked to change
agent birth or context assembly gets no skill that names `seon.context`,
`seon.cluster.prompt`, `seon.render.block`, or `seon.render.walk`. Given the
skills-blast-radius ruling this remains a high-priority gap; this lane is
report-only and did not edit any skill.

### C3. Reproduction

Every live number came from `mcp__seon__eval_clj` against cluster `default`,
reaching the database value as:

```clojure
@(:seon.boot/cluster-connection
  (val (first @@#'seon.cluster/running-instances)))
```

The decisive forms were: `(d/datoms db :eavt <root-eid>)` for the agent's exact
birth datoms; a `d/pull` of `[{:seon.cluster.agent/blocks [*]}]` for the seeded
set; `(d/pull db '[*] tx)` over the history-derived creating tx for provenance;
and `(d/pull db '[*] <config-eid>)` for the singleton.
