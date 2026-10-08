---
title: "FK-KONDO: One clj-kondo fork commit (arglists as data, resolved configuration)"
type: reference
status: ready
created: 2026-10-08
---

# FK-KONDO: One clj-kondo fork commit

Ledger row FK-KONDO (group E). Owner answer AU-Q11 option 2 (expose exact bindings from the fork's existing parse),
plus PB-12a (carded in [PB-12](PB-12.md)). Consumers: UL-19 (signatures), PB-12b. Model: Sonnet. Host-bound: throwaway
check for the bump.

## Where the fork lives (verified at Seon `1fcdbf47a`)

- `deps.edn:17-18`: `clj-kondo/clj-kondo {:local/root "reference-code/clj-kondo" …}`, a **local root** submodule.
- `reference-code/clj-kondo` at `57252e07`, remote `origin` = `git@github.com:seantempesta/clj-kondo.git`. Commit,
  push to `origin`, bump the gitlink.

## What Seon re-parses today

`seon.fn.signature/function-signatures` (`src/seon/fn/signature.cljc:124-147`) reads the definition's source text
again with a `LispReader$Resolver` (`:16-31`) to get each arity's binding vector, then checks it against
clj-kondo's `:arglist-strs` re-read from strings (`:139-145`). clj-kondo already holds each arity's parsed argument
vector: `analyzer.clj:676` assocs `:arglist-str (str arg-vec)` from the node, and `reg-var!` stores
`:arglist-strs` (`analyzer.clj:1079-1082`; from `:arglists` metadata first, `meta-node->arglist-strs` `:698`).

## Change in the fork

1. **Arglists as data.** Under the existing `:analyze-arglists?` (the `{:analysis {:arglists true}}` option), beside
   `:arglist-str` also assoc `:arglist` = the argument vector's s-expression (`clj-kondo.impl.utils/sexpr` of
   `arg-vec`, `analyzer.clj:676`), and in `reg-var!` add `:arglists` (the bodies' `:arglist` in order) and
   `:arglists-from-meta? true` when the `:arglists` metadata supplied the strings (`meta-node->arglist-strs` won).
   `analysis.clj:92-99` passes `:arglists` and `:arglists-from-meta?` through `select-some` to `:var-definitions`.
   Auto-resolved keywords in destructuring (`::k`, `::alias/k`) must come out resolved against the namespace's
   aliases, as Seon's resolver does; check `sexpr` on a `{:keys [::a/b]}` binding and resolve through the ns
   context's `:qualify-ns` if `sexpr` leaves it unresolved.
2. **PB-12a**: `clj-kondo.core/run!` accepts `:resolved-config` ([PB-12](PB-12.md) "PB-12a", `core.clj:139`).

## Fork tests (`reference-code/clj-kondo/test/clj_kondo/analysis_test.clj`)

- `(defn f ([a] a) ([a & {:keys [::x/y]}] a))` in a namespace aliasing `x` → `:arglists` is
  `[[a] [a & {:keys [:the.x.ns/y]}]]`, equal to what `clojure.core/read-string` gives under that alias.
- A `defn` with `{:arglists '([x y])}` metadata: `:arglists [[x y]]` and `:arglists-from-meta? true`.
- PB-12a's two tests ([PB-12](PB-12.md)).
- The fork's suite: `clojure -M:test` (read `reference-code/clj-kondo/deps.edn` for the alias) green.

## Seon side (the bump commit)

- Files: the gitlink `reference-code/clj-kondo`. The analysis cache key: read `seon.fn.analyzer` for a version
  stamp the cache keys on (clj-kondo's version string or the gitlink); stale cached analyses must not lack the new
  keys. If the cache keys on the clj-kondo version, bump the fork's version resource in the same fork commit.
- After the bump, run `(seon.fn.analyzer/analyze-dependencies!)` at the restarted JVM's REPL (root, CLAUDE.md "Trust
  analysis only when current").
- Proof: `seon.fn.analyzer-test`, `seon.fn.publication-test`, `seon.fn-test` green (nothing reads the new keys yet).
- `--test seon.fn.analyzer-test --test seon.fn.publication-test --test seon.fn-test`
- Commit: `bin/lane commit <lane> -m "FK-KONDO: clj-kondo fork reports arglists as data and accepts a resolved configuration" --test seon.fn.analyzer-test --test seon.fn.publication-test --test seon.fn-test reference-code/clj-kondo`

## Cost

Analysis: one `sexpr` per arity already parsed (negligible). UL-19 then deletes about 200 lines of re-parse.

## Deleted

Nothing in this row.

## Dependencies

None for the fork. UL-19 (after UL-1c) and PB-12b wait on this row.
