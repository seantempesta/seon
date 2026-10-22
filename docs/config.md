# Config

Every limit, bound and default of the platform is one key in one aero file, documented beside its value, validated by its schema, and read through one resolver.

## The file

`resources/seon/config/default.edn` holds every key with its unit, its provenance (a ruling or a measurement) and what happens at its bound, in v1's shape. Its schemas are ``resources/seon/schemas/seon.config*.edn``; `:seon.config/config` is the whole config, a closed map, so an unknown key, an invalid value or a missing required key refuses boot with `:seon.config/refused`, naming each key. ```seon.config/config``` is the loaded, validated map, built by ```seon.config/resolved``` and checked against ```seon.config/keys-declared```. Machine-dependent values are derived at load: `#seon/checkout "p"` is `p` under the checkout (`SEON_CHECKOUT`, else `JOLT_PWD`). No key is a clock ([clocks](clocks.md)); the stall silence and the stop bound are guards in their owners, not keys.

## Layers

Later layers win, key by key, and the merged map is validated once (```seon.config/load-layers``` reads them, ```seon.config/layered``` merges them):

1. the shipped file;
2. the checkout's own `config/local.edn` (gitignored), which `bin/ports` writes with the checkout's ports from ``../worktree-ports`` (main 7888/7777, worktree k 7900+10k/7901+10k; `bin/worktree --remove` frees the block);
3. the command line: `--config FILE` and `--set :ns/key=edn` (```seon.config/overrides```), read from `*command-line-args*` while namespaces load (Jolt's jolt/argv binds it in a built binary), taken out of the mode's own args.

```seon.config/sources``` names the layer of every key (the `:seon.config/sources` fact on main). `#env` appears only where a script sets one. No port is listened on unless the config says so: `:seon.config.web/port` and `:seon.config.root/entrance-port` ship 0.

**A value one run needs goes on that run's command line, never in the environment**: the checkout's `config/local.edn` is a later layer than the shipped file's `#env` defaults, so a port handed to a client through `SEON_ENTRANCE_PORT` is overridden by a stale local file, and the client dials a port nothing listens on. `--set :seon.config.root/entrance-port=N` is the top layer and always wins. Not this: a new `#env` read for a per-run value.

## The resolver

```seon.config/resolve``` is the one way code reads a setting: `(seon.config/resolve k ctx)`, with ctx `{:task m :model id}`, looks in the task's or agent's own settings (`:seon.task/config`), the model's entry (default `:seon.config.ai/model`), that model's provider's, then the config's own key (```seon.config/resolve-in```). It answers `{:value v :source s}`, where `s` is `[:task]`, `[:model id]`, `[:provider id]` or `[:config layer]`; a key set nowhere refuses. ```seon.config/value``` is its value, the read every caller makes. Ask it at the [server](server.md#ops): `(seon.config/resolve :seon.config.ai/max-in-flight {:model "deepseek-v4-pro"})`.

## The model manifest

`:seon.config.ai/providers` and `:seon.config.ai/models` are v1's manifest rows, for the models we use; each field is documented beside its value in the file. A provider carries its base URL, the name of its key's environment variable (never the key) and its wire key; a model its provider, version, context window, max output, prices and the provider's concurrency ceiling. Either can state a setting by its `:seon.config.*` key to override the top-level one for itself. ```seon.config/provider``` builds a model's provider and ```seon.config/with-provider``` attaches it. Which provider runs: [model](model.md).

## Facts on main

At boot root writes the config in force on main's root entity, with its sources, and an entity per provider and per model (```seon.config/facts```). These are platform records, which a merge never carries. ```seon.config/from-db``` reads the config back on main or on any branch made from it, so an agent, the dashboard and the context read it there; the dashboard shows it through the schema's declared render, `seon.render.ui/config-panel` ([dashboard](dashboard.md)). `bin/check config` proves that an agent reads it from its branch.
