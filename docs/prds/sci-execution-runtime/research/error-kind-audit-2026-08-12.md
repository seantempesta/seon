---
type: research
status: complete
tags: [research, error, schema, data-model]
---

# Error-kind audit — 2026-08-12

## Verdict

**Refactor `:seon.error/kind`; it is not a justified permanent exception to
the discriminator rule. Do not remove it piecemeal.**

The key is not used to partition stored entities into kinds: production code
has no Datalog query for a particular kind and identifies error facts,
receipts, context contributions, and maintenance results by their declared
attributes and connections. It is nevertheless stored on those entities and
duplicates the classification already declared by error-class marker shapes.
It therefore fails the stronger data-oriented test—there should be no second
classification fact to keep coherent—even though it is not the classic
`:type`/`:kind` entity-taxonomy lookup.

The current tree cannot safely lose the key in a free-file refactor. There are
289 writes and 153 reads or structural uses in 64 `src/` files. Most failure
control flow still treats key presence as the error predicate, and 12 real
classes plus one absence sentinel are selected by exact kind value. The
declared class-marker schemas have landed, but most producers have not been
converted. Under an active schema projection, `seon.error/error?` deliberately
rejects a legacy kind-only map. Converting a consumer first would therefore
turn existing failures into successes.

This confirms the coordinated W2–W5 deletion already ordered in
[the error-model PRD](docs/prds/error-model/README.md), while expanding its
stale five-dispatch census. No production refactor landed from this audit:
the required wave crosses the protected `cluster/loop.clj`, `cluster/run.clj`,
`render/web.clj`, `db.clj`, and provider/AI ownership named for the live lanes.
The updated evidence and acceptance plan are recorded in
[the open catalog/renderer issue](docs/seon/issues/error-class-catalog-and-renderers-disagree.md).

## Scope and dependency ledger

This audit read the discriminator guidance in
[the data-oriented Clojure skill](.agents/skills/data-oriented-clojure/SKILL.md)
and the complete prior authorities:

- [Error catalog, 2026-08-03](docs/prds/sci-execution-runtime/research/error-catalog-2026-08-03.md)
- [Error-model PRD](docs/prds/error-model/README.md)
- [Current catalog/renderer issue](docs/seon/issues/error-class-catalog-and-renderers-disagree.md)

The census uses the vendored rewrite-clj source at commit
`60782e501aaf312cb90c9ff0bee05d5da5125563` to count parsed keyword nodes,
not comments or docstrings. Schema-shape behavior is grounded in the vendored
Malli source at `3517a3cd9271b2083780ac7be1725493905bca2e`, the activated Seon schema
projection, and `seon.schema/matching-shapes-in`. Stored-row conclusions are
grounded in the declared entity schemas and Datahike source at
`cdcb5792db8bd599487f099437265d18a31164a5`.

The reproducible census is
[error_kind_census_2026_08_12.clj](docs/prds/sci-execution-runtime/research/scripts/error_kind_census_2026_08_12.clj).
It reported:

```clojure
{:source-files 64
 :executable-and-contract-occurrences 442
 :classifications {:map-write 277
                   :assoc-write 12
                   :direct-read 137
                   :vector-use 15
                   :query-read 1}
 :write-sites 289
 :literal-write-sites 261
 :dynamic-write-sites 28
 :distinct-literal-kinds 155
 :declared-error-class-markers 231}
```

This is an executable/contract census. A raw text search currently finds 453
tokens on 444 lines; the parsed census excludes prose and comments and avoids
counting a multiline occurrence by line shape.

## Producer and consumer census

`W` is a map literal or `assoc` write. `R` is a direct map lookup. `S` is a
schema/path/query structural use. Consumer roles are:

- **B** — branches on presence/truthiness;
- **E** — dispatches on an exact value;
- **X** — copies or projects the value across an internal boundary; and
- **D** — displays, logs, or declares the value without selecting behavior.

Files with no consumer role are producer-only. Exact-value dispatches are
expanded in the next section.

