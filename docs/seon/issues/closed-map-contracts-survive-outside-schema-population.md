---
type: issue
status: open
severity: blocker
tags: [issue, schema, agent, documentation]
---

# Remove closed map contracts outside the canonical schema population

## Problem

Owner ruling #48 applies to function arguments and entity/value shapes alike,
but the bounded schema-population wave cannot finish the repository-wide rule.
After opening `resources/seon/schema.edn` and the `seon.schema` owner, 34
`:closed true` properties remain under `src/`. They include public function
contracts in bootstrap, run custody, config, indexing, rendering, and SCI
reading.

The bootstrap is actively wrong rather than merely old source style:
`resources/seon/bootstrap.edn:19-22` tells agents that input maps must declare
`{:closed true}`, its worked repair at lines 54-59 adds closed input and output
maps, and `src/seon/bootstrap_drive.clj:260-270` grades the now-deleted
`:seon.schema/open-argument-map` refusal. The data-modeling skill also still
describes the three derived config composites as closed.

## Evidence

`rg -F ':closed true' src` reports 34 occurrences after the canonical
population reaches zero. `rg` reports three more in
`resources/seon/bootstrap.edn`. The bootstrap regression at
`test/seon/bootstrap_test.clj:75-78` asserts that the repair adds the property,
and `test/seon/bootstrap_drive_test.clj:7-18` supplies another closed contract.

The 2026-08-04 isolated dogfood pass confirmed that this is the first guidance
a newly created agent actually receives. `(help)` rendered this current face:

```text
The contract is checked, so write it honestly: input maps must say
{:closed true}, and a return may not be a bare [:maybe ...].
```

The very next worked definition used an open map and was admitted, returning
the new Var face. The instruction therefore contradicts both the owner ruling
and the behavior in the same bootstrap run.

## Owner

The public contract owner in each namespace, plus the one bootstrap plan and
its evaluation grader.

## Acceptance

Every live first-party function and value contract is open unless a later
owner ruling names an exception. The bootstrap teaches and demonstrates
accretion, its grader proves an open argument map publishes successfully, and
the data-modeling skill cites current open derived config forms. A full
`rg -F ':closed true'` census contains only historical archaeology or a
deliberate regression fixture that constructs the former behavior explicitly.
