# Why the autopilot opens no session by itself (models.AutopilotSettings.Halt): a researcher ended
# its session (paused), or its one session finished (done). Kept so a restart doesn't quietly start
# recruiting again. Its own table: when application.conf runs the autopilot there is no row in
# autopilot_settings. "if not exists" because installs that run with evolutions off create the table
# at first use (same definition).

# --- !Ups

create table if not exists autopilot_halt (
  id                          bigint not null,
  experiment_id               bigint,
  paused_because              varchar(2000),
  done_because                varchar(2000),
  updated_at                  timestamp,
  constraint pk_autopilot_halt primary key (id)
);

# --- !Downs

drop table if exists autopilot_halt;
