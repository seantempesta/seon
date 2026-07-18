---
type: research
status: completed
tags: [research, component, cljs, flow]
---

# No-source program artifact audit

## Question and conclusion

What is the smallest existing Seon mechanism that lets production stop
packaging `src/`, `test/`, and `guest-cljs/` while preserving exact function,
schema, test, and namespace behavior?

Keep the existing `seon.client/index-core!` and writer reconciliation. Replace
only their file acquisition with one immutable program-source artifact emitted
after Shadow's successful client flush. The artifact is an ordinary map from
Shadow's classpath-relative resource names to source strings. Its digest joins
the existing application artifact identity, and the immutable runtime root
contains the file instead of source-directory symlinks.

No database protocol operation, second program representation, generated
bootstrap authority, or production test bundle is needed.

## Dependency ledger

- Shadow CLJS is the maintained fork at
  `4e72595f57618f5c43388ad13d5136cd3bede566` in
  `reference-code/shadow-cljs`.
- The relevant hook dispatcher is
  `reference-code/shadow-cljs/src/main/shadow/build.clj`:
  `configure-hooks-from-config` loads hook vars, reads their
  `:shadow.build/stage` metadata, and `execute-hooks` calls the configured hook
  after the target stage.
- Shadow's build state initializes `:sources` in
  `reference-code/shadow-cljs/src/main/shadow/build/api.clj`. The map contains
  the resolved resource closure used by the completed build.
- `reference-code/shadow-cljs/src/main/shadow/build/node.clj` writes the
  `:node-script` output during `:flush`. The name describes the CommonJS output
  target; it does not require the Node executable.
- Seon's proven flush-hook exemplar is
  `script/seon/dev/test_artifact.clj`. Its `publish!` hook runs at
  `:shadow.build/stage :flush`, projects `state[:sources]`, publishes immutable
  files, and atomically updates a manifest.
- The bootstrap target is configured in `shadow-cljs.edn` and consumed by
  `src/seon/eval.cljs` plus `src/seon/eval/bootstrap_cache.cljs`.

## Existing data flow

The intended flow already exists after source acquisition:

```text
Shadow successful client flush
  -> immutable program-source artifact
  -> seon.client/index-core!
  -> seon.client/database-initialization
  -> :seon.db/program
  -> seon.db.writer/initialize-program!
  -> program/compile-tx-data
```

### Current source producers

`src/seon/indexing.clj` derives the compiled first-party boundary at compile
time:

- `public-fn-vars` emits every public function var in `seon.client`'s
  first-party transitive require closure; and
- `first-party-ns-strs` emits every first-party namespace in that closure,
  including namespaces with no public functions.

`src/seon/client.cljs` currently turns those build facts into program rows:

- `read-src-file` synchronously searches `src`, `test`, `guest-cljs/src`, and
  downstream `src` and `test` roots;
- `var->fn-row` reads the file from var metadata and extracts the form at its
  `:line`;
- `read-ns-source` reads complete source for namespaces that must retain it;
- `extra-src-ns->source` separately walks a downstream checkout;
- `index-core!` produces namespace and function rows;
- `index-schemas` produces registered schema rows; and
- `database-initialization` removes wall-clock attributes, sorts rows by their
  existing identity attributes, validates the schema/function projection, and
  returns the database initialization value.

### Current protocol and writer consumers

`seon.client/open-database-session!` passes the existing initialization value:

```clojure
{:seon.execution/artifact-digest "..."
 :seon.db/attributes [...]
 :seon.db/program [...]
 :seon.db/initial-data [...]}
```

`src/seon/db/writer.clj` consumes it through `initialize-connection!`,
`initialize-program!`, `program/compile-tx-data`, `missing-initial-data`, and
`transact-initialization!`. The writer already compares the desired program
with one immutable database value and transacts only the required difference.

After initialization, source strings remain ordinary database facts consumed
by:

- `seon.eval/namespace-source`, `authored-sources`, and
  `reconstitute-ns-source`;
