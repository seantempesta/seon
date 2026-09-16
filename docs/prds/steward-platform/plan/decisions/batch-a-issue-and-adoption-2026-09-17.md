---
type: decision
status: awaiting owner ruling
created: 2026-09-17
tags: [decision, steward, adoption, issue, detector, opening]
---

# Batch A — agent-facing adoption, the worker's opening, generated issues, D2 scope

Four of the decisions listed in
[overnight-report-2026-09-17 §Owner decisions](../overnight-report-2026-09-17.md)
(items 1, 2, 4 and 5). Every code claim below was verified by opening the file
at the cited line on `steward-platform` at the time of writing. One live read
was taken against `default` (`jvm` mode, explicit custody through
`(seon.operator/connection "default")`, 89 ms, no writes); it is marked where
it is used. Everything else is files, notes and `git show`.

---

## 1. Agent-facing adoption — how an agent's own edit becomes live program facts

### (a) Background

An agent that fixes source today writes the file and then cannot make the
change real. `my.edit/form!`, `my.edit/exact!` and `my.edit/lines!`
(`src/my/edit.clj:35`, `:59`, `:82`) are three declared capability requests
that all name the same owner, `seon.edit.jvm/edit`; each returns path,
`:my.edit/changed?`, before/after digests and a source window, and then stops.
Nothing loads the file. The agent's own follow-up, `my.test/check`
(`src/my/test.clj:5`) → `seon.test/check` (`src/seon/test.clj:736`), reloads
only the namespaces of the TEST symbols it selected: `prepare-tests!`
(`src/seon/test.clj:516`) filters `selected` — which `check-in-process`
computes as test symbols reached by the change (`src/seon/test.clj:535`,
`:556-567`) — down to source-bearing namespaces and calls
`(require namespace-name :reload)` on those (`src/seon/test.clj:524`). The
edited `src` namespace keeps its previously loaded Vars, and worse, the
reach selection that chose the tests was derived from the PUBLISHED program
graph, which still describes the old source: an edit that adds a call edge
selects the wrong tests and reports green.

The only adoption that exists is the operator's, and it is already an
in-process call wearing a shell costume. `bin/seon init --dev NAME --changed
PATH` runs Babashka (`bin/seon:24-26`), takes that operator root's lifecycle
lock (`script/seon/fresh_operator.clj:3232-3235`, acquisition and hold bound
`900000` ms, `resources/seon/operator/state.clj:338-340`), connects to the
running JVM's prepl, and evaluates
`(seon.cluster/refresh-source! <cluster-root> <changed-paths> <cluster>)` in
that same JVM (`script/seon/fresh_operator.clj:2404-2406`, sent at
`:2566-2578`). `refresh-source!` is public with a declared contract
(`src/seon/cluster.clj:2344`), serializes on one JVM monitor
(`src/seon/cluster.clj:2365`) and calls `development-source-refresh!`
(`src/seon/cluster.clj:2197`), which reconciles schema declarations, upserts
program identities, re-arms wrappers and records the adoption commit. So the
agent's taught workaround is: agent evaluation inside the JVM → shell child →
Babashka JVM → cross-process lifecycle lock → prepl socket → back into the
same JVM it started in. The hook uses the identical path
(`.claude/seon-hook.edn:29-34`, `:timeout-seconds 180`).

### (b) Real example

From the issue-context trials on the isolated `trials` cluster, the one worker
that wrote a correct fix (candidate F) ran the taught call verbatim and was
killed — `docs/seon/issues/an-agent-cannot-make-its-own-source-edit-live.md`:

```clojure
(my.shell/run! {:my.shell/argv ["bin/seon" "--root" "/Users/sean/src/seon/tmp/issue-trials-root"
                                "init" "--dev" "trials" "--changed"
                                "/Users/sean/src/seon/tmp/issue-trials-wt/src/seon/sci/eval.clj"]
                :my.shell/cwd "/Users/sean/src/seon/tmp/issue-trials-wt"})
```

returning

```
The foreign process was terminated when its evaluation reached its time limit.
```

