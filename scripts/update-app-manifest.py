#!/usr/bin/env python3
import json
import os
from pathlib import Path

version_code = int(os.environ["VERSION_CODE"])
version_name = os.environ["VERSION_NAME"]
tag = os.environ["TAG"]
apk = os.environ["APK"]
repository = os.environ["GITHUB_REPOSITORY"]
manifest = {
    "versionCode": version_code,
    "versionName": version_name,
    "apkUrl": f"https://github.com/{repository}/releases/download/{tag}/{apk}",
    "changelog": "Build automático do CloudStream Kythour Premium com plugins integrados e atualização remota.",
    "id": tag,
}
Path("app-update.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
