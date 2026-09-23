---
type: implementation-manifest
status: prepared for independent review; not implemented
created: 2026-09-23
scope: B3 constructor/callers, cut-analysis rank 9
---

# Error constructor cut — exact implementation manifest

The system already carries errors as flat values and has one cause-preserving constructor. Keep that owner, pass the existing timestamp, and replace only repeated syntax; neither error policy nor domain evidence needs another mechanism.

**Decision for review:** extend `seon.error.refusal/diagnostic` with one positional arity; retain its map arity and all producer contracts. The measured free conversion is **224 sites, −436 caller lines**, not the historical ≈−1,200. With the helper and the one supported prose deletion, the measured prepared free source/script change is **−452**, plus **−11 test lines** before the required regression additions. No implementation, JVM, test, runtime-status request, publication, worktree, or push occurred in this assignment.

## 1. Basis, authority and ownership

Basis: `refactor/agent-platform`, observed HEAD `b2ac2c4737c35dfea584cc623210f16ef2587583`; census uses working-tree bytes, including explicitly marked foreign WIP. The per-file hashes below identify those bytes independently of HEAD. This is a point-in-time manifest, not a lock or a claim that other lanes stopped changing files.

Authorities read: root `AGENTS.md` (principal Clojure approach, flat declared errors, whole causes, scripted changes, one-file ownership); `docs/prds/agent-platform/plan/AGENTS.md`; plan README and B3 §2a / §5 rows 1–3; `cut-analysis-2026-09-23.md` §2 B-c and rank 9; `astra-cut-review-2026-09-23.md` Error group. Design and proof guidance: `.agents/skills/data-oriented-clojure/SKILL.md` and `.agents/skills/clojure-testing/SKILL.md`. This document refines the existing cut; it does not replace B3 or authorize implementation before independent review.

Historical evidence: `a86e93e21` retained explicit producing contracts; `ed2e1a6b6` installed whole-chain construction; `0d6eef939` changed error-floor behavior. Current `refusal.clj:91–115` already has the supplied `at` map contract, and `refusal_test.clj:181` asserts the facade is gone. Thus B3's historical seven-key migration and facade retirement are not repeated. Its old one-publication requirement applies to a breaking input retirement; this proposal adds an arity and leaves held old callers callable, so free callers can land first.

**HELD:** the dirty `src/seon/{cluster,fn,issue,instrument,test}.clj` and their test families named by the assignment. `git status` also reported modified cluster adoption/reload/store tests, `instrument_test`, test admission-digest tests and `render/web_test`; these are foreign boundaries, not work for this lane. The ledger's CURRENT HOLDS block also reserves `db.clj` (cut-l2), flow/await/cluster-agent/cluster-process/turn (m4-n1), maintenance/schedule (store-damage), cluster/source (test-overhead), and script/dev/mcp (branch-repl). Their rows and associated test families are conservatively HELD even when clean. Older “running” ledger blocks explicitly say they are stale. No held file or session was edited, resumed or messaged. FREE means outside those recorded holds and dirty paths, not blanket permission: the orchestrator must reacquire exact paths before launch.

**Deliverable conflict resolved narrowly:** the assignment says both “commit only” this Markdown and “commit the script under tmp/error-constructor/.” Only the Markdown is committed. The complete executed census and preview scripts are embedded below so their bytes are committed and can be extracted to the named tmp paths. Executable copies, JSON evidence and dry-run patches remain in `tmp/error-constructor/`; none is a second committed path. No production source was written.

## 2. Existing owner and the smallest extension

Use **`src/seon/error/refusal.clj:91`, `diagnostic`**. Its one-map arity returns the original observation without a Throwable. With one, it consumes only `:seon.error/throwable`, preserves domain members, derives exception class, complete chain (`chain`, :75) and root frame (`root-frame`, :65), and fills an absent message from the root. `refusal` (:117) is a cause-chain reader, not the constructor. Do not create `error/new`, resurrect `seon.error/diagnostic`, or replace `chain` with message extraction.

What it lacks is positional input. Calling the existing map arity around every literal would add lines and wrapper calls without removing the repeated keys. Add four-argument `[at layer operation members]` to the SAME Var. `members` is the existing open flat remainder, including the optional message and declared distinguishing keys; message absence stays absence. Converted remainders contain no base-key collisions. The arity keeps ordinary `merge` precedence (members last); it neither silently prunes extras nor reads a clock. This is an additive API change, necessary for this cut's compression, not a second constructor.

The exact proposed owner form follows. Its draft diff is +10/−3 = **+7 source lines**; indentation cleanup is excluded from that measured budget. The existing polymorphic open map boundary admits arbitrary producers' declared members; caller output contracts still validate their precise unions. The added `:map` remainder has that same justification. No schema-resource change is proposed.

```clojure
(defn diagnostic
  "Preserve the supplied observation, consuming only its optional Throwable.
  From the Throwable derive the outermost exception class, the whole cause
  `:seon.error/chain`, the ROOT cause's first complete stack frame, and —
  when the observation states none — the root cause's message."
  {:malli/schema
   [:function
    [:=> [:cat [:map
                [:seon.error/at :seon.error/at]
                [:seon.error/layer :seon.error/layer]
                [:seon.error/operation :seon.error/operation]
                [:seon.error/message {:optional true} :seon.error/message]
                [:seon.error/throwable {:optional true} :seon.error/throwable]]]
     :seon.error/base]
    [:=> [:cat :seon.error/at :seon.error/layer :seon.error/operation :map]
     :seon.error/base]]}
  ([{:seon.error/keys [throwable message] :as observation}]
  (if throwable
    (let [links (chain throwable)
          frame (root-frame throwable)
          root-message (:seon.error/message (peek links))]
      (cond-> (assoc (dissoc observation :seon.error/throwable)
                     :seon.error/exception-class (symbol (.getName (class throwable)))
                     :seon.error/chain links)
        frame (assoc :seon.error/frame frame)
        (and (nil? message) root-message) (assoc :seon.error/message root-message)))
    observation))
  ([at layer operation members]
   (diagnostic (merge {:seon.error/at at :seon.error/layer layer
                       :seon.error/operation operation}
                      members))))
```

Dependency seam: the pinned Malli gitlink is `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d` (working fork also dirty/HELD). `reference-code/malli/src/malli/core.cljc:1210` `-map-schema` reads optional/closed properties; :1268–1286 only adds the extra-key check when `closed` is true. `-function-schema` (:2237–2252) compiles `:function` children and groups arities. Use these existing library mechanisms; no custom predicate, registry or validation pass. Supplied inputs are the two complete arity schemas and the acquired projection; compilation/recomputation belongs to contract acquisition when that definition changes. The first-party caller specimen is `src/seon/ai.clj:1404` calling the same diagnostic leaf; its nearby comment-bearing request remains residue until reviewed.

Cost: a plain error remains work proportional to its own member count k, O(k) time/space, with constant extra function/contract dispatch; a Throwable retains existing O(c + f) work for c cause links and f inspected frames. No database, whole-program scan, clock read, cache or persistence occurs in construction. The simplest alternative considered is keeping literals: B3 permits it, and it is retained wherever the conversion would change behavior. Runtime dispatch cost is **unmeasured** here; implementation must compare parent/child on the same supplied values and reject a hot-path regression over the repository's 20% or 50 ms rule. Do not claim the line reduction proves a speed improvement.

## 3. Census definition and measured boundaries

One `rg -l :seon.error/at src test script bin` discovery pass per census invocation, followed by rewrite-clj traversal, not evaluation or regex replacement. Clojure files and extensionless Babashka scripts are parsed. A site is a literal map whose direct key forms contain `:seon.error/at`, `:seon.error/layer`, and `:seon.error/operation`; key order is unrestricted for counting. Maps with message are the requested four-key class. Three-key maps are additionally recorded because message is optional. Keywords inside strings, schema vectors, attribute reads, destructuring without those direct keys, and maps assembled only by `assoc` are not claimed as literal construction sites. Dependencies, caches, EDN database snapshots, and docs are outside the production census.

Result: **460 sites**: src 353, test 102, script 4, bin 1. **416** have all four requested keys; **339** of those are in src. The 353 src map spans occupy 3,059 lines including domain members and multiline values. This is not the historical 2,128 “base-key lines.” The older 342 literals / ≈−1,200 is a proposal at another basis, not a current measured diff. Do not scale it to the free subset.

Mechanical admission requires the first three direct keys in exactly at/layer/operation order, no internal comments, no quoted/discarded ancestor, and no raw Throwable input unless the entire immediate parent is already a recognized one-argument diagnostic call. No member expressions are evaluated by the script. The source-independent `script/seon/dev/dependency_digest.clj` remains a literal: its :2–15 contract says tools.deps loads it without the Seon source classpath. Introducing a require there would break bootstrap. Test literal maps remain independent expected/fixture data; constructing an expectation with the function under test would weaken the proof. The single prose-only test specimen removed with its owner is accounted separately.

Codes in the complete site table:

- `L+M`, `L−M`: direct literal, with/without message. `D+M`, `D−M`: map immediately inside the existing diagnostic call. This defines the distinct **conversion shapes**, parameterized by the untouched ordered remainder; every distinct domain-key set uses the same preservation rule.
- `A`: admitted to the mechanical rule (HELD still prevents application).
- `O`: header evaluation order differs; keep literal until per-expression ordering is proven or explicit ordered let bindings preserve it.
- `T`: raw Throwable member; keep literal. Calling diagnostic here would consume a member and synthesize others.
- `C`: comment inside literal; hand-edit preserving comment attachment and expression order.
- `Q`: quoted/discarded data; keep data, never turn into a call.
- `F`: independent test fixture/expected literal; retain, except error_test:1285's obsolete prose fixture deleted in E0.
- `B`: bootstrap classpath boundary; retain literal.

Every location is `line:column/shape/disposition`. “Auto” is syntactic eligibility, not permission to edit a HELD row. The Δ column includes the necessary require line for that file and measures only admitted substitutions; test/prose/helper changes are separate.

## 4. Exact file/site manifest

| File | State | Sites | Auto | Δ lines | Every site |
|---|---|---:|---:|---:|---|
| `bin/seon-hook` | FREE | 1 | 0 | +0 | 1663:31/L+M/Q |
| `script/seon/dev/dependency_digest.clj` | FREE | 1 | 0 | +0 | 109:5/L+M/B |
| `script/seon/dev/mcp.clj` | HELD | 2 | 0 | +0 | 500:9/L+M/Q, 559:28/L+M/Q |
| `script/seon/operator.clj` | FREE | 1 | 1 | -2 | 61:4/L+M/A |
| `src/my/background.clj` | FREE | 1 | 1 | -2 | 12:3/L+M/A |
| `src/my/program.clj` | FREE | 5 | 5 | -9 | 29:12/L+M/A, 63:21/L+M/A, 430:7/L+M/A, 464:4/L+M/A, 666:16/L+M/A |
| `src/seon/agent.clj` | FREE | 3 | 1 | -1 | 23:7/L+M/O, 44:13/L+M/O, 141:23/L+M/A |
| `src/seon/ai.clj` | FREE | 17 | 14 | -29 | 337:7/L+M/A, 651:11/L+M/A, 662:9/L+M/A, 708:11/L+M/A, 735:3/L+M/A, 904:7/L+M/A, 914:7/L+M/A, 927:7/L+M/A, 942:7/L+M/A, 963:7/L+M/A, 986:7/L+M/A, 1202:11/L+M/A, 1405:15/D+M/C, 1423:13/L+M/C, 1449:11/L+M/A, 1468:7/D+M/A, 1523:7/L+M/C |
| `src/seon/await.clj` | HELD | 1 | 1 | -2 | 45:6/L+M/A |
| `src/seon/background.clj` | FREE | 2 | 2 | -4 | 51:5/L+M/A, 83:7/L+M/A |
| `src/seon/blob.clj` | FREE | 5 | 5 | -10 | 77:9/L+M/A, 94:9/L+M/A, 154:9/L+M/A, 263:13/L+M/A, 395:11/L+M/A |
| `src/seon/bootstrap.clj` | FREE | 5 | 5 | -10 | 117:7/L+M/A, 391:7/L+M/A, 595:7/L+M/A, 696:37/L+M/A, 720:23/L+M/A |
| `src/seon/call_preparation.clj` | FREE | 6 | 6 | -12 | 208:3/L+M/A, 1189:3/L+M/A, 1237:28/L+M/A, 1254:29/L+M/A, 1275:11/L+M/A, 1408:9/L+M/A |
| `src/seon/cluster.clj` | HELD | 7 | 4 | -7 | 103:23/L+M/A, 340:9/L+M/A, 375:5/L+M/A, 393:13/L+M/A, 612:7/L+M/O, 620:5/L+M/O, 3179:11/L+M/O |
| `src/seon/cluster/agent.clj` | HELD | 2 | 2 | -3 | 655:7/L+M/A, 690:19/L+M/A |
| `src/seon/cluster/boot.clj` | FREE | 1 | 1 | -2 | 276:4/L+M/A |
| `src/seon/cluster/message.clj` | FREE | 14 | 0 | +0 | 157:5/L+M/O, 163:5/L+M/O, 169:5/L+M/O, 196:28/L+M/O, 204:28/L+M/O, 216:26/L+M/O, 605:7/L+M/O, 627:5/L+M/O, 635:5/L+M/O, 644:5/L+M/O, 684:29/L+M/O, 739:5/L+M/O, 747:5/L+M/O, 755:5/L+M/O |
| `src/seon/cluster/process.clj` | HELD | 4 | 4 | -8 | 45:9/L+M/A, 202:15/L+M/A, 212:21/L+M/A, 279:11/L+M/A |
| `src/seon/cluster/prompt.clj` | FREE | 2 | 1 | -1 | 57:7/L+M/O, 351:7/L+M/A |
| `src/seon/cluster/reply.clj` | FREE | 1 | 0 | +0 | 55:11/L+M/O |
| `src/seon/cluster/source.clj` | HELD | 5 | 5 | -7 | 409:3/L+M/A, 452:26/L+M/A, 484:36/L+M/A, 535:21/L+M/A, 807:18/L+M/A |
| `src/seon/cluster/status.clj` | FREE | 1 | 1 | -1 | 16:3/L+M/A |
| `src/seon/cluster/wake.clj` | FREE | 4 | 4 | -7 | 231:7/L+M/A, 240:7/L+M/A, 250:7/L+M/A, 277:5/L+M/A |
| `src/seon/config.clj` | FREE | 18 | 18 | -35 | 122:7/L+M/A, 198:8/L+M/A, 217:8/L+M/A, 232:8/L+M/A, 265:8/L+M/A, 280:8/L+M/A, 294:8/L+M/A, 308:8/L+M/A, 323:8/L+M/A, 345:8/L+M/A, 387:8/L+M/A, 404:8/L+M/A, 485:8/L+M/A, 506:8/L+M/A, 529:8/L+M/A, 577:8/L+M/A, 632:12/L+M/A, 791:13/L+M/A |
| `src/seon/context.clj` | FREE | 1 | 1 | -1 | 67:4/L+M/A |
| `src/seon/db.clj` | HELD | 9 | 7 | -17 | 169:3/L+M/O, 196:3/L+M/A, 487:12/L+M/A, 1248:3/L+M/A, 2381:19/L-M/T, 2740:7/L+M/A, 2997:19/D+M/A, 3005:7/D+M/A, 4329:19/D+M/A |
| `src/seon/edit.clj` | FREE | 10 | 8 | -16 | 123:8/L+M/T, 165:11/L+M/A, 276:9/L+M/A, 287:8/L+M/T, 356:17/L+M/A, 373:17/L+M/A, 429:7/L+M/A, 442:7/L+M/A, 482:7/L+M/A, 498:11/L+M/A |
| `src/seon/edit/jvm.clj` | FREE | 1 | 0 | +0 | 11:3/L+M/O |
| `src/seon/effect.clj` | FREE | 15 | 14 | -28 | 258:9/L+M/A, 314:17/L+M/A, 327:17/L+M/A, 383:17/L+M/A, 396:17/L+M/A, 611:16/L+M/A, 633:8/L+M/T, 699:9/L+M/A, 711:7/L+M/A, 726:8/L+M/A, 735:8/L+M/A, 774:12/L+M/A, 784:12/L+M/A, 794:12/L+M/A, 823:16/L+M/A |
| `src/seon/env.clj` | FREE | 10 | 10 | -20 | 89:15/L+M/A, 107:15/L+M/A, 168:11/L+M/A, 217:3/L+M/A, 236:5/L+M/A, 301:7/L+M/A, 314:7/L+M/A, 361:5/L+M/A, 378:7/L+M/A, 439:15/L+M/A |
| `src/seon/error.clj` | FREE | 8 | 7 | -13 | 507:29/L-M/A, 553:36/L-M/A, 611:17/L-M/O, 732:8/D+M/A, 1509:8/L+M/A, 1602:5/L+M/A, 1782:19/L+M/A, 2191:15/L-M/A |
| `src/seon/eval.clj` | FREE | 1 | 1 | -1 | 35:7/L+M/A |
| `src/seon/flow.clj` | HELD | 8 | 8 | -15 | 366:3/L+M/A, 696:9/L+M/A, 818:9/L+M/A, 898:15/L+M/A, 912:12/L+M/A, 939:7/L+M/A, 1035:6/L+M/A, 1194:7/L+M/A |
| `src/seon/fn.clj` | HELD | 1 | 1 | -1 | 1039:16/L+M/A |
| `src/seon/fs/jvm.clj` | FREE | 1 | 0 | +0 | 38:3/L+M/O |
| `src/seon/instrument.clj` | HELD | 4 | 4 | -9 | 370:15/L+M/A, 436:15/L+M/A, 591:21/L-M/A, 670:21/D+M/A |
| `src/seon/issue.clj` | HELD | 21 | 21 | -41 | 78:27/L+M/A, 142:52/L+M/A, 208:23/L+M/A, 558:22/L+M/A, 567:38/L+M/A, 575:29/L+M/A, 597:30/L+M/A, 605:29/L+M/A, 617:22/L+M/A, 781:7/L+M/A, 1049:16/L+M/A, 1078:48/L+M/A, 1085:44/L+M/A, 1093:16/L+M/A, 1101:39/L+M/A, 1108:37/L+M/A, 1116:16/L+M/A, 1170:20/L+M/A, 1179:20/L+M/A, 1290:14/L+M/A, 1333:48/L+M/A |
| `src/seon/issue/opening.clj` | FREE | 2 | 2 | -3 | 206:5/L+M/A, 233:5/L+M/A |
| `src/seon/maintenance.clj` | HELD | 8 | 7 | -14 | 52:7/L+M/A, 65:13/L+M/A, 73:13/L+M/A, 252:7/L+M/A, 448:13/L+M/T, 724:5/L+M/A, 929:9/L+M/A, 950:5/L+M/A |
| `src/seon/plan.clj` | FREE | 24 | 24 | -47 | 172:16/L+M/A, 361:7/L+M/A, 411:7/L+M/A, 541:16/L+M/A, 556:18/L+M/A, 583:16/L+M/A, 593:16/L+M/A, 609:41/L+M/A, 654:16/L+M/A, 707:18/L+M/A, 742:16/L+M/A, 800:16/L+M/A, 814:16/L+M/A, 830:22/L+M/A, 895:16/L+M/A, 907:16/L+M/A, 937:16/L+M/A, 990:16/L+M/A, 1005:16/L+M/A, 1024:22/L+M/A, 1151:16/L+M/A, 1174:20/L+M/A, 1188:22/L+M/A, 1209:30/L+M/A |
| `src/seon/problems.clj` | FREE | 1 | 0 | +0 | 120:18/L-M/O |
| `src/seon/program.cljc` | FREE | 10 | 10 | -20 | 145:13/L+M/A, 325:9/L+M/A, 403:14/L+M/A, 796:23/L+M/A, 813:23/L+M/A, 867:15/L+M/A, 917:17/L+M/A, 944:15/L+M/A, 1003:23/L+M/A, 1082:17/L+M/A |
| `src/seon/render.clj` | FREE | 10 | 6 | -11 | 144:15/L+M/A, 317:9/L+M/A, 938:16/L+M/A, 1257:15/L+M/O, 1273:15/L+M/O, 1288:15/L+M/O, 1349:9/L+M/A, 1401:17/L+M/O, 1467:27/L+M/A, 1749:10/L+M/A |
| `src/seon/render/data.clj` | FREE | 2 | 2 | -3 | 72:19/L+M/A, 114:4/L+M/A |
| `src/seon/render/hiccup.clj` | FREE | 4 | 4 | -8 | 277:5/L+M/A, 304:9/L+M/A, 325:17/L+M/A, 440:18/L+M/A |
| `src/seon/render/transcript.clj` | FREE | 3 | 3 | -6 | 701:9/L+M/A, 961:15/L+M/A, 2346:13/L+M/A |
| `src/seon/render/value.clj` | FREE | 2 | 2 | -4 | 248:7/L+M/A, 345:8/L+M/A |
| `src/seon/render/walk.clj` | FREE | 4 | 2 | -4 | 234:12/L+M/O, 644:12/L+M/O, 742:20/L+M/A, 941:23/L+M/A |
| `src/seon/render/web.clj` | FREE | 11 | 11 | -22 | 639:41/L+M/A, 728:25/L+M/A, 755:13/L+M/A, 1833:23/L+M/A, 2676:30/L+M/A, 2808:17/L-M/A, 2841:11/L-M/A, 3155:21/L+M/A, 3475:24/L+M/A, 3527:21/L+M/A, 3551:21/L+M/A |
| `src/seon/run.clj` | FREE | 2 | 2 | -3 | 124:5/L+M/A, 145:5/L+M/A |
| `src/seon/schedule.clj` | HELD | 8 | 8 | -17 | 243:23/L+M/A, 366:23/L+M/A, 381:23/L+M/A, 399:21/L+M/A, 467:23/L+M/A, 500:23/L+M/A, 643:44/L+M/A, 657:9/D+M/A |
| `src/seon/schema.clj` | FREE | 7 | 6 | -12 | 505:7/L+M/T, 1233:11/L+M/A, 1335:19/L+M/A, 1973:9/L+M/A, 3058:35/L+M/A, 3306:3/L+M/A, 3404:3/L+M/A |
| `src/seon/sci/admit.clj` | FREE | 4 | 4 | -8 | 130:23/L+M/A, 423:21/L+M/A, 634:3/L+M/A, 660:15/L+M/A |
| `src/seon/sci/eval.clj` | FREE | 16 | 16 | -32 | 222:48/L+M/A, 535:23/L-M/A, 542:23/L-M/A, 685:7/L+M/A, 741:17/L+M/A, 857:3/L+M/A, 990:23/L+M/A, 1086:27/L+M/A, 1385:19/L+M/A, 1402:15/L+M/A, 1524:3/L+M/A, 1547:3/L+M/A, 1776:8/L+M/A, 2055:19/L+M/A, 2846:19/L+M/A, 2864:19/L+M/A |
| `src/seon/sci/kernel.clj` | FREE | 6 | 6 | -14 | 174:17/L-M/A, 304:17/L-M/A, 322:15/L-M/A, 561:8/D+M/A, 625:25/L+M/A, 680:17/D+M/A |
| `src/seon/sci/reader.cljc` | FREE | 1 | 1 | -2 | 17:5/L+M/A |
| `src/seon/shell/jvm.clj` | FREE | 9 | 8 | -16 | 46:7/L+M/A, 56:7/L+M/A, 149:7/L+M/A, 168:11/L+M/A, 187:17/L+M/A, 200:17/L+M/A, 214:17/L+M/A, 411:18/L+M/A, 453:16/L+M/T |
| `src/seon/test.clj` | HELD | 6 | 6 | -8 | 26:3/L+M/A, 419:10/L+M/A, 955:16/L+M/A, 1273:25/L+M/A, 1320:13/L+M/A, 1398:3/L+M/A |
| `src/seon/test/accretion.clj` | FREE | 2 | 2 | -4 | 83:30/L+M/A, 348:12/L+M/A |
| `src/seon/test/runner.clj` | FREE | 7 | 7 | -13 | 358:5/L+M/A, 453:15/L+M/A, 892:3/L+M/A, 979:5/L+M/A, 1027:24/L+M/A, 1418:28/L+M/A, 1703:12/L+M/A |
| `src/seon/turn.clj` | HELD | 10 | 9 | -16 | 282:13/L+M/A, 310:21/L+M/A, 2054:7/L+M/A, 2080:7/L+M/A, 2263:19/L+M/A, 3315:7/L+M/O, 3440:15/L+M/A, 4670:7/L+M/A, 5237:11/L+M/A, 5351:5/L+M/A |
| `test/my/background_test.clj` | FREE | 1 | 0 | +0 | 20:17/L+M/F |
| `test/my/program_test.clj` | FREE | 1 | 0 | +0 | 176:33/L-M/F |
| `test/seon/ai_test.clj` | FREE | 1 | 0 | +0 | 1329:3/L+M/F |
| `test/seon/blob_error_test.clj` | FREE | 1 | 0 | +0 | 10:20/L+M/F |
| `test/seon/bootstrap_test.clj` | FREE | 1 | 0 | +0 | 405:13/L+M/F |
| `test/seon/call_preparation_test.clj` | FREE | 1 | 0 | +0 | 894:3/L-M/F |
| `test/seon/cluster/mcp_test.clj` | HELD | 1 | 0 | +0 | 343:17/L+M/F |
| `test/seon/cluster/publication_declared_schema_test.clj` | HELD | 1 | 0 | +0 | 18:22/L+M/F |
| `test/seon/cluster/source_test.clj` | HELD | 1 | 0 | +0 | 206:29/L+M/F |
| `test/seon/cluster/store_transact_test.clj` | HELD | 1 | 0 | +0 | 101:19/L+M/F |
| `test/seon/cluster/turn_test.clj` | HELD | 12 | 0 | +0 | 787:18/L+M/F, 908:22/L+M/F, 1283:18/L+M/F, 1551:18/L+M/F, 1663:43/L+M/F, 2077:10/L+M/F, 2227:13/L+M/F, 2293:21/L+M/F, 2334:13/L+M/F, 3201:15/L+M/F, 3242:27/L+M/F, 3348:21/L+M/F |
| `test/seon/cluster_test.clj` | HELD | 1 | 0 | +0 | 144:22/L+M/F |
| `test/seon/config_test.clj` | FREE | 2 | 0 | +0 | 365:24/L+M/F, 635:17/L+M/F |
| `test/seon/db_test.clj` | HELD | 2 | 0 | +0 | 323:20/L-M/F, 2061:45/L-M/F |
| `test/seon/effect_test.clj` | FREE | 1 | 0 | +0 | 42:9/L+M/F |
| `test/seon/error/refusal_test.clj` | FREE | 5 | 0 | +0 | 17:3/L-M/F, 65:18/L-M/F, 80:18/L-M/F, 126:3/L-M/F, 168:18/L-M/F |
| `test/seon/error_result_test.clj` | FREE | 1 | 0 | +0 | 43:3/L+M/F |
| `test/seon/error_test.clj` | FREE | 21 | 0 | +0 | 36:18/L+M/F, 217:12/L+M/F, 303:23/L+M/F, 896:15/L-M/F, 916:18/L-M/F, 967:26/L-M/F, 1066:21/L+M/F, 1080:21/L-M/F, 1163:21/L-M/F, 1244:18/L+M/F, 1259:15/L+M/F, 1273:18/L+M/F, 1285:18/L+M/F, 1296:18/L+M/F, 1309:18/L+M/F, 1318:18/L+M/F, 1328:17/L+M/F, 1341:18/L+M/F, 1351:18/L+M/F, 1363:18/L+M/F, 1382:21/L-M/F |
| `test/seon/fn_test.clj` | HELD | 1 | 0 | +0 | 1919:12/L+M/F |
| `test/seon/fs/jvm_test.clj` | FREE | 1 | 0 | +0 | 312:30/L+M/F |
| `test/seon/instrument_test.clj` | HELD | 9 | 0 | +0 | 179:17/L-M/F, 345:21/L-M/F, 600:18/L-M/F, 1156:26/L+M/F, 1190:19/L+M/F, 1213:15/L-M/F, 1241:15/L-M/F, 1274:18/L-M/F, 1426:14/L-M/F |
| `test/seon/maintenance_schema_test.clj` | FREE | 2 | 0 | +0 | 285:9/L+M/F, 343:29/L+M/F |
| `test/seon/maintenance_test.clj` | HELD | 2 | 0 | +0 | 65:10/L+M/F, 249:17/L+M/F |
| `test/seon/problems_test.clj` | FREE | 2 | 0 | +0 | 77:7/L+M/F, 111:26/L+M/F |
| `test/seon/reconcile_test.clj` | FREE | 1 | 0 | +0 | 122:21/L+M/F |
| `test/seon/render/faults_test.clj` | FREE | 1 | 0 | +0 | 27:43/L+M/F |
| `test/seon/render/history_test.clj` | FREE | 1 | 0 | +0 | 188:20/L+M/F |
| `test/seon/render/transcript_test.clj` | FREE | 2 | 0 | +0 | 357:9/L-M/F, 898:25/L-M/F |
| `test/seon/render/web_debug_test.clj` | FREE | 4 | 0 | +0 | 256:31/L-M/F, 280:55/L+M/F, 591:39/L+M/F, 667:17/L+M/F |
| `test/seon/render/web_test.clj` | HELD | 4 | 0 | +0 | 2122:19/L+M/F, 2125:19/L+M/F, 2141:29/L+M/F, 2364:61/L-M/F |
| `test/seon/run6_stall_test.clj` | FREE | 1 | 0 | +0 | 40:59/L+M/F |
| `test/seon/schedule_test.clj` | HELD | 2 | 0 | +0 | 90:3/L+M/F, 99:19/L+M/F |
| `test/seon/schema/datahike_test.clj` | FREE | 1 | 0 | +0 | 45:18/L-M/F |
| `test/seon/schema_test.clj` | FREE | 3 | 0 | +0 | 202:19/L-M/F, 1508:26/L-M/F, 1568:21/L+M/F |
| `test/seon/sci/eval_test.clj` | FREE | 3 | 0 | +0 | 1142:15/L+M/F, 2337:19/L+M/F, 2455:22/L+M/F |
| `test/seon/test/selection_test.clj` | HELD | 1 | 0 | +0 | 239:31/L+M/F |
| `test/seon/test_provenance_test.clj` | FREE | 1 | 0 | +0 | 106:23/L+M/F |
| `test/seon/test_support_test.clj` | FREE | 1 | 0 | +0 | 266:19/L-M/F |
| `test/seon/turn_loop_test.clj` | FREE | 3 | 0 | +0 | 593:20/L+M/F, 799:19/L+M/F, 964:10/L+M/F |
| `test/seon/turn_work_test.clj` | FREE | 1 | 0 | +0 | 657:16/L+M/F |

