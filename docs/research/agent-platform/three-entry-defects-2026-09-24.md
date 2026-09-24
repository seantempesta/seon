---
type: research
status: evidence — read-only study; implementation frozen
created: 2026-09-24
scope: producers, root causes and smallest fixes for four SCI entry defects (malli pinned 8725a8cbd9d595f4a970ce53a2eefdbe7211b96d)
---

# Entry defects seen on `exp-drop-note-row-1` — producers and smallest fixes (2026-09-24)

What exists: an SCI `defn` goes through `seon.sci.eval/evaluate`. `definition-row`
(`src/seon/sci/eval.clj:426`) analyses the source into a program row. It validates any
`:malli/schema` through `schema/projection-with-function-contract` with admission `:agent`, then
evaluates the form live. The contract wrapper goes on only after the row commits
(`install-row!` → `install-function-contract!`, `eval.clj:1027-1033`, `:702`). This is a
read-only study, and implementation is frozen. Evidence comes from
`tmp/orchestrator/drop-note-row-1-stdout.log` (forms 3–11) and from new probes on branch
`exp-mcp-check`, SCI mode, namespace `scratch.probe`, all run 2026-09-24. Default's Vars were
not touched. Throwaway branch `exp-entry-probe` was created and then retired.

## Broken first

1. **Defect 4, the worst:** a contracted SCI `defn` evaluated through MCP `eval_clj` is never armed.
2. **Defect 2:** no entry policy (`:gate`/`:warn`) is installed anywhere. A redefinition with no
   contract is admitted. On the turn path it also commits a row that has no contract.
3. **Defect 1:** `:nil` is classed as an "undefined" type for agent contracts. The refusal then
   crashes on its own error helper's contract.
4. **Defect 3:** file-namespace aliases are installed only for namespaces that acquisition selects.

## 1. `:nil` refused in agent contracts; `contract-error!` crashes; `[:= nil]` "not EDN-readable"

**Reproduced.** `(defn probe-nil "Probe." {:malli/schema [:=> [:cat [:or :nil :int]] :int]} [x] (or x 0))`
returns *"seon.schema.internal/contract-error! refused identity at []: expected a keyword, got a
symbol scratch.probe/probe-nil"*. The real refusal (`:seon.schema/undefined-contract`) is only
visible inside `:seon.error/offending`.

**Producers.**
- `src/seon/schema/internal.cljc:16`: `(def ^:private undefined-types #{:any :some :nil})`.
  At `:313` any agent-authored (`:seon.schema.admission/source :agent`) schema node of those
  types is refused.
- Core rows avoid this in `src/seon/schema.clj:1596-1602`. For `:core` admission, this walk only
  checks error-schema inheritance. So `seon.note/add-note!`'s file contract is admitted, and the
  same text re-entered by an agent is refused. The asymmetry is the admission source, not the
  contract.
- `internal.cljc:185` declares `identity` as `:keyword`. Function contracts pass the function
  symbol (`eval.clj:460`, `projection-with-function-contract`), so every function-contract
  refusal from `assert-complete-schema!` becomes an input-contract violation of the helper.
  This affects `:314`, `:331`, `:345`, `:358` and `:367`.

