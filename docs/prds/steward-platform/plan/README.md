---
type: plan
status: active (the roadmap; the owner marks it up; unsettled.md is the working edge)
created: 2026-09-16
tags: [plan, steward, roadmap]
---

# Self-building Seon — the index of real tasks

> Owner, 2026-09-16 01:20Z: the DATA comes first. Read
> [namespace-data-model-2026-09-16.md](namespace-data-model-2026-09-16.md)
> before this index; sections B–F here are its consumers.

Owner (2026-09-16 01:05Z): "no fake tasks … index all the real tasks we need
to achieve this level of self building and repair." This page is that index.
It is the ONE ordered list for the steward platform; `unsettled.md` records
what moved and when. Every entity names the mechanism it builds on (file), what
is genuinely missing, the proof that closes it, and its dependencies. Status:
**landed** (commit), **in flight** (lane), **planned**, **decision**.

## Long-term plan and schedule (orchestrator; last updated 2026-09-17 03:35Z (real clock; earlier labels in the working edge ran up to 2 h fast); owner: "keep the plan up to date and include all the future plans so they don't get lost")

**How to resume after a compaction:** read this section, then
[unsettled.md](unsettled.md) from its last "RESUME HERE" block, then
`bin/codex-agent status`, `git status --short`, `bin/seon status`. Every lane
below has a landing note under `../research/`; every red has an owner here.

**The mission** (PRD section 0a): an AI-first programming environment where
the program graph in the database makes certain failures impossible and
refactoring data-driven. **The owner's sequence** (~00:45Z): find the
failures → mine the easy ones as issues → stabilize → live agents → persist
reliable fixes to disk → iterate. **Standing rules this session added:**
resets are one command and refuse in seconds (proven); the hook sees every
write including shell writes (Claude verified; codex verified only with
`--dangerously-bypass-hook-trust`, now in the launcher); a hook config change
requires restarting codex lanes (they snapshot hooks at start); ≤4 editing
lanes is the cap and was exceeded all night — expect load; lanes never run
`bin/test`; one lane launch per shell.


### The schedule from 2026-09-17 19:50Z (new orchestrator; supersedes "Today" above)

Owner's three priorities, verbatim intent: (1) every function carries a
contract, private included, and instrumentation arms every contracted
function; (2) issue mining is the agents' work, but the critical findings are
fixed by us first and triaged as we go; (3) the test system migrates into the
runtime so an agent asks for its tests and gets recorded results back when
nothing changed, running exactly the changed tests otherwise — "critical we
get this right".

**Standing rules of this schedule.** One orchestrator session; at most four
editing lanes; research passes read-only; the platform tier must be GREEN
before a wave launches; every landing gets a cold `bin/test --paths <files>
-- <namespaces>` from the orchestrator and a ledger line in the same beat;
`--platform` runs at every wave boundary and after any landing touching
boot, cluster, store, runner or the indexer; `steward-platform` merges to
`main` at each green platform checkpoint; a lane that meets a held file
stops at that item and continues with the rest (per item, not per lane);
sol at capacity falls back to astra low (owner, 19:55Z); every lane spec cites
the triage note's row and carries raw evidence, never an attribution.

**Tracks** (serial inside a track, parallel across tracks; file ownership is
the scheduling constraint, named per lane):

