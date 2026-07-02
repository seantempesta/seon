---
type: research
status: active
tags: [research, agent, database, gym]
---

# Database-memory competency drive — k=2 DeepSeek (2026-06-28)

## TL;DR

Drove the three DB-memory-discipline gym scenarios at **k=2** against the real
DeepSeek adapter + judge, each in its **own fresh process** (see the harness bug
below — the combined run is poisoned after the first paid scenario).

| scenario | tier | mech pass-rate (k=2) | judge | verdict |
|---|---|---|---|---|
| `finding-storage-shape` | stub (scripted) | **2/2** | n/a | STORE shape **HANDLED** (but scripted, not free behavior) |
| `s32-consult-before-research` | paid | **0/2** — red ONLY on the salience-render predicate | **2/2** | CONSULT competency **HANDLED**; one **context regression** (findings-content no longer rendered) |
| `s12-run8-two-agent-consultation` | paid | **0/2** | **0/4** | **FAIL / weak** — A under-stores, B re-researches instead of consulting |

**Net:** the database-memory competency is **PARTIALLY handled.** The agent
*can* store schema'd provenance rows and *can* consult-first when the store is
pre-populated and the question signals stored knowledge (s32). But the realistic
**two-agent accumulation bar (s12) FAILS**: agent A persists 0–1 findings (bar is
≥2 with provenance) despite researching, and agent B's first move is to
**re-grep the repo or greet the user** rather than query the accumulated store.
Both failures converge on ONE context lever — the **findings/salience block
regressed from "render user-domain finding CONTENT" to "inventory counts +
low-card samples only"**, so a fresh agent never SEES the accumulated knowledge
and defaults to re-research.

Eval-error rates were low everywhere (0–0.18) — **eval noise is not the
problem; storage discipline + salience are.**

---

## How it was run

A temporary k=2 instrument ns drives `gym/run-scenario!` twice per scenario and
dumps the full per-run scorecards (every predicate `:actual` blob → the agent's
real evals + stored rows) to `tmp/dbmem-<scenario>.edn`:

- `test/seon/gym/database_memory_drive_test.cljs` (temporary; gated on
  `SEON_GYM_PAID` naming the scenario token).
- Run one scenario per fresh process (the harness bug below forces isolation):

```bash
clojure -M:cljs compile test
SEON_GYM_PAID=s32-consult-before-research SEON_AI_PROVIDER=deepseek \
  node out/test/test.js --test=seon.gym.database-memory-drive-test
SEON_GYM_PAID=s12-run8-two-agent-consultation SEON_AI_PROVIDER=deepseek \
  node out/test/test.js --test=seon.gym.database-memory-drive-test
```

Live-pod untouched; stock core (no `SEON_EXTRA_*`); scratch `:memory` conn per run.

---

## HARNESS BUG (flag — contradicts "the gym is clean" for multi-scenario paid runs)

Running **all three** scenarios in one process (the first, naive run) produced
**only the `finding-storage-shape` scorecard**. Every paid scenario AFTER the
first threw at user-message land:

```
gym: user message! failed —
  Malli validation failed for :seon.agent.message/to: expected :seon.db/ref …,
  got [[:seon.agent/id "rRK-2606290327"]]
```

i.e. `:seon.agent.message/to` (live src = `[:vector :seon.db/ref]`, card-many)
was being validated **card-one** mid-process. It poisoned not just s32/s12 but
**every gym scenario in `seon.gym.driver-test`** that ran afterward (s01,
envelope-honesty, blank-message-refusal, …) — all threw the identical error.

- In **isolation** (one scenario per process) s32 and s12 land the message
  fine — confirming process-scoped pollution, not a scenario bug.
- Likely mechanism: `run-scenario!`'s `finally` reaps schema keys with
  `(remove keys-before (schema/current-keys))` — it drops only **newly-minted
  keys**, it does **not restore a MUTATED value of a pre-existing key**. The
  process-global `schema/*schemas` atom is shared across runs, so if any
  scenario's seed re-registers a pre-existing attr (here `:seon.agent.message/to`)
  to a different shape, that mutation **survives the reap forever** and breaks
  every later run's `open-agent-conn!` schema install.
