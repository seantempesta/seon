"""Offline proof for the BFCL AST text->tool_call bridge.

No cluster, no network: the adapter's prompt render + reply parse are exercised
directly, and the synthesized `ToolCall`s are fed to the REAL bfcl `ast_match`
scorer (`inspect_evals.bfcl.score.ast_match`) so a correct parse scores 1 and a
wrong/absent one scores 0 through the bench's own oracle — the whole point of
"keep the scorer, swap the generate".
"""

from __future__ import annotations

import anyio
from inspect_ai.model import ChatMessageAssistant, ChatMessageUser, ModelName
from inspect_ai.solver import TaskState
from inspect_evals.bfcl.score.scorer import ast_match
from inspect_evals.bfcl.utils.task_categories import CATEGORIES

from seon_inspect.bfcl_adapter import (
    BFCL_AST_CATEGORIES,
    _to_tool_calls,
    bfcl_parse,
    bfcl_prompt,
    parse_calls,
    render_bfcl_prompt,
)

# A minimal "simple" BFCL sample: one candidate function, one required int arg.
SIMPLE_TOOLS = [{
    "name": "circle_area",
    "description": "Area of a circle from its radius.",
    "parameters": {
        "type": "object",
        "properties": {"radius": {"type": "integer",
                                  "description": "radius in cm"}},
        "required": ["radius"],
    },
}]
SIMPLE_GT = [{"circle_area": {"radius": [5]}}]  # BFCL possible-answers shape


# ---------------------------------------------------------------------------
# parse_calls
# ---------------------------------------------------------------------------


def test_parse_plain_array():
    calls = parse_calls('[{"name": "f", "arguments": {"a": 1}}]')
    assert calls == [{"name": "f", "arguments": {"a": 1}}]


def test_parse_single_object():
    calls = parse_calls('{"name": "f", "arguments": {"a": 1}}')
    assert calls == [{"name": "f", "arguments": {"a": 1}}]


def test_parse_prose_then_json_takes_last():
    reply = (
        'Here is an example {"name": "wrong", "arguments": {}} but the answer '
        'is:\n[{"name": "f", "arguments": {"a": 2}}]')
    assert parse_calls(reply) == [{"name": "f", "arguments": {"a": 2}}]


def test_parse_fenced_block_and_alt_arg_key():
    reply = 'Sure:\n```json\n[{"name": "f", "parameters": {"a": 3}}]\n```'
    assert parse_calls(reply) == [{"name": "f", "arguments": {"a": 3}}]


def test_parse_brackets_inside_strings_dont_break():
    reply = '[{"name": "note", "arguments": {"text": "a [b] {c}"}}]'
    assert parse_calls(reply) == [
        {"name": "note", "arguments": {"text": "a [b] {c}"}}]


def test_parse_miss_returns_empty():
    assert parse_calls("I would call circle_area with radius 5") == []
    assert parse_calls("") == []
    assert parse_calls("[1, 2, 3]") == []  # JSON, but not call-shaped


def test_parse_parallel_multiple_calls():
    reply = '[{"name": "a", "arguments": {}}, {"name": "b", "arguments": {"x": 1}}]'
    assert parse_calls(reply) == [
        {"name": "a", "arguments": {}}, {"name": "b", "arguments": {"x": 1}}]


# ---------------------------------------------------------------------------
# render_bfcl_prompt — every scorer check is stated
# ---------------------------------------------------------------------------


def test_render_states_the_contract():
    prompt = render_bfcl_prompt("What is the area of a circle radius 5?",
                                SIMPLE_TOOLS, "simple")
    assert "circle_area" in prompt          # function name
    assert "radius" in prompt               # parameter name
    assert "integer" in prompt              # its type
    assert "required" in prompt.lower()     # required-param rule
    assert "JSON array" in prompt           # the answer shape
    assert "EXACTLY ONE" in prompt          # simple/multiple count rule
    assert "circle radius 5" in prompt      # the original request survives


def test_render_parallel_count_rule():
    prompt = render_bfcl_prompt("do two things", SIMPLE_TOOLS, "parallel")
    assert "SEVERAL" in prompt or "more than one" in prompt


# ---------------------------------------------------------------------------
# End-to-end through the REAL ast_match scorer
# ---------------------------------------------------------------------------


def _state_with_reply(reply: str) -> TaskState:
    state = TaskState(
        model=ModelName("mockllm/model"),
        sample_id="s1",
        epoch=1,
        input=[ChatMessageUser(content="q")],
        messages=[ChatMessageUser(content="q")],
        metadata={
            "tools": SIMPLE_TOOLS,
            "parsed_ground_truth": SIMPLE_GT,
            "category_name": "simple_python",
            "scorer": "simple",
            "language": "python",
        },
    )
    state.output.completion = reply
    return state


def test_bfcl_parse_then_ast_match_correct():
    state = _state_with_reply('[{"name": "circle_area", "arguments": {"radius": 5}}]')
    anyio.run(_run_solver, bfcl_parse(), state)
    assert "bfcl_parse_error" not in state.metadata
    score = ast_match(state, state.target, CATEGORIES["simple_python"])
    assert score.value == 1


def test_bfcl_parse_wrong_value_scores_zero():
    state = _state_with_reply('[{"name": "circle_area", "arguments": {"radius": 9}}]')
    anyio.run(_run_solver, bfcl_parse(), state)
    assert "bfcl_parse_error" not in state.metadata     # parsed fine (model miss)
    score = ast_match(state, state.target, CATEGORIES["simple_python"])
    assert score.value == 0


def test_bfcl_parse_miss_scores_zero_and_flags():
    state = _state_with_reply("I'd use circle_area with radius 5.")
    anyio.run(_run_solver, bfcl_parse(), state)
    assert state.metadata.get("bfcl_parse_error")       # HARNESS-side signal
    score = ast_match(state, state.target, CATEGORIES["simple_python"])
    assert score.value == 0


def test_bfcl_prompt_rewrites_user_prompt():
    state = _state_with_reply("")
    anyio.run(_run_solver, bfcl_prompt(), state)
    assert "circle_area" in state.user_prompt.text
    assert "JSON array" in state.user_prompt.text


async def _run_solver(solver, state):
    return await solver(state, None)


# ---------------------------------------------------------------------------
# scope invariant
# ---------------------------------------------------------------------------


def test_ast_categories_are_the_python_subset():
    # Every default category is single-turn, non-live, non-exec (pure AST).
    for name in BFCL_AST_CATEGORIES:
        cfg = CATEGORIES[name]
        assert not cfg.is_multi_turn
        assert not cfg.is_live
        assert not cfg.is_executable
        assert cfg.matching_function in ("simple", "multiple", "parallel")
        assert cfg.language == "python"


def test_to_tool_calls_shape():
    tcs = _to_tool_calls([{"name": "f", "arguments": {"a": 1}}])
    assert len(tcs) == 1 and tcs[0].function == "f"
    assert tcs[0].arguments == {"a": 1}
