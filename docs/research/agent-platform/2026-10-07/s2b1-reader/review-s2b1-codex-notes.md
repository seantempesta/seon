---
title: S2b-1 reader design review — Codex
type: review
date: 2026-10-07
lane: review-s2b1-codex
status: changes requested
---

# S2b-1 review

**Changes requested.** Deleting Parinfer, trials and suffix guessing is the right reduction. Keep the existing
SCI parser and the line policy in its existing owner. The remaining blockers are error continuation in the wrong
owner, a discard crossing the line/fence policy, and an armed allocation claim that does not meet the stated budget.
The proposed regression also cannot observe the failure it claims to prove.

Reviewed with root's supplied decisions applied: `#` followed by a character other than space or `#` starts a
column-zero form; same-line remainder stays prose; C7 changes; unfenced `ns=> (form)` stays prose. No request to
reopen those decisions. `turn.clj` waits for `s5a-reply` to release it; root removes the dependency at landing.

This review serves READ and its error delivery into PRINT/RECORD. The existing owners are `sci/parse-next`,
`forgive/next-form`, `seon.batch` and the turn's carried-error row. It needs no new parser, cache, runner or loop.
Delete more of the handwritten recovery scanner, and make both temporary segmentation and the eventual live fold
consume the same read-step decisions.

## Evidence basis and limits

- Read lane-rules first, AGENTS, the Clojure, REPL, testing and Datahike skills, spec §4.3 and slicing, the whole
  design, r-read, the stopped reader-prose patch, and neighboring repl-lookup, walk-delete and s5a-reply designs.
  `reader-prose/design-notes.md` does not exist; its surviving authority is the stopped patch. The loop-pass
  directory contains saved runs, not a design-notes.md. The approved agent-loop spec supersedes the old designs.
- Archive used for independent measurements/checks: `33a7bb0226367a68a8d68ed629b1aa77cd222b3c`, with its pinned
  dependency sources. edamame `196ea5e4eff390428d8cfd33661cc415a2eec76b`, SCI
  `ce0aa53b9bf35029e0bc27a962d799c196a37874`, Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`.
  No worktree, tests, provider requests, default reload, stop or reset.
- Default initially ran archive `ec5f13d2faf8eeb332c4e4adea43ba845e26d027`, pid 75294. All live measurements below
  precede its replacement. Probes use MCP, agent `review-s2b1-codex`, explicit cluster default; JVM measurements
  pass `(seon.cluster.boot/connection "default")`; SCI uses `scratch.review-s2b1-codex`.
- Prototype was copied read-only from the design author's saved
  `scratchpad/s2b1-reader/src/s2b1/forgive.clj` into my disposable archive measurement environment. It ran only in
  an isolated JVM. It is NOT the final implementation: it predates Q1, returns a string imitation and the old
  refusal keyword, and continues errors in `:code`. Measurements exercise `:reply`; the mixed corpus's dispatch
  forms are unaffected by Q1. Typed-error construction and delivery remain unmeasured after-change costs.
- Static source citations below use the archive unless explicitly named otherwise. The design's opening claim
  of no owner changes between `0c2a12900` and `2c36b20c2` is borne out by Git, but some line citations do not match
  the claimed revision: at `2c36b20c2`, segmented-sources/planned-sources/code-sources are 3009/3036/3052,
  read-row 4668 and show-row 4733. The forgive and batch citations largely match. Use symbols plus the pinned
  revision when reconciling the turn hunks.

## Findings, ranked

### P1 — Failed-read continuation remains outside the shared stepper

Design §4.3 adds `failed-end` to `forms`, then §5/Design check says S6 inherits `next-form` unchanged and merely
deletes `forms`. Those cannot both hold. The prototype's `read-at` returns `:seon.batch/unread-from` even for a
recoverable stray closer. Only `forms` turns that into a segment and continues. `seon.sci.batch/read-next`
(`src/seon/sci/batch.clj:23`) directly delegates to next-form. `seon.batch/form-step` (`src/seon/batch.clj:601`)
treats **any** unread-from as terminal and records an unread tail, not an error entry followed by the next form.

Isolated probe `next-form` on `"```clj\n)\n(+ 1 2)\n```"` answers
`{:seon.batch/unread-from 7 :seon.batch/end 8 :seon.batch/reason "Unmatched delimiter: )"}`;
`segments` on the same text answers sources `[ ")" "(+ 1 2)" ]`. The disagreement is observed, not hypothetical.

Further, `failed-end` does not implement the approved “reader's advanced cursor”: a collection is scanned again
to a guessed closer by `balanced-end` (`forgive.clj:159`), rather than using the reader's stopped position.
The scan counts unlike delimiters identically and understands only selected prefixes (`collection-open:137`).
For `[1 bad: (+ 2 3)]`, it skips the remainder of the collection after the token error; the reader itself stopped
at `bad:`. This is additional recovery semantics, not simply a source span obtained from SCI.

**Smallest correction:** make the next-form result own the error and advanced version, using the existing
`:seon.batch/read-error` variant (schema at `seon.batch.edn:74`, consumed at `batch.clj:412,429`). Have segmentation
project its source while the fold consumes its error. Remove `failed-end` and, if their remaining consumers are
gone, `balanced-end`/`collection-open`/`string-end`. Preserve the explicit current `:code` compatibility decision
as a temporary mode distinction, not a second recovery algorithm. Do not move guessed recovery into S6.

**Library seam:** edamame `impl/parser.cljc:768-792` explicitly consumes an unexpected delimiter before throwing
so the caller can continue; `parse-to-delimiter:275-315` adds the actual container evidence. SCI
`impl/parser.cljc:142-190` preserves that cause/data. This is stronger evidence than a second balancing scan.

**Proof needed:** the same stray-closer and token-error inputs through segmentation and the actual read-step/fold;
error value, advanced cursor and later successful form agree. Include EOF-in-string/collection, odd map,
mismatched delimiters and metadata/namespaced-map prefixes as rows of the existing proposed table.

### P1 — `#_` can carry prose or a closing fence into a code source

