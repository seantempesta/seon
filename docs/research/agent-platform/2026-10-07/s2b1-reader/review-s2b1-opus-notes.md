---
title: S2b-1 reader design, dual review (Opus)
type: review
status: complete
date: 2026-10-07
lane: review-s2b1-opus
---

# Review of `s2b1-reader/design-notes.md` (Opus)

The design reviewed is `0df0480b2`. Root's rulings Q1 (`#` + non-space, non-`#` starts a form), Q2 (keep the same-line
rule, convert C7, an unfenced `ns=> (form)` stays prose), the turn.clj routing and the parinferish removal are taken
as given.

**Probes.** Nothing ran in default's JVM except installed functions and one task write. The prototype
(`scratchpad/s2b1-reader/src/s2b1/forgive.clj`) ran only in throwaway `java -cp` JVMs on the checkout classpath,
each bounded and exiting. The scripts are in `scratchpad/review-s2b1-opus/` (`count.clj`, `edge.clj`, `prose.clj`,
`imit.clj`).

`bin/seon status` reports default `:faulted`, a pre-existing state: `seon.schema/call-with-projection-state` has a
mean of 21 s over 298 calls (tasks `d27179bab996`, `c4ece4f4eec9`).

**Verdict: approve with changes.** The core is right: line rules inside `next-form :reply`, fence state as a value,
deletion of Parinfer, the trials, the code suffix and the thrown-error prose detection, and the reserved shape as an
error value at its producer. It also measures well. One change is needed before coding: the imitation *report* is
the speculative and wrong part, and dropping it removes the cross-lane `turn.clj` dependency (F1). The other
findings are a missed caller, a file-claim collision, a help-text gap and accuracy fixes.

## Findings, ranked

### F1 (high). Defer the imitation report to S4/S6: as designed it is wrong on three counts and needs a held file

The design adds:
- an `:imitation` repair kind and the `imitation` regex (a new production regex, which needs owner permission);
- a new variant `:seon.sci.reader/imitated-output-error`, which restates `:seon.error/offending :string` inline
  (lane rules, guide mistake 5);
- `:seon.batch/imitation` on `:segmented`;
- turn.clj hunk (a), which inserts a synthetic FIRST row through `read-row`'s `carried` branch.

The prototype contradicts the spec in three places:
1. **It flags imitations inside fences.** Spec §4.3 says "outside fences". With

   ```` ```text\nuser=> (+ 1 2)\n3\n```\n(+ 1 2) ```` the prototype answers `:imitation "user=> (+ 1 2)"`. `prose-edit`
   classifies every prose line, including lines of a non-code fence.
2. **It flags non-lines.** `"(+ 1 2) => 3\n(+ 3 4)"` answers `:imitation "=> 3"`, which is the rest of a form's line.
   `"=> 3"` alone also matches, and the spec names only `;; =>` and `ns=>` lines.
3. **Hunk (a) breaks the prose-only rule.** For `"=> 3"` (no forms, one imitation), hunk (a) adds a row before the
   `(seq sources)` check in `segmented-sources`. A prose-only reply then becomes a one-row run with a reader error,
   not the spec's "one exchange with the no-form refusal and no evaluation". The row also uses ordinal 0 and comes
   before forms that precede it in the text, and it gives `carried` (built for the no-forms refusal) a second
   meaning.

"Reported once" belongs where trailing prose is kept: on the exchange, which S4 builds and S6's cursor owns. s5a-astra
also warns that the carried source must be the source already read, never re-segmented.

**Simplification.** In S2b-1, imitation lines are prose (`; ` comments the agent sees) and nothing more. Delete
from the design:
- the `:imitation` kind (so `:repair-kind` becomes `[:enum :prose]`);
- the `imitation` regex;
- the new variant;
- `:seon.batch/imitation`;
- hunk (a).

S2b-1 then needs only hunk (b) from `turn.clj`, together with F2's conversion.

### F2 (medium). Missed caller: `seon.turn/preview-sources` reads authored source in `:reply`

