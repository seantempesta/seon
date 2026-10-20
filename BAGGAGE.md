# Baggage

Seon mechanisms deliberately left behind, and why. Started from `../proto/BAGGAGE.md` (lane P1), which stays the record until entries move here as code is copied in.

## Left behind at the core assembly (lane A1, from proto/flow f2-final)

- **The JVM-written-store reader** (`proto.db/open-jvm`, the `jvm-read` check, `jvm-db`/`jvm-q`): the bridge for path A; nothing on the Jolt path reads a JVM store. `reference-code/jolt-fressian` stays behind the `:fressian` alias.
- **`proto.mock`**: the dashboard's process-free stand-in; the dashboard runs against the real root.
- **`proto.c-bench`, `proto.f1-bench`**: measurement harnesses for writer design C and F1's writer; their numbers are in `notes/option3-F1-flow.md`.
- **The pre-nREPL data REPL in `proto.agent`** (`eval-forms`, `eval-one`, `bounded-eval`): dead since agents speak nREPL (`seon.repl/eval-code`).
- **`proto.seon-printer`, `proto.smoke-seon`**: the printer arrives in its own phase.
