#!/usr/bin/env python3
import json
import os
from pathlib import Path

version_code = int(os.environ["VERSION_CODE"])
version_name = os.environ["VERSION_NAME"]
tag = os.environ["TAG"]
apk = os.environ["APK"]
repository = os.environ["GITHUB_REPOSITORY"]
package_name = os.environ["PACKAGE_NAME"]
certificate_sha256 = os.environ["CERT_SHA256"].lower()
apk_path = Path(apk)
sha256 = os.environ["APK_SHA256"].lower()
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
