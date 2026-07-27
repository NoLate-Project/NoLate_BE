#!/bin/sh
set -e

# Redis must never start with the repository placeholder as its password.
if [ -z "${REDIS_PASSWORD:-}" ]; then
    echo "REDIS_PASSWORD must be configured" >&2
    exit 1
fi

# sed replacement strings treat "\", "&", and the delimiter as syntax. Escape
# all three so generated passwords can be copied into redis.conf verbatim.
escaped_redis_password=$(printf '%s' "$REDIS_PASSWORD" | sed 's#[/&\\]#\\&#g')
sed -i "s/your_redis_password_here/$escaped_redis_password/g" /usr/local/etc/redis/redis.conf

# Start Redis with configuration
exec redis-server /usr/local/etc/redis/redis.conf
