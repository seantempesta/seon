---
type: research
status: active
tags: [publication, performance, verification]
---

# Publication write-volume implementation — 2026-09-17

Implementation lane; observations taken on 2026-09-16 UTC. Default remained
PID 53320. No test JVM, cluster restart, foreign-session operation, or gate
was run. This note is the review boundary, not a cold-gate claim.

## Grounding and dependency ledger

Read end to end, in assignment order:

- `docs/prds/steward-platform/research/adoption-write-volume-2026-09-17.md`;
- `docs/seon/issues/development-adoption-refuses-an-unavailable-source-basis.md`;
- `docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md`
  §4b;
- the supplied `AGENTS.md` §§0–5 and 7;
- `tmp/orchestrator/wave2/repl-rule.txt`.

Applied the data-oriented-clojure, repl, clojure-testing, and datahike skills.
Read the complete source publication owner `src/seon/cluster/source.clj`,
the artifact/publication/adoption seams in `src/seon/cluster.clj:1866–2418`,
and the test execution/recording seam in `src/seon/test.clj:306–377`.
The existing fixture and regression owners are `test/seon/test_support.clj`
(`with-database`, `transacted!`), `test/seon/cluster/source_test.clj`, and
`test/seon/cluster/boot_test.clj`.

Dependency mechanisms read at their owning seams:

- Datahike expected-head publication: `reference-code/datahike/src/datahike/versioning.cljc:323–456`;
  `force-branch!` moves the already-written database head and verifies its
  expected predecessor. It does not transact the program again.
- Commit materialization and release:
  `reference-code/datahike/src/datahike/versioning.cljc:469–504` and
  `reference-code/datahike/src/datahike/api/impl.cljc:350–390`.
- Canonical manifest construction/replacement: `src/seon/fn.clj:1683–1788`;
  database reconciliation: `src/seon/fn.clj:2400–2468`.
- Source schema and activation shape:
  `resources/seon/schemas/seon.source.edn` and
  `resources/seon/schemas/seon.activation.edn`.

The requested `datahike.api/tx-range` does **not exist** in this checkout:
source search found no definition, and live `(ns-resolve 'datahike.api
'tx-range)` returned nil. Transaction evidence therefore uses the existing
historical datom interface, filtering `:tx` above the captured `:max-tx`.
It counts assertions and retractions, not elapsed time or current datoms.

## Commit slices

- A, `c847249b1`: catch only the typed `source-absent` refusal when loading
  the adopted basis; report its commit ID and use the live cluster database
  for reconciliation. The existing two-cluster acceptance regression now
  supplies an absent recorded basis and checks the adopted commit and
  rewritten artifact. No store deletion is used to arrange absence.
- B, `f614aff94`: derive head digest and indexed file digests from the
  published database. A manifest cache is validated by relative-path shape
  and source digest, not by a remembered commit ID. Complete analysis reuses
  an already matching publication and rewrites the cache. An old cache may
  require analysis; it cannot by itself require a new database publication.
- C1, `800b8758a`: empty results return before scratch acquisition and
  `force-branch!`. The existing evidence-preservation regression checks both
  head identity and transaction basis for the empty call. The actual owner
  is `cluster/source.clj`, not `seon.test/run`.
- C2, `11880700e`: unchanged source digest yields no seal transaction.
  Changed seals reuse their source and closure entity IDs; unchanged set
  members remain asserted, removed members retract, and old lookup
  components retire before their replacements. Empty scalar rows are not
  transacted. A scratch with no transaction does not move the source head.
  The seal operates on publication's private connection, so no concurrent
  writer can invalidate its derivation. The two-cluster acceptance test also
  checks a subsequent scalar edit: 1–2 source transactions, fewer than 2,000
  source datoms and fewer than 500 cluster datoms, with positive adoption
  and incremental-report assertions.
- `c89dc007d`: install the publication owner's source schema in the canonical
  seal fixture, propagate refused publication reads, and exclude internal
  file-digest maps from the outward publication report.
