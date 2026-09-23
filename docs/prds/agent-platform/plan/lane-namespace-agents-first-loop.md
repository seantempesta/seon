---
type: plan
status: five bounded slices; 2, 3 and 4 landed (owner-invoked prepare/accept installed), 1 landed with its loop regression red, 5 open
created: 2026-09-23
lane: ns-agents-design
---

# First namespace-agent fix through the gate

Deliver one bounded namespace worker fixing one missing contract, followed by an
explicit owner acceptance into an ordinary cluster's program. Compose the current
issue writer, agent graph, branch acquisition, SCI installation and test runner.
Do not wait for B3's task replacement, D1 export, or cut 4's turn rewrite.
This is the first increment of README §4 cut 3/3.x, not completion of README §2's
export/two-candidate acceptance. README §7's must-fix order still precedes execution.

**The gated merge is installed as an owner-invoked JVM request** (slice 4, `003a931d4`):
`seon.cluster.source/prepare-merge!` and `accept-merge!` over branches, interpreted
overrides, change-scoped three-way comparison, the guarded merge writer and the one test
request. No agent-facing accept, task settlement or export exists. “Compose installed seams” means extending these owners, not pretending
`my.task/merge!` or `seon.task/start-call` exists.

Source anchors below were checked against committed HEAD `57d2312e299172cdd0e85572b623e309bced6e36`;
subsequent HEAD `49b5f22be0fbafb214cee8b511939576c928e9d9` changes operator/recovery
and scheduling documentation, not the cited seams. In particular, `fn.clj` anchors
come from `git show HEAD:src/seon/fn.clj`, excluding its concurrent working changes.
Only this document is owned by this lane. No runtime writes, page GETs, tests,
provider calls, reloads or branch creation were performed.

## Current population and a real subject

`bin/seon status` and MCP `runtime_status` reached PID 65889, started
2026-09-23T03:16:52.974Z. Its source is committed archive
`9f39ae83de88d12724490094f04db0cbca943884`, with hook publication OFF. Thus live
facts are not proof that checkout HEAD is adopted. Status reports 3 error signatures
and 4 failed runs; no claim of a clean platform follows from REPL availability.

The historical **433 without reaching tests** and **3,145 uncontracted** are campaign
inputs, not current counts. B3 §2b describes the latter as private functions.
Read-only JVM probes on `default`, namespace `ns-agents-design.probe`, found:

| Population | Live count | Qualification |
|---|---:|---|
| Rows with `:seon.fn/sym` | 4,930 | Includes non-callable declarations |
| Those rows without `:seon.fn/spec` | 2,962 | Not all are contract defects |
| Private rows without spec | 2,810 | `:seon.fn/private? true`; still includes non-functions |
| `defn` / `defn-` declarations without spec | 2,554 | Positive `:seon.fn/defined-by` filter |
| `defn-` declarations without spec | 2,488 | Does not include `defn ^:private` |
| No reaching test | **Not remeasured** | Derivation below; do not repeat 433 as current |

First probe basis: `536871028`, commit `6ab34644-377b-553c-9a6a-d657728b89df`.
The census is a snapshot, not a cached work queue. These exact query shapes derive
its counts from one immutable value; a refused read is not a count:

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))]
  {:commit (seon.db/commit-id d)
   :functions (seon.db/q '[:find (count ?e) . :where [?e :seon.fn/sym _]] d)
   :without-spec (seon.db/q '[:find (count ?e) . :where
                            [?e :seon.fn/sym _] (not [?e :seon.fn/spec _])] d)
   :private-without-spec
   (seon.db/q '[:find (count ?e) . :where [?e :seon.fn/sym _]
               [?e :seon.fn/private? true] (not [?e :seon.fn/spec _])] d)
   :callable-without-spec
   (seon.db/q '[:find (count ?e) . :where [?e :seon.fn/defined-by ?by]
               [(contains? #{clojure.core/defn clojure.core/defn-} ?by)]
               (not [?e :seon.fn/spec _])] d)})
