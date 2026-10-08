---
title: Landed-work review — landed-review-sol
type: review
date: 2026-10-08
status: changes-required
---

# Landed-work review — landed-review-sol

Reviewed against `CLAUDE.md` and `owner-rulings.md`, both read in full before the review.
This is a review of executable changes and their tests, with live measurements, not approval of the designs.
The ruling is reasonableness: do not reject a useful operation merely because it crosses an aspirational number.

## Verdict and scope

**Fix forward.** The deletion and cost lanes contain useful, substantive changes. They do not establish
REPL equivalence or an economical model/test path. The most urgent findings are the reader consuming the
wrong forms, mutation of an error value during reporting, and incomplete coverage of the observation boundary.
The model fixture allocates 945 MB and a first one-test check 2.89 GB. Those are unreasonable for the work performed.

I ran `git log --oneline -150`. The initial review window is `20e268a42..5a4b9ad63`, including the first commit;
I inspected executable diffs, their first-party callers and tests, and followed later fixes rather than judging
superseded intermediate behavior as current. Design/audit/task-only commits in that window are authorities or
evidence, not installed implementation. The matrix below covers every named slice and the other executable
landings in that window. The moving tail reached `3168005b3` during the review; that stale-caller fix is separately
identified below. No claim that a document-only landing installed its proposed API is made.

Default was PID 1687, start `2026-10-08T03:10:37.955Z`, running committed archive
`4b4caf1421cc3db8e48069e6e406f03505d07ecc`. Its implementation includes the requested slices.
All live correctness forms used MCP SCI agent `landed-review-sol`, namespace `scratch.landed-review-sol` on
its own branch. Existing, already-loaded `seon.pass-probe` orchestration ran through MCP JVM mode with explicit
`"default"`; its measured MCP operations are SCI operations on its newly created agents' branches. Its model
provider was the local fixture provider. No paid call, reload, host definition, default reset or worktree was used.

**Health first:** initial default health was faulted: six open faults, 37 errored receipts, 12 stale Vars and
22 unowned namespaces. Readiness had no missing layer. Later status had seven open faults, 23 unowned namespaces,
245 platform threads and eight regressions. The lazy-eval probe below exposed the missing-origin error.
Aggregate process profile maxima are not timings of this review's operations; I did not attribute their
hundreds-of-seconds totals to a new ordinary pass.

## Concrete wrong or fragile behavior

### R1 — P6 does not preserve reader semantics

On default, sending `#_ #_ 1 2 3` as one MCP SCI request returned **two evaluation replies, 2 and 3**.
Plain SCI evaluates only 3. A last-value-only differential test cannot detect this: its final value is also 3.
Discarding an effect is a semantic operation; evaluating the extra form is not cosmetic.

The producer is `src/seon/sci/forgive.clj`: `scan` treats reader prefixes as counts, `step` at 315–322 gives
SCI separate span readers, and `no-value?` at 390–398 drops anything beginning `#_` without asking the live
reader how far a discard consumed. The scanner is therefore a second parser for top-level reader semantics.
P6 really deletes the old structural-pass functions, but still calls `edamame/parse-string` for conditionals
at 397. Its claim to delete hand cursor scanners does not mean no hand parser survives.

A second, independent failure is:

```clojure
(seon.sci.forgive/segments "true\nfalse\nnil\n42" :reply)
;; {:seon.batch/segments
;;  [{:seon.batch/source "42", :seon.batch/comment "; true\n; false\n; nil"}]}
```

This is a direct probe of the installed read-stage producer, not a claim to have sent those literals through
the model fixture. `literal-line?`, 94–114, accepts numeric/keyword/string/qualified-symbol starts but omits
the three bare literals. It contradicts the owner rule and P6's own bare-literal claim.

**Fix:** let SCI's advancing reader own form/discard consumption; forgiveness should change text only under
the approved prose/repair rules. Preserve the three literals in the reply classifier. RP-11 schedules the
live-cursor change, but its listed alias proof does not cover either failure. Add the exact all-receipts and
discarded-side-effect proofs proposed below. Do not add another parser special case for each reader macro.

### R2 — P7a attributes repairs incorrectly and stores printed repair data

Installed stage probe:

```clojure
(seon.sci.forgive/segments "(inc 1)) (inc 2)" :code)
;; segments:
;; [{:seon.batch/source "(inc 1)"}
;;  {:seon.batch/source "(inc 2)",
;;   :seon.batch/repairs [{:seon.batch/line 1 :seon.batch/dropped ")"}]}]
```

