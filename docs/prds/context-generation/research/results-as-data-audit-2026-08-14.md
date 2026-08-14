---
type: research
status: complete
tags: [research, render, agent, context, class/n1, wave/strict-repl-display]
---

# What the agent gets back — results-as-data audit, 2026-08-14

## The question

The ruled model (plan README, ruling 24-28 era) is that an agent's context IS
a real REPL session: what sits in RESULT position after a form is a VALUE,
printed as readable data. Prose that is genuinely data is a quoted string; the
agent's own prose is a separate category. This audit walks every result
position in the REAL captured context of the live Drive 1 cluster and asks: how
much of it is data, where does every prose block come from, and what should
each producing seam return instead.

## Method and evidence base

The evidence is the drive cluster's own stored capture facts, read from inside
its own JVM through its advertised prepl with explicit custody
(`(seon.operator/connection "default")`, `datahike.api` with explicit `db`).
`bin/seon --root tmp/drive-1-root status` reported pid 69568, prepl 55155.

Eleven `:seon.context.capture/id` rows exist; six carry a
`:seon.context.capture/prompt` (five later ones store no prompt). Their exact
bytes were written out and walked entry by entry:

| capture entity | id | basis `:t` | prompt chars |
|---|---|---|---|
| 30444 | `bootstrap:drive-one-agent-attempt-5-context-536871009` | 536871009 | 34,033 |
| 30500 | `bootstrap:root-context-536871047` | 536871047 | 33,199 |
| 30583 | `bootstrap:root-context-536871091` | 536871091 | 33,199 |
| 30657 | `a887d305-…-context-536871133` | 536871133 | 34,955 |
| 30795 | `2137d230-…-context-536871190` | 536871190 | 63,945 |
| 31021 | `9e7db417-…-context-536871318` | 536871318 | 91,099 |

Classification is mechanical, not by eye. An entry is a prompt line matching
`^<ns>=> ` plus everything up to the next one. Its result position is HONEST
DATA when the complete text reads through Clojure's reader as data with
nothing left over; anything the reader cannot consume as data is prose in
result position. The classifier:

```clojure
(defn forms [s]
  (let [r (PushbackReader. (StringReader. s))]
    (loop [acc []]
      (let [v (try (read {:eof ::eof :read-cond :allow} r)
                   (catch Exception e ::unreadable))]
        (cond (= v ::eof) acc
              (= v ::unreadable) (conj acc ::unreadable)
              :else (recur (conj acc v)))))))
```

## The number

**210 result positions. 64 are honest data (30.5%). 140 are narrated prose
(66.7%). 6 are a quoted string carrying an English elision tail (2.9%).**

Two thirds of everything the agent gets back from a query is English.

Of the 64 honest ones, 58 are a single readable form (the `dir` namespace
walks, which end in a proper `:seon.print/face :seon.print/elided` map, and the
plain agent-entity pulls) and 6 are the two-value `dir` of the agent's own
namespace (an `ns` form plus an error map — both data).

### Where the prose comes from

| # | result positions | producing seam |
|---|---|---|
| 1 | 74 | the whole value replaced by its family's declared `:seon.render/ai` producer |
| 2 | 45 | a nested `/ai` render spliced UNQUOTED inside otherwise-EDN maps |
| 3 | 15 | the value floor's non-EDN `label: value` map face |
| 4 | 6 | an elision rendered as an English tail inside a quoted string |
| 5 | 6 | a declared instruction entity — prose BY DESIGN, but sitting in result position |

## Seam 1 — the value is replaced by a sentence (74 positions)

`seon.render/project-node*` (`src/seon/render.clj:445-495`) walks a print node
and, whenever the sub-value is a map with a declared producer for the requested
output, REPLACES that subtree with a `:seon.print/projected` node holding the
producer's string. At depth 0 that means the agent's query result is discarded
and its family's English summary is delivered instead.

Attribution is verbatim, not inferred. Live in the drive JVM:

```clojure
(run/render-ai (assoc (d/pull db '[*] [:seon.cluster.run/id "bootstrap:root"])
                      :seon.db/db db))
;;=> "Run bootstrap:root, opened #inst \"2026-08-14T11:24:27.135-00:00\". It completed."

(problems/stale-var-ai {:seon.fn/sym "seon.operator/collect!"})
;;=> "Restart the JVM to remove stale loaded Var seon.operator/collect!; it is
;;    absent from the published program graph."
```

Both are byte-identical to what the capture holds in result position.

**The cost, measured.** The same live pull genuinely returns **6,596 characters
across 11 attributes**:

```
:db/id :seon.cluster.run/agent :seon.cluster.run/closed-at
:seon.cluster.run/forms :seon.cluster.run/id :seon.cluster.run/opened-at
:seon.cluster.run/opening-commit-id :seon.cluster.run/plan-digest
:seon.cluster.run/starting-ns :seon.cluster.run/trigger
:seon.cluster.work/situation
```

The agent received **79 characters** naming three of them. `/forms`,
`/trigger`, `/plan-digest`, `/opening-commit-id` and the whole
`:seon.cluster.work/situation` — the facts an agent would actually reason from
— were destroyed by the renderer. **98.8% of the queried data never reached the
agent that asked for it.**

Members of this seam, by lookup attribute and count:

- 16 `:seon.cluster.run/id` → `seon.cluster.run/render-ai`
  (`src/seon/cluster/run.clj:1913-1966`)
- 15 `:seon.fn/sym` → `seon.problems/stale-var-ai`
  (`src/seon/problems.clj:434-438`)
- 13 `my.message/read` → `seon.cluster.message/render-ai`
  (`src/seon/cluster/message.clj:460-471`)
- 10 `:seon.error/id` → `seon.error` prose (`src/seon/error.clj:604-627`)
- 6 `:seon.cluster/name` → `seon.cluster/render-ai` (`src/seon/cluster.clj:155-168`)
- 6 `:seon.config/cluster` → the config family's `/ai`
- 4 `:seon.cluster.run.form/id` → `seon.cluster.run/render-form-ai`
  (`src/seon/cluster/run.clj:1978-1983`) — already filed
- 4 `:seon.cluster.eval/id` → the execution-error face

Verbatim, the worst of them. A pull of a **function row** returns an
operational instruction about the JVM:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.fn/sym "seon.operator/collect!"])
Restart the JVM to remove stale loaded Var seon.operator/collect!; it is absent from the published program graph.
```

Nothing about that answer is the function's `:seon.fn/spec`, `:seon.fn/doc`, or
arities. The agent asked the program graph a question and got told to reboot.

A pull of a **cluster** returns two English sentences:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.cluster/name "default"])
Cluster default.
Configuration default; 1 shared instruction and 9 toolkit namespaces.
```

A pull of a **config row** returns a settings paragraph:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.config/cluster "default"])
Configuration default · manifest 637c5f03a6ad.
Model deepseek-v4-flash (thinking disabled, max 65536 output tokens); evaluation 30000 ms; Flow 18 compute / 64 I/O; core faults panic.
```

Every one of those numbers is a config fact the agent could have queried,
joined, and compared. As a sentence it can only be re-parsed.

This seam also carries the identity substitution already filed as
[a displayed run entry must name the run its own form
pulled](../../../seon/issues/a-run-history-entry-can-name-a-different-run-than-its-form-pulled.md).
The capture corroborates it three more times in one transcript — the form names
one run, the sentence names another:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.cluster.run/id "bootstrap:root"])
Run 9e7db417-d54e-4de2-b1b8-19cddeb18ac4, opened #inst "2026-08-14T11:24:27.135-00:00". It completed.

my.agents.root=> (db/pull db (quote [*]) [:seon.cluster.run/id "2137d230-3874-4e57-9a75-c0c6988ac7d1"])
Run 9e7db417-d54e-4de2-b1b8-19cddeb18ac4, opened #inst "2026-08-14T11:29:08.117-00:00". It completed.
```

A data result cannot lie about which entity it is; only a re-narration can.

## Seam 2 — prose spliced unquoted INTO printed data (45 positions)

This is the worst finding in the audit and it is not filed anywhere.

`seon.print`'s text sink emits a projected `/ai` fragment RAW:

```clojure
;; src/seon/print.cljc:107-112
(-fragment [_ output value]
  (append-chunk! state
                 (if (= :seon.render/ai output)
                   value
                   (pr-str value))))
```

Because `project-node*` substitutes producers at EVERY depth, a ref sitting at
map-value depth becomes an English sentence with no quotes, no escaping, and
embedded newlines — spliced into the middle of a map that is otherwise perfect
EDN. The result is not merely ugly: **the whole value stops being readable**.

Verbatim, from capture 31021:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.maintenance.receipt/id "maintenance-receipt/[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]"])
{:seon.maintenance.receipt/fire {:db/id 29995, :seon.schedule.fire/id "[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]"},
  :seon.maintenance.receipt/request {:db/id 29996, :seon.maintenance.request/id
    "maintenance-request/[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]"},
  :seon.maintenance.receipt/task {:db/id 29994, :seon.schedule.task/id "root/maintenance/compact"},
  :seon.maintenance.receipt/started-at #inst "2026-08-14T11:24:27.953-00:00",
  :db/id 29997, :seon.maintenance.receipt/completed-at #inst "2026-08-14T11:24:32.038-00:00",
  :seon.maintenance.receipt/handler Restart the JVM to remove stale loaded Var seon.operator/collect!; it is absent from the published program graph.}
```

The last entry's value is a bare sentence. A reader hits `Restart` where a
value belongs and the map never closes correctly. Nine of the ten keys are
honest data; one prose splice makes the entire result unreadable.

The most severe instance splices the AGENT'S OWN OPENING INSTRUCTION into a
maintenance record, across three lines, at map-value depth:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.maintenance.request/id "maintenance-request/[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]"])
{:seon.maintenance.request/log-dir "/Users/sean/src/seon/tmp/drive-1-root/data/clusters/default/logs",
  :seon.config.maintenance/min-usable-bytes 53687091200, :seon.config.maintenance/log-retained-files
  4, :seon.maintenance.request/agent You are agent root in namespace my.agents.root. Your opening is generated from live facts. You have 3 unread messages. 97 turns remain in this episode. This run exists because of [:seon.cluster.message/id "maintenance-error/maintenance-receipt/[\"root/maintenance/process-census\" #inst \"2026-08-14T11:05:00.000-00:00\"]-your-run"].
Injected callables: help — Read the calling agent's live situation. dir — List the public names in namespace-name through Clojure's REPL macro. doc — Print documentation for symbol through Clojure's REPL macro.
Every run ends with my.run/complete or my.run/wait; an undisposed run is unfinished work.,
  :seon.maintenance.request/fire {:db/id 29995, …},
  …}
```

`:seon.maintenance.request/agent` is a REF. Its correct value at that position
is the agent's identity — `{:db/id 29979, :seon.cluster.agent/id "root"}`. What
landed there is 400 characters of second-person teaching text, ending in `.,`
where the map separator had to follow the sentence's own period. The same
splice repeats on `:seon.schedule.task/owner` and on every
`:seon.maintenance.*/handler`. It occurs 45 times across the capture set and
recurs identically in every one of the five maintenance families.

Note what this costs beyond readability: those 45 maps each carry the same
400-character instruction paragraph. The instruction is repeated **more than
thirty times** inside one 91 KB context — a large fraction of the agent's whole
window spent restating one paragraph inside unrelated records.

## Seam 3 — the value floor's map face is not EDN (15 positions)

