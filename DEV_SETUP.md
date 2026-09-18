# ZooKeeper 3.3.6 本地开发环境搭建指南

本文记录从零开始把这份 ZooKeeper 3.3.6 源码在本地跑通的完整流程：修复失效的依赖地址 → Ant 编译 → IDE 导入 → 单机启动验证。

> 环境背景：Windows 11 + JDK 8 + Apache Ant 1.10。这是一份 **Ant + Ivy** 老式工程，没有 Maven/Gradle 配置。
>
> 文中每条命令均提供 **Git Bash / CMD / PowerShell** 三个版本，且已在本机从零（`ant clean` 起）逐一实测通过。

---

## 1. 前置条件

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | 8（1.8） | 源码合规级别是 1.5，用 JDK 8 编译即可，别用太新的 JDK |
| Apache Ant | 1.9 / 1.10 | 构建工具 |
| 网络 | 可访问 Maven 镜像 | 用于 Ivy 拉取依赖 |

确认 `JAVA_HOME` 指向 JDK（含 `javac`），例如 `C:\Program Files\Java\jdk1.8.0_503`。

---

## 2. 修复失效的仓库地址（关键步骤）

原始 `build.xml` / `ivysettings.xml` 里写死的仓库地址早已失效，**即使联网也拉不到依赖**，必须先改。

### 2.1 `build.xml` —— 下载 Ivy 本身的地址

```xml
<!-- 原（repo2.maven.org 域名已不存在） -->
<property name="ivy.url"
          value="http://repo2.maven.org/maven2/org/apache/ivy/ivy" />

<!-- 改为阿里云镜像 -->
<property name="ivy.url"
          value="https://maven.aliyun.com/repository/public/org/apache/ivy/ivy" />
```

### 2.2 `ivysettings.xml` —— 解析依赖的三个仓库

```xml
<!-- 原：repo1 强制 HTTPS 会拒绝 http，jboss / download.java.net 已下线 -->
<!-- 全部改为阿里云 public 镜像（聚合了中央仓） -->
<property name="repo.maven.org"
    value="https://maven.aliyun.com/repository/public/" override="false"/>
<property name="repo.jboss.org"
    value="https://maven.aliyun.com/repository/public/" override="false"/>
<property name="repo.sun.org"
    value="https://maven.aliyun.com/repository/public/" override="false"/>
```

> 如果你在海外或走公司代理，也可把上面地址换成 `https://repo1.maven.org/maven2/`（注意必须是 https）。

---

## 3. 编译

**Git Bash：**

```bash
# 若之前失败残留了半截 ivy jar，先清掉
rm -f src/java/lib/ivy-*.jar
ant compile
```

**CMD (Windows)：**

```bat
del /q src\java\lib\ivy-*.jar 2>nul
ant compile
```

**PowerShell：**

```powershell
Remove-Item -Force src\java\lib\ivy-*.jar -ErrorAction SilentlyContinue
ant compile
```

`ant compile` 会自动完成三件事：

1. **Ivy 拉运行期依赖** → 下载到 `build/lib/`（3.3.6 运行期只需 log4j 1.2.15、jline 0.9.94）；
2. **jute 生成源码** → 由 `src/zookeeper.jute` 生成 46 个记录类到 `src/java/generated/`；
3. **编译** → 主源码编译到 `build/classes/`。

看到 `BUILD SUCCESSFUL` 即成功（过程中的 `rawtypes` / `-source 1.5` 是警告，不是错误）。

> ⚠️ **测试依赖不在 `ant compile` 范围内**。junit / checkstyle 等属于 `test` 配置，需单独执行 `ant ivy-retrieve-test`（或 `ant test`）拉取，且落在 **`build/test/lib/`**（不是 `build/lib/`）。本工程用的是 **junit 4.8.1**（自带 hamcrest，无需额外 jar）。IDE 只想编辑主源码时不需要它。

常用目标：`ant compile` / `ant jar` / `ant test`。

