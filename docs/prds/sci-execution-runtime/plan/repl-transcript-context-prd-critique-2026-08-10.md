---
type: research
status: active
tags: [research, render, agent]
---

# Independent critique — REPL transcript context PRD, 2026-08-10

## Verdict

**Revision pass required before owner presentation.** The draft has the right
center—one fact-derived REPL session, ordinary printed values, and on-demand
program faces—but two implementation boundaries are not launchable and the
known changed-basis walk cost is not designed away.

The single most severe finding is **IS-1**: Phase 1 removes the flattened
render-unit argument without converting the schema-declared producers that
read domain attributes from that argument. The first concrete casualty is the
message producer: after the proposed change it sees no message content and
returns `nil`. The owned-file list excludes that producer conversion, so the
phase would cut the live render ABI before its consumers can use the new one.

My verdict on **NESTED-2 is NEEDS-A-PROBE**. The rendering-stack set is a sound
structural defense against producer re-entry, but the recommendation assumes
without evidence that contract-candidate discovery is cheap and deterministic
at every nested map node. Current `candidates` scans and contract-validates the
owning namespace's public functions per call. Nested retained-call identity,
read evidence, target-specific selection, ambiguous fits, and alternating
producer chains are not specified or measured.

I read the draft, the 13-ruling document, the context-quality audit, and the
model-authoring observer report end to end. I spot-checked 18 source claims at
commit `c51cb97d5`, read the maintained Datahike input-binding implementation,
and reran six read-only claims against live cluster `default` through
`eval_clj`. No production source or live facts were changed.

## Axis 1 — rulings compliance

### RC-1 — Blocker — one print traversal cannot provide two target profiles

**Rulings:** 1, 3, 4, 9.

**Evidence.** Acceptance item 3 and the decision sentence promise one
`seon.print` traversal for AI text and HTML while also promising different
target profiles. The cited `emit-both` does tee one event stream
(`src/seon/print.cljc:584-595`), but it receives one already selected print
node and one options map. It cannot:

- fit the text and HTML projections to different token/depth/child profiles;
- select an AI producer and an HTML producer for the same nested semantic
  value; or
- represent two terminal projected nodes, because one projected node records
  one `:seon.render/output`.

The contradiction surfaces again in the debug example: it says HTML may
compose differently under the page profile, while also saying the tee makes
the twins incapable of divergence.

**Required change.** Define the one mechanism as **one ordered semantic
session value**, not one literal target emission pass. Project and fit that
value once per target under explicit AI and HTML profiles, with one retained
call/evidence key per target. Retain `emit-both` only for cases where both
sinks intentionally consume the same projected and fitted node. Add a
regression proving both target projections originate from an equal session
unit vector while allowing profile-specific elisions.

### RC-2 — Blocker — the preview proposal restores bands and a tuning panel

**Rulings:** 4, 12, 13; also the binding 2026-07-31 ruling that bands and
priority die entirely.

**Evidence.** The proposed preview profile adds
`:seon.render.profile/max-agents 24` and
`:seon.render.profile/staleness-bands [25 100 500]`, then changes depth and
children by transaction-distance tier. These are three unmeasured thresholds,
two new surface-specific controls, and a second fit policy beside the existing
generic `token-budget`, `max-depth`, and `max-children`. The draft itself says
the values need later tuning. That is not a great zero-configuration default,
and the bands directly reverse the prior owner ruling.

**Required change.** Delete `staleness-bands` and `max-agents`. A cluster's
agent children must be bounded by the same generic profile keys already used
for every collection; an elision carries the continuation. Keep only naive
derived recency plus stable-path tie-breaking. Seal one shipped preview profile
from evidence before implementation, with one obvious profile override fact.

### RC-3 — High — “one session derivation” has no named enforceable owner

**Rulings:** 1, 2, 3, 10.

**Evidence.** The draft intends one mechanism, but the lane boundaries leave
three places able to reconstruct it:

