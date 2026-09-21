---
type: architecture
status: active — first pass 2026-09-21 (Fable), for astra review before the clean write
tags: [architecture, reference-code, dependencies, forks]
---

# Reference code — the twenty repositories we keep

`reference-code/` vendors dependencies as git submodules so their semantics
are READ at design time, never remembered. An implementer opens the seam
named below before editing the Seon code that sits on it, and cites it by
`file:line`. Twelve are RUNTIME (named by `deps.edn` as `:local/root`); eight
are READ-FOR-DESIGN (not on the classpath; the source of a seam we build
against). Every other submodule was unvendored on 2026-09-21
([usage audit](../../../docs/research/agent-platform/reference-code-usage-audit-2026-09-21.md)).
Gitlinks below are `git -C reference-code/<x> log -1` on 2026-09-21; every
`file:line` was opened at that gitlink.

**The rule.** A fix that belongs to the dependency goes in OUR FORK of the
dependency — accreted in its own idiom, pushed to the fork the moment it
lands, never a PR upstream, never a Seon-side workaround beside it. The
dependency's vocabulary and modeling override ours ("don't be dogmatic").
When a Seon mechanism recomputes something a dependency already maintains,
the mechanism is the defect.

## The twelve on the classpath

| Repository | Gitlink | Origin | `deps.edn` | Why we build on it |
|---|---|---|---|---|
| `datahike` | `006e634a` 2026-09-20 | fork `seantempesta/datahike` (upstream replikativ) | `:26` | the database: branches, writer, pull, temporal reads, cache context |
| `sci` | `fcbd8862` 2026-08-11 | fork `seantempesta/sci` (branch `seon-env-hook`) | `:48` | agent evaluation: `init`/`fork`/`intern`, generation, interrupt, call preparation |
| `malli` | `606083c5` 2026-09-19 | fork `seantempesta/malli` (branch `seon-ref-scope`) | `:15` | contracts and the compiled registry read as the projection |
| `clj-kondo` | `57252e07` 2026-07-30 | fork `seantempesta/clj-kondo` | `:17` | the analyzer behind the program graph and its namespace cache |
| `http-kit` | `238a85c` 2026-07-29 | fork `seantempesta/http-kit` | `:106` | HTTP and SSE with bounded pending-write state |
| `datastar-clojure` | `1cef624` 2025-12-26 | upstream `starfederation` | `:102`, `:110` | the Datastar SDK the page delivery rides |
| `core.async.flow-monitor` | `fbff842` 2026-07-29 | fork `seantempesta/core.async.flow-monitor` | `:147` | the flow monitor on a published port (test alias) |
| `editscript` | `b493ccf` 2026-01-18 | upstream `juji-io` | `:14` | the diff behind `seon.db/diff` |
| `edamame` | `63373df` 2026-09-15 | upstream `borkdude` | `:50` | the reader used for source fidelity |
| `rewrite-clj` | `60782e5` 2026-01-20 | upstream `clj-commons` | `:92` | whitespace-preserving source edits (write-back) |
| `babashka-process` | `16a84e0` 2025-12-21 | upstream `babashka` | `:86`, `:135` | process control for the operator |
| `babashka` | `0fb349c4` 2026-04-20 | upstream `babashka` | `:82` (`babashka/fs` only) | filesystem functions; the whole repository is pinned for one nested path |

## The eight we read for design

| Repository | Gitlink | Origin | The seam we read there |
|---|---|---|---|
| `core.async` | `dc35f3e` 2026-06-04 | upstream `clojure` | flow's proc/graph vocabulary, workloads, `futurize` |
| `konserve` | `07377c2` 2026-08-05 | fork `seantempesta/konserve` (consumed at build by `:git/url` from the same fork) | binary storage and GC under Datahike |
| `clojure` | `b18d3adc` 2026-07-20 | upstream `clojure` | the prepl, `clojure.test`'s reporting, `ex-info`, `Throwable->map` |
| `hyperlith` | `b08a8e8` 2026-06-19 | upstream `andersmurphy` | whole-view-per-batch SSE delivery |
| `clj-reload` | `61c6fa7` 2025-09-15 | upstream `tonsky` | reload ordering by dependents |
| `kaocha` | `8846f91` 2025-10-09 | upstream `lambdaisland` | what a runner is, in a few hundred lines |
| `clojurescript` | `946d75f` 2026-05-29 | upstream `clojure` | read only by the `clojurescript` skill to mine the deleted pod; fresh Seon is CLJ-only |
| `langchain4clj` | `889f9e6` 2026-04-07 | upstream `nandoolle` | the provider-request reference named by the `llm-providers` skill |

