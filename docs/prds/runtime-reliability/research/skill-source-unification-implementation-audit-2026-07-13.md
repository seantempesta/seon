---
type: research
status: completed
tags: [research, agent, database]
---

# Skill source unification implementation audit

## TL;DR

The current skill system has two separate defects that reinforce each other:

- runtime import stores a path and rereads `SKILL.md` from the filesystem on
  every render, so the database is not the source of truth; and
- `.agents/skills`, `.claude/skills`, and `seon-skills` are three
  hand-maintained trees whose copied prose and APIs have drifted.

The smallest durable replacement is one importer and one projection compiler:

1. A skill entity stores `:my.skills/name`, `:my.skills/description`, and the
   exact validated `SKILL.md` text in `:my.skills/source`. The render body is a
   pure projection of `:my.skills/source`; there is no file-path fallback,
   stored loaded flag, digest, owner attribute, or second inline-body path.
2. Directory import, operator import, and future upload all read source text and
   call the same pure source-to-fact compiler. Invalid or duplicate input aborts
   the whole requested import instead of being silently skipped.
3. An omitted skills config performs no skill transition. A present skills
   config is authoritative for the whole skill registry and reconciles its exact
   desired population. This makes deletion well-defined without persisting an
   ownership/management projection. An explicit empty registry clears it; an
   equal registry writes no transaction.
4. `seon-skills` remains the checked-in authority for shipped runtime skills.
   `.agents/skills` and `.claude/skills` become committed, generated adapter
   views. Common material has one source; genuinely provider-specific browser
   operations are small explicit overlays, not forked full skill bodies.
5. Default, test, and root context trees continue to contain no skills catalog
   and no loaded skill. Importing is availability, not prompt injection.

This test database is resettable, so no compatibility migration is justified.
Implement the replacement atomically, reset the default cluster, and remove the
old schema/data paths rather than carrying both.

## Scope and evidence

This audit read the current runtime path, all three checked-in skill trees, the
runtime-reliability PRD, the active architecture, the historical C49 decision,
the downstream Inspect AI and Docker consumers, and Datahike's transaction and
schema implementation. It did not modify production or test code.

The current checked-in populations are materially different:

| Tree | Current role | Population | Approximate source size |
|---|---|---:|---:|
| `seon-skills` | runtime import corpus | 6 skills plus 3 references | 26,716 tokens |
| `.agents/skills` | Codex development discovery | 10 skills plus references | 37,552 tokens |
| `.claude/skills` | Claude development discovery | 10 skills plus references | 38,700 tokens |

They are ordinary directories and files, not links or generated outputs. Hash
comparison found only a minority of corresponding files identical. The
differences are not all intentional audience specialization:

- `.claude/skills/ui-live-tiles` teaches deleted
  `:seon.render.live-tile/content` and `my.tile` APIs.
- the Claude Datastar reference still names `seon.ui.world`;
- the Claude config skill still teaches a default `[:repl]` loadout,
  `:live-tile`, and a `.claude/skills` fallback;
- the two development ClojureScript skills contradict each other about
  `in-ns`, while the Codex REPL skill omits the current `#code` reader path;
- current development and runtime skills still teach `db/store-inventory`,
  which this PRD removes; and
- the Codex browser skill is copied from the Claude tool workflow and names
  Claude-specific browser operations.

Historical commit `68d73395` deliberately replaced Claude symlinks with copies
because runtime agents and repository-development agents are different
audiences. Commit `38cc5057` then added the Codex mirror. That audience decision
was reasonable; whole-body hand copies were not. C49 already records the
resulting drift. Generated adapters preserve real audience differences without
preserving three authorities.

### Live database proof

A live query against the healthy `default/root` runtime found exactly six skill
entities. Each has `:my.skills/name`, `:my.skills/description`, and
`:seon.agent.ctx/file-path` pointing under `seon-skills`; none has canonical
source text. A second query found no installed `skill/*` blocks, and a third
found no `:skills-catalog` block. Thus the desired no-default-skills behavior is
already true, but the durable-source behavior is not.

### Datahike grounding

