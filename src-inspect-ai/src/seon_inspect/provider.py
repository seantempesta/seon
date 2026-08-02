"""Inspect model provider mapping one generation to one Seon episode."""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

from inspect_ai.model import (
    ChatMessage,
    ContentText,
    GenerateConfig,
    ModelAPI,
    ModelOutput,
    modelapi,
)
from inspect_ai.tool import ToolChoice, ToolInfo

from seon_inspect.host import EPISODE_SEMANTICS, SeonHost


def _text_content(message: ChatMessage) -> str:
    if isinstance(message.content, str):
        return message.content
    if not all(isinstance(content, ContentText) for content in message.content):
        raise ValueError("Seon's text-only episode provider refuses non-text content")
    return "".join(content.text for content in message.content)


def objective_message(messages: list[ChatMessage]) -> str:
    """Project an ordered text conversation into one episode objective."""
    if not messages:
        raise ValueError("A Seon episode requires at least one chat message")
    rendered = [(message.role, _text_content(message)) for message in messages]
    if len(rendered) == 1 and rendered[0][0] == "user":
        return rendered[0][1]
    return "\n\n".join(
        f"--- {role.upper()} ---\n{content}" for role, content in rendered
    )


@modelapi("seon")
class SeonModelAPI(ModelAPI):
    """Serve Inspect generations through isolated Seon sample clusters."""

    def __init__(
        self,
        model_name: str,
        base_url: str | None = None,
        api_key: str | None = None,
        config: GenerateConfig = GenerateConfig(),
        **model_args: Any,
    ) -> None:
        super().__init__(model_name, base_url, api_key, config=config)
        self._max_connections = int(model_args.pop("max_connections", 6))
        self._host = SeonHost(
            run_cap=int(model_args.pop("run_cap", 6)),
            timeout_ms=(int(model_args.pop("timeout_ms"))
                        if "timeout_ms" in model_args else None),
        )
        if model_args:
            raise ValueError(f"Unknown Seon model arguments: {sorted(model_args)}")

    def max_connections(self) -> int:
        """Maximum simultaneous sample clusters in the warm JVM."""
        return self._max_connections

    async def generate(
        self,
        input: list[ChatMessage],
        tools: list[ToolInfo],
        tool_choice: ToolChoice,
        config: GenerateConfig,
    ) -> ModelOutput:
        """Run a seeded episode and expose its settled reply as a completion."""
        if tools:
            raise ValueError("Seon's episode provider refuses non-empty tools")
        objective = objective_message(input)
        sample_id = f"inspect-{uuid.uuid4().hex[:16]}"
        episode = await asyncio.to_thread(self._host.run_sample, sample_id, objective)
        completion = episode.get("seon.eval.drive/completed-result") or ""
        output = ModelOutput.from_content(
            model=f"seon/{self.model_name}", content=str(completion)
        )
        output.metadata = {
            "seon_episode_semantics": EPISODE_SEMANTICS,
            "seon_history_enabled": False,
            "seon_episode": episode,
        }
        return output
