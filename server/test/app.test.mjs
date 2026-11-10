import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHandler } from '../src/app.mjs';
import { createMemoryStore, RIDE_TTL_MS, MAX_PASSENGERS } from '../src/store.mjs';

const CODE = 'WPM57X8Z';

async function withServer(fn, { now } = {}) {
  const store = createMemoryStore(now ? { now } : {});
  const server = createServer(createHandler({ store, publicUrl: 'http://192.168.0.10:8787' }));
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (method, path, body, token) => {
    const headers = {};
    if (body) headers['Content-Type'] = 'application/json';
    if (token) headers.Authorization = `Bearer ${token}`;
    const res = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    const text = await res.text();
    let json;
    try { json = JSON.parse(text); } catch { json = null; }
    return { status: res.status, json, text };
  };
  try {
    await fn(call);
  } finally {
    server.close();
  }
}

/** 차량이 이동을 열고, 승객이 탑승한다 */
async function openAndJoin(call, riders = []) {
  const opened = await call('PUT', `/api/rides/${CODE}`, { catName: '나비' });
  const ids = {};
  for (const r of riders) {
    const joined = await call('POST', `/api/rides/${CODE}/passengers`, { name: r.name, commuting: r.commuting });
    ids[r.name] = joined.json.passenger.id;
    if (r.method) await call('POST', `/api/rides/${CODE}/passengers/${ids[r.name]}/payment`, { method: r.method });
  }
  return { token: opened.json.ownerToken, ids };
}

test('차량이 이동을 열면 휴대폰 주소와 차량 토큰을 받고, 승객 이름이 목록에 쌓인다', async () => {
  await withServer(async (call) => {
    const opened = await call('PUT', `/api/rides/${CODE}`, { catName: '나비' });
    assert.equal(opened.status, 200);
    assert.equal(opened.json.joinUrl, `http://192.168.0.10:8787/ride/${CODE}`);
    assert.match(opened.json.ownerToken, /^[0-9a-f]{32}$/);

    const joined = await call('POST', `/api/rides/${CODE}/passengers`, { name: '  민수 ', commuting: true });
    assert.equal(joined.status, 201);
    assert.equal(joined.json.passenger.name, '민수');
    assert.match(joined.json.passenger.id, /^[0-9a-f]{16}$/);

    const ride = await call('GET', `/api/rides/${CODE}`);
    assert.deepEqual(ride.json.passengers.map((p) => [p.name, p.commuting, p.paymentLabel]), [['민수', true, null]]);
    assert.equal(ride.json.ownerToken, undefined, '목록에는 차량 토큰이 없다');
    assert.equal(ride.json.passengers[0].id, undefined, '목록에는 승객 번호가 없다');
  });
});

test('이미 열린 이동은 같은 토큰을 가진 차량만 다시 열 수 있고, 탄 사람은 남는다', async () => {
  await withServer(async (call) => {
    const { token } = await openAndJoin(call, [{ name: '민수', commuting: true }]);
    assert.equal((await call('PUT', `/api/rides/${CODE}`, { catName: '치즈' })).status, 403);
    const reopened = await call('PUT', `/api/rides/${CODE}`, { catName: '치즈' }, token);
    assert.equal(reopened.status, 200);
    assert.equal(reopened.json.catName, '치즈');
    assert.equal(reopened.json.passengers.length, 1);
  });
});

test('열리지 않은 코드, 형식이 틀린 코드로는 탈 수 없다', async () => {
  await withServer(async (call) => {
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers`, { name: '민수' })).status, 404);
    assert.equal((await call('GET', '/api/rides/WPM5')).status, 400);
    assert.equal((await call('GET', '/api/rides/WPM57X8O')).status, 400); // O 는 쓰지 않는 글자
  });
});

test('이름 규칙: 빈 이름·11자 이상·같은 이름은 거절한다', async () => {
  await withServer(async (call) => {
    await call('PUT', `/api/rides/${CODE}`, {});
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers`, { name: '   ' })).status, 400);
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers`, { name: '가나다라마바사아자차카' })).status, 400);
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers`, { name: '민수' })).status, 201);
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers`, { name: '민수' })).status, 409);
  });
});

test(`최대 ${MAX_PASSENGERS}명까지만 탄다`, async () => {
  await withServer(async (call) => {
    await call('PUT', `/api/rides/${CODE}`, {});
    for (let i = 1; i <= MAX_PASSENGERS; i++) {
      assert.equal((await call('POST', `/api/rides/${CODE}/passengers`, { name: `승객${i}` })).status, 201);
    }
    const over = await call('POST', `/api/rides/${CODE}/passengers`, { name: '한명더' });
    assert.equal(over.status, 409);
    assert.equal(over.json.error, 'full');
  });
});

test('시간이 지난 코드로는 탈 수 없다', async () => {
  let t = 1_000_000;
  await withServer(async (call) => {
    await call('PUT', `/api/rides/${CODE}`, {});
    t += RIDE_TTL_MS + 1;
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers`, { name: '민수' })).status, 404);
  }, { now: () => t });
});

