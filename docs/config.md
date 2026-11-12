# Config

Every limit, bound and default of the platform is one key in one aero file, documented beside its value, validated by its schema, and read through one resolver.

## The file

`resources/seon/config/default.edn` holds every key with its unit, its provenance (a ruling or the commit holding the measurement) and what happens at its bound. Its schemas are ``resources/seon/schemas/seon.config*.edn``; `:seon.config/config` is a closed map, so an unknown key, an invalid value or a missing required key refuses boot with `:seon.config/refused`, naming each key. ```seon.config/config``` is the validated map this process loaded, built by ```seon.config/resolved``` (a nil value is absent) and checked against ```seon.config/keys-declared```.

- **Profiles.** `--profile NAME` (default `:default`) is handed to aero's `#profile` (```seon.config/profile```) and kept as `:seon.config.root/profile`, a layer the loader supplies.
- **Machine values are derived at load:** `#seon/checkout "p"` is `p` under the checkout; `#seon/cores nil` is the core count (`:seon.config.machine/cores`); `#seon/own-branch "b"` is `b` in the main tree and `b-<worktree>` in a worktree, since worktrees share refs (`:seon.config.source/branch`).
- **No key decides by time** ([clocks](clocks.md)): the stall bounds (`:seon.config.agent/stall-ms`, `:seon.config.proofs/stall-factor`) only say when a stall is recorded.

## Layers

Later layers win, key by key, and the merged map is validated once (```seon.config/load-layers``` reads them, ```seon.config/layered``` merges them):

1. the shipped file, as built (```seon.config/built-text```) and then as this process's branch states it ([below](#a-process-holds-its-branchs-config)), under `--profile`;
2. the checkout's `config/local.edn` (gitignored), only where a command line names it with `--config`: `bin/system` passes it to the standing root, and `bin/ports` to `seon ports`. `bin/ports` owns the port keys there, from the main tree's `config/worktree-ports` (main 7888/7777, worktree k 7900+10k/7901+10k), and `bin/build`, `bin/test` and `bin/check` run `bin/ports --write` first;
3. the command line: `--profile NAME`, `--config FILE` and `--set :ns/key=edn` (```seon.config/overrides```), read from `*command-line-args*`. `bin/test`, `bin/check` and a test's root (`seon.test-store/start-root!`) pass `--profile scripted`; `bin/system start --profile deepseek` runs agents on DeepSeek, and `my.root/resume-args` carries the profile to `bin/system next`.

```seon.config/sources``` names the layer of every key. No port is listened on unless config says so: `:seon.config.web/port` and `:seon.config.root/server-port` ship 0.

**A value one run needs goes on that run's command line, never in the environment**: `--set` is the top layer and always wins, while an `#env` default is overridden by a stale `config/local.edn`.

## A process holds its branch's config

Which layer a key came from is not part of what a process reads (```seon.config/in-force```); root's boot writes it on main (```seon.config/boot-sources```, `:seon.config/sources`).

A running process reads config from its own branch's db, never from the file: a file an agent writes acts on nothing until an import.

