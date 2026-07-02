---
type: prd
status: draft
tags: [prd, agent]
---

# Gym v2 — Outcome-Based, Cross-Session, Non-Fragile Agent Evaluation

Design assessment, branch `feature/agent-fsm`, 2026-06-25. Read-only. Governs the context-tuning loop; applies [[feedback_test_behavior_not_exact_strings]] to the eval harness. Pairs with [[docs/prds/embeddings/state-and-activation-2026-06-25]].

## Implementation status (2026-06-25)

- **Done — re-enabled + FSM-aligned + green.** `driver_test.cljs` +
  `paid_test.cljs` are back (no longer `.disabled`); the driver drives
  `seon.agent.turn/run-turn!` + `seon.agent.loop/run-loop!`, mints a wake
  per drive (mirrors the live `wake-handler`), boots via
  `client/bootstrap-turn!`, and scopes eval/prompt predicates on the new
  `:seon.agent.turn/wake` marker (the retired `:woken-by` is gone). Full
  CLJS suite: 0 failures, 0 errors.
- **Done — judge is a trustworthy signal.** Added `calibrate-judge!` +
  calibration tests (good→PASS, bad→FAIL, `discriminates?`). Live DeepSeek
  calibration discriminates (good 100 / bad 0). The judge ctx now EXCLUDES
  the bootstrap greeting (every agent greets at boot) so the judge grades
  the answer, not the hello.
- **Done — condition-A baseline.** X1 (subscriptions SUM/MAX), X3 (expense
  reuse-no-fork + category SUM), X12 (negative over-retrieval) wired +
  run paid on DeepSeek: **x12 PASS / x1 + x3 FAIL — cond-A 1/3** (honest
  reds: B announced intent then echoed its transcript / hallucinated the
  total instead of querying; caught by both the discovery leg + the judge).
- **Left:** the rest of the §3 catalog (X2/X4–X11), condition-B
  (embeddings) lift scaffold, and the §6 loader lints. A recurring
  DeepSeek failure pattern — "announce intent, echo transcript, never
  emit the query form" — is a real agent-behavior finding for the
  context-tuning loop, not a harness bug.

## TL;DR

- **Right bones, wrong status.** `test/seon/gym/driver.cljs` (1412 ln) is ALREADY outcome-based: mechanical datalog/eval/domain-attr predicates over the post-run store + a SEPARATE LLM-judge axis, and the r2 directive (2026-06-11) already deleted the fragile layout/cache gates. But the gym is **dormant on this branch** — `driver_test.cljs.disabled` + `paid_test.cljs.disabled` (since `e313add`, the FSM carve). **Step one is re-activation against the FSM, not a rewrite.**
- **Residual fragility is in a few predicate kinds**, not the architecture: `:transcript/prompt-includes` pinning rendered context text, and `:eval-*` patterns pinning fully-qualified verb symbols. The fix is a rule set (§6) that forbids context-byte pins and forces every "found/used the data" check onto store outcomes (entity ids/attrs/refs) + the judge.
- **Structural retrieval gives a full baseline TODAY** (`store-inventory` + inventory section + `query`/`pull`/ref-walk all verified live). **Do NOT block the baseline on embeddings** (`SEON_EMBED` unset live; P2-C/D pending). Embeddings = measured condition (B), added later, reporting **lift over (A)**.
- **Two retrieval conditions + report the lift:** (A) cold agent B must explicitly **discover→search→query** the planted data (tests the three affordances: inventory metadata + search instructions + verb descriptions); (B) **breadcrumbs auto-pulled** into B's context by embedding similarity (`seon.ctx.relevant` is the seam). Condition (B) is scored on **precision, not just recall** — over-pulling bulk content is a penalized *failure*, because breadcrumbs must be pointers/headlines, not blobs. (The relevant section today renders up to 1500 chars/hit — a blob; see §4.)

## 0. What the current gym scores (and what's reusable)

**Dormant:** `driver_test.cljs.disabled` (933 ln) + `paid_test.cljs.disabled` (247 ln), disabled at `e313add`. The driver itself was NOT disabled — only the harness driving it. Re-point at the carved FSM (`seon.agent.loop`/`seon.agent.turn`) and re-enable.

