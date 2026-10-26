# Config

Every limit, bound and default of the platform is one key in one aero file, documented beside its value, validated by its schema, and read through one resolver.

## The file

`resources/seon/config/default.edn` holds every key with its unit, its provenance (a ruling or a measurement) and what happens at its bound, in v1's shape. Its schemas are ``resources/seon/schemas/seon.config*.edn``; `:seon.config/config` is the whole config, a closed map, so an unknown key, an invalid value or a missing required key refuses boot with `:seon.config/refused`, naming each key. ```seon.config/config``` is the loaded, validated map, built by ```seon.config/resolved``` (a key whose value is nil is dropped as absent, then the map is validated) and checked against ```seon.config/keys-declared```. A key may differ by the process's profile (`SEON_PROFILE`, default `:default`, handed to aero's `#profile`: `:seon.config.store/sole-writer?` is false under `:scripted`). Machine-dependent values are derived at load: `#seon/checkout "p"` is `p` under the checkout (`SEON_CHECKOUT`, else `JOLT_PWD`). No key decides by time ([clocks](clocks.md)): the stall bounds (`:seon.config.agent/stall-ms`, `:seon.config.proofs/stall-factor`) only say when a stall is recorded, and the stop bound is a guard in its owner, not a key.

## Layers

Later layers win, key by key, and the merged map is validated once (```seon.config/load-layers``` reads them, ```seon.config/layered``` merges them):

1. the shipped file;
2. the checkout's own `config/local.edn` (gitignored), a layer only where a command line names it with `--config`: `bin/root` passes it to the standing root (a test's root, started with a starter, takes none) and `bin/ports` to `seon ports`, so a check's or a test's process reads only the config it is given (a check's root in the main tree once read the standing root's source tree from it). `bin/ports` writes it with the checkout's ports from the main tree's `config/worktree-ports` (gitignored; main 7888/7777, worktree k 7900+10k/7901+10k; `bin/worktree --remove` frees the block); `bin/ports` owns the port keys and writes them on
   every run under their current names, keeping the ports and every other entry, and
   `bin/build`, `bin/test` and `bin/check` run `bin/ports --write` first;
3. the command line: `--config FILE` and `--set :ns/key=edn` (```seon.config/overrides```), read from `*command-line-args*` while namespaces load (Jolt's jolt/argv binds it in a built binary), taken out of the mode's own args.

```seon.config/sources``` names the layer of every key (the `:seon.config/sources` fact on main). `#env` appears only where a script sets one. No port is listened on unless the config says so: `:seon.config.web/port` and `:seon.config.root/server-port` ship 0.

**A value one run needs goes on that run's command line, never in the environment**: the standing root's `config/local.edn` is a later layer than the shipped file's `#env` defaults, so a port handed to a client through `SEON_SERVER_PORT` is overridden by a stale local file, and the client config keys a port nothing listens on. `--set :seon.config.root/server-port=N` is the top layer and always wins. Not this: a new `#env` read for a per-run value.

## The resolver

```seon.config/resolve``` is the one way code reads a setting: `(seon.config/resolve k ctx)`, with ctx `{:task m :model id}`, looks in the task's or agent's own settings (`:seon.task/config`), the model's entry (default `:seon.config.ai/model`), that model's provider's, then the config's own key (```seon.config/resolve-in```). It answers `{:value v :source s}`, where `s` is `[:task]`, `[:model id]`, `[:provider id]` or `[:config layer]`; a key set nowhere refuses with an error that names the key, the task, the model and its provider and points to `seon.config/keys-declared`. ```seon.config/value``` is its value, the read every caller makes. Ask it at the [server](server.md#ops): `(seon.config/resolve :seon.config.ai/max-in-flight {:model "deepseek-v4-pro"})`.

## The model manifest

`:seon.config.ai/providers` and `:seon.config.ai/models` are v1's manifest rows, for the models we use; each field is documented beside its value in the file. A provider carries its base URL, the name of its key's environment variable (never the key) and its wire key; a model its provider, version, context window, max output, prices and the provider's concurrency ceiling. Either can state a setting by its `:seon.config.*` key to override the top-level one for itself. ```seon.config/provider``` builds a model's provider and ```seon.config/with-provider``` attaches it. Which provider runs: [model](model.md).

## Facts on main

At boot root writes the config in force on main's root entity, with its sources, and an entity per provider and per model (```seon.config/facts```). These are platform records, which a merge never carries. ```seon.config/from-db``` reads the config back on main or on any branch made from it (every declared key the root entity holds, a set-valued key read back as the set it was), so an agent, the dashboard and the context read it there; the dashboard shows it through the schema's declared render, `seon.render.ui/config-panel` ([dashboard](dashboard.md)). `bin/check config` proves that an agent reads it from its branch.

## Constants

Every limit, bound or default is a config key (AGENTS §5). A number left in src is one of the kinds below, each named here with its reason, and `seon.main-test/a-bound-is-a-config-key` is red on any other. It reads src's forms from the compiler's facts and flags every top-level `def` of a number and every literal count over 2 given to `take`, `take-last`, `drop` or `drop-last`. Not covered, so a review reads for them: a number in `subs`, `repeat`, `partition`, `nth`, a `let`, a comparison or a `def` wrapped in a call. A foreign interface's constant, named in upper case as its C header names it (a socket flag such as `seon.sock/AF-UNIX`, LMDB's `seon.db/MDB_NOSYNC`, the page size `seon.lib/PAGE`), is the interface's value, not a bound, and is not listed; the case is trusted, so a bound written in upper case escapes the law, and review reads for it.

- `seon.agent-proc/stop-bound-ms`: a clock standing in for an exit, which its task deletes rather than makes config (`seon.root/shutdown-and-death-are-dataflow`; [clocks](clocks.md)). The stall bound is a config key, `:seon.config.agent/stall-ms`: it decides nothing, only when a stall is recorded (task `seon.root/a-stall-is-past-the-requests-own-bound` replaces it with each request's own bound).
- `seon.cost/tick-allowance`, `seon.cost/steeper-by`, `seon.cost/ratchet`, `seon.cost/growth-share`, `seon.cost/repeats`: the cost judge's own definitions of a proof's tolerance, which task `seon.cost/cost-tolerances-are-derived` derives from measurement instead ([cost](cost.md)).
- `seon.error/digest`: its 8 bytes are the 16 hex digits its contract and `:seon.error/signature`'s schema state, the identity's width, not a bound.
