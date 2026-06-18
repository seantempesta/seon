---
type: prd
status: active
tags: [prd, agent, eval]
---

# Robust Multi-Form Eval + First-Class Debug Capture

## TL;DR

Multi-form eval is the heart of Seon: every reply from the LLM is parsed into
top-level forms, each form is its own eval, and form N+1 must run even if N
fails. The `ari-2606180804` episode (2026-06-18, 24 turns / 75 evals, looped
08:06-08:11; evidence in `tmp/2026-06-18-highscores-episode.txt` and
`tmp/2026-06-18-raw-agent-output.txt`) turned a single mis-delimited `(defn …)`
into a 24-turn doom loop. This PRD makes the eval path robust and adds
first-class, replayable debug capture so we never reconstruct a failing episode
from self-messages again.

Three parts of work, plus a comprehensive test plan, all **in-place edits — no
v2 namespaces, no parallel mechanisms**:

- **Part A — Robust multi-form eval (the heart).** (A.1) Fix `parse-forms` so
  prose narration is never recorded as a failed eval, with a precise
  opener-at-START classification rule so narration that quotes code inline
  ("I'll use `(subs …)` to format the time.") is NOT misread as broken code.
  (A.2) Integrate parinfer **indent-mode repair, per-form, ON BY DEFAULT**: a
  read failure is repaired and the repaired form **auto-evals**, always
  surfacing the repaired source + a structural-shape note. (A.3) Sharpen every
  per-form error to name the offending closer + line:col + the source line with
  a caret + an honest "this form defined nothing" instruction. (A.4) Stop the
  false-confidence trap where a reference to a **failed-def** var returns
  `nil`/`ok? true` — gated behind a live falsification.
- **Part B — Debug capture.** A flag (`SEON_DEBUG_CAPTURE`, OFF by default)
  folds the already-shipped always-on `persist-prompt!` under one knob and adds
  the missing pieces: per turn, at the `run-turn!`/`ask-and-eval!` boundary
  (where prompt and raw response are both in hand), write the verbatim input
  prompt AND the verbatim raw LLM response, keyed by `agent-id` + `turn-idx` +
  `turn-id`, project-local under `logs/turns/…`. EDN for the structured
  request/response (round-trips into fixtures) + a human-readable `.txt`.
- **Part C — Errors vs warnings.** Eval failures stay ERRORS. The already-shipped
  `check-tile-unresolved` warning becomes URGENT (rendered first, louder).
  **There is NO wire-time guard** — the broken-tile condition is already a pure,
  derived, self-healing query over the existing stored pointer; the real fix is
  the parse experience (Part A). No new stored attribute.
- **Part D — Comprehensive test plan.** Per-scenario coverage of every failure
  mode and new behavior, run via `bin/test-cljs`, with a parallel-safe file
  partition so multiple agents can implement without conflict.

### The four real failure modes (cited, real forms only)

| # | Mode | Evidence (real forms) | Root cause |
|---|------|-----------------------|------------|
| 1 | **Delimiter mismatch** (dominant) | High-scores form `(str "generated.md · " total-count " rows · :verified · " (js/Date.)]]}` — `(str` opened, never closed before `]]}` arrives → "Unmatched delimiter: ] [at line 25, column 76]" (`Hyq` ep. line 366, then `zCY`/`GkC`/`wvm` etc. — failed 12×). Start-screen form: outer `[:div` missing its `]`, `:seon.render/ai` dedented to map-key level → "Unmatched delimiter: } [at line 34, column 91]" (`Usd` ep. lines 47-80). | `parse-forms` records the whole bad span as one `:kind :read :ok? false`; no repair attempted (internal.cljc:42 docstring: "We do NOT auto-fix missing parens"). |
| 2 | **Prose tokenization noise** | `"80s arcade/start screen."` → "Invalid number: 80s." (`FHb`, ep. line 5); `to:` → "Invalid symbol: to:." (`SpO`, ep. line 233); `detail:` → "Invalid symbol: detail:." (`ZyJ`, ep. line 262). | rewrite-clj's reader THROWS on these tokens inside `try-parse-one-token`, so they hit the `:error` branch and never reach `narration-atom?` (which only filters tokens that *read cleanly* as bare atoms); `find-recovery-point` then swallows the whole multi-line prose block into one `:read` failure. |
| 3 | **False confidence** | after `(defn my-kb-high-scores-tile …)` failed to parse, `(def tile-content (my-kb-high-scores-tile nil))` → undeclared-var (ok? false, `Vdx`/`wBm`/`oOe`), then `(get tile-content :seon.render/hiccup)` → **nil with ok? true** (`ELD`/`SIS`/`OqS`). | `(get <nil-or-unbound-var> :k)` is legal — returns nil, no warning. The agent read `ok? true` as "the tile is valid" and wired a fn that did not exist. The §A.4 fix must hook **failed-def provenance**, not the reference site (see A.4). |
| 4 | **Never consumed its own error** | re-emitted ~12 subtly-different broken variants; narration claims "the reply was REFUSED" (`Hyq` narration, ep. line 333). | NOTE: there is **no code refusal gate** — that text is the agent's own narration. The stop policy keeps looping. The fix is legibility + repair (Part A), not a gate. |

> **Correction to the original brief, load-bearing.** The input context IS
> already captured verbatim — `persist-prompt!` (agent.cljs:1125) writes the full
> assembled prompt to `logs/prompts/<agent-id>/<turn-id>.txt` on every turn,
> always-on (~447 MB live, unbounded — a latent disk bug). Genuinely missing:
> (a) the exact wire request payload, (b) any flag / output-location machinery,
> (c) the full raw response object (only visible `:text` survives, as a
> non-blank-gated self-message). Part B folds the always-on prompt capture under
> the flag and adds the missing pieces. **Turn→output linkage is FINE in current
> code** — every recent turn's assistant self-message is linked via
> `:seon.agent.turn/messages` (16/16 unique, verified live); the older
> "owning-turn empty / shared tid" reports were historical-episode-data
> artifacts. Debug capture is NOT a data-model repair.

---

