# Rules every lane follows (root, 2026-10-07)

Read `AGENTS.md` (CLAUDE.md) end to end and load `.agents/skills/data-oriented-clojure/SKILL.md` before any Clojure
design or edit. Read the evidence your assignment names end to end before designing.

- **Probe first.** Run `bin/seon status` and MCP `runtime_status` and record anything degraded. Use MCP `eval_clj`:
  - your own agent id (= your lane name) in SCI mode, in a scratch namespace;
  - JVM mode only for host measurement, passing `(seon.cluster.boot/connection "default")` explicitly.

  Never `load-file`, `intern` or `alter-var-root` into default. Never stop, restart or reset default; write **RESET
  NEEDED** in your notes and root does it.
- **Files.** Claim before editing with `bin/lane claim LANE PATHS` (or `bin/lane add`). Root has already claimed the
  paths named in your assignment. Commit only with `bin/lane commit LANE -m "MSG" --test NS... PATHS`, naming the
  tests that reach the change: whole namespaces often exceed the 120 s check. Pass the message inline. Run
  `bin/lane release LANE` when done. No `git add -A`, stash, reset, restore or worktrees, and no push or PR.
- **Scratch and notes.**
  - Scratch files go only under `<session scratchpad>/<lane>/`.
  - Notes go in `docs/research/agent-platform/2026-10-07/<lane>/<topic>-notes.md`. The Write tool refuses files
    named `report.md` or `findings.md`. Commit the notes with your slice. Nothing important goes in `tmp/`.
- **ONE printer.** The value printer is the only thing that turns a value into text:
  - no `pr-str` into a message, log, hash or stored copy;
  - no new clip, cap or floor;
  - fixes are algorithmic, at the producer.

  Agents' own values are never clipped. Seon never generates bulk of its own.
- **Nothing broken escapes inspection.**
  - Everything wrong you see goes in your notes with the exact form, value and timing: every error, a reply with a
    fault, an ordinary operation over 1 s, allocation that grows with input size, or ugly or cut output.
  - Fix it if it takes about 15 minutes and is inside your files.
  - Otherwise find its existing task (`(my.task/open (seon.db/db))`, `resources/seon/tasks/*.edn`) or create one
    with `seon.task/create!`, and name it in your notes. Never hand-type task ids.
  - "Pre-existing", "unrelated" and "known" are not reasons to stay silent.
- **Measure before and after.** Every performance claim gives ms, armed calls and allocated bytes, measured on the
  same probe on the parent and on the change. For allocation use
  `com.sun.management.ThreadMXBean/getTotalThreadAllocatedBytes`, or `getThreadAllocatedBytes` for exact
  per-thread numbers. Every fix adds a class regression or tightens the contract at the producer.
- **No rushing.**
  - Before committing, write a design check in your notes: did the design hold against the real code, and what was
    wrong or awkward?
  - If the design looks wrong, STOP and report it with options; don't patch around it.
  - Don't mark done what isn't proven.
- **Report.** Your final message gives:
  - broken things first;
  - commits with their sizes, net src/test lines and timings;
  - tasks created or linked;
  - verification limits;
  - whether default needs a restart or reset.

## Guide mistakes to avoid

1. Label every rewrite EQUIVALENT (preconditions checked), BEHAVIOR CHANGE (with its regression) or TARGET (don't write it). Check §0 before shortening: absent vs nil vs false vs empty; `some`/`(:k m)` are truthiness tests, `select-keys`/`find` are presence tests.
2. Errors: use the INSTALLED constructor `seon.error.refusal/error`. Don't write the spec's target patterns yet (`fail!`, `caught`, the 4-arg constructor). Keep the existing read-refusal unwraps (load-bearing until errors S-reads), add no new one, and don't use `let-ok`. Catch only at the boundary; no catch-to-wrap-and-rethrow. Validation prose comes from Malli (`me/humanize`), not hand-built English.
3. No new atoms, no `memoize`, no `requiring-resolve`/`defonce delay` to escape a namespace cycle, no new dynamic Vars. A cache is a `seon.cache` kind keyed by content, and only after a profile shows it pays. Prefer the algorithm (work proportional to what's shown or changed) over any cache.
4. One printer: no `pr-str`/`str` of a value into a message, log, hash or comparison; no character quota or second clip (reference-code.md "Value printing" row). Never describe your fix as "a new printer".
5. Contracts on everything you add or change, private functions included, written on one line with registry names. Never weaken one to fit, and never restate a registered key inline.
6. Docstrings: 1-3 lines saying what the function returns. No dates, measurements, history, design argument or `file:line` in source. References are backticked fully qualified names, and tasks are `ns/task-name`.
7. Tests (clojure-testing): through the agent's entrance, asserting DATA (error `:seon.error/type` plus the one member). Text only in the value printer's own tests. Tables for cases of one behavior. Never `with-redefs`/`alter-var-root`/`intern`. Don't add a test that re-expresses a schema or a wall-clock time.
8. Read once and pass the value: no db reads in a loop (`pull-many`), no collection walked twice, eager across effects/binding/try.
9. Today's lane mistakes: running uncommitted code in scratch namespaces in DEFAULT's JVM (use your own agent's SCI branch); shared scratch files crossing commit messages (only scratchpad/<lane>/); unverified tool-limit claims; patching around a wrong design instead of stopping with options; "pre-existing" findings left unrecorded.

## Errors come back to you; you file the tasks (owner, 2026-10-07)

Seon does not auto-create tasks from errors right now. Every error your calls cause comes back in the MCP reply
(`:errors`, `:diagnostics`, `:findings`, each with a result handle to dig into). Read them in EVERY reply, and file
tasks for them as a careful engineer would:

- First search for an existing task on the same defect class (`(my.task/open (seon.db/db))`, `rg` in
  `resources/seon/tasks/`). Link to it rather than duplicating it. One task per defect class, not per occurrence.
- Create one with `seon.task/create!` on default, in the namespace of the producing function, named for the
  behavior (`ns/what-is-wrong`). The intent says what happens, where, the exact reproducing form and value, and what
  the correct behavior is. Name the proof test that should fail today.
- An error that is your own mistake (a wrong call or a typo) is not a task: fix your form.
- An error you expected the call to return is not a task.
- If an error you saw did NOT come back in a reply (only in a log, a fault list or `bin/seon status`), that is a
  delivery defect: file it under the boundary that dropped it.

## One happy path (owner, 2026-10-07)

"WE HAVE ONE HAPPY PATH THAT IS AGENT FOCUSED ON THIS LOOP. No fragile complex bloated shit."

The loop: context = forms that ran (`(help)`, the task's `doc`, its references' `doc`/`source`) plus the agent's own
forms with their real results → the model → ONE read → eval → record → print → appended. Every change makes that path
simpler, faster or more correct for the agent. Before adding anything, answer in your notes: which step of this path
does it serve, what existing owner or library already does it, and what it lets you delete. A second path, a parallel
mechanism, a new subsystem, a fallback, or a feature no agent turn needs is refused. Smaller diffs that delete code win.

## Budgets (owner, 2026-10-07)

"Measure everything. DO NOT LET MULTI SECOND, MULTI MB OR GB ANYTHING happen. Period." Every slice reports
before/after wall ms, allocated bytes, armed calls, commits and store bytes on the same probe. Any non-agent,
non-provider operation over 1 s or 10 MB is stop-and-fix at its producer before landing. A slice that worsens any
number does not land. Budgets per pass: root-plan/live-agents-plan-notes.md §4b.
