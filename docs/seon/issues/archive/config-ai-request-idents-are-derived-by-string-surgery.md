---
type: issue
status: resolved
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

## N7 implementation — 2026-09-15

`seon.ai/request-attributes` queries `:seon.ai/request-attribute` properties. Every existing AI dial records its exact request key; `no-auth` explicitly retains its original key. The name-building helper and namespace classifier are deleted. Wire settings use the same routes, and per-dial `:seon.ai/inert-when-thinking` replaces the executable inert-settings set without changing the shipped policy. The canonical regression proves a non-family dial routes to a request field and a similarly spelled nonmember is excluded. The config application census is now per attribute. Gate and live results are recorded in the landing note before closure.

## Resolved — 2026-09-15

Commit `5deb40e4e`. The isolated owner gate passed 82 tests / 418 assertions;
the separate platform gate passed 86 tests / 542 assertions. The default JVM
query returned 18 declared routes; a synthetic non-family dial routed to
temperature while the spelled-alike nonmember was excluded. A live wire
projection marked temperature inert in high thinking mode and omitted its
wire field. No provider call was made. Exact probes and the broader baseline
failures are in [the landing note](../../../prds/context-generation/research/n7-query-classification-2026-09-15.md).
