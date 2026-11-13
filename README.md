# Seon on Jolt

Seon is where many agents do real work for their user, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This is the version that runs on [Jolt](https://github.com/jolt-lang/jolt) (Clojure on Chez Scheme): Datahike for the store, every component a core.async Flow proc, every agent a forked process with a real REPL.

> **Status, November 2026: mid-refinement.** Seon on Jolt runs end to end (agents fork, work their branches, prove and
> merge), but it grew fast and is now being refined after that growth: duplicated mechanisms are being collapsed into
> one each, the test system is being folded into the runtime (tests as evals in warm forks, results reused by what they
> read), every effect and wait is moving onto Flow, and the data model is being tightened around named malli schemas.
> Expect breaking changes, a store that may be erased, and docs that lag the code in places. The measure everything is
> judged by: the fewest turns for an agent to take a roughly specified task to merged code
> ([AGENTS.md §1](AGENTS.md#1-what-seon-is-for-and-what-moving-forward-means)).

## Start here

Three places to start, by what you are here to do:

- **Working here, as a person or an agent:** [AGENTS.md](AGENTS.md) is how work is handed out and done, and the laws. Then the wiki's map, [docs/README.md](docs/README.md): one page a concept, each section addressed `page#section` and citable from any task.
- **What is in flight:** [docs/plan.md](docs/plan.md) (the plan and what is running). **What was ruled:** [docs/decisions.md](docs/decisions.md).
- **What v1 did and where this stands:** [docs/parity.md](docs/parity.md); what was left behind on purpose: [BAGGAGE.md](BAGGAGE.md).

## Clone and build

Clone with the submodules and build once; every checkout builds its own binary.

```sh
git clone --recurse-submodules https://github.com/seantempesta/seon
cd seon
nice -n 15 bin/build     # Chez (our fork) -> Jolt (our fork) -> LMDB, LZ4, zstd -> target/seon; all output in target/
bin/system start           # a root on this checkout's own ports (bin/ports); bin/system status | stop
bin/prove                # run exactly the tests and checks the change reaches
```

**Prerequisites** (not installed by the build): a C compiler and make (macOS: the Xcode command line tools), git, network access to GitHub and Maven Central on the first build, and OpenSSL 3 at run time (Homebrew `openssl@3` on macOS). The fork submodules are on GitHub; two of them (`persistent-sorted-set`, `jolt-overlay`) are private, so the clone needs your GitHub login (`gh auth login`).

## Layout

Where things live in the repository:

| Path | What it is |
|---|---|
| `src/`, `resources/`, `web/` | the platform: the root, the agents, the store, the reconciler, the printer and the dashboard; `resources/seon/tasks/` is the work itself, as task files |
| `test/` | the deftests (`bin/test NS`); `bin/check MODE` is the end-to-end suite |
| `bin/` | `build`, `root`, `prove`, `test`, `check`, `worktree`, `ports`, `laws`, `mcp` |
| `docs/` | the wiki, and the only place documentation lives ([how it is kept](docs/README.md#how-the-wiki-is-kept)) |
| `reference-code/` | our forks of Chez, Jolt, Datahike, Konserve, Malli and others, as submodules ([forks](docs/forks.md)), and upstream sources we read |
| `native/` | C for the native libraries |
| `.worktrees/` | a worktree per Claude session today, each on its own branch (`bin/worktree NAME`; gitignored); being replaced by sessions working as agents on branches of the one store |

Everything else (`target/`, `runs/`, `test-results/`, `config/local.edn`) is a checkout's own state and is gitignored.
