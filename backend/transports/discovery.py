"""Device discovery for the connection screen.

Every scanner degrades gracefully: if the optional library is missing or the
hardware is absent, it returns an empty list plus a human-readable hint instead
of blowing up. The UI stays usable on a laptop with nothing plugged in.
"""

from __future__ import annotations

import asyncio
import re
import subprocess
import sys
from typing import Any

# Values offered in the dropdowns. Not discovered -- just sensible defaults.
UART_BAUDS = [9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600]
CAN_BITRATES = [125_000, 250_000, 500_000, 1_000_000]
CAN_BACKENDS = ["slcan", "vector"]


def _result(items: list[dict[str, Any]], hint: str = "") -> dict[str, Any]:
    return {"items": items, "hint": hint}


# ---------------------------------------------------------------------------
# Serial / UART
# ---------------------------------------------------------------------------

async def scan_serial() -> dict[str, Any]:
    try:
        from serial.tools import list_ports
    except ImportError:
        return _result([], "pyserial nao instalado (pip install pyserial)")

    def _blocking() -> list[dict[str, Any]]:
        out = []
        for p in list_ports.comports():
            label = p.description or p.device
            out.append({
                "value": p.device,
                "label": f"{p.device} - {label}",
                "detail": p.hwid or "",
            })
        return out

    items = await asyncio.to_thread(_blocking)
    return _result(items, "" if items else "Nenhuma porta serie detetada")


# ---------------------------------------------------------------------------
# CAN
# ---------------------------------------------------------------------------

async def scan_can(backend: str = "slcan") -> dict[str, Any]:
    """Channels for one CAN backend.

    The two backends look for completely different things: an slcan adapter is a
    serial port, a Vector channel comes from the vendor driver. Scanning them the
    same way would offer channels the chosen backend cannot open.
    """
    if backend == "slcan":
        res = await scan_serial()
        return _result(res["items"], res["hint"] or "" if res["items"]
                       else "Nenhum adaptador slcan (porta serie) detetado")

    if backend == "vector":
        try:
            import can
        except ImportError:
            return _result([], "python-can nao instalado (pip install python-can)")

        def _blocking() -> list[dict[str, Any]]:
            out = []
            try:
                for cfg in can.detect_available_configs(interfaces=["vector"]):
                    channel = str(cfg.get("channel", "?"))
                    out.append({
                        "value": channel,
                        "label": f"Vector - canal {channel}",
                        "detail": ", ".join(f"{k}={v}" for k, v in cfg.items()),
                    })
            except Exception as exc:  # noqa: BLE001 - vendor drivers throw anything
                out.append({"value": "", "label": f"erro no scan: {exc}", "detail": ""})
            return out

        items = await asyncio.to_thread(_blocking)
        return _result(items, "" if items else "Nenhum canal Vector detetado (driver XL instalado?)")

    return _result([], f"Backend CAN desconhecido: {backend}")


# ---------------------------------------------------------------------------
# Bluetooth LE
# ---------------------------------------------------------------------------

async def scan_ble(timeout: float = 5.0) -> dict[str, Any]:
    try:
        from bleak import BleakScanner
    except ImportError:
        return _result([], "bleak nao instalado (pip install bleak)")

    try:
        devices = await BleakScanner.discover(timeout=timeout)
    except Exception as exc:  # noqa: BLE001 - adapter off / no permission
        return _result([], f"scan BLE falhou: {exc}")

    items = [
        {
            "value": d.address,
            "label": d.name or "(sem nome)",
            "detail": d.address,
        }
        for d in devices
    ]
    items.sort(key=lambda i: (i["label"].startswith("("), i["label"].lower()))
    return _result(items, "" if items else "Nenhum dispositivo BLE encontrado")


# ---------------------------------------------------------------------------
# WiFi
# ---------------------------------------------------------------------------

async def scan_wifi() -> dict[str, Any]:
    if not sys.platform.startswith("win"):
        return _result([], "scan de redes so implementado em Windows; usa host/porta")

    def _blocking() -> list[dict[str, Any]]:
        try:
            raw = subprocess.run(
                ["netsh", "wlan", "show", "networks", "mode=bssid"],
                capture_output=True,
                text=True,
                timeout=15,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            ).stdout
        except Exception as exc:  # noqa: BLE001
            return [{"value": "", "label": f"netsh falhou: {exc}", "detail": ""}]

        out: list[dict[str, Any]] = []
        current: str | None = None
        for line in raw.splitlines():
            ssid = re.match(r"\s*SSID\s+\d+\s*:\s*(.*)", line)
            if ssid:
                current = ssid.group(1).strip()
                if current:
                    out.append({"value": current, "label": current, "detail": ""})
                continue
            signal = re.match(r"\s*(?:Signal|Sinal)\s*:\s*(.*)", line)
            if signal and out and current:
                out[-1]["detail"] = signal.group(1).strip()
        return out

    items = await asyncio.to_thread(_blocking)
    return _result(items, "" if items else "Nenhuma rede WiFi encontrada")


SCANNERS = {
    "serial": scan_serial,
    "can": scan_can,
    "ble": scan_ble,
    "wifi": scan_wifi,
}