Live installed SCI probe:

```clojure
(seon.sci.forgive/segments "#_ (+ 1 2)\nDone.\n(+ 3 4)" :reply)
;; value: {:seon.batch/segments
;;          [{:seon.batch/source "#_ (+ 1 2)\nDone."}
;;           {:seon.batch/source "(+ 3 4)"}]}
```

Result `result/e21e974f5332c`, 0 ms (rounded), 43 armed calls. The saved prototype answers the same. With
`"```clj\n#_ (+ 1 2)\n```\nDone.\n(+ 3 4)"`, its first source is
`"#_ (+ 1 2)\n```\nDone."`: the closing fence was read as Clojure syntax instead of changing fence state.
This is not the approved unclosed-form-to-EOF rule: the discarded collection is complete.

`parse-next` is a next-*value* operation, not a next-authored-construct boundary. edamame's `#_` dispatch
(`impl/parser.cljc:568-578`) returns `continue`, and `parse-next:836-837` recurs internally. The line classifier
gets no chance to classify the next line. The same family includes reader conditionals that select no value;
structural `:read-cond :preserve` and the live reader's features must not silently choose different boundaries.

**Correction:** define how no-value reader constructs return control to the one line-policy owner. Investigate
the existing edamame `:uneval`/`:read-cond` seams through SCI before adding anything; `:uneval` currently reads
the next value too, so it is not already the required stop-at-discard hook. If upstream needs a small seam,
name that explicitly. A second prefix parser or a second full Markdown pass would worsen this design.

New task on default: `seon.sci.forgive/discard-consumes-prose-and-fence-boundaries` (`c98bb97a06a3`), with these
inputs and the line-rule table as its proof. This task records an installed defect, not a prototype installation.

### P1 — Armed allocation disproves the claimed 5 MB budget; counts now measured

The prototype reduces the read cost substantially, but design §9's “Pass budget … 28.6 KB costs 4.55 MB” comes
from an unarmed run. Under Seon's real host wrappers the same reader work costs **5,942,216 allocated bytes**,
before the live second read, evaluation, recording or final typed-error construction. A whole pass cannot be
certified under 5 MB by a reader that already exceeds it. “S0 is dominated by refresh” does not waive the budget.

