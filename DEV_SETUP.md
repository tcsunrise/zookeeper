# ZooKeeper 3.3.6 本地开发环境搭建指南

本文记录从零开始把这份 ZooKeeper 3.3.6 源码在本地跑通的完整流程：修复失效的依赖地址 → Ant 编译 → IDE 导入 → 单机启动验证 → SpotBugs 静态检查。

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

#### ⚠️ 交互式客户端在 IDEA Run 控制台「像卡住」

连上后敲 `ls /` 回车没反应，不是卡住：ZooKeeper CLI 用 jline 做命令行编辑，jline 需要真实终端(TTY)，而 IDEA 普通 Run 控制台不是 TTY。

源码逻辑：`ZooKeeperMain.run()` 检测到 classpath 有 jline 就用它，**否则自动退回普通 `BufferedReader.readLine()`**（后者在 IDEA 控制台里能正常交互）。据此有三种解法：

**方案 A（推荐，体验最好）——用 IDEA 的 Terminal 跑，不用 Run 配置**

IDEA 底部 **Terminal** 标签是真实 TTY，jline 完整可用（历史、补全）：

```
java -cp "build\classes;build\lib\log4j-1.2.15.jar;build\lib\jline-0.9.94.jar;conf" org.apache.zookeeper.ZooKeeperMain -server 127.0.0.1:2181
```

**方案 B——仍用 Run 控制台，排除 jline**

`ZK Client → Modify options → Modify classpath`，把 `jline-0.9.94.jar` 加入 **Exclude**。再运行会打印 `JLine support is disabled`，`ls /` 回车即正常执行（无行编辑/历史）。

**方案 C——不进交互，命令写进参数**

`ZK Client → Program arguments` 带上命令，一次一条，跑完即退：

```
-server 127.0.0.1:2181 ls /
```

> 注：新版 IDEA 的 Java Application 配置支持勾选「Emulate terminal in output console」（对应 `.run.xml` 的 `RUN_AS_TERMINAL=true`，本仓库 `ZK Client.run.xml` 已内置），勾上后 Run 控制台也变 TTY、jline 可直接用。但**部分旧版本的 Java 配置没有该选项**（`Modify options` 里找不到），这种情况就用上面 A/B/C。

两点前提：

1. **模块名**：`.run/*.run.xml` 里写的是 `<module name="zookeeper" />`，需与你的 IDEA 模块名一致；不同则改这一行。
2. **log4j 日志**：把 `conf/` 目录在 `Project Structure → Modules` 里标记为 **Resources**，`log4j.properties` 才会进 classpath；否则能跑但只有 `No appenders` 警告、无 INFO 日志。（此为模块级设置，存于 `.idea/*.iml`，不随 `.run/` 提交。）

> 若想手动新建运行配置：`Run → Edit Configurations → + → Application`，Main class 填上述主类，Program arguments 填对应参数，Working directory 设为项目根（`$ProjectFileDir$`）。

---

## 6. 静态代码检查（SpotBugs）

`build.xml` 已集成 SpotBugs（FindBugs 的后继），一条命令完成 **编译 → 分析 → 出报告**。原有的 `findbugs` target 保留未动（需自备 FindBugs 安装，已基本不可用）。

### 6.1 首次运行会自动下载

- 首次执行时，`spotbugs-download` 从 Maven Central 下载 **SpotBugs 4.8.6**（约 16 MB）并解压到 **`~/.spotbugs/`**（即 `%USERPROFILE%\.spotbugs`），之后直接复用。
- 缓存放在 `build/` 之外，所以 **`ant clean` 不会删它**，无需重复下载。
- 为什么是 4.8.6：它是最后一个能跑在 **Java 8** 上的版本（4.9+ 要求 Java 11）。

### 6.2 运行

三种 shell 命令相同：

```bash
ant spotbugs
```

看到 `BUILD SUCCESSFUL` 和 `SpotBugs report: ...` 即完成，报告输出到 `build/spotbugs/`：

| 文件 | 说明 |
|------|------|
| `spotbugs-report.html` | 可视化报告（`fancy-hist.xsl` 样式，按类别/包/类分组，可点开看说明和源码行） |
| `spotbugs-report.xml` | 原始数据（含问题描述，便于脚本统计或导入 IDE 插件） |

> 发现问题不会让构建失败，只出报告；构建是否成功看 `BUILD SUCCESSFUL`。

### 6.3 常用参数

所有配置都是 Ant property，可用 `-D` 覆盖：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `spotbugs.report.level` | `low` | 报告级别：`low`（全部）/ `medium` / `high`（只看高优先级） |
| `spotbugs.home` | `~/.spotbugs/spotbugs-4.8.6` | 指向本地已有的 SpotBugs 安装，跳过下载 |
| `spotbugs.download.url` | Maven Central | 下载地址，网络不通时换镜像 |
| `spotbugs.exclude.file` | `src/java/test/config/findbugsExcludeFile.xml` | 排除规则（沿用项目原有的 FindBugs 排除文件） |
| `spotbugs.out.dir` | `build/spotbugs` | 报告输出目录 |

