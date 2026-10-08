---
title: Printer rules, Opus lane
type: research
status: complete
date: 2026-10-08
lane: printer-rules-opus
---

> **Slices moved 2026-10-08.** Part 3's slices S1-S20 are rows `PR-` of [the agent-loop ledger](../../../../seon/schedule/agent-loop-ledger.md) §3.3 (their old ids collided with the agent-loop S1-S8). The census and the rules R1-R14 stay as evidence.

# Printer rules: census, general rules, enforcement and slices (lane printer-rules-opus)

The value printer is `seon.sci.admit` (projection to print nodes) → `seon.render.value` (selection under one budget,
cuts, elision forms) → `seon.print` (fipp layout to text and hiccup). This note counts every place outside that
family that clips, bounds or turns a value into text, states general rules for the one printer, the enforcement
that keeps clipping out of every other namespace, and the slices in order.

Owner, 2026-10-08: "No clipping outside of the one value printer. I know agents are always doing this to cheat on
budgets. DO NOT LET THEM DO THIS." Two root rulings during this audit: Clojure terms and clojure.core tools agents are
trained on (rename "dig form" to **elision form**, plain clojure.core on the live result symbol, lazy first, no Seon
helper, no database re-pull while the live value exists), and every collection cut must name an elision form that
continues past what was shown.

Scope and method: `src/`, `script/`, `bin/` (the `bin/seon-hook` edit hook, `bin/test-check`, `bin/lane`), and the MCP
bridge (`bin/mcp-server` → `script/seon/dev/mcp.clj` → `src/seon/mcp.clj`). Tests excluded except where they assert
the old behaviour. Grep at HEAD `58dbc934a` plus the working tree; each site in the tables was read in context.
`clj-kondo --lint src script --cache false` (3.1 s) and five read-only MCP probes on scratch agent
`printer-rules-probe` (`runtime_status`: default `:faulted` with five open faults; none are this audit's).

## Broken things first

1. **A cut set or map gives an elision form that does not continue.** Live, default: `(set (range 300))` shows 32
   members, then `…268 result/e71ebe33e3ca0`; `(zipmap (range 200) (range 200))` shows `…168 result/e8d6a2352dfbd`;
   `{:m <100 keys>}` shows `…68 (get-in result/eb36a4e9a6cbf [:m])`. Each form evaluates to the WHOLE collection, so
   the agent reprints the same 32 and cannot continue. Cause: `seon.render.value/node-continuation`
   (`src/seon/render/value.clj:855-874`) sets the next offset to 0 for every `:indexed?` node, on the stated
   assumption "a map or set, whose shown order is not its iteration order". Refuted on the same values: shown order
   is seq order — `(nth (seq result/e71ebe33e3ca0) 31)` = `154` (the last shown member),
   `(key (nth (seq result/e8d6a2352dfbd) 31))` = `85`, `(key (nth (seq (:m result/eb36a4e9a6cbf)) 31))` = `:k49`, and
   `(count (drop 32 result/e71ebe33e3ca0))` = `268`, the printed count. The one case where the assumption holds is a
   keyword map of at most 64 keys reordered by rule 4 (identity first); that map needs its own form (rule R7 below).
2. **The `:out` elision form re-pulls the database.** `(print (apply str (repeat 1000 "abcdefghij")))` shows
   `…+5331 (subs (:seon.cluster.eval/output (seon.db/pull '[:seon.cluster.eval/output] [:seon.cluster.eval/id
   "29ea417f846d"])) 4669)`. The live output string exists in the runtime; the form is a database read with a Seon
   attribute name. Producer: `seon.repl/shown-output` (`src/seon/repl.clj:175-190`) hands the printer a
   `seon.db/pull` requery id. The P5 test (`test/seon/repl_differential_p5_test.clj:23,39`) asserts this exact text.
3. **Elision forms call a Seon helper.** A cut under a non-keyword map key or a set member prints
   `(drop 32 (seon.print/value-at result/e092ae498f621 '[[:seon.render.data/entry 0 :seon.render.data/val]]))`.
   Plain clojure.core says the same: `(drop 32 (val (nth (seq result/e092ae498f621) 0)))`. Producer:
   `seon.print/continuation` and `requery-form` (`src/seon/print.cljc:302-338`); roots built with `value-at` in
   `seon.render/declared-projection` (`src/seon/render.clj:1310-1318`) and `seon.error/held-reference`
   (`src/seon/error.clj:2264-2271`).
4. **The printer law does not refuse anything.** `.clj-kondo/config.edn:57-73` lists the printers under
   `:discouraged-var`, which is a warning; `bin/lane commit` refuses only on `errors: 0` (`bin/lane:256`) and the
   edit hook blocks only `:error` findings (`bin/seon-hook:929,967,1086`). The lint reports **183** printer findings
   today among 648 warnings, and every one of them landed. `bin/seon-hook` (2,057 lines, 21 `pr-str`s) is not linted
   at all: `bin/lane` lints `src script test` (`bin/lane:125`), and the file has no `.clj` extension. There is no
   `seon.render.lint` namespace; two docstrings still cite it (`src/seon/render/value.clj:276`,
   `src/seon/render.clj:1117`).
5. **Test reports mutate the agent's exception.** `seon.sci.eval/agent-do-report` (`src/seon/sci/eval.clj:290-297`,
   commit 5295011dd) calls `.setStackTrace` with an empty array on every link of an erroring test's Throwable, so the
   live exception the agent may hold loses its frames for good. Bulk was a printing problem; the fix belongs in the
   printer (rule R3), not in the value.
