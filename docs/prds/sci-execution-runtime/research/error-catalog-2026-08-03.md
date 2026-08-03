---
type: research
status: active
tags: [research, runtime, error, data-model]
---

# Error catalog — every failure class, and its attribute-shaped replacement

Executing the owner ruling of 2026-08-03 evening: `:seon.error/kind` is a
keyword taxonomy in value space (the kind/type anti-pattern) and is replaced by
attribute-shaped errors. Each failure class becomes its own namespaced attribute
set whose PRESENCE makes it what it is, validated by a registered schema and
found by `matching-shapes-in`. One model covers in-flight error values and
committed fault facts.

The pre-wave stored JVM throwable-class string is renamed to
`:seon.error/throwable-class`. Historical `:seon.error/class` string datoms are
not migrated. The freed `:seon.error/class` attribute is the native boolean
schema-row marker described below; slice-2 templates use
`:seon.error/throwable-class` whenever they preserve JVM class evidence.

Method: every claim below is from reading the constructions, not from a keyword
count. I read `src/seon/error.clj` end to end, plus the dispatch sites,
`seon.fn`, `seon.ai`, `seon.effect`, `seon.fs.jvm`, `seon.edit`,
`seon.cluster.wake`, `seon.cluster.loop`, `seon.problems`, and
`resources/seon/schemas/seon.error.edn` in full.

## 0. Headline corrections to the measured baseline

| Baseline claim | Measured truth |
|---|---|
| ~31 distinct kinds (single-line regex, stated as a lower bound) | **160**: 154 distinct literals at construction sites (`:seon.error/kind <lit>` plus the kinds passed to the seven local `flat-error`/`error-value`/`refused`/`refuse!` constructors), plus 6 selected indirectly — `:seon.sci.eval/time-limit`, `:seon.sci.eval/evaluation-failed` (`src/seon/sci/eval.clj:1604-1605`), `:seon.problems/unbound-var`, `:seon.problems/evaluation-failed` (`src/seon/problems.clj:187-188`), `:seon.operator/failed` (`src/seon/operator.clj:26`), `:seon.error/unclassified` (`src/seon/error.clj:164`) |
| 5 dispatch sites | Confirmed 5 classes dispatched on `:seon.error/kind` (§3), **plus one second taxonomy**: `seon.ai/disposition` dispatches on `:seon.ai/error-class` inside `:seon.error/data` (`src/seon/ai.clj:745-767`) |
| 318 src / 254 test occurrences | Consistent with my file census; 254 test occurrences across 56 test namespaces, 61 of them `(is (= <lit> (:seon.error/kind …)))` |

Two structural findings that make the ruling's case better than the keyword
count does:

1. **Under-classification.** `:seon.fn/index-refused` is ONE kind covering 13
   genuinely different failures (`src/seon/fn.clj:28, 40, 107, 262, 311, 388,
   476, 546, 732, 743, 754, 875, 897`). Each already carries a distinct
   evidence attribute — `:seon.fn/index-phase`, `::resource`,
   `::analysis-entry`, `:seon.fn/capability-rule`, `::findings`,
   `:seon.fn.file/path`, `::identity`, `::missing-population`,
   `:seon.schema/key`, `::existing-program-entity`. The attributes ALREADY name
   the class; the kind adds nothing and hides ten classes behind one keyword.
2. **The blame taxonomy is not a failure class at all.** `:core-bug` (22 sites:
   `src/seon/schema.clj` ×16, `src/seon/blob.clj` ×5, `src/seon/cluster/store.clj`
   ×1) and `:user-input` (31 sites: `src/seon/schema.clj` ×7,
   `src/seon/schema/internal.cljc` ×4, `src/seon/schema/datahike.clj` ×1, and
   the remainder in schema paths) label WHO IS AT FAULT, which
   `src/seon/error.clj:54-61` already rules is the CHANNEL's answer and never a
   predicate. They are 53 of the 318 occurrences and carry zero class
   information. Deleting them is pure gain.

Also non-conforming and worth naming: three bare unnamespaced kinds —
`:configuration` (`src/seon/flow.clj:492, 669`), `:timeout`
(`src/seon/flow.clj:1117`), `:keyword` (`src/seon/sci/reader.cljc`, passed to
its local `error-value`) — plus seven duplicate local `flat-error` constructors
(`src/seon/fs/jvm.clj:35`, `src/seon/edit.clj:9`, `src/seon/edit/jvm.clj:6`,
`src/seon/effect.clj:57`, `src/seon/db.clj:74`, `src/seon/sci/reader.cljc:14`,
`src/seon/cluster/reply.clj:336`). The conversion deletes all seven; there is no
constructor in the target model, only a map literal validated by its class
schema.

## 1. The census

Grouped by class family. "Sites" is the emission count; "Data carries" is what
`:seon.error/data` (or the sibling keys at the site) actually holds; "Face" is
the §2 renderer proposal (D = rides the default error renderer, O = earns a
per-class override).

### 1.1 Capability families (agent-facing, already well-shaped)

