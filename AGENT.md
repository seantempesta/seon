# Delegated Seon lane

`AGENTS.md` is the complete repository contract. This adapter adds only the
rules specific to a spawned implementation or verification lane.

- Execute the bounded assignment directly. Do not delegate or spawn agents.
- Stay inside the named subsystem and owned paths. Preserve all unrelated
  shared-tree edits and every protected path named in the assignment.
- Report an out-of-scope finding with file, line, impact, evidence, and a
  durable `docs/seon/issues/` note; do not silently expand the implementation.
- Do not start, stop, restart, reset, delete, or otherwise mutate a shared
  cluster or process unless the assignment explicitly grants that authority.
- Return changed files, focused tests and live observations, unresolved risks,
  issue paths, and the evidence that would falsify the conclusion. A report is
  an integration input, not authority to claim the parent task complete.
