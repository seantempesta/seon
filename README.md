# seon

**Infrastructure for AI agents to write reliable software.**

Not a framework. Not a library. A codebase architecture where agents can discover functions by their contracts, learn from history, own code long-term, and compose safely.

The personal domains (trading, health, finance) are test cases. The infrastructure is the product.

## Status

Research project by [Sean Tempesta](https://github.com/seantempesta). Public for prior-art purposes and as a reference for related work. Direction may shift unilaterally as the underlying research evolves; treat the API as unstable.

For the project thesis and capability set, start at [`docs/seon/vision/index.md`](docs/seon/vision/index.md).

## Lineage

This project consolidated ~18 months of experiments documented in predecessor
repositories. Each is private and RFC 3161 timestamped via FreeTSA on
2026-04-21, anchoring the project's lineage for prior-art purposes.

**Chronological predecessors:**

| Repo | Period | Significance |
|---|---|---|
| `seon-2024-10-xtdb-biff` | Oct 2024 | First XTDB+Biff exploration; introduced the `seon.repl` namespace pattern. |
| `seon-2024-10-kit-migration` | Oct 2024 – Jan 2025 | Concurrent Kit framework experiment; 45 commits exploring agentic-runtime ideas with CLJS/Reagent. |
| `seon-2025-02-architecture` | Feb – Mar 2025 | Primary architectural realization; ~72 KB README documents namespace-as-process, code-graph, schema-discovery, REPL-pipeline, and multi-agent isolation appearing together for the first time. |
| `seon-2025-11-trading-domain` | Nov – Dec 2025 | Immediate git ancestor; became this repo via `git mv` on 2025-12-13. |

This repo's first commit (2025-12-13) was a copy of `ml-options-trading`,
since published as `seon-2025-11-trading-domain`. The architectural patterns
it implements trace back through `seon-2025-02-architecture` to the October
2024 XTDB+Biff and Kit migration experiments.

## License

Released under [AGPL-3.0](LICENSE). Mere unmodified use as a dependency does not trigger AGPL §13's network-copyleft provision; modification + network deployment does.

**Commercial / non-AGPL licensing is available** for users whose deployment doesn't fit AGPL terms — contact Sean Tempesta Consulting LLC.

**Existing engagements with separate written agreements** (e.g., consulting clients with a Statement of Work referencing seon as pre-existing IP) operate under those agreements, not AGPL. The public license is the default; bilateral agreements override it.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). All contributions are accepted under AGPL-3.0 plus an inbound-license clause that grants Sean Tempesta Consulting LLC the right to relicense — required to preserve dual-licensing optionality.

## Contact

For licensing inquiries, partnership questions, or anything that doesn't fit the issue tracker: sean.tempesta@gmail.com.
