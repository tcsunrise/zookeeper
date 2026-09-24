# NIOServerCnxn 发送路径问答笔记

> 基于 ZooKeeper 3.3.6 源码（分支 `dev-3.3.6`），整理自 2026-09-24 的阅读讨论。
> 涉及文件：`src/java/main/org/apache/zookeeper/server/NIOServerCnxn.java`、`ZooKeeperServer.java`

---

## 1. `sendBuffer()` 中「先直接写」的快速路径（NIOServerCnxn.java:441-443）

```java
// We check if write interest here because if it is NOT set,
// nothing is queued, so we can try to send the buffer right
// away without waking up the selector
if ((sk.interestOps() & SelectionKey.OP_WRITE) == 0) {
    try { sock.write(bb); } catch (IOException e) { /* best effort */ }
}
if (bb.remaining() == 0) { packetSent(); return; }
// 否则：synchronized(factory) { wakeup; 入队; 打开 OP_WRITE }
```

**含义**：没有注册写兴趣，说明发送队列为空，可以直接写 socket，不用唤醒 selector。

### 前提：OP_WRITE 打开 ⇔ `outgoingBuffers` 非空

- **打开**：只在 `sendBuffer()` 的 `synchronized(factory)` 块里，先入队、再打开 OP_WRITE（第 464-467 行）。
- **关闭**：只在 `doIO()` 写分支的 `synchronized(factory)` 块里，确认队列为空后才关闭（第 716-734 行）。

### 为什么队列为空就可以直接写

1. **不会打乱顺序**：之前的数据都已进入内核发送缓冲区，新数据直接写在后面，顺序不变。队列非空时如果直接写，新包会插到已排队的包前面，破坏 TCP 字节流。
2. **开销小**：异步路径要做 `selector.wakeup()`（一次系统调用）、和 selector 线程竞争 `factory` 锁、修改 interestOps，还要等下一轮 select。直接写只需当前线程调用一次 `write`。多数响应包很小，通常一次就能写完。

### 边界情况

| 情况 | 处理 |
|---|---|
| 只写出一部分 | `bb.position` 已前移，剩余部分入队并打开 OP_WRITE，由 selector 线程接着发。入队前队列为空，顺序依然正确 |
| 抛 IOException | 吞掉（best effort），整个 `bb` 入队；真正的连接错误由 `doIO()` 统一处理并关闭连接 |
| `closeConn` 哨兵 | 不走直接写（第 440 行），必须入队，排在所有真实数据之后，`doIO()` 取到它时再关闭连接 |
| 检查 OP_WRITE 时未加锁 | 可能读到"已打开"的旧值（`doIO` 刚把队列发空、还没关 OP_WRITE），于是走了入队路径。只是少用一次快速路径，结果依然正确 |

---

## 2. 不是 selector 触发、也没注册 OP_WRITE，为什么 `sock.write` 能写出去

**Selector 和兴趣集只负责"通知"，不负责"授权"。**

- `SocketChannel.write()` 本质上是一次 `write/send` 系统调用，作用是把数据**拷贝进内核发送缓冲区（SO_SNDBUF）**。之后发到网络上由 TCP 协议栈负责。
- channel 是非阻塞模式，所以任何线程任何时候都能调用：

| 发送缓冲区 | `write` 的结果 |
|---|---|
| 空间充足 | 全部写入 |
| 空间不足 | 部分写入，返回实际写入的字节数 |
| 已满 | 立即返回 0，不阻塞 |
| 连接已断 | 抛 IOException |

- **OP_WRITE 就绪 = 发送缓冲区有空间了**。它只用在"之前写不进去、需要等空间"的场景。
- socket 几乎一直是可写的，所以 OP_WRITE 不能常开，否则 `select()` 每次都立即返回，selector 空转、CPU 跑满。这也是 `doIO()` 在队列发空后立刻关闭 OP_WRITE 的原因（第 727 行）。

**思路**：先乐观地直接写，写不完再求助 selector。

> 类比：发送缓冲区是邮筒，`write` 是投信。有空位随时可以投；满了就留电话（注册 OP_WRITE），等邮局（selector）通知有空位了再来投。

---

## 3. 数据交错：是什么，以及 ZooKeeper 如何避免

### 是什么

TCP 是字节流，ZooKeeper 靠 4 字节长度前缀给消息分帧：

```
| len=20 | 响应A(20B) | len=35 | 响应B(35B) | ...
```

交错就是两个消息的字节混在一起，例如 `A 前半 | B | A 后半`。客户端按长度切分时会读到乱码，反序列化失败，随后断开连接。

