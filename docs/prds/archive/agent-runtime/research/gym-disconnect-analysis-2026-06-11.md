---
type: research
status: active
tags: [research, agent]
---

# Gym ↔ Live-System Disconnect Analysis (2026-06-11)

Investigation of the user's question: *"There was a growing disconnect from
when we first envisioned the gym and how the system now operates. I think it
had to do with different turns having different datoms loaded… The gym must
always represent a quality benchmark of the system. We don't want to hide
problems; we want the gym to expose them."*

Read state: working tree at `1d1d2dc` with UNCOMMITTED concurrent edits to
`src/seon/agent.cljs`, `src/seon/client.cljs`, `src/seon/ctx.cljs`,
`src/seon/db/internal.cljs`, `src/seon/warn.cljs` (loop-economy #35 +
legibility agents in flight — I read the working-tree versions, which already
contain `replied-since-inbound?`). Gym files last committed at `095a00b`
(world parity) / `40cd22c` (P8 prep). Live probes ran against the running pod
(MCP CLJS session `default`, the DIS-backed cluster store).

## TL;DR

The user's suspicion is confirmed and is the #1 finding: **the live system's
per-turn context is a function of a LIVING, multi-writer, multi-boot store,
while the gym's is a function of a frozen, single-writer, single-boot scratch
store.** Observed live: a pod re-boot re-seeded the substrate index at
23:14:03 (the P6 message-split), landing two new `:seon.schema` rows in the
durable cluster store *between turn 0 and turn 5 of agent kXQ's one session*
— its schema-catalog line silently changed from "6 schemas" to "8 schemas"
mid-conversation. That entire class of behavior (catalog drift, cross-agent
warnings, foreign writes, stale wakes, accumulated duplicates, resume across
restarts) is unreachable in the gym by construction.

The 095a00b "byte-identical world parity" work is real but covers exactly the
**turn-0 static prefix**. Nothing in the gym asserts what the agent SAW on
turns 1..N — predicates read the **post-run store** and a **re-rendered
transcript**, never the per-turn prompt blobs that `run-turn!` already
persists (`logs/prompts/<agent>/<turn>.txt`) in both live and gym runs. The
catalog's standing predicate G2 ("some turn's prompt-text contains the user
message") is currently **unimplementable as written** because
`:seon.agent.turn/prompt-text` was retired to file blobs on 2026-06-09 — and
indeed no scenario carries a sees-question predicate against actual prompt
content.

Secondary findings: the stub tier bypasses the wake path entirely (no
trigger, no latch, no hop guard, no stop policies); harness defects from the
P8 sweep are confirmed in the logs (S-12 ran twice in sweep 2 = async
double-done; s32's seeded finding question is near-verbatim the asked
question, so "consults" can pass on string-bait); registry bleed puts
test-process-only schemas into gym prompts (flagged residual in 095a00b).

The fix direction (§B below): extend the parity-from-boot-code approach to
per-turn dynamics — prompt-blob predicates, a structural per-turn parity
check derived from `substrate-default-ctx` itself, a world-churn fixture that
re-invokes the boot's own seed fns mid-run, an optional trigger-driven wake
mode, and a live-shadow render pass against the real cluster store.

---

## 1. How the live system operates NOW, per turn

### 1.1 The render pipeline

Every turn: `run-agentic-loop!` → `run-turn!` → `render-prompt` →
`seon.ctx/assemble-context` (the ONE composer) against `@db/*conn*` — a
fresh db value at each turn. Sections (`substrate-default-ctx`, priorities
10–99): `:system` (byte-stable), `:instructions` (`my.kb.instruction` rows),
`:capabilities` (derived from persisted `:seon.fn` rows for
`capability-syms`), `:exemplars` (full `:seon.ns/source` of
`relevant-roots`), `:schema-catalog` (every `:seon.schema` row + live
registry + fuzzy instance counts + domain-attrs reuse block + finding-claims
block), `:functions-catalog` (count index over `:seon.fn` corpus),
`:namespace-context` (render of `current-ns`, itself derived from the latest
successful eval), `:warnings` (`seon.warn` checks — runtime checks are
**deliberately global/cross-agent**), `:open-todos`, `:transcript`
(messages ∪ evals interleaved, 24k-char budget, newest-first eval eviction,
messages never evicted), `:prompt` (per-turn volatile tail). The full prompt
goes to `logs/prompts/<agent-id>/<turn-id>.txt`; the turn datom carries only
`prompt-chars` + `prompt-file` (prompt-text datom retired 2026-06-09).

