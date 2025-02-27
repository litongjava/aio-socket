# aio-socket
## 出处
本项目代码出自[smart-socket](https://github.com/smartboot/smart-socket/tree/master/aio-core/src/main/java/org/smartboot/socket)
## aio-socket 简介

**aio-socket** 是一款基于 Java 的异步、非阻塞高性能 IO 库，其设计理念类似于 JDK7 提供的 AIO，但在性能和资源利用上做了优化。该库采用高效的内存管理策略和自定义的线程模型，能够在 1C1G 的硬件条件下支撑上万并发连接。主要特点包括：

- **异步非阻塞**：通过注册事件（如读、写、连接、接收连接）与回调处理，实现 IO 事件的异步处理，避免线程阻塞。
- **高性能内存管理**：使用内存页池（BufferPagePool）管理物理内存，通过虚拟内存（VirtualBuffer）的方式分割和复用 ByteBuffer，从而减少内存分配和复制的开销。
- **灵活的线程模型**：内部通过多个 Worker 线程（包括读、写、通用处理线程）以及调度任务实现高并发的 IO 处理。
- **接口与实现分离**：通过定义接口（如 BufferPage）和抽象类（如 AbstractBufferPage），实现了弹性内存页（ElasticBufferPage）和静态内存页（StaticBufferPage）的不同策略，满足不同场景下的需求。

---

## 代码模块介绍

下面分别介绍各个主要模块的设计与实现，并补充关键方法和参数的文档说明。

### 1. 内存管理模块

#### AbstractBufferPage

- **作用**：作为内存页的抽象基类，定义了基本的内存清理和释放接口，同时维护一个表示内存页是否空闲的标识。
- **关键方法**：
  - `clean(VirtualBuffer cleanBuffer)`  
    清理（回收）指定的虚拟内存块。子类需要根据内存类型（直接内存或堆内存）实现具体的清理操作。
  - `tryClean()`  
    尝试触发缓冲区的回收任务。当内存页长时间处于空闲状态时，会自动回收未使用的虚拟内存。
  - `release()`  
    释放内存页所占的物理内存，通常在内存页不再使用时调用。

#### BufferPage

- **作用**：定义了内存页对外提供的接口，主要用于申请虚拟内存。
- **关键方法**：
  - `allocate(int size)`  
    申请指定大小的虚拟内存块。返回的 `VirtualBuffer` 是对真实 ByteBuffer 的一个切片映射，用于业务数据的读写。

#### BufferPagePool

- **作用**：管理多个内存页，采用轮训方式均衡分配内存页；同时内部调度定时任务，定期调用各内存页的 `tryClean()` 方法以实现内存回收。
- **构造方法参数**：
  - `pageSize`：内存页的大小。如果为 0，则使用弹性内存页（ElasticBufferPage）。
  - `pageNum`：内存页的个数。
  - `isDirect`：是否使用直接缓冲区（off-heap）。
- **关键方法**：
  - `allocateBufferPage()`  
    从内存页池中按轮询策略返回一个可用的内存页。
  - `release()`  
    关闭内存池，停止调度回收任务，并释放所有内存页占用的资源。

#### ElasticBufferPage

- **作用**：基于弹性分配策略实现的内存页。当申请的虚拟内存大小不匹配时，会直接回收未匹配的缓冲区并创建新的虚拟内存块。
- **内存回收策略**：
  - 利用一个并发队列 `cleanBuffers` 保存待回收的虚拟内存，当 `tryClean()` 被触发且内存页连续处于空闲状态时，会循环回收一定数量的虚拟内存。
  - 当使用直接内存时，通过调用 `((DirectBuffer) buffer).cleaner().clean()` 释放物理内存。

#### StaticBufferPage

- **作用**：基于静态内存页实现，先一次性申请一块固定大小的物理 ByteBuffer，然后通过切分（slice）来生成多个虚拟内存块（VirtualBuffer）。
- **内存分配策略**：
  - 内部维护一个 `availableBuffers` 列表，记录当前可分配的内存块。当申请内存时，采用“快速匹配”和“迭代申请”两种策略查找合适的内存块。
- **内存合并**：
  - 当虚拟内存被释放时（调用 `clean(VirtualBuffer)`），通过 `clean0()` 方法尝试与相邻的空闲块合并，以便后续大块内存的分配。
  
#### VirtualBuffer

- **作用**：表示由物理 ByteBuffer 切分出的虚拟缓冲区，封装了内存页中某一段数据的读写边界（parentPosition 与 parentLimit）以及实际的 ByteBuffer 对象。
- **关键字段与方法**：
  - `getCapacity()`  
    返回该虚拟缓冲区的容量，即 parentLimit - parentPosition。
  - `buffer()` 与 `buffer(ByteBuffer)`  
    分别用于获取和设置真实的 ByteBuffer 引用。
  - `clean()`  
    释放该虚拟缓冲区，内部调用所属内存页的 `clean()` 方法实现回收。

---

### 2. 异步 IO 模块

#### EnhanceAsynchronousChannelGroup

- **作用**：扩展了 JDK 的 `AsynchronousChannelGroup`，实现了异步 IO 事件的调度和分发。内部创建多个 Worker 线程，分别处理读、写、连接和接收等事件。
- **关键成员**：
  - `readExecutorService` 与 `commonExecutorService`：分别用于读和通用（写、连接、accept）任务的线程池。
  - `readWorkers`、`writeWorker`、`commonWorker`：基于 `Worker` 内部类实现，均绑定各自的 Selector 以监听对应的 IO 事件。
- **关键方法**：
  - `shutdown()` 与 `shutdownNow()`  
    停止异步通道组的所有任务，并关闭所有 Selector 及线程资源。
  - `interestOps()` 与 `removeOps()`  
    辅助方法用于动态修改 SelectionKey 的兴趣操作，确保线程在不同 Worker 中协同工作。

#### EnhanceAsynchronousChannelProvider

- **作用**：继承自 `AsynchronousChannelProvider`，提供 aio-socket 实现的异步通道。通过自定义的通道组（EnhanceAsynchronousChannelGroup）创建服务端和客户端的异步通道。
- **关键方法**：
  - `openAsynchronousChannelGroup(...)`  
    根据传入的线程数或 ExecutorService 创建异步通道组。
  - `openAsynchronousServerSocketChannel(...)` 与 `openAsynchronousSocketChannel(...)`  
    分别创建服务端和客户端的异步通道实例。

#### EnhanceAsynchronousClientChannel

- **作用**：实现异步客户端通道，支持连接、读、写操作。通过调用底层 `SocketChannel` 的非阻塞操作实现异步模式。
- **连接操作**：
  - `connect(SocketAddress, A, CompletionHandler<Void, ? super A>)`  
    异步连接远程地址，内部先尝试直接连接，如果无法立即完成，则通过 Worker 注册 OP_CONNECT 事件，等待完成后回调。
  - 同时支持 Future 模式的 `connect(SocketAddress)`。

#### EnhanceAsynchronousServerChannel

- **作用**：实现异步服务端通道，对应于每个已建立连接的 SocketChannel，支持非阻塞的读写操作。
- **读写操作**：
  - `read(ByteBuffer, ..., CompletionHandler<Integer, ? super A>)`  
    异步读取数据。内部判断是否需要直接调用 `channel.read()` 或注册 OP_READ 事件，必要时通过 FutureCompletionHandler 处理取消和超时情况。
  - `write(ByteBuffer, ..., CompletionHandler<Integer, ? super A>)`  
    异步写入数据。写操作中采用轮询写入，如果一次写入未完成，则注册 OP_WRITE 事件等待下一次写操作。

#### EnhanceAsynchronousServerSocketChannel

- **作用**：实现异步服务端 Socket 通道，用于接收客户端连接请求。提供基于回调或 Future 模式的 accept 操作。
- **接收连接**：
  - `accept(A, CompletionHandler<AsynchronousSocketChannel, ? super A>)`  
    异步接收客户端连接。当有新的连接到达时，会创建一个新的 EnhanceAsynchronousServerChannel 实例，并调用回调通知应用层。
  - 内部通过 Selector 监听 OP_ACCEPT 事件，实现非阻塞的接收连接操作。

#### FutureCompletionHandler

- **作用**：实现了 `CompletionHandler` 和 `Future` 接口的组合，用于同时支持回调模式和 Future 模式。  
- **关键特点**：
  - 在操作完成后通过 `completed()` 或 `failed()` 设置结果或异常，并唤醒等待线程。
  - 通过 `get()` 方法可以等待异步操作完成，并获取操作结果。

---

## 使用示例

假设你需要构建一个高并发的服务端，可以这样使用 aio-socket：

1. **创建异步通道组**  
   使用 `EnhanceAsynchronousChannelProvider` 创建一个异步通道组：
   ```java
   AsynchronousChannelGroup group = new EnhanceAsynchronousChannelProvider(false)
       .openAsynchronousChannelGroup(4, Executors.defaultThreadFactory());
   ```

2. **启动服务端 Socket**  
   创建并绑定服务端 Socket 通道：
   ```java
   AsynchronousServerSocketChannel serverChannel = new EnhanceAsynchronousChannelProvider(false)
       .openAsynchronousServerSocketChannel(group)
       .bind(new InetSocketAddress(8080));
   ```

3. **接收连接**  
   调用 `accept()` 方法，注册回调：
   ```java
   serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
       @Override
       public void completed(AsynchronousSocketChannel result, Object attachment) {
           // 处理新连接
           System.out.println("New connection accepted: " + result);
           // 继续接受后续连接
           serverChannel.accept(null, this);
       }
       @Override
       public void failed(Throwable exc, Object attachment) {
           exc.printStackTrace();
       }
   });
   ```
3. **ElasticBufferPage**  
ElasticBufferPage 由 BufferPagePool 在 pageSize 为 0 时自动创建，旨在支持动态大小的内存分配，而不是预先固定大小的内存页。如果你希望使用 ElasticBufferPage，可以通过如下方式：

1). **创建内存池**  
   当你构造 BufferPagePool 时，将 pageSize 参数设置为 0，这样内部会实例化 ElasticBufferPage。例如：
   ```java
   // 使用 pageSize 为 0 创建内存池，pageNum 表示内存页个数，true 表示使用直接内存
   BufferPagePool pool = new BufferPagePool(0, 10, true);
   ```

