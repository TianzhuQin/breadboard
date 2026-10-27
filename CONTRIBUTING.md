# Contributing
There are many different ways to contribute to the Breadboard project. We welcome issues, code, documentation
and examples. 

## Development

### Environment setup
Breadboard requires **Java 8** specifically (the Play 2.2 / Scala 2.10 / sbt 0.13.18 stack does
not build under Java 9 or newer — it hangs silently during compilation). Install a Java 8 JDK and
point sbt at it, e.g. `sbt -java-home /path/to/jdk8 test`. See `CLAUDE.md` for the full build/test
commands.

- `cd frontend`
- `pnpm install`

#### Building from a clean machine
Plain `sbt` no longer resolves this build on its own: the old `http://` resolvers are gone and
`play-docs` is no longer published. `bin/sbt8` downloads a checksum-verified sbt launcher and
resolves everything over https (`project/sbt-repositories`):

```bash
export JAVA8_HOME=/path/to/jdk8
(cd frontend && pnpm install && pnpm run build:all)
bin/sbt8 compile test stage
bin/run-local 9000        # fresh H2 database in .local-run/, evolutions applied
```

`bin/run-local` creates its own database and a random `application.secret`, so it is safe to
delete `.local-run/` to start over.

### Running
#### Backend development
If modifications will be made to files in the **frontend** directory, use the frontend development instructions instead
- Start the play framework server using `sbt "run -Dconfig.file=conf/application-prod.conf"`

#### Frontend development
This uses a slightly different configuration to allow hot module replacement via webpack on frontend files.
- Start the webpack server using `cd frontend && pnpm start`
- Start the play framework server using `sbt "run -Dconfig.file=conf/application-dev.conf"`


From a terminal run . This will start a dev server which will
automatically rebuild the frontend files whenever a file changes.


## Production

### Compile jars
- `cd frontend && pnpm run build:all` to build frontend assets (incl. the client/core packages) if this code has changed
- `sh create_prod_dist.sh` to compile distributable files
- In many cases, only copying the compiled **breadboard.jar** file is enough to update existing Breadboard applications.
