---
type: research
status: active
tags: [research, runtime, operator]
---

# clj-reload evaluation for the in-JVM reload verb (2026-08-03)

Answers the question left open by
[reloaded-ergonomics-sweep-2026-08-03.md](reloaded-ergonomics-sweep-2026-08-03.md)
§4: should `seon.operator/reload!` be `clj-reload.core/reload`, a Seon-owned
adaptation of its pattern, or should the verb not exist at all?

Sources read end to end: `reference-code/clj-reload` at pin `61c6fa7` (1.0.0) —
`src/clj_reload/core.clj` (437 lines), `keep.clj` (190), `parse.clj` (175),
`util.clj` (149), and `README.md` (479); the reloaded-ergonomics sweep above;
`src/seon/flow.clj:82-114`; `src/seon/sci/eval.clj:155-225, 795-860, 919-975`;
`resources/seon/operator/runtime.clj`; `src/seon/cluster.clj:640-661`;
`bin/seon-hook:894-936`. Probe transcripts are inline; the probe fixtures are in
`tmp/reload-probe/` (project-local, gitignored).

**Verdict up front: posture (c) — do not adopt, do not adapt, and do not ship a
`reload!` verb that unloads namespaces.** The evidence is a measured
incompatibility, not a preference: Seon's live SCI context holds 273 real host
`clojure.lang.Var` objects across 36 first-party namespaces, and every flow proc
holds its step-fn as a Var object. clj-reload's core mechanism is `remove-ns`,
which detaches those Vars while leaving them callable at their old roots — so a
single `reload!` would silently freeze the running system on stale code with no
error anywhere. §4 states what each posture gives up and what a lane would have
to falsify to overturn this.

## 1. What clj-reload actually does

### Change tracking

`scan-impl` (`core.clj:77-130`) walks `(:dirs *config*)` with `file-seq`, keeps
files matching `(:files *config*)` (default `#".*\.cljc?"`), and partitions them
by `(<= (util/last-modified file) since)` — plain `File.lastModified` against a
watermark in the state atom (`util.clj:92-93`). Files newer than `since` are
re-read; a file that fails to read becomes an entry in `files-broken` rather
than an abort, and its exception is only rethrown for namespaces that are both
currently loaded and not excluded (`core.clj:223-226`). This is what backs the
README's TNS-65 claim (`README.md:452`): broken auxiliary files do not break the
workflow, because `load?` filters on `@@#'clojure.core/*loaded-libs*`
(`core.clj:211, 246-254`).

Reading is a **parse, not an eval**. `parse/read-file` (`parse.clj:69-120`)
reads forms with `*read-eval* false`, `*suppress-read* true`, and a dummy
`LispReader$Resolver` (`util.clj:22-37`), collecting: `ns` forms and their
`:require`/`:use` (`parse-require-form`, `parse.clj:16-50`, which correctly
drops `:as-alias`), top-level `in-ns`, top-level `require`/`use`, and any form
tagged for keeping. That covers the README's two tools.namespace deltas at
`README.md:456-458` (split namespaces, standalone `require`), and I confirm both
are implemented, not just claimed.

### Graph and ordering

`parse/dependees` (`parse.clj:122-131`) inverts `:requires` into
`{ns -> #{downstream}}`. `transitive-closure` (`:133-148`) expands a changed set
downstream. `topo-sort` (`:163-175`) is repeated root-extraction with `sort` for
determinism, and reports cycles by name (`report-cycle`, `:152-161`). Unload
order is the topological order **reversed** (`core.clj:238-244`); load order is
forward (`:257-262`). Both filters (`unload?`, `load?`) are applied *before and
after* the closure expansion, so an excluded namespace neither unloads nor pulls
its dependents in.

### Unload / load

`ns-unload` (`core.clj:270-285`) calls the unload hook if the namespace defines
one, swallowing hook exceptions deliberately with a comment explaining why
(`:278-282`), then `(remove-ns ns)` and `(alter *loaded-libs* disj ns)` in a
`dosync`. `ns-load` (`:287-302`) slurps the file and hands it to
`Compiler/load` with a synthesized classpath-shaped path (`util.clj:121-124`).

