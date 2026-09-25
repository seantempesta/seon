---
type: research
status: independent revision review; revise
created: 2026-09-25
reviewed-base: ef099dccf
reviewed-head: 6dfe778e4
reviewed-sci: e663d9e23b6b3b33f95e78e2417b3b4312ba8006
task: "132fee0467c8"
---

# S1 revision review

**Verdict: REVISE.** The revision repairs several concrete failures, but source position is still not definition provenance, restoration can report success while leaving a refused root installed, and restoration errors are discarded by two ordinary callers. Keep the existing evaluator, analyzer, SCI ownership seam and Malli instrumentation. Repair those boundaries before accepting S1 or building further admission semantics on its guarantee.

The existing flow is read → evaluate → compare held interns → analyze → admit/arm → settle. The principal Clojure approach remains one immutable observation of the actual change, interpreted by the existing row and restoration owners; neither another source scanner nor a second rollback system is needed.

## Scope and evidence

Read AGENTS.md, the [earlier review](repl-s1-diff-review-2026-09-25.md), the [landing](../../prds/agent-platform/landing/lane-impl-repl-m1-2026-09-25.md), and the [living requirements](language-requirements-2026-09-24.md), including rows 1.9–1.15, end to end before reviewing implementation. Applied the data-oriented Clojure and REPL skills. Reviewed the six requested files with `git diff ef099dccf..6dfe778e4`, the SCI change, and relevant callers/dependency implementations. Source citations below are pinned to **6dfe778e4**, SCI citations to **e663d9e2**. `eval.clj`, `reader.cljc`, `turn.clj` and `schema` abbreviate `src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc`, `src/seon/turn.clj` and `resources/seon/schemas/seon.sci.eval.edn`.

All counterexamples below are **source-derived, not executed here**. This read-only assignment authorizes no tests. No runtime evaluation, publication, stop/reset, worktree, other-lane contact or delegation occurred. Source evidence does not certify installation.

Root supplies published default **6ab6c0b5** evidence: entry-gate **66/0**, restoration **16/0**, reader **292/0**, reply **124/0**. Raw restoration and reader outputs are `tmp/orchestrator/verify-1845.out.d/005.txt` and `006.txt`, runs `2fb2a92749dd` and `a8244ed4eea3`; their whole-request durations are **5,098 ms** and **16,444 ms**, distinct from warm evaluation cost. The entry-gate/reply counts are accepted as supplied evidence; those suite outputs are not among the corresponding numbered files inspected here.

`007.txt`, run `e2cab44dc194`, is **not green**: 62 executed, 12 reused, 370 assertions passed, 10 failed, 5 errors, **99,770 ms**. Root reports four red cases new against pre-S1, one its now-fixed helper. This file alone is not a parent/change comparison, so I neither attribute all its reds to S1 nor relabel them foreign/pre-existing. It includes `both-entrances-classify-one-failure-identically` exposing SCI's interrupt marker, a schema-shape mismatch, custody refusal mismatch, fixture/acquisition failures and time overruns. The exact remaining delta and current rerun are acceptance evidence still owed. `008.txt` also records a turn bound failure; `009.txt` records a batched-turn test not started, not a pass. None blocked this static review.

## Disposition of every earlier finding

