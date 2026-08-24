/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  CONFIGURAÇÃO DO BMS — VALORES POR OMISSÃO E TEXTO
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * É AQUI que se edita a página de configuração. Tudo o que se vê nela — os
 * títulos, as notas, os rótulos, as unidades, os intervalos permitidos e os
 * valores de origem — está neste ficheiro e em mais nenhum.
 *
 * O `config.js` só desenha e valida; o `curves.js` só sabe fazer gráficos.
 * Nenhum dos dois precisa de ser tocado para acrescentar um parâmetro.
 *
 * ── Como acrescentar um campo ─────────────────────────────────────────────
 *
 *     { k: 'nome_interno', label: 'O que aparece no ecrã', unit: 'V',
 *       min: 0, max: 10, step: 0.1, dec: 1, def: 4.2,
 *       help: 'Linha pequena por baixo, opcional.' }
 *
 *   k     chave única; é por ela que o valor sai no objeto de configuração
 *   dec   casas decimais mostradas
 *   def   valor de origem
 *   from  em vez de `def`: vai buscar ao perfil do carro (backend/cars/).
 *         Preferir isto sempre que o perfil já saiba a resposta, senão o
 *         mesmo número fica escrito em dois sítios e acabam por discordar.
 *
 * ── Secções ──────────────────────────────────────────────────────────────
 *
 *   future: true   marca uma secção ou curva que o firmware ainda não lê.
 *                  Aparece esbatida e desativada, em vez de fingir que faz
 *                  alguma coisa.
 *
 * ── Curvas com ajuste ────────────────────────────────────────────────────
 *
 *   Uma curva com bloco `fit` trata a tabela editável como MEDIDAS e desenha
 *   por cima o polinómio que melhor lhes assenta, por mínimos quadrados. É só
 *   visual: o que vai para o BMS são os pares de valores da tabela.
 *
 *     fit: {
 *       source:    'ocv_points',    // a tabela de medidas
 *       degreeKey: 'ocv_fit_degree', degreeDef: 4, degreeMin: 1, degreeMax: 7,
 *     }
 *
 *   O grau fica à vista porque não há um valor certo. Mais grau passa mais
 *   perto das medidas e ondula entre elas; a página mostra o erro e avisa a
 *   vermelho quando a curva deixa de ser monótona — nesse caso a mesma tensão
 *   corresponde a dois estados de carga e a estimativa de SOC deixa de ter
 *   inversa.
 */

import { CSS, fanPwm, dclTemp, dclSoc, fmt } from './curves.js';


// ── Texto da página ────────────────────────────────────────────────────────
// O que aparece no topo e nas faixas de aviso.

export const TEXT = {
  intro: [
    'Configuração do BMS',
  ],
  curvesTitle: 'Curvas',
  lockTitle: 'Configuração bloqueada',
  lockHint: 'Desliga a alta tensão para poder editar.',
  lockPrecharge: 'A alta está ligada, ímpossível configurar.',
  lockMaster: (state) => `O AMS está em ${state}.`,
  warnTitle: 'Valores incoerentes',
  warnBody: 'Campos com valores não permitidos.',
};

// Estados em que o pack está energizado ou a mexer. Editar limites em qualquer
// um deles é o pior momento possível, por isso a página fecha.
export const LOCKED_MASTER = ['ONMISSION', 'CHARGING'];
export const LOCKED_PRECHARGE = 'HV_ON';


// ── Definições ────────────────────────────────────────────────────────────

