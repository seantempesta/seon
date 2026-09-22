---
type: landing
status: landed
created: 2026-09-22
tags: [agent-platform, b3, search, deletion]
---

# B3 commit 14 — search deletion

## Slice

Commit: `434c01f4c` (`Delete the derived search subsystem`). The commit contains
20 files, 119 insertions and 943 deletions.

`seon.search` is deleted whole: the Lucene directory under `derived/lucene`, its
handle, atoms and lock, `index-step`, the cluster Flow proc, transaction-listener
route, boot open/close lifecycle, environment member, schema resource and machinery
tests. `tokens` and `similar-identities`, including their Malli contracts, moved
unchanged to `seon.schema.admission`, beside the sole surviving caller. Admission
tests now exercise both token normalization and the public name-overlap finding.

All `:seon.search/index` schema properties and the two error-union references were
removed because the deleted search roster/query was their only reader. The
`:seon.config.web/max-search-results` dial remains: `src/seon/web/jvm.clj:406-407`
still reads it, and `test/seon/web/jvm_test.clj:191` covers that independent web
result bound.

## Line delta by file

Principal search-slice deltas from `git show --numstat 434c01f4c` are:

- `src/seon/search.clj`: −573
- `resources/seon/schemas/seon.search.edn`: −76
- `test/seon/search_test.clj`: −218
- `src/seon/schema/admission.clj`: +46/−2
- `test/seon/schema/admission_test.clj`: +24
- `src/seon/cluster.clj`: +6/−30
- `src/seon/cluster/boot.clj`: +3/−9
- `src/seon/cluster/wake.clj`: −12
- `test/seon/instrument_test.clj`: +1/−1

The commit also carries the coherent `constructor-repair` remainder authorized by
the owner and backed by that lane's 53 passing isolated assertions:

- `src/seon/cluster/boot.clj`: diagnostic disposition contract and value
- `resources/seon/schemas/seon.cluster.boot.edn`: disposition and operation-error declarations
- `resources/seon/schemas/seon.db.edn`: owned-value and diff-refusal declarations
- `src/seon/schema.clj`: render-contract cause and dead refusal require removal
- `resources/seon/schemas/seon.schema.edn`: render-contract cause declaration

## Running-system before evidence

Surface: MCP `eval_clj`, JVM mode, `default`, read-only, root
`/Users/sean/src/seon`.

```clojure
(let [instance (#'seon.cluster/mcp-instance "default")]
  (->> (seon.oversight/flow-status
        (seon.db/db (:seon.boot/cluster-connection instance))
        instance)
       :seon.oversight/plumbing
       (map :seon.oversight/proc)
       (filter #{:seon.search/index})
       vec))
```

Returned `["seon.search/index"]` in 3 ms. `runtime_status` independently reported
the same proc with the `seon.search/transactions` input buffer and a healthy reply.

## Source and test evidence

Before commit, the required complete working-tree load exited zero:

```sh
clojure -M -e "(require 'seon.cluster 'seon.cluster.boot 'seon.schema.admission 'seon.db)"
```

Completed successfully. A direct from-source derivation returned:

```clojure
(seon.schema.admission/similar-identities
 :invoice.line/item-count
 [:invoice.item/count :unrelated.namespace/value]
 3)
;; => [#:seon.schema.admission{:similar-key :invoice.item/count,
;;                              :shared-tokens 3}]
```

The required focused command was attempted with every changed path:

```sh
bin/test-fast --paths <search-deletion paths> -- seon.schema.admission-test
```

With the carried `seon.cluster.boot` resource included, the focused runner armed
1,684 Vars and executed six tests. The two new surviving-behavior tests passed three
assertions. The namespace as a whole was red: older fixture tests encountered the
active B4 boundaries (transaction-report validation refusals and a cached manifest
missing `:seon.program/definition-digest`), producing 1 failure and 7 errors. Result
recording then refused the conflicting report. This is not claimed green; no B4 file
was edited.

`test/seon/instrument_test.clj` held no foreign diff before the authorized edit, so
its synthetic `:seon.search/index` property was removed in `434c01f4c`.
`test/seon/issue_test.clj` still names the retired test as historical issue-fixture
data, `test/seon/schema_test.clj` retains the historical predicate incident in prose,
and the generated parity snapshot records the previous population; they were outside
the authorized final edit.

An archive made directly from committed HEAD `434c01f4c`, with the repository's
vendored `reference-code` linked in, ran the same four-namespace require from the
archive directory and exited zero. Archive path during verification:
`/tmp/seon-search-head.KTSqXC`.

## Limits

Hook publication is paused. No reload, adoption, stop, refork, reset or restart was
performed. Therefore the live `default` JVM correctly continues to run the old graph
and still lists `:seon.search/index`. The after-proof is limited to the successful
from-source and archived-HEAD loads plus the executed focused tests described above.
A rebuilt graph or fresh orchestrator boot is required to observe proc absence at
runtime.

## Follow-up: missed configured supplier

Commit `62487dbc3` closes the retirement miss from `434c01f4c`. The default
configuration still declared `:seon.search/handle` with supplier
`seon.search/supplied-handle`, so a from-zero population could pass schema loading
and then refuse the dangling call-preparation row. The supplier row was deleted;
there is no replacement for a resource that no longer exists.

The exact required sweep now returns zero:

```sh
rg seon.search config/ resources/ src/ script/ bin/ test/ .claude/
```

The same correction removes the retired search-test assertion, generalizes the
historical predicate incident text, and regenerates the Datahike parity population
from the canonical packaged forms. Commit delta: four files, two insertions and ten
deletions.

The working tree and an archive of committed `62487dbc3` both loaded
`seon.cluster`, `seon.cluster.boot`, `seon.schema.admission`, and `seon.db` with exit
zero. The archive path was `/tmp/seon-search-config-proof.6RvcDh`.

The requested isolated reset command was executed three times against only
`tmp/search-config-root`:

```sh
bin/seon --root tmp/search-config-root reset --force
```

The plain archive attempt refused because boot requires Git metadata. A committed
Git snapshot and the shared tree both proceeded through source population and then
refused while seeding `my.agents.root`: its namespace value lacked the newly required
`:seon.program/definition-digest`. Repeating on `d26fa4bf1` plus only the supplier-row
deletion reached the same later schema-retirement boundary. Each failed JVM exited;
`status` reported no live exact-root JVM. This is not a healthy-boot pass and no
readiness time is claimed. The failing owner is the held schema-retirement slice in
`src/seon/cluster.clj`/`src/seon/fn.clj`/`src/seon/db.clj`, which this lane did not
edit. `default` was never addressed.
