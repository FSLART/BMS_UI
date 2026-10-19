#!/usr/bin/env python3
"""LART BMS Telemetry Relay Server.

Lightweight, high-performance WebSocket & HTTP relay server for the FSLART BMS.
Allows a single person connected to the car via Bluetooth Low Energy (RN4871)
to forward live telemetry to this server, while all other team members can
receive and view the live telemetry stream over Wi-Fi / 4G / 5G.

Usage:
    python3 telemetry_server.py [--host 0.0.0.0] [--port 8000]

Dependencies:
    pip install fastapi uvicorn websockets
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import time
from typing import Any, Set

from fastapi import FastAPI, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("bms_relay")

app = FastAPI(title="LART BMS Telemetry Server", version="1.0.0")

# Enable CORS so web dashboards or tools can connect from anywhere
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class TelemetryHub:
    def __init__(self) -> None:
        self.clients: Set[WebSocket] = set()
        self.latest_frame: str | None = None
        self.latest_frame_dict: dict[str, Any] | None = None
        self.latest_timestamp: float = 0.0
        self.total_frames_received: int = 0
        self.source_ip: str | None = None
        self.lock = asyncio.Lock()

    async def register(self, ws: WebSocket) -> None:
        await ws.accept()
        async with self.lock:
            self.clients.add(ws)
            # Immediately send the latest cached frame if available, so client has no delay
            if self.latest_frame:
                try:
                    await ws.send_text(self.latest_frame)
                except Exception:
                    pass
        logger.info(f"Client connected. Active clients: {len(self.clients)}")

    async def unregister(self, ws: WebSocket) -> None:
        async with self.lock:
            self.clients.discard(ws)
        logger.info(f"Client disconnected. Active clients: {len(self.clients)}")

    async def broadcast_frame(self, frame_text: str, source: str = "unknown") -> int:
        frame_text = frame_text.strip()
        if not frame_text:
            return 0

        now = time.time()
        parsed_dict = None
        try:
            parsed_dict = json.loads(frame_text)
        except Exception:
            pass

        async with self.lock:
            self.latest_frame = frame_text
            self.latest_frame_dict = parsed_dict
            self.latest_timestamp = now
            self.total_frames_received += 1
            self.source_ip = source
            active_clients = list(self.clients)

        # Broadcast to all clients concurrently
        if active_clients:
            dead_clients = []
            for client in active_clients:
                try:
                    await client.send_text(frame_text)
                except Exception:
                    dead_clients.append(client)

            if dead_clients:
                async with self.lock:
                    for dc in dead_clients:
                        self.clients.discard(dc)

        return len(active_clients)


hub = TelemetryHub()


# ---------------------------------------------------------------------------
# WebSocket Endpoint (Bidirectional: Gateway sends, Viewers receive)
# ---------------------------------------------------------------------------
@app.websocket("/ws/telemetry")
async def websocket_telemetry_endpoint(ws: WebSocket) -> None:
    """Unified WebSocket endpoint:
    - Gateway phone sends telemetry lines here.
    - Viewer phones receive telemetry lines from here.
    """
    await hub.register(ws)
    client_ip = ws.client.host if ws.client else "unknown"
    try:
        while True:
            data = await ws.receive_text()
            # If a client sends data, they are acting as the gateway (broadcaster)
            if data and data.strip():
                await hub.broadcast_frame(data, source=client_ip)
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        await hub.unregister(ws)


# ---------------------------------------------------------------------------
# HTTP REST Endpoints
# ---------------------------------------------------------------------------
@app.post("/api/telemetry")
async def post_telemetry(request: Request) -> JSONResponse:
    """Accepts telemetry as raw body text or JSON from Gateway phone."""
    try:
        body = await request.body()
        text = body.decode("utf-8").strip()
        if not text:
            return JSONResponse(status_code=400, content={"ok": False, "error": "Empty body"})

        client_ip = request.client.host if request.client else "unknown"
        recipients = await hub.broadcast_frame(text, source=client_ip)
        return JSONResponse(content={"ok": True, "recipients": recipients, "total": hub.total_frames_received})
    except Exception as e:
        logger.error(f"Error handling POST /api/telemetry: {e}")
        return JSONResponse(status_code=500, content={"ok": False, "error": str(e)})


@app.get("/api/telemetry")
async def get_latest_telemetry() -> Response:
    """Returns the most recent telemetry frame."""
    if hub.latest_frame is None:
        return JSONResponse(status_code=404, content={"ok": False, "error": "No telemetry data yet"})

    age = time.time() - hub.latest_timestamp
    if hub.latest_frame_dict:
        return JSONResponse(content={
            "ok": True,
            "age_seconds": round(age, 2),
            "data": hub.latest_frame_dict,
        })
    return Response(content=hub.latest_frame, media_type="application/json")


@app.get("/health")
@app.get("/api/status")
async def get_status() -> dict[str, Any]:
    """Healthcheck and diagnostic stats."""
    now = time.time()
    age = (now - hub.latest_timestamp) if hub.latest_timestamp > 0 else None
    return {
        "status": "online",
        "service": "LART BMS Telemetry Relay",
        "active_clients": len(hub.clients),
        "total_frames_received": hub.total_frames_received,
        "last_frame_seconds_ago": round(age, 1) if age is not None else None,
        "is_car_streaming": age is not None and age < 5.0,
        "gateway_ip": hub.source_ip,
    }


@app.get("/", response_class=HTMLResponse)
async def get_dashboard() -> str:
    """Live web dashboard for browser viewing."""
    return """<!DOCTYPE html>
