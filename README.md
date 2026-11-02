# 基于 Java Socket 的局域网聊天系统

“Java 语言及网络编程”课设，使用 JDK 17、Swing、Socket、线程和本地文件。服务端采用控制台，多个客户端共用公共聊天室，并支持在线私聊。

已实现昵称加入、群聊、私聊、在线列表、上下线提示、消息时间、会话未读、本地历史、断线提示与手动重连。本机使用同一 Microsoft KM-TEST 环回适配器上的多个地址，分别运行 Java 服务端与多个客户端进程。2026-11-02 已通过只读查询确认 `172.16.1.10`～`172.16.1.20` 已配置为 `/24`；指定 IP 通信验收结果见 [验收与开发计划](docs/03-验收与开发计划.md)。

## 环境与构建

本机实际验证环境：Windows 11，OpenJDK `17.0.11+12-LTS`，`javac 17.0.11`。本机 JDK 目录为 `C:\Program Files\Java\jdk-17.0.11-full`。

在项目根目录的 PowerShell 执行：

```powershell
.\scripts\build.cmd
.\scripts\test.cmd
```

构建脚本调用 `javac --release 17 -encoding UTF-8` 和 `jar`，生成 `dist/chat.jar`。无需 IDE 或第三方库。脚本依次检查当前进程的 `JAVA_HOME`、PATH 和本机上述 JDK 路径，选择完整的 JDK 17；不会修改系统环境。其他电脑可在当前终端设置 `JAVA_HOME` 指向自己的 JDK 17。

源码修改后重新执行构建。启动脚本会复用已有 JAR，缺少 JAR 时构建。

## 开发回环运行

分别打开三个 PowerShell 终端：

```powershell
# 服务端
.\scripts\start-server.cmd --host 127.0.0.1
```

```powershell
# 客户端甲
.\scripts\start-client.cmd demo-a --local-host 127.0.0.1 --host 127.0.0.1
```

```powershell
# 客户端乙
.\scripts\start-client.cmd demo-b --local-host 127.0.0.1 --host 127.0.0.1
```

在窗口填写不同昵称并点击“连接”。每个客户端使用独立 `profile`；同时运行同一个 `profile` 会被文件锁拒绝。需要第三个客户端时使用 `demo-c`。

窗口显示实际 Socket 连接端点，并标注“开发回环自测”。这组命令用于本机开发验证，最终指定 IP 验收见 [IP 使用约定](docs/07-IP使用约定.md)。

## 指定 IP 运行

服务端默认监听 `172.16.1.10:5000`。客户端默认本地 IP 为 `172.16.1.11`，目标服务器为 `172.16.1.10:5000`；本地端口使用 `0`，由操作系统分配。演示时从已配置的 `172.16.1.11`～`172.16.1.20` 中选择客户端本地 IP。分别在三个 PowerShell 终端执行以下三条命令：

```powershell
.\scripts\start-server.cmd
.\scripts\start-client.cmd ip-demo-a --local-host 172.16.1.11
.\scripts\start-client.cmd ip-demo-b --local-host 172.16.1.12
```

在两个窗口分别填写不同昵称并连接。连接栏分别显示“客户端本地 IP”“服务端 IP”“服务端端口”；实际连接端点显示本地地址及系统分配的端口、目标服务器地址和端口。多个客户端也可以使用相同本地 IP，由不同本地端口区分连接，仍须使用不同昵称和 `profile`。

每次连接及重连均创建新的 Socket，显式绑定所选本地 IP 和端口 `0` 后连接服务器。绑定或连接失败会显示具体原因，程序不会自动切换地址。程序使用 IP 地址选择本地端点，不依赖网卡名称。系统网络配置由用户管理；本轮程序开发不修改网卡、路由、DNS、防火墙、hosts 或系统环境。

也可在项目根目录直接使用 JAR：

```powershell
& 'C:\Program Files\Java\jdk-17.0.11-full\bin\java.exe' '-Dfile.encoding=UTF-8' -jar .\dist\chat.jar server --config config/server.properties --host 127.0.0.1
& 'C:\Program Files\Java\jdk-17.0.11-full\bin\java.exe' '-Dfile.encoding=UTF-8' -jar .\dist\chat.jar client --config config/client.properties --profile demo-a --local-host 127.0.0.1 --host 127.0.0.1
```

