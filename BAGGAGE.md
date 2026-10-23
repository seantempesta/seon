# Baggage

v1 Seon (`~/src/seon`) mechanisms deliberately left behind, and why. Each is a row of `docs/parity.md` marked dropped.

- **``bin/lane`` claims and proven commits:** worktrees and root's merges, with no locks (`docs/decisions.md`, "No locks, no lane cap, no waiting").
- **`chain!` and ``:seon.task/next``:** tasks are linked by `:needs` only.
- **The 13 `.agents/skills`:** an agent's context is its task and the wiki sections the task cites ("process, not whim").
- **The JVM evaluator mode of `eval_clj`:** one evaluator, Jolt's.
- **A separate blob store:** large values stay inside the database's own store (in progress; `docs/parity.md`, storage).