- Phase 2 moves message/form/receipt derivation “inside the one walk”;
- Phase 3 builds a debug-specific two-element package; and
- Phase 4 builds agent previews through another candidate relation and join.

No named pure function, input schema, output schema, or equality regression is
the common contract consumed by prompt acquisition, debug, and cluster-root
preview. Deleting `seon.render.transcript` does not by itself prevent three
partial reconstructions inside `walk.clj` and `web.clj`.

**Required change.** Name one pure owner such as `session-units` in the
surviving render owner, give it one database-value/request input and one
ordered open-vector output, and make all three surfaces call it. The debug and
system-view tests must compare the exact unit identities with the agent-context
derivation, not merely compare plausible rendered text.

### RC-4 — High — the public `seon.render/walk` contract is silently broken

**Rulings:** 1, 2, 13 and Seon's accretion rule.

**Evidence.** The current public function returns a string in both arities and
documents that contract (`src/seon/render.clj:591-612`). Worked example B has
the same call return a map, and adds a bare `:profile` option that the current
contract neither declares nor reads. That is a changed meaning for an existing
function, not an accreting optional input. The new bare key also violates the
fully-namespaced-key rule.

**Required change.** Do not change `seon.render/walk`'s existing return
meaning without a separate explicit owner decision. Keep the data derivation
as the internal one owner and have `walk` print it, or add a clearly named
data-returning function in the same namespace. If a profile is an input, use
the established `:seon.render/profile` contract and specify whether it carries
a profile value or an id resolved from a fact.

### RC-5 — Calibration — the draft avoids several forbidden shapes

The draft does **not** store transcript rows, activity rank, preview cards, or
staleness. Preview choice does not name message/transcript/canvas/error
families, and agent-authored comments remain source rather than pseudo-results.
Those aspects comply and should survive the revision.

## Axis 2 — implementation soundness

### IS-1 — Blocker — Phase 1 cuts the current render-producer ABI

**Evidence.** `render-argument` currently merges a map value into the unit and
also retains it at `:seon.render/value` (`src/seon/render.clj:76-108`). Phase 1
removes the merge so arbitrary user keys remain only below
`:seon.render/value`. Existing declared producers consume the flattened form.
For example, `seon.cluster.message/render-ai` reads
`:seon.cluster.message/content`, `from`, and `to` directly from `unit`
(`src/seon/cluster/message.clj:448-463`). With the proposed argument it finds
no content and returns `nil`. Run, agent, config, error, AI, effect, bootstrap,
and instruction renderers use the same `:seon.render/unit` contract; 18 source
namespaces declare that contract.

Phase 1 owns only `render.clj`, `render/value.clj`, `print.cljc`, schema
resources, and focused tests. It neither inventories nor owns the producer
conversion, so its live proof can pass on the generic floor while declared
producers disappear.

**Required change.** Make this an explicit ABI conversion wave. Inventory
every schema-attached and contract-fit producer and choose one ruled argument
shape. If values live only below `:seon.render/value`, update all producers and
their contracts in the same phase, delete flattened reads, and add one class
regression proving a declared map producer still receives its semantic value.
The message producer is the shortest falsifier. Do not start later transcript
work until this wave is complete.

### IS-2 — Blocker — the replacement transcript has no total ordering or supersession contract

**Evidence.** The exact unit table identifies families but never defines one
total order across bootstrap forms, inbound and outbound messages, runs,
unsettled forms, receipts, and multiple same-transaction messages. Current
source has an explicit order tuple for each entry family
(`src/seon/render/transcript.clj:400-416`) and excludes any run superseded by a
session-curation revision through `not-join`
(`src/seon/render/transcript.clj:85-91`). The draft's deletion inventory says
queries and order move into the walk, but no phase, falsifier, or regression
mentions `:seon.cluster.run/supersedes`. Deleting the namespace as written can
make replaced runs visible again.

