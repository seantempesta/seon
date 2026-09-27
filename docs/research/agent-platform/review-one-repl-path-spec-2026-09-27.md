---
type: review
status: revise
created: 2026-09-27
revised: 2026-09-27
spec: docs/research/agent-platform/one-repl-path-spec-2026-09-27.md
spec-commit: eb590e3ec
scope: review only, revision 2
---

# Review: one REPL path specification, revision 2

Revision 2 has one prototype fold with reader/evaluator stage values, speculative database values, and a proposed SCI producer observer. The batching improvement reproduces. **REVISE:** eight execution blockers remain, principally where separately demonstrated mechanisms must compose. This review changes only this document.

The complete `AGENTS.md` and revision 2 specification, including appendices A–G, were read before the probes. Authorities include the REPL, data-oriented Clojure and testing skills, the pinned dependency source, and `tmp/one-path/*`. The first review of `e773f68cf` is preserved in commit `bf1471b38`; its verdict was REVISE with R1–R9. This revision replaces that review in place. Its earlier Q3/Q4 recommendations are superseded by the owner's answers: Q1 A; Q2 forgiving input; Q3 per-form db-after; Q4 storable registered attributes; Q5 def/defonce entities and edges. None is reopened here.

Source citations below use committed HEAD, checked through `git show`/`git grep`, initially `f688ddd10`, then `95b848353`/`c8e1eacfd`; foreign working-tree edits are excluded. The spec and manifest explicitly use the older `dd5dcaff7`. The SCI gitlink remains `743d1c4317`; the proposed observer is **not installed** in default. Patched-SCI observations below came from two finite scratch JVM probes, not a cluster or a test run.

## Verification of the R-answers and owner decisions

| Answer | What the evidence establishes | What remains |
|---|---|---|
| R1, producer observer | `observe-results.edn` demonstrates retained `def`, new/existing `intern`, root alteration, fork isolation, nested dynamic bindings and removals. The patch replaces the unsound env watch. | Its callback still executes inside `eval-def`'s retryable env swap, and after user Var watches. V2-1 reproduces duplicate and missing events. |
| R2, exact join; Q1 A | `join_proof.clj` compares full spans for authored origins; h40 supplies recorded SCI expansions. The claimed 101 consists of 46 span matches, 50 generated matches and five producer-marked members. The record helper marker is present. | The 101 is a classification proof, not row derivation/acquisition with the patched observer. A single-def macro is misclassified as authored and never gets the Q1 hook; closure admission is unsound. V2-2. |
| R3, totality | The new retained prefix fixes the old ns-name failure; printing has a per-entry fallback. `proofs-results.edn` contains the stated shadowing, throwing ns stage and lazy-printer cases. | Publication still follows fallible source bookkeeping, and later cleanup/classification is not all guarded. V2-3. |
| R4, removals | The two supplied examples prove new-then-unmap and removing an existing function in separate top-level forms. | Same-form event order and remove-ns are not implemented. The new refresh predicate also forgets non-bind effects. V2-5. |
| R5, namespace facts | `namespace-row` delegates to the existing namespace owner; the alias example records three transaction items and returns `"OK"`. | The proposed captures-locals criterion misses a local shadowing an existing global. V2-2. |
| R6, loadable cut | The deletion script and manifest are real; counts independently reproduced below. Replacing one huge cut with additive/switch/deletion steps is reasonable. | Proposed schemas collide and are incomplete; function placement needs explicit stage dependencies; tests of removed public functions are postponed past their removal. V2-7. |
| R7, entrants | JVM, thunk and render helpers now all call `pipeline/run`. File proof is correctly identified as an analysis consumer; stored artifact decoding is not another executable reader. | JVM recording is disabled and test recording remains a separate path. One loop is not yet the complete one-path contract. V2-8. |
| R8, read-only rendering | Cooperative shared-atom mutation is now stated. S11 explicitly requires removing connection custody; that is the right boundary, without a security sandbox. | `entrants.clj:54–60` still only forks; the proof starts with `base-ctx`, already lacking a connection (`proofs.clj:12`). It does not prove removal from a connection-bearing viewer. S11's regression must start with that viewer. |
| R9, measurements | The spec now correctly calls these prototype/candidate-write measurements and discloses normalized ERR comparisons and the absent combined diff. | S17 remains necessary for installed semantic and end-to-end performance proof. This disclosure alone is no longer a blocker. |
| Q2, forgiving input | The 52-input comparison and live ordinary alias example establish useful compatibility, finite progress and repairs. | Valid suffixes are read twice, and a repair's speculative full-tail read runs ahead of namespace effects. V2-6. |
| Q3, db-after | Ordinary definition rows are threaded; later pulls see them. Warm speculative apply is approximately 1 ms per definition. | The schema shortcut does not provide the owner's per-form guarantee. V2-4. |
| Q4, attributes | `schema-part` installs the storable key and reports the bridge's unstorable-schema reason. A bare identity-less entity is correctly refused by the separate ownership law. | No new Q4 policy blocker. S3 must use the current bridge and its existing ownership checks. |
| Q5, definitions | The supplied dependency/acquisition proof returns `[42 {:k 41}]` for def/defonce. | Captures still need exact provenance. Optional S16's `get-method`/`satisfies?` inference is not an observed extension; see V2-2. |