## Part A — Robust multi-form eval

The pipeline today: LLM reply → `seon.repl.internal/parse-forms`
(internal.cljc, CLJC, pure rewrite-clj) → `seon.eval/eval-batch!`
(eval.cljs:1783) folds over the parsed entries, each form its own eval, ns
accumulator carried in a `volatile!`. **Form N+1 already runs if N fails** —
confirmed at eval.cljs:1855 `doseq` + the `:read` branch at 1867. The work is
making failures legible, repairable, and impossible to misread as success.

### A.1 — parse-forms: prose-vs-code classification (FM-2)

**Seam:** `seon.repl.internal/parse-forms` `:error` branch
(`src/seon/repl/internal.cljc:248-256`), plus the leaf `try-parse-one-token`
(internal.cljc:169-198) and `find-recovery-point` (internal.cljc:143-158).

**Root cause.** `narration-atom?` (internal.cljc:103-115) drops a token as prose
ONLY when it *parsed cleanly* into a bare atom. The dangerous prose tokens —
`80s`, `to:`, `detail:`, `v1.0` — make rewrite-clj's reader THROW before any
sexpr exists, so they reach the `:error` branch and `find-recovery-point`
swallows the whole multi-line prose block into one `:read` failure whose error
names a token buried lines up.

**The precise rule (new, in the `:error` branch).** Before recording a `:read`
failure, classify the failing span as **prose** vs **broken code**:

> A failing span is **prose** (→ drop as narration, do NOT record an eval) when
> BOTH hold:
>
> 1. the reader error message matches the prose-token signature —
>    `#"^Invalid (number|symbol|keyword|token)"` (the messages rewrite-clj emits
>    for `80s`, `to:`, `detail:`, `v1.0`; live evidence shows a trailing `.`,
>    e.g. "Invalid number: 80s." — the `^`-anchored prefix match still holds);
>    AND
> 2. the span (up to a narrowed recovery point) has **no collection opener
>    (`(` / `[` / `{`) at the START of its trimmed first line** (column-0-ish).
>
> Otherwise it is **broken code** → record a `:read` failure (and attempt
> repair, §A.2).

**Why opener-at-START, not opener-anywhere.** Real LLM narration quotes code
inline — episode line 84 reads "I'll just put `(subs (str (js/Date.)) 11 19)` to
get HH:MM:SS roughly" and line 40 "I'll use `(subs (str (js/Date.)) 11 19)` to
format the time." A span like that CONTAINS a `(` but is plainly prose. If the
rule checked for an opener *anywhere*, this narration would be misclassified as
broken code and recorded as a `:read` failure — the **inverse** of the bug we
are fixing, reintroducing prose-noise evals. Requiring the opener at the START
of the trimmed span makes "`(+ 1 3x)`" (a real broken form, error
`Invalid number: 3x`, opener `(` at start) correctly NOT prose, while
"I'll use `(subs …)`" (opener mid-sentence) correctly IS prose.

**Narrow the recovery point for prose.** Today `find-recovery-point` scans to the
next column-0 `\n` + `(`/`[`/`{`/`;`, swallowing whole paragraphs. For a
prose-classified span, recover at the **next newline** instead, so one stray
`80s` drops a single line and the NEXT line gets a fresh parse attempt — one bad
token can't poison many lines.

**Before/after.** Before: `"80s arcade/start screen."` → `:kind :read :ok? false`,
`:error "Invalid number: 80s."`, n-fail++, surfaced as a failed eval the agent
must explain. After: dropped as narration, carried into `pending-narration` for
the next real form (or discarded if no form follows). Zero failed evals from
prose.

**Cycle/ordering:** none. `seon.repl.internal` is CLJC, pure rewrite-clj, leaf —
JVM-testable. A pure addition.

### A.2 — Parinfer repair, PER FORM, ON BY DEFAULT (FM-1)

**Seam:** `eval-batch!`'s `:read`-failure branch (`src/seon/eval.cljs:1867-1880`).
Repair runs **on the bad span**, NOT the whole reply (whole-reply repair would
mangle the already-good forms around it).

**Decision (authoritative): repair is ON BY DEFAULT and AUTO-EVALS.** On a
`:read` failure, repair the span, and if the repaired source now reads, eval the
repaired form(s) through the normal path and record them as successful evals
**carrying the repaired source + a structural-shape note** so the diff is always
visible. Live-proven safe on the real forms (below). This is not behind a flag.

**Library.** `parinferish` 0.8.0 — pure CLJC (`parinferish/core.cljc`, requires
only `clojure.string`). **Verified on the pod `:repl` build** (the runs below
executed there). The JVM-only `src/seon/dev/repair.clj` is the pattern to mirror,
NOT to fork.

> **Classpath check (do this before Phase A.2 lands).** `parinferish/parinferish`
> is a top-level `deps.edn:52` dep, but shadow pulls deps via alias-scoped
> `:extra-deps` — the top-level entry does NOT automatically reach the shadow
> `:test`/`:node-test` classpath. The implementer MUST compile
> `(require '[parinferish.core])` in the shadow `:test` build first; if it fails,
> add `parinferish/parinferish` to the `:cljs`/`:test` `:extra-deps`. It is
> already present on `:repl` (verified).

**New ns (the one new file): `src/seon/repair.cljc`.** Port the repair logic from
`seon.dev.repair` to CLJC so both pod and JVM tests use one mechanism. Drop the
JVM-only cljfmt step (the value is repair, not formatting). Use `{:optional true}`,
never `[:maybe …]`. Public fn:

