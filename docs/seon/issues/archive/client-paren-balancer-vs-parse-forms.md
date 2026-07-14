---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent]
---

# client paren-balancer duplicates (and under-handles) parse-forms

## Resolution (2026-06-28)

Fixed via option (b) — factored a public `form-source-at` into
`seon.repl.internal` (the ns that already owns rewrite-clj) and routed the
client through it. The hand-rolled `extract-form-from-string` depth-counter
is DELETED; `extract-form-at-line` / `extract-form-at-index` now delegate to
`seon.repl.internal/form-source-at`, which reads EXACTLY one rewrite-clj node
(`rewrite-clj.node/string` of `rewrite-clj.parser/parse-string` on the chunk)
— so a `)` inside a char literal (`\)`), regex literal (`#"…)…"`), or string
is balanced correctly instead of truncating the form. One node is parsed, not
the whole file, so there is no perf regression. The find-first-`(`, leading-
indentation-drop, line-bounds-check (ea603627), and EOF/unbalanced fallback
(rewrite-clj throws → catch → from-`(`-to-EOF substring) semantics are all
preserved.

The genuine truncation bug was the CHARACTER literal (`\)` / `\(` / `\"`): the
old scanner only tracked string state, so a `\)` outside a string decremented
depth. (Regex `#"…)…"` was already handled incidentally by the string
tracking, but is now handled robustly.)

Live-proven in the pod; focused cases added to `seon.repl.internal-test`
(`form-source-at-literal-aware` / `form-source-at-semantics`); `bin/test-cljs`
green (689 tests / 3195 assertions / 0 failures).

## Problem

`seon.client` carries a hand-rolled paren/string-aware scanner —
`extract-form-from-string` / `extract-form-at-line` / `extract-form-at-index`
(`src/seon/client.cljs:1074-1120`, all private) — separate from the rewrite-clj
agent-reply segmenter `seon.repl.internal/parse-forms`. It is a depth-counting
state machine (paren depth + in-string + escape) used to pull ONE top-level form
by line/index for program-graph source capture.

Two concerns:

1. **Correctness edge bugs.** The scanner counts raw `(`/`)` and only tracks
   string state — it does NOT understand **character literals** (`\)`) or **regex
   literals** (`#"…)…"`), so a `)` inside either decrements depth and truncates
   the extracted form. rewrite-clj (and thus `parse-forms`) handles these
   correctly. Low severity — such literals are unlikely in a `defn` header — but
   real.
2. **Duplication of the "segment a top-level form" capability.**

## Callers (what each needs)

| caller | `src/seon/client.cljs` | needs |
|--------|------------------------|-------|
| `var->fn-row` | ~1295 | form source at a `:line` (defn name/doc/arglists) |
| `defn-rows-from-source` | ~1491 | form source at a char index, scanning a file (advances by `(count form)`) |
| `var->test-row` | ~1706 | form source at a `:line` (deftest) |

## Why NOT to fully unify (assessed 2026-06-28, Explore agent)

- **Perf:** the line/index callers extract ONE form; `parse-forms` parses the
  whole file. Measurable overhead on every var introspection.
- **Architecture:** `parse-forms` segments AGENT REPLIES (drops prose, demotes
  data literals, error-recovers). A source-file scanner wants none of that.
- **Capability gap:** `parse-forms` exposes `:span [start end]` ONLY on `:read`
  entries, NOT on `:form` entries, and tracks no line numbers — so it can't
  answer "the form at line N" without retrofitting both.

## Acceptance criteria (deferred path, if pursued)

Partial unification of `defn-rows-from-source` ONLY becomes worthwhile after
`parse-forms` form entries gain `:span [start end]` (+ optionally `:line`).
Then `defn-rows-from-source` can filter `:form` entries by a `^\(\s*defn-?`
source match instead of the hand-rolled scan — picking up correct char/regex
literal handling for free. `var->fn-row`/`var->test-row` (line-keyed, single
form) stay on a dedicated extractor regardless.

Minimum viable fix if we DON'T unify: make the scanner string/char/regex-literal
aware (or note the limitation in its docstring) so the truncation bug can't
silently corrupt a captured form.

## Links

- [[docs/prds/agent-fsm/research/eval-segmenter-2026-06-28]] (flagged the smell)
- `seon.repl.internal/parse-forms` (the segmenter; now carries `:span`/`:error-kind` on `:read`)

## Resolution (2026-06-28 audit)

Closed RESOLVED per `docs/seon/issues-audit-2026-06-28.md`: `b5287550`
replaced the hand-rolled paren/string scanner with rewrite-clj literal-aware
extraction (`form-source-at`); the depth-counting `extract-form-from-string` is
deleted.
