---
type: prd
status: active
tags: [prd, agent]
---

# agent-ctx roadmap — we are here → the target

The single **we-are-here** for this chunk. The target (idealized system) is
`docs/seon/architecture/` — present tense; THIS doc holds what's built, the
gap, and the ordered path, for BOTH lanes. Shared state + issues: [[CLAUDE]].
Cross-lane channel: [[coordination]].

## Where we are (2026-07-02)

Branching off agent-fsm's shipped capstone (see
`docs/prds/agent-fsm/roadmap.md` §"Shipped 2026-07-02"). Built and merging:
tool parity (shell/python/web/file-edit/grep/blob), `my.plan` (rename +
planning redesign), the race-timeout wedge fix, the fn-spec heal, the
consolidated architecture docs. The eval harness (`src-inspect-ai/`) is built +
pytest-green but **not yet running** a standing suite. The context-composition
work (required-key resolution) is designed + Phase-1-built (held as a patch).

## Tooling lane — the ordered path (ratified with owner 2026-07-02)

**Interleave rule (owner call): one stability unit lands per feature unit** —
the P-stability queue burns down continuously without stalling the flywheel.
Feature order (turn-capture pulled forward — it is the eval lane's attribution
substrate):

1. **Required-key resolution** — ✅ COMPLETE (2026-07-02). Phase 1 landed
   (`a6362630`); remainder landed: `:seon.render/at` registered (basis-t
   `:int`, owned by `seon.render`) + third `injectables` entry; the `my.plan`
   skip-syms entries REMOVED — the verbs ride the one injecting wrapper and
   declare `:seon.agent/id` as a request key (`internal/scoped-agent`'s
   ambient read deleted; in-body guards keep the semantic `::ok?` envelopes).
   Live-proven on the default pod: `step!` with no id stamps
   `:my.plan/agent → root`; a fn declaring `:seon.render/at` gets the live
   basis-t injected.
