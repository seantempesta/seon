---
type: spec
status: active
tags: [spec, agent, architecture]
---

# W5-0b — seon.db host-tier parity

Second unit of the W5-0 series. The census gate (test/seon/
host_surface_writer_test.clj, `2f1e9c6c`) currently holds 11
`:host/capability-pending` rows for seon.db; this unit flips them to
`:host/resolved` honestly. Grounding, binding:
`research/w50-surface-census-grounding-2026-07-22.md` §4 row 1 —
read it first, then the actual child source for every fn you shim.

STOPPING EARLY IS FREE — especially on call-shape questions: the child
surface is the CONTRACT; if a child fn's shape cannot be honored over
the host writer session (async semantics, options the synchronous
session cannot express), stop and report rather than shipping a
lookalike.

## The work

For each pending seon.db name (the census rows are the list: as-of,
since, history, cas-assert, transact!, query-with-evidence, pull-many,
entity, installed-schema, execute-many, index-page — verify against the
table, it is the authority):

1. Read the child implementation (src/seon/db.cljs) for the EXACT
   map-in/positional shape, option keys, and error envelope; the host
   wrapper must match it — an agent moving tiers must not relearn a
   call shape (q34's semantic-parity risk: resolved names without
   call-shape parity are worse than missing names).
2. Implement through the existing host wrapper registry + writer
   session (context.clj:851,879,925 mechanism; the four existing
   overlapping wrappers at context.clj:962 are the idiom — but FIRST
   verify those four actually match child shapes; the grounding says
   even overlapping names lack full parity: fixing them is in scope).
3. Pure transformations (as-of/since/history temporal maps, the CAS
   vector shape at db.cljs:805,825) reuse the child's pure logic —
   if genuinely portable, extract to the one honest .cljc owner rather
   than duplicating (report the extraction; the cljc-maximization
   ruling applies); never copy-paste a second implementation.
4. entity = pull '[*] (child precedent db.cljs:1054); pull-many /
   installed-schema / execute-many / index-page ride their existing
   writer protocol operations (db.cljs:1083,1093,1108,1164).
5. Update the census dispositions to :host/resolved ONLY for rows whose
   wrapper lands with a contract test; the gate's honesty assertion
   must pass against the real registry declarations.
6. Contract tests in the writer gate: per fn, one happy-path against a
   real writer session AND one error-envelope case (errors as values —
   the child returns :seon/error maps, the host wrapper must too, same
   keys). A batch-level test evaluating a form that calls several of
   these through sci on a real host base.

## Owned paths

src/seon/host/context.clj (wrapper additions/repairs), any genuinely
extracted pure .cljc owner (report it), test/seon/
host_surface_writer_test.clj (disposition flips only), writer-gate
host tests (host_toolkit_writer_test.clj or the honest existing home —
census it). PROTECTED: src/seon/db.cljs + src/seon/db/internal.cljs +
src/seon/config* (a live lane owns them RIGHT NOW — if the pure
extraction in step 3 requires touching db.cljs, STOP and report
instead), config/*.edn, everything else.

## Gates

bin/test-writer focused + full; bin/test-cljs if a .cljc extraction
lands; honest counts; logs to files. Commit nothing; leave the diff
for review. The live composition proof is the orchestrator's at W5-0f.
