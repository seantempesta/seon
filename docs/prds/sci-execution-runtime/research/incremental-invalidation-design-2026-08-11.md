---
type: research
status: active
tags: [research, render, database, flow, caching]
---

# Incremental render invalidation design

## Verdict

Use the existing per-cluster render proc and the existing `seon.cluster.wake`
listener. The listener should route a payload-free render wake only when the
transaction's changed attributes intersect the union of the retained render
calls' Datahike dependency-plan attributes. The render proc then dereferences
the latest database value, narrows to the calls indexed under those attributes,
and uses the existing `seon.db/read-evidence-current?` replay comparison to
decide which calls are semantically stale. No second listener, query parser,
pattern matcher, or durable invalidation table is required
(`src/seon/cluster/wake.clj:163-246`; `src/seon/render/web.clj:802-931`;
`src/seon/db.clj:316-415`).

For each stale call, re-derive once and append a new, basis-labelled render
entry after the retained entries. Keep the prior entry bytes unchanged. The
proc may replace its private `call-id -> latest evidence/output` lookup because
that lookup is disposable acceleration, but it must not replace an earlier
entry in the ordered prompt sequence. Current code conflates those two roles:
`render-call` writes the newest entry with `assoc` under `call-id`, and
`context-pass` replaces the per-agent captured-call map after every complete
walk (`src/seon/render.clj:416-462`; `src/seon/render/web.clj:786-800`).

The design should target one schema-derived pull at the agent root, not optimize
the bespoke walk. The current walk re-reads every visited entity and scans every
installed ref attribute (`src/seon/render/walk.clj:58-137,182-206,366-496`). A
root pull turns neighbourhood membership, values, arrivals, and removals into
one replayable read result. Its result can be diffed by stable logical call id,
after which only changed/new units invoke their producers and removed units
append an absence observation. The measured four-query plan is a useful cold
upper bound, but not a warm target: it costs 46.0 ms while the current retained
walk still costs 122.0 ms and 146 queries, 797 pulls, and 11,675 `datoms` calls
(`docs/prds/sci-execution-runtime/research/warm-walk-measurement-2026-08-10.md:9-26,72-104,191-209`).

## Scope and authorities read

I read the named current authorities end to end: the localized program
instructions, the flow architecture skill and its wake/render references, the
Datahike and data-oriented Clojure skills, the current transcript PRD, the
2026-08-11 transfer prompt, the warm-walk measurement, the prior invalidation
research, and the current context/UI architecture. The implementation and
dependency boundaries below were read directly; this report makes no production
edit.

The owner correction changes the center of gravity from the earlier Posh
comparison to the already-maintained first-party/Datahike mechanism. In
particular, every first-party query executes through `d/q-with-evidence`, and
every pull through `d/pull-with-evidence` or `d/pull-many-with-evidence`
(`src/seon/db.clj:800-840,842-923`).

## Dependency ledger

| Dependency or owner | Selected revision | Source boundary and finding |
|---|---|---|
| Seon | working tree on 2026-08-11 | `src/seon/cluster/wake.clj:1-63,163-258` is the one listener and payload-free wake law; `src/seon/render/web.clj:786-931` is the existing per-cluster render proc; `src/seon/render.clj:229-271,416-462` owns captured reads and retained calls; `src/seon/db.clj:199-415,486-604,800-923` owns evidence capture, revision comparison, parsing, replay, and decoding. |
| Datahike | `10540578248e` | `reference-code/datahike/src/datahike/query.cljc:2777-2933,3051-3062` derives dependency plans from parsed queries and memoizes parsing; `reference-code/datahike/src/datahike/pull_api.cljc:18-69,400-454` derives pull plans and executes evidence-bearing pulls; `reference-code/datahike/src/datahike/writing.cljc:577-611,872-889` derives modified attributes and transaction reports; `reference-code/datahike/src/datahike/writer.cljc:231-299,393-416` commits, advances revisions, calls listeners, then delivers the caller's result. |
| Datalog parser | dependency selected by Datahike above | `reference-code/datalog-parser/src/datalog/parser/pull.cljc:11-61,71-82,131-145,200-235` defines the parsed `PullSpec`; reverse display attributes normalize to the stored forward attribute in `:attr`, and recursion/subpatterns are explicit options. |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | Seon's existing render channel is `(sliding-buffer 1)` (`src/seon/cluster.clj:2198-2205`); delivery is `offer!`, never a parking operation (`src/seon/cluster/wake.clj:146-161`). |
| persistent-sorted-set | `e1a17bbe767c7801e67407c81f64efabfd2f1601` | Diff buffering is a persistence write-amplification optimization, not a database-value diff API (`reference-code/persistent-sorted-set/doc/diff-buffering.md:1-16,24-54`). Its optional content-defined tree buys canonical structure at a hashing cost and is not recommended for ordinary single-writer use (`reference-code/persistent-sorted-set/doc/merkle-search-tree.md:98-113,160-170`). |
| Posh | `2347c8505f795ab252dbab2fcdf27eca65a75b58` | Comparative only: it derives E/A/V patterns, matches transaction datoms, and reloads affected reads (`reference-code/posh/src/posh/lib/datom_matcher.cljc:4-29`; `reference-code/posh/src/posh/lib/q_analyze.cljc:183-220`; `reference-code/posh/src/posh/lib/update.cljc:53-79`). Seon does not adopt this layer. |

