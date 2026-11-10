// 탑승 정보 저장소.
// 지금은 서버 메모리에 둔다 (서버를 끄면 사라진다). AWS·GCP 로 옮길 때는 이 파일만 DynamoDB·Firestore 구현으로 바꾼다.
//
// 결제는 모두 테스트(가짜)다. 실제 카드·계좌 정보는 받지도 저장하지도 않는다.
// 실제 결제대행사를 붙이면 registerPayment 가 결제대행사 빌링키를, chargeRide 가 빌링키 결제 API 를 쓰게 바꾼다.

import { randomBytes } from 'node:crypto';

export const MAX_PASSENGERS = 7;
export const MAX_NAME_LENGTH = 10;
/** 탑승 코드는 이동 하나에만 쓴다. 출발 전 QR 을 띄운 뒤 이 시간이 지나면 탈 수 없다. */
export const RIDE_TTL_MS = 3 * 60 * 60 * 1000;
export const MAX_CHARGE_WON = 1_000_000;

/** 테스트 결제 수단. 실제 결제창 없이 버튼만 누르면 등록된다. */
export const PAYMENT_METHODS = {
  kakaopay: '카카오페이',
  naverpay: '네이버페이',
  tosspay: '토스페이',
  card: '테스트카드 ••••1234',
};

/** 동승석 이름 (차량 앱 trip/Seats.kt 와 같다). 운전석(ROW_1_LEFT)은 뺀다. */
export const SEATS = ['ROW_1_CENTER', 'ROW_1_RIGHT', 'ROW_2_LEFT', 'ROW_2_CENTER', 'ROW_2_RIGHT', 'ROW_3_LEFT', 'ROW_3_CENTER', 'ROW_3_RIGHT'];

