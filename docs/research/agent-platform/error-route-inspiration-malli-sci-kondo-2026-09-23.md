---
type: research
created: 2026-09-23
status: evidence (read-only survey)
source: Opus 5.5 read-only survey (Explore) of reference-code/ — malli, sci, edamame, rewrite-clj, clj-kondo, kaocha
purpose: prior art for the one error route (fix schedule row 0; AGENTS.md "The error policy")
---

# Error-route prior art: malli, sci, edamame, rewrite-clj, clj-kondo, kaocha

Paths are relative to `reference-code/`. Read directly; nothing was run.

## 1. malli: one shape and one pluggable report function

- **Shape.** Every error goes through `-exception` (`malli/src/malli/core.cljc:203`) and `-fail!` (`:205-207`), as `{:type ::kw :message ::kw :data {...}}`. About 40 `(-fail! ::kw {data})` call sites (e.g. `::invalid-schema` at `:333`). The call site gives a keyword and a data map; no policy is decided there.
- **Validation errors are data:** `-error` (`malli/src/malli/impl/util.cljc:19-21`) gives `{:path :in :schema :value :type}`; `explain` is at `core.cljc:2659`.
- **The `:report` seam.** `-instrument` (`core.cljc:3118-3139`) defaults `(update :report #(or % -fail!))` (`:3136`).
  - Each wrapped fn calls `(report ::invalid-input {...})` or `::invalid-output`/`::invalid-guard`/`::invalid-arity` (`:2212-2219`, `:2290`).
  - The contract is `(fn [type data])`. If it throws, execution stops (panic). If it returns, the value flows on (record and continue). That is the whole dial.
  - `malli/src/malli/instrument.clj:30` wraps `:report` to add `:fn-name`, so context is added in ONE place. Per-var override: `:malli/report` metadata (`:144`).
- **Global flip:** `malli/src/malli/dev.clj:11-21` `alter-var-root`s `#'m/-fail!` to report, then rethrow; `:23-24` restores it. `start!` defaults to `{:report (pretty/reporter)}` (`:44`).
- **Policies:** `pretty/reporter` prints (`malli/src/malli/dev/pretty.cljc:164-171`); `pretty/thrower` prints, then throws (`:173-179`).
- **Formatting is separate from policy.** `v/-format` is a multimethod on `(:type (ex-data e))` (`malli/src/malli/dev/virhe.cljc:190`), with a `::default` (`:192`), and one method per type (`pretty.cljc:41-140`). `-location` (`virhe.cljc:134-143`) skips library frames (`pretty.cljc:16`).
- **Humanized, bounded, hinted:**
  - `humanize` (`malli/src/malli/error.cljc:374-390`); `error-message` with a locale chain (`:288-305`); `error-path` (`:283-286`).
  - "Should be spelled X" hints (`:59-64`, `:266-271`).
  - Valid values are masked as `'...` (`pretty.cljc:17`).
- **Weakness: the cause chain is lost.** `-exception` takes no cause; only `:3095-3096` keeps one, and then in data.

## 2. sci: errors that carry location and keep the cause

- `throw-error-with-location` (`sci/src/sci/impl/utils.cljc:62-70`), 79 call sites, reads `:line :column :file` from node metadata and sets `:type :sci/error`. It takes no cause.
- **One rethrow seam:** `rethrow-with-location-of-node` (`utils.cljc:121-181`), generated around every call node by `gen-return-call` (`sci/src/sci/impl/analyzer.cljc:1583-1600`).
  - It conjs frames onto one shared `:sci.impl/callstack` (`:142-147`).
  - It wraps only once (`:150-154`).
  - The new ex-info keeps the original as its REAL cause (`:164-175`).
  - Inside a user `try` it doesn't wrap (`:123-134`).
- `rewrite-ex-msg` (`:90-119`) turns munged names into `ns/name`.
- Type hierarchy: `(derive :sci.error/parse :sci/error)` (`:16`); parse errors are re-wrapped with the edamame exception as cause (`sci/src/sci/impl/parser.cljc:180-190`).
- **Signals that must not be caught:** `interrupt-ex?` (`utils.cljc:47-56`); `eval-catches` rethrows interrupts before any user catch matches (`sci/src/sci/impl/evaluator.cljc:88-90`).
- Public API: `sci.core/stacktrace` and `format-stacktrace` (`sci/src/sci/core.cljc:430-438`).

## 3. edamame: parse errors with location

- `throw-reader` (`edamame/src/edamame/impl/parser.cljc:44-59`), 32 call sites: `{:type :edamame/error <row> <col>}`, with the location keys taken from ctx.
- `parse-to-delimiter` (`:284-295`) rethrows, adding a breadcrumb to `:edamame/containers`, with the original as cause.
- Actionable data: `:edamame/expected-delimiter` and `:edamame/opened-delimiter-loc` (`:217-219`, `:302-304`).

## 4. rewrite-clj: the counter-example

- `throw-reader` (`rewrite-clj/src/rewrite_clj/reader.cljc:19-30`) gives `{:msg :row :col}`, with no `:type` and no cause, and elsewhere uses a bare `ex-info` with `{}` (`custom_zipper/core.cljc:86`).
- So clj-kondo has to special-case it. Location without a type is not enough.

## 5. clj-kondo: findings as data, one registration point, one reporter

- **Finding shape:** `{:filename :row :col :end-row :end-col :type :message :level}` (`clj-kondo/src/clj_kondo/impl/utils.clj:231-236`).
- **One registration point:** `reg-finding!` (`clj-kondo/src/clj_kondo/impl/findings.clj:74-99`), about 255 call sites.
  - Policy lives there: level from config, `:off` drops, inline ignores (`:24-72`).
  - Hooks call the same function (`hooks_api.clj:96-99`).