The bounds that killed it, and the times it had to fit inside:

| bound or measurement | value | source |
|---|---|---|
| `:seon.config.shell/time-limit-ms` | `30000` | `config/default.edn:197` |
| `:seon.config.eval/time-limit-ms` (also reaps the child; `await-exit` takes whichever ends first) | `30000` | `config/default.edn:127`, `src/seon/shell/jvm.clj:272-296` |
| adoption of one changed file, trials cluster | 60–90 s | `docs/seon/issues/an-agent-cannot-make-its-own-source-edit-live.md` |
| one measured development-adoption convergence | **221,788 ms** | `docs/prds/steward-platform/research/complex-issues-as-schema-spec-2026-09-16.md:237` |
| one lane's `init --dev` waiting for the root lifecycle lock behind two other publications | **244,051 ms**, exit 143 | `docs/prds/steward-platform/research/arming-includes-referenced-schemas-2026-09-16.md:258-260` |
| one manual adoption request that never acquired the lock | exit 1 after **900,066 ms** (the 900,000 ms acquisition bound) | `docs/prds/steward-platform/research/reach-digest-2026-09-16.md:507` |
| hook publication refused at its own bound | exit 124 at 180 s | `docs/prds/steward-platform/plan/unsettled.md:650` |

The trials note records the consequence: candidate F "ran the adoption call
exactly as taught and was killed by the shell bound (defect 2). No session
reached `resolved-tx`; the ceiling was the platform, not the model."
(`docs/prds/steward-platform/research/issue-context-trials-2026-09-16.md:66-68`).

### (c) Options

**(a) A successful `my.edit` write requests in-process adoption of that path,
reported as the effect's result.** *Guarantee:* the edit and its consequence
are one declared request with one typed result naming the adopted commit and
the changed identities; no shell, no second JVM, no cross-process lock.
*Cost:* one new capability owner calling `seon.cluster/refresh-source!` with
the cluster the agent belongs to, plus the decision of which deadline it
carries — a 221 s adoption does not fit any foreground bound, so it must ride
`my.background`'s detached arm (`src/my/background.clj:21-46`;
`:seon.config.effect.background/time-limit-ms` `600000` at
`config/default.edn:36`, overridable per form with no clamp,
`src/seon/effect.clj:642-663`), returning `[:seon.effect/id …]` for
`poll`/`await`. *What we give up:* adoption stops being an operator-only
transition; a mistaken agent edit can now re-publish the whole tree, and two
agents editing at once contend on `source-refresh-monitor` exactly as two
lanes do today.

**(b) `my.test/check` adopts changed `src` namespaces the way it reloads test
namespaces.** *Guarantee:* the loop closes with no new agent-facing call — the
agent's existing "prove it" step becomes the adoption point. *Cost:* the
check's 120 s bound (`:seon.test/check-time-limit-ms` `120000`,
`config/default.edn:359`) is below the measured 221 s convergence, so the bound
must move or the check must go detached anyway; and it inverts an existing
dependency — `seon.test/check-adoption` (`src/seon/test.clj:799`) already
derives WHICH tests to run from the last converged adoption's recorded
identities and inputs. *What we give up:* a read-shaped call
(`check`) acquires a publishing side effect, and an agent that only wants to
know where it stands pays a tree analysis.

**(c) Keep the shell path and raise the shell bound to the measured adoption.**
*Guarantee:* one dial change. *Cost:* it does not work on its own —
`await-exit` (`src/seon/shell/jvm.clj:272-296`) reaps the child at whichever
of the shell bound and the EVALUATION deadline ends first, so
`:seon.config.eval/time-limit-ms` must move too; and neither covers the
244 s / 900 s lifecycle-lock queue the measurements show. *What we give up:*
we keep teaching agents to shell out to the operator that launched their own
JVM, and we raise two process-wide deadlines for one caller's benefit.

### (d) Recommendation — (a)

