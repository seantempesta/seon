---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, runtime]
---

# Base-projection flush rejected its EDN integer fingerprint

## Failure

The default client's Shadow flush stopped in
`seon.dev.program-artifact/publish-base-projection!` with “invalid base
projection,” although the compiled value was an ordinary EDN map.

## Root cause

The compiled CLJS fingerprint is printed as EDN and read by the JVM hook.
`edn/read-string` materializes that number as `java.lang.Long`, but the hook
used `int?`, which only accepts JVM `Integer`. The exception exposed the
projection map class rather than the failed field, obscuring the actual
representation-boundary mistake.

## Resolution

Resolved by `b28cadfee` and completed by `0e4b6234c`: the hook validates the numeric data contract with `integer?` and includes the
fingerprint and relevant value classes in a real failure. The regression sends
an EDN-materialized Long through the actual flush publication path.

The improved failure then exposed a second preparation gap: the release
`:optimize-prepare` stage derived the host base-load plan, while the ordinary
watch flush built only program rows. Both modes now use the same prepared
program derivation, so the strict publisher receives the plan instead of
weakening its nil refusal.
