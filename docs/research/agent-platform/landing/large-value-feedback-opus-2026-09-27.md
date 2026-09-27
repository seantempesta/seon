---
type: landing
lane: eval-feedback-opus (owner slice 2026-09-27: large values are feedback)
created: 2026-09-27
status: "committed: budget 15,000 → 1,000; copyable continuation on every cut; seon.print/full opt-in; LARGE VALUE bound; renderer ceiling deleted. Recording (whole/shown tokens, elisions, full) is patch P2 for root; one test expectation is patch P3. RESET NEEDED. Tests ran only as scratch copies on default (pid 45188)."
---
# Landing: a large value is feedback, not a bigger print (2026-09-27)

What exists after this cut:
- The agent's per-value budget is 1,000 estimated tokens.
- Every cut the floor makes prints the plain-Clojure form that reads the rest of that value, e.g.
  `…2968 (drop 32 result/e7b3)`.
- `(seon.print/full result/eN)` prints one value whole.
- A value whose whole estimate is over 1,000 tokens gets one line above the reply map:
  `;; LARGE VALUE: result/eN is ~N tokens whole (shown M). A value this big usually means a query or function returns more than it should.`
  The line is declared in `:seon.config.eval/bounds`.
- Once P2 lands, the whole and shown tokens, the number of cuts and the full opt-in are all stored on the receipt.

## Broken first / not done

1. **LARGE VALUE cannot fire until root applies P2.** The finding reads `:seon.eval/value-tokens` and
   `:seon.eval/shown-tokens` from the evaluation. Only `seon.sci.eval/shown-result`, `seon.turn` settlement and the
   transcript emission can put them there, and all three are held. Apply
   `tmp/eval-feedback-design-opus/p2-value-size-recorded.patch` in the same adoption. Without it you still get:
   - the 1,000 budget;
   - the continuation forms;
   - the opt-in;
   - the other findings.
2. **Renderer "no exemption" is only half done.** Declared AI render text is now cut at the value's own token budget.
   The separate `:seon.print/rendered-token-ceiling` dial (50,000) is deleted, following the 27eab14c6 precedent for
   dial deletion. But the allocator's `charge` still subtracts rendered widths (`(:rendered state)`), so a nested
   render is budgeted apart from the raw nodes around it. Counting it would make a clipped root render refuse as
   insufficient budget, because the clip marker plus the legend exceed B. That needs the clip to become a chunked
   string node. That is a larger allocator change, not done here.
3. **The whole-value estimate is a lower bound, and biased on growing scalars.** The floor estimates the omitted part
   from what it already knows at each cut:
   - a string's exact unshown characters;
   - omitted members times the mean width of the shown members;
   - a pending lazy tail counts as 0.

   Measured against `pr-str`:

   | Value | Estimate (chars) | Actual (chars) |
   |---|---|---|
   | 200 rows of maps | 15,354 | 15,099 |
   | 10,000-character string | 10,032 | 10,002 |
   | `(vec (range 3000))` | 5,124 | 13,891 |

   The vector is underestimated because the shown members have 1–2 digits and the omitted ones 4. It is still about
   1,600 tokens, so the bound fires. Nothing is printed to count it.
4. **Two value_test reds reproduce identically on the live, unchanged `seon.render.value`, so they are not from this
   cut:**
   - `a-lazy-result-that-throws-while-printing-throws-as-clojure-prints-it`: the floor prints a diagnostic, it does not
     throw;
   - `large-values-have-bounded-work-in-both-faces`: the string's HTML contains 1,000 characters of the raw value at a
     1,024-token budget.

   24 more error in a scratch run only because their fixture requires `seon.test/run`.
5. **Observed, not mine:** on branch `efd-6`, an MCP SCI `(vec (range 3000))` answered only
   `#:seon.turn{:id "b4e11cc585aa"}`, with no reply map. It is the post-one-commit reply path; root should check it.
   Branches `efd-3` and `efd-6` are retired.

## Changed (commit below)

