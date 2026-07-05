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

**Hygiene sweep 2026-07-05 (registry rows C36/C38/C39 + the hint-for
evaluation):** the flat `:seon.ns/requires` twin is DELETED —
`:seon.ns/require-edges` is the one ns-dep store, flat views derive via
`seon.eval/stored-require-targets` (`87ec6e45`); the read-attrs tee excludes
defn annotations (`2264ffe4`); seon.warn internals speak persisted/owner-ns
keys (`0d0d9358`). `hint-for` is KEPT — malli's `me/with-spell-checking` was
evaluated and REFUTED as a replacement (open maps → no-op; wrong-ns near-miss
rejected by its levenshtein threshold even closed) — see the correction in
[[research/malli-instrument-error-data-2026-07-04]] §5.

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

7. **Eval-tee robustness (registry C37/C24/C34)** — ✅ COMPLETE
   (2026-07-03). C37 `fb234016`+`d0de3f60`: `::kw`/`::alias/kw` sources
   pass the read-gate flywheel (the ONE whole-source structural read is
   now `seon.repl.internal/read-forms`, rewrite-clj `:auto-resolve`;
   cljs.tools.reader has NO current-ns hook — live-proven); stored
   `:seon.fn/read-attrs` carry RESOLVED keywords; falsification caught
   the resume leg — the sourceless home-ns load unit gets its `(ns …)`
   head SYNTHESIZED from the M4 `:seon.ns/require-edges` datoms
   (replay 10/10, `::`-fn resumes into its own ns, instrumented).
   C24 `d8994100`: the body-only-redef rescue generalized to every
   source shape (`source-def-syms` walk; single-defn special case
   deleted; body-sensitive var-digest ruled out — the var-map carries no
   body). C34 `5f1686af`: `var-projection` speaks
   `:seon.analyzer-info/*`. Full suite 972/4462 0F/0E.

