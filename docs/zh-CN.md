# Breadboard 中文快速上手

这份指南带你走一遍：安装、建实验、开场、招募、导出数据。界面上的按钮、文件名、命令和设置都保留英文原样，方便你在软件里找到。快捷键按 Windows 写，Mac 上把 Ctrl 换成 ⌘。文中链接的英文文档写得更细。

## Breadboard 是什么

Breadboard 用来做网络实验。参与者彼此相连，每个人能看到和自己相连的人，并做出选择；实验允许的话，还能建立或切断连接。研究者在网页控制台（console）里搭建实验、开场、观察网络、下载数据。也可以打开自动运行（Autopilot），没人值守时实验一场接一场地自己跑。

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
   - **macOS**：双击 `Start Breadboard.command`。第一次 macOS 会拦下它，因为它是从网上下载的，macOS 无法验证开发者。macOS 15 及以后，按住 Control 键点按 → **打开** 已经不管用，只能到 **系统设置 → 隐私与安全性** 里点 **仍要打开**。一定能用的办法是用"终端"：打开终端，输入 `sh` 和一个空格，把文件夹里的 `breadboard.sh` 拖进终端窗口，按回车。
   - **Windows**：解压之前，先右键点下载的 zip，选 **属性**，勾选 **解除锁定**，点 **确定**。不然 Windows（智能应用控制）可能不让运行从网上下载的 `breadboard.bat`。然后右键点 zip，选 **全部解压缩**，再双击解压出的文件夹里的 `breadboard.bat`。（直接在 zip 里双击的话，它会提示先解压。）第一次启动时，Windows 防火墙会询问是否允许 Java 访问网络：勾选 **专用网络**，实验室里的其他电脑才能连上，再点 **允许访问**。如果 SmartScreen 还是拦截，点 **更多信息** → **仍要运行**。
   - **Linux**：解压（没有 `unzip` 的话，先运行 `sudo apt install unzip`），在文件夹里运行 `./breadboard.sh`。
3. 服务器启动好后，浏览器会自动打开控制台。窗口里也会写出访问地址：`On this computer: http://localhost:9000` 给这台电脑用，`On this network: http://192.168.…:9000` 给同一网络（比如实验室）里的其他电脑用。如果是从别的电脑打开一台新服务器（例如远程的服务器），还要填 **Setup code**：服务器会把它打印在自己的窗口里和 `logs/application.log` 里，这样只有管理这台服务器的人能创建第一个账号。第一次会看到 **Welcome to Breadboard**：填 **Email**、**Password**（至少 8 位，输两遍）和 **Default language**，点 **Create account**，以后就用这个账号登录。**Default language** 是你给参与者写文字用的语言（这些文字在 **Content** 页面里写），新建的实验会用它；控制台的界面始终是英文，不受它影响。它以后可以在 **Settings** 里改。
4. 实验室的其他人：在 **Settings → Researchers** 里输入他们的邮箱，点 **Add**，把显示的密码（只显示一次，可以点 **Copy sign-in details** 复制）发给他们。所有研究者都能看到和修改这台服务器上的每个实验。
5. 停止服务器：在它的窗口里按 Ctrl+C，或直接关掉窗口。

服务器运行期间，Mac 和 Windows 电脑不会自己进入睡眠。在 Linux 上，如果是在图形桌面里启动的（不是通过 SSH 或作为服务），启动脚本会请求 systemd（在 GNOME 桌面上也会请求桌面环境）不让电脑睡眠；如果电脑仍然会自动睡眠（有些桌面会这样，比如 Linux Mint 的 Cinnamon），请在电源设置里关掉"自动挂起"。要长时间无人值守地跑实验：笔记本请插着电源，不要合上盖子（合上盖子，电脑仍会睡眠）；Mac 上还要打开 **系统设置 → 电池 → 选项…** 里"显示器关闭时防止自动进入睡眠"那一项（Mac mini、iMac 在 **系统设置 → 能源** 里）。

