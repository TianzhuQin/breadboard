# The longest the game of the autopilot's own session may run, in minutes, before the autopilot ends
# it (models.AutopilotSettings.maxSessionMinutes, Autopilot.watchdog); null: no limit. No default:
# settings saved before this keep no limit (new settings start with 120 minutes, from the console).
# Also made at startup (Global, AutopilotSettings.ADD_LONGEST_SESSION, the same statement), for
# databases from older releases, and in the table the autopilot creates at first use
# (AutopilotSettings.CREATE_TABLE).

# --- !Ups

alter table autopilot_settings add column if not exists max_session_minutes integer;

# --- !Downs

alter table autopilot_settings drop column if exists max_session_minutes;