Severity: **P0** defeats totality, loadability or the authoritative observation boundary; **P1** violates required semantics. Each finding below blocks executing its named step as written; estimates and disclosed missing production measurements are not separate blockers.

## Revision 2 execution blockers

### V2-1 — P0: the producer callback is inside a retry and behind a fallible watch

**Evidence.** Appendix E patches `Var.bindRoot` after `notify-watches` (`tmp/one-path/sci-patched/src/sci/lang.cljc:104–111`). `eval-def` still calls bindRoot inside the function passed to `swap!` (`sci/impl/evaluator.cljc:31–59`). Arming calls `install-function-contract!`, which resolves by symbol and calls `sci/bind-root!` (`src/seon/sci/eval.clj:1346–1385`). During an inherited redefinition, the event's copied Var is not yet the Var resolvable in the fork. Arming can itself change that same env atom and force the outer swap to retry.

A finite scratch probe against the exact supplied patch started with base `(def f (fn [] 1))`, forked, and defined `(def f (fn [] 2))`. Its observer used the same resolve-and-bind seam as arming. It saw **two** events: first `{:same-var? false :observed 2 :resolved 1}`, then `{:same-var? true :observed 2 :resolved 2}`. The base remained 1. A one-shot nested definition inside a callback likewise produced `["outer" "outer"]` for one outer def. Thus nested *bindings* are proven, but producer re-entrancy is not sound.

A second case installed a throwing SCI Var watch, then `(alter-var-root #'w inc)`. It returned `"watch failed"`, left `w` at **2**, and emitted **zero** observer events. A completed root write must not disappear because a subsequent watch failed. Raw evidence: `tmp/review-one-repl-v2-20260927/observer_probe.clj` and `observer-output.txt` (1,337 ms including scratch JVM startup, exit 0).

**Smallest fix.** In S1, resolve generation ownership and publish the owned Var before its root-write callback; emit each actual write once outside retryable env-update functions and before fallible user watches. Arm the event's exact Var/root rather than resolving an earlier binding by name. Suppress only arming's own observer notification. Add these three cases to the S1/S7 regressions. Keep fork isolation; a loaded-program identity comparison by itself does not exclude overlapping private writes to the same context.

### V2-2 — P1: generated origin and captured-value provenance remain incorrect

**Evidence.** At analyzer's patched macro branch, `(merge m (meta v))` gives an expanded root form location metadata. `analyze-def` decides authored/generated solely from the presence of `:line`. The following legal single-definition macro was probed against the exact patch:

```clojure
(defmacro single [] (list 'def 'made 7))
(single)
made
```

SCI returned 7. `made` was observed as **authored**, span `[2 1 2 9]`. Kondo on the source produced only the `single` definition. Q1's rule supplies expansions **only for generated-bind origins**, so it excludes this call and cannot derive the `made` entity. Raw evidence: `origin_probe.clj` and `origin-output.txt` in the review's tmp directory (2,290 ms including scratch startup). Moving the top-level-location binding at analyzer's former line 2106 does not distinguish reader-origin metadata from inherited expansion metadata. The 101-item classification does not cover this case.

S6's captures-locals rule is also insufficient even if implemented literally. At default's MCP JVM REPL:

```clojure
(def capture-k 10)
(let [capture-k 1] (defn capture-f [] capture-k))
(capture-f)
```

The prototype returned **1**, persisted both rows without diagnostics, and fresh acquisition returned **10**. Standalone re-analysis resolves the global `capture-k`; it does not report the unresolved name that S6 requires for rejection. Evidence: `counterexamples2.edn`.

The record helper's `:sci.impl/member-of` marker is a useful producer fact, but classifying it as `:member` is not yet proof that factory/helper acquisition replays its defining operation once. Optional S16 has a related false observation: `(get-method area :never)` is non-nil through a `:default` method even when `(when false (defmethod area :never ...))` never executes. A fresh SCI probe returned `[true false]` for `[(boolean (get-method area :never)) (contains? (methods area) :never)]`. An existing exact method also cannot prove which source occurrence executed.

**Smallest fix.** S1/S6 must carry explicit reader-versus-expansion provenance and the owning macro-call occurrence, rather than infer it from location metadata. Feed that actual expansion to kondo and prove rows plus fresh acquisition for the single-def macro and record helpers. Use enclosing-form lexical binding/use evidence for captures, including a local shadowing a global; keep such non-reconstructible roots private. If S16 is taken, observe extension installation/removal at its producer and retain its executed occurrence; otherwise defer S16 rather than treat method availability as evidence of execution.

### V2-3 — P0: the value is still published after fallible bookkeeping

**Evidence.** `pipeline.clj:469–481` obtains the evaluation outcome, then computes line/column and `subs`, and only then appends the entry. A supplied reader stage returning form `42`, start `0`, end `999` for text `"42"` satisfies the proposed read-step's field types. The evaluator ran and returned 42. The result was `{:entries [], :reply {:values [], :diagnostics ["batch: Range [0, 999) out of bounds for length 2"] ...}}`: the evaluated value was lost. This is a deliberately failing later source stage, exactly the totality boundary, not a malformed user expression (`counterexamples2.edn`).

After the loop, observer cleanup at line 529 and contract classification/resolution at lines 531–535 are outside `guarded`. An injected observer cleanup throwing `"stop failed"` after evaluation of 42 makes `run` throw with no reply (`counterexamples.edn`). The production dynamic observer can delete the cleanup requirement, but copying this fold is not a proof of totality. The scratch diagnostic maps also do not prove durable fault recording or fault delivery on recorder failure.

**Smallest fix.** S7 must publish a minimal outcome containing the actual value immediately upon evaluation return, before timing/source/namespace/classification work. Validate source bounds before evaluation where possible, and attach later failures to that retained outcome. Guard every subsequent boundary, including cleanup and reply assembly, with a defined minimal reply independent of the failed stage. Specify the existing fault owner for those diagnostics and exercise a failed settlement/renderer through the installed path. Add these cases to `a-batch-keeps-every-value-when-a-later-stage-fails`.

### V2-4 — P1: schema batching breaks the per-form db-after guarantee

**Evidence.** The fold computes pending schema keys as `(remove @flushed-keys changed-keys)` and never clears a key after a second change (`pipeline.clj:451–461`). This exact batch committed successfully, but both pulls returned `":int"`:

```clojure
(seon.schema/register! :review.v2/n :int)
(:seon.schema/form
 (seon.db/pull [:seon.schema/form] [:seon.schema/key :review.v2/n]))
(seon.schema/register! :review.v2/n [:int {:min 3}])
(:seon.schema/form
 (seon.db/pull [:seon.schema/form] [:seon.schema/key :review.v2/n]))
```

It emitted no diagnostic. The one schema flush took 42.31 ms (`counterexamples.edn`). A set of ever-flushed names is not content validity.

The operator shortcut is independently unsound: a `register!` argument can query the earlier registration, so “registering reads no database facts” is false. Flushing before the *next non-registration operator* is not per-form threading. Rows-only speculative schema application also leaves new Datahike attributes out of the working value until final settlement; merely carrying a candidate Malli projection is not the attribute installation promised by Q4.

**Smallest fix.** S8 must apply every changed schema version, including attribute declarations, before the next form evaluates. Key reuse by the actual delta content, not an ever-seen key set. Start with correct per-form application, measure it, then optimize the database/projection owner without assuming anything about the next operator's arguments. Reprice h36 for this guarantee; the reproduced 557 ms measures the shortcut, not full Q3/Q4 composition.

### V2-5 — P1: effects are not folded in execution order, and binds cannot identify reads

**Evidence.** The prototype derives final binds first and then applies every unmap, regardless of event order (`pipeline.clj:483–500`). The live probe:

```clojure
(do (def rr-v2 1) (ns-unmap *ns* 'rr-v2) (def rr-v2 2))
rr-v2
```

returned 2 but ended with `user/rr-v2 :removed`, `{:applied true :tx-count 0}`. Conversely, `(ns gone.v2) (def x 1) (in-ns 'user) (remove-ns 'gone.v2)` emitted a remove-ns event yet retained the function row and committed three transaction items. There is no remove-ns reduction in the supplied fold. Evidence: `counterexamples.edn`.

S9 instructs `read-only-evaluation?` to test absence of `:seon.cluster.eval/binds`. An unmap, namespace removal or schema registration can have no binds and still be a write. Current `src/seon/turn.clj:2075–2103` explicitly treats absent receipts/effects as necessary but insufficient before replaying a read. Losing the declaration check without replacing it with complete observed effects can replay writes during context refresh.

**Smallest fix.** S7/S8/S12 must reduce the ordered event stream by program identity, including all rows affected by remove-ns and a later bind that supersedes a removal. Define source/namespace deletion against the existing writer's referential rules. S9 must use complete mutation evidence, including schema delta and removals, alongside existing read/receipt checks; absence of bound Vars alone is not sufficient. Extend the named removal regression to both examples and add the refresh case to `seon.turn-test`.

### V2-6 — P1: the forgiving stage still rereads accepted code

**Evidence.** `forgive.clj:86–110` parses candidate tails/suffixes to test them, then discards the parsed values. `delimiter-repair` uses the full-tail reader at lines 124–134; `next-form` then reads the selected code again. With a local tagged reader incrementing a local atom, `forgive/forms` on `"Answer: [#review/tag 1]"` returned one accepted form but invoked the reader **twice** (MCP 3 ms). This is not a retry of a failed form. The successful structured suffix was parsed twice.

A full-tail trial also resolves later aliases before an earlier require can execute. A missing-close require followed by `::rv2s/k` stopped unread at offset 0 with `"Alias `rv2s` not found in `:auto-resolve`"`; the spec's successful alias example has its require balanced *before* the repair and therefore does not exercise this boundary. Structural balance is not evidence that namespace-sensitive future forms can be read now.

**Smallest fix.** S4 must perform prose/prompt and unambiguous delimiter normalization without invoking a second semantic reader over future code. Preserve already accepted parsed forms rather than discarding them; resolve aliases/tags only at the advancing live cursor. Retain the finite progress guard. Add tagged-reader invocation counts and repair-before-require/alias ordering to `seon.sci.forgive-test`, in addition to the 52 examples.

