---
type: prd
status: draft
tags: [prd, agent]
---

# Downstream Asks Triage — 2026-06-22

**TL;DR:** Of the downstream consumer's backlog, **seven asks are already shipped on our side and just need a build pickup** (#40, #41, #42, #43, #37, #36, #32 — plus #16 effectively closed in the substrate). The **#49–53 reliability batch is fully root-caused and spec'd but NOT built** — its mechanisms touch the wake/eval/intake hot path and must ship per-spec, gated by a live falsification drive. **Three asks are cheap, safe, and aligned to do now** (#46 + #48 together — two demo footguns in one patch; #54 Fix 3 + Fix 2 — event-loop yield). The strategic track (embeddings branch + the artifact/extension design) **subsumes #34, #27, #28**, and the in-flight acme-harness tile fixes (BUG A/B) **subsume #35 and #45**. Do NOT refactor the eval loop while the live-tile SCI fix is landing. The opinionated answer: ship the relay set + the two low-risk patches now, build the #49–53 batch in its designated order behind acceptance checks, and route the big design asks through the artifact PRD rather than building standalone homes.

---

## 1. Master table — every open / not-fully-done ask

Severity = consumer's pain. Effort = our build cost (S/M/L). Stability risk = blast radius on the load-bearing subsystems (eval loop, wake/intake, transact!, write path, wire boot). TIER maps to section 2.

| # | Ask (1 line) | Actual state | Sev | Effort | Stability risk | Overlaps in-flight | TIER |
|---|---|---|---|---|---|---|---|
| 40 | `transact!` returned enormous tx-report → bloats ctx, reads as failure | **DONE** (304ef96; compact envelope, full report behind `::db/return-report?`) | high | S | low | none | Relay |
| 41 | `transact!` result embedded `#datahike/DB`/`#datahike/Datom` the CLJS reader can't read | **DONE** (304ef96; `project-agent-safe` produce-side + `sanitize-result-edn` read-side for legacy rows) | high | S | low | none | Relay |
| 42 | literal-hiccup live-tile rendered as raw text, no actionable error | **DONE** (304ef96; `hiccup-structure-error` located misplaced-attrs message) | medium | S | low | BUG A (SCI tile bounding) touches `live_tile.cljs` | Relay |
| 43 | one user msg processed by multiple turns; no halt after `reply!` + side-work | **DONE** for the `:core`/side-work path (304ef96; `origin` enum + wake-gate + halt filter, dropped forged tile push) | blocker | M | low | #47/#49 are SEPARATE re-wake vectors still open | Relay (residue → #47/#49) |
| 37 | `bin/seon prep` must leave a WARM `:writer`/`:cljs` classpath, not just git-deps | **DONE** (304ef96; unconditional `clojure -P` warm + pid breadcrumb) | high | S | low | none | Relay |
| 36 | embedding install/backfill must be OPT-IN, never NPE the wire, no outbound call key-unset | **DONE** (`embed.clj`: lazy/key-guarded client + `SEON_EMBED` master gate + guarded augmenter/backfill; wire try/catch fallback) | blocker | S | low | embeddings branch / packaging design (same feature) | Relay |
| 16 | fold generic REPL discipline (a/c/d) into substrate `<system>` | **PARTIAL→effectively DONE** (all four sentences a/b/c/d now in `ctx.cljs` system-text; asks-file status is stale) | low | S | low | none | Relay |
| 32 | `db/query`/`pull`/`transact!` bare 1-arg datalog arity (auto-inject `*conn*`) + fix error text | **DONE** (`db.cljs:419-422` auto-conn arity; prompt teaches it `ctx.cljs:996`; the "1-arg-vector error" sub-ask is moot — bare vector is now valid) | low | S | low | none | Relay |
| 46 | failed `transact!` returns ~3596-char error (Malli explanation duplicated) → trips 1500 clip | **OPEN/PARTIAL** (message itself concise; bloat = full `m/explain` duplicated in `::ex-data`+`::data`, `internal.cljs:705-707` / `error.cljs:64-66`) | high | S | low | none | Do now |
| 48 | `:seon.ns/name` rejects the quoted symbol the prompt invites → tool defined-but-not-persisted | **OPEN** (`ctx.cljs:84` keyword-only, no symbol→keyword coercion) | medium | S | low | BUG B + artifact `my.*` home touch ns/fn registration | Do now (coordinate) |
| 54 | Node event-loop starvation: sync CLJS eval blocks the HTTP server | **OPEN** (Fix 3: sync `fs`; Fix 2: no inter-form yield; Fix 1: eval on main thread — all confirmed in code) | high | M | medium | none | Do now (Fix 3+2) / Defer (Fix 1) |
| 50 | agent's prose reply re-read as Clojure → reported as FAILED eval (ROOT CAUSE) | **SPECD_NOT_BUILT** (`reliability-fixes-49-53` §1, "do first"; `eval.cljs:2624-2690` still scores unparseable prose as fail) | blocker | M | medium | #49–53 batch; shares `eval.cljs`/`agent.cljs`/`ctx.cljs` with 304ef96 + BUG A/B | Do with care |
| 51 | batch-poison: one failed sibling form refuses the whole batch's valid `reply!` | **SPECD_NOT_BUILT** (`reliability-fixes-49-53` §2a; `message.cljs:302-316` refusal gate + `:force` still present) | high | S | medium | #49–53 batch; lands on #43's origin stamp | Needs decision |
| 49 | inbound `/chat` msg silently DROPPED (ack-before-enqueue race), never wakes | **SPECD_NOT_BUILT** (`reliability-fixes-49-53` §2b; `!kick-scheduled` latch unfixed `agent.cljs:616/627`) | high | M | high | #49–53 batch; re-uses #43 origin predicates | Do with care |
| 53 | non-error success output truncated mid-value at ~1500 → agent re-queries around the clip | **SPECD_NOT_BUILT** (`reliability-fixes-49-53` §4; agent cap still 1500 `ctx.cljs:300-307`) | medium | S | low | #49–53 batch; #40 already removed the biggest trigger | Needs decision |
| 44 | agent hand-writes fake `=>` result + calls `reply!` with it before runtime executes | **PARTIAL** (render-time string scrub only `ctx.cljs:387-419`; no un-forgeable `=>` channel) | high | L | high | shares eval/ctx scoring with #50/#52 | Needs decision |
| 52 | hallucinated `=>` persists into `<past-evals>`, later deref'd as a real `result/<id>` | **SPECD_NOT_BUILT** (`reliability-fixes-49-53` §3 explicitly DEFERS; no quarantine built) | low | S | medium | #49–53 batch (deferred); blunted by #44 scrub + #50 | Defer |
| 27 | reply hook — fn fires on every assistant reply, async, transacts rows, + panel | **SPECD_NOT_BUILT** (`overridable-substrate` PRD draft; zero `fire-on-reply` seam in `src/`) | medium | M | low | artifact/extension design (delivery vehicle) | Do with care (under artifact) |
| 28 | first-class recorded/replayable home for downstream `my.*` code (seed dir) | **SPECD_NOT_BUILT** (no `SEON_SEED_DIR`; `SEON_EXTRA_SRC` refuses `my.*`) | high | L | medium | DIRECTLY = artifact/extension design (same problem) | Needs decision (converge) |
| 34 | optimized single-file `:node-script` release (lazy-load eval/bootstrap path) | **OPEN** (eager require chain + goog DEBUG-LOADER internals intact; design exists, no mechanism) | medium | L | medium | artifact-packaging design (single-file target) | Do with care (under artifact) |
| 35 | calm broken-tile placeholder (no panic) | **DONE-adjacent** (304ef96 added calm/legible split) — residual silent-drop class | (see #45) | — | — | SUBSUMED by BUG A/B tile fix | Subsumed |
| 38 | ns/fn `<inventory>` ~45% is `register!` boilerplate — collapse, surface sigs+docstrings | **PARTIAL** (fn bodies elided `namespaces.cljs:131-149`; schemas rendered in FULL `:182-246` — the exact target unbuilt) | medium | M | medium | none | Do with care |
| 39 | static sections re-bill every turn — want `:seon.ctx/cacheable?` hint | **PARTIAL** (real cache boundary exists but positional/hardcoded at `:namespaces`; no declarative attr) | medium | M | medium | none | Do with care |
| 45 | live-tile/render request silently DROPPED — agent claims compliance, no render call | **OPEN** (no post-turn "tile requested but no `:seon.render.*` emitted" detector) | high | M | medium | SUBSUMED by BUG A/B (same "silent drop" shape) | Subsumed / Do with care |
| 47 | bare-prose reply (no `reply!`) leaves msg unanswered → loop self-wakes | **SPECD_NOT_BUILT** (`agent.cljs:1467` halt needs an OUTBOUND msg; folds into #50) | high | M | high | #49–53 batch (converges with #50) | Do with care (in #50 batch) |

> Asks NOT in the table because they are fully shipped + need no relay action beyond the build pickup are already covered by the Relay tier above. #52/#44 appear because they remain partial/unbuilt despite adjacent mitigations.

---

## 2. Tiers — what we can RELIABLY do

### Tier A — Already done, relay / re-confirm (shipped our side; consumer picks up the build)

These are merged on `feature/embeddings` (most at 304ef96; #36 across `eaf67a8`/`16fcbc1`/`af0eda4`; #16/#32 via the `550e70b`/`c1fcb03` prompt+db work). **No remaining work on our side — the action is: relay to the downstream and have them re-confirm against HEAD.**

- **#40** — compact `transact!` envelope; full report behind `::db/return-report? true`. Consumer can drop their workaround.
- **#41** — `project-agent-safe` (produce-side) + `sanitize-result-edn` (read-side, fixes legacy rows without a cluster reset). Invariant "agent never reads its own committed `transact` back as a reader error" holds at both ends.
- **#42** — literal hiccup IS supported; malformed hiccup now yields a located structure message. (Flag: `live_tile.cljs` overlaps the in-flight BUG-A SCI bounding — coordinate so the structure-error walk isn't disturbed.)
- **#43** — `:core`/side-work re-wake fixed via `origin` enum + wake-gate + halt filter. **Relay with a caveat:** the asks-file #43 symptom is ALSO driven by #47/#50 (prose / no-`reply!`), which are NOT yet built — so "fully fixed" is true only for the side-work vector.
- **#37** — `bin/seon prep` warms `:writer`/`:cljs` classpath unconditionally + pid breadcrumb. Consumer drops its `clojure -Spath` pre-resolve.
- **#36** — the BLOCKING invariant ("key unset ⇒ wire boots, opens a populated world, accepts writes, **zero** outbound embed call") holds via the `SEON_EMBED` master gate + lazy/`str/blank?`-guarded client + augmenter try/catch fallback. Still marked EXPERIMENTAL (`af0eda4`) and pre-merge to `main`. Consumer should live-verify a key-unset `:writer` boot against HEAD.
- **#32** — bare-arity `db/query`/`pull`/`entity`/`transact!` auto-inject `*conn*`; prompt teaches the bare form. The "fix the 1-arg-vector error text" sub-ask is **moot** (a bare vector is a valid call now). Minor residual: the docstring still hedges that arity≥2 disambiguates db-vs-`:in` via `db-value?`, which can mis-route a map-shaped first `:in` input — edge case, not the ask.
- **#16** — STALE in the asks file. All four discipline sentences (a hiccup-shape, b clipped-results-bind, c never-paste-`=>`, d confidence-grading) are now in `ctx.cljs` system-text at HEAD (via `550e70b`/`f8435c4`/`b1f1c82`). Effectively closed in the substrate; remaining work is consumer-side (strip the four verbatim copies from their identity file).

### Tier B — Do now (low risk, aligned, off the hot path or already-spec'd-and-simplified)

Small, safe, contained to failure-envelope shaping or tool I/O — none touch the wake/eval scoring core.

- **#46 + #48 together (one patch).** #46: stop attaching the full `(m/explain attr val)` to the thrown ex-info (keep a one-line `me/humanize` in `:seon.error/message`), or have `error/->map` omit `:seon.error/ex-data` when `:seon.error/data` already carries it — touches only the throw sites `internal.cljs:679-708` (+ optionally `error.cljs:64-66`). #48: coerce symbol→keyword **only** for `:seon.ns/name`-class idents at the validation gate (keep keyword as the stored canonical so lookup-refs `ctx.cljs:1192` still resolve), OR steer agents to the automatic `defn`-tee in the prompt. **Pair them:** #46 makes #48's keyword-mismatch failure self-explaining instead of a 3596-char wall. Both off the wake/eval hot path; low blast radius.
- **#54 Fix 3 + Fix 2 (event-loop yield).** Fix 3: swap `seon.agent.fs` to `fs.promises.readFile`/`list-dir` (eval already auto-awaits returned promises) — effort S, risk LOW, do first. Fix 2: `js/setImmediate` yield between top-level forms in the eval-batch loop — additive yield point, not a logic change; verify it doesn't reorder `*print-fn*`/tee timing — effort S, risk LOW-MEDIUM. Together they cover file-read + multi-form turns, the common starvation triggers. (Fix 1 → Tier D.)

### Tier C — Do with care (hot path / gated; must ship behind a falsification check)

These touch the eval loop, wake/intake, write path, or boot. Build per the existing spec, rebase on 304ef96, and gate each on a live drive (ideally an acme-harness acceptance check).

- **#50 (do FIRST in the reliability batch).** Highest leverage in the whole list — it manufactures #47/#43 self-wakes. Substrate-side advisory-scoring change (skip-don't-score unparseable prose; in-turn errors advisory, not turn-fail). Edits `eval-batch!` scoring + `agent.cljs` scoring + `ctx.cljs:format-eval-row` — all hot path. Single bounded live drive to validate. NOTE an adjacent partial mitigation already landed (`550e70b` ground-before-reply + system-text nudges) but that is prompt+render, NOT the scoring change #50 requires.
- **#49 (after #43, which is already shipped).** Narrow the `!kick-scheduled` latch to first `:running` + clear-then-drain-to-empty; add the post-loop drain. Touches the wake go-block (hottest concurrency path) — HIGH risk if done loosely. Re-uses #43's origin predicates. Live-verify the "second `/chat` in the tail window" case.
- **#47** — execute INSIDE the #50 batch (advisory scoring + #43 halt). Do NOT patch standalone; it touches the same wake/halt path.
- **#54 Fix 1** appears here only to say: NOT now (see Tier D).
- **#38** — collapse the inventory `register!` boilerplate to a compact `attr→type` line list per ns (keep full forms only for the agent's CURRENT ns). Localized to `compact-ns-source`/`schema-full-source` (`namespaces.cljs:182-246`), small blast radius — but schemas double as the type contract the elided fn signatures reference, so verify agents can still construct valid tx-data. ~10–15K tokens/turn plausible saving.
- **#39** — cheapest honest answer: relay the existing lever (give a static downstream section `:seon.ctx/priority < 20` and it joins the cacheable prefix today) AND/OR replace the hardcoded `(.indexOf names :namespaces)` with a `:seon.ctx/cacheable?` flag. The latter touches `assemble-context`'s boundary computation (`ctx.cljs:1878-1892`) on the prompt hot path — a cacheable section MUST render byte-identically every turn or it poisons the whole cached prefix; the mechanism must NOT let a volatile section opt in. Low-risk variant: document the priority<20 trick + add a guard.
- **#45** — coordinate with the acme-harness BUG-A/B work (same "did the requested effect happen?" post-turn detection shape). Would touch `agent.cljs run-turn!` (hot path). See Sequencing — largely SUBSUMED.
- **#27** — build the additive **noop** seam (`fire-on-reply-hooks!` at `agent.cljs:1066` success branch, fail-soft, late-resolved via `seval/lookup-value`) per the overridable-substrate PRD; the panel is a reactive section-fn add. Low-risk (a noop the compiled core always calls). Sequence UNDER the artifact/extension design so the consumer has a recorded home (#28) to register from.
- **#34** — defer to the artifact-packaging design rather than a standalone patch; lazy-require + flag-gating the eval/bootstrap path touches the agent boot/eval hot path and must be co-designed with the single-file build target.

### Tier D — Defer / risky vs current plans

- **#52** — DEFERRED by `reliability-fixes-49-53` §3 itself. Lowest severity (subtle correctness, not a crash); the #44 render-scrub + #50 advisory scoring are the net. Optional one-line eval-dispatch skip if cheap. Revisit only if it surfaces in a demo.
- **#44** — the structural "un-forgeable `=>`" mechanism is NOT built; only the after-the-fact text scrub ships. High demo-risk but L effort and HIGH stability risk (touches `eval.cljs` result rendering + ctx scoring). Sequence behind #50 — #50's advisory scoring + the #44 scrub blunt the worst of it; a true reserved channel is a separate, larger design decision.
- **#54 Fix 1 (worker_thread)** — re-architects the eval loop (node-worker shadow target + DB conn proxy via `postMessage`; the whole `AsyncLocalStorage` `*conn*` binding + detect-and-tee + stash-result lives on the main thread today). Effort L, risk HIGH. DEFER as a follow-on prototype; do NOT ship for the demo. Fix 3 + Fix 2 cover the common triggers.
- **#28** — converge with the artifact/extension design rather than building `SEON_SEED_DIR` standalone (avoid two homes for consumer code). Touches the boot/replay spine (`start-agent!`/`replay-program-graph!` `client.cljs:1994`) — medium risk, L effort. DECIDE the model first (see Needs-decision).

### Needs user decision (blocked on a call, not on build capacity)

- **#51** — fix (A) full gate-removal + advisory render + retire `:force` (recommended, reactive-aligned) vs (B) narrow surviving gate. Hinges on whether a real false-user-claim incident ever existed. Mechanically small once decided.
- **#53** — one 16384 cap vs two-tier (16384/50000); total-context budget. Small + low-risk once the cap policy is picked.
- **#44** — accept render-scrub-only for the demo, or commit to the larger un-forgeable-`=>` channel?
- **#28 vs #34/#27** — pick ONE home for consumer code: the recorded-corpus seed dir, or the artifact-design's extension model. Build neither until decided.

---

## 3. Sequencing recommendation (respects current plans)

The two strategic tracks in flight are: **(i) the acme-harness + BUG A/B fixes** (SCI live-tile bounding for unspecced helpers; downstream source silently un-indexed when the preload omits `reset!`), landing NOW on the eval/render path; and **(ii) the embeddings branch + artifact/extension packaging design** (`seon-as-artifact-design-2026-06-22.md`), the strategic direction.

**Order the green-lit work so it does not collide with either track:**

1. **Relay Tier A immediately** (#40/#41/#42/#43/#37/#36/#32/#16). Zero code; pure communication + a HEAD re-confirm. Unblocks the consumer's stale workarounds today.
2. **Land BUG A/B (in flight) first on the render/index path.** Do NOT start #45/#38/#39/#50 eval-loop or render edits until BUG A/B is committed — they share `live_tile.cljs`/`eval.cljs`/`agent.cljs`.
3. **Then the two contained patches in parallel** (off the hot path, safe alongside BUG A/B): **#46 + #48** (one patch) and **#54 Fix 3 + Fix 2**. Neither touches the wake go-block or eval scoring.
4. **Then the #49–53 reliability batch in its designated order**, AFTER BUG A/B and AFTER the now-shipped #43: **#50 → #49 → #51 → #53 → #52(deferred)**. This is the spec's order (#50 is root + first). One bounded live drive per leg. Do this as a focused batch — do NOT interleave it with render/inventory edits.
5. **Route the design asks through the artifact PRD, not standalone:** #34 (single-file), #27 (reply hook), #28 (`my.*` home). Resolve the #28-vs-extension-model decision before building any of them. #27's noop seam can land early (it's additive) once a recorded home exists.

**Subsumed by in-flight work (do NOT build standalone):**

- **#35 and #45** → the acme-harness BUG-A/B "silent drop" detection (calm placeholder already shipped for #35; #45's dropped-render detector is the same post-turn "did the effect happen?" shape).
- **#34, #27, #28** → the artifact-packaging + extension-without-fork design (`seon-as-artifact-design-2026-06-22.md`) — single-file release, reply hook delivery, and the `my.*` recorded home are three faces of that one design.
- **#36** → resolved by Phase-2 P2-B on the embeddings branch (lazy/key-optional Gemini + `SEON_EMBED` gate); confirmed against `embed.clj` HEAD.

---

## 4. Stability guardrails

**Load-bearing subsystems right now (touch only behind a falsification check):**

- **Eval loop** — `eval.cljs` `eval-batch!`/`read-all-forms`, the detect-and-tee, `stash-result`, the `AsyncLocalStorage` `*conn*` binding. (#50, #44, #54 Fix 1/Fix 2, #38-adjacent.)
- **Wake / intake** — `agent.cljs` run loop, `inbound-msg-datom?`, `replied-since-inbound?`, the `!kick-scheduled` latch, the go-block. (#43-already-shipped, #47, #49, #50.)
- **`transact!` / the write path** — `db/internal.cljs` validation gate + success/failure envelopes; `wire.clj augment-tx`. (#40-shipped, #46, #36-shipped.)
- **wire-server boot** — `boot.clj` requires + `embed.clj` lazy client; `bin/seon prep` classpath warm. (#36-shipped, #37-shipped.)
- **Render path** — `render.cljs` + `live_tile.cljs` (SCI bounding in flight via BUG A). (#42-shipped, #45, #35.)

**The rule:** any ask that edits one of the above ships behind a falsification check — ideally an **acme-harness acceptance check** that drives the live behavior and proves the intended effect happened (e.g. "second `/chat` in the tail window wakes a turn" for #49; "unparseable prose is advisory, not a turn-fail" for #50; "a key-unset `:writer` boots and makes zero outbound embed calls" for #36's re-confirm). Tests passing is necessary but not sufficient — every hot-path change carries a one-line live proof. Off-hot-path patches (#46/#48 envelope shaping, #54 Fix 3 fs I/O) still get a targeted check but do not require a full live drive.
