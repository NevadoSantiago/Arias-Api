-- State of the in-app Google review card: PENDING, OPENED or NEVER.
ALTER TABLE users
    ADD COLUMN review_prompt_status VARCHAR(10) NOT NULL DEFAULT 'PENDING';
