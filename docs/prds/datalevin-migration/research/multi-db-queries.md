---
type: research
status: draft
tags: [prd, research, database]
---
# Datalevin Multi-Database Query Research

## Summary

Datalevin supports querying across multiple databases using the `:in $ $2 $3 ...` syntax. This is exactly what we need for the agent isolation architecture where each agent has its own database but we need cross-database views in the Observatory.

## Test Results

### Basic Cross-Database Join (PASS)

Query entities from DB1, join with entities in DB2 via a shared key:

```clojure
;; DB1: Users with names
;; DB2: Orders with user-id references

(d/q '[:find ?name ?amount
       :in $ $2
       :where
       [$ ?u :user/name ?name]
       [$2 ?o :order/user-id ?uid]
       [$2 ?o :order/amount ?amount]
       [(= ?u ?uid)]]
     @conn1 @conn2)

;; => #{["Alice" 100.0] ["Alice" 250.0] ["Charlie" 300.0] ["Bob" 75.0]}
```

### String-Key Joins - Our Use Case (PASS)

Sessions in one DB, messages in another, joined by session-id string:

```clojure
(d/q '[:find ?ns ?role ?content
       :in $sessions $msgs
       :where
       [$sessions ?s :session/id ?sid]
       [$sessions ?s :session/namespace ?ns]
       [$msgs ?m :message/session-id ?sid]
       [$msgs ?m :message/role ?role]
       [$msgs ?m :message/content ?content]]
     @conn-sessions @conn-messages)

;; => #{["seon.health" "user" "Start health check"]
;;      ["seon.trading" "assistant" "Hi there"]
;;      ["seon.trading" "user" "Hello"]}
```

### Three-Database Queries (PASS)

Can query across 3+ databases in a single query:

```clojure
(d/q '[:find ?ns ?role ?content ?cost
       :in $sessions $msgs $meta
       :where
       [$sessions ?s :session/id ?sid]
       [$sessions ?s :session/namespace ?ns]
       [$msgs ?m :message/session-id ?sid]
       [$msgs ?m :message/role ?role]
       [$msgs ?m :message/content ?content]
       [$meta ?mt :meta/session-id ?sid]
       [$meta ?mt :meta/cost-usd ?cost]]
     @conn-sessions @conn-messages @conn-metadata)

;; => #{["seon.trading" "assistant" "Hi there" 0.15]
;;      ["seon.health" "user" "Start health check" 0.08]
;;      ["seon.trading" "user" "Hello" 0.15]}
```

### Aggregations Across Databases (PASS)

```clojure
(d/q '[:find ?ns (count ?m)
       :in $ $msgs
       :where
       [$ ?s :session/id ?sid]
       [$ ?s :session/namespace ?ns]
       [$msgs ?m :message/session-id ?sid]]
     @conn-sessions @conn-messages)

;; => (["seon.trading" 2] ["seon.health" 1])
```

### Different Schemas (PASS)

Each database can have completely different schemas. They are truly independent:

```clojure
;; DB1 schema
{:user/name {:db/valueType :db.type/string}
 :user/email {:db/valueType :db.type/string}}

;; DB2 schema
{:order/amount {:db/valueType :db.type/double}
 :order/user-id {:db/valueType :db.type/long}}
```

### Join Behavior (Inner Join)

Cross-database queries behave as inner joins. Entities without matches in the other database are excluded:

```clojure
;; Session "ghi3" exists but has no messages
;; Query does NOT return it (inner join behavior)
(d/q '[:find ?ns ?content
       :in $ $msgs
       :where
       [$ ?s :session/id ?sid]
       [$ ?s :session/namespace ?ns]
       [$msgs ?m :message/session-id ?sid]
       [$msgs ?m :message/content ?content]]
     @conn-sessions @conn-messages)

;; => Only returns sessions that HAVE messages
```

### or-join Across Databases (PASS with caveats)

