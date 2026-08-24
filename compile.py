"""Gera o executavel com PyInstaller.

    python compile.py                # e so isto: onefile, sem consola, limpo
    python compile.py --onedir       # pasta em vez de ficheiro unico, arranca muito mais depressa
    python compile.py --console      # deixa a consola aberta, para ver erros de arranque
    python compile.py --keep         # nao limpa build/ antes (mais rapido a iterar)

Tudo o que a compilacao gera fica em `compile/`, fora do codigo:

    compile/dist/BMS_UI.exe    o executavel a distribuir
    compile/build/             ficheiros intermedios do PyInstaller
    compile/BMS_UI.spec        a receita gerada

A pasta inteira e descartavel: apagar `compile/` nao perde nada que este
script nao volte a gerar.

Ao contrario de um script solto, esta app leva dados atras: o frontend inteiro
(HTML, CSS, JS, fontes, imagens e os GLB), as DBCs incluidas e o icone. Todos
sao empacotados no MESMO caminho relativo que tem no repositorio, para que o
`backend/resources.py` os encontre da mesma maneira nos dois casos.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NAME = "BMS_UI"
ENTRY = ROOT / "app.py"
ICON = ROOT / "frontend" / "pics" / "lart.ico"

# Tudo o que a compilacao produz fica aqui dentro, para nao ficar misturado com
# o codigo na raiz do projeto. O PyInstaller aceita as tres pastas separadas:
# --distpath (o resultado), --workpath (intermedios) e --specpath (a receita).
OUT = ROOT / "compile"
DIST = OUT / "dist"
WORK = OUT / "build"

# Pastas levadas inteiras. `frontend/models` NAO esta aqui de proposito: ao
# lado dos GLB usados vivem copias de trabalho (os `.pre-anim`, de antes de a
# animacao das ventoinhas ser gravada no modelo), e leva-las duplicaria os
# 30 MB de 3D dentro do executavel. Os modelos vao um a um, escolhidos pelos
# perfis -- ver model_files().
DATA = [
    ("frontend/css", "frontend/css"),
    ("frontend/js", "frontend/js"),
    ("frontend/fonts", "frontend/fonts"),
    ("frontend/pics", "frontend/pics"),
    ("backend/dbc", "backend/dbc"),
]

FILES = [
    ("frontend/index.html", "frontend"),
]


def model_files() -> list[tuple[str, str]]:
    """Os GLB que algum carro disponivel referencia, e mais nenhum.

    Lido dos perfis para nao haver uma segunda lista a envelhecer: um carro
    novo traz os seus modelos para dentro da build sem tocar aqui. Sao os
    quatro campos de modelo do perfil -- fechado, aberto, no carregador e o
    segmento generico -- porque falta qualquer um deles e essa vista fica em
    placeholder sem dizer porque.
    """
    sys.path.insert(0, str(ROOT))
    from backend.cars import CARS

    out: list[tuple[str, str]] = []
    seen: set[str] = set()
    for car in CARS:
        if not car.available:
            continue
        for ref in (car.model_closed, car.model_open,
                    car.model_charger, car.model_segment):
            # Os perfis guardam caminhos servidos ("/models/x.glb").
            rel = f"frontend{ref}" if ref.startswith("/") else ref
            if not ref or rel in seen:
                continue
            seen.add(rel)
            if (ROOT / rel).is_file():
                out.append((rel, str(Path(rel).parent).replace("\\", "/")))
            else:
                print(f"  aviso: {rel} nao existe, o 3D desse carro fica em placeholder")
    return out

# Pacotes cujos dados/plugins o PyInstaller nao descobre sozinho.
#   can       - as interfaces (slcan, vector, ...) sao carregadas por nome
#   cantools  - idem para os formatos de base de dados
#   uvicorn   - loops e protocolos tambem resolvidos por string
#   webview   - o backend nativo por plataforma (winforms no Windows)
COLLECT = ["can", "cantools", "uvicorn", "webview"]

HIDDEN = [
    "serial.tools.list_ports",
    # O FastAPI puxa isto de forma dinamica.
    "email.mime.multipart",
]

# Pesado, so usado por transportes que ainda nao estao implementados. Sai do
# executavel ate o BLE existir a serio; tirar daqui quando isso acontecer.
EXCLUDE = ["bleak", "tkinter", "matplotlib", "PIL", "pytest"]


def missing_pieces() -> list[str]:
    """Coisas sem as quais a build sai partida e so se percebe ao executar."""
    problems = []
    if not ENTRY.is_file():
        problems.append(f"falta o ponto de entrada: {ENTRY}")
    if not ICON.is_file():
        problems.append(f"falta o icone: {ICON} (corre scratchpad/make_icon.py)")
    for src, _ in DATA:
        if not (ROOT / src).exists():
            problems.append(f"falta a pasta de dados: {src}")

    models = ROOT / "frontend" / "models"
    if models.is_dir() and not list(models.glob("*.glb")):
        problems.append("frontend/models nao tem nenhum .glb - o 3D vai ficar em placeholder")

    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        problems.append("PyInstaller nao instalado (pip install pyinstaller)")
    return problems


def build_command(onefile: bool, console: bool) -> list[str]:
    sep = ";" if sys.platform.startswith("win") else ":"
    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm",
        "--onefile" if onefile else "--onedir",
        "--console" if console else "--windowed",
        "--name", NAME,
        "--icon", str(ICON),
        "--distpath", str(DIST),
        "--workpath", str(WORK),
        "--specpath", str(OUT),
    ]
    for src, dest in DATA + FILES + model_files():
        cmd += ["--add-data", f"{ROOT / src}{sep}{dest}"]
    for pkg in COLLECT:
        cmd += ["--collect-all", pkg]
    for mod in HIDDEN:
        cmd += ["--hidden-import", mod]
    for mod in EXCLUDE:
        cmd += ["--exclude-module", mod]
    cmd.append(str(ENTRY))
    return cmd


def main() -> int:
    parser = argparse.ArgumentParser(description="Compila o BMS_UI num executavel.")
    parser.add_argument("--onedir", action="store_true",
                        help="pasta em vez de ficheiro unico: arranca muito mais rapido")
    parser.add_argument("--console", action="store_true",
                        help="mantem a consola visivel, para ver erros de arranque")
    # Limpar por omissao: uma build incremental por cima de outra ja empacotou
    # frontend velho dentro de executavel novo, e isso descobre-se tarde e mal.
    # Correr sem argumentos tem de dar sempre a build boa.
    parser.add_argument("--keep", action="store_true",
                        help="nao apagar build/ e dist/ antes (mais rapido a iterar)")
    args = parser.parse_args()

    problems = missing_pieces()
    if problems:
        print("Nao da para compilar:")
        for p in problems:
            print(f"  - {p}")
        return 1

    if not args.keep:
        shutil.rmtree(OUT, ignore_errors=True)
        print(f"{OUT.name}/ apagado")
    OUT.mkdir(exist_ok=True)

    cmd = build_command(onefile=not args.onedir, console=args.console)
    print(" ".join(cmd), "\n")

    started = time.time()
    result = subprocess.run(cmd, cwd=ROOT)
    if result.returncode != 0:
        print(f"\nPyInstaller falhou (codigo {result.returncode})")
        return result.returncode

    exe = f"{NAME}.exe" if sys.platform.startswith("win") else NAME
    out = DIST / exe if not args.onedir else DIST / NAME / exe
    took = time.time() - started
    if out.is_file():
        size = out.stat().st_size / 1048576
        print(f"\nPronto em {took:.0f}s -> {out}  ({size:.0f} MB)")
    else:
        # Nao devia acontecer com returncode 0, mas se acontecer e melhor
        # dizer que nao se sabe onde esta do que apontar para um caminho falso.
        print(f"\nPyInstaller acabou em {took:.0f}s mas {out} nao existe")
        return 1

    print(
        "\nAntes de distribuir, abre o executavel e confirma:\n"
        "  - a janela abre com o simbolo da LART na barra de tarefas\n"
        "  - o ecra de carregamento chega aos 100% (frontend e GLB incluidos)\n"
        "  - a consola in-app diz 'DBC carregada' sem rede (DBCs incluidas)\n"
        "  - o scan de portas CAN encontra as COM (pyserial incluido)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
