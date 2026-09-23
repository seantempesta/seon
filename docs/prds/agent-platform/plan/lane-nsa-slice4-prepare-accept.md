---
type: plan
status: implementation specification; APIs below are proposed
created: 2026-09-23
lane: namespace-agent-slice-4
---

# Compose preparation, gate and named accept

Implement [first-loop slice 4](lane-namespace-agents-first-loop.md), [D1 §2c](lane-d1-isolation-merge-writeback.md#2c-explicit-merge-through-the-tested-head) and [lifecycle rows 11–12](lane-realities-one-lifecycle.md). README §4/§7 governs order. This slice accepts database definitions into an ordinary cluster; default remains the files. No export, automatic issue settlement, new acceptance entity, runner, cache or lifecycle.

Grounding: committed HEAD `b67a08a1019d661e544000a16224c2b68f1902a6`, read with `git show`, excluding concurrent source edits. Slice 2 is `816092afb`; slice 3 is `a118b34b4`; save composition is `c32a2c672`/`20a641b9b`. Datahike gitlink is `131ca6360d09762aa509a72712005fd396264574`. Anchors below refer to this HEAD, not a claim of live adoption.

## Requests and declared contracts

Owner: `seon.cluster.source`, `src/seon/cluster/source.clj`. Add these declarations to `resources/seon/schemas/seon.cluster.edn` in the implementation slice; they are transient request/value schemas, not stored entity schemas. All map entries are open. Existing referenced schemas remain their owners' declarations.

```clojure
:seon.cluster/merge-base :seon.source/commit-id
:seon.cluster/merge-candidate :seon.source/commit-id
:seon.cluster/merge-head :seon.source/commit-id
:seon.cluster/merge-tested :seon.source/commit-id
:seon.cluster/merge-evidence :seon.source/commit-id
:seon.cluster/merge-source
[:and :seon.agent/context-source [:map [:seon.store/store :seon.store/store]]]
:seon.cluster/merge-branch :seon.store/branch
:seon.cluster/merge-reason
[:enum :stale-head :conflict :missing-coverage :red-or-unfinished
 :tested-program-changed :proposal-mismatch :unsupported-change
 :evidence-unavailable :owner-request-required]
:seon.cluster/merge-refusal
[:and :seon.error/base [:map
 [:seon.cluster/merge-reason :seon.cluster/merge-reason]
 [:seon.error/offending :seon.schema/value]]]
:seon.cluster/prepare-merge-request
[:map [:seon.cluster/merge-source :seon.cluster/merge-source]
 [:seon.cluster/merge-base :seon.cluster/merge-base]
 [:seon.cluster/merge-candidate :seon.cluster/merge-candidate]
 [:seon.issue/id :seon.issue/id]
 [:seon.program/max-datoms :seon.program/max-datoms]
 [:seon.test/check-time-limit-ms :seon.test/check-time-limit-ms]]
:seon.cluster/merge-proposal
[:and :seon.cluster/prepare-merge-request [:map
 [:seon.cluster/merge-head :seon.cluster/merge-head]
 [:seon.cluster/merge-branch :seon.cluster/merge-branch]
 [:seon.cluster/merge-tested :seon.cluster/merge-tested]
 [:seon.cluster/merge-evidence :seon.cluster/merge-evidence]
 [:seon.test.run/id :seon.test.run/id]]]
:seon.cluster/merge-required [:set {:min 1} :seon.test/sym]
:seon.cluster/merge-obligations
[:=> [:cat :seon.agent/execution-handle :seon.reconcile/adopt-identities]
 [:or :seon.cluster/merge-required :seon.cluster/merge-refusal]]
:seon.cluster/merge-gate-options
[:map [:seon.cluster/merge-head :seon.cluster/merge-head]
 [:seon.cluster/merge-obligations :seon.cluster/merge-obligations]
 [:seon.test/check-time-limit-ms :seon.test/check-time-limit-ms]]
:seon.cluster/merge-accepter [:= :owner]
:seon.cluster/accept-merge-request
[:and :seon.cluster/merge-proposal [:map
 [:seon.cluster/merge-accepter :seon.cluster/merge-accepter]]]
;; prepare-merge! [request]
[:=> [:cat :seon.cluster/prepare-merge-request]
 [:or :seon.cluster/merge-proposal :seon.cluster/merge-refusal]]
;; accept-merge! [request]
[:=> [:cat :seon.cluster/accept-merge-request]
 [:or :seon.db/transaction-report :seon.db/transaction-refused-error
  :seon.cluster/merge-refusal]]
```

`merge-source` carries the destination connection, context and held store. Reject a development destination. B is the candidate's retained acquisition commit, C its immutable captured head, H the destination capture, S the intermediate roster branch, T its pre-run commit, E its post-run evidence commit. UUIDs name commits; branch keywords never substitute for parents. No request accepts tx-data, required-tests, digests, member ids or a green flag as authority. `offending` is deliberately polymorphic diagnostic evidence: retain the complete upstream refusal or the specific identities/commits; unexpected exceptions rethrow their whole cause.

**Smallest authority restriction:** owner-invoked JVM request only, after inspecting the concrete B/C/H/S/T/E/run proposal. `:owner` records the named action; it is not authentication. HEAD has no installed authenticated D1 root-accept request boundary. Do not invent one, claim a keyword authenticates a caller, expose a `my.*` accept wrapper, or let worker completion call accept. A root-agent accept requires that boundary as a separately proven prerequisite. Every function remains callable under the system model; prompt visibility is not authorization.

## Data flow and exact reuse

Let D be candidate-changed identities, R the required test union, K portable changed components, A affected executable closure, L retained parent/history records visited, P program population. Target is O(D + K + A + R + executed bodies), with bounded lineage reads; never three whole-program digest maps.

| Step | Existing seam at HEAD and exact operation | Work and recomputation |
|---|---|---|
| Capture | `source.clj:280` `commit-database`; `db.clj:4562` supplies connection semantics | Materialize B/C/H once; release with `d/release-materialized-db` in finally; O(retained index pages read) |
| Lineage | Datahike `versioning.cljc:467` `parent-commit-ids`; `source.clj:280` materialization | Walk immutable parents of C/H until B, memoizing visited commits within this request; refuse absent B, missing commit or max-datoms visited bound; O(L), never compare unrelated basis-t values |
| Compare | `program.cljc:576` `changed-identities B C opts`; `:447` scoped `digest-map`; `:618` `three-way` | Read B/C/H digests only for D; H-only identities cannot conflict and already live on H. Net equal/reverted identities disappear; O(D) comparison, history reader currently O(retained digest history) |
| Normalize | `fn.clj:3314` `published-index-rows C (vec taken)`; `program.cljc:242` `shapes-in`, `:1333` `exact-replacement-tx-in` | Pull destination current rows with ids, derive shapes once from H, mapcat exact replacement; O(D + K). Never use the one-argument full row reader or replay eids |
| Intermediate | `cluster.clj:2668` `candidate-gate!`; `registry.clj:193` `branch!`; `agent.clj:783` `acquire-context!`; `store.clj:538` `open-branch!` | Fresh S from captured H, write portable delta with `db/transact!`, acquire current S before running; O(pointer/open + D + K + A). Borrow S so release preserves its roster entry |
| Obligations | `fn.clj:1606` positional `gate-sets H changed-fns` and `gate-sets T changed-fns`; C's issue `:seon.issue/tests` → `:seon.test/sym` | Nonempty after-reaching set per function; R = before/after reach ∪ changed tests ∪ issue tests, all positively present on T. Current `:1554` scans P identities; target follows reached edges |
| One gate | `cluster.clj:2641` `reaching-run`; `test.clj:1608` `run` | Named R on acquired S, recording on S's connection, supplied deadline; one request only, valid reuse retained. Capture T before request and E after actual return; selected closures + bodies |
| Recorded proof | `test/runner.clj:1819` `run-results E run-id`; `:1112` `execution-members`; `test.clj:554` `select`; `runner.clj:900` `reach-digests`, `:1024` `program-digest` | R members and their original execution provenance, inputs and termination; no body rerun. Reuse existing revision/commit memo; no digest or green cache beside it |
| Accept | `db.clj:4562` `transact!`, `:4339` dispatch to `d/merge-db!`; Datahike `writing.cljc:883` → `:864` | Prepared exact delta, expected H basis, immutable parents #{H C E}; writer checks H and final validation together, O(D + K + affected validation). The public Seon API is transact!, not a new merge-db! |
| Retain | `agent.clj:750` `release-context!`; `store.clj:571` `release-branch!` | Release after actual exit; C/S remain roster branches through review. No automatic unlink or GC until D1 retained-evidence proof |

HEAD's history scan and gate graph population scan are real limits, not O(D) guarantees. Measure visited history, closure and memory; route any whole-program recomputation to those existing owners. This slice must not hide it with a new cache. Datahike's `writing.cljc:874` checks expected basis before `core/with`; `:890` calls that exact owner and adds parents. Seon's prepared validator/codec stay on that path. A write-bound result can mean outcome unknown: observe terminal outcome, never retry blindly.

## In-place edits and acceptance algorithm

Reuse `candidate-gate!` rather than another branch/run composition. Preserve its five-argument save caller. Add one optional options arity with schema `:seon.cluster/merge-gate-options`: captured H, explicit R-producing callback and request bound; supplying this arity means prepare-only. In prepare-only mode require a fresh generated branch (`seon.id/id`), never reset a retained S, never call advance!. Capture T/E and return the proposal even for a recorded red run so its facts remain inspectable. Exceptions or refusal before admission return merge-refusal naming S when allocated.

Refactor only `cluster.clj:2691–2704`: supplied H fixes branch creation; the callback supplies R after the write and before the single run; `reaching-run :2656–2662` accepts explicit identities/deadline while retaining the save default. Delete the superseded inline expressions at those spans, not the save path. Reuse `:2705` release and retain `:2706–2711` solely for save's advance. Resolve source→cluster/agent/test calls at request time with `requiring-resolve`, preserving the current require graph. Do not call `save-gate!` itself or `adopt-then-test!`: the host-bound adopt-first path cannot certify a merge.

Before the gate, require a present complete `:seon.fn/spec` for each changed callable. Reuse canonical declaration admission and SCI arming, then call `sci.eval/acquire!` (`src/seon/sci/eval.clj:2602`) with S’s context/database; unchanged acquisition returns its retained report. Require empty `:seon.sci.eval/acquisition-refusals` and `:seon.sci.eval/agent-mistakes`, no acquisition-recording-error, and `acquired-program` (`:1139`) reporting T’s program. Acquisition alone can record a refusal and continue, so merely obtaining a handle is insufficient.

Preparation and acceptance share one proposal derivation in source.clj: lineage, scoped comparison, admission restrictions, portable delta and R. Accept recomputes it from the named immutable commits, never trusts returned selections. Compare H→T's net identities/digests against exactly the proposal; an extra/missing/different definition is proposal-mismatch. Read current S once into immutable E-current; require its program digest and external input digest equal T's and the named run's admitted values. Recorder transactions may move basis-t without changing program. C's later movement is irrelevant; only captured C contributes.

Accept reads the named run at retained E and E-current, requires unchanged admission and member identities, matching S branch and T basis/program, empty exclusions, and exact R coverage. `run-results` already checks admission-history membership and completed/terminated facts; make `test.clj:537` `green-members` public without changing its complete contract. Query the run’s members ∪ covered-by ids and require that exact set equal `green-members E-current ids`; it already checks began/ended, pass>0, zero fail/error, termination and absence of member/error. Preserve red result rendering at run-results. Do not substitute latest-results or verified? for the named run.

Use `test/select` read-only on E-current with named R and the actual destination cluster identity inherited on S (resolved there, never a foreign eid). Require zero new members/exclusions and reused symbols exactly R; compare each reused source run/basis with `run-results`' source provenance. This reuses B4's definition/input/loaded-program checks, including covered-by members; supply `:seon.test/loaded-source` from `(source/current held-store)` exactly as run does at `test.clj:1670`. A different later green cannot replace the named evidence. Acquisition and the ordinary runner supply actual armed execution; absent or degraded arming/adoption evidence refuses, never a caller assertion. No second seon.test/run occurs at accept.

Finally regenerate the exact replacement tx against immutable H, then `(db/transact! destination {:tx-data tx :datahike/expected-basis-t (db/basis-t H) :parents #{H-id C-id E-id}})`. The immutable E used above is the lineage parent containing the inspected run. A later S write cannot change that accepted payload. No issue/agent/turn/evaluation facts are copied. Existing run facts remain on S; no settlement of H's issue is claimed.

| Named refusal | Required evidence and effect |
|---|---|
| `:stale-head` | Preflight H mismatch or writer's `:transaction/stale-basis`; preserve full writer diagnostic, no partial program change; prepare anew explicitly |
| `:conflict` | Nonempty three-way conflict set with B/C/H digests; preserve C; no run/accept, no automatic resolution |
| `:missing-coverage` | Name each function with absent/empty current reach, missing issue test, or unknown analysis; no empty green |
| `:red-or-unfinished` | Name run/member, red counts, exclusion, missing completion/termination or recording refusal; H unchanged |
| `:tested-program-changed` | T/run/current-S program or input mismatch; data-only recording is allowed; no naive equal-head-t test |
| `:proposal-mismatch`, `:unsupported-change`, `:evidence-unavailable`, `:owner-request-required` | Name offending identity/commit/boundary; preserve complete upstream refusal and repair branches; absence is never success |

## Budget, regressions and proof

The unrestricted design is **not credibly 100 added src lines**: ancestry, generalized replacement, authorization and evidence composition need more. First implementation restriction: existing non-host-bound function/test replacements only, existing namespace/schema/resolver bindings, one authored issue with existing tests, owner-driven accept, no deletions/additions, no root transport, no settlement/export/unlink, and R ≤ `seon.test/batch-limit`. A larger R refuses unsupported-change; never truncate it. HEAD run batches can have distinct run ids, so generalized multi-run receipts are later work. Enforce restrictions by comparing portable rows' identity, `:seon.fn/ns`, `:seon.test/ns`, `:seon.fn/calls`, `:seon.fn/references`, `:seon.fn/invokes` and the referenced namespace declaration digest across B/C; unknown host binding refuses. Contract/body replacements remain allowed. Empty net delta returns unsupported-change; do not manufacture a successful merge. This removes general creation/deletion/repair and authority machinery, not evidence checks.

Budget that restricted composition at **≤100 added src lines**: shared proposal derivation 35, prepare/accept 45, save-gate adaptation 19, exposing green-members 1; schema ≤65, tests ≤100 separately. These are physical additions, not net or compressed lines. Reuse/deletion spans above are mandatory; stop before exceeding the source budget and report the exact missing owner seam. No deletion of existing comparison/writer/acquisition tests. File holds required before implementation: source.clj, cluster.clj, test.clj, seon.cluster.edn and test/seon/cluster/merge_test.clj; this design lane edits only this document.

One regression per behavior class in `seon.cluster.merge-test`: (1) portable replacement with colliding branch eids, indirect SCI call and exact parents, C changing after capture; (2) conflict/equal-revert/scope refusal; (3) missing coverage/unknown analysis; (4) recorded evidence, including reused members, forged passed?, other-run green, red, unfinished, removed membership and absent arming; (5) stale H including a writer race, zero partial datoms; (6) T mutation versus data-only recording, evidence still readable after handle release. Use canonical with-database/program-fn-row/transacted!, real SCI and armed contracts; no global Var replacement. Existing save-gate regressions must still pass.

After implementation publication/adoption is positively verified, use ONE focused request on default's JVM; the runner's members and fixtures own branches. Exact alternative forms (run one, not both):

```sh
bin/test-check default --ns seon.cluster.merge-test
```
```clojure
(let [h (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))]
  (seon.test/run {:seon.test/execution h
                 :seon.test/recording-connection (:seon.db/connection h)
                 :seon.test/policy :named
                 :seon.test/namespaces #{'seon.cluster.merge-test}
                 :seon.test/check-time-limit-ms 5000}))
```

MCP uses root `/Users/sean/src/seon`, cluster `default`, JVM mode. Record the complete envelope, B/C/H/T/E/run, selected/executed/reused counts, refusal classes, parent/after-state reads, clocks and memory. No scratch root, second JVM, bin/test gate or worktree. A shared-tree load failure uses HEAD plus the doc/source diff from git archive with shared caches linked; it does not authorize a replacement JVM. Every >1 s result names armed spend and proportionality; >10 s needs owner authorization and an issue extension. Current design verification is source/history inspection only: bin/seon status returned a 30,000 ms PREPL-output bound refusal; MCP runtime_status returned health/flow unknown, “Read timed out”, PID 90963. This is degraded access, not a pass or an established cause. No live test, provider run, publication or reset was performed; src/test delta is zero.
