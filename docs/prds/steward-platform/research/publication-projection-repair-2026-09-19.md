---
type: research
status: active
tags: [research, publication, data-modeling]
---

# Complete publication loses its construction projection

Codex owns this bounded repair at Sean's instruction. Default PID 41822
remains the only development JVM; no competing publication or restart.

## Before

The third `bin/seon init --dev default` request, CLI PID 58594, exited 1
after 56,128 ms. Its last event was `program population compiled: 11191
entities, 23734 identities, 39441 keyword facts`; the operator refused
30,000 ms of silence. Log: `data/operator/operations/init-init-58594.log`.

Virtual-thread-inclusive `jcmd Thread.dump_to_file -format=json` samples
first found the publication caller in `db/write-error`, recursively walking
nested maps, then waiting for the transaction promise while a Konserve
worker forced a file. A later sample contained no publication stack. This
is finite work outliving its observer, not evidence of a deadlock. The
published head did not advance: `6aac85dd-adc8-5171-bde7-5ce8a41de5d3`.

## Confirmed discrepancy

`populate-source!` deliberately installs physical database attributes first
and canonical schema rows together with program rows. Its construction
projection is supplied to admission. But the fresh path in `fn/index!`
dereferences the connection directly, then derives a projection from those
not-yet-populated canonical rows. `compile-index-transaction` uses that
projection to decide which values are references and should be flattened.

The live base connection had **zero canonical forms**, versus **3,208** in
the supplied declaration projection. `:seon.fn/arities` and
`:seon.schema.shape/children` were absent in the former and component refs
in the latter. Acquiring the database through `seon.db/db` under the supplied
projection carried that exact projection (`identical?` true). The existing
acquisition owner already does what the compiler needs.

Reproduction, read-only in default's JVM:

```clojure
(let [instance (get @(var-get (find-var 'seon.operator.runtime/running-instances))
                    "default")
      connection (:seon.store/connection-object (:seon.store/store instance))
      raw @connection
      persisted (seon.schema/projection-from-database raw)
      supplied (seon.schema/declaration-projection
                (seon.schema.edn/packaged-forms))]
  {:persisted-forms (count (:seon.schema.projection/forms persisted))
   :supplied-forms (count (:seon.schema.projection/forms supplied))
   :acquisition-preserves-projection?
   (seon.schema/call-with-projection
    supplied
    #(identical? supplied (seon.db/carried-projection
                          (seon.db/db connection))))})
```

The proposed alias-resolution cache was dropped before landing: 10,000
armed calls already took only 16.370 ms. A sampled frame alone does not
justify an optimization. No validation, instrumentation, durability, or
transaction boundaries should be weakened to get past the operator bound.

## Repair and verification

The fresh path now acquires through `seon.db/db`, preserving the supplied
construction projection. A second confirmed cost was `compiled-wrapper`
rebuilding config defaults for every wrapper. Instrumentation now captures
effective caps and the error evidence limit once per acquisition; SCI and
development adoption pass their already acquired policy. Wrapper reuse
compares caps as well as the contract and its referenced definitions.

Two canonical, armed regressions passed: the fresh-population case had
9 assertions, and instrumentation policy acquisition/reuse had 23; neither
had failures or errors. These ran through the bounded runner in the existing
JVM. The normal host entry encountered the separately recorded
[test classification defect](../../../seon/issues/test-host-classification-and-path-use-different-program-edges.md).
This is iteration evidence, not an isolated gate result.

The exact seven-file `bin/test-fast --paths` request exited 64 at overlay
admission, before executing tests: six concurrently edited caller files
outside this lane were required. No cold gate was run or claimed.

Complete publication and adoption succeeded in the same default JVM:
CLI 64089 completed in 159,359 ms, followed by CLI 64570 in 150,095 ms.
The latter published and adopted `6aaec3db-f99e-5125-b8aa-db32567affc8`.
Read-only verification confirmed equal published/adopted commits, matching
owned definition source/spec facts in the acquired SCI program, and the
updated `seon.fn/index!` JVM docstring. The follow-up requested one changed
file, but concurrent program identity changes caused complete publication;
an isolated incremental-only proof remains owed. No restart was needed.

The [bounded landing note](../../context-generation/research/publication-update-repair-2026-09-19.md)
records all owned paths, measurements, cache guidance and handoff boundaries.
