# 基于 Java Socket 的局域网聊天系统

“Java 语言及网络编程”课设，使用 JDK 17、Swing、Socket、线程和本地文件。服务端采用控制台，多个客户端共用公共聊天室，并支持在线私聊。

已实现昵称加入、群聊、私聊、在线列表、上下线提示、消息时间、会话未读、本地历史、断线提示与手动重连。指定 IP 的最终通信验收仍待网络配置；开发测试使用显式回环地址。

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
.\scripts\start-client.cmd demo-a --host 127.0.0.1
```

```powershell
# 客户端乙
.\scripts\start-client.cmd demo-b --host 127.0.0.1
```

在窗口填写不同昵称并点击“连接”。每个客户端使用独立 `profile`；同时运行同一个 `profile` 会被文件锁拒绝。需要第三个客户端时使用 `demo-c`。

窗口显示实际 Socket 连接端点，并标注“开发回环自测”。这组命令用于本机开发验证，最终指定 IP 验收见 [IP 使用约定](docs/07-IP使用约定.md)。

## 指定 IP 运行

两个配置文件默认均为 `172.16.1.10:5000`。地址配置到本机接口后，可执行：

```powershell
.\scripts\start-server.cmd
.\scripts\start-client.cmd course-a
.\scripts\start-client.cmd course-b
```

地址未配置或端口被占用时会明确报错。当前未修改网卡、防火墙、路由、hosts 或系统环境。

也可在项目根目录直接使用 JAR：

```powershell
& 'C:\Program Files\Java\jdk-17.0.11-full\bin\java.exe' '-Dfile.encoding=UTF-8' -jar .\dist\chat.jar server --config config/server.properties --host 127.0.0.1
& 'C:\Program Files\Java\jdk-17.0.11-full\bin\java.exe' '-Dfile.encoding=UTF-8' -jar .\dist\chat.jar client --config config/client.properties --profile demo-a --host 127.0.0.1
```

支持 `--config`、`--host`、`--port`，客户端另支持 `--profile`。命令参数优先于 UTF-8 Properties 配置；未知键、重复参数和非法配置会报错。应用根目录由 JAR 或编译目录确定，相对配置和资料路径不随终端当前目录改变。

## 窗口操作与资料

- 公共聊天室中的消息由服务端回显，发送者只显示一次。双击在线用户打开私聊；离线私聊仍可查看，发送按钮禁用。
- Enter 发送，Shift+Enter 换行，也可点击“发送”。非当前会话的来信增加未读，进入会话后清零。
- 连接中可取消，已连接可主动断开；断线后点击“连接”手动重连，输入草稿继续保留。
- 消息收到服务端回显后显示“服务端已接受”。尚无回显的消息单独显示，断开后标记“未确认，可能未送达”，重连不会自动发送这些文本。
- 本地资料位于 `data/client/<profile>/`，包含 `profile.properties`、`profile.lock` 和 `history.bin`。资料首次成功加入后绑定服务端地址、端口及昵称；需要更换时使用其他 `profile`。
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

验收结果和证据位置见 [验收与开发计划](docs/03-验收与开发计划.md)，连续开发记录见 [开发记录](docs/development/records/局域网聊天-实现群聊私聊与本地历史.md)。指定 IP 通信和中文输入法候选确认的实际结果分别记录，自动组件测试不代替这些检查。

`.gitignore` 保留原有内容，构建产物、聊天资料和临时数据均不提交。`docs/` 延续用户原有忽略规则，本地文档和证据仍保存在工作区；向教师交付工程时应一并包含。教师原文件、正式 Word 报告与 PPT 本轮未修改。
