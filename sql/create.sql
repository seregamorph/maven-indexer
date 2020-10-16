CREATE TABLE gav(
    -- case sensitive
    uinfo varchar(256) CHARACTER SET utf8 COLLATE utf8_bin NOT NULL,

    group_id varchar(128) not null,
    artifact_id varchar(128) not null,
    version varchar(128) not null,
    classifier varchar(128) not null,
    file_extension varchar(128) not null,

    artifact_version varchar(128) null,
    last_modified datetime not null,
    -- there are records longer than 40 chars, we replace it with NULL
    sha1 varchar(40) null,
    sources_exists int not null,
    javadoc_exists int not null,
    signature_exists int not null,
    size bigint not null,
    packaging varchar(128) null,
    name varchar(256),
    description varchar(16384)
) engine=InnoDB;

-- note: this index cannot be unique
create index gav_primary on gav(group_id, artifact_id, version, classifier, file_extension);

-- note: this index cannot be unique
create index gav_uinfo on gav(uinfo);

