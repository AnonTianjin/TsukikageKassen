package com.taketori.kassen.data;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC 驱动按需加载：优先类路径，其次 {@code plugins/TsukikageKassen/drivers/*.jar}。
 *
 * <p><b>为什么不写进 plugin.yml 的 libraries</b>：Paper 的 libraries 在插件加载<b>之前</b>下载，
 * 下载失败会连带整个插件加载失败——默认用 YAML 的服（离线服尤其常见）会平白被拖死。
 * 这里改成运行期按需加载：用 YAML 就完全不碰驱动；要上 SQL 才需要把 jar 放进 drivers/。</p>
 *
 * <p><b>为什么要 DriverShim</b>：{@link DriverManager} 会按「调用方类加载器能否看见这个驱动类」
 * 过滤驱动，子加载器里的驱动实例会被它滤掉。把真实驱动包一层、用本类的类加载器注册，
 * 就能正常走 {@code DriverManager.getConnection}。</p>
 */
final class DriverLoader {

    private DriverLoader() {
    }

    /** 确保驱动可用；找不到时抛出带路径提示的异常。 */
    static void ensure(Plugin plugin, String driverClass) throws Exception {
        if (onClasspath(driverClass)) {
            return;
        }
        File dir = new File(plugin.getDataFolder(), "drivers");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new SQLException("无法创建驱动目录：" + dir);
        }
        File[] jars = dir.listFiles((ignored, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new SQLException("缺少 JDBC 驱动 " + driverClass + "：把对应的 jar 放进 "
                    + dir.getPath() + "（sqlite-jdbc / mysql-connector-j），然后重启服务器");
        }
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toURI().toURL();
        }
        URLClassLoader loader = new URLClassLoader(urls, plugin.getClass().getClassLoader());
        Class<?> clazz = Class.forName(driverClass, true, loader);
        Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        plugin.getLogger().info("已从 drivers/ 加载 JDBC 驱动：" + driverClass);
    }

    private static boolean onClasspath(String driverClass) {
        try {
            Class.forName(driverClass);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /** 把子加载器里的驱动实例转交给系统 DriverManager。 */
    private static final class DriverShim implements Driver {

        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