| Earlier finding | Disposition at this revision | Code and proof boundary |
|---|---|---|
| P0-A: incomplete changed set / form-wide host exemption | **Concrete examples repaired; full guarantee still open.** | `program-definitions` consults the prior intern and root (`eval.clj:484–506`); `program-change` rejects function→data and groups records by position (`:537–560`). Tests cover data/nil, two records and record plus dynamic definition (`test/seon/sci/entry_gate_test.clj:140–152`). The remaining positional exemption is not positive provenance; finding 1 applies. Only `first var-rows` is carried (`eval.clj:4245`), so the landing's record-helper row limitation remains real. |
| P0-B: inherited metadata / lost generation | **Isolation and stamp cases repaired; current-Var resolution incomplete.** | SCI `namespaces.cljc:852–882` copies inherited writable Vars and retains generation; `utils.cljc:362–378` owns the copy. The inherited Seon case is exercised at `entry_gate_test.clj:173–179`; the SCI commit adds a fork/stamp test. A held inherited handle still overwrites newer context state (finding 2). |
| P0-C: same-name / dynamic overwrite provenance | **Duplicate occurrences repaired; principal finding open.** | Grouping and repeated occurrences replace `some` (`eval.clj:536–546`), and declarations are a vector (`:553`, schema `:377`). Different-position dynamic overwrite is refused. An ordinary root write preserving position still passes (finding 1). |
| P0-D: restore candidate instead of recipient | **Successful acceptance path repaired; incomplete failure boundary and installed proof.** | `accept-candidate!` captures recipient restoration and returns it (`eval.clj:4390–4400`); the snapshot includes program/installed/environment state (`:702–716`). `restoration_test.clj:25–52` verifies the recipient, affected caller, snapshot and old contract. It directly invokes restoration, not an actual settlement refusal. A throw during acceptance loses that restoration (finding 4). |
| P1-A: redefine then remove / unmap throw / db-only request | **Named bound-root case repaired; unbound case open.** | Removed owned Vars now receive their captured root/metadata (`eval.clj:621–634`), observed even for unmap (`:4136–4142`); failure uses request db without a connection (`:4042`). `restoration_test.clj:54–66` covers the bound-root helper path. Neither branch restores an originally unbound owned Var (finding 3). |
| P1-B: incomplete unavailable transition | **Planning catch, marker retention and admission propagation repaired; ordinary consumers still incomplete.** | Planning/application share a catch (`eval.clj:677–686`); fault callback is invoked (`:687–692`); admission propagates the unavailable value (`:868–871`); regeneration preserves the marker (`:2824`, `:2877–2879`); entry fences before acquisition (`:4087–4090`). Failure-result and settlement still discard the new return value (finding 5). |
| P1-C: silently dropped closer / accepted-source analysis | **Original closer-loss finding closed in source and focused supplied evidence.** | `reader.cljc:853–863` now drops only blank spans; `one-event` rejects any reader-error event (`eval.clj:920–927`); analysis still uses event source (`:4232`). Tests replace the permissive trailing-closer assertion (`entry_gate_test.clj:162–164`, `reader_test.clj:386–395`). No new nonblank span deletion is introduced by this change. Recovered nested coordinates remain inconsistent with the new admission check (finding 6). |
| P1-D: armed cost and retention | **Armed timing improved; memory/comparable accumulated-state proof incomplete.** | Supplied/landing measurements are discussed below. Every success still carries restoration (`eval.clj:4314`), and every form captures state twice (`:4129–4136`, `:702–703`). Retained memory through batch release is explicitly unmeasured. |
| P2-A: point-free and macro definitions | **Deferred by the owner, not implemented or closed.** | Named refusal remains (`eval.clj:724–731`); analyzer delegation remains `seon.fn/source-rows` (`:764`). The remedy now mentions `defn`, but it still must not imply semantic equivalence for captured point-free functions. Keep the landing's explicit next-slice limitation. |
| P2-B: contracts and error shapes (also present in the earlier review) | **Partly repaired.** | Restoration alternatives are closed maps and `{}` refuses (schema `:332–343`); constructors require nonempty members (`eval.clj:720`, `:736`); repeated declarations survive. `analyzed-rows` and `definition-row` now have contracts (`:758–780`). Generic row `:map` slots remain, and `restoration_test.clj:13–24` introduces an uncontracted private request helper. The path alternative still permits a one-symbol path without `prior`, which the consumer treats as removing a nil intern (`eval.clj:646`), not removing a namespace. Tighten the actual operation alternatives while fixing finding 3. |

## Required revisions and distinguishing cases

### 1. P0 — Root writes preserve the supposed provenance; substring search also misidentifies source

At `eval.clj:545–546`, exactly one row plus matching `:line`/`:column` suffices to admit the final root. SCI `namespaces.cljc:814–840` implements `alter-var-root` through `utils/bind-root!`; `utils.cljc:380–385` and `lang.cljc:97–103` change the root without updating those coordinates. Consider one ordinary form:

```clojure
(do
  (defn f "Identity." {:malli/schema [:=> [:cat :int] :int]} [x] x)
  (alter-var-root #'f (constantly inc)))
```

