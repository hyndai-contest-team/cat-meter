// 탑승 등록 서버의 요청 처리.
//
// 차량 앱
//   PUT  /api/rides/:code                         탑승 QR 을 띄울 때 이동을 연다 → 휴대폰 주소(joinUrl), 차량 전용 토큰(ownerToken)
//   GET  /api/rides/:code                         탑승자 목록 (이름, 출퇴근 여부, 결제 수단 이름, 결제 결과)
//   POST /api/rides/:code/alights                 하차 판단 (좌석이 빔). Authorization: Bearer <ownerToken>
//   POST /api/rides/:code/charges                 사람별 금액 자동결제 (테스트). 먼저 내린 사람은 내릴 때, 남은 사람은 도착 때. Authorization: Bearer <ownerToken>
// 승객 휴대폰
//   GET  /ride/:code                              탑승 등록 페이지
//   POST /api/rides/:code/passengers              이름·출퇴근 여부를 넣어 탑승 → 내 탑승 번호(id)
//   POST /api/rides/:code/passengers/:id/payment  테스트 결제 수단 등록
//   POST /api/rides/:code/passengers/:id/seat     자리 이동 (같은 휴대폰이 다른 좌석 QR 을 찍었을 때)
//   POST /api/rides/:code/passengers/:id/stay     하차로 잘못 판단됐을 때 '아직 타고 있어요' (결제 전에만)
//   GET  /api/rides/:code/passengers/:id          내 탑승 정보와 결제 결과
//
// 개인정보는 탑승자 이름만 받는다. 카드·계좌·전화번호는 받지 않는다. 결제는 모두 테스트(실제 출금 없음)다.

import { readFile } from 'node:fs/promises';
import { RideError } from './store.mjs';

/** 차량 앱의 탑승 코드 규칙 (trip/Boarding.kt): 0·O·1·I·L 을 뺀 8글자 */
const CODE_PATTERN = /^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$/;
const ID_PATTERN = /^[0-9a-f]{16}$/;
const MAX_BODY_BYTES = 2048;
const RIDE_PAGE = new URL('../public/ride.html', import.meta.url);

const ERROR_STATUS = {
  not_found: 404, invalid_name: 400, duplicate: 409, full: 409,
  forbidden: 403, invalid_method: 400, invalid_charge: 400, not_commuting: 409, invalid_seat: 400,
  not_alighted: 409, already_charged: 409,
};

/**
 * @param {{ store: ReturnType<import('./store.mjs').createMemoryStore>, publicUrl: string }} deps
 * publicUrl: 승객 휴대폰이 이 서버에 접속할 주소 (예: http://192.168.0.10:8787)
 */
