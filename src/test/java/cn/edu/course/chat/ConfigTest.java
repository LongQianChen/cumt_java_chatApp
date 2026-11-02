package cn.edu.course.chat;

import cn.edu.course.chat.common.AppConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class ConfigTest {
    private ConfigTest() {
    }

    public static void run() throws Exception {
        Path temporaryRoot = Path.of("tmp").toAbsolutePath().normalize();
        Files.createDirectories(temporaryRoot);
        Path root = Files.createTempDirectory(temporaryRoot, "java-course-config-");
        try {
            Files.createDirectories(root.resolve("config"));
            Path client = root.resolve("config/client.properties");
            Path server = root.resolve("config/server.properties");
            Files.writeString(client, "# 中文配置\nhost=172.16.1.10\nport=5000\n", StandardCharsets.UTF_8);
            Files.writeString(server, "host=172.16.1.10\nport=5000\n", StandardCharsets.UTF_8);
            AppConfig defaults = AppConfig.parse(new String[]{"client"}, root);
            require(defaults.host().equals("172.16.1.10") && defaults.port() == 5000,
                    "默认地址与端口");
            require(defaults.localHost().equals("172.16.1.11"), "默认客户端本地地址");
            require(defaults.profile().equals("default"), "默认 profile");
            require(defaults.dataRoot().equals(root.resolve("data/client")), "资料目录固定在应用目录");
            AppConfig serverDefaults = AppConfig.parse(new String[]{"server"}, root);
            require(serverDefaults.localHost().isEmpty(), "服务端不使用客户端本地地址");
            Files.writeString(client, "# 中文配置\nlocalHost=172.16.1.12\nhost=172.16.1.10\nport=5000\n",
                    StandardCharsets.UTF_8);
            require(AppConfig.parse(new String[]{"client"}, root).localHost().equals("172.16.1.12"),
                    "客户端本地地址读取配置");
            AppConfig override = AppConfig.parse(new String[]{"client", "--host", "127.0.0.1",
                    "--local-host", "127.0.0.1", "--port", "54321", "--profile", "demo-a"}, root);
            require(override.developmentLoopback() && override.port() == 54321
                    && override.profile().equals("demo-a") && override.localHost().equals("127.0.0.1"),
                    "显式回环与参数优先级");
            AppConfig localChange = AppConfig.parse(new String[]{"client", "--local-host", "172.16.1.13"}, root);
            require(localChange.dataRoot().equals(defaults.dataRoot())
                    && localChange.profile().equals(defaults.profile())
                    && localChange.host().equals(defaults.host()) && localChange.port() == defaults.port(),
                    "更换本地地址保持资料目录、profile 和服务端目标");
            expectInvalid(root, "client", "--host", "localhost");
            expectInvalid(root, "client", "--local-host", "localhost");
            expectInvalid(root, "client", "--local-host", "0.0.0.0");
            expectInvalid(root, "client", "--local-host", "172.16.1.256");
            expectInvalid(root, "client", "--local-host", "127.0.0.1", "--local-host", "127.0.0.2");
            expectInvalid(root, "client", "--local-host");
            expectInvalid(root, "client", "--localHost", "127.0.0.1");
            expectInvalid(root, "server", "--local-host", "172.16.1.11");
            expectInvalid(root, "server", "--host", "0.0.0.0");
            expectInvalid(root, "client", "--port", "0");
            expectInvalid(root, "client", "--port", "65536");
            expectInvalid(root, "client", "--port", "1.5");
            expectInvalid(root, "client", "--profile", "../outside");
            expectInvalid(root, "server", "--profile", "demo");
            expectInvalid(root, "client", "--host", "127.0.0.1", "--host", "127.0.0.2");
            expectInvalid(root, "client", "--unknown", "value");
            expectInvalid(root, "client", "--port");
            expectInvalid(root, "client", "--host", "--port", "5000");
            expectInvalid(root, "other");
            expectInvalid(root);
            Files.writeString(server, "localHost=172.16.1.11\nhost=172.16.1.10\nport=5000\n", StandardCharsets.UTF_8);
            expectInvalid(root, "server");
            Files.writeString(client, "localHost=localhost\nhost=172.16.1.10\nport=5000\n", StandardCharsets.UTF_8);
            expectInvalid(root, "client");
            Files.writeString(client, "host=127.0.0.1\nport=5000\nunknown=true\n", StandardCharsets.UTF_8);
            expectInvalid(root, "client");
            Files.writeString(client, "host=127.0.0.1\nport=5000\nport=5001\n", StandardCharsets.UTF_8);
            expectInvalid(root, "client");
            Files.writeString(client, "host=127.0.0.1\nport=bad\n", StandardCharsets.UTF_8);
            expectInvalid(root, "client");
            boolean missingRejected = false;
            try {
                AppConfig.parse(new String[]{"client", "--config", "missing.properties"}, root);
            } catch (IOException expected) {
                missingRejected = true;
            }
            require(missingRejected, "缺少指定配置文件必须失败");
            require(AppConfig.locateApplicationRoot().resolve("config/server.properties").toFile().isFile(),
                    "从 build/main 确定应用目录");
            System.out.println("ConfigTest 通过：UTF-8 配置、本地地址与服务端地址、参数优先级、路径隔离、错误配置拒绝");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void expectInvalid(Path root, String... arguments) throws IOException {
        try {
            AppConfig.parse(arguments, root);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("错误配置未被拒绝：" + String.join(" ", arguments));
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