## 5. Before/after shapes and residue

| Conversion shape | All sites | Admitted free | Admitted held | Retained/review residue |
|---|---:|---:|---:|---:|
| `L+M` | 406 | 210 | 81 | 115 |
| `L-M` | 44 | 10 | 1 | 33 |
| `D+M` | 10 | 4 | 5 | 1 |
| `D-M` | 0 | 0 | 0 | 0 |

The single transformation keeps the first three value expressions in source order and copies the remaining source bytes. It replaces a whole existing diagnostic call for D shapes, never nests a second constructor. It does not descend into a quoted map and “fix” its contents.

```clojure
;; L+M: message and every domain member remain flat, in their existing order.
{:seon.error/at at-expr
 :seon.error/layer layer-expr
 :seon.error/operation operation-expr
 :seon.error/message message-expr
 :domain/distinguishing member-expr}
;; =>
(seon.error.refusal/diagnostic at-expr layer-expr operation-expr
 {:seon.error/message message-expr
  :domain/distinguishing member-expr})

;; L-M: optional absence, including the empty remainder.
{:seon.error/at at-expr :seon.error/layer layer-expr
 :seon.error/operation operation-expr :domain/distinguishing member-expr}
;; =>
(seon.error.refusal/diagnostic at-expr layer-expr operation-expr
 {:domain/distinguishing member-expr})
;; With no remainder, pass {}. Never introduce :seon.error/message nil.

;; D+M / D-M: unwrap the existing call, preserve its Throwable expression.
(refusal/diagnostic
 {:seon.error/at at-expr :seon.error/layer layer-expr
  :seon.error/operation operation-expr
  :seon.error/throwable throwable-expr :domain/distinguishing member-expr})
;; =>
(seon.error.refusal/diagnostic at-expr layer-expr operation-expr
 {:seon.error/throwable throwable-expr :domain/distinguishing member-expr})
;; A supplied message remains in this same remainder; no supplied message stays absent.

;; Surrounding forms are unchanged: merge precedence, assoc/cond-> and ex-info cause.
(merge original (seon.error.refusal/diagnostic at layer operation members))
(ex-info message (seon.error.refusal/diagnostic at layer operation members) cause)
```

