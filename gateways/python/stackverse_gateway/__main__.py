from __future__ import annotations

import uvicorn

from .app import build_app
from .config import load_config


def run() -> None:
    config = load_config()
    uvicorn.run(build_app(config=config), host="0.0.0.0", port=config.port, access_log=False)


if __name__ == "__main__":
    run()
