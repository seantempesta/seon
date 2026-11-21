# Seon

**A self-improving Clojure runtime where autonomous agents work in isolated Copy-on-Write OS processes on Datahike branches, prove changes by transitive reach and generative laws, and merge into one program that gets smaller, faster, and more certain as it grows.**

Built on [Jolt](https://github.com/jolt-lang/jolt) (Clojure compiled to native Chez Scheme binaries), [Datahike](https://github.com/replikativ/datahike) over LMDB, `clojure.core.async.flow`, [Malli](https://github.com/metosin/malli), and [Datastar](https://data-star.dev/).

---

## Why Seon

Most coding-agent setups bolt shell loops, Git worktrees, and ad-hoc test runners onto the outside of a repository. Seon turns that inside out — the codebase, docs, tasks, schemas, tests, telemetry, and UI all live as queryable immutable data inside one runtime:

- **Datahike branches instead of Git worktrees:** Every internal Seon agent and external coding session works on its own isolated Datahike branch in one shared LMDB store (`runs/store`). Branches export zero-worktree to `refs/heads/seon/<branch>` via `git fast-import`, and every green merge writes touched files back to local Git `main` atomically.
- **~6 ms Copy-on-Write agent & test processes:** A single-threaded `ForkParent` forks persistent agent REPLs and isolated test workers in milliseconds, with live `result/e<n>` handles and hard tick/byte budgets (`jolt.host/run-interruptible`).
- **One Malli schema authority:** 248 co-located multi-segment Malli schemas (`resources/seon/schemas.edn`) auto-derive Datahike attributes, enforce write-seam validation, attach UI/context renderers, and drive deterministic `seed = 42` generative checks automatically.
- **Single-source Living Canvas & agent context:** One `datahike.reads`-tracked reactive walk renders both the 120 fps Datastar Web UI (`http://127.0.0.1:9998/` + embedded macOS `WKWebView`) and each agent's KV-cache-friendly wire context (`86–96%` cache hit rates).
- **Transitive reach testing & zero-downtime self-evolution:** Unchanged tests reuse Merkle-digest green verdicts in `0 ms`. Normal merges hot-load live into the running server and `ForkParent`; merges touching boot inputs automatically run `bin/build seon` → `--self-test` → graceful drain → `--take-over`.
- **External coding agents use the exact same insides:** IDE and CLI coding agents connect via 5 native MCP tools (`seon_repl`, `seon_test`, `seon_merge`, `seon_health`, `seon_status`) through the thin stdio relay `bin/mcp`.
- **Clean boundary for external repos:** Datahike holds Seon's own code, docs, tasks, and schemas. External polyglot repositories (e.g., Harbor / Terminal-Bench Docker containers) are worked through `seon.fork.container` (`user/sh!`, `user/fs-read`, `user/fs-write`, `user/fs-patch`) — external project code never enters Datahike.

---

## Architecture at a Glance

```
                    ┌──────────────────────────────────────────┐
  Browser / WKWebView ◄── Datastar SSE (120 fps) ──┐           │
  IDE / CLI (MCP)     ◄── POST /mcp (bin/mcp)    ──┼── :web / :server (:9998)
  CLI (bin/seon)      ◄── POST /op  (EDN)        ──┘           │
                    └──────────────────┬───────────────────────┘
                                       │ static core.async.flow graph
          ┌──────────────┬─────────────┼─────────────┬──────────────┐
          ▼              ▼             ▼             ▼              ▼
     :transactor    :reconciler     :agents       :model         :test
   (sole :main     (pure O(|Δ|)   (warm COW    (streaming LLM  (reach-selected
    writer + 3-way  status)        worker pool) + KV cache)     fail-fast +
    merge + :git)        │             │                        seed=42 gen)
          │              └──────┬──────┘                             │
          ▼                     ▼                                    ▼
    Datahike LMDB ◄──── 1-thread COW ForkParent (~6 ms fork) ───────┘
    (:main + branches)
          │ atomic write-back on green merge
          ▼
      Local Git (main + refs/heads/seon/*)
```

---

## Quickstart

### Prerequisites

- C compiler & `make` (macOS: Xcode Command Line Tools)
- `git` and OpenSSL 3 (macOS: `brew install openssl@3`)
- `DEEPSEEK_API_KEY` (or compatible OpenAI-format endpoint) for live model turns
- `git clone --recurse-submodules` to fetch the public tracked fork & overlay submodules under `reference-code/`

### Clone, Build & Start

```sh
git clone --recurse-submodules https://github.com/seantempesta/seon.git
cd seon

# Build Chez -> Jolt -> LMDB/LZ4/zstd -> native target/seon (+ self-test) and target/seon-browser
nice -n 15 bin/build seon
bin/build browser

# Start the standing Seon server (http://127.0.0.1:9998/)
export DEEPSEEK_API_KEY=...
SEON_BINARY=1 bin/seon start
```

### Interact via CLI, Browser, or MCP

```sh
bin/seon status                      # Live :main tx, Git commit, branches, tasks, agents
bin/seon health                      # Pure derived health report
bin/seon test :main                  # Reach-selected fail-fast tests + seed=42 generative checks
bin/seon eval :branch/demo '(+ 1 2)' # Evaluate Clojure in a COW forked branch REPL
bin/seon merge :branch/demo          # 3-way Malli merge -> :main + local Git + hot-reload
bin/seon browser                     # Open embedded macOS WKWebView on http://127.0.0.1:9998/
```

For MCP-capable coding agents (already configured in `.mcp.json`):

```json
{
  "mcpServers": {
    "seon": {
      "type": "stdio",
      "command": "./bin/mcp",
      "args": []
    }
  }
}
```

---

## Measured Numbers

All measurements collected directly from the running system (`deepseek-flash`, macOS arm64):

- **Official Terminal-Bench 2.1 (Harbor `0.26.0`, Host-Bridge Mode):** **`7 / 9` genuine `1.0` passes (`77.8%`)** across `tb21-file-recovery` and `tb21-code-debug` (`87–96%` KV-cache hit rates, `0` exceptions, zero external code in Datahike).
- **Internal Parallel Seon Suite A (`seon.bench.cluster`):** **`3 / 3` auto-merged @ `10.3` mean evals** (`88.3%` KV cache; down from `22.7` mean evals and `-69%` tokens after Malli upfront validation and cold-boot overlay sync).
- **Incremental UI Walk (`seon.render/step-walk`):** **`0.02 ms/frame`** (`> 400x` headroom inside an `8.33 ms` 120 fps budget), with cache-transparent `datahike.reads` replay in `seon.store/lru-get!`.
- **Health & Test Suite:** **`186 / 186` green** (`seon_test :main`), `0` health errors, `0` oversized namespaces (`< 8,000` tokens each), `8` verified class fixes recorded in Datahike `:seon.fix/*`.

See [docs/bench.md](docs/bench.md) and [bench/harbor/README.md](bench/harbor/README.md) for how to run isolated parallel A/B trials and Harbor benchmarks.

---

## Documentation

Every document under `docs/` (plus `AGENTS.md`) is parsed losslessly into `:seon.code.doc/*` in Datahike so agents read and update docs directly from the REPL:

- **[docs/principles.md](docs/principles.md)** — The 7 founding principles and what enforces them.
- **[AGENTS.md](AGENTS.md)** — Standing rules for external coding-agent sessions and Seon agents alike.
- **[docs/README.md](docs/README.md)** — Complete documentation map.
- **[docs/architecture.md](docs/architecture.md)** — Static Flow graph, COW `ForkParent`, Malli → Datahike derivation, cold-boot sync, and Git write-back.
- **[docs/agent.md](docs/agent.md)** — Complete `user/*` REPL helper reference, `@seon.ctx/ctx`, `result/e<n>`, and `seon_*` MCP tools.
- **[docs/render.md](docs/render.md)** — 120 fps `datahike.reads` walk, Living Canvas & Chronicle, `#cv-bench-runs`, context tiles, and single-source wire assembly.
- **[docs/health.md](docs/health.md)** — Pure derived health rules, efficiency advisories, reach testing, and `:seon.fix/*` class-fix ledger.
- **[docs/bench.md](docs/bench.md)** — Parallel isolated Seon trials, no-rigging rules, Harbor Host-Bridge Mode, and baselines.
- **[docs/plan.md](docs/plan.md)** — Shipped phases and active roadmap aligned with `resources/seon/tasks.edn`.
- **[docs/decisions.md](docs/decisions.md)** — Owner rulings with dates and context.
- **[docs/forks.md](docs/forks.md)** — Tracked library forks under `reference-code/`.

---

## Repository Layout

| Path | What it holds |
|---|---|
| `src/seon/` | Platform runtime (Flow procs, COW `ForkParent`, Malli/Datahike store, CST `rewrite-clj` editor, health, Living Canvas UI, parallel benchmark cluster) |
| `test/seon/` | Reach-selected `clojure.test` suites + `seed = 42` generative specs |
| `resources/seon/` | Declarative Datahike ↔ Git state: `schemas.edn` (248 Malli schemas), `tasks.edn`, `agents/{root,examples}.edn`, `config/default.edn` |
| `docs/` | System wiki indexed losslessly into `:seon.code.doc/*` in Datahike |
| `bench/harbor/` | Thin Harbor `0.26.0` Host-Bridge adapter (`seon_agent.py`) for Terminal-Bench 2.1 / SWE-bench |
| `bin/` | Thin relays (`bin/seon`, `bin/mcp`) and native bootstrap (`bin/build`, `bin/env`, `bin/ports`) |
| `native/` | C / Objective-C sources (LMDB helpers, macOS `WKWebView` `target/seon-browser`) |
| `reference-code/` | Tracked library fork submodules (`jolt`, `chez`, `datahike`, `konserve`, `malli`, `persistent-sorted-set`, `lmdb`, `zstd`) |

Generated artifacts (`target/`, `runs/`, `config/local.edn`) are gitignored.