6. **Identity and comparison by printed text survive.** `seon.fn` hashes `(pr-str (mapv … artifacts))` for the
   manifest digest (`src/seon/fn.clj:2674`); `seon.schema.datahike` compares `(id/printed left)` with
   `(id/printed right)` (`src/seon/schema/datahike.clj:525`); `seon.env` sorts keys by `str`
   (`src/seon/env.clj:88`); `seon.ai` sorts attempt keys by `str` (`src/seon/ai.clj:82`); the operator's program-class
   cache key is a SHA-256 of `pr-str` (`script/seon/operator.clj:586`).

Recorded as tasks (this commit): `seon.render.value/a-cut-set-or-map-names-the-whole-collection`,
`seon.repl/the-out-elision-form-re-pulls-the-database`, `seon.print/elision-forms-call-a-seon-helper`,
`seon.print/the-printer-law-lint-refuses-nothing`, `seon.sci.eval/a-test-report-erases-the-agents-stack-frames`.
Existing tasks this census refines: `seon.print.value/printed-copies-stored-or-sent-instead-of-values` (35 sites,
stale `seon.print.value` references) and `seon.print.value/ad-hoc-text-clips-outside-the-printer` (its `seon.mcp`
site is gone; the `my.program` one remains).

## Part 1: the census

Classes: **(a)** a display clip or display print that must go to the value printer; **(b)** a printed copy used for
identity, comparison, hashing, size or storage, which must use the data or a digest; **(c)** a legitimate non-display
use (query or storage bound, deadline, transport or code encoding), justified per row.

### Counts

| Class | Sites | Where most live |
| --- | ---: | --- |
| (a) display | **121** | error messages with printed values (61), shown text built outside the printer (12), render or report count cuts (16), HTML `[:code (pr-str …)]` (9), hook and dev-script clips and logs (14), error-message `str` interpolation not separately counted (see note) |
| (b) printed copy | **49** | stored EDN text members (25), printed values in error data (10), identity/compare/sort/size by print (14) |
| (c) legitimate | **≈60** | URL parameters (11), code generation (13), storage encoding (7), query/storage bounds and suggestion lists (≈29) |

Not counted as sites: about 120 `subs` calls that parse text (reader spans, SSE lines, edit spans, frame class
names, Markdown and citation tooling, env-file parsing); they cut nothing shown. Note on messages: 110 sites build
`:seon.error/message (str …)` or `(ex-info (str …))`; the 61 counted are the ones that print a value with `pr-str`.
The rest interpolate ids and names with `str`; they fall under the same rule (R12) and the same sweep, listed by
`grep -rnE ':seon.error/message \(str|ex-info \(str' src` (db 15, batch 8, schema 7, my.program 7, …).

### Top offenders

1. `src/seon/error.clj` — 21 sites: `fit-fact-payload`/`bounded-text` (`:356-490`) store canonical-EDN copies of the
   message, arguments, expected and actual under a halving byte loop (b); seven contract messages print values
   (a); `evidence-text`, `repl/source-text` prose (a); `names-shown`/`call-lines` count cuts (a).
2. `src/seon/turn.clj` — 16: run text and refusal messages print agent ids, run ids and instants (a); `:seon.eval/shown
   (pr-str {:seon.repl/changes …})` builds shown text with no printer (`:2214`, a); settings, usage and proposals
   stored as `-edn` text (`:3856,4242,4244`, b).
3. `src/seon/render/web.clj` — 16: eight `[:code (pr-str …)]` displays (a), graph ids by print (b), six URL
   parameters (c).
4. `bin/seon-hook` — 21 `pr-str`s, unlinted: feedback cut by `subs` with a hand-built "… feedback elided" line
   (`:1215-1233`), advisory findings capped with "… N advisory finding(s) omitted" (`:1086-1099`), the whole patch
   printed into a reason (`:799`), `printStackTrace` to stdout (`:2036-2038`).
5. `src/seon/program.cljc` (9), `src/seon/fs.clj` (9), `src/seon/cluster/message.clj` (8), `src/seon/db.clj` (8).

### (a) Display clips and prints that must go to the value printer

Error and exception messages that print a value with `pr-str` (61; rule R12: fixed text, values as data keys).
Consumer: the error map's `:seon.error/message`, shown to agents and stored with recorded errors.

