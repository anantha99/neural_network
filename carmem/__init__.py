"""carmem: on-device, identity-scoped memory for in-car LLM assistants.

Five layers, each with its own write policy:

* identity   - who is present (hard signals + soft voice), guests are ephemeral
* episodic   - append-only trips and utterances with provenance ids
* semantic   - distilled facts/preferences with evidence and conditions
* procedural - mined routines the car may act on proactively
* vehicle    - person-independent facts about the car itself

The public entry point is :class:`carmem.memory.CarMemory`.
"""

from .memory import CarMemory
from .models import (
    Fact,
    IdentitySignal,
    Occupancy,
    Person,
    Proposal,
    Routine,
    Tier,
    Trip,
    Utterance,
    VEHICLE_SCOPE,
)
from .policy import Policy

__all__ = [
    "CarMemory",
    "Fact",
    "IdentitySignal",
    "Occupancy",
    "Person",
    "Policy",
    "Proposal",
    "Routine",
    "Tier",
    "Trip",
    "Utterance",
    "VEHICLE_SCOPE",
]

__version__ = "0.1.0"