- **Workaround used:** one paid scenario per fresh process. **Proposed fix
  (U lane / gym):** snapshot+restore the FULL `schema/*schemas` map in
  `run-scenario!`/`measure-context!` `finally` (not just drop-new-keys), so a
  scenario can never leak a re-registration into the next. Worth a task; it
  silently caps any multi-scenario paid battery at one real result.

---

## Per-scenario findings (the real evals)

### 1. `finding-storage-shape` — STORE shape: HANDLED (scripted)

`:stub` tier with an `llm-script`, so k=2 is deterministic (a regression that
pins the taught my.kb convention, **not** free agent behavior). Both runs 4/4:

- `:knowledge-row-with-full-provenance` 2/2 — stored row:
  `["transact! Malli-validates every entity before the tx reaches datahike"
   "src/seon/db.cljs" 630 :verified]` — claim + `:my.kb/source-path` +
  `:my.kb/source-line` + `:my.kb/confidence`, a multi-segment
  `:my.kb.codebase.fn/*` namespace reusing the shared `:my.kb/*` provenance attrs.
- `:no-single-segment-namespace-fork` 2/2 (no `:finding/*` fork).
- `:reply-confirms-after-store` 2/2 (reply lands AFTER the store).

This proves the *taught convention still scores* — schema'd data with provenance,
not a memory-text blob. It does **not** prove a free agent does this unprompted.

### 2. `s32-consult-before-research` — CONSULT: HANDLED; salience render REGRESSED

`mech 0/2` but the **only** red is `:seeded-claim-rendered-in-prompt`. Every
behavioral predicate is GREEN and the judge is 2/2:

- `:first-eval-consults-stored-findings` **2/2**. The agent's literal first eval:
  - run 1: `(seon.db/query '[:find ?path ?line ?confidence :where
    [?e :my.kb/source-path ?path] [?e :my.kb/source-line ?line]
    [?e :my.kb/confidence ?confidence]])` — a real Datalog query over the seeded
    `:my.kb/*` provenance attrs.
  - run 2: `(seon.agent.ctx/render-namespace {:seon.ns/name :seon.agent.message})`
    — inspected the namespace rather than grepping.
- `:at-most-one-repo-search` **2/2** — 0/11 and 0/16 evals were repo searches
  (no re-derivation).
- judge `:judge-answer-states-the-concise-envelope` **2/2** — correct answer both
  runs ("message! returns a concise envelope … NOT the full transact report").

The red predicate, `:seeded-claim-rendered-in-prompt` (0/2), asserts the seeded
claim TEXT ("the transaction report itself is swallowed at the boundary") appears
in the agent's prompt. It doesn't. Inspecting the prompt blob, the seeded
`my.kb.codebase` rows surface ONLY as an **inventory count**:

```
;;; ┌─ inventory ─
; my.kb.codebase: claim 4 question 4
; my.kb: confidence 4 «:verified :inferred» source-line 2 source-path 4 «"src/seon/agent/message.cljs" "src/seon/db.cljs"»
```

That is counts + low-cardinality «sample values» — **no claim content**. The
predicate's own comment records that a `:findings` block (seon.agent.findings,
core-default-ctx priority 48) was EXPECTED-GREEN since 2026-06-12, live-proven to
render user-domain row CONTENT in full. It now renders only a pointer. So this is
a real **salience-render regression** (or a deliberate-but-unrecorded shift to an
inventory-pointer model). The agent compensated by querying — which is *why s32
still answered correctly* — but a fresh agent that does NOT query (see s12-B) is
left blind.

### 3. `s12-run8-two-agent-consultation` (THE demo bar) — FAIL / weak

`mech 0/2`, `judge 0/4`. Greens: `:a-searched-the-repo` 2/2, all four
`terminate`/`idle` predicates 2/2, `:b-replied-to-the-user` 2/2,
`:no-single-segment-finding-fork` 2/2. The reds are the load-bearing ones:

