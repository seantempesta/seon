---
type: research
status: complete
date: 2026-09-04
tags: [research, context, architecture]
---

# Second opinion: the agent-centric target

*2026-09-04. Independent review of the target, not an assessment of HEAD as
the desired end state. I read, end to end and in the requested order, the
[condensed design](../plan/agent-centric-design-2026-09-04.md), the
[behavior specification](../plan/repl-first-behavior-2026-09-03.md), the
[one-platform design](../plan/repl-first-one-platform-2026-09-03.md), the
[REPL-first probes](repl-first-probes-2026-09-02.md), the
[parallel-paths register](parallel-paths-register-2026-09-03.md), the
[data-model census](context-data-model-census-2026-09-04.md), and
`AGENTS.md` sections 1–3. I then read the cited first-party and vendored
dependency code at the bytes and reran the non-paid probes against the
isolated `opinion` cluster. The required Seon MCP tools were absent from this
lane; that tool defect is already recorded in
`docs/seon/issues/lane-toolset-omits-required-seon-mcp-tools.md`, so the live
forms below used the documented `io-prepl` attachment instead.*

## 1. What the owner is asking for

The owner is asking for a REPL that is generated from the agent's current
facts and program graph, teaches only the operations the generated forms are
about to use, preserves every result as addressable data, and uses one
contract-selected render mechanism for AI text and HTML. The condensed
document states that behavioral aim faithfully in its goals
(`agent-centric-design-2026-09-04.md:19-44`) and correctly labels itself a
target rather than HEAD (`:9-17`). It drifts when it equates an agent-rooted
*view* with physical component ownership of every inbox, eval, and arbitrary
data entity (`:46-75`), when it treats a generic recursive renderer as a
complete context generator (`:146-195`), and when it removes the run,
receipt, error, and capture authorities before restating their distinct
atomicity and recovery guarantees (`:120-127`). The behavioral request does
not require those three design choices. The condensed model also contradicts
the maintained one-owner namespace invariant by allowing several agents to
steward one namespace (`:66` versus `AGENTS.md:442`). My verdict is therefore:
the document is faithful about the experience the owner wants and unfaithful
about what that experience forces the database to own.

## 2. The data model: an agent is a good query root, not a universal component owner

“Everything hangs off the agent as component collections” is the wrong
physical shape for Datahike. A component attribute means lifecycle ownership,
not convenient nesting. Datahike recursively schedules component children for
`:db.fn/retractEntity` and `:db.fn/retractAttribute`
(`reference-code/datahike/src/datahike/db/transaction.cljc:830-839,997-1014,1072-1081`).
Its own test proves that those operations delete the children, while plain
`:db/retract` removes only the edge and leaves the child entity alive
(`reference-code/datahike/test/datahike/test/transact_test.cljc:152-182`).
Consequently the target's “handling POPS it (retract; history keeps it)”
(`agent-centric-design-2026-09-04.md:77-88`) has two incompatible readings:
plain retract leaves a current, unowned message entity; retracting the
component attribute or agent deletes the message rather than merely removing
it from the inbox. History exists in both cases, but current-state semantics
are different.

Datahike does not enforce the claimed one-owner invariant. This bounded
dependency probe admitted the same component child under two owners:

```clojure
(d/db-with base
  [{:owner/id :a :owner/items [{:child/id :x}]}
   {:owner/id :b :owner/items [[:child/id :x]]}])
```

Retracting `[:owner/id :a]` then produced this result:

```clojure
{:before-b #:owner{:id :b, :items [#:child{:id :x}]}
 :after-b  #:owner{:id :b}
 :child-after nil}
```

The cascade deleted the shared child and Datahike removed the other owner's
reverse reference. That is correct dependency behavior and makes “a fork that
copies evals copies its defs” (`agent-centric-design-2026-09-04.md:252-254`)
unsafe if “copies” means sharing component refs. Copying rows instead creates
new history and identity questions; following only `:forked-from` requires an
ancestry query and a conflict rule, contradicting the one-pull claim. Fork
inheritance must be settled before components encode it.

### Messages and cross-agent queries

