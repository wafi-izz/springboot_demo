CREATE SCHEMA IF NOT EXISTS user_management;

CREATE TABLE user_management.users (
    id          BIGSERIAL       PRIMARY KEY,
    first_name  VARCHAR(255)    NOT NULL,
    last_name   VARCHAR(255)    NOT NULL,
    email       VARCHAR(255)    NOT NULL,
    username    VARCHAR(255)    NOT NULL,
    password    VARCHAR(255)    NOT NULL,
    role        VARCHAR(255)    NOT NULL
);