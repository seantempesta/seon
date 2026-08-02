# Seon

**A personal AI that can do anything for you, because it can write code.**

Seon is a long-running Clojure system in which agents author ordinary
functions, schemas, and tests against a shared database program graph. The
runtime is JVM-only: one process can host several sovereign clusters, each
with its own Datahike branch, agents, and `core.async.flow` graphs.

The long-form thesis and project lineage live in
[`docs/seon/vision/`](docs/seon/vision/). The maintained architecture starts at
[`docs/seon/architecture/architecture.md`](docs/seon/architecture/architecture.md).

## The premise

An agent should receive a language, not a closed catalog of tools. Clojure
gives the agent immutable data, ordinary functions, schemas as data, and a
REPL-friendly environment. Datahike keeps durable facts and the program graph;
Malli gives functions and stored values explicit contracts; Flow gives each
agent an independently pausable graph.

Every function in a cluster's program graph is callable. Effects leave
evaluation through the one guarded `seon.effect` owner, while durable facts
are committed to the database. Context and web pages are derived from those
facts rather than maintained as parallel state.

## Runtime shape

- One JVM process owns the process-root Datahike store and shared executors.
- Each cluster is a named branch with one live connection and sovereign
  program facts.
- Each agent owns its own Flow graph, parked between episodes and woken by
  messages.
- SCI evaluates agent forms on the compute executor under one time limit.
- The Datastar web UI is rendered in-process and streams the latest complete
  page state over SSE.
- Crashes do not replay forms. Recovery reopens facts, marks interrupted work,
  and re-derives graphs.

## Requirements

| Tool | Purpose |
|---|---|
| JDK 26 | Runtime, builds, and tests |
| Clojure CLI 1.12+ | Source development and artifact builds |
| Babashka 1.x | Development utilities |
| Bun 1.3.14 | Reproducible Tailwind CSS builds only |

Set the credential selected by `config/default.edn`; the shipped DeepSeek
configuration reads `DEEPSEEK_API_KEY`. Credentials remain process
environment values and never become database facts.

## Run it

```bash
git clone https://github.com/seantempesta/seon
cd seon
export DEEPSEEK_API_KEY=sk-...
bin/seon init
bin/seon start
bin/seon status
bin/seon open default
```

`bin/seon` is the development operator. Use `--root PATH` for an isolated
deployment or destructive proof. Configuration is reconciled into database
facts from the shipped defaults plus an optional sparse EDN overlay:

```bash
bin/seon --root tmp/demo-root start demo
bin/seon --root tmp/demo-root status
bin/seon --root tmp/demo-root down
```

The complete correctness gate is:

```bash
bin/test
```

The web stylesheet is a separate build product. It is the only Node package
closure retained in this repository:

```bash
bun install --frozen-lockfile
bin/css
```

## Development

Fresh `src/` and `test/` are the system. `src-old/` and `test-old/` are a
disabled historical quarry and are absent from runtime and test classpaths.
Repository instructions and the active program ledger are:

1. [`AGENTS.md`](AGENTS.md)
2. [`docs/TRANSFER_PROMPT.md`](docs/TRANSFER_PROMPT.md)
3. [`docs/prds/sci-execution-runtime/plan/README.md`](docs/prds/sci-execution-runtime/plan/README.md)

The API is unstable while the fresh runtime is built out. Git preserves the
deleted systems; current source and documentation describe only the surviving
mechanisms.

## License

Released under [AGPL-3.0](LICENSE). Commercial or non-AGPL licensing is
available from Sean Tempesta Consulting LLC. Existing engagements with
separate written agreements operate under those agreements.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the inbound licensing terms.