The root gitlinks and dependency checkouts agreed on all three revisions. Two
current documentation authorities still name an older Datahike pin; that is
recorded under issue notes rather than used as evidence
(`.agents/skills/datahike/SKILL.md:35-40`;
`docs/seon/architecture/library-grounding.md:14-24`).

## Existing mechanism

### Commit and wake

Datahike's writer serially applies a transaction, commits the resulting
database value, advances the query-cache context using the transaction's
modified attributes, and installs that value on the connection
(`reference-code/datahike/src/datahike/writer.cljc:231-275`). The public
transaction path calls registered listeners before delivering the transaction
report to the committing caller (`reference-code/datahike/src/datahike/writer.cljc:393-416`).
The report carries `:db-before`, `:db-after`, `:tx-data`, `:tempids`, and
`:tx-meta`; the writer replaces `:db-after` with the committed value and adds
the commit id to transaction metadata (`reference-code/datahike/src/datahike/writing.cljc:604-611`;
`reference-code/datahike/src/datahike/writer.cljc:270-275`).

`seon.cluster.wake/route!` is already the one registration. Its handler is one
`try/catch`, offers only to nonparking channels, and never queries or transacts
(`src/seon/cluster/wake.clj:163-246`). These constraints are correctness
constraints: the callback runs before the committing caller is delivered, so a
throw or parking operation delays or strands the caller
(`reference-code/datahike/src/datahike/writer.cljc:393-416`;
`src/seon/cluster/wake.clj:12-32`). The render channel already has a
sliding-one buffer and already belongs to the existing per-cluster render proc
(`src/seon/cluster.clj:2126-2159,2198-2233`).

The listener currently wakes render unconditionally once per report because no
computed render-interest set was available when it was written
(`src/seon/cluster/wake.clj:180-196,209-223`). The incremental change is to give
that same listener a process-local render-interest projection and replace the
unconditional offer with an attribute-intersection offer. Agent work routing
keeps its existing computed disjointness law; render is passive and remains
outside that law because a repaint cannot make an agent perform work
(`src/seon/cluster/wake.clj:34-49,78-93,180-196`).

### Reads and fingerprints

`seon.db` already captures evidence at the read door. Query evidence retains
the Datahike dependency plan, a replayable request when exactly one database
source is present, and the stable result; pull evidence retains the same three
things (`src/seon/db.clj:199-270`). `render/invoke-selected` supplies the
per-call evidence sink to SCI, and `render-call` converts captured reads into
retained evidence beside the output (`src/seon/render.clj:244-271,441-459`).

The query parser is not a future mechanism. Seon's result decoder calls
`datahike.query/memoized-parse-query`; its `query-variable-attributes` walks
typed `Pattern` nodes and resolves attributes supplied through scalar `:in`
bindings (`src/seon/db.clj:486-535,572-604`). The evidence-bearing execution
uses Datahike's fuller version of the same parsed-semantic derivation: it walks
patterns, rules, database functions, and find-pulls, binds scalar inputs,
partitions attributes by database source, and widens unknown semantics to
`:all` (`reference-code/datahike/src/datahike/query.cljc:2777-2904`). Parsed
queries are memoized once at the dependency (`reference-code/datahike/src/datahike/query.cljc:3051-3062`).

A direct probe on the selected revision confirmed that
`[:in $ ?a :where [?e ?a ?v]]` with `?a` bound to
`:seon.cluster.message/content` produces that concrete singleton fingerprint;
the result follows the scalar-input binding path at
`reference-code/datahike/src/datahike/query.cljc:2844-2863` and the pattern
dependency path at `:2777-2803`.

