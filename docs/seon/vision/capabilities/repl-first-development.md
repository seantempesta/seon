---
type: capability
status: not-started
tags: [vision, agent]
---
# REPL-First Agent Development

Agents develop exclusively through REPL eval, not file editing. The eval pipeline validates contracts before accepting forms, a live cockpit renders namespace state, and `persist!` graduates evaluated forms to disk. The REPL is the sole interface; the filesystem is a persistence format.

## Namespace-Scoped Agent REPLs

Each namespace agent operates through a Claude Code SDK instance with a REPL scoped to its namespace. The agent can only eval forms in its own namespace -- it sees its functions, its schemas, its dependencies, and its test results. Cross-namespace calls go through `topology/request!`, enforcing the isolation boundary.

Spinning up a namespace agent means: create a Claude Code instance, provide its namespace context (functions, schemas, dependents, notifications), and give it a restricted nREPL connection. The agent develops interactively -- eval a form, see the result, iterate. The eval pipeline validates each form before accepting it. The agent never touches the filesystem directly.

## Custom REPL Pipeline

The REPL pipeline is not standard nREPL eval. Every form is parsed, validated, and processed through a custom pipeline:

1. **Parse** -- read the form into a Clojure data structure.
2. **Validate** -- check schema presence, concrete types, map-in/map-out (for `defn`). Reject invalid forms with clear errors.
3. **Compile and execute** -- standard Clojure compilation.
4. **Transact to graph** -- function metadata (name, schema, dependencies, docstring) persisted to Datalevin.
5. **Persist to disk** -- the source form written to the `.clj` file, keeping filesystem and database in sync.
6. **Run affected tests** -- schema-based test selection, not file-based.

Both the database and the filesystem stay current. The database is the queryable source of truth; the filesystem is the persistence format that version control tracks. Real Clojure is written to disk, compiled, and run -- no interpreted subset or DSL.

## What Exists

Nothing beyond the partial eval pipeline (see [[capabilities/repl-eval-pipeline]]). The constraint enforcement, cockpit rendering, graduation workflow, and namespace-scoped agent REPLs are not built.

## Gaps

- Eval pipeline does not enforce schema presence, concrete types, or map-in/map-out
- No `*ctx*` cockpit rendering live namespace state
- No `persist!` / graduation from eval to disk
- File editing is still the primary development mode for agents
- No Claude Code SDK integration for namespace-scoped agents
- No custom REPL pipeline (forms go through standard nREPL, not the validation chain)

## Related

- Components: [[components/dev-tools]], [[components/agent-system]], [[components/harness]]
- Concepts: [[concepts/namespace-as-process]], [[concepts/progressive-enhancement]]
- PRDs: [[prds/agent-repl-interface/prd]]
