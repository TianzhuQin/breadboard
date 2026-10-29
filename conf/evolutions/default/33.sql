# An index on the names of event data, so that finding a Prolific participant's earlier sessions
# (models.Recruitment.prolificCompletion: the prolificPid data of prolificEntry events) reads only
# those rows, not every event of the experiment. On the name alone: values can be long. Also made at
# startup (Global), for databases from older releases.

# --- !Ups

create index if not exists ix_event_data_name on event_data (name);

# --- !Downs

drop index if exists ix_event_data_name;
