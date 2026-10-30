"""LLM adapters. The memory core never imports a vendor SDK; it talks to
:class:`LLMClient`. Pick an adapter or write a ten-line one for your stack."""

from .base import LLMClient, NullLLM
from .tools import MEMORY_TOOLS, to_anthropic_tools, to_openai_tools

__all__ = ["LLMClient", "NullLLM", "MEMORY_TOOLS", "to_anthropic_tools", "to_openai_tools"]