- `src/seon/print.cljc`:
  - `continuation`: the copyable form for a cut. Members read with `(drop n …)`, characters with `(subs … n)`. The
    path uses `get-in` when every step is a key or index, and `seon.print/value-at` for typed steps.
  - `form-text`: prints `'x` for a quoted form.
  - `elided-text`: the one text face of a cut, the marker followed by its continuation. The emitter prints it and the
    allocator measures it.
  - `elision` carries `::continuation`, supplied or derived.
  - `full`: marks a value with metadata to print whole. A string or number is returned unchanged, and `println`
    prints a long string whole.
- `src/seon/render/value.clj`:
  - `node-continuation` is the single owner of the continuation, used by both `cut-text` (widths) and `cut-node`
    (print), so a cut is measured with the same text it prints.
  - A map or set cut reads the whole collection, because its shown order is not its iteration order, so `drop`
    would name the wrong rest.
  - `omitted-characters` builds the estimate.
  - `prepare` returns `:seon.render.value/elisions`, `/whole-characters`, and `/full?` when the value carries the
    opt-in. With the opt-in, the budget, member, depth and string bounds are unbounded.
  - The rendered ceiling is now the token budget.
  - The legend no longer names a second read route.
- `src/seon/repl.clj`: each bound entry declares its line as `:seon.repl/finding` parts. A string part is printed
  verbatim; a keyword part is replaced by the evaluation's value, e.g. `:seon.repl/result` → the handle. There are
  still no measure-specific branches.
- `resources/seon/schemas/seon.config.eval.edn`: four bounds with lines:
  - SLOW: `:seon.eval/duration-ms` over 1,000;
  - LARGE VALUE: `:seon.eval/value-tokens` over 1,000;
  - LARGE ALLOCATION: `:seon.eval/allocated-bytes` over 512 MiB;
  - LARGE OUTPUT: `:seon.repl/out-tokens` over 500.
- `resources/seon/schemas/seon.print.edn`:
  - `:seon.print/continuation` and `:seon.print/full` declared;
  - the rendered-ceiling dial deleted.
- `resources/seon/schemas/seon.render.value.edn`: the three projection keys are declared.
- `config/default.edn`: `:seon.config.render.agent/token-budget` goes from 15,000 to 1,000, with its provenance; the
  ceiling row is deleted.
- Tests:
  - `test/seon/render/value_test.clj`: new `every-cut-prints-the-plain-clojure-form-that-reads-its-rest` covers
    evaluating the continuation, the string `subs` case, the full opt-in and the estimate. Four expectations were
    updated to the new cut face.
  - `test/seon/repl_test.clj`: the LARGE VALUE line, and the SLOW/LARGE OUTPUT lines.

Size: +255 / −80 across 9 files.

## Evidence (default pid 45188, HEAD 5aef37f42; scratch copies loaded into `scratch.efd-*`, never over default's Vars)

- `(vec (range 3000))` prints `[0 1 … 31 …2968 (drop 32 result/e7b3)]`.
- A 200-row map prints `…168 (drop 32 (get-in result/e7b3 [:rows]))`, and a map cut prints
  `{…3 (get-in result/e7b3 [:rows 31])}`.
- A 10k string prints `"abab…"…+6944 (subs result/e7b3 3056)`.
- A lazy infinite seq prints `…? (drop 32 result/e7b3)`.
- `full` of the 3000-vector: 0 elisions, 14,087 characters, 48 ms.
- Warm `prepare` timing, median of 15 runs, new vs live:

  | Value | New | Live |
  |---|---|---|
  | rows | 7.6 ms | 7.1 ms |
  | 3000-vector | 0.43 ms | 0.60 ms |
  | small set | 83 µs | 94 µs |

  No regression.
- Scratch test runs:
  - repl_test: 18 tests, 93 assertions, 0 failures;
  - value_test: 94 pass. The 2 failures are the ones item 4 shows are unchanged from live. The 24 errors are fixture
    errors (need `seon.test/run`).
