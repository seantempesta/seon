---
type: issue
status: open
severity: cleanup
tags: [issue, config, schema, class/n7, wave/config-application-contract]
---

# Declare the config-to-request ident route instead of string-building it

## Problem

`seon.ai` turns every `:seon.config.ai/*` dial into its `:seon.ai/*` request
field by rebuilding the name from the keyword's `name`, and decides membership
by comparing the keyword's `namespace` to the literal string
`"seon.config.ai"`. That is a naming convention standing in for a declared
fact, the defect ruling #47 names explicitly ("deriving a symbol by
string-building a name is a hand rule in disguise").

The cost is now measurable rather than theoretical. With keyword-usage edges
indexed (`:seon.fn/keywords`), "which function consumes this dial" is a query.
It answers correctly for 49 of 63 registered dials and reports eleven
`:seon.config.ai/*` dials as having no consumer, because no function names
them. They are live — they are simply applied through an undeclared rename, so
the program graph cannot see the edge.

## Evidence

- `src/seon/ai.clj:144-158` — `config-ai-ident->request-ident` builds
  `(keyword "seon.ai" (name config-ident))`, and `primary-setting-entries`
  selects dials with `(= "seon.config.ai" (namespace config-ident))`.
- Measured 2026-08-03 over `src/` with `seon.fn/build-manifest`: the dials with
  no literal consumer are `:seon.config.ai/` `api-key-variable`, `endpoint`,
  `extra-body-edn`, `frequency-penalty`, `max-tokens`, `presence-penalty`,
  `response-format`, `stop`, `temperature`, `timeout-ms`, `top-p`.
- The contrasting CORRECT pattern is one file away:
  `resources/seon/schemas/seon.config.shell.edn` declares
  `:seon.shell/environment "HOME"` in the dial's Malli properties, and
  `src/seon/shell/jvm.clj:77-87` reads that declared property. Its three dials
  (`home`, `lang`, `path`) are equally invisible to literal-keyword analysis,
  but the fact that makes them applied IS recorded and queryable.
- `test/seon/config_application_test.clj` therefore asserts application at
  config-family grain rather than per attribute. The weaker grain exists only
  because of this undeclared route.

## Acceptance criteria

- Each `:seon.config.ai/*` dial declares its request field in its own Malli
  properties (the `:seon.shell/environment` precedent), and
  `config-ai-ident->request-ident` plus `thinking-inert-settings` read the
  declaration rather than rebuilding or listing names.
- `seon.config-application-test/every-config-entry-has-an-honest-application-contract`
  tightens `unapplied-families` to per-attribute grain and stays green, so a
  genuinely dead AI dial fails the gate.
- No literal `"seon.config.ai"` namespace-string comparison remains in `src/`.
