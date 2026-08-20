# CAN do TEK-26e — mapa do descodificador

Implementado em [`backend/decode/t26.py`](../decode/t26.py). As DBCs são
resolvidas por [`backend/dbcstore.py`](../dbcstore.py).

## De onde vêm as DBCs

Duas, fundidas numa só base de dados por ordem de prioridade (a primeira ganha
os IDs repetidos):

| # | Ficheiro | O que traz |
|---|---|---|
| 1 | `powertrain_t26.dbc` | AMS (12 slaves + master) e o sensor ISA IVT |
| 2 | `handcart_t26.dbc` | carregador e handcart; repete os IDs do IVT com menos detalhe, por isso vem depois |

Repositório: `FSLART/T26_DBC_Aquisition_Boards`, ramo `main`.

**A app tem de funcionar sem internet.** Ordem de procura, por ficheiro:

1. `CarProfile.dbc.local_dir` — checkout nesta máquina
2. cache — `%LOCALAPPDATA%\BMS_UI\dbc` (Linux: `~/.local/share/bms_ui/dbc`)
3. cópia incluída — `backend/dbc/`, commitada no repositório
4. descarga do GitHub raw

Sempre que há rede, a descarga acontece e escreve na cache, mesmo quando já se
encontrou uma cópia local — a rede nunca está no caminho crítico da ligação, só
mantém a cache atualizada para o dia em que não houver.

## Slaves

12 slaves, 2 por segmento, 12 grupos série cada. Blocos de **7 mensagens**,
base `1536` (`0x600`), passo 7. IDs `1536`–`1619`.

```
slave_base(n) = 1536 + (n - 1) * 7        # n = 1..12

+0  Slave_nn_Voltage_ID_1      cell_voltage_1..4      16 bit, 0.001 V
+1  Slave_nn_Voltage_ID_2      cell_voltage_5..8
+2  Slave_nn_Voltage_ID_3      cell_voltage_9..12
+3  Slave_nn_Temperature_ID_1  temperature_value_1..4 16 bit, 0.01 degC
+4  Slave_nn_Temperature_ID_2  temperature_value_5..6, temperature_maximum, temperature_delta
+5  Slave_nn_MSC_ID_1          module_voltage_sum / avg / min / max
+6  Slave_nn_MSC_ID_2          module_voltage_delta, module_ic_voltage,
                               module_open_wire (8b), module_ic_temperature,
                               module_overvoltage (1b), module_undervoltage (1b),
                               module_under_over_identifier (5b)
```

Todos little-endian sem sinal (`@1+`).

### Slave → posição no pack

```
segmento       = (n - 1) // slaves_per_segment + 1      # 2 slaves por segmento
grupo_no_seg   = ((n - 1) % slaves_per_segment) * cells_per_slave + canal
```

Com `slaves_per_segment=2` e `cells_per_slave=12`: o slave 1 cobre os grupos
1–12 do segmento 1, o slave 2 cobre 13–24, o slave 3 abre o segmento 2, etc.

## Master

| ID | Mensagem | Para o `UniversalBmsState` |
|---|---|---|
| 1793 | `Master_MSC_ID_1` | `master_state`, `master_fan_pwm`, `mcu_temperature`. **`ams_current_draw` não é usado** — ver abaixo |
| 1794 | `Master_PreCharge_ID_1` | `precharge_ctc_air_pos_state` → `safety.air_positive`; `..._air_min_state` → `air_negative`; `precharge_state == HV_ON` → `precharge_done` |
| 1795 | `Master_MSC_ID_2` | `fault1_*` / `fault2_*` → lista de `Fault` (com tabela `VAL_`, 255 = `EMPTY`) |
| 1796 | `Master_MSC_ID_3` | `overall_maximum_voltage` / `minimum_voltage` → `pack.cell_v_max` / `cell_v_min`; idem temperaturas |
| 1797 | `Master_SOC_Accumulator` | `SOC_Integer` + `SOC_Float` → `pack.soc` |
| 1798 | `Master_MSC_ID_4` | `slaves_detected` < 12 → `Fault` |
| 1801 | `AMS_SDC_Feedback` | `SDC_State` → `safety.sdc_closed` (1 byte, big-endian `@0+`) |

## Sensor ISA IVT

`0x521`–`0x528`, emissor `ISABELLE`. Todos os resultados são **int32 com sinal,
big-endian** — é daqui que vêm a corrente e a tensão do pack:

| ID | Sinal | Unidade | Usado em |
|---|---|---|---|
| 0x521 | `IVT_Result_I` | mA | `pack.current` |
| 0x522 | `IVT_Result_U1` | mV | `pack.voltage` |
| 0x526 | `IVT_Result_W` | W | `pack.power` |
| 0x528 | `IVT_Result_Wh` | Wh | `pack.energy_used` |

## Carregador (handcart)

`Charger_Status` (`0x18FF50E8`, mais as variantes P998 e P1000) e
`BMS_ChargingRequest` (`0x1806E8F4`). Alimentam `BmsState.charger`.

Serve para desambiguar o sinal da corrente: **corrente negativa com o
`Charger_Status` vivo no barramento é carregamento; a mesma leitura sem ele é
regeneração**. É essa a regra em `BmsState.charging`.