One message living in exactly one inbox is a useful delivery invariant, but
the component direction makes the recipient implicit in an edge whose current
datom is deliberately retracted. Before pop, current cross-agent queries are
possible:

```clojure
[:find ?from ?to ?message-id
 :where
 [?to :seon.agent/inbox ?m]
 [?m :seon.agent.message/id ?message-id]
 [?m :seon.agent.message/from ?from]]
```

After pop, `?to` is available only by querying history and choosing the
assertion rather than its later retraction. A sender retains no direct durable
edge to its sent message; it can only reverse-query `:from`, and a system or
human message has no `:from` at all (`agent-centric-design-2026-09-04.md:79-86`).
The target therefore makes the common “who sent what to whom?” query temporal
after ordinary handling and makes an outside sender indistinguishable from
the system.

HEAD's addressed entity is better on this point. It records indexed `:to`,
`:from`, `:about`, and `:caused-by` refs on the message
(`resources/seon/schemas/seon.cluster.message.edn:1-5,30-32,57-76,87-91`).
The sender, recipient, causal chain, and subject remain directly queryable
without reconstructing an inbox-edge history. The target can still present
that message as an agent-rooted inbox *view*; it need not reverse the durable
edge to obtain the view.

Wake routing also is not the computed attribute substitution the condensed
document claims. HEAD's listener explicitly dispatches a hard-coded
`:seon.cluster.message/to` case and takes the recipient from the datom's value
slot (`src/seon/cluster/wake.clj:166-181,227-250`). An inbox assertion would
put the recipient in the entity slot, so both the case and slot change. The
render-interest set is computed (`:183-190,214-253`); mailbox routing is not.
The new route can be small, but it is a new routing rule whose deletion and
pop datoms must not spuriously wake or requeue work.

### Pull size, arbitrary data, and order

The proposed agent pull is not complete for a large record. Datahike applies a
default limit of 1,000 datoms per pulled attribute
(`reference-code/datahike/src/datahike/pull_api.cljc:16,304-324`). Against an
in-memory database with 10,000 committed component children, the exact nested
pull shape measured:

```clojure
{:committed-children 10000
 :pulled-children 1000
 :pull-ms 13.695708}
```

There was no omission marker. The target's positional pull
(`agent-centric-design-2026-09-04.md:129-131,258-261`) supplies no explicit
limit and places token-budget middleware *after* the pull (`:148-156`), so it
cannot recover or even report the missing 9,000 evals/events. The options-map
pull API can enforce work/result bounds
(`reference-code/datahike/src/datahike/pull_api.cljc:517-534`), but a bound is
not pagination, ordering, or an honest summary. A collection descriptor must
carry count, stable ordering, basis, page/requery identity, and a bounded
sample; it must be derived by query before rendering.

`:seon.agent/data` is also too unconstrained. It is a cardinality-many set of
refs to “any entity” (`agent-centric-design-2026-09-04.md:73-75,135-139`). It
asserts cascade ownership over shared calendar attendees, project facts, or
cross-agent plans; duplicates a semantic owner ref when a domain already has
one; and supplies no order from which “newest” can be derived. An agent-rooted
query can discover data through declared semantic refs without storing a
second generic ownership mirror. Components belong only on values with a
proved one-owner lifecycle.

### Namespace ownership and provenance

“Several may steward one namespace” is a breakage, not an incidental target
retype. HEAD declares the namespace ref unique
(`resources/seon/schemas/seon.cluster.agent.edn:72-75`), tests that a second
owner is rejected (`test/seon/cluster/agent_namespace_test.clj:53-71`), and
returns one scalar from `owner-of` (`src/seon/cluster/agent.clj:181-192`). The
program index identifies functions by namespace-qualified symbol. If two
agents share a namespace, their same-named defs collide and the “viewer's own
namespace” render rung cannot distinguish which steward authored a candidate.
Either retain one namespace per agent or introduce an explicit agent-scoped
definition identity and a deterministic co-steward conflict policy.

