# Config

Owner, 2026-10-21: "the only things that should stay are documented config schemas". Every
limit, bound and default of the platform is one key in one aero file, documented beside
its value, validated by its schema, and read through one resolver.

## The file

`resources/seon/config/default.edn` holds every key with its unit, its provenance (an owner
ruling, a measurement, a worktree session) and what happens at its bound, in v1's shape. Its schemas are
`resources/seon/schemas/seon.config*.edn`; `:seon.config/config` is the whole config, a closed
map, so an unknown key, an invalid value or a missing required key refuses boot with
`:seon.config/refused`, naming each key. Machine-dependent values are derived at load:
`#seon/checkout "p"` is `p` under the checkout (`SEON_CHECKOUT`, else `JOLT_PWD`). No key is a
clock (owner: no clocks or timers decide anything); the stall silence and
the stop bound are guards in their owners, not keys.

The file is `resources/seon/config/default.edn`, its schema `:seon.config/config` (in `resources/seon/schemas/seon.config.edn`); ```seon.config/config``` is the loaded, validated map, built by ```seon.config/resolved``` and checked against ```seon.config/keys-declared```.

## Layers

Later layers win, key by key, and the merged map is validated once:

1. the shipped file;
2. the checkout's own `config/local.edn` (gitignored), which `bin/ports` writes with the
   checkout's ports from `../worktree-ports` (main 7888/7777, worktree k 7900+10k/7901+10k;
   `bin/worktree --remove` frees the block);
3. the command line: `--config FILE` and `--set :ns/key=edn`, read from `*command-line-args*`
   while namespaces load (Jolt's jolt/argv binds it in a built binary), taken out of the
   mode's own args.

`#env` appears only where a script sets one. `seon.config/sources` (and the
`:seon.config/sources` fact) names the layer of every key. No port is listened on unless the
config says so: `:seon.config.web/port` and `:seon.config.root/entrance-port` ship 0.

```seon.config/load-layers``` reads the layers and ```seon.config/layered``` merges them; ```seon.config/overrides``` reads the command line; ```seon.config/sources``` names each key's layer (`:seon.config/sources` on main). `bin/ports` and `bin/worktree` write and free a checkout's layer.

**A value one run needs goes on that run's command line, never in the environment** (the merge of CONFIG, 2026-10-21): the checkout's `config/local.edn` is a later layer than the shipped file's `#env` defaults, so a check that handed its MCP client a port through `SEON_ENTRANCE_PORT` was overridden by a stale local file and dialled a port nothing listened on. `--set :seon.config.root/entrance-port=N` is the top layer and always wins. Not this: a new `#env` read for a per-run value.

## The resolver

`(seon.config/resolve k ctx)` is the one way code reads a setting, with ctx `{:task m :model
id}`. Order: the task's or agent's own settings (`:seon.task/config`), the model's entry
(default `:seon.config.ai/model`), that model's provider's, then the config's own key. It
answers `{:value v :source s}`, where `s` is `[:task]`, `[:model id]`, `[:provider id]` or
`[:config layer]`. A key set nowhere refuses. `(seon.config/value k ctx)` is its value. Ask it
at the server: `(seon.config/resolve :seon.config.ai/max-in-flight {:model "deepseek-v4-pro"})`.

```seon.config/resolve``` answers `{:value :source}` for a key in a context, through ```seon.config/resolve-in```; ```seon.config/value``` is the read every caller makes.

## The model manifest

`:seon.config.ai/providers` and `:seon.config.ai/models` (v1's manifest rows, for the models we
use: deepseek-flash and deepseek-v4-pro). A provider carries its base URL, the name of its
key's environment variable (never the key) and its wire key. A model carries its provider,
version, context window, max output, off-peak prices (USD per M tokens; peak is twice) and
the provider's documented concurrency ceiling, with where and when they were read
(api-docs.deepseek.com pricing and rate_limit, 2026-10-21). Either can state a setting by its
`:seon.config.*` key to override the top-level one for itself; our cap is
`:seon.config.ai/max-in-flight` (8).

The manifest is `:seon.config.ai/providers` and `:seon.config.ai/models` (each model's `:seon.ai.model/id` and `:seon.ai.model/provider-id`); ```seon.config/provider``` builds a model's provider and ```seon.config/with-provider``` attaches it.

## Facts on main

At boot, root writes the config in force on main's root entity (with its sources) and an
entity per provider and per model. These are platform records, which a merge never carries.
`(seon.config/from-db db)` reads the config back on main or on any branch made from it, so an
agent, the dashboard and the context read it there. The dashboard shows it through the
schema's declared render, `seon.render.ui/config-panel`. `bin/check config` proves that an
agent reads it from its branch.

```seon.config/facts``` writes the config on main; ```seon.config/from-db``` reads it back; `seon.render.ui/config-panel` shows it on the dashboard.