export class RideError extends Error {
  /** @param {'not_found' | 'full' | 'duplicate' | 'invalid_name' | 'forbidden' | 'invalid_method' | 'invalid_charge' | 'not_commuting' | 'invalid_seat' | 'not_alighted' | 'already_charged'} code */
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

const token = (bytes) => randomBytes(bytes).toString('hex');

export function createMemoryStore({ now = () => Date.now() } = {}) {
  /** @type {Map<string, any>} */
  const rides = new Map();

  function live(code) {
    const ride = rides.get(code);
    if (!ride) return null;
    if (ride.expiresAt <= now()) {
      rides.delete(code);
      return null;
    }
    return ride;
  }

  function mustRide(code) {
    const ride = live(code);
    if (!ride) throw new RideError('not_found', '탑승 코드가 없거나 시간이 지났어요. 차 화면의 QR을 다시 찍어 주세요.');
    return ride;
  }

  /** 좌석을 정한다. 그 자리에 이미 다른 사람이 있으면 그 사람은 자리를 옮긴 것으로 보고 자리를 비운다. */
  function seatPassenger(ride, passenger, seat) {
    if (seat == null) return;
    if (!SEATS.includes(seat)) throw new RideError('invalid_seat', '좌석 정보가 맞지 않아요. 차 화면의 QR을 다시 찍어 주세요.');
    for (const other of ride.passengers) if (other !== passenger && other.seat === seat) other.seat = null;
    passenger.seat = seat;
  }

  function mustOwner(ride, ownerToken) {
    if (!ownerToken || ownerToken !== ride.ownerToken) throw new RideError('forbidden', '이 이동을 연 차량만 할 수 있어요.');
  }

  return {
    /**
     * 차량 앱이 탑승 QR 을 띄울 때 부른다. 처음 열면 차량 전용 토큰(ownerToken)을 만들어 돌려준다.
     * 이미 열린 이동은 같은 토큰을 가진 차량만 다시 열 수 있다 (탑승자는 그대로 둔다).
     */
    openRide(code, catName, ownerToken) {
      const existing = live(code);
      if (existing) {
        mustOwner(existing, ownerToken);
        existing.catName = catName;
        return existing;
      }
      const t = now();
      const ride = { code, catName, ownerToken: token(16), openedAt: t, expiresAt: t + RIDE_TTL_MS, passengers: [], chargedTripId: null };
      rides.set(code, ride);
      return ride;
    },

    getRide(code) {
      return live(code);
    },

    /**
     * 승객이 휴대폰에서 이름을 넣을 때 부른다.
     * commuting: 승객이 직접 고른 '출퇴근 길이에요'. seat: 좌석 QR 로 들어왔으면 그 자리 (차량이 벨트·좌석 센서로 감지한 자리)
     */
    addPassenger(code, rawName, commuting, seat = null) {
      const ride = mustRide(code);
      const name = normalizeName(rawName);
      if (!name) throw new RideError('invalid_name', `이름을 1~${MAX_NAME_LENGTH}자로 넣어 주세요.`);
      if (ride.passengers.some((p) => p.name === name)) {
        throw new RideError('duplicate', '같은 이름이 이미 탔어요. 다른 이름을 넣어 주세요.');
      }
      if (ride.passengers.length >= MAX_PASSENGERS) {
        throw new RideError('full', `이 차에는 최대 ${MAX_PASSENGERS}명까지 탈 수 있어요.`);
      }
      if (seat != null && !SEATS.includes(seat)) throw new RideError('invalid_seat', '좌석 정보가 맞지 않아요. 차 화면의 QR을 다시 찍어 주세요.');
      const passenger = { id: token(8), name, commuting: commuting === true, seat: null, joinedAt: now(), payment: null, charge: null, alightedAt: null };
      ride.passengers.push(passenger);
      seatPassenger(ride, passenger, seat);
      return passenger;
    },

    /** 이미 탄 승객이 다른 자리 QR 을 찍었을 때: 자리 이동 */
    moveSeat(code, id, seat) {
      const ride = mustRide(code);
      const passenger = this.getPassenger(code, id);
      if (seat == null) throw new RideError('invalid_seat', '좌석 정보가 맞지 않아요.');
      seatPassenger(ride, passenger, seat);
      return passenger;
    },

    getPassenger(code, id) {
      const passenger = mustRide(code).passengers.find((p) => p.id === id);
      if (!passenger) throw new RideError('not_found', '탑승 정보를 찾을 수 없어요. 차 화면의 QR을 다시 찍어 주세요.');
      return passenger;
    },

    /** 테스트 결제 수단 등록. 실제 서비스에서는 결제대행사 결제창이 빌링키를 발급한다. */
    registerPayment(code, id, method) {
      const passenger = this.getPassenger(code, id);
      if (!passenger.commuting) throw new RideError('not_commuting', '출퇴근 길이 아니면 결제 없이 냥만 표시해요.');
      if (!Object.hasOwn(PAYMENT_METHODS, method)) throw new RideError('invalid_method', '결제 수단을 다시 골라 주세요.');
      passenger.payment = { method, label: PAYMENT_METHODS[method], billingKey: `mock_bk_${token(8)}`, registeredAt: now() };
      return passenger;
    },

    /**
     * 차량 앱이 하차를 판단했을 때 부른다 (좌석이 비고 일정 시간이 지남).
     * 승객 휴대폰에 '하차 처리됐어요'를 보여 주고, 차량은 잠시 뒤 그 사람만 결제한다.
     */
    alight(code, ownerToken, name) {
      const ride = mustRide(code);
      mustOwner(ride, ownerToken);
      const passenger = ride.passengers.find((p) => p.name === name);
      if (!passenger) throw new RideError('not_found', '그 이름의 탑승자가 없어요.');
      passenger.alightedAt ??= now();
      return passenger;
    },

    /** 승객이 휴대폰에서 '아직 타고 있어요'를 눌렀을 때. 결제 전이면 하차를 취소한다. */
    stay(code, id) {
      const passenger = this.getPassenger(code, id);
      if (!passenger.alightedAt) throw new RideError('not_alighted', '하차 처리된 상태가 아니에요.');
      if (passenger.charge) throw new RideError('already_charged', '이미 결제됐어요. 운전자에게 말해 주세요.');
      passenger.alightedAt = null;
      return passenger;
    },

    /**
     * 차량 앱이 부른다. 사람별 금액을 테스트 결제한다.
     * 먼저 내린 사람은 내릴 때 한 명씩, 남은 사람은 도착 때 한꺼번에 부른다.
     * 이미 결제한 사람은 다시 결제하지 않고 지난 결과를 돌려준다. 한 탑승 코드는 한 이동(tripId)에만 쓴다.
     * @param {{name: string, amount: number}[]} items
     */
    chargeRide(code, ownerToken, tripId, items) {
      const ride = mustRide(code);
      mustOwner(ride, ownerToken);
      if (typeof tripId !== 'string' || !tripId || !Array.isArray(items) || items.length > MAX_PASSENGERS) {
        throw new RideError('invalid_charge', '결제 요청 형식이 맞지 않아요.');
      }
      if (ride.chargedTripId && ride.chargedTripId !== tripId) throw new RideError('invalid_charge', '이 탑승 코드는 이미 다른 이동으로 결제했어요.');

      // 하나라도 틀리면 아무도 결제하지 않는다
      for (const item of items) {
        if (!item || typeof item.name !== 'string' || !Number.isInteger(item.amount) || item.amount < 0 || item.amount > MAX_CHARGE_WON) {
          throw new RideError('invalid_charge', '결제 금액이 맞지 않아요.');
        }
      }
      const charges = items.map(({ name, amount }) => {
        const passenger = ride.passengers.find((p) => p.name === name);
        if (!passenger) return { name, amount, status: 'not_boarded' };
        if (passenger.charge) return { name, ...passenger.charge };
        if (!passenger.commuting) return { name, amount, status: 'not_commuting' };
        if (!passenger.payment) return { name, amount, status: 'no_method' };
        passenger.charge = { amount, status: 'paid', methodLabel: passenger.payment.label, receiptId: `mock_rc_${token(6)}`, paidAt: now() };
        return { name, ...passenger.charge };
      });
      ride.chargedTripId = tripId;
      return charges;
    },

    /** 지난 탑승 정보를 지운다. 서버가 주기적으로 부른다. */
    sweep() {
      for (const code of rides.keys()) live(code);
    },
  };
}

/** 앞뒤 공백과 제어 문자를 빼고, 길이를 확인한다. 맞지 않으면 null. */
export function normalizeName(raw) {
  if (typeof raw !== 'string') return null;
  const name = raw.replace(/[\u0000-\u001f\u007f]/g, '').trim();
  if (name.length === 0 || [...name].length > MAX_NAME_LENGTH) return null;
  return name;
}