`seon.turn/preview-sources` calls `planned-sources` (working tree `turn.clj:5019`, HEAD `:5028`). That caller is
missing from §1 and from the risk table. Its callers:
- production: `seon.render.web/render-source-call` (`web.clj:1398`), the page's authored source slots;
- tests: `seon.cluster.prompt-test` `record-evaluation!` (`:41`), `seon.render-simplification-test` (`:740`, `:763`),
  `seon.loop-proof-test` (`:742`) and `seon.turn-test` (`:2080`).

The source is render-produced code, not a model reply. Under the line rules the following become prose, and a slot
holding only those becomes the no-forms refusal:
- a lone symbol: `"(def x 1)\nx"` → only `(def x 1)` (probe `edge.clj`, "lone symbol after form");
- an indented form;
- a column-0 syntax quote: `` "`(a b)" `` → no segments.

Like hunk (b) (generated opening blocks), this caller should read with `code-sources`. Generated and authored code
is code by definition, so the unverified shape question in §4.3(b) goes away. Both are one-symbol changes in
`turn.clj`.

The test fixtures checked all feed column-0 lists, so they pass either way.

### F3 (medium). File-claim collision not flagged

§11 claims `test/seon/cluster/turn_test.clj` (to delete `delimiter-repair-is-span-local-and-precedes-intent`). That
file is held by `s5a-reply` (`bin/lane status`), together with `src/seon/turn.clj` and `src/seon/turn/batch.clj`
(the owner of `turn-request` and `code-request`, which §1 cites). Route the test deletion with the hunks: one patch
to `s5a-reply`, or one reassignment.

### F4 (medium). Help text is nobody's

S1's design (`s1-lookups/design-notes.md:215-218`) says the full rule is TARGET until S2b-1 lands, and that
"if S2b-1 lands before S1 … S2b-1's commit adds it". Spec §4.3 makes `(help)` the place that teaches fences, and
the change makes lone symbols, indented forms and line-start backticks outside fences into prose. The S2b-1 design
neither adds the help text nor names the coordination. One of the two designs has to own the help text, or agents
are told nothing about the new rules.

### F5 (medium). The proposed regression proves the carrier on a different show stage than the one that faulted

The fault was observed at `seon.turn/show-row`, through MCP SCI → `code-sources` → `read-row`. The new test
`a-reserved-result-form-answers-its-error-value` goes through `seon.test-support/evaluate` → `code-request` →
`seon.turn.batch` show (`turn/batch.clj:781`).

Both show stages read `:seon.batch/error-value`, so the one-line carrier fix in `batch.clj` fixes both. The proof,
however, never drives the entrant that faulted. Add one assertion through the MCP SCI entrance. The natural home is
`seon.cluster.evaluate-source-test`, which is also held by `s5a-reply`.

**Reproduced now on default** (installed code, SCI, agent `review-s2b1-opus`):
- `"(+ 1 2)\n#:seon.repl{:value 3}\n(* 2 3)"`:
  - form 2 answers `{:error "Only the REPL writes responses: this map was not evaluated."}`, with no `:result` and no
    `:ms`;
  - form 3 (value `6`) carries the `shown-failure` "expected a map, got nil … seon.turn/show-row (turn.clj:4750)"
    error.
- `"(+ 1 2)\n#:seon.repl{:value 4}"`: the same error rides the refused form itself.

So beyond the nil, an error recorded during form N's show is delivered on form N+1. That is a separate delivery
defect, which the design folds into the show-row task. Filed as **`56e06f9e410a`**
(`seon.batch/an-error-recorded-while-showing-a-form-rides-the-next-form`).

The design also cites task `7f5dfb6ad7a2` as existing. It is not in default's database now: a query over
`:seon.task/id` finds the other nine cited ids but not this one. Re-file it, or cite `e05f76582956` (show-stage
throws) or `81b1ff4eed9e` (`shown-failure` called with nil), which are the live tasks in this class.

### F6 (low). An undeclared spec deviation: `failed-end` skips a failed collection to its closer

Spec §4.3 says "reading continues from the reader's advanced cursor". The prototype's `failed-end` keeps
`balanced-end`, `collection-open` and `string-end` (a hand-written delimiter scanner, about 40 lines) and skips a
failed collection to its matching closer.

