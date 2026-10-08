---
title: REPL-first slices — dogfood, blockers and the branch-evidence gate
type: research
status: active
created: 2026-10-08
---

# REPL-first slices

What exists: an agent's `defn` on its own branch (MCP `eval_clj`, sci mode) records a program entity there;
`my.test/check` runs the tests reaching a change on that branch and reuses unchanged green members by reach digest
(`seon.test/run`); `seon.cluster.source/write-back!` splices declarations into their file and proves the candidate
by reindexing it. Data flow: defn → branch entity → reaching tests (evidence on the branch) → file text → commit.
Principal approach: reuse those three owners; the commit gate reads stored evidence instead of booting a JVM.

Owner ruling: `review/owner-rulings.md`, last bullet ("REPL-first proving").

## 1. Dogfood, measured (agent `repl-first`, default cluster, 2026-10-08)

The change: `my.agent/settings` docstring now says it returns `{}` when I override nothing (true: `(my.agent/settings)`
answered `{}`). Its one reaching test is
`seon.call-preparation-test/an-acquired-context-supplies-a-leading-database-to-a-first-party-read`.

| Step | Form | Measured |
|---|---|---|
| defn on my branch | `(in-ns 'my.agent) (defn settings …)` | 6 ms evaluation; the Var render lists the pending reaching test and offers `(my.test/check {:seon.test/changed [my.agent/settings]})` |
| reaching tests, first run | `(my.test/check {:seon.test/changed ['my.agent/settings]})` | 1,862 ms: 1 executed (run 650 ms, acquire 35 ms); the rest is selection and recording |
| reaching tests, unchanged program | same form again | 60 ms, `:reused-count 1`, `:executed-count 0` |
| reaching tests, program changed elsewhere, test closure unchanged | same form after an unrelated defn | 820 ms, 0 executed, 1 reused |
| write-back from the branch | `seon.cluster.source/write-back!` with `{:base-commit-id <main head> :commit-id <branch head> :identities #{…}}` | 458 ms, then **refused** `:not-analyzable` (blocker B1) |
| file equals the branch definition | analyze the working-tree file against the branch database and compare digests | 35 ms, `:equal? true` (main's database: `:equal? false`, as it must) |

Loop total (edit → tests → file check): about 2.5 s on first run, under 0.2 s when nothing reachable changed,
against the throwaway check's 2-4 minutes per commit plus queueing for one of two slots.

The throwaway path for this slice's own commit, measured: 2 min 20 s to refuse with zero tests run (every
`seon.cluster.write-back-test` var is declared long), then 3 min 35 s with `--include-long` (6 executed, 7.2 s of
it tests), queued behind two other checks. Three merge tests in that namespace are red on HEAD without this change
(HEAD-only check run `1ee9072479d5`: 26 pass, 5 fail, 1 error; with the change, run `83ea6897fc5c`: 28 pass, the
same 5 fail and 1 error): `a-merge-writes-each-change-into-its-own-file`,
`a-require-added-at-the-repl-merges-and-writes-back-its-ns-form` and
`an-agent-replacement-merges-and-writes-back-its-exact-span`, each failing in `prepare-merge!` before any write-back.

The dogfood change's reaching test is red, and red on an unchanged branch too (agent `repl-first-base`, same form, same error:
SCI cannot resolve `seon.call-preparation-test/probe-current-database`). It is red before this change; the gate
must tell "red before" from "made red" (section 3).

## 2. Blockers found

- **B1 (fixed in this slice). Write-back from the main JVM refuses any file that calls another first-party
  namespace.** `proven-file` analyzed the candidate with no database, so `seon.fn/host-bound-callers` saw calls
  into `seon.agent`, `seon.db`, … as namespaces neither SCI nor clj-kondo's dependency cache knows and threw
  "Cannot classify host binding" (`seon.fn/unresolved-macro-refusal`). The check JVM never saw it: its analysis
  ran over whole roots. Reproduced at the REPL: the same `analyze-entities` request refuses without
  `:seon.source/previous-database` and answers 14 rows in 28-170 ms with it. Fix: pass the merged (`after`)
  database. Regression: `seon.cluster.write-back-test/a-branch-change-calling-another-first-party-namespace-writes-back`.
  The refusal also lost its cause: `:seon.error/offending nil` (`:seon.fn/findings` is absent on that throw).
- **B2. Write-back is reachable only through a merge.** `my.program/write-back!` takes `accept-merge!`'s answer.
  The owner (`seon.cluster.source/write-back!`) needs only `{identities, base-commit-id, commit-id}`, so a
  branch write-back is one small `my.program` function (card RF-1).
- **B3. `bin/lane commit` accepts no branch evidence.** It always runs `bin/seon check` (card RF-2).
- **B4. Host-bound status exists per declaration but is not the gate's question.** `:seon.fn/host-bound?` is
  stored per function (86 true, 5,598 false on default) from `seon.fn/host-bound-callers`: "SCI cannot interpret
  this body". The gate's question is narrower and already answered by evidence: did the branch's definition run
  under the reaching tests? A declaration whose `:seon.fn/host-bound?` is true keeps its compiled Var on a branch
  (CLAUDE.md, "The program is entities"), so its branch evidence proves nothing: refuse it. Platform code the
  compiled path calls (evaluator, writer, test runner, boot) runs compiled under the tests even when SCI could
  interpret it, so it is classified by file class until a per-declaration "reached from compiled callers" fact
  exists (card RF-3). Schema resources, `deps.edn`, `config/`, `bin/`, `script/` and submodule pins always take
  the throwaway check.
