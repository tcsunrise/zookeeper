# 用早期版本理解 ZooKeeper 核心

> 个人学习笔记：早期版本的 ZooKeeper 是否更好理解、代码更简单、更贴近核心？

## 结论

很大程度上如此。用早期版本（尤其 3.0/3.1，乃至更早的 Yahoo 内部版本）学习核心原理，通常比读现在的 3.9/master 更划算。但不要挑最古老的版本抠实现细节，**3.3.x / 3.4.x 是最佳平衡点**。

## 为什么早期版本更适合"理解核心"

### 1. 核心协议本身没变

ZooKeeper 最有价值的东西在最早期就已经定型：

- **ZAB 协议**：Leader 选举 + 原子广播
- **Watch 机制**
- **session 管理**
- **内存 DataTree + 事务日志 / 快照**

这些核心思想读老代码一样能学到，而且被无关代码淹没得更少。

### 2. 后来加入的大量代码是"生产化"而非"核心逻辑"

现在膨胀出来的部分，大多不是原理性的：

- 可插拔认证 / SASL / Kerberos / X509
- 动态重配置（reconfig，3.5 引入，非常大的一块）
- Netty 传输层（早期只有 NIO 一种）
- Metrics / Prometheus、JMX、Admin Server（内嵌 Jetty）
- Read-only 模式、local sessions、container / TTL nodes
- 大量健壮性补丁、边界处理、限流、安全修复

这些对读懂"ZooKeeper 是怎么工作的"帮助不大，却占了现在代码量的一大半。

### 3. 早期抽象层少

老版本里 `QuorumPeer`、`Leader`、`Follower`、`FastLeaderElection`、`RequestProcessor` 责任链这些主干类，逻辑更直，接口 / 策略分层更少，一条请求从客户端到落盘的路径更容易一眼跟下来。

## 需要注意的坑

- **别用最古老的选举算法**：早期有 `LeaderElection`（UDP）、`AuthFastLeaderElection` 等多种实现，现在只保留 `FastLeaderElection`。学选举请直接看 `FastLeaderElection`，别挑太老的。
- **早期版本有已知 bug 和竞态**：适合"读懂原理"，不适合当权威实现细节，更不能用于生产。
- **文档 / 注释更少**：需要配合论文一起读。

## 建议的学习路径

1. **先读论文**
   - Hunt 等，《ZooKeeper: Wait-free coordination for Internet-scale systems》(USENIX ATC 2010)
   - Junqueira 等，《Zab: High-performance broadcast for primary-backup systems》(DSN 2011)
   - 原理都在这两篇。

2. **代码取一个"够老但已成型"的版本**
   - **3.3.x / 3.4.x** 是很好的平衡点：ZAB、选举、Watch、责任链都齐了，但还没有 reconfig、Netty、Admin Server 这些负担。
   - 比 3.0 更完整，比 3.5+ 干净很多。

3. **主干阅读顺序**
   ```
   ZooKeeperServer / QuorumPeer
     → FastLeaderElection
       → Leader / Follower / Learner
         → RequestProcessor 责任链
           → DataTree + FileTxnSnapLog
   ```
