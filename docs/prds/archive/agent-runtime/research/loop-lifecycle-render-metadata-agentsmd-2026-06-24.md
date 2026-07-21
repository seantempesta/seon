---
type: research
status: active
tags: [research, agent, flow]
---

# Loop lifecycle, render metadata, system/soul/AGENTS, and the AGENTS.md standard

## TL;DR

- **Agent loop / wake (topic 1):** The wake is **already pure DB-reactive** — a datahike `d/listen` tx-listener fires on a freshly-transacted inbound `:seon.agent.message/to` datom, reads `:seon.agent/state` from the post-commit snapshot, and starts a loop iff wakeable. No polling, no kick-atom, no function-call mousetrap. Stop policy is a single `cond` reading `:seon.agent/state` + `:seon.agent/wake` off the record. **`:seon.agent.message/handled?` is dead-but-wired: read in two negative gates, written by nothing in production `.cljs`.** It can be deleted today; route any future "don't wake" message through `origin :core` (already gated). A separate **dormant handler registry** (`seon.handler` + seeded `:wake/on-message`) never fires in the pod — surface and resolve it.
- **Render metadata (topic 2):** The owner is right on both counts. The render fn IS just a **quoted symbol stored as metadata on the schema's `:map` props** (`:seon.render/ai` / `:seon.render/html`), decomposed by `register!` into `:seon.schema/render-fn` datoms and resolved via `eval/lookup-value`. **`register-renderer!` does NOT exist in `src/`** — it is a ghost from a superseded, rejected design that lives only in stale docs. Both override paths reduce to "upsert a symbol." No new API.
- **System / soul / AGENTS order (topic 3):** The owner's "system then soul then agents" instinct is **inverted from reality and should be rejected.** The wire is two messages: `system` role = **soul** (block 1), `user` role = the assembled `ctx` whose first section is named `:system` (block 2). The naming collision (`:system` *section* vs `system` *role*) is the entire source of confusion. Soul-first is correct and already deployed. **`AGENTS.md` is a real bug**: `my.soul` looks for `AGENTS.md` (plural) but only `AGENT.md` (singular) exists, and `AGENT.md` is Claude-Code-subagent/JVM-track content that is wrong for a pod agent anyway.
- **AGENT.md vs AGENTS.md (topic 4):** **AGENTS.md (plural) is the settled industry standard** (OpenAI + Amp/Sourcegraph converged May 2025; stewarded by the Agentic AI Foundation under the Linux Foundation; 60+ tools, 60k+ repos). Singular `AGENT.md` is a deprecated alias. Recommendation: rename root `AGENT.md` → `AGENTS.md`, optional back-compat symlink. Claude Code reads `CLAUDE.md`, not AGENTS.md, so keep CLAUDE.md as-is (it can `@AGENTS.md`-import or symlink for portability).

---

## 1. Agent loop lifecycle + wake

### 1.1 Verdicts the owner asked for

1. **The wake IS DB-reactive already.** A freshly-transacted inbound `:seon.agent.message/to` datom fires a datahike `d/listen` tx-listener (`fsm/wake-handler`), which reads the agent's `:seon.agent/state` from the post-commit DB snapshot and starts a loop iff wakeable. No polling, no kick-atom, no function-call mousetrap.
2. **There is NO production writer of `handled? = true` anywhere in the `.cljs` codebase.** It is read in two places (the wake gate + the transcript gate) but never set. It is a tx-hook hook-point for a downstream chat-control feature that does not exist yet.
3. **There is a genuine hidden second wake system**: a DB-stored handler registry (`seon.handler` + the seeded `:wake/on-message` entity pointing at `seon.handlers.wake/wake-on-message`). It is **dormant** — no `.cljs` dispatcher reads it; its dispatcher "lives in `seon.runtime`" which is CLJ-only (paused JVM track). It is seeded at boot and rendered in the inspector, but never fires a wake.
4. The "messages get replied to" nightmare was already **deleted** by the FSM refactor (the `reply!` verb, `unanswered-live-inbound?` reply-accounting, and the `!kick-scheduled` atom are gone). `handled?` is the last vestige of that era kept "just in case."

### 1.2 Every way the loop TERMINATES or PARKS

The whole stop policy is the `cond` in `seon.agent.fsm/run-loop!` (`src/seon/agent/fsm.cljs:148-216`). The loop re-reads `{:seon.agent/state, :seon.agent/wake}` from the agent record **every iteration** — that record IS the coordination truth.

| # | Trigger (file:line) | Exact condition | What it writes to DB | Final state |
|---|---|---|---|---|
| A | External state change `fsm.cljs:163` | `(not= :active state)` at top of loop | nothing (READS) | whatever was written |
| B | Superseded wake `fsm.cljs:166` | `(not= wake my-wake)` — newer wake token won | nothing | winning loop's `:active` |
| C | Per-loop cap `fsm.cljs:170` | `turns-this-wake ≥ effective-cap` (base + inbounds, derived via datalog count, `fsm.cljs:68-140`) | finally → `:idle` | `:idle` |
| D | Turn error `fsm.cljs:190` | turn `:seon.agent.turn/status :error` | finally → `:idle` | `:idle` |
| E | Lifecycle verb fired `fsm.cljs:194-196` | after turn, `state ∉ {:active :idle}` | the **verb** wrote it | `:waiting`/`:completed`/`:terminated` |
| F | No actionable forms (quiet) `fsm.cljs:198-202` | `eval-count = 0` for `empty-streak ≥ 2` (2-turn thinking guard) | finally → `:idle` | `:idle` |
| G | Loop body threw `fsm.cljs:206-208` | `catch :default` → `:halt-throw` | finally → `:idle` | `:idle` |

