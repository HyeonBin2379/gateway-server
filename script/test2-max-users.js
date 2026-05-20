import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

// 환경변수
// 실행 방법:
// k6 run --env NGINX_IP=34.64.xxx.xxx test2-max-users.js

const BASE_URL = `http://${__ENV.NGINX_IP}`;

const errorRate = new Rate('error_rate');
const latency   = new Trend('latency_ms', true);

// 단계별 메트릭
const stage100Latency = new Trend('stage_100vu_latency', true);
const stage200Latency = new Trend('stage_200vu_latency', true);
const stage300Latency = new Trend('stage_300vu_latency', true);
const stage400Latency = new Trend('stage_400vu_latency', true);
const stage500Latency = new Trend('stage_500vu_latency', true);

const stage100Errors = new Rate('stage_100vu_error_rate');
const stage200Errors = new Rate('stage_200vu_error_rate');
const stage300Errors = new Rate('stage_300vu_error_rate');
const stage400Errors = new Rate('stage_400vu_error_rate');
const stage500Errors = new Rate('stage_500vu_error_rate');

export const options = {
    stages: [
        { duration: '20s', target: 100 }, // Ramp-Up → 100명
        { duration: '1m',  target: 100 }, // 유지
        { duration: '20s', target: 0   }, // Ramp-Down
        { duration: '10s', target: 0   }, // 대기
        { duration: '20s', target: 200 }, // Ramp-Up → 200명
        { duration: '1m',  target: 200 }, // 유지
        { duration: '20s', target: 0   }, // Ramp-Down
        { duration: '10s', target: 0   }, // 대기
        { duration: '20s', target: 300 }, // Ramp-Up → 300명
        { duration: '1m',  target: 300 }, // 유지
        { duration: '20s', target: 0   }, // Ramp-Down
        { duration: '10s', target: 0   }, // 대기
        { duration: '20s', target: 400 }, // Ramp-Up → 400명
        { duration: '1m',  target: 400 }, // 유지
        { duration: '20s', target: 0   }, // Ramp-Down
        { duration: '10s', target: 0   }, // 대기
        { duration: '20s', target: 500 }, // Ramp-Up → 500명
        { duration: '1m',  target: 500 }, // 유지
        { duration: '20s', target: 0   }, // Ramp-Down
    ],
    thresholds: {
        'error_rate':               [{ threshold: 'rate<0.05',  abortOnFail: true }],
        'http_req_duration':        [{ threshold: 'p(95)<3000', abortOnFail: true }],
        'stage_100vu_error_rate':   [{ threshold: 'rate<0.05',  abortOnFail: false }],
        'stage_200vu_error_rate':   [{ threshold: 'rate<0.05',  abortOnFail: false }],
        'stage_300vu_error_rate':   [{ threshold: 'rate<0.05',  abortOnFail: false }],
        'stage_400vu_error_rate':   [{ threshold: 'rate<0.05',  abortOnFail: false }],
        'stage_500vu_error_rate':   [{ threshold: 'rate<0.05',  abortOnFail: false }],
    },
};

// 단계 판별 함수 (경과 시간 기준)
// 각 단계: Ramp-Up 20s + 유지 60s + Ramp-Down 20s + 대기 10s = 110s
function getCurrentStage(elapsedSeconds) {
    if (elapsedSeconds < 110)  return 100;
    if (elapsedSeconds < 220)  return 200;
    if (elapsedSeconds < 330)  return 300;
    if (elapsedSeconds < 440)  return 400;
    return 500;
}

// setup()에서 토큰 1개 발급 후 전체 VU 재사용
export function setup() {
    console.log(`테스트 시작: ${new Date().toISOString()}`);

    const res = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ username: 'user00001', password: 'password' }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    const body = res.json();
    if (!body.success || !body.data.accessToken) {
        throw new Error(`로그인 실패: ${res.body}`);
    }

    console.log('토큰 발급 완료 (최대 동시 접속자 수 탐색)');
    return { token: body.data.accessToken, startTime: Date.now() };
}

export default function (data) {
    const res = http.get(`${BASE_URL}/api/v1/users/me`, {
        headers: { Authorization: data.token },
        timeout: '15s',
    });

    const ok = check(res, {
        'status 200':       (r) => r.status === 200,
        'latency < 3000ms': (r) => r.timings?.duration < 3000,
    });

    if (res.timings) {
        latency.add(res.timings.duration);
    }
    errorRate.add(!ok);

    // 단계별 메트릭 기록
    const elapsedSeconds = (Date.now() - data.startTime) / 1000;
    const stage = getCurrentStage(elapsedSeconds);
    const duration = res.timings?.duration;

    switch (stage) {
        case 100:
            if (duration !== undefined) stage100Latency.add(duration);
            stage100Errors.add(!ok);
            break;
        case 200:
            if (duration !== undefined) stage200Latency.add(duration);
            stage200Errors.add(!ok);
            break;
        case 300:
            if (duration !== undefined) stage300Latency.add(duration);
            stage300Errors.add(!ok);
            break;
        case 400:
            if (duration !== undefined) stage400Latency.add(duration);
            stage400Errors.add(!ok);
            break;
        case 500:
            if (duration !== undefined) stage500Latency.add(duration);
            stage500Errors.add(!ok);
            break;
    }
}

export function teardown(data) {
    console.log(`테스트 종료: ${new Date().toISOString()}`);
}

export function handleSummary(data) {
    const { setup_data, ...rest } = data;

    // 단계별 결과 요약 출력
    const stages = [100, 200, 300, 400, 500];
    let stageSummary = '\n===== 단계별 결과 요약 =====\n';

    stages.forEach(vu => {
        const latencyKey = `stage_${vu}vu_latency`;
        const errorKey   = `stage_${vu}vu_error_rate`;
        const l = data.metrics[latencyKey];
        const e = data.metrics[errorKey];

        if (l && e) {
            stageSummary += `\n[${vu} VU]\n`;
            stageSummary += `  AVG:    ${(l.values.avg).toFixed(2)}ms\n`;
            stageSummary += `  P90:    ${(l.values['p(90)']).toFixed(2)}ms\n`;
            stageSummary += `  P95:    ${(l.values['p(95)']).toFixed(2)}ms\n`;
            stageSummary += `  MAX:    ${(l.values.max).toFixed(2)}ms\n`;
            stageSummary += `  에러율: ${(e.values.rate * 100).toFixed(2)}%\n`;
        }
    });

    stageSummary += '\n============================\n';

    // 단계별 메트릭 분리 저장
    const stageResults = {};
    stages.forEach(vu => {
        const latencyKey = `stage_${vu}vu_latency`;
        const errorKey   = `stage_${vu}vu_error_rate`;
        const l = data.metrics[latencyKey];
        const e = data.metrics[errorKey];

        if (l && e) {
            stageResults[`result/result-test2-stage-${vu}vu.json`] = JSON.stringify({
                vu,
                latency: l.values,
                errorRate: e.values,
            }, null, 2);
        }
    });

    return {
        'result/result-test2-max-users.json': JSON.stringify(rest, null, 2),
        ...stageResults,
        stdout: textSummary(data, { indent: ' ', enableColors: true }) + stageSummary,
    };
}