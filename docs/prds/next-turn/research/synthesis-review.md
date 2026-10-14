---
type: independent-review
date: 2026-09-26
status: changes required
---

# Independent review of the next-turn synthesis

Read `synthesis.md`, `README.md`, all eight research files, and `CLAUDE.md` end to end. Source inspection only: no JVM, tests, lifecycle commands, model/GPU runs, commits, or delegation. Only this review is written. Checkout HEAD observed: `5837942014c30f3590324bc0a44557f094075688`; Malli: `a89ebef0572628b9198b825e5aa8b59484902593`. Citations describe the working tree inspected, not installed behavior. Other lanes are changing source, so use the named functions as well as line numbers. External paper claims were compared with the supplied research, not independently reproduced or re-reviewed.

Paths abbreviated below: `S` = `docs/prds/next-turn/synthesis.md`; `R/name` = `docs/prds/next-turn/research/name.md`.

1. **[blocking] The principal correctness rationale uses a superseded merge law.**

   **Evidence:** S:50–57,92,263 says the gate is weaker than the law and “merge still runs everything.” R/test-selection:102–105,252–264 assumes every reaching test gates merge. Current `CLAUDE.md:154–160` instead requires the task's tests plus root's chosen checks on the combined program, and explicitly says reaching-test counts are shown, not gated. `src/seon/cluster/source.clj:971–984` really does require one green reaching member per surviving replaced function and all named members green; that verifies the described source mechanism, not the claimed legal deficiency.

   **Correction:** Separate the real selection/reuse mismatch from merge policy. Remove the obsolete “all reaching tests” justification and define “everything” as the required task tests and root-selected checks. Audit this source against the current task-based acceptance rule before labeling its direction of divergence. Do not make widening a gate the reason to build item ① first.

2. **[blocking] Observed reach is neither the proposed exact selector nor sufficient reusable proof.**

   **Evidence:** S:63–67 stores symbols and falls back to two-hop reach only for tests without a recording, then S:81–86 proposes deleting the static index. R/test-selection:283–285,328–330 explicitly retains static macro, `def`, unarmed-definition and schema dependencies; these safeguards disappeared in synthesis. Current `CLAUDE.md:296–299` also explicitly calls past observed reach diagnostic and requires current dependencies plus actual tested program/input evidence. `src/seon/test/runner.clj:826–860` includes definition and schema digests; a symbol set does not replace that evidence. The proposed >5% missing-call rejection threshold (S:85–86) permits unsafe missing dependencies below the threshold.

   **Correction:** First record run/member-specific observed symbols **and exercised digests**, bound to the tested program and inputs, as diagnostics. Keep current dependency evidence and unknown handling authoritative. Retain static dependencies wrappers cannot observe, and distinguish missing recording from a completed zero-call observation. Require zero unexplained omissions for any correctness use; 5% can only be an exploratory signal. Do not credit deletion of the whole reach index before its surviving responsibilities have replacements.

3. **[blocking] Global profile differences cannot label what this evaluation actually used.**

   **Evidence:** S:63–69 proposes the same profile diff for tests and evaluations and calls the shown→used label free. `src/seon/profile.clj:10–14,132–136,181–196,206–240` exposes process-wide host/context cells, approximate concurrent reads, and differences including other threads. `explain` filters out growth below one millisecond and limits sorted results (`:239–241`), so its public output is not complete call evidence. Counters increment on completion (`:107–126`). R/test-selection:340–345 acknowledges missing/stale cells and concurrency; R/astra:100–108,163,214–216 distinguishes observed use from causal usefulness.

   **Correction:** Prove attribution at the existing evaluation/contract owner, including shared host calls, nested evaluations, new/replaced cells and actual exit. Do not consume `profile/explain` as a complete trace or add an unrelated global atom/registry. Until attribution is proved, label the data “calls observed during this window,” never “this agent used.” Record exposure, direct reads/references, and transitive execution separately: calling a helper does not demonstrate that displaying its documentation helped. Use ablations to establish usefulness.