There is one analyzed `f`, one changed Var, and its coordinates still name that `defn`. The row describes identity; the executing root increments. `definition-row` merges contract metadata and digests the analyzed body (`eval.clj:788–811`); arming then wraps the changed root (`:865–866`). A valid contract proves neither source nor behavior equality. No forged metadata or dynamic `eval` is needed. The same problem weakens the positional host-helper exemption (`:547`).

Separately, `source-position` uses the **first substring occurrence** (`eval.clj:508–517`). A preceding comment containing the exact declaration bytes makes an otherwise supported nested declaration fail provenance. For example, place `;(defn f "D." {:malli/schema [:=> [:cat :int] :int]} [x] x)` before the identical executable declaration inside `do`. clj-kondo ignores the comment; `str/index-of` does not. Thus the new mechanism can both admit the wrong root and refuse the right one.

**Smallest correction:** establish provenance at SCI's actual definition/root-write owner and distinguish subsequent writes from the one admitted definition; for this bounded slice, refuse additional unsupported root writes. Carry the analyzer's actual source span rather than rediscovering it by text search. That information exists at `src/seon/fn.clj:694,717,757`; `source-rows` currently removes it at `:1503`, so retain a transient analysis coordinate through that owner, adjusted for its prelude. Do not add a root hash, macro re-expansion, second reader or symbol exception list. Prove the form above, a comment containing identical bytes, and row/source/behavior equality after reacquisition.

### 2. P1 — Metadata copy-on-write can overwrite a newer context-local Var

`reference-code/sci/src/sci/impl/utils.cljc:370–377` looks only at the supplied Var's generation. An inherited handle is copied and installed every time; it never checks the existing context-local binding. In an SCI fork inheriting writable `shared`:

```clojure
(let [v #'shared]
  (alter-meta! v assoc :a 1)
  (alter-meta! v assoc :b 2)
  (meta (resolve 'shared)))
```

Both writes receive the same inherited object. The second copies its original metadata, erasing `:a` from the resolved binding. More seriously, an `alter-var-root` through `v` followed by an `alter-meta!` through the same `v` can reinstall the inherited root. Parent/sibling isolation alone does not prove correct sequential writes inside the child. These are dependency-level counterexamples; Seon's admission may refuse a standalone metadata mutation, which does not repair the dependency semantics used during evaluation.

**Smallest correction:** resolve the current owned counterpart at the same namespace/name in the existing ownership seam before mutation, and have metadata/root updates compute from that counterpart. Define behavior for detached/replaced handles explicitly at that seam. Add consecutive-write and root-then-metadata cases to the existing SCI regression. No second Var registry is needed. This is the context-local-resolution part of earlier P0-B that remains unfulfilled.

### 3. P0 — Restoration leaves an originally unbound Var bound to refused code

`turn-interns` represents an unbound root with `absent-intern` (`eval.clj:443–445`). Restoration explicitly excludes that state from root restoration (`:616–619`, also `:632`). For a generation-owned Var, the fallback reinserts the **same already-mutated Var** and declares success.

Distinguishing sequence: admit `(declare pending)` as private state, then evaluate `(defn pending "Missing contract." [x] x)`. The second form is refused for its missing contract (`eval.clj:855–861`), but SCI reuses the generation-owned Var (`reference-code/sci/src/sci/impl/evaluator.cljc:35–49`). The restoration step puts that object back without unbinding it. The subsequent failure selector protects stored rows only (`eval.clj:4042–4043`); it cannot repair this row-less admission refusal. `pending` can remain callable despite refusal.

**Smallest correction:** represent the captured bound/unbound state explicitly in the existing restoration value and apply SCI's existing `vars/unbind` (`reference-code/sci/src/sci/lang.cljc:119–125`) for an unbound prior root, restoring metadata too. Preserve Var identity; do not intern a replacement or bind the sentinel as a user root. Cover both redefinition and redefinition-then-unmap from an unbound owned Var. This also resolves the unbound-state omission identified in earlier P2-B.

### 4. P0 — Recipient restoration is returned only after acceptance has finished mutating

`accept-candidate!` captures a recipient closure, transfers roots, then installs the function (`eval.clj:4394–4397`). It has no catch/finally applying that closure if either operation throws. Installation changes cache and installed-function state before arming and projection advancement (`:4344–4352`). A failure in arming/config acquisition or projection advancement therefore leaves a partially installed recipient, and no returned evaluation carries its restoration.