### JDK 的 writeLock 为什么不够

`SocketChannel.write` 内部有锁，**单次 write** 是原子的。但非阻塞 write 可能只写出一部分，**一个消息会被拆成多次 write**，别的线程可以在两次 write 之间插进来：

```
线程1: write(A, 24B) → 只写进 10B
线程2: write(B, 39B) → 全部写入
线程1: A 剩下的 14B 入队，稍后发送
流: A[0..10) | B | A[10..24)   ← 交错
```

所以需要在**逻辑层面**串行化。

### 三道防线

1. **连接级串行化**：`synchronized public void sendResponse(...)`（第 1606 行）锁住连接对象。同一连接的响应和 watch 通知同一时刻只能有一个线程进入 `sendBuffer`，而且前一个线程把剩余部分入队、打开 OP_WRITE 之后才会退出。
2. **OP_WRITE 当门闩**：队列非空时 OP_WRITE 一定是打开的，后来的线程只能排到队尾，不能直接写。
3. **两类线程的写条件互斥**：
   - selector 线程只在 `isWritable()` 时写，这时队列非空；
   - 业务线程只在 OP_WRITE 关闭时直接写，这时队列为空。

   另外，`factory` 锁保证「入队 + 打开 OP_WRITE」和「判断队列为空 + 关闭 OP_WRITE」互斥，不会丢失写事件。

### 没经过 `sendResponse` 同步的旁路

| 入口 | 为什么安全 |
|---|---|
| `sendCloseSession()` | `closeConn` 一定入队，排在最后 |
| `finishSessionInit()`（第 1686 行） | 连接上的第一个响应，客户端收到它之后才会发业务请求。**靠协议时序而不是锁**，是一个隐含前提 |

**总结**：交错的根源是非阻塞 write 可能写不完。ZooKeeper 用连接级 `synchronized`、OP_WRITE 门闩和 `factory` 锁这三者共同保证每个连接上的字节流按消息顺序、完整地发出。

---

## 4. 为什么变量叫 `si`（ZooKeeperServer.java:568）

```java
public void submitRequest(Request si) { ... firstProcessor.processRequest(si); ... }
```

- **没有官方解释**，注释、JIRA 和提交记录里都查不到。
- 这是早期（Yahoo 时期）作者的**全局命名习惯**，多处都这样用：
  - `NIOServerCnxn.java:810`
  - `SyncRequestProcessor.java:92`
  - `ZKDatabase.java:474`
  - `FileTxnSnapLog.java:330`
  - `quorum/LearnerHandler.java:488`
  - `quorum/SendAckRequestProcessor.java:39`
- 推测的含义（均未证实）：*submitted item*、*server item/input*，或者是更早期类名留下的缩写。
- **当作 `request` 来读即可**：一个 `Request` 对象，封装了 `cnxn`、`sessionId`、`xid`、`type`、请求体 `bb` 和 `authInfo`，会被送入处理链 `PrepRequestProcessor → SyncRequestProcessor → FinalRequestProcessor`。

---

## 5. `sendBuffer` 的 factory 锁与 `Factory.run()` 中 `synchronized (this)` 的配合

`sendBuffer()` 中 `synchronized(this.factory)` 和 `Factory.run()` 中 `synchronized (this) { selected = selector.selectedKeys(); }` 是**同一把锁**（`this` 就是 factory），两段代码配套使用。这是早期 Java NIO 的惯用写法：**业务线程先 wakeup，再在锁里修改 interestOps；selector 线程被唤醒后，先过一道锁，才能继续往下走。**

```java
// 业务线程：sendBuffer()（NIOServerCnxn.java:458-468）
synchronized (this.factory) {
    sk.selector().wakeup();                                   // ① 把 selector 从 select() 中踢出来
    outgoingBuffers.add(bb);                                  // ② 入队
    sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE); // ③ 打开写兴趣
}

// selector 线程：Factory.run()（NIOServerCnxn.java:235-240）
selector.select(1000);           // ④ 被 ① 唤醒后返回
synchronized (this) {            // ⑤ 业务线程还在锁里，就卡在这里等
    selected = selector.selectedKeys();
}
// ... 处理完后回到 ④，重新 select()
```

### 解决的两个问题

1. **让新的 OP_WRITE 尽快生效**
   - 正在进行的 `select()` 使用的是调用时的兴趣集，中途修改 interestOps，不保证本次 select 能感知到。
   - 如果不调用 `wakeup()`，新入队的数据最多要等 `select(1000)` 超时，也就是约 1 秒才会被发送。
   - `wakeup()` 让 select 立刻返回，下一轮 select 就会带上新的 OP_WRITE。

