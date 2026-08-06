---
type: research
status: complete
tags: [research, schema, runtime]
---

# Parse-forms entry boundary (2026-07-20)

## Ruling

The Stage 5 implementation named by
[[../../../seon/issues/parse-forms-entry-schema-and-bare-keys]] is already
landed. No parser, eval, render, diffusion, or worker source edit remains for
that issue's stated acceptance boundary:

- `150bb6036` made the public parse-entry envelope uniformly
  `:seon.repl/*`, moved read failures onto the one `:seon/error` value, and
  migrated every maintained consumer in the same atomic refactor;
- `3a0dbd31` added the requested public schemas: the exact fence-strip
  string boundary and a closed, discriminator-driven union for form, read,
  and comment entries; and
- `58fb020d` restored the Babashka parser gate without adding Malli or another
  parser to the portable runtime owner.

Current source at audit HEAD `4f4dbd95` retains all three changes. Both focused
gates were rerun against the current shared tree: `bin/test-parser` passed 46
tests / 368 assertions, and
`bin/test-cljs --test=seon.repl.internal-test` passed 46 / 369 with zero
compiler warnings. The later frozen-source CLJS checkpoint at `286180f7`
contains all three commits and passed 1,331 tests / 6,151 assertions with zero
failures, errors, or warnings. It is not a final program graduation run, but it
does satisfy this issue's historical full-CLJS acceptance evidence.

The issue note is therefore stale-open bookkeeping. Its integration unit
should move it to `docs/seon/issues/archive/`, record `150bb6036`, `3a0dbd31`,
and `58fb020d` with the current focused reruns, and change the Stage 5 triage row
from scheduled to closed. Reimplementing or renaming any parser mechanism would
be a regression.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding | Consequence |
|---|---|---|---|
| rewrite-clj | runtime `1.2.51`; mirrored source `60782e501aaf312cb90c9ff0bee05d5da5125563` | `reference-code/rewrite-clj/src/rewrite_clj/parser.cljc:14-41` shows `parse-string` returns exactly the next source node while `parse-string-all` is a distinct all-elements operation | `seon.repl.internal/try-parse-one-token` remains the one token-at-a-time parser. Do not add a schema-specific reader or rebuild entry validation from text. |
| Malli | runtime `0.20.0`; mirrored source `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli/src/malli/core.cljc:1861-1901` dispatches `:multi` through the declared dispatch property and rejects an unknown dispatch value; each child is the selected validator | Dispatch on `:seon.repl/kind`, use one closed child map per entry family, and keep the union exhaustive rather than weakening it to `[:vector :map]`. |
| Portable parser owner | `src/seon/repl/internal.cljc` | The namespace requires only `clojure.string` and rewrite-clj and is loaded directly by `bin/oracle-server` / `bin/test-parser` | Schema forms may live in function metadata because they are inert data here; the namespace must not require `seon.schema` or Malli at runtime. |
| Registered envelope attributes | `src/seon/repl.cljs:61-75` | `seon.repl`, not its portable `.internal` machinery, registers `:seon.repl/kind`, `ok?`, narration, source, and span | Do not create `seon.repl.entry`, duplicate registrations, or transact the in-memory parse envelope wholesale. The owning data namespace is already `seon.repl`. |
| Eval consumer | `src/seon/eval.cljs:4990-5423` | The one eval-batch path consumes `:seon.repl/kind`, narration, source, eval-source, form, and the nested `:seon/error`; repair reparses through the same `parse-forms` owner | There is no compatibility adapter or legacy bare-key branch to delete. A future entry field must be added to the same producer, exact union, and consumers together. |
| Structural downstream consumers | `src/seon/diffusion/oracle.cljs`, `src/seon/diffusion/retrieval.cljs`, `src/seon/worker_validator.cljs` | Each reads the qualified entry contract from the same parser result; JS boundary maps in the validator are outbound foreign-interface data, not parse entries | Do not confuse consumer-local result keys or JS object fields with the parse-entry envelope. |

## Exact current contract

`strip-code-fences` carries the issue-requested
`[:=> [:cat :string] :string]` metadata and preserves its byte behavior.
`parse-forms` returns a vector dispatched by `:seon.repl/kind`:

- `:form` is a closed map with kind, narration, byte-faithful source, optional
  rewritten eval source, the rewrite-clj sexpr, and a non-negative offset
  tuple;
- `:read` is a closed map with kind, literal false `:seon.repl/ok?`,
  narration, source, optional eval source, one closed `:seon/error` carrying
  the six classified read kinds plus its message, and the offset tuple; and
- `:comment` is a closed map with only kind and narration.

The single `:any` is `:seon.repl/form`, the honest third-party reader-value
boundary. It may be a list, symbol re-reference, metadata-bearing form, or
another reader-produced value before the parser's evaluability policy is
applied. Replacing it with a guessed authored-data schema would be less exact.
Parse failures remain ordinary entry data; no schema work should introduce a
second error envelope or throw into the eval loop.

## Historical implementation boundary and deletions

The namespacing commit was correctly atomic because parse-entry keys cross
several source owners. It changed the producer, `seon.eval`, diffusion
retrieval/oracle, worker validation, the portable oracle server, and maintained
tests together. The old bare entry keys and the old split
`:error` / `:error-kind` read-failure fields no longer have a maintained
producer or compatibility consumer. Repository inspection finds no legacy
parse-entry adapter to remove.

The schema commit then strengthened the existing owner in place. It did not
move parsing into `seon.repl`, add a schema-validation traversal, or require
Malli in the Babashka path. That division is the one-mechanism design to keep.

## Focused falsifiers and closure evidence

The smallest durable checks for this boundary are:

1. Introspect both public vars and require the exact fence-strip function
   schema plus `:vector` of `:multi` with precisely
   `#{:form :read :comment}` branches.
2. Parse a normal form and require byte-identical
   `:seon.repl/source`; this catches a metadata/schema edit that accidentally
   changes parser behavior.
3. Parse a broken form and require a qualified read entry with literal false
   `:seon.repl/ok?`, one nested `:seon/error`, a classified error kind, and a
   source-aligned span.
4. Validate the schema against representative form, read, and comment entries;
   add one unknown key and one unknown kind and require rejection. This latter
   negative validation is implicit in the closed schema today but should be
   added if the issue archive requires direct behavioral proof of closure.
5. Run both `bin/test-parser` and the focused CLJS namespace. The Babashka gate
   proves the portable owner stayed free of runtime Malli dependencies; CLJS
   supplies the transport-specific assertion omitted on the portable path.

Evidence already present is sufficient to close the historical issue:

- the key migration and one-error-value conversion are commit `150bb6036`;
- the precise closed schema and schema-introspection regression are
  `3a0dbd31`;
- the portable gate repair is `58fb020d`;
- current focused reruns are 46 / 368 and 46 / 369, both green; and
- frozen full CLJS evidence containing those commits is `286180f7`, 1,331 /
  6,151 green with zero warnings.

## Independent residual: the option map

One separate bare-key conformance defect remains adjacent to, but outside, the
issue's enumerated parse-entry contract: `parse-forms` still accepts
`{:strip-fences? boolean}` and its new function schema explicitly preserves
that bare option key. Maintained production callers exist in diffusion
retrieval/oracle and `worker_validator.cljs`, plus tests. This is a public
optional argument map, so it conflicts with the current no-bare-map-key rule
even though every emitted entry key is fixed.

Do not keep the historical issue open ambiguously for this newly isolated
fact. Before archiving it, create one found-problem note for the option contract
and rule either:

- migrate it atomically to `:seon.repl/strip-fences?` across the parser schema,
  destructuring, every maintained caller, tests, and documentation; or
- explicitly document why this control map is a sanctioned seam exception.

The first option is the data-rule-consistent recommendation. It is a small
follow-on in the same owner, but it is not evidence that the original entry
envelope/schema implementation is missing.

## Ownership and U4 overlap

The parser unit owns `src/seon/repl/internal.cljc`, `src/seon/repl.cljs`, the
qualified parse-entry reads in `src/seon/eval.cljs`, diffusion and worker
callers, and their focused tests only when an actual contract migration is
authorized. The current U4 dirty paths are JVM host/database identity work
(`src/seon/host*.clj`, `src/seon/db/id.cljc`, and the host conformance test), so
there is no present parser-path overlap. This audit intentionally made no
source, issue, or shared-roadmap edit.
