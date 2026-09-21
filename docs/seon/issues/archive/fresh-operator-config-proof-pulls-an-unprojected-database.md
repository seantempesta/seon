---
type: issue
status: resolved
severity: friction
created: 2026-09-16
resolved: 2026-09-16
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

## Resolution (2026-09-16)

Both prepl forms in
`seon.dev.fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle`
now read through `(seon.db/db connection#)`, so the database value carries its
projection to `seon.db/pull` (AGENTS.md §2.1). Two call sites changed; no
second fallback was added. The equality assertions are preserved, and each
form now returns a diagnostic map instead of a bare `false` when the
comparison fails, so a read refusal or a key difference is visible in the
failure output rather than hidden behind `"true"` versus `"false"`.

The file's two remaining raw `@connection#` derefs (the `datahike.api/q`
marker and agent counts) are direct dependency calls outside `seon.db`'s
projection contract and are correct as written.

Live evidence on `default` (JVM mode, `(seon.operator/connection "default")`):

- raw `@connection` pull of `[:seon.config/cluster "default"]` returns
  `{:seon.error/kind :seon.schema/missing-projection ...}` with the
  `seon.db/projection-fallback` warning, and the cluster pull's
  `:seon.source/commit-id` reads as `nil`;
- the same reads through `(seon.db/db connection)` return the real entity —
  79 keys, 76 of the 77 desired config keys equal, the sole difference being
  `:seon.config/applied-manifest-digest` because this live cluster was not
  config-applied by the probe (the test applies `config/default.edn`
  immediately before its comparison) — and a real
  `:seon.source/commit-id`.

The declared-long test itself was not run here (110 s, forks operator roots);
the orchestrator's batched gate is its proof.
