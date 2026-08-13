---
type: research
status: active
tags: [research, render, bootstrap, datahike, performance]
---

# Generated-opening live-pull attribution — 2026-08-13

## Verdict

The generated opening's dominant measured cost is the Datahike pull
interpreter, specifically the per-entity execution of the generated root
selector in `datahike.pull-api/pull-pattern-frame` and
`datahike.pull-api/pull-attr`. It is not selector generation or parsing,
candidate expansion, render selection, SCI invocation, admission, or the
ordered-episode fixed point.

At HEAD, an isolated full source publication with 37 acquisition members took
3.04–3.43 seconds inside `datahike.pull-api/pull-spec`. A clone of that
publication with 40 additional real agent graphs returned 198 members and took
4.23–5.01 seconds inside `pull-spec`. The latter acquisition completed in
4.47–5.25 seconds overall. The historical 24.2-second result therefore does
not reproduce at HEAD on this 78,974,355-byte database, but the cost still
scales with database contents and graph size and remains far above the design
price.

Bounded counters on the 198-member database attributed one acquisition to
1,775,664 `pull-pattern-frame` calls and 1,770,912 `pull-attr` calls. The
instrumented wrappers perturb the absolute time, so those call counts—not the
instrumented wall time—are the attribution evidence.

## Authorities and dependency ledger

The following named authorities were read end to end before the probe and this
attribution:

- [the 24-second live-pull issue](../../../seon/issues/live-root-pull-of-189-members-takes-24-seconds.md)
- [the non-returning post-help issue](../../../seon/issues/generated-opening-live-pull-does-not-return-after-help.md)
- [the undemanded-candidate issue](../../../seon/issues/opening-generator-pushes-undemanded-candidates.md)
- [the evolving-session implementation plan](../plan/evolving-session-implementation-2026-08-12.md), including “One generator, one entry at a time” and Phase 1's exit
- [the self-generating-context PRD](../plan/self-generating-context-prd-2026-08-11.md), including “One derivation” and its 19.6 ms cold / 1.9 ms warm price
- `src/seon/bootstrap.clj`, `src/seon/render/walk.clj`, and
  `src/seon/render.clj`
- `reference-code/datahike/src/datahike/pull_api.cljc`
- [the archived four-query-floor issue](../../../seon/issues/archive/cold-root-pull-is-slower-than-the-four-query-floor.md)

The selected Datahike source is the repository gitlink at
`cdcb5792a5c917768c74e6da884af1c853c23995`. The relevant first-party seams are
the root selector and acquisition in `src/seon/render/walk.clj:82-144,198-381`,
candidate expansion in `src/seon/bootstrap.clj:135-220`, generated-entry
selection in `src/seon/bootstrap.clj:237-303`, and render selection/invocation
in `src/seon/render.clj:120-147,227-296,483-557`.

## Method

The reusable probe is [`tmp/live-pull/probe.clj`](../../../../../tmp/live-pull/probe.clj).
It publishes the current source through
`seon.test-support/populate-published-root!`, opens the resulting immutable
database value, times the live owners directly, and records current-thread
allocated bytes around each region. It instruments Vars only around bounded
calls and restores them in `finally`.

The representative larger graph was built by cloning the successful immutable
publication with the supported `seon.test.published-base` path and transacting
40 complete agent graphs through the normal test-support constructor. The
acquisition-only mode was used for that larger graph because an unrelated
in-flight `seon.render` edit made construction of a new SCI context fail after
the acquisition measurement. The database pull itself and all acquisition
owners remained directly executable.

All runs used OpenJDK 26.0.1 on macOS 26.5.2, `aarch64`, with 18 available
processors. “Cold” means the first measured invocation in a fresh JVM; “warm”
means the immediately following invocation against the same immutable database
value. Datahike's pull execution does not acquire a result cache, so “warm” is
not expected to memoize the traversal. Allocated bytes are current-thread
allocation estimates and are useful for relative attribution, not retained
heap size.

## Full published graph: 37 members

