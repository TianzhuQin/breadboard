# Breadboard 中文快速上手

这份指南带你走一遍：安装、建实验、开场、招募、导出数据。界面上的按钮、文件名、命令和设置都保留英文原样，方便你在软件里找到。文中链接的英文文档写得更细。

## Breadboard 是什么

Breadboard 用来做网络实验。参与者彼此相连，每个人能看到和自己相连的人，并做出选择；实验允许的话，还能加上或切断连接。研究者在网页控制台（console）里搭建实验、开场、观察网络、下载数据。也可以打开自动运行（Autopilot），没人值守时实验一场接一场地自己跑。

## 下载并启动

1. 从 [releases 页面](https://github.com/TianzhuQin/breadboard/releases) 下载适合你电脑的 zip，解压。这些下载**已经带了 Java 8**，不用另外安装：

| 下载 | 适用于 |
|---|---|
| `breadboard-<version>-macos-arm64.zip` | Apple 芯片（M1 及以后）的 Mac |
| `breadboard-<version>-macos-x64.zip` | Intel 处理器的 Mac |
| `breadboard-<version>-windows-x64.zip` | Windows |
| `breadboard-<version>-linux-x64.zip` | Linux |
| `breadboard-<version>.zip` | 任何系统，但要自己装 Java 8（**只能用 Java 8**），并把 `JAVA8_HOME` 设为它的安装文件夹 |

2. 启动：
   - **macOS**：双击 `Start Breadboard.command`。第一次 macOS 可能提示"无法验证开发者"：右键点这个文件，选 **打开**（较新的 macOS 要到 系统设置 → 隐私与安全性 里点 **仍要打开**）。也可以在终端里进入文件夹，运行 `./breadboard.sh`。
   - **Windows**：双击 `breadboard.bat`。如果 SmartScreen 拦截，点 **更多信息** → **仍要运行**。
   - **Linux**：在文件夹里运行 `./breadboard.sh`。
3. 服务器启动好后，浏览器会自动打开控制台（也可以手动打开 <http://localhost:9000>）。如果是从别的电脑打开一台新服务器（例如远程的服务器），还要填 **Setup code**：服务器会把它打印在自己的窗口里和 `logs/application.log` 里，这样只有管理这台服务器的人能创建第一个账号。第一次会看到 **Welcome to Breadboard**：填 **Email**、**Password**（至少 8 位，输两遍）和 **Default language**（新内容默认用的语言），点 **Create account**。以后用这个账号登录。
4. 实验室的其他人：在 **Settings → Researchers** 里输入他们的邮箱，点 **Add**，把显示的密码（只显示一次，可以点 **Copy sign-in details** 复制）发给他们。所有研究者都能看到和修改这台服务器上的每个实验。
5. 停止服务器：在它的窗口里按 Ctrl+C，或直接关掉窗口。

如果提示端口被占用（"Port 9000 is in use" 或 "Breadboard is already running"），通常是 Breadboard 已经在运行，直接打开那个网址就行；要再开一个，用 `./breadboard.sh -Dhttp.port=9001`。

更多见 [install.md](install.md)。

## 建一个实验

控制台的大多数页面都针对一个实验：侧栏顶部选中的那个。点它可以切换实验，也可以新建或导入。

- **新建或导入**：在 **Experiments** 页点 **New experiment**，从 **Breadboard's template**（带 InitStep、OnJoinStep、OnLeaveStep 三个空步骤和标准的参与者页面）开始，从 **Example: network game**（一个可以直接运行的网络小游戏）开始，或复制一个已有的实验。Breadboard 导出的 .zip（比如别人给你的）用 **Import** 导入，它会成为一个新实验，不会改动你已有的东西。**Export .zip** 可以把实验导出，用来备份或搬到另一台服务器。
- **Steps**：实验的程序，用 Groovy 写。**Save**（Ctrl+S）只是保存。要让引擎用上新代码，点 **Reload engine**；新建一场时引擎也会重新加载。注意：重新加载会让网络里的所有人断开。
- **Content**：参与者读到的文字，比如说明和知情同意书。每段文字有一个名字，程序里用 `c.get("名字")` 显示。每种语言各写一份。
- **Participant page**：参与者页面的 HTML、JavaScript 和 CSS。新实验已经带了一个现成的页面。
- **Preview**（页面标题是 Participant preview）：开场后，在这里按选中参与者的视角看页面，可选 **Desktop** 或 **Phone**。**预览是真的**：在预览里点一个选项，就等于那位参与者做了这个选择，会记进数据。

**第一次用，建议从 Example: network game 开始**：建好后开一场（或打开 Autopilot），在两个标签页里打开登录链接（同一个浏览器里，第二个标签页会问你是以第一个人的身份继续，还是 **Join as someone else** 换一个身份加入），就能看到完整流程：等候室凑够 2 人后自动开始，电脑玩家把网络补到 8 人；每轮选蓝色或橙色，和多数邻居相同就得 1 分；网络图上能看到邻居上一轮的选择和邻居之间的连接，每轮可以点自己的一条连接把它剪掉；几轮之后显示得分。网络人数、最少开场人数、等待时间、轮数和每轮时长都是启动参数，在 **Parameters** 页面就能改，不用改代码；参与者看到的所有文字都在 **Content** 页面，可以直接改写或翻译。每次选择和每轮得分会记成 `ColourPick`、`RoundResult` 事件，导出数据里就有。它的步骤代码也是写自己实验时很好的参考。

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
| **Download all data** | Sessions & data | 列出的所有场次的事件放在一个文件 `<experiment>_data.csv` 里，每行前面是场次的 ID、名字、状态和启动参数，后面是和单场文件一样的列，可以直接合在一起分析。 |
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

**服务器重启后，正在进行的实验怎么样了？** 一场实验的状态保存在服务器内存里，所以停止或重启服务器会结束正在进行的场次。重启后，这样的场次会被标成 Stopped，数据里记一条 `sessionInterrupted` 事件；在 **Sessions & data** 里点 **Resume** 可以让它的游戏从头再来。回来的参与者会被告知这一场没有在运行，并被请求重新打开研究链接；开着自动运行时，这个链接会带他们进入重启后新开的那一场。请在两场之间再重启。要让服务器意外退出后自己重新启动（以及开机自动启动），见 [install.md](install.md#putting-it-on-a-server)（Linux 用 systemd，Mac 用 launchd，Windows 用任务计划程序）。`/health` 地址可以给监控服务用，正常时返回 `{"status":"ok"}`。

**启动时提示 "Breadboard needs Java 8"**：启动脚本没找到 Java，或找到的不是 Java 8。最简单的办法是下载带 Java 的版本（名字以 `-macos-arm64`、`-macos-x64`、`-windows-x64` 或 `-linux-x64` 结尾）。它按顺序找 `JAVA8_HOME`、自带的 `jre` 文件夹、（在 Mac 上）电脑里装好的任何 Java 8、`JAVA_HOME`，最后才用 PATH 里的 `java`。

**参与者看到 "This session isn't running right now"**：这一场已经结束，或服务器重启过，或控制台把引擎换到了另一场。新开一场，或打开自动运行，再把研究链接发给参与者。

**忘记了登录密码怎么办？** 先停止服务器，再用这个账号的邮箱启动一次：`./breadboard.sh -Dbreadboard.resetPassword=你的邮箱`（Windows 上是 `breadboard.bat -Dbreadboard.resetPassword=你的邮箱`）。服务器会在它的窗口里打印一个新密码（不会写进日志），并让这个账号在所有浏览器里退出。用新密码登录后，在 **Settings** 里改成你自己的密码，以后正常启动、不要再带这个参数。登录时邮箱不区分大小写；同一个账号可以同时在几个浏览器里登录（比如笔记本和实验室的电脑）。

**实验名字写错了能改吗？** 能。在 **Experiments** 页，实验卡片的菜单里有 **Rename…**，当前实验旁边也有 **Rename**。改名不影响它的场次、数据和链接。文件模式下不能改名（它的文件夹是按名字起的），先退出文件模式再改。

**参与者关了网页还能回来吗？** 能。用同一个浏览器重新打开研究链接（或登录页再输入一次自己的 ID），就会回到原来的座位，即使游戏已经开始；离开超过设定时间时由电脑代玩，回来后交还给本人。换了浏览器或设备就不行，这是为了防止别人冒用 ID。

**数据库在哪里？怎么备份？** 实验、场次、事件和账号都在 `db/breadboard.h2.db` 这一个文件里。服务器**每天自动备份**一次到 `backups/` 文件夹（升级到新版本前也会先备份一次），保留最近 14 份，不用停服务器。在 **Settings → Backups** 里可以看到这些备份，点 **Back up now** 立即备份，点 **Download** 下载。备份和数据库在同一块硬盘上，请不时复制到别的地方。**恢复**：停止服务器，把备份 zip 解压到 `db` 文件夹（替换里面的 `breadboard.h2.db`），再启动。也可以在 **Experiments** 页把每个实验导出为 .zip，并在 **Sessions & data** 下载每一场的数据。用文件模式（file mode）的实验，文件在 `dev/` 文件夹里，也要一起复制。`conf/application.secret` 也请保管好，不要外传，从 3.x 升级时和数据库一起带到新版本（见 [install.md](install.md#upgrading)）。

**实验室几个人怎么一起用？** 每人一个账号：在 **Settings → Researchers** 里添加、删除，或给别人重置密码。所有研究者共用这台服务器上的全部实验（谁都能打开、修改、运行）。要和别的课题组分开，就各用一台服务器。

**旧版控制台还能用吗？** 能。它在 `/legacy-admin`，例如 <http://localhost:9000/legacy-admin>，也可以从 **Settings** 里 **Previous console** 下的 **Open it** 进入。新旧两个控制台用的是同样的实验、场次和引擎，可以同时打开。
