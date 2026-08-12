---
type: research
status: active
tags: [research, agent, context, datahike]
---

# Session-curation transcript supersession

## Result

A curated run can replace an original run without mutating history, but the
current renderer does not yet have one run-selection seam. Run-backed history
is selected independently in receipt counts, comment-only form queries,
recent-receipt queries, and bootstrap-receipt queries. The final ordering and
token walk are centralized later.

The recommended design is one explicit, cardinality-many connection from the
verified replacement run to the runs it supersedes:
`:seon.cluster.run/supersedes`. The projection derives the active run set once
from the current database value, uses that same set for counts and candidate
pulls, anchors replacement entries at the replaced run's transcript position,
and only then runs the existing bootstrap-plus-tail token walk. The original
run, forms, and receipts remain ordinary historical facts.

This is also the right run-backed mechanism for compaction: a compacted run can
supersede several earlier runs and the same projection selects its active
representatives. It is not yet a complete whole-transcript compaction
mechanism because messages are selected independently of runs.

I read
[`bootstrap-vector-design-2026-08-01.md`](../plan/bootstrap-vector-design-2026-08-01.md)
end to end before this design, as requested. I also read
`src/seon/render/transcript.clj` completely, plus the current ruling #24 and
dynamic-transcript sections in
[`README.md`](../plan/README.md) and
[`repl-session-context-2026-08-01.md`](../plan/repl-session-context-2026-08-01.md).

## Dependency ledger and proof surface

- Seon source revision during the probe: `aacba2d29`.
- Root gitlink and checked-out Datahike revision:
  `574c5f0f0db9`. Datahike creates a branch from
  an existing branch or exact commit ID at
  `reference-code/datahike/src/datahike/versioning.cljc:237-270`; a branch
  database value is loaded independently at
  `reference-code/datahike/src/datahike/versioning.cljc:488-499`.
- Seon's branch lifecycle owner preserves that vocabulary and accepts a branch
  keyword or commit ID at `src/seon/cluster/registry.clj:160-198`. The store
  opens one live connection to a named branch at
  `src/seon/cluster/store.clj:374-405`.
- The current run, form, and receipt declarations are
  `resources/seon/schemas/seon.cluster.run.edn:1-43`,
  `resources/seon/schemas/seon.cluster.run.form.edn:1-24`, and
  `resources/seon/schemas/seon.cluster.eval.edn:1-55`. None declares a
  supersession, branch, basis transaction, or commit ID attribute.
- The run transition owner derives form and receipt identities from run ID and
  ordinal at `src/seon/cluster/run.clj:394-471` and
  `src/seon/cluster/run.clj:503-537`.
- The recurring transcript proofs cover time ordering and projection-only
  detail at `test/seon/render/transcript_test.clj:195-238`, loud oldest-first
  elision at `test/seon/render/transcript_test.clj:240-260`, pinned bootstrap
  plus newest tail at `test/seon/render/transcript_test.clj:307-336`, bounded
  candidate pulls at `test/seon/render/transcript_test.clj:499-523`, and the
  generated contiguous-tail property at
  `test/seon/render/transcript_test.clj:617-682`.

The live falsifier used only the named scratch cluster
`transcript-curation-0804`; it never read or wrote `default`. The probe:

1. committed original run `curation-original` with two receipts to the scratch
   cluster branch;
2. forked exact commit `6a724bbf-d19d-5145-ac2f-bca5b545e959` to
   `:curation-probe-fork-0804`;
3. committed shorter run `curation-curated` with one receipt only on that
   fork; and
4. rendered the same agent from the main and fork database values, then called
   the renderer's private pure selection/projection functions for measurement.

Observed results:

| Database value | Run IDs visible to the query | AI transcript tokens | Rendered entries |
|---|---|---:|---|
| scratch main branch | `curation-original` | 30 | original failure, original success |
| child fork | `curation-original`, `curation-curated` | 40 | both runs, oldest first |
| manually selected original run | `curation-original` | 30 | two receipts |
| manually selected curated run | `curation-curated` | 9 | one receipt |

The actual bound is the larger AI/serialized-HTML twin, not the AI count. The
same entries measured 166 twin tokens for the original run, 90 for the curated
run, and 231 for both. At a requested budget of 140, the unmodified projection
showed only the newest curated receipt and reported two elided entries. If the
original is removed from the active set before accounting, the curated run's
90-token twin fits with no false elision marker. Supersession therefore has to
precede `total`, `minimum`, and the backwards walk; post-filtering rendered
bytes is incorrect.

## Findings

### 1. Selection is scattered; final ordering is centralized

The renderer does not select or order runs as units. It flattens messages,
comment-only forms, and receipts into transcript entries:

| Current owner | What it selects | Supersession consequence |
|---|---|---|
| `message-count`, `recent-message-rows` | every message to or from the agent (`transcript.clj:81-92,140-154`) | unaffected; messages have no run connection |
| `receipt-count` | every receipt reached through any run owned by the agent (`transcript.clj:94-104`) | currently counts both R and C |
| `comment-form-rows` | comment-only forms in any agent run that lack a same-ordinal receipt (`transcript.clj:106-130`) | currently selects comment-only forms from both R and C |
| `recent-receipt-rows` | newest non-bootstrap receipts through all agent runs (`transcript.clj:156-171`) | currently pulls both R and C |
| `pinned-receipt-ids` | every receipt in the derived bootstrap run (`transcript.clj:177-186`) | bootstrap is a separate pinned exception |
| `form-sources` | source joined by the already-selected receipt's run and ordinal (`transcript.clj:215-229`) | becomes correct automatically once receipt IDs are filtered |

`candidate-entity-ids` merges those independent streams, sorts the recent rows
newest-first, takes the requested limit, and adds all bootstrap receipts back
to the eval IDs (`transcript.clj:188-213`). `history` then pulls the selected
entities, converts each to one entry, marks bootstrap run entries pinned, and
sorts the combined result oldest-first (`transcript.clj:367-384`).

The single final comparator is `entry-order`: stored instant, then kind
(`message`, `attempt`, `input`, `eval`), then entry ID
(`transcript.clj:361-365`). Receipt ordinals do not directly order the
transcript; their stored `:seon.cluster.eval/at` values do. Ordinary evaluated
forms are not separate entries: `receipt-text` prints the source and receipt as
one entry (`transcript.clj:463-480`). Only comment-only sources without a
receipt become `:input` entries (`transcript.clj:343-359`).

Thus there is no existing one-line filter where “C supersedes R” can be added
correctly. There is, however, one clean seam to create: `projection` can derive
the active run EIDs once and pass them to both `history-count` and `history`.
All run-backed queries must consume that same set. A predicate pasted into only
`recent-receipt-rows` would leave `total`, the elision marker, and comment-only
forms wrong.

There is a second, smaller ordering decision. If C retains its fork execution
timestamps, selecting C and omitting R moves the corrected session to curation
time. A rewritten session should occupy R's historical slot. The projection
therefore needs a derived replacement anchor as well as a visibility filter;
it must not rewrite either run's stored timestamps.

### 2. The current elision walk is pinned prefix plus contiguous newest tail

The August 1 bootstrap design accurately recorded the then-unbuilt pinning gap
at `bootstrap-vector-design-2026-08-01.md:72-84`. Pinning has since landed.
The current walk at `src/seon/render/transcript.clj:603-654` is exactly:

1. Count all messages, receipts, and comment-only forms for the agent.
2. Let `requested` be the non-negative token budget. Fetch at most
   `max(6, requested)` recent entries; the requested token count doubles as a
   conservative row-fetch cap (`transcript.clj:607-613`).
3. Pull every bootstrap receipt regardless of that cap, project it at full
   detail, and remove all pinned entries from the ordinary candidate vector
   (`transcript.clj:614-619`).
4. Compute `unacquired = total - pinned-count - acquired`. This is the prefix
   omitted by the bounded candidate pull (`transcript.clj:620-621`).
5. Compute the floor as the larger token estimate of the AI and serialized
   HTML twins containing the full pinned prefix plus a loud marker for every
   non-pinned entry. Raise the effective budget to that floor
   (`transcript.clj:622-625,587-595`).
6. Walk the candidate vector backwards from newest to oldest. Only the newest
   six candidates are attempted at `:full`; every entry may then be attempted
   at `:summary`. Both modes currently produce the same text, but the detail
   value remains visible in HTML metadata (`transcript.clj:626-649`).
7. The first candidate that cannot fit ends the walk. That entry, all older
   acquired entries, and all unacquired entries become one elided count
   (`transcript.clj:650-654`). The visible non-pinned result is therefore
   always a contiguous newest tail.
8. Output order is pinned prefix, optional “middle” marker, then ordinary tail
   when bootstrap entries exist; otherwise it is an “older” marker plus tail
   (`transcript.clj:540-585`).

Supersession must transform the semantic history before step 1. A shorter C
changes the active count, twin token cost, minimum marker, candidate limit,
and the exact point where the backwards walk stops. Counting R and removing it
after the walk can falsely elide an older useful entry and falsely tell the
agent that hidden entries remain.

The bootstrap must remain outside this relation. Ruling #24 pins it permanently
(`repl-session-context-2026-08-01.md:146-154`), and the current renderer
recognizes it by the derived `bootstrap/run-id` rather than a stored flag
(`transcript.clj:177-186,378-382`). The supersession transaction should refuse
the bootstrap as a target rather than adding another projection exception.

