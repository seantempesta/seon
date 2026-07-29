---
type: issue
status: open
severity: blocker
tags: [issue, tooling, operator]
---

# `bin/seon status` cannot load Datahike through Babashka

## Problem

`bin/seon status` fails while loading `seon.dev.cli` — its Babashka path
cannot load `datahike.api`. `bin/seon-fresh status` works and reports
correctly. Every agent (and every skill or instruction that teaches
`bin/seon status`) hits this on the first command it runs, so it reads as
"the system is broken" when the system is fine.

## Evidence

```text
$ bin/seon status
seon.dev.cli   - /Users/sean/src/seon/script/seon/dev/cli.clj:3:3
user           - NO_SOURCE_PATH:1:10

$ bin/seon-fresh status
default   61316 alive   62125 http://127.0.0.1:7994
1/1 clusters alive
```

Confirmed independently by the `skills-independent-verify` lane while
trying to follow skill guidance (2026-07-29 evening).

Related: [[babashka-default-classpath-exposes-src-old]] — the hygiene lane
deliberately did NOT switch `bb.edn` to fresh-only because that breaks
maintained hook/operator consumers. This is the other half of that
dependency: the operator's Babashka entry cannot see the JVM-only
Datahike coordinate.

## Owner

The `bin/seon` Babashka entry and `script/seon/dev/cli.clj`'s load
requirements — either the status path stops needing `datahike.api` (it is
reading advertisements and process facts, which need no database), or the
operator entry runs on the JVM classpath like `bin/seon-fresh`.

## Acceptance

`bin/seon status` reports the same as `bin/seon-fresh status` on a clean
checkout, and the two entries are reconciled to one (the standing
one-mechanism rule) rather than left as a working and a broken twin.

## Note for skills and instructions

Until this is fixed, guidance must teach `bin/seon-fresh status`. A skill
teaching a broken command is the poison class the blast-radius law names.
