/**
 * 공통 부하 곡선: 단계적 램프 → 피크 VU 구간에서 부하 집중(유지) → 램프다운.
 * 스크립트마다 숫자를 고치지 않고 환경 변수만 바꾼다.
 *
 * --- 필수로 자주 쓰는 것 ---
 * - K6_PEAK_VU          최대 동시 VU (미설정 시 각 스크립트의 defaultPeak)
 * - K6_PROFILE=stress   threshold를 거의 통과만 시킴 → 에러 나도 시나리오 끝까지(knee/한계 관측)
 *
 * --- 세부 튜닝(선택) ---
 * - K6_WARM_DURATION, K6_MID_DURATION, K6_CLIMB_DURATION, K6_PEAK_HOLD, K6_RAMP_DOWN  (예: 30s, 2m, 90s)
 * - K6_WARM_VU, K6_MID_VU, K6_PREPEAK_VU  중간 단계 타깃 직접 지정(미설정 시 피크 비율로 계산)
 */

/** @returns {boolean} */
export function isStressProfile() {
  const p = (__ENV.K6_PROFILE || 'normal').toLowerCase();
  return p === 'stress' || p === 'max';
}

/**
 * @param {number} defaultPeak K6_PEAK_VU 없을 때 쓰는 스크립트별 기본 최대 VU
 */
export function peakVu(defaultPeak) {
  const raw = __ENV.K6_PEAK_VU;
  if (raw === undefined || String(raw).trim() === '') {
    return Math.max(1, defaultPeak);
  }
  const n = parseInt(String(raw), 10);
  return Number.isFinite(n) && n >= 1 ? n : Math.max(1, defaultPeak);
}

function intEnv(name, computed) {
  const raw = __ENV[name];
  if (raw === undefined || String(raw).trim() === '') {
    return computed;
  }
  const n = parseInt(String(raw), 10);
  return Number.isFinite(n) ? n : computed;
}

function durEnv(name, fallback) {
  const v = __ENV[name];
  return v && String(v).trim() !== '' ? String(v).trim() : fallback;
}

/**
 * @param {number} defaultPeak
 * @param {object} [opts]
 * @param {string} [opts.warmDur]
 * @param {string} [opts.midDur]
 * @param {string} [opts.climbDur]
 * @param {string} [opts.peakHold]
 * @param {string} [opts.rampDown]
 * @param {number} [opts.minVu] 최소 VU (기본 1)
 */
export function buildRampPeakStages(defaultPeak, opts = {}) {
  const minVu = opts.minVu ?? 1;
  const peak = Math.max(minVu, peakVu(defaultPeak));

  const pct = (f) => Math.max(minVu, Math.floor(peak * f));

  let t1 = intEnv('K6_WARM_VU', pct(0.15));
  let t2 = intEnv('K6_MID_VU', pct(0.42));
  let t3 = intEnv('K6_PREPEAK_VU', pct(0.72));

  t1 = Math.min(Math.max(minVu, t1), peak);
  t2 = Math.min(Math.max(t1, t2), peak);
  t3 = Math.min(Math.max(t2, t3), peak);

  let beforePeak = t3;
  if (peak > minVu && beforePeak >= peak) {
    beforePeak = Math.max(t1, peak - 1);
  }
  if (beforePeak < t2) {
    beforePeak = t2;
  }

  return [
    { duration: durEnv('K6_WARM_DURATION', opts.warmDur || '30s'), target: t1 },
    { duration: durEnv('K6_MID_DURATION', opts.midDur || '90s'), target: t2 },
    { duration: durEnv('K6_CLIMB_DURATION', opts.climbDur || '2m'), target: beforePeak },
    { duration: durEnv('K6_PEAK_HOLD', opts.peakHold || '2m'), target: peak },
    { duration: durEnv('K6_RAMP_DOWN', opts.rampDown || '45s'), target: 0 },
  ];
}

/**
 * @param {object} tight { http_req_duration: string[], http_req_failed: string[] }
 * @param {object} [loose] stress 시 사용
 */
export function pickThresholds(tight, loose) {
  const l =
    loose ||
    ({
      http_req_duration: ['p(95)<120000'],
      http_req_failed: ['rate<1'],
    });
  return isStressProfile() ? l : tight;
}
