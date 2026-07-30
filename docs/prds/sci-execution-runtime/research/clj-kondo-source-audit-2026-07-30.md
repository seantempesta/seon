---
type: research
status: active
tags: [research, sci, clj-kondo, code-admission]
---

# clj-kondo source audit — 2026-07-30

## Question and verdict

This audit asks where clj-kondo belongs between an agent reply and SCI
evaluation, what the pinned clj-kondo can actually supply, and which existing
Seon mechanisms it can simplify.

The narrow result is:

- `seon.sci.admit` is not a source-admission seam. It bounds and serializes a
  value *after* `sci/eval-form` (`src/seon/sci/eval.clj:785-792,883-894`;
  `src/seon/sci/admit.clj:304-354`).
- The only coherent source-analysis seam is in `freeze!`, after
  `reply/sources` has produced exact ordered sources and before `run/plan-tx`
  digests and commits them (`src/seon/cluster/loop.cljc:696-721`). A finding
  may refuse the whole candidate or cause a pure whole-candidate correction;
  it must never rewrite or splice a committed plan.
- clj-kondo should run with the full dependency/classpath cache as its
  **resolution context**, while Seon's database program graph publishes only
  the **first-party projection** from `src/` and `test/`. Cached dependency
  definitions make findings and call resolution accurate; they do not become
  `:seon.fn`, `:seon.ns`, `:seon.schema`, or `:seon.test` rows.
- The current cross-agent “repair” path routes a failed receipt to an owner and
  later calls any successful owner response `:owner-fixed`; it does not link a
  replacement declaration to the failed source or retry the original frozen
  form. That is settlement messaging, not a source-correction loop
  (`src/seon/problems.clj:152-222`; `src/seon/cluster/work.cljc:243-321`).
- The vendored clj-kondo can replace duplicate static parsing, invocation, and
  dependency extraction. One thin normalized Seon projection should remain as
  the sole wrapper. clj-kondo cannot replace Seon's exact SCI reader,
  capability policy, schema admission, `current-src` database publication, or
  test-runner ownership rules.

## Dependency ledger

| Dependency or mechanism | Selected revision | Evidence and role |
|---|---:|---|
| clj-kondo | `v2026.07.24`, `794a508d53df319bfb2f4db666315de6a3e56fff` | `deps.edn:17-21`; vendored `reference-code/clj-kondo`. Static findings, namespace/var analysis, dependency caches, hook configuration. |
| clj-kondo JVM API | same | `reference-code/clj-kondo/src/clj_kondo/core.clj:67-106`. `run!` accepts paths/classpaths, stdin, filename, cache, config, and parallel options and returns findings, summary, config, and optional analysis. |
| clj-kondo cache | same | `reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:16-36,65-79,83-144,210-215`. Per-namespace Transit cache, built-in fallback, process/thread locking, required-namespace loading. |
| clj-kondo analysis | same | `reference-code/clj-kondo/analysis/README.md:14-60,77-142`. Namespace/var definitions and usages plus opt-in locals, keywords, protocol, Java, metadata, and call data. |
| SCI reader | repository source | `src/seon/sci/reader.cljc:100-118,442-565,567-636`. The one accepted-source reader and exact-source/namespace-attribution authority. |
| SCI evaluator | repository source | `src/seon/sci/eval.clj:368-400,752-800,878-910`. Re-reads one frozen source against the actual SCI namespace bindings, evaluates it, then admits the result value. |
| reply freeze | repository source | `src/seon/cluster/reply.cljc:310-352`; `src/seon/cluster/loop.cljc:696-721`. Converts the complete reply into exact ordered sources, then commits one immutable plan. |
| static program index | repository source, concurrent worktree edits observed | `src/seon/fn/analyzer.clj:9-16,94-128`; `src/seon/fn.clj:15-17,207-224`. Runs clj-kondo over explicitly selected source paths, refuses error-level findings, and builds first-party rows. |
| changed-test selector | repository source, concurrent worktree edits observed | `script/seon/dev/changed_test.clj:173-230,235-304`. Owns a broader host corpus and currently shells out to clj-kondo after optionally warming the dependency cache. |

The public artifact and the vendored tag agree. This audit used the vendored
source rather than inferred behavior from the command-line interface.

### Prior failure archaeology

