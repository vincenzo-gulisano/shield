#!/usr/bin/env python3

import argparse
import hashlib
import html
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Node:
    raw: str
    type_name: str
    attrs: dict[str, str]

    @property
    def id(self) -> str:
        return self.attrs.get("id", self.raw)


def split_top_level(text: str, separator: str = ",") -> list[str]:
    parts = []
    start = 0
    depth = 0
    for i, char in enumerate(text):
        if char in "[{(":
            depth += 1
        elif char in "]})":
            depth -= 1
        elif char == separator and depth == 0:
            parts.append(text[start:i].strip())
            start = i + 1
    parts.append(text[start:].strip())
    return [part for part in parts if part]


def parse_attrs(attrs_text: str) -> dict[str, str]:
    attrs = {}
    for part in split_top_level(attrs_text):
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        attrs[key.strip()] = value.strip()
    return attrs


def parse_node(node_text: str) -> Node:
    node_text = node_text.strip()
    bracket_index = node_text.find("[")
    if bracket_index < 0:
        return Node(raw=node_text, type_name=node_text, attrs={})
    type_name = node_text[:bracket_index].strip()
    attrs_text = node_text[bracket_index + 1:-1]
    return Node(raw=node_text, type_name=type_name, attrs=parse_attrs(attrs_text))


def section_between(text: str, start_marker: str, end_marker: str) -> str:
    start = text.index(start_marker) + len(start_marker)
    end = text.index(end_marker, start)
    return text[start:end]


def parse_graph_printout(text: str) -> tuple[dict[str, Node], list[tuple[str, str]]]:
    text = " ".join(text.split())
    nodes_text = section_between(text, "nodes=[", "], arcs={")
    arcs_start = text.index("arcs={") + len("arcs={")
    arcs_end = text.rindex("}")
    if arcs_end > 0 and text[arcs_end - 1] == "}":
        arcs_end -= 1
    arcs_text = text[arcs_start:arcs_end]

    nodes = {}
    for node_text in split_top_level(nodes_text):
        node = parse_node(node_text)
        nodes[node.raw] = node

    arcs = []
    for arc_text in split_top_level(arcs_text):
        source_text, rest = arc_text.split("->", 1)
        target_text, _arc_value = rest.rsplit("=", 1)
        source = parse_node(source_text)
        target = parse_node(target_text)
        nodes.setdefault(source.raw, source)
        nodes.setdefault(target.raw, target)
        arcs.append((source.raw, target.raw))

    return nodes, arcs


def node_label(node: Node) -> str:
    attrs = {k: v for k, v in node.attrs.items() if k != "id"}
    rows = [
        f'<TR><TD BGCOLOR="#E8F0FE"><B>{html.escape(display_type(node.type_name))}</B></TD></TR>',
        f'<TR><TD><FONT POINT-SIZE="18"><B>{html.escape(node.id)}</B></FONT></TD></TR>',
    ]
    for key, value in attrs.items():
        rows.append(f'<TR><TD ALIGN="LEFT">{html.escape(key)} = {html.escape(value)}</TD></TR>')
    return '<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="6">' + "".join(rows) + "</TABLE>>"


def display_type(type_name: str) -> str:
    return " ".join(re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?=[A-Z]|$)|\d+", type_name))


def dot_id(raw: str) -> str:
    return "n" + hashlib.sha1(raw.encode("utf-8")).hexdigest()


def to_dot(nodes: dict[str, Node], arcs: list[tuple[str, str]]) -> str:
    lines = [
        "digraph G {",
        "  graph [rankdir=LR, bgcolor=white, pad=0.4, nodesep=0.7, ranksep=1.0];",
        "  node [shape=plain, fontname=Helvetica];",
        "  edge [color=\"#4B5563\", arrowsize=0.8, penwidth=1.6];",
    ]
    for raw, node in nodes.items():
        lines.append(f"  {dot_id(raw)} [label={node_label(node)}];")
    for source, target in arcs:
        lines.append(f"  {dot_id(source)} -> {dot_id(target)};")
    lines.append("}")
    return "\n".join(lines) + "\n"


def render(dot_path: Path, output_path: Path, verbose: bool = True) -> None:
    dot = shutil.which("dot")
    if dot is None:
        print(f"Graphviz 'dot' was not found. DOT file written to {dot_path}")
        return
    output_format = output_path.suffix.lstrip(".") or "png"
    subprocess.run([dot, f"-T{output_format}", str(dot_path), "-o", str(output_path)], check=True)
    if verbose:
        print(f"Wrote {output_path}")


def render_graph_printout(
    text: str,
    output_path: Path,
    dot_path: Path | None = None,
    verbose: bool = True,
) -> None:
    nodes, arcs = parse_graph_printout(text)
    dot_text = to_dot(nodes, arcs)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    if dot_path is not None:
        dot_path.parent.mkdir(parents=True, exist_ok=True)
        dot_path.write_text(dot_text)
        render(dot_path, output_path, verbose)
        return

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_dot_path = Path(tmp_dir) / (output_path.stem + ".dot")
        tmp_dot_path.write_text(dot_text)
        render(tmp_dot_path, output_path, verbose)


def main() -> None:
    parser = argparse.ArgumentParser(description="Render a Java graph printout as an image via Graphviz.")
    parser.add_argument("input", type=Path, help="Text file containing the graph printout.")
    parser.add_argument("output", type=Path, help="Output image path, e.g. graph.png, graph.svg, graph.pdf.")
    parser.add_argument("--dot", type=Path, help="Optional path for the intermediate DOT file.")
    args = parser.parse_args()

    render_graph_printout(args.input.read_text(), args.output, args.dot or args.output.with_suffix(".dot"))


if __name__ == "__main__":
    main()
