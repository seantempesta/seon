---
type: independent-review
status: ready after fixes
created: 2026-09-23
reviewed-commit: 271b159d6
scope: docs only; error constructor manifest and offline scripts
---

# Independent review of the error constructor cut

The system already constructs flat observations and derives Throwable evidence at one leaf. Keep that leaf and each producer's declared output; the smallest change is an additive arity plus substitutions whose complete values are unchanged.

**Verdict: ready after fixes.** No P0 found. The existing owner is sufficient; no redesign or second constructor is justified. Do not begin the mechanical conversion until P1-1 and P1-2 below are incorporated into its admission/proof contract. This is static approval of a design, not evidence that the proposed code loads or runs armed.

## Basis and verification boundary

Reviewed `docs/research/agent-platform/error-constructor-cut-manifest-2026-09-23.md` at `271b159d6`, also the observed HEAD on `refactor/agent-platform`. Read root `AGENTS.md`, B3 §2a and §5 rows 1–3, `src/seon/error/refusal.clj`, the census/preview/prose-preview scripts and manifest generator, the existing constructor tests, representative callers and their schema declarations. Applied the data-oriented Clojure and testing skills as review guidance. History inspected includes `a86e93e21`, `ed2e1a6b6`, and `0d6eef939` through the current source/history trail.

No JVM, system status request, runtime evaluation, test, publication, production edit, foreign-session interaction, worktree, or push. A native Babashka **read-only census**, with the script's output-writing tail removed, reproduced the retained JSON exactly. Python independently reconstructed the preview in memory and compared it with the stored candidate files and diff counts. Neither operation ran a project namespace or wrote its evidence files. An initial shell yield failed to surface its final output; the same read-only analysis was repeated to obtain the result, not counted as independent evidence.

Observed census: 133 discovered files, 460 sites, JSON equality true; 3.359 s wall (script reported 3.3415 s). This exceeds one second because the explicitly requested inventory parses every candidate source byte, O(total candidate bytes), outside the agent execution path; no armed function ran. Keep this offline and reuse the recorded inventory when inputs are unchanged. Other returned shell/read operations were sub-second; no memory or runtime-performance claim. Source/test line delta of this review: **0/0**; only this review document is owned and committed.

## Findings and concrete changes

### P1-1 — The preview is not yet a refusing, replay-safe implementation script

`tmp/error-constructor/preview.py:12` selects every eligible row, including HELD rows; this is correctly labelled a measurement preview in manifest §8, but it cannot be the implementation entrance. It checks the old span (`:18`), not the recorded whole-file digest. It recomputes the digest of whatever file it reads (`:33`) instead of rejecting a changed basis. A same-length edit outside the span can therefore pass. `census.clj:11` consults only unstaged `git diff --name-only`: newly staged or untracked files are not captured by that ownership check. Current hard-coded holds happen to cover today's affected dirty paths; that does not make the check general.

Admission also assumes more than it proves. `census.clj:31–54` neither demands unique literal keyword keys throughout the map nor resolves the diagnostic aliases from the namespace. A computed remainder key evaluating to `:seon.error/throwable` would bypass the raw-Throwable exclusion and change a raw observation into consumed/derived evidence. A computed key colliding with a header can also change duplicate-key behavior when moved into a separate remainder map. The observed 311 eligible rows have literal keyword key spellings, so these are script admission defects, **not a claim that a current row loses data**. Reader-context admission is similarly a small blacklist, not a positive statement that an expression position is safe. The require insertion uses substring searches (`preview.py:24–29`), not the namespace form; it should not infer dependency availability from a docstring or unrelated text.

**Change:** finish one small, offline, path-selected rewrite entrance using the existing rewrite-clj tree. Require an explicit acquired-file list; compare whole-file hashes and exact spans; refuse held/staged/untracked drift using current porcelain status plus the ledger; admit only the documented expression shapes and literal, unique, resolved keys; check the actual require/alias. Keep unsupported rows unchanged with a named reason, rather than guessing. No production parser, registry, or second runtime mechanism. Prefer narrowing admission over adding general Clojure interpretation.

