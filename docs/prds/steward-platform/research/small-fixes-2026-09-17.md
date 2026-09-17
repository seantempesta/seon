---
type: research
date: 2026-09-17
tags: [datahike, pull, render, prompt, program-graph, test-runner, class/absence-as-health]
---

# Five small fixes — landing note

One row per queued item: what landed with its commit, or what was deferred
with the exact hunk. Files another lane held dirty at the time of the work
are named; `git status` was read before each file.

## 1. Datahike's pull truncates cardinality-many at 1 000 with no signal

**Done.** The cut is the dependency's default, not a caller mistake:
`pull-attr-datoms` reads `(get opts :limit +default-limit+)` and drops the
surplus datoms without a word
(`reference-code/datahike/src/datahike/pull_api.cljc:16`, `:315`, `:323`).
`:limit nil` is the dependency's own spelling for no limit
(`reference-code/datalog-parser/src/datalog/parser/pull.cljc:186`,
`:214-216`), and `pull-attr-datoms` honours it by taking `identity` instead
of `(take limit)`.

Fixed at the one owner rather than at N readers: `seon.db`'s `pull-call`
now runs every selector through `total-pull-selector`, which gives each
attribute expression the caller named `:limit nil` unless the caller spelled
a limit itself. Named attributes, attributes inside a map-spec subpattern,
and attributes under `pull-many` and `entity` all become total; `:db/id` and
a caller's own `:limit` are left alone. Every reader the assignment listed —
`seon.test/changed-since-green`'s `{:seon.test/reach [:db/id]}`
(`src/seon/test.clj:62`), `seon.test/host`'s `{:seon.fn/calls …}`
(`src/seon/test.clj:247`), `seon.test.selection`'s call/reference rows,
`seon.render.test`'s `:seon.test/failures` and `:seon.fn/calls`
(`src/seon/render/test.clj:15`, `:17`), `seon.problems` (`:358`),
`seon.cluster.source` (`:410`) — is total by construction, with no edit at
the call site.

**Residue, recorded not fixed:** a WILDCARD clause names no attribute, so
`[*]` (and `seon.db/entity`, which is a wildcard pull) is still cut at
1 000. The wildcard expansion path looks its options up in the compiled
spec's `:attrs` (`pull_api.cljc:423-426`), so `[* [:a :limit nil]]` does
widen a named attribute beside a wildcard, but widening EVERY
cardinality-many attribute would mean rebuilding and reparsing a
projection-sized selector on every `entity` call. Filed rather than tuned.

**The class this slice cost an hour to, recorded because it will recur.**
`total-pull-arguments` first returned a SEQ (`cons`). `append-pull-evidence!`
replays arguments with `(assoc arguments 0 selector)` (`src/seon/db.clj:714`),
which throws on a seq — INSIDE `pull-call`'s own `catch Throwable`, so every
positional `seon.db/pull` returned a flat dependency error and each caller
read that error as absence. Two readers of that absence:
`seon.cluster/transact-initialization!`'s readiness pre-read
(`src/seon/cluster.clj:1243-1254`) refused the canonical fixture base with
"Initialization lookup refs do not resolve", and — because the edit hook
hot-reloaded the intermediate version into the development JVM while
tree-wide adoption was refused — `seon.config/effective` read the error map
as the config row and reported 68 missing facts, while config apply read
"no such entity" and produced a conflicting upsert. The orchestrator
hot-loaded the corrected version by hand. Two lessons: a seam that catches
`Throwable` around its own evidence-writing turns a programming error into
a silent absence at every caller, and an intermediate edit is LIVE in the
development JVM even when adoption is refused everywhere else.

Regression: `seon.db-test/a-pull-reads-every-member-of-a-cardinality-many-attribute`
builds an entity with 1 001 members in a scalar cardinality-many attribute
and 1 001 in a ref one, then asserts raw `datahike.api/pull` returns exactly
1 000 (the cut is real) while `seon.db/pull` (plain, subpattern and
unexpanded ref), `seon.db/pull-many` and a Datalog `:find` all return 1 001,
and that a caller-spelled `:limit 10` is still honoured.

## 2. `seon.render/captured-history` compared a selected capture to an unselected join