Pull has the same maintained path. `PullSpec` is `{:wildcard? boolean :attrs
map}`; every attr option carries canonical stored `:attr`, optional
`:subpattern`, or optional `:recursion` (`reference-code/datalog-parser/src/datalog/parser/pull.cljc:11-61,200-235`). A reverse selector such as
`:seon.cluster.message/_to` retains that display key but normalizes `:attr` to
`:seon.cluster.message/to` (`reference-code/datalog-parser/src/datalog/parser/pull.cljc:24-31,71-75`). Datahike recursively unions those canonical attributes,
adds lookup-ref identity attributes, and returns `:all` for a wildcard or
dynamic attribute (`reference-code/datahike/src/datahike/pull_api.cljc:18-69`).
A direct probe confirmed that a reverse message selector with nested content
produces exactly `#{:seon.cluster.message/to
:seon.cluster.message/content}` plus the root lookup attribute.

Datahike already advances an attribute revision map at commit. It derives the
changed attribute set from transaction datoms, widens empty internal reports to
an unknown/conservative revision, and advances the cache context before the
connection is reset (`reference-code/datahike/src/datahike/writing.cljc:577-602`;
`reference-code/datahike/src/datahike/writer.cljc:245-268`). Seon retains only
the selected revisions for each fingerprint and compares them with the current
database value (`src/seon/db.clj:293-343`). A revision mismatch is only a cheap
candidate test: `read-evidence-current?` replays the retained request and
compares stable results before declaring the call stale
(`src/seon/db.clj:366-415`). This second test removes attribute-level false
positives without needing entity-level listener patterns.

## Recommended data and control flow

### 1. Retain two derived projections, not one overloaded map

The render proc should retain, per agent/projection:

- `latest-call`: `call-id -> {static evidence, read evidence, latest output}`;
- `entries`: an ordered vector of immutable, already-serialized prompt entries;
- `calls-by-attribute`: `attribute -> #{call-id}`, plus an `:all` set; and
- `processed-attribute-revisions`: the revision of every current interest at
  the last completed pass; and
- the current root acquisition result and its read evidence.

All five are process-local values owned by the existing render proc. They are
derived from captured calls and therefore may be discarded. This is consistent
with the proc's current disposable `::calls`, `::ai-calls`, fragments, and
packages (`src/seon/render/web.clj:770-800,822-882`). No database entity, atom
registry outside the pipeline, or second cache owner is introduced.

The reverse index is a projection of
`:datahike.read/dependency-plan`, interpreted by
`datahike.query/dependency-plan-attributes`; Seon already calls that
interpretation while retaining revisions (`src/seon/db.clj:316-343`;
`reference-code/datahike/src/datahike/query.cljc:2906-2933`). Its estimated
space is `O(total attribute memberships across retained calls)` and build cost
is one fold over captured evidence. These are estimates; implementation should
publish counts before adding a memory budget.

### 2. Route only an interest signal in the existing listener

Expose the union of the proc's current attribute keys through one process-local
value carried in the existing cluster view, just as the view already carries
registration and latest-package atoms (`src/seon/cluster.clj:2206-2217`). In
the existing `doseq` over `:tx-data`, record whether any datom attribute is in
that union or the union contains `:all`; after the loop, `deliver!` one
payload-free wake when interested. Do not put the report, call ids, or changed
attributes on the render channel. The handler remains `O(number of transaction
datoms)`—the same traversal it already performs for agent routing—and every
operation remains nonparking (`src/seon/cluster/wake.clj:207-245`).

Before the first derivation the published interest is `:all`. Register the
listener first, start/resume the existing graph, inject one render wake, and
replace `:all` only after the first complete root read publishes its derived
index. This is the same listen-before-derive and boot-as-one-wake rule already
used by agent arming (`src/seon/cluster.clj:2173-2180,2230-2288`;
`src/seon/cluster/wake.clj:55-63`). During an index replacement, publish the
union of old and new interests, compare the newly captured revisions against
the then-current connection value, and only then drop the old interests. That
fence prevents a commit between database dereference and interest publication
from being missed.

The routed-set/turn-write disjointness property remains unchanged for work
wakes. Render interests may overlap turn-committed attributes because render is
a passive consumer; such overlap produces at most a coalesced render pass, not
new agent work (`src/seon/cluster/wake.clj:34-49,180-196`). The render proc must
therefore not transact an append from this path: doing so would convert passive
derivation into a commit loop and violate the listener contract.

