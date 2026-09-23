/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.Environment;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.proto.AuthPacket;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.auth.AuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;

/**
 * This class handles communication with clients using NIO. There is one per
 * client, but only one thread doing the communication.
 */
public class NIOServerCnxn implements Watcher, ServerCnxn {
    private static final Logger LOG = Logger.getLogger(NIOServerCnxn.class);

    private ConnectionBean jmxConnectionBean;

    static public class Factory extends Thread {
        static {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    LOG.error("Thread " + t + " died", e);
                }
            });
            /**
             * this is to avoid the jvm bug:
             * NullPointerException in Selector.open()
             * http://bugs.sun.com/view_bug.do?bug_id=6427854
             */
            try {
                Selector.open().close();
            } catch(IOException ie) {
                LOG.error("Selector failed to open", ie);
            }
        }

        ZooKeeperServer zks;

        final ServerSocketChannel ss;

        final Selector selector = Selector.open();

        /**
         * We use this buffer to do efficient socket I/O. Since there is a single
         * sender thread per NIOServerCnxn instance, we can use a member variable to
         * only allocate it once.
        */
        final ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);

        final HashSet<NIOServerCnxn> cnxns = new HashSet<NIOServerCnxn>();
        final HashMap<InetAddress, Set<NIOServerCnxn>> ipMap =
            new HashMap<InetAddress, Set<NIOServerCnxn>>( );

        int outstandingLimit = 1;

        int maxClientCnxns = 10;

        /**
         * Construct a new server connection factory which will accept an unlimited number
         * of concurrent connections from each client (up to the file descriptor
         * limits of the operating system). startup(zks) must be called subsequently.
         * @param port
         * @throws IOException
         */
        public Factory(InetSocketAddress addr) throws IOException {
            this(addr, 0);
        }


        /**
         * Constructs a new server connection factory where the number of concurrent connections
         * from a single IP address is limited to maxcc (or unlimited if 0).
         * startup(zks) must be called subsequently.
         * @param port - the port to listen on for connections.
         * @param maxcc - the number of concurrent connections allowed from a single client.
         * @throws IOException
         */
        public Factory(InetSocketAddress addr, int maxcc) throws IOException {
            super("NIOServerCxn.Factory:" + addr);
            setDaemon(true);
            maxClientCnxns = maxcc;
            this.ss = ServerSocketChannel.open();
            ss.socket().setReuseAddress(true);
            LOG.info("binding to port " + addr);
            ss.socket().bind(addr);
            // https://medium.com/@kaustubh.saha/java-nio-2d64e57f8d3f
            ss.configureBlocking(false);
            ss.register(selector, SelectionKey.OP_ACCEPT);
        }

        @Override
        public void start() {
            // ensure thread is started once and only once
            if (getState() == Thread.State.NEW) {
                super.start();
            }
        }

        public void startup(ZooKeeperServer zks) throws IOException,
                InterruptedException {
            start();
            zks.startdata();
            zks.startup();
            setZooKeeperServer(zks);
        }

        public void setZooKeeperServer(ZooKeeperServer zks) {
            this.zks = zks;
            if (zks != null) {
                this.outstandingLimit = zks.getGlobalOutstandingLimit();
                zks.setServerCnxnFactory(this);
            } else {
                this.outstandingLimit = 1;
            }
        }

        public ZooKeeperServer getZooKeeperServer() {
            return this.zks;
        }

        public InetSocketAddress getLocalAddress(){
            return (InetSocketAddress)ss.socket().getLocalSocketAddress();
        }

        public int getLocalPort(){
            return ss.socket().getLocalPort();
        }

        public int getMaxClientCnxns() {
            return maxClientCnxns;
        }

        private void addCnxn(NIOServerCnxn cnxn) {
            synchronized (cnxns) {
                cnxns.add(cnxn);
                synchronized (ipMap){
                    InetAddress addr = cnxn.sock.socket().getInetAddress();
                    Set<NIOServerCnxn> s = ipMap.get(addr);
                    if (s == null) {
                        // in general we will see 1 connection from each
                        // host, setting the initial cap to 2 allows us
                        // to minimize mem usage in the common case
                        // of 1 entry --  we need to set the initial cap
                        // to 2 to avoid rehash when the first entry is added
                        s = new HashSet<NIOServerCnxn>(2);
                        s.add(cnxn);
                        ipMap.put(addr,s);
                    } else {
                        s.add(cnxn);
                    }
                }
            }
        }

        protected NIOServerCnxn createConnection(SocketChannel sock,
                SelectionKey sk) throws IOException {
            return new NIOServerCnxn(zks, sock, sk, this);
        }

        private int getClientCnxnCount(InetAddress cl) {
            // The ipMap lock covers both the map, and its contents
            // (that is, the cnxn sets shouldn't be modified outside of
            // this lock)
            synchronized (ipMap) {
                Set<NIOServerCnxn> s = ipMap.get(cl);
                if (s == null) return 0;
                return s.size();
            }
        }

        public void run() {
            // 1.接收新连接并新建 ServerCnxn
            // 2.处理 ServerCnxn 上的读写
            while (!ss.socket().isClosed()) {
                try {
                    selector.select(1000);
                    Set<SelectionKey> selected;
                    // 这里为什么在 this 上同步？
                    synchronized (this) {
                        selected = selector.selectedKeys();
                    }
                    ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(selected);
                    Collections.shuffle(selectedList);

                    for (SelectionKey k : selectedList) {
                        if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                            // 获取 server 和 client 之间的 channel
                            SocketChannel sc = ((ServerSocketChannel) k.channel()).accept();
                            InetAddress ia = sc.socket().getInetAddress();
                            int cnxncount = getClientCnxnCount(ia);
                            if (maxClientCnxns > 0 && cnxncount >= maxClientCnxns){
                                LOG.warn("Too many connections from " + ia + " - max is " + maxClientCnxns );
                                sc.close();
                            } else {
                                LOG.info("Accepted socket connection from " + sc.socket().getRemoteSocketAddress());
                                // 将客户端 channel 配置为 非阻塞
                                sc.configureBlocking(false);
                                // 将读事件注册到 selector
                                SelectionKey sk = sc.register(selector, SelectionKey.OP_READ);
                                // 创建服务端 cnxn
                                NIOServerCnxn cnxn = createConnection(sc, sk);
                                // 附件到 sk
                                sk.attach(cnxn);
                                addCnxn(cnxn);
                            }
                        } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                            NIOServerCnxn c = (NIOServerCnxn) k.attachment();
                            c.doIO(k);
                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Unexpected ops in select "
                                          + k.readyOps());
                            }
                        }
                    }
                    selected.clear();
                } catch (RuntimeException e) {
                    LOG.warn("Ignoring unexpected runtime exception", e);
                } catch (Exception e) {
                    LOG.warn("Ignoring exception", e);
                }
            }
            // socket 关闭后清理资源
            clear();
            LOG.info("NIOServerCnxn factory exited run method");
        }

        /**
         * Clear all the connections in the selector.
         * 
         * You must first close ss (the serversocketchannel) if you wish
         * to block any new connections from being established.
         *
         */
        //  org.apache.zookeeper.server.NIOServerCnxn.Factory
        // clear NIOServerCnxn
        @SuppressWarnings("unchecked")
        synchronized public void clear() {
            selector.wakeup();

            HashSet<NIOServerCnxn> cnxns;
            synchronized (this.cnxns) {
                // 浅拷贝
                cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone();
            }

            // got to clear all the connections that we have in the selector
            for (NIOServerCnxn cnxn: cnxns) {
                try {
                    // don't hold this.cnxns lock as deadlock may occur
                    cnxn.close();
                } catch (Exception e) {
                    LOG.warn("Ignoring exception closing cnxn sessionid 0x"
                            + Long.toHexString(cnxn.sessionId), e);
                }
            }
        }

        // //  org.apache.zookeeper.server.NIOServerCnxn.Factory
        // 关闭整个 NIO 服务端工厂：停止监听、断开所有连接、结束 selector 线程、关闭底层服务
        public void shutdown() {
            try {
                // 1. 关闭 ServerSocketChannel（监听 socket），不再接受新的客户端连接
                ss.close();
                // 2. 遍历并关闭当前所有已建立的客户端连接（见上面的 clear() 方法）
                clear();
                // 3. 中断本线程（Factory 本身是一个 Thread，run() 里阻塞在 selector.select() 上）
                this.interrupt();
                // 4. 等待本线程真正结束（阻塞直到 run() 退出），保证 selector 线程已停
                this.join();
            } catch (InterruptedException e) {
                // join()/interrupt() 期间被中断——关闭阶段可忽略，仅告警
                LOG.warn("Ignoring interrupted exception during shutdown", e);
            } catch (Exception e) {
                // 关闭过程中的其它意外异常同样只告警，不阻断后续清理
                LOG.warn("Ignoring unexpected exception during shutdown", e);
            }
            try {
                // 5. 关闭 selector 本身，释放其占用的系统资源（文件描述符等）
                selector.close();
            } catch (IOException e) {
                LOG.warn("Selector closing", e);
            }
            // 6. 若持有 ZooKeeperServer 实例，级联关闭它（关闭请求处理链、事务日志等）
            if (zks != null) {
                zks.shutdown();
            }
        }

        synchronized void closeSession(long sessionId) {
            selector.wakeup();
            closeSessionWithoutWakeup(sessionId);
        }

        @SuppressWarnings("unchecked")
        private void closeSessionWithoutWakeup(long sessionId) {
            HashSet<NIOServerCnxn> cnxns;
            synchronized (this.cnxns) {
                cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone();
            }

            for (NIOServerCnxn cnxn : cnxns) {
                if (cnxn.sessionId == sessionId) {
                    try {
                        cnxn.close();
                    } catch (Exception e) {
                        LOG.warn("exception during session close", e);
                    }
                    break;
                }
            }
        }
    }

    /**
     * The buffer will cause the connection to be close when we do a send.
     */
    static final ByteBuffer closeConn = ByteBuffer.allocate(0);

    final Factory factory;

    /** The ZooKeeperServer for this connection. May be null if the server
     * is not currently serving requests (for example if the server is not
     * an active quorum participant.
     */
    private final ZooKeeperServer zk;

    private SocketChannel sock;

    private SelectionKey sk;

    boolean initialized;

    ByteBuffer lenBuffer = ByteBuffer.allocate(4);

    ByteBuffer incomingBuffer = lenBuffer;

    LinkedBlockingQueue<ByteBuffer> outgoingBuffers = new LinkedBlockingQueue<ByteBuffer>();

    int sessionTimeout;

    ArrayList<Id> authInfo = new ArrayList<Id>();

    /* Send close connection packet to the client, doIO will eventually
     * close the underlying machinery (like socket, selectorkey, etc...)
     */
    public void sendCloseSession() {
        sendBuffer(closeConn);
    }

    /**
     * send buffer without using the asynchronous
     * calls to selector and then close the socket
     * @param bb
     */
    void sendBufferSync(ByteBuffer bb) {
       try {
           /* configure socket to be blocking
            * so that we dont have to do write in 
            * a tight while loop
            */
           sock.configureBlocking(true);
           if (bb != closeConn) {
               if (sock != null) {
                   sock.write(bb);
               }
               packetSent();
           } 
       } catch (IOException ie) {
           LOG.error("Error sending data synchronously ", ie);
       }
    }
    
    void sendBuffer(ByteBuffer bb) {
        try {
            if (bb != closeConn) {
                // We check if write interest here because if it is NOT set,
                // nothing is queued, so we can try to send the buffer right
                // away without waking up the selector
                if ((sk.interestOps() & SelectionKey.OP_WRITE) == 0) {
                    try {
                        sock.write(bb);
                    } catch (IOException e) {
                        // we are just doing best effort right now
                    }
                }
                // if there is nothing left to send, we are done
                if (bb.remaining() == 0) {
                    packetSent();
                    return;
                }
            }

            synchronized(this.factory){
                sk.selector().wakeup();
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Add a buffer to outgoingBuffers, sk " + sk
                            + " is valid: " + sk.isValid());
                }
                outgoingBuffers.add(bb);
                if (sk.isValid()) {
                    sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
                }
            }
            
        } catch(Exception e) {
            LOG.error("Unexpected Exception: ", e);
        }
    }

    private static class CloseRequestException extends IOException {
        private static final long serialVersionUID = -7854505709816442681L;

        public CloseRequestException(String msg) {
            super(msg);
        }
    }

    private static class EndOfStreamException extends IOException {
        private static final long serialVersionUID = -8255690282104294178L;

        public EndOfStreamException(String msg) {
            super(msg);
        }

        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
    }

    /** Read the request payload (everything following the length prefix) */
    private void readPayload() throws IOException, InterruptedException {
        // 正在接收数据中
        if (incomingBuffer.remaining() != 0) { // have we read length bytes?
            // 这里如何理解, 为什么又读了一遍? 贪心策略
            int rc = sock.read(incomingBuffer); // sock is non-blocking, so ok
            if (rc < 0) {
                throw new EndOfStreamException(
                        "Unable to read additional data from client sessionid 0x"
                        + Long.toHexString(sessionId)
                        + ", likely client has closed socket");
            }
        }

        if (incomingBuffer.remaining() == 0) { // have we read length bytes?
            packetReceived();
            incomingBuffer.flip();
            if (!initialized) {
                // 建立会话、协商参数
                readConnectRequest();
            } else {
                // 处理业务操作
                readRequest();
            }
            lenBuffer.clear();
            incomingBuffer = lenBuffer;
        }
    }

    void doIO(SelectionKey k) throws InterruptedException {
        try {
            if (sock == null) {
                LOG.warn("trying to do i/o on a null socket for session:0x"
                         + Long.toHexString(sessionId));

                return;
            }
            if (k.isReadable()) {
                int rc = sock.read(incomingBuffer);
                if (rc < 0) {
                    throw new EndOfStreamException(
                            "Unable to read additional data from client sessionid 0x"
                            + Long.toHexString(sessionId)
                            + ", likely client has closed socket");
                }
                // 这段不为0呢，当前连接的状态是什么样:正在接收数据中
                if (incomingBuffer.remaining() == 0) {
                    boolean isPayload;
                    // 这里是2个对象的比较 是不是一个对象，标识一个请求的开始
                    if (incomingBuffer == lenBuffer) { // start of next request
                        incomingBuffer.flip();
                        isPayload = readLength(k);
                        incomingBuffer.clear();
                    } else {
                        // continuation
                        isPayload = true;
                    }
                    if (isPayload) { // not the case for 4letterword
                        readPayload();
                    }
                    else {
                        // four letter words take care
                        // need not do anything else
                        return;
                    }
                }
            }
            /*
             * ==================== 写分支整体分析 ====================
             *
             * 【何时进入】selector 报告该连接可写（OP_WRITE 就绪）。OP_WRITE 只在「有待发数据」时
             *   才会被打开：业务线程调用 sendBuffer()，如果直接 write 没写完（或当前已在排队），
             *   就把剩余数据放入 outgoingBuffers 并打开 OP_WRITE，交给 selector 线程在这里异步发送。
             *
             * 【三个阶段】
             *   1. 聚合拷贝：把队列中多个（通常是很小的）堆内 ByteBuffer 依次拷进 Factory 共享的
             *      64KB 直接内存 directBuffer，最多拷满为止。
             *   2. 一次写出：sock.write(directBuffer) 一次系统调用发出尽可能多的数据；
             *      非阻塞 socket 可能只写出一部分（内核发送缓冲区满），返回实际写出的字节数 sent。
             *   3. 按字节数出队：用 sent 从队头往后「核销」：整块发完的出队并计数，发了一半的
             *      推进 position 后停止；遇到 closeConn 标记说明前面的数据都已发完，抛异常关闭连接。
             *
             * 【为什么要拷到 directBuffer】
             *   - 用堆内 buffer 直接 write，JDK 内部也会临时拷贝到一块直接内存再发送；这里自己维护
             *     一块复用的直接内存，省去每次临时分配。
             *   - 把多个小响应合并成一次 write，减少系统调用次数（效果类似 gathering write）。
             *   - directBuffer 属于 Factory，所有连接共用；这样做是安全的，因为 doIO 只在唯一的
             *     selector 线程里执行，同一时刻只有一个连接在使用它。
             *
             * 【为什么源 buffer 的 position 要先保存再还原】
             *   拷贝时不能动源 buffer 的 position：此时还不知道 socket 实际能写出多少字节，
             *   只有写完之后才按 sent 推进 position，这样没写出去的部分下次还能重新拷贝发送。
             *
             * 【最后的 synchronized(factory) 块：维护 OP_WRITE】
             *   - 队列空了：关掉 OP_WRITE，否则 socket 一直可写，selector 会不停空转（CPU 100%）。
             *   - 队列非空：保持 OP_WRITE，等 socket 再次可写时继续发送。
             *   - 必须加锁，并且和 sendBuffer() 用同一把锁（factory）：sendBuffer() 在锁内做
             *     「入队 + 打开 OP_WRITE」，这里在锁内做「判断队列空 + 关闭 OP_WRITE」。
             *     如果不加锁，可能出现：这里判断队列为空 → 业务线程入队并打开 OP_WRITE → 这里再
             *     关闭 OP_WRITE。结果新数据留在队列里，却再也没有写事件来触发发送，响应卡死。
             *
             * 【几处要注意的点】
             *   - 队列元素可能被拆分：一个响应可能要分多次 doIO 才能发完（部分写），
             *     position 就是它的「已发送进度」。
             *   - closeConn 是长度为 0 的哨兵 buffer（sendCloseSession() 放入），用「==」比较引用，
             *     作用是「前面的数据都发完之后再关闭连接」，保证关闭前的最后一个响应能送到客户端。
             *   - "responded to info probe" 分支是早期四字命令（ruok 等）的收尾逻辑：未初始化
             *     并且读已关闭，说明只是一次探测，回完就关闭。3.3.6 中四字命令会 cancel 掉
             *     SelectionKey，改用 sendBufferSync 同步发送，已不会走到这里，基本属于历史遗留代码。
             */
            if (k.isWritable()) {
                // ZooLog.logTraceMessage(LOG,
                // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK
                // "outgoingBuffers.size() = " +
                // outgoingBuffers.size());
                // 有待发送的数据才进入发送流程；队列为空时直接跳到下面，去关闭 OP_WRITE
                if (outgoingBuffers.size() > 0) {
                    // ZooLog.logTraceMessage(LOG,
                    // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK,
                    // "sk " + k + " is valid: " +
                    // k.isValid());

                    /*
                     * This is going to reset the buffer position to 0 and the
                     * limit to the size of the buffer, so that we can fill it
                     * with data from the non-direct buffers that we need to
                     * send.
                     */
                    // 取 Factory 共享的 64KB 直接内存（所有连接共用，只在 selector 线程中使用）
                    ByteBuffer directBuffer = factory.directBuffer;
                    // clear()：position=0，limit=capacity，变成「整块可写入」的状态，准备接收拷贝
                    directBuffer.clear();

                    // 按先进先出的顺序遍历待发队列（只读遍历，不出队），把数据拷进 directBuffer
                    for (ByteBuffer b : outgoingBuffers) {
                        // 当前 buffer 的剩余数据比 directBuffer 的剩余空间大，一次放不下
                        if (directBuffer.remaining() < b.remaining()) {
                            /*
                             * When we call put later, if the directBuffer is to
                             * small to hold everything, nothing will be copied,
                             * so we've got to slice the buffer if it's too big.
                             */
                            // put(src) 要求目标装得下 src 的全部剩余数据，否则抛
                            // BufferOverflowException，一个字节都不拷。
                            // 所以用 slice() 得到一个共享底层数据的新视图（从 b 当前 position 开始），
                            // 再把它的 limit 截到 directBuffer 的剩余空间，只拷前面能放下的部分。
                            // 强转是因为 Java 8 中 Buffer.limit(int) 返回的是 Buffer 而不是 ByteBuffer。
                            b = (ByteBuffer) b.slice().limit(
                                    directBuffer.remaining());
                        }
                        /*
                         * put() is going to modify the positions of both
                         * buffers, put we don't want to change the position of
                         * the source buffers (we'll do that after the send, if
                         * needed), so we save and reset the position after the
                         * copy
                         */
                        // 记住源 buffer 拷贝前的 position
                        int p = b.position();
                        // 拷贝：directBuffer 和 b 的 position 都会前移本次拷贝的字节数
                        directBuffer.put(b);
                        // 把源 buffer 的 position 还原：还不知道 socket 实际能写出多少，
                        // 写完后再按实际写出的字节数推进。
                        // （如果 b 是上面 slice 出来的视图，还原的是视图，原队列元素本来就没动）
                        b.position(p);
                        // directBuffer 已拷满，后面的 buffer 等下一次可写事件再发
                        if (directBuffer.remaining() == 0) {
                            break;
                        }
                    }
                    /*
                     * Do the flip: limit becomes position, position gets set to
                     * 0. This sets us up for the write.
                     */
                    // flip()：limit=已拷入的数据量，position=0，切换成「可读出」状态，准备 write
                    directBuffer.flip();

                    // 非阻塞写：返回实际写出的字节数，可能小于 directBuffer 中的数据量，甚至为 0
                    // （内核发送缓冲区满了）
                    int sent = sock.write(directBuffer);
                    // 用来指向队头 buffer 的临时变量
                    ByteBuffer bb;

                    // Remove the buffers that we have sent
                    // 按实际写出的字节数 sent，从队头开始「核销」已发送的 buffer
                    while (outgoingBuffers.size() > 0) {
                        // 只查看队头，不出队：它可能只发出了一部分
                        bb = outgoingBuffers.peek();
                        // 遇到关闭哨兵：说明它前面的数据都已完整发出，现在可以关闭连接了
                        // （用 == 比较引用，closeConn 是全局唯一的长度为 0 的 buffer）
                        if (bb == closeConn) {
                            // 抛出后由下面的 catch (CloseRequestException) 调用 close()
                            throw new CloseRequestException("close requested");
                        }
                        // 队头 buffer 未发送的字节数，减去本次还没核销完的已发送字节数
                        // （拷贝时 position 已还原，所以 bb.remaining() 仍是发送前的剩余量）
                        int left = bb.remaining() - sent;
                        // left > 0：这个 buffer 只发出了一部分
                        if (left > 0) {
                            /*
                             * We only partially sent this buffer, so we update
                             * the position and exit the loop.
                             */
                            // 把 position 推进 sent 个字节，记录发送进度；下次从未发送的位置继续
                            bb.position(bb.position() + sent);
                            // 本次写出的字节已全部核销完，后面的 buffer 都没发，退出
                            break;
                        }
                        // 走到这里说明该 buffer 已完整发出：统计发送包数（连接级和服务器级）
                        packetSent();
                        /* We've sent the whole buffer, so drop the buffer */
                        // 从 sent 中扣掉这个 buffer 的字节数，剩下的继续核销后面的 buffer
                        sent -= bb.remaining();
                        // 出队：真正从待发队列中移除已发送完的 buffer
                        outgoingBuffers.remove();
                    }
                    // ZooLog.logTraceMessage(LOG,
                    // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK, "after send,
                    // outgoingBuffers.size() = " + outgoingBuffers.size());
                }

                // 和 sendBuffer() 用同一把锁：保证「判断队列 + 修改 OP_WRITE」与
                // 「入队 + 打开 OP_WRITE」互斥，避免丢失写事件（见上方整体分析）
                synchronized(this.factory){
                    // 待发数据已全部发完
                    if (outgoingBuffers.size() == 0) {
                        // 连接还未完成会话初始化，并且读事件已关闭：说明只是一次探测
                        // （早期四字命令的处理方式），响应已发完，关闭连接
                        if (!initialized
                                && (sk.interestOps() & SelectionKey.OP_READ) == 0) {
                            throw new CloseRequestException("responded to info probe");
                        }
                        // 关闭 OP_WRITE：没有数据要发了，否则 socket 一直可写，selector 会空转
                        sk.interestOps(sk.interestOps()
                                & (~SelectionKey.OP_WRITE));
                    } else {
                        // 还有数据没发完（部分写，或 directBuffer 装不下）：保持 OP_WRITE，
                        // 等 socket 再次可写时由 selector 再次调用 doIO 继续发送
                        sk.interestOps(sk.interestOps()
                                | SelectionKey.OP_WRITE);
                    }
                }
            }
        } catch (CancelledKeyException e) {
            LOG.warn("Exception causing close of session 0x"
                    + Long.toHexString(sessionId)
                    + " due to " + e);
            if (LOG.isDebugEnabled()) {
                LOG.debug("CancelledKeyException stack trace", e);
            }
            close();
        } catch (CloseRequestException e) {
            // expecting close to log session closure
            close();
        } catch (EndOfStreamException e) {
            LOG.warn(e); // tell user why

            // expecting close to log session closure
            close();
        } catch (IOException e) {
            LOG.warn("Exception causing close of session 0x"
                    + Long.toHexString(sessionId)
                    + " due to " + e);
            if (LOG.isDebugEnabled()) {
                LOG.debug("IOException stack trace", e);
            }
            close();
        }
    }

    private void readRequest() throws IOException {
        // We have the request, now process and setup for next
        InputStream bais = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        RequestHeader h = new RequestHeader();
        h.deserialize(bia, "header");
        // Through the magic of byte buffers, txn will not be
        // pointing
        // to the start of the txn
        incomingBuffer = incomingBuffer.slice();
        if (h.getType() == OpCode.auth) {
            AuthPacket authPacket = new AuthPacket();
            ZooKeeperServer.byteBuffer2Record(incomingBuffer, authPacket);
            String scheme = authPacket.getScheme();
            AuthenticationProvider ap = ProviderRegistry.getProvider(scheme);
            if (ap == null
                    || (ap.handleAuthentication(this, authPacket.getAuth())
                            != KeeperException.Code.OK)) {
                if (ap == null) {
                    LOG.warn("No authentication provider for scheme: "
                            + scheme + " has "
                            + ProviderRegistry.listProviders());
                } else {
                    LOG.warn("Authentication failed for scheme: " + scheme);
                }
                // send a response...
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.AUTHFAILED.intValue());
                sendResponse(rh, null, null);
                // ... and close connection
                sendCloseSession();
                disableRecv();
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Authentication succeeded for scheme: "
                              + scheme);
                }
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.OK.intValue());
                sendResponse(rh, null, null);
            }
            return;
        } else {
            Request si = new Request(this, sessionId, h.getXid(), h.getType(), incomingBuffer, authInfo);
            si.setOwner(ServerCnxn.me);
            zk.submitRequest(si);
        }
        if (h.getXid() >= 0) {
            synchronized (this) {
                outstandingRequests++;
            }
            synchronized (this.factory) {        
                // check throttling
                if (zk.getInProcess() > factory.outstandingLimit) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Throttling recv " + zk.getInProcess());
                    }
                    disableRecv();
                    // following lines should not be needed since we are
                    // already reading
                    // } else {
                    // enableRecv();
                }
            }
        }
    }

    public void disableRecv() {
        sk.interestOps(sk.interestOps() & (~SelectionKey.OP_READ));
    }

    public void enableRecv() {
        if (sk.isValid()) {
            int interest = sk.interestOps();
            if ((interest & SelectionKey.OP_READ) == 0) {
                sk.interestOps(interest | SelectionKey.OP_READ);
            }
        }
    }

    private void readConnectRequest() throws IOException, InterruptedException {
        BinaryInputArchive bia = BinaryInputArchive.getArchive(new ByteBufferInputStream(incomingBuffer));
        ConnectRequest connReq = new ConnectRequest();
        connReq.deserialize(bia, "connect");
        if (LOG.isDebugEnabled()) {
            LOG.debug("Session establishment request from client "
                    + sock.socket().getRemoteSocketAddress()
                    + " client's lastZxid is 0x"
                    + Long.toHexString(connReq.getLastZxidSeen()));
        }
        if (zk == null) {
            throw new IOException("ZooKeeperServer not running");
        }
        // 建立连接时候，如果客户端的zxid比当前的最大的还大，关闭链接
        if (connReq.getLastZxidSeen() > zk.getZKDatabase().getDataTreeLastProcessedZxid()) {
            String msg = "Refusing session request for client "
                + sock.socket().getRemoteSocketAddress()
                + " as it has seen zxid 0x"
                + Long.toHexString(connReq.getLastZxidSeen())
                + " our last zxid is 0x"
                + Long.toHexString(zk.getZKDatabase().getDataTreeLastProcessedZxid())
                + " client must try another server";

            LOG.info(msg);
            throw new CloseRequestException(msg);
        }
        sessionTimeout = connReq.getTimeOut();
        byte passwd[] = connReq.getPasswd();
        int minSessionTimeout = zk.getMinSessionTimeout();
        if (sessionTimeout < minSessionTimeout) {
            sessionTimeout = minSessionTimeout;
        }
        int maxSessionTimeout = zk.getMaxSessionTimeout();
        if (sessionTimeout > maxSessionTimeout) {
            sessionTimeout = maxSessionTimeout;
        }
        // 为什么？We don't want to receive any packets until we are sure that the
        // session is setup
        disableRecv();
        if (connReq.getSessionId() != 0) {
            long clientSessionId = connReq.getSessionId();
            LOG.info("Client attempting to renew session 0x" + Long.toHexString(clientSessionId)
                    + " at " + sock.socket().getRemoteSocketAddress());
            factory.closeSessionWithoutWakeup(clientSessionId);
            setSessionId(clientSessionId);
            zk.reopenSession(this, sessionId, passwd, sessionTimeout);
        } else {
            LOG.info("Client attempting to establish new session at "
                    + sock.socket().getRemoteSocketAddress());
            zk.createSession(this, passwd, sessionTimeout);
        }
        initialized = true;
    }

    private void packetReceived() {
        stats.incrPacketsReceived();
        if (zk != null) {
            zk.serverStats().incrementPacketsReceived();
        }
    }

    private void packetSent() {
        stats.incrPacketsSent();
        if (zk != null) {
            zk.serverStats().incrementPacketsSent();
        }
    }

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int confCmd =
        ByteBuffer.wrap("conf".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int consCmd =
        ByteBuffer.wrap("cons".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int crstCmd =
        ByteBuffer.wrap("crst".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int dumpCmd =
        ByteBuffer.wrap("dump".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int enviCmd =
        ByteBuffer.wrap("envi".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int getTraceMaskCmd =
        ByteBuffer.wrap("gtmk".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int ruokCmd =
        ByteBuffer.wrap("ruok".getBytes()).getInt();
    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int setTraceMaskCmd =
        ByteBuffer.wrap("stmk".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int srvrCmd =
        ByteBuffer.wrap("srvr".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int srstCmd =
        ByteBuffer.wrap("srst".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int statCmd =
        ByteBuffer.wrap("stat".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int wchcCmd =
        ByteBuffer.wrap("wchc".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int wchpCmd =
        ByteBuffer.wrap("wchp".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int wchsCmd =
        ByteBuffer.wrap("wchs".getBytes()).getInt();

    private final static HashMap<Integer, String> cmd2String =
        new HashMap<Integer, String>();

    // specify all of the commands that are available
    static {
        cmd2String.put(confCmd, "conf");
        cmd2String.put(consCmd, "cons");
        cmd2String.put(crstCmd, "crst");
        cmd2String.put(dumpCmd, "dump");
        cmd2String.put(enviCmd, "envi");
        cmd2String.put(getTraceMaskCmd, "gtmk");
        cmd2String.put(ruokCmd, "ruok");
        cmd2String.put(setTraceMaskCmd, "stmk");
        cmd2String.put(srstCmd, "srst");
        cmd2String.put(srvrCmd, "srvr");
        cmd2String.put(statCmd, "stat");
        cmd2String.put(wchcCmd, "wchc");
        cmd2String.put(wchpCmd, "wchp");
        cmd2String.put(wchsCmd, "wchs");
    }

    /**
     * clean up the socket related to a command and also make sure we flush the
     * data before we do that
     * 
     * @param pwriter
     *            the pwriter for a command socket
     */
    private void cleanupWriterSocket(PrintWriter pwriter) {
        try {
            if (pwriter != null) {
                pwriter.flush();
                pwriter.close();
            }
        } catch (Exception e) {
            LOG.info("Error closing PrintWriter ", e);
        } finally {
            try {
                close();
            } catch (Exception e) {
                LOG.error("Error closing a command socket ", e);
            }
        }
    }
    
    /**
     * This class wraps the sendBuffer method of NIOServerCnxn. It is
     * responsible for chunking up the response to a client. Rather
     * than cons'ing up a response fully in memory, which may be large
     * for some commands, this class chunks up the result.
     */
    private class SendBufferWriter extends Writer {
        private StringBuffer sb = new StringBuffer();
        
        /**
         * Check if we are ready to send another chunk.
         * @param force force sending, even if not a full chunk
         */
        private void checkFlush(boolean force) {
            if ((force && sb.length() > 0) || sb.length() > 2048) {
                sendBufferSync(ByteBuffer.wrap(sb.toString().getBytes()));
                // clear our internal buffer
                sb.setLength(0);
            }
        }

        @Override
        public void close() throws IOException {
            if (sb == null) return;
            checkFlush(true);
            sb = null; // clear out the ref to ensure no reuse
        }

        @Override
        public void flush() throws IOException {
            checkFlush(true);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            sb.append(cbuf, off, len);
            checkFlush(false);
        }
    }

    private static final String ZK_NOT_SERVING =
        "This ZooKeeper instance is not currently serving requests";
    
    /**
     * Set of threads for commmand ports. All the 4
     * letter commands are run via a thread. Each class
     * maps to a correspoding 4 letter command. CommandThread
     * is the abstract class from which all the others inherit.
     */
    private abstract class CommandThread extends Thread {
        PrintWriter pw;
        
        CommandThread(PrintWriter pw) {
            this.pw = pw;
        }
        
        public void run() {
            try {
                commandRun();
            } catch (IOException ie) {
                LOG.error("Error in running command ", ie);
            } finally {
                cleanupWriterSocket(pw);
            }
        }
        
        public abstract void commandRun() throws IOException;
    }
    
    private class RuokCommand extends CommandThread {
        public RuokCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            pw.print("imok");
            
        }
    }
    
    private class TraceMaskCommand extends CommandThread {
        TraceMaskCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            long traceMask = ZooTrace.getTextTraceLevel();
            pw.print(traceMask);
        }
    }
    
    private class SetTraceMaskCommand extends CommandThread {
        long trace = 0;
        SetTraceMaskCommand(PrintWriter pw, long trace) {
            super(pw);
            this.trace = trace;
        }
        
        @Override
        public void commandRun() {
            pw.print(trace);
        }
    }
    
    private class EnvCommand extends CommandThread {
        EnvCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            List<Environment.Entry> env = Environment.list();

            pw.println("Environment:");
            for(Environment.Entry e : env) {
                pw.print(e.getKey());
                pw.print("=");
                pw.println(e.getValue());
            }
            
        } 
    }
    
    private class ConfCommand extends CommandThread {
        ConfCommand(PrintWriter pw) {
            super(pw);
        }
            
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            } else {
                zk.dumpConf(pw);
            }
        }
    }
    
    private class StatResetCommand extends CommandThread {
        public StatResetCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            }
            else { 
                zk.serverStats().reset();
                pw.println("Server stats reset.");
            }
        }
    }
    
    private class CnxnStatResetCommand extends CommandThread {
        public CnxnStatResetCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            } else {
                synchronized(factory.cnxns){
                    for(NIOServerCnxn c : factory.cnxns){
                        c.getStats().reset();
                    }
                }
                pw.println("Connection stats reset.");
            }
        }
    }

    private class DumpCommand extends CommandThread {
        public DumpCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            }
            else {
                pw.println("SessionTracker dump:");
                zk.sessionTracker.dumpSessions(pw);
                pw.println("ephemeral nodes dump:");
                zk.dumpEphemerals(pw);
            }
        }
    }
    
    private class StatCommand extends CommandThread {
        int len;
        public StatCommand(PrintWriter pw, int len) {
            super(pw);
            this.len = len;
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            }
            else {   
                pw.print("Zookeeper version: ");
                pw.println(Version.getFullVersion());
                if (len == statCmd) {
                    LOG.info("Stat command output");
                    pw.println("Clients:");
                    // clone should be faster than iteration
                    // ie give up the cnxns lock faster
                    HashSet<NIOServerCnxn> cnxnset;
                    synchronized(factory.cnxns){
                        cnxnset = (HashSet<NIOServerCnxn>)factory
                        .cnxns.clone();
                    }
                    for(NIOServerCnxn c : cnxnset){
                        ((CnxnStats)c.getStats())
                        .dumpConnectionInfo(pw, true);
                    }
                    pw.println();
                }
                pw.print(zk.serverStats().toString());
                pw.print("Node count: ");
                pw.println(zk.getZKDatabase().getNodeCount());
            }
            
        }
    }
    
    private class ConsCommand extends CommandThread {
        public ConsCommand(PrintWriter pw) {
            super(pw);
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            } else {
                // clone should be faster than iteration
                // ie give up the cnxns lock faster
                HashSet<NIOServerCnxn> cnxns;
                synchronized (factory.cnxns) {
                    cnxns = (HashSet<NIOServerCnxn>) factory.cnxns.clone();
                }
                for (NIOServerCnxn c : cnxns) {
                    ((CnxnStats) c.getStats()).dumpConnectionInfo(pw, false);
                }
                pw.println();
            }
        }
    }
    
    private class WatchCommand extends CommandThread {
        int len = 0;
        public WatchCommand(PrintWriter pw, int len) {
            super(pw);
            this.len = len;
        }

        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            } else {
                DataTree dt = zk.getZKDatabase().getDataTree();
                if (len == wchsCmd) {
                    dt.dumpWatchesSummary(pw);
                } else if (len == wchpCmd) {
                    dt.dumpWatches(pw, true);
                } else {
                    dt.dumpWatches(pw, false);
                }
                pw.println();
            }
        }
    }
    
    
    /** Return if four letter word found and responded to, otw false **/
    private boolean checkFourLetterWord(final SelectionKey k, final int len)
    throws IOException
    {
        // We take advantage of the limited size of the length to look
        // for cmds. They are all 4-bytes which fits inside of an int
        String cmd = cmd2String.get(len);
        if (cmd == null) {
            return false;
        }
        LOG.info("Processing " + cmd + " command from "
                + sock.socket().getRemoteSocketAddress());
        packetReceived();

        /** cancel the selection key to remove the socket handling
         * from selector. This is to prevent netcat problem wherein
         * netcat immediately closes the sending side after sending the
         * commands and still keeps the receiving channel open. 
         * The idea is to remove the selectionkey from the selector
         * so that the selector does not notice the closed read on the
         * socket channel and keep the socket alive to write the data to
         * and makes sure to close the socket after its done writing the data
         */
        if (k != null) {
            try {
                k.cancel();
            } catch(Exception e) {
                LOG.error("Error cancelling command selection key ", e);
            }
        }

        final PrintWriter pwriter = new PrintWriter(new BufferedWriter(new SendBufferWriter()));
        if (len == ruokCmd) {
            RuokCommand ruok = new RuokCommand(pwriter);
            ruok.start();
            return true;
        } else if (len == getTraceMaskCmd) {
            TraceMaskCommand tmask = new TraceMaskCommand(pwriter);
            tmask.start();
            return true;
        } else if (len == setTraceMaskCmd) {
            int rc = sock.read(incomingBuffer);
            if (rc < 0) {
                throw new IOException("Read error");
            }

            incomingBuffer.flip();
            long traceMask = incomingBuffer.getLong();
            ZooTrace.setTextTraceLevel(traceMask);
            SetTraceMaskCommand setMask = new SetTraceMaskCommand(pwriter, traceMask);
            setMask.start();
            return true;
        } else if (len == enviCmd) {
            EnvCommand env = new EnvCommand(pwriter);
            env.start();
            return true;
        } else if (len == confCmd) {
            ConfCommand ccmd = new ConfCommand(pwriter);
            ccmd.start();
            return true;
        } else if (len == srstCmd) {
            StatResetCommand strst = new StatResetCommand(pwriter);
            strst.start();
            return true;
        } else if (len == crstCmd) {
            CnxnStatResetCommand crst = new CnxnStatResetCommand(pwriter);
            crst.start();
            return true;
        } else if (len == dumpCmd) {
            DumpCommand dump = new DumpCommand(pwriter);
            dump.start();
            return true;
        } else if (len == statCmd || len == srvrCmd) {
            StatCommand stat = new StatCommand(pwriter, len);
            stat.start();
            return true;
        } else if (len == consCmd) {
            ConsCommand cons = new ConsCommand(pwriter);
            cons.start();
            return true;
        } else if (len == wchpCmd || len == wchcCmd || len == wchsCmd) {
            WatchCommand wcmd = new WatchCommand(pwriter, len);
            wcmd.start();
            return true;
        }
        return false;
    }

    /** Reads the first 4 bytes of lenBuffer, which could be true length or
     *  four letter word.
     *
     * @param k selection key
     * @return true if length read, otw false (wasn't really the length)
     * @throws IOException if buffer size exceeds maxBuffer size
     */
    private boolean readLength(SelectionKey k) throws IOException {
        // Read the length, now get the buffer
        int len = lenBuffer.getInt();
        if (!initialized && checkFourLetterWord(k, len)) {
            return false;
        }
        if (len < 0 || len > BinaryInputArchive.maxBuffer) {
            throw new IOException("Len error " + len);
        }
        if (zk == null) {
            throw new IOException("ZooKeeperServer not running");
        }
        incomingBuffer = ByteBuffer.allocate(len);
        return true;
    }

    /**
     * The number of requests that have been submitted but not yet responded to.
     */
    int outstandingRequests;

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionTimeout()
     */
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    /**
     * This is the id that uniquely identifies the session of a client. Once
     * this session is no longer active, the ephemeral nodes will go away.
     */
    long sessionId;

    static long nextSessionId = 1;

    public NIOServerCnxn(ZooKeeperServer zk, SocketChannel sock,
            SelectionKey sk, Factory factory) throws IOException {
        this.zk = zk;
        this.sock = sock;
        this.sk = sk;
        this.factory = factory;
        sock.socket().setTcpNoDelay(true);
        sock.socket().setSoLinger(false, -1);
        InetAddress addr = ((InetSocketAddress) sock.socket()
                .getRemoteSocketAddress()).getAddress();
        authInfo.add(new Id("ip", addr.getHostAddress()));
        sk.interestOps(SelectionKey.OP_READ);
    }

    @Override
    public String toString() {
        return "NIOServerCnxn object with sock = " + sock + " and sk = " + sk;
    }

    /*
     * Close the cnxn and remove it from the factory cnxns list.
     * 
     * This function returns immediately if the cnxn is not on the cnxns list.
     */
    public void close() {
        synchronized(factory.cnxns){
            // if this is not in cnxns then it's already closed
            if (!factory.cnxns.remove(this)) {
                return;
            }

            synchronized (factory.ipMap) {
                Set<NIOServerCnxn> s =
                    factory.ipMap.get(sock.socket().getInetAddress());
                s.remove(this);
            }

            // unregister from JMX
            try {
                if(jmxConnectionBean != null){
                    MBeanRegistry.getInstance().unregister(jmxConnectionBean);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister with JMX", e);
            }
            jmxConnectionBean = null;
    
            if (zk != null) {
                zk.removeCnxn(this);
            }
    
            closeSock();
    
            if (sk != null) {
                try {
                    // need to cancel this selection key from the selector
                    sk.cancel();
                } catch (Exception e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ignoring exception during selectionkey cancel", e);
                    }
                }
            }
        }
    }

    /**
     * Close resources associated with the sock of this cnxn. 
     */
    private void closeSock() {
        // 幂等
        if (sock == null) {
            return;
        }

        LOG.info("Closed socket connection for client "
                + sock.socket().getRemoteSocketAddress()
                + (sessionId != 0 ?
                        " which had sessionid 0x" + Long.toHexString(sessionId) :
                        " (no session established for client)"));
        try {
            /*
             * The following sequence of code is stupid! You would think that
             * only sock.close() is needed, but alas, it doesn't work that way.
             * If you just do sock.close() there are cases where the socket
             * doesn't actually close...
             */
            sock.socket().shutdownOutput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during output shutdown", e);
            }
        }
        try {
            sock.socket().shutdownInput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during input shutdown", e);
            }
        }
        try {
            sock.socket().close();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during socket close", e);
            }
        }
        try {
            sock.close();
            // XXX The next line doesn't seem to be needed, but some posts
            // to forums suggest that it is needed. Keep in mind if errors in
            // this section arise.
            // factory.selector.wakeup();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during socketchannel close", e);
            }
        }
        sock = null;
    }
    
    private final static byte fourBytes[] = new byte[4];

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#sendResponse(org.apache.zookeeper.proto.ReplyHeader,
     *      org.apache.jute.Record, java.lang.String)
     */
    synchronized public void sendResponse(ReplyHeader h, Record r, String tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // Make space for length
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            try {
                baos.write(fourBytes);
                bos.writeRecord(h, "header");
                if (r != null) {
                    bos.writeRecord(r, tag);
                }
                baos.close();
            } catch (IOException e) {
                LOG.error("Error serializing response");
            }
            byte b[] = baos.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(b);
            bb.putInt(b.length - 4).rewind();
            sendBuffer(bb);
            if (h.getXid() > 0) {
                synchronized(this){
                    outstandingRequests--;
                }
                // check throttling
                synchronized (this.factory) {        
                    if (zk.getInProcess() < factory.outstandingLimit
                            || outstandingRequests < 1) {
                        sk.selector().wakeup();
                        enableRecv();
                    }
                }
            }
         } catch(Exception e) {
            LOG.warn("Unexpected exception. Destruction averted.", e);
         }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#process(org.apache.zookeeper.proto.WatcherEvent)
     */
    synchronized public void process(WatchedEvent event) {
        ReplyHeader h = new ReplyHeader(-1, -1L, 0);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                                     "Deliver event " + event + " to 0x"
                                     + Long.toHexString(this.sessionId)
                                     + " through " + this);
        }

        // Convert WatchedEvent to a type that can be sent over the wire
        WatcherEvent e = event.getWrapper();

        sendResponse(h, e, "notification");
    }

    public void finishSessionInit(boolean valid) {
        // register with JMX
        try {
            jmxConnectionBean = new ConnectionBean(this, zk);
            MBeanRegistry.getInstance().register(jmxConnectionBean, zk.jmxServerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxConnectionBean = null;
        }

        try {
            ConnectResponse rsp = new ConnectResponse(0, valid ? sessionTimeout
                    : 0, valid ? sessionId : 0, // send 0 if session is no
                    // longer valid
                    valid ? zk.generatePasswd(sessionId) : new byte[16]);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            bos.writeInt(-1, "len");
            rsp.serialize(bos, "connect");
            baos.close();

            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.remaining() - 4).rewind();
            sendBuffer(bb);

            if (!valid) {
                LOG.info("Invalid session 0x"
                        + Long.toHexString(sessionId)
                        + " for client "
                        + sock.socket().getRemoteSocketAddress()
                        + ", probably expired");
                sendCloseSession();
            } else {
                LOG.info("Established session 0x"
                        + Long.toHexString(sessionId)
                        + " with negotiated timeout " + sessionTimeout
                        + " for client "
                        + sock.socket().getRemoteSocketAddress());
            }

            // Now that the session is ready we can start receiving packets
            synchronized (this.factory) {
                sk.selector().wakeup();
                enableRecv();
            }
        } catch (Exception e) {
            LOG.warn("Exception while establishing session, closing", e);
            close();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionId()
     */
    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public ArrayList<Id> getAuthInfo() {
        return authInfo;
    }

    public synchronized InetSocketAddress getRemoteAddress() {
        if (sock == null) {
            return null;
        }
        return (InetSocketAddress) sock.socket().getRemoteSocketAddress();
    }

    class CnxnStats implements ServerCnxn.Stats {
        private final Date established = new Date();

        private final AtomicLong packetsReceived = new AtomicLong();
        private final AtomicLong packetsSent = new AtomicLong();

        private long minLatency;
        private long maxLatency;
        private String lastOp;
        private long lastCxid;
        private long lastZxid;
        private long lastResponseTime;
        private long lastLatency;

        private long count;
        private long totalLatency;

        CnxnStats() {
            reset();
        }

        public synchronized void reset() {
            packetsReceived.set(0);
            packetsSent.set(0);
            minLatency = Long.MAX_VALUE;
            maxLatency = 0;
            lastOp = "NA";
            lastCxid = -1;
            lastZxid = -1;
            lastResponseTime = 0;
            lastLatency = 0;

            count = 0;
            totalLatency = 0;
        }

        long incrPacketsReceived() {
            return packetsReceived.incrementAndGet();
        }

        long incrPacketsSent() {
            return packetsSent.incrementAndGet();
        }

        synchronized void updateForResponse(long cxid, long zxid, String op,
                long start, long end)
        {
            // don't overwrite with "special" xids - we're interested
            // in the clients last real operation
            if (cxid >= 0) {
                lastCxid = cxid;
            }
            lastZxid = zxid;
            lastOp = op;
            lastResponseTime = end;
            long elapsed = end - start;
            lastLatency = elapsed;
            if (elapsed < minLatency) {
                minLatency = elapsed;
            }
            if (elapsed > maxLatency) {
                maxLatency = elapsed;
            }
            count++;
            totalLatency += elapsed;
        }

        public Date getEstablished() {
            return established;
        }

        public long getOutstandingRequests() {
            synchronized (NIOServerCnxn.this) {
                synchronized (NIOServerCnxn.this.factory) {
                    return outstandingRequests;
                }
            }
        }

        public long getPacketsReceived() {
            return packetsReceived.longValue();
        }

        public long getPacketsSent() {
            return packetsSent.longValue();
        }

        public synchronized long getMinLatency() {
            return minLatency == Long.MAX_VALUE ? 0 : minLatency;
        }

        public synchronized long getAvgLatency() {
            return count == 0 ? 0 : totalLatency / count;
        }

        public synchronized long getMaxLatency() {
            return maxLatency;
        }

        public synchronized String getLastOperation() {
            return lastOp;
        }

        public synchronized long getLastCxid() {
            return lastCxid;
        }

        public synchronized long getLastZxid() {
            return lastZxid;
        }

        public synchronized long getLastResponseTime() {
            return lastResponseTime;
        }

        public synchronized long getLastLatency() {
            return lastLatency;
        }

        /**
         * Prints detailed stats information for the connection.
         *
         * @see dumpConnectionInfo(PrintWriter, boolean) for brief stats
         */
        @Override
        public String toString() {
            StringWriter sw = new StringWriter();
            PrintWriter pwriter = new PrintWriter(sw);
            dumpConnectionInfo(pwriter, false);
            pwriter.flush();
            pwriter.close();
            return sw.toString();
        }

        /**
         * Print information about the connection.
         * @param brief iff true prints brief details, otw full detail
         * @return information about this connection
         */
        public synchronized void
        dumpConnectionInfo(PrintWriter pwriter, boolean brief)
        {
            Channel channel = sk.channel();
            if (channel instanceof SocketChannel) {
                pwriter.print(" ");
                pwriter.print(((SocketChannel)channel).socket()
                        .getRemoteSocketAddress());
                pwriter.print("[");
                pwriter.print(sk.isValid() ? Integer.toHexString(sk.interestOps())
                        : "0");
                pwriter.print("](queued=");
                pwriter.print(getOutstandingRequests());
                pwriter.print(",recved=");
                pwriter.print(getPacketsReceived());
                pwriter.print(",sent=");
                pwriter.print(getPacketsSent());

                if (!brief) {
                    long sessionId = getSessionId();
                    if (sessionId != 0) {
                        pwriter.print(",sid=0x");
                        pwriter.print(Long.toHexString(sessionId));
                        pwriter.print(",lop=");
                        pwriter.print(getLastOperation());
                        pwriter.print(",est=");
                        pwriter.print(getEstablished().getTime());
                        pwriter.print(",to=");
                        pwriter.print(getSessionTimeout());
                        long lastCxid = getLastCxid();
                        if (lastCxid >= 0) {
                            pwriter.print(",lcxid=0x");
                            pwriter.print(Long.toHexString(lastCxid));
                        }
                        pwriter.print(",lzxid=0x");
                        pwriter.print(Long.toHexString(getLastZxid()));
                        pwriter.print(",lresp=");
                        pwriter.print(getLastResponseTime());
                        pwriter.print(",llat=");
                        pwriter.print(getLastLatency());
                        pwriter.print(",minlat=");
                        pwriter.print(getMinLatency());
                        pwriter.print(",avglat=");
                        pwriter.print(getAvgLatency());
                        pwriter.print(",maxlat=");
                        pwriter.print(getMaxLatency());
                    }
                }
                pwriter.println(")");
            }
        }
    }

    private final CnxnStats stats = new CnxnStats();
    public Stats getStats() {
        return stats;
    }
}
