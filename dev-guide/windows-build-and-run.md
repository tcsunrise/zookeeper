# ZooKeeper 3.4.5 本地编译与启动（Windows）

> 本项目用 **Ant + Ivy** 构建，不是 Maven。
> 下文 `<项目根目录>` 指本仓库在你机器上的 clone 路径（即 `build.xml` 所在目录，不是本文档所在的 `dev-guide` 目录），请自行替换。
> **所有命令都在 `<项目根目录>` 下执行**；每个章节按自己的终端（CMD / PowerShell / Git Bash）选一套照做即可。

## 前提条件

| 项 | 要求 | 自检命令 |
|----|----|----|
| JDK | 1.8（已验证 1.8.0_503），且已设置环境变量 `JAVA_HOME` 指向 JDK 安装目录 | CMD：`echo %JAVA_HOME%` / PowerShell：`$env:JAVA_HOME` / Git Bash：`echo $JAVA_HOME` |
| java 命令 | `PATH` 中的 `java` 可用（`bin\*.cmd` 脚本直接调用它，不读 `JAVA_HOME`） | `java -version` |
| Ant | 1.10 系列（已验证 1.10.17），`ant` 命令在 `PATH` 中 | `ant -version` |
| Git for Windows | 使用 Git Bash 或第五章 `git clean` 时需要 | `git --version` |
| 网络 | 首次编译需访问 `https://maven.aliyun.com` 下载依赖（之后走本地缓存） | — |
| 端口 | `2181` 未被占用 | `netstat -ano \| findstr :2181` |
| 代码 | 已切到包含本文档的分支（`git checkout dev-3.4.5`） | `git branch --show-current` |

PowerShell 用 Windows 自带的 5.1 或 PowerShell 7 均可。

目录：

