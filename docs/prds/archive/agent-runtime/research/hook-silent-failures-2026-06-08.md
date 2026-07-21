---
type: research
status: active
tags: [research, reference]
---

# Dev-hook silent-failure class — why a broken conventions path went unnoticed for weeks

## TL;DR

The Gemini reviewer ran with **no project conventions injected** from roughly
2026-03-11 (when `CONVENTIONS.md` was reorganized into `docs/conventions.md`)
until the path was fixed — silently. The root cause is not one bug but a
**three-layer silence**:

1. `load-conventions` uses a `(when (.exists f) …)` guard that returns `nil`
   on a missing file, with **no log/warn/error**. A missing *configured input*
   is treated identically to "file legitimately absent".
2. Every downstream consumer treats `nil`/empty conventions as a **valid,
   optional value** — `build-context`, `call-gemini`, and `gemini/review-code`
   all `(when conventions …)` and proceed happily with a degraded prompt.
3. The one test that exercises this path (`review_test.clj` "Loads conventions
   with truncation") wraps its assertion in `(when (::review/conventions
   result) …)` — so when conventions came back `nil`, the assertion was
   **never evaluated** and the test stayed green.

There is **no observability into the hook's own health** — no startup
assertion that required inputs loaded, no log line when an expected input is
missing, no surface in the hook output that says "running without
conventions". The hook is fire-and-forget and **fail-open** at every level: a
missing input degrades quality silently rather than warning, and a thrown
exception in the review/compliance path is swallowed so the Edit/Write just
proceeds.

Count of silent-swallow spots in the dev hook found below: **1 critical
config-path silence (the conventions bug), plus ~6 broad-catch / fail-open
sites** that could each mask a real degradation.

Top 3 guards to add: (1) **fail-loud on a missing configured path** — distinguish "configured file
not found" from "optional file legitimately absent" and log a WARN + surface
it in hook feedback; (2) a **startup/health assertion** that required hook
inputs (conventions, kondo config) loaded non-empty; (3) **fix the test** to
assert conventions are present, not conditionally skip.

## The exact mechanism of the conventions silence

### The move (the trigger)

- `CONVENTIONS.md` lived at repo root through 2026-01-19 (last commit touching
  it: `dbad661`).
- `50e6995 "feat: reorganize docs/ into clean Obsidian knowledge system"`
  (2026-03-11) moved it to `docs/conventions.md`. Root `CONVENTIONS.md` no
  longer exists (`ls: CONVENTIONS.md: No such file or directory`).
- `review.clj`'s `conventions-path` const still pointed at the old root path
  for ~weeks afterward (a separate agent is fixing it; as of this writing the
  const at `src/seon/dev/review.clj:164` already reads `"docs/conventions.md"`).

### The silent code path

`src/seon/dev/review.clj:200-206`:

```clojure
(defn- load-conventions
  "Load conventions from file, truncating if needed."
  [max-length]
  (let [f (io/file conventions-path)]
    (when (.exists f)              ; <- missing file => nil, no log, no warn
      (-> (slurp f)
          (truncate max-length)))))
```

When `conventions-path` doesn't exist, `(when (.exists f) …)` returns `nil`.
There is no `else` branch, no `log/warn`, no exception — the function just
hands back `nil`.

`build-context` (`review.clj:276-289`) stores that `nil` verbatim:

```clojure
conventions (load-conventions max-conv)
…
::conventions conventions          ; nil flows straight through
```

`call-gemini` (`review.clj:316-326`) forwards `nil` to the client, and the
client builds the system instruction with a `(when conventions …)` that simply
omits the conventions block when `nil` (`src/seon/ai/gemini.clj:566-567`):

```clojure
(when conventions
  (str "=== PROJECT CONVENTIONS ===\n" conventions "\n\n"))
```

Net effect: Gemini received a system instruction with the literal text
"Review these Clojure code changes against project conventions" but **zero
conventions content** — it was reviewing against conventions it was never
given.

### Why this differs from every other file read in the same namespace

`read-file-safe` (`review.clj:170-179`) — used for **source files** — returns
a visible placeholder on a missing file:

```clojure
(if (and (.exists f) (.canRead f))
  (try (slurp f) (catch Exception e (str "[Error reading file: " (.getMessage e) "]")))
  "[File not found]")          ; <- LOUD: the placeholder reaches Gemini's prompt