### 3. Derive exact stale calls in the render proc

On a render wake, the proc should:

1. Dereference the connection once after the existing coalescing floor; this is
   the newest immutable database value (`src/seon/render/web.clj:909-921`).
2. Compare the current database's attribute revisions with
   `processed-attribute-revisions`, then select the union of call ids indexed
   under the changed attributes. Sliding-one loss is harmless because this
   comparison spans every commit since the last completed pass
   (`src/seon/db.clj:316-343`).
3. For every candidate, compare static evidence and call
   `read-evidence-current?`. A changed revision with an equal replay result is
   not stale; a changed stable result, changed input/code evidence, failed
   replay, or conservative `:all` evidence is stale
   (`src/seon/render.clj:429-459`; `src/seon/db.clj:395-415`).
4. Invoke only stale existing calls and newly discovered calls. Capture their
   replacement latest evidence/output and append one new ordered entry for each
   invocation.
5. Publish the new interest union using the handoff fence above, then advance
   `processed-attribute-revisions` to the database value the completed pass
   consumed.

This makes “exactly stale” semantic rather than merely physical. Attribute
intersection is conservative and cheap; result replay is the exact test for a
retained read. The remaining limitation is an effectful or nondeterministic
renderer, which is already outside the render contract: the intended projection
is a deterministic function of database value, code, and explicit arguments
(`docs/seon/architecture/context.md:22-39`).

### 4. Append observations; never rewrite prompt history

Each appended entry should carry a stable logical call id, the database basis
transaction/commit id of the observation, and the already-serialized bytes.
Ordering is deterministic: one wake uses one database value; changed logical
call ids are appended in the root derivation's established order. A stale call
whose producer returns byte-equal output still appends, because the required
unit is the re-derivation event, not an equality-suppressed page patch.

`latest-call` may point to the appended entry for future reuse, but existing
`entries` elements are never edited or reserialized. Thus prompt N+1 is exactly
prompt N plus a suffix, preserving the provider-cache prefix. A removed logical
unit appends an explicit absent-at-basis observation; it does not delete its
older historical observation. A newly arriving unit has no prior call entry and
appends its first observation.

This report does not choose retention aging. If a token policy must compact old
observations, it should start a new prompt generation with a new prefix rather
than mutate entries inside the current generation. That is a separate,
observable provider-cache trade-off, not part of transaction invalidation.

## The root-pull shape

The recommended acquisition is one pull or find-pull rooted at the agent. Its
selector is generated from installed schema ref declarations: ordinary
attributes at the node, every forward ref with a nested selector, and every
reverse spelling of each stored ref with the same nested selector. Requested
distance determines selector nesting depth. The current walk establishes the
same semantic requirement—both directions, one hop per distance—and derives
installed refs from `:db/valueType :db.type/ref`
(`src/seon/render/walk.clj:13-31,81-90,92-137`).

The parsed fingerprint needs no schema-specific interpretation after selector
construction. Every nested forward attribute is present in its subpattern;
every reverse display attribute normalizes to its stored forward attribute;
and a recursion option retains the traversed ref plus all other attributes in
the containing `PullSpec` (`reference-code/datalog-parser/src/datalog/parser/pull.cljc:24-49,131-145,200-235`;
`reference-code/datahike/src/datahike/pull_api.cljc:18-37`). Wildcards widen to
`:all`, so a generated selector should enumerate concrete attributes when
selective invalidation matters.

One root result is also the membership index. Diff the old and new pulled value
by stable entity/call identity to produce changed, added, and removed logical
units. This replaces `:seon.render.walk/changed-at` as an invalidation oracle;
today `changed-at` is an EAVT maximum computed only after the entity has already
been discovered, so it cannot discover arrivals and costs one entity scan per
node (`src/seon/render/walk.clj:219-226,366-370,502-566`). It may remain display
metadata until the walk is removed.

Producers that issue extra `seon.db` reads retain their own fingerprints in
addition to the root pull. A transaction may therefore stale the root
membership call, a producer call, or both. The root result diff deduplicates
the resulting logical call ids before invocation.

## Arrivals, removals, and the idempotent-assert trap

A new entity enters the neighbourhood only through a stored edge or a query
predicate. Adding a forward or reverse ref emits a datom for the canonical
stored ref attribute. That attribute is in the root selector fingerprint, so
the listener wakes, the root pull replays, and the new entity appears in the
new result. Reverse pull syntax does not create a second attribute: it
normalizes to the same stored forward attribute
(`reference-code/datalog-parser/src/datalog/parser/pull.cljc:24-31,71-75`). A
retraction follows the same path and produces a removed logical unit.