This is the better behavior. In
```` ```clojure\n(defn f [x] (let [y #1] (inc y)))\n(+ 1 2)\n``` ```` the reader's own cursor would continue

mid-form: `(inc y)` would run, and stray closers would error. With `failed-end` the reply yields exactly two sources
(probe "fence cascade").

It is still a deviation from the spec, and a second structural scan beside the one reader. Add it to §7 as a ruling
and amend the spec line, rather than leaving it implicit. Outside fences the line rule already makes the mid-form
rest prose, so this matters only inside code fences.

### F7 (low). Inaccurate "what exists" claims

- **Line numbers.** The `turn.clj` numbers are the `s5a-reply` working tree, not `2c36b20c2` as stated. At HEAD:
  `segmented-sources` `:3009`, `planned-sources` `:3036`, the `freeze!` call `:4451`, the opening blocks `:1777`,
  `read-row` `:4668`, `carried` `:4682`, `show-row` `:4733`. The `forgive.clj`, `batch.clj` and schema numbers are
  correct.
- **The "1,000 prose lines" row in §9 is not like-for-like.** The installed `segments` stops after about 65 lines of
  that input, and the rest (52,359 chars) becomes one `:seon.batch/unread` source (probe `prose.clj`). The installed
  "2.07 ms, 2.84 MB" therefore reads 6.5% of the input, and the new "3.39 MB" (3.58 MB in my run) reads all of it.
  - The installed stop is a current defect that this slice fixes: a prose line with an apostrophe ends reading.
  - State it, because a reader of the table sees a worse allocation number.
- **Stray-closer row.** The "1,000 stray `)` in a fence" row is worse in ms (18.10 → 27.92). It is pathological and
  linear, but under the budget rule ("a slice that worsens any number does not land") it needs an explicit
  sentence, not only a footnote.

### F8 (low). Q1 rule wording and consequence

"`#` followed by a character that is neither space nor `#`" also matches `#` followed by a newline or tab, and
`#1`, `#hashtag` and `#!`. Recommend "followed by a non-whitespace character other than `#`".

Q1 also turns Markdown-ish prose such as `#1 priority is (launch!)` into a read-error row the agent sees; the
prototype without Q1 makes it prose. That is acceptable under the ruling, but list it in §8's "what changes" so it is
expected.

### F9 (low). Wasted work and allocation in the new stepper

- **A reader per prose line.** `reply-step`'s `to` builds a fresh `version` (a new SCI source reader) for every prose
  line: 1,002 `version` calls for 1,000 prose lines, about 3.6 KB allocated per 56-char line. Prose lines need only
  a position. Carrying `pos` and building the reader once in `read-at` removes it.
- **A refusal built and thrown away.** `read-at` builds the reserved-shape error (`refusal/error`: a diagnostic plus a
  Malli explain) in the structural `:reply` pass too, whose `segments` drops everything but the source. Each reserved
  form pays it twice, and run4 `7b254915ca94` has 24 of them. Build it only where it is consumed (the live `:code`
  read in `read-row`), or have the structural pass not refuse at all.

Neither is required for the budget; both are smaller code.

### F10 (info). Optional smaller carrier

Instead of keeping `:seon.batch/refused` and reshaping it into an error map, the reserved shape could become the
existing `:seon.batch/read-error` step: "never evaluated, an error value" is what `read-error` already means. That
deletes `:seon.batch/refused` and the separate `refused` branch in `batch.clj` (`:411`, `:415`, `:427-428`), and
needs:
- `read-row` (held) to pass a `read-error` step through;
- `forms` to treat it as a source, as it now does for failed reads.

This is not required. The design's one-line change is also small. Offered as the deletion-first alternative.

## Armed calls (the design left them UNVERIFIED): now measured

**Calibration on default** (installed and armed, SCI, agent `review-s2b1-opus`): `seon.sci.forgive/segments` of live
01 (2,042 chars) made **933** armed calls in about 1 ms, and live 01 ×3 made **2,743**. Armed overhead is about 1 µs
per call here.