Define replay precisely: identical inputs produce identical output; already converted files produce a no-op after a fresh census; replaying a stale manifest refuses. Today `preview.py` rerun against unchanged originals is deterministic, but against converted files its old span assertion fails. A fresh census removes converted maps, while stale files in `preview/` are not cleared when a file has no chosen rows (`:34–35`). Consume only the current patch/output roster. The prose script likewise uses `.index` on the removed definition and fails after application. Document these refusal/no-op distinctions and include a small offline demonstration before applying edits. This reviewer did not execute a rewrite test harness under the no-tests instruction.

### P1-2 — Prove values at the new armed boundary, including intermediate maps

The syntactic value argument is sound for the observed admitted maps, but it is not an armed equivalence proof. Literal construction gains a new contracted call; the four-argument arity then calls the contracted one-map arity. In `src/seon/error.clj:553`, the converted map is an intermediate value subsequently extended by `cond->` and overridden by `merge`. In `src/seon/config.clj:122`, domain evidence is conditional. Constructor validation now happens before surrounding additions/overrides; a complete final producer value alone cannot prove that intermediate admission succeeds.

Manifest §6 names appropriate tests and explicitly leaves execution pending. Make its acceptance requirement concrete: compare complete old/new values under the **same acquired projection with both arities armed**, assert each supplied `at` is identical and evaluated once, and exercise the actual converted producer. Include absent message, empty remainder, conditional evidence, computed message, post-construction `assoc`/merge precedence, and ex-info data plus original cause identity. Preserve the exact producer alternative and the schema explicitly supplied to recording; do not replace unions with base or infer identity from a shape match. E0 must include at least its `prepare` intermediate-map case, not only synthetic constructor examples.

The existing `constructor-output-keeps-the-producing-contract` (`test/seon/error/refusal_test.clj:157`) is useful but only wraps the old map entry in synthetic `:x/y-error`/`:x/z-error` contracts. Extend it for the new entry and retain actual producer coverage. The armed cost of two constructor entries is also unmeasured; use the manifest's parent/child probe and profile both entries before accepting a performance claim. This is a required proof, not a diagnosed runtime regression.

### P2-1 — Tighten completeness and reproducibility claims

The census is complete for its stated lexical subset on today's bytes, not for every possible Clojure error construction. Discovery requires the literal text `:seon.error/at`; namespaced-map shorthand, alias-qualified spellings and computed/assembled maps are outside that method. A source-only search found no actual namespaced-map constructor omitted in the inspected source/script/test Clojure files (the hit in `html_views_test.clj:150` is a string assertion). Keep the lexical scope explicit and list assembled constructions as outside scope; do not promote “460 sites” into “all error producers.”

The three executable scripts embedded in manifest §8 exactly match their tmp copies. `write-manifest.py` is a document generator, not the conversion script; its template lacks the subsequently added shape-count table and is not a byte-for-byte reproduction of the final Markdown. Either update that generator or label it as an earlier drafting aid; never run it as a final-manifest refresh that silently drops reviewed content.

### P2-2 — Treat slice size and platform proof as admission, not completion

E0's 44 pre-regression additions plus ≤40 proposed test additions is a credible ≤84 budget. Each later free file is ≤74 generated additions. These are measured caller diffs plus **unwritten proof budgets**, not confirmed final slice sizes. Stop/split at existing definition boundaries if the actual tests push additions over about 100; do not trim assertions to meet the number.

All listed subsequent proof namespace paths exist. Their names alone do not establish reaching coverage. In particular `script/seon/operator.clj` cannot be proven loadable by a pure constructor test: its platform proof belongs to the orchestrator and remains explicitly outstanding. Retain the manifest's held-test deferral and report exclusions as unproven. No lane runs a cold gate.

## Census and ownership, checked now

All 101 per-file metric fingerprints match the current input bytes; the refreshed native census is equal to the saved 460-row JSON, including its HELD flags. Counts independently reproduced:

