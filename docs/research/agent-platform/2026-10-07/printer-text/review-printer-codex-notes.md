---
title: Printer-text design review
type: review
date: 2026-10-07
lane: review-printer-codex
status: revisions requested
---

# Printer-text design review — review-printer-codex

Review of `design-notes.md` at `e0eca860a`, with root's Q1–Q4 decisions applied.
Verdict: **revise before implementation**. Deleting the reply re-printer is right. The diagnostic replacement
is not yet total, the new full-value link is not executable for strings as specified, and the render-text cut
needs to join the existing cut representation, not remain text assembled inside a leaf.

This review changes only this file. It serves PRINT/FINISH in the one agent path. The existing owners are
`seon.render.value` (selection), `seon.print` (emission), and `seon.repl/response` (composition of saved text).
No new parser, renderer, cache, background process, or retry mechanism is needed.

## 1. Findings, ranked

### P1 — §3 makes the terminal fallback recursive

The proposed `plain-projection` replacement calls `core-text value`. The installed call graph is:

```
core-text → render-ai → prepare
                       catch, structural? → plain-projection
```

Evidence: `src/seon/render/value.clj:235-269,1486-1500,1615-1643`. The structural flag is already true in
`core-text`'s request. If structural printing itself fails, changing the last arrow to call `core-text`
re-enters that same selection. The outer core catch is not a guard against recursion: it cannot take its
last-resort branch until the recursive call returns or throws. A repeated structural failure can exhaust
the stack first. The current core's totality therefore does **not** establish the proposed core's totality.

This is a proposed regression, not a failure deliberately induced in default. The existing failure class is
`seon.render.value/failed-selection-escapes-the-profile-budget`; related task
`seon.render.value/a-lazy-seq-that-throws-shows-as-empty` already names destructive repeated realization.

Smallest correction: keep the projection-free terminal operation inside the one core owner and make the
fallback graph acyclic. Delete `diagnostic-line`, but do not replace a terminal fallback with an entrance
that calls it. Root's ruling retaining the core's projection-free last resort supports this. Prove a persistent
structural failure, as well as a renderer that fails once. Do not add a recursion counter or new dynamic flag.

### P1 — Q1's full-string action is not supplied by the existing API

`seon.print/full` marks only values implementing `IObj`; strings are returned unchanged
(`src/seon/print.cljc:252-265`). `prepare` reads the mark from the input value's metadata
(`value.clj:1554-1559`). Neither `path-url` nor `parse-cursor` carries a full request
(`value.clj:478-482`, `render/data.clj:28-45`). There is no existing full handler in the debug or data route.

Live JVM probe, own agent, explicit default connection:
`(identical? "abc" (seon.print/full "abc"))` returned true; envelope 5 ms.
Thus `(seon.print/full (subs result/e… n))` is also just a normally budgeted string. Adding a link alone
does not fulfill root's decision.

The existing composition to consider first is marking an existing metadata-capable containing value before
printing, using the same `full` and `prepare` path. If the action must show exactly the selected scalar,
the design must name the scalar opt-in representation and the route's consumption of it. Do not implement
full display by `println`, a raw `[:pre]` string, or a second HTML printer. Re-reading the original root,
rather than the already sliced page, is necessary for “whole value.”

This is a design gap against the accepted decision, not a newly discovered undocumented behavior of `full`.

### P1 — Q3 requires the normal cut state, not just a longer projected string

The existing defect is real. `make-node` shortens declared text inside a `:leaf`
(`value.clj:1037-1056`); `more?` returns false for leaves (:846-855). Consequently
`prepare` derives `:truncated?` and `:elisions` as though no cut happened (:1562-1563,1597-1600),
and `omitted-characters` cannot account for this leaf (:1455-1474).

Changing only `make-node` and `omitted-characters` as §8 proposes leaves false truncation/elision metadata.
It also bypasses the character-cut HTML override: that override handles `::print/elided`, whereas the
declared text is still `::print/projected`. A textual dig pasted into that leaf does not become a cut link.

