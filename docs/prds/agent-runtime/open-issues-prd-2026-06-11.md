---
type: prd
status: active
tags: [prd, agent, database]
---

# Open issues — accumulated register + plan (2026-06-11)

**POST-V4 STATE (evening):** the context-v4 refactor + all fix waves
are COMMITTED (through 595aa2b) and pushed; the post-v4 gym sweep ran
on a FRESH world. **THE plan is now
[[fix-everything-prd-2026-06-11]]** — it consolidates the sweep's 7
ranked findings ([[research/e2e-demo-findings-2026-06-08]] §POST-V4
SWEEP) and the 12-row blind-spot table
([[research/context-blind-spots-2026-06-11]], whose headline reverses
the sweep's framing: agents DID consult; the context defeated them)
into FOUR root causes with one general mechanism each, three fix
waves, and the re-measure bar. INTEGRITY RULES standing: gym fs roots
exclude the harness (answer-key leak caught + fixed); fixes must be
GENERAL mechanisms, never scenario-coached answers. Historical tiers
below remain as the register of record.

**Sweep-finding routing into [[fix-everything-prd-2026-06-11]]:**

| Sweep finding / row | Routes to |
|---|---|
| f1 prior domain schemas invisible (+ blind-spot 2, 3, 10) | ROOT 2 — inventory by attr-namespace + loud truncation (Wave A) |
| f2 substrate/`my.*` nses not requirable (+ teaching half) | ROOT 3 require fix (Wave B) + ROOT 1 executable teachings |
| f3 blind same-batch reply (+ blind-spot 4) | ROOT 3 — reply! envelope-aware guard (Wave B) |
| f4 consult-first regression (+ blind-spot 1: dangling catalog pointers) | ROOT 1 — executable teachings + content sweep; ROOT 2 salience |
| f5 s12 storage under-landing (+ blind-spot 9) | §2 DECIDED-widen + ROOT 1 shown-not-told provenance example |
| f6 first-boot seed ordering | Wave C item 6 (verify — likely landed with 595aa2b boot-seed!) |
| f7 judge-rubric staleness | ROOT 1 staleness class; rubric re-verify rides the harness habit |
| blind-spot 5–7 (failing examples, docstring fiction, stub bait) | ROOT 1 — executable teachings + content sweep (Wave B) |
| blind-spot 8 (prose-as-evals) + the downstream consumer 13 | ROOT 3 — parser format contract (Wave A) |
| blind-spot 11 (standing self-warning) | Wave C item 6 |
| blind-spot 12 (lookup illegibility) + wire lookup-ref bug | Wave A unit A1 |
| **s12 provenance predicate** | **DECIDED-widen (user 2026-06-11)** — storage predicate accepts provenance-SHAPED attrs in any namespace; consult/reuse scenarios stay strict; see PRD §2 |

Everything known-open after the 2026-06-10 refactor day (19 commits) and
the P8 measurement (5 paid runs, sha 1ca105a). One doc to plan from.
Board P-numbers in brackets; evidence lives in the P8 sweep logs
(`tmp/gym-paid-sweep*-p8.log`, `tmp/cards-1ca105a-run*.txt`) and the
session's commit messages.

## Tier 1 — fix before Friday's demo

| Issue | Evidence | Fix shape | Board |
|---|---|---|---|
| **Loop economy**: agents answer by turn ~3 then churn check-forms to the 20-turn cap (3/5 paid runs, ~15 wasted turns each) + 2-6 noise onward replies — **DONE 2026-06-11**: `seon.agent/replied-since-inbound?` derivation + loop halt `:seon.agent/halt :replied`; stub-llm state-flip hack removed (#22 zero-forms was already a stop policy, now test-pinned); live-proved (1 turn vs cap 20, scratch agent `aiR-2606111030`); REMAINING: terminates-under-cap predicates for S-32/S-12 (gym under user review) + reply-every-asked-turn wording amendment (proposed in P21 report) | S-32 run1 capped; S-12 B 16-19 turns | `run-agentic-loop!` gains "reply! landed this wake AND no new inbound → stop"; review the reply-every-asked-turn instruction wording; add terminates-under-cap predicates to S-32/S-12 | P21 #35 |
| **Error-handling legibility**: validators throw in `db/internal.cljs`, envelope catch lives in the face — 2 independent agents misread the contract from source (judge red 3/3, genuine) — **DONE 2026-06-11**: `transact!*` now wraps its WHOLE body (conn-resolve, validators, schema install, commit) in the catch→`commit-error-envelope`; contract test pins `internal/transact!*` called directly resolving to the envelope; ctx citations repointed at `src/seon/db/internal.cljs:499`; lookup-ref + bad-ref wording now leads with eid/transact-first and warns AGAINST re-registering an existing attr for identity; identity-everywhere steer applied to the ctx Common-shapes + my.kb exemplars (todo-ns exemplar deferred — `src/seon/agent/**` fenced to P21's agent) | S-12 judges 0-40 across all runs | Hoist envelope conversion into `transact!*` (truth becomes local); fix stale ctx exemplar citations (849-850/871-874 cite pre-split db.cljs:803); review warn fix-example wording (coached the S-21 re-registration flake) | P22 #36 |
| **Gym-world parity**: scratch worlds miss the my.kb ns-source rows (4/7 exemplar blocks); hand-maintained seed drifts from boot | live=7 blocks, gym=4 | Driver seeds via the boot's OWN fns (structural parity); then re-baseline S-32/S-12 (user: correctness > benchmark continuity) | audit in flight + #17 tail |
| **Transcript-render suspicion**: an S-12 agent narrated "the user's last message is missing from the visible transcript" — **VERIFIED REAL + FIXED 2026-06-11**: budget eviction was newest-first over ALL items, so ~14 capped eval rows (≈1.7k chars each) after the user's message pushed it past the 24k budget; messages are now EXEMPT from eviction (kept in chronological position, content capped at `message-render-cap` 4000), eval rows still evict oldest-first; pinned by `transcript-eviction-keeps-messages-under-eval-flood` | KoQ turn Ckz-2606101827 | Verify against the composer before dismissing (possible seon.ctx transcript-window bug) | folded in P22 |
| **Demo prep**: Thursday rehearsal + reset + seeding | script committed 014416e | Run the script's checklist; S-21 3-run stability probe pre-demo | P9 #24 |

## Tier 2 — context quality (gym-gated, the standing method)

| Issue | Notes | Board |
|---|---|---|
| capabilities section lacks the XML wrapper other sections have | SUPERSEDED by [[context-v4-repl-realism-2026-06-11]] V4-6 — the capabilities section dissolves entirely (teaching → docstrings of rendered nses + instruction rows); no wrapper to add | context-v4 |
| identity-everywhere exemplar steer | agents over-apply `{:seon.db/identity true}` (S-21 mutation flake, S-12 single-entity upsert) — one context iteration on the todo exemplar + warn wording. **Partially done with P22 (2026-06-11)**: ctx Common-shapes now presents identity as OPTIONAL (plain values first, "never re-register an existing attr to add identity"), both my.kb register! exemplars annotated with WHY identity, warn `check-bad-ref` + the internal lookup-ref translation lead with eid/transact-first. REMAINING: the `seon.agent.todo` exemplar's `::id` register! (fenced out of P22); gym-measured iteration | with P22 |
| `relevant-roots` growth to post-split faces | ABSORBED by [[context-v4-repl-realism-2026-06-11]] V4-2 — exemplar-roots becomes the namespace-SELECTION policy (`<namespace>` tags); growth decided by gym A/B as before | context-v4 |
| V3-D datahike API block | ABSORBED by [[context-v4-repl-realism-2026-06-11]] V4-6/§3 — datahike teaching rides the included ns sources/docstrings; no separate var-metadata render unit | context-v4 |
| V3-E show-don't-tell | INTENT ABSORBED by [[context-v4-repl-realism-2026-06-11]] §3 — the rendered namespaces + threaded REPL transcript ARE the show-don't-tell; v3e-demonstrated-evals PRD superseded as an implementation plan | context-v4 |
| S-21 instability | zero-register! flake 1/2 — partly the warn wording, partly plan variance; 3-run probe before declaring | with P22/P9 |
| **SOUL/system-prompt hardcoded** (user, 2026-06-11) | the SOUL-derived identity text is a compiled-in def at `seon.ai.deepseek/default-system-prompt` (deepseek.cljs:107) — uneditable without a rebuild, and identity content sits in a PROVIDER ns (placement smell: it isn't deepseek-specific). User directive: ANY user must be able to change this and control ALL content injected into context. Fix shape: load from an editable source — store entity seeded at boot from SOUL.md, same seeded+editable pattern as `my.kb.instruction` (one mechanism); the deepseek def becomes a read of it; SOUL.md the seed, the store the truth. **IMPLEMENTED 2026-06-11**: `my.soul` ns (NOT `my.kb.soul` — `my.kb` is an exemplar root, a child would double-inject the whole prompt into ctx as full-source exemplar, measured +18k chars, blew the 84k turn-0 budget). Two rows seeded at boot: `identity` (SOUL.md read at seed time) + `repl-mechanics`; seed-ONLY-if-absent so a user's transact edit survives reboot; `effective-system-prompt` (now in `seon.ai.openai-compat`, renamed from `seon.ai.deepseek` 2026-06-16) reads the store per call with a one-liner fallback. Tests: `my.soul-test` (seed, no-clobber, request-body reads store). PENDING: pod-level live proof — pod boot blocked by the live-tile `sci-not-available` instrumentation error (tiles unit) | unit landed, awaiting pod-boot fix for live proof |

## Startup/restart reliability (user, 2026-06-11 — DONE, wave-2 unit)

Observed during the 06-11 morning restart; resolved 2026-06-11:

- `bin/seon restart all` excludes the JVM — **DECIDED: documented
  exclusion, not inclusion.** The jvm has no dependency on the pod
  stack (its embedded store is separate from the wire-server's cluster
  store) but hosts the SHARED nREPL 7888 (every agent's REPL, the dev
  hook, the MCP bridge); including it in `all` would let one agent
  sever every other agent's live session mid-flight. `start all` /
  `stop all` now print the rationale + the explicit command
  (`bin/seon restart jvm`); decision documented in the script header.
- JVM MCP bridge cached a failed pre-flight forever — **FIXED**
  (b8b3400 shape): the background init loop is bounded (30 attempts ≈
  2.5 min), so a JVM down longer than that wedged `ready?` false until
  a manual `/mcp` reconnect. `execute-eval`'s not-ready guard now
  RE-RUNS the pre-flight inline instead of throwing the cached verdict.
  Live-proved on a sandbox server (`SEON_NREPL_PORT` testability
  override): dead port → live error naming the port; loop exhausted
  ("failed after 30 attempts"); port then came up (forwarder→7888);
  next eval on the SAME server process returned the value — no
  reconnect. Applies to newly-spawned bridge processes.
- `:runtime-persisted {:instance-count 0}` post-start WARN — **FIXED**:
  not "benign before agents resume" but a check for a retired feature —
  instances register only via the pool-backed `seon.session` path and
  the pool Integrant keys were removed 2026-06-09 (verified live: a
  healthy 4h-uptime multi-agent JVM reads 0). `check-runtime-persisted`
  now checks the registry is READABLE (only a throw is degradation) and
  surfaces the count in `:details`.

## Complexity-audit register (2026-06-11, research/complexity-audit-2026-06-11.md)

Every change the audit flagged, with its owner. The audit doc carries
the evidence; THIS table is the durable routing record.

| Finding | Owner / where it dies |
|---|---|
| ctx: `capabilities-section` ~190 lines hand-written prose inside a fn claiming "DERIVED" | context-v4 V4-6 (section dissolves) |
| ctx: fuzzy-count + uncounted-kinds epicycle (exists only because counts sat in the cache prefix) | context-v4 V4-3 (store-inventory eval — whole epicycle deletes) |
| ctx: `finding-claims-block` dispatches on attrs literally NAMED `claim` (violates our own uniformity-canary rule) | context-v4 V4-2/V4-3 sweep — explicit delete, do not port |
| ctx: 11 numeric knobs + 13-value priority ladder + 5 truncation helpers | context-v4 composer rewrite; truncation collapse = post-demo ladder step |
| ctx: `evals`/`current-ns` query live `db/*conn*` instead of the composer's db value (run-3 bug class LATENT; P22 flagged the same for messages) | NEW unit: unify eval read leg with message leg (post-demo, audit-ranked last) |
| render: 5 HTML paths / 3 AI paths / 3 dispatch mechanisms; three coexisting "hiccup" representations | render drift sweep (one-path sketch in the audit; tiles PRD §8 follow-ups fold in) |
| render: `:seon.db/conn` registered in `seon.render` (once, NOT twice — earlier claim corrected) — wrong namespace forces inline-`:any` copies downstream | render drift sweep: move to `seon.db` |
| render: FilteredDB schema guard ×4 copies (`installed-schema`) | render drift sweep: one home in `seon.db` |
| gym: scorecard schema REQUIRES `:seon.gym.scorecard/turn-profiles` after the gate ripout | handed to the in-flight finisher (must fix or every card fails) |
| gym: `seed-scenario-world!` hand-mirrors `start-agent!`, drifted twice | NEW unit: extract shared `boot-seed!` (post-demo, after V4-3) |
| Ranked order (audit §end) | pre-demo: finish gym ripout then FREEZE; post-demo: V4-2 → V4-3 → boot-seed! → render sweep → V4-0/1/6 → truncation collapse → read-leg unification |

## Tier 3 — platform (post-demo unless cheap)

| Issue | Board |
|---|---|
| P6 splits: agent.cljs → seon.agent + seon.agent.message (real ns for its keywords) + seon.agent.internal; seon.eval/schema/warn faces; deletes P5's transitional alias block (currently bypasses instrumentation) | #18 |
| stub-llm zero-forms termination (pairs with P21's stop policy) — **DONE 2026-06-11 with P21/#35**: zero-forms stop was already in `run-agentic-loop!`; now pinned by `seon.agent-loop-test/zero-forms-terminates-cleanly`; stub-llm's churn-causing state-flip form removed | #22 |
| :seon.turn/error attr — turn failure detail queryable (gym S-08 mechanical) | #23 |
| auto-run agent tests on fn update (tee fixed a53d2a6; the reactive section remains) + analyzer var-digest staleness | #33, P14(c) |
| gym harness: paid-gate anomaly (partial key list enabled all scenarios), async double-done (S-12 ran 2×), stub/paid question-text reuse | P23 #37 |
| small bugs: db-schema helper triplication; MCP tool-description drift (:repl vs :client) | P14 #29 |
| seon.agent.mcp (call the user's MCP servers) — user-wanted, post-demo | #25 |
| Timbre-unified logging; atom kill-list; tile/card unification (PARKED — label naming undecided) | #26 #27 #34 |
| DECIDEs: seon.dev.node-agent keep-or-delete; seon.log tail promotion; handlers.wake relocation | reorg PRD |
| wasm residue: mcp-server-seon still embeds a wasmtime pod; wasm-tauri dir rename + graveyard list (from the shell unit) | reorg PRD |

## Sequencing recommendation

Wed: P21 + P22 (+ audit lands, re-baseline) → S-21 stability probe.
Thu: P9 demo rehearsal on the fixed loop; freeze the substrate at the
rehearsed sha; only demo-blocking fixes after.
Post-demo: P6 → roots-growth A/B → V3-D/E (one measured unit each) →
the Tier-3 ladder in board order.

## Agent-reported issues log (2026-06-11, full-day sweep)

Everything agents flagged today that isn't already a row above or in
the complexity-audit register. Source = the unit reports (P21/P22/T1–
T6/SOUL/boot-fix/gym baseline).

| Issue | Evidence / location | Suggested owner |
|---|---|---|
| `seon.agent/create!` silently accepts-and-drops `:seon.agent/turns-cap` (open Malli map) — the cap only works as entity data; observed live (passed cap, loop ran the 20 default) | B3 report, 22d01e1 | close the request map or honor the key at create |
| MCP CLJS bridge `default`/`create_session` can pin a STALE `:client` runtime that never hot-reloads (evals return "undefined" for code the pod has); `agent_id` addressing resolves correctly | extensibility unit report | bridge re-resolution on staleness; recurrence = priority bump |
| `message!` docstring still carries the old salience-contamination phrase ("never the raw tx-report") — harmless since the fixture re-cut, but it's the reversion trap | hygiene unit report; message.cljs:145-146 | reword with the next message.cljs touch |
| s32 fixture/judge reference cites `src/seon/agent.cljs` for `message!` (physically in agent/message.cljs; re-export makes it defensible) | hygiene unit report | re-pin at next fixture touch |
| warn_test provenance coverage narrowed by the my.workout rename (the "agent-registered seon.*-domain attr not blanket-hidden" case is gone; substrate stays-hidden case remains) | hygiene unit report | restore with a synthetic seon.* fixture if the case matters |
| `my.workout` rename one-liners pending in fenced files: client.cljs:675-676, db.cljs:767, ctx.cljs:875 | hygiene unit report | ride the clamp/B4 agents' commits |
| v3-era residue in fenced files (B1+B2 report): `agent.cljs:85-97` ns docstring teaches the dead nine-section list; `complete!` docstring's bare free `id` (prefer a `"<id>"` placeholder); `client.cljs:891` "functions catalog" comment; `render.cljs:243` names dead `schema-catalog-section` | teachings unit, 23c8e68 | one-liner sweep with each file's next touch |
| EXISTING stores keep the OLD soul mechanics text + fn docstrings (seed-only-if-absent; `:seon.fn` rows dedupe on sym) — `:seon.ns/source` rows DO heal on boot. Remedy per store: one identity-upsert of `my.soul/mechanics-text`; fn-doc re-emit-on-change = candidate unit | teachings unit report; no-porting rule applies | fresh worlds clean; doc the upsert in release notes |
| editing teaching sources mid-suite-run trips `no-stub-source-anywhere` (runtime file read vs compile-time line meta) — transient, self-heals on re-run | teachings unit report | known race; note in test docs |
| `record-eval!` double-failure path (bad turn-id) SILENTLY loses both the eval row and tee rows (console.error only) — observed live with a non-id-shaped turn-id; dishonest-record class — **PARTIAL FIX 2026-06-12 (task #24 symptom 3)**: the recovered-without-tee path now stamps `:seon.eval/record-error` on the eval row (separate top-level tx) and `seon.warn/check-record-errors` derives the warning into every agent's context (scoped by latest user message). The TOTAL-loss path (even the bare eval row fails, e.g. bad turn-id) still has only console.error — nothing exists in the DB to annotate; a derived check would need a different fact source | B4 unit report / eval.cljs + warn.cljs | partial-loss done; total-loss surface still open |
| possible tee-persistence loss in a prior session: live compile-state held `my.kb.orchestro.*`/`my.tile.roster` nses with NO store rows | B4 unit report | focused look if it recurs on a fresh world |
| STANDING ORDER (user): every agent-reported smell lands in THIS register the turn its unit lands — chat is not a record | user 2026-06-11 | orchestrator discipline |
| **datahike pull THROWS on registered-but-never-transacted attrs** — **FIXED 2026-06-11**: ONE `seon.db/installed-schema` home (copies deleted in render/warn/ctx/live-tile) + a guard at `seon.db/pull`: registered-uninstalled attrs silently filtered (provably ≡ installed-with-zero-rows), unregistered attrs (typos) throw a legible error naming the attr + fix; `'[*]` and valid pulls byte-identical. Pinned by `test/seon/db/pull_guard_test.cljs` (4 tests / 23 assertions); live-proven on the cluster store incl. FilteredDB read-through. Remaining (flagged): direct `d/pull` explicit patterns in `seon.handlers.fn` bypass the guard | T5 hit it live (29 assertion fails, fixed by gating); installs are lazy at first transact | done |
| **datahike `get-else`: STANDALONE optional scans drop rows (IR/planner engine, BOTH platforms)** — root-caused 2026-06-11: `*force-legacy*` defaults true on CLJ / false on CLJS (query.cljc:56), so only CLJS runs the planner → "CLJS-only" was an exposure artifact (JVM + planner flag fails identically). Entity-bound shapes always worked — the `waking-hops` "dead branch" claim was WRONG (live-disproven; corrected). **FIX READY**: fork branch `fix/cljs-get-else` (ec902943) + CLJC regression suite, green on JVM-legacy/JVM-planner/Node. AWAITING USER GO: push fork branch + bump 4 deps.edn pins + live re-probe. Live exposure until then: agent-authored standalone get-else queries (the context teaches get-else) | fork agent report; probes in its transcript | push+bump on user go; upstream PR candidate (inherited planner bug) |
| `*force-legacy*` platform-asymmetric default = systemic trap: CLJS is the ONLY default planner consumer, so every planner bug presents as CLJS-only; nothing runs the fork's JVM CI with the planner on | fork agent report | decide: flip CLJS to legacy-default until the planner matures, or add planner-on CI to the fork |
| fork test `query_fns_test.cljc` has an unconditional java.util import — can never compile on CLJS, not in the Node runner → get-else had ZERO CLJS coverage | fork agent report | fix in the fork with the next fork touch |
| dev hook lints reference-code submodule files (false-positives on CLJS 1.12 native await + macro-scoped symbols) — blocked Edit/Write in the fork; agent had to use perl | fork agent report | exclude reference-code/ from the hook's lint scope |
| **`reply!` logging artifact** — **FIXED 2026-06-11 (1ab6e3e)**: stored rows were verified CORRECT; the defect was derivation-only (recent-messages ignored `to`, so the newest from=to=me transcript self-row became "last reply"). Direction classification added; self-narration excluded from chat surfaces. DECIDED(user): do NOT design for agents legitimately messaging themselves — the implicit from=to=me narration convention stands | T3 surfaced; live-verified on kXQ | done |
| outgoing my-agent→peer messages render as ordinary assistant bubbles — **FIXED 2026-06-11 (1ab6e3e)** with the same direction fix: outgoing peer = `→ agent-<id>` label. Remaining (minor, flagged): fan-out to-ref classification picks first match — revisit if fan-out becomes common | T2 report | done |
| boot re-index heals CHANGED schema sources but may not PRUNE rows for DELETED registrations (stale `:seon.render.chat/bubble` row possible) — **DONE 2026-06-11**: `seon.client/prune-substrate-ghosts!` boot-index GC retracts substrate-claimed (`:seon.db/origin :substrate-seed` on the source datom's tx) ns/fn/test/schema rows absent from the freshly-built boot index; agent rows protected by provenance + replay's `registration-call-source?` rule; runs BEFORE replay (a deleted ns falls out of `substrate-ns-set` and would otherwise replay as agent corpus — the my.kb.instruction dead-teachings path); live-proved: first restart pruned 11 real ghosts incl. `:seon.render.chat/bubble` + the `seon.agent/message!`→`seon.agent.message/message!` rename pair, second restart pruned 0 | T3 require-flip removed the register! | done — test `prune-substrate-ghosts-removes-only-substrate-claimed-absentees` (index_substrate_test) |
| `render-agent-tile` pulls `'[*]` on the agent entity — inlines the whole `:seon.agent/sessions` component tree per tile render | T5 report | render sweep: narrower pattern |
| `seon.agent` ns docstring says "nine sections" (now twelve); `live-tile-section` missing from its re-export list | T5 report | V4 composer rewrite (the section list changes anyway) |
| duplicated latest-inbound datalog: `replied-since-inbound?` (agent.cljs) ↔ `turns-since-inbound` (ctx.cljs) | P21 report (ctx was fenced) | P6 split: shared `latest-inbound-at` |
| hardcoded line-number citations in context exemplars drift on every edit above them | P22 report (already repointed once) | derived citations from analyzer `:seon.fn` line info (reactive-context) |
| `seon.test.runner/::selector` uses `[:fn]` for "exactly one of" — pure-data law violation; exclusivity not directly expressible as data — **FIXED 2026-06-11 (wave-2 unit)**: `::selector` is now the pure-data "at least one" :or-of-maps shape (same pattern as `:seon.render/ai-response`); the exactly-one rule moved into `run!`'s body throwing a legible `::ambiguous-selector` envelope naming both keys; pinned by `run!-rejects-ambiguous-selector-with-legible-envelope`; live-proved on the pod (ambiguous → envelope, valid call unchanged 1/1/0/0) | boot-fix report; non-crashing | done |
| JVM-side `[:fn]` registrations (`seon/server/registry.clj:59,98`, `seon/dev/*.clj`) — law violations if those forms ever round-trip | boot-fix report | JVM sweep, post-demo |
| minute-resolution todo ages (`todo.cljs:115`) + 1h rolling warnings cutoff (`warn.cljs:717`) bust the provider cache prefix at every minute/hour boundary | gym U3 report | V4 composer rewrite (both sections land in the volatile tail anyway — verify placement solves it) |
| `my.soul` references `:my.kb/*` provenance attrs from outside the my.kb family | SOUL report | decide: provenance shapes to a neutral ns, or bless cross-family reference |
| `cljs-finish-clj-pivot-plan-2026-06-09.md:~321` still describes SOUL as hardcoded in deepseek | SOUL report | stale-doc fix with the next PRD touch |
| s12 baseline: agents tell the user `transact!` THROWS; truth = errors-as-values | gym baseline (judge 40/40, both agents) | V4-1 system paragraphs + seon.db docstrings (in the v4 bar) |
| s32 baseline: re-grep economy (2 greps, cap 1) — consults stored finding then greps anyway | gym baseline | v4 teaching surfaces; re-measure at the post-refactor sweep |
| T6 overlay verified via curl markup only — backtick/Esc/focus-guard not exercised in a real browser | T3+T6 report | one manual check (or browser-automation pass) before demo |
| outside-builder blockers: konserve local fork (4 deps.edn sites), `bin/run:11` hardcoded JAVA_HOME, README lacks build instructions, `.mcp.json` absolute paths, datahike gitlink pinned to unreachable sha | release-readiness research (d1b8e90) — push plan §7 PARKED for user go | release unit when user green-lights the push |
| pull-guard bypasses: `handlers/fn.cljs:60` + `handlers/message.cljs:41` call `d/pull` directly (raw-throw on uninstalled attrs; the message one masks typos in a bare try); the `query`/`d/datoms` boundary has the same lazy-install trap (render's :aevt scan gates manually) | 65dfc90 unit report | route handlers through `seon.db/pull` + a sibling guard for `query` — fold into the render sweep / composer rewrite |
| **DIS transient false-empty datalog read**: the same provenance query returned `#{}` once then the row seconds later on the live DIS conn (possible lazy node-fetch timing in datahike-cljs query). DISHONEST READS class — boot-time consumers (GC, replay selection) would silently mis-decide; GC under-prunes safely, but the read itself is wrong | 098c0b3 unit report | focused investigation unit — reproduce, then fix in the fork or the wire layer |
| recurring `tx-feed pump failed (wire rpc timeout) — re-subscribing in 2s` in pod.log around heavy test runs; self-heals via re-subscribe; pre-existing | 098c0b3 unit report | watch; fold into wire hardening if frequency grows |

## Downstream consumer asks (downstream, 2026-06-11)

The first real downstream product (external) consumes seon as an
UNMODIFIED substrate. Its top asks, with routing + status. Asks 1–3
landed as ONE unit (this row block's source); 4–6 are handled
elsewhere as noted.

| # | Ask | Status |
|---|---|---|
| 1 | **Composer ns-prefix extensibility** — the `<namespace>` tag inclusion rule was hardwired to `seon.* + my.*`. Now customize-with-data: `:seon.ctx/included-prefixes` (cardinality-many string) on the config entity `[:seon.ctx/config-id "substrate"]`, seeded with the defaults seed-if-absent at first render (`seon.ctx/ensure-ctx-config!`). A downstream adds `"the downstream consumer."` by ONE identity-upsert transact; its `the downstream consumer.*` nses render for every agent next turn; retract removes them. The `*.internal` exclusion stays STRUCTURAL (all prefixes) | **DONE 2026-06-11** — live-proved on the cluster store (transact → tag, retract → gone); pinned by `included-prefix-extensibility` (ctx_test.cljs) |
| 2 | **`SEON_RUNTIME_ROOT` env override** — build/source ARTIFACT reads (self-host bootstrap `out/bootstrap`, boot-indexer source roots `src`/`test`/`guest-cljs/src`, static `resources/public/*`) resolved CWD-relative, forcing a downstream to copy/symlink them. Now routed through ONE helper, `seon.platform/artifact-path`: resolves against `SEON_RUNTIME_ROOT` when set, byte-identical CWD-relative when unset. DATA paths (store, sockets, tmp, logs) deliberately stay CWD/world-relative — the downstream's own state. (`SHADOW_IMPORT_PATH` in the compiled `out/client/main.js` needed no change — already `__dirname`-relative) | **DONE 2026-06-11** — live-proved: second pod launched from a scratch world dir against this repo's artifacts + its OWN scratch store booted, minted an agent, served `/agents` + css; pinned by `platform_test.cljs` |
| 3 | **`bin/seon` supervisor parametrization** — store path, UDS socket paths, and ports were baked in (fork-to-change). Now env overrides defaulting to today's values: `SEON_CLUSTER_DIR`, `SEON_REQ_SOCK`, `SEON_PUB_SOCK`, `SEON_WRITER_REPL_PORT`, plus pass-through `SEON_PORT` / `SEON_RUNTIME_ROOT` (read by the pod itself). A downstream shells out to `bin/seon` with env instead of re-implementing | **DONE 2026-06-11** — defaults expand byte-identical to the old command; live stack unaffected (`bin/seon status` green, idempotent starts no-op) |
| 4 | **Publicly-resolvable deps** (outside-builder blocker: datahike gitlink, konserve `:local/root`) | **DONE — repinned 4929dfc** (datahike → ec902943 fork main, konserve → public git dep; `clojure -P` green on all four aliases) |
| 5 | (handled by the parallel robustness unit — agent/ai/warn fence; see that unit's report for the ask text + status) | parallel unit |
| 6 | (handled by the parallel robustness unit — see its report) | parallel unit |
| 7 | **`bin/seon prep` verb** — git-dep prep needs the alias form (`clojure -X:deps prep :aliases '[:writer]'`); downstream supervisors rediscovered it | [[fix-everything-prd-2026-06-11]] Wave C item 5 |
| 8 | **`SEON_FS_LOCK` env knob** — agent self-narrowed its grant via `configure!`; locked = env grant immutable, `configure!` a legible no-op | [[fix-everything-prd-2026-06-11]] Wave C item 3 (fenced behind the robustness unit's fs.cljs) |
| 9 | **Tile hiccup-serialization errors 500 the page** — structure errors escape the fn-call guard | [[fix-everything-prd-2026-06-11]] Wave C item 1 (fenced behind the CSS unit's live_tile/inspector) |
| 10 | **(a) loud truncation on clipped eval results** (confabulation incident) / **(b) fs read paging** | (a) Wave A unit A4; (b) Wave C item 4 — both in [[fix-everything-prd-2026-06-11]] |
| 11 | **BOOT-FATAL schema-row id collision** — tempid dropped the keyword namespace; restart-resume died on agent-authored schemas | **DONE — 500486a** (entity-schema tempids carry the FULL keyword) |
| 12 | **Stale live-tile 500 during boot replay** — same class as 9 | [[fix-everything-prd-2026-06-11]] Wave C item 1 (same guard) |
| 13 | **Self-poisoning fake results** — model-completed bare result-envelope literals evaluate and become real transcript lines | [[fix-everything-prd-2026-06-11]] Wave A unit A2 (parser format contract) |
| 14 | **Agent fn replay fails on pod boot** — 19 `log-replay-failure!` WARNs (`indexOf` on undefined), 2/4 tile fns don't rehydrate after snapshot restore; repro cut PRE-B4 (17:42 vs B4 18:16) | **CLOSED — FIXED BY B4 (72f6aab), live-proved 2026-06-11 late**: multi-agent restart on the real store, replay 9/8-ok/1-deliberate-fail, zero `indexOf` in pod.log, fns callable + tiles rehydrate. Proof: [[research/c14-replay-verify-2026-06-11]] |
| 15 | **Identity-seed filename hardcoded** (`SOUL.md`) — downstream wants `SEON_SOUL_FILE` env + `AGENTS.md` fallback | Wave C addendum C-15 |
| 16 | **Substrate-generic REPL discipline lives in downstream identity files** — hiccup tile rules, clipped-results discipline, never-write-expected-results, kb provenance | Wave C addendum C-16 (AFTER the paid measure — it changes measured context) |
| 17 | **Branding not customizable** (BUG per user; demo-relevant Jun 12) — hardcoded "seon ·" titles/h1, `data-theme "phosphor"` | **DONE — 24671ca** (brand rows + `SEON_BRAND_NAME`/`SEON_BRAND_CSS`; live-proved Acme↔seon roundtrip) |
| 18 | **LLM settings fork-to-change** (user, relayed) — DeepSeek model/endpoint/temp/max-tokens are private defs, thinking is a REPL-only atom; downstream needs override (e.g. thinking ON) | **DONE 2026-06-11 night (one unit with 20)** — `seon.ai` ns: `:seon.ai/config` singleton row (provider/model/temperature/max-tokens/thinking/timeout-ms), `SEON_AI_*` env owns the row (C-17 sync contract), read PER CALL by both adapters; `!thinking`/`set-thinking!`/`!timeout-ms` atoms FOLDED into the row (no parallel mechanism); absent env+row → byte-identical deepseek wire body (pinned by full-map test + live read); live-proved transact→same-call pickup→sync-retract roundtrip on the running pod, no restart. **Migrated to the `openai` Node SDK 2026-06-16 (see SDK-migration row below): config row unchanged; `:seon.ai/base-url` now PREFERS the `/v1` root form (SDK appends `/chat/completions`); the full `/v1/chat/completions` form still auto-strips.** |
| 19 | **Model-authored result-comments indistinguishable from real results in the transcript** — fake `;; =>` narration survives the A2 parser (correctly doesn't eval) but later turns trust it as a real read (downstream F13/F14 fabrication incidents) | Wave C addendum C-19 — render-side rewrite to `;; [unverified narration]`; post-measure; downstream soul-rule mitigation in place |
| 20 | **Anthropic provider support** (user, direct) — latest Claude models, default `claude-opus-4-8`; key already in env | **DONE 2026-06-11 night (one unit with 18)** — `seon.ai.anthropic` (same agent-adapter contract as deepseek; adaptive-only thinking truthy→adaptive/falsy→OMIT, sampling params never sent, top-level `:system`, typed content-block parse, `stop_reason` checked incl. refusal→error envelope); provider selection in `client/current-llm-fn` off the config row (`SEON_AI_PROVIDER`), deepseek stays default; live-proved with two bounded real API calls on `claude-opus-4-8` (\"OK\" end_turn; adaptive-thinking call accepted, correct arithmetic) |
| a20 | **Thinking-mode 0-eval completions silently kill the wake** (downstream ask 20) — all tokens land in reasoning, visible content empty → 0 forms → turn closes `done`, wake ENDS; agent looks dead (2/4 asks, deepseek thinking). Blocks thinking-on configs; same class possible on any provider | **DONE 2026-06-12** — provider-agnostic empty-turn guard in `run-agentic-loop!`: not-replied + zero evals → substrate nudge + re-prompt (max 2 consecutive, streak resets on any forms-turn), then error-turn + chat-visible ⚠ system line (ask-6 shape); cap checked BEFORE the guard; deepseek logs reasoning-present/content-empty at debug; live-proved on the real kick path |
| — | `rearm-user-triggers!` silently swaps ANY injected per-agent llm-fn for `current-llm-fn` on every hot reload (`client.cljs:1528`) — stub-driven live verification is a paid-call hazard (bit this unit: one accidental real call); would also clobber a deliberate per-agent adapter | client.cljs | S: per-agent llm-fn override attr or rearm exemption |
| — | `:cap-hit` wake end is chat-SILENT for the human (self→self note dropped by recent-messages) while the new no-visible-output end is chat-visible — inconsistent; cap-hit could adopt the same error-turn shape | agent.cljs | S, fold into the next loop unit |
| a21 | **Markdown chat rendering** (downstream ask 21; user had already queued it) — `**bold**` renders literally | **DONE 2026-06-12** — SERVER-side `md->hiccup` for both bubble kinds (escaped by construction; script-injection curl-proved inert; links nofollow/noopener + scheme guard; javascript:/data: hrefs degrade to text); root cause of their screenshot = the old CLIENT-side marked.js pass no-oped without the CDN script. Writing-teaching bullet in system-text |
| a22 | **camelCase style keys silently dead in hiccup** (downstream ask 22) — `{:fontSize …}` passes through verbatim; LLMs carry React priors; every flat tile traced to this | **DONE 2026-06-12** — `style-key->css` normalization at the ONE render point (`ui/html.cljc` `render-style`; ask cited .cljs — file is .cljc); React semantics, `--*` untouched, leading-cap → leading dash, normalized-key sort = deterministic; live-proved. NEXT React-prior in the same class (flagged, not fixed): numeric values `{:fontSize 12}` render `font-size: 12` (no px auto-append) — silently dead the same way |
| — | `seon.web.brand-test` stack-traces a loud expected ERROR (`SEON_BRAND_CSS … unreadable`) into every suite run — passing test, noisy output | test/seon/web/brand_test.cljs ~83: capture/suppress the expected error log; S |
| a23 | **Default-purpose instruction leaks to the user-facing welcome tile** (downstream ask 23, demo-relevant) | **DONE 2026-06-12** — `:seon.agent/purpose` never defaulted (absent until claimed); derive-purpose guidance renders in `<your-entity>` ONLY while unset (self-healing); welcome tile placeholder ("I'm still finding my purpose — tell me what you need."); explicit-purpose param path unchanged; live-proved both directions |
| — | hot-reload silently skipped a compile batch (shadow blocks hot-load on a warning build; next clean build only pushes recompiled files) — "edited but live behavior unchanged" trap; joins the a24 reload-gap row | shadow/cljs-watch | investigation S (two units bitten today) |
| — | two styling sources for chat markdown (`md->hiccup` element classes vs inspector `.markdown` CSS) — inline wins on conflict, fine today | render/chat + inspector css | cosmetic; consolidate when next touching either |
| a24 | **Generic OpenAI-compatible provider** (downstream ask 24) — deepseek adapter already speaks the OpenAI wire format but endpoint is a hardcoded private def; downstream wants an enterprise LLM gateway (verified live, bearer-keyed) | **DONE 2026-06-12** — deepseek path IS the openai-compat path (one mechanism): `:seon.ai/base-url` (FULL chat-completions URL, posted as-is) + `:seon.ai/api-key-env` indirection + `SEON_AI_API_KEY` fallback (keys read from process.env at call time, never transacted); missing config → legible error envelope (also fixed a latent bug: missing-key was mislabeled retryable-transport); thinking sent only-if-truthy on compat; live-proved request shape. Their gateway smoke is theirs to run. **CLOSED 2026-06-16 by the SDK migration (row below): the hand-rolled openai-compat path was replaced by the official `openai` Node SDK. The ns was renamed `seon.ai.deepseek` → `seon.ai.openai-compat` (it serves BOTH `:deepseek` and `:openai-compat` — the same OpenAI wire format); provider ENUM VALUES `:deepseek`/`:openai-compat`/`:anthropic` UNCHANGED.** |
| — | a24 behavior delta worth relaying: `SEON_AI_API_KEY` now also enables `:deepseek` (DEEPSEEK_API_KEY still wins — existing deployments unchanged) | seon.ai.openai-compat (was seon.ai.deepseek) | note for the consumer relay |
| — | cljs-watch reload did NOT propagate a new public fn to the pod runtime (explicit `:reload` require needed during live proof) — reload-on-save gap class | pod hot reload | investigation S; bit one live proof |
| a24b | **Gateway streams SSE unconditionally** (downstream live-verified 2026-06-12 with a real key: completions + code-gen WORK, but `stream:false` is ignored — gateway bug, flagged to its owners) — adapter must tolerate either body shape | **DONE 2026-06-12** — content-type branch (charset-tolerant): SSE → `parse-sse-response` (delta concat, last usage chunk, [DONE] required, reasoning deltas dropped w/ debug log → feeds the a20 guard); JSON path byte-identical (re-pinned); malformed stream → legible envelope w/ raw body; live-proved with stubbed fetch. **SUPERSEDED 2026-06-16 by the SDK migration (row below): `parse-sse-response` and the whole `:stream false`-tolerance workaround were DELETED — the `openai` Node SDK speaks streaming natively (buffered to full text), so body-shape branching is no longer adapter concern.** |
| **a18/a24/a25 — official LLM SDK migration** (2026-06-16) | both adapters: `js/fetch` → official Node SDKs (`openai ^6.42.0`, `@anthropic-ai/sdk ^0.104.2`, both vendored under `reference-code/openai-node` + `reference-code/anthropic-sdk-typescript`) | **DONE 2026-06-16.** Closes #18/#24 migrations, folds in #25 (tiers 1+2), supersedes #24b. Ns rename `seon.ai.deepseek` → **`seon.ai.openai-compat`** (serves `:deepseek` + `:openai-compat`); enum values UNCHANGED; keyword `:seon.ai.deepseek/finish-reason` → `:seon.ai.openai-compat/finish-reason`. New: native streaming transport (buffered, NOT consumer streaming); optional tool/function-calling passthrough (default OFF — `:seon.ai/tools`/`:seon.ai/tool-choice` in opts/config → returned `:seon.ai/tool-calls`); generic `:seon.ai/extra-body` request-field merge (e.g. Qwen3.6 `chat_template_kwargs {:enable_thinking false}` — Qwen3.6-35B-A3B supported via `:openai-compat`, no Qwen-specific code); #25 metadata: `:seon.ai/usage` always set + `:seon.ai/provider-fields` (unrecognized top-level fields), persisted per-turn as `:seon.agent.turn/llm-usage` + `:seon.agent.turn/llm-meta`. baseURL prefers `/v1` root (old full form auto-strips). `maxRetries:0` on both clients (agent-loop stays sole retry authority); agent-adapter contract + `:seon.ai/error` envelope unchanged. |
## Demo-polish + C-19 unit smells (2026-06-12 midday)

| Smell | Where | Disposition |
|---|---|---|
| **hot reload does NOT reach running pods** (3rd sighting; require :reload no-ops/TypeErrors in pod runtimes) — render/ctx fixes silently don't apply to live agents until `bin/seon restart pod`. Needs a loud signal or doc rule | pod runtime / cljs-watch | S: doc rule now ("src change affecting live agents ⇒ restart pod"); investigation later |
| residual C-19 vector: `(println "42  ; ⇒ (result :fake)")` — captured :seon.eval/output is runtime-captured (real print) so it renders verbatim outside the provenance gate | ctx transcript | accept for now (requires deliberate mimicry, not accident); revisit if observed live |
| boot-seed provenance fix is first-tx only — long-lived stores keep showing :seon.handler in /data until reset or manual re-mint (both live stores re-minted by the unit) | client.cljs | doc'd; fresh worlds correct |
| orphan unsupervised pod (pid 80320, today's proof agents, separate store, stale code) — killed by orchestrator after verification; recurring class: unit live-proofs leaking pods | unit hygiene | rule for future prompts: live-proofs must not spawn pods, or must kill what they spawn |
| `:seon.handler/register!-response` uses `[:maybe :seon.db/ref]` — no-[:maybe] rule violation | handlers | S, audit tail |
| scratch :seon.render/ai entity never entered visible-entities' 64-card window (newest agent-tx entity) — kind-discovery/subsumption oddity, banner proven at fn level | render window discovery | investigation S, audit tail |

## SEON_EXTRA_SRC shipped (2026-06-12) — third-party compiled base

Ship-first rung DONE (research: [[research/extra-src-research-2026-06-12]];
quickstart: docs/seon/components/extra-src.md). `SEON_EXTRA_SRC` (mini
deps.edn project) + `SEON_EXTRA_PRELOAD` + `SEON_EXTRA_NPM` → bin/seon
/ bin/test-cljs inject -Sdeps + --config-merge (byte-identical when
unset, pinned via the new `bin/seon print-cmd` verb); preload registers
into `!extra-substrate-vars`; indexer accepts the root, extra nses
render FULL-SOURCE; reserved-prefix (`seon.*`/`my.*`) loud refusal;
gym strips the env (measures stock substrate). Deferred (doc'd):
full-source-roots store-row generalization (M), `.cljc` boot-read
probe, per-product out/ redirect.

| Smell | Where | Disposition |
|---|---|---|
| downstream entry requiring a seon ns outside seon.client's closure surfaces its vars as "extra" → trips the prefix guard — quickstart shows the own-prefix filterv form as the rule | extra-src registration | doc'd; revisit if a real downstream hits it |

## FACTS-chips unit smells (2026-06-12)

| Smell | Where | Disposition |
|---|---|---|
| handler registration rows count as FACTS — `seon.handlers.wake` registers at conn-open BEFORE boot-seed! stamps seed-origin → fresh world shows FACTS=1 not 0 | client.cljs boot path | S: stamp conn-open machinery txes as seed-origin (provenance fix, not a chip fix — chip honestly mirrors /data) |
| lazy schema-install tee rows (+2 :seon.schema/:seon.ns per new agent kind) count as facts — arguably correct (agent-authored = user data); awareness row | per-row provenance rule | accept; revisit if demo viewers ask |
| `no-stub-source-anywhere` failed mid-parallel-work (whitespace-leading :seon.fn source from seon.ctx) — matches the REGISTERED index-substrate disk-read race signature, two agents mid-edit in ctx/eval | shared-tree race (known row) | verify on the settled tree with the next unit's suite; route to tee-family agent if it persists |

## Mid-task gate unit smells (2026-06-12)

| Smell | Where | Disposition |
|---|---|---|
| **CONSUMER SECRET IN THE LIVE STORE**: `:my.config/secret` (hex token) + cloud-run URL render verbatim into every agent's `<findings>` section → transited LLM providers on every live wake. USER MUST RELAY: rotate the token | live cluster store / findings rung | DEFERRED (user, 2026-06-12: "how do we know what a secret is?" — the name-pattern heuristic is dubious; demo focus first). Post-demo shape: the STRUCTURAL piece only — `:seon.ctx/excluded-kinds` opt-out row (consumer marks kinds, no guessing). RELAY STANDS: rotate the leaked token |
| ctx read-API public fns unspecced (`turns-since-inbound`, `messages`, siblings) — instrumentation-rule drift, second report (OPUS-S flagged the same family) | ctx.cljs | S sweep unit, queue |
| after a `:cap-hit` halt without reply, `task-in-progress?` stays TRUE until next inbound — harmless today (renders only happen in wakes); matters if a future renderer assembles context for parked agents | ctx.cljs | note; revisit with the lifecycle UX unit |

## Sweep findings (2026-06-12, $4.60 — full section in [[research/opus-live-tests-2026-06-12]])

Cache VERIFIED live (reads every call 2+, cross-process) but covers ~14% of input (system block only). s32 pass. s12: A stores + terminates under cap now; consult-first red on a THIRD model. Todo adherence 1/3 on haiku = opus's 1/3 (model-independent). Sonnet = measurement default.

| Smell | Where | Disposition |
|---|---|---|
| **question-gated sections are FIRST-TURN-ONLY** — `<turns>` + `<findings-pointer>` gate on the unanswered-inbox window, which the agent's first outbound CLOSES; both rendered in 1/19 and 1/14 blobs. The countdown can't pressure late turns; the pointer dies mid-research. The two new affordances were built right, displayed wrong | ctx inbox-window gate | REFACTOR FIRST: gate on "task in progress" (inbound → replied-or-idle), not unanswered-inbox |
| ~~cache covers ~14% of input — ctx rides in messages uncached every call~~ — **CLOSED 2026-06-12 (task #34)**: `seon.ctx/assemble-context` now splits at an in-band `stable-boundary` line (sections through `:namespaces` = stable prefix; your-entity→prompt = volatile tail; returns `:seon.render/stable-text`/`volatile-text` alongside the unchanged full text, `split-context` recovers the halves from the joined string); anthropic adapter sends `:system` as [soul block, stable-ctx block] BOTH with `cache_control` (2 of 4 breakpoints) and ONLY the volatile tail as the user message; deepseek wire unchanged (stable already first in its single message — gateway auto-cache gets the same prefix stability). Determinism pinned (`seon.ctx-test/stable-volatile-split-determinism`: same db value → byte-identical stable block; new turn row → stable untouched). LIVE-PROVED (2-call sonnet, $0.07): call 1 `cache_creation 17736`, call 2 `cache_read 17736` / `input 28` = 99.8% of input cached vs the old 5.4k-of-38k | anthropic adapter / ctx assembler | done |
| `:seon.ai/model` is provider-UNQUALIFIED — one shared row field reached the openai-compat (deepseek) JUDGE → HTTP 400 (judge pinned in-fence as the hotfix) | seon.ai config shape | S: per-provider model field or provider-qualified resolution |
| err-recovery scenario passed trivially (sonnet registers first — the envelope arc never fired) | gym scenario | re-cut with a value-violation shape next measurement round |

| a25 | **Preserve + persist provider metadata** (downstream ask 25) — adapter drops everything but text+usage; gateway sends governance scores/cost ledger as top-level extras; usage itself never reaches the store | **DONE 2026-06-16 (folded into the SDK migration, tiers 1 AND 2):** tier 1 open-world `:seon.ai/provider-fields :map` (ALL unrecognized top-level envelope fields, no provider key-lists) + `:seon.ai/usage` always set; tier 2 persist per-turn `:seon.agent.turn/llm-usage` + `:seon.agent.turn/llm-meta` (EDN) → queryable datoms, tiles, audits, spend-per-turn telemetry as data |
| a26 | **Cluster-header stats count the whole store** (downstream ask 26) — fresh world shows ~200 fns/8K datoms of seon internals; FINDINGS chip reads as dead weight at 0 | queued (post-sweep, S, demo-visible): header chips use the SAME post-bootstrap derivation as /data (shared `bootstrap-row-ids`), same `?system=1` toggle; zero-count chips hidden |
| — | **VERIFIED by orchestrator (2026-06-12, live)**: REPL-scope `register!` tees NO `:seon.schema/key` row (`registered? true`, store rows `#{}`). Mechanism: the tee lives in eval.cljs `build-tee-entities` via `record-eval!` — agent evals only; `register!` itself only swaps the in-memory atom. CONSEQUENCE: REPL-registered attrs VANISH from the registry on restart (datoms readable, new transacts rejected as unregistered) — silently write-dead — **FIXED 2026-06-12 (task #24)**: `register!` self-tees via a late-bound hook (`seon.schema/set-tee-fn!`, installed by `seon.eval/tee-registered-schema!` at load — no schema→db require cycle). Conn-gated (boot ns-loads untouched), replay-gated (`:seon.db/replay?` — no tx re-anchoring), substrate-claim-gated (a `:substrate-seed`-origin row is never overwritten — same provenance rule as prune-substrate-ghosts!), identical-source-gated (no no-op upsert churn). Source = the replayable `(seon.schema/register! …)` call form → replay-on-boot rebuilds the registry from the store. Live-proved: scratch attr row appears at register; the orchestrator's row-less `:my.teeverify/x` re-registered through the fixed path → row appeared; both retracted after. Tests: record_eval_tee_test (7 new) | schema.cljc / eval.cljs | done |
| — | ~~`parse-response` emits `finish_reason`/`usage` keys with NIL values when absent~~ — **MOOT 2026-06-16: the SSE parse path was deleted in the SDK migration; the SDK returns typed objects, and `:seon.ai/usage` is always set** | ai/openai-compat.cljs | resolved by SDK migration |

**Future PRD row (explicitly OUT of scope for the asks-1–3 unit):** a
**release-bundle target** — a self-contained artifact (compiled pod +
bootstrap output + static assets + bin/seon) a downstream can vendor
without a seon source checkout. Today's answer is `SEON_RUNTIME_ROOT`
pointed at a checkout; the bundle is the next rung.

## C-17 unit smells (2026-06-11 late evening)

| Smell | Where | Disposition |
|---|---|---|
| first render can race the boot brand-sync (~300ms window observed live: defaults render before the env tx lands; self-heals next request). `sync!` is fire-and-forget from `install!`; hard guarantee needs `install!` awaited in `start-agent!` | `web/brand.cljs` / `client.cljs` | accept self-heal for now; fold the await into the next client.cljs boot unit |
| CONFIRMED AGAIN: MCP default `:client` runtime ≠ the pod (cost the unit one misleading "empty store" read; pid mismatch live-verified) | stale-MCP-runtime row | evidence appended to the existing open row |
| `agents-dash-fragment` renders agent-authored tile content containing a bare `<h1>` — second h1 on the page (pre-existing, structural-HTML) | inspector dash | small unit: demote/strip headings in embedded tile content |

## Docs unit smells (2026-06-12 ~03:40Z)

| Smell | Where | Disposition |
|---|---|---|
| `"ML Options Trading - Log Viewer"` hardcoded title — a consumer-domain string in substrate src (hard-rule violation), in the PAUSED JVM web lane so zero demo risk | `src/seon/web/html.clj:304` | one-liner, fold into the next JVM-lane src unit post-demo |
| TWO unrelated `seon.ctx` systems share one ns name (JVM atom/watch state vs CLJS prompt composer) — standing confusion source, now both documented in the context note | src/seon/ctx.clj vs ctx.cljs | post-demo decide: rename one lane or document as permanent |
| frontmatter taxonomy drift: component notes use `status: production`/`stable` while the documented enum is active/draft/completed/abandoned; the markdown linter tolerates both — uniformity canary | docs linter vs CLAUDE.md taxonomy | S: reconcile the enum (linter or docs) |

## Demo-readiness sweep (2026-06-12 ~03:15Z) — PASS

Pod restarted clean at 43c5145: replay 2/2 zero failures, zero
`indexOf`; provider boot line `using deepseek LLM` (config row
pristine `{}`, provider `:deepseek` — C-18 boot path exercised); GC
correctly pruned the 3 folded `deepseek/set-thinking!`-era ghost fn
rows (code-as-data self-heal observed); `/agents` 200; unbranded
defaults (`seon · agents` / `seon · cluster`); store hygiene clean
(only the resident `nme-2606111920` non-completed). Suite at HEAD:
457/2037/0.

| Smell | Where | Disposition |
|---|---|---|
| forged-origin warn fires ×4 on LEGITIMATE boot replay (agent-scoped replay txes claim `:substrate-seed`) — warn-only noise, but it means the deprioritized forged-origin row's eventual enforcement MUST distinguish replay-scope from live agent evals, or boot breaks | db/internal warn-on-seed-origin-forge! / replay scope | fold into the forged-origin backlog row as a hard constraint |

## Opus live-test unit — harness limitations + smells (2026-06-12Z, [[research/opus-live-tests-2026-06-12]])

Spend: ≈$17.61 (unit budget $15 — honest $2.61 overrun, under the $20
evening cap; s12 alone $8.44, all uncached). Headlines: **#26
salience GREEN first paid confirmation** (judge 100/100, 1 turn);
opus stored 3 provenance findings (deepseek 0/4); todo-teaching
adherence 1/3 completed runs = real behavioral red; s12-B's
first-eval repo grep proves re-derivation is NOT a weak-model
artifact (the related-question salience gap IS the demo bar).

| Smell | Where | Disposition |
|---|---|---|
| agent's in-eval `cljs.test/run-tests` shares global completion machinery with the host suite → `process.exit(0)` kills the gym referee mid-run (root-caused, stack trace in doc). In-fence interposer shipped; REAL fix = isolate the agent's test-runner env | src test-runner env | M unit, post-demo (interposer holds) |
| `seon.ai-test` DELETES operator `SEON_AI_*` env mid-suite (first "opus" run silently drove deepseek; caught by new telemetry). paid_test snapshot/restore shipped; proper fix in ai_test | test/seon/ai_test.cljs | S tonight |
| s32 consult predicate punishes post-#26 OPTIMAL behavior (answer now renders → consulting redundant) | s32 scenario edn | S re-cut tonight: consult OR (salient-claim-rendered AND zero searches) |
| Anthropic adapter sends NO `cache_control` — `cache_read 0` on all 49 calls; ~$8 of s12's $8.44 was uncached re-billing. Dominant opus cost driver | src/seon/ai/anthropic.cljs | S tonight: cache_control on the stable prefix |
| ~~turn budget invisible to the agent~~ — CLOSED 2026-06-12: `seon.agent.turns` `<turns>` countdown section (OPUS-S, fix-everything §3b) | ctx | done |
| `record-eval!` drops the program-graph tee row on entity-schema `register!` (`:seon.ns/name got nil`, live stack) — replay implications for agent-authored entity schemas — **FIXED 2026-06-12 (task #24)**: root cause = entity-kind keys (`:my.garden.watering`) have a NIL keyword namespace, and `build-tee-entities` unconditionally wrote `{:seon.ns/name (keyword nil)}` → literal nil → Malli fail → whole tee tx sunk. New shared `schema-tee-row` builder omits `:seon.schema/ns` for nil-ns keys (mirrors `index-schemas`); live-proved through `eval-batch!` on the cluster store (entity tee row present, no record-error). Replay EXECUTION of nil-ns schema rows still broken in client.cljs `target-ns-for-entry` (`(symbol nil)`) — smell reported, out of #24's fence | eval.cljs tee path | done (client.cljs target-ns follow-up open) |
| salience is verbatim-shaped: paraphrase answered instantly, RELATED question re-derived | findings rung scope | post-demo: the next salience rung (related-question retrieval) |
| ~~`:deepseek` tier keyword~~ — CLOSED 2026-06-12: renamed `:paid` across EDNs + driver (OPUS-S) | scenario files | done |
| ~~38MB trace logs drown run evidence~~ — CLOSED 2026-06-12: gym/test processes install the pod's quiet-library-logs! gate; suite log 56KB (OPUS-S) | gym logging | done |

## Todo teachability unit smells (2026-06-12Z)

| Smell | Where | Disposition |
|---|---|---|
| B1 extractor blind spot (structural): `(-> …)`/`(.then …)`-headed taught examples are silently NOT extracted (head must be letter-initial + qualified/definer) — an author can write uncovered "executable" examples | teachings_test extractor | S: canary test or convention note in the harness |
| clj-kondo hook OOM (Java heap) AGAIN (todo_test.cljs edit; third occurrence tonight incl. 2× client.cljs) — retry passes, lint silently skipped each time | dev hook | bump: recurring; same row as the C-18 smell — needs the heap look + loud-skip |

## Salience unit (#26) smells (2026-06-12Z)

| Smell | Where | Disposition |
|---|---|---|
| twin provenance query — `findings.cljs/substrate-kinds` duplicates store-inventory's derivation | findings.cljs / db.cljs | **CLOSED with the data-browser unit (2026-06-12)** — `seon.db/bootstrap-row-ids` + `seon.db/substrate-kinds` are THE derivation; findings consumes them |
| inspector SSE registry maps use bare keywords (`:id :res :view :opened-at :params`) — namespaced-keys rule violation across the registry | inspector.cljs | S rename unit, all five keys at once |
| pre-existing agents' stored creation-turn eval results carry the OLD full inventory (`:seon.eval/result-edn` is history, not re-derived) — honest, but a demo on an old agent shows the old blob in its transcript | eval history | accept (history is history); demo agents should be freshly minted |
| `/data` SSE push full-datom-scans per 100ms coalesced window (same cost class as `/agents` findings-data) — fine at current sizes | inspector.cljs | memoize on db-value identity if it shows in profiles |
| **inspector findings pane is legacy bare-ns `:finding/*`** — dashboard tile reads "0 findings" while agents now SEE findings in context. DEMO-VISIBLE inconsistency | `web/inspector.cljs:117-137` | S, TONIGHT if time: point the pane at the same user-domain derivation |
| `cap-result`-family helpers in ctx.cljs are near-triplets of the new `cap-kind` loud-truncation marker — wording drift risk (already bit a test draft) | ctx.cljs | S: one shared loud-truncate helper |
| datahike-cljs rejects `clojure.string/starts-with?` as a datalog predicate (JVM datahike resolves it) — parity gap, confirmed live | datahike-cljs fork | register; fork/wire backlog with the DIS false-empty row |
| context component note not updated with the `:findings` section | docs | folded into task #17 docs batch |

## C-18+C-20 unit smells (2026-06-12Z)

| Smell | Where | Disposition |
|---|---|---|
| `index-substrate!` reads source files from DISK at test runtime — a concurrent agent editing `my/*.cljs` between compile and run misaligns `:seon.fn/source` (caused 3 spurious suite failures, clean on re-run). Shared-tree race, structural | boot indexer / test harness | register; candidate fix: index from the compiled/analyzer view, not a second disk read (code-as-data rule — one mechanism) |
| dev-hook convention checker reports "missing :malli/schema" for fns that DON'T EXIST in the written file (analyzed a stale/wrong target on NEW `.cljs` files, twice) | dev hook | investigation S — false feedback trains agents to ignore the hook |
| clj-kondo OOM'd (Java heap) twice linting `client.cljs`; edits succeeded on retry but lint SILENTLY SKIPPED — fail-loud violation | dev hook lint stage | S: surface the skip loudly; look at heap ceiling |
| boot-path `(ai/sync!)` wired in `start-agent!` but not yet exercised by a real boot (restart was fenced) — fn live-proved standalone | client.cljs boot | exercise + verify on the demo-readiness sweep's pod restart tonight |
| `my/soul.cljs:81` docstring cites nonexistent `seon.ai.deepseek/default-system-prompt` (pre-existing staleness; file owned by the in-flight salience agent) | my/soul.cljs | fold into whichever unit lands it; re-check after salience unit |

## Post-Wave-B measure — verdicts + smells (2026-06-12Z collection)

Full analysis: [[research/e2e-demo-findings-2026-06-08]] §POST-WAVE-B
COLLECTION COMPLETE. **s32 bar MET** (consult-first 5/5 widened
anchor; salience 0/5 is the fixture's own documented expected-red pin,
not a regression). **s12 bar NOT met** — predicate verified CORRECT
(§2 shape); misses are real, three mechanisms (worked example omits
`source-line` so agents copy the shown shape; `:my.kb/source-line
:int` can't express ranges → agents fork plural attrs; identity-upsert
makes ≥2 rows structurally hard from one question). Judge reds are
NEW classes, not the extinct transact!-throws one:
premature-idle/no-reply, internal-throw surface-misattribution
(partial-read path), fabricated provenance, omission. Run 4 proved the
accumulation thesis end-to-end once (B consulted stored kb rows first,
judges 100/100).

| Smell | Where | Disposition |
|---|---|---|
| duplicate gym card artifacts — same card echoed twice in one file (s32-4/5), same run under two filenames (s12-1≡B1, s12-3≡A3) → miscounts (bit the orchestrator's own preliminary read) | gym driver card-writing | S: one canonical `card-<run-id>.edn` per run |
| `:my.kb/source-line` `:int` contradicts the gym's own ground truth (line RANGES) — directly caused the run-4 `source-lines :string` fork | kb schema + the rendered worked example | fix unit: decide the canonical shape, align example + predicate; the worked example must SHOW source-line |
| s32 worlds nag paid agents about fixture-seeded kinds (`unmarked-entity-kinds` + "Please correct before moving on") — self-defeating-surface-adjacent; burned agent transacts | warn/world seeding | S: seeded fixture kinds carry whatever marking the check wants, or the check excludes seed-origin rows (structural, not name-list) |
| `seon.agent.search/grep` with `:paths` returns WRONG LINE NUMBERS — agent cited them, judge-failed | seon.agent.search | unit-worthy S/M: reproduce + fix; feeds the fabricated-provenance class |
| C-19 priority +1: live instance of a bare result-envelope literal rendering indistinguishably from a real send result (after the B3 refusal gate correctly blocked the reply) | transcript render | C-19 (task #7) bumped above C-16 |

## C-14 verify-unit smells (2026-06-11 late evening)

| Smell | Where | Disposition |
|---|---|---|
| **`[open-todos] render failed: :malli.core/invalid-input` in EVERY agent's live assembled context** — a substrate section crash-looping per render. DEMO-AFFECTING correctness bug | `agent/todo.cljs:226-227` (NOT ctx.cljs — it only registers the symbol) | **FIXED** — only section fn missing the `(or db @db/*conn*)` default; +regression test. IMPORTANT reframe: only the db-less OBSERVATION call shape failed; the LLM prompt path (`render-prompt` passes `@db/*conn*`) was always clean |
| `assemble-context` instrumentation did NOT reject a request missing REQUIRED `:seon.db/db` (`:seon.render/assemble-request`, ctx.cljs:1612) — request schemas aren't actually guarding this surface (instrumentation inactive on it, or check not firing). Alternatively the schema should say `{:optional true}` since all section fns now conn-default | `ctx.cljs:1612` | investigation unit — DISHONEST GUARDS class |
| `:seon.db/db` registered as `:any` in `seon.render` (render.cljs:50) while `seon.db` owns the keyword ns and registers `::db-val` as `map?` — out-of-owner registration, looser than owner's shape | `render.cljs:50` | small unit: move/align with the owner's shape |
| SECOND live `:client` runtime (successor to the 65066 trap): the downstream consumer's pod (their repo cwd, our compiled out/client/main.js) attaches to our shadow watcher; MCP default-session pinning ambiguity on demo day. NOT a stray — do not kill; it's their product running | process table | operational: `agent_id` addressing always; consider scoping the shadow watcher to own-repo runtimes post-demo |
| audit finding 5 CONFIRMED RED with forced-failure probe: replay failures surface ONLY on disk; broken ns renders healthy in `<namespace>` inventory; PLUS attribution bug — `log-replay-failure!` (`client.cljs:752`) stamps the PRIMARY agent, not the row's owner (live-observed). S-sized fix spec in [[research/c14-replay-verify-2026-06-11]] | `client.cljs:752`, `seon.log` (no DB rows by design) | post-demo (B4 fixed the actual failures; downstream workaround exists) |
| stale UNSUPERVISED pod (pid 65066, no ports, in-memory world) is the second `:client` runtime — MCP session `default` silently pins to it; ate first repro rounds of two units today. Two orphan in-memory agents (EiA/fKP-2606111928) unreachable for complete! | process table | SELF-RESOLVED — pid gone on orchestrator verify (no kill needed); OPERATIONAL RULE stands — address the pod via `agent_id`, never session `default` |
| docs lane: `web-layer.md:124` still lists the stale "ML Options Trading" log-viewer title as a known issue; the whole CLJS web lane (inspector.cljs / serve.cljs) has no component note (only agent-content-css + the new web-brand) | docs/seon/components/ | small docs unit: inspector component note + stale-row cleanup |

## Self-defeating-surfaces audit (2026-06-11 late evening)

Full ranked report (14 findings + checked-clean list):
[[research/self-defeating-surfaces-2026-06-11]]. Read-only audit;
nothing fixed yet. Top rows registered here so they queue into Wave
C+; the doc carries the other eight plus evidence.

| Smell | Where | Disposition |
|---|---|---|
| ~~`create!` returns success on failed transact~~ — CLOSED: envelope returned (43c5145) AND the boot path now branches on it (task #21, OPUS-S 2026-06-12: `boot!` propagates, `boot-one-agent!` skips MCP hosting, `start-agent!` refuses a ghost roster) | `agent.cljs` | done |
| generic entity-render paths `(catch :default _ nil)` — broken agent-authored renderer's card silently vanishes; makes inspector's own render-error fallback (`inspector.cljs:221-226`) dead code. AGENT BLINDNESS (the tile fix didn't cover these surfaces) | `render.cljs:451-457, 556-564` | S–M: surface the error like the tile banner |
| one throwing warn-check kills the WHOLE warnings section (no per-check guard in `run-checks`) — all warnings degrade to one `render failed` line | `warn.cljs` | S: per-check try → synthetic cluster |
| auto-instrument / auto-test-run failures are console-only — `:seon.test` rows keep stale ✓ stamps; context renders green against code the tests never ran on; specced fn can be silently uninstrumented. DISHONEST RECORDS | `eval.cljs:1493-1497, 1519-1524` | M |
| boot-replay failures live only in the disk log while rendered `<namespace>` sections claim the fn is live (fails at call time). B4 fixed the biggest cause, not the class | `client.cljs:752-823`, `log.cljs` | M: derive liveness at render (B4 probes exist) — overlaps ask #14 / C-14 |
| agent-forged `:seon.db/origin :substrate-seed` is warn-only (enforcement TODO) and now LOAD-BEARING for boot GC via `prune-substrate-ghosts!` — a forged row is GC bait. GUESSED AUTHORITY; mechanism high-confidence, end-to-end UNVERIFIED (labeled) | `db/internal.cljs:905-919` | DEPRIORITIZED (user, 2026-06-11 late: not security-focused — correctness + demo bugs first). Post-demo backlog: verify-then-enforce at the transact boundary |
| findings 6–8, 10–14 (remaining silent-truncation stragglers, context-defeats-consultation candidates, et al.) | see the research doc | rank into Wave C+ at next planning pass |

Audit also CONFIRMED live (fresh 18:13 prompt blob): Wave A/B fixes
rendering (loud clips, stub self-description, attr-keyed inventory);
`:seon.handler/key` standing warning still fires (Wave C item 6
evidence); 33 registered schemas exceed the quiet 200-char
member-block clip (e.g. `:seon.agent.turn` at 722 chars).

## What P8 proved (so the plan stays honest)

Consult-first 5/5 under stricter predicates; provenance storage +
cross-agent correction real; reply discipline transformed; zero
src-behavior regressions from 19 commits of refactor (every apparent
one was harness staleness). The costs are economy and legibility, not
capability.