| Family / classes | Sites | Data carries | Consumers | Face |
|---|---|---|---|---|
| `:my.fs/*` — `not-found`, `not-directory`, `not-regular-file`, `already-exists`, `path-refused`, `read-failed`, `write-failed`, `read-limit`, `write-limit`, `stale-digest`, `changed-during-read`, `invalid-utf8-window`, `atomic-write-unsupported`, `glob-failed`, `blob-unavailable`, `invalid-glob` (16) | 19 | path, pattern, digest, expected-digest, limit bytes, blob digest | agent context via the flat value; `seon.edit.jvm/edit-error` translates two of them (§3.1) | D, O for `read-limit`/`write-limit` (show the limit and what to do) |
| `:my.edit/*` — `no-match`, `ambiguous-match`, `parse-refused`, `lossless-check-failed`, `stale-source`, `not-utf8` (6) | 8 | `:seon.edit/candidates` + `candidates-complete?`, path, expected vs actual digest | agent context; `edit/jvm` dispatch | O for `no-match`/`ambiguous-match` (candidate list is the whole value) |
| `:my.message/*` — `no-recipient`, `no-content`, `no-about`, `no-reason` (4) | 6 | nothing; message only (`src/my/message.clj:80-92, 142-152`) | agent context | D |
| `:my.run/*` — `blank-note`, `blank-result` (2); `:my.background/*` — `invalid-call`, `invalid-result`, `missing-result` (3) | 5 | call/result value | agent context | D |
| `:seon.effect/*` — `already-recorded`, `already-settled`, `missing-receipt`, `handler-failed`, `interrupted`, `invalid-owner`, `no-evaluation-context` (7) | 11 | `:seon.effect/id` | run loop; receipts | D |

### 1.2 Provider transport (`seon.ai`) — the existing precedent for the target model

| Classes | Sites | Data carries | Consumers | Face |
|---|---|---|---|---|
| `unparseable-body` (6), `provider-error`, `timeout`, `transport-failure`, `no-credential`, `token-starvation`, `invalid-extra-body` (2), `extra-body-conflict` | 15 | `::error-class`, `::request-transmitted?`, `::response-started?`, `::output-observed?`, `::http-status`, `::body`, `:seon.ai/timeout-ms`, `:seon.ai/endpoint` (`src/seon/ai.clj:860-960`) | `seon.ai/disposition` decides retry from `::error-class` + `output-observed?` (`:745-767`); `seon.error` prose reads the same keys (`src/seon/error.clj:473, 500, 569-576`); the loop lifts four of them onto the attempt (`src/seon/cluster/loop.clj:872-873`); `seon.eval.drive:189-191` | O (one AI-attempt face; the four decision attributes are the display) |

This family is the proof the ruling is right: the retry decision does not read
the kind at all, it reads the ATTRIBUTES. The kind is already vestigial here.

### 1.3 Program graph, schema, and evaluation

| Classes | Sites | Data carries | Consumers | Face |
|---|---|---|---|---|
| `:seon.fn/index-refused` (see §0 — source-derived 11-way split) | 13 | phase, resource, analysis entry, capability rule + sym, findings, file path, identity, missing population, schema key, existing entity, or a subjectless missing manifest | boot/init refusal; `bin/seon init` | O per split class |
| `:seon.instrument/contract-violated` | 3 (`src/seon/instrument.clj:148, 170`, doc `:44`) | `:seon.instrument/fn`, `/arm`, `/schema`, `/args` — already lifted to real fact attributes by `normalize` (`src/seon/error.clj:303-306, 330-337`) | **dispatch** in `error/normalize` and `error/notice` (§3.4) | O (the offending key/value pair) |
| `:seon.schema/unresolved-predicate` | 1 (`src/seon/instrument.clj:238`) | predicate symbol | schema install | D |
| `:seon.sci.eval/*` — `install-source-mismatch`, `install-delete-mismatch`, `missing-function-row`, `namespace-binding-cycle`, `reader-event-count`, `schema-refused` (2), `session-blob-unavailable`, plus selected `time-limit` / `evaluation-failed` | 10 | source digests, function row, cycle path, event count, schema key, blob digest | run loop receipts; agent context | O for `time-limit` (`:seon.eval/fn-entries` spin diagnostic) and `schema-refused` |
| `:seon.sci.kernel/*` — `already-armed`, `missing-function-installer`, `missing-interrupt-guard`, `unresolved-invocation`, `failure-admission-failed`, plus indirectly selected `time-limit` and `invocation-failed` | 7 | `:seon.fn/sym`, `:seon.sci.admit/record`, throwable class (`src/seon/sci/kernel.clj:280-300, 362-363`) | the guarded door's one classifier | O for `unresolved-invocation` |
| `:seon.sci.admit/projection-failed`; `:seon.print/unknown-face` | 2 | face keyword, value marker | admission/print floor | D |
| `:seon.program/declaration-refused` | 1 (`src/seon/program.cljc:80`) | declaration form | install gate | D |
| `:seon.problems/unbound-var`, `/evaluation-failed` | derived (`src/seon/problems.clj:186-188`) | form source, ordinal, owner, author | problem routing → `my.message` assignment | O (the routed problem card) |

### 1.4 Cluster lifecycle, store, config

