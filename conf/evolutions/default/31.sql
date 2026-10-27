# Autopilot settings saved from the console (GET/POST /autopilot, models.AutopilotSettings). One row:
# the script engine is shared, so only one experiment runs unattended at a time. "if not exists"
# because installs that run with evolutions off create the table at first use (same definition).

# --- !Ups

create table if not exists autopilot_settings (
  id                          bigint not null,
  enabled                     boolean default false not null,
  experiment_id               bigint,
  operator_email              varchar(255),
  continuous                  boolean default true not null,
  next_session_delay_seconds  integer default 10 not null,
  waiting_room_enabled        boolean default true not null,
  min_players                 integer default 2 not null,
  max_players                 integer,
  wait_seconds                integer default 60 not null,
  max_wait_seconds            integer,
  countdown_seconds           integer default 5 not null,
  replace_enabled             boolean default true not null,
  replace_after_seconds       integer default 30 not null,
  return_to_human             boolean default true not null,
  updated_at                  timestamp,
  constraint pk_autopilot_settings primary key (id)
);

# --- !Downs

drop table if exists autopilot_settings;
