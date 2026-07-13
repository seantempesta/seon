---
name: repl
description: "How the Seon REPL reads, repairs, and evaluates the Clojure forms you write. Use when an eval fails to parse, when your form comes back as a :read error, when a value you typed didn't run, when you're unsure whether to write a `(` form vs `;` prose vs a `{}` map, when a paren/bracket is unbalanced, or when you want your forms to land correctly on the first try."
---

# REPL — how your forms are read, repaired, and run

Every reply you write is split into top-level forms by a **real reader**
(rewrite-clj), not string-splitting. Knowing how it segments and repairs your
text lets you write forms that land on the first try. The segmenter is
`seon.repl.internal/parse-forms`; the repair layer is `seon.repair`.

## What EVALUATES vs what is DROPPED (forms-and-prose-only)

The reader keeps exactly one thing as a runnable form: a **list** `(…)` (and the
list-shaped reader macros `'x` `@x` `#(…)` `` `(…) `` `#'x`). Everything else is
prose.

- `(db/transact! …)` `(message/user "hi")` `(let [x 1] …)` → **evaluated**.
- A bare atom / number / string / keyword / sentence → **dropped** (not echoed).
- A top-level **data literal** `{…}` / `[…]` / `#{…}` → **dropped** with one
  warning. If you mean to RUN a value, wrap it in a call: `(db/transact! :seon
  [{…}])`, not a bare `{…}`.

## `#code` — raw foreign-source blocks with ZERO escaping

To pass a chunk of another language (Python, Rust, Go, YAML, a diff…) as data
WITHOUT escaping its quotes and backslashes, write a `#code` heredoc:

```
(seon.agent.fs/replace!
  {:seon.agent.fs/path "app.py"
   :seon.agent.fs/find #code/python <<PY
def f(x):
    """Docs with "quotes" and \d regexes."""
    return x
PY
   :seon.agent.fs/replace #code/python <<PY
def f(x):
    return x + 1
PY
   })
```

- Opener `#code/<lang> <<SENTINEL`, then the payload on the next lines, then a
  line that is **exactly** `SENTINEL` (you pick the word — any `[A-Za-z0-9_-]+`
  that won't appear alone on a line in your payload). It reads to the inert
  value `{:seon.code/lang :python :seon.code/text "…"}` — DATA, never run as
  Clojure. The text is byte-faithful: no quote/backslash escaping, ever.
- Use it **nested inside a call form** (an argument or map value), as above —
  that's the point. A bare top-level `#code` is a lone value and gets dropped
  like any bare literal.
- Forget the closing `SENTINEL` line and you get a `:read` error naming the
  sentinel it's still waiting for — fix it the same way you'd close a paren.

**Comment levels carry meaning** (your context renders as eval'able Clojure):
`;` = prose to your human, `;;` = a code comment above a form, `;;;` = runtime
structure (don't author these). Write your reasoning as `;` lines — never type
`;;` when you actually mean data.

## What the REPL AUTO-FIXES for you (don't sweat these)

A delimiter mistake is repaired in place via parinfer indent-mode and then
re-validated before it runs — so these recover automatically:

- a **missing trailing closer**: `(defn mean [xs] (/ (reduce + xs) (count xs))`
  → the `)` is added.
- a **surplus closer**: `(foo))` → the extra `)` is dropped.
- a **wrong closer type** indent makes obvious: `]` where a `)` was meant.

Keep your indentation honest — it's the signal parinfer uses. A pure orphan
closer on its own line (`}` / `]`) is treated as recovery noise and dropped, so
one stray delimiter never shreds your whole block.

## What the REPL does NOT guess — you must fix these

When the fix would require guessing your intent, the form comes back as a
`:read` error showing your broken text, and you correct it next turn. These are
NOT auto-repaired:

- **invalid token**: `3x`, a lone `:` — `(map inc [1 2 3x])` is wrong; did you
  mean `3`? `30`? The REPL won't guess.
- **odd map**: `{:a 1 :b}` — a value is missing; only you know what.
- **bad metadata**: `^123 (foo)` — metadata must be a map/keyword/symbol/string.

These are exactly the cases where checking a fn/arg name against the codebase
(query the program graph) before re-writing pays off — the reader can flag the
shape, but not the right name.

## How a failure reaches you

A broken form records a `:read` eval whose value is your own text plus the
parser message — you see it on your next turn's transcript and self-correct.
Forms BEFORE and AFTER a broken one still run; one mistake doesn't abort the
batch. An incomplete final form (EOF mid-form, e.g. `(db/transact! :seon [{:a
1}` with no closer) is ONE honest error, not a cascade.

## Write forms that land

1. **One form, then read the result.** An eval returns the envelope; read it —
   an eval can succeed yet carry `:seon.db/ok? false`. Don't batch ten forms
   blind.
2. **Balanced delimiters matter less than honest indentation** — parinfer closes
   what your indentation implies, but a misleading indent fixes the wrong thing.
3. **Wrap values you mean to run** — a bare `{…}` is dropped; `(do {…})` or the
   call that consumes it runs.
4. **Reasoning is `;` prose**, data is data — never `;;`-disguise a value.