| Classes | Sites | Data carries | Consumers | Face |
|---|---|---|---|---|
| `:seon.cluster.store/*` — `branch-absent`, `branch-already-open`, `held-elsewhere`, `initialization-incomplete`, `refused`, `file-lock-generator-failed` | 7 | branch name, holder pid, lock file | operator; `bin/seon` | D |
| `:seon.cluster.source/*` — `root-absent`, `invalid-source-seal`, `populate-unresolvable`, `publish-readback-failed`, `stale-publication`, `unsafe-incremental-rows`, `refused` | 7 | commit id, path, row counts | `bin/seon init` | D |
| `:seon.cluster.registry/*` — `cannot-retire-main`, `cluster-connected`, `source-absent`, `refused` | 5 | cluster name | operator | D |
| `:seon.cluster.export/*` — `clone-unsupported`, `export-exists`, `genesis-incomplete`, `no-branch-head`, `refused` | 6 | export path, branch head | operator | D |
| `:seon.cluster.message/*` — `unknown-recipient` (2), `unknown-about`, `ambiguous-about`, `blank-content`, `content-too-large`, `chain-limit`, `no-limit` | 9 | recipient id, about id, size, limit | inbound message HTTP path (§3.3 sits above it) | D, O for `content-too-large`/`chain-limit` (show limit vs actual) |
| `:seon.cluster.run/refused` | 1 + the transition-refusal shape (`src/seon/error.clj:226-230, 393-408`) | `:seon.cluster.run/rule`, `/transition`, `/request` | `error/refusal-prose`; the run loop's `terminal-refused!` (`src/seon/cluster/loop.clj:731-775`) | O (`refusal-prose` already exists and is the override) |
| `:seon.cluster.loop/*` — `lint-rejected`, `prompt-failed`, `terminal-refusal-settlement-refused` | 3 | lint findings; settlement data | fault channel | D |
| `:seon.cluster.agent/*` — `no-such-agent`, `turn-completion-backstop`, `turn-completion-undeliverable`; `:seon.cluster.wake/undeliverable-wake`; `:seon.cluster.process/start-instant-unavailable` | 5 | agent id, channel state, pid | fault committer | D |
| `:seon.config/*` — `refused`, `manifest-unreadable`, `reconcile-refused`, `required-absent`, `unknown-key`; `:seon.reconcile/*` — `refused`, `no-identity`, `two-identities`, `duplicate-identity`, `identity-outside-scope` | 12 | config key, manifest path, identity | `config/apply!` | D |
| `:seon.bootstrap/*` — `resource-absent`, `resource-invalid`, `population-conflict`, `plan-absent`, `invalid-ordinals`, `agent-plan-absent`; `:seon.boot/refused` (3) | 9 | resource name, plan ordinal | boot | D |
| `:seon.db/rejected`, `:seon.db/unknown-failure` | 2 (`src/seon/db.clj:897, 904`) | transaction refusal data | **dispatch** in `render/web` (§3.3); every `db/transact!` caller | D |
| `:seon.flow/*` — `submission-capacity`, `launcher-stopped` (3), `time-limit`, `:configuration` (2), `:timeout` | 8 | proc id, capacity | fault channel | D |

### 1.5 Render, walk, web, operator, dev

| Classes | Sites | Data carries | Consumers | Face |
|---|---|---|---|---|
| `:seon.render.walk/elided`, `/no-such-entity` (4 each), `::connections-failed` (`src/seon/render/walk.clj:426`) | 9 | lookup, attribute, hop count | **dispatch** on `elided` at four reads (§3.5) | O for `elided` (it is a display affordance, not a failure) |
| `:seon.render/ambiguous`, `/invalid-ai-output`, `/invalid-html-output` | 3 | candidate producer symbols, the offending output | render pipeline; page fallback | O for `ambiguous` |
| `:seon.render.hiccup/unparseable-tag` (4); `:seon.render.data/no-such-path`; `:seon.render.value/window-failed` | 6 | tag, path, window | render | D |
| `:seon.render.web/*` — `missing-port`, `owner-not-ensured`, `value-not-found`, `value-unreadable` | 4 | port, digest | web boot / `/data` | D |
| `:seon.dev.mcp/*` — `cluster-degraded`, `value-not-found`, `remainder-not-retrievable` | 3 | cluster name, digest, path | MCP envelope | O (the MCP envelope is a distinct audience) |
| `:seon.cluster.reply/*` — `refused-tag`, `unreadable`, `no-forms` | 3 | the text | **dispatch** on `refused-tag` (§3.2) | O for `unreadable` (reader position) |
| `:seon.test.runner/*` — `invalid-silence-seconds`, `invalid-long-reason`, `long-test-ns-hook`, `default-cluster-refused`, `invalid-selection-mode`; `:seon.artifact/refused`; `:seon.operator/failed`; `:seon.eval.drive/absent`; `:seon.cluster.prompt/no-trigger`, `/refused` | 10 | selection, reason, cluster | `bin/test`, operator | D |
| `:core-bug` (22), `:user-input` (31) — the blame taxonomy | 53 | varies per site; each site's real evidence is already beside the kind | nobody dispatches on them | replaced per site |
| `:seon.error/unclassified` | 1 (`src/seon/error.clj:164`) | the whole source in `data-edn` | the fail-closed floor | O (it must say honestly that nothing recognized it) |