The extra closer belongs after the first form. `owned`, `forgive.clj:413–423`, chooses the last segment whose
start *line* precedes the repair. Same-line forms cannot be distinguished. Its repeated `take-while` is also
O(forms × repairs), although the measured finite case is still reasonable: 100/400/1600 repaired code lines
took 1.66/5.04/47.23 ms, with exactly that many repairs and segments. Do not block on an invented microsecond
ceiling; use an ordered span/repair merge while fixing the ownership error.

`src/seon/turn.clj:3019–3027` prints repair vectors with `core-text`; the new
`resources/seon/schemas/seon.cluster.eval.edn` repair attribute is a string, subsequently spliced into replies.
This newly stores a bounded printed copy instead of the repair value. The shown-copy rule for observations
does not justify replacing structured diagnostic facts with clipped text before the final printer.
RP-12 already schedules repair data; amend its proof to cover more than one same-line form and round-trip
the complete repair value. The existing P7a multiline example does not prove attribution in this case.

### R3 — test-stack shortens output by destroying its input

Actual MCP SCI probe, with a local exception only:

```clojure
(let [e (ex-info "review-stack" {}) before (count (.getStackTrace e))]
  (clojure.test/do-report
    {:type :error :actual e :expected :value :message "Review probe."})
  [before (count (.getStackTrace e))])
;; [513 0], :ms 2
```

`src/seon/sci/eval.clj:290–298` calls `.setStackTrace` on every cause and then delegates to
`clojure.test/do-report`, which prints by its own reporting path. This changes the error retained elsewhere,
violates preservation of the original value, and adds an unbounded `iterate .getCause` walk without cycle
detection. I did not construct a cycle on default. The landed test checks a short string and cannot catch
either mutation or nontermination.

**Fix:** return/report error data through the value printer, preserving the Throwable; general cycle handling
belongs there. **Already covered: PR-04**, explicitly naming frame erasure. Extend its proof to assert original
frames unchanged and a finite representation for a cyclic cause graph, in a suitable isolated test.

### R4 — lazy-observe needs the positionless-definition and delivery proof

```clojure
(map (fn [_]
       (eval '(defn lr-lazy {:malli/schema [:=> [:cat] :int]} [] 7))
       9)
     [0])
;; reply: (9), :ms 0, no diagnostic
[(vec result/eb19b2e2d86ad) (lr-lazy)]
;; [[9] 7]
```

The value survives this particular example. Subsequent runtime status exposes a new fault from
`seon.sci.arm/write-event`: output at `[:seon.batch/origin :line]` expected a positive integer, got nil;
caller `seon.sci.observe/observe`. On the running archive, `arm.clj:69` copies origin unchanged.
`observe.clj:55` expands observation across printing, making this boundary relevant. This review does **not**
claim value replacement from this probe: it proves a recorded producer failure not delivered in that reply.

**Fix:** represent absent source location as declared data at the write producer, preserve the value, and
deliver any remaining observation failure in the same envelope. The existing task
`seon.sci.arm/a-def-without-line-metadata-refuses-its-origin` describes the producer. E-P3/RP-7 are broad
coverage, but there is no explicit scheduled proof of this lazy-positionless delivery case. Foreign edits
to `arm.clj` and its schema appeared while reviewing; they are not installed evidence and were not edited.

### R5 — S1-05 masks read refusal and evaluates a lookup helper

At `src/seon/sci/eval.clj:2055–2057`, `schema-documentation` recognizes a refused `db/pull` but returns
`documentation-unavailable` whenever the refusal lacks `:seon.schema/form`, which ordinary refusals do.
This erases the actual failure. This finding is from the complete branch logic, not an injected live storage failure.
Return the refusal unchanged; distinguish it from a successful missing lookup.

At 2079–2081, namespace documentation calls `sci/eval-form` on generated `(ns-publics 'namespace)`.
Use SCI's namespace API with the supplied context, as the same owner already does at 287–288, rather than
silently evaluating a second form to answer a read. Test both a namespace's public names and a refused schema
lookup as data. S1-07a/S1-09 cover the larger lookup cleanup but do not name the refusal regression.

### R6 — stale callers are still wrong on the installed archive; a fix landed during review

Separate SCI requests on this review's branch:

```clojure
(defn lr-g {:malli/schema [:=> [:cat] :int]} [] 1)
(defn lr-h {:malli/schema [:=> [:cat] :int]} [] (lr-g))
(lr-h) ; 1
;; next request
(defn lr-g {:malli/schema [:=> [:cat] :int]} [] 3)
;; next request
[(lr-h) (lr-g)] ; [1 3], should be [3 3]
```

The installed regeneration owner is `eval.clj:2849–2908`; earlier callers retain an earlier Var.
PB-01's sharing/cache tests do not establish cross-pass semantic preservation. **F0 already covers it**.
While I reviewed, `3168005b3` landed: it preserves an own Var when its root was transferred and carries the
base's current row. Its new multi-pass MCP rows and an old test that must turn red are meaningful proofs.
The diff identifies a pre-PB-01 cause, so I do not blame PB-01 for introducing it. I have not reloaded default
or claimed the new fix passed a live installed probe. Root must adopt/reload, then repeat the exact example.

## Measurements and reasonableness

Allocation is decimal MB, from `seon.pass-probe`'s allocation counter window; it is not retained heap.
The shared JVM was active. Its process-wide counters and inclusive armed-call profile can include concurrent
work; no exclusive allocation attribution or additive interpretation of nested timings is claimed. These
are actual finite observations, not a statistically established distribution or before/after regression.

| Operation | Observed result | Judgment and likely owner | Ledger coverage |
|---|---|---|---|
| SCI arithmetic `(+ 1 2)` | 3; evaluation `:ms 0` | Reasonable; this excludes entrance/settlement overhead | O |
| Ordinary fixture MCP pass | 46.44 ms, 58.59 MB; next ordinary 46.27 ms, 74.16 MB; one own commit each | Reasonable latency. Allocation merits reduction but these observations alone do not make the 40 MB target a blocking cap | PB-02b, PB-04, RP-10 |
| One-line fixture `defn` | 194.87 ms, 276.40 MB; 37,581 armed calls, one commit | Latency reasonable; allocation unreasonable for one definition. `turn.clj:3554` settlement accounts for 106 ms inclusive; projection/admission/reconstruction owners remain | PB-02, RP-6/RP-10; cost landings are improvements, not completion |
| Pass immediately after that `defn` | 83.66 ms, 190.40 MB, 137,762 armed calls; two attempts, one own commit | Unreasonable repeated work after one small change, despite acceptable wall time. `eval.clj:2849` regeneration and `:2959` fork path are candidates; retry is included | PB-02/PB-02b |
| Fixture model pass | 741.70 ms, 945.00 MB, seven attempts/seven own commits, 104,995 armed calls; one local provider request, 0.321 ms provider | **Unreasonable allocation and bookkeeping** for the small answer. `turn.clj:4442` call path, `cluster/prompt.clj:403`, `render/web.clj:2459`, `render.clj:891/900` evidence. Prompt/acquisition ~315–321 ms; render evidence ~242 ms over 14 calls, inclusive | C0, PB-02b, S4-02b/S4-06/S4-07, RP-10/RP-14. EN design exists but its slices are absent from this ledger |
| First check of one named trivial test | 1644.79 ms, 2894.47 MB, three own commits; member executed | **Unreasonable.** `test.clj:1834` clean world 701 ms, `test/run` 1432 ms, member bounded result 86 ms, selection 33 ms. The test body does not explain the cost | F1–F3 explicitly; EN-6/7 need reconciliation, not a duplicate schedule |
| Repeated check, cached result | 105.60 ms, 146.91 MB, one commit; member **not** executed | Wall reasonable; 147 MB to reuse a result is unreasonable. Do not report this as a fast executed test. `test/select` 8 ms, run 9 ms, settlement ~77 ms | F1–F3/RP-10 |
| `(doc seon.db/pull)` | 273 estimated reply tokens, `:ms 6` | Reasonable for this multi-arity contract; duplicated union detail can improve | S1 lookups |
| `(dir my.agent)` | 501 estimated reply tokens, `:ms 28`, eight functions | Reasonable one-off directory, although world-member destructuring is noisy. No new token cap warranted | S1-07a |
| `(help)` | 1208 estimated reply tokens, `:ms 30`; value itself flagged ~1111 tokens | Size is reasonable for a one-off manual, **unreasonable if repeated as protocol**: obsolete Markdown/steps/session-end guidance spends attention teaching wrong behavior. Fix content before a numerical quota | H-01, S4-02b |
| `(/ 1 0)` | 69 estimated reply tokens, `:ms 0`, error data with two Clojure frames | Reasonable; does not justify erasing arbitrary Throwable stacks | MS-03, PR-04 |
| Eight newly created idle agents | post-GC heap 725,376,272 → 733,738,384 B; delta 8,362,112 B, **1.045 MB/agent**; eight complete arithmetic replies | Reasonable. Shared-heap delta after explicit GC, not a heap-dominator proof. The ~1.916 s harness includes two before/two after GCs and creation | MEM-03/MEM-04; no new per-agent restriction |
| Process memory | observed RSS 2,389,632 KiB (~2.45 GB); heap-live initially ~948 MB, post-GC probe ~725–734 MB; configured max ~11.0 GB | Shared process size is not per-agent retention. Current RSS reasonable on this machine; configured ceiling still needs the 16 GB-machine proof | MEM-01 |
| Reach-declarations cache | initial 153.55 MB/0% hits; later **204.74 MB, 40% of cache, 0% hits**, ~2.015 s cumulative load | **Unreasonable retained derivation with no reuse.** `test/runner.clj:792–840` keys whole-program rows by connection identity/generation; sibling branches miss | Existing task `seon.test.runner/reach-declarations-misses-on-every-branch`; no explicit ledger row: LR-CACHE below |
| Boot readiness, existing boot timeline | listener 174 ms; store 233; published source 11843; branch 12023; connection 12025; config 12429; ctx 13612; graphs 14552; web/ready **14560 ms** | **Unreasonable unexplained readiness cost**: 11.610 s between store and source dominates, not the 8 ms web tail. `cluster/boot.clj:180–197` dependency analysis/changed-source publication. These phase facts do not distinguish the two producers; measure them before prescribing a fix | No boot-cost row; LR-BOOT below. No disruptive restart was made |

