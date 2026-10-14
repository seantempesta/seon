---
title: Content-key-growth measurement and unresolved fix
type: research
date: 2026-10-14
status: investigation-incomplete
---

# Content-key-growth: measurement and unresolved fix

Lane: content-key-growth. 2026-10-14, default JVM pid 77951, installed archive
e5dd2abd5501619eb19e1f31f62ff54e948b0344.
Datahike pin: 62f94893faf929c9ee37f4188dd88983495c960a.
All live probes used MCP SCI as agent lane-content-key-growth, cluster default.
No JVM-mode evaluation, restart, source edit, or performance commit.

## Outcome

The requested incremental fix is **not implemented or proven**. Direct measurements
do not reproduce increasing native-database content-key cost across 56 definitions
of one function. They do reproduce the expensive temporal path. Redefining an
existing function with a reaching test reproduces the high *request-wide* monitor
average while the directly measured native call stays inexpensive.

A separate content-identity defect is reproduced: a native value and its as-of
view at the same basis yield different content keys despite identical row sets.
Sorting restores equality but increases direct key cost; this candidate is not
a demonstrated performance improvement and is not committed as code.
There is no parent/change ordinary-request proof and no claimed after column.

## Growth series

The first eight requests defined a new function with constant `:int` input/output
contract and bodies `(+ x n)`. Direct key milliseconds:
3.46, 5.18, 3.64, 4.10, 5.89, 4.74, 4.59, 3.90.

The next 48 requests changed the same function's output contract to `[:= n]`
and its body to `n`. Each request settled before the next. The measurement in
a request sees the previously settled definition; the final stored spec was
also pulled to verify that definitions persisted. Rows remained 3,811 schema
identities and 7,011 function identities. Native calls for definitions 9–24:
3.43, 4.39, 3.66, 3.53, 3.61, 6.02, 3.83, 3.54,
3.63, 3.53, 3.56, 3.75, 5.69, 4.78, 3.67, 3.72 ms.

For definitions 25–56 both shapes were measured in each request:

| Definition | Native ms | Native allocated bytes | As-of ms | As-of allocated bytes |
|---|---:|---:|---:|---:|
| 25 | 3.41 | 5547128 | 21.59 | 59056240 |
| 26 | 3.49 | 5547128 | 21.68 | 59058440 |
| 27 | 3.4 | 5547128 | 28.73 | 62328632 |
| 28 | 3.97 | 5547128 | 24.34 | 59066720 |
| 29 | 3.62 | 5547128 | 23.69 | 59068856 |
| 30 | 3.89 | 5546776 | 23.16 | 59070920 |
| 31 | 3.53 | 5547128 | 29.94 | 59073088 |
| 32 | 3.59 | 5547128 | 24.43 | 59075184 |
| 33 | 6.78 | 8045776 | 32.4 | 59077296 |
| 34 | 3.56 | 5547128 | 21.67 | 59079512 |
| 35 | 3.73 | 5547128 | 24.78 | 59081680 |
| 36 | 3.56 | 5547128 | 22.58 | 59083856 |
| 37 | 3.67 | 5547128 | 23.44 | 59086032 |
| 38 | 3.51 | 5547128 | 23.78 | 59088240 |
| 39 | 3.91 | 5547128 | 22.9 | 59090536 |
| 40 | 3.98 | 5547128 | 24.21 | 59093112 |
| 41 | 3.56 | 5547128 | 23.17 | 59095064 |
| 42 | 3.83 | 5547128 | 23.03 | 59097272 |
| 43 | 3.61 | 5547128 | 24.53 | 59099288 |
| 44 | 3.9 | 5547128 | 21.98 | 59101576 |
| 45 | 3.52 | 5547128 | 23.65 | 59103712 |
| 46 | 3.47 | 5547128 | 22.89 | 59105864 |
| 47 | 3.52 | 5547128 | 23.45 | 59107944 |
| 48 | 3.55 | 5547128 | 23.99 | 59110064 |
| 49 | 3.58 | 5547128 | 23.63 | 59116144 |
| 50 | 3.7 | 5547128 | 29.11 | 59856640 |
| 51 | 3.54 | 5547128 | 21.93 | 59120728 |
| 52 | 3.53 | 5547128 | 24.04 | 59122888 |
| 53 | 3.56 | 5547128 | 22.84 | 59125080 |
| 54 | 3.63 | 5547128 | 23.05 | 59127304 |
| 55 | 3.6 | 5547128 | 24.24 | 59129528 |
| 56 | 3.53 | 5547128 | 23.35 | 59132120 |

Native allocation is flat except one allocation spike. Temporal allocation grows
about 76 KB across these 32 requests, not tens of MB per definition.
This is evidence against the proposed native branch-history growth cause, not
proof that every branch or every existing-function edit has constant cost.

First paired probe, before these warm samples: native 4.17 ms / 5,548,472 bytes;
as-of 55.75 ms / 82,879,728 bytes (result/e308bb0b8c7f9).
Later paired samples must not be represented as a code improvement: code did not change.

## Existing-function request

Four semantics-preserving redefinitions of `seon.edit/valid-form-operation?`
added an ignored numeric expression before the original body. Its reaching test
`my.edit-test/form-operation-relation-is-structural-and-open` passed each time.