- **B5. The automatic test step is not installed.** The agent runs `my.test/check` by hand (F4a is in flight).

## 3. The gate: `bin/lane commit LANE --branch-evidence`

Inputs: the lane's agent id (its branch), the committed paths. No JVM boot; one prepl round trip to default.

1. **Classify paths.** Any path outside `src/` and `test/` `.clj` files, or a file holding a declaration whose
   `:seon.fn/host-bound?` is true or whose namespace is platform (RF-3), sends the commit to the throwaway check.
2. **File equals branch.** For each committed `.clj` file, analyze the working-tree bytes against the branch
   database (`seon.fn/analyze-entities` with `:seon.fn.analyzer/sources` and `:seon.source/previous-database`)
   and require every declaration's definition digest to equal the branch head's (35 ms per file measured).
   This is write-back's own proof run on the file as it stands, so a hand edit that matches the branch passes.
3. **Evidence covers the digests.** The reaching tests of the changed declarations
   (`seon.test/run` selection with `:seon.test/changed`) each hold `seon.test/verified?` true on the branch head
   (green, reach digest current for that head's program). A declaration reached by no test refuses
   `:unproven` naming it. A test red on main before the branch (no green evidence on main, red on the branch
   with the same reach) is listed as `:red-before` and refuses unless root passes `--accept-red-before TEST`.
4. **Lint** `src script test` with `--cache false`, zero errors.
5. **Commit** with `git commit --only` as today, the run id and branch commit in the message trailer.

Owner of each step: path classes and the commit in `bin/lane` (Babashka); steps 2-3 one function
`seon.cluster.source/branch-evidence` (RF-2) answering data, called through `seon.operator/live-root-value!`, the
prepl the hook already uses for schema admission and contract compilation. If default is down, the gate falls back
to the throwaway check and says so.

## 4. The edit hook as the lane's REPL entrance (owner addition)

`bin/seon-hook` already reaches the live JVM: schema admission and contract compilation go through
`seon.operator/live-root-value!` (prepl) and degrade to "unavailable" when no root answers. So the REPL entrance
needs no new transport. Design (card RF-4):

- **Which branch:** `SEON_LANE` (set by the lane) names the agent; else the one lane whose `bin/lane status`
  claim holds the edited path; else none, and the hook stays lint-only and says so.
- **What it sends:** the top-level forms of the edited file whose bytes differ from the branch's recorded
  source span (the declaration's `:seon.fn/form-span` text against the file, analyzed once by clj-kondo, which the
  hook already runs), as one eval request: `(in-ns 'ns)` then those forms, through `seon.mcp/call` `eval_clj`
  with `agent` = the lane. The reply is printed as the value printer made it.
- **What it skips:** a form whose declaration is host-bound or platform (RF-3) is not sent; the hook prints
  "host-bound: throwaway check at commit".
- **Tests:** once F4a lands the eval request runs the reaching tests itself; until then the hook appends
  `(my.test/check {:seon.test/changed [...]})`.
- **Cost:** measured prepl round trip from Babashka: 6-129 ms per call (first call includes connection);
  a one-function defn evaluates in 6 ms; well under 1 s unless reaching tests execute (section 1).
- **Failure is local:** no live root → lint only, with the reason.
- **Gemini:** the review batch (`schedule-gemini-review!`, `agy -p`) is **disabled** (`.claude/seon-hook.edn`
  `:review :enabled false`). When enabled it calls `agy` on the owner's Gemini CLI login, not a metered API key
  in this repo; it is still a provider call made by default on every edit, so it stays off; the ~400 lines of
  batching machinery are a deletion candidate (RF-5).

Then write-back becomes a check: the hook made the branch equal the file, and the gate checks file = branch.

## 5. Friction met at the REPL (each a task, RF-6)

1. `(my.program/tests-reaching 'my.message/read)` refuses a bare symbol; the contract example prints
   `#object[datahike.db.DB …]` and a subject `tV`.
2. `(my.program/diff)` in the same eval request as the `defn` answers empty: the defn records at settlement.
3. `(my.test/check {})` with no `:changed` selected `scratch.redtriage/probe-observed` (`(is true)`, reaching
   nothing changed) because `seon.test/changes` compares against the published program, not main.
4. No discoverable way to read my own connection or branch database: `seon.db/*conn*` is nil in an eval;
   `seon.db/connection` is not exposed; I used `(seon.db/branch-connection (seon.db/db) branch)`.
5. A `def` whose init threw leaves an unbound Var; the next deref answers `ClassCastException SciUnbound`.
6. `slurp` is unresolvable in SCI (`my.fs/read` is the tool), so ad hoc file probes need `java.nio`.
7. A failing test prints clojure.test's report and a 40-frame stack into `:out` (1,075 tokens): a second printer.
8. `seon.profile` has no reset; `summary` is process-cumulative, so a slow call cannot be ranked alone.
9. `(my.test/check …)` with zero executions still costs 820 ms after an unrelated program change.
10. Default restarted mid-session (07:21Z): the branch survived, the SCI defs did not, and the prepl answered
    "not ready" for the boot window — expected, but the hook must say "lint only" there.
11. `write-back!`'s `:not-analyzable` refusal carries `:seon.error/offending nil`, hiding the analyzer's message.
