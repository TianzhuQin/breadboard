# The language of a session's texts, as its steps chose it with c.setDefaultLanguage
# (models.SessionLanguage): Breadboard's own words to the session's participants follow it (English or
# Chinese, models.SystemTexts). "" when the steps chose none; null when nothing was recorded (a session
# from an older release). Also made at startup (Global.addContentLanguage, SessionLanguage.ADD_COLUMN,
# the same statement), for databases from older releases.

# --- !Ups

alter table experiment_instances add column if not exists content_language varchar(16);

# --- !Downs

alter table experiment_instances drop column if exists content_language;