```

So a missing *source* file shows up as the string `[File not found]` in the
review prompt — a human or agent reading the prompt would notice. A missing
*conventions* file produces `nil`, which is invisible. **The same namespace has
two opposite policies for "file not found": loud placeholder vs. silent nil.**
That inconsistency is the heart of the bug. `conventions-path` is a *configured
constant* (a thing that is supposed to exist) — it should fail loud like a
config error, not soft like an optional input.

### The third layer: the test that should have caught it

`test/seon/dev/review_test.clj:91-97`:

```clojure
(testing "Loads conventions with truncation"
  (let [result (review/build-context
                {::review/files #{} ::review/max-conventions-length 100})]
    (when (::review/conventions result)          ; <- assertion SKIPPED if nil
      (is (<= (count (::review/conventions result)) 120)))))
```

When the path broke and `::conventions` came back `nil`, the `when` short-
circuited and **no assertion ran** — the test passed with zero checks. The
test comment even says "Just verify it doesn't crash", which is exactly the
fail-open mindset that hid the bug. A test that asserted conventions are
**present and non-empty** would have gone red the moment the file moved.

## Was there any signal?

**No.** Searching the dev-hook code and tests for conventions-health
observability turns up nothing:

- No `log/warn`/`log/error` is emitted anywhere when conventions fail to load.
- The review output (`format-output`, `review-edits-response`) carries no "no
  conventions" indicator. The `::conventions` value is stored in the
  `review-event` for training data, but nothing inspects it for emptiness.
- The hook is invoked per-edit and its result is fire-and-forget; there is no
  health check, no startup assertion, no "hook self-test" that a human or
  agent would see.

A human could only have noticed by reading a recorded Gemini system
instruction and observing the missing conventions block — i.e. nothing in the
normal workflow surfaces it.

## How do hook errors surface at all? (fail-open vs fail-loud)

The hook is **fail-open at every level**:

- `bin/seon-hook -main` wraps all processing in `(try … (catch Exception e
  (log! "ERROR" …) (println "HOOK ERROR:" …)))` (`bin/seon-hook:410-413`) — but
  note it **does not emit a block decision**; on any uncaught error the process
  simply ends and Claude Code proceeds (default continue). So a hook crash =
  silent skip of all feedback.
- `format-response` (`bin/seon-hook:142-158`) defaults to `{:continue true}`
  for anything that isn't an explicit `"block"` — including `nil`/malformed
  responses.
- `load-config` (`bin/seon-hook:64-68`) returns `{}` on any parse error
  `(catch Exception _ {})` — a corrupt `seon-hook.edn` silently falls back to
  empty config (which then merges with code defaults, so the hook keeps running
  with possibly-different settings than the file intends).
- Inside `seon.dev.hook`, `call-gemini`'s outer `(catch Exception e (log/error
  …) {::success false …})` (`review.clj:341-344`) at least logs, but the review
  feedback then just says "Gemini review failed: …" and the edit still
  succeeds. Review is **advisory, never blocking** by design — which is fine,
  but it means review degradation is invisible unless someone reads the
  feedback line.

The only fail-**loud** path is PreToolUse syntax/lint validation, which emits a
real `"block"` decision. Everything in the PostToolUse quality pipeline
(compliance, review, code-index) is best-effort and degrades silently.

## Silent-failure inventory (dev hook)

| File:line | Pattern | What it swallows | Risk it could mask |
|-----------|---------|------------------|--------------------|
| `review.clj:204` | `(when (.exists f) …)` → nil | Missing **configured** conventions file | **THE BUG.** Reviewer runs with no conventions; no log, no surface. Critical because the path is a constant that is *supposed* to resolve. |
| `review_test.clj:96` | `(when (::conventions result) (is …))` | Assertion skipped when conventions nil | Test stays green while the feature is broken. Third layer of the same silence. |
| `gemini.clj:566` | `(when conventions …)` | Treats nil conventions as valid-optional | Downstream cannot tell "no conventions configured" from "conventions empty". Propagates the silence into the prompt builder. |
| `bin/seon-hook:410-413` | `(catch Exception e (log! "ERROR" …))` then end | Any uncaught hook error | Whole hook silently no-ops (fail-open continue). A broken nREPL eval, a thrown stage, etc. → edit proceeds with zero feedback. |
| `bin/seon-hook:64-68` | `load-config` `(catch Exception _ {})` | Corrupt/invalid `seon-hook.edn` | Hook silently runs on empty config (then code defaults). A typo in the config file disables nothing visibly but may change behavior. |
| `bin/seon-hook:100-105`, `135` | `nrepl-eval` catches → nil; result parse `(catch _ nil)` → orchestrator-port | nREPL connection / eval failures | Session routing silently falls back to orchestrator port; a down REPL means no pipeline ran but the edit still continues. |
| `hook.clj:297-298` | `update-code-index!` `(catch Exception e (log/debug …))` | Code-index ingest failure | Debug-level only; Datahike code graph silently drifts out of sync with source. (By-design best-effort, but `log/debug` is effectively invisible.) |
| `hook.clj:522-523` | markdown auto-fix `(catch Exception e (log/debug …))` | Markdown fix failure | Debug-level; a broken auto-fix silently leaves the file unfixed. |

Note: the many `(catch … _ nil/false/[])` in `instrumentation.clj`,
`codebase.clj`, `clojure_replace.clj`, `test.clj` are mostly **legitimate**
best-effort probes (reflection over vars, optional file existence) where nil/
false is a meaningful answer, not a hidden failure. They are lower priority
than the config-path and fail-open-continue class above.

## Prioritized recommendations (make breakage LOUD without making the hook fragile)

### P0 — Fail loud on a missing *configured* path

`load-conventions` must distinguish "the configured conventions file is
missing" (a config/path error worth shouting about) from "optional file
absent" (fine). Concretely: when `conventions-path` does not resolve, emit
`log/warn` **and** thread a one-line marker into the review feedback /
`::conventions` so it is visible in hook output, e.g. return a placeholder
string like `read-file-safe` does (`"[conventions not found at <path>]"`)
instead of `nil`. This makes the same failure visible in the Gemini prompt and
in the recorded review event. Mirror the existing `read-file-safe` policy —
the inconsistency between the two readers in one namespace is itself the smell
to remove. **Do not** make it block; loud + non-blocking is the right level for
an advisory reviewer.

### P0 — Fix the test to assert, not skip

Change `review_test.clj`'s conventions test to assert conventions are
**present and non-empty** (the file is checked into the repo, so it must
resolve). Drop the `(when (::conventions result) …)` guard. A green test must
mean "conventions loaded", not "didn't crash". Add a direct unit test:
`(is (.exists (io/file review/conventions-path)))` so a future move of the file
fails a test immediately.

### P1 — Startup / health assertion for required hook inputs

Add a hook self-check (run at hook namespace load, or as an Integrant
`:seon.dev/...` component health check) that asserts every *required* input
resolves non-empty: `conventions-path`, the clj-kondo config, any other
configured file constants. On failure, `log/warn` loudly (not debug) with the
resolved absolute path. This is the "configured inputs loaded" gate that would
have caught the move within one boot. Keep it non-fatal — warn, don't throw —
so a missing optional input never bricks the whole hook.

### P1 — Raise the visibility floor on best-effort catches

The fail-open `(catch … (log/debug …))` sites in `hook.clj` (`update-code-
index!`, markdown auto-fix) and `bin/seon-hook` (config parse, nREPL fallback)
should log at **warn**, not **debug** — debug is effectively off in normal
runs. A best-effort step is allowed to keep going, but its failure should leave
a trail. Specifically: `load-config`'s `(catch Exception _ {})` should
`log/warn` the parse error before falling back, so a broken `seon-hook.edn`
isn't invisible.

### P2 — Surface hook-internal errors in the hook output, not just logs

When the review/compliance/code-index stage throws, append a short
`"[hook: <stage> failed — see logs]"` line to the feedback vector instead of
silently dropping it. The agent reading hook feedback then knows a stage
degraded. Combined with P1's warn-level logging, this turns the hook from
fire-and-forget into observable.

### P2 — Consider a logged-read helper

Replace ad-hoc `(when (.exists f) (slurp f))` reads of *configured* files with
a single `read-required-file` helper that logs a warn + returns a visible
placeholder on miss (and `read-optional-file` for genuinely-optional inputs).
One helper, one consistent policy — removes the per-call-site drift that let
`load-conventions` and `read-file-safe` diverge in the first place.

## Source pointers

- `src/seon/dev/review.clj:170-179` — `read-file-safe` (loud placeholder, the good policy)
- `src/seon/dev/review.clj:200-206` — `load-conventions` (silent nil, the bug)
- `src/seon/dev/review.clj:162-164` — `conventions-path` const
- `src/seon/ai/gemini.clj:560-568` — system-instruction builder, `(when conventions …)`
- `test/seon/dev/review_test.clj:91-97` — the conditionally-skipped test
- `bin/seon-hook:64-68` — `load-config` fail-open
- `bin/seon-hook:370-413` — `-main`, fail-open-continue on any error
- `src/seon/dev/hook.clj:269-298` — `update-code-index!` best-effort debug catch
- `src/seon/dev/hook.clj:421-442` — `stage-review` (consumes review result, no nil-conventions check)
- Move commit: `50e6995` (2026-03-11) "reorganize docs/ into clean Obsidian knowledge system"
