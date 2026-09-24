---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, database, query, datahike, upstream-delta]
---

# Datahike's AVET seek casts a bound value of another type

Seen 2026-09-24 by lane fix-datahike-var-attr on the fork at `620721d6`.
When a ground attribute's value position is bound to a value whose type
differs from the attribute's stored values, the planned executor's AVET
point seek (`probe-driven-iterable`,
`reference-code/datahike/src/datahike/query/execute.cljc`, chosen by
`scan-datoms` when `K * 2500 < N`) compares the alien value with stored
values and throws `ClassCastException`. The join semantics, and the filter
regime of the same function, match nothing.

Reproduction (fork test JVM, `clojure -M:test`):

```clojure
(def dbv (d/db-with (db/empty-db {:sym {:db/unique :db.unique/identity}})
                    (into [{:db/id -1 :holder "not-a-symbol"}]
                          (map (fn [i] {:sym (symbol "a" (str "f" i))}) (range 5000)))))
(d/q '[:find ?t :in $ [?s ...] :where [?t :sym ?s]] dbv ["str"])
;; => ClassCastException: Symbol cannot be cast to String
(d/q '[:find ?t :where [?h :holder ?s] [?t :sym ?s]] dbv)
;; => the same
```

Seon keeps the `[(symbol? ?symbol)]` guard in the fourth
`seon.fn/declared-reference-rules` rule for this reason: an invoked
attribute may hold an EDN string, and it must not reach the `:seon.fn/sym`
index seek.

Wanted: a seek keeps only probe values that satisfy the attribute's declared
`:db/valueType` (`datahike.schema/value-valid?`, `schema.cljc:228`), which
cannot match anything else, and uses the filter regime when no type is
declared. The same applies to `var-attr-avet-pairs`.
