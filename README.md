Breadboard
==========

Breadboard runs experiments in which people interact over a network. Each participant sees the
people they are tied to, makes choices, and, when the experiment allows it, adds or cuts ties.
The researcher builds the experiment and runs its sessions from a web console, or lets the
autopilot run them with nobody at the console.

This is a maintained fork of [human-nature-lab/breadboard](https://github.com/human-nature-lab/breadboard)
(v2.4). Version 3 keeps everything v2 did, and existing experiments keep working. It adds:

- **A new researcher console**: every v2 feature, plus a network you edit directly (draw a tie
  between two people, cut one), a live view of each session, and previews of the participant
  page. The v2 console is still at `/legacy-admin`.
- **A new participant page**: a clear picture of the participant and their neighbors, on
  phones too, where participants add or cut ties when the experiment lets them.
- **Visibility modes**: an experiment can show participants the ties among their neighbors
  (`g.setVisibility("egoTies")`), not only their own.
- **Autopilot**: sessions open, fill a waiting room, start, replace people who drop out with
  computer players and finish, with no researcher present. One sign-in link, or one Prolific
  study URL, leads participants to whichever session is open.
- **Participant pages in Chinese**: in a study whose texts are in Chinese, Breadboard's own words to
  participants (sign-in, waiting room, network, end screens) are in Chinese too.
- **A lab's researchers**: an account for each, all working on the same experiments.
- **Security fixes**: nobody can take over another participant's page by knowing its address,
  and previews can't act with the researcher's sign-in.
- **Fixes to the engine**: steps with time limits, games closed to latecomers once started,
  correct UTC timestamps in the data, and many more (see [CHANGELOG.md](CHANGELOG.md)).

## Getting started

From the [latest release](https://github.com/TianzhuQin/breadboard/releases/latest), download the
zip for your computer (`-macos-arm64`, `-macos-x64`, `-windows-x64` or `-linux-x64`: they include
Java) and unzip it. Then double-click `Start Breadboard.command` (macOS) or `breadboard.bat`
(Windows), or run `./breadboard.sh` (Linux). The console opens in your browser: create your
researcher account, and add your colleagues' in Settings. [docs/install.md](docs/install.md) covers
putting it on a server, HTTPS, backups, starting it again by itself, and upgrading from v2.

## Documentation

| | |
|---|---|
| [Installing and running](docs/install.md) | Requirements, settings, HTTPS, backups, upgrading |
| [The researcher console](docs/console.md) | Building an experiment, running sessions, recruiting, data |
| [What's new for experiment authors](docs/whats-new.md) | Script API additions: visibility, tie editing, timed steps, autopilot, the participant page |
| [Developing Breadboard](docs/development.md) | Building from source, tests, CI, making a release |
| [中文快速上手](docs/zh-CN.md) | 安装、建实验、开场、自动运行、用 Prolific 招募、导出数据 |

The [original Breadboard wiki](https://github.com/human-nature-lab/breadboard/wiki) still
describes the experiment language (steps, `g`, `a`, content), which is unchanged.

## Built with

[Play Framework](https://www.playframework.com/) 2.2 (Java 8), [Groovy](https://groovy-lang.org/),
[TinkerPop](https://tinkerpop.apache.org/) Blueprints, an H2 database, and on the front end
[Vue](https://vuejs.org/) 3 (console), Vue 2 with Vuetify (participant page), d3-force and
[CodeMirror](https://codemirror.net/).

## License

MIT, see [LICENSE.md](LICENSE.md). Breadboard was created by the
[Human Nature Lab](https://humannaturelab.net/) at Yale.

### Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