2. **Current-ns render-fn auto-run** — ✅ COMPLETE (2026-07-02). The render
   pass derives ONE context block per current-ns fn whose OUTPUT schema
   declares a render twin (`:seon.render/ai` / `:seon.render/hiccup`, incl.
   the `:seon.render/html-response` ref) — structural detection via malli
   over `:seon.fn/spec`, no name lists (`seon.agent.ctx.render-fns`,
   merged into `context-root`'s single ordered child list at priority 30).
   Derived per render, never stored; `install!` slots AND the canvas
   content are pins (not re-derived). Runs bounded + errors-as-values
   (SCI for agent syms, the injecting wrapper for core syms; db/id/at
   explicit = frozen snapshot); a throw → an actionable `;; ⚠` line (with
   the humanized malli explain) + the `:seon/error` tile; ai output
   clipped at `:seon.config.render/render-fn-token-cap` TOKENS. Landed
   with three adjacent root fixes the build/drive surfaced: (a) SCI
   env-reconstruction derives the canonical home-ns form + unions
   `:seon.ns/requires` edges (home-ns render fns now run bounded);
   (b) `start!`/`delegate!` dropped their optional `:seon.agent/id` slot
   (the injectable convention resolved it to "me" — every agent-scoped
   spawn silently self-upserted); (c) `changed-defs` body-only-redef
   rescue in the eval tee (a body edit with unchanged meta was
   digest-invisible → stale `:seon.fn/source` → SCI rendered the old
   body forever; also re-instruments the fresh var). Uncoached DeepSeek
   drive: the agent authored `subs-tile` (output
   `:seon.render/html-response`) turn 3; `inspect/turn` shows the derived
   block in its turn-4 verbatim prompt; nudged on the error it
   diagnosed + redefined the fn from the ⚠ explain.
   Tests: `test/seon/agent/ctx/render_fns_test.cljs`.
   (design: [[research/explicit-deps-injection-2026-07-02]])
3. **Observability turn-capture** — ✅ COMPLETE (2026-07-02). Always-on:
   every `run-turn!` persists `:seon.agent.turn/rendered-as-of` (the
   PRE-turn basis-t of the frozen db) + `prompt-blob`/`reply-blob` refs
   (`my.blob`, content-addressed) + `:seon.agent.turn/error` on failure —
   capture is errors-as-values, never wedges a turn.
   `seon.agent.inspect/turn` reconstructs {basis-t, verbatim prompt/reply,
   tokens, tx trail}; `turn-diff` gives basis-t delta + prompt drift
   (tokens + line multiset). Eval-lane consumption: per-row
   rendered-context evidence = `(seon.agent.inspect/turn
   {:seon.agent.turn/id id})` over the sample's turns. Tests:
   `test/seon/agent/turn_capture_test.cljs` (4 tests / 26 assertions).
   Follow-up queued: the gym driver still reads the gated `seon.debug`
   prompt.txt file tree — migrate it to prompt blobs, then retire the
   file tree (dual-path registry row).
4. **`my.*` as namespace-scribed entities** — ✅ COMPLETE (2026-07-03).
   Ref-direction SETTLED: **DATA→AGENT** (`:my.plan/agent`, registered in
   `my.plan` — the owning ns is the schema authority; there is no
   `:seon.agent/plan`, the core agent schema never learns a domain). The
   existing direction WAS the design; the unit formalized it: the
   `:my.plan/step` entity declaration is now honest (required
   id/title/status/agent/created-at — what step!/plan! write
   unconditionally; from/completed-at joined the optionals), and the
   list-open PROJECTION got its own `::open-step` schema (the old conflated
   shape also mis-specced a pulled `::message` ref as a transact-side ref).
   No cascade by design: retracting an agent retracts the incoming scoping
   edges (datahike `retract-entity` v-datoms, transaction.cljc:897) and
   orphans the rows out of every scoped read (history recovers) — component
   cascade stays reserved for owned bounded sets. `my.kb` checked: global by
   signature everywhere, consistent. Live-proven on a fresh default cluster:
   plan! under with-agent stamps the ref; both-direction pulls
   (`:my.plan/agent` / `:my.plan/_agent`); no-id injection stamps the scoped
   agent; agent-retract orphaning observed in the store; the plan block
   renders in root's ctx (`/agent/root/debug`) with ZERO SCI-bounding
   warnings. Docs: data-model.md §5.1–5.3; test:
   `test/my/plan_test.cljs` `entity-ref-direction-and-agent-retract-semantics`.
5. **Canvas = last-updated tile** (derived default, pin to override) — ✅
   COMPLETE (2026-07-03). Resolution is now pin → derived → welcome, one
   path (`live-tile/wired-content` grew an optional caller-supplied
   `::derived` slot; `render-agent-tile` + the `:live-tile` ctx section
   both feed it, so the human's canvas and the agent's provenance header
   name the SAME value). The derivation
   (`seon.agent.ctx.render-fns/last-updated-tile`) is a pure f(db):
   candidates = the agent's OWN authored tile fns (`:seon.fn` rows whose
   source-datom tx carries the agent's `:seon.db/agent-id` provenance and
   whose spec output declares the hiccup twin — structural, no lists);
   touch = max(own source tx, max tx of the attrs the source names as
   qualified keyword literals — the declared read-set, read on the HISTORY
   view so retractions count). Argmax touch wins; the derived-canvas fn is
   skipped as its own auto-run block (same as a pin); no candidates →
   welcome unchanged. Honest bound (documented in context.md): a tile
   reaching attrs only dynamically follows only its own redefinitions.
   Live-proven on the default pod (agent `cvp-2607030320`): authored
   plan-tile then clock-tile → canvas = clock-tile (last authored); ONE
   `my.plan` write → canvas = plan-tile rendering the live item, observed
   in the gunzipped `/agent/{id}` feed `#world-canvas`; pin → pin wins
   regardless of recency; retract pin → derived again; ctx section header
   reads "(derived — your last-updated tile; … pin …)" with the fn source
   inline. Tests: `render_fns_test.cljs` (last-updated-tile ×4) +
   `live_tile_test.cljs` (pin>derived>welcome). Docs: context.md + ui.md
   canvas paragraphs, ui-live-tiles skill.
