import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';
import { SharedArray } from 'k6/data';

// 환경변수
// 실행 방법:
// k6 run --env NGINX_IP=34.64.xxx.xxx --env ACCOUNTS_FILE=./p_user.csv test3-max-users-revised.js

const BASE_URL = `http://${__ENV.NGINX_IP}`;

// CSV에서 계정 목록 로드
const accounts = new SharedArray('accounts', function () {
    const csv = open(__ENV.ACCOUNTS_FILE);
    const parsed = papaparse.parse(csv, { header: true, skipEmptyLines: true });
    return parsed.data.map((row) => row.username);
});

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
        { duration: '20s', target: 200 }, // Ramp-Up → 200명
        { duration: '1m',  target: 200 }, // 유지
        { duration: '20s', target: 0   }, // Ramp-Down
        { duration: '20s', target: 300 }, // Ramp-Up → 300명
        { duration: '1m',  target: 300 }, // 유지
        { duration: '20s', target: 0   }, // Ramp-Down
        { duration: '20s', target: 400 }, // Ramp-Up → 400명
        { duration: '1m',  target: 400 }, // 유지
        { duration: '20s', target: 0   }, // Ramp-Down
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
// 각 단계: Ramp-Up 20s + 유지 60s + Ramp-Down 20s = 100s
function getCurrentStage(elapsedSeconds) {
    if (elapsedSeconds < 100)  return 100;
    if (elapsedSeconds < 200)  return 200;
    if (elapsedSeconds < 300)  return 300;
    if (elapsedSeconds < 400)  return 400;
    return 500;
}

// setup()에서 VU마다 다른 토큰 발급 후 재사용
export function setup() {
    console.log(`테스트 시작: ${new Date().toISOString()}`);

    const tokens = [];
    const target = Math.min(500, accounts.length);

    console.log(`토큰 발급 시작: ${target}개`);

    const password = __ENV.PASSWORD || 'password';

    for (let i = 0; i < target; i++) {
        const res = http.post(
            `${BASE_URL}/api/v1/auth/login`,
            JSON.stringify({ username: accounts[i], password }),
            {
                headers: { 'Content-Type': 'application/json' },
                timeout: '10s',
            }
        );

        if (res.status !== 200) {
            console.warn(`로그인 실패 (status ${res.status}): ${accounts[i]}`);
            continue;
        }

        try {
            const body = res.json();
            if (body && body.success && body.data && body.data.accessToken) {
                tokens.push(body.data.accessToken);
            } else {
                console.warn(`토큰 없음: ${accounts[i]}`);
            }
        } catch (e) {
            console.warn(`JSON 파싱 실패: ${accounts[i]} - ${e}`);
        }
    }

    console.log(`토큰 발급 완료: ${tokens.length}개 (최대 동시 접속자 수 탐색)`);

    if (tokens.length === 0) {
        throw new Error('발급된 토큰이 없습니다. 테스트를 중단합니다.');
    }

    return { tokens, startTime: Date.now() };
}

export default function (data) {
    // VU마다 다른 토큰 사용
    const token = data.tokens[__VU % data.tokens.length];

    const res = http.get(`${BASE_URL}/api/v1/users/me`, {
        headers: { Authorization: token },
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
            stageResults[`result/result-test3-stage-${vu}vu.json`] = JSON.stringify({
                vu,
                latency: l.values,
                errorRate: e.values,
            }, null, 2);
        }
    });

    return {
        'result/result-test3-max-users-revised.json': JSON.stringify(rest, null, 2),
        ...stageResults,
        stdout: textSummary(data, { indent: ' ', enableColors: true }) + stageSummary,
    };
}