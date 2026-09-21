---
type: research
status: draft
created: 2026-09-21
---

# `reference-code/` usage audit — every vendored submodule, derived

Owner question (2026-09-21): *"we can probably unvendor a bunch of repos that
we only briefly looked at and are not using. audit that too."* and *"The
architecture should cover the system at a high level including references to
reference-code so agents can easily find reference code to hold in context."*

Read-only pass. No JVM, no `bin/test`, no `bin/seon`, no submodule was
modified, no network fetch was made. The decision to unvendor is the owner's;
this note supplies only derived facts.

## Method — every column is a command

- inventory: `git submodule status` (109 rows) and
  `git config -f .gitmodules --get-regexp '^submodule\..*\.(path|url)$'`
  (109 paths, 109 urls — they agree exactly);
- fork: URL under `github.com/seantempesta`;
- gitlink: `git -C reference-code/<x> log -1 --format='%h %ad' --date=short`;
  every one of the 109 is checked out and readable (no "not checked out" row);
- disk: `du -sh reference-code/<x>`;
- deps.edn: `grep -nE 'reference-code/<x>' deps.edn` → the line number, or `no`;
- the three citation counts: `grep -rhoE 'reference-code/[A-Za-z0-9._-]+'` over
  (a) `src bin script resources config`, (b)
  `AGENTS.md .agents/skills docs/seon/architecture docs/prds/agent-platform`,
  (c) `docs` minus (b). Counting the **path form** `reference-code/<name>` is
  what makes a citation a citation: it is the form that sends an agent to the
  vendored bytes. A prose mention of a project's bare name without the path is
  not counted, and the audit says so rather than guessing.
- **One file is excluded from (c) and must be**:
  `docs/prds/steward-platform/research/one-jvm-input-digest-facts-2026-09-23.edn`
  is a single-line machine dump of gate input paths that names all 109
  submodules. Counting it made every submodule look cited; excluding it, only
  35 names appear anywhere in `docs/` prose.
- gitlink churn: `git log -1 --format='%h %ad' --date=short -- reference-code/<x>`
  and `git log --oneline -- reference-code/<x> | wc -l`;
- language: top-level `deps.edn`/`project.clj` → Clojure, `package.json` → JS/TS,
  `pyproject.toml`/`setup.py`/`requirements.txt` → Python, `pom.xml`/`build.gradle`
  → Java, `Cargo.toml` → Rust, else other.

Classification, derived not judged:

- **RUNTIME** — named by `deps.edn` (`:local/root`);
- **READ** (read-for-design) — not in `deps.edn`, but cited by `AGENTS.md`, a
  skill, `docs/seon/architecture/`, `docs/prds/agent-platform/`, or by `src/`;
- **HISTORY** — cited only elsewhere under `docs/` (archive, steward-platform,
  context-generation, issues). History does not justify keeping the bytes;
- **UNREF** — no `reference-code/<name>` citation anywhere in the checkout.

## Table

Columns: name · upstream (github.com/ elided) · our fork? · gitlink commit+date ·
disk · deps.edn line · src/bin/script/resources/config cites · design cites ·
other-docs cites · last gitlink change · times changed · language · class.

