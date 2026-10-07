# Guide notes (2026-10-07)

These notes go with the draft rewrite of `.agents/skills/data-oriented-clojure/SKILL.md` and
`.agents/skills/clojure-testing/SKILL.md`, and the new "Read before you build" section in
`docs/seon/architecture/reference-code.md`. Nothing is committed. No JVM was started and no tests were run.

## (a) Conflicts with current CLAUDE.md, for the owner to rule on

1. **Docstring headings and costs.** CLAUDE.md says: "Docstrings are Markdown with a few fixed headings (Examples,
   Edge cases, Costs, Recommendations, Errors, Known issues) … Costs are prose stating armed or unarmed and when
   measured". The guide (9.1, 9.2) says: "Markdown headings … belong only on public `my.*` functions" and "Put no
   dates, measurements, incident history … in source … Cost belongs in the profile evidence." Proposed CLAUDE.md
   edit: headings only on public `my.*` functions; a one-line docstring elsewhere; drop "Costs … when measured" or move
   costs to profile evidence.
2. **Doc reference syntax.** CLAUDE.md says: "`[[sym]]` links resolve as data". The owner ruling of 2026-10-07 and the
   guide (9.3) say a reference is "a backticked span that reads as one fully qualified symbol or keyword". Proposed:
   replace the `[[sym]]` sentence with the backtick rule, and say tasks are referenced as `ns/task-name`.
3. **Return versus throw.** CLAUDE.md says: "A function that can fail declares each failure as a named alternative of
   its output union and RETURNS it as an error value … `throw` is reserved for internal control flow that its own
   boundary converts". The errors spec §2 and the guide (3.1, 3.4) say: "Return the map when answering is the
   function's job … Throw it everywhere else" and "Only functions that return errors list error alternatives". Proposed:
   rewrite "Nothing crashes" to the spec's rule, which is one map with two carriers and one boundary.
4. **Exact alternatives, stamps and a general predicate.** CLAUDE.md says: "Arities enumerate their exact error
   alternatives; no general error predicate, copied union or discriminator stamp". The errors spec has
   `:seon.error/type` as a required key and as the `:multi` dispatch, and makes `error?` equal
   `(contains? v :seon.error/type)`. That is a general predicate over a type key. The spec argues the type is the
   producer's declared identity rather than a stamp. CLAUDE.md should say so, or the predicate sentence should go.
5. **Refusal members.** CLAUDE.md says: "a refusal adds operation/member, expected shape, offending value and fix".
   The errors spec requires only type, operation and message. Expected shape and offending value come from Malli's
   `:seon.error/problems`, so the per-site `expected` and `offending` members go. Proposed: "a validation refusal
   carries Malli's problems".
6. **Measurements in test metadata.** CLAUDE.md says: "a longer bound carries the measured operation and reason beside
   the number". `:seon.test/long` strings today carry dated measurements, for example in `test/my/examples_test.clj`.
   That conflicts with guide rule 9.2 (no dates or measurements in source). The testing skill asks only that the reason
   name the measured operation. Owner: may test metadata keep dated numbers, or do they move to evidence?
7. **"Fix what you touch" versus ownership.** The owner ruling says to fix problems on code you touch if they take
   about 15 minutes. CLAUDE.md says: "Stay inside the assignment. Work within its stop boundary" and "One file, one
   owner". Guide 13.1 reconciles the two by having a lane claim the file with `bin/lane add` or hand the owner a patch.
   CLAUDE.md "How to work" should state the 15-minute rule in the same place.
8. **Comment levels.** CLAUDE.md says: "Source comments use `;` for prose, `;;` above forms, `;;;` for runtime
   structure." Guide 9.5 forbids banners (the `;;; ----` rule lines, 337 of them). `;;;` section titles remain allowed.
   This is a minor tightening and could be added to CLAUDE.md.
9. **Key spelling** (no conflict; confirm). CLAUDE.md says: "Use fully namespaced keys". Guide 2.1 recommends `::k`
   and `::alias/k`, which read to the same fully qualified keyword. Confirm that the law is about identity, not spelling.

## (b) Rules dropped, and why

- **Contracts only on public functions** (idioms-src R12; malli practice). The owner ruled that every function is
  contracted and armed.
- **Error kind key, `refuse` macro, `&form` operation** (idioms-src R9, R19, R27). Superseded by the errors spec:
  `:seon.error/type`, with an explicit operation argument to the constructor.
- **Generators as schema properties** (R14). Too narrow; folded into 4.2 ("name a repeated form once").
- **Style-guide 10-line maximum and 80/120-column limit.** Seon functions carry contracts and destructuring, so the
  guide uses 5-15 typical and 40 as the split threshold. Line length is lint's job.
- **`:pre`/`:post`** (style guide). The contract is the single check.
- **`:keys!` checked destructuring** (canon 3.1, "Clojure 1.13"). The pinned Clojure is 1.12.5 (`deps.edn`,
  `reference-code/clojure/pom.xml`), and `core.clj` has no `:keys!`.
