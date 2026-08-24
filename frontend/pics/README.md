# Fotografias dos carros

Um PNG por carro, com os nomes exatos abaixo. Enquanto um não existir, o cartão
mostra o caminho em falta em texto ténue — o ecrã nunca fica partido.

| Ficheiro | Carro | Estado |
|---|---|---|
| `t26.png`          | TEK-26e     | ✅ foto real, 1536×1024 RGBA transparente |
| `mystery_car.png`  | TEK-26e EVO | 🕶 silhueta "?" provisória |
| `mystery_car.png`  | T-28        | 🕶 silhueta "?" provisória |

Quando houver foto real, mete-a aqui e troca o `image=` do carro em
`backend/cars/tek26e.py`, apagando o `image_is_silhouette=True`.

## Logótipo

| Ficheiro | Uso |
|---|---|
| `LART_LogoPrincipal.png` | original da equipa, 1501×501 — mantido intacto |
| `lart_logo.png`          | o mesmo cortado à caixa de conteúdo, 960×240 (4:1) |

A interface usa o **cortado**, nas barras de topo dos três ecrãs. O original tem
~25 % de margem transparente à volta: a 26 px de altura os glifos ficariam com
metade do tamanho e desalinhados do texto ao lado.

Se substituíres o logótipo, volta a cortar com:

```python
from PIL import Image
src = Image.open('LART_LogoPrincipal.png').convert('RGBA')
src.crop(src.getbbox()).save('lart_logo.png', optimize=True)
```

## Silhuetas

O `mystery_car.png` é uma forma preta opaca sobre fundo transparente, com o "?"
recortado. Preto sobre o fundo escuro da aplicação desaparecia, por isso o
perfil marca `image_is_silhouette=True` e o CSS aplica `invert(1)`: o corpo fica
cinzento-claro e o "?" lê-se recortado a escuro.

Os caminhos vêm de `CarProfile.image` em [`../../backend/cars/`](../../backend/cars/).
Para acrescentar um carro, junta o perfil lá e mete o PNG aqui.

## Recomendações

- **Fundo transparente** (RGBA, alpha 0 nas margens). É o que faz o carro
  assentar na página sem retângulo à volta. O `t26.png` já é assim.
- **Proporção 3:2**, igual à do `t26.png`. O cartão usa `object-fit: contain`,
  por isso nada é cortado — só sobra espaço se a proporção divergir muito.
- Vista de 3/4 (frente + lado) é a que lê melhor a esta escala.
- ~1500 px de largura chega.

## Cor

As fotografias aparecem a cores. Só levam um ligeiro recuo de brilho e
saturação quando o carro não está selecionado, em `.car-photo img`
([`../css/app.css`](../css/app.css)).