## Decisões do descodificador

- **Corrente e tensão vêm do IVT. O `ams_current_draw` não serve para nada
  disto.** Apesar do nome, não é a corrente do pack: o firmware preenche-o de
  `analog_readings->ams_master_current`, um sensor hall MCS1802 a 0.264 V/A que
  mede o que a **própria placa master consome** (uns amperes, sem relação com a
  tração). Ver `Firmware/Core/Src/analog_readings.c` em
  [lart_bms](https://github.com/FSLART/lart_bms/tree/beta-v2.1). Ainda por cima
  o firmware calcula-o como float com sinal e a DBC declara-o `unsigned`, por
  isso valores negativos dão a volta. Se o IVT estiver calado, a corrente é
  desconhecida e é isso que se mostra, mais uma falta `IVT_MUTE`.
- **`pack.voltage` de reserva é a soma das tensões de célula**, não a soma de
  `module_voltage_sum` — ver o defeito do slave 10 abaixo.
- **A temperatura por célula fica vazia.** São 6 NTC por slave contra 12 grupos,
  e o mapeamento NTC → grupo é um facto de hardware que este código não tem. As
  temperaturas são reportadas por termístor, onde são verdadeiras.
- **NTC avariados** (`CarProfile.broken_thermistors`, hoje `3:3` e `3:4`) são
  reportados sem leitura, em vez de repetirem o valor do vizinho como a bridge
  do Foxglove fazia. O UI já sabe mostrar "sem dados"; um número inventado não
  se distingue de um verdadeiro.

## Defeitos conhecidos na DBC

O descodificador audita os 12 blocos de slave no arranque e avisa na consola.
Hoje encontra dois:

- **`Slave_10_MSC_ID_1` (ID 1604): os quatro campos estão declarados a 8 bits
  em vez de 16.** `module_voltage_sum` do slave 10 descodifica para 0,255 V no
  máximo. Corrigir no repositório da DBC.
- **`Slave_03..12_MSC_ID_2` não têm `module_overvoltage`,
  `module_undervoltage` nem `module_under_over_identifier`** — só os slaves 1 e
  2 os declaram. As faltas de OV/UV por módulo só funcionam nesses dois.

## Dois barramentos, não um

O firmware usa **CAN1 para a powertrain e CAN2 só para carregamento** (handcart,
carregador EV Europe, ISA secundário). As mensagens do carregador que este
descodificador percebe só aparecem em quem estiver ligado ao CAN2. A interface
liga a um canal de cada vez, portanto num carregamento vê-se ou o AMS ou o
carregador, não os dois.

| Barramento | Velocidade | Quando existe |
|---|---|---|
| Carro (CAN1) | **1 Mbit/s** | bateria montada no carro |
| Carregamento (CAN2) | **500 kbit/s** | bateria no handcart |

Valores confirmados pela equipa. A velocidade é a única coisa que a app não
consegue descobrir sozinha — no valor errado não chega trama nenhuma, portanto
não há tráfego de onde inferir. Tudo o resto (em que barramento se está) sai do
que aparece no fio: ver `BmsState.mode`.

## Bitmasks a caminho do firmware

Estão previstas três, e o modelo de estado já as acomoda sem mudar de forma —
`Cell.open_wire`, `Cell.balancing` e `Thermistor` já são por posição. Só falta o
descodificador passar a lê-las em vez de as inferir.

| Bitmask | O que substitui hoje | Onde ligar |
|---|---|---|
| Células em fio aberto | dedução por tensão `< v_open_wire` em `_build_cells` | pôr `open_wire` a partir do bit, e deixar o limiar só como reserva |
| NTC em fio aberto | nada — hoje um NTC solto passa por leitura válida | `_build_thermistors`, marcar `status=UNKNOWN` e `temperature=None` |
| Células em balanceamento | `master_state == BALANCING` aplicado ao pack inteiro | `_balancing` deixa de ser global; `Cell.balancing` vem do bit |

A interface não precisa de alteração nenhuma para o balanceamento: já decide
pela forma dos dados. Enquanto todas as células trazem a mesma flag mostra um
badge de pack; assim que deixarem de concordar, marca as que estão mesmo a
descarregar. Ver `_paintCells` em `frontend/js/dashboard.js`.

## Por confirmar com o firmware

- Sinal da corrente do IVT: assumido **positivo = descarga**. Se o shunt estiver
  montado ao contrário, inverter num sítio só (`_build_pack`).
- O último slave da cadeia ADBMS6830 emite o sentinela `0x8000`, que descodifica
  como ≈ −3,42 V. O firmware mitiga com valor absoluto e trata `< 2,30 V` como
  fio aberto. Os limites em `CellLimits` usam `v_min = 2,50`, portanto uma
  leitura de fio aberto aparece aqui como subtensão — que manda alguém procurar
  o problema errado.

**Confirmado no firmware:** `BMS_ChargingRequest.Control` é `0 = carregar`,
`1 = parar`, como estava implementado.
- Não há mensagem de IMD nem de resistência de isolamento nesta base de dados.
  `safety.imd_ok` está ligado ao SDC, que é a única evidência disponível.
- Não há sinal de SOH nem de balanceamento por célula. O balanceamento é global,
  vindo de `master_state == BALANCING`.
