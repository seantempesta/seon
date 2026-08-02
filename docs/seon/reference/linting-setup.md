---
type: reference
status: active
tags: [reference, agent]
---

# Linting reference

Seon retains one standalone Clojure lint command, `bin/lint`. It runs
clj-kondo and Splint over fresh `src/` and `test/` by default. The command does
not run tests, publish source facts, or replace the edit hook.

## Commands

```sh
bin/lint
bin/lint src/seon/render test/seon/render
bin/lint --kondo src
bin/lint --splint test
bin/lint --metrics src
bin/lint --fix src
```

`--kondo`, `--splint`, and `--metrics` are mutually exclusive. `--fix` invokes
Splint's autocorrection and cannot be combined with `--kondo`. Missing paths or
required executables fail before analysis. Run `bin/lint --help` for the exact
grammar.

The clj-kondo pass disables its cache and uses `.clj-kondo/config.edn`. When a
`.cljc` file is shadowed by a sibling `.cljs`, the command suppresses findings
from that dead CLJS branch in the main pass and performs a second CLJ-only pass
for the `.cljc` file. This rule is derived from the files supplied to the
command; it is not a namespace list.

The Splint pass runs version `1.22.0` through Babashka and reads
`.splint.edn`. `--metrics` selects only the configured metric rules, while
`--fix` passes `--autocorrect`. Any nonzero clj-kondo or Splint result makes
`bin/lint` nonzero.

## Edit feedback

`bin/seon-hook` is a separate editing boundary. Before a reconstructable
Clojure edit, it runs clj-kondo on the prospective content and refuses
error-level findings or an unavailable analysis. After an edit, it reports
the resulting files' clj-kondo findings as advisory feedback. It does not run
Splint or tests.

For `docs/**/*.md`, the same hook uses `seon.dev.markdown`: it applies safe
formatting fixes and reports remaining structural violations. The Markdown
contract is YAML frontmatter with valid `type`, `status`, and `tags`, one H1,
ATX headings without jumps, valid links, and no bare URLs.

Source publication has its own clj-kondo analysis in
`seon.fn.analyzer`. That analyzer retains `:type-mismatch` as warning context;
local inference is not a sound database-admission proof. Syntax, unresolved
name or namespace, privacy, and arity failures remain admission errors.

Tests are always explicit. Use `bin/test` for the fresh suite or
`seon.dev.changed-test/run-changed!` for the retained changed-test selector;
neither is queued by `bin/lint` or the edit hook.

## Sources checked

- `bin/lint` — command grammar, path checks, shadowed-`.cljc` handling, and
  exit behavior.
- `.clj-kondo/config.edn` and `.splint.edn` — current rule configuration.
- `bin/seon-hook` — prospective blocking, advisory feedback, and Markdown
  validation.
- `src/seon/fn/analyzer.clj` — source-publication analysis policy.
- `script/seon/dev/markdown.clj` — Markdown validation and safe fixes.
- `script/seon/dev/changed_test.clj` and `bin/test` — explicit test surfaces.
