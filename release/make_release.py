"""Builds the Flames and Steel playtest zip (fork request 68).

Usage (from the repository root, after `gradlew :game-headed:portableInstaller`):

  py -I release/make_release.py --draft 118 --map-zip ../flames_and_steel_map_draft118.zip
      --repo-url https://github.com/OWNER/REPO --network-id 0123456789abcdef --out ../releases

The zip holds one folder, FlamesAndSteel-draft<N>/, with:
  game/                 the fork's portable build (jar, assets)
  flames_and_steel/     the map
  Run Flames and Steel.bat / .command, run-flames-and-steel.sh
  README.md, LICENSE, SOURCE.txt

The .command and .sh files get Unix line endings and execute permission inside the zip (the
permission travels in the zip entries, so the Mac Archive Utility keeps it); the .bat gets
Windows line endings.
"""

import argparse
import re
import stat
import sys
import time
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
PORTABLE = ROOT / "game-app/game-headed/build/distributions/game-headed.zip"


def info(external_mode: int) -> int:
    return (external_mode & 0xFFFF) << 16


def add_bytes(z: zipfile.ZipFile, name: str, data: bytes, mode: int) -> None:
    zi = zipfile.ZipInfo(name, date_time=time.localtime()[:6])
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.create_system = 3  # Unix, so the permission bits are honoured
    zi.external_attr = info(stat.S_IFREG | mode)
    z.writestr(zi, data)


def add_dir(z: zipfile.ZipFile, name: str) -> None:
    zi = zipfile.ZipInfo(name.rstrip("/") + "/", date_time=time.localtime()[:6])
    zi.create_system = 3
    zi.external_attr = info(stat.S_IFDIR | 0o755) | 0x10
    z.writestr(zi, b"")


def copy_zip(z: zipfile.ZipFile, src: Path, prefix: str, strip: str = "") -> int:
    count = 0
    with zipfile.ZipFile(src) as s:
        for e in s.infolist():
            name = e.filename
            if strip:
                if not name.startswith(strip):
                    continue
                name = name[len(strip):]
            if not name:
                continue
            if name.endswith("/"):
                add_dir(z, prefix + name)
            else:
                add_bytes(z, prefix + name, s.read(e), 0o644)
                count += 1
    return count


def text(path: Path, eol: str, values: dict) -> bytes:
    t = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    for k, v in values.items():
        t = t.replace("{{" + k + "}}", v)
    left = re.findall(r"\{\{[A-Z_]+\}\}", t)
    if left:
        sys.exit(f"{path.name}: placeholders not filled: {sorted(set(left))}")
    return t.replace("\n", eol).encode("utf-8")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--draft", required=True)
    p.add_argument("--map-zip", required=True, type=Path)
    p.add_argument("--repo-url", required=True)
    p.add_argument("--network-id", required=True)
    p.add_argument("--out", required=True, type=Path)
    p.add_argument("--portable", type=Path, default=PORTABLE)
    a = p.parse_args()

    with zipfile.ZipFile(a.portable) as s:
        jars = [n for n in s.namelist() if re.match(r"bin/game-headed-.*\.jar$", n)]
        if len(jars) != 1:
            sys.exit(f"expected one game jar in {a.portable}, found {jars}")
        build = re.match(r"bin/game-headed-(.*)\.jar$", jars[0]).group(1)
    with zipfile.ZipFile(a.map_zip) as s:
        xml = s.read("flames_and_steel/map/games/flames_and_steel.xml").decode("utf-8")
    m = re.search(r"<info\b[^>]*\bversion\s*=\s*[\"']([^\"']+)", xml)
    map_version = m.group(1) if m else "unknown"

    release = f"draft {a.draft}"
    repo = a.repo_url.rstrip("/")
    values = {
        "RELEASE": release,
        "BUILD": build,
        "MAP_VERSION": map_version,
        "RELEASES_URL": repo + "/releases",
        "ISSUES_URL": repo + "/issues",
        "REPO_URL": repo,
        "NETWORK_ID": a.network_id,
    }
    top = f"FlamesAndSteel-draft{a.draft}/"
    a.out.mkdir(parents=True, exist_ok=True)
    out = a.out / f"FlamesAndSteel-draft{a.draft}.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        add_dir(z, top)
        add_dir(z, top + "game")
        n_game = copy_zip(z, a.portable, top + "game/")
        n_map = copy_zip(z, a.map_zip, top + "flames_and_steel/", strip="flames_and_steel/")
        add_bytes(
            z,
            top + "Run Flames and Steel.bat",
            text(HERE / "Run Flames and Steel.bat", "\r\n", {}),
            0o755,
        )
        add_bytes(
            z,
            top + "Run Flames and Steel.command",
            text(HERE / "Run Flames and Steel.command", "\n", {}),
            0o755,
        )
        add_bytes(
            z,
            top + "run-flames-and-steel.sh",
            text(HERE / "run-flames-and-steel.sh", "\n", {}),
            0o755,
        )
        add_bytes(z, top + "README.md", text(HERE / "README.md", "\n", values), 0o644)
        add_bytes(z, top + "LICENSE", (ROOT / "LICENSE").read_bytes(), 0o644)
        add_bytes(
            z,
            top + "SOURCE.txt",
            (
                "Flames and Steel runs on a modified version of TripleA (GNU GPL v3, see LICENSE).\n"
                f"The modified source code for game build {build} is at:\n{repo}\n"
            ).encode("utf-8"),
            0o644,
        )
    print(f"{out}  (build {build}, map {map_version}; {n_game} game files, {n_map} map files)")


if __name__ == "__main__":
    main()