```clojure
(ns seon.repair
  (:require [parinferish.core :as parinferish]))

(defn repair-source
  "Best-effort delimiter repair via parinferish indent-mode. Returns
   {:seon.repair/repaired? bool :seon.repair/source <repaired-or-original>
    :seon.repair/changes <diff-vector>}. Pure, never throws.

   `reads?` is injected (cycle-free): re-parse the repaired string and
   check zero `:kind :read` failures. Accept the repair ONLY if it (a)
   changed the source AND (b) the changed source now reads."
  {:malli/schema [:=> [:cat :seon.repair/source-request] :seon.repair/result]}
  [{:seon.repair/keys [source reads?]}]
  (try
    (let [parsed  (parinferish/parse source {:mode :indent})
          out     (parinferish/flatten parsed)
          changes (parinferish/diff parsed)]
      (if (and (not= out source) (reads? out))
        {:seon.repair/repaired? true  :seon.repair/source out :seon.repair/changes changes}
        {:seon.repair/repaired? false :seon.repair/source source :seon.repair/changes []}))
    (catch #?(:clj Exception :cljs :default) _
      {:seon.repair/repaired? false :seon.repair/source source :seon.repair/changes []})))
```

**LIVE-PROVEN on the real episode forms** (ran `parinferish/parse` + `flatten`
`{:mode :indent}` in the pod `:repl` build against faithful copies of both
dominant forms; both repaired forms read cleanly and the key-set check returns
`#{:seon.render/hiccup :seon.render/ai}`):

- **High-scores** (the form that failed 12×):

  ```text
  IN  : … (str "generated.md · " total-count " rows · :verified · " (js/Date.)]]}
        :seon.render/ai "High scores tile updated with 50 papers."})
  OUT : … (str "generated.md · " total-count " rows · :verified · " (js/Date.))]]
        :seon.render/ai "High scores tile updated with 50 papers."}))
  ```

  parinfer inserted the missing `)` after `(js/Date.` and closed the defn —
  BOTH `:seon.render/hiccup` AND `:seon.render/ai` survive as top-level map keys.

- **Start-screen `Usd`** (unclosed outer `[:div`, `:seon.render/ai` dedented to
  map-key level):

  ```text
  IN  : … "demo.tile · 4 rows · :verified · (js/Date.)"]
        :seon.render/ai "80s Arcade Start Screen Tile.")
  OUT : … "demo.tile · 4 rows · :verified · (js/Date.)"]]
        :seon.render/ai "80s Arcade Start Screen Tile.")
  ```

  parinfer closed the outer `[:div` with `]` BEFORE `:seon.render/ai`,
  preserving it as a map key.

**The deciding factor is INDENTATION**, and the agent's real code was
consistently indented (it always dedented `:seon.render/ai` back to map-key
column). So repair would have salvaged the entire episode and auto-evaled it.

**Residual risk (real in principle, NOT observed on these forms).**
Misleading indentation could in principle produce a wrong-but-valid structure
(e.g. a key absorbed into the preceding vector) that still reads. The `reads?`
gate is necessary but not sufficient to catch that. So we KEEP two guards even
though both PASS on the real forms: (1) a **key-preservation regression test**
(Part D test a) pinned to both real forms, asserting the repaired body map still
has both render keys; and (2) a **structural-shape note** in the transparency
output — the recorded eval states the delimiter change AND, where cheap, that
the resulting top-level form's shape may differ, so the agent can reject a
wrong-but-valid repair. State clearly in the recorded note that repair
auto-evaled.

**Integration at the seam.** In `eval-batch!`'s `:read` branch, on a read
failure:

1. Call `repair-source` on `(:source entry)` with the re-parse `reads?` gate
   ("re-`parse-forms` the repaired string and check zero `:kind :read`
   failures"; cycle-free).
2. If `:seon.repair/repaired?` → re-`parse-forms` the repaired string, eval those
   forms through the normal `:else` path, and record each with the repaired
   source + a transparency note derived from `:seon.repair/changes`
   (e.g. `;; repaired your input: inserted ) before the ]] at line 25; the
   resulting form is a 2-key map {:seon.render/hiccup …, :seon.render/ai …}`).
   Tag the entry so the agent sees the diff.
3. If not repaired → fall through to the sharpened `:read` error (§A.3).

**Honest can/can't-fix scope.**

- **CAN fix** (empirically verified): missing trailing parens; an unclosed call
  before a `]`/`}` arrives (the dominant high-scores case); the unclosed-`[:div`
  start-screen case; mismatched close-delimiter TYPE (`]`↔`)`↔`}` — indent-mode
  DOES swap these); a stray extra closer.
- **CANNOT reliably fix**: a wrong *opening* delimiter, or misleading
  indentation → can produce *valid-but-not-necessarily-intended* structure. This
  is exactly why (a) we re-validate and only accept reading output, and (b) the
  structural-shape note + key-preservation test exist.
- `:paren` and `:smart` modes are unusable (`:paren` no-ops on broken input;
  `:smart` needs cursor coords we don't have). **`:indent` is the only repairing
  mode.**

**Cycle/ordering:** `seon.repair` depends only on `parinferish` — cycle-free.
`seon.eval` adds a require on `seon.repair` (eval already sits above repl/db).

### A.3 — Sharpened per-form ERROR messages (honest scope)

**Seam:** preferred at parse time (`internal.cljc` `:error` branch, where source +
exception coexist); folded into `:seon.error/message` at the `:read` branch
(eval.cljs:1878).

A failure (when repair did NOT salvage it) is an ERROR (`ok? false`) and we **do
not downplay it**. The hardening is the *content*, made maximally actionable,
and **honest about what the data supports**:

1. **Parse `[at line N, column C]` out of the rewrite-clj message** — it is there
   today, e.g. "Unmatched delimiter: ] [at line 25, column 76]". The message
   names the offending **CLOSER + line:col**, NOT the unmatched opener.
2. **Slice line N from `:source`** and store it on the entry as
   `:seon.repl/error-line` with a caret `^` under column C.
3. **Compose the actionable instruction — naming the CLOSER only** (do NOT
   promise "the X opened at line Y is never closed" — rewrite-clj's message does
   not carry the opener, and a balance-walk is an optional nice-to-have, not
   required):

   ```text
   READ ERROR — this form did not parse, so it DEFINED NOTHING.
   Unmatched ] at line 25, col 76:
       (str "generated.md · " total-count " rows · :verified · " (js/Date.)]]}
                                                                            ^
   Do NOT call or wire anything that depended on this form — it does not
   exist. Fix the delimiter and re-eval the whole form.
   ```

   (Optional nice-to-have: parinfer's parse tree has the balance data, so a
   future enhancement could name the unclosed opener via a balance-walk. Not
   required for this PRD.)

4. **When repair succeeded** (§A.2), there is no error — the recorded eval is
   `ok? true` with the calm repaired-source + structural-shape note.

**Before/after.** Before: agent sees only "Unmatched delimiter: ] [at line 25,
column 76]" — coordinates, no source line; counts brackets by eye (failed ~12×).
After (repair-failed case): the offending closer + line + caret + the "this form
defined nothing — do not call/wire it" instruction.

**Cycle/ordering:** none — `internal.cljc` already holds source + exception.

### A.4 — False-confidence: failed-def provenance (FM-3, GATED)

**The reframe (corrects the draft, which aimed one layer too high).**
`(get tile-content :k)` → nil with `ok? true` is NOT an undeclared-var path.
`(get nil :k)` is legal Clojure — the analyzer emits no warning, so tightening
`truly-undeclared?` (which only runs *when* the analyzer emits a warning) may do
nothing for this trap. The real defect is **provenance**: a `(def x …)` /
`(defn x …)` whose eval returned `ok? false` must NOT make `x` resolvable for
subsequent forms. The fix hooks the failed-def, not the reference site.

**LIVE FALSIFICATION GATE (required before committing A.4).** Before writing the
fix, the implementer MUST reproduce in the pod, against a real bootstrap
compile-state, BOTH of:

- Does a `(def x (undefined-fn))` whose RHS errors leave `x` in
  `:cljs.analyzer/namespaces[<ns>][:defs]`?
- Does the LATER `(get x :k)` (or `(some-fn x)`) emit an `:undeclared-var`
  warning, or does it silently read nil with `ok? true`?

Choose the fix location based on the OBSERVED reality, not the assumption:

- **If the later reference DOES warn** (because the failed def registered `x` in
  `:defs` and the short-circuit suppresses it): the fix is to tighten the
  `:defs`-contains short-circuit in `truly-undeclared?` (eval.cljs:376-380) to
  ALSO require `resolves-on-globalthis?` (the same probe the `:else` branch
  uses). A name in `:defs` but not on globalThis (its def errored) then
  escalates to an honest `:compile` error.
- **If the later reference does NOT warn** (silent nil, the episode's actual
  behavior): the fix must be at the **def site** — detect that the `(def …)`
  eval itself returned `ok? false` and refuse to register the symbol as
  resolvable for the remainder of the batch (e.g. track failed-def symbols in
  the `eval-batch!` accumulator and escalate a later reference to them). The
  reference-site tightening alone is insufficient.

**Target experience (either fix location).** `(get tile-content :k)` after a
failed `(def tile-content …)` → an honest `:compile`/error result, ok? false,
naming the failed-def provenance ("the def that would create `tile-content`
failed (see eval <id>); it does not exist"), NOT nil/ok? true.

**This is its own phase, gated on the falsification.** Do not commit until the
reproduction has chosen the location. Part D test (c) is the falsification gate,
not a confirmation test.

**Cycle/ordering:** none — all inside `seon.eval`.

---

## Part B — Debug capture

### B.1 — A legitimate stateful artifact, not a reactive violation

The reactive principle (derive-from-DB, don't store what you can re-derive)
explicitly carves out "genuinely stateful runtime/debug artifacts." The raw
bytes that left and entered the process at turn T are *historical I/O* — they
**cannot** be re-derived (the ctx re-renders differently every turn; the model is
non-deterministic; parsing destroys the raw reply). A flat-file blob keyed by
`agent-id/turn-idx/turn-id` is correct — the same three-tier rule already applied
to `prompt-file` (DB datom = pointer/projection; blob = full content). The flag
is a process-level runtime knob, not derivable cluster state.

### B.2 — The flag

Mirror the established `env SEEDS → DB OWNS` pattern (`seon.ai`, ai.cljs:388-434)
and the `_DEBUG` precedent (`REPLICA_DEBUG`).

- **Boot/process default:** `SEON_DEBUG_CAPTURE` env var, **OFF by default**.
  Values `off | prompt | wire | full`. Read once.
- **Runtime override (live, no restart):** a single config row
  `:seon.debug/config` with `:seon.debug/capture-mode` + `:seon.debug/capture-dir`,
  flippable by transact, read on the next turn.
- **Resolution fns** (in the new `seon.debug` ns): `(capture-mode)` → row, else
  env, else `:off`; `(capture-dir)` → row, else `SEON_DEBUG_CAPTURE_DIR`, else
  default `logs/turns`.

Modes:

- `:off` (default) — nothing on the hot path (one cheap `db/entity` read per turn).
- `:prompt` — input ctx only (what `persist-prompt!` does today).
- `:wire` — input ctx + the exact request params (system blocks + cache
  breakpoints + messages + model + max_tokens).
- `:full` — `:wire` + the verbatim full raw response object (`:seon.ai/raw`:
  visible text, reasoning_content, tool_calls, stop_reason, usage).

### B.3 — Output location + keying (refuse-overwrite) + on-disk shape

**Project rule: never `/tmp`.** Default base `logs/turns`, configurable via row /
env. This is a DATA path → CWD-relative `logs/`, NOT
`seon.platform/artifact-path`.

**Keying — `<agent-id>/<turn-idx>-<turn-id>/`, with a monotonic component.**
`db/new-id!` is `<3 random letters>-<YYMMDDHHmm>` (db.cljs:203) — minute
resolution, non-monotonic, no cross-restart counter. Its own docstring notes
sub-minute order is not encoded. The episode ran ~5 turns/minute, so a bare
`turn-id` is neither unique nor sortable across a minute or a restart. Prefix the
turn-idx (zero-indexed, already computed by `turn-index`, agent.cljs:825) so the
dir is monotonic and order-reconstructible:

```text
logs/turns/<agent-id>/<turn-idx>-<turn-id>/
  prompt.txt      ; rendered user-message ctx (== today's prompt blob)
  request.edn     ; EXACT wire params: {model, max_tokens, system[], messages[],
                  ;   cache breakpoints, thinking, tools}            (:wire+)
  response.edn    ; verbatim :seon.ai/raw — full parsed response
                  ;   incl. reasoning/tool_calls/stop_reason/usage   (:full)
  response.txt    ; verbatim :seon.ai/text (visible reply) — written
                  ;   EVEN WHEN BLANK (closes the blank-output gap)  (:wire+)
```

**The writer must REFUSE TO OVERWRITE.** Make NO "truly unique" claim. If a
target dir already exists (a minute-collision or a restart reuse), the writer
suffixes (`-2`, `-3`, …) or logs-and-skips — an episode is never silently
clobbered. (Test d.)

**EDN, not JSON** for the structured artifacts: CLJS-native via `pr-str`, no JSON
dep, round-trips directly into a test fixture — exactly what the "robust eval
with proper tests" directive needs. The turn datom keeps ONE pointer
`:seon.agent.turn/debug-dir` (new, optional `:string`) — projection only.

### B.4 — Seams (capture at the run-turn!/ask-and-eval! boundary)

Capture at the **`run-turn!` / `ask-and-eval!` boundary**, where the prompt and
the raw response are both in hand — pairing is by construction, not by re-reading
async-local state at three separate sites.

1. **Input prompt** — already at `run-turn!` (agent.cljs:1166-1171): `turn-id`,
   `turn-idx`, and `prompt` are all local. Replace the bare always-on
   `persist-prompt!` (agent.cljs:1125) with a gated `debug/capture-prompt!`
   writing `prompt.txt` under the per-turn dir when mode ≥ `:prompt`. **This
   folds the shipped always-on capture under the flag — one mechanism, and it
   fixes the ~447 MB unbounded-growth bug.**
2. **Wire request + raw response** — both are available at `ask-and-eval!`
   (agent.cljs ~1110-1123) / its child `ask-and-eval-reply!` (agent.cljs:1007),
   which already receive the `resp` (carrying `:text`, usage, provider fields)
   and run inside `run-turn!`'s `with-tx-context` scope (turn-id at
   agent.cljs:1185). **Capture turn-id into a LOCAL at the start of the turn and
   thread it** to the write site — do NOT re-read `(db/current-tx-context)` after
   an `await` and assume AsyncLocalStorage survived the boundary (the load-bearing
   ALS-across-await assumption; test e pins it, but threading a local is the safer
   default).
   - When mode ≥ `:wire`: write `request.edn` with the exact params the adapter
     built (`request-params`, anthropic.cljs:120 / openai_compat.cljs:187). If
     the adapter is the only place the resolved system/cache-breakpoint payload
     exists, pass a capture callback or capture turn-id into the adapter via the
     existing tx-context the adapter already reads (ai.cljs:373) — but capture
     the id into a local at adapter entry, before any await.
   - When mode = `:full`: write `response.edn` (`pr-str` the full `:seon.ai/raw`)
     and `response.txt` (`:seon.ai/text`, **even when blank** — closes the
     blank-output gap).

All writes go through ONE helper `(debug/write-turn-artifact! agent-id turn-idx
turn-id filename content)` — best-effort, never throws (mirrors
`persist-prompt!`'s try/catch-and-log). **Losing a debug artifact must never
abort a turn.**

### B.5 — Perf / size / rotation

- `:off` (default): zero filesystem writes; one cheap config read per turn.
- `:full` for a 24-turn run ≈ 10-15 MB. Fine for debugging a specific agent; NOT
  acceptable always-on for every agent forever — exactly why it is flagged off by
  default (and why folding `persist-prompt!` under the flag fixes the existing
  447 MB latent bug).
- Rotation: a simple `debug/prune!` (keep last N turns per agent, or delete dirs
  older than D days), callable from a maintenance section/REPL; or
  `rm -rf logs/turns/<agent>` (gitignored, per-turn-addressable).

### B.6 — Code-smell to fix while wiring the flag

Several duplicate private `env-val` helpers exist (ai.cljs, brand.cljs,
openai_compat.cljs, plus reads in platform.cljs, fs.cljs, client.cljs,
serve.cljs). Per the shared-shape rule, **do not add another** — promote
`seon.platform/env-val` to public and reuse it in the new `seon.debug` ns.
(Folding the existing copies is optional scope.)

### B.7 — Schema additions (register before entity schema; load-order rule)

```clojure
;; seon.debug
(schema/register! :seon.debug/capture-mode [:enum :off :prompt :wire :full])
(schema/register! :seon.debug/capture-dir  :string)
(schema/register! :seon.debug/id  [:string {:seon.db/identity true}])
(schema/register! :seon.debug/config
  [:map {:seon.db/entity true}
   [:seon.debug/id :seon.debug/id]                                  ; always "config"
   [:seon.debug/capture-mode {:optional true} :seon.debug/capture-mode]
   [:seon.debug/capture-dir  {:optional true} :seon.debug/capture-dir]])

;; on the turn entity (augments the existing prompt-file/prompt-chars):
(schema/register! :seon.agent.turn/debug-dir :string)              ; pointer; blob holds content
```

### B.8 — Files touched (Part B)

- `src/seon/debug.cljs` (NEW) — `capture-mode`, `capture-dir`,
  `write-turn-artifact!`, `capture-prompt!`, `prune!`; depends only on
  `seon.db`/`seon.schema`/`seon.log`/`seon.platform` (cycle-free).
- `src/seon/agent.cljs` — swap `persist-prompt!` → `debug/capture-prompt!`
  (1166-1171); thread `turn-idx`+`turn-id` locals to the capture sites; add
  `:seon.agent.turn/debug-dir` to the open-tx.
- `src/seon/ai.cljs` — promote/reuse `env-val`; if the wire/raw write needs the
  adapter's resolved payload, expose a capture seam there.
- `.gitignore` — `logs/` already covers `logs/turns/`.

---

## Part C — Errors vs warnings (readable + actionable, NO wire guard)

### C.1 — Eval failures ARE errors

Per-form, in the eval surface: `record-eval!` writes `:seon.eval/ok? false` +
`:seon.eval/error`. §A.3 sharpens the content. We do not soften severity — "do
not downplay failures."

### C.2 — Urgent broken-tile warning

**Seam:** `check-tile-unresolved` (`src/seon/warn.cljs:853-897`, shipped this
session) + `render-warnings` (warn.cljs:979) + `render-cluster` (warn.cljs:966).
`check-tile-unresolved` is already in the `checks` registry (warn.cljs:923).

**The gap.** `check-tile-unresolved` is well-formed (global scope, same resolve
predicate as the calm placeholder) but renders through the SAME template as a
`[uses-maybe]` nit, and other clusters can render above it. The unresolved symbol
does NOT throw — render routes to the CALM `default/pending-html` placeholder
(default.cljs:53), so the live-tile section shows the calm twin ("Preparing this
view…") and the only escalation is buried in the warnings cluster. **This is
precisely what let the agent loop without urgency.**

**Three-part fix (data-driven, one mechanism — urgency is a flag the renderer
reads):**

1. **Promote to an urgent tier.** Add `:seon.warn/urgent? true` to
   `check-tile-unresolved`'s `::check-response`. In `render-warnings`, partition
   clusters into urgent-first and render urgent ones with a louder
   `render-urgent-cluster` template at the TOP of `<warnings>`:
   `⚠ YOUR LIVE TILE IS BROKEN RIGHT NOW — the human sees a placeholder, not your
   view.`
2. **Name the human stakes + salience join (if cheap).** When the unresolved
   symbol matches a fn the agent *tried and failed to define this turn* (join
   `check-tile-unresolved`'s symbol to failed-eval rows whose source mentions
   it), escalate and name the failed eval-id: "your `my-kb-high-scores-tile`
   failed to parse (eval <id>) — that is why the tile is dead." Name the exact
   symbol (already in `:seon.warn/sym`) and the single next action. If the join
   is not cheap, ship part (1) + (3) and treat the salience join as optional.
3. **Sharpen the agent-facing TWIN, keep the human visual calm.** Keep
   `pending-html`'s VISUAL calm for the human (default.cljs:63 — confirmed
   correct: "Preparing this view…", no error dump). But the `:seon.render/ai`
   TWIN (default.cljs:74) and the live-tile section body the AGENT reads should
   lead with "BROKEN:" so it reads as a live defect, not a loading state. Visual
   stays calm for the human; words stay loud for the agent.

Strongest variant: render the urgent defect INSIDE the `live-tile-section`
itself, since that section is guaranteed to be about this exact tile.

**Cycle/ordering:** none — `warn.cljs` already requires `seon.eval` + `seon.db`
and calls `eval/lookup-value`. New surfaces stay derived-at-render (no stored
warning datoms), per the reactive principle.

### C.3 — No wire-time guard. No new attribute.

**There is NO wire-time tx-validator.** The earlier draft proposed a
`!tx-validators` registry in `seon.db.internal`, eval-side registration, a
`validate-entity-values!` hook, and a hard-fail on an unresolvable tile symbol.
**That entire design is removed.** Rationale:

- The wire-guard treated a *symptom*. The real fix is the parse experience
  (Part A) — once the `(defn …)` parses (or is repaired and auto-evaled), the fn
  exists and the wire works. The episode's transacts (`yaC`, `kwm`, `ViA`, …)
  all SUCCEEDED; the only broken thing was the defn, which Part A fixes.
- Rejecting a good transaction over one not-yet-resolved symbol is LLM-hostile
  (extra latency + cost on a transact the agent legitimately wanted) and adds
  complexity for no benefit. Forms are sequential, so a "define then wire in a
  later form" workflow is normal and must not be blocked at the wire.
- **No new stored DB attribute** to flag the problem either. The broken-tile
  condition is ALREADY a pure query over the existing stored pointer
  (`:seon.render.live-tile/content`) joined against resolvability — exactly what
  the already-shipped, derived, self-healing `check-tile-unresolved` warning
  (C.2) computes. "Derive, don't store."

**The tile experience for an unresolved fn** is therefore entirely derived /
self-healing, no wire rejection:

1. The transact is accepted as-is (the pointer is set).
2. The human sees the calm `pending-html` placeholder (already shipped), which
   auto-updates the instant the fn resolves.
3. The agent gets **same-turn** feedback via the sharpened parse error on the
   failed defn form (Part A.3) and **cross-turn** feedback via the URGENT
   `check-tile-unresolved` warning (C.2), which self-heals when the fn is
   defined.

---

## Part D — Comprehensive test plan

**Runner:** `bin/test-cljs` (shadow `:test` target, `:node-test`, DEV compile —
release breaks `lookup-value`'s globalThis walk + instrumentation; ~160s, fresh
JVM). `--no-build` skips compile; logs to `tmp/test-cljs-<ts>.log`. **Never run
overlapping `cljs.test/run-tests` in the live pod** — restart for a pristine run.
Single-behavior checks: eval the fn directly against the live pod.

**Conventions.** Pure tests: plain `deftest` + `is`/`testing` (model:
`repl_parity_test.cljs`). Async/DB tests: `(async done … (.then … (fn [] (done))))`,
fresh `:memory` conn bound via `set!` (model: `agent_loop_test.cljs:46`
`with-conn`). Real eval: `(await (repl/ensure-bootstrap!))` for compile-state,
then `eval-batch!` (model: `record_eval_tee_test.cljs`). Unregister test schema
keys after (process-shared registry).

The five tests the critique flagged as missing are marked **[critique-flagged]**.

| Namespace | Kind | Scenario | Covers |
|-----------|------|----------|--------|
| `test/seon/repair_test.cljc` | pure | missing trailing paren → repaired, reads | A.2 |
| | | unclosed `(str …` before `]]}` → repaired | A.2 / FM-1 |
| | | mismatched `]`-for-`)` → repaired to correct closer | A.2 |
| | | stray extra `]`/`}`/`)` → removed/corrected | A.2 |
| | | **(a) real HIGH-SCORES form (ep. line 366) → repaired body map has BOTH `:seon.render/hiccup` AND `:seon.render/ai`** (key-set preservation, not just "reads") **[critique-flagged]** | A.2 / FM-1 |
| | | **(a) real START-SCREEN `Usd` form (ep. lines 47-80) → repaired body map has BOTH render keys** (key-set preservation) **[critique-flagged]** | A.2 / FM-1 |
| | | idempotency: `(+ 1 2)`, real `transact!`, `let`+hiccup → `:repaired? false`, byte-identical | A.2 |
| | | re-validate gate: a "fix" that reads-but-changes-shape → `:changes` non-empty so the note flags shape drift | A.2 residual risk |
| | | unrepairable garbage (`(((`, half-typed string) → `:repaired? false` | A.2 |
| `test/seon/repl/internal_test.cljc` | pure | multi-form: good + bad → forms before AND after bad span parse; bad span repaired-or-`:read` | A.1 / FM-1 |
| | | prose preamble + code (`"80s arcade…"` then `(defn …)`) → prose dropped, defn parses | A.1 / FM-2 |
| | | bare-atom narration (`hello world`) → dropped (narration-atom?) | A.1 |
| | | prose that THROWS in reader (`80s`, `to:`, `detail:`, `v1.0`) → dropped as narration, NOT a `:read` failure (regression pin) | A.1 / FM-2 |
| | | **(b) parenthetical-prose narration — `"I'll use (subs (str (js/Date.)) 11 19) to format the time."` (ep. line 40) → NOT recorded as a `:read` failure** (opener-at-START rule) **[critique-flagged]** | A.1 |
| | | broken-code span with opener AT START (`(+ 1 3x)`, error `Invalid number: 3x`) → still recorded as broken code, not dropped | A.1 rule |
| `test/seon/eval/repair_batch_test.cljs` | async+conn | multi-form, form N fails (unrepairable), N+1 still runs; ns accumulator unchanged on failure | directive A |
| | | repair-succeeds-auto-eval: `:read` failure repairs → repaired form evals, recorded eval is `ok? true` with repaired source + structural-shape note | A.2 |
| | | repair-fails-sharp-error: unrepairable → `:read` failure with closer + line:col + offending line + caret + "defined nothing — do not call/wire" | A.3 |
| | | **(c) FALSIFICATION GATE: `(def x (undefined-fn))` then `(get x :k)` — assert the OBSERVED behavior (nil/ok? true today), then assert the chosen fix turns it into an honest error**. This test RUNS BEFORE the A.4 fix is committed and decides the fix location. **[critique-flagged]** | A.4 / FM-3 |
| | | undeclared-var: `(my-undefined-fn nil)` → `:compile` error naming the symbol | A.4 |
| | | ns-switch across forms; `n-ok`/`n-fail`/`ids` accounting correct across a mixed (form + read + repaired) batch | directive A |
| `test/seon/warn_test.cljs` (extend) | async+conn | `check-tile-unresolved` fires for a missing fn; self-heals once defined (reactive) | C.2 |
| | | `:seon.warn/urgent? true` set; renders FIRST with the louder template | C.2 |
| | | salience (if shipped): unresolved tile fn matches a fn that failed to parse this turn → escalated, names failed eval-id | C.2 |
| | | `pending-html` visual stays calm ("Preparing this view…"); `:seon.render/ai` twin leads with "BROKEN:" | C.2 |
| `test/seon/debug_test.cljs` | async+conn | `SEON_DEBUG_CAPTURE=full`: turn writes prompt.txt + request.edn + response.edn + response.txt under `logs/turns/<agent>/<turn-idx>-<turn-id>/` | B |
| | | blank reply still captured (`response.txt` written) — debug ≠ non-blank self-message gate | B.3 |
| | | flag `:off` → no debug writes; behavior byte-identical | B.2 |
| | | configurable dir (`SEON_DEBUG_CAPTURE_DIR` / row) honored; defaults to `logs/turns` | B.3 |
| | | DB config row overrides env at runtime (next turn) | B.2 |
| | | **(d) collision: an existing `<turn-idx>-<turn-id>` dir → writer suffixes / skips, NO silent overwrite** **[critique-flagged]** | B.3 |
| | | **(e) turn-id at the write site: assert the threaded local turn-id is correct at the `ask-and-eval!` write site after the LLM `await`** (or, if relying on ALS, assert `(db/current-tx-context)` still returns the turn-id post-await) **[critique-flagged]** | B.4 |

**Fixtures from real episodes.** Seed the repair / internal tests with the
`ari-2606180804` snippets from `tmp/2026-06-18-highscores-episode.txt` and
`tmp/2026-06-18-raw-agent-output.txt` immediately. Once Part B lands, captured
`request.edn` / `response.txt` from a real failing turn become test fixtures
directly (the EDN round-trips).

---

## Phased implementation plan (parallel-safe file partition)

Three groups. **Within a group, phases are sequential (they touch the same
files); across groups they are independent and can run in parallel.** The only
shared concern is the schema registry (process-global), avoided by each group
registering its OWN namespaced keys in its OWN ns — no two groups register the
same key.

### Group A — eval heart (A.1-A.4). Sequential within.

Files: `src/seon/repl/internal.cljc`, `src/seon/eval.cljs`, NEW
`src/seon/repair.cljc`. Tests: `test/seon/repair_test.cljc`,
`test/seon/repl/internal_test.cljc`, `test/seon/eval/repair_batch_test.cljs`.

1. **A.1 — parse-forms hardening.** Pure CLJC: opener-at-START prose
   classification + narrowed-recovery for prose + offending-line extraction in
   `internal.cljc`. No cycle, no new deps. Highest value, lowest risk.
2. **A.2 — repair (ON BY DEFAULT).** NEW `seon.repair.cljc` (parinferish
   indent-mode, `reads?`-gate). **First verify `parinferish` is on the `:test`
   build classpath** (add to `:cljs`/`:test` `:extra-deps` if absent). Wire into
   `eval-batch!`'s `:read` branch: repair → auto-eval repaired forms → record
   with diff + structural-shape note. Key-preservation tests (a) on both real
   forms.
3. **A.3 — sharpened errors.** In `internal.cljc` / folded at the `:read` branch.
   Name the CLOSER + line:col + source line + caret only (no opener promise).
4. **A.4 — false-confidence (GATED).** Run the falsification (test c) FIRST to
   choose the fix location (reference-site tighten vs failed-def provenance),
   then implement inside `seon.eval`.

### Group B — debug capture (B). Independent files.

Files: NEW `src/seon/debug.cljs`, `src/seon/agent.cljs`, `src/seon/ai.cljs`
(+ adapter capture seam if needed). Tests: `test/seon/debug_test.cljs`.

5. **B — debug capture.** New `seon.debug.cljs`; fold `persist-prompt!` under the
   flag; thread turn-idx+turn-id locals to the `run-turn!`/`ask-and-eval!`
   capture sites; refuse-overwrite writer; EDN+txt artifacts; schema regs;
   promote `seon.platform/env-val` public.

### Group C — warn urgency (C.2). Independent files.

Files: `src/seon/warn.cljs` (+ confirm `src/seon/render/default.cljs`
`pending-html` visual stays calm, sharpen its `:seon.render/ai` twin). Tests:
extend `test/seon/warn_test.cljs`.

6. **C.2 — urgent warning.** `:seon.warn/urgent?` + urgent partition in
   `render-warnings` + (optional) salience join + agent-facing twin wording.

### Shared-concern callout

- `src/seon/eval.cljs` is touched by Group A only (A.2 require on `seon.repair`,
  A.4 inside `truly-undeclared?`/batch accumulator). Group C reads
  `eval/lookup-value` but does NOT edit `eval.cljs` — no conflict.
- `src/seon/agent.cljs` is touched by Group B only.
- `src/seon/render/default.cljs` is touched by Group C only.
- No two groups edit the same file. Schema keys are partitioned by namespace
  (`:seon.repair/*` in Group A, `:seon.debug/*` in Group B, `:seon.warn/urgent?`
  in Group C).

Full suite (`bin/test-cljs`) ONCE at the end of each group (test-cadence
economy), not per-edit.

---

## Risks + remaining falsification gate

- **A.4 is the one open falsification (gate, blocks Phase A.4).** Whether a
  failed `(def x …)` leaves `x` in `:defs` and whether the later reference warns
  is UNVERIFIED — test (c) must run in the pod against a real compile-state
  before the fix is committed, and the fix location depends on the result. This
  is the single must-falsify-before-shipping item.
- **Parinfer wrong-but-valid repair** (A.2) — does NOT reproduce on the real
  forms (both verified live to preserve both render keys), but is real in
  principle for misleadingly-indented input. Mitigated by the key-preservation
  tests (a) + the structural-shape note. Repair is on-by-default per the user's
  decision; the note is the guard.
- **Prose-vs-code misclassification** (A.1) — the opener-at-START rule must not
  over-drop genuinely broken code (`(+ 1 3x)` opener at start → kept) nor
  under-drop inline-code prose (`"I'll use (subs …)"` opener mid-line → dropped).
  Tests (b) + the broken-code-opener-at-start test pin both edges.
- **Debug keying / overwrite** (B.3) — minute-resolution, non-monotonic ids;
  mitigated by the `<turn-idx>-` prefix (monotonic) + refuse-overwrite writer.
  Test (d).
- **ALS across await** (B.4) — threading turn-id into a local at turn entry
  avoids the assumption entirely; test (e) pins whichever approach ships.
- **Debug disk growth** — mitigated by off-by-default + `prune!` + folding the
  existing always-on prompt capture (the 447 MB latent bug) under the flag.

### Files (all absolute)

- `/Users/sean/src/seon/src/seon/repl/internal.cljc` — `parse-forms` (204-256),
  `try-parse-one-token` (169-198), `narration-atom?` (103-115),
  `find-recovery-point` (143-158). [A.1/A.3]
- `/Users/sean/src/seon/src/seon/repair.cljc` (NEW) — CLJC port of
  `/Users/sean/src/seon/src/seon/dev/repair.clj` (try-repair 84-96, repair
  156-166), cljfmt dropped, `{:optional true}` not `[:maybe]`. [A.2]
- `/Users/sean/src/seon/src/seon/eval.cljs` — `eval-batch!` (1783; `:read` branch
  1867-1880), `truly-undeclared?` (322-392; short-circuit 376-380),
  `lookup-value` (286-320), `resolves-on-globalthis?` (280-284). [A.2/A.3/A.4]
- `/Users/sean/src/seon/src/seon/warn.cljs` — `check-tile-unresolved` (853-897),
  `checks` (903-923), `render-cluster` (966-977), `render-warnings` (979). [C.2]
- `/Users/sean/src/seon/src/seon/render/default.cljs` — `pending-html` (53-79;
  visual calm at 63-72, twin at 73-79). [C.2]
- `/Users/sean/src/seon/src/seon/render/live_tile.cljs` — `live-tile-section`
  body. [C.2]
- `/Users/sean/src/seon/src/seon/agent.cljs` — `run-turn!` (1145-1219,
  prompt+turn-idx+turn-id locals 1166-1171), `persist-prompt!` (1125-1143),
  `ask-and-eval!` (~1110-1123), `ask-and-eval-reply!` (1007-1040), `turn-index`
  (825-831). [B]
- `/Users/sean/src/seon/src/seon/ai.cljs` — `env-val`, `log-error!` ALS turn-id
  read (~373); adapter `request-params` in `ai/anthropic.cljs` (120-169) /
  `ai/openai_compat.cljs` (187-207) for the wire payload. [B]
- `/Users/sean/src/seon/src/seon/platform.cljs` — promote `env-val` public. [B]
- `/Users/sean/src/seon/src/seon/db.cljs` — `new-id!` (203-215, the keying
  non-monotonicity). [B reference only — NOT edited]
- `/Users/sean/src/seon/src/seon/debug.cljs` (NEW). [B]
- Evidence: `/Users/sean/src/seon/tmp/2026-06-18-highscores-episode.txt`,
  `/Users/sean/src/seon/tmp/2026-06-18-raw-agent-output.txt`.