Raw `:seon.error/throwable` maps are explicitly NOT D shapes. Their before/after is identity. The same is true of F/Q/B residue. O/C sites are hand-review candidates, not a promise that all must become calls: when a literal is smaller or an evaluation-order-preserving let adds machinery, retain the literal under B3 §2a. Never widen this cut into a cause-policy repair, generic error predicate, schema discriminator or domain-member rename.

Exact non-fixture residue by reason (all locations also appear above):

- **header evaluation order** (33): `src/seon/agent.clj:23`, `src/seon/agent.clj:44`, `src/seon/cluster.clj:612`, `src/seon/cluster.clj:620`, `src/seon/cluster.clj:3179`, `src/seon/cluster/message.clj:157`, `src/seon/cluster/message.clj:163`, `src/seon/cluster/message.clj:169`, `src/seon/cluster/message.clj:196`, `src/seon/cluster/message.clj:204`, `src/seon/cluster/message.clj:216`, `src/seon/cluster/message.clj:605`, `src/seon/cluster/message.clj:627`, `src/seon/cluster/message.clj:635`, `src/seon/cluster/message.clj:644`, `src/seon/cluster/message.clj:684`, `src/seon/cluster/message.clj:739`, `src/seon/cluster/message.clj:747`, `src/seon/cluster/message.clj:755`, `src/seon/cluster/prompt.clj:57`, `src/seon/cluster/reply.clj:55`, `src/seon/db.clj:169`, `src/seon/edit/jvm.clj:11`, `src/seon/error.clj:611`, `src/seon/fs/jvm.clj:38`, `src/seon/problems.clj:120`, `src/seon/render.clj:1257`, `src/seon/render.clj:1273`, `src/seon/render.clj:1288`, `src/seon/render.clj:1401`, `src/seon/render/walk.clj:234`, `src/seon/render/walk.clj:644`, `src/seon/turn.clj:3315`.
- **raw Throwable must remain raw** (7): `src/seon/db.clj:2381`, `src/seon/edit.clj:123`, `src/seon/edit.clj:287`, `src/seon/effect.clj:633`, `src/seon/maintenance.clj:448`, `src/seon/schema.clj:505`, `src/seon/shell/jvm.clj:453`.
- **comment inside literal** (3): `src/seon/ai.clj:1405`, `src/seon/ai.clj:1423`, `src/seon/ai.clj:1523`.
- **quoted/discarded data** (3): `bin/seon-hook:1663`, `script/seon/dev/mcp.clj:500`, `script/seon/dev/mcp.clj:559`.
- **tools.deps bootstrap classpath: keep core-only literal** (1): `script/seon/dev/dependency_digest.clj:109`.

## 6. Loadable slices, proofs and the error/prose file group

**E0 first, one owner:** `src/seon/error/refusal.clj`, `test/seon/error/refusal_test.clj`, `src/seon/blob.clj`, `src/seon/error.clj`, `test/seon/error_test.clj`, `test/seon/schema_test.clj`. Install the additive arity, convert exactly the A sites in blob/error, and perform the prose changes below once. This couples the helper to actual deletion. Measured additions before regression work: 10 helper + 15 blob + 17 error + 2 fixture = 44. Reserve at most 40 added regression lines: **≤84 additions**, with a deletion-heavy source diff. Retain the existing map arity; no held caller or resource must move for this slice to load.