§2.2 says a question the database cannot answer is a defect report about the
data model. Adoption already answers this one: `development-source-refresh!`
records the adoption's changed identities and inputs as facts, and
`check-adoption` (`src/seon/test.clj:799-828`) already reads them back to
select the gate. The missing thing is not a mechanism, it is the agent-facing
REQUEST for the mechanism that exists. §2.5 forbids a second path; option (a)
adds none — the owner it calls is `seon.cluster/refresh-source!`
(`src/seon/cluster.clj:2344`), the same public function the operator reaches
by prepl (`script/seon/fresh_operator.clj:2404-2406`).

§2.3 requires both halves: the bound belongs at the seam that admits the work.
`my.background` is that seam and it already exists — a detached arm at
`:seon.config.effect.background/time-limit-ms`, overridable per submission with
no clamp, returning `[:seon.effect/id …]` to poll or await
(`src/my/background.clj:21-46`, `src/seon/effect.clj:631-663`). This is the
"ask what the dependency already does" answer: nothing new needs to be built
for the 221 s case, and the shell path's real defect is not its 30 s dial — it
is that the agent had no representable way to say "this outlives my turn"
about an operation that plainly does.

Option (b) reverses a dependency that is already correctly oriented; option (c)
hardens a mechanism against its own normal operation, which §"Prefer
dissolution to addition" names as the signal to ask whether the mechanism
belongs on that path at all. It does not: the shell hop between one JVM and
itself is the thing to delete.

---

## 2. Which opening the issue worker's generated first turn uses

### (a) Background

`seon.issue/render-ai` is the issue schema's declared AI projection; it
dispatches on a per-agent config enum, `:seon.config.render/issue-opening`,
whose seven members are one per trial candidate
(`resources/seon/schemas/seon.config.render.edn:3-11`). The candidate bodies
live in `src/seon/issue/opening.clj` as `defmethod`s of a private multimethod
(`:122-124`), and the dial's absent value is `:bare` (`opening.clj:29-31`, the
`:default` method at `:200-202`). The live setting on `default` today is
`:seon.config.render/issue-opening :bare` (`config/default.edn:355`).

Seven sessions were run on an isolated scratch cluster `trials` (operator root
`tmp/issue-trials-root`, checkout `tmp/issue-trials-wt` at `db65dc542`), model
`deepseek-flash`, 20 provider turns each, 131 provider calls total, one real
issue rendered seven ways. Lines 1–18 of every turn-0 prompt are identical —
`(help)`, the identity pull, the plan block — so only the issue block varied
(`docs/prds/steward-platform/research/issue-context-trials-2026-09-16.md:118-121`).
The second round was never run, under the plan's own stop rule, because the
trials found four platform defects, two of which make the loop impossible to
close (decision 1 is one of them).

### (b) Real example — the measured table, and the two candidates recommended

From `issue-context-trials-2026-09-16.md:79-90` and `:94-104`, verbatim:

| Candidate | Block bytes / lines | Turns | Evals | Eval errors | Shell calls | First edit call | Edit calls | Fix written | Prompt tokens | Completion tokens |
|---|---|---|---|---|---|---|---|---|---|---|
| A bare | 84 / 2 | 22 | 37 | 9 | 9 | 34 | 3 | no (wrong fn name) | 170,052 | 3,660 |
| B plan-first | 829 / 16 | 21 | 40 | 13 | 7 | 23 | 3 | no (wrong fn name) | 160,866 | 5,069 |
| C evidence-first | 2229 / 49 | 22 | 42 | 6 | 8 | — | 0 | no | 191,290 | 3,282 |
| D walkthrough | 1795 / 45 | 23 | 44 | 12 | 7 | — | 0 | no | 173,286 | 3,594 |
| E questions | 863 / 24 | 27 | 45 | 9 | 2 | — | 0 | no | 169,626 | 3,262 |
| F namespace-picture | 160 / 4 | 21 | 32 | **5** | 12 | 25 | 3 | **YES** | 179,234 | 3,988 |
| G minimal-retrieval | 231 / 4 | 21 | 32 | 4 | — | — | 0 | no | 136,752 | 1,877 |