Plain stage scaling with 100/400/1600 reply lines took 0.77/2.21/7.46 ms in the finite JVM probe; the
repair-heavy code case is recorded under R2. Those are reasonable and do not justify restricting model input.

The model fixture's 696-token measurement is the request prompt estimate, not its completion size. The
one-test probe's `error? false` is an envelope observation, not by itself proof of a green member. Execution
is distinguished using the probe's `member-executed?` and named stage counts.

## Slice-by-slice diff and proof assessment

“Keep” means the inspected change implements its narrow claim without a new demonstrated correctness defect;
it is not a claim that I reran every historical test or that all broader rules are already implemented.

| Slice / commits | Code, deletion and proof assessment |
|---|---|
| P0 `c816e3fde` | Useful MCP-versus-SCI table, real entrance. Weak oracle: only last reply/value is compared; swallowed failures or extra earlier forms can pass, especially nil expectations. R1 demonstrates the hole. Compare every outcome and effect, not only the last value. |
| P1 `ded5e35de` | Native veto and refusal helper really deleted; actual MCP mutation examples exercise SCI. Keep that deletion. Persistence/settlement of unmap is a separate still-open REPL issue, covered by RP recording changes; removing a veto alone never proves the next pass preserves deletion. |
| P2 `e21fc4674` | Constant SCI reader options and pass binding of data readers implement the claim; fabricated-response veto deleted from both paths. Record/tag tests are meaningful. Reader-equivalence proof remains incomplete under later P6. |
| P3 `f9dc29814` | Ending namespace captured in `finally`, retained across throwing evaluation. MCP before-throw/next-call namespace test is a real proof. Keep. |
| P4 `0667ee448` | Deletes refusing-root; malformed authored contract leaves callable function and a diagnostic. Positive malformed-contract proof is useful. Valid-contract test's `not= "1"` can pass on an unrelated failure/missing value: require exact input refusal and prove body was not entered. |
| P5 `d78473124` | Output now goes through the value printer and offers a retrieval. Existing huge-output test invokes continuation, useful. Reply fields still get separate budgets; output is stored/requeried as text. PR-07 explicitly owns the remaining one-budget/live-output change. |
| P6 `c98ade404` | Removes old structural machinery, repairs/recognizes text cheaply, but implements prefix/token grammar itself. R1 is a concrete correctness failure. No new long-lived atom; local scan mutation is per-call accumulation, not a rogue cache. |
| P7a `7569761aa` | Repairs really travel to receipts/replies. R2: printed-copy storage, same-line misattribution, O(nr) assignment. New schema is string rather than repair data. RP-12 plus proof amendment needed. |
| P11 `666c767f7` | Renames evaluation print facts rather than weakening dial invalidation. Stored-row assertions prove producer separation. Median of 35 live passes is a noisy performance test, not deterministic proof of zero base derivations. Keep behavior, replace that regression oracle with counted misses under real set!/evaluation. |
| PB-01 `6191eace4`, `48191071f`, `4b4caf142` | Central `seon.cache` shared base replaces revision LRU-of-four; second commit correctly removes recorder from shared key and supplies it per fork. No second cache owner. Base still rebuilds on loaded commit; PB-03 deliberately remains. New-agent/context identity tests prove sharing, not cross-pass caller semantics (R6). Whole-context regeneration remains PB-02b. |
| M8 `76daa8fd5` | Existing namespace is written only for resolver changes; keeps authored metadata rather than replacing it with context snapshot. Real MCP test checks stored metadata and an alias change. Keep. |
| printer-code `a423b8b29` | Deletes `data-text`, `one-form?`, diagnostic-line; shared printer op stream now gives HTML string characters, basis-bearing links, render-text cuts. Real improvement. Terminal `bounded-text`/datum path and separate diagnostic quota remain, explicitly PR-09/PR-01. New `ends-in-comment?` is a hand lexical scan at reply composition: remove with a structural envelope, not another grammar extension. |
| printer-fix `131082e0d` | Hard print-length/level, including zero, enforced before whole admission; fixes core off-custody path and deletes render-prepared. Outer render-ai/html contracts were still wrong until out-fix. Small/zero-bound printer tests are meaningful; envelope splicing still relies on arbitrary shown text. |
| printer-web `13b850d30` | Carries basis, cursor and text/plain route through actual HTTP handler. Regression follows the whole-text link and checks status/type, but only asserts body nonempty. It does not prove exact content, correct suffix, or stable bytes after an intervening write; strengthen it. Browser paint was not independently tested here. |
| out-fix `9c88ae5b8` | render-ai/html now admit db-free unit, presentation failure selects compact data. MCP println test confirms dig form and absence of missing-database diagnostics. Selection is inside printer owner, not a new outside clipping layer. Still partial PR-07, not completion of one reply budget. |
| S1-01 `f50ef6059` | Unwired agent.loop, schema/tests deleted, references converted. This deletes a second context builder rather than installing it. Keep. |
| S1-02 `438328b34` | Namespace directory reads one reverse pull and parses arglists using aliases, no per-function documentation-contract call. Owner-data tests meaningful, but not a full MCP directory proof. Large host arglists remain, measured above; S1-07a owns replacement. |
| S1-03 / s1-03 `d11dbe2b6` | Synthetic refers move to analysis; stored namespace keeps authored refers/source. Test asserts internal analysis/source selection, not the full saved entity entrance. Later linear batching fix `94affe518` is needed and landed. |
| S1-04 / doc-fix `7876f594e` | Explicit viewer SCI context, Var metadata and authored-reference classification solve installed doc lookup rather than inventing a second database. Real SCI alias/library cases. Keep narrow fix. No separate commit named doc-fix in the reviewed log; these doc changes and S1-05 are the relevant landed owners. |
| S1-05 `28c141d56` | Adds schema and namespace docs, with real data tests. R5: hides read refusal and uses a second eval-form for public names. Fix those paths; source is not installation evidence for future lookup APIs. |
| S3-01 `2bf3e10db` | Producers/consumers use origin :outside/:model; generated rows omit it. Old author schema remains explicitly for historical rows, not a missed new writer. Test drives SCI, JVM and fixture-model entrances and queries stored origin; real proof, though its manual bounded turn-driving should eventually follow one entrant. |
| S2b-1 `84a3c5e4d`, deps `a4d438dc6` | Deletes Parinfer use, forms/read-code/unread/refused path and converts preview/opening callers. Explicit read-stage values and continued read errors reach SCI/JVM. Tests include real receipts, though many source-string assertions encode its then-current policy. P2/P6 intentionally supersede refusal/repair policy; do not demand those retired restrictions return. The structural reader was real then and replaced by P6's hand scanner, not a complete one-reader solution. |
| redef-arm `b391c0ef9`, `ca00cfd7e` | Authored changed contract takes precedence; registered-in-same-form schema uses arming projection. Fixture model tests check 103 for changed arity and stored contract references. Keep. `arm.clj:56` still prints contract to `:seon.fn/spec` for a downstream read; known printed-copy task/PR-25 remains. Arming projection is still rebuilt per caller for schema-changing forms. |
| lazy-observe `b5ff51e4c` | `with-ctx` around observation thunk makes lazy realized definitions observable. Real stored-definition/redefinition proofs, but misses positionless eval; R4. No new atom or alternate evaluator introduced by this tiny diff. |
| reader-pos `e24fda839` | Original reader line/column restored; test checks metadata positions. P6 replaces reader-at with span-reader using direct dependency constructor; brittle to dependency layout, despite pinned version. One real cursor is simpler. |
| test-stack `5295011dd` | Its short output claim is true; implementation wrong by R3, proof misses mutation. PR-04. |
| pass-cost `e9002e761` | Reuses registration defaults, selects namespace range instead of whole snapshot, derives terminal ordinal once, uses held evaluation identity. Removes actual repeated work. Existing refusal assertions narrowed to data. Does not solve the measured model/test allocation. |
| defn-cost2 `97faccb88` | Requested-function arities, relevant pull batches, declaration-only shapes, Malli compiled-storage reuse. Deletes whole-population work in those calls. Counted owner reads plus value validation are valid performance proofs, not all end-to-end proof. |
| defn-cost3 `4c28e748f`, `4455e4917`, `bd63ce073`, `b20762d6b` | Incremental registries and written-attribute/owner selection, restores owning validation for declaration-only projection, seeks requested declared callers and identities. Keep simpler proportional algorithms. The bd63ce073 test of returned schema names is insufficient proof that final owning writes reject missing children; add public-writer negative case. Some caller tests compare two modes of the same function; existing independent expected-set tests matter. |
| runtime-analysis `94affe518` | Running line count + source chunks replace repeated prefix concatenation/counting. Actual spans recover exact original multiline forms. Strong small data proof of the transformation; linear complexity follows code. |
| publish-cost `7f1b86594` | Analyzer reach pulls only reached declarations; skips empty reverse query. Tests read real DB evidence, existing digest-change test remains. Prior namespace exclusions are inherited, not newly invented. Keep. |
| publish-cost2 `9e0f1cba8`, `bb4446294` | Reuses per-file schema dependencies, derivation world, task declarations/basis, previous entity rows; identity queries avoid materialization. Central content caches, no private atom. Names-only canonical-membership key removes retained forms. Tests check exact data and counted repeated derivations. Task content still traverses task attributes per lookup: proportionality limit acknowledged, not evidence of a new slow operation here. |
| contract-check-cost `139806d87` | Static source check stops loading checkout namespaces; read cache keyed by text, predicates resolved per batch, qualification moves to analyzer. Test changes predicate source and checks absence of namespace load. `any?` placeholders are explicitly compile-only: this proves Malli function-schema compilation, never actual predicate behavior/adoption. Production compilation must remain in the candidate gate. Printed round-trips in static checker are inherited and still violate printer/storage cleanup goals. |
| check-analysis `a9d1c4d33` | Scratch analysis joins shared dependency analysis; does not change correct runtime code merely to silence unresolved dependency Vars. Fixture checks dependency names/call metadata. Keep. |
| S5a reply `7b5535cf6`, fix `191627656` | Removes run-observation reread and automatic problem assignment; replies consume held saved receipts. Fix supplies JVM call situation, repairs phase-failure source shape and deletes producerless problems/id. Reply-versus-stored-history tests are useful. Refused-but-saved branch remains unproven, as the landing honestly states; do not mark total settlement proven. |
| S2a `25bb94be0`, fix `ae929e364` | Actual large deletion of outline/ledger/walk graph/problem panel, CSS, tests and prompt flag. Follow-up deletes orphan calibration and stale graph JS branches, completes touched contracts. Context is composed only when opened through the printer. Prompt-refusal regression added by S5a fix. Keep; DBG-01 still owns equivalence to a particular sent call, not this next-context preview. |
| Dependency join `1e61a3d44` | Moves shared dependency analysis into runtime-analysis-batch, removes duplicate downstream joins. Test asserts resolved dependency calls; real owner data though not complete MCP admission. Keep. |
| Acquired program `37d11f6a0` | Run reacquires current connection program before fork. Real armed-agent test writes by another agent then calls and docs that function; useful installed-path proof. Does not establish own-Var preservation across passes (F0). |
| Competency C1 `c6ae8875d` | Known solution goes red-to-green through MCP and real test runner; named identity runs while namespace request excludes long proofs. Useful. “Any error type” plus “some contract metadata” is weaker than the task's exact input contract; tighten the negative proof. No paid provider run here. |

