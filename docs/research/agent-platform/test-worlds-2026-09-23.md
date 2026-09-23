---
type: research
status: evidence for B4 §2f; independent review and implementation proof pending
created: 2026-09-23
scope: read-only runtime investigation and source census; documentation only
---

# Test worlds: one system, supplied values

The system already owns branches, contexts, writers and contracts. The smallest
composition prepares writer-produced data once and invokes ordinary armed functions
with the resources their schemas request. The design lives in
[B4 §2f](../../prds/agent-platform/plan/lane-b4-tests-in-process.md#2f-tests-are-the-real-system-with-their-own-data),
not in this evidence report.

## Authority and inspected state

Read root `AGENTS.md`, plan `AGENTS.md`, README (including §7), B4 in full,
[one lifecycle](../../prds/agent-platform/plan/lane-realities-one-lifecycle.md), D1,
[red root causes](red-root-causes-2026-09-23.md), canonical test support and the `my.*`
surface. Applied data-oriented Clojure, REPL, testing, Datahike and data-modeling skills.
Read the subsequent [simplicity research](test-simplicity-research-2026-09-23.md)
(`27d9e1db2`) and owner rulings `e5f649f94` and `1af25b4e8` on resumption.
Its author interface is adopted; its call-preparation injection proposal is superseded
by the owner's explicit A1 wrapper instruction and fork-audit S12(a).

Source census began around checkout HEAD `509b907b3` on `refactor/agent-platform`;
concurrent commits advanced HEAD through `680f19376` and `154a949fe` during reading.
The lexical inventory below is the captured working-tree observation, not an assertion
that all bytes equal a single Git commit. Foreign source/test/dependency edits were
present and never edited, adopted, tested or reverted by this assignment.
B4 had foreign D1 pointer corrections; they are preserved. No README edit was needed.

History evidence: `678009fcd0` replaced fixture/base machinery with the acquired-member
request (31 files, +1,292/−3,099); `d7b3930ff` converted agent fixture construction
across 62 files (+566/−536), but retained singleton-cluster inference; `ba4e30f0f`
reduced repeated program/reach work; `f3a4b33d9` introduced content-keyed member evidence.
The second conversion explains why using a helper alone did not eliminate manufactured
worlds: callers still choose scope and may submit other literal rows.

`bin/seon status` and MCP `runtime_status` were available. The later CLI status took
**42.1 ms**. Default pid **48902**, started `2026-09-23T18:07:01Z`, runs archive
`ce73846828a5cc32798ef630b5a574f777646f30`; hook publication is **off**. MCP reports
alive/readiness without missing layers, but **16 error signatures, 34 errored receipts,
one failed run and unknown failed-test evidence**. Alive is not a green system.
Historical profile maxima include `seon.test/run` **97,608 ms**, `member-result`
**14,790 ms**, `bounded-result` **14,690 ms**, `runner/run-var!` **11,858 ms** and
`with-database` **11,820 ms**. **Directive to their owners: measure and fix the
non-sub-second work; these inclusive observations do not justify it or locate exclusive
cost.** Existing issue/evidence authority is the
[test-overhead landing](../../prds/agent-platform/landing/lane-test-overhead-2026-09-23.md)
and the root-cause report's performance section; no duplicate issue class was created.
No such slow operation was executed by this design lane.

## Installed seams and gaps

Anchors refer to inspected checkout bodies, not proof of archive adoption.

| Owner | Evidence | Consequence for design |
|---|---|---|
| `test_support.clj:215` `transacted!` | Requires successful `:db-after`, keeps failed transaction and offending row | Checks validity, not canonical construction/provenance. Keep refusal checking. |
| `test_support.clj:262,627,643` | Member handle, branch fixture, `with-database` | Runner child plus callback fixture child is duplicated acquisition to remove. |
| `test_support.clj:277,697` | `seeded-cluster-name`, `agent-tx` require exactly one cluster | Explicit acquisition scope must replace inferred singleton scope. |
| `cluster/agent.clj:186` | `creation-tx` takes explicit cluster and optional branch; otherwise derives live cluster branch | Loader must pass its actual member branch, not merely cluster name. |
| `test_support.clj:686,723,741,754,773` | Namespace/program helpers analyze or read indexed rows; config and cluster helpers call real writers | Reuse the production owners; delete fixture-only row-producing interfaces after conversion. |
| `cluster/agent.clj:607,746,779` | Submission, release, acquisition | One lifecycle for agent and test, no test-world startup implementation. |
| `my/message.clj:31`, `my/issue.clj:13`, `my/test.clj:5,28` | Thin owners for send, issue creation and real test requests | Use actual returned identities; no invented `my.agent/create` API. |
| `my/agent.clj:60` | Branch reader still queries one cluster name from database | Multi-cluster scope proof must cover consumers as well as fixture creation. |
| `instrument.clj:447`, `sci/eval.clj:699` | `wrap-interpreted` and contract installation compile per installed callable | A1 supply logic belongs inside this existing wrapper; not a new B4 wrapper. |
| `sci/eval.clj:245` | SCI call-preparation hook still present in inspected source | S12(a) is a target retirement; do not describe wrapper injection as installed. |
| `turn.clj:4525`, `effect.clj:740` | Direct `ai/complete`; `requiring-resolve` handler | Completion/capability environment inputs must land at these real effect seams first. |
| `sci/eval.clj:2314,2475,2486` | Bounded core.cache policy, `copy-base-ctx`, `base-ctx` | Reuse this owner; current copying behavior does not prove the new in-place advancement ruling. |
| `test.clj:1571` `isolated-members` | Tagged ∪ fixture-observation ∪ destructive reach, excluding fixture material | Exact platform selection is a query, not a test namespace hand roster. |
| `turn_work_test.clj:131` | `d/with` receives `write-report-validator` via transaction metadata | Precedent for ordinary validated speculative seam; the example's own literal message is not a recommended world constructor. |

`my.note`, `my.turn`, `my.plan`, `my.background`, `my.fs`, `my.edit`, `my.shell`,
`my.web` and `my.program` were also inspected for delegated owners and declared
capabilities. Their existence is not permission to reproduce their storage entities.
The testing/REPL skill contains older line anchors and describes the host fixture lookup
as SCI-arm based; inspected `execution-handle` also uses `seon.test/*member*`. Record
that documentation drift here; skill files are outside this assignment's edit scope.
The source no longer supports treating the old B4 worker narrative as installed behavior.

## Dependency source, pins and costs

Pins from `git ls-tree HEAD reference-code/{datahike,sci,malli,clojure}`:

| Dependency pin | Read seam / guarantee | Supplied inputs, recomputation, proportionality |
|---|---|---|
| Datahike `c79cd03a44427ac1734d917c7484c3e529c77716` | `versioning.cljc:212` branch, `api/impl.cljc:134` with, `db/transaction.cljc:1224` final validator | Captured commit + branch/store; head/roster/secondary-index work, no datom copy. `with` takes db + tx + validator; delta/index/validation work per trial. |
| SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2` | `core.cljc:345` fork | Held context → independent env atom and generation. One new context, no deep copy of objects. Fork only for independence, not program advancement. |
| Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d` | `core.cljc:2555` schema, `:2515` type, `:2579` form, `:2600` children | Compile at changed schema/registry content; inspect held signature proportional to arguments/references, not entire program per call. Dirty dependency checkout is a verification limit. |
| Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` | `test.clj:710,725` test-var/test-vars | Callable `:test`, reporting and namespace fixture composition; body cost per execution. Typed `defn` invocation must be adapted in Seon's existing owner. |

Upstream/fork distinction is verified in the primary-source comparison already captured
in [simplicity research §9](test-simplicity-research-2026-09-23.md#9-dependency-seams-measurements-and-limits):
branches and SCI forks exist upstream, while this generation-based SCI isolation,
call-preparation hook and Datahike final-report validation wiring are maintained-fork
extensions. No generic upstream guarantee is inferred from those patches. No new
library or replacement implementation is proposed here.

## Census: mutation sites and literal candidates

Requested command `rg -c 'with-redefs|alter-var-root' test/` returns **363 matching
lines in 101 files**. It counts lines including comments/docstrings and matches
`with-redefs-fn` by substring. A separate lexical pass over **338** Clojure/EDN files,
skipping strings/comments/character literals, found **263 `with-redefs`, 65
`with-redefs-fn`, 21 `alter-var-root` tokens in 99 files**. These **349 tokens** include
quoted/referred forms; they are not a count of proven executed root mutations.

The same pass records map literals with direct identity-key tokens from eleven named
families: **1,599 distinct candidate maps in 181 files**, **247.120 ms**. Family totals
overlap when one map names multiple identities. This is a reproducible lexical review
queue, not a sound dataflow detector or a claim that 1,599 maps must be replaced.
It excludes identity keys added by `assoc`, shorthand namespaced maps and dynamically
constructed maps; it can include keys used as values, quoted negative cases, request
maps, expected values and generated data. The implementation census must use canonical
analysis to classify actual constructor/write paths. Do not use this script as admission.

| Identity-key candidate | Maps | Files |
|---|---:|---:|
| `:seon.agent/id` | 647 | 133 |
| `:seon.cluster/name` | 195 | 72 |
| `:seon.config/cluster` | 7 | 3 |
| `:seon.error/id` | 22 | 13 |
| `:seon.fn/sym` | 118 | 36 |
| `:seon.issue/id` | 30 | 11 |
| `:seon.message/id` | 144 | 46 |
| `:seon.ns/name` | 351 | 110 |
| `:seon.schema/key` | 66 | 27 |
| `:seon.test/sym` | 59 | 17 |
| `:seon.turn/id` | 347 | 74 |

Helper-token census (definitions and references included):

| Helper | Tokens |
|---|---:|
| `with-database` | 958 |
| `transacted!` | 955 |
| `agents-tx` | 32 |
| `agent-tx` | 107 |
| `seed-cluster!` | 172 |
| `fork-cluster-ctx` | 232 |
| `program-fn-row` | 73 |
| `apply-config!` | 17 |
| `program-row` | 9 |
| `namespace-row` | 57 |

Mutation-related token inventory by file (zero files omitted):

| File under test/ | with-redefs | with-redefs-fn | alter-var-root |
|---|---:|---:|---:|
| `my/plan_test.clj` | 1 | 0 | 0 |
| `seon/ai_test.clj` | 6 | 5 | 0 |
| `seon/blob_publication_test.clj` | 2 | 0 | 0 |
| `seon/blob_test.clj` | 0 | 1 | 0 |
| `seon/bootstrap_test.clj` | 1 | 0 | 0 |
| `seon/cluster/acquisition_test.clj` | 2 | 0 | 0 |
| `seon/cluster/agent_test.clj` | 13 | 1 | 2 |
| `seon/cluster/armed_test.clj` | 5 | 0 | 0 |
| `seon/cluster/boot_drill_child.clj` | 0 | 1 | 0 |
| `seon/cluster/boot_test.clj` | 2 | 0 | 0 |
| `seon/cluster/bootstrap_resume_child.clj` | 1 | 0 | 0 |
| `seon/cluster/evaluate_sources_test.clj` | 3 | 0 | 0 |
| `seon/cluster/export_test.clj` | 0 | 2 | 0 |
| `seon/cluster/mcp_test.clj` | 4 | 2 | 1 |
| `seon/cluster/program_restart_test.clj` | 2 | 0 | 0 |
| `seon/cluster/prompt_test.clj` | 3 | 0 | 0 |
| `seon/cluster/publication_adoption_test.clj` | 0 | 1 | 0 |
| `seon/cluster/publication_host_test.clj` | 2 | 0 | 0 |
| `seon/cluster/publication_inputs_test.clj` | 3 | 0 | 0 |
| `seon/cluster/registry_test.clj` | 1 | 0 | 0 |
| `seon/cluster/release_context_test.clj` | 2 | 0 | 0 |
| `seon/cluster/reload_measure.clj` | 0 | 2 | 0 |
| `seon/cluster/source_nochange_test.clj` | 1 | 0 | 0 |
| `seon/cluster/source_test.clj` | 1 | 0 | 0 |
| `seon/cluster/store_test.clj` | 1 | 0 | 0 |
| `seon/cluster/turn_test.clj` | 67 | 3 | 0 |
| `seon/cluster_test.clj` | 1 | 0 | 0 |
| `seon/concurrency_independence_test.clj` | 1 | 0 | 0 |
| `seon/config_application_test.clj` | 1 | 0 | 0 |
| `seon/config_test.clj` | 5 | 1 | 0 |
| `seon/contracts_plan_test.clj` | 1 | 0 | 0 |
| `seon/core_functions_test.clj` | 1 | 0 | 0 |
| `seon/db/declaration_population_test.clj` | 1 | 0 | 0 |
| `seon/db_test.clj` | 8 | 0 | 0 |
| `seon/dev/changed_test_test.clj` | 1 | 0 | 0 |
| `seon/dev/dependency_cache_test.clj` | 0 | 1 | 0 |
| `seon/dev/hook_measure.clj` | 0 | 1 | 0 |
| `seon/dev/hook_test.clj` | 1 | 0 | 0 |
| `seon/dev/mcp_bridge_test.clj` | 1 | 8 | 0 |
| `seon/dev/publication_test.clj` | 1 | 0 | 0 |
| `seon/dev/source_instrumentation_test.clj` | 0 | 1 | 0 |
| `seon/effect_test.clj` | 1 | 1 | 0 |
| `seon/error/refusal_test.clj` | 1 | 0 | 0 |
| `seon/error_recording_test.clj` | 0 | 1 | 0 |
| `seon/error_test.clj` | 1 | 0 | 0 |
| `seon/error_write_timing_test.clj` | 0 | 2 | 0 |
| `seon/flow_test.clj` | 2 | 0 | 0 |
| `seon/fn/analyzer_test.clj` | 0 | 2 | 0 |
| `seon/fn/publication_cache_test.clj` | 1 | 0 | 0 |
| `seon/fn/publication_test.clj` | 0 | 1 | 0 |
| `seon/fn_test.clj` | 12 | 3 | 0 |
| `seon/fs/jvm_test.clj` | 1 | 3 | 0 |
| `seon/fs_test.clj` | 0 | 1 | 0 |
| `seon/gen/loop_test.clj` | 3 | 0 | 0 |
| `seon/help_test.clj` | 1 | 0 | 0 |
| `seon/incremental_publication_test.clj` | 0 | 1 | 0 |
| `seon/instrument_replaced_roots_test.clj` | 0 | 0 | 1 |
| `seon/instrument_test.clj` | 1 | 1 | 6 |
| `seon/mcp_test.clj` | 2 | 1 | 0 |
| `seon/owned_value_test.clj` | 1 | 0 | 0 |
| `seon/problems_test.clj` | 1 | 0 | 0 |
| `seon/profile_test.clj` | 0 | 0 | 3 |
| `seon/program_test.clj` | 1 | 0 | 0 |
| `seon/publication_validation_test.clj` | 0 | 1 | 0 |
| `seon/reconcile_test.clj` | 2 | 0 | 0 |
| `seon/registry_isolation_test.clj` | 0 | 0 | 2 |
| `seon/render/retained_test.clj` | 3 | 0 | 0 |
| `seon/render/root_pull_test.clj` | 4 | 1 | 0 |
| `seon/render/walk_test.clj` | 1 | 0 | 0 |
| `seon/render/web_adoption_test.clj` | 1 | 0 | 0 |
| `seon/render/web_context_test.clj` | 4 | 1 | 0 |
| `seon/render/web_debug_test.clj` | 1 | 1 | 0 |
| `seon/render/web_feed_test.clj` | 0 | 1 | 0 |
| `seon/render/web_performance_test.clj` | 1 | 0 | 0 |
| `seon/render/web_test.clj` | 14 | 5 | 0 |
| `seon/render_coverage_test.clj` | 1 | 0 | 0 |
| `seon/render_simplification_test.clj` | 8 | 0 | 0 |
| `seon/render_source_test.clj` | 3 | 0 | 0 |
| `seon/schema/datahike_test.clj` | 2 | 0 | 0 |
| `seon/schema/declaration_population_test.clj` | 2 | 0 | 0 |
| `seon/schema/edn_test.clj` | 2 | 2 | 0 |
| `seon/schema/projection_acquisition_test.clj` | 1 | 0 | 0 |
| `seon/schema/projection_writer_test.clj` | 5 | 0 | 0 |
| `seon/schema_test.clj` | 6 | 1 | 0 |
| `seon/sci/admit/declaration_population_test.clj` | 1 | 0 | 0 |
| `seon/sci/eval_instrumentation_test.clj` | 1 | 0 | 0 |
| `seon/sci/eval_test.clj` | 6 | 0 | 4 |
| `seon/sci/reader_test.clj` | 1 | 0 | 0 |
| `seon/test/reach_test.clj` | 1 | 1 | 0 |
| `seon/test/selection_test.clj` | 2 | 0 | 0 |
| `seon/test_provenance_test.clj` | 1 | 0 | 0 |
| `seon/test_reaching_test.clj` | 0 | 0 | 1 |
| `seon/test_support_test.clj` | 0 | 0 | 1 |
| `seon/test_test.clj` | 1 | 0 | 0 |
| `seon/turn_continue_test.clj` | 1 | 0 | 0 |
| `seon/turn_loop_test.clj` | 9 | 1 | 0 |
| `seon/turn_test.clj` | 2 | 1 | 0 |
| `seon/turn_work_cost_test.clj` | 1 | 0 | 0 |
| `seon/web/jvm_test.clj` | 0 | 2 | 0 |

Spot checks prevent misusing the census:

- `test/seon/bootstrap_test.clj:174,181` submits literal function and test rows,
  including invented successful test facts: a true R1 constructor/reuse problem.
- `test/seon/call_preparation_test.clj:182` constructs namespace/schema rows around
  analyzed function rows: a mixed case; preserve behavior at the future A1 wrapper seam.
- `test/seon/config_test.clj:94` is an **expected result**, not a seed. Do not replace it.
- `test/seon/namespace_agent_loop_test.clj:37,70` has legitimate owner start inputs
  beside a literal issue write and hand-composed graph setup. A map ban rejects both.
- `test/seon/blob_test.clj:38` writes native attribute schema for a store subject;
  it is outside the eleven identity-key census. Lexical counts are not complete.

Largest map-candidate files: `turn_test.clj` 106; `turn_loop_test.clj` 73;
`cluster/turn_test.clj` 53; `cluster/agent_test.clj` 51; `db_test.clj` 47;
`fn_test.clj` 46. No claim is made that these counts are all fixture defects.

Reproduction script is preserved below rather than installing a new production lint.
It ran as `python3 tmp/test-worlds-design/census.py`; JSON at
`tmp/test-worlds-design/census.json` retains every candidate path/line and token count.
The committed script text is the reproducibility authority; tmp is disposable.

```python
from pathlib import Path
import re, collections, json, time
start=time.perf_counter()
files=sorted(p for p in Path('test').rglob('*') if p.suffix in ('.clj','.cljc','.cljs','.edn'))
lex=re.compile(r';[^\n]*|"(?:\\.|[^"\\])*"|\\(?:[^\s\[\]{}()]+|.)|#\{|[\[\]{}()]|[^\s,\[\]{}()";]+')
identities={':seon.agent/id', ':seon.ns/name', ':seon.fn/sym', ':seon.test/sym', ':seon.schema/key', ':seon.cluster/name', ':seon.config/cluster', ':seon.message/id', ':seon.turn/id', ':seon.error/id', ':seon.issue/id'}
maps=[]; forms=collections.Counter(); perfile={}; helpers=collections.Counter()
for p in files:
 s=p.read_text(); stack=[]; fc=collections.Counter()
 for m in lex.finditer(s):
  t=m.group()
  if t.startswith(';') or t.startswith('"') or t.startswith('\\'): continue
  if t in ('{','#{','[','('):
   stack.append([t,m.start(),set()]); continue
  if t in ('}',']',')'):
   if stack:
    kind,pos,keys=stack.pop()
    if kind=='{' and keys:
     maps.append({'path':str(p),'line':s.count('\n',0,pos)+1,'keys':sorted(keys)})
   continue
  if stack and stack[-1][0]=='{' and t in identities: stack[-1][2].add(t)
  bare=t.split('/')[-1]
  if bare in ('with-redefs','with-redefs-fn','alter-var-root'):
   forms[bare]+=1; fc[bare]+=1
  if bare in ('agent-tx','agents-tx','program-fn-row','program-row','namespace-row','transacted!','with-database','fork-cluster-ctx','seed-cluster!','apply-config!'): helpers[bare]+=1
 if fc: perfile[str(p)]=dict(fc)
counts={k: {'maps':sum(k in x['keys'] for x in maps), 'files':len({x['path'] for x in maps if k in x['keys']})} for k in sorted(identities)}
result={'files':len(files),'forms':dict(forms),'redef_files':len(perfile),'helpers_tokens_including_definitions':dict(helpers),'literal_candidates':len(maps),'literal_files':len({x['path'] for x in maps}),'by_identity':counts,'perfile_redefs':perfile,'maps':maps,'elapsed_ms':(time.perf_counter()-start)*1000}
Path('tmp/test-worlds-design/census.json').write_text(json.dumps(result,indent=2))
print(json.dumps({k:v for k,v in result.items() if k not in ('maps','perfile_redefs')},indent=2))
print('top files',collections.Counter(x['path'] for x in maps).most_common(12))
```

## Read-only REPL evidence

Every form used MCP JVM mode, explicit root `/Users/sean/src/seon`, cluster `default`,
`read_only: true`, and a 1,000 or 2,000 ms tool bound. Initial calls used the default
inspection session; final namespace census used `test-worlds-design`. No definitions,
root mutations, branch acquisition or test execution occurred. Small returned values
were deliberately used after the first large census exceeded rendering bounds.

Initial aggregate probe:

```clojure
(let [t (System/nanoTime)
      c (seon.cluster.boot/connection "default") d (seon.db/db c)]
  {:basis (seon.db/basis-t d)
   :clusters (seon.db/q '[:find [?n ...] :where [_ :seon.cluster/name ?n]] d)
   :tests (seon.db/q '[:find (count ?e) . :where [?e :seon.test/sym]] d)
   :platform (seon.db/q '[:find (count ?e) . :where [?e :seon.test/platform]
                         [?e :seon.test/sym]] d)
   :destroyers (seon.db/q '[:find [?s ...] :where [?e :seon.fn/destroys]
                           [?e :seon.fn/sym ?s]] d)
   :ms (/ (- (System/nanoTime) t) 1e6)})
```

Value `{:basis 536872632 :clusters ["default"] :tests 2107 :platform 80
:destroyers [seon.test-support/populate-published-root!
seon.test-support/populate-published-operator-root!] :ms 5.251791}`.
Envelope: `ret`, **7 ms**, runtime `clj`, alive, unwindowed, no error event.

Compact platform set probe (after an elided identity inventory):

```clojure
(let [t (System/nanoTime)
      d (seon.db/db (seon.cluster.boot/connection "default"))
      s (seon.test/isolated-members d)
      p (set (seon.db/q '[:find [?s ...] :where [?e :seon.test/platform]
                         [?e :seon.test/sym ?s] (not [?e :seon.test/fixture])] d))
      f (set (seon.db/q '[:find [?s ...] :where [?e :seon.test/fixture-observation]
                         [?e :seon.test/sym ?s] (not [?e :seon.test/fixture])] d))]
  {:basis (seon.db/basis-t d) :isolated (count s) :platform (count p)
   :fixture (count f) :both (count (clojure.set/intersection p f))
   :destructive-only (count (clojure.set/difference s p f))
   :ms (/ (- (System/nanoTime) t) 1e6)})