**Corrected source-derived count: 225 target class schemas.** The earlier
approximately-118 arithmetic incorrectly subtracted 53 blame *occurrences* as
though they were 53 distinct kinds; they are only the two literals `:core-bug`
and `:user-input`. A complete construction-site pass found 163 distinct current
kinds, then applied the two ruled pair merges, the 11-way `index-refused`
split, 10 additional unique AI replacement names (`timeout` overlaps), and 45
real replacements for the blame sites: 39 schema classes, five blob classes,
and one cluster-store class. The grouped inclusion oracle adds to 225; its SCI,
evaluation, and program-outcome group contains 21 rather than the initially
reported 20. The concurrent shell work adds five classes outside this catalog's
scope and is not included in 225.

## 2. Per-class design — the replacement shape

### 2.1 The one rule

Every class declares exactly ONE **marker attribute**, `:<deepest-owning-ns>/<class-name>`,
whose VALUE is the class's primary subject — the scalar a reader most needs.
Presence of the marker IS the class. Sibling evidence rides as ordinary
namespaced attributes. `:seon.error/message` survives unchanged as a required
human line on every error value; it is prose, not a taxonomy.

```clojure
;; today (src/seon/fs/jvm.clj, via its local flat-error)
{:seon.error/kind :my.fs/not-found
 :seon.error/message "No file exists at that path."
 :seon.error/data {:my.fs/path "/a/b"}}

;; target
{:my.fs/not-found "/a/b"
 :seon.error/message "No file exists at that path."}
```

Where a class has genuinely no scalar subject the marker is `:boolean` valued
`true` (never `false` — absence is the state). This keeps every marker
bridgeable to a Datahike attribute, which §6 needs.

Registered schema, one per class, in the flat file that already owns the
namespace (`resources/seon/schemas/my.fs.edn`, `seon.ai.edn`, `seon.fn.edn`, …
— the directory layout, not the single `schema.edn` the `data-modeling` skill
still names; that skill line is stale and should be fixed):

```clojure
;; resources/seon/schemas/my.fs.edn
{:my.fs/not-found :my.fs/path
 :my.fs/not-found-error
 [:map {:seon.error/class true
        :seon.render/ai seon.error/render-ai
        :seon.render/html seon.error/render-html}
  [:my.fs/not-found :my.fs/not-found]
  [:seon.error/message :seon.error/message]]}
```

