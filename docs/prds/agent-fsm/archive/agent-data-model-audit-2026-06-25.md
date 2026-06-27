---
type: prd
status: draft
tags: [prd, agent, schema, flow]
---

# Agent data-model audit — lock ONE coherent fingerprintable record (2026-06-25)

## TL;DR

The agent record is close to coherent. The FSM core (`state` + `wake`) is
clean and irreducible. The drift is at the edges: two **write-only / orphaned**
fields (`:seon.agent/wait-note`, `:seon.agent.turn/llm-meta`) that no reader
consumes, one **no-writer** field (`:seon.agent/parent`, aspirational), one
**name-vs-reality** mismatch (`max-turns-per-loop` is per-WAKE), and one
genuinely-good-but-confusing pair (`:seon.agent/wake` vs `:seon.agent.turn/wake`
— keep, it's a foreign key). For CRON: recommend a stored `:seon.agent/cron`
string + a pod-level scheduler that mints a wake DIRECTLY (not a synthetic
message), no auto-todo. The big missing capability is a single `agent-state`
fingerprint fn that returns stored + derived in one map; nearly everything it
needs already exists as derivations in `seon.agent.loop`.

---

## 1. Proposed coherent spec

### 1a. STORED (irreducible — survives a render, not derivable)

| Attr | Type | Why stored (not derived) |
|---|---|---|
| `:seon.agent/id` | identity `:seon.db/id` | identity |
| `:seon.agent/state` | `[:enum :idle :active :terminated]` | loop control + atomic race-safe wake check |
| `:seon.agent/wake` | `:seon.db/id` | current wake-episode token (optimistic concurrency) |
| `:seon.agent/max-turns-per-loop` *(rename candidate)* | `:int` opt | base turn budget (config) |
| `:seon.agent/purpose` | `:string` opt | human-facing headline (rendered to the welcome tile + your-entity) |
| `:seon.agent/parent` | `:seon.db/ref` opt | lineage — **currently has no writer** (see §2) |
| `:seon.agent/sessions` | `[:vector {component} :ref]` | durable turn history |
| `:seon.agent/ctx` *(rename candidate)* | `[:vector {component} :ref]` | the agent's own context sections |
| `:seon.render.live-tile/content` | symbol/hiccup | tile wiring |
| **`:seon.agent/cron`** *(NEW)* | `:string` opt | 5-field cron expr → self-wake schedule |

Per-wake/turn-stamped (own namespaces, correctly stored as coordination
metadata): `:seon.agent.turn/wake`, `:seon.agent.turn/status`,
`:seon.agent.turn/at`, prompt-chars/prompt-file, `:seon.agent.session/*`.

### 1b. DERIVED (functions of the DB at render — never stored)

All already exist in `seon.agent.loop` / `seon.ctx` / `seon.agent.todo`:

- `turns-this-wake`, `inbounds-during-this-wake`, `effective-cap`,
  `first-turn-at-this-wake` (loop.cljs)
- `activity-log` → `:seon.agent.loop/stop-reason` + `:cause` joined from
  tx-meta + `d/history` (loop.cljs 399)
- `state-as-of`, `current-ns`, `current-session`, `messages`, open-todo list
- **NEW derivations to surface (no new attrs):** `remaining-turns`
  (`effective-cap − turns-this-wake`), `last-stop-reason`,
  `last-human-inbound-at`, `cron-next-at` (next fire of `:seon.agent/cron`).

### 1c. Where CRON fits (recommendations — flag the open choices)

- **Attr name + shape:** `:seon.agent/cron` `:string` (standard 5-field cron
  expression). String, not a map — one field, reactive (edit re-reads), trivially
  fingerprintable. `cron-next-at` is DERIVED from it, never stored.
- **Scheduler location:** a pod-level ticker installed at boot in `seon.client`,
  exactly parallel to `install-wake-trigger!` — one interval (e.g. 60s) that
  queries non-`:terminated` agents whose `:seon.agent/cron` is due and fires a
  wake. It belongs in `seon.agent.loop` (alongside `wake-handler` /
  `install-wake-trigger!`) as `install-cron-scheduler!`, wired from the client
  boot path — same shape as the message trigger.
- **Own event vs synthetic message — recommend OWN event (direct wake).** A
  cron self-wake has no `from`, should not inflate `hops`, and should not appear
  in the inter-agent transcript. Have the scheduler call the SAME
  `fresh-wake!` + `set-state! :active` path the message handler uses (reuse,
  don't fork). This keeps `:seon.agent.message/*` meaning "real comms".
  - **OPEN CHOICE for the owner:** the wake provenance. `:seon.agent.loop/cause`
    is a `:seon.db/ref` (to a message); a cron wake has no message to point at.
    Either (a) extend the loop tx-meta vocabulary with a `:seon.agent.loop/cause`
    sibling like `:seon.agent.loop/cause-kind :cron`, or (b) the cron wake stamps
    only a stop-reason-style marker. The activity-log's `cause` join (loop.cljs
    444) currently pulls `:seon.agent.message/content`, so a cron wake would land
    with neither cause-string nor stop-reason — needs a third row kind. Decide
    before implementing.
- **Does cron add a todo? Recommend NO.** P4 auto-todo answers a SPECIFIC human
  ask; a recurring tick auto-minting a todo each fire would pile up open items
  the agent never closes. Let the agent decide whether the cron work warrants a
  todo. **OPEN CHOICE** — owner may want a single standing todo per cron instead.
- **Message `origin` enum:** if cron is a direct wake (recommended), `{:human
  :agent :core}` is UNCHANGED — cron is not a message. (Only if the owner picks
  the synthetic-message route does an `:cron` origin value become needed.)

### 1d. The `agent-state` fingerprint fn (proposed return shape)

Map-in `{:seon.agent/id}` → map-out, the COMPLETE snapshot from record +
cheap derivations:

```clojure
{:seon.agent/id                    "..."
 ;; --- stored ---
 :seon.agent/state                 :idle
 :seon.agent/wake                  <id>
 :seon.agent/purpose               "..."          ; opt
 :seon.agent/parent                <ref>           ; opt
 :seon.agent/cron                  "0 * * * *"      ; opt (NEW)
 :seon.agent/max-turns-per-loop    20               ; base (opt; falls to env/default)
 ;; --- derived window/loop ---
 :seon.agent.loop/effective-cap    22
 :seon.agent.loop/turns-this-wake  7
 :seon.agent.loop/inbounds-this-wake 2
 :seon.agent.loop/remaining-turns  15
 :seon.agent.loop/last-stop-reason :complete        ; from activity-log tail
 :seon.agent.loop/last-cause       "..."            ; waking msg content
 ;; --- derived context/activity ---
 :seon.agent/open-todo-count       3
 :seon.agent/last-human-inbound-at <inst>
 :seon.agent/cron-next-at          <inst>           ; derived from :cron
 :seon.agent/section-names         [:soul :namespaces :open-todos :transcript ...]
 :seon.agent/turn-count-total      141}
```

Lives next to the state helpers in `seon.agent`. Single source for the
inspector, the gym, and any "what is this agent right now" query.

---

## 2. DEAD fields (proven with grep)

| Attr · line | Verdict | Evidence (non-def references) | Recommendation |
|---|---|---|---|
| `:seon.agent/parent` (agent.cljs:103,323) | **NO WRITER** (read-only, aspirational) | Only read at `lifecycle.cljs:68` (`complete` → notify parent). Zero `assoc`/transact writers anywhere; own comment: "no spawn path sets this yet." | KEEP only if spawn lands soon; otherwise DROP until a writer exists. Owner call — it's cheap but currently un-exercised. |
| `:seon.agent/wait-note` (agent.cljs:106,324) | **ORPHANED WRITE** (written, no reader) | Written by `lifecycle/wait` (lifecycle.cljs:49). NOT in the your-entity pull (your_entity.cljs:54-63 lists state/purpose/wake/max-turns/ctx — NOT wait-note), NOT in inspector, NOT in activity-log. Only `agent_lifecycle_test.cljs:83` reads it. Docstring claims "surfaced to monitoring agents" — no such reader exists. | Either WIRE it (add to the your-entity pull / a parked-section) or DROP it and rely on `:seon.agent.loop/stop-reason :wait`. Today it pulls zero weight. |
| `:seon.agent.turn/llm-meta` (turn.cljs:82,102) | **WRITE-ONLY / ARCHIVAL** | Written (turn.cljs:395), persisted via close-turn select-keys (295), but NO reader renders it. Inspector + `seon.ctx.usage` consume `llm-usage` ONLY; `llm-meta` is never surfaced. | DROP, or keep explicitly as archival provenance with a docstring that says "stored, not rendered". Right now it silently bloats every turn datom. |

Confirmed NOT dead (checked, have live readers): `purpose` (welcome tile +
your-entity + render + live-tile), `max-turns-per-loop` (loop cap + ctx),
`llm-usage` (usage section + inspector), `llm-retries` (turn record + tests),
`prompt-file` (debug + gym driver), `stop-reason`/`cause` (activity-log +
inspector + serve), `todo/message` (✉ marker in open-todos projection,
todo/internal.cljs:48), `todo/completed-at`/`description`/`owner`/`from` (all
read in todo list/section).

---

## 3. DUPLICATE / overlapping concepts

| Concepts | Verdict | Evidence | Recommendation |
|---|---|---|---|
| `:seon.agent/state` {idle/active/terminated} vs `:seon.agent.turn/status` {running/done/error} vs `:seon.agent.loop/stop-reason` | **NOT redundant — clean 3-level hierarchy** | state = actor lifecycle (loop control); turn/status = one completion's outcome; stop-reason = why a loop RUN ended. turn.cljs:61 explicitly notes the distinction. | KEEP all three. The relationship is clear and each is irreducible. |
| `:seon.agent/wake` (agent record) vs `:seon.agent.turn/wake` (turn) | **NOT redundant — it's a foreign key** | agent/wake = the CURRENT episode pointer (mutable, re-minted each wake); turn/wake = which episode a turn BELONGED to (immutable stamp). `turns-this-wake` joins them (loop.cljs:109). | KEEP. Same token, two correct homes (current vs historical). |
| "what am I doing / why parked" spread across `purpose` + `wait-note` + `stop-reason` + self-authored ctx-section | **PARTIAL overlap → 3 homes** | purpose = durable human-facing direction; wait-note = transient last-park free-text (orphaned, §2); stop-reason = enum WHY a loop ended; ctx sections = self-authored standing notes. | Collapse the transient one: drop `wait-note`, derive "why last parked" from the activity-log's latest `:wait`/`:complete` row. Leaves a clean split: `purpose` (durable) vs ctx-sections (self-notes) vs activity-log (history). |

---

## 4. POORLY-NAMED attrs/values

| Attr · line | Issue | Proposed | Rename cost | Verdict |
|---|---|---|---|---|
| `:seon.agent/max-turns-per-loop` (agent.cljs:99) | The effective cap is per-WAKE (sliding window over a wake episode), not per-loop — "loop" and "wake" are 1:1 per run but the budget mechanism is wake-scoped; the name predates the sliding window. | `:seon.agent/base-turns-per-wake` or `:seon.agent/turn-budget` | HIGH — ~12 sites + env `SEON_MAX_TURNS_PER_LOOP` + tests + 2 ctx fns. | **OWNER CALL.** Accurate but load-bearing; a clean atomic rename is feasible on this branch. Flag, don't decide. |
| `:seon.agent/ctx` (agent.cljs:179) | Reads like "the whole context"; it actually holds the agent's OWN section overrides only. | `:seon.agent/sections` or `:seon.agent/ctx-sections` | MEDIUM — your-entity pull, add/remove/reset-ctx!, render. | Recommend rename to `:seon.agent/sections` — clearer and the verbs are already `add-section!`/`remove-section!`. Owner call. |
| `:seon.agent.loop/cause` (loop.cljs:54 ref) vs activity-entry `:cause` (loop.cljs:74 string) | One name, two types: a ref attr in tx-meta, a content STRING in the activity row. | keep ref; name the row field `:seon.agent.loop/cause-text` | LOW | Minor; tidy when touching cron provenance (which needs a cause-kind anyway). |
| message `origin` {:human :agent :core} | Complete for messages; no gap unless cron is modeled as a message (recommended NOT). | — | — | KEEP as-is given direct-wake cron. |

---

## 5. GAPS — data that would improve agent outputs (add sparingly)

| Gap | Today | Recommendation | New attr? |
|---|---|---|---|
| Agent can't see its own window budget in its entity | `effective-cap` IS surfaced in the readline/turn-header (ctx.cljs:285), but `remaining-turns` is not explicit and not in the your-entity map | Surface `remaining-turns` + `turns-this-wake` via the `agent-state` fn / your-entity section | NO — derived |
| "Why did my last loop end" not agent-facing | `stop-reason` only in inspector/serve, not in the prompt | Surface `last-stop-reason` so the agent knows it last hit cap / completed / errored | NO — derived from activity-log |
| Idleness signal for the soul's "idleness is not pause" | No `last-human-inbound-at` surfaced | Derive + surface; pairs with cron for "it's been N hours, do background work" | NO — derived from messages |
| Cron schedule + next fire invisible | no cron at all | Add `:seon.agent/cron` (§1c) + surface `cron-next-at` | YES — `:seon.agent/cron` only |
| blocked / confidence signal | none | **Recommend AGAINST a stored attr** — the agent can self-author this via a ctx-section (reactive, self-healing). Don't add stored mood/confidence state. | NO |

---

## 6. Open choices flagged for the owner (do not decide unilaterally)

1. **Cron wake provenance** — how a message-less cron wake records its cause in
   the activity log (new `cause-kind :cron` tx-meta vs a stop-reason-style
   marker). §1c.
2. **Cron → todo?** — none (recommended) vs one standing todo per cron. §1c.
3. **`max-turns-per-loop` rename** — accurate-but-load-bearing. §4.
4. **`:seon.agent/ctx` → `:seon.agent/sections` rename.** §4.
5. **`:seon.agent/parent`** — keep aspirational or drop until a spawn writer
   exists. §2.
6. **`wait-note`** — wire it (surface in your-entity) or drop in favor of the
   activity-log `:wait` row. §2/§3.
7. **`llm-meta`** — drop or keep-as-archival-with-honest-docstring. §2.