```

Value `{:basis 536872632 :both 0 :destructive-only 19 :fixture 52 :isolated 151
:ms 23.162708 :platform 80}`. Envelope: `ret`, **25 ms**, `clj`, alive, unwindowed,
no error event. The selector derives membership; this does not prove the tests pass.

First identity inventory used the same `s`, `p`, `f` bindings, returning those sets
instead of counts: **727.993917 ms**, envelope **731 ms**, windowed/elided. The next
query grouped `s` by namespace into a sorted count map: **30.005625 ms**, envelope
**32 ms**, also windowed. Final namespace census used this exact form:

```clojure
(let [t (System/nanoTime)
      d (seon.db/db (seon.cluster.boot/connection "default"))
      s (seon.test/isolated-members d)]
  {:basis (seon.db/basis-t d)
   :namespace-pages
   (mapv vec (partition-all 25
               (sort (map (fn [[k v]] [k (count v)]) (group-by namespace s)))))
   :ms (/ (- (System/nanoTime) t) 1e6)})
```

Returned basis **536872632**, **514.691459 ms** (envelope **516 ms**, `ret`, alive,
windowed flag). The displayed two pages contain these complete 49 pairs, total 151:

```text
my.examples-test 1
seon.ai-stream-fold-test 1
seon.background-blob-test 1
seon.blob-publication-test 1
seon.blob-test 1
seon.cluster.acquisition-test 1
seon.cluster.armed-test 6
seon.cluster.boot-test 10
seon.cluster.cohost-boot-test 1
seon.cluster.entrance-supplier-test 1
seon.cluster.fault-storage-test 2
seon.cluster.mcp-test 2
seon.cluster.program-restart-test 1
seon.cluster.publication-concurrency-test 1
seon.cluster.publication-declared-schema-test 1
seon.cluster.publication-export-test 1
seon.cluster.publication-inputs-test 1
seon.cluster.publication-lock-test 6
seon.cluster.publication-reuse-test 1
seon.cluster.registry-test 14
seon.cluster.source-database-test 1
seon.cluster.source-lineage-test 3
seon.cluster.source-nochange-test 1
seon.cluster.source-test 2
seon.cluster.store-test 18
seon.concurrency-independence-test 1
seon.config-application-test 1
seon.db.declaration-population-test 1
seon.dev.dependency-cache-test 1
seon.dev.fresh-operator-export-test 1
seon.env-test 5
seon.flow-configuration-test 1
seon.fs-test 3
seon.incremental-publication-test 1
seon.issue-head-guard-test 1
seon.oversight-test 1
seon.reset-edges-test 1
seon.schema-redeclare-test 1
seon.schema.declaration-population-test 3
seon.sci.admit.declaration-population-test 3
seon.sci.eval-instrumentation-test 1
seon.sci.kernel-arm-carriage-test 6
seon.shell.jvm-test 7
seon.test-reaching-test 2
seon.test-runner-test 1
seon.test-support-test 11
seon.test.runner-test 3
seon.test.selection-test 9
seon.web.jvm-test 7
```

Canonical constructor / installed schema probe:

```clojure
(let [t (System/nanoTime)
      d (seon.db/db (seon.cluster.boot/connection "default"))
      tx (seon.cluster.agent/creation-tx
          {:seon.agent/id "test-worlds-design-probe"
           :seon.ns/name 'my.agents.test-worlds-design-probe
           :seon.cluster/name "explicit-cluster" :seon.agent/branch :explicit-branch})]
  {:basis (seon.db/basis-t d) :operations (count tx)
   :created-branch (:seon.agent/branch (second tx))
   :owned-keys (vec (sort (keys (second tx))))
   :schema (seon.db/pull d [:seon.schema/key :seon.schema/form]
                         [:seon.schema/key :seon.agent/creation-request])
   :agent-present? (boolean (seon.db/q '[:find ?e . :where
                                       [?e :seon.agent/id "test-worlds-design-probe"]] d))
   :ms (/ (- (System/nanoTime) t) 1e6)})
