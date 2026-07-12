---
type: research
status: active
tags: [research, agent, eval]
---

# Observer index — agent ids + transcript files per drive

The observer agent reads `transcripts/<task>-d<n>.txt` (byte-exact prompt +
reply blobs per turn, extracted from the t4drive store) or replays live via
`seon.agent.inspect/turn {:seon.agent.inspect/eid <turn-eid>}` against the
t4drive db (`data/clusters/t4drive/store`; blobs beside it). Turn eids are in
each transcript's TURN headers. `transcripts/index.txt` is the machine row
per drive: `<tag> agent=<id> outcome=<GREEN|RED> sha_ok=<yes|NO>`.

NOTE for the observer (driver finding, 2026-07-06): the ambiguous-flow
mechanics (`candidates` / `::near` / `::expected-count`) are taught by the
CONTRACT, not by the compact toolbelt card (the card carries verb line-1
docstrings only; the full docstrings carry the mechanics but do not render
into the assembled prompt). Weigh candidate-flow behaviour against the
contract text in `contracts/<task>.md`, not against the rendered card.

| drive tag | agent id | turns | outcome | transcript |
|---|---|---|---|---|
| two-bucket-d1 | OpP-2607061706 | RED | transcripts/two-bucket-d1.txt |
| two-bucket-d2 | WGk-2607061711 | RED | transcripts/two-bucket-d2.txt |
| two-bucket-d3 | BJK-2607061717 | RED | transcripts/two-bucket-d3.txt |
| grep-py-d1 | fQw-2607061720 | GREEN | transcripts/grep-py-d1.txt |
| grep-py-d2 | BFj-2607061722 | GREEN | transcripts/grep-py-d2.txt |
| grep-py-d3 | iHP-2607061723 | GREEN | transcripts/grep-py-d3.txt |
| book-store-py-d1 | JHk-2607061726 | GREEN | transcripts/book-store-py-d1.txt |
| book-store-py-d2 | ReQ-2607061728 | GREEN | transcripts/book-store-py-d2.txt |
| book-store-py-d3 | Rsf-2607061730 | RED | transcripts/book-store-py-d3.txt |
| react-d1 | Tcd-2607061732 | RED | transcripts/react-d1.txt |
| react-d2 | Yix-2607061741 | RED | transcripts/react-d2.txt |
| react-d3 | Dxd-2607061747 | RED | transcripts/react-d3.txt |
| poker-d1 | nqB-2607061751 | RED | transcripts/poker-d1.txt |
| poker-d2 | gBc-2607061752 | GREEN | transcripts/poker-d2.txt |
| poker-d3 | zQp-2607061754 | RED | transcripts/poker-d3.txt |
| paasio-d1 | bay-2607061756 | RED | transcripts/paasio-d1.txt |
| paasio-d2 | QaM-2607061801 | RED | transcripts/paasio-d2.txt |
| paasio-d3 | sgl-2607061804 | RED | transcripts/paasio-d3.txt |
| grep-js-d1 | fNr-2607061808 | RED | transcripts/grep-js-d1.txt |
| grep-js-d2 | xMO-2607061812 | RED | transcripts/grep-js-d2.txt |
| grep-js-d3 | tsu-2607061814 | RED | transcripts/grep-js-d3.txt |
| book-store-js-d1 | uJd-2607061820 | GREEN | transcripts/book-store-js-d1.txt |
| book-store-js-d2-CRASHED | cdP-2607061826 | CRASH (SEON-CORE-FAULT @t=536874714) | transcripts/book-store-js-d2-CRASHED.txt |
| book-store-js-d2 | nIx-2607061830 | RED | transcripts/book-store-js-d2.txt |
| book-store-js-d3 | pQc-2607061831 | RED | transcripts/book-store-js-d3.txt |
