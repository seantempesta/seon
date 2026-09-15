---
type: research
status: complete
tags: [agent, documentation, audit]
---

# AGENTS.md audit — 2026-09-15

## Boundary and result

Read AGENTS.md end to end (943 lines at `be0c498302d805be130fb02b934dbd866dd44b29`). CLAUDE.md is the tracked
symlink to it and needs no separate edit. This audit owns only AGENTS.md and
this note. Source, schema, dependency, runner and historical evidence below is
read from that HEAD, including the gitlink-pinned dependency revisions.
Concurrent edits are not evidence for corrections.

**Counts:** 181 table rows: **115 current, 50 stale,
16 wrong; 13 deleted**. Deleted is an action subset, not a fourth
verdict. Rows comprise 113 claim/mechanism records and
68 reference records. Repeated references are grouped by
all original locations; a semantic claim and its pointer may have separate
rows. These counts describe the audit records, not independent code defects.
“Current” for a design/workflow instruction means the named authority or
mechanism remains applicable, not that all implementations satisfy every law.

Corrections cover authored-contract re-arming and bounded adoption retry,
projection state on database values, persistent agent contexts and crash
recovery, saved shown text and result objects, compaction/doc/dir/read evidence,
message writes, API arity differences, CLI behavior and file/function pointers.
Evaluation identity renaming, entity-wide render-pair consolidation, canvas,
source-initialization consolidation, root maintenance and my.branch retain
TARGET labels: related functions alone do not prove those whole targets.

Two runner slots are **per source checkout**, shared by its invocations.
`bin/_test-slot` says “machine-wide”, but derives its directory from source_root
or PWD. Separate checkouts do not share that directory. Each admitted gate can
still own several JVMs. AGENTS.md now states the implemented scope.

No test JVMs were launched (explicit assignment). No default stop, refork,
restart, SCI evaluation, provider call, source reload or source adoption was
performed. All shell commands were awaited and no background shell was created.
The working tree was already dirty in nine foreign paths at entry and changed
further during this audit; no foreign files or lane sessions were operated.
The boundary is committed source inspection plus one host-JVM observation,
not a fresh full-loop or browser proof. No foreign gate was run or attributed.

## Live observation

`bin/seon status` reported default alive, PID 69622, prepl 55914,
http://127.0.0.1:7994, one of one advertised clusters alive, no orphan JVMs.
It explicitly reported “root footprint: not scanned (status --verbose)” and
“test evidence: UNKNOWN; not queried by descriptor-only status”. These are
observations of the inherited system, not health inferred from missing output.

Exactly one `mcp__seon__eval_clj` call used root `/Users/sean/src/seon`, cluster
`default`, mode `jvm`, timeout 20000 ms, and the following read-only form:

```clojure
(let [c (seon.operator/connection "default")]
  {:audit/connection-present (some? c)
   :audit/database-class (str (class @c))
   :audit/functions
   (into {}
         (map (fn [s]
                [s (when-let [v (resolve s)]
                     (select-keys (meta v) [:file :line :arglists]))])
         '[seon.turn/open? seon.turn/open-call seon.turn/step
           seon.turn/work seon.turn/turn seon.turn/system-turn
           seon.id/evaluation seon.schema/projection-from-database])})
```

The complete returned MCP envelope reported `cluster-state: alive`, runtime
`clj`, mode `jvm`, namespace `user`, and a `ret` event taking 2 ms. Its projection
was marked windowed, with blob size 6065 and digest
`088d7b111d8d71f328795dbbd50b8fc40e7f1c8624fdfd2092a4d4aea52d0b2c`.
The displayed result supplied all requested names: connection true, class
`datahike.db.DB`; open? 189, open-call 348, step 4933, turn 4723,
system-turn 2039, evaluation 55, projection-from-database 2452.
`seon.turn/work` was absent. Its replacement label is `next-agent-work`, verified
from HEAD source at 2753. No claim depends on undisplayed blob bytes.

## Dependency ledger and method

No new dependency or mechanism was introduced. First-party owners are db/env
for carried projection state, sci/eval and cluster/agent for context ownership,
turn for recovery/system turns, repl/render for saved results, instrument and
cluster for adoption, and bin/test with test/runner for durable gate evidence.
The table cites their exact source declarations. Dependency reads use the
SCI, Datahike, core.async, Clojure, konserve and clj-kondo gitlinks at the audit
basis; their source pointers are included below.

Reproduce source evidence with `git show be0c498302d805be130fb02b934dbd866dd44b29:PATH` and locate forms with
`rg -n`. For a dependency, obtain its commit with
`git rev-parse be0c498302d805be130fb02b934dbd866dd44b29:reference-code/NAME`, then run
`git -C reference-code/NAME show COMMIT:PATH-IN-SUBMODULE`.
Reference inventory scans every full path and numeric pointer in the original
AGENTS.md; shorthand numeric pointers are listed separately. Verification checks
both line existence and whether it names the intended declaration, not merely
whether the line number is in range. Named commands were checked against their
parsers/entry points without launching test or operator mutation commands.

## Claim table

