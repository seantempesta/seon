---
type: landing
status: complete
created: 2026-09-23
tags: [agent-platform, datahike, retention, error]
---

# Fault-occurrence no-history retention

## Outcome

The fault-occurrence churn attributes alone now request Datahike no-history
storage:

- `:seon.error.occurrence/count`
- `:seon.error.occurrence/last-at`

`first-at` and every other occurrence attribute retain ordinary history. The
declarations live in `resources/seon/schemas/seon.error.occurrence.edn`; the
assignment's `seon.error.edn` path does not own these attributes in the current
tree. The existing bridge still maps `:seon.db/no-history?` to
`:db/noHistory true` at `src/seon/schema/datahike.clj:180` after `6ec971b07`.
The declaration hunks landed with the indexer partition sweep in commit
`8a069b5e4`; this lane's clean-HEAD scratch boot below is their installed-schema
evidence. This lane therefore commits only its regression and landing record.

The ruled literal spelling `[:inst {:seon.db/no-history? true}]` is not a valid
Malli form in the installed registry. The first focused run refused it as
`:malli.core/infinitely-expanding-schema`. The loadable equivalent is
`[:and {:seon.db/no-history? true} :inst]`, matching the existing scalar
property convention. `count` uses the ruled form
`[:int {:min 1 :seon.db/no-history? true}]`.

## Focused regression

Command:

```text
bin/test-fast --paths resources/seon/schemas/seon.error.occurrence.edn test/seon/fault_no_history_test.clj -- seon.fault-no-history-test
```

Snapshot: `tmp/test-runs/run.arAh2J`. Recorded run `d8b95bd2f6f3`:
1 executed, 0 unchanged, 4 assertions, 0 failures, 0 errors. Contracts were
armed for 1,695 registered Vars and 1,688 program-armable Vars. The canonical
fixture installs the production bridge's derived `count` declaration on its
isolated branch because the published fixture base necessarily retains the old
immutable Datahike schema until reset. It then proves:

- the second write leaves exactly one current `count` datom, value 2;
- the history view contains only `[2 true]` for `count`, with neither the old
  assertion nor its retraction;
- sibling `first-at`, which lacks the property, retains both its old assertion
  and retraction.

An earlier run `2cadd48307d4` intentionally exposed the old fixture schema: its
history still held `[1 true]` and `[1 false]`. That red result is superseded by
the explicit derived-schema installation and the from-zero proof below.

The required post-landing HEAD run used HEAD `f4fb846e5` and only the owned test
path:

```text
bin/test-fast --paths test/seon/fault_no_history_test.clj -- seon.fault-no-history-test
```

Run `d7eaa664f1eb` reached the named test with contracts armed, but the body did
not enter: canonical fixture construction refused `:my.note/note` because the
published overlay graph `d73e0a6c` was 39 commits behind HEAD and lacked the
partition facts landed in `8a069b5e4`. HEAD's `my.note.edn` does declare
`:seon.program/partition :seon.data`; this is foreign stale-base evidence, not a
fault-retention assertion failure. A simultaneous foreign syntax error in
`test/seon/cluster/reload_per_declaration_test.clj:77` blocked the ordinary shell
hook after that run. Per the lane boundary, neither foreign file was edited and
the earlier green focused run plus the clean from-zero installed-schema proof
remain the completed evidence for this slice.

## From-zero installed-schema proof

The first shared-tree attempt used exactly:

```text
bin/seon --root tmp/no-history-root reset --force
```

It was blocked during indexing by foreign in-flight changes:

```text
test/seon/dev/hook_test.clj:90:21 invalid-arity seon.operator/prepl-value!
test/seon/program_test.clj:1108:30 unresolved-namespace clojure.set
```

No exact-root JVM remained. Per the lane rule, proof continued from clean HEAD
`295c03f6a` in `tmp/fault-no-history-wt`, with only this lane's resource and
test changes and a link to the maintained `reference-code` checkout.

The clean snapshot reset completed from zero with PID 78026, source commit id
`6ab2da98-9ba0-5159-9296-e756abe896ea`, no missing layers or problems, and
`:seon.boot/ready-ms 180675`. A subsequent held start used PID 83172 and reached
ready in 20,867 ms so the read-only probe could complete before the shell
released the child.

Exact read-only PREPL form:

```clojure
(do
  (require '[seon.cluster :as cluster]
           '[seon.cluster.boot :as boot]
           '[seon.db :as db])
  (cluster/project-next-prepl-value! {:seon.dev.mcp/read-only? true})
  (let [connection (boot/connection "default")]
    (->> (:schema (db/schema-database (db/db connection)))
         (filter (fn [[ident declaration]]
                   (and (qualified-keyword? ident)
                        (= "seon.error.occurrence" (namespace ident))
                        (true? (:db/noHistory declaration)))))
         (into (sorted-map)))))
```

Exact projected result:

```clojure
#:seon.dev.mcp
{:value
 #:seon.error.occurrence
 {:count
  #:db{:cardinality :db.cardinality/one,
       :ident :seon.error.occurrence/count,
       :noHistory true,
       :valueType :db.type/long},
  :last-at
  #:db{:cardinality :db.cardinality/one,
       :ident :seon.error.occurrence/last-at,
       :noHistory true,
       :valueType :db.type/instant}},
 :windowed? false}
```

The result proves exactly two matching installed attributes. A preliminary
Datalog query returned `[]` because Datahike schema properties live in the
database schema map rather than ordinary schema datoms; it is superseded by the
installed-schema query above.

## Operational boundary

`default` was not reset or adopted. **RESET NEEDED for `default`**, owned by the
orchestrator's reset batch. MCP `runtime_status` and `eval_clj` were unavailable
in this session; `bin/seon status`, the exact-root PREPL, and the installed
database schema supplied the evidence instead. No platform or cold gate ran.