Live SCI, `scratch.review-printer-codex`, form:

```clojure
{:seon.render/ai (apply str (repeat 550 "line \"q\"\n"))}
```

The 4,950-character value was shown ending `line "…+156`, without a dig; both reported
`:seon.eval/value-tokens` and `:seon.eval/shown-tokens` were 1,499. Envelope 0 ms,
result `result/ef85f2f3235f6`. Existing task:
`seon.render.value/render-text-past-the-budget-is-cut-mid-line-with-no-dig-form` (`5d42a25f9a53`).

Smallest correction: represent this as the printer's character cut, retaining raw render-text emission,
and have the same cut state drive charge, omitted count, flags, text and HTML. Reserve space for the
**entire continuation** before choosing the line end. Use the last line end when available; a first line
longer than the budget still needs forward progress. A string literal under the value's own render key
has a known path; a symbol or function under that same key is not a string and cannot be passed to
`subs`. A renderer-produced string needs a truthful refusal unless its output has an actual retrieval
source. Test these as separate rows.

### P2 — deleting diagnostic-line otherwise loses coordinates and repeats printing

`diagnostic-line` adds the path separately; the failures vector contains only exceptions/maps
(`value.clj:996-1005,1582-1583,1608-1609`). Replacing the line by
`(core-text {:seon.render.value/diagnostics failures})` loses that path.

Live JVM probe:

```clojure
(let [failure (ex-info "review sentinel" {:review/token :kept})
      p (#'seon.render.value/plain-projection 42 failure [:member])]
  {:text (:seon.render.value/text p)
   :diagnostic-data (mapv ex-data (:seon.render.value/diagnostics p))
   :replacement (seon.render.value/core-text
                  {:seon.render.value/diagnostics [failure]})})
```

Envelope 4 ms. Current text contains `renderer failed at [:member]`; diagnostic-data is
`[{:review/token :kept}]`; replacement retains the sentinel data but has no `[:member]`.
Keep the coordinate as data at the failure producer before deleting its only displayed occurrence.

The claim “a Throwable in it prints through core-text's own chain handling” also needs precision:
`core-text`'s `shown-chain` branch applies only when its **root argument** is a Throwable (:250-256).
A Throwable nested in this diagnostic map is handled by `classify`/`throwable-value`
(:559-572,746-747). The live comparison confirmed nested `#error {:via …}` versus a root chain
with `:seon.error/frames`. Ordinary ExceptionInfo message/data survived; equivalence of the two
Throwable presentations is false. Do not claim a loss of frames relative to today's diagnostic-line:
today's line deliberately acquires no frames either.

Also `core-html` calls `core-text` again (:265-269). Computing core-text for text and core-html
for the same diagnostic repeats selection/emission. Compute its shown text once and reuse it in the
HTML wrapper, or reuse one existing prepared result. Finally, the structural-retry arm currently
updates text/diagnostics but not HTML (:1640-1643); the new design should explicitly keep both
consumers informed when a structural retry succeeds. The existing lazy-sequence failure task covers
this retry family.

### P2 — data-text deletion is correct, but “no second printer left” is not true

`data-text` has exactly the three named callers in `response-entries`, and `one-form?` has only
`data-text` as its caller. Delete both together. Splicing their already-shown inputs preserves all
characters and removes a second semantic read plus a second print. Keep `value-text` and
`:seon.render.value/value-text`: `sci.eval/shown` uses the latter to separate the value from diagnostics
(`src/seon/sci/eval.clj:3560-3562,3593-3598`). Q2 settles the EDN tradeoff.