| Shape | Total | Eligible FREE | Eligible HELD |
|---|---:|---:|---:|
| L+M | 406 | 210 | 81 |
| L−M | 44 | 10 | 1 |
| D+M | 10 | 4 | 5 |
| D−M | 0 | 0 | 0 |

By tree: src 353, test 102, script 4, bin 1. Four-key sites: 416, of which 339 are src. There are 311 eligible sites; 224 FREE and 87 HELD. Eligibility and ownership are separate.

`git status --short` confirms the five dirty production paths `src/seon/{cluster,fn,instrument,issue,test}.clj` and the census-bearing dirty `test/seon/{instrument_test,render/web_test}.clj` are all HELD. Other dirty cluster adoption/reload/store and test admission-digest files are outside this literal-site table, not falsely FREE. The CURRENT HOLDS ledger (`tmp/orchestrator/file-ownership.md:5–23`) supports clean-but-HELD db, flow/await, cluster agent/process/source, turn, maintenance/schedule and dev/mcp. Its older running rows are explicitly stale. Dirty dependency/config/docs boundaries remain foreign. No FREE candidate was observed dirty. Reacquire the exact source **and test** files at launch; this observation is not a lock.

## Complete-value examples and edge cases

The following are source transformations, not executed outcomes. To show complete remainders without duplicating long unchanged expressions, `R` below means the exact listed remainder map, substituted textually at that one call; it is not a new binding/API. `d` denotes the existing `seon.error.refusal/diagnostic` Var. Headers and remainder value expressions retain their original order and execute once. Aliases and auto-resolved keywords remain in the original namespace.

### L+M: two actual maps

1. `src/seon/blob.clj:77`: before `{:seon.error/at (java.util.Date.) :seon.error/layer :seon.blob/storage :seon.error/operation 'seon.blob/binary-threshold, ...R}`; after `(d (java.util.Date.) :seon.blob/storage 'seon.blob/binary-threshold R)`.

```clojure
{:seon.error/offending threshold
 :seon.blob/threshold-attribute :seon.config.eval.result/blob-threshold
 :seon.error/message "Blob threshold is not a positive integer."
 :seon.error/expected :pos-int}
```

`R` is unchanged. The distinguishing threshold attribute still satisfies `:seon.blob/invalid-threshold-error` (`resources/seon/schemas/seon.blob.edn:31–32`). The enclosing ex-info message/data and throw stay in place; no Throwable input is introduced.

2. `src/seon/blob.clj:94`: before the same header with operation `'seon.blob/stage-file!` and `R` below; after `(d (java.util.Date.) :seon.blob/storage 'seon.blob/stage-file! R)`.

```clojure
{:seon.error/offending store-base
 :seon.blob/store-root-absent (str store-base)
 :seon.error/message "The file-backed blob store has no process root."
 :seon.blob/store-base store-base
 :seon.error/expected "a store directory with a process-root parent"}
```

The distinguishing string remains in `:seon.blob/store-root-absent-error` (`seon.blob.edn:33–34`), including the exact `(str store-base)` expression. No domain member is filtered.

### L−M: two actual maps

`src/seon/sci/eval.clj:535` and `:542` differ only in their event-count expression:

```clojure
;; Before, with N = 0 at :535, (count events) at :542:
{:seon.error/at (java.util.Date.)
 :seon.error/layer :seon.sci.eval/reader
 :seon.error/operation 'seon.sci.eval/one-event
 ::reader-event-count N}
;; After each:
(d (java.util.Date.) :seon.sci.eval/reader 'seon.sci.eval/one-event
   {::reader-event-count N})
```

These are two real locations, not a synthesized third branch. Message absence is preserved; the two different enclosing ex-info messages remain outside the data map. `:seon.sci.eval/reader-event-count-error` still requires the same count (`resources/seon/schemas/seon.sci.eval.edn:194–200`). No `:message nil` is added.

### D+M: two actual existing constructor calls

