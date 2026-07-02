"""The anti-dead-bundle gate must abort LOUD when the eval bundle is dead.

Isolation via subprocess: oracle_scorers reads SEON_EVAL_BUNDLE at import, and
the liveness flag is process-global.
"""

import os
import subprocess
import sys


def test_liveness_gate_aborts_on_missing_bundle():
    env = dict(os.environ, SEON_EVAL_BUNDLE="/nonexistent/main.js")
    r = subprocess.run(
        [sys.executable, "-c",
         "import seon_inspect.oracle_scorers as o; o.assert_oracle_live()"],
        capture_output=True, text=True, env=env,
    )
    assert r.returncode != 0
    assert "eval bundle missing" in (r.stderr + r.stdout)
