---
type: research
status: complete
tags: [prd, research, packages, runtime]
---

# First npm package wrapper exemplar

## Outcome

`fast-deep-equal@3.1.3` installed successfully in the isolated
`pkg-wrapper-exemplar` cluster, and its package export worked from that
cluster's Bun package directory. The wrapper leaf was authored in the
cluster's packages corpus, never in Seon `src/`. The end-to-end live-agent gate
stopped honestly: no mechanism ingests a cluster package corpus file into the
database-backed program corpus, so the agent loader cannot see the namespace.
The shared operator also encountered unrelated retained-default-database schema
drift before an isolated pod could become live; that does not change the
source-proven loader stop.

## Package choice

I chose `fast-deep-equal` because recursive equality of JSON-like values is a
small but genuinely useful building block for assertions, reconciliation, and
change detection. Version 3.1.3 has a tiny single-function API, no native
binding, and no lifecycle-script trust requirement. Its ordinary return is a
boolean, making the successful wrapper boundary naturally data-only, while its
module export is itself a JavaScript function and therefore supplies a clear
deliberate non-serializable case for ruling 15 steering.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding |
|---|---|---|
| `fast-deep-equal` | npm 3.1.3 | Installed package `index.js`; cluster probe recorded in `tmp/orchestrator/pkg-package-probe.log` |
| Bun | install 1.3.14 | `tmp/orchestrator/pkg-install.log` |
| WP-K package data layer | Seon `src/seon/packages.cljc` at working HEAD | Request/ledger schemas, `row->host`, byte-stable `npm-manifest`, and plan functions at lines 1-372 |
| Cluster package skeleton | Seon `script/seon/dev/cluster.clj` at working HEAD | Only native manifests are materialized at lines 75-95 |
| CLJS authored-source loader | Seon `src/seon/eval.cljs` at working HEAD | Database-provided source fallback and absent-ns rethrow at lines 818-885 |
| Program acquisition | Seon `src/seon/execution.cljs` and `execution/runtime.cljs` at working HEAD | REPL-provenance source selection at `execution.cljs:342-356,669-708`; handoff at `runtime.cljs:648-681` |

## Cluster artifacts and install

The WP-K-shaped manifest row is equivalent to:

```clojure
{:seon.packages/as seon.packages.js.fast-deep-equal
 :seon.packages.npm/name "fast-deep-equal"
 :seon.packages.npm/range "3.1.3"}

```

WP-K's `npm-manifest` deterministically projects that row to the exact checked
in cluster-local file:

```json
{"dependencies":{"fast-deep-equal":"3.1.3"},"trustedDependencies":[]}

```

`bun install` in
`data/clusters/pkg-wrapper-exemplar/packages/npm/` produced `bun.lock` and
`node_modules/fast-deep-equal`. The direct tier-local probe returned:

```json
{"equal":true,"unequal":false,"exportType":"function"}

```

This proves the native manifest, install directory, package resolution, useful
data return, and deliberate non-serializable export independently of Seon's
missing wrapper loader.

## WP-K coverage versus manual WP-W work

| Step | WP-K covered | Manual action or gap |
|---|---|---|
| Validate row shape and namespace | Pure schemas and `validate-install` exist | No installed `my.packages/install` entry invoked them |
| Select host | `row->host` derives Bun from the npm attribute | No runtime routed installation to a package host |
| Generate manifests | `npm-manifest` and `deps-manifest` define byte-stable output | I created the cluster package directories and emitted both files by hand |
| Install and pin | Ledger schemas admit resolved/integrity fields | I ran `bun install`; no bounded stage/verify/swap flow recorded pins or generation |
| Author wrapper | Ruling 16 fixes the namespace and cluster ownership | I chose the API and wrote the `.cljs` leaf by hand |
| Ingest wrapper corpus | Not covered | No packages-filesystem to database-corpus door exists |
| Register/load wrapper | Host routing is only data today | No prefix-filtered JS wrapper loader or registration path exists |
| Enforce ruling 15 | Ruling defines the error value | No generic package boundary was reachable; the leaf explicitly steers its function export |
| Live agent proof | Not covered | Blocked first by shared default schema drift operationally and, decisively, by absent corpus ingestion |
| Remove/update | Pure plans and manifest regeneration exist | No staged runtime flow, host generation swap, or corpus retraction exists |

Every manual action in this table is WP-W work or a prerequisite WP-B loader
door; none was hidden behind a local Seon-source fix.

## Deterministic npm name to namespace munge

