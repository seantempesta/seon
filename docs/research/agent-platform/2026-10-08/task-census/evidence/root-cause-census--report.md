# Root-cause census (lane root-cause-census, 2026-09-28)

Summary. The full text is in root's transcript.

| # | Root cause | Deletable lines (est.) | Bugs today |
|---|---|---|---|
| RC1 | Errors as values with no public predicate or combinator | 300–390 | ~10 commits |
| RC3 | World / custody / db confusion (worlds holding a db; custody fence; 12 projection-fallback callers) | 60–90 | 7 commits |
| RC2 | SCI write observers bound on the caller's thread | 20–30 | 4 commits |
| RC9 | Hand-rolled release-and-rethrow (addSuppressed) | 40–60 | 2 commits |
| RC5 | Contracts vs source arities or reads | ~40 | 6 commits |

## Guards and patches found

- 44 `(contains? x :seon.error/at)` sites; 15 three-key checks; 17 `debt:` comments.
- 4 private error predicates, each a different shape.
- 304 catch; 24 catch-nil swallows; ~10 release-and-rethrow sites.

## Lane plan

- **L1:** one public `error/observation?` plus `error/then`; narrow `db/error-result`.
- **L2:** observers scoped to the evaluation (observe.clj); no unbinding elsewhere.
- **L3:** the world schema refuses `:seon.db/db`; fallback callers return db's refusal.
- **L4:** `with-release`.
- **L5:** an arity and contract check in `bin/seon check`.
- **L6:** housekeeping:
  - closed tags `e13e5a9c557f` (kernel.clj:803) and `cea47b015b23` (schema.clj:20);
  - dangling tags: cluster.clj:1597 (aca1b25fa16a, 50a6a9afcb64, 52875b443ef0), cluster.clj:1884 (431c6e828256), runner.clj:1774 (80aa14c43291), accretion.clj:42 (0dfdcc8e447e), render/web.clj:3193 (0be9b4c92c56);
  - `walk.clj:344 join-membership` is dead (23 lines).