- Static checks:
  - `seon.contracts-compile-test/check` over the five changed source/test files returns `[]`; over P2's four patched
    sources it returns 0 findings.
  - `seon.schema/declaration-projection` over the checkout forms builds 3,552 forms.
  - P2's receipt and `:seon.sci.eval/shown` validate with the new keys.
  - `seon.config-test/checkout-effective` gives budget 1000 and no ceiling key.
  - clj-kondo: 0 errors, with the same warning counts as HEAD.

## Patches for root

- **P2** `tmp/eval-feedback-design-opus/p2-value-size-recorded.patch`. Passes `git apply --check` on the tree.
  - `seon.sci.eval/shown-result` records the value's size beside its shown text: `:seon.eval/value-tokens` (via
    `tokens/estimate-of-characters` of the floor's whole-characters), `:seon.eval/shown-tokens`,
    `:seon.eval/elisions` and `:seon.eval/full`.
  - Success and failed evaluations carry these keys.
  - `seon.turn` settles them onto the receipt.
  - The transcript emission carries value/shown tokens, so the finding fires over MCP and in the next context.
  - `seon.render/agent-render-profile` stops reading the deleted dial.
  - Schemas: `seon.eval.edn` attributes, receipt entries, and `:seon.sci.eval/shown`.
  - Afterwards the owner's two questions are queries over receipts:
    - "does 1,000 elide too often?" is `[?e :seon.eval/elisions ?n]` grouped by agent;
    - "who returns large values?" is `:seon.eval/value-tokens` joined with `:seon.cluster.eval/source` and the run's
      agent.
- **P3** `tmp/eval-feedback-design-opus/p3-cut-text-test-expectations.patch`: `test/seon/instrument_test.clj:713`
  expects the new cut face. `test/seon/render/transcript_test.clj:500` asserts the cut includes `transcript-cut` via
  the old legend form. It may turn red. I could not predict it without running it: that unit's requery identity is
  its render call id, which now appears only in a cut's continuation.

## Root runs (lanes run none)

1. Apply P2 and P3 with this commit.
2. `bin/test-check default --ns seon.repl-test`
3. `--ns seon.render.value-test`
4. `--ns seon.print-test`
5. `--test seon.instrument-test/<the audit render test>`
6. `--ns seon.render.transcript-test`
7. After P2: `--ns seon.turn-test`.
8. The two static checks.
9. `seon.config-test`, because the default config changed and a dial was deleted.

**RESET NEEDED.** A live store holding a `:seon.print/rendered-token-ceiling` config fact must accept the attribute's
deletion at adoption. The dial was optional and had no known stored value.

## Round 2 (after root loaded 132d94015 + P2 on pid 50733)

Broken first:
- **LARGE VALUE did not fire live, and P1 had never recorded either.** The receipt writer copies a hand list,
  `seon.turn/receipt-terminal-attributes`, and neither P1 (acquisition-ms, allocated-bytes) nor P2 (value/shown
  tokens, elisions, full) added its keys to it.
  - Probe on branch `efd-11`: after `(vec (range 3000))`, run `2882bd7dd074`, the receipt held only
    `{:seon.eval/duration-ms 9}`.
  - Fix, **P5** (turn.clj, held): add the six keys to the list.
  - The list duplicates the receipt schema. CLAUDE.md says to declare that partition on entity schemas and not in a
    hand list; deriving it is the follow-up.
- **The legend printed on every elided reply** (about 30 tokens) and is deleted. Each cut's continuation says how to
  read on. The floor reserves nothing for it, and the budget-refusal test's member case moves from 20 to 8 tokens to
  stay a member refusal.
- **Why only 32 members were shown under a 1,000-token budget:** the member bound cut first, not tokens. The cut's
  `:seon.print/bound-by` is `:seon.render.profile/max-children`, from dial `:seon.config.render.agent/max-children 32`.
  That dial is the intended floor rule as configured. With a 1,000-token budget it is the binding bound for flat
  collections of small scalars. Whether 32 stays is an owner call on the dial.