But the touched reply owner still prints raw values at:
- `repl.clj:220-221`: findings; :232: interrupted instant; :238: stdout; :242: def note.
- `repl.clj:410`: a full-value operation printed with `pr-str`.
- `repl.clj:115-122`: `source-text` uses `pprint/write`.
- `value.clj:469`: missing-root identity members printed into the error.
- `value.clj:1483`: breadcrumb labels use `pr-str`.

The design notices some, but misses the interrupted instant and root-description case. These remain
under `seon.print.value/printed-copies-stored-or-sent-instead-of-values`; do not close that task.
Q4 correctly excludes `path-url`'s EDN address encoding from this inventory.
`read-shown`/`shown-value` also survive: turn refresh uses them and `repl/text` calls them for
changed history. They are not callers of `one-form?` and cannot be deleted with it without converting
those callers. Walk-delete/S4 owns their retirement.

An immediate smaller reply composition is to end each shown entry with a newline unconditionally.
That makes `ends-in-comment?` unnecessary (:263-270), saves tokenizing every field just to decide
its separator, and is safe for prose, unterminated quoted text and trailing comments. Keep
`syntax-tokens` for actual syntax coloring. This is a suggested whitespace behavior change, not
an additional parser or output format.

### P2 — the listed regressions omit existing laws that this behavior intentionally changes

`seon.print-test/p-tee-generated-grammar-cannot-disagree` (:248-260) compares lexical HTML text
to the text face. Raw string HTML plus CSS-generated quotes intentionally violates that equality.
Preserve the meaningful property (one selection and all original string characters); revise its
text/HTML spelling assumption. Likewise
`seon.repl-test/the-reply-is-one-edn-map-whatever-the-value` (:349-387) explicitly requires prose
renders as a quoted string. Q2 retires that row's assumption, not its handle/timing checks.

Add rows for CRLF, first line longer than the budget, escaped quotes/backslashes, HTML-special
characters, and render keys that hold a string versus a producer. CSS pseudo-content needs browser
verification for visible string boundaries and selection/copy behavior. A hiccup equality test cannot
prove pseudo-elements paint or copy as intended.

## 2. Existing-code and dependency audit

The main inventory is accurate: `string-shown`, `fill-strings` and `fill-end` grow ordinary strings;
`cut-node` makes their dig/link; `leaf-ops` emits escaped literals on both faces; the data view supplies
the route base. Repository search found no other production route-base supplier. The three
`diagnostic-line` call sites and single `data-text` owner are correctly identified.

Qualifications:
- The opening “selection does not change” is false once accepted Q3 is part of the slice.
- The live ordinary-string allocation below supports bounded work. The historical context census,
  544 ms / 136k-call context assembly, 26-link prototype, and “same ms” after claims are measurements
  of another snapshot/prototype, not installed before/after proof. The exact historical context value
  is not supplied here. Do not treat them as a performance gate pass.
- `fill-end` falls back to the character endpoint when no line end fits; it does not guarantee every
  cut is at a line end.
- “Three producers” means three owning areas, not three call sites: message.clj has split-lines at
  :360 and :512; error.clj also uses it at :1979 besides :2206. Converting the vector to a string
  requires the owning schema/consumer change, not merely deleting split-lines.
- `leaf-ops` is contracted, as claimed. The target files print.cljc/value.clj/repl.clj have no diff
  from e0eca860a at review time. Web line numbers moved with another lane's edits; its current /data
  unit is at web.clj:3473-3501; the design's old :3850 locator is historical.

Library checks: pinned Fipp `255a19061f`, Malli `43c6dca8d2`, Clojure `3bc2b3e91f`.
Fipp `edn.cljc:9-21` supplies collection document construction, :38-41 prints string literals;
`visit.cljc:32-52` supplies type dispatch; `engine.cljc:10-46` supplies the document op stream.
None supplies Seon's result handles, continuation coordinates or one token allocation over both faces.
Use the existing Seon `text-op` hiccup override, not a new visitor/printer. Hiccup already escapes the
five HTML-sensitive characters (`src/seon/render/hiccup.clj:227-257`); no new escape utility is needed.
The contract checker uses Malli `function-schema` (core.cljc:3130) through the packaged projection;
nothing here needs a replacement contract compiler.

