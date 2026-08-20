"""Desktop launcher: starts uvicorn on a background thread, then opens a
native window pointed at it. Run `python app.py --web` to skip the window and
just use a browser (handy while iterating on CSS).
"""

from __future__ import annotations

import argparse
import os
import socket
import sys
import threading
import time
from pathlib import Path


def _fix_missing_streams() -> None:
    """Dar streams reais a uma build --windowed.

    Um executavel sem consola arranca com sys.stdout e sys.stderr a None, e
    qualquer print() ou biblioteca que lhes toque rebenta com
    'NoneType' object has no attribute ...'. Tem de acontecer antes de
    importar seja o que for que escreva para o ecra.
    """
    for name in ("stdout", "stderr"):
        if getattr(sys, name, None) is None:
            setattr(sys, name, open(os.devnull, "w", encoding="utf-8"))


_fix_missing_streams()

import uvicorn  # noqa: E402 - depois de _fix_missing_streams, ver acima

from backend.main import app                    # noqa: E402
from backend.resources import resource_path     # noqa: E402

HOST = "127.0.0.1"

# Icone da janela nativa. Tem de ser .ico: o winforms passa o caminho ao
# construtor Icon do .NET, que rejeita PNG. Gerado do Simbolo_LART.png por
# scratchpad/make_icon.py, com os tamanhos de 16 a 256.
ICON = resource_path("frontend", "pics", "lart.ico")

# Identidade da app para a barra de tarefas do Windows.
APP_ID = "LART.BMS_UI"


def claim_taskbar_identity() -> None:
    """Desligar o botao da barra de tarefas do python.exe.

    Sem isto o Windows agrupa a janela debaixo do executavel que a lancou e
    mostra o icone do Python, por muito que a janela tenha o seu proprio. O
    AppUserModelID tem de ser declarado ANTES de a janela existir -- depois
    disso o botao ja foi criado e mantem a identidade herdada.
    """
    if not sys.platform.startswith("win"):
        return
    try:
        import ctypes

        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_ID)
    except Exception:  # noqa: BLE001 - um icone errado nao justifica falhar o arranque
        pass


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
    # log_config=None: a configuracao por omissao do uvicorn monta formatadores
    # coloridos que perguntam sys.stdout.isatty() ao serem construidos, e numa
    # build sem consola isso rebenta antes de a app chegar a arrancar. O
    # logging desta app ja e nosso (backend/logbuffer.py, no root logger), por
    # isso nao ha nada a perder em nao deixar o uvicorn configurar o seu.
    config = uvicorn.Config(app, host=HOST, port=port, log_level="warning", log_config=None)
    server = uvicorn.Server(config)

    if args.web:
        print(f"BMS UI -> http://{HOST}:{port}")
        server.run()
        return

    threading.Thread(target=server.run, daemon=True).start()
    if not wait_for_server(port):
        raise SystemExit("servidor nao arrancou")

    import webview

    claim_taskbar_identity()

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