export const SECTIONS = [
  {
    id: 'cell',
    label: 'Limites de célula',
    note: 'Por paralelo',
    fields: [
      { k: 'v_min', label: 'Tensão mínima', unit: 'V', min: 2.0, max: 4.0, step: 0.01, dec: 2,
        from: (c) => c.limits.v_min },
      { k: 'v_warn_low', label: 'Aviso de tensão baixa', unit: 'V', min: 2.0, max: 4.0, step: 0.01, dec: 2,
        from: (c) => c.limits.v_warn_low },
      { k: 'v_warn_high', label: 'Aviso de tensão alta', unit: 'V', min: 3.0, max: 4.5, step: 0.01, dec: 2,
        from: (c) => c.limits.v_warn_high },
      { k: 'v_max', label: 'Tensão máxima', unit: 'V', min: 3.0, max: 4.5, step: 0.01, dec: 2,
        from: (c) => c.limits.v_max },
      { k: 'v_open_wire', label: 'Limite de openwire', unit: 'V', min: 0.5, max: 3.0, step: 0.01, dec: 2,
        from: (c) => c.limits.v_open_wire,
        help: 'Abaixo dest valor, considera-se em openwire.' },
      { k: 'temp_warn', label: 'Aviso de temperatura', unit: '°C', min: 20, max: 80, step: 1, dec: 0,
        from: (c) => c.limits.temp_warn },
      { k: 'temp_fault', label: 'Falha de temperatura', unit: '°C', min: 20, max: 90, step: 1, dec: 0,
        from: (c) => c.limits.temp_fault },
    ],
  },
  {
    id: 'charge',
    label: 'Carregamento',
    note: 'Carregador TC Charger 6.6KW HK-LF-540-12, 14A máximo',
    fields: [
      { k: 'charge_v_max', label: 'Tensão máxima de carregamento', unit: 'V', min: 0, max: 650, step: 0.5, dec: 1, def: 600 },
      { k: 'charge_a_max', label: 'Corrente máxima de carregamento', unit: 'A', min: 0, max: 6, step: 0.1, dec: 1, def: 6 },
      { k: 'contactor_open_a', label: 'Corrente para abrir contactores', unit: 'A', min: 0, max: 5, step: 0.05, dec: 2, def: 0.5,
        help: 'O firmware espera que a corrente desca abaixo disto antes de para o carregamento. Máximo 5 s.' },
    ],
  },
  {
    id: 'balancing',
    label: 'Balanceamento',
    note: '',
    fields: [
      { k: 'bal_v_min', label: 'Descartar abaixo de', unit: 'V', min: 2.0, max: 4.0, step: 0.01, dec: 2, def: 3.0 },
      { k: 'bal_v_max', label: 'Descartar acima de', unit: 'V', min: 3.0, max: 4.5, step: 0.01, dec: 2, def: 4.25 },
      { k: 'bal_delta_mv', label: 'Delta', unit: 'mV', min: 1, max: 200, step: 1, dec: 0, def: 20,
        help: 'Delta máximo admito.' },
      { k: 'bal_die_temp', label: 'Parar aos', unit: '°C', min: 40, max: 120, step: 1, dec: 0, def: 85,
        help: 'Temperatura dos chips, não das células.' },
      { k: 'bal_pulse_ms', label: 'Duração do pulso', unit: 'ms', min: 100, max: 1800, step: 50, dec: 0, def: 1500,
        help: 'Duração de descarga .' },
    ],
  },
  {
    id: 'fans',
    label: 'Ventoinhas',
    note: 'Curva da velocidade das ventoinhas.',
    fields: [
      { k: 'fan_temp_start', label: 'Começam a', unit: '°C', min: 0, max: 60, step: 1, dec: 0, def: 30 },
      { k: 'fan_temp_full', label: 'Máximo a', unit: '°C', min: 20, max: 90, step: 1, dec: 0, def: 55 },
      { k: 'fan_pwm_min', label: 'PWM mínimo', unit: '%', min: 0, max: 100, step: 1, dec: 0, def: 20 },
      { k: 'fan_pwm_max', label: 'PWM máximo', unit: '%', min: 0, max: 100, step: 1, dec: 0, def: 100 },
    ],
  },
  {
    id: 'precharge',
    label: 'Pré-carga',
    fields: [
      { k: 'pre_timeout_s', label: 'Tempo máximo da sequência', unit: 's', min: 1, max: 60, step: 1, dec: 0, def: 10 },
      { k: 'pre_bus_pct', label: 'Tensão do bus para concluir', unit: '%', min: 50, max: 100, step: 1, dec: 0, def: 95 },
      { k: 'pre_air_timeout', label: 'Espera por cada AIR', unit: 'ms', min: 50, max: 5000, step: 50, dec: 0, def: 500,
        help: 'Tempo de espera antes de verificar a resposta do AIR.' },
    ],
  },
  {
    id: 'future',
    label: 'todo',
    note: 'nao implementado',
    future: true,
    fields: [
      { k: 'dcl_max_a', label: 'Limite de descarga', unit: 'A', min: 0, max: 600, step: 5, dec: 0, def: 300 },
      { k: 'dcl_derate_temp_hi', label: 'Derating acima de', unit: '°C', min: 20, max: 90, step: 1, dec: 0, def: 45 },
      { k: 'dcl_derate_soc_lo', label: 'Derating abaixo de', unit: '%', min: 0, max: 50, step: 1, dec: 0, def: 15 },
      { k: 'soh_nominal_ah', label: 'Capacidade nominal', unit: 'Ah', min: 1, max: 100, step: 0.5, dec: 1,
        from: (c) => c.cell_capacity_ah * c.parallel_strings },
    ],
  },
];

// ── Curvas ────────────────────────────────────────────────────────────────

