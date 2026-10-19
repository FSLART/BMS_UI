# Servidor de Telemetria LART BMS (Ubuntu / stocklart.pt)

Este servidor permite que um membro da equipa ligado ao carro por **Bluetooth** reencaminhe a telemetria em tempo real para toda a equipa aceder via **Wi-Fi / 4G / 5G** na aplicação Android.

---

## 🚀 Instalação Rápida no Ubuntu (`stocklart.pt`)

### 1. Instalar dependências Python
```bash
sudo apt update
sudo apt install -y python3 python3-pip
pip3 install -r requirements.txt
```

### 2. Testar o servidor manualmente
```bash
python3 telemetry_server.py --port 8000
```
Podes abrir no navegador em `http://stocklart.pt:8000` para ver o painel com o estado da telemetria e o número de clientes ligados.

---

## ⚙️ Configurar para Iniciar Automaticamente com o Ubuntu (Systemd)

Para que o servidor fique a correr 24/7 e arranque sozinho se o servidor reiniciar:

```bash
# 1. Criar pasta no servidor e copiar ficheiros
sudo mkdir -p /opt/bms_server
sudo cp telemetry_server.py requirements.txt /opt/bms_server/

# 2. Copiar e ativar o serviço systemd
sudo cp bms-relay.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable bms-relay
sudo systemctl start bms-relay

# 3. Verificar se está ativo
sudo systemctl status bms-relay
```

---

## 🔒 Configuração com Nginx / SSL (Opcional - Recomendado para `wss://bms.stocklart.pt`)

Se já tens o Nginx configurado com certificado SSL (Let's Encrypt / Certbot), podes adicionar este bloco ao teu virtualhost para ter WebSocket seguro (`wss://`):

```nginx
server {
    server_name bms.stocklart.pt;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 86400;
    }
}
```

Desta forma, na aplicação Android podes ligar diretamente a:
- **`ws://stocklart.pt:8000/ws/telemetry`** (porta direta)
- ou **`wss://bms.stocklart.pt/ws/telemetry`** (com SSL/Nginx)