支持 `--config`、`--host`、`--port`，客户端另支持 `--profile`、`--local-host`。客户端配置文件使用 `localHost=172.16.1.11`、`host=172.16.1.10`、`port=5000`；`--host` 和 `--port` 始终表示目标服务器。命令参数优先于 UTF-8 Properties 配置；未知键、重复参数和非法配置会报错。应用根目录由 JAR 或编译目录确定，相对配置和资料路径不随终端当前目录改变。

## 窗口操作与资料

- 公共聊天室中的消息由服务端回显，发送者只显示一次。双击在线用户打开私聊；离线私聊仍可查看，发送按钮禁用。
- Enter 发送，Shift+Enter 换行，也可点击“发送”。非当前会话的来信增加未读，进入会话后清零。
- 连接中可取消，已连接可主动断开；断线后点击“连接”手动重连，输入草稿继续保留。
- 消息收到服务端回显后显示“服务端已接受”。尚无回显的消息单独显示，断开后标记“未确认，可能未送达”，重连不会自动发送这些文本。
- 本地资料位于 `data/client/<profile>/`，包含 `profile.properties`、`profile.lock` 和 `history.bin`。资料首次成功加入后绑定服务器地址、服务器端口及昵称；需要更换这些信息时使用其他 `profile`。客户端本地 IP 可在断开后修改，继续使用原 `profile`。
- 历史记录使用“历史记录”标记，每个会话显示最近 500 条。历史不会重新发送或增加未读；上下线通知不保存。
- 每份历史上限 10 MiB，保存队列上限 256。容量不足、文件损坏或写入失败会醒目显示“记录保存已暂停”，已存在的文件会保留，聊天仍可继续。
- 点击窗口关闭按钮会关闭连接、等待已接受的保存任务并释放资料锁；服务端使用 Ctrl+C 退出。

本软件用于可信课程实验。昵称为在线会话标签；私聊接受回显表示服务端已安排路由，不证明接收者已读或已保存。历史提供正常关闭后重启查看，不保证突然断电时的数据完整性。

## 源码与验证

```text
src/main/java/cn/edu/course/chat/
  Main.java             启动入口
  common/               帧编解码、输入与配置校验
  server/               监听、会话、路由与资源限制
  client/               网络连接、Swing、会话与历史
src/test/java/cn/edu/course/chat/
config/                 UTF-8 配置
scripts/                构建、测试和启动入口
build/                  编译产物
dist/chat.jar           可执行 JAR
data/client/            运行时本地资料
```

`test.cmd` 执行纯 Java 主方法测试，覆盖协议、真实 Socket 路由、连接故障、历史文件与配置。图形环境另可运行真实窗口测试：

```powershell
& 'C:\Program Files\Java\jdk-17.0.11-full\bin\java.exe' -ea '-Dfile.encoding=UTF-8' -cp 'build/main;build/test' cn.edu.course.chat.SwingTest
```

指定 IP 的独立多进程验收入口如下，需要真实桌面、已配置的 `.10`、`.11`、`.12`、未配置的 `172.16.1.254`，且服务端端口 `5000` 空闲。测试自行启动一个 JAR 服务端和两个 Swing 客户端进程，检查通信、绑定失败及重连，并关闭本次创建的进程。该入口独立运行，未加入常规 `test.cmd`：

```powershell
& 'C:\Program Files\Java\jdk-17.0.11-full\bin\java.exe' -ea '-Dfile.encoding=UTF-8' -cp 'build/main;build/test' cn.edu.course.chat.IpAcceptanceTest
```

2026-11-02 已实际通过两个指定来源 IP 的单机多进程验收：监听与连接端点、群聊、私聊、在线列表、上下线提示、手动重连和未配置地址绑定失败均已检查。结果及原始日志、截图见 [验收与开发计划第 6 节](docs/03-验收与开发计划.md#6-2026-11-02-指定-ip-多进程验收)，连续开发记录见 [开发记录](docs/development/records/局域网聊天-实现群聊私聊与本地历史.md)。实际中文输入法候选确认仍未执行。准备十个客户端地址，仅本次两个客户端参与了指定 IP 通信验收。

`.gitignore` 保留原有内容，构建产物、聊天资料和临时数据均不提交。`docs/` 延续用户原有忽略规则，本地文档和证据仍保存在工作区；向教师交付工程时应一并包含。教师原文件、正式 Word 报告与 PPT 本轮未修改。
