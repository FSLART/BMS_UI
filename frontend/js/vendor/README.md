# Bibliotecas de terceiros

Servidas daqui, nunca de um CDN. **A app tem de arrancar sem rede** — num
paddock não há Wi-Fi, e o `model-viewer` é o que desenha todos os modelos 3D.
Enquanto vinha de fora, abrir o programa offline dava quatro placeholders.

| Ficheiro | Versão | Licença |
|---|---|---|
| `model-viewer-4.0.0.min.js` | 4.0.0 | BSD-3-Clause (Google LLC) |

```
origem  https://ajax.googleapis.com/ajax/libs/model-viewer/4.0.0/model-viewer.min.js
bytes   955 556
sha256  774edda21e1be2a0934e460ca5943af1fe3f88da130a9f98bd6a9d611576cacf
```

A versão está no nome do ficheiro de propósito: atualizar é pôr o novo ao lado
e trocar a linha em [`../../index.html`](../../index.html), o que deixa a
anterior a um passo de distância se algo partir.

## Não vai buscar nada à rede

O ficheiro tem doze URLs lá dentro. Dez são comentários, licenças e *namespaces*
XML. As outras duas são descarregadas **em execução**, e só quando um modelo
precisa delas:

| URL | Quando |
|---|---|
| `gstatic.com/draco/...` | um GLB com `KHR_draco_mesh_compression` |
| `gstatic.com/basis-universal/...` | um GLB com `KHR_texture_basisu` |

Nenhum dos nossos usa. Os modelos são otimizados com **quantização**
(`KHR_mesh_quantization`), que o motor descodifica sozinho, e não têm texturas.
Ver [`../../models/OTIMIZAR.md`](../../models/OTIMIZAR.md) — foi exatamente por
isto que a quantização foi preferida ao Draco, que comprime mais mas obrigaria
a ter rede para abrir um ficheiro.

**Se alguém vier a comprimir um modelo com Draco ou a meter-lhe texturas KTX2,
esse modelo deixa de abrir offline.** Nesse caso é preciso trazer também o
descodificador para aqui e apontar-lhe o `model-viewer` com
`document.querySelector('model-viewer').dracoDecoderLocation = '/js/vendor/draco/'`.

## Empacotamento

O [`../../../compile.py`](../../../compile.py) leva a pasta `frontend/js`
inteira, portanto isto entra no executável sem ser preciso mexer nele.
