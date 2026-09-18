# ZooKeeper 3.5.5 本地开发运行手册

> 分支 `dev-3.5.5`，Windows + JDK 1.8。以下提供四种本地跑法。

## 环境前提

- JDK：`C:\Program Files\Java\jdk1.8.0_503`
- Maven：3.6.3（需运行在 JDK8 上）
- 所有主类都在 `zookeeper-server` 模块：
    - Server（集群/通用）：`org.apache.zookeeper.server.quorum.QuorumPeerMain`
    - Server（单机）：`org.apache.zookeeper.server.ZooKeeperServerMain`
    - Client：`org.apache.zookeeper.ZooKeeperMain`
- 配置文件 `conf\zoo.cfg`（`dataDir=D:/data/zookeeper`，`clientPort=2181`）。首次运行前建好数据目录：
  ```bat
  if not exist D:\data\zookeeper mkdir D:\data\zookeeper
  ```

> 假设系统 `JAVA_HOME` 与 `PATH` 均已指向 JDK8（`java -version` 显示 `1.8`、`mvn -v` 的 Java version 为 `1.8`）。若未配置，可在当前
> cmd 会话临时执行：`set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_503`。

先编译一次（改代码后重跑）：

```bat
mvn -pl zookeeper-jute,zookeeper-server -am -DskipTests clean install
```

---

## (1) IDEA 直接启动 / 调试

`.idea/runConfigurations/` 下已就位 3 个配置，打开项目后右上角运行下拉框可见，可直接 Run / Debug：

| 配置名                                        | 主类                                   | 程序参数                     |
|--------------------------------------------|--------------------------------------|--------------------------|
| ZK Server (QuorumPeerMain)                 | `...server.quorum.QuorumPeerMain`    | `conf/zoo.cfg`           |
| ZK Server Standalone (ZooKeeperServerMain) | `...server.ZooKeeperServerMain`      | `conf/zoo.cfg`           |
| ZK Client (zkCli)                          | `org.apache.zookeeper.ZooKeeperMain` | `-server 127.0.0.1:2181` |

共同要点（已配好）：

- **Module**：`zookeeper-server`
- **JRE**：`C:\Program Files\Java\jdk1.8.0_503`（ALTERNATIVE_JRE，强制 JDK8）
- **Working directory**：`$PROJECT_DIR$`
- **VM options**：`-Dzookeeper.log.dir=$PROJECT_DIR$/logs -Dzookeeper.root.logger=INFO,CONSOLE`（客户端可用
  `WARN,CONSOLE`）
- **Before launch**：Make

调试：Debug 启动 Server → Run/Debug Client，在 server 端代码（如 `FinalRequestProcessor`、`ZooKeeperServer`）打断点，从 client
敲命令命中。

> 首次运行前：Project Structure → SDK 设为 JDK8；Maven Reload 让依赖就绪。

---

## (2) 命令行启动 server / client

依赖已由 `install` 复制到 `zookeeper-server/target/lib`，classpath 直接引用即可，无需布置 build 目录。

启动 **Server**（另开终端保持前台）：

```bat
set CP=zookeeper-server\target\classes;conf;zookeeper-server\target\lib\*
java -cp "%CP%" -Dzookeeper.log.dir=logs -Dzookeeper.root.logger=INFO,CONSOLE org.apache.zookeeper.server.quorum.QuorumPeerMain conf\zoo.cfg
```

启动 **Client**（交互式）：

```bat
java -cp "%CP%" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181
```

单条命令非交互执行（把命令附在末尾）：

```bat
java -cp "%CP%" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 create /hello world
java -cp "%CP%" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 get /hello
java -cp "%CP%" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 ls /
```

预期输出：`Created /hello` → `get` 返回 `world` → `ls /` 得 `[hello, zookeeper]`。

> 也可用 Maven exec 免手拼 classpath：
>
`mvn -pl zookeeper-server exec:java -Dexec.mainClass=org.apache.zookeeper.server.quorum.QuorumPeerMain -Dexec.args=conf/zoo.cfg -Dexec.classpathScope=runtime`

---

## (3) 打包命令

生成二进制发行包（含 assembly 的 tar.gz）：

```bat
mvn -pl zookeeper-assembly -am -DskipTests -Dcheckstyle.skip=true -Drat.skip=true clean package
```

产物：

- `zookeeper-assembly/target/apache-zookeeper-3.5.5-bin.tar.gz`（≈10 MB，可运行二进制包）
- `zookeeper-assembly/target/apache-zookeeper-3.5.5.tar.gz`（≈3 MB，源码包）
- 模块 jar：`zookeeper-server/target/zookeeper-3.5.5.jar` 等

只出 jar 不打 tar.gz：`mvn -pl zookeeper-jute,zookeeper-server -am -DskipTests package`。

---

## (4) bin 目录脚本启动（Windows .cmd）

**关键**：`bin\zkEnv.cmd` 原生 classpath 只认 `build\classes` + `build\lib\*`（或发行包 `..\*;..\lib\*`），**不认 Maven
的 `target`**。已在脚本中补了一行指向 Maven 模块布局：

```bat
SET CLASSPATH=%~dp0..\zookeeper-server\target\classes;%~dp0..\zookeeper-server\target\lib\*;%CLASSPATH%
```

这样 `mvn install` 之后直接跑脚本即可。改完代码只需重新 `mvn install`。

启动：

```bat
bin\zkServer.cmd                          :: 前台 standalone，监听 2181
bin\zkCli.cmd -server 127.0.0.1:2181 ls / :: 客户端
```

---

## 常见坑

1. **`No snapshot found, but there are log entries`**：dataDir 残留了旧版本事务日志但无快照。清空 `D:/data/zookeeper` 后重启。
2. **`ruok is not exec`**：3.5.x 四字命令默认不在白名单，需在 `zoo.cfg` 配 `4lw.commands.whitelist=ruok,stat,...`。
3. **`operable program or batch file`**：仓库自带 `bin\zkCli.cmd` 有一行 `ZOO_LOG_FILE=` 漏写 `set`，无害。
4. **AdminServer 占 8080**：3.5 内置 Jetty AdminServer 默认监听 8080，如冲突在 `zoo.cfg` 改 `admin.serverPort` 或设
   `admin.enableServer=false`。
