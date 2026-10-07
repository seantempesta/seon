# Guide revision notes (2026-10-07)

Revision of the Clojure reference guide after the Astra and Sol 6.1 reviews
(`tmp/model-trial/review-guide-trial-astra-guide.md`, `tmp/model-trial/review-guide-trial-sol61-guide.md`) under the
owner decisions A-E of 2026-10-07. Nothing committed; no JVM lifecycle; no tests run. Files edited:
`.agents/skills/data-oriented-clojure/SKILL.md`, `.agents/skills/clojure-testing/SKILL.md`,
`docs/seon/architecture/reference-code.md`, `AGENTS.md` (= `CLAUDE.md`), this file. `docs/seon/specs/*` untouched
(another agent revised them during this work; the guide's slice names were re-aligned to the revised errors spec:
"the cut", "S-reads", "S-renders"; agent-loop tests are now its §7).

Evidence used: source reads at HEAD `33fb994f2` plus the working tree; reference-code checkouts (every gitlink matched
its checkout, `git ls-tree HEAD reference-code/` vs `rev-parse`); and read-only JVM probes through MCP `eval_clj`
(mode jvm) on default: `first/filter` vs `some` (`[false :later]` -> `false` vs `:later`), `cond->` vs
`select-keys` (`{:k :old}` vs `{:k false}`), `update-vals` keeps metadata (`{:m 1}`) and `into {}` drops it (`nil`),
`(not (empty? [1]))` -> `true` vs `(seq [1])` -> `(1)`; installed `seon.error.refusal/error` arglists
`([alternative members])`, `fail!` and `caught` absent; `seon.id/id` rows used in testing example 1. Runtime health at
start: default `:faulted` (8 open faults, 105 failed tests, 15 over-bound warnings, cache at 503/512 MB); not
investigated (outside this assignment).

## Changes per file

### `.agents/skills/data-oriented-clojure/SKILL.md` (~10.5k tokens, was ~6.8k)

- New header: Law vs Review prompt; every rewrite labeled EQUIVALENT (with preconditions) / BEHAVIOR CHANGE / TARGET
  (decision B). Complete pin roster (20 libraries, decision D) plus how to re-derive pins. Census statistics and the
  "malli 11 lines per defn" comparison removed (Astra L2, Sol L2).
- New §0 absent / nil / false / empty (decision D).
- §1.1 size is a review prompt; no promised line count; `write-owned-values-error` described as design work (callback
  `charge!`, memo, worklists), not a mechanical reduce (Astra M2, Sol M4).
- Worked decomposition (decision D): `seon.cluster/transact-initialization!` split into `initialization-round` (pure),
  `missing-lookups`, `refuse-round!` (validate) and the shell, EQUIVALENT with checked preconditions (`refused!` always
  throws; `entity-ready?` is `every?`, so exactly true/false; immutable reads). Contracts included.
- §1.2 pure example is clj-reload `topo-sort` (`parse.clj:163-175`) and `topo-sort-fn` (`core.clj:185-191`); `scan`
  explicitly marked impure (`core.clj:206-229` reads clock, `*config*`, `*loaded-libs*`, filesystem).
- §2.1 `debug-turn-request` rewrite now keeps all seven members (Sol H4 body, verified against
  `seon.render.web/debug-turn-request`).
- §2.2/2.5/6.1/6.3/7.2/10/11 converted to review prompts (positionals, loops for worklists/readers/indices, named
  intermediates, protocols, naming, macro evaluation).
- §2.3 `admit-declarations!` and `receipt-entry` re-described as boundaries with derivations (Astra M2, Sol M4).
- §2.4 select-keys rewrite: EQUIVALENT only when no value is nil/false; `var-entity`'s input is `:map`.
- §3 errors: installed vs TARGET; installed example fixed (member is `:my.fs/not-found`, not `:my.fs/path`:
  `resources/seon/schemas/my.fs.edn` `:not-found-error`); constructor validates (owner round-3 ruling, installed code,
  revised spec decision 2); unwraps load-bearing until S-reads; returning entrants keep exact variants; producers write
  domain messages, Malli writes validation prose; closed-schema spelling on the refusal path only; `Throwable->map`
  once at the owning boundary. Worked end-to-end error flow (variant schema, producer `fail!`, the tool's one top catch,
  boundary `caught`, assertion), TARGET.
- §4.1 `interrupted-warning` rewrite uses only registered keys (no undeclared `::interrupted-warning`). §4.2 worked
  registration of the 45-times-repeated total-predicate exemption (proposed name, publish/adopt, scripted conversion).
