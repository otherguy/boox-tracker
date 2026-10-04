"""Create a dedicated key using Java from mise. Never print credentials."""

import os
import secrets
import subprocess
from pathlib import Path

root = Path.home() / ".config/reading-sync"
root.mkdir(mode=0o700, parents=True, exist_ok=True)
config = root / "signing.properties"
key = root / "diagnostic.jks"
if config.exists() or key.exists():
    raise SystemExit("Signing files already exist; retain them for updates.")
password = secrets.token_urlsafe(48)
env = dict(os.environ, READING_SYNC_KEY_PASSWORD=password)
subprocess.run(
    [
        "keytool",
        "-genkeypair",
        "-keystore",
        str(key),
        "-alias",
        "diagnostic",
        "-keyalg",
        "RSA",
        "-keysize",
        "3072",
        "-validity",
        "10000",
        "-dname",
        "CN=Reading Sync diagnostic",
        "-storepass:env",
        "READING_SYNC_KEY_PASSWORD",
        "-keypass:env",
        "READING_SYNC_KEY_PASSWORD",
    ],
    env=env,
    check=True,
    capture_output=True,
)
key.chmod(0o600)
with os.fdopen(
    os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w"
) as handle:
    handle.write(
        f"storeFile={key}\nstorePassword={password}\nkeyAlias=diagnostic\nkeyPassword={password}\n"
    )
print(f"Diagnostic signing identity created. Back up {root}.")
