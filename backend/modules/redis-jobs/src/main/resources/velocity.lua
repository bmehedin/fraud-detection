local key = KEYS[1]
local now = tonumber(ARGV[1])
local windowStart = tonumber(ARGV[2])
local member = ARGV[3]
local ttlSeconds = tonumber(ARGV[4])

redis.call('ZADD', key, now, member)
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
redis.call('EXPIRE', key, ttlSeconds)
return redis.call('ZCARD', key)
