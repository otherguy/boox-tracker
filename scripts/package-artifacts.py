"""Verify and package APKs without accessing signing secrets."""

import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
destination = root / "dist"
destination.mkdir(exist_ok=True)
info = {"tools": (root / ".tool-versions").read_text(), "artifacts": []}
for variant in ("debug", "diagnostic"):
    source = root / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
    verifier = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0/apksigner"
    result = subprocess.run(
        [str(verifier), "verify", "--verbose", "--print-certs", str(source)],
        capture_output=True,
        text=True,
        check=True,
    )
    target = destination / f"reading-sync-0.1.0-{variant}.apk"
    shutil.copyfile(source, target)
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix(".apk.sha256").write_text(f"{checksum}  {target.name}\n")
    info["artifacts"].append(
        {
            "variant": variant,
            "file": target.name,
            "sha256": checksum,
            "signature": result.stdout,
            "outputMetadata": json.loads(
                (source.parent / "output-metadata.json").read_text()
            ),
        }
    )
    print(f"{target.name}: {checksum}")
(destination / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