## 3. Live evidence and allocation

Default answered the review probes, but was **faulted** before probing: PID 91875,
start 2026-10-07T12:18:27.392Z, committed source e80131ef15471d3669360eca0ecab27e2f66dec8.
Both status surfaces reported show-row/shown-failure's nil fault, wrong-branch fault recording,
and reply entity-emission's missing database. Existing references: s2b1-reader's `7f5dfb6ad7a2` /
`fe8f23d6095c`; recording `3d2fb1b41dc8`; response tasks shown in runtime_status. The largest
reported historical over-bound call was call-with-projection-state, max 82,914 ms
(tasks `c4ece4f4eec9`, `d27179bab996`); changed-identities max 1,972 ms
(`483c64e1796e`, `4a627750dc7e`). This is degraded baseline evidence, not caused by this review.
Heap used/live/max at first status: 968,738,592 / 809,355,040 / 10,997,465,088 bytes;
318 platform threads; no open turns. No restart/reset requested.

Own agent: `review-printer-codex`; SCI namespace: `scratch.review-printer-codex`.
JVM reads used `(seon.cluster.boot/connection "default")`. No definitions were loaded into default.
The explicitly created platform thread was joined before each answer. A 1,000-element vector
sanity check read 26,640 allocated bytes and `:virtual? false`.

Measured compiled `prepare`, both faces produced from the same node selection; the input string
and unit are built before the measurement. One warmup per size, three samples, 1,500-token profile:

| Input characters (coordinate size) | ms, three samples | allocated bytes, three samples | armed calls per sample | shown tokens |
|---|---|---|---|---|
| 14,000 | 1.561 / 1.913 / 1.613 | 671136 / 671064 / 671064 | 293 | 1498 |
| 140,000 | 2.316 / 1.692 / 1.828 | 671264 / 671648 / 671192 | 293 | 1498 |
| 1,400,000 | 1.625 / 2.004 / 2.052 | 671456 / 671456 / 671456 | 293 | 1499 |

All nine projections had zero diagnostics. Probe envelope 33 ms, result `result/e5f7690f7559f`.
Allocation is the exact current **platform-thread** delta, not the process-wide upper bound.
The call-count snapshot itself can contribute instrumentation; the count is reported as measured.
Selection has no transactions; per-probe store bytes were not measured, and these numbers exclude
MCP setup/recording, input construction, HTML serialization and browser paint. There is no compiled
“after” because this is a design review. Re-run the identical probe after implementation.

Other probes: root inline prose ending in a comment kept its following result key on a fresh line
(0 ms per form); nested Throwable map versus direct Throwable printing retained ExceptionInfo data
but differed in shape (9 ms); explicit plain-projection path-loss comparison (4 ms).
Two explicit prepare attempts without an effective viewer printed the map structurally (3 ms, 298 calls;
1 ms, 288 calls); they are **not** declared-render evidence. The direct SCI form above is.
My mistaken one-argument `seon.task/open-tasks` call returned a contract error (4 ms); corrected to
`my.task/open` (12 ms). This is a caller mistake, not a system defect. Existing task files were searched
as well; no duplicate task was created for a proposal-only finding.

## 4. Cross-design ownership and simplification

- **S1 / repl-lookup:** latest s1-lookups §4.4 has already accepted a single
  `{:seon.sci.eval/sym … :seon.render/ai text}` result and depends on this raw reply/cut behavior.
  Do not reintroduce the duplicate source member described in older repl-lookup notes.
  S1 also deletes directory renderers in **repl.clj**: a real same-file ownership overlap,
  even though the functions differ.