These measurements used `tmp/live-pull/root-run-4`: a fresh current-source
publication with a 68,885,520-byte database, 162,396 EAVT datoms, basis
transaction 536870927, a 662-entry top-level selector, 37 returned members,
and `-Xmx8g` (8,589,934,592 bytes).

| Region | Cold wall time | Cold allocated | Second wall time | Second allocated |
|---|---:|---:|---:|---:|
| Root-plan generation | 38.05 ms | 16.02 MB | not repeated | not repeated |
| Root acquisition | 3,203.18 ms | 8.02 GB | 3,567.21 ms | 7.91 GB |
| Datahike `pull-spec` | 3,040.62 ms | 7.62 GB | 3,425.48 ms | 7.51 GB |
| Pull-result decoding | 137.98 ms | 372.63 MB | 129.12 ms | not isolated |
| Membership index | 11.83 ms | 7.18 MB | 3.41 ms | not isolated |

One cold `seon.bootstrap/pull-result` returned 78 candidates in 3,315.88 ms
and allocated 7.99 GB. Its inclusive decomposition was:

| Region | Wall time | Allocated | Condition |
|---|---:|---:|---|
| Root acquisition | 3,187.63 ms | dominant allocation above | first `pull-result` in the fresh JVM |
| Datahike `pull-spec` | 3,044.53 ms | dominant allocation above | nested in that acquisition |
| Direct-candidate expansion | 88.47 ms | 60.56 MB | 37 render calls |
| Listing-candidate expansion | 39.46 ms | 17.19 MB | 37 render calls |
| Direct render selection | 47.99 ms | nested | inclusive across 37 calls |
| Direct render invocation | 20.17 ms | nested | SCI invoke was 19.40 ms |
| Direct admission | 2.51 ms | nested | inclusive across 37 calls |

Nested rows overlap and must not be summed. A second `pull-result` took
3,550.70 ms: 3,508.11 ms in root acquisition, 29.68 ms in direct candidates,
and 12.81 ms in listing candidates.

A real `seon.bootstrap/next-entry` after a settled `help` receipt returned
`(dir (quote my.run))` in 2,801.47 ms and allocated 7.81 GB. Of that,
2,768.36 ms was root acquisition, 2,649.84 ms was Datahike `pull-spec`,
107.33 ms was decoding, 3.25 ms was membership indexing, 24.41 ms was direct
plus listing candidate expansion, 2.46 ms was `ordered-episode`, and 1.37 ms
was final root-candidate choice. The call returned normally; it did not
reproduce the approximately 27-second non-return at commit `16f022fc9`.

## Larger graph: 198 members

These measurements used `tmp/live-pull/root-run-7`: the immutable full
publication above plus 40 real agent graphs, producing a 78,974,355-byte
database, 164,125 EAVT datoms, basis transaction 536870970, a 662-entry
top-level selector, 198 returned members, and `-Xmx12g`
(12,884,901,888 bytes).

| Region | Cold wall time | Cold allocated | Second wall time | Second allocated |
|---|---:|---:|---:|---:|
| Root-plan generation | 40.31 ms | 16.02 MB | not repeated | not repeated |
| Root acquisition | 4,465.32 ms | 12.07 GB | 5,250.90 ms | 12.02 GB |
| Datahike `pull-spec` | 4,228.15 ms | 11.37 GB | 5,005.77 ms | 11.33 GB |
| Pull-result decoding | 201.91 ms | 615.25 MB | 217.52 ms | not isolated |
| Membership index | 20.85 ms | 53.11 MB | 12.88 ms | not isolated |

Thus the closest HEAD reproduction returned nine more members than the
historical 189-member pull but completed in 4.47–5.25 seconds. The historical
post-help observation used a 1.22 GiB database—approximately 15.5 times this
probe's database bytes—and eight intervening commits touched the opening path.
This probe supports graph/database-size scaling but does not claim a linear
size law or explain the entire historical 24.2 seconds by bytes alone.

## Inner-owner counters

`tmp/live-pull/root-run-8` repeated the 198-member database conditions with
wrappers around the maintained Datahike pull functions. One cold acquisition
produced:

| Datahike owner | Calls | Inclusive instrumented time |
|---|---:|---:|
| `pull-pattern-frame` | 1,775,664 | 7,498.22 ms |
| `pull-attr` | 1,770,912 | 6,207.90 ms |
| `pull-attr-datoms` | 1,743,682 | 621.13 ms |

The wrappers nearly doubled acquisition wall time to 8,644.59 ms, so their
inclusive timing is deliberately not mixed into the uninstrumented tables.
The exact call counts repeated on the second acquisition and establish the
structural mechanism.

`src/seon/render/walk.clj:82-144` constructs one selector containing all
installed scalar attributes plus forward and reverse reference recursion.
Datahike compiles it in
`reference-code/datahike/src/datahike/pull_api.cljc:51-91`. At execution,
`pull-pattern-frame` at lines 466-488 reduces the compiled selector specs for
each entity frame; `pull-attr` at lines 368-391 resolves every requested
attribute; and `pull-attr-datoms` at lines 304-366 performs the EAVT or AVET
lookup. Most entity/attribute combinations are absent, but they still cross
this interpreter and index-lookup boundary. `pull-spec` at lines 517-526 owns
the outer entity traversal.

That is the specific inner attribution the Phase 1 lane should consume:
`seon.render.walk/root-acquisition` supplies a schema-wide recursive selector,
and Datahike's `pull-pattern-frame` × `pull-attr` execution expands it per
visited entity. The measured millions of calls and 7.5–11.4 GB of transient
allocation dominate the wall time.

## Comparison with the design price and four-query floor

The self-generating-context design was priced from a 19.6 ms cold / 1.9 ms
warm pull. The archived four-query-floor issue recorded an approximately
46 ms floor and fixed recursive selector parsing by compiling the shared
selector once. HEAD root-plan generation now takes 38–40 ms under both
representative conditions, consistent with that floor.

The remaining 3.0–5.0 seconds is after compilation, in selector execution.
The earlier parser fix remains valid but cannot address the current cost: the
compiled plan is reused while Datahike still interprets every selector entry
against every entity frame. The original 19.6/1.9 ms price therefore described
a smaller graph/database condition and is not a valid price for the current
full published graph.

## Smallest structural fix shapes

Phase 1 should implement its already-specified one-generator/one-entry
boundary: acquire and expand the opening once per generation invocation, carry
that immutable result in invocation-local generation state, and have each
subsequent `next-entry` advance that state after the prior receipt settles.
This removes the measured 2.8–5.3 second reacquisition from every generated
form. It is the smallest source-owner change that directly consumes this
attribution and makes the repeated-stall class structurally impossible.

That change does not make the one remaining acquisition interactive. The
smallest dependency-owner optimization shape is a compiled multi-attribute
frame that scans an entity's forward EAVT range once and dispatches only
present datoms to the requested compiled attribute handlers, while preserving
explicit AVET handling, recursion, limits, defaults, and evidence semantics
for reverse attributes. Equivalently stated: avoid one index lookup for every
absent selector attribute on every entity. This belongs in Datahike's pull
execution owner and requires its own equivalence and allocation proof; it is
not a filter in `seon.bootstrap` or a second acquisition path.

Demand-first candidate selection remains correct for context quality, but it
is not the 24-second fix. On the 37-member full publication, all direct and
listing candidate expansion was tens of milliseconds versus seconds in the
database pull. Demand-first selection should reduce the 78 undemanded render
candidates after acquisition; it cannot recover time already spent executing
the schema-wide pull.

## Reproduction command

From the repository root, a fresh representative publication is:

```sh
clojure -J-Xmx8g -M:dev:test -i tmp/live-pull/probe.clj \
  -m probe tmp/live-pull/root-fresh 0
```

To clone a successful immutable publication and add 40 agent graphs without
depending on concurrent current-source publication:

```sh
clojure -J-Xmx12g \
  -J-Dseon.test.published-base=tmp/live-pull/root-fresh \
  -M:dev:test -i tmp/live-pull/probe.clj \
  -m probe tmp/live-pull/root-large 40 acquisition-only
```

The exact timings are hardware- and graph-dependent. The attribution test is
the relative decomposition and the bounded inner-owner call counts, not an
elapsed-time correctness threshold.