| File | Lines |
| --- | --- |
| `src/seon/fault.clj` | 275 |
| `src/seon/error.clj` | 890, 948, 954, 959, 966, 983, 1021 (`scalar-text` prints a scalar into a message) |
| `src/seon/fn.clj` | 60, 61, 1263, 3983 |
| `src/seon/db.clj` | 561, 563, 566, 1218, 2019, 2060, 5258 |
| `src/seon/call_preparation.clj` | 230 |
| `src/seon/agent.clj` | 27, 48 |
| `src/seon/env.clj` | 58, 60 |
| `src/seon/turn.clj` | 1690, 3821, 3823, 3825, 5483, 5619, 5621, 5671, 5672, 5756, 5757 |
| `src/seon/schema.clj` | 358, 1963, 4054 |
| `src/seon/effect.clj` | 87, 90 |
| `src/seon/instrument.clj` | 313, 355 |
| `src/seon/cluster/message.clj` | 161, 220, 313, 317, 708, 867 |
| `src/seon/cluster.clj` | 3168 |
| `src/seon/cluster/registry.clj` | 617 |
| `src/seon/fs.clj` | 115, 121, 125, 127, 132, 134, 151, 152 |
| `src/seon/schema/admission.clj` | 247 |
| `src/seon/sci/eval.clj` | 3452 (a non-string `:seon.error/message` printed into the evaluation's error text) |
| `src/seon/test.clj` | 1128 |

Shown text built outside the printer (12). Consumer: the agent's reply or context.

| Site | What it does |
| --- | --- |
| `src/seon/turn.clj:2214` | `:seon.eval/shown (pr-str {:seon.repl/changes changed})`: a reply's shown text with no printer, no budget |
| `src/seon/render.clj:1478` | `render-default-ai-source` returns `(pr-str form)`; the form face must return the form as data |
| `src/seon/render/transcript.clj:418` | message entry text: `(pr-str (list 'my.message/read …))` spliced into history |
| `src/seon/repl.clj:101-107` | `source-text`: `clojure.pprint/write` with `code-dispatch`, a second code layout; consumer `error.clj:2451` |
| `src/seon/error.clj:2503-2507` | `evidence-text`: `attribute=(pr-str value)` lines joined into prose |
| `src/seon/render/transcript.clj:461-463` | reasoning summary `(str first-line "…")` |
| `src/seon/test/runner.clj:123-128` | `message-text` decides whole-or-printer by comparing `(count message)` with the token budget |
| `src/seon/edit.clj:56-79` | `context-window`: a byte-bounded window of source with `:my.edit/source-window-complete? false` and no elision form |
| `src/seon/shell/jvm.clj:303-322` | `output-descriptor`: stdout over `inline-output-bytes` becomes a `preview-bytes` clip plus a blob digest |
| `src/seon/sci/eval.clj:290-297` | `agent-do-report` erases the Throwable's frames instead of printing its chain |
| `src/seon/cluster/agent.clj:764`, `src/seon/bootstrap.clj:310` | `:seon.turn/reply-size (count (pr-str …))`: a reply size measured by a second print (also (b)) |

Render and report functions that cut a collection by count and hand-build its count (16). Consumer: render data
or reports the printer then prints; rule R10 forbids the cut, not the selection.

| Site | Cut |
| --- | --- |
| `src/seon/render/var.clj:123` | `(take shown-tests v)` plus a hand `counts` map |
| `src/seon/render/schema.clj:65, 68` | `(take names-shown …)` plus `-count` keys |
| `src/seon/render/schema.clj:144` | `" and N more"` in hiccup |
| `src/seon/health.clj:665` | `(take shown found)` for `:seon.health/top` |
| `src/seon/test/accretion.clj:111-117` | `(take 3 members)` plus `"N more in the complete gate-report blob."` |
| `src/my/program.clj:889-891` | `(take limit rows)` plus `:more (my.program/search {… :limit (* 3 limit)})`, a Seon-helper elision |
| `src/my/program.clj:906` | `sizes`: `(take 20 rows)` |
| `src/my/program.clj` `match-row` | 100-character snippet with `...` (existing task `ad-hoc-text-clips-outside-the-printer`) |
| `src/seon/error.clj:1977, 1980` | `call-lines`: three functions, three lines each |
| `src/seon/db.clj:2003` | twelve registered-attribute candidates, unranked |
| `src/seon/db.clj:5809` | three `seon.db/pull` operations offered for a transaction's entities |
| `src/seon/test.clj:1712` | `(vec (take 5 (sort absent)))` as `:seon.error/offending` |
| `src/seon/ai.clj:80-92` | restates `:seon.render.data/total` so "the elision machinery" does not report omitted children |
| `src/seon/render/transcript.clj:870` | builds a `:seon.print/elided` node by hand instead of `seon.print/elision` |

HTML display through `pr-str` (9; must be the printer's HTML face, `seon.render.value/html`):
`src/seon/render/web.clj:752, 794, 802, 982, 3290, 3292, 3294, 3296`; `src/seon/render/transcript.clj:786`.

Edit hook, dev scripts and logs (14):

| Site | What it does |
| --- | --- |
| `bin/seon-hook:1215-1233` | `clip-feedback`: `subs` to an estimated character count plus "… feedback elided (approximately N tokens …)" |
| `bin/seon-hook:1086-1099` | first 20 advisory findings plus "… N advisory finding(s) omitted." |
| `bin/seon-hook:1408-1435, 1463-1468` | review prompts cut by `clip-with-marker` (review is off, `.claude/seon-hook.edn`) |
| `bin/seon-hook:799, 626, 759, 765, 833, 903` | the whole patch, findings vector or line printed into a reason string |
| `bin/seon-hook:2036-2038` | `with-out-str` of `printStackTrace` |
| `script/seon/dev/docstring.clj:355-357`, `script/seon/dev/markdown.clj:1210-1212` | lint output cut with `"\n... (truncated)"` |
| `bin/test-check:65, 93, 101` | `(pr-str result)`, `(pr-str (ex-data cause))` to the terminal |
| `script/seon/operator.clj:1834` | `" MISSING: " (pr-str missing)` |
| `src/seon/db.clj:1432` | `println` WARN line to stderr |
| `src/seon/schema.clj:1076, 1084` | `println` fallback lines (fixed text plus a caller name; a counter in an atom) |
| `src/seon/cluster/boot.clj:407` | `println` of a refusal message to stderr |
| `src/seon/fs.clj:212` | `log/warn` interpolating root, operation and caller |

### (b) Printed copies used for identity, comparison, size or storage (49)

Stored EDN text members (25). Consumer: Datahike string attributes and their readers. Replace with the data in an
EDN-typed attribute (encoded once by `seon.schema.datahike` through `seon.id/printed`) or, for source text, the reader's
own source span.

| Site | Stored copy |
| --- | --- |
| `src/seon/program.cljc:766, 847, 872, 881, 966, 975, 992, 1068, 1117` | component ids, default forms, `key-edn`, binding forms, `label-edn`, arity text |
| `src/seon/sci/reader.cljc:289, 302, 340` | `:seon.fn/arglists`, `:seon.fn/spec`, `:seon.schema/form` as `pr-str` text |
| `src/seon/sci/arm.clj:56`, `src/seon/turn/batch.clj:275, 445` | `:seon.fn/spec`, `:seon.schema/form` text |
| `src/seon/fn.clj:541, 766` | seed contract `:seon.fn/spec` text |
| `src/seon/schema.clj:3694` | `:seon.schema/form` text |
| `src/seon/turn.clj:3856, 4242, 4244` | deletion proposals, provider settings and usage as `-edn` strings (`3856` binds `*print-length*` nil to dodge an ambient bound: the bug class) |
| `src/seon/error.clj:337, 356-373, 466, 487` | `fit-fact-payload`: message, data, expected, args, actual stored as canonical EDN cut to the missing marker by a halving loop; size measured by `pr-str` |
| `src/seon/effect.clj:541, 866` | effect result stored as admitted canonical EDN |
| `src/seon/background.clj:55` | `:my.background/result-observation (pr-str result-ref)` |

Printed values inside error data (10): `src/seon/render/hiccup.clj:280, 281, 303, 306, 322, 327` (the unparseable tag
head), `src/seon/fn/signature.cljc:70, 81, 93` (the form), `src/seon/test/runner.clj:2120` (`Throwable->map`: every
root frame copied into `:seon.error/offending`; errors hold references, the chain is the data).

Identity, comparison, sort and size by printing (14):

| Site | Use |
| --- | --- |
| `src/seon/fn.clj:2674` | manifest digest = SHA-256 of `pr-str` (use `seon.id/digest`) |
| `src/seon/schema/datahike.clj:525` | `compare` of `id/printed` texts (use `seon.print/compare-values` or the data) |
| `src/seon/env.clj:88, 90` | keys sorted by `str`; a type's name via `pr-str` |
| `src/seon/ai.clj:82` | `sorted-map-by` comparing `str` of keys |
| `src/seon/config.clj:603`, `src/seon/program.cljc:766` | tempid and component id strings by print (use `seon.id/id`) |
| `src/seon/render/web.clj:853, 870` | graph node and edge ids by print |
| `src/seon/cluster/message.clj:197` | `:seon.message/refused-chain-limit (pr-str max-chain)`: a number stored as text |
| `src/seon/cluster/agent.clj:764`, `src/seon/bootstrap.clj:310` | reply size by a second print (also (a)) |
| `script/seon/operator.clj:586` | program-class cache key = SHA-256 of `pr-str` |
| `script/seon/dev/dependency_digest.clj:145` | dependency digest of printed deps (Babashka and JVM must agree; uncertain whether `seon.id` loads in Babashka) |
| `src/seon/error.clj:2716` | generator builds `:seon.error.key/projection (pr-str scalar)` |

### (c) Legitimate non-display uses (≈60), with the justification

| Sites | Justification |
| --- | --- |
| URL parameters: `src/seon/render/web.clj:273, 274, 282, 288, 292, 425`; `src/seon/error.clj:1844, 2315, 2332`; `src/seon/cluster/message.clj:268`; `src/seon/render/transcript.clj:1004` | transport: the route reads the EDN parameter back (`render.route`); should use `seon.id/printed` so an ambient `*print-length*` cannot cut a URL |
| Code generation: `src/seon/cluster/agent.clj:181`; `src/seon/sci/eval.clj:679-686`; `src/seon/fn/analyzer.clj:85, 838`; `src/seon/schema/datahike.clj:38`; `src/seon/bootstrap.clj:282, 291`; `script/seon/operator.clj:257, 391, 1276`; `bin/seon-hook:502, 509, 590`; `script/seon/dev/mcp.clj:77`; `bin/test-check:52` | source text is the value (a file, a prepl form, a JSON-wrapped EDN string on the wire); should use `seon.id/printed` or `seon.print/form-text`, never ambient `pr-str` |
| Storage encoding: `src/seon/blob.clj:47`; `src/seon/fs.clj:25`; `src/seon/schema.clj:756, 1342, 1717`; `script/seon/operator.clj:406-448, 656, 1854` | EDN is the storage or file format, or a round-trip check that a value is storable; same `seon.id/printed` caveat |
| Query, read and storage bounds that name their cut: `src/seon/render/walk.clj:217-312, 475, 596-616, 718, 1147-1153`; `src/seon/render/transcript.clj:154, 847-875`; `src/seon/cluster/prompt.clj:236-295` (whole prompt units, `seon.print/elision`); `src/seon/schema/datahike.clj:847`; `src/seon/web/search.clj:27`; `src/seon/profile.clj:428, 493, 582-640`; `src/seon/sci/admit.clj:110-130` and `src/seon/sci/eval.clj:3966-3990` (`max-bytes` storage refusals); `src/seon/fs/jvm.clj` read and write limits; `src/seon/web/jvm.clj:114-149`; `src/seon/shell/jvm.clj:142-171` | bound the work or the storage, not the shown text; each names its bound and count. The printer still prints what they return |
| Algorithmic: `src/seon/call_preparation.clj:966, 998` (`take 2` detects ambiguity); `src/seon/schema.clj:3967, 3974`; `src/seon/db.clj:4071`; `src/seon/ai/tokens.cljc:125` | cost bounds inside a computation |
| Suggestion lists: `src/seon/error.clj:1955`; `src/seon/schema/admission.clj:59`; `src/my/program.clj:828` | the k nearest names are the answer to "did you mean", ranked by distance; the k must be a declared argument, not a display cap |
| Number formatting: `format` in `src/seon/ai.clj:177, 214, 225`, `src/seon/agent.clj:218`, `src/seon/render/transcript.clj:995-996`, `src/seon/cluster.clj:411`, `src/seon/fn.clj:116` | numbers, not values |
| `src/seon/problems.clj:450`, `src/seon/id.clj:261` | digest prefixes are identities, not clipped values |
| `src/seon/sci/kernel.clj:733-770` | `clojure.main/ex-triage` and `ex-str`: Clojure's own one-line error text from class, message and location (no ex-data) |
| `src/seon/error/refusal.clj:160-185` | stack frames as data, the shared suffix removed as `printStackTrace` does: the cause chain the printer prints |

### What the existing lint catches, and what it misses

`:discouraged-var` (`.clj-kondo/config.edn:57-73`) flags `pr-str prn-str print-str println-str pr prn print println
printf clojure.pprint/pprint clojure.pprint/write`, off in `seon.render.value`, `seon.print` and `seon.id`. The
`is`-message hook (`.clj-kondo/hooks/is_message.clj`) is an error. Misses:

1. **Level.** A warning: 183 findings stand and nothing refuses a new one (broken thing 4).
2. **Files.** `bin/seon-hook`, `bin/test-check` and other extensionless Babashka scripts are never linted.
3. **Patterns not listed:** `with-out-str`; `clojure.main/ex-str` and `Throwable->map` outside the error owners;
   `.printStackTrace` and `.setStackTrace` (Java interop, which `:discouraged-var` cannot see); `binding` of
   `*print-length*`/`*print-level*`/`*print-meta*` outside the printer; `id/printed` or `admit/canonical-edn` used to
   compare, sort or hash; `(sort-by str …)` and `compare` of `str`; hand-built elision text (`"…"`, `" more"`,
   `"omitted"`, `"elided"`, `"(truncated)"`) in string literals; `(str …)` with values as an error message; `take`,
   `subs` and `count` cuts for display (statically indistinguishable from parsing).
4. **Escape hatch.** `#_{:clj-kondo/ignore [:discouraged-var]}` silences it with no reason (three uses in
   `script/seon/dev/mcp.clj`).
5. **Second printer allowed in the printer's own allowlist:** `seon.render.value`'s core path has its own walker
   (`datum`/`bounded-text`, `src/seon/render/value.clj:85-127`) beside `select-nodes`; inside the family, but two walks.

## Part 2: general rules for the one value printer

These replace the "Rules" docstring of `seon.render.value` and the Presentation lines of CLAUDE.md that name dig forms.
No rule mentions a schema.

- **R1 One budget.** Each shown unit has one token budget from its render profile; the printer spreads it breadth
  first over the value's shape (every member of a level before any member of the next; sequence members one level
  deeper per doubling of position). Nothing outside the printer bounds shown text. Storage bounds (`max-bytes`) refuse
  or store a marker; they never produce shown text.
- **R2 Strings, source and `:out`.** A string that fits what remains at its level prints whole before sibling
  collections expand; a longer one shows its head and `…+N (subs <path> n)`. Code (function source,
  `:seon.render/source`) is a string of lines: whole when it fits, else cut at a line end. Printed output is the
  evaluation's `:out` string and follows the same rule, with its elision form on the live output.
- **R3 Errors and Throwables.** A Throwable prints as its cause chain as data (`seon.error.refusal/chain`: class,
  message, ex-data, first-party frames); an error prints as its one flat map. The printer never calls
  `printStackTrace`, `Throwable->map` or `toString`, and never mutates the value; omitted host frames are counted.
- **R4 Nested values and renders.** The root is offered to its render once; render output is data the printer prints
  under the same budget; nothing beneath re-dispatches. A nested render is an explicit call by the outer render.
- **R5 Laziness, infinity, cycles.** Realize only what prints; an unknown tail is `…? (drop n <path>)`; a realization
  that throws shows the realized prefix and the failure as data. Host references (atoms, contexts, channels) print
  as `#object[Class 0x…]`; the walk is an explicit stack bounded by depth.
- **R6 Elision forms.** Every cut shows its exact count (`…N`, `…+N`, `…?`) and an elision form: plain clojure.core on
  the live result symbol that returns exactly what was omitted, lazy first. No Seon helper, no database read while
  the live value exists; a `seon.db/pull` form only when there is no runtime value (a database entity in render data,
  or a handle that answers its value is gone after a restart). The printer never runs an elision form.
- **R7 Shown order is seq order whenever a collection is cut**, so `(drop n x)` continues it. Reordering (identity
  attributes first, short before long) applies only to a map that prints whole; a reordered map that is cut gets
  `(apply dissoc x [shown keys…])`.
- **R8 One selection, two faces.** AI text and HTML come from one node tree and the same cuts; HTML expanders are the
  same elision forms. Only the final emission differs.
- **R9 With or without a database.** With custody, an attribute declared `:seon.db/ref` prints its referent's identity;
  without a database (off custody, boot edges, a failed recorder), `core-text` prints the same tree under a fixed
  profile and references print as lookup refs. A number is never guessed to be a reference.
- **R10 Render functions** may select keys, filter, rank, derive and offer operations as data (AI face), return hiccup
  (HTML face) or a requery form (form face). They may not cut a collection by count, clip a string, write "N more" or
  "…", call `pr-str`/`str` on a value, print, or call the printer except for an explicit nested render. A render
  returns its ranked collection whole (lazy when long); the printer cuts it with an elision form.
- **R11 Storage is not presentation.** Stored data is the data (EDN-typed attribute or blob). A stored copy of a shown
  value is the printer's shown text plus ids. Identity is `seon.id/id`/`digest` over structure; comparison and sorting
  use the data. Text that must be EDN (a URL parameter, generated source, a file) uses `seon.id/printed`, which fixes the
  print bindings.
- **R12 Messages are fixed text.** `:seon.error/message` and `ex-info` messages are literal strings; every value
  goes in a data key the printer shows.
- **R13 Clojure's own bounds.** A session's `*print-length*` and `*print-level*` (its `set!`s) are hard bounds the
  printer honours as Clojure does; nothing else sets them.
- **R14 One walker.** The core path and the profiled path share one node walk; `core-text` is that walk under a fixed
  profile.

Elision forms by shape (R6, R7). `r` is the live result symbol (`result/e…`).

| Shape cut | Shown | Elision form | Today |
| --- | --- | --- | --- |
| vector | first 32 | `(drop 32 r)` | correct |
| list, seq, lazy seq | first 32 | `(drop 32 r)`; unknown tail `…? (drop 32 r)` | correct |
| set | first 32 in seq order | `(drop 32 r)` | **bare `r`** (broken 1) |
| map, cut by entries | first 32 entries in seq order | `(drop 32 r)` (entries) | **bare `r`** or `(get-in r [:m])` (broken 1) |
| map ≤ 64 keyword keys, reordered, cut | identity first | `(apply dissoc r [:k1 :k2 …])` | bare `r` |
| string | head | `(subs r 4669)` | correct |
| `:out` | head | `(subs (:out r) 4669)` on the live output (see slice S3) | **`seon.db/pull`** (broken 2) |
| nested by key or vector index | — | `(drop 32 (get-in r [:rows 7 :tags]))` | correct |
| nested under a list/seq index | — | `(drop 32 (nth (seq r) 3))` | broken (`get-in` on a list: task `seon.print/a-dig-form-through-a-list-index-reads-nil`) |
| nested under a set member or non-keyword map key | — | `(drop 32 (nth (seq r) 0))`, `(drop 32 (val (nth (seq r) 0)))` | **`seon.print/value-at`** (broken 3) |
| one member | — | `(nth r 4321)`, `(get-in r [:rows 7 :name])` | offered as operations |
| shape questions | — | `(count r)`, `(keys r)` | offered as operations |

The printer must not: branch on a schema; clip or reorder a value it was handed (it shows, it never trims the
original); run an elision form or a render's offered operation; re-dispatch below the root; realize beyond what
prints; read the database to continue a live value; print a host object's contents; mutate a value (no
`setStackTrace`); store its output as an identity; print anything without a count and an elision form for what it cut.

