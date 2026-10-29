# The longest the game of the autopilot's own session may run, in minutes, before the autopilot ends
# it (models.AutopilotSettings.maxSessionMinutes, Autopilot.watchdog); null: no limit. Settings saved
# before this get the default, 120. Also made at startup (Global), for databases from older releases,
# and in the table the autopilot creates at first use (AutopilotSettings.CREATE_TABLE).

# --- !Ups

alter table autopilot_settings add column if not exists max_session_minutes integer default 120;

# --- !Downs

alter table autopilot_settings drop column if exists max_session_minutes;
