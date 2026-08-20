# Reduzir o tamanho dos GLB

O SolidWorks exporta glTF sem qualquer compactação: posições e normais em
`float32`, índices em `uint32`, e vértices repetidos em vez de partilhados. Dá
24 bytes por vértice num modelo que cabe numa caixa de 64 cm — a maior parte do
ficheiro é desperdício, não detalhe.

Dois comandos resolvem, **sem apagar um único triângulo**:

```bash
npx @gltf-transform/cli weld     entrada.glb     tmp.glb
npx @gltf-transform/cli quantize tmp.glb         saida.glb
```

| Passo | O que faz |
|---|---|
| `weld` | junta vértices duplicados: os que estão na mesma posição passam a ser um só, partilhado pelos triângulos à volta |
| `quantize` | guarda posições em 14 bits e normais em 10 em vez de `float32` |

Resultado no `tek26e_open.glb`: **223 MB → 26 MB**, com os 5 813 270 triângulos
todos lá e a *bounding box* igual ao milímetro.

## Porquê `quantize` e não Draco/meshopt

Draco e meshopt comprimem mais, mas o `<model-viewer>` vai buscar o
descodificador a um CDN — e **esta app tem de funcionar sem internet**.

`quantize` usa `KHR_mesh_quantization`, que o three.js descodifica nativamente.
Sem descarregar nada.

## Verificar depois de otimizar

```bash
npx @gltf-transform/cli inspect saida.glb
```

Três coisas a confirmar contra o ficheiro original:

- **`bboxMin` / `bboxMax` iguais** — se mudarem, as âncoras dos hotspots em
  [`../../backend/cars.py`](../../backend/cars.py) deixam de apontar para o
  sítio certo e é preciso apanhá-las outra vez
- **mesmo número de materiais** — as regras de cor em
  [`../js/viewer.js`](../js/viewer.js) casam pela cor RGB original, e um
  material que desapareça leva a peça a ficar com a cor errada do CAD
- **`renderVertexCount` igual** — garante que nenhuma geometria se perdeu

Depois é abrir a app e olhar. O sítio onde a quantização se nota primeiro é o
sombreamento de superfícies curvas e lisas (células, conectores): se aparecerem
faixas, sobe a precisão dos normais.

```bash
npx @gltf-transform/cli quantize tmp.glb saida.glb --quantize-normal 12
```

## Se ainda for pesado a renderizar

O tamanho do ficheiro e o peso na GPU são problemas diferentes. `weld` e
`quantize` só atacam o primeiro; a contagem de triângulos fica igual, e é essa
que decide quantos segundos o modelo demora a aparecer.

Para isso o botão certo está no CAD, e **não** no diálogo de export:

**Tools → Options → Document Properties → Image Quality** → slider
*"Shaded and draft quality HLR/HLV resolution"* para o lado *Low*.

Com o assembly aberto, liga **"Apply to all referenced part documents"** —
senão cada peça mantém a definição dela e o export não muda quase nada.

Referência de quanto há a ganhar: no modelo aberto, as células
(`Fillet` / `solidBody` / `Terminal`, 2402 nodes) sozinhas são 2,43 M
triângulos — 42% do modelo. Um cilindro precisa de umas centenas de triângulos,
não de milhares.

Em alternativa, decimar depois do export, com perda de detalhe controlada:

```bash
npx @gltf-transform/cli simplify entrada.glb saida.glb --ratio 0.5 --error 0.001
```