## Enforcement

One clj-kondo linter, `:seon/printer-law`, at **`:error`**, implemented as an `:analyze-call` hook
(`.clj-kondo/hooks/printer_law.clj`) and enabled everywhere except the printer family (`seon.sci.admit`,
`seon.render.value`, `seon.print`) and the storage encoder (`seon.id`) through `:config-in-ns`. Because it is an
error, the edit hook's pre-edit check refuses the edit (`bin/seon-hook:925-942`) and `bin/lane commit` refuses the
candidate (`bin/lane:256`). Detection rules:

1. A call to `pr-str prn-str print-str println-str with-out-str clojure.pprint/pprint clojure.pprint/write
   clojure.main/ex-str Throwable->map`. (Every legitimate use has a replacement: display → the value printer, identity →
   `seon.id/id`, storage, URL and code text → `seon.id/printed`.)
2. A `binding` form whose vector binds `*print-length* *print-level* *print-meta* *print-namespace-maps*`.
3. A `str` or `format` call with a string-literal argument containing `…`, `" more"`, `omitted`, `elided` or
   `truncated` (hand-built elision text).
4. A map literal argument, or `ex-info` call, whose `:seon.error/message` value or first argument is not a string
   literal (R12).
5. `compare`, `sort-by` or `sorted-map-by` whose key function or comparator is `str`, `pr-str` or `seon.id/printed`
   (R11).