Can use `or-join` but must ensure same free vars in all branches:

```clojure
;; FAILS - different free vars
(or [$msgs ?m :message/session-id ?sid]
    [$sessions ?s :session/namespace "seon.orphan"])

;; WORKS - use or-join with explicit var binding
(or-join [?sid]
  [$msgs ?m :message/session-id ?sid]
  (and [$sessions ?s2 :session/id ?sid]
       [$sessions ?s2 :session/namespace "seon.orphan"]))
```

## Performance

Tested with 1000+ messages across 3 sessions:

| Operation | Time |
|-----------|------|
| Bulk insert 1000 messages | 137ms |
| Cross-DB query (1003 results) | 13ms |

Performance is excellent for our use case. The query optimizer handles cross-database joins efficiently.

## Syntax Reference

### Database Binding

```clojure
;; In :in clause, $ is default, $2, $3, etc for additional DBs
:in $ $2 $3

;; Can use descriptive names
:in $sessions $messages $metadata
```

### Referencing Databases in Patterns

```clojure
;; Explicit database prefix
[$sessions ?s :session/id ?sid]
[$messages ?m :message/session-id ?sid]

;; $ is default (first database)
[?e :attr ?v]  ;; equivalent to [$ ?e :attr ?v]
```

### Cross-Database Predicates

```clojure
;; Join on shared value
[$db1 ?e1 :attr1 ?shared]
[$db2 ?e2 :attr2 ?shared]

;; Join with explicit equality
[$db1 ?e1 :id ?id1]
[$db2 ?e2 :ref ?id2]
[(= ?id1 ?id2)]
```

## Limitations Discovered

1. **Inner join only** - No built-in left/outer join. Must handle missing data explicitly.

2. **or-join restrictions** - All branches must use the same free variables.

3. **No cross-DB refs** - Cannot use `:db.type/ref` across databases. Must use value equality.

4. **Query planning** - Each database is scanned independently; optimizer doesn't have cross-DB statistics.

## Implications for Seon

### Architecture Decision: CONFIRMED

Multi-database queries work well for our use case:

1. **Agent isolation** - Each agent gets its own Datalevin database
2. **Shared orchestrator view** - Observatory can query across all agent DBs
3. **Session-based linking** - Use `session-id` string as the join key

### Recommended Pattern

```clojure
;; Each agent DB has messages
{:message/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
 :message/session-id {:db/valueType :db.type/string}
 :message/role {:db/valueType :db.type/string}
 :message/content {:db/valueType :db.type/string}}

;; Orchestrator DB has session registry
{:session/id {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :session/namespace {:db/valueType :db.type/string}
 :session/status {:db/valueType :db.type/keyword}
 :session/agent-db-path {:db/valueType :db.type/string}}

;; Cross-query for Observatory
(d/q '[:find ?ns ?status (count ?m)
       :in $registry $agent1 $agent2
       :where
       [$registry ?s :session/id ?sid]
       [$registry ?s :session/namespace ?ns]
       [$registry ?s :session/status ?status]
       (or-join [?sid ?m]
         [$agent1 ?m :message/session-id ?sid]
         [$agent2 ?m :message/session-id ?sid])]
     @orchestrator-db @agent1-db @agent2-db)
```

## Test Code Location

Full test code available in REPL history. Key patterns:

```clojure
(require '[datalevin.core :as d])

;; Create connections
(def conn1 (d/create-conn "path/to/db1" schema1))
(def conn2 (d/create-conn "path/to/db2" schema2))

;; Cross-database query
(d/q '[:find ?x ?y
       :in $db1 $db2
       :where
       [$db1 ?e1 :attr1 ?shared]
       [$db2 ?e2 :attr2 ?shared]
       [$db1 ?e1 :data ?x]
       [$db2 ?e2 :data ?y]]
     @conn1 @conn2)

;; Always close when done
(d/close conn1)
(d/close conn2)
```