The proposed provenance claim is not supported by its citation. The target
says transactions carry `:seon.db/eval` and `:seon.db/process` and can drop the
user stamp (`agent-centric-design-2026-09-04.md:141-144,297`). The cited test
asserts `:seon.db/receipt` plus caller-supplied `:seon.db/user`
(`test/seon/receipt_write_carrier_test.clj:21-79`) and explicitly proves only
that a system write outside receipt custody has no receipt (`:80-90`). It does
not assert an eval or process stamp. `seon.db` currently adds the dynamic
receipt to transaction metadata (`src/seon/db.clj:1867-1875`). An eval is a
causal execution identity; it is not a substitute for the human or agent who
authorized the write. Keep `:seon.db/user` and `:seon.db/process`; add an eval
or receipt ref when one exists.

The consolidation that *is* better than HEAD is one settled eval row instead
of the current run-form/eval split. The census demonstrates the repeated
`(run, ordinal)` join and duplicated settlement shape
(`context-data-model-census-2026-09-04.md:70-78,181-217`). That consolidation
does not imply deleting the run that owns claim, trigger, opening basis,
terminal disposition, and crash evidence. Likewise, the agent record is a
better presentation root than today's scattered entry queries; it should be a
derived view over addressed messages, a minimal run, unified evals, program
facts, and semantically connected domain data rather than one cascade tree.

Finally, the sketched eval schema is not transactable through Seon's current
schema bridge: `:error :map` (`agent-centric-design-2026-09-04.md:217`) has no
native Datahike mapping. The bridge maps only concrete scalar heads and refs
and rejects other heads (`src/seon/schema/datahike.clj:50-61,112-174`). A flat
error must either be its own queryable attributes/entity or use an explicit
EDN-string codec with its indexing loss stated. The model also needs a
constraint saying when `:result`, `:result-blob`, and `:error` are mutually
exclusive; the current sketch permits all or none.

## 3. Context generation: recursive rendering is necessary and insufficient

`(help)` cannot yet be “the record rendered through the families' collection
render functions + processing functions by contract.” A per-entity renderer
accepting family `F` and a collection summary accepting `[:coll F]` are two
different contracts. The behavior spec leaves the absence case open
(`repl-first-behavior-2026-09-03.md:73-76`), while the condensed design assumes
every family has both (`agent-centric-design-2026-09-04.md:28-31,175-188`). A
family without a collection summary must produce an explicit generic summary
such as identity, count, bounded sample, stable order, and requery form. Blank
output or a floor dump would turn absence of a renderer into apparent health.

For 10,000 events, the answer cannot be “pull all, then let a renderer
summarize.” The exact target-shaped pull silently returned 1,000 above. Even
with its limit disabled, it would materialize every event before the token
budget can act. `(help)` needs database-side count/aggregate/page queries that
return bounded collection descriptors, and the agent layout face needs to
render those descriptors. That is still data-driven, but it is an assembler:
some function must choose the families, queries, order, and page bounds.

“Processing functions found by contract” is also underspecified. Merely
having `F` among an arity's input refs does not prove that the arity accepts a
collection, that every other argument is injectable, that it is public and
safe to suggest, or that it performs a meaningful domain operation. The
one-platform document itself sketches an unbuilt `inputs-satisfiable` rule
(`repl-first-one-platform-2026-09-03.md:343-380`). A family with no matching
processor should say “none declared” or omit a labeled section honestly; it
must not infer success from an empty query.

Teaching-before-use is not computable completely without the full-parse usage
bridge. The one-platform design admits that settled form-usage children are
the intended source and otherwise proposes walking the form
(`repl-first-one-platform-2026-09-03.md:314-318`). A reader walk cannot resolve
aliases and referred names, macro expansion, syntax quote, symbols emitted by
rendered values, or dynamic calls. Until usage children exist, the generator
can prove teaching for only a named subset of ordinary calls. The proof gate
must say so rather than claim P-TEACH-BEFORE-USE globally.

There is hand-authored text; it is simply inside render functions. The agent
face authors section order and labels, the eval face authors the prompt/result
grammar (`agent-centric-design-2026-09-04.md:175-195`), collection faces author
summaries, and `doc`/`dir` faces author teaching prose. The original behavior
also explicitly allows one authored introduction
(`repl-first-behavior-2026-09-03.md:28-31`). This is not a defect. The honest
claim is “all presentation bytes are owned by named, contracted render
functions, except the one declared introduction,” not “never authored” or
“nothing hardcoded.”