6. Interop `.printStackTrace` and `.setStackTrace`: clj-kondo hooks do not see interop calls, so this one is a
   `bin/lane` grep over the candidate (`\.(print|set)StackTrace`) outside `seon.error.refusal`; same refusal.

Allowed false positives, each declared once in `.clj-kondo/config.edn` with a reason string, never inline:
`script/seon/dev/mcp.clj` (EDN string on the prepl wire, until it uses `seon.id/printed`), the dev-only `script/seon/dev`
linters' own stdout. Inline `#_{:clj-kondo/ignore [:seon/printer-law]}` is refused by the same `bin/lane` grep.

Coverage: `bin/lane lint_candidate` and the edit hook also lint `bin/seon-hook` and `bin/test-check` with
`clj-kondo --lang clj`. Existing sites: a ratchet, not a waiver — `.clj-kondo/printer-law-baseline.edn` holds the count
per file at enforcement time; `bin/lane commit` refuses a candidate whose count for any file rises, and each slice
lowers the counts it converts. Display `take`/`subs` cuts in renders cannot be told from parsing statically: rule 3
catches their hand-built text, R10 is in the review rubric, and the slices delete the current ones.

## Part 3: slices (about 15 minutes each)

Each slice names its files and one proof test that drives the real MCP entrance (`seon.mcp/call`) and asserts data:
the reply is read as EDN (`seon.repl/shown-value`), the offered elision form is taken from it as a list, evaluated
through the same entrance, and compared with clojure.core on the value. A new namespace
`test/seon/printer_elision_test.clj` holds them (`test/seon/mcp_entrance_test.clj` is held by lane repl-p11).

