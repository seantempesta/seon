"""Register Seon's Inspect provider, scorer, and falsifier task."""

from seon_inspect import provider as _provider
from seon_inspect import scorers as _scorers
from seon_inspect.tasks import gpqa as _gpqa

__all__ = ["_provider", "_scorers", "_gpqa"]