| Track | Lanes in order | Owner | Files | Depends on |
|---|---|---|---|---|
| **0 Platform green** | `fabricated-symbol-edges` (running) → cold platform gate → `bin/seon reset --force` (stored fabricated members) → Juniper reseed → the seven serial gates (`reset-batch-2026-09-17.md` §"Reset procedure" step 6) → merge to main | orchestrator | — | — |
| **A Test system** | stage 1 (resume `test-system-stage1` with `tmp/orchestrator/worktree-patches/test-system-stage1-wip-2026-09-17.patch` reapplied; absorbs triage #14 gate-set-shrinks-silently since it rewrites `gate-sets`) → stage 3 (claims at the writer; `bin/test` a launcher; old paths deleted; absorbs #16 record-latest-tx) → stage 2 (resolution by identity; needs parent S1 for agent tests) → stage 4 tally (Opus) → stage 5 docs (Opus) | astra | `src/seon/test.clj`, `src/seon/test/runner.clj`, `selection.clj`, `cache.clj`, `fn.clj` gate-set seam, `seon.test*.edn` | Track 0 |
| **B1 One predicate** | `private-contracts` resumed: triage #1 (nine copies onto `seon.error/error?`), then #5 `transact-call`, then #17 `config/refuse!` as a value | sol (low) | the nine call-site files, `db.clj:3707-3763`, `config.clj` | **C1 design note landed (owner, 2026-09-17 19:55Z: the predicate waits for the design)**; then Track 0; runs first in its wave because its edits are one-line replacements across files other lanes need |
| **B2 Turn and cluster writer reds** | `audit2-blockers` resumed with triage rows #2, #6, #12, #8, #13 | sol | `src/seon/turn.clj`, `src/seon/cluster.clj` | B1 landed |
| **B3 Detector, recorder, delivery** | `audit3-blockers` resumed with #3, #4, #11 (its items 1–2 were never named; the note says so) | sol | `src/seon/issue/detect.clj`, `src/seon/error.clj`, `src/seon/cluster/message.clj` | B1 landed |
| **B4 Database read seams** | #18 declarations-as-refusal, #19 replay-read default arm, #20 projection-from-empty-table | sol | `src/seon/db.clj`, `src/seon/schema.clj` | B1 landed (db.clj is shared) |
| **B5 Indexer and operator graph** | #15 indexer writes error keys; #21 operator outside the graph (`source-roots`) | sol | `src/seon/fn.clj` | Track 0 lane 1 and stage 1 both released `fn.clj` |
| **B6 The dead turn proc is visible** | live-trial blocker: agent procs in `runtime_status`; a proc death is a fault naming the agent; the mailbox→turn drop counted; partitioned fault-evidence admission so the classifying key survives the cap (the same class hid today's supervision refusal) | sol | `src/seon/cluster/agent.clj`, `oversight`, `error.clj` recorder seam (coordinate with B3) | Track 0 |
| **B7 Namespace page** | `a-namespace-page-serves-twenty-seven-megabytes-in-thirty-four-seconds`: the route renders `full-html-view` for 503 namespaces; find the expanding walk; then #22's render/derivation reads | terra | `src/seon/render/ns.clj`, `route.clj`, `walk.clj`, `render.clj`, `ai.clj`, `search.clj` | Track 0 |
| **B8 Small owners** | #7 plan ownership fails open; #9 error keys as program facts (`sci/eval.clj:720`); #10 lost effect settlement; schema reconciliation not idempotent (`test_support_test.clj:293`) | terra/luna | `plan.clj`, `sci/eval.clj`, `effect.clj`, `schema.clj` reconcile | B1 landed |
| **C1 Error and data-model design** | `error-and-data-model-design` resumed (design only; its note was never written): the error value family, the shapes the audits found, the checks, the campaign order | astra (high), read-only | its note | now (research, not an editing slot) |
| **C2 Contract campaign** | contracts in the audits' order on the critical private read-consumers and error-returning functions, breakage welcomed as findings, one namespace per lane | sol ×2 | per namespace | C1 landed; B1 landed |
| **C3 Live agents, first paid slice** | the four `seon.test.cache` members to ONE agent (triage §4), budget 8, deepseek-flash, an Opus driver recording opening bytes, every turn, the settlement's test run; then the 27-row easy pool three at a time | orchestrator + Opus driver | — | stage 1 + stage 3 (owner's order), B6, B3's #3 |
| **C0 $0 loop probe** | rerun live trial 1 on the fresh cluster: generate → tests → start → opening; zero provider attempts | Opus driver | — | B6 landed (or before it, to re-confirm the blocker on the reset base) |

**Waves (editing slots; research lanes do not count):**

1. **Wave 1 (now):** lane `fabricated-symbol-edges` (editing); C1 design resumed (research). Then Track 0's gate, reset, serial gates, merge.
2. **Wave 2 (platform green):** A stage 1; B6 dead proc; B7 namespace page; the fourth slot stays free for the first B lane the moment C1's note lands (B1 first, then B2 and B3 in turn — the owner ruled 19:55Z that the predicate and the repairs spelled with it wait for the design). C0 probe as soon as B6 lands.
3. **Wave 3:** A stage 3; B1 → B2/B3 (design landed); B4 db seams; B5 indexer/operator (after stage 1 releases `fn.clj`); B8 smalls.
4. **Wave 4:** A stage 2 → 4 → 5; C2 contract campaign (two lanes); C3 first paid slice once stage 3 and B6 are proven live.
5. **Wave 5:** S5 write-back (Phase 5 below) once a worker-authored change is green through the merge gate.

**Cadence of proof.** Fast tallies from lanes are iteration, never proof;
the orchestrator's cold gate per landing is the proof; `--platform` at each
wave boundary; a live observation (page, turn, fault fact) for anything
touching the running system, named as hot-reload, fork, or in-place
adoption. RESET NEEDED is recorded, never improvised.

### Phase 0 — stabilize (now)
| Item | Owner | Status |
|---|---|---|
| Default's "config loss" | orchestrator | ROOT-CAUSED + repaired live 03:40Z: no fact was lost; an intermediate hot-reload of an uncommitted db.clj hunk broke every `seon.db/pull`, and two config readers read the error value as a row. Issue resolved. Follow-up below. |
| `seon.config/effective-in` (config.clj:579) and `population-transaction-data` (:452) return the pull's error value instead of reading it as a row/absence; one regression through a refusing pull | Opus (fully specified; launch when an editing slot frees) | queued |
| Two platform reds in batch 116 A: `declaration-population` tests measure 0 resource reads because the R4 memo (`5e54c9ae1`) serves the second resolution; tests must invalidate through the memo's declared seam before measuring | Opus (fully specified; launch when a slot frees) | queued |
| Gate recording refused `:seon.test.run/immutable` (runner.clj:2486) on batch 116 A — a run row with different provenance already exists for the run id; retained root `tmp/test-runs/run.jHOFSg` | astra `test-system-stage2` (owns the recorder) | fold at its next stop |
| Guardrails item 2: declared bounds, overrides refused | orchestrator | integrated `0db8b71bc`; items 3–4 resume when a slot frees |
| A first-party namespace under `resources/` (`seon.operator.state`) is never reloaded by development adoption → adoption fails after any change to it; move it under `src/` | Opus (spec in the issue) | queued; reset is tonight's repair |
| Adoption refused tree-wide (S3's seam) | astra `acquisition-by-provenance-s3` | dissolved by `684f185f8`; then the invalid issue note, the resources reload gap, and the hold bound each blocked adoption in turn — all root-caused (see the working edge) |
| Platform tier refused: registry tests reach a declared destroyer since the fs consolidation (batch 115 A) | astra `reset-is-total` | landed `d65cc688c`; cold proof = batch 116 A |
| The whole-entity validator ADMITS an incomplete create (new `:seon.fn` row missing ns + admission source) | Opus | REFUTED by probe: the create path refuses at HEAD; class regression landed `770cf35d3`; the real hole is identity-less entities never validated (issue filed) |
| `record-tx` creates test rows without admission source (3 batch-115 reds) | astra `test-system-stage2` | in flight (folded) |
| Two-gate test eats the silence bound and hides every red (blocker) | astra `lane-guardrails` (bounds) | in flight |
| `declared-row…delta`: sci/eval.clj:303 dropped `:seon.schema/ns` (writer regression) + stale shape expectation; hunks in `baseline-reds-sci-eval-documentation-2026-09-17.md` | astra `acquisition-by-provenance-s3` (holds the file) | at its next stop |
| Lane guardrails: lane identity, no lane `bin/test`, declared bounds, overlay completeness, orphan announcement, resume reads the session id from the launcher record | astra `lane-guardrails` | in flight |
| Hook: loophole inventory; `agent_id` recorded; codex drops PostToolUse blocks → every refusal exits 2 with stderr reason; scan on every PostToolUse | Opus hook agent | landed `db0c51fa6` `8952da44f` |
| Independent end-to-end hook verifier on both platforms | Opus | queued behind the hook agent |
| `datahike.api/with` admission-bypass detector | Opus | queued |
| Adoption/republish margin: hold bound was a duration constant applied to the holder itself | astra `adoption-margin` | landed `c772db2d3` `be9c90e2f` `627a24047` `ad75bab51`: phase-liveness hold bound, per-phase timings, adoption 181 s → 37 s; default adopted |
| Cold gates for every slice landed | orchestrator | batches 116–120 run and routed; **platform tier GREEN at batch 120 (97/686/0/0, HEAD ad5d09b01)** |
| Load cap: ≤4 editing lanes, three test slots shared with iteration | orchestrator | EXCEEDED at 03:15Z (six codex + three Opus); nothing new launches until batch 116 has run; prune before adding |

### Phase 1 — the one reset
| Item | Owner | Status |
|---|---|---|
| Edge retype (indexed symbol sets), stub minting + tombstones + second validator deleted, G4 digest required, the 19 unsatisfiable-required keys, strict deletion refusal, `archived-tx`, `capability-fn` deleted, fn.ast merge-then-delete (Q2 A), S2, S6 | integrator on worktree branch `reset-batch` (pushed) | retype + deletion landed on the branch (`357628778`), rebased on the study; G5 (projection-carried budget, option 1 taken) and the executable reset order in flight; query-cost contract = absolute budget + reported ratio (taken) |
| Validator cost on the writer thread (75.9 → 41.6 s; more owed) | integrator | in flight |
| Message/wake/provenance model (§1h): all seams landed (`78cc3b9b7`, `a50424f6b`, `57581f12f`, `31ac4c05d`); default reforked for the origin type change | astra `message-wake-model` (high) | landed; batch 119: its `gen.loop-test` `inst-ms`-on-a-Long errors (3) and `situation-totality-property` are its residue — resume with the lines when a slot frees; live wake-flip proof after adoption works |
| SCI arity-message parity: `an-instrumented-multi-arity-miss-reads-like-clojure` 4F (eval_test), known since the no-default lane's baseline | Opus triage | queued after the platform tier is green |
| Datahike modeling study: second opinion on the reset batch; its corrections OVERRIDE prior schema decisions (§1i) | astra `datahike-modeling-study` (high) | landed `bd5923a8c`; integrator stopped+resumed on its correction table 03:55Z; Q2 AST = A and message subject = A taken as recommended (vetoable) |
| Data-modeling decision guide (owner: retract vs archived, refs vs identities, one reasoned guide) | Opus | landed `ec350ece0` (`docs/seon/architecture/data-modeling-guide.md`); its link lines in AGENTS.md + both skills uncommitted (files held) |
| Reset of default with the whole batch + Juniper reseed + live proofs (walk parity; a deletion refused naming callers; an archived issue retracted) | orchestrator | after the branch is green and merged |

### Phase 2 — the runtime is the database
| Item | Owner | Status |
|---|---|---|
| S3 base SCI context as pure `(base-ctx db)`; overrides by provenance; regenerate on accepted change; installer kept as measured optimization with equivalence regression | astra `acquisition-by-provenance-s3` | landed `684f185f8`, reviewed; cold gate batch 117; live adoption proof after the reset |
| S12 REPL operations: reads `a2e16338b`, writes `f5d268ed6` (breaks-first `ns-unmap!`/`remove-ns!`/`ns-unalias!`, `overrides`) | astra `repl-program-operations` | landed; owed: hook-arm hunk (sci/eval.clj held by S3) + AGENTS.md vocabulary rows; live proof after adoption works |
| S12 after the reset: `rename!`, `move!`, `change-contract!`, `revert!`, `launch!`, `seon.program/unresolved-callers` | astra | queued (Phase 1) |
| Tier 3: implementations as declarations (defmethod/protocol bodies as rows) | astra | queued (Phase 1) |
| Debug page outline (turns → units → HTML with AI-text toggle; comments as thinking; raw prompt) | Opus | landed `c81946d4a` (`?outline=true`); screenshots blocked by the config loss |
| S10 conversational reply | astra | queued (S7, S11 landed) |
| S8 root collects at 2× automatically | Opus | queued |
| Small fixes: pull's 1,000 cap on reach reads; captured-history compare; `:entity-id/syntax`; `:defined-by`; reporter ex-data; the reporter's FAIL line pointing at the enclosing `let` | Opus | in flight |

### Phase 3 — progressive testing
| Item | Owner | Status |
|---|---|---|
| Stage 1: one selection function over symbol edges | astra | NEXT after the reset — priority per the owner (live agents need it) |
| Stage 3: workers claim from the run entity; `bin/test` a launcher; old runner paths deleted | astra | right after stage 1 — priority |
| Stage 2: resolution through one owner, claim/completion, unchanged-green reuse in `run-owned` | astra `test-system-stage2` | landed `a0c69cfd9` `163367a9a` `b80e7f615`; cold proof in the post-reset serial gates |

### Phase 4 — live agents
| Item | Owner | Status |
|---|---|---|
| **Ruling §1j (2026-09-17 morning): every function carries a contract, private included; instrumentation arms every contracted function** — slice 1 arm private functions; slice 2 contracts on the critical private read-consumers (352 by query) and error-returning functions, welcoming the breakage as findings | astra `private-contracts` (high) | in flight |
| Issue triage design: namespace steward agents triaging and launching sub-agents to write contracts and sanity-check data in/out; the `private-function-without-contract` detector (3,145 today) as the first mined class | astra `issue-triage-design` (high, design only) | in flight |
| First paid tasks = EASY private-contract issues (one call site, one caller, reaching tests exist), from the four audits + the detector; start three with budget 8 on the cheapest model | orchestrator | after stage 1 + 3 land (owner's order) |
| Four read-only audits of the critical private functions | Opus ×4 | all landed (`7506b8c59` `64d6a85cd` `3d1ccde52` `c314408c0`): 3,145 private without contracts; 72 refused-read-as-row instances; `seon.db/q` symbol codec blocker; operator outside the graph |
| **Ruling §1l: the data-model analysis first** — the error value family (kind/class/critical), the shapes the audits found, the checks and what they emit, the campaign order with schemas declared before contracts | astra `error-and-data-model-design` (high, design) | in flight; the contract campaign waits on it |
| Issue triage design (steward per namespace, per-function findings, acceptance deftests, priced slices) | astra `issue-triage-design` | landed `7a6173b22`; slice one = `seon.render.web`, one steward, three workers, after the reset and the data-model design |
| Serious bugs from the audits (ours): one error predicate (`seon.error/error?`, seven private copies dissolved); `require-open-run` on a refused read; the detector fabricating issues; the fault recorder's fail-open existence checks; the gate set shrinking on a refused read; `transact-call` relabelling refusals; `config/refuse!` without a message | astra `private-contracts` (predicate), sol `audit2-blockers`, sol `audit3-blockers` | in flight |
| Live trial 1 (`5148553f7`): generate → tests → start → opening all good; the worker's turn proc died silently on every wake (stale projection + oversight silence) | Opus driver | landed, $0; rerun on the fresh cluster after the reset |
| A dead agent turn proc must be visible: agent procs in `runtime_status`, a proc death is a fault naming the agent, the mailbox→turn drop is counted; fault evidence admission partitioned so the classifying key survives the cap | sol (after the reset) | queued, serious |
| Owner inspects a prepped agent's context on the debug outline | owner | with the outline |

### Phase 5 — write-back and iterate
| Item | Owner | Status |
|---|---|---|
| S5 write-back of accepted definitions by exact span, gated | astra | after Phase 4 proves reliability |
| Listening redesign (matching over the transaction log; `:seon.listen/entity` widening) | owner designs | parked by ruling; `what-listening-is-2026-09-16.md` |

### Owner decisions — answered this session
- 2026-09-17 19:55Z (new orchestrator): the one-predicate consolidation and the repairs spelled with it WAIT for the §1l design note; the first paid live-agent slice runs after stage 1 + stage 3 land and the dead-turn-proc fix is proven live (a $0 loop probe earlier); `steward-platform` merges to `main` at every green platform checkpoint; sol at capacity falls back to astra low.
- Deletion: strict, no escape; all fixes in one transaction verified on `:db-after`, or farmed to agents. An agent's own unreferenced definition deletes trivially.
- Agents: never retracted; an archived positive fact, hidden from the UI.
- `:seon.fn/capability-fn`: ref deleted (decided from research; overturns G2).
- Forks: pushed (Datahike main, SCI `seon-env-hook`); no PRs. Main branch, `reset-batch`, and `wip-snapshot-2026-09-17` pushed.

### Owner decisions — still to ask, each with background and a real row, in batches of four (owner: "explain the decisions more clearly; use the questions tool; if you don't know the options do the research")
1. Message/wake/provenance model — after the research lands (present as a researched recommendation with pros/cons).
2. Issue lifecycle at the reset: derive `resolved-tx`/`superseded-by` from the enum before deleting it (1,437 terminal rows; recommended) vs delete and re-derive from notes (blank window).
3. `:seon.context.capture/prompt`: keep and drop `no-history?` (temporal calibration; unmeasured growth) vs keep `no-history?` as a latest-value dial.
4. `-at` instants vs transaction refs: family-wide rule with an exception list in AGENTS (recommended) vs narrow rule.
5. AST family: merge role then delete (≈1 day, recommended) vs delete outright (≈½ day).
6. `:seon.fn/call-arities`: `[symbol long]` tuple vs an interned (callee, arity) identity family (≈½ day, real refs).
7. `seon.commit` entity vs commit sha as a value.
8. D2 (public-without-doc) scope: src only (recommended) vs test helpers too.
9. Shape-row reclamation: leave and count at each reset (recommended) vs a maintenance task.
10. Retention removal (`5a10f5dfa`, reversible) stands unless vetoed.

## The target loop, in one paragraph

A task is a entity: instructions, namespace, subject, a set of deftests that
define done, a budget. Starting it creates a worker agent entity with its
first turn open, in the same transaction. The worker iterates until every
test in the set is verified on the current reach digest; it may add tests,
never remove them. Batches run on forked clusters, one per namespace; a
finished batch merges its changed program entities into the shared branch, the
merge gate runs exactly the tests whose reach changed, and approved entities are
written back to their files by exact span. Signals derived from facts open
the tasks: red tests, untested and uncontracted functions, recurring faults,
missing render pairs, ugly output, lint findings, indexed issues, and a
user's unanswered messages. A namespace's steward is the engineer
responsible for all of it. Triggers and scheduling plug in as callers of
`start!` later (owner ruling 2026-09-16).

## A. Task definition and instance

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| A1 | `my.task` schema: id, title, instructions, namespace, subject, tests `[:set {:min 1} ref]`, budget, agent, from; entity map with units and pair | `resources/seon/schemas/*` population; `:seon.render/units` on `seon.agent.edn`; walk `declared-concerns` (`src/seon/render/walk.clj:88`) | the resource, the writer's empty-set refusal | fixture: transact the two entities in [task-prototype](task-prototype-2026-09-16.md) §2; pull and render | — | planned (slice 1) |
| A2 | `my.task/start!`: creation-tx + agent ref + budget overlay + plan step + `generated-run-tx`, one transaction function | `seon.cluster/ensure-entity!` (`src/seon/cluster.clj:2259`), `bootstrap/seed-tx`, `turn/generated-run-tx` (`src/seon/turn.clj:728`) | the generalisation; the bootstrap's hard-coded task becomes a entity | live: `start!` on default → armed worker, opening rendered with the task block | A1 | planned (slice 1) |
| A3 | `my.task/status` and the AI/HTML pair: instructions, subject, each test red/green/unrun, exact completing calls | plan/message pairs as the pattern (`src/seon/plan.clj:1229`, `src/seon/cluster/message.clj:379`) | the pair, the `:seon.test` entity pair (audit A: absent) | the opening bytes in §4 of the prototype, recorded | A1 | planned (slice 1) |
| A4 | Open test set: workers add test refs, retraction refused except by the creator; minimum one | `[:db.fn/call …]` writer decision (datahike skill) | the writer | regression: add succeeds, worker retract refused, creator retract allowed, empty refused | A1 | planned (slice 1) |
| A5 | Settlement runs the task's tests in-process before `plan/settle-call`; done-query = every test verified on the current reach digest, digest supplied as a query input | `seon.plan/settle-call` (`src/seon/plan.clj:562`), `seon.test/run`, turn settlement seams (`src/seon/turn.clj:2192`, `:3412`, `:3451`, `:4623`) | the call and the third query input | live: the worker never calls complete; the step closes on the run entity | A1, B1 | planned (slice 2) |
| A6 | `my.task/copy!`: new entity from a template entity with a new subject; id via `seon.id/id`; `:my.task/from` | `seon.id` | the function | regression | A1 | planned (slice 4) |
| A7 | Interim done-query for slice 1: latest result green with run basis after item creation | audit A `green-after?` lower bound | nothing new; replaced by A5 | — | A1 | planned (slice 1) |

## B. Test evidence: know what is tested and what must re-run

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| B1 | Per-test reach digest recorded on every result; `verified?` (db, test) and `stale`; `check` selects by staleness; in-JVM runs as ordinary REPL commands | reach rules (`src/seon/fn.clj:790`), `record-tx` (`src/seon/test/runner.clj:1420`), `seon.test/run` | the digest, the arity, the selector | regressions named in the lane spec; live: second `check` with no edit executes zero tests | call-edge completeness (B3) | in flight: lane `reach-digest`, beat 1 probes |
| B2 | Full run once per HEAD by the gate-owning session; later gates derive outstanding coverage and print existing reds | [suite-efficiency plan](../../context-generation/research/suite-efficiency-plan-2026-09-15.md) (`d64e304e8`) | admission-before-execution in `bin/test`; recorded reds surfaced per lane | a bare gate after a recorded full run executes only stale tests | B1 | planned; plan reviewed |
| B3 | Call-edge completeness for agent-admitted definitions (Juniper's test lacked its edge) | n7 landing (`src/seon/fn.clj` runtime analysis returns core + `my.*` edges) | the regression on the Juniper shape inside B1 | B1's fourth regression | — | in flight within B1 |
| B4 | Fixture-observation declarations published as program-entity facts; the post-adoption check runs cheap reaching tests first and defers declared observations with their exact command | hook lane member 2, option 1 | the entity facts, the deferral | hook feedback after an edit reaching the docstring-examples test arrives within the bound | — | in flight: lane `hook-publication-race` (resumed) |
| B5 | In-process runner keeps the drift guarantee: a test mutating worker-global state is detected | runner drift detector | the same check on the in-process path | regression with the two batch-12b mutating tests | B1 | planned |
| B6 | Program-digest provenance discrepancy on run entities | `runner/program-digest` (`:1358`) | one stable derivation | [issue](../../../seon/issues/recomputed-program-digest-disagrees-with-the-stored-run-digest.md) | — | open, friction after B1 |

## C. Signals as facts: how a namespace knows it has problems

| # | Signal | Facts today | Missing | Task it opens (required tests) | Status |
|---|---|---|---|---|---|
| C1 | red test | `:seon.test/fail-count`, `error-count`, runs | none | fix `T` (T itself) | derivable now |
| C2 | public function without a reaching test | `seon.fn/functions-without-tests` (288) | none | test `F` (a reaching test the worker writes, then required) | derivable now |
| C3 | incomplete contract (`:any`, `:some`, bare value, unguarded variadic) | `:seon.fn/spec`, schema-audit checker | expose the checker as a query function (`seon.fn/contract-findings`, audit A chain 4) | contract `F` (contract-complete predicate as a test) | planned |
| C4 | recurring fault in my functions | `seon.error` entities, `:seon.instrument/fn` → ns → steward (`src/seon/error.clj:1095`) | none for detection; `:seon.test/error-signatures` for the regression link (audit A chain 2) | regression for signature `S` | planned |
| C5 | unresolved or missing call | `:seon.fn/calls` to identity stubs | `:seon.fn/unresolved-calls` at the analyzer seam (audit A chain 5) | resolve `F`'s calls (caller's reaching tests) | planned |
| C6 | entity schema without a render pair | schema entities, `:seon.render/ai` props | none | declare the pair (a render test on the fixture) | derivable now |
| C7 | ugly or elided output | `:seon.eval/shown` text; elision maps in memory | `:seon.eval/elisions` observation facts (audit B §4) | bound the render (render bound test) | planned |
| C8 | lint findings | kondo in the hook; `:seon.fn.file/findings` as a value | store findings per entity at index time | fix the finding (reaching tests green + finding gone) | planned |
| C9 | issues | 236 markdown notes, `bin/issues-index` | index frontmatter, namespace tags, status as facts at publication | the issue's own regression | planned (small side lane) |
| C10 | duplicate behaviour, parallel code paths | nothing stored | candidate derivation: same output refs and overlapping calls, same keyword footprint; plus the recorded human judgments from `docs/seon/issues/` n11 class | dissolve `F` into `G` (both sets of reaching tests green, one identity retired) | decision: start from recorded judgments |
| C11 | cross-namespace red-test attribution and steward alerts | [attribution plan](../research/test-attribution-plan-2026-09-15.md) option 1 | `:seon.test/stewards` on the result/adoption transaction | both stewards woken with T, F, R | decision (owner): later, as a caller of `start!` |
| C12 | a user's unanswered messages | `seon.message` from/to/about/inbox | `seon.cluster.message/unanswered` and the relative predicate (D1) | reply (D1's test) | planned (slice 3) |

## D. The chatting task and the steward as engineer

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| D1 | Chatting task: one persistent entity, repeated sessions; test = no inbound message from the user newer than my newest reply to them | inbox wake (exists), message facts | the predicate as a contracted function and its deftest; test custody when settlement runs a test reading cluster facts | live: two user messages across two sessions on one entity; the test flips red and green each time | A1–A5 | planned (slice 3) |
| D2 | The steward starts workers from a conversation: `start!` called by an agent | A2, `my.*` protocol | `my.task/start!` as an agent-callable function with call preparation | live: root spins up a worker on a request from the user | A2 | planned (slice 3) |
| D3 | Steward standing task per namespace: required tests derived from C1–C9 for that namespace; the namespace picture pair for `:seon.ns` renders them | `:seon.ns/steward`, `seon.render.ns` pair (`src/seon/render/ns.clj:846`) | the derivation function; the picture's extension | live on one messy namespace: the steward's opening lists its real problems as tests | A, C | planned (slice 4) |
| D4 | Budget as settings overlay at start; `turns-left` unchanged | `max-episode-runs` overlay | nothing beyond A2 | — | A2 | planned (slice 1) |

## E. Batches, isolation, merge

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| E1 | One forked cluster per namespace batch; workers admit definitions there only | `bin/seon init NAME` forks the published commit; cluster = branch + agents | operator support for N batch clusters from one command; cleanup of finished ones | two clusters running two namespaces' workers concurrently | A, D3 | planned (slice 5) |
| E2 | Changed program entities since the fork basis, as a pure projection | `seon.db/since`, `seon.program` entity identities and exact source/spec | the projection | regression: edit three entities on a fork, projection returns exactly those | — | planned (slice 5) |
| E3 | Merge writer into the shared branch: exact replacement through `seon.program`; a entity changed on both sides since the fork basis refuses (no three-way merge in v1) | `seon.program/exact-replacement-tx` (`src/seon/program.cljc:840`), `:db.fn/call` | the writer and its conflict rule | regression: clean merge lands; conflicting identity refuses with both sources named | E2 | decision (owner): refusal rule acceptable? |
| E4 | Merge gate: run exactly the tests whose reach digest changed on the shared branch | B1 | the invocation at merge | after a merge the gate executes only the affected tests | B1, E3 | planned (slice 5) |
| E5 | Re-fork on refusal: the task re-forks from the new shared head and re-runs to green | E1, A5 | the operator step | live: a refused merge is re-forked and lands | E3 | planned |

## F. To disk: the real code base

| # | Task | Builds on | Missing | Proof | Depends on | Status |
|---|---|---|---|---|---|---|
| F1 | File and form-span provenance on every indexed program entity | `seon.fn/build-artifact` knows path, entities, identities at index time (`src/seon/fn.clj:1182`); audit A: not durable on entities | `:seon.fn/file` ref and span attributes written at index and adoption | pull any function → its file and span; regression on the fixture | — | planned (slice 6) |
| F2 | Exact write-back: replace the old form's bytes by the new source inside the owning file; append new definitions to the namespace's file; new namespaces as new files (`seon.program/source-files`, audits A/B) | `my.fs/write!` conditional writes (`src/my/fs.clj:59`), exact stored source | the pure assembly and the effect | the ordinary index run over the written files reproduces the merged entities byte for byte | F1, E3 | planned (slice 6) |
| F3 | Round-trip proof and commit: index the written files, gate the reaching tests, path-limited git commit by the operator | `bin/seon init`, B1 | the operator step | a worker-authored change lands in the repository with its tests | F2, E4 | planned (slice 6) |

## G. Platform defects in the way

| # | Defect | Status |
|---|---|---|
| G1 | First debug page after idle costs 2 s (35× warm); root page acquisitions erased by read-only MCP returns | in flight: lane `cold-page-kills` on the [approved plan](../../context-generation/research/cold-page-plan-2026-09-15.md) |
| G2 | Hook false refusal "requires a running operator JVM" after a converged batch | landed `c0006a125` |
| G3 | Post-adoption check times out on expensive tests | in flight: B4 |
| G4 | A value larger than the budget is elided to nothing | [issue](../../../seon/issues/a-value-larger-than-the-budget-is-elided-to-nothing.md); next n1 slice |
| G5 | Two orchestrator sessions writing one gate ledger | decision (owner): which session owns gates |
| G6 | Codex usage limit noted in the gate ledger; lanes launched tonight were accepted | watch |

## H. The live proof ladder (each slice ends in a live run the owner reads)

1. **Slice 1** — A1, A2, A3, A4, A7, D4: a real code task on `my.agents.root` (subject chosen by the owner, no fake tasks: candidates are C2's 288 untested functions, starting with `my.agent/done`, `my.background/await`, `my.edit/exact!`), live on DeepSeek; deliverables: opening bytes, ledger line, explain probe.
2. **Slice 2** — A5, B1 beat 2, B5: completion derived at settlement; the worker never calls complete.
3. **Slice 3** — D1, D2, C12: the chatting task across two sessions on one entity; root spins up a worker from the conversation.
4. **Slice 4** — D3, C3, C4, C9, A6: the steward's standing task on one messy namespace chosen by the owner; issues indexed as facts.
5. **Slice 5** — E1–E5: two namespaces in two forked clusters, merged, gated.
6. **Slice 6** — F1–F3: the first worker-authored change written to disk and committed.

## Decisions pending (owner)

1. The first real subject for slice 1 (an untested public function in `my.*`, or another).
2. Workers add tests, never remove them (A4).
3. Merge refusal rule for v1: same identity changed on both sides refuses, re-fork (E3).
4. The first messy namespace for slice 4.
5. Which session owns gates (G5).
