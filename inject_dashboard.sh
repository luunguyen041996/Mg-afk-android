#!/bin/bash
python3 - <<'PYEOF'
import os, sys

server_file = "/storage/emulated/0/Music/mg-afk-android-main/app/src/main/java/com/mgafk/app/service/RemoteControlServer.kt"

candidates = [
    "/storage/emulated/0/Music/mg-afk-android-main/index.html",
    "/storage/emulated/0/Download/index.html",
]
html_file = next((c for c in candidates if os.path.exists(c)), None)
if not html_file:
    print("ERROR: index.html not found"); sys.exit(1)
print(f"Using HTML: {html_file}")

with open(html_file, "r", encoding="utf-8") as f:
    html_content = f.read()

html_escaped = html_content.replace("$", "${'$'}")

with open(server_file, "r", encoding="utf-8") as f:
    kt_content = f.read()

START_MARKER = 'private val INDEX_HTML = """'
END_MARKER = '""".trimIndent()'

start_idx = kt_content.find(START_MARKER)
if start_idx == -1:
    print("ERROR: Cannot find INDEX_HTML in RemoteControlServer.kt"); sys.exit(1)

end_idx = kt_content.find(END_MARKER, start_idx)
if end_idx == -1:
    print("ERROR: Cannot find end of INDEX_HTML"); sys.exit(1)

end_idx += len(END_MARKER)

new_block = START_MARKER + '\n' + html_escaped + '\n' + END_MARKER
kt_new = kt_content[:start_idx] + new_block + kt_content[end_idx:]

with open(server_file, "w", encoding="utf-8") as f:
    f.write(kt_new)

print(f"SUCCESS: Injected {len(html_content)} bytes of HTML")
PYEOF