`docs/seon/issues/archive/dup-kondo-analysis.md:8-25` records the old failure:
three namespaces independently wrapped clj-kondo and diverged. The target in
this report does **not** authorize one wrapper per consumer. One canonical
analysis operation owns invocation, explicit config/cache selection, normalized
findings, normalized graph facts, and source-input identity. Program indexing,
changed-test selection, the edit hook, and candidate lint may retain different
corpus and policy owners, but those owners pass data to that operation; they do
not shell out, call `run!`, or normalize clj-kondo maps themselves.

`docs/seon/issues/archive/post-edit-hook-hid-invalid-multifile-sources.md:8-44`
records a second failure: one invalid result in a multi-file edit could silently
suppress feedback and changed-test enqueueing. A shared analyzer must accept a
vector of all resulting named inputs, analyze the complete vector in one call,
and return findings grouped by every filename. Callers may refuse the batch,
but may not stop at the first invalid file or silently omit the remaining
findings. Build inputs that are not Clojure remain dependency invalidators, not
fake Clojure inputs. The current hook's post-edit call preserves the aggregate
shape at `bin/seon-hook:259-279`; consolidating wrappers must preserve it.

## Current source path

The accepted path has two reads with different responsibilities:

1. `reply/sources` strips fences, calls the one reader, preserves each exact
   source span and parse-time namespace, and may comment a reader-failing prose
   line (`src/seon/cluster/reply.cljc:310-352`). It performs no semantic lint
   and no code repair.
2. `freeze!` immediately hashes and commits that vector
   (`src/seon/cluster/loop.cljc:703-715`). This is the last point at which a
   complete candidate can be refused or replaced without invalidating plan
   identity and resume.
3. At evaluation, `reader-context` projects aliases, imports, refers, and
   requires from the actual SCI context, and `one-event` reads exactly one
   frozen source with that context (`src/seon/sci/eval.clj:368-400`).
4. SCI evaluates the form (`src/seon/sci/eval.clj:785-792`). Only then does
   `admit/admit` bound the resulting value (`src/seon/sci/eval.clj:883-894`).

Therefore a proposed `reply -> reader -> admit -> eval` description is
incorrect. The implemented order is `reply -> reader -> plan fact -> reader
with live SCI bindings -> eval -> value admission`.

### The correction seam

The historical ruling already requires a pure pre-plan transformation:
`docs/prds/sci-execution-runtime/research/capability-ledger-2026-07-26.md:71-85`
records that a post-plan source splice once changed six emitted entries into
seven executed forms and invalidated resume accounting. The fresh path removed
that mutable repair queue.

If semantic correction is restored, its contract should be one pure map in and
one value out:

```clojure
{:seon.code.candidate/sources [...]
 :seon.code.candidate/namespace-context {...}
 :seon.db/db <one immutable database value>}

;; => either
{:seon.code.candidate/sources [...]
 :seon.code.candidate/findings [...]}

;; or one flat :seon.error value
```

The successful vector is then the vector digested and committed by
`run/plan-tx`. No receipt exists yet. A correction should replace the complete
candidate and run the reader plus lint again; it should not regex-rewrite a
symbol, append a form, mutate a queue, or revise the plan after commitment.
Whether an LLM is allowed to author the corrected candidate is an owner design
decision, separate from clj-kondo analysis.

### Owner decision: exactly three admission/repair options

1. **Lint and refuse, with no automatic repair — recommended.** Guarantee:
   every frozen plan has passed the one reader and the settled error-level
   clj-kondo policy against one explicit namespace/dependency context; failure
   is a flat value and the agent may author a fresh reply. Cost/risk: one
   in-process lint call and source-location projection, with no new runtime
   effect or provenance model. Operational trade-off: an otherwise obvious
   correction costs another agent turn. Capability given up: transparent
   same-turn repair.
2. **One whole-candidate model correction before freeze.** Guarantee: the
   original candidate is never partially executed; on lint failure, one
   bounded capability request may return a complete replacement candidate,
   which is read and linted from the beginning and either frozen once or
   refused. Cost/risk: materially higher—request identity, correction prompt,
   provenance, attempt bound, context-size limit, model failure, and billing
   all cross owners. Operational trade-off: fewer user-visible retries but
   slower and nondeterministic admission. Capability given up: repeated or
   incremental repair; one failed correction ends the attempt.
