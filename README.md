# BMS_UI

Interface de monitorização do acumulador.

![Ecrã de ligação](frontend/pics/ui1.png)

## Run

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
compile.py             gera o exe (PyInstaller) - tudo o que sai vai pra compile/
backend/
  cars/                escolha carro
    models.py          3d (CarProfile, Hotspot, CameraView...)
    common.py          o que os 3 carros partilham: barramentos, comandos
    tek26e.py          um ficheiro por carro, muda DBC e modelos 3d
  state.py             modelo que a UI consome
  simulator.py         self explanatory
  manager.py           liga/desliga transporte, corre o loop, faz broadcast
  main.py              FastAPI: /api/scan, /api/connect, /ws
  logbuffer.py         logs para a consola in-app
  dbcstore.py          vai buscar as DBCs: local, cache, incluida, download
  dbc/                 DBCs commitadas - pra funcionar sem net
  decode/
    t26.py             DBC
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
  js/bms-settings.js   TODOS os valores default e textos da config
  js/config.js         pagina de configuracao do bms (bloqueia em HV_ON)
  js/curves.js         graficos das curvas da config
  js/panels.js         graficos de barras e tabelas
  js/preload.js        carrega os GLBs todos antes de arrancar
  js/console.js        consola in-app
  js/api.js            fetch + websocket
  js/chart.js          sparkline sem dependências - merdas feitas pelo claudio, fazer melhor tho
  pics/                pics bitch
  models/              GLBs - 3d models
  fonts/               fonts
```