1. `src/seon/error.clj:732`: before `(error.refusal/diagnostic {:seon.error/at A :seon.error/layer :seon.error/reading :seon.error/operation 'seon.error/fact-source, ...R})`; after `(d A :seon.error/reading 'seon.error/fact-source R)`, where `A` is exactly `(or (:seon.error/at fact) (java.util.Date.))` and:

```clojure
{:seon.error/expected-key :seon.error/data-edn
 :seon.error/message (str "Stored error evidence could not be read: "
                         (or (not-empty (ex-message failure))
                             (.getName (class failure))))
 :seon.error/throwable failure}
```

Computed message, existing timestamp fallback **at the caller**, expected key and identical Throwable expression survive. The old and new calls both derive exception class/chain/root frame through the same code. No new domain schema or recording declaration is selected by the conversion.

2. `src/seon/ai.clj:1468`: before `(refusal/diagnostic {:seon.error/at (java.util.Date.) :seon.error/layer :seon.ai/request :seon.error/operation 'seon.ai/send-request, ...R})`; after `(d (java.util.Date.) :seon.ai/request 'seon.ai/send-request R)`:

```clojure
{:seon.error/message (or (ex-message failure) (.getName (class failure)))
 :seon.error/data {:seon.ai/endpoint endpoint
                   ::throwable (.getName (class failure))
                   ::error-class (if before-send?
                                   :transport-before-send :transport-unknown)
                   ::request-transmitted? (not before-send?)
                   ::response-started? false
                   ::output-observed? false}
 :seon.ai/transport-failure endpoint
 :seon.error/throwable failure
 :seon.error/member :seon.ai/endpoint
 :seon.error/expected "a completed HTTP exchange"}
```

All endpoint, transport-state and paid-output evidence remains. The precise alternative remains `:seon.ai/transport-failure-error` (`resources/seon/schemas/seon.ai.edn:51–60`). This is conversion of one existing diagnostic call, not nesting two caller constructors.

### D−M: no real sites; two prospective proof cases only

The census has zero D−M sites. Inventing two real examples would be false. These two explicit constructor cases should cover the claimed rule if later encountered:

```clojure
;; Case 1, empty Throwable-free remainder:
(d {:seon.error/at at :seon.error/layer :x/y :seon.error/operation 'x/f})
;; =>
(d at :x/y 'x/f {})

;; Case 2, Throwable plus separately declared evidence:
(d {:seon.error/at at :seon.error/layer :x/y :seon.error/operation 'x/f
    :seon.error/throwable failure :seon.error/cause cause-ref :x/member 1})
;; =>
(d at :x/y 'x/f
   {:seon.error/throwable failure :seon.error/cause cause-ref :x/member 1})
```

Case 1 retains absence. Case 2 preserves the cause ref/member and derives root message only as the existing map arity does. The whole-chain specimens in `refusal_test.clj:75–155` already name exact outer/root messages, ex-data, first-party frames and root frame; run equivalent assertions for the new arity. These examples are not current admitted rows or executed tests.

### Conditional members, computed message, and assoc after construction

All three required edges have real evidence:

- `src/seon/config.clj:122`: the entire `:seon.error/data (cond-> {::key missing} ... )` expression survives verbatim, including conditionally present `:seon.config/missing-effective` and `::configuration-refusal`. False conditions still mean absent members. Its error-key/expected-key/offending members survive too.
- `src/seon/error.clj:732`: the D+M example above preserves `str`, `not-empty`, `ex-message`, and class-name fallback without evaluating any of them in the script.
- `src/seon/error.clj:553`: before `(merge (cond-> {:seon.error/at at :seon.error/layer :seon.error/normalization :seon.error/operation (or function 'seon.error/normalize)} class-name (assoc :seon.error/exception-class (symbol class-name)) frame (assoc :seon.error/frame frame) chain (assoc :seon.error/chain chain)) (when (map? error-value) error-value))`; after replace only the inner map with `(d at :seon.error/normalization (or function 'seon.error/normalize) {})`. All subsequent `assoc`s and right-hand merge precedence remain. This is the intermediate-admission proof called out in P1-2.