1. [编译](#一编译)
2. [用 bin 脚本启动 Server 和连接 zk](#二用-bin-脚本启动-server-和连接-zk)
3. [用 java 命令启动 Server 和连接 zk](#三用-java-命令启动-server-和连接-zk)
4. [IDEA 中配置并启动 Server 和连接 zk](#四idea-中配置并启动-server-和连接-zk)
5. [恢复为原始状态（清除编译和运行痕迹）](#五恢复为原始状态清除编译和运行痕迹)

## 先看：五个坑

1. **仓库地址失效**：`build.xml` / `ivysettings.xml` 里的 `repo2.maven.org`、`http://repo1...` 已不可用，编译时用 `-D` 参数覆盖为阿里云镜像（不改文件）。
2. **`.cmd` 脚本用的是 PATH 里的 `java`**：`bin\zkServer.cmd` / `zkCli.cmd` 直接执行 `java`，不看 `JAVA_HOME`。若 `java -version` 不是期望的 JDK，先临时把 JDK 放到 PATH 最前面：CMD `set "PATH=%JAVA_HOME%\bin;%PATH%"`，PowerShell `$env:Path = "$env:JAVA_HOME\bin;$env:Path"`。
3. **Git Bash 下 `.sh` 脚本 classpath 不对**：`bin/zkEnv.sh` 只在 `uname` 为 `CYGWIN*` 时才把 classpath 转成 Windows 格式，Git Bash（`MINGW*`）下会报找不到主类。解决：用一个 `uname` 函数"伪装"成 Cygwin（见第二章 Git Bash 部分，不改文件）。
4. **必须在项目根目录启动**：`zoo.cfg` 中 `dataDir=data` 是相对路径，相对于 JVM 的**当前工作目录**解析。在别的目录（如 `bin\` 下）启动会把数据写到别处，看起来像"数据丢了"。
5. **`JAVA_HOME` 含空格时 `.sh` 脚本会失败**：`bin/zkServer.sh` / `zkCli.sh` 里 `$JAVA` 没加引号，`JAVA_HOME=C:\Program Files\...` 会被拆开，报 `C:\Program: No such file or directory`（`zkServer.sh start` 仍显示 `STARTED`，但进程并没起来）。解决：Git Bash 中先执行 `export JAVA_HOME="$(cygpath -d "$JAVA_HOME")"` 转成 8.3 短路径（见第二章 Git Bash 部分，不改文件）。

## 哪些步骤需要重复做

| 步骤 | 频率 |
|----|----|
| 编译、生成 `zoo.cfg` | 首次；改了源码后重新编译 |
| Git Bash 的 `uname` 伪装 + `JAVA_HOME` 短路径（使用 `.sh` 脚本时） | 每开一个新终端窗口 |
| 启动 / 连接 / 停止 | 每次 |

---

## 一、编译

编译产物：`build\classes`、`build\lib\*`（依赖 jar）、`build\zookeeper-3.4.5.jar`，以及 jute 生成的代码 `src\java\generated`。看到 `BUILD SUCCESSFUL` 即成功。

编译完成后还要**生成配置文件 `conf\zoo.cfg` 和数据目录 `data`**（仓库只自带 `zoo_sample.cfg`，其 `dataDir=/tmp/zookeeper` 不适合 Windows）。

### CMD

```bat
cd /d <项目根目录>

set A=https://maven.aliyun.com/repository/public
call ant -Divy.url=%A%/org/apache/ivy/ivy -Dmvnrepo=%A% -Drepo.maven.org=%A%/ -Drepo.jboss.org=%A%/ -Drepo.sun.org=%A%/ jar

mkdir data
(
echo tickTime=2000
echo initLimit=10
echo syncLimit=5
echo dataDir=data
echo clientPort=2181
) > conf\zoo.cfg
```

### PowerShell

> PowerShell 会把 `-Divy.url=...` 在 `.` 处拆开，**`-D` 参数必须加双引号**。

```powershell
cd <项目根目录>

$A = 'https://maven.aliyun.com/repository/public'
ant "-Divy.url=$A/org/apache/ivy/ivy" "-Dmvnrepo=$A" "-Drepo.maven.org=$A/" "-Drepo.jboss.org=$A/" "-Drepo.sun.org=$A/" jar

New-Item -ItemType Directory -Force data | Out-Null
@(
  'tickTime=2000'
  'initLimit=10'
  'syncLimit=5'
  'dataDir=data'
  'clientPort=2181'
) | Set-Content conf\zoo.cfg
```

### Git Bash

```bash
cd <项目根目录>   # Git Bash 下盘符写成 /d/...，分隔符用 /

A=https://maven.aliyun.com/repository/public
ant -Divy.url=$A/org/apache/ivy/ivy -Dmvnrepo=$A -Drepo.maven.org=$A/ -Drepo.jboss.org=$A/ -Drepo.sun.org=$A/ jar

mkdir -p data
cat > conf/zoo.cfg <<'EOF'
tickTime=2000
initLimit=10
syncLimit=5
dataDir=data
clientPort=2181
EOF
```

---

## 二、用 bin 脚本启动 Server 和连接 zk

| 终端 | Server 脚本 | Client 脚本 |
|----|----|----|
| CMD / PowerShell | `bin\zkServer.cmd`（只支持前台运行） | `bin\zkCli.cmd` |
| Git Bash | `bin/zkServer.sh`（支持 `start` / `start-foreground` / `status` / `stop`） | `bin/zkCli.sh` |

Server 启动成功的标志：日志里出现 `binding to port 0.0.0.0/0.0.0.0:2181`。
连接成功的标志：执行 `ls /` 返回 `[zookeeper]`。

### CMD

**启动 Server**（前台运行，占用当前窗口）：

```bat
cd /d <项目根目录>
bin\zkServer.cmd
```

**连接 zk**（另开一个 CMD 窗口）：

```bat
cd /d <项目根目录>
bin\zkCli.cmd -server localhost:2181
```

进入交互后输入 `ls /`，`quit` 退出。也可一次性执行：`bin\zkCli.cmd -server localhost:2181 ls /`。

**停止 Server**：在 Server 窗口按 `Ctrl+C`；或在其他窗口结束监听 2181 的进程：

```bat
for /f "tokens=5" %p in ('netstat -ano ^| findstr ":2181 " ^| findstr LISTENING') do taskkill /F /PID %p
```

> `%p` 是交互式写法；若写进 `.bat` 文件，需改成 `%%p`。

### PowerShell

**启动 Server**（前台运行）：

```powershell
cd <项目根目录>
.\bin\zkServer.cmd
```

**连接 zk**（另开一个 PowerShell 窗口）：

```powershell
cd <项目根目录>
.\bin\zkCli.cmd -server localhost:2181
```

**停止 Server**：在 Server 窗口按 `Ctrl+C`；或在其他窗口结束监听 2181 的进程（只按端口匹配，不会误杀本机其他 ZK 实例）：

```powershell
Get-NetTCPConnection -LocalPort 2181 -State Listen |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

### Git Bash

先让脚本以为自己运行在 Cygwin 下（见坑 3），并把 `JAVA_HOME` 转成不含空格的短路径（见坑 5）。两者都只影响当前终端窗口：

```bash
cd <项目根目录>
uname() { echo CYGWIN_NT; }; export -f uname
export JAVA_HOME="$(cygpath -d "$JAVA_HOME")"
```

> 执行后 `echo $JAVA_HOME` 应类似 `C:\PROGRA~1\Java\JDK18~1.0_5`（不含空格）。

**启动 Server**（后台运行，日志写到 `<项目根目录>/zookeeper.out`，PID 写到 `data/zookeeper_server.pid`）：

```bash
bash bin/zkServer.sh start
bash bin/zkServer.sh status        # 输出 Mode: standalone 即正常
```

> 想前台运行看日志：`bash bin/zkServer.sh start-foreground`，`Ctrl+C` 停止。

**连接 zk**：

```bash
MSYS_NO_PATHCONV=1 bash bin/zkCli.sh -server localhost:2181 ls /
```

> `MSYS_NO_PATHCONV=1` 防止 Git Bash 把参数 `/` 改写成 Windows 路径（否则报 `Path must start with / character`）。去掉末尾 `ls /` 即进入交互模式。

**停止 Server**：

```bash
bash bin/zkServer.sh stop
```

---

## 三、用 java 命令启动 Server 和连接 zk

不依赖 bin 脚本，直接用 `JAVA_HOME` 下的 java，也不需要 Git Bash 的 `uname` 伪装。

### CMD

**启动 Server**（前台运行）：

```bat
cd /d <项目根目录>
"%JAVA_HOME%\bin\java" -Dzookeeper.log.dir=. -Dzookeeper.root.logger=INFO,CONSOLE ^
  -cp "build\classes;build\lib\*;conf" ^
  org.apache.zookeeper.server.quorum.QuorumPeerMain conf\zoo.cfg
```

**连接 zk**（另开一个窗口）：

```bat
cd /d <项目根目录>
"%JAVA_HOME%\bin\java" -cp "build\classes;build\lib\*;conf" org.apache.zookeeper.ZooKeeperMain -server localhost:2181
```

**停止**：同第二章 CMD（`Ctrl+C` 或 `netstat` + `taskkill`）。

### PowerShell

> `-D` 参数同样要加引号。

**启动 Server**（前台运行）：

```powershell
cd <项目根目录>
& "$env:JAVA_HOME\bin\java" "-Dzookeeper.log.dir=." "-Dzookeeper.root.logger=INFO,CONSOLE" `
  -cp "build\classes;build\lib\*;conf" `
  org.apache.zookeeper.server.quorum.QuorumPeerMain conf\zoo.cfg
```

**连接 zk**（另开一个窗口）：

```powershell
cd <项目根目录>
& "$env:JAVA_HOME\bin\java" -cp "build\classes;build\lib\*;conf" org.apache.zookeeper.ZooKeeperMain -server localhost:2181
```

**停止**：同第二章 PowerShell（`Ctrl+C` 或 `Stop-Process`）。

### Git Bash

> Windows 版 java 的 classpath 分隔符是 `;`，不是 `:`。

**启动 Server**（前台运行）：

```bash
cd <项目根目录>
"$JAVA_HOME/bin/java" -Dzookeeper.log.dir=. -Dzookeeper.root.logger=INFO,CONSOLE \
  -cp "build/classes;build/lib/*;conf" \
  org.apache.zookeeper.server.quorum.QuorumPeerMain conf/zoo.cfg
```

**连接 zk**（另开一个窗口）：

```bash
cd <项目根目录>
MSYS_NO_PATHCONV=1 "$JAVA_HOME/bin/java" -cp "build/classes;build/lib/*;conf" \
  org.apache.zookeeper.ZooKeeperMain -server localhost:2181 ls /
```

> 去掉末尾 `ls /` 即进入交互模式。

**停止**：Server 窗口 `Ctrl+C`；或在其他窗口：

```bash
netstat -ano | grep ':2181 ' | grep LISTENING | awk '{print $5}' | sort -u | xargs -r -I{} taskkill //F //PID {}
```

### 参数说明

| 参数 | 作用 |
|----|----|
| `-cp "build/classes;build/lib/*;conf"` | 编译产物 + 依赖 jar + `conf`（读取 `log4j.properties`） |
| `-Dzookeeper.log.dir=.` | 日志目录（`ROLLINGFILE` 输出时使用） |
| `-Dzookeeper.root.logger=INFO,CONSOLE` | 日志级别与输出到控制台 |
| `org.apache.zookeeper.server.quorum.QuorumPeerMain` | Server 入口类（单机模式也用它） |
| `conf/zoo.cfg` | 配置文件路径 |
| `org.apache.zookeeper.ZooKeeperMain` | 客户端（zkCli）入口类 |

---

## 四、IDEA 中配置并启动 Server 和连接 zk

**前提**：先按第一章执行一次 Ant 编译并生成 `conf/zoo.cfg`（编译会生成 jute 代码到 `src/java/generated`，并下载依赖到 `build/lib`）。

### 1. 打开项目并指定 JDK

**File → Open** 选 `<项目根目录>`（按普通目录打开，不要导入为 Ant/Eclipse 项目）。

**File → Project Structure → Project**：

- SDK：JDK 1.8（没有则 Add SDK，指向本机 JDK 1.8 安装目录）
- Language level：8

### 2. 配置模块源码目录

**Project Structure → Modules → <模块名，默认同项目目录名> → Sources**：

- `src/java/main` → 标记为 **Sources**
- `src/java/generated` → 标记为 **Sources**（jute 生成的代码，不加会报 `org.apache.zookeeper.proto` 等类找不到）
- `src/java/test` 暂不加（依赖 junit 等测试 jar，启动 Server 用不到）

### 3. 配置依赖

同一窗口 **Dependencies** → `+` → **JARs or Directories…**：

- 选 `build/lib` 目录，弹框选 **Jar Directory**
- 再 `+` 选 `conf` 目录，选 **Classes**（让 classpath 能读到 `log4j.properties`）

### 4. 新建 Server 运行配置

**Run → Edit Configurations → `+` → Application**：

| 项 | 值 |
|----|----|
| Name | ZooKeeper Server |
| Module / classpath | 上面的模块名 |
| Main class | `org.apache.zookeeper.server.quorum.QuorumPeerMain` |
| Program arguments | `conf/zoo.cfg` |
| VM options | `-Dzookeeper.root.logger=INFO,CONSOLE` |
| Working directory | `$PROJECT_DIR$`（必须是项目根目录，否则 `dataDir=data` 会写到别处） |

### 5. 新建 Client 运行配置

再 `+` → **Application**：

| 项 | 值 |
|----|----|
| Name | ZooKeeper Client |
| Module / classpath | 上面的模块名 |
| Main class | `org.apache.zookeeper.ZooKeeperMain` |
| Program arguments | `-server localhost:2181` |
| VM options | `-Dzookeeper.root.logger=INFO,CONSOLE` |
| Working directory | `$PROJECT_DIR$` |

### 6. 启动与验证

1. 先 Run / Debug **ZooKeeper Server**，控制台出现 `binding to port 0.0.0.0/0.0.0.0:2181` 即成功。
2. 再 Run **ZooKeeper Client**，在它的控制台里输入 `ls /`，返回 `[zookeeper]` 即正常；`quit` 退出。
3. 调试断点建议：`QuorumPeerMain.initializeAndRun`、`ZooKeeperServerMain.runFromConfig`。
4. 用 IDEA 的停止按钮结束进程。

---

## 五、恢复为原始状态（清除编译和运行痕迹）

### 1. 先停掉所有 ZK 进程

按第二章对应终端的"停止 Server"操作；IDEA 里点停止按钮。并关闭 IDEA 中的该项目（否则 `.idea` 会被重新写入）。

### 2. 会产生哪些文件

| 文件 / 目录 | 来源 | `ant clean` 是否删除 |
|----|----|----|
| `build/` | 编译产物、依赖 jar | 是 |
| `src/java/generated/`、`src/c/generated/` | jute 生成的代码 | 是 |
| `.revision/` | 编译时记录版本信息 | 是 |
| `src/java/lib/ivy-2.2.0.jar` | 编译时下载的 Ivy | 否 |
| `conf/zoo.cfg`、`data/` | 第一章手动生成 / Server 运行写入 | 否 |
| `zookeeper.out` | Git Bash `zkServer.sh start` 的日志 | 否 |
| `.idea/`、`*.iml` | IDEA 打开项目 | 否 |

### 3. 一条命令清除（CMD / PowerShell / Git Bash 通用）

利用 git 删除所有未被版本库跟踪的文件（含被 `.gitignore` 忽略的）。`-e dev-guide` 用于保留本文档目录（本文档若尚未提交到 git，也属于"未跟踪文件"，不排除就会被一起删掉）：

```bash
git clean -ndx -e dev-guide     # 先预览将删除哪些文件，确认无误再执行下一行
git clean -fdx -e dev-guide
```

> ⚠️ 这会删除**所有**未提交的新文件（不只是编译痕迹），务必先看预览结果；自己新建且想保留的文件，再加 `-e <文件名>` 排除。
> 若还改过已跟踪的文件，用 `git status` 查看，再 `git checkout -- <文件>` 还原。

### 4. 或者手动清除（不想用 git clean 时）

先执行 `ant clean`，再删除 `ant clean` 不管的部分：

**CMD**：

```bat
call ant clean
del /q src\java\lib\ivy-*.jar conf\zoo.cfg zookeeper.out *.iml
rmdir /s /q data .idea
```

**PowerShell**：

```powershell
ant clean
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue src\java\lib\ivy-*.jar, conf\zoo.cfg, zookeeper.out, *.iml, data, .idea
```

**Git Bash**：

```bash
ant clean
rm -rf src/java/lib/ivy-*.jar conf/zoo.cfg zookeeper.out *.iml data .idea
```

### 5. 项目外的缓存（可选）

Ivy 下载的依赖缓存在用户目录 `~/.ant/cache`（Windows 为 `%USERPROFILE%\.ant\cache`），不在项目内，删除项目文件不受影响。它可能被其他 Ant/Ivy 项目共用，**一般不用删**；确需清理时手动删除该目录，下次编译会重新下载。

---

## 附：常见问题

| 现象 | 原因 / 处理 |
|----|----|
| `git clone` 报 `Filename too long` / `checkout failed` | 仓库里有很深的路径（`src/contrib/zooinspector/...`），clone 目录本身太深会超过 Windows 260 字符限制。换个短路径（如 `D:\zk`）重新 clone |
| `'java' 不是内部或外部命令` | `bin\*.cmd` 用的是 PATH 里的 java，见坑 2 把 `%JAVA_HOME%\bin` 加到 PATH |
| Git Bash 下 `zkServer.sh` 报找不到或无法加载主类 | 没做 `uname` 伪装（坑 3） |
| Git Bash 下 `zkServer.sh start` 显示 `STARTED`，但 `status` 报 `Error contacting service`；`zookeeper.out` 里是 `C:\Program: No such file or directory` | `JAVA_HOME` 含空格（坑 5），执行 `export JAVA_HOME="$(cygpath -d "$JAVA_HOME")"` 后重试 |
| `Address already in use: bind`（2181） | 已有 ZK 在运行，先停止；或改 `clientPort` |
| Ivy 下载依赖失败 / 超时 | 确认编译命令带了阿里云镜像的 `-D` 参数；必要时删掉 `~/.ant/cache` 中下载失败的目录重试 |
| IDEA 报 `org.apache.zookeeper.proto` 包不存在 | 没把 `src/java/generated` 标记为 Sources，或还没执行 Ant 编译 |

> 查看端口占用：CMD/Git Bash 用 `netstat -ano | findstr :2181`，PowerShell 用 `Get-NetTCPConnection -LocalPort 2181`。
