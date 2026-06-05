FROM redis:7.4-alpine

# Set timezone to Korean Standard Time
ENV TZ=Asia/Seoul

# Create Redis configuration directory
RUN mkdir -p /usr/local/etc/redis

# Copy Redis configuration
COPY conf/redis.conf /usr/local/etc/redis/redis.conf

# Copy entrypoint script
COPY docker-entrypoint.sh /usr/local/bin/

# Set proper permissions
RUN chmod 644 /usr/local/etc/redis/redis.conf && \
    chmod +x /usr/local/bin/docker-entrypoint.sh

# Expose Redis default port
EXPOSE 6379

# Health check (will use password from environment)
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
    CMD redis-cli -a "$REDIS_PASSWORD" ping || exit 1

# Use custom entrypoint
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
