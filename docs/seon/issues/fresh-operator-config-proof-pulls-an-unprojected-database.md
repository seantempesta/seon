---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, test, config, schema]
---

# Operator lifecycle proof compares projection refusals as entity values

Batch 30's `seon.dev.fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle`
failed at `test/seon/dev/fresh_operator_test.clj:1145` and `:1160`.
Both remote forms call `seon.db/pull` with raw `@connection` (`:1154`,
`:1169`). On default PID 53378 those exact reads return:

```clojure
{:seon.error/kind :seon.schema/missing-projection
 :seon.error/message "This operation requires a carried schema projection."
 :seon.error/data {:seon.db/operation seon.db/pull
                   :seon.schema/missing-projection true}}
```

The config assertion selects expected config keys from this error map,
obtains an empty map, and compares it with the 77-key desired row. The
adoption assertion reads a commit ID from the error map, obtaining nil.
Neither comparison diagnoses the refusal it actually received.

Changing only the read input in a read-only probe to
`(seon.db/db connection)` (`src/seon/db.clj:1398`) succeeds with the same
`[:*]` selector. All shipped decision values are present. Default's sole
remaining config difference is the recorded manifest digest, which exactly
matches the old effective config with the removed blob-budget dial. Default
had not been config-applied by this investigation. Its source head was also
ahead of its adopted commit during concurrent publication; that separate
live state is not proof of the cold test's adoption state.

The two raw-pull expressions and `projection-fallback`'s typed refusal
(`src/seon/db.clj:966`) are present in `5a10f5dfa^`, before the retention
removal. No config decision is missing from the correctly obtained entity,
and the removed dial is absent from the desired row. This establishes a
pre-existing read-custody failure, not a lost write caused by retention.
The cold retained root was not reopened and the long test was not rerun:
the lane assignment explicitly required stopping on a cause outside its change.

Owner: operator lifecycle proof and explicit database-value custody. Preserve
the equality assertions; establish valid read inputs and expose any read
refusal before comparing config or adoption state. A following lane should
run the declared-long test through the in-process test loader, then the cold
operator gate, and inspect any residual comparison difference.

Exact forms and map differences are in the
[retention landing note](../../prds/context-generation/research/retention-sweep-2026-09-16.md),
section “Batch 30 follow-up”.
