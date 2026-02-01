import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '30s', target: 100 },  // 워밍업
        { duration: '2m', target: 200 },   // 구간 1
        { duration: '2m', target: 300 },   // 구간 2
        { duration: '2m', target: 400 },   // 구간 3
        { duration: '2m', target: 600 },   // 구간 4
        { duration: '30s', target: 0 },    // 종료
    ],
    thresholds: {
        http_req_duration: ['p(95)<5000'], // knee point 측정용: 임계치 여유
        http_req_failed: ['rate<0.1'],     // 에러율 10% 미만
    },
};

// Windows Docker에서는 host.docker.internal 사용
const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const CONCERT_ID = 1; // 테스트할 콘서트 ID (실제 ID로 변경)

export default function () {
    // 1. 대기열 진입
    const enterRes = http.post(`${BASE_URL}/api/queue/enter?concertId=${CONCERT_ID}`, null, {
        headers: {
            'Content-Type': 'application/json',
        },
    });
    
    check(enterRes, {
        '대기열 진입 성공': (r) => r.status === 201,
    });
    
    if (enterRes.status !== 201) {
        return;
    }
    
    const token = enterRes.json('data.token');
    const rank = enterRes.json('data.rank');
    const totalWaiting = enterRes.json('data.totalWaiting');
    
    console.log(`진입 성공 - 순번: ${rank}, 대기인원: ${totalWaiting}`);
    
    // 2. 순번 폴링 (최대 50번)
    for (let i = 0; i < 50; i++) {
        const statusRes = http.get(`${BASE_URL}/api/queue/status?token=${token}&concertId=${CONCERT_ID}`);
        
        check(statusRes, {
            '순번 조회 성공': (r) => r.status === 200,
        });
        
        if (statusRes.status === 200) {
            const data = statusRes.json('data');
            const isAllowed = data.isAllowed;
            
            if (isAllowed) {
                console.log(`입장 허용됨! 순번: ${data.rank}`);
                // 입장 허용 시 좌석 조회
                const seatsRes = http.get(`${BASE_URL}/api/concerts/${CONCERT_ID}/seats`);
                check(seatsRes, {
                    '좌석 조회 성공': (r) => r.status === 200,
                });
                break;
            }
        }
        
        sleep(1); // 1초 대기 (폴링 압력 증가)
    }
}