4. **[should-fix] Numeric fidelity has several concrete mismatches and scope substitutions.**

   | Synthesis claim | Research value / scope | Concrete correction |
   |---|---|---|
   | S:54–55: invalidated p90 **1,374** | R/test-selection:146: p75 **1,374**, p90 **1,481**. Its headline at :19 repeats the same error. Raw `tmp/next-turn-test-selection/reach.out:31` agrees with the table. | Use p90 **1,481**, and mark the per-commit survey an estimate (:135–140). |
   | S:23: CLM **0.55 s** as a measured point | R/predictability:305–308 reports median **0.53–0.63 s**, 50 uncached cards; no specific 0.55 s measurement. | Quote the interval or label 0.55 s an approximation. |
   | S:108–109: “the one reader; **92%** resolve” | R/token-level:185: **92.2%**, obtained with the static Python reader/index described at :156–180. | Preserve rounding, but remove attribution to Seon's live reader. This is not measured live resolution. |
   | S:128: a render miss **~1.5 ms** | R/render-cache:26–30: **~1.5 ms compiled non-turn**, **~0.4 ms SCI**, plus **~4 ms per call** for current key construction; :43–46: turn rendering **1.43–1.86 s**. | State which component is timed; 1.5 ms is not complete miss latency. |
   | S:150–152: **15.5%** errors, **3.9%** catchable, presented together | R/ingredients:29: **2,126/13,756 = 15.5%** overall; R/token-level:176,215–220: recent unique cohort, **83.7% ok = 16.3% non-ok**, **3.9%** catchable classes. | Name both cohorts; do not imply a single measured joint rate. |
   | S:186,210: `options` **<5–20 ms / <20 ms** | R/form-assembly:550–551: **<5 ms warm for small attributes**; :619–621: **<20 ms server-side for the additional web UI**. | Separate backend and UI estimates and their bounds. |
   | S:179,193,210 includes web autocomplete but budgets only **3–4 days/~100 lines** for options, total **6.5–8.5 days/+180–220** | R/form-assembly:548–549 gives those backend costs; :619–623 adds **3–5 days, ~80 Clojure +60 JS/Datastar lines** for UI. | Defer UI explicitly, or revise item ③ to **9.5–13.5 days/~+320–360 lines**, before the few schema edits and unpriced verification. |
   | S:235: **+55/−435** cache lines without an estimate label | R/render-cache:184–197: **estimated +55/−435 to −465**, excluding central cache work. | Restore range, estimate label and dependency exclusion. |
   | S:208: first red after **1–3 tests** | R/test-selection:369–370: **1–3 only if ranking works**, latency unchanged on green runs. | Retain the condition; this is not measured performance. |

   Other prominent rounded values match: 82/60%, 54%, 74%, CLM 8% versus 36%, first-touch 23% versus 10% on n=70, 84/81/88% hole scores, 27.5% supplied slots, 44/68% reads, ~11% forced tokens, 13K→50K, 97% historical render hits, and 69.8 MB. The 36% comparison is the matched 300-step CLM subset, not the full ensemble's 36.7% (R/predictability:133,295). The ~one-third saved-steps calculation is faithfully marked hypothetical. S:269–270's 538 MB/1.9 GB agrees with R/astra:205 but conflicts with R/ingredients:28's 4.3 GB; attribute this correction to the scoped Astra inventory rather than imply all censuses agree. Thresholds such as 15-point recall gain and 5–15k prompt tokens are new proposed criteria, not research measurements.

5. **[should-fix] Key seams exist, but three line citations no longer identify them.**

   **Evidence and correction:**

   | Requested citation | Source check |
   |---|---|
   | `seon.eval.edn:36` | Verified: `resources/seon/schemas/seon.eval.edn:36` declares integer `:fn-entries`; it does not declare call identities. |
   | `source.clj:970` | Nearby valid seam: `src/seon/cluster/source.clj:971–984`, `accept-merge!`, contains the one-green-reaching-member check. Correct its policy interpretation per finding 1. |
   | `turn.clj:4489–4503` | Now reply-plan persistence, not the sink. The snapshot sink is `src/seon/turn.clj:4426–4440`, with a sliding latest-snapshot channel. It is not an incremental evaluation API. |
   | `call_preparation.clj:639–671` | Line drift: `arity-plan` was at `src/seon/call_preparation.clj:611–643` in the inspected version, joined through `planned-arities` at :645–650. It joins supplied defaults by schema name; it does not enumerate arbitrary holes. This file changed during review; refresh line anchors when landing. |
   | `runner.clj:690–945` | Verified static reach acquisition, schema closure, digests and cache machinery. The function continues with refusal handling at :946–950; “delete 250 lines” is not a complete dependency retirement plan. |
   | `sci/eval.clj:4793` | Now a routing value in `disposable-copy`. The candidate test `mapv` remains at `src/seon/sci/eval.clj:4858–4859`; its owner starts :4818 and documents all-tests-after-red at :4827. |

   Pin a source revision and name functions with updated lines. These checks substantiate source availability only; this review intentionally provides no adoption, arming or runtime evidence.