- **A failed render prints through the floor.** When the floor throws outside a declared pair, `prepare` now selects
  again structurally (no render pairs) under the same budget, and adds the failure as the one diagnostic. This is the
  same call and the same estimator, so whole-characters survives. Only a floor that also fails structurally prints
  plain text.
  - A declared renderer that throws was already caught per node (`declared`) with the estimate intact. The scratch
    probe gave 14,222 whole characters, 680 shown tokens and one diagnostic line.
  - Regression: `a-renderer-that-throws-still-prints-through-the-floor-and-says-large-value`, an end-to-end SCI
    evaluation. It checks:
    - the recorded value/shown tokens;
    - the bounded print with its continuation;
    - one diagnostic;
    - the LARGE VALUE line from `seon.repl/response`.

    It needs the `seon.test/run` harness, so root runs it.
- **Task c7bc0c506aa4 is patch P4** (cluster.clj and profile.clj, both held):
  - JVM-mode MCP replies no longer attach a JVM-wide profile window. Its counters include every lane's concurrent
    work; the event's own `ms` is the time.
  - `explain-slow`'s headline no longer says "this is your defect".

Patches:
- `tmp/eval-feedback-design-opus/p4-no-jvm-wide-blame.patch`
- `tmp/eval-feedback-design-opus/p5-receipt-records-cost-and-p6-cut-face-tests.patch`: P5 plus the expectations in
  loop_proof_test, problems_test, web_test and transcript_test that named the old legend. Some were red since
  ad0f8dab2.

Both pass `git apply --check`. Contracts compile over all patched sources with 0 findings. Scratch value_test: 99 pass;
2 fail, the same pre-existing failures as before; the errors are fixtures that need `seon.test/run`.

## Round 3 (pid 51535, bb9773a8d): the receipt records the cost, but MCP showed no LARGE VALUE

Cause: `seon.render.transcript/receipt-selector` pulls a hand-listed set of receipt attributes, and it has none of the
cost measures.
- The history entry's `::value-tokens`/`::shown-tokens` (P2) were therefore always nil.
- `seon.cluster.agent/run-observation` → `history-entries` → `seon.repl/response` is the one reply path for both MCP
  and the next context, so both saw no LARGE VALUE.

Proof on branch `efd-13`, run `965b42559c21`, `(vec (range 3000))`: the receipt holds
`{:value-tokens 1604 :shown-tokens 39 :elisions 1 :duration-ms 5}`. The live `seon.repl/findings` over those recorded
keys answers:
`;; LARGE VALUE: result/eb53baf43a2c6 is ~1604 tokens whole (shown 39). A value this big usually means a query or function returns more than it should.`

Fix:
- `seon.repl/cost-attributes` derives the stored attributes the bounds and their lines read from the bounds schema
  (committed).
- `seon.repl/entity-emission` carries them (committed).
- **P7**, `tmp/eval-feedback-design-opus/p7-transcript-pulls-cost.patch` (transcript.clj, held; +7 / −8): the
  selector adds `(repl/cost-attributes)`, and the history entry and emission carry `::cost` as one map, replacing the
  per-key copies.

Regression: `seon.repl-test/every-reply-path-reads-the-recorded-cost-its-findings-need`. Scratch repl_test: 19 tests,
96 assertions, 0 failures. Contracts compile over repl.clj, repl_test and P7's transcript with 0 findings.

## Round 4: red triage 2 (tests-0627h)

Live check first, branch `efd-16` on pid 52335: the renderer-throws value
(`{:seon.render/ai (fn [_] (throw …)) :rows …300 maps}`) answers end to end with:
- `;; LARGE VALUE: … ~4463 tokens whole (shown 553)`;
- the bounded print, with `…268 (drop 32 (get-in result/e… [:rows]))`;
- one diagnostic.

1. **`render.value-test/a-renderer-that-throws-…-large-value`: the test was wrong, not the code.** The one diagnostic
   carries the renderer's refusal as its `:seon.error/offending`, and that refusal has its own `:seon.error/at`, so
   counting `:seon.error/at` gave 2. The test now counts the render-layer entry.
