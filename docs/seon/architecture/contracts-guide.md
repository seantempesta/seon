---
type: architecture
status: active
created: 2026-10-09
tags: [architecture, contracts, malli, performance, tasks]
---

# Function contracts guide

A function contract is the Malli function schema in a `defn`'s `:malli/schema` metadata. Seon admits it
(`seon.schema/function-contract`), stores it as `:seon.fn/spec`, instruments it on every call (`seon.instrument/apply!`
in the JVM, `seon.sci.arm/arm!` in an agent's SCI context) and fills omitted evaluation members by schema name
(`seon.call-preparation`). An instrumented contract runs on **every** call, so it is code on the hot path: it must check
the thing the boundary is for, and nothing more expensive than that. This guide is what every contract task
(`seon.contracts/task`) points at.

## 1. What a complete contract is

- `[:=> [:cat input ...] output]`, or `[:function [:=> ...] [:=> ...]]` for several arities. Every input and the
  output, private functions included.
- Inputs and outputs named by **registered schemas** (`:seon.db/database-value`, `:seon.fn/sym`,
  `:seon.task/declaration`), never a registered key's schema restated inline. A small one-off structural shape
  may stay inline; a repeated one gets one registered name in the owner's `resources/seon/schemas/<ns>.edn`.
- No `:any` or `:some` (except a registered polymorphic boundary carrying its exemption and reason), no
  `[:maybe ...]`, no bare `:map`, no anonymous `[:fn (fn ...)]` (a registered predicate instead). Absence is no
  key: an optional map entry, never a nil value. `seon.fn/contract-findings` enumerates exactly these defects
  (`:seon.fn.contract/finding`); `seon.contracts/gaps` answers them for one function.
- **Error variants.** A function whose job is to answer (a `my.*` tool, a validator, a gate) returns its error map
  and lists those variants in its output union. Readers return refusals until the errors spec's S-reads is committed, so
  a function passing a read's refusal through declares it, as `seon.contracts/gaps` does:
  `[:or [:vector ...] :seon.error/value :seon.db/invalid-read-error :seon.schema/validation-refusal]`. A function
  that throws declares only its success value; the edit hook refuses an uncontained throw it can see
  (`error/uncontained-throw`) and names the variant to add.

## 2. Cheap and to the point

Ask first: **what is this check for?** Then use the cheapest schema that checks that.

### 2.1 Check a value by its kind, not by walking its contents

`:seon.db/database-value` is `[:fn seon.db/database-value?]`, which calls Datahike's own `db?`. On default's
store (563,472 datoms, 2026-10-09) the instrumented check costs 0.01 ms and 168 bytes, independent of the store's size.

- Bad: an input contract that proves the database is "valid" by validating datoms, pulled entities or a query
  result over it. That is a scan of the store on every call, and the writer already validated every entity when
  it was written (reads never re-apply admission).
- Good: `[:=> [:cat :seon.db/database-value :seon.fn/sym] ...]`. The question "is this a database?" is the one
  the boundary needs; the body's reads answer the rest.

The same holds for connections (`:seon.db/connection`), SCI contexts and other live host objects: one registered
predicate of the object's kind.

### 2.2 Pass references, check references

A function that takes entities its caller pulled from the database should take their identities
(`[:sequential :seon.db/entity-id]`, `:seon.fn/sym`, an identity key), not the whole pulled maps under their full
entity schema. The identity is checked in constant time per element; the entity was checked by the writer.

### 2.3 A large collection: its shape, not a deep walk on a hot path

`seon.fn/contract-findings` returns `:seon.fn.contract/report`, a `[:vector :seon.fn.contract/finding-row]`; over
default's 4,509 findings validating that output costs 17 ms and 44 MB (2026-10-09). On a report called once per
review that is 3% of its 510 ms; on a per-evaluation path it would be the cost. When a collection is large and
its function hot, ask whether the collection should cross the boundary at all (pass the database and let the
callee read; return a handle) before choosing a cheaper element schema. Never replace the element schema with
`:map` or `:any`: that is a weaker contract, not a cheaper one.

### 2.4 A tiny predicate called per element is checked once, at the collection

Seon instruments every contracted function, so a small predicate called once per element pays its input checks once
per element. `regenerate-agent-context!` called an instrumented `own-binding?` (whose contract checked `ctx?` and
`sci-var?`) for each of 7,891 Vars on every fork: 53,013 instrumented calls for `(+ 1 2)`. The fix (86659ca5b) kept
every contract and changed the structure: `seon.sci.eval/own-bindings` reads the bindings in one pass, so the
context is checked once (5,812 calls). When a contract makes a loop expensive, move the check to the boundary
that holds the whole collection; do not delete or weaken the contract.

### 2.5 Contract the meaning, not the incidental container

`seon.fn/gating-tests` declares `[:sequential :qualified-symbol]` and refused `#{seon.ref/section}` from a REPL
probe (2026-10-09). When the body only iterates, ask whether a set is as meaningful as a vector to every caller
before declaring one container; when it is, accept both (`[:or [:set X] [:sequential X]]`). When order or
duplicates matter, the narrow container is the meaning: keep it.

### 2.6 The check must cost less than the body

Measure before and after with `seon.profile/measure` on a representative call: wall ms, allocation and instrumented
calls. A contract that makes its function slower than its body's own work is wrong work, whatever it checks.

## 3. Finding the right schema

1. Read the producer: the output contract of the function that supplies the argument
   (`(my.program/source 'ns/producer)`), and the consumer's use of it (`(my.program/callers 'ns/f)`; its tests by
   `seon.fn/gating-tests`).
2. Search the registry before adding a name. In an agent's context:
   `(->> (seon.db/db) seon.db/carried-projection :seon.schema.projection/registry malli.registry/schemas keys
   (filter #(= "seon.db" (namespace %))) sort)`; the declarations live in `resources/seon/schemas/<ns>.edn`.
3. Find a keyword's consumers: `(seon.fn/functions-using (seon.db/db) :seon.db/database-value)`, or
   `(my.program/search :seon.db/database-value)` for every source that names it.
4. Try the contract on real values at the REPL: call the function on its callers' inputs and validate with the
   instrumented contract before writing it back.

## 4. A refused real value is a finding

When a newly instrumented contract refuses a real value (a reaching test, a live call, the agent's probe), it found
something. Decide with evidence:

- **The contract misread the value's real shape** (for example a Datahike `:find ?a ?b` answers a set, not a
  vector; `seon.contracts/partitioned` was first declared `[:sequential ...]` and its instrumented contract refused the
  set on the first probe). Fix the contract and cite the observed shape.
- **The producer emits a bad value.** Keep the tight contract. File a task on the producer with the exact form, the
  refused value's essence and the Malli explain, naming the contracted function in its `:seon.task/must-read`
  (that is how `seon.contracts/findings` counts the batch's bug yield), and say so in the reply.

Never widen to `:any`, `[:maybe ...]` or `:map` to go green. Root reviews every loosened contract at merge.

**A nil input is the caller's defect.** When a function's callers pass nil for an input, do not contract nil
(`[:maybe X]`, `[:or X :nil]`, a nilable registered name): absence is no key. Change the caller so it does not
pass nil, or make the function take a map whose key is optional. The generated intent says this for every input
(the call evidence is not read). If you do need a nil literal in a schema, `[:= nil]` is not valid Malli (nil is read
as the properties slot): write `[:= {} nil]` or `:nil`.

## 5. Generating a contract task

`seon.contracts/task` turns any uncontracted function into an assignable task declaration: name
`contract-<name>` in the function's namespace, a short typed intent naming the function and this guide, must-read
the function and `seon.contracts/gaps`. Nothing is copied into the intent: the resolver pulls the function's doc,
source, callers and tests into the agent's context from the backticked symbol. Create it with
`seon.task/create!` or add it to `resources/seon/tasks/<ns>.edn`.

`seon.contracts/report` is the bulk query: the uncontracted source functions (test roots, macros, constants and
value defs excluded) partitioned into `eligible` (used, ranked by calling functions, tests and references),
`host-bound` (SCI cannot interpret them: a Claude lane changes them through the check), `unused` (no call or
reference edge: deletion candidates for root) and `named-by-task` (an open task already names them; skipped so two
tasks never edit one function). Schema resources name generator and predicate functions (`:gen/gen`, `[:fn ...]`)
without a call edge, so an `unused` entry is a candidate to verify, not a verdict.

## 6. Proof and the case lifecycle

- **The proof** of a contract task is its case in `seon.contract-coverage-test`: a one-line deftest tagged with the
  task (`^{:seon/tasks #{'ns/contract-name}}`) asserting `(contracts/gaps (db/db) 'ns/name)` is `[]` in the
  executing test var's SCI context, so it turns green on the agent's branch once its `defn` carries a complete contract.
  Its reaching tests must stay green.
- **Cost.** A contract that makes a reaching test slower is a regression. The COST-REG card
  (`docs/seon/schedule/cards/COST-REG.md`) turns a reaching test that got more expensive red; until it is committed, the
  agent measures its function's representative call before and after (§2.6) and reports the numbers.
- **Cases are temporary.** Root deletes a case when its task merges or retires; the table and its untagged
  `a-contracted-function-has-no-gaps` case stay. The lasting guard is merge acceptance:
  `seon.cluster.source/accept-merge!` refuses a merge carrying a function with no `:seon.fn/spec` (macros and
  host-bound declarations excepted). It does not yet refuse an incomplete contract (`:any`, `[:maybe ...]`, bare
  `:map`); `seon.contracts/gaps` and the review do.
