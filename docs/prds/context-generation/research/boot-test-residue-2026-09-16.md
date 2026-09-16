---
type: research
date: 2026-09-16
---

# Boot-test residue: the four ownerless cold-gate reds

Thread: bounded triage-and-fix over the four `seon.cluster.boot-test` tests red
in every cold gate from batch 68 to batch 94 (`tmp/orchestrator/gate-results/
batch-94/named.log`, retained root `tmp/test-runs/run.MQiXUY`), continuing
[batch 68's classes](batch-68-boot-test-reds-2026-09-16.md). Branch
`steward-platform`, HEAD `e3f5148d1` at the time of the probes.

No gate was run from this thread. Every measurement below comes from probe JVMs
in a throwaway worktree (`git worktree add tmp/boot-residue-wt HEAD`, its
`reference-code` symlinked to the checkout's) carrying HEAD plus only this
thread's own changes, so no other lane's in-flight edit is in the
picture. Each probe armed contracts exactly as a worker does
(`seon.test.arm/initialize-contracts!`, `src/seon/test/fast.clj:29`) and ran
the test vars through `clojure.test/test-vars` with the namespace's own
fixture. The published base the `published-root` fixture clones was prepared
the way `bin/test` prepares it (`seon.test.runner --prepare-base`,
`bin/test:747`). The default cluster was never called.

## The class: a branch's own projection was derived before the branch was admitted

Two of the four reds are one defect. `seon.cluster/stand-boot-layers!` opened the
cluster branch and immediately derived its projection —
`schema/projection-from-database` then `sci.eval/projection-state`
(`src/seon/cluster.clj:3355-3358` at HEAD) — and only afterwards, inside
`schema/call-with-projection-state`, asked whether the branch was admissible at
all (`require-activation!`, then `accrete-schema-population!`'s
`declaration-changes` comparison). A branch that is NOT admissible is exactly
the branch whose own projection cannot answer for this program, so the
projection build refused first and the ruled steer never ran.

Measured, in a probe JVM over the tests' own fixtures:

| seeded branch | refusal the operator got | refusal the branch deserves |
|---|---|---|
| sovereign legacy store (`seed-incompatible-sovereign!`, `boot_test.clj:186`) | `The cluster instance failed above the REPL: :malli.core/invalid-schema`, `{:schema :seon.env/environment :form :seon.env/environment}`, thrown from `seon.sci.eval/projection-state`'s instrumented call (`eval.clj:175` → `seon.instrument/compiled-wrapper`, `instrument.clj:572`) | ``Cluster `legacy` cannot reopen in place: `:seon.ns/requires` changed :db/valueType from :db.type/symbol to :db.type/ref…`` |
| every `:seon.fn/sym` retracted (`partial-clusters…`, `boot_test.clj:894`) | `Schema publication refused :my.background/invalid-call-error: :seon.render/ai names seon.background/render-ai whose declared input nil does not accept the declaring shape.` (`seon.schema/assert-render-contracts!`, `schema.clj:1631`, over the branch's empty `:seon.schema.projection/function-contracts`) | the activation refusal naming the missing `:seon.activation/executable-symbol` members |

The first is the open blocker
[a sovereign schema refusal is replaced by an opaque :malli.core/invalid-schema](../../../seon/issues/archive/a-sovereign-schema-refusal-is-replaced-by-an-opaque-malli-invalid-schema.md),
whose unanswered question was *which form fails to compile, and against which
population*. The answer: `:seon.env/environment`, against the projection
`schema/projection-from-database` derived from the legacy branch, because
`seon.instrument`'s wrapper compiles a contract against the projection SUPPLIED
IN THE ARGUMENTS (`seon.instrument/supplied-projection`,
`src/seon/instrument.clj:542-557`) — and the argument here is that branch's own
projection. Neither `a3cbcd9a8`, `0b910eb69` nor `bb46455fb` (the note's
suspects) is the cause; the ordering is, and it predates all three.

### The fix

One admission seam, `seon.cluster/require-admissible-branch!`, called on
`initial-database` before any projection is derived from it
(`src/seon/cluster.clj:3350`). It runs under the PACKAGED declarations
(`schema.edn/packaged-forms` + `schema/declaration-projection`), never the
branch's, and asks the two admission questions in the fixture's own ruled
order: `require-activation!` first (the seeded sovereign fixture deliberately
stores a complete closure "so boot may then reach the intended
incompatible-schema refusal instead of correctly refusing an unsealed source
first", `boot_test.clj:190-196`), then `declaration-changes` for its refusal.
`require-activation!` is removed from the later projection-state block, where
it was the second of the two; `accrete-schema-population!` still performs the
comparison it transacts through, unchanged.

Cost, stated honestly and NOT measured here: every boot now runs
`declaration-changes` twice — once for the refusal, once inside
`accrete-schema-population!` moments later — one extra
`schema.datahike/malli->datahike-schema-in` pass over the canonical attributes.
The probe that would have timed it needs a published root (it refused with
"No `current-src` branch is published"), and timing it was outside this
thread's bound. If boot time regresses, the dissolution is to compute the
changes ONCE at the admission seam and hand them to the population, rather
than to drop the check.

### Measured after the fix (probe JVM, same fixtures)

- `incompatible-sovereign-schema-refusal-steers-the-operator`: 14 assertions,
  0 failures. The wrapped message is now
  ``The cluster instance failed above the REPL: Cluster `legacy` cannot reopen in place: `:seon.ns/requires` changed :db/valueType from :db.type/symbol to :db.type/ref, which Datahike does not apply to an installed attribute. `bin/seon init legacy --force` destroys and reforks it from `current-src`; use export/import instead to preserve its data.``
  and the cause chain carries
  `{:seon.boot/attribute :seon.ns/requires, :seon.boot/installed {… :db/valueType :db.type/symbol …}, :seon.boot/current {… :db/valueType :db.type/ref …}, :seon.boot/cluster-name "legacy"}`.
- `partial-clusters-refuse-and-fresh-clusters-are-current`: the refusal is now
  `The source activation closure is missing 1003 facts: [… #:seon.activation{:executable-symbol "babashka.fs/delete"}] … 993 more.`,
  so `activation-refusal` is present, every missing member is an activation
  member, `missing-count` is 1003, and `(count (ex-message failure))` is 918 — inside the
  test's 2000-character bound. The NullPointerException at `Numbers.java:1099` was
  the consequence of `activation-refusal` being nil and is gone.

## `boot-order-completes-in-one-start` — the episode it scans for was retired

The test rendered the bootstrap agent's history and searched it for three
literal markers (`boot_test.clj:1409-1411` before this change):

```clojure
build-index    (.indexOf session "(defn largest")
verify-index   (.indexOf session ":seon.fn/spec")
complete-index (.indexOf session "(run/complete")
```

Measured: `(not (< -1 4621 -1))` — only the middle marker is present, and it
comes from the ASSIGNMENT text, not from any evaluation. The captured session
(7,321 bytes, 10 prompts, probe JVM) is the generated opening: `(help)`,
`(my.message/read …)`, the identity pull, `(seon.plan/plan {})`, the inbox
pull carrying `seon.bootstrap/task-message`'s assignment, the settings, notes,
`dir`, error and cluster reads. No `defn`, and no completion form.

Both missing markers were the hand-authored episode of `37df160dd`
(2026-08-12, "Implement complete HALF bootstrap episode"), whose
`resources/seon/bootstrap.edn` literally contained
`"(defn largest\n…"` and
`"(run/complete \"Built largest: …\")"`. That file is gone. The completion
call also moved: `e915d2de0` (2026-09-09, "Keep the opening ordered and render
truthful REPL data") renamed `my.run` to `my.turn`, so `seon.run/walkthrough`'s
last entry is `(my.turn/complete {…})` (`src/seon/run.clj:74-79`). And the run
itself stopped being a scripted episode: `6aca09cce` (2026-09-09, "Use the
system-turn generator for seeded agent openings") replaced
`seon.turn/generate-turn`'s per-entry derivation with the record walk —
"Creation and later system turns derive their sources from the same record
walk" (`src/seon/turn.clj:4886`). Defining `largest` is now the agent's
assignment, performed by a model; a boot test never completes it.

So the expectation is stale, not the behavior. It now asserts what the opening
IS, in derived markers rather than hand-copied text — help first, then the
trigger message read, then the plan read, then the assignment
`(bootstrap/task-message)` — and the turn's CLOSURE stays proven by
`await-bootstrap!` (the fact), not by a word in the rendered text. Measured
indices on the captured session: 74 < 3322 < 3957 < 4460.

## `a-generated-prefix-resumes-on-the-same-run-after-jvm-kill` — the drill's pause point moved

The parent waits 60 s for the child JVM to print `deriving <run-id>`
(`boot_test.clj:1689`); the child produced that line by redefining
`seon.bootstrap/next-entry` (`test/seon/cluster/bootstrap_resume_child.clj:12`).
`6aca09cce` removed the only call to that function, and nothing in `src/`
calls it today:

```
$ rg -n "next-entry" src/
src/seon/bootstrap.clj:628  (defn- next-entry-in
src/seon/bootstrap.clj:701  (defn next-entry
src/seon/bootstrap.clj:711     #(next-entry-in request run-id))))
```

So the redefinition was never invoked, the boundary was never published, and
the test spent its whole bound waiting for a line that could not arrive — the
project's named failure class, a wait whose subject no longer exists, read as
"still working" (`bin/test` recorded `elapsed-ms=60685`, the
`TimeoutException` at `CompletableFuture.java:1981`).

The drill now redefines the seam production actually reaches,
`seon.turn/generate-turn` (`src/seon/turn.clj:4851`, dispatched at
`src/seon/turn.clj:4995`), and prints the run id from the work it is handed.
It calls through to the real derivation for the first two entries before it
parks: the subject is a generated PREFIX resuming, so the killed child must
leave one behind — parking at the FIRST derivation left the run with no
evaluations at all (measured: `ordinals` `[]`, and the restart closed the
empty turn).
With the prefix restored, one further expectation in that test proved stale:
`(is (nil? (:seon.turn/closed-tx run)))`. `34e47f595` (2026-09-09, "Close all
prior open turns during boot recovery") deleted the exemption it encodes — the
old `recover-call` docstring read "A generated run stays open and attached: its
settled receipts are the append-only derivation prefix" — and the current rule
is "At boot, close every open turn and interrupt its unfinished evaluations and
effects… a saved holder or a generated-source tag cannot exempt an open turn"
(`src/seon/turn.clj:1793`), the crash model's "interrupted execution never
resumes" (AGENTS.md §1). The assertion now reads `(some? …)`, with that ruling
quoted in place; every other assertion in the test — same run id, the derived
prefix `[0 1]`, the trigger, exactly one run for that trigger, no
`:seon.bootstrap/prefix-drift`, no `:seon.turn.loop/trigger-already-answered`
— passes unchanged, which is what "resumes on the same run" means under the
current model.

The orphaned `seon.bootstrap/next-entry` and the regressions that exist only to
exercise it are filed as
[seon.bootstrap/next-entry has no caller in src/](../../../seon/issues/bootstrap-next-entry-has-no-production-caller.md).

## Files touched

- `src/seon/cluster.clj` — `require-admissible-branch!` added; called on the
  provisional branch's database before any projection is derived from it;
  `require-activation!` removed from the later projection-state block. Nothing
  in the publication path (`refresh-source!`, `incremental-source-refresh!`,
  `full-source-refresh!`, `development-source-refresh!`) is touched — that path
  is another lane's, and this commit stages only these hunks.
- `test/seon/cluster/bootstrap_resume_child.clj` — the kill drill pauses at
  `seon.turn/generate-turn`.
- `test/seon/cluster/boot_test.clj` — `boot-order-completes-in-one-start`'s
  episode markers replaced with the generated opening's own, with the ruling
  that retired them quoted in place.
- `docs/seon/issues/bootstrap-next-entry-has-no-production-caller.md` — new.
- `docs/seon/issues/archive/a-sovereign-schema-refusal-is-replaced-by-an-opaque-malli-invalid-schema.md`
  — resolved, with the answer to its open question.

## Re-gate list

`bin/test --paths src/seon/cluster.clj test/seon/cluster/boot_test.clj test/seon/cluster/bootstrap_resume_child.clj -- seon.cluster.boot-test seon.cluster-test seon.cluster.source-test`
plus `bin/test --platform`. No gate was run from this thread.

Probe results at the committed state (each test run through
`clojure.test/test-vars` with the namespace fixture, contracts armed, over a
base prepared from these exact sources):

| test | before | after |
|---|---|---|
| `incompatible-sovereign-schema-refusal-steers-the-operator` | 11 F | 0 |
| `partial-clusters-refuse-and-fresh-clusters-are-current` | 1 F + 1 E | 0 |
| `boot-order-completes-in-one-start` | 1 F | 0 |
| `a-generated-prefix-resumes-on-the-same-run-after-jvm-kill` | 1 E (60 s timeout) | 0 |

A probe run whose published base was prepared BEFORE a later edit fails
`boot_test.clj:885` on the source digest — an artifact of preparing the base by
hand, not of these changes; the gate prepares its base from the snapshot it
runs.
