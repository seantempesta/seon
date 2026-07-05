"""Offline proof for the BFCL AST form->tool_call bridge.

No cluster, no network: the adapter's prompt render + Clojure-form reply parse
are exercised directly, and the synthesized `ToolCall`s are fed to the REAL
bfcl `ast_match` scorer (`inspect_evals.bfcl.score.ast_match`) so a correct
parse scores 1 and a wrong/absent one scores 0 through the bench's own oracle —
the whole point of "keep the scorer, swap the generate".

The answer surface is a CLOJURE FORM (the agent's native output), not JSON (the
2026-07-05 rework): the reader maps Clojure literals onto the native Python
types the AST matcher compares.
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
SIMPLE_NAMES = ["circle_area"]


# ---------------------------------------------------------------------------
# parse_calls — read Clojure forms
# ---------------------------------------------------------------------------


def test_parse_single_form():
    calls = parse_calls('(f {:a 1})', candidates=["f"], matching="simple")
    assert calls == [{"name": "f", "arguments": {"a": 1}}]


def test_parse_string_and_float_and_bool_types():
    calls = parse_calls('(f {:s "hi" :x 3.0 :b true :n nil})',
                        candidates=["f"], matching="simple")
    assert calls == [{"name": "f",
                      "arguments": {"s": "hi", "x": 3.0, "b": True, "n": None}}]


def test_parse_vector_and_nested_map_args():
    calls = parse_calls('(f {:xs [1 2 3] :o {:k "v"}})',
                        candidates=["f"], matching="simple")
    assert calls == [{"name": "f",
                      "arguments": {"xs": [1, 2, 3], "o": {"k": "v"}}}]


def test_parse_keyword_value_becomes_name_string():
    calls = parse_calls('(f {:when :weekend})',
                        candidates=["f"], matching="simple")
    assert calls == [{"name": "f", "arguments": {"when": "weekend"}}]


def test_parse_dotted_function_name_survives():
    # normalize_function_name (scorer) folds dots->underscores; we keep raw.
    calls = parse_calls('(court_records.search_cases {:q "theft"})',
                        candidates=["court_records.search_cases"],
                        matching="simple")
    assert calls == [{"name": "court_records.search_cases",
                      "arguments": {"q": "theft"}}]


def test_parse_prose_then_form_takes_last_for_simple():
    reply = ('; reasoning about the request\n'
             '(f {:a 1})\n'
             '; on reflection the answer is:\n'
             '(f {:a 2})')
    assert parse_calls(reply, candidates=["f"], matching="simple") == [
        {"name": "f", "arguments": {"a": 2}}]


def test_parse_commented_out_call_is_ignored():
    reply = ('; (f {:a 1})\n'
             '(f {:a 2})')
    assert parse_calls(reply, candidates=["f"], matching="parallel") == [
        {"name": "f", "arguments": {"a": 2}}]


def test_parse_bookkeeping_form_filtered_by_candidates():
    reply = '(plan/done! {:my.plan/id "X"})\n(f {:a 5})'
    assert parse_calls(reply, candidates=["f"], matching="simple") == [
        {"name": "f", "arguments": {"a": 5}}]


def test_parse_parallel_returns_all_calls():
    reply = '(a {}) (b {:x 1})'
    assert parse_calls(reply, candidates=["a", "b"], matching="parallel") == [
        {"name": "a", "arguments": {}}, {"name": "b", "arguments": {"x": 1}}]


def test_parse_parallel_vector_wrapped():
    reply = '[(a {:p 1}) (b {:q "z"})]'
    assert parse_calls(reply, candidates=["a", "b"], matching="parallel") == [
        {"name": "a", "arguments": {"p": 1}},
        {"name": "b", "arguments": {"q": "z"}}]


def test_parse_brackets_inside_strings_dont_break():
    reply = '(note {:text "a [b] {c} )("})'
    assert parse_calls(reply, candidates=["note"], matching="simple") == [
        {"name": "note", "arguments": {"text": "a [b] {c} )("}}]


def test_parse_wrong_name_falls_back_to_raw_call():
    # No candidate matches -> the raw call stands (a MODEL miss, not parse miss).
    calls = parse_calls('(made_up_fn {:a 1})',
                        candidates=["circle_area"], matching="simple")
    assert calls == [{"name": "made_up_fn", "arguments": {"a": 1}}]


def test_parse_miss_returns_empty():
    assert parse_calls("I would call circle_area with radius 5",
                       candidates=SIMPLE_NAMES, matching="simple") == []
    assert parse_calls("", candidates=SIMPLE_NAMES, matching="simple") == []
    # A bare vector of literals is not a call form.
    assert parse_calls("[1 2 3]", candidates=SIMPLE_NAMES,
                       matching="simple") == []


# ---------------------------------------------------------------------------
# render_bfcl_prompt — every scorer check is stated (native form surface)
# ---------------------------------------------------------------------------


def test_render_states_the_contract():
    prompt = render_bfcl_prompt("What is the area of a circle radius 5?",
                                SIMPLE_TOOLS, "simple")
    assert "circle_area" in prompt          # function name
    assert "radius" in prompt               # parameter name
    assert "integer" in prompt              # its type
    assert "required" in prompt.lower()     # required-param rule
    assert "Clojure form" in prompt         # the answer shape (native)
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
    state = _state_with_reply('(circle_area {:radius 5})')
    anyio.run(_run_solver, bfcl_parse(), state)
    assert "bfcl_parse_error" not in state.metadata
    score = ast_match(state, state.target, CATEGORIES["simple_python"])
    assert score.value == 1


def test_bfcl_parse_wrong_value_scores_zero():
    state = _state_with_reply('(circle_area {:radius 9})')
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
    assert "Clojure form" in state.user_prompt.text


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