```

Complete returned value:

```clojure
{:agent-present? false :basis 536872632 :created-branch :explicit-branch
 :ms 0.387542 :operations 3
 :owned-keys [:db/id :seon.agent/branch :seon.agent/id :seon.agent/namespace
              :seon.agent/plan :seon.agent/runtime :seon.agent/settings]
 :schema {:seon.schema/key :seon.agent/creation-request
          :seon.schema/form "[:map [:seon.agent/id :seon.agent/id] [:seon.agent/branch {:optional true} :seon.agent/branch] [:seon.ns/name :seon.ns/name] [:seon.cluster/name :seon.cluster/name]]"}}
```

Envelope: `ret`, **2 ms**, `clj`, alive, unwindowed, no error event. Three operations
are construction output, not proof of transaction acceptance, arming or agent execution.

An earlier variant returned the whole transaction and read stale `:seon.schema/edn`.
The read owner returned `:seon.db/invalid-read true`, operation `seon.db/pull`, full
ExceptionInfo cause/frames, and message `Bad entity attribute :seon.schema/edn at
(resolve-datom db 19299 :seon.schema/edn nil nil), not defined in current schema`.
**2.336041 ms**, envelope **4 ms**, with a Datahike error log. This invalid research
input was corrected to `/form` above; it is not evidence of a broken writer. No row
was transacted. The correction matters because older B4 schema examples still use `/edn`.

Tool-side limitation: windowed results produced blob artifacts even for read-only
forms (digests `3ca4a74178f04e15e5646dc143e4dc35dce0c6cc3a02b3e6edaf677359bea531`,
`ed71392f62448a21bd8385736587fc70d2d6dfc7af953746b2a56d85cb748362`,
`e37383137c97b1081d7654c93213903315dab756d3f405af0a8bc7cb8d2b50b6`,
`ad486ff37303ca4da29bbc85df73a2d95ae23632dce19cd1e0d1d984e7af8620`).
Thus “read-only probes” describes evaluated forms, not a claim that the MCP transport
wrote no storage. No artifact deletion was attempted. README's existing MCP result
retirement ruling owns that behavior. No branch latency benchmark was authorized or run.

## Landing boundary

Owned paths: B4 spec and this evidence report only. Net src **0**, test **0** lines.
No runtime edits, reload/adoption, paid effects, test request, gate, platform proof,
worktree, foreign-lane session control or push. A test request writes evidence and is
not necessary for this read-only design assignment. No source-load, branch isolation,
world latency, wrapper injection or implementation pass is claimed. The next step is
independent review before implementation. Foreign archive/source divergence limits
runtime conclusions; it did not prevent completing the design.

Documentation validation and the path-limited commit are the landing proof. The commit
id is returned in the handoff (the document cannot include its own final content hash).

Validation: path-limited `git diff --check` clean; all **10** relative Markdown file
links resolve (**0.684 ms** check). MCP read all **10** Clojure fences from both
documents with `*read-eval* false`: form counts **[3 1 1 2 1 1 1 1 1 1]**, **0.333458 ms**,
envelope **3 ms**, `ret`, alive, unwindowed. This proves reader syntax only; proposed
names and historical examples were not executed or compiled. Document additions are
B4 **284** lines plus this evidence report; src/test remain **0/0**.