| Source | W | R | S | Consumer role |
|---|---:|---:|---:|---|
| `src/my/background.clj` | 3 | 2 | 0 | B |
| `src/my/message.clj` | 6 | 0 | 0 | — |
| `src/my/run.clj` | 3 | 0 | 0 | — |
| `src/seon/ai.clj` | 15 | 6 | 0 | B, X |
| `src/seon/artifact.clj` | 1 | 0 | 0 | — |
| `src/seon/blob.clj` | 5 | 0 | 0 | — |
| `src/seon/bootstrap.clj` | 8 | 0 | 0 | — |
| `src/seon/bootstrap_drive.clj` | 1 | 0 | 0 | — |
| `src/seon/call_preparation.clj` | 1 | 1 | 0 | B |
| `src/seon/cluster.clj` | 10 | 5 | 1 | B, X |
| `src/seon/cluster/agent.clj` | 4 | 2 | 0 | B |
| `src/seon/cluster/curate.clj` | 2 | 6 | 1 | B, E, X |
| `src/seon/cluster/export.clj` | 1 | 0 | 0 | — |
| `src/seon/cluster/loop.clj` | 7 | 29 | 0 | B, E, X |
| `src/seon/cluster/message.clj` | 8 | 1 | 0 | B |
| `src/seon/cluster/process.clj` | 1 | 0 | 0 | — |
| `src/seon/cluster/prompt.clj` | 4 | 3 | 0 | B, X |
| `src/seon/cluster/registry.clj` | 5 | 1 | 0 | B |
| `src/seon/cluster/reply.clj` | 1 | 1 | 0 | E |
| `src/seon/cluster/run.clj` | 3 | 1 | 3 | B, X, D |
| `src/seon/cluster/source.clj` | 1 | 1 | 0 | B |
| `src/seon/cluster/store.clj` | 1 | 1 | 0 | B |
| `src/seon/cluster/wake.clj` | 1 | 0 | 0 | — |
| `src/seon/cluster/work.clj` | 0 | 1 | 0 | E |
| `src/seon/config.clj` | 3 | 2 | 0 | B |
| `src/seon/context.clj` | 1 | 1 | 0 | B, X |
| `src/seon/db.clj` | 3 | 2 | 0 | B |
| `src/seon/edit.clj` | 2 | 7 | 0 | B |
| `src/seon/edit/jvm.clj` | 1 | 4 | 0 | B, E |
| `src/seon/effect.clj` | 6 | 3 | 0 | B |
| `src/seon/env.clj` | 10 | 0 | 0 | — |
| `src/seon/error.clj` | 4 | 10 | 3 | B, E, X, D |
| `src/seon/eval/drive.clj` | 1 | 1 | 2 | B, D |
| `src/seon/flow.clj` | 7 | 0 | 0 | — |
| `src/seon/fn.clj` | 13 | 1 | 0 | B |
| `src/seon/fn/schema_shape.clj` | 3 | 0 | 0 | — |
| `src/seon/fn/signature.clj` | 1 | 0 | 0 | — |
| `src/seon/fs/jvm.clj` | 2 | 2 | 0 | B |
| `src/seon/instrument.clj` | 5 | 1 | 0 | B |
| `src/seon/maintenance.clj` | 2 | 3 | 1 | B, X, D |
| `src/seon/operator.clj` | 7 | 4 | 0 | B, E |
| `src/seon/print.cljc` | 1 | 0 | 0 | — |
| `src/seon/problems.clj` | 3 | 5 | 2 | B, X, D |
| `src/seon/program.clj` | 8 | 0 | 0 | — |
| `src/seon/reconcile.clj` | 2 | 1 | 0 | B |
| `src/seon/render.clj` | 4 | 8 | 0 | B, X, D |
| `src/seon/render/data.clj` | 1 | 0 | 0 | — |
| `src/seon/render/hiccup.clj` | 4 | 1 | 0 | B |
| `src/seon/render/transcript.clj` | 0 | 2 | 1 | X, D |
| `src/seon/render/value.clj` | 2 | 2 | 0 | B, D |
| `src/seon/render/walk.clj` | 2 | 1 | 2 | E, D |
| `src/seon/render/web.clj` | 5 | 6 | 0 | B, E, D |
| `src/seon/schedule.clj` | 8 | 2 | 0 | B |
| `src/seon/schema.clj` | 28 | 0 | 0 | — |
| `src/seon/schema/datahike.clj` | 7 | 0 | 0 | — |
| `src/seon/schema/edn.clj` | 12 | 0 | 0 | — |
| `src/seon/schema/internal.clj` | 4 | 0 | 0 | — |
| `src/seon/sci/admit.clj` | 1 | 0 | 0 | — |
| `src/seon/sci/eval.clj` | 12 | 0 | 0 | — |
| `src/seon/sci/kernel.clj` | 6 | 2 | 0 | B |
| `src/seon/sci/reader.clj` | 1 | 0 | 1 | D |
| `src/seon/search.clj` | 1 | 0 | 0 | — |
| `src/seon/shell/jvm.clj` | 6 | 5 | 0 | B |
| `src/seon/test/runner.clj` | 7 | 1 | 0 | B |
| **Total** | **289** | **137** | **16** | |

