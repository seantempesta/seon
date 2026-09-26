---
type: audit
date: 2026-09-26
status: source review complete; runtime verification not performed
scope: eleven Seon commits and two maintained dependency commits
---

# Audit of landed changes — 2026-09-26

This is an independent source audit, not an implementation or a runtime acceptance report. The main correctness finding is that `ab87961d5` treats a transaction report as proof of every change in its returned database, although the pinned Datahike writer can return a batch's final database with one transaction's datoms. Other findings concern configuration on retained branches, cache weights, Malli aliases, failure reporting, and boot identity.

The reported parent-head assertion does **not** establish a run-custody escape. Its measurement includes the host's deliberate parent-side admission write. The exact parent connection selection is in an earlier commit, `dba08a8ab1`, not in one of the requested diffs. The declaration failure in the supplied output is separately an auto-check failure before the later forms run.

## Scope and evidence

Read `CLAUDE.md` end to end, the data-oriented Clojure, REPL and Datahike skills, the turn PRD §§13–15, the named diffs through `git show`, and the dependency implementations and first-party callers cited below. Reviewed all eleven requested Seon commits and both fork commits, including their tests, resource changes, landing notes and the P1 patch committed by `d6942b298`.

Source inspection began at Seon `29bb6bb3279b43c1093902b2ecd691b4b03c3693`. Other lanes advanced HEAD during this audit; their changes are not part of its verdicts. The checked-out forks were Datahike `67b00071e020a7ccf310985d01b28128cdadec34` and Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`. Unless expressly identified as an inspection-base location, line numbers below refer to the named commit, obtainable with `git show COMMIT:PATH | nl -ba`.

No tests, JVM evaluations, restarts, publications, adoptions, branch creations or provider calls were performed. Runtime statements below are attributed to the supplied log or existing landing evidence. No installed-API, installed-fix, new timing or memory measurement is claimed. Other lanes' in-flight edits did not obstruct this read-only assignment and were not changed, messaged or resumed. Only this document is owned and changed.

## Findings, ranked by severity

### P1 — F1. A batched transaction report can incorrectly advance the acquired program

**Commit / location:** `ab87961d5`, `src/seon/sci/eval.clj:1965`, `:1975`, `:2075`; caller `src/seon/turn.clj:5221`. **Owner:** SCI acquisition/settlement; coordinate with the Datahike report owner.

The new check requires only equal `basis-t` for the previously acquired value and `report/:db-before`, then derives the entire changed entity set from `report/:tx-data`. If those entities are covered by the installations, `install-evaluated-rows!` advances the program snapshot to the supplied after-database.

The pinned dependency does not give this report the assumed boundary. In Datahike `67b00071`, `src/datahike/writer.cljc:229` drains multiple reports into a batch, `:234` chooses the last report's database, and `:259–264` substitutes that committed database into **every** report's `:db-after`. Each report retains its own `:tx-data` and `:db-before`. Seon's `transact-call` returns that report; it does not restore a transaction-local after-value (`src/seon/db.clj:4746–4796`, inspection base).

Source-derived counterexample: start from acquired value D; settlement A installs f; a following transaction B changes unrelated g; the writer commits A and B together. A's before-value is D, so the new equality test passes. A's datoms do not name g, but A's returned after-value includes B. The snapshot can advance to that value after installing only f, leaving the old g in SCI while its database is marked acquired. The caller's `committed-row?` filter at `turn.clj:5212–5213` checks f against the final value, which protects that installation; it does not discover unrelated g.

This violates current positive proof and content-valid acquisition. It is a program-staleness defect, not a connection-routing defect, and it is not evidence for the supplied parent-head failure.

**Smallest fix:** use the fast path only when dependency evidence establishes that the returned after-value contains exactly the report's transaction. An uncovered/batched report must leave the snapshot unadvanced so ordinary acquisition handles it. Also compare actual branch/commit identity where committed values are compared; a transaction number alone is not a cross-branch identity. If the dependency supplies an exact applied report, carry that value explicitly instead of adding a stamp or restoring the history scan.

**Missing regression:** two queued transactions sharing one commit, with the second changing an unrelated function and then, separately, the just-installed function. Assert both the next SCI call and the acquired identity. Existing changed test callers merely pass reports; they do not exercise batching. Also retain component-only edits, retractions and an intervening write as coverage cases.

### P1 investigation — F2. The parent-head test includes a deliberate parent write; the declaration red has a different producer

**Locations / provenance:** `test/seon/cluster/host_test.clj:106–115` at the inspection base, introduced by `07a03cdd4b`; current parent write `src/seon/cluster/host.clj:82–85`, attributed by `git blame` to `dba08a8ab1`. **Owner:** hosted-agent lifecycle/test owner; auto-check owner for the second failure.

The saved output `tmp/orchestrator/tests-0626c/seon.cluster.host-test.txt` reports run `682226279b15`, 16 executed members, 37 passes, 15 failures, 3 errors and 121,189 ms. For the custody member, its only reported failed assertion is that the parent commit changed from `6ab83b57-5c18-5fb9-b438-15a23b38801a` to `6ab83b57-70c2-55e3-b2d5-a7128ec54a8a`.

The test captures `parent-commit` **before** `(host-on! handle branch "mcp/solo")`. Host `start!` subsequently executes:

```clojure
(doseq [target [(:seon.db/connection cluster-handle) connection]]
  (db/call-with-custody {}
    #(db/transact! target [[:db.fn/call #'home-call ...]])))
```

Here `cluster-handle` is the parent and `connection` is the acquired branch. `home-call` creates an absent agent row on each database; the parent lacked `mcp/solo` when this test started. The first explicit transaction can therefore move the parent before either tested source run. Clearing ambient custody here is deliberate, so both lifecycle writes reach their named connection. The host then merges the execution's branch connection and SCI context into the handle used for runs (`host.clj:90–96`).

The requested diffs do not introduce a parent-connection substitution at that seam:

- `55b5b580f` adds `*open*` transaction **data**, prepended at `db.clj:3232–3238`; `absent-call` at `:3240` reads its supplied writer database and returns operations. Neither selects a connection. Evaluation still takes custody from its context; the turn still uses its handle's connection. Its new `call-with-custody` binding preserves `*open*` only when the incoming connection is identical to the previous one.
- The `binding` initializers are parallel, so that identity comparison sees the previous binding, not the newly supplied `*conn*`. This follows Clojure's actual `binding` implementation and contract (`reference-code/clojure/src/clj/clojure/core.clj:1968–1992`).
- `ab87961d5` consumes immutable reports and mutates a SCI snapshot, not a branch connection. Datahike `67b00071` shares `:node-cache`, not `:conn`; connection identity still includes the branch (`src/datahike/store.cljc:44–55`).

**Conclusion:** the exact line capable of the observed parent write is `host.clj:84` after parent selection at `:82`, from `dba08a8ab1`. It is a **host-admission** write. No requested commit is established as making a branch run's own write land on the parent. The saved output does not report the accompanying assertions for branch row presence or parent row absence failing, which is consistent with that distinction; this is not a fresh proof that every custody path is correct.

**Fix / missing regression:** capture the parent head after host admission, then compare it after each run. Separately assert the allowed lifecycle transaction on the parent. Include a form that calls elided `seon.db/transact!`, inspect that transaction's receipt and branch, and verify no parent transaction occurred during the run. Do not remove the declared host-home write to satisfy the current assertion.

For `host_test.clj:300–303`, the same log records the first form's terminal diagnostic:

```text
seon.test.accretion/auto-check refused return value at
[:seon.test.accretion/failure :seon.test.accretion/explanation]:
expected a string, got nil
```

Its displayed results are the phase failure followed by three nils; a later run cannot resolve `run-square`. At the inspection base, `src/seon/test/accretion.clj:129–154` sets `returned?` false on a thrown invocation and produces a nil explanation; `auto-check` at `:264–270` places that observation in its failure result. That can cause the armed output refusal to replace the useful candidate failure. The log does not reveal the original invocation failure, so it cannot establish why that candidate failed or that a successful earlier declaration was lost. None of the requested diffs changes this producer.

**Fix / regression:** the auto-check failure producer must retain the invocation's whole error under a valid declared shape and omit an absent optional explanation, or produce the required explanation from that error. Cover a thrown generated invocation, then repeat the existing multi-form hosted declaration regression. Do not loosen unrelated contracts or bypass admission. The existing task `62a5644dd469` concerns auto-check custody; this nil-explanation symptom needs separate evidence within that owner's diagnosis.

### P2 — F3. A forms-only cache weight permits unlimited zero-weight contract projections

**Commit / location:** `92b22cbf1`, `src/seon/schema.clj:2338–2342`; `src/seon/cache.clj:159–164`. **Owner:** schema/cache owner.

`build-projection` keys by `[forms function-contracts options]` but weighs only `count (:seon.schema.projection/forms value)`. The API accepts empty forms and nonempty function contracts. A projection built from `{}` and one unique contracted function has a registry, contract indexes, compiled values and its retained key, but weighs zero. Repeating with different function symbols produces distinct retained entries that never consume the configured byte allowance. Large contract populations with a small fixed schema population are also undercounted independently of their size.

This is not merely an imprecise heap estimate. Caffeine 3.2.4 explicitly excludes zero-weight entries from size eviction; this cache configures no other expiry. See [`Caffeine.weigher`, lines 449–456](https://github.com/ben-manes/caffeine/blob/v3.2.4/caffeine/src/main/java/com/github/benmanes/caffeine/cache/Caffeine.java#L449-L456). The zero-weight counterexample is source-derived, not an executed allocation experiment.

**Fix:** assign a positive base cost to each retained entry, account for function-contract population and retained key inputs, and calibrate those terms. Do not replace Caffeine eviction with a second policy. Keep nonretained nil/refusal results distinct from retained values weighing zero.

**Missing regression:** many distinct empty-form/nonempty-contract projections under a small explicit cache budget, plus contracts growing with forms held constant. Assert eventual eviction after the library's maintenance, not just a weighted total that remains zero. Existing tests use constant positive weights and cannot catch this.

### P2 — F4. The Malli fast path excludes direct `:ref`, but not a registry alias to it

**Commit / location:** Malli `43c6dca8`, `src/malli/core.cljc:2447–2450`. **Owner:** maintained Malli fork / contract-cost owner.

The predicate checks `-regex-op?` and whether the child's immediate parent's type equals `:ref`. A named schema wraps its target in Malli's internal pointer (`schema`, `:2617`; `-pointer`, `:235`; `-schema-schema`, `:2053`). That pointer delegates `-regex-op?` to its child, but its own parent type is `:malli.core/schema`. The underlying `:ref` returns false from `-regex-op?` and deliberately throws `::potentially-recursive-seqex` from the regex validator and explainer (`:2036–2042`).

Consequently, with registry entries `:audit/item [:ref :audit/int]` and `:audit/int :int`, the source path for `[:cat :audit/item]` selects the item validator, which can validate `[1]`. Its unchanged regex explainer still delegates through the pointer to the refusing `:ref`. The old validator used that same refusing regex path. This changes accepted schema semantics and breaks validator/explainer agreement. Function-schema construction may reject such a schema earlier through regex min/max; this finding does not claim that every armed function bypasses that check.

**Fix:** make fast-path eligibility respect the dependency's actual delegated regex-item semantics, including internal pointers/proxies, while keeping an explicit public `[:schema ...]` wrapper's intentionally atomic semantics. Do not add a Seon-side validator workaround.

**Missing regression:** validator/explainer agreement for a named alias to `:ref`, including a reference to a recursive schema. The new tests cover a direct `:ref`, a named scalar, a splicing alias, and an explicit `:schema` wrapper, but not this combination. No test was run here.

### P2 — F5. `effective` now applies the JVM's config vocabulary to every retained branch

**Commit / location:** `558446356`, `src/seon/config.clj:870–905`; `af7ba85b7` retains this behavior. **Owner:** config/schema owner.

The boot failure being repaired is concrete: `apply-compiled!` writes a new dial that the older branch projection does not select. However, the change applies the packaged declaration population to **all** calls of `effective`, including immutable historical values and retained agent branches that have not had `apply-compiled!` run on them.

For a retained branch from before `:seon.config.cache/max-bytes` existed, its config row lacks that required dial and its own program still has the older config vocabulary. The new `effective-in` computes required keys from the newer packaged projection and returns a missing-effective error. A branch-defined additional dial is conversely omitted by `select-keys`. The public docstring's premise that this JVM compiled and wrote every row it reads does not hold for retained branches or history.

This moves a boot-specific authority into the general reader and conflicts with branches retaining their program and configuration facts. It does not write to the parent, so it cannot explain F2's head movement.

**Fix:** keep the explicit projection boundary (`effective-in`) and give boot the declaration population/config value it actually applied; general branch reads must use the row's owning program and contract. `0dc665873` already carries compiled boot dials for boot's own consumers. Reconcile publication/arming at that boundary rather than making every branch reader use the newest package.

**Missing regression:** hold a branch at the old commit across a new-dial boot/adoption; read old-branch config and new boot config independently. Include a branch-local declaration and historical read. There are no test changes in `558446356`; its landed evidence covers the boot symptom, not this cross-program behavior.

### P2 — F6. Failure replies retain the old timing semantics and discard recorded allocation

**Commit / location:** `65d0ca8e0`, `src/seon/sci/eval.clj:4329–4342`, `:4537–4539`, `:4782`; unchanged failure conversion at `:4117–4150` and `failure-result`. **Owner:** evaluation/cost producer; presentation consumes its values.

Success gets the new elapsed clock after rendering and subtracts acquisition. Failure still copies `:seon.eval/duration-ms` from the kernel record. That record is taken before the failure's `shown-result`, and the failure conversion does not copy acquisition or allocated bytes. Thus success and failure under one public key measure different intervals, and a failing allocation-heavy evaluation has no allocation finding despite the kernel having measured it. The earlier `d6942b298` landing explicitly acknowledged the unchanged failure path; the replacement landing in `65d0ca8e0` omits that limitation.

There are two further limits to the claim of “entrance to answer”: the clock starts after context construction/scoping, and it ends before `gate-function-install` and settlement, which run after `sci.eval/evaluate`. Recording only successfully completed `acquire!` calls also leaves thrown acquisition time unclassified. These boundaries should be named accurately rather than reported as a complete caller-visible answer time.

**Fix:** one terminal cost projection for both success and failure, taken at the same defined boundary after rendering; preserve the kernel's recorded allocation and account for acquisition in a `finally`. Keep complete request/settlement latency separately if that is what `:ms` promises. No second failure timer or formatter is needed.

**Missing regression:** success and throw after equivalent work; acquisition refusal; a failing high-allocation form; a slow error renderer. Compare emission and pulled receipt fields. `65d0ca8e0` changes no tests, and `d6942b298`'s tests provide made-up cost fields rather than exercising their producer.

### P2 — F7. The first-write preflight erases unexpected `ExceptionInfo` failures

**Commit / location:** `55b5b580f`, `src/seon/turn.clj:5861–5875`. **Owner:** turn submission owner.

The new preflight catches every `ExceptionInfo` and retains only `ex-data`. Only a map with `:seon.error/at` is then treated as a refusal. An unexpected `(ex-info "failure" {})` from the plan or a called owner becomes `{}`, passes the refusal selection, and reaches the resume path with no valid plan or starting namespace. A structured recognized refusal also loses the thrown cause information at this catch. Previously the `db/with` path went through the database owner's `write-refusal`, which classifies and preserves the exception.

**Fix:** catch only the declared plan refusals and preserve their error value through the existing error owner; rethrow everything else whole to the turn's fault boundary. A preflight must not turn an unknown failure into permission to evaluate. This requires no new retry or error envelope.

**Missing regression:** a recognized busy/admission-closed refusal and an unexpected `ExceptionInfo` with no error identity; neither may execute the form, and the latter must retain its cause and reach the caller/fault owner.

### P2, fixed in later source — F8. The new failure notice could itself prevent the failure reply

**Commit / location:** `804440215`, `src/seon/sci/eval.clj:1026–1028`, `:4510`; `program-value?` at `:562`. **Owner:** SCI admission owner.

`unadmitted-notice` passes failed-form binding maps to `program-value?`, whose armed input required an intern including `:seon.sci.eval/var`. Those binding maps do not contain that member. The notice is called while constructing `failure-result`, so its contract error replaces/escapes the failure that the notice was supposed to explain. This violates the total-path rule.

This is corroborated by the recorded `do/defn` internal error, signature `cefa1c70ca31`, in the `35171776f` landing. The later diff `715c23669` changes `program-value?` to accept `:seon.sci.eval/binding`, which is the shape it actually reads. That source fix was present at inspection; this audit does not establish its installation or test result. Its landing records that another lane committed the held file, an ownership violation independent of the code fix.

**Fix / regression:** retain the smaller input contract and run the existing new top-level-do regression armed. Add an assertion that the original failure value survives notice construction. Do not count `804440215` alone as a passing landed slice.

### P2 — F9. Reply bounds are a second, reload-keyed derived cache

**Commit / location:** `d6942b298`, `src/seon/repl.clj:245–255`, consumers `cost` and `findings`. **Owner:** REPL renderer/config owner.

The new `bounds` delay closes over the packaged forms and keeps an explainer, measure list and fixes until the namespace reloads. It takes no viewing projection or declaration content. A branch's changed bounds cannot govern its replies, and changing the declaration without reloading this namespace leaves old findings. It also sits outside the central cache that `92b22cbf1` requires. The old landing explicitly documented the reload dependence; it was not repaired by applying P1.

**Fix:** pass the owning declaration value/projection into the render path and compile/cache by that content through the existing schema/Malli/central-cache owners. Keep Malli's `explainer` and native paths; do not add a separate threshold engine. If the intended policy is permanently process-owned, declare that boundary explicitly and still retire the independent cache.

**Missing regression:** two viewing worlds with different bounds, and a changed declaration taking effect without a `seon.repl` reload. Within-bound formatting and two simultaneous findings are already covered.

### P2 proof gap — F10. The boot shortcut names a stronger identity than its evidence establishes

**Commit / location:** `fa6f8e8b0`, `src/seon/cluster/boot.clj:72–80`; `src/seon/cluster.clj:2358–2361`, `:2684–2687`, `:2718`. **Owner:** boot/publication/arming owner.

`published-program` reads `source/current` for namespaces and digests; after arming, boot reads `source/current` again to choose the commit it names loaded. `loaded-commit` compares only the published digests of loaded source files. It does not establish equality of schema resources or other arming inputs. Meanwhile `arm-host-program!` receives the cluster database before development adoption, and `instrument/apply!` derives supplied-default snapshots from that database (`src/seon/instrument.clj:850`, inspection base). Wrapper preparation plans are decided at arming (`call_preparation.clj:757–776`).

A source-derived race is a publication between those two `source/current` reads that changes schema resources without changing loaded `.clj` bytes: the byte comparison can name the later commit, although arming preceded it. More generally, equal source bytes do not by themselves prove equality of supplied-default/contract populations. The new `loaded?` branch uses this identity to skip both reload and re-arming.

This is a missing proof at a correctness shortcut, not an observed incident or an attribution of the host-test errors. The existing test uses three file digests and an arbitrary commit id; it proves the helper's comparison, not publication → arming → adoption convergence.

**Smallest fix:** capture one published value/identity and carry it through boot arming and naming. Prove the content inputs that determine arming against that same value; skip re-arming only when those inputs match. Reuse existing content identities rather than add a separate “healthy” stamp. Keep the extracted `record!` composition.

**Missing regression:** boot on an existing cluster program with changed supplied-default/schema inputs; a second cluster in the JVM; and publication movement during arming. Verify prepared calls and armed behavior after adoption, not only the named commit. The three `Exception` catch expansions in this commit are separately appropriate and preserve whole causes through `diagnostic`.

## Smaller compositions and remaining law issues

| Area | Evidence and disposition | Owner |
|---|---|---|
| Cache slice size and residual holders | `92b22cbf1` adds 244 and removes 50 source lines, net +194, including a new 197-line cache owner. Its own landing acknowledges that the intended later deletions were not landed. Caffeine is the right dependency, but the “one cache” migration is incomplete: existing projection/read/render caches remain. Treat this as an explicitly incomplete slice, not compliance established by changing the law. | Cache/root integration |
| Cache lifetime and status | `cache.clj:83–87` uses a process `defonce` delay before store boot; that reason is stated, but it is not the required process roster. `kind-counters` retains every distinct caller-supplied kind indefinitely outside the weight budget. Bound/own that metadata or establish a finite declared set. Per-kind removal statistics are asynchronous; the new test's `cleanUp` call alone does not await the removal listener, so immediate per-kind equality can race. | Cache owner |
| Duplicate declared projection retention | `558446356` adds `config/declared-projection` around the already cached `schema/declaration-projection`. `af7ba85b7` improves its key to the held forms value, but the same derived object is charged by two cache entries. It avoids repeated predicate discovery; move that optimization into the projection owner so callers do not add another derivation wrapper. Preserve changes in predicate bindings when choosing the key. | Config/schema |
| Retired stamp | `af7ba85b7`, `schema/edn.clj:350–371`, leaves `declaration-stamp` with no production caller. Existing task `68490518e186` names the held test that still redefines it. Delete the test dependency and the function together through their owners. This is known unfinished deletion, not a newly invented task. | Schema EDN/program test |
| URL key | The URL in `af7ba85b7` names a committed immutable archive under the operating contract. It is defensible content identity by provenance, not a file-mtime stamp. It is not safe evidence for arbitrary mutable classpaths; do not extend that guarantee beyond the archived runtime. No extra filesystem scan is recommended on the ordinary path. | Bootstrap/schema EDN |
| First-write preflight work | `55b5b580f`'s `system-run-plan` builds all source/open rows during preflight, although the pass retains only its namespace, then the writer builds them again. Separate the shared admission/namespace decision from row construction; leave the writer as the final authority. No second evaluator or speculative database is needed. | Turn owner |
| Top-level `do` | `804440215` keeps one admission authority, but adds its own recursive top-level walk alongside SCI's existing walk (`sci/impl/interpreter.cljc:34–43`). Source +110/−27; no after timing in that landing. A dependency stage hook could remove the duplicated walk if a verified seam is added upstream. Do not claim such a hook exists now. Multiple declarations still refuse because settlement holds one row (task `3411720c27cf`), and the reader's separate declaration classifier survives (task `1ef8af9c225f`). These are recorded remaining limitations. | SCI/turn/reader owners |
| Historical scope | `ab87961d5` removes the unbound whole-history scan, a material simplification. Its parent discovery still queries historical component edges, so its work can grow with historical owners of the touched children; “proportional to settlement datoms” is stronger than the implementation. Prefer exact before/after ownership where sufficient, preserving removed-edge evidence. | SCI/database |
| Already-applied patch | `d6942b298` commits a 212-line P1 patch under `tmp/`; `65d0ca8e0` applies it. The live code and patch now duplicate an implementation. Retire that applied patch once its evidence is preserved in the landing/history. The same commit's landing claims for successful costs must retain F6's failure-path limitation. | Evaluation documentation/root |

## Per-commit verdicts

“No additional defect found” means only that this review found none; it does not mean installed or tested. All regressions listed below are recommendations for the owning lane/root, not work run by this audit.

| Commit | Verdict | Evidence, composition and regression assessment |
|---|---|---|
| `55b5b580f` | Revise error handling; custody accusation not established. | F7; F2 identifies the actual parent write outside this diff. `absent-call` correctly uses Datahike `entid` and the mid-transaction `:db.fn/call` seam (`db/utils.cljc:116–136`, `db/transaction.cljc:1171–1172` at the fork). Keeping pending open as data beside custody avoids a second writer. Tests cover first/second write and a renderer fault, but omit a post-host parent baseline, effect/capture first writes, staged replies and preflight exceptions. The renderer test checks receipt count, not preserved value plus fault delivery. Source net +66; tests net +64. |
| `ab87961d5` | Correctness revision required. | F1. The report-based algorithm replaces a whole-history scan appropriately, but the exact report guarantee is wrong. Pass one authoritative report value rather than both independently supplied `:db` and `:transaction-report` when possible; their agreement is currently assumed. No behavioral batching regression was added. Source +53/−45; test caller changes +23/−22. |
| `fc0ff40b9` | Pin correctly references the reviewed fork; no independent custody defect found. | Gitlink `df4bcddb5` → `67b00071`. Performance improvement is not verified by the pin itself. Test the real connected branch/cache path, not only reservation identity. |
| Datahike `67b00071` | Sharing composition is appropriate; lifecycle coverage incomplete. | Reservation and publication carry one node cache per physical-store key and threshold; storage receives it explicitly. Connection, writer, pending writes and freelists remain separate. The test proves two opening reservations share cache/hooks, but not actual connected storage, differing physical stores/thresholds, failed opening, final release or address reuse. `CachedStorage.store` can reuse addresses when crypto hashing is off (`persistent_set.cljc:414–424`), so “addresses never change meaning” needs that lifecycle qualification; no new reuse-corruption incident is established here. No custom second cache registry was introduced. |
| `92b22cbf1` | Revise weights; incomplete cache migration and boot integration. | F3 and the residual-holder/size findings. The old converted holders and Throwable-class memo are actually deleted; computing four method lookups per Throwable is simpler. The original boot call reads a dial absent from the old carried projection; later `0dc665873`/`558446356` address that observed failure. Existing concurrency tests are useful but lack zero-weight contracts and deterministic listener completion. |
| `558446356` | Boot repair is too broad for retained worlds. | F5; also adds duplicate projection-cache retention. No regressions added. Initial per-call file stamps add measured work; `af7ba85b7` removes that cost rather than adding another invalidation system. |
| `af7ba85b7` | Key simplification appropriate under archive immutability; F5 remains. | Population-value keys correctly let changed content miss. URL key is justified only by immutable archive provenance. No new connection state is captured. Retire the obsolete stamp and applied test workaround together; add distinct-archive population and retained-world regressions. No test changes in this commit. |
| Malli `43c6dca8` | Revise fast-path eligibility. | F4. Positional checking of genuinely atomic `:cat` children, delayed argument vector creation, and skipping an absent guard are appropriate producer optimizations. Failure reports and actual guards still receive vector args. The tests cover ordinary sequence shapes and direct refs but miss aliased refs. The original regex path remains necessary for real sequence operators; it is not dead code. |
| `804440215` | Original landing was not a valid armed failure path; later source repairs one defect. | F8 was fixed by `715c23669`; installation unproven here. Per-child admission fixes the unarmed-call window through the existing owner. Two-definition restriction, duplicated reader classification and per-child capture cost remain. Existing new test should catch the original contract mismatch when run armed; add namespace-switch/require and refusal-restoration cases through settlement. |
| `65d0ca8e0` | Partial cost correction, not complete honest timing. | F6. Deleting the process-wide profile-window prose is sound and reduces the source. Allocation/acquisition fields are copied into settlement only when the evaluator emits them. Acquisition is not added to transcript emission despite the replacement landing's broader wording. No tests added; producer-to-receipt coverage is missing. |
| `d6942b298` | Reply composition improves; revise bounds ownership. | F9. Existing lexer reuse fixes a comment swallowing later map keys, and renderer replies now keep handle/time/output. Native Malli `explainer`/error paths are preferable to manual bound checking. New tests cover formatting, but not branch-owned bound changes or actual recorded costs. Keep one reply composer; remove the independent delayed policy cache and obsolete applied patch. Source +88/−19; tests +44. |
| `0dc665873` | Appropriate scoped boot fix; no additional defect found. | Uses the compiled config that boot just applied for cache budget, caps, workload settings and service startup. Deletes rereads of the older carried projection. This is a value passed to its consumer, not an extra config store. Needs old-store/new-dial boot plus nondefault overlay regression and proof that recorded config equals served settings. No tests added. |
| `fa6f8e8b0` | Accept catch broadening; require stronger proof for arming skip. | F10. Catching `Exception` at the three existing recovery boundaries includes non-`ExceptionInfo` launch failures and preserves causes. `record!` removes duplicated record construction. Matching source bytes is useful but insufficient evidence for all arming inputs. The new pure helper test does not establish runtime convergence or recovery. Source/script net +64; tests +19. Recovery testing can inject a failing launch/read boundary or use a bounded isolated operator scenario; absence of permission to redefine Vars is not proof that no regression is possible. |

## Verification and landing limits

The review's strongest new findings, F1, F3 and F4, are deductions from the pinned implementations and dependency guarantees. They have explicit unexecuted regression scenarios. F2 and F8 additionally have recorded failure evidence; neither is relabeled as a newly reproduced incident. F10 is deliberately a proof gap with a concrete interleaving, not a claimed observed failure.

Existing landing measurements are mixed: the one-commit landing reports writing forms at 201 ms p50 and two commits; contracted definitions at 2,645/815/753/864 ms; and trivial forms at 176–193 ms p50 versus 144 ms earlier under different concurrent load. It explicitly leaves that regression unexplained. These figures cannot serve as clean parent/change proof for any one of the combined commits. The Malli measurements are bare-JVM figures; the cache stamp-removal “after” values are estimates from components, not a measured installed full path. Boot's reload savings were also unmeasured in its landing. No additional memory or performance result was obtained here.

This audit changes zero source or test lines. It leaves runtime acceptance, the corrected custody regression, and the targeted missing proofs with the named owners. It creates no task records and does not edit the issue schedule or ownership ledger.