The inspected atom additions are principally dependency SCI contexts/owned recorder and finite test/probe
sinks. Those are not evidence of an illicit second state owner. The defects above instead identify the
specific duplicate parser, printer/report path, stored printed values, and semantic special cases.

Dependency evidence: running archive pins SCI `ce0aa53b9bf35029e0bc27a962d799c196a37874`,
Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`, edamame
`196ea5e4eff390428d8cfd33661cc415a2eec76b`, Datahike `e5453d967b749794944dbdfe64b751519983ba59`.
SCI `core.cljc:334–340` forks the context env/generation; `:390–398` parses the next form from a reader.
These are real seams, not a promise that copying arbitrary Seon snapshots preserves Var identity.
Malli `core.cljc:3130–3135`, `function-schema`, compiles and verifies function-schema kind; it does not execute
the predicate or validate a sample function result. The static-check placeholder must be judged accordingly.

## Missing ledger rows and proposed amendments

These are **proposals for the owner**, not edits to another lane's schedule. Preserve existing IDs where a
row already owns the fix; do not create parallel work streams. Budgets below name work avoided and useful
behavior, rather than arbitrary token or execution restrictions.

| Proposed id / existing row | Change | Files | Proof to add or run | Budget |
|---|---|---|---|---|
| LR-READ, amend RP-11 | Preserve nested discards and bare true/false/nil; consume one live SCI cursor | `sci/forgive.clj`, `sci/batch.clj`, `repl_differential_test.clj`, `sci/forgive_test.clj` | Assert *all* receipt values for nested discard equal plain SCI; discarded side effect never occurs; fixture reply containing the three literals returns them | O(input + forms), one semantic read per form; retain measured small scan cost, no new input cap |
| LR-REPAIR, amend RP-12 | Repair ownership by source spans; stored native repair data | `sci/forgive.clj`, `turn.clj`, repair schema, P7a tests | Same-line two-form case places dropped closer on first form; many repairs survive store/pull exactly and printer alone elides | O(forms + repairs); ordinary repair-only producer remains sub-second, not a 47 ms failure by fiat |
| LR-OBSERVE, explicit RP-7/E-P3 proof | Positionless lazy definition and local delivery of observation errors | `sci/arm.clj`, `sci/observe.clj`, `batch.clj`, batch schema, `cluster/evaluate_source_test.clj` | Known missing-origin task: lazy eval-created def returns original sequence, function works, no origin refusal; deliberate observer failure appears beside value in same reply | No second evaluation/print; ordinary pass budget |
| LR-DOC, amend S1-07a/S1-09 | Preserve schema-read refusal; use namespace API rather than eval-form | `sci/eval.clj`, `sci/documentation_test.clj` | Refusal identity/data preserved through doc; missing schema remains unavailable; namespace publics returned without an evaluated helper form | One owning read, no additional eval; retain current sub-second lookup |
| LR-PROOF | Strengthen P0/P4/P11, owning validation, printer-web and competency assertions | Named test files in matrix; `schema_test.clj`, `db_test.clj`, `render/web_context_test.clj`, `competency_test.clj` | All outcomes; exact contract error and body-not-entered; zero base misses after print set!; missing child refused by checked writer; exact continued/whole text after intervening write; exact competency contract | Deterministic behavior/counts. Move wall-clock samples to evidence, no extra 35-pass regression tax |
| LR-CACHE | Share reach declarations by valid common content across branches, preserving entity-identity correctness | `test/runner.clj`, `test_reaching_test.clj`, pass probe | Existing task: two fresh branches of same program produce one derivation/hit; changed reach input misses and changes answer; unrelated writes hit | Eliminate branch-count multiplication of identical whole-program data; cache retained weight bounded centrally; first-check GB row must materially fall |
| LR-BOOT | Attribute the 11.610 s store-to-source interval; reuse unchanged dependency/publication work | `cluster/boot.clj`, `cluster.clj`, `fn/analyzer.clj`, operator measurement evidence/tests | Persist separate dependency-analysis and publication clock rows; unchanged-input committed boot avoids whole-program rederivation; changed input still publishes/adopts | No unexplained >10 s stage; cost proportional to actual changed inputs. Set a narrower numerical budget only after that measurement |
| EN schedule amendment | Import/reconcile EN-6/7 with F2/F3 and EN settlement cut with RP-10/RP-14 | `agent-loop-ledger.md` and owning entrant spec (owner only); implementation `test.clj`, `test/runner.clj`, `turn.clj`, `turn/batch.clj` | Executed trivial check and fixture-model row, transaction counts, retained original values/failures; cached check explicitly distinguished | One settlement, no GB transient for tiny fixture. Keep F1–F3/C0 cost ownership instead of duplicate rows |

PR-04 already covers test-stack; PR-01/07/09 cover printer budgets/fallback; PR-25 covers arming contract
text; F0 covers stale callers; PB rows cover context rebuilding; H-01 covers misleading help. These are not
missing rows. The cache task was already persisted by another review before this report; only its explicit
ledger placement is missing. A new task with a guessed identity is unnecessary.

## Reproduction and verification record

Measurement calls, using the already loaded helper (no source load):

```clojure
;; MCP mode jvm, agent landed-review-sol, cluster default:
(seon.pass-probe/run-mcp "default" "sci" 2 #{1} true)
(seon.pass-probe/run-model "default" 1 #{})
(seon.pass-probe/run-check
  "default"
  'seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings
  2)
(seon.cluster.boot/readiness
  (get @seon.operator.runtime/running-instances "default"))

;; MCP mode sci, same own agent/cluster:
(doc seon.db/pull)
(dir my.agent)
(help)
(/ 1 0)
```

MCP/result handles during the review: ordinary/defn rows `result/e35248e5f68a4`, model
`result/e9abc1a0f8c7d`, check `result/edf39b011dfd8`. These live handles are not durable evidence;
the forms and measured values above are. Querying a SCI result symbol in JVM mode failed to resolve its
namespace; I corrected that probe to SCI rather than treating the failed query as missing measurement data.
Reply token estimates are `ceil(text length / 3.2)`, the installed estimate calibration, including the
MCP envelope text but excluding transport metadata. They are estimates, not provider tokenizer counts.

Retained-memory probe used eight unique `landed-memory-N` agents. A local function requested GC twice then
read `ManagementFactory/getMemoryMXBean` heap usage; capture before, call installed `mcp-pass` for `(+ 1 2)`
for each agent, capture after, divide the delta by eight. `finally` retired each owned host through
`seon.cluster.host/retire!`, using installed `seon.pass-probe/agent-world`. All pass-probe helpers likewise
retire their generated hosts. This measurement changes no shared program definitions. The review branch's
scratch definitions are disposable and are not proposed program entities.

Own-branch cleanup used `(my.agent/retire-branch! {:seon.store/branch
(:seon.agent/branch (my.agent/branch))})`. The reply was
`{:seon.error/message "run transition refused: admission-closed"}`, with MCP `isError true`.
Thus retirement was requested, but a successful unlink/exit is **not verified**. The installed self-retirement
path (`my/agent.clj:151–165`) closes admission before its requesting run settles, and the transition rejects it.
Preserve this uncertain resource for root rather than force-close a connection or retry a disposed agent.
Add that same-run settlement/unlink proof to branch-lifecycle work; closing admission must block later work,
not refuse the already-owned retiring run. This is an additional discovered health defect, outside the landed
slices reviewed here.

Lint: `clj-kondo --lint src script test --cache false` completed in **6383 ms, zero errors, 1460 warnings**.
Warnings were not hidden or called zero findings. Full output was retained during work at
`tmp/landed-review-sol/lint.txt`. This is static lint of the shared tree, not proof that its foreign edits
are installed. Candidate verification uses committed HEAD plus this claimed Markdown path only.

Foreign boundary: initially `src/seon/sci/eval.clj`, `test/seon/repl_differential_test.clj`,
`tmp/test-data-assertions/proofs.edn`, `reference-code/http-kit` and untracked research/source paths were
changed outside this lane. Stale-caller subsequently committed. Later uncommitted
`src/seon/sci/arm.clj` and `resources/seon/schemas/seon.batch.edn` belong to other work.
No such file was changed, staged, restored or loaded by this reviewer. The full historical behavior suite
was not rerun; no absence of red tests is claimed. In particular the stale-caller landing names nine
pre-existing acquisition reds, and S5a's refused-but-saved case remains unproven. This documentation-only
slice's required test is exactly
`seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings`; it does not certify all reviewed
implementations. Root owns the broad test round and installed re-probes.

No source/test code changes: net source/test lines **0/0**. This report is the only proposed path.
Commit/gate outcome and owned-resource release are reported with the delivered commit, without amending it.