**Required change.** Specify the exact order key as data, including stable
tie-breaks and where an injected message read sits relative to the run it
triggers. Preserve the active-run supersession rule explicitly. Add one
regression with same-transaction messages, two run ordinals, an unsettled form,
and a superseded run; assert the exact unit identity vector before testing any
rendered bytes.

### IS-3 — High — `seon.program/faces` contradicts its own result schema

**Evidence.** The declared result is either `[:vector :seon.program/face]` or
one outer `:seon.error/value`, while the prose requires an unknown identity to
place a flat error **inside** the vector. Those shapes cannot both validate.
The request also admits an unqualified symbol but supplies no namespace
resolution rule to the pure projection. The deep-doc examples disagree about
`:seon.schema/accepted-by`: Worked example A returns symbols, while the later
exact target returns maps containing symbol and arglists.

**Required change.** Choose and schema one answer. The accreting useful shape
is `[:vector [:or :seon.program/face :seon.error/value]]`, with one canonical
face per identity and request-order preservation. Either require qualified
identities or have the SCI wrapper resolve unqualified symbols before calling
the pure projection. Freeze one canonical deep-schema face and use it in every
worked example and regression.

### IS-4 — High — the claimed preview relation probe does not reproduce with Hiccup

**Evidence.** I reran the draft's four-column relation query with representative
two-element Hiccup values. It returned
`:seon.db/invalid-read`: `Lookup ref attribute should be marked as :db/unique:
[:div "a"]`. The maintained Datahike fork intentionally recognizes every
two-element sequential value beginning with a keyword in an input relation as
a lookup ref (`reference-code/datahike/src/datahike/query.cljc:3312-3355`). A
valid Hiccup fragment therefore collides with lookup-ref input semantics.

The worked relation also does not need Datalog: it is already derived Clojure
data, and the aggregate result is a set, not the vector the draft prints.

**Required change.** Replace the relation query and join with one deterministic
`reduce` over the derived candidate vector, comparing
`[changed-at stable-path]`. This avoids lookup-ref ambiguity, a redundant query,
and the set/vector discrepancy. Retain a test whose winning HTML is exactly
`[:div "a"]`.

### IS-5 — High — the worked examples do not all show the values their forms produce

**Evidence.** The pure total example does produce `16`, and the live program
count is currently `2775`. The stored `in-ns` receipt also confirms that an
object address is durable print-node data. However:

- the ordinary two-column `seon.db/q` in Worked example B returns a set of
  tuples, not `[[-tuple-]]`;
- the preview aggregate likewise returns a set even when its values are
  admissible;
- current `(doc :my.run/result)` returns `nil`; changing `doc` to return a data
  map is target work and a public semantic decision, not current behavior; and
- the two deep-schema examples disagree as described in IS-3.

**Required change.** Re-execute every approved example against the candidate
implementation and paste the exact admitted result. Until then, mark target
results explicitly rather than calling them actual. Add the `doc` return
semantic as an owner option: either preserve Clojure's print-and-`nil` contract
and use `faces` for data, or explicitly approve the break.

### IS-6 — High — generated schema examples make unchanged-basis output nondeterministic

**Evidence.** Deep doc requires a generated example from the registered schema
generator, but gives no seed. A test.check/Malli generated sample can differ at
the same database basis, invalidating exact worked bytes and retained output
even when code and facts are unchanged.

**Required change.** Either omit generated examples from the default deep face
or derive a stable seed from the schema identity plus program commit ID and
record that derivation in the face. Add a same-basis repeated-call equality
regression.

### IS-7 — Medium — the “unchanged basis executes no producer” claim is broader than the cited code

**Evidence.** `render-call` does reuse retained output after static and read
evidence checks (`src/seon/render.clj:401-447`), but it still calls `producer`
to select a producer before determining reuse (`:414`). Contract-candidate
selection can therefore run even when invocation is skipped. Page-package
reuse can avoid the whole derivation at an unchanged basis, but that is a web
registration boundary, not proven for prompt/debug/system consumers by this
phase.

