---
type: research
status: active
tags: [agent, flow, render, web]
---

# Error-detection + clean-display infrastructure (2026-06-28)

## TL;DR

The owner directive: "We should NEVER generate a wall of errors. Build a
mechanism that DETECTS it, gives a CLEAN error, and we are NOTIFIED of it."
Two parts, both grounded in the live pod (7890) and real recorded data
(agents `root`, `Rtd-2606281344`, `Bgl-2606281423`).

- **Part 1 — clean display (BUILT, render lane).** The transcript now (a)
  drops content-free segmentation artifacts (empty / closing-delimiter-only
  evals — a mis-split `}` that parsed to NOTHING) and (b) coalesces a run of
  consecutive same-signature error evals into ONE honest line. Live-proven
  on `Bgl`: its transcript went from **14 `✗ READ ERROR` rows → 3** (the 3
  kept are real-source failures the agent should see), **11 orphan-delimiter
  rows gone**, **1 run coalesced**, **~31.0k → ~26.2k chars** (~1.2k tokens).

- **Part 2 — detect + notify (query + human surface BUILT; agent-facing
  warn-check DESIGNED + flagged for Core).** A DERIVED `error-storm` signal
  (`seon.derive/error-storms`) flags an agent thrashing on broken evals — a
  pure query of the recent eval log, no stored counter, so it vanishes when
  the agent recovers. Surfaced to the HUMAN in the global header
  (`seon.ui.header`). The ROOT/agent-facing surface is a `seon.warn` check —
  designed below, flagged for the Core lane.

## The real wall shape (a correction to the original drive doc)

The drive doc framed the noise as "a WALL of repeated `✗ READ ERROR`". Read
literally as *consecutive identical* errors, that does NOT match the live
data: on `root` and `Bgl` the orphan-delimiter errors are interleaved **1:1
with real successful forms** (each multi-form block the model emits has its
trailing `}` mis-split into its own failing "form" sitting between two good
forms). There is no consecutive run of identical errors to coalesce.

So the wall is fixed in two complementary ways, not one:

1. **Drop the content-free orphans** (the dominant fix — 11/11 of `Bgl`'s
   orphan READ-ERRORs). This is what actually cleans the live transcript.