### `:clj-reload/no-reload`, `:no-unload`, and the exclusion sets

Four mechanisms, two of them metadata:

- `:no-reload` in `init` opts — the namespace is neither unloaded nor loaded,
  and (because `unload?`/`load?` are applied inside the closure) it does not
  propagate the reload to its dependents;
- `:no-unload` in `init` opts — the namespace is loaded "on top" of its previous
  state, so `def` re-evaluates but the namespace map is not cleared;
- `^:clj-reload/no-reload` / `^:clj-reload/no-unload` on the **ns symbol**,
  read out of `(:meta (namespaces %))` at `core.clj:232-235, 252`;
- `README.md:165` states "`:no-reload` implies `:no-unload`"; verified in source
  — both `unload?` and `load?` test `no-reload` (`core.clj:235, 253`).

### `defonce` and keep-forms

`parse/read-file:109-117` marks a form for keeping when its tag is `defonce`, or
when `^:clj-reload/keep` is on the form or on its name symbol. Keeping is a
two-phase stash-and-patch:

- **Resolve** (before `remove-ns`, `core.clj:320`): `keep/resolve-keeps`
  captures the live Var (`keep.clj:37-48`), the record/type constructors
  (`:50-81`), or the protocol plus its `:method-builders` (`:91-117`).
- **Patch** (at load, `keep.clj:177-190`): the file's text is re-emitted with
  the kept form **textually replaced** (`patch-file`, `:135-169` — a
  read-and-rewrite that pads to preserve line and column numbers) by a `def`
  reading the stashed value out of a temporary `clj-reload.stash` namespace,
  which is then `remove-ns`'d in a `finally`.

So `^:clj-reload/keep (defrecord Z [])` does not recompile the class at all: the
patch emits `(clojure.core/import my.ns.Z) (def ->Z clj-reload.stash/->Z)`
(`keep.clj:78-81`). That is genuinely the strongest thing in the library and it
is exactly the mechanism tools.namespace lacks. `defprotocol` keeping is more
invasive — it re-points `:method-builders` by `alter-var-root` on the protocol
map (`keep.clj:83-89`) — but it is honest about being a fixed set:
`keep-methods` is a multimethod whose `:default` throws
(`core.clj:399-407`), so an unsupported form fails loudly.

### Errors mid-reload

A load failure pushes the namespace back onto `:to-unload` and either throws an
`ex-info` carrying `{:unloaded :loaded :failed}` or returns that map under
`{:throw false}` (`core.clj:367-381`). The remaining `:to-load` stays in the
state atom, so a subsequent `reload` resumes — the README's "call reload again"
recovery (`README.md:104`) is real, not aspirational. The whole of `reload` and
`unload` runs under one reentrant lock (`core.clj:57-65, 309, 358`).

### The library's own warning

`README.md:321-327` is the decisive paragraph for Seon: "Clj-reload works by
removing whole namespaces… if you store a link to a var somewhere, it'll be
pointing to the old version after reload." Its prescribed workaround is
`(resolve 'full.ns/sym)` at every call site.

## 2. Seon's current reload story, and what it does not handle