---

## 4. IDE 导入（IntelliJ IDEA）

这份工程不能像 Maven/Gradle 那样自动导入，需要手动配一次。

### 4.1 准备依赖 jar 目录

不要直接让 IDE 指向 `build/lib`（里面可能混着其它版本残留的 jar，`zookeeper-x.x.x.jar` 会盖住你的源码）。单独准备一个干净目录，放 jar：

| jar | 来自 | 何时需要 | 前置命令 |
|-----|------|----------|----------|
| `log4j-1.2.15.jar` | `build/lib/` | 编译主源码必需 | `ant compile` |
| `jline-0.9.94.jar` | `build/lib/` | 编译主源码必需 | `ant compile` |
| `junit-4.8.1.jar` | `build/test/lib/` | **仅编辑测试源码时才需要** | `ant ivy-retrieve-test` |

> 🚩 **最常见的坑**：直接 `copy build\test\lib\junit-4.8.1.jar` 会报「系统找不到指定的文件」。
> 因为 `ant compile` **不拉测试依赖**，`build/test/lib/` 尚不存在。**复制 junit 前必须先执行：**
>
> ```
> ant ivy-retrieve-test
> ```
>
> 如果你只想编辑 server/client 主源码，**跳过 junit 那一行即可**，只要 log4j + jline 两个 jar。

**Git Bash：**

```bash
mkdir -p ide-lib
cp build/lib/log4j-1.2.15.jar   ide-lib/
cp build/lib/jline-0.9.94.jar   ide-lib/
# 下面这行需先 ant ivy-retrieve-test，且仅编辑测试源码时才需要
cp build/test/lib/junit-4.8.1.jar ide-lib/
```

**CMD (Windows)：**

```bat
if not exist ide-lib mkdir ide-lib
copy /y build\lib\log4j-1.2.15.jar      ide-lib\
copy /y build\lib\jline-0.9.94.jar      ide-lib\
rem 下面这行需先 ant ivy-retrieve-test，且仅编辑测试源码时才需要
copy /y build\test\lib\junit-4.8.1.jar  ide-lib\
```

**PowerShell：**

```powershell
New-Item -ItemType Directory -Force ide-lib | Out-Null
Copy-Item build\lib\log4j-1.2.15.jar,build\lib\jline-0.9.94.jar ide-lib\
# 下面这行需先 ant ivy-retrieve-test，且仅编辑测试源码时才需要
Copy-Item build\test\lib\junit-4.8.1.jar ide-lib\
```

### 4.2 配置项目

`File → Open` 选本目录，然后 `File → Project Structure`：

1. **SDK / 语言级别**：JDK 8，语言级别 8。
2. **Modules → Sources**，标记源码根（右键 → Mark as）：
   - `src/java/main` → **Sources**
   - `src/java/generated` → **Sources** ← 不标这个，`org.apache.zookeeper.data.*` / `proto.*` 生成类全红
   - `src/java/test` → **Tests**
   - `src/java/systest` → **Tests**（可选）
3. **Libraries → +**：加目录 `ide-lib`，并在 **Modules → Dependencies** 里勾选它（Scope = Compile）。

> Eclipse 同理需手动配 Build Path，`ant eclipse` 目标依赖联网下载插件，不一定可用。

---

## 5. 单机启动验证

### 5.1 配置文件

从样例复制一份（`dataDir` 用绝对路径，正斜杠）：

**Git Bash：**

```bash
mkdir -p build/zkdata
cat > conf/zoo.cfg <<'EOF'
tickTime=2000
initLimit=10
syncLimit=5
dataDir=D:/workspaces/opensource/zookeeper/build/zkdata
clientPort=2181
EOF
```

**CMD (Windows)：**

```bat
if not exist build\zkdata mkdir build\zkdata
(
  echo tickTime=2000
  echo initLimit=10
  echo syncLimit=5
  echo dataDir=D:/workspaces/opensource/zookeeper/build/zkdata
  echo clientPort=2181
) > conf\zoo.cfg
```

