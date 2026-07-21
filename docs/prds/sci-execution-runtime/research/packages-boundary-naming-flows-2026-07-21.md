---
type: research
status: active
tags: [research, agent, database]
---

# Packages refinement — boundary naming, the full name hierarchy, agent flows

Deep-design refinement of
[[w6-package-host-design-2026-07-21]] under the boundary-layer-first
owner ruling (anchor
[[../program-synthesis-2026-07-21]], "Owner decision batch round 2"):
the production package surface is COMPILED, spec'd per-package boundary
namespaces built into the package-host artifact; the generic op tier
(package-call / handle-call / dispose / describe / subscribe) is the
dev-gated exploration substrate the boundary functions ride on. The
executed playwright probe's amendments
([[probe-evalfree-playwright-2026-07-21]]) are folded in as mandatory.
Every interface claim cites the verified source line; unverified claims
say NOT GROUNDED.

## 0. Grounding ledger (verified this pass, current tree)

| Interface | Where verified | What it settles |
|---|---|---|
| npm name grammar (bun's own) | `reference-code/bun/src/install/dependency.rs:550-567` `is_scoped_package_name` (scoped = leading `@`, one `/` not at position 1 or last); `:572-589` `is_safe_install_folder_name` (per-`/`-component: non-empty, not `.`/`..`, no `\` `:` NUL — "A dependency name/alias becomes a directory under `node_modules/`"); `:533-541` `unscoped_package_name` strips `@scope/` | bun validates names as safe install-folder paths, scoped as `@scope/name`. Bun does NOT enforce the npm-registry authoring grammar itself |
| npm registry authoring grammar (≤214 chars, lowercase for new packages, URL-safe, no leading `.`/`_`) | NOT GROUNDED — `validate-npm-package-name` is not vendored in `reference-code/` and bun's source does not restate it. Nothing depends on this grammar: names pass through verbatim as npm spec strings (§1.1) and bun's own validation is the gate |
| deps.edn lib symbol grammar | `reference-code/tools.deps/src/main/clojure/clojure/tools/deps/util/maven.clj:235-239` `lib->names`: group-id = `(namespace lib)` (falling back to artifact-id for unqualified libs), artifact-id = `(name lib)` split on `$` for classifier; `:228` rebuilds the symbol as `group/artifact$classifier`; `extensions.clj:75-81` `canonicalize` returns `[lib coord]` | a lib is one qualified symbol `group/artifact` (`$classifier` suffix in the name); groups are dotted reverse-DNS strings |
| build-closure fn indexing | `src/seon/indexing.clj:85` `public-fn-vars` (compile-time macro, closure-relative via `(-> &env :ns :name)`, every public first-party fn var); `:110` `first-party-ns-strs`; consumed by the pod at `src/seon/client.cljs:227` (`:require-macros`), `:1109` `core-vars`, `:1425` `var->fn-row` (reads real source/spec/doc at boot), `:1717` `index-core!` (transacts the `:seon.fn` rows; row shape "IDENTICAL … to a detect-and-tee row — downstream readers never branch on origin", client.cljs:1104-1106) | the indexing macro works in ANY CLJS build (it expands against the calling build's own analyzer closure); the TRANSACTION half (`index-core!`) is pod-boot code and does not run in a db-free package host |
| agent discovery surface | `src/seon/agent/ctx/namespaces.cljs:210-212` (context card pulls `:seon.fn/_ns` rows: sym/arglists/doc/source/spec); `src/seon/agent/ctx/menu.cljs:198-203` (toolkit block: "your required namespaces' public fns") | agents discover functions exclusively through `:seon.fn` rows; a fn without a corpus row is invisible in context |
| U2 registry | `src/seon/host/context.clj:804` `registry` (process-local, rebuilt by re-registration); `:816` `:sci/built-in` stamp for host-authored vars; `:834-858` `register-wrapper-vars!` (per-lib sci ns, real `:arglists`/`:doc`, `sci/alter-var-root` on re-register); `:862` `register-wrappers!` (agent corpus class); `:877` `register-host-wrappers!`; `:883` `install-registered-wrappers!`; `:891-899` `registry-load-fn` (shared `:load-fn`; first require injects cached vars) | wrapper registration/upgrade/lazy-require is existing machinery; W6's claim survives the W0.4 line shift with new line numbers as cited |
| corpus row builder (agent-authored tier) | `src/seon/host/record.clj:122-161` `fn-row` (`:seon.fn/sym`, `:seon.fn/ns`, source, fingerprint, `:seon.fn/execution-tier :nursery`, arglists, doc, spec) | the graduation-path row mint for agent-authored `my.*` wrappers |
| execution envelope | `src/seon/host.clj:11-23` (startup first frame; invoke carries function-identity "function-symbol plus EITHER …", args, ABSOLUTE deadline-ms, result-limit-bytes); `:107` `::function-symbol :qualified-symbol`; `:141-142` deadline/result-limit schema rows; `:1037` `start!`; `:1113` `-main` | boundary functions are invocable as ordinary `:seon.execution/function-symbol` values — no new message kind |
| op-id receipts | `src/seon/host/context.clj:793` (`:seon.capability/op-id` on the transact wrapper's success map) | the receipt discipline package ops reuse |
| config-cap idiom | `src/seon/config.cljs:100-118` (`:seon.config/cap` registered once; `:seon.config.render/*` keys reference it) | the key-shape the §1.4/W1 accessors follow |
| artifact flavor + digest | `script/seon/dev/config.clj:168-176` `default-artifact` (fixed fields: one `client-build-id`, one `execution-build-id`, cache root, outputs); `script/seon/dev/artifact.clj:42-60` (manifest schema: fixed named digest fields — client/execution/execution-runtime/bootstrap/css/writer/application) | a new build = one new named descriptor field + one new named digest field; the schema is fixed-field, not an open per-cluster matrix |
| event-frame idiom | `src/seon/db/protocol.cljc:302-304` (`::event` enum) and `:846-861` (closed per-event maps keyed by `::event`) | the "bounded event frames" the ruling names: closed maps keyed by an event keyword, schema'd per kind |
| call-time `js/require` idiom | `src/seon/agent/ctx.cljs:130,144`, `src/seon/agent/search/internal.cljs:75-76` (first-party fns call `(js/require "fs")` inside the body, not in the ns form) | the lazy-require idiom boundary namespaces must use (§2.4) |
| shadow `:node-script` module splitting | NOT GROUNDED (not read in `reference-code/shadow-cljs` this pass) — and not needed: the §2.4 decision requires no code splitting |

Everything else this refinement leans on (cluster layout, staged
install, host lifecycle, handles) was grounded in the W6 base design's
own ledger and is not re-derived here; where its line numbers drifted
(post-W0.4 `context.clj`), the corrected numbers above supersede.

## 1. Q1 — naming: an explicit mapping fact, not a munge

Owner ruling (2026-07-21, integrated here, superseding any
munging-scheme answer): the package→namespace mapping is EXPLICIT DATA
chosen by the agent at install time. The ecosystems' own formats keep
every coordinate detail verbatim; the namespace is an agent/owner-
chosen handle; uniqueness is enforced by the ledger, never derived
from the name. There is NO munging function.

### 1.1 The install map shape (settled)

`my.packages/install` takes one namespaced map — the request keys ARE
the ledger attribute keys (one shape, transacted as written):

```clojure
;; npm — the value is npm's own package spec string, verbatim
;; ("name@range"; a bare name means npm's own default-latest)
{:seon.packages/npm "playwright-core@^1.61"
 :seon.packages/as  seon.packages.browser
 :seon.capability/op-id "…"}          ; optional, receipt discipline

;; deps.edn — the value is one deps.edn :deps entry, verbatim
;; (lib symbol → coord map, exactly as deps.edn holds it)
{:seon.packages/deps '{org.clojure/data.csv {:mvn/version "1.1.0"}}
 :seon.packages/as   seon.packages.csv}
```

Shape rules (data-oriented):

- Keys live in `seon.packages` — the namespace whose functions operate
  on this data (manifest generation, install planning; key-namespaces
  ruling). Ecosystem is ATTRIBUTE PRESENCE: a row carrying
  `:seon.packages/npm` is an npm row, `:seon.packages/deps` a deps
  row. No `:type`/`:kind` stamp; supplying both, or neither, in one
  request is a schema rejection.
- `:seon.packages/npm` — `:string`, npm's own spec form (npm's word for
  `name@range` is a package spec). Scoped names (`@scope/name@range`)
  pass verbatim; bun's own grammar validates them
  (`is_scoped_package_name`, dependency.rs:550-567).
- `:seon.packages/deps` — exactly one deps.edn `:deps` entry
  (`[:map-of :qualified-symbol :map]` with count 1; stored as the
  canonical EDN string of that one entry, the W6 §1.2 storage idiom).
  Lib and coord vocabulary are tools.deps' own
  (`lib->names`, maven.clj:235-239; coord maps per
  `extensions.clj:75-81`).
- `:seon.packages/as` — the boundary namespace symbol, the row's
  IDENTITY attribute (unique, in `:seon.entity/id-attr`). Validation
  is a legality rule only, not a derivation: a dotted symbol beginning
  `seon.packages.` whose segments are valid Clojure ns segments, and
  not an existing compiled first-party or corpus-owned namespace.
  Collision with an existing row → steering `:seon/error` naming the
  occupying coordinate and namespace.
- Lock pins stay in the lock's own words on the same row:
  `:seon.packages.npm/resolved` + `:seon.packages.npm/integrity`
  (from `bun.lock`, W6 §1.2 unchanged); the deps basis proof is the
  JVM-side verification (W6 §1.3 step 3-4).

### 1.2 Uniqueness and runtime routing

- **Namespace uniqueness** is the identity attribute: one
  `:seon.packages/as` value, one row. Datahike's own unique-identity
  semantics make re-install-with-same-`:as` an UPSERT (§3.1-3.2) and
  a different coordinate under an occupied namespace an update, never
  a silent second row.
- **Package uniqueness** is a validation rule in the install flow (not
  a second identity attribute): one npm name / one deps lib may appear
  in at most one row's coordinate, because the generated
  `package.json`/`deps.edn` can hold one entry per name. Installing an
  already-mapped package under a NEW `:as` returns the steering error
  naming the existing namespace ("playwright-core is mapped to
  seon.packages.browser; remove it first to re-map").
- **Runtime is derived from the row's ecosystem attribute**, stated
  once: `seon.packages/row->host` — `:seon.packages/npm` present → the
  Bun package host; `:seon.packages/deps` present → the JVM package
  host. Attribute presence is the dispatch; the namespace name carries
  no runtime encoding and never needs to.

### 1.3 Where the authoritative identity lives

The namespace name is a handle; the ledger row is the identity. The
authority chain:

- **Ledger facts**: `:seon.packages/as` (identity) ↔ the verbatim
  coordinate (`:seon.packages/npm` or `:seon.packages/deps`) ↔ pins ↔
  `:seon.packages/generation`. Manifests generate from the verbatim
  coordinate values — the ecosystems' formats already capture all
  coordinate detail; nothing is re-encoded into names.
- **Compiled boundary namespaces** (the graduated tier) carry ns
  metadata `{:seon.packages/coordinate <verbatim>}` — the reverse
  link, readable without the database. At registration time the sci
  host matches compiled boundaries to ledger rows by coordinate: a
  compiled boundary serves a row whose coordinate matches AND whose
  `:seon.packages/as` equals the compiled ns name. If the coordinate
  matches but the agent mapped a different `:as`, the install receipt
  steers ("a compiled boundary for playwright-core exists as
  seon.packages.browser") — suggestion, never a forced rename.
- **Generation** carries staleness; versions live in the coordinate
  and pin facts. Re-mapping (a package to a different namespace) is
  remove-by-namespace then install with the new `:as` (§3.3);
  namespace renames are reset-boundary events per the no-lock-in
  ruling.

## 2. Q2 — the full name hierarchy

### 2.1 The one table

| Layer | Example | Who names it | Vocabulary rule | Schema lives | Agents discover via |
|---|---|---|---|---|---|
| 1. Package coordinate (ledger fact) | `:seon.packages.npm/name "cheerio"`, `:seon.packages.deps/lib org.clojure/data.csv` | the ecosystem (verbatim producer identifier) | producer's own words, byte-exact | `src/seon/packages.cljc` (W6 §1.2) | `my.packages/installed` derived read + the derived packages context block (§3.4) |
| 2. Boundary namespace (host-side, compiled) | `seon.packages.browser/goto` | the agent/owner at install time via `:seon.packages/as` (§1.1); the compiled ns takes the mapped name, its coordinate in ns metadata | the NAMESPACE is a chosen handle; the FUNCTION names are goal-shaped in the package's domain terms (playwright's "click", cheerio's "select") | malli schemas colocated in the boundary ns itself (`{:malli/schema …}` on each defn — the ordinary rule) | `:seon.fn` rows served by the host at startup, transacted by the sci host (§2.3), rendered by `ctx.namespaces` (namespaces.cljs:210-212) and the menu toolkit block (menu.cljs:198-203) |
| 3. U2 registry wrapper var (sci host) | `(require '[seon.packages.browser :as browser])` inside an agent context | derived — SAME lib name and fn syms as layer 2 (a wrapper is the boundary fn's client stub, never a rename) | identical to layer 2 by construction | none of its own — `:arglists`/`:doc` copied from the layer-2 row at registration (context.clj:846-856); a name-only binding is a rejected registration (W6 §3) | lazy require through `registry-load-fn` (context.clj:891-899); the SAME `:seon.fn` rows as layer 2 (one sym, one row — the wrapper is not a second function) |
| 4. Agent-facing capability (`my.*`) | `my.browser/goto`, backed by `seon.packages.browser/*` | the capability author (agent, then graduation) | GOAL vocabulary — what the agent wants done, not how the package says it | on the `my.*` fns themselves; corpus row via the recorded eval path (record.clj:122-161, `:seon.fn/execution-tier :nursery` → graduation) | ordinary corpus discovery: `:seon.fn` rows + required-namespaces toolkit render |

The boundary namespace NAME is an agent/owner-chosen handle; the
authoritative identity is the ledger fact (§1.3). When a chosen handle
is already goal-shaped (`seon.packages.browser`), layer 4 may stay
thin or absent — the layers are ownership classes (compiled host-side
vs agent corpus), not mandatory indirection.

Layer 3 exists because the boundary function EXECUTES in the package
host while the agent evaluates on the sci host: the registry var's body
is the protocol hop (invoke with
`:seon.execution/function-symbol` = the boundary fn's own symbol —
host.clj:107,113-116; no new message kind, exactly W6 §2.1's mechanism).
It is not a rename layer and mints no vocabulary: same ns, same syms,
same arglists.

### 2.2 Who writes which facts

The package hosts stay database-free (owner ruling: "the SCI HOST
writes the facts"). Fact writers by layer:

- layer 1 rows + generation: the sci host inside the `my.packages/*`
  flows (§3), through its existing writer channel;
- layer 2 `:seon.fn`/`:seon.ns` rows: the sci host transacts the row
  payload the package host SERVED (§2.3) — the host computes, the sci
  host writes;
- layer 3: no facts — the registry is process-local derived state
  (context.clj:804-808), rebuilt by re-registration;
- layer 4: the existing recorded-eval corpus path (record.clj), no
  package-specific machinery.

### 2.3 Boundary-function discovery — the real interface

Grounded finding: `public-fn-vars` (indexing.clj:85) is a compile-time,
build-closure-relative macro — it CAN ride in the `:packages-host`
build exactly as it rides in the pod build (client.cljs:227). What
cannot ride is the transaction half: `index-core!` (client.cljs:1717)
is pod-boot code with a database session; the package host has none.

Settled mechanism — **compute in the host build, transact on the sci
host**:

1. The `:packages-host` entry ns evaluates `(public-fn-vars)` +
   `(first-party-ns-strs)` at compile time over ITS closure — every
   public boundary fn, by construction, the moment the build compiles
   it.
2. At startup the host projects each var through the same row shape
   `var->fn-row` produces (client.cljs:1425 — sym, arglists parsed from
   real source, doc, spec form when present, source text). Source files
   are read from the checkout exactly as the pod does today; same
   deployment model, same assumption.
3. The ready reply (the startup→ready exchange, host.clj:11-30) carries
   the row payload plus the build's boundary-ns list and the echoed
   `:seon.packages/generation`.
4. The sci-host client transacts `:seon.fn`/`:seon.ns` rows ONLY for
   boundary namespaces matched to an installed ledger row by the §1.3
   rule (`:seon.packages/as` = the ns name, coordinate = the ns
   metadata) — a compiled-in boundary for a package this
   cluster has not installed produces no rows and therefore no context
   presence (derive-don't-store: visibility is a join between the build
   surface and the install facts).
5. The same gate governs wrapper registration: `register-host-wrappers!`
   runs per installed boundary ns; the vars carry the served
   arglists/doc (context.clj:846-856) and the `:sci/built-in` stamp
   (context.clj:816).

Rows use the existing `:seon.fn` shape verbatim ("downstream readers
never branch on origin", client.cljs:1104-1106). No new discovery
mechanism, one new data flow (host-served row payload).

### 2.4 One shared artifact, not per-cluster composition

Settled: **one `:packages-host` build containing ALL first-party
boundary namespaces**, shared by every cluster.

- Per-cluster-composed flavors are rejected on grounded operator
  interfaces: the artifact descriptor and digest manifest are
  fixed-field schemas (config.clj:168-176; artifact.clj:42-60 — named
  digest fields, one per build). A per-package-set build matrix would
  multiply descriptors/digests combinatorially and reverse W9's build
  shrink. The `:packages-host` build adds exactly one descriptor field
  and one digest field (e.g. `:seon.dev.artifact/packages-host-digest`)
  to those schemas.
- Shadow lazy module loading is not evaluated (NOT GROUNDED for
  `:node-script`) because nothing needs it.
- Per-cluster difference is entirely RUNTIME: (a) the host process cwd
  is the cluster's `packages/npm/` tree (W6 §2.2 — isolation by cwd);
  (b) row/wrapper visibility gates on installed facts (§2.3.4-5).
- **Hard spec constraint this creates:** boundary namespaces must
  acquire their package with call-time `js/require` inside function
  bodies — the proven first-party idiom (ctx.cljs:130,144;
  search/internal.cljs:75-76) — NEVER an ns-form string require. A
  bundle-level `require("cheerio")` executes at host boot and would
  crash every cluster missing any one compiled-in package. A boundary
  call whose `js/require` fails returns the steering error naming
  `my.packages/install` and the coordinate (errors as values; the
  not-installed case is reachable only when the visibility gate and
  reality disagree — e.g. mid-swap — and must still steer, not throw).
- JVM boundary namespaces (`seon.packages.deps.*`, `.clj` files in the
  same tree) follow the same rule with `requiring-resolve` at call
  time against the host's cluster basis; they are compiled/loaded only
  in the JVM package host, never on the sci host's classpath.
- Boundary graduation (§3.5) changes the build input ⇒ new digest ⇒ it
  is a coordinated source-freeze checkpoint per the standing artifact
  rule, and host relaunch after rebuild is the ordinary swap protocol
  (W6 §2.4) with a generation bump.

## 3. Q3 — the agent flows, explicit

All agent-facing entry points are `my.packages/*` host wrappers (owner
decision 4), map-in/map-out, errors as values, registered through
`register-host-wrappers!`. Every effectful op carries
`:seon.capability/op-id` (context.clj:793 idiom): replaying the same
op-id returns the recorded receipt, never a second side effect.

### 3.1 `my.packages/install` — W6 base (§1.3/§1.5) spine, §1.1 arity

One map in (§1.1): the verbatim ecosystem coordinate
(`:seon.packages/npm` string OR `:seon.packages/deps` one-entry map)
PLUS the explicit `:seon.packages/as` namespace, optional
`:seon.capability/op-id`.

Steps and what the agent sees at each:

1. **Gate** — `:seon.config.packages/policy` admits/rejects; rejection
   is a steering error naming the key (default policy is `:open` for
   now — owner decision 8). Shape rejections here too: both/neither
   ecosystem attributes, an illegal `:as` (§1.1), an `:as` occupied by
   a DIFFERENT coordinate's ecosystem twin (§1.2), or a coordinate
   already mapped to another namespace (§1.2 — steering names the
   occupying namespace).
2. **Record** — the row transacts as written: `:seon.packages/as`
   upserts on the identity attribute, coordinate verbatim. From this
   instant `my.packages/installed` and the packages context block show
   the row as requested-but-unpinned (coordinate present,
   `resolved`/`integrity` absent — derived, no status flag).
3. **Stage → Verify** — as W6 §1.3 (bounded subprocess, staging tree,
   trust-list semantics). Failure: staging deleted, error value with
   the failure on the op receipt; the row stays visible and
   re-runnable.
4. **Swap + relaunch + generation bump** — as W6 §1.3/§2.4. Queued
   wrapper calls drain or error naming
   `:seon.config.packages.host/swap-queue-deadline-ms`.
5. **Register + index** — the sci host re-registers wrappers for the
   new generation and transacts the layer-2 rows served by the fresh
   host (§2.3). The agent's NEXT context render shows the boundary
   namespace in the toolkit/namespaces blocks — discovery is the
   ordinary corpus render, not an announcement.
6. **Return** — receipt map: `:seon.db/ok? true`,
   `:seon.capability/op-id`, the pin facts
   (`resolved`/`integrity` or the canonical coord), and
   `:seon.packages/generation`.

Hostile gate: W6 §5.4 (hostile postinstall) + §5.5 (concurrent
clusters) verbatim.

### 3.2 Update — install upserting on the same namespace (no second fn)

Update = a new coordinate on the SAME `:seon.packages/as` (owner
ruling; last version wins, per the teaching contract). Mechanically it
is `my.packages/install` again: the identity attribute upserts the
row, the new verbatim coordinate replaces the old, old pin facts
retract on successful swap. There is deliberately NO separate
`my.packages/update` function — one mechanism, and Datahike's own
upsert semantics already are the last-version-wins behavior.
Asserted differences from a fresh install:

- on the JVM side an update is NEVER a live `add-libs` — change/remove
  is terminate + rebuild basis + relaunch (W6 §1.3 ruling; live add is
  add-only, `reference-code/clojure/.../deps.clj:22-33` per the W6
  ledger);
- a coordinate equal to the row's current coordinate with a matching
  lock is a converged no-op receipt (`:seon.packages/converged? true`),
  mirroring config-apply's writes-nothing-when-converged discipline;
- switching an occupied `:as` to the OTHER ecosystem attribute is a
  steering rejection (remove first) — an upsert never silently changes
  a row's ecosystem.

Idempotency: replaying the op-id after success returns the recorded
receipt. Hostile gate: W6 §5.7 (swap under load) is THE update gate;
plus handle-staleness (§5.6) since an update invalidates the prior
generation's handles.

### 3.3 `my.packages/remove` — by namespace; re-mapping defined

`(my.packages/remove {:seon.packages/as seon.packages.browser})` —
the namespace is the identity, so removal is by namespace. Retract the
row, regenerate manifests from the remaining verbatim coordinates,
swap/relaunch, bump generation — W6 §1.2/§1.3. NEW here: the sci host
also retracts the removed namespace's layer-2 `:seon.fn`/`:seon.ns`
rows and unregisters its wrappers, so the namespace disappears from
context by fact absence (a require after removal fails as an ordinary
unknown lib; a retained handle from the old generation returns the
staleness steering).

**Re-mapping** a package to a different namespace is exactly
remove-by-namespace + install-with-new-`:as` (two ops, two receipts —
never a rename fact). A compiled boundary whose ns name equals the
OLD namespace simply stops matching (§1.3) and its rows never
transact; the new namespace serves agent-authored wrappers until a
compiled boundary under that name graduates.

### 3.4 `my.packages/installed` — W6 base, render pinned down

Derived read over ledger rows + generation; never a census. Returns
per row: the namespace (`:seon.packages/as`), the verbatim coordinate,
the resolved pin, and the generation. The same derivation backs the
packages CONTEXT BLOCK: a render function that queries ledger rows and
omits itself when no packages exist (reactive-context law). Per row it
renders namespace, verbatim coordinate → pin, and whether this host
generation serves it;
`:seon.render/ai` head bounded by the existing token discipline.

### 3.5 Author-boundary flow — NEW (the extension path, mechanized)

The graduation ladder from exploration to compiled boundary:

1. **Explore (dev-gated generic ops).** Config key
   `:seon.config.packages/exploration-ops`
   (`[:enum :enabled :disabled]`, default `:enabled` now per the open
   policy; the flip to `:disabled` is one fact). When enabled, the
   generic tier is registered as host wrappers:
   `seon.packages.host/call` (module + export path + args),
   `seon.handle/call`, `seon.handle/dispose!`, `seon.handle/describe`,
   and NEW `seon.handle/subscribe` + `seon.handle/poll` (§4). When
   disabled, the wrappers are not registered — absent, not scolding.
2. **Author.** The agent writes `my.*` wrapper fns composing generic
   ops — ordinary recorded evals minting `:nursery` corpus rows
   (record.clj:122-161), registered via `register-wrappers!`
   (context.clj:862) so the whole fleet can require them.
3. **Test + hostile gate.** The wrapper's malli schema drives the
   generative tier (anchor testing policy); its hostile entries follow
   the §5 battery shapes. Package-capabilities Phase 0's learning
   capture judges the arglists/doc surface.
4. **Graduate to compiled boundary.** Promotion is a SOURCE event, not
   a runtime event: the wrapper's logic is rewritten as a boundary
   namespace under `src/seon/packages/…` taking the mapped
   `:seon.packages/as` name, its verbatim coordinate in ns metadata
   (§1.3), call-time require, function names in the package's domain
   terms (§2.1 layer 2), with its adoption map and declarative ops
   audited (§4.4-4.5). This lands as an
   ordinary reviewed commit; the `:packages-host` artifact rebuilds
   (new digest, checkpoint rules — §2.4).
5. **Relaunch + appear.** The next host launch serves the new boundary
   rows (§2.3); the sci host registers the layer-3 wrappers; the
   `my.*` capability (layer 4) now delegates to the boundary fn — the
   agent-authored version either graduates alongside (the existing
   corpus graduation path) or is superseded by re-registration.

Failure shapes throughout: every step is an error value with the
config key or coordinate named; nothing throws into the agent loop.

### 3.6 Flow inventory vs the W6 base

| Flow | W6 base | This refinement |
|---|---|---|
| install | §1.3/§1.5 spine | NEW arity (verbatim coordinate + explicit `:seon.packages/as`, §1.1); collision/occupied steering (§1.2); step-visible context states (3.1.2); post-swap row/wrapper registration (3.1.5) |
| update | ABSENT (only "range/coord change" implied by §1.3) | settled as install-upsert on the same namespace (last version wins); converged-no-op receipt; never-live-add and no-ecosystem-switch rules explicit |
| remove | §1.2 retract + reconcile | keyed by `:seon.packages/as`; + layer-2 row retraction and wrapper unregistration; re-mapping = remove + install defined |
| installed | §1.5 derived read | returns namespace + verbatim coordinate + pin + generation; + the derived packages context block as the same derivation |
| author-boundary | §6 Phase 0 sketch only | NEW mechanized ladder incl. the dev gate key, graduation-as-source-event, artifact/digest consequences |
| exploration ops | §2.1 four ops, always-on | dev-gated by `:seon.config.packages/exploration-ops`; + subscribe/poll (§4) |

## 4. Probe amendments folded in (mandatory for WP-B)

1. **`handle-subscribe` / subscription handles.** A subscription is a
   held object in the same handle table: target handle + event name +
   bounded ring. Functions-first naming: `seon.handle/subscribe` and
   `seon.handle/poll` are the sci-host wrappers; on the wire they ride
   the existing invoke/`handle-call` machinery (the probe proved no
   separate poll/unsubscribe op is needed). Disposal is ordinary
   `seon.handle/dispose!`; disposing the target auto-disposes its
   subscriptions.
2. **Bounded cursor-addressed event frames.** A poll result is a
   closed map in the db-protocol event-map idiom (protocol.cljc:302-304,
   846-861): keyed by `:seon.handle/event`, carrying monotonically
   increasing sequence, the caller's cursor, dropped-event count +
   oldest retained sequence, event name, timestamp, and projected args
   (ordinary data or tagged handles). Cursor reads, never destructive
   polls — request retry must not lose events. New config keys join
   §1.4: `:seon.config.packages.host/subscription-ring-capacity`
   (default from the probe's 64) and the ring count per handle under
   the existing `:seon.config.handle/per-channel-cap` discipline.
   Delivery is PULL (poll); a boundary function may loop a poll
   internally to present a blocking wait — the host never pushes
   unsolicited frames, keeping the one-active-invocation session
   contract intact.
3. **Concurrent shared-handle sessions.** All sessions in one package
   host share the handle table and dispatch concurrently, retaining
   one active invocation per session (`:seon.config.packages.host/sessions`
   ≥ 2). Serial sessions deadlock the dialog and waiter-plus-trigger
   flows — this is a MUST in the WP-B gate, proven live in the probe.
4. **Recursive handle references in arguments.** `seon/handle` tagged
   values are legal recursively inside argument data; the host resolves
   each against its own table AND generation before invocation
   (mismatch → staleness steering). The wire still carries only data.
5. **Channel adoption registry.** Constructor names lie (`_Page`,
   `Browser2`); each boundary namespace ships an explicit adoption map
   from the package's runtime objects to producer-word channels
   (`:playwright/page`, `:playwright/dialog`, …). An object no adoption
   map claims gets channel `:seon.packages.host/opaque` — honest, and a
   render cue that a boundary namespace is missing.
6. **The evaluate-APIs boundary, stated.** APIs that intrinsically take
   authored executable code (`page.evaluate`, predicate callbacks, some
   routing forms) are NOT reachable through the generic ops and are
   exposed, if at all, only as audited declarative operations inside a
   compiled boundary namespace. The spec says this affirmatively; it is
   the eval-free rule's visible edge, not an omission.

## (a) Delta list against the W6 base design

1. W6 §1.2's attribute set is REPLACED by the explicit-mapping shape
   (owner ruling): `:seon.packages/as` (identity, the chosen
   namespace), `:seon.packages/npm` (verbatim spec string) /
   `:seon.packages/deps` (verbatim one-entry `:deps` map) — the
   name/range and lib/coord splits collapse into the ecosystems' own
   verbatim forms; lock pins (`:seon.packages.npm/resolved`/
   `integrity`) and `:seon.packages/generation` stand. Ns-metadata
   reverse link `:seon.packages/coordinate` on compiled boundaries.
2. NO munging function (superseded by the ruling): uniqueness =
   identity attribute + steering collision errors; `:as` legality is a
   validation rule only; runtime routing derived from ecosystem
   attribute presence by `seon.packages/row->host`.
3. The production surface is the compiled boundary tier; W6 §2.1's
   four generic ops become the DEV-GATED exploration substrate
   (`:seon.config.packages/exploration-ops`), extended with
   subscribe/poll per the probe.
4. NEW discovery flow: `public-fn-vars` compiled into the
   `:packages-host` build; host serves `:seon.fn`-shaped rows in the
   ready exchange; the sci host transacts them gated on installed
   facts (§2.3). W6 had no boundary-fn discovery story.
5. Artifact decision: ONE shared `:packages-host` build; call-time
   `js/require`/`requiring-resolve` becomes a hard boundary-ns
   constraint; one new descriptor field + digest field (§2.4).
6. Update settled as install-upsert (one mechanism, converged-no-op
   receipts); remove keyed by namespace, retracting layer-2 rows and
   unregistering wrappers; re-mapping = remove + install.
7. NEW derived packages context block sharing `installed`'s derivation.
8. NEW author-boundary graduation ladder ending in a source-event
   promotion + artifact rebuild (§3.5).
9. Probe amendments 1-6 (§4) are mandatory WP-B contract items.
10. Corrected `host/context.clj` line anchors post-W0.4 (registry :804,
    stamp :816, register :862/:877, load-fn :891).
11. Config-key additions to §1.4:
    `:seon.config.packages/exploration-ops`,
    `:seon.config.packages.host/subscription-ring-capacity`.

## (b) Revised WP scope statements (spec-ready)

**WP-B — Bun package host + generic-op substrate.** Depends on WP-K.
Owned paths: `src/seon/db/transport/uds.cljs` (`listen-stream!` in
place), NEW `src/seon/packages/host.cljs` (op dispatch, handle table,
subscription rings, adoption fallback channel), shadow `:packages-host`
build + artifact descriptor/digest field, NEW
`src/seon/host/packages_client.clj` (spawn/ready/respawn/queue/
watchdog; ready-exchange row payload handling), tests. Contract: the
five generic ops (`package-call`, `handle-call`, `handle-dispose`,
`handle-describe`, `handle-subscribe`) + `poll` via handle-call;
concurrent shared-handle sessions with one active invocation per
session; recursive handle refs resolved against table + generation;
bounded cursor event frames per §4.2; evaluate-APIs boundary stated in
the host docstring; dev gate honored (ops absent when
`:seon.config.packages/exploration-ops` is `:disabled`). Gate: W6
WP-B gate + probe flows re-run against the real host (dialog flow
across two sessions; waiter+trigger; cursor retry loses no events;
killed browser child ≠ dead host; hostile entries §5.1-3).

**WP-W — install/update/remove flow, boundary registration, wrapper
generation.** Depends on WP-K + one host; completes U13. Owned paths:
`src/seon/packages.cljc` (staged flow §1.3, the §1.1 request/row
schemas, `:as` legality validation, manifest generators from verbatim
coordinates), `src/seon/host/context.clj` (register `my.packages/*`;
per-generation re-registration; installed-fact gating),
`packages_client.clj` (swap protocol; transact served rows), the
packages context block render, tests. Gate: W6 WP-W gate PLUS: the
§1.1 map shape round-trips to byte-stable manifests (npm spec string →
`package.json` entry; verbatim `:deps` entry → `deps.edn`); an
occupied `:as` under a different coordinate upserts (update); a
coordinate already mapped elsewhere and an illegal/colliding `:as`
each return the named steering error; update converges (no-op receipt)
and swaps (facts + generation + handle staleness asserted); remove by
namespace retracts layer-2 rows and the ns disappears from a live
agent's context render (behavioral, not string); boundary rows appear
in `ctx.namespaces`/menu renders only for installed packages; op-id
replay of install/remove is idempotent.

(WP-K, WP-J, WP-H, WP-S stand as cut in W6 §8; WP-J additionally
inherits the call-time `requiring-resolve` constraint and the served
rows exchange; WP-H additionally owns `seon.handle/subscribe`/`poll`
wrappers + the event-frame schema.)

## (c) Open owner decisions

1. **`:as` legality prefix**: §1.1 requires the chosen namespace to
   begin `seon.packages.` — it keeps the discoverability promise and
   aligns the graduated source tree (`src/seon/packages/…`), but it is
   a constraint on the agent's choice. Alternative: any non-reserved,
   non-colliding namespace. Recommend requiring the prefix.
2. **Exploration-ops default**: `:enabled` now (consistent with the
   open install policy), flipped to `:disabled` when the trust posture
   tightens. Recommend `:enabled`; both are one fact.
3. **Boundary graduation authorship**: §3.5 step 4 makes promotion a
   reviewed source commit (human/CI), not a runtime self-modification.
   The alternative — agents write into `src/seon/packages/` live — is
   rejected here as a second graduation mechanism competing with the
   corpus tier. Confirm, or direct that the corpus graduation tier
   should eventually subsume compiled-boundary promotion (that would be
   a namespace-hierarchy/W5-era design, not WP-W).