- **The dev/prod dial, verbatim:** `impl/analyzer.clj:4535-4547`, `:4562-4564`: `(catch Exception e (if dev? (throw e) (run! #(findings/reg-finding! ctx %) (->findings e filename))))`. `dev?` comes from `CLJ_KONDO_DEV` (`impl/core.clj:19`). `->findings` normalizes foreign error shapes (`analyzer.clj:4379-4405`). Hook crashes become findings too (`:3620-3630`; `impl/hooks.clj:316-331`).
- **Dedup key:** `(juxt :filename :row :col :type :cljc)` (`clj-kondo/src/clj_kondo/core.clj:283`); `collapse-cljc-findings` (`impl/core.clj:715-719`); `summarize` counts (`:705-710`).
- **One reporter:** `print!` (`core.clj:21-63`) with `:text :edn :json :sarif` output; the pattern is configurable (`impl/core.clj:23-45`); `--fail-level` → exit code (`main.clj:163-175`).
- **Weakness:** the conversion keeps only the message; the stack is dropped.

## 6. kaocha: one reporter chain, fail-fast, global plugins

- **Event shape:** a map with `:type`, classified by a hierarchy (`kaocha/src/kaocha/hierarchy.clj:73-83`); third parties join with `derive!`.
- **One chain:** `resolve-reporter` (`kaocha/src/kaocha/api.clj:69-95`) takes the user reporters plus `report-counters`, `history/track`, `dispatch-extra-keys` and optionally `fail-fast` (`:73-79`).
  - Composed as `run!` over the vector (`config.clj:281-283`).
  - Guarded: a reporter error is printed with its cause, and fail-fast passes through (`api.clj:80-91`).
  - Installed with `with-redefs [t/report ...]` (`:35-36`).
- **Fail-fast is a reporter** (`report.clj:352-360`) that throws a marker; catch sites let the marker through (`type/var.clj:25-28`). Uncaught exceptions become events via `report-exception` (`report.clj:469-474`).
- **Context added in one spot:** `do-report` (`monkey_patch.clj:35-45`) adds the testable, the test plan, the pre-report hook and file/line; `history/track` adds contexts and vars (`history.clj:13-21`).
- **Plugins:** `run-hook` reduces over a bound chain (`plugin.clj:88-100`; `with-plugins` at `:9-10`).
- **Aggregation and bounded output:** `result/totals` (`result.clj:29-39`); `minimal-test-event` (`util.clj:39-47`); `print-cause-trace` in the error summary (`report.clj:311-325`).

## Which ideas fit Seon

1. **One constructor, one reporter** (malli `-fail!`/`-exception` + clj-kondo `reg-finding!`). Every catch calls `(seon.error/report! ::kind ctx throwable)`, with fixed keys: kind, owner (the agent to wake), where (ns/var/file/line/col), data, chain. ALWAYS keep the Throwable as the real cause; only sci does this right (`utils.cljc:174`).
2. **The dial is the reporter function** (malli `:report` `core.cljc:3136`; kondo `if dev? throw else record`, `analyzer.clj:4536`).
   - `report!` reads the `:panic`/`:record` dial from database config.
   - `:record` returns a value; `:panic` records FIRST, then rethrows (malli `dev.clj:17-19`).
   - Policies compose as a vector, `[store! wake! (when panic throw!)]` (kaocha `api.clj:73-79`), with the chain itself guarded (`:80-91`).
   - Enforce the call with a clj-kondo hook flagging any `catch` that neither calls `report!` nor rethrows.
3. **Store the full chain as data:** per link, the class, message, bounded ex-data and the top N first-party frames (malli `-location`); the sci callstack for agent code (`sci/core.cljc:430-433`). Wrap once: if the error already carries an id, rethrow it as is (sci `utils.cljc:150-154`).
4. **Fingerprint and count** (requirement A).
   - The hash covers kind + owner + where + root-cause class + a normalized message (ids and numbers stripped) + the malli path or type. It is the analogue of clj-kondo's `(juxt :filename :row :col :type)` (`core.clj:283`).
   - Upsert on a unique fingerprint: increment the count, set last-seen, keep the full chain only for the first and latest occurrences.
   - Coalesce bursts in memory (kaocha `report-counters`/`result/totals`) and flush on a bound.
   - Wake the owner only when a fingerprint is new, reappears after being resolved, or crosses a threshold.
5. **Actionable for the orchestrator and lanes** (requirement B).
   - A humanized message (malli `humanize` `error.cljc:374`; sci `rewrite-ex-msg` `utils.cljc:90`).
   - The location (sci `utils.cljc:65`), or edamame breadcrumbs for parse errors.
   - The offending value, bounded (malli `'...`; kaocha `minimal-test-event`).
   - A hint (`error.cljc:59-64`; a docs link, `pretty.cljc:57`).
   - One render multimethod on kind with a default (`virhe.cljc:190-192`), which the MCP/status tool and log lines share; plus an EDN form agents can query (clj-kondo `:edn`, `core.clj:46-52`).
6. **Sentinels always propagate** (sci `interrupt-ex?` `utils.cljc:51-56`; kaocha fail-fast pass-through `var.clj:25-27`): `report!` rethrows interrupt, cancel and panic markers before recording.
7. **Kinds as a hierarchy** (`derive`; kaocha `hierarchy.clj`): owner routing and severity dispatch on parents.