Two more boundaries are absent. Recursive rendering needs cycle detection for
`reply-to`, `forked-from`, arbitrary backrefs, and inline render values; the
prototype has no seen-set. Candidate render functions must be pure and
bounded, or the broad “contract fits” query can call an external sink while
building a prompt. Program-graph `:seon.fn/external-sink` and projection facts
exist specifically to make that exclusion queryable (`AGENTS.md:456`).

## 4. Evidence: what held, what did not

I initialized and started only the isolated operator root:

```text
bin/seon --root /Users/sean/src/seon/tmp/lane-second-opinion-2-root init
bin/seon --root /Users/sean/src/seon/tmp/lane-second-opinion-2-root start opinion
```

The published commit was `6a9b3db0-d379-5ec3-9e02-1d23b9af310e`; the
cluster exposed HTTP `7789` and prepl `63155`. I did not touch the shared
operator root or `ctxprobe`.

| condensed claim | verdict | rerun or byte evidence |
|---|---|---|
| Recursive renderer, 3 passes, 3.3 ms | **Narrow mechanism holds; evidence is overstated.** | I loaded `recursive-render-probe-2026-09-03.clj` with only its hard-coded cluster name changed in memory to `opinion`. Pass 1 was **22.805666 ms**, pass 3 **0.128667 ms**. Swapping the inbox face changed only that entry and swapping the entry face changed every entry. But the script says its `faces` atom substitutes for the unbuilt contract query and `floor` substitutes for `seon.print/fit` (`docs/prds/context-generation/research/scripts/recursive-render-probe-2026-09-03.clj:1-4,32-46`). It proves recursive composition, not production selection, bounded help, or the published 3.3 ms. |
| Every identity attribute names exactly one family, 0.1 ms | **Refuted.** | On `opinion`: 40 identity attrs, 38 mapped attrs, 41 identity→family pairs. `:seon.cluster.agent/id` mapped to three entity schemas and `:seon.test/sym` to two; `:seon.db.process/id` and `:seon.source/digest` mapped to none. The prototype's query is exactly `recursive-render-probe-2026-09-03.clj:24-28,36-42`, but it checks singleton cardinality only for the three hand-selected identities used by the demo. |
| Namespace distance is one query; 54 rows, 16 ms cold / 0.07 ms warm | **Shape holds; exact evidence is stale.** | The same explicit one-, two-, and three-hop rules returned 55 rows in **19.351458 ms** cold and **0.594667 ms** warm. The relation is queryable. The fixed population count and timings are not current invariants. |
| Schema facts derive Datahike schema and instrumentation; breaking changes refuse | **Mechanism largely holds; citation overclaims.** | `src/seon/schema/datahike.clj:221-262` derives declarations. `src/seon/cluster/run.clj:1436-1460` settles a candidate, but the actual breakage refusal is at `:1134-1165` and invoked at `:1422-1424`. `src/seon/sci/eval.clj:591-602` installs a wrapper from a supplied committed row; it is not by itself proof of the whole facts-to-wrapper chain. The target's `:map` error field is currently unmappable. |
| Watch primitive and per-query interest exist | **Holds at HEAD; target routing does not follow automatically.** | `src/seon/cluster/wake.clj:166-256` proves the listener and render-interest intersection. Its mailbox case is hard-coded to `:seon.cluster.message/to` and reads the value slot. |
| `diff` represents additions/deletions; `since` cannot | **Holds.** | `probe_core_diff_2026_08_13.clj` and `probe_datahike_since_2026_08_13.clj` reproduced the recorded distinction. The raw vector diff remained structurally poor; identity-keyed results were coherent. |
| Recursive and reverse pulls work | **Holds, with omitted semantics.** | Vendored tests prove component expansion and singular reverse-component pull (`reference-code/datahike/test/datahike/test/attribute_refs/pull_api_test.cljc:117-128`). They do not prove universal ownership, complete pulls over >1,000 children, or safe fork sharing. The 10,000-child probe refuted completeness. |
| Transactions carry eval and process stamps | **Refuted by the cited test.** | `receipt_write_carrier_test` proves receipt + user, not eval + process (`test/seon/receipt_write_carrier_test.clj:66-90`). |
| Query cost was the wrapper, 2.4 s → 0.048 ms | **Historical fix holds; it is not evidence for the target model.** | The owning issue records the current 0.048 ms wrapper measurement at `docs/seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md:68-83`. My three-hop query's warm call was 0.595 ms because it is a different query. The evidence supports handing/caching projections, not one giant agent pull. |