The ordinary caller invokes acceptance after candidate evaluation has returned (`turn.clj:3291–3294`, `:4734–4739`), outside `evaluate`'s restoration try. The successful manual restore regression does not exercise this edge.

**Smallest correction:** put transfer and installation inside the recipient's existing restoration failure boundary. On any throw, restore the recipient or immediately surface its unavailable error with both causes. Continue returning that same closure on success for settlement. Prove a failure after transfer and before completed installation with the real supplied-world owner, plus an ordinary batch refusal that checks root, affected caller, contract, installed set, projection and durable row. Do not add another candidate/undo lifecycle.

### 5. P1 — The new restoration error value is silently ignored on failure and settlement

The restore contract now returns nil **or an unavailable error** (schema `:344–347`). Admission handles it correctly (`eval.clj:869–870`). Two first-party consumers do not:

- `failure-result` calls selective restoration and discards its result (`eval.clj:4042–4043`), then constructs the outward value exclusively from the original throwable (`:4052–4058`).
- `turn/restore-evaluations!` uses `doseq`, discards every return and promises nil (`turn.clj:4828–4838`); settlement reports its original refusal (`:4936–4942`, `:4982–4984`).

Thus a restoration planning/application failure can be stored on the context and sent to its callback while the immediate caller is told only about the original error. The fault callback is optional (`eval.clj:689`), and the regression supplies a collecting function (`restoration_test.clj:74–94`), not the durable status/fault owner. It proves invocation, not storage, panic behavior or branch/status visibility. Admission's exception cause also is not supplied to `restore-or-unavailable!` when that helper records its fault, so the recorded restoration error is not by itself evidence of both failures.

**Smallest correction:** propagate restoration errors as ordinary values through both existing consumers, preserving the original refusal and restoration cause in the established error representation. Continue required cleanup without hiding a failed step. Require the existing fault owner where the execution contract requires durable core-fault recording; no new status registry. Prove immediate reply, next-form fence, regeneration, durable fault/status visibility, and a failure in the real recording callback. Update every caller with the changed return contract in the same cut.

### 6. P1 — Reader recovery shifts the outer form but leaves nested declaration positions local

`reader/shift-event` adjusts event offsets and only the outer form's metadata (`reader.cljc:680–700`). Recovery reparses a suffix, then applies that shallow shift (`:866–877`). A nested `defn` still has suffix-relative metadata, while the revision computes its analyzed position from the shifted event start (`eval.clj:4233–4234`, `:513–517`).

Distinguishing stream:

```clojure
)
(do
  (defn recovered "D." {:malli/schema [:=> [:cat :int] :int]} [x] x))
```

Recovery retains an error then a form. The nested declaration is line 2 in the reparsed suffix and line 3 in the original stream. SCI receives the former; admission expects the latter. The correctly recovered definition can be refused as unindexed. This is an existing coordinate defect made material by the new position gate, not evidence that the closer fix itself drops source spans.

**Smallest correction:** carry one consistent reader coordinate basis through recovery and admission, including nested executable forms, using the existing reader's facts. Avoid another parse or textual scan. Prove error/form ordering, exact source slices, nonoverlapping span coverage, nested metadata coordinates and successful admission on the ordinary event-consuming path. Source inspection found no additional nonblank drop in the revised blank-span filter; that is narrower than claiming all recovery is lossless.

## Contracts, language scope and cost

The revision keeps values as values through the added change/restoration maps and uses flat named errors. Retain those choices. The immediate-return failures above violate that design by ignoring values, not by lacking another printer. The existing instrumentation owner calls Malli `m/-instrument` (`src/seon/instrument.clj:706–714`); at pinned Malli **befcd302**, `core.cljc:3146–3152` compiles through `schema`, and `:2219–2231` validates inputs before calling the body and outputs afterwards. Keep this dependency composition; source contracts alone do not prove all new helpers are armed.

Requirements 1.9 and 1.10 require truthful handles and REPL-authored rows; a row naming the wrong body breaks both. Requirements 1.14–1.15 favor parser-owned spans and canonical row schemas, rather than substring inference and new `:map` stage boundaries (`eval.clj:529–531,760–762,778–780`; schema `:349–350,369`). Rows 1.11–1.13 describe ongoing anonymous-function, derived-contract and default-argument work; this review does not demand those unrelated slices as S1 additions or certify them from S1 tests. Qualified definitions, general macros, point-free classification and the full deletion API remain declared later work. A safe temporary refusal is acceptable; an accepted mismatched row is not.