- `32867359c`: require an existing activation closure before taking the
  same-digest shortcut; test the canonical fixture's existing digest too.
  The updated in-process regression again returned 6/0/0.
- `b95e08db4`: also log the missing commit through Timbre, so callers without
  a progress observer cannot make the fallback silent.

Owned paths changed: `src/seon/cluster.clj`, `src/seon/cluster/source.clj`,
`test/seon/cluster/boot_test.clj`, `test/seon/cluster/source_test.clj`, this
note, and the assigned issue note. Protected files were not edited.

## In-process evidence

Definitions were evaluated through MCP JVM mode before their initial source
edits. Later adoption reloads were observed separately. Test namespaces were
reloaded through `seon.test/with-test-loader`; `seon.test-support` was never
reloaded. Runs used the three-argument owner, on a future:

```clojure
(let [c (seon.operator/connection "default")
      db (seon.db/db c)]
  (seon.test/run
   (#'seon.test/resolve-test 'seon.cluster.source-test/activation-seal-preserves-unchanged-facts)
   c
   {:seon.db/db db
    :seon.test.run/provenance (seon.test.runner/provenance db)
    :seon.test/remaining-ms 180000}))
```

- `incremental-source-refresh-includes-unreported-changes`: 5 pass, 0 fail,
  0 error at 18:51:03Z.
- `activation-seal-preserves-unchanged-facts`: initial fixture write correctly
  refused missing publication-only `:seon.source/built-at` storage schema.
  The fixture now installs the exact `source-attributes` through the existing
  schema bridge, as publication does. Rerun at 18:57:46Z: 6 pass, 0 fail,
  0 error. This proves unchanged seal = 0 transactions; changed seal = 1
  transaction and fewer than 2,000 historical datoms on the canonical fixture.
- Live empty `source/record-results!`: returned `[]`; before and after
  `source/current` were equal.
- Live published-head inspection: 337 indexed file digests; the digest is
  read from that head. The old artifact had already been rewritten by the
  time this probe ran.
- `seon.fn/tests-reaching` was queried for refresh, current publication,
  seal, upsert, and result recording. The existing C1 regression reaches
  file-backed store construction; the boot regression starts clusters.
  Neither was executed in process under this assignment.

## Live publication boundary

The first explicit command was:

```sh
bin/seon init --dev default --changed src/seon/cluster.clj
```

It reported:

```text
development source basis unavailable: 6aaad49e-a55e-52ad-981d-4df826c4bb8f; reconciling against the live cluster
development schema declarations
development program reconciliation
development JVM instrumentation
Source changed during adoption through the one retry; the next edit must converge it.
```

Thus A removed the measured refusal, but that attempt did not converge.
The source changed during the attempt, including this lane's ongoing edits;
it is not attributed solely to foreign work. A second explicit command uses
`--changed src/seon/cluster/source.clj`. Final results and transaction/object
measurements follow below when that boundary is observed.

Historical before numbers, from the assigned measurement note (not a new
measurement): 538,569 history datoms and approximately 950 objects / 130 MB
per complete rebuild; approximately 10,400 cluster datoms per adoption.

Cold verification is withheld for the orchestrator's diff review. No RESET
NEEDED: no key's meaning or stored schema changed in this slice.

At 19:07Z an all-thread JVM dump (including virtual threads) showed an
earlier adoption awaiting `seon.db/transact!`, the explicit command waiting
on the source publication monitor, and a writer executing
`seon.test.runner/record-tx → reach-memberships → reach-refresh`. The
transaction basis continued advancing: this is an observation of concurrent
work, not an attribution of a deadlock or a particular lane's failure.
The exact three stacks are retained in
`adoption-write-volume-fix-threads-2026-09-17.json`. The two completed
source-change refusals' reports are retained in the adjacent `-a-` and
`-converge-` logs (warning and lock-wait lines omitted).

