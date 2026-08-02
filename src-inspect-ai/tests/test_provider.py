from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest
from inspect_ai.model import ChatMessageSystem, ChatMessageUser, GenerateConfig
from inspect_ai.scorer import CORRECT, INCORRECT, Target

from seon_inspect.host import EPISODE_SEMANTICS
from seon_inspect.provider import SeonModelAPI, objective_message
from seon_inspect.scorers import seon_terminal_honesty
from seon_inspect.tasks.gpqa import gpqa_diamond


def test_objective_message_preserves_one_user_message_verbatim():
    assert objective_message([ChatMessageUser(content="question")]) == "question"


def test_objective_message_renders_ordered_history():
    assert objective_message([
        ChatMessageSystem(content="system"),
        ChatMessageUser(content="question"),
    ]) == "--- SYSTEM ---\nsystem\n\n--- USER ---\nquestion"


def test_provider_refuses_tools_before_starting_host(monkeypatch):
    provider = SeonModelAPI("eval-host")
    monkeypatch.setattr(provider._host, "run_sample", lambda *_: pytest.fail())
    with pytest.raises(ValueError, match="non-empty tools"):
        asyncio.run(
            provider.generate(
                [ChatMessageUser(content="question")],
                [object()],
                "auto",
                GenerateConfig(),
            )
        )


def test_provider_maps_completed_episode_and_metadata(monkeypatch):
    provider = SeonModelAPI("eval-host")
    monkeypatch.setattr(
        provider._host,
        "run_sample",
        lambda *_: {
            "seon.eval.drive/completed-result": "ANSWER: B",
            "seon.eval.drive/terminal": {
                "seon.eval.drive/outcome": "seon.eval.drive/completed"
            },
        },
    )
    output = asyncio.run(
        provider.generate(
            [ChatMessageUser(content="question")], [], "none", GenerateConfig()
        )
    )
    assert output.completion == "ANSWER: B"
    assert output.metadata["seon_episode_semantics"] == EPISODE_SEMANTICS
    assert output.metadata["seon_episode"]["seon.eval.drive/completed-result"] == "ANSWER: B"


@pytest.mark.parametrize(
    ("outcome", "expected"),
    [
        ("seon.eval.drive/completed", CORRECT),
        ("seon.eval.drive/capped", INCORRECT),
    ],
)
def test_terminal_honesty_is_a_separate_gate(outcome, expected):
    state = SimpleNamespace(
        output=SimpleNamespace(
            metadata={
                "seon_episode": {
                    "seon.eval.drive/terminal": {
                        "seon.eval.drive/outcome": outcome
                    }
                }
            }
        )
    )
    result = asyncio.run(seon_terminal_honesty()(state, Target("A")))
    assert result.value == expected


def test_gpqa_falsifier_keeps_upstream_solver_and_adds_one_gate():
    task = gpqa_diamond()
    assert len(task.dataset) == 198
    assert task.epochs == 1
    assert len(task.scorer) == 2
    assert task.metadata["seon_episode_semantics"] == EPISODE_SEMANTICS