2. **`render.value-test/dir-of-a-large-namespace-is-its-declared-renderer-text`: caused by ad0f8dab2** (the 50k
   rendered ceiling became the value budget).
   - A render's text past the budget was turned into a characters elision node. That printed it as a quoted string
     and lost `:seon.render.call/selected-producer`, so `:seon.eval/renderer` was nil.
   - Fixed in value.clj: the clipped text stays the render's own node, with its first B characters then `…+N`,
     printed as REPL text, and the producer is kept.
   - The test now expects the cut: prefix, `…+N` at the end, length ≤ B + 16, and the renderer named.
3. **`dev.mcp-bridge-test/runtime-status-…`: caused by ad0f8dab2.**
   - At a 1,000-token budget, `seon.cluster/runtime-observation-ai` came to 1,107 tokens live and the MCP reply fell
     to a blob handle. By family: open faults 479, cache status 232, over-second lines 134, replaced roots 82.
   - **P8**, `tmp/eval-feedback-design-opus/p8-status-face-fits.patch`: three entries per family and three
     over-second lines. The live measure is 876 tokens (2,805 characters).
   - `mcp_test` now expects 3, and a whole face under 3,200 characters.
   - The cache status stays whole, because its schema requires every per-kind counter. Its kinds grow with the
     cache, so the face's margin narrows as kinds are added.
   - P8 also passes `:seon.db/db` to `runtime-observation-ai` from `script/seon/dev/mcp.clj`. Since 93144cb71
     requires the database on every render unit, live `runtime_status` answered the refusal
     `runtime-observation-ai refused unit at [:seon.db/db]`.
4. **`cluster.host-test/a-run-reads-its-earlier-declarations`: mine through ad0f8dab2's budget, with a second
   owner.**
   - The install refusal's `:seon.test.accretion/orientation` ("… · install refused") comes late in the map.
   - At a 1,000-token budget the floor print of a refusal carrying a long-overflow auto-check error cuts before it.
     On branch `efd-16`, without the overflow, the whole map fits and shows the phrase.
   - The real fix is that the refusal's declared teaching face (`seon.test.accretion/render-ai`, which leads with
     the orientation) should be the one shown. The failed evaluation's value prints through `seon.sci.kernel/failure-value`'s
     returns instead, so the floor prints the raw map.
   - That selection belongs to the eval/error owner. It is not fixed here; the options are that fix, or a test that
     reads the orientation from the error value.
5. **Update to item 4 (host-test a-run-reads, plus one-commit's analysis): cause found at the producer.**
   `seon.render/declared-producers` finds a value's pair through one of:
   - its producer's `:seon.error/declared-schema` metadata;
   - the root call's contracted return alternatives;
   - its reaching attribute;
   - its entity owner.

   `seon.test.accretion/install-refusal` declared none of these. `refuse-install` prints it with no call function, and
   the refusal is not an entity, so the chain fell to the floor. The floor printed the raw map (374 tokens here; more
   with an auto-check error), which the 1,000 budget cuts before the orientation line.
   - **P9**, `tmp/eval-feedback-design-opus/p9-install-refusal-declares-identity.patch`: `install-refusal` puts
     `:seon.error/declared-schema :seon.test.accretion/install-refused-error` on its value, as `config/result-caps`
     does. Its pair `seon.test.accretion/render-ai` then renders 71 tokens, leading with "… · install refused" (live
     probe, pid 59300).
   - Regressions in `accretion_test`:
     - the refusal carries its declared schema;
     - `refuse-install`'s evaluation is shown by `seon.test.accretion/render-ai` and contains "install refused".
6. **`instrument-test/refusal-value-projection-…`.**
   - **Line 713 is mine.** The cut's continuation lengthened the line and the layout broke before `…37`. P9 compares
     with whitespace normalized.
   - **Line 716 (HTML lacks members 103 onward) is not from my changes.** The test fixes its own profile
     (max-children 3, 2,048 tokens), so neither the budget default nor the ceiling reaches it. Both faces are one
     selection by design (`prepare`: "ONE SELECTION FOR BOTH FACES"). Whether the HTML face should keep the whole
     value is the three-points law against that design, a question for the render owner.
