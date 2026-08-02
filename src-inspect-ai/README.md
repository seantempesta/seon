# seon-inspect — Seon evaluation on Inspect AI

This package contains the maintained Inspect AI evaluation surfaces for fresh
Seon. Inspect and Inspect Evals are selected by root Gitlinks and installed
from `reference-code/`; `evaluation-sources.lock.json` verifies their exact
revisions, installed origins, provider version, Python/dataset locks, and the
current Seon harness source before a task constructs or runs.

## Setup

```bash
cd src-inspect-ai
uv sync --extra test
.venv/bin/pytest
```

## Maintained surfaces

- `catalog.py` loads admitted standard `inspect_evals` tasks and retains
  native Inspect logs with source identity.
- `source_admission.py` verifies selected source and artifact digests.
- `seon_cluster.py` is the current io-prepl evaluation client.
- `generators.py`, `tool_scorers.py`, and `tasks/frozen_tool_rows.py`
  provide seeded shell and local-web rows with pure outcome scoring.
- `planning.py`, `milestone.py`, and their tasks retain offline scorer
  proofs while live execution moves through the current provider boundary.
- `mvp_graduation.py`, `product_scenarios.py`, and
  `reachability.py` own the fresh-system evaluation rows.

The deleted parser/oracle servers, self-host evaluation bundle, Diffusion
package, and pod container adapters are not supported evaluation paths. Code
goals are graded through the ruling #36 clojure.test/test.check provider
boundary; this package does not embed a second Clojure parser or evaluator.