### V2-7 — P0: the schema and cut instructions are not yet loadable

**Evidence.** Contrary to §2's “None collides,” `:seon.eval/origin` already means the originating **issue identity** (`resources/seon/schemas/seon.eval.edn:1`, used by `:seon.eval/entity` at line 26 and `src/seon/turn.clj:115–116,891,969`). Replacing it with a def source-span union changes the meaning of admitted history. The proposed `:seon.eval/read-state` is referenced but defined nowhere in §2 or HEAD. A registry intersection of the literal proposed schemas confirms the collision and missing name.

The ownership split also needs an explicit dependency cut. Moving `derive-form` into `seon.fn` while retaining its calls to `eval/reader-context` introduces the reverse of `seon.sci.eval`'s existing `seon.fn` dependency (`eval.clj:125`). Moving `run` into `seon.sci.eval` while retaining direct transaction helpers in `seon.turn` introduces the reverse of `turn.clj:39`'s eval dependency. The introduction says derive/settle are stage values, but §2 actually declares only reader/evaluator functions and derive/settle booleans. It does not specify the function inputs needed to perform the proposed movement without cycles.

The Var-usage manifest also omits namespace dependencies on the deleted `seon.cluster.reply` file: `src/seon/turn.clj:16`, `test/seon/ai_test.clj:25`, `test/seon/cluster/turn_test.clj:28` and `test/seon/repl_parity_test.clj:5` require it. S15 must remove/retarget those requires too; its zero-unresolved-symbol check is not proof that namespace loading succeeds.

S9 says `restore-evaluations!` and its call go, and S14 removes `evaluate`'s old admission behavior. S11 says the old render/preview functions become `render-batch`, while their test conversions are deferred to S15. For example, `test/seon/cluster/prompt_test.clj:40`, `test/seon/loop_proof_test.clj:774` and `test/seon/render_simplification_test.clj:764,787` reference `preview-sources`. If S11 removes that Var, it cannot meet its full-tree lint/load gate; if it keeps a delegate, that needs to be explicit. S8's plural-row schema additions likewise need to remain compatible with single-row callers until switched.

**Smallest fix.** Name the new span schema separately, define read-state and complete stage contracts, and pass immutable resolver/namespace data plus transaction/derive functions downward as explicit values. Do not add reverse namespace requires. State exactly which old names and schema members remain as temporary compatibility delegates until their **last** source/test/schema caller converts; move each final deletion and its caller conversions into the same commit. Add actual require/contract-compile gates to those intermediate commits. The manifest is a useful inventory, not proof of their ordering.

### V2-8 — P1: every entrant calls the loop, but recording is still split

**Evidence.** The §4 table and `entrants.clj:26–48` give JVM and test bodies `:settle? false`. S13 explicitly leaves test recording in `runner/record-tx`; current `src/seon/test/runner.clj:1710,1813,1834` builds/applies that transaction outside the proposed batch. JVM mode gets no equivalent evaluation recording at all. Host Vars not being program entities does not imply host evaluations have no queryable results or faults. The owner's rule requires recording through the same path; the allowed difference is a supplied stage value.

The render helper does enter the common loop, but S11 must actually implement its promised connection removal; its supplied proof is not evidence of that transition (R8 table above). Ordinary render/acquisition results also need an explicit enclosing recording owner where a nested call should contribute to one outer transaction.

**Smallest fix.** Supply the existing entrant-specific recording function/data as the orchestrator's settle stage, including test evidence and JVM evaluation receipts. Distinguish “no program row derivation” from “no recording.” Specify how nested render/acquisition entries contribute to their owning run without a second transaction. Make recording failures diagnostics beside retained values through the same total boundary. Prove one invocation and one owning transaction at each entrant rather than retaining an independent recording call after the common loop.

## Reproduced costs and deletion measurement

