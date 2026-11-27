package com.kani.kanitest;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Lives next to KanitestApplication.java; Spring finds it automatically.
@RestController
public class StorageController {

    private static final int CACHE_TTL_SECONDS = 60;
    private static final String LIST_KEY = "files:list";

    // ---------- Storage (secret comes from Key Vault via App Service setting) ----------

    private BlobContainerClient container() {
        String conn = System.getenv("STORAGE_CONNECTION_STRING");
        if (conn == null || conn.isBlank() || conn.startsWith("@Microsoft.KeyVault")) {
            throw new IllegalStateException(
                "STORAGE_CONNECTION_STRING is missing or the Key Vault reference is not resolved");
        }
        String name = System.getenv().getOrDefault("STORAGE_CONTAINER", "data");
        BlobContainerClient c = new BlobServiceClientBuilder()
                .connectionString(conn)
                .buildClient()
                .getBlobContainerClient(name);
        c.createIfNotExists();
        return c;
    }

    // ---------- Redis (host and password also come from Key Vault) ----------

    private UnifiedJedis redisClient;

    private synchronized UnifiedJedis redis() {
        if (redisClient == null) {
            String host = System.getenv("REDIS_HOST");
            String password = System.getenv("REDIS_PASSWORD");
            if (host == null || host.isBlank() || host.startsWith("@Microsoft.KeyVault")
                    || password == null || password.isBlank() || password.startsWith("@Microsoft.KeyVault")) {
                throw new IllegalStateException(
                    "REDIS_HOST / REDIS_PASSWORD is missing or the Key Vault reference is not resolved");
            }
            int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
            HostAndPort node = new HostAndPort(host, port);
            DefaultJedisClientConfig config =
                DefaultJedisClientConfig.builder().ssl(true).password(password).build();
            // Azure Managed Redis with the OSSCluster policy needs a cluster-aware client.
            // Set REDIS_CLUSTER=true for that; otherwise a normal client is used.
            if ("true".equalsIgnoreCase(System.getenv("REDIS_CLUSTER"))) {
                redisClient = new JedisCluster(Set.of(node), config);
            } else {
                redisClient = new JedisPooled(node, config);
            }
        }
        return redisClient;
    }

    // If Redis is down or not configured, the app still works straight from Storage.
    private String cacheGet(String key) {
        try { return redis().get(key); } catch (Exception e) { return null; }
    }

    private void cacheSet(String key, String value) {
        try { redis().setex(key, CACHE_TTL_SECONDS, value); } catch (Exception ignored) { }
    }

    // Keys are deleted one by one because in cluster mode they can live on different shards.
    private void cacheDelete(String... keys) {
        for (String key : keys) {
            try { redis().del(key); } catch (Exception ignored) { }
        }
    }

    private ResponseEntity<Object> fail(Exception e) {
        return ResponseEntity.status(500).body("Error: " + e.getMessage());
    }

    private long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // ---------- Endpoints ----------

    @GetMapping("/api/redis-test")
    public ResponseEntity<Object> redisTest() {
        try {
            redis().setex("health:check", 10, "ok");
            return ResponseEntity.ok("Redis replied: " + redis().get("health:check"));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/api/upload")
    public ResponseEntity<Object> upload(@RequestParam String name, @RequestParam String content) {
        try {
            container().getBlobClient(name).upload(BinaryData.fromString(content), true);
            cacheDelete("file:" + name, LIST_KEY); // cached copies are now outdated
            return ResponseEntity.ok("Uploaded " + name + " (cache cleared for this file)");
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GetMapping("/api/files")
    public ResponseEntity<Object> list() {
        long start = System.nanoTime();
        try {
            String cached = cacheGet(LIST_KEY);
            if (cached != null) {
                return ResponseEntity.ok("[from Redis cache - " + msSince(start) + " ms]\n" + cached);
            }
            List<String> names = new ArrayList<>();
            for (BlobItem item : container().listBlobs()) {
                names.add(item.getName());
            }
            String text = String.valueOf(names);
            cacheSet(LIST_KEY, text);
            return ResponseEntity.ok("[from Storage - " + msSince(start) + " ms]\n" + text);
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GetMapping("/api/files/{name:.+}")
    public ResponseEntity<Object> read(@PathVariable String name) {
        long start = System.nanoTime();
        try {
            String cached = cacheGet("file:" + name);
            if (cached != null) {
                return ResponseEntity.ok("[from Redis cache - " + msSince(start) + " ms]\n" + cached);
            }
            String data = container().getBlobClient(name).downloadContent().toString();
            cacheSet("file:" + name, data);
            return ResponseEntity.ok("[from Storage - " + msSince(start) + " ms]\n" + data);
        } catch (Exception e) {
            return fail(e);
        }
    }

    @DeleteMapping("/api/files/{name:.+}")
    public ResponseEntity<Object> delete(@PathVariable String name) {
        try {
            container().getBlobClient(name).delete();
            cacheDelete("file:" + name, LIST_KEY);
            return ResponseEntity.ok("Deleted " + name);
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GetMapping(value = "/demo", produces = "text/html")
    public String home() {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Kanitest - Storage + Redis demo</title>
              <style>
                body { font-family: sans-serif; max-width: 640px; margin: 30px auto; padding: 0 12px; }
                input, textarea { width: 100%; margin: 4px 0; padding: 6px; box-sizing: border-box; }
                button { padding: 8px 14px; margin: 4px 4px 4px 0; }
                pre { background: #f4f4f4; padding: 10px; white-space: pre-wrap; }
              </style>
            </head>
            <body>
              <h2>Kanitest - Storage + Redis demo</h2>
              <p>Secrets come from Key Vault. Reads are cached in Redis for 60 seconds.</p>
              <input id="name" placeholder="file name, e.g. hello.txt">
              <textarea id="content" rows="4" placeholder="file content"></textarea>
              <button onclick="upload()">Upload</button>
              <button onclick="list()">List files</button>
              <button onclick="readFile()">Read file</button>
              <button onclick="del()">Delete file</button>
              <button onclick="redisTest()">Test Redis</button>
              <pre id="out">Ready.</pre>
              <script>
                const out = document.getElementById('out');
                const val = id => document.getElementById(id).value;
                async function show(p) {
                  try {
                    const r = await p;
                    const t = await r.text();
                    out.textContent = (r.ok ? '' : '[' + r.status + '] ') + t;
                  } catch (e) { out.textContent = 'Request failed: ' + e; }
                }
                function upload() {
                  show(fetch('/api/upload', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: new URLSearchParams({ name: val('name'), content: val('content') })
                  }));
                }
                function list() { show(fetch('/api/files')); }
                function readFile() { show(fetch('/api/files/' + encodeURIComponent(val('name')))); }
                function del() { show(fetch('/api/files/' + encodeURIComponent(val('name')), { method: 'DELETE' })); }
                function redisTest() { show(fetch('/api/redis-test')); }
              </script>
            </body>
            </html>
            """;
    }
}