2). **申请内存页与虚拟内存**  
   通过内存池申请一个内存页，然后调用其 allocate 方法申请所需大小的虚拟内存（VirtualBuffer）：
   ```java
   BufferPage bufferPage = pool.allocateBufferPage();
   // 申请 1024 字节的虚拟内存
   VirtualBuffer virtualBuffer = bufferPage.allocate(8192);
   ```

3). **内存回收**  
   当你不再使用该虚拟内存时，应调用 VirtualBuffer.clean() 方法，将其归还给 ElasticBufferPage。内部会将该虚拟内存放入回收队列，等待下一次 tryClean() 进行回收：
   ```java
   virtualBuffer.clean();
   ```

这样，ElasticBufferPage 就会自动管理虚拟内存的分配与回收，帮助你减少频繁内存分配带来的性能开销。通常，业务代码无需直接操作 ElasticBufferPage，而是通过 BufferPagePool 获取 BufferPage，然后使用其中的 allocate/clean 接口来进行内存管理。
---


## 使用示例

### 添加依赖
```java
<properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <java.version>1.8</java.version>
    <maven.compiler.source>${java.version}</maven.compiler.source>
    <maven.compiler.target>${java.version}</maven.compiler.target>
    <main.class>HttpServer</main.class>
  </properties>
  <dependencies>
    <dependency>
      <groupId>com.litongjava</groupId>
      <artifactId>aio-socket</artifactId>
      <version>1.0.0</version>
    </dependency>
  </dependencies>
  <profiles>
    <!-- 开发环境配置 -->
    <profile>
      <id>development</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <build>
        <plugins>
          <!-- Spring Boot Maven 插件 -->
          <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <version>2.7.4</version>
            <configuration>
              <fork>true</fork>
              <mainClass>${main.class}</mainClass>
              <excludeGroupIds>org.projectlombok</excludeGroupIds>
              <arguments>
                <argument>--mode=dev</argument>
              </arguments>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>

    <!-- 生产环境配置 -->
    <profile>
      <id>production</id>
      <build>
        <plugins>
          <!-- Spring Boot Maven 插件 -->
          <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <version>2.7.4</version>
            <configuration>
              <mainClass>${main.class}</mainClass>
              <excludeGroupIds>org.projectlombok</excludeGroupIds>
            </configuration>
            <executions>
              <execution>
                <goals>
                  <goal>repackage</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```