The remaining non-paid scripts under
`docs/prds/context-generation/research/scripts/` were rerun in the isolated
JVM. `probe_candidates`, `probe_core_diff`, `probe_costs`,
`probe_datahike_since`, `probe_editscript`, `probe_index_identity`,
`probe_node_sharing`, `probe_read_set`, `probe_zero_helper`, and the
root-path-adjusted `probe_commit_as_db` completed. Current notable measurements
were: query costs 0.293 ms single / 0.374 ms double / 7.440 ms double+diff /
11.626 ms recording / 2.444 ms history scan; EditScript A* 17.927 ms versus
quick 0.582 ms and core 4.391 ms; node sharing 98.7234% with a 6.455 ms scan;
and commit-as-db 234/235 or 156/157 shared addresses in the relevant probes.

Three archived probes no longer fully run at HEAD: `probe_id_attr` and
`probe_identity_coverage` fail because
`seon.schema.internal/derive-entity-id-attr` no longer exists; `probe_addresses`
gets through its sharing measurement and then fails because
`datahike.experimental.versioning` is no longer on the classpath. Those are
stale research scripts, not evidence against the target. I did not run
`context_ablation_2026_08_14.clj`: its own `-main` declares one named paid
variant with a supplied prompt path; running all V1–V4 variants would require
four paid calls (`docs/prds/context-generation/research/scripts/context_ablation_2026_08_14.clj:147-160`).
No paid call was needed to test the claims above.

The condensed document's register link is also broken: it points to
`parallel-paths-register-2026-09-04.md`
(`agent-centric-design-2026-09-04.md:361-364`), while the cited authority that
exists and was read is `parallel-paths-register-2026-09-03.md`.

The focused evidence gate was:

```text
bin/test seon.schema-usage-guard-test seon.receipt-write-carrier-test seon.cluster.agent-namespace-test seon.cluster.wake-test

Ran 28 tests containing 194 assertions.
0 failures, 0 errors.
```

## 5. Missing unknowns, missing errors, and the likely failure

The unknown list (`agent-centric-design-2026-09-04.md:300-339`) acknowledges
forks, run removal, wake routing, and scale, but omits the following blocking
questions:

1. **Atomic turn state.** What single transaction claims a message, assigns a
   turn, records process custody, and later records one terminal disposition?
   The target says an open turn is the last eval without a closing disposition
   (`:229-234`), but the eval schema has no disposition or closed-at attribute
   (`:205-223`). A crash before the first eval leaves no eval from which to
   derive the alleged open turn.
2. **Interrupted form evidence.** What identifies a form that performed an
   effect or database write and died before eval settlement? HEAD's run schema
   explicitly records interruption even when the dead process left no receipt
   row (`resources/seon/schemas/seon.cluster.run.edn:21-27`), and claim takeover
   stamps the run and running receipts atomically
   (`src/seon/cluster/run.clj:456-473`). Agent-level process custody names
   neither the trigger nor the interrupted form.
3. **Pop atomicity and wake behavior.** Is the trigger popped in the same
   transaction as terminal disposition? What prevents a still-open inbox edge
   from waking the agent repeatedly, and what makes a delete datom harmless?
4. **Bounded collection semantics.** What are the stable order, page token,
   basis, count, and explicit omission for >1,000 evals or domain rows? A set
   ref plus an after-the-pull token budget answers none of these.
5. **Component ownership enforcement.** Who prevents two agents or a fork from
   attaching the same component child when Datahike permits it? What operation
   intentionally deletes versus merely detaches a message?
