#!/usr/bin/env python3
"""Feasibility generator for the committed repetition fixture; never used by an export route.

Proves real loop/function/callback source for both targets before extending the shared generator.
Names are allocated from indices, and numeric/color literals are parsed instead of copied as code.
The prototype intentionally supports only the fixture's numeric layout properties and integer state.
"""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCUMENT = ROOT / "docs/design/evidence/ui-builder-repetition-export/repetition-initial.document.json"


def generate(document, remote):
    nodes = document["nodes"]
    definitions = document["components"]
    component_names = {key: f"Component{i}" for i, key in enumerate(definitions)}
    loop_names = {key: f"Rows{i}" for i, (key, node) in enumerate(nodes.items())
                  if node["componentId"] == "layout/for-each"}
    state_names = {key: f"state{i}" for i, key in enumerate(document["stateVariables"])}
    prefix, unit = ("Remote", "rdp") if remote else ("", "dp")
    modifier = prefix + "Modifier"
    lines = []
    classes = []

    def number(value):
        assert type(value) in (int, float) and math.isfinite(value)
        return f"{float(value)}f"

    def integer(value):
        assert type(value) is int and -(2**31) <= value < 2**31
        return str(value)

    def expr(value, scope):
        if value["type"] == "binding":
            return scope[value["value"]]
        assert value["type"] in ("float", "int")
        return number(value["value"])

    def subtree(root):
        yield root
        for children in nodes[root].get("slots", {}).values():
            for child in children:
                yield from subtree(child)

    parameters, callbacks = {}, {}
    for key, definition in definitions.items():
        ids = list(subtree(definition["root"]))
        parameters[key] = list(dict.fromkeys(
            v["value"] for id in ids for v in nodes[id]["properties"].values()
            if v["type"] == "binding"))
        callbacks[key] = [id for id in ids if nodes[id].get("eventBindings")]

    def action(id):
        actions = nodes[id]["eventBindings"]["click"]
        parts = []
        for item in actions:
            assert item["type"] == "set"
            state, value = state_names[item["variable"]], integer(item["value"])
            parts.append(f"valueChange({state}, {value}.ri)" if remote else f"{state} = {value}")
        if not remote:
            return "{ " + "; ".join(parts) + " }"
        return parts[0] if len(parts) == 1 else "combinedAction(" + ", ".join(parts) + ")"

    def mods(node, handlers):
        result = modifier
        for m in node["modifiers"]:
            kind = m["type"]
            if kind == "padding":
                result += ".padding(" + ", ".join(number(m.get(k, 0)) + "." + unit
                          for k in ("startDp", "topDp", "endDp", "bottomDp")) + ")"
            elif kind == "size":
                result += f".size({number(m['widthDp'])}.{unit}, {number(m['heightDp'])}.{unit})"
            elif kind == "fillMaxSize":
                result += ".fillMaxSize()"
            elif kind == "background":
                color = m["color"]
                assert color["type"] == "color" and len(color["value"]) == 9
                literal = f"Color(0x{int(color['value'][1:], 16):08X})"
                result += f".background({literal + '.rc' if remote else literal})"
            else:
                raise ValueError(f"prototype modifier: {kind}")
        if node.get("eventBindings"):
            callback = handlers[node["id"]] if node["id"] in handlers else action(node["id"])
            result += f".clickable({callback})" if remote else f".clickable(onClick = {callback})"
        return result

    def line(depth, text):
        lines.append("    " * depth + text)

    def emit(id, depth, scope, handlers):
        node = nodes[id]
        properties = node["properties"]
        placement = node.get("component")
        if placement:
            key = placement["componentKey"]
            args = [expr(placement["arguments"][key2], scope) for key2 in parameters[key]]
            args += [handlers[id2] if id2 in handlers else action(id2) for id2 in callbacks[key]]
            args += [mods(node, handlers)]
            line(depth, component_names[key] + "(" + ", ".join(args) + ")")
            return
        kind = node["componentId"]
        layout = {"layout/box": "Box", "layout/row": "Row", "layout/column": "Column",
                  "layout/for-each": "Column"}[kind]
        args = ["modifier = " + mods(node, handlers)]
        spacing = "horizontalSpacingDp" if layout == "Row" else "verticalSpacingDp"
        if spacing in properties:
            axis = "horizontal" if layout == "Row" else "vertical"
            args.append(f"{axis}Arrangement = {prefix}Arrangement.spacedBy({expr(properties[spacing], scope)}.{unit})")
        if layout == "Row":
            args.append(f"verticalAlignment = {prefix}Alignment.CenterVertically")
        line(depth, prefix + layout + "(" + ", ".join(args) + ") {")
        if kind == "layout/for-each":
            data = properties["data"]
            assert data["type"] == "list"
            rows = [row["fields"] for row in data["values"]]
            keys = list(rows[0]) if rows else []
            rowtype = loop_names[id]
            row_scope = {key: f"row.field{i}" for i, key in enumerate(keys)}
            classes.append("private data class " + rowtype + "(" + ", ".join(
                f"val field{i}: Float" for i in range(len(keys))) + ")" if keys else
                "private class " + rowtype)
            values = [rowtype + "(" + ", ".join(expr(row[key], {}) for key in keys) + ")" for row in rows]
            line(depth + 1, f"kotlin.collections.listOf<{rowtype}>(" + ", ".join(values) + ").forEach { row ->")
            template, = node["slots"]["template"]
            emit(template, depth + 2, row_scope, handlers)
            line(depth + 1, "}")
        elif "showByState" in properties:
            selection = properties["showByState"]["fields"]
            state = state_names[selection["selector"]["variable"]]
            cases = list(selection["cases"]["fields"].items())
            fallback = selection["fallback"]["value"]
            if remote:
                # Same exact low/high integer comparison proven in the production selection emitter.
                index = f"{len(cases)}.ri"
                for i, (_, value) in reversed(list(enumerate(cases))):
                    n = int(integer(value["value"]))
                    name = f"selection{i}"
                    line(depth + 1, f"val {name} = ({state} and 65535.ri).isEqualTo({n & 65535}.ri).and(({state} shr 16.ri).isEqualTo({n >> 16}.ri)).select({i}.ri, {index}).createReference()")
                    index = name
                line(depth + 1, f"RemoteStateLayout({index}, " + ", ".join(str(i) for i in range(len(cases) + 1)) + ") { selected ->")
                line(depth + 2, "when (selected) {")
                for i, (child, _) in enumerate(cases + [(fallback, None)]):
                    line(depth + 3, f"{i} -> RemoteBox {{")
                    emit(child, depth + 4, scope, handlers)
                    line(depth + 3, "}")
                line(depth + 2, "}")
                line(depth + 1, "}")
            else:
                line(depth + 1, f"when ({state}) {{")
                for child, value in cases + [(fallback, None)]:
                    label = integer(value["value"]) if value else "else"
                    line(depth + 2, label + " -> {")
                    emit(child, depth + 3, scope, handlers)
                    line(depth + 2, "}")
                line(depth + 1, "}")
        else:
            for child in node.get("slots", {}).get("children", []):
                emit(child, depth + 1, scope, handlers)
        line(depth, "}")

    imports = ["androidx.compose.runtime.*", "androidx.compose.ui.graphics.Color"]
    if remote:
        imports += [f"androidx.compose.remote.creation.compose.{p}.*" for p in ("action", "layout", "modifier", "state")]
    else:
        imports += ["androidx.compose.foundation.layout.*", "androidx.compose.foundation.background",
                    "androidx.compose.foundation.clickable", "androidx.compose.ui.Modifier",
                    "androidx.compose.ui.Alignment", "androidx.compose.ui.unit.dp"]
    line(0, '// Experimental source generated from the committed semantic repetition fixture.')
    line(0, '@file:Suppress("RestrictedApi")')
    line(0, "package proof.repetition." + ("remote" if remote else "compose"))
    for item in imports:
        line(0, "import " + item)
    annotation = "@Composable @RemoteComposable" if remote else "@Composable"
    line(0, annotation)
    line(0, "fun " + ("RemoteRepeatedContent" if remote else "ComposeRepeatedContent") + "() {")
    for key, declaration in document["stateVariables"].items():
        assert declaration["valueType"] == "int" and not declaration["nullable"]
        value = integer(declaration["initialValue"])
        line(1, f"val {state_names[key]} = rememberMutableRemoteInt({value})" if remote else
             f"var {state_names[key]} by remember {{ mutableIntStateOf({value}) }}")
    for root in document["roots"]:
        emit(root, 1, {}, {})
    line(0, "}")
    for key, definition in definitions.items():
        args = [f"argument{i}: Float" for i, _ in enumerate(parameters[key])]
        args += [f"callback{i}: " + ("Action" if remote else "() -> Unit") for i, _ in enumerate(callbacks[key])]
        args += [f"modifier: {modifier}"]
        line(0, annotation)
        line(0, "private fun " + component_names[key] + "(" + ", ".join(args) + ") {")
        line(1, prefix + "Box(modifier = modifier) {")
        emit(definition["root"], 2, {p: f"argument{i}" for i, p in enumerate(parameters[key])},
             {id: f"callback{i}" for i, id in enumerate(callbacks[key])})
        line(1, "}")
        line(0, "}")
    return "\n".join(lines + classes) + "\n"


if __name__ == "__main__":
    document = json.loads(DOCUMENT.read_text())
    output = ROOT / "experiments/remote-state-selection/build/repetition-source"
    output.mkdir(parents=True, exist_ok=True)
    for remote in (False, True):
        name = "RemoteRepeatedContent" if remote else "ComposeRepeatedContent"
        directory = output / ("remote" if remote else "compose")
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / (name + ".kt")
        path.write_text(generate(document, remote))
        print(path)
