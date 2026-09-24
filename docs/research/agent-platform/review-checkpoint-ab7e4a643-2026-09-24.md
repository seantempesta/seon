---
type: review
status: changes required; installation and execution proof unavailable
created: 2026-09-24
---

# Review of checkpoint ab7e4a643

The checkpoint composes existing writer operations into atomic adoption, narrows reload selection,
adds an instrumentation failure path, and batches test recording. Keep the writer composition;
fix reload/instrumentation correctness and revert the unreviewed recording batch.

## Ranked findings — broken things first

1. **P1 — the instrumentation change consumes a missing Malli feature. FIX.**
   `src/seon/instrument.clj:692` handles `:malli.core/invalid-schema-at-call`, but both the
   checkpoint and reviewed HEAD pin Malli **8725a8cbd9d595f4a970ce53a2eefdbe7211b96d**.
   Its `-instrument-f`, `reference-code/malli/src/malli/core.cljc:2202–2220`, calls validators
   directly; validator exceptions never enter `:report`. The live dependency is that same pin.
   Probe P2 returned raw `:malli.core/invalid-ref`, with no `:reported` key.
   Consequently `a-validator-that-throws-at-the-call-is-a-minimal-declared-error` cannot obtain
   its asserted member, operation and chain through this pinned dependency. The landing says
   “G2 fork committed and pushed, pin pending restart”; neither the pin nor restart happened here.
   Smallest fix: integrate/review the already-authored validator-only Malli report change and
   its pin with this consumer; preserve exceptions from the function body unchanged. Do not
   catch all wrapped-call exceptions and misclassify body failures as validator failures.
   Also pass the original report into `minimal`: it currently drops expected schema and
   offending input, contrary to A1-8b and CLAUDE's diagnostic rule. Test missing refusal schema,
   failed refusal construction, throwing input/output/guard validators, and an unchanged body throw.
   Existing authority: [validation gap G2–G4](validation-fails-as-data-gap-2026-09-23.md).

2. **P1 — batch recording loses completed outcomes on a recorder throw. REVERT.**
   `src/seon/test.clj:1765–1766` takes `@executed` then clears it **before** `commit-results!`.
   If that write throws, the catch sees an empty buffer and `record-interrupted!` records all
   still-open members as never-finished errors; earlier actual results have been discarded.
   Even without a throw, completed members remain only in memory while subsequent bodies run.
   This is a regression from recording each completed member before executing the next body.
   B4 §2 step 12 and §8 explicitly settle per-member recording (“not a batching decision”).
   Smallest correction: reverse the checkpoint's sole `src/seon/test.clj` hunk
   (`@@ -1769,41 +1769,66 @@`) and both `admission_digest_test.clj` hunks, removing the added
   `sut` require and `a-request-records-its-batch-in-one-transaction`. Retain its older digest test.
   The new test checks only three successful bodies and transaction count; it cannot detect
   lost outcomes, recording refusals, interruption or an unfinished body. Optimize the writer
   separately using the measured cost in the test-overhead landing. `git apply --reverse --check`
   succeeded for exactly these paths, including after `daa833e32`; no textual conflict then.
   Related existing class: [unfinished runs](../../seon/issues/an-unfinished-test-run-hides-every-problem-family.md).

3. **P1 — S1 narrows reloads without complete compiler evidence. FIX.**
   `cluster.clj:2219` calls every non-macro-namespace `def` Var-indirected, including `^:const`.
   Clojure **3bc2b3e9**, `Compiler.java:7908–7909`, embeds a constant Var's value in its caller.
   The query at `cluster.clj:2247–2267` reads head/inline and same-namespace macro presence,
   never `:const` metadata or cross-namespace macro dependencies. `:seon.fn/constant?` means
   literal initializer (`fn.clj` producer), not compiler `^:const`; substituting it is not proof.
   A constant or a value read by a macro in another namespace can leave compiled callers stale.
   Existing `schema.clj:3914,3918` contains `^:const` declarations; their current uses found here
   are in the same file, so this review does **not** claim an observed stale external caller.
   Smallest safe interim fix: retain conservative compiled/unknown treatment for `def` until
   the producer and dependency facts prove Var indirection; then narrow from those facts.
   Hoist `(set macro-namespaces)` outside the per-declaration map if that query remains.
   The changed test puts literal defs beside a macro, masking the missing no-macro case.