- **S2a / walk-delete:** current S2a retains `sent-context-html` and `?sent=`; the older walk-delete
  B1 text says delete the sent view. Root's newer S2a ruling wins. Route
  `:seon.render.route/agent-debug` exists (route.clj:15-16), so that part is verifiable.
  The snippet's `query` is not an argument of S2a's shown sent-context-html signature: explicitly
  carry the request query/cursor from the existing handler. Coordinate web.clj and both CSS files
  with their owner. No duplicate debug endpoint or retained prompt copy is warranted.
- **Loop-pass / S4–S6:** when prompt/prompt disappears, convert this view to the held context owner
  in the same cut. Do not build paging around obsolete capture storage or recompute old model calls.
  Live re-derivation can change between clicks; it is not a guaranteed historical snapshot.
- **S5a reply:** held-responses and saved-history must both enter the same response composer.
  Keep value-text/diagnostics separate, and test failing render + later settlement diagnostic.
  Neither path may call data-text after it is deleted.
- **Reader-prose / S2b1:** Q2 concerns printed output. It does not authorize reading the reply
  back as model code. The current reader design reserves `#:seon.repl`; raw prose or an incomplete
  printed cut must not undermine the fabricated-response refusal. Reader tests belong to that
  owner. Old loop-pass and reader-prose work survives as stopped-wip patches, not installed APIs.
- **Skill accuracy:** repl/SKILL.md still says a contractless defn is refused at installation
  and gives old fork-per-turn line locators. These conflict with the current root instructions'
  warn-at-REPL contract and must not be used as authority for this printer slice. No skill edited.

Questions for root's reconciliation (Q1–Q4 are settled, not reopened):
1. Which existing containing value will carry Q1's full mark for a selected string, and who owns
   the route action? If none fits, name the smallest scalar opt-in change before coding.
2. Which terminal call remains below core-text after diagnostic-line is removed? Require an acyclic
   call graph and the persistent-failure proof.
3. Should the reply change also delete ends-in-comment? with unconditional line separation?
   It is a direct simplification enabled by Q2.

## 5. Central proof list and landing

No tests were run by this lane. Root's focused list should include:

- `seon.print-test/a-string-literal-keeps-its-line-breaks-and-reads-back`,
  `one-cut-face-and-held-order`, `p-total-generated-grammar-emits-and-readable-faces-round-trip`,
  and the **converted** `p-tee-generated-grammar-cannot-disagree`.
- `seon.render.value-test/a-failing-renderer-never-replaces-the-value`;
  proposed `an-html-cut-of-a-long-string-shows-text-and-continues` and
  `a-render-text-past-the-budget-is-cut-at-a-line-end-with-its-dig-form`.
  Add `a-full-html-string-action-shows-the-whole-original-value` and
  `a-terminal-structural-failure-does-not-reenter-selection` (proposed names).
- `seon.render.floor-test/the-core-printer-needs-no-render-database-or-config`,
  `the-core-budget-is-fixed-whatever-the-value`, `the-core-and-total-entries-answer-a-hostile-throwable`,
  `the-total-entries-always-answer`.
- `seon.repl-test/the-reply-is-one-edn-map-whatever-the-value` (convert Q2 row/name),
  `an-error-in-the-printers-grammar-is-a-map-not-a-string`,
  `colouring-preserves-every-character-including-malformed-source`,
  proposed `a-rendered-value-with-cuts-is-spliced-verbatim`.
- S2a owns the actual route-click proof; S5a owns held-versus-saved reply parity.
  Those tests need the composed branch, not just this slice's isolated printer tests.

Verification: clj-kondo on a git archive of HEAD `de118d86a67907c220db1cf1827ae7124e0dde59`,
with this notes-only diff (no change to lint inputs), `--lint src script test --cache false --parallel`:
**0 errors, 1,481 warnings, 6,920 ms**; CLI exit 2 for warnings. Full-tree lint is an explicitly
requested repo-sized static operation, not an ordinary agent pass. No shared analysis cache written.
Contract compile owner `seon.contracts-compile-test/check`, archived packaged resources, live candidate
projection: **[] findings, 0 changed contracts, 36.293 ms** (37 ms envelope; require of the
committed checker 131 ms). This is the documentation slice's check, not a full contract census or a
claim that implementation contracts have passed.

