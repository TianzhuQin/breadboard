# Breadboard 中文快速上手

这份指南带你走一遍：安装、建实验、开场、招募、导出数据。界面上的按钮、文件名、命令和设置都保留英文原样，方便你在软件里找到。文中链接的英文文档写得更细。

## Breadboard 是什么

Breadboard 用来做网络实验。参与者彼此相连，每个人能看到和自己相连的人，并做出选择；实验允许的话，还能加上或切断连接。研究者在网页控制台（console）里搭建实验、开场、观察网络、下载数据。也可以打开自动运行（Autopilot），没人值守时实验一场接一场地自己跑。

## 安装 Java 8 并启动

1. 安装 Java 8 的 JDK，例如 [Azul Zulu 8](https://www.azul.com/downloads/?version=java-8-lts&package=jdk) 或 [Eclipse Temurin 8](https://adoptium.net/temurin/releases/?version=8)。**只能用 Java 8**，Java 9 及更新的版本启动不了。电脑上已经有别的 Java 也没关系：把环境变量 `JAVA8_HOME` 设为 Java 8 的安装文件夹，启动脚本就会用它。
2. 从 [releases 页面](https://github.com/TianzhuQin/breadboard/releases) 下载 `breadboard-<version>.zip`，解压。
3. macOS 和 Linux：在终端里进入解压后的文件夹，运行 `./breadboard.sh`。Windows：双击 `breadboard.bat`。

```bash
cd breadboard-v3.0.0
./breadboard.sh                        # Windows: breadboard.bat
JAVA8_HOME=/opt/zulu8 ./breadboard.sh  # 指定 Java 8 的位置
```

4. 在浏览器打开 <http://localhost:9000>。第一次会看到 **Welcome to Breadboard**：填 **Email**、**Password**（至少 8 位，输两遍）和 **Default language**（新内容默认用的语言），点 **Create account**。以后用这个账号登录。
5. 停止服务器：在它的窗口里按 Ctrl+C。

更多见 [install.md](install.md)。

## 建一个实验

控制台的大多数页面都针对一个实验：侧栏顶部选中的那个。点它可以切换实验，也可以新建或导入。

- **新建或导入**：在 **Experiments** 页点 **New experiment**，从 **Breadboard's template**（带 InitStep、OnJoinStep、OnLeaveStep 三个步骤和标准的参与者页面）开始，或复制一个已有的实验。Breadboard 导出的 .zip（比如别人给你的）用 **Import** 导入，它会成为一个新实验，不会改动你已有的东西。**Export .zip** 可以把实验导出，用来备份或搬到另一台服务器。
- **Steps**：实验的程序，用 Groovy 写。**Save**（Ctrl+S）只是保存。要让引擎用上新代码，点 **Reload engine**；新建一场时引擎也会重新加载。注意：重新加载会让网络里的所有人断开。
- **Content**：参与者读到的文字，比如说明和知情同意书。每段文字有一个名字，程序里用 `c.get("名字")` 显示。每种语言各写一份。
- **Participant page**：参与者页面的 HTML、JavaScript 和 CSS。新实验已经带了一个现成的页面。
- **Preview**（页面标题是 Participant preview）：开场后，在这里按选中参与者的视角看页面，可选 **Desktop** 或 **Phone**。**预览是真的**：在预览里点一个选项，就等于那位参与者做了这个选择，会记进数据。

实验语言（步骤、`g`、`a`、内容）的写法见 [原版 wiki](https://github.com/human-nature-lab/breadboard/wiki)。各页面的说明见 [console.md](console.md#building-an-experiment)。

## 开一场实验并邀请参与者

"一场"（session）就是实验的一次运行。

1. 在 **Live session** 页点 **New session**，填 **Session name**，检查 **Launch parameters**（已填好默认值），点 **Create session**。这一场马上开放，参与者可以用链接加入。
2. 把 **Links for participants** 里的链接发给参与者。**This session** 只在这一场运行时有效。**Whichever session is open** 总是指向这个实验当前开放、还没开始的那一场，可以一直用同一个。**Prolific study URL** 给 Prolific 用。参与者打开链接，在 **Your ID** 里输入一个 ID，点 **Continue**。
3. 链接用的是你打开控制台时的网址。用 localhost 打开时，链接只在你自己的电脑上能用。放到服务器上以后，要用服务器的公开网址打开控制台，再复制链接。
4. 人到齐后点 **Start the game**。等待的人一起进入，实验开始。之后再来的人会被拒绝（除非在 `conf/application-prod.conf` 里设了 `breadboard.allowLateJoin=true`）。
5. 在 **Network** 页实时看网络。选 **Tie**（快捷键 C），先点一个人再点另一个人，就加了一条连接；选 **Cut**（X）再点一条连接，就把它切断。成功后会出现 **Undo**，Ctrl+Z 也能撤销。每次修改都记成一条 `edgeEdit` 事件，操作者是 `admin`。
6. **Stop session** 之后，没有新人能加入。

更多见 [console.md](console.md#running-a-session)。

## 让实验自动运行

**Autopilot** 页让实验在没人值守时自己运行：Breadboard 自动开一场，参与者从一个固定链接进来，人数够了就自动开始，一场结束后再开下一场。设置保存在数据库里，**Save**（Ctrl+S）后立刻生效。主要设置：

| 设置 | 默认 | 作用 |
|---|---|---|
| **Run this experiment unattended** | 关 | 总开关。在 **Experiment** 里选要运行的实验。 |
| **Open the next session when one finishes** | 开 | 一场结束（或被取消）后，过 **Pause before the next session**（10 秒）再开下一场。 |
| **Start the game automatically when enough people are here** | 开 | 等待室：先到的人看到等待页面。 |
| **Minimum players** / **Maximum players** | 2 / 不限 | 到了最少人数就可以开始；到了最多人数，马上开始倒计时。 |
| **Wait for more people** | 60 秒 | 最少人数到齐后，再等迟到的人多久。 |
| **Give up after** | 不限 | 从第一个人到达算起。到时人数还不够，这一场取消，并告诉等待的人。 |
| **Countdown** | 5 秒 | 开始前参与者看到的倒计时。 |
| **Let the computer play for people who leave** | 开 | 有人中途关掉页面，过了 **Take over after**（30 秒）由电脑替他玩。勾选 **Give the seat back when they return**，他回来后可以接着玩。 |

页面顶部显示当前状态、开放中的那一场（**Watch it live** 可以去看），以及给参与者的 **Sign-in link** 和 **Prolific link**。这两个链接总是指向当时开放的那一场，所以一直用同一个就行。

**暂停与继续**：如果你在控制台里结束了自动运行的那一场（停止它，或做了会重新加载引擎的事，比如切换实验、新建或恢复另一场、**Reload engine**），自动运行就会暂停，控制台会先提醒你。暂停时它不开新场，所以除非你自己为这个实验开了一场，链接都进不去。回到 Autopilot 页点 **Resume** 继续。如果关掉了 **Open the next session when one finishes**，一场结束后按钮会变成 **Open another session**。暂停状态在服务器重启后仍然保留。

整台服务器只有一个脚本引擎，控制台和自动运行共用。所以在开着自动运行的服务器上，不要测试别的实验：换一台服务器测，或者先关掉自动运行。更多见 [console.md](console.md#running-sessions-with-nobody-at-the-console)。

## 用 Prolific 招募

1. 先确保有一场开放、还没开始的场次：在 Live session 新建一场，或打开自动运行。
2. 在 **Recruitment** 页的 **Prolific** 标签下，点 **Copy URL** 复制 **Study URL**，粘贴到 Prolific 的研究设置里。在 Prolific 里选择用 URL 参数记录 Prolific ID，链接里 `{{%PROLIFIC_PID%}}` 这样的部分保持原样。
3. 这个链接总是把人带到这个实验当前开放、还没开始的那一场。没有这样的场次时，参与者会看到 "No session is open right now"。**Try it as a test participant** 用一个假的 Prolific ID 让你自己试一遍，它会像真实参与者一样占一个位置。
4. 参与者的 Prolific ID 会换成一个 player id，其他参与者看不到。ID 记在 `prolificEntry` 事件里，方便付费。
5. **完成码**：在 Prolific 里设置用完成码（completion code）把参与者送回。然后在 **When a participant finishes** 的 **Your completion code** 里填上这个码，用 **Copy code** 复制生成的代码，放进实验步骤里参与者结束的地方（比如最后一步）。它的核心是这一行（把 `C1A2B3C4` 换成你的完成码）：

```groovy
recruitment.completeProlific(player, [completionCode: "C1A2B3C4"])
```

参与者会看到完成码和一个 **Finish** 按钮，点了就回到 Prolific。**From Prolific in this session** 列出谁加入了、谁完成了，如果你在 `completeProlific` 里给了 `bonus` 金额，**Copy bonus list** 会复制奖金名单，用于 Prolific 的批量发奖金。等待室因为人不够被取消时，可以在实验步骤的 `onCancel` 里给等待的人另一个完成码，例子见 [whats-new.md](whats-new.md#running-without-anyone-at-the-admin-console-autopilot)。更多见 [console.md](console.md#recruiting-participants)。

## 导出数据

**Sessions & data** 页列出这个实验的每一场，可以按状态筛选，按名字、ID 或参数搜索。点 **View events** 看一场的事件，点 **Download data (CSV)** 下载它的数据。

| 下载 | 在哪里 | 内容 |
|---|---|---|
| **Download data** | Live session、Sessions & data | 一场的事件，文件 `<session>_<id>.csv`。每个事件的每项数据一行（没有数据的事件不出现），列是 `id`、`event`、`event_date`、`data_name`、`data_value`。 |
| **Download list** | Sessions & data | 所有场次 `<experiment>.csv`，每场一行：ID、名字、状态、创建时间和启动参数。 |
| **Export as table** | View events 的 **By event** 视图 | 某一类事件，每个事件一行，每项数据一列。 |
| **Download CSV** | Players | 当前列出的参与者和他们的全部属性。 |

CSV 里的时间都是 UTC（北京时间是 UTC+8）。数值按原样保存。如果参与者可能输入以 `=`、`+`、`-`、`@` 开头的内容，用表格软件打开时请按文本导入，否则可能被当成公式。更多见 [console.md](console.md#data)。

## 放到服务器上

参与者要从互联网访问服务器，实际上就要用 HTTPS。最简单的做法是在 Breadboard 前面放一个能自己申请证书的反向代理，比如 [Caddy](https://caddyserver.com)，配置只有三行（见下）。然后用这个公开网址（例如 `https://breadboard.example.org`）打开控制台，再复制给参与者的链接：控制台里的链接用的是你打开它时的网址。在 `conf/application-prod.conf` 里设 `breadboard.secureCookies=true` 和 `session.secure=true`；用 Mechanical Turk 的话，还要把 `breadboard.rootUrl` 设为这个网址。nginx 配置、作为服务长期运行（systemd）等见 [install.md](install.md#putting-it-on-a-server)。

```
breadboard.example.org {
  reverse_proxy localhost:9000
}
```

## 常见问题

**服务器重启后，正在进行的实验怎么样了？** 一场实验的状态保存在服务器内存里，所以停止或重启服务器会结束正在进行的场次。回来的参与者会被告知这一场没有在运行，并被请求重新打开研究链接；开着自动运行时，这个链接会带他们进入重启后新开的那一场。请在两场之间再重启。

**启动时提示 "Breadboard needs Java 8"**：启动脚本没找到 Java，或找到的不是 Java 8。它按顺序找 `JAVA8_HOME`、（在 Mac 上）电脑里装好的任何 Java 8、`JAVA_HOME`，最后才用 PATH 里的 `java`。安装 Java 8，并把 `JAVA8_HOME` 设为它的安装文件夹。

**参与者看到 "This session isn't running right now"**：这一场已经结束，或服务器重启过，或控制台把引擎换到了另一场。新开一场，或打开自动运行，再把研究链接发给参与者。

**数据库在哪里？怎么备份？** 实验、场次、事件和账号都在 `db/breadboard.h2.db` 这一个文件里。先停止服务器，再复制这个文件，就是备份。也可以在 **Experiments** 页把每个实验导出为 .zip，并在 **Sessions & data** 下载每一场的数据。用文件模式（file mode）的实验，文件在 `dev/` 文件夹里，也要一起复制。`conf/application.secret` 也请保管好，不要外传，从 3.x 升级时和数据库一起带到新版本（见 [install.md](install.md#upgrading)）。

**旧版控制台还能用吗？** 能。它在 `/legacy-admin`，例如 <http://localhost:9000/legacy-admin>，也可以从 **Settings** 里 **Previous console** 下的 **Open it** 进入。新旧两个控制台用的是同样的实验、场次和引擎，可以同时打开。