4. **P2 — defmulti/adoption completion remains partial. FIX the missing behavior; keep row order.**
   A changed `defmulti` now avoids dependent reloads, but ordinary reload does not replace an
   existing MultiFn: Clojure `core.clj:1799–1802` deliberately retains it. Adoption unmaps only
   deleted identities (`cluster.clj:2596–2602`). Changing dispatch/default/hierarchy therefore
   still leaves the old callable. This was already a broader adoption gap, not newly created
   by narrowing alone. B1 §2a′ explicitly requires unmapping changed multimethods.
   Smallest fix: unmap affected changed MultiFns and reload their actual method installers;
   prove a changed dispatcher plus retained methods. No second loader.
   Rows → load → arm → record is correct and fixes the old mid-reload adopter-arity hazard,
   but the checkpoint supplies no whole-path regression for that hazard or compile/arm failure.
   Nor does its row transaction serialize JVM reload/evaluation. Keep B1's single integration
   caller restriction until the existing execution boundary proves exclusion; an old record
   must not certify partially replaced Vars. This is incomplete S4/S5 proof, not grounds to
   restore reload-before-write. See [adoption history](../../seon/issues/development-adoption-writes-program-rows-seconds-before-its-record.md).

5. **P2 — the atomic-adoption regression proves less than its description. FIX.**
   `adoption_record_test.clj:55` ignores the success call's result even though its local
   `adopt!` converts exceptions to values. Check the expected returned identities and positively
   establish all three destination entities before comparing their transaction ids.
   The test exercises program + issue rows, **no schema change**, no load, no arming and no
   record write. Add a real schema declaration in the same adoption and exercise the S4 failure
   and retry through the existing adoption owner. The fabricated published descriptor uses the
   live head to guard independently constructed rows; this is a row-writer test, not publication proof.

6. **P2 — new instrumentation regression inherits unsafe global restoration. FIX.**
   `instrument_test.clj:56–75` wraps every test in a fixture that restores old JVM Var roots
   with `alter-var-root`; the new test also interns into the shared test namespace. This fixture
   predates the checkpoint, but adding this regression does not satisfy CLAUDE's ownership law.
   Use the canonical instrumentation preservation scope and disposable namespace, with the
   existing host-isolation classification where necessary; do not restore roots over reloads.

## Every checkpoint hunk: owner, current location, dependency and verdict

Review HEAD is **4fd59fa8784a39cc849d62c8ec28e48284dfdcb6**; initial HEAD was `2614d0353`.
Rechecked through **2f551bf7f**: none of the ten reviewed paths changed after that review HEAD.
Coordinates below are **checkpoint new-side hunk header → review HEAD line range**. All 21
hunks survive byte-for-byte, including context, at review HEAD. None was superseded.
`git blame -l HEAD` and a line-matching comparison against checkpoint blobs establish survival;
deletions were also checked through the complete checkpoint→HEAD diff.

**Load U** in every row means fresh checkpoint/HEAD loading and test execution are **unproven**,
not a pass: only read-only live probes are authorized, and the JVM demonstrably runs older code.
Test dependencies below are source callers/behavior obligations, not recorded green evidence.
`K` = keep implementation direction, `F` = fix, `R` = revert the exact hunk with its companion.

