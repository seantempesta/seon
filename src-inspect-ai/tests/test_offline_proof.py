from types import SimpleNamespace

from seon_inspect import offline_proof


def _log(accuracy=None, *, status="success"):
    scores = []
    if accuracy is not None:
        scores.append(SimpleNamespace(
            name="primary", reducer="mean",
            metrics={"accuracy": SimpleNamespace(value=accuracy)}))
    return SimpleNamespace(
        status=status, error=None,
        results=SimpleNamespace(scores=scores))


def _run(monkeypatch, log, expected=1.0):
    monkeypatch.setattr(offline_proof, "RUNS", [
        offline_proof.ExpectedRun("proof", lambda: object(),
                                  "primary", expected),
    ])
    monkeypatch.setattr(offline_proof, "inspect_eval",
                        lambda *args, **kwargs: [log])
    return offline_proof.main()


def test_offline_proof_accepts_expected_metric(monkeypatch):
    assert _run(monkeypatch, _log(1.0)) == 0


def test_offline_proof_rejects_metric_regression(monkeypatch):
    assert _run(monkeypatch, _log(0.0)) == 1


def test_offline_proof_rejects_missing_metric(monkeypatch):
    assert _run(monkeypatch, _log()) == 1


def test_offline_proof_rejects_failed_eval(monkeypatch):
    assert _run(monkeypatch, _log(status="error")) == 1