`reference-code/datahike/src/datahike/db/transaction.cljc` confirms that a
`:db.unique/identity` value upserts the existing entity and that a new
cardinality-one value replaces the current value. Equal effective datoms are
omitted. With history enabled, an old source remains available through history
and as-of queries without a second version entity.

`reference-code/datahike/src/datahike/schema.cljc` validates
`:db.type/string` with `string?`; no Datahike string-size restriction was found.
An exact source string is therefore the direct, supported representation. A
blob indirection should be introduced only if measured skill sizes later prove
that storage choice wrong, and it must remain behind the same importer contract.

## Current runtime path

### Configuration

`config/system.edn` selects:

```clojure
:seon.config/skills {:seon.config/dirs ["seon-skills"]}
```

`config/test.edn` and `config/acme.edn` include the system manifest and do not
install a skills context block. This is the correct separation between import
and context.

`seon.config/skills-dir` in `src/seon/config.cljs` does not implement what the
manifest shape says:

- it consumes only the first member of plural `:seon.config/dirs`;
- it lets `SEON_SKILLS_DIR` override the manifest; and
- it silently falls back to `.claude/skills`.

`.env.example` documents that stale fallback. This turns a selected config
transition into ambient filesystem behavior and can import the wrong audience's
instructions.

### Import and render

`my.skills/seed-skills-tx-data` in `src/my/skills.cljs` scans files and uses a
small regular-expression frontmatter parser. Unsupported or malformed files are
silently skipped. Successful rows store only name, description, and path.

`seon.client/boot-seed!` calls that scan on every selected boot and includes the
rows in `seon.state/reconcile!`. The renderer later calls
`my.skills/skill-block`, pulls the path, rereads the file, and strips
frontmatter. A file edit can therefore change agent context without a database
transaction, provenance record, history entry, changed-attribute invalidation,
or config apply. Removing the original directory makes a supposedly imported
skill unrenderable after restart.

There is also a second content representation, `:my.skills/body`, for inline
skills. The file-backed and inline paths are parallel implementations of one
fact. `::load` still declares an old default `[:repl]`, and
`my.skills/catalog-block` remains as deprecated render machinery even though no
active context tree uses it.

Loaded state itself is already modeled correctly: `my.skills/load` installs an
ordinary `skill/<name>` context block, `my.skills/unload` removes it, and
`my.skills/list` derives loaded state from block presence. Keep that mechanism.

## Target database facts

Register exactly these skill attributes:

| Attribute | Datahike facet | Meaning |
|---|---|---|
| `:my.skills/name` | keyword, cardinality one, unique identity | stable lookup identity |
| `:my.skills/description` | string, cardinality one | compact catalog/query projection from frontmatter |
| `:my.skills/source` | string, cardinality one | exact validated `SKILL.md` source, including frontmatter |

`name` and `description` are deliberate stored facts: identity lookup and cheap
catalog queries require them. The body is not another fact; it is the portion of
`source` after validated frontmatter. Do not add:

- `:my.skills/body`;
- a skill-specific `:seon.agent.ctx/file-path`;
- a loaded/enabled boolean;
- a source digest, imported timestamp, or source-directory attribute;
- an owner/management attribute; or
- a render cache in the database.

The selected config transaction already carries `{user root, process config}`
metadata. Operator or future human imports carry their own transaction metadata.
That records provenance without turning process mechanics into current entity
state.

## One source-to-fact compiler

The public boundary should be source text, not a path. The core pure operation
belongs in `my.skills` and returns errors as data:

```clojure
(compile-source
  {:my.skills/source source
   :my.skills/expected-name expected-name})
;; => {:my.skills/fact
;;     {:my.skills/name :datahike
;;      :my.skills/description "..."
;;      :my.skills/source source}}
;; or {:seon/error ...}
```

`compile-sources` accepts a collection of source requests, validates all of
them, rejects duplicate names, and returns either the complete desired fact set
or an error. It never returns a partially usable transaction.

All adapters stop after obtaining source text:

- configured directory adapter: deterministically reads every standard
  `<directory>/<skill-name>/SKILL.md` from every declared directory;
- operator adapter: reads one file or directory and calls the same compiler;
- future web upload: passes the uploaded string directly; and
- tests: pass strings without requiring fixture paths.

