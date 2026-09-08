#!/usr/bin/env python3

import re
from collections import Counter
from dataclasses import dataclass


STRUCTURAL_NODES = {"Source", "Sink", "Union"}
STATEFUL_OPERATORS = {
    "MapAggregate",
    "MapCountUniqueKeys",
    "MapTimestampGroupShuffle",
    "MapTimestampPairwiseSwap",
    "MapConditionPairwiseSwap",
    "MapConditionPartitionShuffle",
}
NODE_RE = re.compile(r"\b([A-Za-z][A-Za-z0-9]*)\[id=")
ARC_RE = re.compile(
    r"([A-Za-z][A-Za-z0-9]*\[id=[^\]]+])"
    r"->"
    r"([A-Za-z][A-Za-z0-9]*\[id=[^\]]+])"
)
ID_RE = re.compile(r"\bid=([^,\]]+)")


@dataclass(frozen=True)
class GraphMetrics:
    total_operators: int
    stateful_operators: int
    branches_or_joins: int


def graph_metrics(individual: str) -> GraphMetrics:
    """Return compact structural metrics for a Shield solution graph string."""
    operators = [
        operator
        for operator in NODE_RE.findall(nodes_section(individual))
        if operator not in STRUCTURAL_NODES
    ]
    out_degree: Counter[str] = Counter()
    in_degree: Counter[str] = Counter()
    for source, target in ARC_RE.findall(arcs_section(individual)):
        out_degree[node_id(source)] += 1
        in_degree[node_id(target)] += 1
    branch_points = sum(1 for degree in out_degree.values() if degree > 1)
    join_points = sum(1 for degree in in_degree.values() if degree > 1)
    return GraphMetrics(
        total_operators=len(operators),
        stateful_operators=sum(1 for operator in operators if operator in STATEFUL_OPERATORS),
        branches_or_joins=max(branch_points, join_points),
    )


def operator_types(individual: str) -> list[str]:
    return [
        operator
        for operator in NODE_RE.findall(nodes_section(individual))
        if operator not in STRUCTURAL_NODES
    ]


def nodes_section(individual: str) -> str:
    match = re.search(r"nodes=\[(.*?)]\s*, arcs=", individual)
    return match.group(1) if match else individual


def arcs_section(individual: str) -> str:
    match = re.search(r"arcs=\{(.*)}\s*}", individual)
    return match.group(1) if match else ""


def node_id(node: str) -> str:
    match = ID_RE.search(node)
    return match.group(1) if match else node
