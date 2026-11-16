"""Install pinned release binaries, checking the publisher's release checksums."""
import hashlib
import io
import os
from pathlib import Path
import platform
import tarfile
import urllib.request
import zipfile

TOOLS = {
    "actionlint": ("rhysd/actionlint", "1.7.7"),
    "gitleaks": ("gitleaks/gitleaks", "8.24.3"),
    "trivy": ("aquasecurity/trivy", "0.76.2"),
    "hadolint": ("hadolint/hadolint", "2.12.0"),
}


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "curavence-ci"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def install(name, destination):
    repo, version = TOOLS[name]
    windows = platform.system() == "Windows"
    base = f"https://github.com/{repo}/releases/download/v{version}/"
    if name == "hadolint":
        asset = "hadolint-Windows-x86_64.exe" if windows else "hadolint-Linux-x86_64"
        checksum_asset = asset + ".sha256"
    elif name == "trivy":
        asset = f"trivy_{version}_windows-64bit.zip" if windows else f"trivy_{version}_Linux-64bit.tar.gz"
        checksum_asset = f"trivy_{version}_checksums.txt"
    else:
        system = "windows" if windows else "linux"
        suffix = "zip" if windows else "tar.gz"
        architecture = "x64" if name == "gitleaks" else "amd64"
        asset = f"{name}_{version}_{system}_{architecture}.{suffix}"
        checksum_asset = f"{name}_{version}_checksums.txt"
    checksums = download(base + checksum_asset).decode()
    expected = next(line.split()[0] for line in checksums.splitlines() if line.strip().endswith(asset))
    data = download(base + asset)
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError(f"Checksum mismatch: {asset}")
    executable = name + (".exe" if windows else "")
    if asset.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            data = archive.read(executable)
    elif asset.endswith(".tar.gz"):
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            data = archive.extractfile(executable).read()
    target = destination / executable
    target.write_bytes(data)
    target.chmod(0o755)
    print(f"Installed {name} {version}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("tools", nargs="+", choices=TOOLS)
    parser.add_argument("--destination", default=".runtime/ci-tools")
    args = parser.parse_args()
    destination = Path(args.destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    for tool in args.tools:
        install(tool, destination)
    if os.environ.get("GITHUB_PATH"):
        with open(os.environ["GITHUB_PATH"], "a", encoding="utf-8") as stream:
            stream.write(str(destination) + "\n")
