---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, render]
---

# References: `:seon.db/query`, `:seon.db/query-args`, `:seon.db/invalid-read-error`

Kind: registered schema keys, named in `seon.db/q`'s contract and in the corrected task text. Declared AI render:
`:seon.schema/schema` → `seon.render.schema/render-ai`. The three were separate calls.

```clojure
(defn schema-unit [k] {:seon.render/value (seon.db/pull dbh '[*] [:seon.schema/key k]) :seon.db/db dbh :seon.render/distance 1})
(seon.render.schema/render-ai (schema-unit :seon.db/query))
(seon.render.schema/render-ai (schema-unit :seon.db/query-args))
(seon.render.schema/render-ai (schema-unit :seon.db/invalid-read-error))
```

Replies as the agent sees them (65, 31 and 32 ms; 364, 361 and 373 armed calls):

```clojure
#:seon.repl{:value {:seon.schema/key :seon.db/query,
 :seon.schema.usage/referenced-by [:seon.db/query-args],
 :seon.schema.usage/referenced-by-count 1,
 :seon.schema.usage/contract-count 5,
 :seon.schema/definition [:or [:vector :seon.schema/value] :map :string],
 :seon.schema.usage/contracts
   [seon.db/arity-mismatches-with seon.db/call-edges
    seon.db/declared-arity-bounds seon.db/q seon.db/storage-q-in]}, :result result/e947f958b6dc1, :ms 65, :calls 364}
#:seon.repl{:value {:seon.schema/key :seon.db/query-args,
 :seon.schema.usage/referenced-by [:seon.db/read-request],
 :seon.schema.usage/referenced-by-count 1,
 :seon.schema.usage/contracts [seon.db/q],
 :seon.schema.usage/contract-count 1,
 :seon.schema/definition
   [:map
    [:query :seon.db/query]
    [:args {:optional true} [:sequential :seon.schema/value]]
    [:request-id {:optional true} :string]
    [:limit {:optional true} :int]
    [:offset {:optional true} :int]
    [:max-work {:optional true} [:int {:min 1}]]
    [:max-results {:optional true} [:int {:min 1}]]
    [:max-result-weight {:optional true} [:int {:min 1}]]]}, :result result/ef0ea56d81eaf, :ms 31, :calls 361}
#:seon.repl{:value {:seon.schema/key :seon.db/invalid-read-error,
 :seon.schema.usage/referenced-by-count 3,
 :seon.schema.usage/contract-count 76,
 :seon.schema/definition
   [:and
    :seon.error/base
    [:map
     {:error/message
        "must carry the :seon.db/invalid-read distinguishing member"}
     [:seon.db/invalid-read :seon.db/invalid-read]
     [:seon.db/refused-read-operation :seon.db/refused-read-operation]
     [:seon.error/message :seon.error/message]]],
 :seon.schema.usage/referenced-by
   [:seon.db.read/unreadable-declarations-error :seon.render.walk/cut
    :seon.test/host-error],
 :seon.schema.usage/contracts
   [my.task/held seon.ai/agent-overlay seon.ai/agent-setting-attributes
    seon.background/await seon.background/poll
    seon.cluster.host/agent-entity seon.cluster.host/retire!
    seon.cluster.prompt/calibration-for]}, :result result/e2ca1c4db47aa, :ms 32, :calls 373}
```

## What is good

- **The best of the renders.** The definition is data and readable, and the usage is useful (who references it, which
  contracts use it).

## What is wrong

- **Key order follows the hash map**: the definition, the one thing always needed, lands in the middle or last.
- **`:seon.schema.usage/contracts`** for `:seon.db/invalid-read-error` lists 8 of 76 functions starting at `my.task/held`;
  that is noise for a task about `seon.db/q`. The count is enough.
- **No `:description`** shows for these keys because none is declared. That is fine; when one is declared it shows
  inside the definition's properties.

## What the render should do

- Key first, definition second, then counts. Lists only when short (≤ 5) or asked for.
