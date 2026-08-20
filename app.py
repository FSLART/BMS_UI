"""Desktop launcher: starts uvicorn on a background thread, then opens a
native window pointed at it. Run `python app.py --web` to skip the window and
just use a browser (handy while iterating on CSS).
"""

from __future__ import annotations

import argparse
import socket
import threading
import time
from pathlib import Path

import uvicorn

from backend.main import app

HOST = "127.0.0.1"

# Icone da janela nativa. Tem de ser .ico: o winforms passa o caminho ao
# construtor Icon do .NET, que rejeita PNG. Gerado do Simbolo_LART.png por
# scratchpad/make_icon.py, com os tamanhos de 16 a 256.
ICON = Path(__file__).resolve().parent / "frontend" / "pics" / "lart.ico"


def free_port() -> int:
    with socket.socket() as s:
        s.bind((HOST, 0))
        return int(s.getsockname()[1])


def wait_for_server(port: int, timeout: float = 15.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket() as s:
            s.settimeout(0.25)
            if s.connect_ex((HOST, port)) == 0:
                return True
        time.sleep(0.1)
    return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--web", action="store_true", help="serve only, no native window")
    parser.add_argument("--port", type=int, default=0, help="0 = pick a free port")
    args = parser.parse_args()

    port = args.port or (8000 if args.web else free_port())
    config = uvicorn.Config(app, host=HOST, port=port, log_level="warning")
    server = uvicorn.Server(config)

    if args.web:
        print(f"BMS UI -> http://{HOST}:{port}")
        server.run()
        return

    threading.Thread(target=server.run, daemon=True).start()
    if not wait_for_server(port):
        raise SystemExit("servidor nao arrancou")

    import webview

    webview.create_window(
        "BMS UI",
        f"http://{HOST}:{port}",
        width=1600,
        height=980,
        min_size=(1200, 760),
        background_color="#0B0F14",
    )
    # Um icone em falta nao vale rebentar com a app: sem ele fica o do Python.
    webview.start(icon=str(ICON) if ICON.is_file() else None)
    server.should_exit = True


if __name__ == "__main__":
    main()