| name | upstream | fork | gitlink | disk | deps | code | design | docs | last gitlink change | n | lang | class |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `ADAS` | ShengranHu/ADAS | no | 2702bee 2025-01-27 | 25M | no | 0 | 0 | 0 | 7c2c845ef 2026-06-29 | 1 | Python | UNREF |
| `SimpleMem` | aiming-lab/SimpleMem | no | 60a48e8 2026-06-23 | 81M | no | 0 | 0 | 0 | 7c2c845ef 2026-06-29 | 1 | Python | UNREF |
| `Voyager` | MineDojo/Voyager | no | 55e45a8 2023-07-27 | 6.4M | no | 0 | 0 | 0 | 7c2c845ef 2026-06-29 | 1 | Python | UNREF |
| `aero` | juxt/aero | no | c47a10f 2020-02-16 | 164K | no | 0 | 0 | 0 | 00d381b13 2026-07-01 | 2 | Clojure | UNREF |
| `again` | liwp/again | no | 0b4f195 2026-06-25 | 192K | no | 0 | 0 | 0 | 56ccc23f4 2026-06-28 | 1 | Clojure | UNREF |
| `aider-polyglot` | Aider-AI/polyglot-benchmark | no | 7e0611e 2024-12-22 | 31M | no | 0 | 0 | 0 | 184adeb15 2026-06-26 | 1 | other | UNREF |
| `anthropic-sdk-typescript` | anthropics/anthropic-sdk-typescript | no | fbee0d1 2026-06-15 | 4.8M | no | 0 | 0 | 0 | 2bfadeaf6 2026-06-16 | 1 | JS/TS | UNREF |
| `babashka` | babashka/babashka | no | 0fb349c4 2026-04-20 | 5.9M | no | 0 | 0 | 1 | 8e27662c8 2026-07-18 | 1 | Clojure | RUNTIME |
| `babashka-process` | babashka/process | no | 16a84e0 2025-12-21 | 744K | 86 | 0 | 0 | 5 | 8e5ab7704 2026-01-09 | 1 | Clojure | RUNTIME |
| `bd3lms` | kuleshov-group/bd3lms | no | 1c3e8f4 2025-07-10 | 1.9M | no | 0 | 0 | 0 | 690ae2b81 2026-06-27 | 1 | Python | UNREF |
| `browsecomp-plus` | texttron/BrowseComp-Plus | no | 0469490 2026-05-28 | 4.2M | no | 0 | 0 | 0 | 7ad53b22d 2026-07-12 | 1 | Python | UNREF |
| `browsergym` | ServiceNow/BrowserGym | no | 9e779f0 2026-03-17 | 3.1M | no | 0 | 0 | 0 | 184adeb15 2026-06-26 | 1 | Python | UNREF |
| `bun` | seantempesta/bun | yes | d8ecf098 2026-07-18 | 15G | no | 0 | 0 | 0 | 42de07211 2026-07-18 | 2 | JS/TS | UNREF |
| `cheerio` | cheeriojs/cheerio | no | d7ac7a9 2026-07-21 | 1.9M | no | 0 | 0 | 0 | b1ac99a95 2026-07-21 | 1 | JS/TS | UNREF |
| `cider-nrepl` | clojure-emacs/cider-nrepl | no | 08a7334 2026-04-19 | 1.2M | no | 0 | 0 | 0 | 4a7d73eb5 2026-06-17 | 1 | Clojure | UNREF |
| `claude-agent-sdk-demos` | anthropics/claude-agent-sdk-demos | no | bd3afda 2026-01-08 | 6.9M | no | 0 | 0 | 0 | 8e5ab7704 2026-01-09 | 1 | other | UNREF |
| `claude-agent-sdk-typescript` | seantempesta/claude-agent-sdk-typescript | yes | 6084a9d 2026-01-19 | 7.0M | no | 0 | 0 | 0 | 809166f8a 2026-01-20 | 2 | other | UNREF |
| `claude-code-lsps` | boostvolt/claude-code-lsps | no | 3cd3fea 2026-01-18 | 468K | no | 0 | 0 | 0 | 50ac52b8d 2026-02-21 | 1 | other | UNREF |
| `clj-kondo` | seantempesta/clj-kondo | yes | 57252e07 2026-07-30 | 11M | 17 | 0 | 6 | 42 | 995ccec92 2026-07-30 | 3 | Clojure | RUNTIME |
| `clj-reload` | tonsky/clj-reload | no | 61c6fa7 2025-09-15 | 368K | no | 0 | 3 | 0 | 8a38de9ac 2026-01-04 | 1 | Clojure | READ |
| `clojure` | clojure/clojure | no | b18d3adc 2026-07-20 | 29M | no | 0 | 8 | 40 | b1ac99a95 2026-07-21 | 1 | Java | READ |
| `clojure-mcp` | bhauman/clojure-mcp | no | d801e2f 2026-06-17 | 1.8M | no | 0 | 0 | 0 | 5daeb297e 2026-06-18 | 3 | Clojure | UNREF |
| `clojurescript` | clojure/clojurescript | no | 946d75f 2026-05-29 | 6.4M | no | 0 | 4 | 0 | 4a7d73eb5 2026-06-17 | 1 | Clojure | READ |
| `commit0` | commit-0/commit0 | no | 1123db3 2026-02-24 | 29M | no | 0 | 0 | 0 | 184adeb15 2026-06-26 | 1 | Python | UNREF |
| `core.async` | clojure/core.async | no | dc35f3e 2026-06-04 | 1.6M | no | 2 | 19 | 120 | 84583d04b 2026-07-26 | 2 | Clojure | READ |
| `core.async.flow-monitor` | seantempesta/core.async.flow-monitor | yes | fbff842 2026-07-29 | 1.8M | 147 | 0 | 0 | 0 | 62ce76559 2026-07-29 | 3 | Clojure | RUNTIME |
| `cron-utils` | jmrozanec/cron-utils | no | a3d31f7 2023-03-26 | 2.7M | no | 0 | 0 | 0 | fa0095a26 2026-08-05 | 1 | Java | UNREF |
| `cybench` | andyzorigin/cybench | no | 1097a72 2026-07-08 | 1.9G | no | 0 | 0 | 0 | f869a20fe 2026-07-12 | 2 | Python | UNREF |
| `cytoscape` | cytoscape/cytoscape.js | no | a4de13e0 2024-11-25 | 25M | no | 0 | 0 | 15 | 70ea7a53c 2026-09-06 | 1 | JS/TS | HISTORY |
| `datahike` | seantempesta/datahike | yes | 006e634a 2026-09-20 | 8.2M | 26 | 26 | 79 | 630 | d480242c1 2026-09-20 | 77 | Clojure | RUNTIME |
| `datahike-lmdb` | replikativ/datahike-lmdb | no | bc9f024 2026-04-06 | 76K | no | 0 | 0 | 1 | 54ed3bba4 2026-05-17 | 3 | Clojure | HISTORY |
| `datalog-parser` | replikativ/datalog-parser | no | 08a32d8 2026-03-14 | 260K | no | 0 | 0 | 9 | 703a21ff6 2026-07-19 | 1 | Clojure | HISTORY |
| `datastar` | starfederation/datastar | no | bb9ed6fb 2026-01-02 | 1.9M | no | 0 | 0 | 7 | 8090461e3 2026-01-20 | 1 | other | HISTORY |
| `datastar-clojure` | starfederation/datastar-clojure | no | 1cef624 2025-12-26 | 760K | no | 0 | 3 | 5 | be7625548 2026-01-30 | 1 | Clojure | RUNTIME |
| `deepswe` | datacurve-ai/deep-swe | no | 6db64a4 2026-07-10 | 32M | no | 0 | 0 | 0 | 7ad53b22d 2026-07-12 | 1 | other | UNREF |
| `ds4` | antirez/ds4 | no | 54b36ed 2026-07-28 | 89M | no | 0 | 0 | 0 | 34de64d5d 2026-08-01 | 1 | other | UNREF |
| `edamame` | borkdude/edamame | no | 63373df 2026-09-15 | 944K | 50 | 0 | 0 | 8 | a24ad11b7 2026-09-15 | 2 | Clojure | RUNTIME |
| `editscript` | juji-io/editscript | no | b493ccf 2026-01-18 | 328K | 14 | 0 | 0 | 5 | d693e3f75 2026-08-13 | 1 | Clojure | RUNTIME |
| `expectations` | clojure-expectations/expectations | no | 4bd9357 2023-04-11 | 156K | no | 0 | 0 | 0 | c81692cfe 2026-03-06 | 1 | Clojure | UNREF |
| `flash` | runpod/flash | no | f7cfc47 2026-06-24 | 4.2M | no | 0 | 0 | 0 | 4da034c7d 2026-06-28 | 1 | Python | UNREF |
| `flash-examples` | runpod/flash-examples | no | 2b45929 2026-06-04 | 560K | no | 0 | 0 | 0 | 4da034c7d 2026-06-28 | 1 | Python | UNREF |
| `funsearch` | google-deepmind/funsearch | no | cc53f27 2024-02-05 | 20M | no | 0 | 0 | 0 | 7c2c845ef 2026-06-29 | 1 | other | UNREF |
| `gemini-mcp` | RLabs-Inc/gemini-mcp | no | 9b56760 2025-08-23 | 176K | no | 0 | 0 | 0 | 3a9036cd2 2025-12-28 | 1 | JS/TS | UNREF |
| `generative_agents` | joonspk-research/generative_agents | no | fe05a71d 2023-08-11 | 1.1G | no | 0 | 0 | 0 | 7c2c845ef 2026-06-29 | 1 | Python | UNREF |
| `gorilla-bfcl` | ShishirPatil/gorilla | no | 6ea5797 2026-03-23 | 180M | no | 0 | 0 | 0 | 184adeb15 2026-06-26 | 1 | other | UNREF |
| `hato` | gnarroway/hato | no | 8c80539 2024-06-29 | 180K | no | 0 | 0 | 0 | 67c44e937 2026-08-03 | 1 | Clojure | UNREF |
| `hermes-agent` | nousresearch/hermes-agent | no | a3abeb595 2026-05-25 | 79M | no | 0 | 0 | 0 | 31e31cbe5 2026-05-25 | 1 | JS/TS | UNREF |
| `http-kit` | seantempesta/http-kit | yes | 238a85c 2026-07-29 | 7.4M | 106 | 0 | 2 | 2 | 875353668 2026-07-29 | 2 | Clojure | RUNTIME |
| `hyperlith` | andersmurphy/hyperlith | no | b08a8e8 2026-06-19 | 584K | no | 0 | 5 | 4 | e7c88a788 2026-06-26 | 2 | Clojure | READ |
| `inspect-ai` | UKGovernmentBEIS/inspect_ai | no | 8ebc782ec 2026-07-20 | 88M | no | 0 | 0 | 0 | 1033821a7 2026-08-02 | 3 | Python | UNREF |
| `inspect-evals` | UKGovernmentBEIS/inspect_evals | no | 97c99f5 2026-07-02 | 188M | no | 0 | 0 | 0 | f869a20fe 2026-07-12 | 2 | Python | UNREF |
| `integrant` | weavejester/integrant | no | bcad6bc 2026-01-07 | 152K | no | 0 | 0 | 0 | 218a2518e 2026-07-24 | 3 | Clojure | UNREF |
| `integrant-repl` | weavejester/integrant-repl | no | 76457ff 2026-03-27 | 44K | no | 0 | 0 | 0 | 01effefc2 2026-08-03 | 1 | Clojure | UNREF |
| `java-genai` | googleapis/java-genai | no | fd3713ba47e 2026-06-17 | 34M | no | 0 | 0 | 0 | d227b792e 2026-06-18 | 1 | Java | UNREF |
| `js-genai` | googleapis/js-genai | no | b751112ef 2026-06-17 | 14M | no | 0 | 0 | 0 | d227b792e 2026-06-18 | 1 | JS/TS | UNREF |
| `jsoup` | jhy/jsoup | no | ac28afe6 2026-04-20 | 7.4M | no | 0 | 0 | 0 | 67c44e937 2026-08-03 | 1 | Java | UNREF |
| `kaocha` | lambdaisland/kaocha | no | 8846f91 2025-10-09 | 1.4M | no | 0 | 2 | 0 | e33f4905e 2026-02-21 | 1 | Clojure | READ |
| `konserve` | seantempesta/konserve | yes | 07377c2 2026-08-05 | 58M | no | 0 | 7 | 25 | 164750ba7 2026-08-05 | 5 | Clojure | READ |
| `konserve-lmdb` | replikativ/konserve-lmdb | no | b747ee4 2026-04-06 | 168K | no | 0 | 0 | 1 | 72e6dc202 2026-04-19 | 1 | Clojure | HISTORY |
| `langchain4clj` | nandoolle/langchain4clj | no | 889f9e6 2026-04-07 | 1.4M | no | 0 | 1 | 0 | 412572bff 2026-08-03 | 1 | Clojure | READ |
| `letta` | letta-ai/letta | no | 6d8cb7fd4 2026-06-25 | 25M | no | 0 | 0 | 0 | 7c2c845ef 2026-06-29 | 1 | Python | UNREF |
| `litellm-clj` | unravel-team/litellm-clj | no | 14bcdd9 2026-06-17 | 936K | no | 0 | 0 | 0 | 16f25fa1e 2026-07-23 | 1 | Clojure | UNREF |
| `malli` | seantempesta/malli | yes | 606083c5 2026-09-19 | 4.7M | 15 | 2 | 21 | 169 | 4806aad03 2026-09-19 | 4 | Clojure | RUNTIME |
| `malli-datomic` | Blasterai/malli-datomic | no | 6d9109f 2021-11-25 | 1.9M | no | 0 | 0 | 1 | 048241a56 2026-01-28 | 1 | Clojure | HISTORY |
| `mammoth.js` | mwilliamson/mammoth.js | no | 1a495a9 2026-05-24 | 1.4M | no | 0 | 0 | 0 | b1ac99a95 2026-07-21 | 1 | JS/TS | UNREF |
| `mem0` | mem0ai/mem0 | no | b2ff3aed 2026-06-29 | 51M | no | 0 | 0 | 0 | 7c2c845ef 2026-06-29 | 1 | Python | UNREF |
| `mle-bench` | openai/mle-bench | no | 507f92e 2026-04-24 | 42M | no | 0 | 0 | 0 | 184adeb15 2026-06-26 | 1 | Python | UNREF |
| `mvm` | tinylabscom/mvm | no | 1eea128d 2026-06-25 | 27M | no | 0 | 0 | 0 | 092352336 2026-06-26 | 1 | Rust | UNREF |
| `needle` | cactus-compute/needle | no | ffb1c51 2026-07-01 | 1.7M | no | 0 | 0 | 0 | afbb4e5f3 2026-07-12 | 1 | Python | UNREF |
| `nippy` | taoensso/nippy | no | 40b3dcc 2025-10-21 | 380K | no | 0 | 0 | 0 | c982feb14 2026-03-05 | 1 | Clojure | UNREF |
| `nrepl` | nrepl/nrepl | no | 0e75a27 2026-01-03 | 2.1M | no | 0 | 0 | 0 | 5e71590d8 2026-01-04 | 1 | Clojure | UNREF |
| `obsidian-linter` | platers/obsidian-linter | no | e8fb229 2026-03-04 | 4.9M | no | 0 | 0 | 0 | 2c5db39d8 2026-03-11 | 1 | JS/TS | UNREF |
| `openai-node` | openai/openai-node | no | 6f849f4f 2026-06-03 | 8.0M | no | 0 | 0 | 0 | 2bfadeaf6 2026-06-16 | 1 | JS/TS | UNREF |
| `openclaw` | openclaw/openclaw | no | 8f80e2a46 2026-02-20 | 63M | no | 0 | 0 | 0 | 10b139712 2026-02-20 | 1 | JS/TS | UNREF |
| `openevolve` | algorithmicsuperintelligence/openevolve | no | 80945ed 2026-03-18 | 9.0M | no | 0 | 0 | 0 | 7c2c845ef 2026-06-29 | 1 | Python | UNREF |
| `orchard` | clojure-emacs/orchard | no | c462a25 2026-05-20 | 2.4M | no | 0 | 0 | 2 | 4a7d73eb5 2026-06-17 | 1 | Clojure | HISTORY |
| `osworld` | xlang-ai/OSWorld | no | 7a17d3a 2026-07-06 | 22M | no | 0 | 0 | 0 | f869a20fe 2026-07-12 | 2 | Python | UNREF |
| `partial-cps` | seantempesta/partial-cps | yes | 1e119b0 2026-06-30 | 276K | no | 0 | 0 | 0 | 6bd334086 2026-07-15 | 1 | Clojure | UNREF |
| `pdf.js` | mozilla/pdf.js | no | 028c02f 2026-07-20 | 253M | no | 0 | 0 | 0 | b1ac99a95 2026-07-21 | 1 | JS/TS | UNREF |
| `persistent-sorted-set` | replikativ/persistent-sorted-set | no | e1a17bb 2026-07-10 | 1.3M | no | 0 | 0 | 9 | 6bd334086 2026-07-15 | 1 | Clojure | HISTORY |
| `piscina` | piscinajs/piscina | no | 23a6c2e 2026-06-22 | 2.5M | no | 0 | 0 | 0 | f30d9f028 2026-06-25 | 1 | JS/TS | UNREF |
| `playwright` | microsoft/playwright | no | a7b4d5e 2026-07-21 | 108M | no | 0 | 0 | 0 | b1ac99a95 2026-07-21 | 1 | JS/TS | UNREF |
| `posh` | mpdairy/posh | no | 2347c85 2019-10-21 | 216K | no | 0 | 0 | 3 | 18e8f420f 2026-06-03 | 1 | Clojure | HISTORY |
| `proximum` | seantempesta/proximum | yes | 9846d3e 2026-07-15 | 1.8M | no | 0 | 0 | 0 | a20560d0f 2026-07-15 | 2 | Clojure | UNREF |
| `re-bench` | METR/RE-Bench | no | 93b9806 2025-01-30 | 2.2M | no | 0 | 0 | 0 | 184adeb15 2026-06-26 | 1 | other | UNREF |
| `reitit` | metosin/reitit | no | 106fc4c7 2026-03-10 | 7.2M | no | 0 | 0 | 1 | 92550a084 2026-06-27 | 1 | Clojure | HISTORY |
| `reveal` | vlaaad/reveal | no | 911b7b6 2025-10-06 | 11M | no | 0 | 0 | 0 | 809166f8a 2026-01-20 | 1 | Clojure | UNREF |
| `rewrite-clj` | clj-commons/rewrite-clj | no | 60782e5 2026-01-20 | 3.2M | 92 | 0 | 0 | 1 | ffff034d4 2026-02-01 | 1 | Clojure | RUNTIME |
| `runpod-python` | runpod/runpod-python | no | 6c2cd64 2026-06-24 | 2.4M | no | 0 | 0 | 0 | 4da034c7d 2026-06-28 | 1 | Python | UNREF |
| `sci` | seantempesta/sci | yes | fcbd8862 2026-08-11 | 25M | 48 | 8 | 24 | 246 | 9623a26d6 2026-08-11 | 19 | Clojure | RUNTIME |
| `shadow-cljs` | seantempesta/shadow-cljs | yes | c98bf60f 2026-07-19 | 61M | no | 0 | 0 | 0 | 6cf70cc7b 2026-07-19 | 5 | Clojure | UNREF |
| `sharp` | lovell/sharp | no | 686f4586 2026-07-21 | 50M | no | 0 | 0 | 0 | b1ac99a95 2026-07-21 | 1 | JS/TS | UNREF |
| `sheetjs` | git.sheetjs.com/sheetjs/sheetjs | no | d2f2e17 2026-02-09 | 241M | no | 0 | 0 | 0 | b1ac99a95 2026-07-21 | 1 | JS/TS | UNREF |
| `spectomic` | Provisdom/spectomic | no | 94407d3 2022-04-03 | 84K | no | 0 | 0 | 1 | 048241a56 2026-01-28 | 1 | Clojure | HISTORY |
| `superv.async` | seantempesta/superv.async | yes | 3e6ed75 2026-05-20 | 296K | no | 0 | 0 | 0 | 6bd334086 2026-07-15 | 1 | Clojure | UNREF |
| `swe-agent` | SWE-agent/SWE-agent | no | 1132b3e 2026-07-07 | 35M | no | 0 | 0 | 0 | f869a20fe 2026-07-12 | 2 | Python | UNREF |
| `swe-bench` | SWE-bench/SWE-bench | no | f7bbbb2 2026-03-19 | 5.6M | no | 0 | 0 | 0 | 184adeb15 2026-06-26 | 1 | Python | UNREF |
| `tau2-bench` | sierra-research/tau2-bench | no | 1901a30 2026-07-01 | 777M | no | 0 | 0 | 0 | f869a20fe 2026-07-12 | 2 | Python | UNREF |
| `terminal-bench` | laude-institute/terminal-bench | no | d28711d 2026-07-10 | 171M | no | 0 | 0 | 0 | a3a303ccb 2026-07-12 | 2 | Python | UNREF |
| `test.check` | clojure/test.check | no | 5ba3a25 2025-12-30 | 684K | no | 0 | 0 | 2 | c81692cfe 2026-03-06 | 1 | Clojure | HISTORY |
| `timbre` | taoensso/timbre | no | b72cc65 2024-02-26 | 368K | no | 0 | 0 | 1 | 51f280460 2026-07-20 | 1 | Clojure | HISTORY |
| `tinypool` | tinylibs/tinypool | no | abc247f 2026-01-03 | 796K | no | 0 | 0 | 0 | f30d9f028 2026-06-25 | 1 | JS/TS | UNREF |
| `tools.deps` | clojure/tools.deps | no | 820a5e5 2026-07-02 | 1.3M | no | 0 | 0 | 0 | b1ac99a95 2026-07-21 | 1 | Clojure | UNREF |
| `transformers` | huggingface/transformers | no | e7b5b964e6 2026-06-10 | 91M | no | 0 | 0 | 0 | 2d2f18010 2026-06-28 | 2 | Python | UNREF |
| `transit-clj` | cognitect/transit-clj | no | 8d2d217 2026-05-29 | 284K | no | 0 | 0 | 1 | 13627b8e8 2026-06-24 | 1 | Clojure | HISTORY |
| `transit-cljs` | cognitect/transit-cljs | no | 3d8a2c4 2024-07-15 | 208K | no | 0 | 0 | 0 | 13627b8e8 2026-06-24 | 1 | Clojure | UNREF |
| `transit-js` | cognitect/transit-js | no | 9f58010 2024-07-16 | 912K | no | 0 | 0 | 0 | 13627b8e8 2026-06-24 | 1 | JS/TS | UNREF |
| `vllm` | vllm-project/vllm | no | 311ad689a 2026-06-29 | 100M | no | 0 | 0 | 1 | aa1141ddc 2026-06-28 | 1 | Python | HISTORY |
| `webarena` | web-arena-x/webarena | no | dce0468 2025-11-26 | 7.4M | no | 0 | 0 | 0 | 184adeb15 2026-06-26 | 1 | Python | UNREF |