Totals: 1,181,106 prompt tokens of which 1,066,368 were cache hits (90.3%),
24,732 completion tokens. Only each session's FIRST call missed the cache.

F's whole block, quoted from `issue-context-trials-2026-09-16.md:229-235`:

```
;; I am the steward of these namespaces. The picture first.
(dir seon.sci.eval)
;; One of its issues is mine.
(my.issue/status {:seon.issue/id "6effaf777327"})
```

B's two completing calls, the part recommended for merging, from `:154-166`:

```
;; The one call that proves it finished:
;;   (my.test/check
;;     {:seon.test/changed
;;      ["seon.sci.eval/directory-value"
;;       "seon.sci.eval/documentation-value"
;;       "seon.sci.eval/function-doc-map"]})
;; Change source with the digest you just read:
;;   (my.edit/form! {:my.edit/path "<path>"
;;                   :my.edit/expected-digest "<the file's current digest>"
;;                   :my.edit/form {:my.edit.form/head 'defn
;;                                  :my.edit.form/name '<name>}
;;                   :my.edit/operation :replace
;;                   :my.edit/source "<the whole new form>"})
```

F is the only candidate whose agent wrote a real fix — a two-site change to
`read-arglists`, applied with two successful `my.edit/exact!` calls
(`issue-context-trials-2026-09-16.md:44-58`) — and it died on decision 1's
shell bound one step from green. C and D, the two largest blocks (2,229 and
1,795 bytes), produced ZERO edit calls. G's retrieval offer,
`seon.issue.opening/context`, was never called in any of its twenty turns
(`:244-246`).

**"Keep A as the floor" means, concretely:** leave `default-candidate`
`:bare` at `src/seon/issue/opening.clj:31` and the `:default` method at
`:200-202` unchanged, so an issue with no worker, or a worker with no dial,
still renders A's two lines. The note's own reason
(`issue-context-trials-2026-09-16.md:277-282`): A "is within noise of the
elaborate candidates on every measure", and the honest summary is that "one
round of seven cannot separate these openings, because the platform ceiling is
lower than the differences between them."

### (c) Options

**(a) Merge F + B's completing calls into one candidate; keep `:bare` the
floor; delete C, D, E, G.** *Guarantee:* the shipped opening is the smallest
block that produced work, plus the only exact executable completing call any
candidate put in front of the model; the enum shrinks from seven members to
three (`:bare`, the merged one, and whatever is under trial next).
*Cost:* deleting four `defmethod`s (`opening.clj:140-182` and `:192-198`) and four
enum members (`seon.config.render.edn:10-11`); the merged shape has itself
never been run. *What we give up:* the ability to re-run C/D/E/G without
`git show` — which is the archive by ruling.

**(b) Ship F unchanged and keep the dial at seven members until the platform
defects are fixed and a second round runs.** *Guarantee:* the only candidate
with a measured success ships as measured; no untested merge.
*Cost:* the model's most expensive recurring error class stays unaddressed —
three of seven sessions called `my.edit/edit`, which does not exist, before
finding `my.edit/exact!`, and 15 of 58 evaluation errors are unresolved
symbols (`issue-context-trials-2026-09-16.md:38-43`, `:106-116`). *What we give
up:* four dead candidate renderings stay in the enum, and a dial member that
nothing selects is a mirror of a decision already made.

**(c) Keep `:bare` for everything and delete the candidate machinery.**
*Guarantee:* the simplest possible surface; the finding that A is within noise
is taken at face value. *Cost:* F's five evaluation errors against A's nine,
and F's fix against A's `my.edit/edit` dead end, are discarded on a single
round. *What we give up:* the trial apparatus itself, which the second round
needs once decision 1 lands.

### (d) Recommendation — (a), with the merge stated as one candidate

§2.5: one mechanism, accreted in place. Four candidate renderings that the
measurement dropped are four second paths to the same output; keeping them in
the enum is a hand-maintained mirror of a decision already taken, which
§2.2's derive-or-die rule names as a defect on sight. The dial itself stays —
it is how the next round runs — but its members should be the ones a decision
stands behind.