The **lifecycle verbs** (explicit agent-driven exits), in `agent.cljs`:

- **`(agent/wait note)`** `agent.cljs:520-536` → `{:seon.agent/state :waiting, :seon.agent/wait-note note}`. Wakeable. Returns `:waiting`.
- **`(complete result)`** `agent.cljs:538-560` → `{:seon.agent/state :completed}`; if `:seon.agent/parent` set, also `message!`s the result to the parent (waking it via the normal gate). Wakeable. Returns `:completed`.
- **`(terminate id)`** `agent.cljs:562-573` → `{:seon.agent/state :terminated}`. The one UNWAKEABLE state. Orchestrator-only; an agent never terminates itself.

**Design fact:** the loop OWNS `:seon.agent/state` writes for the implicit exits (A/C/D/F/G all land `:idle` via the single `finally` block `fsm.cljs:209-216`). The turn (`turn.cljs`) NEVER writes `:seon.agent/state` (only `:seon.agent.turn/status`) — see the comment at `turn.cljs:243-252`. The verbs are the only agent-facing state writers. State lives on the record, the loop reads it, the verbs write it.

Nuance: at `fsm.cljs:198` the zero-forms halt uses `eval-count` = `n-ok + n-fail` (`turn.cljs:343`) — attempted forms. A turn where every form *errored* is NOT a quiet halt; it recurs so the next turn shows the errors. Correct.

### 1.3 The WAKE path — is it DB-reactive? (yes) + every stateful holder

The live wake path is **one datahike `d/listen` tx-listener per agent. It IS DB-reactive.**

Wiring chain:

- `seon.client/boot-one-agent!` (`client.cljs:1924-1959`) calls `fsm/install-wake-trigger!` per armable agent at boot; `client` re-arm (`client.cljs:1875-1922`) re-installs on hot reload.
- `fsm/install-wake-trigger!` (`fsm.cljs:293-312`) registers `(wake-handler input)` via `db/listen!` under stable per-agent key `[:seon.agent/user-message-trigger id]` (idempotent: unlistens prior key first).
- `db/listen!` (`db.cljs:904-924`) → `d/listen conn k (wrap-listen-handler …)`. `wrap-listen-handler` (`internal.cljs:1473-1493`) builds `{:seon.db/db, :seon.db/db-before, :seon.db/datoms, :seon.db/attr-index}` and calls the handler **on every transact** (datahike fires post-commit on the local pod conn).
- `wake-handler` (`fsm.cljs:229-291`): filters `(:seon.agent.message/to attr-index)` added datoms passing `agent/inbound-msg-datom?`; partitions out hop-exhausted ones (loud `console.error`, no wake — `fsm.cljs:253-261`); for the waking set reads `:seon.agent/state` from the snapshot. If `state ∈ {:active :terminated}` → **skip** (`:active` = running loop's sliding cap picks it up; `:terminated` = unwakeable). Else (`:idle`/`:waiting`/`:completed`) → mint a fresh wake, set `:active`, and `run-loop!` stamped with it.

So the trigger is a datom-firing listener, not a function-call chain. An idle agent's DB shape: `{:seon.agent/id _, :seon.agent/state :idle/:waiting/:completed, :seon.agent/wake <last token>}`. A new inbound datom is the only thing that re-arms a loop.

**Concurrency = optimistic via the DB, no atom/CAS** (`fsm.cljs:13-21,229-235`): two simultaneous idle wakes both write `:active` + their own `:seon.agent/wake` token; last-writer-wins; the losing loop re-reads a different wake at `fsm.cljs:166` (case B) and bails.

**Every stateful holder in the wake + loop path (audited):**

- `db/*conn*` — datahike connection (genuinely-stateful runtime artifact, allowed). `internal.cljs` `*conn*` dynamic.
- `turn.cljs:152-158` **`!sessions-opened-this-run` (atom #{})** — which session ids THIS pod process opened, so a resumed agent gets a fresh session per boot. NOT wake/stop coordination — a per-process session-freshness marker; not derivable from the DB alone, so defensible.
- bootstrap `compile-state` `defonce` (CLJS compiler) — runtime artifact, allowed.
- `client.cljs` `!agent-conn` atom — the boot conn holder, runtime artifact.
- `js/setTimeout 0` in `wake-handler` (`fsm.cljs:278`) — breaks the AsyncLocalStorage scope so the loop re-enters `with-agent`; not state, but the one piece of wiring cleverness in the path.

**No atom, no volatile, no registry holds wake/stop state.** The stop policy and wake gate read entirely from `:seon.agent/state`, `:seon.agent/wake`, and `:seon.agent.message/*` datoms. This part is already exactly what the owner wants.

### 1.4 What `:seon.agent.message/handled?` ACTUALLY does

**Schema:** `message.cljs:53` `(register! :seon.agent.message/handled? :boolean)`; entity-kind marks it `{:optional true}` (`message.cljs:85`) — "STORED only when true, absent = live/unconsumed, never stored as false" (`message.cljs:50-53`).

**Readers — exactly two, both as a negative gate `(not (true? …))`:**

- (a) **The wake gate** — `agent/inbound-msg-datom?` `agent.cljs:418`. A `handled? = true` message does not pass the gate, so does not wake an idle agent.
- (b) **The transcript gate** — `ctx/transcript/inbound-msg?` `transcript.cljs:101` (a duplicated local copy; `TODO unify` at `transcript.cljs:84` because requiring `seon.agent` back would cycle). A `handled?` message is not rendered as an inbound line.

**Writers — ZERO in production `.cljs`.** The only `handled? = true` writes are in `test/seon/agent_loop_test.cljs.disabled`. The schema comment (`message.cljs:48-53`) describes the INTENDED writer — a tx-hook (e.g. a downstream `/persona` chat-control) that sets it true IN THE SAME TX that processes the command, so the message does NOT wake the agent. **That tx-hook does not exist in the active codebase.**

The three possible roles resolve to:

- (a) "addressed/replied" signal — **NO.** Not a reply/answered marker (that concept was deleted; see 1.6). It means "consumed by a deterministic tx-hook."
- (b) wake gate / "don't re-process" — **YES in code shape, but vacuous in practice** because nothing sets it.
- (c) anything else — only the transcript-suppression mirror of (b).

**It is dead-but-wired: read in two gates, written nowhere.**

### 1.5 THE KEY QUESTION — can the gate + "don't re-process twice" be DERIVED (no `handled?`, no atoms)?

**It already is, except for the vacuous `handled?` clause.** Two conflated concerns:

**Concern 1 — "don't re-process the same message twice."** NOT solved by `handled?` today and never was. It is solved structurally by the **wake-episode token + sliding cap**:

- A wake mints a fresh `:seon.agent/wake` token (`agent/fresh-wake!`). Each turn stamps it (`:seon.agent.turn/wake`, `turn.cljs:269-283`).
- The loop runs until cap or no-forms; the agent SEES all inbounds in its transcript (`transcript/inbound-messages`, `transcript.cljs:119-150`) — it does not "consume" them one-by-one.
- "Don't re-wake while running" = the `:active`-skip in `wake-handler` (`fsm.cljs:266`): an inbound during `:active` doesn't start a new loop; the running loop's `effective-cap` grants it +1 turn (`fsm.cljs:104-140`). **Pure DB derivation** (count of turns vs base+inbounds, all datalog).

There is **no per-message "processed" flag anywhere** and the system works. The closest thing is the wake token, which is legitimately stored coordination metadata (episode identity, like a CAS stamp — not derivable).

**Concern 2 — the wake gate.** Strip `handled?` and the gate is: wake iff an added `:seon.agent.message/to me` datom exists where `from ≠ me` ∧ `origin ∈ {:human :agent}` ∧ `hops < hop-cap`. All four surviving clauses are pure functions of stored datoms — already DB-reactive, no atom.

**What breaks if you delete `handled?` today:** essentially nothing in the active system, because nothing sets it. The two `(not (true? (:…/handled? msg)))` clauses (`agent.cljs:418`, `transcript.cljs:101`) become always-true and can be dropped, with the schema register (`message.cljs:53`) and the entity-kind optional entry (`message.cljs:85`). The disabled-test refs go (already disabled).

**The ONE thing `handled?` was reserving:** a way for a deterministic tx-hook (e.g. a future `/persona`) to say "I consumed this message in-tx; do NOT wake." The **DB-reactive replacement** is cleaner and needs no boolean flag: make those control messages carry **`:seon.agent.message/origin :core`** (the gate already excludes `:core` — `agent.cljs:417`, `fsm.cljs:126`). A slash-command is substrate-originated, not human-conversational — `:core` is the honest provenance. Zero new attrs, zero new gate clauses, self-healing (a `:core` message never wakes, never renders as inbound).

So the **smallest correct replacement for `handled?`: delete it; route any future "don't wake" message through `origin :core`** — collapsing two suppression mechanisms (`origin` + `handled?`) into one (`origin`). Caveat: `handled?` is "reversible — retract to un-consume" (`message.cljs:52`); a `:core` message is permanently non-waking, which is the right semantics for a slash-command echo, and since nothing consumes `handled?` today there is no behavior to preserve.

### 1.6 The "messages get replied to" nightmare — what it was, why it was fragile

**Already escaped** — the FSM refactor (commits `612e17e…0126b7c` on this branch) deleted it (history: `docs/prds/agent-runtime/agent-fsm-redesign-2026-06-23.md`). The OLD system tracked reply/handled state via:

- **`reply!`** — a dedicated verb (deleted; replaced by `message/user` / `message/agent`). The stop policy was a reply count — run until a user-facing reply landed.
- **`unanswered-live-inbound?` / `live-inbound-count` / `user-facing-reply-count` / `query-count` / `task-in-progress?` / `inbox-count`** — a cluster of derived "answer-accounting" predicates (design doc `:140,:442`). The loop halted `:replied` the moment a message got a reply strictly after it — the literal "messages get replied to" machinery.
- **`!kick-scheduled` atom** (design doc `:39`) — a process-global atom gating whether a wake/kick was already scheduled. **The fragile stateful wiring** — could desync from DB reality (the exact anti-pattern the owner hunts).
- self→self note messages + an XML transcript — also deleted.

Why fragile: the stop condition was a *relationship between two message rows* PLUS an atom guarding re-entry → three coordination surfaces (reply-accounting queries, the atom, `handled?`), one of them an atom. The FSM refactor replaced all of it with: state on the record, loop reads it, verbs write it, the cap is a derived count, wake is a tx-listener — a single coordination surface (the DB). `handled?` is the last vestige still wired into the live gates (read-only, never written). Removing it finishes the consolidation.

### 1.7 Recommendations (topic 1)

1. **Delete `:seon.agent.message/handled?`** — schema (`message.cljs:53,85`), the two gate clauses (`agent.cljs:418`, `transcript.cljs:101`), and the disabled-test refs. Route any future "consumed, don't wake" message through `origin :core` (already gated). Net: one fewer suppression mechanism, fully DB-derived.
2. **Decide the fate of the dormant handler registry** (`seon.handler` + `seon.handlers.wake/wake-on-message` + the seeded `:wake/on-message` entity at `client.cljs:2024-2032`). In the active CLJS pod it never fires — no `.cljs` dispatcher reads `query-handlers` for dispatch (only the inspector reads it for *display*, `inspect.cljs:106`). The real wake is the `db/listen!` closure. Either wire the dispatcher (if registry-based is the future) or **delete the registry seed + `seon.handlers.wake` from the CLJS boot** so there's ONE wake path. Today an inspecting human sees a `:wake/on-message` handler entity that does nothing.
3. **Unify the duplicated inbound predicate** — `transcript/inbound-msg?` (`transcript.cljs:88-102`) is a hand-copy of `agent/inbound-msg-datom?` kept only to dodge a require cycle (`TODO` at `transcript.cljs:84`). Two copies of the wake rule will drift. Move the gate to a cycle-free ns (e.g. `seon.agent.message` or a tiny `seon.agent.gate`) and have both call it.

---

## 2. Render-fn as schema metadata

### 2.1 TL;DR

The owner is right on both counts, and the current code already implements his model — there is no `register-renderer!` ceremony in `src/`:

1. **The render fn IS just a symbol**, declared as **metadata on the schema's `:map` properties** (`:seon.render/ai`, `:seon.render/html`), stored verbatim. `register!` decomposes it into a `:seon.schema` DB entity (`:seon.schema/render-fn` / `:seon.schema/render-html-fn`), resolved at render time via `eval/lookup-value`.
2. **`register-renderer!` does NOT exist** anywhere in `src/`. It appears only in stale docs (`docs/prds/spec-driven-rendering/prd.md`, `docs/prds/namespace-ui/archive/research/*`) describing an old, rejected design with a separate `*renderers` atom. The current system already superseded it.
3. Both override paths reduce to **"upsert a symbol."** No new mechanism is warranted.

The PRD line the owner reacted to ("requires explicit `register-renderer!` calls") describes the system *before* the schema-property pattern landed — the problem that was already solved, not current reality.

### 2.2 How the schema-default renderer works today

The attrs that store a kind's render fn — `src/seon/schema.cljc:158-161`, registered as plain `:symbol`-typed attrs:

```clojure
(swap! *schemas assoc :seon.schema/render-fn :symbol)        ; the :seon.render/ai symbol
(swap! *schemas assoc :seon.schema/render-html-fn :symbol)   ; the :seon.render/html symbol
```

A renderable entity kind declares its renderers **inline in the `:map` schema's properties map**. Live example, `src/seon/agent.cljs:254-257`:

```clojure
(schema/register! :seon.eval
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.eval/render-ai      ; quoted symbol, schema metadata
         :seon.render/html 'seon.handlers.eval/render-html}
   [:seon.eval/id     :seon.eval/id]
   ...])
```

Same pattern for `:seon.fn`, `:seon.schema`, `:seon.ns`, `:seon.agent` (`agent.cljs:270-317`). The render fn is a qualified symbol in the schema's own props map — precisely "metadata on the schema definition."

**Set at `register!` time, no other step.** `register!` (`schema.cljc:431-444`) stores the form verbatim into `*schemas` (`swap! *schemas assoc k (with-entity-id-attr v)`). No separate renderer-registration call. At agent boot, `seon.client/start-agent!` calls `schema/all-entity-schemas-tx-data` (`schema.cljc:548-554`) → `entity-schema-tx-data` per kind (`schema.cljc:502-533`), which pulls the symbols out of the schema props and emits DB datoms:

```clojure
render-ai   (:seon.render/ai props)
render-html (:seon.render/html props)
...
(cond-> [[:db/add tid :seon.schema/key k]
         [:db/add tid :seon.schema/id-attr id-attr]]
  render-ai   (conj [:db/add tid :seon.schema/render-fn render-ai])
  render-html (conj [:db/add tid :seon.schema/render-html-fn render-html]))
```

Stored shape (one `:seon.schema` row per kind):

```clojure
{:seon.schema/key             :seon.eval
 :seon.schema/id-attr         :seon.eval/id
 :seon.schema/render-fn       seon.handlers.eval/render-ai     ; symbol
 :seon.schema/render-html-fn  seon.handlers.eval/render-html   ; symbol
 :seon.schema/required-attrs  [:seon.eval/id :seon.eval/source :seon.eval/ok? :seon.eval/at]}
```

**Resolution at render time:** `renderable-kinds` (`render.cljs:202-240`) datalog-queries these rows. `entity-render-slot` (`render.cljs:300-318`) does the two-step resolution; `ai-render`/`html-render` (`render.cljs:158-183`) resolve the symbol via `eval/lookup-value` and fall through to `seon.render.default/*` on a miss. Dispatch is 100% symbol-driven — no fn objects are ever stored (they can't survive the form round-trip; platform-law comment at `render.cljs:122-126`).

### 2.3 Is there a `register-renderer!` fn? — Confirmed NO

`grep -rn "register-renderer" src/` → **zero hits.** Only docs:

- `docs/prds/spec-driven-rendering/prd.md:18` describes it as the *friction to remove*; `:469` lists "Remove old manual registry (`*renderers` atom, `register-renderer!`, `get-renderer`, `clear-renderers!`)" as a completed-design goal.
- `docs/prds/namespace-ui/archive/research/*` show it as an old proposed API.

The owner's instinct is correct — it's a ghost from a superseded design.

### 2.4 The two override paths — both "upsert a symbol"

**(a) Per-ENTITY slot override** — transact a symbol onto a specific entity. `entity-render-slot` (`render.cljs:310-312`) checks the per-entity attr first:

```clojure
(let [attr (case surface :html :seon.render/html :ai :seon.render/ai)]
  (or (some->> (get entity attr) (db/decode-edn-value attr))   ; per-entity override WINS
      ... kind default ...))
```

So an agent overrides one entity's renderer by upserting onto it:

```clojure
(db/transact! :seon [{:seon.eval/id "abc...", :seon.render/html 'my.ns/custom-card}])
```

`:seon.render/ai`/`:seon.render/html` are registered as the value-or-symbol shape (`render.cljs:80,87`). This is the live-tile mechanism (`render-agent-tile` docstring, `render.cljs:377-388`).

**(b) Per-SCHEMA (kind) default** — sets the default for all instances. Render-side reads the `:seon.schema/render-fn` datom. Two equivalent upserts:

- **Option 1 — re-register the schema (canonical):** `:seon.schema/key` is the identity attr, so calling `register!` again with the new symbol in props re-runs `entity-schema-tx-data` → re-emits the datom → identity upsert replaces the old row. This is "redefine = upsert, no override/reconcile." Keeps the in-memory `*schemas` atom and the DB in sync — the honest path.
- **Option 2 — direct datom upsert onto the `:seon.schema` row:**

  ```clojure
  (db/transact! :seon [{:seon.schema/key :seon.eval
                        :seon.schema/render-fn 'my.ns/better-eval-ai}])
  ```

  Works because `renderable-kinds` reads the symbol from the DB. **Caveat:** the in-memory `*schemas` props then disagree with the DB row until the next `register!`; Option 2 won't survive a re-index from source. Prefer Option 1.

**Where the schema definition lives:** both — the code-side `*schemas` atom (`schema.cljc:39`, the seed/source of truth) AND a queryable/transactable `:seon.schema` DB entity (decomposed at boot via `all-entity-schemas-tx-data`, identity-keyed on `:seon.schema/key`). The renderer reads the DB rows (`render.cljs:209-210`: "reads schemas from core state instead of walking the in-memory `*schemas` atom").

### 2.5 Recommendation (topic 2): NO `register-renderer!`. Keep `schema/register!`

The simplest design with zero new ceremony already exists and is deployed:

- **Per-kind default:** declare `:seon.render/ai`/`:seon.render/html` symbols in the `:map` schema's props and call `schema/register!`. To change it, re-`register!` (upsert via `:seon.schema/key`).
- **Per-entity override:** `db/transact!` a symbol onto the entity's `:seon.render/ai`/`:seon.render/html` attr; wins over the kind default in `entity-render-slot`.

Both are upsert-a-symbol. A `register-renderer!` would (1) reintroduce the friction the PRD already removed, (2) bifurcate the source of truth (parallel `*renderers` atom vs. schema props), and (3) violate "code as data — one mechanism."

**Action item:** the stale `register-renderer!` language in `docs/prds/spec-driven-rendering/prd.md:18` and `namespace-ui/archive/research/*` is what's misleading — they describe the pre-schema-property design. Update or archive them.

Key citations: `src/seon/schema.cljc:158-161,431-444,502-533`; `src/seon/agent.cljs:254-317`; `src/seon/render.cljs:158-183,202-240,300-318`; `grep -rn "register-renderer" src/` → 0 hits.

---

## 3. System / soul / AGENTS — order, content, recommendations

### 3.1 True top-to-bottom order as the LLM receives it TODAY

There are exactly **two blocks** wired to every LLM call (`src/seon/ai/openai_compat.cljs:196-197`):

```clojure
:messages [{:role "system" :content (ai/effective-system-prompt request)}
           {:role "user"   :content ctx}]
```

| # | Block | Role | Source | Notes |
|---|-------|------|--------|-------|
| **1** | **SOUL** (identity) | `system` | `my.soul/system-prompt-text` → reads `SOUL.md` (+ `AGENTS.md`) live each call (`src/my/soul.cljs:80-92`), via `seon.ai/effective-system-prompt` (`src/seon/ai.cljs:357-368`) | OUTSIDE the composer. Falls back to `fallback-system-prompt` (`ai.cljs:334-336`) when no file. |
| **2** | **The assembled context** (`ctx`) | `user` | `seon.ctx/assemble-context` — all `:seon.ctx` sections by `:seon.ctx/priority` | INSIDE the composer. Begins with the `:system` section. |

Within block 2, sections render smallest-priority-first (`core-default-ctx`, `ctx.cljs:1522-1545`):

1. **`:system`** (priority 10) → `system-text` (`ctx.cljs:762-1019`) — universal REPL mechanics + COMMON DB OPS + standing teachings
2. `:namespaces` (20)
3. `:your-entity` (30)
4. `:live-tile` (35)
5. `:warnings` (40)
6. `:open-todos` (45)
7. `:relevant-source` (48, env-gated off)
8. `:inventory` (97)
9. `:transcript` (100, last — the comment-block REPL: masthead + turns + readline)

**The owner's instinct is INVERTED from reality.** The owner said "system first, then soul, then agents." In truth: **soul is first** (it is literally the `system` role message, block 1), and the thing called `:system` (`system-text`) is *second*, the opening section of the user message. The naming collision is the source of confusion: the `system` *role message* = soul; the `:system` *section* = universal teachings. AGENT.md reaches the LLM **not at all** (see 3.2b).

The debug/inspector preview (`seon.agent.inspect/ctx-preview`, `inspect.cljs:59-96`) reconstructs this faithfully — `soul + soul-boundary + ctx` via `ai/debug-full-prompt` (`ai.cljs:370-383`), using the same `effective-system-prompt` the adapters call, so the preview is byte-identical to the wire. Boundary marker: `;; ──── ↑ system message (soul) │ ↓ context (:seon.ai/ctx) ────` (`ai.cljs:346-347`).

### 3.2 Content of each, with overlap/staleness flags

**Block 1a — SOUL.md** (`SOUL.md`, 106 lines): pure identity prose. Three principles — **Loyalty** (one human, no users, no performing), **Adaptability** (form fits the person; Primer/Nell metaphor), **Growth** (extend the runtime, idleness is wasted, queryable memory). Then "How you come to know your human / remember / reflect / act / the bond grows / what you hold to / Coda." Well-written, on-thesis. It is *philosophy*, not mechanics — correctly so (`soul.cljs:11-14` states identity-only by design).

**Block 1b — `AGENTS.md` — DOES NOT EXIST (BUG).** `my.soul/agents-md-path` is hardcoded `"AGENTS.md"` (plural, `soul.cljs:33-36`) and `soul-files` filters by `file-exists?` (`soul.cljs:65-78`). The repo only has `AGENT.md` (singular). So:

- `soul-files` returns `["SOUL.md"]` only; **AGENTS.md is silently never read into any agent's prompt.**
- `AGENT.md` (root) is NOT pod identity — it is the **Claude Code subagent** instructions ("You are a subagent… scope is sacred… use Gemini… don't `pkill`"). Consumed only on the **paused JVM track** by `seon.ai/claude.clj:559` (`agent-instructions-path "AGENT.md"`). Dumping it into a pod agent's system prompt would be actively wrong — it speaks to a Claude-Code subagent, references `(user/reset)`, nREPL ports, `data/datahike/`, kaocha, the Observatory UI.

**This is the headline finding for the overhaul:** the owner's "agents" maps to a file that (a) doesn't exist under the name the code looks for, and (b) under the name it does exist (`AGENT.md`) is the wrong document for the pod. There is no pod-side "agents/work-instructions" identity file today. Either create a real `AGENTS.md` (pod work-instructions, distinct from the subagent `AGENT.md`) or drop the `agents-md-path` mechanism. The current code carries dead intent.

**Block 2 §1 — `system-text`** (`ctx.cljs:762-1019`, ~250 lines): the universal REPL mechanics, all as `;;;` comments + an inline COMMON DB OPS cheat-sheet (real `register!`/`transact!`/`query`/`pull` forms). Covers: you-are-a-REPL, JS interop / no-JVM, live-context-system, transcript-is-an-eval'able-session, eval mechanics, think-in-comments / no fences, report-the-value-you-computed, `result/<id>` vars, state-across-turns, errors-are-values, the rendering system (render twins), the shared store + two laws, COMMON DB OPS, "namespaces below are real code," STANDING TEACHINGS, MESSAGING + LIFECYCLE verbs, turns-are-precious, sliding-window cap, hiccup splicing, write-forms-read-values, grade-your-facts. Dense and current. `def`, not a fn — byte-identical cache-prefix across the cluster (`ctx.cljs:768-772`).

**Overlap / contradiction / staleness flags:**

- **CONTRADICTION (real): `AGENTS.md` filename mismatch** — `soul.cljs` looks for `AGENTS.md`; only `AGENT.md` exists. Dead branch; the docstrings (`soul.cljs:5,20-21,65-71`) describe a file never loaded. Fix the filename OR the docstrings, and decide what (if anything) the second pod identity file should be.
- **OVERLAP (soul ↔ system-text), mostly fine by design** — both touch "grow the runtime," "queryable memory," "store what matters," but at different altitudes: soul = *why* (motivation), system-text = *how* (the exact forms). Intended division (`soul.cljs:11-14`). The one thing to watch: soul's "you are not paused / write the function yesterday wanted / fork a copy and reflect" (idleness/reflection) has **no mechanical counterpart** in system-text — an agent may try to "reflect"/"fork" with no verb to do it. Either soul over-promises or system-text is missing the idle-loop teaching. Flag.
- **No soul↔system-text behavioral contradiction** — both say no-performing/answer-when-you-know; consistent.
- **Naming smell (the owner's confusion, codified):** the section literally named `:system` is NOT the system-role message (which is the soul). Two different things both called "system." Worth renaming the `:system` section (e.g. `:repl-manual` / `:mechanics`).

### 3.3 Does the content argue for an order? Recommended order

**The content strongly argues identity (soul) FIRST, mechanics (`system-text`) SECOND — exactly what's deployed.**

- The soul opens "You are Seon… go look at what your human is doing" and answers *who am I and who do I serve* — the frame everything else is read through. Mechanics before identity would teach "how to push a Datalog form" before *whose* life the runtime exists for. Soul explicitly defers mechanics to the core (`soul.cljs:13-16`) — soul is the premise, system-text the toolkit applied under that premise. Premise before toolkit.
- Also forced by transport: the soul is the only thing that can be the `system` role (live, user-editable identity); `system-text` is a cacheable shared `def` belonging in the cached user-message prefix. The split is correct.

So the **PRD's target order (soul+AGENTS → :system → namespaces → inventory → transcript → todos → repl-prompt) is right on the soul-then-system axis**, and the owner's "system first then soul" should be **rejected** — it inverts the correct, deployed order. The confusion is purely the `:system`-name collision.

Note: the PRD's "inventory → transcript → todos" tail does not match the deployed priorities — deployed is todos(45) → inventory(97) → transcript(100, always last). The **transcript-last invariant is load-bearing** — the readline must be the final bytes so the model's reply is the next REPL input (`system-text` ~ll.787-789). Keep transcript last; the middle sections are the negotiable part.

### 3.4 Recommendations (topic 3)

**Order:** keep as-is — `soul` (system role) → `:system`/system-text → namespaces → your-entity → live-tile → warnings → open-todos → relevant-source → inventory → **transcript (always last)**. Reject "system before soul."

1. **Fix the AGENTS.md bug** (`src/my/soul.cljs:33-36`). Either (a) rename the code constant to a file that exists / create a real pod-side `AGENTS.md` of *pod work-instructions* (NOT the subagent `AGENT.md`), or (b) delete `agents-md-path` and the multi-file plumbing and make soul = SOUL.md only. Today the code is half-built and the docstrings lie. Do NOT point it at the existing `AGENT.md` — that is Claude-Code-subagent instructions for the paused JVM track, wrong for a bonded pod agent.
2. **Rename the `:system` section** (`ctx.cljs:1523`, `system-section`) to e.g. `:repl-manual` / `:mechanics` so it stops colliding with the system-*role* message (the soul). This single rename dissolves the "system vs soul vs agents" confusion.
3. **Reconcile soul's idle/reflect promises with mechanics** — if no verb exposes "fork a copy / write the function yesterday wanted / use idle time," add a mechanical note in `system-text` or soften the soul. Flag, not fix (out of scope).
4. **SOUL.md and system-text are both current and well-written** — no rewrite needed; the overlap is intentional (why vs how). The "written quickly" worry applies to the plumbing/filenames, not the prose.

Files cited: `src/seon/ai/openai_compat.cljs:196-197`; `src/seon/ai.cljs:334-336,346-347,357-383`; `src/my/soul.cljs:33-36,65-92`; `src/seon/ctx.cljs:762-1019,1522-1545`; `src/seon/agent/inspect.cljs:59-96`; `SOUL.md` (1-106); `AGENT.md` (root, = JVM-track subagent instructions, `src/seon/ai/claude.clj:559`); **`AGENTS.md` does not exist.**

---

## 4. AGENT.md vs AGENTS.md — industry-standard verdict

**AGENTS.md (plural) is the industry standard.**

**The naming debate is settled.** In May 2025 two competing proposals emerged: Sourcegraph/Amp pushed **AGENT.md (singular)**; OpenAI pushed **AGENTS.md (plural)** (they owned the `agents.md` domain). Amp conceded and redirected their `agent.md` domain to the plural standard. As of 2026, **AGENTS.md (plural) is the established convention.** Singular AGENT.md is now a deprecated/legacy alias — the official spec references it only in a migration note ("rename existing files to AGENTS.md and create symbolic links for backward compatibility").

**Who backs it:**

- Published spec/site: [agents.md](https://agents.md/) — official; [GitHub: agentsmd/agents.md](https://github.com/agentsmd/agents.md)
- Stewardship: Agentic AI Foundation, under the Linux Foundation
- Origin: collaboration across OpenAI, Amp/Sourcegraph, Google, Cursor, Factory
- 60+ tools read it: OpenAI Codex, Google Jules + Gemini CLI, Cursor, Aider, Zed, GitHub Copilot/VS Code, Devin, JetBrains Junie, Warp, Windsurf, Factory, RooCode, Augment, others
- Adoption: 60k+ open-source repos (up from ~20k in mid-2025)

**Claude Code caveat (relevant to this repo):** Claude Code does **not** read AGENTS.md natively — it reads **CLAUDE.md**. Standard pattern: keep AGENTS.md as the shared source of truth and make CLAUDE.md a thin layer that imports it (`@AGENTS.md` inside CLAUDE.md) or symlink `ln -s AGENTS.md CLAUDE.md`. This repo already has a strong CLAUDE.md, so AGENTS.md would be the cross-tool-portable mirror.

**Recommendation for this repo:** rename the existing root **`AGENT.md` → `AGENTS.md`** (plural is the standard; this repo's file is the legacy singular form). If broad backward compatibility matters, add a symlink `AGENT.md -> AGENTS.md`, but the canonical file should be plural.

Sources:

- [AGENTS.md (official site)](https://agents.md/)
- [agentsmd/agents.md (GitHub)](https://github.com/agentsmd/agents.md)
- [Agent.md vs Agents.md: How the Industry Settled on One Standard](https://agentsmd.io/agent-md-vs-agents-md)
- [AGENTS.md Emerges as Open Standard for AI Coding Agents — InfoQ](https://www.infoq.com/news/2025/08/agents-md/)
- [AGENTS.md vs CLAUDE.md: Does Claude Code or Codex Read Both? — agyn.io](https://agyn.io/blog/claude-md-agents-md-compatibility)
- [AGENTS.md becomes the convention — pnote.eu](https://pnote.eu/notes/agents-md/)

---

## Recommendations / decisions for the owner

1. **(Loop) Delete `:seon.agent.message/handled?`** — it is dead-but-wired (read in 2 gates, written nowhere in production). Remove the schema (`message.cljs:53,85`) and the two `(not (true? …))` gate clauses (`agent.cljs:418`, `transcript.cljs:101`). Route any future "consumed, don't wake" message through `origin :core` (already gated). **Decision: approve deletion.**
2. **(Loop) Resolve the dormant handler registry** (`seon.handler` + `seon.handlers.wake/wake-on-message` + seeded `:wake/on-message` at `client.cljs:2024-2032`) — it never fires in the pod; the real wake is the `db/listen!` closure. **Decision: wire the dispatcher OR delete the registry seed from CLJS boot — pick one wake path.** (Recommend delete; the listener is the live mechanism.)
3. **(Loop) Unify the duplicated inbound predicate** — move `agent/inbound-msg-datom?` to a cycle-free ns and have `transcript/inbound-msg?` (`transcript.cljs:88-102`) call it. Two copies of the wake rule will drift.
4. **(Loop, no action) The wake is already pure DB-reactive** (datahike `d/listen` + state on the record + episode-token sliding cap). No atoms/flags hold wake/stop state. Keep it.
5. **(Render) Do NOT add `register-renderer!`** — it does not exist in `src/` and would reintroduce removed friction. Keep `schema/register!` with `:seon.render/ai`/`:seon.render/html` symbols in props. Per-kind change = re-`register!` (upsert via `:seon.schema/key`); per-entity = transact a symbol onto the entity. **Decision: update/archive the stale `register-renderer!` docs** (`docs/prds/spec-driven-rendering/prd.md:18`, `namespace-ui/archive/research/*`).
6. **(Order) Reject "system then soul then agents."** Deployed order is correct: **soul (system role) first, `:system`/system-text section second, transcript always last.** No reordering.
7. **(Order) Rename the `:system` context section** to `:repl-manual` / `:mechanics` to end the `:system`-section vs `system`-role collision that produced the confusion.
8. **(AGENTS.md) Fix the bug + adopt the standard:** `my.soul` looks for `AGENTS.md` but only `AGENT.md` exists. Industry standard is plural `AGENTS.md`. **Decision needed:** create a real pod-side `AGENTS.md` (pod work-instructions, distinct content from the subagent `AGENT.md`) and point `my.soul/agents-md-path` at it — OR delete the `agents-md-path` plumbing and make soul = SOUL.md only. Separately, rename the root `AGENT.md` → `AGENTS.md` (optional `AGENT.md` symlink) for cross-tool portability; CLAUDE.md stays primary for Claude Code. Do NOT feed the existing JVM-track-subagent `AGENT.md` into a pod agent's prompt.
9. **(Order, flag) Reconcile soul's idle/reflect promises** with the absence of a matching verb in `system-text` — either expose the capability or soften the soul prose.