Use the prefix `seon.packages.js.`. Remove an initial `@`, then split the npm
name on `/`: one segment denotes an unscoped package; exactly two denote scope
and package. Encode each segment from UTF-8 bytes by leaving ASCII lowercase
letters, digits, and `-` unchanged and encoding every other byte as `_hh` with
two lowercase hexadecimal digits. Join encoded segments with `.`. Thus
`fast-deep-equal` becomes `seon.packages.js.fast-deep-equal`, and
`@scope/pkg` becomes `seon.packages.js.scope.pkg`. The inverse removes the
prefix, splits on `.`, decodes every `_hh` byte sequence, UTF-8 decodes each
segment, and reconstructs `name` for one segment or `@scope/name` for two.
Encoding `_` and `.` themselves makes the mapping byte-stable and unambiguous;
any other segment count is invalid. The CLJS resource path applies the ordinary
hyphen-to-underscore filename mapping only after this namespace mapping.

## Wrapper source

The owned source is
`data/clusters/pkg-wrapper-exemplar/packages/corpus/seon/packages/js/fast_deep_equal.cljs`:

```clojure
(ns seon.packages.js.fast-deep-equal
  "Compare JSON-like maps with the `fast-deep-equal` npm package."
  (:require [seon.schema :as schema]))

(schema/register! ::left :map)
(schema/register! ::right :map)
(schema/register! ::compare-request
  [:map {:closed true} [::left ::left] [::right ::right]])
(schema/register! ::module-request
  [:map {:closed true} [::module [:= :fast-deep-equal]]])
(schema/register! ::steering-error
  [:map {:closed true}
   [:seon.error/kind [:= :unserializable-value]]
   [:seon.error/message [:string {:min 1}]]])
(schema/register! ::compare-response [:or :boolean ::steering-error])

(defn equal?
  "True when two JSON-like maps have equal recursive values."
  {:malli/schema [:=> [:cat ::compare-request] ::compare-response]}
  [{::keys [left right]}]
  (try
    ((js/require "fast-deep-equal") left right)
    (catch :default error
      {:seon.error/kind :unserializable-value
       :seon.error/message
       (str "Keep package objects tier-local, extract data, or call the "
            "owning capability function; fast-deep-equal failed: "
            (.-message error))})))

(defn module-export
  "Steering for the package's non-serializable function export."
  {:malli/schema [:=> [:cat ::module-request] ::steering-error]}
  [_request]
  (let [export (js/require "fast-deep-equal")]
    (if (fn? export)
      {:seon.error/kind :unserializable-value
       :seon.error/message
       (str "Keep the fast-deep-equal function tier-local by result symbol, "
            "extract data, or call seon.packages.js.fast-deep-equal/equal?.")}
      {:seon.error/kind :unserializable-value
       :seon.error/message
       "The fast-deep-equal module export was not a callable function."})))

```

The request maps are closed, all keys are namespaced, public functions have
Malli schemas, and failures are flat steering data. The module export remains
tier-local; `module-export` demonstrates the exact error an attempted crossing
must return rather than stringifying or dropping the function.

## Loader stop and live evidence

The shortest intended live form was:

```clojure
(require '[seon.packages.js.fast-deep-equal :as equal])

```

It cannot currently reach wrapper compilation from the package file:

1. `script/seon/dev/cluster.clj:75-95` materializes only
   `npm/package.json` and `deps.edn`; it never scans `packages/corpus`.
2. `src/seon/execution.cljs:342-356,669-708` acquires namespace sources only
   when their source transaction has REPL process provenance.
3. `src/seon/execution/runtime.cljs:648-681` hands that acquired map to the
   CLJS eval boundary.
4. `src/seon/eval.cljs:818-885` can load compiled namespaces or entries in
   that database-derived authored-source map; otherwise line 885 rethrows
   `ns ... not available`.

Directly evaluating the wrapper source into the agent would manufacture REPL
provenance and conceal the missing installation/corpus door, so it was not
done. The defect is recorded in
`docs/seon/issues/cluster-package-corpus-has-no-loader-door.md`.

The attempted live operator transcript is
`tmp/orchestrator/pkg-operator-up.log`. Before the isolated pod could be
opened, the canonical default pod failed current-schema admission because its
retained database has `:seon.config/agent-context`,
`:seon.config/context-profiles`, and `:seon.config/root-context` installed as
cardinality-one strings rather than current cardinality-many component refs.
No shared/default reset was performed. Subsequent cluster-open attempts are in
`tmp/orchestrator/pkg-live-agent.log`; concurrent source publication also made
the shared watcher/writer unavailable. These operational failures prevented an
actual agent transcript, but the loader's missing input door is independently
established by the complete source chain above.

## Evidence files

- `tmp/orchestrator/pkg-install.log` — Bun install and resolved package tree.
- `tmp/orchestrator/pkg-package-probe.log` — package data results and function export.
- `tmp/orchestrator/pkg-operator-open.log` — first cluster-open prerequisite failure.
- `tmp/orchestrator/pkg-operator-up.log` — build/start attempt and default schema failure.
- `tmp/orchestrator/pkg-live-agent.log` — isolated cluster attempts and unavailable shared owners.
- `data/clusters/pkg-wrapper-exemplar/packages/npm/bun.lock` — resolved lock.

The native-binding stretch was not attempted because the pure-JS path did not
reach a live agent and therefore was not smooth.