- §5.1 guard removal only when the exact contract entails it; `require-open-run` probe is the read unwrap (TARGET).
- §6.2 table with per-row labels: group-by precondition, first/filter vs some, update-vals metadata, seq vs boolean.
- §6.3 settlement-projection replaced by Sol H4's body, verified; added the precondition both reviewers missed:
  incoming `:seon.blob/staged-writes` absent or a vector (`(fnil conj [])` prepends on a list; the evaluation schema
  does not declare the key).
- §7.1 `seon.error/message` is an ordered fallback, not type dispatch. §8.1 atom consolidation is a design sketch;
  existing owner first. §8.2 db delays: five `seon.error` + three `seon.call-preparation`. §8.3 world supply vs helper.
- §9 docstrings/references per the owner rulings (backticked qualified names, `ns/task-name`, headings only on
  `my.*`). §9.5 banners.
- §12.1 `turn-function-result`: zero resource probes when `find-ns` succeeds, else up to three, plus two
  `str/replace` per call; GOOD Seon cache example `seon.render/call-reach`. §12.2 `caller-data` batch labeled an
  acquisition sketch; `previous-ending-namespace` example removed (it already has `(max ?evaluation)`).
- §13 adds "TARGET rewrites are not fixable yet". §14 quick table: every row carries its label/condition. §15 review
  questions.

### `.agents/skills/clojure-testing/SKILL.md` (~4.9k tokens, was ~3.4k)

- §1 rewritten per decision C: schema-shape tests go but one entrance regression stays (refusal, delivery, unchanged
  state); dependency integration is Seon's; deterministic work counts testable (agent-loop T4).
- §2.1 false library claims removed (Malli `core_test.cljc:2836` derefs a private; SCI `interop_test.cljc:63` calls the
  analyzer). §2.2 edamame regex fixed (no ellipsis). §2.3 smallest world (render reads get a db value and profile).
  §2.4 tables: `are` vs `doseq` with named cases; distinct boundaries stay separate; Datahike range `:11-26`.
  §2.5 `cluster-branch-is-one-injective-derivation` is a law (was a counter-example). §2.6 uses
  `an-absent-path-answers-the-not-found-error-type`; clojure string table no longer cited for error data.
  §2.7 GOOD Seon injection example (`:seon.ai/send!` in `seon.ai/complete`, `seon.ai-test`). §2.8 printer-test
  exception kept; diagnostic data converts. §2.11 examples runner described accurately.
- §3 "Delete or convert" per decision C, with a conversion table; assertion events counted through helpers; no task
  in place of the only proof.
- §4 three complete minimal tests (decision D): pure `seon.id/id` table (rows probed), refused retraction leaving the
  basis unchanged (from `seon.reset-edges-test/analysis-is-a-required-positive-fact`), agent evaluation returning a
  contract error (from `seon.sci.admit-test/an-absent-storage-bound-refuses-and-names-the-key-it-wanted`); imports,
  helper names, teardown and TARGET assertions for after the errors cut.
- §5 harness: global-state bullet no longer grants permission (Astra H7, Sol M5); bounds vs actual exit restored
  (`duration-failures`, SCI arm, `seon.test/bounded-result`).

### `docs/seon/architecture/reference-code.md` (~4.3k tokens, was ~3.4k)

- "Read before you build" intro: reuse guarantees, name what the library lacks, do not copy architecture; no "every
  line re-read" claim.