| Path / checkpoint hunk → HEAD | Lane and intent | Completeness, test dependency, verdict (all load U) |
|---|---|---|
| cluster `+2182,27` → 2197–2223 | b1-adoption S1; opus-publication's queued def-reload work | F #3/#4; `reload-per-declaration-test` directly invokes rule; compiler classification incomplete. |
| cluster `+2235,21` → 2250–2270 | b1-adoption S1; macro namespace query | F #3; same namespace suite reaches query; no cross-namespace macro/const proof. |
| cluster `+2494,41` → 2509–2549 | b1-adoption S4; atomic schema/program/issue rows | K, partial proof #5; `adoption-record-test` directly calls adopter. Writer decides diffs; schema first, issue refs last. |
| cluster `+2560,9` → 2575–2583 | b1-adoption S4; change ordering commentary | F wording: “rows ahead … interpreted” is a B2 obligation, not proved here; S4 integration tests owed. |
| cluster `+2574,7` → 2589–2595 | b1-adoption S4; write rows before reload | K; correct smallest arity-hazard repair, whole-path proof owed (#4). |
| cluster `+2618,21` → 2633–2653 | b1-adoption S4; guarded record after arm | K; prior record retained on failure; `publication-serialize-test` / `save-gate-test` are surrounding callers, not new S4 proof. |
| cluster `+2782,7` → 2797–2803 | b1-adoption S4; candidate save-gate arity conversion | K, complete caller conversion; `save-gate-test` reaches candidate route. Reverting adopter alone breaks this caller. |
| cluster `+2962,7` → 2989–2995 | process identity deduplication; in b1-held file, no specific lane attribution recorded | K; exact old `str pid "-" (inst-ms start)` now owned by `cluster.process/id:26`; `cluster.turn-test:58` calls adapter. |
| fn `+3521,36` → 3487–3522 | b1-adoption S4; `reconcile-call`, `adoption-tx` | K; pure tx-data composition, writer's DB determines replacement; adoption test reaches both, fn/index tests reach helper. |
| fn `+3651,11` → 3617–3627 | b1-adoption S4; reuse reconcile helper, retire record hook | K; no remaining source/test caller found supplying retired record to index!; `fn-test`, `incremental-deletion-test`, `publication-delta-test`. |
| instrument `+655,49` → 665–713 | a1-arming G3/G4 after G2 fork | F #1; lazy refusal validator and local diagnostic direction useful; missing dependency and incomplete minimal diagnostic. |
| issue `+947,31` → 947–977 | b1-adoption S4; `adopt-call` plus delegated selected writer | K; immutable source read, issue diff at writer; adoption test + `issue-test`/`issue-head-guard-test`. |
| issue `+985,6` → 985–990 | b1-adoption S4; delete five-argument contract | K; removed record caller converted with cluster; surviving 2/3/4 arities covered by issue tests. |
| issue `+994,8` → 994–1001 | b1-adoption S4; remove five-argument body | K, same retirement; reverse with cluster/fn only, never in isolation. |
| test `+1769,66` → 1755–1820 | test-overhead; one recording transaction per batch | R #2; incomplete failure handling, conflicts with B4; added batch test depends directly on it. |
| adoption_record_test `+1,64` → 1–64 | b1-adoption S4, replaces opus-publication's rows+record test | F #5; replacement principle correct, positive/schema/full-adoption evidence missing. |
| reload_per_declaration_test `+69,12` → 69–80 | b1-adoption S1; rule's new arity | F #3; keep arity conversion with rule, add const/no-macro/cross-namespace cases. |
| store_test `+143,15` → 143–157 | **store-damage**, not one of the four named lanes; ledger row 12, parent `20d317e19` | K for commit-durability scope: injected hook fired, no report, old memory/disk head checked. Accepts throw or explicit unknown; does not prove default panic policy. |
| instrument_test `+1006,39` → 1006–1044 | a1-arming G2–G4 validator regression | F #1/#6; correct intended class, impossible asserted report under current pin; unsafe inherited fixture. |
| admission_digest_test `+1,7` → 1–7 | test-overhead; import batch caller | R with #2; added `sut` alias has no remaining use after removing new test. |
| admission_digest_test `+41,23` → 41–63 | test-overhead; assert two transactions | R #2; asserts rejected recording mechanism rather than required failure behavior. |

The `store_test` adjustment is not a storage-policy implementation: its bare store has no
config fact, and `db/panic-on-core-error?` reads that fact. Preserve the durability assertions;
prove the default/panic policy separately with explicit configuration and exact failure cause.

## Lineage, guarantees and cost

Ledger authority: `tmp/orchestrator/file-ownership.md:10,12,16,19,20,154–155`.
Read [opus-publication landing](../../prds/agent-platform/landing/lane-opus-publication-2026-09-23.md),
[a1-arming landing](../../prds/agent-platform/landing/lane-a1-arming-2026-09-23.md),
[test-overhead landing](../../prds/agent-platform/landing/lane-test-overhead-2026-09-23.md), and
[store-damage landing](../../prds/agent-platform/landing/lane-store-damage-2026-09-23.md).
No separate b1-adoption landing was found; B1 §2a′ and the ledger name its S1/S4 intent.
Opus's older `68f769a4a` last-row-plus-record design is deliberately replaced by S4, not lost accidentally.
Current authorities read: CLAUDE.md end to end first; B1, A1 and B4 plans; data-oriented-clojure,
REPL, testing and Datahike skills. Later rules supersede dated landing prescriptions.

Later commits touching these paths: `43addd46c`, `de13aa02c` (cluster fault disposition/status),
`2614d0353` (publication gitlink paths); `35585cac6`, `ebfa81ace` (query grounding then removal),
`fa13c41a8`, `7c761e0c9` (fn source races); `daa833e32` (test failure text), `30b570d91`
(instrumented namespace reads). None overwrites a checkpoint hunk. All five changed test files
and `issue.clj` have no later edits at the review commit. Dirty foreign files were neither
restored nor treated as committed HEAD. Both targeted and whole-checkpoint reverse **check**
succeeded on the then-current tree; no reversal was applied. Whole reversal is nevertheless
semantically wrong: it restores the superseded adoption order and duplicate process formatting.

Dependency seam: Datahike **620721d6**, `db/transaction.cljc:1171–1172`, passes the evolving
writer DB into `:db.fn/call`; `:466–467` updates schema/reverse schema as schema datoms land;
`:1224–1234` validates the final report. This supports the one-transaction composition, not
JVM exclusion. Inputs are published rows/identities/projection and the writer DB; reconciliation
is recomputed on adoption inside the writer, proportional to selected rows plus schema work.
Reload classification queries changed declarations and macro namespace membership; current set
construction repeats per declaration. Instrumentation adds arming-time closures/delay; refusal
construction is on failure. Batching buffers O(batch size) outcomes to save member transactions.
No checkpoint-specific before/after timings, heap deltas or publication clock row were found;
older landing measurements predate these hunks and cannot establish their performance.

## Read-only live evidence and limits

All calls used seon MCP, root `/Users/sean/src/seon`, cluster `default`, JVM mode,
`read_only: true`; no reload, arming, branch creation, test request, reset or provider call.
No archive/worktree was created: fresh loading would require runtime mutation or files beyond
the sole report authorized here. Thus no fresh load/test claim is available for **any** hunk.

P1 (`runtime_status`): pid **90471**, start **2026-09-23T22:18:14Z**, no missing boot layers,
all listed procs replied, but **16 error signatures, 34 errored receipts, 1 failed run,
1 failed-tests-unknown**, 68 profiled functions >1 s; `seon.test/run` max **120,942 ms**.
These are existing observations, not attributed to the checkpoint. Every eval also returned
branch-error observation **unknown**: “This JVM's seon.error has no occurred-since (RESET NEEDED).”
No reset was performed or requested by this review.

P2 (exact dependency-only form; local objects only):

```clojure
(let [t (System/nanoTime)
      s (malli.core/schema [:=> [:cat [:schema [:lazy-ref :review.checkpoint/absent]]] :int]
          {:registry (assoc (malli.core/default-schemas) :lazy-ref (malli.core/-ref-schema {:lazy true}))})
      f (malli.core/-instrument {:schema s :scope #{:input :output :guard}
          :report (fn [k _] (throw (ex-info "reported" {:reported k})))} identity)]
  (try (f 1) (catch Throwable e
    {:message (ex-message e) :data (ex-data e) :ms (/ (- (System/nanoTime) t) 1e6)})))
```

Result: message/type `:malli.core/invalid-ref`, data `{:ref :review.checkpoint/absent :type :ref}`,
**0.414875 ms**, MCP event **2 ms**; the callback was not invoked.

P3: on `(seon.db/db (seon.cluster.boot/connection "default"))`, basis **536872716**,
cluster's source commit **6ab41567-108f-58d6-b79e-4c4b6ccbe032**. Reading
`(select-keys (meta (find-var s)) [:file :line :arglists])` showed reload rule args
`[defined-by inline?]`, adopter args `[connection published identities published-database record]`;
`find-var` returned nil for `seon.fn/reconcile-call`, `seon.fn/adoption-tx`, `seon.issue/adopt-call`.
Inventory **1.463125 ms**, event **3 ms**, heap used **4,309,470,768 bytes** (snapshot, not allocation).
The MCP windowed that inventory into its own result blob; no transaction was requested by the probe.

P4: query `[:find [?s ...] :in $ [?s ...] :where [_ :seon.test/sym ?s]]` with the three
new/replacement test symbols in rows 16, 19 and 21 above returned **[]**; none is installed.
Classpath resource reads `(str (clojure.java.io/resource "seon/cluster.clj"))` and
`"malli/core.cljc"` name archive **ce73846828a5cc32798ef630b5a574f777646f30** and Malli pin
**8725a8cbd9d595f4a970ce53a2eefdbe7211b96d**. Read **3.214125 ms**, event **5 ms**.
Neither older tests nor historical greens certify the added tests. No induced failures escaped
P2; its Throwable was caught locally. Runtime health was not declared green.

Checkpoint size: **350+/207−**, source **223+/165− (net +58)**, tests **127+/42− (net +85)**.
This review changes only this report; production/test delta **0**. Commit is the path-limited
commit containing this file; no source fixes, dependency changes, issues or index edits were made.
Markdown hook reported 57 stale dependency-citation errors in foreign documents; those were
left untouched. Report whitespace and local link-target checks passed; this is not a global lint pass.