如果提示端口被占用（"Port 9000 is in use" 或 "Breadboard is already running"），通常是 Breadboard 已经在运行，直接打开那个网址就行；要再开一个，用 `./breadboard.sh -Dhttp.port=9001`。

更多见 [install.md](install.md)。

## 建一个实验

控制台的大多数页面都针对一个实验：侧栏顶部选中的那个。点它可以切换实验，也可以新建或导入。

- **新建或导入**：在 **Experiments** 页点 **New experiment**，从 **Breadboard's template**（带 OnJoinStep、OnLeaveStep、InitStep 三个空步骤和标准的参与者页面）开始，从 **Example: network game**（一个可以直接运行的网络小游戏）开始，或复制一个已有的实验。Breadboard 导出的 .zip（比如别人给你的）用 **Import** 导入，它会成为一个新实验，不会改动你已有的东西。**Export .zip** 可以把实验导出，用来备份或搬到另一台服务器。
- **Steps**：实验的程序，用 Groovy 写。**Save**（Ctrl+S，Mac 上是 ⌘S）只是保存。要让引擎用上新代码，点 **Reload engine**；新建一场时引擎也会重新加载。注意：重新加载会让网络里的所有人断开。
- **Content**：参与者读到的文字，比如说明和知情同意书。每段文字有一个名字，程序里用 `c.get("名字")` 显示。每种语言各写一份；实验有几种语言时，参与者读哪一种由步骤决定，比如 `c.setDefaultLanguage("zho")`（`zho` 是语言的代码，Content 页面上写在语言名字旁边）。某段文字还没有这种语言的版本时，参与者看到的是英文版。
- **Participant page**：参与者页面的 HTML、JavaScript 和 CSS。新实验已经带了一个现成的页面。
- **Preview**（页面标题是 Participant preview）：开场后，在这里按某位参与者的视角看他的页面，可选 **Desktop** 或 **Phone**。
  - 预览里的点击是真实的：在预览里选一个选项，就等于这位参与者做了这个选择，会记进数据。
  - 他本人的页面在线时，预览只能看：你看到的就是他的屏幕，不能替他选，也不会打扰他的页面。
  - 他在这一场里来过、现在离开了（或者电脑已经接手了他的位置），预览可以替他选，但他仍算离开：到了设定的时间，电脑照常接手（或者接着玩），等候室也不算他。打开、刷新或放大预览都不会改变这一点。
  - 电脑玩家的预览只能看：你看着电脑怎么玩，电脑接着玩。
  - 测试玩家（他自己的页面从没在这一场连上过）由预览来玩：预览算他在线，预览里的选择就是他的选择。
  - 预览不会把人加进游戏：不在网络里的人（比如恢复场次后还没回来的人），预览上写着 **Not in the game right now**，等他回来后自动显示他的页面。恢复场次或重新加载引擎以后，这些规则照样适用。

**第一次用，建议从 Example: network game 开始**：建好后开一场（或打开 Autopilot），在两个标签页里打开登录链接（同一个浏览器里，第二个标签页会问你是以第一个人的身份继续，还是 **Join as someone else** 换一个身份加入），就能看到完整流程：等候室凑够 2 人后自动开始，电脑玩家把网络补到 8 人；每轮选蓝色或橙色，和多数邻居相同就得 1 分；网络图上能看到邻居上一轮的选择和邻居之间的连接，每轮可以点自己的一条连接把它剪掉；几轮之后显示得分。网络人数、最少开场人数、等待时间、轮数和每轮时长都是启动参数，不用改代码：在 **Parameters** 页面改它们的默认值，或者新建一场时只改这一场的；参与者看到的所有文字都在 **Content** 页面，可以直接改写或翻译。每次选择、每轮得分和每次剪断连接会记成 `ColourPick`、`RoundResult` 和 `TieCut` 事件，导出数据里就有。它的步骤代码也是写自己实验时很好的参考。

例子的文字是英文写的，放在 Content 页面的 **English** 下。如果你的 Default language 不是英文（比如中文，代码 `zho`），新建的例子里还会有这种语言，参与者读的就是它：