6. **[blocking] Streamed evaluation has unresolved semantics, not just an unverified speed estimate.**

   **Evidence:** S:161–170 proposes cancel-on-error and evaluation while generation continues. Current `src/seon/turn.clj:4895–4903` stops on a declared disposition and otherwise continues to the next form; it does not universally stop on an evaluation error. Current reply handling derives and durably plans sources after completion (`:4472–4499`). The streaming sink publishes replaceable snapshots, not durable execution admission (`:4426–4440`). R/token-level:289–292 explicitly leaves both semantics and parse-once unresolved.

   **Correction:** Separate advisory read/check from execution and cancellation. First establish chunk-boundary behavior, namespace/alias changes, definitions used by later forms, ordinary error values followed by repair, final malformed input, truncated/retried completions, durable ordinals and exit ownership. A precheck must not evaluate argument expressions to discover their values or reject unresolved expressions as proven invalid. Use existing reader stages and `malli.core/validator` (`reference-code/malli/src/malli/core.cljc:2645–2651`) for complete literal values. Route eventual execution through the same record/print owner; no second streaming runner. The proposed “no change to passing replies” test (S:198–199) misses intentionally erroring/recovering replies. Defer ③(a)'s production ranking until this semantic decision is resolved.

7. **[should-fix] The hypothesis is promising, but the headline and ranking claim more support than exists.**

   **Evidence:** S:9–11,38–44,46 calls these the most impactful choices while admitting no task-success experiment. File-target hits are lenient any-target matches (R/predictability:74–82,354–376), not required context slices; repeated reads commonly fetch different ranges (:243–247). Native Seon turns are unmeasured (R/ingredients:138–150; R/form-assembly:221–222). R/form-assembly:306–313 finds only **34.9% of all holes closed-small**, **30.5% of arguments**, and schema-enumerated values below **2% of contracted arguments**. This qualifies S:26,153's “mostly closed and small” claim. The largest fill opportunity is already implemented for product SCI agents (R/form-assembly:543–545).

   **Correction:** Say “supports testing focused structural context; relative task-success impact remains unknown.” Separate measured predictors, correctness maintenance and product-impact experiments. Start with recency/task/contract baselines before fitting another ranker: full-corpus ensemble @1 is only 36.7 versus MRU 35.6, although @10 improves 69.6→73.6 (R/predictability:131–133). Do not count existing silent fill as a new autocomplete benefit. Rejection of hot-path zero-shot CLM is well grounded for the tested setting; it is not a universal rejection of learned retrieval.

8. **[should-fix] The candidate comparison omits serious cheaper alternatives.**

   **Evidence:** S:106–120 absorbs task links only as initial candidates. R/astra:50–68,122–146 instead ranks next-proof-obligation second, prices it at **3–5 days, 5–40 ms, 1–10 MiB**, and treats changed-fact refresh as correctness regardless of prediction. R/prior-art:163–170,267 supports a harness-delivered situation-memory challenger, with explicit small-study/equal-budget caveats. S:202's tool-usage threshold cannot distinguish an ineffective menu from a useful menu the agent never asks for. R/test-selection:292–293 offers an executable-digest alternative; :355–400 offers ordering and stale-work policies distinct from reach replacement.

   **Correction:** Include a comparison row for proof-directed context, changed-read refresh, harness-delivered error/lesson context, and test ordering without selector replacement. Trial proof/contract context and changed-read delivery before learned entity ranking. Memory need not outrank stronger typed-context evidence, but merits a low-cost paired challenger rather than omission. Price it separately instead of inventing certainty. Keep stale-test draining optional, task-policy-aware and inside existing Flow execution; do not import that research's proposed separate worker or obsolete load cap as a requirement.

