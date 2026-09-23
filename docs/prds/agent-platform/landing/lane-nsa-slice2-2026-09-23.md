---
type: landing
status: landed
lane: nsa-slice2
plan: docs/prds/agent-platform/plan/lane-namespace-agents-first-loop.md (slice 2, "Change-scoped comparison")
created: 2026-09-23
---

# Slice 2: change-scoped input for the one three-way comparator

`three-way` (d8734f1e7) is unchanged and remains the one comparator. Its
input, `digest-map`, gains an optional `:seon.program/identities` set. The
set comes from the new `changed-identities`. The caller composes them as
the regression does:

```clojure
(let [scope (into (program/changed-identities base branch bound)
                  (program/changed-identities base head bound))]
  (apply program/three-way
         (map #(program/digest-map % (assoc bound :seon.program/identities scope))
              [base branch head])))
```

## Seams used (Datahike gitlink `131ca6360d09762aa509a72712005fd396264574`)

| Seam | Guarantee used |
|---|---|
| `seon.db/history`, `seon.db/since` → `db.cljc:685` `SinceDB` + `db/search.cljc:228` `search-temporal-indices` | since-history holds every assertion and retraction after a basis, including retractions of deleted entities |
| index layout `db.cljc:307` `DB` record (EAVT/AEVT/AVET + temporal, tx last) | **no index has a transaction prefix**, so finding "changed since B" walks the digest attribute's history range |
| row schemas `seon.fn.edn:134`, `seon.test.edn:88`, `seon.ns.edn:17`, `seon.schema.edn:102` require `:seon.program/definition-digest` | every committed addition, replacement or deletion of a compared declaration asserts or retracts a digest, so scanning that one attribute finds them all |
| `digest-map` family classification (d8734f1e7) | unclassified/outside families still refuse before any identity read |

## Algorithm and cost

- `changed-identities`: one since-history scan of `:seon.program/definition-digest`.
  This is O(H_digest), the attribute's history (≥ P), at a small constant. Then
  one history join per changed entity, O(k log P), gives every identity the
  entity ever held, so a deleted declaration keeps its identity. It refuses by
  name on missing history (`:seon.program/history`), more than `max-datoms`
  changed entities (`:read-bound`), a changed digest outside every compared
  family (`:family-classification`), and any changed family other than
  `:seon.fn/sym`/`:seon.test/sym` (`:scope`). That last refusal is the first
  loop's restriction on ns and schema bindings.
- `digest-map` with identities: two queries per value, O(k log P). An
  identity lookup is followed by a digest `get-else`. It refuses by name on a
  present declaration without a digest. Joining the relation binding to the
  digest clause in ONE query planned a scan: 982 ms against 26 + 22 ms for
  1,000 identities (measured, default JVM).
- The simplest alternative is three full `digest-map`s. Each costs O(P),
  210–238 ms warm with P = 10,696. A truly O(k) finder needs a structural diff of two
  content-addressed index trees (`datahike/index/persistent_set.cljc:253`
  `branch-content-uuid`). Datahike does not supply one. It would be a fork
  change well over this slice's budget. **Verification limit: the change scan is
  proportional to the digest attribute's history, not only to k.**

## Proof

Regression `seon.program-test/changed-identities-scope-the-digest-maps-to-one-change`
covers replacement (changed-on-branch / changed-on-head), addition, deletion
(identity resolved through history), equal revert (head b then back to a →
unchanged), conflict, an unchanged row never read, the out-of-scope ns change
(`:scope`), the datom bound (`:read-bound`), a retracted digest (`:definition-digest`)
and an unretained database (`:history`).

**Verification gap (orchestrator correction, 2026-09-23: prove only on default's
JVM with `bin/test-check`).** Default (pid 81369) runs the committed archive
`42870700541256963a291be532d6692cddbf64d9` with hook publication OFF, so neither this
uncommitted edit nor its commit reaches that path until the orchestrator adopts a
commit carrying it. Then run
`nice -n 20 taskpolicy -b bin/test-check --test seon.program-test/changed-identities-scope-the-digest-maps-to-one-change`.
The evidence below was gathered before the correction, and that JVM has exited.

