# ZooKeeper 官网（zookeeper-website）本地启动指南

本文档说明如何在 **Windows** 本地构建、运行 `zookeeper-website`（Apache ZooKeeper 官网 + 文档站）。
技术栈：React Router 7 + Vite + Fumadocs(MDX)。

---

## 1. 前置要求

| 依赖      | 版本要求                         | 说明                                   |
|---------|------------------------------|--------------------------------------|
| Node.js | `^22.12.0 \|\| ^23 \|\| ^24` | `package.json` 的 `engines` 强制，低版本会报错 |
| npm     | 随 Node 附带即可                  |                                      |

确认版本：

```powershell
node -v   # 需 >= 22.12.0
npm -v
```

---

## 2. 首次准备

在 `zookeeper-website/` 目录下执行：

```powershell
cd D:\workspaces\opensource\zookeeper\zookeeper-website

npm ci                 # 安装依赖（约 900 个包）
npm run fumadocs-init  # 生成 MDX 索引（构建前置）
```

> **注意**：若 `npm ci` 报 `EPERM ... unlink lightningcss...`，是有正在运行的 dev/preview 进程锁着原生模块。
> 先停掉所有该项目的 node 进程再重试（见文末「常见问题」）。

---

## 3. 本地开发（推荐日常使用）

```powershell
npm run dev
```

- 访问 **http://localhost:5173/**（若 5173 被占用会自动顺延到 5174 等，看终端输出）。
- 修改 `_mdx/` 下文档或 `app/` 组件会热更新。
- 文档站入口：`http://localhost:5173/doc/r3.10.0/`

---

## 4. 完整生产构建 + 预览

```powershell
npm run build   # 构建 landing + 当前版本文档，合并产物、生成 sitemap
npm run start   # vite preview，访问 http://localhost:5173/
```

产物目录：`build/client/`

- 首页：`build/client/index.html`
- 文档：`build/client/doc/r3.10.0/`

### 只构建其中一部分

```powershell
npm run build:docs      # 仅文档
npm run build:landing   # 仅落地页
```

---

## 5. 文档正文与官网源码在哪

| 内容                  | 路径                                                                             |
|---------------------|--------------------------------------------------------------------------------|
| **文档 Markdown 源文件** | `app/pages/_docs/docs/_mdx/`（overview / admin-ops / developer / miscellaneous） |
| 侧边栏导航顺序             | 各目录下的 `meta.json`                                                              |
| 官网落地页               | `app/pages/_landing/`（home / events / credits / bylaws …）                      |
| 通用组件                | `app/components/`（导航栏、页脚、文档布局、搜索、TOC）                                          |
| 站点配置/工具             | `app/lib/`（版本号、文档路径、存档逻辑）                                                      |
| 当前文档版本号             | `app/lib/current-version.ts`（`CURRENT_VERSION = "3.10.0"`）                     |

---

## 6. 版本切换下拉为什么点旧版本会 404？

导航栏 **Documentation ▾** 里的 `3.9.5 / 3.8.6 Documentation` 指向 `/doc/r3.9.5/`、`/doc/r3.8.6/`。

- 本仓库的文档源码（`_mdx/`）**只有当前版本 3.10.0** 一份。
- 旧版本是**预先构建好的静态存档**，只存在于部署分支 `asf-site`，**不在源码仓库里**（见 `app/lib/released-docs-versions.ts`
  注释）。
- 本地构建只产出当前版本，所以点旧版本 **必然 404 —— 这是预期行为，不是 bug**。

**要在本地也能看旧版本**（需外网），把线上存档镜像到本地产物目录：

```bash
cd build/client/doc
wget -r -np -nH --cut-dirs=1 -P . https://zookeeper.apache.org/doc/r3.9.5/
wget -r -np -nH --cut-dirs=1 -P . https://zookeeper.apache.org/doc/r3.8.6/
```

日常开发忽略该 404 即可。

---

## 7. 常见问题（Windows）

**端口被旧进程占用 / dev 起在了 5174**
上一次的 dev/preview 进程没退干净。查找并清理：

```powershell
# 找出占用 5173 的进程并结束
Get-NetTCPConnection -LocalPort 5173 -State Listen |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

**`npm ci` 报 EPERM unlink lightningcss**
有 node 进程锁着 `node_modules`。先停掉本项目所有 node 进程：

```powershell
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
  Where-Object { $_.CommandLine -like '*zookeeper-website*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**首页 500，报 `Failed to load ./developers.json`**
`developers.json` 是从父 `pom.xml` 生成的，缺失会导致首页 500。执行：

```powershell
npm run extract-developers
```

生成后重启 dev 服务器（Vite 会缓存失败的解析，须重启才生效）。

---

## 8. 其它脚本

```powershell
npm run typecheck   # 类型检查
npm run lint        # ESLint + prettier 检查
npm run test:unit   # 单元测试（vitest）
npm run test:e2e    # 端到端测试（playwright）
npm run ci          # 完整 CI：lint + typecheck + 单测 + build + e2e
```

---

## 附：Windows 兼容性修复记录

本项目原先在 Windows 原生环境下 `npm run build` / `npm run extract-developers` 跑不通，已修复以下 4 处
（均为「POSIX 上无副作用、仅 Windows 生效」的兼容性改动）：

| 文件                              | 问题                                                               | 修复                                       |
|---------------------------------|------------------------------------------------------------------|------------------------------------------|
| `scripts/build-site.ts`         | `spawnSync("npm"/"npx")` 在 Windows 找不到 `.cmd` shim               | `runCommand` 加 `shell: true`             |
| `scripts/build-docs.ts`         | 同上                                                               | `runCommand` 加 `shell: true`             |
| `scripts/extract-developers.js` | 入口判断 `file://${process.argv[1]}` 路径格式不匹配，`main()` 不执行            | 改用 `pathToFileURL(process.argv[1]).href` |
| `react-router.config.ts`        | `glob` 返回反斜杠路径，`getSlugs` 切不开，预渲染出 `/overview\quick-start` 而 404 | slug 前 `entry.replaceAll("\\", "/")` 归一化 |