- **Accessor namespaces** (Normand). CLAUDE.md forbids accessor layers.
- **`as->` only inside `->`** (Sierra). Low value per token.
- **Declared record fields on hot paths** (SCI ADR 0010). The source is an unreviewed, AI-generated ADR.
- **Area redesigns** in the four map reports: vector cells, read evidence, call preparation, admit, selection registry,
  per-connection deltas. These are owner design decisions, not style rules. The useful pattern from each is in the
  "Read before you build" table.
- **From the old data-oriented-clojure skill:** "Carry the world", "Declare the shape once" and the nil-ambiguity
  paragraph restate CLAUDE.md laws and the data-modeling skill. "Derive state and preserve observations" and "Render
  … target" describe system turns and refresh evaluations, which the agent-loop spec deletes. The old skill also
  cited `AGENTS.md` line numbers and a superseded PRD.
- **From the old clojure-testing skill:** the enforcement table's internals (`bounded-result`, `ambient-drift`,
  file-backed publication helpers), the "Will it boot?" section and the turn/context PRD pointers. The agent-loop spec
  supersedes the PRD pointers. CLAUDE.md and `bin/lane` cover the boot check. A short concurrency bullet was kept.

## (c) Estimated tokens (characters / 4)

| File | Tokens |
|---|---|
| `.agents/skills/data-oriented-clojure/SKILL.md` | ~6,800 |
| `.agents/skills/clojure-testing/SKILL.md` | ~3,400 |
| `docs/seon/architecture/reference-code.md` (whole; the new section is ~1,700) | ~3,400 |
| **Total read before an implementation** | **~13,600** |

This is below the 25-40k target on purpose. The guide points at library code rather than copying it. An agent that
follows one row of "Read before you build" reads 300-1,500 lines of library source, about 4-15k tokens, which brings a
typical pre-implementation read to roughly 20-30k tokens. Every rule has a one-line form, a cited example and a
rewrite. Adding more would mean restating CLAUDE.md.

## (d) Citations and claims I could not verify, and research errors found

- **Seam rows corrected in `reference-code.md`** (they had drifted): malli instrument `2193-2218,2626-2648` is now
  `2210-2245,2672-2712,3166-3187`; sci fork `345-350` is now `334-340`, and `utils.cljc:362-379` is now `:410`
  (`bind-root!`); Datahike validator `1206-1226` is now `1225-1234,1296`; `merge!` `734-748` is now `738`.
- **Seam rows still unverified** (the cited line no longer starts the named thing): `datahike/writing.cljc:860-889`,
  `db/transaction.cljc:998-1015`, `query.cljc:2568-2590`, `datom.cljc:325-359`, `gc.cljc:22-81,144-167`,
  `flow/impl.clj:271-320`, `flow.clj:265-286` (line 265 is `:compute-timeout-ms` docs, so it is plausible),
  `clj-kondo core.clj:242-270`, `http-kit server.clj:321-326`. The row "many-pull defaults to 1,000 without a marker"
  looks wrong: `pull_api.cljc:16` is `(def ^:private ^:const +default-limit+ nil)`. A lane should re-derive the A2 claim.
- **map-repl-path R1** says `seon.eval.drive` has "no caller in src/test/script/bin". That is wrong:
  `seon.cluster.agent` reaches `seon.eval.drive/run-receipts` through `requiring-resolve`, and 11 test files require
  the namespace. census.md's "unreachable" list covers only `run-episode!`, `run-sample!` and their helpers.
- **idioms-src** cites `src/seon/db.clj:4088` for `write-owned-values-error`; it is at `:4082` today. That is drift,
  and the guide names functions only.
- **Old clojure-testing skill** said `apply-config!` "replaces the whole overlay". Its docstring says "A later manifest
  changes only its own keys", and the new skill follows the docstring. The old skill's `test_support.clj` line numbers
  were stale; the new skill cites symbols only.
- **Findings for tasks** (outside this assignment; not filed):
  - `seon.test-support/refusal-data` recognizes an error by `:seon.error/at` and `:seon.error/layer`. Errors S2 drops
    both from the base, so every test using it will misread errors after S2.
  - `seon.test-support/transacted!` builds its exception message with `(pr-str report)` and `(pr-str entity)`. That
    prints values outside the value printer, against CLAUDE.md "Apply presentation limits once".
  - `sci/test/sci/error_test.cljc:21,24` uses `(pr-str frames)` in assertion messages. The guide does not cite it as
    an example.
- **Verified but approximate ranges:** `malli/src/malli/core.cljc:247-257` (`-comp`; its arities continue to `:260`)
  and `clojure/src/clj/clojure/core.clj:7054-7065` (`mapv`; the two-collection arities continue past it). The cited
  start lines are exact.
- `seon.render.transcript/receipt-entry` still reads about eight keys and restates them (re-read 2026-10-07).