<html lang="pt">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>LART BMS — Servidor de Telemetria</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, sans-serif; }
        body { background: #0D0F12; color: #F0F2F5; padding: 24px; display: flex; flex-direction: column; align-items: center; min-height: 100vh; }
        .container { max-width: 720px; width: 100%; }
        .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; border-bottom: 1px solid #23272F; padding-bottom: 16px; }
        .title { font-size: 20px; font-weight: 800; color: #FFD60A; letter-spacing: 0.5px; }
        .subtitle { font-size: 13px; color: #8B949E; margin-top: 4px; }
        .card { background: #161A22; border: 1px solid #2B313A; border-radius: 12px; padding: 20px; margin-bottom: 16px; }
        .badge { display: inline-block; padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: bold; }
        .badge-live { background: rgba(50, 215, 75, 0.15); color: #32D74B; border: 1px solid #32D74B; }
        .badge-waiting { background: rgba(255, 214, 10, 0.15); color: #FFD60A; border: 1px solid #FFD60A; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-top: 14px; }
        .kpi { background: #0D0F12; border: 1px solid #23272F; border-radius: 8px; padding: 12px; }
        .kpi-label { font-size: 11px; color: #8B949E; font-weight: 600; text-transform: uppercase; }
        .kpi-val { font-size: 22px; font-weight: bold; color: #FFF; margin-top: 4px; font-family: monospace; }
        pre { background: #090B0E; border: 1px solid #23272F; border-radius: 8px; padding: 14px; font-size: 11px; color: #38BDF8; overflow-x: auto; max-height: 250px; }
        .endpoint-box { font-family: monospace; background: #0D0F12; padding: 8px 12px; border-radius: 6px; border: 1px solid #2B313A; color: #FFD60A; font-size: 12px; margin-top: 6px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div>
                <div class="title">⚡ FORMULA STUDENT LART — BMS RELAY</div>
                <div class="subtitle">Servidor de reencaminhamento Wi-Fi / 4G para a aplicação Android</div>
            </div>
            <div id="liveBadge" class="badge badge-waiting">À ESPERA DO CARRO</div>
        </div>

        <div class="card">
            <h3 style="font-size: 14px; color: #C9D1D9;">ESTADO DA TRANSMISSÃO</h3>
            <div class="grid">
                <div class="kpi">
                    <div class="kpi-label">Dispositivos a Ver</div>
                    <div class="kpi-val" id="activeClients">0</div>
                </div>
                <div class="kpi">
                    <div class="kpi-label">Total de Pacotes</div>
                    <div class="kpi-val" id="totalPackets">0</div>
                </div>
                <div class="kpi">
                    <div class="kpi-label">Último Pacote</div>
                    <div class="kpi-val" id="lastSeen">—</div>
                </div>
            </div>
        </div>

        <div class="card">
            <h3 style="font-size: 14px; color: #C9D1D9; margin-bottom: 8px;">ENDEREÇO PARA A APP ANDROID</h3>
            <p style="font-size: 12px; color: #8B949E;">No ecrã de ligação da app Android, usa o seguinte endereço WebSocket:</p>
            <div class="endpoint-box" id="wsEndpoint">ws://...</div>
        </div>

        <div class="card">
            <h3 style="font-size: 14px; color: #C9D1D9; margin-bottom: 8px;">ÚLTIMO PACOTE RECEBIDO DO BMS</h3>
            <pre id="rawTelemetry">Nenhum dado recebido ainda...</pre>
        </div>
    </div>

    <script>
        const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${wsProto}//${location.host}/ws/telemetry`;
        document.getElementById('wsEndpoint').textContent = wsUrl;

        let sock = null;
        function connectWs() {
            sock = new WebSocket(wsUrl);
            sock.onmessage = (event) => {
                document.getElementById('rawTelemetry').textContent = event.data;
            };
            sock.onclose = () => { setTimeout(connectWs, 2000); };
        }
        connectWs();

        async function pollStatus() {
            try {
                const res = await fetch('/api/status');
                const data = await res.json();
                document.getElementById('activeClients').textContent = data.active_clients;
                document.getElementById('totalPackets').textContent = data.total_frames_received;

                const badge = document.getElementById('liveBadge');
                if (data.is_car_streaming) {
                    badge.className = 'badge badge-live';
                    badge.textContent = '🟢 CARRO A TRANSMITIR';
                    document.getElementById('lastSeen').textContent = `${data.last_frame_seconds_ago}s atrás`;
                } else {
                    badge.className = 'badge badge-waiting';
                    badge.textContent = '🟡 À ESPERA DO CARRO';
                    document.getElementById('lastSeen').textContent = data.last_frame_seconds_ago ? `${data.last_frame_seconds_ago}s atrás` : '—';
                }
            } catch (e) {}
        }
        setInterval(pollStatus, 1500);
        pollStatus();
    </script>
</body>
</html>"""


def main() -> None:
    parser = argparse.ArgumentParser(description="LART BMS Telemetry Relay Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host interface (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="Port (default: 8000)")
    args = parser.parse_args()

    import uvicorn
    print(f"\n=======================================================")
    print(f"  LART BMS Telemetry Relay Server")
    print(f"  Listening on: http://{args.host}:{args.port}")
    print(f"  WebSocket:    ws://{args.host}:{args.port}/ws/telemetry")
    print(f"=======================================================\n")
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