3. **No static gate; retain evaluation failure plus owner routing.** Guarantee:
   only reader-valid source is frozen; SCI containment and receipts still make
   runtime failures durable. Cost/risk: lowest implementation cost but it keeps
   known semantic mistakes in committed plans and retains the unproven
   `:owner-fixed` settlement label. Operational trade-off: failures are
   discovered late and consume evaluation/coordination work. Capability given
   up: pre-execution arity, namespace, unresolved-var, and type-mismatch
   refusal.

Option 1 is the simplest constraint and the recommendation. Option 2 crosses
enough owners that the owner design gate applies before production edits.
Option 3 is the present effective behavior, not a destination.

### The current owner-routing gap

`form-problem` records the failed source and routes a message to its derived
namespace owner (`src/seon/problems.clj:152-222`). The prompt asks that owner
to “repair one in your own namespace” or explicitly decline
(`src/seon/context.clj:172-210`). Settlement then derives:

- red receipt plus assignment: `:routed`, unsettled;
- matching declination: `:owner-declared-cant`, settled; and
- any later non-red receipt with an assignment: `:owner-fixed`, settled
  (`src/seon/cluster/work.cljc:283-321`).

There is no join from a later declaration identity or source digest to the
original failed form and no retry of that form. The label `:owner-fixed` thus
overstates what the facts prove. This is a correctness gap in the existing
settlement model, not authority for this audit to edit production code.

## What the pinned clj-kondo provides

### Source strings and namespace context

The in-process string API is:

```clojure
(with-in-str source
  (clj-kondo.core/run!
   {:lint ["-"]
    :filename "my/agents/alpha.clj"
    :lang :clj
    :config-dir ".clj-kondo"
    :cache-dir ".clj-kondo/.cache"
    :config {...}}))
```

`process-file` reads `*in*` for `"-"`; `:filename` controls reported identity
and language inference (`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:471-527`).
It also affects nearest-config discovery when `:config-dir` is absent
(`reference-code/clj-kondo/src/clj_kondo/core.clj:133-143`). Seon should pass
both explicitly so an agent namespace cannot accidentally select configuration
from an invented path.

clj-kondo begins every ordinary input with a synthetic `(ns user)`
(`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:4349-4368`). A bare
source string therefore does not model the agent's effective namespace. The
lint source must prepend a synthetic `ns` form that represents the applicable
aliases, refers, imports, and requires. `(in-ns ...)` alone is insufficient.
Findings must have the prelude row count removed before being returned; the
exact original source remains authoritative.

This prelude should be derived from the same database namespace facts or live
SCI binding projection used by the reader. It must not become another mutable
namespace registry. Pre-plan lint can only know the namespace state available
before this new plan; declarations earlier within the candidate must be linted
as one ordered source so clj-kondo can see them.

### Configuration, imports, and hooks

Configuration resolution merges defaults, optional home configuration, the
nearest project configuration, exported/imported configurations, an optional
extra directory, and explicit config
(`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:148-191`). Production
analysis should set `:config-dir` and `:repro true` so user-home configuration
cannot change admission. Dependency configuration exports can be copied or
auto-loaded under the selected directory
(`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:235-245`).

Use `:lint-as` for a genuine macro whose binding or definition shape matches a
known construct (`reference-code/clj-kondo/doc/config.md:341-353`). Use a hook
only when a macro cannot be represented that way. Hooks run in clj-kondo's own
SCI over rewrite nodes and can transform analysis or emit findings
(`reference-code/clj-kondo/doc/hooks.md:1-16,35-77`). They are not an execution
security boundary and must not become a source-repair engine. Hook namespaces
are cached after first load (`reference-code/clj-kondo/doc/hooks.md:589-600`),
so changing a hook is not a live-update mechanism.

The current `.clj-kondo/config.edn:4-49` makes unresolved symbols,
namespaces/vars, invalid arity, and type mismatch errors while keeping
style/unused findings at warning or info. `.clj-kondo/config.edn:51-56` has one
first-party `:lint-as`. Those levels are suitable inputs, but the application
must retain an explicit policy: program indexing currently blocks only
`:error` (`src/seon/fn.clj:207-224`). Warnings are evidence, not refusal.