**PowerShell：**

```powershell
New-Item -ItemType Directory -Force build\zkdata | Out-Null
@"
tickTime=2000
initLimit=10
syncLimit=5
dataDir=D:/workspaces/opensource/zookeeper/build/zkdata
clientPort=2181
"@ | Set-Content -Encoding ascii conf\zoo.cfg
```

### 5.2 启动 Server

**Git Bash：**

```bash
java -cp "build/classes;build/lib/log4j-1.2.15.jar;conf" \
     org.apache.zookeeper.server.quorum.QuorumPeerMain conf/zoo.cfg
```

**CMD (Windows)：**（`^` 是换行续行符；也可写成一行）

```bat
java -cp "build\classes;build\lib\log4j-1.2.15.jar;conf" ^
     org.apache.zookeeper.server.quorum.QuorumPeerMain conf\zoo.cfg
```

**PowerShell：**（`` ` `` 是续行符）

```powershell
java -cp "build\classes;build\lib\log4j-1.2.15.jar;conf" `
     org.apache.zookeeper.server.quorum.QuorumPeerMain conf\zoo.cfg
```

单机配置下会自动以 **standalone 模式**运行，监听 2181 端口。

探活（四字命令）：

```bash
# Git Bash
echo ruok | (exec 3<>/dev/tcp/127.0.0.1/2181; cat >&3; cat <&3)   # 返回 imok
```

```powershell
# CMD 无 /dev/tcp，用 PowerShell 探活
$c=New-Object Net.Sockets.TcpClient("127.0.0.1",2181);$s=$c.GetStream();$b=[Text.Encoding]::ASCII.GetBytes("ruok");$s.Write($b,0,4);$r=New-Object IO.StreamReader($s);$r.ReadToEnd();$c.Close()
```

### 5.3 客户端读写

**Git Bash：**

```bash
export MSYS_NO_PATHCONV=1   # Git Bash 必须：否则 /path 参数会被转成 Windows 路径

CP="build/classes;build/lib/log4j-1.2.15.jar;build/lib/jline-0.9.94.jar;conf"
java -cp "$CP" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 create /hello world
java -cp "$CP" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 get /hello   # 返回 world
java -cp "$CP" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 ls /          # [hello, zookeeper]
```

**CMD (Windows)：**（cmd 不做路径转换，无需 MSYS_NO_PATHCONV）

```bat
set CP=build\classes;build\lib\log4j-1.2.15.jar;build\lib\jline-0.9.94.jar;conf
java -cp "%CP%" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 create /hello world
java -cp "%CP%" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 get /hello
java -cp "%CP%" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 ls /
```

**PowerShell：**

```powershell
$CP="build\classes;build\lib\log4j-1.2.15.jar;build\lib\jline-0.9.94.jar;conf"
java -cp "$CP" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 create /hello world
java -cp "$CP" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 get /hello
java -cp "$CP" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181 ls /
```

> 客户端会把 log4j 的 INFO 日志一起打到控制台（这是 3.3.6 默认行为，属正常）。命令是否成功看关键输出：`create` 返回 `Created /hello`，`get` 返回节点值，`ls /` 返回 `[hello, zookeeper]`。

### 5.4 停止 Server

**Git Bash：**（`taskkill` 的斜杠要写双斜杠）

```bash
netstat -ano | grep :2181
taskkill //PID <PID> //F
```

**CMD (Windows)：**

```bat
netstat -ano | findstr :2181
taskkill /PID <PID> /F
```

**PowerShell：**（一步到位）

```powershell
$p = (Get-NetTCPConnection -LocalPort 2181 -State Listen).OwningProcess | Select-Object -First 1
Stop-Process -Id $p -Force
```

### 5.5 从 IDEA 启动（无需命令行）

项目根的 **`.run/`** 目录已内置两个可共享的运行配置，IDEA 打开项目会自动加载，右上角运行下拉框即可看到：