The one error/prose group is:

1. Delete only `src/seon/error.clj:1102–1124` (`refusal-prose`, 23 lines including its separator). Its current src/test/schema census finds only test callers.
2. Delete only the `testing "refusal names the transition, rule, and atomic result"` block in `test/seon/error_test.clj:1283` (11 lines). It tests the retired prose function, not real transition atomicity. Existing `the-default-renderers-accept-an-attribute-shaped-error` and refusal grammar remain.
3. In `test/seon/schema_test.clj:977,989`, change both literal renderer symbols `'seon.error/refusal-prose` to `'seon.error/render-ai`. This fixture proves metadata/required-attribute derivation; its required members and expected shape stay unchanged. Two added/two removed lines, net zero.
4. **Retain** `edit-prose`, `mcp-prose`, `index-refusal-prose`, `evidence-text` and their surviving tests. Contrary to a blanket −62 prose deletion, `resources/seon/schemas/seon.dev.mcp.edn:2,4,6,10,22` names `mcp-prose`; `seon.fn.edn:218–238` names `index-refusal-prose`; `edit-prose` still has its explicit specialist test. This manifest does not remove schema render behavior or change shown text to chase that estimate. No second prose lane or double-counted error.clj cut.

E0 proof namespaces: **`seon.error.refusal-test`, `seon.blob-error-test`, `seon.error-test`, `seon.schema-test`, `seon.refusal-grammar-test`**. Extend the existing constructor behavior cases, not a parallel test harness:

- `constructor-preserves-open-domain-maps` (:64): both arities preserve entire values; supplied timestamp identity; every declared distinguishing member; optional message absence; empty remainder. Expected values remain literal, not computed by the new arity.
- `constructor-consumes-only-the-throwable-input` (:75), `a-wrapped-cause-is-recorded-whole` (:130), `the-frame-comes-from-the-root-cause` (:149): both entry shapes, exact outer/root classes/messages/ex-data, per-link Seon frames, root frame, retained `:seon.error/cause`, supplied message, and no accidental nil insertion. Compare the complete returned observation, not just key sets or chain length. The original Throwable used by `ex-info` remains `identical?` as its cause at callers; diagnostic itself intentionally consumes the input Throwable in both arities.
- `constructor-output-keeps-the-producing-contract` (:157): acquired/armed precise producer alternative accepts the complete value; wrong alternative still refuses. Do not broaden caller unions to the base. Compile the touched multi-arity contract against the packaged projection.
- Keep `facade-is-retired-and-reader-refusal-is-a-literal` (:181): cluster/reply remains residue; do not convert its protected literal to satisfy a grep count.
- Use side-effect counters only inside the disposable test body to prove at → layer → operation → remainder evaluation order once each; ensure surrounding merge precedence and ex-info data/cause survive. Existing full-cause rendering/recording assertions must remain; add no runtime classifier.

**E1 onward:** one whole FREE production file per sequential slice, exactly its A sites and one dependency require if absent. E0's error/blob paths are excluded. Every generated file diff below is ≤74 added lines, leaving a small per-file regression budget without exceeding ~100. This is a slice size limit, not permission for simultaneous regions in a file. The same lane retains a file until its coherent slice commits. There is no helper-only commit and no all-files mega-commit. If required regression additions exceed remaining room, split by existing defn boundaries in sequential commits under the same file owner; do not split an individual definition or contract.

Each slice must load its source namespace and its listed test namespace using the installed branch path after E0. The list names existing test namespaces to extend/check; **their existence does not prove they currently assert every converted branch**. Require positive reaching coverage and complete-value assertions at the changed producer. Where a listed test file is HELD, run its unchanged committed tests and place any necessary new case in the owned refusal test, or defer that file's implementation until the test owner releases it; do not edit a HELD test. No source deletion depends on deleting or weakening held tests.

| Subsequent FREE file (E1 onward, table order) | Generated added lines | Net lines | Named proof namespace |
|---|---:|---:|---|
| `script/seon/operator.clj` | 3 | -2 | `seon.error.refusal-test (pure constructor); platform operator proof deferred to orchestrator` |
| `src/my/background.clj` | 3 | -2 | `my.background-test` |
| `src/my/program.clj` | 17 | -9 | `my.program-test` |
| `src/seon/agent.clj` | 4 | -1 | `seon.cluster.agent-identity-test` |
| `src/seon/ai.clj` | 41 | -29 | `seon.ai-test` |
| `src/seon/background.clj` | 6 | -4 | `seon.background-test` |
| `src/seon/bootstrap.clj` | 15 | -10 | `seon.bootstrap-test` |
| `src/seon/call_preparation.clj` | 18 | -12 | `seon.call-preparation-test` |
| `src/seon/cluster/boot.clj` | 3 | -2 | `seon.cluster.boot-test` |
| `src/seon/cluster/prompt.clj` | 5 | -1 | `seon.cluster.prompt-test` |
| `src/seon/cluster/status.clj` | 5 | -1 | `seon.cluster.status-test` |
| `src/seon/cluster/wake.clj` | 14 | -7 | `seon.cluster.wake-test` |
| `src/seon/config.clj` | 56 | -35 | `seon.config-test` |
| `src/seon/context.clj` | 5 | -1 | `seon.context-test` |
| `src/seon/edit.clj` | 24 | -16 | `seon.edit-test` |
| `src/seon/effect.clj` | 42 | -28 | `seon.effect-test` |
| `src/seon/env.clj` | 30 | -20 | `seon.env-test` |
| `src/seon/eval.clj` | 5 | -1 | `seon.eval-test` |
| `src/seon/issue/opening.clj` | 6 | -3 | `seon.issue-test` |
| `src/seon/plan.clj` | 74 | -47 | `seon.plan-test` |
| `src/seon/program.cljc` | 30 | -20 | `seon.program-test` |
| `src/seon/render.clj` | 20 | -11 | `seon.render-simplification-test` |
| `src/seon/render/data.clj` | 8 | -3 | `seon.render.data-test` |
| `src/seon/render/hiccup.clj` | 12 | -8 | `seon.render.hiccup-test` |
| `src/seon/render/transcript.clj` | 9 | -6 | `seon.render.transcript-test` |
| `src/seon/render/value.clj` | 6 | -4 | `seon.render.value-test` |
| `src/seon/render/walk.clj` | 6 | -4 | `seon.render.walk-test` |
| `src/seon/render/web.clj` | 33 | -22 | `seon.render.web-test` |
| `src/seon/run.clj` | 8 | -3 | `seon.run6-stall-test` |
| `src/seon/schema.clj` | 18 | -12 | `seon.schema-test` |
| `src/seon/sci/admit.clj` | 12 | -8 | `seon.sci.admit-test` |
| `src/seon/sci/eval.clj` | 46 | -32 | `seon.sci.eval-test` |
| `src/seon/sci/kernel.clj` | 16 | -14 | `seon.sci.eval-test` |
| `src/seon/sci/reader.cljc` | 3 | -2 | `seon.sci.reader-test` |
| `src/seon/shell/jvm.clj` | 31 | -16 | `seon.shell.jvm-test` |
| `src/seon/test/accretion.clj` | 6 | -4 | `seon.test.accretion-test` |
| `src/seon/test/runner.clj` | 21 | -13 | `seon.test.runner-test` |

**H last:** the HELD A sites are a measured later tranche, never silently included in E1. Their table locations remain exact evidence of the observed WIP, not a patch to replay blindly on its eventual commits. After release, rebase the census onto released bytes and reacquire each whole file/test owner; apply the same rule. No additional constructor is needed. Residue remains separately named until reviewed, and retained literals are an explicit outcome rather than false completion of “all literals deleted.”

Implementation verification (not executed here): one focused installed request at a time, `bin/test-check [--root ROOT] CLUSTER --ns <namespace>` on the lane's cluster branch, or the equivalent `seon.test/run` request. Positively establish adopted definition and armed contracts first; then record full envelope, timing, actual executions/reuse, exclusions and exact proof boundary. No `bin/test` gates by a lane. Orchestrator owns platform/affected integration at cut completion and exclusive adoption. A broken shared tree calls for the authorized HEAD-plus-diff git-archive proof with linked caches; no worktree or probe JVM. Foreign breakage is a named proof boundary, never fabricated green. This docs-only assignment intentionally performs none of these runtime steps.

## 7. Measured line accounting

All measurements are dry-run source diffs, not landed changes. `preview.py` preserves remainder bytes and uses unified-diff added/deleted counts; a require is counted once when needed. No test literals were blanket-converted. The optional fixture deletion is disjoint from caller rewrites.

