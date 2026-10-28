# Contributing

Contributions to Breadboard are welcome: bug reports, fixes, documentation and example
experiments. This fork's code and pull requests are at
[github.com/TianzhuQin/breadboard](https://github.com/TianzhuQin/breadboard).

## Reporting a problem

Check [Troubleshooting](docs/install.md#troubleshooting) first. To report a problem, open a pull
request whose description explains it (with a failing test, if you can), or write to the
maintainer. Say:

- which release you run (its folder is named after it, e.g. `breadboard-v3.1.0`), your
  operating system and your Java version (`java -version`);
- what you did, what you expected, and what happened instead;
- the relevant lines of `logs/application.log`, or of the server's output.

For a problem with an experiment, the smallest script that shows it helps most. Leave out
`conf/application.secret`, passwords and participants' data.

## Proposing a change

1. Fork the repository and make a branch from `main`.
2. Make the change, with tests: the server's in `test/`, the engine's in `groovy/*_test.groovy`,
   the front end's in each package's `test/` folder.
3. Run the checks described in [docs/development.md](docs/development.md#tests).
4. If people using Breadboard will notice the change, add a line to `CHANGELOG.md` and update the
   documentation in `docs/`.
5. Open a pull request against `main` that says what the change does and why. CI builds and
   tests every pull request.

For a larger change, open an issue first to talk it over. Changes to the database have their own
rules: see [docs/development.md](docs/development.md#database-changes).

## Building and testing

[docs/development.md](docs/development.md) covers the requirements (Java 8, Node, pnpm), building
the server and the front end, running from source, the tests, CI and making a release.