**Required change.** State the exact gate: no producer **invocation**, no
candidate selection, or no walk derivation. Give each consumer a falsifier and
counter for the promised level.

### Source-claim spot check

| Draft claim | Source verdict |
|---|---|
| map values are merged into render arguments (`render.clj:76-108`) | Correct; also exposes IS-1. |
| top-level precedence consults contract candidates (`:203-221`) | Correct. |
| nested projection consults explicit/schema producers and a stack (`:294-353`) | Correct. |
| retained calls compare static/read evidence (`:401-447`) | Correct, with IS-7 qualification. |
| `emit-both` tees one traversal (`print.cljc:584-595`) | Correct for one node/options pair; insufficient for two profiles. |
| transcript has a hard-coded six-entry tail (`transcript.clj:26-30`) | Correct. |
| transcript orders entries and filters superseded runs | Correct in source; omitted from replacement design. |
| namespace renderer now reads the shared profile (`ns.clj:320-325`) | Correct; the earlier audit's private-key defect has since been repaired. |
| latest debug AI reads a capture while HTML is live (`web.clj:503-566`) | Correct. |
| fleet oversight is appended outside the walk (`web.clj:336-375`) | Correct. |
| package revision/delta/keyframe logic is shared (`web.clj:596-643`) | Correct. |
| agent schema points both targets at transcript wrappers | Correct (`seon.cluster.agent.edn:1-14`). |
| entity `changed-at` scans newest EAVT transaction (`walk.clj:219-225`) | Correct. |
| inbound origin is absence of `from` (`message.clj:258-304`) | Correct. |
| reply prose remains submitted source comments (`reply.clj:224-266`) | Correct. |
| form and receipt join by required run+ordinal | Correct and reproduced live. |
| `walk` currently returns the map in Worked example B | Incorrect; it returns text (`render.clj:591-612`). |
| the Hiccup-bearing preview relation was live-proven | Not reproducible with representative Hiccup; see IS-4. |

### Live-probe results

| Probe on live `default` | Result |
|---|---|
| draft's form/receipt join, limit 5 | Passed; rows carry exact source and serialized print node. |
| installed `:seon.cluster.eval/*` attribute census | Passed; all 12 attributes named by the draft are installed and there is no receipt→form ref. |
| current program-function count | Passed: `2775`. |
| bootstrap ordinal 1 source/result | Passed: `(in-ns 'my.agents.root)` stored an object print node with class, address, and representation. |
| pure worked total | Passed: `16`. |
| preview relation with `[:div "a"]` and `[:div "b"]` | Failed with `:seon.db/invalid-read`; Datahike treated Hiccup as lookup refs. |
| current `(doc :my.run/result)` | Returned `nil`; the map face is unbuilt target behavior. |

The **riskiest already-falsified premise** is that the Phase 1 argument change
preserves declared renderers. The **riskiest still-unverified premise** is that
NESTED-2 remains fast and cycle-safe for heterogeneous trees under both target
profiles.

## Axis 3 — defaults ergonomics

### DE-1 — High — a namespace-owner agent is not shown its own API

**Surface walk.** A fresh generic agent learns run/message functions and one
schema, which is a good baseline. A namespace-owner agent receives the same
opening but no summary of its assigned namespace. The replacement deletes the
old namespace card and relies on on-demand discovery, yet the 12-form default
never performs that first discovery. The agent with the strongest reason to
understand `seon.db` or `seon.render` starts with no member names from it.

**Required change.** Inject one assignment-parameterized face pull for the
agent's own namespace. This imposes no namespace function obligation: the
system supplies the same honest form for every assignment, and an empty
namespace returns an honest empty face. Ablate it during MINIMUM rather than
omitting it before measurement.

### DE-2 — High — the 20-agent system view has no sealed zero-config behavior