## 1. Totals per class

| class | submodules | disk |
|---|---|---|
| RUNTIME (in `deps.edn`) | 12 | 0.07 GB |
| READ-FOR-DESIGN | 8 | 0.10 GB |
| HISTORY-ONLY | 15 | 0.14 GB |
| UNREFERENCED | 74 | 20.96 GB |
| **total** | **109** | **21.27 GB** |

`du -sh` values were parsed to MB for the sums (K/M/G suffixes); the sum is
therefore accurate to the rounding `du -sh` itself prints.

## 2. Unvendor candidates — facts only

**UNREFERENCED (74, 20.96 GB)**, largest first: bun (15G, FORK), cybench
(1.9G), generative_agents (1.1G), tau2-bench (777M), pdf.js (253M), sheetjs
(241M), inspect-evals (188M), gorilla-bfcl (180M), terminal-bench (171M),
playwright (108M), transformers (91M), ds4 (89M), inspect-ai (88M), SimpleMem
(81M), hermes-agent (79M), openclaw (63M), shadow-cljs (61M, FORK), mem0
(51M), sharp (50M), mle-bench (42M), swe-agent (35M), java-genai (34M),
deepswe (32M), aider-polyglot (31M), commit0 (29M), mvm (27M), letta (25M),
ADAS (25M), osworld (22M), funsearch (20M), js-genai (14M), reveal (11M),
openevolve (9.0M), openai-node (8.0M), webarena (7.4M), jsoup (7.4M),
claude-agent-sdk-typescript (7.0M, FORK), claude-agent-sdk-demos (6.9M),
Voyager (6.4M), swe-bench (5.6M), obsidian-linter (4.9M),
anthropic-sdk-typescript (4.8M), flash (4.2M), browsecomp-plus (4.2M),
browsergym (3.1M), cron-utils (2.7M), piscina (2.5M), runpod-python (2.4M),
re-bench (2.2M), nrepl (2.1M), cheerio (1.9M), bd3lms (1.9M), proximum (1.8M,
FORK), clojure-mcp (1.8M), needle (1.7M), mammoth.js (1.4M), tools.deps
(1.3M), cider-nrepl (1.2M), litellm-clj (936K), transit-js (912K), tinypool
(796K), flash-examples (560K), claude-code-lsps (468K), nippy (380K),
superv.async (296K, FORK), partial-cps (276K, FORK), transit-cljs (208K),
again (192K), hato (180K), gemini-mcp (176K), aero (164K), expectations
(156K), integrant (152K), integrant-repl (44K).