Use a maintained YAML parser for frontmatter rather than extending the regular
expression scanner. For the active CLJS/Node path, add the pinned `yaml` npm
package and call it behind `compile-source`. Parsing validates and projects
metadata; it does not serialize it again. Persist the submitted UTF-8 source
string unchanged, including its body formatting and trailing newline.

Minimum validation is:

- one well-delimited frontmatter mapping;
- nonblank string `name` and `description`;
- a safe, unqualified kebab-case name converted to the identity keyword;
- nonblank Markdown after frontmatter;
- optional directory leaf equal to the declared name; and
- unique names across the whole requested batch.

Allow additional standard frontmatter keys. Reject malformed input explicitly;
do not silently discard unknown YAML shapes or normalize the source.

Relative attachments referenced by a skill are outside this slice's promised
durability. The importer should diagnose such references instead of pretending
they were frozen. If package resources become a demonstrated requirement, add
them later behind this same import boundary; do not retain a path-based body
fallback in anticipation.

## Config state transitions

There is no need for a general diff over a speculative database copy and no need
for a skill-specific ownership system.

| Input | Required skill transition |
|---|---|
| no selected config, or selected config omits `:seon.config/skills` | no skill read, scan, transaction, or retraction |
| skills section with directories | compile every source, then exact-reconcile the whole skill registry |
| skills section with an explicit empty directory vector | retract the current skill registry |
| invalid source or duplicate name | abort before the transaction; preserve current registry |
| desired facts equal current facts | emit no transaction |
| one source changed | replace that entity's description/source facts only |
| configured source removed | retract that skill entity from the managed registry |

A present skills section is an explicit declaration that config owns this
registry transition. Facts in other namespaces remain outside the managed
subset. A user who wants a restored database without config omits the section or
boots config-free; existing imported skill facts remain available from the
database.

This rule is more predictable than trying to infer "config-owned" rows from
filesystem paths or adding ownership datoms. It also matches the broader PRD:
config repairs a declared subset, while config-free restart normally writes
nothing.

## One generated tool-projection system

### Authorities

- `seon-skills/<name>/SKILL.md` is the canonical runtime source for the shipped
  agent corpus.
- `dev-resources/skills/` contains canonical repository-development addenda and
  the few provider overlays that have genuinely different operations.
- a fully namespaced EDN projection manifest declares every output and its
  ordered source fragments.
- `.agents/skills` and `.claude/skills` are committed generated outputs, never
  hand-edited authorities.

Committed outputs are necessary because Codex and Claude discover skills before
the Seon operator has run in a fresh checkout. Mechanical generation plus a
byte-for-byte check makes that duplication disposable rather than authoritative.

### Composition rules

For the shared runtime subjects—`clojurescript`, `data-modeling`,
`data-oriented-clojure`, `datahike`, `repl`, and `ui-canvas`—generate each
tool-facing skill from the runtime `SKILL.md` plus, only where needed, one
repository-development addendum. Improvements to common semantics then flow to
both tools and runtime from one place.

For development-only subjects such as `clojure-testing`, `datastar-web-ui`, and
`seon-context-config`, keep one common canonical development source and project
it into both tool trees.

Browser automation is a real provider split. Keep the shared Seon browser/SSE
invariants once, then compose a small Codex browser-control adapter for
`.agents` and a small Claude Chrome-MCP adapter for `.claude`. Provider names in
incidental prose, such as `AGENTS.md` versus `CLAUDE.md`, should instead say
"repository instructions" and stay common.

The generator must expose two operations through the planned Babashka operator:

- `bin/seon skills sync` atomically replaces both desired output trees and
  removes stale outputs;
- `bin/seon skills check` computes desired outputs in memory and fails on any
  missing, changed, or extra generated file.

`bin/seon up` in a source checkout runs `sync` before build/start. CI and the
development hook run `check` when canonical skill, projection, or generated
files change. The pure projection logic belongs in `seon.dev.skills` and the CLI
verb in `seon.dev.cli`; do not add a second shell implementation.

### Initial semantic merge