Both reader namespaces were armed with `seon.instrument/apply!`, 50 contracted Vars total, using the archive's
packaged projection. The only schema overlay admits `:imitation` alongside the old repair kinds, so both versions
can be measured together; it does not replace collection contracts with permissive maps. No global default JVM
instrumentation changed. Each measurement binds its own `seon.batch/*world*` sink; counts are not noisy global
counter deltas. `seon.batch/calls`/`call-profile` are the installed owners (`batch.clj:119,133`);
Malli `-instrument` and Seon's `compiled-wrapper` validate inputs/outputs as usual.

Median of five runs, after three warm calls, same isolated platform thread and JVM; exact
`ThreadMXBean/getCurrentThreadAllocatedBytes`. Calls below are the **reader namespaces' armed calls**; other
production wrappers are excluded from both columns, so these are not total pass counts.

| Input | Old ms / bytes / armed calls | Prototype ms / bytes / armed calls | Sources old → new |
|---|---|---|---|
| rr ×1, 9,557 chars | 13.189 / 5,094,600 / 2,119 | 3.697 / 1,993,184 / 1,458 | 43 → 49 |
| rr ×3, 28,671 chars | 15.301 / 15,007,232 / 6,307 | 6.477 / 5,942,216 / 4,344 | 129 → 147 |
| rr ×8, 76,456 chars | 34.178 / 39,660,400 / 16,777 | 24.568 / 15,521,640 / 11,559 | 344 → 392 |
| 100 × prose/form pair, 2,100 chars | 9.217 / 7,254,760 / 3,110 | 2.622 / 2,374,984 / 2,009 | 100 → 100 |
| 400 × same pair, 8,400 chars | 42.232 / 28,845,496 / 12,410 | 21.120 / 9,381,232 / 8,009 | 400 → 400 |
| 1,000 prose lines, 56,000 chars | 7.998 / 6,143,688 / 1,123 | 2.223 / 5,537,064 / 6,010 | 0 → 0 |

