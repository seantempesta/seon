---
type: issue
status: open
severity: blocker
tags: [issue, boot, schema, cluster, class/absence-as-health]
---

# A sovereign schema refusal is replaced by an opaque :malli.core/invalid-schema

## What was observed

Gate batch 68 (HEAD `d090c9934`, cold `bin/test`, 2026-09-16) ran
`seon.cluster.boot-test/incompatible-sovereign-schema-refusal-steers-the-operator`.
`cluster/start!` against the seeded sovereign legacy store still refuses with
`:seon.error/kind :seon.boot/refused` and still carries the cluster identity and
the degraded instance, but:

- the wrapped message is exactly
  `"The cluster instance failed above the REPL: :malli.core/invalid-schema"`
  (`src/seon/cluster.clj:3418` wrapping the inner failure's `ex-message`), and
- no cause in the chain carries `:seon.boot/offense` with
  `:seon.boot/attribute :seon.ns/requires`.

So the operator's ruled steer — the incompatible-declaration message naming the
property, both values, `bin/seon init NAME --force`, and export/import
(`src/seon/cluster.clj:942-954`) — is never produced. Something above
`declaration-changes` (`src/seon/cluster.clj:1020`) refuses the sovereign
population first, with a Malli form-compilation failure whose whole message is
its type keyword.

## Why it matters twice

1. The declaration comparison the test exists to prove is not reached, so the
   cluster-reopen refusal path has no live proof.
2. `:malli.core/invalid-schema` with no named identity is an
   evidence-free diagnostic at exactly the seam whose job is to steer a human
   operator (AGENTS.md §2.4). The refusal must name the offending schema
   identity and its form, whatever the cause turns out to be.

## What is NOT yet established

Which form fails to compile, and against which population. A naive merge of
every `resources/seon/schemas/*.edn` file compiles all but the six
runtime-derived config composites (`:seon.config/manifest`,
`:seon.config/effective`, `:seon.config/entity`, `:seon.config/agent-overlay`,
`:seon.config/compiled`, `:seon.config/apply-request`, `:seon.boot/start-request`,
`my.agent/settings`, `seon.ai/settings` — derived by
`seon.schema.edn/derive-config-forms`, `src/seon/schema/edn.clj:67`), which is
expected and not by itself the cause. Pinning it needs a live boot over a
seeded legacy store (the boot-test fixture's `seed-incompatible-sovereign!`,
`test/seon/cluster/boot_test.clj:180`), which the triage thread was not
permitted to run.

Suspects to falsify first, newest last: `a3cbcd9a8` (the population carries its
own projection; declarations compared on the union of both facets),
`0b910eb69` (per-facet accretive acceptance), `bb46455fb`.

## Adjacent, already fixed

The same test also expected the word "predates", which `0b910eb69` deliberately
replaced; that expectation was updated in `d427728d7`. It is not the cause of
this red.