### 1.2 Why different turns load different datoms — the variance taxonomy

Per-turn context varies in the live system through SIX distinct channels:

1. **Intended dynamics** — transcript grows; `current-ns` follows the latest
   successful eval (namespace-context re-targets); warnings appear/vanish
   (reactive-context); prompt tail counters/timestamps; turn-pressure nudges
   escalate with `turns-since-inbound`.
2. **Semi-static catalog churn** — fuzzy counts cross buckets as the corpus
   grows; `domain-attrs-block` and `finding-claims-block` gain rows the
   moment ANY agent registers/stores (cross-agent by design — no
   `:seon.agent/id` filter).
3. **Substrate re-seeding across pod boots** — `open-cluster-conn!` step 3
   transacts the full Malli-derived schema + substrate index OVER THE WIRE on
   every boot against the SAME durable store
   (`data/clusters/default/store`). A code change + restart = new
   `:seon.ns`/`:seon.fn`/`:seon.schema` rows landing mid-life of every
   resumed agent's context. **Observed live** (probe, §3 evidence E1): the
   `seon.agent.message/message-request|response` schema rows landed at
   `2026-06-10T23:14:03` — between agent kXQ's turn at 23:12 and its turn at
   23:15; the prompt-blob diff shows the schema-catalog line move
   `seon.agent.message — 6 schemas` → `8 schemas` and a new functions-catalog
   line `seon.agent.message — 2 fns` inside one session.
4. **Foreign writers** — the pod is a DIS peer; foreign txs (JVM writer,
   other pods, other agents) fire this conn's native listeners. Other agents'
   failed evals show in MY warnings; their domain attrs in MY catalog. The
   live store also carries attrs absent from the local Malli registry (the
   `db-schema` FilteredDB/valueType fallback in `ctx.cljs` exists precisely
   because this surfaced at the 2.2e flip).
5. **Cross-session asymmetry** — `messages` queries the WHOLE message log
   (from/to me, no session scope); `evals` walks only `current-session`'s
   turns. A resumed agent sees its whole conversation but none of its prior
   evals. (`ensure-session!`'s docstring says "re-uses an existing session
   within the same pod run" but the implementation reads the DB and reuses
   across pod runs too — flagged as a smell, §5 Q2.)
6. **Loop-policy dynamics** — the working tree adds the `#35` halt:
   `replied-since-inbound?` ends the wake after an outbound reply (derived
   from the message log). What context a later turn even GETS to see now
   depends on this policy.

The gym reproduces channel 1 faithfully (its `run-turn!` IS the live
`run-turn!`, re-rendering from the scratch conn each turn) and channel 2
within a single run. Channels 3–6 are structurally absent or untested.

## 2. How the gym is set up

`test/seon/gym/driver.cljs` (1051 lines): scenarios are EDN data (question
turns + fixtures + predicates). Per run: `client/open-agent-conn!` (a FRESH
isolated `:memory` datahike conn — "Test/diagnostic surface ONLY; the pod
itself boots on the shared cluster store via open-cluster-conn!"), swap root
`db/*conn*`, seed THE WORLD A POD BOOTS INTO via the boot's own calls
(`h/bootstrap-schema!` + wake bootstrap + handler row;
`all-entity-schemas-tx-data` + `seed-substrate!` + `substrate-index-tx`
under `:substrate-seed` origin inside the primary agent's `with-agent`
scope), then the scenario's prior-agent layer (registrations as tee-shaped
`:seon.schema` rows + fixtures, under a synthetic prior-agent id), then
drive turns:

- **stub / per-turn-script**: one `run-turn!` per scripted LLM text — NOT
  the loop, NOT the trigger ("deliberately… the stub self-wake bug burns
  trigger-driven stub loops to the turn cap").
- **stub / :scripted-replay**: `run-agentic-loop!` with a replaying llm-fn
  (terminates via zero-forms… and now also via the #35 replied halt).
- **deepseek**: `run-agentic-loop!` with the real adapter, double-gated
  (`allow-paid?` + `DEEPSEEK_API_KEY`); `bin/gym --paid=…` →
  `SEON_GYM_PAID` → `paid_test.cljs`.

No tier installs `install-user-trigger!` — "the driver drives." Predicates
(datalog / transcript-includes / first-eval-matches / eval-count-matching /
domain-attrs / llm-judge) evaluate against the **post-run db value** plus a
transcript **re-rendered post-run** via `agent/transcript-section`.
Scorecards keyed scenario × git sha.

**What 095a00b ("byte-identical world parity") does guarantee:** the scratch
world carries the same row-classes a live pod boot produces, derived from the
boot's own sources of truth (test-preload roster → `:seon.test` rows +
test-sibling sources → 7/7 exemplar blocks; handler entity; live provenance
shapes), and the decisive proof was a **turn-0** live-vs-gym prompt diff = 32
lines in ~1780, "all legitimate (transcript/timestamps/test-registry catalog
lines — residual registry-bleed flagged)". Permanent regression tests:
`seeded-world-matches-a-pod-boot-…` and
`seeded-world-carries-the-pod-boot-roster-…` in `driver_test.cljs`.

**What it does NOT guarantee:** anything about turns 1..N; anything about a
store that has lived through multiple boots, code versions, or writers;
anything about the wake path; anything about what the prompt actually
contained when the agent acted.

## 3. Evidence (verbatim excerpts)