### 3. The renderer is branch-local and basis-agnostic, not cross-branch

The scratch proof is decisive: the main database value rendered only R; the
fork database value rendered R and C. A Datahike child branch inherits its
ancestor facts, but its later facts do not appear in the ancestor. The
renderer receives exactly one immutable `:seon.db/db` and runs every query
against it (`transcript.clj:367-384,603-613`). It never opens another branch.

The receipt selector pulls ID, ordinal, stored time, result/error/output facts,
namespace, and run ID (`transcript.clj:40-55`). The form selector adds the
owning run's `opened-at` for comment-only input (`transcript.clj:57-70`). No
selector reads a branch, basis transaction, or commit ID, and the current
schemas declare none. Consequently:

- a receipt left only on the verification fork is invisible to the agent's
  current branch;
- once the verified candidate run, forms, receipts, referenced namespace/error
  entities, and supersession connection are committed to the current branch,
  the renderer does not care where they were evaluated; and
- importing only receipt rows is insufficient. `form-sources` requires a
  same-run, same-ordinal form in the current database value
  (`transcript.clj:215-229`), and the pulled refs must resolve there.

There is one current-basis assumption worth making explicit. Stored source,
result EDN, output, and triage bytes remain the receipt's facts, but rich
rendering is selected against the database and live SCI context supplied to
the current render. `rendered-family` finds the owning namespace and calls the
current declared producer (`transcript.clj:409-421`); the receipt producer
reconstructs the error face from stored triage data at
`src/seon/cluster/run.clj:1143-1174`. Thus an old local receipt and an imported
fork receipt are both dynamically re-rendered under the current program graph.
If exact historical producer behavior is required, the present renderer does
not provide it for either class of receipt.

The transcript also records `:seon.cluster.eval/result-blob` in its pulled
entry (`transcript.clj:319-341`) but `bounded-result` renders only
`result-edn` (`transcript.clj:439-453`). The current transcript never reads a
receipt result blob through the branch connection. That is the still-unbuilt
re-query/detail side of the August 1 compaction design
(`repl-session-context-2026-08-01.md:116-126`), not a foreign-branch special
case.

### 4. An instructive failed form is source ordering, not a marker

The receipt facts can faithfully show a failure: `error`, `triage-edn`,
`:seon.error/kind`, problem ID, interruption, output, source, namespace, and
ordinal are all pulled into the entry (`transcript.clj:319-341`). The renderer
does not declare or inspect an “instructive”, “pedagogical”, “keep”, or
per-form pin attribute. Its only pin policy is whole-run bootstrap identity.

Therefore the curator's proposed rule is already expressible without another
fact: when one failure is pedagogically load-bearing, keep that actual failing
form in C's corrected sources vector at the intended ordinal, followed by the
repair. The candidate is re-executed, so the resulting receipt carries the
real failure face. Omitting the form omits the experience; including and
ordering it preserves the experience.

This does not guarantee that the form survives a later token-bound elision.
Per-form pinning does not exist. That is healthy for the first design: do not
invent a durable pedagogical label before model evidence shows that ordinary
curated ordering plus the existing tail is insufficient.

### 5. Supersession is the compaction selection mechanism, with one boundary

Ruling #24 says compaction is token-bound, discrete, and stable between
boundaries; it elides older work behind a permanently pinned bootstrap
(`repl-session-context-2026-08-01.md:132-154`). Current `projection` already
implements a token-bound render, but it recomputes its tail on every call from
current facts. It does not record a discrete compaction boundary or replacement
summary.

Run supersession supplies the missing stable fact without storing rendered
bytes:

- session curation publishes a verified corrected run C and says C supersedes
  messy run R;
- compaction may publish a verified compact run K and say K supersedes several
  earlier runs; and
- the transcript always derives active representatives, then applies one
  token-bound walk to them.

That is one projection mechanism for run/form/receipt history. It keeps the
semantic operation independent of its producer: nothing in the renderer needs
to ask whether C came from a curator or K came from compaction.

The boundary is messages. Message counts and candidates do not traverse runs,
so a run-level relation cannot replace or summarize them. The design should
claim one mechanism for run-backed session history now, while leaving message
compaction explicit. Generalizing the first relation to arbitrary transcript
entities would add semantics no current requirement or code proves.

### Render-quality finding

The scratch receipt with `:seon.cluster.eval/error` but no `triage-edn`
rendered as a prompt followed by the naked sentence “The exploratory call
failed.” The fallback is intentional at `src/seon/cluster/run.clj:1155-1166`,
but it is visually weak: it does not say `Execution error` and can be mistaken
for ordinary output. Complete run-loop receipts normally carry triage evidence;
the permissive schema still makes this fallback reachable. This belongs with
the existing REPL-parity issue
[`repl-parity-divergences.md`](../../../seon/issues/repl-parity-divergences.md),
not in the supersession implementation.

