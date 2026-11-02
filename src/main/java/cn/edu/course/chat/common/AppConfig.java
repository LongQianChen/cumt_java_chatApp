package cn.edu.course.chat.common;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public record AppConfig(Mode mode, String host, int port, String profile,
                        Path applicationRoot, Path configFile) {
    public enum Mode { SERVER, CLIENT }

    private static final Set<String> PROPERTY_KEYS = Set.of("host", "port");
    private static final Set<String> ARGUMENT_KEYS =
            Set.of("--config", "--host", "--port", "--profile");

    public static AppConfig parse(String[] arguments) throws IOException {
        return parse(arguments, locateApplicationRoot());
    }

    public static AppConfig parse(String[] arguments, Path root) throws IOException {
        if (arguments.length == 0) {
            throw new IllegalArgumentException(usage());
        }
        Mode mode = switch (arguments[0]) {
            case "server" -> Mode.SERVER;
            case "client" -> Mode.CLIENT;
            default -> throw new IllegalArgumentException("启动模式须为 server 或 client。" + usage());
        };
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < arguments.length; index += 2) {
            String key = arguments[index];
            if (!ARGUMENT_KEYS.contains(key)) {
                throw new IllegalArgumentException("未知参数：" + key);
            }
            if (mode == Mode.SERVER && key.equals("--profile")) {
                throw new IllegalArgumentException("server 不接受 --profile");
            }
            if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--")
                    || arguments[index + 1].isBlank()) {
                throw new IllegalArgumentException("参数缺少有效值：" + key);
            }
            if (options.putIfAbsent(key, arguments[index + 1]) != null) {
                throw new IllegalArgumentException("参数重复：" + key);
            }
        }
        Path applicationRoot = root.toAbsolutePath().normalize();
        String defaultFile = mode == Mode.SERVER ? "server.properties" : "client.properties";
        Path suppliedPath = Path.of(options.getOrDefault("--config", "config/" + defaultFile));
        Path configFile = (suppliedPath.isAbsolute() ? suppliedPath : applicationRoot.resolve(suppliedPath))
                .toAbsolutePath().normalize();
        if (Files.size(configFile) > 65_536) {
            throw new IllegalArgumentException("配置文件超过 64 KiB：" + configFile);
        }
        Properties properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("配置键重复：" + key);
                }
                return super.put(key, value);
            }
        };
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (String key : properties.stringPropertyNames()) {
            if (!PROPERTY_KEYS.contains(key)) {
                throw new IllegalArgumentException("未知配置键：" + key);
            }
        }
        String host = options.getOrDefault("--host", properties.getProperty("host", "172.16.1.10"));
        Validation.host(host);
        String portValue = options.getOrDefault("--port", properties.getProperty("port", "5000"));
        if (!portValue.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException("端口须为 1～65535：" + portValue);
        }
        int port = Integer.parseInt(portValue);
        Validation.port(port);
        String profile = mode == Mode.CLIENT
                ? Validation.profile(options.getOrDefault("--profile", "default")) : "";
        return new AppConfig(mode, host, port, profile, applicationRoot, configFile);
    }

    public Path dataRoot() {
        return applicationRoot.resolve("data").resolve("client");
    }

    public boolean developmentLoopback() {
        return host.startsWith("127.");
    }

    public static Path locateApplicationRoot() {
        final Path location;
        try {
            location = Path.of(AppConfig.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException failure) {
            throw new IllegalStateException("无法确定应用目录", failure);
        }
        Path parent = location.getParent();
        if (parent != null && parent.getFileName().toString().equals("dist")
                && location.getFileName().toString().endsWith(".jar")) {
            return parent.getParent();
        }
        if (parent != null && parent.getFileName().toString().equals("build")
                && location.getFileName().toString().equals("main")) {
            return parent.getParent();
        }
        throw new IllegalStateException("应用须从 dist/chat.jar 或 build/main 启动：" + location);
    }

    public static String usage() {
        return "用法：java -jar dist/chat.jar server|client [--config 路径] [--host IPv4]"
                + " [--port 端口] [client 模式：--profile 资料名称]";
    }
}