Raw-Throwable L maps (`src/seon/edit.clj:123,287`, for example) are intentionally unchanged: otherwise the constructor would remove their Throwable key and synthesize evidence. Quoted/fixture/bootstrap data is likewise retained. The script does not “repair” swallowed errors; existing message-only failures outside this change remain separate work. For D conversions, `refusal.clj:75–115` continues to preserve per-link ex-data and first-party frames using `Throwable->map` plus the actual cause chain. Its existing omission of absent/empty link messages is unchanged, not a new exact-Throwable-serialization guarantee.

## Constructor, dependency, and line-accounting decisions

`diagnostic` (`src/seon/error/refusal.clj:91–115`) already preserves the entire observation when no Throwable is supplied; with one it removes only that input key and derives the existing class/chain/frame/message fields. It reads no clock. The proposed positional arity accepts `at` as data and delegates through an open map. Caller clock expressions remain caller expressions. A valid complete observation therefore has the same declared-schema membership; producer contracts and explicitly carried recording schema remain authoritative for deduplication. Map equality alone does not prove recording identity, so retain its existing identity tests.

Nothing missing here forces a second API. Raw Throwable maps may remain literals, and map input remains available for conditional assembly. Keep ordinary members-last merge precedence as documented, with collision-free admitted callers; do not silently drop extras or add a schema discriminator. The new arity is optional compression, not a prerequisite for every error map.

Dependency checked: Malli gitlink `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`; working fork is dirty/HELD. `reference-code/malli/src/malli/core.cljc:1210,1268–1286` owns open-map validation; `:2237–2252` owns function arity grouping. The leaf has no namespace requires, so adding a direct require to it introduces no reverse namespace dependency. Supplied inputs are the arity schemas/projection; compilation belongs to definition/contract acquisition, not each error construction. No custom validator is needed. Construction work is O(k) in members; Throwable work remains O(c + f) in cause links and inspected frames. The simpler alternative is retaining the literal; do so where conversion needs extra machinery.

Independent preview reconstruction gives FREE **693 added / 1,129 removed = −436**, covering 223 src sites and one script site; 13 require additions included. FREE source bytes drop **9,969**, and non-whitespace characters drop **4,552**. Thus the reduction is real repeated-key removal, not merely moving line breaks, although it does add function/contract dispatch and is not evidence of fewer runtime operations. HELD remains separate. Helper +7, prose −23, old test −11 and fixture net zero are separate accounting; future regression additions are not measured savings. The prose deletion's source/test/schema callers were checked; retain the unrelated surviving specialist renderers.

## First slice after fixes

Acquire exactly E0's six files: `src/seon/error/refusal.clj`, `test/seon/error/refusal_test.clj`, `src/seon/blob.clj`, `src/seon/error.clj`, `test/seon/error_test.clj`, `test/seon/schema_test.clj`. Add the arity and its complete contract, convert the five blob and seven error A sites, delete only unused `refusal-prose` and its obsolete 11-line test block, and replace the two renderer-symbol fixtures. Current caller evidence supports these boundaries; no HELD file is required.

Reserve ≤40 regression additions only if that suffices for P1-2's complete-value proof. Establish adoption/arming, then one focused installed `bin/test-check [--root ROOT] CLUSTER --ns ...` request at a time on the lane branch for `seon.error.refusal-test`, `seon.blob-error-test`, `seon.error-test`, `seon.schema-test`, and `seon.refusal-grammar-test`. Use current reaching evidence; do not claim namespace presence as coverage. Compare the same constructor probe on parent/child, record timing and exact execution/reuse/exclusion envelopes. Orchestrator owns platform/affected integration. Foreign in-flight files named above are outside this review and do not prevent this static decision; their runtime state was deliberately not inspected.

B3's original one-publication rule concerned a breaking supplied-`at` migration. That migration and facade retirement already exist in the inspected source. Keeping the map arity makes this new positional extension additive, so E0 followed by bounded file slices is coherent. Approval after the findings are resolved does not authorize silently replaying the all-files preview patch.
