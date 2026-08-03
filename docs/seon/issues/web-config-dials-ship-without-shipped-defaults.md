---
type: issue
status: open
severity: blocker
tags: [issue, config, web, gate]
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
