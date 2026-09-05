---
type: issue
status: resolved
severity: friction
tags: [issue, sci, render]
---

# The elision marker tells an agent nothing about what it lost

Found by the bootstrap-vector design lane (2026-08-01, live probes on a
scratch cluster): a capped collection renders into agent context as a
bare `:seon.sci.admit/elided` marker with no retained/total count and
no receipt identity — a model cannot tell whether it lost three
elements or 300,000, and has no handle to page into the remainder.

This contradicts the sealed print contract's own standard (ruling #26:
cut REASONS are part of the grammar) and the honesty bar already met
elsewhere (the MCP envelope carries retained/total; the routed /data
window pages with an explicit elision link). The one general printer
should give the agent the same evidence: what was cut, how much
remained of how many, and the identity through which the full value is
reachable (the receipt/blob digest where one exists).

Acceptance: an agent-facing elision names retained/total (or total
unknown, stated) and, where the full value survives as a blob or
receipt, the identity to reach it; the bootstrap vector's guardrail
beat can then SHOW an elision that teaches rather than confuses; a
regression covers the capped-collection face in both sinks.

## Data-session dogfood, 2026-08-04

Scratch cluster `codex-repl-dogfood-0804`, through MCP `eval_clj` in
`door` mode:

```clojure
(seon.db/transact!
 (mapv (fn [i]
         {:seon.test.run/id (str "dogfood-bulk-" i)
          :seon.test.run/at #inst "2026-08-04T21:00:00.000-00:00"
          :seon.test.run/git-sha
          "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
       (range 10000)))
```

The transaction committed after 9.648 seconds, but its complete agent face was
only `...`; it carried no transaction ID, datom count, success statement, or
retrieval identity. The evaluation record said `:seon.sci.admit/capped? true`.

The corresponding 10,000-row read:

```clojure
(->> (seon.db/q
      '[:find [?id ...]
        :where [?e :seon.test.run/id ?id]])
     (filter (fn [id]
               (clojure.string/starts-with? id "dogfood-bulk-")))
     sort
     vec)
```

showed 32 IDs followed by `...`. MCP retained digest
`cd01a815b718e3895951127d1bc5b2299d6a24ad2ac2776fa48bd491db58cc9a`,
but drilling that value reported total `8193`: the 8,192 admitted IDs plus the
marker, not the original 10,000. The remaining 1,808 IDs were not reachable
through the digest. They were recoverable only by independently knowing to
rerun the query with deterministic `:order-by`, `:offset`, and `:limit`; the
face supplied none of the count or continuation information needed to do so.

## Resolution, 2026-08-04

Commits `e34eea186`, `aaaaf856b`, and `e35e7b27f` replace every marker emitted
by the generic projection walk with a declared elision value carrying omitted
count, knowable total, path, next offset, profile identity, and either a stable
requery identity or an explicit refusal. AI and HTML sinks consume the same
node. A regression asserts both faces and their digest identity.

Fresh SCI evaluation proof on isolated root `tmp/universal-floor-live-0804` rendered a
100,000-element vector as 32 retained children plus 99,968 omitted of 100,000,
with next offset 32 and blob digest
`f09029ee10a50fbde2ea1fb3459502f769df6b8779a95f7158fc6c7c4f793f38`.