**HISTORY-ONLY (15, 0.14 GB)** — cited under `docs/` but never by `deps.edn`,
`src/`, `AGENTS.md`, a skill, the architecture docs, or the agent-platform
packs: vllm (100M, 1), cytoscape (25M, 15), reitit (7.2M, 1), orchard (2.4M,
2), malli-datomic (1.9M, 1), datastar (1.9M, 7), persistent-sorted-set (1.3M,
9), test.check (684K, 2), timbre (368K, 1), transit-clj (284K, 1),
datalog-parser (260K, 9), posh (216K, 3), konserve-lmdb (168K, 1), spectomic
(84K, 1), datahike-lmdb (76K, 1). Counts are the `docs/` citations.

Removing both sets would reclaim **21.10 GB** of the 21.27 GB total; `bun`
alone is 15 GB (71% of everything vendored).

**Forks among the candidates, and whether they carry unpushed work.** Six of
the 74 UNREFERENCED are ours: `bun`, `shadow-cljs`, `claude-agent-sdk-typescript`,
`proximum`, `superv.async`, `partial-cps`. For every one of the 13
`seantempesta` forks, `git -C reference-code/<x> branch -r --contains HEAD`
names at least one remote branch, so **no fork's pinned commit is unpushed**:

| fork | pinned | remote branch containing it |
|---|---|---|
| `bun` | d8ecf098 | `origin/seon` (1 commit ahead of `upstream/main`, pushed) |
| `shadow-cljs` | c98bf60f | `fork/sync-upstream` (0 ahead) |
| `claude-agent-sdk-typescript` | 6084a9d | `origin/seon` |
| `proximum` | 9846d3e | `seon/seon-guarded-force-v126` |
| `superv.async` | 3e6ed75 | `origin/wasm/lazy-watchdog` |
| `partial-cps` | 1e119b0 | `origin/main` + `upstream/main` (identical to upstream) |
| `datahike` | 006e634a | `origin/main` (0 ahead) |
| `konserve` | 07377c2 | `origin/main` (0 ahead) |
| `sci` | fcbd8862 | `fork/seon-env-hook` |
| `malli` | 606083c5 | `fork/seon-ref-scope` |
| `clj-kondo` | 57252e07 | `origin/seon` |
| `http-kit` | 238a85c | `origin/seon-pending-write-state` |
| `core.async.flow-monitor` | fbff842 | `origin/seon` |