The 137 lookups are dominated by generic success/failure branching such as
`(if (:seon.error/kind result) ...)`. Those sites do not care which error
class arrived; after producer conversion their intended owner is
`seon.error/error?`, not another marker roster. Projection and display sites
should retain the actual marker attribute and message, rather than copying a
second keyword label.

## Exact dispatches

The current tree contains 14 exact dispatch occurrences covering 12 real
classes and one value used to mean absence. The operator class is caught at
two nested boundaries.

| Site | Current selection | Shape-based replacement |
|---|---|---|
| `src/seon/edit/jvm.clj:22` | `case` on `:my.fs/stale-digest` and `:my.fs/invalid-utf8-window` | `contains?` on each declared `my.fs` marker |
| `src/seon/cluster/reply.clj:376` | `:seon.sci.reader/refused-tag` | declared refused-tag marker; reconcile the current reader/reply namespace-name drift |
| `src/seon/render/web.clj:1487` | `:seon.db/unknown-failure` selects HTTP 500 | declared unknown-failure marker; other errors remain 422 |
| `src/seon/error.clj:362` | instrument contract evidence extraction | `:seon.instrument/contract-violated` marker presence |
| `src/seon/error.clj:433` | instrument render-function selection | the same marker presence |
| `src/seon/render/walk.clj:588` | `:seon.render.walk/elided` | elision marker presence |
| `src/seon/cluster/loop.clj:661` | `:seon.ai/stream-truncated` | stream-truncated marker presence |
| `src/seon/cluster/loop.clj:1019` | `:seon.cluster.loop/trigger-already-answered` | class marker presence |
| `src/seon/cluster/loop.clj:1124` | `:seon.cluster.reply/unreadable` | unreadable marker presence |
| `src/seon/cluster/loop.clj:1464` | `:seon.cluster.loop/phase-failed` | phase-failed marker presence |
| `src/seon/cluster/work.clj:184` | `:seon.cluster.loop/lint-rejected` in a receipt value | lint-rejected marker presence after reading the receipt value |
| `src/seon/operator.clj:741,747` | `:seon.operator/collection-incomplete` in `ex-data` | collection-incomplete marker presence |
| `src/seon/cluster/curate.clj:209` | `:seon.eval.drive/absent` | remove the sentinel: dispatch on absence of the value/attribute |

Several exact classes do not yet have a same-named marker in the current 231
declarations, while others use an `*-error` schema key and a domain marker
whose name intentionally differs from the old kind. Shape conversion must be
driven by `:seon.error/class true` declarations and required marker
attributes, never by mechanically renaming `kind` values or maintaining a
literal crosswalk.

## Question 1: stored entity discriminator or flat value only?

It is stored, but not used as an entity taxonomy.

- `resources/seon/schemas/seon.error.edn:35-74` declares
  `:seon.error/fact` as a database entity and requires
  `:seon.error/kind`. `seon.error/normalize` transacts that value.
- `resources/seon/schemas/seon.cluster.eval.edn:29-74` declares the receipt
  entity and optionally stores `:seon.error/kind`; run settlement projects it
  onto receipt transaction data.
- `resources/seon/schemas/seon.context.contribution.edn:1-19` and the
  maintenance result component shapes also carry the key.
- The only production Datalog clause naming it is
  `src/seon/problems.clj:170`, `[?receipt :seon.error/kind ?kind]`. It does
  not constrain `?kind`; attribute presence selects errored receipts and the
  value is displayed later. Receipt error presence already exists as
  `:seon.cluster.eval/error`, so the classification key is unnecessary for
  that selection.
- There is no production Datalog query matching a literal kind. Stored entity
  identity and relations come from their declared identity attributes and
  refs, not from `kind`.

The precise conclusion is therefore: this is not the classic banned entity
taxonomy, but it is redundant stored derived state on entities as well as an
in-flight discriminator. That is sufficient reason to complete its deletion.

## Question 2: can shape declarations own dispatch?

Yes, after the producers cross the same boundary.