- New rows (decision D): data modeling/refs/components + writer validation; central content-keyed cache; branch and
  store lifecycle; source analysis + exact-span write-back. REPL row split into reading/evaluation and value printing
  (QuotaBoundWriter described as sink truncation, not Seon's printer). SSE row: dropping buffer bounds wake signals;
  bytes via `write-state` (`server.clj:345-350`, `ServerAtta.java:44-66`). Contracts row: report must throw.
  Reload row: topo-sort pure, scan impure. Provider row: langchain4clj not a wire/fold model. Ranges extended:
  nREPL `:72-180`, Kaocha filter `:10-99`, var `:30-45`, flow `map->step` `:291-309`, babashka shell row, datastar
  adapter `:23-76`.
- Seam table: merge row (fence and validation present, `versioning.cljc:738-756`, `writing.cljc:883-911`); pull
  default nil; query row re-pointed and the "specified correction" marked unverified; versioning ranges extended
  (`:494-501`, `:584-588`); Flow timed get `:257-260`; clj-kondo `impl/cache.clj:171-215`; http-kit drain seam.

### `AGENTS.md` / `CLAUDE.md`

Revised in place; 6,535 words / 43,168 bytes (was 6,561 / 43,195). Sentences changed, old -> new (unified diff of
the changed lines; `-` old, `+` new):

```diff
@@ step 4: dual review @@
-   - **Every lane refuses bad changes on sight** and proves its own slice before committing (see "Use focused
-     verification"); a review round never substitutes for that proof.
+   - **Every lane refuses bad changes on sight** and proves its own slice before committing (see "Use focused
+     verification"). Every review is dual (a fresh Opus agent and Astra, independently) and never
+     substitutes for that proof.
@@ step 5: fix what you touch (appended to the owning step) @@
+   Fix problems on code you touch when that takes about fifteen minutes, within file ownership (`bin/lane add` or a
+   patch); otherwise reference a new task in that var's docstring.
@@ Documentation is data @@
- Docstrings are Markdown with a few fixed headings (Examples, Edge cases, Costs, Recommendations, Errors, Known
- issues); cross-cutting knowledge lives in Markdown pages. `[[sym]]` links resolve as data, and a subject's context
- shows every doc item linking it. Costs are prose stating armed or unarmed and when measured; every known issue links
- its task. Never author what the call graph already derives.
+ A docstring says briefly what the function returns; Markdown headings (Examples, Edge cases, Errors, Known issues)
+ only on public `my.*` docstrings; cross-cutting knowledge in Markdown pages. A backticked span reading as one fully
+ qualified symbol or keyword is a reference (a task's is `ns/task-name`): the index resolves it into the subject's
+ context; one resolving to nothing is a problem on that var. No dates, measurements, history or `file:line` in
+ source: costs are profile evidence. Known issues reference their task. Never author what the call graph already
+ derives.
@@ Use grounded vocabulary @@
- Keep JVM REPL and SCI evaluation, turn and evaluation, live result and shown text distinct;
+ Keep JVM REPL and SCI evaluation, live result and shown text distinct;
@@ Nothing crashes @@
- A function that can fail declares each failure as a named alternative of its output union and RETURNS it as an error
- value that says what went wrong and how to fix it. `throw` is reserved for internal control flow that its own
- boundary converts to that value in the same function or its declared caller. Every entrant boundary (…) converts any
- exception that still reaches it into the one error value, records it as a fault, and delivers it as data beside
- whatever value exists. A throw that escapes to an entrant is a defect of its producer: fix it there and add its union
- alternative.
+ A function whose job is to answer (`my.*` tools, validators, merge checks, gates) RETURNS its error map and lists
+ exactly those variants as one `:multi` on `:seon.error/type`; every other function throws the same map inside
+ `ex-info` and declares only its success value. Every entrant boundary (…) takes a thrown map back once with
+ `ex-data`, turns any other exception into an error map, records the fault and delivers it as data beside whatever
+ value exists. No layer catches only to convert, wrap or rethrow; an unclassifiable exception is its producer's
+ defect.
@@ Errors are ordinary values of one shape @@
- An error is one flat map under one named extension of `:seon.error/base`: the producer's fields, message, source
- location and the whole cause chain (class, message, `ex-data` per link); a refusal adds operation/member, expected
- shape, offending value and fix. Arities enumerate their exact error alternatives; no general error predicate, copied
- union or discriminator stamp (a contract alone does not prove a body's error union — that needs its producer,
- checker and regression). The producer declares identity; nothing downstream guesses it.
+ An error is one flat map: `:seon.error/type` (its variant's schema key, extending `:seon.error/base`),
+ `:seon.error/operation`, `:seon.error/message`, optional fix, Malli problems and the variant's members; the boundary
+ adds the cause chain. One constructor builds it where the failure is detected and validates the variant, for both
+ carriers; nothing stamps metadata or guesses the type downstream. Target (the errors cut): the base drops
+ `:seon.error/at` and `:seon.error/layer`, and the constructor becomes `(error type operation message data)` with
+ `fail!`; until then use the installed constructor.
  (references sentence unchanged)
- Catch only declared cases or resurface the whole cause; missing evidence is a typed unknown, never nil, a default or
- message-only success. There is one way to build, store and show an error; delete every other wrapper, stringifier,
- cursor or copy.
+ Missing evidence is a typed unknown, never nil, a default or message-only success.
@@ Recover facts, not execution @@
- Close interrupted runs and mark unfinished evaluations; never resume interrupted execution or serialize private
- objects or live results. A run is a plain loop: read and evaluate every form, then write its settlement (every form
- parsed and evaluated) as ONE synchronous transaction, durable before the run answers, under 100 ms; no asynchronous
- or fire-and-forget write, so the next call always sees it. A crash mid-run loses only that unanswered run; recovery
- closes it and the agent redoes the work — do not represent the in-between state.
+ Never resume interrupted execution or serialize private objects or live results. A pass reads and evaluates every
+ form, then writes its settlement as ONE synchronous transaction, durable before it answers, under 100 ms; no
+ fire-and-forget write, so the next call sees it. A crash before settlement loses only that pass, leaves the database
+ unchanged and the work still due.
  (consequences sentence unchanged)
@@ Derive wakes and completion -> Derive work and completion @@
- Use listened datoms and a qualifying ordinary reply's basis; openings and system-only turns answer nothing. Handling
- is separate, a message subject is literal, `:seon.message/from` marks inside wakes. Before a turn, refresh each
- distinct changed read from the latest evidence and append it; never replay writes or effects or rewrite history.
- Compaction clears evaluations and regenerates the opening.
+ Work is a listened wake newer than the agent's answered basis; a message subject is literal, `:seon.message/from`
+ marks inside wakes. Context renders live through `seon.cache`, never stored or refreshed by system turns; never
+ replay writes or effects. An agent completes by marking its task complete; a task is complete only when its tests
+ prove it; a listener on task facts decides who is notified.
  (archive sentence unchanged)
@@ Make bounds honest @@
- a longer bound carries the measured operation and reason beside the number.
+ a longer bound carries a reason naming the measured operation, its numbers in profile evidence.
@@ Stay inside the assignment @@
- A lane proves its slice with `bin/lane commit … --test NS` and names what it could not prove; root owns the broad
- tiers and cold proof.
+ (deleted: duplicated "Prove each slice before it lands" and "Report honestly"; line reflowed)
```

Resolves guide-notes conflicts 1-7 (docstrings/costs, `[[sym]]`, return vs throw, the general-predicate and stamp
sentence, refusal members, measurements in test bounds, fix-what-you-touch vs ownership). Conflict 8 (banners) lives in
the skill only; 9 (key spelling) needed no change. Also aligned with the agent-loop rulings (no open runs, live context,
crash loses the pass, completion through tasks). Owner review points: the "Recover facts" and "Derive work" rewrites
state the target loop while the old loop still runs; the deleted "Stay inside the assignment" sentence.

## Findings rejected or refined (with evidence)

No reviewer finding was disproved outright; every one cited below was checked in source.

- **Refined, both reviews (settlement-projection):** both corrected bodies are equivalent to the original only when an
  incoming `:seon.blob/staged-writes` is absent or a vector; the original's `(fnil conj [])` prepends to a list.
  `:seon.turn.loop/evaluation` (resources/seon/schemas/seon.turn.loop.edn) is an open map that does not declare the key.
  Precondition added; Sol's body used.
- **Refined, errors constructor validation:** the reviewed errors spec text at review time said "No validation in the
  constructor"; the owner round-3 ruling, the installed `seon.error.refusal/error` (validates, `refusal.clj:743-`) and
  the revised spec's decision 2 say it validates. The guide follows the ruling.
- **Refined, guide's installed example:** neither review caught that the installed `:my.fs/not-found-error` member is
  `:my.fs/not-found` (`my.fs.edn` `:not-found :my.fs/path`), not `:my.fs/path`. Fixed.
- **Partly kept, `debug-diagnostic` → constructor (Astra M2 caveat):** kept as the direction; the call still needs its
  named variant, stated.
- **Kept with qualification, Throwable->map rewrite (Astra M5, Sol ledger):** the result is equivalent (`:via` is the
  same cause chain as the `ex-cause` loop in `seon.test.runner/throwable-signature`); the cost objection is correct, so
  the rewrite is limited to boundaries that already hold the map.
- **Not adopted, Sol M2 citation `src/seon/db.clj:5913-5915` (parents through `db/transact!`)**: not verified in this
  pass, so not cited; the merge row names `seon.cluster.source` and the Datahike seam only.
- **Not adopted, Astra M10.5 broader streaming rule:** folded into one sentence of §12.3 rather than a rule; no Seon
  example was verified.

## Token estimates (characters / 4)

| File | Before | After |
|---|---|---|
| `.agents/skills/data-oriented-clojure/SKILL.md` | ~6,800 | ~10,500 |
| `.agents/skills/clojure-testing/SKILL.md` | ~3,400 | ~4,900 |
| `docs/seon/architecture/reference-code.md` | ~3,400 | ~4,300 |
| **Guide total** | **~13,600** | **~19,700** |
| `AGENTS.md` (not counted above) | ~10,800 | ~10,800 |

The guide grew ~6k tokens, short of the ~8-12k (22-26k total) asked. Growth was limited to examples verified in
source or by probe; padding to the target was not attempted. Further worth-adding material, if wanted: one re-keying
boundary worked end to end (`receipt-entry`), and one complete Seon property test with `support/assert-check!`.

## Not verified

- No example test was executed; each was checked against `test/seon/test_support.clj` and the test it is derived from.
  Whether those source tests are green today is unknown (default reports 105 failed tests).
- The worked decomposition and the registration example were not compiled or published.
- Rows naming Seon owners (`seon.edit/exact`, `seon.cache/lookup`, `seon.cluster.registry/branch!`, …) were verified by
  definition, not by behavior.
