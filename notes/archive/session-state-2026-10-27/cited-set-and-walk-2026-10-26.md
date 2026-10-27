# The cited set and the walk, on the ten requests (audit items 2 and 3)

Measured on the worktree the-page-cites-the-fix-not-the-repro's own root (main c25169839c plus to-prove's fix; 5,313 vectors), the ten seon.fixtures/requests as tasks. Each true site is from the audit's table.

## What the plan cites today (ref/references over the plan, its kind)

| request | cited | site cited? |
|---|---|---|
| related-list | test-db (test ns), datahike.api/db-with (lib), seon.docs/load-tx (program, setup), "docs/flow.md" (a string in the repro: a section), related-view | yes |
| message-eval | 8 schema keywords only; `#'seon.context/printed-run` not cited at all | no |
| help | my.agent/help | yes |
| mixed | result/e1 (no var), "docs/render.md" (a string: a section), print-value | no |
| namespaced | print-value, 3 schema keywords, clojure.string/split-lines (lib) | no |
| long-string | print-value, result/e1 | no |
| bytes-str | bytes-str | yes |
| first-sentence | lead-sentence | yes (wrapper) |
| cut-char | cut-words | yes |
| star-name | seon.ref/names | no |

## Where the site ranks (in the page = cited, or related's five)

| request | cited | related (main) | callee walk from cited program vars, depth 3 | walk inside the cited var's namespace | rrf(walk, related) top 5 |
|---|---|---|---|---|---|
| related-list | yes | – | – | – | – |
| message-eval | – | 1 | (nothing cited) | – | 1 |
| help | yes | – | – | – | – |
| mixed | – | 2 | – (22 walked) | – | 3 |
| namespaced | – | – | – (22) | – | – |
| long-string | – | – | 20 | – | – |
| bytes-str | yes | – | – | – | – |
| first-sentence | yes | 3 | 1 | 1 | 1 |
| cut-char | yes | – | – | – | – |
| star-name | – | – | 17 | 8 | – |

- The page holds the site for 7 of 10 today.
- The walk finds none of the three misses within five lines: emit-collection and member-indent are not within three hops of print-value, member-rank and allot rank 20, trim-word 8 to 17.
- Leaving plumbing out by rarity also cuts the chain (seon.ref/parse is named by 16+ forms).