The merge is what the evidence supports rather than what any one session ran:
F's contribution is that it names the namespace, renders `(dir …)` and gets out
of the way (160 bytes, 5 evaluation errors, the only fix written); B's is that
it was the only block to put a correct executable `my.test/check` in front of
the model, and it reached an edit attempt earliest (eval 23 of the three that
tried). Both halves are LINKS and reads, which is the family's own ruling
(`src/seon/issue/opening.clj:11-16`: a candidate "LINKS rather than copies …
it emits only reads"), so the merge breaks nothing in the turn loop.

Keeping `:bare` as the floor is §2.4: an issue with no worker or no dial must
still render something honest, and two lines that name the issue is the
smallest honest thing. Note for the same beat: the trials' own cheapest win is
not an opening at all — `my.edit/edit` as an alias, or `dir my.edit` in the
agent's face, addresses 15 of 58 measured evaluation errors and is independent
of which candidate ships.

---

## 4. Generated issues and tests — the detector decides done

### (a) Background

`seon.issue/generate` mints one issue entity per (detector, subject) pair and
upserts on re-run; `:seon.issue/detector` is declared as "The detector
function's program entity for a generated issue. With the subject's own
identity value it is the issue's identity, so a detector run upserts its issues
instead of duplicating them. Absent on an authored issue."
(`resources/seon/schemas/seon.issue.edn:35`). A subject the detector stops
yielding gets `:seon.issue/status :resolved` and `:seon.issue/resolved-tx`,
declared as "The transaction recording that every test verified; absence means
open. Written by settlement, never asserted by a model."
(`seon.issue.edn:37`). `:seon.issue/tests` is declared "Success tests remain
nonempty after first assignment." (`seon.issue.edn:10-13`).

The generator deliberately mints no `:seon.test` entity: a program fact for a
deftest nobody has written would be fabricated on the very identity the runner
selects by (`:seon.test/sym`). So a generated issue has no tests, and
`start-tx` refuses exactly that: `(when-not (seq (:seon.issue/tests row))
(refuse! :seon.issue/no-tests "Starting an issue requires at least one test."))`
(`src/seon/issue.clj:781`), reached through `start!`
(`src/seon/issue.clj:821-832`). Half the decision has already landed on the
read side: `check-form` (`src/seon/issue.clj:564-573`) chooses the tests when
the issue has any, otherwise `(<detector> (seon.db/db))`, and NOTHING when
neither — its docstring names the reason, that
`(my.test/check {:seon.test/changed []})` "promised a verification that would
pass by being empty", which is the absence-as-health class. The writer,
`start-tx`, has not been given the same choice.

### (b) Real example

The first generator run on `default` (2026-09-16) wrote 63 issue entities:
`entity-map-without-pair` 32 in 1,941 ms, `public-without-doc` 31 in 409 ms;
the immediate second run produced **0 transaction forms for both**, basis `:t`
536871343 → 536871343, i.e. no transaction at all
(`docs/prds/steward-platform/research/issue-generator-2026-09-16.md:106-114`).
One generated issue's entity, pulled live on `default` after the run
(`issue-generator-2026-09-16.md:134-142`), verbatim:

```clojure
{:seon.fn/sym "my.agents.root/largest"
 :seon.issue/_functions [{:seon.issue/id "7cf1077d99bb"
                          :seon.issue/status :open
                          :seon.issue/title "Public function my.agents.root/largest carries no docstring"
                          :seon.issue/detector {:seon.fn/sym "seon.issue.detect/public-without-doc"}}]}
```

and the opening that issue renders today, also real
(`issue-generator-2026-09-16.md:238-242`):

```
;; My issue. Its detector decides done: it resolves on the run after
;; (seon.issue.detect/public-without-doc (seon.db/db)) stops naming this subject.
(my.issue/status {:seon.issue/id "7cf1077d99bb"})
```

**The real count of generated issues on `default` today is ZERO.** My single
live read (`jvm` mode, `(seon.operator/connection "default")`, 89 ms) asked
for every issue entity carrying `:seon.issue/detector`, grouped by detector
symbol and by status, and both queries returned `[]`; the pull of
`7cf1077d99bb` returned `nil`. The 63 entities were written before the
2026-09-16 21:10Z refork of `default` and the store reset
(`docs/prds/steward-platform/plan/unsettled.md:706`, `:628`); database data is
disposable by ruling, so this is expected — but it means no generated issue is
startable today for the plainer reason that none exists. The 63 above remain
the measured evidence; a re-run is one `generate!` call
(`issue-generator-2026-09-16.md:206-209`).

### (c) Options

**(a) `start!` accepts `:seon.issue/detector` as an alternative done-query
source, and refuses an issue with neither tests nor a detector.** *Guarantee:*
one rule at the writer, expressed as the writer's version of the choice
`check-form` already makes on the read side; a generated issue becomes
startable and an issue with no way to decide done stays refused.
*Cost:* `start-tx`'s refusal at `src/seon/issue.clj:781` becomes a two-branch
condition plus one refusal for the neither case; two regressions, per the
issue note's acceptance
(`docs/seon/issues/generated-issues-carry-no-tests-so-start-refuses-them.md`).
*What we give up:* nothing measured; the settlement path must then also read
the detector, since `:seon.issue/resolved-tx` is written by settlement.

**(b) Require a worker to WRITE the acceptance test first, then start.**
*Guarantee:* every started issue is decided by a test, one rule everywhere.
*Cost:* the worker cannot be started to write the test, because starting is
what refuses — the loop has no entry. A human or another agent would have to
author 63 deftests before any generated issue could be worked.
*What we give up:* the generator's whole value, which is that a detector both
opens and closes its issues without anyone writing prose or tests.

**(c) Mint a placeholder `:seon.test` entity per generated issue.**
*Guarantee:* `start-tx` needs no change at all. *Cost:* a program fact for a
deftest that does not exist, on `:seon.test/sym`, the identity the runner
selects by. *What we give up:* the truthfulness of the program graph — the
generator note rejected this for exactly that reason
(`issue-generator-2026-09-16.md:196-203`).

### (d) Recommendation — (a)

§2.2: facts over inference, and the missing fact is already declared. The
detector ref IS the done-query — its schema says so
(`resources/seon/schemas/seon.issue.edn:35`) — and the resolution semantics are
already implemented and proven: the same entity resolves and reopens across
runs without losing identity, with the hand-edited prose surviving
(`issue-generator-2026-09-16.md:117-132`). `start!` reading it is not a new
mechanism; it is the writer catching up with the reader, since `check-form`
(`src/seon/issue.clj:564-573`) already made this exact choice.

§2.4 supplies the shape of the refusal that must remain: an issue with neither
tests nor a detector has NO way to decide done, and admitting it would be the
project's recurring failure class — a check that reads absence of signal as
health, which is precisely what `check-form`'s docstring says the empty
`{:seon.test/changed []}` was. So the rule is two-sided: either evidence
source admits the start, neither refuses by name.

The dependency's own mechanism carries it. `start!` writes through
`[:db.fn/call #'start-tx …]` (`src/seon/issue.clj:831`), so the decision is
re-derived inside Datahike's writer against the database value it is committing
against — a detector that was retracted between the agent's read and the write
cannot slip through, which is the owner law about pre-reads. Option (c) would
put the lie in the program graph itself; option (b) has no entry point.

---

## 5. D2 scope — docstring issues for test helpers, or `src` only

### (a) Background

`seon.issue.detect/public-without-doc` (`src/seon/issue/detect.clj:100-144`)
yields every public, source-bearing function with no `:seon.fn/doc`, excluding
functions that do not OWN their defining form (several interned by one
`defrecord` form, where no docstring can be attached). Its one-argument arity
is deliberately the whole population, test helpers included — its docstring
calls this "an honest over-report, not a name-based exclusion"
(`detect.clj:107-112`), because a name rule is one of §2.2's three banned
substitutes.

The missing fact was then declared rather than inferred. `:seon.fn.file/root`
landed on the file entity as the exact root the indexer walked
(`docs/prds/steward-platform/research/source-root-fact-2026-09-16.md`), and was
renamed `:seon.fn.file/relative-root` in `28f1a761e`
(`resources/seon/schemas/seon.fn.file.edn:5`, `:13`;
`src/seon/fn.clj:1048`). Absence is meaningful — a file under no declared root
is scoped out, never assumed to be production — and the detector's
two-argument arity joins POSITIVELY on the fact
(`detect.clj:130-136`). So the scoping is a declared fact and a Datalog join,
with no name pattern anywhere.

### (b) Real example — live on `default`, 2026-09-17

My single live read ran the detector's three arities against `default`'s
current database value:

| call | subjects |
|---|---|
| `(public-without-doc db)` — unscoped | **30** |
| `(public-without-doc db {:seon.fn.file/relative-root "src"})` | **2** |
| `(public-without-doc db {:seon.fn.file/relative-root "test"})` | **28** |

The two `src` subjects, complete:

```
seon.flow/->CountedDroppingBuffer
seon.flow/->RefusingBuffer
```

Ten of the 28 test-root subjects, in the detector's own sorted order:

```
seon.background-blob-test/binary-capability
seon.cluster.source-test/activation
seon.cluster.source-test/populate!
seon.cluster.source-test/populate-blocked!
seon.cluster.source-test/populate-fails!
seon.cluster.source-test/populate-from-data!
seon.contracts-fixture/install-orders!
seon.contracts-fixture/request
seon.contracts-fixture/submit
seon.contracts-fixture/with-agent
```

This reproduces the 2026-09-16 measurement exactly — `31 → 2 src + 28 test
helpers`, with the same two `src` symbols
(`docs/prds/steward-platform/plan/unsettled.md:679`;
`source-root-fact-2026-09-16.md:92-96`) — on a database that has since been
reforked, which is stronger evidence than a repeat on the same branch would be.
The file population behind it: 335 `seon.fn.file` entities, 106 `src`, 228
`test`, 1 under no root (`source-root-fact-2026-09-16.md:70-76`).

One honest tension I could not resolve without a second probe: the generator
note lists `->CountedDroppingBuffer` among the six functions excluded for not
owning their form (`issue-generator-2026-09-16.md:87-92`), yet the live run
names it as a `src` subject. Either its sibling constructor's span changed, or
the note's list (written with an ellipsis) named the family loosely. The live
numbers above are what the detector answers today.

### (c) Options

**(a) Scope D2 to `{:seon.fn.file/relative-root "src"}` by default; the whole
population stays available as the one-argument arity on request.**
*Guarantee:* 2 issues instead of 30, both real production findings; the
unscoped over-report is one argument away and loses nothing.
*Cost:* the default generator invocation must pass the request map
(`generate!` already takes `:seon.issue/detector` and `:seon.issue/severity`,
`issue-generator-2026-09-16.md:206-209`). *What we give up:* test helpers stop
being nudged toward docstrings by the generator — though nothing stops a
steward running the unscoped arity.

**(b) Keep the unscoped over-report as the default.** *Guarantee:* the
detector's honesty is absolute — every public function with no docstring is
reported, and no policy hides any of them. *Cost:* 28 of 30 issues are test
helpers, and a steward's ranked list is 93% noise; the generator note already
recorded that the 63-issue run's top responsible namespaces were
`seon.instrument-test` 8, `seon.cluster.source-test` 5,
`seon.contracts-fixture` 5, `seon.schedule-test` 3
(`issue-generator-2026-09-16.md:143-148`). *What we give up:* the usefulness
of the generated population as a work queue.

**(c) Scope `src` by default and declare a separate lower-severity run for
`test`.** *Guarantee:* both populations are visible, ranked apart.
*Cost:* two standing generator invocations for one standard, and two severities
for one finding. *What we give up:* §2.5's one-mechanism rule — the same
detector and the same subject family with two policies attached.

### (d) Recommendation — (a)

§2.2 has already been satisfied here and it is worth being explicit about why:
the earlier answer to "these are test helpers" would have been a name rule over
`-test` / `-fixture`, a banned substitute. Instead the missing fact was declared
at the one indexing seam where the indexer knows the root it walked
(`src/seon/fn.clj:1048`), and the detector now JOINS on it
(`src/seon/issue/detect.clj:130-136`). That is the whole shape §2.2 asks for,
and scoping the default run is now a query argument rather than a policy.

§2.4's honesty requirement is met by absence being meaningful rather than
defaulted: a declaration under no declared root — one file today — is scoped
out of the `src` run rather than assumed to be production, so the scoped answer
never over-claims. And the unscoped arity remains the same function, which is
why this is not option (c): one detector, one standard, one mechanism, with the
scope supplied by the caller (§2.1 — the caller hands the projection, the
function does not fetch a policy at call time).

Two production subjects is also the right size for the standing order that a
generated population is a steward's ranked queue. Thirty subjects of which 28
are fixtures teaches a reader that the queue is noise, and a queue a reader
learns to ignore is worse than no queue.

---

## Vocabulary corrections

Legacy or drifted spellings met while reading for this batch, with the grounded
replacement. Per the standing order these are retired on sight when their file
is next in scope.

| Where | Spelling met | Grounded replacement |
|---|---|---|
| `config/default.edn:21` | "durable receipts awaiting settlement" | **evaluation** / **result** — AGENTS.md §3's vocabulary table rules "receipt" a legacy identifier; the durable entity is the `:seon.eval` evaluation. |
| `config/default.edn:131` | "The seed rides the terminal receipt" | **evaluation** (the terminal evaluation), same rule. |
| `docs/prds/steward-platform/plan/overnight-report-2026-09-17.md:152` | "receipt seeded twice" | **evaluation** — the fixture seeds an evaluation entity twice. |
| `docs/prds/steward-platform/research/issue-generator-2026-09-16.md:198` | "Minting a `:seon.test` row" | **entity** — Datahike's own noun; "row" for a Datahike entity is the spelling AGENTS.md §3 warns against in prose. Same file, `:173`: "the detector's own `:seon.fn/sym` row". |
| `docs/prds/steward-platform/research/source-root-fact-2026-09-16.md:74`, `:92` | attribute `:seon.fn.file/root` | **`:seon.fn.file/relative-root`** — the installed attribute (`resources/seon/schemas/seon.fn.file.edn:5`), renamed in `28f1a761e`. The note is a dated point-in-time record, but a reader copying the key from it writes a query that silently matches nothing. |
| `src/seon/issue.clj:762`, `:781`, and throughout the issue notes | "worker" for the agent an issue starts | **agent** — `start-tx` creates an agent through `seon.cluster.agent/creation-tx` (`src/seon/issue.clj:787`). "Worker" is not a name any dependency or schema uses; `:seon.issue/agent` is the declared attribute. Flagged for the owner rather than asserted: if "worker" is wanted as the settled term for an issue-assigned agent, it belongs in the vocabulary table with sources on both sides. |

No newly invented noun was found in `src/seon/issue/opening.clj`,
`src/seon/issue/detect.clj` or `src/my/edit.clj`: candidate names
(`:bare`, `:namespace-picture`, …) are dial enum members naming renderings
under trial, and `detector` is declared once as an attribute with a docstring
(`resources/seon/schemas/seon.issue.edn:35`).

---

## Verification boundary

Read end to end before writing: AGENTS.md §2, §3, §5, §7; the overnight
report's Owner-decisions section; `issue-context-trials-2026-09-16.md`;
`issue-generator-2026-09-16.md`; `source-root-fact-2026-09-16.md`;
`adoption-retry-on-analysis-refusal-2026-09-17.md`; the three issue notes
cited. Every `file:line` above was opened at that line. ONE live read against
`default` (`jvm` mode, explicit custody, 89 ms, read-only); no writes, no test
JVM, no `bin/seon` command, no lane or cluster was operated. `default` was
never stopped, reforked or restarted. Nothing is committed.
