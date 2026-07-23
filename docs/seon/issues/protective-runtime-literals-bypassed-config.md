---
type: issue
status: open
tags:
  - runtime
  - configuration
  - reliability
---

# Protective runtime literals bypassed config

## Evidence

Portable LLM retry waits, the shell request timeout, web fetch/search/parser
ceilings, and JVM claimant invocation limits were numeric source literals.
They therefore could not be inspected as immutable cluster facts, overridden
through the selected manifest, or named consistently when a limit was missing
or fired.

## Owner and acceptance

`seon.config.resolve` owns one closed section per concern and resolves every
default into the `:seon.config` singleton. Portable/JVM consumers acquire those
facts and carry no numeric fallback. Every leaf description records units,
default provenance, protected resource, and the key surfaced when the limit
fires. Focused CLJC tests prove default and override resolution.