| Scope | Converted sites | Added | Removed | Net |
|---|---:|---:|---:|---:|
| FREE src callers | 223 | 690 | 1,124 | −434 |
| FREE script caller | 1 | 3 | 5 | −2 |
| FREE caller subtotal | 224 | 693 | 1,129 | **−436** |
| HELD src callers, after release only | 87 | 272 | 437 | −165 |
| Full mechanically admitted callers | 311 | 965 | 1,566 | **−601** |
| Same-Var arity addition, once | — | 10 | 3 | +7 |
| Supported refusal-prose deletion, once | — | 0 | 23 | −23 |
| Prose-only test retirement | — | 0 | 11 | −11 |
| Renderer-symbol fixture conversion | — | 2 | 2 | 0 |
| Prepared FREE src + script + helper/prose | 224 | 703 | 1,155 | **−452** |
| Prepared FULL src + script + helper/prose | 311 | 975 | 1,592 | **−617** |
| Prepared FREE including existing test edits | — | 705 | 1,168 | **−463** |
| Prepared FULL including existing test edits | — | 977 | 1,605 | **−628** |

Source alone (excluding the −2 script caller): free **−450**, full **−615**. Existing-test net is **−11** in each. Required new regression edits have not been authored/executed; E0 reserves ≤40 added lines, so its effect is a budget, not a measured number. A maximum +40 test increase would leave free total ≤−423 and full total ≤−588 before any further per-file regression needs. Remaining residue contributes **zero assumed savings**. Therefore −628 is the exact prepared full candidate before new proofs, not a guarantee for all historical 342 sites or an implemented result. Every eventual landing must replace budgets with its actual src/test diff counts.

Static evidence: final census 3.3–3.8 s wall across this checkout; dry-run generation sub-second; rewrite-clj parsing of proposed files/constructor 0.43 s wall, successful. **The census is over one second:** its work is proportional to every candidate source byte because an exact whole-tree inventory was explicitly requested; it is offline planning work, not an armed Seon call. No armed runtime functions were entered. Do not install this scan on an agent/eval path; reuse its recorded JSON for subsequent tables and regenerate only when inputs/rules change. The early preview failures were script issues (groupby iterator consumption and newline after `:require`), corrected before the measured candidate. Parse success proves syntax only: not contracts, loadability, evaluation order, runtime latency, adoption or browser paint. Peak memory was not measured; no memory-performance claim is made.

## 8. Reproducible scripts (committed here; executable copies in tmp)

Run from repository root with installed native Babashka/rewrite-clj, then Python. No JVM or project namespace loads are involved. The census produces exact before/after strings in `census.json`; the dry-run writes only under tmp and never applies a production patch. One reviewable rule handles all A sites. For implementation, select only the acquired FREE file's rows, refuse changed file hashes or changed `before` spans, apply that file's reviewed patch, lint and prove through the installed path. Full preview includes HELD rows for measurement only; **never apply preview.patch wholesale**.

The script versions below are the final executed versions; three census invocations occurred while admission/preservation rules were refined, each with one rg pass. They are not three independent claimed proofs.

### tmp/error-constructor/census.clj

```clojure
(require '[babashka.process :as p] '[rewrite-clj.zip :as z]
         '[clojure.string :as s] '[cheshire.core :as json])
(def started (System/nanoTime))
(def headers [":seon.error/at" ":seon.error/layer" ":seon.error/operation"])
(def held #{"src/seon/cluster.clj" "src/seon/fn.clj" "src/seon/issue.clj"
            "src/seon/instrument.clj" "src/seon/test.clj" "src/seon/db.clj"
            "src/seon/flow.clj" "src/seon/await.clj" "src/seon/cluster/agent.clj"
            "src/seon/turn.clj" "src/seon/maintenance.clj" "src/seon/schedule.clj"
            "src/seon/cluster/source.clj" "src/seon/cluster/process.clj"
            "script/seon/dev/mcp.clj"})
(def dirty (set (s/split-lines (:out (p/shell {:out :string} "git diff --name-only")))))
(defn held? [f]
  (or (held f) (dirty f)
      (some #(or (= f (str "test/seon/" % "_test.clj"))
                 (s/starts-with? f (str "test/seon/" % "/")))
            ["cluster" "fn" "issue" "instrument" "test" "db" "flow" "await"
             "turn" "maintenance" "schedule"])))
(defn children [l] (take-while some? (iterate z/right (z/down l))))
(defn ancestors [l] (take-while some? (iterate z/up (z/up l))))
(def files (sort (s/split-lines (:out (p/shell {:out :string}
  "rg -l :seon.error/at src test script bin")))))
(def files (filterv #(or (some (partial s/ends-with? %) [".clj" ".cljc" ".cljs" ".bb"])
                              (s/includes? (first (s/split-lines (slurp %))) "bb")) files))
(defn census [f]
  (let [source (slurp f)
        starts (vec (reductions + 0 (map #(inc (count %)) (s/split source #"\n"))))
        offset (fn [[r c]] (+ (starts (dec r)) (dec c)))]
    (for [l (take-while (complement z/end?)
                       (iterate z/next (z/of-string source {:track-position? true})))
          :when (= :map (z/tag l))
          :let [cs (vec (children l)) pairs (partition 2 cs)
                ks (mapv (comp z/string first) pairs)]
          :when (every? (set ks) headers)
          :let [parents (ancestors l)
                quoted (some #(or (#{:quote :syntax-quote :uneval} (z/tag %))
                                  (and (= :list (z/tag %))
                                       (= "quote" (some-> % z/down z/string)))) parents)
                parent (z/up l)
                diagnostic (and (= :list (some-> parent z/tag))
                                (contains? #{"error.refusal/diagnostic" "refusal/diagnostic"
                                             "seon.error.refusal/diagnostic"}
                                           (some-> parent z/down z/string))
                                (= 2 (count (children parent))))
                comments (some #(= :comment (z/tag %))
                               (take-while (complement z/end?)
                                (iterate z/next* (z/of-string (z/string l)))))
                reason (cond (= f "script/seon/dev/dependency_digest.clj") "tools.deps bootstrap classpath: keep core-only literal"
                             (s/starts-with? f "test/") "fixture/expected value: retain independent literal"
                             quoted "quoted/discarded data"
                             comments "comment inside literal"
                             (not= headers (subvec ks 0 3)) "header evaluation order"
                             (and (some #{":seon.error/throwable"} ks) (not diagnostic))
                             "raw Throwable must remain raw"
                             :else nil)
                target (if diagnostic parent l)
                before (z/string target)
                extras (drop 3 pairs)
                members (if (seq extras)
                          (str "{" (subs source (offset (z/position (ffirst extras)))
                                         (+ (offset (z/position l)) (count (z/string l)))))
                          "{}")
                after (str "(seon.error.refusal/diagnostic "
                           (s/join " " (map (comp z/string second) (take 3 pairs)))
                           "\n " members ")")
                [row col] (z/position l)]]
      {:file f :line row :column col :held (boolean (held? f))
       :keys ks :shape (str (if diagnostic "D" "L") (if (some #{":seon.error/message"} ks) "+M" "-M"))
       :reason reason :before before :after (when-not reason after)
       :map-lines (count (s/split-lines (z/string l)))
       :delta (if reason 0 (- (count (s/split-lines after)) (count (s/split-lines before))))
       :target-position (z/position target)})))
(def rows (vec (mapcat census files)))
(spit "tmp/error-constructor/census.json" (json/generate-string rows {:pretty true}))
(doseq [[k rs] (sort-by key (group-by :file rows))]
 (println k (if (:held (first rs)) "HELD" "FREE") (count rs)
          "auto" (count (remove :reason rs)) "delta" (reduce + (map :delta rs))))
(println "TOTAL" (count rows) "SECONDS" (/ (- (System/nanoTime) started) 1e9))
```

### tmp/error-constructor/preview.py

```python
# Dry-run only: exact admitted spans, no checkout writes.
import json, pathlib, difflib, collections, hashlib
root=pathlib.Path('tmp/error-constructor')
rows=json.loads((root/'census.json').read_text())
for r in rows:
    if r['file']=='script/seon/dev/dependency_digest.clj':
        r['reason']='tools.deps bootstrap classpath: keep core-only literal';r['after']=None;r['delta']=0
(root/'census.json').write_text(json.dumps(rows,indent=2)+'\n')
metrics=[]; patches=[]
for file, sites in __import__('itertools').groupby(sorted(rows,key=lambda r:r['file']),key=lambda r:r['file']):
    sites=list(sites); original=pathlib.Path(file).read_text(); candidate=original
    chosen=[r for r in sites if not r['reason']]
    offsets=[0]
    for line in original.splitlines(True): offsets.append(offsets[-1]+len(line))
    spans=[]
    for r in chosen:
        row,col=r['target-position']; start=offsets[row-1]+col-1
        assert original[start:start+len(r['before'])]==r['before'],(file,row)
        after=r['after'].replace('\n ','\n'+' '*col,1)
        spans.append((start,start+len(r['before']),after))
    for (a,b,_),(c,d,_) in zip(sorted(spans),sorted(spans)[1:]): assert b<=c,(file,a,c)
    for a,b,after in sorted(spans,reverse=True): candidate=candidate[:a]+after+candidate[b:]
    # All selected files have an ns :require; preserve existing dependency aliases.
    ns_end=original.find('(:import')
    ns_prefix=original[:ns_end if ns_end>=0 else original.find('(def')]
    require_added=bool(chosen and 'seon.error.refusal' not in ns_prefix)
    if require_added:
        assert '(:require' in candidate,file
        candidate=candidate.replace('(:require','(:require [seon.error.refusal]\n           ',1)
    diff=list(difflib.unified_diff(original.splitlines(True),candidate.splitlines(True),fromfile='a/'+file,tofile='b/'+file))
    add=sum(l.startswith('+') and not l.startswith('+++') for l in diff)
    delete=sum(l.startswith('-') and not l.startswith('---') for l in diff)
    metrics.append(dict(file=file,held=sites[0]['held'],sites=len(sites),auto=len(chosen),add=add,delete=delete,net=add-delete,require=require_added,sha256=hashlib.sha256(original.encode()).hexdigest()))
    if chosen:
        dest=root/'preview'/file;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(candidate)
        patches+=diff
(root/'preview.patch').write_text(''.join(patches))
(root/'metrics.json').write_text(json.dumps(metrics,indent=2)+'\n')
for held in [False,True]:
    a=[m for m in metrics if m['held']==held];print('HELD' if held else 'FREE', {k:sum(m[k] for m in a) for k in ['sites','auto','add','delete','net','require']})
```

