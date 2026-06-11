---
type: prd
status: active
tags: [prd, agent, database]
---

# Open issues — accumulated register + plan (2026-06-11)

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
| **SOUL/system-prompt hardcoded** (user, 2026-06-11) | the SOUL-derived identity text is a compiled-in def at `seon.ai.deepseek/default-system-prompt` (deepseek.cljs:107) — uneditable without a rebuild, and identity content sits in a PROVIDER ns (placement smell: it isn't deepseek-specific). User directive: ANY user must be able to change this and control ALL content injected into context. Fix shape: load from an editable source — store entity seeded at boot from SOUL.md, same seeded+editable pattern as `my.kb.instruction` (one mechanism); the deepseek def becomes a read of it; SOUL.md the seed, the store the truth. **IMPLEMENTED 2026-06-11**: `my.soul` ns (NOT `my.kb.soul` — `my.kb` is an exemplar root, a child would double-inject the whole prompt into ctx as full-source exemplar, measured +18k chars, blew the 84k turn-0 budget). Two rows seeded at boot: `identity` (SOUL.md read at seed time) + `repl-mechanics`; seed-ONLY-if-absent so a user's transact edit survives reboot; `seon.ai.deepseek/effective-system-prompt` reads the store per call with a one-liner fallback. Tests: `my.soul-test` (seed, no-clobber, request-body reads store). PENDING: pod-level live proof — pod boot blocked by the live-tile `sci-not-available` instrumentation error (tiles unit) | unit landed, awaiting pod-boot fix for live proof |

## Startup/restart reliability (user, 2026-06-11 — investigate)

Observed during the 06-11 morning restart; one investigation unit:

- `bin/seon restart all` excludes the JVM ("jvm not included") — a full
  system restart is two commands; either include it or say why not.
- The seon JVM MCP bridge caches a failed pre-flight across a JVM
  restart and keeps reporting "server not running" after nREPL 7888 is
  verifiably up (direct bencode clone succeeds) — requires a manual
  `/mcp` reconnect. The CLJS bridge self-heals (b8b3400); the JVM
  bridge should too (same fix shape: re-resolve on failure instead of
  caching the verdict).
- Post-start health check warns `:runtime-persisted {:instance-count
  0}` degradation 30s after boot — likely benign before agents resume,
  but verify and either fix the check's timing or document it.

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
| **datahike pull THROWS on registered-but-never-transacted attrs** — **FIXED 2026-06-11**: ONE `seon.db/installed-schema` home (copies deleted in render/warn/ctx/live-tile) + a guard at `seon.db/pull`: registered-uninstalled attrs silently filtered (provably ≡ installed-with-zero-rows), unregistered attrs (typos) throw a legible error naming the attr + fix; `'[*]` and valid pulls byte-identical. Pinned by `test/seon/db/pull_guard_test.cljs` (4 tests / 23 assertions); live-proven on the cluster store incl. FilteredDB read-through. Remaining (flagged): direct `d/pull` explicit patterns in `seon.handlers.fn` bypass the guard | T5 hit it live (29 assertion fails, fixed by gating); installs are lazy at first transact | done |
| **datahike-cljs `get-else` defaults never fire** — rows lacking the attr are DROPPED, not defaulted | T2 found it (recent-messages silently dropped ALL agent-from messages); same dead branch in `seon.agent.message/waking-hops` (~129) | focused audit unit; candidate upstream fork fix (datahike fork is ours) |
| **`reply!` logging artifact** — **FIXED 2026-06-11 (1ab6e3e)**: stored rows were verified CORRECT; the defect was derivation-only (recent-messages ignored `to`, so the newest from=to=me transcript self-row became "last reply"). Direction classification added; self-narration excluded from chat surfaces. DECIDED(user): do NOT design for agents legitimately messaging themselves — the implicit from=to=me narration convention stands | T3 surfaced; live-verified on kXQ | done |
| outgoing my-agent→peer messages render as ordinary assistant bubbles — **FIXED 2026-06-11 (1ab6e3e)** with the same direction fix: outgoing peer = `→ agent-<id>` label. Remaining (minor, flagged): fan-out to-ref classification picks first match — revisit if fan-out becomes common | T2 report | done |
| boot re-index heals CHANGED schema sources but may not PRUNE rows for DELETED registrations (stale `:seon.render.chat/bubble` row possible) | T3 require-flip removed the register! | small unit: deletion-pruning or a loud stale-row warn |
| `render-agent-tile` pulls `'[*]` on the agent entity — inlines the whole `:seon.agent/sessions` component tree per tile render | T5 report | render sweep: narrower pattern |
| `seon.agent` ns docstring says "nine sections" (now twelve); `live-tile-section` missing from its re-export list | T5 report | V4 composer rewrite (the section list changes anyway) |
| duplicated latest-inbound datalog: `replied-since-inbound?` (agent.cljs) ↔ `turns-since-inbound` (ctx.cljs) | P21 report (ctx was fenced) | P6 split: shared `latest-inbound-at` |
| hardcoded line-number citations in context exemplars drift on every edit above them | P22 report (already repointed once) | derived citations from analyzer `:seon.fn` line info (reactive-context) |
| `seon.test.runner/::selector` uses `[:fn]` for "exactly one of" — pure-data law violation; exclusivity not directly expressible as data | boot-fix report; non-crashing | design call: move the check into `run!`'s body |
| JVM-side `[:fn]` registrations (`seon/server/registry.clj:59,98`, `seon/dev/*.clj`) — law violations if those forms ever round-trip | boot-fix report | JVM sweep, post-demo |
| minute-resolution todo ages (`todo.cljs:115`) + 1h rolling warnings cutoff (`warn.cljs:717`) bust the provider cache prefix at every minute/hour boundary | gym U3 report | V4 composer rewrite (both sections land in the volatile tail anyway — verify placement solves it) |
| `my.soul` references `:my.kb/*` provenance attrs from outside the my.kb family | SOUL report | decide: provenance shapes to a neutral ns, or bless cross-family reference |
| `cljs-finish-clj-pivot-plan-2026-06-09.md:~321` still describes SOUL as hardcoded in deepseek | SOUL report | stale-doc fix with the next PRD touch |
| s12 baseline: agents tell the user `transact!` THROWS; truth = errors-as-values | gym baseline (judge 40/40, both agents) | V4-1 system paragraphs + seon.db docstrings (in the v4 bar) |
| s32 baseline: re-grep economy (2 greps, cap 1) — consults stored finding then greps anyway | gym baseline | v4 teaching surfaces; re-measure at the post-refactor sweep |
| T6 overlay verified via curl markup only — backtick/Esc/focus-guard not exercised in a real browser | T3+T6 report | one manual check (or browser-automation pass) before demo |
| outside-builder blockers: konserve local fork (4 deps.edn sites), `bin/run:11` hardcoded JAVA_HOME, README lacks build instructions, `.mcp.json` absolute paths, datahike gitlink pinned to unreachable sha | release-readiness research (d1b8e90) — push plan §7 PARKED for user go | release unit when user green-lights the push |

## What P8 proved (so the plan stays honest)

Consult-first 5/5 under stricter predicates; provenance storage +
cross-agent correction real; reply discipline transformed; zero
src-behavior regressions from 19 commits of refactor (every apparent
one was harness staleness). The costs are economy and legibility, not
capability.