6. **Queued tool defects** — fresh-world `my.kb` empty render (✅ resolved,
   see agent-ctx CLAUDE.md); turn-6 recall visibility; ~~SCI-bounding
   fallback on `my.plan.internal/plan-block`~~ (✅ fixed 2026-07-02, issue
   note completed; re-verified on the default cluster 2026-07-03 — zero
   warnings on a fresh boot with live plan renders; alias STORAGE residue
   ~~tracked as registry M4~~ ✅ CLOSED 2026-07-03 with C28 (`7c385f61` +
   `877d8a80`): the tee/setup/boot-index store `:seon.ns/require-edges`
   component rows + `:seon.fn/read-attrs`; the SCI cage env and the
   canvas watch set read DATOMS — text re-parse survives only as the
   observable pre-structural fallback (once-per-ns debug note)).

Stability queue (interleaved, one per feature unit above; owner-agreed
2026-07-02 — each fix REUSES an existing mechanism, no new ones):

1. **Provenance-at-the-boundary** — `seon.db/transact!` stamps
   `:seon.db/origin` from the ambient scope (same boundary-resolution concept
   as the unit-1 injectable registry); callers never pass it; DELETE
   `warn-on-seed-origin-forge!` (the forgery becomes impossible, killing the
   ×3 boot warning).
2. **Pub-socket feed migration** → **transact-timeout semantics**
   (`docs/seon/orchestrator/issues/tx-feed-pump-timeouts.md`).
3. **SCI alias root-fix + fallback DELETION** — store the analyzer's requires
   on `:seon.ns/source` so SCI resolves aliases (code-as-data reuse); a fn
   that still can't run bounded renders a `:seon/error` tile
   (never-crash-always-surface) and the unbounded compiled fallback path is
   REMOVED. Absorbs `sci-bounding-fallback-plan-block.md` and part of the
   `*conn*` root.
4. ~~**`*conn*` single-dynamic-root / fiber-local**~~ — DISSOLVED by the
   one-pod-per-cluster ruling (coordination.md MAJORs): one pod = one world =
   one root is correct by construction; the root-swap machinery is DELETED in
   the eval lane's cluster build, not fixed. Turn-6 recall re-verifies after
   that build.
5. ~~**skip-syms → zero**~~ ✅ DONE (2026-07-02): `skip-syms`/`skip?` DELETED.
   `seon.agent.search`/`fs`/`message` verbs now ride the one injecting
   wrapper (semantic failures stay `ok? false` envelopes; shape-invalid →
   structured instrument error — the `my.plan` doctrine). The one residual
   opt-out is STRUCTURAL: `seon.instrument/async-unwrappable?` — an
   `^:async` fn that cannot take the Promise-aware injecting wrapper
   (variadic/multi-arity, e.g. `seon.db/transact!`, `seon.eval/eval`,
   `seon.client/mem-db`) registers NO wrapper; computed from the async flag
   + live fn shape + schema form, never a name. Boot 553/18 → 569/3.
6. **Mechanical unification sweep** (one cleanup unit, after roadmap item 1
   lands — audit 2026-07-02): `SEON_EMBED` read ×3 → the one
   `embed-retrieval-on?`; the ×8 pr-str+clip helpers → one bounded-print
   (budgets in TOKENS, fixing the chars leak); `ai.*` private `env*` readers →
   `seon.platform/env-val`; worker-eval bootstrap copies → a shared leaf ns;
   dead `:seon.agent.ctx/fn` attr + inert-comment residue deleted; **eval
   envelope bare `:ok`/`:error` → `:seon.eval/*`** (owner call — one envelope
   convention everywhere).

