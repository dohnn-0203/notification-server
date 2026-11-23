-- 결과 기록과 ACK 사이에 서버가 종료되는 구간을 줄인다.
local previous = redis.call('HGET', KEYS[1], 'status')
if previous ~= 'SENT' and previous ~= 'FAILED' then
    redis.call('HSET', KEYS[1], 'status', ARGV[1], 'updatedAt', ARGV[2],
        'lastError', ARGV[3], 'nextRetryAt', '0')
    if ARGV[1] == 'FAILED' then
        redis.call('XADD', KEYS[3], '*', 'notificationId', ARGV[6], 'error', ARGV[3])
    end
end
return redis.call('XACK', KEYS[2], ARGV[4], ARGV[5])