**Done — `846d75e9c`.** A capture holds `compose(select(units))` plus the turn frame —
history under the agent's token budget
(`seon.cluster.prompt/acquire-context-report`). `captured-history` lived at
`seon.render/acquire-context!`, which holds the acquired UNITS and not the
composition, and compared the capture against `(apply str segments)`, the
whole unselected join. Equal only while the budget keeps every unit; a false
`capture-mismatch` at every smaller budget. Inert at the shipped
1 000 000 budget, which is why it was invisible.

This is the owner law of 2026-08-29 — a seam acting on a mirror its
authority will re-decide — so the fix is dissolution, not repair:
`captured-history` is deleted from `src/seon/render.clj` (with its now-unused
`seon.repl` require) and the comparison is made by the composing function,
`seon.cluster.prompt/capture-mismatch`, against the exact bytes it just
composed. A live turn has no capture yet and is unaffected; a replay is now
exact at any budget.

Regression:
`seon.cluster.prompt-test/a-replay-under-a-selecting-budget-reconstructs-its-own-capture`
composes under a 3-token budget (so the capture is provably NOT the join),
stores that as the capture, and asserts the replay is not a mismatch — then
drifts the capture and asserts `:seon.cluster.prompt/capture-mismatch` by
name. `seon.render.web-debug-test` no longer probes the removed private var.

## 3. `:entity-id/syntax` in the transcript render

**Deferred — held path, and the caller was not isolated.** The sighting is
real and reproducible in a gate log: three
`datahike.db.utils [138 10] Expected number or lookup ref for entity id, got
"target-fact"` lines per render in
`seon.render.transcript-test/malformed-receipt-bytes-and-any-unique-about-stay-replayable`,
and sixteen with `"generated-target"` in
`every-generated-history-is-ordered-and-total`
(`tmp/s11-render-final-fast.log:83-89`, `:134-168`). The values are the
fixtures' own identity strings, so an id string reaches
`datahike.db.utils/entid` (`reference-code/datahike/src/datahike/db/utils.cljc:139`)
where an eid or lookup ref belongs; `seon.db`'s `lookup-ref-error`
(`src/seon/db.clj:1218`) only screens SEQUENTIAL entity ids, so the
dependency raises, `pull-call`'s `catch` turns it into a flat
`dependency-error`, and the caller reads that as absence.

The candidate readers walked without finding the site:
`seon.render.transcript/about-identities` (`:212-256`, its
`[attribute identity-value]` pulls are lookup refs and screened),
`message-entry` (`:277`), `message-text` (`:432`) and
`seon.cluster.message/identity-reference` (`:301-323`, whose non-vector,
non-map branch passes `reference` straight through as an entity id — the
shape that would produce exactly this — but every observed caller hands it
`{:db/id n}` or a lookup ref). Isolating it needs a live reproduction, and
`src/seon/render/transcript.clj` was held dirty by another lane throughout
(`git status`: ` M src/seon/render/transcript.clj`), so no edit was made.

**Hunk for whoever holds the file:** instrument
`seon.cluster.message/identity-reference`'s pass-through branch (or run
`seon.render.transcript-test` with a breakpoint on `entid`) to name the
caller, then fix the caller. The seam-side companion worth landing beside
it: `seon.db/lookup-ref-error` should refuse a non-numeric, non-keyword,
non-sequential entity id by name instead of letting the dependency raise and
log, so the NEXT sighting names its operation and offending value instead of
three unattributed `:error` lines.

## 4. `:defined-by` carried onto the declaration row

**Done — `8242ec533`.** clj-kondo's `:defined-by` was already normalized by
the indexer (`src/seon/fn/analyzer.clj:111`) and then dropped when the row
was built, so `deftype`/`defrecord` positional constructors and
`defprotocol` method signatures — vars with no body, nowhere to hang a
`:malli/schema` and nothing for instrumentation to arm — counted as
uncontracted public functions (6 of the 8 `src` contract subjects, per
`review-first-task-detectors-1a42fbfa7-2026-09-17.md:26-31`).