The measurement forms are retained in
`adoption-write-volume-fix-probe-2026-09-17.clj`. Object counts compare
surviving `.ksv` files by path, modification time and size over the interval;
concurrent writers are included, and objects created and collected entirely
inside the interval cannot be counted by that filesystem observation.

The Markdown hook reported 29 existing historical gitlink-citation errors
outside this slice after this note's frontmatter was corrected. Those files
were not changed; they are not a verification result for this implementation.

## Completed live measurements and remaining boundary

The final convergence command exited zero, on unchanged PID 53320:

```text
development cluster converged
:current-src commit 6aaae98a-34bd-5072-aaed-472ca71e7fd6 digest bd51f4ab26eeca08b56f9670966a72673844708d7b2a0add1102d1143ebbba2c
```

Two one-line changes to the `source/digest` docstring were then made with
shell writes, followed immediately by the explicit owner command (so no
second edit-hook publication was requested by this lane). Both reported
`incremental scalar publication: 1 paths; reasons=()` and
`development cluster converged`. Final docstring cleanup is `dbe7f389e`.

| Observation | Source transactions | Source datoms | Cluster transactions | Cluster datoms | Observed changed objects | Observed bytes |
|---|---:|---:|---:|---:|---:|---:|
| Historical full rebuild, assigned note | 11 from empty base | 538,569 | — | ~10,400 | ~950 | ~130 MB |
| Edit 1, `6aaaea13-ffac-5c38-8aef-2396af843dba` | 3 | 351 | 14 | 38,145 | 1,454 | 113,757,355 |
| Edit 2, `6aaaeabe-d99a-5eec-9e3e-9d254e71c9c6` | 2 | 168 | 4 | 15,946 | 378 | 27,379,612 |

The source parent of edit 1 was independently loaded and matched the captured
baseline `6aaae98a-34bd-5072-aaed-472ca71e7fd6`; the three source transactions
were 56 program datoms, 183 issue-index datoms (181 `:seon.issue/issues`,
one opened date, one transaction instant), and 112 activation datoms.
Edit 2 was exactly 56 program + 112 activation datoms, satisfying the source
acceptance of at most two transactions and fewer than 2,000 datoms.

**The total cluster bound is still red, and is not claimed green.** Edit 2
wrote 15,860 `:seon.issue/*` datoms and 86 other datoms. The former are at
the protected `seon.issue/adopt!` owner (`src/seon/issue.clj:742–749` at this
observation), which this assignment explicitly excludes. Edit 1's interval
also includes 6,455 `:seon.test/reach` datoms and concurrent recording. Store
object counts are whole-interval observations, not source-only attribution;
the remaining issue writes dominate them. This slice removes the complete
source rebuild and closure rewrite, not the separately assigned issue churn.

Exact values are in `adoption-write-volume-fix-edit1-2026-09-17.edn` and
`adoption-write-volume-fix-edit2-2026-09-17.edn`; the latter includes each
source transaction's attribute counts. The adjacent `-edit1-`, `-edit2-`,
and `-final-converge-` logs retain the publication reports without analysis
warnings or operator-lock wait lines.

The post-adoption regression was attempted with the same 180000 ms
three-argument form, but **did not run**. The returned value is:

```clojure
{:seon.error/kind :user-input
 :seon.schema.edn/misplaced-attribute :seon.issue/turns-remaining
 :seon.schema.edn/expected-file "seon.issue.edn"
 :seon.schema.edn/file "file:/Users/sean/src/seon/resources/seon/schemas/seon.issue.status.edn"}
```

This is a later in-flight schema boundary, distinct from the earlier 6/0/0
in-process proof and the two successful live publications. No protected
schema was changed to bypass it. The complete successful and refused test
values are retained in `adoption-write-volume-fix-tests-2026-09-17.edn`.

Review must include the expanded seal delta, the missing-closure case, the
database-owned cache guard, and the cold two-cluster regression. The
orchestrator's requested gate remains `seon.cluster.boot-test`,
`seon.cluster.source-test`, and `seon.test-test`, after its diff-review note.