`partial-cps` is pinned to a commit that is also `upstream/main`: the fork
carries nothing of ours at this pin.

## 3. RUNTIME and READ-FOR-DESIGN — the set the architecture should point at

**RUNTIME (12)** — the architecture can state these as "we build against the
vendored source", because `deps.edn` resolves them from disk:

| submodule | deps.edn | why it is vendored (from the citation) |
|---|---|---|
| `datahike` | :26 | the database itself; the writer's serial loop, `retractEntity` sweep/cascade, pull's 1,000-member truncation, `as-of`/`since` origin chain — 26 `src/` cites, 79 design cites |
| `sci` | :48 | the agent evaluation engine: `init`/`fork`/`intern`, `:interrupt-fn`, `time-limit`, the call-preparation hook — 8 `src/` cites, 24 design cites |
| `malli` | :15 | the contract/registry layer read as the compiled projection, not re-implemented — 21 design cites |
| `clj-kondo` | :17 | the analyzer behind program-graph publication; its namespace cache and classpath traversal (`impl/core.clj:337`) — 6 design cites |
| `http-kit` | :106 | fork: atomic pending-byte/drain state for bounded SSE writes (`src/org/httpkit/server.clj:321`) |
| `datastar-clojure` | :102 (sdk), :109 (sdk-http-kit) | the SSE SDK the web layer's package/delta delivery rides |
| `core.async.flow-monitor` | :147 | test-alias flow monitor, Seon fork publishing the bound port |
| `editscript` | :14 | the diff engine behind `seon.db/diff` |
| `edamame` | :50 | the reader used for source fidelity |
| `rewrite-clj` | :92 | source-preserving edits |
| `babashka-process` | :86, :135 | process control for the operator and `dev-cache` |
| `babashka` | :82 | only `reference-code/babashka/fs` is on the classpath — a nested path inside the babashka checkout, which is why the whole 5.9M repo is pinned |

