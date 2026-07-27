package com.studysprout.ratelimiter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public final class RateLimiterServer {
    
    private final RedisTokenBucket redisBucket;
    private final HttpServer server;

    public RateLimiterServer(int port, String redisUrl, long capacity, double refillTokensPerSecond) throws IOException{
        this.redisBucket = new RedisTokenBucket(redisUrl, capacity, refillTokensPerSecond);
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/check", new CheckHandler()).getFilters().add(new CorsFilter());
        this.server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}")).getFilters().add(new CorsFilter());
        this.server.setExecutor(Executors.newFixedThreadPool(8));
    }

    public void start(){
        server.start();
    }

    public void stop(){
        server.stop(0);
        redisBucket.close();
    }

    private class CheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException{
            if(!"GET".equals(exchange.getRequestMethod())){
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }

            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String key = params.get("key");
            if(key == null || key.isBlank()){
                respond(exchange, 400, "{\"error\":\"missing 'key' param\"}");
                return;
            }

            int tokens = 1;
            if(params.containsKey("tokens")){
                try {
                    tokens = Integer.parseInt(params.get("tokens"));
                } catch (NumberFormatException e) {
                   respond(exchange, 400, "{\"error\":\"invalid 'tokens' param\"}");
                   return;
                }
            }

            RedisTokenBucket.Result result = redisBucket.tryConsume(key, tokens);
            String body = String.format(
                "{\"allowed\":%b,\"remaining\":%.2f}", result.allowed(), result.remainingTokens());
            respond(exchange, result.allowed() ? 200 : 429, body);
        }
    }

    private static Map<String, String> parseQuery(URI uri){
        Map<String, String> result = new HashMap<>();
        String query = uri.getRawQuery();
        if(query == null) return result;
        for(String pair: query.split("&")){
            String[] kv = pair.split("=", 2);
            if(kv.length == 2){
                result.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException{
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try(OutputStream os = exchange.getResponseBody()){
            os.write(bytes);
        }
    }

    public static void main(String[] args)throws IOException{
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        long capacity = Long.parseLong(System.getenv().getOrDefault("BUCKET_CAPACITY", "20"));
        double refillRate = Double.parseDouble(System.getenv().getOrDefault("BUCKET_REFILL_PER_SEC", "0.5"));
        String redisUrl = System.getenv("REDIS_URL");

        if(redisUrl == null || redisUrl.isBlank()){
            throw new IllegalStateException("REDIS_URL env var is required (get it from redis cloud)");
        }

        RateLimiterServer server = new RateLimiterServer(port, redisUrl, capacity, refillRate);
        server.start();
        System.out.println("Rate limiter listening on: " + port 
            + " (capacity=" + capacity + " , refill=" + refillRate + "/s, backed by Redis Cloud");
    }
}