An earlier archive at 49d423a1b passed with 0 errors / 1,481 warnings in 11,040 ms, and its contract
check returned [] in 62.674 ms. Dependency-analysis landed fn.clj/fn_test.clj during review, so lint
was repeated on the final HEAD above. Root also restarted default externally: final status is
PID 6749, start 2026-10-07T12:32:12.818Z, source de118d86a, still faulted. The missing checker namespace
after that restart caused one ClassNotFoundException reply (3 ms); requiring the committed checker
again resolved it. No review code was loaded. Final status also showed two cache discipline warnings
(loaded-function-rows and projection), eval-clj max 2,178 ms (existing task e333444a896c), 653 platform
threads and no open turns. The allocation samples above belong to the earlier JVM. The later restart
invalidated their live result handles; their actual forms and returned values are preserved here.

Foreign working-tree edits observed initially: dependency-analysis owned fn.clj/fn_test.clj; S2a owns
web.clj/transcript.clj and transcript schema; other untracked working material and http-kit dirt were
left untouched. The final archive includes committed dependency-analysis and excludes uncommitted
foreign edits. No foreign breakage blocked this review.

Design check: emission overrides and deleting data-text simplify the real path. The mistaken assumption
is that total core-text can safely replace its own terminal fallback. The other material omission is that
render-text is not currently an ordinary cut node. Resolve those at their owners, then make the deletions.

Changed source/test lines: **0 / 0**. No provider/model calls. No worktree, runtime reload, restart,
reset, or test JVM. No new runtime tasks: proposal gaps are recorded here; observed installed defect
classes link their existing tasks above. Scratch is lane-owned and disposable; the SCI branch is
disposable, and the cited observations survive in these notes.

## 6. Reproducible platform allocation form

MCP JVM mode, cluster default, agent review-printer-codex; no definitions or source reload:

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      profile {:seon.render.profile/id :seon.render.profile/review :seon.render.profile/token-budget 1500
               :seon.render.profile/max-depth 8 :seon.render.profile/max-children 32 :seon.render.profile/composition :multiline}
      inputs (mapv (fn [n] [n (.repeat "a \"q\" \\ <tag>\n" n)]) [1000 10000 100000])
      result (promise)
      thread (.unstarted (Thread/ofPlatform)
               (bound-fn []
                 (deliver result
                   (mapv (fn [[n text]]
                     (let [unit {:seon.render/value text :seon.render.value/root 'result/review
                                 :seon.render/profile profile :seon.render.value/route-base "/review"}
                           sink (:seon.batch/sink seon.batch/*world*)]
                       (seon.render.value/prepare unit)
                       (mapv (fn [_]
                         (let [calls0 (reduce + 0 (vals (seon.batch/executed sink)))
                               id (.threadId (Thread/currentThread))
                               a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id)
                               t (System/nanoTime) p (seon.render.value/prepare unit)
                               elapsed (/ (- (System/nanoTime) t) 1e6)
                               allocated (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id) a)
                               calls1 (reduce + 0 (vals (seon.batch/executed sink)))]
                           {:input-chars (count text) :ms elapsed :bytes allocated :calls (- calls1 calls0)
                            :shown-tokens (seon.ai.tokens/estimate (:seon.render.value/text p))
                            :omitted (get-in p [:seon.render.value/tree :seon.print/omitted])
                            :diagnostics (count (:seon.render.value/diagnostics p))
                            :platform? (not (.isVirtual (Thread/currentThread)))})) (range 3)))) inputs))))]
  (.start thread) (.join thread) {:basis (seon.db/basis-t database) :samples @result})
```
