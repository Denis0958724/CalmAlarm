"""Find a UI node by text/content-desc in a uiautomator dump (stdin) and print its centre."""
import html
import re
import sys

attr, value = sys.argv[1], sys.argv[2]
xml = sys.stdin.read()
nodes = re.findall(r"<node [^>]*>", xml)


def centre(node):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not b:
        return None
    x1, y1, x2, y2 = map(int, b.groups())
    return f"{(x1 + x2) // 2} {(y1 + y2) // 2}"


for exact in (True, False):
    for node in nodes:
        m = re.search(attr + r'="([^"]*)"', node)
        if not m:
            continue
        v = html.unescape(m.group(1))
        if (v == value) if exact else (value in v):
            c = centre(node)
            if c:
                print(c)
                sys.exit(0)