test('결제 수단 등록: 출퇴근 길인 사람만, 정해진 수단만 등록되고 빌링키는 밖으로 나가지 않는다', async () => {
  await withServer(async (call) => {
    const { ids } = await openAndJoin(call, [{ name: '민수', commuting: true }, { name: '지영', commuting: false }]);

    const ok = await call('POST', `/api/rides/${CODE}/passengers/${ids['민수']}/payment`, { method: 'kakaopay' });
    assert.equal(ok.status, 200);
    assert.deepEqual(ok.json.passenger.payment, { method: 'kakaopay', label: '카카오페이' });
    assert.doesNotMatch(ok.text, /mock_bk_/, '빌링키는 응답에 없다');

    assert.equal((await call('POST', `/api/rides/${CODE}/passengers/${ids['민수']}/payment`, { method: 'bitcoin' })).status, 400);
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers/${ids['지영']}/payment`, { method: 'card' })).status, 409);
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers/0123456789abcdef/payment`, { method: 'card' })).status, 404);

    const ride = await call('GET', `/api/rides/${CODE}`);
    assert.equal(ride.json.passengers.find((p) => p.name === '민수').paymentLabel, '카카오페이');
  });
});

test('자동결제: 결제 수단을 등록한 출퇴근 승객만 결제되고, 나머지는 이유가 남는다', async () => {
  await withServer(async (call) => {
    const { token, ids } = await openAndJoin(call, [
      { name: '민수', commuting: true, method: 'naverpay' },
      { name: '지영', commuting: true },
      { name: '철수', commuting: false },
    ]);
    const items = ['민수', '지영', '철수', '수기입력'].map((name) => ({ name, amount: 3200 }));
    const res = await call('POST', `/api/rides/${CODE}/charges`, { tripId: 't1', items }, token);
    assert.equal(res.status, 200);
    assert.equal(res.json.mock, true);
    assert.deepEqual(res.json.charges.map((c) => [c.name, c.status]), [
      ['민수', 'paid'], ['지영', 'no_method'], ['철수', 'not_commuting'], ['수기입력', 'not_boarded'],
    ]);
    assert.equal(res.json.charges[0].methodLabel, '네이버페이');

    const mine = await call('GET', `/api/rides/${CODE}/passengers/${ids['민수']}`);
    assert.equal(mine.json.passenger.charge.amount, 3200);
    assert.equal(mine.json.passenger.charge.status, 'paid');
  });
});

test('자동결제: 차량 토큰이 없으면 거절하고, 같은 이동을 다시 요청해도 두 번 결제하지 않는다', async () => {
  await withServer(async (call) => {
    const { token } = await openAndJoin(call, [{ name: '민수', commuting: true, method: 'card' }]);
    const items = [{ name: '민수', amount: 3200 }];
    assert.equal((await call('POST', `/api/rides/${CODE}/charges`, { tripId: 't1', items })).status, 403);
    assert.equal((await call('POST', `/api/rides/${CODE}/charges`, { tripId: 't1', items }, 'ab'.repeat(16))).status, 403);

    const first = await call('POST', `/api/rides/${CODE}/charges`, { tripId: 't1', items }, token);
    const again = await call('POST', `/api/rides/${CODE}/charges`, { tripId: 't1', items }, token);
    assert.equal(first.json.charges[0].receiptId, again.json.charges[0].receiptId);
    assert.equal((await call('POST', `/api/rides/${CODE}/charges`, { tripId: 't2', items }, token)).status, 400);
  });
});

test('자동결제: 금액이 하나라도 틀리면 아무도 결제하지 않는다', async () => {
  await withServer(async (call) => {
    const { token } = await openAndJoin(call, [
      { name: '민수', commuting: true, method: 'card' },
      { name: '지영', commuting: true, method: 'tosspay' },
    ]);
    const bad = await call('POST', `/api/rides/${CODE}/charges`, { tripId: 't1', items: [{ name: '민수', amount: 3200 }, { name: '지영', amount: -1 }] }, token);
    assert.equal(bad.status, 400);
    const ride = await call('GET', `/api/rides/${CODE}`);
    assert.ok(ride.json.passengers.every((p) => p.chargeStatus === null));
  });
});

test('탑승 등록 페이지와 상태 확인', async () => {
  await withServer(async (call) => {
    const page = await call('GET', `/ride/${CODE}`);
    assert.equal(page.status, 200);
    assert.match(page.text, /탑승하기/);
    assert.equal((await call('GET', '/health')).json.ok, true);
  });
});

test('너무 큰 요청은 거절한다', async () => {
  await withServer(async (call) => {
    await call('PUT', `/api/rides/${CODE}`, {});
    assert.equal((await call('POST', `/api/rides/${CODE}/passengers`, { name: 'x'.repeat(3000) })).status, 400);
  });
});
