// Noches de ejemplo («Lucía», del 11 al 25 de octubre), las mismas de las capturas de Google Play.
// Se guardan solo en el emulador (IndexedDB de la app); devuelve el id de la última noche, la completa.
(async () => {
  const {DB} = window.__EN;
  const D = (y, m, d, h, mi) => new Date(y, m - 1, d, h, mi).getTime();
  const rnd = (() => { let x = 7; return () => (x = (x * 16807) % 2147483647) / 2147483647; })();
  // 14 noches: del 11/12 al 24/25 de octubre. [día, acostarse h:m, levantarse h:m, min roncando, etiquetas, movimientos]
  const plan = [
    [11, [23, 50], [7, 20], 48, ["cena"], 26], [12, [0, 15], [7, 40], 41, ['alcohol'], 30], [13, [23, 35], [7, 5], 18, [], 16],
    [14, [23, 40], [7, 10], 33, ['cena'], 24], [15, [23, 30], [6, 55], 15, ['deporte'], 14], [16, [0, 5], [7, 30], 38, ['cafe'], 27],
    [17, [23, 45], [7, 15], 20, [], 19], [18, [0, 30], [8, 40], 46, ['alcohol', 'cena'], 33], [19, [23, 55], [7, 45], 24, [], 21],
    [20, [23, 30], [7, 0], 16, ['deporte'], 15], [21, [23, 40], [7, 10], 19, [], 17], [22, [23, 35], [7, 5], 31, ['cafe'], 25],
    [23, [23, 50], [7, 15], 17, [], 16],
  ];
  const noches = [];
  for (const [d, a, l, ronq, tags, nmov] of plan) {
    const ini = D(2026, 10, a[0] < 12 ? d : d - 1, a[0], a[1]), fin = D(2026, 10, d, l[0], l[1]);
    const dur = (fin - ini) / 1000, mov = Array.from({length: nmov}, () => Math.round(rnd() * dur)).sort((p, q) => p - q);
    noches.push({id: ini, inicio: ini, fin, fuente: 'micro', ronquidos: Math.round(ronq / 3), voces: Math.round(rnd() * 3), otros: 1,
      segRonquido: ronq * 60, segVoz: 40, timeline: [], mov, tags, avisos: 0});
  }
  // La última noche (24 → 25 oct), con todo: grabaciones, volumen, movimientos, avisos y etiquetas café + cena fuerte
  const ini = D(2026, 10, 23, 23, 40), fin = D(2026, 10, 24, 7, 5), mins = Math.round((fin - ini) / 60e3);
  const evs = [];
  const pon = (minIni, durS, tipo, conf) => evs.push({off: minIni * 60, dur: durS, tipo, conf});
  // tandas de ronquidos (más en la segunda mitad de la noche, boca arriba) + alguna voz y un ruido
  [[62, 95, .81], [66, 140, .88], [71, 60, .64], [140, 210, .92], [146, 180, .9], [152, 120, .77], [161, 75, .58],
   [255, 240, .94], [262, 200, .91], [270, 150, .86], [281, 90, .7], [300, 240, .9], [306, 200, .87], [330, 160, .89], [337, 130, .83], [365, 180, .85], [372, 120, .76], [395, 120, .79]]
    .forEach(([m, s, c]) => pon(m, s, 'ronquido', c));
  pon(28, 18, 'voz', .74); pon(410, 25, 'voz', .69); pon(214, 6, 'otro', null);
  const segR = evs.filter(e => e.tipo === 'ronquido').reduce((a, e) => a + e.dur, 0);
  const timeline = Array.from({length: mins}, (_, i) => {
    let v = 30 + rnd() * 4;
    for (const e of evs) { const a = e.off / 60, b = a + e.dur / 60; if (i >= a - 1 && i <= b + 1) v = Math.max(v, e.tipo === 'ronquido' ? 44 + rnd() * 8 : 40 + rnd() * 4); }
    return Math.round(v * 10) / 10;
  });
  const movMin = [3, 5, 8, 12, 15, 19, 23, 96, 131, 188, 199, 232, 290, 318, 352, 371, 388, 402, 416, 431, 436, 441];
  const n = {id: ini, inicio: ini, fin, fuente: 'micro', ronquidos: evs.filter(e => e.tipo === 'ronquido').length, voces: 2, otros: 1,
    segRonquido: segR, segVoz: 43, timeline, mov: movMin.map(m => m * 60 + Math.round(rnd() * 50)), tags: ['cafe', 'cena'],
    avisoT: [148 * 60, 266 * 60, 341 * 60], avisos: 3};
  noches.push(n);
  for (const x of noches) await DB.put('noches', x);
  // audio de ejemplo (ruido suave) para cada grabación
  const wav = (seg) => {
    const sr = 8000, len = Math.min(seg, 20) * sr, b = new ArrayBuffer(44 + len * 2), v = new DataView(b);
    const w = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
    w(0, 'RIFF'); v.setUint32(4, 36 + len * 2, true); w(8, 'WAVE'); w(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true);
    v.setUint16(22, 1, true); v.setUint32(24, sr, true); v.setUint32(28, sr * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    w(36, 'data'); v.setUint32(40, len * 2, true);
    for (let i = 0; i < len; i++) v.setInt16(44 + i * 2, (Math.random() - .5) * 6000 * (0.4 + 0.6 * Math.abs(Math.sin(i / sr * 1.3))), true);
    return new Blob([b], {type: 'audio/wav'});
  };
  for (const e of evs) await DB.put('eventos', {noche: n.id, off: e.off, dur: e.dur, tipo: e.tipo,
    sn: e.tipo === 'ronquido' ? e.conf : .05, vo: e.tipo === 'voz' ? e.conf : .03, br: 0, top: e.tipo === 'otro' ? 'Door' : null, peak: .4, est: false, blob: wav(e.dur)});
  window.__demoId = n.id;
  return n.id;
})()