**Root cause.** `:nil` is not undefined. It is Malli's singleton `-nil-schema`, `(-simple-schema
{:type :nil, :pred nil?})` (`reference-code/malli/src/malli/core.cljc:811`, registered at `:2950`).
It accepts exactly one value. `:any`/`:some` say nothing, but `:nil` is exact. Refusing it while
admitting `[:maybe X]` (`internal.cljc:339-360` only advises, except for map values and bare
returns) is backwards: `[:or :nil X]` and `[:maybe X]` accept the same values. The log shows the
agent's workaround, `[:maybe :my.note/about]`, being accepted at form 11.

**`[:= nil]` is a Malli syntax fact, not a Seon reader defect.** `m/schema` treats a nil second
element as the properties slot:
`(if (or (nil? ?p) (map? ?p)) (into-schema t ?p (subvec 2) …)` (`malli/core.cljc:2563-2568`).
So `[:= nil]` has no children and fails with `::m/child-error`. The JVM probe confirms it:
`(seon.schema/malli-form? [:= nil])` is `false` and `[:= 1]` is `true`. The misleading part is the
message. `seon.schema/malli-form?` (`src/seon/schema.clj:1414-1448`) folds Malli's parse refusal
into the same `false` as an EDN read failure, and `:seon.schema/definition` says *"must be a
parseable, EDN-readable Malli form"*. The correct forms are `:nil` or `[:= {} nil]`.

**Smallest fix (about 6 lines, at the owner, `internal.cljc`).**
- Change `undefined-types` to `#{:any :some}`.
- Make the `:maybe` rules also apply to an `:or` node that has a `:nil` child. This is about
  3 lines in the `:339` `cond`, so that `[:or :nil X]` as a map value or bare return is refused
  exactly as `[:maybe X]` is. This keeps the "absence is no key" law without refusing a precise
  type.
- Change `contract-error!`'s contract at `:185` to take `identity` as
  `[:or :qualified-keyword :qualified-symbol]`. That is the real domain: schema keys and function
  symbols.

An optional message fix (`malli-form?` returning a reason) is out of scope. Instead, add
"nil in the properties slot; write `[:= {} nil]` or `:nil`" to the `:seon.schema/definition`
`:error/message`.

**Class regression.** Take every packaged core function contract that contains `:nil`
(`src/seon/plan.clj:109` and others). Re-admitting it through
`projection-with-function-contract` with `:agent` admission must succeed. It must also return
the same refusal kind as `[:maybe X]` in the map-value and bare-return positions. The refusal
itself must be a `:seon.schema/undefined-contract` value, never a helper-contract violation.

## 2. A redefinition with no `:malli/schema` is admitted and drops the contract

**Reproduced.** `(defn probe-bare "Probe." [x] (inc x))` returns `#'scratch.probe/probe-bare`
with outcome `:ok`. In the log, form 9 redefined `seon.note/add-note!` without a contract and
form 10 then showed "contract absent".

**Producers.**
- `definition-row` (`eval.clj:426-470`). When `(:malli/schema metadata)` is absent, it returns
  the analysed row without `:seon.fn/spec` (`:458`, `(if (:seon.fn/spec row) … row)`). No branch
  refuses. `install-row!` then installs it with no contract (`eval.clj:1031`, `(when (:seon.fn/spec
  committed) …)`).
- The reader path disagrees with this. `program/declaration-row` with `:contracted`
  (`src/seon/program.cljc:1224-1228`) maps "a function without a spec" to `nil`, which means
  *not a declaration* rather than *refused*. `declared-row` (`eval.clj:2801`) then falls through
  to a plain `eval-form!`.
- A search for `:gate`, `entry-policy`, `uncontracted` and `missing-contract` finds no entry dial
  or refusal. It finds only the after-the-fact issue detector `src/seon/issue/detect.clj:220`,
  which covers public functions only.

**Root cause.** The CLAUDE.md law ("Schema/test/test-first entry defaults to `:gate`, refusing
before evaluation") names a mechanism that is not installed. There is no dial and no producer.
Redefinition is not special: every uncontracted `defn` is admitted. A redefinition only makes it
visible because an armed contract disappears.

**Smallest fix (about 12 lines, `eval.clj` `definition-row` plus one config key).** When
`(fn? value)` and `(nil? (:malli/schema metadata))`, return a declared refusal
`:seon.sci.eval/missing-contract` that names the symbol and the expected `:malli/schema`. Do this
before the row exists, and roll back the live root the same way refused candidates are discarded
(`bindings`, `eval.clj:3531-3533`). Read the dial as `:seon.config.entry/contract` `[:enum :gate
:warn]`, default `:gate`, from the effective configuration already read at `eval.clj:688`. With
`:warn`, admit the row and add a declared advisory. Also make `declaration-row :contracted`
refuse rather than return `nil`, so the reader path and the definition path agree. Note that the
dial is a new config key, which needs an owner ruling. The rest is ordinary.

**Class regression.** Using the canonical helpers, one `deftest` evaluates an uncontracted
`defn` twice: once as a new name and once redefining a contracted function. Under the default it
asserts a `:seon.sci.eval/missing-contract` refusal and that the original armed root is intact.
Under `:warn` it asserts admission plus an advisory.

## 3. A branch's SCI copy of a file namespace lacks its `:require` aliases

**Reproduced.** On `exp-mcp-check`, `(ns-aliases 'seon.note)` returns `{}` while
`(count (ns-interns 'seon.note))` is 21. The branch row for `seon.note` does hold all six aliases
(`db → seon.db`, `str`, `route`, `schema.edn`, `render.value`, `repl`). So aliases are stored,
but they are not installed.

**Producer.** In `acquire-program!` (`eval.clj:1859`), namespace rows are pulled for every
namespace (`all-namespace-rows`, `:1937-1955`). Aliases, however, are installed only for
`namespace-row-by-name` (`:2082-2086`), and those rows come from `selected-namespace-names`
(`:1931-1936`). That is only the namespaces owning interpreted (overridden or affected) functions
plus agent-admitted namespaces. `install-first-party-namespaces!` receives `all-namespace-rows`
but ignores them (`_namespace-rows`, `:1437`) and copies only host Vars (`:1448-1464`). A clean
first-party namespace such as `seon.note` therefore has Vars and no aliases. The first agent
`defn` in it cannot resolve `db/pull`.

**Root cause.** Alias installation was tied to the "which functions must be interpreted" selection
instead of to namespace existence. SCI analyses new source in that namespace, so it needs the
namespace's reader context whether or not anything in it is interpreted yet.

**Smallest fix (about 3 lines).** In `install-first-party-namespaces!`, use the ignored rows:
for each first-party namespace row, call
`(sci/install-namespace-bindings! ctx ns (assoc (row-bindings row) :refers {}))` before copying
Vars, as `:2085` already does for selected namespaces. Imports need their classes, so
`install-declared-classes!` (`:1728`, called at `:2081`) must take `all-namespace-rows` too.
Otherwise, pass `:imports {}` for unselected namespaces. The cost is one map install per
namespace per base context, which is memoized by program identity (`base-ctx`, `eval.clj:2475`).

**Class regression.** On a fresh branch, check every first-party namespace row:
`(ns-aliases ns)` in SCI must equal the row's `:seon.ns/aliases`. Then evaluate one `defn` in
`seon.note` that calls `db/pull`.

## 4. A contracted branch-private `defn` is unarmed; acquisition cost

**Reproduced.** On `exp-mcp-check`:
- `(defn twice "Double x." {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))` returns
  `#'scratch.probe/twice` with `:ok`.
- `(twice "a")` throws `java.lang.ClassCastException` with no `ex-data`.
- `(meta #'twice)` still carries `:malli/schema`.
- `(:seon.instrument/interpreted-original (meta twice))` is `false`, so there is no wrapper.
- `(seon.db/pull (seon.db/db) … [:seon.fn/sym 'scratch.probe/twice])` is `nil`, so there is no row.

**Producer.** The wrapper is applied only by `install-function-contract!` (`eval.clj:702`). That
is reached from `install-row!` after the row commits (`:1027-1033`), and from branch
re-acquisition (`:2795`). MCP's `sci-evaluation-form` (`script/seon/dev/mcp.clj:518-529`) calls
`seon.sci.eval/evaluate` and discards `:seon.program/row`. By design (tool description, `mcp.clj:820`)
no settlement or commit happens, so the contract validated at `eval.clj:458-463` is never armed.
The live Var is exactly what SCI evaluated. The same gap explains why the log's caller
redefinitions "were not recorded as program rows".

**Root cause.** Arming depends on persistence. The law is "Arm every valid contract: invalid
inputs prevent entry", and it has nothing to do with durability. `evaluate` produces a live Var
from a contract it has already validated, but leaves the arming step to a later commit that some
callers never perform.

**Smallest fix (about 5 lines, `eval.clj`).** After `definition-row` returns a row that has
`:seon.fn/spec`, call `install-function-contract!` on the execution context with that row and
`projection`, next to `var-row` at about `:3499`. It uses only `:seon.fn/sym`, `:seon.fn/spec`
and the optional digest. The commit path's later install is then an idempotent re-wrap. Settling
MCP evaluations into rows is a separate policy question and is not needed for arming.

**Class regression.** Evaluate a contracted `defn` through `evaluate` with no settlement. Then
calling it with an invalid argument must return the declared `:malli.core/invalid-input` refusal,
never a host exception.

**Cost.** Default carries one agent-admitted override, `my.agents.root/largest`. Every
acquisition of a new commit therefore finds `overridden` non-empty (`eval.clj:1919-1922`) and runs
`seon.fn/reverse-closure` (`:1924`). `gate-sets-in` (`src/seon/fn.clj:1556-1597`) first reads
whole-program joins: 6,859 identity rows, 19,232 file-reference tuples, declared-edge rules and
handlers. Only then does it walk from one seed, finding 1 affected function and 1 test.

JVM measurements on default's head, basis-t 536872648:
- First call of the day: **873 ms**. The same call three times after that: 16–17 ms each, 53 MB
  allocated.
- The component queries, warm: identity 3.8 ms, file-references 10.3 ms, declared edges 0.2 ms,
  handlers 0.2 ms.

So this is cold index acquisition plus armed validation of large query results, and it is
whole-program work on every new commit. The 3.2 s / 2.3 s figure from today's run was not
reproduced on a warm commit. A fresh branch off an already acquired commit took 242 ms end to
end, because `base-ctx` is memoized by program identity. Warm trivial evaluations allocate about
9.6 MB, as recorded by `:seon.eval/allocated-bytes` for `(+ 1 2)`, a 5 ms evaluation. The 330 MB
was not reproduced; it most likely includes the cold acquisition.

Recommendation: key the closure by the overridden digests and the call-edge datoms it reads,
using the Datahike commit identity only where it is exact. An alternative is to walk only
`:avet` datoms from the seeds (`gate-set-in` already does this), and fetch the global
identity/file-reference maps lazily for reached entities instead of materialising them up front.
The target is sub-second cold time. Record a clock row before and after.

## Probe residue

`scratch.probe/probe-bare` and `scratch.probe/twice` remain in `exp-mcp-check`'s retained
private SCI context. They are not program rows and are disposable with that branch.