| Revision | Direct native key ms | Request ms | Test-step ms | Test body ms | Monitor key average ms |
|---|---:|---:|---:|---:|---:|
| 1 | 3.32 | 4701.6 | 4301.2 | 22.9 | 20.32026 |
| 2 | 5.81 | 4881.3 | 4585.9 | 17.1 | not shown |
| 3 | 4.74 | 4566.3 | 4375.7 | 17.5 | 24.96894 |
| 4 | 4.79 | 7259.5 | 7020.3 | 18.0 | not shown |

Direct result handles: result/ee21d458e6a5f, result/ea8beb3165ca1,
result/e251dc9ad4d6e, result/e92958d16a57b.
The requests allocated 9.0–16.6 GB in their step accounting for one 17–23 ms test.
That is unresolved excessive work, consistent with the existing
[test-step investigation](teststep-cost.md); it is not excused as cold cost.
Shared JVM GC and compilation also varied substantially. No claim of quiet-JVM
end-to-end speedup is possible from these observations.

## What the work is proportional to

`seon.db/declaration-content-key` reads the five attributes named by
`seon.schema/projection-ranges` and `seon.schema/projection-attributes`.
It reconstructs entity/value maps and joins every identity on every invocation.

For a native DB, `datahike.db/contextual-datoms` slices current indexes.
The work is proportional to the whole current declaration population, not the
number of redefinitions or branch commits.

For an AsOfDB, `datahike.db/contextual-datoms` reads temporal ranges, then
`datahike.db/post-process-datoms` filters transactions, groups by entity/attribute,
and `datahike.db/assemble-datoms-xform` reconstructs the value. Work includes
historical datoms in those ranges. It is not a scan of the branch's merge-base
delta. Group reconstruction does not preserve the original index order.

Consequently the previous report's request-wide 3.9 → 8.5 → 22 ms signal cannot
by itself establish growth of a native call. A single request can include native
and temporal invocations. This lane reproduced that distinction directly.

## Unequal keys for equal content

Exact form, result/e415dc5f443fd:

```clojure
(let [database (seon.db/db)
      basis (seon.db/basis-t database)
      current (#'seon.db/declaration-content-key database)
      temporal (#'seon.db/declaration-content-key
                (seon.db/as-of database basis))]
  {:same-key (= current temporal)
   :same-rows (= (mapv set current) (mapv set temporal))})
;; => {:same-key false, :same-rows true}
```

This contradicts the key's stated identity-order promise and prevents these equal
contents sharing their canonical projection key. It is a producer defect in
`seon.db/declaration-content-key`; the declaration content must have canonical
order independently of the database representation.

The smallest experimental transformation was to sort each returned range by its
identity. Five interleaved parent/transformation measurements over the same held
as-of value in one request (result/e90e3484d35bd):

| Pair | Parent ms | Ordered ms | Ordered equals native |
|---|---:|---:|---|
| 1 | 35.09 | 28.64 | true |
| 2 | 23.35 | 36.69 | true |
| 3 | 23.03 | 29.51 | true |
| 4 | 23.07 | 29.89 | true |
| 5 | 22.16 | 28.03 | true |

Warm parent allocation was 58.86–60.32 MB; ordered was 68.71–70.22 MB.
This experiment corrects order but is a direct-key regression. It does not prove
whether avoiding duplicate projection derivation compensates on a first ordinary
request. No such end-to-end improvement is claimed.

## Reproduction

Send separate SCI requests as the named agent; do not wrap successive definitions
in one request, since settlement is part of the behavior. Substitute successive
integers for N in both contract and body:

```clojure
(in-ns 'content-key-growth.probe)
(defn subject {:malli/schema [:=> [:cat :int] [:= N]]} [x] N)
(let [database (seon.db/db)
      view (seon.db/as-of database (seon.db/basis-t database))]
  (mapv
   (fn [v]
     (select-keys
      (seon.profile/measure
       (fn [] (mapv count (#'seon.db/declaration-content-key v))))
      [:seon.profile/value :seon.profile/ms :seon.profile/alloc-bytes]))
   [database view]))
```

For the ordering candidate measure the following against the unmodified call,
holding the same database value throughout, and compare with the native key:

```clojure
(mapv (fn [rows] (vec (sort-by first rows)))
      (#'seon.db/declaration-content-key view))
```

## Verification and remaining work

No production or test files changed. Therefore no seon.db-test or
seon.schema.projection-writer-test run is claimed. A future fix must prove the
equal-content ordering case, preserve cross-transaction/entity identity sharing,
and measure both key construction and an ordinary request in the same JVM.
The reaching namespaces named by the assignment remain seon.db-test and
seon.schema.projection-writer-test.

Full-tree `clj-kondo --lint src script test --cache false`: 0 errors,
1,729 warnings, 7,444 ms. The documentation-only commit changes no declarations;
no test run is presented as proof of an unimplemented performance change.

Historical reds documented by c1dd12e6e (not reverified here):
- seon.db-test/equal-declaration-content-shares-one-projection-across-values.
- seon.schema.projection-writer-test/speculative-projections-are-process-cache-hits.

Default was available but faulted at entry. A diagnostic `(keys (seon.db/db))`
also produced a rendering ClassCastException (result/e6db4a18e52d1); this is
preserved as evidence, not treated as a successful keys inspection.
No foreign source breakage prevented the measurements. Other lanes' dirty files
and the differing working-tree/runtime revisions were not loaded or modified.

The incremental design remains open: use the writer's actual declaration delta
and a held base content, while retaining canonical equal-content identity across
native, speculative, and temporal values. No new cache or connection-local key
has been introduced. Preserving content equality and avoiding full-vector
reconstruction/hashing need proof before implementation.