Deferred (noted so it isn't lost): fold `seon.dev.compliance`'s extra checks
(docstrings, unregistered schema refs) into `seon.warn` when the JVM track
retires — owner call 2026-07-02, not before.

Small fixes (no unit needed): dev hook resolves `logs/`/`tmp/` from the repo
root, not the edited file's tree (submodule litter). ✅ Skills corpora SPLIT
(owner ruling, `68d73395`): `seon-skills/` (manifest-owned) = the seon agents'
in-runtime corpus; `.claude/skills/` = Claude Code's dev corpus — real copies,
no symlinks, free to diverge by audience perspective; convergence returns when
seon agents write seon code. Follow-up (content, eval-lane-shared): rewrite
agent skills from the agent's in-runtime perspective where they still read as
repo-dev docs.

## Eval lane — the ordered path

1. ~~**Calibration run**~~ — ✅ DONE 2026-07-02
   ([[research/calibration-run-2026-07-02]]): per-pod `/solve` ceiling = **1**
   (conn-swap collisions observed live at c=2: cas write-errors + 300s burns —
   parallelism = more pods, never more samples per pod); gsm8k median 40.7s /
   p90 ~70s → `QA_SOLVE_TIMEOUT_S=240` (opt-in), general default 300s, wired into
   `src-inspect-ai/src/seon_inspect/config.py` (call-time, per-run
   overridable). Agentic rows re-calibrate when their generators land (step 3/4).
2. ~~**Dataset freeze**~~ — ✅ DONE 2026-07-02: `seon_inspect.freeze` +
   `evals/datasets.lock` (global seed 20260702; gsm8k/arc_challenge 15/15,
   mmlu 15/15 subject-stratified, gpqa_diamond 10/10, rest = blind test;
   bespoke rows reserved `pending-generator` with generator seeds 1/2/fresh).
   Regenerate-with-lock = verify (no-op or loud diff, proven ×2 byte-identical);
   tier discipline structural (milestone aggregate-only, test raises without
   `formal_eval=True`, canary GUID → test-sample METADATA); canary CI grep =
   `tests/test_canary_guard.py` (fail proven on a planted canary in docs/).
3. ~~**Tool-row generators**~~ — ✅ DONE 2026-07-02: seeded generators
   (`src-inspect-ai/src/seon_inspect/generators.py` — 8 templates per row,
   rows derive from seed + procedure, byte-identical per seed) + outcome
   oracles (`tool_scorers.py`: workspace re-read for shell/file-edit with bb
   parse + node behavioral eval on code targets; LOCAL-fixture ground truth
   for web-fetch via `serve_fixtures`, loopback only). Goal-stated, never
   API-coached (test-enforced: no Seon verb names in any task text); every
   scorer check is stated in the task text. Lock entries upgraded
   `pending-generator` → `generated` (dev artifacts `evals/{shell_use,
   web_fetch,file_edit}.dev.jsonl`, dev+milestone sha256s, canaries carried);
   regenerate-with-lock verified no-op ×2 incl. artifact hashes. Live-drive
   calibration of these rows rides step 5 (first dev pass).
4. ~~**Planning bench re-ground**~~ — ✅ DONE 2026-07-02 (offline unit; the
   headline continuity row, bespoke by design — no public bench measures
   plan-survives-restart). Seeded TWO-PHASE generator
   (`generators.py:long_term_planning`, 8 templates: phase 1 = partial data +
   the full stated contract, phase 2 = remaining data to the SAME agent after
   the pod restart; synthesis answer spans both batches). Two-part oracle
   (`planning.py`): final answer (reuses `check_answer`) AND resumption
   evidence from the agent's `:my.plan` step rows across the interruption
   timestamp (durable pre-restart plan · ≥1 pre step completed post-restart ·
   no new post-restart root = no re-plan · no pre-restart leaf left open;
   message-minted steps excluded; open parent roots tolerated — my.plan derives
   parent done-ness). Choreography (`run_planning_sample`) is
   injected-callable + unit-tested; the LIVE driver is a loud stub — the dev
   pass needs (a) a durable-world `/solve` variant that reuses an `agent_id`
   across the restart on the ISOLATED planning cluster and (b) the plan
   read-back (`SNAPSHOT_QUERY_NOTE`). Lock upgraded `pending-generator` →
   `generated` (canary carried; regenerate-with-lock no-op ×2); artifact
   `evals/long_term_planning.dev.jsonl`; goal-stated verb-scan now covers
   phase-2 texts; pytest 95 → 116 green. Supersedes the spike
   `docs/prds/agent-fsm/research/inspect-bridge-spike/planning_resume_bench.py`
   (pre-rename verbs, kept as history).
5. ~~**Cluster formalization build**~~ — ✅ DONE 2026-07-02 (two sessions; the
   first died at the migration snapshot `8a035be9`, finished on
   `feature/agent-ctx` HEAD). db-name = CLUSTER NAME everywhere (C15: the ONE
   derivation `seon.store.wire/cluster-name` = basename of `SEON_CLUSTER_DIR`;
   wire ops/feed labels/logs carry it; `bin/seon` passes `--db-name` to the
   wire-server) · supervisor-facing `list-dbs`/`remove-db` wire ops +
   `registry/delete-db!` (ambient-cluster guard; never agent-exposed) ·
   `bin/seon cluster create <name> [--ephemeral]` / `destroy <name>` (create =
   wire-server ready-gate (C16) + `pod-<name>` on an ephemeral port, db
   ensured `:file` at pod boot; destroy = stop pod + registry delete +
   `rm -rf data/clusters/<name>/` incl. `blobs/`) · `/solve` scratch machinery
   DELETED, replaced by `POST /agents/run` (start-or-reuse `agent_id`, real
   wake path, window-scoped truthful metadata, one conn) · turn capture
   verified per-cluster (`inspect/turn` ok inside an ephemeral cluster) ·
   boot latency create→ready: 23.5s cold / 9.3s / 9.3s warm (no pool needed
   yet). Live proof: probe1 created → DeepSeek task "391" :completed →
   agent-id reuse drive → destroyed, registry `[:default]`, dir gone, default
   cluster untouched; acme reset green under `--db-name acme`.
   **Follow-up (next src-adjacent unit): harness re-point** — inspect-ai's
   solver → per-sample `bin/seon cluster create` + `POST /agents/run`. Door
   delta vs `/solve`: path `/solve` → `/agents/run`; new optional `agent_id`
   request key (the planning row's restart-reuse prerequisite — step 4(a)
   DELIVERED); response adds nothing, but `turns`/`evals` are now scoped to
   the REQUEST's window (a reused agent's history never inflates counts);
   unknown `agent_id`/failed mint → HTTP 422 `{"error": …}` (was only
   500/503).
