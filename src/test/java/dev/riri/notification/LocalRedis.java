package dev.riri.notification;

import redis.embedded.RedisServer;
import java.util.concurrent.CountDownLatch;

/** Docker가 없는 개발 PC에서만 사용하는 실행 도구. */
public class LocalRedis {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 16379 : Integer.parseInt(args[0]);
        RedisServer server = RedisServer.newRedisServer().port(port)
                .setting("bind 127.0.0.1").setting("maxmemory 128mb")
                .setting("save \"\"").setting("appendonly no").build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.stop(); } catch (Exception ignored) { }
        }));
        server.start();
        System.out.println("Local Redis ready on 127.0.0.1:" + port + " (temporary data)");
        new CountDownLatch(1).await();
    }
}
