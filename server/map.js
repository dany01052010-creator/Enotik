'use strict';
// Карта в стиле Minecraft: блоки. Сервер хранит её и отправляет клиентам.
// . трава  , тропа  = деревянный пол  # камень  T дерево  ~ вода  H кирпич  G ворота (закрыты)

function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function buildMap(seed) {
  const W = 40, H = 26;
  const g = [];
  for (let y = 0; y < H; y++) g.push(new Array(W).fill('.'));
  const set = (x, y, c) => { if (x >= 0 && y >= 0 && x < W && y < H) g[y][x] = c; };

  // тропы
  for (let x = 1; x < W - 1; x++) { set(x, 12, ','); set(x, 13, ','); }
  for (let y = 1; y < H - 1; y++) { set(19, y, ','); set(20, y, ','); }

  // пруд
  for (let y = 3; y <= 8; y++) for (let x = 4; x <= 12; x++) {
    const dx = (x - 8) / 4.6, dy = (y - 5.5) / 2.9;
    if (dx * dx + dy * dy < 1) set(x, y, '~');
  }

  // дом 1 (внизу слева) и дом 2 (вверху справа): кирпич снаружи, пол внутри
  function house(x0, y0, x1, y1, doorX, doorY) {
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
      const edge = x === x0 || x === x1 || y === y0 || y === y1;
      set(x, y, edge ? 'H' : '=');
    }
    set(doorX, doorY, '=');
  }
  house(4, 16, 13, 22, 8, 16);
  house(26, 3, 35, 9, 30, 9);

  // деревья по краю
  for (let x = 0; x < W; x++) { set(x, 0, 'T'); set(x, H - 1, 'T'); }
  for (let y = 0; y < H; y++) { set(0, y, 'T'); set(W - 1, y, 'T'); }

  // случайные деревья и камни (не на тропе и не у домов)
  const rnd = mulberry32(seed || 7);
  for (let i = 0; i < 70; i++) {
    const x = 2 + Math.floor(rnd() * (W - 4));
    const y = 2 + Math.floor(rnd() * (H - 4));
    if (g[y][x] !== '.') continue;
    if (Math.abs(y - 12.5) < 2.5 || Math.abs(x - 19.5) < 2.5) continue;
    let near = false;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const c = (g[y + dy] || [])[x + dx];
      if (c === 'H' || c === '=') near = true;
    }
    if (near) continue;
    set(x, y, rnd() < 0.78 ? 'T' : '#');
  }

  // ворота на востоке
  set(W - 1, 12, 'G'); set(W - 1, 13, 'G');

  const rows = g.map(r => r.join(''));

  // задания: мусор и генераторы
  const tasks = [];
  let id = 1;
  const add = (type, x, y) => tasks.push({ id: id++, type, x: x + 0.5, y: y + 0.5, done: false });
  // мусор (убрать)
  add('trash', 6, 18); add('trash', 11, 20); add('trash', 28, 5); add('trash', 33, 7);
  add('trash', 16, 6); add('trash', 24, 16); add('trash', 30, 20); add('trash', 3, 10);
  // генераторы (починить)
  add('gen', 8, 21); add('gen', 31, 4); add('gen', 17, 22); add('gen', 24, 2);

  const spawns = [[3, 12], [3, 13], [4, 12], [4, 13], [2, 12], [2, 13], [5, 12], [5, 13]]
    .map(p => ({ x: p[0] + 0.5, y: p[1] + 0.5 }));
  const gate = { x: W - 1 + 0.5, y: 13 };

  return { w: W, h: H, rows, tasks, spawns, gate };
}

const SOLID = new Set(['#', 'T', '~', 'H', 'G']);

function reachable(map) {
  // проверка: от спавна можно дойти до всех заданий и до ворот
  const { w, h, rows } = map;
  const seen = Array.from({ length: h }, () => new Array(w).fill(false));
  const q = [[Math.floor(map.spawns[0].x), Math.floor(map.spawns[0].y)]];
  seen[q[0][1]][q[0][0]] = true;
  while (q.length) {
    const [x, y] = q.shift();
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= w || ny >= h || seen[ny][nx]) continue;
      const c = rows[ny][nx];
      if (SOLID.has(c) && c !== 'G') continue;
      seen[ny][nx] = true;
      q.push([nx, ny]);
    }
  }
  const bad = [];
  for (const t of map.tasks) if (!seen[Math.floor(t.y)][Math.floor(t.x)]) bad.push('task ' + t.id);
  if (!seen[13][w - 1] && !seen[13][w - 2]) bad.push('gate');
  return bad;
}

module.exports = { buildMap, reachable, SOLID };
