package ru.playsoftware.mini_shizuku.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 以 shell 身份执行命令的工具类。该进程由 {@code app_process} 以 shell（UID 2000）身份
 * 运行，因此这里执行的命令具备 shell 权限。
 */
public final class ShellUtil {

    /**
     * 一条命令的执行结果：合并的标准输出+标准错误，以及进程退出码。
     */
    public static final class Result {
        public final String output;
        public final int exitCode;

        public Result(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }
    }

    private ShellUtil() {
    }

    /**
     * 执行命令并等待结束，不关心输出。
     */
    public static void execute(String command) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/sh", "-c", command});
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            // 忽略，由调用方决定
        }
    }

    /**
     * 执行命令并返回其标准输出内容。
     */
    public static String execWithOutput(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/sh", "-c", command});
            return readAll(p.getInputStream());
        } catch (IOException e) {
            return "";
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /**
     * 执行命令并返回合并的标准输出+标准错误，以及进程退出码。
     * <p>
     * 与 {@link #execWithOutput(String)} 的区别：本方法会把 stderr 一并捕获（通过
     * {@code 2>&1} 合并），并等待进程结束后返回退出码，供回写客户端作为结束标记使用。
     */
    public static Result execWithOutputAndCode(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/sh", "-c", command + " 2>&1"});
            String output = readAll(p.getInputStream());
            int code = p.waitFor();
            return new Result(output, code);
        } catch (IOException e) {
            return new Result("", -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("", -1);
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static String readAll(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