6. ~~**Harness re-point**~~ — ✅ DONE 2026-07-03 (the last gate before the dev
   pass). Config surface renamed to cluster vocabulary, NO back-compat alias
   (`SEON_CLUSTER_URL` / `cluster_url()` / `DEFAULT_CLUSTER_URL` /
   `run_timeout_s`; call-time resolution + env-never-shadows-config kept);
   `pod_run` posts to `/agents/run` with optional `agent_id`, HTTP 422 →
   `AgentRunRefused` (a distinct wiring-defect class, never a model score).
   NEW `seon_inspect.cluster`: create/restart_pod/destroy via `bin/seon`
   subprocesses, per-cluster port-file read + ready poll, `wire_repl_json`
   (sentinel-framed JSON over the wire-server socket REPL) — all effects
   injectable, offline-tested with fakes. `run_bench` gains
   `per_sample_cluster=True` (one ephemeral cluster per sample, serial;
   static-URL mode stays for acme); per-sample budget = boot
   (`CLUSTER_BOOT_BUDGET_S=60`, measured 9.3-23.5s) + row timeout.
   **Planning row LIVE** — `pod_planning_driver` replaces the stub: cluster
   create → phase 1 → `bin/seon restart pod-<cluster>` (fresh ephemeral
   port re-read) → phase 2 with the SAME `agent_id` → `fetch_plan_snapshot`
   (SNAPSHOT_QUERY_NOTE implemented over the wire REPL) → destroy. Live
   proof (DeepSeek, 1 sample, 103s wall): phase 1 laid a 6-step plan,
   `:waited`; restart; phase 2 REUSED the agent (boot log `resumed
   ["cig-…" "root"]`), completed 4 pre-restart steps post-restart, 0 new
   roots, replied the oracle answer "1428" — `check_planning` ok=true on
   BOTH parts. Shell row smoked live end-to-end (workspace materialized,
   agent drove real shell, oracle correctly scored a model fabrication
   INCORRECT — `result-edn` showed wc exit 1 vs the model's invented "=>"
   echoes). Grants verified in the ephemeral pod's process env
   (SEON_SHELL=1, SEON_WEB=1 inherited from the supervisor). pytest
   116 → 134 green; datasets.lock regenerate-verify no-op.
