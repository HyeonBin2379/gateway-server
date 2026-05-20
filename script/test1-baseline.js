import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';
import { SharedArray } from 'k6/data';

// 환경변수
// 실행 방법:
// k6 run --env NGINX_IP=34.64.xxx.xxx --env ACCOUNTS_FILE=./p_user.csv test1-baseline.js

const BASE_URL = `http://${__ENV.NGINX_IP}`;

const errorRate = new Rate('error_rate');
const latency   = new Trend('latency_ms', true);

// CSV에서 계정 목록 로드
const accounts = new SharedArray('accounts', function () {
    const csv = open(__ENV.ACCOUNTS_FILE);
    const parsed = papaparse.parse(csv, { header: true, skipEmptyLines: true });
    return parsed.data.map((row) => row.username);
});

export const options = {
    stages: [
        { duration: '20s', target: 300 }, // Ramp-Up
        { duration: '5m',  target: 300 }, // 유지
        { duration: '10s', target: 0   }, // Ramp-Down
    ],
    thresholds: {
        'error_rate':        [{ threshold: 'rate<0.05',   abortOnFail: true }],
        'http_req_duration': [{ threshold: 'p(95)<3000',  abortOnFail: true }],
    },
};

// setup()에서 VU마다 다른 토큰 발급 후 재사용 (캐시 HIT 유도)
export function setup() {
    const tokens = [];
    const target = Math.min(300, accounts.length);

    console.log(`테스트 시작: ${new Date().toISOString()}`);
    console.log(`토큰 발급 시작: ${target}개`);

    for (let i = 0; i < target; i++) {
        const res = http.post(
            `${BASE_URL}/api/v1/auth/login`,
            JSON.stringify({ username: accounts[i], password: 'password' }),
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

    console.log(`토큰 발급 완료: ${tokens.length}개`);

    if (tokens.length === 0) {
        throw new Error('발급된 토큰이 없습니다. 테스트를 중단합니다.');
    }

    return { tokens };
}

export default function (data) {
    // VU마다 다른 토큰 사용, 로그아웃 없이 재사용 → TTL 30초 내 캐시 HIT 유도
    const token = data.tokens[__VU % data.tokens.length];

    const res = http.get(`${BASE_URL}/api/v1/users/me`, {
        headers: { Authorization: token },
        timeout: '15s',
    });

    const ok = check(res, {
        'status 200':        (r) => r.status === 200,
        'latency < 3000ms':  (r) => r.timings?.duration < 3000,
    });

    if (res.timings) {
        latency.add(res.timings.duration);
    }
    errorRate.add(!ok);
}

export function teardown(data) {
    console.log(`테스트 종료: ${new Date().toISOString()}`);
}

export function handleSummary(data) {
    const { setup_data, ...rest } = data;

    return {
        'result/result-test1-baseline.json': JSON.stringify(rest, null, 2),
        stdout: textSummary(data, { indent: ' ', enableColors: true }),
    };
}