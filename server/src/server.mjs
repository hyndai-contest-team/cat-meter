// 탑승 등록 서버 실행 진입점.
//   node server/src/server.mjs
// 환경 변수
//   PORT        기본 8787
//   PUBLIC_URL  승객 휴대폰이 접속할 주소. 비우면 이 컴퓨터의 와이파이 IP 로 만든다 (http://192.168.x.x:8787)

import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { createHandler } from './app.mjs';
import { createMemoryStore } from './store.mjs';

const port = Number(process.env.PORT ?? 8787);
const publicUrl = (process.env.PUBLIC_URL ?? `http://${lanAddress() ?? 'localhost'}:${port}`).replace(/\/$/, '');

const store = createMemoryStore();
setInterval(() => store.sweep(), 10 * 60 * 1000).unref();

createServer(createHandler({ store, publicUrl })).listen(port, '0.0.0.0', () => {
  console.log(`탑승 등록 서버: http://0.0.0.0:${port}`);
  console.log(`  휴대폰이 열 주소: ${publicUrl}/ride/<탑승 코드>`);
  console.log(`  에뮬레이터 앱이 쓸 주소: http://10.0.2.2:${port}`);
});

/** 사설망 IPv4 주소 하나 (와이파이·유선). 휴대폰이 같은 와이파이에 있어야 접속된다. */
function lanAddress() {
  const candidates = Object.values(networkInterfaces())
    .flat()
    .filter((a) => a && a.family === 'IPv4' && !a.internal)
    .map((a) => a.address);
  return candidates.find((ip) => /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip)) ?? candidates[0];
}
