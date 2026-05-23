package com.memory.engine.store;

import com.memory.spi.MemoryStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JsonMemoryStoreTest {

    private JsonMemoryStore store;
    private Path testDir;

    @BeforeEach
    void setUp() throws IOException {
        // 使用独立的测试目录，避免污染真实数据
        testDir = Files.createTempDirectory("memory-store-test-");
        store = new JsonMemoryStore();
        System.setProperty("memory.store.path", testDir.toString());
        store.init();
    }

    @AfterEach
    void tearDown() throws IOException {
        // 清理测试目录
        try (var files = Files.list(testDir)) {
            files.forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) {}
            });
        }
        Files.deleteIfExists(testDir);
    }

    @Test
    @Order(1)
    void nameReturnsJson() {
        assertEquals("json", store.name());
    }

    @Test
    @Order(2)
    void saveAndLoad() {
        store.save("mem-1", "{\"content\":\"hello\",\"type\":\"fact\"}");
        String loaded = store.load("mem-1");
        assertNotNull(loaded);
        assertEquals("{\"content\":\"hello\",\"type\":\"fact\"}", loaded);
    }

    @Test
    @Order(3)
    void loadNonexistentReturnsNull() {
        assertNull(store.load("nonexistent"));
    }

    @Test
    @Order(4)
    void saveOverwrites() {
        store.save("mem-1", "{\"version\":1}");
        store.save("mem-1", "{\"version\":2}");
        assertEquals("{\"version\":2}", store.load("mem-1"));
    }

    @Test
    @Order(5)
    void deleteExistingReturnsTrue() {
        store.save("mem-1", "{\"data\":\"test\"}");
        assertTrue(store.delete("mem-1"));
        assertNull(store.load("mem-1"));
    }

    @Test
    @Order(6)
    void deleteNonexistentReturnsFalse() {
        assertFalse(store.delete("nonexistent"));
    }

    @Test
    @Order(7)
    void listAllReturnsAllIds() {
        store.save("mem-1", "{}");
        store.save("mem-2", "{}");
        store.save("mem-3", "{}");

        Set<String> ids = store.listAll();
        assertEquals(3, ids.size());
        assertTrue(ids.contains("mem-1"));
        assertTrue(ids.contains("mem-2"));
        assertTrue(ids.contains("mem-3"));
    }

    @Test
    @Order(8)
    void listAllReflectsDeletes() {
        store.save("mem-1", "{}");
        store.save("mem-2", "{}");
        store.delete("mem-1");

        Set<String> ids = store.listAll();
        assertEquals(1, ids.size());
        assertTrue(ids.contains("mem-2"));
    }

    @Test
    @Order(9)
    void persistenceAcrossReinit() throws IOException {
        // 写入数据
        store.save("persist-1", "{\"persistent\":true}");

        // 新建一个 store 实例，指向同一目录
        JsonMemoryStore store2 = new JsonMemoryStore();
        System.setProperty("memory.store.path", testDir.toString());
        store2.init();

        // 数据应该还在
        assertEquals("{\"persistent\":true}", store2.load("persist-1"));
        assertTrue(store2.listAll().contains("persist-1"));
    }

    @Test
    @Order(10)
    void fileIsWrittenToDisk() throws IOException {
        store.save("mem-1", "{\"test\":true}");

        Path file = testDir.resolve("mem-1.json");
        assertTrue(Files.exists(file), "JSON file should exist on disk");
        String content = Files.readString(file);
        assertEquals("{\"test\":true}", content);
    }
}