**READ-FOR-DESIGN (8)** — no `deps.edn` entry, but named as the source an
agent must read:

| submodule | cites | the seam we read there |
|---|---|---|
| `core.async` | 19 design, 2 `src/` | `impl/dispatch.clj` workload tags `:io`/`:compute`/`:mixed`; `flow.clj:78,:165` proc/graph-def vocabulary |
| `konserve` | 7 design | `core.cljc:435-464` and `gc.cljc:22-30` — GC roots and binary storage under Datahike. Consumed at build time by `:git/url` from the same fork, so the submodule is the *readable* copy only |
| `clojure` | 8 design | `core/server.clj:228` (prepl), `main.clj:368`, `core.clj:3854` (`with-open` cleanup semantics) |
| `hyperlith` | 5 design | `impl/datastar.clj:119-180` — the revisioned keyframe/delta batching the render package copies |
| `clojurescript` | 4 design | `cljs/core.cljc:975-977`, `analyzer.cljc`, `compiler.cljc` — read only by the `clojurescript` skill, which exists to mine the deleted pod |
| `clj-reload` | 3 design | `parse.clj:122-131` `dependees` — dependent ordering we deliberately do not re-implement |
| `kaocha` | 2 design | "what a runner is, in facts": selection, reporters, bounds |
| `langchain4clj` | 1 design | the vendored reference named by the `llm-providers` skill |