**Gap A — agent A under-stores (`:a-stored-at-least-two-findings-with-provenance`
0/2).** A *researched* (6/28 and 3/24 evals were repo greps) but persisted almost
nothing provenance-shaped: run 1 stored **0** findings, run 2 stored **1** (bar is
≥2). The task message explicitly says "Store what you find so the next agent
doesn't have to redo this," and A largely didn't. Judge on A's answer also failed
2/2 — A described only the registration check, omitted per-value validation, and
gave no file citation.

**Gap B — agent B does not consult-first
(`:b-first-eval-consults-stored-findings` 0/2).** B's literal first
message-driven eval:

- run 1: `(message/user "Hi — I'm up and connected to the shared store. What
  should I work on?")` — B **greeted the user and asked for work** instead of
  answering the question it was sent (judge: "does not address the question at
  all — it merely greets").
- run 2: `(seon.agent.search/grep {:seon.agent.search/pattern
  "validate.*transact|schema.*conform|:seon.db/error"})` — B went straight to a
  **repo grep** (the run-7 re-derivation signature), not a store query.

B never ran `store-inventory`/datalog as its first move. Note B's failure is
**compounded** by Gap A (A stored little for B to find), but even so B should
query the store before grepping/asking.

---

## Diagnosis — what the context didn't teach (and the proposed fix by lane)

Both s12 gaps and the s32 red converge on **one context lever**: a fresh agent
does not SEE accumulated knowledge surfaced in its prompt, so storing feels
optional (A) and consulting feels unnecessary (B re-greps).

1. **Salience render regressed — Core lane (primary).** Restore the
   `:findings` block (seon.agent.findings, core-default-ctx) so user-domain
   `:my.kb.*` finding CONTENT (the actual claims + their `:my.kb/source-*`
   provenance) renders into the agent prompt, not just an inventory count. This
   directly re-greens s32's `:seeded-claim-rendered-in-prompt` AND gives s12-B
   the answer in-context so it doesn't re-research. If the inventory-pointer
   model is intended instead, that's a deliberate design decision that needs to
   be recorded and the s32 predicate re-cut (U lane) — but the s12-B evidence
   argues for *restoring content render*, because B demonstrably will NOT query
   on its own.

2. **STORE-PROACTIVELY teaching too weak — Core lane (my.kb manual /
   always-on context).** When a task says "store what you find," the agent must
   persist EACH distinct claim as its own `my.kb.<domain>` row with
   `:my.kb/source-*` provenance, and not treat the reply as the deliverable. The
   always-on guidance shows HOW to store but does not press "a research task is
   not done until your findings are durable rows (≥1 per claim)." A stored 0–1
   rows after a multi-claim investigation.

3. **CONSULT-FIRST as the default first move — Core lane (always-on context),
   reinforced by (1).** The inventory block already says "Consult BEFORE
   researching," but B ignored it on its first eval (greet / grep). Hoisting the
   "first move on any answerable question = `store-inventory` + datalog, BEFORE
   grep/read-file/asking-the-user" rule to a high-salience always-on position —
   and, per (1), rendering the findings so the answer is already visible — is the
   fix. (B greeting-the-user-instead-of-answering in run 1 may also be a
   second-agent wake/turn nuance worth a Core look.)

**Do NOT implement here** — this is the diagnosis + proposed fix. The
orchestrator should run the fix+re-measure iteration (primarily lever 1, Core).

## Verdict

- `finding-storage-shape`: **HANDLED** (store-as-schema'd-data convention scores;
  scripted, so it's a regression guard, not free-behavior proof).
- `s32-consult-before-research`: **competency HANDLED** (consult-first 2/2,
  no-re-research 2/2, judge 2/2); **one context regression** (findings-content
  salience render → inventory-count only).
- `s12-run8-two-agent-consultation`: **FAIL / weak** — the two-agent accumulation
  bar is the real gap: A under-stores, B re-researches/greets instead of
  consulting.

**Database-memory competency: PARTIALLY HANDLED — the store-shape and
single-agent-with-fixtures consult are handled; the realistic cross-agent
accumulation is not, and the root lever is the regressed findings-content
salience render.**