The law (`AGENTS.md`, "Live update is two cases"): graph definitions reference
transforms as vars, so re-evaluating a `defn` changes proc behavior with no
restart; topology changes rebuild the graph. `seon.flow/var-process`
(`src/seon/flow.clj:82-114`) enforces the first half at construction time — it
**throws** on a non-var step, with the docstring "an anonymous step captures its
closures and hot reload silently stops applying to running graphs." core.async
itself states the same contract upstream: using a var as the step-fn "enables
hot-code-reloading of the proc logic in a flow"
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:177-181`).
Nine procs are built this way (`flow.clj:181, 374, 593, 784, 808, 852, 878, 942,
963`).

The edit hook does **not** reload the JVM. `current-source-feedback`
(`bin/seon-hook:904-936`) shells `bin/seon init --changed` to publish program
facts to `current-src`; nothing in `bin/` or `src/` requires `clj-reload` or
`tools.namespace` (verified: `grep -rn "clj-reload|tools.namespace" deps.edn
src/ script/ bin/` returns nothing). So today's in-JVM story is a human typing
`(require 'ns :reload)` at the prepl.

### Measured failure classes of `(require ... :reload)`

Probe fixture `tmp/reload-probe/src/probe/{a,b}.clj`, run under
`clojure -Sdeps '{:paths ["src"]}'`:

```
PROBE 1: stale deleted var after (require :reload)
  before: probe.a/gone = #'probe.a/gone -> :original-value
  after :reload, probe.a/gone still resolves? true  value = :original-value
PROBE 2: defonce under :reload
  probe.a/state generation after :reload = 1
PROBE 3: downstream namespace sees new probe.a/f without being reloaded?
  (probe.b/calls-f) = :v2
PROBE 4: downstream STALE case -- direct var capture
  probe.c/snapshot (captured at load) = :v2
  after a changes to :v3, probe.c/snapshot (NOT reloaded) = :v2
  probe.c/captured invoked = :v2
```

- **Deleted vars linger** (probe 1). Real and unfixable without `remove-ns`.
  This is the README's buffer-eval criticism (`README.md:414-430`) and it holds.
- **`defonce` survives `:reload` natively** (probe 2) — because `defonce`
  expands to a `has-root?` guard, not because anything special happened.
  clj-reload's keep machinery *restores parity* here, it does not exceed it.
  This matters: Seon has 39 `defonce` forms in `src/` (schema registry
  `src/seon/schema.clj:451,568`, the SCI deadline timer
  `src/seon/sci/kernel.clj:35`, the process-root holder
  `resources/seon/operator/runtime.clj:11-15`), and every one is already safe
  under `:reload` and would need clj-reload's keep to become safe again *after*
  clj-reload broke it.
- **Call-through-var works** (probe 3): a downstream namespace that was not
  reloaded still sees the new definition, because a `(a/f)` call site compiles
  to a Var invocation. This is the mechanism the law relies on and it is real.
- **Value capture is stale** (probe 4): `(def captured a/f)` captures the fn
  object, so a downstream `def` — not a call — freezes. Load-order sensitivity
  reduces to this one case.
- **Record/protocol churn** (probe 5, `tmp/reload-probe/probe2.clj`): after
  `(require 'probe.p :reload)`, `(identical? old-class (class (p/->R 1)))` is
  **false** — a new class object. Old instances survive protocol calls only
  because `defprotocol` expands to `alter-var-root … merge`, preserving
  `:impls`; anything doing `(instance? R x)` against the new class fails. Seon
  has 7 such forms total (`src/seon/flow.clj:208,516`,
  `src/seon/print.cljc:9,80,139,159`, `src/seon/render/hiccup.clj:69`), all
  process-local, none crossing a persistence boundary.

## 3. The live-runtime hazard, measured

Probes run read-only against the live `default` cluster (pid 44547) via MCP
`eval_clj`, JVM mode:

```clojure
;; first probe
{:ctx? true :ns-count 73
 :seon-flow-sample [->CountedDroppingBuffer clojure.lang.Var "seon.flow"]}
;; second probe: entries in the SCI ctx that are REAL host Vars of that same ns
{:host-var-namespaces 36
 :total-host-vars 273
 :sample [[seon.flow 28] [seon.cluster 26] [seon.ai 21] [seon.render.web 18]
          [seon.cluster.work 14] [seon.sci.eval 13] [seon.cluster.store 12]
          [seon.sci.kernel 12] [seon.config 11] [seon.fn 10] [seon.error 10]
          [seon.bootstrap 9]]}
```

This is by design, and the design is documented as depending on Var identity:
`install-loaded-first-party-namespaces!`
(`src/seon/sci/eval.clj:807-820`) states "Direct bindings retain those Vars… Both
paths therefore observe a re-evaluated `defn` without reacquisition." One shared
SCI ctx per cluster is the vocabulary-table ruling (#27 + #20) — every agent in
the cluster reads through those 273 Var objects.

And Var identity is exactly what clj-reload does not preserve. Probe 7,
`tmp/reload-probe/probe3.clj`, clj-reload at the vendored pin:

```
PROBE 6: clj-reload on the same fixture
  Unloading probe.b / Unloading probe.a / Loading probe.a / Loading probe.b
  reload => {:unloaded [probe.b probe.a], :loaded [probe.a probe.b]}
  deleted var probe.a/gone still resolves? false          ; the win
  defonce state survived (mutation kept)? {:generation 1, :mutated true}
  downstream probe.b reloaded and sees :v2? :v2
PROBE 7: var identity across clj-reload
  same Var object after reload? false
  invoking the OLD captured var: :v2                      ; stale, silently
  ns-name of old var: probe.a   find-ns => #object[…Namespace… probe.a]
```

The old Var still reports `probe.a` as its namespace and still invokes — at the
**pre-reload root**, forever, with no exception. Applied to Seon:

| Live holder | What a clj-reload cycle does to it | Mitigated by |
|---|---|---|
| Shared per-cluster SCI ctx (273 host Vars, `sci/eval.clj:840-850`) | every copied Var detaches; agents keep executing old code with no error | **nothing in clj-reload.** Would need re-`acquire!` after every reload, i.e. Seon owning the seam anyway |
| Flow proc step-fns (9 `#'` step vars, `flow.clj:181-963`) | the running graph holds the old Var; hot reload silently stops applying — the precise failure `var-process` throws to prevent | **nothing.** `:no-reload 'seon.flow` protects the procs but then flow itself is never reloadable, and it blocks the 75-namespace downstream closure below |
| Armed agent mid-turn | a `remove-ns` between two forms of one turn detaches the callee's Var; the in-flight eval finishes against old code, the next turn sees new | **nothing.** clj-reload's `ReentrantLock` (`core.clj:57`) serializes reloads against each other, not against evals |
| `io-prepl` server / MCP sessions | `clojure.core.server` holds the accept fn as `#'seon.cluster/mcp-io-prepl` resolved at `start-server` time; reloading `seon.cluster` detaches it | `:no-reload 'seon.cluster` — but that is a 68-namespace closure |
| Process-root holder (`resources/seon/operator/runtime.clj:11-15`) | **untouched**, structurally: `resources/` is on `:paths` (`deps.edn:8`) but is not a `source-root` (`src/seon/cluster.clj:646-649`) | the sweep's Q1 finding; correct and already load-bearing |
| Process-local `defonce` state (39 in `src/`) | destroyed by `remove-ns`, restored by keep | **`defonce` keep works** (probe 6) — but only restores what `:reload` never broke |
| `deftype`/`defrecord` (7 forms) | new classes, as with `:reload` | `^:clj-reload/keep` genuinely fixes this |

### Blast radius

Using clj-reload's own parser over Seon's `src/` + `test/`
(`tmp/reload-probe/closure.clj`, 162 first-party namespaces parsed):

```
  edit seon.db            => downstream closure 110 of 162
  edit seon.schema        => downstream closure 138 of 162
  edit seon.flow          => downstream closure  75 of 162
  edit seon.sci.eval      => downstream closure  74 of 162
  edit seon.cluster       => downstream closure  68 of 162
  edit seon.render.hiccup => downstream closure  81 of 162
  edit seon.ai            => downstream closure  74 of 162
```

Editing `seon.schema` unloads 85% of the tree in one keystroke, taking every one
of the 273 SCI-held Vars and all 9 proc step Vars with it. There is no
`:no-reload` set that both protects the live holders and leaves a useful reload
scope: the namespaces one most wants to reload are precisely the ones the
running system holds Vars into.

## 4. Verdict

### (a) Adopt clj-reload as `seon.operator/reload!` — REJECTED

Scoping it would mean `:dirs ["src" "test"]` (matching `seon.fn/source-roots`,
`src/seon/fn.clj:19-21`) and a `:no-reload` set covering `seon.flow`,
`seon.cluster`, `seon.sci.eval`, `seon.sci.kernel`, `seon.schema`, `seon.db`.
Those six exclusions plus their required-by closure leave almost nothing
reloadable, and a hand-maintained exclusion set is itself the banned shape
("no hand-maintained lists" — every exception must be a computed rule). Making
it a computed rule is possible in principle — "exclude any namespace with a Var
in the live SCI ctx or in a running graph" — but that rule evaluates to *nearly
every first-party namespace*, which is the answer, not a configuration.

Gives up: nothing Seon has. Costs: silent staleness, the worst failure mode this
codebase has, in the one process every agent runs in.

### (b) Adapt the dependency-tracking pattern — REJECTED as framed, with one carve-out

Seon already has a strictly better dependency graph than clj-reload's text
parse: `:seon.fn/calls` and `:seon.ns` facts in the database, from one clj-kondo
analysis, queryable by Datalog. Reimplementing `parse/dependees` over files
would be a *second* mechanism for something the program graph owns — and the
standing principle is that a question about code is a query, never a re-parse.

The one carve-out worth recording: clj-reload's parser is a legitimate
**offline** tool. `tmp/reload-probe/closure.clj` above used it as a
zero-dependency measuring instrument without loading Seon, which is a fine use
and needs no adoption.

Gives up: nothing. It is the same capability we have, expressed worse.

### (c) Keep `(require … :reload)` plus Var indirection, and document the edges — RECOMMENDED

What it gives up, stated plainly:

1. **Deleted vars linger** (probe 1). A removed `defn` stays callable until the
   JVM restarts. Detection is cheap and already available: `bin/seon init
   --changed` republishes `:seon.fn` facts, so a Var present in `ns-publics` but
   absent from the namespace's current program rows is a **query**, not a
   reload problem. That query is the honest fix, and it belongs in
   `seon.problems` where readiness already surfaces it
   (`src/seon/cluster.clj:1763-1806`).
2. **`(def x other.ns/f)` freezes** (probe 4). Genuine, narrow, and already the
   thing `var-process` throws about for procs.
3. **Record/protocol classes churn** (probe 5). 7 forms, all process-local.
4. **No downstream ordering.** Mostly moot: probe 3 shows call sites see new
   definitions without being reloaded. It bites only for macros and for
   top-level value capture.

Consequently `seon.operator/reload!` as sketched in the sweep's §4 table should
**not ship backed by clj-reload**. The sweep already anticipated this — "if
adopted; otherwise omit the verb rather than hand-roll one"
(reloaded-ergonomics-sweep §4). Omit it. The verb that is actually missing is
`seon.operator/require!` — a thin, honest `(require ns :reload)` over an
explicit namespace list, returning what it loaded — and it is not worth a row of
its own until someone wants it.

### Falsifiers, if the owner overrules

An implementation lane adopting clj-reload must pass all four; each is a
concrete probe, not a review item:

1. **Armed agent mid-turn.** Arm an agent, start a turn calling a first-party
   function, `reload!` the callee's namespace during the turn, and show the
   turn completes and the *next* turn observes the new definition. Currently
   expected to fail the second half: the SCI ctx holds the detached Var.
2. **Proc step-fn Var.** `reload!` a namespace owning a `var-process` step
   (e.g. `seon.flow/mailbox-step`), then show the running graph executes the new
   step. Currently expected to fail — probe 7 is the direct counter-evidence.
3. **SCI ctx referencing a deleted var.** Delete a public `defn` an agent calls,
   `reload!`, and show the agent's next call returns an error value rather than
   silently executing the removed implementation.
4. **No stale Var after a wide reload.** After `reload!` of `seon.schema`
   (138-namespace closure), assert that zero Vars in the live SCI ctx are
   detached — `(not= v (ns-resolve (.ns v) (.sym v)))` over all 273 — and that
   `runtime_status` still reports every proc replying to ping.

Passing 1-4 requires re-`acquire!`ing the SCI ctx and rebuilding every graph
after each reload, at which point the reload is a `stop!` + `start!` under
another name — which `seon.operator/restart!` already is.