export const CURVES = [
  {
    id: 'ocv',
    label: 'Tensão da célula x SOC',
    note: 'Mede pares tensão/SOC em repouso e escreve-os abaixo.',
    editable: { key: 'ocv_points', cols: ['SOC [%]', 'Tensão [V]'], dec: [0, 3] },
    // O que vai para o BMS são os pares de valores da tabela, não a equação.
    // O ajuste é visual: mostra a forma que as medidas descrevem e onde uma
    // delas destoa das outras.
    fit: {
      source: 'ocv_points',
      degreeKey: 'ocv_fit_degree', degreeDef: 4, degreeMin: 1, degreeMax: 7,
    },
    axes: { xMin: 0, xMax: 100, xStep: 10, xLabel: 'SOC [%]',
            yMin: 2.4, yMax: 4.4, yStep: 0.2, yLabel: 'Tensão da célula [V]', yDec: 1 },
    marks: (v) => [
      { y: v.v_max, color: CSS.fault, label: `máx ${fmt(v.v_max, 2)} V` },
      { y: v.v_warn_high, color: CSS.warn, label: `aviso ${fmt(v.v_warn_high, 2)} V` },
      { y: v.v_warn_low, color: CSS.warn, label: `aviso ${fmt(v.v_warn_low, 2)} V` },
      { y: v.v_min, color: CSS.fault, label: `mín ${fmt(v.v_min, 2)} V` },
    ],
  },
  {
    id: 'fan',
    label: 'Ventoinhas x Temperatura',
    note: 'Rampa de aceleração das ventoinmhas.',
    axes: { xMin: 0, xMax: 90, xStep: 10, xLabel: 'Temperatura máxima do Acumulador [°C]',
            yMin: 0, yMax: 100, yStep: 20, yLabel: 'PWM [%]' },
    series: (v) => {
      const pts = [];
      for (let t = 0; t <= 90; t += 1) pts.push([t, fanPwm(v, t)]);
      return [{ points: pts, color: CSS.ok }];
    },
    marks: (v) => [
      { x: v.temp_warn, color: CSS.warn, label: 'aviso' },
      { x: v.temp_fault, color: CSS.fault, label: 'falha' },
    ],
  },
  {
    id: 'dcl',
    label: 'Limite de descarga',
    note: 'Derating por temperatura e por estado de carga.',
    future: true,
    axes: { xMin: 0, xMax: 100, xStep: 10, xLabel: 'Temperatura [°C]  ·  SOC [%]',
            yMin: 0, yMax: 400, yStep: 50, yLabel: 'Corrente [A]' },
    series: (v) => {
      const byTemp = [];
      for (let t = 0; t <= 100; t += 2) byTemp.push([t, dclTemp(v, t)]);
      const bySoc = [];
      for (let s = 0; s <= 100; s += 2) bySoc.push([s, dclSoc(v, s)]);
      return [
        { points: byTemp, color: CSS.cool, label: 'por temperatura' },
        { points: bySoc, color: CSS.accent, dash: [5, 4], label: 'por SOC' },
      ];
    },
    marks: (v) => [{ y: v.dcl_max_a, color: CSS.mute, label: `máx ${fmt(v.dcl_max_a, 0)} A` }],
  },
  {
    id: 'tempcomp',
    label: 'Compensação por temperatura',
    note: 'Correção da tensão lida e resistência interna esperada, por temperatura.',
    future: true,
    editable: { key: 'tempcomp_points', cols: ['Temp [°C]', 'Ajuste [mV]', 'R [mΩ]'], dec: [0, 1, 1] },
    axes: { xMin: -20, xMax: 80, xStep: 10, xLabel: 'Temperatura [°C]',
            yMin: -20, yMax: 40, yStep: 10, yLabel: 'mV  ·  mΩ' },
    series: (v) => [
      { points: (v.tempcomp_points || []).map((p) => [p.t, p.mv]),
        color: CSS.accent, dots: true, label: 'ajuste [mV]' },
      { points: (v.tempcomp_points || []).map((p) => [p.t, p.r]),
        color: CSS.cool, dots: true, label: 'resistência [mΩ]' },
    ],
  },
];

// ── Pontos de origem das curvas ───────────────────────────────────────────

/**
 * A curva OCV é uma NMC típica normalizada, esticada entre os limites DESTE
 * carro. Escrever tensões absolutas aqui daria números errados no dia em que
 * a química mudar.
 */
export function defaultCurvePoints(car) {
  const lo = car?.limits?.v_min ?? 2.5;
  const hi = car?.limits?.v_max ?? 4.2;
  const norm = [[0, 0.00], [5, 0.18], [10, 0.30], [20, 0.44], [30, 0.52],
                [40, 0.57], [50, 0.61], [60, 0.66], [70, 0.73], [80, 0.82],
                [90, 0.92], [100, 1.00]];
  return {
    ocv_points: norm.map(([soc, n]) => ({ soc, v: +(lo + n * (hi - lo)).toFixed(3) })),
    tempcomp_points: [-20, -10, 0, 10, 20, 30, 40, 50, 60, 70].map((t) => ({
      t, mv: 0, r: +(Math.max(2, 14 - t * 0.18)).toFixed(1),
    })),
  };
}
