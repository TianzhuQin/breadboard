#!/bin/sh
chmod u+x ./bin/breadboard
./bin/breadboard -Dsbt.log.noformat=true -Dhttps.port=9443 -Dapplication.secret="pleasechangethis" -Dconfig.file=conf/application-prod.conf -J-XX:+CMSClassUnloadingEnabled -J-XX:+UseConcMarkSweepGC
