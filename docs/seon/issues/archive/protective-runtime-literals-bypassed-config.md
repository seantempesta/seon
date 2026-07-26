---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, config, reliability]
---

# Protective runtime literals bypassed config

## Evidence

Portable LLM retry waits, the shell request timeout, web fetch/search/parser
ceilings, and cluster JVM invocation limits were numeric source literals.
They therefore could not be inspected as immutable cluster facts, overridden
through the selected manifest, or named consistently when a limit was missing
or fired.

## Owner and acceptance

`seon.config.resolve` owns one closed section per concern and resolves every
default into the `:seon.config` singleton. Portable/JVM consumers acquire those
facts and carry no numeric fallback. Every leaf description records units,
default provenance, protected resource, and the key surfaced when the limit
fires. Focused CLJC tests prove default and override resolution.

## Resolution

Commit `34f0373e8` registers and resolves the LLM retry, shell, web, and
cluster JVM invocation limits as singleton facts. Portable and JVM consumers
acquire those projections without numeric fallbacks. Focused config,
portable-core, shell-host, web-host, and durable-LLM gates pass 27 tests / 146
assertions.
