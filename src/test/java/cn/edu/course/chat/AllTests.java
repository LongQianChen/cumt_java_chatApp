package cn.edu.course.chat;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class AllTests {
    private AllTests() {
    }

    public static void main(String[] arguments) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        long started = System.nanoTime();
        ProtocolTest.run();
        ServerTest.run();
        ConnectionTest.run();
        HistoryTest.run();
        ConfigTest.run();
        System.out.printf("纯 Java 自动测试全部通过，耗时 %.3f 秒%n",
                (System.nanoTime() - started) / 1_000_000_000.0);
    }
}