Maps stay OPEN (owner ruling #48) and required keys stay minimal: the marker
plus `:seon.error/message`. Everything else is optional, so a site that learns
new evidence tomorrow accretes rather than breaking. `{:seon.error/class true}`
is an ordinary Malli property (ruling #47; `m/properties`,
`reference-code/malli/src/malli/core.cljc:39`) and is the fact that makes "which
schemas are error classes?" a query — the same mechanism `{:seon.db/entity true}`
already uses (`src/seon/schema.clj:928-934`).

Class selection is then `schema/matching-shapes-in` unchanged
(`src/seon/schema.clj:2361-2383`): it indexes by present attributes, filters on
required-attrs, and validates. Two classes matching one value is the
already-handled ambiguity case, and it is now a schema defect a query finds
rather than a keyword collision nobody can see.

### 2.2 Rendering — one default, overrides where a class earns them

**(a) The default error renderer.** One declared producer pair accepting any
value carrying `{:seon.error/class true}`:

- `:seon.render/ai` — one to three honest lines: what failed, the load-bearing
  attribute values (the marker's value plus every present sibling), and what to
  do next. This is what the agent reads: the flat error value IS the agent's
  face, so no error may fall through to the generic value floor.
- `:seon.render/html` — a readable card for debug and owner surfaces:
  message headline, marker row, evidence rows, evidence link
  (`[:seon.error/id …]`) when the value came from a committed fact.

Declared exactly like today's fact renderers
(`resources/seon/schemas/seon.error.edn:38-39` already carries
`:seon.render/ai seon.error/render-ai` and `:seon.render/html
seon.error/render-html` as schema properties), so it is discovered by the same
output-declaration query as every other renderer — never a hand-wired dispatch.

**(b) Per-class overrides.** A class earns one only when it has display its
attributes alone cannot carry. The census "Face" column marks each; the ones
that clearly earn it:

| Class | Why it earns an override |
|---|---|
| `:seon.instrument/contract-violated` | the offending key/value pair against the expected schema — today's `instrumentation-prose` (`src/seon/error.clj:337-372`) is exactly this and becomes the class's declared renderer |
| `:seon.cluster.run/refused` | today's `refusal-prose` (`src/seon/error.clj:393-408`), which names the transition, the rule, and that nothing committed |
| `:seon.ai/*` attempt failures | the four decision attributes (`request-transmitted?`, `response-started?`, `output-observed?`, `http-status`) are the display; today's ai prose (`src/seon/error.clj:473-576`) becomes it |
| `:seon.sci.eval/time-limit` | the `:seon.eval/fn-entries` spin diagnostic (271M entries reads as a spin, 12 reads as blocked in a host call) |
| `:my.edit/no-match`, `/ambiguous-match` | the bounded candidate list is the whole value |
| `:seon.render.walk/elided` | a display affordance, not a failure — it must not read as an error at all |
| `:seon.error/unclassified` | must honestly say nothing recognized this, and show the capped projection |
| `:seon.dev.mcp/*` | the MCP envelope is its own audience |
| the `:seon.fn/index-refused` split classes | each names a different init failure with a different repair |

Scope fence: these faces serve agent context, eval results, MCP envelopes, and
owner/debug pages. PUBLIC namespace-page failure handling stays
loading/unavailable per ruling #50 and is untouched here.

### 2.3 Merge proposals (owner decides; I recommend, I do not decide)

| Proposal | Recommendation |
|---|---|
| `:seon.render/invalid-ai-output` + `/invalid-html-output` → one class with `:seon.render/output` naming the projection (`src/seon/render.clj:184, 199` differ only in that word) | MERGE |
| `:seon.sci.eval/install-source-mismatch` + `/install-delete-mismatch` → one `:seon.sci.eval/install-mismatch` carrying which side mismatched | MERGE |
| the ten `::refused` kinds in `config`, `reconcile`, `artifact`, `boot`, `cluster.{store,source,registry,prompt,export,run}` | KEEP per namespace (each has a distinct repair) but reference ONE shared registered shape for the rule/transition attributes so they cannot drift |
| `:seon.effect/already-recorded` + `/already-settled` | KEEP separate — a replayed request identity and a terminal receipt are different repairs, despite reading alike |
| `:my.fs/read-limit` + `/write-limit` | KEEP separate, sharing one `:my.fs/limit-bytes` attribute |
| `:seon.db/rejected` + `/unknown-failure` | KEEP — §3.3's HTTP status depends on the distinction — but rename to say what it means: "the database refused this transaction" vs "we do not know what happened" |
| `:core-bug` + `:user-input` (53 sites) | DELETE the kinds entirely; each site gets its real class. Blame is the channel's answer (`src/seon/error.clj:54-61`), and a taxonomy that contradicts its own namespace's doctrine is the clearest case in the tree |
| `:configuration`, `:timeout`, `:keyword` (bare, unnamespaced) | REPLACE with namespaced classes in their owning namespaces |

## 3. The five dispatch conversions

**3.1 `src/seon/edit/jvm.clj:20-31`** — `(case (:seon.error/kind result) :my.fs/stale-digest … :my.fs/invalid-utf8-window … result)`.
Replacement: attribute presence, in the same `cond` shape.

```clojure
(defn- edit-error [result request actual-digest]
  (cond
    (contains? result :my.fs/stale-digest)
    (stale-source request (:my.fs/digest result))

    (contains? result :my.fs/invalid-utf8-window)
    {:my.edit/not-utf8 (:my.edit/path request)
     :my.fs/digest actual-digest
     :seon.error/message "Structural source editing requires strict UTF-8."}

    :else result))
```

**3.2 `src/seon/cluster/reply.clj:341`** — `(= :seon.sci.reader/refused-tag (:seon.error/kind events))` selecting `::refused-tag` over `::unreadable`.
Replacement: `(contains? events :seon.sci.reader/refused-tag)`.

**3.3 `src/seon/render/web.clj:1155-1166`** — three-way HTTP branch on `(not kind)` / `(= :seon.db/unknown-failure kind)` / `:else`.
Replacement: `(seon.error/error? result)` for the first (presence of ANY class
schema match, or simply of `:seon.error/message`), `(contains? result
:seon.db/unknown-failure)` for the 500 branch, `:else` 422. The distinction the
status codes encode is preserved exactly.

**3.4 `src/seon/error.clj:300-302` and `:371-374`** — `(= :seon.instrument/contract-violated (:seon.error/kind failure-data))`, twice: once to lift the instrument attributes onto the fact, once in `notice` to select `instrumentation-prose` over `ai-prose`.
Replacement: the first becomes `(contains? failure-data :seon.instrument/fn)` —
the attributes it is about to lift are the test. The second DISAPPEARS: the
producer is declared on the class schema (§2.2b) and found by the same
output-declaration query as every renderer, so `notice` stops choosing at all
and just carries `:seon.render/ai` from the matched class.

**3.5 `src/seon/render/walk.clj:331, 523-524, 572, 581`** — four reads of `(= ::elided …)`, one at the marker constructor and three in the unit key/prose paths.
Replacement: `(contains? failure :seon.render.walk/elided)` and
`(contains? (:seon.error/value unit) :seon.render.walk/elided)`. Four
mechanical rewrites, one class.

**Sixth, the second taxonomy.** `src/seon/ai.clj:745-767` dispatches
`disposition` on `:seon.ai/error-class` (`:rate-limit`/`:server`/`:credential`/
`:authentication`/`:authorization`/`:model`/`:transport-before-send`/`:request`/
`:response`/`:timeout`/`:transport-unknown`) — a kind taxonomy one level down.
It is out of the ruling's literal scope but is the same defect, and the decision
it drives is already mostly attribute-based (`output-observed?` short-circuits
first). Recommendation: convert it in the same wave, because leaving it is
leaving the pattern the ruling exists to delete. Flagged for the owner.

## 4. Transform templates

**Honest constraint first.** `src/seon/edit.clj` exposes exactly two operations:
`form` (one unambiguous named TOP-LEVEL form, `:288`) and `exact` (old-string,
`:362`). It has no "rewrite every map literal containing key K" operation, and
nearly every emission site is a map literal INSIDE a function body. So `seon.edit`
can drive the conversion only as per-site `:my.edit/old-string` exact edits
generated from this census, or the whole-defn `:my.edit/form` replacement where
the defn is small. A rewrite-clj zip pass in `tmp/` is the honest alternative
for the mechanical majority; either way the census is the work list.

**T1 — flat value, literal kind, data map (the majority, ~110 sites).**

```
old: {:seon.error/kind K :seon.error/message M :seon.error/data {a1 v1 …}}
new: {MARKER(K) SUBJECT(K, {a1 v1 …}) :seon.error/message M  <remaining ai vi>}
```

where `MARKER(K)` is the class marker from §2 and `SUBJECT` is the site's
already-present primary evidence value. Drive it per-site with `:my.edit/form`
on the enclosing defn.

**T2 — flat value, literal kind, sibling keys (no `:seon.error/data`), e.g. `src/seon/blob.clj:51-52, 63-64, 97-100, 179-180, 236-238`, `src/seon/fn.clj` throughout.**

```
old: {:seon.error/kind K a1 v1 a2 v2}
new: {MARKER(K) v1 :seon.error/message "<new honest line>" a2 v2}
```

These sites carry NO message today (they are `ex-info` data whose message is the
`ex-info` string). The conversion must supply the human line, which is hand
work, not mechanical — one line per site.

**T3 — constructor call sites (~55 sites through seven local `flat-error`/`error-value`/`refused`/`refuse!` helpers).**

```
old: (flat-error K M {a1 v1 …})
new: {MARKER(K) SUBJECT :seon.error/message M  <rest>}
```

and DELETE the seven helper defns in the same commit.

**T4 — presence tests (~90 sites): `(:seon.error/kind x)` used as a truth test.**

```
old: (if (:seon.error/kind x) …)      new: (if (error/error? x) …)
old: (when-not (:seon.error/kind x) …) new: (when-not (error/error? x) …)
```

One new predicate in `seon.error`: true when the value's matched class schemas
are non-empty (or, for the leaves that must not depend on the schema registry —
`seon.edit`, `seon.sci.reader` — presence of `:seon.error/message` in a map).
This is a pure `:my.edit/old-string` replacement per site.

**T5 — dispatch sites (5).** Hand work; §3 gives each replacement verbatim.

**Irregular sites needing hands, named:**

- `src/seon/error.clj:190-199` (`kind`), `:295-340` (`normalize`), `:345-354`
  (`value`), `:355-380` (`notice`), `:400-408`, `:829-838` — the normalizer
  itself; §6.
- `src/seon/sci/kernel.clj:280-300` — kinds arrive as REQUEST PARAMETERS
  (`::time-limit-kind`, `::failure-kind`, supplied at
  `src/seon/sci/eval.clj:1604-1605`); the parameterization becomes two marker
  attributes.
- `src/seon/fs/jvm.clj:49-57` (`error-value`) — reconstructs a kind out of
  `ex-data`; becomes a merge of the class attributes.
- `src/seon/problems.clj:134` — Datalog `[?receipt :seon.error/kind ?kind]`; §6.
- `src/seon/eval/drive.clj:146, 157, 193` — `get-else` with
  `:seon.eval.drive/absent` as the default; the absent case becomes absence.
- `src/seon/cluster/loop.clj:257-258, 275, 300, 313, 704, 751, 765, 810` — the
  receipt's kind attribute; §6.
- `src/seon/render/transcript.clj:51, 285, 404` — round-trips kind through a
  private `::error-kind` entry key.
- `src/seon/operator.clj:26` — `(or (:seon.error/kind data) ::failed)`.
- `src/seon/cluster/run.clj:556, 775, 811` — `:seon.error/kind` in the run
  schemas.

## 5. Spec declaration mechanics

**Declaring error branches.** A function's `:malli/schema` output becomes an
`[:or …]` naming the success schema and each error class schema it can return:

```clojure
(defn read
  {:malli/schema [:=> [:cat :my.fs/read-request]
                  [:or :my.fs/read-response
                       :my.fs/not-found-error
                       :my.fs/read-limit-error]]}
  …)
```

**Why this is already queryable.** `src/seon/program.cljc:261-279` computes
`:seon.fn.arity/input-refs` and `:seon.fn.arity/output-refs` as SETS OF REFS to
the schema entities named anywhere in the arity's input/output forms
(`resources/seon/schemas/seon.fn.arity.edn:5,10,25-30`). An `[:or …]` output
therefore already produces one output-ref per branch — no new fact, no new
indexer.

**"What errors can `f` return?"**

```clojure
[:find ?schema-key
 :in $ ?sym
 :where
 [?f :seon.fn/sym ?sym]
 [?f :seon.fn/arities ?a]
 [?a :seon.fn.arity/output-refs ?s]
 [?s :seon.schema/key ?schema-key]
 [?s :seon.error/class true]]
```

**The reverse — "who can return this error class?"** — is the same query with
`?schema-key` bound and `?sym` found. The precedent is already in the tree:
`resources/seon/bootstrap.edn:43` runs exactly this query shape over
`input-refs`.

The `[?s :seon.error/class true]` clause requires that the schema property from
§2.1 lands as a `:seon.schema` row attribute, the same way `{:seon.db/entity
true}` does. Confirming that the projection emits arbitrary schema properties as
row attributes (rather than only its known set) is an **open question for the
schema owner** — if it does not today, that is the one enabling change.

**The advisory install gate.** At install/admission, for each error VALUE the
new definition is observed to produce (from the definition's own declared
branches at install time, and from committed receipts at runtime), match its
class with `matching-shapes-in` and compare against the arity's declared
output-ref error classes. An observed class not in the declared set produces a
TEACHING WARNING, never a refusal:

```
seon.fs/read returned :my.fs/blob-unavailable, which its output schema does
not declare. Add it to the [:or …] output, or return a declared class.
```

Advisory is the correct strength: undeclared is the accretion case (a new class
is added before every caller's spec knows it), and refusing would make adding a
class a breaking change — which §2's open-maps law exists to prevent.

## 6. Fault-fact alignment

**Today.** `seon.error/normalize` (`src/seon/error.clj:295-340`) writes a fact
carrying `:seon.error/kind` (required, `resources/seon/schemas/seon.error.edn:43,
70`) plus `:seon.error/data-edn` — the WHOLE source, printed through
`seon.sci.admit` in `:record` mode, as one opaque string (`:29`). The only
structured evidence that escapes the string today is the four instrument
attributes, lifted by the §3.4 dispatch (`:303-306, 330-337`). `error/value`
(`:345-354`) projects the fact back down to a flat value carrying kind, message,
and an evidence pointer. `seon.cluster/commit-fault!`
(`src/seon/cluster.clj:1314-1356`) is the one committer for Throwables off
flow's error channel; the run loop's `terminal-refused!`
(`src/seon/cluster/loop.clj:731-775`) is the other.

**Converted.** The class marker and its evidence attributes become REAL DATOMS
on the fact — that is the whole point of one model — while `data-edn` survives
for the unadmittable remainder (flow's `::flow/state` holding a live connection,
a reference cycle: `src/seon/error.clj:72-84`). `:seon.error/kind` is dropped
from `:seon.error/fact` in the same wave.

```clojure
{:seon.error/id "…", :seon.error/at #inst"…", :seon.error/process "…"
 :seon.error/message "…"
 :seon.error/signature "…"          ; now over [process class MARKER-KEY frame]
 :seon.error/data-edn "…", :seon.error/capped? false
 :my.fs/not-found "/a/b"            ; the class, as a datom
 :seon.error/to <owning-agent-eid>  ; the ownership ref — see below
 :seon.error/run […], :seon.error/agent […]}
```

`signature` (`:249-256`) substitutes the marker attribute KEY for the kind — same
content-addressing, same recurrence query, no change to `seon.problems`'
grouping (`src/seon/problems.clj:72-89`). `problems`' one Datalog use of kind
(`:134`) becomes a query over the marker attribute, or over
`:seon.error/signature` alone where the class name was only for display.

**Historical facts stay as history.** Committed facts carrying
`:seon.error/kind` are not migrated. The attribute declaration remains in the
schema so history reads; nothing new writes it. That is ordinary accretion, and
`problems` degrades honestly on an old fact (no marker, so the default face
renders the message and the signature).

**The owning-agent wake chain — existing mechanism, not new machinery.**
`:seon.error/to` is a ref to the OWNING agent entity, derived at commit time
from the failing unit's namespace owner. The derivation already exists:
`seon.cluster.work/form-owner` (`src/seon/cluster/work.clj:202-212`) resolves a
form's namespace owner, and `:seon.cluster.agent/namespace` is unique, so the
owner is a lookup, never a name inference. `commit-fault!` adds it exactly when
a namespace owner is derivable — absent otherwise, because an untagged cluster-graph
fault genuinely has no owner (`src/seon/cluster.clj:1319-1327` already reasons
this way about run attribution).

The wake then works with no new code, because `route!` already routes on a
datom whose VALUE is the recipient's entity id:

1. the fault fact commits with `:seon.error/to <agent-eid>`;
2. `seon.cluster.wake/wake-attributes` (`src/seon/cluster/wake.clj:78-93`, today
   `#{:seon.cluster.message/to :seon.effect/to :seon.cluster.agent/id}`) gains
   `:seon.error/to` — a one-element addition to a COMPUTED set that
   `committed-attributes` is checked against (C2 disjointness);
3. `route!`'s `case` (`:213-230`) gains the identical branch the other two
   `…/to` attributes already use: look the eid up in the routing map, `offer!`
   a payload-free wake into that agent's mailbox, fall back to the armer;
4. the owning agent's flow kicks and derives the problem from facts.

Two properties this inherits for free and must not break: the wake carries no
information (a coalesced wake loses nothing), and a new fact is a new entity so
the datom always exists and the wake always fires (`:86-90`). The only real
check is disjointness — the fault committer must not be woken by its own commit;
it is not, because the fault committer is not an agent mailbox.

## 7. Migration order — one wave

Cut-first, per the standing ruling. The kind attribute dies in the SAME wave.

- **W1 (schemas).** Declare every class schema under
  `resources/seon/schemas/<ns>.edn` with `{:seon.error/class true}` and the
  §2.1 shape; land the default error renderer and the overrides §2.2b names;
  land `error/error?`. Nothing else changes yet. This slice is independently
  green.
- **W2 (emissions + constructors).** Apply T1/T2/T3 across all ~165
  construction sites, deleting the seven local constructors. Same commit per
  namespace family; the families are file-disjoint, so this parallelizes cleanly
  across lanes (fs/edit · ai · fn/schema · cluster lifecycle · render · my.*).
- **W3 (presence tests + dispatch).** T4 across ~90 sites and T5's five
  conversions, plus the §3-sixth `:seon.ai/error-class` conversion if the owner
  rules it in.
- **W4 (facts + wake).** `normalize`/`value`/`notice`/`signature`, the receipt
  and run schemas, `commit-fault!`'s `:seon.error/to`, the one-line
  `wake-attributes` and `route!` additions, `problems`' Datalog. **Drop
  `:seon.error/kind` from `:seon.error/fact` and every request/value schema in
  this same commit.** The attribute declaration stays for history reads only.
- **W5 (spec branches + gate).** Add `[:or …]` error branches to the capability
  and door entry points first (they are what agents call), then the advisory
  install-gate check.

**Tests (254 assertions).** Three mechanical classes and one that needs hands:

- 61 `(is (= <literal-kind> (:seon.error/kind x)))` → `(is (contains? x MARKER))`,
  or better `(is (= <subject> (MARKER x)))` where the subject is asserted
  anyway. Mechanical, per-site, generated from this census.
- ~86 `(:seon.error/kind x)` truth tests → `(error/error? x)`. Mechanical.
- ~75 fixture/literal error maps inside test data → T1/T3. Mechanical.
- The remainder — `test/seon/error_test.clj` (19), `test/seon/cluster/turn_test.clj`
  (29), `test/seon/cluster/problem_routing_test.clj` (10) — assert the
  normalizer, receipt, and routing behavior directly and are rewritten by hand
  against the surviving mechanism, never green-washed. Tests pinning a deleted
  path are deleted in the same commit.

**Falsifiers** (each must be a recurring surface, not a one-off lane proof):

1. **No kind survives.** `rg ':seon\.error/kind' src/ script/` returns only the
   history-read declaration in `seon.error.edn`. A standing test asserts the
   program graph holds zero `:seon.fn` bodies referencing the keyword.
2. **Every class is findable.** For every schema row carrying
   `:seon.error/class true`, a generated sample value (the schema IS the
   generator) is fed to `matching-shapes-in` and must match exactly that class.
   Ambiguity — two classes matching one value — fails the property. This is the
   one test that makes accidental class collision unrepresentable.
3. **No error reaches the value floor.** Generate one value per class, render
   both projections, assert a non-generic face for every one.
4. **The five dispatches still decide the same thing.** One behavioral test per
   §3 site over the before/after inputs: the HTTP status triple, the edit-error
   translation, the reader refused-tag branch, the instrument face selection,
   the walk elision suppression.
5. **The wake fires.** Live, at a reset boundary (a fixture cannot see this
   class): commit a fault carrying `:seon.error/to`, observe the owning agent's
   mailbox receive a wake and its graph derive the problem. Plus the standing
   disjointness property between `wake-attributes` and `committed-attributes`.
6. **Declared branches match reality.** For each converted capability entry
   point, drive its failure paths and assert every observed class is in its
   declared output-refs — i.e. the advisory gate stays silent on converted code.

## 8. Open questions for the owner

1. **Marker value where there is no subject.** I recommend `:boolean` `true`
   (never `false`). The alternative — a nested evidence map as the marker's
   value — is not Datahike-bridgeable and would split the one model in two.
2. **Does the schema projection emit arbitrary Malli properties as `:seon.schema`
   row attributes?** §5's query needs `[?s :seon.error/class true]`. If only a
   known set is emitted today, opening it is the one enabling change.
3. **The `:seon.ai/error-class` second taxonomy** (§3-sixth): convert in this
   wave or leave it? I recommend converting.
4. **The eleven `::refused` kinds**: one shared shape referenced by eleven
   classes (my recommendation), or eleven fully independent classes?
5. **How far does `:seon.error/message` go?** I kept it required on every value.
   An alternative is deriving it from the class renderer — but a value that
   cannot say what happened without a registry lookup is worse for the leaves
   (`seon.edit`, `seon.sci.reader`) that deliberately have no registry
   dependency. Recommendation: keep it required.
6. **The exact replacement class count for the 53 blame sites** is the
   implementation lane's per-site call; §1's ≈118 target assumes ~12.
