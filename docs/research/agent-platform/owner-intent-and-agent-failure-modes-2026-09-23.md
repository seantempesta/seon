---
type: research
status: current
created: 2026-09-23
scope: why agents keep building a nightmare system, and what the owner actually wants — input to the AGENTS.md rewrite
---

# Owner intent and agent failure modes (2026-09-23)

Distilled by the orchestrator from a full day of owner corrections. This is the brief for
rewriting `AGENTS.md` as one cohesive document; it is not itself an instruction file.

## What the owner is building

Seon is a Clojure system where AI agents write, test and improve their own code inside a
running system. Everything else serves that:

- **One JVM, many realities.** A cluster is a Datahike branch plus its agents; a branch is a
  pointer, not a copy. Code lives as rows on a branch; the JVM's loaded code derives from
  the files. An agent works on its own branch at the REPL, gets immediate feedback, and its
  work reaches the shared code and the files only through a tested, reviewed merge.
- **Batteries included for agents.** Define schemas and you can store and query anything;
  write functions and tests; write a function that emits hiccup, declare it as a render,
  and get a data-driven UI. Fast, feeling-independent environments.
- **Tests are the real system.** A test is a plain `deftest`, or a `defn` with a Malli
  schema whose declared arguments tell the runner what to inject (a database value, a
  connection, a world, an agent, a declared external capability). Worlds are data built
  through the system's own writers. Reads share; writes fork in milliseconds; worlds are
  prepared ahead. Only external effects get test versions, passed as values.
- **Our outside agents (Claude, Codex) are Seon agents.** Same branch, same REPL entrance,
  same checks, same merge. The orchestrator acts as root. Dogfooding everything is the goal.
- **Small.** Toward 10,000 lines. Every operation sub-second by design.

## How the owner works, and what he expects back

- He thinks in Clojure and Rich Hickey's terms: simple (one role) over easy; values not
  places; information as data; the REPL to understand before writing; small functions
  composed; accretion not breakage; design by taking things apart.
- He asks sharp questions: "is this a hack?", "why is this complex?", "what's the benefit
  of our custom version?", "did you read the plan?", "why would it do that?". Each one found
  a real defect. Agents must ask these of their own work before he has to.
- He wants the dependency's way: read Malli/Datahike/konserve/SCI/clj-kondo source first;
  our forks' patches are presumed lazy until proven to be real upstream bugs.
- He wants root causes, not fires: group failures by the mechanism that manufactures them;
  most test failures are tests built wrong, not code that later cuts delete.
- He rules quickly when asked a clear question with options; he does not want to re-rule
  what an existing ruling or law already decides.
- He is often away: the work must continue correctly without him, by the same standards.

## Why agents built a nightmare (read between the lines)

Each is a habit that is locally reasonable and globally destructive:

1. **Building instead of using.** Agents wrote mechanisms the dependency already had
   (a batch writer in konserve that broke its atomicity invariant; a query-cache key that
   retained databases; caches keyed on a fork-invented `:cache-context` presented as
   Datahike's; a second analysis beside clj-kondo that dropped 400 declarations). Cause:
   they did not read the dependency, and nothing asked "does upstream already do this?".
2. **Manufacturing test worlds.** Tests hand-write rows and redefine Vars because it is
   easier than going through the owner; every schema change then breaks dozens of tests.
   92 reds traced to five such causes; planned cuts retire only two.
3. **Patching the site, not the owner.** A failure gets a guard, a fallback, a workaround
   test, a special case, an exemption — each adds a mechanism and hides the cause.
4. **Stating causes before verifying them.** "The collector deleted the nodes" (it never
   ran); "the leak fix caused it" (it did not). Unverified causes become wrong fixes.
5. **Plans beside plans.** New design documents were written next to existing specs that
   already owned the area; rulings were appended as notes; specs became unreadable lists.
6. **Relaying instead of thinking.** An option offered by a lane ("a stub HTTP server")
   was passed on without looking at the code, where one function argument solved it.
7. **Accepting reports without reading diffs**; "landed" treated as "proven"; a weakened
   check ("some green test reaches it") accepted in place of the ruled one.
8. **Silent assumptions of an empty world**: a branch inherits the real program; code and
   tests that assume a fresh single-cluster store break on every new fact.
9. **Whole-program work per call** (rebuilding an SCI context from 4,820 rows on any head
   move; re-reading declarations per read) where the change is small and the listener
   already says what changed.
10. **Long functions and long files**, which are where the braids live.

## What must be true of the rewritten AGENTS.md

- One cohesive, evergreen document (no dated narration, no plan/PRD pointers, no lane IDs;
  plan-specific additions live in the plan directory's AGENTS.md). Target about 250 lines.
- It must change behaviour, not list incidents: lead with the mindset and the few laws
  that prevent the habits above, each stated as what to DO, with a one-line reason.
- The order a good agent works in: understand (read the plan's owning spec and the
  dependency source; explore at the REPL until you can predict the result) → design the
  smallest composition (name what it deletes; algorithmic cost; what a principal Clojure
  developer would do) → get it reviewed → implement small → prove on the running system →
  report honestly.
- The system model (clusters, branches, contexts, the one REPL entrance, program rows,
  loaded code, the merge gate) stated once, simply.
- Tests: the real system with injected worlds; no fixtures, redefs or hand-built rows.
- Errors: declared data; agent mistakes returned, system faults loud; nothing swallowed.
- Every existing owner ruling kept, stated as the rule it created, not as a quote log.
- Operations (bin/seon, the one JVM, adoption/integration protocol, commits, no pushes,
  no worktrees) in one compact section.