**E1 — live catalog drift mid-session (the user's "different datoms per
turn", observed).** Probe of the live cluster store:

```clojure
;; :seon.schema rows in ns "seon.agent.message", with tx instants:
[[:seon.agent.message/hops    #inst "2026-06-10T22:14:54.904-00:00"]
 ;; … (6 rows at boot 22:14) …
 [:seon.agent.message/message-request  #inst "2026-06-10T23:14:03.613-00:00"]
 [:seon.agent.message/message-response #inst "2026-06-10T23:14:03.613-00:00"]]
```

Agent kXQ's turns straddle 23:14:03 (turn blobs at 23:12:03 … 23:15:15).
Diff of `logs/prompts/kXQ-2606101814/ZTU-2606101912.txt` (turn 0) vs
`hgq-2606101915.txt` (turn 5), non-transcript portion:

```text
1672c1672
<   seon.agent.message — 6 schemas
---
>   seon.agent.message — 8 schemas
1717a1718
>   seon.agent.message — 2 fns
```

(Everything else that changed was transcript growth + the prompt tail:
`;; ── turn 0 · 0 since-user …` → `;; ── turn 5 · 3 since-user …`.)

**E2 — cross-boot static-prefix drift between agents on the SAME store.**
`kXQ` (booted 06-10) vs `aiR` (booted 06-11) turn-0 prompts diverge inside
`<capabilities>` (the identity-is-OPTIONAL teaching block added between
builds) — the static prefix tracks the CODE of the booting pod, while the
catalogs track the accumulated STORE. Two agents on one store can see
different teachings about the same data. The gym can never see this: one
code version, one boot.

**E3 — S-12 ran twice in one paid sweep (async double-done).**
`tmp/gym-paid-sweep2-p8.log` emitted scorecards:

```text
2 :seon.gym.scorecard/scenario :s12-run8-two-agent-consultation
  #inst "2026-06-10T22:38:37.081-00:00"
  #inst "2026-06-10T22:39:28.318-00:00"
```

(also 2× `:envelope-honesty` in both sweeps — that one is expected: the
broken-predicate honesty test re-runs the same scenario). One paid scenario
executing twice = double spend and two cards under one (scenario × sha) key.

**E4 — post-answer churn + duplicate replies in the paid s32 run**
(`tmp/gym-paid-sweep1-p8.log` ~line 5213): the agent's own narration,
verbatim, mid-run:

```text
"The transcript shows the prior agent already answered this question —
multiple times — and stored the claim. … There's nothing new to serve. …
this is a stale wake."
```

and three identical hops-1 replies to the user from one wake (msg ids
IOL/QXT at 22:25:47.761/.910, then DtI "Already answered — …" at 22:26:01).
This is the #35 loop-economy defect — **the gym DID expose it**, which is the
behavior we want more of. (The duplicate-content pairs also reflect that
both a `reply!` message AND the raw-LLM self-message render in the
transcript — live noise the agent itself misread as "a prior agent".)

**E5 — s32's consult predicate can pass on string bait.** The scenario seeds
`:my.kb.codebase/question "What does seon.agent/message! return — the full
transact report or something smaller?"` and then asks exactly that question;
the consult predicate is `first-eval-matches ":my\\."`. A first eval that
merely queries any `:my.*` attr (which the schema-catalog explicitly teaches
as recipe step 0) passes — the predicate measures whether the bait was
rendered + pattern-matched, not whether consulting replaced research. (The
companion `at-most-one-repo-search [:count<= 1]` predicate does real work —
E4's run shows ≥2 `seon.agent.fs/read-file` evals, an honest red.)

**E6 — paid-gate anomaly: UNCONFIRMED.** `enabled?` in `paid_test.cljs` has
been the same exact-match split since creation (`884a75d`); both sweep logs
ran all three paid scenarios, but the logs do not record the
`SEON_GYM_PAID` value or the `bin/gym` invocation, so "a partial key list
enabled all scenarios" cannot be confirmed or killed from the artifacts.
Recommendation regardless: print the gate value + resolved scenario list at
suite start (one line, greppable).

**E7 — G2 sees-question is unimplementable as cataloged.** The catalog's
standing predicate G2 reads `[?t :seon.turn/prompt-text ?p]`-style datalog;
`:seon.agent.turn/prompt-text` "is NOT in agent-bootstrap-attrs and never
reaches the DB" (agent.cljs schema comment, retirement 2026-06-09). No
predicate kind reads the prompt-file blobs. Grep of all scenario EDNs: zero
sees-question predicates against prompt content exist.

**E8 — the standing predicates are not standing.** Catalog §2 says G1–G5
are "appended to every behavioral scenario's mechanical set"; the driver has
no such mechanism — scenario authors copy them by hand (S-32 has terminates +
reply predicates; nothing appends G3/G5).

## 4. (a) Ranked disconnects

| # | Disconnect | Evidence | Severity |
|---|-----------|----------|----------|
| 1 | **Frozen single-writer/single-boot world vs the living store.** Gym worlds never see substrate re-seeding across boots, foreign writers, cross-agent catalog/warning bleed, accumulated duplicates, or resume — the exact channels (3–6 in §1.2) by which live turns load different datoms. | E1, E2; `open-agent-conn!` vs `open-cluster-conn!` source | **Hides problems** — the stale-wake/duplicate-answer/catalog-drift family can only be discovered in paid live runs |
| 2 | **No per-turn prompt fidelity.** Parity = turn-0 prefix; predicates read the post-run store + a re-rendered transcript, never the persisted prompt blobs. A regression that drops the question (or the consult surface) from turn N's prompt is invisible if the agent still muddles to a reply. | E7, 095a00b commit message, `eval-predicate` source | **Hides problems** |
| 3 | **Wake path never exercised.** No tier installs the trigger; hop guard, `!kick-scheduled` latch, state-machine guard, inbound-datom filter, and DIS foreign-tx listener adaptation have zero gym coverage. The stub tier also bypasses ALL stop policies. | driver source ("no dispatcher is armed — the driver drives") | **Hides problems** (this is where live races live) |
| 4 | **Loop-policy coupling.** #35's replied-halt (uncommitted, in tree) changes which turns exist at all; scripted-replay scenarios were authored against zero-forms termination. The gym both exposed the original churn (E4) and now silently measures a different machine after the fix lands. | E4; working-tree `run-agentic-loop!` | **Mixed** — exposure worked; re-baselining is unowned |
| 5 | **Harness defect: async double-done.** S-12 ran twice in sweep 2 (double spend, ambiguous scorecard key). | E3 | **Hides problems** (corrupts the measurement itself) |
| 6 | **Predicate bait: s32 question-text reuse + unanchored consult pattern.** | E5 | **Hides problems** (false green on the #26 salience axis) |
| 7 | **Registry bleed.** Gym prompts carry `:seon.schema` rows for every schema registered in the TEST process (`:seon.gym.*`, test-only nses) — lines no live agent sees. | 095a00b "residual registry-bleed flagged"; `substrate-index-tx` reads `schema/current-keys` | Cosmetic→moderate (prompt-size + teaching noise skew) |
| 8 | **Standing predicates exist only on paper.** G1–G5 not auto-appended; G2 impossible (see #2). | E8 | Cosmetic (drift hazard) |
| 9 | **Paid-gate anomaly unconfirmed; gate state unrecorded in logs.** | E6 | Cosmetic (observability gap) |
| 10 | **`agent-reply-text` still uses the fetch-then-filter workaround** for the engine bug that `156a53e` fixed (driver_test restored the original double-identity-join as the regression pin). | driver.cljs docstring vs driver_test comment | Cosmetic |

Also flagged (live-system smell, not a gym defect): `ensure-session!` reuses
sessions ACROSS pod restarts despite its "within the same pod run" docstring;
combined with the messages-global/evals-session-scoped asymmetry this shapes
resumed-agent context in a way nothing tests.

## 4. (b) Gym-upgrade design sketch — make the gym EXPOSE the live system

Principle throughout (uniformity canary): every expectation is **derived from
the boot's/composer's own code**, never a hand-maintained seed — the same
move 095a00b made for the static prefix, extended to dynamics.

1. **Prompt-blob predicate kinds** (closes #2, revives G2). The gym already
   persists every turn's full prompt via `run-turn!`'s `persist-prompt!`.
   Add `:prompt-includes` / `:prompt-excludes` / `:prompt-every-turn`
   predicate kinds that read `logs/prompts/<agent>/<turn>.txt` for the run's
   turns (turn ids are in the store; files are on disk). G2 becomes
   `:prompt-every-turn {:text <question>}`. The #26 salience axis becomes
   honest: assert the seeded claim TEXT rendered in the prompt the agent
   acted on (the surface existed) as a separate predicate from "the agent
   consulted" — distinguishing "context failed" from "agent ignored context".

2. **Structural per-turn parity check** (closes #2's other half). A
   driver-emitted, schema-validated `:seon.gym/turn-profile` per turn:
   section name list + per-section char counts, taken from
   `assemble-context`'s `:seon.render/section-texts` (the composer already
   returns it — call it once per turn alongside `render-prompt`, or have
   `run-turn!` thread it through). Two checks fall out structurally:
   (a) the section list equals `(map :seon.ctx/name (substrate-default-ctx))`
   minus blank-rendered names — derived from the code, not a list in a test;
   (b) cache-prefix byte-stability: rendering twice against the same db value
   must be byte-identical up to the `:transcript` boundary (the provider-
   cache invariant, currently asserted nowhere).

3. **World-churn scenario mechanic** (closes #1 for channels 3–4). New
   scenario key `:seon.gym.turn/before` (or a `:seon.gym/churn` fixture
   layer) executed between turns, with two STRUCTURAL generators —
   - `:reboot` — re-run the boot's own seed sequence
     (`all-entity-schemas-tx-data` + `substrate-index-tx`) against the same
     scratch conn after mutating the registry (register one extra schema),
     simulating a pod restart on a populated store; assert idempotence
     (no duplicate catalog rows; the live `:db/ident` upsert claim) and that
     the NEXT turn's prompt reflects the new rows.
   - `:foreign-write` — a `with-agent <other-id>` transact of
     registrations/rows between turns, asserting cross-agent surfaces
     (warnings, domain-attrs, finding-claims) appear in the next prompt.
   Both reuse boot/eval code paths verbatim — no hand-written row soup.

4. **Trigger-driven wake mode** (closes #3). Optional
   `:seon.gym.scenario/wake :trigger`: the driver installs
   `install-user-trigger!` on the scratch conn with the scenario's llm-fn and
   lets `message!` do the waking; await-idle = poll
   `:seon.agent/state` + the `!kick-scheduled` latch (expose a read fn).
   Encode the hop-guard scenario (hops ≥ cap wakes nothing) and the
   double-message-single-loop latch scenario as stubs. The known stub
   self-wake bug stops being a reason to avoid the path once the #35 replied
   halt terminates the loop — re-evaluate that ns-docstring caveat.

5. **Restart/resume scenario** (S-06, closes #1 channel 5). The `:memory`
   backend is keyed by uuid within the process: drop the agent's compile
   state, `d/connect` the same store id, re-run the resume path
   (`start-agent!`'s resume branch), then drive one more turn. Assert the
   asymmetry deliberately: the prompt contains prior MESSAGES but the evals
   walk only the new session — if that asymmetry is a bug, this scenario is
   where it turns red.

6. **Auto-append standing predicates** (closes #8). The driver appends
   G1 (terminates/idle/done), G3 (no blank messages), G5 (multi-segment
   attrs), and the new prompt-based G2 to every scenario at load —
   catalog intent becomes code; scenarios only add their specifics.

7. **Harness hygiene** (closes #5, #6, #9): wrap each async test's `done` in
   a call-once guard (or find/fix the actual double-resolution in
   `run-paid!`); print `SEON_GYM_PAID` + the resolved scenario set at suite
   start; key scorecards (scenario × sha × run-uuid) so a double-run is
   visible instead of ambiguous; paraphrase s32's seeded `:question` away
   from the asked question and/or anchor the consult predicate on the seeded
   DOMAIN attr names (`:my\.kb\.codebase/`) rather than any `:my\.`.

8. **Registry hygiene** (closes #7): `substrate-index-tx` (or a gym-side
   filter) limits `:seon.schema` rows to keys whose owning ns ∈
   `(client/substrate-ns-set)` ∪ `my.*` — derived from the boot's own ns
   set, not a deny-list of test namespaces.

9. **Live-shadow tier (read-only, zero spend)** — the direct answer to "the
   gym must benchmark the SYSTEM as it now operates": a gym mode that runs
   `assemble-context` against a db VALUE of the real cluster store for each
   live agent and evaluates the structural predicates from (2) plus
   invariants (budgets respected; no render-error lines; FilteredDB schema
   fallback exercised; catalog renders rows whose attrs aren't in the local
   registry). No transacts, no LLM. This makes the benchmark track the real
   store's accumulated mess on every run — the gym inherits live drift
   instead of being insulated from it.

Sequencing suggestion: 7 (cheap, restores measurement integrity) → 1+2
(prompt evidence + structural profile; biggest hides-problems closure) → 6 →
3 → 9 → 4 → 5 → 8.

## 5. Open questions for the user

1. **Should a gym tier run against a fork/copy of the REAL cluster store**
   (datahike keep-history makes a point-in-time db value cheap to read;
   a writable fork is harder) rather than only synthetic worlds? The
   live-shadow tier (sketch #9) gets most of the value read-only — is
   writable-fork worth the machinery?
2. **Is cross-pod-restart session reuse in `ensure-session!` intended?**
   Docstring says per-pod-run; implementation reuses any DB session. This
   decides what the resume scenario (sketch #5) should assert.
3. **Who re-baselines stub scripts for #35?** The replied-halt changes
   scripted-replay turn counts; the loop-economy agent landing it should
   probably own the gym re-run, but that hand-off isn't written anywhere.
4. **Prompt blobs as scorecard evidence:** should the scorecard carry the
   prompt-file paths (or hashes) per turn so a moved number is diffable to
   the exact context bytes that produced it?
5. **The paid-gate anomaly (E6)** could not be confirmed from the logs —
   was it observed interactively? If so, the repro (exact `--paid=` value)
   is needed; the current `enabled?` code reads correct.
