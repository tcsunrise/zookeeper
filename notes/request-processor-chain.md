# RequestProcessor 实现与处理链笔记

> 基于 ZooKeeper 3.3.6 源码（分支 `dev-3.3.6`），整理自 2026-09-30 的阅读讨论。
> 涉及文件：`server/RequestProcessor.java` 及其各实现类、`ZooKeeperServer.java`、`quorum/LeaderZooKeeperServer.java`、`quorum/FollowerZooKeeperServer.java`、`quorum/ObserverZooKeeperServer.java`

---

## 1. 全部实现类（共 10 个，均在 `src/java/main`，测试代码中无其他实现）

路径均相对于 `src/java/main/org/apache/zookeeper/`。

| # | 类 | 位置 | 独立线程 | 使用角色 | 职责 |
|---|---|---|---|---|---|
| 1 | `PrepRequestProcessor` | `server/PrepRequestProcessor.java:64` | ✅ | 单机、Leader | 预处理：校验 ACL / 版本，生成事务头（分配 zxid）与事务体 |
| 2 | `SyncRequestProcessor` | `server/SyncRequestProcessor.java:35` | ✅ | 单机、Leader、Follower、Observer | 写事务日志、组提交 fsync，触发 rollLog 与快照 |
| 3 | `FinalRequestProcessor` | `server/FinalRequestProcessor.java:68` | ❌ | 所有角色 | 应用到 DataTree，构造响应并回复客户端（链的最后一环） |
| 4 | `ProposalRequestProcessor` | `server/quorum/ProposalRequestProcessor.java:30` | ❌ | Leader | 把写请求作为提案广播给 Follower，同时交给本地 Sync 落盘 |
| 5 | `AckRequestProcessor` | `server/quorum/AckRequestProcessor.java:31` | ❌ | Leader | Leader 本地落盘后给自己记一票 ACK（`leader.processAck`） |
| 6 | `CommitProcessor` | `server/quorum/CommitProcessor.java:35` | ✅ | Leader、Follower、Observer | 写请求等到收到 COMMIT 才放行，读请求直接通过，保证会话内顺序 |
| 7 | `Leader.ToBeAppliedRequestProcessor` | `server/quorum/Leader.java:528`（静态内部类） | ❌ | Leader | 请求交给 Final 后，从 `toBeApplied` 队列中移除 |
| 8 | `FollowerRequestProcessor` | `server/quorum/FollowerRequestProcessor.java:34` | ✅ | Follower | 客户端请求入口：写请求转发给 Leader，所有请求交给 CommitProcessor |
| 9 | `ObserverRequestProcessor` | `server/quorum/ObserverRequestProcessor.java:34` | ✅ | Observer | 同 Follower 版本，写请求转发给 Leader |
| 10 | `SendAckRequestProcessor` | `server/quorum/SendAckRequestProcessor.java:30`（同时实现 `Flushable`） | ❌ | Follower、Observer | 本地落盘后给 Leader 发 ACK |

**独立线程**：标 ✅ 的类都是 `extends Thread`，内部有自己的队列，`processRequest()` 只负责把请求放进队列；标 ❌ 的类在上游线程中被同步调用。

---

## 2. 各角色的处理链

各角色的处理链都在对应类的 `setupRequestProcessors()` 中组装。

### 单机（`ZooKeeperServer.java:387`）

```
Prep → Sync → Final
```

### Leader（`LeaderZooKeeperServer.java:60`）

```
Prep → Proposal → Commit → ToBeApplied → Final
          │
          └→ Sync → Ack          （Proposal 内部 spawn，给自己投 ACK）
```

### Follower（`FollowerZooKeeperServer.java:72`）

```
FollowerRP → Commit → Final      （客户端请求链）
Sync → SendAck                   （Leader 提案日志链，由 logRequest() 喂入）
```

注释 "A SyncRequestProcessor is also spawned off to log proposals from the leader" 中的 **spawn off** 指的就是第二条链：另起一个独立的 `SyncRequestProcessor` 线程，它不在客户端请求链上。

### Observer（`ObserverZooKeeperServer.java:84`）

```
ObserverRP → Commit → Final
Sync → SendAck
```

---

## 3. 要点

- **先持久化，再生效和应答**（persist first, then apply and acknowledge）：`SyncRequestProcessor` 在 fsync 完成后才把请求交给下一个处理器。单机模式下下一个是 Final，负责生效和回复客户端；集群模式下是 Ack / SendAck，负责投票。
- **写请求在集群中的生效时机**：Follower / Observer 上，写请求要等 Leader 发来 COMMIT，经 `CommitProcessor` 放行后才会到达 Final 并应用到 DataTree。
- **Leader 自己也要落盘并投票**：`ProposalRequestProcessor` 在广播提案的同时交给本地的 `Sync → Ack`，Leader 自己的这一票也要等本地 fsync 完成后才计入。