Printer first:

- **S1 Set and map cuts continue.** `src/seon/render/value.clj` (`node-continuation`: next offset = shown count for a
  cut collection; the reordered ≤ 64-key map emits the `dissoc` form). Proof
  `a-cut-set-or-map-continues-where-it-stopped`: for `(set (range 300))`, `(zipmap (range 200) (range 200))` and
  `{:m <100 keys>}`, the evaluated elision form equals `(drop 32 (seq v))` and its count equals the printed count.
- **S2 Elision forms are clojure.core.** `src/seon/print.cljc` (`continuation`, `requery-form`: `nth`/`seq`/`val`/`key`
  for typed steps; `value-at` leaves every form), `src/seon/render.clj:1310-1318`, `src/seon/error.clj:2264-2271`.
  Proof `elision-forms-name-only-clojure-core`: for nested list, set and non-keyword-key cuts, every symbol in the
  offered form resolves to `clojure.core` or the result symbol, and the form's value equals the omitted rest.
- **S3 `:out` continues on the live output.** `src/seon/repl.clj` (`shown-output`), `src/seon/sci/eval.clj` and the
  handle binder (`src/seon/turn.clj`): bind the evaluation's output on its live handle so the form is
  `(subs (:out result/e…) n)` — the handle needs a map face for output, or a second symbol `out/e…` bound to the string
  (`(subs out/e… n)`); root to choose, the second keeps `result/e…` the value. Proof
  `the-out-elision-form-reads-the-live-output`; update `test/seon/repl_differential_p5_test.clj` (held by lane out-fix).
