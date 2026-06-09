#!/bin/sh
set -e

# Replace placeholder with actual password from environment variable
if [ -n "$REDIS_PASSWORD" ]; then
    sed -i "s/your_redis_password_here/$REDIS_PASSWORD/g" /usr/local/etc/redis/redis.conf
fi

# Start Redis with configuration
exec redis-server /usr/local/etc/redis/redis.conf