7. ~~**First dev pass**~~ — ✅ DONE 2026-07-03. The ledger is LIVE:
   `evals/scorecard.jsonl` (4 rows appended, append-only; shape documented in
   the src-inspect-ai README) + the standing `pass^k` regression alarm
   (`tests/test_scorecard_alarm.py`: latest dev pass^1 vs the row's ≤7-run
   median, drop >0.10 fails the suite — eval-design's rule). New maintained
   modules: `seon_inspect.scorecard` (reducers + append discipline) and
   `seon_inspect.tool_rows` (per-sample tool-row wiring: materialize →
   ephemeral cluster → drive → oracle re-read; harness failures are flake
   classes, never scores). Headline numbers (DeepSeek, dev tier):
   gsm8k n=15 k=3 → mean .730 / pass@3 .889 / pass^3 .778 · shell_use n=8
   k=3 → .667 / 1.00 / .600 (every sample CAN pass; 3/5 always do) ·
   file_edit n=8 → .800 · long_term_planning n=10 → .286 (ALL scored
   samples answered the synthesis correctly — db data survives restarts;
   fails are plan DISCIPLINE: steps left open / premature closes / re-plan
   roots) · web_fetch VOIDED (no ledger row — 5/8 samples ran under mid-run
   dev-watch hot reloads, 3/8 run_error; re-run queued after bench-bundle
   isolation). Rendered-context sanity check passed pre-batch (task contract
   verbatim in the captured turn prompt, ~19k est tokens). Evidence:
   `evals/runs/2026-07-03-first-dev-pass/` (per-execution records incl. the
   archived contaminated/pre-fix gsm8k runs + the sanity prompt).
   **Harness finding (the load-bearing finding biting our own bridge):**
   `run_bench` replaced each bench's WHOLE solver chain, silently dropping
   its answer-format contract (gsm8k's "ANSWER: $ANSWER" prompt_template) —
   correct conversational replies scored INCORRECT. Fixed:
   `catalog.swap_generate` keeps the task's own template/system solvers and
   swaps only `generate()`; the pod solver POSTs the TEMPLATED
   `user_prompt.text`. Pre-fix acme run: mean .500 → post-fix .730 on the
   same frozen samples (the delta IS the dropped contract).
   **Environment defects found (evidence in the run dir + coordination):**
   (a) bench/ephemeral pods run the WATCHED dev build — cljs-watch rebuilds
   hot-patch them mid-sample (`reloading…` then `run-turn! error No matching
   clause`); every affected execution excluded as
   `hot_reload_contaminated`/`run_error`; fix = frozen bench bundle via
   `SEON_CLIENT_OUT` (queued, next src-adjacent unit). (b) the default-stack
   wire-server is SHARED and was restarted externally mid-run — ephemeral
   dbs deregistered ("unknown db-name"), one boot died on the vanished UDS
   socket. (c) a long-lived pod accumulates one agent per sample until node
   OOM (acme crashed at 4GB heap after ~55 agents — gsm8k epoch 3 truncated
   at 11/15) — per-sample ephemeral clusters are structurally immune;
   restart the acme pod between heavy rows until then.
8. **Ongoing / cadence** — re-run web_fetch after bench-bundle isolation;
   standard-bench sweep (arc/mmlu/gpqa dev) on a quiet stack; per-row
   rendered-context audits (every trim is an A/B), baseline each tool the
   tooling lane lands, milestone runs at merges, the mvm case-2 sandbox tier
   later.

Spec: [[eval-design]] · plan: [[eval-lane-plan]] · readiness:
[[research/tool-surface-survey-2026-07-02]].

## Still open (from agent-fsm, may land here)

Root-world-at-`/`, the spawn capability gate + roles,
`:seon.agent/purpose` → `:my.agent/purpose`. (The observability
turn-capture build landed — item 3 above.)