**Git Bash：**

```bash
# 只看高优先级
ant spotbugs -Dspotbugs.report.level=high

# 访问不了 Maven Central 时，改用阿里云镜像下载（与第 2 节同源）
ant spotbugs -Dspotbugs.download.url=https://maven.aliyun.com/repository/public/com/github/spotbugs/spotbugs/4.8.6/spotbugs-4.8.6.tgz
```

**CMD (Windows)：**

```bat
ant spotbugs -Dspotbugs.report.level=high

ant spotbugs -Dspotbugs.download.url=https://maven.aliyun.com/repository/public/com/github/spotbugs/spotbugs/4.8.6/spotbugs-4.8.6.tgz
```

**PowerShell：**（⚠️ `-D` 参数**必须加引号**）

```powershell
ant spotbugs "-Dspotbugs.report.level=high"

ant spotbugs "-Dspotbugs.download.url=https://maven.aliyun.com/repository/public/com/github/spotbugs/spotbugs/4.8.6/spotbugs-4.8.6.tgz"
```

> PowerShell 会把不带引号的 `-Dspotbugs.report.level=high` 在点号处拆成两个参数，Ant 把后半截当成 target 名，报 `Target "high" does not exist`（分析其实跑完了，但构建失败、参数也没生效）。

### 6.4 查看报告

**Git Bash：**

```bash
start build/spotbugs/spotbugs-report.html              # 用默认浏览器打开
grep -c '<BugInstance' build/spotbugs/spotbugs-report.xml   # 问题总数
```

**CMD (Windows)：**

```bat
start build\spotbugs\spotbugs-report.html
findstr /c:"<BugInstance" build\spotbugs\spotbugs-report.xml | find /c /v ""
```

**PowerShell：**

```powershell
Invoke-Item build\spotbugs\spotbugs-report.html
(Select-String -Path build\spotbugs\spotbugs-report.xml -Pattern '<BugInstance').Count
```

> 参考基线（`dev-3.3.6` 分支，`low` 级别）：共 **269** 个问题，其中高优先级 47、中 136、低 86。数量明显变化时说明代码改动引入或消除了问题。

### 6.5 只检查并发问题

```bash
ant spotbugs-mt
```

只报告 **多线程正确性（`MT_CORRECTNESS`）** 类问题：不一致的同步、volatile 字段的非原子自增、对并发容器加 `synchronized`、并发容器上的非原子「先查后改」等。报告单独输出到 **`build/spotbugs-mt/`**，不会覆盖 `build/spotbugs/` 下的完整报告。三种 shell 命令相同，打开报告的方式同 6.4，把路径换成 `build/spotbugs-mt/spotbugs-report.html` 即可。

- 过滤规则在 `src/java/test/config/findbugsIncludeConcurrencyFile.xml`，按 `<Bug category="..."/>` 或 `<Bug pattern="..."/>` 增删即可（语法同排除文件）。
- 不想新开输出目录时，也可以给 `ant spotbugs` 直接传任意 include 过滤器（PowerShell 记得给 `-D` 参数加引号）：

  ```bash
  ant spotbugs -Dspotbugs.include.file=src/java/test/config/findbugsIncludeConcurrencyFile.xml
  ```

- 不想重新分析时：在完整报告里点 **Browse By Categories → Multithreaded correctness**，看到的是同一批问题。

> 参考基线（`dev-3.3.6`）：共 **16** 个并发问题，其中高优先级 3 个，全是 `VO_VOLATILE_INCREMENT`（`FastLeaderElection:659`、`AuthFastLeaderElection:765/839`）。

### 6.6 实现要点（改 `build.xml` 时参考）

- 分析对象是 `build/classes`（依赖 `compile`，不需要打 jar）；`build/lib/*.jar` 作为辅助 classpath，`src/java/main` 和 `src/java/generated` 作为源码路径，报告能定位到源码行。
- **用 `<java>` 直接调 SpotBugs 命令行，而不是它自带的 `<spotbugs>` Ant task**。Ant task 会把输出拆成 `-xml:withMessages` + 单独的 `-outputFile`，这种组合下 withMessages 失效，XML 里缺 `BugPattern` 定义和 `instanceHash`，HTML 报告能看到包、**点类名却展不开**。命令行写成 `-xml:withMessages=文件` 就是完整的。
- **HTML 由 Ant `<xslt>` 从 XML 文件转换生成，不用命令行的 `-html:` 选项**。`-html:` 转换时拿不到 `FindBugsSummary`（包/类统计），报告「Browse by Packages」会显示 **Total number of bugs: 0**。
- `fancy-hist.xsl` 是 **XSLT 2.0**，JDK 自带的 Xalan 只支持 1.0，会报一堆「语法错误」，所以 `<xslt>` 用 SpotBugs 自带的 **Saxon-HE**（`<factory name="net.sf.saxon.TransformerFactoryImpl">`）。
- 自检方法：HTML 里 `var packageStats`、`var patterns` 两个数组都不应为空。
- 目前发现问题**不会让构建失败**，只出报告。

