# Seon on Jolt

Seon is a self-improving Clojure runtime on [Jolt](https://github.com/jolt-lang/jolt) (Clojure on Chez Scheme) where autonomous agents work in isolated Copy-on-Write OS processes on Datahike branches, prove changes by transitive reach and `seed = 42` Malli generative checks, and merge into `:main` with zero outside infrastructure.

- **Active branch:** `jolt/rebuild` (in `.worktrees/rebuild`; `main` stays clean until promotion).
- **Standing server:** `http://127.0.0.1:9998/` (Living Canvas UI + `/op` EDN API + `/mcp` JSON-RPC 2.0 endpoint).
- **Number-one metric:** Fewest turns for an agent to take a roughly specified `:seon.task/*` to proven, merged code ([AGENTS.md](AGENTS.md)).

## Start Here

- **Working here (external session or Seon agent):** Read [docs/principles.md](docs/principles.md), then [AGENTS.md](AGENTS.md), then the documentation map [docs/README.md](docs/README.md).
- **Roadmap & rulings:** [docs/plan.md](docs/plan.md) and [docs/decisions.md](docs/decisions.md).
- **Benchmarks & Harbor integration:** [docs/bench.md](docs/bench.md) and [bench/harbor/README.md](bench/harbor/README.md).

## Build & Run

```sh
git clone --recurse-submodules https://github.com/seantempesta/seon
cd seon
nice -n 15 bin/build seon   # Chez -> Jolt -> LMDB/LZ4/zstd -> target/seon (and target/seon-browser)
bin/seon start              # Start standing server on http://127.0.0.1:9998/
bin/seon status             # Live snapshot of :main tx, Git commit, branches, tasks, agents
bin/seon health             # Pure derived health report
bin/seon test :main         # Reach-selected fail-fast tests + seed=42 Malli generative checks
```

**Prerequisites:** C compiler & `make` (macOS Xcode Command Line Tools), `git`, OpenSSL 3 (Homebrew `openssl@3`), and `DEEPSEEK_API_KEY` (or compatible OpenAI-format endpoint) for live model turns.

## Repository Layout

| Path | What it holds |
|---|---|
| `src/seon/` | Platform runtime (static Flow procs, COW `ForkParent`, Malli/Datahike store, CST editor, health, Living Canvas UI, parallel benchmark cluster) |
| `test/seon/` | Fast `clojure.test` suites + `seed = 42` generative specs |
| `resources/seon/` | Declarative Datahike <-> Git files: `schemas.edn` (248 Malli schemas), `tasks.edn`, `agents/{root,examples}.edn`, `config/default.edn` |
| `docs/` | System wiki, indexed losslessly into `:seon.code.doc/*` in Datahike ([docs/README.md](docs/README.md)) |
| `bench/harbor/` | Thin Harbor `0.26.0` Host-Bridge adapter (`seon_agent.py`) for Terminal-Bench 2.1 / SWE-bench |
| `bin/` | Thin relays (`bin/seon`, `bin/mcp`) and native bootstrap (`bin/build`, `bin/env`, `bin/ports`, temporary `bin/worktree`) |
| `native/` | C / Objective-C sources (LMDB helpers, macOS `WKWebView` `target/seon-browser`) |
| `reference-code/` | Tracked library fork submodules ([docs/forks.md](docs/forks.md)) |

Generated state (`target/`, `runs/`, `config/local.edn`) is gitignored.