- execution-child program queries in `src/seon/execution.cljs`;
- namespace rendering in `src/seon/agent/ctx/namespaces.cljs`; and
- agent-authored rendering in `src/seon/render/sci.cljs`.

None of those consumers should learn about the artifact file.

## Ordinary database row shapes

The program-source artifact supplies strings to the existing row builders. It
does not define another row shape.

Namespace row:

```clojure
{:seon.ns/name :my.data
 :seon.ns/source "(ns my.data ...)"
 :seon.ns/require-edges [...]}
```

Function row:

```clojure
{:seon.fn/sym "my.data/query"
 :seon.fn/ns [:seon.ns/name :my.data]
 :seon.fn/source "(defn query ...)"
 :seon.fn/arglists [...]
 :seon.fn/doc "..."
 :seon.fn/private? false
 :seon.fn/fn-var? true
 :seon.fn/spec "..."}
```

Schema row:

```clojure
{:seon.schema/key :my.data/request
 :seon.schema/form "..."
 :seon.schema/ns {:seon.ns/name :my.data}}
```

Agent-authored test row:

```clojure
{:seon.test/sym "my.agent/example-test"
 :seon.test/ns {:seon.ns/name :my.agent}
 :seon.test/source "(deftest example-test ...)"}
```

## Program-source artifact shape

Publish one deterministically ordered file adjacent to the flavor-owned client
output:

```clojure
{:seon.dev.artifact/program-sources
 {"my/data.cljs" "..."
  "seon/client.cljs" "..."
  "seon/test/runner.cljs" "..."}}
```

The canonical application manifest records:

```clojure
{:seon.dev.artifact/program-source-path
 "out/client/program-sources.edn"
 :seon.dev.artifact/program-source-digest "<sha256>"}
```

The path is relative inside the immutable runtime root. The digest contributes
to `:seon.dev.artifact/application-digest`. Resource names are Shadow's
classpath-relative producer values because those directly match the `:file`
consumer value in ClojureScript var metadata.

Sort by resource name before serialization. Publication is atomic and happens
only after a successful client flush. Do not add an ambient path selector.

## Exact production source selection

For Seon's client artifact, include:

- `.cljs` and `.cljc` resources in the successful client build;
- only files canonically contained by the Seon project root;
- every compiled first-party namespace, including one with no public function;
  and
- the complete source needed by `var->fn-row` and `ns-row`.

Exclude:

- jar and Git dependency sources;
- `.clj` macro implementation files that execute only in the compiler;
- the independent Shadow `:test` build closure;
- checked-in platform tests not required by the production client;
- documentation, evaluation runs, retired tools, and generated outputs; and
- any source reached through a symlink outside an admitted source root.

A downstream client build also includes `.cljs` and `.cljc` files beneath its
explicitly declared source root. To preserve today's downstream behavior, its
declared `src` and `test` roots may include unspecced-only namespaces even when
they are not in Shadow's compiled closure. They remain consumer program source,
not Seon platform tests.

## Checked-in test exclusion

No root `test/**/*.cljs` namespace is required by the production client
bundle. `seon.client` requires `seon.test.runner`, but that production namespace
is `src/seon/test/runner.cljs` and therefore joins the ordinary client closure.

Agent-authored tests are created by eval and stored as `:seon.test/source`.
They reconstruct from database rows without a packaged test directory.

The Shadow `:test` build and `test/seon/test/node_preload.cljs` are correctness
test artifacts only. They must not enter the production runtime artifact. A
downstream project may deliberately ship its own tests through its own
program-source artifact.

## Bootstrap is a separate artifact

The complete `out/bootstrap` directory remains required. It currently contains
about 16 MiB of:

- `index.transit.json`;
- `ana/*.transit.json` analyzer caches;
- per-namespace compiled JavaScript; and
- macro namespace output.

`seon.eval/init-bootstrap!` calls Shadow's bootstrap loader, then
`seon.eval.bootstrap-cache/load-all!` loads every emitted analyzer cache.
Packaging only the bootstrap index is insufficient.

Bootstrap and program source solve different problems:

- bootstrap supplies analyzer state and compiled dependency code; and
- program source supplies first-party namespace, function, and test source
  strings.

Neither is a replacement for the other, and neither should be generated from
database history.

## Required runtime members for this cut

- client output and its Shadow runtime closure;
- execution-child output and its Shadow runtime closure;
- complete `out/bootstrap`;
- program-source artifact;
- writer jar;
- manifest-bound Bun executable identity; and
- bounded config and static resources owned by the adjacent package work.

Source-directory symlinks are not runtime members.

## Deletion inventory

After parity proof, update `script/seon/dev/artifact.clj`:

- remove `src`, `test`, and `guest-cljs` from `runtime-root-links`;
- copy the program-source file into the immutable runtime root; and
- verify its digest with bootstrap and execution output.

Retain `resources` only until the bounded static-resource cut replaces that
last directory link.

Update `src/seon/client.cljs`:

- make `read-src-file`, `read-ns-source`, and `extra-src-ns->source` consume
  the admitted program-source map;
- delete probing of `src`, `test`, `guest-cljs/src`, and downstream runtime
  source directories;
- delete repeated synchronous file reads per function; and
- delete recursive downstream runtime source walking.

Retain `extract-form-at-line`, `extract-form-at-index`, `var->fn-row`, `ns-row`,
`defn-rows-from-source`, `index-core!`, `index-schemas`, and
`database-initialization`. These already implement the desired semantics.

Rewrite source-indexing tests to supply a temporary program-source artifact
instead of relying on repository working-directory files. Remove only tests
that require production to find checked-in source roots; preserve behavioral
assertions about exact source, arglists, schemas, require edges, and
deterministic rows.

## Dependency-ordered implementation plan

1. Add a pure Shadow `:flush` hook modeled on
   `seon.dev.test-artifact/publish!`. It projects first-party source strings
   from successful client build state and atomically publishes deterministic
   bytes.
2. Add focused hook tests with synthetic Shadow state. Prove selection,
   containment, stable ordering and digest, changed-source identity, and
   outside-root refusal.
3. Configure `:client` and `:acme-client` to use the same hook. Do not add it
   to test, bootstrap, execution, or worker builds.
4. Add program-source path and digest to the application manifest, client
   digest, and application digest.
5. Copy and verify the file in `publish-runtime-root!`. Readiness fails when it
   is missing or changed.
6. Load the map once for the admitted artifact and make `read-src-file` answer
   by the existing classpath-relative file name.
7. Move downstream source acquisition onto the same map while preserving
   reserved namespace checks and existing function-row semantics.
8. Compare the complete normalized `index-core!` output before and after the
   cut, excluding only existing wall-clock attributes.
9. Remove source, test, and guest source links.
10. Assemble a temporary no-source runtime, make the producer checkout
    inaccessible, initialize an empty database, reopen it with no program
    change, and evaluate one cross-namespace function in an execution child.

## Highest-risk falsifiers

- A public function whose metadata names a `.cljc` file retains exact source,
  arglists, docstring, spec, and namespace ref.
- A compiled namespace with no public functions still receives its namespace
  row.
- Full-source `my.*`, configured `:seon.config/always`, and downstream
  namespaces retain complete source and require edges.
- `src/seon/test/runner.cljs` remains indexed as production code.
- No root test namespace appears merely because `test` is on the compiler
  classpath.
- Client output and program source cannot publish mismatched digests.
- A missing or changed program-source file fails before database
  initialization.
- Reopening an unchanged database transacts no compiled program rows.
- Agent-authored `:seon.test/source` reconstructs without a packaged test
  directory.
- A symlink or resource outside an admitted source root is rejected before
  artifact publication.
- With the producer checkout inaccessible, bootstrap initialization,
  namespace rendering, cross-namespace eval, schema activation, and execution
  child startup all remain successful.

The decisive simplification is narrow: replace source acquisition, then delete
the directory links. The existing deterministic `:seon.db/program`, database
initialization request, and writer reconciliation remain the one program
mechanism.