One subtle trap is `.clj-kondo/config.edn:58-62`: output is filtered to source
extensions. With no `:filename`, stdin is named `<stdin>` and repo-configured
findings disappear. Every source-string call therefore requires a `.clj` or
`.cljc` filename even when the caller does not display it.

### Cache and full dependency context

The cache stores per-namespace definition data and loads a required namespace
on demand (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:16-36,65-79,127-144`).
Writes are protected by both JVM and file locks
(`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:83-125,210-215`).
Dependency JARs use a config-hashed skip marker when linted with
`:dependencies`; non-SNAPSHOT JARs can then be skipped
(`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:487-505,590-607`).

The separation Seon needs is:

- **Resolution context:** deliberately prime the exact `clojure -Spath` with
  `:dependencies true`, `:parallel true`, `:copy-configs true`, the fixed
  config directory, and the operator-owned cache directory. Refresh when the
  classpath or lint configuration changes. The changed-test implementation
  already has this shape at `script/seon/dev/changed_test.clj:183-230`.
- **Published projection:** call clj-kondo on only the selected first-party
  `src/` and `test/` paths with that cache. Normalize analysis rows from those
  paths and populate only those rows into the `current-src` scratch build.
  `source-roots` already establishes this publication boundary at
  `src/seon/fn.clj:15-17`.

The top-level `:analysis` returned by `run!` describes sources analyzed in that
invocation. Loaded cache entries enrich resolution; they are not a request to
publish dependency namespaces. The publication boundary must nevertheless be
enforced by filtering canonical filenames under the selected roots, rather
than relying only on this observed behavior.

### Analysis exports and parallelism

The default useful graph is namespace definitions/usages and var
definitions/usages. Opt-ins add arglists, metadata, locals, keywords, protocol
implementations, symbols, Java information, instance invocations, and analysis
context (`reference-code/clj-kondo/analysis/README.md:14-60,77-142`). Seon
currently requests the first four plus arglists and full definition metadata
(`src/seon/fn/analyzer.clj:9-16`). That is enough for namespace rows,
declarations, and the function-call graph. Extra exports should be enabled only
for a named consumer; their presence is not evidence they belong in database
facts.

Parallel mode groups sources, processes each group serially, and runs groups in
a fixed executor of `2 + floor(0.6 * processors)` threads
(`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:373-399`). It is suitable
for full-corpus indexing and dependency warming. A single stdin candidate has
no useful parallel work.

## Executable probes

All probes ran in the repository JVM with the pinned dependency on 2026-07-30.

### Namespace context and filename

A source containing `missing` and `(str/join [])` produced both
`:unresolved-symbol` and `:unresolved-namespace` when linted as a named `.clj`
file without a prelude. Prepending
`(ns my.agents.alpha (:require [clojure.string :as str]))` removed only the
namespace finding. Prepending only `(in-ns 'my.agents.alpha)` did not. This
falsifies the assumption that a filename or `in-ns` supplies resolver context.

The same bare stdin source under the repository config returned no findings
when `:filename` was omitted, because `<stdin>` failed the configured
`:output :include-files` filter. Supplying `tmp/probe.clj` exposed the expected
findings.

### Cache is context, not returned projection

Linting a named stdin source requiring `clojure.string` against the existing
cache resolved `str/join`. The only returned analysis filename was
`tmp/probe_cache.clj` and the only namespace definition was the candidate's
namespace. The one finding was the deliberately inconsistent namespace/file
name. This supports, but does not replace, the explicit first-party filename
filter required above.

### Cost

Five same-JVM source-string runs with `:cache false` took
`[32 7 6 6 5]` milliseconds. One full `src` plus `test` analysis over 123 files,
with shallow/no-lint analysis and cache disabled, took 808 ms serially and
304 ms with `:parallel true`; both returned 138 namespace definitions. These
are one-machine directional measurements, not service-level guarantees. They
show that an in-process candidate lint is cheap after initialization and that
parallelism materially helps the corpus scan.

## Replace, retain, and delete boundaries

| Surface | Decision | Reason |
|---|---|---|
| `seon.sci.reader` | Retain | clj-kondo analysis does not provide Seon's exact accepted-source events, reader-tag policy, REPL namespace attribution, or SCI form values. |
| Pre-plan semantic analysis | Add at `freeze!` boundary | This is the last point where a complete corrected vector can acquire one stable digest before receipts and resume state exist. |
| `seon.sci.admit` | Retain unchanged | It is the one bounded value codec after evaluation, unrelated to source lint. |
| `seon.fn.analyzer` normalization | Keep one owner, simplify | A thin stable Seon projection is warranted, but all program indexing and other JVM callers should share it rather than hand-normalize clj-kondo maps independently. Add cache/config/parallel controls to this owner instead of another wrapper. |
| `seon.fn` exact-source extraction and `current-src` population | Retain | clj-kondo supplies locations and analysis, not canonical source bytes, schema qualification policy, identities, or database transactions. |
| Changed-test namespace graph | Reuse analyzer mechanics, retain separate corpus policy | Namespace definitions/usages duplicate analyzer mechanics, but changed tests include `script/seon/dev` and operator/writer test roots outside the published program graph (`script/seon/dev/changed_test.clj:173-181`). It must not expand database publication merely to reuse analysis. |
| Per-change full corpus scan | Optimize, then consider replacement from database graph | Dependency caches accelerate resolution but do not make the returned first-party namespace graph durable. Once program indexing publishes complete calls for `src`/`test`, changed tests can query that graph for those roots; operator-only roots still need their own indexed artifact or scan. |
| `bin/oracle-server` clj-kondo temp-file bridge | Delete when the downstream draft oracle is retired; do not reuse | It lazily loads the clj-kondo pod and writes a reused temporary `.cljs` file (`bin/oracle-server:357-389`). The accepted-source test explicitly exempts it because it is upstream draft intelligence (`test/seon/sci/reader_test.clj:485-520`). Fresh admission should use the JVM string API, not this second reader/tool path. |
| Oracle delimiter repair | Never move into admission | `bin/oracle-server:304-355` repairs incomplete cursor drafts. Accepted replies must pass the one reader; a typeahead helper's tolerance is not a correctness contract. |
| Hooks | Narrow use only | They model genuine macros for lint analysis. They must not enforce SCI capabilities, mutate source, or become a second program analyzer. |

### Deletion timing

**Within the current hour:** no production deletion is justified by this
audit. The dangerous old mid-evaluation repair/splice mechanism is already
gone. The apparent remaining duplicates each still have a live dependency:
the edit hook owns prospective and multi-file feedback, changed-test owns a
broader operator/writer corpus, the index owns exact first-party rows and
database reconciliation, and `bin/oracle-server` still has downstream draft
callers. Deleting any one of them before the shared analyzer contract lands
would remove behavior rather than remove duplication. The immediate deletion
is architectural: reject `admit` as a source seam and do not restore the old
repair queue, delimiter appender, or oracle temp-file bridge in fresh runtime
code.

**After the shared operation is proven:** delete direct clj-kondo invocation
and result normalization from each consumer, leaving only its corpus and policy
projection. Delete the changed-test full first-party scan only when a complete
database program graph can answer its reverse-dependency query while its
operator-only roots remain covered. Delete the oracle pod/temp-file analysis
only with its downstream draft feature or after that feature calls the shared
string operation. Correct or delete the `:owner-fixed` settlement state only
after the owner rules what durable fact proves a repair.

## Exact target boundary

One source-analysis owner should expose two operations over ordinary data:

1. Analyze a finite vector of named source inputs against an explicit config
   directory and cache, returning normalized findings and analysis facts.
2. Prime dependency resolution from an explicit classpath without returning
   publishable program rows.

Program indexing calls operation 1 with canonical first-party files and
publishes only rows whose canonical filename is under `src/` or `test/`.
Changed-test selection calls the same analysis mechanics with its broader
operator-owned corpus but retains its own test-root and fallback policy.
Candidate admission calls operation 1 through stdin with a synthetic namespace
prelude, translates locations back to original source rows, blocks only the
settled error classes, and returns a whole candidate or a flat error before
`run/plan-tx`.

This arrangement uses the full classpath for truth without confusing
dependency knowledge with first-party authorship. It also preserves the three
surviving authorities: the SCI reader decides what source means, clj-kondo
reports static facts, and the database index decides what first-party program
facts are published.