## 4. Anything odd

- **Every gitlink is a declared gate input.** `seon.test.cache/input-roots`
  (`src/seon/test/cache.clj:154-166`) unions `declared-input-roots` with
  `(keys (gitlink-digests …))` — "a vendored dependency is an input whether or
  not `deps.edn` names it". So all 109 submodules already widen gate input
  digests by construction, and unvendoring changes those digests. This is the
  only sense in which the 74 UNREFERENCED repos are "used".
- **Directories vs submodules agree.** `comm` of `ls reference-code` against
  the `.gitmodules` paths is empty in both directions: 109 = 109, no stray
  directory, no missing checkout.
- **Duplicate/near-duplicate repos.** `datahike` (RUNTIME) + `datahike-lmdb`
  (HISTORY); `konserve` (READ) + `konserve-lmdb` (HISTORY); `datastar`
  (HISTORY, the polyglot monorepo) + `datastar-clojure` (RUNTIME, the SDK
  actually on the classpath); `transit-clj` (HISTORY) + `transit-cljs` +
  `transit-js` (both UNREF); `nrepl` + `cider-nrepl` + `orchard` (UNREF,
  UNREF, HISTORY); `piscina` + `tinypool` (both UNREF, both JS worker pools);
  `inspect-ai` + `inspect-evals`; `flash` + `flash-examples`; `integrant` +
  `integrant-repl`; `clojure` (READ) + `clojurescript` (READ, skill-only) +
  `shadow-cljs` (UNREF fork, 61M, for a build that AGENTS.md says is off).