- **`ZK Standalone`** —— 启动单机 server（`QuorumPeerMain conf/zoo.cfg`）
- **`ZK Client`** —— 启动客户端（`ZooKeeperMain -server 127.0.0.1:2181`，不带命令进交互模式）

直接点 ▶ 启动，点红色 ■ 停止，无需 `taskkill`。

两点前提：

1. **模块名**：`.run/*.run.xml` 里写的是 `<module name="zookeeper" />`，需与你的 IDEA 模块名一致；不同则改这一行。
2. **log4j 日志**：把 `conf/` 目录在 `Project Structure → Modules` 里标记为 **Resources**，`log4j.properties` 才会进 classpath；否则能跑但只有 `No appenders` 警告、无 INFO 日志。（此为模块级设置，存于 `.idea/*.iml`，不随 `.run/` 提交。）

> 若想手动新建运行配置：`Run → Edit Configurations → + → Application`，Main class 填上述主类，Program arguments 填对应参数，Working directory 设为项目根（`$ProjectFileDir$`）。

---

## 6. 清理与从头重来

分两部分：`ant clean` 能删的构建产物，和它**管不到**的本地文件。

### 6.1 `ant clean`（shell 通用）

```bash
ant clean
```

删除：`build/`（含编译产物 `build/classes`、依赖 `build/lib`、测试依赖 `build/test/lib`，以及数据目录 `build/zkdata`）、生成源码 `src/java/generated/`、`src/c/generated/`、`.revision/`。

> ⚠️ `build/zkdata` 在 `build/` 下，`ant clean` 会**连同 ZooKeeper 数据一起删除**。想保留数据就把 `dataDir` 配到 `build/` 之外。

### 6.2 清理本地附加文件（`ant clean` 不会动）

`ide-lib/` 和 `conf/zoo.cfg` 是本文额外创建的，需手动删。

**Git Bash：**

```bash
rm -rf ide-lib conf/zoo.cfg
rm -f src/java/lib/ivy-*.jar   # 如需重新拉 ivy
```

**CMD (Windows)：**

```bat
rmdir /s /q ide-lib
del /q conf\zoo.cfg
del /q src\java\lib\ivy-*.jar 2>nul
```

**PowerShell：**

```powershell
Remove-Item -Recurse -Force ide-lib,conf\zoo.cfg -ErrorAction SilentlyContinue
Remove-Item -Force src\java\lib\ivy-*.jar -ErrorAction SilentlyContinue
```

### 6.3 彻底从头（先停 server）

完整重来的顺序：**停 server（见 5.4）→ `ant clean` → 6.2 删本地文件**，之后即可回到第 3 节重新 `ant compile`。

---

## 7. 常见坑

| 现象 | 原因 | 解决 |
|------|------|------|
| `UnknownHostException: repo2.maven.org` | build 脚本里写死的老地址失效 | 见第 2 节改镜像 |
| IDE 里 `data.*` / `proto.*` 一片红 | jute 生成源码未生成或未标源码根 | 先 `ant compile`，再把 `src/java/generated` 标 Sources |
| 客户端报 `Path must start with / character` | Git Bash 把 `/hello` 转成了 Windows 路径 | `export MSYS_NO_PATHCONV=1` |
| `javac: command not found` | PATH 上是 JRE 垫片 | Ant 用 `JAVA_HOME` 编译，确保它指向 JDK 即可 |
| 复制 junit 报「系统找不到指定的文件」 | `ant compile` 不拉测试依赖，`build/test/lib/` 不存在 | 先 `ant ivy-retrieve-test`；或跳过 junit（只编辑主源码不需要它） |

---

## 8. 不纳入版本库的本地文件

以下为本地环境产物，已加入 `.gitignore`：

- `ide-lib/` —— IDE 依赖 jar 目录
- `conf/zoo.cfg` —— 本机单机配置
- `build/`、`src/java/generated/` —— 构建与生成产物（原本已忽略）
