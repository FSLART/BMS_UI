"""O que os tres carros partilham.

A eletronica do AMS e a mesma nos tres: mesmo firmware, mesmos barramentos,
mesmos comandos. So mudam os modelos 3D e a DBC. Por isso isto vive uma vez
e cada perfil refere-se a estas listas em vez de as repetir -- corrigir uma
velocidade de barramento passa a ser uma edicao, nao tres.

Se um carro futuro precisar de algo diferente, sobrepoe-se no perfil dele; a
excecao fica visivel por estar escrita, em vez de se perder entre copias.
"""

from __future__ import annotations

from .models import CanBus, CanCommand

# Barramento do carro a 1 Mbit, carregamento a 500k. Confirmado pela equipa.
BUSES: list[CanBus] = [
    CanBus(
        id="car", name="Carro", bitrate=1_000_000,
        detail="Bateria montada no carro: AMS, sensor ISA IVT e inversores.",
    ),
    CanBus(
        id="charger", name="Carregamento", bitrate=500_000,
        detail="Bateria no handcart: carregador, handcart e ISA secundario.",
    ),
]

# As unicas tramas que a interface pode enviar. Vem da DBC da powertrain, que e
# a mesma nos tres carros.
COMMANDS: list[CanCommand] = [
    CanCommand(
        id="balancing", label="Balanceamento",
        message="Start_Balancing", signal="Balancing_Request",
        detail="O firmware so arranca o balanceamento por CAN. Celulas fora de "
               "3000-4250 mV sao excluidas, e para aos 85 degC de die.",
    ),
    CanCommand(
        id="charging", label="Carregamento",
        message="Start_Charging", signal="Charging_Request",
        detail="Pede ao BMS que inicie a sessao de carga. So faz sentido com o "
               "acumulador no handcart.",
    ),
    CanCommand(
        id="precharge", label="Pre-carga", danger=True,
        message="Start_PreCharge", signal="Precharge_Request",
        detail="FECHA OS CONTACTORES E ENERGIZA A ALTA TENSAO. Na DBC quem envia "
               "esta mensagem e a VCU: a partir daqui estamos a falar por cima dela.",
    ),
]

# Dialeto CAN. Um so descodificador serve os tres (backend/decode/t26.py).
DECODER = "t26"