- **S4 Rename dig form to elision form.** `CLAUDE.md` Presentation (lines 286, 332, 333, 346),
  `src/seon/render/value.clj` rules docstring, `src/seon/print.cljc:554`, `src/seon/error.clj:2141`,
  `src/seon/health.clj:647`, `src/seon/bootstrap.clj:55`, `docs/seon/specs/agent-loop.md:244`,
  `docs/seon/specs/errors.md:282`, test names in `test/seon/render/value_test.clj` and the P5 test;
  `my.program`'s `:dig` key (an offered operation, not an elision) becomes an operation key. Proof: the S2 test.
- **S5 Strings that fit print whole; Throwables print as chains.** `src/seon/render/value.clj` (task
  `a-short-identifier-string-is-cut-at-its-first-chunk`); delete the frame erasure in `src/seon/sci/eval.clj:290-297`
  and print the report's Throwable as its chain. Proofs: a vector of finding maps shows every `:filename` whole; an
  erroring agent test's report is under 300 tokens and the held exception still has its frames.
- **S6 One walker.** `src/seon/render/value.clj` (`core-text` through `select-nodes` under the core profile; delete
  `datum`/`bounded-text`). Proof: `core-text` of a 200-deep map and of a Throwable equals the profiled printer's
  output under the same profile.

Class (a) conversions:

