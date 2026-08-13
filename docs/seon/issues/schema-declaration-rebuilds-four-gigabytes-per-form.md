---
type: issue
status: open
severity: friction
tags: [issue, schema, sci, performance, class/p1, wave/schema-projection-performance]
---

# Stop rebuilding gigabytes of schema state for one declaration

## Problem

A single valid agent schema declaration rebuilds the complete schema
projection inside the guarded evaluation. At the current registry size it
allocates about 4.65 GB and takes about 0.9 seconds even though the returned
value is one keyword.

Schema declaration is the first step of ordinary data work. Paying this cost
for every new attribute makes the taught schema-first workflow expensive and
amplifies one agent's declaration across the co-hosted process heap.

## Evidence

Scratch cluster `codex-repl-dogfood-0804`, MCP `eval_clj`, `door` mode:

```clojure
(seon.schema/register! :my.dogfood/score [:int {:min 0 :max 100}])
```

returned the clean face `:my.dogfood/score`, but its evaluation record was:

```clojure
{:seon.eval/duration-ms 919
 :seon.eval/allocated-bytes 4652159248
 :seon.eval/outcome :ok}
```

An independent second declaration reproduced the same class:

```clojure
(seon.schema/register! :my.dogfood/label [:string {:min 1}])
;; duration-ms 907, allocated-bytes 4652146872
```

`src/seon/sci/eval.clj:1363-1411` builds an isolated registration delta and
validates it with `schema/projection-with-schema`.
`src/seon/schema.clj:1774-1786` implements that operation by calling the whole
`build-projection`. The contracted-function sibling is already tracked in
[[contracted-defn-rebuilds-the-whole-schema-projection]]; this note owns the
separately reproduced schema-declaration path.

## Owner

`seon.schema` owns incremental projection construction; `seon.sci.eval` owns
using it once inside the declaration boundary.

## Acceptance

- Adding one schema validates only the new declaration and the dependency
  closure it changes; unrelated registry forms are not rebuilt.
- Doubling unrelated registry forms does not double one declaration's work.
- The two exact door probes retain the same admitted keyword result and schema
  refusal semantics without gigabyte-scale allocation.