- 在 **Content** 页面顶部的语言栏（**Language**）点 **Chinese**（`zho`），写上译文（可以先点 **Copy from English**，再改成中文），保存后参与者就看到中文。
- 还没翻译的文字，参与者看到的仍是英文，所以可以一段一段地翻译。
- 参与者读哪种语言，由启动参数 `language` 决定，默认是你的 Default language。要让参与者看英文，在 **Parameters** 页把 `language` 的 **Default** 改成 `eng`，自动运行以后开的场次就用英文。自己新建一场时要注意：**New session** 会照上一场填好启动参数，不会自动换成新的 Default，所以要在 `language` 下点 **Use default**，确认是 `eng`，再点 **Create session**。

实验语言（步骤、`g`、`a`、内容）的写法见 [原版 wiki](https://github.com/human-nature-lab/breadboard/wiki)。各页面的说明见 [console.md](console.md#building-an-experiment)。

## 开一场实验并邀请参与者

"一场"（session）就是实验的一次运行。

1. 在 **Live session** 页点 **New session**，填 **Session name**（已经填好了，比如 Session 1，可以改），检查 **Launch parameters**，点 **Create session**。这一场马上开放，参与者可以用链接加入。
   - 第一场的启动参数填的是 **Parameters** 页上的默认值（Default）。以后每一场都照上一场填好；和默认值不一样时，对话框里写着 Filled in as in “Session 1”, the last session。
   - 所以在 **Parameters** 页改了某个参数的 Default 以后，自己新建一场时，要在这个参数下点 **Use default**（或点 **Reset to defaults**，全部换回默认值），新的 Default 才会用上。自动运行开的场次总是用默认值。
2. 把 **Links for participants** 里的链接发给参与者。**This session** 只在这一场运行时有效。**Whichever session is open** 总是指向这个实验当前开放、还没开始的那一场，可以一直用同一个。**Prolific study URL** 给 Prolific 用。参与者打开链接，在 **Your ID** 里输入一个 ID，点 **Continue**。
3. 链接用的是你打开控制台时的网址。用 localhost 打开时，链接只在你自己的电脑上能用。放到服务器上以后，要用服务器的公开网址打开控制台，再复制链接。
4. 人到齐后点 **Start the game**，在确认框里再点一次 **Start the game**。等待的人一起进入，实验开始。有等候室的实验（比如 Example: network game）人够了会自己开始，不用点。开始后再来的人会被拒绝（除非在 `conf/local.conf` 里设了 `breadboard.allowLateJoin=true`，见下面"设置写在哪里"）。
5. 在 **Network** 页实时看网络。选 **Tie**（快捷键 C），先点一个人再点另一个人，就加了一条连接；选 **Cut**（X）再点一条连接，就把它切断。成功后会出现 **Undo**，Ctrl+Z 也能撤销。每次修改都记成一条 `edgeEdit` 事件，操作者是 `admin`。
6. **Stop session** 之后，没有新人能加入。

更多见 [console.md](console.md#running-a-session)。

## 让实验自动运行

**Autopilot** 页让实验在没人值守时自己运行：Breadboard 自动开一场，参与者从一个固定链接进来，人数够了就自动开始，一场结束后再开下一场。设置保存在数据库里，**Save**（Ctrl+S）后立刻生效。主要设置：

| 设置 | 默认 | 作用 |
|---|---|---|
| **Run this experiment unattended** | 关 | 总开关。在 **Experiment** 里选要运行的实验。 |
| **Open the next session when one finishes** | 开 | 一场结束（或被取消）后，过 **Pause before the next session**（10 秒）再开下一场。 |
| **Longest session** | 120 分钟 | 从游戏开始算起。游戏超过这个时间就会被结束：参与者看到这一场时间已用完（已经拿到完成码的人仍然看到完成码），数据里记一条 `sessionTooLong` 事件，过了暂停时间再开下一场，Autopilot 页会写明是哪一场。连续 3 场都超时，自动运行就暂停。留空表示不限。新的设置默认 120 分钟；用旧版本保存过的设置升级后仍然不限时，需要的话自己填上。 |
| **Use the waiting room set here** | 开；实验步骤自己设了等候室时为关 | 等候室：先到的人看到等待页面，人够了游戏自己开始。打开时用下面四项设置。关着时，如果实验步骤自己设了等候室，就用步骤里的（点 **Show what the steps say** 可以看到），下面四项也不显示；如果步骤没设，要有人点 **Start the game**，游戏才会开始。 |
| **Minimum players** / **Maximum players** | 2 / 不限 | 只在上面的开关打开时有效。到了最少人数就可以开始；到了最多人数，马上开始倒计时。 |
| **Wait for more people** | 60 秒 | 只在上面的开关打开时有效。最少人数到齐后，再等迟到的人多久。 |
| **Give up after** | 600 秒 | 只在上面的开关打开时有效。从第一个人到达算起，到时人数还不够，这一场就取消，并告诉等待的人。留空表示一直等。 |
| **Countdown** | 5 秒 | 只在上面的开关打开时有效。开始前参与者看到的倒计时。 |
| **Let the computer play for people who leave, as set here** | 开；实验步骤自己设了时为关 | 有人中途关掉页面，过了 **Take over after**（30 秒）由电脑替他玩；勾选 **Give the seat back when they return**，他回来后可以接着玩。关着时用实验步骤里的设置。 |

**用 Example: network game 时**，它的步骤自己设好了等候室和电脑代玩，这两个开关保持关着就行。最少人数、等待时间和网络人数在 **Parameters** 页改（`minPlayers`、`waitSeconds`、`networkSize`）。

**要用 Prolific 招募？** 先按下面[用 Prolific 招募](#用-prolific-招募)的第 5 步设好完成码，试一遍，再打开自动运行。

页面顶部显示当前状态、开放中的那一场（**Watch it live** 可以去看），以及给参与者的 **Sign-in link** 和 **Prolific link**。这两个链接总是指向当时开放的那一场，所以一直用同一个就行。

**暂停与继续**：如果你在控制台里结束了自动运行的那一场（停止它，或做了会重新加载引擎的事，比如新建或恢复另一场、**Reload engine**；切换实验不会），自动运行就会暂停，控制台会先提醒你。暂停时它不开新场，所以除非你自己为这个实验开了一场，链接都进不去。回到 Autopilot 页点 **Resume** 继续。如果关掉了 **Open the next session when one finishes**，一场结束后按钮会变成 **Open another session**。暂停状态在服务器重启后仍然保留。

**游戏中步骤出错**：游戏进行中，步骤代码出了错（在计时器上、有人做出选择后，或在 OnJoinStep、OnLeaveStep 里）：

- 每个打开的控制台都在 Output 面板显示这个错误，这一场的数据里记一条 `stepFailed`，写明是哪个步骤、哪一行。
- 如果出错让下一步没能开始，游戏就卡住了。如果下一步照常开始了，错误只记录和显示，游戏继续。
- **自动运行的场次**卡住时（也包括你点 **Resume** 或保存设置时正开着的、这个实验的那一场）：这一场被停掉，参与者看到这一场因为研究出了问题不得不停止（已经拿到完成码的人仍看到完成码）；自动运行暂停，Autopilot 页写明是哪个步骤、哪一行。改好步骤后，点 **Resume**。没设等候室时，游戏开始前 OnJoinStep 出错也算卡住。在这样的场次里点 **Start the game** 却没能开始，这一场也会被停掉，因为它的等候室不会再开始游戏。
- 自动运行的场次里一旦有步骤出过错，如果游戏有一分钟什么都不再等（没有人有选项，没有步骤或计时器在等时间，也没有人还没填完表单或别的输入；聊天不算，在聊天里写的话不会让游戏往下走），这一场也会被停掉，记一条 `sessionStalled`，自动运行暂停。
- **你手动运行的场次**不会被结束。由 **Start the game**、等候室或 OnJoinStep 开始、却没能开始的游戏会被撤回，新来的人又能进来；改好步骤后，再点 **Start the game**。实验代码在计时器上或有人做出选择后开始的游戏，只有 InitStep 本身出错时才撤回。

整台服务器只有一个脚本引擎，控制台和自动运行共用。自动运行的时候，你仍然可以切换到别的实验去修改它：切换不会影响正在进行的那一场，那个实验的 Build 页面（Steps、Content、Parameters 等）和 **Sessions & data** 都照常可用。要运行或测试别的实验，先停止自动运行的那一场（自动运行会因此暂停），或者为那个实验新建一场（控制台会先提醒你，再结束自动运行的那一场）；用完后回到 Autopilot 页点 **Resume**。更多见 [console.md](console.md#running-sessions-with-nobody-at-the-console)。

## 用 Prolific 招募

1. 先确保有一场开放、还没开始的场次：在 Live session 新建一场，或打开自动运行。最好先做完下面第 5 步再开场：已经开着的那一场不会自动用上之后设的完成码。
2. 在 **Recruitment** 页的 **Prolific** 标签下，点 **Copy URL** 复制 **Study URL**，粘贴到 Prolific 的研究设置里。在 Prolific 研究设置的 Data collection 部分，记录 Prolific ID 的方式选 **I'll use URL parameters**；链接里 `{{%PROLIFIC_PID%}}` 这样的部分保持原样。
3. 这个链接总是把人带到这个实验当前开放、还没开始的那一场。没有这样的场次时，参与者会看到 "No session is open right now"。**Try it as a test participant** 用一个假的 Prolific ID 让你自己试一遍，它会像真实参与者一样占一个位置。
4. 参与者的 Prolific ID 会换成一个 player id，其他参与者看不到。ID 记在 `prolificEntry` 事件里，方便付费。**同一个人不会参加两次**：在这个实验里已经参加过的人（拿到过完成码，或者游戏结束时还在场）再打开 Prolific 链接，会看到 "You have already taken part in this study" 和他的完成码，不会被安排进新的一场（服务器重启后也一样）；场次被停止或因重启中断的人可以再加入。这个检查按整个实验算：如果要在 Prolific 上用同一批人做新一期，就复制一个实验。自己测试时请用 **Try it as a test participant**，它每次都会生成新的 Prolific ID。
5. **完成码**：在 Prolific 里设置用完成码（completion code）把参与者送回，记下这个码。然后：
   - **用 Example: network game（或复制它的实验）**：不用改代码。在 **Parameters** 页点 `prolificCode` 右边的铅笔，把 **Default** 填成这个码，点 **Save changes**。最好在开场和打开自动运行之前做：已经开着的那一场仍用开场时的值。
     - 自动运行开的场次用的就是这个 Default。自动运行已经开着时：趁还没有人进来，在 **Live session** 点 **Stop session**（自动运行会因此暂停），再到 **Autopilot** 页点 **Resume**，新开的那一场就用新的完成码。
     - 自己新建一场时要注意：**New session** 会照上一场填好启动参数（对话框里写着 Filled in as in “Session 1”, the last session），不会自动换成新的 Default。在 `prolificCode` 下点 **Use default**（或点 **Reset to defaults**），确认是你的完成码，再点 **Create session**。已经有一场开着时，这个按钮是 **Stop it and create**（确认框里再点一次），它会先停掉开着的那一场。
   - **自己写的实验**：在 **Recruitment** 页 **When a participant finishes** 的 **Your completion code** 里填上这个码，点 **Copy code**，把代码放进参与者结束的那一步。代码里的 `player` 要换成那一步里代表参与者的变量（例子游戏的 FinishStep 里是 `p`），并且只对从 Prolific 来的人调用（把 `C1A2B3C4` 换成你的完成码）：

     ```groovy
     if (p._system?.recruitment?.source == "prolific") {
       recruitment.completeProlific(p, [completionCode: "C1A2B3C4"])
     }
     ```

     不加这个判断也不会出错：对不是从 Prolific 来的人，它什么都不做，只在服务器日志里写一行原因。
   - 改好后先试一遍，再在 Prolific 上发布：在 **Live session** 新建一场（用例子游戏时，确认 `prolificCode` 填的是你的完成码，不是的话点它下面的 **Use default**），然后在 **Recruitment** 页点 **Try it as a test participant**，它会打开一个新标签页，用一个假的 Prolific ID 加入。游戏没有自己开始时（例子游戏的等候室默认要 2 人），回到 **Live session** 点 **Start the game**，电脑玩家会补上其他位置。玩完几轮，确认最后能看到完成码和 **Finish and return to Prolific** 按钮。

参与者会看到完成码和 **Finish and return to Prolific** 按钮，点了就回到 Prolific。**From Prolific in this session** 列出谁加入了、谁完成了，如果你在 `completeProlific` 里给了 `bonus` 金额，**Copy bonus list** 会复制奖金名单，用于 Prolific 的批量发奖金。等候室因为人不够被取消时，可以在实验步骤的 `onCancel` 里给等待的人另一个完成码，例子见 [whats-new.md](whats-new.md#running-without-anyone-at-the-admin-console-autopilot)。更多见 [console.md](console.md#recruiting-participants)。

## 导出数据

**Sessions & data** 页列出这个实验的每一场，可以按状态筛选，按名字、ID 或参数搜索。点 **View events** 看一场的事件，点 **Download data (CSV)** 下载它的数据。

| 下载 | 在哪里 | 内容 |
|---|---|---|
| **Download data** | Live session、Sessions & data | 一场的事件，文件 `<session>_<id>.csv`。每个事件的每项数据一行（没有数据的事件也占一行，数据列空着），列是 `id`、`event`、`event_date`、`data_name`、`data_value`。 |
| **Download all data** | Sessions & data | 列出的所有场次的事件放在一个文件 `<experiment>_data.csv` 里，每行前面是场次的 ID、名字、状态和启动参数，后面是和单场文件一样的列，可以直接合在一起分析。 |
| **Download list** | Sessions & data | 所有场次 `<experiment>.csv`，每场一行：ID、名字、状态、创建时间和启动参数。 |
| **Export as table** | View events → **By event**，点开一种事件后 | 某一类事件，每个事件一行，每项数据一列。 |
| **Download CSV** | Players | 当前列出的参与者和他们的全部属性。 |

**用 Excel 或 WPS 打开**：CSV 是 UTF-8 编码的，直接双击打开时，Excel 和 WPS 常把中文显示成乱码。两种办法：

- 下载前，在 **Sessions & data** 页右上角勾选 **For Excel**。这样下载的文件开头多一个 BOM（字节顺序标记），双击打开就能正确显示中文。它对这一页的下载和导出都有效，也包括 **Live session** 的 **Download data** 和 **Players** 页的 **Download CSV**。这个选择记在这个浏览器里。
- 没勾选时下载的文件，在 Excel 里用 **数据 → 从文本/CSV** 导入，**文件原始格式** 选 **65001: Unicode (UTF-8)**；在 WPS 里导入时，编码也选 UTF-8。

用 R、Python 或以前写好的分析脚本时不用勾选。不勾选时，文件开头没有 BOM，**Download data**、**Download all data** 和 **Download list** 下载的文件和以前的版本写法完全一样。

**时间**：CSV 里的时间都是 UTC（北京时间是 UTC+8），写成 `2026-10-30T13:27:26:036Z`。毫秒前面是冒号，和以前的版本一样，这样旧的分析脚本照样能用。View events 里导出的文件现在也这样写（3.0 版写的是 `2026-10-30T13:27:26.036Z`，毫秒前面是点）。读成时间：

- pandas：`pd.to_datetime(df.event_date, format="%Y-%m-%dT%H:%M:%S:%fZ", utc=True)`
- R：`as.POSIXct(sub(":(\\d{3})Z$", ".\\1", df$event_date), format = "%Y-%m-%dT%H:%M:%OS", tz = "UTC")`

数据值按原样写进 CSV：值里的逗号、引号和换行按 CSV 的规矩放在引号里，不会弄乱文件，但不会为表格软件做任何改动。如果参与者可能输入以 `=`、`+`、`-`、`@` 开头的内容，用表格软件打开时请按文本导入，否则可能被当成公式。更多见 [console.md](console.md#data)。

## 放到服务器上

参与者要从互联网访问服务器，实际上就要用 HTTPS。最简单的做法是在 Breadboard 前面放一个能自己申请证书的反向代理，比如 [Caddy](https://caddyserver.com)，配置只有三行（见下）。然后用这个公开网址（例如 `https://breadboard.example.org`）打开控制台，再复制给参与者的链接：控制台里的链接用的是你打开它时的网址。在 `conf/local.conf` 里设 `breadboard.secureCookies=true` 和 `session.secure=true`；用 Mechanical Turk 的话，还要把 `breadboard.rootUrl` 设为这个网址。nginx 配置见 [install.md](install.md#putting-it-on-a-server)，作为服务长期运行（systemd、launchd、任务计划程序）见 [install.md](install.md#running-it-for-good)。

```
breadboard.example.org {
  reverse_proxy localhost:9000
}
```

## 常见问题

**服务器重启后，正在进行的实验怎么样了？** 一场实验的状态保存在服务器内存里，所以停止或重启服务器会结束正在进行的场次：参与者的页面会说明这一场因为研究服务器停止或重启而结束，并告诉他们几分钟后可以重新打开研究链接，加入那时开着的一场（从 Prolific 来的人：从 Prolific 重新打开研究，或者退回研究；已经拿到完成码的人仍然看到完成码）。重启后，这样的场次会被标成 Stopped，数据里记一条 `sessionInterrupted` 事件；在 **Sessions & data** 里点 **Resume** 可以让它的游戏从头再来。回来的参与者会被告知这一场没有在运行，并被请求重新打开研究链接；开着自动运行时，这个链接会带他们进入重启后新开的那一场。请在两场之间再重启。用 `Start Breadboard.command`（Mac）、`breadboard.bat`（Windows）或在终端里启动时，服务器意外退出（例如内存耗尽）会自动重新启动，等待时间逐次加长，10 分钟内重启 5 次后就不再重启；原因会写在窗口里和 `logs/application.log` 里。按 Ctrl+C 或关掉窗口则是真正停止。要开机自动启动，见 [install.md](install.md#running-it-for-good)（Linux 用 systemd，Mac 用 launchd，Windows 用任务计划程序：发布包里附有现成的 `breadboard-task.xml`）。`/health` 地址可以给监控服务用，正常时返回 `{"status":"ok"}`。

**启动时提示 "Breadboard needs Java 8"**：启动脚本没找到 Java，或找到的不是 Java 8。最简单的办法是下载带 Java 的版本（名字以 `-macos-arm64`、`-macos-x64`、`-windows-x64` 或 `-linux-x64` 结尾）。它按顺序找 `JAVA8_HOME`、自带的 `jre` 文件夹、（在 Mac 上）电脑里装好的任何 Java 8、`JAVA_HOME`，最后才用 PATH 里的 `java`，用找到的第一个 Java 8。`JAVA8_HOME` 可以是 JDK 的文件夹、它的 `bin` 文件夹，或者 Mac 的 Finder 里看到的 JDK 文件夹（比如 `zulu-8.jdk`）；如果那里没有 Java 8，启动脚本会提示 "JAVA8_HOME is …, which has no Java 8; ignoring it"，然后接着找别的。

**参与者看到 "This session isn't running right now"**：这一场已经结束，或服务器重启过，或控制台把引擎换到了另一场。新开一场，或打开自动运行，再把研究链接发给参与者。

**忘记了登录密码怎么办？** 先停止服务器，再用这个账号的邮箱启动一次：`./breadboard.sh -Dbreadboard.resetPassword=你的邮箱`（Windows 上是 `breadboard.bat -Dbreadboard.resetPassword=你的邮箱`）。服务器会在它的窗口里打印一个新密码（不会写进日志），并让这个账号在所有浏览器里退出。用新密码登录后，在 **Settings** 里改成你自己的密码，以后正常启动、不要再带这个参数。登录时邮箱不区分大小写；同一个账号可以同时在几个浏览器里登录（比如笔记本和实验室的电脑）。

**实验名字写错了能改吗？** 能。在 **Experiments** 页，实验卡片的菜单里有 **Rename…**，当前实验旁边也有 **Rename**。改名不影响它的场次、数据和链接。文件模式下不能改名（它的文件夹是按名字起的），先退出文件模式再改。

**参与者关了网页还能回来吗？** 能。用同一个浏览器重新打开研究链接（或登录页再输入一次自己的 ID），就会回到原来的座位，即使游戏已经开始；离开超过设定时间时由电脑代玩，回来后交还给本人。换了浏览器或设备就不行，这是为了防止别人冒用 ID。

**数据库在哪里？怎么备份？** 实验、场次、事件和账号都在 `db/breadboard.h2.db` 这一个文件里。服务器**每天自动备份**一次到 `backups/` 文件夹（升级到新版本前也会先备份一次），保留最近 14 份，不用停服务器。在 **Settings → Backups** 里可以看到这些备份，点 **Back up now** 立即备份，点 **Download** 下载。备份和数据库在同一块硬盘上，请不时复制到别的地方。**恢复**：停止服务器，把备份 zip 解压到 `db` 文件夹（替换里面的 `breadboard.h2.db`），再启动。也可以在 **Experiments** 页把每个实验导出为 .zip，并在 **Sessions & data** 下载每一场的数据。用文件模式（file mode）的实验，文件在 `dev/` 文件夹里，也要一起复制。`conf/application.secret` 也请保管好，不要外传。从 3.x 升级时，把它和 `conf/local.conf`（你的设置）跟数据库一起带到新版本（见 [install.md](install.md#upgrading)）。

**设置写在哪里？** 写在 `conf/local.conf` 里，每行一项，例如 `breadboard.rootUrl="https://breadboard.example.org"`，改完后重启服务器。这个文件在第一次启动时自动生成（里面只有说明），用文本编辑器（Mac 上的"文本编辑"，Windows 上的"记事本"）打开就能改。`conf/application-prod.conf` 列出了所有设置和说明，但不要直接改它：每个新版本都带着自己的 `application-prod.conf`，而 `conf/local.conf` 是你自己的，升级时把它复制到新版本的 `conf` 文件夹里就行。从 3.0 升级时还没有这个文件：把你在旧的 `application-prod.conf` 里改过的那几行写进新版本的 `conf/local.conf`。如果写错了（比如引号没配对），服务器会启动不了，并指出 `conf/local.conf` 的哪一行有问题。Windows 上的路径要用正斜杠，例如 `breadboard.backup.dir="D:/Breadboard backups"`，或者把每个反斜杠写两遍（`"D:\\Breadboard backups"`）：引号里的单个反斜杠是转义符，会让服务器启动不了，或者悄悄变成别的路径。运行服务器的账号必须能读这个文件：读不了的话（比如用 `sudo` 复制过来的，属于 root），启动脚本会提示 "conf/local.conf can't be read by …" 并拒绝启动，免得服务器不带你的设置就跑起来；按提示把文件的所有者改成那个账号就行。

**实验室几个人怎么一起用？** 每人一个账号：在 **Settings → Researchers** 里添加、删除，或给别人重置密码。所有研究者共用这台服务器上的全部实验（谁都能打开、修改、运行）。要和别的课题组分开，就各用一台服务器。

**旧版控制台还能用吗？** 能。它在 `/legacy-admin`，例如 <http://localhost:9000/legacy-admin>，也可以从 **Settings** 里 **Previous console** 下的 **Open it** 进入。新旧两个控制台用的是同样的实验、场次和引擎，可以同时打开。