**Already outcome-shaped.** Predicate kinds (`driver.cljs:172`): `:datalog`, `:transcript-includes/excludes`, `:first-eval-matches`, `:eval-count-matching`, `:domain-attrs`, `:prompt-includes/excludes/every-turn`, `:llm-judge`.
- `:datalog` — query post-run store, assert `:non-empty`/`:empty`/`[:count* n]`/`[:some-includes s]`/`[:every-in [...]]`. The outcome primitive.
- `:domain-attrs` — agent-provenance attrs via `seon.warn/domain-attrs`; `[:every-in …]` = "no forked attr." Vocabulary-agnostic.
- `:llm-judge` — rubric + reference facts + verbatim reply → graded verdict on a SEPARATE axis (`judge-pass?` vs `pass?`, `driver.cljs:324-341`).
- `:first-eval-matches`/`:eval-count-matching` — regex over eval *source*, scoped to message-driven turns (`eval-at+source` `:500`, woken-by filter excludes the creation tutorial).

**The de-fragiling is already templated.** `consults-findings-run8.edn` shows the moves: consult anchors WIDENED from `:my\.kb` to "any `seon.db` read op" ("measures the BEHAVIOR not the VOCABULARY"); storage anchored on attr-name **stems** not namespace; `normalize-ws` containment. v2 **systematizes these as rules** instead of per-scenario.

**Residual fragility (v2 targets):** (1) `:transcript/prompt-includes` keyed on context section text; (2) `:eval-count-matching` hard-coding FQ symbols (a verb rename already broke one — file comment, line 56); (3) judge `:reference` strings carrying `~line 615` citations that rot.

## 1. Scoring model — outcome + judge, no context pins

| Class | Mechanism | Proves | Fragile under churn? |
|---|---|---|---|
| **Outcome (store)** | `:datalog`/`:domain-attrs` over post-run db | right entity/attr/ref/value landed + was read | No — data, not text |
| **Behavior (evals)** | `:eval-*` over eval source, anchored on **op families** | B read store before researching | Low (survives verb renames) |
| **Meaning (judge)** | `:llm-judge`, separate axis, **shape** references | B demonstrably learned the linked info | No — fuzzy by design |
| ~~Context text~~ | ~~`:*-includes` on section bytes~~ | ~~surface rendered~~ | **YES — restricted (§6)** |

