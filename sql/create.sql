CREATE TABLE gav(
    group_id varchar(128) not null,
    artifact_id varchar(128) not null,
    version varchar(128) not null,
    classifier varchar(128) not null,
    file_extension varchar(64) not null,
    artifact_version varchar(64) null,
    last_modified datetime,
    sha1 varchar(128),
    sources_exists int not null,
    javadoc_exists int not null,
    signature_exists int not null,
    size bigint not null,
    packaging varchar(128) null,
    name varchar(256),
    description varchar(16384),
    primary key (group_id, artifact_id, version, classifier, file_extension)
) engine=InnoDB;