P1-D's armed fresh-branch measurements improve: contracted `defn` **22.4 → 16.3 ms** (about **27% faster**), arithmetic **2.41 → 1.50 ms** (about **38% faster**). The landing records current allocations **33.1 MB** and **2.3 MB** per form; after 200 private defs, **18.8 ms / 35.1 MB** and **2.57 ms / 4.3 MB**. Parent allocation and parent accumulated-private-state measurements are absent. Comparing the latter current case against the parent's fresh case is not a controlled regression comparison. Retained memory through batch release remains unmeasured. No new measurement was performed here.

Cost remains proportional to namespaces plus entries in maps differing from the acquired base and owned interns, followed by analysis on a definition. New substring position lookup repeats scans of source per row/helper/group (`eval.clj:533–558`), adding roughly O(number of considered rows × source length) work. Every ordinary success retains a closure over namespaces, intern values and program/environment state (`:702–716`, `:4314`), including reads with no initial change. Do not blindly drop such closures: under today's late-diff semantics they can participate in unwinding later mutations. First fix the capture boundary and then retain only actual required restoration data, measuring release.

## Size and deletions

Measured from the requested commit range, not the landing's stale original-cut totals:

| Material | Added | Removed | Net |
|---|---:|---:|---:|
| Seon Clojure source (eval + reader) | 277 | 147 | +130 |
| Schema | 30 | 19 | +11 |
| Three Seon test files | 135 | 10 | +125 |
| Requested six-file revision | **442** | **176** | **+266** |
| SCI two production files, e663d9e2 | 36 | 9 | +27 |
| SCI regression in that commit, separately | 24 | 0 | +24 |

Actual retired blocks: the old `definition-rows` form is **58 lines** (`ef099dccf:eval.clj:628–685`), replaced by analysis/final-row owners; `closer-only?` is **6 lines** (`ef099dccf:reader.cljc:816–821`); the evaluator's inline restore binding is **8 lines** (`ef099dccf:eval.clj:4002–4009`), replaced by `context-restoration`. These are gross retired blocks, not net savings, and overlap the diff's removal totals.

This revision and its corrections let the owner delete:

- **10 lines**, `source-position` (`eval.clj:508–517`), once the existing analyzer carries exact coordinates. This removes an incorrect duplicate derivation.
- **2 duplicate capture lines**, `before-namespaces` / `before-owned` (`eval.clj:4129–4130`), once admission and restoration consume one capture; the same reads currently recur at `:702–703`. This saves a full repeated intern walk, not just two lines.
- **3 obsolete comment lines**, `eval.clj:4229–4231`, replaced with an accurate one-line accepted-event-source explanation: the reader no longer drops stray closers.

Do not count deletion of all restoration machinery or candidate transfer as earned by this revision. Its correctness still depends on them, and later S2/S3 retirement is not a credit against today's growth. Root needs a revised size justification before integration: the requested revision alone adds 130 net production Clojure lines, plus the SCI change, while material earlier findings remain open.

The landing must be revised in place after fixes: its older section still says the trailing-closer definition is admitted; its current top reports awaiting tests despite root's later evidence; its sizes describe the first cut. Preserve history as dated evidence, not conflicting current claims. Skill correction also remains with its owner: the data-oriented skill's statement about rejecting undefined slots conflicts with `src/seon/schema/internal.cljc:309–347`, which makes them advisory. This lane may edit only this review.

## Delivery boundary

Acceptance requires the numbered corrections, focused installed evidence for their distinguishing cases, ordinary settlement/fault-owner proof, and disposition of root's remaining new reds. Existing green counts establish useful covered behavior but do not discharge these counterexamples. No foreign in-flight breakage is used as a reason to stop.

Only this review file is authored and committed with `git commit --only -- docs/research/agent-platform/repl-s1-revision-review-2026-09-25.md`. Review source/test delta: **0/0**. No independent load, runtime health, publication, browser, timing or memory pass is claimed. Document-only checks inspect whitespace and commit scope. Unrelated workspace/submodule changes are preserved; no push or merge. This path is released after the commit; its hash accompanies delivery.
