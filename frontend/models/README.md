# Modelos 3D

Dois ficheiros, exportados diretamente do SolidWorks (`Save As → glTF 2.0`):

| Ficheiro | Configuração no SolidWorks | Tamanho | Em git? |
|---|---|---|---|
| `tek26e_closed.glb` | assembly fechado | 20 MB | sim |
| `tek26e_open.glb` | tampa aberta, interiores à vista | 223 MB | **não** |

Enquanto não existirem, a interface mostra um placeholder e continua 100%
funcional — o 3D nunca é caminho crítico.

## Onde ir buscar o `tek26e_open.glb`

Tem 223 MB, e o GitHub recusa qualquer ficheiro acima de **100 MB**. Está no
`.gitignore` e é distribuído como **asset de um Release** (limite de 2 GB por
ficheiro, e não gasta a quota de LFS da organização).

```bash
# a partir da raiz do repositorio
gh release download modelos-v1 -p tek26e_open.glb -D frontend/models/
```

Depois de um clone novo, sem este ficheiro: o ecrã de ligação funciona
normalmente (usa o modelo fechado) e o dashboard mostra o placeholder.

Para publicar uma versão nova do modelo:

```bash
gh release create modelos-v1 frontend/models/tek26e_open.glb --title "Modelos 3D" --notes "GLB do acumulador, tampa aberta"
```

## Limite de tamanho

`MAX_MODEL_MB` em [`../js/viewer.js`](../js/viewer.js) está em **1024 MB** — é um
travão só para o caso de um GLB tão grande que mataria o separador. Exports de
CAD com centenas de MB passam de propósito: carregam devagar e renderizam
pesado, o que é uma escolha a fazer, não uma falha.

### O que aconteceu com o primeiro export (referência)

`tek26e_closed.glb` saiu do SolidWorks com 337 MB e 20,6 M de triângulos
renderizados. A repartição:

| Componente | Triângulos | Instâncias |
|---|---|---|
| `Molicel_P42A` | 11 902 032 | 432 × ~27 550 cada |
| `BMS_Slave_2.0` | 3 456 324 | 5664 malhas (cada SMD) |
| `Cables_ASM` | 1 989 564 | 348 |
| `BMS_Master_v0.2` | 619 038 | 1 |
| parafusos, porcas, tie bars | ~230 000 | 168 |

Duas causas: tessellation no máximo (uma célula cilíndrica precisa de ~400
triângulos, não 27 550) e o interior todo incluído num modelo que é para ser
visto **fechado**.

## Preparação no CAD

1. Renomeia as peças na árvore do assembly antes de exportar. Os nomes passam
   para os *nodes* do glTF e são a ponte para os dados: `segment_01`,
   `cell_01_03`, `cover_top`, `bms_master`.
2. Baixa a qualidade de tessellation.
3. Suprime parafusos, roscas e tudo o que fica invisível.
4. As appearances/cores do SolidWorks são exportadas — bom. Só atenção às
   texturas (metal escovado, carbono), que embutem imagens e incham o ficheiro.

## Otimização

```bash
npx @gltf-transform/cli optimize tek26e_open.glb tek26e_open.glb --compress draco --texture-compress webp
```

## Cores

As cores erradas que vêm do CAD são corrigidas em `COLOR_RULES`, em
[`../js/viewer.js`](../js/viewer.js). As regras são indexadas pela **cor
original RGB**, não pelo índice do material — o índice muda a cada reexport, a
appearance do SolidWorks não. Boa parte dos casos são appearances por omissão
(`243,236,184` e `202,209,238` são literalmente os defaults do SolidWorks:
peças a que nunca foi atribuído material).

## Hotspots

As âncoras vivem em `CarProfile.hotspots`, em
[`../../backend/cars.py`](../../backend/cars.py), não no frontend.

Para apanhar uma âncora nova: abre a consola in-app (canto superior direito),
carrega em **âncora**, e clica na peça no modelo que estiver no ecrã. A linha
`Hotspot(...)` fica na área de transferência, pronta a colar em `cars.py`.

Bate o modo âncora ao editor do modelviewer.dev quando várias peças partilham um
*node* do CAD — AIR+, AIR− e o pré-carga estão todos dentro de
`Pre_Charge_AIR.step` e não se distinguem pelo nome.