Four edits in ONE publication, as the schema-resource rule requires:
`resources/seon/schemas/seon.fn.edn` declares `:seon.fn/defined-by` as an
indexed `:qualified-symbol` AND lists it on the `:seon.fn/fn` entity map;
`seon.fn`'s row constructor keeps the fact; and
`seon.issue.detect/public-without-contract` excludes a declaration whose
`:seon.fn/defined-by` is one of Clojure's bodiless interning forms
(`bodiless-symbols`, a Datalog clause over the fact — not a roster of our
symbols).

The entity-map half is not optional and is not a second declaration:
`seon.program/canonical-row` derives ownership from the family's declared
entity map (`src/seon/program.cljc:910-922`), so an attribute the indexer
emits and the entity map does not declare is dropped from the canonical
row — which `seon.fn-test/the-indexer-emits-no-attribute-the-program-row-schema-drops`
caught on the first fast run. `src/seon/program.cljc` was held by another
lane and needed no change.

Regression:
`seon.issue.detect-test/the-contract-standard-excludes-a-var-no-author-gave-a-body`
seeds a `deftype` constructor, a `defprotocol` method and a plain `defn`
with their `:seon.fn/defined-by` facts and asserts the first two are not
contract subjects while the `defn` — and a row carrying no `defined-by`
fact at all — still are. `src/seon/fn.clj` was dirty when the queue was
written and free by the time the item was reached.

## 5. The test reporter drops ex-data

**Deferred — held path.** `src/seon/test/runner.clj` was held dirty by
another lane for the whole session (`git diff --stat`: 179 insertions across
hunks at lines 19, 986-1134, 2124+ and 2400).

**Hunk, ready to apply:** `seon.test.runner/throwable-text`
(`src/seon/test/runner.clj:133-150`) prints `(.getName (class failure))`,
`(ex-message failure)` and `(take (:seon.print/length options)
(.getStackTrace failure))` — and never `(ex-data failure)`. When the ex-data
is a `:seon.error` value (which is exactly what
`seon.test-support/checked-fixture-result` throws), render it through the
error render pair after the message and before the frames, bounded by the
same declared `:seon.print/length` — NOT by the AI profile, which is the one
clipping spot and belongs to the agent projection, not a gate log. The
sibling change the issue asks for is storing it as
`:seon.test.failure/throwable` data rather than only its message.
Issue: [a fixture refusal loses its diagnostic at the test reporter](../../../seon/issues/a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter.md).

## Verification boundary

Iteration was `bin/test-fast --paths <owned files> -- <namespaces>`; a fast
run shares the worker's contract arming but not its isolation, retained run
roots, platform tier or recorded result facts. The cold gate for these
slices is the orchestrator's; no `bin/test` was run from this lane.

Fast tallies at the committed state:

| run | selection | result |
|---|---|---|
| `tmp/small-fixes-run4-db.log` | `seon.db-test` (db.clj + db_test.clj) | 53 tests, 404 assertions, 1 failure — `pull-many-preserves-input-alignment-with-one-shared-plan`, a stale expectation of the un-totalized selector, corrected in the same commit and re-run below |
| `tmp/small-fixes-run2b.log` | `seon.cluster.prompt-test seon.render.web-debug-test seon.issue.detect-test seon.fn-test` | 93 tests, 799 assertions, 4 failures, all in `seon.fn-test` |
| `tmp/small-fixes-run5-fn.log` | `seon.fn-test seon.issue.detect-test` after the entity-map declaration | 63 tests, 460 assertions, 1 failure |
| `tmp/small-fixes-run6-detect.log` | `seon.issue.detect-test` with the new regression | 5 tests, 31 assertions, 0 failures |

The one surviving red,
`seon.fn-test/sci-evaluation-has-one-first-party-owning-namespace`, is
FOREIGN and pre-existing: it names `my.program/native!` beside the three
`seon.sci.eval` functions, and `native!` is identical at HEAD and in the
working tree (`src/my/program.clj:442`), untouched by any file this lane
edited. `seon.db-test` still owes one confirming fast run after the stale
alignment expectation was corrected — the correction is one literal in the
test, landed in `6a0f8a08a`.

A control run with `db.clj` at HEAD and only the new test overlaid
(`tmp/small-fixes-control-head-db.log`) failed exactly the four totality
assertions and nothing else, which is the regression proving it fails
without the fix.
