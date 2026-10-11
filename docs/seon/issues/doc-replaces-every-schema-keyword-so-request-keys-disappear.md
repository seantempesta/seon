---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, sci, dogfood, malli]
---

# `doc` replaces every schema keyword in a contract, so request keys disappear and supplied members are not removed

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP. This is design
D9 (repl-entry-renders-design §0), which had not been filed. The cause is now verified at the
source.

## Observed (default archive 04f3a159b, branch `dogfood-repl-opus-11`, 2026-09-26)

`(:in (doc my.issue/add!))`, the only way to learn this function's request (its `:example` is `""`),
answered, in abridged form:

```clojure
[:cat
 [:map
  [[:fn {:error/message "must be a Datahike connection object, live or released", …} seon.db/connection-object?]
   [:fn {:error/message "must be a Datahike connection object, live or released", …} seon.db/connection-object?]]
  [[:string {:min 1, :seon.db/identity true, :seon.program/row-schema :seon.agent/agent, …}]
   [:string {:min 1, :seon.db/identity true, :seon.program/row-schema :seon.agent/agent, …}]]
  [[:string {:min 1, :seon.db/index true}] [:string {:min 1, :seon.db/index true}]]
  [[:string {:min 1, :description "The Problem section, …"}] [:string {…}]]
  [[:enum :blocker :friction :cleanup] [:enum :blocker :friction :cleanup]]
  …]]
```

- Every entry prints as `[<schema form> <schema form>]`. The **keys** (`:seon.issue/title`,
  `:seon.issue/problem`, `:seon.issue/severity`, …) are gone, so the request cannot be written from
  `doc`.
- `:seon.db/connection` and `:seon.agent/id` are still listed, although `:supplied [:seon.agent/id
  :seon.db/connection]` says the caller omits them.
- The same shape appeared for `my.issue/status` (`[[:string {…}] [:string {…}]]`, where the key
  `:seon.issue/id` is lost).

## Cause (verified in source)

`seon.sci.eval/agent-documentation-contract` (`src/seon/sci/eval.clj:2234-2263`) expands the
contract with `(walk/postwalk-replace (into {} (documentation-schemas database row)) contract)`.
`postwalk-replace` substitutes **every occurrence** of a referenced keyword. That includes the entry
key in `[:seon.issue/title :seon.issue/title]`, and not only the value position. The supplied-member
removal that follows tests `(keys (first %))` on each entry. After the replacement the first element
is a schema form, not the keyword, so nothing is removed.

## Which model is wrong

A hand-written tree walk over Malli *forms*, treating keywords as text to substitute, stands in for
Malli's own structure. Malli knows which position is a map key and which is a schema reference
(`m/entries`, `m/walk`, `m/deref`). Expansion, if it is wanted at all, belongs to the value position
of each entry, through `m/walk`. The simpler design keeps the contract's `m/form` as authored,
with its keys, removes the supplied entries by key, and lets each referenced schema render once
beside it, as `dir`'s `:schemas` map already does.

## Expected

`(:in (doc my.issue/add!))` shows `[:map [:seon.issue/title …] [:seon.issue/problem …] [:seon.issue/severity
[:enum :blocker :friction :cleanup]] …]`, without `:seon.db/connection` or `:seon.agent/id`.

## Regression

For a `my.*` function with a supplied connection and a required identity key, `doc`'s `:in` names
every non-supplied key and no supplied one.

## Re-verified open 2026-09-26 (dogfood-repl-opus-2, cycle 10, archive 6ccf12a5a)

`(doc my.issue/add!)` still prints every `:in` entry as `[<schema form> <schema form>]`, `:example ""`, and still lists the
supplied `:seon.db/connection` and `:seon.agent/id` inside `:in` beside a new `:supplied [:seon.agent/id :seon.db/connection]`.
`(dir my.program)` shows the same doubled entries in `request-merge!`'s `:in`. Building `my.issue/add!`'s request took
three attempts: `:seon.issue/functions #{'sym}` refused with sixteen alternative lines (int, string, tuple, map per member);
lookup refs `[:seon.fn/sym 'sym]` were needed, which nothing printed.