## Seams per repository — open these

Each row: the block, then what the source guarantees (read, not inferred).

### datahike (`src/datahike/`)

| Open | Guarantee |
|---|---|
| `writer.cljc:42` `LocalWriter`, `:105` `create-thread` | one processing thread and one commit thread per connection; transactions serialize there — never build a writer |
| `versioning.cljc:212` `branch!` | a branch is a pointer; refuses `:branch-already-exists` |
| `versioning.cljc:279` `delete-branch!` | refuses main; refuses while a connection is active; bytes survive until GC |
| `versioning.cljc:457` `commit-id`, `:550` `fork-database`, `:734` `merge!` | head = commit id in store metadata; `fork-database` COPIES every key (never a hot path); `merge!` is the merge seam |
| `query.cljc:2568-2590` `advance-query-cache-context`, `:2963-2976` `source-context-unchanged?`, `:2877` `query-dependency-plan` | per transaction, a commit id per changed attribute; currency is a revision comparison, never a re-read; the plan is attribute-granular without executing |
| `pull_api.cljc:16` `+default-limit+ 1000`, `:315`, `:107-113`, `:162-177` | a cardinality-many pull is cut at 1,000 with no marker; `:315` honours a nil limit; pulls carry their dependency plan |
| `schema.cljc:87` `:db.type/any`, `:35-55` the value set, `:167` `:db/tupleType` | `any` exists but is not admissible for an ordinary attribute today (fork item); tuple types are validated at `db.cljc:811-826` |
| `connector.cljc:144` `ensure-stored-config-consistency` | refuses a connect whose config diverges from the stored one |
| `connections.cljc:5` `active-connection`, `:124` `delete-connection!` | positive reference count is liveness; a `contains?` on the map stays true through a release drain |
| `gc.cljc:83` `gc-storage!`; `gc_guard.cljc:36-42` | reachability from branch heads plus a caller extension; Datahike assumes one JVM — the `flock` is ours |
| `db/transaction.cljc:321` | provenance rides `:tx-meta` and returns on the report |

### sci (`src/sci/`)

| Open | Guarantee |
|---|---|
| `core.cljc:331` `init`, `:345` `fork`, `:260` `intern`, `:273` `bind-root!` | `fork` is a new env atom over the same namespace map plus a fresh generation — no per-Var allocation |
| `impl/utils.cljc:356` `next-generation`, `:362-379` `bind-root!` | equal generation ⇒ mutate in place; unequal ⇒ copy the Var into this env stamped with the fork's generation; the base Var is never mutated through a fork (the fork change: arm at the base so generation alone discriminates the private layer) |
| `doc/interrupt.md` | the ONE `:interrupt-fn`, called on every fn body entrance and `loop`/`recur`; `time-limit` is the evaluation deadline |

### malli (`src/malli/`)

| Open | Guarantee |
|---|---|
| `registry.cljc:11-22` `Registry`/`fast-registry`, `:97` `schema` | a sealed registry answers by `HashMap.get`; `mr/schema` is the read |
| `core.cljc:268` `-memoize`, `:345` `-create-cache` | every Schema has its own cache — never a second validator cache in Seon |
| `core.cljc:2771` `entries`, `:2848` `from-ast`/`ast`, `:2193-2277` `-function-schema-arities`/`-function-info` | keys, `:optional`, arities and `:input`/`:output` come off the compiled node; `ast` is the normal form and keeps `{:closed false}` |
| `core.cljc:3118` `-instrument`, `:2659` `explain`, `:996` `-or-schema` | `-instrument` dispatches input/output/arity with a `:report` seam; `explain` builds its explainer per call; an `:or` failure carries every branch's problems |
| `error.cljc:44` `default-errors`, `:288` `error-message`, `:374` `humanize` | a data table (fork accretes the absent collection types); ten-step message fallback; `humanize` mirrors the value's shape |

