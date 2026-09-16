---
type: research
status: active; read-only sweep, 41 rows, ranked rip-out list at the end
created: 2026-09-16
tags: [research, workarounds, dissolution, bin/test, seon-hook, config, program-graph, classpath]
---

# Workaround inventory — mode switches, silent fallbacks, tuned constants, second paths

Read-only sweep at `steward-platform` HEAD `90632c26b`, answering the owner's
standing order (2026-09-16): *"find all terrible shitty ideas and rip them
out."* Two were found and removed before this note: the `tmp/test-slots/
orchestrator-only` marker file that refused every gate invocation without
`SEON_TEST_ORCHESTRATOR=1` (deleted at `fa971495f`) and the second write
validator for retired rows (`src/seon/db.clj:2913`, being replaced). This note
is everything else.

Read end to end for this note: [AGENTS.md](../../../../AGENTS.md) §0–§7;
`bin/test` (807 lines), `bin/test-fast`, `bin/test-check`, `bin/_test-slot`,
`bin/seon-hook` (1,436) and `.claude/seon-hook.edn`; `dev_cache.clj` (611);
`src/seon/{cluster,config,db,program,schema,test,turn,search,issue,instrument}`
and the `cluster/`, `test/`, `sci/`, `render/`, `fn/` regions named per row.
Sibling passes own their own ground and are cited, not repeated:
[test-execution-model](test-execution-model-2026-09-16.md) (D1–D6),
[evaluation-write-path-and-retired-identities](evaluation-write-path-and-retired-identities-2026-09-16.md)
(D1–D7), [schema-key-audit](schema-key-audit-2026-09-16.md), and the
recompute-from-scratch inventory.

Every row below was opened at the line cited. No tests, no prepl, no JVM.

---

## 1. Mode switches, env vars, marker files

Every `SEON_*` read in `src/`, `bin/`, `script/`, listed.

| file:line | what it is | what it works around | still needed? | dissolution |
|---|---|---|---|---|
| `bin/test:97-103` | `SEON_TEST_FULL=1` sets the `full` tier | a second spelling of `--full` for a caller that cannot pass an argument | **no** — no caller in the tree sets it; added 2026-08-03 (`01142eade`) alongside the flag itself | delete the `case`; `--full` is the one spelling |
| `src/seon/schema.clj:723-727` | `SEON_APPLICATION_DIGEST` + `SEON_PREPROCESSED_RELEASE_IDENTITY` must be equal 64-hex to bind `*verified-release-identity*` | module load happens before process main, so a release digest cannot come from cluster facts yet | conditionally — there is no release manifest in this tree; nothing sets either variable | delete until a release artifact exists; git is the archive |
| `bin/test:79-80`, `src/seon/test/runner.clj:3775` | `SEON_TEST_RESULT_CLUSTER` / `SEON_TEST_RESULT_ROOT` shadow `--result-cluster` / `--result-root` | two ways to say one thing | no | keep the flags, delete the env reads |
| `src/seon/test/runner.clj:508-522` **and** `src/seon/test/cache.clj:25` | `SEON_TEST_SILENCE_SECONDS`, defaulting to `300` in **two** places | the suite watchdog and the cache child want the same bound | the bound is legitimate; the duplication is not | one declared accessor; `cache.clj` must not re-parse and re-default |
| `src/seon/test/runner.clj:2891` | `SEON_TEST_CLOJURE` overrides the worker launcher binary | a machine without `clojure` on PATH | no evidence of a caller | delete; `bin/_java-home-resolver` already owns toolchain selection |
| `bin/codex-agent:56` | `SEON_TEST_WORKERS` defaulted to 3 for every lane | lanes starving the machine (2026-09-15) | the cap is real | it is a cap, not a lane preference — belongs in `bin/test` as the derived cap, which `bin/test:687` already applies |
| `bin/_test-slot:14-15` | `SEON_TEST_SLOTS` (2), `SEON_TEST_SLOT_WAIT_SECONDS` (1800) | eight concurrent gate JVMs pinning the machine | yes; both are documented at `:4-13`, exhaustion is loud at `:53-61`, never a hang | **legitimate** — leave it |
| `bin/test:33-37` | re-exec via `SEON_TEST_SCRIPT_SNAPSHOT` | bash re-reading an edited script mid-run (36-minute gate, documented `:29-32`) | yes | **legitimate** |
| `bin/seon-hook:40,45` | `SEON_HOOK_CONFIG`, `SEON_HOOK_STATE_DIR` | the hook's own test fixtures need an isolated root | yes, tests use both | **legitimate** |
| `script/seon/dev/mcp.clj:18,22` | `SEON_PROJECT_ROOT`, `SEON_CLUSTER_DIR` | the MCP server is launched with no cwd guarantee | yes (`bin/mcp-server:5` sets the first) | `SEON_CLUSTER_DIR` derives a cluster NAME from a directory basename — a path-to-identity munge; hand the cluster name |
| `script/seon/fresh_operator.clj:40` | `SEON_OPERATOR_EPHEMERAL_OWNER_PID` | a lane's operator root should die with the lane | yes (`bin/codex-agent:40`) | **legitimate** |
| `src/seon/test/selection.clj:192-202` | the green basis is a checkout-local `tmp/test-basis/green-basis.edn`; when absent or unreadable, `read-basis` returns `nil` and the bare gate silently widens to every eligible test | the gate runs before any cluster exists | the file is not a fact, and its absence changes the tier with no word said | record the basis on `:current-src` like every other gate result (`test/runner.clj:1435` already records there); until then, SAY "no basis; widened" |
| `.claude/worktrees/gym-metric-validation` | a registered git worktree, 218 MB, on branch `gym-metric-validation`, holding the **deleted** CLJS pod (`src/seon/embed.clj`, `shadow-cljs.edn`, `SEON_EMBED`) | nothing current | no — CLJS is off by ruling (AGENTS §1) | `git worktree remove` after confirming no holder |

`(catch Throwable _ nil)` in `read-basis` (`selection.clj:202`) is its own
defect: a corrupt basis file and an absent one are the same answer.

---

## 2. Silent fallbacks

| file:line | what it is | what it works around | still needed? | dissolution |
|---|---|---|---|---|
| `src/seon/config.clj:404` | `(or (:seon.boot/cluster-name request) "default")` — and the name is then **written** into `:seon.config/cluster` as a durable fact | a caller that omitted the cluster | **no.** AGENTS §1: "nothing may assume 'the' cluster". Added 2026-07-29 (`68a5de51f`) | refuse; a config row with no cluster is a bug report |
| `src/seon/config.clj:549,553`; `src/seon/test.clj:631,663,854` | five more `(or cluster "default")` / `(effective db "default")` | same | no | same: one required argument |
| `src/seon/schedule.clj:605-609` | cluster name = `(or (db/q '[:find ?n . :where [_ :seon.cluster/name ?n]]) "default")` — *whichever* entity carries the attribute, else `"default"` | the schedule proc has no cluster in its value | **no** — this is a pre-read the authority will re-decide (AGENTS §0 owner law) | the proc's `:args` carry the cluster; `state` already holds `:seon.agent/id` at `:696` |
| `src/seon/cluster.clj:1937-1940` | `valid-source-manifest?` wraps schema validation in `(catch Throwable _ false)` | a validator that throws | no | a throwing validator is a fault, not an invalid manifest — the two are indistinguishable today, and the consequence is a silent complete rebuild |
| `src/seon/cluster.clj:2015, 2084, 2087, 2417` | four `full-source-refresh!` fallbacks from the incremental path | an incremental analysis that cannot prove itself | the escape is real; **four independent triggers is the defect** — `:2084` fires when `paths` is empty, i.e. on a no-op | one named refusal reason per fallback, reported, and no rebuild for the empty-path case |
| `src/seon/cluster.clj:625-651` | `retrying-source-change`: retry ONCE on "source changed under me" | a file edited between snapshot and publication | it hardens a mechanism against its own normal operation (AGENTS §0) | the observable is filesystem quiescence; the hook's own coalescing window (`.claude/seon-hook.edn:27`) is the event — wait for it, don't retry |
| `src/seon/reconcile.cljc:319-323` | `(or carried-projection handed-projection (do (projection-fallback) (projection-from-database db)))` | a bootstrap caller before carriage is installed | the fallback is **loud** (`db.clj:1049` prints, counts by caller) | **legitimate as built**; see the recompute-from-scratch pass for the cost |
| `src/seon/sci/eval.clj:1226,1252-1253,1277-1278` | `(edn/read-string (or (:seon.fn/spec row) "[]"))`, `(or (:seon.fn/arglists row) "()")`, `(or (:seon.fn/doc row) "")` in the `doc`/`dir` surface | a program row missing its contract | **no** — an absent contract renders identically to an empty one, in the surface an agent reads to decide how to call a function | typed unknown: "this row carries no contract" |
| `bin/seon-hook:1128-1140` (`publication-exception`) | EDN-reads every stdout and stderr line of the operator child, two nested `(catch Exception _ nil)` | extracting a failure envelope from mixed output | no | the operator should write its failure to a known file (it already writes `logs/current-source-failure.log`); reading the child's console is the workaround |
| `bin/test-check:56` | `(not (pos? (or (:seon.test/pass-count result) 0)))` | a result map missing the key | the *direction* is right (absent ⇒ fail) but the key's absence and a genuine zero are conflated | require the key |
| `src/seon/search.clj:159-166` | `(try (namespace (symbol identity-value)) (catch Throwable _ nil))` | a non-symbol identity string | no | the attribute's declared type answers this; AGENTS §3 — symbols are stored as symbols |
| `src/seon/instrument.clj:183-184, 190, 413` | three `(catch Throwable _ …)` collapsing every cause into `:failed` / `nil` | arglist and violation rendering must never throw | totality is right; **losing the cause is not** | keep the catch, carry `(ex-message failure)` — `:189` already does this one line away |
| **146 `requiring-resolve` call sites** in `src/` (`grep -c`), incl. `src/seon/turn.clj:2025` resolving `seon.turn/planned-sources` — **its own namespace** | a load-cycle dodge, resolved on EVERY call | genuine cycles exist (`seon.db` ↔ `seon.error`, `seon.schema` ↔ `seon.schema.edn`) | a self-namespace resolve is a forward reference, not a cycle; and a per-call `requiring-resolve` is the fetch-at-call-time defect AGENTS §2.1 names as the recurring performance killer | `declare` for the forward references; one `delay`-held var per genuine cycle boundary; measure the rest |

---

## 3. Tuned constants standing in for events

The AI retry family is **legitimate and exemplary**: `config/default.edn:447-484`
declares six dials, each with a unit line and a provenance line, and
`resources/seon/schemas/seon.ai.retry.edn` types them. The three `Thread/sleep`
calls in `src/` are all bounded by a declared value
(`turn.clj:4522` a finite retry schedule, `schedule.clj:699` a computed delay to
a nominal instant, `render/web.clj:2591` the declared
`:seon.config.render/coalesce-ms`). The offenders are the bare literals.

| file:line | what it is | what it works around | still needed? | dissolution |
|---|---|---|---|---|
| `src/seon/cluster/export.clj:81` | `(def ^:private clone-deadline-ms 600000)` — ten minutes, no docstring, no config fact | a store clone that might be slow | the bound is required (AGENTS §2.3); its *value* names no observable | a config dial with a provenance line, or better: the clone's own completion event |
| `src/seon/eval/drive.clj:333` | `(await-fact! connection 120000 (str "bootstrap " id) …)` — two minutes hardcoded, while the very next `await-fact!` at `:335` uses the request's own `timeout-ms` | the bootstrap wait had no declared bound | no | the request already carries a bound; use it |
| `src/seon/bootstrap_drive.clj:155` | `:seon.sci.eval/time-limit-ms 30000` literal | needing an eval deadline where the config was not to hand | **no** — `config/result-caps (config/effective db cluster-name)` is read two lines above at `:145` | read `:seon.config.eval/time-limit-ms` from the same effective config |
| `src/seon/test/cache.clj:93-96` | keep the 3 most recent inactive bases, delete anything older than 24 h | unbounded disk under `target/test-published-bases` | the reclamation is needed; two magic numbers are not | reclaim on the observable — a base with no live reference (`referenced?` at `:52-60` already answers it) |
| `bin/test:679-691` | `logical_processors=2` when `getconf` fails; `worker_count = cores/2`; hard cap `3` | machine starvation (measured, documented `:682-686`) | the cap is grounded in a measurement; the `cores/2` shape and the `2` fallback are not | derive from the cap and the selection size alone; a `getconf` failure should say so, not assume a dual-core host |
| `bin/seon-hook:1123` | `(call-agy prompt 60000)` literal, while the same config block declares `:interval-seconds 120` | the review call needed a bound | no | one declared key in `.claude/seon-hook.edn` `:review` |
| `bin/seon-hook:910-912` **and** `:1113-1117` **and** `.claude/seon-hook.edn:43-45` | `max-code-length 40000`, `max-batch-length 200000`, `max-rubric-length 60000` written out **three times** | `:or` destructuring defaults plus `get`-with-default plus the config file | no | the config file is the declaration; a missing key is a refusal |
| `bin/seon-hook:1224, 1251` | `(get-in config [:current-source :timeout-seconds] 180)` twice, with the literal default repeated | same shape | no | same |
| `bin/seon-hook:61` | `hook-log-cap (* 1024 1024)` | unbounded diagnostic files | bound is right, value is undeclared | declare it with the observable it protects |
| `src/seon/cluster/prompt.clj:88-89` | `(if (:seon.error/kind rows) [] rows)` then `10 fallback-calibration` — **a refused query becomes an empty sample set**, which then silently takes the default calibration, and the sample count `10` is a bare literal | a query that may refuse, and a calibration that must always answer | no | a refusal is not "no samples"; return the typed unknown and declare the sample count (AGENTS §2.4) |

---

## 4. Second paths for one concern

Cross-checked against
[test-execution-model](test-execution-model-2026-09-16.md) §3, §5 and
[evaluation-write-path](evaluation-write-path-and-retired-identities-2026-09-16.md)
§1; their rows are listed here so the whole set is in one place.

| file:line | what it is | what it works around | still needed? | dissolution |
|---|---|---|---|---|
| `src/seon/program.cljc:11-14` **vs** `src/seon/db.clj:1018-1029` **vs** `src/seon/reconcile.cljc:57` | **three** derivations of "identity attributes": a hand-written literal vector, a query over installed `:db.unique/identity`, and a third over declaration forms. All three are live: `fn.clj:2638` uses the literal and `fn.clj:2560` uses the query, in the same file | the literal predates the query; the forms version serves a pre-install caller | no | one derivation over declaration forms, with the installed-schema filter as an argument; delete the literal |
| `dev_cache.clj:421,475` **vs** `src/seon/test.clj:106-111` **vs** `src/seon/fn/analyzer.clj:172-186` **vs** `src/seon/test/runner.clj:2892` | **four** classpath derivations: `tools.build/create-basis` twice; a raw `(slurp "deps.edn")` reading only `[:aliases :test :extra-paths]` relative to cwd; a second raw `deps.edn` read taking `:paths` + every alias, canonicalized; and `java.class.path` | each host needed "the classpath" and none could reach the others | no — and the `test.clj` one is the cause of the open issue `the-in-process-test-loader-cannot-load-a-namespace-needing-a-test-alias-dependency` | `dev_cache.clj:475-484` already holds an ordered `:classpath-roots`; hand that one value to every host |
| `src/seon/cluster.clj:1977-1990` (`full-source-refresh!`) **vs** `:2000-2110` (`incremental-source-refresh!`) | two publication paths, the second re-implementing the analyzer's change plan and escaping to the first at four points | incremental publication for the edit hook | the split is defensible; the *duplicated change classification* is not | let the analyzer emit the change plan once; the publication chooses a transaction shape, not a second algorithm |
| `bin/test` (807 lines of bash) **vs** `src/seon/test/fast.clj` **vs** `src/seon/test/check` | three gate entry points with three selection bases (file basis, loaded namespaces, stale test rows) | each was added for a different caller | no — the test-system PRD stage 1 already targets this | one `select` |
| `src/seon/test/cache.clj:85-97` (`reap!`) **vs** `dev_cache.clj:576` (`reap`) | two reclamation functions for two caches under `target/` | two caches | probably one cache | one reclaimer keyed on live references |
| `src/seon/turn.clj` — six writer calls mint an evaluation, four assert its terminal facts | see the evaluation-write-path note D1/D2 | — | no | one evaluation write path |
| `src/seon/program.cljc:1007-1026` **vs** `src/seon/cluster/source.clj:305-337` | two retirement paths producing two entity shapes; one needs the second validator at `src/seon/db.clj:2913`, the other does not | see that note D5/D7 | no | "retired" as a fact, one tombstone shape (lane in flight) |
| `src/seon/test/runner.clj:653,759` (`marker-reason`, `platform-reason`) | the platform tier reads Var/namespace **metadata**, while `:seon.test/long` was moved onto the indexed row at `:676,695` with a drift check | see that note D1 | no | `:seon.test/platform` as an installed attribute |

---

## 5. Hand-maintained mirrors

| file:line | what it is | what it works around | still needed? | dissolution |
|---|---|---|---|---|
| `src/seon/program.cljc:11-14` | `identity-attributes` — a literal vector of the six program identity attributes; `test/seon/program_test.clj:1013` asserts it "names exactly the declared families", i.e. the mirror has a drift check but is still a mirror | the derivation did not exist when it was written (2026-07-29) | no | derive; see §4 row 1 |
| `src/seon/program.cljc:899-904` | `declaration-required-attributes` — a literal map of required attributes per identity family | Malli already declares required keys on every one of these row schemas | no; added 2026-07-29 (`16afa2a10`) | read `:required` off the declared row schema |
| `src/seon/program.cljc:102-108` | `test-marker-attributes` — `[:seon.test/long :seon.test/long-ms]` | the markers were not property-tagged | the sibling function `base-context-injected-symbols` (`:17-36`) derives its list from declared schema properties — the pattern exists two functions away | tag the marker attributes and derive |
| `src/seon/test/selection.clj:28-35` | `widening-inputs` — `["resources" "config" "script" "bin/test" "bb.edn" "deps.edn" ".clj-kondo/config.edn"]`, the literal list of gate inputs outside the program graph | no fact says "this path is a gate input" | no | the graph already knows which paths it covers (`graph-roots`, `:24`); everything else in the checkout is a widening input **by construction** — invert the test and the list disappears |
| `bin/test:659-666` | test namespaces discovered by `find test -name '*_test.clj'` plus a filename munge (`_`→`-`, `/`→`.`) | the launcher runs before any database | **no** — this is a naming convention deciding behaviour, in the launcher, ahead of every derived selection (test-execution-model D2); dates to `f25e34594`, 2026-07-27 | `:seon.test/sym` rows on `:current-src` |
| `bin/seon-hook:1233-1237` | the hook decides publication progress by matching four literal English phrases in the operator's stdout (`"incremental scalar publication"`, `"incremental manifest reconciliation"`, `"complete publication:"`, `"development reload "`) against `report-source-progress!` (`src/seon/cluster.clj:86-89`) | there is no progress fact | **no** — a prose string is the contract between two processes | emit a keyed progress value; the hook reads the key |
| `src/seon/test/cache.clj:46` | `#{"data" "logs" "target" "tmp" "workers" ".cpcache" "test-run.txt" "changed-paths.txt"}` — a literal exclusion set for checkout copying, and `:68` a second literal list `["data" "logs" "tmp" "target"]` that overlaps it | no declaration of "disposable" | no | one declared set, used twice |
| `bin/test:64-78` | three hardcoded skill-link paths asserted equal (`seon-skills`, `.claude/skills`, `.agents/skills`) inside the **test gate** | a symlink drift incident | the check is fine; **the gate is the wrong owner** — it refuses every test run for an unrelated repository-layout fact | a repository-hygiene check, not a precondition of running tests |

---

## 6. Name-based classification

| file:line | what it is | production or tooling? | dissolution |
|---|---|---|---|
| `src/seon/schema/edn.clj:31-37` | `config-dial?` — true when the namespace **starts with `"seon.config."`** OR the form declares `:seon.config/dial true` | **production classification**, and the two arms disagree by construction: a dial outside that namespace prefix needs the property, one inside it never does. Added 2026-07-29 (`42887d234`) | keep the declared property, delete the prefix arm |
| `src/seon/bootstrap_drive.clj:259` | `(re-find #"\(defn\s+([^\s\[\](){}]+)" source)` — extracts a function name from source text | **production**, and a banned substitute (AGENTS §2.2): the reader answers this | `seon.sci.reader/read` and take the second element |
| `src/seon/bootstrap_drive.clj:186` | `(re-find #":seon\.fn(?:\.arity/input-refs|/spec)" …)` over text | **production**: a regex asking whether a contract mentions two attributes | the program graph answers it as a query |
| `src/seon/schema/edn.clj:122-126` | `(re-matches #"Duplicate key: (.+)" (ex-message error))` then `edn/read-string` on the captured group | **production**: parsing a dependency's exception message prose to recover the offending key | detect the duplicate before reading, over the parsed forms |
| `src/seon/test/runner.clj:3705` | `(str/starts-with? (::worker-id %) "pool-")` | **production** classification of a worker by its generated name | the worker record already distinguishes pooled from confirmation workers; carry it |
| `src/seon/cluster/store.clj:339-343` | stack-frame attribution by `(str/starts-with? frame "seon.")` and an owners prefix list | **diagnostic** — bounded to a refusal message | acceptable; say so in the docstring |
| `src/seon/render/web.clj:626`, `src/seon/render/walk.clj:106,637` | reverse-ref detection by `(str/starts-with? (name attribute) "_")` | **legitimate** — that is Datalog pull's own syntax, the dependency's vocabulary (AGENTS §3) | leave; ideally call the pull API's own parser |
| `src/seon/repl.clj:306` | `(str/starts-with? token "result/")` picks the `result` syntax class | **presentation only** (syntax highlighting) | low priority; the handle is minted by `seon.id/symbol-in "result"` (`sci/admit.clj:629`), so a shared constant would close it |
| `src/seon/fn.clj:61-62`, `src/seon/cluster.clj:2033-2034`, `src/seon/cluster/source.clj:96-99`, `src/seon/schema/edn.clj:152-177`, `src/seon/web/jvm.clj:160-164` | file-extension and media-type suffix tests | **reader-level tooling** — an extension IS the filesystem's own declaration | legitimate; no action |
| `src/seon/cluster/reply.clj:234,276`, `src/seon/sci/reader.cljc:506-507` | reply-grammar line classification (`#"^[\(\[\{…]"`, fence markers) | **reader-level**, this is the reply reader's grammar | legitimate — except `:276`, `(re-find prose-read-failure message)`, which classifies a reader **failure by its message text**: make the reader's refusal carry a kind |
| `src/seon/schema.clj:725` | `(re-matches #"[0-9a-f]{64}" …)` on an env var | validating a digest's shape | legitimate if the env var survives §1 |

---

## 7. Absence read as health

| file:line | what it is | verdict |
|---|---|---|
| `src/seon/test/selection.clj:192-202` | a missing or corrupt `green-basis.edn` returns `nil`; the bare gate then widens silently | **defect** — the widening is the safe direction, but nothing says the basis was missing, so a permanently absent basis reads as a normal gate forever. The suite-efficiency probe already found it absent |
| `src/seon/sci/eval.clj:1226,1252` | a row with no `:seon.fn/spec` renders as the empty contract `[]` in `doc`/`dir` | **defect** — "this function declares nothing" and "this row lost its contract" are the same bytes, in the surface an agent reads before calling |
| `src/seon/test.clj:923-933` (`feedback`) | a check that selected zero tests prints `tests run 0 / passed 0 / failed 0` into the post-edit hook feedback | **defect** — a green-shaped line for "no program was tested". The docstring of `check` (`:846-847`) already says empty checks mint no provenance; the feedback line does not say it |
| `src/seon/cluster.clj:1937-1940` | `valid-source-manifest?` answers `false` for a validator that threw | **defect** (also §2) — absence of a successful validation reads as "invalid", which then reads as "rebuild" |
| `src/seon/cluster.clj:2343` | `(not (pos? (or (:seon.instrument/instrumented result) 0)))` before refusing adoption | **correct** — absence refuses. Keep |
| `src/seon/test.clj:892-899` (`check-adoption`) | "Missing adoption facts are unknown, never green" | **exemplary** — typed unknown, not a pass. This is the shape every other row should copy |
| `src/seon/cluster/status.clj:74-80` | store bytes, adopted commit and current commit are each a typed `unknown` with a sentence | **exemplary** |
| `src/seon/instrument.clj:175-190` | the arglist lookup returns `:missing` / `:no-program-graph` / `:failed` as distinct statuses | **exemplary shape**, but `:184` and `:413` drop the cause (see §2) |

---

## 8. Shell in the loop

| file:line | what it is | dissolution |
|---|---|---|
| `bin/test:659-666` | namespace discovery by `find` + filename munge (also §5) | `:seon.test/sym` rows |
| `bin/test:679-701` | worker-count arithmetic in bash | the runner already knows the selection; size the pool there |
| `bin/test-check:15-52` | a Babashka script that **constructs a twenty-line Clojure program with `list`** and ships it over prepl — re-implementing the call protocol of `seon.test/check-adoption`, `seon.test/prepare-tests!`, `seon.test/resolve-test` and `seon.test/run` outside the JVM that owns them | one function on the cluster side taking a request map; the script sends `(seon.test/check-request {…})` |
| `bin/seon-hook:1204-1240` | the hook shells `bin/seon … init --dev … --changed` and then reads the child's console for progress and failure (also §2, §5) | a publication fact the hook queries |
| `src/seon/test/cache.clj:44-50` | the JVM shells `/bin/cp -cRP` (macOS) or `cp -a --reflink=auto` (else) per top-level entry, one child process each | `java.nio.file.Files/copy` with `COPY_ATTRIBUTES`, or one child for the whole tree — and the os-name branch is a second path |
| `src/seon/issue.clj:49-53` | the JVM shells `git log --format=%x00%at --name-only` and parses `\0`-separated output with `str/starts-with?` at `:60` | acceptable — git is the authority for git history; but the parse should be `-z` and structured |
| `dev_cache.clj` at the **repository root** | 611 lines of un-namespaced build tooling living beside `deps.edn`, copied into every `tmp/test-runs/run.*` root | it is a build script; `script/` is where build scripts live |

---

## 9. Duplicated dependency machinery

| file:line | what we built | what the dependency provides | verdict |
|---|---|---|---|
| `src/seon/cluster.clj:1700-1704` + `:1946-1975` | `source-analysis-cache`, a process-global `defonce` atom keyed by source snapshot, to avoid re-running clj-kondo | clj-kondo has its own cache — which `bin/seon-hook:185-196` deliberately **disables** for both lint paths (`--cache false`), for a documented reason | two cache disciplines for one analyzer. The hook's reason (a partial buffer poisoning the shared cache) is sound; the answer is to let the publication own the cache, not to add a second one in the JVM |
| `src/seon/cluster.clj:625-651` | `retrying-source-change`, our own retry loop | nothing; the underlying event is filesystem quiescence, which the hook's coalescing window already provides | dissolve, see §2 |
| `src/seon/test/cache.clj:85-97` + `dev_cache.clj:576` | two filesystem reclamation loops with rank and age heuristics | konserve has GC; these are `target/` directories, not store data, so konserve does not apply — but `referenced?` (`:52-60`) is a real liveness signal already computed | keep one reaper, key it on liveness only |
| `src/seon/search.clj:70-76` | `owners`, a `defonce` atom registry of Lucene index handles keyed by index-id and by connection | Lucene's `SearcherManager` (imported at `:26`) owns reader lifecycle; the per-connection map is ours | the cluster's environment should carry its index handle (AGENTS §2.1: never a process-global registry); Lucene itself is used correctly |
| `src/seon/cluster.clj:236` | `mcp-projection`, a `ThreadLocal` carrying projection intent across the prepl return | — | a side channel by definition: the value does not travel with the computation (AGENTS §2.1). The prepl return should carry its own projection |
| `src/seon/sci/reader.cljc` (934 lines) | the accepted-source reader | delegates to `clojure.tools.reader` and `sci` (`:5-7`) | **legitimate** — it adds tag refusal and `#=` rejection, which the dependency does not |
| `src/seon/search.clj` (Lucene), `src/seon/await.clj` (126 lines over `core.async`/`FutureTask`), `src/seon/blob.clj` | thin owners over real dependencies | — | **legitimate** |

---

## Ranked rip-out list

Ordered by blast radius (readers poisoned) then by cost. One-line deletions
first.

### Tier 0 — delete a literal, nothing else changes

1. **`bin/test:97-103` `SEON_TEST_FULL`.** No caller. Delete the `case`.
2. **`src/seon/test/runner.clj:2891` `SEON_TEST_CLOJURE`.** No caller. Delete the `or`.
3. **`bin/test:79-80`, `runner.clj:3775` result-cluster/root env reads.** Flags already exist.
4. **`src/seon/bootstrap_drive.clj:155` `30000`.** The effective config is read ten lines above at `:145`.
5. **`src/seon/eval/drive.clj:333` `120000`.** The request's `timeout-ms` is used on the next line.
6. **`bin/seon-hook:910-912` / `:1113-1117` / `:1224` / `:1251` duplicated defaults.** Three copies of four numbers; keep the ones in `.claude/seon-hook.edn`.
7. **`src/seon/test/cache.clj:25` second `SEON_TEST_SILENCE_SECONDS` default.** One accessor.
8. **`.claude/worktrees/gym-metric-validation` (218 MB, deleted CLJS pod).** `git worktree remove` after confirming no holder — AGENTS §6 requires the holder check before deleting any run root.

### Tier 1 — small, high blast radius

9. **`(or … "default")` × 7** (`config.clj:404,549,553`; `test.clj:631,663,854`; `schedule.clj:609`). A forgotten cluster name silently becomes the owner's development cluster, and `config.clj:404` **writes** it as a durable fact. Every cluster-scoped reader downstream is poisoned. Make the argument required; refuse.
10. **`src/seon/schedule.clj:605-609` "whichever entity has `:seon.cluster/name`".** Same seam, worse: a pre-read the authority will re-decide. The proc's state already carries the agent.
11. **`src/seon/sci/eval.clj:1226,1252` empty-contract-for-missing-contract.** Poisons `doc` and `dir` — the surface every agent reads before calling a function.
12. **`src/seon/schema/edn.clj:31-37` `config-dial?` namespace prefix.** Delete the prefix arm; keep the declared property. Every config dial's classification depends on it.
13. **`src/seon/test/selection.clj:192-202` green basis absence + `(catch Throwable _ nil)`.** Say "no basis; widened".
14. **`src/seon/cluster.clj:1937-1940` `(catch Throwable _ false)`.** A throwing validator triggers a full rebuild, silently.
15. **`src/seon/cluster/export.clj:81` `clone-deadline-ms`.** One undeclared ten-minute bound on the store clone path.

### Tier 2 — one mechanism, real work

16. **`src/seon/program.cljc:11-14` `identity-attributes`** — collapse three derivations into one (§4 row 1). Touches `fn.clj`, `turn.clj`, `cluster/source.clj`, `test/runner.clj`, `sci/eval.clj`.
17. **`bin/test:659-666` `find`-based namespace discovery** — the launcher's naming convention overrides every derived selection (test-execution-model D2).
18. **`bin/seon-hook:1233-1237` English-phrase progress contract** — replace with a keyed progress value emitted by `report-source-progress!`.
19. **Four classpath derivations → one** (`dev_cache.clj`, `test.clj:108`, `analyzer.clj:172`, `runner.clj:2892`). Closes an open issue on the in-process test loader.
20. **`src/seon/program.cljc:899-904` `declaration-required-attributes`** — read `:required` off the declared row schema.
21. **`src/seon/test/selection.clj:28-35` `widening-inputs`** — invert the predicate; the list disappears.
22. **`src/seon/cluster.clj:625-651` `retrying-source-change`** — the observable is the hook's coalescing window, not a retry.
23. **`src/seon/bootstrap_drive.clj:186,259` two production regexes over source text** — banned substitutes; the reader and the program graph answer both.
24. **`bin/test-check:15-52`** — stop composing a Clojure program in bash; call one cluster-side function.
25. **146 `requiring-resolve` sites**, starting with `turn.clj:2025` (self-namespace). Per-call resolution is the fetch-at-call-time defect; `declare` closes the forward references for free.

### Tier 3 — structural, owned by PRDs already in flight

26. `full-source-refresh!` ×4 escapes and the duplicated incremental change classification (`cluster.clj:1977-2110`).
27. Three gate entry points → one `select` (test-system PRD stage 1).
28. Six evaluation minting calls + four terminal writers → one write path (evaluation-write-path note D1/D2).
29. `:seon.test/platform` as an installed attribute (test-execution-model D1).
30. `search/owners` and `mcp-projection` — process-global registry and `ThreadLocal`; the environment should carry both.

---

## Verification boundary

Static only: source, `git log -S`, `git blame`, `git worktree list`, `du`.
No test JVM, no prepl evaluation, no cluster was touched. Claims about
*behaviour* under load, about which branches actually fire in production, and
about the measured cost of the 146 `requiring-resolve` sites are **not**
established here and are marked as such where they appear. Rows citing
sibling notes carry that note's evidence, not new evidence.