---

## 7. 清理与从头重来

分两部分：`ant clean` 能删的构建产物，和它**管不到**的本地文件。

### 7.1 `ant clean`（shell 通用）

```bash
ant clean
```

删除：`build/`（含编译产物 `build/classes`、依赖 `build/lib`、测试依赖 `build/test/lib`、SpotBugs 报告 `build/spotbugs`，以及数据目录 `build/zkdata`）、生成源码 `src/java/generated/`、`src/c/generated/`、`.revision/`。

> ⚠️ `build/zkdata` 在 `build/` 下，`ant clean` 会**连同 ZooKeeper 数据一起删除**。想保留数据就把 `dataDir` 配到 `build/` 之外。

### 7.2 清理本地附加文件（`ant clean` 不会动）

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

SpotBugs 缓存在用户目录（见 6.1），一般无需删除；想强制重新下载时再删：

```bash
rm -rf ~/.spotbugs                               # Git Bash
```

```bat
rmdir /s /q "%USERPROFILE%\.spotbugs"
```

```powershell
Remove-Item -Recurse -Force "$HOME\.spotbugs"
```

### 7.3 彻底从头（先停 server）

完整重来的顺序：**停 server（见 5.4）→ `ant clean` → 7.2 删本地文件**，之后即可回到第 3 节重新 `ant compile`。

---

## 8. 常见坑

| 现象 | 原因 | 解决 |
|------|------|------|
| `UnknownHostException: repo2.maven.org` | build 脚本里写死的老地址失效 | 见第 2 节改镜像 |
| IDE 里 `data.*` / `proto.*` 一片红 | jute 生成源码未生成或未标源码根 | 先 `ant compile`，再把 `src/java/generated` 标 Sources |
| 客户端报 `Path must start with / character` | Git Bash 把 `/hello` 转成了 Windows 路径 | `export MSYS_NO_PATHCONV=1` |
| `javac: command not found` | PATH 上是 JRE 垫片 | Ant 用 `JAVA_HOME` 编译，确保它指向 JDK 即可 |
| 复制 junit 报「系统找不到指定的文件」 | `ant compile` 不拉测试依赖，`build/test/lib/` 不存在 | 先 `ant ivy-retrieve-test`；或跳过 junit（只编辑主源码不需要它） |
| CMD 下编译刷屏「编码GBK的不可映射字符」，中文注释显示为乱码 | 源码是 UTF-8，中文 Windows 上 javac 默认按 GBK 读 | `build.xml` 的 `<javac>` 已统一加 `encoding="UTF-8"`；新增 `<javac>` 时记得带上 |
| PowerShell 下 `ant spotbugs -D...` 报 `Target "xxx" does not exist` | PowerShell 在 `-D` 参数的点号处拆分了参数 | 给 `-D` 参数加引号：`"-Dspotbugs.report.level=high"` |
| `ant spotbugs` 卡在 `[get]` 或报下载失败 | 访问不了 Maven Central | 用 `-Dspotbugs.download.url=` 指向阿里云镜像（见 6.3） |
| HTML 报告点类名展不开 | XML 不是完整的 withMessages 格式，缺 `BugPattern` / `instanceHash`（用 `<spotbugs>` Ant task 时会这样） | 用当前 `build.xml` 重新 `ant spotbugs`（已改为调命令行，见 6.6） |
| HTML 报告「Browse by Packages」显示 `Total number of bugs: 0` | 用了 SpotBugs 命令行的 `-html:` 选项，转换时缺包/类统计 | 用当前 `build.xml`（XML 生成后再用 `<xslt>` 转换，见 6.6） |
| 生成 HTML 时报一堆「语法错误」 | 用 JDK 自带 Xalan 跑 XSLT 2.0 的 `fancy-hist.xsl` | `<xslt>` 里保留 Saxon 的 `<factory>`，别删 |

---

## 9. 不纳入版本库的本地文件

以下为本地环境产物，已加入 `.gitignore`：

- `ide-lib/` —— IDE 依赖 jar 目录
- `conf/zoo.cfg` —— 本机单机配置
- `build/`、`src/java/generated/` —— 构建与生成产物（原本已忽略）