2. **Coalesce genuine consecutive same-error runs** (the owner's literal
   ask) — the right tool for the *other* wall shape: an agent re-emitting
   the same broken form N times in a row. Fired once on `Bgl` (a 3× "not
   defined" run).

Both live in the render lane as pure derivations; the durable complement —
never *recording* the orphan/empty segments — is the Core lane (already
flagged in the drive doc) and is independent of this work.

## Part 1 — what was built (render lane)

`src/seon/agent/ctx/transcript.cljs` — one `coalesce-events` pass applied by
BOTH twins (`transcript-block` :ai, `transcript-block-html` :html):

- `noise-eval?` — an eval whose source is empty or only closing delimiters
  (`}`, `]`, `)}`) AND either it failed to read (`ok? false`; its narration
  is the model's mis-attributed `=>` prose, not intent) or it has no
  narration. Dropped outright. A comment-only row (blank source, real `;`
  narration, no read error) is preserved.
- `error-signature` — normalizes a failed eval's error to a CLASS (strips
  `[line …]`, `… at line N`, the offending token, backtick'd symbols) so
  `Unmatched delimiter: }` and `: ]` share one class.
- `coalesce-events` — drops noise, then collapses maximal runs of
  `≥ coalesce-min-run` (3) consecutive same-signature errors into one
  `::coalesced` event.
- `:ai` render (`coalesced->renderable`): a single flat, eval'able `;`
  comment — `;=> ✗ 10× Unmatched delimiter — 10 consecutive failures
  collapsed; each DEFINED NOTHING. Fix the form once, not 10 times.`
- `:html` render (`coalesced-card-html`): a collapsed `✗ N× <class>`
  `<details>` summary expanding to the individual eval cards. The `<summary>`
  is intentionally NOT a flex container (a flex summary hides the native ▾
  disclosure marker — the known gotcha).

### Before / after (pasted bytes, live `Bgl-2606281423`)

ONE orphan eval, rendered into the `:ai` wall ×11 BEFORE:

```
}

;=> ✗ READ ERROR — this form did not parse, so it DEFINED NOTHING.
; Unmatched delimiter: } at line 1, col 1:
; }
; ^
; Do NOT call or wire anything that depended on this form — it does not exist. Fix the delimiter and re-eval the whole form.
```

AFTER: `noise-eval?` drops it → the row renders blank and is pulled by the
existing `(remove str/blank?)`. 66 lines of pure noise (6 × 11) gone.

A genuine consecutive run collapses to ONE line:

```
;=> ✗ 3× `…` is not defined — you have not defined it (or its defn failed earlier, or it's a typo). This form ran NOTHING. Define it first, then this runs. — 3 consecutive failures collapsed; each DEFINED NOTHING. Fix the form once, not 3 times.
```

Tally (live): `:ai` transcript `✗ READ ERROR` rows 14 → 3; orphan-delimiter
rows 11 → 0; coalesced runs 1; chars 31040 → 26248. The 3 surviving READ
ERRORs are REAL-source failures (`` `:db/ident` approach returned … `` prose-
as-code, a `(message/user …)` with stray chars) — exactly the feedback the
agent must keep.

## Part 2 — detection design + what was built

### The derived signal (BUILT) — `seon.derive`

`error-storm` / `error-storms` are pure reads of the recent eval log, no
stored state — reactive-context: a storm clears the instant the agent's evals
recover (the window slides past the failures). CONTENT-FREE noise evals are
excluded (a mis-split `}` is not thrash).

An agent is storming when EITHER:

- its last `error-storm-consec` (4) REAL evals ALL failed (stuck repeating a
  broken form), OR
- among its last `error-storm-window` (8) REAL evals, MORE THAN HALF failed
  and `≥ error-storm-min-fail` (4) absolute.

Live proof of correctness (no false positives, fires on real thrash, self-
heals) — replaying `Bgl`'s actual `ok?` history through the rule with an
8-wide sliding window:

- Healthy fleet NOW: `(error-storms db)` → `[]`; per-agent `error-storm` →
  `nil` for root / Rtd / Bgl (all recovered).
- `Bgl`'s swamp (turns 2–7 in the drive): windows 3–11 → `storm? true`
  (failed 4–8 / 8, up to 8 consecutive). Recovery: window 12 onward →
  `false`. The clean finish stays `false`. The signal tracks the real thrash
  and vanishes on recovery — exactly the reactive behavior wanted.

### Human surface (BUILT) — `seon.ui.header`

`storms-chunk` renders in the global status bar (every page) only when a
storm exists (renders `nil`, including its own divider, when healthy). Each
storming agent is a `⚠ <id> erroring` link to `/agent/{id}` with a
`6/8 recent evals failed · 5 in a row`-style tooltip. Live-rendered with a
fabricated storm to confirm the hiccup; renders nil for the healthy fleet.

### Agent-facing surface (DESIGNED — FLAGGED FOR CORE, `seon.warn`)

The agent/root notification is a new runtime check in `seon.warn`, reusing
the SAME `seon.derive/error-storms` query (one rule, two surfaces — no
re-derivation). It is GLOBAL (cross-agent, like the other runtime checks),
so root sees any agent storming in its warnings/fleet block. Drop-in:

```clojure
;; add to the (:require …) — derive is a leaf, no cycle
[seon.derive :as derive]

(defn check-error-storm
  "Agents thrashing on broken evals RIGHT NOW — an anomalous recent
   eval-failure rate (seon.derive/error-storms). DERIVED at render; clears
   the moment the agent's evals recover. GLOBAL — :seon.warn/ns ignored."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db]}]
  {:seon.warn/kind :error-storm
   :seon.warn/urgent? true
   :seon.warn/affected
   (->> (derive/error-storms db)
        (mapv (fn [{:seon.agent/keys [id] :seon.derive/keys [failed window consec]}]
                {:seon.warn/sym   id
                 :seon.warn/where (str failed "/" window " recent evals failed"
                                       (when (>= consec 2) (str ", " consec " in a row")))})))
   :seon.warn/explain
   (str "An agent is THRASHING: most of its recent evals are failing. Stop "
        "retrying blind — errors are values: read the failed evals above "
        "((result <eval-id>) holds the full error), fix the ROOT cause once, "
        "then proceed. If it is YOU, slow down and re-read your last error "
        "before the next form. If it is another agent, it may need help.")
   :seon.warn/example "(result :<eval-id>)  ; read the actual error, then fix the cause"})
```

…then add `check-error-storm` to the `checks` registry vector (near
`check-failed-evals`, before `check-tile-unresolved`). The render path
(`render-warnings`) already handles `:urgent?` clusters (rendered first,
louder) and the per-check throw isolation, so nothing else is needed.

Why DESIGN-not-build here: per the task split, the agent-facing warning is
Core-lane; this lane owns the derived query (the shared mechanism) + the
human header. The query is built and live-proven, so the Core add is a
3-line registry change + the fn above.

## Files

- `src/seon/agent/ctx/transcript.cljs` — `noise-eval?`, `error-signature`,
  `coalesce-events`, `coalesced->renderable`, `coalesced-card-html`; wired
  into both transcript twins. (BUILT)
- `src/seon/derive.cljs` — `error-storm`, `error-storms`,
  `:seon.derive/error-storm` schema, thresholds. (BUILT)
- `src/seon/ui/header.cljs` — `storms-chunk` + wiring. (BUILT)
- `src/seon/warn.cljs` — `check-error-storm` above. (FLAGGED — Core)

## Notes / smells

- `seon.ui.header/system-header` now runs one more full-fleet derived scan
  per render (`error-storms` queries each agent's evals). Consistent with the
  header's existing per-render scans (`fleet-summary`, `throughput`,
  `store-inventory`); sub-ms at current datom counts. If the fleet/eval log
  grows, memoize on the db basis-t (perf escape hatch), do not bifurcate.
- Coalescing a run of distinct-symbol "not defined" errors into one class
  loses which symbols in the `:ai` summary (the html `<details>` keeps them).
  Acceptable: a 3+ consecutive run of the same error class is thrash; the fix
  is the same for all of them ("define it first").
- The orphan-delimiter root cause is segmentation (Core): the form segmenter
  emits `}\n` / `]\n` / `""` as standalone forms. Render-lane dropping makes
  the EXISTING recorded data clean now; Core dropping them pre-record is the
  durable fix and removes them from the store entirely.
</content>
</invoke>