Mechanical predicates are **binary** (there's a ground-truth entity to find); the judge is **graded 0-100** beside, never merged. Headline = `mechanical-pass? AND judge-pass?`, always reported separately.

**Core outcome signals for store→retrieve** (plant a ground-truth entity at seed, record its identity; check "did B reference that exact identity," never "reply contains string X"):
- **DISCOVERY** — B's first message-driven eval is a `seon.db/(query|pull|entity|store-inventory)` read.
- **RETRIEVAL HIT** — `:datalog` that B touched the planted entity's identity attr by its seeded value-stem.
- **LINK FOLLOW** — `:datalog` that B retrieved an entity reachable ONLY via a ref from an inventory-visible entity (entity-1 --ref--> entity-2). The signal that the *linked nature* paid off.

**Judge grades the fuzzy part** — "did B learn the fact on the far side of the link, that it couldn't know without discovering entity-1 + following its ref?" Receives B's verbatim reply + rubric + **shape-level** reference facts (planted values + relationships, never file:line). A correct-by-luck/generic answer fails the "could not know without the link" clause.

**Baseline metric** (per git-sha, scorecards keyed `scenario × sha × run-uuid`):
```
gym @<sha>: discovery R/Y  retrieval R/Y  link-follow R/Y  judge mean-score
            cond-A pass-rate | cond-B pass-rate | LIFT = B−A | noise = mean off-target breadcrumbs
```
A context rewrite shows as a **moved number on a stable key** — the whole point.

## 2. Cross-session protocol (A stores, B retrieves cold)

**Mechanism (exists):** turn designators `:a`/`:b` (`driver.cljs:131`) boot lazily on the SAME scratch `:memory` conn, run strictly in order → "boot A → await idle → boot B" falls out. Root `*conn*` swapped for the run, restored in `finally`; minted registry keys removed after. Live cluster store untouchable.

**B shares with A:** the DATABASE only (A's entities/schemas/refs are B's fixture — the accumulation thesis).
**B sees at boot (the affordances under test):** B's creation turn runs `creation-evals!` boot parity — `(store-inventory)`, instructions, tile wiring — so B's OWN transcript carries (a) inventory metadata, (b) search instructions, (c) verb descriptions. The inventory section renders A's kinds into B's context.
**B must NOT see:** A's transcript (woken-by scoping guarantees B's predicates range only over B's sessions); the harness/answer (scenario EDN is driver-side, never written to the conn); `check-self-bait!` hard-fails the load if a turn message appears in a fixture — v2 extends it to the planted **answer value**. No coaching: fixes are general mechanisms, never answer-shaped.
**v2 invariant:** assert B booted with zero prior session/turn history of its own (one datalog predicate).

## 3. Scenario catalog (cross-session, outcome-scored)

Naming `X1…`. Each reports condition (A) by default; (B) is the same scenario with `SEON_EMBED` on, reporting lift.

- **Group I — new schema:** X1 store+cold structural retrieve (houseplants); X2 B discovers a kind it was never told about (two unrelated kinds → B must read inventory) — the pure discovery-via-inventory case.
- **Group II — reuse (no fork):** X3 cross-agent reuse of A's `:my.workout/*` attrs (no stem-colliding fork) + correct aggregate; X4 reuse with a missing field forcing extend-not-fork (fractional km → no `:double`).
- **Group III — single-link:** X5 B learns a fact only via a task→project ref (owner lives on the project; a reply naming the owner without the chain is a guess → fail).
- **Group IV — multi-hop:** X6 person→document→finding (two-hop headline; generic-correct answer fails); X7 inventory reveals only hop-0, B must ref-walk from a person.
- **Group V — affordance isolation (measuring CONTEXT QUALITY):** X8 inventory is the only breadcrumb across a vocabulary gap (tests the inventory sample values); **X9 instruction-presence probe** — same store, B's context with/without the "consult first" instruction, measure the discovery-rate delta (isolates affordance b); **X10 verb-description probe** — generic vs specific description, measure the delta (isolates affordance c).
- **Group VI — negatives/precision:** X11 the data does NOT exist (anti-fabrication — B must say so, not invent a `:verified` finding); **X12 over-retrieval penalty** — B queries the relevant kind and does NOT bulk-pull unrelated kinds (`[:count<= n]`), rewarding precision (the structural analogue of (B)'s over-pull penalty).
- **Group VII — stub regressions** (free): pipeline smoke, envelope-honesty under a failing write, blank-message refusal, no-`:double`, error-as-value recovery — re-pointed at the FSM.

## 4. Two retrieval modalities + the predictive third

**Condition (A) — structural/datalog (works TODAY).** B explicitly discovers→searches→queries via inventory + `query`/`pull`/`store-inventory` + ref-walk. Verified live. The baseline; needs nothing new.

**Condition (B) — semantic/embeddings (the measured lift, when it lands).** Two sub-modalities:
- **(B-search)** agent-initiated `seon.embed/search`/`search-pull` (NL query → KNN). Pod thin-client over the JVM wire-server.
- **(B-predictive)** anticipatory breadcrumbs auto-pulled into B's context by embedding similarity, no explicit B action. Seam exists: `seon.ctx.relevant/relevant-source-section` (priority 48, volatile tail), prefetched by `run-turn!` via the wire, stashed fiber-local; default-OFF when `SEON_EMBED` unset (returns `""`, prompt byte-identical).

**Critical constraint — breadcrumbs are pointers, not blobs.** Owner's rule: surface "this exists, here's the fn that views it, here's how to refresh" — a trail, not bulk. **The section TODAY violates this:** `render-hit` (`relevant.cljs:85`) renders each hit's longest string attr (the body) capped at 1500 chars/hit, top-5 ≈ 7.5k chars (`source-char-cap` `:36-40`). That is the failure mode. So (B-predictive) measures TWO things:
1. **Recall/lift** — did the auto-breadcrumb let B answer WITHOUT explicit search (fewer discovery steps, same/better outcome)?
2. **Precision/penalty** — did the section stay pointer-sized and on-target? Penalize when (a) a breadcrumb body exceeds a small pointer budget, or (b) the pulled set includes entities irrelevant to the ground truth. Makes "every section pulls measurable weight" a *scored* property.

Scored via the stash contents (set checks) + a bounded read of the existing per-section char-count telemetry — gated on `SEON_EMBED`, so it never re-introduces the r2-forbidden layout gates. **LIFT = pass-rate(B) − pass-rate(A); noise = mean off-target breadcrumbs.** A high lift with high noise is WORSE than a modest lift with clean pointers — both reported so over-pulling can't masquerade as improvement.

## 5. Embeddings sequencing — recommendation

**NO, don't block on embeddings.** Get the baseline + tuning loop running on structural retrieval NOW; add embeddings as condition (B) lift later.
1. The baseline is fully scorable today (verified live).
2. The three affordances under test (inventory/instructions/descriptions) are STRUCTURAL; tuning them — the core goal — needs no embeddings. Embeddings test a fourth, additive capability.
3. Embeddings are off-by-default with P2-C/D pending; gating the eval on a cross-branch JVM/Proximum/Gemini integration inverts the dependency — you need an A-baseline FIRST so B's lift is meaningful, else embeddings bugs become gym outages.

Mitigation for structural-first (condition-B predicates sit dormant): write them now gated on `SEON_EMBED`, with a **fake-stash** stub scenario that proves the size/precision predicates fire before the wire-server exists. Sequence: (1) re-enable vs FSM structural-only; (2) land the catalog + lift scaffold (B defined, gated, fake-stash tested); (3) run the A-baseline, start tuning; (4) when embeddings land, flip `SEON_EMBED` and the lift metric activates with zero gym changes.

## 6. Staying non-fragile under refactor (loader-enforced where possible)

1. **Outcome over text** — "found/used the data" is ONLY a `:datalog`/`:domain-attrs` check against store entities/attrs/refs/values.
2. **No context-text predicate gates `pass?`** — `:*-includes` restricted to (a) the sees-question check (the user's OWN message text) and (b) informational telemetry; never asserting a rendered section's phrasing. Loader lint: a `:prompt-includes` whose text isn't from a turn message fails to load.
3. **Op-family anchors, not symbol literals** — `seon.db/(query|pull|entity|store-inventory)`; when a specific verb IS the subject, note it as a known rename-coupling point.
4. **Judge references are shapes** — function names + envelope shapes + planted values + relationships, never `~line N`.
5. **Vocabulary-agnostic anchors** — attr-name stems or provenance, not a chosen keyword namespace.
6. **Self-bait + no-coaching, loader-enforced** — extends to the planted answer value.
7. **Derive expectations from the system's own code**, never duplicated literals (the boot-seed drift-killer).
8. **Correctness > continuity** — a fix that moves a number → re-baseline + say so; a green→red from a STRICTER predicate is the gym working.

The test: a full context rewrite (sections renamed, docstrings rewritten, inventory reformatted) must change pass-RATES but cause zero load failures / zero predicate exceptions. If a rewrite *breaks* the gym vs *moves* it, a rule above was violated.

## 7. Implementation shape

**Reuse:** the driver's predicate engine, judge runner, designator sequencing, scratch-conn isolation, self-bait check, relative-date fixtures, scorecard schema; `bin/gym`; the existing scenario EDNs as templates.
**Build new:** (1) **re-enable + FSM-align** the disabled tests (the gating first step); (2) the §3 cross-session catalog (~12 EDNs with planted ground-truth + ref chains); (3) the **lift scaffold** (run each scenario in A, and B when `SEON_EMBED` set, emit LIFT/noise; condition-B predicates fake-stash tested now); (4) loader lints for the §6 rules.
**Where it runs:** inside `bin/test-cljs` (scratch `:memory` store — live cluster never touched). Paid tier wires the active provider (`:deepseek` on the live pod) under the allow-paid + API-key guard. Condition (B) needs the wire-server up only when `SEON_EMBED` is set, never in the default suite.
**Trackable number:** `bin/gym` greps `SEON-GYM SCORECARD` lines keyed `scenario × sha × run-uuid`; the baseline tuple (§1) is computed from them. This measures context QUALITY, not size — a docstring/instruction/inventory improvement that raises discovery-rate at equal bytes is a measured win; a breadcrumb change that raises recall while raising noise is a measured loss.

## Critical files
- `test/seon/gym/driver.cljs` — the spine.
- `test/seon/gym/driver_test.cljs.disabled` (+ `paid_test.cljs.disabled`) — re-enable + FSM-align first.
- `src/seon/ctx/inventory.cljs` + `src/seon/db.cljs` (`store-inventory` ~1139, `transact!` ~375, `query` ~477) — the affordances (a)+(c) that condition (A) tunes.
- `src/seon/ctx/relevant.cljs` — the predictive seam for (B); currently blob-rendering (the over-pull failure the gym must penalize).
- `src/seon/embed.cljs` (+ `docs/prds/embeddings/state-and-activation-2026-06-25.md`) — the off-by-default semantic path; condition (B) lift, sequenced after the structural baseline.