### 编写代码
```java
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ThreadFactory;

import com.litongjava.enhance.buffer.BufferPage;
import com.litongjava.enhance.buffer.BufferPagePool;
import com.litongjava.enhance.buffer.VirtualBuffer;
import com.litongjava.enhance.channel.EnhanceAsynchronousChannelProvider;
import com.litongjava.enhance.channel.EnhanceAsynchronousServerSocketChannel;

public class HttpServer {

  private static int cpuNum = Runtime.getRuntime().availableProcessors();
  private static BufferPagePool pool = new BufferPagePool(0, 1024 * cpuNum, true);
  private static BufferPage bufferPage = pool.allocateBufferPage();

  public static void main(String[] args) throws Exception {

    // 创建通道提供者，false 表示非低内存模式
    EnhanceAsynchronousChannelProvider provider = new EnhanceAsynchronousChannelProvider(false);

    // 创建一个异步通道组，线程数设为2（根据需求调整）
    AsynchronousChannelGroup group = provider.openAsynchronousChannelGroup(2, new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        return new Thread(r, "http-server-thread");
      }
    });

    // 使用提供者创建服务器通道
    EnhanceAsynchronousServerSocketChannel server = (EnhanceAsynchronousServerSocketChannel) provider.openAsynchronousServerSocketChannel(group);
    //
    // 绑定端口，例如 8080，设置 backlog 为 100
    server.bind(new InetSocketAddress(8080), 100);

    System.out.println("HTTP Server 正在监听端口 8080 ...");

    // 异步接受连接请求
    server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
      @Override
      public void completed(AsynchronousSocketChannel channel, Object attachment) {
        // 接收到连接后，立即继续接受下一个连接
        server.accept(null, this);
        // 处理客户端连接
        handleClient(channel);
      }

      @Override
      public void failed(Throwable exc, Object attachment) {
        exc.printStackTrace();
      }
    });

    // 主线程阻塞，以保证服务器运行
    Thread.currentThread().join();
  }

  private static void handleClient(AsynchronousSocketChannel channel) {
    // 通过 BufferPage 池化获取一个 VirtualBuffer，分配 8192 字节空间
    VirtualBuffer virtualBuffer = bufferPage.allocate(8192);
    ByteBuffer buffer = virtualBuffer.buffer();

    // 异步读取客户端请求，将 VirtualBuffer 作为附件传入
    channel.read(buffer, virtualBuffer, new CompletionHandler<Integer, VirtualBuffer>() {
      @Override
      public void completed(Integer result, VirtualBuffer attachment) {
        try {
          if (result > 0) {
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            // 此处可以处理客户端请求数据 bytes

            // 构造简单的 HTTP 响应
            String httpResponse = "HTTP/1.1 200 OK\r\n" + "Content-Length: 13\r\n" + "Content-Type: text/plain\r\n" + "\r\n" + "Hello, World!";
            ByteBuffer responseBuffer = ByteBuffer.wrap(httpResponse.getBytes());

            // 异步写响应
            channel.write(responseBuffer, attachment, new CompletionHandler<Integer, VirtualBuffer>() {
              @Override
              public void completed(Integer result, VirtualBuffer attachment) {
                try {
                  channel.close();
                } catch (IOException e) {
                  e.printStackTrace();
                } finally {
                  // 写完响应后归还虚拟缓冲区
                  attachment.clean();
                }
              }

              @Override
              public void failed(Throwable exc, VirtualBuffer attachment) {
                exc.printStackTrace();
                try {
                  channel.close();
                } catch (IOException e) {
                  e.printStackTrace();
                } finally {
                  // 出现写异常时也归还虚拟缓冲区
                  attachment.clean();
                }
              }
            });
          } else {
            // 未读到数据，则关闭连接
            try {
              channel.close();
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
        } finally {
          // 不论读取成功与否，归还虚拟缓冲区
          attachment.clean();
        }
      }

      @Override
      public void failed(Throwable exc, VirtualBuffer attachment) {
        exc.printStackTrace();
        try {
          channel.close();
        } catch (IOException e) {
          e.printStackTrace();
        } finally {
          // 出现读异常时归还虚拟缓冲区
          attachment.clean();
        }
      }
    });
  }
}

```
## 并发测试
```
[root@ip-172-31-4-97 ~]# ab -c1000 -n1000000 http://localhost:8080/
```
测试报告
```
This is ApacheBench, Version 2.3 <$Revision: 1913912 $>
Copyright 1996 Adam Twiss, Zeus Technology Ltd, http://www.zeustech.net/
Licensed to The Apache Software Foundation, http://www.apache.org/

Benchmarking localhost (be patient)
Completed 100000 requests
Completed 200000 requests
Completed 300000 requests
Completed 400000 requests
Completed 500000 requests
Completed 600000 requests
Completed 700000 requests
Completed 800000 requests
Completed 900000 requests
Completed 1000000 requests
Finished 1000000 requests


Server Software:        
Server Hostname:        localhost
Server Port:            8080

Document Path:          /
Document Length:        13 bytes

Concurrency Level:      1000
Time taken for tests:   96.688 seconds
Complete requests:      1000000
Failed requests:        0
Total transferred:      78000000 bytes
HTML transferred:       13000000 bytes
Requests per second:    10342.50 [#/sec] (mean)
Time per request:       96.688 [ms] (mean)
Time per request:       0.097 [ms] (mean, across all concurrent requests)
Transfer rate:          787.81 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        1   50  80.8     44    1143
Processing:     2   46   9.3     46     450
Waiting:        0   31  10.9     31     446
Total:          9   97  82.3     94    1528

Percentage of the requests served within a certain time (ms)
  50%     94
  66%     96
  75%     97
  80%     97
  90%     99
  95%    100
  98%    108
  99%    124
 100%   1528 (longest request)
```