A second map face exists in the same context, and it is not readable either.
`seon.render.value/attribute-label` (`src/seon/render/value.clj:365-372`) drops
the namespace from a qualified keyword unless the short name collides, and
`components-text` (`src/seon/render/value.clj:393-398`) joins pairs as
`label ": " value` with `", "` — no braces, no keyword colons:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.schedule.fire/id "[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]"])
:seon.schedule.fire/id: "[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]", task: {:db/id 29994, :seon.schedule.task/id "root/maintenance/compact"}, nominal-at: #inst "2026-08-09T03:00:00.000-00:00", observed-at: #inst "2026-08-14T11:24:27.953-00:00", :db/id: 29995
```

The live value is perfectly ordinary data and needed no re-facing at all:

```clojure
{:db/id 29995,
 :seon.schedule.fire/id "[\"root/maintenance/compact\" #inst \"2026-08-09T03:00:00.000-00:00\"]",
 :seon.schedule.fire/nominal-at #inst "2026-08-09T03:00:00.000-00:00",
 :seon.schedule.fire/observed-at #inst "2026-08-14T11:24:27.953-00:00",
 :seon.schedule.fire/task #:db{:id 29994}}
```

Three defects in one face: the namespaces are stripped (`nominal-at` no longer
names `:seon.schedule.fire/nominal-at`, which the agent needs to query it
back), `:db/id:` acquires a second colon, and the enclosing braces are gone.
This also means the capture carries **two different map faces for the same kind
of value** — the EDN-ish one in seam 2 and this one — which is a duplicate
mechanism in the rendering path, not a styling choice.

## Seam 4 — one elision, two representations (6 positions)

In the same context, the same `dir` call renders its elision two ways depending
on which cap fired. Nine namespaces end with an honest elision VALUE:

```text
{:seon.print/face :seon.print/elided, :seon.print/omitted 46, :seon.print/elision-unit :children,
 :seon.render.data/total 69, :seon.render.data/path [], :seon.render.data/next-offset 23,
 :seon.render.profile/id :seon.render.profile/agent, :seon.print/requery-id [:seon.ns/name my.web]}
```

`my.background` alone becomes a QUOTED STRING with an English sentence glued
outside the closing quote:

```text
my.agents.root=> (dir (quote my.background))
"[(ns my.background (:require [my.run :as run] …)) {:seon.fn/sym \"my.background/await\", …must mark a background call with no resu"… 1641 more characters of 3279; requery by [:seon.render.call/id [:seon.render/ai [:seon.ns/name my.background] 2]] at path [] offset 1638 with :seon.render.profile/agent
```

The tail is `seon.print/render-elision-ai` (`src/seon/print.cljc:283-301`);
`seon.db` has a second spelling of the same sentence at `src/seon/db.clj:1666`.
The elision value already carries count, total, path, offset and requery
identity as data — that data is what the other nine entries show. Here the same
facts are re-emitted as English, and the value itself is double-escaped inside
a string, so the agent must un-escape a string to read a vector.

## Seam 5 — instruction prose in result position, and the bleed

The owner asked whether the instruction/teaching units — legitimately prose by
design as declared instruction entities — stay cleanly separable from result
positions. **They do not. They bleed, in both directions.**

Downward, the instruction sits in result position with no marker at all,
shaped exactly like the narration two entries above it:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.cluster.instruction/id :getting-started])
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. …
```

Nothing distinguishes "this is a declared instruction the system means" from
"this is a run summary the renderer invented". Both are bare paragraphs after a
`db/pull`.

Upward, seam 2 puts the agent's own opening instruction — a teaching unit —
inside the map value of `:seon.maintenance.request/agent` in an unrelated
maintenance record, 45 times. Teaching text is now data-position content, and
data is now prose-position content. There is no boundary left to separate.

The one clean separation available today is that the instruction is the ONLY
prose that is prose on purpose. Once seams 1-4 are removed, every remaining
prose block in the context is a declared instruction entity, and marking it
becomes a one-line question (does the entity carry
`:seon.cluster.instruction/id`?) rather than a classification problem.

## The agent's own prose — classified separately, and absent here

The agent's own reply prose is a separate category, currently preserved as `;;`
comment lines, with an open question in
[transcript-renderer-encodes-entries-as-comment-forms](../../../seon/issues/transcript-renderer-encodes-entries-as-comment-forms.md).

**In this capture set it does not appear at all.** The only `;;` lines in all
210 entries are the two INSIDE the `getting-started` instruction's own code
fence:

```text
;; unqualified name — it lands in YOUR namespace
;; the :malli/schema attr-map is what makes it permanent; without it this is scratch
```

The drive's model replies never produced comment-encoded entries because the
turns failed before settling (see the `:seon.instrument/contract-violated`
entries in the same capture). So this audit can neither confirm nor refute the
comment-encoding subclass from live evidence, and says so rather than inferring
it. It remains open on its own note and needs its own live drive.

## The rip-out list

One line per seam: what it must return instead.

| # | seam | file:line | returns today | must return |
|---|---|---|---|---|
| 1 | `project-node*` producer substitution at depth 0 | `src/seon/render.clj:445-495` | the family's English summary in place of the queried value | the queried value as data; the `/ai` producer is for context BLOCKS, never for a value in result position |
| 1a | run family `/ai` | `src/seon/cluster/run.clj:1913-1966` | `"Run X, opened …. It completed."` | the run's pulled attributes; disposition is `:seon.cluster.run/closed-at` presence, already a fact |
| 1b | form family `/ai` | `src/seon/cluster/run.clj:1978-1983` | `"Form N: <source>"` | the exact submitted source (filed) |
| 1c | stale-var steering | `src/seon/problems.clj:434-438` | `"Restart the JVM to remove stale loaded Var …"` | the `:seon.fn` row when it exists; a flat `:seon.error` VALUE naming the stale symbol when it does not — never an instruction in place of a row |
| 1d | message family `/ai` | `src/seon/cluster/message.clj:460-471` | `"From outside this cluster to root: …"` | the message map; `:seon.cluster.message/content` is already a string, so it quotes itself |
| 1e | error family `/ai` | `src/seon/error.clj:604-627` | `"… nothing was retried. Signature: …"` | the `:seon.error` value; `seon.error/diagnostic` already constructs it |
| 1f | cluster / config `/ai` | `src/seon/cluster.clj:155-168` | `"Cluster default. Configuration …"` | the pulled cluster and config attributes |
| 2 | text sink emits `/ai` fragments raw | `src/seon/print.cljc:107-112` | an unquoted sentence at map-value depth, breaking the whole value | never splice `/ai` below the root: nested positions take the `:seon.render/form` projection, or the identity ref, or `pr-str` — a fragment inside a data structure is a quoted string or it is a bug |
| 3 | value floor's map face | `src/seon/render/value.clj:365-372, 393-398` | `nominal-at: #inst …, :db/id: 29995` with no braces | readable EDN with qualified keys through the one `seon.print/fit` owner; delete the second map face |
| 4 | elision as an English tail | `src/seon/print.cljc:283-301`, `src/seon/db.clj:1666` | `… 1641 more characters of 3279; requery by …` | the elision VALUE that the other nine entries already show — one representation, always data |
| 5 | instruction entity in result position | declared instruction producer | a bare paragraph indistinguishable from narration | prose that is data is a quoted string; the instruction is the one legitimate prose block and should be the only unquoted one |

Order of value: seam 2 first (it is unfiled, it corrupts results that are
otherwise correct, and it is the narrowest change), then seam 1 (largest count,
largest information loss), then 3 and 4 (single owners, mechanical).

## Filed

New this audit:

- [prose-renders-splice-unquoted-into-printed-data](../../../seon/issues/prose-renders-splice-unquoted-into-printed-data.md)
- [an-entity-pull-returns-a-sentence-instead-of-its-attributes](../../../seon/issues/an-entity-pull-returns-a-sentence-instead-of-its-attributes.md)
- [the-value-floors-map-face-is-not-readable-edn](../../../seon/issues/the-value-floors-map-face-is-not-readable-edn.md)
- [one-elision-has-two-representations-in-one-context](../../../seon/issues/one-elision-has-two-representations-in-one-context.md)

Corroborated with live capture evidence, not duplicated:
[run-renderer-narrates-forms-and-receipts](../../../seon/issues/run-renderer-narrates-forms-and-receipts.md),
[a-run-history-entry-can-name-a-different-run-than-its-form-pulled](../../../seon/issues/a-run-history-entry-can-name-a-different-run-than-its-form-pulled.md),
[transcript-renderer-encodes-entries-as-comment-forms](../../../seon/issues/transcript-renderer-encodes-entries-as-comment-forms.md),
[effect-context-suffix-returns-comment-notices](../../../seon/issues/effect-context-suffix-returns-comment-notices.md)
(no `;;`-prefixed effect notice appears in this capture set — the drive's turns
never reached a background effect, so that note stands unchanged on its own
evidence).
