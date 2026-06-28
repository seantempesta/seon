---
type: component
status: active
tags: [component, agent]
---

# Agent-reply segmenter (`parse-forms`)

> Turns an LLM reply — `;` comments interleaved with Clojure forms — into a
> vector of structured entries the pod's eval pipeline drives form-by-form.

`seon.repl.internal/parse-forms` is the boundary between what an agent *types*
and what the pod *runs*. It is pure rewrite-clj and lives in a `.cljc` file so
the JVM test loop (`bin/test-parser`) can exercise the whole corpus without
booting the CLJS pod — the agent eval-batch path runs in the pod, but the parse
contract is platform-agnostic.

## Namespace

| Namespace | File | Role |
|-----------|------|------|
| `seon.repl.internal` | `src/seon/repl/internal.cljc` | `parse-forms`, `form-source-at`, fence-strip, prose/form classification, read-error recovery + classification |

## The forms-and-prose-only cut (#50/#52)

A top-level read token becomes a `:kind :form` entry (EVALUATED) **iff it reads
as a LIST/SEQ** — `(…)` plus the reader-macros that sexpr to seqs (`@x`, `'x`,
`#(…)`, `#'x`). Everything else is **prose** and is **DROPPED, never echoed back
as a `;;` line** (that echo was the `;;`-imitation trap that taught agents to
write `;;` when they meant data). Prose covers bare atoms, a bare `=>`/`⇒` echo,
tagged literals (`#inst`/`#uuid`/`#js`/`#?(…)`, discriminated on the
`:reader-macro` tag), inline-backtick reader-macros (`` `(…) ``/`~x`/`~@x` —
always inline prose at the agent REPL, classified as prose to stop the
"backtick cascade"), and an unreadable prose token (`80s`, `to:`).

The **one** exception that does not silently vanish: a top-level DATA LITERAL
(`{…}`/`[…]`/`#{…}`) is dropped from eval but emits a single reactive
`demoted-literal-warning` (`:kind :comment`) so the agent learns to wrap a value
it means to run. Without this drop a fabricated `=> {…}` echo would
self-evaluate into a real `result/<id>` (#52).

## Entry shape

Each entry is one of `:form`, `:comment`, or `:read` (see the namespace
docstring for the full contract). Real `;`/`;;` comments are the taught
reasoning channel and attach as `:narration` to the following form; dropped
prose between a comment and its form does not break that attachment.

A `:read` (failed-parse) entry now carries re-noise / repair metadata:

- `:span [start end]` — ABSOLUTE char offsets of the bad span in the input, what
  a token-canvas re-noise step maps back to mask positions.
- `:error-kind` — the failure classified from rewrite-clj's (case-folded, fixed)
  wording: `:eof` / `:unmatched-delimiter` (SAFE, mechanically completable) ·
  `:odd-map` / `:bad-metadata` / `:invalid-token` / `:read` (UNSAFE — needs the
  agent or a lookup; `:invalid-token` is the embedding-lookup hook). Classifier
  matching is CLJC-portable (JVM and CLJS core throw different odd-map wording)
  and degrades unknown messages to `:read` rather than throwing.

## Per-form error isolation + recovery

A broken chunk does not halt the parse. On a read failure the scanner:

1. Classifies prose-vs-broken-code on a one-line span (`prose-failure?` +
   `opener-at-start?`): a throwing span whose trimmed first line does NOT start
   with `(` is prose → dropped, recover at the next newline. Only a `(`-opener
   start signals intended code.
2. For genuine broken code, records the bad span as a `:read` entry and recovers
   at the next column-0 anchor — **`(` or `;` ONLY** (PRONG 2; never `[`/`{`,
   which are almost always the inner body of the broken form above). This keeps
   one broken block as ONE honest `:read` span instead of shredding it into a
   bad head + N demoted-map comments + an orphan closer.
3. Drops a pure-closer recovery span (`closer-only?`, PRONG 1) — an orphan
   delimiter the unbalanced form upstream already shed, otherwise duplicate
   noise.

A top-level `#_` discard is `:uneval` — dropped like whitespace (its node has no
sexpr), so it no longer false-reads as a `:read` failure.

We do NOT auto-fix parens here — surfacing the failure clearly beats guessing.
The eval pipeline layers a best-effort parinfer repair ON TOP of a `:read`
entry.

## `form-source-at` — the one-node source extractor

`form-source-at` returns the byte-faithful source of the single top-level form
beginning at the first `(` at-or-after an offset, by reading exactly one
rewrite-clj node (the same one-node parse `try-parse-one-token` uses). It
replaced `seon.client`'s hand-rolled `(`/`)` depth counter, which truncated any
form containing a paren inside a CHARACTER literal (`\)`), REGEX literal
(`#"…)…"`), or string. `seon.client`'s program-graph source capture now calls it;
it falls back to the from-`(`-to-EOF substring on a genuinely unbalanced chunk
(the truncated-tail fallback the source-capture callers rely on).

## Verifying

- `bin/test-parser` — sub-second babashka loop over `seon.repl.internal` +
  `..._test` (no pod boot). The fast inner loop while iterating on the cut.
- Full `.cljc` suite via `bin/test-cljs` at the unit checkpoint.
- The `/repl` Claude Code skill (`.claude/skills/repl/`) teaches agents to write
  forms this parser lands on the first try (`(`-forms vs `;` prose vs `{}` data).

## Key files

- `src/seon/repl/internal.cljc`, `test/seon/repl/internal_test.cljc`
- `bin/test-parser`, `.claude/skills/repl/SKILL.md`
- consumer: `seon.client` (program-graph source capture) + the pod eval-batch path
