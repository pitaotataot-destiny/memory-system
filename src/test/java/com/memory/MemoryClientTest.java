package com.memory;

import com.memory.engine.manager.SearchMgr;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryClientTest {

    private Path testDataDir;

    @BeforeEach
    void setUp() throws IOException {
        testDataDir = Files.createTempDirectory("memory-client-test-");
        System.setProperty("memory.store.path", testDataDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (testDataDir != null) {
            try (var files = Files.list(testDataDir)) {
                files.forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
            }
            Files.deleteIfExists(testDataDir);
        }
    }

    @Test
    @Order(1)
    void createFromStringWorks() {
        String yaml = minimalYaml();
        try (MemoryClient client = MemoryFactory.createFromString(yaml)) {
            assertNotNull(client);
        }
    }

    @Test
    @Order(2)
    void createAndReadMemory() {
        try (MemoryClient client = MemoryFactory.createFromString(minimalYaml())) {
            String id = client.create("fact", "{\"content\":\"hello\"}", Set.of("greeting"));
            assertNotNull(id);

            String data = client.read(id);
            assertNotNull(data);
            assertTrue(data.contains("hello"));
        }
    }

    @Test
    @Order(3)
    void updateAndDeleteMemory() {
        try (MemoryClient client = MemoryFactory.createFromString(minimalYaml())) {
            String id = client.create("fact", "{\"content\":\"original\"}", Set.of());
            client.update(id, "{\"content\":\"modified\"}");
            String data = client.read(id);
            assertNotNull(data);
            assertTrue(data.contains("modified"));

            boolean deleted = client.delete(id);
            assertTrue(deleted);
            assertNull(client.read(id));
        }
    }

    @Test
    @Order(4)
    void searchReturnsList() {
        try (MemoryClient client = MemoryFactory.createFromString(minimalYaml())) {
            // search() returns a list — even empty is valid
            var results = client.search("java");
            assertNotNull(results);
        }
    }

    @Test
    @Order(5)
    void listAndCount() {
        try (MemoryClient client = MemoryFactory.createFromString(minimalYaml())) {
            client.create("fact", "{\"content\":\"a\"}", Set.of());
            client.create("fact", "{\"content\":\"b\"}", Set.of());
            client.create("fact", "{\"content\":\"c\"}", Set.of());

            assertEquals(3, client.count());
            assertEquals(3, client.listAll().size());
        }
    }

    @Test
    @Order(6)
    void runDecayOnEmptyStore() {
        try (MemoryClient client = MemoryFactory.createFromString(minimalYaml())) {
            var summary = client.runDecay();
            assertEquals(0, summary.total());
        }
    }

    @Test
    @Order(7)
    void checkLifecycleActive() {
        try (MemoryClient client = MemoryFactory.createFromString(minimalYaml())) {
            String id = client.create("fact", "{\"content\":\"fresh\"}", Set.of());
            var status = client.checkLifecycle(id);
            assertEquals("active", status.status());
        }
    }

    @Test
    @Order(8)
    void getModelReturnsMetaModel() {
        try (MemoryClient client = MemoryFactory.createFromString(minimalYaml())) {
            assertNotNull(client.getModel());
            assertEquals("1.0", client.getModel().getVersion());
        }
    }

    @Test
    @Order(9)
    void closeStopsOperations() {
        MemoryClient client = MemoryFactory.createFromString(minimalYaml());
        client.close();

        assertThrows(IllegalStateException.class, () -> client.create("fact", "{}", Set.of()));
    }

    @Test
    @Order(10)
    void autoCloseableWorks() throws IOException {
        Path dir1 = Files.createTempDirectory("client-autoclose-");
        String yaml1 = minimalYaml().replace(testDataDir.toString().replace("\\", "/"), dir1.toString().replace("\\", "/"));

        // Create and populate via first client
        try (MemoryClient client = MemoryFactory.createFromString(yaml1)) {
            String id = client.create("fact", "{\"content\":\"auto\"}", Set.of());
            assertNotNull(id);
            assertEquals(1, client.count());
        }

        // Create fresh via second client — should see persisted data from first
        try (MemoryClient client2 = MemoryFactory.createFromString(yaml1)) {
            // Should see the persisted memory from client1
            assertEquals(1, client2.count());
            // close() was called by first client's try-with-resources
            client2.close();
            // After close, operations should throw
            assertThrows(IllegalStateException.class, () -> client2.create("fact", "{}", Set.of()));
        }

        // Cleanup
        try (var s1 = Files.list(dir1)) { s1.forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} }); }
        Files.deleteIfExists(dir1);
    }

    // ── helpers ──────────────────────────────────────────

    private String minimalYaml() {
        String safePath = testDataDir.toString().replace("\\", "/");
        return """
            version: "1.0"
            globals:
              default_type: fact
              max_memory_size: 100
              default_ttl_days: 30
              storage:
                engine: json
                path: '%s'
            types:
              fact:
                description: "事实"
                fields:
                  content: { type: string, required: true }
                tags:
                  max: 10
            decay:
              default:
                daily_decay: 0.9
                access_gain: 0.05
                min_importance: 0.1
            search:
              engines:
                keyword:
                  enabled: true
              strategies:
                default:
                  steps:
                    - engine: keyword
                      weight: 1.0
                      top_k: 10
            triggers: []
            """.formatted(safePath);
    }
}
