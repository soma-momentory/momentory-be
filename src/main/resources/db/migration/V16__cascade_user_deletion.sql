ALTER TABLE oauth_accounts
    DROP CONSTRAINT fk_oauth_accounts_user,
    ADD CONSTRAINT fk_oauth_accounts_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE refresh_tokens
    DROP CONSTRAINT fk_refresh_tokens_user,
    ADD CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE user_profiles
    DROP CONSTRAINT fk_user_profiles_user,
    ADD CONSTRAINT fk_user_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE user_interest_areas
    DROP CONSTRAINT fk_user_interest_areas_user,
    ADD CONSTRAINT fk_user_interest_areas_user
        FOREIGN KEY (user_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE;

ALTER TABLE user_rest_methods
    DROP CONSTRAINT fk_user_rest_methods_user,
    ADD CONSTRAINT fk_user_rest_methods_user
        FOREIGN KEY (user_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE;

ALTER TABLE schedules
    DROP CONSTRAINT fk_schedules_user,
    ADD CONSTRAINT fk_schedules_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE daily_memos
    DROP CONSTRAINT fk_daily_memos_user,
    ADD CONSTRAINT fk_daily_memos_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE retrospects
    DROP CONSTRAINT fk_retrospects_user,
    ADD CONSTRAINT fk_retrospects_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE action_cards
    DROP CONSTRAINT fk_action_cards_user,
    ADD CONSTRAINT fk_action_cards_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE diaries
    DROP CONSTRAINT fk_diaries_user,
    ADD CONSTRAINT fk_diaries_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
