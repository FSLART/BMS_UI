from __future__ import annotations

import asyncio
import contextlib
from pathlib import Path
from typing import Any

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from .logbuffer import buffer as log_buffer, install as install_logging

# Before anything imports `can`: that import is where the vendor-driver probe
# messages are emitted, and we want them in the buffer.
install_logging()

from .cars import CARS                                    # noqa: E402
from .manager import SELECTABLE, manager                   # noqa: E402
from .transports import discovery                          # noqa: E402

FRONTEND = Path(__file__).resolve().parent.parent / "frontend"

app = FastAPI(title="BMS UI")


@app.on_event("startup")
async def _startup() -> None:
    app.state.watchdog = asyncio.create_task(manager.watchdog())


@app.on_event("shutdown")
async def _shutdown() -> None:
    task = getattr(app.state, "watchdog", None)
    if task:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
    await manager.disconnect()


# ---------------------------------------------------------------------------
# API
# ---------------------------------------------------------------------------

@app.get("/api/logs")
async def logs(since: int = 0) -> dict[str, Any]:
    """Entries newer than `since`. The console polls this while it is open."""
    return log_buffer.since(since)


@app.post("/api/logs/clear")
async def clear_logs() -> dict[str, Any]:
    log_buffer.clear()
    return {"ok": True}


@app.get("/api/cars")
async def cars() -> dict[str, Any]:
    """Catalogue for the car-selection screen, plus whichever is active."""
    return {
        "cars": [c.model_dump() for c in CARS],
        "selected": manager.car.id if manager.car else None,
    }


class CarRequest(BaseModel):
    id: str


@app.post("/api/car")
async def select_car(req: CarRequest) -> dict[str, Any]:
    return await manager.select_car(req.id)


@app.get("/api/options")
async def options() -> dict[str, Any]:
    """Static choices for the config dropdowns."""
    return {
        "uart_bauds": discovery.UART_BAUDS,
        "can_bitrates": discovery.CAN_BITRATES,
        "can_backends": discovery.CAN_BACKENDS,
        # The UI greys out anything not listed here; /api/connect enforces it.
        "selectable_transports": sorted(t.value for t in SELECTABLE),
    }


@app.get("/api/scan/{kind}")
async def scan(kind: str, backend: str | None = None) -> dict[str, Any]:
    scanner = discovery.SCANNERS.get(kind)
    if scanner is None:
        return {"items": [], "hint": f"scanner desconhecido: {kind}"}
    # Only the CAN scanner is backend-dependent; the rest take no arguments.
    if kind == "can":
        return await scanner(backend or "slcan")
    return await scanner()


class ConnectRequest(BaseModel):
    type: str
    config: dict[str, Any] = {}
    demo_fallback: bool = False


@app.post("/api/connect")
async def connect(req: ConnectRequest) -> dict[str, Any]:
    return await manager.connect(req.type, req.config, req.demo_fallback)


@app.post("/api/disconnect")
async def disconnect() -> dict[str, Any]:
    return await manager.disconnect()


@app.websocket("/ws")
async def ws(sock: WebSocket) -> None:
    await sock.accept()
    q = manager.subscribe()
    await sock.send_text(manager.state.model_dump_json())
    try:
        while True:
            await sock.send_text(await q.get())
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        manager.unsubscribe(q)


# ---------------------------------------------------------------------------
# Static frontend (mounted last so /api and /ws win)
# ---------------------------------------------------------------------------

@app.middleware("http")
async def no_cache(request, call_next):
    """The browser happily serves a stale js/css module after an edit, which
    looks exactly like a bug that will not die. Not worth it for a local tool."""
    response = await call_next(request)
    if request.url.path.startswith(("/js/", "/css/")) or request.url.path == "/":
        response.headers["Cache-Control"] = "no-store"
    return response


@app.get("/")
async def index() -> FileResponse:
    return FileResponse(FRONTEND / "index.html")


app.mount("/", StaticFiles(directory=FRONTEND), name="static")
