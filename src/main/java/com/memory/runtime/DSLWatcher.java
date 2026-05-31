package com.memory.runtime;

import com.memory.dsl.DSLParser;
import com.memory.model.MetaModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DSL 文件热重载监听器。
 * 使用 Java WatchService 监控 DSL YAML 文件变更，
 * 检测到修改后自动重新解析并原子替换 Runtime Context 中的 MetaModel。
 *
 * <p>特性：
 * <ul>
 *   <li>防抖（500ms）：避免编辑器保存时触发多次重载</li>
 *   <li>解析失败不影响旧模型：YAML 有语法错误时保留当前配置并记录日志</li>
 *   <li>守护线程：不阻塞 JVM 退出</li>
 * </ul>
 */
public class DSLWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(DSLWatcher.class);

    /** 防抖间隔（毫秒），避免短时间内重复触发 */
    private static final long DEBOUNCE_MS = 500;

    private final Path dslPath;
    private final MemoryRuntimeContext ctx;
    private final DSLParser parser;
    private final Runnable onReload;  // 热重载完成后的回调（如重新注册触发器）
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread watcherThread;
    private WatchService watchService;

    /**
     * 创建 DSL 文件监听器。
     *
     * @param dslPath  DSL YAML 文件路径（必须是已存在的文件）
     * @param ctx      运行时上下文（热更新替换其中的 MetaModel）
     * @param onReload 热重载完成后的回调（如重新注册触发器），可为 null
     */
    public DSLWatcher(Path dslPath, MemoryRuntimeContext ctx, Runnable onReload) {
        this.dslPath = dslPath.toAbsolutePath();
        this.ctx = ctx;
        this.parser = new DSLParser();
        this.onReload = onReload;
    }

    /**
     * 启动文件监听（后台守护线程）。
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            watcherThread = new Thread(this::watchLoop, "dsl-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            LOG.info("DSLWatcher 已启动，监控文件: {}", dslPath);
        }
    }

    /**
     * 停止文件监听。
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                if (watchService != null) {
                    watchService.close();
                }
            } catch (Exception ignored) {
                // 关闭失败不影响
            }
            if (watcherThread != null) {
                watcherThread.interrupt();
            }
            LOG.info("DSLWatcher 已停止");
        }
    }

    /**
     * 监听循环：等待文件事件 → 防抖 → 重新解析 → 热更新。
     */
    private void watchLoop() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path dir = dslPath.getParent();
            if (dir == null) {
                dir = Path.of(".");
            }
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

            long lastReload = 0;

            while (running.get()) {
                WatchKey key;
                try {
                    key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    break;
                }

                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    // 只关心目标文件的变更
                    if (changed == null) continue;
                    Path absolute = dir.resolve(changed);
                    if (!absolute.equals(dslPath)) continue;

                    // 防抖：距上次重载不足 DEBOUNCE_MS 则跳过
                    long now = System.currentTimeMillis();
                    if (now - lastReload < DEBOUNCE_MS) continue;
                    lastReload = now;

                    reloadModel();
                }

                boolean valid = key.reset();
                if (!valid) break;
            }
        } catch (Exception e) {
            LOG.error("DSLWatcher 监听异常: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 重新解析 DSL 文件并原子替换 MetaModel。
     * 解析失败时保留旧模型，只记录错误日志。
     */
    private void reloadModel() {
        try {
            LOG.info("检测到 DSL 文件变更，开始热重载: {}", dslPath.getFileName());
            MetaModel newModel = parser.parse(dslPath);
            MetaModel oldModel = ctx.getMetaModel();

            ctx.swapMetaModel(newModel);

            // 通知回调（如重新注册触发器）
            if (onReload != null) {
                onReload.run();
            }

            LOG.info("DSL 热重载成功: version {} → {}",
                oldModel != null ? oldModel.getVersion() : "?",
                newModel.getVersion());
        } catch (Exception e) {
            LOG.error("DSL 热重载失败，保留当前配置: {}", e.getMessage());
        }
    }

    /**
     * 是否正在运行。
     */
    public boolean isRunning() {
        return running.get();
    }
}
