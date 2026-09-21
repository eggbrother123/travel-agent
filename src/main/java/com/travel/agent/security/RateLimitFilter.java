package com.travel.agent.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * E4 限流：按客户端 IP 的固定窗口计数，拦 /plan* 全部生成接口。
 *
 * 为什么用 Redis 而不是 Guava RateLimiter（面试核心）：
 *   模型调用是花钱的资源，限流必须全局准确。单机令牌桶在 N 实例部署下
 *   每台各自限额 N 次/分 = 实际放行 N 倍流量——限了个寂寞。集中计数才语义正确，
 *   且复用 E1 已有的 Redis，零新增中间件。
 *
 * 原子性（经典坑）：INCR 和 EXPIRE 若分两步调用，INCR 后进程挂掉 EXPIRE 未执行
 * → key 永不过期 → 该 IP 被永久限流。所以用 Lua 脚本一次执行（Redis 单线程执行脚本，
 * 天然原子）。这是 redis 限流的教科书写法。
 *
 * 固定窗口的已知缺陷（主动讲）：窗口边界突刺——59s 和 61s 各打满 N 次，
 * 2 秒内实际放行 2N。改进方向：滑动窗口（ZSET）、令牌桶（Lua）。当前场景（人肉点按钮）
 * 固定窗口够用，不提前上复杂度。
 *
 * fail-open 取舍：Redis 故障时【放行】而不是拒绝——限流器是保护措施不是业务依赖，
 * 它挂了让业务裸奔（风险：费钱）好过整个服务不可用（风险：全瘫）。若场景是防恶意攻击，
 * 则应 fail-close。取舍跟着业务错误代价走。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** INCR + 首次 EXPIRE 原子执行 */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return c
            """, Long.class);

    private static final String KEY_PREFIX = "travel:ratelimit:";
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final int requestsPerMinute;

    public RateLimitFilter(StringRedisTemplate redis, MeterRegistry registry,
                           @Value("${travel.ratelimit.requests-per-minute:5}") int requestsPerMinute) {
        this.redis = redis;
        this.registry = registry;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只保护烧钱的生成接口；actuator/静态资源/导出不限
        return !request.getRequestURI().startsWith("/plan");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 注：取 X-Forwarded-For 第一跳才是真实客户端 IP（有 LB/CDN 时）；
        // 本地/单机部署 getRemoteAddr 够用，上云要改这里（面试点）
        String ip = clientIp(request);
        String key = KEY_PREFIX + ip;

        try {
            Long count = redis.execute(RATE_LIMIT_SCRIPT,
                    List.of(key), String.valueOf(WINDOW.toSeconds()));

            if (count != null && count > requestsPerMinute) {
                registry.counter("travel.ratelimit.rejected").increment();
                System.out.println(">>> [限流] " + ip + " 触发限流（窗口内第 " + count + " 次 > "
                        + requestsPerMinute + "）");
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("请求太频繁啦（限流 " + requestsPerMinute + " 次/分钟），请约 1 分钟后再试");
                return;
            }
        } catch (Exception e) {
            // fail-open：限流器故障不阻断业务（取舍见类注释）
            System.out.println(">>> [限流] Redis 不可用，放行（fail-open）：" + e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