```

To derive the current counterpart of 433, capture the complete function-symbol
population from that same value, call `(seon.fn/gate-sets d symbols)` ONCE, require
a map entry for every supplied symbol, and count entries whose vector is empty.
Return the population, missing entries, basis and elapsed time with the count.
For an actionable public/source-only campaign use
`seon.issue.detect/public-without-reaching-test` with
`{:seon.fn.file/relative-root "src"}` (`src/seon/issue/detect.clj:289`), whose
population is explicitly narrower. Do not equate the two counts. Historical full
reach collection took 2.6 s (deep-review-wins-2026-09-21.md §4); this design lane
does not run that whole-program operation. Current positional `gate-sets` shares
one graph read but walks per seed (`src/seon/fn.clj:1554,1606`): cost
O(V+E+sum of reached closures), not O(V+E). Derive once for campaign admission;
never regenerate the census at every turn. Missing analysis is unknown coverage.

**Demonstration subject:** `my.program/subject-identities`,
`src/my/program.clj:40`. Live pull returned private=true, defined-by
`clojure.core/defn-`, host-bound=false, no spec, and digest
`b9ce981972913e347585eab7b1b098fcb7947c793172865041d402d1e9442339`.
It chooses schema/function/test/namespace identity attributes from the subject.
The defect is its missing contract, not invented faulty behavior. Add a complete
contract preserving its admitted callers and a regression that fails on the old
missing spec and exercises its identity choices and armed boundary. The agent
must inspect callers before selecting the contract. A private declaration is not
covered by the installed **public** missing-contract detector (`detect.clj:232`).
Use an authored issue for this one subject; do not build another detector framework.
If the defect has already disappeared at admission, select another positively
observed callable gap; never restore the defect to stage a demonstration.

## Installed seams and their limits

| Seam at HEAD | Supplied inputs and guarantee | Work / recomputation |
|---|---|---|
| `src/seon/cluster/agent.clj:134,183,431,450` (`steward-call`, `creation-tx`, `assigned-to`, `steward-of`) | Namespace assignment and stewardship are distinct; creation defaults branch from cluster unless supplied. Stewardship decided inside the writer | One agent/ns lookup and creation; once at start |
| `src/seon/render/web.clj:3132,3440`; `src/seon/render/route.clj:20` | `ensure-namespace-owner!` is reached by explicit POST action=create-owner since `d1396501f`; it creates an active, potentially paid agent | Never use browsing as startup. Existing POST lacks candidate/task admission, so it is not this loop's launcher |
| `src/seon/issue.clj:632,691,1038,1135,1231,1247,1283` | `generate!` upserts detector subjects; `add!` authors an issue; `start!` creates worker + plan + opening atomically and supports larger-budget resume | `generate!` scans detector population, even computes before and inside writer. First loop authors exactly one issue; start is subject/test-local |
| `src/seon/cluster/wake.clj:438`; `cluster/agent.clj:471,937` | `route!` consumes connection-local reports; mailbox → episode → turn. `arm!` merges acquired execution into the graph handle, installs route before prime | Datoms in report plus matched recipients; declaration changes recompile routing. No new scheduler or per-agent listener |
| `src/seon/db.clj:375` | `call-with-custody` binds exactly the supplied connection and clears inherited read basis | O(1) per evaluation, never fetch default implicitly |
| `src/seon/cluster/agent.clj:783,750`; `cluster/registry.clj:193,330`; `cluster/store.clj:538,571`; `sci/eval.clj:2716` | Acquisition composes branch!, open-branch!, fork-cluster-ctx and acquire!; release waits for ownership rules and unlinks owned branches. Acquisition alone starts no graph | Pointer/open + affected executable closure; unchanged contexts reuse existing state |
| `src/seon/sci/eval.clj:940,1203,3154` | Existing canonical installation/evaluation; contracts and source analysis remain at their current owners | Changed declarations and affected callers, including contract-only overrides |
| `src/seon/program.cljc:408,447,551` | Declared families, complete digest maps, pure three-way including absence. Comparator already landed (`b51a24055`, `e4cd4ee97`, `d8734f1e7`) | Current digest-map scans the program; do not call three full maps per tiny merge |
| `src/seon/fn.clj:3130,3294`; `program.cljc:1281` | `published-index-rows` has identity-scoped arity and portable refs/components; exact replacement is already owned | Changed complete rows/components, not raw datom/eid replay |
| `src/seon/test.clj:885,1584`; `src/my/test.clj`; `bin/test-check:1` | Reaching selection and one run request (`8bc917872`). Execution and recording connection must name the same branch. Tests get their own branch and SCI context | Graph construction + selected closures + members not validly reusable; exclusions/unfinished are not green |
| `src/seon/profile.clj:242` | `explain-slow` (`27c08129d`) tells the agent an operation over one second is its defect and ranks inclusive armed costs | One clock read on fast path; report captured observations on slow path |

Dependency pins at that HEAD: Datahike
`684d329037af665815d32a86fdbf1821618157eb`, SCI
`fcbd8862800e638dc0f8f5521111f999279cbcd2`.
Datahike `versioning.cljc:212` supplies branch pointers; SCI `core.cljc:345`
supplies generation-based fork, not protection against compiled caller bypass.
Datahike `versioning.cljc:738` supplies merge lineage but caller-supplied content;
`writing.cljc:865` applies merge through `core/with`, while `:877`'s ordinary
transaction already checks `:datahike/expected-basis-t`. Therefore current merge
is **not** guarded acceptance. Seon's preparation/codec/final report validator
are at `src/seon/db.clj:4326,4346`; preserve them on the merge path.
No `force-branch!` replacement of an open live connection.

## Smallest loop and costs

Use an **ordinary, non-development cluster** already hosted in this JVM as shared
H (demonstration name `ns-loop`). Default remains the files; without export do not
promise a durable default override. One shared cluster may host many later workers;
never start a new cluster per candidate. Provisioning that shared cluster is an
explicit operator prerequisite, outside the timed fix; it must be measured and
separately authorized if over ten seconds. This design lane does not provision it.

| Step | Concrete action | Cost target / proportionality |
|---|---|---|
| 1. Admit | Confirm one callable gap and named existing test; retain immutable B=H commit. Fork candidate with installed acquisition using isolate? and no agent id | Gap lookup 5–50 ms estimated; branch/open historical 74.65/25.62 ms; raw fork 0.021 ms. Full acquisition NOT measured here; target <1 s, closure-dependent |
| 2. Start | Author one issue ON C with `issue/add!`, citing subject and an existing meaningful test. `issue/start!` gets C's branch, creates one namespace worker/opening there. Explicitly arm that worker with C's handle and existing routing machinery | 100–500 ms estimated, one issue/agent/plan/test set. No bulk detector and no inherited-agent arm-all |
| 3. Work | Worker reads source/callers, installs contract and regression through ordinary SCI; runs `my.test/check` for the changed identity | Installation 50–500 ms estimate plus affected closure. Provider latency is unmeasured external work; paid turn/budget require explicit run authorization. Local gates remain measured separately |
| 4. Combine | Capture immutable C and current H. Compare only candidate-changed definition identities at B/C/H using existing three-way. Refuse conflict by identity; otherwise fork S from H and apply portable program rows, never issue/turn/run data | O(changed history examined + changed identities/components); 100–500 ms estimate plus acquisition. Existing whole-program digest-map is not the happy path |
| 5. Gate | On S require contracts and a nonempty current reaching set for every changed function; union task tests, changed tests, before/after reach. Invoke one `seon.test/run` on S and record ON S | O(V+E) current graph derivation plus reached closure + selected bodies. Target <1 s for this leaf; historical fixture 37 ms, ordinary body bound 5 s is a ceiling, not a target |
| 6. Accept | Owner/root names B/C/H/S and the exact run. Writer verifies H's basis, program/evidence basis and complete green, then applies tested program delta with immutable merge parents | O(delta + final affected validation), 50–250 ms estimate; stale basis refuses before mutation, no automatic retry |
| 7. Observe | Next shared SCI acquisition sees the contract; ordinary indirect call observes it. Read accepted digest/run/parents, stop only the worker, release owned handles after exit | Query 5–50 ms estimate; stop/release target <1 s, actual termination required |

Historical primitive timings are from README §7 Candidate shape; they are not a
measurement of this composed loop. Every estimate must be replaced by a timed REPL
proof before claiming the slice complete. Above one second, carry the installed
profile directive, name armed spend and fix redundant work before proceeding;
above ten seconds requires authorization and an issue extension. No higher bound
or “cold” label is the fix. Source generation latency cannot justify a slow writer.

Candidate-only issue storage deliberately avoids copying messages or a shared-task
lifecycle: retain C and its issue/turns as the repair record. Shared acceptance is
proved by program and run evidence, not `issue/done?` (`issue.clj:994`), which gives
tests precedence over detectors and is not a merge gate. Namespace ownership on C
comes from ordinary creation; inherited namespace stewardship is not stolen.
Register the existing route against C's connection with a candidate-local routing
value, arm only the new worker, and unregister it on release. Do not reuse H's
entity-id routing map: fork-local numeric ids can collide. This is one connection's
existing wake route, not a second listener per agent. Faults must retain C custody
through the ordinary error owner; the must-now error route and the startup proof
below decide readiness, not a copied fault committer.

## Missing pieces, priced as five landable slices

Estimates are **added physical lines**, including tests/schema, not net lines or
permission to spread a large mechanism over commits. Reuse named owners; if a row
needs over roughly 100 added lines, stop and choose the stated smaller restriction.
No new task, runner, cache, registry, launcher or merge service namespace.

| Slice | Missing behavior and exact proposed ownership | Added lines | REPL-confirmable proof / smaller alternative |
|---|---|---:|---|
| 1. Start one worker on its branch | `src/seon/issue.clj`: thread optional existing `:seon.agent/branch` through start!/start-tx/create-tx to creation-tx. `test/seon/namespace_agent_loop_test.clj`: use installed acquisition/route/arm, branch-local issue and one virtual ordinary reply | 15–25 source + 50–60 test = **65–85** | One worker only; definition, evaluation, effect and fault facts stay on C; H digest unchanged; no paid call. Repeated start resumes/refuses through existing issue rules. If graph scope requires new machinery, give the held agent owner the exact failing seam; do not create another lifecycle |
| 2. Change-scoped comparison | `src/seon/program.cljc` + its schema resource: extend digest-map input to identities derived from digest assertions/retractions since retained B; resolve deleted identities through history. `test/seon/program_test.clj` | 35–50 source/schema + 25–35 test = **60–85** | Existing three-way sees replacement/addition/deletion/equal revert/conflict; missing digest/family/history refuses. Target cost follows changed history, not P. Restrict first loop to function/test replacements with unchanged ns/schema bindings; refuse other changed program families explicitly. Keep generalized export later |
| 3. Guard existing merge writer | Maintained Datahike `api.cljc`, `versioning.cljc`, `writing.cljc` and its regression; `src/seon/db.clj` + transaction schema: expose expected basis and immutable parents through the existing prepared write path | 30–45 source/schema + 35–45 test = **65–90** | Stale H leaves all datoms unchanged; final validator refusal leaves parents unchanged; good merge has immutable H/C/S lineage and updates the held connection. Reuse ordinary transact guard inside merge-writer!, not a copied validator. Do not trade away lineage silently |
| 4. Compose preparation, gate and named accept — **landed `003a931d4`** | `src/seon/cluster/source.clj` (+139), `src/seon/program.cljc` (+5/−4), `test/seon/cluster/merge_test.clj` (+117); no schema, no new key, no reason enum, no second run at accept. `prepare-merge! [source held-store candidate issue]` → `{:seon.source/candidate S :seon.test.run/id :seon.test/passed? :seon.source/tally :datahike/expected-basis-t :parents #{C}}`, a nonempty `:seon.program/three-way`, or the refusing owner's flat error; `accept-merge! [source held-store proposal]` → the transaction report or `:seon.source/test-evidence-error` / `:transaction/stale-basis` / `digest-map-refusal` | 144 source + 117 test = **261**, over the 85–100 budget | Landed algorithm and its over-budget follow-ups are below the table |
| 5. First real fix demonstration | `test/seon/namespace_agent_loop_test.clj` and committed `script/seon/namespace_agent_loop.clj`; agent authors its own database definitions | 35–50 test + 25–35 script = **60–85** | Actual missing contract → ordinary worker reply → regression red-before/green-after → combined gate → named accepted digest → shared indirect call. Script is repeatable composition of prior owners, not runtime machinery |

Three-way comparison itself costs **0 new lines**. Portable row normalization,
branch allocation, context fork, test execution and profile directives likewise
need no replacement. Slice 2 must distinguish a legitimately absent declaration
from missing analysis, bind digest attributes before history joins, verify B's
ancestry and preserve the existing datom bound. It must reject ns/schema changes
rather than quietly ignore them. Test selection's broader input invalidation
(`src/seon/test.clj:430`) is evidence to reuse where semantics match, not code to
copy: it also handles file/input obligations that are not merge content.

Slice 4 must not accept an arbitrary caller-supplied green flag. Prepare retains
S, its captured execution program and the actual recorded run/member identities.
Accept rereads positive completed/terminated evidence for every required member,
matching definition/input digests and arming, checks unchanged tested program,
and verifies the named accepter through the existing root/owner request boundary.
H precondition is checked IN the serialized writer. C changing after its capture
cannot change the proposal. Test recording may advance S's data head; compare
its program identity to the executed basis, not a naive equal transaction number.
Run facts remain on S: the runner currently refuses a different recording branch.
Keep C and S roster branches through owner inspection; release connections only
after body/graph exit. Automatic unlink waits for D1's retained-run-after-unlink
proof under the real retention policy. This bounded two-branch retention uses the
existing roster, not a new durable acceptance entity or copied run facts.

**Slice 4 as landed** ([landing](../landing/lane-nsa-slice4-2026-09-23.md)). Merge base B:
alternate parent walks from C and H over `:datahike/parents` meta (O(commits since fork),
2.3 ms per commit, bound 10,000). Compare: `changed-identities B C` — the candidate's own
changes only, because H's basis-t numbers belong to another lineage — then `digest-map` scoped
to them and `three-way`. Delta: `published-index-rows C D` + `reconcile-tx` against the head.
Gate: the existing `seon.cluster/candidate-gate!` unchanged, with an advance of `(constantly #{})`,
one `seon.test/run :named` over D ∪ the issue's tests on a fresh branch S. Accept rereads the
named run on S (tested branch, complete, every member green, every replaced function reached
by `gate-sets`) and refuses a program write since the run through
`seon.test.runner/program-written-since?` (`as-of` keeps the head's basis-t, so a
`changed-identities` comparison from `as-of` would answer `#{}` falsely); `changed-identities`
now also accepts a basis-t on the compared value's own lineage, and accept compares from the
proposal's `:datahike/expected-basis-t`. The test owner does not refuse a changed function that
reaches zero tests (`seon.test/select` of `my.agent/branch` → 0 members, no refusal), so accept
refuses each uncovered function by name. One real merge: prepare 3,837 ms (3,003 ms the nested
run), accept 1,187 ms (≈714 ms the first `gate-sets` reach index on E, whole program; 21 ms
warm); merged parents #{H C E}. The named merge-test request took 35 s (five nested runs, each
a new candidate commit — over ten seconds, the save-gate cost class in
`cache-invalidation-audit-2026-09-23.md` item 6). **Over-budget follow-ups:** `merge-base`
(22 lines) is deleted when the Datahike fork exposes a common-ancestor function beside
`branch-history` (`versioning.cljc:191`, which materializes every commit on one side);
accept's evidence block (≈14 lines) moves to `seon.test` as a "run R positively covers
identities D" predicate, where the zero-reach refusal also belongs. **Not yet as specified
above:** accept checks no arming or input-digest evidence and no accepter identity (owner JVM
request only; a root accept needs D1's request boundary); "unfinished" and "other-run"
evidence are not exercised by a regression.

Slices 2 and 3 can proceed independently of slice 1; slice 4 needs 2+3; slice 5
needs all. Slice 1's `issue.clj` is held: queue that change to its current holder
or wait for release. Do not touch `reference-code/core.async`, `src/seon/flow.clj`,
`src/seon/await.clj`, `src/seon/cluster/agent.clj`, `src/seon/cluster.clj`,
`src/seon/cluster/boot.clj`, `.clj-kondo/config.edn`, `script/seon/operator.clj`.
Check the orchestrator's exact file ledger before any implementation assignment;
these suggested paths are not a reservation. `fn.clj` has foreign edits too:
use its existing public row reader without editing it.

## Owner-visible demonstration

The implementation script must print the actual cluster, namespace, issue, agent,
B/C/H/S commits, changed identities, selected tests, executed/reused/excluded counts,
run identity, acceptance result and timings. It takes a shared execution handle
and never boots a JVM. Start in no-provider mode with a virtual reply through the
ordinary source submission/turn path (`cluster/agent.clj:607`) for infrastructure
proof; a deliberately authorized bounded provider turn then supplies the real fix.
Do not call MCP SCI as a substitute for the worker turn.

Use namespace `my.program` and subject above. Initially name the existing test
`my.program-test/program-reads-name-the-referrers-and-propose-without-writing`
(`test/my/program_test.clj:14`); require its installed row before issue start.
The worker strengthens/adds a contract regression and adds that test to the issue
through `issue/tests!`. Its test must positively see the function, detect missing
spec on B, pass on C, and reach the changed function through the ordinary callable
path. Failure to produce a meaningful reaching regression blocks acceptance.

These observation commands/forms are installed today; run MCP in JVM mode,
explicit root `/Users/sean/src/seon`, cluster `ns-loop`, read_only=true,
namespace `ns-agents-design.watch`. The operator must supply that ordinary cluster
before the demonstration. Replace neither its custody nor its handle with default.

```sh
bin/seon status
# Focused implementation proof on the selected cluster, after adoption:
bin/test-check --root /Users/sean/src/seon ns-loop --ns seon.namespace-agent-loop-test
```

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "ns-loop"))]
  {:commit (seon.db/commit-id d)
   :subject (seon.db/pull d
              [:seon.fn/sym :seon.fn/spec :seon.program/definition-digest]
              [:seon.fn/sym 'my.program/subject-identities])
   :agents (seon.cluster.agent/assigned-to d 'my.program)})
```

Candidate observation is also read-only; select the actual branch printed by the
script, rather than calling acquire! (which can record acquisition faults):

```clojure
(let [h (:seon.turn.loop/cluster
          (get @seon.operator.runtime/running-instances "ns-loop"))]
  (mapv (fn [[[branch agent] execution]]
          {:branch branch :agent agent
           :subject (seon.db/pull (seon.db/db (:seon.db/connection execution))
                      [:seon.fn/sym :seon.fn/spec :seon.program/definition-digest]
                      [:seon.fn/sym 'my.program/subject-identities])})
        @(:seon.agent/context-state h)))
```

For the combined run the future composition uses this **installed** request shape,
with `s` the acquired intermediate handle and `required-tests` the verified union:

```clojure
(seon.test/run {:seon.test/execution s
               :seon.test/recording-connection (:seon.db/connection s)
               :seon.test/policy :named
               :seon.test/identities required-tests
               :seon.test/check-time-limit-ms 5000})
```

This is a WRITE and belongs to implementation/demo, not this read-only design lane.
The final script calls the installed `seon.cluster.source/prepare-merge!` and, after the
owner reads the printed proposal, `accept-merge!` with that proposal.
The owner approves the printed concrete H/C/S/run proposal. A red rehearsal, a
moved H rehearsal and the accepted contract's indirect-call proof must be visible.
No namespace GET or create-owner POST is necessary to watch these facts.

## Deliberately later

- **File write-back:** accepted rows persist on the ordinary cluster's branch and
  are interpreted there. Files/default and Git are unchanged. This demonstrates a
  database program fix, not export/reindex survival; D1 §2d remains open.
- **Conflicts as root tasks:** report the existing three-way conflict identities
  and retain C/S. Owner repairs through the same path; no auto-resolution or
  `seon.task` adapter. First success uses one non-conflicting subject.
- **Full task family and campaigns:** one authored issue already supplies objective,
  budget, tests, worker and opening. No bulk note promotion, periodic census,
  scheduled trigger rollout, parent-task graph or new settlement lifecycle.
- **General merge scope:** namespace/schema changes, deletions needing repair and
  host-bound changes refuse in this first loop. No omission masquerades as success.
  Later D1 widens admission with its own proofs.

## TIMINGS and proof boundary

| Operation actually performed | Duration | Evidence / limitation |
|---|---:|---|
| `bin/seon status` with Git status/branch/HEAD reads | 269 ms shell wall | Shared JVM reachable; archive source and publication-off reported |
| Census JVM form | 42 ms MCP ret; 34.039 ms inner | 4,930 rows / 2,962 missing specs, task schema absent |
| `seon.id` gap query | 17 ms MCP ret | Only default-length; rejected as demonstration because it is a constant |
| Callable gap sample | 46 ms MCP ret | Distinguishes defn/defn- from constants |
| Real subject source/digest pull | 6 ms MCP ret | Existing private, non-host-bound declaration, no spec |
| Callable/private-defn census | 39 ms MCP ret | 2,554 / 2,488 |
| Private-flag census and subject pull | 17 ms MCP ret | 2,810; subject private=true |
| MCP runtime status | <1.6 s enclosing concurrent batch; individual not measured | Status response complete; not a timed health benchmark |
| Historical full reach census | 2.6 s, not run here | Whole graph/per-seed closures; cannot claim sub-second or current 433 |
| Historical branch/open/raw fork | 74.65 / 25.62 / 0.021 ms | Primitive measurements, not composed acquisition |
| Existing process readiness/profile, reported by status | 12,252 ms ready; turn-step max 30,357 ms | Foreign runtime evidence, not caused or fixed here. Existing must-fix order owns it; requires repair before the paid demonstration |

No live test run was appropriate for a one-document design. No new Clojure was
loaded, no snapshot/JVM was required, and no unavailable evidence is called green.
Slow foreign runtime observations remain the readiness boundary; this plan does
not restart another lane's process or widen its own read-only authorization.
Implementation landings must record actual net source/test lines and memory as
well as the per-operation clock; estimates above are not performance evidence.
