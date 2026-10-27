# Experiment.fileMode has been in the model for a long time, but no evolution ever created
# the column. Released installs only worked because they ship a pre-built database.

# --- !Ups

alter table experiments add column if not exists file_mode bit default 0;

# --- !Downs

alter table experiments drop column if exists file_mode;
