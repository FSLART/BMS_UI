/**
 * Escuro/claro.
 *
 * O tema e um unico atributo na raiz do documento -- `data-theme="light"` ou
 * nada. Todo o resto do CSS pede `var(--x)` e nao sabe que ha temas; trocar o
 * atributo troca o bloco de variaveis e a pagina inteira segue.
 *
 * Os canvas nao seguem, porque pintam pixeis e nao herdam nada. Por isso este
 * modulo avisa quem desenha, com `bms:theme`, e cada um repinta com as cores
 * que le nesse momento.
 *
 * Sem dependencias e sem rede: o botao e feito de sombras CSS e a escolha fica
 * no localStorage. Offline funciona igual.
 *
 * Por omissao segue o sistema, e continua a segui-lo enquanto ninguem carregar
 * no botao. A partir do primeiro clique manda a escolha da pessoa: quem trocou
 * de proposito nao quer que o Windows lhe volte a trocar ao anoitecer.
 */

const KEY = 'bms.theme';
const DARK = 'dark';
const LIGHT = 'light';

const mq = window.matchMedia?.('(prefers-color-scheme: light)') ?? null;
let watching = false;

/** O tema do sistema. Escuro se o sistema nao disser nada. */
export function system() {
  return mq?.matches ? LIGHT : DARK;
}

/**
 * O tema escolhido a mao, ou `null` se ninguem escolheu ainda.
 *
 * A diferenca entre `null` e um valor e o que decide se o sistema ainda manda.
 */
export function chosen() {
  try {
    const v = localStorage.getItem(KEY);
    return v === LIGHT || v === DARK ? v : null;
  } catch {
    // Modo privado ou armazenamento bloqueado: o tema deixa de ser lembrado,
    // mas a aplicacao nao pode falhar a arrancar por causa disso.
    return null;
  }
}

/** Com o que arrancar: a escolha da pessoa, senao o sistema. */
export function initial() {
  return chosen() ?? system();
}

export function current() {
  return document.documentElement.dataset.theme === LIGHT ? LIGHT : DARK;
}

/**
 * Aplica o tema. Chamado antes de qualquer viewer ou grafico existir, para o
 * primeiro desenho ja sair com as cores certas.
 *
 * `persist` so e verdade quando a pessoa carrega no botao. Gravar tambem no
 * arranque marcava logo uma escolha que ninguem fez, e a aplicacao deixava de
 * acompanhar o sistema a partir da primeira abertura.
 */
export function apply(theme, { announce = true, persist = false } = {}) {
  const light = theme === LIGHT;
  const root = document.documentElement;

  // Sem transicoes durante a troca. Metade da interface tem `transition:
  // background`, e trocar o tema poe todas a correr ao mesmo tempo: a pagina
  // atravessa cores intermedias que nao pertencem a tema nenhum. Pior, uma
  // transicao so avanca enquanto a janela desenha -- com a janela tapada ou
  // minimizada fica a meio, e o que devia ser um cartao claro fica escuro para
  // sempre. Aplicar de uma vez nao tem esse estado intermedio.
  root.classList.add('theme-switching');

  // Sempre explicito, tambem no escuro. O escuro e o bloco por omissao do CSS
  // e funcionaria sem atributo nenhum, mas o script no <head> escreve os dois
  // valores, e deixar o atributo cair aqui punha os dois a discordar.
  root.dataset.theme = light ? LIGHT : DARK;

  for (const btn of document.querySelectorAll('[data-theme-toggle]')) {
    btn.classList.toggle('day', light);
    btn.querySelector('.moon')?.classList.toggle('sun', light);
    btn.setAttribute('aria-pressed', String(light));
  }

  // Forcar o recalculo antes de devolver as transicoes, senao o browser junta
  // as duas mudancas na mesma passagem e a supressao nao chega a valer.
  void getComputedStyle(root).backgroundColor;
  // setTimeout e nao requestAnimationFrame: rAF so corre quando a janela
  // desenha, e a supressao ficaria colada se estivesse tapada.
  setTimeout(() => root.classList.remove('theme-switching'), 0);

  if (persist) {
    try { localStorage.setItem(KEY, light ? LIGHT : DARK); } catch { /* sem persistencia */ }
  }
  // Quem pinta em canvas volta a ler as cores. `announce: false` no arranque,
  // que ainda nao ha nada desenhado para avisar.
  if (announce) window.dispatchEvent(new CustomEvent('bms:theme', { detail: { theme: light ? LIGHT : DARK } }));
}

/** Clique no botao: troca e passa a mandar sobre o sistema. */
export function toggle() {
  apply(current() === LIGHT ? DARK : LIGHT, { persist: true });
}

/** Volta a seguir o sistema, esquecendo a escolha feita a mao. */
export function followSystem() {
  try { localStorage.removeItem(KEY); } catch { /* sem persistencia */ }
  apply(system());
}

/** Liga os botoes que existirem no documento, e a preferencia do sistema. */
export function mount() {
  for (const btn of document.querySelectorAll('[data-theme-toggle]')) {
    if (btn.dataset.wired) continue;
    btn.dataset.wired = '1';
    btn.addEventListener('click', toggle);
  }

  // O sistema pode mudar com a aplicacao aberta -- o Windows troca sozinho a
  // certa hora, se estiver configurado assim. Acompanhar, mas so enquanto
  // ninguem tiver escolhido a mao.
  if (mq && !watching) {
    watching = true;
    const onChange = () => { if (!chosen()) apply(system()); };
    if (mq.addEventListener) mq.addEventListener('change', onChange);
    else mq.addListener(onChange);   // WebViews antigos
  }
}

/** Cor de tema, lida do CSS no momento em que se desenha. */
export function css(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}
