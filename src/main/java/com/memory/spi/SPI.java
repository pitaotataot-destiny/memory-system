package com.memory.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SPI 扩展点标记注解。
 * 所有可替换组件接口和实现类必须标注此注解，
 * 供 {@link com.memory.registry.ComponentRegistry} 扫描和注册。
 *
 * <p>使用示例：
 * <pre>{@code
 * @SPI(name = "memory-store", description = "存储层扩展点",
 *      defaultImpl = JsonMemoryStore.class)
 * public interface MemoryStore { ... }
 *
 * @SPI(name = "json", description = "JSON 文件存储 + 内存索引")
 * public class JsonMemoryStore implements MemoryStore { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface SPI {

    /**
     * SPI 组件名称，全局唯一。
     * 接口标识用功能类别名（如 "memory-store"），
     * 实现类标识用引擎名（如 "json", "keyword"）。
     */
    String name();

    /**
     * 组件描述，用于文档和日志输出。
     */
    String description() default "";

    /**
     * 默认实现类。
     * 仅在接口上使用时有效，注册时如无其他实现则自动使用此默认值。
     * 实现类上不需填写。
     */
    Class<?> defaultImpl() default Void.class;
}