Generation must begin only after reconciling the existing content into current
truth. Preserve the newer real-REPL `in-ns` behavior and `#code` reader path,
current canvas API, and no-default-skills semantics. Remove stale
`ui-live-tiles`, `my.tile`, live-tile, world-view, inspector, and
`db/store-inventory` teaching as the corresponding active APIs are removed.
Rewrite the Codex browser overlay instead of copying the Claude commands again.

These are API/vocabulary checks, not tests that assert exact context prose.

## Exact file and symbol changes

| File or tree | Change |
|---|---|
| `src/my/skills.cljs` | register `:my.skills/source`; remove `:my.skills/body` and unused `::load`; replace `parse-frontmatter`, `list-skill-files`, and `seed-skills-tx-data` with specced `compile-source`, `compile-sources`, and a thin deterministic directory adapter; make `skill-block` pull and project source only; delete deprecated catalog rendering |
| `src/seon/config.cljs` | keep an explicit skills-section schema, consume every declared directory, and delete `skills-dir`, `SEON_SKILLS_DIR`, and `.claude/skills` fallback behavior |
| `src/seon/client.cljs` | make `boot-seed!` compile skills only when the selected manifest contains the section; pass the complete desired population to the normal exact reconciler |
| `src/seon/state.cljs` | generalize conditional managed subsets only if needed by the already-planned exact/no-op reconciler; do not add a skill-specific reconciliation function |
| `config/system.edn` | retain explicit shipped-corpus import if a normal selected config should restore it; continue omitting all skill context blocks |
| `config/test.edn` | test import explicitly or override it explicitly empty according to test purpose; never install skill context by default |
| `config/acme.edn` | inherit the same importer semantics; add no ACME-specific path |
| `.env.example` | delete `SEON_SKILLS_DIR` and the `.claude/skills` fallback documentation |
| `package.json`, lockfile | add and pin the YAML parser used by the one compiler |
| `src/seon/dev/skills.clj` | add pure manifest-driven projection, atomic sync, stale-output deletion, and byte-check operations compatible with the Babashka operator |
| `src/seon/dev/cli.clj` | expose `skills sync` and `skills check`; source-mode `up` invokes sync |
| `dev-resources/skills/` | add the fully namespaced projection manifest, common development material, and small provider overlays |
| `seon-skills/` | become the only runtime corpus authority; update stale active APIs in place |
| `.agents/skills/`, `.claude/skills/` | replace all hand copies with committed generated outputs; delete `ui-live-tiles`; generate `ui-canvas` for both |
| `test/my/skills_test.cljs` | replace path/prose assertions with compiler, durability, load/unload, and error behavior |
| `test/seon/config_test.cljs` | test explicit-section and omission semantics; delete env/fallback precedence tests |
| `test/seon/boot/reconcile_seed_test.cljs` | exercise exact skill population update/removal/empty/omission/no-op behavior against a fresh database |
| `test/seon/test_seed.cljs` | remove stale inline-body/default-load setup and comments |
| `src/seon/dev/markdown.clj` | keep `seon-skills/<name>/SKILL.md` as the canonical skill-content lint scope; call projection validation separately rather than teaching the markdown linter three authorities |
| `src/my/CLAUDE.md` | remove the obsolete catalog-block claim and describe explicit load through ordinary blocks |
| `docker/Dockerfile` | continue copying only `seon-skills`; generated developer adapters are not runtime image inputs |
| `src-inspect-ai/src/seon_inspect/tasks/skill_lift.py` | continue reading the canonical `seon-skills` treatment source; change only if it currently assumes a stale skill name |
| `src-inspect-ai/tests/test_canary_guard.py` | continue guarding the canonical runtime corpus, not generated projections |
| `config/legacy.edn` | remove with the planned legacy-config archive; do not update it into a fourth live contract |
| architecture, runtime-reliability roadmap/AGENTS, C49 registry | change “source/body” to exact source plus derived body, record generated adapters, and close C49 only after live proof |

## Ordered implementation

1. Add failing focused behavioral tests for exact-source preservation, invalid
   batch atomicity, database-only rendering, config omission, exact population,
   and projection drift.
2. Replace the `my.skills` schema/compiler/render path in place. There is no
   compatibility read from path or `:my.skills/body`.