### tmp/error-constructor/prose-preview.py

```python
from pathlib import Path
import difflib,json
out=[]
for file in ['src/seon/error.clj','test/seon/error_test.clj','test/seon/schema_test.clj']:
    old=Path(file).read_text();new=old
    if file=='src/seon/error.clj':
        a=new.index('(defn refusal-prose');b=new.index('(defn instrumentation-prose',a);new=new[:a]+new[b:]
    elif file=='test/seon/error_test.clj':
        a=new.index('  (testing "refusal names the transition, rule, and atomic result"');b=new.index('  (testing ',a+12);new=new[:a]+new[b:]
    else:
        assert new.count("'seon.error/refusal-prose")==2
        new=new.replace("'seon.error/refusal-prose","'seon.error/render-ai")
    diff=list(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='a/'+file,tofile='b/'+file))
    add=sum(l.startswith('+') and not l.startswith('+++') for l in diff);delete=sum(l.startswith('-') and not l.startswith('---') for l in diff)
    out.append(dict(file=file,add=add,delete=delete,net=add-delete))
    Path('tmp/error-constructor/'+Path(file).name+'.prose.patch').write_text(''.join(diff))
Path('tmp/error-constructor/prose-metrics.json').write_text(json.dumps(out,indent=2))
print(out)
```

## 9. Input fingerprints and review gate

These SHA-256 values cover exact whole-file census inputs. They make drift visible; they are evidence for this document, not a new runtime stamp/cache mechanism. The constructor source is separately fixed by the quoted form and basis above. Review must settle the additive arity, per-call wrapper cost, each admitted key expression/order, and the residual/prose decisions before implementation. Recheck all holds at launch. No unverified runtime guarantee is inferred from this manifest.

