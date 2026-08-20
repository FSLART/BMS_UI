# BMS_UI

Interface de monitorização do acumulador.

![Ecrã de ligação](frontend/pics/ui1.png)

## Correr

```bash
pip install -r requirements.txt
```

App:

```bash
python app.py
```

Apenas o servido:

```bash
python app.py --web --port 8123
```

Sem hardware ligado: deixa o **Modo demo** ligado no ecrã de connect.

## Tree

```
app.py                 launch: uvicorn + pywebview
backend/
  cars.py              esclha carrio: DBC, topologia, limites, GLBs
  state.py             modelo que a UI consome
  simulator.py         self explanatory
  manager.py           liga/desliga transporte, corre o loop, faz broadcast
  main.py              FastAPI: /api/scan, /api/connect, /ws
  logbuffer.py         logs para a consola in-app
  dbcstore.py          vai buscar as DBCs: local, cache, incluida, download
  dbc/                 DBCs commitadas - pra funcionar sem net
  decode/
    t26.py             DBC -> state universal (AMS, IVT, carregador)
  transports/
    base.py            interface Transport: stream de RawFrame
    discovery.py       scan de portas série, interfaces CAN, BLE, redes WiFi
    can_bus.py         python-can: thread de leitura + drain por tick
    DBC_T26.md         mapa do barramento e bugs conhecidos da DBC
frontend/
  index.html           três screens: carro, conn e dashboard
  css/app.css          design + layout
  js/cars.js           seleção do carro
  js/connect.js        connection methods
  js/viewer.js         wrapper do <model-viewer>
  js/dashboard.js      info lol
  js/panels.js         graficos de barras e tabelas
  js/preload.js        carrega os GLBs todos antes de arrancar
  js/console.js        consola in-app
  js/api.js            fetch + websocket
  js/chart.js          sparkline sem dependências - merdas feitas pelo claudio, fazer melhor tho
  pics/                pics bitch
  models/              GLBs - 3d models
  fonts/               Poppins + Source Sans 3, locais
```