**Surface walk.** The worked example covers four agents. The default for 20 is
not specified: no fixed card count from the generic profile, no continuation
face for the omitted agents, no layout behavior, and no changed-basis latency
gate. Instead the reader is offered `max-agents` and three staleness bands to
tune later.

**Required change.** Show the exact 20-agent default using only the generic
preview profile: visible count, deterministic order, ordinary elision with a
requery identity, and responsive layout. Seal it before Phase 4. The override
is one preview-profile fact, not five independent knobs.

### DE-3 — High — debug's “exact model text” profile is ambiguous

**Surface walk.** The proposed pane is much better than “No recorded context
capture exists”: it works before a first attempt and shows current facts. But
the draft alternately calls it the model's exact P text, a current live
projection, and text under a debug package. If debug and prompt select
different profiles or distances, exact equality is impossible. If they share
the agent profile, HTML still uses a different page profile and cannot share
the literal tee.

**Required change.** State that the left pane calls the exact agent-context
projection with the agent profile and current database value; historical
provider bytes remain the capture. State the right pane's independent profile.
The proof compares left bytes to prompt acquisition at the same database value
and compares unit identities—not fitted bytes—between left and right.

### DE-4 — Medium — changing `doc` makes the supposedly intuitive REPL less Clojure-like

Returning a map from `doc` is useful data, but Clojure users expect `doc` to
print and return `nil`. The draft silently changes that default while claiming
stock-REPL intuition. `seon.program/faces` already provides the data-returning
surface.

**Required change.** Prefer keeping `doc` familiar and make keyword deep-doc
output print from `faces`; if the owner wants data-returning `doc`, price and
rule it explicitly.

### Quiet configuration and tuning assumptions

The draft assumes all of the following without sealing a zero-config source
or one override path:

- preview token budget `220`, depth `4`, children `12`, agents `24`, and
  staleness bands `[25 100 500]`;
- where the preview profile is stored and how a keyword profile id resolves to
  the profile value;
- which profile and distance the debug text pane uses;
- the 12-form opening rather than the six-form opening;
- five task families, `>=90%` success, `100%` settlement, and the `10%`
  provider-token boundary for the ablation study;
- the generated schema-example seed;
- a full-session history after deleting both the six-entry tail and transcript
  token budget;
- a render on every settled database wake regardless of whether the session's
  read dependencies changed; and
- `/` changing from root's namespace page to the cluster system view.

Each needs either a shipped default with evidence or an explicit owner option.

## Axis 4 — performance by construction

### PF-1 — Blocker — the design cites the warm cache and ignores the open 3,859-pull cost

**Evidence.** The draft reports 17 ms warm namespace HTML, but the open cost
note records the same source at a changed basis:

- `/ns/seon.db`: **3.168 s** after the partial fix;
- `render/render-call`: 178 calls;
- `db/pull`: **3,859** calls;
- `db/datoms`: **21,560** reads; and
- unchanged-basis reuse: 16.5 ms.

The PRD does not name, fix, bound, or gate the remaining per-node walk work.
Deleting schema text may shrink output but does not stop `neighborhood` from
pulling concrete entities, scanning installed ref attributes, and deriving
`changed-at`. Printer fitting happens after those reads. Thus the draft
**inherits and ignores** the known cost rather than fixing it.

**Required change.** Put the remaining walk-cost class before transcript and
system-view expansion, not in the final experiment. Name the source owner and
an exit: a changed-basis agent page and debug update below 1 s, bounded
pull/datom counts independent of rendered schema size, and unchanged-basis
reuse in the tens of milliseconds. A phase cannot graduate on warm-only HTTP
timings.

### PF-2 — Blocker — deleting bounded transcript acquisition creates unbounded history reads

