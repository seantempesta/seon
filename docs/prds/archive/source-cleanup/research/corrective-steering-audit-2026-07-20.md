---
type: research
status: complete
tags: [research, agent, architecture, capability]
---

# Corrective-steering audit (2026-07-20)

Design audit of the owner principle: the runtime always corrects and steers
the agent so its next forms are more correct. When the runtime fixes or
completes an agent action (auto-await, source repair, coercion), the
persisted record and the transcript render must read as if the agent did the
right thing — the corrected form as the record, the resolved value as the
result. Ground truth stays safe (reply blobs are byte-exact); the transcript
is the derived render.

Two hard constraints govern every proposed fix:

- **Single execution.** Correction is a persist-time rewrite of the saved
  source/narration/result record (the `augment-ns-source` pattern) or a pure
  render-time projection of stored bytes — never a second evaluation of the
  form. The dev-MCP bridge's re-eval-on-await-assert trick
  (`script/seon/dev/mcp.clj:954-1081`, "an await-assert compile failure
  re-evals the SAME code inside one wrapper") is a dev-REPL transport
  workaround and is explicitly NOT the agent-path pattern.
- **Byte identity as clips age.** A transcript eval row must render
  byte-identical within its age band (prompt-cache law), so corrections must
  be deterministic at FIRST render and never retroactive.

## 1. Traced inventory — where steering already holds

### 1.1 Promise auto-await (the owner's motivating example) — CONFORMS

- `src/seon/eval.cljs:1969` `maybe-await-value` — the form evals ONCE
  (`eval-form-entry!` `src/seon/eval.cljs:4574-4583`); if the value is a
  Promise it is awaited to data. The eval record's
  `:seon.eval/result-edn` stores the RESOLVED value via `render-result-edn`
  (`src/seon/eval.cljs:3139`) — never `#object[Promise]`, never an await
  wrapper.
- The saved `:seon.eval/source` is the form exactly as the agent wrote it,
  with NO `await`. In this REPL that IS the taught idiom — agents never
  write `await` (`maybe-await-value` docstring: "Agents don't write `await`
  … makes calls to seon.db/* feel synchronous"). So the persisted record
  already reads as "the agent did the right thing": working form, resolved
  value. No source rewrite is needed on this path; the owner's example is
  satisfied by the existing record shape.
- Timeout / explicit `(defer …)`: the record stores the clean
  `pending-placeholder` data value (`src/seon/eval.cljs:207-215`) — "still
  running — re-reference `result/<id>` in a later eval to await its value" —
  and the live Promise binds at `result/<id>`
  (`src/seon/eval.cljs:4754-4765`, `replace-live-result!` swaps in the
  resolved value when it settles). Steering text names the exact next form.
  The stored placeholder never mutates after resolution — correct under the
  byte-identity law; the steering is "re-reference", which is exactly the
  single-execution recovery.
- Rejection: recorded as a `:seon/error` value (`src/seon/eval.cljs:2008-
  2017`), fault-classified, never a throw into the loop.

### 1.2 Form corrections — the corrected form is uniformly what persists

All three correction owners rewrite the source BEFORE the one execution, so
the fixed source is what evals, records, AND tees — and the transcript shows
the corrected form with a transparency note in the narration preamble:

- **`augment-ns-source`** (`src/seon/eval.cljs:3462`, applied at
  `eval-form-entry!` `src/seon/eval.cljs:4462-4482`): a new authored
  `(ns …)` gets the real `(:require …)` written in; `:seon.ns/source`
  stores the augmented form; narration gains "; added (:require …) for …".
- **Preflight symbol repair** (`src/seon/eval.cljs:4511-4531`): a
  compile-proven unique near-miss fix is applied pre-execution — "the real
  eval below runs the FIXED source as the form's FIRST run — side effects
  can never double-fire. The fixed source is what evals, records, AND
  tees". Narration carries the `↻ fixed:` note (`repair/fix-note`);
  queryable fix datoms ride a separate tx (`src/seon/eval.cljs:4770-4779`).
  Trials are compile-only and phantom analyzer defs are removed
  (`src/seon/eval.cljs:4373-4392`) — no execution ever double-fires.
- **Parinfer read-repair (A.2)** (`src/seon/eval.cljs:5417-5490`): a bad
  span is repaired, re-parsed, and dispatched through the SAME
  `dispatch-eval-entry!` path; the repair note (diff + structural shape via
  `form-shape`, `src/seon/eval.cljs:2408`) rides the first entry's
  narration. Heredoc payloads are refused repair so their errors surface
  intact (`src/seon/eval.cljs:5436-5445`).

`format-eval-row` (`src/seon/agent/ctx.cljs:513`) renders "the form
verbatim (or the parinfer-repaired source)" — i.e. the STORED (corrected)
`:seon.eval/source`. **The `augment-ns-source` pattern is already the
uniform pattern for every correction that exists today.** The raw mistake
survives only in the reply blob (byte ground truth,
`src/seon/agent/turn.cljs:563-568`), never in the rendered transcript.

### 1.3 Error steering that already meets the bar

- Refused/ambiguous repair → did-you-mean candidates appended to the
  failing eval's error (`src/seon/eval.cljs:4614-4635`).
- Failed-def false-confidence guard (`src/seon/eval.cljs:4489-4510`):
  "`x` does not exist — the def that would create it failed … Fix and
  re-eval the def first, then re-run this form."
- `read-error-message` (`src/seon/eval.cljs:2425-2504`): caret-underlined
  excerpt, "DEFINED NOTHING", and the exact next action; the EOF/truncation
  case steers to the store-data-send-pointer idiom (blob chunks +
  `my.blob/concat!`).
- `render-error-string` (`src/seon/eval.cljs:3031-3065`): genuine runtime
  throws get the "errors are values — read it and adapt" framing; known
  agent-fault kinds stand on their self-contained thrower message.
- Execution-child interruption (`src/seon/agent/ctx.cljs:642-659`):
  explains exactly what was lost, what survives, and what recovery does.
- Clip guides: `cap-result-body` (`src/seon/agent/ctx.cljs:396-431`) and
  `clip-result-body` (`src/seon/eval.cljs:3067-3096`) teach `result/<id>`
  drilling and how to get less next time (aggregate / tighter `:where` /
  fewer attrs).
- `scratch-def-note` (`src/seon/eval.cljs:2380-2406`): a bare `(def …)`
  success gets a derived "won't persist … write `(defn …)` / store with
  `db/transact!`" line — pure, recomputed each render from stored source.
- Legacy-row sanitizer `sanitize-result-edn` (`src/seon/eval.cljs:3108`):
  read-side reprojection of raw `#datahike/DB` dumps — deterministic over
  the stored string.

## 2. Gap table

| # | Gap | Evidence | Proposed fix (owning mechanism; persist- or render-time, single execution) |
|---|---|---|---|
| G1 | db errors returned as VALUES render as a raw error map, and several thrower messages state the rule without the working form | `transact!` catches into `error/->map` (`src/seon/db.cljs:918-922`); the eval then SUCCEEDS with the error map as its value, rendered by `render-ai` — no `⟹ ✗` failure shape, no framing. Messages like "Only keyword Malli enums are storable." / "Transaction data names unregistered attributes." (`src/seon/db/internal.cljs:138,151,176,183,290,306,320,404`) name the defect but not the corrected call | Strengthen the thrower messages in `seon.db.internal` in place (per the standing clear-directive-errors rule): each message computes the corrected shape from the offending input — e.g. name the unregistered attrs inline and show the `schema/register!` form to run first; for `:maybe`, show the same map with the key omitted. Persist-time (the message IS the stored value). No render-layer rewriting |
| G2 | `schema/register!` accepts banned shapes (`[:maybe :int]`) and the failure surfaces later at transact time — steering displaced from the causal form | `register!` (`src/seon/schema.cljc`) performs no banned-shape validation; rejection happens only in `form->datahike-value-type` at transact (`src/seon/db/internal.cljs:151`). Standing owner rule: "NEVER return ok when input is wrong (register! accepted banned `[:maybe :int]`)" | Strict validation inside `register!` itself: reject at the registration form with an error value showing the corrected registration ("omit the key when the value is absent; register `:int`"). One mechanism — the validation predicate shared with the db bridge so the two ends cannot drift |
| G3 | `:stream` dropped-tail invisibility: everything after the first complete form is silently treated as prose; the transcript shows one form + result and the agent never learns its remaining forms did not run | `reply-program` (`src/seon/agent/turn.cljs:538-555`) truncates at the first `:form`; the comment at `turn.cljs:590-596` says the tail "stays byte-intact in the reply blob, it just never evals" — but nothing in the rendered context says so | Persist-time: when stream truncation drops ≥1 parsed tail entry, append a deterministic narration line to the ONE recorded eval ("stream mode runs one form per turn; N further forms in this reply were not executed — re-send the next one") at record time in `ask-and-eval-reply!`/`eval-parsed!`. Frozen into the row's narration → byte-stable forever. Never a marker layer, never reply rewriting |
| G4 | Silent argument coercion teaches non-canonical calling shapes | `transact!` runs `coerce-identity-symbol-idents` / `normalize-entity-ref-keys` (`src/seon/db.cljs:906-908`) with no trace; the transcript shows the agent's original shape succeeding | Owner decision needed per coercion: (a) contract — the shape is documented as accepted, nothing to do; or (b) correction — follow the preflight-repair pattern: the persisted source/record shows the canonical shape with a narration note. Do NOT bolt a render-layer annotator on. Recommend (a) for these two (they are documented normalization), with the general rule recorded in `toolkit.md`: any NEW coercion must either be contract-documented or persist the canonical form |
| G5 | Promise-rejection messages can be raw host errors ("fetch failed") with only generic framing | `maybe-await-value` catch (`src/seon/eval.cljs:2008-2017`) → `error/->map` → `render-error-string` adds only the errors-are-values line for unknown kinds | Low priority. Strengthen at the fault-recording boundary for the few known host shapes (fetch/transport) with a computed next-step (retry with `budget`, or `defer` + re-reference). In `seon.error`/adapter catch sites, not in render |
| G6 | `format-eval-row` dead-end branch renders `⟹ ✗ <no result>` with zero steering | `src/seon/agent/ctx.cljs:721` | Minor: make the branch name the eval id and the honest state ("no result recorded for this form — the eval record is incomplete; re-run the form"). Pure render change, deterministic |
| G7 | Pending placeholder never updates after resolution | `pending-placeholder` stored at record time; `replace-live-result!` updates only the live var (`src/seon/eval.cljs:4757-4765`) | NOT a defect — retroactive rewrite would violate byte identity. The steering ("re-reference `result/<id>`") is already the correct single-execution recovery. Record here so nobody "fixes" it |

Also audited and found conforming: capability refusals in `seon.agent.fs`
(locked `configure!` is a legible no-op error value directing to `grants`,
`src/seon/agent/fs.cljs:280-291`); `my.data` envelopes carry the underlying
db message ("rows query failed: …", `src/my/data.cljs:64-71`) — their
quality inherits from G1's thrower messages, so G1 fixes them transitively.

## 3. Byte-identity analysis

The proposed corrections all satisfy the first-render determinism law:

- **Persist-time corrections** (G1 thrower messages, G2 register!
  validation, G3 stream-tail narration) freeze into the stored
  `:seon.eval/*` row before the first render. Every later render reads the
  same bytes.
- **Render-time steering** is only ever a PURE function of stored bytes:
  `format-eval-row` (`src/seon/agent/ctx.cljs:513-751`) reads the row plus
  values THREADED by the transcript converter — the age-decayed
  `:seon.render/result-body-cap` and the `escape-clipping?` override are
  computed once off the converter's render db and stamped on the row
  (`ctx.cljs:578-588`), so an as-of render never reads the live conn.
  `scratch-def-note` and `sanitize-result-edn` are pure over stored source
  / result strings.
- **Age banding** (`src/seon/agent/ctx/transcript.cljs:121-210`) is
  tier-driven and byte-stable within a band ("an eval's fate changes only
  when it crosses a turn-offset boundary, not every turn" — the #62
  discipline). A correction that lives in stored source/narration renders
  identically in every band that includes the component.
- The ONLY safe pattern for future corrective features is therefore:
  rewrite at the eval/persist owner (`eval-form-entry!` / `record-eval!`)
  or project purely at the render owner (`format-eval-row`) — never a
  post-hoc row mutation, never a render that consults live state, and
  never a second execution. `sanitize-result-edn` is the precedent for a
  read-side net when historical rows predate a write-side fix.

## 4. Single-execution audit

No agent-path re-eval exists today: preflight trials are compile-only with
phantom-def rollback (`src/seon/eval.cljs:4373-4392`); parinfer repair
happens before the one eval; `maybe-await-value` awaits the already-running
Promise produced by the single eval; deferred/timeout Promises resolve via
the live handle, and a later `result/<id>` re-reference is a NEW form the
agent chose, not a runtime replay. The one re-eval-shaped mechanism in the
tree — `script/seon/dev/mcp.clj:954-1081` (wrap-and-re-eval on the
top-level-await assert) — is a dev-REPL transport workaround for human
operators and must never migrate into the agent path; any future "the form
should have awaited" correction is a persist-time rewrite of the saved
source paired with the value the single execution already produced.

## 5. Proposed ordering

1. **Rides stage 1.5 / data-browser (small, independent):** G6 render
   branch; G3 stream-tail narration (touches only the record path;
   `:stream` work is already active on the repl milestone).
2. **Dedicated small unit — "directive error text":** G1 + G2 together
   (they share the banned-shape predicate and the message-construction
   sites in `seon.db.internal` + `seon.schema`); acceptance = each sampled
   envelope names the corrected next form computed from the offending
   input, `bin/test-cljs` message-behavior tests assert structure not
   exact prose.
3. **Owner decision, then doc-only or per-site:** G4 coercion policy
   (recommend contract-documenting the two existing normalizations and
   adding the rule to `toolkit.md`).
4. **Backlog:** G5 host-error steering at fault-recording boundaries.