- **S7 Shown text built outside the printer.** `src/seon/turn.clj:2214`, `src/seon/render.clj:1478`,
  `src/seon/render/transcript.clj:418, 463`, `src/seon/repl.clj:101-107` with `src/seon/error.clj:2451` (code layout
  becomes the printer's), `src/seon/error.clj:2503-2507`, `src/seon/test/runner.clj:123-128`. Proof: a changes reply's
  `:value` reads back as the changes map.
- **S8 Renders stop cutting.** `src/seon/render/var.clj`, `src/seon/render/schema.clj`, `src/seon/health.clj`,
  `src/seon/test/accretion.clj`, `src/seon/ai.clj:80-92`, `src/seon/render/transcript.clj:870`. Proof: a var render
  with 50 pending tests prints `…N (drop k …)` and the form returns the rest.
- **S9 Report functions return whole ranked data.** `src/my/program.clj` (search, sizes, match-row),
  `src/seon/error.clj:1977-1980`, `src/seon/db.clj:2003, 5809`, `src/seon/test.clj:1712`, `src/seon/edit.clj:56-79`
  (window → the whole source plus span; the printer cuts), `src/seon/shell/jvm.clj:303-322` (preview → the text, the
  printer cuts). Proof: `my.program/search` over a 10,000-character line holds the whole line.
- **S10 HTML through the printer's HTML face.** `src/seon/render/web.clj` (8 sites),
  `src/seon/render/transcript.clj:786`. Proof: the debug page's subject renders as printer hiccup (`:seon-print` classes).
- **S11–S14 Messages are fixed text** (R12), scripted once per group then hand-fixed: S11 `seon.db`, `seon.fn`,
  `seon.call-preparation`; S12 `seon.turn`, `seon.agent`, `seon.env`, `seon.effect`; S13 `seon.error`, `seon.schema`,
  `seon.instrument`, `seon.schema.admission`, `seon.test`, `seon.fault`; S14 `seon.cluster.message`, `seon.cluster`,
  `seon.cluster.registry`, `seon.fs`, `seon.sci.eval`. Proof per group: one refusal's `:seon.error/message` equals its
  literal and the value sits in its data key.

Class (b) conversions:

- **S15 Identity, compare, sort by data.** `src/seon/fn.clj:2674`, `src/seon/schema/datahike.clj:525`,
  `src/seon/env.clj:88-90`, `src/seon/ai.clj:82`, `src/seon/config.clj:603`, `src/seon/render/web.clj:853, 870`,
  `src/seon/cluster/agent.clj:764`, `src/seon/bootstrap.clj:310`, `src/seon/cluster/message.clj:197`. Proof: the
  manifest digest of two maps with different entry order is equal.
- **S16 Program text members as data.** `src/seon/program.cljc`, `src/seon/sci/reader.cljc`, `src/seon/sci/arm.clj`,
  `src/seon/turn/batch.clj`, `src/seon/fn.clj:541, 766`, `src/seon/schema.clj:3694` with their schema resources;
  published and adopted on a branch (schema change). Probably two slices; first measure which readers parse the text.
- **S17 Error evidence holds references.** `src/seon/error.clj:337-490` (`fit-fact-payload`, `bounded-text`):
  stored evidence is the printer's shown text plus ids, under the errors spec; `src/seon/render/hiccup.clj`,
  `src/seon/fn/signature.cljc` keep the head and form as data; `src/seon/test/runner.clj:2120` stores the chain. Larger
  than 15 minutes: start with a design note in the errors spec.
- **S18 Turn records keep data.** `src/seon/turn.clj:3856, 4242, 4244`, `src/seon/background.clj:55`,
  `src/seon/effect.clj:541, 866` (existing task `printed-copies-stored-or-sent-instead-of-values`).
- **S19 Tooling.** `bin/seon-hook` (findings returned whole as data and shown through `seon.render.value/core-text` if
  the printer core loads in Babashka — unverified; otherwise the hook shows the first finding and the exact count
  with the command that lists the rest), `script/seon/dev/docstring.clj`, `script/seon/dev/markdown.clj`,
  `bin/test-check`, `script/seon/operator.clj:586, 1834`, logs in `src/seon/db.clj:1432`, `src/seon/schema.clj:1076,
  1084`, `src/seon/cluster/boot.clj:407`, `src/seon/fs.clj:212`.

Enforcement last:

- **S20 `:seon/printer-law` at `:error`** with the ratchet baseline, `bin/seon-hook` and `bin/test-check` linted, the
  interop grep and the inline-ignore refusal in `bin/lane`. Proof: `bin/lane commit` refuses a scratch candidate that
  adds `(pr-str x)` to a `src/` namespace, adds `"… N more"`, or adds `(binding [*print-length* 5] …)`; the
  contracts-compile test stays green.

## Verification limits

- The counts come from grep plus reading each site; class (c) is approximate (≈60) because bounded query sites span
  many lines per site. The 110 message `str` interpolations are listed by command, not one by one.
- Not verified: whether `seon.id` and `seon.print`'s core load in Babashka (S15 operator and dependency digests, S19
  hook feedback); whether clj-kondo hooks can see a map literal's entries passed to arbitrary constructors (rule 4 may
  need a per-constructor hook list).
- MCP probes ran on default as agent `printer-rules-probe` (defined nothing). Each probe answered in 0–4 ms.
