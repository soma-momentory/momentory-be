ALTER TABLE user_profiles
    ADD COLUMN other_interest_detail VARCHAR(50),
    ADD COLUMN other_rest_method_detail VARCHAR(50),
    ADD COLUMN notification_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE user_rest_methods (
    user_id BIGINT NOT NULL,
    rest_method VARCHAR(50) NOT NULL,
    CONSTRAINT pk_user_rest_methods PRIMARY KEY (user_id, rest_method),
    CONSTRAINT fk_user_rest_methods_user
        FOREIGN KEY (user_id) REFERENCES user_profiles (user_id)
);