Run: plain JVM on a `git archive` snapshot of HEAD with my three files
applied (`tmp/nsa-slice2/snap`, reference-code symlinked). Contracts were armed
by `seon.test.arm/arm-contracts!` with the packaged projection:
`instrumented= 1838`, mode `:panic`. Result for 7 vars (the regression plus the
existing digest-map/three-way/family tests; the regression ran twice):
`{:test 7, :pass 153, :fail 0, :error 0}`. Contracts compile from zero: the
`changed-identities` and `digest-map` `:malli/schema`s compile against
`(schema/build-projection (schema.edn/packaged-forms) {})` → `true`. The new
enum members validate there.

Found and fixed on the way: `digest-map`'s index-page refusal put a refused
read under `:seon.error/cause`. That key is a `:seon.db/ref`
(`seon.error.edn:326`), so the armed refusal would have been refused. Both
refusals now carry it as `:seon.error/offending`.

### Real-branch cost (default JVM, `nsa-slice2.probe` copy of the function, two `registry/branch!` branches per row, retired after)

P = 10,696 declarations. The branch had k digest replacements; head had 2
(one of them a conflict).

| k | first call ms | repeat ms | scan (cold) | identity lookup (cold) |
|---:|---:|---:|---:|---:|
| 1 | 251–467 | 28–53 | 190 | 7 |
| 10 | 450 | 51 | – | – |
| 100 | 753 | 94 | – | – |
| 1000 | 1,894–4,062 | 224–628 | 170 | 1,059 |

Three full digest-maps on the same base: 3 × 210–238 ms warm.
Justifications for the rows above one second: at k = 1000 (about 9 % of P) the
cold cost is k random AVET lookups, each loading index nodes from konserve.
They cost more than a sequential scan at that point. That is proportional to
k, which is the slice's target shape, but not sub-second. The first loop
changes one function, the k = 1 row. The cold 170–190 ms scan is the
Datahike limit named above.

### Schema resource adopted in place (no from-zero boot)

The resource change widens `:seon.program/missing-evidence` with `:seon.program/history` and
`:seon.program/scope`. On a `registry/branch!` branch of default's live store,
`seon.db/transact!` of `(seon.program/declaration-row projection
{:seon.schema/key :seon.program/missing-evidence :seon.schema/form <new form>} :all :agent)`
was accepted (no refusal). Carried projection before: `:seon.program/scope`
invalid. After: `:scope` and `:history` valid, and `:read` is still valid. Branch
retired. No member was removed, so the 1.3e retirement refusal has no subject.

### Hot path

Neither function has a production caller (`rg digest-map src` finds only
`program.cljc`). The whole-program `digest-map` path is byte-identical except
for a refusal branch's member key. No transact/projection/publication/fixture
path changed, so no parent-versus-own clock row applies. The regression
exercises the new branch only.

## TIMINGS

| Operation | Wall | Justification |
|---|---:|---|
| plain test JVM, namespace load (`require seon.program-test`) | 31.0–42.6 s | DEFECT (>10 s): namespace compilation, proportional to the whole loaded program. Same class as `docs/seon/issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`; rows for the orchestrator to fold in. The first attempt also spent 35.4 s before a foreign syntax error in the working-tree `src/seon/cluster.clj:372`, which forced the archive snapshot |
| arm contracts (1838) | 3.1–13.4 s | DEFECT when >10 s: proportional to armed Vars; 13.4 s under the owner-reported machine load |
| 7 test vars | 1.7–9.7 s | includes a 100-trial generative three-way property. The 9.7 s run was under machine load |
| own regression alone | 1.77 s | two in-memory genesis stores (a schema declaration transaction each), five speculative `d/with` values, the armed wrapper. Not split further under machine load; next measurement names the split |
| schema declaration `transact!` on a branch | 1.57 s | seon.db writer (final-report validation), not this slice. The foreign system turn ran concurrently (3.5 s turn-step in the same profile) |
| carried projection after schema change | 233 ms | one projection derivation, keyed by declaration revisions |
| `git archive` snapshot | 1.1 s | proportional to the tree (162 MB). The shared caches are not linkable across the snapshot path |
| branch-probe transactions, k = 1000 | 2.26 s | writer validation of 1000 rows; probe setup, not the comparison |

No RESET NEEDED. Default was only read; disposable branches were created and
retired with `registry/retire-branch!`.
