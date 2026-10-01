#!/usr/bin/env python3
import json
import os
from pathlib import Path

version_code = int(os.environ["VERSION_CODE"])
if version_code <= 0:
    raise ValueError("VERSION_CODE must be positive")
version_name = os.environ["VERSION_NAME"]
if not version_name.strip():
    raise ValueError("VERSION_NAME must not be empty")
tag = os.environ["TAG"]
apk = os.environ["APK"]
repository = os.environ["GITHUB_REPOSITORY"]
package_name = os.environ["PACKAGE_NAME"]
certificate_sha256 = os.environ["CERT_SHA256"].lower()
apk_path = Path(apk)
if not apk_path.is_file() or apk_path.stat().st_size <= 0:
    raise ValueError("APK file must exist and be non-empty")
sha256 = os.environ["APK_SHA256"].lower()
for label, value in (("APK_SHA256", sha256), ("CERT_SHA256", certificate_sha256)):
    if len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
        raise ValueError(f"{label} must be a 64-character hexadecimal digest")
manifest = {
    "versionCode": version_code,
    "versionName": version_name,
    "apkUrl": f"https://github.com/{repository}/releases/download/{tag}/{apk}",
    "changelog": "Build automático do CloudStream Kythour Premium com plugins integrados e atualização remota.",
    "id": tag,
    "packageName": package_name,
    "sha256": sha256,
    "certificateSha256": certificate_sha256,
    "size": apk_path.stat().st_size,
}
Path("app-update.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
