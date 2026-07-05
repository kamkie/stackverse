from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Sequence
from urllib import error, parse, request


DEFAULT_BACKEND_URL = "http://localhost:8080"
DEFAULT_KEYCLOAK_URL = "http://localhost:8180"
DEFAULT_ROLE = "admin"
VALID_ROLES = {"demo", "mentor", "moderator", "admin", "none"}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run the Stackverse Schemathesis OpenAPI property suite.",
        allow_abbrev=False,
    )
    parser.add_argument("--backend-url", default=os.getenv("BACKEND_URL", DEFAULT_BACKEND_URL))
    parser.add_argument("--keycloak-url", default=os.getenv("KEYCLOAK_URL", DEFAULT_KEYCLOAK_URL))
    parser.add_argument(
        "--auth-role",
        default=os.getenv("SCHEMATHESIS_AUTH_ROLE", DEFAULT_ROLE),
        choices=sorted(VALID_ROLES),
        help="Dev Keycloak user for authenticated requests; use 'none' for anonymous-only probing.",
    )
    parser.add_argument(
        "--max-examples",
        default=os.getenv("SCHEMATHESIS_MAX_EXAMPLES", "20"),
        help="Schemathesis examples per operation.",
    )
    parser.add_argument(
        "--max-failures",
        default=os.getenv("SCHEMATHESIS_MAX_FAILURES", "5"),
        help="Stop after this many Schemathesis failures.",
    )
    parser.add_argument("--seed", default=os.getenv("SCHEMATHESIS_SEED"))
    parser.add_argument("--workers", default=os.getenv("SCHEMATHESIS_WORKERS", "1"))
    parser.add_argument("--phases", default=os.getenv("SCHEMATHESIS_PHASES", "fuzzing"))
    parser.add_argument("--mode", default=os.getenv("SCHEMATHESIS_MODE", "positive"))

    args, schemathesis_args = parser.parse_known_args(argv)

    suite_dir = Path(__file__).resolve().parents[1]
    repo_root = find_repo_root(suite_dir)
    schema_path = repo_root / "spec" / "openapi.yaml"

    token = ""
    if args.auth_role != "none":
        token = fetch_token(args.keycloak_url, args.auth_role)

    env = os.environ.copy()
    env["BACKEND_URL"] = args.backend_url
    env["KEYCLOAK_URL"] = args.keycloak_url
    env["STACKVERSE_SCHEMATHESIS_TOKEN"] = token
    env["STACKVERSE_SCHEMATHESIS_RUN_ID"] = run_id()
    env.setdefault("STACKVERSE_SCHEMATHESIS_CLEANUP", "true")
    env["PYTHONPATH"] = prepend_path(suite_dir, env.get("PYTHONPATH"))

    command = [
        resolve_schemathesis_binary(),
        "run",
        str(schema_path),
        "--url",
        args.backend_url,
        "--workers",
        str(args.workers),
        "--phases",
        args.phases,
        "--mode",
        args.mode,
        "--max-examples",
        str(args.max_examples),
        "--max-failures",
        str(args.max_failures),
    ]
    if args.seed:
        command.extend(["--seed", str(args.seed)])
    command.extend(schemathesis_args)

    print(
        "Running Schemathesis against "
        f"{args.backend_url} with auth role {args.auth_role} "
        f"(phases={args.phases}, mode={args.mode}, max_examples={args.max_examples})",
        flush=True,
    )
    completed = subprocess.run(command, cwd=suite_dir, env=env, check=False)
    return completed.returncode


def find_repo_root(start: Path) -> Path:
    for candidate in (start, *start.parents):
        if (candidate / "spec" / "openapi.yaml").is_file():
            return candidate
    raise RuntimeError(f"could not find spec/openapi.yaml above {start}")


def fetch_token(keycloak_url: str, role: str) -> str:
    endpoint = f"{keycloak_url.rstrip('/')}/realms/stackverse/protocol/openid-connect/token"
    payload = parse.urlencode(
        {
            "grant_type": "password",
            "client_id": "stackverse-conformance",
            "username": role,
            "password": role,
        }
    ).encode("utf-8")
    req = request.Request(
        endpoint,
        data=payload,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=10) as response:
            body = json.loads(response.read().decode("utf-8"))
    except error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Keycloak token request failed for {role}: HTTP {exc.code} {detail}") from exc
    except error.URLError as exc:
        raise SystemExit(f"Keycloak token request failed for {role}: {exc}") from exc

    token = body.get("access_token")
    if not isinstance(token, str) or not token:
        raise SystemExit(f"Keycloak token response for {role} did not contain access_token")
    return token


def run_id() -> str:
    return "st" + base36(int(time.time()))[-8:]


def base36(value: int) -> str:
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    if value == 0:
        return "0"
    digits: list[str] = []
    while value:
        value, remainder = divmod(value, 36)
        digits.append(alphabet[remainder])
    return "".join(reversed(digits))


def prepend_path(path: Path, existing: str | None) -> str:
    if existing:
        return str(path) + os.pathsep + existing
    return str(path)


def resolve_schemathesis_binary() -> str:
    scripts_dir = Path(sys.executable).resolve().parent
    for name in ("st.exe", "st"):
        candidate = scripts_dir / name
        if candidate.is_file():
            return str(candidate)
    binary = shutil.which("st")
    if binary is not None:
        return binary
    raise SystemExit("Schemathesis executable 'st' was not found on PATH")


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
