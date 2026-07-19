from types import SimpleNamespace

import pytest

from seon_inspect import cluster


def _status(port, generation):
    return ("{:seon.dev.target/status :seon.dev.target.status/ready "
            f':seon.dev.target/url "http://127.0.0.1:{port}" '
            f':seon.dev.target/processes {{:pod {{:generation "{generation}"}}}}}}')


class Runner:
    def __init__(self):
        self.calls = []
        self.generation = 1

    def __call__(self, command, **kwargs):
        self.calls.append(command[1:])
        if command[1:3] == ["branch", "restart"]:
            self.generation += 1
        stdout = (_status(41000 + self.generation, self.generation)
                  if "status" in command else "ok")
        return SimpleNamespace(returncode=0, stdout=stdout, stderr="")


def test_branch_lease_uses_canonical_operator_and_refreshes_identity():
    runner = Runner()
    lease = cluster.acquire_branch_lease("inspect-proof", runner=runner)
    assert lease.cluster_url == "http://127.0.0.1:41001/agents/run"
    restarted = cluster.restart_branch_lease(lease, runner=runner)
    assert restarted.cluster_url == "http://127.0.0.1:41002/agents/run"
    cluster.release_branch_lease(restarted, runner=runner)
    assert runner.calls == [
        ["branch", "open", "inspect-proof"],
        ["branch", "status", "inspect-proof", "--edn"],
        ["branch", "restart", "inspect-proof"],
        ["branch", "status", "inspect-proof", "--edn"],
        ["branch", "close", "inspect-proof"],
    ]


def test_branch_lease_fails_when_status_is_not_ready():
    def runner(command, **kwargs):
        stdout = "{:seon.dev.target/status :seon.dev.target.status/down}"
        return SimpleNamespace(returncode=0, stdout=stdout, stderr="")

    with pytest.raises(RuntimeError, match="not ready"):
        cluster.acquire_branch_lease("inspect-proof", runner=runner)


def test_branch_lease_surfaces_operator_failure():
    def runner(command, **kwargs):
        return SimpleNamespace(returncode=1, stdout="", stderr="owned elsewhere\n")

    with pytest.raises(RuntimeError, match="owned elsewhere"):
        cluster.acquire_branch_lease("inspect-proof", runner=runner)
