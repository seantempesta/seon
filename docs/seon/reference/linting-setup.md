---
type: reference
status: active
tags: [reference, agent]
---
# Linting Setup

This project uses a layered linting approach with two complementary tools:

1. **clj-kondo** - Fast static analysis for bugs, unused code, arity errors
2. **Splint** - Style enforcement, complexity metrics, auto-fix capabilities

## Quick Start

```bash
# Run both linters on src/ and test/
./bin/lint

# Run only on specific paths
./bin/lint src/seon/dev

# Auto-fix safe issues
./bin/lint --fix src

# Check only complexity metrics
./bin/lint --metrics src

# Run only clj-kondo (bugs)
./bin/lint --kondo

# Run only Splint (style)
./bin/lint --splint

```

## Tool Comparison

| Feature | clj-kondo | Splint |
|---------|-----------|--------|
| **Speed** | Extremely fast (~400ms full scan) | Very fast (~180ms full scan) |
| **Primary Use** | Bug detection, unused code | Style, idioms, complexity |
| **Complexity Metrics** | No | Yes (fn-length, parameter-count) |
| **Auto-fix** | Via clojure-lsp | Native --autocorrect flag |
| **Configuration** | `.clj-kondo/config.edn` | `.splint.edn` |
| **Reference Code** | `reference-code/clj-kondo/` | `reference-code/splint/` |

## clj-kondo

### What It Catches

- Unresolved symbols/namespaces/vars (ERROR level)
- Invalid arity calls (ERROR level)
- Unused bindings, imports, namespaces (WARNING level)
- Redundant do/let expressions (WARNING level)
- Deprecated var usage (WARNING level)
- Shadowed variables (WARNING level)

### Configuration

Located at `.clj-kondo/config.edn`. Key settings:

```clojure
{:linters
 {:unresolved-symbol {:level :error}   ; Definite bugs
  :invalid-arity {:level :error}        ; Wrong number of args
  :unused-binding {:level :warning}     ; Probably a mistake
  :redundant-do {:level :warning}}}     ; Code smell

```

### Running Directly

```bash
# Lint entire src
clj-kondo --lint src

# Output as EDN for programmatic use
clj-kondo --lint src --config '{:output {:format :edn}}'

# Get analysis data
clj-kondo --lint src --config '{:output {:analysis true}}'

```

### Editor Integration

clj-kondo powers clojure-lsp, which integrates with:

- VS Code (Calva extension)
- Emacs (lsp-mode)
- Neovim (nvim-lspconfig)
- IntelliJ (Cursive with LSP)

The editor will show real-time linting as you type.

## Splint

### What It Catches

#### Metrics (Complexity)

- **metrics/fn-length** - Functions longer than threshold (default: 30 lines)
- **metrics/parameter-count** - Functions with too many parameters (default: 5)

#### Style (Idioms)

- `(= x 0)` -> `(zero? x)`
- `(= nil x)` -> `(nil? x)`
- `(not (= ...))` -> `(not= ...)`
- `(+ x 1)` -> `(inc x)`
- Many more from [Clojure Style Guide](https://guide.clojure.style)

#### Lint (Potential Bugs)

- Redundant calls
- Suspicious patterns
- Missing `*warn-on-reflection*`

### Configuration

Located at `.splint.edn`. Key settings:

```clojure
{;; Complexity thresholds
 metrics/fn-length {:enabled true :length 30}
 metrics/parameter-count {:enabled true :count 5}

 ;; Disable noisy rules
 lint/prefer-method-values {:enabled false}

 ;; Global exclusions
 global {:excludes ["target/" "reference-code/"]}}

```

### Running Directly

```bash
# Via Babashka (recommended, faster)
bb -Sdeps '{:deps {io.github.noahtheduke/splint {:mvn/version "1.22.0"}}}' \
   -m noahtheduke.splint src

# With auto-fix
bb -Sdeps '{:deps {io.github.noahtheduke/splint {:mvn/version "1.22.0"}}}' \
   -m noahtheduke.splint --autocorrect src

# Only check metrics
bb -Sdeps '{:deps {io.github.noahtheduke/splint {:mvn/version "1.22.0"}}}' \
   -m noahtheduke.splint --only metrics src

# Output as clj-kondo compatible format
bb -Sdeps '{:deps {io.github.noahtheduke/splint {:mvn/version "1.22.0"}}}' \
   -m noahtheduke.splint -o clj-kondo src

```

### Auto-fix Capabilities

Splint can automatically fix many style issues:

```bash
./bin/lint --fix src

```

The `--autocorrect` flag only applies "safe" fixes - transformations that are guaranteed to preserve behavior. Manual review is still recommended after auto-fixing.

**Safe fixes include:**

- `(= x 0)` -> `(zero? x)`
- `(= nil x)` -> `(nil? x)`
- `(not (= ...))` -> `(not= ...)`
- `(+ x 1)` -> `(inc x)`
- `(- x 1)` -> `(dec x)`

**NOT auto-fixed (require manual review):**

- Function length violations
- Parameter count violations
- Some lint warnings

## Severity Levels

### Blocking (Errors)

These issues should block commits in CI:

- clj-kondo `:error` level findings
- Splint `metrics/fn-length` when > 50 lines (configurable in hook)

### Warnings

These should be reviewed but don't block:

- clj-kondo `:warning` level findings
- Splint style violations
- Splint `metrics/fn-length` when 30-50 lines

### Info

Suggestions for improvement:

- clj-kondo `:info` level findings

## Hook Pipeline Integration

The linters can be integrated into the dev hook pipeline. See `docs/archive/unified-dev-hook/phase-10-linting.md` for the integration plan.

Design notes for hook integration:

1. **Run Order**: Linting should run AFTER repair but BEFORE tests
2. **Blocking**: Only block on actual errors, not style warnings
3. **Caching**: clj-kondo can use its analysis cache for speed
4. **Incremental**: Only lint changed files in the hook

## Troubleshooting

### "Splint fails with 'No such var: e/continue'"

This happens when running Splint via `clj` instead of `bb`. Splint requires Babashka >= 1.12.205 or has compatibility issues with some Clojure versions.

**Solution**: Always use Babashka:

```bash
bb -Sdeps '{:deps {io.github.noahtheduke/splint {:mvn/version "1.22.0"}}}' -m noahtheduke.splint src

```

### "clj-kondo shows unresolved symbol for valid code"

clj-kondo may not recognize macros from third-party libraries. Add hooks:

```clojure
;; .clj-kondo/config.edn
{:hooks {:analyze-call {my-lib/my-macro my-hooks/my-macro}}}

```

Or import configs from libraries:

```bash
clj-kondo --copy-configs --dependencies --lint "$(clj -Spath)"

```

### "Too many style warnings"

Disable noisy rules in `.splint.edn`:

```clojure
{lint/prefer-method-values {:enabled false}  ; Clojure 1.12+ only
 style/prefer-clj-math {:enabled false}}     ; If using Java Math

```

## Reference

- clj-kondo source: `reference-code/clj-kondo/`
- Splint source: `reference-code/splint/`
- [clj-kondo docs](https://github.com/clj-kondo/clj-kondo/tree/master/doc)
- [Splint docs](https://github.com/NoahTheDuke/splint/tree/main/docs)
- [Clojure Style Guide](https://guide.clojure.style)
