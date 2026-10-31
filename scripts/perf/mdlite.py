"""A minimal Markdown subset to HTML, for the narrative files of the performance report (standard library only).

Supported: headings # to ###, paragraphs (lines joined with a space), "- " / "* " bullet lists, "1. " numbered lists,
**bold**, `code`, simple pipe tables (header row, separator row with optional :--: alignment), links rendered as
their text only, fenced ``` code blocks and --- rules. Everything else is HTML-escaped and shown as written.
"""
from __future__ import annotations

import html
import re

_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
_BULLET = re.compile(r"^\s*[-*]\s+(.*)$")
_NUMBER = re.compile(r"^\s*\d+[.)]\s+(.*)$")
_FENCE = re.compile(r"^\s*```")
_RULE = re.compile(r"^\s*(?:-\s*){3,}$|^\s*(?:\*\s*){3,}$")
_SEPARATOR = re.compile(r"^\s*\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)*\|?\s*$")
_LINK = re.compile(r"\[([^\]]+)\]\([^)\s]*(?:\s+\"[^\"]*\")?\)")
_BOLD = re.compile(r"\*\*(.+?)\*\*")


def inline(text: str) -> str:
    """Inline markup: `code` (verbatim), **bold**, [text](url) -> text; the rest escaped."""
    out = []
    for index, part in enumerate(re.split(r"(`[^`]+`)", text)):
        if index % 2 == 1:
            out.append("<code>" + html.escape(part[1:-1]) + "</code>")
            continue
        part = _LINK.sub(lambda m: m.group(1), part)
        part = html.escape(part)
        out.append(_BOLD.sub(r"<strong>\1</strong>", part))
    return "".join(out)


def _cells(line: str) -> list:
    line = line.strip()
    if line.startswith("|"):
        line = line[1:]
    if line.endswith("|") and not line.endswith("\\|"):
        line = line[:-1]
    cells, current, code = [], [], False
    for char in line:  # a | inside `code` does not split the cell
        if char == "`":
            code = not code
        if char == "|" and not code:
            cells.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    cells.append("".join(current).strip())
    return cells


def _alignments(separator: str) -> list:
    out = []
    for cell in _cells(separator):
        left, right = cell.startswith(":"), cell.endswith(":")
        out.append("center" if left and right else "right" if right else "left" if left else None)
    return out


def _table(header: str, separator: str, rows: list) -> str:
    aligns = _alignments(separator)
    head = _cells(header)

    def cell(tag, text, i):
        align = aligns[i] if i < len(aligns) and aligns[i] else None
        style = f' style="text-align:{align}"' if align else ""
        return f"<{tag}{style}>{inline(text)}</{tag}>"

    parts = ['<table class="md-table">', "<thead><tr>"]
    parts += [cell("th", text, i) for i, text in enumerate(head)]
    parts.append("</tr></thead><tbody>")
    for row in rows:
        values = _cells(row)
        values += [""] * (len(head) - len(values))
        parts.append("<tr>" + "".join(cell("td", text, i) for i, text in enumerate(values[:len(head)])) + "</tr>")
    parts.append("</tbody></table>")
    return "".join(parts)


def render(text: str, heading_offset: int = 0) -> str:
    """Markdown subset -> HTML. heading_offset shifts heading levels (# -> h1+offset, capped at h6)."""
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out, paragraph, items, list_kind, list_start = [], [], [], None, 1

    def flush_paragraph():
        if paragraph:
            out.append("<p>" + " ".join(inline(line.strip()) for line in paragraph) + "</p>")
            paragraph.clear()

    def flush_list():
        nonlocal list_kind
        if items:
            start = f' start="{list_start}"' if list_kind == "ol" and list_start != 1 else ""
            out.append(f"<{list_kind}{start}>" + "".join(f"<li>{inline(item)}</li>" for item in items)
                       + f"</{list_kind}>")
            items.clear()
        list_kind = None

    i = 0
    while i < len(lines):
        line = lines[i]
        if _FENCE.match(line):
            flush_paragraph()
            flush_list()
            code = []
            i += 1
            while i < len(lines) and not _FENCE.match(lines[i]):
                code.append(lines[i])
                i += 1
            out.append("<pre><code>" + html.escape("\n".join(code)) + "</code></pre>")
            i += 1
            continue
        if not line.strip():
            flush_paragraph()
            flush_list()
            i += 1
            continue
        heading = _HEADING.match(line)
        if heading:
            flush_paragraph()
            flush_list()
            level = min(min(len(heading.group(1)), 3) + heading_offset, 6)
            out.append(f"<h{level}>{inline(heading.group(2))}</h{level}>")
            i += 1
            continue
        if line.lstrip().startswith("|") and i + 1 < len(lines) and _SEPARATOR.match(lines[i + 1]):
            flush_paragraph()
            flush_list()
            header, separator = line, lines[i + 1]
            i += 2
            rows = []
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                rows.append(lines[i])
                i += 1
            out.append(_table(header, separator, rows))
            continue
        if _RULE.match(line) and not paragraph:
            flush_list()
            out.append("<hr>")
            i += 1
            continue
        bullet, number = _BULLET.match(line), _NUMBER.match(line)
        if bullet or number:
            flush_paragraph()
            kind = "ul" if bullet else "ol"
            if list_kind and list_kind != kind:
                flush_list()
            if not items:  # a list resumed after a code block keeps its number ("5." stays 5)
                list_start = int(line.split(".")[0].split(")")[0]) if number else 1
            list_kind = kind
            items.append((bullet or number).group(1).strip())
            i += 1
            continue
        if items and line.startswith((" ", "\t")):
            items[-1] += " " + line.strip()  # continuation of the last list item
            i += 1
            continue
        flush_list()
        paragraph.append(line)
        i += 1
    flush_paragraph()
    flush_list()
    return "\n".join(out)
