-- 접수 상태와 큐를 같이 기록
redis.call('HSET', KEYS[2],
    'id', ARGV[1], 'recipient', ARGV[2], 'message', ARGV[3],
    'demoFailures', ARGV[4], 'demoDelayMs', ARGV[5],
    'status', 'QUEUED', 'attempts', '0', 'lastError', '',
    'createdAt', ARGV[6], 'updatedAt', ARGV[6], 'nextRetryAt', '0')
redis.call('ZADD', KEYS[3], ARGV[6], ARGV[1])
return redis.call('XADD', KEYS[1], '*', 'notificationId', ARGV[1])
