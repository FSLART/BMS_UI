"""TEK-26e — o carro de 2026.

144s3p: 6 segmentos x 24 paralelos x 3 celulas = 432 Molicel P45B.
As ancoras do segmento foram calculadas do GLB, nao clicadas -- ver
scratchpad/seg_anchors.py e frontend/models/README.md.
"""

from __future__ import annotations

from .common import BUSES, COMMANDS, DECODER
from .models import CameraView, CanBus, CanCommand, CarProfile, CellLimits, DbcSource, Hotspot

CAR = CarProfile(
    id="tek26e",
    name="TEK-26e",
    subtitle="Elétrico",
    year="2026",
    image="/pics/t26.png",
    dbc=DbcSource(
        # Repositorio publico da equipa - o mesmo que a bridge do Foxglove
        # usa. E o unico que responde sem credenciais, o que interessa
        # porque a atualizacao e feita sem pedir login a ninguem.
        repo="https://github.com/FSLART/T26_DBC_Aquisition_Boards.git",
        ref="main",
        # Ordem = prioridade. A powertrain define o AMS e o IVT; a handcart
        # traz o carregador e o handcart, e repete os IDs do IVT com menos
        # detalhe - por isso vem depois e esses IDs nao a apanham.
        files=["powertrain_t26.dbc", "handcart_t26.dbc"],
        local_dir="C:/Users/jpser/Documents/GitHub/T26_DBC",
    ),
    buses=BUSES,
    commands=COMMANDS,
    decoder=DECODER,
    # NTC 3 e 4 do slave 3 avariados: leem lixo, nao o vizinho.
    broken_thermistors=["3:3", "3:4"],
    # 144s3p: 6 segmentos x 24 paralelos x 3 celulas = 432 Molicel P45B
    # 12 slaves AMS, 2 por segmento, 12 paralelos cada (DBC: Slave_01..Slave_12)
    n_segments=6,
    cells_per_segment=24,
    parallel_strings=3,
    cell_model="Molicel P45B",
    nominal_cell_v=3.60,
    cell_capacity_ah=4.5,
    slaves_per_segment=2,
    cells_per_slave=12,
    temps_per_slave=6,
    limits=CellLimits(v_min=2.50, v_max=4.20, v_warn_low=2.80, v_warn_high=4.15),
    model_closed="/models/tek26e_closed.glb",
    # Export do SolidWorks passado por weld + quantize: 223 MB -> 26 MB com
    # os 5,8M triangulos e a bounding box intactos, por isso as ancoras dos
    # hotspots aqui em baixo continuam validas. Ver models/README.md.
    model_open="/models/tek26e_open_light.glb",
    # Tres quartos, quase ao nivel: apanha a face das ventoinhas e uma das
    # laterais, em vez do alcado plano que a vista de frente dava.
    # Apanhada com o botao "vista" da consola; raio em metros, nao em
    # percentagem, para nao depender do enquadramento automatico.
    view_closed=CameraView(orbit="232deg 70deg 0.748m", fov="26deg"),
    # De topo: os segmentos e a placa master leem-se como planta.
    view_open=CameraView(orbit="0deg 18deg 66%", fov="32deg"),
    # Acumulador fechado assente no handcart, 1,23 m de altura ao todo.
    # Gerado por scratchpad/compose_charger.py a partir de handcart_t26.glb e
    # tek26e_closed.glb -- o <model-viewer> mostra um modelo por elemento, por
    # isso a montagem e feita em disco e nao com dois viewers sobrepostos.
    # Reexportar qualquer um dos dois obriga a correr o script outra vez.
    model_charger="/models/tek26e_charger.glb",
    view_charger=CameraView(orbit="200deg 72deg 70%", fov="32deg"),
    model_segment="/models/seguemento.glb",
    # As PCBs saem do CAD na face z ~ -68 mm, de lado. O pitch de +90 graus
    # roda-as para cima, e o modelo fica 457 x 153 x 80 mm.
    #
    # As ancoras NAO acompanham o `orientation`: foram rodadas pela mesma
    # transformacao, (x, y, z) -> (x, -z, y). Se este valor mudar, as 36
    # posicoes tem de rodar com ele ou saem de cima das celulas.
    #
    # Enquadramento apanhado com o botao "vista" da consola.
    view_segment=CameraView(orbit="-18deg 86deg 0.561m", fov="34deg",
                            orientation="0deg 90deg 0deg"),
    hotspots=[
        # Conector RTS718N32S03 na tampa. Centro do node no GLB.
        Hotspot(
            id="rts",
            view="closed",
            position="-0.128m 0.327m 1.755m",
            normal="0m 1m 0m",
            label="Offline",
            binds="link",
            # O painel de ligacao ocupa o centro do palco: o rotulo vai
            # para o canto oposto ao do modo demo, ligado por uma linha.
            standoff="top-right",
        ),
        # --- modelo aberto ---------------------------------------------
        # Posicoes calculadas dos limites dos nodes do GLB, nao clicadas
        # num editor (scratchpad/open_anchors.py). Os segmentos vem das 6
        # placas BMS_Slave_2.0, uma por segmento, igualmente espacadas ao
        # longo do eixo x.
        Hotspot(id="ams", view="open", label="AMS",
                position="-0.187m 0.276m 1.770m", binds="ams"),
        Hotspot(id="imd", view="open", label="IMD",
                position="-0.054m 0.261m 1.774m", binds="imd"),
        Hotspot(id="fuse", view="open", label="Fusivel",
                position="0.051m 0.270m 1.765m", binds="fuse"),
        # AIR+, AIR- e pre-carga partilham o node Pre_Charge_AIR.step e nao
        # se distinguem por nome. Estas posicoes foram apanhadas com o modo
        # ancora da consola, clicando em cada contactor.
        Hotspot(id="air-pos", view="open", label="AIR+",
                position="0.100m 0.274m 1.797m", normal="0m 1m 0m",
                binds="air_positive"),
        Hotspot(id="air-neg", view="open", label="AIR-",
                position="0.170m 0.280m 1.789m", normal="0m 1m 0m",
                binds="air_negative"),
        Hotspot(id="precharge", view="open", label="Pre-carga",
                position="0.131m 0.241m 1.745m", normal="0.697m 0m -0.717m",
                binds="precharge_done"),
        Hotspot(id="ivt", view="open", label="IVT",
                position="0.210m 0.310m 1.768m", normal="0m 1m 0m", binds="ivt"),
        Hotspot(id="fans", view="open", label="Ventoinhas",
                position="-0.213m 0.152m 1.907m", normal="0m 1m 0m", binds="fans"),
        Hotspot(id="seg-1", view="open", label="S1",
                position="-0.212m 0.219m 1.650m", binds="segment:1"),
        Hotspot(id="seg-2", view="open", label="S2",
                position="-0.127m 0.219m 1.650m", binds="segment:2"),
        Hotspot(id="seg-3", view="open", label="S3",
                position="-0.042m 0.219m 1.650m", binds="segment:3"),
        Hotspot(id="seg-4", view="open", label="S4",
                position="0.042m 0.219m 1.650m", binds="segment:4"),
        Hotspot(id="seg-5", view="open", label="S5",
                position="0.127m 0.219m 1.650m", binds="segment:5"),
        Hotspot(id="seg-6", view="open", label="S6",
                position="0.212m 0.219m 1.650m", binds="segment:6"),

        # --- modelo no carregador ---------------------------------------
        # Ambas sao `standoff`: o rotulo vai para um canto e a posicao so decide
        # onde a linha de chamada acaba. Apontam para a peca que a leitura
        # descreve, e nao para um sitio qualquer do modelo.
        Hotspot(id="chg-precharge", view="charger", label="Pre-carga",
                # Topo do acumulador, apanhado com o modo ancora da consola.
                position="0.175m 0.819m 0.266m", normal="0m 1m 0m",
                binds="precharge_done", standoff="top-right"),
        Hotspot(id="chg-control", view="charger", label="Carregador",
                # Face exterior da ventoinha de 120 mm, na lateral do carro:
                # e ela que trabalha quando o carregamento esta a decorrer.
                # Centro da face calculado do GLB (120 x 120 x 25 mm).
                position="-0.198m 0.128m 0.584m", normal="-1m 0m 0m",
                binds="charger_control", standoff="bottom-right"),

        # --- modelo do segmento (generico: os 6 sao iguais) -------------
        # Posicoes calculadas do GLB: as 72 celulas estao em 18 colunas x 4
        # filas, e cada paralelo e um trio de celulas seguidas na mesma fila
        # (6 paralelos por fila x 4 filas = 24). Os NTC vem dos 12 nos
        # NTC_SENSOR, que a export nomeia.
        #
        # A POSICAO de cada paralelo esta certa; a NUMERACAO assume que a serie
        # sobe fila a fila, da frente para tras. Isso e ligacao de barramento
        # e nao se le da geometria -- se estiver trocada, corrigir aqui com o
        # modo ancora da consola. Os binds nao mudam.
        # --- paralelos ---
        Hotspot(id="par-1", view="segment", label="1",
                position="-0.184m 0.038m -0.000m", binds="parallel:1"),
        Hotspot(id="par-2", view="segment", label="2",
                position="-0.110m 0.038m -0.000m", binds="parallel:2"),
        Hotspot(id="par-3", view="segment", label="3",
                position="-0.037m 0.038m -0.000m", binds="parallel:3"),
        Hotspot(id="par-4", view="segment", label="4",
                position="0.037m 0.038m -0.000m", binds="parallel:4"),
        Hotspot(id="par-5", view="segment", label="5",
                position="0.110m 0.038m -0.000m", binds="parallel:5"),
        Hotspot(id="par-6", view="segment", label="6",
                position="0.184m 0.038m -0.000m", binds="parallel:6"),
        Hotspot(id="par-7", view="segment", label="7",
                position="-0.184m 0.013m -0.000m", binds="parallel:7"),
        Hotspot(id="par-8", view="segment", label="8",
                position="-0.110m 0.013m -0.000m", binds="parallel:8"),
        Hotspot(id="par-9", view="segment", label="9",
                position="-0.037m 0.013m -0.000m", binds="parallel:9"),
        Hotspot(id="par-10", view="segment", label="10",
                position="0.037m 0.013m -0.000m", binds="parallel:10"),
        Hotspot(id="par-11", view="segment", label="11",
                position="0.110m 0.013m -0.000m", binds="parallel:11"),
        Hotspot(id="par-12", view="segment", label="12",
                position="0.184m 0.013m -0.000m", binds="parallel:12"),
        Hotspot(id="par-13", view="segment", label="13",
                position="-0.184m -0.013m -0.000m", binds="parallel:13"),
        Hotspot(id="par-14", view="segment", label="14",
                position="-0.110m -0.013m -0.000m", binds="parallel:14"),
        Hotspot(id="par-15", view="segment", label="15",
                position="-0.037m -0.013m -0.000m", binds="parallel:15"),
        Hotspot(id="par-16", view="segment", label="16",
                position="0.037m -0.013m -0.000m", binds="parallel:16"),
        Hotspot(id="par-17", view="segment", label="17",
                position="0.110m -0.013m -0.000m", binds="parallel:17"),
        Hotspot(id="par-18", view="segment", label="18",
                position="0.184m -0.013m -0.000m", binds="parallel:18"),
        Hotspot(id="par-19", view="segment", label="19",
                position="-0.184m -0.038m -0.000m", binds="parallel:19"),
        Hotspot(id="par-20", view="segment", label="20",
                position="-0.110m -0.038m -0.000m", binds="parallel:20"),
        Hotspot(id="par-21", view="segment", label="21",
                position="-0.037m -0.038m -0.000m", binds="parallel:21"),
        Hotspot(id="par-22", view="segment", label="22",
                position="0.037m -0.038m -0.000m", binds="parallel:22"),
        Hotspot(id="par-23", view="segment", label="23",
                position="0.110m -0.038m -0.000m", binds="parallel:23"),
        Hotspot(id="par-24", view="segment", label="24",
                position="0.184m -0.038m -0.000m", binds="parallel:24"),
        # --- NTC ---
        Hotspot(id="ntc-1", view="segment", label="N1",
                position="-0.121m 0.040m 0.037m", binds="ntc:1"),
        Hotspot(id="ntc-2", view="segment", label="N2",
                position="0.025m 0.040m 0.037m", binds="ntc:2"),
        Hotspot(id="ntc-3", view="segment", label="N3",
                position="0.171m 0.040m 0.037m", binds="ntc:3"),
        Hotspot(id="ntc-4", view="segment", label="N4",
                position="-0.194m 0.014m 0.037m", binds="ntc:4"),
        Hotspot(id="ntc-5", view="segment", label="N5",
                position="-0.048m 0.014m 0.037m", binds="ntc:5"),
        Hotspot(id="ntc-6", view="segment", label="N6",
                position="0.098m 0.014m 0.037m", binds="ntc:6"),
        Hotspot(id="ntc-7", view="segment", label="N7",
                position="-0.121m -0.012m 0.037m", binds="ntc:7"),
        Hotspot(id="ntc-8", view="segment", label="N8",
                position="0.025m -0.012m 0.037m", binds="ntc:8"),
        Hotspot(id="ntc-9", view="segment", label="N9",
                position="0.171m -0.012m 0.037m", binds="ntc:9"),
        Hotspot(id="ntc-10", view="segment", label="N10",
                position="-0.194m -0.038m 0.037m", binds="ntc:10"),
        Hotspot(id="ntc-11", view="segment", label="N11",
                position="-0.048m -0.038m 0.037m", binds="ntc:11"),
        Hotspot(id="ntc-12", view="segment", label="N12",
                position="0.098m -0.038m 0.037m", binds="ntc:12"),
    ],
)
