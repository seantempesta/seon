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