The last row is **not equivalent work**: the installed guard stops after 65 repairs (`forgive.clj:319`), whereas
the prototype processes the prose. Do not use it as a same-work speedup. The mixed corpus allocation is roughly
linear over these sizes; timing alone does not prove an asymptotic bound. The proposal still passes a growing
repairs vector through several armed contracts per form; avoid claiming total linear validation work without
counting that traversal (Malli's vector validator checks its members).

Independent default check on the exact rr ×1: 9.745 ms, 6,645,176 bytes, 2,262 total armed calls,
**2,119 reader calls**, 43 sources (`result/efecb87233094`). The exact reader count agrees with the isolated
baseline. Saved loop call-1 alone: 3.891 ms, 772,784 bytes, 250 total calls, four sources
(`result/e996b2b813282`). The design's historical 805/live-01 count is not a current whole-pass baseline.

**Concrete simplifications to measure first:** retain read-one's returned version/reader on successful reads
instead of `(version t end)` per form; move the existing cursor across skipped lines rather than construct another
reader for each. Prototype rr ×1 makes 186 `version` calls for 49 forms. It also makes 267 `fence-line?` calls in
`comment-of`, after the first pass already classified fences. Stop reclassifying text whose accepted spans and
comments the reader already holds. No cache or validation exemption is proposed. Historical cost tasks remain
`seon.sci.forgive/a-prose-repair-replays-the-whole-reply` and
`seon.instrument/hot-internals-pay-armed-validation` (`ee57516cbaef`).

Pure segmentation writes no store data; store bytes/commits for the whole proposed pass are **unverified**.
These measurements are evidence about the saved prototype, not approval of a final armed implementation.

### P2 — The proposed refusal regression cannot observe the first row, and misses an existing test

Design §10 proposes `test-support/evaluate` on two forms and assertions about the refused first entry and
`:errors` on the second. That helper explicitly returns **only the last entry's evaluation/shown-map**
(`test/seon/test_support.clj:378,435-438`); it does not expose the preceding entries or batch diagnostics.
It also removes settlement, observe, derive, facts, apply and fold. It cannot prove the live reply/record boundary
or the “no fault on the next form” claim.

An existing owned test is a better starting point: `seon.turn-test/a-refused-response-map-settles-as-an-unrun-evaluation`
(`turn_test.clj:246`). It reaches `turn/evaluate-sources` and currently asserts the old message
`"Only the REPL writes responses"`, so the proposed new message breaks it. The design did not list it.
Extend that test with a following successful form, assert the refused row's typed value and absence of the nil
fault, and cover the settled/MCP receipt through the existing ordinary-run proof. Use DATA rather than another
message substring assertion. This serves the already-linked `fe8f23d6095c`/`7f5dfb6ad7a2` defect class without
another partly overlapping fixture.

### P2 — The deletion/caller inventory is incomplete

The two-pass account, Parinfer's sole production require, normalized SCI parser seam and batch's missing
error-value carrier are accurate. But these additional callers affect the claimed deletion and compatibility:

- `seon.turn/preview-sources` calls **planned-sources**, not code-sources (`turn.clj:5004,5028`), reached by
  `seon.render.web/render-source-call` (`web.clj:1398`). A generated render block that is a bare keyword,
  symbol or multiple forms on one line now changes behavior there as well as in `declared-sources:1777`.
  Hunk (b) needs this caller accounted for, or its deletion assigned to the appropriate cut. No new preview parser.
- `seon.sci.reader/read` does **not** die just by removing refresh and evaluable-source?. Surviving calls include
  `sci.eval/one-event:548`, `arglists-reader:1929`, `evaluated-source:3480`,
  `cluster.agent/namespace-seed-call:185`, and `render.value/read-artifact:1697`. Some are destined for other cuts;
  none can be assumed deleted by the two listed consumers. Its fabrication check remains duplicated until the
  actual closure is retired. Link `seon.sci.reader/fabrication-refusal-guards-every-reader` (`83b10f8c64d2`).
- JVM read calls `forgive/skip-to` (`turn/batch.clj:874`). Keeping its current behavior is correct for this slice;
  deleting the entire forgive namespace in S6 must move or eliminate that real caller too.
- `planned-sources`' own docstring still teaches marker stripping/repair. `(help)` must teach the accepted fence
  convention with the reader landing, as required by §4.3; name its owner rather than leave the teaching stale.

### P2 — Fence reopening is extra semantics, not a library guarantee

`fence-kind` treats a tagged line inside any open fence as a new opener. Spec §4.3 says a non-code fence's lines
are prose; it does not specify switching a non-code body to executable code at a tagged line. The prototype
therefore executes `(+ 1 2)` in `"```text\n```clj\n(+ 1 2)\n```"`. The earlier infinite-loop incident explains
why progress must be guaranteed, but does not authorize that semantic recovery.

Simplest interpretation: an open fence stays open until its declared closing line; otherwise its body follows
that fence's mode. If root intends tagged-line reopening, put that rule in the spec and the table. Do not describe
it as CommonMark or as a consequence of “unclosed forms reach EOF”. This is a bounded policy question, not a
reason to add a Markdown parser.

## Cross-design reconciliation and exact central proofs

- **s5a-reply:** honor the turn.clj hold. Ship imitation producer and consumer together once the file is released,
  or explicitly keep imitation delivery incomplete; do not call it done between landings. Its new held-receipt
  reply must preserve the typed error on the correct ordinal. My final task lookup briefly hit the root's restart;
  `7b5535cf6` landed this slice during review. I did not operate its session or files.
- **loop-pass / S4/S6:** no-form/only-read-error replies must not acquire successful-evaluation semantics merely
  because an imitation report becomes an artificial first row. Test imitation-only, then imitation + form.
  The current design defers trailing prose correctly, but its ownership must travel to the exchange, not disappear
  when `forms` is deleted. Fenced `ns=>` tokens deliberately error under root's ruling; help and the model's
  presentation need the same convention.
- **reader-prose:** do not revive `:fenced`, its whole-text rewrite, prompt-block skipping or Parinfer. The design
  is correctly smaller than that stopped patch.
- **repl-lookup:** bare lookup calls are lists and survive; bare result symbols require code mode/fences. Its
  arglists-reader deletion and help changes overlap the inventory/teaching above; C7 belongs to the reader change.
- **walk-delete:** generated-opening/preview callers and `loop_proof_test` overlap. Delete the obsolete generated
  caller if that cut lands first instead of changing it twice. Do not retain `reader/read` based on the false claim
  that all its other consumers are already gone.

Root's focused list (no tests run by this review):

1. `seon.sci.forgive-test/recorded-replies-read-into-the-same-segments`.
2. Proposed `seon.sci.forgive-test/the-reader-reads-a-reply-by-its-line-rules`, with dispatch under Q1 (`#inst`,
   `#uuid`, invalid `#1`, heading `# `, `##`), same-line vs fenced forms, discard/conditional, cursor recovery,
   fence mode and imitation-only cases. Add cases to this table, not separate overlapping tests.
3. `seon.sci.forgive-test/a-reply-reads-in-allocation-linear-in-its-length`, armed; plus the same measured corpus
   budget row after final typed-error construction. Do not encode noisy wall-clock assertions.
4. `seon.turn-test/a-refused-response-map-settles-as-an-unrun-evaluation`, tightened as above.
5. `seon.turn-test/a-reply-keeps-its-dispatch-macro-forms-as-code`.
6. `seon.repl-parity-test/parity-c7` (the exact generated Var name).
7. `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent` for unchanged MCP mode and
   typed-error receipt delivery; root owns its longer integration bound.
8. `seon.ai-test/captured-run4-replies-stop-before-fabricated-responses` and the retained relevant portion of
   `seon.loop-proof-test/virtual-loop-end-to-end` (the design's help-trial label is a testing block in that Var).

Before declaring installation: publish/adopt both schema resources and their consumers on a live-store branch,
compile complete helper contracts, then repeat the armed probe on the installed landing. The existing step
schema already distinguishes `read-error` from terminal unread; use it rather than invent a parallel carrier.

## Reproduction of the count measurement

The disposable driver requires both unchanged namespaces, contracts-compile-test, instrument, batch and the
value printer. All results above are printed through `seon.render.value/core-text`; no new result printer.
The essential measurement body, with `f` either existing segments Var, is:

```clojure
(let [sink (seon.batch/sink)
      bean ^com.sun.management.ThreadMXBean
      (java.lang.management.ManagementFactory/getThreadMXBean)
      b (.getCurrentThreadAllocatedBytes bean)
      t (System/nanoTime)
      result (binding [seon.batch/*world* {:seon.batch/sink sink}] (f text :reply))
      ms (/ (- (System/nanoTime) t) 1e6)
      allocated (- (.getCurrentThreadAllocatedBytes bean) b)]
  {:ms ms :bytes allocated
   :calls (:seon.batch/call-count (seon.batch/call-profile sink))
   :sources (count (:seon.batch/segments result))
   :by-symbol (seon.batch/calls sink)})
```

Arming: `contracts/packaged-projection "."`; replace only repair-kind in its forms with
`[:enum :prose :imitation :prompt-marker :delimiters]`; `contracts/population-projection` on that value;
`instrument/apply!` with that projection and `:seon.profile/definition-digests` mapping each contracted symbol in
`seon.sci.forgive` and `s2b1.forgive` to nil (unknown digest in the disposable measurement). It returned
`{:seon.instrument/registered 50 :seon.instrument/instrumented 50}`. Median samples exclude arming and printing.

rr is live replies 01,05,06,07,08 then loop replies 1,2, joined by one newline. Repetitions use `(apply str (repeat
n rr))`. The mixed pair is `"Note: ready.\n(+ 1 2)\n"`; prose-only line is
`"Note: here is prose, with (a form), `code` and left: 6.\n"`. Input characters here identify reproducible source
sizes, not an estimate of the agent's shown context. The compact live count result is approximately 90 tokens.

## Health, checks, and landing

- Initial status: ready but `:faulted`, three open faults, ten errored receipts, seven unowned namespaces,
  five over-bound warnings. Top warnings: call-with-projection-state max 612,877 ms (tasks `d27179bab996`,
  `c4ece4f4eec9`), analyzed-files 2,365 ms (`d0022e8fb20b`), publish! 3,971 ms (`9665c2ed25f0`), index! 3,725 ms
  (`12037fb1e077`, `84fdc131a599`, `8b62adf64442`), adoption 1,849 ms (`5dbb7de36bdb`). Heap-live 854,491,152 bytes.
  Status also reported no completed maintenance receipt for this checkout; no collection was attempted here.
- Mid-review default was replaced externally. One read-only task query returned “Cluster default is not ready:
  it is starting”; after independent work, status reported pid 91875, archive `e80131ef1`, ready in 13,697 ms.
  The query then succeeded (4 ms). No prototype entered either JVM. Three faults remained, including show-row's
  nil error (existing `7f5dfb6ad7a2`), record-fault custody (`3d2fb1b41dc8`), and response/entity-emission's missing
  database (`s5a-reply` boundary; status task refs include `405c17a9414b`, `87e2aa8555ae`).
- Stale REPL-skill claims encountered: old line locations and a refusal claim for missing contracts. Keep target
  and installed behavior distinct; existing task `seon.turn/repl-and-flow-skills-describe-retired-loop-contracts`
  (`53e2a265edb0`). Skills are outside this notes-only ownership; no unauthorized edit.
- Initial static checks on the measurement archive: `clj-kondo --lint src script test --cache false --parallel`:
  **0 errors, 1,482 warnings**, 10,126 ms reported (10,161 ms wall). Exit 2 is warnings, not an
  error-free-warning claim. Required whole-tree check.
- `seon.contracts-compile-test/check` over its `source-files ["src" "test"]`, packaged archive projection:
  **475 paths, 0 findings**, 4,011.657 ms, 3,759,162,056 allocated bytes, 17,603 ms process wall including loading.
  This invokes the compile checker, not deftests. Over-budget checker work links existing
  `seon.schema/contract-check-on-three-files-exceeds-one-second` (`d83330412fd2`) and
  `seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing` (`12501877aeb3`).
  A passing correctness check is not a passing cost result. No shared analysis cache was written.
- Because s5a-reply changed HEAD during review, repeated both required checks on a fresh source extraction of
  **`49d423a1be8d4c63c628f10609672b7be619941b` plus this note**. Final lint: **0 errors, 1,481 warnings**,
  9,821 ms reported / 9,907 ms wall. Final compile check: **475 paths, 0 findings**, 6,048.167 ms,
  3,754,498,448 allocated bytes / 30,734 ms process wall. Same existing checker-cost tasks. No test run.
- Foreign boundary: other lanes' dirty turn, evaluator, MCP, transcript, schema and test edits were excluded by
  the archive. Subsequent commits/restarts are not after-change evidence for this reader proposal. Initial
  disposable setup misses (nested babashka/fs archive and dependency-pins.txt) were corrected before measurements;
  those were my archive setup mistakes, not product findings.
- Cleanup observation: selecting only agent id/branch from a pulled map still triggered the full agent renderer,
  showing 1,471 tokens from a value estimated at 2,985 tokens (`result/e5d961ab40641`, 0 ms, 61 calls), including
  unrelated run history. Existing `seon.render/declared-metadata-selects-a-value-that-no-longer-fits` covers this;
  a vector of the two scalar fields answered the actual branch in 0 ms / 49 calls.
- Self-retirement `(my.agent/retire-branch! {:seon.store/branch :agent-3ccddf97c3a4})` returned
  `{:seon.error/message "run transition refused: admission-closed"}`. Existing
  `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` (`bc5e022a68e6`). Leave this named branch
  for root's ordinary retirement; do not enter another lane's session to repair it. The owned archive JVMs exited;
  disposable local files were removed after evidence was written here. No reset is required for this cleanup.
- The first notes-only commit attempt was refused because `s2a-code` had
  `test/seon/rereads_panel_test.clj` staged in the shared index. Its entry was not changed or included by this lane.
- Change: this review note only; **net src/test lines 0**, no runtime installation requested. No restart or reset
  is needed for the review. Final commit identity/size is reported by the landing reply.

## Design check and owner questions

The design holds for the common fenced/unfenced classification and deletes expensive machinery. It does not yet
hold as the one reusable error/continuation policy, nor as an armed pass-budget proof. The retained cursor and
already classified comment spans offer smaller changes before any new mechanism is justified.

Two decisions remain genuinely unspecified by the supplied rulings: how completed no-value reader constructs
yield back to the line classifier, and whether a tagged fence line may reopen a non-code fence. Resolve those
in their owning spec/table. Do not reopen Q1/Q2 or work around the held turn file. Root reconciles the findings;
this lane has changed neither the design nor production/test code.