**Evidence.** Current transcript acquisition counts history, selects bounded
candidate ids, pulls only those entities, pins bootstrap, and emits an elision
(`src/seon/render/transcript.clj:93-220,700-752`). The draft deletes the
six-entry policy and per-family token budget, then says generic `seon.print/fit`
will spend the profile. But `fit` can bound bytes only after the entire session
has been queried, joined, decoded, and ordered. A ten-turn agent may be fine; a
hundred- or thousand-turn agent has linear database and allocation growth on
every changed basis.

**Required change.** Separate bounded acquisition from generic value fitting.
Derive a pinned bootstrap plus a profile-bounded recent candidate window using
ordered ids, with one ordinary elision value addressing older history. Deep
history uses the same session-units owner with an explicit offset/requery
argument. Do not restore the magic six; derive the acquisition allowance from
the selected generic profile and measure it.

### PF-3 — High — NESTED-2 adds per-node namespace scans without a retained boundary

**Evidence.** Current `candidates` obtains public functions in the owning
namespace and validates input and output contracts (`src/seon/render.clj:110-137`).
Calling it for every nested semantic map creates approximately
`nodes × public-functions` contract checks. The rendering stack prevents
re-entry after selection; it does not make selection cheap. The PRD mentions
“retained selection evidence” but gives no call id, cache key, invalidation
evidence, or phase regression for nested nodes.

**Required probe before recommendation.** On one immutable database value:

1. render a producer delegating its own value to the floor;
2. render an alternating A→B→A chain;
3. render a heterogeneous 1,000-map tree in a namespace with at least 80
   public functions through AI and HTML profiles;
4. count candidate enumeration, contract validations, invocations, and
   retained hits; and
5. rerun at the same basis and after one unrelated transaction.

NESTED-2 is acceptable only if output is total, ambiguity is loud, no producer
re-enters, and both changed- and unchanged-basis costs meet the interactive
gate.

### PF-4 — High — debug still recomputes on every database wake

**Evidence.** Current `render-pass` derives every watched registration on a
wake (`src/seon/render/web.clj:673-738`). DEBUG-1 explicitly re-renders on every
settled database wake. A shared immutable basis prevents disagreement but does
not prevent unrelated transactions from causing full session derivation and
two target projections.

**Required change.** Reuse the session derivation and both retained target
calls from captured read evidence. Prove an unrelated transaction executes no
session query, candidate selection, or producer invocation for the watched
debug page. Keep the database listener as the wake; filtering belongs in the
derived evidence, not a new channel.

### PF-5 — High — the 20-agent system view has no credible interactive cost bound

**Projection.** Even an optimistic 50 ms per-agent session derivation consumes
1 s for 20 agents before cluster traversal, ordering, fitting, serialization,
and package construction. Current evidence includes a single changed-basis
core page at 3.168 s. The proposed `max-agents` is applied after candidate
derivation and therefore does not prove bounded query work.

**Required change.** Phase 4 needs a 20-agent fixture and counters, not only a
four-agent visual example. The gate is under 1 s changed-basis derivation,
bounded query/pull/datom counts, one shared cluster query for agent/session
identities, and no per-agent namespace walk. An update to one agent must
rederive that agent's preview plus cluster order, not all 20 sessions.

### Cost projection by surface

| Zero-config surface | Draft projection | Critical result |
|---|---|---|
| Fresh agent context | Candidate text is 1,005 estimated tokens, but no query/pull budget; current cold root page was 1.31 s. | **Unproven and already near/over 1 s.** Output size alone is not constructed speed. |
| Mid-conversation turn | Full session after bounded-tail deletion. | **Unbounded in run/form/message count.** Needs profile-driven acquisition before printing. |
| Debug live update | Every database wake, current session, two target passes. | **Unbounded and globally wake-sensitive.** Needs retained read-evidence reuse. |
| System view, 20 agents | Full candidate derivation before preview cap; only four-agent example. | **No credible sub-second path.** A 20-agent changed-basis gate is mandatory. |

## Three strongest parts to keep

1. **The deletion inventory is unusually concrete.** It names the separate
   prose assembler, transcript projection, schema wall, per-family budgets,
   latest-capture debug authority, and fleet append to remove while preserving
   provider captures as forensics. That is the right simplification posture.
