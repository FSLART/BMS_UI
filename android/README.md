# BMS Monitor Mobile (Android) — Formula Student LART

Aplicação móvel nativa Android desenvolvida em **Kotlin** e **Jetpack Compose** para monitorização em tempo real do acumulador de alta tensão da equipa de Formula Student LART, replicando e adaptando a interface desktop em Python para formato móvel (smartphones e tablets).

---

## ⚡ Prioridade: Bluetooth Low Energy (BLE)

Esta aplicação foi especificamente desenhada tendo como prioridade máxima a ligação **Bluetooth Low Energy (BLE)** ao módulo **Microchip RN4871**, ligado diretamente ao UART2 do STM32 AMS Master a 115200 baud.

### Especificação de Comunicação BLE

| Parâmetro | Valor / UUID | Descrição |
|---|---|---|
| **Endereço MAC Pré-configurado** | `40:84:32:61:CE:13` | Endereço de fábrica do módulo no TEK-26e (1 clique para ligar) |
| **Nomes de Anúncio Suportados** | `"RN4871"`, `"LART Accumulator"` | Filtro e priorização automática no scan |
| **Serviço GATT UART Transparente** | `49535343-fe7d-4ae5-8fa9-9fafd205e455` | Serviço UART Microchip |
| **Característica de Notificação (RX)** | `49535343-1e4d-4bd9-ba61-23c647249616` | Dispositivo → Telemóvel (Telemetria) |
| **Característica de Escrita (TX)** | `49535343-8841-43f4-a8d4-ecbe34729bb3` | Telemóvel → Dispositivo |
| **Descritor CCCD** | `00002902-0000-1000-8000-00805f9b34fb` | Ativação de notificações de dados |
| **MTU Solicitado** | `512 bytes` | Maximiza taxa de transferência e reduz fragmentação |

### Protocolo de Fragmentação & Montagem

1. O firmware envia um dump JSON por segundo (~3,1 kB) terminado por quebra de linha `\n`.
2. As notificações BLE chegam ao telemóvel em fragmentos de ~20 a 180 bytes.
3. O `BleManager` acumula os fragmentos em buffer de memória seguro (com teto máximo de 16 kB contra overflows) e corta exatamente em cada linha completa terminada em `\n`.
4. Cada linha JSON é processada pelo `LiveJsonDecoder`, garantindo coerência temporal instantânea sem dependência de ficheiros DBC externos.

---

## 📱 Estrutura e Ecrãs da Aplicação

A interface adapta o design de cockpit da aplicação desktop para ecrãs móveis e tablets:

- **Ecrã 1 — Ligação & Carro (`ConnectScreen`)**:
  - Seleção do modelo do carro (**TEK-26e** 144s3p Molicel P45B, TEK-26e EVO, T-28).
  - Botão de ligação rápida direta com 1 toque ao endereço pré-configurado `40:84:32:61:CE:13`.
  - Scanner BLE em tempo real para encontrar dispositivos próximos com indicação de RSSI.
  - Alternador para **Modo Demo (Simulador)** para desenvolvimento e demonstrações sem hardware ligado.
- **Ecrã 2 — Painel Principal (`DashboardScreen`)**:
  - **KPIs em Destaque**: Tensão do Pack (V), Corrente (A), Potência (kW), Estado de Carga SoC (%), Temperatura Máxima (°C) e Desvio entre Células (mV).
  - **Gráfico Sparkline em Tempo Real**: Traçado duplo de Corrente (amarelo) e Tensão (azul) em tempo real com auto-escala.
  - **Grelha de Segurança & AMS**: Indicadores de AMS, SDC, AIR+, AIR−, Pré-carga (HV ON), Estado da máquina de estados, PWM das ventoinhas e temperatura do MCU master.
  - **Resumo dos Segmentos**: Cartões dos 6 segmentos com tensão somada, temperaturas e estado de balanceamento.
  - **Painel de Falhas Ativas**: Alertas imediatos em caso de PEC CRC inválido, células com fio aberto (open-wire) ou sobreaquecimento.
- **Ecrã 3 — Matriz de Células (`CellsScreen`)**:
  - Visualização interativa das **144 células** em mapa de calor (verde → amarelo → vermelho) conforme a tensão.
  - Indicador animado **BAL** para células em sangramento/balanceamento ativo.
  - Alerta **ABERTO** para falhas de fio de medição de tensão.
  - Filtro rápido por Segmento (Todos, Seg 1 a Seg 6).
  - Toque em qualquer célula para abrir modal com detalhes completos (tensão ao milivolt, slave, canal, etc.).
- **Ecrã 4 — Temperaturas NTC (`ThermistorsScreen`)**:
  - Tabela organizada pelos 12 Slaves com os **72 termistores NTC**.
  - Exclusão e indicação automática de sensores danificados com bypass (ex.: NTC 3 e 4 do Slave 3 no TEK-26e).
- **Ecrã 5 — Carregamento (`ChargerScreen`)**:
  - Modo Handcart com medidor gigante de **Energia Carregada na Sessão (kWh)**.
  - Tempo de carga decorrido, tensão e corrente reais do carregador EV Europe OEM3.
- **Ecrã 6 — Consola de Diagnóstico (`ConsoleScreen`)**:
  - Registo em direto dos pacotes e linhas JSON recebidas por BLE, contagem de amostras e diagnóstico de perdas de rádio.

---

## 🛠️ Tecnologias Utilizadas

- **Linguagem**: Kotlin 2.0
- **UI Framework**: Jetpack Compose (Material 3) com suporte adaptativo a telemóveis e tablets
- **Concorrência**: Kotlin Coroutines & StateFlow (arquitetura reativa MVVM)
- **Bluetooth**: Android BluetoothGatt API nativo de baixa latência
- **Parsing JSON**: Gson com modelo tipado resiliente a linhas parciais
- **Android Target**: Android 15 (API 35), compatível com Android 8.0+ (API 26+)

---

## 🚀 Como Abrir e Compilar

### Opção 1: Android Studio (Recomendado)
1. Abre o **Android Studio**.
2. Seleciona **File > Open** e escolhe a pasta `android/` deste repositório.
3. Deixa o Gradle sincronizar as dependências.
4. Conecta o teu smartphone Android por USB (com Depuração USB ativada) ou inicia um emulador.
5. Clica em **Run ▶**.

### Opção 2: Linha de Comandos (Gradle Wrapper)
```bash
cd android
./gradlew assembleDebug
```
O ficheiro APK será gerado em:
`android/app/build/outputs/apk/debug/app-debug.apk`
