# Baggage

Seon mechanisms deliberately left behind, and why. Started from `../proto/BAGGAGE.md` (session P1), which stays the record until entries move here as code is copied in.

## Left behind at the core assembly (session A1, from proto/flow f2-final)

- **The JVM-written-store reader** (`proto.db/open-jvm`, the `jvm-read` check, `jvm-db`/`jvm-q`): the bridge for path A; nothing on the Jolt path reads a JVM store. `reference-code/jolt-fressian` stays behind the `:fressian` alias.
- **`proto.mock`**: the dashboard's process-free stand-in; the dashboard runs against the real root.
- **`proto.c-bench`, `proto.f1-bench`**: measurement harnesses for writer design C and F1's writer; their numbers are in `notes/option3-F1-flow.md`.
- **The pre-nREPL data REPL in `proto.agent`** (`eval-forms`, `eval-one`, `bounded-eval`): dead since agents speak nREPL (`seon.repl/eval-code`).
- **`proto.seon-printer`, `proto.smoke-seon`**: the printer arrives in its own phase.

## Left behind at the tasks phase (session A1, from proto/agents R1/M1)

- **Root's executor thread and one observer thread per attempt** (`proto.agents.root/executor!`, `observe-attempt!`): replaced by the reconciler proc, whose flights are requests and answers.
- **`proto.agents.schema`'s own registry and instrument!**: the one registry (`resources/seon/schemas`) and `seon.contract`.
- **`proto.agents.task/db-schema`**: derived from the fact schemas (session D1).
- **Root's judge-time `harvest!` call**: the turn loop stages the runtime at its end; the judge reads the branch.
- **The attempt's end read back from the agent's printed reply**: the end is a fact on the branch (`:seon.end/*`).
- **`proto.agents.entrance`** (the line-EDN outside-agent socket): the MCP phase replaces it.
- **The harvest demo and the mock provider runs**: the merge tests and the scenarios cover them.
- **`proto.agent/eval-forms` and `record-items!` for nested evals**: `seon.repl/eval-string`, the one REPL path.

## Left behind from v1 Seon (`~/src/seon`), on purpose

Each row of `docs/parity.md` marked dropped, with its reason:
- **`bin/lane` claims and proven commits:** worktrees and root's merges, with no locks (`../notes/DECISIONS.md`, "No locks, no lane cap, no waiting").
- **`chain!` and `:seon.task/next`:** tasks are linked by `:needs` only.
- **The 13 `.agents/skills`:** an agent's context is its task and the wiki sections the task cites ("process, not whim").
- **The JVM evaluator mode of `eval_clj`:** one evaluator, Jolt's.
- **A separate blob store:** large values stay inside the database's own store (in progress; `docs/parity.md`, storage).