### clj-kondo (`src/clj_kondo/`)

| Open | Guarantee |
|---|---|
| `impl/cache.clj:23` `from-cache-1`, `:83` `with-cache`, `:127` `load-when-missing` | a `:disk` entry is returned whenever the transit file exists (the fork change: skip one whose filename is gone); reads and writes are lock-scoped; a never-linted namespace silently no-ops |
| `core.clj:67` `run!` | `:cache false` disables resolution; `:analysis` and `:cache-dir` travel together |
| `impl/analysis.clj:87-115` `reg-var!` | the per-var row: name and end row/col, arglists, private, macro, arities, doc — the span and the facts the program graph stores |
| `impl/core.clj:337` | a `.` classpath entry is traversed recursively — never feed the test alias's `.` to the linter |

### core.async, konserve, clojure, hyperlith, clj-reload, kaocha

| Open | Guarantee |
|---|---|
| core.async `flow.clj:76-78` `create-flow`, `:136-155` `ping`, `:186-188` `:compute-timeout-ms`; `flow/impl.clj:29` `futurize`, `:243` `proc`; `impl/dispatch.clj:91` `create-default-executor`, `:97` `executor-for` | a `:compute` step runs as a `FutureTask` under the deadline and its timeout surfaces on the `::flow/error` port; `:io` is virtual threads, `:mixed` a platform thread per proc |
| konserve `core.cljc:634` `bget`, `:658` `bget-range`, `:673` `bassoc`, `:690` `keys`; `gc.cljc:8` `sweep!` | streaming binary reads by key and range; `keys` yields no size (fork item); a throw inside the GC callback hides the batch from the store |
| clojure `core/server.clj:228` (prepl), `test.clj:325` `report`, `:710` `test-var`, `core.clj:4924` `ex-info`, `core_print.clj:473` `Throwable->map` | the REPL every tool speaks to; a dynamic reporting defmulti; the throwable shape we store |
| hyperlith `impl/datastar.clj:122-189` `render-handler` | one dropping-buffer tap per tab, whole view per signal, brotli, idiomorph does the diff — no revisioning |
| clj-reload `parse.clj:122` `dependees`, `:133` `transitive-closure`, `:163` `topo-sort` | dependent ordering in a maintained dependency; default `on-cycle` throws |
| kaocha `type/var.clj:30-63`, `testable.clj:214-228`, `plugin/profiling.clj:17`, `api.clj:34-36` | one var in 34 lines, a collection with fail-fast in 15, duration as a plugin that never fails a test, reporter substitution in two lines |

### datastar-clojure, http-kit, edamame, rewrite-clj, editscript, babashka-process

| Open | Guarantee |
|---|---|
| datastar-clojure `libraries/sdk/src/main/starfederation/datastar/clojure/api/elements.clj:112` `->patch-elements-seq` | the one SDK call the page delivery uses |
| http-kit `src/org/httpkit/server.clj:321` `write-state` | our fork's atomic pending-byte state for bounded SSE writes |
| edamame `src/edamame/core.cljc:9` `parse-string` | forms with locations, source-faithful |
| rewrite-clj `src/rewrite_clj/parser.cljc:34` `parse-string`, `:39` `parse-string-all` | a rewrite that preserves whitespace and comments — write-back by span |
| editscript `src/editscript/core.cljc:22` `diff` | the structural diff `seon.db/diff` maps to |
| babashka-process `src/babashka/process.cljc:453` `process` | child processes with explicit streams and exit |

## Our forks

`datahike`, `sci`, `malli`, `clj-kondo`, `http-kit`, `konserve`,
`core.async.flow-monitor` — every pinned commit is on a remote branch of its
fork (usage audit §2). The fork changes the plan lands, each pushed with the
Seon deletion it enables: datahike admits `:db.type/any` for ordinary
attributes and refuses it for indexed or unique ones, pulls complete by
default, exports `branch-commit-id`, types the `:keep-history-mismatch`
refusal; konserve reports size in `keys` metadata; clj-kondo's `from-cache-1`
skips an entry whose file is gone; malli's `default-errors` gains the absent
collection types; sci arms contract wrappers at the base so `:sci/generation`
alone discriminates the private layer
([synthesis §5](../../prds/agent-platform/research/synthesis-2026-09-21.md)).
