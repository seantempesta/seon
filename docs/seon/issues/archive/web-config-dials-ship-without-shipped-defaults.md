---
type: issue
status: resolved
severity: blocker
tags: [issue, config, web]
---

# Eight `:seon.config.web/*` dials are registered with no shipped default

## Problem

`resources/seon/schemas/seon.config.web.edn` (landed in `4824631b7`, "Declare
my.web capability contracts") registers eight config dials that
`config/default.edn` does not decide. Every cluster start therefore refuses:

```
Configuration refused: missing-default.
{:seon.config/rule :seon.config/missing-default
 :seon.config/explanation
 {:seon.config/missing #{:seon.config.web/max-search-results
                         :seon.config.web/max-redirects
                         :seon.config.web/search-result-projection
                         :seon.config.web/search-api-key-variable
                         :seon.config.web/max-inline-bytes
                         :seon.config.web/search-endpoint
                         :seon.config.web/max-response-bytes
                         :seon.config.web/timeout-ms}}}
```

`config/default.edn:204` carries only `:seon.config.web/port`.

## Evidence

- `bin/test seon.config-application-test` on `b0283c089`: 2 errors, both from
  `seon.config$refuse!` (`src/seon/config.clj:97`) in the two `^:long` tests
  that start a real cluster — `applied-values-shape-the-running-system` and
  `no-auth-is-consumed-as-the-credential-alternative`. The non-long assertions
  in that namespace pass.
- This is committed state, not an in-flight edit: `git log` attributes the
  schema file to `4824631b7`.

## Why the existing proof did not catch it

`seon.config-application-test/every-config-entry-has-an-honest-application-contract`
compares its declared update modes against the keys of `config/default.edn`,
so a dial registered WITHOUT a shipped default is invisible to it — the two
sets simply agree on a smaller universe. The assertion should read the dial
registry (`:seon.config/dial` schema properties), which is the fact that
`config/apply!` itself enforces.

## Acceptance criteria

- `config/default.edn` decides all eight dials, or the registrations are
  marked `:seon.config/optional` the way the shell credentials are.
- The application-contract test compares against the dial registry rather than
  the shipped manifest, so the next dial registered without a default fails a
  fast test instead of every cluster start.

## Recurrence and ugly output, 2026-08-04

The universal-output-floor lane registered four agent render-profile dials
before its default commit. Fresh boots correctly refused all four. Commit
`b40a43f47` immediately supplied the complete decisions: 1,024 estimated
tokens, depth 8, 32 children, and multiline composition.

The refusal's one-line face was only `Configuration refused:
missing-default.` The missing attributes existed in report data but were not
named in the visible face, so operators had to inspect the EDN report to learn
what blocked boot. That is a remaining ugly-output defect: the concise refusal
must name the missing keys directly while retaining the structured report.

## Current recurrence — 2026-08-10

The eight keys now have shipped defaults, but the search projection default is
not applicable. While creating the independent `v3-scratch` recovery probe,

```bash
bin/seon config apply v3-scratch config/default.edn
```

failed at compile time with:

```text
var: seon.web.search/organic-results is not public
```

The mismatch is direct: `config/default.edn:282` supplies the symbol
`seon.web.search/organic-results`, while `src/seon/web/search.clj:23` declares
it with `defn-`. The generated prepl form resolves that symbol from `user`, so
Clojure's privacy check refuses before `seon.config/apply!` runs. This is
committed state, not a dirty source edit.

The acceptance boundary now includes a live `config apply` of the shipped
manifest into a new named cluster. The selected projection must be a resolvable
declared function at that boundary; a shipped default that cannot be resolved
is not a default the system can apply.

## Closure — 2026-08-13

All the cited `:seon.config.web/*` dials now ship decided defaults in `config/default.edn:259-282` (verified 2026-08-13); the refusal premise is gone.
