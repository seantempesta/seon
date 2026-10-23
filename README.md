# Seon on Jolt

Seon is where many agents do real work for their user, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This is the version that runs on [Jolt](https://github.com/jolt-lang/jolt) (Clojure on Chez Scheme): Datahike for the store, every component a core.async Flow proc, every agent a forked process with a real REPL.

## Start here

- **Working here, as a person or an agent:** [AGENTS.md](AGENTS.md) is how work is handed out and done, and the laws. Then the wiki's map, [docs/README.md](docs/README.md): one page a concept, each section addressed `page#section` and citable from any task.
- **What is in flight:** [docs/plan.md](docs/plan.md) (the plan and what is running). **What was ruled:** [docs/decisions.md](docs/decisions.md).
- **What v1 did and where this stands:** [docs/parity.md](docs/parity.md); what was left behind on purpose: [BAGGAGE.md](BAGGAGE.md).

## Clone and build

```sh
git clone --recurse-submodules https://github.com/seantempesta/seon-next   # or the folder you were given
cd seon-next
nice -n 15 bin/build     # Chez (our fork) -> Jolt (our fork) -> LMDB, LZ4, zstd -> target/seon; all output in target/
bin/root start           # a root on this checkout's own ports (bin/ports); bin/root status | stop
bin/prove                # run exactly the tests and checks the change reaches
```

**Prerequisites** (not installed by the build): a C compiler and make (macOS: the Xcode command line tools), git, network access to GitHub and Maven Central on the first build, and OpenSSL 3 at run time (Homebrew `openssl@3` on macOS). The fork submodules are on GitHub; two of them (`persistent-sorted-set`, `jolt-overlay`) are private, so the clone needs your GitHub login (`gh auth login`).

## Layout

| Path | What it is |
|---|---|
| `src/`, `resources/`, `web/` | the platform: the root, the agents, the store, the reconciler, the printer and the dashboard; `resources/seon/tasks/` is the work itself, as task files |
| `test/` | the deftests (`bin/test NS`); `bin/check MODE` is the end-to-end suite |
| `bin/` | `build`, `root`, `prove`, `test`, `check`, `worktree`, `ports`, `laws`, `mcp`, `agy-agent` |
| `docs/` | the wiki, and the only place documentation lives ([how it is kept](docs/README.md#how-the-wiki-is-kept)) |
| `reference-code/` | our forks of Chez, Jolt, Datahike, Konserve, Malli and others, as submodules ([forks](docs/forks.md)), and upstream sources we read |
| `native/` | C for the native libraries |
| `.worktrees/` | a worktree per piece of work, each on its own branch with its own root and stores (`bin/worktree NAME`; gitignored) |

Everything else (`target/`, `runs/`, `test-results/`, `config/local.edn`) is a checkout's own state and is gitignored.