The elision marker itself was legible and loud in the probe.

## Recommended design

### Declare one direct connection

Add this optional connection to the existing run declaration, not a curation
entity kind and not a second transcript registry:

```clojure
:seon.cluster.run/supersedes [:set :seon.db/ref]
```

On replacement run C, the values are the run entities whose transcript content
C replaces. Cardinality-many costs nothing for the one-to-one curation case
and lets a future compact run replace several runs without another mechanism.
The set has honest membership semantics; order is derived from the replaced
runs' stored positions.

The connection records a durable semantic decision, not a render snapshot.
Retracting it naturally restores the old projection. History remains queryable
through R, its forms, its receipts, and Datahike history.

### Publish verification and visibility atomically

The curation owner should evaluate C on the fork of R's opening basis, then
commit the ordinary candidate run/form/receipt facts and C's supersession
connection to the agent's current branch in one guarded transaction. The
renderer must not open or join the verification branch.

That transaction should refuse unless:

- C and every target run belong to the same agent;
- C and every target are distinct;
- C is closed and every evaluable candidate form has a terminal receipt;
- the verifier accepted re-execution from the required opening basis;
- no target is the derived bootstrap run; and
- adding the edges creates no supersession cycle.

The verifier's branch/commit evidence belongs to the curation owner's facts if
it must be audited later. It is not a transcript-selection input. The
renderer needs only the current database value and the explicit run
connections.

### Create one active-run seam before accounting

At the top of `projection`, derive once:

```text
agent runs - runs targeted by an accepted supersession connection
```

Pass that exact active-run set to both count and candidate acquisition. In
particular:

- `receipt-count` counts receipts only in active runs;
- `comment-form-rows` selects forms only in active runs;
- `recent-receipt-rows` selects receipts only in active runs;
- `pinned-receipt-ids` remains the derived bootstrap exception; and
- `form-sources` needs no new rule because it receives already-active receipt
  IDs.

Compute the set once from the same immutable database value. Do not repeat a
`not superseded` clause independently in four queries: count/pull drift would
recreate the false-marker defect this design is meant to prevent.

Chains need no latest pointer. If D supersedes C after C superseded R, both R
and C are targets and only D is active. The database retains the whole chain.

### Derive replacement order without rewriting time

For a non-replacement run, retain the current stored-time comparator. For a
replacement run, derive a run anchor from the earliest superseded target's
`opened-at`, following the chain to the oldest replaced root. Sort C's entries
contiguously at that anchor by candidate ordinal, with entry ID as the final
deterministic tie-break. This makes the corrected form vector appear where R
used to appear rather than at the later verification time.

Messages keep their stored timestamps. If a message was interleaved between
forms of R, replacing R as one corrected vector deliberately treats the run as
the curated unit and may move that message to one side of the vector. Exact
per-form message adjacency would require a finer position contract and should
not be inferred from the present requirement.

### Keep the existing token walk after semantic selection

After active entries and replacement order are derived, keep the current
projection policy:

```text
full pinned bootstrap
→ loud middle/older marker when needed
→ contiguous newest active tail
```

`total`, `pinned-count`, `unacquired`, `minimum`, and every fit check must use
only active entries. That is the behavior demonstrated by the scratch
measurement: selecting the 90-token curated twin before accounting avoids the
231-token combined history and the false two-entry elision marker.

### Recurring acceptance evidence

The implementation wave should add one database-backed transcript regression
per failure class:

1. With R and verified C present and C superseding R, AI and HTML contain C's
   entry IDs and bytes, contain none of R's, and leave R queryable directly.
2. The active count equals visible plus elided; R contributes to neither side.
3. At a budget between C's twin cost and R+C's twin cost, C renders with no
   false marker and the next eligible older active entry remains visible.
4. C occupies R's historical position even though C's receipt timestamps are
   later; C's own forms follow candidate ordinal.
5. C→R followed by D→C renders only D; retracting D→C restores C, not R.
6. Cross-agent, self, cycle, incomplete-candidate, and bootstrap-target
   supersession transactions refuse atomically.
7. A curated source vector that retains one real failed form renders the
   recorded failure followed by its repair, with no pedagogical marker fact.
8. Main-branch rendering never reads the verification fork; publishing the
   accepted candidate facts and relation is what changes the next context.

The full current transcript property should then generate supersession chains
alongside messages and receipts, and retain the existing invariants: stable
IDs, deterministic order, AI/HTML twin bound, contiguous ordinary tail, and a
loud exact elision count.