| Claim | Location in AGENTS.md at audit basis | Verdict | Evidence | Action |
|---|---|---|---|---|
| AGENTS.md is the authority; CLAUDE.md resolves to its bytes | AGENTS.md:9–12 | current | `git ls-tree be0c498302d805be130fb02b934dbd866dd44b29 CLAUDE.md` gives mode 120000; `git show be0c498302d805be130fb02b934dbd866dd44b29:CLAUDE.md` is `AGENTS.md`; filesystem readlink agrees. | retain |
| Lane rules are a verbatim PRD §10 copy | AGENTS.md:15–22 | current | `docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md:723` — `## 10. Lane rules for this wave (the source copied verbatim into AGENTS.md)` The numbered rules match PRD §10 at the audit basis; these corrections update that copy, so the evergreen introduction must stop claiming verbatim identity. | correct |
| Fast iteration, paths snapshots, explicit namespaces, platform/all/full selection and contract arming | AGENTS.md:24–45;708–737 | current | `bin/test-fast:14` — `exec bin/test --fast "$@"` Plain fast runs seon.test.fast; bin/test parses paths and archives HEAD, overlays selected paths, then invokes the runner. This is a workflow requirement, not a gate run in this audit. | retain |
| Historical attribution e33a887fe | AGENTS.md:37 | current | `bin/test:490` — `git archive "$git_sha" \| tar -x -C "$run_root"` Commit exists (Record runner proofs and the cross-lane bare-gate boundary); remove the dated attribution from evergreen instructions. | delete |
| One AI presentation boundary; max-bytes as result-storage rule | AGENTS.md:46–49 | stale | `src/seon/sci/eval.clj:1957` — `(defn- shown-result` The ordinary evaluation path stores :seon.eval/shown and retains the value; legacy admission still has max-bytes. Remove the superseded storage instruction. | correct |
| Path-only commits, preserve concurrent edits, scratch worktree and background cleanup commands | AGENTS.md:50–59;891–900 | current | Git command syntax is valid; these are owner workflow requirements, not claims that the working tree is clean. `git status --short` showed foreign edits. No worktree, shell runner, or test JVM was launched. | retain |
| Unqualified §0a points to retired spellings | AGENTS.md:60–61 | wrong | AGENTS.md has no §0a; the PRD does. Replace with the local vocabulary table. | correct |
| Default development target and source coalescing | AGENTS.md:62–73 | current | `.claude/seon-hook.edn:23` — `:current-source` Root ".", cluster "default", quiet-seconds 5, timeout-seconds 180 are explicit. Never restart default is an instruction. | retain |
| Lanes kept default down most of 2026-09-08 | AGENTS.md:66–67 | wrong | No duration observation accompanies this assertion. Delete the unquantified operational anecdote; retain the no-restart instruction. | delete |
| Operator start/init/config/stop/down/reset and isolated-root syntax | AGENTS.md:69–73;769–785 | current | `script/seon/fresh_operator.clj:401` — `(fail! "Use config apply [CLUSTER] PATH."` Command parser and usage confirm named commands, --root, --force, --dev, --changed; scratch creation is not performed. | retain |
| Codex lane model, effort, run/resume/status/summary/stop and log behavior | AGENTS.md:74–75;860–877 | current | `bin/codex-agent:51` — `model=${LANE_MODEL:-gpt-6-astra}` effort=${LANE_EFFORT:-low}; run/resume persist stdout and final summaries. Native collaboration is the Codex workflow requirement. | retain |
| Research/issues/tmp/test/reference-code locations and Git archaeology | AGENTS.md:76–88;634–638 | current | `git ls-tree -r be0c498302d805be130fb02b934dbd866dd44b29` verifies the named tracked roots, research directories, and dependency gitlinks; tmp is the required disposable local location. | retain |
| First implementation worked for months before its deliberate teardown | AGENTS.md:82–85 | wrong | Source history establishes earlier implementation, not a duration of successful operation. Delete the unverifiable success-duration narrative. | delete |
| Over a hundred dependency submodules | AGENTS.md:88 | current | `git ls-tree -r be0c498302d805be130fb02b934dbd866dd44b29 reference-code` contains 109 gitlinks. Delete the undated count; retain the dependency source location. | delete |
| SCI live environment, konserve GC/content-addressing, Datahike branch pointers | AGENTS.md:94–99 | wrong | `reference-code/sci/src/sci/core.cljc:331` — `(defn init` Konserve provides binary key/value storage and GC; content addressing is supplied by its callers. Correct the binary-storage claim; Datahike owns branch pointers. | correct |
| 283 ms cold acquisition per turn | AGENTS.md:98–99 | current | `docs/prds/sci-execution-runtime/plan/per-cluster-base-context-2026-08-01.md:74` — `\| acquire! median (7 samples: 286/281/283/279/284/282/284) \| **283 ms** \|` Historical measurement is retained in its dated source; delete the repeated number from evergreen instructions. | delete |
| Six of six assumptions falsified | AGENTS.md:107–109 | current | `docs/prds/sci-execution-runtime/plan/README.md:40` — `prose wrong, six of six assumptions falsified in a single sitting.` Historical statement; remove duplicated incident count. | delete |
| Open means no closed-tx | AGENTS.md:110;549 | current | `src/seon/turn.clj:189` — `(defn open?` Reads absence of ::closed-tx; open-call checks at the writer. Pointer correction is separately inventoried. | retain |
| Five-class synthesis and stable program-identity tombstones | AGENTS.md:113–124 | current | `src/seon/cluster.clj:1936` — `;; whose identity now has no source, retaining their durable tombstones.` The linked dated synthesis exists. Publication removes definitions while keeping identities; the population invariant is a design requirement, not a new census. | retain |
| CLJ process, REPL before boot layers, exactly two conceptual states | AGENTS.md:142–151 | stale | `src/seon/cluster.clj:5` — `advertises an io-prepl, then builds the remaining instance from the` Boot/running are conceptual phases; REPL is opened before store work, not literally at second zero. | correct |
| Closed bootstrap config and process pid/start identity | AGENTS.md:153–156;552 | current | `src/seon/cluster.clj:4` — `start! resolves the closed bootstrap configuration, opens and` Process owner and operator parser supply the remaining construction inputs; see file inventory. | retain |
| Store at data/store, lock at data/store.lock, flock and one connection per branch | AGENTS.md:157–166 | current | `src/seon/cluster/store.clj:125` — `(defn lock-file` Operator state derives lock path; Datahike writer serializes connection transactions. | retain |
| 40/40 commits lost with two store writers | AGENTS.md:164–165 | current | `docs/prds/sci-execution-runtime/research/f2-live-render-proof-2026-07-28.md:18` — `40/40 commits.` Retained historical incident; remove duplicate measurement. | delete |
| current-src publication and explicit development adoption | AGENTS.md:167–173 | current | `src/seon/cluster.clj:1934` — `(report-source-progress! "development loaded definitions")` Ordinary forks use the publication; explicit adoption reconciles source and preserves agent facts. | retain |
| Agent graphs; shared executors; workload tags and losable channels | AGENTS.md:174–181;548;558–559 | stale | `src/seon/cluster/agent.clj:422` — `(defn graph-definition` Uses Flow graph-def with explicit workloads; pinned dispatch/spi and sliding-buffer owners are listed below. Abbreviated paths corrected. | correct |
| One environment per cluster; scope carries agent identity | AGENTS.md:183–188;528;534 | current | `src/seon/env.clj:253` — `(defn scope` scope accepts only members of the declared turn layer. seon.env.edn defines the environment. | retain |
| Var-referenced transforms update; topology rebuilds graph | AGENTS.md:190–194 | current | `src/seon/cluster/agent.clj:450` — `#'turn/step :io` Flow graph owns the callable Var; graph topology is supplied to create-flow. | retain |
| Publication, adoption and success-only source commit | AGENTS.md:196–204;793–799 | current | `src/seon/cluster.clj:1993` — `;; This fact means indexing, reload, SCI acquisition, and instrumentation` Marker follows instrumentation and source-digest check; it does not imply atomic rollback. | retain |
| Existing wrappers always retained/restored after reload failure | AGENTS.md:205–208 | wrong | `src/seon/instrument.clj:588` — `(defn apply!` Authored schema participates in current-wrapper?; changed contracts re-arm. cluster adoption retries source-change refusal once. | correct |
| Shown text durable, live results excluded from blobs; losable channels | AGENTS.md:211–218 | stale | `src/seon/sci/eval.clj:1980` — `:seon.eval/shown (if (string? shown) shown (pr-str shown))}` Object retention is implemented by bind-result!; remove target label for this behavior. | correct |
| Crash recovery and persistent contexts still targets; line 1821 rehydrates defs | AGENTS.md:220–228 | wrong | `src/seon/sci/eval.clj:1709` — `(defn fork-for-turn` Reuses agent-ctx through receive-base!; only first acquisition calls sci/fork. turn/recover-call closes interrupted turns. No rehydration exists at line 1821. | correct |
| Opening/system reads/wake answering still partial target | AGENTS.md:230–238 | stale | `src/seon/turn.clj:2039` — `(defn system-turn` Unchanged reads do not append; write? stores closed system turns. latest-answering-turn-t qualifies replies. step invokes the ordinary loop. | correct |
| Agent errors versus core faults and configuration dial | AGENTS.md:240–244 | current | `config/default.edn:282` — `:seon.config/on-core-error :panic` Shipped :panic; error and flow owners implement diagnostics and fault delivery. This is the required boundary contract. | retain |
| Downstream scope and orchestrator manual | AGENTS.md:246–249 | current | Repository scope is an owner requirement; docs/TRANSFER_PROMPT.md exists at the audited commit (inventory below). | retain |
| Values carry projection; temporal schema derives through origin | AGENTS.md:259–271 | stale | `src/seon/db.clj:141` — `(defn carry-projection-state` read-declarations reads metadata on schema-database origin before fallback; evaluation binds a carried read database. Update grounding for the landed mechanism. | correct |
| Illustrative schema/register! takes a projection map | AGENTS.md:273–279 | wrong | `src/seon/schema.clj:1290` — `(defn register!` Actual arguments are k and v. Replace with the real explicit-projection storable-attribute-in? call. | correct |
| 217 s versus 6.2 s projection measurement | AGENTS.md:281–285 | current | `docs/prds/context-generation/research/agents-md-verification-audit-2026-08-13.md:254` — `\| Fixed render profiles changed the affected fixture cost from 217 s to 6.2 s. \| VERIFIED \| Commit 1930dacd1 exists and is titled “Supply fixed render profiles in flow fixtures”; t` Historical evidence belongs in that dated record; delete repeated number. | delete |
| Open law-2.1 issue tag class/p1 | AGENTS.md:285 | current | `git grep -l class/p1 be0c498302d805be130fb02b934dbd866dd44b29 -- docs/seon/issues` finds the issue family; not a promise that every member is still open. | retain |
| Queryable program declarations and tests-reaching/input-refs example | AGENTS.md:289–313 | current | `src/seon/fn.clj:825` — `(defn tests-reaching` Public compatibility alias delegates to gate-set; seon.turn/open-tx exists; :seon.fn.arity/input-refs is declared in seon.fn.arity.edn. | retain |
| Derive/enforce/date mirrors and owner permission for production regex | AGENTS.md:315–325 | current | Owner design requirements. rg is explicitly allowed as working tooling; no production regex was introduced. | retain |
| Bounded event-driven SCI execution, interrupt-fn and diagnostic fn-entries | AGENTS.md:330–345;542–546 | current | `src/seon/sci/kernel.clj:276` — `(defn arm` SCI interrupt dependency, effect request deadlines, event-backstop-seconds and runner watchdog support the named seams; fn-entries is recorded, not the deadline. | retain |
| Malli boundary contracts and evidence-complete diagnostic constructor | AGENTS.md:350–361 | current | `src/seon/error.clj:302` — `(defn diagnostic` This paragraph prescribes the boundary invariant; apply! arms authored schemas. It is not evidence that every existing function is defect-free. | retain |
| Actual result objects, saved shown text, AI profile bounds and unbounded HTML | AGENTS.md:363–390 | stale | `src/seon/sci/eval.clj:1957` — `(defn- shown-result` bind-result! holds objects; repl/shown-value reads saved text and render-html tries live-response. Value prepare selects HTML mode separately. Target label for object storage removed. | correct |
| request-profile derives missing profile | AGENTS.md:374–376 | stale | `src/seon/render.clj:67` — `(defn request-profile` Requires a handed projection; otherwise returns a typed refusal. Supplied profile wins. This precondition is added to the instruction. | correct |
| Human display estimates use seon.ai.tokens/estimate | AGENTS.md:388–390 | current | `src/seon/ai/tokens.cljc:128` — `(defn estimate-of-characters` This is a display instruction; no new measurement or guarantee of perfect estimates. | retain |
| Open maps, namespaced schema population, nil/undefined contract restrictions, no entity-kind stamps | AGENTS.md:394–408;419–443 | current | `git grep "closed true" be0c498302d805be130fb02b934dbd866dd44b29 -- resources/seon/schemas` returns no matches. schema/internal checks stored-nil and undefined types with explicit polymorphic exemptions. These paragraphs prescribe design rules. | retain |
| Identity discovery and storable-attribute-in? | AGENTS.md:435–437 | current | `src/seon/db.clj:929` — `(defn populated-identity-attributes` Queries actual AVET datoms over installed identity attributes; schema.datahike/storable-attribute-in? asks the declaration bridge. | retain |
| contains? checks vector indices and map keys, including nil-valued keys | AGENTS.md:445–451 | current | Pinned Clojure core contains? implementation/docstring; examples are semantic illustrations, not stored nil assertions. | retain |
| my.* reads through db and returns effects for loop interpretation | AGENTS.md:453–459 | wrong | `src/seon/cluster/message.clj:733` — `(defn send!` send! transacts directly and returns the stored message; my.message/send calls it. Returned-only message effects are obsolete. | correct |
| Every database function preserves both dependency arities/results | AGENTS.md:461–469 | wrong | `src/seon/db.clj:2961` — `(defn transact!` Explicit connection returns full report; elided arity returns :seon.db/tx and datoms. Replace blanket equivalence with declared API guidance. | correct |
| Config reconciliation and transaction provenance refs | AGENTS.md:471–476 | current | `resources/seon/schemas/seon.db.edn:196` — `:process [:and #:seon.db{:index true} :seon.db/ref],` Also declares :user ref; cluster/message send! supplies tx-meta rather than copying provenance onto message entities. | retain |
| Evaluation spelling migration remains a target; MCP modes jvm/sci | AGENTS.md:480–495 | stale | `script/seon/dev/mcp.clj:581` — `(when-not (contains? #{"jvm" "sci"} mode)` Identity still :seon.cluster.eval/id; keep the entity-family rename target. jvm/sci are already current; remove retired mode from live prose. | correct |
| Vocabulary precedence and legacy-column recognition | AGENTS.md:501–522 | current | Normative naming instructions. Legacy spellings remain only as recognition aids or exact identifiers; ordinary nontechnical uses are not renamed mechanically. | retain |
| SCI call preparation supplies absent declared arguments | AGENTS.md:529 | current | `reference-code/sci/src/sci/core.cljc:310` — `- :call-preparation-hook: a three-arg fn (hook ctx var args) called at` Hook receives ctx, var and args; first-party call-preparation supplies keys without overwriting supplied values. | retain |
| Canvas target, UI design and no declared canvas attribute | AGENTS.md:530 | current | `docs/seon/architecture/ui.md:140` — `Generalized agent-authored canvas and control constructors remain a` No :seon.canvas schema identity is installed in HEAD source population; retain target label. | retain |
| Surface/card, attributes/refs, get-in/path, manifest names, contexts/bindings and accretion terms | AGENTS.md:531;535;537;540–541;550 | current | Terminology instructions and ecosystem names, not assertions that package.json or packages/ must exist in this CLJ tree; Clojure/SCI semantics and deps.edn are the grounding. | retain |
| Web UI route inventory | AGENTS.md:532 | stale | `src/seon/render/route.clj:9` — `["/ns/{namespace}" {:name ::namespace` Namespace routes are present in addition to root/agent/debug/data; include them. | correct |
| Subagents connected by database refs | AGENTS.md:533 | wrong | No parent/child agent relationship is declared in seon.agent.edn at this HEAD. Delete the ungrounded vocabulary row. | delete |
| bin/seon and bin/acme operator scope | AGENTS.md:536 | current | `bin/acme:6` — `ACME_OPERATOR_ROOT="${SEON_ACME_ROOT:-$SEON_SOURCE_ROOT/tmp/acme-operator-root}"` acme wrapper explicitly supplies isolated root and cluster. | retain |
| my.plan owns authored plan facts and writes changed entity | AGENTS.md:538 | stale | `resources/seon/schemas/seon.agent.edn:151` — `:plan [:and {:seon.db/component true` Component declaration is line 151, not line 1; my/plan delegates to seon.plan. | correct |
| Provider descriptor rows under configuration | AGENTS.md:539 | current | `config/default.edn:503` — `{:seon.ai.model/provider-id "deepseek"` Provider rows and separate model refs are declared data, not inferred adapters. | retain |
| SCI ctx/fork, candidate isolation and persistent private layer | AGENTS.md:547;570–571;578 | stale | `src/seon/sci/eval.clj:1709` — `(defn fork-for-turn` Actual persistent context and base diffs landed; candidate evaluation still forks separately. Add first-party grounding. | correct |
| Turn open?, open-call and render schema pointers | AGENTS.md:549 | stale | `src/seon/turn.clj:348` — `(defn open-call` Correct function starts 189/348; schema declares history render pair; process identity travels on requests. | correct |
| Source initialization rows remain a target | AGENTS.md:551 | current | `src/seon/bootstrap.clj:1` — `(ns seon.bootstrap` Opening generation exists; this does not prove complete retirement of earlier source-population paths. Retain target. | retain |
| System-turn and debug-controls pointers; wake qualification | AGENTS.md:553 | stale | `src/seon/render/web.clj:709` — `(defn- system-action-form` Actual debug button definition is 709; system-turn is 2039; qualification is latest-answering-turn-t. | correct |
| step/work/turn and graph pointers | AGENTS.md:554 | stale | `src/seon/turn.clj:2753` — `(defn next-agent-work` There is no seon.turn/work Var; label next-agent-work. Starts are 4933/2753/4723 and graph-definition 422. | correct |
| Every capability including db writes crosses effect/request! | AGENTS.md:555 | wrong | `src/seon/db.clj:2961` — `(defn transact!` No effect/request! call in db namespace. Name database writer separately; effect/request! owns declared external capability execution. | correct |
| Program graph facts; functions callable independent of rendered context | AGENTS.md:556–557 | current | `src/seon/sci/eval.clj:1035` — `regardless of its :seon.fn/private? attribute. Indexed functions and` Function admission/acquisition, not prompt visibility, controls actual resolution. Program namespaces/schema/test owners exist. | retain |
| Datahike tuple and cardinality-many set semantics | AGENTS.md:560 | current | `reference-code/datahike/src/datahike/index/persistent_set.cljc:1` — `(ns ^:no-doc datahike.index.persistent-set` Tuple schema is in Datahike schema/transaction implementation; many-valued datoms use the set index. | retain |
| Agent namespace nonunique; default temp namespace and steward ref | AGENTS.md:561 | stale | `resources/seon/schemas/seon.agent.edn:79` — `:namespace` Correct pointer to 79; no db.unique declaration on the namespace ref. seon.ns/steward is separately declared. | correct |
| Entity render-pair and block integration targets | AGENTS.md:562;568 | stale | `src/seon/render/block.clj:61` — `(defn surface-id` DOM target exists; entity-wide pair consolidation is not proved by this function, and attribute render metadata remains. Retain these targets; correct repl render pointer. | correct |
| AI/HTML, saved shown text, actual result handles, render profile and elisions | AGENTS.md:563–565;573–574;584 | stale | `src/seon/sci/eval.clj:513` — `(defn bind-result!` Actual object is interned and stored in ::result-objects. shown-result and repl render saved text; print/render-elision-ai emits omitted/path/offset/requery fields. Remove implemented target labels. | correct |
| External wire, namespace page and revisioned package/keyframe/delta | AGENTS.md:566–567;569 | current | `src/seon/render/web.clj:12` — `keyframe assembled from those retained bytes, so a revision gap can` Retained keyframe/delta delivery is implemented by the web owner; route inventory exists. These are transport naming requirements. | retain |
| Compaction wipes evaluation entities at writer | AGENTS.md:572 | stale | `src/seon/turn.clj:2207` — `(defn compact-call` Requires existing idle agent; retracts evaluation entities and retains turns; next system turn derives opening. Remove target label. | correct |
| external-sink/projection-boundary and output-path-report | AGENTS.md:575 | current | `src/seon/fn.clj:1054` — `(defn output-path-report` Function traverses program call facts; attributes are declared in seon.fn.edn. | retain |
| Root maintenance and my.branch remain targets | AGENTS.md:576–577 | stale | `docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md:7` — `# Scheduler mining and root maintenance design — 2026-08-04` Design documents exist; no src/my/branch.clj exists. Keep target; replace live branch/history “verbs” with functions. | correct |
| Evaluation entity rename/identity target | AGENTS.md:579 | stale | `src/seon/id.clj:55` — `(defn evaluation` ID derivation exists at 55; durable identity remains :seon.cluster.eval/id. Retain target for rename. | correct |
| History of-agent ordering and saved render | AGENTS.md:580 | stale | `src/seon/render/walk.clj:875` — `(defn history` Calls eval/of-agent and renders saved entities; requested reply turn is excluded, while default includes all. Correct text pointer, remove implemented target label. | correct |
| doc and dir return public program data | AGENTS.md:581 | stale | `src/seon/sci/eval.clj:1194` — `(defn directory-value` Returns symbols, arglists, summary and contracts; documentation-value supplies full doc. Remove target label. | correct |
| SHA-256 identity, 12 hex default, event arity, evaluation arities, result handle | AGENTS.md:582 | stale | `src/seon/id.clj:29` — `(defn id` digest delegates to id; evaluation has 2/3 arities. next-id uses branch/agent/count. Pointer corrections listed below; no collision-freedom claim added. | correct |
| Read-evidence plans/revisions and freshness pointers | AGENTS.md:583 | stale | `src/seon/db.clj:682` — `(defn read-evidence` Starts at 682; read-evidence-current? at 849, changes at 803. Generated and agent reads pass through system-plan. | correct |
| Per-call contract-fitting render selection | AGENTS.md:585 | stale | `src/seon/render.clj:615` — `(defn- compatible-selection-candidate?` Replace live prose “render producer” with render function; preserve dependency identifiers. | correct |
| Operator status, default target, JVM explicit custody, SCI mutable context | AGENTS.md:596–607 | wrong | `script/seon/dev/mcp.clj:757` — `(defn- execute-runtime-status` Selects one cluster and reports health; it does not list all live clusters. JVM probe returned a real default connection and DB. | correct |
| Read complete REPL envelope; direct owner probe; source/library ledger; explicit verification boundary | AGENTS.md:608–630 | current | Workflow requirements. One read-only JVM expression was run; no SCI state mutation, reload, or adoption was performed by this audit. | retain |
| Comment grammar, skills, roadmap/working-edge and architecture/issue document authority | AGENTS.md:641–658;932–943 | current | Tracked paths resolve. Data-oriented and REPL skills were read in full; AGENTS.md was read end to end. Existing documentation roles remain instructions, not implementation claims. | retain |
| Nearly every historical red was a fixture defect | AGENTS.md:665–666 | wrong | No denominator or identified tally is supplied here; remove the unverifiable aggregate and keep the fixture instructions. | delete |
| Canonical with-database, extra-schema, fixed profiles and event backstop | AGENTS.md:668–688 | current | `test/seon/test_support.clj:605` — `(defn with-database` Installs canonical population; extra-schema is synthetic only. event-backstop-seconds is declared. Other bullets prescribe fixture ownership and input requirements. | retain |
| Preserve instrumentation; closeable resource release | AGENTS.md:690–699 | current | `test/seon/test_support.clj:636` — `(defn preserving-instrumentation-state` Restores roots/registry; closeable adapts release functions for with-open. Runner drift detection remains independent. | retain |
| Testing skill exists | AGENTS.md:703 | current | Tracked .agents/skills/clojure-testing/SKILL.md; path-only documentation audit does not execute its test workflow because assignment forbids test JVMs. | retain |
| Runner admission slots and durable gate evidence omitted | AGENTS.md:708–737 (addition) | stale | `bin/_test-slot:14` — `test_slot_count=${SEON_TEST_SLOTS:-2}` Default 2, wait bound 1800 s; source-root/tmp/test-slots is per checkout. bin/test acquires after snapshot before JVM; fast shares mechanism. bin/test defaults durable results to current-src; runner recording-failure fails finalization. | correct |
| Hook matchers, lint, publication and shell-write follow-up | AGENTS.md:747–762 | stale | `.claude/settings.json:5` — `"matcher": "^(apply_patch\|Edit\|Write)$",` Pre/PostToolUse match apply_patch/Edit/Write. Shell writes bypass these; explicit --dev default is needed for adoption, not publication alone. | correct |
| Five stale-cache blocked files on 2026-09-08 | AGENTS.md:749–751 | current | Dated incident also recorded in the roadmap. Delete duplicated incident count from evergreen instructions. | delete |
| clj-kondo dependency refresh command and recursive dot-classpath warning | AGENTS.md:752–755 | current | `deps.edn:135` — `{:extra-paths ["test" "script" "."]` Pinned clj-kondo process-dir uses file-seq at 337; --dependencies --skip-lint --copy-configs flags exist in the dependency CLI. | retain |
| Default command targets, process identity stop semantics, acme wrapper | AGENTS.md:779–785 | current | `bin/acme:7` — `ACME_CLUSTER_NAME="acme"` Operator checks recorded pid/start identity before stop. CLI usage supports optional names/force/root. | retain |
| Default hook keys, quiet window, failure log and current source marker | AGENTS.md:787–802 | current | `.claude/seon-hook.edn:27` — `:quiet-seconds 5` Root/cluster pointer corrected to 25; source/current reads publication facts. Publication failure path exists in hook implementation. | retain |
| A running JVM cannot acquire changes to its adoption path without restarting | AGENTS.md:800–801 | wrong | A blanket impossibility is not supported: loaded definitions can be re-evaluated. Delete; the audit does not restart or reload anything. | delete |
| Verbatim lane-copy claim, reset advice and footprint status command | AGENTS.md:804–831 | stale | Rules are an updated workflow; never reset default from a lane. Descriptor-only bin/seon status says footprint not scanned; --verbose is required. Disposable-root cleanup remains an instruction. | correct |
| Re-derive operator status; no recursive symlink following | AGENTS.md:833–839 | current | Owner operational requirements. No cleanup of foreign roots or sessions was attempted. | retain |
| Shipped DeepSeek and all AI dials under :seon.config.ai/* | AGENTS.md:841–845 | wrong | `config/default.edn:365` — `:seon.config.ai/model "deepseek-flash"` Backup and retry keys use separate namespaces. Replace the overly narrow wildcard with schema-declared config facts. Credentials remain variable names. | correct |
| Piping lane output reduces live panel to exactly one line | AGENTS.md:864–865 | wrong | No invariant about arbitrary filters establishes this number. Delete unverified UI assertion; retain bare invocation requirement. | delete |
| Named-path lane ownership and path-limited commits | AGENTS.md:880–900 | current | Explicit workflow requirements. Other lanes remained untouched; this audit commits only its two deliverables. | retain |
| Orchestrator sweep manual at line 309 | AGENTS.md:903–909 | current | `docs/TRANSFER_PROMPT.md:309` — `## The orchestrator's sweep (owner, 2026-09-08)` Line 309 still names the section; this bounded assignment does not perform the cross-lane sweep. | retain |
| Issue lifecycle frontmatter and bin/issues-index --check | AGENTS.md:911–921 | current | `docs/seon/issues/README.md:10` — `a single severity vocab, an archive/ for closed notes, and one ranked owner` README declares lifecycle/severity; executable exists and accepts --check. No issue-index edits. | retain |
| Owner report format and final pointer list | AGENTS.md:923–943 | current | Normative reporting instructions; every linked target is checked in the reference inventory below. | retain |
| Konserve GC | AGENTS.md:94 | current | `reference-code/konserve/src/konserve/gc.cljc:8` — `(defn sweep!` | retain |
| Datahike branches | AGENTS.md:94 | current | `reference-code/datahike/src/datahike/versioning.cljc:13` — `write-pending-kvs! branch-heads-as-commits]]` | retain |
| Datahike serial writer | AGENTS.md:161 | current | `reference-code/datahike/src/datahike/writer.cljc:14` — `(defn chan? [x]` | retain |
| core.async workload dispatch | AGENTS.md:179;548 | current | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:96` — `:mixed   (make-ctp-named :mixed)))` | retain |
| SCI interrupt contract | AGENTS.md:542–544 | current | `reference-code/sci/doc/interrupt.md:1` — `# Bounding execution with :interrupt-fn` | retain |
| SCI uncatchable interrupt | AGENTS.md:543 | current | `reference-code/sci/src/sci/interrupt.cljc:32` — `(defn interrupt!` | retain |
| Flow SPI proc vocabulary | AGENTS.md:558 | current | `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:14` — `creating new types of Processes that are not possible to create` | retain |
| Sliding buffer | AGENTS.md:559 | current | `reference-code/core.async/src/main/clojure/clojure/core/async.clj:103` — `(defn sliding-buffer` | retain |
| Clojure contains? | AGENTS.md:445–451 | current | `reference-code/clojure/src/clj/clojure/core.clj:1502` — `(defn contains?` | retain |

## Reference inventory

| Claim | Location in AGENTS.md at audit basis | Verdict | Evidence | Action |
|---|---|---|---|---|
| Reference `docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md` | AGENTS.md:15,221,291,538,551,553,554,562,563,565,568,570,572,573,578,579,580,581,583,584 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md` resolves (1352 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/prds/sci-execution-runtime/research/class-root-cause-synthesis-2026-08-29.md` | AGENTS.md:116 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/prds/sci-execution-runtime/research/class-root-cause-synthesis-2026-08-29.md` resolves (93 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/datahike/src/datahike/writer.cljc` | AGENTS.md:162 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/datahike/src/datahike/writer.cljc` resolves (454 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj` | AGENTS.md:181 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj` resolves (123 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `resources/seon/schemas/seon.env.edn` | AGENTS.md:185,528 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:resources/seon/schemas/seon.env.edn` resolves (92 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `src/seon/sci/eval.clj:1821` | AGENTS.md:228 | stale | `src/seon/sci/eval.clj:1709` — `(defn fork-for-turn` | correct |
| Reference `src/seon/turn.clj:2033` | AGENTS.md:238,553 | stale | `src/seon/turn.clj:2039` — `(defn system-turn` | correct |
| Reference `docs/TRANSFER_PROMPT.md` | AGENTS.md:249,932 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/TRANSFER_PROMPT.md` resolves (334 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/datahike/src/datahike/versioning.cljc` | AGENTS.md:271 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/datahike/src/datahike/versioning.cljc` resolves (758 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md` | AGENTS.md:284 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md` resolves (681 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/sci/doc/interrupt.md` | AGENTS.md:336,542,544 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/sci/doc/interrupt.md` resolves (94 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/datahike/src/datahike/api/specification.cljc` | AGENTS.md:468 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/datahike/src/datahike/api/specification.cljc` resolves (1280 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `src/seon/db.clj` | AGENTS.md:469 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/db.clj` resolves (3002 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/sci/src/sci/core.cljc` | AGENTS.md:529,547 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/sci/src/sci/core.cljc` resolves (939 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/seon/architecture/ui.md` | AGENTS.md:530 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/seon/architecture/ui.md` resolves (178 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `resources/seon/schemas/seon.agent.edn:1` | AGENTS.md:538 | stale | `resources/seon/schemas/seon.agent.edn:151` — `:plan [:and {:seon.db/component true` | correct |
| Reference `src/my/plan.clj` | AGENTS.md:538 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/my/plan.clj` resolves (173 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `deps.edn` | AGENTS.md:540 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:deps.edn` resolves (153 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `src/seon/sci/eval.clj` | AGENTS.md:542 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/sci/eval.clj` resolves (2651 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/sci/src/sci/interrupt.cljc` | AGENTS.md:543 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/sci/src/sci/interrupt.cljc` resolves (315 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/core.async/.../impl/dispatch.clj` | AGENTS.md:548 | stale | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj` resolves (123 lines at the audit basis). Meaning checked in the claim table. | correct |
| Reference `src/seon/turn.clj:194` | AGENTS.md:549 | stale | `src/seon/turn.clj:189` — `(defn open?` | correct |
| Reference `src/seon/turn.clj:378` | AGENTS.md:549 | stale | `src/seon/turn.clj:348` — `(defn open-call` | correct |
| Reference `resources/seon/schemas/seon.turn.edn:1` | AGENTS.md:549 | current | `resources/seon/schemas/seon.turn.edn:1` — `#:seon.turn{:write? [:and {:seon.wake/context-inert true} :boolean],` | retain |
| Reference `src/seon/bootstrap.clj` | AGENTS.md:551 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/bootstrap.clj` resolves (871 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `src/seon/cluster/process.clj` | AGENTS.md:552 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/cluster/process.clj` resolves (51 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `src/seon/render/web.clj:2465` | AGENTS.md:553 | stale | `src/seon/render/web.clj:709` — `(defn- system-action-form` | correct |
| Reference `src/seon/turn.clj:4814` | AGENTS.md:554 | stale | `src/seon/turn.clj:4933` — `(defn step` | correct |
| Reference `src/seon/turn.clj:2649` | AGENTS.md:554 | stale | `src/seon/turn.clj:2753` — `(defn next-agent-work` | correct |
| Reference `src/seon/turn.clj:4619` | AGENTS.md:554 | stale | `src/seon/turn.clj:4723` — `(defn turn` | correct |
| Reference `src/seon/cluster/agent.clj:396` | AGENTS.md:554 | stale | `src/seon/cluster/agent.clj:422` — `(defn graph-definition` | correct |
| Reference `reference-code/core.async/.../flow/spi.clj` | AGENTS.md:558 | stale | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165` — `(defn process` | correct |
| Reference `reference-code/datahike/src/datahike/index/persistent_set.cljc` | AGENTS.md:560 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:reference-code/datahike/src/datahike/index/persistent_set.cljc` resolves (608 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `resources/seon/schemas/seon.agent.edn:87` | AGENTS.md:561 | stale | `resources/seon/schemas/seon.agent.edn:79` — `:namespace` | correct |
| Reference `src/seon/repl.clj:315` | AGENTS.md:562 | stale | `src/seon/repl.clj:387` — `(defn render-ai` | correct |
| Reference `src/seon/repl.clj:246` | AGENTS.md:563,580 | stale | `src/seon/repl.clj:213` — `(defn text` | correct |
| Reference `src/seon/render/hiccup.clj` | AGENTS.md:564 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/render/hiccup.clj` resolves (513 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/sci/src/sci/core.cljc:260` | AGENTS.md:565 | current | `reference-code/sci/src/sci/core.cljc:260` — `(defn intern` | retain |
| Reference `src/seon/render/route.clj` | AGENTS.md:567 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/render/route.clj` resolves (58 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `src/seon/render/block.clj:61` | AGENTS.md:568 | current | `src/seon/render/block.clj:61` — `(defn surface-id` | retain |
| Reference `reference-code/sci/src/sci/core.cljc:330` | AGENTS.md:570 | stale | `reference-code/sci/src/sci/core.cljc:331` — `(defn init` | correct |
| Reference `resources/seon/schemas/seon.render.profile.edn` | AGENTS.md:573 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:resources/seon/schemas/seon.render.profile.edn` resolves (21 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `resources/seon/schemas/seon.print.edn` | AGENTS.md:574 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:resources/seon/schemas/seon.print.edn` resolves (395 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `src/seon/print.cljc` | AGENTS.md:574 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/print.cljc` resolves (1319 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `resources/seon/schemas/seon.fn.edn` | AGENTS.md:575 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:resources/seon/schemas/seon.fn.edn` resolves (108 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `src/seon/fn.clj` | AGENTS.md:575 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/fn.clj` resolves (2049 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md` | AGENTS.md:576 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/prds/sci-execution-runtime/research/scheduler-mining-and-gc-design-2026-08-04.md` resolves (462 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md` | AGENTS.md:577 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md` resolves (175 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `reference-code/sci/src/sci/core.cljc:345` | AGENTS.md:578 | current | `reference-code/sci/src/sci/core.cljc:345` — `(defn fork` | retain |
| Reference `src/seon/id.clj:50` | AGENTS.md:579 | stale | `src/seon/id.clj:55` — `(defn evaluation` | correct |
| Reference `src/seon/id.clj:1` | AGENTS.md:582 | current | `src/seon/id.clj:1` — `(ns seon.id` | retain |
| Reference `src/seon/turn.clj:483` | AGENTS.md:582 | stale | `src/seon/turn.clj:452` — `(defn next-id` | correct |
| Reference `src/seon/sci/admit.clj:614` | AGENTS.md:582 | current | `src/seon/sci/admit.clj:614` — `(defn result-handle` | retain |
| Reference `src/seon/db.clj:468` | AGENTS.md:583 | stale | `src/seon/db.clj:682` — `(defn read-evidence` | correct |
| Reference `src/seon/render.clj` | AGENTS.md:585 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:src/seon/render.clj` resolves (1669 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `.claude/seon-hook.edn:23–26` | AGENTS.md:597,788 | stale | `.claude/seon-hook.edn:25` — `:root "."` | correct |
| Reference `docs/prds/context-generation/plan/README.md` | AGENTS.md:654,937 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/prds/context-generation/plan/README.md` resolves (32 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/prds/context-generation/plan/unsettled.md` | AGENTS.md:655 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/prds/context-generation/plan/unsettled.md` resolves (1465 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `deps.edn:135` | AGENTS.md:755 | current | `deps.edn:135` — `{:extra-paths ["test" "script" "."]` | retain |
| Reference `reference-code/clj-kondo/src/clj_kondo/impl/core.clj:337` | AGENTS.md:755 | current | `reference-code/clj-kondo/src/clj_kondo/impl/core.clj:337` — `files (file-seq dir)` | retain |
| Reference `.claude/seon-hook.edn:27` | AGENTS.md:795 | current | `.claude/seon-hook.edn:27` — `:quiet-seconds 5` | retain |
| Reference `docs/seon/reference/llm-adapters.md` | AGENTS.md:845 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/seon/reference/llm-adapters.md` resolves (183 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/seon/reference/driving-codex-agents.md` | AGENTS.md:878 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/seon/reference/driving-codex-agents.md` resolves (104 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/TRANSFER_PROMPT.md:309` | AGENTS.md:907 | current | `docs/TRANSFER_PROMPT.md:309` — `## The orchestrator's sweep (owner, 2026-09-08)` | retain |
| Reference `docs/seon/issues/README.md` | AGENTS.md:917,941 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/seon/issues/README.md` resolves (166 lines at the audit basis). Meaning checked in the claim table. | retain |
| Reference `docs/seon/architecture/architecture.md` | AGENTS.md:935 | current | `git show be0c498302d805be130fb02b934dbd866dd44b29:docs/seon/architecture/architecture.md` resolves (189 lines at the audit basis). Meaning checked in the claim table. | retain |
| SCI shorthand :345/:260 | AGENTS.md:570;578 | current | Pinned SCI core.cljc:345 defines fork; :260 defines intern. Expand shorthand to full file:line references. | correct |
| Read-evidence shorthand :561 | AGENTS.md:583 | stale | Old line lies inside query capture. `src/seon/db.clj:849` defines read-evidence-current?. | correct |

## Final documentation verification

Rechecked all 54 distinct numeric file:line pointers (including config/default.edn)
against committed HEAD `caddf111be08955f3f3d3476366c28cc7f3b9731` and the dependency gitlinks it names.
Every pointer exists and names the intended form, metadata declaration,
configuration entry or section. The relevant source owners did not change
between the audit basis and that final HEAD. CLAUDE.md remains a symlink and
reads exactly AGENTS.md's bytes. `git diff --check` is clean.

Corrected AGENTS.md: 67617 UTF-8 bytes; SHA-256
`02d766ac6961603bb2858d1d7e8b1561395dc1a31bbe34db0e4de4d8fe5532a6`.

The owner-named commits are present in the history: 5ffc491ae (contract
freshness/adoption retry), 9dd65ec5e (SCI mode spelling), d5e5b870e and
f68b79e01 (carried database projection), 4de9e95f7 (runner slots), and
7f484d4bb (durable test evidence). Their current owning forms, rather than
commit subjects alone, ground the corrections above.

Table counts can be reproduced without loading Clojure:

```python
from pathlib import Path
from collections import Counter
import re
text = Path("docs/prds/context-generation/research/agents-md-audit-2026-09-15.md").read_text()
rows = [line for line in text.splitlines() if line.startswith("| ")
        and re.search(r"\| (current|stale|wrong) \|", line)]
print(Counter(re.search(r"\| (current|stale|wrong) \|", line)[1] for line in rows))
print("deleted", sum(line.endswith("| delete |") for line in rows))
```

No test result is claimed. The pre-commit hook only validates the configured
author email; it does not launch a JVM. Audit-only generation scripts under
tmp/agents-md-audit are removed before reporting. The persistent evidence is
this table, the exact source basis, the JVM form and observation, and the
reproduction commands above.
