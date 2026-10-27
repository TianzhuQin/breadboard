#!/bin/sh
chmod u+x ./bin/breadboard
chmod u+x ./db/breadboard.h2.db

# application.secret signs admin sessions and participant invitation links, so it must not be the
# value printed in this script (it used to be "pleasechangethis"). Use $APPLICATION_SECRET if set;
# otherwise generate one on first run and keep it in conf/application.secret.
if [ -z "$APPLICATION_SECRET" ]; then
  if [ ! -s conf/application.secret ]; then
    (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > conf/application.secret)
  fi
  APPLICATION_SECRET="$(cat conf/application.secret)"
fi

./bin/breadboard -Dhttps.port=9443 -Dapplication.secret="$APPLICATION_SECRET" -Duser.timezone=UTC -Dconfig.file=conf/application-prod.conf -J-XX:+CMSClassUnloadingEnabled -J-XX:+UseConcMarkSweepGC
