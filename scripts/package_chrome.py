import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit
from zipfile import ZIP_DEFLATED, ZipFile


def main():
    parser = argparse.ArgumentParser(description="Package the Bili CC Chrome extension")
    parser.add_argument("--server", default="", help="Preset HTTPS service URL")
    args = parser.parse_args()
    try:
        parts = urlsplit(args.server)
        if args.server and (
            parts.scheme != "https" or not parts.hostname or parts.username is not None
            or parts.password is not None or parts.path not in {"", "/"} or parts.query
            or parts.fragment or (parts.port is not None and not 1 <= parts.port <= 65535)
            or any(char.isspace() for char in args.server)
        ):
            raise ValueError
    except ValueError:
        parser.error("--server must be an HTTPS origin, optionally including a port.")

    project = Path(__file__).resolve().parent.parent
    source = project / "extension"
    output = project / "dist" / "bili-cc-chrome"
    output.mkdir(parents=True, exist_ok=True)
    target = output.with_suffix(".zip")
    files = sorted(path for path in source.iterdir() if path.is_file() and not path.name.startswith("."))
    for name in ("options.html", "options.js", "options.css"):
        (output / name).unlink(missing_ok=True)
    with ZipFile(target, "w", ZIP_DEFLATED) as archive:
        for path in files:
            content = path.read_bytes()
            if path.name == "default-server.js":
                content = f"export const DEFAULT_SERVER = {json.dumps(args.server)};\n".encode()
            if path.name == "manifest.json" and args.server:
                manifest = json.loads(content)
                hostname = f"[{parts.hostname}]" if ":" in parts.hostname else parts.hostname
                manifest["host_permissions"] = [f"https://{hostname}/*"]
                content = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
            (output / path.name).write_bytes(content)
            archive.writestr(path.name, content)
    print(output)
    print(target)


if __name__ == "__main__":
    main()