- **At load** a process has no db, so it reads ```seon.config/built-text```, the file's text baked when `seon.config` compiles, under its profile, `#env`, checkout, `--config` and `--set`.
- **At start**, once on a branch, it holds what that branch's entries state (```seon.program/hold-config!```, ```seon.config/hold!```): root right after loading main's program (```seon.system/boot!```), before the fork server forks, so every child inherits it; a resumed agent from its branch (```seon.program/load-diff!```). The entries (`:config/key`, `:config/form`, the value's exact text, [source](source.md#a-program-file)) are read as aero reads the file (```seon.config/of-entries```). A db with no entries changes nothing.
- **Every read** takes that held map: ```seon.config/resolve```, ```seon.config/value```, ```seon.config/resolve-in```. Nothing pulls per read.
- **It moves** only when a transaction of its own branch names an entry: a merge loading in place (```seon.program/load-changes!```) or the agent's own write (`seon.agent/transact!`).
- **The source is the process's own branch**, never another db: a test's ``(my.test/world)`` holding entries changes nothing.
- **A place stays where it loaded.** A key whose schema says `:seon.config/place` is read before any db or says where this process runs (the store: `:seon.config.root/runs-dir`, `store-path`, `main`, `:seon.config.store/sole-writer?`; the import: `:seon.config.source/root`, `dirs`, `branch`), so an entry for it waits for a rebuild (```seon.fx/config-input```).
- **A branch's schema counts.** A key a branch adds validates against that branch's schema entries, so ``(my.schema/register! k form)`` then an edit of the file reads at that branch's REPL only; the new entry is `{:optional true}` while the binary lacks it. A write of entries the schema refuses is refused before it lands (```seon.program/config-refused```).

The file as the program holds it: ```seon.config/shipped-text```. A branch's values in test digests: ```seon.agent/branch-inputs``` ([merging](merging.md#verdicts-are-a-cache)).

### What waits for a rebuild

A top-level `def` reads config once, at load, and a root's start reads its `:bind/*` buffers and limits once, so a value a branch states doesn't reach them. Each such key says `:seon.config/at-load true` on its schema entry (`seon.main-test/a-key-read-at-load-says-so`). ```seon.config/waiting``` answers each place or at-load key whose stated value differs from what this process loaded, `{k {:running :stated}}`; the dashboard's config panel names them. It may overstate after a restart, never hide.

## The resolver

```seon.config/resolve``` is the one way code reads a setting: `(seon.config/resolve k {:task m :model id})` looks in the task's or agent's own settings (`:seon.task/config`), the model's entry (default `:seon.config.ai/model`), its provider's, then the config (```seon.config/resolve-in```), and answers `{:value v :source s}`; a key set nowhere is an error naming the key, task, model and provider. ```seon.config/value``` is its value. A task's settings may name its model, `:seon.config.ai/provider` and `:seon.config.ai/script` (```seon.config/provider-at``` reads them each turn), so a test's scripted reply rides its task, not root's arguments.

## The model manifest

`:seon.config.ai/providers` and `:seon.config.ai/models` are v1's manifest entries, each field documented beside its value. A provider carries its base URL, its key's environment variable name (never the key) and its wire key; a model its provider, version, context window, max output, prices and concurrency ceiling. Either may override a `:seon.config.*` key for itself. ```seon.config/provider``` builds a model's provider, ```seon.config/with-provider``` attaches it. Which provider runs: [model](model.md).

## Facts on main

At boot root writes the config in force on main as its own entity, named by its content id (`:seon.config/id`, ```seon.task/config-tx```), with an entity per provider and model, and root's entity refs it (`:seon.system/config`) (`seon.config-test/the-config-in-force-is-its-own-entity`). When a commit of main touches an entry, root's writer writes the config as now held (```seon.config/moved```), so ```seon.config/provider-at``` reads what merged. A merge never carries these. ```seon.config/from-db``` reads it back on main or any branch from it; the dashboard shows it through `seon.render.ui/config-panel` ([dashboard](dashboard.md)). `bin/check config` proves an agent reads it from its branch.

## A bound fails loudly

A limit is removed, or derived from measurements with its provenance, and reaching it is a typed error or a problem that names the key: never a silent cut, drop or clamp ([decisions](decisions.md#no-artificial-limits-or-cuts-on-an-agents-context-or-evals-owner-2026-10-29)). Before adding a bound, measure the population it bounds and say what happens at it and who sees it. A budget is right when work stays under it nine times in ten and an overrun is worth looking at. Nothing cuts an agent's own context or evals.

## The test service's three limits

How much the test service runs at once is three optional keys, and any combination of them has an answer. ```seon.prove/admission``` turns them into one count and, when set, a load; ```seon.prove/config-admission``` reads them. Items and the queue: [the test service](merging.md#the-test-service).

- `:seon.config.proofs/max-workers`: items running at once.
- `:seon.config.proofs/max-cores`: a share of `:seon.config.machine/cores` in (0,1], rounded down, at least 1.
- `:seon.config.proofs/max-load`: the 1-minute load average under which a further item is granted.

The count is the least of the count keys set, else the core count. Beside them, one bound is derived, never a key: the processes the running items hold stay under the cores and two (`:max-processes`, AGENTS.md §3a.7), counted from the machine by ```seon.prove/held-processes``` ([the test service](merging.md#the-test-service)). The load is read only in a step the service already takes, never on a timer ([the machine's load admits work](clocks.md#the-machines-load-admits-work)), and never blocks the first item. These are signals: they decide when an item starts, never a verdict. The shipped file sets `max-workers` only; set the others, or `:seon.config.machine/cores`, to the machine in `config/local.edn`.

## Constants

Every limit, bound or default is a config key. A number left in src is one of the kinds below, each named here with its reason, and `seon.main-test/a-bound-is-a-config-key` is red on any other. It reads src's forms from the compiler's facts and flags every top-level `def` of a number and every literal count over 2 given to `take`, `take-last`, `drop` or `drop-last`; numbers elsewhere (`subs`, `nth`, a `let`, a comparison) are for review. A foreign interface's constant, named in upper case as its C header names it (`seon.sock/AF-UNIX`, `seon.db/MDB_NOSYNC`), is not a bound.

- `seon.cost/tick-allowance`, `seon.cost/steeper-by`, `seon.cost/ratchet`, `seon.cost/growth-share`, `seon.cost/increment-floor`: the cost judge's tolerances, which task `seon.cost/cost-tolerances-are-derived` derives instead ([cost](cost.md)).
- `seon.cost/min-points`: three, the fewest sizes a cost proof measures, since two points always make a line.
- `seon.program/form-ids`: `(drop 3 form)` drops a `defn`'s head, name and docstring.
- `seon.program/place-gap`: the space between a file's forms in the sort key.
- `seon.cron/day-ms`: a day in milliseconds.
- `seon.server/verdict-line`: the ten reds `seon_done`'s line names; it counts the rest.
- `seon.fx/runner-end`: the six lines of runner output an error names beside its log path.