For query-shaped membership, a pattern such as
`[?message :seon.cluster.message/to ?agent]` fingerprints
`:seon.cluster.message/to`, independent of which entity currently matches.
Therefore a new matching entity is visible even though its entity id did not
exist in the retained result. This is the useful part of Posh's wildcard
pattern behavior that attribute-level routing already preserves; entity-level
precision is not required for correctness.

Datahike emits no transaction datom for an idempotent re-assertion, and the
listener consequently emits no wake (`src/seon/cluster/wake.clj:46-49`). For a
pure database projection this is correct: the before and after read results are
equal, so no call is stale. A feature that needs to observe “the same value was
asserted again” must record a distinct durable event fact; invalidation must not
pretend that a no-op transaction changed the database.

## Crash, recovery, and channel loss

The reverse index, processed revisions, latest-call lookup, root result, and
append vector are performance state. A crash discards them and every channel
value. On boot the
listener registers before derivation, the initial `:all` interest plus one
injected wake derives a fresh root result from the latest facts, and demand on
the reliable context channel can perform the same cold derivation
(`src/seon/cluster/wake.clj:55-63`; `src/seon/render/web.clj:786-800,822-826`).
No invalidation log is replayed and no transaction report must survive.

The byte-stable prefix guarantee applies to one retained prompt generation.
After a process crash, correctness is recovered from facts but provider-cache
reuse of the lost process-local prefix is not promised. Promising the identical
cross-crash prefix would require a durable prompt-generation fact or replay of
every historical basis; neither exists in this mechanism and neither should be
smuggled into a listener callback. The already-recorded sent prompt remains the
forensic byte truth, not a live cache authority
(`docs/seon/architecture/context.md:41-54`).

Sliding-one loss is free because the channel carries only “look.” If ten
interested commits coalesce, the proc compares every retained revision with the
latest database value and replays any changed read once. The existing wake law
states this explicitly (`src/seon/cluster/wake.clj:6-10,22-29`), and the
attribute-revision map supplies the basis-to-basis memory that the channel does
not (`src/seon/db.clj:316-343`).

## Cost target from the four-query floor

The measured 46.0 ms four-query plan proves that the current 122.0 ms warm walk
is not an inherent context cost, but it is still too expensive as a no-change
steady-state path (`docs/prds/sci-execution-runtime/research/warm-walk-measurement-2026-08-10.md:147-209`). The target cost classes are:

| Case | Target work | Reason |
|---|---|---|
| Prompt acquisition, no relevant commit | `O(1)` retained-generation lookup plus returning retained bytes; zero database reads and zero producer calls | The current same-basis check already proves all 96 outputs reusable, but reaches it after the whole walk (`warm-walk-measurement-2026-08-10.md:72-104,211-232`). |
| Irrelevant commit | One listener pass over transaction datoms; zero render-proc wake | Attribute union rejects it at the existing routing seam (`src/seon/cluster/wake.clj:207-245`). |
| Relevant commit with unchanged semantic read | One candidate read replay; zero producer calls and zero appended entries | Attribute precision may overselect; stable result equality removes the false positive (`src/seon/db.clj:395-415`). |
| Relevant membership change | One root pull replay, one old/new result diff, producer calls only for changed/new units, and suffix serialization only | The pull result replaces recursive discovery and carries arrivals/removals. |
| Cold boot/crash | One root pull and full current projection | Loss costs performance only; the 46 ms four-query plan is the measured fallback ceiling, while the one-pull candidate should be measured against it. |

Implementation acceptance should therefore require zero `seon.db` reads on an
unchanged context acquisition, not merely zero producer calls. It should also
measure root-pull cold/relevant-change latency against the 46.0 ms four-query
floor and publish listener datoms scanned, candidate calls, replayed reads,
re-derived calls, and appended bytes. No numeric budget should be chosen before
that measurement.

## Comparative alternatives and ranked recommendations

### 1. Attribute fingerprints plus exact replay — recommended

Extend the existing wake route, retain the existing Datahike plans/revisions,
and make the existing render proc own the reverse index and append sequence.
Guarantee: no false negatives for dependency plans; same-attribute false
positives stop before producer invocation by result replay. Cost: one small
process-local index and a selective pass. Trade-off: an unrelated entity using
the same attribute may cause a read replay. This is the simplest viable design
and composes directly with one root pull.