6. **Family ambiguity.** How does selection handle zero, one, or several
   entity schemas referencing an identity attribute, or a map carrying several
   identity attributes? The live singleton premise is already false.
7. **Render purity, cycles, and versioning.** How are cycles cut? Which facts
   exclude external sinks? Does regenerating an old eval use today's hot-
   reloaded renderer, or the renderer/program basis that originally showed it?
   The former is not replay; the latter needs preserved identity.
8. **Queryable error shape.** Which error fields remain indexed after the
   error entity is replaced by an unmappable/free-form map? How are core faults
   that have no current agent represented without inventing a recipient?
9. **Definition and namespace conflicts.** What does “newest” mean across
   turn ties, fork ancestry, `ns-unmap`, atom mutation, and two agents in one
   namespace? The sample live-def query groups by name and max turn but neither
   returns the winning def nor considers ordinal (`:271-272`).
10. **Complete teaching evidence.** Which reader/compiler facts make demanded
    `doc`/`dir` complete for aliases, refers, and macro expansion before the
    full-parse bridge lands?

Section 6 lists mistakes made during design (`agent-centric-design-2026-09-04.md:341-359`),
not failure values the target must represent. Missing from the target's error
model are at least: ambiguous/no family, no collection renderer, tied render
candidates, renderer cycle, renderer effect refusal, pull-page elision,
trigger already claimed, turn already open, stale process takeover,
interrupted-before-first-eval, duplicate `[agent turn ordinal]`, invalid fork
inheritance, and result/error/blob exclusivity refusal. Each must be a flat,
queryable value at its admitting boundary.

The most likely reason this design fails in practice is removal of the run and
receipt authority. It collapses four independent facts—agent process custody,
message claim, turn identity/terminal disposition, and form/effect
interruption—into `:process` plus the presence of eval rows. That model reads
absence of an eval as “no open turn” in the exact crash window where no eval
could be written. HEAD uses one in-transaction read for run eligibility and an
atomic claim/takeover (`src/seon/cluster/run.clj:395-443,456-490`), then closes
custody, terminal time, and the agent pointer together (`:516-559`). The target
has not supplied an equivalent authority. Recursive rendering can be repaired
incrementally; a missing claim fact makes recovery guess.

## 6. Recommendation

Build one transaction-only crash falsifier first, not `(help)`: in a scratch
Datahike database using the target schema, implement only `begin-turn`,
`settle-eval`, and `close-turn`, then cut execution (a) after message arrival
but before the first eval, (b) after an effect begins but before eval
settlement, and (c) after the last eval but before pop. From facts alone, the
probe must identify exactly one trigger, owner process, turn, interrupted form
or no-form interruption, and terminal/open disposition, and a takeover must be
one transaction. If that cannot be done without recreating a run/receipt
entity, the largest destructive premise is falsified before any renderer is
built.

The owner must make these three decisions before production code:

1. **View versus ownership.** Recommended: the agent is the root of a derived
   query/view; retain addressed messages and semantic domain refs, and use
   components only for children with a proved one-owner cascade lifecycle.
   Also retain one namespace per agent until agent-scoped definition identity
   and co-steward conflict semantics are specified. This gives direct
   cross-agent queries and safe deletion; it gives up the slogan that the
   entire record is one physical tree.
2. **Turn authority.** Recommended: keep a minimal run plus receipt/eval
   custody authority while unifying run-form and eval settlement. Delete it
   only if the crash falsifier proves an equally atomic, explicit replacement.
   This preserves trigger claims, terminal disposition, and interruption
   evidence; it gives up “five families” as a target metric.
3. **Bounded context contract.** Recommended: define a collection descriptor
   with family, count, stable order, basis, bounded page, and requery identity;
   define the generic no-summary fallback; and limit P-TEACH-BEFORE-USE to
   forms with settled usage facts until the full-parse bridge exists. This
   makes 10,000-row help honest and computable; it gives up the literal “one
   pull renders everything” claim.

After those decisions, generate and read `(help)` for one real agent and one
real family. Before them, a pleasant help screen would validate presentation
while leaving the target's ownership and crash model unfalsified.