9. **[should-fix] Render repair is a prerequisite cost, not merely a footnote under things not to build.**

   **Evidence:** S:128 assumes cached renders; :132–139 acknowledges only slow turn misses, while :233–238 relegates cache replacement to the “not to build” section. R/render-cache:26–42 reports **~4 ms key construction on every call**, retained database/read results, and cache-derived page subscriptions. Its 97% hit figure (:168) is historical consecutive-opening reuse, not measured for changing ranked contexts. Current cache cost can dominate even on hits.

   **Correction:** Put render-key reuse, acquisition cost and bounded retention in an explicit prerequisite lane, with page interest migrated atomically with eviction. Reuse the existing cache owner and dependency freshness; no third cache. Price both central-cache integration and turn-read acquisition, which the synthesis totals currently omit. Measure candidate acquisition, key construction, hit validation, misses and churn together; do not promise ~1 ms turns plus 1.5 ms misses from unrelated microbenchmarks.

10. **[should-fix] “Pure function” conflicts with commit-keyed refolding and unspecified hysteresis.**

    **Evidence:** S:104,113–120 proposes a pure function over db/evaluations, a transition-table fold keyed by commit, and enter/leave thresholds. A moved commit can contain unrelated writes; `CLAUDE.md:316–324` explicitly rejects that as grounds to recompute. Hysteresis also needs prior membership, which the stated inputs do not identify.

    **Correction:** Derive or pass prior selection from existing recorded exposures/agent-owned state, or remove hysteresis from the first cut. Key bounded transition derivation on the evaluation population/content it reads, using upstream change tracking where available. Specify acquisition and recomputation cost in number of rows, candidates and changed observations. No new atom, auxiliary registry, or whole-history fold per turn is justified by saying “memoized.”

11. **[should-fix] The hole API needs a smaller contract and a justified extension point.**

    **Evidence:** S:171–179 adds a schema→enumerator row kind and promises SCI locals and matching producers. The verified supplied-default join only establishes omitted arguments by declared schema name. R/astra:22–24,92–98,221 warns that schema validity does not choose values and that partial syntax is not a solved API. R/form-assembly:550 limits its read estimate to small attributes. A quoted form such as `(let [x …] (f _))` does not put `x` into the caller's live SCI bindings; its lexical scope comes from the form. Arbitrary Malli output/input compatibility is not simply name equality or validation.

    **Correction:** Start with one known head, its arities, literal-map keys and explicit unknowns. Reuse `malli.core/children` (:2614–2621), `deref-all` (:2845–2851), documentation and supplied-default owners. Represent lexical bindings from the parsed form without executing initializers; distinguish them from live session bindings. Keep open-map extra keys admissible. Restrict producer suggestions initially to declared named-schema matches. Prove an existing extension seam before adding an enumerator registry, and justify any new row against the no-second-registry law. Bound identity enumeration and expose query bounds rather than silently truncating candidates.

12. **[should-fix] Ranked context must remain ordinary generated reads, not a second prompt/render path.**

    **Evidence:** S:115–120's full/cards/volatile-block design does not specify recording, render precedence, retrieval of omitted content, or how mandatory evidence survives ranking. `src/seon/turn.clj:2034–2074` already combines declared reads and eligible prior reads with currency evidence. `src/seon/cluster/prompt.clj:301–333` selects whole stored history units. `CLAUDE.md:142–149,273–278` requires one record/print path and limits once. R/astra:223 explicitly forbids clipping historical observations.

    **Correction:** Select ordinary read requests, execute them through the existing system-turn path, and let declared rendering produce whole recorded units. Use render profiles/declared render choices for depth, never truncate existing shown text into cards. Hold task must-reads, actionable errors and required refresh evidence outside probabilistic omission. No explicit new clipping or atom appears in the synthesis, but these missing ownership rules could create both indirectly; write them into the design before implementation.