- **Benchmark and eval suites: 17 repos, ~3.6 GB, nothing cites any of them.**
  `swe-bench`, `swe-agent`, `deepswe`, `commit0`, `terminal-bench`,
  `mle-bench`, `aider-polyglot`, `tau2-bench`, `gorilla-bfcl`, `webarena`,
  `osworld`, `browsergym`, `browsecomp-plus`, `inspect-ai`, `inspect-evals`,
  `re-bench`, `cybench` are all UNREFERENCED — every apparent `docs/` hit came
  from the excluded digest dump. The agent-memory index still points at
  inspect-ai as "model evals", but no file in the checkout does.
- **Agent-memory research cluster, also uncited**: `ADAS`, `Voyager`,
  `funsearch`, `openevolve`, `mem0`, `letta`, `generative_agents` (1.1 GB
  alone), `SimpleMem`, `hermes-agent`, `bd3lms`, `transformers`, `vllm`
  (HISTORY, 1 cite). All pinned in one or two 2026-06 commits and never moved.
- **Single-touch pins.** 83 of the 109 gitlinks have been changed exactly once
  — added and never advanced. Only `datahike` (77), `sci` (19), `konserve` (5),
  `shadow-cljs` (5), `malli` (4) have been maintained; several were pinned in
  batch commits (`7c2c845ef`, `184adeb15`, `f869a20fe`, `b1ac99a95`) that each
  added five to eight repos on one day.
- **Stale pins.** `posh` (2019-10-21), `aero` (2020-02-16), `malli-datomic`
  (2021-11-25), `spectomic` (2022-04-03), `expectations` (2023-04-11),
  `cron-utils` (2023-03-26), `Voyager` (2023-07-27), `generative_agents`
  (2023-08-11) are pinned to commits three to six years old.
- **Whether any pin no longer exists upstream could not be derived**: that
  needs a network fetch, which this read-only pass did not perform. Every pin
  is present in its local clone, and every `seantempesta` pin is on a remote
  branch of that clone.
- **`sheetjs`** is the only submodule not hosted on GitHub
  (`git.sheetjs.com`), so the fork test does not apply to it.