3. Connect the explicit config transition to that compiler and the shared exact
   reconciler. Prove identical input produces no transaction.
4. Reset the test cluster so obsolete skill facts disappear with the old
   schema; do not write migration code.
5. Reconcile the three trees semantically into canonical runtime sources,
   canonical development addenda, and minimal provider overlays.
6. Add the manifest-driven generator/checker, generate both adapter trees, and
   delete all unmanifested files including `ui-live-tiles`.
7. Update focused tests, operator/config documentation, architecture, and the
   downstream assumptions named above.
8. Reset and prove the default cluster from cold start. Only after that proof,
   reset and verify ACME without introducing an ACME variant.

## Verification

### Pure compiler

- A valid source returns one fact whose `:my.skills/source` equals the submitted
  source string exactly.
- Rich valid YAML parses through the maintained parser.
- Missing delimiters, wrong root shape, invalid name, blank metadata/body,
  directory-name mismatch, and duplicate batch names return structured errors.
- One invalid member yields no desired facts and no transaction input.

### Database behavior

- Import into a fresh in-memory database, delete the temporary source
  directory, and prove `list`, `load`, and `skill-block` still work.
- Update source and prove the current entity has the new cardinality-one value
  while an as-of/history query can see the old value.
- Prove loaded state changes only with ordinary context-block installation and
  that there is no stored loaded flag.

### Config behavior

- Omitted skills section preserves existing facts and emits no skill scan.
- Explicit empty section clears the registry; other namespace facts remain.
- Invalid input writes nothing.
- Reapplying equal desired sources creates no transaction.
- One changed source updates only its entity; removing one configured source
  retracts only that skill entity.

### Context and reactive behavior

- Structural database queries—not exact prose assertions—show no
  `:skills-catalog` and no `skill/*` block in fresh default, test, or root
  context.
- Explicit load produces one ordinary block; source update causes an ordinary
  changed-attribute invalidation and feed update.
- Config-free restart renders the same loaded source after the import directory
  has been removed.

### Tool projections

- `skills check` passes in a clean checkout.
- A deliberate edit, missing output, and extra output each make it fail;
  `skills sync` repairs all three cases.
- Both tool loaders discover `ui-canvas` and their correct browser adapter.
- A static active-tree vocabulary check finds no `ui-live-tiles`, `my.tile`,
  `seon.render.live-tile`, product world/inspector APIs,
  `db/store-inventory`, `SEON_SKILLS_DIR`, or file-backed skill body. Ordinary
  English and explicitly historical documents are not blindly banned.

Run focused CLJS and Babashka/tool doors for this slice rather than the whole
legacy suite. The final proof is a cold default reset and browser/context
observation, followed by ACME only after default succeeds.

## Risks and non-goals

- Persisting source strings is intentionally simple. Very large user-uploaded
  packages may later justify a content-addressed blob, but adding a digest/cache
  now would store an unproven projection and create another path.
- A `SKILL.md` that depends on relative assets is not portable under this
  source-only contract. Report that limitation at import; package-resource
  storage is a later measured feature.
- Generated tool files can be accidentally edited. The committed manifest,
  check door, source-mode sync, and clear generated-tree ownership make the
  mistake loud and repairable.
- Provider browser commands genuinely differ and require proof in both tool
  environments. This is the one known overlay, not permission for arbitrary
  provider forks.
- The runtime corpus itself contains stale APIs. Generating before the semantic
  merge would multiply bad instructions consistently; canonical does not mean
  correct until those sources are repaired.
- Optional-config semantics and exact reconciliation are shared work in this
  PRD. The importer must land with them, not invent a temporary skill-specific
  ownership attribute or boot fallback.

## Decision summary

One imported skill is one database entity with one exact source fact. One pure
compiler creates those facts. A selected config may authoritatively reconcile
the registry; no config means no skill transition. Loading remains an explicit
ordinary context-block operation, disabled by default. `seon-skills` is the
runtime source authority, and the two development trees are mechanically
generated adapters with only small, named provider differences. Everything else
in the current path is duplicate state or ambient behavior and should be
deleted, not deprecated.