8. **Error recording phase 1 (error-blame-strict-gate)** — ✅ COMPLETE
   (2026-07-04, `0e9c9b92`+`a69da9f0`+`17c04e3a`). `seon.error/record!`
   (fault `:agent|:core` + `:seon.error/at` basis-t + EDN frame component
   entities + full malli `args-edn`; fire-and-forget persist, bounded
   buffer, one-error-one-datom dedup tag); the `:seon.config/on-core-error`
   dial SHIPPED `:gate` (`:crash` persists-then-exits, proven with a
   stubbed `process.exit`); the process net; the injecting-wrapper async
   arms (rejections + resolved-output violations become datoms;
   `wrapper-fault` content refinement keeps agent typos rejecting through
   the `seon.eval` conduits `:agent` — the live drive caught the
   misclassification); root-only `core-faults-block` (vanishes past the
   latest user message — live-proven render + vanish); gates in
   `bin/test-cljs` (transcript marker) + the dev hook (pod-log offset
   bracket, block-on-new-fault). `agent-authored-sym?` MOVED
   render.sci → `seon.error`.

   **Phase 2 (the 4-file catch-site sweep)** — ✅ COMPLETE (2026-07-04,
   `825332ce` render/sci + `68f66070` eval.cljs + `74736906` client.cljs +
   `f1d035b7` render.cljs). All 44 `catch :default` sites classified: each
   either `record!`s a fault-tagged datom (guarded by `recorded?` so a
   propagated already-recorded error is not double-counted) or is annotated
   `;; probe:` (expected-absence, e.g. a missing-lookup-ref throw, a
   best-effort parse). Conduit/render sites classify by content
   (`wrapper-fault`, now public) or by the render symbol
   (`fault-for`/`agent-authored-sym?`); OUR-machinery sites default `:core`.
   Return contracts byte-unchanged (live-proven per file: agent typos →
   `:agent` datoms, forced core throws → `:core` datoms w/ frames + `:at`,
   caller envelopes identical).

   **Phase 2 finish — `:crash` FLIPPED + LIVE-PROVEN** — ✅ COMPLETE
   (2026-07-04, `5e2416c2` C41/C42 + `d2a11341` gate/config + `114b6c80`
   crash flip). Three parts:
   - **C41 (owner option A):** `seon.error/expecting-core-fault!` — a TEST
     bracket (module-level depth counter, async-safe across `.then` hops —
     NOT a dynamic binding) making `escalate!` print the distinct
     `SEON-EXPECTED-CORE-FAULT` marker the gate does not count (datom still
     written, `:crash` not taken). 8 genuine-`:core` render/tile/block
     fixtures annotated. bin/test-cljs counts `SEON-CORE-FAULT` MINUS the
     `-EXPECTED-` variant.
   - **C42 (real bug the C41 investigation surfaced, crash-flip-critical):**
     agent-form failures (a cljs.js `:cljs/analysis-error` — undeclared var /
     bad require — or a `:user-input`/`:read`/`:seon.eval/repl-parity`
     `:seon.error/kind` — a mistyped query attr) were misclassified `:core`
     and would have crashed the pod under `:crash`. `wrapper-fault` now
     classifies them `:agent` (a cljs.js analysis error only ever arises from
     an agent form; core is AOT-compiled by shadow). This is why the tee /
     gym / require "faults" need NO bracket. The md->hiccup fault was a
     variadic-only redef fixture (fixed to multi-arity).
   - **Config-selection:** bin/test-cljs defaults `SEON_CONFIG=config/test.edn`
     (dial unset → `:gate`), so the suite runtime + gate both stay `:gate`;
     the dev manifest (`config/system.edn`) flips to `:crash`.
   Full suite = **983/4514 0F/0E**, gate GREEN (0 un-expected markers, 8
   expected). Live proof on the default pod (7890) under `:crash`: a real
   agent mistyped-attr query → `:agent` (no crash); a `:core` record! →
   `SEON-CORE-FAULT` then "exiting after persisting the fault datom", pod
   exits, datom persisted (`#{[:core 536870943]}`, same `@t`); `:agent`
   record! on the fresh pod → no crash. Registry **C41**+**C42** CLOSED.

   **Unit A (error-workflow arc): triage verbs + watch + deepest message** —
   ✅ COMPLETE (2026-07-05, `05fc844c`+`ac13d909`). Three `seon.agent.inspect`
   altitudes over the persisted error datoms: `errors` (compact newest-first
   list — eid/fault/`at`/deepest-cause message/top frame/agent; fault filter +
   limit), `error` (full envelope + JOINS: recording agent via tx-meta, the
   turn active at that basis-t — tx turn-id when turn-scoped, else the
   `rendered-as-of` window; composes with `inspect/turn`), `repro` (the
   work-backwards bundle: the LIVE as-of db value frozen at `:seon.error/at`,
   fn-sym + args-edn from the malli envelope, a ready-to-eval repro
   expression; honest `::note` when args were not captured).
   `seon.error/deepest-message` (moved from seon.eval, single owner) now
   feeds the datom projection, so the `SEON-CORE-FAULT` marker names the
   real cause, never cljs.js's `"ERROR"` wrapper (prefix/format unchanged —
   gate greps intact). `bin/seon watch-faults [--cluster n]`: dependency-free
   background alarm — tail -F from EOF, fires on the first NEW un-expected
   marker, prints it + 20 context lines, exits 0. Convention documented
   (CLAUDE.md "Core-fault watch" + observability.md). Live-proven on the
   default pod under `:crash`: real typo eval → `:agent` datom, triaged
   through all three verbs; repro's frozen db re-raised the exact violation
   AND differed from head; a malli-path violation re-invoked via the bundle's
   expression → same `:malli.core/invalid-input`; a real `:core` fault →
   watch fired (expected marker earlier ignored), pod exited after
   persisting, restart clean; seeded datoms retracted. Suite **985/4549
   0F/0E**, gate GREEN.

   **✅ ACCEPTANCE DRILL PASSED (2026-07-05,
   [[research/error-workflow-drill-2026-07-05]]) — DONE-with-drill.** A
   realistic core bug planted in `seon.render.value/truncated?` (dropped
   `map?` guard — `tree-seq` leaf scalars), tripped ORGANICALLY by an
   uncoached DeepSeek planning+db-memory drive (turn 0's first eval value
   render), and the whole loop closed on the shipped tools alone: crash +
   datom persist → `watch-faults` fired → `errors`/`error`/`repro` (frames
   at the planted line, turn join, byte-exact 17,445-token prompt via
   `inspect/turn`) → fork-hint run VERBATIM → reproduction in the fork
   (identical marker; error datom absent inside its own fork, as designed)
   → fix verified in the fork FIRST → fork destroyed, re-drive green,
   faults section blank by window-move, watch silent, suite green. All 11
   links PASS; one gap found AND fixed (C51: a mid-eval crash stranded the
   captured reply blob — `ask-and-eval-reply!` now links
   `:seon.agent.turn/reply-blob` eagerly at capture).

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
8. **Frozen bench bundle — ✅ BUILT + LIVE-PROVEN 2026-07-03** (fixes
   defect (a) above): ephemeral clusters now default to a FROZEN bundle —
   `bin/seon cluster create` builds `:bench-client` (a `:client` mirror
   with its OWN build id → `out-bench/client/main.js`; shadow pushes
   reloads per build id, so cljs-watch can never hot-patch it) via the
   same `clj -M:cljs compile` invocation, staleness-guarded at create
   (acme's rule), PINNED across pod restarts (restart never rebuilds —
   the planning row's interruption stays on one bundle). `--watched`
   opts a dev inner loop back in; durable creates default watched.
   Build writes `out-bench/client/main.js.sha256`; the harness records
   the identity in each per-sample run's EvalLog metadata
   (`catalog.run_bench`) and asserts it unchanged at run end —
   violation raises `cluster.FrozenBundleChanged` (flake class
   `frozen_bundle_changed`), so contamination is DETECTED, never scored
   through. Live proof: two drives on a frozen ephemeral cluster
   completed clean (`:completed`, correct replies) while a src touch +
   revert recompiled `:client` mid-drive — frozen pod log 0 `reloading`
   lines; the same touches hot-patched a `--watched` contrast cluster 6
   times. pytest 175.
9. **bench-cluster-N + cadence sweep — ✅ DONE 2026-07-03.** (A)
   CONCURRENT per-sample clusters: `run_bench(per_sample_cluster=True,
   cluster_parallelism=N)` + `tool_rows.run_tool_row(..., parallelism=N)` —
   N ephemeral clusters live at once over a bounded thread pool, each
   sample still its OWN cluster (POD_MAX_SAMPLES stays 1). N calibrated
   empirically (trivial DeepSeek drives, frozen bundle, shared wire-server;
   serial-equivalent throughput): N=1 23.9 s/sample · N=2 13.0 (1.84x, 0
   errors) · N=4 11.5 (only +12% over N=2 for 2-3x create/drive latency
   inflation) → **default `config.BENCH_CLUSTER_PARALLELISM = 2`**;
   wire-server clean at both. Cross-talk spot-check at N=2 CLEAN: two
   concurrent samples' turns each landed ONLY in their own cluster db
   (turn→run→agent join over the wire REPL). The frozen bundle pre-builds
   ONCE up front (`bin/seon bench-bundle`, mutexed) — creates never rebuild
   (freshness is RUN-level). (B) web_fetch re-run (the voided row): dev n=8
   k=3, frozen N=2 → **mean .625 / pass@3 1.0 / pass^3 .25, 0 flakes**
   (every sample solvable — instability, not a floor; 9 wrong-VALUE replies
   vs local fixture ground truth). Attempt 1 caught + VOIDED by the identity
   assertion (`frozen_bundle_changed`) — exposed TWO real defects, both
   fixed: a per-create staleness rebuild swapped code under the run when the
   tooling lane saved src/ mid-run, AND the bundle sha hashed only the
   ~70KB loader (`main.js`) while code lives in the cljs-runtime chunks (a
   rebuild left main.js byte-identical). (C) standard sweep, dev, k=1,
   frozen N=2, each bench's own `multiple_choice` chain riding a new
   `catalog.pod_backed` (its INTERNAL generate callback drives the pod) +
   guarded `pod_fallback` (keys on the pod-run marker, never re-runs):
   **arc_challenge .867 · mmlu_0_shot .800 · gpqa_diamond .700**, 0 flakes;
   per-bench rendered-prompt template spot-checked pre-batch (ANSWER
   contract + choices present). Ledger now 8 rows; alarm green; pytest 186.
   Evidence: `evals/runs/2026-07-03-concurrent-pass/`.
   **Still ongoing:** per-row rendered-context audits (every trim is an
   A/B), baseline each tool the tooling lane lands, milestone runs at
   merges, the mvm case-2 sandbox tier later.
10. **Iterate-until-green — ✅ DONE 2026-07-04.** The instruction-error
    classes from [[research/reply-surface-instruction-audit-2026-07-04]]
    iterated to green with GENERAL fixes only. Item 0 (owner-authorized
    mechanism): parentless `(complete "…")` now DELIVERS its string to the
    user via the one `message!` path before closing (lifecycle.cljs +
    targeted tests). Fix 1 (reply-channel truth) + Fix 2 (plan-resumption
    discipline) landed in `system-text`; two logged wording iterations
    (final sha `c04ea6bd6bd6`, diffs archived in
    `evals/runs/2026-07-04-iterate-until-green/`) — incl. the load-bearing
    find that models COPY example placeholders verbatim (`(complete
    "result")` → a literal "result" reply; placeholder now
    `"<the answer>"`). Ledger (arm-labeled rows): gsm8k .730→.800
    (noise-excluded .972) · mmlu .800→.933 · arc .867→.933 · planning
    .286→**.700** (armC; the armC-i2 re-run scored .400 — not separable at
    n=10 k=1; re-plan class ELIMINATED both runs, finals ~100%; residual
    step-closing compliance classified model-bound at the iteration cap).
    web_fetch ROOT-CAUSED via the new evidence retention: the
    always-on SSRF guard refuses the loopback fixtures → fabrication after
    refusal. FIXED same day (owner-directed): host-owned
    `SEON_WEB_ALLOW_PRIVATE` grant, harness-scoped to web_fetch's ephemeral
    clusters. (Later SUPERSEDED 2026-07-04 by the config-driven web-access
    policy — `:seon.config/web {:seon.agent.web/policy :open|:public-only|:allowlist}`;
    the env grant + the separate allowlist unified into one host-owned config,
    bench clusters inherit system.edn's `:open`.) Harness: run dirs now always retain .eval logs +
    per-execution reply text + pre-destroy cluster blob copies; the
    regression alarm keys by (row, arm). **Capstone FULL dev sweep
    (armD-full, one arm, bundle 393c2a26afc3, 0 flakes):** web_fetch .875 ·
    shell_use .917 · file_edit .750 · gsm8k .800 · mmlu .733 · arc 1.000 ·
    gpqa 1.000 · planning .800. Suites 973/4468/0 cljs · 197/0 pytest.
11. **Capstone-smell fix pair — ✅ DONE 2026-07-04.** (a) The item-10
    KNOWN TRADE closed: `complete` now delivers its string ONLY when the
    agent has NOT already messaged the recipient THIS RUN (derived from
    the message log, no stored flag) — a filler complete can no longer
    clobber a clean messaged answer; ctx system-text +
    agent-runtime.md aligned; live-proven on an ephemeral cluster
    (message-then-complete → exactly ONE delivered message). (b) The
    `:seon.web/url` wrong-ns mistake now self-diagnoses: fetch docstring
    names the key in line 1, and the latently-dead missing-key hint in
    `seon.error.instrument/hint-for` was fixed generically (wrong-ns
    near-miss → "you passed :seon.web/url — the key is
    :seon.agent.web/url"). Details: coordination.md 2026-07-04 tail.

12. **First established AGENTIC bench — BFCL AST subset — ✅ ADOPTED
    2026-07-04.** BFCL (Berkeley Function-Calling Leaderboard) single-turn
    AST subset is the harness's first established tool-calling row. Scope =
    the pure-AST python categories (`simple_python` / `multiple` /
    `parallel` / `parallel_multiple`) — scored by inspect_evals' pure-Python
    `ast_match` (no exec, no sandbox, no tool bridge; even single-turn
    `exec_*` GT is preprocessed into the same matcher). Excluded: `exec_*`
    / `rest` / `live_*` / `multi_turn_*` (sandbox/tool-bridge tier) and
    java/js (their literal-float rule is a separate prompt concern; loadable
    via `categories=`). New code = ONE adapter, `seon_inspect.bfcl_adapter`
    (the text→tool_call bridge): the pod emits a JSON call as TEXT (it has
    no OpenAI `tool_calls`), so a 3-step chain — `bfcl_prompt` (render the
    candidate function schemas + the JSON-call contract, stating every
    scorer check: exact names, required params, no extras, JSON types, one
    call for simple/multiple & all for parallel) → the pod solver
    (unchanged) → `bfcl_parse` (lift the JSON into synthesized `ToolCall`s
    the bench's OWN `ast_match` harvests). Scorer untouched — the
    correctness gate is BFCL's. Wiring: `catalog.BENCH_ADAPTERS` (bench→adapt
    hook; default stays `swap_generate`) + `BENCH_DEFAULT_TASK_KWARGS`
    (pins the AST subset so freeze and run load identically) + a `run_bench`
    `adapt=` seam. Frozen split added (`freeze.EXTERNAL_SOURCES` bfcl_ast,
    category-stratified dev/milestone/test 10/10/980, commit-pinned upstream
    `dac44e7a…` → contamination-proof; public bench, test reserve handles
    leakage, no bespoke canary GUID needed). **Live dev proof** (DeepSeek
    non-think, frozen ephemeral clusters, N=2, k=1, 0 flakes): mean **.700**
    (simple 1.00 · multiple 1.00 · parallel .50 · parallel_multiple .33);
    **parse_miss = 0** — every reply was a clean JSON call the adapter lifted,
    so all 3 fails are MODEL misses on multi-call tasks (wrong arg values /
    over-called by one), NOT adapter defects. Report-only band vs the PUBLIC
    BFCL leaderboard (NO DeepSeek non-think anchor exists for any
    door-fitting agentic bench — expected; never a fabricated anchor column).
    Ledger row `2026-07-04:bfcl_ast:dev:k1:armD-full` (row `tool_calling`).
    Offline proof: `tests/test_bfcl_adapter.py` (15 tests — parse shapes +
    render contract + end-to-end through the real `ast_match`). Evidence:
    `evals/runs/2026-07-04-bfcl-ast-dev/` (verbatim prompts, per-execution
    records, scorer explanations, the run recipe).

    - **FORM-surface A/B — 2026-07-05 (JSON kept).** Owner question: the JSON
      contract tests a FOREIGN surface (our agents emit Clojure forms
      natively) — a confound that might UNDERSTATE us. Reworked the adapter to
      ask for the call as a Clojure form (`(fn {:kw v})`), parsed by a small
      no-dep s-expr reader (symbol→name, `{:kw v}`→args dict, Clojure
      literals→the native types `ast_match` compares; dotted names, vectors,
      floats, multi-forms, candidate-filtered) — SAME frozen 10 dev samples,
      k=1, frozen ephemeral clusters. **Result: form .600 vs JSON .700 — the
      confound hypothesis was NOT supported.** 9/10 samples scored IDENTICALLY;
      the sole flip (`multiple_168`, JSON pass → form fail) REGRESSED, and its
      mechanism IS the finding: told to "emit a Clojure form the way you invoke
      a verb at your REPL", the agent tried to EVALUATE the undefined candidate
      function, errored, and looped to `:turn-limit` with an empty reply. A
      text form is neither clean text (JSON) nor a real executable verb — it
      collides with the agent's action semantics. Per the A/B guardrail (forms
      not ≥ JSON → keep JSON, report before ripping out), **JSON remains the
      shipped adapter**; the reworked form adapter + its 21 offline tests are
      preserved as a frozen experiment under the run dir. The evidence points
      PAST text surfaces to the **eval-native** path (register BFCL candidates
      as real stub verbs; read the captured call off the runtime — no text
      parse) — a separate owner decision, see [[coordination]]. Ledger row
      `2026-07-05:bfcl_ast:dev:k1:form-surface`. Evidence:
      `evals/runs/2026-07-05-bfcl-ast-dev-form/` (verbatim form prompts,
      per-execution records incl. the `:turn-limit` transcripts, the frozen
      form adapter + tests, run recipe).

Spec: [[eval-design]] · plan: [[eval-lane-plan]] · readiness:
[[research/tool-surface-survey-2026-07-02]] · BFCL adoption:
[[research/agentic-benchmark-adoption-2026-07-04]].

## Still open (from agent-fsm, may land here)

Root-world-at-`/`, the spawn capability gate + roles,
`:seon.agent/purpose` → `:my.agent/purpose`. (The observability
turn-capture build landed — item 3 above.)