2. **避免 `interestOps(int)` 被正在进行的 select 卡住**
   - `SelectionKey` 的 JavaDoc 写明：在朴素实现中，select 进行期间读写兴趣集可能会一直阻塞。早期 JDK 的部分平台实现确实如此，`register()` 也有同样的问题。
   - 业务线程先 `wakeup()`：即使 selector 此刻不在 select 中，下一次 select 也会立即返回。
   - selector 线程返回后要先抢 factory 锁。业务线程正持有这把锁，所以 selector 线程**卡在第 238 行，回不到 select()**。
   - 业务线程在"selector 一定不在 select 中"的窗口里安全地完成 ②③，然后释放锁。

**结论**：第 238 行的 `synchronized` 保护的不是 `selectedKeys()` 这个调用本身。获取集合引用不需要加锁，后面遍历集合也没有持锁。它的真正作用是在 select 返回后**设一道门**：只要有线程正在锁里修改兴趣集，selector 线程就必须等它改完，才能重新进入 select。

### 同一把 factory 锁的两种用途

| 配对 | 目的 |
|---|---|
| `sendBuffer` 锁块 ↔ `run()` 第 238 行 | 让 wakeup 和修改兴趣集安全、及时地生效 |
| `sendBuffer` 锁块 ↔ `doIO()` 第 718 行 | 让「入队 + 打开 OP_WRITE」与「判断队列为空 + 关闭 OP_WRITE」互斥，不会丢失写事件（见第 3 节） |

> 补充：从 JDK 11 开始，Selector 实现做了重构，修改 interestOps 不再会被 select 阻塞，而是排队等下一次 select 时生效，所以第 2 个问题已经不存在；但为了及时生效，`wakeup()` 仍然需要。ZooKeeper 3.3.x 面向的是 JDK 6 时代，两个问题都要处理。

---

## 6. `OP_READ`、`OP_WRITE` 中的 `OP` 是什么意思

`OP` 是 **Operation（操作）** 的缩写。这些常量定义在 `java.nio.channels.SelectionKey` 中，JavaDoc 原文是：

> `OP_READ`: **Operation-set bit** for read operations.

意思是"操作集合中代表读操作的那一位"。

### 四个常量

```java
public static final int OP_READ    = 1 << 0;  // 1  ：可读（有数据到达，或对端关闭）
public static final int OP_WRITE   = 1 << 2;  // 4  ：可写（发送缓冲区有空间）
public static final int OP_CONNECT = 1 << 3;  // 8  ：客户端 connect() 完成
public static final int OP_ACCEPT  = 1 << 4;  // 16 ：服务端有新连接可以 accept()
```

（`1 << 1` 没有被使用，是历史原因留下的空位。）

### 为什么都用 `OP_` 前缀

1. **它们是同一个「操作集合（operation set）」中的位标志**
   - `SelectionKey` 有两个集合：`interestOps()`（兴趣集，表示想监听哪些操作）和 `readyOps()`（就绪集，表示哪些操作现在可以做了）。
   - `OP_*` 就是这两个集合中的每一位。每个常量占一位，因此可以用位运算组合和判断：

   ```java
   sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);    // 置位：加上写兴趣
   sk.interestOps(sk.interestOps() & ~SelectionKey.OP_WRITE);   // 清位：去掉写兴趣
   (k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0  // 判断：读或写就绪
   ```

2. **命名习惯**：用统一前缀给一组相关常量归类，沿袭自 C 语言的做法。例如 `open()` 的 `O_RDONLY`、`O_NONBLOCK`，Linux epoll 的 `EPOLLIN`、`EPOLLOUT`。Java NIO 的 Selector 本身就是对 select/poll/epoll 的封装，所以沿用了同样的风格。

### 不同的 channel 支持不同的操作

可以用 `channel.validOps()` 查看：

| Channel | 支持的操作 |
|---|---|
| `ServerSocketChannel` | 只有 `OP_ACCEPT` |
| `SocketChannel` | `OP_READ`、`OP_WRITE`、`OP_CONNECT` |

对应到 ZooKeeper 的代码：

- 监听 socket `ss` 注册 `OP_ACCEPT`，`run()` 用它接收新连接（第 245 行）；
- 客户端连接 `sc` 注册 `OP_READ`（第 258 行），需要发送数据时再临时加上 `OP_WRITE`；
- `OP_CONNECT` 用于客户端主动连接，服务端用不到。
