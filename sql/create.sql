CREATE TABLE gav(
    group_id varchar(64) not null,
    artifact_id varchar(64) not null,
    version varchar(64) not null,
    artifact_version varchar(64) null,
    last_modified datetime,
    sha1 varchar(40),
    sources_exists boolean not null,
    javadoc_exists boolean not null,
    signature_exists boolean not null,
    size bigint not null,
    file_extension varchar(32),
    classifier varchar(32) null,
    packaging varchar(32) not null,
    name varchar(256),
    description varchar(4096),
    primary key (group_id, artifact_id, version)
) engine=InnoDB;