2. **The durable form/value grammar is grounded.** Live probes confirmed exact
   form source, run+ordinal receipt joins, serialized admitted print nodes, and
   the stored namespace-object face. Replacing comment-framed output with
   prompt + exact source + actual value is sound.
3. **API-first context plus on-demand bulk/deep faces targets the measured
   waste directly.** It preserves the useful function name/arglists/doc shape,
   removes raw schema walls and intentional bootstrap faults, and gives the
   minimum-context experiment a falsifiable starting candidate.

## Required amendment set before presentation

The owner should see a revised draft that, at minimum:

1. converts the full render-producer argument ABI or keeps it intact;
2. defines the one named ordered session-units owner, including supersession;
3. resolves one semantic session into two target-specific profile passes
   instead of claiming one literal tee can do both;
4. repairs the `faces` contract and all worked values;
5. removes preview bands and extra tuning keys;
6. replaces the Hiccup-bearing relation query with a pure deterministic reduce;
7. places changed-basis walk cost and bounded history acquisition before
   surface expansion; and
8. adds a 20-agent, under-1-second changed-basis graduation gate.

With those amendments the recommendation can be presented. Without them, the
owner would be asked to rule options atop a production-breaking Phase 1 and an
unbounded performance shape.

## Author dispositions

The final PRD answers every finding as follows; evidence and normative details
remain in the PRD rather than being duplicated here.

| Finding | Disposition |
|---|---|
| RC-1 | One agent history, two independent target/profile passes; no shared physical tee requirement. |
| RC-2 | Removed agent-count and staleness-band keys; one preview profile plus ordinary elision spends values. |
| RC-3 | Named pure `seon.render.walk/history`, its request/result, and every consumer. |
| RC-4 | `seon.render/walk` keeps its string contract; the data function has a new name and request shape. |
| RC-5 | Preserved no per-namespace obligation, stored rank, bespoke feed, comment output, or capture-as-live authority. |
| IS-1 | Added `:seon.render/producer-request`; all 49 contracts/18 namespaces convert atomically before old flattening disappears. |
| IS-2 | One history entry owns form + optional value; total ordering, unsettled display, attempt exclusion, and superseded-run exclusion are specified. |
| IS-3 | `docs` returns ordered doc-or-error values; ambiguous identities refuse in position. |
| IS-4 | Preview selection is a pure reduce with stable tie-break and insertion-order regression. |
| IS-5 | Query output is a set, target values are labeled, and current `doc` print-plus-`nil` behavior remains. |
| IS-6 | Deep examples derive and expose a stable schema-identity + program-commit seed. |
| IS-7 | Zero producer work requires current retained read/program/profile evidence; counters distinguish query, selection, and invocation. |
| DE-1 | Fresh context retrieves its own namespace docs and shows its empty public-function vector. |
| DE-2 | Twenty agents produce 12 preview values plus an ordinary elision of 8; CSS changes no spend. |
| DE-3 | Debug uses exact agent profile/database value; main HTML fits independently; entry identities correspond, not bytes. |
| DE-4 | Bare `doc` prints familiar output and returns `nil`; bare `docs` returns data. |
| Quiet configuration | One whole preview profile, explicit debug/main profiles, bounded acquisition, stable example seed, and ruled root route; no hidden bands. |
| PF-1 | Changed-basis acquisition is Phase 1, baseline 3.168 s/3,859 pulls, target below one second. |
| PF-2 | Candidate identities are bounded before pull/fit; elision carries continuation. |
| PF-3 | The live probe rejects full scans and selects acquired candidates with a 20 ms/target gate. |
| PF-4 | Retained evidence gates wakes; unrelated transactions do zero history queries, selections, or invocations. |
| PF-5 | Twenty-agent changed-basis target is below one second with bounded pulls and no per-agent namespace walk. |