13. **[should-fix] The estimates are plausible only as narrow prototypes; total cost and memory are understated.**

    **Evidence:** S:75–82,185–210 gives “facts only”/“≈0” memory and a few milliseconds with no end-to-end allocation accounting. `profile/begin` allocates two longs per cell plus a cell vector (`src/seon/profile.clj:181–196`): **5–10k cells means 80–160 KB of primitive snapshot storage alone, calculated**, before references, diff maps, indexes or writes. The **0.2–0.7 M datoms** is an unmeasured logical count, not heap size or lifetime storage; evaluation evidence grows with evaluations. S:212's “well under ~10k” has no current source-size census or measured retirement diff. R/astra:124–134 includes verification/tooling separately, unlike synthesis totals.

    **Correction:** Keep 0.1–1 ms profile diff and ~1 ms selection labeled optimistic, unmeasured CPU estimates. Include transaction/index cost, allocation and retained/transient bytes, concurrency, schema/static evidence and history growth. Price contracts, publication, recording failures, integration tests and retirement separately. The largest likely underestimates are streamed execution and whole-suite reach replacement; the missing UI adds a known 3–5 days. A known-head contract menu and bounded structural rank lookup are plausible cheap prototypes; no evidence supports an exact revised production duration. Do not claim the codebase fits 10k until measured, and do not count deletions that correctness still requires.

14. **[should-fix] Section 4 is a useful pilot, but cannot decide whether ② and ③ improve success.**

    **Evidence:** S:240–259 changes only context, keeps existing silent fill equal, and measures no optional-menu or streamed-check treatment. It therefore cannot estimate ③'s effect. “Before building beyond ①” also conflicts with requiring an implemented ② arm. Twenty to thirty tasks without repetitions or uncertainty bounds cannot establish equal success reliably; success changes in increments of 3.3–5 percentage points. Token/turn thresholds ignore billed cache behavior (R/prior-art:51,218), and observed-use coverage is not usefulness (R/astra:100–108,209–216).

    **Correction:**

    - Run the cheap known-head oracle and ten-task proof-context screens first (R/astra:140–146), without requiring call-evidence infrastructure. Use a minimal context prototype for the pilot.
    - Freeze task states, program/schema revision, branch/private-state fixtures, model settings and acceptance checks. Split task families/time; randomize paired arm order and repeat stochastic runs. Exclude unreconstructible private-state cases explicitly.
    - Compare current walk, simple task/recency/contract context, and proof/changed-fact context at equal total budgets with mandatory evidence fixed. Test `options` separately or factorially; keep streamed execution out until semantics are settled.
    - Primary measure: independently verified task success with paired uncertainty intervals and a predeclared non-inferiority margin. Then time to accepted result, billed cost per success, cache hit/miss tokens, total tokens, repair turns, errors, required evidence omitted, CPU time and peak/retained memory.
    - Treat 20–30 pairs as a feasibility screen. Choose a larger sample from observed variance and a meaningful effect size; an inconclusive interval means gather evidence, not “keep only ① and ③(a).” Price provider runs from a fixed model, repetitions and token cap; “a few dollars” is only a provisional estimate.

15. **[minor] The pending CLM appendix and prerequisite language are stale or unsupported.**

    **Evidence:** S:224–225 points to a pending bounded-result appendix, but R/form-assembly:415–474 contains an experiment specification, not results, and the file ends at :662 without that appendix. S:50,213 says call evidence is needed by every other idea; R/astra:140–146 and R/form-assembly:508–545 provide contract/proof/menu screens using existing declarations and forms. S:269 labels README corrections although README:55,73 still contains the old volume and per-agent cache claims.

    **Correction:** Say “experiment specified, not run/reported”; link its actual section. Make call-evidence improvements independently valuable instrumentation, not a blocker for deterministic context experiments. Label README corrections as identified/pending unless applied by its owner.

## Verdict

Changes required before implementation. Yes, reorder the top three **experiments**: (1) contract-directed context for known operations; (2) next-proof/changed-fact context; (3) bounded task/recency working-set context against those baselines. Keep harness-delivered situation memory as a challenger. Treat render repair and trustworthy evidence recording as enabling maintenance, with separate costs, rather than demonstrated top-impact product features. Defer streaming execution until its semantics are settled.

The evidence supports testing the owner's hypothesis, not declaring it the winner. It strongly argues against the tested zero-shot CLM configuration on the hot path. Most headline percentages are faithfully rounded; the important defects are the p90 error, omitted UI cost, misattributed measurements, understated full-path cost, superseded merge premise, and unsafe promotion of observed reach into correctness authority. No runtime or agent-success proof was performed or claimed here.