`seon.schema/matching-shapes-in` does not scan every schema. It collects
candidates from the projection's attribute index, rejects rows whose required
attributes are absent, orders the remaining shapes deterministically, and
validates them with projection-local cached validators. `seon.error/error?`
then filters those matches to schemas carrying `:seon.error/class true`.

A live load probe against the activated registry established the critical
migration constraint:

```clojure
{:kind-only-error? false
 :marker-only-error? true
 :kind-only-matches [:seon.error/value ...generic shapes...]
 :marker-only-matches [:my.fs/stale-digest-error ...generic shapes...]
 :class-count 231}
```

The kind-only input was
`{:seon.error/kind :my.fs/stale-digest :seon.error/message "stale"}`;
the marker input used `:my.fs/stale-digest` plus the same message. Thus:

- generic control flow should call `seon.error/error?` once marker-producing
  schemas and producers are complete;
- consumers selecting one known class should use `contains?` on that class's
  declared marker, which is registry-free and constant-time;
- renderers needing a class label can derive it from the matched class row's
  required marker attribute, as `seon.error/error-marker` already begins to
  do; and
- registry-free leaves retain the documented message-presence fallback for
  generic failure detection, while exact leaves use marker presence.

The cost is a coordinated producer/consumer/schema/fact wave, not expensive
steady-state dispatch. Removing `kind` requires converting all 289 writes,
then the generic and exact branches, then the durable fact and receipt shapes,
then public contracts. The existing W2–W5 order is correct because it prevents
a legacy error value from becoming success-shaped between commits.

## Question 3: boundary load bearing

No external boundary fundamentally requires `:seon.error/kind`.

### Provider HTTP

`src/seon/ai.clj:1260-1364` reads the JDK HTTP response status and body and
constructs local error maps afterward. The provider wire does not send or
dispatch on `:seon.error/kind`. The current AI owner also derives dispositions
from transport evidence and `:seon.ai/error-class`; the error-model PRD already
identifies that second classification as part of the coordinated cleanup.

### MCP

`script/seon/dev/mcp.clj:61-73` expresses MCP failure with the protocol's
top-level `:isError true` and text content. Evaluation uses the prepl terminal
tag `:exception` to choose that envelope. A projected Seon value can contain a
legacy kind as ordinary EDN content, but the MCP protocol does not inspect it.
Declared marker maps are equally serializable ordinary data.

### Browser HTTP and internal envelopes

The web message handler currently maps `:seon.db/unknown-failure` to HTTP 500
and other database failures to 422. That is a local exact dispatch, not a wire
limitation, and marker presence preserves the distinction. Other internal
envelopes use kind presence as today's generic success/error predicate; they
can use `seon.error/error?` only after producers emit declared shapes.

Therefore `kind` is load-bearing today as a compatibility field inside the
unfinished W1-to-W5 migration and on durable receipts/facts. It is not
load-bearing because any provider, MCP, browser, or serialization boundary
lacks shape dispatch.

## Refactor boundary and acceptance

The scoped next action is the existing error-model wave, refreshed by this
census:

1. Reconcile every literal producer with a declared `:seon.error/class true`
   schema, adding a missing declaration only when the registry query proves it
   absent. Do not create a kind-to-marker hand list.
2. Convert producers to marker-shaped error values, one owner at a time, while
   the temporary renderer fallback continues to accept legacy kind values.
3. Replace generic truth checks with `seon.error/error?`; replace the exact
   dispatch table above with marker presence; replace the curate absence
   sentinel with absence.
4. Delete `:seon.error/kind` from error facts, receipts, context contributions,
   maintenance projections, render units, and Datalog selection. Stored
   diagnostics retain the admitted error value or declared marker plus message.
5. Delete the legacy `seon.error/error-marker` kind fallback and
   `:seon.error/value` contract only after no parsed producer, read, structural
   use, or stored datom remains.
6. Keep one generated regression proving every intentional producer matches at
   least one declared error class, plus focused regressions for generic
   truthiness, each exact-dispatch class, durable receipt/fact projection,
   and provider/MCP/browser boundary behavior.

The graduation query is zero executable or contract occurrences of
`:seon.error/kind` in `src/` and zero declarations/datoms in
`resources/seon/schemas/`, with all error predicates derived from declared
class shapes. The census script makes the source half repeatable; database
proof must additionally query a freshly forked cluster for remaining datoms.