| Input | SHA-256 |
|---|---|
| `bin/seon-hook` | `692e9c4b09705a27648f20f0102e10f04167ecbd9b442a0d47e4da9f2f8a59bb` |
| `script/seon/dev/dependency_digest.clj` | `06e897c370088e979cef67358ae1ba5a7913285138e05dcb7f62578d4a868b24` |
| `script/seon/dev/mcp.clj` | `8dff8be5ddb1cb96fa5cff8f304888d67a9f242e9006a89957efe308f086277c` |
| `script/seon/operator.clj` | `eaf69a103bfa719b29d1c5f47af9fea33d7569e669ec9d967e3e81f2684679ec` |
| `src/my/background.clj` | `94aa00f9304fbcfb3a399c387d790bab0d37a97e5909752993e98bcbd7997fc2` |
| `src/my/program.clj` | `7c85ec8908d6b786fecff1ce6102b83d5366406addfce4937378c4fefa2f35f5` |
| `src/seon/agent.clj` | `3c334ddfb4b9887f5466864d6a83bde292d674c89a9d2a169fec9373465329e3` |
| `src/seon/ai.clj` | `debbac466847941b1e49bf3b469517d72404c49fec3a9ed94f99399fcf395e7d` |
| `src/seon/await.clj` | `cd835d4bf3ac6389dd92d3dac43c99f8827ab37590b5c9d86f082cf8bacee8a1` |
| `src/seon/background.clj` | `202bbe6b4e542fcd235e20d7e0c867b1983a8eb7adfa76863b6794510e8bf7bb` |
| `src/seon/blob.clj` | `ba64513063aaea4099c44af25b3032ec83575f3eaaac41676e25cc6a35b41f3f` |
| `src/seon/bootstrap.clj` | `db8379b669a31c2b43cbab5d3bc718762bd1eeebb0cfc69f1082ccc53a8e5f6e` |
| `src/seon/call_preparation.clj` | `19e8048e800f53c96d6a102dc3d11393f31b7a86ec45e40554503ca32ee4bec9` |
| `src/seon/cluster.clj` | `0c99f578202f9c4ae6a0e257cfb1c6c83c701764b5c7d32cb45bdd8c85f17487` |
| `src/seon/cluster/agent.clj` | `cb5a2b80909465d6ee6134ac48b93487a0f1f3ab261590fbe4a585cceac3d46a` |
| `src/seon/cluster/boot.clj` | `f85d26f7842b11206c3458bea4bc5d4a7b87eb92487ecee98efbf8730c4c5a8e` |
| `src/seon/cluster/message.clj` | `98e2a240edb4e1b2d4ee70c3e02b9516d34b81ca3d29b0bc001665d8b7d27676` |
| `src/seon/cluster/process.clj` | `aea8fcbe04a38ac829304cc9f6a0bbf43adda51e5f2d3f28ccf24a3887bcfee4` |
| `src/seon/cluster/prompt.clj` | `1e4dbeec974542d6f01aacb1b7ddff0d30775cb46741a35c042538be6e00677f` |
| `src/seon/cluster/reply.clj` | `90006365ef060ddf729b7583992b676567a913d1704a081f4a645e1749700a04` |
| `src/seon/cluster/source.clj` | `7e22889f11abd81dc54adf7061f469a6baa72a01ae39cbc2f50ee7bacb343075` |
| `src/seon/cluster/status.clj` | `228d907118728fae4cda7a9368299555ed13ca80c753a2753b04cecf2f5ee1e7` |
| `src/seon/cluster/wake.clj` | `922d92dd0ab16cf552ef53c56e3e862310b10efea96925eb42f18c16cd804d8e` |
| `src/seon/config.clj` | `7928b4fbf7591176a29253bd5ee51afe075d82bea4f71a794692a8628de3024c` |
| `src/seon/context.clj` | `0d69079e0517d1367f06e00223dfa3b97e88a651a44259b31dcef15b1e465c16` |
| `src/seon/db.clj` | `cec003c690a56c2a34572fe0b783e69d950b2977234c225f941dfa23a2701266` |
| `src/seon/edit.clj` | `49ec11613a18a96841a784e7a964d5045a144d8cda01c1da889cdcf6295c54f8` |
| `src/seon/edit/jvm.clj` | `196da875210ece8505e0c9580b7605dea9abd4cfff5b85d9cbcc4747458f8584` |
| `src/seon/effect.clj` | `cf6767794fc44e1298da6d7bcbbad4a58623727f9b6438cbb7600b767fccc76c` |
| `src/seon/env.clj` | `c32c27976c48f03594d4991109f67573232f1335fcbf994991537b0c7f8efd42` |
| `src/seon/error.clj` | `31a42962874d629af6b802f354c05df289f8c18e03b08fb7c5c37bca876d09a8` |
| `src/seon/eval.clj` | `02d64c3663db9a16d8a23731bb745061842979f0bbc1f0a14c84bbec7183ff2c` |
| `src/seon/flow.clj` | `5ff2b4b494073a2cf0d1c51d5e87bcdf1b901f6d1eb1bac523f3f7ef06e66074` |
| `src/seon/fn.clj` | `7d9150e3fa55e4c05c7e2def18b8b1aff1fd50014c3a20c7bb66e2234dfbf58f` |
| `src/seon/fs/jvm.clj` | `b986a382097d654a29d1635e726495392e7bc6c88db8b1498bd86bba7d815a4c` |
| `src/seon/instrument.clj` | `717425eed653c48060109368f724749c6501a18c47e539e47ca4b73ab2f29f4b` |
| `src/seon/issue.clj` | `a2eec87ba8f38832da08e2642b847a7507b72068db0b7f23141b87e1374e5961` |
| `src/seon/issue/opening.clj` | `ab2c4223d1197beb3e664f2b92c27aa09dc3ed7fcd313c1503f213a51d324f20` |
| `src/seon/maintenance.clj` | `797faec68e723f14010bfcea5a658938a5798ca1d0acf9b3a09d22f559f84edf` |
| `src/seon/plan.clj` | `8c0b9fddcc290b257cec99835f2a9dfeafc82050437abbb5b7684b1d5150e178` |
| `src/seon/problems.clj` | `ccc23b8c7a25e2d5b3ca9501a8c01f1d7a76252888008892af16d77dbf41d6f3` |
| `src/seon/program.cljc` | `fcaebe61f95a9b528c8bd6ac6e533423e3b411b997b895d6b6567d8290d40f67` |
| `src/seon/render.clj` | `beef64a75fa0d0cada0ca106b22df7df3c6ff5ef5833c20120fc72e48b008aeb` |
| `src/seon/render/data.clj` | `620c8192815a1c6420b75074504fce1058b092d08cdffbf1628cb7b21782b2d0` |
| `src/seon/render/hiccup.clj` | `66a0bc1f0c4c4290a166e127fd3a798a433509d19ed6f5926cc1dfa572002fa2` |
| `src/seon/render/transcript.clj` | `b9ba1f7cb1c414bdc6db466d226e8dca28ea2d74b138280de7b52dd95966eabb` |
| `src/seon/render/value.clj` | `949e689ddd64d3475618e7e66453b585b9b8e4bf1a37ce1b7e7a9d753713c32f` |
| `src/seon/render/walk.clj` | `5df1354949fce568ba1aad87ee345a35dbc56e634bc5ef46bb451a144fdadb3a` |
| `src/seon/render/web.clj` | `0377dd7f783aa8f707e80810020bab789971d23c767ee40a6a8708b152b479ca` |
| `src/seon/run.clj` | `27fae8f1504a9f5d4945927a492d5bec6a44864acb3d4353b8ed73c219a95b06` |
| `src/seon/schedule.clj` | `ab4b68a7943c2ead9fbaccc03d4f71d268a80565f38ea0622a5dcbb29a4c19d9` |
| `src/seon/schema.clj` | `6cccaad723140f88a09a8c81f4e3efd65ae77c5f84874ca8cf6c368653cfa865` |
| `src/seon/sci/admit.clj` | `6d30c95b605f42f34fef6c7e5d6963e0571e53549bf05311441078edb8d24f6e` |
| `src/seon/sci/eval.clj` | `e6e0ac18892835f146b63ba5d79bc72aa43e961d1fc187fc20f78a93ab487b97` |
| `src/seon/sci/kernel.clj` | `3b2dbbb0c730659950d46f77d27042d5a37d672f2a23075d30977974d94dd23f` |
| `src/seon/sci/reader.cljc` | `b1d096e6f2fe223ed7779cb198cde85b351940fea82606e27458ed40e5975805` |
| `src/seon/shell/jvm.clj` | `e698645cce568c5ac04b64a12be56ce2626df62b24970e5107a2fefa910ab385` |
| `src/seon/test.clj` | `2233db6bd29ce9005f7da371a1a28bfc1b93b4446fab7c0a6208559a56998383` |
| `src/seon/test/accretion.clj` | `59b0a73084f8a1204c2916cae0a94afde356a85cc42a562f05d81cfaa1a61ce7` |
| `src/seon/test/runner.clj` | `f7583d87b8ca74fc4f75e35de86617648a5ed1210077182035b58da4d2f19735` |
| `src/seon/turn.clj` | `5ad41fffdba868cece0925d0fd95947e6d28bfe849f671b01eeecd83ca6a4b0f` |
| `test/my/background_test.clj` | `e63c958e583274e70fabf9b59d5d91ff80ddc3e279133cc16eaa74929edeb7bb` |
| `test/my/program_test.clj` | `cc17baae3823d29d87a677f24e5ab121e6736b88bd4f122510b5dec725ec275f` |
| `test/seon/ai_test.clj` | `fc1f892b0b622c974cfd524462589b5e99b659aa098c40c6a8dd010748aa8319` |
| `test/seon/blob_error_test.clj` | `56e284b665e23a07c70cc6546295c8fa5fb0dc354b9e7f2b69218fcd15e8c0ef` |
| `test/seon/bootstrap_test.clj` | `17dda1a960eb239c1cf02cd070cc579302bf23af034393d735c95962553c3289` |
| `test/seon/call_preparation_test.clj` | `56e4723709ee634e91075af3bbf786a4e47f2c2127ef612667316343fb68866a` |
| `test/seon/cluster/mcp_test.clj` | `b2d62010f82b20778f87cecc2664039e78debefb758b2ad7bc156a5754e6f1e8` |
| `test/seon/cluster/publication_declared_schema_test.clj` | `a183fad1fcfd2103cc7be3f3e2673b67ae5959fc60a4ee7eb0b3efff96b9bb26` |
| `test/seon/cluster/source_test.clj` | `5d069db136cbb3483ac914d2898256bb47fe80739d8efe67419c009ce0a690f0` |
| `test/seon/cluster/store_transact_test.clj` | `11c8784a1e94173675b1046b42d97446c8d4101a4f58ba00a43d358d11fa89f3` |
| `test/seon/cluster/turn_test.clj` | `2f4783ba853f5c54f4f4029f3870b0b26c2aae2b29ca620e0e4e67d836f2e722` |
| `test/seon/cluster_test.clj` | `066d55d885b7036a29f20323ced28eaedac0dc98e558ff9f285f69beb932c3c7` |
| `test/seon/config_test.clj` | `a7b41a4369ed5d02f20e9a2d64064d9b301f52f3027bc14ecb73f56ca4f60564` |
| `test/seon/db_test.clj` | `4811c71fffa6d91da1fe798b8a6894625d7984bd1491b135fcd8b0e0355d4de9` |
| `test/seon/effect_test.clj` | `b4f3d65801bed6e1561761681c9d66804d3c28def3f93d260270a5b8e51a50f2` |
| `test/seon/error/refusal_test.clj` | `1c985e6293f259de95118559a17e36fb79464e0309fec873a5f435689f263f5c` |
| `test/seon/error_result_test.clj` | `e9e04a4a2dd6914d02a2747250dbd5072b7af798d76dd157e6e5140914717d87` |
| `test/seon/error_test.clj` | `3516d1321c5a83aec6e449efaf25a708bbbf85f2f2c2cedc30b551167c35eb76` |
| `test/seon/fn_test.clj` | `daa9ded869e9e7835f78261d15d21821f666351e29a9f2878aeba7d305f4db6a` |
| `test/seon/fs/jvm_test.clj` | `b7bae8698a651ae675fbd25607d12c96851ba84fb6e8f8f9aa64931129f08108` |
| `test/seon/instrument_test.clj` | `d6900627945ae338a9c94bb5efccbe2500f0c78481386919b30611d46e286c4d` |
| `test/seon/maintenance_schema_test.clj` | `92faf218cb37a8617c8629fe43a0b87c4a9b5c9a720543fc850a42331bac0c98` |
| `test/seon/maintenance_test.clj` | `945b28165af5bcdb97bb72333f839221b8f3797bbe3db5de1f926a6e5a07b138` |
| `test/seon/problems_test.clj` | `37a7abc0934cc2a2216f42ffd992ce931ff173c1f3076acc9832624d914c2976` |
| `test/seon/reconcile_test.clj` | `bf2d00600fce5e95711bb6b3905dea37dedde11795a6083140661270cc72d11e` |
| `test/seon/render/faults_test.clj` | `55578fb31df3a2de6b68cec9d502c2d62b3c6a4dd081b47beda65327df1aaf17` |
| `test/seon/render/history_test.clj` | `06906449c27767b63fef2b1846611f6c7081f516908bb33282d510178c8fa647` |
| `test/seon/render/transcript_test.clj` | `0c879877987a2f0f01cbb975763c7ef5275f5a5ae8a33024c7a9d05313588b8c` |
| `test/seon/render/web_debug_test.clj` | `26a4289fe4d7d6923487fe37ca879bce8494fb38bfc723cf367ba03787118ea0` |
| `test/seon/render/web_test.clj` | `8389666948d996d408292f3a528d8954db1f918641a4f4a55cbf17767e3c0ae6` |
| `test/seon/run6_stall_test.clj` | `eea923a3c39d98cfb810fe55c16a2f8611511030413b83fda44c7db05bc1d37e` |
| `test/seon/schedule_test.clj` | `8edc908ed58c7632086f21f3d8386d6ee230adb417cbe728c97664d75d5de37c` |
| `test/seon/schema/datahike_test.clj` | `25d6612f9a01f6699eb04f457b7b6b0edd40b184b69a6e1570c4b0db1cfbf9ba` |
| `test/seon/schema_test.clj` | `77076e0e92e96b0a55078c8b69a5569f95cb5faecedbb17d58aef90dc48450a4` |
| `test/seon/sci/eval_test.clj` | `a1aea5fdcdcd467c81a2900183560db4d8cbe92dff3b5edba060270336a4a5e0` |
| `test/seon/test/selection_test.clj` | `21cb5a278204b0b218bc6a5ff2155856d08fe8eccf5156ab65c6bcde2f3462b0` |
| `test/seon/test_provenance_test.clj` | `548ed70303137f700114bb615ca34e9b125ca8b1e10aae97ba6f0c0baab59069` |
| `test/seon/test_support_test.clj` | `4abacc4edf2caf7ce81beaa07a3cfd0fb68683c43bb503fccaaf2d3a83a10685` |
| `test/seon/turn_loop_test.clj` | `7574a3f3e2b9adca19d249859332fbed5a5534d5ac6a6d9553df60d8b71edb73` |
| `test/seon/turn_work_test.clj` | `cd69e17c7d75059b2f8a26d346c0945034fd15e463a28b656da8d56730026e15` |