**Throwaway JVM.** Every `:malli/schema` Var in both namespaces was wrapped with a counter. That is a count of
contracted calls, not Malli validation. The installed count of 868 for live 01 is within 7% of default's 933 (the
difference is the contracted SCI and Seon calls beneath).

| Input | chars | installed | new (prototype) |
|---|---|---|---|
| live 01 | 2,042 | 868 | 679 (−22%) |
| rr ×1 | 9,557 | 2,119 | 1,458 (−31%) |
| rr ×3 | 28,671 | 6,307 | 4,344 (−31%) |
| run4 7b254915ca94 | 12,176 | 1,029 | 873 (−15%) |
| 1,000 prose lines | 56,000 | 1,058 (stopped at line ~65, F7) | 6,010 (6 per prose line, linear) |

The new stepper costs about 6 armed calls per prose line (`fence-kind`, `form-start?`, `line-bounds`, `prose-edit`,
`skip-blank`, `version`) and about 7 per form. The design's expectation of fewer armed calls than today holds on
every realistic input. At about 1 µs per armed call, a 30 KB reply costs about 5 ms of arming at most. F9's first
item removes one call per prose line.

## The questions in the review spec

1. **Is "what exists" accurate?** Mostly yes (`forgive.clj`, `batch.clj` and the schema lines are verified). It
   misses the `preview-sources` caller (F2), the turn.clj line numbers are from the working tree (F7), and one task id
   is missing (F5).
2. **Duplicates.** Verified:
   - the reserved-shape check exists twice (`forgive.clj:333-337` and `reader.cljc:570-579`). The reader copy also
     hand-builds its error without `:seon.error/type` (`reader.cljc:13-26`), which dies with it;
   - two reply rule sets would exist if the rules lived in `segments`;
   - prose is detected by a thrown read.

   One more second scanner remains: `balanced-end` and its helpers (F6). `skip-to` still skips `~~~` fences for
   `:code` and the JVM read (`turn/batch.clj:874`) while `:reply` treats `~~~` as prose. That is harmless, but it is
   two fence notions.
3. **Library seams.** Agreed: SCI `parse-next`, with no edamame and no Parinfer left in `forgive`. No CommonMark
   library is worth taking for the first-character rule plus fence state.
4. **Smallest composition.** It is smaller after F1, which deletes about five additions and a cross-lane hunk, plus
   F9 and optionally F10.
5. **One path.** Yes. It deletes a mode-specific repair path and adds none. Hunk (a) was the only piece adding a
   second meaning to an existing mechanism (`carried`), and F1 removes it.
6. **Risks and tests it misses:**
   - the preview path and its tests (F2);
   - help text (F4);
   - the entrant-level proof (F5);
   - render source blocks containing `;=>` lines would be flagged as imitations if they reach `:reply`, which F1 and
     hunk (b) both remove.
7. **Cross-design conflicts:**
   - `s5a-reply` holds `turn.clj`, `turn/batch.clj`, `cluster/turn_test.clj` and `evaluate_source_test.clj` (F3, F5);
   - S1 expects S2b-1 to own the help text (F4);
   - s5a-astra's "carried source must be the held source already read" argues against hunk (a) (F1).

## Questions for the owner or root

1. Do you accept deferring the imitation *report* to S4/S6 (F1)? In S2b-1, imitation lines would be prose comments
   only.
2. Should `preview-sources` and the generated opening blocks both read with `code-sources` (F2, hunk b)?
3. Should the spec take "a failed collection resumes after its closer" (F6)?
4. Who adds the fence rules to `(help)`: S1 or S2b-1 (F4)?

## Design check (this review)

The review held against the code: every `forgive.clj`, `batch.clj` and schema claim checked. What I could not verify:
- the prototype under real Malli arming (my counts are calls, not validation cost);
- render `:seon.render/source-blocks` shapes in production (F2 and hunk b make that moot).

No RESET NEEDED. Tasks: created `56e06f9e410a`; linked `e05f76582956`, `81b1ff4eed9e`, `d27179bab996` and
`c4ece4f4eec9`.
