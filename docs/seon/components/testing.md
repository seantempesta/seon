---
type: component
status: active
tags: [component, schema, database]
---

# Testing

Tests follow the active runtime boundary. There is no all-JVM application
suite, nREPL test runner, `user/run-tests`, or fallback `bin/test` path.

## Focused doors

| Change area | Command |
|---|---|
| Paths changed during development | `bin/seon test changed --path PATH` |
| One CLJS namespace or var | `bin/test-cljs --test=seon.example-test` |
| Same CLJS target, existing bundle | `bin/test-cljs --no-build --test=seon.example-test/example` |
| Full CLJS checkpoint | `bin/test-cljs` |
| Retained JVM database server | `bin/test-writer` |
| One JVM database-server namespace | `bin/test-writer seon.db.writer-integration-test` |
| Public focused pod gate | `bin/seon test pod seon.example-test` |
| Public focused database gate | `bin/seon test database seon.db.writer-integration-test` |
| Operator, Markdown, and docstring gate | `bin/seon test operator` |
| Complete checkpoint | `bin/seon test all` |

`bin/test-cljs` compiles the Shadow `:node-test` target and requires a real
cljs.test completion summary; a process exit without the summary is an
incomplete failure, not a pass. Focused selectors become Shadow's compile-time
`:namespaces` input, so unrelated test namespaces do not enter the bundle. The
one output bundle is protected by a compile-plus-run owner lock. `--no-build`
works only when a content fingerprint proves the namespace selection,
source/config/dependency inputs, and downstream build flavor match the artifact;
otherwise it fails loudly and requests a build. Each database test owns a fresh
connection or explicit fixture. Async tests await the actual Promise and always
complete the `cljs.test/async` continuation.

`bin/test-writer` loads an explicit list of retained JVM namespaces through the
`:writer:writer-test` basis. It does not discover or load the archived JVM
application.

`bin/seon test` is the public operator surface. It delegates to those two
canonical runners rather than implementing another harness. The `operator`
target runs the Babashka lifecycle, artifact, Markdown, and docstring behavior;
`all` runs operator, database, then pod gates and stops on the first failure.

Run the smallest gate that can falsify the change while debugging, then the
relevant batch checkpoint once at the unit boundary. Behavioral invariants and
edge cases are the contract; tests that pin agent prose are not.

The inner loop is one changed-test operation backed by the managed Shadow
compiler. A successful test build publishes an immutable artifact and a
manifest containing its source fingerprint and compiler dependency graph. The
operation waits for an exact current manifest, selects the conservative
reverse-transitive namespace closure, and runs a fresh bounded Node process.
The existing edit hook calls that same operation and returns its concise result
through trusted `PostToolUse.additionalContext`; it does not own a second
selector or runner. Unknown, macro, shared CLJC, configuration, dependency, and
deletion changes widen explicitly. Exact function-level automatic selection is
deferred until the analyzer can prove complete call edges.

The artifact is the complete dev-mode runtime, not only Shadow's small
`test.js` launcher. Runtime files live once in a content-addressed object store;
bounded bundle directories compose them with symbolic links and a rewritten
local import path. A watcher flush therefore cannot mutate a running test.
Changed-test failures are advisory feedback, because deleting an obsolete test
can be the correct outcome of a refactor. Checkpoint gates retain ordinary
failing exit status.

For live proof after tests, inspect the database or page produced by the same
runtime path. A passing unit test alone does not establish that a transaction
replicated or a Datastar morph reached a browser.