Default was available at PID 29866, start `2026-09-27T19:44:15.527Z`, source archive `24a33075c`. `bin/seon status` and MCP `runtime_status` reported **faulted**: 12 open faults, 117 failed tests (41 unknown), 17 unowned namespaces and one replaced instrumentation root. `sci.lang/*write-observer*` was absent. No restart, reset, program transaction or test request was issued by this review.

MCP JVM probes used root `/Users/sean/src/seon`, cluster `default`, owned session `review-one-repl-v2-20260927`, and explicit `@(seon.cluster.boot/connection "default")`. Prototype definitions were read/evaluated only into fresh `review.onepath.v2.*` host namespaces, with qualified references renamed together; no existing prototype or production Var was replaced. SCI work used fresh contexts and immutable candidate database values. The proposed observer was checked separately, never loaded over installed SCI.

| Probe | Result | What it measures |
|---|---|---|
| One contracted defn, first call | 481.52 ms | Includes 371.74 ms candidate settlement; setup/acquisition is visible, not excluded as an excuse. |
| Same defn, two warm calls | **35.71 / 32.36 ms** | Derive 7.26 / 5.83; speculative 1.05 / 0.90; settle 21.09 / 19.91 ms. Applied, no diagnostics. Consistent with spec's 38–39 ms range. |
| h36, 22 top-level register! calls | **557.38 ms** | Read 0.83; evaluate 131.25; derive 110.57; settle 279.12 ms. 22 entries, 44 tx items, applied, no diagnostics. Consistent with 540–600 ms, with the Q3 limitation above. |
| 40 defns with 39 intervening pulls of the preceding definition's facts | **3,151.72 ms** | 79 entries, 40 rows; all sampled reads found the preceding symbol. Evaluate 2,555.47; derive 276.86; speculative 35.05; settle 235.65 ms. This is **not** the same 40-form input as the spec's 623 ms. |

The last probe establishes ordinary read visibility and approximately 0.88 ms speculative apply per definition, but exposes the cost of actual reads on the overlay. It exceeds one second, predominantly in evaluation/pulls; it is not a passing latency claim. This overlaps the spec's F13 (speculative reads) and existing JVM-wide profiling task `c7bc0c506aa4`. The exact generated source and stage timings are retained in `chained.edn`; root owns task classification. No 50-second legacy-path run was repeated. Allocation and the full installed old/new path were not remeasured, and these are not armed production-path latency proofs.

The manifest contains **102 kondo rows in 20 files**, 40 source and 62 test, representing **97 distinct file/line/name sites** (CLJC/usage duplicates explain the difference). Running its deletion script on an owned copy of committed HEAD `95b848353` reproduced:

| File | Definitions | Deleted lines |
|---|---:|---:|
| `src/seon/sci/eval.clj` | 37 | 933 |
| `src/seon/sci/reader.cljc` | 19 | 316 |
| `src/seon/turn.clj` | 15 | 301 |
| `src/seon/cluster/reply.clj` | 17 | 390 |
| Total | 88 | **1,940** |

The remaining reply namespace adds 36 removed lines, yielding the spec's measured **1,976**. The **−1,609** net is arithmetic over measured fragments plus estimates, not a measured candidate diff. The spec now discloses this correctly. The +38/−12 fork count describes the supplied patch, not the corrected observer. S1/S6/S7/S8 prices need to include the cases above and contract work; there is no evidence that every listed slice takes under an hour (S16 itself is priced at 1.5 h). This is a pricing limit, not an additional verdict blocker. The owner decisions remain fixed.

## Verification and landing boundary

This document requires **no runtime test request**. Root's focused implementation list should retain the exact proposed tests below, with the additional cases in V2-1–V2-8:

- `sci.core-test/write-observer-sees-every-root-write`
- `sci.core-test/generated-definitions-carry-their-macro-call-location`
- `seon.fn-test/binds-join-kondo-definitions-exactly`
- `seon.sci.eval-test/every-definition-is-observed-at-the-def-and-armed-before-the-next-expression`
- `seon.sci.eval-test/a-batch-keeps-every-value-when-a-later-stage-fails`
- `seon.cluster.evaluate-source-test/one-batch-settles-in-one-transaction-with-every-row`
- `seon.cluster.evaluate-source-test/a-definition-and-its-removal-in-one-batch-settle-once`
- `seon.sci.eval-test/a-render-batch-cannot-define-or-write`
- `seon.dev.mcp-bridge-test/jvm-and-sci-events-carry-the-shown-text-once`
- `seon.schema-in-place-test/an-agent-registered-storable-key-is-an-attribute`
- `seon.sci.forgive-test`, `seon.turn-test`, and `seon.test.one-request-test` for input, refresh and test evidence.

These are implementation targets, not claims that the new named Vars are installed. The original spelling `seon.test/one-request-test` in S13 is a namespace/path label; the installed namespace is `seon.test.one-request-test`.

Static validation used an owned **git archive of HEAD `c28618184` plus only this document**, not the shared working tree. `clj-kondo --cache false --lint src script test` reported **0 errors, 948 warnings**, 5,658 ms (exit 2 for warnings). An accidentally launched shared-tree lint was discarded as evidence; it used `--cache false` and changed no source. No worktree, test runner, publication, adoption or cluster operation was involved.

The static `seon.contracts-compile-test/check`, called through MCP with that archive's packaged schemas and all **486** source/script/test paths, completed in **1,612 ms** (MCP 1,620 ms). It reproduced the **same ten pre-existing script contract compile refusals** as revision 1: `script/seon/dev/docstring.clj:285,318,334,360` and `script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`. There were **zero source/test contract findings**. These canonical-EDN contract refusals are a foreign script boundary, not a green whole-tree compile claim. The check composes the archive's packaged projection and Malli function-schema compiler; it does not invoke a test request. Whole-population static check time is proportional to 486 files. Markdown validation reported valid with zero violations.

Foreign boundaries: concurrent source/error/vector-storage/dependency edits and staged additions; the older, faulted installed JVM; and any pre-existing static-check findings. None is part of this diff, and none authorizes touching another lane. The REPL skill's claim that an uncontracted defn is refused is stale against current AGENTS and the owner's contract-coverage decision; it was not used as authority. Skill/source corrections remain outside this doc-only assignment. No source, test, resource, reference-code, issue-index or other lane's session/file was changed.

Owned raw evidence is in `tmp/review-one-repl-v2-20260927/`. The two scratch JVMs exited successfully; archive copies and scratch host namespaces are retired after validation. Net source/test changes: **0/0**. Only this document is staged and committed.

The final cleanup reply confirmed all four owned host namespaces were removed (`:remaining []`, MCP 3 ms), but exposed another foreign rendering fault: **"The floor's saved text exceeded its charge."** MCP returned fallback text with blob digest `c043be86086ff73e07fc6c8c7edb0aa73c825751de00e2dbd982c60b5fa6a7d2` (roughly 146 tokens of retained evidence). Root should attach this occurrence to the floor owner's task; it is not attributed to the proposed pipeline or repaired in this review.

After adding that occurrence, a fresh archive of HEAD plus the doc was checked again: lint **0 errors / 948 warnings**, 5,632 ms; contracts **486 files / the same ten script-only findings**, 1,563 ms (MCP 1,570 ms). Both archive directories were removed after the checks.

## Verdict for revision 2

**REVISE**, limited to V2-1–V2-8: make observer delivery exact and retry-safe; carry trustworthy expansion/capture provenance; retain values before all bookkeeping; thread every schema version and installed attribute; fold all effects in order and preserve refresh safety; eliminate speculative semantic rereads; complete the collision-free, loadable contract/caller cut; and route recording through the same stage-valued orchestrator.

Keep the owner decisions, the single-fold direction and the reproduced batching gains. The next revision should change these owning instruction steps and attach the named counterexample proofs; it need not reopen Q1–Q5 or treat disclosed estimates as production evidence.