export function createHandler({ store, publicUrl }) {
  let ridePage;

  return async function handle(req, res) {
    try {
      const url = new URL(req.url, 'http://localhost');
      const parts = url.pathname.split('/').filter(Boolean);

      if (req.method === 'GET' && url.pathname === '/health') {
        return sendJson(res, 200, { ok: true });
      }

      // GET /ride/:code → 탑승 등록 페이지 (코드 확인은 페이지가 API 로 한다)
      if (req.method === 'GET' && parts[0] === 'ride' && parts.length === 2) {
        ridePage ??= await readFile(RIDE_PAGE);
        res.writeHead(200, {
          'Content-Type': 'text/html; charset=utf-8',
          'Cache-Control': 'no-store',
          'X-Content-Type-Options': 'nosniff',
          'Referrer-Policy': 'no-referrer',
        });
        return res.end(ridePage);
      }

      if (parts[0] === 'api' && parts[1] === 'rides' && parts.length >= 3) {
        const code = parts[2].toUpperCase();
        if (!CODE_PATTERN.test(code)) return sendJson(res, 400, { error: 'invalid_code', message: '탑승 코드 형식이 맞지 않아요.' });
        const rest = parts.slice(3);

        // 차량: 이동 열기
        if (rest.length === 0 && req.method === 'PUT') {
          const body = await readJson(req);
          const catName = typeof body.catName === 'string' ? body.catName.trim().slice(0, 12) : '';
          const ride = store.openRide(code, catName || '고양이', bearer(req));
          return sendJson(res, 200, { ...rideView(ride), joinUrl: `${publicUrl}/ride/${code}`, ownerToken: ride.ownerToken });
        }

        // 차량·승객: 탑승자 목록
        if (rest.length === 0 && req.method === 'GET') {
          const ride = store.getRide(code);
          if (!ride) return sendJson(res, 404, { error: 'not_found', message: '탑승 코드가 없거나 시간이 지났어요. 차 화면의 QR을 다시 찍어 주세요.' });
          return sendJson(res, 200, rideView(ride));
        }

        // 승객: 탑승
        if (rest.length === 1 && rest[0] === 'passengers' && req.method === 'POST') {
          const body = await readJson(req);
          const passenger = store.addPassenger(code, body.name, body.commuting === true, body.seat ?? null);
          return sendJson(res, 201, { passenger: passengerView(passenger), ride: rideView(store.getRide(code)) });
        }

        // 승객: 내 정보, 결제 수단 등록
        if (rest.length >= 2 && rest[0] === 'passengers' && ID_PATTERN.test(rest[1])) {
          const id = rest[1];
          if (rest.length === 2 && req.method === 'GET') {
            return sendJson(res, 200, { passenger: passengerView(store.getPassenger(code, id)), ride: rideView(store.getRide(code)) });
          }
          if (rest.length === 3 && rest[2] === 'payment' && req.method === 'POST') {
            const body = await readJson(req);
            return sendJson(res, 200, { passenger: passengerView(store.registerPayment(code, id, body.method)) });
          }
          if (rest.length === 3 && rest[2] === 'stay' && req.method === 'POST') {
            return sendJson(res, 200, { passenger: passengerView(store.stay(code, id)) });
          }
          if (rest.length === 3 && rest[2] === 'seat' && req.method === 'POST') {
            const body = await readJson(req);
            return sendJson(res, 200, { passenger: passengerView(store.moveSeat(code, id, body.seat ?? null)), ride: rideView(store.getRide(code)) });
          }
        }

        // 차량: 하차 판단
        if (rest.length === 1 && rest[0] === 'alights' && req.method === 'POST') {
          const body = await readJson(req);
          store.alight(code, bearer(req), body.name);
          return sendJson(res, 200, rideView(store.getRide(code)));
        }

        // 차량: 자동결제 (테스트)
        if (rest.length === 1 && rest[0] === 'charges' && req.method === 'POST') {
          const body = await readJson(req);
          const charges = store.chargeRide(code, bearer(req), body.tripId, body.items);
          return sendJson(res, 200, { charges, mock: true });
        }
      }

      return sendJson(res, 404, { error: 'not_found', message: '없는 주소예요.' });
    } catch (err) {
      if (err instanceof RideError) return sendJson(res, ERROR_STATUS[err.code] ?? 400, { error: err.code, message: err.message });
      if (err instanceof BadRequest) return sendJson(res, 400, { error: 'bad_request', message: err.message });
      console.error(err);
      return sendJson(res, 500, { error: 'server_error', message: '잠시 뒤 다시 시도해 주세요.' });
    }
  };
}

/** 모두에게 보여도 되는 정보: 탑승 번호(id)·빌링키·차량 토큰은 넣지 않는다 */
function rideView(ride) {
  return {
    code: ride.code,
    catName: ride.catName,
    expiresAt: ride.expiresAt,
    passengers: ride.passengers.map((p) => ({
      name: p.name,
      joinedAt: p.joinedAt,
      commuting: p.commuting,
      seat: p.seat ?? null,
      alighted: p.alightedAt != null,
      paymentLabel: p.payment?.label ?? null,
      chargeStatus: p.charge?.status ?? null,
    })),
  };
}

/** 승객 본인에게만 보여 주는 정보 (빌링키는 넣지 않는다) */
function passengerView(p) {
  return {
    id: p.id,
    name: p.name,
    commuting: p.commuting,
    seat: p.seat ?? null,
    alighted: p.alightedAt != null,
    payment: p.payment ? { method: p.payment.method, label: p.payment.label } : null,
    charge: p.charge,
  };
}

function bearer(req) {
  const m = /^Bearer\s+([0-9a-f]+)$/i.exec(req.headers.authorization ?? '');
  return m ? m[1] : null;
}

class BadRequest extends Error {}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new BadRequest('보낸 내용이 너무 커요.');
    chunks.push(chunk);
  }
  if (size === 0) return {};
  try {
    const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    return value && typeof value === 'object' ? value : {};
  } catch {
    throw new BadRequest('JSON 형식이 아니에요.');
  }
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}