### 2. Add entity/attribute candidate keys only after measurement — deferred

Transaction `:tx-data` already exposes each changed `[e a]` pair, so a future
reverse index could retain entity-qualified dependencies for pulls and literal
entity query patterns without changing Datahike's report shape
(`reference-code/datahike/src/datahike/writing.cljc:577-585`). It could reduce
same-attribute replay at the cost of larger evidence, harder query semantics,
and special handling for arrivals where the entity was not previously known.
It gives no correctness unavailable from result replay. Adopt only if counters
show attribute false positives dominate the relevant-change budget.

### 3. Posh patterns or index-to-index diff — reject

Posh can retain entity/value-sensitive E/A/V patterns and match them directly
against transaction datoms, which is more precise before query replay
(`reference-code/posh/src/posh/lib/q_analyze.cljc:183-220`;
`reference-code/posh/src/posh/lib/datom_matcher.cljc:4-29`). It also maintains a
second analysis/reload system and has narrower hand-parsed semantics than the
maintained Datahike parser. Seon does not need that precision because replay
equality is already the exact second stage.

Persistent-sorted-set exposes lookup/mutation/persistence operations, not a
public two-root logical diff; its diff buffering deliberately leaves query
semantics unchanged and exists to reduce object writes
(`reference-code/persistent-sorted-set/doc/diff-buffering.md:1-16`). A
hypothetical structural diff is additionally complicated by buffered anchors
whose address may remain while logical content changes (`:91-98`). Transaction
reports and Datahike's already-advanced attribute revisions are cheaper and
more direct for a single serialized writer.

## Datahike fork recommendation

No writer change is required for the simplest option. Datahike already scans
transaction datoms once to derive modified attributes and advances the
per-attribute revision map at commit
(`reference-code/datahike/src/datahike/writing.cljc:577-602`;
`reference-code/datahike/src/datahike/writer.cljc:257-265`). The transaction
report already contains changed datoms, so adding a duplicate changed-`[e a]`
field would save at most a small projection in a listener whose existing agent
routing already traverses `:tx-data` (`src/seon/cluster/wake.clj:224-243`).

The fork should instead fix the component-pull dependency false negative noted
below. The root-pull design avoids it by generating explicit nested selectors
for every ref, but evidence-bearing `pull` is a public maintained mechanism and
must remain sound for all valid pull forms.

## Acceptance evidence for implementation

1. A generated query/pull property mutates every concrete fingerprint
   attribute and proves: changed result implies routed candidate; unchanged
   result never invokes the producer. Include scalar `:in` attributes, reverse
   refs, nested selectors, recursion, wildcard widening, add, retract, and a
   new matching entity.
2. A listener test proves the one `route!` registration, `offer!` only, a
   sliding-one render channel, initial `:all`, one boot wake, and no query,
   commit, throw, or park in the callback (`src/seon/cluster/wake.clj:12-32,163-246`).
3. A race test commits between root database dereference and interest
   publication; the union/revision fence must schedule the missed basis before
   old interests are dropped.
4. An append test captures prompt N, changes one retained read, and proves
   prompt N+1 begins with byte-for-byte prompt N and has exactly one new
   basis-labelled entry. A byte-equal re-derivation still appends; an irrelevant
   commit and a same-attribute/equal-result commit do not.
5. A root-pull test adds and retracts a forward and reverse edge at the distance
   boundary. Arrivals append first observations and removals append absence
   observations without rewriting prior bytes.
6. A crash test discards every proc/index/channel value, registers the listener,
   injects one wake, and derives the current root projection from facts. No
   transaction-report replay or stored invalidation row may be required.
7. The measurement gate is zero database reads and zero producer calls for
   unchanged acquisition; relevant change reports root-pull reads, candidate
   reads, exact stale calls, appended bytes, and wall time against the measured
   46.0 ms four-query floor.

## Issue notes filed

- [Datahike pull evidence misses attributes read by automatic component expansion](../../../seon/issues/datahike-pull-evidence-misses-automatic-component-expansion.md).
- [Posh cardinality-one pull analysis calls a four-argument helper with three arguments](../../../seon/issues/posh-cardinality-one-pull-analysis-has-an-arity-defect.md).
- [Current Datahike pin statements drifted after the selected fork advanced](../../../seon/issues/datahike-current-pin-statements-drifted-again.md).
- [Context architecture still specifies replacement instead of append-only refresh entries](../../../seon/issues/context-architecture-conflicts-with-append-only-refresh-entries.md).
